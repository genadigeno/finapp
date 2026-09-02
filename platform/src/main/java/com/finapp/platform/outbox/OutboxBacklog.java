package com.finapp.platform.outbox;

import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * How much is waiting in the outbox, and how long the oldest thing has been waiting.
 *
 * <p>ADR-0005 names outbox depth and age as first-class monitored metrics, and this is the half of
 * that which can be measured <strong>without a relay running</strong> — because it is a question
 * about the table, not about the relay. That distinction is the point rather than a convenience:
 *
 * <ul>
 *   <li>A counter incremented by the relay reports nothing when the relay is <em>down</em>, which
 *       is precisely the incident an operator needs to see. A backlog that is growing because
 *       nothing is draining it looks, to a relay-side counter, exactly like a quiet afternoon.
 *   <li>Depth alone is ambiguous — a thousand events published within the second is healthy. Age
 *       is what separates "busy" from "stuck", so the two are read together and are measured
 *       together here.
 * </ul>
 *
 * <p><strong>Not a financial reading.</strong> The outbox is transport ({@code INV-EVT-02}); these
 * numbers describe publication lag, never money. Nothing may decide anything financial from them.
 *
 * <h2>Cost</h2>
 *
 * <p>Both figures come from one statement over the partial index on unpublished rows
 * ({@code V005}), so the scan is proportional to the backlog rather than to the table — which
 * matters because the table only grows: published rows are never deleted, and their retention
 * sweep is recorded debt owned by Phase 15.
 */
public final class OutboxBacklog {

    /**
     * Depth and age in one statement.
     *
     * <p>One statement rather than two, so the pair cannot describe two different instants. Two
     * queries a few milliseconds apart can report a depth of zero beside a non-zero age, which is
     * a state that never existed and which an operator would reasonably spend an hour explaining.
     *
     * <p>Age comes back as whole seconds, cast in SQL: {@code INV-MON-01} forbids floating point
     * on any production path, and a duration measured in seconds gains nothing from a double.
     *
     * <p>Age is measured by the <strong>server's</strong> clock, on the same reasoning as
     * {@code V004} and {@code V006}: an age computed by subtracting a server timestamp from a
     * client's clock measures the difference between two machines as much as the age of a row.
     */
    private static final String BACKLOG_SQL =
            """
            SELECT count(*) AS pending,
                   coalesce(extract(epoch FROM now() - min(occurred_at))::bigint, 0) AS oldest_seconds
              FROM platform.outbox_event
             WHERE published_at IS NULL
            """;

    private final OutboxConnectionSource connections;

    public OutboxBacklog(OutboxConnectionSource connections) {
        if (connections == null) {
            throw new IllegalArgumentException("connections must not be null");
        }
        this.connections = connections;
    }

    /** @throws OutboxReadException if the backlog cannot be read */
    public Reading read() {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(BACKLOG_SQL);
                ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                // count(*) always returns a row. If this ever happens the query is not the query
                // that was written, and reporting zero would be a lie an operator would act on.
                throw new OutboxReadException("the outbox backlog query returned no row");
            }
            long pending = results.getLong("pending");
            // Cast to bigint in SQL and read as a long, rather than reading a double and
            // flooring it. INV-MON-01 forbids floating point anywhere on a production path, and
            // the rule was right to catch this: a whole number of seconds has no business being
            // carried in a double, and letting one in "because it is only a metric" is how the
            // habit spreads to something that is not.
            long oldestSeconds = results.getLong("oldest_seconds");
            return new Reading(pending, Duration.ofSeconds(oldestSeconds));
        } catch (SQLException e) {
            throw new OutboxReadException("Could not read the outbox backlog", e);
        }
    }

    /**
     * @param pending unpublished events, including any that are dead-lettered and therefore
     *     blocking their aggregate
     * @param oldest how long the oldest unpublished event has been waiting; {@link Duration#ZERO}
     *     when there is nothing pending
     */
    public record Reading(long pending, Duration oldest) {

        public Reading {
            if (pending < 0) {
                throw new IllegalArgumentException("pending must not be negative but was " + pending);
            }
            if (oldest == null || oldest.isNegative()) {
                throw new IllegalArgumentException("oldest must be a non-negative duration");
            }
        }
    }

    /** Reading the backlog failed. Distinct from an empty backlog, which is a valid reading. */
    public static final class OutboxReadException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        OutboxReadException(String message) {
            super(message);
        }

        OutboxReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
