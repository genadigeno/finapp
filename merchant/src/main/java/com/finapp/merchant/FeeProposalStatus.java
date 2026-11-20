package com.finapp.merchant;

import java.util.EnumSet;
import java.util.Set;

/**
 * A fee schedule version proposal's and a merchant fee assignment proposal's machine (`P17-TSK-123`, ADR-0154's shape):
 * {@code PROPOSED → APPROVED | REJECTED}, decided once. Merchant V022's status {@code CHECK}s are {@link #sqlValueList()}.
 */
public enum FeeProposalStatus {
    PROPOSED, APPROVED, REJECTED;

    /** The edges out of this status: two from {@code PROPOSED}, none from a decision. */
    public Set<FeeProposalStatus> permittedTransitions() {
        return this == PROPOSED ? EnumSet.of(APPROVED, REJECTED) : EnumSet.noneOf(FeeProposalStatus.class);
    }

    public static String sqlValueList() {
        return "'PROPOSED', 'APPROVED', 'REJECTED'";
    }
}
