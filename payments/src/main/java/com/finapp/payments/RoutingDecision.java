package com.finapp.payments;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pinned routing decision of one payment (`P7-TSK-003`, ADR-0060 §2): the policy version
 * that produced it ({@code INV-HIST-04}), the inputs it was judged from, every candidate's
 * step, and the chosen rail — or no rail, which is the recorded refusal behind
 * {@code payments.NoEligibleRail}. Committed in the confirmation's Tx1 beside the attempt it
 * governs, frozen by trigger for every writer.
 *
 * <h2>Advance is append, and only on knowledge</h2>
 *
 * <p>{@link #abandonedOnNothingSent} appends the fallback's one lawful verdict: the chosen
 * rail's adapter reported {@code NOTHING_SENT} — a refused connection before anything left —
 * so the abandonment is knowledge, never ambiguity (`INV-RAIL-02`; an {@code *_UNKNOWN}
 * attempt stays on its rail, {@code INV-LIFE-03}, and no door here can say otherwise). The
 * chosen-rail column keeps the rail that WAS dispatched — the historical fact the attempt
 * row shares — and the step trail says it was abandoned. A later advance to a further
 * candidate arrives with the rails that can carry one (`P7-TSK-006`, `-009`); this task
 * ships the trail it will append to.
 *
 * <p><strong>The sequence rule lives here, not in a step {@code CHECK}</strong>: at most one
 * chosen step is un-abandoned. The database rank for "dispatched on at most one rail at a
 * time" is the attempt table's one-live index (`V003`/`V012`), which this decision's steps
 * explain rather than duplicate.
 *
 * <p>A class rather than a record: the judged amount is {@code RESTRICTED-FINANCIAL}
 * ({@code INV-AUD-02}) — the {@code PaymentIntent} form.
 */
public final class RoutingDecision {

    private final RoutingDecisionId id;
    private final RoutingSubject subject;
    private final RoutingPolicyVersionId policyVersionId;
    private final PaymentDirection direction;
    private final InstrumentKind instrumentKind;
    private final Money amount;
    private final Optional<Integer> matchedRuleIndex;
    private final Optional<RailId> chosenRail;
    private final List<RoutingStep> steps;
    private final Instant createdAt;
    private Optional<com.finapp.sharedkernel.money.CountryCode> destinationCountry = Optional.empty();
    private Optional<java.util.Set<RailId>> reachableRails = Optional.empty();

    private RoutingDecision(
            RoutingDecisionId id,
            RoutingSubject subject,
            RoutingPolicyVersionId policyVersionId,
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Money amount,
            Optional<Integer> matchedRuleIndex,
            Optional<RailId> chosenRail,
            List<RoutingStep> steps,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.subject = Objects.requireNonNull(subject, "subject must not be null");
        this.policyVersionId = Objects.requireNonNull(
                policyVersionId,
                "policyVersionId must not be null - the pin is the decision's defence"
                        + " (INV-HIST-04)");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.instrumentKind =
                Objects.requireNonNull(instrumentKind, "instrumentKind must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.matchedRuleIndex =
                Objects.requireNonNull(matchedRuleIndex, "matchedRuleIndex must not be null");
        this.chosenRail = Objects.requireNonNull(chosenRail, "chosenRail must not be null");
        this.steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");

        for (int index = 0; index < this.steps.size(); index++) {
            if (this.steps.get(index).stepIndex() != index) {
                throw new IllegalArgumentException(
                        "step indexes are contiguous from 0 in trail order - position " + index
                                + " holds index " + this.steps.get(index).stepIndex());
            }
        }
        if (matchedRuleIndex.isEmpty() && !this.steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "steps exist only under a matched rule: they judge its candidates");
        }
        Optional<RailId> chosenInTrail = this.steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                .map(RoutingStep::rail)
                .findFirst();
        if (!chosenInTrail.equals(chosenRail)) {
            throw new IllegalArgumentException(
                    "the chosen rail is the trail's own CHOSEN step, denormalised - "
                            + chosenRail + " against " + chosenInTrail + " is a corrupt row");
        }
        long chosen = this.steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                .count();
        long abandoned = this.steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.ABANDONED)
                .count();
        if (abandoned > chosen) {
            throw new IllegalArgumentException(
                    "an abandonment abandons a choice: more abandoned steps than chosen ones"
                            + " is a trail no lawful sequence writes");
        }
        if (chosen - abandoned > 1) {
            throw new IllegalArgumentException(
                    "at most one chosen step is un-abandoned: a payment is dispatched on at"
                            + " most one rail at a time (INV-RAIL-02)");
        }
    }

    /** The decision as the plan judged it, frozen with the inputs it was judged from. */
    public static RoutingDecision create(
            IdGenerator ids,
            Clock clock,
            PaymentIntentId intentId,
            RoutingPolicyVersionId policyVersionId,
            RoutingInputs inputs,
            RoutingPlan plan) {
        return create(
                ids, clock, RoutingSubject.ofIntent(intentId), policyVersionId, inputs, plan);
    }

    /** The subject-general form (`P7-TSK-008`): the withdrawal's dispatch pins here too. */
    public static RoutingDecision create(
            IdGenerator ids,
            Clock clock,
            RoutingSubject subject,
            RoutingPolicyVersionId policyVersionId,
            RoutingInputs inputs,
            RoutingPlan plan) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        List<RoutingStep> steps = new ArrayList<>();
        for (int index = 0; index < plan.steps().size(); index++) {
            steps.add(RoutingStep.from(index, plan.steps().get(index)));
        }
        RoutingDecision decision = new RoutingDecision(
                RoutingDecisionId.next(ids),
                subject,
                policyVersionId,
                inputs.direction(),
                inputs.instrumentKind(),
                inputs.amount(),
                plan.matchedRuleIndex(),
                plan.chosen(),
                steps,
                Instant.now(clock));
        decision.destinationCountry = inputs.destinationCountry();
        decision.reachableRails = inputs.reachableRails();
        return decision;
    }

    /** A decision read back from storage, through the same constructor. */
    public static RoutingDecision rehydrate(
            RoutingDecisionId id,
            RoutingSubject subject,
            RoutingPolicyVersionId policyVersionId,
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Money amount,
            Optional<Integer> matchedRuleIndex,
            Optional<RailId> chosenRail,
            List<RoutingStep> steps,
            Instant createdAt) {
        return new RoutingDecision(
                id, subject, policyVersionId, direction, instrumentKind, amount,
                matchedRuleIndex, chosenRail, steps, createdAt);
    }

    /**
     * The fallback's one lawful append (`INV-RAIL-02`): the chosen rail's dispatch met a
     * refused connection — {@code NOTHING_SENT}, knowledge that nothing left the platform.
     */
    /**
     * A decision with its third subject's inputs (`P9-TSK-019`): the destination country and the rails that
     * reach it, stored so the decision recomputes.
     */
    public static RoutingDecision rehydrate(
            RoutingDecisionId id,
            RoutingSubject subject,
            RoutingPolicyVersionId policyVersionId,
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Money amount,
            Optional<Integer> matchedRuleIndex,
            Optional<RailId> chosenRail,
            List<RoutingStep> steps,
            Instant createdAt,
            Optional<com.finapp.sharedkernel.money.CountryCode> destinationCountry,
            Optional<java.util.Set<RailId>> reachableRails) {
        RoutingDecision decision = rehydrate(id, subject, policyVersionId, direction, instrumentKind, amount,
                matchedRuleIndex, chosenRail, steps, createdAt);
        decision.destinationCountry = Objects.requireNonNull(destinationCountry, "destinationCountry must not be null");
        decision.reachableRails = Objects.requireNonNull(reachableRails, "reachableRails must not be null");
        return decision;
    }

    public Optional<com.finapp.sharedkernel.money.CountryCode> destinationCountry() {
        return destinationCountry;
    }

    public Optional<java.util.Set<RailId>> reachableRails() {
        return reachableRails;
    }

    public RoutingDecision abandonedOnNothingSent() {
        RailId dispatched = chosenRail.orElseThrow(
                () -> new IllegalStateException(
                        "nothing was chosen, so nothing can be abandoned: this decision"
                                + " refused the payment before dispatch"));
        long alreadyAbandoned = steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.ABANDONED)
                .count();
        long chosen = steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                .count();
        if (alreadyAbandoned >= chosen) {
            throw new IllegalStateException(
                    "the choice is already abandoned: one dispatch, one abandonment - a"
                            + " second would claim knowledge of a send that never happened");
        }
        List<RoutingStep> extended = new ArrayList<>(steps);
        extended.add(new RoutingStep(
                steps.size(),
                dispatched,
                RoutingStepVerdict.ABANDONED,
                Optional.of(RoutingRejection.NOTHING_SENT),
                steps.stream()
                        .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .railAvailable(),
                steps.stream()
                        .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .descriptorVersion()));
        RoutingDecision abandoned = new RoutingDecision(
                id, subject, policyVersionId, direction, instrumentKind, amount,
                matchedRuleIndex, chosenRail, extended, createdAt);
        abandoned.destinationCountry = destinationCountry;
        abandoned.reachableRails = reachableRails;
        return abandoned;
    }

    public RoutingDecisionId id() {
        return id;
    }

    /** The decision's one subject: the confirmed intent, or the dispatched withdrawal. */
    public RoutingSubject subject() {
        return subject;
    }

    /** The intent arm's convenience — the confirmation's callers predate the second
     * subject. A withdrawal's decision has no intent, loudly. */
    public PaymentIntentId intentId() {
        return subject.intent()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a withdrawal's routing decision has no intent;"
                                                + " read subject()"));
    }

    /** The pinned version — {@code INV-HIST-04}'s routing element. */
    public RoutingPolicyVersionId policyVersionId() {
        return policyVersionId;
    }

    public PaymentDirection direction() {
        return direction;
    }

    public InstrumentKind instrumentKind() {
        return instrumentKind;
    }

    /** The judged amount, recorded verbatim so the decision recomputes. */
    public Money amount() {
        return amount;
    }

    /** Which rule matched, or empty when the version had no rule for this payment's shape. */
    public Optional<Integer> matchedRuleIndex() {
        return matchedRuleIndex;
    }

    /** The rail this payment was dispatched on, or empty for the recorded refusal. */
    public Optional<RailId> chosenRail() {
        return chosenRail;
    }

    /** The trail, in judgement order — the explanation, step by step. */
    public List<RoutingStep> steps() {
        return steps;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
