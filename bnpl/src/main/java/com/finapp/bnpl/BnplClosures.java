package com.finapp.bnpl;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A BNPL obligation's closure - a consequence, never a command (`P12-TSK-018`; {@code PHASE_12_PLAN.md} sections 5.5 and
 * 12.11 F6's refund arm, {@code BNPL_LIFECYCLES.md} section 4.7; lending's {@code LoanClosures}; {@code INV-LND-08} by
 * analogy, {@code INV-LIFE-04}). <strong>One implementation, called from both zeroing transactions</strong>: the
 * customer's repayment ({@link BnplRepayments}, T-rep) and the servicing step after its credit-balance applications
 * ({@link BnplServicing}, T-svc's closure slot) - later the collection (`-019`), refund (`-021`) and payoff (`-022`) -
 * always under the caller's B4, on the caller's unit of work. When the obligation is settled it closes in that same
 * transaction; otherwise nothing is written.
 *
 * <h2>Settled</h2>
 *
 * <ol>
 *   <li>every receivable zero - {@code BNPL_PRINCIPAL}, {@code BNPL_PRINCIPAL_DUE}, {@code BNPL_INTEREST_ACCRUED},
 *       {@code BNPL_INTEREST_DUE}, {@code BNPL_FEES_DUE}, each a ledger balance under B4 - and every instalment of the
 *       latest schedule billed (the rows' side {@code bnpl V010}'s closure trigger re-judges, with every billed component
 *       paid in full);
 *   <li><strong>and, pay-in-N, no elapsed date left unaccrued with principal outstanding at its end</strong>: a date that
 *       ended with a positive base ({@code BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE} over the journal lines valued on or before
 *       it) inside the accrual span - which ends at the final due date - has earned interest the ledger does not hold yet,
 *       so closing would forgo it: T-acc's own {@link BnplAccruals#caughtUp} (`P12-TSK-017`), one rule for every act. The
 *       next servicing step (T-acc first) closes the obligation once that interest is billed and paid.
 * </ol>
 *
 * <h2>The closure</h2>
 *
 * <p>The credit balance - money held beyond every receivable - is refunded to the customer's existing wallet: {@code DR
 * BNPL_CREDIT_BALANCE / CR CUSTOMER_WALLET} under {@code bnpl.closure-refund:<obligation>}, after <strong>(P)</strong> the
 * wallet's row {@code FOR UPDATE} (the repayment holds it already; the servicing step takes it here, after B4) and
 * <strong>(L8)</strong> the two projection rows. Then the {@code bnpl_obligation_closure} row, the conditional edge
 * {@code ACTIVE -> CLOSED (REPAID)} (held by the edge function beside the row), its history row and
 * {@code bnpl.BnplAgreementClosed} - so after the commit all six balances are zero and nothing is owed either way. A
 * wallet that cannot be credited now (none, or not {@code ACTIVE}) leaves the obligation open with its credit balance,
 * nothing written; the next servicing step closes it once it can (no principal is outstanding, so nothing accrues
 * meanwhile).
 */
@RequiredArgsConstructor
public final class BnplClosures {

    /** The refund's ledger key prefix: {@code bnpl.closure-refund:<obligation>} - an obligation closes once. */
    public static final String REFUND_KEY_PREFIX = "bnpl.closure-refund:";

    /** The event a closure publishes. */
    public static final String CLOSED_EVENT = "bnpl.BnplAgreementClosed";

