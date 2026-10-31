package com.finapp.accounting.reporting;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PeriodStatus;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * The mapping version a calendar month is read under (`P14-TSK-007`; {@code INV-ACC-06}, {@code INV-ACC-07}): the month,
 * its accounting period's status when a period row exists for it, and the version - its id, number and immutable
 * content - that the period <strong>pins</strong> (a locked, closing or closed period) or that is in force for the month
 * (an open period, or a month no period has reached yet). Resolved in the root package ({@code GlPins}, which reads the
 * stores); carried here as a value so the GL's fold reaches no store - every figure computed under it names it.
 */
public record PinnedMapping(
        YearMonth period,
        Optional<PeriodStatus> periodStatus,
        GlMappingVersionId versionId,
        int version,
        GlMappingVersion content) {

    public PinnedMapping {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(periodStatus, "periodStatus");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(content, "content");
        if (version < 1) {
            throw new IllegalArgumentException("a mapping version is numbered from 1");
        }
    }

    /** The month's posting-date range. */
    public JournalReads.Range range() {
        return JournalReads.Range.of(period);
    }
}
