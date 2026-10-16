package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Period;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The credit products a decision may be requested for (ADR-0084 section 6, PHASE_10_PLAN.md section 12.1; the two
 * buy-now-pay-later products since `P12-TSK-001`, ADR-0102 point 6, PHASE_12_PLAN.md section 12.1).
 *
 * <p><strong>Closed.</strong> A product is a reviewed code change with its migration, never a string; an unknown product is
 * {@code 422 credit.ProductNotOffered} and an amount or term outside the bounds {@code 422 credit.AmountOutOfRange} (both
 * {@code P10-TSK-014}'s). Phase 11 builds its priced products on the lending family; Phase 12's {@code bnpl} on the BNPL
 * family.
 *
 * <p><strong>Every member declares every property</strong>, and the declarations are checked as the class loads, so a
 * member that disagreed with itself - a bound in another currency, a threshold outside the bounds, a non-positive validity,
 * a term shape without its bounds - would fail every use of the type:
 * <ul>
 *   <li>its {@link Family}: who opens a request for it ({@link RequestOrigin}), who takes an approval up
 *       ({@link ConsumerKind}), and whether a person may review it;</li>
 *   <li>its currency, and its amount bounds in it, as {@link Money} - never floating point ({@code INV-CRD-12});</li>
 *   <li>its {@link TermShape}: no term (a revolving line), a term in months within its {@link TermBounds} (a range, or an
 *       allowed set), or a fixed plan of a declared number of instalments that takes no term;</li>
 *   <li>its four-eyes threshold: a person's approval above it waits for a second underwriter (ADR-0089 section 4);</li>
 *   <li>its request validity - how long a decision request may stay open (G6) - and its decision validity - how long an
 *       approval reserves exposure and may be consumed - both judged on the database clock by the tasks that use them;
 *       a BNPL product's are an hour and a day, so an offer cannot outlive its checkout (OQ-18, B8);</li>
 *   <li>its evidence retention: how long collected credit evidence is kept (ADR-0085).</li>
 * </ul>
 */
public enum CreditProduct {

    /** An amortising instalment loan: EUR 500.00 to 25,000.00 over 6 to 60 months. */
    PERSONAL_LOAN(
            Family.LENDING,
            euros(500_00),
            euros(25_000_00),
            TermShape.MONTHS,
            TermBounds.between(6, 60),
            0,
            euros(10_000_00),
            Duration.ofDays(7),
            Duration.ofDays(30),
            Period.ofMonths(25)),

    /** A revolving line: a limit of EUR 250.00 to 5,000.00, with no term. */
    CREDIT_LINE(
            Family.LENDING,
            euros(250_00),
            euros(5_000_00),
            TermShape.NONE,
            null,
            0,
            euros(2_500_00),
            Duration.ofDays(7),
            Duration.ofDays(30),
            Period.ofMonths(25)),

    /**
     * Pay-in-4 (ADR-0102, B1): a purchase of EUR 50.00 to 1,000.00 split into four instalments - the first at purchase,
     * then every 14 days - so it takes no term; its plan is fixed.
     */
    BNPL_PAY_IN_4(
            Family.BNPL,
            euros(50_00),
            euros(1_000_00),
            TermShape.FIXED_PLAN,
            null,
            4,
            euros(500_00),
            Duration.ofHours(1),
            Duration.ofDays(1),
            Period.ofMonths(25)),

    /** Pay-in-N (ADR-0102, B1): a purchase of EUR 200.00 to 5,000.00 over 3, 6 or 12 monthly instalments. */
    BNPL_PAY_IN_N(
            Family.BNPL,
            euros(200_00),
            euros(5_000_00),
            TermShape.MONTHS,
            TermBounds.oneOf(3, 6, 12),
            0,
            euros(2_500_00),
            Duration.ofHours(1),
            Duration.ofDays(1),
            Period.ofMonths(25));

    private final Family family;
    private final Money minimumAmount;
    private final Money maximumAmount;
    private final TermShape termShape;
    private final TermBounds termBounds;
    private final int planInstalments;
    private final Money fourEyesThreshold;
    private final Duration requestValidity;
    private final Duration decisionValidity;
    private final Period evidenceRetention;

    CreditProduct(
            Family family,
            Money minimumAmount,
            Money maximumAmount,
            TermShape termShape,
            TermBounds termBounds,
            int planInstalments,
            Money fourEyesThreshold,
            Duration requestValidity,
            Duration decisionValidity,
            Period evidenceRetention) {
        this.family = Objects.requireNonNull(family, "family");
        this.minimumAmount = Objects.requireNonNull(minimumAmount, "minimumAmount");
        this.maximumAmount = Objects.requireNonNull(maximumAmount, "maximumAmount");
        this.termShape = Objects.requireNonNull(termShape, "termShape");
        this.termBounds = termBounds;
        this.planInstalments = planInstalments;
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
        if ((termShape == TermShape.MONTHS) != (termBounds != null)) {
            throw new IllegalStateException(name() + ": a term in months has its bounds, and no other shape has any");
        }
        if (termShape == TermShape.FIXED_PLAN ? planInstalments < 2 : planInstalments != 0) {
            throw new IllegalStateException(name() + ": a fixed plan declares at least two instalments, no other shape any");
        }
        if (requestValidity.isNegative() || requestValidity.isZero()
                || decisionValidity.isNegative() || decisionValidity.isZero()) {
            throw new IllegalStateException(name() + ": the validities must be positive");
        }
        if (evidenceRetention.isNegative() || evidenceRetention.isZero()) {
            throw new IllegalStateException(name() + ": the evidence retention must be positive");
        }
    }

    /** The products of {@code family}, in declaration order. */
    public static List<CreditProduct> of(Family family) {
        Objects.requireNonNull(family, "family");
        return Arrays.stream(values()).filter(product -> product.family == family).toList();
    }

    /** The product's family - who opens a request for it, who takes its approval up, whether a person reviews it. */
    public Family family() {
        return family;
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

    /** The shape of the product's term - none, months within its bounds, or a fixed plan that takes none. */
    public TermShape termShape() {
        return termShape;
    }

    /** The term bounds in months - present exactly for a {@link TermShape#MONTHS} product. */
    public Optional<TermBounds> termBounds() {
        return Optional.ofNullable(termBounds);
    }

    /** A fixed plan's number of instalments - present exactly for a {@link TermShape#FIXED_PLAN} product. */
    public OptionalInt planInstalments() {
        return termShape == TermShape.FIXED_PLAN ? OptionalInt.of(planInstalments) : OptionalInt.empty();
    }

    /**
     * Whether the product revolves - a line with a limit and no term ({@link TermShape#NONE}). A fixed plan takes no term
     * either and is never a line: the shape, not the term's absence, says so.
     */
    public boolean revolving() {
        return termShape == TermShape.NONE;
    }

    /**
     * Whether a request for the product may be referred to a person (`P12-TSK-001`'s design, ADR-0102's Follow-up): a
     * lending product's may; a BNPL product's is decided automatically only - its request lives an hour, inside a checkout,
     * so a referral could never be worked in time, and its policy may therefore never refer ({@link CreditPolicy}).
     */
    public boolean referable() {
        return family.referable;
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
     * A product's family (`P12-TSK-001`): the lending products, which lending prices and services, and the
     * buy-now-pay-later products, which {@code bnpl} does. A family is credit's declaration, never inferred from a name.
     */
    public enum Family {

        /** The personal loan and the credit line: requested at credit's customer door, taken up by a loan agreement. */
        LENDING(true),

        /** Pay-in-4 and pay-in-N: requested by the BNPL application, taken up by a BNPL agreement, never referred. */
        BNPL(false);

        private final boolean referable;

        Family(boolean referable) {
            this.referable = referable;
        }
    }

    /**
     * The shape of a product's term (`P12-TSK-001`; ADR-0102 point 6) - replacing the boolean "revolving or not": a
     * request names a term exactly when its product's shape is {@link #MONTHS}.
     */
    public enum TermShape {

        /** No term - a revolving line. */
        NONE,

        /** A term in whole months, within the product's {@link TermBounds}. */
        MONTHS,

        /** A fixed plan of the product's declared instalments - the plan is the product's, so no term is sent. */
        FIXED_PLAN
    }

    /**
     * A term's bounds in whole months: a range, both ends inclusive, or - when {@code admittedMonths} is not empty - exactly
     * the months it lists (pay-in-N's 3, 6 or 12), whose least and greatest are then the range's ends.
     *
     * @param minimumMonths the shortest term, at least one month
     * @param maximumMonths the longest term, at least the shortest
     * @param admittedMonths empty for a range; else the admitted terms, ascending, each once
     */
    public record TermBounds(int minimumMonths, int maximumMonths, List<Integer> admittedMonths) {

        public TermBounds {
            Objects.requireNonNull(admittedMonths, "admittedMonths");
            admittedMonths = List.copyOf(admittedMonths);
            if (minimumMonths < 1 || maximumMonths < minimumMonths) {
                throw new IllegalArgumentException(
                        "term bounds must be at least one month and ordered: " + minimumMonths + ".." + maximumMonths);
            }
            if (!admittedMonths.isEmpty() && (!admittedMonths.equals(admittedMonths.stream().sorted().distinct().toList())
                    || admittedMonths.get(0) != minimumMonths
                    || admittedMonths.get(admittedMonths.size() - 1) != maximumMonths)) {
                throw new IllegalArgumentException(
                        "an admitted set is ascending, each term once, from the minimum to the maximum");
            }
        }

        /** A range of months, both ends inclusive. */
        public TermBounds(int minimumMonths, int maximumMonths) {
            this(minimumMonths, maximumMonths, List.of());
        }

        /** {@code minimumMonths} to {@code maximumMonths}, both inclusive. */
        public static TermBounds between(int minimumMonths, int maximumMonths) {
            return new TermBounds(minimumMonths, maximumMonths);
        }

        /** Exactly {@code months}, ascending. */
        public static TermBounds oneOf(int... months) {
            if (months.length == 0) {
                throw new IllegalArgumentException("an admitted set holds at least one term");
            }
            List<Integer> admitted = Arrays.stream(months).boxed().toList();
            return new TermBounds(admitted.get(0), admitted.get(admitted.size() - 1), admitted);
        }

        /** Whether a term of {@code months} is within these bounds. */
        public boolean admits(int months) {
            return months >= minimumMonths && months <= maximumMonths
                    && (admittedMonths.isEmpty() || admittedMonths.contains(months));
        }

        /** The bounds in words, for a refusal: {@code 6 to 60 months}, or {@code 3, 6 or 12 months}. */
        public String describe() {
            if (admittedMonths.isEmpty()) {
                return minimumMonths + " to " + maximumMonths + " months";
            }
            if (admittedMonths.size() == 1) {
                return admittedMonths.get(0) + " months";
            }
            String head = admittedMonths.subList(0, admittedMonths.size() - 1).stream().map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return head + " or " + admittedMonths.get(admittedMonths.size() - 1) + " months";
        }
    }
}
