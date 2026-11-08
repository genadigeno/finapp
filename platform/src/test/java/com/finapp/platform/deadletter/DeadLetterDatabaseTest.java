package com.finapp.platform.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.InboxParking;
import com.finapp.platform.inbox.JdbcInboxParking;
import com.finapp.platform.inbox.ParkingPolicy;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The dead-letter statements on a real PostgreSQL (`P15-TSK-028`): the row locks, the server's clock, the grants, and ten simulated
 * instances racing one row, each on its own connection. Written and owed (C14) to {@code P15-TST-002}.
 */
@Tag("database")
@DisplayName("dead letters and replay against PostgreSQL (P15-TSK-028)")
class DeadLetterDatabaseTest {

    private static final Duration RETENTION = Duration.ofDays(14);
    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId FLOW = CorrelationId.of("dead-letter-flow");
    private static final Actor FIRST = new Actor("operator-1", ActorType.EMPLOYEE);
    private static final Actor SECOND = new Actor("operator-2", ActorType.EMPLOYEE);
    private static final String TYPE = "probe.ReplayedEvent";

    private static Connection admin;

    private final DeadLetterOperations operations = new DeadLetterOperations(new JdbcDeadLetterStore(), new JdbcAuditWriter(), IDS, CLOCK, RETENTION, type -> false);

    @BeforeAll
    static void connect() throws SQLException {
        admin = DatabaseRoles.bootstrap();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (admin != null) {
            admin.close();
        }
    }

