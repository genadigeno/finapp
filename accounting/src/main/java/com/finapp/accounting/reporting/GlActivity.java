package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A GL account's activity in one currency and period (`P14-TSK-007`; {@code INV-ACC-02}): its figure and one keyset page
 * of the journal lines composing it, both read in one snapshot - so a page's lines are always among its figure's.
 * {@code next} is the position after the page's last line when the page is full, empty when the drill-down is exhausted.
 *
 * <p><strong>Every page together sums to the figure only for a period no longer {@code OPEN}</strong> - a locked or
 * closed period's posting-date range is immutable after the cutoff ({@code INV-ACC-08}). In an {@code OPEN} period each
 * page is a snapshot of its own: an entry committed between two pages and ordered before the cursor's position (a
 * back-dated posting, or an earlier date's posting committed late) is never served by a later page, and each page's figure
 * is its own snapshot's - a reader re-reads the figure, or reads a locked period.
 */
public record GlActivity(
        PinnedMapping mapping, GlFigure figure, List<JournalReads.Line> lines, Optional<JournalReads.LineCursor> next) {

    public GlActivity {
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(figure, "figure");
        Objects.requireNonNull(next, "next");
        lines = List.copyOf(lines);
    }
}
