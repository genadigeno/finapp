package com.finapp.bnpl;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Early payoff - the quote and its execution, T-pay, both products (`P12-TSK-022`; {@code PHASE_12_PLAN.md} sections 5.4,
 * 7.1 T-pay, 7.2, 7.4 scenario 11, 12.11 F6; A21 - any time, no fee (L8); lending's {@code Payoffs}, {@code P11-TSK-026};
 * {@code INV-LND-08} by analogy, {@code INV-LND-02}, {@code INV-IDEM-01}, {@code INV-LIFE-04}, {@code INV-BNPL-01},
 * {@code INV-BNPL-10}). The customer learns, and pays, exactly what ends the obligation on a given day - never a wrong
 * payoff, whatever races it.
 *
 * <h2>The quote (keyed, under B4)</h2>
 *
 * <p>(B4) the obligation owner-scoped; a quote already born under the key converges; an {@code ACTIVE} obligation; the
 * database clock after the lock; {@code G} today up to the pinned payoff horizon. Pay-in-N <strong>caught up</strong> -
 * every elapsed date of the accrual span with principal outstanding accrued ({@link BnplAccruals#caughtUp}), else
 * {@link BehindAccrual}, nothing written: the door runs T-acc in its own transaction and comes back, so the quote never
 * holds the currency's income row. The ledger then holds everything earned through yesterday, and the projection covers
 * exactly the dates {@code [today, G - 1]} of the span ({@link AccrualEngineV1#project} on the current base {@code P + PD},
 * each period resetting at its start, chained onto the latest accrual - what T-acc will post for them if nothing moves).
 * The figure: {@code P + PD + IA + ID + FD - CB + projected}, every balance folded from journal lines under B4 -
 * {@code BNPL_FEES_DUE} counted generically, whatever assessed it - born with its inputs ({@code bnpl_payoff_quote}).
 * Pay-in-4's is its unpaid instalments (billed unpaid and unbilled principal) plus fees due less the credit balance. A
 * quote moves no money and binds nothing.
 *
 * <h2>The execution - T-pay (keyed, {@code MULTI_FACTOR}), one transaction, in this order</h2>
 *
 * <ol>
 *   <li><strong>(B4)</strong> the obligation owner-scoped - the serialisation point it shares with every repayment,
 *       collection, servicing step, refund (after its B5) and, later, condition step on the obligation;
 *   <li>a payoff already executed converges: the same quote answers its execution (ten executions, one closure), another
 *       quote finds the obligation closed;
 *   <li>the quote, read plainly (immutable; the execution's uniques beneath B4 arbitrate - no row lock on it); an
 *       {@code ACTIVE} obligation; the database clock - <strong>today must be {@code G}</strong>, and the agreement version
 *       and engines the quote's, else {@link PayoffQuoteStale}; pay-in-N caught up, else {@link BehindAccrual};
 *   <li>the floor read under B4 <strong>before anything is written</strong> and the payoff's value date
 *       {@code V = max(today, floor)}; the {@code bnpl_payoff_execution} row - first, because it alone admits the early
 *       billing, the {@code PAYOFF} repayment and the {@code PAID_OFF_EARLY} closure, and a deferred check holds it to the
 *       obligation closed at commit;
 *   <li><strong>(L8)</strong> the obligation's own projection rows; every unbilled instalment billed
 *       ({@link BnplServicing#billForPayoff}: one already due as the servicing step bills it, one not yet due early, its
 *       entry valued {@code V}), each followed by the credit-balance application at its birth; then the held credit applied
 *       over everything already due (the catch-up application, the Phase 11 gate's A4-M1) - so the wallet is debited only
 *       the net; every application valued {@code max(today, floor)}, which is {@code V} (RG-M1);
 *   <li><strong>the recomputation</strong>: the receivables less the credit balance folded again from journal lines
 *       under B4 - different from the quote's amount, {@link PayoffQuoteStale}, and every write above rolls back with the
 *       claim (a refund, a repayment, a collection or a fee that committed first moved it - scenario 11). Never adjusted,
 *       never paid approximately;
 *   <li><strong>(P)</strong> the customer's existing wallet {@code FOR UPDATE} - after the step's own postings, as the
 *       collection takes it - {@code ACTIVE}, its available balance at least the amount ({@code INV-BAL-04}); only when
 *       money moves either way; else {@link BnplRepayments.InsufficientFunds}, nothing posted;
 *   <li>one repayment of kind {@code PAYOFF} (keyed by the quote) of everything now receivable, allocated by the pinned
 *       engine over the derived due state to every component (F3) through {@link BnplRepayments}' one posting half
 *       ((L8), the rows, {@code bnpl.InstalmentPaid}), valued {@code V}; none when the credit balance already paid
 *       everything;
 *   <li>the closure through the one {@link BnplClosures}: every receivable zero, every instalment billed, every elapsed
 *       date accrued - the credit balance refunded under {@code bnpl.closure-refund:<obligation>}, {@code CLOSED
 *       (PAID_OFF_EARLY)}, {@code bnpl.BnplAgreementClosed}.
 * </ol>
 *
 * <p><strong>No rebate arithmetic</strong>: interest is earned daily, so paying early simply stops it - the "early
 * settlement rebate" is zero by construction. <strong>No fee</strong> (L8). <strong>No standing check</strong>, <strong>no
 * L0</strong> (exposure only falls), <strong>no L6</strong> (repaid principal restores headroom at commit,
 * {@code INV-BNPL-10}), <strong>no B5</strong> (a refund waiting on B4 behind the payoff finds the obligation closed and pays
 * the customer only, OQ-15), <strong>no audit record</strong> (the customer's own act is its rows and entries).
 * <strong>Any condition</strong>: delinquent, defaulted or disputed, the obligation is paid off. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplPayoffs {

    /** The longest key a requester may quote or execute under: the door's own bound, which {@code bnpl V014} repeats. */
    public static final int MAX_REQUEST_KEY_LENGTH = IdempotencyKey.MAX_LENGTH;

    @NonNull private final BnplRepaymentStore owned;
    @NonNull private final BnplPayoffStore store;
    @NonNull private final BnplServicingStore servicingStore;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplAccrualStore accruals;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final BnplServicing servicing;
    @NonNull private final BnplRepayments repayments;
    @NonNull private final BnplClosures closures;
    @NonNull private final IdGenerator ids;

    /** What the customer asks of a quote: their key, and the good-through date (today when absent). */
    public record QuoteRequest(String requestKey, Optional<LocalDate> goodThrough) {

        public QuoteRequest {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(goodThrough, "goodThrough must not be null");
            requireKey(requestKey);
        }

        /** Never the key ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "QuoteRequest[redacted]";
        }
    }

    /** The quote born - or, {@code replayed}, the one a key retried after its claim lapsed converged on. */
    public record Quoted(BnplPayoffQuote quote, boolean replayed) {

        public Quoted {
            Objects.requireNonNull(quote, "quote must not be null");
        }
    }

    /**
     * The payoff executed - or, {@code replayed}, the execution this one converged on: the execution, its quote, the
     * payoff's one repayment (none when the credit balance paid everything) and the closure.
     */
    public record Executed(BnplPayoffExecution execution, BnplPayoffQuote quote,
            Optional<BnplPurchaseStore.Repayment> repayment, BnplClosure closure, boolean replayed) {

        public Executed {
            Objects.requireNonNull(execution, "execution must not be null");
            Objects.requireNonNull(quote, "quote must not be null");
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(closure, "closure must not be null");
        }
    }

    // ------------------------------------------------------------------ the quote

    /**
     * Quotes the payoff of {@code party}'s agreement {@code agreement}, on the caller's unit of work.
     *
     * @throws BnplRepayments.AgreementNotFound the party has no agreement with this id (another party's included)
     * @throws QuoteKeyReused a quote under this key exists for another good-through date
     * @throws BnplRepayments.ObligationClosed the obligation is {@code CLOSED}
     * @throws GoodThroughInvalid the good-through date is before today or past the pinned horizon
     * @throws BehindAccrual pay-in-N's record is not caught up - nothing written; T-acc first, in its own transaction
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public Quoted quote(Connection unitOfWork, BnplAgreementId agreement, UUID party, QuoteRequest request,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");

        // (B4) The obligation, owner-scoped: another party's is never locked, and is the same answer as an absent one.
        BnplServicingStore.Locked locked = owned.lockOwnedBy(unitOfWork, agreement, party)
                .orElseThrow(BnplRepayments.AgreementNotFound::new);
        BnplObligation obligation = locked.obligation();
        Optional<BnplPayoffQuote> born = store.findQuoteByKey(unitOfWork, obligation.id(), request.requestKey());
        if (born.isPresent()) {
            if (request.goodThrough().isPresent() && !request.goodThrough().get().equals(born.get().goodThrough())) {
                throw new QuoteKeyReused();
            }
            return new Quoted(born.get(), true);
        }
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            throw new BnplRepayments.ObligationClosed(obligation.product());
        }
        // The database clock, read after the lock, in the pinned zone; G from it.
        Instant now = servicingStore.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        BnplTerms pinned = pinned(unitOfWork, locked);
        LocalDate goodThrough = request.goodThrough().orElse(today);
        if (goodThrough.isBefore(today) || goodThrough.isAfter(today.plusDays(pinned.payoffHorizonDays()))) {
            throw new GoodThroughInvalid(pinned.payoffHorizonDays());
        }
        // Pay-in-N caught up: the ledger holds everything earned through yesterday - else T-acc first, never here.
        if (!BnplAccruals.caughtUp(unitOfWork, servicingStore, purchases, book, locked, today)) {
            throw new BehindAccrual(obligation.id(), obligation.product());
        }

        // Every balance from journal lines under B4; the rows refused unless they explain the due accounts.
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        receivables.dueState(unitOfWork, obligation.id(), accounts);
        Money principal = book.balanceOf(unitOfWork, accounts.principal());
        Money principalDue = book.balanceOf(unitOfWork, accounts.principalDue());
        Money interestAccrued = book.balanceOf(unitOfWork, accounts.interestAccrued());
        Money interestDue = book.balanceOf(unitOfWork, accounts.interestDue());
        Money feesDue = book.balanceOf(unitOfWork, accounts.feesDue());
        Money credit = book.balanceOf(unitOfWork, accounts.creditBalance());
        Money base = principal.plus(principalDue);
        Projection projection = project(unitOfWork, locked, pinned, today, goodThrough, base);
        Money amount = BnplPayoffQuote.parts(principal, principalDue, interestAccrued, interestDue, feesDue, credit,
                projection.interest());

        BnplPayoffQuote unstamped = new BnplPayoffQuote(ids.next(), obligation.id(), locked.agreement(),
                locked.agreementVersion(), request.requestKey(), today, goodThrough, principal, principalDue,
                interestAccrued, interestDue, feesDue, credit, base, projection.days(), projection.interest(), amount,
                locked.scheduleEngine(), locked.accrualEngine(), locked.allocationEngine(), now, correlation);
        Instant createdAt = store.insertQuote(unitOfWork, unstamped);
        return new Quoted(stamped(unstamped, createdAt), false);
    }

    /** The interest projected and the dates it covers. */
    record Projection(Money interest, int days) {}

    /**
     * What {@link AccrualEngineV1#project} posts for {@code [today, G - 1]} of the accrual span on {@code base}, chained onto
     * the obligation's latest accrual - nothing for pay-in-4, nothing when {@code G} is today, none at or after the final due
     * date (T-acc accrues none there), none on no principal outstanding (T-acc writes no row for such a date).
     */
    private Projection project(Connection unitOfWork, BnplServicingStore.Locked locked, BnplTerms pinned, LocalDate today,
            LocalDate goodThrough, Money base) {
        Money zero = base.minus(base);
        if (locked.accrualEngine().isEmpty() || !goodThrough.isAfter(today) || !base.isPositive()) {
            return new Projection(zero, 0);
        }
        BnplPurchaseStore.StoredSchedule schedule = servicingStore.latestSchedule(unitOfWork, locked.obligation().id())
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        if (schedule.instalments().isEmpty()) {
            return new Projection(zero, 0);
        }
        List<AccrualPeriod> periods = BnplAccruals.periodsOf(schedule);
        LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
        LocalDate through = goodThrough.minusDays(1).isBefore(spanEnd) ? goodThrough.minusDays(1) : spanEnd.minusDays(1);
        if (through.isBefore(today)) {
            return new Projection(zero, 0);
        }
        // The predecessor as T-acc takes it: the latest accrual, when its period is one of the latest schedule's.
        Optional<InterestAccrual> last = accruals.latest(unitOfWork, locked.obligation().id())
                .map(BnplInterestAccrual::accrual).filter(accrual -> periods.contains(accrual.period()));
        Money interest = AccrualEngineV1.project(BnplAccruals.accrualTerms(pinned, locked), periods, last, today, through,
                base).interest();
        return new Projection(Money.ofPersisted(interest.minorUnits(), base.currency(), base.scale()),
                Math.toIntExact(ChronoUnit.DAYS.between(today, through) + 1));
    }

    // ------------------------------------------------------------------ T-pay

    /**
     * Executes {@code party}'s quote {@code quoteId} on their agreement {@code agreement}, on the caller's unit of work.
     *
     * @param requestKey the executing requester's key
     * @throws BnplRepayments.AgreementNotFound the party has no agreement with this id (another party's included)
     * @throws QuoteNotFound the obligation has no such quote
     * @throws BnplRepayments.ObligationClosed the obligation is closed - by another payoff included
     * @throws PayoffQuoteStale today is not the quote's good-through date, the agreement moved, or the recomputed amount
     *     differs from the quote's - nothing posted
     * @throws BehindAccrual pay-in-N's record is not caught up - nothing written; T-acc first, in its own transaction
     * @throws BnplRepayments.InsufficientFunds the wallet cannot fund the amount, or cannot be posted to
     * @throws AllocationAnomaly the obligation's rows do not explain its due accounts
     */
    public Executed execute(Connection unitOfWork, BnplAgreementId agreement, UUID party, String requestKey, UUID quoteId,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        requireKey(requestKey);

        // (B4) The obligation, owner-scoped - every act on it serialises here (scenario 11).
        BnplServicingStore.Locked locked = owned.lockOwnedBy(unitOfWork, agreement, party)
                .orElseThrow(BnplRepayments.AgreementNotFound::new);
        BnplObligation obligation = locked.obligation();
        // Paid off already: the same quote converges on its execution; any other finds the obligation closed.
        Optional<BnplPayoffExecution> executed = store.findExecution(unitOfWork, obligation.id());
        if (executed.isPresent()) {
            if (!executed.get().quote().equals(quoteId)) {
                throw new BnplRepayments.ObligationClosed(obligation.product());
            }
            return replayed(unitOfWork, obligation.id(), executed.get());
        }
        // The quote - immutable, read plainly under B4 (the execution's uniques beneath B4 arbitrate).
        BnplPayoffQuote quote = store.findQuote(unitOfWork, obligation.id(), quoteId).orElseThrow(QuoteNotFound::new);
        if (obligation.status() != BnplObligationStatus.ACTIVE) {
            throw new BnplRepayments.ObligationClosed(obligation.product());
        }
        Instant now = servicingStore.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        if (!today.equals(quote.goodThrough())) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.NOT_ITS_DATE, obligation.product());
        }
        if (locked.agreementVersion() != quote.agreementVersion()
                || !locked.scheduleEngine().equals(quote.scheduleEngine())
                || !locked.accrualEngine().equals(quote.accrualEngine())
                || !locked.allocationEngine().equals(quote.allocationEngine())) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.AMOUNT_CHANGED, obligation.product());
        }
        if (!BnplAccruals.caughtUp(unitOfWork, servicingStore, purchases, book, locked, today)) {
            throw new BehindAccrual(obligation.id(), obligation.product());
        }
        BnplTerms pinned = pinned(unitOfWork, locked);
        if (!pinned.allocationEngine().name().equals(locked.allocationEngine())) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        AllocationRules rules = new AllocationRules(pinned.allocationEngine(), pinned.allocationSequence(),
                pinned.allocationOrder(), pinned.overpaymentTreatment());

        // The floor under B4, before anything is written: every fact of the payoff is dated max(today, floor) (RG-M1).
        LocalDate floor = servicingStore.principalValueFloor(unitOfWork, obligation.id());
        LocalDate valueDate = floor.isAfter(today) ? floor : today;
        // The execution first: it alone admits the early billing, the PAYOFF repayment and the closure here.
        BnplPayoffExecution unstamped = new BnplPayoffExecution(ids.next(), quoteId, obligation.id(), requestKey, today,
                valueDate, now, correlation);
        Instant executedAt = store.insertExecution(unitOfWork, unstamped);
        BnplPayoffExecution execution = new BnplPayoffExecution(unstamped.id(), quoteId, obligation.id(), requestKey,
                today, valueDate, executedAt, correlation);

        // (L8) The obligation's own projection rows; everything unbilled billed, each with its application; then what is
        // held over what was already due (A4-M1) - the wallet is debited only the net.
        BnplReceivables receivables = new BnplReceivables(purchases, book);
        BnplLedgerAccounts accounts = receivables.accounts(unitOfWork, obligation.id());
        book.lockBalancesInOrder(unitOfWork, accounts.all().stream().map(LedgerAccount::id).toList());
        BnplCreditBalanceApplications applications =
                new BnplCreditBalanceApplications(servicingStore, receivables, book, ids);
        BnplCreditBalanceApplications.Context context =
                new BnplCreditBalanceApplications.Context(locked, rules, accounts, today);
        servicing.billForPayoff(unitOfWork, locked, accounts, applications, context, today, valueDate);
        applications.catchUp(unitOfWork, context);

        // The recomputation under B4: anything that moved since the quote makes it stale - never adjusted.
        Money receivable = receivable(unitOfWork, accounts);
        Money credit = receivables.creditHeld(unitOfWork, accounts);
        if (!receivable.minus(credit).equals(quote.amount())) {
            throw new PayoffQuoteStale(PayoffQuoteStale.Reason.AMOUNT_CHANGED, obligation.product());
        }
        if (!book.balanceOf(unitOfWork, accounts.principal()).isZero()
                || !book.balanceOf(unitOfWork, accounts.interestAccrued()).isZero()) {
            throw new IllegalStateException("a payoff bills every instalment: nothing is left not yet due");
        }
        if (receivable.isPositive() && credit.isPositive()) {
            throw new AllocationAnomaly("held credit is applied over everything due before the wallet is debited"
                    + " (A4-M1, INV-BNPL-01)");
        }

        // (P) The wallet, after the step's own postings - only when money moves either way.
        Optional<LedgerAccountId> wallet = Optional.empty();
        if (receivable.isPositive() || credit.isPositive()) {
            LedgerAccountId resolved = wallets.existingWallet(unitOfWork, party, obligation.currency())
                    .orElseThrow(() -> new BnplRepayments.InsufficientFunds(obligation.product()));
            Optional<LedgerAccountStatus> status = book.lockForUpdate(unitOfWork, resolved);
            if (status.filter(LedgerAccountStatus.ACTIVE::equals).isEmpty()
                    || (receivable.isPositive() && book.availableUnderLock(unitOfWork, resolved).compareTo(receivable) < 0)) {
                throw new BnplRepayments.InsufficientFunds(obligation.product());
            }
            wallet = Optional.of(resolved);
        }

        // One repayment of everything now receivable, allocated to every component by the pinned engine (F3).
        Optional<BnplPurchaseStore.Repayment> repayment = Optional.empty();
        if (receivable.isPositive()) {
            BnplReceivables.Derived derived = receivables.dueState(unitOfWork, obligation.id(), accounts);
            List<AllocationLine> lines = BnplRepayments.allocate(receivable, derived.dueState(), rules);
            BnplPurchaseStore.Repayment received = repayments.receive(unitOfWork, locked,
                    BnplPurchaseStore.Repayment.PAYOFF, quoteId.toString(), receivable, lines, derived.dueState(),
                    accounts, wallet.orElseThrow(), today, correlation);
            if (!received.valueDate().equals(valueDate)) {
                throw new IllegalStateException("every fact of a payoff is dated the payoff's value date (RG-M1)");
            }
            repayment = Optional.of(received);
        }
        // The closure: every receivable zero, every instalment billed, every elapsed date accrued - PAID_OFF_EARLY.
        BnplClosure closure = closures.closeIfSettled(unitOfWork, BnplClosures.Closing.payoff(locked, accounts, today,
                repayment.map(BnplPurchaseStore.Repayment::id), wallet), () -> actor, correlation)
                .orElseThrow(() -> new IllegalStateException("a payoff settles every receivable and closes (INV-LND-08)"));
        return new Executed(execution, quote, repayment, closure, false);
    }

    /** Σ of the five receivables, each a ledger balance under B4. */
    private Money receivable(Connection unitOfWork, BnplLedgerAccounts accounts) {
        Money sum = book.balanceOf(unitOfWork, accounts.principal());
        for (LedgerAccount account : List.of(accounts.principalDue(), accounts.interestAccrued(), accounts.interestDue(),
                accounts.feesDue())) {
            sum = sum.plus(book.balanceOf(unitOfWork, account));
        }
        return sum;
    }

    /** The execution a converging request answers: its quote, its repayment if any, its closure. */
    private Executed replayed(Connection unitOfWork, BnplObligationId obligation, BnplPayoffExecution execution) {
        BnplPayoffQuote quote = store.findQuote(unitOfWork, obligation, execution.quote())
                .orElseThrow(() -> new IllegalStateException("an execution names its obligation's quote"));
        Optional<BnplPurchaseStore.Repayment> repayment = owned.findByKey(unitOfWork, obligation,
                BnplPurchaseStore.Repayment.PAYOFF, execution.quote().toString());
        BnplClosure closure = owned.closureOf(unitOfWork, obligation)
                .orElseThrow(() -> new IllegalStateException("a payoff execution commits only beside its closure"));
        return new Executed(execution, quote, repayment, closure, true);
    }

    private BnplTerms pinned(Connection unitOfWork, BnplServicingStore.Locked locked) {
        return terms.version(unitOfWork, locked.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
    }

    private static BnplPayoffQuote stamped(BnplPayoffQuote quote, Instant createdAt) {
        return new BnplPayoffQuote(quote.id(), quote.obligation(), quote.agreement(), quote.agreementVersion(),
                quote.requestKey(), quote.businessDate(), quote.goodThrough(), quote.principal(), quote.principalDue(),
                quote.interestAccrued(), quote.interestDue(), quote.feesDue(), quote.creditBalance(),
                quote.projectionBase(), quote.projectedDays(), quote.projectedInterest(), quote.amount(),
                quote.scheduleEngine(), quote.accrualEngine(), quote.allocationEngine(), createdAt, quote.correlation());
    }

    private static void requireKey(String requestKey) {
        if (requestKey.isEmpty() || requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("a payoff key is 1 to " + MAX_REQUEST_KEY_LENGTH + " characters");
        }
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

    /** The obligation has no such quote. */
    public static final class QuoteNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        QuoteNotFound() {
            super("no such payoff quote");
        }
    }

    /**
     * Pay-in-N's record is not caught up - every elapsed date of the accrual span with principal outstanding is not yet
     * accrued; nothing was written. The caller runs T-acc in its own transaction ({@link BnplAccruals#accrue}) and asks
     * again - never inside the payoff (the A2-F1 rule).
     */
    public static final class BehindAccrual extends BnplRepayments.Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient BnplObligationId obligation;

        BehindAccrual(BnplObligationId obligation, BnplProduct product) {
            super(product, "the BNPL agreement's interest is not yet accrued to date; nothing was written");
            this.obligation = Objects.requireNonNull(obligation, "obligation");
        }

        /** The obligation T-acc must bring up to date. */
        public BnplObligationId obligation() {
            return obligation;
        }
    }

    /**
     * The quote no longer ends the obligation: today is not its good-through date, or what the obligation owes moved since
     * it was made - nothing was posted; a new quote answers the new figure.
     */
    public static final class PayoffQuoteStale extends BnplRepayments.Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /** Why the quote is stale. */
        public enum Reason {
            /** Executed on a date other than its good-through date. */
            NOT_ITS_DATE,
            /** The recomputed amount differs from the quote's (or the agreement moved). */
            AMOUNT_CHANGED
        }

        private final Reason reason;

        PayoffQuoteStale(Reason reason, BnplProduct product) {
            super(product, "the payoff quote is stale; nothing was posted");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }
}
