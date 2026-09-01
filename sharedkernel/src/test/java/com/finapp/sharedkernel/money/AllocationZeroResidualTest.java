package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code INV-BAL-03} — value is neither created nor destroyed by allocation (P0-TST-002).
 *
 * <p><strong>What this adds over {@link MoneyAllocationTest}.</strong> That class already
 * sweeps every amount in −500..500 across 1..40 parts for four currencies, and a mutation to
 * naive division is caught by eight of its tests. Two things it does not do: it stops at 40
 * parts where this task's criterion says 1..100, and its amounts are a dense band around zero
 * rather than values drawn from the whole representable range. A hundred-way split is not
 * exotic — it is a payment schedule, an instalment plan, or a fee spread across a settlement
 * batch — and the residual grows with the number of parts, so the untested half of the range is
 * the half where more is at stake.
 *
 * <p><strong>Why an indivisible remainder must be forced, not hoped for.</strong> Zero residual
 * is trivially true whenever an amount divides exactly: every part is {@code amount / parts} and
 * even a naive allocator that discards the remainder is correct, because there is none. A
 * randomised test that happened to generate mostly divisible cases would therefore pass over a
 * broken allocator. Both sweeps here count the trials that actually had a remainder to
 * distribute and fail if that count is not substantial — the same vacuity guard the property
 * tests in {@link MoneyPropertiesTest} carry.
 *
 * <p><strong>Evenness is asserted as well as totality.</strong> Summing back to the original is
 * necessary but not sufficient: an allocator that handed the entire remainder to the first part
 * would satisfy it while producing a split nobody asked for. The parts of an even allocation
 * must differ from each other by at most one minor unit, which is what "as evenly as the
 * currency allows" means.
 */
