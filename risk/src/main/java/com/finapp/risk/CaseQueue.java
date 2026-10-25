package com.finapp.risk;

import java.util.Arrays;
import java.util.Optional;

/**
 * Which desk works a case (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 3.1 "Escalation"; ADR-0110 decision 2;
 * {@code DESIGN-016} D11) - born {@link #FIRST_LINE}; an escalation names where it goes: {@link #SECOND_LINE},
 * {@link #SENIOR_REVIEW} or {@link #SPECIALIST}, never back to the first line. The queues themselves (oldest first,
 * filtered) are `-018`'s reads; this is the case's routing fact.
 */
public enum CaseQueue {

    /** Where every case is born. */
    FIRST_LINE,

    /** The second line's investigators. */
    SECOND_LINE,

    /** A senior investigator's review. */
    SENIOR_REVIEW,

    /** A specialist desk - a pattern the first two lines do not judge alone. */
    SPECIALIST;

    /** True when an escalation may name this queue - every queue but the one cases are born in. */
    public boolean escalationTarget() {
        return this != FIRST_LINE;
    }

    /** The queue {@code name} names, if any - never a case-insensitive guess. */
    public static Optional<CaseQueue> named(String name) {
        return Arrays.stream(values()).filter(queue -> queue.name().equals(name)).findFirst();
    }
}
