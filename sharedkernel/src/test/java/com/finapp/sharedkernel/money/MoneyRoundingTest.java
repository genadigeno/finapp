package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Rounding must be explicit and named ({@code INV-MON-03}). These cover the behaviour at 0-,
 * 2- and 3-minor-unit currencies that the invariant's verification method calls for, and the
 * negative-amount cases where the easy mistake lives.
 */
class MoneyRoundingTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    @Test
    @DisplayName("a rounding policy is required — there is no overload that picks one")
    void policyIsRequired() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.005"), USD, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("INV-MON-03");
    }

    @Test
    @DisplayName("the no-policy factory refuses to round rather than choosing a default")
    void withoutAPolicyItRefuses() {
        // This is the distinction the whole invariant rests on: no policy means no rounding,
        // never a silent default.
        assertThatExceptionOfType(InexactAmountException.class)
                .isThrownBy(() -> Money.of(new BigDecimal("1.005"), USD));
    }

    @ParameterizedTest(name = "{0} of 2.345 USD is {1}")
    @CsvSource({
        "HALF_EVEN, 2.34",
        "HALF_UP, 2.35",
        "TOWARDS_ZERO, 2.34",
        "AWAY_FROM_ZERO, 2.35",
        "FLOOR, 2.34",
        "CEILING, 2.35"
    })
    @DisplayName("each policy resolves a positive tie as documented")
    void positiveTies(RoundingPolicy policy, String expected) {
        assertThat(Money.of(new BigDecimal("2.345"), USD, policy).toBigDecimal())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "{0} of -2.345 USD is {1}")
    @CsvSource({
        "HALF_EVEN, -2.34",
        "HALF_UP, -2.35",
        "TOWARDS_ZERO, -2.34",
        "AWAY_FROM_ZERO, -2.35",
        "FLOOR, -2.35",
        "CEILING, -2.34"
    })
    @DisplayName("negative amounts separate TOWARDS_ZERO from FLOOR, which is the usual bug")
    void negativeAmountsDistinguishTruncationFromFloor(RoundingPolicy policy, String expected) {
        // TOWARDS_ZERO and FLOOR agree on every positive amount and disagree on every
        // negative one. Debits are negative, so a test suite using only positive amounts
        // would pass with either and be wrong in production for one of them.
        assertThat(Money.of(new BigDecimal("-2.345"), USD, policy).toBigDecimal())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("HALF_EVEN breaks ties to the even neighbour, in both directions")
    void halfEvenGoesToTheEvenNeighbour() {
        assertThat(Money.of(new BigDecimal("0.125"), USD, RoundingPolicy.HALF_EVEN).minorUnits())
                .isEqualTo(12L);
        assertThat(Money.of(new BigDecimal("0.135"), USD, RoundingPolicy.HALF_EVEN).minorUnits())
                .isEqualTo(14L);
    }

    @Test
    @DisplayName("HALF_UP is biased and HALF_EVEN is not, over repeated ties")
    void halfEvenDoesNotAccumulateBias() {
        // Ten ties at 0.005: HALF_UP gains a whole cent per tie; HALF_EVEN splits them.
        long halfUp = 0L;
        long halfEven = 0L;
        for (int cents = 0; cents < 10; cents++) {
            BigDecimal tie = new BigDecimal("0." + cents + "05");
            halfUp += Money.of(tie, USD, RoundingPolicy.HALF_UP).minorUnits();
            halfEven += Money.of(tie, USD, RoundingPolicy.HALF_EVEN).minorUnits();
        }

        assertThat(halfUp).as("HALF_UP rounds every tie away from zero").isGreaterThan(halfEven);
    }

    @ParameterizedTest(name = "rounds to {0} at its own scale")
    @CsvSource({"USD, 1.239, 1.24", "JPY, 100.6, 101", "BHD, 1.2345, 1.234", "CLF, 1.23455, 1.2346"})
    @DisplayName("rounds to the currency's own scale, across 0-, 2-, 3- and 4-decimal currencies")
    void roundsToEachCurrencyScale(String code, String input, String expected) {
        CurrencyCode currency = CurrencyCode.of(code);

        assertThat(Money.of(new BigDecimal(input), currency, RoundingPolicy.HALF_EVEN).toBigDecimal())
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("an amount already exact is unchanged by any policy")
    void exactAmountsAreUntouched() {
        for (RoundingPolicy policy : RoundingPolicy.values()) {
            assertThat(Money.of(new BigDecimal("12.34"), USD, policy))
                    .as("policy %s", policy)
                    .isEqualTo(Money.ofMinorUnits(1234L, USD));
            assertThat(Money.of(new BigDecimal("100"), JPY, policy))
                    .as("policy %s", policy)
                    .isEqualTo(Money.ofMinorUnits(100L, JPY));
        }
    }

    @Test
    @DisplayName("rounding still refuses an amount outside the representable range")
    void roundingDoesNotBypassOverflow() {
        assertThatExceptionOfType(MonetaryOverflowException.class)
                .isThrownBy(
                        () ->
                                Money.of(
                                        new BigDecimal("99999999999999999999.995"),
                                        USD,
                                        RoundingPolicy.HALF_EVEN));
    }

    @Test
    @DisplayName("rounding to a 3-decimal currency keeps the third decimal")
    void threeDecimalCurrencyKeepsItsPrecision() {
        assertThat(Money.of(new BigDecimal("1.2344"), BHD, RoundingPolicy.HALF_EVEN).minorUnits())
                .isEqualTo(1234L);
        assertThat(Money.of(new BigDecimal("1.2346"), BHD, RoundingPolicy.HALF_EVEN).minorUnits())
                .isEqualTo(1235L);
    }

    // -----------------------------------------------------------------
    // The policy type itself
    // -----------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(RoundingPolicy.class)
    @DisplayName("every policy has a stable name that resolves back to itself")
    void policyNamesRoundTrip(RoundingPolicy policy) {
        // INV-HIST-04: the name is what gets recorded on a decision, so it must survive a
        // round-trip through storage.
        assertThat(RoundingPolicy.ofName(policy.policyName())).isSameAs(policy);
        assertThat(policy.mode()).isNotNull();
    }

    @Test
    @DisplayName("an unknown policy name is rejected, never defaulted")
    void unknownPolicyNameIsRejected() {
        // Replaying a decision against a policy nobody recognises must fail loudly; silently
        // substituting a default would change the meaning of the decision being reproduced.
        assertThatThrownBy(() -> RoundingPolicy.ofName("HALF_SIDEWAYS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown rounding policy");

        assertThatThrownBy(() -> RoundingPolicy.ofName(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the biased and refusing JDK modes are deliberately not offered")
    void doesNotOfferHalfDownOrUnnecessary() {
        assertThat(RoundingPolicy.values())
                .extracting(RoundingPolicy::mode)
                .doesNotContain(RoundingMode.HALF_DOWN, RoundingMode.UNNECESSARY);
    }
}
