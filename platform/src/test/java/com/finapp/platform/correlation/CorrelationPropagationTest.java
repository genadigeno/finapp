package com.finapp.platform.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * End-to-end correlation propagation (P0-TST-003).
 *
 * <p><strong>What this asserts.</strong> One inbound request, whose correlation identifier is
 * accepted from the caller, hands work to another thread, and that work persists a record. The
 * identifier the caller supplied must appear — identically — in the log lines the flow writes
 * <em>and</em> in the row it commits. Not "a correlation id in each": the same one, compared
 * directly, because two independently-correct sinks that disagree are worse than one.
 *
 * <p><strong>Which sinks exist.</strong> P0-TST-003's criterion names four — log, trace, outbox
 * row, audit record. Two exist today: log lines, and the {@code correlation_id} on
 * {@code platform.idempotency_record}, which is a persisted sink and a real database round
 * trip. The outbox (P0-EPIC-06), audit store (P0-EPIC-07) and tracing exporter (P0-EPIC-09) do
 * not exist yet, so this test covers what there is to cover — and
 * {@link CorrelationSinkCoverageTest} fails the build the moment a new platform concern
 * appears without a decision about whether it is a sink. The criterion is therefore enforced as
 * the sinks arrive rather than deferred to somebody remembering.
 *
 * <p>Tagged {@code database}: run with {@code ./gradlew :platform:databaseTest}.
 */
@Tag("database")
@SuppressWarnings("try") // A Scope is used for its close side effect; see CorrelationContextTest.
class CorrelationPropagationTest {

    private static final String TABLE = "platform.idempotency_record";
    private static final String OUTBOX = "platform.outbox_event";

    /** What a caller's gateway would put on the request. */
    private static final String INBOUND_HEADER = "upstream-trace-9f2a";

    private static Connection connection;

    private ListAppender<ILoggingEvent> recorded;
    private Logger logger;

