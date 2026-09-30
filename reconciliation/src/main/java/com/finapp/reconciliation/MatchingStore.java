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
            String correlationId,
            Optional<String> settlementCycle) {

        public RunRow {
            java.util.Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        }

        /** A run of a source with no cycles — the `P8-TSK-011` shape. */
        public RunRow(
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
                String correlationId) {
            this(id, sourceId, batchId, status, ruleSetId, sourceSequence, itemCount, cursor,
                    failures, businessDate, correlationId, Optional.empty());
        }
    }

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

    /**
     * One item as the matcher reads it. {@code positionPurpose} is empty for a bank fee and an
     * unattributed bank line (`P8-TSK-016`, `V008`'s position rule); {@code attributedSourceId}
     * is present exactly for an attributed bank credit or debit — the source whose remittance
     * pattern claimed it, and so the KEY SCOPE its expectation keys are judged in (ADR-0068
     * §1). The item's own keys stay stored under its own source.
     */
    record ChunkItem(
            UUID id,
            long lineNo,
            ExternalLineType lineType,
            ExpectationDirection direction,
            Money amount,
            Optional<com.finapp.ledger.AccountPurpose> positionPurpose,
            Optional<UUID> attributedSourceId,
            LocalDate businessDate,
            Optional<LocalDate> settlementDate,
            Optional<LocalDate> valueDate,
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

        /**
         * The source whose expectation keys this item is judged against: its attributed
         * source when a remittance pattern claimed it, else its own ({@code ownSource}, the
         * run's).
         */
        public UUID keyScope(UUID ownSource) {
            return attributedSourceId.orElse(ownSource);
        }

        /** The date a value-date group judges: the line's value date, else its business day. */
        public LocalDate groupDate() {
            return valueDate.orElse(businessDate);
        }

        /** Identifiers only — an amount in a log line is `INV-AUD-02`'s to refuse. */
        @Override
        public String toString() {
            return "ChunkItem[" + id + ", " + lineType + "]";
        }
    }

    /**
     * The run's next {@code PENDING} items past {@code cursor}, in {@code line_no} order, keys
     * joined. An item born disposed — an unattributed bank line is born {@code PARKED} in the
     * acceptance transaction that births the run (`P8-TSK-016`) — is never read, so never
     * decided; a run whose remaining items are all disposed reads an empty chunk and
     * completes.
     */
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

    /**
     * Untouched {@code OPEN} expectations of one source, kind, direction and currency promised
     * for {@code expectedBy} — nothing allocated and nothing resolved — sorted by id: a
     * value-date group's candidates (`P8-TSK-016`). Lock-free; the caller locks and re-reads.
     */
    List<UUID> groupCandidates(
            Connection unitOfWork,
            UUID sourceId,
            ExpectationKind kind,
            ExpectationDirection direction,
            com.finapp.sharedkernel.money.CurrencyCode currency,
            LocalDate expectedBy);

    /**
     * Locks the expectations sorted by id and reads the facts the engine snapshots; an id
     * {@code reachedBy} does not name was reached by no key (a value-date group's candidate).
     */
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
     * Records the cycle a cycle-less expectation learned from this item's report
     * (`P8-TSK-017`): written once, equal to the item's run's cycle — `V009`'s every-writer rule
     * refuses a second write and any other value.
     */
    void recordLearnedCycle(Connection unitOfWork, UUID itemId, String cycle);

    /** The expectation's pinned rule set — the one its own breaks are judged under. */
    UUID expectationRuleSet(Connection unitOfWork, UUID expectationId);

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
            String correlationId, Optional<String> runCycle) {

        public ResidualItem {
            java.util.Objects.requireNonNull(runCycle, "runCycle must not be null");
        }

        /** A residual of a source with no cycles — the `P8-TSK-013` shape. */
        public ResidualItem(
                ChunkItem item, UUID runId, UUID sourceId, UUID ruleSetId,
                String correlationId) {
            this(item, runId, sourceId, ruleSetId, correlationId, Optional.empty());
        }
    }

    /** Sources holding an {@code UNMATCHED} item whose grace has passed (database clock). */
    List<UUID> sourcesWithExpiredGrace(Connection unitOfWork);

    /**
     * The distinct attributed sources among the source's expired items — read lock-free, so
     * the leg takes their advisories before any row lock (`P8-TSK-016`).
     */
    List<UUID> attributedSourcesWithExpiredGrace(Connection unitOfWork, UUID sourceId);

    /**
     * The source's expired {@code UNMATCHED} items, LOCKED, oldest {@code grace_until}
     * first — each judged on its locked row (ADR-0073 §7: a candidate committed by a
     * holder of the item's share lock is found and allocated, never parked beside). Only
     * unattributed items and items attributed to one of {@code heldAttributions} are read: an
     * item attributed to a source whose advisory the leg does not hold waits for the next
     * batch.
     */
    List<ResidualItem> lockExpiredItems(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions, int limit);

    /**
     * Sources holding a residual item whose keys reach an expectation opened later, or an
     * attributed {@code UNMATCHED} item for which a value-date group candidate opened later.
     */
    List<UUID> sourcesWithRematchWork(Connection unitOfWork);

    /** The distinct attributed sources among the source's rematch candidates — lock-free. */
    List<UUID> attributedSourcesWithRematchWork(Connection unitOfWork, UUID sourceId);

    /**
     * The source's {@code UNMATCHED} or {@code PARKED} items whose keys now reach an
     * expectation opened AFTER their latest decision — in their KEY SCOPE (`P8-TSK-016`: an
     * attributed item's keys are judged under its attributed source) — and the attributed
     * {@code UNMATCHED} items for which an untouched value-date group candidate opened after
     * their latest decision, LOCKED, oldest first. An item owning a suspense item of another
     * origin than {@code RECON_PARK} — an unattributed bank line's — is never read: it has no
     * park to invert and leaves by a person's resolution. {@code heldAttributions} as for
     * {@link #lockExpiredItems}.
     */
    List<ResidualItem> lockRematchCandidates(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions, int limit);

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

    /**
     * One {@code PROCESSING_FEE} decision per row of the run, in claimant order — the per-batch
     * fold. A bank fee is judged per line only (`P8-TSK-016`: the bank's terms are flat, and no
     * batch bound is pinned for them), so it never enters the fold.
     */
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

    /** {@code keyKind} is null exactly for a value-date group's candidate (`V008`). */
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
