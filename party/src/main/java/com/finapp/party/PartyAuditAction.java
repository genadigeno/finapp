package com.finapp.party;

import com.finapp.platform.audit.AuditableAction;

/**
 * What the {@code party} module does that must produce an audit record.
 *
 * <p>Declared per module rather than centrally: actions belong to the module that performs them,
 * and {@code platform} sits below every business module, so an enum there naming this module's
 * vocabulary would invert the dependency. See {@code AUDITABLE_ACTIONS.md} §2.
 *
 * <p><strong>Deliberately small, and it will grow with the tasks that emit each action.</strong>
 * A registry is the list reality is checked against, so an action belongs here whether or not the
 * code emitting it exists — but that is a licence to declare what this module's documented
 * responsibility makes certain, not to guess at Phase 2. {@code MODULE_ARCHITECTURE.md} §`party`
 * states one auditable action outright: "profile changes audited". That is what is here.
 *
 * <p><strong>Nothing here is emitted yet</strong>, exactly as none of {@code PlatformAuditAction}
 * is. The module has no aggregates ({@code P1-TSK-005}) and no endpoints ({@code P1-TSK-006}), so
 * there is nothing to record. The gap is the same one recorded in {@code CURRENT_STATE.md}
 * §Known Architectural Debt for the platform's three.
 */
public enum PartyAuditAction implements AuditableAction {

    /**
     * A Party's profile data changed.
     *
     * <p>Audited because this module holds personal data and a change to it is a change to what
     * the platform believes about a person — which later decisions, including KYC and credit, are
     * taken against. The record is what makes "what did we hold about them at the time" an
     * answerable question rather than an inference from the current row.
     *
     * <p>No reason is required. This is ordinarily the customer maintaining their own details,
     * not a human overriding a rule, and a mandatory reason on a routine action produces a column
     * of {@code "update"} — which is how a required field stops meaning anything
     * ({@code AUDITABLE_ACTIONS.md} §4). A staff-initiated change on someone else's behalf is a
     * different action and will be declared separately when it exists.
     */
    PARTY_PROFILE_CHANGED(
            "party.ProfileChanged",
            "A party's profile data was changed, recording what was held before and after.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    PartyAuditAction(String code, String description, boolean requiresReason) {
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