    @BeforeAll
    static void connect() throws SQLException {
        connection = openConnection();
        connection.setAutoCommit(true);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void startRecording() throws SQLException {
        logger = (Logger) LoggerFactory.getLogger(CorrelationPropagationTest.class);
        recorded = new ListAppender<>();
        recorded.start();
        logger.addAppender(recorded);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE + " WHERE scope LIKE 'propagation:%'");
            statement.executeUpdate("DELETE FROM " + OUTBOX + " WHERE producer = 'propagation'");
        }
    }

    @AfterEach
    void stopRecording() {
        logger.detachAppender(recorded);
        assertThat(CorrelationContext.current()).as("the flow must not outlive the test").isEmpty();
    }

    @Test
    @DisplayName("one request's identifier reaches every sink, identically, across a thread handoff")
    void correlationReachesEverySinkThatExists() throws Exception {
        String scope = "propagation:transfer";

        // --- ingress: the caller's identifier is accepted, not replaced -------------------
        CorrelationId accepted = CorrelationId.of(INBOUND_HEADER);
        Correlation flow = Correlation.startingWith(accepted);

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            try (var scoped = CorrelationContext.enter(flow)) {
                logger.info("request accepted");

                // --- the handoff the criterion specifically calls out ---------------------
                worker.submit(
                                CorrelationContext.propagate(
                                        (Runnable)
                                                () -> {
                                                    // Deliberately reads the ambient context rather than
                                                    // closing over the identifier: closing over it would
                                                    // make the test pass even if propagation were removed.
                                                    CorrelationId onWorker = currentCorrelationId();
                                                    logger.info("work executed");
                                                    writeClaim(scope, onWorker);
                                                }))
                        .get(30, TimeUnit.SECONDS);
            }
        }

        // --- sink 1: the log ------------------------------------------------------------
        List<ILoggingEvent> events = recorded.list;
        assertThat(events).as("both the ingress and the worker must have logged").hasSize(2);
        assertThat(events)
                .allSatisfy(
                        event ->
                                assertThat(event.getMDCPropertyMap())
                                        .containsEntry(
                                                CorrelationContext.CORRELATION_ID_KEY, INBOUND_HEADER));

        // --- sink 2: the persisted record ------------------------------------------------
        assertThat(persistedCorrelationId(scope))
                .as("the committed row must carry the identifier the caller supplied")
                .isEqualTo(INBOUND_HEADER);

        // --- and the point of the whole exercise ----------------------------------------
        String fromLog = events.getLast().getMDCPropertyMap().get(CorrelationContext.CORRELATION_ID_KEY);
        assertThat(fromLog)
                .as("log and database must agree; two sinks that disagree are worse than one")
                .isEqualTo(persistedCorrelationId(scope));
    }

    @Test
    @DisplayName("an event queued during the flow carries the same identifier as its log lines")
    void correlationReachesTheOutboxRow() throws Exception {
        // The third sink. P0-TSK-014's criterion named an "emitted event" and could not verify
        // it because no outbox existed; CorrelationSinkCoverageTest is what stopped the outbox
        // arriving without this assertion rather than leaving it to memory.
        String scope = "propagation:emits";
        CorrelationId accepted = CorrelationId.of(INBOUND_HEADER);
        EventId eventId = EventId.next(
                new com.finapp.sharedkernel.id.IdGenerator(
                        java.time.Clock.systemUTC(), new java.security.SecureRandom()));

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            try (var scoped = CorrelationContext.enter(Correlation.startingWith(accepted))) {
                logger.info("request accepted");
                worker.submit(
                                CorrelationContext.propagate(
                                        (Runnable)
                                                () -> {
                                                    // Reads the ambient context, as the real
                                                    // emitting code will: closing over the
                                                    // identifier would make this pass with
                                                    // propagation removed.
                                                    CorrelationId onWorker = currentCorrelationId();
                                                    logger.info("event emitted");
                                                    writeClaim(scope, onWorker);
                                                    queueEvent(eventId, onWorker);
                                                }))
                        .get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(outboxCorrelationId(eventId))
                .as("the queued event must carry the identifier the caller supplied")
                .isEqualTo(INBOUND_HEADER);
        assertThat(outboxCorrelationId(eventId))
                .as("and agree with the idempotency record written in the same flow")
                .isEqualTo(persistedCorrelationId(scope));
        assertThat(recorded.list)
                .allSatisfy(
                        event ->
                                assertThat(event.getMDCPropertyMap())
                                        .containsEntry(
                                                CorrelationContext.CORRELATION_ID_KEY, INBOUND_HEADER));
    }

    @Test
    @DisplayName("without propagation the identifier is lost, so this test has teeth")
    void unpropagatedWorkLosesTheIdentifier() throws Exception {
        // The negative control. P0-TST-003 asks that the test fail if propagation is removed
        // from a sink; this asserts the mechanism is what carries it, rather than something
        // incidental about running on one machine.
        String scope = "propagation:unwrapped";

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            try (var scoped = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(INBOUND_HEADER)))) {
                worker.submit(() -> writeClaimWithoutContext(scope)).get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(persistedCorrelationId(scope))
                .as("an unwrapped handoff must not carry the flow's identifier")
                .isNotEqualTo(INBOUND_HEADER);
    }

    @Test
    @DisplayName("a request arriving without an identifier is given one, and it still reaches both sinks")
    void generatedCorrelationAlsoPropagates() throws Exception {
        // The other ingress path: no inbound header, so the platform mints one. It must
        // propagate exactly as an accepted one does, or flows started by a job or an internal
        // caller would be untraceable while flows from an HTTP client were fine.
        String scope = "propagation:generated";
        CorrelationId generated =
                CorrelationId.generate(
                        new com.finapp.sharedkernel.id.IdGenerator(
                                java.time.Clock.systemUTC(), new java.security.SecureRandom()));

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            try (var scoped = CorrelationContext.enter(Correlation.startingWith(generated))) {
                worker.submit(
                                CorrelationContext.propagate(
                                        (Runnable) () -> writeClaim(scope, currentCorrelationId())))
                        .get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(persistedCorrelationId(scope)).isEqualTo(generated.value());
    }

    // -----------------------------------------------------------------

    private static CorrelationId currentCorrelationId() {
        return CorrelationContext.current().map(Correlation::correlationId).orElse(null);
    }

    private static void writeClaim(String scope, CorrelationId correlationId) {
        writeClaim(scope, correlationId == null ? "MISSING" : correlationId.value());
    }

    private static void writeClaimWithoutContext(String scope) {
        // What a worker that was handed no context can honestly record.
        writeClaim(scope, currentCorrelationId() == null ? "NO-CORRELATION" : currentCorrelationId().value());
    }

    private static void writeClaim(String scope, String correlationId) {
        String sql =
                "INSERT INTO " + TABLE + " (scope, idempotency_key, request_fingerprint, "
                        + "fingerprint_algorithm, state, correlation_id, created_at, expires_at, "
                        + "lease_expires_at) "
                        // V004: a running claim carries a lease, taken from the server's clock.
                        + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, now() + INTERVAL '5 minutes')";
        Instant now = Instant.now();
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, scope);
            insert.setString(2, "key-1");
            insert.setBytes(3, new byte[32]);
            insert.setString(4, "SHA-256");
            insert.setString(5, correlationId);
            insert.setTimestamp(6, Timestamp.from(now));
            insert.setTimestamp(7, Timestamp.from(now.plusSeconds(86_400)));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write the claim for " + scope, e);
        }
    }

    private static String persistedCorrelationId(String scope) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT correlation_id FROM " + TABLE + " WHERE scope = ?")) {
            select.setString(1, scope);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("a row must have been written for %s", scope).isTrue();
                return rows.getString(1);
            }
        }
    }

    /** Queues an event carrying whatever correlation the current context holds. */
    private static void queueEvent(EventId eventId, CorrelationId correlationId) {
        EventEnvelope envelope =
                new EventEnvelope(
                        eventId,
                        "propagation.Probe",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeAggregateId(java.util.UUID.fromString(eventId.value().toString())),
                        "Probe",
                        Instant.now(),
                        "propagation",
                        correlationId == null ? CorrelationId.of("MISSING") : correlationId,
                        CausationId.of("command-1"));
        new JdbcOutboxWriter().write(connection, envelope, new byte[0], "application/json");
    }

    @Test
    @DisplayName("a message consumed during the flow carries the same identifier as its log lines")
    void correlationReachesTheInboxRow() throws Exception {
        // The fourth sink of P0-TST-003's criterion. The consumer reads the ambient context
        // rather than being handed an identifier, which is what makes this fail if propagation
        // is removed.
        String scope = "propagation:consumes";
        CorrelationId accepted = CorrelationId.of(INBOUND_HEADER);
        InboxKey key = new InboxKey("propagation-probe", "message-" + System.nanoTime());

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            try (var scoped = CorrelationContext.enter(Correlation.startingWith(accepted))) {
                logger.info("message received");
                worker.submit(
                                CorrelationContext.propagate(
                                        (Runnable)
                                                () -> {
                                                    logger.info("message handled");
                                                    writeClaim(scope, currentCorrelationId());
                                                    consumeMessage(key);
                                                }))
                        .get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(inboxCorrelationId(key))
                .as("the dedupe record must carry the identifier the caller supplied")
                .isEqualTo(INBOUND_HEADER);
        assertThat(inboxCorrelationId(key))
                .as("and agree with the idempotency record written in the same flow")
                .isEqualTo(persistedCorrelationId(scope));
    }

    private static void consumeMessage(InboxKey key) {
        // The real wrapper, not a hand-written INSERT: the claim under test is that consuming
        // propagates correlation, and an INSERT here would assert only that this test can spell
        // the column name.
        // A real transaction, because the store refuses an auto-commit connection: its one
        // guarantee is that the dedupe record commits with the handler's effect, and auto-commit
        // would commit the record alone. This class's connection is auto-commit for every other
        // sink, so the transaction is opened and closed around just this call.
        try {
            connection.setAutoCommit(false);
            new InboxConsumer<Connection>(
                            new JdbcInboxRecordStore(),
                            java.time.Clock.systemUTC(),
                            java.time.Duration.ofHours(1))
                    .consume(connection, key, "propagation.Probe", unitOfWork -> {});
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not consume the probe message", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException("could not restore auto-commit", e);
            }
        }
    }

    private static String inboxCorrelationId(InboxKey key) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT correlation_id FROM platform.inbox_message "
                                + "WHERE consumer = ? AND dedupe_key = ?")) {
            select.setString(1, key.consumer());
            select.setString(2, key.dedupeKey());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("an inbox row must have been written").isTrue();
                return rows.getString(1);
            }
        }
    }

    private static String outboxCorrelationId(EventId eventId) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT correlation_id FROM " + OUTBOX + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("an outbox row must have been written").isTrue();
                return rows.getString(1);
            }
        }
    }

    /** Stands in for an aggregate identifier owned by a business module in a later phase. */
    static final class ProbeAggregateId extends com.finapp.sharedkernel.id.EntityId {
        ProbeAggregateId(java.util.UUID value) {
            super(value);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                requiredProperty("finapp.db.url"),
                requiredProperty("finapp.db.user"),
                requiredProperty("finapp.db.password"));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set. Run this through "
                            + "'./gradlew :platform:databaseTest', which supplies it.");
        }
        return value;
    }
}
