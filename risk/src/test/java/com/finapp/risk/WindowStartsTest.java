package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where each window kind starts (`P13-TSK-006`; {@code INV-RSK-03}) - tumbling buckets anchored at Monday 1970-01-05
 * 00:00 UTC, the rule {@code risk V005}'s {@code velocity_window_start_is_aligned} states in SQL.
 */
@DisplayName("a window starts on its boundary, cut from the instant it is given (P13-TSK-006)")
class WindowStartsTest {

    private static final Instant AT = Instant.parse("2026-10-23T14:37:12.345678Z");

    @Test
    @DisplayName("H1 on the hour, D1 at UTC midnight, D7 on an ISO Monday, D30 thirty days from the anchor")
    void everyKindsCut() {
        assertThat(WindowStarts.start(WindowKind.H1, AT)).isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        assertThat(WindowStarts.start(WindowKind.D1, AT)).isEqualTo(Instant.parse("2026-10-23T00:00:00Z"));
        Instant week = WindowStarts.start(WindowKind.D7, AT);
        assertThat(week).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
        assertThat(week.atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        Instant month = WindowStarts.start(WindowKind.D30, AT);
        assertThat(month).isBeforeOrEqualTo(AT);
        assertThat(month.plus(WindowKind.D30.length())).isAfter(AT);
        assertThat(java.time.Duration.between(WindowStarts.ANCHOR, month).toDays() % 30).isZero();
        assertThat(WindowStarts.ANCHOR.atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("the boundary instant belongs to the window it starts; the instant before it to the previous one")
    void theBoundaryInstant() {
        Instant midnight = Instant.parse("2026-10-24T00:00:00Z");
        for (WindowKind kind : WindowKind.values()) {
            Instant start = WindowStarts.start(kind, AT);
            Instant end = WindowStarts.end(kind, start);
            assertThat(WindowStarts.start(kind, end)).as(kind + " at its end").isEqualTo(end);
            assertThat(WindowStarts.start(kind, end.minusNanos(1))).as(kind + " a nanosecond before").isEqualTo(start);
            assertThat(WindowStarts.aligned(kind, start)).isTrue();
            assertThat(WindowStarts.aligned(kind, start.plusSeconds(1))).isFalse();
        }
        assertThat(WindowStarts.start(WindowKind.D1, midnight)).isEqualTo(midnight);
        assertThat(WindowStarts.start(WindowKind.D1, midnight.minusNanos(1000))).isEqualTo(midnight.minusSeconds(86_400));
        assertThat(WindowStarts.start(WindowKind.H1, Instant.parse("2026-10-23T23:59:59.999999Z")))
                .isEqualTo(Instant.parse("2026-10-23T23:00:00Z"));
    }

    @Test
    @DisplayName("two instants five seconds apart either side of a boundary fall in two windows - which is why the cut"
            + " is one clock's, the database's")
    void fiveSecondsEitherSideOfABoundary() {
        Instant boundary = Instant.parse("2026-10-24T00:00:00Z");
        for (WindowKind kind : new WindowKind[] {WindowKind.H1, WindowKind.D1}) {
            assertThat(WindowStarts.start(kind, boundary.minusSeconds(5)))
                    .isNotEqualTo(WindowStarts.start(kind, boundary.plusSeconds(5)));
        }
    }
}
