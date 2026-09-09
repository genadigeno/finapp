package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The schedule drives the poll (`P2-TSK-001`) — in `app`, because the schedule is
 * composition-root machinery and `platform` must not reach upward for it.
 *
 * <p>One test, deliberately: everything about the relay's correctness is proven where the relay
 * lives ({@code KafkaOutboxDeliveryKafkaTest}, {@code OutboxRelayTest}). What only this class can
 * prove is that the schedule actually turns the crank — an event flows with no call from the
 * test — and that the publication counters see it, which is the eager-metrics rule
 * (`P1-TSK-029`) applied to the platform's first background worker.
 */
@Tag("kafka")
@DisplayName("the outbox relay schedule (P2-TSK-001)")
class OutboxRelayScheduleKafkaTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    static final class ProbeId extends EntityId {
        ProbeId(UUID value) {
            super(value);
        }
    }

    @Test
    @DisplayName("started, an event flows with no call from the test - and the counter saw it")
    void theScheduleDrivesThePoll() throws Exception {
        String producerName = "sched" + UUID.randomUUID().toString().substring(0, 8);
        EventEnvelope envelope =
                new EventEnvelope(
                        EventId.next(IDS),
                        "probe.SomethingHappened",
                        1,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        // IDS.next(), not UUID.randomUUID(): EntityId validates UUIDv7 (ADR-0013), and the
                        // v4 the obvious call returns is refused as malformed - the P1-TSK-028
                        // lesson, met again by its own author.
                        new ProbeId(IDS.next()),
                        "Probe",
                        Instant.now(),
                        producerName,
                        CorrelationId.of(UUID.randomUUID().toString()),
                        CausationId.of("command-1"));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcOutboxWriter()
                    .write(
                            app,
                            envelope,
                            "published-by-the-schedule".getBytes(StandardCharsets.UTF_8),
                            "text/plain");
            app.commit();
        }
        backDate(envelope.eventId());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (KafkaEventPublisher publisher =
                KafkaEventPublisher.connect(
                        System.getProperty("finapp.kafka.bootstrap"),
                        "PLAINTEXT",
                        Duration.ofSeconds(10))) {
            OutboxRelaySchedule schedule =
                    new OutboxRelaySchedule(
                            new OutboxRelay(DatabaseRoles::application, publisher),
                            Duration.ofMillis(200),
                            registry);

            assertThat(
                            registry.counter("finapp.outbox.publication", "outcome", "published")
                                    .count())
                    .as("eager registration: the series exists before anything has happened")
                    .isZero();

            schedule.start();
            try {
                // Wait on the CONDITION, never for a duration (P1-TSK-002's lesson); the bound
                // is generous because exceeding it is a failure and never a pass.
                Instant deadline = Instant.now().plusSeconds(30);
                while (publishedAt(envelope.eventId()) == null
                        && Instant.now().isBefore(deadline)) {
                    Thread.sleep(50);
                }
            } finally {
                schedule.stop();
            }
        }

        assertThat(publishedAt(envelope.eventId()))
                .as("every instance polls; this one proved it without the test calling pollOnce")
                .isNotNull();
        assertThat(registry.counter("finapp.outbox.publication", "outcome", "published").count())
                .as("and the publication was counted - the relay-metrics debt row's other half")
                .isGreaterThanOrEqualTo(1.0d);
    }

    private static void backDate(EventId eventId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement update =
                        app.prepareStatement(
                                "UPDATE platform.outbox_event"
                                        + " SET next_attempt_at = now() - INTERVAL '1 minute'"
                                        + " WHERE event_id = ?")) {
            update.setObject(1, eventId.value());
            update.executeUpdate();
        }
    }

    private static Instant publishedAt(EventId eventId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT published_at FROM platform.outbox_event"
                                        + " WHERE event_id = ?")) {
            select.setObject(1, eventId.value());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                var stamp = row.getTimestamp(1);
                return stamp == null ? null : stamp.toInstant();
            }
        }
    }
}