    @NonNull private final BnplRepaymentStore store;
    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * The obligation as its closing transaction holds it: under B4, its six accounts, the business date of the database
     * clock read after the lock, the repayment whose transaction this is (if one) and the wallet that transaction already
     * holds under (P) (the repayment's), if any.
     */
    public record Closing(BnplServicingStore.Locked locked, BnplLedgerAccounts accounts, LocalDate today,
            Optional<UUID> repayment, Optional<LedgerAccountId> walletHeld, Optional<UUID> refund, boolean payoff) {

        public Closing {
            Objects.requireNonNull(locked, "locked must not be null");
            Objects.requireNonNull(accounts, "accounts must not be null");
            Objects.requireNonNull(today, "today must not be null");
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(walletHeld, "walletHeld must not be null");
            Objects.requireNonNull(refund, "refund must not be null");
            if (repayment.isPresent() && refund.isPresent()) {
                throw new IllegalArgumentException("one closing act: a repayment or a refund");
            }
            if (payoff && refund.isPresent()) {
                throw new IllegalArgumentException("one closing act: a payoff or a refund");
            }
        }

        /** A closing transaction that is no payoff's - the repayment's, the servicing step's or a refund's. */
        public Closing(BnplServicingStore.Locked locked, BnplLedgerAccounts accounts, LocalDate today,
                Optional<UUID> repayment, Optional<LedgerAccountId> walletHeld, Optional<UUID> refund) {
            this(locked, accounts, today, repayment, walletHeld, refund, false);
        }

        /**
         * The payoff's closing transaction (`P12-TSK-022`, T-pay): its {@code PAYOFF} repayment, if money moved, and the
         * wallet it holds under (P), if any - the closure's reason {@code PAID_OFF_EARLY}.
         */
        static Closing payoff(BnplServicingStore.Locked locked, BnplLedgerAccounts accounts, LocalDate today,
                Optional<UUID> repayment, Optional<LedgerAccountId> walletHeld) {
            return new Closing(locked, accounts, today, repayment, walletHeld, Optional.empty(), true);
        }

        /** A closing transaction that is no refund's - the repayment's or the servicing step's. */
        public Closing(BnplServicingStore.Locked locked, BnplLedgerAccounts accounts, LocalDate today,
                Optional<UUID> repayment, Optional<LedgerAccountId> walletHeld) {
            this(locked, accounts, today, repayment, walletHeld, Optional.empty());
        }
    }

    /**
     * Closes the obligation if it is settled, on the caller's unit of work under its B4 - for the reason its closing act
     * and its purchase name (`P12-TSK-021`, {@code BNPL_LIFECYCLES.md} section 4.7): {@code REFUNDED} when the purchase is
     * {@code FULLY_REFUNDED} (refunds settled it, whichever transaction refunds the credit), {@code REFUNDED_PARTIALLY_REPAID}
     * when a partial refund's own transaction settles it, else {@code REPAID}.
     *
     * @param actor the closing act's actor - asked for only when the obligation closes (the servicing step's is the
     *     scope's system actor)
     * @return the closure, or empty when the obligation is not settled, or its refund cannot be credited now
     */
    public Optional<BnplClosure> closeIfSettled(Connection unitOfWork, Closing closing, Supplier<Actor> actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(closing, "closing");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        BnplObligation obligation = closing.locked().obligation();
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            return Optional.empty();
        }
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        Money refund = receivables.creditHeld(unitOfWork, closing.accounts());
        BnplPurchaseStore.StoredSchedule schedule = servicing.latestSchedule(unitOfWork, obligation.id())
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        if (!receivables.everyReceivableZero(unitOfWork, closing.accounts())
                || !everyInstalmentBilled(unitOfWork, obligation.id(), schedule)
                // Pay-in-N: T-acc's own "caught up" (`P12-TSK-017`) - no elapsed date of the accrual span, which ends at
                // the final due date, left unaccrued with principal outstanding at its end. One rule for every act.
                || !BnplAccruals.caughtUp(unitOfWork, servicing, purchases, book, closing.locked(), closing.today())) {
            return Optional.empty();
        }
        if (refund.isNegative()) {
            throw new AllocationAnomaly("a credit balance is never negative - nothing is closed (INV-BNPL-01)");
        }
        Optional<JournalEntryId> refundEntry = Optional.empty();
        if (refund.isPositive()) {
            Optional<LedgerAccountId> wallet = closing.walletHeld().isPresent() ? closing.walletHeld()
                    : lockedWallet(unitOfWork, obligation);
            if (wallet.isEmpty()) {
                return Optional.empty();
            }
            refundEntry = Optional.of(refund(unitOfWork, obligation, refund, closing.accounts().creditBalance().id(),
                    wallet.get(), closing.today()));
        }
        BnplClosureReason reason = reasonOf(unitOfWork, obligation, closing);
        BnplClosure closure = new BnplClosure(ids.next(), obligation.id(), reason, refund, refundEntry,
                closing.repayment(), closing.refund(), correlation);
        store.insertClosure(unitOfWork, closure);
        if (!store.close(unitOfWork, obligation.id(), reason)) {
            throw new IllegalStateException("the locked obligation did not close under its own row lock");
        }
        purchases.appendObligationEvent(unitOfWork, new BnplPurchaseStore.ObligationHistoryRow(ids.next(),
                obligation.id(), Optional.of(BnplObligationStatus.ACTIVE), BnplObligationStatus.CLOSED,
                reason.name(), actor.get(), correlation));
        publish(unitOfWork, closing.locked(), closure);
        return Optional.of(closure);
    }

