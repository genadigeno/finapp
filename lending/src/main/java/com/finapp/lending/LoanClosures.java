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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An instalment loan's closure - a consequence, never a command (`P11-TSK-018`; {@code INV-LND-08}, {@code INV-LIFE-04},
 * ADR-0090, {@code LENDING_LIFECYCLES.md} section 4.5, {@code PHASE_11_PLAN.md} section 12.5). Called at the end of the
 * transactions that can bring a loan's last receivable to zero - a repayment ({@link Repayments}) and the servicing step
 * after its credit-balance application ({@link LoanServicing}) - under the caller's loan row lock (L3), on the caller's
 * unit of work. When the loan is settled it closes in that same transaction; otherwise nothing is written.
 *
 * <h2>Settled</h2>
 *
 * <ol>
 *   <li>an {@code ACTIVE} instalment loan (a line closes by its own request, `P11-TSK-023`; a payoff is `P11-TSK-026`'s);
 *   <li>every receivable zero - {@code LOAN_PRINCIPAL}, {@code LOAN_PRINCIPAL_DUE}, {@code LOAN_INTEREST_ACCRUED},
 *       {@code LOAN_INTEREST_DUE}, {@code LOAN_FEES_DUE}, each DR - CR over its journal lines under L3;
 *   <li><strong>and no elapsed date left unaccrued with principal outstanding at its end</strong>: a date that ended with a
 *       positive base has earned interest the ledger does not hold yet (the servicing step accrues it), so a loan whose
 *       recorded receivables are zero is not settled while one exists - closing it would forgo that interest. The next
 *       servicing step accrues and bills it, and closes the loan when it is paid.
 * </ol>
 *
 * <h2>The closure</h2>
 *
 * <p>The credit balance - money held beyond every receivable - is refunded to the borrower's wallet: {@code DR
 * LOAN_CREDIT_BALANCE / CR CUSTOMER_WALLET} under {@code lending.closure-refund:<loan>}, after (L7) the wallet's ledger row
 * {@code FOR UPDATE} (the lock order: the wallet always after the loan). Then the {@code loan_closure} row, the edge
 * {@code ACTIVE -> CLOSED (REPAID)} (conditional, and held by the edge function beside the row), its history row and
 * {@code lending.LoanClosed} - so after the commit all six balances are zero and nothing is owed either way. A wallet that
 * cannot be credited now (none, or not {@code ACTIVE}) leaves the loan {@code ACTIVE} with its credit balance, nothing
 * written; the next servicing step closes it once it can (no principal is outstanding, so nothing accrues meanwhile).
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
     * Closes {@code loan} if it is settled - on the caller's unit of work, under the caller's L3.
     *
     * @param today the business date of the database clock in the pinned zone, read after L3
     * @param repayment the repayment whose transaction this is, if one
     * @param actor the closing act's actor - asked for only when the loan closes (the servicing step's is the scope's)
     * @return the closure, or empty when the loan is not settled (or its refund cannot be credited now)
     */
    public Optional<LoanClosure> closeIfSettled(Connection unitOfWork, Loan loan, LoanAgreement agreement, LocalDate today,
            Optional<UUID> repayment, Supplier<Actor> actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (loan.kind() != LoanKind.INSTALMENT || loan.status() != LoanStatus.ACTIVE) {
            return Optional.empty();
        }
        LoanReceivables receivables = new LoanReceivables(billings, ledgerAccounts, balances);
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        Accounts accounts = receivables.accounts(unitOfWork, loan.id(), loan.currency());
        if (!receivables.everyReceivableZero(unitOfWork, accounts, zero)
                || !accruedThroughYesterday(unitOfWork, loan, accounts, today, zero)) {
            return Optional.empty();
        }
        Money refund = receivables.creditHeld(unitOfWork, accounts, zero);
        Optional<JournalEntryId> refundEntry = Optional.empty();
        if (refund.isPositive()) {
            // (L7) The wallet's ledger row, after the loan: the refund is posted only to an account that accepts it now.
            Optional<LedgerAccountId> wallet = wallets.existingWallet(unitOfWork, loan.party(), loan.currency());
            if (wallet.isEmpty()) {
                return Optional.empty();
            }
            Optional<LedgerAccount> locked = ledgerAccounts.lockForUpdate(unitOfWork, wallet.get());
            if (locked.isEmpty() || locked.get().status() != LedgerAccountStatus.ACTIVE) {
                return Optional.empty();
            }
            refundEntry = Optional.of(refund(unitOfWork, loan, refund, accounts.creditBalance(), wallet.get(), today));
        }
        LoanClosure closure = new LoanClosure(ids.next(), loan.id(), ClosureReason.REPAID, refund, refundEntry, repayment);
        if (!loans.close(unitOfWork, closure, correlation)) {
            throw new IllegalStateException("the locked ACTIVE loan did not close under its own row lock");
        }
        loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), loan.id(), Optional.of(LoanStatus.ACTIVE),
                LoanStatus.CLOSED, ClosureReason.REPAID.name(), actor.get(), correlation));
        publish(unitOfWork, loan, closure, correlation);
        return Optional.of(closure);
    }

    /**
     * Whether every elapsed date of the loan - through yesterday in the pinned zone - is accrued, or ended with no
     * principal outstanding: the base {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} over the journal lines dated on or
     * before each date after the latest accrual, read in ONE statement (the accrual step's own rule, ADR-0094).
     */
    private boolean accruedThroughYesterday(
            Connection unitOfWork, Loan loan, Accounts accounts, LocalDate today, Money zero) {
        Optional<LocalDate> start = servicing.accrualStartOf(unitOfWork, loan.id());
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
