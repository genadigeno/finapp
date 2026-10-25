package com.finapp.risk;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The downstream actions a case may propose (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112; owner
 * decision C10) - each a keyed command through the owning domain's existing door, dispatched in its own transaction
 * (T-act, `P13-TSK-017`), never a direct write and never a reversal ({@code INV-RSK-02}). There is no credential reset
 * (C10) and no reversal, refund or posting kind (ADR-0112 decision 8).
 *
 * <p>Each kind names its <strong>target</strong> ({@link ActionTarget} - re-typed by `P13-TSK-017`: the held kinds act on
 * the judged movement, a withdrawal or a payment intent, which the vocabulary had declared as an account and a
 * merchant) and the <strong>owning domain</strong> whose port executes it - identity ({@code IdentityActions}), accounts
 * ({@code AccountActions}), merchant ({@code MerchantActions}), crossborder ({@code BeneficiaryActions}), payments
 * ({@code HeldWithdrawalActions}, {@code HeldPaymentActions}) and kyc ({@code PartyRescreening}).
 *
 * <p><strong>Available</strong> means the owning domain has the command today. `P13-TSK-017` found three that do not and
 * recorded them unavailable - never proposable, never dispatched - rather than add a command to another domain (the
 * entry's out of scope): accounts has no door producing {@code SUSPENDED} (its machine names the state, no command
 * reaches it), and crossborder reaches {@code BLOCKED} only by kyc's screening outcome from {@code IN_REVIEW} - no
 * command blocks a beneficiary. Each is an owner item.
 */
public enum ActionKind {

    /** Suspend the party's identities - the platform never resets a credential (C10). */
    SUSPEND_IDENTITY(ActionTarget.PARTY, Domain.IDENTITY, true),

    /** Reinstate the party's suspended identities. */
    REINSTATE_IDENTITY(ActionTarget.PARTY, Domain.IDENTITY, true),

    /** Revoke every session of the party's identities (C10). */
    REVOKE_SESSIONS(ActionTarget.PARTY, Domain.IDENTITY, true),

    /** Suspend the account - <strong>unavailable</strong>: accounts has no suspension command (`P13-TSK-017`). */
    SUSPEND_ACCOUNT(ActionTarget.ACCOUNT, Domain.ACCOUNTS, false),

    /** Reinstate a suspended account - <strong>unavailable</strong>, as {@link #SUSPEND_ACCOUNT}. */
    REINSTATE_ACCOUNT(ActionTarget.ACCOUNT, Domain.ACCOUNTS, false),

    /** Suspend the merchant. */
    SUSPEND_MERCHANT(ActionTarget.MERCHANT, Domain.MERCHANT, true),

    /** Block the beneficiary - <strong>unavailable</strong>: crossborder has no block command (`P13-TSK-017`). */
    BLOCK_BENEFICIARY(ActionTarget.BENEFICIARY, Domain.CROSSBORDER, false),

    /** Release a held withdrawal to dispatch (an approved override's or a case's effect). */
    RELEASE_HELD_WITHDRAWAL(ActionTarget.WITHDRAWAL, Domain.PAYMENTS, true),

    /** Deny a held withdrawal, releasing its hold. */
    DENY_HELD_WITHDRAWAL(ActionTarget.WITHDRAWAL, Domain.PAYMENTS, true),

    /**
     * Capture a held card payment (`-010`'s second slice, 2026-10-24): payments' keyed capture door behind
     * {@code HeldPaymentActions}.
     */
    CAPTURE_HELD_PAYMENT(ActionTarget.PAYMENT_INTENT, Domain.PAYMENTS, true),

    /** Void a held card payment (`-010`'s second slice, 2026-10-24): payments' keyed void door. */
    VOID_HELD_PAYMENT(ActionTarget.PAYMENT_INTENT, Domain.PAYMENTS, true),

    /** Request kyc's rescreening of the party (`-022`'s: the sweep's, never a case's proposal). */
    RESCREEN_PARTY(ActionTarget.PARTY, Domain.KYC, true);

    /** The owning domain whose existing command executes the action. Named, not depended on: a port each. */
    public enum Domain {
        IDENTITY,
        ACCOUNTS,
        MERCHANT,
        CROSSBORDER,
        PAYMENTS,
        KYC
    }

    private final ActionTarget target;
    private final Domain domain;
    private final boolean available;

    ActionKind(ActionTarget target, Domain domain, boolean available) {
        this.target = Objects.requireNonNull(target, "target");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.available = available;
    }

    /** What the action acts on. */
    public ActionTarget target() {
        return target;
    }

    /** The domain whose door executes it. */
    public Domain domain() {
        return domain;
    }

    /** True when the owning domain has the command - every kind but accounts' two and crossborder's block. */
    public boolean available() {
        return available;
    }

    /** True for the kinds an approved override issues - payments' four held doors (ADR-0108, ADR-0112 decision 2). */
    public boolean issuedByOverride() {
        return this == RELEASE_HELD_WITHDRAWAL || this == DENY_HELD_WITHDRAWAL || this == CAPTURE_HELD_PAYMENT
                || this == VOID_HELD_PAYMENT;
    }

    /** The vocabulary as a SQL list - {@code V013}'s CHECK is held to it. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
