package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pull worklist, derived and never stored (`P8-TSK-021`, the plan's cadence rule): a daily
 * report owed for each lookback date past its cut-off with no accepted batch; a cycle report
 * owed for each referenced token past its lag with no accepted batch.
 */
@DisplayName("the expected arrivals: derived from rows, never a stored schedule (P8-TSK-021)")
class ExpectedArrivalsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T07:00:00Z");

    @Test
    @DisplayName("daily: every lookback date whose cut-off (the next day plus the cut-off) has"
            + " passed and which holds no accepted batch - oldest first, today never owed")
    void aDailyReportIsOwedPastItsCutOff() {
        List<String> owed = ExpectedArrivals.daily(NOW, 3, Duration.ofHours(6), Set.of());
        assertThat(owed).containsExactly("2026-09-28", "2026-09-29", "2026-09-30");

        List<String> beforeCutOff =
                ExpectedArrivals.daily(
                        Instant.parse("2026-10-01T05:59:59Z"), 3, Duration.ofHours(6), Set.of());
        assertThat(beforeCutOff)
                .as("yesterday's report is not owed before today's cut-off")
                .containsExactly("2026-09-28", "2026-09-29");

        assertThat(ExpectedArrivals.daily(
                        NOW, 3, Duration.ofHours(6), Set.of(LocalDate.parse("2026-09-29"))))
                .as("an accepted date is received, never owed again")
                .containsExactly("2026-09-28", "2026-09-30");
        assertThat(ExpectedArrivals.windowStart(NOW, 3)).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThatThrownBy(() -> ExpectedArrivals.daily(NOW, 0, Duration.ZERO, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cycles: every referenced token past its lag with no accepted batch, once and in"
            + " token order")
    void aCycleReportIsOwedPastItsLag() {
        List<ExpectedArrivals.ReferencedCycle> referenced =
                List.of(
                        new ExpectedArrivals.ReferencedCycle(
                                "CYC-B", Instant.parse("2026-10-01T05:00:00Z")),
                        new ExpectedArrivals.ReferencedCycle(
                                "CYC-A", Instant.parse("2026-10-01T04:00:00Z")),
                        new ExpectedArrivals.ReferencedCycle(
                                "CYC-YOUNG", Instant.parse("2026-10-01T06:30:00Z")),
                        new ExpectedArrivals.ReferencedCycle(
                                "CYC-DONE", Instant.parse("2026-09-30T01:00:00Z")));
        assertThat(ExpectedArrivals.cycles(
                        referenced, NOW, Duration.ofHours(1), Set.of("CYC-DONE")))
                .as("the young cycle waits its lag; the accepted one is received")
                .containsExactly("CYC-A", "CYC-B");
    }
}
