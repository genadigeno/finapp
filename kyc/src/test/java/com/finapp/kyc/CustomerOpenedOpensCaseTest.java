package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The consumer's own decisions (`P2-TSK-007`): created announces, converged is silent, and the
 * emitted event is caused by the consumed one. The transport, the dedupe and the race are the
 * shell's and the kafka tier's subjects; this is hermetic on purpose.
 */
@DisplayName("a registration opens a case (P2-TSK-007)")
@SuppressWarnings("try") // correlation Scopes are used for their close side effect
class CustomerOpenedOpensCaseTest {

    /** The kind resolution the app wires over party; here every customer is a person. */
    private static final CaseKindResolver<Connection> PERSON_KIND =
            (unitOfWork, customerId) -> KycCaseKind.KYC;

    /** The consent gate the app wires over consent; here every party holds a basis. */
    private static final CaseOpeningConsent<Connection> CONSENTED =
            (unitOfWork, customerId) -> true;

    /** And its refusal: absence, withdrawal and a lapsed grant are one false (P2-TSK-019). */
    private static final CaseOpeningConsent<Connection> NO_BASIS =
            (unitOfWork, customerId) -> false;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final RecordingAudit audit = new RecordingAudit();
    private final RecordingOutbox outbox = new RecordingOutbox();

    @Test
    @DisplayName("a created case is audited against the customer and announced, once")
    void aCreatedCaseIsAuditedAndAnnounced() {
        ScriptedStore store = new ScriptedStore(true);
        CustomerOpenedOpensCase handler =
                new CustomerOpenedOpensCase(store, PERSON_KIND, CONSENTED, IDS, CLOCK, audit, outbox);
        UUID customerId = IDS.next();
        EventId consumed = EventId.next(IDS);
        ReceivedEvent event = customerOpened(consumed, customerId);

        try (CorrelationContext.Scope scope = scopeFor(consumed)) {
            handler.handle(null, event);
        }

        assertThat(audit.records).hasSize(1);
        AuditRecord record = audit.records.get(0);
        assertThat(record.operation().code()).isEqualTo("kyc.CaseOpened");
        assertThat(record.targetType()).isEqualTo("Customer");
        assertThat(record.targetId()).isEqualTo(customerId.toString());
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(record.correlationId().value()).isEqualTo("flow-1");
        assertThat(record.changeSummary())
                .as("the case and the policy regime, so the trail answers 'which case, under"
                        + " what rules' without a join")
                .hasValueSatisfying(
                        summary -> {
                            assertThat(summary).contains(store.created.id().toString());
                            assertThat(summary).contains(store.created.policyVersion().value());
                        });

        assertThat(outbox.envelopes).hasSize(1);
        EventEnvelope announced = outbox.envelopes.get(0);
        assertThat(announced.eventType()).isEqualTo("kyc.KycCaseOpened");
        assertThat(announced.aggregateId()).isEqualTo(store.created.id());
        assertThat(announced.correlationId().value()).isEqualTo("flow-1");
        assertThat(announced.causationId().value())
                .as("the CONSUMED event is this one's cause - inheriting the parent's causation"
                        + " would flatten the causal tree")
                .isEqualTo(consumed.value().toString());
        assertThat(new String(outbox.payloads.get(0), StandardCharsets.UTF_8))
                .contains(store.created.id().value().toString())
                .contains(customerId.toString());
    }

    @Test
    @DisplayName("a converged case records nothing and announces nothing")
    void aConvergedCaseIsSilent() {
        // The fact already exists and was recorded by whoever created it. A converged open that
        // audited itself would put two opening records on one case - the ambiguity INV-KYC-03
        // exists to prevent - and a second KycCaseOpened event would tell consumers a second
        // case exists.
        ScriptedStore store = new ScriptedStore(false);
        CustomerOpenedOpensCase handler =
                new CustomerOpenedOpensCase(store, PERSON_KIND, CONSENTED, IDS, CLOCK, audit, outbox);
        EventId consumed = EventId.next(IDS);

        try (CorrelationContext.Scope scope = scopeFor(consumed)) {
            handler.handle(null, customerOpened(consumed, IDS.next()));
        }

        assertThat(audit.records).isEmpty();
        assertThat(outbox.envelopes).isEmpty();
    }

