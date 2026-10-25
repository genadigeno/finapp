package com.finapp.risk;

import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T-mon's intake leg (`P13-TSK-019`; {@code PHASE_13_PLAN.md} sections 7.1, 7.5, 12.7; ADR-0111 decision 3;
 * {@code INV-RSK-09}): one batch, in one transaction, on the connection the schedule hands it - the watermark claimed
 * {@code FOR UPDATE SKIP LOCKED} (R7; another sweeper's claim, or a permit not yet due, is an empty batch), the entries
 * after it read through {@link MonitoredLedger} within the settle horizon, each classified
 * ({@link MonitoringClassification}), the rows inserted born once ({@code ON CONFLICT DO NOTHING}), the watermark
 * advanced to the batch's last entry with its permit re-stamped on the database clock - now after a full batch, the
 * idle cadence after a short one. The claim and its batch are <em>one</em> transaction, holding no money row, so a
 * partial batch never advances and a crash mid-batch leaves the committed watermark for the next claim to re-read
 * from (plan section 14). Ten sweepers ingest once by the row lock and the unique.
 *
 * <p>The port's contract is checked, never trusted: entries must sort strictly after the watermark and ascend, or the
 * batch is refused whole - an adapter that returned an entry at or before the position would advance over it.
 */
public final class MonitoringIntake {

    /**
     * What runs on a batch's rows inside the claim's own transaction, after they are inserted and before the watermark
     * advances (`P13-TSK-020`: T-mon's rule leg, {@link MonitoringRun}) - so the rows, what was judged of them and the
     * advance commit together or not at all, and a crash leaves the committed watermark for the next claim to redo.
     */
    @FunctionalInterface
    public interface BatchLeg {

        /** Nothing runs on the rows - `-019`'s intake alone. */
        BatchLeg NONE = (unitOfWork, rows) -> { };

        /** Runs on {@code rows} - the batch's, born or absorbed - on the claim's unit of work. */
        void onBatch(Connection unitOfWork, List<MonitoredTransaction> rows);
    }

    /** No bound on the customer accounts one claim touches - `-019`'s shape. */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    private final MonitoredLedger ledger;
    private final MonitoringWatermarkStore watermarks;
    private final MonitoredTransactionStore transactions;
    private final IdGenerator ids;
    private final Duration settleHorizon;
    private final int batch;
    private final Duration idleCadence;
    private final int subjectsPerClaim;

    /**
     * @param settleHorizon how long after its minting millisecond an entry is read - over the longest money transaction
     *     plus the instances' clock skew ({@code DESIGN-019} section 0)
     * @param batch the most entries one claim reads
     * @param idleCadence the permit after a short batch - the sweep's pace when the journal is caught up
     */
    public MonitoringIntake(MonitoredLedger ledger, MonitoringWatermarkStore watermarks,
            MonitoredTransactionStore transactions, IdGenerator ids, Duration settleHorizon, int batch,
            Duration idleCadence) {
        this(ledger, watermarks, transactions, ids, settleHorizon, batch, idleCadence, UNBOUNDED);
    }

    /**
     * @param subjectsPerClaim the most customer accounts one claim's batch may touch (`P13-TSK-020`: the rule leg's
     *     subject loop bounded per claim) - the batch is cut before the entry that would exceed it, never below one
     *     entry, and the rest is read by the next claim at once (a cut batch leaves the permit due now)
     */
    public MonitoringIntake(MonitoredLedger ledger, MonitoringWatermarkStore watermarks,
            MonitoredTransactionStore transactions, IdGenerator ids, Duration settleHorizon, int batch,
            Duration idleCadence, int subjectsPerClaim) {
        if (subjectsPerClaim < 2) {
            throw new IllegalArgumentException("a claim touches at least the two accounts one entry may name: "
                    + subjectsPerClaim);
        }
        this.subjectsPerClaim = subjectsPerClaim;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.settleHorizon = Objects.requireNonNull(settleHorizon, "settleHorizon");
        this.idleCadence = Objects.requireNonNull(idleCadence, "idleCadence");
        if (settleHorizon.isNegative() || idleCadence.isNegative() || idleCadence.isZero()) {
            throw new IllegalArgumentException("the settle horizon is not negative and the idle cadence is positive");
        }
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be positive: " + batch);
        }
        this.batch = batch;
    }

    /**
     * One batch's outcome.
     *
     * @param claimed whether this call held the watermark; false means another sweeper does, or the permit is not due
     * @param read how many entries the port served
     * @param born how many rows were born - fewer than {@code read} when earlier rows absorbed them
     * @param unclassified how many of the read entries classified {@code UNCLASSIFIED}
     * @param full whether the batch was full, so the permit was left due now and the next claim continues at once
     */
    public record Batch(boolean claimed, int read, int born, int unclassified, boolean full) {

        static final Batch SKIPPED = new Batch(false, 0, 0, 0, false);
    }

    /** One batch for {@code source} on {@code unitOfWork} - the caller's one transaction, committed by the caller. */
    public Batch sweepBatch(Connection unitOfWork, String source) {
        return sweepBatch(unitOfWork, source, BatchLeg.NONE);
    }

    /**
     * One batch for {@code source} on {@code unitOfWork}, {@code leg} run on its rows after their inserts and before the
     * advance (`P13-TSK-020`) - the caller's one transaction, committed by the caller.
     */
    public Batch sweepBatch(Connection unitOfWork, String source, BatchLeg leg) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(leg, "leg");
        Optional<MonitoringWatermark> claimed = watermarks.claimDue(unitOfWork, source);
        if (claimed.isEmpty()) {
            return Batch.SKIPPED;
        }
        MonitoringWatermark watermark = claimed.get();
        List<MonitoredEntry> entries = ledger.entriesAfter(unitOfWork, watermark.lastEntryId(), settleHorizon, batch);
        requireAscendingAfter(entries, watermark.lastEntryId());

        List<MonitoredTransaction> rows = new ArrayList<>(entries.size());
        Set<UUID> touched = new HashSet<>();
        boolean cut = false;
        for (MonitoredEntry entry : entries) {
            MonitoredTransaction row = MonitoringClassification.classify(MonitoredTransactionId.next(ids), entry);
            Set<UUID> next = new HashSet<>(touched);
            MonitoredMovement.subjectAccount(row).ifPresent(next::add);
            MonitoredMovement.counterpartyAccount(row).ifPresent(next::add);
            if (next.size() > subjectsPerClaim && !rows.isEmpty()) {
                // The subject loop's bound (P13-TSK-020): the rest is the next claim's, read at once.
                cut = true;
                break;
            }
            touched = next;
            rows.add(row);
        }
        int unclassified = (int) rows.stream().filter(row -> row.kind() == MonitoredKind.UNCLASSIFIED).count();
        int born = transactions.insertAbsent(unitOfWork, rows);
        leg.onBatch(unitOfWork, List.copyOf(rows));
        boolean full = cut || entries.size() >= batch;
        UUID position = rows.isEmpty() ? watermark.lastEntryId() : rows.getLast().journalEntryId();
        // The advance, in the batch's own transaction, after its inserts and its rule leg: a partial batch never
        // advances.
        watermarks.move(unitOfWork, source, position, full ? Duration.ZERO : idleCadence);
        return new Batch(true, rows.size(), born, unclassified, full);
    }

    private static void requireAscendingAfter(List<MonitoredEntry> entries, UUID after) {
        UUID previous = after;
        for (MonitoredEntry entry : entries) {
            if (compare(entry.journalEntryId(), previous) <= 0) {
                throw new IllegalStateException(
                        "the monitored ledger served an entry at or before the position - the batch is refused whole");
            }
            previous = entry.journalEntryId();
        }
    }

    /** Bytewise uuid order - PostgreSQL's, which {@code UUID.compareTo} (signed) is not in general. */
    static int compare(UUID left, UUID right) {
        int most = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return most != 0 ? most : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
