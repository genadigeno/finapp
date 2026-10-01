package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A rule set version's machine (`P8-TSK-022`, ADR-0068 §8, `V012`): proposed by one holder of
 * {@code RECONCILIATION_ADMINISTER}, activated by another — retiring its predecessor in the same
 * transaction, so a source always has exactly one active version — or rejected. Retired and
 * rejected are terminal. Content is frozen from {@code PROPOSED} by trigger.
 */
public enum RuleSetStatus {
    PROPOSED,
    ACTIVE,
    RETIRED,
    REJECTED;

    private static final Map<RuleSetStatus, EnumSet<RuleSetStatus>> EDGES =
            Map.of(
                    PROPOSED, EnumSet.of(ACTIVE, REJECTED),
                    ACTIVE, EnumSet.of(RETIRED),
                    RETIRED, EnumSet.noneOf(RuleSetStatus.class),
                    REJECTED, EnumSet.noneOf(RuleSetStatus.class));

    public Set<RuleSetStatus> permittedTransitions() {
        return EnumSet.copyOf(EDGES.get(this));
    }

    public boolean canMoveTo(RuleSetStatus target) {
        return EDGES.get(this).contains(target);
    }

    /** The `V012` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
