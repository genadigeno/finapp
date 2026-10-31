package com.finapp.accounting.reporting;

import com.finapp.accounting.JournalReads;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A GL account's activity in one currency and period (`P14-TSK-007`; {@code INV-ACC-02}): its figure and one keyset page
 * of the journal lines composing it, read in one snapshot - so paging through every page sums to the figure. {@code next}
 * is the position after the page's last line when the page is full, empty when the drill-down is exhausted.
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
