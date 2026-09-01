package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The relay's log lines carry the flow they belong to.
 *
 * <p><strong>Why this is its own test.</strong> A publication failure is the single most
 * important thing the relay says, and it is said about one specific event belonging to one
 * specific business flow. A warning that an event could not be published, with no correlation
 * identifier on it, cannot be joined to the transfer or payment that produced the event — which
 * is the entire question an operator has when they read it.
 *
 * <p>Read from a real appender rather than a mocked logger, for the same reason as
 * {@code CorrelationPropagationTest}: the claim is about what reaches the log, not about which
 * method was called.
 */
@Tag("database")
class RelayLoggingTest {

    private static final String TABLE = "platform.outbox_event";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T12:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(OCCURRED, ZoneOffset.UTC), new Random(77L));
    private static final CorrelationId FLOW = CorrelationId.of("relay-log-flow");
    private static final CausationId CAUSE = CausationId.of("relay-log-cause");

    private static Connection writeConnection;
    private ListAppender<ILoggingEvent> recorded;
    private Logger relayLogger;

    static final class ProbeAggregateId extends EntityId {
        ProbeAggregateId(UUID value) {
            super(value);
        }
    }

    @BeforeAll
    static void connect() throws SQLException {
        writeConnection = open();
        writeConnection.setAutoCommit(false);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (writeConnection != null) {
            writeConnection.close();
        }
    }

    @BeforeEach
    void startRecording() throws SQLException {
        try (Statement statement = writeConnection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + TABLE);
        }
        writeConnection.commit();

        relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        recorded = new ListAppender<>();
        recorded.start();
        relayLogger.addAppender(recorded);
    }

    @AfterEach
    void stopRecording() {
        relayLogger.detachAppender(recorded);
        recorded.stop();
    }

    @Test
    @DisplayName("a failed publication is logged against the flow that produced the event")
    void aFailureCarriesItsCorrelation() {
        EventId eventId = writeEvent(IDS.next());

        relay(failing()).pollOnce();

        assertThat(correlationOf(warningsAbout(eventId)))
                .as(
                        "a publication failure with no correlation cannot be joined to the "
                                + "transfer or payment whose event it is")
                .containsExactly(FLOW.value());
    }

    @Test
    @DisplayName("abandoning an event is logged against the flow that produced it")
    void abandonmentCarriesItsCorrelation() {
        // The loudest line the relay ever emits, and the one most likely to start an
        // investigation. If any line needs to name its flow, it is this one.
        EventId eventId = writeEvent(IDS.next());
        OutboxRelay relay = relay(failing());
        for (int attempt = 0; attempt < 3; attempt++) {
            relay.pollOnce();
            makeDue(eventId);
        }

        List<ILoggingEvent> abandonment =
                recorded.list.stream()
                        .filter(event -> event.getFormattedMessage().contains("abandoned"))
                        .toList();

        assertThat(abandonment).isNotEmpty();
        assertThat(correlationOf(abandonment)).containsOnly(FLOW.value());
    }

    @Test
    @DisplayName("a blocked aggregate is logged against the event that blocks it")
    void blockedAggregateCarriesItsCorrelation() {
        EventId blocking = writeEvent(IDS.next());
        abandonDirectly(blocking);

        // The candidate query skips an aggregate whose head is abandoned, so the blocked-warning
        // is reached by putting a publishable event in front of the abandoned one.
        UUID aggregateId = IDS.next();
        writeEvent(aggregateId);
        EventId poisoned = writeEvent(aggregateId);
        abandonDirectly(poisoned);
        relay(accepting()).pollOnce();

        List<ILoggingEvent> blocked =
                recorded.list.stream()
                        .filter(event -> event.getFormattedMessage().contains("blocked behind"))
                        .toList();

        assertThat(blocked).isNotEmpty();
        assertThat(correlationOf(blocked)).containsOnly(FLOW.value());
    }

    @Test
    @DisplayName("the relay does not leak a flow's correlation into whatever ran before it")
    void theScopeIsRestored() {
        // A relay that entered a correlation scope and never left it would stamp the next
        // event's identifier - or the scheduler's own log lines - with a flow they belong to.
        writeEvent(IDS.next());

        relay(failing()).pollOnce();

        assertThat(CorrelationContext.current()).isEmpty();
    }

    // -----------------------------------------------------------------

    private List<ILoggingEvent> warningsAbout(EventId eventId) {
        return recorded.list.stream()
                .filter(event -> event.getFormattedMessage().contains(eventId.value().toString()))
                .toList();
    }

    private static List<String> correlationOf(List<ILoggingEvent> events) {
        assertThat(events).as("the relay logged nothing to check").isNotEmpty();
        return events.stream()
                .map(event -> event.getMDCPropertyMap().get(CorrelationContext.CORRELATION_ID_KEY))
                .distinct()
                .toList();
    }

    private static EventPublisher failing() {
        return event -> {
            throw new IllegalStateException("the broker is unavailable");
        };
    }

    private static EventPublisher accepting() {
        return event -> {};
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(
                RelayLoggingTest::open,
                publisher,
                new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 3),
                8,
                100);
    }

    private static EventId writeEvent(UUID aggregateId) {
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "transfers.TransferCompleted",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new ProbeAggregateId(aggregateId),
                        "Transfer",
                        OCCURRED,
                        "relay-log-probe",
                        FLOW,
                        CAUSE);
        try {
            new JdbcOutboxWriter()
                    .write(
                            writeConnection,
                            envelope,
                            "{}".getBytes(StandardCharsets.UTF_8),
                            "application/json");
            makeDue(envelope.eventId());
            writeConnection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed an outbox row", e);
        }
        return envelope.eventId();
    }

    /** See {@code OutboxRelayTest.backDate}: the local server clock steps backwards. */
    private static void makeDue(EventId eventId) {
        update(
                "UPDATE " + TABLE + " SET next_attempt_at = now() - INTERVAL '1 minute' "
                        + "WHERE event_id = ?",
                eventId);
    }

    private static void abandonDirectly(EventId eventId) {
        update(
                "UPDATE " + TABLE + " SET attempts = 3, dead_lettered_at = now(), "
                        + "last_error = 'probe' WHERE event_id = ?",
                eventId);
    }

    private static void update(String sql, EventId eventId) {
        try (PreparedStatement statement = writeConnection.prepareStatement(sql)) {
            statement.setObject(1, eventId.value());
            statement.executeUpdate();
            writeConnection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not update the probe row", e);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                required("finapp.db.url"), required("finapp.db.user"), required("finapp.db.password"));
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set. Run this through "
                            + "'./gradlew :platform:databaseTest', which supplies it.");
        }
        return value;
    }
}
