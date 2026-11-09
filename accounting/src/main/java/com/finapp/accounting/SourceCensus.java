package com.finapp.accounting;

import com.finapp.platform.sourcejoin.EntryRef;
import com.finapp.platform.sourcejoin.SourceDoor;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SourceNamespace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The source-join census (`P15-TSK-035`; {@code THREAT_MODEL.md} T15, section 5): every journal entry, read against the domain record
 * that caused it. One {@link #sweepBatch batch} at a time, one replica at a time across every instance.
 *
 * <p><strong>The walk's horizon is a fence, not a window.</strong> A journal position is not commit order: an id minted before another
 * can commit after it, so a cursor at the head skips the slower transaction for ever and a census with a hole reads as clean. The
 * fence is the start of the oldest transaction this role can see open, minus a margin for instance clock skew: a transaction that
 * could still commit an earlier id is, by definition, open, so nothing is judged until it is not. It is read <strong>before</strong>
 * the batch's own transaction, or a transaction that commits in between would be behind the fence and outside the snapshot.
 * Compared with a re-scan window, a fence cannot be overrun by an unusually long posting; its failure is a stall, which the lag gauge
 * tickets. Stated as an inequality: a margin smaller than the real skew could hide a transaction whose instance's clock is behind by
 * more than the margin - the one residual, named in the runbook.
 *
 * <p><strong>One batch, one transaction, one walker.</strong> The batch holds the head row {@code FOR UPDATE SKIP LOCKED}: a replica
 * that finds it held does nothing this tick; a replica that dies mid-batch has its transaction rolled back and the next one resumes
 * from the committed position. Findings are unique per entry, so re-judging what was not committed converges.
 *
 * <p><strong>A read that fails fails the batch.</strong> A source that cannot be read is not an absent record: the batch rolls back
 * whole, the head stays, the failure is stamped in a transaction of its own and the gauge reads no value. Never a zero.
 */
public final class SourceCensus {

    /** The transaction seams: a port because {@code accounting} has no Spring. */
    public interface Units {
        /** {@code work} in one transaction on one connection; a thrown failure rolls it back. */
        <R> R inTransaction(Function<Connection, R> work);

        /** {@code work} on a connection outside any transaction: read and released before the batch begins. */
        <R> R outsideTransaction(Function<Connection, R> work);
    }

    /** What one batch did. */
    public record Batch(boolean claimed, int judged, int unsourced, int mismatched) {

        static Batch busy() {
            return new Batch(false, 0, 0, 0);
        }

        static Batch idle() {
            return new Batch(true, 0, 0, 0);
        }
    }

    private final Units units;
    private final SourceCensusStore store;
    private final JournalCensusWalk walk;
    private final SourceJoinCatalogue catalogue;
    private final Duration margin;
    private final int batchSize;
    private final Duration statementTimeout;

    /**
     * @param margin subtracted from the fence: must exceed the instances' clock skew; a position minted past it is not yet judged
     * @param batchSize entries per batch, at most {@link JournalCensusWalk#MAX_LIMIT}
     * @param statementTimeout each statement of a batch, armed once per batch
     */
    public SourceCensus(Units units, SourceCensusStore store, JournalCensusWalk walk, SourceJoinCatalogue catalogue, Duration margin,
            int batchSize, Duration statementTimeout) {
        this.units = Objects.requireNonNull(units, "units");
        this.store = Objects.requireNonNull(store, "store");
        this.walk = Objects.requireNonNull(walk, "walk");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.margin = Objects.requireNonNull(margin, "margin");
        if (margin.isNegative()) {
            throw new IllegalArgumentException("a margin is not negative");
        }
        if (batchSize < 1 || batchSize > JournalCensusWalk.MAX_LIMIT) {
            throw new IllegalArgumentException("a batch holds 1 to " + JournalCensusWalk.MAX_LIMIT + " entries");
        }
        this.batchSize = batchSize;
        this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
        if (statementTimeout.isNegative() || statementTimeout.isZero()) {
            throw new IllegalArgumentException("a statement timeout is positive");
        }
    }

    /**
     * Judges one page after the head and moves the head past it, in one transaction.
     *
     * @return what was judged; {@code claimed} false when another walker holds the head
     * @throws RuntimeException a source or the journal could not be read: the batch is rolled back whole and the failure is stamped
     */
    public Batch sweepBatch() {
        try {
            // The fence FIRST and outside the batch: a transaction open now cannot commit behind what the batch will read.
            Instant fence = units.outsideTransaction(store::fence);
            Instant mintedBefore = fence.minus(margin);
            return units.inTransaction(connection -> judge(connection, mintedBefore));
        } catch (RuntimeException failure) {
            stampFailure(failure);
            throw failure;
        }
    }

    private void stampFailure(RuntimeException failure) {
        try {
            String failed = failure.getClass().getSimpleName();
            units.inTransaction(connection -> {
                store.markFailure(connection, failed);
                return null;
            });
        } catch (RuntimeException secondary) {
            // Nothing to do: if the failure could not be recorded the head is still stale, and the gauge reads no value when its own read fails.
            failure.addSuppressed(secondary);
        }
    }

    private Batch judge(Connection connection, Instant mintedBefore) {
        arm(connection);
        Optional<SourceCensusStore.Head> claimed = store.claimHead(connection);
        if (claimed.isEmpty()) {
            return Batch.busy();
        }
        SourceCensusStore.Head head = claimed.get();
        List<JournalCensusWalk.CensusEntry> page = walk.page(connection, head.position(), mintedBefore, batchSize);
        if (page.isEmpty()) {
            store.touch(connection);
            return Batch.idle();
        }
        List<SourceCensusStore.Finding> findings = new ArrayList<>();
        for (Map.Entry<SourceNamespace, List<JournalCensusWalk.CensusEntry>> group : groupedByNamespace(page).entrySet()) {
            findings.addAll(judgeGroup(connection, group.getKey(), group.getValue()));
        }
        store.insertFindings(connection, findings);
        UUID last = page.get(page.size() - 1).id();
        store.advance(connection, last, page.size());
        return new Batch(true, page.size(),
                (int) findings.stream().filter(finding -> finding.kind() == SourceFindingKind.UNSOURCED).count(),
                (int) findings.stream().filter(finding -> finding.kind() == SourceFindingKind.MISMATCHED).count());
    }

    private void arm(Connection connection) {
        try (PreparedStatement set = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
            set.setString(1, statementTimeout.toMillis() + "ms");
            set.execute();
        } catch (SQLException failure) {
            throw new AccountingStorageException("arming the census's statement timeout (SQLState " + failure.getSQLState() + ")");
        }
    }

    /** Groups a page by the join that judges it; an entry with no namespace groups under {@code EMPTY_KEY} and is unsourced. */
    private static Map<SourceNamespace, List<JournalCensusWalk.CensusEntry>> groupedByNamespace(List<JournalCensusWalk.CensusEntry> page) {
        Map<SourceNamespace, List<JournalCensusWalk.CensusEntry>> groups = new LinkedHashMap<>();
        for (JournalCensusWalk.CensusEntry entry : page) {
            SourceDoor door = doorOf(entry.door());
            String namespace = entry.namespace().orElse(UNNAMED);
            groups.computeIfAbsent(new SourceNamespace(door, namespace), key -> new ArrayList<>()).add(entry);
        }
        return groups;
    }

    /** The namespace of an entry that names none: never a registered one (a rule's namespace has a shape this cannot match). */
    static final String UNNAMED = "(unnamed)";

    private List<SourceCensusStore.Finding> judgeGroup(Connection connection, SourceNamespace namespace, List<JournalCensusWalk.CensusEntry> entries) {
        Optional<SourceJoin> join = catalogue.join(namespace);
        if (join.isEmpty()) {
            return entries.stream()
                    .map(entry -> finding(entry, SourceFindingKind.UNSOURCED, namespace, "no join answers for this namespace"))
                    .toList();
        }
        Map<UUID, SourceFigures> figures;
        try {
            figures = join.get().read(connection, refs(entries));
        } catch (SQLException failure) {
            throw new AccountingStorageException("reading the source records of " + namespace.namespace()
                    + " (SQLState " + failure.getSQLState() + ")");
        }
        List<SourceCensusStore.Finding> findings = new ArrayList<>();
        for (JournalCensusWalk.CensusEntry entry : entries) {
            SourceFigures found = figures.get(entry.id());
            if (found == null) {
                findings.add(finding(entry, SourceFindingKind.UNSOURCED, namespace, "no record names this entry"));
                continue;
            }
            SourceJoinComparison.mismatch(entry.lines(), found)
                    .ifPresent(reason -> findings.add(finding(entry, SourceFindingKind.MISMATCHED, namespace, reason)));
        }
        return findings;
    }

    private static List<EntryRef> refs(List<JournalCensusWalk.CensusEntry> entries) {
        return entries.stream().map(entry -> new EntryRef(entry.id(), entry.suffix(), entry.reference())).toList();
    }

    private static SourceCensusStore.Finding finding(JournalCensusWalk.CensusEntry entry, SourceFindingKind kind, SourceNamespace namespace, String evidence) {
        return new SourceCensusStore.Finding(entry.id(), kind, entry.door(), namespace.namespace(), evidence);
    }

    /** The accounting door as the shared contract names it: the two enums are the same three names, by {@code EntryType}'s own rule. */
    static SourceDoor doorOf(EntryType door) {
        return SourceDoor.valueOf(door.name());
    }
}
