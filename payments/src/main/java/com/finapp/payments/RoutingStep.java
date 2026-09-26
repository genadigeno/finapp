package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * One recorded judgement of one candidate rail on a {@link RoutingDecision} (`P7-TSK-003`,
 * ADR-0060 §2/§5), append-only and frozen: rejected before dispatch with its enumerated
 * reason, chosen, or — the fallback's verdict — abandoned on knowledge that nothing was sent.
 *
 * <p>The step also freezes what it judged from: whether the recorded availability said the
 * rail was in service, and the declared capability descriptor's version (absent exactly when
 * the build declared no such rail — {@link RoutingRejection#UNDECLARED_BY_BUILD}).
 */
public record RoutingStep(
        int stepIndex,
        RailId rail,
        RoutingStepVerdict verdict,
        Optional<RoutingRejection> rejection,
        boolean railAvailable,
        Optional<Integer> descriptorVersion) {

    public RoutingStep {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(rejection, "rejection must not be null");
        Objects.requireNonNull(descriptorVersion, "descriptorVersion must not be null");
        if (stepIndex < 0) {
            throw new IllegalArgumentException("a step index is zero-based and never negative");
        }
        if ((verdict == RoutingStepVerdict.CHOSEN) != rejection.isEmpty()) {
            throw new IllegalArgumentException(
                    "a rejection exists exactly on a non-CHOSEN step - a chosen rail was"
                            + " refused nothing, and a refusal names its reason");
        }
        if (verdict == RoutingStepVerdict.ABANDONED
                && rejection.orElseThrow() != RoutingRejection.NOTHING_SENT) {
            throw new IllegalArgumentException(
                    "a rail is abandoned only on knowledge that nothing was sent"
                            + " (INV-RAIL-02): " + rejection.orElseThrow()
                            + " is a pre-dispatch refusal, not an abandonment");
        }
        if (rejection.map(RoutingRejection.UNDECLARED_BY_BUILD::equals).orElse(false)
                != descriptorVersion.isEmpty()) {
            throw new IllegalArgumentException(
                    "the descriptor version exists exactly when the build declares the rail");
        }
    }

    /** The plan's judgement at its position in the decision's trail. */
    static RoutingStep from(int stepIndex, RoutingPlan.PlannedStep planned) {
        return new RoutingStep(
                stepIndex,
                planned.rail(),
                planned.verdict(),
                planned.rejection(),
                planned.railAvailable(),
                planned.descriptorVersion());
    }
}
