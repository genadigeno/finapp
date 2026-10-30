package com.finapp.accounting;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A GL mapping version's machine (`P14-TSK-003`; {@code PHASE_14_PLAN.md} section 6; ADR-0113 decision 3;
 * {@code INV-ACC-07}): {@code PROPOSED -> ACTIVE -> RETIRED}, {@code PROPOSED -> REJECTED}. {@code accounting V002}'s
 * edge trigger refuses every other move for every writer.
 */
public enum GlMappingVersionStatus {

    /** Proposed by an accountant; awaits a different person's activation, or a rejection. */
    PROPOSED,

    /** In force from its effective month - at most one at a time. */
    ACTIVE,

    /** Superseded: retired only beside its later successor's activation, ending where the successor begins. Terminal. */
    RETIRED,

    /** Rejected. Terminal; never in force. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<GlMappingVersionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            case RETIRED, REJECTED -> EnumSet.noneOf(GlMappingVersionStatus.class);
        };
    }

    /** The value list {@code accounting V002}'s status {@code CHECK} names. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
