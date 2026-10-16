package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.AccrualPeriod;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An instalment loan's accrual periods (`P11-TSK-016`; ADR-0094 point 3, ADR-0093 step 1). */
@DisplayName("the instalment calendar (P11-TSK-016)")
class InstalmentCalendarTest {

    // A schedule from 10 January 2024 on day 31: due 29 February (clamped, leap), 31 March, 30 April.
    private static final InstalmentCalendar CALENDAR = new InstalmentCalendar(List.of(
            new AccrualPeriod(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 2, 29)),
            new AccrualPeriod(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31)),
            new AccrualPeriod(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 30))), 31);

    @Test
    @DisplayName("a scheduled date lies in its instalment period, a due date opening the next one")
    void scheduledDates() {
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 1, 10)))
                .isEqualTo(new AccrualPeriod(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 2, 29)));
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 2, 28)).endExclusive()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 2, 29)).start()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 4, 29)).endExclusive()).isEqualTo(LocalDate.of(2024, 4, 30));
    }

    @Test
    @DisplayName("after the final due date the monthly calendar continues on the intended day, clamped - 31 May, 30 June,"
            + " 31 July - never one unbounded span")
    void afterMaturityTheCalendarContinues() {
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 4, 30)))
                .isEqualTo(new AccrualPeriod(LocalDate.of(2024, 4, 30), LocalDate.of(2024, 5, 31)));
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 6, 15)))
                .isEqualTo(new AccrualPeriod(LocalDate.of(2024, 5, 31), LocalDate.of(2024, 6, 30)));
        assertThat(CALENDAR.periodOf(LocalDate.of(2024, 7, 30)))
                .isEqualTo(new AccrualPeriod(LocalDate.of(2024, 6, 30), LocalDate.of(2024, 7, 31)));
    }

    @Test
    @DisplayName("a date before the schedule's start has no period; periods that do not chain are refused")
    void refusals() {
        assertThatThrownBy(() -> CALENDAR.periodOf(LocalDate.of(2024, 1, 9))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstalmentCalendar(List.of(
                new AccrualPeriod(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 2, 10)),
                new AccrualPeriod(LocalDate.of(2024, 2, 11), LocalDate.of(2024, 3, 10))), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstalmentCalendar(List.of(), 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