class AllocationZeroResidualTest {

    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.of("JPY"), CurrencyCode.of("USD"), CurrencyCode.of("BHD"));

    /** The criterion's range: every part count from 1 to 100 inclusive. */
    private static final int MAX_PARTS = 100;

    private static final int AMOUNTS_PER_CURRENCY = 120;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("any amount split across any 1..100 parts sums back to exactly the original")
    void evenAllocationLosesNothingAcrossTheWholeRange() {
        Random random = seeded();
        int trials = 0;
        int withRemainder = 0;

        for (CurrencyCode currency : CURRENCIES) {
            for (int i = 0; i < AMOUNTS_PER_CURRENCY; i++) {
                Money original = anyAmount(random, currency);

                for (int parts = 1; parts <= MAX_PARTS; parts++) {
                    List<Money> allocation = original.allocateEvenly(parts);

                    assertThat(allocation).as("%s split %d ways", original, parts).hasSize(parts);
                    assertThat(sumOf(allocation, currency))
                            .as("%s split %d ways must sum back to itself", original, parts)
                            .isEqualTo(original);

                    trials++;
                    if (original.minorUnits() % parts != 0L) {
                        withRemainder++;
                    }
                }
            }
        }
        assertRemainderWasExercised(withRemainder, trials, "even allocation");
    }

    @Test
    @DisplayName("the parts of an even split differ by at most one minor unit")
    void evenAllocationIsActuallyEven() {
        Random random = seeded();

        for (CurrencyCode currency : CURRENCIES) {
            for (int i = 0; i < AMOUNTS_PER_CURRENCY; i++) {
                Money original = anyAmount(random, currency);

                for (int parts = 1; parts <= MAX_PARTS; parts++) {
                    List<Money> allocation = original.allocateEvenly(parts);

                    long smallest = Long.MAX_VALUE;
                    long largest = Long.MIN_VALUE;
                    for (Money part : allocation) {
                        smallest = Math.min(smallest, part.minorUnits());
                        largest = Math.max(largest, part.minorUnits());
                    }
                    // Summing back correctly does not make a split even: handing the whole
                    // remainder to one part would also sum back correctly.
                    assertThat(largest - smallest)
                            .as("%s split %d ways spread from %d to %d", original, parts, smallest, largest)
                            .isLessThanOrEqualTo(1L);
                }
            }
        }
    }

    @Test
    @DisplayName("weighted allocation across up to 100 weights loses nothing either")
    void weightedAllocationLosesNothingAcrossTheWholeRange() {
        Random random = seeded();
        int trials = 0;
        int withRemainder = 0;

        for (int iteration = 0; iteration < 4_000; iteration++) {
            CurrencyCode currency = CURRENCIES.get(random.nextInt(CURRENCIES.size()));
            Money original = anyAmount(random, currency);
            long[] weights = anyWeights(random);

            List<Money> allocation = original.allocateByWeights(weights);

            assertThat(allocation).hasSize(weights.length);
            assertThat(sumOf(allocation, currency))
                    .as("%s allocated across %d weights must sum back to itself", original, weights.length)
                    .isEqualTo(original);

            trials++;
            if (!dividesExactly(original.minorUnits(), weights)) {
                withRemainder++;
            }
        }
        assertRemainderWasExercised(withRemainder, trials, "weighted allocation");
    }

    @Test
    @DisplayName("a zero amount allocates to zeros, never to a phantom minor unit")
    void zeroAllocatesToZeros() {
        for (CurrencyCode currency : CURRENCIES) {
            for (int parts = 1; parts <= MAX_PARTS; parts++) {
                List<Money> allocation = Money.zero(currency).allocateEvenly(parts);

                assertThat(allocation)
                        .as("zero %s split %d ways", currency, parts)
                        .allMatch(Money::isZero);
            }
        }
    }

    // -----------------------------------------------------------------

    /**
     * Fails if the sweep mostly allocated amounts that divided exactly.
     *
     * <p>Those cases cannot distinguish a correct allocator from one that discards the
     * remainder, so a sweep dominated by them would report success over a broken allocator.
     */
    private static void assertRemainderWasExercised(int withRemainder, int trials, String sweep) {
        assertThat(withRemainder)
                .as(
                        "%s only had an indivisible remainder in %d of %d trials; an exactly "
                                + "divisible amount cannot distinguish a correct allocator from one "
                                + "that discards the remainder",
                        sweep, withRemainder, trials)
                .isGreaterThan(trials / 2);
    }

    /**
     * True when every part's exact share is a whole number of minor units.
     *
     * <p>Computed in {@link BigInteger} rather than {@code long}: {@code amount * weight}
     * overflows for realistic inputs, and an overflowing check would misreport which trials
     * were the interesting ones — which is the one thing this helper must not do, since its
     * only job is to tell the sweep whether it tested anything.
     */
    private static boolean dividesExactly(long minorUnits, long[] weights) {
        BigInteger amount = BigInteger.valueOf(minorUnits);
        BigInteger totalWeight = BigInteger.ZERO;
        for (long weight : weights) {
            totalWeight = totalWeight.add(BigInteger.valueOf(weight));
        }
        for (long weight : weights) {
            if (amount.multiply(BigInteger.valueOf(weight)).mod(totalWeight).signum() != 0) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------

    /** Seeded so a failing case stays reproducible, as in {@link MoneyAllocationTest}. */
    private static Random seeded() {
        return new Random(20260901L);
    }

    /**
     * An amount from across the representable range, not a band around zero.
     *
     * <p>Small amounts and large ones fail differently: a small amount split 100 ways is mostly
     * remainder, while a large one exercises the division itself.
     */
    private static Money anyAmount(Random random, CurrencyCode currency) {
        long minorUnits =
                switch (random.nextInt(8)) {
                    case 0 -> random.nextLong(-100L, 100L);
                    case 1, 2 -> random.nextLong(-10_000L, 10_000L);
                    case 3, 4 -> random.nextLong(-1_000_000_000L, 1_000_000_000L);
                    case 5, 6 -> random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
                    default -> random.nextBoolean() ? Long.MAX_VALUE : Long.MIN_VALUE;
                };
        return Money.ofMinorUnits(minorUnits, currency);
    }

    /** Between 1 and 100 weights, with zeros included deliberately. */
    private static long[] anyWeights(Random random) {
        long[] weights = new long[1 + random.nextInt(MAX_PARTS)];
        boolean anyNonZero = false;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = random.nextInt(5);
            anyNonZero |= weights[i] > 0L;
        }
        if (!anyNonZero) {
            weights[0] = 1L;
        }
        return weights;
    }

    /**
     * Sums through {@link Money#plus}, so the real arithmetic is exercised rather than bypassed.
     *
     * <p>Safe at the extremes: every part carries the sign of the original, so each partial sum
     * is bounded in magnitude by the original and cannot overflow on the way back to it.
     */
    private static Money sumOf(List<Money> parts, CurrencyCode currency) {
        Money total = Money.zero(currency);
        for (Money part : parts) {
            total = total.plus(part);
        }
        return total;
    }
}
