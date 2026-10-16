package com.finapp.lending;

import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore.BilledItem;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The borrower's partial prepayment with recalculation - T-ppy (`P11-TSK-028`; {@code PHASE_11_PLAN.md} sections 7.1, 7.2,
 * 7.4, 12.2 step 7, 12.5 and 12.8 rule 5; ADR-0099 point 6, ADR-0095, ADR-0093; {@code INV-LND-03}, {@code INV-LND-05},
 * {@code INV-LND-04}, {@code INV-IDEM-01}). The borrower pays principal not yet due early from the wallet; the term
 * shortens and the instalment stays - agreement version n+1 and schedule version n+1 in the same, ONE transaction on the
 * caller's unit of work, which the door's idempotency claim ({@code lending.prepayment:CUSTOMER:<id>}) wraps: a retried key
 * replays, and every refusal rolls the whole transaction back with the claim (the catch-up included), so it writes nothing.
 *
 * <h2>The order, which is the lock order (L3, the catch-up's own ledger rows, L7, L8) - T-pay's</h2>
 *
 * <ol>
 *   <li><strong>(L3)</strong> the loan {@code FOR UPDATE}, owner-scoped - the serialisation point it shares with every
 *       servicing step, repayment, collection, payoff, reversal, waiver and amendment acceptance on the account;
 *   <li>a prepayment already born under the customer's key converges - the same amount answers it as a replay with the
 *       versions it made, another amount is the key reused ({@code UNIQUE (loan_id, kind, request_key)});
 *   <li>an {@code ACTIVE} instalment loan ({@code LoanNotRepayable} otherwise - a closed, cancelled or undisbursed loan, or
 *       a credit line, whose repayment already pays its principal down, `P11-TSK-023`); the amount positive in the loan's
 *       currency and scale;
 *   <li>the database clock after the lock; <strong>the servicing step's own catch-up</strong> - every elapsed date accrued
 *       ({@link LoanServicing#catchUp}), everything due billed, each billing followed by the credit-balance application
 *       ({@link InstalmentBillings#bill}) - so a billing on the same business date always comes first, whichever
 *       transaction runs it (the backlog's "the billing first by the step order"); a loan awaiting its payout has no
 *       schedule to recalculate ({@code LoanNotRepayable});
 *   <li>the due state derived under L3 and proven against the ledger ({@link LoanReceivables#dueState});
 *       {@link AllocationEngineV1#allocatePrepayment} - anything billed still owing, past due or due today, is
 *       {@link ArrearsFirst}; an amount reaching the principal not yet due is {@link PrepaymentExceedsPrincipal} (the whole
 *       is the payoff's, which also settles the open period's interest - so a prepayment never closes the loan);
 *   <li>the new schedule: the open period (the latest calendar's period holding today), the principal outstanding after
 *       the prepayment ({@code LOAN_PRINCIPAL} less the amount), the superseded schedule's level instalment and at most its
 *       remaining instalments - {@link ScheduleEngineV1#recalculateAtInstalment} under the governing agreement's rate;
 *   <li><strong>(L7)</strong> the borrower's wallet ledger row and its available balance judged under it
 *       ({@code InsufficientFunds}) - after the catch-up's postings, exactly as T-svc and T-pay take it;
 *   <li>(L8) and posting rule 5 through {@link Repayments}' one posting half - kind {@code PREPAYMENT}, its one line
 *       {@code CR LOAN_PRINCIPAL}, key {@code lending.repayment:<repaymentId>}, {@code lending.RepaymentReceived};
 *   <li>then only inserts: agreement version n+1 (a prepayment's - canonical terms format 4), schedule version n+1 and its
 *       instalments (billed instalments kept, unbilled superseded, nothing updated), {@code lending.LoanAmended}.
 * </ol>
 *
 * <p><strong>No L0</strong>: a prepayment only lowers outstanding exposure ({@code LOAN_PRINCIPAL} falls at commit; a
 * deciding transaction meanwhile reads the higher, safe figure). <strong>No L6</strong>: repaid principal restores capital
 * headroom at commit. <strong>No L5</strong>: the repayment row is born here, and no other transaction can wait on a row
 * this one creates. <strong>No standing check</strong> (a suspended customer may still repay) and <strong>no audit
 * record</strong>: the customer's own act is its rows and entries. A pending amendment of version n lapses by derivation
 * (the agreement moved); a payoff quote made under version n is stale. A prepayment is never reversed
 * ({@link RepaymentReversals} and {@code lending V024} refuse it): its correction is an amendment.
 */
@RequiredArgsConstructor
public final class Prepayments {

    /** The origin a prepayment's {@code lending.LoanAmended} names. */
    public static final String ORIGIN = "PREPAYMENT";

    @NonNull private final LoanStore loans;
    @NonNull private final RepaymentStore repaymentStore;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanOfferStore offers;
    @NonNull private final LoanServicing servicing;
    @NonNull private final LoanServicingStore servicingStore;
    @NonNull private final InstalmentBillings billings;
    @NonNull private final InstalmentBillingStore billingStore;
    @NonNull private final LoanDisbursementStore schedules;
    @NonNull private final LoanWallets wallets;
    @NonNull private final AvailableBalance<Connection> available;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final Repayments repayments;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What the customer asks: an amount of principal to pay early, under their own key. */
    public record Request(String requestKey, Money amount) {

        public Request {
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (requestKey.isEmpty() || requestKey.length() > Repayments.MAX_REQUEST_KEY_LENGTH) {
                throw new IllegalArgumentException("a prepayment key is 1 to " + Repayments.MAX_REQUEST_KEY_LENGTH
                        + " characters");
            }
        }

        /** Never the key or the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    /**
     * What the prepayment committed - the repayment and the re-schedule it made - or, {@code replayed}, the prepayment a
     * key retried after its claim lapsed converged on.
     */
    public record Prepaid(Repayment repayment, RepaymentStore.Rescheduled rescheduled, boolean replayed) {

        public Prepaid {
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(rescheduled, "rescheduled must not be null");
        }
    }

    // ------------------------------------------------------------------ T-ppy

    /**
     * Prepays {@code request.amount()} of {@code party}'s loan {@code id}'s principal not yet due from their wallet, on the
     * caller's unit of work, inside the caller's correlation and actor scopes (the postings carry both), in the order the
     * class names.
     *
     * @throws Repayments.LoanNotFound the party has no loan with this id (another party's included)
     * @throws Repayments.RepaymentKeyReused a prepayment under this key exists for another amount
     * @throws Repayments.LoanNotRepayable not an {@code ACTIVE}, scheduled instalment loan
     * @throws Repayments.AmountInvalid the amount is not positive, or not in the loan's currency and scale
     * @throws ArrearsFirst something billed is still owing - past due or due today
     * @throws PrepaymentExceedsPrincipal the amount reaches the principal not yet due
     * @throws Repayments.InsufficientFunds the wallet's available balance cannot cover the amount, or it cannot be debited
     * @throws AllocationAnomaly the loan's rows do not explain its due accounts
     */
    public Prepaid prepay(Connection unitOfWork, LoanId id, UUID party, Request request, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (L3) The loan, owner-scoped: another party's account is never locked, and is the same answer as an absent one.
        LoanStore.Owned locked = loans.lockOwnedBy(unitOfWork, id, party).orElseThrow(Repayments.LoanNotFound::new);
        Loan loan = locked.loan();
        LoanAgreement agreement = locked.agreement();
        // A prepayment already born under this key: the arbiter behind a claim that has lapsed.
        Optional<Repayment> born = repaymentStore.findByKey(unitOfWork, id, RepaymentKind.PREPAYMENT, request.requestKey());
        if (born.isPresent()) {
            if (!born.get().amount().equals(request.amount())) {
                throw new Repayments.RepaymentKeyReused();
            }
            return new Prepaid(born.get(), repaymentStore.rescheduledBy(unitOfWork, id, born.get().id())
                    .orElseThrow(() -> new IllegalStateException("a prepayment commits beside its re-schedule")), true);
        }
        if (loan.kind() != LoanKind.INSTALMENT || loan.status() != LoanStatus.ACTIVE) {
            throw new Repayments.LoanNotRepayable(loan.product());
        }
        Money amount = request.amount();
        Money principal = agreement.amount();
        if (!amount.isPositive() || !amount.currency().equals(principal.currency())
                || amount.scale() != principal.scale()) {
            throw new Repayments.AmountInvalid();
        }
        // The database clock, read after the lock, in the agreement's pinned zone.
        Instant now = repaymentStore.databaseNow(unitOfWork);
        LoanTermsStore.LoanTermsVersion version = terms.version(unitOfWork, agreement.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"));
        LoanTerms pinned = version.terms();
        ZoneId zone = ZoneId.of(pinned.servicingZone());
        LocalDate today = LocalDate.ofInstant(now, zone);

        // The servicing step's own catch-up: every elapsed date accrued, everything due billed (each billing followed by
        // the credit-balance application) - its rows, uniques and keys, so the sweep and the prepayment do each once.
        LocalDate accruedThrough = servicing.catchUp(unitOfWork, loan, agreement, pinned, zone, today)
                .orElseThrow(() -> new Repayments.LoanNotRepayable(loan.product()));
        billings.bill(unitOfWork, loan, agreement, pinned, today, accruedThrough);
        // The prepayment's value - and effective - date: today, or on a clock stepped back behind the record the first date
        // it leaves open (the Phase 11 gate's R1); the re-schedule's open period, the agreement's terms and the event are
        // the repayment's date, so the replay re-derives them from it.
        LocalDate valueDate = loans.principalValueDate(unitOfWork, id, today);
        BillableSchedule latest = billingStore.latestSchedule(unitOfWork, id)
                .orElseThrow(() -> new Repayments.LoanNotRepayable(loan.product()));

        // The due state under L3, explained by the ledger; the prepayment's one line (INV-LND-01, INV-LND-04).
        Money zero = Money.ofPersisted(0, loan.currency(), principal.scale());
        LoanReceivables receivables = new LoanReceivables(billingStore, ledgerAccounts, balances);
        Accounts accounts = receivables.accounts(unitOfWork, id, loan.currency());
        DueState dueState = receivables.dueState(unitOfWork, id, accounts, zero);
        AllocationRules rules = AllocationRules.of(pinned);
        if (rules.engine() != agreement.allocationEngine()) {
            throw new IllegalStateException("the pinned terms and the agreement name one allocation engine (INV-LND-09)");
        }
        List<AllocationLine> lines = allocate(amount, dueState, rules);
        Money notDue = dueState.notDuePrincipal();
        if (amount.compareTo(notDue) >= 0) {
            // The whole principal not yet due is the payoff's: the open period's accrued interest is still owed.
            throw new PrepaymentExceedsPrincipal();
        }

        // Schedule version n+1: the remaining principal, the same level instalment, the least term within what is left.
        int unbilled = unbilled(latest, billingStore.billedItems(unitOfWork, id));
        AccrualPeriod open = servicingStore.calendarOf(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a scheduled loan has its calendar"))
                .periodOf(valueDate);
        LoanTerms governing = pinned.toBuilder().nominalAnnualRate(agreement.nominalAnnualRate()).build();
        Money remaining = notDue.minus(amount);
        ScheduleOutcome outcome = ScheduleEngineV1.recalculateAtInstalment(governing, remaining, latest.instalment(),
                unbilled, agreement.repaymentDay(), open.start(), open.endExclusive());
        if (!(outcome instanceof ScheduleOutcome.Projected projected)) {
            throw new IllegalStateException("the schedule engine refuses a schedule for less principal than the one it"
                    + " supersedes: " + ((ScheduleOutcome.Refused) outcome).reason().name());
        }
        Schedule schedule = projected.schedule();

        // (L7) The wallet's ledger row, then what it can spend, judged under it (INV-BAL-04).
        LedgerAccountId wallet = wallets.existingWallet(unitOfWork, party, loan.currency())
                .orElseThrow(Repayments.InsufficientFunds::new);
        LedgerAccount walletRow = ledgerAccounts.lockForUpdate(unitOfWork, wallet)
                .orElseThrow(() -> new IllegalStateException("a resolved wallet has its ledger account row"));
        if (walletRow.status() != LedgerAccountStatus.ACTIVE
                || available.underLock(unitOfWork, wallet).compareTo(amount) < 0) {
            throw new Repayments.InsufficientFunds();
        }

        // (L8) and rule 5 - DR CUSTOMER_WALLET / CR LOAN_PRINCIPAL - through the one posting half; a prepayment never closes.
        Repayments.Repaid repaid = repayments.receive(unitOfWork, loan, agreement, RepaymentKind.PREPAYMENT,
                request.requestKey(), amount, lines, accounts, wallet, valueDate, now, correlation,
                repaymentId -> Optional.empty());
        UUID prepayment = repaid.repayment().id();

        // Agreement version n+1: a prepayment's - everything of version n but the term; its canonical terms format 4.
        int billedInstalments = billedInstalments(billingStore.billedItems(unitOfWork, id));
        int termMonths = billedInstalments + schedule.termMonths();
        LoanOffer origin = offers.ownedBy(unitOfWork, loan.offer(), party)
                .orElseThrow(() -> new IllegalStateException("a loan is born of its own party's offer"));
        int nextVersion = agreement.version() + 1;
        String canonical = CanonicalOfferTerms.renderPrepayment(
                new CanonicalOfferTerms.Content(loan.offer(), origin.application(), agreement.decision(),
                        agreement.termsVersionId(), version.row().version(), governing, agreement.amount(),
                        Optional.of(termMonths), agreement.repaymentDay(), Optional.empty()),
                new CanonicalOfferTerms.Prepayment(prepayment, agreement.version(), agreement.termsSha256Hex(), nextVersion,
                        valueDate, amount, schedule.start(), schedule.periods().get(0).dueDate(), schedule.termMonths(),
                        schedule.principal(), schedule.instalment()));
        LoanAgreement unstamped = agreement.prepaid(LoanAgreementId.next(ids), prepayment, termMonths, canonical, now);
        Instant effective = loans.insertAgreement(unitOfWork, unstamped);
        LoanAgreement prepaidVersion = agreement.prepaid(unstamped.id(), prepayment, termMonths, canonical, effective);

        // Schedule version n+1, from the principal outstanding: billed instalments kept, unbilled superseded.
        RepaymentSchedule next = new RepaymentSchedule(ids.next(), id, latest.version() + 1, prepaidVersion.id(),
                prepaidVersion.version(), schedule);
        schedules.insertSchedule(unitOfWork, next, next.instalments(ids), loan.correlation());

        RepaymentStore.Rescheduled rescheduled = new RepaymentStore.Rescheduled(prepaidVersion.version(), next.version(),
                schedule.termMonths(), schedule.instalment(), schedule.finalInstalment(),
                schedule.periods().get(0).dueDate());
        publish(unitOfWork, loan, agreement, prepaidVersion, prepayment, rescheduled, valueDate, correlation);
        return new Prepaid(repaid.repayment(), rescheduled, false);
    }

    /**
     * {@link AllocationEngineV1#allocatePrepayment} over a due state derived under L3: arrears are {@link ArrearsFirst}, an
     * amount above the principal not yet due {@link PrepaymentExceedsPrincipal} - the customer's to act on; any other
     * refusal, or the engine breaking its assertion, is an {@link AllocationAnomaly} (nothing written).
     */
    static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        try {
            return AllocationEngineV1.allocatePrepayment(amount, dueState, rules);
        } catch (AllocationEngineV1.AllocationRefused refused) {
            switch (refused.reason()) {
                case ARREARS_OUTSTANDING -> throw new ArrearsFirst();
                case PREPAYMENT_EXCEEDS_PRINCIPAL -> throw new PrepaymentExceedsPrincipal();
                default -> throw new AllocationAnomaly("the allocation engine refused a prepayment's own inputs"
                        + " (INV-LND-04)", refused);
            }
        } catch (IllegalStateException defect) {
            throw new AllocationAnomaly("the allocation engine broke its own assertion (INV-LND-04)", defect);
        }
    }

    /** The latest schedule's instalments not yet billed - the most the new schedule may have. */
    private static int unbilled(BillableSchedule latest, List<BilledItem> billed) {
        Set<UUID> billedInstalments = new HashSet<>();
        for (BilledItem item : billed) {
            item.billing().instalment().ifPresent(billedInstalments::add);
        }
        int unbilled = 0;
        for (BillableSchedule.Instalment instalment : latest.instalments()) {
            if (!billedInstalments.contains(instalment.id())) {
                unbilled++;
            }
        }
        return unbilled;
    }

    /** The instalments billed over the loan's life, every schedule version's - the agreement's term counts them. */
    private static int billedInstalments(List<BilledItem> billed) {
        int count = 0;
        for (BilledItem item : billed) {
            if (item.billing().kind() == BillingKind.INSTALMENT) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ the event

    /**
     * {@code lending.LoanAmended} with origin {@code PREPAYMENT} - the prepayment, agreement version n+1 and its hash, the
     * version it followed, the schedule version, the effective date and the remaining instalments; never an amount, a rate
     * or a party.
     */
    private void publish(Connection unitOfWork, Loan loan, LoanAgreement followed, LoanAgreement prepaid, UUID prepayment,
            RepaymentStore.Rescheduled rescheduled, LocalDate today, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("origin", ORIGIN)
                .with("prepaymentId", prepayment.toString())
                .with("agreementVersion", Integer.toString(prepaid.version()))
                .with("amendsVersion", Integer.toString(followed.version()))
                .with("termsSha256", prepaid.termsSha256Hex())
                .with("scheduleVersion", Integer.toString(rescheduled.scheduleVersion()))
                .with("effectiveDate", today.toString())
                .with("remainingInstalments", Integer.toString(rescheduled.termMonths()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        LoanAmendments.AMENDED_EVENT,
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

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** Something billed is still owing - past due or due today: pay it before prepaying. */
    public static final class ArrearsFirst extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ArrearsFirst() {
            super("something billed is still owing");
        }
    }

    /** The amount reaches the principal not yet due - the whole is the payoff's. */
    public static final class PrepaymentExceedsPrincipal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PrepaymentExceedsPrincipal() {
            super("a prepayment is less than the principal not yet due");
        }
    }
}