    @BeforeEach
    void empty() throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.dead_letter_discard");
            statement.executeUpdate("DELETE FROM platform.inbox_parked_record");
            statement.executeUpdate("DELETE FROM platform.inbox_message");
            statement.executeUpdate("DELETE FROM platform.outbox_event");
        }
    }

    @Test
    @DisplayName("ten instances retrying one row: exactly one wins, the row lock is the arbiter")
    void tenRetriesOneWinner() throws Exception {
        UUID event = seedAbandoned();

        List<Boolean> outcomes = raceTenInstances(connection -> operations.retry(connection, event, FIRST, "retry it", FLOW));

        assertThat(outcomes.stream().filter(won -> won).count()).isEqualTo(1);
        assertThat(abandoned(event)).isFalse();
        assertThat(auditCount("outbox.EventRetryAuthorised")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances replaying one window: each event is requeued once")
    void tenReplaysOneRequeue() throws Exception {
        UUID event = seedPublished(Duration.ofDays(1));
        Instant from = Instant.now().minus(Duration.ofDays(2));
        Instant to = Instant.now();

        List<Boolean> outcomes = raceTenInstances(connection ->
                assertThat(operations.replay(connection, TYPE, from, to, FIRST, "replay it", FLOW).requeued()).isLessThanOrEqualTo(1));

        assertThat(outcomes).doesNotContain(false);
        assertThat(pending(event)).isTrue();
        assertThat(auditCount("outbox.EventReplayOrdered")).isEqualTo(10);
    }

    @Test
    @DisplayName("a replay past the window is refused by the server's clock, and nothing moves")
    void aReplayPastTheWindowIsRefused() throws Exception {
        UUID event = seedPublished(RETENTION.plusHours(1));
        Instant from = Instant.now().minus(Duration.ofDays(30));
        Instant to = Instant.now();

        try (Connection connection = application()) {
            assertThatThrownBy(() -> operations.replay(connection, TYPE, from, to, FIRST, "why", FLOW))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(DeadLetterErrorCode.REPLAY_OUTSIDE_DEDUPE_WINDOW));
        }
        assertThat(pending(event)).isFalse();
    }

    @Test
    @DisplayName("a replay keeps every consumer's dedupe row a full retention, and never shortens one")
    void theDedupeIsExtendedForEveryConsumer() throws Exception {
        UUID event = seedPublished(Duration.ofDays(13));
        seedDedupe("named.consumer", event, Duration.ofDays(1));
        seedDedupe("zz.long-gone-handler", event, Duration.ofDays(1));
        seedDedupe("longer.consumer", event, Duration.ofDays(40));
        Instant before = Instant.now();

        try (Connection connection = application()) {
            connection.setAutoCommit(false);
            operations.replay(connection, TYPE, Instant.now().minus(Duration.ofDays(20)), Instant.now(), FIRST, "why", FLOW);
            connection.commit();
        }

        assertThat(expiry("named.consumer", event)).isAfter(before.plus(RETENTION).minusSeconds(60));
        assertThat(expiry("zz.long-gone-handler", event)).as("from the table, whichever release named it").isAfter(before.plus(RETENTION).minusSeconds(60));
        assertThat(expiry("longer.consumer", event)).isAfter(before.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("a discard keeps the row, the mark, and needs a second person at the database as well")
    void aDiscardIsARecordedDecision() throws Exception {
        UUID event = seedAbandoned();
        UUID discard;
        try (Connection connection = application()) {
            connection.setAutoCommit(false);
            discard = operations.proposeDiscard(connection, DeadLetterStore.SubjectKind.OUTBOX_EVENT, event, FIRST, "why", FLOW);
            operations.approveDiscard(connection, discard, SECOND, "agreed", FLOW);
            connection.commit();
        }
        assertThat(abandoned(event)).as("the mark an N-1 relay stops at").isTrue();
        assertThat(exists("SELECT 1 FROM platform.outbox_event WHERE discarded_at IS NOT NULL AND event_id = '" + event + "'")).isTrue();

        try (Statement statement = admin.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE platform.dead_letter_discard SET decided_by = proposed_by WHERE discard_id = '" + discard + "'"))
                    .as("INV-AUD-04 at the database")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("a retry and a standing proposal cannot both happen: the one that waits sees the other")
    void aRetryCannotOutrunAProposal() throws Exception {
        UUID event = seedAbandoned();
        try (Connection proposer = application()) {
            proposer.setAutoCommit(false);
            operations.proposeDiscard(proposer, DeadLetterStore.SubjectKind.OUTBOX_EVENT, event, FIRST, "why", FLOW);
            proposer.commit();
        }
        try (Connection retrier = application()) {
            retrier.setAutoCommit(false);
            assertThatThrownBy(() -> operations.retry(retrier, event, SECOND, "try again", FLOW))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(DeadLetterErrorCode.DISCARD_STANDING));
            retrier.rollback();
        }
        assertThat(abandoned(event)).isTrue();
    }

    @Test
    @DisplayName("the relay records one abandonment, in the transaction that abandons, and goes on past a discarded row")
    void theRelayRecordsAndSkips() throws Exception {
        UUID aggregate = UUID.randomUUID();
        UUID event = IDS.next();
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO platform.outbox_event (event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at, producer,"
                    + " correlation_id, causation_id, payload, payload_media_type) VALUES ('" + event + "', '" + TYPE + "', 1, 1, '" + aggregate
                    + "', 'Probe', now(), 'probe', 'flow', 'cause', '\\x00'::bytea, 'application/octet-stream')");
        }
        com.finapp.platform.outbox.OutboxRelay relay = new com.finapp.platform.outbox.OutboxRelay(DatabaseRoles::application, published -> {
            throw new IllegalStateException("broker down");
        }, new com.finapp.platform.outbox.RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 2), 8, 100,
                new com.finapp.platform.outbox.AuditedAbandonments(new JdbcAuditWriter(), IDS, CLOCK));
        Instant deadline = Instant.now().plusSeconds(10);
        while (!abandoned(event) && Instant.now().isBefore(deadline)) {
            relay.pollOnce();
        }
        relay.pollOnce();

        assertThat(abandoned(event)).isTrue();
        assertThat(auditCount("outbox.EventAbandoned")).isEqualTo(1);
    }

    @Test
    @DisplayName("the budget needs both the count and the elapsed floor, judged on the row")
    void theBudgetNeedsBothConditions() throws Exception {
        JdbcInboxParking parking = new JdbcInboxParking(new ParkingPolicy(2, Duration.ofHours(1)), new JdbcAuditWriter(), IDS, CLOCK);
        InboxParking.Failure failure = failure("probe.consumer", UUID.randomUUID().toString());

        try (Connection connection = application()) {
            assertThat(parking.recordFailure(connection, failure, "x.Boom")).isEqualTo(InboxParking.Verdict.RETRY);
            assertThat(parking.recordFailure(connection, failure, "x.Boom")).as("the count is spent, the floor is not").isEqualTo(InboxParking.Verdict.RETRY);
        }
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("UPDATE platform.inbox_parked_record SET first_failed_at = now() - interval '2 hours'");
        }
        try (Connection connection = application()) {
            assertThat(parking.recordFailure(connection, failure, "x.Boom")).isEqualTo(InboxParking.Verdict.PARKED);
            assertThat(parking.recordFailure(connection, failure, "x.Boom")).as("already parked: not parked twice").isEqualTo(InboxParking.Verdict.ALREADY_SETTLED);
            assertThat(parking.standing(connection, failure.key())).isEqualTo(InboxParking.Standing.PARKED);
        }
        assertThat(auditCount("inbox.RecordParked")).isEqualTo(1);
    }

    @Test
    @DisplayName("the application role deletes from neither table, and updates nothing of the dedupe row but its expiry")
    void theGrantsAreNarrow() throws Exception {
        try (Connection connection = application(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM platform.dead_letter_discard")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM platform.inbox_parked_record")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE platform.inbox_message SET consumer = 'x'")).isInstanceOf(SQLException.class);
            statement.executeUpdate("UPDATE platform.inbox_message SET expires_at = expires_at");
        }
    }

    // -----------------------------------------------------------------

    @FunctionalInterface
    private interface Door {
        void open(Connection connection) throws Exception;
    }

    /** Ten connections released together; each commits or rolls back its own transaction. */
    private static List<Boolean> raceTenInstances(Door door) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try (Connection connection = application()) {
                    connection.setAutoCommit(false);
                    try {
                        door.open(connection);
                        connection.commit();
                        return true;
                    } catch (ApiException refused) {
                        connection.rollback();
                        return false;
                    }
                }
            }));
        }
        start.countDown();
        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static Connection application() throws SQLException {
        return DatabaseRoles.application();
    }

    private static InboxParking.Failure failure(String consumer, String key) {
        return new InboxParking.Failure(new InboxKey(consumer, key), "finapp.probe", TYPE, Optional.of(FLOW), "finapp.probe", 0, 7L);
    }

    private static UUID seedAbandoned() throws SQLException {
        UUID id = IDS.next();
        seed(id, "attempts, dead_lettered_at, last_error", "8, now(), 'java.lang.IllegalStateException'");
        return id;
    }

    private static UUID seedPublished(Duration age) throws SQLException {
        UUID id = IDS.next();
        seed(id, "attempts, published_at", "1, now() - " + age.toSeconds() + " * interval '1 second'");
        return id;
    }

    private static void seed(UUID id, String extraColumns, String extraValues) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO platform.outbox_event (event_id, event_type, event_version, schema_version, aggregate_id, aggregate_type, occurred_at, producer,"
                    + " correlation_id, causation_id, payload, payload_media_type, " + extraColumns + ") VALUES ('" + id + "', '" + TYPE + "', 1, 1, '" + UUID.randomUUID()
                    + "', 'Probe', now(), 'probe', 'flow', 'cause', '\\x00'::bytea, 'application/octet-stream', " + extraValues + ")");
        }
    }

    private static void seedDedupe(String consumer, UUID event, Duration remaining) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.executeUpdate("INSERT INTO platform.inbox_message (consumer, dedupe_key, message_type, correlation_id, processed_at, expires_at) VALUES ('" + consumer + "', '" + event
                    + "', '" + TYPE + "', 'flow', now() - interval '1 second', now() + " + remaining.toSeconds() + " * interval '1 second')");
        }
    }

    private static boolean abandoned(UUID event) throws SQLException {
        return exists("SELECT 1 FROM platform.outbox_event WHERE dead_lettered_at IS NOT NULL AND event_id = '" + event + "'");
    }

    private static boolean pending(UUID event) throws SQLException {
        return exists("SELECT 1 FROM platform.outbox_event WHERE published_at IS NULL AND event_id = '" + event + "'");
    }

    private static boolean exists(String sql) throws SQLException {
        try (Statement statement = admin.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private static Instant expiry(String consumer, UUID event) throws SQLException {
        try (PreparedStatement select = admin.prepareStatement("SELECT expires_at FROM platform.inbox_message WHERE consumer = ? AND dedupe_key = ?")) {
            select.setString(1, consumer);
            select.setString(2, event.toString());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).toInstant();
            }
        }
    }

    private static long auditCount(String action) throws SQLException {
        try (PreparedStatement select = admin.prepareStatement("SELECT count(*) FROM platform.audit_record WHERE operation = ?")) {
            select.setString(1, action);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
