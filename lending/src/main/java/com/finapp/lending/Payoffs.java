package com.finapp.lending;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanReceivables.Accounts;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The payoff - its quote and its execution, T-pay, both products (`P11-TSK-026`; {@code PHASE_11_PLAN.md} sections 5.3,
 * 7.1, 7.2, 7.4 scenarios 7 and 10, 12.3, 12.5 and 12.8 rule 7; ADR-0094 point 10, ADR-0095 point 8, ADR-0100 point 8;
 * {@code INV-LND-08}, {@code INV-LND-02}, {@code INV-LND-04}, {@code INV-IDEM-01}, {@code INV-LIFE-04}). The borrower
 * learns, and pays, exactly what ends the loan or line on a given day - never a wrong payoff, whatever races it.
 *
 * <h2>The quote (keyed, under L3)</h2>
 *
 * <p>(L3) the account owner-scoped; a quote already born under the key converges; an {@code ACTIVE} instalment loan in
 * servicing or a line {@code ACTIVE}/{@code CLOSING}; the database clock after the lock; {@code G} today up to the pinned
 * payoff horizon. Then <strong>every elapsed date accrued</strong> - the servicing step's own accrual, rows, uniques and
 * keys ({@link LoanServicing#catchUp}) - so the ledger holds everything owed through yesterday and the projection covers
 * exactly the dates {@code [today, G - 1]}: {@link AccrualEngineV1#project} on the current base (principal outstanding),
 * each period resetting at its start, chained onto the latest accrual. The figure: {@code P + PD + IA + ID + FD - CB +
 * projected}, every balance folded from journal lines under L3, born with its inputs ({@code payoff_quote}). A quote moves
 * no money and binds nothing: it is a promise that, on {@code G}, if nothing moved, this figure ends the account.
 *
 * <h2>The execution - T-pay (keyed, {@code MULTI_FACTOR}), in this order, one transaction</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the account owner-scoped - the serialisation point it shares with every repayment, collection,
 *       servicing step (and its condition step), draw, reversal and closure request on the account;
 *   <li>a payoff already executed converges: the same quote answers its execution (ten executions, one closure), another
 *       quote finds the account closed ({@code LoanNotRepayable});
 *   <li>the quote, read plainly - immutable, so no L5 lock is needed: {@code payoff_execution UNIQUE (quote_id)} and
 *       {@code UNIQUE (loan_id)} beneath L3 are the arbiters; the account still payable; the database clock after the lock -
 *       <strong>today must be {@code G}</strong> and the agreement version the quote's, else {@code PayoffQuoteStale};
 *   <li>the {@code payoff_execution} row - first, because it alone admits what follows in this transaction (the early
 *       billing, the payoff's repayment, the {@code PAID_OFF_EARLY} closure), and a deferred check holds it to the account
 *       {@code CLOSED} at commit;
 *   <li><strong>rule 2</strong>: every elapsed date through {@code G - 1} accrued ({@link LoanServicing#catchUp} - the
 *       sweep's uniques, so the sweep and the payoff accrue a date once); <strong>rule 3</strong> (and 6): everything unbilled
 *       billed ({@link InstalmentBillings#billForPayoff}; a line's current cycle stated, {@link
 *       CreditLineStatements#stateForPayoff}), each followed by the credit-balance application - and then rule 6 over what
 *       was already due ({@link InstalmentBillings#applyHeldCredit}, the Phase 11 gate's A4-M1), so a credit balance held
 *       beside a due item none of today's billings met is applied before the wallet is debited. Every one of those
 *       applications is valued as every principal-moving fact is, {@code max(today, floor)} - never a date the record has
 *       closed (the Phase 11 gate's R1; its residual RG-M1, the billings' and statements' own applications dated by the
 *       clock, closed by the Phase 12 transition gate's A7-4);
 *   <li><strong>the recomputation</strong>: the outstanding folded again from journal lines under L3 - different from the
 *       quote's, {@code PayoffQuoteStale}, and every write above rolls back with the claim (a repayment, a collection, a
 *       draw, a reversal or a fee that committed first moved it - scenario 7). Never adjusted, never paid approximately;
 *   <li><strong>(L7)</strong> the wallet's ledger row - after the step's own postings, as T-svc takes it (no writer holding
 *       a wallet row ever waits on the interest income row those postings lock) - {@code ACTIVE}, and its available balance
 *       at least the net amount ({@code INV-BAL-04}); a wallet absent or not {@code ACTIVE} is {@code InsufficientFunds};
 *   <li><strong>rule 5</strong>: one repayment of kind {@code PAYOFF} (keyed by the quote) of everything now receivable,
 *       allocated by the pinned engine over the derived due state - every billed component, then a line's principal not yet
 *       due - through {@link Repayments}' one posting half ((L8), the rows, {@code lending.RepaymentReceived}); none when the
 *       credit balance already paid everything;
 *   <li>the closure through the one {@link LoanClosures}: every receivable zero and every elapsed date accrued, the credit
 *       balance refunded under {@code lending.closure-refund:<loan>}, {@code CLOSED (PAID_OFF_EARLY)} - a line {@code CLOSED
 *       (CLOSED_BY_CUSTOMER)}, its closure request born by the payoff when it is {@code ACTIVE} - {@code lending.LoanClosed}.
 * </ol>
 *
 * <p><strong>No rebate arithmetic</strong>: interest is earned daily, so paying early simply stops it - the "early
 * settlement rebate" is zero by construction. <strong>No standing check</strong>, <strong>no L0</strong> (exposure only
 * falls), <strong>no L6</strong> (repaid principal restores headroom at commit), <strong>no audit record</strong> (the
 * customer's own act is its rows and entries). <strong>Any condition</strong>: delinquent or defaulted, the account is paid
 * off; the condition step serialises on L3 and never evaluates a closed account (scenario 10).
 */
@RequiredArgsConstructor
public final class Payoffs {

    /** The longest key a requester may quote or execute under: the door's own bound, which {@code lending V022} repeats. */
    public static final int MAX_REQUEST_KEY_LENGTH = IdempotencyKey.MAX_LENGTH;

    @NonNull private final LoanStore loans;
    @NonNull private final PayoffStore store;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanServicing servicing;
    @NonNull private final LoanServicingStore servicingStore;
    @NonNull private final InstalmentBillings billings;
    @NonNull private final CreditLineStatements statements;
    @NonNull private final InstalmentBillingStore billingStore;
    @NonNull private final RepaymentStore repaymentStore;
    @NonNull private final LoanWallets wallets;
    @NonNull private final AvailableBalance<Connection> available;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final Repayments repayments;
    @NonNull private final LoanClosures closures;
    @NonNull private final LineClosures lineClosures;
    @NonNull private final IdGenerator ids;

    /** What the customer asks of a quote: their key, and the good-through date (today when absent). */
    public record QuoteRequest(String requestKey, Optional<LocalDate> goodThrough) {

        public QuoteRequest {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(goodThrough, "goodThrough must not be null");
            if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
                throw new IllegalArgumentException("a payoff key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
            }
        }

        /** Never the key ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "QuoteRequest[redacted]";
        }
    }

    /** The quote born - or, {@code replayed}, the one a key retried after its claim lapsed converged on. */
    public record Quoted(PayoffQuote quote, boolean replayed) {

        public Quoted {
            Objects.requireNonNull(quote, "quote must not be null");
        }
    }

    /**
     * The payoff executed - or, {@code replayed}, the execution this one converged on: the execution, the payoff's one
     * repayment (none when the credit balance paid everything) and the closure.
     */
    public record Executed(PayoffExecution execution, PayoffQuote quote, Optional<Repayment> repayment,
            LoanClosure closure, boolean replayed) {

        public Executed {
            Objects.requireNonNull(execution, "execution must not be null");
            Objects.requireNonNull(quote, "quote must not be null");
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ the quote

    /**
     * Quotes the payoff of {@code party}'s loan {@code id}, on the caller's unit of work, inside the caller's correlation
     * and actor scopes (the catch-up accrual posts).
     *
     * @throws Repayments.LoanNotFound the party has no loan with this id (another party's included)
     * @throws QuoteKeyReused a quote under this key exists for another good-through date
     * @throws Repayments.LoanNotRepayable the account cannot be paid off - closed, cancelled, not yet disbursed, or awaiting
     *     its payout's outcome (no accrual start)
     * @throws GoodThroughInvalid the good-through date is before today or past the pinned horizon
     */
    public Quoted quote(Connection unitOfWork, LoanId id, UUID party, QuoteRequest request, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");

        // (L3) The account, owner-scoped: another party's is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(Repayments.LoanNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        Optional<PayoffQuote> born = store.findQuoteByKey(unitOfWork, id, request.requestKey());
        if (born.isPresent()) {
            if (request.goodThrough().isPresent() && !request.goodThrough().get().equals(born.get().goodThrough())) {
                throw new QuoteKeyReused();
            }
            return new Quoted(born.get(), true);
        }
        if (!Repayments.repayable(loan)) {
            throw new Repayments.LoanNotRepayable(loan.product());
        }
        Instant now = store.databaseNow(unitOfWork);
        LoanTerms pinned = pinned(unitOfWork, agreement);
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        Optional<Function<LocalDate, AccrualPeriod>> periods =
                servicing.periodsOf(unitOfWork, loan, agreement, pinned, zone);
        if (periods.isEmpty()) {
            // Path X awaiting its payout's outcome: nothing accrues, no schedule falls due - nothing a payoff can settle yet.
            throw new Repayments.LoanNotRepayable(loan.product());
        }
        LocalDate goodThrough = request.goodThrough().orElse(today);
        if (goodThrough.isBefore(today) || goodThrough.isAfter(today.plusDays(pinned.payoffHorizonDays()))) {
            throw new GoodThroughInvalid(pinned.payoffHorizonDays());
        }

        // Every elapsed date accrued - the sweep's own accrual, under the same uniques - so the projection is the future.
        servicing.catchUp(unitOfWork, loan, agreement, pinned, zone, today);
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        LoanReceivables receivables = new LoanReceivables(billingStore, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, id, loan.currency());
        Money principal = receivables.netDebit(unitOfWork, accounts.principal(), zero);
        Money principalDue = receivables.netDebit(unitOfWork, accounts.principalDue(), zero);
        Money interestAccrued = receivables.netDebit(unitOfWork, accounts.interestAccrued(), zero);
        Money interestDue = receivables.netDebit(unitOfWork, accounts.interestDue(), zero);
        Money feesDue = receivables.netDebit(unitOfWork, accounts.feesDue(), zero);
        Money credit = receivables.creditHeld(unitOfWork, accounts, zero);
        Money base = principal.plus(principalDue);
        Money projected = projection(unitOfWork, loan, agreement, pinned, periods.get(), today, goodThrough, base, zero);
        Money outstanding = principal.plus(principalDue).plus(interestAccrued).plus(interestDue).plus(feesDue)
                .minus(credit).plus(projected);

        PayoffQuote unstamped = new PayoffQuote(ids.next(), id, request.requestKey(), today, goodThrough, principal,
                principalDue, interestAccrued, interestDue, feesDue, credit, base,
                Math.toIntExact(ChronoUnit.DAYS.between(today, goodThrough)), projected, outstanding, agreement.id(),
                agreement.version(), agreement.accrualEngine(), agreement.allocationEngine(), now);
        Instant createdAt = store.insertQuote(unitOfWork, unstamped, correlation);
        return new Quoted(new PayoffQuote(unstamped.id(), id, unstamped.requestKey(), today, goodThrough, principal,
                principalDue, interestAccrued, interestDue, feesDue, credit, base, unstamped.projectedDays(), projected,
                outstanding, agreement.id(), agreement.version(), agreement.accrualEngine(), agreement.allocationEngine(),
                createdAt), false);
    }

    /**
     * The interest {@link AccrualEngineV1#project} posts for {@code [today, goodThrough - 1]} on {@code base}, chained onto
     * the account's latest accrual - zero when {@code G} is today.
     */
    private Money projection(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned,
            Function<LocalDate, AccrualPeriod> periodOf, LocalDate today, LocalDate goodThrough, Money base, Money zero) {
        if (!goodThrough.isAfter(today)) {
            return zero;
        }
        LocalDate through = goodThrough.minusDays(1);
        List<AccrualPeriod> periods = new ArrayList<>();
        for (LocalDate date = today; !date.isAfter(through); date = date.plusDays(1)) {
            AccrualPeriod period = periodOf.apply(date);
            if (periods.isEmpty() || !periods.get(periods.size() - 1).equals(period)) {
                periods.add(period);
            }
        }
        AccrualTerms accrualTerms = new AccrualTerms(loan.currency(), agreement.nominalAnnualRate(), pinned.dayCount(),
                pinned.interestRounding(), agreement.accrualEngine());
        Optional<InterestAccrual> last = servicingStore.latestAccrual(unitOfWork, loan.id())
                .map(RecordedAccrual::accrual);
        return AccrualEngineV1.project(accrualTerms, periods, last, today, through, base).interest();
    }

    // ------------------------------------------------------------------ T-pay

    /**
     * Executes {@code party}'s quote {@code quoteId} on their loan {@code id}, on the caller's unit of work, inside the
     * caller's correlation and actor scopes.
     *
     * @param requestKey the executing requester's key
     * @throws Repayments.LoanNotFound the party has no loan with this id (another party's included)
     * @throws QuoteNotFound the loan has no such quote
     * @throws Repayments.LoanNotRepayable the account cannot be paid off - closed (by another payoff included), cancelled
     * @throws PayoffQuoteStale today is not the quote's good-through date, the agreement moved, or the recomputed
     *     outstanding differs from the quote's - nothing posted
     * @throws Repayments.InsufficientFunds the wallet cannot fund the net amount, or cannot be posted to
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Executed execute(Connection unitOfWork, LoanId id, UUID party, String requestKey, UUID quoteId, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("a payoff key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
        }

        // (L3) The account, owner-scoped - every act on it serialises here (scenarios 7 and 10).
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(Repayments.LoanNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // Paid off already: the same quote converges on its execution; any other finds the account closed.
        Optional<PayoffExecution> executed = store.findExecution(unitOfWork, id);
        if (executed.isPresent()) {
            if (!executed.get().quote().equals(quoteId)) {
                throw new Repayments.LoanNotRepayable(loan.product());
            }
            return replayed(unitOfWork, id, executed.get());
        }
        // The quote - immutable, read plainly under L3 (no L5 lock: the execution's uniques beneath L3 arbitrate).
        PayoffQuote quote = store.findQuote(unitOfWork, id, quoteId).orElseThrow(QuoteNotFound::new);
        if (!Repayments.repayable(loan)) {
            throw new Repayments.LoanNotRepayable(loan.product());
        }
        Instant now = store.databaseNow(unitOfWork);
        LoanTerms pinned = pinned(unitOfWork, agreement);
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!today.equals(quote.goodThrough())) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.NOT_ITS_DATE);
        }
        if (agreement.version() != quote.agreementVersion()) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.AMOUNT_CHANGED);
        }

        // The execution first: it admits the early billing, the payoff's repayment and its closure in this transaction.
        PayoffExecution unstamped = new PayoffExecution(ids.next(), quoteId, id, requestKey, today, now);
        Instant executedAt = store.insertExecution(unitOfWork, unstamped, correlation);
        PayoffExecution execution =
                new PayoffExecution(unstamped.id(), quoteId, id, requestKey, today, executedAt);

        // Rule 2: every elapsed date through G - 1, under the sweep's uniques. Rule 3 (and 6): everything unbilled.
        servicing.catchUp(unitOfWork, loan, agreement, pinned, zone, today);
        // Every application below is valued as every principal-moving fact is (the Phase 11 gate's R1): max(today, floor),
        // read under L3 after the catch-up - never a date the record has closed. On a clock stepped back behind an accrued
        // date, today's applications would pay principal into a day whose accrual already counted it - a day's interest
        // over-charged (RG-M1, closed by the Phase 12 transition gate's A7-4). The billings themselves move principal only
        // between not-due and due, so they stand on today.
        LocalDate open = loans.principalValueDate(unitOfWork, id, today);
        if (loan.kind() == LoanKind.INSTALMENT) {
            billings.billForPayoff(unitOfWork, loan, agreement, pinned, today, open);
        } else {
            statements.stateForPayoff(unitOfWork, loan, agreement, pinned, today, open);
        }
        // Rule 6 over what is already due (the Phase 11 gate's A4-M1): credit still held beside a due item - none billed
        // today to apply it at - settles it first, so the wallet is debited only what the credit does not cover, never the
        // gross receivable with the credit refunded beside it. The outstanding below is unchanged by it (DR credit / CR due).
        billings.applyHeldCredit(unitOfWork, loan, agreement, pinned, open);

        // The recomputation under L3: anything that moved since the quote makes it stale - never adjusted.
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        LoanReceivables receivables = new LoanReceivables(billingStore, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, id, loan.currency());
        Money receivable = zero;
        for (LedgerAccountId account : accounts.receivables()) {
            receivable = receivable.plus(receivables.netDebit(unitOfWork, account, zero));
        }
        Money credit = receivables.creditHeld(unitOfWork, accounts, zero);
        if (!receivable.minus(credit).equals(quote.outstanding())) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.AMOUNT_CHANGED);
        }

        // A line ends at its customer's request: an ACTIVE line's request is born by its payoff.
        lineClosures.requestForPayoff(unitOfWork, loan, quoteId.toString(), correlation);

        // (L7) The wallet, after the step's own postings - only when money moves either way.
        Optional<LedgerAccountId> wallet = Optional.empty();
        if (receivable.isPositive() || credit.isPositive()) {
            LedgerAccountId resolved = wallets.existingWallet(unitOfWork, party, loan.currency())
                    .orElseThrow(Repayments.InsufficientFunds::new);
            LedgerAccount walletRow = ledgerAccounts.lockForUpdate(unitOfWork, resolved)
                    .orElseThrow(() -> new IllegalStateException("a resolved wallet has its ledger account row"));
            if (walletRow.status() != LedgerAccountStatus.ACTIVE
                    || (receivable.compareTo(credit) > 0
                            && available.underLock(unitOfWork, resolved).compareTo(receivable.minus(credit)) < 0)) {
                throw new Repayments.InsufficientFunds();
            }
            wallet = Optional.of(resolved);
        }

        Supplier<Actor> closingActor = () -> actor;
        if (!receivable.isPositive()) {
            // The credit balance paid everything: no repayment, the excess refunded at the closure.
            LoanClosure closure = closures.closeOnPayoff(unitOfWork, loan, agreement, zone, today, Optional.empty(),
                    closingActor, correlation);
            return new Executed(execution, quote, Optional.empty(), closure, false);
        }
        // Rule 5: one repayment of everything now receivable, allocated to every component by the pinned engine.
        AllocationRules rules = LoanEngines.allocationRules(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        DueState dueState = receivables.dueState(unitOfWork, id, accounts, zero);
        List<AllocationLine> lines = Repayments.allocate(receivable, dueState, rules);
        Repayments.Repaid repaid = repayments.receive(unitOfWork, loan, agreement, RepaymentKind.PAYOFF,
                quoteId.toString(), receivable, lines, accounts, wallet.orElseThrow(), today, now, correlation,
                repaymentId -> Optional.of(closures.closeOnPayoff(unitOfWork, loan, agreement, zone, today,
                        Optional.of(repaymentId), closingActor, correlation)));
        return new Executed(execution, quote, Optional.of(repaid.repayment()), repaid.closure().orElseThrow(), false);
    }

    /** The execution a converging request answers: its quote, its repayment if any, its closure. */
    private Executed replayed(Connection unitOfWork, LoanId id, PayoffExecution execution) {
        PayoffQuote quote = store.findQuote(unitOfWork, id, execution.quote())
                .orElseThrow(() -> new IllegalStateException("an execution names its loan's quote"));
        Optional<Repayment> repayment =
                repaymentStore.findByKey(unitOfWork, id, RepaymentKind.PAYOFF, execution.quote().toString());
        LoanClosure closure = store.closureOf(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a payoff execution commits only beside its closure"));
        return new Executed(execution, quote, repayment, closure, true);
    }

    private LoanTerms pinned(Connection unitOfWork, LoanAgreement agreement) {
        return terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** A quote was born under this key for another good-through date - the key reused after its claim lapsed. */
    public static final class QuoteKeyReused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        QuoteKeyReused() {
            super("the key was already used for a different quote");
        }
    }

    /** The good-through date is before today or past the pinned payoff horizon. */
    public static final class GoodThroughInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final int horizonDays;

        GoodThroughInvalid(int horizonDays) {
            super("the good-through date is today up to the payoff horizon");
            this.horizonDays = horizonDays;
        }

        /** The pinned horizon, in days - the caller's to state. */
        public int horizonDays() {
            return horizonDays;
        }
    }

    /** The loan has no such quote. */
    public static final class QuoteNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        QuoteNotFound() {
            super("no such payoff quote");
        }
    }

    /**
     * The quote no longer ends the account: today is not its good-through date, or what the account owes moved since it
     * was made - nothing was posted; a new quote answers the new figure.
     */
    public static final class PayoffQuoteStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /** Why the quote is stale. */
        public enum Reason {
            /** Executed on a date other than its good-through date. */
            NOT_ITS_DATE,
            /** The recomputed outstanding differs from the quote's (or the agreement moved). */
            AMOUNT_CHANGED
        }

        private final Reason reason;

        PayoffQuoteStale(Reason reason) {
            super("the payoff quote is stale");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }
}
