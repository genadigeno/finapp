package com.finapp.platform.abuse;

import com.finapp.platform.abuse.WindowCounterUnavailableException.Reason;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
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
 * <p><strong>What a count costs.</strong> One round trip, one statement in its own transaction on its own borrow of a pooled
 * connection, returned before the caller does anything else; on an unlogged table, so the row writes no WAL and the commit waits on
 * neither a flush nor a standby. A request past the limit is read-only: the {@code NOT EXISTS} finds the window spent, so nothing
 * is inserted, no row is locked and no transaction id is taken. A race past that reading meets the {@code UPDATE}'s own condition,
 * which is what makes the count exact: the {@code NOT EXISTS} is only the cheap path.
 *
 * <p><strong>What bounds a count.</strong> At most {@code maxConcurrent} run at once on this instance. One past that waits up to
 * {@code permitWait} for a place and is then refused, and it never queues behind the pool the rest of the instance needs. The
 * cap is this instance's and protects this instance's connections: it coordinates nothing between instances and no correctness
 * rests on it, which is why it is an instance field and not a {@code Semaphore} (the {@code CallbackInquiryBulkhead} reasoning);
 * the wait is a sleep poll for the same reason. A count holds its place for the pool's own wait and then at most
 * {@link #NETWORK_TIMEOUT_MILLIS}: without that a black-holed read, a failover's old primary, never returns and never gives its
 * place back, and enough of them would close every door until a restart while the rest of the API recovered.
 *
 * <p><strong>Fails closed.</strong> A count that could not be taken is a
 * {@link WindowCounterUnavailableException}, never an admission. Nothing here can answer "admitted" without PostgreSQL having
 * counted it.
 *
 * <p>Scope and key are the caller's: a door's per-source count is one scope, and a count per identity and surface would be
 * another. Whoever chooses a key also bounds it: the table holds one row per key per window.
 */
public final class WindowCounter {

    /**
     * How long a count's connection may go unanswered. The data source sets no {@code socketTimeout}. A count takes about a
     * millisecond, so this is a verdict on the connection, not on the statement. HikariCP puts the connection's own value back when
     * it is returned.
     */
    public static final int NETWORK_TIMEOUT_MILLIS = 2_000;

    private static final Executor ON_THE_CALLERS_THREAD = Runnable::run;

    private static final long POLL_MILLIS = 5;

    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z0-9.-]{0,62}");

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private static final String COUNT =
            """
            WITH bounds AS MATERIALIZED (
                SELECT date_bin(w.stride, now(), TIMESTAMPTZ 'epoch') AS window_start,
                       w.stride,
                       w.scope,
                       w.counter_key,
                       w.ceiling,
                       now() AS read_at
                  FROM (SELECT ?::bigint * INTERVAL '1 second' AS stride,
                               ?::text AS scope,
                               ?::text AS counter_key,
                               ?::integer AS ceiling) w
            ),
            counted AS (
                INSERT INTO platform.window_counter AS c (scope, counter_key, window_start, window_ends_at, admitted)
                SELECT b.scope, b.counter_key, b.window_start, b.window_start + b.stride, 1
                  FROM bounds b
                 WHERE NOT EXISTS (
                           SELECT 1
                             FROM platform.window_counter spent
                            WHERE spent.scope = b.scope
                              AND spent.counter_key = b.counter_key
                              AND spent.window_start = b.window_start
                              AND spent.admitted >= b.ceiling
                       )
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
    private final long waitPolls;
    private final AtomicInteger running = new AtomicInteger();

    /**
     * @param connections where a count borrows its connection
     * @param maxConcurrent how many counts may run at once on this instance, at least 1
     * @param permitWait how long a count past the cap waits for a place before it is refused; zero refuses at once
     */
    public WindowCounter(CounterConnections connections, int maxConcurrent, Duration permitWait) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1 but was " + maxConcurrent);
        }
        Objects.requireNonNull(permitWait, "permitWait must not be null");
        if (permitWait.isNegative()) {
            throw new IllegalArgumentException("permitWait must not be negative but was " + permitWait);
        }
        this.maxConcurrent = maxConcurrent;
        this.waitPolls = permitWait.toMillis() / POLL_MILLIS;
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
        if (!enter()) {
            throw new WindowCounterUnavailableException("This instance is already running its maximum of counts", Reason.AT_CAPACITY, null);
        }
        try {
            return count(scope, key, limit);
        } finally {
            running.decrementAndGet();
        }
    }

    private boolean enter() {
        for (long polls = waitPolls; ; polls--) {
            if (running.incrementAndGet() <= maxConcurrent) {
                return true;
            }
            running.decrementAndGet();
            if (polls <= 0 || !pausedOnePoll()) {
                return false;
            }
        }
    }

    private static boolean pausedOnePoll() {
        try {
            Thread.sleep(POLL_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Admission count(String scope, String key, WindowLimit limit) {
        try (Connection connection = connections.open()) {
            connection.setNetworkTimeout(ON_THE_CALLERS_THREAD, NETWORK_TIMEOUT_MILLIS);
            // Stated: a pool configured with autocommit off would roll the count back when this connection is returned, and every
            // request would then be counted as the first in its window.
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(COUNT)) {
                statement.setLong(1, limit.windowSeconds());
                statement.setString(2, scope);
                statement.setString(3, key);
                statement.setInt(4, limit.limit());
                statement.setInt(5, limit.limit());
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
            throw new WindowCounterUnavailableException("The count could not be taken", reasonFor(failure.getSQLState()), failure.getSQLState());
        }
    }

    /** Connection trouble, a server out of resources or going down, and a pool that timed out (no SQLState) are one cause; the rest is the statement's. */
    private static Reason reasonFor(String sqlState) {
        if (sqlState == null || sqlState.startsWith("08") || sqlState.startsWith("53") || sqlState.startsWith("57")) {
            return Reason.UNREACHABLE;
        }
        return Reason.STATEMENT_FAILED;
    }

    private static void requireShape(Pattern shape, String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (!shape.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " does not have the counter's shape");
        }
    }
}
