package com.finapp.accounting.reporting;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.TrialBalanceReads;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What every journal-derived definition reads first (`P14-TSK-017`; {@link TrialBalanceDefinition}'s opening, shared): the
 * period's row in the snapshot, the version the run pinned - and a FINAL run refused when its period's row pins another
 * ({@link TrialBalanceDefinition.PinMismatch}) - the cut a PROVISIONAL run honours and the month's posting-date range. A FINAL
 * run's range is immutable after its lock: it reads no cut, and its content never names one.
 */
record JournalWindow(
        TrialBalanceReads.Period period,
        Optional<GlMappingVersion> version,
        Optional<Instant> cut,
        JournalReads.Range range) {

    JournalWindow {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(cut, "cut");
        Objects.requireNonNull(range, "range");
    }

    /**
     * @throws IllegalStateException when the period's row is not in the snapshot
     * @throws TrialBalanceDefinition.PinMismatch when a FINAL run's version is not its period's pin
     */
    static JournalWindow of(TrialBalanceReads reads, Connection snapshot, ReportInputs inputs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(inputs, "inputs");
        TrialBalanceReads.Period period = reads.period(snapshot, inputs.periodId())
                .orElseThrow(() -> new IllegalStateException("the run's period is not in the snapshot"));
        if (inputs.basis() == RunBasis.FINAL && !period.pin().equals(inputs.mappingVersionId())) {
            throw new TrialBalanceDefinition.PinMismatch();
        }
        return new JournalWindow(period, inputs.mappingVersionId().map(id -> reads.version(snapshot, id)),
                inputs.basis() == RunBasis.PROVISIONAL ? Optional.of(inputs.cutAt()) : Optional.empty(),
                JournalReads.Range.of(inputs.period()));
    }

    /** The GL account the pinned version maps {@code key} to - empty with no version, or none for the key. */
    Optional<String> glAccountOf(String key) {
        return version.flatMap(content -> Optional.ofNullable(content.rulesByKey().get(key)))
                .map(rule -> rule.glAccountCode());
    }

    /** The two ranges every body names: the code's and the period row's. */
    CanonicalJson.Obj ranges(CanonicalJson.Obj body) {
        return body
                .put("range", CanonicalJson.object()
                        .put("from", range.from().toString())
                        .put("through", range.to().toString()))
                .put("periodBounds", CanonicalJson.object()
                        .put("from", period.start().toString())
                        .put("through", period.end().toString()));
    }
}
