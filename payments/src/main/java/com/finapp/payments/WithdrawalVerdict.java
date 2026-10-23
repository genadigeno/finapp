package com.finapp.payments;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The withdrawal risk seam's answer for one withdrawal (`P13-TSK-009`; {@code PHASE_13_PLAN.md} §7.3, §12.3) - what
 * payments commits, never why. Three answers, because the withdrawal has a native hold-then-dispatch shape (C2):
 *
 * <ul>
 *   <li>{@link Kind#PERMIT} - dispatch it, as before Phase 13 ({@code DISPATCHED});</li>
 *   <li>{@link Kind#HOLD} - accept it and move nothing: the ledger hold kept, {@code HELD} until a person releases or
 *       denies it or {@link #holdValidity()} lapses;</li>
 *   <li>{@link Kind#REFUSE} - {@code FAILED(RISK_REFUSED)}, the hold released in the same transaction.</li>
 * </ul>
 *
 * <p><strong>No reason crosses</strong> (the {@code transfers.SeamVerdict} stance): the committed failure reason is
 * payments' own, fixed for the seam, so an implementation can neither invent a vocabulary nor carry a rule's name onto
 * a payments row. The one datum a {@code HOLD} carries is how long it may stand - the pinned policy's hold validity
 * (ADR-0109: policy data, versioned and pinned per assessment, so never a payments constant).
 */
public final class WithdrawalVerdict {

    /** The longest a hold may stand - the policy's own bound (thirty days), held again here so a defect is loud. */
    public static final Duration MAX_HOLD_VALIDITY = Duration.ofDays(30);

    /** What payments commits. */
    public enum Kind {
        PERMIT,
        HOLD,
        REFUSE
    }

    private static final WithdrawalVerdict PERMIT = new WithdrawalVerdict(Kind.PERMIT, Optional.empty());
    private static final WithdrawalVerdict REFUSE = new WithdrawalVerdict(Kind.REFUSE, Optional.empty());

    private final Kind kind;
    private final Optional<Duration> holdValidity;

    private WithdrawalVerdict(Kind kind, Optional<Duration> holdValidity) {
        this.kind = kind;
        this.holdValidity = holdValidity;
    }

    /** Dispatch it. */
    public static WithdrawalVerdict permit() {
        return PERMIT;
    }

    /** Refuse it: {@code FAILED(RISK_REFUSED)}, the hold released. */
    public static WithdrawalVerdict refuse() {
        return REFUSE;
    }

    /**
     * Hold it for at most {@code validity} - positive, at most {@link #MAX_HOLD_VALIDITY}: past it, payments fails the
     * withdrawal {@code RISK_REFUSED}/{@code HOLD_EXPIRED} and releases its hold.
     */
    public static WithdrawalVerdict hold(Duration validity) {
        Objects.requireNonNull(validity, "validity must not be null");
        if (validity.isZero() || validity.isNegative() || validity.compareTo(MAX_HOLD_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                    "a hold validity is positive and at most " + MAX_HOLD_VALIDITY.toDays() + " days");
        }
        return new WithdrawalVerdict(Kind.HOLD, Optional.of(validity));
    }

    public Kind kind() {
        return kind;
    }

    /** Present exactly for {@link Kind#HOLD}. */
    public Optional<Duration> holdValidity() {
        return holdValidity;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WithdrawalVerdict that
                && kind == that.kind
                && holdValidity.equals(that.holdValidity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, holdValidity);
    }

    @Override
    public String toString() {
        return "WithdrawalVerdict[" + kind + holdValidity.map(v -> ", " + v).orElse("") + "]";
    }
}
