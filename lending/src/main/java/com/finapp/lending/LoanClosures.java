package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanReceivables.Accounts;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A loan's closure - a consequence, never a command (`P11-TSK-018`; a credit line's, `P11-TSK-023`; {@code INV-LND-08},
 * {@code INV-LIFE-04}, ADR-0090, ADR-0100 point 8, {@code LENDING_LIFECYCLES.md} section 4.5, {@code PHASE_11_PLAN.md}
 * sections 5.3, 12.5 and 12.6). Called at the end of the transactions that can bring an account's last receivable to zero -
 * a repayment ({@link Repayments}, the customer's or an automatic collection) and the servicing step after its
 * credit-balance application ({@link LoanServicing}) - and by a line's closure request ({@link LineClosures}), under the
 * caller's loan row lock (L3), on the caller's unit of work. When the account is settled it closes in that same
 * transaction; otherwise nothing is written.
 *
 * <h2>Who closes</h2>
 *
 * <ul>
 *   <li>an {@code ACTIVE} instalment loan - {@code REPAID} (a payoff's {@code PAID_OFF_EARLY} is `P11-TSK-026`'s);
 *   <li>a {@code CLOSING} credit line - {@code CLOSED_BY_CUSTOMER}: the customer asked, and the transaction that zeroes its
 *       last receivable closes it (`P11-TSK-023`). An {@code ACTIVE} line whose receivables reach zero stays open - the
 *       customer may draw again; it closes at its closure request when nothing is outstanding then ({@link #settlement}
 *       and {@link #close}, in the request's own transaction).
 * </ul>
 *
 * <h2>Settled</h2>
 *
 * <ol>
 *   <li>every receivable zero - {@code LOAN_PRINCIPAL}, {@code LOAN_PRINCIPAL_DUE}, {@code LOAN_INTEREST_ACCRUED},
 *       {@code LOAN_INTEREST_DUE}, {@code LOAN_FEES_DUE}, each DR - CR over its journal lines under L3;
 *   <li><strong>and no elapsed date left unaccrued with principal outstanding at its end</strong>: a date that ended with a
 *       positive base has earned interest the ledger does not hold yet (the servicing step accrues it), so an account whose
 *       recorded receivables are zero is not settled while one exists - closing it would forgo that interest. The next
 *       servicing step accrues and bills (or states) it, and closes the account when it is paid. The accrual start is an
 *       instalment loan's {@code accrual_start}, a line's opening business date ({@link LoanServicing#lineAccrualStart}).
 * </ol>
 *
 * <h2>The closure</h2>
 *
 * <p>The credit balance - money held beyond every receivable - is refunded to the borrower's wallet: {@code DR
 * LOAN_CREDIT_BALANCE / CR CUSTOMER_WALLET} under {@code lending.closure-refund:<loan>}, after (L7) the wallet's ledger row
 * {@code FOR UPDATE} (the lock order: the wallet always after the loan). Then the {@code loan_closure} row, the edge to
 * {@code CLOSED} (conditional, and held by the edge function beside the row), its history row and {@code lending.LoanClosed}
 * - so after the commit all six balances are zero and nothing is owed either way. A wallet that cannot be credited now
 * (none, or not {@code ACTIVE}) leaves the account open with its credit balance, nothing written; the next servicing step
 * closes it once it can (no principal is outstanding, so nothing accrues meanwhile).
 */
@RequiredArgsConstructor
public final class LoanClosures {

    /** The refund's ledger key prefix: {@code lending.closure-refund:<loan>} - a loan closes once. */
    public static final String REFUND_KEY_PREFIX = "lending.closure-refund:";

    /** The event a closure publishes. */
    public static final String CLOSED_EVENT = "lending.LoanClosed";

    @NonNull private final LoanStore loans;
    @NonNull private final LoanServicingStore servicing;
    @NonNull private final InstalmentBillingStore billings;
    @NonNull private final LoanWallets wallets;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A settled account, ready to close: what its credit balance holds and - when that is positive - the wallet the refund
     * credits, its ledger row already locked (L7) and {@code ACTIVE}.
     */
    record Settlement(Money refund, Optional<LedgerAccountId> wallet, Accounts accounts) {

        Settlement {
            Objects.requireNonNull(refund, "refund");
            Objects.requireNonNull(wallet, "wallet");
            Objects.requireNonNull(accounts, "accounts");
            if (refund.isPositive() != wallet.isPresent()) {
                throw new IllegalArgumentException("a refund's wallet exists exactly when a credit balance is refunded");
            }
        }

        /** Never the figure ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Settlement[redacted]";
        }
    }

    /**
     * Closes {@code loan} if it is settled and its state closes by settlement - an {@code ACTIVE} instalment loan
     * ({@code REPAID}) or a {@code CLOSING} line ({@code CLOSED_BY_CUSTOMER}) - on the caller's unit of work, under the
     * caller's L3.
     *
     * @param zone the agreement's pinned servicing zone
     * @param today the business date of the database clock in the pinned zone, read after L3
     * @param repayment the repayment whose transaction this is, if one
     * @param actor the closing act's actor - asked for only when the loan closes (the servicing step's is the scope's)
     * @return the closure, or empty when the loan is not settled (or its refund cannot be credited now)
     */
    public Optional<LoanClosure> closeIfSettled(Connection unitOfWork, Loan loan, LoanAgreement agreement, ZoneId zone,
            LocalDate today, Optional<UUID> repayment, Supplier<Actor> actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        ClosureReason reason;
        if (loan.kind() == LoanKind.INSTALMENT && loan.status() == LoanStatus.ACTIVE) {
            reason = ClosureReason.REPAID;
        } else if (loan.kind() == LoanKind.REVOLVING && loan.status() == LoanStatus.CLOSING) {
            reason = ClosureReason.CLOSED_BY_CUSTOMER;
        } else {
            return Optional.empty();
        }
        return settlement(unitOfWork, loan, agreement, zone, today)
                .map(settled -> close(unitOfWork, loan, settled, reason, today, repayment, actor, correlation));
    }

    /**
     * Whether {@code loan} is settled now - every receivable zero and every elapsed date accrued - and, when its credit
     * balance is positive, the wallet that refund credits: (L7) its ledger row locked {@code FOR UPDATE}, after the
     * caller's L3. Empty when not settled, or when the refund cannot be credited now. Writes nothing.
     */
    Optional<Settlement> settlement(Connection unitOfWork, Loan loan, LoanAgreement agreement, ZoneId zone,
            LocalDate today) {
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        Accounts accounts = receivables.accounts(unitOfWork, loan.id(), loan.currency());
        if (!receivables.everyReceivableZero(unitOfWork, accounts, zero)
                || !accruedThroughYesterday(unitOfWork, loan, accounts, zone, today, zero)) {
            return Optional.empty();
        }
        Money refund = receivables.creditHeld(unitOfWork, accounts, zero);
        if (!refund.isPositive()) {
            return Optional.of(new Settlement(refund, Optional.empty(), accounts));
        }
        // (L7) The wallet's ledger row, after the loan: the refund is posted only to an account that accepts it now.
        Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, loan.party(), loan.currency());
        if (wallet.isEmpty()) {
            return Optional.empty();
        }
        Optional<LedgerAccount> locked = ledgerAccounts.lockForUpdate(unitOfWork, wallet.get());
        if (locked.isEmpty() || locked.get().status() != LedgerAccountStatus.ACTIVE) {
            return Optional.empty();
        }
        return Optional.of(new Settlement(refund, wallet, accounts));
    }

    /**
     * Closes a settled {@code loan} for {@code reason} - the refund, the {@code loan_closure} row, the edge from the loan's
     * status, its history row and {@code lending.LoanClosed} - on the caller's unit of work, under its L3 (and L7 when the
     * settlement holds a wallet).
     */
    LoanClosure close(Connection unitOfWork, Loan loan, Settlement settled, ClosureReason reason, LocalDate today,
            Optional<UUID> repayment, Supplier<Actor> actor, CorrelationId correlation) {
        Optional<JournalEntryId> refundEntry = settled.wallet().map(wallet ->
                refund(unitOfWork, loan, settled.refund(), settled.accounts().creditBalance(), wallet, today));
        LoanClosure closure = new LoanClosure(ids.next(), loan.id(), reason, settled.refund(), refundEntry, repayment);
        if (!loans.close(unitOfWork, closure, loan.status(), correlation)) {
            throw new IllegalStateException("the locked loan did not close under its own row lock");
        }
        loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), loan.id(), Optional.of(loan.status()),
                LoanStatus.CLOSED, reason.name(), actor.get(), correlation));
        publish(unitOfWork, loan, closure, correlation);
        return closure;
    }

    /**
     * Whether every elapsed date of the account - through yesterday in the pinned zone - is accrued, or ended with no
     * principal outstanding: the base {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} over the journal lines dated on or
     * before each date after the latest accrual, read in ONE statement (the accrual step's own rule, ADR-0094).
     */
    private boolean accruedThroughYesterday(
            Connection unitOfWork, Loan loan, Accounts accounts, ZoneId zone, LocalDate today, Money zero) {
        Optional<LocalDate> start = loan.kind() == LoanKind.INSTALMENT
                ? servicing.accrualStartOf(unitOfWork, loan.id())
                : Optional.of(LoanServicing.lineAccrualStart(loan, zone));
        if (start.isEmpty()) {
            return false; // Nothing has started accruing: the loan is not in servicing yet.
        }
        LocalDate lastElapsed = today.minusDays(1);
        LocalDate from = servicing.latestAccrual(unitOfWork, loan.id())
                .map(row -> row.accrual().date().plusDays(1))
                .filter(next -> next.isAfter(start.get()))
                .orElse(start.get());
        if (from.isAfter(lastElapsed)) {
            return true;
        }
        List<PurposeBalances.DatedLine> lines =
                balances.datedLinesOn(unitOfWork, Set.of(accounts.principal(), accounts.principalDue()), lastElapsed);
        Money base = zero;
        int next = 0;
        for (LocalDate date = from; !date.isAfter(lastElapsed); date = date.plusDays(1)) {
            while (next < lines.size() && !lines.get(next).valueDate().isAfter(date)) {
                PurposeBalances.DatedLine line = lines.get(next++);
                base = line.direction() == Direction.DEBIT ? base.plus(line.amount()) : base.minus(line.amount());
            }
            if (base.isPositive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The refund: {@code DR LOAN_CREDIT_BALANCE / CR CUSTOMER_WALLET}, dated today, its reference the loan - one per loan
     * under its key, so a replay (an entry for a loan with no closure) is a defect, never a second refund.
     */
    private JournalEntryId refund(Connection unitOfWork, Loan loan, Money refund, LedgerAccountId creditBalance,
            LedgerAccountId wallet, LocalDate today) {
        PostingResult result = postings.post(unitOfWork, new PostingCommand(REFUND_KEY_PREFIX + loan.id().value(), today,
                today, loan.id().value().toString(), List.of(
                        new JournalLine(creditBalance, Direction.DEBIT, refund),
                        new JournalLine(wallet, Direction.CREDIT, refund))));
        if (result.replayed()) {
            throw new IllegalStateException("a closure refund exists for a loan that is not closed (INV-LND-08)");
        }
        return result.entryId();
    }

    /** {@code lending.LoanClosed} - the loan, the reason, the refund and its entry, the closing repayment; no wallet. */
    private void publish(Connection unitOfWork, Loan loan, LoanClosure closure, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("closureId", closure.id().toString())
                .with("reason", closure.reason().name())
                .with("refundMinor", Long.toString(closure.refund().minorUnits()))
                .with("scale", Integer.toString(closure.refund().scale()))
                .with("currency", closure.refund().currency().code());
        if (closure.refundEntry().isPresent()) {
            payload = payload.with("refundJournalEntryId", closure.refundEntry().get().value().toString());
        }
        if (closure.repayment().isPresent()) {
            payload = payload.with("repaymentId", closure.repayment().get().toString());
        }
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        CLOSED_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LoanAcceptances.LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
