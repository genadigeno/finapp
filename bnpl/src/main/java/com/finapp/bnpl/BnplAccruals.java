package com.finapp.bnpl;

import com.finapp.creditmath.AccrualEngineV1;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Pay-in-N interest accrual - T-acc (`P12-TSK-017`; {@code PHASE_12_PLAN.md} sections 7.1 and 12.11 F2a; ADR-0101 decision
 * 11, ADR-0103; lending's {@code LoanServicing#accrue}, {@code P11-TSK-016}; {@code INV-LND-02}, {@code INV-LND-09} and
 * {@code INV-LND-10} by analogy, {@code INV-IDEM-02}, {@code INV-BAL-05}, {@code INV-BNPL-01}). A system act with no door:
 * interest is earned because a day ended, never because someone asked.
 *
 * <h2>T-acc - its own transaction, one obligation per call</h2>
 *
 * <ol>
 *   <li><strong>(B4)</strong> the obligation {@code FOR UPDATE}; one not {@code ACTIVE}, or a pay-in-4 (interest-free, A4),
 *       accrues nothing;
 *   <li>the database clock, read after the lock, and its business date in the pinned zone - never an instance clock;
 *   <li>every <em>elapsed</em> date of the obligation's accrual span not yet accrued, oldest first: the span is the latest
 *       schedule version's instalment periods {@code [start, D1), [D1, D2), ... [D(n-1), Dn)} from the purchase's business
 *       date; a date {@code d} is elapsed when the database clock is past its end. After the final due date nothing
 *       accrues - there is no billing to make it due and the plan admits no penalty interest (section 12.3);
 *   <li>each date on the principal outstanding at its end - {@code BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE} over the journal
 *       lines valued on or before it, read in ONE statement under B4 and folded through {@link Money}: never interest or
 *       fees, never the projection ({@code INV-BAL-05}); a date with none writes no row;
 *   <li>{@link AccrualEngineV1} built from {@link AccrualTerms}' canonical constructor over the pinned terms and the
 *       agreement's accrual engine, its predecessor the period's previous accrual - cumulative rounding once per
 *       instalment period;
 *   <li>F2a, {@code DR BNPL_INTEREST_ACCRUED / CR BNPL_INTEREST_INCOME}, key {@code bnpl.accrual:<obligation>:<date>},
 *       value-dated {@code d}, posted on the business date - the currency's shared income row taken at <strong>(L8)</strong>
 *       with the obligation's accrued-interest row, before the first posting; then the row. A zero day writes the row and
 *       no entry;
 *   <li>commit. T-acc takes <strong>no wallet and no payable</strong>, so the shared {@code BNPL_INTEREST_INCOME} row is
 *       never held across a wallet wait (the Phase 11 gate's A2-F1, designed out).
 * </ol>
 *
 * <p>At most {@link #MAX_ACCRUALS_PER_STEP} dates per call: after a long outage the call commits and answers not caught up,
 * and the caller calls again - every date still accrued oldest first, none skipped, no transaction growing with the outage.
 *
 * <h2>Caught up - what every other act checks, never does</h2>
 *
 * <p>T-svc, the repayment (`-018`), the refund (`-021`), the payoff (`-022`) and the corrections never accrue inside their
 * own transaction: under their B4 each asks {@link #caughtUp} - every elapsed date of the span with principal outstanding
 * accrued - and, when not, writes nothing and runs T-acc first ({@code BnplServicing} answers {@code BEHIND_ACCRUAL}).
 *
 * <h2>Why once (scenario 18)</h2>
 *
 * <p>B4 serialises every act on one obligation, each reading the latest accrual after the lock; beneath it
 * {@code bnpl_interest_accrual UNIQUE (obligation_id, accrual_date)} and {@code bnpl V009}'s oldest-first, never-early ranks;
 * and the ledger key, whose replay - an entry for a date with no row - is a defect the book refuses, never a second
 * posting. A date's row and its entry commit together, with every other date of the call, or not at all. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplAccruals {

    /** F2a's key prefix: one entry per obligation and date, however often it is attempted. */
    public static final String KEY_PREFIX = "bnpl.accrual:";

    /**
     * Reserved (ADR-0094 point 11's shape): the key a correction run would post an engine version's delta under -
     * {@code bnpl.accrual-correction:<obligation>:<date>:<engine>}. Not built in Phase 12 (nothing to correct); named so no
     * other key collides with it. Nothing posts under it.
     */
    public static final String CORRECTION_KEY_PREFIX = "bnpl.accrual-correction:";

    /** The most dates one call accrues - lending's bound; the caller calls again until caught up. */
    public static final int MAX_ACCRUALS_PER_STEP = 92;

    @NonNull private final BnplServicingStore servicing;
    @NonNull private final BnplAccrualStore store;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplBook book;
    @NonNull private final IdGenerator ids;

    /**
     * What one T-acc call did - for the meters; never a figure, a date or a party: its kind, the obligation's product, the
     * dates that {@code posted} an entry and the {@code zero} dates that wrote their row alone, and whether every elapsed
     * date is now accrued ({@code caughtUp}).
     */
    public record Outcome(Kind kind, Optional<BnplProduct> product, int posted, int zero, boolean caughtUp) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(product, "product must not be null");
            if (posted < 0 || zero < 0) {
                throw new IllegalArgumentException("counts are not negative");
            }
            if (kind == Kind.NOT_ACCRUING && (posted > 0 || zero > 0 || !caughtUp)) {
                throw new IllegalArgumentException("an obligation that does not accrue writes nothing and is never behind");
            }
        }

        static Outcome notAccruing(Optional<BnplProduct> product) {
            return new Outcome(Kind.NOT_ACCRUING, product, 0, 0, true);
        }

        /** The call's effect. */
        public enum Kind {
            /** Gone, no longer {@code ACTIVE}, or a pay-in-4 obligation - nothing accrues, nothing written. */
            NOT_ACCRUING,
            /** A pay-in-N obligation held under B4: {@code posted} and {@code zero} dates accrued, oldest first. */
            ACCRUED
        }
    }

    /** One elapsed date still to accrue: its instalment period and the principal outstanding at its end. */
    record Pending(LocalDate date, AccrualPeriod period, Money base) {

        Pending {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(base, "base");
        }

        /** The date only - never the base ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Pending[" + date + "]";
        }
    }

    // ------------------------------------------------------------------ T-acc

    /** T-acc for one obligation, on the caller's unit of work - a transaction of its own that holds no wallet or payable. */
    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    public Outcome accrue(Connection unitOfWork, BnplObligationId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        // (B4) The obligation - every act on it serialises here.
        Optional<BnplServicingStore.Locked> found = servicing.lock(unitOfWork, id);
        if (found.isEmpty() || found.get().obligation().status() != BnplObligationStatus.ACTIVE) {
            return Outcome.notAccruing(Optional.empty());
        }
        BnplServicingStore.Locked locked = found.get();
        BnplObligation obligation = locked.obligation();
        if (locked.accrualEngine().isEmpty()) {
            // Pay-in-4: interest-free (A4) - its agreement pins no accrual engine and nothing ever accrues.
            return Outcome.notAccruing(Optional.of(obligation.product()));
        }
        // The database clock, read after the lock, in the pinned zone - never an instance clock.
        Instant now = servicing.databaseNow(unitOfWork);
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(locked.servicingZone()));
        Optional<BnplInterestAccrual> latest = store.latest(unitOfWork, id);
        List<Pending> pending = unaccrued(unitOfWork, servicing, purchases, book, locked, today,
                latest.map(row -> row.accrual().date()));
        if (pending.isEmpty()) {
            return new Outcome(Outcome.Kind.ACCRUED, Optional.of(obligation.product()), 0, 0, true);
        }
        AccrualTerms accrualTerms = accrualTerms(unitOfWork, locked);
        BnplLedgerAccounts accounts = new BnplReceivables(purchases, book).accounts(unitOfWork, id);
        LedgerAccountId income = book.operational(unitOfWork, AccountPurpose.BNPL_INTEREST_INCOME, obligation.currency());
        Optional<InterestAccrual> previous = latest.map(BnplInterestAccrual::accrual);
        boolean incomeHeld = false;
        int posted = 0;
        int zero = 0;
        Correlation flow = Correlation.startingWith(obligation.correlation())
                .causing(CausationId.of(obligation.correlation().value()));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow)) {
            for (Pending date : pending) {
                if (posted + zero == MAX_ACCRUALS_PER_STEP) {
                    return new Outcome(Outcome.Kind.ACCRUED, Optional.of(obligation.product()), posted, zero, false);
                }
                // The period's previous accrual is the predecessor; a period's first date resets the cumulative figure.
                Optional<InterestAccrual> predecessor = previous.filter(prior -> prior.period().equals(date.period()));
                InterestAccrual accrual = AccrualEngineV1.accrue(accrualTerms, date.period(), predecessor, date.date(),
                        date.base());
                Optional<JournalEntryId> entry = Optional.empty();
                if (accrual.posted().isPositive()) {
                    if (!incomeHeld) {
                        // (L8) The obligation's accrued-interest row and the currency's shared income row, in the ledger's
                        // one order, before the first posting - the only rows T-acc takes after B4.
                        book.lockBalancesInOrder(unitOfWork, List.of(accounts.interestAccrued().id(), income));
                        incomeHeld = true;
                    }
                    entry = Optional.of(post(unitOfWork, id, accrual, accounts.interestAccrued().id(), income, today));
                }
                store.insert(unitOfWork, new BnplInterestAccrual(ids.next(), id, accrual, locked.agreement(),
                        locked.agreementVersion(), entry, obligation.correlation()));
                previous = Optional.of(accrual);
                if (entry.isPresent()) {
                    posted++;
                } else {
                    zero++;
                }
            }
        }
        return new Outcome(Outcome.Kind.ACCRUED, Optional.of(obligation.product()), posted, zero, true);
    }

    // ------------------------------------------------------------------ caught up

    /**
     * Whether every elapsed date of the obligation's accrual span with principal outstanding at its end is accrued - the
     * check every act runs under its own B4 instead of accruing (T-svc, T-rep, T-ref, T-pay, the corrections). Always
     * {@code true} for pay-in-4.
     */
    public boolean caughtUp(Connection unitOfWork, BnplServicingStore.Locked locked, LocalDate today) {
        return caughtUp(unitOfWork, servicing, purchases, book, locked, today);
    }

    /** {@link #caughtUp(Connection, BnplServicingStore.Locked, LocalDate)} over the caller's own stores. */
    static boolean caughtUp(Connection unitOfWork, BnplServicingStore servicing, BnplPurchaseStore purchases, BnplBook book,
            BnplServicingStore.Locked locked, LocalDate today) {
        Objects.requireNonNull(locked, "locked");
        Objects.requireNonNull(today, "today");
        if (locked.accrualEngine().isEmpty()) {
            return true;
        }
        return unaccrued(unitOfWork, servicing, purchases, book, locked, today,
                servicing.latestAccrualDate(unitOfWork, locked.obligation().id())).isEmpty();
    }

    /**
     * The elapsed dates of the span after {@code latest} - or from its start - that end with principal outstanding, oldest
     * first, each with its period and its base: what T-acc accrues, and what "caught up" requires to be empty.
     */
    static List<Pending> unaccrued(Connection unitOfWork, BnplServicingStore servicing, BnplPurchaseStore purchases,
            BnplBook book, BnplServicingStore.Locked locked, LocalDate today, Optional<LocalDate> latest) {
        BnplObligation obligation = locked.obligation();
        BnplPurchaseStore.StoredSchedule schedule = servicing.latestSchedule(unitOfWork, obligation.id())
                .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1"));
        LocalDate start = obligation.purchaseDate();
        if (!schedule.start().equals(start)) {
            throw new IllegalStateException("a pay-in-N schedule starts at its purchase's business date (ADR-0102)");
        }
        if (schedule.instalments().isEmpty()) {
            // A refund's version that left nothing to bill and nothing billed (`P12-TSK-021`): no period, no accrual span.
            return List.of();
        }
        List<AccrualPeriod> periods = periodsOf(schedule);
        LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
        LocalDate lastElapsed = today.minusDays(1);
        LocalDate last = lastElapsed.isBefore(spanEnd) ? lastElapsed : spanEnd.minusDays(1);
        LocalDate from = latest.map(date -> date.plusDays(1)).filter(next -> next.isAfter(start)).orElse(start);
        if (from.isAfter(last)) {
            return List.of();
        }
        BnplLedgerAccounts accounts = new BnplReceivables(purchases, book).accounts(unitOfWork, obligation.id());
        // The principal lines through the last date in ONE statement: a billing moving principal from not-due to due is
        // wholly in or wholly out.
        List<PurposeBalances.DatedLine> lines = book.datedLinesOn(unitOfWork,
                Set.of(accounts.principal().id(), accounts.principalDue().id()), last);
        CurrencyCode currency = obligation.currency();
        Money base = Money.ofPersisted(0, currency, currency.minorUnits());
        List<Pending> pending = new ArrayList<>();
        int next = 0;
        for (LocalDate date = from; !date.isAfter(last); date = date.plusDays(1)) {
            while (next < lines.size() && !lines.get(next).valueDate().isAfter(date)) {
                PurposeBalances.DatedLine line = lines.get(next++);
                base = line.direction() == Direction.DEBIT ? base.plus(line.amount()) : base.minus(line.amount());
            }
            if (!base.isPositive()) {
                // No principal outstanding at the end of the date: nothing accrues on it, and no row is written.
                continue;
            }
            pending.add(new Pending(date, periodOf(periods, date), base));
        }
        return List.copyOf(pending);
    }

    /** The latest schedule version's instalment periods: {@code [start, D1), [D1, D2), ... [D(n-1), Dn)}. */
    static List<AccrualPeriod> periodsOf(BnplPurchaseStore.StoredSchedule schedule) {
        List<AccrualPeriod> periods = new ArrayList<>();
        LocalDate from = schedule.start();
        for (BnplPurchaseStore.StoredInstalment instalment : schedule.instalments()) {
            periods.add(new AccrualPeriod(from, instalment.dueDate()));
            from = instalment.dueDate();
        }
        if (periods.isEmpty()) {
            throw new IllegalStateException("a schedule has at least one instalment");
        }
        return List.copyOf(periods);
    }

    private static AccrualPeriod periodOf(List<AccrualPeriod> periods, LocalDate date) {
        for (AccrualPeriod period : periods) {
            if (period.contains(date)) {
                return period;
            }
        }
        throw new IllegalStateException("every date of the accrual span lies in an instalment period");
    }

    // ------------------------------------------------------------------ the terms and F2a

    /** {@link AccrualTerms}' canonical constructor over the pinned terms and the agreement's accrual engine (INV-LND-09). */
    private AccrualTerms accrualTerms(Connection unitOfWork, BnplServicingStore.Locked locked) {
        BnplTerms pinned = terms.version(unitOfWork, locked.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
        BnplTerms.PayInNTerms own = pinned.payInN()
                .orElseThrow(() -> new IllegalStateException("a pay-in-N agreement pins pay-in-N terms"));
        EngineVersion engine = EngineVersion.valueOf(locked.accrualEngine().orElseThrow());
        if (own.accrualEngine() != engine) {
            throw new IllegalStateException("the pinned terms and the agreement name one accrual engine (INV-LND-09)");
        }
        return new AccrualTerms(locked.obligation().currency(), pinned.nominalAnnualRate(), own.dayCount(),
                own.interestRounding(), engine);
    }

    /**
     * F2a for one date: {@code DR BNPL_INTEREST_ACCRUED / CR BNPL_INTEREST_INCOME}, value-dated the date, posted on the
     * business date, its reference the obligation - so a second attempt at the date is the same request under the same
     * key, a replay the book refuses as the defect it is (an entry for a date with no row), never a second posting.
     */
    private JournalEntryId post(Connection unitOfWork, BnplObligationId obligation, InterestAccrual accrual,
            LedgerAccountId accrued, LedgerAccountId income, LocalDate today) {
        LocalDate date = accrual.date();
        return book.post(unitOfWork, new PostingCommand(key(obligation, date), today, date,
                obligation.value().toString(), List.of(
                        new JournalLine(accrued, Direction.DEBIT, accrual.posted()),
                        new JournalLine(income, Direction.CREDIT, accrual.posted()))));
    }

    /** F2a's key for {@code obligation} and {@code date}: {@code bnpl.accrual:<obligation>:<date>}. */
    public static String key(BnplObligationId obligation, LocalDate date) {
        return KEY_PREFIX + obligation.value() + ":" + date;
    }
}
