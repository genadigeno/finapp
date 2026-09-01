package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The algebraic laws {@link Money} must obey, asserted over generated values rather than
 * chosen ones (P0-TST-001).
 *
 * <p><strong>What this adds over {@link MoneyTest}.</strong> {@code MoneyTest} already covers
 * currency mismatch, scale mismatch, overflow, negatives and zero thoroughly — by example. An
 * example proves an implementation works for the value the author thought of. It cannot
 * distinguish a correct implementation from one that is correct only for small positive
 * amounts, which is precisely the shape of monetary defect that survives review: commutativity
 * was asserted here on {@code 1.11 + 2.22 + 3.33} and nothing else, and that example holds
 * under implementations that break at the boundaries of {@code long}.
 *
 * <p><strong>Why laws rather than more examples.</strong> A ledger is built out of these
 * properties, not out of individual sums. If addition is not associative, two postings that
 * balance in one grouping do not balance in another. If {@code (a + b) - b} is not {@code a},
 * a reversal does not restore the original position. If {@code compareTo} disagrees with
 * {@code equals}, a sorted balance report and a lookup disagree about the same amount. These
 * are the guarantees the ledger will silently assume from Phase 3 onward.
 *
 * <p><strong>Generation, and why it is seeded.</strong> A failing case must stay findable, so
 * the seed is fixed. Amounts are drawn across magnitude classes — zero, small, mid-range and
 * the extremes of {@code long} — because a generator that only produces small values tests the
 * easy half of every law, and one that only produces extremes makes every operation overflow
 * and tests nothing at all.
 *
 * <p><strong>Vacuity is the failure mode being guarded against.</strong> Every operation here
 * may legitimately throw, so a law phrased as "the results agree" is satisfied trivially when
 * both sides throw. Each law therefore counts how many trials actually produced an amount and
 * asserts that count is substantial. Without that, tightening the generator — or a bug that
 * made every operation throw — would leave a green suite that had checked nothing.
 */
class MoneyPropertiesTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    /** The three minor-unit widths P0-TST-001 names: 0, 2 and 3 decimals. */
    private static final List<CurrencyCode> CURRENCIES = List.of(JPY, USD, BHD);

    private static final int TRIALS = 20_000;

    /**
     * The minimum fraction of trials that must produce an amount rather than a rejection. Set
     * well below what the generator actually achieves, so it fails on a collapse in coverage
     * rather than on ordinary variation.
     */
    private static final double MINIMUM_DEFINED_FRACTION = 0.5;

    // -----------------------------------------------------------------
    // Additive laws
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("addition obeys the laws a ledger assumes")
    class AdditiveLaws {

        @Test
        @DisplayName("addition is commutative for every generated pair, including overflow")
        void commutative() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);

                // Comparing outcomes rather than results: an overflow must be symmetric too.
                // If a+b throws and b+a does not, the order of two postings would decide
                // whether a transaction is accepted.
                assertThat(outcomeOf(() -> a.plus(b)))
                        .as("%s + %s must equal %s + %s", a, b, b, a)
                        .isEqualTo(outcomeOf(() -> b.plus(a)));

                if (isDefined(() -> a.plus(b))) {
                    defined++;
                }
            }
            assertMeaningfulCoverage(defined, "commutativity");
        }

        @Test
        @DisplayName("addition is associative wherever both groupings are defined")
        void associative() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);
                Money c = anyAmount(random, currency);

                Money left = resultOrNull(() -> a.plus(b).plus(c));
                Money right = resultOrNull(() -> a.plus(b.plus(c)));

                // Grouping may legitimately decide whether an intermediate overflows, so the
                // law is asserted where both are defined. That is not a weakening: an
                // intermediate that overflows is rejected, never silently wrapped, and
                // MoneyTest covers that directly.
                if (left != null && right != null) {
                    assertThat(left).as("(%s + %s) + %s must equal %s + (%s + %s)", a, b, c, a, b, c).isEqualTo(right);
                    defined++;
                }
            }
            assertMeaningfulCoverage(defined, "associativity");
        }

        @Test
        @DisplayName("zero is the additive identity at every currency scale")
        void zeroIsIdentity() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);

                // Never throws: adding zero cannot overflow. Asserted unconditionally so a
                // regression that made it throw would fail rather than be skipped as undefined.
                assertThat(a.plus(Money.zero(currency))).as("%s + zero", a).isEqualTo(a);
                assertThat(a.minus(Money.zero(currency))).as("%s - zero", a).isEqualTo(a);
            }
        }

        @Test
        @DisplayName("an amount and its negation sum to zero")
        void negationIsTheAdditiveInverse() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);

                Money negated = resultOrNull(a::negated);
                if (negated == null) {
                    // Long.MIN_VALUE has no representable negation; rejecting it is the
                    // documented behaviour, not a gap in the law.
                    continue;
                }
                assertThat(a.plus(negated)).as("%s + (-%s)", a, a).isEqualTo(Money.zero(currency));
                defined++;
            }
            assertMeaningfulCoverage(defined, "additive inverse");
        }

        @Test
        @DisplayName("subtracting then adding the same amount restores the original")
        void reversalRestoresTheOriginal() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);

                // This is the reversal property in arithmetic form. INV-REV-01 requires a
                // reversal to restore the original position exactly; if this law does not
                // hold, no compensating entry can be trusted to.
                Money sum = resultOrNull(() -> a.plus(b));
                if (sum == null) {
                    continue;
                }
                assertThat(sum.minus(b)).as("(%s + %s) - %s", a, b, b).isEqualTo(a);
                defined++;
            }
            assertMeaningfulCoverage(defined, "reversal round-trip");
        }

        @Test
        @DisplayName("subtraction agrees with adding the negation")
        void subtractionIsAdditionOfTheNegation() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);

                Money difference = resultOrNull(() -> a.minus(b));
                Money viaNegation = resultOrNull(() -> a.plus(b.negated()));

                if (difference != null && viaNegation != null) {
                    assertThat(difference).as("%s - %s", a, b).isEqualTo(viaNegation);
                    defined++;
                }
            }
            assertMeaningfulCoverage(defined, "subtraction as negated addition");
        }

        @Test
        @DisplayName("multiplication by a whole number agrees with repeated addition")
        void multiplicationIsRepeatedAddition() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                int factor = random.nextInt(0, 12);

                Money multiplied = resultOrNull(() -> a.times(factor));
                Money accumulated = Money.zero(currency);
                boolean accumulationDefined = true;
                for (int i = 0; i < factor && accumulationDefined; i++) {
                    Money running = accumulated;
                    Money next = resultOrNull(() -> running.plus(a));
                    if (next == null) {
                        accumulationDefined = false;
                    } else {
                        accumulated = next;
                    }
                }

                if (multiplied != null && accumulationDefined) {
                    assertThat(multiplied).as("%s x %d", a, factor).isEqualTo(accumulated);
                    defined++;
                }
            }
            assertMeaningfulCoverage(defined, "multiplication as repeated addition");
        }
    }

    // -----------------------------------------------------------------
    // Exactness
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("arithmetic is exact, not merely close")
    class Exactness {

        @Test
        @DisplayName("the decimal value of a sum equals the sum of the decimal values")
        void additionAgreesWithBigDecimal() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);

                Money sum = resultOrNull(() -> a.plus(b));
                if (sum == null) {
                    continue;
                }
                // BigDecimal is an independent implementation of exact decimal arithmetic, so
                // agreeing with it is a stronger statement than agreeing with another Money
                // operation: a shared defect in the minor-unit path would not survive it.
                assertThat(sum.toBigDecimal())
                        .as("%s + %s in decimal", a, b)
                        .isEqualByComparingTo(a.toBigDecimal().add(b.toBigDecimal()));
                defined++;
            }
            assertMeaningfulCoverage(defined, "decimal agreement");
        }

        @Test
        @DisplayName("an amount round-trips through its decimal form unchanged")
        void decimalRoundTripIsLossless() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);

                assertThat(Money.of(a.toBigDecimal(), currency)).as("round-trip of %s", a).isEqualTo(a);
            }
        }
    }

    // -----------------------------------------------------------------
    // Ordering and equality
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("ordering and equality are consistent")
    class OrderingAndEquality {

        @Test
        @DisplayName("comparison is antisymmetric and agrees with equality")
        void comparisonIsAntisymmetricAndConsistentWithEquals() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);

                assertThat(Integer.signum(a.compareTo(b)))
                        .as("sign of %s vs %s must invert", a, b)
                        .isEqualTo(-Integer.signum(b.compareTo(a)));

                // Consistency with equals is what lets an amount be used as a map key and a
                // sort key without the two disagreeing.
                assertThat(a.compareTo(b) == 0)
                        .as("compareTo == 0 must mean equals for %s and %s", a, b)
                        .isEqualTo(a.equals(b));
            }
        }

        @Test
        @DisplayName("comparison is transitive")
        void comparisonIsTransitive() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money b = anyAmount(random, currency);
                Money c = anyAmount(random, currency);

                if (a.compareTo(b) <= 0 && b.compareTo(c) <= 0) {
                    assertThat(a.compareTo(c)).as("%s <= %s <= %s", a, b, c).isLessThanOrEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("equal amounts have equal hash codes, and equality is symmetric")
        void equalityContractHolds() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money a = anyAmount(random, currency);
                Money copy = Money.ofPersisted(a.minorUnits(), a.currency(), a.scale());
                Money b = anyAmount(random, currency);

                assertThat(a).as("reflexive").isEqualTo(a);
                assertThat(copy).as("value equality").isEqualTo(a);
                assertThat(copy.hashCode()).as("equal values, equal hash").isEqualTo(a.hashCode());
                assertThat(a.equals(b)).as("symmetric").isEqualTo(b.equals(a));
            }
        }
    }

    // -----------------------------------------------------------------
    // Currency separation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("no generated pair of different currencies ever combines")
    void differentCurrenciesNeverCombine() {
        Random random = seeded();
        int rejected = 0;

        for (int trial = 0; trial < TRIALS; trial++) {
            CurrencyCode left = anyCurrency(random);
            CurrencyCode right = anyCurrency(random);
            if (left.equals(right)) {
                continue;
            }
            Money a = anyAmount(random, left);
            Money b = anyAmount(random, right);

            // Asserted as an exception *type*, not merely "throws": overflow throwing here
            // instead would satisfy a laxer assertion while meaning something entirely
            // different, and the whole point of INV-MON-04 is that the currency is checked.
            assertThat(outcomeOf(() -> a.plus(b))).isEqualTo(CurrencyMismatchException.class);
            assertThat(outcomeOf(() -> a.minus(b))).isEqualTo(CurrencyMismatchException.class);
            assertThat(outcomeOf(() -> a.compareTo(b))).isEqualTo(CurrencyMismatchException.class);
            rejected++;
        }
        assertMeaningfulCoverage(rejected, "cross-currency rejection");
    }

    // -----------------------------------------------------------------
    // Rounding
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("rounding stays within one minor unit and respects its policy")
    class Rounding {

        @Test
        @DisplayName("a rounded amount never moves by a whole minor unit or more")
        void roundingIsBounded() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                BigDecimal raw = anyDecimal(random);
                RoundingPolicy policy = anyPolicy(random);

                Money rounded = resultOrNull(() -> Money.of(raw, currency, policy));
                if (rounded == null) {
                    continue;
                }
                BigDecimal oneMinorUnit = BigDecimal.ONE.movePointLeft(rounded.scale());
                BigDecimal drift = rounded.toBigDecimal().subtract(raw).abs();

                // Rounding that moves a value by a whole minor unit is not rounding; it is a
                // scaling defect, and at ledger volume it is money creation.
                assertThat(drift)
                        .as("%s rounded %s to %s moved by %s", raw, policy.policyName(), rounded, drift)
                        .isLessThan(oneMinorUnit);
                assertThat(rounded.scale()).as("result takes the currency's scale").isEqualTo(currency.minorUnits());
                defined++;
            }
            assertMeaningfulCoverage(defined, "rounding bound");
        }

        @Test
        @DisplayName("each policy rounds in the direction that defines it")
        void eachPolicyRoundsInItsOwnDirection() {
            Random random = seeded();
            int defined = 0;

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                BigDecimal raw = anyDecimal(random);
                RoundingPolicy policy = anyPolicy(random);

                Money rounded = resultOrNull(() -> Money.of(raw, currency, policy));
                if (rounded == null) {
                    continue;
                }
                BigDecimal result = rounded.toBigDecimal();
                BigDecimal halfMinorUnit =
                        BigDecimal.ONE.movePointLeft(currency.minorUnits()).divide(BigDecimal.valueOf(2L));

                // Each policy is pinned by the property that defines it, stated without
                // reference to RoundingMode. An earlier version of this test bracketed every
                // policy between FLOOR and CEILING computed through Money itself; when a
                // deliberate break made every policy round CEILING, the bracket collapsed onto
                // the broken value and the assertion held. An oracle that shares the defect is
                // not an oracle.
                switch (policy) {
                    case FLOOR ->
                            assertThat(result)
                                    .as("FLOOR of %s must not exceed it", raw)
                                    .isLessThanOrEqualTo(raw);
                    case CEILING ->
                            assertThat(result)
                                    .as("CEILING of %s must not fall below it", raw)
                                    .isGreaterThanOrEqualTo(raw);
                    case TOWARDS_ZERO ->
                            assertThat(result.abs())
                                    .as("TOWARDS_ZERO of %s must not grow in magnitude", raw)
                                    .isLessThanOrEqualTo(raw.abs());
                    case AWAY_FROM_ZERO ->
                            assertThat(result.abs())
                                    .as("AWAY_FROM_ZERO of %s must not shrink in magnitude", raw)
                                    .isGreaterThanOrEqualTo(raw.abs());
                    case HALF_EVEN, HALF_UP ->
                            assertThat(result.subtract(raw).abs())
                                    .as("%s of %s must pick the nearer neighbour", policy.policyName(), raw)
                                    .isLessThanOrEqualTo(halfMinorUnit);
                }
                defined++;
            }
            assertMeaningfulCoverage(defined, "policy direction");
        }

        @Test
        @DisplayName("rounding an already-rounded amount changes nothing, under any policy")
        void roundingIsIdempotent() {
            Random random = seeded();

            for (int trial = 0; trial < TRIALS; trial++) {
                CurrencyCode currency = anyCurrency(random);
                Money exact = anyAmount(random, currency);
                RoundingPolicy policy = anyPolicy(random);

                assertThat(Money.of(exact.toBigDecimal(), currency, policy))
                        .as("re-rounding %s under %s", exact, policy.policyName())
                        .isEqualTo(exact);
            }
        }
    }

    // -----------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------

    /** Fixed so a failing case stays reproducible; see {@link MoneyAllocationTest}. */
    private static Random seeded() {
        return new Random(20260901L);
    }

    private static CurrencyCode anyCurrency(Random random) {
        return CURRENCIES.get(random.nextInt(CURRENCIES.size()));
    }

    private static RoundingPolicy anyPolicy(Random random) {
        RoundingPolicy[] policies = RoundingPolicy.values();
        return policies[random.nextInt(policies.length)];
    }

    /**
     * An amount drawn from a magnitude class rather than uniformly over {@code long}.
     *
     * <p>A uniform draw over {@code long} makes almost every addition overflow, so every law
     * would be satisfied by both sides throwing and nothing would be tested. A draw confined to
     * small values would never reach the boundary where overflow handling lives. Mixing the
     * classes exercises both, and the coverage assertions confirm the mix actually does.
     */
    private static Money anyAmount(Random random, CurrencyCode currency) {
        int magnitudeClass = random.nextInt(10);
        long minorUnits =
                switch (magnitudeClass) {
                    case 0 -> 0L;
                    case 1, 2, 3 -> random.nextLong(-1_000L, 1_000L);
                    case 4, 5, 6 -> random.nextLong(-1_000_000_000L, 1_000_000_000L);
                    case 7, 8 -> random.nextLong(Long.MIN_VALUE / 2L, Long.MAX_VALUE / 2L);
                    default -> extremeValue(random);
                };
        return Money.ofMinorUnits(minorUnits, currency);
    }

    /** The values where overflow handling either works or silently wraps. */
    private static long extremeValue(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Long.MAX_VALUE;
            case 1 -> Long.MIN_VALUE;
            case 2 -> Long.MAX_VALUE - random.nextInt(1_000);
            default -> Long.MIN_VALUE + random.nextInt(1_000);
        };
    }

    /** A decimal with more precision than any test currency, so rounding is actually needed. */
    private static BigDecimal anyDecimal(Random random) {
        return BigDecimal.valueOf(random.nextLong(-1_000_000_000L, 1_000_000_000L), random.nextInt(0, 7));
    }

    // -----------------------------------------------------------------
    // Outcome capture
    // -----------------------------------------------------------------

    /**
     * The result of an operation, or the type of the exception it raised.
     *
     * <p>Comparing outcomes rather than results is what lets a law cover the rejecting cases
     * too. "Both threw" is only an acceptable way to satisfy a law when it is the *same*
     * rejection, which comparing types enforces and a bare {@code assertThatThrownBy} would not.
     */
    private static Object outcomeOf(Supplier<?> operation) {
        try {
            return operation.get();
        } catch (MonetaryException e) {
            return e.getClass();
        }
    }

    private static Money resultOrNull(Supplier<Money> operation) {
        try {
            return operation.get();
        } catch (MonetaryException e) {
            return null;
        }
    }

    private static boolean isDefined(Supplier<Money> operation) {
        return resultOrNull(operation) != null;
    }

    /**
     * Fails if a law was satisfied mostly by rejection rather than by computation.
     *
     * <p>Without this, narrowing the generator or a defect that made every operation throw
     * would leave every property green while checking nothing — the same vacuity that the
     * architecture rules' coverage guards exist to prevent.
     */
    private static void assertMeaningfulCoverage(int defined, String law) {
        assertThat(defined)
                .as(
                        "%s was only exercised on %d of %d trials; a law satisfied mostly by "
                                + "rejection has not been tested",
                        law, defined, TRIALS)
                .isGreaterThan((int) (TRIALS * MINIMUM_DEFINED_FRACTION));
    }
}
