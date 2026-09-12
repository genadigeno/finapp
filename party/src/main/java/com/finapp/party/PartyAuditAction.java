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
    /**
     * A party was registered and a customer relationship opened for it, in one transaction
     * (`P1-TSK-006`).
     *
     * <p><strong>One action for two writes, deliberately.</strong> {@code PHASE_1_PLAN.md} §4
     * lists {@code RegisterParty} and {@code OpenCustomerRelationship} as separate commands, and
     * they are — but registration performs both atomically and neither is separately reachable, so
     * two audit records would describe one decision twice and invite a reader to wonder what it
     * means when only one of them is present. It cannot be.
     *
     * <p>No reason required: the request is the justification. A reason exists to explain an action
     * taken <em>against</em> someone, and self-registration is not one — which is why
     * {@link #PARTY_PROFILE_CHANGED} sits at {@code false} too and the two administrative actions
     * in {@code identity} do not.
     */
    CUSTOMER_REGISTERED(
            "party.CustomerRegistered",
            "A party was registered and a customer relationship was opened for it.",
            false),

    /**
     * An authenticated person registered an organisation they act for (`P2-TSK-016`).
     *
     * <p>One action for three writes — the ORGANISATION party, its customer, and the registrant
     * record — for {@link #CUSTOMER_REGISTERED}'s reason: one decision, performed atomically,
     * with no piece separately reachable. The change summary carries the registrant linkage,
     * because <em>who may act for this organisation</em> is the record's point.
     *
     * <p>Unlike {@link #CUSTOMER_REGISTERED}, the actor is <strong>the proven person</strong>,
     * never the platform: this caller is authenticated, so {@code enterSystem()} would erase
     * exactly the attribution the trail exists for.
     *
     * <p>No reason required: registering one's own organisation is not an action taken against
     * anybody.
     */
    ORGANISATION_REGISTERED(
            "party.OrganisationRegistered",
            "An organisation was registered, with the acting person recorded as its registrant.",
            false),

    /**
     * A party changed its own profile.
     *
     * <h2>The description used to promise the before and after values, and the classification
     * forbids them</h2>
     *
     * <p>It read <em>"recording what was held before and after"</em> until {@code P1-TSK-030} built
     * the endpoint that emits it. Those values are display names — {@code RESTRICTED-PII}, and
     * {@code DATA_CLASSIFICATION.md} calls {@code party.display_name} <em>"the clearest
     * RESTRICTED-PII column on the platform"</em>. {@code audit_record.change_summary} is
     * {@code RESTRICTED-FINANCIAL}.
     *
     * <p>Those are <strong>peers, not a hierarchy</strong>: writing PII into a column whose handling
     * assumes financial data means the PII rules — retention, subject access, erasure — do not reach
     * it, and ADR-0022 is explicit that a column cannot be reclassified once it holds data. So the
     * record says <em>that</em> the display name changed, on which party, by whom and when; it does
     * not say what the name was. {@code PartyRegistration}'s change summary already made the same
     * choice for the same reason.
     *
     * <p><strong>The consequence is stated rather than glossed:</strong> there is no name history in
     * Phase 1, and this record does not create one. <em>Who changed it and when</em> is an audit
     * question and is answered; <em>what it used to be</em> is a history question, and the plan asks
     * for no such capability.
     *
     * <p>No reason required: a reason explains an action taken <em>against</em> somebody, and this
     * is a person editing their own name.
     */
    PARTY_PROFILE_CHANGED(
            "party.ProfileChanged",
            "A party's own profile data was changed, recording which field and by whom.",
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
