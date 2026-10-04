package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fee check's pure seat (`P8-TSK-012`, `INV-MON-03`, `INV-REC-08`): the expected fee
 * reproduced from the pinned terms under the NAMED rounding, the boundary strict — at the
 * tolerance nothing, one minor unit beyond a breach — and the conservative zeros for an
 * unreachable gross or an absent schedule.
 */
@DisplayName("the fee check is pure and strict (P8-TSK-012)")
class FeeCheckTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** The seeded PSP terms: 1.5% + 0.25, HALF_UP at scale 2 (rule set v1). */
    private static final FeeCheck.Schedule TERMS =
            new FeeCheck.Schedule(new BigDecimal("0.015000"), 25, 2, RoundingMode.HALF_UP);

    @Test
    @DisplayName("the expected fee is round(rate x gross + fixed) under the pinned terms:"
            + " 1.5% of 100.00 plus 0.25 is 1.75")
    void expectedFeeReproducedFromThePinnedTerms() {
        FeeCheck.Verdict verdict =
                FeeCheck.check(
                        money(1_75), Optional.of(money(100_00)), Optional.of(TERMS), 2);
        assertThat(verdict.expectedMinor()).isEqualTo(175L);
        assertThat(verdict.deviationMinor()).isZero();
        assertThat(verdict.beyondTolerance()).isFalse();
    }

    @Test
    @DisplayName("the boundary is strict: exactly at the tolerance nothing, one minor"
            + " unit beyond a breach (scenario 7)")
    void theBoundaryIsStrict() {
        // Expected 1.75; tolerance 2 minor.
        assertThat(FeeCheck.check(
                        money(1_77), Optional.of(money(100_00)), Optional.of(TERMS), 2)
                        .beyondTolerance())
                .as("deviation 2 = tolerance 2: within")
                .isFalse();
        assertThat(FeeCheck.check(
                        money(1_78), Optional.of(money(100_00)), Optional.of(TERMS), 2)
                        .beyondTolerance())
                .as("deviation 3 > tolerance 2: a breach")
                .isTrue();
        assertThat(FeeCheck.check(
                        money(1_72), Optional.of(money(100_00)), Optional.of(TERMS), 2)
                        .beyondTolerance())
                .as("the deviation is absolute: under-charging is judged the same way")
                .isTrue();
    }

    @Test
    @DisplayName("the NAMED rounding is applied, not assumed: HALF_UP and DOWN part ways"
            + " on the same gross")
    void theNamedRoundingIsApplied() {
        // 1.5% of 0.33 = 0.495 minor: HALF_UP rounds to 0.01... in minor units,
        // rate x 33 = 0.495 -> HALF_UP 0; use a gross where they differ at the unit:
        // rate x 99 = 1.485 -> HALF_UP 1, DOWN 1; rate x 100 = 1.5 -> HALF_UP 2, DOWN 1.
        FeeCheck.Schedule halfUp = TERMS;
        FeeCheck.Schedule down =
                new FeeCheck.Schedule(new BigDecimal("0.015000"), 25, 2, RoundingMode.DOWN);
        long grossMinor = 1_00; // 1.5% of 100 minor = 1.5 minor
        assertThat(FeeCheck.check(
                        money(0), Optional.of(money(grossMinor)),
                        Optional.of(halfUp), 0)
                        .expectedMinor())
                .isEqualTo(25 + 2);
        assertThat(FeeCheck.check(
                        money(0), Optional.of(money(grossMinor)),
                        Optional.of(down), 0)
                        .expectedMinor())
                .isEqualTo(25 + 1);
    }

    @Test
    @DisplayName("an unreachable gross (F1) or an absent schedule (F2) prices the"
            + " expected fee at zero - the whole reported fee stands at issue")
    void conservativeZeros() {
        FeeCheck.Verdict noGross =
                FeeCheck.check(money(1_75), Optional.empty(), Optional.of(TERMS), 2);
        assertThat(noGross.expectedMinor()).isZero();
        assertThat(noGross.deviationMinor()).isEqualTo(175L);
        assertThat(noGross.beyondTolerance()).isTrue();

        FeeCheck.Verdict noSchedule =
                FeeCheck.check(money(1_75), Optional.of(money(100_00)), Optional.empty(), 2);
        assertThat(noSchedule.expectedMinor()).isZero();
        assertThat(noSchedule.beyondTolerance()).isTrue();

        assertThat(FeeCheck.check(money(2), Optional.empty(), Optional.empty(), 2)
                        .beyondTolerance())
                .as("a tiny fee within the bound honestly raises nothing")
                .isFalse();
    }

    @Test
    @DisplayName("a fee is judged in its gross's own currency (INV-MON-04)")
    void currencyIsNeverConverted() {
        assertThatThrownBy(() ->
                        FeeCheck.check(
                                money(1_75),
                                Optional.of(
                                        Money.ofPersisted(
                                                100_00, CurrencyCode.of("GBP"), 2)),
                                Optional.of(TERMS),
                                2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-MON-04");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a schedule at another scale than the fee is refused loud, never priced - a JPY"
            + " fee against a scale-2 schedule would read its fixed part a hundred times too large"
            + " (P9-TSK-003, INV-MON-05)")
    void aScheduleAtAnotherScaleIsRefused() {
        CurrencyCode jpy = CurrencyCode.of("JPY");
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> FeeCheck.check(
                        Money.ofPersisted(190, jpy, 0),
                        Optional.of(Money.ofPersisted(10_000, jpy, 0)),
                        Optional.of(TERMS), 3))
                .withMessageContaining("scale 2 cannot price a fee at scale 0");
        FeeCheck.Verdict jpyAtItsOwnScale = FeeCheck.check(
                Money.ofPersisted(190, jpy, 0),
                Optional.of(Money.ofPersisted(10_000, jpy, 0)),
                Optional.of(new FeeCheck.Schedule(new BigDecimal("0.015000"), 40, 0,
                        RoundingMode.HALF_UP)),
                3);
        assertThat(jpyAtItsOwnScale.expectedMinor())
                .as("1.5% of 10,000 JPY plus 40 is 190 yen").isEqualTo(190L);
        assertThat(jpyAtItsOwnScale.beyondTolerance()).isFalse();
    }

    private static Money money(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }
}
