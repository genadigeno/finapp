package com.finapp.consent;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code consent} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally: actions belong to the module that performs them,
 * and {@code platform} sits below every business module. See {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Two actions, and they mirror the two facts the module records.</strong> ADR-0037
 * makes grants and withdrawals immutable history rows, and each is also an auditable act —
 * {@code PHASE_2_PLAN.md} §Audit names both outright, which is {@code P1-TSK-003}'s licence to
 * declare them here before their emitters exist. The audit record and the consent row are
 * <strong>not</strong> the same thing and neither substitutes: the consent row is the lawful
 * basis the gate queries ({@code INV-CNS-01}), the audit record is the trail of the act that
 * created it ({@code INV-AUD-01}), and they live under different retention and access regimes.
 *
 * <p><strong>Nothing here is emitted yet</strong>; both constants name their owning task in
 * {@code AuditCompletenessTest.NOT_YET_EMITTED}.
 */
public enum ConsentAuditAction implements AuditableAction {

    /**
     * A party granted consent for a purpose, against a specific version of the consent text.
     *
     * <p>No reason required: a reason explains an action taken <em>against</em> somebody, and
     * this is a person's own act — the {@code party.ProfileChanged} argument. What makes the
     * record reconstructable is the text version it was given against ({@code INV-CNS-04}),
     * which the change summary names. Emitted by {@code P2-TSK-018}.
     */
    CONSENT_GRANTED(
            "consent.ConsentGranted",
            "A party granted consent for a purpose, against a named version of the consent"
                    + " text.",
            false),

    /**
     * A party withdrew consent for a purpose.
     *
     * <p>No reason required, and asking for one would be worse than pointless: a person may
     * withdraw consent without justifying it, and a platform that demands a justification at
     * the moment of withdrawal is applying pressure exactly where the law says none may exist.
     * The withdrawal is a new history row, never an edit ({@code INV-CNS-02}), and takes effect
     * on every instance immediately ({@code INV-CNS-03}). Emitted by {@code P2-TSK-018}.
     */
    CONSENT_WITHDRAWN(
            "consent.ConsentWithdrawn",
            "A party withdrew consent for a purpose; the gated capability blocks from this"
                    + " record on.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    ConsentAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

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
