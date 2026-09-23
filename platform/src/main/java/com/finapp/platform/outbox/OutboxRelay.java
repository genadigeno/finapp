package com.finapp.platform.outbox;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes what the outbox holds: the other half of ADR-0005.
 *
 * <p>{@link OutboxWriter} guarantees that a committed fact always has a publication record.
 * This class guarantees that a publication record always ends up published. Neither is worth
 * much without the other — a fact recorded and never announced is as invisible to downstream
 * systems as one that was never recorded at all.
 *
 * <h2>Delivery is at least once</h2>
 *
 * <p>The relay publishes, then records publication, and there is no transaction spanning the
 * two because one of them is a broker. A crash in between republishes the event on restart.
 * That is a deliberate choice and not a gap: the alternative ordering — record first, then
 * publish — loses the event instead, and losing is worse than repeating. Consumers deduplicate
 * on {@code event_id} ({@code INV-IDEM-04}, P0-TSK-021), which is why that identifier is minted
 * with the event and never regenerated here.
 *
 * <p><strong>Nothing in this class may be described as exactly-once.</strong> What is
 * exactly-once is the <em>effect</em> at a deduplicating consumer, and that is the consumer's
 * property, not the relay's.
 *
 * <h2>Running as N instances (ADR-0014)</h2>
 *
 * <p>The relay is the platform's first scheduled component, so the question is not whether two
 * instances will poll at the same instant but what happens when they do. Every instance runs
 * the poller; there is no leader, no "primary", and no configuration naming which node is the
 * real one, because every such arrangement is a single point of failure wearing a distributed
 * costume.
 *
 * <p>Safety comes from a <strong>transaction-scoped advisory lock per aggregate</strong>. An
 * instance claims an aggregate, drains it in event order, and releases the lock at commit. A
 * second instance wanting the same aggregate is refused immediately and moves to the next
 * candidate rather than waiting, so instances spread across the backlog instead of queueing
 * behind one another.
 *
 * <p>The lock is per <em>aggregate</em> rather than per row, and that is what makes the ordering
 * guarantee survive concurrency. Row-level claiming — {@code SELECT ... FOR UPDATE SKIP LOCKED},
 * the usual answer — lets one instance take event 1 and another take event 2 of the same
 * aggregate, and whichever finishes first publishes first. Ordering would then hold only while
 * the relay happened to be running as a single instance, which is exactly the assumption
 * ADR-0014 exists to remove.
 *
 * <p>Two aggregates whose lock keys collide simply serialise against each other. Harmless: each
 * is still published in order, by one instance at a time.
 *
 * <h2>Ordering under failure</h2>
 *
 * <p>Within an aggregate the relay stops at the first event it cannot publish — failed, backing
 * off, or abandoned — and leaves the rest pending. Publishing event 3 while event 2 is still
 * unpublished would give ordering on the happy path and nothing anywhere else, which is the
 * same as not having it.
 *
 * <h2>Scheduling</h2>
 *
 * <p>{@link #pollOnce()} is one cycle and returns what it did. It does not sleep, own a thread,
 * or know what a scheduler is: the driver belongs to the composition root, which is where a
 * framework may be chosen, and a relay owning its own timer could not be tested without one.
 * Calling it concurrently from any number of threads or instances is safe by the same mechanism
 * that makes multiple instances safe.
 */
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
@Slf4j
public final class OutboxRelay {

    private static final String TABLE = "platform.outbox_event";

    /**
     * The advisory-lock namespace for outbox aggregates.
     *
     * <p>PostgreSQL advisory locks share one cluster-wide space, so an unqualified key would
     * collide with any other feature that ever takes one — silently, and only under load. The
     * two-argument form partitions that space; this constant reserves a partition for the relay,
     * and any future component taking advisory locks reserves its own rather than reusing this.
     */
    private static final int AGGREGATE_LOCK_NAMESPACE = 1;

    /** Matches {@code outbox_event_last_error_bounded} in {@code V006}. */
    private static final int MAX_LAST_ERROR_LENGTH = 1000;

    /**
     * How many more candidate aggregates to read than this cycle will drain.
     *
     * <p>Every instance's candidate query returns the same oldest-first list, so with no
     * overscan they would all contend for the same few aggregates and all but one would come
     * back with nothing. Reading further down the backlog gives each instance somewhere else to
     * go the moment it is refused a lock.
     */
    private static final int CANDIDATE_OVERSCAN = 4;

    /**
     * Aggregates whose oldest pending event is due now, oldest first.
     *
     * <p>{@code DISTINCT ON} takes each aggregate's <em>head</em> — the event that must go next
     * — so an aggregate blocked behind a backing-off or abandoned event is not offered as a
     * candidate at all. Filtering on any pending row rather than the head would keep handing out
     * aggregates that cannot make progress.
     *
     * <p>There is no sequence watermark here, deliberately; {@code V005} records why one would
     * skip committed rows permanently.
     */
    private static final String CANDIDATE_AGGREGATES_SQL =
            "SELECT head.aggregate_id FROM ("
                    + "  SELECT DISTINCT ON (aggregate_id) aggregate_id, event_id, "
                    + "         next_attempt_at, dead_lettered_at"
                    + "  FROM " + TABLE
                    + "  WHERE published_at IS NULL"
                    + "  ORDER BY aggregate_id, event_id"
                    + ") head "
                    + "WHERE head.dead_lettered_at IS NULL AND head.next_attempt_at <= now() "
                    + "ORDER BY head.event_id LIMIT ?";

    /**
     * One aggregate's pending events, in order.
     *
     * <p>Abandoned and not-yet-due rows are selected rather than filtered out, because the relay
     * must <em>stop</em> at them. A query that hid them would hand back the events behind a
     * blocked one and the relay would publish them out of order without ever knowing.
     *
     * <p>{@code due} is computed by the server: whether a row is eligible is a comparison
     * against the clock every instance shares, never against the caller's (ADR-0014).
     */
    private static final String PENDING_FOR_AGGREGATE_SQL =
            "SELECT event_id, event_type, event_version, schema_version, aggregate_id, "
                    + "aggregate_type, occurred_at, producer, correlation_id, causation_id, "
                    + "payload, payload_media_type, attempts, "
                    + "dead_lettered_at IS NOT NULL AS abandoned, "
                    + "next_attempt_at <= now() AS due "
                    + "FROM " + TABLE + " WHERE aggregate_id = ? AND published_at IS NULL "
                    + "ORDER BY event_id LIMIT ?";

    /**
     * Records a successful publication.
     *
     * <p>Conditional on the row still being unpublished rather than read-then-written: a
     * conditional {@code UPDATE} is the only form that cannot lose to a concurrent writer
     * ({@code DISTRIBUTED_EXECUTION.md} §5). {@code now()} is the server's clock, for the same
     * reason as everything else on this path.
     */
    private static final String MARK_PUBLISHED_SQL =
            "UPDATE " + TABLE + " SET published_at = now(), attempts = attempts + 1, "
                    + "last_error = NULL WHERE event_id = ? AND published_at IS NULL";

    /**
     * Records a failed attempt and schedules the retry.
     *
     * <p>Exhaustion is evaluated in SQL against the stored attempt count rather than against the
     * value this instance read, so the decision to abandon a row is made from the row's own state
     * and not from a snapshot that may be older than it looks.
     */
    private static final String MARK_FAILED_SQL =
            "UPDATE " + TABLE + " SET attempts = attempts + 1, last_error = ?, "
                    + "next_attempt_at = now() + (? * INTERVAL '1 millisecond'), "
                    + "dead_lettered_at = CASE WHEN attempts + 1 >= ? THEN now() ELSE NULL END "
                    + "WHERE event_id = ? AND published_at IS NULL";

    private final OutboxConnectionSource connections;
    private final EventPublisher publisher;
    private final RetryPolicy retryPolicy;
    private final int aggregatesPerPoll;
    private final int eventsPerAggregate;

    /** Uses {@link RetryPolicy#DEFAULT} and modest batch sizes. */
    public OutboxRelay(OutboxConnectionSource connections, EventPublisher publisher) {
        this(connections, publisher, RetryPolicy.DEFAULT, 8, 100);
    }

    /**
     * @param aggregatesPerPoll how many aggregates one cycle drains; bounds how long a cycle
     *     holds locks, and therefore how quickly a stuck instance stops mattering
     * @param eventsPerAggregate how many of one aggregate's events one cycle publishes; bounds
     *     how far a busy aggregate can starve the others in the batch
     */
    public OutboxRelay(
            OutboxConnectionSource connections,
            EventPublisher publisher,
            RetryPolicy retryPolicy,
            int aggregatesPerPoll,
            int eventsPerAggregate) {

        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        if (aggregatesPerPoll < 1) {
            throw new IllegalArgumentException(
                    "aggregatesPerPoll must be at least 1 but was " + aggregatesPerPoll);
        }
        if (eventsPerAggregate < 1) {
            throw new IllegalArgumentException(
                    "eventsPerAggregate must be at least 1 but was " + eventsPerAggregate);
        }
        this.aggregatesPerPoll = aggregatesPerPoll;
        this.eventsPerAggregate = eventsPerAggregate;
    }

    /**
     * Runs one poll cycle.
     *
     * @return what this cycle did; never null
     * @throws OutboxRelayException if the database could not be reached or a statement failed.
     *     Nothing is lost when that happens — the rows are still pending and the next cycle
     *     takes them.
     */
    public RelayPollResult pollOnce() {
        List<UUID> candidates = candidateAggregates();
        if (candidates.isEmpty()) {
            return RelayPollResult.idle();
        }

        int drained = 0;
        int published = 0;
        int failed = 0;
        int deadLettered = 0;
        for (UUID aggregateId : candidates) {
            if (drained == aggregatesPerPoll) {
                break;
            }
            AggregateOutcome outcome = drainAggregate(aggregateId);
            if (!outcome.locked()) {
                // Another instance holds it. Not a failure: it is the mechanism working.
                continue;
            }
            drained++;
            published += outcome.published();
            failed += outcome.failed();
            deadLettered += outcome.deadLettered();
        }
        return new RelayPollResult(drained, published, failed, deadLettered);
    }

    // -----------------------------------------------------------------
    // One aggregate, in its own transaction
    // -----------------------------------------------------------------

    private AggregateOutcome drainAggregate(UUID aggregateId) {
        try (Connection connection = connections.open()) {
            // Not restored afterwards, deliberately. JDBC commits the open transaction when
            // auto-commit is switched back on, so a `finally` that restored it would turn every
            // error path into a commit — the exact opposite of the intended response, and
            // invisible until the day something throws. The connection is closed immediately
            // after; a pool resets it on return.
            connection.setAutoCommit(false);
            try {
                if (!lockAggregate(connection, aggregateId)) {
                    // Ends the transaction so the connection is clean for whoever gets it next.
                    connection.rollback();
                    return AggregateOutcome.NOT_LOCKED;
                }
                AggregateOutcome outcome = drainLockedAggregate(connection, aggregateId);
                // Commits the attempt records, and releases the advisory lock with them.
                connection.commit();
                return outcome;
            } catch (Throwable t) {
                // Throwable rather than Exception: an Error — an OutOfMemoryError, a test's
                // simulated crash — must not leave this transaction open on its way out.
                connection.rollback();
                throw t;
            }
        } catch (SQLException e) {
            throw new OutboxRelayException("Outbox relay could not drain aggregate " + aggregateId, e);
        }
    }

    private AggregateOutcome drainLockedAggregate(Connection connection, UUID aggregateId)
            throws SQLException {

        int published = 0;
        int failed = 0;
        int deadLettered = 0;
        for (PendingRow row : pendingFor(connection, aggregateId)) {
            PendingEvent event = row.event();
            // The scope covers everything said about this event, not only its publication.
            // Scoping just the publish left every failure line — including the abandonment
            // error, the loudest thing the relay ever says — with no correlation on it, because
            // a catch block attached to a try-with-resources runs after the resource is closed.
            // A warning that an event could not be published, which cannot be joined to the
            // transfer that produced it, does not answer the only question its reader has.
            try (CorrelationContext.Scope ignored =
                    CorrelationContext.enter(
                            new Correlation(event.correlationId(), event.causationId()))) {

                if (row.abandoned()) {
                    // The head of what remains has been given up on. Everything behind it waits,
                    // by design: see V006.
                    log.warn(
                            "Outbox aggregate {} is blocked behind abandoned event {}; it will not "
                                    + "publish again until that event is resolved",
                            aggregateId,
                            event.eventId().value());
                    break;
                }
                if (!row.due()) {
                    break;
                }

                try {
                    publisher.publish(event);
                    markPublished(connection, event.eventId());
                    published++;
                } catch (Exception e) {
                    // Any exception, not only the expected one: an adapter failing with a
                    // NullPointerException must cost one event a retry, not stop the relay.
                    if (recordFailure(connection, event, e)) {
                        deadLettered++;
                    } else {
                        failed++;
                    }
                    // Ordering: nothing behind a failed event may go ahead of it.
                    break;
                }
            }
        }
        return new AggregateOutcome(true, published, failed, deadLettered);
    }

    /**
     * Claims the aggregate for this transaction, or reports that someone else holds it.
     *
     * <p>{@code pg_try_advisory_xact_lock} rather than the blocking form: waiting would tie this
     * instance to another instance's broker latency, and there is always other work in the
     * backlog. The transaction-scoped variant rather than the session-scoped one because a
     * session lock outlives a crash of the code that was supposed to release it, and a relay
     * that leaks locks stops publishing an aggregate forever.
     */
    private boolean lockAggregate(Connection connection, UUID aggregateId) throws SQLException {
        try (PreparedStatement lock =
                connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
            lock.setInt(1, AGGREGATE_LOCK_NAMESPACE);
            lock.setInt(2, aggregateId.hashCode());
            try (ResultSet result = lock.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private List<UUID> candidateAggregates() {
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(CANDIDATE_AGGREGATES_SQL)) {
            select.setInt(1, aggregatesPerPoll * CANDIDATE_OVERSCAN);
            try (ResultSet rows = select.executeQuery()) {
                List<UUID> aggregates = new ArrayList<>();
                while (rows.next()) {
                    aggregates.add(rows.getObject("aggregate_id", UUID.class));
                }
                return aggregates;
            }
        } catch (SQLException e) {
            throw new OutboxRelayException("Outbox relay could not read pending aggregates", e);
        }
    }

    private List<PendingRow> pendingFor(Connection connection, UUID aggregateId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(PENDING_FOR_AGGREGATE_SQL)) {
            select.setObject(1, aggregateId);
            select.setInt(2, eventsPerAggregate);
            try (ResultSet rows = select.executeQuery()) {
                List<PendingRow> pending = new ArrayList<>();
                while (rows.next()) {
                    pending.add(readRow(rows));
                }
                return pending;
            }
        }
    }

    private static PendingRow readRow(ResultSet rows) throws SQLException {
        PendingEvent event =
                new PendingEvent(
                        EventId.of(rows.getObject("event_id", UUID.class)),
                        rows.getString("event_type"),
                        rows.getInt("event_version"),
                        rows.getInt("schema_version"),
                        rows.getObject("aggregate_id", UUID.class),
                        rows.getString("aggregate_type"),
                        rows.getTimestamp("occurred_at").toInstant(),
                        rows.getString("producer"),
                        CorrelationId.of(rows.getString("correlation_id")),
                        CausationId.of(rows.getString("causation_id")),
                        rows.getBytes("payload"),
                        rows.getString("payload_media_type"),
                        rows.getInt("attempts"));
        return new PendingRow(event, rows.getBoolean("due"), rows.getBoolean("abandoned"));
    }

    private static void markPublished(Connection connection, EventId eventId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(MARK_PUBLISHED_SQL)) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
    }

    /**
     * Records a failed attempt.
     *
     * @return whether this failure exhausted the row's attempts
     */
    private boolean recordFailure(Connection connection, PendingEvent event, Exception failure)
            throws SQLException {

        int attemptsAfter = event.attempts() + 1;
        boolean exhausted = retryPolicy.isExhausted(attemptsAfter);
        Duration backoff = retryPolicy.backoffAfter(attemptsAfter);

        try (PreparedStatement update = connection.prepareStatement(MARK_FAILED_SQL)) {
            update.setString(1, describe(failure));
            update.setLong(2, backoff.toMillis());
            update.setInt(3, retryPolicy.maxAttempts());
            update.setObject(4, event.eventId().value());
            update.executeUpdate();
        }

        if (exhausted) {
            log.error(
                    "Outbox event {} ({}) abandoned after {} attempts; aggregate {} is now blocked",
                    event.eventId().value(),
                    event.eventType(),
                    attemptsAfter,
                    event.aggregateId(),
                    failure);
        } else {
            log.warn(
                    "Outbox event {} ({}) failed on attempt {}; retrying in {}",
                    event.eventId().value(),
                    event.eventType(),
                    attemptsAfter,
                    backoff,
                    failure);
        }
        return exhausted;
    }

    /**
     * A bounded, non-empty description of a failure for {@code last_error}.
     *
     * <p>The exception's type is always included, because a message alone is often empty and a
     * type alone is often enough to recognise a broker outage. The payload never is: an adapter
     * putting event content into its exception message would put it in a column operators read
     * ({@code INV-AUD-02}), and this truncation is a bound on volume, not a redaction.
     */
    private static String describe(Exception failure) {
        String message = failure.getMessage();
        String described =
                message == null || message.isBlank()
                        ? failure.getClass().getName()
                        : failure.getClass().getName() + ": " + message;
        return described.length() <= MAX_LAST_ERROR_LENGTH
                ? described
                : described.substring(0, MAX_LAST_ERROR_LENGTH);
    }

    // -----------------------------------------------------------------

    /** One pending row plus the two server-evaluated conditions the relay stops on. */
    private record PendingRow(PendingEvent event, boolean due, boolean abandoned) {}

    private record AggregateOutcome(boolean locked, int published, int failed, int deadLettered) {
        static final AggregateOutcome NOT_LOCKED = new AggregateOutcome(false, 0, 0, 0);
    }
}
