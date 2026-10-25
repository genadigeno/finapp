package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The abuse ratios' arithmetic, property-based (`P13-TSK-014`; {@code INV-MON-01}): over seeded generated figures across
 * the engine's whole range, {@link Ratio#atLeast} answers exactly what the exact rational comparison answers - computed
 * here in {@link BigInteger}, the oracle no {@code long} can overflow - so the cross-multiplication neither overflows nor
 * rounds; a zero denominator is never a share; a negative or out-of-bound figure is refused, never clamped.
 */
@DisplayName("the abuse ratios: exact by cross-multiplication over generated figures (P13-TSK-014)")
class AbuseRatiosPropertiesTest {

    private static final long SEED = 20261025L;
    private static final int CASES = 5_000;

    @Test
    @DisplayName("over 5,000 seeded figures and shares, atLeast is the exact comparison num * 10,000 >= bps * den")
    void atLeastIsTheExactComparison() {
        Random random = new Random(SEED);
        int fired = 0;
        for (int i = 0; i < CASES; i++) {
            long denominator = figure(random);
            long numerator = random.nextInt(4) == 0 ? figure(random) : Math.floorMod(random.nextLong(), denominator + 1);
            int bps = random.nextInt(10_001);
            Optional<Ratio> ratio = AbuseRatios.of(numerator, denominator);
            if (denominator == 0) {
                assertThat(ratio).as("case %d: a zero denominator is no share", i).isEmpty();
                continue;
            }
            boolean exact = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(10_000))
                    .compareTo(BigInteger.valueOf(bps).multiply(BigInteger.valueOf(denominator))) >= 0;
            assertThat(ratio.orElseThrow().atLeast(bps)).as("case %d: %d / %d at %d bps", i, numerator, denominator, bps)
                    .isEqualTo(exact);
            fired += exact ? 1 : 0;
        }
        assertThat(fired).as("the generator reaches both sides of the boundary").isBetween(CASES / 10, CASES - CASES / 10);
    }

    @Test
    @DisplayName("at the boundary itself - num * 10,000 == bps * den - the share is at least bps, and one below is not")
    void theBoundaryIsInclusive() {
        Random random = new Random(SEED + 1);
        for (int i = 0; i < CASES; i++) {
            int bps = 1 + random.nextInt(10_000);
            long k = 1 + Math.floorMod(random.nextLong(), EvaluationInputs.MAX_MINOR / 10_000);
            long numerator = bps * k;
            long denominator = 10_000L * k;
            assertThat(AbuseRatios.of(numerator, denominator).orElseThrow().atLeast(bps)).as("case %d", i).isTrue();
            assertThat(AbuseRatios.of(numerator - 1, denominator).orElseThrow().atLeast(bps)).as("case %d", i).isFalse();
        }
    }

    @Test
    @DisplayName("a negative figure, or one above the engine's bound, is refused - never clamped")
    void outOfBoundFiguresAreRefused() {
        assertThatThrownBy(() -> AbuseRatios.of(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRatios.of(1, -10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRatios.of(EvaluationInputs.MAX_MINOR + 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseRatios.of(1, EvaluationInputs.MAX_MINOR + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AbuseRatios.of(0, EvaluationInputs.MAX_MINOR)).contains(new Ratio(0, EvaluationInputs.MAX_MINOR));
        assertThat(AbuseRatios.of(EvaluationInputs.MAX_MINOR, 1).orElseThrow().atLeast(10_000)).isTrue();
    }

    /** A figure across the engine's range - mostly small counts, sometimes zero, sometimes near the bound. */
    private static long figure(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextInt(3);
            case 1 -> random.nextInt(1_000);
            case 2 -> Math.floorMod(random.nextLong(), 1_000_000_000L);
            default -> EvaluationInputs.MAX_MINOR - Math.floorMod(random.nextLong(), 1_000L);
        };
    }
}
