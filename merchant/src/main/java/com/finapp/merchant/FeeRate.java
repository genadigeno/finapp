package com.finapp.merchant;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The proportional part of a fee: a dimensionless ratio applied to a captured amount
 * (`P6-TSK-004`, ADR-0050 §4).
 *
 * <p><strong>Not {@code Money}, and not {@code double}.</strong> A rate has no currency, so
 * {@code Money} would be a lie about what it is; and {@code double} cannot represent 0.029
 * exactly, so a rate held in one would price every capture very slightly wrong in a direction
 * nobody chose ({@code INV-MON-01}). {@link BigDecimal} is exact, and the multiplication it
 * performs against a gross amount is exact — the single rounding in the whole fee computation
 * happens once, afterwards, under the version's named policy.
 *
 * <p><strong>Why the bound is {@code [0, 1)}.</strong> A rate of 1 takes the entire capture
 * and leaves the merchant nothing; above 1 it takes more than arrived, making the net negative
 * on the rate alone. Neither states anything a commercial agreement means, so the bound is
 * enforced here and again as a schema {@code CHECK} — a rate is configuration an operator
 * types, and a mistyped decimal point is the realistic defect.
 *
 * <p><strong>Why the scale is bounded.</strong> Rates are quoted in percent (0.029), in basis
 * points (0.0295) and occasionally finer. {@link #MAX_SCALE} of 6 reaches one ten-thousandth
 * of a percent, which is past any real quotation, and a bound means a rate cannot arrive with
 * fifty digits of spurious precision that no two readers would render the same way.
 */
public final class FeeRate implements Comparable<FeeRate> {

    /**
     * The most decimal places a rate may carry.
     *
     * <p>Public because the database {@code CHECK} bounding {@code fee_schedule_version.rate}
     * is generated from it. Two independent copies of this number would eventually disagree.
     */
    public static final int MAX_SCALE = 6;

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final BigDecimal value;

    private FeeRate(BigDecimal value) {
        this.value = value;
    }

    /**
     * @throws IllegalArgumentException if the rate is negative, at or above 1, or carries more
     *     than {@link #MAX_SCALE} decimal places
     */
    public static FeeRate of(BigDecimal value) {
        Objects.requireNonNull(value, "rate must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("A fee rate must not be negative, but was: " + value);
        }
        if (value.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException(
                    "A fee rate of 1 or more takes the whole capture or more, leaving the"
                            + " merchant nothing or less; it was: "
                            + value);
        }
        if (value.scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "A fee rate carries at most "
                            + MAX_SCALE
                            + " decimal places, but "
                            + value
                            + " carries "
                            + value.scale());
        }
        // Negative scale is legal in BigDecimal (1E+2) and would round-trip through the
        // database as a different literal; normalising to a non-negative scale here keeps the
        // stored form and the in-memory form the same number written the same way.
        return new FeeRate(value.scale() < 0 ? value.setScale(0) : value);
    }

    /** Parses a recorded rate. The stored form is exactly {@link #toBigDecimal}'s. */
    public static FeeRate ofStored(BigDecimal value) {
        return of(value);
    }

    /** The exact ratio. Never via {@code double}. */
    public BigDecimal toBigDecimal() {
        return value;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public int compareTo(FeeRate other) {
        return value.compareTo(other.value);
    }

    /**
     * Value equality by <strong>numeric</strong> value, not by representation: {@code 0.02900}
     * and {@code 0.029} are the same rate and price identically, so treating them as different
     * would make a recomputation test pass or fail on trailing zeros rather than on money.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof FeeRate other && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
