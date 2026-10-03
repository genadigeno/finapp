package com.finapp.fx;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A pricing fraction - a spread or a markup - as a value (`P9-TSK-002`, ADR-0074 §10).
 *
 * <p>In {@code [0, 0.1)} at scale at most {@link #SCALE}: the {@code NUMERIC(7,6)} a pricing pair
 * stores it in. A value with a seventh decimal is refused, never rounded ({@code INV-MON-03}). Its
 * {@link #attributionWeight() attribution weight} is {@code value × 10⁶}, an exact {@code long}, so
 * splitting a posted margin between spread and markup through {@code Money.allocateByWeights} is
 * integer arithmetic on stored inputs - reproducible on replay ({@code INV-HIST-04}).
 *
 * @param value the fraction, {@code 0 <= value < 0.1}, scale at most six
 */
public record Margin(BigDecimal value) {

    /** The most decimal places a margin carries - {@code NUMERIC(7,6)}. */
    public static final int SCALE = 6;

    /** The exclusive upper bound: a ten-percent margin is a configuration error, not a price. */
    public static final BigDecimal LIMIT = new BigDecimal("0.1");

    public static final Margin ZERO = new Margin(BigDecimal.ZERO);

    public Margin {
        Objects.requireNonNull(value, "value must not be null");
        if (value.signum() < 0 || value.compareTo(LIMIT) >= 0) {
            throw new IllegalArgumentException(
                    "A margin is a fraction in [0, 0.1), but was " + value.toPlainString());
        }
        if (value.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "A margin carries at most " + SCALE + " decimal places, but "
                            + value.toPlainString() + " carries " + value.scale()
                            + " - refused, never rounded");
        }
    }

    public static Margin of(String value) {
        return new Margin(new BigDecimal(value));
    }

    /** {@code value × 10⁶}, exactly: the margin's weight in an attribution. */
    public long attributionWeight() {
        return value.movePointRight(SCALE).longValueExact();
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Margin other && value.compareTo(other.value) == 0;
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
