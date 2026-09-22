package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic every capture will trust (`P6-TSK-004`, ADR-0050 §4, {@code INV-MER-04},
 * {@code INV-MON-03}).
 *
 * <p><strong>Property tests, not worked examples alone.</strong> A defect in this computation
 * is systematic and silent — a minor unit per transaction, found at reconciliation months
 * later if at all. Worked examples prove the cases somebody thought of; the conservation
 * property is proved over amounts, rates, rounding policies and currencies together, which is
 * where the interaction defects live.
 *
 * <p><strong>0-, 2- and 3-minor-unit currencies</strong> — JPY, USD, BHD — because that is
 * literally what {@code INV-MON-03}'s verify clause asks for, and because a fee computation
 * exercised only against two-decimal currencies is one whose scale handling is assumed rather
 * than tested. {@code Money} is currency-general even though the ledger's chart is not, and
 * this is the level at which that generality has to hold.
 */
@DisplayName("fee arithmetic (P6-TSK-004)")
class FeeCalculationTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Zero, two and three minor units — the three shapes {@code INV-MON-03} names. */
    private static final List<CurrencyCode> SCALES =
            List.of(CurrencyCode.of("JPY"), CurrencyCode.of("USD"), CurrencyCode.of("BHD"));

    @Test
    @DisplayName("INV-MER-04: fee + net == gross, for every amount, rate, policy and currency")
    void theSplitConservesExactly() {
        // The seed is fixed so a failure is reproducible from the message alone: a property
        // test that cannot be re-run on the input that broke it is a flake report, not a
        // finding.
        Random random = new Random(20260922L);
        int cases = 0;
        for (CurrencyCode currency : SCALES) {
            for (RoundingPolicy policy : RoundingPolicy.values()) {
                for (int i = 0; i < 400; i++) {
                    long grossMinor = random.nextLong(1L, 1_000_000_000L);
                    // Across the whole legal range, including 0 and values with every
                    // permitted scale, because the rounding behaviour that differs between
                    // policies only shows up on exact halves and tiny residues.
                    BigDecimal rate =
                            BigDecimal.valueOf(random.nextLong(0L, 1_000_000L), FeeRate.MAX_SCALE);
                    long fixedMinor = random.nextLong(0L, 1_000L);

                    Money gross = Money.ofMinorUnits(grossMinor, currency);
                    FeeScheduleVersion version =
                            version(currency, rate, fixedMinor, policy, RefundFeePolicy.RETAINED);

                    FeeAssessment assessment = FeeCalculation.assess(gross, version);

                    assertThat(assessment.fee().plus(assessment.net()))
                            .as(
                                    "conservation: currency=%s policy=%s gross=%d rate=%s"
                                            + " fixed=%d",
                                    currency, policy, grossMinor, rate.toPlainString(),
                                    fixedMinor)
                            .isEqualTo(gross);
                    assertThat(assessment.version()).isEqualTo(version.id());
                    cases++;
                }
            }
        }
        assertThat(cases).as("the property was actually exercised").isEqualTo(3 * 6 * 400);
    }

    @Test
    @DisplayName("the fee is round(gross x rate) + fixed - one rounding, then exact addition")
    void theFeeIsTheDocumentedFormula() {
        // 2.9% + 0.30 on 10.00 USD: 1000 x 0.029 = 29.00 exactly, + 30 = 59, net 941.
        FeeAssessment assessment =
                FeeCalculation.assess(
                        Money.ofMinorUnits(1000L, CurrencyCode.of("USD")),
                        version(
                                CurrencyCode.of("USD"),
                                new BigDecimal("0.029"),
                                30L,
                                RoundingPolicy.HALF_EVEN,
                                RefundFeePolicy.RETAINED));
        assertThat(assessment.fee().minorUnits()).isEqualTo(59L);
        assertThat(assessment.net().minorUnits()).isEqualTo(941L);
    }

    @Test
    @DisplayName("the rounding policy is the VERSION's, and it genuinely changes the answer")
    void theRoundingPolicyIsUsedRatherThanDefaulted() {
        // 1025 x 0.005 = 5.125 minor units - an exact residue that every policy resolves
        // differently. If the policy were ignored (or defaulted), these would agree.
        Money gross = Money.ofMinorUnits(1025L, CurrencyCode.of("USD"));
        BigDecimal rate = new BigDecimal("0.005");

        long floor = fee(gross, rate, RoundingPolicy.FLOOR);
        long ceiling = fee(gross, rate, RoundingPolicy.CEILING);

        assertThat(floor).isEqualTo(5L);
        assertThat(ceiling).isEqualTo(6L);
        assertThat(floor)
                .as("a defaulted rounding mode would make these equal - INV-MON-03")
                .isNotEqualTo(ceiling);
    }

    @Test
    @DisplayName("TOWARDS_ZERO and FLOOR differ on the sign, which is RoundingPolicy's own trap")
    void theSignSensitivePoliciesAreDistinguished() {
        // A gross is positive, so a fee's variable part never goes negative through this path
        // today; the policies are still distinguishable and the version stores which was
        // meant, so a later inverse computation (a refund's returned share) inherits the
        // correct one rather than a mode that happened to agree on positives.
        assertThat(RoundingPolicy.TOWARDS_ZERO.mode())
                .isNotEqualTo(RoundingPolicy.FLOOR.mode());
        assertThat(RoundingPolicy.AWAY_FROM_ZERO.mode())
                .isNotEqualTo(RoundingPolicy.CEILING.mode());
    }

    @Test
    @DisplayName("a zero-minor-unit currency rounds at zero decimals, not at two")
    void zeroScaleCurrenciesRoundAtTheirOwnScale() {
        // 1000 JPY at 2.9% = 29.0 -> 29 yen. A computation that assumed two decimals would
        // produce 2900 minor units of a currency that has none.
        FeeAssessment assessment =
                FeeCalculation.assess(
                        Money.ofMinorUnits(1000L, CurrencyCode.of("JPY")),
                        version(
                                CurrencyCode.of("JPY"),
                                new BigDecimal("0.029"),
                                0L,
                                RoundingPolicy.HALF_EVEN,
                                RefundFeePolicy.RETAINED));
        assertThat(assessment.fee().minorUnits()).isEqualTo(29L);
        assertThat(assessment.fee().scale()).isZero();
        assertThat(assessment.net().minorUnits()).isEqualTo(971L);
    }

    @Test
    @DisplayName("a three-minor-unit currency keeps its third decimal")
    void threeScaleCurrenciesKeepTheirPrecision() {
        // 1.000 BHD at 2.9% = 0.029 -> 29 fils of 1000.
        FeeAssessment assessment =
                FeeCalculation.assess(
                        Money.ofMinorUnits(1000L, CurrencyCode.of("BHD")),
                        version(
                                CurrencyCode.of("BHD"),
                                new BigDecimal("0.029"),
                                0L,
                                RoundingPolicy.HALF_EVEN,
                                RefundFeePolicy.RETAINED));
        assertThat(assessment.fee().minorUnits()).isEqualTo(29L);
        assertThat(assessment.fee().scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("a fee larger than the capture is computed honestly, not clamped")
    void theFeeIsNotClampedToTheGross() {
        // 0.30 fixed on a 0.10 capture. The net goes negative and conservation STILL holds.
        // Clamping would make the recorded fee disagree with what the pinned version produces
        // on recomputation - INV-MER-03 broken for cosmetics.
        Money gross = Money.ofMinorUnits(10L, CurrencyCode.of("USD"));
        FeeAssessment assessment =
                FeeCalculation.assess(
                        gross,
                        version(
                                CurrencyCode.of("USD"),
                                new BigDecimal("0.029"),
                                30L,
                                RoundingPolicy.HALF_EVEN,
                                RefundFeePolicy.RETAINED));
        assertThat(assessment.fee().minorUnits()).isEqualTo(30L);
        assertThat(assessment.net().minorUnits()).isEqualTo(-20L);
        assertThat(assessment.exceedsGross()).isTrue();
        assertThat(assessment.fee().plus(assessment.net())).isEqualTo(gross);
    }

    @Test
    @DisplayName("a free schedule charges nothing and the merchant keeps everything")
    void aZeroScheduleIsFree() {
        Money gross = Money.ofMinorUnits(12_345L, CurrencyCode.of("USD"));
        FeeAssessment assessment =
                FeeCalculation.assess(
                        gross,
                        version(
                                CurrencyCode.of("USD"),
                                BigDecimal.ZERO,
                                0L,
                                RoundingPolicy.HALF_EVEN,
                                RefundFeePolicy.RETAINED));
        assertThat(assessment.fee().isZero()).isTrue();
        assertThat(assessment.net()).isEqualTo(gross);
        assertThat(assessment.exceedsGross()).isFalse();
    }

    @Test
    @DisplayName("INV-MER-03: recomputing under the same version reproduces the same amount")
    void recomputationIsDeterministic() {
        FeeScheduleVersion version =
                version(
                        CurrencyCode.of("USD"),
                        new BigDecimal("0.0175"),
                        25L,
                        RoundingPolicy.HALF_UP,
                        RefundFeePolicy.RETURNED);
        Money gross = Money.ofMinorUnits(98_765L, CurrencyCode.of("USD"));

        assertThat(FeeCalculation.assess(gross, version))
                .isEqualTo(FeeCalculation.assess(gross, version));
    }

    @Test
    @DisplayName("INV-MER-03: a new version reprices nothing already assessed")
    void aNewVersionRepricesNothing() {
        // The hermetic half of the acceptance clause; the database half proves the same thing
        // through a stored pin in FeeScheduleDatabaseTest.
        FeeScheduleVersion v1 =
                version(
                        CurrencyCode.of("USD"),
                        new BigDecimal("0.029"),
                        30L,
                        RoundingPolicy.HALF_EVEN,
                        RefundFeePolicy.RETAINED);
        FeeScheduleVersion v2 =
                version(
                        CurrencyCode.of("USD"),
                        new BigDecimal("0.049"),
                        50L,
                        RoundingPolicy.HALF_EVEN,
                        RefundFeePolicy.RETAINED);
        Money gross = Money.ofMinorUnits(10_000L, CurrencyCode.of("USD"));

        FeeAssessment under1 = FeeCalculation.assess(gross, v1);
        FeeAssessment under2 = FeeCalculation.assess(gross, v2);

        assertThat(under1.fee().minorUnits()).isEqualTo(320L);
        assertThat(under2.fee().minorUnits()).isEqualTo(540L);
        assertThat(FeeCalculation.assess(gross, v1))
                .as("the existence of v2 changes nothing about what v1 prices")
                .isEqualTo(under1);
    }

    @Test
    @DisplayName("a gross in another currency is refused by name, before Money refuses by type")
    void aForeignGrossIsRefused() {
        FeeScheduleVersion usd =
                version(
                        CurrencyCode.of("USD"),
                        new BigDecimal("0.029"),
                        30L,
                        RoundingPolicy.HALF_EVEN,
                        RefundFeePolicy.RETAINED);
        assertThatThrownBy(
                        () ->
                                FeeCalculation.assess(
                                        Money.ofMinorUnits(1000L, CurrencyCode.of("EUR")), usd))
                .isInstanceOf(FeeCurrencyMismatchException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("an assessment that does not conserve is refused at the boundary too")
    void theAssessmentRefusesANonConservingSplit() {
        // The property is guaranteed by FeeCalculation; this asserts the CARRIER refuses it
        // anyway. Two classes, one property - so a future producer that computes the net some
        // other way cannot hand a drifted split onward.
        CurrencyCode usd = CurrencyCode.of("USD");
        assertThatThrownBy(
                        () ->
                                new FeeAssessment(
                                        Money.ofMinorUnits(1000L, usd),
                                        Money.ofMinorUnits(59L, usd),
                                        // One minor unit short: the residual-stranding defect.
                                        Money.ofMinorUnits(940L, usd),
                                        FeeScheduleVersionId.next(IDS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-MER-04");
    }

    @Test
    @DisplayName("a rate at or above 1, or negative, or too precise, is refused")
    void theRateBoundsHold() {
        assertThatThrownBy(() -> FeeRate.of(new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeRate.of(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeRate.of(new BigDecimal("0.0000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeeRate.of(new BigDecimal("0.999999")).toBigDecimal())
                .isEqualByComparingTo("0.999999");
    }

    @Test
    @DisplayName("a rate read back from its stored form prices identically")
    void storedRatesRoundTrip() {
        // numeric(7,6) pads the scale on storage: 0.029 comes back as 0.029000. If FeeRate
        // treated those as different rates, every recomputation of a stored version would
        // compare unequal to the original while pricing the same - a test that fails on
        // trailing zeros rather than on money.
        FeeRate typed = FeeRate.of(new BigDecimal("0.029"));
        FeeRate stored = FeeRate.ofStored(new BigDecimal("0.029000"));
        assertThat(stored).isEqualTo(typed);

        Money gross = Money.ofMinorUnits(1000L, CurrencyCode.of("USD"));
        assertThat(
                        FeeCalculation.assess(
                                gross,
                                version(
                                        CurrencyCode.of("USD"),
                                        stored.toBigDecimal(),
                                        30L,
                                        RoundingPolicy.HALF_EVEN,
                                        RefundFeePolicy.RETAINED))
                                .fee())
                .isEqualTo(
                        FeeCalculation.assess(
                                        gross,
                                        version(
                                                CurrencyCode.of("USD"),
                                                typed.toBigDecimal(),
                                                30L,
                                                RoundingPolicy.HALF_EVEN,
                                                RefundFeePolicy.RETAINED))
                                .fee());
    }

    // -----------------------------------------------------------------

    private static long fee(Money gross, BigDecimal rate, RoundingPolicy policy) {
        return FeeCalculation.assess(
                        gross, version(gross.currency(), rate, 0L, policy, RefundFeePolicy.RETAINED))
                .fee()
                .minorUnits();
    }

    private static FeeScheduleVersion version(
            CurrencyCode currency,
            BigDecimal rate,
            long fixedMinor,
            RoundingPolicy rounding,
            RefundFeePolicy refundFee) {
        FeeSchedule schedule =
                FeeSchedule.create(IDS, "Test " + currency, currency, "operator", CLOCK);
        return FeeScheduleVersion.create(
                IDS,
                schedule,
                FeeScheduleVersion.FIRST_VERSION,
                FeeRate.of(rate),
                Money.ofMinorUnits(fixedMinor, currency),
                rounding,
                refundFee,
                Optional.of(NOW),
                "operator",
                CLOCK);
    }
}
