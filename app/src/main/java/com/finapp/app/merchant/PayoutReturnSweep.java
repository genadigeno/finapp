package com.finapp.app.merchant;

import com.finapp.merchant.MerchantTransactionRunner;
import com.finapp.merchant.PayoutReturns;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.WaitingPayoutReturns;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The payout return worker (`P8-TSK-019`, ADR-0073 §4): applies returns the beneficiary banks
 * made, from the payout provider's evidence, with no person — transition decision O2's automated
 * path, the four-eyes transfer its fallback. Composed here because the act spans three modules:
 * reconciliation's waiting item, settlement's stored acceptance date, merchant's
 * {@link PayoutReturns#apply}.
 *
 * <h2>One transaction per item, judged on the locked row</h2>
 *
 * <p>Each tick walks the waiting returns in claimant order, a bounded page at a time by keyset,
 * lock-free, to the end — a return that cannot apply yet waits out its grace, and a fixed first
 * page of them would starve every return behind it — and for each opens its own transaction
 * that FIRST re-reads the item {@code FOR SHARE} and proceeds only
 * while it is still {@code UNMATCHED} (ADR-0073 §7): the grace leg locks the same row {@code FOR
 * UPDATE}, so either the worker's return commits first and the grace leg, on the row, finds the
 * return's expectation and allocates it — or the grace leg parks the item first and the worker,
 * on the row, writes nothing. A parked or resolved item is never applied: a return beside a
 * parked item would credit the merchant twice once a person transferred the suspense.
 *
 * <p>The worker allocates NOTHING — the matcher's rematch leg finds the new expectation through
 * the operation-anchored rule — and takes no advisory namespace 4: it sits outside every matching
 * chunk, so no matcher ever waits on a merchant row. A failing row is contained, logged by class,
 * and retried next tick while the item waits out its grace.
 */
@Slf4j
public final class PayoutReturnSweep {

    private final MerchantTransactionRunner transactions;
    private final WaitingPayoutReturns waiting;
    private final SettlementBatchStore<Connection> batches;
    private final PayoutReturns returns;
    private final int batchSize;

    public PayoutReturnSweep(
            MerchantTransactionRunner transactions,
            WaitingPayoutReturns waiting,
            SettlementBatchStore<Connection> batches,
            PayoutReturns returns,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.waiting = Objects.requireNonNull(waiting, "waiting must not be null");
        this.batches = Objects.requireNonNull(batches, "batches must not be null");
        this.returns = Objects.requireNonNull(returns, "returns must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * One tick's tally — telemetry, never the count of record.
     *
     * @param applied this tick's own applications
     * @param notApplicable items whose return could not apply now (nothing written; retried
     *     while the item waits out its grace)
     * @param skipped items no longer waiting on their locked re-read, or already returned
     */
    public record SweepResult(
            int candidates, int applied, int notApplicable, int skipped, int failedRows) {}

    /**
     * One sweep: every waiting return, a bounded page at a time, one transaction per item, as
     * the platform. The walk ends at a short page; a return that becomes waiting behind the
     * walk's position is the next tick's.
     */
    public SweepResult sweep() {
        int candidates = 0;
        Tally tally = new Tally();
        Optional<UUID> after = Optional.empty();
        while (true) {
            Optional<UUID> cursor = after;
            List<WaitingPayoutReturns.WaitingReturn> page =
                    transactions.inTransaction(
                            unitOfWork -> waiting.page(unitOfWork, cursor, batchSize));
            candidates += page.size();
            for (WaitingPayoutReturns.WaitingReturn candidate : page) {
                applyContained(candidate, tally);
            }
            if (page.size() < batchSize) {
                return tally.result(candidates);
            }
            after = Optional.of(page.get(page.size() - 1).itemId());
        }
    }

    /** One item in its own flow and transaction; a failure contained, counted, retried later. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    private void applyContained(WaitingPayoutReturns.WaitingReturn candidate, Tally tally) {
        // The item's own flow continues; the item is the cause (ADR-0073 section 4).
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(candidate.correlation())
                                        .causing(CausationId.of(candidate.itemId().toString())));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            Optional<PayoutReturns.Outcome> outcome =
                    transactions.inTransaction(
                            unitOfWork -> applyOne(unitOfWork, candidate.itemId()));
            if (outcome.isPresent() && outcome.get() == PayoutReturns.Outcome.APPLIED) {
                tally.applied++;
            } else if (outcome.isEmpty()
                    || outcome.get() == PayoutReturns.Outcome.ALREADY_RETURNED) {
                tally.skipped++;
            } else {
                tally.notApplicable++;
            }
        } catch (RuntimeException failure) {
            tally.failedRows++;
            // The class only: a JDBC message can name identifiers and amounts.
            log.warn(
                    "A payout return failed and rolled back; the next tick retries: {}",
                    failure.getClass().getSimpleName());
        }
    }

    /** One tick's running counts - local to the tick, never shared. */
    private static final class Tally {
        private int applied;
        private int notApplicable;
        private int skipped;
        private int failedRows;

        SweepResult result(int candidates) {
            return new SweepResult(candidates, applied, notApplicable, skipped, failedRows);
        }
    }

    /**
     * The item's transaction: the share-locked re-read FIRST, then the application — or, when
     * the item moved on, nothing at all.
     */
    Optional<PayoutReturns.Outcome> applyOne(Connection unitOfWork, UUID itemId) {
        Optional<WaitingPayoutReturns.WaitingReturn> locked =
                waiting.lockWaiting(unitOfWork, itemId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        WaitingPayoutReturns.WaitingReturn item = locked.get();
        LocalDate acceptedOn =
                batches.acceptedOnOf(unitOfWork, item.batchId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an item's batch is accepted by construction:"
                                                        + " its run was born in the acceptance"
                                                        + " transaction"));
        return Optional.of(
                returns.apply(
                                unitOfWork,
                                new PayoutReturns.ReturnEvidence(
                                        item.itemId(),
                                        item.providerReference(),
                                        item.ourReference(),
                                        item.amount(),
                                        acceptedOn,
                                        item.settlementDate(),
                                        CorrelationContext.current().orElseThrow()))
                        .outcome());
    }
}
