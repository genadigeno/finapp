package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the relay records when it gives up on an event (`P15-TSK-028`). That the relay calls it is {@code DeadLetterDatabaseTest}'s. */
@DisplayName("an abandonment is recorded as the platform, naming the event and its flow (P15-TSK-028)")
class AuditedAbandonmentsTest {

    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the writer is a fake");
            });

    @Test
    @DisplayName("the record is the platform's, for the event, in the flow that produced it, and carries no payload")
    void anAbandonmentIsRecorded() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        List<AuditRecord> audited = new ArrayList<>();
        AuditedAbandonments abandonments = new AuditedAbandonments((uow, record) -> audited.add(record), ids, Clock.systemUTC());
        EventId eventId = EventId.next(ids);
        UUID aggregate = UUID.randomUUID();
        PendingEvent event = new PendingEvent(eventId, "probe.SomethingHappened", 1, 1, aggregate, "Probe", Instant.EPOCH, "probe",
                CorrelationId.of("the-flow"), CausationId.of("cause"), "secret-content".getBytes(StandardCharsets.UTF_8), "application/json", 7);

        abandonments.recordAbandoned(UOW, event, 8);

        AuditRecord record = audited.getFirst();
        assertThat(record.operation()).isEqualTo(PlatformAuditAction.OUTBOX_EVENT_ABANDONED);
        assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM);
        assertThat(record.targetId()).isEqualTo(eventId.value().toString());
        assertThat(record.correlationId()).isEqualTo(CorrelationId.of("the-flow"));
        assertThat(record.changeSummary().orElseThrow()).contains("attempts=8", aggregate.toString()).doesNotContain("secret-content");
    }
}
