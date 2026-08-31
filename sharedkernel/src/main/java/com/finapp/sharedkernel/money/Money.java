package com.finapp.sharedkernel.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An exact monetary amount: integer minor units, an explicit currency, and the scale the
 * amount was created with (ADR-0003).
 *
 * <p><strong>Why integer minor units.</strong> Binary floating point cannot represent decimal
 * fractions exactly, so errors accumulate and money is silently created or destroyed
 * ({@code INV-MON-01}). There is no {@code double} anywhere on this type's path, including
 * its factories and its conversions.
 *
 * <p><strong>Why the scale is stored rather than looked up.</strong> A currency's minor-unit
 * count is data that changes — between JDK versions, and occasionally in reality. An amount
 * that looked up its scale on demand would silently change meaning when that data changed:
 * {@code 1234} minor units is 12.34 at scale 2 and 1.234 at scale 3. Storing the scale makes
 * a historical amount interpretable as originally written ({@code INV-MON-05}).
 *
 * <p><strong>Rounding is never implicit.</strong> Every operation that could lose precision
 * requires the caller to name a {@link RoundingPolicy}; there is no default and no overload
 * that guesses ({@code INV-MON-03}). {@link #of(BigDecimal, CurrencyCode)} — the one taking
 * no policy — does not round at all, it refuses.
 *
 * <p><strong>Allocation loses nothing.</strong> {@link #allocateEvenly(int)} and
 * {@link #allocateByWeights(long...)} distribute the indivisible remainder rather than discarding it,
 * so the parts always sum back to the original. An absorbed residual is money creation or
 * destruction, at scale ({@code INV-BAL-03}).
 *
 * <p><strong>What this type still does not do.</strong> No free division, and no conversion
 * between currencies. Conversion is Phase 9 and posts through an FX position; it never
 * happens inside this type.
 *
 * <p>Immutable and thread-safe. Every operation returns a new instance.
 */
public final class Money implements Comparable<Money> {

    /**
     * No ISO 4217 currency currently exceeds 4 decimal places (CLF). The bound exists to turn
     * a nonsensical scale — from a corrupt record or a programming error — into an immediate
     * failure rather than an amount that is quietly off by orders of magnitude.
     */
    private static final int MAX_SUPPORTED_SCALE = 9;

    private final long minorUnits;
    private final CurrencyCode currency;
    private final int scale;

    private Money(long minorUnits, CurrencyCode currency, int scale) {
        this.minorUnits = minorUnits;
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        if (scale < 0 || scale > MAX_SUPPORTED_SCALE) {
            throw new IllegalArgumentException(
                    "Scale must be between 0 and " + MAX_SUPPORTED_SCALE + ", but was: " + scale);
        }
        this.scale = scale;
    }

    // -----------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------

    /**
     * An amount in the currency's minor units — cents for USD, yen for JPY, fils for BHD.
     *
     * <p>The scale is taken from the currency, which is the correct source for an amount being
     * created now. Rehydrating a stored amount uses {@link #ofPersisted} instead.
     */
    public static Money ofMinorUnits(long minorUnits, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return new Money(minorUnits, currency, currency.minorUnits());
    }

    /**
     * An amount in major units, exactly.
     *
     * <p>Rejects any value that would need rounding to fit the currency: {@code 12.345 USD}
     * is an error, not 12.34 or 12.35. Choosing between those is a decision with a named
     * rounding mode behind it (P0-TSK-010), not something a constructor should make silently.
     *
     * @throws InexactAmountException if the amount has more precision than the currency allows
     * @throws MonetaryOverflowException if the amount is outside the representable range
     */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        // setScale without a RoundingMode throws if the change would lose information, which
        // is precisely the required behaviour: exact, or refuse. It is translated into a
        // monetary exception so that every failure of this type is one a caller can catch as
        // MonetaryException rather than having to know which JDK exception leaks through.
        BigDecimal exact;
        try {
            exact = amount.setScale(currency.minorUnits());
        } catch (ArithmeticException e) {
            throw new InexactAmountException(amount, currency, e);
        }
        try {
            return new Money(exact.unscaledValue().longValueExact(), currency, currency.minorUnits());
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(
                    "Amount " + amount.toPlainString() + " " + currency
                            + " is outside the representable range", e);
        }
    }

    /**
     * An amount in major units, rounded to the currency under an explicitly named policy.
     *
     * <p>This is the sanctioned way to turn a computed decimal — a fee, an interest accrual,
     * the result of applying a rate — into money. The policy is a required argument: there is
     * no overload that picks one, because a default rounding mode is a decision nobody made
     * and nobody can afterwards explain ({@code INV-MON-03}).
     *
     * <p>Rounding here discards the fraction, by design. Where that fraction must be
     * accounted for rather than dropped, use {@link #allocateByWeights(long...)}, which distributes it.
     *
     * @throws MonetaryOverflowException if the rounded amount is outside the representable range
     */
    public static Money of(BigDecimal amount, CurrencyCode currency, RoundingPolicy policy) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(policy, "rounding policy must not be null (INV-MON-03)");

        BigDecimal rounded = amount.setScale(currency.minorUnits(), policy.mode());
        try {
            return new Money(rounded.unscaledValue().longValueExact(), currency, currency.minorUnits());
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(
                    "Amount " + amount.toPlainString() + " " + currency
                            + " is outside the representable range once rounded "
                            + policy.policyName(), e);
        }
    }

    /** Zero in the given currency. There is no currency-less zero — {@code INV-MON-02}. */
    public static Money zero(CurrencyCode currency) {
        return ofMinorUnits(0L, currency);
    }

    /**
     * Rebuilds an amount from stored components, keeping the scale it was written with.
     *
     * <p>This is the only way to construct a {@code Money} whose scale differs from its
     * currency's present minor-unit count, and it exists solely so that a historical amount
     * survives a change to currency data. It is named to be conspicuous: using it for an
     * amount being created now would defeat the point of storing the scale at all.
     */
    public static Money ofPersisted(long minorUnits, CurrencyCode currency, int scale) {
        return new Money(minorUnits, currency, scale);
    }

    // -----------------------------------------------------------------
    // Arithmetic — exact, or it fails
    // -----------------------------------------------------------------

    /**
     * @throws CurrencyMismatchException if the currencies differ ({@code INV-MON-04})
     * @throws ScaleMismatchException if the scales differ
     * @throws MonetaryOverflowException if the result is not representable ({@code INV-MON-06})
     */
    public Money plus(Money other) {
        requireCompatible(other, "add");
        return new Money(addExact(minorUnits, other.minorUnits, "adding"), currency, scale);
    }

    /** @see #plus(Money) for the failure modes, which are identical. */
    public Money minus(Money other) {
        requireCompatible(other, "subtract");
        return new Money(subtractExact(minorUnits, other.minorUnits, "subtracting"), currency, scale);
    }

    /**
     * Multiplication by a whole number — a quantity, not a rate. Exact by construction.
     *
     * <p>There is deliberately no multiplication by a decimal or a rate: the result would
     * almost always need rounding, and the rounding mode must be the caller's explicit choice
     * (P0-TSK-010).
     */
    public Money times(long factor) {
        try {
            return new Money(Math.multiplyExact(minorUnits, factor), currency, scale);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(
                    "Overflow multiplying " + this + " by " + factor, e);
        }
    }

    /** @throws MonetaryOverflowException negating {@link Long#MIN_VALUE} is not representable */
    public Money negated() {
        try {
            return new Money(Math.negateExact(minorUnits), currency, scale);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException("Overflow negating " + this, e);
        }
    }

    /** @throws MonetaryOverflowException if the amount is {@link Long#MIN_VALUE} */
    public Money absoluteValue() {
        return isNegative() ? negated() : this;
    }

    // -----------------------------------------------------------------
    // Allocation — the only division, and it loses nothing
    // -----------------------------------------------------------------

    /**
     * Splits this amount into {@code parts} as evenly as the currency allows.
     *
     * <p>Named rather than overloaded on argument type. As {@code allocate(int)} and
     * {@code allocate(long...)} these two methods resolved silently by the width of the
     * literal: {@code allocate(3)} split three ways while {@code allocate(3L)} returned the
     * whole amount as a single part. Counts are often held in a {@code long}, so that is a
     * money bug the compiler accepts and no test notices.
     *
     * <p>The parts always sum to exactly this amount. Where the split is not exact the
     * indivisible remainder is handed out one minor unit at a time to the earliest parts:
     * 1.00 USD into 3 gives 0.34, 0.33, 0.33 — never 0.33 three times with a cent
     * evaporating ({@code INV-BAL-03}).
     *
     * <p>Negative amounts split symmetrically: −1.00 USD into 3 gives −0.34, −0.33, −0.33.
     *
     * @throws IllegalArgumentException if {@code parts} is not positive
     */
    public List<Money> allocateEvenly(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException(
                    "Cannot allocate across " + parts + " parts; must be at least 1");
        }
        long base = minorUnits / parts;
        long remainder = minorUnits % parts;
        long step = Long.signum(remainder);
        long unitsToHandOut = Math.abs(remainder);

        List<Money> allocation = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            long share = i < unitsToHandOut ? base + step : base;
            allocation.add(new Money(share, currency, scale));
        }
        return Collections.unmodifiableList(allocation);
    }

    /**
     * Splits this amount in proportion to the given weights.
     *
     * <p>See {@link #allocateEvenly(int)} for why these are named rather than overloaded.
     *
     * <p>The parts always sum to exactly this amount. Each part first takes its exact share
     * truncated toward zero; the minor units left over are then handed to the parts with the
     * largest discarded fraction — the standard largest-remainder method. Ties go to the
     * earlier part, so the split is deterministic and replaying it reproduces the same
     * result, which {@code INV-HIST-04} will require of any decision built on it.
     *
     * <p>Weights are relative. A zero weight receives nothing and is never given a remainder
     * unit. Negative weights are rejected: they have no meaning when dividing an amount.
     *
     * @throws IllegalArgumentException if no weights are given, any weight is negative, or
     *     every weight is zero
     */
    public List<Money> allocateByWeights(long... weights) {
        Objects.requireNonNull(weights, "weights must not be null");
        if (weights.length == 0) {
            throw new IllegalArgumentException("Cannot allocate across an empty set of weights");
        }
        BigInteger totalWeight = BigInteger.ZERO;
        for (long weight : weights) {
            if (weight < 0L) {
                throw new IllegalArgumentException(
                        "Weights must not be negative, but were: " + Arrays.toString(weights));
            }
            totalWeight = totalWeight.add(BigInteger.valueOf(weight));
        }
        if (totalWeight.signum() == 0) {
            throw new IllegalArgumentException("At least one weight must be non-zero");
        }

        // BigInteger for the intermediate product only: amount * weight overflows a long for
        // entirely realistic inputs, and an overflow here would misallocate silently rather
        // than fail.
        BigInteger amount = BigInteger.valueOf(minorUnits);
        long[] shares = new long[weights.length];
        BigInteger[] discardedFractions = new BigInteger[weights.length];
        long allocated = 0L;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] shareAndRemainder =
                    amount.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(totalWeight);
            shares[i] = shareAndRemainder[0].longValueExact();
            discardedFractions[i] = shareAndRemainder[1].abs();
            allocated += shares[i];
        }

        long leftover = minorUnits - allocated;
        long step = Long.signum(leftover);
        long unitsToHandOut = Math.abs(leftover);

        List<Integer> byLargestDiscardedFraction = new ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            byLargestDiscardedFraction.add(i);
        }
        byLargestDiscardedFraction.sort(
                Comparator.<Integer, BigInteger>comparing(i -> discardedFractions[i])
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()));

        long handedOut = 0L;
        for (int index : byLargestDiscardedFraction) {
            if (handedOut == unitsToHandOut) {
                break;
            }
            if (weights[index] == 0L) {
                continue;
            }
            shares[index] += step;
            handedOut++;
        }

        List<Money> allocation = new ArrayList<>(weights.length);
        for (long share : shares) {
            allocation.add(new Money(share, currency, scale));
        }
        return Collections.unmodifiableList(allocation);
    }

    // -----------------------------------------------------------------
    // Inspection
    // -----------------------------------------------------------------

    public long minorUnits() {
        return minorUnits;
    }

    public CurrencyCode currency() {
        return currency;
    }

    /** The scale this amount was created with. See the class javadoc for why it is stored. */
    public int scale() {
        return scale;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    /** Exact conversion for display and persistence. Never goes via {@code double}. */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(minorUnits, scale);
    }

    /**
     * Orders amounts of the same currency and scale.
     *
     * <p>Throws rather than imposing an order across currencies: comparing 100 USD with 100
     * JPY has no answer that is not a conversion, and a silent one would be a fabricated
     * exchange rate ({@code INV-MON-04}). Sorting a mixed-currency collection therefore
     * fails, which is the correct outcome.
     */
    @Override
    public int compareTo(Money other) {
        requireCompatible(other, "compare");
        return Long.compare(minorUnits, other.minorUnits);
    }

    // -----------------------------------------------------------------
    // Value semantics
    // -----------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return minorUnits == other.minorUnits
                && scale == other.scale
                && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minorUnits, currency, scale);
    }

    /** For example {@code 12.34 USD}. Round-trips the exact value, never an approximation. */
    @Override
    public String toString() {
        return toBigDecimal().toPlainString() + " " + currency;
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private void requireCompatible(Money other, String operation) {
        Objects.requireNonNull(other, "other amount must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency, operation);
        }
        if (scale != other.scale) {
            throw new ScaleMismatchException(currency, scale, other.scale, operation);
        }
    }

    private long addExact(long a, long b, String operation) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException("Overflow " + operation + " " + this, e);
        }
    }

    private long subtractExact(long a, long b, String operation) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException("Overflow " + operation + " from " + this, e);
        }
    }
}
