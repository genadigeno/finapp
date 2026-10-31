package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal port's values (`P14-TSK-007`): a read is bounded - a range of at most a year, a page of at most 500 lines,
 * a drill-down naming its keys - and the cursor is a position the database's order agrees with.
 */
@DisplayName("the journal port's bounds and cursor (P14-TSK-007)")
class JournalReadsTest {

    @Test
    @DisplayName("a range is ordered and at most 366 days; a calendar month is the period's range")
    void aRangeIsBounded() {
        assertThat(JournalReads.Range.of(YearMonth.of(2026, 2)))
                .isEqualTo(new JournalReads.Range(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertThat(new JournalReads.Range(start, start.plusDays(365)).holds(start.plusDays(365))).isTrue();
        assertThatThrownBy(() -> new JournalReads.Range(start, start.plusDays(366)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("366");
        assertThatThrownBy(() -> new JournalReads.Range(start, start.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(JournalReads.Range.of(YearMonth.of(2026, 1)).holds(LocalDate.of(2026, 2, 1))).isFalse();
    }

    @Test
    @DisplayName("a drill-down page names its keys and holds 1 to 500 lines")
    void aPageIsBounded() {
        JournalReads.Range range = JournalReads.Range.of(YearMonth.of(2026, 11));
        assertThatThrownBy(() -> new JournalReads.LineQuery(range, Set.of(), "EUR", Optional.empty(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalReads.LineQuery(range, Set.of("FEE_REVENUE/OPERATIONAL"), "EUR",
                Optional.empty(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalReads.LineQuery(range, Set.of("FEE_REVENUE/OPERATIONAL"), "EUR",
                Optional.empty(), 501)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new JournalReads.LineQuery(range, Set.of("FEE_REVENUE/OPERATIONAL"), "EUR", Optional.empty(), 500)
                .limit()).isEqualTo(500);
    }

    @Test
    @DisplayName("the cursor round-trips through its URL-safe token and refuses anything it did not write")
    void theCursorRoundTrips() {
        JournalReads.LineCursor cursor = new JournalReads.LineCursor(LocalDate.of(2026, 11, 3), UUID.randomUUID(), 4);
        assertThat(cursor.encoded()).matches("[A-Za-z0-9_-]+");
        assertThat(JournalReads.LineCursor.parse(cursor.encoded())).isEqualTo(cursor);
        for (String garbage : new String[] {"", "!!!", "bm90LWEtY3Vyc29y", "x".repeat(200),
                java.util.Base64.getUrlEncoder().encodeToString("2026-11-03|nope|1".getBytes())}) {
            assertThatThrownBy(() -> JournalReads.LineCursor.parse(garbage))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> JournalReads.LineCursor.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the cursor orders by posting date, then entry id in PostgreSQL's unsigned byte order, then line order")
    void theCursorOrdersAsTheDatabase() {
        UUID low = UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-4000-8000-000000000000");
        assertThat(low.compareTo(high)).as("Java's signed order").isPositive();
        assertThat(JournalReads.LineCursor.compareIds(low, high)).as("the database's unsigned order").isNegative();
        LocalDate day = LocalDate.of(2026, 11, 3);
        JournalReads.LineCursor cursor = new JournalReads.LineCursor(day, low, 1);
        assertThat(cursor.precedes(line(day, low, 2))).isTrue();
        assertThat(cursor.precedes(line(day, low, 1))).isFalse();
        assertThat(cursor.precedes(line(day, high, 0))).isTrue();
        assertThat(cursor.precedes(line(day.minusDays(1), high, 9))).isFalse();
        assertThat(cursor.precedes(line(day.plusDays(1), low, 0))).isTrue();
    }

    @Test
    @DisplayName("an aggregate's amount is exact at its scale; a journal line is a debit or a credit, never EITHER")
    void amountsAreExact() {
        JournalReads.Aggregate aggregate = new JournalReads.Aggregate("FEE_REVENUE", "OPERATIONAL", Optional.empty(),
                "EUR", 2, Side.CREDIT, new BigInteger("123456789012345678901234567890"), 3);
        assertThat(aggregate.amount().toPlainString()).isEqualTo("1234567890123456789012345678.90");
        assertThat(aggregate.key()).isEqualTo("FEE_REVENUE/OPERATIONAL");
        assertThatThrownBy(() -> new JournalReads.Total("EUR", 2, Side.EITHER, BigInteger.ONE, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JournalReads.Line line(LocalDate day, UUID entry, int seq) {
        return new JournalReads.Line(entry, seq, day, day, "POSTING", UUID.randomUUID(), "FEE_REVENUE", "OPERATIONAL",
                Optional.empty(), Optional.empty(), Side.CREDIT, 1, "EUR", 2);
    }
}
