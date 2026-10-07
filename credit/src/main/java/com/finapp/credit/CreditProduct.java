package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Period;
import java.util.Objects;
import java.util.Optional;

/**
 * The credit products a decision may be requested for (ADR-0084 section 6, PHASE_10_PLAN.md
 * section 12.1).
 *
 * <p><strong>Closed.</strong> A product is a reviewed code change with its migration, never a
 * string; an unknown product is {@code 422 credit.ProductNotOffered} and an amount outside the
 * bounds {@code 422 credit.AmountOutOfRange} (both {@code P10-TSK-014}'s). Phase 11 builds its
 * priced products on these.
 *
 * <p><strong>Every member declares every property</strong>, and the declarations are checked as
 * the class loads, so a member that disagreed with itself - a bound in another currency, a
 * threshold outside the bounds, a non-positive validity - would fail every use of the type:
 * <ul>
 *   <li>its currency, and its amount bounds in it, as {@link Money} - never floating point
 *       ({@code INV-CRD-12});</li>
 *   <li>its term bounds in months, or none for a revolving product;</li>
 *   <li>its four-eyes threshold: a person's approval above it waits for a second underwriter
 *       (ADR-0089 section 4);</li>
 *   <li>its request validity - how long a decision request may stay open (G6) - and its decision
 *       validity - how long an approval reserves exposure and may be consumed - both judged on the
 *       database clock by the tasks that use them;</li>
 *   <li>its evidence retention: how long collected credit evidence is kept (ADR-0085).</li>
 * </ul>
 */
public enum CreditProduct {

    /** An amortising instalment loan: EUR 500.00 to 25,000.00 over 6 to 60 months. */
    PERSONAL_LOAN(
            euros(500_00),
            euros(25_000_00),
            new TermBounds(6, 60),
            euros(10_000_00),
            Duration.ofDays(7),
            Duration.ofDays(30),
            Period.ofMonths(25)),

    /** A revolving line: a limit of EUR 250.00 to 5,000.00, with no term. */
    CREDIT_LINE(
            euros(250_00),
            euros(5_000_00),
            null,
            euros(2_500_00),
            Duration.ofDays(7),
            Duration.ofDays(30),
            Period.ofMonths(25));

    private final Money minimumAmount;
    private final Money maximumAmount;
    private final TermBounds termBounds;
    private final Money fourEyesThreshold;
    private final Duration requestValidity;
    private final Duration decisionValidity;
    private final Period evidenceRetention;

    CreditProduct(
            Money minimumAmount,
            Money maximumAmount,
            TermBounds termBounds,
            Money fourEyesThreshold,
            Duration requestValidity,
            Duration decisionValidity,
            Period evidenceRetention) {
        this.minimumAmount = Objects.requireNonNull(minimumAmount, "minimumAmount");
        this.maximumAmount = Objects.requireNonNull(maximumAmount, "maximumAmount");
        this.termBounds = termBounds;
        this.fourEyesThreshold = Objects.requireNonNull(fourEyesThreshold, "fourEyesThreshold");
        this.requestValidity = Objects.requireNonNull(requestValidity, "requestValidity");
        this.decisionValidity = Objects.requireNonNull(decisionValidity, "decisionValidity");
        this.evidenceRetention = Objects.requireNonNull(evidenceRetention, "evidenceRetention");
        CurrencyCode currency = minimumAmount.currency();
        if (!maximumAmount.currency().equals(currency) || !fourEyesThreshold.currency().equals(currency)) {
            throw new IllegalStateException(name() + ": every amount must be in the product's one currency");
        }
        if (!minimumAmount.isPositive() || minimumAmount.compareTo(maximumAmount) > 0) {
            throw new IllegalStateException(name() + ": the amount bounds must be positive and ordered");
        }
        if (fourEyesThreshold.compareTo(minimumAmount) < 0 || fourEyesThreshold.compareTo(maximumAmount) > 0) {
            throw new IllegalStateException(name() + ": the four-eyes threshold must lie within the amount bounds");
        }
        if (requestValidity.isNegative() || requestValidity.isZero()
                || decisionValidity.isNegative() || decisionValidity.isZero()) {
            throw new IllegalStateException(name() + ": the validities must be positive");
        }
        if (evidenceRetention.isNegative() || evidenceRetention.isZero()) {
            throw new IllegalStateException(name() + ": the evidence retention must be positive");
        }
    }

    /** The product's currency - every amount of a request, a decision and an assessment is in it. */
    public CurrencyCode currency() {
        return minimumAmount.currency();
    }

    /** The smallest amount (or limit) a request may name, inclusive. */
    public Money minimumAmount() {
        return minimumAmount;
    }

    /** The largest amount (or limit) a request may name, inclusive. */
    public Money maximumAmount() {
        return maximumAmount;
    }

    /** The term bounds in months - empty for a revolving product, which has no term. */
    public Optional<TermBounds> termBounds() {
        return Optional.ofNullable(termBounds);
    }

    /** Whether the product revolves - a line with a limit and no term. */
    public boolean revolving() {
        return termBounds == null;
    }

    /** A person's approval strictly above this amount needs a second underwriter (ADR-0089 section 4). */
    public Money fourEyesThreshold() {
        return fourEyesThreshold;
    }

    /** How long a decision request may stay open before it lapses, on the database clock. */
    public Duration requestValidity() {
        return requestValidity;
    }

    /** How long a decision stays valid - reserving exposure and consumable - on the database clock. */
    public Duration decisionValidity() {
        return decisionValidity;
    }

    /** How long collected credit evidence is retained. */
    public Period evidenceRetention() {
        return evidenceRetention;
    }

    private static Money euros(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, CurrencyCode.of("EUR"));
    }

    /**
     * A term's bounds in whole months, both inclusive.
     *
     * @param minimumMonths the shortest term, at least one month
     * @param maximumMonths the longest term, at least the shortest
     */
    public record TermBounds(int minimumMonths, int maximumMonths) {

        public TermBounds {
            if (minimumMonths < 1 || maximumMonths < minimumMonths) {
                throw new IllegalArgumentException(
                        "term bounds must be at least one month and ordered: " + minimumMonths + ".." + maximumMonths);
            }
        }
    }
}
