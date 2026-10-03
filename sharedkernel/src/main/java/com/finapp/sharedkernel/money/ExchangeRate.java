package com.finapp.sharedkernel.money;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * How many units of {@code destination} one unit of {@code source} buys - a directed price, as a
 * value (`P9-TSK-002`, ADR-0074 §1).
 *
 * <p><strong>Why the kernel, not {@code fx}.</strong> A rate is a representation primitive the
 * platform shares exactly as it shares {@link Money}: the quote prices with it, the cross-border
 * offer displays it, reporting will read it. In {@code fx}, every later holder would need an edge
 * to a sibling or a copy of the type - the argument ADR-0003 made for {@code Money}, recorded once
 * in ADR-0074 rather than re-argued per module. It carries no Spring, no persistence and no
 * policy.
 *
 * <p><strong>Bounded so a column can never round it.</strong> Precision at most
 * {@link #MAX_PRECISION} and scale at most {@link #MAX_SCALE} - the {@code NUMERIC(20,10)} every
 * rate column is generated from ({@code RateColumns}). A value beyond either is refused here, not
 * rounded: a provider rate with eleven decimals is the provider's defect, and rounding it would
 * be a decision nobody named ({@code INV-MON-03}).
 *
 * <p><strong>No inversion and no cross rate.</strong> {@code EUR→USD} and {@code USD→EUR} are two
 * different prices, each obtained, never derived: an inverted rate is a rounding decision nobody
 * named, and a destination-fixed conversion is {@link #sourceFor one exactly-rounded division},
 * never an inversion followed by a multiplication, which rounds twice. Every operation ends in at
 * most ONE named rounding.
 *
 * <p>Equality is numeric ({@code 1.085024} equals {@code 1.0850240000}); the hash agrees.
 *
 * @param source the currency sold
 * @param destination the currency bought; never {@code source}
 * @param value units of destination per one unit of source, strictly positive
 */
public record ExchangeRate(CurrencyCode source, CurrencyCode destination, BigDecimal value)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The most significant digits a rate may carry - {@code NUMERIC(20, …)}. */
    public static final int MAX_PRECISION = 20;

    /** The most decimal places a rate may carry - {@code NUMERIC(…, 10)}. */
    public static final int MAX_SCALE = 10;

    public ExchangeRate {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (source.equals(destination)) {
            throw new IllegalArgumentException(
                    "A rate converts between two currencies, but both were " + source);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("A rate must be positive, but was " + value);
        }
        if (value.scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "A rate carries at most " + MAX_SCALE + " decimal places, but " + value
                            + " carries " + value.scale() + " - refused, never rounded");
        }
        if (value.precision() > MAX_PRECISION) {
            throw new IllegalArgumentException(
                    "A rate carries at most " + MAX_PRECISION + " digits, but " + value
                            + " carries " + value.precision());
        }
    }

    /** Factory mirroring the canonical constructor, for readability at call sites. */
    public static ExchangeRate of(CurrencyCode source, CurrencyCode destination, BigDecimal value) {
        return new ExchangeRate(source, destination, value);
    }

    /**
     * {@code amount × rate}, exactly - no rounding at all. The caller names the one rounding
     * that turns it into money ({@link #convert}) or into a margin.
     *
     * @throws CurrencyMismatchException if {@code amount} is not in {@code source}
     */
    public BigDecimal exactProduct(Money amount) {
        requireCurrency(amount, source, "the source");
        return amount.toBigDecimal().multiply(value);
    }

    /**
     * The destination amount {@code amount} buys: {@link #exactProduct} rounded once, under
     * {@code policy}, to the destination's minor units.
     */
    public Money convert(Money amount, RoundingPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return Money.of(exactProduct(amount), destination, policy);
    }

    /**
     * The source amount that buys exactly {@code destinationAmount}: ONE exactly-rounded division
     * to the source's minor units, under {@code policy} - never {@code 1/rate} taken first, which
     * would round twice.
     *
     * @throws CurrencyMismatchException if {@code destinationAmount} is not in {@code destination}
     */
    public Money sourceFor(Money destinationAmount, RoundingPolicy policy) {
        requireCurrency(destinationAmount, destination, "the destination");
        Objects.requireNonNull(policy, "policy must not be null");
        BigDecimal quotient =
                destinationAmount.toBigDecimal().divide(value, source.minorUnits(), policy.mode());
        return Money.of(quotient, source);
    }

    /**
     * {@code this − other}, exactly: how much more destination one unit of source buys at this
     * rate than at {@code other}. Same direction only - a margin against a rate in the opposite
     * direction would need an inversion, which this type never takes.
     *
     * @throws IllegalArgumentException if {@code other} is not the same pair in the same direction
     */
    public BigDecimal marginAgainst(ExchangeRate other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!source.equals(other.source) || !destination.equals(other.destination)) {
            throw new IllegalArgumentException(
                    "A margin compares one direction: " + source + "→" + destination + " against "
                            + other.source + "→" + other.destination);
        }
        return value.subtract(other.value);
    }

    private static void requireCurrency(Money amount, CurrencyCode expected, String side) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.currency().equals(expected)) {
            throw new CurrencyMismatchException(expected, amount.currency(), "price against " + side);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExchangeRate other
                && source.equals(other.source)
                && destination.equals(other.destination)
                && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, destination, value.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return source + "→" + destination + " " + value.toPlainString();
    }
}
