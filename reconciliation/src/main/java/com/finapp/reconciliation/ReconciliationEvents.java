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
 *
 * <p><strong>Causation is the flow's, never the aggregate's own id</strong>
 * ({@code EVENT_ARCHITECTURE.md} §Causation at the root of a flow): no reconciliation flow is
 * caused by a message — each is a request or a scheduled leg — so its events name the flow's
 * correlation identifier, recorded on the run, the audit record and the idempotency record, as
 * {@code ReconciliationBreakRaised} always did. {@code BreakResolved} names the resolution that
 * closed the break: a distinct, recorded cause. *(Corrected 2026-10-02 by the Phase 8 -> 9
 * transition, ARCH-P8-04: the run, expectation and investigation events named their own
 * aggregate, a causal self-loop that never reached the flow that caused them.)*
 */
public final class ReconciliationEvents {

    static final String PRODUCER = "reconciliation";
    static final int EVENT_VERSION = 1;

    /**
     * The two expectation events' version: 2 since the Phase 8 -> 9 transition stopped publishing
     * the expectation's {@code operation_ref}. The expectation's identifier and kind already name
     * it, and the reference is not payload vocabulary: an unmatched confirmation's is
     * {@code rail:schemeReference} - a CONFIDENTIAL scheme reference, and a {@code ':'} the
     * outbox's {@link EventPayload} refuses, so publishing it threw inside the settling and the
     * ageing transactions and neither could ever commit for such an expectation. *(Corrected
     * 2026-10-02 by the Phase 8 -> 9 transition: version 1 carried {@code operationRef}.)*
     */
    static final int EXPECTATION_EVENT_VERSION = 2;

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
     * — identifiers, enumerated names and stored dates only, never an amount, and never the
     * expectation's operation reference (version {@link #EXPECTATION_EVENT_VERSION}).
     */
    static void expectationOverdue(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID expectationId,
            ExpectationKind kind,
            java.time.LocalDate expectedBy,
            UUID breakId,
            Instant occurredAt,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EXPECTATION_OVERDUE_EVENT_TYPE,
                        EXPECTATION_EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        ExpectationId.of(expectationId),
                        "settlement_expectation",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        causedByTheFlow(correlation)),
                EventPayload.of()
                        .with("expectationId", expectationId.toString())
                        .with("kind", kind.name())
                        .with("expectedBy", expectedBy.toString())
                        .with("breakId", breakId.toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private ReconciliationEvents() {}

    /** The flow's root as the cause: no message caused a reconciliation flow (ARCH-P8-04). */
    private static CausationId causedByTheFlow(CorrelationId correlation) {
        return CausationId.of(correlation.value());
    }

    static final String INVESTIGATION_STARTED_EVENT_TYPE =
            "reconciliation.BreakInvestigationStarted";

    /**
     * Announced once, on the break's first assignment ({@code OPEN → INVESTIGATING},
     * `P8-TSK-014`) — never on a reassignment, a note, a link or a reclassification: a note
     * body never enters an event.
     */
    static void investigationStarted(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID breakId,
            String assigneeId,
            Instant occurredAt,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        INVESTIGATION_STARTED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        BreakId.of(breakId),
                        "reconciliation_break",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        causedByTheFlow(correlation)),
                EventPayload.of()
                        .with("breakId", breakId.toString())
                        .with("assigneeId", assigneeId)
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

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
                        causedByTheFlow(correlation)),
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

    /**
     * Announced when an expectation reaches {@code SETTLED} — identifiers only, never the
     * expectation's operation reference (version {@link #EXPECTATION_EVENT_VERSION}).
     */
    static void expectationSettled(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID expectationId,
            ExpectationKind kind,
            UUID sourceId,
            Instant occurredAt,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EXPECTATION_SETTLED_EVENT_TYPE,
                        EXPECTATION_EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        ExpectationId.of(expectationId),
                        "settlement_expectation",
                        occurredAt,
                        PRODUCER,
                        correlation,
                        causedByTheFlow(correlation)),
                EventPayload.of()
                        .with("expectationId", expectationId.toString())
                        .with("kind", kind.name())
                        .with("sourceId", sourceId.toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
