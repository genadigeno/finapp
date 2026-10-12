package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The servicing step - T-svc - and its first step, daily interest accrual (`P11-TSK-016`; {@code PHASE_11_PLAN.md}
 * sections 7.1, 7.2, 7.5, 12.3 and 12.8 rule 2; ADR-0094; {@code INV-LND-02}, {@code INV-LND-09}, {@code INV-LND-10},
 * {@code INV-IDEM-02}, {@code INV-BAL-05}). One account per call, in <strong>one local transaction</strong> on the
 * caller's unit of work. A system act with no door: interest is earned because a day ended, never because someone asked.
 *
 * <h2>The skeleton, in its fixed order under L3</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE} - the serialisation point of every servicing act on one account;
 *       an account not {@code ACTIVE} or {@code CLOSING} is not serviced. No L0 (exposure does not move: interest is not
 *       principal, A3) and no L6 (capital does not move);
 *   <li>the database clock, read after the lock, and its business date in the agreement's pinned servicing zone - never
 *       an instance clock ({@code INV-LND-10});
 *   <li><strong>accrue</strong> (this task): every elapsed date not yet accrued, oldest first;
 *   <li><strong>bill</strong> (`P11-TSK-017`, {@link InstalmentBillings}): an instalment loan's instalments due by
 *       today whose periods the accrual has reached, oldest first - posting rule 3 - each followed by the credit-balance
 *       application, rule 6 (a line's statement is `-022`'s);
 *   <li>the steps later tasks add, in this order: auto-collect (`-019`), the condition (`-024`), the late fee (`-025`) -
 *       each after billing, each under the same lock;
 *   <li><strong>close</strong> (`P11-TSK-018`, {@link LoanClosures}): a caught-up instalment loan whose every receivable
 *       is zero closes {@code REPAID}, its credit balance refunded to the wallet - (L7) the wallet's row after L3 - and is
 *       not re-permitted;
 *   <li>the permit: the start of the next business date in the pinned zone - or now, when a long catch-up stopped at its
 *       bound and the rest is due at once.
 * </ol>
 *
 * <h2>The accrual (ADR-0094)</h2>
 *
 * <p>A date {@code d} is <em>elapsed</em> when the database clock, read under L3, is past its end in the pinned zone.
 * From the accrual start - an instalment loan's {@code accrual_start} (none: path X awaiting its payout, nothing
 * accrues), a line's opening business date - each elapsed date after the latest accrual is accrued oldest first while
 * principal outstanding at its end is positive: the base {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} over the journal
 * lines whose value date is on or before {@code d}, read in ONE statement and folded through {@link Money} - never
 * interest or fees (A4), never the projection ({@code INV-BAL-05}). The period is the instalment period (the schedule's,
 * continued monthly after the final due date - {@link InstalmentCalendar}) or the line's statement cycle
 * ({@link StatementEngineV1#cycle}); {@link AccrualEngineV1} rounds within it, its predecessor the period's previous
 * accrual. Each date writes its {@code interest_accrual} row and - when it posts something - posting rule 2,
 * {@code DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME}, under {@code lending.accrual:<loan>:<date>}, posting date
 * = value date = {@code d}; a zero day writes the row and no entry. The row and its entry commit together, with every
 * other date of the step, or not at all.
 *
 * <h2>Why once (scenario 4)</h2>
 *
 * <p>Three arbiters: L3 serialises every step on one account, each reading the latest accrual after the lock;
 * {@code interest_accrual UNIQUE (loan_id, accrual_date)} beneath it; and the ledger key, whose replay - an entry for a
 * date with no row - is a defect, never a second posting. Ten sweepers, a duplicate tick, a crash between dates or between
 * a row and its posting (one transaction), a restart: one row and at most one entry per account and date. A missed day -
 * an outage of any length - is caught up, never skipped.
 */
@RequiredArgsConstructor
public final class LoanServicing {

    /** The ledger key's prefix (rule 2): one entry per account and date, however often it is attempted. */
    public static final String POSTING_KEY_PREFIX = "lending.accrual:";

    /**
     * Reserved (ADR-0094 point 11): the key a correction run would post an engine version's delta under -
     * {@code lending.accrual-correction:<loan>:<date>:<engine>}. Not built in Phase 11 (nothing to correct); named so no
     * other key collides with it. Nothing posts under it.
     */
    public static final String CORRECTION_KEY_PREFIX = "lending.accrual-correction:";

    /**
     * The most dates one step accrues: after a long outage the step stops here, commits, and makes the account due again
     * at once - so no transaction grows with the outage, and every date is still caught up, oldest first.
     */
    public static final int MAX_ACCRUALS_PER_STEP = 92;

    @NonNull private final LoanStore loans;
    @NonNull private final LoanServicingStore store;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final IdGenerator ids;
    @NonNull private final InstalmentBillings billings;
    @NonNull private final LoanClosures closures;

    /**
     * What one servicing step did - for the meter; never a figure or a date: {@code posted} and {@code zero} accrued
     * dates, {@code billed} items made due and credit-balance applications {@code applied} (`P11-TSK-017`), and whether
     * the step {@code closed} the loan - its last receivable settled by an application (`P11-TSK-018`).
     */
    public record Outcome(Kind kind, int posted, int zero, boolean caughtUp, int billed, int applied, boolean closed) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            if (posted < 0 || zero < 0 || billed < 0 || applied < 0) {
                throw new IllegalArgumentException("counts are not negative");
            }
        }

        static Outcome notServiced() {
            return new Outcome(Kind.NOT_SERVICED, 0, 0, true, 0, 0, false);
        }

        /** The step's effect. */
        public enum Kind {
            /** The account was not in servicing - gone, or no longer {@code ACTIVE} or {@code CLOSING}. */
            NOT_SERVICED,
            /** Serviced: {@code posted} dates posted an entry and {@code zero} wrote their row alone. */
            SERVICED
        }
    }

    // ------------------------------------------------------------------ the claim

    /** The accounts due servicing - see {@link LoanServicingStore#claimDue}. */
    public List<LoanId> claim(Connection unitOfWork, int limit, Duration retryCadence) {
        return store.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(retryCadence, "retryCadence"));
    }

    // ------------------------------------------------------------------ T-svc

    /** One account's servicing step, on the caller's unit of work - a transaction of its own. */
    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    public Outcome service(Connection unitOfWork, LoanId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        // (L3) The account - every servicing act on it serialises here.
        Optional<LoanStore.Owned> locked = loans.lock(unitOfWork, id);
        if (locked.isEmpty() || !inServicing(locked.get().loan().status())) {
            return Outcome.notServiced();
        }
        Loan loan = locked.get().loan();
        LoanAgreement agreement = locked.get().agreement();
        // The database clock, read after the lock, in the agreement's pinned zone.
        Instant now = store.databaseNow(unitOfWork);
        LoanTerms pinned = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);

        Correlation flow = Correlation.startingWith(loan.correlation()).causing(CausationId.of(loan.correlation().value()));
        Accrued accrued;
        InstalmentBillings.Billed billed = InstalmentBillings.Billed.NOTHING;
        Optional<LoanClosure> closure = Optional.empty();
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow)) {
            accrued = accrue(unitOfWork, loan, agreement, pinned, zone, today);
            // Then billing (P11-TSK-017): an instalment loan's instalments due by today whose periods the accrual has
            // reached, oldest first, each followed by the credit-balance application - under the same lock. A line's
            // statement is P11-TSK-022's.
            if (accrued.accruedThrough().isPresent()) {
                billed = billings.bill(unitOfWork, loan, agreement, pinned, today, accrued.accruedThrough().get());
            }
            // The steps later tasks add run here, after billing and under the same lock: auto-collection (-019), the
            // condition (-024), the late fee (-025).
            // Last, the closure (P11-TSK-018): a loan the step's credit-balance application settled - or one settled
            // earlier whose refund could not be credited then - closes here, its credit balance refunded (L7 after L3).
            if (accrued.caughtUp()) {
                closure = closures.closeIfSettled(unitOfWork, loan, agreement, today, Optional.empty(),
                        com.finapp.platform.security.SecurityContext::require, loan.correlation());
            }
        }
        if (closure.isPresent()) {
            // A CLOSED account leaves servicing: no permit moves on a terminal account (the edge function's rule).
            return new Outcome(Outcome.Kind.SERVICED, accrued.posted(), accrued.zero(), true, billed.billed(),
                    billed.applied(), true);
        }
        Instant permit = accrued.caughtUp() ? today.plusDays(1).atStartOfDay(zone).toInstant() : now;
        if (!store.restamp(unitOfWork, id, permit)) {
            throw new IllegalStateException("the locked account left servicing under its own row lock");
        }
        return new Outcome(Outcome.Kind.SERVICED, accrued.posted(), accrued.zero(), accrued.caughtUp(), billed.billed(),
                billed.applied(), false);
    }

    private static boolean inServicing(LoanStatus status) {
        return status == LoanStatus.ACTIVE || status == LoanStatus.CLOSING;
    }

    // ------------------------------------------------------------------ the accrual step

    /**
     * What the accrual step did, and through which date every elapsed date has been accrued or found with nothing to
     * accrue on - empty when the account has no accrual start (nothing accrues, nothing is billed).
     */
    private record Accrued(int posted, int zero, boolean caughtUp, Optional<LocalDate> accruedThrough) {}

    /** Every elapsed date after the latest accrual, oldest first, under L3 (ADR-0094 points 2-4, 7). */
    private Accrued accrue(
            Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, ZoneId zone, LocalDate today) {
        LoanId id = loan.id();
        Optional<LocalDate> accrualStart = loan.kind() == LoanKind.INSTALMENT
                ? store.accrualStartOf(unitOfWork, id)
                : Optional.of(LocalDate.ofInstant(loan.openedAt(), zone));
        if (accrualStart.isEmpty()) {
            // Path X awaiting its payout's terminal outcome (A30): no accrual start, nothing accrues - never a guess.
            return new Accrued(0, 0, true, Optional.empty());
        }
        LocalDate lastElapsed = today.minusDays(1);
        Optional<RecordedAccrual> latest = store.latestAccrual(unitOfWork, id);
        LocalDate from = latest.map(row -> row.accrual().date().plusDays(1))
                .filter(next -> next.isAfter(accrualStart.get()))
                .orElse(accrualStart.get());
        if (from.isAfter(lastElapsed)) {
            return new Accrued(0, 0, true, Optional.of(lastElapsed));
        }
        Function<LocalDate, AccrualPeriod> periodOf = periods(unitOfWork, loan, agreement, pinned, accrualStart.get());
        AccrualTerms accrualTerms = new AccrualTerms(loan.currency(), agreement.nominalAnnualRate(), pinned.dayCount(),
                pinned.interestRounding(), agreement.accrualEngine());
        CurrencyCode currency = loan.currency();
        LedgerAccountId principal = owned(unitOfWork, id, AccountPurpose.LOAN_PRINCIPAL, currency);
        LedgerAccountId principalDue = owned(unitOfWork, id, AccountPurpose.LOAN_PRINCIPAL_DUE, currency);
        LedgerAccountId interestAccrued = owned(unitOfWork, id, AccountPurpose.LOAN_INTEREST_ACCRUED, currency);
        LedgerAccountId interestIncome = ledgerAccounts
                .findOperational(unitOfWork, AccountPurpose.LOAN_INTEREST_INCOME, currency)
                .orElseThrow(() -> new IllegalStateException(
                        "ledger V026 seeds the loan interest income account in every supported currency"))
                .id();
        // The principal lines through the last elapsed date, in ONE statement: a billing moving principal from not-due to
        // due is wholly in or wholly out.
        List<PurposeBalances.DatedLine> lines =
                balances.datedLinesOn(unitOfWork, Set.of(principal, principalDue), lastElapsed);

        Money base = Money.ofPersisted(0, currency, agreement.amount().scale());
        int next = 0;
        int posted = 0;
        int zero = 0;
        for (LocalDate date = from; !date.isAfter(lastElapsed); date = date.plusDays(1)) {
            while (next < lines.size() && !lines.get(next).valueDate().isAfter(date)) {
                PurposeBalances.DatedLine line = lines.get(next++);
                base = line.direction() == Direction.DEBIT ? base.plus(line.amount()) : base.minus(line.amount());
            }
            if (!base.isPositive()) {
                // No principal outstanding at the end of the date: nothing accrues on it (a line at zero drawn).
                continue;
            }
            if (posted + zero == MAX_ACCRUALS_PER_STEP) {
                return new Accrued(posted, zero, false, Optional.of(date.minusDays(1)));
            }
            AccrualPeriod period = periodOf.apply(date);
            Optional<InterestAccrual> previous = latest.map(RecordedAccrual::accrual)
                    .filter(prior -> prior.period().equals(period));
            InterestAccrual accrual = AccrualEngineV1.accrue(accrualTerms, period, previous, date, base);
            UUID rowId = ids.next();
            Optional<JournalEntryId> entry = accrual.posted().isPositive()
                    ? Optional.of(post(unitOfWork, id, accrual, interestAccrued, interestIncome))
                    : Optional.empty();
            RecordedAccrual row = new RecordedAccrual(rowId, id, accrual, agreement.id(), agreement.version(), entry);
            store.insertAccrual(unitOfWork, row, loan.correlation());
            latest = Optional.of(row);
            if (entry.isPresent()) {
                posted++;
            } else {
                zero++;
            }
        }
        return new Accrued(posted, zero, true, Optional.of(lastElapsed));
    }

    /** The account's accrual periods: the schedule's instalment periods, or the line's statement cycles. */
    private Function<LocalDate, AccrualPeriod> periods(
            Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate accrualStart) {
        if (loan.kind() == LoanKind.INSTALMENT) {
            InstalmentCalendar calendar = store.calendarOf(unitOfWork, loan.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "an instalment loan's accrual start is born beside its schedule version 1 (ADR-0097)"));
            return calendar::periodOf;
        }
        StatementTerms statement = StatementTerms.of(pinned, agreement.repaymentDay());
        return date -> StatementEngineV1.cycle(statement, accrualStart, date);
    }

    /**
     * Posting rule 2 for one date: {@code DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME}, dated the date, its
     * reference the loan (rule 1's) - so a second attempt at the date is the same request under the same key, a replay
     * the step refuses as the defect it is, never a second posting.
     */
    private JournalEntryId post(
            Connection unitOfWork, LoanId loan, InterestAccrual accrual, LedgerAccountId accrued, LedgerAccountId income) {
        LocalDate date = accrual.date();
        PostingResult result = postings.post(unitOfWork, new PostingCommand(
                POSTING_KEY_PREFIX + loan.value() + ":" + date, date, date, loan.value().toString(), List.of(
                        new JournalLine(accrued, Direction.DEBIT, accrual.posted()),
                        new JournalLine(income, Direction.CREDIT, accrual.posted()))));
        if (result.replayed()) {
            // The key's entry exists while the date has no row: the row and its entry were not committed together.
            throw new IllegalStateException("an accrual entry exists for a date with no accrual row (INV-LND-02)");
        }
        return result.entryId();
    }

    private LedgerAccountId owned(Connection unitOfWork, LoanId loan, AccountPurpose purpose, CurrencyCode currency) {
        return ledgerAccounts.findOwned(unitOfWork, loan.value(), purpose, currency)
                .orElseThrow(() -> new IllegalStateException("the acceptance opened the loan's six accounts"))
                .id();
    }
}