    /**
     * The closure's reason (`P12-TSK-021`): {@code REFUNDED} for a purchase its refunds fully refunded, read on the
     * caller's unit of work (the refund moves it under B5 and B4, and every transaction that can close holds B4);
     * {@code REFUNDED_PARTIALLY_REPAID} when a partial refund's own transaction closes it; {@code REPAID} otherwise -
     * earlier partial refunds notwithstanding, the closing act names it ({@code BNPL_LIFECYCLES.md} section 4.7).
     */
    private BnplClosureReason reasonOf(Connection unitOfWork, BnplObligation obligation, Closing closing) {
        if (closing.payoff()) {
            // The payoff names its own reason (`P12-TSK-022`): every receivable settled before the schedule ended.
            return BnplClosureReason.PAID_OFF_EARLY;
        }
        if (purchases.purchaseStatus(unitOfWork, obligation.purchase())
                .filter(BnplPurchaseStatus.FULLY_REFUNDED::equals).isPresent()) {
            return BnplClosureReason.REFUNDED;
        }
        return closing.refund().isPresent() ? BnplClosureReason.REFUNDED_PARTIALLY_REPAID : BnplClosureReason.REPAID;
    }

    /**
     * Every instalment of the obligation's latest schedule version billed - the last receivable is never unbilled. Judged
     * by due date (`P12-TSK-021`): a billing is dated its instalment's due date, one per date, and a refund's schedule
     * version carries its billed instalments as new rows beside the billings that name the earlier version's.
     */
    private boolean everyInstalmentBilled(Connection unitOfWork, BnplObligationId obligation,
            BnplPurchaseStore.StoredSchedule schedule) {
        Set<LocalDate> billed = new HashSet<>();
        for (BnplPurchaseStore.BilledItem item : purchases.billedItems(unitOfWork, obligation)) {
            billed.add(item.dueDate());
        }
        return schedule.instalments().stream().allMatch(instalment -> billed.contains(instalment.dueDate()));
    }


    /**
     * (P) The customer's existing wallet, its row {@code FOR UPDATE} after B4 - empty when there is none or it is not
     * {@code ACTIVE}: the refund is posted only to an account that accepts it now.
     */
    private Optional<LedgerAccountId> lockedWallet(Connection unitOfWork, BnplObligation obligation) {
        Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, obligation.party(), obligation.currency());
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        Optional<LedgerAccountStatus> status = book.lockForUpdate(unitOfWork, wallet.get());
        return status.filter(LedgerAccountStatus.ACTIVE::equals).isPresent() ? wallet : Optional.empty();
    }

    /**
     * The refund: (L8) the credit balance's and the wallet's projection rows in the ledger's one order (re-entrant where the
     * caller already holds them), then {@code DR BNPL_CREDIT_BALANCE / CR CUSTOMER_WALLET}, dated today, its reference the
     * obligation - one per obligation under its key, so a replay is a defect, never a second refund.
     */
    private JournalEntryId refund(Connection unitOfWork, BnplObligation obligation, Money refund,
            LedgerAccountId creditBalance, LedgerAccountId wallet, LocalDate today) {
        book.lockBalancesInOrder(unitOfWork, List.of(creditBalance, wallet));
        return book.post(unitOfWork, new PostingCommand(REFUND_KEY_PREFIX + obligation.id().value(), today, today,
                obligation.id().value().toString(), List.of(
                        new JournalLine(creditBalance, Direction.DEBIT, refund),
                        new JournalLine(wallet, Direction.CREDIT, refund))));
    }

    /**
     * {@code bnpl.BnplAgreementClosed} - the obligation, its agreement, the closure, the reason, the refund and its entry,
     * the closing repayment; never the wallet or the party.
     */
    private void publish(Connection unitOfWork, BnplServicingStore.Locked locked, BnplClosure closure) {
        EventPayload payload = EventPayload.of()
                .with("obligationId", closure.obligation().value().toString())
                .with("agreementId", locked.agreement().value().toString())
                .with("closureId", closure.id().toString())
                .with("reason", closure.reason().name())
                .with("currency", closure.refund().currency().code())
                .with("scale", Integer.toString(closure.refund().scale()))
                .with("refundMinor", Long.toString(closure.refund().minorUnits()));
        if (closure.refundEntry().isPresent()) {
            payload = payload.with("refundJournalEntryId", closure.refundEntry().get().value().toString());
        }
        if (closure.repayment().isPresent()) {
            payload = payload.with("repaymentId", closure.repayment().get().toString());
        }
        CorrelationId correlation = closure.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        CLOSED_EVENT,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        closure.obligation(),
                        BnplAcceptances.OBLIGATION_AGGREGATE_TYPE,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
