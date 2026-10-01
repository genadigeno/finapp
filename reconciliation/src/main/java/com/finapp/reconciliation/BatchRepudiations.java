package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A settlement batch's repudiation (`P8-TSK-023`, ADR-0065 §10, ADR-0070 §10, ADR-0071): the
 * {@code REPUDIATE_BATCH} resolution, whose subject is a batch, not a break. Accepted evidence
 * proven fabricated or mis-normalised is undone by compensation alone — never an edit
 * ({@code INV-REV-01}) — under four-eyes ({@code INV-AUD-04}).
 *
 * <h2>The plan</h2>
 *
 * <p>Everything the batch caused, derived from the rows: every standing allocation of its items
 * (each gains a counter-allocation, its expectation reopened); its own {@code REMITTANCE}
 * (counter-allocated where a bank item of a standing statement matched it, that item reopened,
 * then closed {@code RESOLVED_BY_ADJUSTMENT}); every suspense value its items still hold (an
 * unpark, or — for a statement's unattributed line — the reversal's own suspense line), and
 * every value a resolution already moved OUT of suspense (answered, never released twice: the
 * inverse posting or the reversal's line opens a {@code REPUDIATION} item on the opposite
 * side, owned by a new {@code PROCESSING_ERROR} break); every open break it empties; and the
 * recognition entry, reversed through {@link ReversalService}. The proposal digests the plan;
 * the approval re-derives it under the locks and refuses a moved subject
 * ({@code ResolutionStale}).
 *
 * <h2>Refused, before anything is written</h2>
 *
 * <p>An unknown batch; a batch not {@code ACCEPTED}; an item still {@code PENDING} (no edge
 * leaves it to {@code REPUDIATED} — let the run dispose of it first); and three shapes this
 * phase does not compensate, refused rather than half-done (recorded debt): a correction
 * {@code OFFSET} item, an allocation whose expectation a person already closed
 * {@code RESOLVED_BY_ADJUSTMENT}, and a bank item matched to the remittance that a person
 * already {@code RESOLVED}.
 *
 * <h2>Ten instances</h2>
 *
 * <p>The approval's lock order (`DISTRIBUTED_EXECUTION.md` §3's Phase 8 row): namespace-4
 * advisories for every affected source, BLOCKING, sorted — derived from an unlocked read, and a
 * set that differs under the locks is stale, never a late advisory; the breaks it closes, sorted;
 * the resolution row and its conditional {@code PROPOSED → APPROVED}; the items, the
 * expectations and the suspense items, each sorted by id; settlement's conditional batch edge;
 * the projection rows of every posting, pre-locked in their own order; the postings last. Ten
 * approvers: one reversal, under the resolution row, {@code resolution_batch_repudiated_once},
 * the reversal's key and the ledger's bound.
 */
@RequiredArgsConstructor
public final class BatchRepudiations {

    private static final String SUSPENSE_SUBJECT_KEY = "reconciliation.break_suspense_item_fk";

    @NonNull private final RepudiationStore store;
    @NonNull private final ResolutionStore resolutions;
    @NonNull private final BreakCaseStore breaks;
    @NonNull private final BreakRegister register;
    @NonNull private final Suspense suspense;
    @NonNull private final SettlementBatchRepudiations batches;
    @NonNull private final ReversalService reversals;
    @NonNull private final JournalEntryStore<Connection> journalEntries;
    @NonNull private final AdjustmentService adjustments;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final ReconciliationTelemetry telemetry;

    // ------------------------------------------------------------------ outcomes

    /** What the proposal will do, in counts — never an amount. */
    public record Counts(
            int items,
            int counterAllocations,
            int reopenedItems,
            int unparks,
            int answered,
            int breaksClosed,
            boolean remittanceClosed,
            boolean reversesRecognition) {}

    public record Proposed(
            UUID resolutionId,
            UUID settlementBatchId,
            ResolutionStatus status,
            Counts counts,
            Instant proposedAt) {}

    public record Decided(
            UUID resolutionId,
            UUID settlementBatchId,
            ResolutionStatus status,
            Optional<UUID> journalEntryId,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** Settlement holds no batch with this id, or it never reached reconciliation. */
    public static final class BatchNotFound extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        BatchNotFound() {
            super("no settlement batch has this identifier");
        }
    }

