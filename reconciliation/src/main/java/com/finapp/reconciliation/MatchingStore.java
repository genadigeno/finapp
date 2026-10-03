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

    /** When the run was born - the start of {@code finapp.reconciliation.run.latency}. */
    Optional<java.time.Instant> runBornAt(Connection unitOfWork, UUID runId);

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

    /**
     * Locks the chunk's item rows, sorted by id (`DISTRIBUTED_EXECUTION.md` §3), and returns
     * the ids still {@code PENDING} under their locks: a waiter re-reads each row's status
     * after the holder commits, so an item another writer decided between the lock-free chunk
     * read and these locks is handed back to no caller and never decided twice (the
     * Phase 8 -> 9 transition's REC-10).
     */
    java.util.Set<UUID> lockItems(Connection unitOfWork, Collection<UUID> itemIds);

    // ------------------------------------------------------------------ writes

    /**
     * What a decision judged and concluded, stored so its replay re-runs it exactly
     * (`P8-TSK-022`, ADR-0068 §9, `V012`): the engine's verdict, the item state and value it
     * judged, and the inputs the snapshot rows cannot carry — the fingerprint a matching-engine
     * decision read, a value-date group's membership, a fee's gross. Held by `V012`'s insert
     * trigger for every writer.
     */
    record Basis(
            DecisionVerdict verdict,
            JudgedStatus judgedStatus,
            long judgedMinor,
            Optional<Boolean> fingerprintSeenEarlier,
            Optional<Boolean> groupMembershipComplete,
            Optional<Long> feeGrossMinor) {

        public Basis {
            java.util.Objects.requireNonNull(verdict, "verdict must not be null");
            java.util.Objects.requireNonNull(judgedStatus, "judgedStatus must not be null");
            java.util.Objects.requireNonNull(
                    fingerprintSeenEarlier, "fingerprintSeenEarlier must not be null");
            java.util.Objects.requireNonNull(
                    groupMembershipComplete, "groupMembershipComplete must not be null");
            java.util.Objects.requireNonNull(feeGrossMinor, "feeGrossMinor must not be null");
            if (judgedMinor < 0) {
                throw new IllegalArgumentException("a judged value is never negative");
            }
            if (verdict.engine() == DecisionVerdict.Engine.MATCH
                    && fingerprintSeenEarlier.isEmpty()) {
                throw new IllegalArgumentException(
                        "a matching-engine decision stores its fingerprint input");
            }
            if (verdict.engine() == DecisionVerdict.Engine.GROUP
                    && groupMembershipComplete.isEmpty()) {
                throw new IllegalArgumentException(
                        "a value-date group decision stores its membership input");
            }
        }

        /** A matching-engine conclusion and the fingerprint it read. */
        public static Basis match(
                DecisionVerdict verdict,
                JudgedStatus judged,
                long judgedMinor,
                boolean fingerprintSeenEarlier) {
            return new Basis(
                    verdict, judged, judgedMinor, Optional.of(fingerprintSeenEarlier),
                    Optional.empty(), Optional.empty());
        }

        /** A value-date group's conclusion and its membership input. */
        public static Basis group(
                DecisionVerdict verdict,
                JudgedStatus judged,
                long judgedMinor,
                boolean membershipComplete) {
            return new Basis(
                    verdict, judged, judgedMinor, Optional.empty(),
                    Optional.of(membershipComplete), Optional.empty());
        }

        /** A fee check: within or beyond its tolerance, against its gross (if reached). */
        public static Basis fee(boolean beyond, long judgedMinor, Optional<Long> grossMinor) {
            return fee(beyond, JudgedStatus.PENDING, judgedMinor, grossMinor);
        }

        /**
         * A fee check of an item in {@code judged} - {@code PENDING} at the run, {@code UNMATCHED}
         * when a {@code REPROCESS} run re-checks a contained fee line (REC-6).
         */
        public static Basis fee(
                boolean beyond, JudgedStatus judged, long judgedMinor, Optional<Long> grossMinor) {
            return new Basis(
                    DecisionVerdict.fee(beyond), judged, judgedMinor,
                    Optional.empty(), Optional.empty(), grossMinor);
        }

        /** A correction's, a person's or a contained conclusion: no further input. */
        public static Basis of(DecisionVerdict verdict, JudgedStatus judged, long judgedMinor) {
            return new Basis(
                    verdict, judged, judgedMinor, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
    }

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
            CorrelationId correlation,
            Basis basis) {

        public NewDecision {
            java.util.Objects.requireNonNull(basis, "basis must not be null");
        }

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
                CorrelationId correlation,
                Basis basis) {
            this(id, externalItemId, runId, origin, ruleSetId, rulePriority, strategy,
                    matchedKeyKind, outcome, claimantRank, claimantCount,
                    dateDeviationDays, timingToleranceDays, Optional.empty(),
                    Optional.empty(), Optional.empty(), decidedBy, decidedAt, decidedOn,
                    correlation, basis);
        }
    }

    void insertDecision(Connection unitOfWork, NewDecision decision);

    /**
     * A correction's second input, snapshotted in its order (`P8-TSK-022`, `V012`): the
     * original items' open parked values the correction engine judged.
     */
    void insertParkedOriginals(
            Connection unitOfWork,
            UUID decisionId,
            List<CorrectionEngine.ParkedOriginal> parkedOriginals);

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
    default boolean markItemChecked(
            Connection unitOfWork, UUID itemId, Actor actor, Instant at,
            CorrelationId correlation) {
        return markItemCheckedFrom(unitOfWork, itemId, "PENDING", actor, at, correlation);
    }

    /**
     * The item's conditional exit to {@code CHECKED} from {@code fromStatus}: {@code PENDING} at
     * the run, or {@code UNMATCHED} when a {@code REPROCESS} run re-checks a fee line whose check
     * was contained ({@code V016}'s edge - the Phase 8 -> 9 transition's REC-6).
     */
    boolean markItemCheckedFrom(
            Connection unitOfWork, UUID itemId, String fromStatus, Actor actor, Instant at,
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
     * The operation-anchored rule's reach (`P8-TSK-018`): for each anchor expectation a key
     * reached, the expectation of {@code kind} for the SAME operation ({@code UNIQUE (kind,
     * operation_ref)}) under the anchor's own source - never the anchor itself. Read lock-free:
     * the anchor's operation is a frozen birth fact, and the reached expectation is locked with
     * the chunk's sorted pass.
     */
    List<UUID> anchoredExpectations(
            Connection unitOfWork, List<UUID> anchorIds, ExpectationKind kind);

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

    /**
     * The one open break of this type on the external item, LOCKED - a re-checked fee line's
     * contained {@code PROCESSING_ERROR} (the Phase 8 -> 9 transition's REC-6), taken before the
     * decision that explains it.
     */
    Optional<UUID> lockOpenBreakOnItem(Connection unitOfWork, UUID itemId, BreakType type);

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
     * The source's expired {@code UNMATCHED} items, LOCKED, in claimant order
     * {@code (source_sequence, line_no)} within the expired set — each judged on its locked row
     * (ADR-0073 §7: a candidate committed by a holder of the item's share lock is found and
     * allocated, never parked beside). Only unattributed items and items attributed to one of
     * {@code heldAttributions} are read: an item attributed to a source whose advisory the leg
     * does not hold waits for the next batch. *(Corrected 2026-10-02 by the Phase 8 -> 9
     * transition: this read "oldest {@code grace_until} first", against INV-REC-04's claimant
     * order.)*
     */
    List<ResidualItem> lockExpiredItems(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions, int limit);

    /**
     * Sources holding a residual item whose reach holds an expectation with a remainder that no
     * decision of the item has yet judged ({@link #lockRematchCandidates}' predicate).
     */
    List<UUID> sourcesWithRematchWork(Connection unitOfWork);

    /** The distinct attributed sources among the source's rematch candidates — lock-free. */
    List<UUID> attributedSourcesWithRematchWork(Connection unitOfWork, UUID sourceId);

    /**
     * The source's {@code UNMATCHED} or {@code PARKED} items whose REACH - their keys in their KEY
     * SCOPE (`P8-TSK-016`), an operation-anchored rule's anchor to its operation's expectation,
     * or, for an attributed {@code UNMATCHED} item, an untouched value-date group candidate -
     * holds an expectation still {@code OPEN}/{@code PARTIALLY_SETTLED} with a remainder that no
     * decision of the item has yet judged: recorded as a candidate, or as reached by a late
     * leg's examination ({@link #insertReach}). Judged on rows, never on two clocks, so a reach
     * is examined exactly once and a residual that can never allocate leaves the worklist. A
     * definitive duplicate - an item with a {@code DUPLICATE} decision, or owning an open
     * {@code REPEATED_FINGERPRINT} break - is never read: it leaves only by a person's
     * resolution. An item owning a suspense item of another origin than {@code RECON_PARK} — an
     * unattributed bank line's — is never read either: it has no park to invert. LOCKED in
     * claimant order {@code (source_sequence, line_no)} (INV-REC-04). {@code heldAttributions} as
     * for {@link #lockExpiredItems}. *(Corrected 2026-10-02 by the Phase 8 -> 9 transition: the
     * keyed and value-date clauses compared {@code opened_at} with the latest decision's
     * {@code decided_at} - two instants stamped before two commits, often on two instances'
     * clocks - and carried no remainder condition, and the order was {@code line_no} across
     * runs.)*
     */
    List<ResidualItem> lockRematchCandidates(
            Connection unitOfWork, UUID sourceId, Collection<UUID> heldAttributions, int limit);

    /**
     * The expectations {@link #lockRematchCandidates}' predicate reaches for this item right
     * now - read BEFORE the item is judged, so the examination that records them as reached can
     * never consume an expectation committed after its own reads.
     */
    List<UUID> rematchReach(Connection unitOfWork, UUID itemId);

    /**
     * Records the expectations a late leg's examination reached and judged
     * ({@code reconciliation.match_reach}, `V016`): consumed once, so the rematch predicate
     * never re-locks a reach a decision has already judged.
     */
    void insertReach(Connection unitOfWork, UUID decisionId, Collection<UUID> expectationIds);

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

    /** The ageing leg's keyset position: past this {@code (expected_by, id)}. */
    record OverdueCursor(LocalDate expectedBy, UUID expectationId) {}

    /**
     * Expectations still {@code OPEN}/{@code PARTIALLY_SETTLED} past
     * {@code expected_by + SETTLEMENT_DATE_DAYS} on the database clock with
     * {@code overdue_since} unset — read lock-free in {@code (expected_by, id)} order, past
     * {@code after} when present; each is re-judged under its own row lock by
     * {@link #lockAndMarkOverdue}. The cursor carries one sweep past a row that failed, so a
     * page of failing rows can never hold the leg (the Phase 8 -> 9 transition's ARCH-P8-01).
     */
    List<OverdueCandidate> overdueCandidates(
            Connection unitOfWork, Optional<OverdueCursor> after, int limit);

    /**
     * The one-way fact: locks the expectation row and sets {@code overdue_since} iff
     * still unset, still open and still past its window — false when another sweeper
     * won or the money arrived meanwhile.
     */
    boolean lockAndMarkOverdue(Connection unitOfWork, UUID expectationId, Instant at);

    /** One unresolved break with its ageing facts (`P8-TSK-013`). */
    record EscalationRow(
            UUID breakId, UUID sourceId, Severity severity, long daysSinceRaised,
            long escalations, Instant raisedAt) {}

    /** The escalation leg's keyset position: past this {@code (raised_at, id)}. */
    record EscalationCursor(Instant raisedAt, UUID breakId) {}

    /**
     * Unresolved non-{@code CRITICAL} breaks whose next escalation is DUE - more ageing bands
     * ({@code bandUpperBounds}, days since {@code raised_at} on the database clock) crossed than
     * {@code SEVERITY_ESCALATED} events recorded - oldest first, past {@code after} when present.
     * Selecting the due rows in SQL is what keeps a backlog of older breaks not yet due from
     * holding the page (the Phase 8 -> 9 transition's MI-1).
     */
    List<EscalationRow> dueEscalations(
            Connection unitOfWork,
            List<Long> bandUpperBounds,
            Optional<EscalationCursor> after,
            int limit);

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
     * A contained park's item (the Phase 8 -> 9 transition, REC-3): an {@code UNMATCHED} item
     * whose park failed keeps waiting, owned by the break its decision raised, with its grace
     * clock STOPPED - the expiry predicate drops it, so one item can never hold a leg's
     * worklist. False when the item is no longer {@code UNMATCHED}.
     */
    boolean stopGrace(Connection unitOfWork, UUID itemId);

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

    // ------------------------------------------------------------------ P8-TSK-022

    /** The source's ACTIVE rule set (exactly one, `rule_set_one_active`), read lock-free. */
    UUID activeRuleSetId(Connection unitOfWork, UUID sourceId);

    /** Sources with an {@code OPEN} or {@code IN_PROGRESS} {@code REPROCESS} run. */
    List<UUID> sourcesWithOpenReprocess(Connection unitOfWork);

    /** The source's one open {@code REPROCESS} run (`reconciliation_batch_one_open_reprocess`). */
    Optional<RunRow> openReprocessRun(Connection unitOfWork, UUID sourceId);

    /**
     * The residual items a {@code REPROCESS} run still owes a decision: {@code UNMATCHED} or
     * {@code PARKED}, owning no suspense of another origin, owning no open
     * {@code REPEATED_FINGERPRINT} break (a definitive duplicate's park leaves only by a person's
     * resolution - the Phase 8 -> 9 transition's REC-1), and holding no decision of this run.
     */
    int reprocessWorklistSize(Connection unitOfWork, UUID sourceId, UUID runId);

    List<UUID> attributedSourcesWithReprocessWork(Connection unitOfWork, UUID sourceId, UUID runId);

    /** Locks the next chunk of the reprocess worklist in claimant order. */
    List<ResidualItem> lockReprocessCandidates(
            Connection unitOfWork,
            UUID sourceId,
            UUID runId,
            Collection<UUID> heldAttributions,
            int limit);

    /** The item's parked value - what a parked item's decision judges. */
    long itemParkedMinor(Connection unitOfWork, UUID itemId);

    /** Locks the run's open break of {@code type}, if one stands. */
    Optional<UUID> lockOpenBreakOnRun(Connection unitOfWork, UUID runId, BreakType type);

    /** The run's items whose rematch is merely pending - replay's {@code PENDING_REMATCH}. */
    int pendingRematchOf(Connection unitOfWork, UUID runId);

    /** Locks the run row, for a person's act on it. */
    Optional<RunRow> lockRun(Connection unitOfWork, UUID runId);

    /**
     * {@code BLOCKED -> IN_PROGRESS}, its failures reset, with the reasoned history row; false
     * when the run was not {@code BLOCKED} (the conditional edge, for any racing writer).
     */
    boolean requeueRun(
            Connection unitOfWork,
            UUID runId,
            Actor actor,
            String reason,
            Instant at,
            CorrelationId correlation);

    List<RunRow> runs(Connection unitOfWork, int limit);

    /** The run's decisions per outcome — the completion event's counts. */
    Map<DecisionOutcome, Long> outcomeCounts(Connection unitOfWork, UUID runId);

    /**
     * The run's FIRST decisions per outcome - origin {@code RUN} alone, never a later leg's
     * decision that carries the run's id (`P8-TSK-024`, the match rate's numerator).
     */
    Map<DecisionOutcome, Long> firstDecisionCounts(Connection unitOfWork, UUID runId);
}
