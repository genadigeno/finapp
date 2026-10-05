package com.finapp.crossborder;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The crossborder module's audited acts (`P9-TSK-015`, ADR-0080 section 4, {@code INV-AUD-04}): the
 * corridor policy's proposal, activation and rejection, and the corridor kill switch with its enable
 * proposals. Every one is reasoned - which corridors exist, at what fee and limit, and whether money may
 * move through them are policy, and a policy act without a stated reason is not reviewable.
 */
@RequiredArgsConstructor
public enum CrossborderAuditAction implements AuditableAction {

    /** A whole corridor policy version was proposed - frozen from this moment. */
    CORRIDOR_POLICY_PROPOSED(
            "crossborder.CorridorPolicyProposed",
            "A corridor policy version was proposed by a holder of CROSSBORDER_ADMINISTER, with its reason;"
                    + " nothing is offered by it until a different person activates it.",
            true),

    /** A different person activated a proposed version, retiring its predecessor. */
    CORRIDOR_POLICY_ACTIVATED(
            "crossborder.CorridorPolicyActivated",
            "A corridor policy version was activated by someone other than its proposer (INV-AUD-04),"
                    + " retiring its predecessor in the same transaction.",
            true),

    /** A proposed version was rejected - or withdrawn by its proposer. */
    CORRIDOR_POLICY_REJECTED(
            "crossborder.CorridorPolicyRejected",
            "A proposed corridor policy version was rejected, or withdrawn by its proposer; no corridor"
                    + " changed.",
            true),

    /** A corridor was disabled - one person, at once. */
    CORRIDOR_DISABLED(
            "crossborder.CorridorDisabled",
            "A corridor was disabled by one holder of CROSSBORDER_ADMINISTER with a reason, at once:"
                    + " stopping money never waits for a second person.",
            true),

    /** Enabling a corridor was proposed. */
    CORRIDOR_ENABLE_PROPOSED(
            "crossborder.CorridorEnableProposed",
            "Enabling a corridor was proposed; nothing is enabled until a different person approves.",
            true),

    /** A different person approved an enabling; the enabling fact was appended. */
    CORRIDOR_ENABLED(
            "crossborder.CorridorEnabled",
            "A corridor enable proposal was approved by someone other than its proposer (INV-AUD-04)"
                    + " and the corridor made available in the same transaction.",
            true),

    /** An enable proposal was rejected - or withdrawn by its proposer. */
    CORRIDOR_ENABLE_REJECTED(
            "crossborder.CorridorEnableRejected",
            "A corridor enable proposal was rejected, or withdrawn by its proposer; the corridor stays"
                    + " unavailable.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
