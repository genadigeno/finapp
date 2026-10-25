package com.finapp.risk;

import java.util.Objects;

/**
 * T-act's middle step (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 7.1 T-act, 7.2 (R6), 12.8; ADR-0112 decisions
 * 4-7; {@code INV-RSK-02}, {@code INV-IDEM-01}) - a claimed action through its owning domain's existing door, on
 * <strong>no connection and inside no transaction</strong>: the owner runs its command in its own transaction on its own
 * lock order with no risk row held (the claim has committed), under the one key every attempt of the action presents -
 * {@code risk.action:<id>} - and the action's frozen reason code, so the owner's fingerprint is the same each time and any
 * number of attempts is one effect. The acting principal is the caller's: the platform ({@code app}'s
 * {@code RiskActionDriver} dispatches under {@code SecurityContext.enterSystem()} - a person decided, the platform
 * executes), so the owner's audit names the system and carries the action's code and correlation.
 *
 * <p>Risk writes nothing of the owner's here and never reverses: the answer is the owner's, word for word. A kind with no
 * owning command - accounts' two, crossborder's block, kyc's rescreening - is never born on the machine, and reaching it
 * here is a defect, refused loudly.
 */
public final class ActionDispatch {

    private final IdentityActions identities;
    private final MerchantActions merchants;
    private final HeldWithdrawalActions withdrawals;
    private final HeldPaymentActions payments;

    public ActionDispatch(IdentityActions identities, MerchantActions merchants, HeldWithdrawalActions withdrawals,
            HeldPaymentActions payments) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.payments = Objects.requireNonNull(payments, "payments");
    }

    /**
     * {@code claimed} through its owner's door.
     *
     * @return the owner's answer
     * @throws RuntimeException whatever the owner throws - unreachable, in flight, a storage failure: nothing is
     *     recorded, the permit stands, and the next claim re-drives the same key
     */
    public ActionOutcome dispatch(RiskAction claimed) {
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.status() != ActionStatus.DISPATCHED) {
            throw new IllegalStateException("only a claimed action is dispatched: " + claimed.status());
        }
        String key = claimed.key();
        String reason = claimed.reasonCode();
        return switch (claimed.kind()) {
            case SUSPEND_IDENTITY -> identities.suspend(claimed.target(), decider(claimed), key, reason);
            case REINSTATE_IDENTITY -> identities.reinstate(claimed.target(), decider(claimed), key, reason);
            case REVOKE_SESSIONS -> identities.revokeSessions(claimed.target(), key, reason);
            case SUSPEND_MERCHANT -> merchants.suspend(claimed.target(), key, reason);
            case RELEASE_HELD_WITHDRAWAL -> withdrawals.release(claimed.target(), key, reason);
            case DENY_HELD_WITHDRAWAL -> withdrawals.deny(claimed.target(), key, reason);
            case CAPTURE_HELD_PAYMENT -> payments.capture(claimed.target(), key, reason);
            case VOID_HELD_PAYMENT -> payments.voidHold(claimed.target(), key, reason);
            case SUSPEND_ACCOUNT, REINSTATE_ACCOUNT, BLOCK_BENEFICIARY, RESCREEN_PARTY ->
                    throw new IllegalStateException("no owning command executes " + claimed.kind()
                            + ": it is never proposed or issued");
        };
    }

    /** The person who approved the action - identity's administration names the decider, never the subject. */
    private static String decider(RiskAction claimed) {
        return claimed.decidedBy().orElseThrow(() -> new IllegalStateException("a dispatched action was decided"))
                .id();
    }
}
