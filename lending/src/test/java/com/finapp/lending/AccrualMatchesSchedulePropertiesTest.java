package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accrual engine's properties over generated inputs (`P11-TSK-007`; ADR-0094 point 3, {@code INV-LND-09},
 * {@code INV-MON-01}) - the backlog's {@code AccrualMatchesScheduleProperties}, named {@code ...Test} as every executed
 * test class must be ({@code TestTaxonomyTest}; precedent {@code AffordabilityPropertiesTest}).
 *
 * <ul>
 *   <li>On every on-time path - the base constant within each period, falling at each due date - a period's daily
 *       postings sum to the schedule's {@code interest_j = round_HALF_EVEN(B(j-1) x r x days_j / 365)}, computed here
 *       independently as plan section 12.2 step 3 states it: one product, one division, one rounding.</li>
 *   <li>With a base varying day by day, zero days and gaps included, a period's postings sum to its exact cumulative
 *       interest rounded once - never a figure further than half a minor unit from the exact rational interest.</li>
 *   <li>A second JVM with another charset, locale and time zone computes every figure of a seeded battery
 *       identically.</li>
 * </ul>
 *
 * <p>Seeded, so a failure reproduces. The cross-check driving {@code ScheduleEngineV1}'s own schedules through the
 * engine joins when the two engines meet on one branch (built in parallel, `P11-TSK-006`).
 */
@DisplayName("the accrual engine agrees with the schedule and rounds once per period (P11-TSK-007)")
class AccrualMatchesSchedulePropertiesTest {

    private static final int PATHS = 400;
    private static final int PERIODS = 3_000;

    @Test
    @DisplayName("on every on-time path, each period's accruals sum to the schedule's interest for it")
    void onTimePathsAgreeWithTheSchedule() {
        Random random = new Random(7_001);
        int periods = 0;
        for (int i = 0; i < PATHS; i++) {
            AccrualPaths.OnTimePath path = AccrualPaths.onTimePath(random, 60);
            Money total = Money.zero(path.terms().currency());
            Money scheduled = Money.zero(path.terms().currency());
            for (int j = 0; j < path.periods().size(); j++) {
                AccrualPeriod period = path.periods().get(j);
                Money base = path.bases().get(j);
                Money posted = Money.zero(base.currency());
                Optional<InterestAccrual> previous = Optional.empty();
                for (LocalDate date = period.start(); date.isBefore(period.endExclusive()); date = date.plusDays(1)) {
                    InterestAccrual accrual = AccrualEngineV1.accrue(path.terms(), period, previous, date, base);
                    assertThat(accrual.posted().isNegative()).isFalse();
                    posted = posted.plus(accrual.posted());
                    previous = Optional.of(accrual);
                }
                Money interest = scheduleInterest(base, path.terms().nominalAnnualRate(), period.days());
                String as = "path " + i + " period " + j + " (" + period.days() + " days)";
                assertThat(posted).as(as).isEqualTo(interest);
                assertThat(AccrualEngineV1.postedThrough(previous.orElseThrow())).as(as).isEqualTo(interest);
                assertThat(Act365Fixed.roundedOnce(
                                Act365Fixed.exactInterest(base, path.terms().nominalAnnualRate(), period.days()),
                                base.currency()))
                        .as(as + ", through the shared primitive").isEqualTo(interest);
                total = total.plus(posted);
                scheduled = scheduled.plus(interest);
                periods++;
            }
            assertThat(total).as("path " + i + ": the whole path's interest").isEqualTo(scheduled);
        }
        assertThat(periods).as("periods compared").isGreaterThan(PATHS * 10);
    }

    @Test
    @DisplayName("with a varying base, a period's postings sum to its exact interest rounded once")
    void cumulativeRoundingIsOneRoundingPerPeriod() {
        Random random = new Random(7_002);
        for (int i = 0; i < PERIODS; i++) {
            AccrualPaths.VaryingPeriod varying = AccrualPaths.varyingPeriod(random);
            BigDecimal rate = varying.terms().nominalAnnualRate();
            int scale = varying.terms().currency().minorUnits();
            BigDecimal cumulative = BigDecimal.ZERO.setScale(20);
            BigDecimal rational = BigDecimal.ZERO;
            Money posted = Money.zero(varying.terms().currency());
            Optional<InterestAccrual> previous = Optional.empty();
            LocalDate date = varying.period().start();
            for (Optional<Money> base : varying.bases()) {
                if (base.isPresent()) {
                    InterestAccrual accrual =
                            AccrualEngineV1.accrue(varying.terms(), varying.period(), previous, date, base.get());
                    assertThat(accrual.posted().isNegative()).isFalse();
                    posted = posted.plus(accrual.posted());
                    previous = Optional.of(accrual);
                    BigDecimal product = base.get().toBigDecimal().multiply(rate);
                    cumulative = cumulative.add(product.divide(BigDecimal.valueOf(365), 20, RoundingMode.HALF_EVEN));
                    rational = rational.add(product);
                    assertThat(accrual.cumulativeExact()).isEqualTo(cumulative);
                }
                date = date.plusDays(1);
            }
            String as = "period " + i;
            assertThat(posted).as(as).isEqualTo(Money.of(cumulative, varying.terms().currency(),
                    com.finapp.sharedkernel.money.RoundingPolicy.HALF_EVEN));
            // |posted - rational / 365| <= half a minor unit (plus the days' scale-20 divisions), compared times 365.
            BigDecimal error = posted.toBigDecimal().multiply(BigDecimal.valueOf(365)).subtract(rational).abs();
            BigDecimal bound = BigDecimal.valueOf(365).multiply(BigDecimal.valueOf(5, scale + 1))
                    .add(BigDecimal.valueOf(1, 15));
            assertThat(error).as(as + ": no residue beyond one rounding").isLessThanOrEqualTo(bound);
        }
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone - computes every figure identically")
    void aSecondJvmComputesIdentically() throws Exception {
        List<String> mine = AccrualInAnotherJvm.lines();
        List<String> theirs = SecondJvm.run(AccrualInAnotherJvm.class);
        assertThat(mine).as("the battery's lines - seeded, so a fixed count").hasSize(19_950);
        assertThat(theirs).hasSameSizeAs(mine);
        assertThat(theirs).isEqualTo(mine);
    }

    /** Plan section 12.2 step 3, literally: the exact product, one division to the minor unit, one rounding. */
    private static Money scheduleInterest(Money base, BigDecimal rate, long days) {
        BigDecimal interest = base.toBigDecimal().multiply(rate).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(365), base.currency().minorUnits(), RoundingMode.HALF_EVEN);
        return Money.of(interest, base.currency());
    }
}
