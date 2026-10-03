package com.finapp.app.telemetry;

import com.finapp.settlement.ReceptionOutcomeObserver;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.SettlementFileStore;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The composition root's {@link ReceptionOutcomeObserver} (`P8-TSK-002`) — the
 * {@link CommittedRailOutcomes} shape at the settlement door: the reception reports from
 * inside its transaction, and the count rides {@code afterCommit}, so a rolled-back reception
 * or refusal is never published. A meter never fails a committed operation: an increment that
 * throws is logged — its class only — and swallowed.
 *
 * <p>Per instance, per thread, per transaction: nothing is shared and nothing is decided;
 * exactly one instance commits each receipt, so the fleet's {@code sum()} is the tally.
 */
@Slf4j
public final class CommittedReceptionOutcomes implements ReceptionOutcomeObserver {

    private final SettlementMeters meters;
    private final com.finapp.platform.telemetry.Spans spans;

    public CommittedReceptionOutcomes(SettlementMeters meters) {
        this(meters, com.finapp.platform.telemetry.Spans.NONE);
    }

    public CommittedReceptionOutcomes(
            SettlementMeters meters, com.finapp.platform.telemetry.Spans spans) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public com.finapp.platform.telemetry.Spans spans() {
        return spans;
    }

    @Override
    public void received(String sourceCode, SettlementFileStore.ReceiptOutcome outcome) {
        afterCommit(() -> meters.countReceived(sourceCode, outcome));
    }

    @Override
    public void refused(String sourceCode, RefusalReason reason) {
        afterCommit(() -> meters.countRefused(sourceCode, reason));
    }

    private static void afterCommit(Runnable count) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(count);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        safely(count);
                    }
                });
    }

    private static void safely(Runnable count) {
        try {
            count.run();
        } catch (RuntimeException meterFailed) {
            log.warn(
                    "A committed settlement reception could not be counted; the rows stand and"
                            + " only the meter is short: {}",
                    meterFailed.getClass().getSimpleName());
        }
    }
}
