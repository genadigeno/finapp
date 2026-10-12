package com.finapp.lending;

import com.finapp.ledger.HoldService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The external payout's steps, path X (`P11-TSK-015`; {@code PHASE_11_PLAN.md} sections 7.1 T-pyo and T-pyr, 7.3 (d),
 * 12.4; {@code LENDING_LIFECYCLES.md} section 4.7; ADR-0097 points 4-9; {@code INV-LND-07}, {@code INV-RAIL-02},
 * {@code INV-RAIL-03}, {@code INV-LIFE-03}). The loan was born financially in T-dis; here only its funds move on - through
 * payments' ambiguity-safe withdrawal, which lending <em>instructs</em> and then <em>reads</em>. The platform, not the
 * borrower, bears the provider's ambiguity: interest starts at the payout's terminal outcome (A30).
 *
 * <h2>T-pyo - three parts, never one transaction around a provider call (ADR-0046)</h2>
 *
 * <ol>
 *   <li>(a) the sweep's claim {@code PENDING -> DISPATCHING} with a database-stamped permit, <strong>committed</strong>
 *       ({@link #claim}); a lapsed permit re-claims the same row, and the permit is each claim's token;
 *   <li>(b) {@link LoanPayouts#dispatch} with no lending transaction open and no lending lock held - payments' own Tx1,
 *       wire and Tx2 under {@code payments.withdrawal:lending/<loan>}, adopting the disbursement's hold;
 *   <li>(c) <strong>(L3)</strong> the loan, <strong>(L5)</strong> the payout - acted on only while {@code DISPATCHING}
 *       on this claim's permit: {@code DISPATCHED} naming payments' withdrawal; or, payments having refused or found no
 *       route, {@code NOT_DISPATCHED}, <strong>(L7)</strong> the hold released (the funds free in the wallet) and the
 *       payout concluded. A crash anywhere re-drives under the same instruction, and payments replays.
 * </ol>
 *
 * <h2>T-pyr - one transaction</h2>
 *
 * <p><strong>(L3)</strong> the loan, <strong>(L5)</strong> the payout {@code DISPATCHED}, payments' outcome read plainly
 * ({@link LoanPayouts#outcome}, never locked): undecided ({@code DISPATCHED} or {@code UNKNOWN} at payments) - an
 * observation stamped, nothing sent, never a re-dispatch (scenario 5); concluded - {@code PAID_OUT} or {@code FAILED}
 * with payments' conclusion instant, the accrual start its business date in the pinned zone, schedule version 1 from it
 * and {@code lending.LoanPayoutConcluded}, together (a deferred check holds the outcome to its schedule).
 *
 * <p>No transaction here holds a lending lock while waiting on a payments row, and payments never locks a lending row: the
 * order is the loan, the payout, then (only on {@code NOT_DISPATCHED}) the wallet and its projections - every wallet
 * writer's order. A payout outcome never touches the receivable ({@code INV-LND-07}).
 */
@RequiredArgsConstructor
public final class LoanPayoutSteps {

    public static final String CONCLUDED_EVENT = "lending.LoanPayoutConcluded";

    @NonNull private final LoanStore loans;
    @NonNull private final LoanPayoutStore payouts;
    @NonNull private final LoanDisbursementStore disbursements;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LoanPayouts payments;
    @NonNull private final HoldService holds;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What one payout step did - for the meter; never a figure. */
    public record Outcome(Kind kind, Optional<NotDispatchedReason> reason) {

        public Outcome {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        static Outcome of(Kind kind) {
            return new Outcome(kind, Optional.empty());
        }

        /** The step's effect. */
        public enum Kind {
            /** Nothing to do: another step moved the payout, or this claim's permit was overtaken. */
            NOTHING,
            /** Payments gave no answer now; nothing written - the permit lapses and a later sweep re-drives. */
            RETRYING,
            /** Payments took the withdrawal: the payout {@code DISPATCHED}. */
            DISPATCHED,
            /** Payments has not concluded the withdrawal: observed, nothing sent. */
            AWAITING,
            /** Concluded {@code PAID_OUT}: interest starts. */
            PAID_OUT,
            /** Concluded {@code FAILED}: the funds back in the wallet by payments; interest starts. */
            FAILED,
            /** Payments refused or could not route it: the hold released; interest starts. */
            NOT_DISPATCHED
        }
    }

    // ------------------------------------------------------------------ (a) the claim

    /** The payouts due a step - see {@link LoanPayoutStore#claimDue}. Commit before stepping them. */
    public List<LoanPayoutStore.Claimed> claim(Connection unitOfWork, int limit, Duration permit) {
        return payouts.claimDue(Objects.requireNonNull(unitOfWork, "unitOfWork"), limit,
                Objects.requireNonNull(permit, "permit"));
    }

    // ------------------------------------------------------------------ the step

    /**
     * One claimed payout's step, each part a transaction of its own through {@code transactions}: T-pyo for a
     * {@code DISPATCHING} claim - and, when payments took the withdrawal, T-pyr at once (a synchronous answer concludes
     * without waiting a sweep); T-pyr for a {@code DISPATCHED} one. Answers every effect, in order.
     */
    public List<Outcome> step(LendingUnitOfWork transactions, LoanPayoutStore.Claimed claimed, Actor actor) {
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(actor, "actor");
        List<Outcome> effects = new ArrayList<>();
        switch (claimed.status()) {
            case DISPATCHING -> {
                Outcome dispatched = dispatch(transactions, claimed, actor);
                effects.add(dispatched);
                if (dispatched.kind() == Outcome.Kind.DISPATCHED) {
                    effects.add(transactions.inTransaction(uow -> observe(uow, claimed.loan(), actor)));
                }
            }
            case DISPATCHED -> effects.add(transactions.inTransaction(uow -> observe(uow, claimed.loan(), actor)));
            default -> effects.add(Outcome.of(Outcome.Kind.NOTHING));
        }
        return effects;
    }

    // ------------------------------------------------------------------ T-pyo (b) and (c)

    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    private Outcome dispatch(LendingUnitOfWork transactions, LoanPayoutStore.Claimed claimed, Actor actor) {
        Optional<LoanPayouts.Instruction> instruction =
                transactions.inTransaction(uow -> instruction(uow, claimed));
        if (instruction.isEmpty()) {
            return Outcome.of(Outcome.Kind.NOTHING);
        }
        LoanPayouts.Dispatch answer;
        // (b) No lending transaction is open here: payments runs its own (ADR-0046, section 7.3 (d)).
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow(instruction.get().correlation()))) {
            answer = payments.dispatch(instruction.get());
        }
        return transactions.inTransaction(uow -> recordDispatch(uow, claimed, answer, actor));
    }

    /**
     * The claimed payout's instruction, read unlocked - empty unless it is still {@code DISPATCHING} on this claim's
     * permit. The amount is the disbursement's: the agreement's principal less the pinned origination fee - the hold's,
     * which payments' adoption re-judges under the wallet's lock.
     */
    Optional<LoanPayouts.Instruction> instruction(Connection unitOfWork, LoanPayoutStore.Claimed claimed) {
        Optional<LoanPayout> found = payouts.find(unitOfWork, claimed.loan());
        if (found.isEmpty() || found.get().status() != PayoutStatus.DISPATCHING
                || !found.get().permit().equals(claimed.permit())) {
            return Optional.empty();
        }
        LoanPayout payout = found.get();
        UUID party = loans.partyOf(unitOfWork, claimed.loan())
                .orElseThrow(() -> new IllegalStateException("a payout's loan exists"));
        LoanStore.Owned owned = loans.ownedBy(unitOfWork, claimed.loan(), party)
                .orElseThrow(() -> new IllegalStateException("a payout's loan is its party's"));
        Money principal = owned.agreement().amount();
        Money net = principal.minus(pinned(unitOfWork, owned).originationFee().on(principal));
        return Optional.of(new LoanPayouts.Instruction(claimed.loan(), party, payout.paymentMethod(), payout.wallet(),
                payout.hold(), net, owned.loan().correlation()));
    }

    /**
     * T-pyo (c): payments' answer recorded under (L3) and (L5), on this claim's permit only - a step whose permit lapsed
     * and was re-claimed writes nothing, so a slow dispatcher cannot undo a faster one.
     */
    @SuppressWarnings("try") // The correlation Scope is used for its close side effect (the idiom).
    Outcome recordDispatch(Connection unitOfWork, LoanPayoutStore.Claimed claimed, LoanPayouts.Dispatch answer,
            Actor actor) {
        if (answer instanceof LoanPayouts.Dispatch.NotNow) {
            return Outcome.of(Outcome.Kind.RETRYING);
        }
        // (L3) The loan; (L5) its payout.
        Optional<LoanStore.Owned> owned = loans.lock(unitOfWork, claimed.loan());
        Optional<LoanPayout> locked = payouts.lock(unitOfWork, claimed.loan());
        if (owned.isEmpty() || locked.isEmpty() || locked.get().status() != PayoutStatus.DISPATCHING
                || !locked.get().permit().equals(claimed.permit())) {
            return Outcome.of(Outcome.Kind.NOTHING);
        }
        if (answer instanceof LoanPayouts.Dispatch.Accepted accepted) {
            if (!payouts.dispatched(unitOfWork, claimed.loan(), claimed.permit(), accepted.withdrawal())) {
                throw new IllegalStateException("the locked payout moved under its own row lock");
            }
            return Outcome.of(Outcome.Kind.DISPATCHED);
        }
        NotDispatchedReason reason = ((LoanPayouts.Dispatch.Refused) answer).reason();
        Instant concludedAt = payouts.notDispatched(unitOfWork, claimed.loan(), claimed.permit(), reason)
                .orElseThrow(() -> new IllegalStateException("the locked payout moved under its own row lock"));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow(owned.get().loan().correlation()))) {
            // (L7) The hold released: nothing left the platform, the funds are the borrower's to use at once.
            holds.release(unitOfWork, locked.get().hold())
                    .orElseThrow(() -> new IllegalStateException("a payout's hold is never deleted"));
        }
        conclude(unitOfWork, owned.get(), locked.get(), PayoutStatus.NOT_DISPATCHED, concludedAt, Optional.of(reason));
        return new Outcome(Outcome.Kind.NOT_DISPATCHED, Optional.of(reason));
    }

    // ------------------------------------------------------------------ T-pyr

    /**
     * T-pyr: (L3) the loan, (L5) the payout {@code DISPATCHED}, payments' word read plainly. Never a re-dispatch: an
     * undecided withdrawal - {@code UNKNOWN} at payments after a lost answer included - is observed and left to payments'
     * own authenticated inquiry (scenario 5, {@code INV-RAIL-02}).
     */
    public Outcome observe(Connection unitOfWork, LoanId loan, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(actor, "actor");
        Optional<LoanStore.Owned> owned = loans.lock(unitOfWork, loan);
        Optional<LoanPayout> locked = payouts.lock(unitOfWork, loan);
        if (owned.isEmpty() || locked.isEmpty() || locked.get().status() != PayoutStatus.DISPATCHED) {
            return Outcome.of(Outcome.Kind.NOTHING);
        }
        LoanPayout payout = locked.get();
        LoanPayouts.Outcome word = payments.outcome(unitOfWork, loan, payout.withdrawal()
                .orElseThrow(() -> new IllegalStateException("a DISPATCHED payout names its withdrawal")));
        if (word instanceof LoanPayouts.Outcome.Concluded concluded) {
            Instant at = payouts.concluded(unitOfWork, loan, concluded.status(), concluded.at())
                    .orElseThrow(() -> new IllegalStateException("the locked payout moved under its own row lock"));
            conclude(unitOfWork, owned.get(), payout, concluded.status(), at, Optional.empty());
            return Outcome.of(concluded.status() == PayoutStatus.PAID_OUT ? Outcome.Kind.PAID_OUT : Outcome.Kind.FAILED);
        }
        if (!payouts.observed(unitOfWork, loan)) {
            throw new IllegalStateException("the locked payout moved under its own row lock");
        }
        return Outcome.of(Outcome.Kind.AWAITING);
    }

    // ------------------------------------------------------------------ the conclusion

    /**
     * The terminal outcome's facts, in its transaction (A30): the accrual start - the business date of the conclusion in
     * the pinned servicing zone - schedule version 1 projected from it by the pinned engine, and
     * {@code lending.LoanPayoutConcluded}. The receivable is untouched ({@code INV-LND-07}).
     */
    private void conclude(Connection unitOfWork, LoanStore.Owned owned, LoanPayout payout, PayoutStatus outcome,
            Instant concludedAt, Optional<NotDispatchedReason> reason) {
        Loan loan = owned.loan();
        LoanAgreement agreement = owned.agreement();
        LoanTerms pinned = pinned(unitOfWork, owned);
        LocalDate start = LocalDate.ofInstant(concludedAt, ZoneId.of(pinned.servicingZone()));
        disbursements.insertAccrualStart(unitOfWork, ids.next(), AccrualStart.ofPayout(loan.id(), start),
                loan.correlation());
        RepaymentSchedule versionOne = new RepaymentSchedule(ids.next(), loan.id(), 1, agreement.id(),
                agreement.version(), LoanDisbursements.schedule(pinned, agreement, start));
        disbursements.insertSchedule(unitOfWork, versionOne, versionOne.instalments(ids), loan.correlation());
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("product", loan.product().name())
                .with("outcome", outcome.name())
                .with("accrualStart", start.toString());
        if (payout.withdrawal().isPresent()) {
            payload = payload.with("withdrawalId", payout.withdrawal().get().toString());
        }
        if (reason.isPresent()) {
            payload = payload.with("reason", reason.get().name());
        }
        CorrelationId correlation = loan.correlation();
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        CONCLUDED_EVENT,
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

    private LoanTerms pinned(Connection unitOfWork, LoanStore.Owned owned) {
        return terms.version(unitOfWork, owned.agreement().termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an agreement pins a terms version that is never deleted"))
                .terms();
    }

    /** The acceptance's flow, carried to payments and the ledger: one correlation from the acceptance to the money. */
    private static Correlation flow(CorrelationId correlation) {
        return Correlation.startingWith(correlation).causing(CausationId.of(correlation.value()));
    }
}
