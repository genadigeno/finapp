package com.finapp.payments;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pure outcome of judging one policy version over one payment's inputs (`P7-TSK-003`,
 * ADR-0060 §2): which rule matched (or none), every candidate judged in order with its
 * verdict, and — derivably — the chosen rail. {@link RoutingPolicyVersion#decide} returns
 * this and nothing else touches I/O, which is what the recomputation test replays: the same
 * version over the same stored inputs must reproduce this plan exactly (`INV-RAIL-02`).
 *
 * <p>Candidates after the chosen one are deliberately absent: the first eligible candidate
 * is chosen and nothing was decided about the rest, and a recorded judgement that never
 * happened would be a false row.
 */
public record RoutingPlan(Optional<Integer> matchedRuleIndex, List<PlannedStep> steps) {

    /** One candidate's judgement — the row shape a {@link RoutingDecision} step freezes. */
    public record PlannedStep(
            RailId rail,
            RoutingStepVerdict verdict,
            Optional<RoutingRejection> rejection,
            boolean railAvailable,
            Optional<Integer> descriptorVersion) {

        public PlannedStep {
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(rejection, "rejection must not be null");
            Objects.requireNonNull(descriptorVersion, "descriptorVersion must not be null");
            if ((verdict == RoutingStepVerdict.CHOSEN) != rejection.isEmpty()) {
                throw new IllegalArgumentException(
                        "a rejection exists exactly on a non-CHOSEN step - a chosen rail was"
                                + " refused nothing, and a refusal names its reason");
            }
            if (rejection.map(RoutingRejection.UNDECLARED_BY_BUILD::equals).orElse(false)
                    != descriptorVersion.isEmpty()) {
                throw new IllegalArgumentException(
                        "the descriptor version exists exactly when the build declares the"
                                + " rail - an undeclared candidate has none to record");
            }
        }
    }

    public RoutingPlan {
        Objects.requireNonNull(matchedRuleIndex, "matchedRuleIndex must not be null");
        Objects.requireNonNull(steps, "steps must not be null");
        steps = List.copyOf(steps);
        if (matchedRuleIndex.isEmpty() && !steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "steps exist only under a matched rule: they judge its candidates");
        }
        long chosen = steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                .count();
        if (chosen > 1) {
            throw new IllegalArgumentException(
                    "at most one step is CHOSEN: a payment is dispatched on at most one rail"
                            + " at a time (INV-RAIL-02)");
        }
    }

    /** The chosen rail, or empty when no candidate was eligible (or no rule matched). */
    public Optional<RailId> chosen() {
        return steps.stream()
                .filter(step -> step.verdict() == RoutingStepVerdict.CHOSEN)
                .map(PlannedStep::rail)
                .findFirst();
    }
}
