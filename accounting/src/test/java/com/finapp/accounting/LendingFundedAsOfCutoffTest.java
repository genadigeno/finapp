package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.LendingFundedAsOfCutoff.Committed;
import com.finapp.accounting.LendingFundedAsOfCutoff.LoanFacts;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which loans stood committed at a cutoff (`P14-TSK-019`; ADR-0096 section 6; {@code INV-ACC-04}): the rule, on each loan's frozen facts. Every boundary a day either side, an absence never read as a date, and the crossing into the outstanding term on the one date its journal line is dated. The statement that reads the facts is {@code LendingFundedAsOfCutoffReadsTest}'s, and {@code SafeguardingReturnDatabaseTest}'s against PostgreSQL (owed, C5).
 */
@DisplayName("committed as of the cutoff (P14-TSK-019)")
class LendingFundedAsOfCutoffTest {

    private static final LocalDate CUTOFF = LocalDate.of(2097, 6, 30);
    private static final LocalDate DAY_BEFORE = CUTOFF.minusDays(1);
    private static final LocalDate DAY_AFTER = CUTOFF.plusDays(1);
    private static final Instant MIDDAY = Instant.parse("2097-06-30T12:00:00Z");

    private static LoanFacts awaiting(LocalDate openedOn) {
        return new LoanFacts("EUR", 2, BigInteger.valueOf(1_000), openedOn, openedOn.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static LoanFacts disbursed(LocalDate openedOn, LocalDate on) {
        return new LoanFacts("EUR", 2, BigInteger.valueOf(1_000), openedOn, openedOn.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC), Optional.of(on), Optional.empty(), Optional.of(on.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)));
    }

    private static LoanFacts cancelled(LocalDate openedOn, LocalDate on) {
        return new LoanFacts("EUR", 2, BigInteger.valueOf(1_000), openedOn, openedOn.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC), Optional.empty(), Optional.of(on), Optional.of(on.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("a loan opened on the last day and awaiting its disbursement stood committed; one opened the day after did not")
    void theOpening() {
        assertThat(awaiting(CUTOFF).stoodCommitted(CUTOFF, Optional.empty())).isTrue();
        assertThat(awaiting(DAY_BEFORE).stoodCommitted(CUTOFF, Optional.empty())).isTrue();
        assertThat(awaiting(DAY_AFTER).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
    }

    @Test
    @DisplayName("a disbursement dated the last day is out of committed, and its journal line is in the outstanding term; dated the day after, it is still committed: one date, so never twice and never in neither")
    void theDisbursementCrossesOnItsValueDate() {
        assertThat(disbursed(DAY_BEFORE, CUTOFF).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
        assertThat(disbursed(DAY_BEFORE, DAY_BEFORE).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
        assertThat(disbursed(DAY_BEFORE, DAY_AFTER).stoodCommitted(CUTOFF, Optional.empty())).isTrue();
    }

    @Test
    @DisplayName("a cancellation on the last day releases it; on the day after, it still stood committed")
    void theCancellation() {
        assertThat(cancelled(DAY_BEFORE, CUTOFF).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
        assertThat(cancelled(DAY_BEFORE, DAY_BEFORE).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
        assertThat(cancelled(DAY_BEFORE, DAY_AFTER).stoodCommitted(CUTOFF, Optional.empty())).isTrue();
    }

    @Test
    @DisplayName("a clock stepped back across a midnight: opened after the cutoff yet disbursed on it never counts, because no two instants are ordered")
    void aClockSteppedBack() {
        assertThat(disbursed(DAY_AFTER, CUTOFF).stoodCommitted(CUTOFF, Optional.empty())).isFalse();
    }

    @Test
    @DisplayName("a PROVISIONAL run's cut: what was recorded after its claim has not happened yet, on every fact")
    void theCut() {
        LoanFacts openedAfterTheCut = new LoanFacts("EUR", 2, BigInteger.TEN, CUTOFF, MIDDAY.plusSeconds(60), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(openedAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY))).isFalse();
        assertThat(openedAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY.plusSeconds(60)))).as("at the cut is recorded by it").isTrue();

        LoanFacts disbursedAfterTheCut = new LoanFacts("EUR", 2, BigInteger.TEN, CUTOFF, MIDDAY.minusSeconds(60), Optional.of(CUTOFF), Optional.empty(), Optional.of(MIDDAY.plusSeconds(60)));
        assertThat(disbursedAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY))).as("still awaiting it at the cut").isTrue();
        assertThat(disbursedAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY.plusSeconds(60)))).isFalse();
        assertThat(disbursedAfterTheCut.stoodCommitted(CUTOFF, Optional.empty())).as("a FINAL run has no cut").isFalse();

        LoanFacts cancelledAfterTheCut = new LoanFacts("EUR", 2, BigInteger.TEN, CUTOFF, MIDDAY.minusSeconds(60), Optional.empty(), Optional.of(CUTOFF), Optional.of(MIDDAY.plusSeconds(60)));
        assertThat(cancelledAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY))).isTrue();
        assertThat(cancelledAfterTheCut.stoodCommitted(CUTOFF, Optional.of(MIDDAY.plusSeconds(60)))).isFalse();
    }

    @Test
    @DisplayName("the fold: per currency and scale, sorted, counted, and nothing for a currency no loan stood in - never a zero the port invents")
    void theFold() {
        List<Committed> folded = LendingFundedAsOfCutoff.committedAt(List.of(
                new LoanFacts("USD", 2, BigInteger.valueOf(5), DAY_BEFORE, MIDDAY, Optional.empty(), Optional.empty(), Optional.empty()),
                new LoanFacts("EUR", 2, BigInteger.valueOf(7), DAY_BEFORE, MIDDAY, Optional.empty(), Optional.empty(), Optional.empty()),
                new LoanFacts("EUR", 2, new BigInteger("9223372036854775807"), DAY_BEFORE, MIDDAY, Optional.empty(), Optional.empty(), Optional.empty()),
                new LoanFacts("EUR", 3, BigInteger.valueOf(11), DAY_BEFORE, MIDDAY, Optional.empty(), Optional.empty(), Optional.empty()),
                disbursed(DAY_BEFORE, DAY_BEFORE)), CUTOFF, Optional.empty());

        assertThat(folded).extracting(Committed::currency, Committed::scale, Committed::minor, Committed::loans).containsExactly(
                org.assertj.core.groups.Tuple.tuple("EUR", 2, new BigInteger("9223372036854775814"), 2L),
                org.assertj.core.groups.Tuple.tuple("EUR", 3, BigInteger.valueOf(11), 1L),
                org.assertj.core.groups.Tuple.tuple("USD", 2, BigInteger.valueOf(5), 1L));
        assertThat(LendingFundedAsOfCutoff.committedAt(List.of(), CUTOFF, Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("the return's term: outstanding plus written off, exactly, committed not among it")
    void theFormula() {
        assertThat(LendingFundedAsOfCutoff.principalUnrepaid(new BigDecimal("10.00"), new BigDecimal("0.25"))).isEqualByComparingTo("10.25");
    }

    @Test
    @DisplayName("no figure in a log line")
    void noFigureInALogLine() {
        assertThat(awaiting(CUTOFF).toString()).doesNotContain("1000");
        assertThat(new Committed("EUR", 2, BigInteger.valueOf(1_234), 1).toString()).doesNotContain("1234");
        assertThat(new LendingFundedAsOfCutoff.WrittenOff("EUR", 2, BigInteger.valueOf(1_234), 1).toString()).doesNotContain("1234");
    }
}
