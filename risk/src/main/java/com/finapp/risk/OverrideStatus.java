package com.finapp.risk;

/**
 * A decision override's state (`P13-TSK-012`; {@code RISK_LIFECYCLES.md} section 4.2a; {@code risk V007}'s
 * {@code decision_override_status_is_known}, the machine {@code risk V009}'s): {@code PROPOSED → APPROVED | REJECTED},
 * both terminal. The assessment it overrides never changes in any of them ({@code INV-RSK-04}).
 */
public enum OverrideStatus {

    /** Proposed by a {@code RISK_OVERRIDE} holder, awaiting a decision - one per assessment at a time. */
    PROPOSED,

    /** Approved - by a second holder where C7 requires one - its effect issued. */
    APPROVED,

    /** Rejected, or withdrawn by its proposer: the decision stands, nothing was issued. */
    REJECTED
}
