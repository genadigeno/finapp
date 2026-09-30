package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The escalation arithmetic (`P8-TSK-013`, ADR-0069 §6): how many ageing bands lie behind a
 * break's age is a pure function of the day count — the sweep owes an escalation whenever
 * bands crossed exceed escalations already recorded, so each boundary must count exactly
 * once, at strictly-greater, never at the boundary itself (a break raised two days ago is
 * ON the first band, not past it). The database-clock day count and the counted races ride
 * {@code GraceAndRematchDatabaseTest}.
 */
@DisplayName("the ageing bands' arithmetic (P8-TSK-013)")
class ReconciliationSweepTest {

    @ParameterizedTest(name = "{0} days since raised -> {1} bands crossed")
    @CsvSource({
        "0, 0",
        "1, 0",
        "2, 0", // exactly on the first bound: not past it
        "3, 1",
        "7, 1", // exactly on the second bound
        "8, 2",
        "30, 2", // exactly on the third bound
        "31, 3",
        "365, 3", // capped by the band count - severity's own cap is CRITICAL
    })
    void bandsCrossedCountsStrictlyPastEachBound(long days, int expected) {
        assertThat(ReconciliationSweep.bandsCrossed(days)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the bounds are the recorded 2/7/30 - a change here is a policy change")
    void boundsArePinned() {
        assertThat(ReconciliationSweep.BAND_UPPER_BOUNDS).containsExactly(2L, 7L, 30L);
    }
}
