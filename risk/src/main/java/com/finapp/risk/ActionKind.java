package com.finapp.risk;

import java.util.Objects;

/**
 * The downstream actions a case may propose (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112; owner
 * decision C10) - each a keyed command through the owning domain's existing door, dispatched by the action schedule in
 * its own transaction (`-017`), never a direct write and never a reversal ({@code INV-RSK-02}).
 *
 * <p>Each kind names its <strong>target</strong> ({@link SubjectKind}) and the <strong>owning domain</strong> whose port
 * executes it - identity ({@code IdentityActions}), accounts ({@code AccountActions}), merchant ({@code MerchantActions}),
 * crossborder ({@code BeneficiaryActions}), payments ({@code HeldWithdrawalActions}) and kyc ({@code PartyRescreening}).
 * The two held-payment kinds are `-010`'s second slice's: declared so the vocabulary is whole, proposable only once that
 * slice lands (the task edits {@link #available}).
 */
public enum ActionKind {

    /** Suspend the identity - the platform never resets a credential (C10). */
    SUSPEND_IDENTITY(SubjectKind.PARTY, Domain.IDENTITY, true),

    /** Reinstate a suspended identity. */
    REINSTATE_IDENTITY(SubjectKind.PARTY, Domain.IDENTITY, true),

    /** Revoke the identity's sessions (C10). */
    REVOKE_SESSIONS(SubjectKind.PARTY, Domain.IDENTITY, true),

    /** Suspend the account. */
    SUSPEND_ACCOUNT(SubjectKind.ACCOUNT, Domain.ACCOUNTS, true),

    /** Reinstate a suspended account. */
    REINSTATE_ACCOUNT(SubjectKind.ACCOUNT, Domain.ACCOUNTS, true),

    /** Suspend the merchant. */
    SUSPEND_MERCHANT(SubjectKind.MERCHANT, Domain.MERCHANT, true),

    /** Block the beneficiary. */
    BLOCK_BENEFICIARY(SubjectKind.BENEFICIARY, Domain.CROSSBORDER, true),

    /** Release a held withdrawal to dispatch (an approved override's or disposition's effect, `-012`). */
    RELEASE_HELD_WITHDRAWAL(SubjectKind.ACCOUNT, Domain.PAYMENTS, true),

    /** Deny a held withdrawal, releasing its hold. */
    DENY_HELD_WITHDRAWAL(SubjectKind.ACCOUNT, Domain.PAYMENTS, true),

    /** Capture a held card payment (`-010`'s second slice; not yet proposable). */
    CAPTURE_HELD_PAYMENT(SubjectKind.MERCHANT, Domain.PAYMENTS, false),

    /** Void a held card payment (`-010`'s second slice; not yet proposable). */
    VOID_HELD_PAYMENT(SubjectKind.MERCHANT, Domain.PAYMENTS, false),

    /** Request kyc's rescreening of the party (`-022`). */
    RESCREEN_PARTY(SubjectKind.PARTY, Domain.KYC, true);

    /** The owning domain whose existing command executes the action. Named, not depended on: a port each. */
    public enum Domain {
        IDENTITY,
        ACCOUNTS,
        MERCHANT,
        CROSSBORDER,
        PAYMENTS,
        KYC
    }

    private final SubjectKind target;
    private final Domain domain;
    private final boolean available;

    ActionKind(SubjectKind target, Domain domain, boolean available) {
        this.target = Objects.requireNonNull(target, "target");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.available = available;
    }

    /** The kind of subject the action acts on. */
    public SubjectKind target() {
        return target;
    }

    /** The domain whose door executes it. */
    public Domain domain() {
        return domain;
    }

    /** True once the owning domain has a door for it - the two held-payment kinds wait for `-010`'s second slice. */
    public boolean available() {
        return available;
    }
}
