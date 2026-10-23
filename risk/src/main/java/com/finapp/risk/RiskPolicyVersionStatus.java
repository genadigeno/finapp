package com.finapp.risk;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A risk policy version's machine (`P13-TSK-002`; ADR-0109 decision 5, {@code RISK_LIFECYCLES.md} section 4.1):
 * {@code PROPOSED -> ACTIVE -> RETIRED}, {@code PROPOSED -> REJECTED} - bnpl's terms machine
 * ({@code BnplTermsVersionStatus}), risk's own. {@code risk V002}'s edge trigger and the history's edge {@code CHECK} are
 * this machine's every-writer ranks.
 */
public enum RiskPolicyVersionStatus {

    /** Proposed by a holder of {@code RISK_POLICY_ADMINISTER}; awaits a different holder's activation, or anyone's rejection. */
    PROPOSED,

    /** In force for its operation - at most one per operation, from its effective start. The only version evaluated. */
    ACTIVE,

    /** Superseded: retired only beside its later successor's activation, in the same transaction. Terminal. */
    RETIRED,

    /** Rejected - or withdrawn by its proposer. Terminal; never in force. */
    REJECTED;

    /** The states reachable from this one. */
    public Set<RiskPolicyVersionStatus> permittedTransitions() {
        return switch (this) {
            case PROPOSED -> EnumSet.of(ACTIVE, REJECTED);
            case ACTIVE -> EnumSet.of(RETIRED);
            case RETIRED, REJECTED -> EnumSet.noneOf(RiskPolicyVersionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** True once a person has decided the version: its content is frozen for every role ({@code INV-RSK-05}). */
    public boolean isDecided() {
        return this != PROPOSED;
    }

    /** The value list {@code risk V002}'s status {@code CHECK} names. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