    @Test
    @DisplayName("a refused opening writes nothing: no case, no record, no announcement")
    void aRefusedOpeningWritesNothing() {
        // The gate at the eager door (P2-TSK-019, INV-CNS-01): a party with no current basis -
        // absence, withdrawal or a lapsed grant, indistinguishably - means the platform may not
        // open the case, and the consumer's answer is a quiet skip. The store must not even be
        // ASKED: an open attempted and converged away would still have been an attempt to
        // process without a basis, and the assertion that catches the gate being consulted
        // after the fact is the store staying untouched.
        ScriptedStore store = new ScriptedStore(true);
        CustomerOpenedOpensCase handler =
                new CustomerOpenedOpensCase(store, PERSON_KIND, NO_BASIS, IDS, CLOCK, audit, outbox);
        EventId consumed = EventId.next(IDS);

        try (CorrelationContext.Scope scope = scopeFor(consumed)) {
            handler.handle(null, customerOpened(consumed, IDS.next()));
        }

        assertThat(store.created).as("the store was never asked to open").isNull();
        assertThat(audit.records).isEmpty();
        assertThat(outbox.envelopes).isEmpty();
    }

    // -----------------------------------------------------------------

    private static CorrelationContext.Scope scopeFor(EventId consumed) {
        // What the shell enters before the handler runs: the producing flow's correlation, with
        // the consumed event as the cause.
        return CorrelationContext.enter(
                new Correlation(
                        CorrelationId.of("flow-1"),
                        CausationId.of(consumed.value().toString())));
    }

    private static ReceivedEvent customerOpened(EventId eventId, UUID customerId) {
        return new ReceivedEvent(
                eventId,
                "party.CustomerOpened",
                1,
                1,
                customerId,
                "Customer",
                Instant.parse("2026-09-09T11:59:00Z"),
                "party",
                CorrelationId.of("flow-1"),
                CausationId.of("command-1"),
                "application/json",
                "{\"customerId\":\"ignored-by-design\"}".getBytes(StandardCharsets.UTF_8));
    }

    /** Echoes the fresh case back as created, or supplies an existing one as converged. */
    private static final class ScriptedStore implements KycCaseStore<Connection> {
        private final boolean create;
        private KycCase created;

        private ScriptedStore(boolean create) {
            this.create = create;
        }

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            if (create) {
                created = fresh;
                return new Opening(fresh, true);
            }
            return new Opening(
                    KycCase.rehydrate(
                            KycCaseId.next(IDS),
                            fresh.customerId(),
                            KycCaseKind.KYC,
                            KycCaseStatus.OPEN,
                            KycPolicyVersion.CURRENT,
                            Instant.parse("2026-09-09T11:00:00Z"),
                            Instant.parse("2026-09-09T11:00:00Z")),
                    false);
        }

        @Override
        public java.util.Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public boolean moveStatus(
                Connection unitOfWork,
                KycCaseId caseId,
                KycCaseStatus from,
                KycCaseStatus to,
                Instant at) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public java.util.Optional<KycCase> findById(Connection unitOfWork, KycCaseId caseId) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public java.util.Optional<KycCase> findLatestFor(
                Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public boolean moveToReadyForDecision(
                Connection unitOfWork, KycCaseId caseId, KycCaseStatus from, Instant at) {
            throw new UnsupportedOperationException("not part of this test");
        }
    }

    private static final class RecordingAudit implements AuditWriter<Connection> {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void append(Connection unitOfWork, AuditRecord record) {
            records.add(record);
        }
    }

    private static final class RecordingOutbox implements OutboxWriter<Connection> {
        private final List<EventEnvelope> envelopes = new ArrayList<>();
        private final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void write(
                Connection unitOfWork, EventEnvelope envelope, byte[] payload, String mediaType) {
            envelopes.add(envelope);
            payloads.add(payload);
        }
    }
}
