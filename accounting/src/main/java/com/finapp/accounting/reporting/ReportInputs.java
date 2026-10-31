package com.finapp.accounting.reporting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.RunBasis;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * What a report run pins, as its generation reads it (`P14-TSK-008`; ADR-0115 decision 2: "the period and its revision,
 * the mapping version, the valuation rate set, the journal cut, the definition version"): fixed at the claim and stored
 * on the run, so a regeneration asks the definition exactly the question the run asked.
 *
 * @param periodId the period
 * @param period its calendar month (frozen from the period's birth)
 * @param periodRevision the period's revision at the claim (a reopen moves it, `P14-TSK-012`)
 * @param basis {@code FINAL} for a period no longer {@code OPEN}, {@code PROVISIONAL} for an open one
 * @param periodLockedAt the lock the FINAL run read under - the period's {@code locked_at}; empty for PROVISIONAL
 * @param mappingVersionId the GL mapping version pinned: the period's pin for FINAL, the version in force at the claim
 *     for PROVISIONAL; empty when none is
 * @param mappingVersion that version's number
 * @param cutAt the claim's database clock - the journal cut a PROVISIONAL definition honours (a FINAL run's range is
 *     immutable after the cutoff, so its content never reads it)
 * @param parameters the caller's parameters and the pinned valuation rate set
 */
public record ReportInputs(
        AccountingPeriodId periodId,
        YearMonth period,
        int periodRevision,
        RunBasis basis,
        Optional<Instant> periodLockedAt,
        Optional<GlMappingVersionId> mappingVersionId,
        Optional<Integer> mappingVersion,
        Instant cutAt,
        RunParameters parameters) {

    public ReportInputs {
        Objects.requireNonNull(periodId, "periodId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(periodLockedAt, "periodLockedAt");
        Objects.requireNonNull(mappingVersionId, "mappingVersionId");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(cutAt, "cutAt");
        Objects.requireNonNull(parameters, "parameters");
        if (periodRevision < 1) {
            throw new IllegalArgumentException("a period's revision is 1 or more");
        }
        if ((basis == RunBasis.FINAL) != periodLockedAt.isPresent()) {
            throw new IllegalArgumentException("a FINAL run names the lock it read under, and only a FINAL run does");
        }
        if (mappingVersionId.isPresent() != mappingVersion.isPresent()) {
            throw new IllegalArgumentException("a pinned mapping version names its id and its number together");
        }
    }

    /** The period's first day - the posting-date range's start. */
    public LocalDate start() {
        return period.atDay(1);
    }

    /** The period's last day - the posting-date range's end (the cutoff a lock sets). */
    public LocalDate end() {
        return period.atEndOfMonth();
    }
}
