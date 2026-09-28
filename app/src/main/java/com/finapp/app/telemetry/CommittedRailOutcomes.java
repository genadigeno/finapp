package com.finapp.app.telemetry;

import com.finapp.payments.DisputeResponseStatus;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOutcomeObserver;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.WithdrawalStatus;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The composition root's {@link RailOutcomeObserver} (`P7-TSK-015`): every acting judgement the
 * three appliers report is counted into {@link PaymentMeters} <strong>once its transaction
 * commits</strong> — never before, and never for a transaction that rolls back.
 *
 * <h2>Why after commit, and how</h2>
 *
 * <p>The appliers report from inside the caller's transaction, at the moment their conditional
 * transition fired — the only place the acting bit exists. But an acting transition can still be
 * rolled back by the transaction's owner (a posting failure later in the same unit of work, a
 * crash before commit), and a judgement that rolled back never happened: counting it would
 * publish a capture nobody got. So the count rides the transaction's own
 * {@link TransactionSynchronization#afterCommit()}: bound to this thread's one transaction,
 * fired only when it commits, discarded when it does not. Every production caller runs its
 * applier inside a Spring-managed transaction (the payments {@code TransactionRunner} and both
 * webhook doors' {@code TransactionTemplate}s), so the synchronization is always active there;
 * where it is not — a test driving an applier on a bare connection — the count is immediate, the
 * best a caller without a transaction manager can be told.
 *
 * <h2>A meter never fails a committed operation</h2>
 *
 * <p>{@code afterCommit} runs after the money has moved: an exception thrown from it would reach a
 * caller whose operation DID commit, and read as a failure it is not. So an increment that throws
 * is logged — its class only — and swallowed. Telemetry is never financial truth
 * ({@code INV-EVT-02}'s spirit); the rows are the record.
 *
 * <h2>Multi-instance</h2>
 *
 * <p>Per instance, per thread, per transaction: nothing is shared, and nothing is decided. Exactly
 * one instance wins each conditional transition, so exactly one commits the synchronization that
 * counts it — the fleet's {@code sum()} is the judgement count.
 */
@Slf4j
public final class CommittedRailOutcomes implements RailOutcomeObserver {

    private final PaymentMeters meters;

    public CommittedRailOutcomes(PaymentMeters meters) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    public void attemptJudged(RailId rail, PaymentAttemptStatus committed) {
        // A mid-question status (a void redirect, an opened initiation) judged nothing.
        PaymentMeters.Judgement.of(committed)
                .ifPresent(judgement -> afterCommit(() -> meters.attemptJudged(rail, judgement)));
    }

    @Override
    public void refundJudged(RailId rail, RefundStatus committed) {
        PaymentMeters.RefundOutcome.of(committed)
                .ifPresent(outcome -> afterCommit(() -> meters.refundJudged(rail, outcome)));
    }

    @Override
    public void withdrawalJudged(RailId rail, WithdrawalStatus committed) {
        PaymentMeters.WithdrawalOutcome.of(committed)
                .ifPresent(outcome -> afterCommit(() -> meters.withdrawalJudged(rail, outcome)));
    }

    @Override
    public void disputeResponseJudged(RailId rail, DisputeResponseStatus committed) {
        PaymentMeters.ResponseOutcome.of(committed)
                .ifPresent(
                        outcome -> afterCommit(() -> meters.disputeResponseJudged(rail, outcome)));
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
                    "A committed rail judgement could not be counted; the operation stands and"
                            + " only its meter is short: {}",
                    meterFailed.getClass().getSimpleName());
        }
    }
}
