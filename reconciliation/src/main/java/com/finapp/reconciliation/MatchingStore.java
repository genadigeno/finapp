package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the matcher (`P8-TSK-011`, ADR-0068) — the run leg's chunk reads and
 * conditional edges, the candidate resolution, the append-only decision, candidate and
 * allocation writes, and the explanation reads the operator doors serve from stored rows
 * alone.
 */
public interface MatchingStore {

    // ------------------------------------------------------------------ runs

    record RunRow(
            UUID id,
            UUID sourceId,
            UUID batchId,
            RunStatus status,
            UUID ruleSetId,
            long sourceSequence,
            int itemCount,
            long cursor,
            int failures,
            LocalDate businessDate,
            String correlationId) {}

    /** Sources holding a non-terminal {@code BATCH} run — the sweep's worklist. */
    List<UUID> sourcesWithWork(Connection unitOfWork);

    /**
     * The one run the source may work: its lowest-sequence non-terminal {@code BATCH} run,
     * and only when every lower-sequence one has completed — a {@code BLOCKED} run holds
     * its source, visibly (ADR-0068 §4).
     */
    Optional<RunRow> eligibleRun(Connection unitOfWork, UUID sourceId);

    /** The conditional {@code OPEN → IN_PROGRESS}; false when another edge won. */
    boolean markRunInProgress(Connection unitOfWork, UUID runId, Actor actor, Instant at);

    /** The conditional completion; the deferred trigger refuses it over a PENDING item. */
    boolean completeRun(Connection unitOfWork, UUID runId, Actor actor, Instant at);

    /** The cursor, advanced in the chunk's own transaction (ADR-0068 §4). */
    void advanceCursor(Connection unitOfWork, UUID runId, long lastLineNo, Instant at);

    /** Failure bookkeeping in its own small transaction; returns the new count. */
    int bumpRunFailures(Connection unitOfWork, UUID runId, Instant at);

    /** The conditional move to {@code BLOCKED}; false when another edge won. */
    boolean blockRun(Connection unitOfWork, UUID runId, Actor actor, Instant at);

    // ------------------------------------------------------------------ the chunk

    record ChunkItem(
            UUID id,
            long lineNo,
            ExternalLineType lineType,
            ExpectationDirection direction,
            Money amount,
            com.finapp.ledger.AccountPurpose positionPurpose,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            byte[] fingerprint,
            long sourceSequence,
            Map<ItemKeyKind, String> keys) {

        public ChunkItem {
            fingerprint = fingerprint.clone();
            keys = Map.copyOf(keys);
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint.clone();
        }
    }

    /** The run's next items past {@code cursor}, in {@code line_no} order, keys joined. */
    List<ChunkItem> chunkItems(Connection unitOfWork, UUID runId, long cursor, int limit);

    /** Whether an identical fingerprint stands earlier in claimant order (ADR-0068 §4). */
    boolean fingerprintSeenEarlier(
            Connection unitOfWork,
            UUID sourceId,
            byte[] fingerprint,
            long sourceSequence,
            long lineNo);

    /** Expectation ids reached by one key, per source — the direct hop. */
    List<UUID> expectationsByKey(
            Connection unitOfWork, UUID sourceId, KeyKind kind, String value);

    /** The alias hop: {@code (kind, value) → (anchorKind, anchorValue)}, if recorded. */
    Optional<Map.Entry<KeyKind, String>> aliasAnchor(
            Connection unitOfWork, UUID sourceId, KeyKind kind, String value);

    /** Locks the expectations sorted by id and reads the facts the engine snapshots. */
    List<MatchEngine.HitFacts> lockExpectations(
            Connection unitOfWork, Collection<UUID> expectationIds, Map<UUID, KeyKind> reachedBy);

    /** Locks the chunk's item rows, sorted by id (`DISTRIBUTED_EXECUTION.md` §3). */
    void lockItems(Connection unitOfWork, Collection<UUID> itemIds);

    // ------------------------------------------------------------------ writes

    record NewDecision(
            UUID id,
            UUID externalItemId,
            UUID runId,
            DecisionOrigin origin,
            UUID ruleSetId,
            Optional<Integer> rulePriority,
            Optional<Cardinality> strategy,
            Optional<KeyKind> matchedKeyKind,
            DecisionOutcome outcome,
            Optional<Integer> claimantRank,
            Optional<Integer> claimantCount,
            Optional<Integer> dateDeviationDays,
            Optional<Integer> timingToleranceDays,
            Optional<Long> feeExpectedMinor,
            Optional<Long> feeReportedMinor,
            Optional<Long> feeToleranceMinor,
            Actor decidedBy,
            Instant decidedAt,
            LocalDate decidedOn,
            CorrelationId correlation) {

        /** The pre-`P8-TSK-012` shape: no fee comparison on the row. */
        public NewDecision(
                UUID id,
                UUID externalItemId,
                UUID runId,
                DecisionOrigin origin,
                UUID ruleSetId,
                Optional<Integer> rulePriority,
                Optional<Cardinality> strategy,
                Optional<KeyKind> matchedKeyKind,
                DecisionOutcome outcome,
                Optional<Integer> claimantRank,
                Optional<Integer> claimantCount,
                Optional<Integer> dateDeviationDays,
                Optional<Integer> timingToleranceDays,
                Actor decidedBy,
                Instant decidedAt,
                LocalDate decidedOn,
                CorrelationId correlation) {
            this(id, externalItemId, runId, origin, ruleSetId, rulePriority, strategy,
                    matchedKeyKind, outcome, claimantRank, claimantCount,
                    dateDeviationDays, timingToleranceDays, Optional.empty(),
                    Optional.empty(), Optional.empty(), decidedBy, decidedAt, decidedOn,
                    correlation);
        }
    }

