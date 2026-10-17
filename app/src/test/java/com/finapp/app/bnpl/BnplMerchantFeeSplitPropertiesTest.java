package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.MerchantFeeSplit;
import com.finapp.merchant.FeeAssessment;
import com.finapp.merchant.FeeCalculation;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL merchant fee's arithmetic as bnpl receives it (`P12-TSK-006`; {@code INV-BNPL-04}, {@code INV-MER-04},
 * ADR-0058, ADR-0105 decision 3, PHASE_12_PLAN.md section 12.5): merchant's {@link FeeCalculation} under a version,
 * carried across the port by {@link MerchantBnplMerchants#split} into bnpl's {@link MerchantFeeSplit}. Seeded, so a red
 * run reproduces:
 *
 * <ul>
 *   <li>{@code gross = fee + net} exactly for every gross, rate, fixed part, rounding policy and minor-unit count;
 *   <li>{@code f < G}, or the split is refused - never a sale that pays the merchant nothing;
 *   <li>the returned fee over every refund sequence summing to G telescopes to exactly f, whatever the order; and no
 *       prefix of any sequence returns more than f, nor any refund a negative fee.
 * </ul>
 */
@DisplayName("BNPL merchant fee split and return, as properties (P12-TSK-006)")
class BnplMerchantFeeSplitPropertiesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");
    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.of("EUR"), CurrencyCode.of("JPY"), CurrencyCode.of("KWD"));

    @Test
    @DisplayName("gross = fee + net exactly, and f < G or the split is refused, for every gross and version")
    void theSplitConservesTheGross() {
        Random seeded = new Random(20261017L);
        int admitted = 0;
        int refused = 0;
        for (int sample = 0; sample < 20_000; sample++) {
            FeeScheduleVersion version = randomVersion(seeded);
            Money gross = Money.ofMinorUnits(1 + seeded.nextInt(seeded.nextBoolean() ? 500 : 5_000_000),
                    version.currency());
            FeeAssessment assessment = FeeCalculation.assess(gross, version);
            assertThat(assessment.fee().plus(assessment.net())).isEqualTo(gross);
            if (assessment.net().isPositive()) {
                MerchantFeeSplit split = MerchantBnplMerchants.split(assessment);
                assertThat(split.fee().plus(split.net())).isEqualTo(split.gross()).isEqualTo(gross);
                assertThat(split.fee().minorUnits()).isBetween(0L, gross.minorUnits() - 1);
                assertThat(split.feeScheduleVersionId()).isEqualTo(version.id().value());
                admitted++;
            } else {
                assertThatThrownBy(() -> MerchantBnplMerchants.split(assessment))
                        .as("a sale that does not cover its fee is refused, never split")
                        .isInstanceOf(IllegalArgumentException.class);
                refused++;
            }
        }
        assertThat(admitted).as("the property is not vacuous on admitted sales").isGreaterThan(10_000);
        assertThat(refused).as("nor on refused ones").isGreaterThan(10);
    }

    @Test
    @DisplayName("every refund sequence summing to G returns exactly f, in any order, never more than f on the way")
    void theReturnedFeeTelescopesToExactlyF() {
        Random seeded = new Random(20261018L);
        for (int sample = 0; sample < 3_000; sample++) {
            FeeScheduleVersion version = randomVersion(seeded);
            Money gross = Money.ofMinorUnits(2 + seeded.nextInt(seeded.nextBoolean() ? 50 : 500_000),
                    version.currency());
            FeeAssessment assessment = FeeCalculation.assess(gross, version);
            if (!assessment.net().isPositive()) {
                continue;
            }
            List<Long> refunds = partition(seeded, gross.minorUnits(), 1 + seeded.nextInt(6));
            for (int order = 0; order < 3; order++) {
                Collections.shuffle(refunds, seeded);
                long before = 0;
                Money returned = Money.ofMinorUnits(0, gross.currency());
                for (long refund : refunds) {
                    Money share = FeeCalculation.returnedFee(assessment, version,
                            Money.ofMinorUnits(before, gross.currency()), Money.ofMinorUnits(refund, gross.currency()));
                    assertThat(share.isNegative()).as("a refund never takes a fee back from the merchant").isFalse();
                    returned = returned.plus(share);
                    before += refund;
                    assertThat(returned.minorUnits()).as("never more than f returned, at any prefix")
                            .isLessThanOrEqualTo(assessment.fee().minorUnits());
                }
                assertThat(returned).as("a full refund returns exactly f: %s over %s", refunds, gross)
                        .isEqualTo(assessment.fee());
            }
        }
    }

    // ------------------------------------------------------------------ generators

    private static FeeScheduleVersion randomVersion(Random seeded) {
        CurrencyCode currency = CURRENCIES.get(seeded.nextInt(CURRENCIES.size()));
        // A rate in [0, 0.15) at up to six places - basis points and finer - and sometimes exactly zero (OQ-17's PN).
        BigDecimal rate = seeded.nextInt(10) == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(seeded.nextInt(150_000), 6);
        long fixed = seeded.nextInt(3) == 0 ? 0 : seeded.nextInt(seeded.nextBoolean() ? 50 : 500);
        RoundingPolicy policy = seeded.nextInt(2) == 0 ? RoundingPolicy.HALF_EVEN
                : RoundingPolicy.values()[seeded.nextInt(RoundingPolicy.values().length)];
        return FeeScheduleVersion.rehydrate(FeeScheduleVersionId.next(IDS), FeeScheduleId.next(IDS), 1,
                FeeRate.of(rate), Money.ofMinorUnits(fixed, currency), policy, RefundFeePolicy.RETURNED, AT, AT,
                "operator");
    }

    /** {@code parts} positive amounts summing to {@code total} (fewer when the total is too small). */
    private static List<Long> partition(Random seeded, long total, int parts) {
        List<Long> cuts = new ArrayList<>();
        long remaining = total;
        for (int part = 1; part < parts && remaining > 1; part++) {
            long piece = 1 + Math.floorMod(seeded.nextLong(), remaining - 1);
            cuts.add(piece);
            remaining -= piece;
        }
        cuts.add(remaining);
        return cuts;
    }
}
