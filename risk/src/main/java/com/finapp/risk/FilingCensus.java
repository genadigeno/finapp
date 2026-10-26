package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * No filed report is left undriven (`P13-TSK-023`, the row `-021` routed here; {@code PHASE_13_PLAN.md} section 12.9;
 * ADR-0111 decision 6; {@code DESIGN-023} D5): the {@code FILED} reports over {@code regulatory_report_due} - filed
 * before the authority was asked, then driven by the platform until it acknowledges or rejects them, every attempt
 * moving the permit {@code next_inquiry_at} ahead. A report whose permit came more than {@link #OVERDUE_AFTER} ago is
 * <strong>overdue</strong>: no drive has claimed it for a whole inquiry interval - the filing's drive is not running, or
 * cannot claim. An {@code UNKNOWN} answer is not overdue: its inquiry re-permits it. One snapshot; nothing written - the
 * drive is the only thing that ever moves a report (runbook section 9.4, `-024`'s).
 */
public final class FilingCensus {

    /** The bound past a report's permit at which it is overdue - one inquiry interval (`-021`'s 15 minutes). */
    public static final Duration OVERDUE_AFTER = Duration.ofMinutes(15);

    /** The census at {@code asOf}. */
    public record Result(Instant asOf, long filed, long due, long overdue) {
        public Result {
            Objects.requireNonNull(asOf, "asOf");
        }

        public ProofVerdict verdict() {
            return ProofVerdict.of(filed, overdue);
        }
    }

    private final RiskProofReads reads;

    public FilingCensus(RiskProofReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** The census on {@code snapshot}. */
    public Result run(Connection snapshot) {
        RiskProofs.requireSnapshot(snapshot);
        Instant asOf = reads.databaseNow(snapshot);
        RiskProofReads.FilingRow row = reads.filings(snapshot, OVERDUE_AFTER);
        return new Result(asOf, row.filed(), row.due(), row.overdue());
    }
}
