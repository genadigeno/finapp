package com.finapp.platform.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;

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
