package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The resolution's machine (`P8-TSK-012`, ADR-0071 §2) — stated whole; `P8-TSK-012`
 * produces only the born-{@code APPROVED} platform edge ({@code EVIDENCED}), and `V006`'s
 * {@code CHECK} admits only {@code APPROVED} (the plan's §8 slicing; `V007` regenerates
 * the widened machine when `P8-TSK-015` brings the person edges).
 */
public enum ResolutionStatus {

    /** A person's proposal awaiting its second person (`P8-TSK-015`). */
    PROPOSED,

    /** Decided and effective — terminal. {@code EVIDENCED} is born here. */
    APPROVED,

    /** Refused by another RESOLVE holder, reasoned — terminal (`-015`). */
    REJECTED,

    /** Taken back by its proposer, or by the platform on evidence — terminal (`-015`). */
    WITHDRAWN;

    /** The states reachable from this one — `V007`'s trigger edges regenerate from it. */
    public Set<ResolutionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(APPROVED, REJECTED, WITHDRAWN);
            case APPROVED, REJECTED, WITHDRAWN -> EnumSet.noneOf(ResolutionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** `V007`'s transition trigger's edge condition — reconciled by its migration test. */
    public static String sqlTransitionRule() {
        return Arrays.stream(values())
                .filter(from -> !from.permittedTransitions().isEmpty())
                .map(
                        from ->
                                "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                                        + from.permittedTransitions().stream()
                                                .map(to -> "'" + to.name() + "'")
                                                .collect(Collectors.joining(", "))
                                        + "))")
                .collect(Collectors.joining(" OR "));
    }

    /** The value list `V007` regenerates into the widened {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
