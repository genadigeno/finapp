package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
import com.finapp.lending.LoanReceivables.Accounts;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * T-svc's billing step and its credit-balance application (`P11-TSK-017`; {@code PHASE_11_PLAN.md} sections 7.1, 12.2
 * step 5, 12.3, 12.5 and 12.8 rules 3 and 6; ADR-0094 points 6 and 8, ADR-0095 point 5; {@code INV-LND-01},
 * {@code INV-LND-03}, {@code INV-LND-04}, {@code INV-LND-10}, {@code INV-IDEM-02}). Called by {@link LoanServicing}
 * after its accrual, in the same transaction, under the same loan row lock (L3) - never on its own.
 *
 * <h2>What becomes due, and when</h2>
 *
 * <p>An instalment of the loan's <em>latest</em> schedule version falls due on its due date {@code Dj}; it is billed
 * once {@code Dj} has come on the database clock in the pinned zone (the caller's {@code today}) <em>and</em> every date
 * of its period through {@code Dj - 1} has been accrued (the caller's {@code accruedThrough}: a catch-up stopped at its
 * bound bills only what its accruals reach). Oldest first; several in one step after an outage. The bill:
 *
 * <ul>
 *   <li><strong>interest due</strong> = the sum of the period's {@code interest_accrual.posted_minor} - interest actually
 *       accrued on actual principal outstanding, so a late payment raises the next bill; never the projection;
 *   <li><strong>principal due</strong> = {@code max(0, A - interest due)}, never beyond the principal not yet due; the
 *       final instalment all of it ({@code LOAN_PRINCIPAL}, a ledger balance) - so the principal billed over a loan's life
 *       is its principal exactly, whatever its history;
 *   <li>after the final due date, the monthly calendar continued ({@link InstalmentCalendar}): each period in which
 *       interest accrued on principal still outstanding is billed at its end - interest only ({@link BillingKind#POST_MATURITY}).
 * </ul>
 *
 * <p>Posting rule 3 - {@code DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED}, {@code DR LOAN_PRINCIPAL_DUE /
 * CR LOAN_PRINCIPAL} - re-classifies the same claim as payable, dated the business date of the transaction (ADR-0094
 * point 8: a late billing is applied on the day it is applied), under {@code lending.billing:<instalment>} (a
 * post-maturity period: {@code lending.billing:<loan>:<due date>}), its reference the item billed. A billing of nothing
 * writes its row and no entry.
 *
 * <h2>Money already held pays it (rule 6)</h2>
 *
 * <p>After each billing, a positive {@code LOAN_CREDIT_BALANCE} is applied by {@link AllocationEngineV1#allocate} -
 * the same call a repayment makes, the credit balance the amount - over the billed items still due, oldest first, by the
 * agreement's pinned order: {@code DR LOAN_CREDIT_BALANCE / CR <due accounts>} aggregated per account, the per-item split
 * in {@code credit_balance_allocation}, under {@code lending.credit-application:<billing>}. The due state is derived
 * from the rows (billed less allocated) under L3 and checked against the due accounts' ledger balances before anything is
 * applied - a subledger that does not explain the ledger is a defect, never allocated over ({@code INV-LND-01}).
 *
 * <h2>Why once (the ten-sweeper question)</h2>
 *
 * <p>L3 serialises every step on one account, each reading the billed items after the lock; beneath it
 * {@code instalment_billing UNIQUE (instalment_id)} and {@code UNIQUE (loan_id, due_date)},
 * {@code credit_balance_application UNIQUE (billing_id)}; and the ledger keys, whose replay - an entry with no row - is a
 * defect, never a second posting. No L0 (exposure does not move), no L6, no L7; every ledger account written is the
 * loan's own, written by nobody without L3, so no projection-lock ordering is needed beyond the posting's own.
 */
@RequiredArgsConstructor
public final class InstalmentBillings {

    /** Rule 3's key prefix: {@code lending.billing:<instalment>}, or {@code lending.billing:<loan>:<due date>}. */
    public static final String POSTING_KEY_PREFIX = "lending.billing:";

    /** Rule 6's key prefix: {@code lending.credit-application:<billing>}. */
    public static final String APPLICATION_KEY_PREFIX = CreditBalanceApplications.KEY_PREFIX;

    /** The event a billing publishes - one per billed item. */
    public static final String BILLED_EVENT = "lending.InstalmentBilled";

    @NonNull private final InstalmentBillingStore store;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one billing step did - counts only, for the meter. */
    public record Billed(int billed, int applied) {

        public Billed {
            if (billed < 0 || applied < 0) {
                throw new IllegalArgumentException("counts are not negative");
            }
        }

        static final Billed NOTHING = new Billed(0, 0);
    }

    /** A due date the step may bill: an instalment, or a post-maturity period. */
    private record Candidate(BillingKind kind, Optional<UUID> instalment, int sequence, AccrualPeriod period) {}

    /**
     * Bills every instalment of {@code loan} due by {@code today} whose period is accrued through
     * {@code accruedThrough}, oldest first, each followed by the credit-balance application - on the caller's unit of
     * work, under the caller's L3 and correlation.
     *
     * @param today the business date of the database clock in the pinned zone, read after L3
     * @param accruedThrough the last date the caller's accrual step has accrued or found nothing to accrue on
     */
    public Billed bill(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            LocalDate accruedThrough) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(accruedThrough, "accruedThrough");
        if (loan.kind() != LoanKind.INSTALMENT) {
            return Billed.NOTHING; // A line's statement is P11-TSK-022's.
        }
        Optional<BillableSchedule> latest = store.latestSchedule(unitOfWork, loan.id());
        if (latest.isEmpty()) {
            return Billed.NOTHING; // Path X awaiting its payout: no schedule yet, nothing falls due.
        }
        BillableSchedule schedule = latest.get();
        // Dj is billable once it has come and every date through Dj - 1 is accrued.
        LocalDate limit = today.isBefore(accruedThrough.plusDays(1)) ? today : accruedThrough.plusDays(1);
        List<BilledItem> items = store.billedItems(unitOfWork, loan.id());
        return bill(unitOfWork, loan, agreement, pinned, today, schedule, candidates(schedule, items, limit));
    }

    /**
     * Bills <em>everything</em> {@code loan} has not yet billed, oldest first, each followed by the credit-balance
     * application - a payoff's billing (`P11-TSK-026`, posting rule 7's rule 3): every unbilled instalment of the latest
     * schedule version whatever its due date (a period not yet begun bills no interest and its principal portion, the final
     * instalment all remaining principal), then - once every instalment is billed - each post-maturity period that has
     * begun and holds an accrual. On the caller's unit of work, under the caller's L3, after the caller has accrued every
     * elapsed date and beside the loan's {@code payoff_execution} (the billing trigger admits an early billing only there).
     * Afterwards {@code LOAN_PRINCIPAL} and {@code LOAN_INTEREST_ACCRUED} are zero: what the loan owes is all billed.
     *
     * @param today the business date of the database clock in the pinned zone, read after L3 - the payoff's date
     */
    Billed billForPayoff(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        if (loan.kind() != LoanKind.INSTALMENT) {
            return Billed.NOTHING;
        }
        Optional<BillableSchedule> latest = store.latestSchedule(unitOfWork, loan.id());
        if (latest.isEmpty()) {
            return Billed.NOTHING;
        }
        BillableSchedule schedule = latest.get();
        List<BilledItem> items = store.billedItems(unitOfWork, loan.id());
        return bill(unitOfWork, loan, agreement, pinned, today, schedule, everythingUnbilled(schedule, items, today));
    }

    /** Bills {@code candidates}, oldest first, each followed by the credit-balance application. */
    private Billed bill(Connection unitOfWork, Loan loan, LoanAgreement agreement, LoanTerms pinned, LocalDate today,
            BillableSchedule schedule, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Billed.NOTHING;
        }
        CurrencyCode currency = loan.currency();
        Money zero = Money.ofPersisted(0, currency, agreement.amount().scale());
        Accounts accounts = receivables().accounts(unitOfWork, loan.id(), currency);
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        // The principal not yet due, from the ledger under L3 - what the final instalment bills and nothing exceeds.
        Money notDue = receivables().netDebit(unitOfWork, accounts.principal(), zero);
        int billed = 0;
        int applied = 0;
        for (Candidate candidate : candidates) {
            InstalmentBillingStore.PeriodAccruals accrued =
                    store.periodAccruals(unitOfWork, loan.id(), candidate.period(), zero);
            if (candidate.kind() == BillingKind.POST_MATURITY && accrued.rows() == 0) {
                continue; // Nothing accrued in the period (no principal outstanding in it): nothing falls due.
            }
            Money interest = accrued.posted();
            Money principal = principalDue(candidate, schedule, interest, notDue, zero);
            UUID billingId = ids.next();
            Optional<JournalEntryId> entry = interest.plus(principal).isPositive()
                    ? Optional.of(postBilling(unitOfWork, loan, candidate, interest, principal, today, accounts))
                    : Optional.empty();
            InstalmentBilling billing = new InstalmentBilling(billingId, loan.id(), candidate.kind(), schedule.id(),
                    schedule.version(), candidate.instalment(), candidate.sequence(), candidate.period(), interest,
                    principal, agreement.id(), agreement.version(), schedule.engine(), agreement.accrualEngine(), entry);
            store.insertBilling(unitOfWork, billing, loan.correlation());
            notDue = notDue.minus(principal);
            billed++;
            Optional<CreditBalanceApplication> application =
                    apply(unitOfWork, loan, agreement, rules, billing, today, accounts, zero);
            if (application.isPresent()) {
                applied++;
            }
            publish(unitOfWork, loan, billing, application);
        }
        return new Billed(billed, applied);
    }

    // ------------------------------------------------------------------ what falls due

    /**
     * The unbilled instalments of the latest version due by {@code limit}, oldest first; then - once every instalment is
     * billed or about to be - the post-maturity periods ending by {@code limit} after the latest billed due date.
     */
    private static List<Candidate> candidates(BillableSchedule schedule, List<BilledItem> items, LocalDate limit) {
        Set<UUID> billedInstalments = new HashSet<>();
        LocalDate lastDue = null;
        for (BilledItem item : items) {
            item.billing().instalment().ifPresent(billedInstalments::add);
            LocalDate due = item.billing().dueDate();
            lastDue = lastDue == null || due.isAfter(lastDue) ? due : lastDue;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (BillableSchedule.Instalment instalment : schedule.instalments()) {
            if (billedInstalments.contains(instalment.id())) {
                continue;
            }
            if (instalment.period().endExclusive().isAfter(limit)) {
                return candidates; // Not yet due (or its period not yet accrued): neither is any later one.
            }
            candidates.add(new Candidate(BillingKind.INSTALMENT, Optional.of(instalment.id()), instalment.sequence(),
                    instalment.period()));
        }
        // Every instalment billed: the calendar continued after the final due date, interest only.
        InstalmentCalendar calendar = schedule.calendar();
        int sequence = schedule.instalments().size();
        AccrualPeriod period = calendar.periodOf(schedule.last().period().endExclusive());
        while (!period.endExclusive().isAfter(limit)) {
            sequence++;
            if (lastDue == null || period.endExclusive().isAfter(lastDue)) {
                candidates.add(new Candidate(BillingKind.POST_MATURITY, Optional.empty(), sequence, period));
            }
            period = calendar.periodOf(period.endExclusive());
        }
        return candidates;
    }

    /**
     * A payoff's candidates: every unbilled instalment of the latest version, oldest first, whatever its due date; then -
     * every instalment billed or about to be - the post-maturity periods after the latest billed due date that have begun
     * by {@code today} (those holding no accrual are skipped by the billing itself).
     */
    private static List<Candidate> everythingUnbilled(BillableSchedule schedule, List<BilledItem> items, LocalDate today) {
        Set<UUID> billedInstalments = new HashSet<>();
        LocalDate lastDue = null;
        for (BilledItem item : items) {
            item.billing().instalment().ifPresent(billedInstalments::add);
            LocalDate due = item.billing().dueDate();
            lastDue = lastDue == null || due.isAfter(lastDue) ? due : lastDue;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (BillableSchedule.Instalment instalment : schedule.instalments()) {
            if (!billedInstalments.contains(instalment.id())) {
                candidates.add(new Candidate(BillingKind.INSTALMENT, Optional.of(instalment.id()), instalment.sequence(),
                        instalment.period()));
            }
        }
        InstalmentCalendar calendar = schedule.calendar();
        int sequence = schedule.instalments().size();
        AccrualPeriod period = calendar.periodOf(schedule.last().period().endExclusive());
        while (period.start().isBefore(today)) {
            sequence++;
            if (lastDue == null || period.endExclusive().isAfter(lastDue)) {
                candidates.add(new Candidate(BillingKind.POST_MATURITY, Optional.empty(), sequence, period));
            }
            period = calendar.periodOf(period.endExclusive());
        }
        return candidates;
    }

    /** {@code max(0, A - interest)}, never beyond the principal not yet due; the final instalment all of it. */
    private static Money principalDue(
            Candidate candidate, BillableSchedule schedule, Money interest, Money notDue, Money zero) {
        if (candidate.kind() == BillingKind.POST_MATURITY) {
            return zero;
        }
        if (candidate.sequence() == schedule.last().sequence()) {
            return notDue.isNegative() ? zero : notDue;
        }
        Money portion = schedule.instalment().minus(interest);
        if (!portion.isPositive()) {
            return zero;
        }
        return portion.compareTo(notDue) <= 0 ? portion : (notDue.isNegative() ? zero : notDue);
    }

    // ------------------------------------------------------------------ rule 3

    private JournalEntryId postBilling(Connection unitOfWork, Loan loan, Candidate candidate, Money interest,
            Money principal, LocalDate today, Accounts accounts) {
        List<JournalLine> lines = new ArrayList<>();
        if (interest.isPositive()) {
            lines.add(new JournalLine(accounts.interestDue(), Direction.DEBIT, interest));
            lines.add(new JournalLine(accounts.interestAccrued(), Direction.CREDIT, interest));
        }
        if (principal.isPositive()) {
            lines.add(new JournalLine(accounts.principalDue(), Direction.DEBIT, principal));
            lines.add(new JournalLine(accounts.principal(), Direction.CREDIT, principal));
        }
        String key = candidate.instalment()
                .map(instalment -> POSTING_KEY_PREFIX + instalment)
                .orElseGet(() -> POSTING_KEY_PREFIX + loan.id().value() + ":" + candidate.period().endExclusive());
        // The reference is the billed item - the instalment, or (a post-maturity period, which has no row of its own)
        // the loan, rule 1's and rule 2's - never the attempt's fresh billing id: a second attempt at the item from the
        // same history is then the same request under the same key, a replay refused below as the defect it is.
        String reference = candidate.instalment().map(UUID::toString).orElseGet(() -> loan.id().value().toString());
        PostingResult result = postings.post(unitOfWork, new PostingCommand(key, today, today, reference, lines));
        if (result.replayed()) {
            // The key's entry exists while the item has no billing: the row and its entry were not committed together.
            throw new IllegalStateException("a billing entry exists for an item with no billing (INV-LND-01)");
        }
        return result.entryId();
    }

    // ------------------------------------------------------------------ rule 6

    /**
     * Applies the loan's credit balance over its billed items, oldest first, if both exist - rule 6's one implementation,
     * shared with the line's statement ({@link CreditBalanceApplications}, lifted out of this step by `P11-TSK-022`).
     */
    private Optional<CreditBalanceApplication> apply(Connection unitOfWork, Loan loan, LoanAgreement agreement,
            AllocationRules rules, InstalmentBilling billing, LocalDate today, Accounts accounts, Money zero) {
        return new CreditBalanceApplications(store, ledgerAccounts, balances, postings, ids)
                .apply(unitOfWork, loan, agreement, rules, billing.id(), today, accounts, zero);
    }

    /**
     * Rule 6 over what is ALREADY due (the Phase 11 gate's R2, {@code lending V029}): {@code loan}'s credit balance applied
     * over its billed items' amounts due, oldest first - the loan's next catch-up application - when both exist; either
     * product (a line's statements are billed items of the same store). The one implementation
     * ({@link CreditBalanceApplications#catchUp}); on the caller's unit of work, under its L3 - the servicing step after
     * billing and before the collection, the condition and the late fee; the payoff before its recomputation.
     */
    Optional<CreditBalanceApplication> applyHeldCredit(Connection unitOfWork, Loan loan, LoanAgreement agreement,
            LoanTerms pinned, LocalDate today) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(today, "today");
        Money zero = Money.ofPersisted(0, loan.currency(), agreement.amount().scale());
        Accounts accounts = receivables().accounts(unitOfWork, loan.id(), loan.currency());
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        return new CreditBalanceApplications(store, ledgerAccounts, balances, postings, ids)
                .catchUp(unitOfWork, loan, agreement, rules, today, accounts, zero);
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code lending.InstalmentBilled} - the item, its due date, what it made due (fees none: a late fee is
     * {@code P11-TSK-025}'s own assessment) and what the credit balance paid of the loan's items at it.
     */
    private void publish(Connection unitOfWork, Loan loan, InstalmentBilling billing,
            Optional<CreditBalanceApplication> application) {
        Money interest = billing.interestDue();
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("billingId", billing.id().toString())
                .with("kind", billing.kind().name())
                .with("sequence", Integer.toString(billing.sequence()))
                .with("scheduleVersion", Integer.toString(billing.scheduleVersion()))
                .with("agreementVersion", Integer.toString(billing.agreementVersion()))
                .with("dueDate", billing.dueDate().toString())
                .with("interestDueMinor", Long.toString(interest.minorUnits()))
                .with("principalDueMinor", Long.toString(billing.principalDue().minorUnits()))
                .with("feesDueMinor", "0")
                .with("creditAppliedMinor",
                        Long.toString(application.map(applied -> applied.amount().minorUnits()).orElse(0L)))
                .with("scale", Integer.toString(interest.scale()))
                .with("currency", interest.currency().code());
        if (billing.entry().isPresent()) {
            payload = payload.with("journalEntryId", billing.entry().get().value().toString());
        }
        CorrelationId correlation = loan.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        BILLED_EVENT,
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

    // ------------------------------------------------------------------ the ledger

    /**
     * The loan's receivables, read under L3 from its journal lines and billed items - the derivation the repayment and
     * the closure share ({@link LoanReceivables}, lifted out of this step by `P11-TSK-018`).
     */
    private LoanReceivables receivables() {
        return new LoanReceivables(store, ledgerAccounts, balances);
    }
}
