package com.finapp.platform.abuse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A cluster-wide count per key and fixed window, arbitrated by PostgreSQL (`P15-TSK-022`, {@code INV-CON-03}).
 *
 * <p><strong>The whole protocol is one statement.</strong> {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE admitted < limit
 * RETURNING}: the row's lock orders the writers and the condition is evaluated on the latest version under it, so exactly the
 * limit is admitted across every replica. A per-instance count would admit the limit times the instance count.
 *
 * <p><strong>The window is the database's.</strong> {@code date_bin} over {@code now()}, inside the statement, and the
 * {@code Retry-After} from the same reading. This class holds no clock and binds no instant.
 *
 * <p><strong>What a count costs.</strong> One round trip, one statement in its own transaction on its own short borrow of a pooled
 * connection, returned before the caller does anything else; on an unlogged table, so it adds no WAL. A request past the limit
 * does not rewrite its row: the condition is false, so the {@code UPDATE} writes nothing. At most {@code maxConcurrent} counts
 * run at once on this instance, and one past that is refused at once and never queued behind the pool the rest of the instance
 * needs. That cap is this instance's and protects this instance's connections: it coordinates nothing between instances and no
 * correctness rests on it, which is why it is an instance field and not a {@code Semaphore} (the {@code CallbackInquiryBulkhead}
 * reasoning).
 *
 * <p><strong>Fails closed.</strong> A count that could not be taken is a
 * {@link WindowCounterUnavailableException}, never an admission. Nothing here can answer "admitted" without PostgreSQL having
 * counted it.
 *
 * <p>Scope and key are the caller's: a door's per-source count is one scope, and a count per identity and surface would be
 * another. Whoever chooses a key also bounds it: the table holds one row per key per window.
 */
public final class WindowCounter {

    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z0-9.-]{0,62}");

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final String COUNT =
            """
            WITH bounds AS MATERIALIZED (
                SELECT date_bin(w.stride, now(), TIMESTAMPTZ 'epoch') AS window_start,
                       w.stride,
                       now() AS read_at
                  FROM (SELECT ?::bigint * INTERVAL '1 second' AS stride) w
            ),
            counted AS (
                INSERT INTO platform.window_counter AS c (scope, counter_key, window_start, window_ends_at, admitted)
                SELECT ?::text, ?::text, b.window_start, b.window_start + b.stride, 1
                  FROM bounds b
                ON CONFLICT (scope, counter_key, window_start) DO UPDATE
                    SET admitted = c.admitted + 1
                  WHERE c.admitted < ?
                RETURNING c.admitted
            )
            SELECT (SELECT admitted FROM counted) AS admitted,
                   CEIL(EXTRACT(EPOCH FROM (b.window_start + b.stride - b.read_at)))::bigint AS retry_after
              FROM bounds b
            """;

    private final CounterConnections connections;
    private final int maxConcurrent;
    private final AtomicInteger running = new AtomicInteger();

    /**
     * @param connections where a count borrows its connection
     * @param maxConcurrent how many counts may run at once on this instance, at least 1
     */
    public WindowCounter(CounterConnections connections, int maxConcurrent) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1 but was " + maxConcurrent);
        }
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * Counts one request against its key's current window.
     *
     * @return admitted, or refused with how long until the window ends
     * @throws WindowCounterUnavailableException if the count could not be taken: the caller refuses the request
     */
    public Admission admit(String scope, String key, WindowLimit limit) {
        requireShape(SCOPE, scope, "scope");
        requireShape(KEY, key, "key");
        Objects.requireNonNull(limit, "limit must not be null");
        if (running.incrementAndGet() > maxConcurrent) {
            running.decrementAndGet();
            throw new WindowCounterUnavailableException("This instance is already running its maximum of counts", null);
        }
        try {
            return count(scope, key, limit);
        } finally {
            running.decrementAndGet();
        }
    }

    private Admission count(String scope, String key, WindowLimit limit) {
        try (Connection connection = connections.open()) {
            // Stated: a pool configured with autocommit off would roll the count back when this connection is returned, and every
            // request would then be counted as the first in its window.
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(COUNT)) {
                statement.setLong(1, limit.windowSeconds());
                statement.setString(2, scope);
                statement.setString(3, key);
                statement.setInt(4, limit.limit());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("The count returned no row");
                    }
                    result.getInt("admitted");
                    boolean admitted = !result.wasNull();
                    return new Admission(admitted, Math.max(1, result.getLong("retry_after")));
                }
            }
        } catch (SQLException failure) {
            throw new WindowCounterUnavailableException("The count could not be taken", failure.getSQLState());
        }
    }

    private static void requireShape(Pattern shape, String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (!shape.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " does not have the counter's shape");
        }
    }
}
