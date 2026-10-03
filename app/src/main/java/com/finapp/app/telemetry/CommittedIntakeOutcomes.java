package com.finapp.app.telemetry;

import com.finapp.platform.telemetry.Spans;
import com.finapp.settlement.IntakeOutcomeObserver;
import com.finapp.settlement.RejectionCode;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The composition root's {@link IntakeOutcomeObserver} (`P8-TSK-008`) — the
 * {@link CommittedReceptionOutcomes} shape at the parse leg: the verdict reports from inside
 * its one transaction, the count rides {@code afterCommit}, so a rolled-back parse or
 * rejection is never published. A meter never fails a committed verdict.
 */
@Slf4j
public final class CommittedIntakeOutcomes implements IntakeOutcomeObserver {

    private final SettlementMeters meters;
    private final Spans spans;

    public CommittedIntakeOutcomes(SettlementMeters meters) {
        this(meters, Spans.NONE);
    }

    public CommittedIntakeOutcomes(SettlementMeters meters, Spans spans) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public void accepted(String sourceCode, Duration sinceReceipt) {
        afterCommit(() -> meters.countAccepted(sourceCode, sinceReceipt));
    }

    @Override
    public Spans spans() {
        return spans;
    }

    @Override
    public void parsed(String sourceCode, Duration sinceReceipt) {
        afterCommit(() -> meters.recordParseLatency(sourceCode, sinceReceipt));
    }

    @Override
    public void rejected(String sourceCode, RejectionCode code, Duration sinceReceipt) {
        afterCommit(
                () -> {
                    meters.countRejected(sourceCode, code);
                    meters.recordParseLatency(sourceCode, sinceReceipt);
                });
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
                    "A committed intake verdict could not be counted; the rows stand and only"
                            + " the meter is short: {}",
                    meterFailed.getClass().getSimpleName());
        }
    }
}
