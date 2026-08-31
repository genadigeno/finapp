package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Allocation must lose nothing. An absorbed residual is money creation or destruction at
 * scale ({@code INV-BAL-03}), and it is invisible in any test that only checks one example.
 *
 * <p>The acceptance criterion for P0-TSK-010 is a universal statement — "splitting any amount
 * across any n reassembles to exactly the original" — so the central tests here sweep amounts
 * and divisors rather than asserting a handful of cases.
 */
class MoneyAllocationTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    // -----------------------------------------------------------------
    // The property that matters
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"USD", "JPY", "BHD", "CLF"})
    @DisplayName("every amount split across every n sums back to exactly the original")
    void evenAllocationNeverLosesOrCreatesValue(String code) {
        CurrencyCode currency = CurrencyCode.of(code);

        for (long amount = -500L; amount <= 500L; amount++) {
            for (int parts = 1; parts <= 40; parts++) {
                Money original = Money.ofMinorUnits(amount, currency);

                List<Money> allocation = original.allocateEvenly(parts);

                assertThat(allocation).hasSize(parts);
                assertThat(sumOf(allocation, currency))
                        .as("%s split %d ways must sum back to itself", original, parts)
                        .isEqualTo(original);
            }
        }
    }

    @Test
    @DisplayName("the same holds at the extremes of the representable range")
    void evenAllocationHoldsAtTheLimits() {
        for (long amount : new long[] {Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1}) {
            for (int parts : new int[] {1, 2, 3, 7, 97, 1000}) {
                Money original = Money.ofMinorUnits(amount, USD);

                assertThat(sumOfMinorUnits(original.allocateEvenly(parts)))
                        .as("%d split %d ways", amount, parts)
                        .isEqualTo(amount);
            }
        }
    }

    @Test
    @DisplayName("weighted allocation sums back to the original, over a randomised sweep")
    void weightedAllocationNeverLosesOrCreatesValue() {
        // Seeded so a failure is reproducible: an allocation defect that only appears on one
        // seed is a defect that must stay findable.
        Random random = new Random(20260831L);

        for (int iteration = 0; iteration < 2_000; iteration++) {
            long amount = random.nextLong(-1_000_000L, 1_000_000L);
            long[] weights = new long[1 + random.nextInt(8)];
            boolean anyNonZero = false;
            for (int i = 0; i < weights.length; i++) {
                weights[i] = random.nextInt(6); // zero weights included deliberately
                anyNonZero |= weights[i] > 0L;
            }
            if (!anyNonZero) {
                weights[0] = 1L;
            }

            Money original = Money.ofMinorUnits(amount, USD);
            List<Money> allocation = original.allocateByWeights(weights);

            assertThat(allocation).hasSize(weights.length);
            assertThat(sumOf(allocation, USD))
                    .as("%s allocated by weights %s", original, java.util.Arrays.toString(weights))
                    .isEqualTo(original);
        }
    }

    // -----------------------------------------------------------------
    // Distribution behaviour
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the remainder goes to the earliest parts, not into thin air")
    void remainderIsDistributedNotDiscarded() {
        Money oneDollar = Money.ofMinorUnits(100L, USD);

        assertThat(oneDollar.allocateEvenly(3))
                .extracting(Money::minorUnits)
                .containsExactly(34L, 33L, 33L);
    }

    @Test
    @DisplayName("negative amounts split symmetrically")
    void negativeAmountsSplitSymmetrically() {
        Money oweADollar = Money.ofMinorUnits(-100L, USD);

        assertThat(oweADollar.allocateEvenly(3))
                .extracting(Money::minorUnits)
                .containsExactly(-34L, -33L, -33L);
    }

    @Test
    @DisplayName("splits a currency with no minor unit")
    void splitsZeroDecimalCurrency() {
        assertThat(Money.ofMinorUnits(100L, JPY).allocateEvenly(3))
                .extracting(Money::minorUnits)
                .containsExactly(34L, 33L, 33L);
    }

    @Test
    @DisplayName("an exact split gives equal parts and no remainder")
    void exactSplitIsEven() {
        assertThat(Money.ofMinorUnits(900L, USD).allocateEvenly(3))
                .extracting(Money::minorUnits)
                .containsExactly(300L, 300L, 300L);
    }

    @Test
    @DisplayName("splitting into one part returns the whole amount")
    void singlePartIsIdentity() {
        Money amount = Money.ofMinorUnits(12_345L, USD);

        assertThat(amount.allocateEvenly(1)).containsExactly(amount);
    }

    @Test
    @DisplayName("splitting zero gives zeros")
    void zeroSplitsToZeros() {
        assertThat(Money.zero(USD).allocateEvenly(4))
                .containsExactly(
                        Money.zero(USD), Money.zero(USD), Money.zero(USD), Money.zero(USD));
    }

    @Test
    @DisplayName("weights divide proportionally, remainder to the largest discarded fraction")
    void weightsDivideProportionally() {
        // 100 across 1:1:1 is 33.33 each with 1 unit over; the fractions are equal, so the
        // tie-break gives it to the earliest part.
        assertThat(Money.ofMinorUnits(100L, USD).allocateByWeights(1L, 1L, 1L))
                .extracting(Money::minorUnits)
                .containsExactly(34L, 33L, 33L);

        assertThat(Money.ofMinorUnits(100L, USD).allocateByWeights(70L, 30L))
                .extracting(Money::minorUnits)
                .containsExactly(70L, 30L);

        // 3-way 1:2:3 of 100 = 16.66, 33.33, 50.0 -> 16, 33, 50 leaves 1 unit, whose largest
        // discarded fraction is the first part (0.666).
        assertThat(Money.ofMinorUnits(100L, USD).allocateByWeights(1L, 2L, 3L))
                .extracting(Money::minorUnits)
                .containsExactly(17L, 33L, 50L);
    }

    @Test
    @DisplayName("a zero weight receives nothing, including no remainder unit")
    void zeroWeightReceivesNothing() {
        List<Money> allocation = Money.ofMinorUnits(100L, USD).allocateByWeights(1L, 0L, 2L);

        assertThat(allocation.get(1)).isEqualTo(Money.zero(USD));
        assertThat(sumOf(allocation, USD)).isEqualTo(Money.ofMinorUnits(100L, USD));
    }

    @Test
    @DisplayName("weighted allocation is deterministic — a replay gives the same split")
    void weightedAllocationIsDeterministic() {
        Money amount = Money.ofMinorUnits(1_000_003L, USD);
        long[] weights = {7L, 11L, 13L, 17L};

        assertThat(amount.allocateByWeights(weights)).isEqualTo(amount.allocateByWeights(weights));
    }

    @Test
    @DisplayName("weights far larger than the amount still sum correctly")
    void hugeWeightsDoNotOverflow() {
        // amount * weight overflows a long here; the implementation must not.
        Money amount = Money.ofMinorUnits(1_000_000L, USD);

        List<Money> allocation = amount.allocateByWeights(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4);

        assertThat(sumOf(allocation, USD)).isEqualTo(amount);
    }

    // -----------------------------------------------------------------
    // Rejections
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("rejects a non-positive number of parts")
    void rejectsNonPositiveParts(int parts) {
        assertThatThrownBy(() -> Money.ofMinorUnits(100L, USD).allocateEvenly(parts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    @DisplayName("rejects empty, negative and all-zero weights")
    void rejectsMeaninglessWeights() {
        Money amount = Money.ofMinorUnits(100L, USD);

        assertThatThrownBy(amount::allocateByWeights)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> amount.allocateByWeights(1L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> amount.allocateByWeights(0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-zero");
    }

    @Test
    @DisplayName("the returned allocation cannot be modified")
    void allocationIsUnmodifiable() {
        List<Money> allocation = Money.ofMinorUnits(100L, USD).allocateEvenly(2);

        assertThatThrownBy(() -> allocation.add(Money.zero(USD)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("parts keep the currency and scale of the original")
    void partsPreserveCurrencyAndScale() {
        Money historical = Money.ofPersisted(1000L, BHD, 3);

        assertThat(historical.allocateEvenly(3))
                .allSatisfy(
                        part -> {
                            assertThat(part.currency()).isEqualTo(BHD);
                            assertThat(part.scale()).isEqualTo(3);
                        });
    }

    @Test
    @DisplayName("the two allocations are named, not overloaded on argument width")
    void allocationsAreNotOverloaded() {
        // As allocate(int) and allocate(long...) these resolved silently by the width of the
        // literal: allocate(3) split three ways, allocate(3L) returned the whole amount as a
        // single part. Counts are routinely held in a long, so that is a money bug the
        // compiler accepts and no assertion notices. This guards against reintroducing it.
        assertThat(Money.class.getMethods())
                .as("no method named 'allocate' may exist — the name is ambiguous by width")
                .noneMatch(method -> method.getName().equals("allocate"));
    }

    // -----------------------------------------------------------------

    private static Money sumOf(List<Money> parts, CurrencyCode currency) {
        Money total = Money.zero(currency);
        for (Money part : parts) {
            total = total.plus(part);
        }
        return total;
    }

    /** Sums without {@link Money#plus} so the limit tests are not themselves overflow-bound. */
    private static long sumOfMinorUnits(List<Money> parts) {
        long total = 0L;
        for (Money part : parts) {
            total += part.minorUnits();
        }
        return total;
    }
}
