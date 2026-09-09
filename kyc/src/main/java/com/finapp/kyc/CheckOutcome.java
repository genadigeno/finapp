package com.finapp.kyc;

/**
 * What a provider's answer normalises to — the port's result vocabulary (`P2-TSK-009`,
 * {@code INV-KYC-01}).
 *
 * <p>A separate enum rather than {@link CheckStatus}, so an adapter <strong>cannot express an
 * illegal completion</strong>: a port whose result type contained {@code REQUESTED} would let a
 * defective adapter un-dispatch a check, and no review of call sites is as good as the state not
 * being sayable — the level-is-not-a-parameter shape (`P1-TSK-027`).
 *
 * <p>This is <em>our</em> vocabulary (ADR-0008): whatever a provider answers — including states
 * we have never seen — an adapter maps it here, and the mapping's default is
 * {@link #INDETERMINATE}, never success.
 */
public enum CheckOutcome {
    CLEAR,
    HIT,
    INDETERMINATE;

    /** The terminal {@link CheckStatus} this outcome moves a check to. */
    public CheckStatus toStatus() {
        return switch (this) {
            case CLEAR -> CheckStatus.CLEAR;
            case HIT -> CheckStatus.HIT;
            case INDETERMINATE -> CheckStatus.INDETERMINATE;
        };
    }
}
