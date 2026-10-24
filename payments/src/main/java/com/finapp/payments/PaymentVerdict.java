package com.finapp.payments;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The checkout payment risk seam's answer for one confirmation (`P13-TSK-010`; {@code PHASE_13_PLAN.md} §7.3, §12.1,
 * §12.3) - what payments commits, never why. Three answers:
 *
 * <ul>
 *   <li>{@link Kind#PERMIT} - route and dispatch as before Phase 13;</li>
 *   <li>{@link Kind#REFUSE} - the intent ends {@code FAILED} with its own {@code RISK_REFUSED}, before any attempt,
 *       any routing row or any call: terminal, never re-confirmable;</li>
 *   <li>{@link Kind#HOLD} - <strong>a card payment only</strong> (the authorisation is the native hold, C2): authorise
 *       and defer the capture behind a capture hold until a person captures or voids it, or {@link #holdValidity()}
 *       lapses and payments voids it.</li>
 * </ul>
 *
 * <p><strong>No reason crosses</strong> ({@code WithdrawalVerdict}'s stance): the committed reason is payments' own,
 * fixed for the seam, so an implementation can neither invent a vocabulary nor carry a rule's name onto a payments row.
 * The one datum a {@code HOLD} carries is how long it may stand - the pinned policy's hold validity (ADR-0109: policy
 * data, never a payments constant).
 */
public final class PaymentVerdict {

    /** The longest a hold may stand - the policy's own bound (thirty days), held again here so a defect is loud. */
    public static final Duration MAX_HOLD_VALIDITY = Duration.ofDays(30);

    /** What payments commits. */
    public enum Kind {
        PERMIT,
        HOLD,
        REFUSE
    }

    private static final PaymentVerdict PERMIT = new PaymentVerdict(Kind.PERMIT, Optional.empty());
    private static final PaymentVerdict REFUSE = new PaymentVerdict(Kind.REFUSE, Optional.empty());

    private final Kind kind;
    private final Optional<Duration> holdValidity;

    private PaymentVerdict(Kind kind, Optional<Duration> holdValidity) {
        this.kind = kind;
        this.holdValidity = holdValidity;
    }

    /** Route and dispatch it. */
    public static PaymentVerdict permit() {
        return PERMIT;
    }

    /** Refuse it: the intent {@code FAILED(RISK_REFUSED)}, nothing routed, nothing sent. */
    public static PaymentVerdict refuse() {
        return REFUSE;
    }

    /**
     * Authorise it and hold the capture for at most {@code validity} - positive, at most {@link #MAX_HOLD_VALIDITY}:
     * past it, payments voids the authorisation. Admitted for a card payment only.
     */
    public static PaymentVerdict hold(Duration validity) {
        Objects.requireNonNull(validity, "validity must not be null");
        if (validity.isZero() || validity.isNegative() || validity.compareTo(MAX_HOLD_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                    "a hold validity is positive and at most " + MAX_HOLD_VALIDITY.toDays() + " days");
        }
        return new PaymentVerdict(Kind.HOLD, Optional.of(validity));
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
        return other instanceof PaymentVerdict that && kind == that.kind && holdValidity.equals(that.holdValidity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, holdValidity);
    }

    @Override
    public String toString() {
        return "PaymentVerdict[" + kind + holdValidity.map(v -> ", " + v).orElse("") + "]";
    }
}
