package com.finapp.reconciliation;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The matcher's published events (`P8-TSK-011`, ADR-0068 §11) — notifications, never
 * correctness ({@code INV-EVT-04}): dispositions and identifiers, never an amount. The
 * source rides as its UUID (the `P8-TSK-008` recorded stance: a dotted source code is not
 * payload vocabulary).
 */
public final class ReconciliationEvents {

    static final String PRODUCER = "reconciliation";
    static final int EVENT_VERSION = 1;
    static final String RUN_COMPLETED_EVENT_TYPE =
            "reconciliation.ReconciliationRunCompleted";
    static final String EXPECTATION_SETTLED_EVENT_TYPE =
            "reconciliation.SettlementExpectationSettled";
    static final String BREAK_RESOLVED_EVENT_TYPE = "reconciliation.BreakResolved";
    static final String EXPECTATION_OVERDUE_EVENT_TYPE =
            "reconciliation.SettlementExpectationOverdue";

    /**
     * Announced once, when the ageing sweep marks an expectation overdue
     * (`P8-TSK-013`, replacing the planned {@code settlement.SettlementExpectationUnmet})
     * — identifiers, enumerated names and stored dates only, never an amount.
     */
    static void expectationOverdue(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID expectationId,
            ExpectationKind kind,
            String operationRef,
            java.time.LocalDate expectedBy,
            UUID breakId,
            Instant occurredAt,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EXPECTATION_OVERDUE_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        ExpectationId.of(expectationId),
                        "settlement_expectation",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        CausationId.of(expectationId.toString())),
                EventPayload.of()
                        .with("expectationId", expectationId.toString())
                        .with("kind", kind.name())
                        .with("operationRef", operationRef)
                        .with("expectedBy", expectedBy.toString())
                        .with("breakId", breakId.toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private ReconciliationEvents() {}

    /** Announced once, on the run's completing edge, with its counts per outcome. */
    static void runCompleted(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID runId,
            UUID batchId,
            UUID sourceId,
            Map<DecisionOutcome, Long> counts,
            Instant occurredAt,
            CorrelationId correlation) {
        EventPayload payload =
                EventPayload.of()
                        .with("runId", runId.toString())
                        .with("sourceId", sourceId.toString());
        if (batchId != null) {
            payload.with("batchId", batchId.toString());
        }
        for (Map.Entry<DecisionOutcome, Long> count : counts.entrySet()) {
            payload.with(
                    "count" + count.getKey().name(), String.valueOf(count.getValue()));
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        RUN_COMPLETED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        RunId.of(runId),
                        "reconciliation_run",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        CausationId.of(runId.toString())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * Announced once, on the break's terminal edge (`P8-TSK-012` the {@code EVIDENCED}
     * producer; `P8-TSK-015` extends to the person kinds) — identifiers and enumerated
     * names only, never an amount; the unpark's entry rides as its identifier when an
     * offset explained the break.
     */
    static void breakResolved(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID breakId,
            UUID resolutionId,
            ResolutionKind kind,
            ResolutionReasonCode reasonCode,
            java.util.Optional<UUID> journalEntryId,
            Instant occurredAt,
            CorrelationId correlation) {
        EventPayload payload =
                EventPayload.of()
                        .with("breakId", breakId.toString())
                        .with("resolutionId", resolutionId.toString())
                        .with("kind", kind.name())
                        .with("reasonCode", reasonCode.name());
        journalEntryId.ifPresent(entry -> payload.with("journalEntryId", entry.toString()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        BREAK_RESOLVED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        BreakId.of(breakId),
                        "reconciliation_break",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        CausationId.of(resolutionId.toString())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** Announced when an expectation reaches {@code SETTLED} — identifiers only. */
    static void expectationSettled(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID expectationId,
            ExpectationKind kind,
            String operationRef,
            UUID sourceId,
            Instant occurredAt,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EXPECTATION_SETTLED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        ExpectationId.of(expectationId),
                        "settlement_expectation",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        CausationId.of(expectationId.toString())),
                EventPayload.of()
                        .with("expectationId", expectationId.toString())
                        .with("kind", kind.name())
                        .with("operationRef", operationRef)
                        .with("sourceId", sourceId.toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