    void insertDecision(Connection unitOfWork, NewDecision decision);

    void insertCandidates(
            Connection unitOfWork, UUID decisionId, List<MatchEngine.HitFacts> candidates);

    record NewAllocation(
            UUID id,
            UUID decisionId,
            UUID externalItemId,
            UUID expectationId,
            Money amount,
            Instant at,
            CorrelationId correlation) {}

    void insertAllocation(Connection unitOfWork, NewAllocation allocation);

    /**
     * Adds {@code amount} to the expectation's {@code allocated_minor} and moves its
     * machine ({@code OPEN → PARTIALLY_SETTLED → SETTLED}), appending the {@code ALLOCATED}
     * event; returns the resulting status so the caller announces {@code SETTLED} once.
     */
    ExpectationStatus allocateToExpectation(
            Connection unitOfWork,
            UUID expectationId,
            Money amount,
            String detail,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /**
     * Bumps every OPEN break's staleness counter whose subject the allocation touched —
     * the expectation or the item (ADR-0071: a resolution proposal reads the version it
     * judged, and a moved residual refuses the stale approval). Forward-only by trigger;
     * a {@code RESOLVED} break takes no write.
     */
    void bumpResidualOnSubjects(Connection unitOfWork, UUID expectationId, UUID itemId);

    /** Records the allocated part on a {@code PENDING} item that will PARK its excess. */
    void recordItemAllocation(Connection unitOfWork, UUID itemId, long allocatedMinor);

    /** The item's conditional {@code PENDING → CHECKED} — a fee judged (`P8-TSK-012`). */
    boolean markItemChecked(
            Connection unitOfWork, UUID itemId, Actor actor, Instant at,
            CorrelationId correlation);

    /**
     * The correcting item's conditional {@code PENDING → OFFSET}, its value recorded as
     * offset ({@code offset_minor} — the `V003` conservation's own column).
     */
    boolean markItemOffset(
            Connection unitOfWork,
            UUID itemId,
            long offsetMinor,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /** The ORIGINAL item's conditional {@code PARKED → RESOLVED} when its excess offsets. */
    boolean markParkedItemResolved(
            Connection unitOfWork, UUID itemId, Actor actor, Instant at,
            CorrelationId correlation);

    /** Original items reached by one item-side key, per source (`P8-TSK-012`). */
    List<UUID> itemsByKey(
            Connection unitOfWork, UUID sourceId, ItemKeyKind kind, String value);

    /**
     * The originals' open parked value, LOCKED in the §3 order — each break row first,
     * then its suspense item, then the original external item — oldest suspense first;
     * rows whose owning break is already {@code RESOLVED} are skipped, never offset.
     */
    List<CorrectionEngine.ParkedOriginal> lockParkedOriginals(
            Connection unitOfWork, Collection<UUID> originalItemIds);

    /** One expectation's amount, lock-free — the fee check's gross (`P8-TSK-012`). */
    Optional<Money> expectationAmount(Connection unitOfWork, UUID expectationId);

    /** The break's staleness counter as stored — frozen onto the resolution it closes. */
    long breakResidualVersion(Connection unitOfWork, UUID breakId);

    /**
     * The one open break of this type on the expectation, LOCKED — taken before the
     * allocation that might settle it, so the evidence write holds the §3 break-first
     * order against any other writer of that break.
     */
    Optional<UUID> lockOpenBreakOn(
            Connection unitOfWork, UUID expectationId, BreakType type);

    // ------------------------------------------------------------------ time's legs

    /** One residual item with its run's pinned facts (`P8-TSK-013`). */
    record ResidualItem(
            ChunkItem item, UUID runId, UUID sourceId, UUID ruleSetId,
            String correlationId) {}

    /** Sources holding an {@code UNMATCHED} item whose grace has passed (database clock). */
    List<UUID> sourcesWithExpiredGrace(Connection unitOfWork);

    /**
     * The source's expired {@code UNMATCHED} items, LOCKED, oldest {@code grace_until}
     * first — each judged on its locked row (ADR-0073 §7: a candidate committed by a
     * holder of the item's share lock is found and allocated, never parked beside).
     */
    List<ResidualItem> lockExpiredItems(Connection unitOfWork, UUID sourceId, int limit);

    /** Sources holding a residual item whose keys reach an expectation opened later. */
    List<UUID> sourcesWithRematchWork(Connection unitOfWork);

    /**
     * The source's {@code UNMATCHED} or {@code PARKED} items whose keys now reach an
     * expectation opened AFTER their latest decision, LOCKED, oldest first.
     */
    List<ResidualItem> lockRematchCandidates(Connection unitOfWork, UUID sourceId, int limit);

    /** The item's conditional exit to {@code MATCHED} from the named non-terminal state. */
    boolean markItemMatchedFrom(
            Connection unitOfWork,
            UUID itemId,
            String fromStatus,
            long allocatedMinor,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /** One overdue candidate: still open past its pinned window (`P8-TSK-013`). */
    record OverdueCandidate(
            UUID expectationId,
            ExpectationKind kind,
            ExpectationDirection direction,
            String operationRef,
            Money remainder,
            UUID sourceId,
            UUID ruleSetId,
            LocalDate expectedBy,
            String correlationId) {}

    /**
     * Expectations still {@code OPEN}/{@code PARTIALLY_SETTLED} past
     * {@code expected_by + SETTLEMENT_DATE_DAYS} on the database clock with
     * {@code overdue_since} unset — read lock-free; each is re-judged under its own row
     * lock by {@link #lockAndMarkOverdue}.
     */
    List<OverdueCandidate> overdueCandidates(Connection unitOfWork, int limit);

    /**
     * The one-way fact: locks the expectation row and sets {@code overdue_since} iff
     * still unset, still open and still past its window — false when another sweeper
     * won or the money arrived meanwhile.
     */
    boolean lockAndMarkOverdue(Connection unitOfWork, UUID expectationId, Instant at);

    /** One unresolved break with its ageing facts (`P8-TSK-013`). */
    record EscalationRow(
            UUID breakId, UUID sourceId, Severity severity, long daysSinceRaised,
            long escalations) {}

    /** Unresolved breaks with days-since-raise (database clock) and escalations counted. */
    List<EscalationRow> unresolvedBreaks(Connection unitOfWork, int limit);

    /**
     * One escalation step: the expected-value predicate converges racers, the
     * {@code SEVERITY_ESCALATED} event appended only by the winner.
     */
    boolean escalate(
            Connection unitOfWork,
            UUID breakId,
            Severity from,
            Severity to,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /** Runs whose recorded failures reached the bound but whose block was lost. */
    List<RunRow> runsAtFailureBound(Connection unitOfWork, int bound);

    /** Whether an open break of this type stands on the expectation — no lock. */
    boolean openBreakExistsOn(Connection unitOfWork, UUID expectationId, BreakType type);

    /** The item's stored status, read on the already-locked row. */
    String itemStatus(Connection unitOfWork, UUID itemId);

    /** One fee decision per row of the run, in claimant order — the per-batch fold. */
    record FeeDecisionRow(
            UUID itemId,
            long lineNo,
            String currency,
            int scale,
            long feeExpectedMinor,
            long feeReportedMinor) {}

    List<FeeDecisionRow> feeDecisionsOf(Connection unitOfWork, UUID runId);

    /**
     * The item's conditional {@code PENDING → UNMATCHED}; {@code graceHours} empty leaves
     * no clock running (an unlanded cardinality's item, `P8-TSK-012`'s to dispose).
     */
    boolean markItemUnmatched(
            Connection unitOfWork,
            UUID itemId,
            Optional<Integer> graceHours,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    // ------------------------------------------------------------------ the doors' reads

    record DecisionRow(
            UUID id,
            UUID externalItemId,
            UUID runId,
            String origin,
            UUID ruleSetId,
            Optional<Integer> rulePriority,
            Optional<String> strategy,
            Optional<String> matchedKeyKind,
            String outcome,
            Optional<Integer> claimantRank,
            Optional<Integer> claimantCount,
            Optional<Integer> dateDeviationDays,
            Optional<Integer> timingToleranceDays,
            Optional<Long> feeExpectedMinor,
            Optional<Long> feeReportedMinor,
            Optional<Long> feeToleranceMinor,
            Instant decidedAt,
            LocalDate decidedOn) {}

    record CandidateRow(
            UUID expectationId,
            String keyKind,
            Money amount,
            String direction,
            long remainderBeforeMinor,
            Instant openedAt) {}

    record AllocationRow(
            UUID id,
            UUID decisionId,
            UUID externalItemId,
            UUID expectationId,
            Money amount,
            Optional<UUID> reversesAllocationId,
            Instant createdAt) {}

    Optional<DecisionRow> decision(Connection unitOfWork, UUID decisionId);

    List<CandidateRow> candidatesOf(Connection unitOfWork, UUID decisionId);

    List<AllocationRow> allocationsOfDecision(Connection unitOfWork, UUID decisionId);

    Optional<AllocationRow> allocation(Connection unitOfWork, UUID allocationId);

    Optional<RunRow> run(Connection unitOfWork, UUID runId);

    List<RunRow> runs(Connection unitOfWork, int limit);

    /** The run's decisions per outcome — the completion event's counts. */
    Map<DecisionOutcome, Long> outcomeCounts(Connection unitOfWork, UUID runId);
}