    /** The batch is not {@code ACCEPTED}: only accepted evidence is repudiated. */
    public static final class BatchNotRepudiable extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        BatchNotRepudiable() {
            super("only an ACCEPTED settlement batch is repudiated");
        }
    }

    /** An item of the batch is still {@code PENDING}: its run has not disposed of it. */
    public static final class BatchNotDisposed extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        BatchNotDisposed() {
            super("an item of the batch is still PENDING: its run disposes of every item first");
        }
    }

    /** A shape this phase does not compensate: refused rather than half-done. */
    public static final class RepudiationNotSupported extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        RepudiationNotSupported(String why) {
            super(why);
        }
    }

    // ------------------------------------------------------------------ the plan

    private record Answer(RepudiationStore.SuspenseRow row, long amountMinor) {}

    private record Plan(
            RepudiationStore.RunFacts run,
            List<RepudiationStore.ItemRow> items,
            List<RepudiationStore.ItemRow> foreignItems,
            List<RepudiationStore.AllocationRow> own,
            List<RepudiationStore.AllocationRow> foreign,
            Optional<RepudiationStore.ExpectationRow> remittance,
            List<RepudiationStore.ExpectationRow> expectations,
            List<RepudiationStore.SuspenseRow> suspenseRows,
            List<RepudiationStore.BreakRef> breaksToClose,
            Set<UUID> sources,
            byte[] digest) {

        Counts counts(boolean reversesRecognition) {
            int unparks =
                    (int) suspenseRows.stream()
                            .filter(row -> row.origin() == SuspenseOrigin.RECON_PARK
                                    && row.unreleasedMinor() > 0)
                            .count();
            int answered =
                    (int) suspenseRows.stream()
                            .filter(row -> row.releasedElsewhereMinor() > 0)
                            .count();
            return new Counts(
                    items.size(), own.size() + foreign.size(), foreignItems.size(), unparks,
                    answered, breaksToClose.size(), remittance.isPresent(),
                    reversesRecognition);
        }
    }

    private Plan derive(
            Connection unitOfWork,
            UUID batchId,
            RepudiationStore.RunFacts run,
            boolean lock) {
        List<RepudiationStore.ItemRow> items = store.itemsOfRun(unitOfWork, run.runId(), lock);
        if (items.stream().anyMatch(item -> item.status() == ItemStatus.PENDING)) {
            throw new BatchNotDisposed();
        }
        if (items.stream().anyMatch(item -> item.status() == ItemStatus.OFFSET)) {
            throw new RepudiationNotSupported(
                    "the batch holds a correction OFFSET against a parked excess: its"
                            + " compensation is not built in this phase");
        }
        List<RepudiationStore.AllocationRow> own =
                store.standingAllocationsOfRun(unitOfWork, run.runId());
        Optional<UUID> remittanceId = store.remittanceOf(unitOfWork, batchId);
        List<RepudiationStore.AllocationRow> foreign =
                remittanceId
                        .map(id -> store.standingAllocationsTo(unitOfWork, id, run.runId()))
                        .orElse(List.of());
        List<RepudiationStore.ItemRow> foreignItems =
                store.itemsById(
                        unitOfWork,
                        foreign.stream()
                                .map(RepudiationStore.AllocationRow::externalItemId)
                                .collect(Collectors.toCollection(TreeSet::new)),
                        lock);
        for (RepudiationStore.ItemRow item : foreignItems) {
            if (item.status() != ItemStatus.MATCHED
                    && item.status() != ItemStatus.PARKED
                    && item.status() != ItemStatus.UNMATCHED) {
                throw new RepudiationNotSupported(
                        "a bank item matched to the batch's remittance stands " + item.status()
                                + ": its reopening is not built in this phase");
            }
        }
        TreeSet<UUID> expectationIds =
                own.stream()
                        .map(RepudiationStore.AllocationRow::expectationId)
                        .collect(Collectors.toCollection(TreeSet::new));
        remittanceId.ifPresent(expectationIds::add);
        List<RepudiationStore.ExpectationRow> expectations =
                store.expectationsById(unitOfWork, expectationIds, lock);
        Optional<RepudiationStore.ExpectationRow> remittance =
                remittanceId.flatMap(
                        id -> expectations.stream().filter(row -> row.id().equals(id)).findFirst());
        for (RepudiationStore.ExpectationRow expectation : expectations) {
            if (expectation.status() == ExpectationStatus.RESOLVED_BY_ADJUSTMENT) {
                throw new RepudiationNotSupported(
                        "an expectation the batch reached was already closed"
                                + " RESOLVED_BY_ADJUSTMENT by a person: its reopening is not"
                                + " built in this phase");
            }
        }
        TreeSet<UUID> suspenseHolders = new TreeSet<>();
        items.forEach(item -> suspenseHolders.add(item.id()));
        foreignItems.stream()
                .filter(item -> item.status() == ItemStatus.PARKED)
                .forEach(item -> suspenseHolders.add(item.id()));
        List<RepudiationStore.SuspenseRow> suspenseRows =
                store.suspenseOfItems(unitOfWork, suspenseHolders, lock);
        List<UUID> decisions =
                store.decisionsOfItems(
                        unitOfWork, items.stream().map(RepudiationStore.ItemRow::id).toList());
        List<UUID> foreignParked =
                foreignItems.stream()
                        .filter(item -> item.status() == ItemStatus.PARKED)
                        .map(RepudiationStore.ItemRow::id)
                        .toList();
        List<UUID> breakItems =
                new ArrayList<>(items.stream().map(RepudiationStore.ItemRow::id).toList());
        breakItems.addAll(foreignParked);
        List<RepudiationStore.BreakRef> breaksToClose =
                store.openBreaks(
                        unitOfWork,
                        breakItems,
                        run.runId(),
                        remittanceId,
                        suspenseRows.stream().map(RepudiationStore.SuspenseRow::id).toList(),
                        suspenseRows.stream()
                                .filter(row -> row.unreleasedMinor() > 0)
                                .map(RepudiationStore.SuspenseRow::breakId)
                                .toList(),
                        decisions);
        TreeSet<UUID> sources = new TreeSet<>();
        sources.add(run.sourceId());
        foreignItems.forEach(item -> sources.add(item.sourceId()));
        breaksToClose.forEach(ref -> sources.add(ref.sourceId()));
        suspenseRows.forEach(row -> sources.add(row.breakSourceId()));
        return new Plan(
                run, items, foreignItems, own, foreign, remittance, expectations, suspenseRows,
                breaksToClose, sources,
                digest(run, items, foreignItems, own, foreign, expectations, suspenseRows,
                        breaksToClose, sources));
    }

    /** The plan's canonical form, hashed: what the approver must find unchanged. */
    private static byte[] digest(
            RepudiationStore.RunFacts run,
            List<RepudiationStore.ItemRow> items,
            List<RepudiationStore.ItemRow> foreignItems,
            List<RepudiationStore.AllocationRow> own,
            List<RepudiationStore.AllocationRow> foreign,
            List<RepudiationStore.ExpectationRow> expectations,
            List<RepudiationStore.SuspenseRow> suspenseRows,
            List<RepudiationStore.BreakRef> breaksToClose,
            Set<UUID> sources) {
        StringBuilder canonical = new StringBuilder("run=").append(run.runId());
        for (RepudiationStore.ItemRow item : items) {
            canonical.append("|i:").append(item.id()).append(':').append(item.status())
                    .append(':').append(item.allocatedMinor()).append(':')
                    .append(item.parkedMinor());
        }
        for (RepudiationStore.ItemRow item : foreignItems) {
            canonical.append("|f:").append(item.id()).append(':').append(item.status())
                    .append(':').append(item.allocatedMinor()).append(':')
                    .append(item.parkedMinor());
        }
        for (RepudiationStore.AllocationRow allocation : own) {
            canonical.append("|a:").append(allocation.id()).append(':')
                    .append(allocation.amountMinor());
        }
        for (RepudiationStore.AllocationRow allocation : foreign) {
            canonical.append("|r:").append(allocation.id()).append(':')
                    .append(allocation.amountMinor());
        }
        for (RepudiationStore.ExpectationRow expectation : expectations) {
            canonical.append("|e:").append(expectation.id()).append(':')
                    .append(expectation.status()).append(':')
                    .append(expectation.allocatedMinor()).append(':')
                    .append(expectation.resolvedMinor());
        }
        for (RepudiationStore.SuspenseRow row : suspenseRows) {
            canonical.append("|s:").append(row.id()).append(':').append(row.releasedMinor())
                    .append(':').append(row.releasedElsewhereMinor());
        }
        for (RepudiationStore.BreakRef ref : breaksToClose) {
            canonical.append("|b:").append(ref.id()).append(':').append(ref.status());
        }
        canonical.append("|sources=").append(sources);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JVM provides SHA-256", absent);
        }
    }

    private RepudiationStore.RunFacts acceptedRun(Connection unitOfWork, UUID batchId) {
        SettlementBatchRepudiations.Batch batch =
                batches.read(unitOfWork, batchId).orElseThrow(BatchNotFound::new);
        if (!batch.accepted()) {
            throw new BatchNotRepudiable();
        }
        return store.runOf(unitOfWork, batchId).orElseThrow(BatchNotFound::new);
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes the batch's repudiation (the first person's act): the plan derived and digested,
     * the {@code PROPOSED} row — one live per batch, for any writer — its history and the
     * audit record. Nothing else moves until a second person approves.
     */
    public Proposed propose(
            Connection unitOfWork,
            UUID batchId,
            ResolutionReasonCode reasonCode,
            String narrative,
            Actor actor,
            CorrelationId correlation) {
        if (reasonCode != ResolutionReasonCode.EVIDENCE_REPUDIATED) {
            throw new ResolutionMachine.ReasonCodeNotAllowed(
                    "REPUDIATE_BATCH admits the reason codes [EVIDENCE_REPUDIATED], not "
                            + reasonCode.name());
        }
        ResolutionMachine.refuseNarrative(narrative);
        SettlementBatchRepudiations.Batch batch =
                batches.read(unitOfWork, batchId).orElseThrow(BatchNotFound::new);
        RepudiationStore.RunFacts run = acceptedRun(unitOfWork, batchId);
        Plan plan = derive(unitOfWork, batchId, run, false);
        Instant now = Instant.now(clock);
        UUID resolutionId = ids.next();
        int scale =
                plan.items().stream()
                        .mapToInt(RepudiationStore.ItemRow::scale)
                        .findFirst()
                        .orElse(2);
        long countered =
                plan.own().stream().mapToLong(RepudiationStore.AllocationRow::amountMinor).sum();
        try {
            store.insert(
                    unitOfWork,
                    new RepudiationStore.NewRepudiation(
                            resolutionId,
                            batchId,
                            narrative,
                            Money.ofPersisted(countered, CurrencyCode.of(batch.currency()), scale),
                            run.ruleSetId(),
                            plan.digest(),
                            actor,
                            now,
                            correlation));
        } catch (ResolutionStore.OneLiveProposal taken) {
            throw new ResolutionMachine.ResolutionAlreadyProposed();
        }
        resolutions.appendEvent(
                unitOfWork, resolutionId, Optional.empty(), ResolutionStatus.PROPOSED, actor,
                Optional.empty(), now, correlation);
        Counts counts = plan.counts(batch.recognitionEntryId().isPresent());
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RESOLUTION_PROPOSED,
                resolutionId, Optional.of(reason()),
                "batch=" + batchId + ", " + summary(counts) + ", fourEyes=true", correlation);
        return new Proposed(resolutionId, batchId, ResolutionStatus.PROPOSED, counts, now);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Approves the repudiation (the second person's act), in one transaction: the plan
     * re-derived under the locks and its digest compared; the counter-allocations, the
     * reopenings, the remittance closed and the items {@code REPUDIATED}; settlement's batch
     * edge; then the postings last — the recognition's reversal and the unparks — and the
     * answers to values already released; then the breaks it emptied closed, the event and the
     * audit records.
     */
    public Decided approve(
            Connection unitOfWork, UUID resolutionId, Actor actor, CorrelationId correlation) {
        RepudiationStore.RepudiationRow unlocked =
                store.byId(unitOfWork, resolutionId, false)
                        .orElseThrow(() -> new ResolutionMachine.ResolutionNotFound());
        if (unlocked.status() == ResolutionStatus.APPROVED
                && unlocked.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(unlocked.id(), unlocked.settlementBatchId(), unlocked.status(),
                    unlocked.journalEntryId(), true);
        }
        if (unlocked.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionMachine.ResolutionNotPending(unlocked.status());
        }
        if (unlocked.proposedBy().equals(actor.id())) {
            throw new ResolutionMachine.SelfApprovalRefused();
        }
        UUID batchId = unlocked.settlementBatchId();
        SettlementBatchRepudiations.Batch batch;
        RepudiationStore.RunFacts run;
        Plan seen;
        try {
            batch = batches.read(unitOfWork, batchId).orElseThrow(BatchNotFound::new);
            run = acceptedRun(unitOfWork, batchId);
            seen = derive(unitOfWork, batchId, run, false);
        } catch (BatchNotRepudiable | RepudiationNotSupported | BatchNotDisposed refused) {
            // These lock-free reads may straddle a racing approver's commit - the batch then
            // reads REPUDIATED, its remittance closed: answer what the machine now says,
            // never the half-seen subject.
            Optional<Decided> decided = decidedMeanwhile(unitOfWork, resolutionId, actor);
            if (decided.isPresent()) {
                return decided.get();
            }
            throw refused;
        }

        // (1) The advisories, blocking and sorted; (2) the breaks to close, sorted.
        for (UUID source : seen.sources()) {
            breaks.lockSource(unitOfWork, source);
        }
        for (RepudiationStore.BreakRef ref : seen.breaksToClose()) {
            breaks.lockForUpdate(unitOfWork, ref.id());
        }
        // (3) The resolution and its state, under its own lock.
        RepudiationStore.RepudiationRow row =
                store.byId(unitOfWork, resolutionId, true).orElseThrow();
        if (row.status() != ResolutionStatus.PROPOSED) {
            if (row.status() == ResolutionStatus.APPROVED
                    && row.decidedBy().filter(actor.id()::equals).isPresent()) {
                return new Decided(row.id(), batchId, row.status(), row.journalEntryId(), true);
            }
            throw new ResolutionMachine.ResolutionNotPending(row.status());
        }
        // (4) The rows, locked and re-derived: the approver approves what was proposed.
        Plan plan = derive(unitOfWork, batchId, run, true);
        if (!plan.sources().equals(seen.sources())
                || !Arrays.equals(plan.digest(), seen.digest())) {
            throw stale(
                    "the batch's subject moved while its locks were taken");
        }
        if (!Arrays.equals(plan.digest(), row.subjectDigest())) {
            throw stale(
                    "the batch's subject moved since the proposal");
        }

        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String causeRef = "resolution=" + resolutionId;

        // ---- reconciliation's rows ----
        Map<UUID, Long> counteredByItem = new TreeMap<>();
        Map<UUID, Long> counteredByExpectation = new TreeMap<>();
        List<RepudiationStore.AllocationRow> counters = new ArrayList<>(plan.own());
        counters.addAll(plan.foreign());
        for (RepudiationStore.AllocationRow allocation : counters) {
            store.insertCounter(unitOfWork, allocation, ids.next(), now, correlation);
            counteredByItem.merge(allocation.externalItemId(), allocation.amountMinor(), Long::sum);
            counteredByExpectation.merge(
                    allocation.expectationId(), allocation.amountMinor(), Long::sum);
        }
        for (Map.Entry<UUID, Long> reopened : counteredByExpectation.entrySet()) {
            store.reopenExpectation(
                    unitOfWork, reopened.getKey(), reopened.getValue(), causeRef, actor, now,
                    correlation);
        }
        if (plan.remittance().isPresent()) {
            RepudiationStore.ExpectationRow remittance = plan.remittance().get();
            long remainder =
                    remittance.remainderMinor()
                            + counteredByExpectation.getOrDefault(remittance.id(), 0L);
            if (remainder > 0
                    && !resolutions.resolveExpectation(
                            unitOfWork, remittance.id(), remainder, resolutionId, actor, now,
                            correlation)) {
                throw new IllegalStateException(
                        "the locked remittance's remainder moved under its lock");
            }
        }
        // The parked value returned to each item's position: what an unpark releases, and
        // what a resolution had released and the inverse posting reposts - both leave the
        // item's parking, so its remainder carries them again (INV-REC-06).
        Map<UUID, Long> unreleasedByItem = new TreeMap<>();
        for (RepudiationStore.SuspenseRow suspenseRow : plan.suspenseRows()) {
            long returned =
                    suspenseRow.origin() == SuspenseOrigin.RECON_PARK
                            ? suspenseRow.unreleasedMinor()
                                    + suspenseRow.releasedElsewhereMinor()
                            : suspenseRow.unreleasedMinor();
            suspenseRow.externalItemId()
                    .ifPresent(item -> unreleasedByItem.merge(item, returned, Long::sum));
        }
        for (RepudiationStore.ItemRow item : plan.items()) {
            store.moveItem(
                    unitOfWork, item.id(), item.status(), ItemStatus.REPUDIATED,
                    counteredByItem.getOrDefault(item.id(), 0L),
                    Math.min(item.parkedMinor(), unreleasedByItem.getOrDefault(item.id(), 0L)),
                    OptionalInt.empty(), actor, now, correlation);
        }
        for (RepudiationStore.ItemRow item : plan.foreignItems()) {
            store.moveItem(
                    unitOfWork, item.id(), item.status(), ItemStatus.UNMATCHED,
                    counteredByItem.getOrDefault(item.id(), 0L),
                    item.status() == ItemStatus.PARKED
                            ? Math.min(item.parkedMinor(),
                                    unreleasedByItem.getOrDefault(item.id(), 0L))
                            : 0L,
                    store.graceHours(unitOfWork, item.sourceId(), item.lineType()),
                    actor, now, correlation);
        }

        // ---- settlement's edge ----
        if (!batches.markRepudiated(unitOfWork, batchId, resolutionId, actor, now, correlation)) {
            throw new BatchNotRepudiable();
        }

        // ---- the postings, last: their union pre-locked in the projection's order ----
        Optional<com.finapp.ledger.JournalEntry> recognition =
                batch.recognitionEntryId()
                        .map(id -> journalEntries
                                .findById(unitOfWork, JournalEntryId.of(id))
                                .orElseThrow(() -> new IllegalStateException(
                                        "an accepted batch's recognition entry exists"))
                                .entry());
        TreeSet<UUID> touched = new TreeSet<>();
        recognition.ifPresent(entry -> entry.lines().forEach(
                line -> touched.add(line.account().value())));
        for (RepudiationStore.SuspenseRow suspenseRow : plan.suspenseRows()) {
            suspenseRow.positionAccountId().ifPresent(touched::add);
            touched.add(suspenseAccount(unitOfWork, suspenseRow.currency()));
        }
        if (!touched.isEmpty()) {
            suspense.lockBalancesInOrder(unitOfWork, List.copyOf(touched));
        }
        Optional<UUID> reversalEntry = Optional.empty();
        if (recognition.isPresent()) {
            com.finapp.ledger.JournalEntry entry = recognition.get();
            PostingResult reversed =
                    reversals.reverse(
                            unitOfWork,
                            new ReversalCommand(
                                    "settlement-batch:" + batchId,
                                    entry.id(),
                                    today,
                                    entry.valueDate(),
                                    resolutionId.toString(),
                                    entry.lines().stream()
                                            .map(line -> new JournalLine(
                                                    line.account(),
                                                    line.direction().opposite(),
                                                    line.amount()))
                                            .toList()));
            reversalEntry = Optional.of(reversed.entryId().value());
        }
        List<Answer> answers = new ArrayList<>();
        Map<UUID, UUID> answerEntries = new LinkedHashMap<>();
        for (RepudiationStore.SuspenseRow suspenseRow : plan.suspenseRows()) {
            if (suspenseRow.unreleasedMinor() > 0) {
                if (suspenseRow.origin() == SuspenseOrigin.RECON_PARK) {
                    suspense.unpark(
                            unitOfWork, suspenseRow.id(),
                            Money.ofPersisted(
                                    suspenseRow.unreleasedMinor(),
                                    CurrencyCode.of(suspenseRow.currency()), suspenseRow.scale()),
                            ReleaseCause.REPUDIATION, causeRef, today, actor, now, correlation);
                } else {
                    // A statement's unattributed line: the reversal carried its suspense line,
                    // so the release posts nothing (ADR-0070 section 10).
                    suspense.release(
                            unitOfWork, suspenseRow.id(), suspenseRow.unreleasedMinor(),
                            ReleaseCause.REPUDIATION, causeRef, Optional.empty(), actor, now,
                            correlation);
                }
            }
            if (suspenseRow.releasedElsewhereMinor() > 0) {
                UUID lineEntry;
                if (suspenseRow.origin() == SuspenseOrigin.RECON_PARK) {
                    lineEntry =
                            suspense.repostReleased(
                                            unitOfWork, suspenseRow.id(),
                                            suspenseRow.releasedElsewhereMinor(), today, actor,
                                            now, correlation)
                                    .entryId();
                } else {
                    lineEntry =
                            reversalEntry.orElseThrow(() -> new IllegalStateException(
                                    "an unattributed line's value was recognised by an entry"));
                }
                answers.add(new Answer(suspenseRow, suspenseRow.releasedElsewhereMinor()));
                answerEntries.put(suspenseRow.id(), lineEntry);
            }
        }

        // ---- the answers: a new owned item per value already released (INV-REC-09) ----
        List<UUID> answerBreaks = new ArrayList<>();
        for (Answer answer : answers) {
            answerBreaks.add(
                    openAnswer(unitOfWork, answer, answerEntries.get(answer.row().id()), run,
                            today, actor, now, correlation));
        }

        // ---- the breaks it emptied ----
        for (RepudiationStore.BreakRef ref : plan.breaksToClose()) {
            withdrawPending(unitOfWork, ref.id(), resolutionId, actor, now, correlation);
            if (!resolutions.resolveBreak(
                    unitOfWork, ref.id(), resolutionId, ResolutionKind.REPUDIATE_BATCH, actor,
                    now, correlation)) {
                throw new IllegalStateException(
                        "the locked break was resolved by another writer: the lock order was"
                                + " bypassed");
            }
            store.insertClosure(unitOfWork, ref.id(), resolutionId, now, correlation);
            ReconciliationEvents.breakResolved(
                    outbox, unitOfWork, ids, ref.id(), resolutionId,
                    ResolutionKind.REPUDIATE_BATCH, ResolutionReasonCode.EVIDENCE_REPUDIATED,
                    Optional.empty(), now, correlation);
        }

        // ---- the resolution, the announcement, the record ----
        if (!resolutions.decide(unitOfWork, resolutionId, ResolutionStatus.APPROVED, actor, now,
                reversalEntry, Optional.empty(), Optional.empty())) {
            throw new IllegalStateException(
                    "the locked resolution was decided by another writer: the FOR UPDATE"
                            + " protocol was bypassed");
        }
        resolutions.appendEvent(
                unitOfWork, resolutionId, Optional.of(ResolutionStatus.PROPOSED),
                ResolutionStatus.APPROVED, actor, Optional.empty(), now, correlation);
        batches.announce(unitOfWork, batchId, resolutionId, reversalEntry, actor, now,
                correlation);
        telemetry.resolved(
                ResolutionKind.REPUDIATE_BATCH, ResolutionOutcome.APPROVED, Optional.empty());
        Counts counts = plan.counts(reversalEntry.isPresent());
        audit(unitOfWork, actor, now, ReconciliationAuditAction.RESOLUTION_APPROVED,
                resolutionId, Optional.empty(),
                "batch=" + batchId + ", kind=REPUDIATE_BATCH, reasonCode=EVIDENCE_REPUDIATED"
                        + ", proposedBy=" + row.proposedBy() + ", " + summary(counts)
                        + reversalEntry.map(id -> ", journalEntry=" + id).orElse("")
                        + (answerBreaks.isEmpty() ? "" : ", answerBreaks=" + answerBreaks),
                correlation);
        return new Decided(resolutionId, batchId, ResolutionStatus.APPROVED, reversalEntry,
                false);
    }

    /**
     * The resolution as another approver left it: the same person's retry converges, anyone
     * else's is {@code ResolutionNotPending}; empty while it is still {@code PROPOSED}.
     */
    private Optional<Decided> decidedMeanwhile(
            Connection unitOfWork, UUID resolutionId, Actor actor) {
        RepudiationStore.RepudiationRow now =
                store.byId(unitOfWork, resolutionId, false).orElseThrow();
        if (now.status() == ResolutionStatus.PROPOSED) {
            return Optional.empty();
        }
        if (now.status() == ResolutionStatus.APPROVED
                && now.decidedBy().filter(actor.id()::equals).isPresent()) {
            return Optional.of(new Decided(now.id(), now.settlementBatchId(), now.status(),
                    now.journalEntryId(), true));
        }
        throw new ResolutionMachine.ResolutionNotPending(now.status());
    }

    /** A stale approval, counted at once - the refusal commits nothing (`P8-TSK-024`). */
    private ResolutionMachine.ResolutionStale stale(String detail) {
        telemetry.staleRefused(ResolutionKind.REPUDIATE_BATCH);
        return new ResolutionMachine.ResolutionStale(detail);
    }

    /** The new owner and its {@code REPUDIATION} item, born together (V011's deferral). */
    private UUID openAnswer(
            Connection unitOfWork,
            Answer answer,
            UUID entryId,
            RepudiationStore.RunFacts run,
            LocalDate openedOn,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        RepudiationStore.SuspenseRow released = answer.row();
        SuspenseSide side =
                released.side() == SuspenseSide.CREDIT ? SuspenseSide.DEBIT : SuspenseSide.CREDIT;
        Money amount =
                Money.ofPersisted(
                        answer.amountMinor(), CurrencyCode.of(released.currency()),
                        released.scale());
        UUID itemId = ids.next();
        UUID breakId = ids.next();
        constraint(unitOfWork, "DEFERRED");
        BreakRegister.Raised raised =
                register.raise(
                        unitOfWork,
                        new BreakRegister.NewBreak(
                                breakId,
                                BreakType.PROCESSING_ERROR,
                                BreakCause.EVIDENCE_REPUDIATED,
                                BreakRegister.Subject.suspenseItem(itemId),
                                released.breakSourceId(),
                                run.ruleSetId(),
                                amount,
                                Optional.of(
                                        side == SuspenseSide.CREDIT
                                                ? ExpectationDirection.INBOUND
                                                : ExpectationDirection.OUTBOUND),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                actor,
                                now,
                                correlation));
        if (!raised.created()) {
            throw new IllegalStateException(
                    "a freshly minted subject has no standing owner: break " + breakId);
        }
        suspense.openRepudiation(
                unitOfWork,
                new Suspense.Repudiated(
                        itemId, breakId, released.id(), side, amount, openedOn, entryId),
                now, correlation);
        constraint(unitOfWork, "IMMEDIATE");
        return breakId;
    }

    private static void constraint(Connection unitOfWork, String mode) {
        try (Statement set = unitOfWork.createStatement()) {
            set.execute("SET CONSTRAINTS " + SUSPENSE_SUBJECT_KEY + " " + mode);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not set the owner's subject key " + mode, failure);
        }
    }

    /** A closed break's pending proposal is withdrawn, as evidence withdraws one. */
    private void withdrawPending(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        resolutions
                .lockProposedOf(unitOfWork, breakId)
                .ifPresent(
                        pending -> {
                            pending.adjustmentProposalId()
                                    .ifPresent(proposal -> adjustments.rejectOwned(
                                            unitOfWork, AdjustmentProposalId.of(proposal)));
                            if (!resolutions.decide(
                                    unitOfWork, pending.id(), ResolutionStatus.WITHDRAWN, actor,
                                    now, Optional.empty(), Optional.empty(), Optional.empty())) {
                                throw new IllegalStateException(
                                        "the locked proposal was decided by another writer");
                            }
                            telemetry.resolved(
                                    pending.kind(), ResolutionOutcome.WITHDRAWN, Optional.empty());
                            String detail = "repudiation=" + resolutionId;
                            resolutions.appendEvent(
                                    unitOfWork, pending.id(),
                                    Optional.of(ResolutionStatus.PROPOSED),
                                    ResolutionStatus.WITHDRAWN, actor, Optional.of(detail), now,
                                    correlation);
                            audit(unitOfWork, actor, now,
                                    ReconciliationAuditAction.RESOLUTION_WITHDRAWN, pending.id(),
                                    Optional.empty(),
                                    "break=" + breakId + ", kind=" + pending.kind().name()
                                            + ", withdrawnBy=REPUDIATION, " + detail,
                                    correlation);
                        });
    }

    // ------------------------------------------------------------------ reject, withdraw

    /** Another person rejects the proposal, reasoned; nothing else moved, so nothing returns. */
    public Decided reject(
            Connection unitOfWork,
            UUID resolutionId,
            String reason,
            Actor actor,
            CorrelationId correlation) {
        ResolutionMachine.refuseReason(reason);
        RepudiationStore.RepudiationRow row =
                store.byId(unitOfWork, resolutionId, true)
                        .orElseThrow(() -> new ResolutionMachine.ResolutionNotFound());
        if (row.status() == ResolutionStatus.REJECTED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.settlementBatchId(), row.status(), Optional.empty(),
                    true);
        }
        if (row.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionMachine.ResolutionNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new ResolutionMachine.SelfApprovalRefused();
        }
        close(unitOfWork, row, ResolutionStatus.REJECTED, Optional.of(reason), actor,
                correlation);
        return new Decided(row.id(), row.settlementBatchId(), ResolutionStatus.REJECTED,
                Optional.empty(), false);
    }

    /** The proposer withdraws their own proposal; the retry converges. */
    public Decided withdraw(
            Connection unitOfWork, UUID resolutionId, Actor actor, CorrelationId correlation) {
        RepudiationStore.RepudiationRow row =
                store.byId(unitOfWork, resolutionId, true)
                        .orElseThrow(() -> new ResolutionMachine.ResolutionNotFound());
        if (row.status() == ResolutionStatus.WITHDRAWN
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.settlementBatchId(), row.status(), Optional.empty(),
                    true);
        }
        if (row.status() != ResolutionStatus.PROPOSED) {
            throw new ResolutionMachine.ResolutionNotPending(row.status());
        }
        if (!row.proposedBy().equals(actor.id())) {
            throw new ResolutionMachine.NotTheProposer();
        }
        close(unitOfWork, row, ResolutionStatus.WITHDRAWN, Optional.empty(), actor,
                correlation);
        return new Decided(row.id(), row.settlementBatchId(), ResolutionStatus.WITHDRAWN,
                Optional.empty(), false);
    }

    /** Whether the resolution is a batch's repudiation — the desk's routing read. */
    public boolean isRepudiation(Connection unitOfWork, UUID resolutionId) {
        return store.isRepudiation(unitOfWork, resolutionId);
    }

    private void close(
            Connection unitOfWork,
            RepudiationStore.RepudiationRow row,
            ResolutionStatus to,
            Optional<String> reason,
            Actor actor,
            CorrelationId correlation) {
        Instant now = Instant.now(clock);
        telemetry.resolved(
                ResolutionKind.REPUDIATE_BATCH,
                to == ResolutionStatus.REJECTED
                        ? ResolutionOutcome.REJECTED
                        : ResolutionOutcome.WITHDRAWN,
                Optional.empty());
        if (!resolutions.decide(unitOfWork, row.id(), to, actor, now, Optional.empty(),
                Optional.empty(), Optional.empty())) {
            throw new IllegalStateException(
                    "the locked resolution was decided by another writer: the FOR UPDATE"
                            + " protocol was bypassed");
        }
        resolutions.appendEvent(
                unitOfWork, row.id(), Optional.of(ResolutionStatus.PROPOSED), to, actor, reason,
                now, correlation);
        audit(unitOfWork, actor, now,
                to == ResolutionStatus.REJECTED
                        ? ReconciliationAuditAction.RESOLUTION_REJECTED
                        : ReconciliationAuditAction.RESOLUTION_WITHDRAWN,
                row.id(), reason,
                "batch=" + row.settlementBatchId() + ", kind=REPUDIATE_BATCH"
                        + ", reasonCode=" + row.reasonCode().name()
                        + ", proposedBy=" + row.proposedBy(),
                correlation);
    }

    // ------------------------------------------------------------------ plumbing

    private UUID suspenseAccount(Connection unitOfWork, String currency) {
        return accounts
                .findOperational(
                        unitOfWork, AccountPurpose.SUSPENSE_UNMATCHED, CurrencyCode.of(currency))
                .orElseThrow(() -> new IllegalStateException(
                        "the chart seeds SUSPENSE_UNMATCHED per currency"))
                .id()
                .value();
    }

    private static String reason() {
        return "kind=REPUDIATE_BATCH, reasonCode=EVIDENCE_REPUDIATED";
    }

    private static String summary(Counts counts) {
        return "items=" + counts.items()
                + ", counterAllocations=" + counts.counterAllocations()
                + ", reopenedItems=" + counts.reopenedItems()
                + ", unparks=" + counts.unparks()
                + ", answered=" + counts.answered()
                + ", breaksClosed=" + counts.breaksClosed()
                + ", remittanceClosed=" + counts.remittanceClosed()
                + ", reversesRecognition=" + counts.reversesRecognition();
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            ReconciliationAuditAction action,
            UUID resolutionId,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        JdbcResolutions.TARGET_TYPE,
                        resolutionId.toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
