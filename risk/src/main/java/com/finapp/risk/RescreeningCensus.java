package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Every rescreening permit is accounted for under its list version (`P13-TSK-024`, the row `P13-TSK-022` routed;
 * {@code PHASE_13_PLAN.md} sections 12.10, 13.3; {@code INV-KYC-04}, {@code INV-RSK-06}; {@code -023}'s census pattern).
 * Per screening list version in force, or retired since yesterday's start (UTC, the database's clock - the day-based
 * censuses' "today and yesterday"), three identities over {@code risk V018}'s rows:
 *
 * <ul>
 *   <li><strong>born = counted</strong> - the version's permits equal what its enqueue counted born
 *       ({@code screening_list_version.enqueued}, advanced in each batch's transaction by the permits that batch
 *       inserted, {@code ON CONFLICT DO NOTHING} answering a duplicate) - a permit lost or born twice diverges;</li>
 *   <li><strong>every clearance a signal</strong> - the permits concluded {@code DONE(CLEARED)} equal the
 *       {@code SCREENING_CLEARED} signals naming the version (each born of its permit, in the conclusion's
 *       transaction);</li>
 *   <li><strong>every hit a signal, a late one too</strong> - the permits concluded {@code DONE(HIT)} are at most the
 *       {@code SCREENING_HIT} signals naming the version: a hit kyc tells after its permit concluded otherwise is a second
 *       signal born of kyc's check, the permit unmoved ({@code RescreeningRun.hit}, `-022`'s last review), so the signals
 *       may outnumber the permits, never the reverse.</li>
 * </ul>
 *
 * A version breaking any of the three is a difference; the verdict is {@link ProofVerdict#of} the versions judged and the
 * versions diverged. One snapshot; nothing written - the leg is the only thing that moves a permit, and a person acts on a
 * difference (`OPERATIONS_RUNBOOK.md` section 9.6). Bounded by the snapshot's {@code statement_timeout}
 * ({@link RiskProofs#bound}); the version window bounds the rows (a list's in-force version and the ones retired in the
 * last day or two), each count an index range of {@code rescreening_permit_of_version} or
 * {@code risk_signal_by_list_version}. The per-version rows are this census's own: no door serves them (the list version
 * read door is an owner item, `-022`'s O4).
 */
public final class RescreeningCensus {

    /** One version's figures and whether its identities hold. */
    public record Row(RiskProofReads.RescreeningRow figures) {
        public Row {
            Objects.requireNonNull(figures, "figures");
        }

        /** Permits born = permits counted by the enqueue. */
        public boolean everyPermitCounted() {
            return figures.permits() == figures.enqueued();
        }

        /** {@code DONE(CLEARED)} permits = {@code SCREENING_CLEARED} signals naming the version. */
        public boolean everyClearanceASignal() {
            return figures.cleared() == figures.clearedSignals();
        }

        /** {@code DONE(HIT)} permits ≤ {@code SCREENING_HIT} signals naming the version - a late hit adds a signal. */
        public boolean everyHitASignal() {
            return figures.hit() <= figures.hitSignals();
        }

        public boolean holds() {
            return everyPermitCounted() && everyClearanceASignal() && everyHitASignal();
        }
    }

    /** The census at {@code asOf}, over the versions in force or retired since {@code retiredSince}. */
    public record Result(Instant asOf, Instant retiredSince, List<Row> rows) {
        public Result {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(retiredSince, "retiredSince");
            rows = List.copyOf(rows);
        }

        public long diverged() {
            return rows.stream().filter(row -> !row.holds()).count();
        }

        public ProofVerdict verdict() {
            return ProofVerdict.of(rows.size(), diverged());
        }
    }

    private final RiskProofReads reads;

    public RescreeningCensus(RiskProofReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** The census on {@code snapshot}: the versions in force, and those retired since yesterday's start (UTC). */
    public Result run(Connection snapshot) {
        RiskProofs.requireSnapshot(snapshot);
        Instant asOf = reads.databaseNow(snapshot);
        Instant retiredSince = RiskDay.containing(asOf).previous().start();
        return new Result(asOf, retiredSince,
                reads.rescreenings(snapshot, retiredSince).stream().map(Row::new).toList());
    }
}
