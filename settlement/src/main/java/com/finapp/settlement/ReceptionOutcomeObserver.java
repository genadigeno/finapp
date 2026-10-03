package com.finapp.settlement;

/**
 * Where the door reports what it committed (`P8-TSK-002`) — the {@code RailOutcomeObserver}
 * shape: called from inside the reception's transaction at the moment the fact is written,
 * and counted by the composition root only once that transaction commits
 * ({@code CommittedReceptionOutcomes}), so a rolled-back reception is never published.
 *
 * <p>The meters are a second, independent tally, never truth: the rows are the record, and a
 * failing meter never fails a committed reception.
 */
public interface ReceptionOutcomeObserver {

    /** A delivery landed ({@code NEW}) or converged ({@code DUPLICATE}). */
    void received(String sourceCode, SettlementFileStore.ReceiptOutcome outcome);

    /** A delivery was refused at the door — alertable from the first file (ADR-0066 §4). */
    void refused(String sourceCode, RefusalReason reason);

    /** Where the door records its {@code settlement.receive} span (`P8-TSK-024`). */
    default com.finapp.platform.telemetry.Spans spans() {
        return com.finapp.platform.telemetry.Spans.NONE;
    }
}
