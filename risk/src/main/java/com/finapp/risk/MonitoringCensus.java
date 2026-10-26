package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Monitored transactions = journal entries since the floor, by sequence (`P13-TSK-023`; {@code PHASE_13_PLAN.md}
 * sections 12.7, 13.3; {@code INV-RSK-09}; F8; {@code DESIGN-023} D3) - one day of the journal at a time. The ledger's
 * side is read through {@link MonitoredLedger#entryIds} ({@code app} over the journal's primary key, because risk has no
 * edge to the ledger); risk's side is the monitored rows' {@code journal_entry_id}s in the same range. The comparison is
 * of <strong>sets</strong>, so a lost entry and a phantom row cannot cancel out in a count.
 *
 * <ul>
 *   <li>The intake's watermark bounds what is judged: an entry at or before its position must be monitored; one after
 *       it is <strong>pending</strong> (the intake has not reached it - its lag is {@code finapp.risk.monitoring.lag}'s).</li>
 *   <li><strong>Missing</strong> - an entry at or before the watermark with no monitored row: the intake passed it
 *       (an entry committed behind the position, a batch that advanced over a gap).</li>
 *   <li><strong>Phantom</strong> - a monitored row naming no journal entry of its range: the monitoring record diverged
 *       from the journal.</li>
 *   <li><strong>Ahead</strong> - a monitored row after the watermark: a reprocessing moved the position back over rows
 *       already born (born once - the re-walk creates nothing); counted, never a difference.</li>
 * </ul>
 *
 * One read-only {@code REPEATABLE READ} snapshot: the intake's batch inserts its rows and advances the watermark in one
 * transaction, so the snapshot sees both or neither. Nothing is written.
 */
public final class MonitoringCensus {

    /** The day's census. */
    public record Result(RiskDay day, Instant watermarkThrough, long entries, long monitored, long pending, long ahead,
            List<UUID> missing, long missingCount, List<UUID> phantom, long phantomCount) {
        public Result {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(watermarkThrough, "watermarkThrough");
            missing = List.copyOf(missing);
            phantom = List.copyOf(phantom);
        }

        public ProofVerdict verdict() {
            return ProofVerdict.of(entries + monitored, missingCount + phantomCount);
        }
    }

    private final MonitoredLedger ledger;
    private final MonitoringWatermarkStore watermarks;
    private final RiskProofReads reads;

    public MonitoringCensus(MonitoredLedger ledger, MonitoringWatermarkStore watermarks, RiskProofReads reads) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** {@code day}'s census on {@code snapshot}. */
    public Result run(Connection snapshot, RiskDay day) {
        RiskProofs.requireSnapshot(snapshot);
        Objects.requireNonNull(day, "day");
        Optional<MonitoringWatermark> watermark = watermarks.read(snapshot, MonitoringWatermark.LEDGER_JOURNAL);
        UUID through = watermark.map(MonitoringWatermark::lastEntryId).orElse(MonitoringWatermark.FLOOR);
        List<UUID> journal = ledger.entryIds(snapshot, day.fromId(), day.toId());
        List<UUID> rows = reads.monitoredBetween(snapshot, day.fromId(), day.toId());
        Set<UUID> monitored = new HashSet<>(rows);
        Set<UUID> entries = new HashSet<>(journal);
        long judged = 0;
        long pending = 0;
        List<UUID> missing = new ArrayList<>();
        for (UUID entry : journal) {
            if (RiskProofs.order(entry, through) > 0) {
                pending++;
                continue;
            }
            judged++;
            if (!monitored.contains(entry)) {
                missing.add(entry);
            }
        }
        long ahead = 0;
        List<UUID> phantom = new ArrayList<>();
        for (UUID row : rows) {
            if (RiskProofs.order(row, through) > 0) {
                ahead++;
            }
            if (!entries.contains(row)) {
                phantom.add(row);
            }
        }
        return new Result(day, Instant.ofEpochMilli(MonitoringWatermark.sequenceOf(through)), judged, rows.size(),
                pending, ahead, RiskProofs.named(missing), missing.size(), RiskProofs.named(phantom), phantom.size());
    }
}
