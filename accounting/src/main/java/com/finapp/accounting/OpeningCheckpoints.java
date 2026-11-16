package com.finapp.accounting;

import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The openings leg's writer (`P16-REM-003`; {@code THREAT_MODEL.md} section 6 item 64; ADR-0142's follow-up): one locked month's
 * opening checkpoint per call, oldest first, after the lock has committed.
 *
 * <p><strong>Why after the lock, not in it.</strong> The lock's transaction holds the posting cutoff's key exclusively from its advance
 * to its commit, and every journal entry's insert takes that key shared (ADR-0114). A month's fold inside that transaction would hold
 * every posting in the platform behind it for as long as the fold ran. After the commit the cutoff stands over the month and nothing
 * waits: the month's lines are fixed while the lock stands, and the store writes a checkpoint only under the lock it was folded under.
 *
 * <p><strong>The fold is the reads' own.</strong> The GL side is {@link TrialBalanceReads#before} the day after the month, the ledger's
 * side is {@link TrialBalanceReads#ledger}'s through-the-end window of the month, the counterparty side is
 * {@link CounterpartyPositionReads#before} - the very reads a report takes, so a checkpoint is what a read would have folded. Wired to the
 * checkpointed reads, each starts from the month before's checkpoint: one month's lines per call, never the journal's life.
 *
 * <p><strong>Ten instances.</strong> {@link OpeningCheckpointStore#claim} is a try-lock: one instance folds, the others skip the tick. A
 * month that moved back while it was folded gets nothing stored - the insert reads the lock again - and is folded under its next lock.
 */
public final class OpeningCheckpoints {

    private final OpeningCheckpointStore store;
    private final TrialBalanceReads reads;
    private final CounterpartyPositionReads positions;
    private final JournalCreations creations;
    private final IdGenerator ids;

    public OpeningCheckpoints(OpeningCheckpointStore store, TrialBalanceReads reads, CounterpartyPositionReads positions,
            JournalCreations creations, IdGenerator ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.creations = Objects.requireNonNull(creations, "creations");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Folds and stores the oldest month whose present lock has no checkpoint, in {@code unitOfWork}'s transaction - when this instance
     * holds the writing and such a month exists.
     *
     * @return the month stored; empty when another instance holds the writing, none is missing, or the month moved back meanwhile
     */
    public Optional<AccountingPeriodId> storeNext(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (!store.claim(unitOfWork)) {
            return Optional.empty();
        }
        Optional<OpeningCheckpointStore.Pending> next = store.pending(unitOfWork);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        OpeningCheckpointStore.Pending pending = next.get();
        TrialBalanceReads.Period period = reads.period(unitOfWork, pending.period())
                .orElseThrow(() -> new IllegalStateException("a locked month's period row is not in the transaction"));
        if (!period.end().equals(pending.through())) {
            throw new IllegalStateException("a period's end is immutable (accounting V003)");
        }
        LocalDate after = period.end().plusDays(1);
        Optional<OpeningCheckpoint> previous = store.latest(unitOfWork, after, Optional.empty());
        List<JournalReads.Aggregate> gl = reads.before(unitOfWork, after, Optional.empty());
        List<JournalReads.Total> ledger = reads.ledger(unitOfWork, period, Optional.empty()).stream()
                .filter(window -> window.throughLines() > 0)
                .map(window -> new JournalReads.Total(window.currency(), window.scale(), window.side(), window.throughMinor(),
                        window.throughLines()))
                .toList();
        List<JournalReads.Aggregate> counterparties = positions.before(unitOfWork, after, Optional.empty());
        Optional<Instant> latestCreated = latest(previous.flatMap(OpeningCheckpoint::latestCreated),
                creations.latestCreated(unitOfWork, previous.map(OpeningCheckpoint::through), period.end()));
        OpeningCheckpoint checkpoint = new OpeningCheckpoint(pending.period(), period.end(), pending.lockedAt(), latestCreated, gl,
                ledger, counterparties);
        return store.store(unitOfWork, ids.next(), checkpoint) ? Optional.of(pending.period()) : Optional.empty();
    }

    private static Optional<Instant> latest(Optional<Instant> before, Optional<Instant> since) {
        return Stream.concat(before.stream(), since.stream()).max(Instant::compareTo);
    }
}
