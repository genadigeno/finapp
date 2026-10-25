package com.finapp.risk;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The risk action's machine (`P13-TSK-017`; {@code PHASE_13_PLAN.md} section 5; {@code RISK_LIFECYCLES.md} section 4.7;
 * ADR-0112 decisions 1, 4-6): {@code PROPOSED → APPROVED} (a second person) {@code | REJECTED};
 * {@code APPROVED → DISPATCHED} (T-act's claim) {@code → DISPATCHED} (a re-claim past the lease, the same key)
 * {@code | DONE | FAILED} (the owning domain's answer, recorded once). Nothing moves out of {@code DONE}, {@code FAILED}
 * or {@code REJECTED}: a failed action is <strong>re-proposable</strong> - a new action, four eyes again - and never
 * re-dispatched by itself.
 */
public enum ActionStatus {

    PROPOSED,

    APPROVED,

    DISPATCHED,

    DONE,

    FAILED,

    REJECTED;

    /** The edges out of this state. */
    public Set<ActionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(DISPATCHED);
            case DISPATCHED -> EnumSet.of(DISPATCHED, DONE, FAILED);
            case DONE, FAILED, REJECTED -> EnumSet.noneOf(ActionStatus.class);
        };
    }

    public boolean canTransitionTo(ActionStatus target) {
        return permittedTransitions().contains(target);
    }

    /** True while the action holds {@code risk_action_one_live}: proposed, approved, or dispatched awaiting its record. */
    public boolean live() {
        return this == PROPOSED || this == APPROVED || this == DISPATCHED;
    }

    /** True for a state nothing moves out of. */
    public boolean terminal() {
        return permittedTransitions().isEmpty();
    }

    /** True while the action carries a permit - approved, or dispatched awaiting its record. */
    public boolean permitted() {
        return this == APPROVED || this == DISPATCHED;
    }

    /** The vocabulary as a SQL list - {@code V013}'s CHECK is held to it. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
