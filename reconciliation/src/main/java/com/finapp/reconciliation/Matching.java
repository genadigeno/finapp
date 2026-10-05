package com.finapp.reconciliation;

import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The matcher's four legs, each per source under the same namespace-4 try-lock: the RUN leg
 * (`P8-TSK-011`), time's GRACE and REMATCH legs (`P8-TSK-013`, {@link #sweep}) and a person's
 * REPROCESS leg (`P8-TSK-022`), each documented on its own batch method. *(Corrected 2026-10-01,
 * `P8-DOC-001`: this javadoc described the run leg alone.)*
 *
 * <p>The run leg (`P8-TSK-011`, ADR-0068 §§3–6): per source, under
 * {@code pg_try_advisory_xact_lock(4, hashtext(source_id::text))} per chunk — an instance
 * refused the lock moves to the next source (the {@code OutboxRelay} argument) — the
 * lowest-sequence eligible run is walked chunk by chunk in {@code line_no} order, each
 * chunk one transaction that re-reads the cursor and advances it, so a crash resumes on
 * any instance exactly where the last commit left off.
 *
 * <p><strong>The lock only orders; the arbiters are PostgreSQL's</strong>: each item's
 * status re-read under its own row lock — a writer that waited skips what left
 * {@code PENDING} meanwhile, never deciding it twice or raising a break beside the
 * winner's — the item's conditional exit from {@code PENDING}, which THROWS when it
 * updates no row so the attempt rolls back with its decision (the Phase 8 -> 9
 * transition's REC-10 and IDEM-3), `V005`'s allocation unique and deferred Σ
 * triggers, and the append-only grants — proven with the try-lock bypassed (the
 * {@code claimSource} seam scenario 3's variant overrides). A poisoned item is contained
 * under a savepoint: an {@code ERRORED} decision, the remainder parked with its
 * {@code PROCESSING_ERROR} break, and the chunk carries on — the rows behind it are other
 * people's money. A run failing {@code blockAfterFailures} chunks in a row moves to
 * {@code BLOCKED} with a CRITICAL {@code RUN_BLOCKED} break, holding its source visibly.
 *
 * <p><strong>Bank lines</strong> (`P8-TSK-016`): an attributed bank credit or debit is judged
 * in its attributed report source's KEY SCOPE — its {@code REMITTANCE_REF} first, and only when
 * that reaches nothing, the value date's untouched remittances through {@link GroupMatch}
 * (exact total or wait, never a subset); a difference against a remittance is
 * {@code SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS)}; a bank fee is judged flat through the
 * {@code CHECK} route. Because a bank leg may lock the report source's committed breaks, it
 * takes the attributed sources' advisories before any row lock. A line born disposed (an
 * unattributed bank line, {@code PARKED} at acceptance) is never read by the chunk.
 */
@Slf4j
public class Matching {

    /** Namespace 4, registered in `DISTRIBUTED_EXECUTION.md` §3 and pinned by test. */
    public static final int ADVISORY_NAMESPACE = 4;

    static final String CLAIM_SQL = "SELECT pg_try_advisory_xact_lock(4, hashtext(?::text))";

    /** Bounds refused at zero: a chunk of nothing and a block that never comes. */
    public record Config(int chunkSize, int blockAfterFailures) {

        public Config {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be positive");
            }
            if (blockAfterFailures <= 0) {
                throw new IllegalArgumentException("blockAfterFailures must be positive");
            }
        }
    }

    public record SweepResult(
            int sources,
            int chunks,
            int decided,
            int completedRuns,
            int blockedRuns,
            int graced,
            int rematched,
            int reprocessed) {}

    private final MatchingStore store;
    private final MatchingRules rules;
    private final BreakRegister breaks;
    private final Suspense suspense;
    private final Resolutions resolutions;
    private final InternalReferenceLookup lookup;
    private final LedgerAccountStore<Connection> accounts;
    private final OutboxWriter<Connection> outbox;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final Config config;
    private final TransactionRunner transactions;
    private final ReconciliationTelemetry telemetry;
    private final PositionAccounts positionAccounts;

    /** The matcher without telemetry - its suites' shape (`P8-TSK-024`). */
    public Matching(
            MatchingStore store,
            MatchingRules rules,
            BreakRegister breaks,
            Suspense suspense,
            Resolutions resolutions,
            InternalReferenceLookup lookup,
            LedgerAccountStore<Connection> accounts,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            Config config,
            TransactionRunner transactions) {
        this(store, rules, breaks, suspense, resolutions, lookup, accounts, outbox, audit, ids,
                clock, config, transactions, ReconciliationTelemetry.NONE);
    }

    public Matching(
            MatchingStore store,
            MatchingRules rules,
            BreakRegister breaks,
            Suspense suspense,
            Resolutions resolutions,
            InternalReferenceLookup lookup,
            LedgerAccountStore<Connection> accounts,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            Config config,
            TransactionRunner transactions,
            ReconciliationTelemetry telemetry) {
        this(store, rules, breaks, suspense, resolutions, lookup, accounts, outbox, audit, ids,
                clock, config, transactions, telemetry, PositionAccounts.operational(accounts));
    }

    /**
     * The matcher whose items' positions resolve through the composed register (`P9-TSK-011`): a
     * counterparty's source parks on that counterparty's own account (ADR-0078).
     */
    public Matching(
            MatchingStore store,
            MatchingRules rules,
            BreakRegister breaks,
            Suspense suspense,
            Resolutions resolutions,
            InternalReferenceLookup lookup,
            LedgerAccountStore<Connection> accounts,
            OutboxWriter<Connection> outbox,
            AuditWriter<Connection> audit,
            IdGenerator ids,
            Clock clock,
            Config config,
            TransactionRunner transactions,
            ReconciliationTelemetry telemetry,
            PositionAccounts positionAccounts) {
        this.positionAccounts = Objects.requireNonNull(positionAccounts, "positionAccounts must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.breaks = Objects.requireNonNull(breaks, "breaks must not be null");
        this.suspense = Objects.requireNonNull(suspense, "suspense must not be null");
        this.resolutions =
                Objects.requireNonNull(resolutions, "resolutions must not be null");
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.transactions =
                Objects.requireNonNull(transactions, "transactions must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    /**
     * The sweep: every source with work, each until its eligible run rests or blocks;
     * then time's legs (`P8-TSK-013`) — grace over the expired waiters and rematch over
     * the residuals whose keys now reach a later expectation — each per source under the
     * same namespace-4 try-lock, in bounded batches, failures contained per batch.
     */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    public SweepResult sweep() {
        int chunks = 0;
        int decided = 0;
        int completed = 0;
        int blocked = 0;
        int graced = 0;
        int rematched = 0;
        int reprocessed = 0;
        List<UUID> sources;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            sources = transactions.inTransaction(store::sourcesWithWork);
            for (UUID source : sources) {
                while (true) {
                    ChunkOutcome outcome = chunkContained(source);
                    if (outcome.kind() == ChunkOutcome.Kind.CHUNKED
                            || outcome.kind() == ChunkOutcome.Kind.COMPLETED) {
                        chunks++;
                        decided += outcome.decided();
                    }
                    if (outcome.kind() == ChunkOutcome.Kind.COMPLETED) {
                        completed++;
                        continue; // The next run of this source may now be eligible.
                    }
                    if (outcome.kind() == ChunkOutcome.Kind.BLOCKED) {
                        blocked++;
                    }
                    if (outcome.kind() != ChunkOutcome.Kind.CHUNKED) {
                        break; // Skipped, rested, failed or blocked: next source.
                    }
                }
            }
            for (UUID source : transactions.inTransaction(store::sourcesWithExpiredGrace)) {
                graced += legContained("grace", source, this::graceBatch);
            }
            List<UUID> reprocessing = transactions.inTransaction(store::sourcesWithOpenReprocess);
            for (UUID source : transactions.inTransaction(store::sourcesWithRematchWork)) {
                if (reprocessing.contains(source)) {
                    // A person's open REPROCESS run re-decides every residual of the source under
                    // the version it pinned; time's rematch waits for it to complete. Ordering
                    // only - both legs hold the same try-lock and lock the same item rows, so
                    // either order would converge (the Phase 8 -> 9 transition: the rematch's
                    // reach is judged on rows now, and an activation alone can give a residual
                    // a reach no decision judged).
                    continue;
                }
                // The rematch drains while its batches PROGRESS - each residual acted on or its
                // reach consumed - and reports what it ACTED on (the Phase 8 -> 9 transition's
                // rematch-key-clause-reselection: a full batch of residents that can never
                // allocate is consumed, never the end of the source's turn).
                int[] acted = {0};
                legContained(
                        "rematch", source,
                        (unitOfWork, rematchSource) ->
                                rematchBatch(unitOfWork, rematchSource, acted));
                rematched += acted[0];
            }
            // A person's REPROCESS runs (`P8-TSK-022`), after time's legs.
            for (UUID source : reprocessing) {
                reprocessed += reprocessContained(source);
            }
        }
        return new SweepResult(
                sources.size(), chunks, decided, completed, blocked, graced, rematched,
                reprocessed);
    }

    /** Drains one leg's batches for one source; a batch failure ends the source's turn. */
    private int legContained(
            String leg,
            UUID source,
            java.util.function.BiFunction<Connection, UUID, Integer> batch) {
        int acted = 0;
        while (true) {
            int step;
            try {
                step =
                        "rematch".equals(leg)
                                ? telemetry.spans().within(
                                        "reconciliation.rematch",
                                        Map.of("source.id", source.toString()),
                                        () -> transactions.inTransaction(
                                                unitOfWork -> batch.apply(unitOfWork, source)))
                                : transactions.inTransaction(
                                        unitOfWork -> batch.apply(unitOfWork, source));
            } catch (RuntimeException failure) {
                // Rolled back whole; oldest-first, the next tick retries.
                log.warn(
                        "The {} leg's batch failed and rolled back: {}",
                        leg,
                        failure.getClass().getSimpleName());
                return acted;
            }
            if (step < 0) {
                return acted; // The try-lock was held elsewhere: not this instance's turn.
            }
            acted += step;
            if (step == 0) {
                return acted;
            }
        }
    }

    /**
     * The grace leg (`P8-TSK-013`): expired {@code UNMATCHED} items, judged AGAIN on
     * their locked rows — a candidate committed meanwhile is found and allocated, never
     * parked beside it (ADR-0073 §7) — and only then typed by the lookup and parked
     * with their break: {@code TERMINAL} answers take the definitive `-011` types,
     * anything known-but-not-settled is {@code MISSING_INTERNAL}, an unknown key
     * {@code UNKNOWN_EXTERNAL} (the entry's precedence). Returns −1 when the try-lock
     * was held elsewhere; the expiry predicate self-drains, so each batch makes
     * progress.
     */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    private int graceBatch(Connection unitOfWork, UUID source) {
        if (!claimSource(unitOfWork, source)) {
            return -1;
        }
        List<UUID> attributions = store.attributedSourcesWithExpiredGrace(unitOfWork, source);
        if (!claimAttributedSources(unitOfWork, source, attributions)) {
            return -1; // An attributed source is busy elsewhere: not this instance's turn.
        }
        List<MatchingStore.ResidualItem> residuals =
                store.lockExpiredItems(unitOfWork, source, attributions, config.chunkSize());
        if (residuals.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        List<Suspense.ParkedItem> parks = new ArrayList<>();
        List<OffsetIntent> noReleases = new ArrayList<>(); // The grace leg releases nothing.
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        for (MatchingStore.ResidualItem residual : residuals) {
            CorrelationId correlation = CorrelationId.of(residual.correlationId());
            try (CorrelationContext.Scope scope =
                    CorrelationContext.enter(Correlation.startingWith(correlation))) {
                ItemSavepoint savepoint =
                        savepoint(unitOfWork, residual.item(), parks, noReleases, claimantRanks);
                try {
                    graceOne(unitOfWork, residual, claimantRanks, parks, now, correlation);
                } catch (RuntimeException poisoned) {
                    rollbackTo(unitOfWork, savepoint, parks, noReleases, claimantRanks);
                    containPoisoned(
                            unitOfWork, syntheticRun(residual), residual.item(),
                            JudgedStatus.UNMATCHED, parks, now, correlation, poisoned);
                }
            }
        }
        // The batch's postings carry the leg's own correlation (INV-LED-05's scope);
        // each item's records above carry the ingesting flow's.
        CorrelationId batchCorrelation = CorrelationId.generate(ids);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(batchCorrelation))) {
            postPhaseContained(unitOfWork, source, residuals.get(0).ruleSetId(), parks,
                    List.of(), now, batchCorrelation);
        }
        return residuals.size();
    }

    private void graceOne(
            Connection unitOfWork,
            MatchingStore.ResidualItem residual,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        MatchingStore.RunRow run = syntheticRun(residual);
        Resolution resolution =
                resolve(unitOfWork, residual.ruleSetId(), residual.sourceId(), item);
        Map<UUID, MatchEngine.HitFacts> lockedHits =
                lockResolutionHits(unitOfWork, resolution);
        int tolerance = rules.settlementDateToleranceDays(unitOfWork, residual.ruleSetId());
        // Every late leg judges the item with its TRUE fingerprint, stored in the basis so the
        // replay re-runs what was judged (the Phase 8 -> 9 transition's REC-1: the late legs had
        // judged every residual as never seen before). Strictly EARLIER in claimant order, so a
        // waiting item is never its own duplicate.
        boolean fingerprintSeen = fingerprintSeen(unitOfWork, residual.sourceId(), item);
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        facts(run, item, fingerprintSeen), fired, resolution.anyLandedRule(),
                        tolerance);
        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // What this decision saw and concluded, stored whole (`P8-TSK-022`).
        Snapshot seen = Snapshot.of(fired);
        MatchingStore.Basis judged =
                MatchingStore.Basis.match(
                        DecisionVerdict.of(verdict.kind()), JudgedStatus.UNMATCHED,
                        item.amount().minorUnits(), fingerprintSeen);
        switch (verdict.kind()) {
            case ALLOCATE ->
                    applyAllocation(
                            unitOfWork, run, item, fired.orElseThrow(), verdict,
                            decisionId, decidedOn, lockedHits, claimantRanks, parks,
                            tolerance, DecisionOrigin.RUN, "UNMATCHED", now, correlation);
            case AMBIGUOUS ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                            Optional.empty(), parks, now, correlation);
            case DIRECTION_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.DIRECTION_CONTRADICTED,
                            Optional.empty(), parks, now, correlation);
            case CURRENCY_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                            Optional.empty(), parks, now, correlation);
            case DUPLICATE ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.DUPLICATE_EXTERNAL,
                            fingerprintSeen
                                    ? BreakCause.REPEATED_FINGERPRINT
                                    : BreakCause.EXPECTATION_EXHAUSTED,
                            Optional.empty(), parks, now, correlation);
            case NO_CANDIDATES, NO_RULE -> {
                Snapshot typedSeen = seen;
                MatchingStore.Basis typedBasis = judged;
                if (verdict.kind() == MatchEngine.VerdictKind.NO_CANDIDATES
                        && resolution.groupRule().isPresent()) {
                    // The value-date group judged again before anything parks (ADR-0073
                    // section 7's rule, P8-TSK-016): candidates committed while the item
                    // waited are found and allocated, never parked beside.
                    GroupMatch.Verdict group =
                            groupVerdict(
                                    unitOfWork, item, residual.sourceId(), resolution,
                                    lockedHits, java.util.Set.of());
                    if (group.kind() == GroupMatch.Kind.MATCH) {
                        applyGroupMatch(
                                unitOfWork, run, item, resolution.groupRule().get(), group,
                                decisionId, decidedOn, lockedHits, claimantRanks, tolerance,
                                DecisionOrigin.RUN, "UNMATCHED", now, correlation);
                        return;
                    }
                    if (group.kind() == GroupMatch.Kind.MEMBERSHIP_MOVED) {
                        // A candidate committed after the lock-free read: parking now could
                        // park beside its owner. Nothing is written; the next batch reads
                        // the whole group and judges it.
                        return;
                    }
                    typedSeen =
                            group.candidates().isEmpty()
                                    ? Snapshot.none()
                                    : Snapshot.group(
                                            resolution.groupRule().get(), group.candidates());
                    typedBasis =
                            MatchingStore.Basis.group(
                                    DecisionVerdict.of(group.kind()), JudgedStatus.UNMATCHED,
                                    item.amount().minorUnits(), true);
                }
                InternalReferenceLookup.InternalReference internal =
                        classifyThroughLookup(unitOfWork, run, item);
                if (internal.classification() == InternalClassification.TERMINAL) {
                    boolean refund = namesARefund(item, internal);
                    applyDefinitive(
                            unitOfWork, run, item, typedSeen, typedBasis, decisionId,
                            decidedOn,
                            refund ? BreakType.REFUND_MISMATCH : BreakType.REVERSAL_MISMATCH,
                            refund
                                    ? BreakCause.REFUND_CONTRADICTED
                                    : BreakCause.TERMINAL_STATE_CONTRADICTED,
                            Optional.of(internal), parks, now, correlation);
                    return;
                }
                if (resolution.anchored()
                        && internal.classification() != InternalClassification.UNKNOWN) {
                    // A returned payout whose operation the platform knows, but whose return
                    // was never applied within grace (`P8-TSK-018`, ADR-0069 section 2): the
                    // matcher cannot apply it - `P8-TSK-019`'s worker does, inside grace - so
                    // the value parks OWNED, for a person's four-eyes transfer back to the
                    // payable (transition decision O2's fallback).
                    applyDefinitive(
                            unitOfWork, run, item, typedSeen, typedBasis, decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.RETURN_NOT_APPLICABLE,
                            Optional.of(internal), parks, now, correlation);
                    return;
                }
                // Grace has run out: the remainder is OWNED now, its classification the
                // lookup's frozen answer (INV-REC-02).
                applyDefinitive(
                        unitOfWork, run, item, typedSeen, typedBasis, decisionId, decidedOn,
                        internal.classification() == InternalClassification.UNKNOWN
                                ? BreakType.UNKNOWN_EXTERNAL
                                : BreakType.MISSING_INTERNAL,
                        BreakCause.GRACE_EXPIRED,
                        Optional.of(internal), parks, now, correlation);
            }
        }
    }

    /**
     * The rematch leg (`P8-TSK-013`): residual items whose reach holds an expectation with a
     * remainder that no decision of theirs has yet judged, in claimant order, re-decided under
     * origin {@code REMATCH} through the shared allocation path — {@code UNMATCHED → MATCHED};
     * {@code PARKED → MATCHED} with the unpark and the owning break resolved {@code EVIDENCED}.
     * Only an allocating verdict acts; anything else records the examination - what it saw and
     * concluded, and the reach it judged - so the reach is consumed once and never re-locks the
     * worklist (the Phase 8 -> 9 transition). Committed decisions, parks and breaks are never
     * edited ({@code INV-HIST-04}). Returns the number that PROGRESSED - acted on or consumed -
     * and adds the number ACTED on to {@code acted}; zero progress ends the drain.
     */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    private int rematchBatch(Connection unitOfWork, UUID source, int[] acted) {
        if (!claimSource(unitOfWork, source)) {
            return -1;
        }
        List<UUID> attributions = store.attributedSourcesWithRematchWork(unitOfWork, source);
        if (!claimAttributedSources(unitOfWork, source, attributions)) {
            return -1; // An attributed source is busy elsewhere: not this instance's turn.
        }
        List<MatchingStore.ResidualItem> residuals =
                store.lockRematchCandidates(
                        unitOfWork, source, attributions, config.chunkSize());
        if (residuals.isEmpty()) {
            return 0;
        }
        // A rematch decides under the version ACTIVE when it runs (ADR-0068 section 8 - the
        // P8-TSK-022 design's find: it had pinned the original run's), read once per batch,
        // lock-free: either side of a racing activation is valid, and each decision pins the
        // version it read.
        UUID ruleSetId = store.activeRuleSetId(unitOfWork, source);
        Instant now = Instant.now(clock);
        List<Suspense.ParkedItem> parks = new ArrayList<>();
        List<OffsetIntent> unparks = new ArrayList<>();
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        int allocated = 0;
        int progressed = 0;
        for (MatchingStore.ResidualItem residual : residuals) {
            CorrelationId correlation = CorrelationId.of(residual.correlationId());
            try (CorrelationContext.Scope scope =
                    CorrelationContext.enter(Correlation.startingWith(correlation))) {
                ItemSavepoint savepoint =
                        savepoint(unitOfWork, residual.item(), parks, unparks, claimantRanks);
                try {
                    // The reach read BEFORE the judgement: an examination consumes only what
                    // its own reads could see, never an expectation committed after them.
                    List<UUID> reach = store.rematchReach(unitOfWork, residual.item().id());
                    Rematched rematched =
                            rematchOne(
                                    unitOfWork, residual, syntheticRun(residual, ruleSetId),
                                    reach, claimantRanks, parks, unparks, now, correlation);
                    if (rematched == Rematched.ACTED) {
                        allocated++;
                    }
                    if (rematched != Rematched.UNJUDGED) {
                        progressed++;
                    }
                } catch (RuntimeException poisoned) {
                    // A rematch is opportunistic: the residual keeps its committed record and
                    // waits for the next tick or later evidence - and everything this attempt
                    // queued or counted in the batch's Java state rolls back with its rows
                    // (the Phase 8 -> 9 transition's ATOM-02).
                    rollbackTo(unitOfWork, savepoint, parks, unparks, claimantRanks);
                    log.warn(
                            "A rematch candidate was skipped: {}",
                            poisoned.getClass().getSimpleName());
                }
            }
        }
        // The batch's postings carry the leg's own correlation (INV-LED-05's scope);
        // each item's records above carry the ingesting flow's.
        CorrelationId batchCorrelation = CorrelationId.generate(ids);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(batchCorrelation))) {
            postPhaseContained(unitOfWork, source, ruleSetId, parks, unparks, now,
                    batchCorrelation);
        }
        acted[0] += allocated;
        return progressed;
    }

    /** What one rematch did: allocated, recorded its examination, or left the item unjudged. */
    private enum Rematched {
        ACTED,
        EXAMINED,
        UNJUDGED
    }

    private Rematched rematchOne(
            Connection unitOfWork,
            MatchingStore.ResidualItem residual,
            MatchingStore.RunRow run,
            List<UUID> reach,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> unparks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        String status = store.itemStatus(unitOfWork, item.id());
        if (!"UNMATCHED".equals(status) && !"PARKED".equals(status)) {
            return Rematched.UNJUDGED; // Disposed meanwhile: converge.
        }
        JudgedStatus judged = JudgedStatus.ofItemStatus(status);
        long judgedMinor = judgedMinor(unitOfWork, item, judged);
        Resolution resolution =
                resolve(unitOfWork, run.ruleSetId(), residual.sourceId(), item);
        Map<UUID, MatchEngine.HitFacts> lockedHits =
                lockResolutionHits(unitOfWork, resolution);
        int tolerance = rules.settlementDateToleranceDays(unitOfWork, run.ruleSetId());
        boolean fingerprintSeen = fingerprintSeen(unitOfWork, residual.sourceId(), item);
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        facts(run, item, fingerprintSeen), fired, resolution.anyLandedRule(),
                        tolerance);
        Snapshot seen = Snapshot.of(fired);
        MatchingStore.Basis basis =
                MatchingStore.Basis.match(
                        DecisionVerdict.of(verdict.kind()), judged, judgedMinor,
                        fingerprintSeen);
        if (verdict.kind() == MatchEngine.VerdictKind.NO_CANDIDATES
                && resolution.groupRule().isPresent()
                && judged == JudgedStatus.UNMATCHED) {
            // A waiting item's value-date group, re-judged now an unjudged candidate stands
            // (P8-TSK-016); a PARKED item re-matches by its reference alone.
            GroupMatch.Verdict group =
                    groupVerdict(
                            unitOfWork, item, residual.sourceId(), resolution, lockedHits,
                            java.util.Set.of());
            if (group.kind() == GroupMatch.Kind.MATCH) {
                applyGroupMatch(
                        unitOfWork, run, item, resolution.groupRule().get(), group, ids.next(),
                        LocalDate.ofInstant(now, ZoneOffset.UTC), lockedHits, claimantRanks,
                        tolerance, DecisionOrigin.REMATCH, "UNMATCHED", now, correlation);
                return Rematched.ACTED;
            }
            if (group.kind() == GroupMatch.Kind.MEMBERSHIP_MOVED) {
                // A candidate committed after the lock-free read: nothing is written, and the
                // next batch reads the whole group (the grace leg's rule).
                return Rematched.UNJUDGED;
            }
            seen =
                    group.candidates().isEmpty()
                            ? Snapshot.none()
                            : Snapshot.group(resolution.groupRule().get(), group.candidates());
            basis =
                    MatchingStore.Basis.group(
                            DecisionVerdict.of(group.kind()), judged, judgedMinor, true);
        } else if (verdict.kind() == MatchEngine.VerdictKind.ALLOCATE) {
            if (judged == JudgedStatus.UNMATCHED) {
                applyAllocation(
                        unitOfWork, run, item, fired.orElseThrow(), verdict, ids.next(),
                        LocalDate.ofInstant(now, ZoneOffset.UTC), lockedHits, claimantRanks,
                        parks, tolerance, DecisionOrigin.REMATCH, "UNMATCHED", now,
                        correlation);
                return Rematched.ACTED;
            }
            switch (applyParkedRematch(
                    unitOfWork, run, residual, fired.orElseThrow(), verdict,
                    DecisionOrigin.REMATCH, claimantRanks, unparks, now, correlation)) {
                case ACTED -> {
                    return Rematched.ACTED;
                }
                case RELEASED -> {
                    return Rematched.UNJUDGED; // Released meanwhile: converge.
                }
                case WAITS -> {
                    // A fuller candidate or a person: examined below, the reach consumed.
                }
            }
        }
        // Nothing changes: the examination records what was seen and concluded, the item as it
        // was, and the reach it judged - consumed, so it never re-locks the worklist.
        UUID decisionId = ids.next();
        recordExamined(
                unitOfWork, run, item, decisionId, DecisionOrigin.REMATCH, seen, basis,
                judged == JudgedStatus.PARKED ? DecisionOutcome.PARKED : DecisionOutcome.UNMATCHED,
                now, correlation);
        store.insertReach(unitOfWork, decisionId, reach);
        return Rematched.EXAMINED;
    }

    /**
     * The reprocess leg (`P8-TSK-022`, ADR-0068 section 9.2): a person's {@code REPROCESS} run
     * re-decides the source's residual items - {@code UNMATCHED} or {@code PARKED} - under the
     * version it pinned, in claimant order, a chunk per transaction under the same namespace-4
     * try-lock as every other leg. Every item examined gets ONE decision of this run - its
     * allocation through the shared path, or what it saw and concluded when nothing changes -
     * so the worklist's anti-join is the run's progress, and committed history is never edited.
     * Contained like a chunk: a failure counts against the run, and {@code blockAfterFailures}
     * in a row block it with its {@code RUN_BLOCKED} break, for a person's requeue.
     */
    private int reprocessContained(UUID source) {
        int acted = 0;
        while (true) {
            UUID[] runHolder = new UUID[1];
            int step;
            try {
                step = transactions.inTransaction(uow -> reprocessBatch(uow, source, runHolder));
            } catch (RuntimeException failure) {
                log.warn(
                        "A reprocess chunk failed and rolled back: {}",
                        failure.getClass().getSimpleName());
                UUID runId = runHolder[0];
                if (runId != null) {
                    // The failure's own record survives the rollback (the chunk's shape).
                    transactions.inTransaction(uow -> recordFailure(uow, source, runId));
                }
                return acted;
            }
            if (step < 0) {
                return acted; // The try-lock was held elsewhere: not this instance's turn.
            }
            acted += step;
            if (step < config.chunkSize()) {
                return acted; // The last chunk completed the run, or nothing was open.
            }
        }
    }

    @SuppressWarnings("try") // Scopes are used for their close side effect.
    private int reprocessBatch(Connection unitOfWork, UUID source, UUID[] runHolder) {
        if (!claimSource(unitOfWork, source)) {
            return -1;
        }
        Optional<MatchingStore.RunRow> open = store.openReprocessRun(unitOfWork, source);
        if (open.isEmpty()) {
            return 0;
        }
        runHolder[0] = open.get().id();
        List<UUID> attributions =
                store.attributedSourcesWithReprocessWork(unitOfWork, source, open.get().id());
        if (!claimAttributedSources(unitOfWork, source, attributions)) {
            return -1;
        }
        // The arbiter beneath the try-lock, which only orders (the seam's contract): the run
        // row, locked after every advisory and before any item. A second instance waits here,
        // then reads this run's committed decisions afresh in its next statement - so an item
        // examined unchanged, whose row nothing updates, is never examined twice (the
        // completion gate's find, `P8-TSK-022`).
        Optional<MatchingStore.RunRow> locked =
                store.lockRun(unitOfWork, open.get().id())
                        .filter(row -> row.status() == RunStatus.OPEN
                                || row.status() == RunStatus.IN_PROGRESS);
        if (locked.isEmpty()) {
            return 0; // Completed by another instance meanwhile.
        }
        MatchingStore.RunRow run = locked.get();
        Instant now = Instant.now(clock);
        // The person's request flow, restored per chunk (the run leg's ADR-0068 section 11).
        CorrelationId correlation = CorrelationId.of(run.correlationId());
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(correlation))) {
            if (run.status() == RunStatus.OPEN) {
                store.markRunInProgress(unitOfWork, run.id(), SecurityContext.require(), now);
            }
            List<MatchingStore.ResidualItem> residuals =
                    store.lockReprocessCandidates(
                            unitOfWork, source, run.id(), attributions, config.chunkSize());
            List<Suspense.ParkedItem> parks = new ArrayList<>();
            List<OffsetIntent> unparks = new ArrayList<>();
            Map<UUID, Integer> claimantRanks = new HashMap<>();
            for (MatchingStore.ResidualItem residual : residuals) {
                MatchingStore.RunRow decisionRun = reprocessView(run, residual);
                ItemSavepoint savepoint =
                        savepoint(unitOfWork, residual.item(), parks, unparks, claimantRanks);
                try {
                    reprocessOne(
                            unitOfWork, residual, decisionRun, claimantRanks, parks, unparks,
                            now, correlation);
                } catch (RuntimeException poisoned) {
                    // The attempt's queued parks and unparks and its claimant counts roll back
                    // with its rows (ATOM-02): postPhase never acts on a rolled-back decision.
                    rollbackTo(unitOfWork, savepoint, parks, unparks, claimantRanks);
                    log.warn(
                            "A reprocessed item was contained; it keeps its committed record:"
                                    + " {}",
                            poisoned.getClass().getSimpleName());
                    // An ERRORED decision of this run, so the run moves past it.
                    JudgedStatus judged =
                            JudgedStatus.ofItemStatus(
                                    store.itemStatus(unitOfWork, residual.item().id()));
                    recordExamined(
                            unitOfWork, decisionRun, residual.item(), ids.next(),
                            DecisionOrigin.REPROCESS, Snapshot.none(),
                            MatchingStore.Basis.of(
                                    DecisionVerdict.ERRORED, judged,
                                    judgedMinor(unitOfWork, residual.item(), judged)),
                            DecisionOutcome.ERRORED, now, correlation);
                }
            }
            if (!residuals.isEmpty()) {
                // The cursor counts what this run examined: progress is its decisions.
                store.advanceCursor(unitOfWork, run.id(), run.cursor() + residuals.size(), now);
            }
            if (residuals.size() < config.chunkSize()) {
                completeRun(unitOfWork, run, now, correlation);
            }
            postPhase(unitOfWork, source, run.ruleSetId(), parks, unparks, List.of(), now,
                    correlation);
            return residuals.size();
        }
    }

    private void reprocessOne(
            Connection unitOfWork,
            MatchingStore.ResidualItem residual,
            MatchingStore.RunRow run,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> unparks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        JudgedStatus judged = JudgedStatus.ofItemStatus(store.itemStatus(unitOfWork, item.id()));
        long judgedMinor = judgedMinor(unitOfWork, item, judged);
        Resolution resolution =
                resolve(unitOfWork, run.ruleSetId(), residual.sourceId(), item);
        if (resolution.firedRule().isEmpty()
                && resolution.unlandedRule()
                        .filter(rule -> rule.cardinality() == Cardinality.CHECK)
                        .isPresent()
                && judged == JudgedStatus.UNMATCHED) {
            // A fee line left waiting - its check contained as ITEM_ERRORED, or no CHECK rule
            // in its own version - is CHECKED under this run's version, and the contained
            // PROCESSING_ERROR closes EVIDENCED naming the check: ADR-0069's "reprocess, then
            // EVIDENCED" exit (the Phase 8 -> 9 transition's REC-6). The break is locked first
            // (the section-3 break-first order; this leg holds the source's advisory).
            Optional<UUID> contained =
                    store.lockOpenBreakOnItem(unitOfWork, item.id(), BreakType.PROCESSING_ERROR);
            UUID checked =
                    applyFeeCheck(
                            unitOfWork, run, item, resolution.unlandedRule().get(),
                            DecisionOrigin.REPROCESS, JudgedStatus.UNMATCHED, now, correlation);
            contained.ifPresent(
                    breakId ->
                            resolutions.evidence(
                                    unitOfWork,
                                    new Resolutions.Evidence(
                                            ids.next(),
                                            breakId,
                                            item.amount(),
                                            store.breakResidualVersion(unitOfWork, breakId),
                                            checked,
                                            Optional.empty(),
                                            Optional.empty(),
                                            Optional.empty(),
                                            run.ruleSetId(),
                                            SecurityContext.require(),
                                            now,
                                            correlation)));
            return;
        }
        Map<UUID, MatchEngine.HitFacts> lockedHits =
                lockResolutionHits(unitOfWork, resolution);
        int tolerance = rules.settlementDateToleranceDays(unitOfWork, run.ruleSetId());
        // The TRUE fingerprint (REC-1), stored so the replay re-runs what was judged.
        boolean fingerprintSeen = fingerprintSeen(unitOfWork, residual.sourceId(), item);
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        facts(run, item, fingerprintSeen), fired, resolution.anyLandedRule(),
                        tolerance);
        Snapshot seen = Snapshot.of(fired);
        MatchingStore.Basis basis =
                MatchingStore.Basis.match(
                        DecisionVerdict.of(verdict.kind()), judged, judgedMinor,
                        fingerprintSeen);
        if (verdict.kind() == MatchEngine.VerdictKind.NO_CANDIDATES
                && resolution.groupRule().isPresent()
                && judged == JudgedStatus.UNMATCHED) {
            GroupMatch.Verdict group =
                    groupVerdict(
                            unitOfWork, item, residual.sourceId(), resolution, lockedHits,
                            java.util.Set.of());
            if (group.kind() == GroupMatch.Kind.MATCH) {
                applyGroupMatch(
                        unitOfWork, run, item, resolution.groupRule().get(), group, ids.next(),
                        decidedOn, lockedHits, claimantRanks, tolerance,
                        DecisionOrigin.REPROCESS, "UNMATCHED", now, correlation);
                return;
            }
            seen =
                    group.candidates().isEmpty()
                            ? Snapshot.none()
                            : Snapshot.group(resolution.groupRule().get(), group.candidates());
            basis =
                    MatchingStore.Basis.group(
                            DecisionVerdict.of(group.kind()), judged, judgedMinor,
                            group.kind() != GroupMatch.Kind.MEMBERSHIP_MOVED);
        } else if (verdict.kind() == MatchEngine.VerdictKind.ALLOCATE) {
            if (judged == JudgedStatus.UNMATCHED) {
                applyAllocation(
                        unitOfWork, run, item, fired.orElseThrow(), verdict, ids.next(),
                        decidedOn, lockedHits, claimantRanks, parks, tolerance,
                        DecisionOrigin.REPROCESS, "UNMATCHED", now, correlation);
                return;
            }
            if (applyParkedRematch(
                            unitOfWork, run, residual, fired.orElseThrow(), verdict,
                            DecisionOrigin.REPROCESS, claimantRanks, unparks, now, correlation)
                    == ParkedRematch.ACTED) {
                return;
            }
        }
        // Nothing changes: the decision records what was seen and concluded, the item as it was.
        recordExamined(
                unitOfWork, run, item, ids.next(), DecisionOrigin.REPROCESS, seen, basis,
                judged == JudgedStatus.PARKED ? DecisionOutcome.PARKED : DecisionOutcome.UNMATCHED,
                now, correlation);
    }

    /**
     * A late leg's decision that changed nothing - its snapshot and basis, no other write: a
     * {@code REPROCESS} run's examination, or a {@code REMATCH} that judged a new reach and could
     * not act (the Phase 8 -> 9 transition). Written straight to the store: an examination is no
     * late record found, so it never reaches the rematch series (`P8-TSK-024`'s gate).
     */
    private void recordExamined(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            UUID decisionId,
            DecisionOrigin origin,
            Snapshot snapshot,
            MatchingStore.Basis basis,
            DecisionOutcome outcome,
            Instant now,
            CorrelationId correlation) {
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        origin,
                        run.ruleSetId(),
                        snapshot.rulePriority(),
                        snapshot.strategy(),
                        snapshot.keyKind(),
                        outcome,
                        Optional.empty(),
                        snapshot.claimantCount(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        LocalDate.ofInstant(now, ZoneOffset.UTC),
                        correlation,
                        basis));
        store.insertCandidates(unitOfWork, decisionId, snapshot.candidates());
        store.insertParkedOriginals(unitOfWork, decisionId, snapshot.parkedOriginals());
    }

    /** The value a decision on this item judges: a parked item's remainder, else its amount. */
    private long judgedMinor(
            Connection unitOfWork, MatchingStore.ChunkItem item, JudgedStatus judged) {
        return judged == JudgedStatus.PARKED
                ? store.itemParkedMinor(unitOfWork, item.id())
                : item.amount().minorUnits();
    }

    /**
     * A reprocess decision's run view: the REPROCESS run's identity and pinned version, the
     * item's own run's cycle (what the cycle comparison reads) - never persisted.
     */
    private static MatchingStore.RunRow reprocessView(
            MatchingStore.RunRow run, MatchingStore.ResidualItem residual) {
        return new MatchingStore.RunRow(
                run.id(),
                residual.sourceId(),
                null,
                run.status(),
                run.ruleSetId(),
                0L,
                0,
                0L,
                0,
                residual.item().businessDate(),
                run.correlationId(),
                residual.runCycle());
    }

    /** What a parked rematch did: allocated, waits for a fuller candidate, or found it released. */
    private enum ParkedRematch {
        ACTED,
        WAITS,
        RELEASED
    }

    /** {@code PARKED -> MATCHED}: the parked value allocated whole, unparked, EVIDENCED. */
    private ParkedRematch applyParkedRematch(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ResidualItem residual,
            MatchEngine.FiredRule rule,
            MatchEngine.Verdict verdict,
            DecisionOrigin origin,
            Map<UUID, Integer> claimantRanks,
            List<OffsetIntent> unparks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        List<CorrectionEngine.ParkedOriginal> parked =
                store.lockParkedOriginals(unitOfWork, List.of(item.id()));
        if (parked.isEmpty()) {
            return ParkedRematch.RELEASED; // Released meanwhile: converge.
        }
        CorrectionEngine.ParkedOriginal suspenseRow = parked.get(0);
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        long parkedRemainder = suspenseRow.remainderMinor();
        if (candidate.remainderMinor() < parkedRemainder
                || !candidate.amount().currency().equals(suspenseRow.currency())) {
            // A partial unpark would leave a split record; the residual waits for a
            // fuller candidate or a person (the design's G2 refinement, recorded).
            return ParkedRematch.WAITS;
        }
        Money amount =
                Money.ofPersisted(
                        parkedRemainder, suspenseRow.currency(), suspenseRow.scale());
        boolean settles = parkedRemainder == candidate.remainderMinor();
        Optional<UUID> overdueBreak =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                BreakType.MISSING_EXTERNAL)
                        : Optional.empty();
        // The remainder's last tranche settles the expectation: its earlier shortfall break -
        // AMOUNT_MISMATCH, or a remittance's SETTLEMENT_MISMATCH - is explained to zero too,
        // locked after the overdue break (applyAllocation's order) and closed EVIDENCED below,
        // never left open over a SETTLED expectation (the Phase 8 -> 9 transition's REC-5).
        Optional<UUID> shortfallBreak =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                differenceType(candidate.kind()))
                        : Optional.empty();
        UUID decisionId = ids.next();
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        origin,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(rule.cardinality()),
                        candidate.reachedBy(),
                        DecisionOutcome.MATCHED,
                        Optional.of(
                                claimantRanks.merge(
                                        candidate.expectationId(), 1, Integer::sum)),
                        Optional.of(1),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        LocalDate.ofInstant(now, ZoneOffset.UTC),
                        correlation,
                        // The parked value judged whole (`P8-TSK-022`): its replay allocates
                        // this remainder or nothing.
                        MatchingStore.Basis.match(
                                DecisionVerdict.ALLOCATE, JudgedStatus.PARKED, parkedRemainder,
                                false)));
        // Every hit the rule saw, as on every allocation (the snapshot the replay re-runs).
        store.insertCandidates(unitOfWork, decisionId, rule.hits());
        store.insertAllocation(
                unitOfWork,
                new MatchingStore.NewAllocation(
                        ids.next(), decisionId, item.id(), candidate.expectationId(),
                        amount, now, correlation));
        ExpectationStatus status =
                store.allocateToExpectation(
                        unitOfWork,
                        candidate.expectationId(),
                        amount,
                        "decision=" + decisionId,
                        SecurityContext.require(),
                        now,
                        correlation);
        store.bumpResidualOnSubjects(unitOfWork, candidate.expectationId(), item.id());
        if (status == ExpectationStatus.SETTLED) {
            ReconciliationEvents.expectationSettled(
                    outbox, unitOfWork, ids, candidate.expectationId(), candidate.kind(),
                    residual.sourceId(), now, correlation);
        }
        exitOrThrow(
                store.markItemMatchedFrom(
                        unitOfWork, item.id(), "PARKED", parkedRemainder,
                        SecurityContext.require(), now, correlation),
                item.id(), "PARKED -> MATCHED");
        // The cycle observed here as on any allocation (P8-TSK-017, the gate's find): a parked
        // line re-matched to a completion that announced another cycle is its zero-value
        // TIMING_DIFFERENCE - unless an open MISSING_EXTERNAL already states the timing, the L1
        // rule - and one that announced none (a return) learns its run's cycle. The date
        // deviation alone raises nothing on this path, as P8-TSK-013 built it.
        boolean overdueStands =
                overdueBreak.isPresent()
                        || (!settles
                                && store.openBreakExistsOn(
                                        unitOfWork, candidate.expectationId(),
                                        BreakType.MISSING_EXTERNAL));
        if (verdict.timing().map(MatchEngine.Verdict.Timing::cycleShift).orElse(false)
                && !overdueStands) {
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.TIMING_DIFFERENCE,
                            BreakCause.CYCLE_MISMATCH, BreakRegister.Subject.decision(decisionId),
                            Money.ofPersisted(0, amount.currency(), amount.scale()),
                            Optional.empty(), now));
        }
        if (residual.runCycle().isPresent() && candidate.settlementCycle().isEmpty()) {
            store.recordLearnedCycle(unitOfWork, item.id(), residual.runCycle().get());
        }
        unparks.add(
                new OffsetIntent(decisionId, suspenseRow, amount, ReleaseCause.UNPARK));
        for (Optional<UUID> explained : List.of(overdueBreak, shortfallBreak)) {
            explained.ifPresent(
                    breakId ->
                            resolutions.evidence(
                                    unitOfWork,
                                    new Resolutions.Evidence(
                                            ids.next(),
                                            breakId,
                                            amount,
                                            store.breakResidualVersion(unitOfWork, breakId),
                                            decisionId,
                                            Optional.empty(),
                                            Optional.empty(),
                                            Optional.empty(),
                                            run.ruleSetId(),
                                            SecurityContext.require(),
                                            now,
                                            correlation)));
        }
        return ParkedRematch.ACTED;
    }

    /** The synthetic run view over a residual's stored facts — never persisted. */
    private MatchingStore.RunRow syntheticRun(MatchingStore.ResidualItem residual) {
        return syntheticRun(residual, residual.ruleSetId());
    }

    /**
     * The synthetic run view deciding under {@code ruleSetId}: the residual's own run for
     * grace, which concludes that run's decision; the ACTIVE version for a rematch.
     */
    private MatchingStore.RunRow syntheticRun(
            MatchingStore.ResidualItem residual, UUID ruleSetId) {
        return new MatchingStore.RunRow(
                residual.runId(),
                residual.sourceId(),
                null,
                RunStatus.COMPLETED,
                ruleSetId,
                0L,
                0,
                0L,
                0,
                residual.item().businessDate(),
                residual.correlationId(),
                residual.runCycle());
    }

    /**
     * Locks a resolution's hits and value-date group candidates together, sorted by id, and
     * maps them for the engine.
     */
    private Map<UUID, MatchEngine.HitFacts> lockResolutionHits(
            Connection unitOfWork, Resolution resolution) {
        Map<UUID, KeyKind> reachedBy = new HashMap<>();
        resolution.hitIds().forEach(id -> reachedBy.put(id, resolution.reachedBy()));
        java.util.Set<UUID> locked = new java.util.HashSet<>(reachedBy.keySet());
        locked.addAll(resolution.groupCandidateIds());
        Map<UUID, MatchEngine.HitFacts> lockedHits = new HashMap<>();
        for (MatchEngine.HitFacts hit : store.lockExpectations(unitOfWork, locked, reachedBy)) {
            lockedHits.put(hit.expectationId(), hit);
        }
        return lockedHits;
    }

    /**
     * The rule that fired, its hits re-read from the locked rows. Only a key's rule fires
     * here: a resolution names a {@code ONE_TO_ONE} rule with its key, never the keyless
     * value-date group (that is {@link #groupVerdict}'s).
     */
    private Optional<MatchEngine.FiredRule> firedRule(
            Resolution resolution, Map<UUID, MatchEngine.HitFacts> lockedHits) {
        return resolution.firedRule()
                .map(rule ->
                        new MatchEngine.FiredRule(
                                rule.priority(),
                                rule.keyKind().orElseThrow(),
                                rule.expectationKind(),
                                rule.cardinality(),
                                rule.graceHours(),
                                resolution.hitIds().stream()
                                        .map(lockedHits::get)
                                        .filter(Objects::nonNull)
                                        // This item's hits were reached by ITS rule's
                                        // kind, not another item's.
                                        .map(hit ->
                                                new MatchEngine.HitFacts(
                                                        hit.expectationId(),
                                                        hit.kind(),
                                                        hit.direction(),
                                                        hit.amount(),
                                                        hit.remainderMinor(),
                                                        hit.openedAt(),
                                                        hit.expectedBy(),
                                                        rule.keyKind(),
                                                        hit.operationRef(),
                                                        hit.settlementCycle()))
                                        .toList()));
    }

    /**
     * The value-date group's verdict over the locked candidates (`P8-TSK-016`): each re-read
     * under the lock (an earlier claimant's allocation in this transaction shows as a
     * diminished remainder), every candidate a key of ANOTHER item in the same chunk reaches
     * set aside ({@code keyClaims} — a referenced line's claim is never taken by an
     * unreferenced one), and the membership re-read now that the rows are locked: a candidate
     * committed after the lock-free read makes the locked set a part, never the whole, and the
     * item waits.
     */
    private GroupMatch.Verdict groupVerdict(
            Connection unitOfWork,
            MatchingStore.ChunkItem item,
            UUID sourceId,
            Resolution resolution,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            java.util.Set<UUID> keyClaims) {
        MatchingRules.RuleRow rule = resolution.groupRule().orElseThrow();
        List<UUID> standing =
                store.groupCandidates(
                        unitOfWork,
                        item.keyScope(sourceId),
                        groupKind(rule),
                        item.direction(),
                        item.amount().currency(),
                        item.groupDate());
        boolean membershipComplete =
                resolution.groupCandidateIds().containsAll(standing);
        List<MatchEngine.HitFacts> candidates =
                resolution.groupCandidateIds().stream()
                        .filter(id -> !keyClaims.contains(id))
                        .map(lockedHits::get)
                        .filter(Objects::nonNull)
                        // Reached by the date, never by a key: the snapshot carries none.
                        .map(hit ->
                                new MatchEngine.HitFacts(
                                        hit.expectationId(),
                                        hit.kind(),
                                        hit.direction(),
                                        hit.amount(),
                                        hit.remainderMinor(),
                                        hit.openedAt(),
                                        hit.expectedBy(),
                                        Optional.empty(),
                                        hit.operationRef(),
                                        hit.settlementCycle()))
                        .toList();
        return GroupMatch.decide(
                facts(item), item.groupDate(), rule.expectationKind(), candidates,
                membershipComplete);
    }

    /** A value-date group names the expectation kind it discharges (v1: REMITTANCE). */
    private static ExpectationKind groupKind(MatchingRules.RuleRow rule) {
        return rule.expectationKind()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a GROUP_BY_VALUE_DATE rule names the expectation"
                                                + " kind it discharges"));
    }

    private record ChunkOutcome(Kind kind, UUID runId, int decided) {
        enum Kind {
            SKIPPED,
            RESTED,
            CHUNKED,
            COMPLETED,
            FAILED,
            BLOCKED
        }
    }

    /**
     * Every decision this matcher writes, through one door: a late leg's (`REMATCH`,
     * `REPROCESS`) is reported to telemetry, which counts it after the commit (`P8-TSK-024`).
     */
    private void insertDecision(
            MatchingStore.RunRow run,
            Connection unitOfWork,
            MatchingStore.NewDecision decision) {
        store.insertDecision(unitOfWork, decision);
        // A late leg's decision, by outcome; a REPROCESS decision only when it allocated -
        // an examination that changed nothing is no late record found (the gate's find).
        if (decision.origin() == DecisionOrigin.REMATCH
                || (decision.origin() == DecisionOrigin.REPROCESS
                        && decision.outcome() == DecisionOutcome.MATCHED)) {
            telemetry.rematched(run.sourceId(), decision.outcome());
        }
    }

    /** One chunk, contained: its failure is the run's failure, never the sweep's. */
    private ChunkOutcome chunkContained(UUID source) {
        UUID[] runHolder = new UUID[1];
        try {
            return telemetry.spans().within(
                    "reconciliation.chunk",
                    Map.of("source.id", source.toString()),
                    () -> transactions.inTransaction(uow -> chunk(uow, source, runHolder)));
        } catch (RuntimeException chunkFailure) {
            UUID runId = runHolder[0];
            if (runId == null) {
                log.warn(
                        "A matching chunk failed before claiming a run: {}",
                        chunkFailure.getClass().getSimpleName());
                return new ChunkOutcome(ChunkOutcome.Kind.FAILED, null, 0);
            }
            log.warn(
                    "A matching chunk failed and rolled back to the last cursor: {}",
                    chunkFailure.getClass().getSimpleName());
            // The failure's own record survives the rollback (the parse-leg shape).
            return transactions.inTransaction(uow -> recordFailure(uow, source, runId));
        }
    }

    private ChunkOutcome recordFailure(Connection unitOfWork, UUID source, UUID runId) {
        Instant now = Instant.now(clock);
        int failures = store.bumpRunFailures(unitOfWork, runId, now);
        if (failures < config.blockAfterFailures()) {
            return new ChunkOutcome(ChunkOutcome.Kind.FAILED, runId, 0);
        }
        if (!store.blockRun(unitOfWork, runId, SecurityContext.require(), now)) {
            return new ChunkOutcome(ChunkOutcome.Kind.FAILED, runId, 0);
        }
        MatchingStore.RunRow run =
                store.run(unitOfWork, runId)
                        .orElseThrow(
                                () ->
                                        new ReconciliationStorageException(
                                                "a blocked run exists: " + runId));
        breaks.raise(
                unitOfWork,
                new BreakRegister.NewBreak(
                        ids.next(),
                        BreakType.PROCESSING_ERROR,
                        BreakCause.RUN_BLOCKED,
                        BreakRegister.Subject.run(runId),
                        source,
                        run.ruleSetId(),
                        // A blocked run holds no money of its own; the run row carries no
                        // currency, so the zero rides the platform's presentation currency.
                        Money.ofPersisted(0, CurrencyCode.of("EUR"), 2),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        CorrelationId.of(run.correlationId())));
        return new ChunkOutcome(ChunkOutcome.Kind.BLOCKED, runId, 0);
    }

    /** The seam scenario 3's lock-bypass variant overrides: orders, never arbitrates. */
    boolean claimSource(Connection unitOfWork, UUID source) {
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_SQL)) {
            claim.setObject(1, source);
            try (var row = claim.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not try the source's advisory lock", failure);
        }
    }

    /**
     * The attributed sources' advisories (`P8-TSK-016`), after the leg's own try-lock and
     * before any row lock: a bank item settles its attributed report source's
     * {@code REMITTANCE}, whose committed breaks — ageing's {@code MISSING_EXTERNAL} is stamped
     * with the REPORT source — this transaction may lock (L1's closure, the residual bump),
     * and every writer locking a committed break row holds that break's source's namespace-4
     * advisory first (`DISTRIBUTED_EXECUTION.md` §3). Sorted by the UUID's string, the leg's
     * own source skipped (it holds that one already).
     *
     * <p><strong>The TRY form, and a refusal skips the turn</strong> — never a wait. The
     * resolution machine takes a two-source offset's pair BLOCKING in {@code UUID} order, and a
     * report source sorts before the bank, so a bank leg WAITING here on the report source
     * while holding its own could close a cycle with such an offset. Trying instead means this
     * leg never waits on a namespace-4 key while holding one, so no cycle can pass through it;
     * a refused leg writes nothing (the claim comes before any write) and the next tick, on any
     * instance, tries again — the try only orders, exactly as {@link #CLAIM_SQL} does.
     *
     * @return false when an attributed source is held elsewhere: the caller skips its turn
     */
    private boolean claimAttributedSources(
            Connection unitOfWork, UUID ownSource, java.util.Collection<UUID> attributed) {
        java.util.TreeMap<String, UUID> sorted = new java.util.TreeMap<>();
        for (UUID source : attributed) {
            if (!source.equals(ownSource)) {
                sorted.put(source.toString(), source);
            }
        }
        for (UUID source : sorted.values()) {
            if (!claimSource(unitOfWork, source)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("try")
    private ChunkOutcome chunk(Connection unitOfWork, UUID source, UUID[] runHolder) {
        if (!claimSource(unitOfWork, source)) {
            return new ChunkOutcome(ChunkOutcome.Kind.SKIPPED, null, 0);
        }
        Optional<MatchingStore.RunRow> eligible = store.eligibleRun(unitOfWork, source);
        if (eligible.isEmpty()) {
            return new ChunkOutcome(ChunkOutcome.Kind.RESTED, null, 0);
        }
        MatchingStore.RunRow run = eligible.get();
        runHolder[0] = run.id();
        // The ingesting request's correlation, restored per chunk (ADR-0068 §11).
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(run.correlationId())))) {
            return chunkUnderCorrelation(unitOfWork, run);
        }
    }

    private ChunkOutcome chunkUnderCorrelation(
            Connection unitOfWork, MatchingStore.RunRow run) {
        Instant now = Instant.now(clock);
        CorrelationId correlation = CorrelationId.of(run.correlationId());
        if (run.status() == RunStatus.OPEN && run.itemCount() == 0) {
            completeRun(unitOfWork, run, now, correlation);
            return new ChunkOutcome(ChunkOutcome.Kind.COMPLETED, run.id(), 0);
        }
        // The chunk's items are read lock-free FIRST, so the attributed sources' advisories
        // precede every row lock this transaction takes, the run row's included.
        List<MatchingStore.ChunkItem> items =
                store.chunkItems(unitOfWork, run.id(), run.cursor(), config.chunkSize());
        if (!claimAttributedSources(
                unitOfWork,
                run.sourceId(),
                items.stream()
                        .map(MatchingStore.ChunkItem::attributedSourceId)
                        .flatMap(Optional::stream)
                        .toList())) {
            // Nothing written yet: the run waits for a tick when its report sources are free.
            return new ChunkOutcome(ChunkOutcome.Kind.SKIPPED, run.id(), 0);
        }
        if (run.status() == RunStatus.OPEN) {
            store.markRunInProgress(unitOfWork, run.id(), SecurityContext.require(), now);
        }
        if (items.isEmpty()) {
            completeRun(unitOfWork, run, now, correlation);
            return new ChunkOutcome(ChunkOutcome.Kind.COMPLETED, run.id(), 0);
        }

        int tolerance = rules.settlementDateToleranceDays(unitOfWork, run.ruleSetId());
        // Resolve every item's fired rule and hit ids BEFORE any row lock, then lock
        // expectations and items sorted by id (the §3 order), then re-read the hit facts
        // on the locked rows - the snapshot is the locked truth. A value-date group's
        // candidates are locked in the same sorted pass (P8-TSK-016).
        Map<UUID, Resolution> resolutions = new LinkedHashMap<>();
        Map<UUID, KeyKind> reachedBy = new HashMap<>();
        java.util.Set<UUID> locked = new java.util.HashSet<>();
        for (MatchingStore.ChunkItem item : items) {
            Resolution resolution = resolve(unitOfWork, run, item);
            resolutions.put(item.id(), resolution);
            resolution.hitIds().forEach(id -> reachedBy.put(id, resolution.reachedBy()));
            locked.addAll(resolution.hitIds());
            locked.addAll(resolution.groupCandidateIds());
        }
        // What some item's KEY reaches is that item's claim: no value-date group in this
        // chunk takes it (the design's "no other claimant in the chunk").
        java.util.Set<UUID> keyClaims = java.util.Set.copyOf(reachedBy.keySet());
        Map<UUID, MatchEngine.HitFacts> lockedHits = new HashMap<>();
        for (MatchEngine.HitFacts hit : store.lockExpectations(unitOfWork, locked, reachedBy)) {
            lockedHits.put(hit.expectationId(), hit);
        }
        java.util.Set<UUID> stillPending =
                store.lockItems(
                        unitOfWork, items.stream().map(MatchingStore.ChunkItem::id).toList());

        Map<UUID, MatchingStore.ChunkItem> chunk = new LinkedHashMap<>();
        items.forEach(item -> chunk.put(item.id(), item));
        List<Suspense.ParkedItem> parks = new ArrayList<>();
        List<OffsetIntent> offsets = new ArrayList<>();
        List<QueuedOffset> sameChunkOffsets = new ArrayList<>();
        // The chunk's own claimant ledger: each allocation diminishes the remainder the
        // NEXT item in claimant order sees (INV-REC-07 inside one chunk), and ranks count.
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        int decided = 0;
        long lastLineNo = run.cursor();
        for (MatchingStore.ChunkItem item : items) {
            if (!stillPending.contains(item.id())) {
                // Decided by another writer between the lock-free chunk read and this
                // chunk's item locks - the try-lock bypassed, or any future writer that
                // skips it: the loser SKIPS what left PENDING, so it writes no second RUN
                // decision and no break beside the winner's with no value to own, and the
                // cursor still passes the line (the Phase 8 -> 9 transition's REC-10,
                // MI-4 and IDEM-3).
                lastLineNo = item.lineNo();
                continue;
            }
            ItemSavepoint savepoint =
                    savepoint(unitOfWork, item, parks, offsets, sameChunkOffsets,
                            claimantRanks);
            try {
                decideAndApply(
                        unitOfWork, run, item, resolutions.get(item.id()), lockedHits,
                        keyClaims, claimantRanks, tolerance, parks, offsets,
                        sameChunkOffsets, chunk, now, correlation);
            } catch (RuntimeException poisoned) {
                rollbackTo(unitOfWork, savepoint, parks, offsets, sameChunkOffsets,
                        claimantRanks);
                // The chunk's remainders the failed attempt had already diminished are re-read
                // from the rows the rollback restored (still locked by this transaction): the
                // next claimant is judged against what the database holds, never against an
                // allocation that rolled back (the Phase 8 -> 9 transition's ATOM-02).
                for (MatchEngine.HitFacts hit :
                        store.lockExpectations(unitOfWork, lockedHits.keySet(), reachedBy)) {
                    lockedHits.put(hit.expectationId(), hit);
                }
                containPoisoned(
                        unitOfWork, run, item, JudgedStatus.PENDING, parks, now, correlation,
                        poisoned);
            }
            decided++;
            lastLineNo = item.lineNo();
        }
        store.advanceCursor(unitOfWork, run.id(), lastLineNo, now);
        boolean lastChunk = items.size() < config.chunkSize();
        if (lastChunk) {
            completeRun(unitOfWork, run, now, correlation);
        }
        postPhase(unitOfWork, run.sourceId(), run.ruleSetId(), parks, offsets,
                sameChunkOffsets, now, correlation);
        return new ChunkOutcome(
                lastChunk ? ChunkOutcome.Kind.COMPLETED : ChunkOutcome.Kind.CHUNKED,
                run.id(),
                decided);
    }

    /**
     * One recorded release awaiting the posting phase: the locked original's facts and
     * the release's own cause — {@code CORRECTION_OFFSET} for a correction's offset,
     * {@code UNPARK} for a rematch's late allocation (`P8-TSK-013`).
     */
    private record OffsetIntent(
            UUID decisionId,
            CorrectionEngine.ParkedOriginal parked,
            Money amount,
            ReleaseCause cause) {}

    /**
     * A correction's offset of a park its OWN chunk queued (the Phase 8 -> 9 transition's
     * ATOM-04): the original's park has no suspense row until the posting phase posts it, so
     * the offset's snapshot row, the original's {@code PARKED -> RESOLVED} exit and the unpark
     * with its {@code EVIDENCED} closure complete there, right after the park - the same
     * record the two lines leave across two runs. Claimed in memory at the decision, so one
     * chunk's queued park offsets at most once; the claim rolls back with the item's
     * savepoint.
     */
    private record QueuedOffset(
            UUID decisionId,
            UUID originalItemId,
            UUID breakId,
            SuspenseSide side,
            Money amount,
            UUID positionAccountId) {}

    /**
     * THE POSTINGS, LAST (the §3 rule): a transaction posting more than one entry over
     * the platform's shared projection rows pre-locks their union in the projection's
     * own order before its first posting — {@code Suspense.park}'s internal pre-lock
     * covers only its OWN groups, so a park-beside-unpark transaction pre-locks here or
     * nowhere. The rows written after each posting are the transaction's own claims
     * (the D3 shape).
     */
    private void postPhase(
            Connection unitOfWork,
            UUID sourceId,
            UUID ruleSetId,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets,
            List<QueuedOffset> sameChunkOffsets,
            Instant now,
            CorrelationId correlation) {
        if (parks.size() + offsets.size() + sameChunkOffsets.size() > 1) {
            preLockPostingUnion(unitOfWork, parks, offsets);
        }
        // The parks each same-chunk offset releases, by their original item (ATOM-04).
        Map<UUID, Suspense.ParkedOutcome> posted = new HashMap<>();
        if (!parks.isEmpty()) {
            Suspense.ParkResult parkResult =
                    suspense.park(
                            unitOfWork,
                            new Suspense.ParkCommand(
                                    sourceId,
                                    LocalDate.ofInstant(now, ZoneOffset.UTC),
                                    parks,
                                    SecurityContext.require(),
                                    now,
                                    correlation));
            for (Suspense.ParkedOutcome outcome : parkResult.parked()) {
                posted.put(outcome.externalItemId(), outcome);
            }
        }
        for (OffsetIntent offset : offsets) {
            applyOffsetPostingAndEvidence(unitOfWork, ruleSetId, offset, now, correlation);
        }
        for (QueuedOffset offset : sameChunkOffsets) {
            applySameChunkOffset(unitOfWork, ruleSetId, offset, posted, now, correlation);
        }
    }

    /**
     * Completes a correction's offset of a park queued in its own chunk (ATOM-04), after
     * {@link Suspense#park} posted it: the decision's parked-original snapshot — the suspense
     * row exists now — the original's {@code PARKED -> RESOLVED} exit, and the unpark with
     * its {@code EVIDENCED} closure through the shared path.
     */
    private void applySameChunkOffset(
            Connection unitOfWork,
            UUID ruleSetId,
            QueuedOffset offset,
            Map<UUID, Suspense.ParkedOutcome> posted,
            Instant now,
            CorrelationId correlation) {
        Suspense.ParkedOutcome park = posted.get(offset.originalItemId());
        if (park == null) {
            // The claim is made only against a park queued earlier in this chunk, whose
            // item this transaction holds locked, so its posting cannot have converged.
            throw new IllegalStateException(
                    "a same-chunk offset's park posts in this transaction (ATOM-04):"
                            + " item " + offset.originalItemId());
        }
        CorrectionEngine.ParkedOriginal parked =
                new CorrectionEngine.ParkedOriginal(
                        offset.originalItemId(),
                        park.suspenseItemId(),
                        offset.breakId(),
                        offset.side(),
                        offset.amount().minorUnits(),
                        offset.amount().currency(),
                        offset.amount().scale(),
                        offset.positionAccountId());
        store.insertParkedOriginals(unitOfWork, offset.decisionId(), List.of(parked));
        exitOrThrow(
                store.markParkedItemResolved(
                        unitOfWork, offset.originalItemId(), SecurityContext.require(), now,
                        correlation),
                offset.originalItemId(), "PARKED -> RESOLVED");
        applyOffsetPostingAndEvidence(
                unitOfWork,
                ruleSetId,
                new OffsetIntent(
                        offset.decisionId(), parked, offset.amount(),
                        ReleaseCause.CORRECTION_OFFSET),
                now,
                correlation);
    }

    /**
     * The post phase of a TIME leg (grace, rematch) with a park failure contained per item (the
     * Phase 8 -> 9 transition, REC-3): one item whose park cannot post must never jam a leg. The
     * leg's worklist is oldest first, so a batch that rolled back whole on one item's park was
     * taken again on every tick, and every later item of the source waited for ever.
     *
     * <p>The whole phase runs first, as before. When it fails, it is rolled back to its own
     * savepoint - the items' decisions and breaks above it stand - and re-run piecewise: the
     * union pre-locked again (a rolled-back savepoint released its locks), the offsets together
     * (each is atomic with its own allocation, so an offset's failure still fails the batch
     * whole), then each park alone under its own savepoint. A park that fails again is undone and
     * its item left {@code UNMATCHED}, owned by the break its decision raised, its grace clock
     * stopped ({@link MatchingStore#stopGrace}): the value stays in its position, where the
     * position identity counts it, and no time leg takes the item again until evidence or a
     * person does. Never the run leg: its items are its cursor's, and a chunk that cannot post
     * rolls back whole and blocks the run, loudly.
     */
    private void postPhaseContained(
            Connection unitOfWork,
            UUID sourceId,
            UUID ruleSetId,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets,
            Instant now,
            CorrelationId correlation) {
        PhaseSavepoint whole = phaseSavepoint(unitOfWork, "post_phase");
        try {
            // A time leg's corrections never queue a same-chunk offset (the run leg's shape).
            postPhase(unitOfWork, sourceId, ruleSetId, parks, offsets, List.of(), now,
                    correlation);
            return;
        } catch (RuntimeException failed) {
            if (parks.isEmpty()) {
                throw failed; // Nothing per item to contain: the batch rolls back whole.
            }
            rollbackToPhase(unitOfWork, whole);
            log.warn(
                    "A time leg's postings failed and are re-posted one park at a time: {}",
                    failed.getClass().getSimpleName());
        }
        if (parks.size() + offsets.size() > 1) {
            preLockPostingUnion(unitOfWork, parks, offsets);
        }
        for (OffsetIntent offset : offsets) {
            applyOffsetPostingAndEvidence(unitOfWork, ruleSetId, offset, now, correlation);
        }
        for (Suspense.ParkedItem park : parks) {
            PhaseSavepoint one =
                    phaseSavepoint(unitOfWork,
                            "park_" + park.externalItemId().toString().replace("-", ""));
            try {
                suspense.park(
                        unitOfWork,
                        new Suspense.ParkCommand(
                                sourceId,
                                LocalDate.ofInstant(now, ZoneOffset.UTC),
                                List.of(park),
                                SecurityContext.require(),
                                now,
                                correlation));
            } catch (RuntimeException poisoned) {
                rollbackToPhase(unitOfWork, one);
                store.stopGrace(unitOfWork, park.externalItemId());
                log.warn(
                        "A park was contained: its item waits UNMATCHED, owned by its break,"
                                + " its grace clock stopped: {}",
                        poisoned.getClass().getSimpleName());
            }
        }
    }

    private void preLockPostingUnion(
            Connection unitOfWork,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets) {
        java.util.TreeSet<UUID> union = new java.util.TreeSet<>();
        for (Suspense.ParkedItem park : parks) {
            union.add(park.positionAccountId());
            union.add(suspenseAccount(unitOfWork, park.remainder().currency()));
        }
        for (OffsetIntent offset : offsets) {
            union.add(offset.parked().positionAccountId());
            union.add(suspenseAccount(unitOfWork, offset.amount().currency()));
        }
        suspense.lockBalancesInOrder(unitOfWork, List.copyOf(union));
    }

    private UUID suspenseAccount(Connection unitOfWork, CurrencyCode currency) {
        return accounts
                .findOperational(
                        unitOfWork, com.finapp.ledger.AccountPurpose.SUSPENSE_UNMATCHED,
                        currency)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart seeds suspense per currency"))
                .id()
                .value();
    }

    private void applyOffsetPostingAndEvidence(
            Connection unitOfWork,
            UUID ruleSetId,
            OffsetIntent offset,
            Instant now,
            CorrelationId correlation) {
        Suspense.Unparked unparked =
                suspense.unpark(
                        unitOfWork,
                        offset.parked().suspenseItemId(),
                        offset.amount(),
                        offset.cause(),
                        "decision=" + offset.decisionId(),
                        LocalDate.ofInstant(now, ZoneOffset.UTC),
                        SecurityContext.require(),
                        now,
                        correlation);
        boolean closed =
                resolutions.evidence(
                        unitOfWork,
                        new Resolutions.Evidence(
                                ids.next(),
                                offset.parked().breakId(),
                                offset.amount(),
                                store.breakResidualVersion(
                                        unitOfWork, offset.parked().breakId()),
                                offset.decisionId(),
                                Optional.of(unparked.parkId()),
                                Optional.of(offset.parked().suspenseItemId()),
                                Optional.of(unparked.entryId()),
                                ruleSetId,
                                SecurityContext.require(),
                                now,
                                correlation));
        if (!closed) {
            // The break row was locked at the decision and screened not-RESOLVED, so
            // this transaction's own release cannot find it closed.
            throw new IllegalStateException(
                    "an offset's owning break vanished under its lock (INV-REC-09)");
        }
    }

    private void completeRun(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            Instant now,
            CorrelationId correlation) {
        if (!store.completeRun(unitOfWork, run.id(), SecurityContext.require(), now)) {
            return; // Another edge won; the deferred trigger guards the claim anyway.
        }
        // A run blocked, requeued and now complete (`P8-TSK-022`): its RUN_BLOCKED break is
        // explained by this completion and closes EVIDENCED naming the run - the source's
        // advisory is held, so the break-first order holds.
        store.lockOpenBreakOnRun(unitOfWork, run.id(), BreakType.PROCESSING_ERROR)
                .ifPresent(
                        breakId ->
                                resolutions.evidence(
                                        unitOfWork,
                                        new Resolutions.Evidence(
                                                ids.next(),
                                                breakId,
                                                Money.ofPersisted(
                                                        0, CurrencyCode.of("EUR"), 2),
                                                store.breakResidualVersion(
                                                        unitOfWork, breakId),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.of(run.id()),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                run.ruleSetId(),
                                                SecurityContext.require(),
                                                now,
                                                correlation)));
        // The per-batch fee comparison (`P8-TSK-012`, ADR-0068 §7), judged once by the
        // completing edge's one winner: the signed fold of reported - expected per
        // currency over the run's CHECKED PROCESSING_FEE decisions - a bank fee is judged
        // per line only, P8-TSK-016 - (one currency's minor units -
        // Java arithmetic, never SQL SUM), its subject the run's FIRST fee item in
        // claimant order (the design's D: the taxonomy names the item, and the batch's
        // fee narrative starts at its first line), converging on the one-open unique.
        Map<String, long[]> foldByCurrency = new LinkedHashMap<>();
        Map<String, MatchingStore.FeeDecisionRow> firstByCurrency = new LinkedHashMap<>();
        // A BATCH run's fold alone: the per-batch bound is its batch's, and a REPROCESS run's
        // re-checked fee lines (REC-6) span batches - each was judged per line at its re-check.
        List<MatchingStore.FeeDecisionRow> batchFees =
                run.batchId() == null ? List.of() : store.feeDecisionsOf(unitOfWork, run.id());
        for (MatchingStore.FeeDecisionRow fee : batchFees) {
            foldByCurrency
                    .computeIfAbsent(fee.currency(), currency -> new long[] {0L, fee.scale()})
                    [0] += fee.feeReportedMinor() - fee.feeExpectedMinor();
            firstByCurrency.putIfAbsent(fee.currency(), fee);
        }
        for (Map.Entry<String, long[]> fold : foldByCurrency.entrySet()) {
            CurrencyCode currency = CurrencyCode.of(fold.getKey());
            long batchTolerance =
                    rules.feeToleranceMinor(
                            unitOfWork, run.ruleSetId(), "PROCESSING_FEE_PER_BATCH",
                            currency);
            long deviation = Math.abs(fold.getValue()[0]);
            if (deviation > batchTolerance) {
                MatchingStore.FeeDecisionRow first = firstByCurrency.get(fold.getKey());
                breaks.raise(
                        unitOfWork,
                        new BreakRegister.NewBreak(
                                ids.next(),
                                BreakType.FEE_MISMATCH,
                                BreakCause.FEE_BEYOND_TOLERANCE,
                                BreakRegister.Subject.externalItem(first.itemId()),
                                run.sourceId(),
                                run.ruleSetId(),
                                Money.ofPersisted(
                                        deviation, currency, (int) fold.getValue()[1]),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                SecurityContext.require(),
                                now,
                                correlation));
            }
        }
        Map<DecisionOutcome, Long> counts = store.outcomeCounts(unitOfWork, run.id());
        if (run.batchId() != null) {
            // A BATCH run only, and its FIRST decisions only (origin RUN): a rematch or grace
            // decision carries the original run's id, and between two chunks another
            // instance's leg may already have re-decided an item - counted on the rematch
            // series, never twice on the match rate (the gate's find). A REPROCESS run's
            // birth is a person's request - it never belongs on the match rate.
            telemetry.runCompleted(
                    run.sourceId(),
                    store.firstDecisionCounts(unitOfWork, run.id()),
                    store.runBornAt(unitOfWork, run.id())
                            .map(born -> Duration.between(born, now))
                            .filter(age -> !age.isNegative()));
        }
        StringBuilder summary = new StringBuilder("items=").append(run.itemCount());
        counts.forEach(
                (outcome, count) ->
                        summary.append(", ")
                                .append(outcome.name().toLowerCase(java.util.Locale.ROOT))
                                .append('=')
                                .append(count));
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        now,
                        ReconciliationAuditAction.RUN_COMPLETED,
                        "reconciliation_run",
                        run.id().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary.toString())));
        ReconciliationEvents.runCompleted(
                outbox, unitOfWork, ids, run.id(), run.batchId(), run.sourceId(), counts,
                now, correlation);
    }

    // ------------------------------------------------------------------ resolution

    /**
     * What one item's rules reached: the fired rule, or the facts of why none did — and, when
     * no key reached anything, the value-date group rule its line type rides with the group's
     * candidates as the lock-free read found them (`P8-TSK-016`).
     */
    private record Resolution(
            Optional<MatchingRules.RuleRow> firedRule,
            boolean anyLandedRule,
            List<UUID> hitIds,
            KeyKind reachedBy,
            Optional<Integer> waitingGraceHours,
            Optional<MatchingRules.RuleRow> unlandedRule,
            Optional<MatchingRules.RuleRow> groupRule,
            List<UUID> groupCandidateIds,
            boolean anchored) {}

    private Resolution resolve(
            Connection unitOfWork, MatchingStore.RunRow run, MatchingStore.ChunkItem item) {
        return resolve(unitOfWork, run.ruleSetId(), run.sourceId(), item);
    }

    /**
     * @param sourceId the item's own source (the run's); its expectation keys are judged in
     *     {@link MatchingStore.ChunkItem#keyScope} — the attributed source for an attributed
     *     bank line (ADR-0068 §1, `P8-TSK-016`)
     */
    private Resolution resolve(
            Connection unitOfWork,
            UUID ruleSetId,
            UUID sourceId,
            MatchingStore.ChunkItem item) {
        UUID scope = item.keyScope(sourceId);
        List<MatchingRules.RuleRow> lineRules =
                rules.rulesFor(unitOfWork, ruleSetId, item.lineType());
        boolean anyLanded =
                lineRules.stream()
                        .anyMatch(rule -> rule.cardinality() == Cardinality.ONE_TO_ONE);
        boolean anchored = lineRules.stream().anyMatch(MatchingRules.RuleRow::operationAnchored);
        // The CHECK or CORRECTION rule this line type rides when no landed rule serves
        // it (`P8-TSK-012`); PARTIAL has no v1 rule.
        Optional<MatchingRules.RuleRow> unlanded =
                lineRules.stream()
                        .filter(rule ->
                                rule.cardinality() == Cardinality.CHECK
                                        || rule.cardinality() == Cardinality.CORRECTION)
                        .findFirst();
        Optional<Integer> waitingGrace =
                lineRules.stream()
                        .filter(rule -> rule.cardinality() == Cardinality.ONE_TO_ONE)
                        .findFirst()
                        .map(MatchingRules.RuleRow::graceHours)
                        // A correction that reaches nothing waits under ITS rule's clock.
                        .or(() -> unlanded.map(MatchingRules.RuleRow::graceHours));
        for (MatchingRules.RuleRow rule : lineRules) {
            if (rule.cardinality() != Cardinality.ONE_TO_ONE || rule.keyKind().isEmpty()) {
                continue;
            }
            KeyKind keyKind = rule.keyKind().get();
            Optional<String> value = itemKeyValue(item, keyKind);
            if (value.isEmpty()) {
                continue;
            }
            List<UUID> hits = resolveKey(unitOfWork, scope, keyKind, value.get());
            if (rule.operationAnchored()) {
                // An operation-anchored rule (`P8-TSK-018`, the Phase 7 -> 8 transition's A4):
                // the line's key names its operation's ANCHOR - a returned payout's reference
                // is its payout's own key - and the rule reaches only that operation's
                // expectation of the rule's kind (`UNIQUE (kind, operation_ref)`), never the
                // anchor: an INBOUND return is no direction mismatch against its OUTBOUND
                // payout. Finding none, the line has reached nothing and waits.
                hits = store.anchoredExpectations(
                        unitOfWork, hits, rule.expectationKind().orElseThrow());
            }
            if (!hits.isEmpty()) {
                return new Resolution(
                        Optional.of(rule), anyLanded, hits, keyKind, waitingGrace,
                        unlanded, Optional.empty(), List.of(), anchored);
            }
        }
        // No key reached anything in the scope: the value-date group, if the line type rides
        // one, reads its candidates now - before any row lock - to lock them with the rest.
        // Never when a reference HIT something exhausted or contradicted (returned above).
        Optional<MatchingRules.RuleRow> group =
                lineRules.stream()
                        .filter(rule -> rule.cardinality() == Cardinality.GROUP_BY_VALUE_DATE)
                        .findFirst();
        List<UUID> groupCandidates =
                group.map(
                                rule ->
                                        store.groupCandidates(
                                                unitOfWork,
                                                scope,
                                                groupKind(rule),
                                                item.direction(),
                                                item.amount().currency(),
                                                item.groupDate()))
                        .orElse(List.of());
        return new Resolution(
                Optional.empty(), anyLanded, List.of(), KeyKind.OUR_REF, waitingGrace,
                unlanded, group, groupCandidates, anchored);
    }

    /**
     * The expectation-side kind's value on the item (the design's D8): the dispute stages
     * ride one item key, and the ARN resolves through the alias's local two-hop join.
     */
    private Optional<String> itemKeyValue(MatchingStore.ChunkItem item, KeyKind kind) {
        ItemKeyKind itemKind =
                switch (kind) {
                    case DISPUTE_CB_REF, DISPUTE_REV_REF, DISPUTE_FEE_REF ->
                            ItemKeyKind.DISPUTE_REF;
                    case PSP_CAPTURE_REF -> ItemKeyKind.PSP_CAPTURE_REF;
                    case PSP_REFUND_REF -> ItemKeyKind.PSP_REFUND_REF;
                    case ACQUIRER_REF -> ItemKeyKind.ACQUIRER_REF;
                    case OUR_REF -> ItemKeyKind.OUR_REF;
                    // The bank line's structured reference (`P8-TSK-016`).
                    case REMITTANCE_REF -> ItemKeyKind.REMITTANCE_REF;
                    // The scheme's reference and our end-to-end reference (`P8-TSK-017`).
                    case SCHEME_REF -> ItemKeyKind.SCHEME_REF;
                    case END_TO_END_REF -> ItemKeyKind.END_TO_END_REF;
                    // The payout provider's reference (`P8-TSK-018`).
                    case PAYOUT_PROVIDER_REF -> ItemKeyKind.PAYOUT_PROVIDER_REF;
                    // The FX provider's cover reference and trade reference (`P9-TSK-011`).
                    case COVER_REF -> ItemKeyKind.COVER_REF;
                    case FX_TRADE_REF -> ItemKeyKind.FX_TRADE_REF;
                    default -> null; // The other sources' kinds arrive with their tasks.
                };
        return itemKind == null
                ? Optional.empty()
                : Optional.ofNullable(item.keys().get(itemKind));
    }

    private List<UUID> resolveKey(
            Connection unitOfWork, UUID sourceId, KeyKind kind, String value) {
        if (kind == KeyKind.ACQUIRER_REF) {
            // The ARN's alias: (ACQUIRER_REF, arn) -> (CARD_ATTEMPT, attempt) -> the key.
            return store.aliasAnchor(unitOfWork, sourceId, kind, value)
                    .map(anchor ->
                            store.expectationsByKey(
                                    unitOfWork, sourceId, anchor.getKey(),
                                    anchor.getValue()))
                    .orElse(List.of());
        }
        return store.expectationsByKey(unitOfWork, sourceId, kind, value);
    }

    // ------------------------------------------------------------------ apply

    private void decideAndApply(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Resolution resolution,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            java.util.Set<UUID> keyClaims,
            Map<UUID, Integer> claimantRanks,
            int toleranceDays,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets,
            List<QueuedOffset> sameChunkOffsets,
            Map<UUID, MatchingStore.ChunkItem> chunk,
            Instant now,
            CorrelationId correlation) {
        boolean fingerprintSeen = fingerprintSeen(unitOfWork, run.sourceId(), item);
        MatchEngine.ItemFacts facts =
                new MatchEngine.ItemFacts(
                        item.id(),
                        item.lineType(),
                        item.direction(),
                        item.amount(),
                        item.businessDate(),
                        item.settlementDate(),
                        fingerprintSeen,
                        run.settlementCycle());
        // The unlanded cardinalities route before the engine (`P8-TSK-012`): a fee
        // line's value was expensed at acceptance and is JUDGED, never parked - each
        // expensed line checked, a repeated one included; a correction is the
        // counterparty's own evidence against its original, and a repeated correction
        // is the fingerprint duplicate it always was.
        if (resolution.firedRule().isEmpty() && resolution.unlandedRule().isPresent()) {
            MatchingRules.RuleRow unlanded = resolution.unlandedRule().get();
            if (unlanded.cardinality() == Cardinality.CHECK) {
                applyFeeCheck(
                        unitOfWork, run, item, unlanded, DecisionOrigin.RUN,
                        JudgedStatus.PENDING, now, correlation);
                return;
            }
            if (unlanded.cardinality() == Cardinality.CORRECTION) {
                applyCorrection(
                        unitOfWork, run, item, facts, resolution, unlanded, lockedHits,
                        claimantRanks, parks, offsets, sameChunkOffsets, chunk, now,
                        correlation);
                return;
            }
        }
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(facts, fired, resolution.anyLandedRule(), toleranceDays);
        // What this decision saw and concluded, stored whole (`P8-TSK-022`).
        Snapshot seen = Snapshot.of(fired);
        MatchingStore.Basis judged =
                MatchingStore.Basis.match(
                        DecisionVerdict.of(verdict.kind()), JudgedStatus.PENDING,
                        item.amount().minorUnits(), fingerprintSeen);

        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        switch (verdict.kind()) {
            case ALLOCATE ->
                    applyAllocation(
                            unitOfWork, run, item, fired.orElseThrow(), verdict,
                            decisionId, decidedOn, lockedHits, claimantRanks, parks,
                            toleranceDays, DecisionOrigin.RUN, "PENDING", now,
                            correlation);
            case AMBIGUOUS ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                            Optional.empty(), parks, now, correlation);
            case DIRECTION_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.DIRECTION_CONTRADICTED,
                            Optional.empty(), parks, now, correlation);
            case CURRENCY_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                            Optional.empty(), parks, now, correlation);
            case DUPLICATE ->
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.DUPLICATE_EXTERNAL,
                            fingerprintSeen
                                    ? BreakCause.REPEATED_FINGERPRINT
                                    : BreakCause.EXPECTATION_EXHAUSTED,
                            Optional.empty(), parks, now, correlation);
            case NO_RULE -> {
                if (item.lineType().allocating() && item.positionPurpose().isPresent()) {
                    // No rule of the pinned version can ever allocate this line - an OTHER_IN
                    // or OTHER_OUT no rule set can even name, or a line type its version does
                    // not serve: its grace is zero, so the value is OWNED NOW, parked with its
                    // UNKNOWN_EXTERNAL break, never left waiting with no clock and no break
                    // (INV-REC-02; the Phase 8 -> 9 transition's REC-2). A later version's
                    // REPROCESS run may still re-decide it.
                    applyDefinitive(
                            unitOfWork, run, item, seen, judged, decisionId, decidedOn,
                            BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED,
                            Optional.empty(), parks, now, correlation);
                } else {
                    // A fee line its version names no CHECK for: nothing of it stands in the
                    // position, so nothing parks; it waits for a REPROCESS run under a version
                    // that checks it.
                    applyWaiting(
                            unitOfWork, run, item, decisionId, decidedOn,
                            Optional.empty(), seen, judged, now, correlation);
                }
            }
            case NO_CANDIDATES -> {
                // No key reached anything: a line type riding a value-date group is judged
                // against its date's untouched candidates before the lookup types what is
                // left (`P8-TSK-016`).
                Snapshot unreachedSeen = seen;
                MatchingStore.Basis unreachedBasis = judged;
                if (resolution.groupRule().isPresent()) {
                    GroupMatch.Verdict grouped =
                            groupVerdict(
                                    unitOfWork, item, run.sourceId(), resolution, lockedHits,
                                    keyClaims);
                    if (grouped.kind() == GroupMatch.Kind.MATCH) {
                        applyGroupMatch(
                                unitOfWork, run, item, resolution.groupRule().get(), grouped,
                                decisionId, decidedOn, lockedHits, claimantRanks,
                                toleranceDays, DecisionOrigin.RUN, "PENDING", now,
                                correlation);
                        return;
                    }
                    // A group judged and not matched records the candidates it saw (the
                    // P8-TSK-016 rule) and its membership input (P8-TSK-022).
                    unreachedSeen =
                            grouped.candidates().isEmpty()
                                    ? Snapshot.none()
                                    : Snapshot.group(
                                            resolution.groupRule().get(), grouped.candidates());
                    unreachedBasis =
                            MatchingStore.Basis.group(
                                    DecisionVerdict.of(grouped.kind()), JudgedStatus.PENDING,
                                    item.amount().minorUnits(),
                                    grouped.kind() != GroupMatch.Kind.MEMBERSHIP_MOVED);
                }
                applyUnreached(
                        unitOfWork, run, item, resolution, unreachedSeen, unreachedBasis,
                        decisionId, decidedOn, parks, now, correlation);
            }
            default ->
                    throw new IllegalStateException("unhandled verdict " + verdict.kind());
        }
    }

    /**
     * What a written decision snapshots (ADR-0068 §5, completed by `P8-TSK-022`): the rule
     * that fired, every candidate it saw, and - for a correction - the parked originals it
     * judged. A value-date group judged and not matched records its candidates when any stood.
     */
    private record Snapshot(
            Optional<Integer> rulePriority,
            Optional<Cardinality> strategy,
            Optional<KeyKind> keyKind,
            List<MatchEngine.HitFacts> candidates,
            List<CorrectionEngine.ParkedOriginal> parkedOriginals) {

        static Snapshot none() {
            return new Snapshot(
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of());
        }

        /** The matching engine's input: the fired rule and every hit it reached. */
        static Snapshot of(Optional<MatchEngine.FiredRule> fired) {
            return fired.map(
                            rule ->
                                    new Snapshot(
                                            Optional.of(rule.priority()),
                                            Optional.of(rule.cardinality()),
                                            Optional.of(rule.keyKind()),
                                            rule.hits(),
                                            List.<CorrectionEngine.ParkedOriginal>of()))
                    .orElseGet(Snapshot::none);
        }

        static Snapshot group(
                MatchingRules.RuleRow rule, List<MatchEngine.HitFacts> candidates) {
            return new Snapshot(
                    Optional.of(rule.priority()),
                    Optional.of(Cardinality.GROUP_BY_VALUE_DATE),
                    Optional.empty(),
                    candidates,
                    List.of());
        }

        static Snapshot correction(
                MatchingRules.RuleRow rule,
                List<MatchEngine.HitFacts> hits,
                List<CorrectionEngine.ParkedOriginal> parked) {
            return new Snapshot(
                    Optional.of(rule.priority()),
                    Optional.of(Cardinality.CORRECTION),
                    Optional.empty(),
                    hits,
                    parked);
        }

        /** How many candidates the named rule saw; nothing without a rule. */
        Optional<Integer> claimantCount() {
            return rulePriority.map(priority -> candidates.size());
        }
    }

    /**
     * The value-date group's allocation (`P8-TSK-016`): ONE decision, one candidate row per
     * member with no key kind, one allocation per member for its WHOLE amount — each member
     * settles on its own machine edge with its own {@code SettlementExpectationSettled} — and
     * the item {@code MATCHED} for its whole amount. A member's open {@code MISSING_EXTERNAL}
     * is locked before the allocations and closed {@code EVIDENCED} after them (`P8-TSK-013`'s
     * L1, member by member). A group is reached BY its date, so it is never late against it:
     * the decision freezes a deviation of zero and raises no timing observation.
     */
    private void applyGroupMatch(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
            GroupMatch.Verdict verdict,
            UUID decisionId,
            LocalDate decidedOn,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            Map<UUID, Integer> claimantRanks,
            int toleranceDays,
            DecisionOrigin origin,
            String itemFromStatus,
            Instant now,
            CorrelationId correlation) {
        List<MatchEngine.HitFacts> members = verdict.candidates();
        Map<UUID, UUID> overdueBreaks = new LinkedHashMap<>();
        for (MatchEngine.HitFacts member : members) {
            store.lockOpenBreakOn(
                            unitOfWork, member.expectationId(), BreakType.MISSING_EXTERNAL)
                    .ifPresent(breakId -> overdueBreaks.put(member.expectationId(), breakId));
        }
        int claimantRank = 0;
        for (MatchEngine.HitFacts member : members) {
            claimantRank =
                    Math.max(
                            claimantRank,
                            claimantRanks.merge(member.expectationId(), 1, Integer::sum));
        }
        int deviation =
                (int) ChronoUnit.DAYS.between(members.get(0).expectedBy(), item.groupDate());
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        origin,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(Cardinality.GROUP_BY_VALUE_DATE),
                        Optional.empty(),
                        DecisionOutcome.MATCHED,
                        Optional.of(claimantRank),
                        Optional.of(members.size()),
                        Optional.of(deviation),
                        Optional.of(toleranceDays),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        MatchingStore.Basis.group(
                                DecisionVerdict.GROUP_MATCH,
                                JudgedStatus.ofItemStatus(itemFromStatus),
                                item.amount().minorUnits(), true)));
        store.insertCandidates(unitOfWork, decisionId, members);
        Map<UUID, Money> allocated = new LinkedHashMap<>();
        for (MatchEngine.HitFacts member : members) {
            // Untouched by construction: the remainder IS the member's whole amount.
            Money allocation =
                    Money.ofPersisted(
                            member.remainderMinor(),
                            member.amount().currency(),
                            member.amount().scale());
            store.insertAllocation(
                    unitOfWork,
                    new MatchingStore.NewAllocation(
                            ids.next(), decisionId, item.id(), member.expectationId(),
                            allocation, now, correlation));
            ExpectationStatus status =
                    store.allocateToExpectation(
                            unitOfWork,
                            member.expectationId(),
                            allocation,
                            "decision=" + decisionId,
                            SecurityContext.require(),
                            now,
                            correlation);
            store.bumpResidualOnSubjects(unitOfWork, member.expectationId(), item.id());
            // The next claimant in this chunk sees the member emptied (INV-REC-07).
            lockedHits.put(
                    member.expectationId(),
                    new MatchEngine.HitFacts(
                            member.expectationId(),
                            member.kind(),
                            member.direction(),
                            member.amount(),
                            0L,
                            member.openedAt(),
                            member.expectedBy(),
                            member.reachedBy(),
                            member.operationRef(),
                            member.settlementCycle()));
            if (status == ExpectationStatus.SETTLED) {
                ReconciliationEvents.expectationSettled(
                        outbox, unitOfWork, ids, member.expectationId(), member.kind(),
                        run.sourceId(), now, correlation);
            }
            allocated.put(member.expectationId(), allocation);
        }
        exitOrThrow(
                store.markItemMatchedFrom(
                        unitOfWork, item.id(), itemFromStatus, item.amount().minorUnits(),
                        SecurityContext.require(), now, correlation),
                item.id(), itemFromStatus + " -> MATCHED");
        overdueBreaks.forEach(
                (expectationId, breakId) ->
                        resolutions.evidence(
                                unitOfWork,
                                new Resolutions.Evidence(
                                        ids.next(),
                                        breakId,
                                        allocated.get(expectationId),
                                        store.breakResidualVersion(unitOfWork, breakId),
                                        decisionId,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        run.ruleSetId(),
                                        SecurityContext.require(),
                                        now,
                                        correlation)));
    }

    /**
     * The difference's break against its candidate (`P8-TSK-016`): the bank's cash against a
     * report's {@code REMITTANCE} is a {@code SETTLEMENT_MISMATCH} ({@code REMITTANCE_DIFFERS}),
     * every other kind's an {@code AMOUNT_MISMATCH} ({@code AMOUNT_DIFFERS}) — the pairing
     * `V004`'s detector trigger admits.
     */
    private static BreakType differenceType(ExpectationKind kind) {
        return kind == ExpectationKind.REMITTANCE
                ? BreakType.SETTLEMENT_MISMATCH
                : BreakType.AMOUNT_MISMATCH;
    }

    private static BreakCause differenceCause(ExpectationKind kind) {
        return kind == ExpectationKind.REMITTANCE
                ? BreakCause.REMITTANCE_DIFFERS
                : BreakCause.AMOUNT_DIFFERS;
    }

    private void applyAllocation(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchEngine.FiredRule rule,
            MatchEngine.Verdict verdict,
            UUID decisionId,
            LocalDate decidedOn,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            int toleranceDays,
            DecisionOrigin origin,
            String itemFromStatus,
            Instant now,
            CorrelationId correlation) {
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        Money allocation = verdict.allocation().orElseThrow();
        // Late settlement (`P8-TSK-013`, L1): an open MISSING_EXTERNAL on the candidate
        // IS the timing record. When this allocation settles it to zero, the break is
        // locked FIRST (the §3 order) and resolved EVIDENCED below; either way the
        // TIMING_DIFFERENCE raise is suppressed - two breaks would state one fact twice.
        boolean settles = allocation.minorUnits() == candidate.remainderMinor();
        Optional<UUID> overdueBreak =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                BreakType.MISSING_EXTERNAL)
                        : Optional.empty();
        boolean overdueStands =
                overdueBreak.isPresent()
                        || (!settles
                                && store.openBreakExistsOn(
                                        unitOfWork, candidate.expectationId(),
                                        BreakType.MISSING_EXTERNAL));
        // A later tranche (`P8-TSK-016`): an earlier partial allocation left its shortfall
        // break on the candidate - AMOUNT_MISMATCH, or a remittance's SETTLEMENT_MISMATCH. When
        // THIS allocation settles the remainder, that shortfall is explained to zero: locked
        // FIRST (the section-3 break-first order; its source's advisory is the expectation's,
        // which this transaction holds) and resolved EVIDENCED below, never left open over a
        // SETTLED expectation with nothing a person could dispose of.
        Optional<UUID> shortfallBreak =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                differenceType(candidate.kind()))
                        : Optional.empty();
        int claimantRank =
                claimantRanks.merge(candidate.expectationId(), 1, Integer::sum);
        int deviation =
                (int)
                        ChronoUnit.DAYS.between(
                                candidate.expectedBy(), facts(item).effectiveSettlementDate());
        DecisionOutcome outcome =
                verdict.excess().isPresent() ? DecisionOutcome.PARKED : DecisionOutcome.MATCHED;
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        origin,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(rule.cardinality()),
                        candidate.reachedBy(),
                        outcome,
                        Optional.of(claimantRank),
                        Optional.of(1),
                        Optional.of(deviation),
                        Optional.of(toleranceDays),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        // ALLOCATE is reached only past the fingerprint (MatchEngine).
                        MatchingStore.Basis.match(
                                DecisionVerdict.ALLOCATE,
                                JudgedStatus.ofItemStatus(itemFromStatus),
                                item.amount().minorUnits(), false)));
        store.insertCandidates(unitOfWork, decisionId, rule.hits());
        store.insertAllocation(
                unitOfWork,
                new MatchingStore.NewAllocation(
                        ids.next(), decisionId, item.id(), candidate.expectationId(),
                        allocation, now, correlation));
        ExpectationStatus status =
                store.allocateToExpectation(
                        unitOfWork,
                        candidate.expectationId(),
                        allocation,
                        "decision=" + decisionId,
                        SecurityContext.require(),
                        now,
                        correlation);
        // An allocation moves the residual a standing break was judged on, so its
        // staleness counter moves with it (ADR-0071; a fresh break this chunk raises is
        // born after the bump and carries version 0 honestly).
        store.bumpResidualOnSubjects(unitOfWork, candidate.expectationId(), item.id());
        // The NEXT item in claimant order sees the diminished remainder, so one chunk
        // can never allocate one expectation twice over (INV-REC-07; the deferred sum
        // trigger stands beneath for every writer).
        lockedHits.put(
                candidate.expectationId(),
                new MatchEngine.HitFacts(
                        candidate.expectationId(),
                        candidate.kind(),
                        candidate.direction(),
                        candidate.amount(),
                        candidate.remainderMinor() - allocation.minorUnits(),
                        candidate.openedAt(),
                        candidate.expectedBy(),
                        candidate.reachedBy(),
                        candidate.operationRef(),
                        candidate.settlementCycle()));
        if (status == ExpectationStatus.SETTLED) {
            ReconciliationEvents.expectationSettled(
                    outbox, unitOfWork, ids, candidate.expectationId(), candidate.kind(),
                    run.sourceId(), now, correlation);
        }
        // Against a REMITTANCE the difference is the bank's cash against the report's promise
        // - SETTLEMENT_MISMATCH (REMITTANCE_DIFFERS) (P8-TSK-016); the arithmetic and the
        // parking are the same. The item's excess is stamped with this run's source; the
        // expectation's shortfall with the EXPECTATION's source and rule set - the report
        // source for a bank line - so every open break on one remainder shares one source's
        // advisory (ageing's MISSING_EXTERNAL is stamped the same way), and a resolution that
        // disposes of the remainder and its siblings holds the one key they all answer to.
        if (verdict.excess().isPresent()) {
            Money excess = verdict.excess().get();
            store.recordItemAllocation(unitOfWork, item.id(), allocation.minorUnits());
            BreakRegister.Raised raised =
                    breaks.raise(
                            unitOfWork,
                            newBreak(
                                    run, item, differenceType(candidate.kind()),
                                    differenceCause(candidate.kind()),
                                    BreakRegister.Subject.externalItem(item.id()), excess,
                                    Optional.empty(), now));
            parks.add(
                    new Suspense.ParkedItem(
                            item.id(), raised.breakId(), excess,
                            positionAccount(unitOfWork, run, item)));
        } else {
            exitOrThrow(
                    store.markItemMatchedFrom(
                            unitOfWork, item.id(), itemFromStatus, allocation.minorUnits(),
                            SecurityContext.require(), now, correlation),
                    item.id(), itemFromStatus + " -> MATCHED");
        }
        if (verdict.underRemainder().isPresent()) {
            UUID expectationSource = item.keyScope(run.sourceId());
            BreakRegister.NewBreak shortfall =
                    newBreak(
                            run, item, differenceType(candidate.kind()),
                            differenceCause(candidate.kind()),
                            BreakRegister.Subject.expectation(candidate.expectationId()),
                            verdict.underRemainder().get(),
                            Optional.of(candidate.kind()), now);
            if (!expectationSource.equals(run.sourceId())) {
                shortfall =
                        new BreakRegister.NewBreak(
                                shortfall.breakId(), shortfall.type(), shortfall.cause(),
                                shortfall.subject(), expectationSource,
                                store.expectationRuleSet(unitOfWork, candidate.expectationId()),
                                shortfall.valueAtIssue(), shortfall.direction(),
                                shortfall.expectationKind(), shortfall.internalClassification(),
                                shortfall.internalOperationRef(), shortfall.internalState(),
                                shortfall.followsBreakId(), shortfall.actor(),
                                shortfall.raisedAt(), shortfall.correlation());
            }
            breaks.raise(unitOfWork, shortfall);
        }
        if (verdict.timing().isPresent() && !overdueStands) {
            // A cycle shift names the more specific cause (P8-TSK-017): the report settled the
            // line in a cycle other than the one the completion announced - the money matched,
            // only the timing differs. One break per decision either way.
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.TIMING_DIFFERENCE,
                            verdict.timing().get().cycleShift()
                                    ? BreakCause.CYCLE_MISMATCH
                                    : BreakCause.LATE_MATCH,
                            BreakRegister.Subject.decision(decisionId),
                            Money.ofPersisted(
                                    0, item.amount().currency(), item.amount().scale()),
                            Optional.empty(), now));
        }
        // The learned cycle (P8-TSK-017, ADR-0062's follow-up): an expectation that announced no
        // cycle - a return - learns it from the report that settled it; the item records its
        // run's cycle, once, in this chunk (V009's every-writer rule beneath).
        if (run.settlementCycle().isPresent() && candidate.settlementCycle().isEmpty()) {
            store.recordLearnedCycle(unitOfWork, item.id(), run.settlementCycle().get());
        }
        // The money arrived, late but whole: the overdue break is explained to zero
        // and closes EVIDENCED, the timing already on the decision (INV-SET-03).
        overdueBreak.ifPresent(
                breakId ->
                        resolutions.evidence(
                                unitOfWork,
                                new Resolutions.Evidence(
                                        ids.next(),
                                        breakId,
                                        allocation,
                                        store.breakResidualVersion(unitOfWork, breakId),
                                        decisionId,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        run.ruleSetId(),
                                        SecurityContext.require(),
                                        now,
                                        correlation)));
        // The remainder's last tranche: the earlier shortfall is explained to zero.
        shortfallBreak.ifPresent(
                breakId ->
                        resolutions.evidence(
                                unitOfWork,
                                new Resolutions.Evidence(
                                        ids.next(),
                                        breakId,
                                        allocation,
                                        store.breakResidualVersion(unitOfWork, breakId),
                                        decisionId,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        run.ruleSetId(),
                                        SecurityContext.require(),
                                        now,
                                        correlation)));
    }

    /**
     * The {@code CHECK} cardinality (`P8-TSK-012`, ADR-0068 §7): the reported fee against
     * round(rate × gross + fixed) under the pinned schedule and the per-line bound —
     * strict, so exactly at the tolerance raises nothing. The item is {@code CHECKED}
     * whatever the verdict; a breach is `FEE_MISMATCH`, commercial, NEVER parked — the
     * value was expensed at acceptance. An unreachable original or an absent schedule
     * prices the expected fee at zero (the design's F1/F2, conservative).
     *
     * <p>A bank fee is FLAT (`P8-TSK-016`): charged per statement line on no transaction's
     * gross, so its pinned schedule is judged against a gross of exactly zero — expected =
     * round(rate × 0 + fixed), the fixed part — never F1's unreachable-original zero, which
     * would price the fixed part away and stand the whole fee at issue.
     *
     * <p>The run leg checks a {@code PENDING} line under origin {@code RUN}; a {@code REPROCESS}
     * run re-checks a fee line left {@code UNMATCHED} - its check contained, or no CHECK rule in
     * its own version - under its own origin and pinned version (the Phase 8 -> 9 transition's
     * REC-6). Returns the {@code CHECKED} decision.
     *
     * <p>A gross in another currency prices nothing (the transition's currency pre-filter,
     * below): the fee is checked under F1's conservative rule instead of being handed to
     * {@link FeeCheck}'s cross-currency arithmetic, whose {@code INV-MON-04} guard throws.
     */
    private UUID applyFeeCheck(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
            DecisionOrigin origin,
            JudgedStatus judged,
            Instant now,
            CorrelationId correlation) {
        Optional<Money> gross = Optional.empty();
        String originalRef = item.keys().get(ItemKeyKind.ORIGINAL_REF);
        if (item.lineType() == ExternalLineType.BANK_FEE) {
            gross =
                    Optional.of(
                            Money.ofPersisted(
                                    0, item.amount().currency(), item.amount().scale()));
        } else if (originalRef != null && item.lineType().originalKeyKind().isPresent()) {
            // The original by the fee line type's own key (P8-TSK-017): the PSP's fee names a
            // capture, the scheme's an execution - never one key for every counterparty's fee.
            gross =
                    store.expectationsByKey(
                                    unitOfWork, item.keyScope(run.sourceId()),
                                    item.lineType().originalKeyKind().get(), originalRef)
                            .stream()
                            .findFirst()
                            .flatMap(id -> store.expectationAmount(unitOfWork, id));
        }
        if (gross.isPresent()
                && !gross.get().currency().equals(item.amount().currency())) {
            // The currency pre-filter (the Phase 8 -> 9 transition): a gross in another
            // currency cannot price this fee - FeeCheck's own INV-MON-04 guard would throw,
            // and the savepoint would contain the line as an untyped ITEM_ERRORED. The
            // mismatched original prices NOTHING (F1's conservative rule): expected zero,
            // the whole reported fee at issue, raised below as FEE_MISMATCH - deliberately
            // never CURRENCY_MISMATCH, whose every kind disposes of parked value a fee line
            // does not hold (its value was expensed at acceptance), so that type here could
            // never close.
            gross = Optional.empty();
        }
        long tolerance =
                rules.feeToleranceMinor(
                        unitOfWork, run.ruleSetId(), "PROCESSING_FEE_PER_LINE",
                        item.amount().currency());
        FeeCheck.Verdict verdict =
                FeeCheck.check(
                        item.amount(),
                        gross,
                        rules.feeScheduleFor(
                                unitOfWork, run.ruleSetId(), item.lineType(),
                                item.amount().currency()),
                        tolerance);
        UUID decisionId = ids.next();
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        origin,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(Cardinality.CHECK),
                        Optional.empty(),
                        DecisionOutcome.CHECKED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(verdict.expectedMinor()),
                        Optional.of(item.amount().minorUnits()),
                        Optional.of(tolerance),
                        SecurityContext.require(),
                        now,
                        LocalDate.ofInstant(now, ZoneOffset.UTC),
                        correlation,
                        // The gross the expected fee was priced on - absent when no
                        // original was reached (`P8-TSK-022`'s replay input).
                        MatchingStore.Basis.fee(
                                verdict.beyondTolerance(), judged, item.amount().minorUnits(),
                                gross.map(Money::minorUnits))));
        exitOrThrow(
                store.markItemCheckedFrom(
                        unitOfWork, item.id(), judged.name(), SecurityContext.require(), now,
                        correlation),
                item.id(), judged.name() + " -> CHECKED");
        if (verdict.beyondTolerance()) {
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.FEE_MISMATCH,
                            BreakCause.FEE_BEYOND_TOLERANCE,
                            BreakRegister.Subject.externalItem(item.id()),
                            Money.ofPersisted(
                                    verdict.deviationMinor(),
                                    item.amount().currency(),
                                    item.amount().scale()),
                            Optional.empty(), now));
        }
        return decisionId;
    }

    /**
     * The {@code CORRECTION} cardinality (`P8-TSK-012`, the plan's §12.5): the
     * counterparty's own evidence against its original — a same-direction top-up of the
     * open remainder, or an exact opposite offset of the parked excess, resolving the
     * break it explains {@code EVIDENCED}; matching neither, it waits like any
     * grace-class remainder.
     */
    private void applyCorrection(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchEngine.ItemFacts facts,
            Resolution resolution,
            MatchingRules.RuleRow rule,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets,
            List<QueuedOffset> sameChunkOffsets,
            Map<UUID, MatchingStore.ChunkItem> chunk,
            Instant now,
            CorrelationId correlation) {
        if (facts.fingerprintSeenEarlier()) {
            applyDefinitive(
                    unitOfWork, run, item, Snapshot.none(),
                    MatchingStore.Basis.match(
                            DecisionVerdict.DUPLICATE, JudgedStatus.PENDING,
                            item.amount().minorUnits(), true),
                    ids.next(),
                    LocalDate.ofInstant(now, ZoneOffset.UTC),
                    BreakType.DUPLICATE_EXTERNAL, BreakCause.REPEATED_FINGERPRINT,
                    Optional.empty(), parks, now, correlation);
            return;
        }
        String originalRef = item.keys().get(ItemKeyKind.ORIGINAL_REF);
        List<MatchEngine.HitFacts> hits = List.of();
        List<CorrectionEngine.ParkedOriginal> parkedOriginals = List.of();
        java.util.Set<UUID> originalItems = java.util.Set.of();
        if (originalRef != null) {
            Map<UUID, KeyKind> reachedBy = new LinkedHashMap<>();
            for (KeyKind kind : List.of(KeyKind.PSP_CAPTURE_REF, KeyKind.PSP_REFUND_REF)) {
                for (UUID id :
                        store.expectationsByKey(
                                unitOfWork, item.keyScope(run.sourceId()), kind,
                                originalRef)) {
                    reachedBy.putIfAbsent(id, kind);
                }
            }
            hits = store.lockExpectations(unitOfWork, reachedBy.keySet(), reachedBy);
            java.util.LinkedHashSet<UUID> originals = new java.util.LinkedHashSet<>();
            for (ItemKeyKind kind :
                    List.of(ItemKeyKind.PSP_CAPTURE_REF, ItemKeyKind.PSP_REFUND_REF)) {
                originals.addAll(
                        store.itemsByKey(unitOfWork, run.sourceId(), kind, originalRef));
            }
            originals.remove(item.id()); // Never its own original.
            originalItems = originals;
            parkedOriginals = store.lockParkedOriginals(unitOfWork, originalItems);
        }
        CorrectionEngine.Verdict verdict =
                CorrectionEngine.decide(facts, hits, parkedOriginals);
        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        switch (verdict.kind()) {
            case TOP_UP ->
                    applyTopUp(
                            unitOfWork, run, item, rule, verdict, hits, parkedOriginals,
                            decisionId, decidedOn, lockedHits, claimantRanks, parks, now,
                            correlation);
            case OFFSET -> {
                CorrectionEngine.ParkedOriginal parked = verdict.offset().orElseThrow();
                insertDecision(
                        run,
                        unitOfWork,
                        new MatchingStore.NewDecision(
                                decisionId,
                                item.id(),
                                run.id(),
                                DecisionOrigin.RUN,
                                run.ruleSetId(),
                                Optional.of(rule.priority()),
                                Optional.of(Cardinality.CORRECTION),
                                Optional.empty(),
                                DecisionOutcome.OFFSET,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                SecurityContext.require(),
                                now,
                                decidedOn,
                                correlation,
                                MatchingStore.Basis.of(
                                        DecisionVerdict.OFFSET, JudgedStatus.PENDING,
                                        item.amount().minorUnits())));
                // What the correction engine judged: the hits and the parked originals.
                store.insertCandidates(unitOfWork, decisionId, hits);
                store.insertParkedOriginals(unitOfWork, decisionId, parkedOriginals);
                exitOrThrow(
                        store.markItemOffset(
                                unitOfWork, item.id(), item.amount().minorUnits(),
                                SecurityContext.require(), now, correlation),
                        item.id(), "PENDING -> OFFSET");
                exitOrThrow(
                        store.markParkedItemResolved(
                                unitOfWork, parked.originalItemId(),
                                SecurityContext.require(), now, correlation),
                        parked.originalItemId(), "PARKED -> RESOLVED");
                offsets.add(
                        new OffsetIntent(
                                decisionId, parked, item.amount(),
                                ReleaseCause.CORRECTION_OFFSET));
            }
            case UNREACHED -> {
                // A park the chunk itself queued is as offsettable as a committed one (the
                // Phase 8 -> 9 transition's ATOM-04): the same two lines offset across two
                // runs, so in one chunk they must not leave two opposite suspense items
                // for a person's OFFSET_SUSPENSE.
                Optional<Suspense.ParkedItem> queued =
                        queuedParkToOffset(parks, sameChunkOffsets, originalItems, item, chunk);
                if (queued.isPresent()) {
                    applyQueuedOffset(
                            unitOfWork, run, item, rule, hits, queued.get(),
                            chunk.get(queued.get().externalItemId()), sameChunkOffsets,
                            decisionId, decidedOn, now, correlation);
                    return;
                }
                applyUnreached(
                        unitOfWork, run, item, resolution,
                        Snapshot.correction(rule, hits, parkedOriginals),
                        MatchingStore.Basis.of(
                                DecisionVerdict.CORRECTION_UNREACHED,
                                JudgedStatus.PENDING, item.amount().minorUnits()),
                        decisionId, decidedOn, parks, now, correlation);
            }
        }
    }

    /**
     * The one queued park of this chunk an exact opposite correction may offset (ATOM-04):
     * the correction engine's own OFFSET predicate — opposite side, same currency and scale,
     * EXACTLY the parked remainder, never partial — over the parks queued for the posting
     * phase, in queue order, skipping parks an earlier correction of this chunk already
     * claimed. Only reached when no committed parked original offset (the engine ran first).
     */
    private Optional<Suspense.ParkedItem> queuedParkToOffset(
            List<Suspense.ParkedItem> parks,
            List<QueuedOffset> sameChunkOffsets,
            java.util.Set<UUID> originalItems,
            MatchingStore.ChunkItem item,
            Map<UUID, MatchingStore.ChunkItem> chunk) {
        SuspenseSide offsettable =
                item.direction() == ExpectationDirection.OUTBOUND
                        ? SuspenseSide.CREDIT
                        : SuspenseSide.DEBIT;
        java.util.Set<UUID> claimed = new java.util.HashSet<>();
        for (QueuedOffset earlier : sameChunkOffsets) {
            claimed.add(earlier.originalItemId());
        }
        for (Suspense.ParkedItem park : parks) {
            if (!originalItems.contains(park.externalItemId())
                    || claimed.contains(park.externalItemId())) {
                continue;
            }
            // A queued park is always this chunk's own: its item was decided lines ago in
            // this very transaction.
            MatchingStore.ChunkItem original = chunk.get(park.externalItemId());
            if (original == null
                    || SuspenseSide.of(original.direction()) != offsettable
                    || !park.remainder().currency().equals(item.amount().currency())
                    || park.remainder().scale() != item.amount().scale()
                    || park.remainder().minorUnits() != item.amount().minorUnits()) {
                continue;
            }
            return Optional.of(park);
        }
        return Optional.empty();
    }

    /**
     * The correction's offset of a park its own chunk queued (ATOM-04): the {@code OFFSET}
     * decision and the correcting item's exit now; the snapshot row, the original's
     * {@code PARKED -> RESOLVED} and the unpark in the posting phase, right after the park
     * posts ({@link #applySameChunkOffset}).
     */
    private void applyQueuedOffset(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
            List<MatchEngine.HitFacts> hits,
            Suspense.ParkedItem queuedPark,
            MatchingStore.ChunkItem original,
            List<QueuedOffset> sameChunkOffsets,
            UUID decisionId,
            LocalDate decidedOn,
            Instant now,
            CorrelationId correlation) {
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(Cardinality.CORRECTION),
                        Optional.empty(),
                        DecisionOutcome.OFFSET,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        MatchingStore.Basis.of(
                                DecisionVerdict.OFFSET, JudgedStatus.PENDING,
                                item.amount().minorUnits())));
        store.insertCandidates(unitOfWork, decisionId, hits);
        exitOrThrow(
                store.markItemOffset(
                        unitOfWork, item.id(), item.amount().minorUnits(),
                        SecurityContext.require(), now, correlation),
                item.id(), "PENDING -> OFFSET");
        sameChunkOffsets.add(
                new QueuedOffset(
                        decisionId,
                        queuedPark.externalItemId(),
                        queuedPark.breakId(),
                        SuspenseSide.of(original.direction()),
                        item.amount(),
                        queuedPark.positionAccountId()));
    }

    /** The correction's allocating half: `ONE_TO_ONE`'s arithmetic, `EVIDENCED` at zero. */
    private void applyTopUp(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
            CorrectionEngine.Verdict verdict,
            List<MatchEngine.HitFacts> hits,
            List<CorrectionEngine.ParkedOriginal> parkedOriginals,
            UUID decisionId,
            LocalDate decidedOn,
            Map<UUID, MatchEngine.HitFacts> lockedHits,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        Money allocation = verdict.allocation().orElseThrow();
        boolean settles = allocation.minorUnits() == candidate.remainderMinor();
        // The breaks BEFORE the settling allocation (the §3 order), in applyAllocation's order:
        // the overdue MISSING_EXTERNAL first, then the shortfall - each explained to zero when
        // the top-up settles the expectation, so neither is left open over a SETTLED expectation
        // with nothing a person could dispose of (the Phase 8 -> 9 transition's REC-5: only the
        // shortfall was closed). A REMITTANCE's shortfall is its SETTLEMENT_MISMATCH
        // (P8-TSK-016), closed exactly as AMOUNT_MISMATCH is.
        Optional<UUID> overdue =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                BreakType.MISSING_EXTERNAL)
                        : Optional.empty();
        Optional<UUID> explained =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                differenceType(candidate.kind()))
                        : Optional.empty();
        int claimantRank =
                claimantRanks.merge(candidate.expectationId(), 1, Integer::sum);
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(Cardinality.CORRECTION),
                        candidate.reachedBy(),
                        verdict.excess().isPresent()
                                ? DecisionOutcome.PARKED
                                : DecisionOutcome.MATCHED,
                        Optional.of(claimantRank),
                        Optional.of(1),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        MatchingStore.Basis.of(
                                DecisionVerdict.TOP_UP, JudgedStatus.PENDING,
                                item.amount().minorUnits())));
        // Every hit and parked original the correction engine judged (`P8-TSK-022`).
        store.insertCandidates(unitOfWork, decisionId, hits);
        store.insertParkedOriginals(unitOfWork, decisionId, parkedOriginals);
        store.insertAllocation(
                unitOfWork,
                new MatchingStore.NewAllocation(
                        ids.next(), decisionId, item.id(), candidate.expectationId(),
                        allocation, now, correlation));
        ExpectationStatus status =
                store.allocateToExpectation(
                        unitOfWork,
                        candidate.expectationId(),
                        allocation,
                        "decision=" + decisionId,
                        SecurityContext.require(),
                        now,
                        correlation);
        store.bumpResidualOnSubjects(unitOfWork, candidate.expectationId(), item.id());
        // The NEXT item in this chunk sees the topped-up remainder (INV-REC-07 inside one chunk,
        // the Phase 8 -> 9 transition's ATOM-01): the correction re-read its candidate under the
        // held lock, so its remainder less this allocation is the database's - never the chunk's
        // pre-correction value, which judged a later line against value already taken.
        lockedHits.computeIfPresent(
                candidate.expectationId(),
                (id, chunkHit) ->
                        withRemainder(chunkHit, candidate.remainderMinor() - allocation.minorUnits()));
        if (status == ExpectationStatus.SETTLED) {
            ReconciliationEvents.expectationSettled(
                    outbox, unitOfWork, ids, candidate.expectationId(), candidate.kind(),
                    run.sourceId(), now, correlation);
        }
        if (verdict.excess().isPresent()) {
            Money excess = verdict.excess().get();
            store.recordItemAllocation(unitOfWork, item.id(), allocation.minorUnits());
            BreakRegister.Raised raised =
                    breaks.raise(
                            unitOfWork,
                            newBreak(
                                    run, item, differenceType(candidate.kind()),
                                    differenceCause(candidate.kind()),
                                    BreakRegister.Subject.externalItem(item.id()), excess,
                                    Optional.empty(), now));
            parks.add(
                    new Suspense.ParkedItem(
                            item.id(), raised.breakId(), excess,
                            positionAccount(unitOfWork, run, item)));
        } else {
            exitOrThrow(
                    store.markItemMatchedFrom(
                            unitOfWork, item.id(), "PENDING", allocation.minorUnits(),
                            SecurityContext.require(), now, correlation),
                    item.id(), "PENDING -> MATCHED");
        }
        // A zero remainder EXPLAINS the overdue break and the under-payment's own break:
        // EVIDENCED, in this transaction, no posting of its own - the allocation is the effect.
        for (Optional<UUID> closes : List.of(overdue, explained)) {
            closes.ifPresent(
                    breakId ->
                            resolutions.evidence(
                                    unitOfWork,
                                    new Resolutions.Evidence(
                                            ids.next(),
                                            breakId,
                                            allocation,
                                            store.breakResidualVersion(unitOfWork, breakId),
                                            decisionId,
                                            Optional.empty(),
                                            Optional.empty(),
                                            Optional.empty(),
                                            run.ruleSetId(),
                                            SecurityContext.require(),
                                            now,
                                            correlation)));
        }
    }

    /** The hit as the chunk now sees it: its facts, its remainder moved. */
    private static MatchEngine.HitFacts withRemainder(MatchEngine.HitFacts hit, long remainder) {
        return new MatchEngine.HitFacts(
                hit.expectationId(),
                hit.kind(),
                hit.direction(),
                hit.amount(),
                remainder,
                hit.openedAt(),
                hit.expectedBy(),
                hit.reachedBy(),
                hit.operationRef(),
                hit.settlementCycle());
    }

    private void applyDefinitive(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Snapshot snapshot,
            MatchingStore.Basis basis,
            UUID decisionId,
            LocalDate decidedOn,
            BreakType type,
            BreakCause cause,
            Optional<InternalReferenceLookup.InternalReference> internal,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        snapshot.rulePriority(),
                        snapshot.strategy(),
                        snapshot.keyKind(),
                        DecisionOutcome.PARKED,
                        Optional.empty(),
                        snapshot.claimantCount(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        basis));
        store.insertCandidates(unitOfWork, decisionId, snapshot.candidates());
        store.insertParkedOriginals(unitOfWork, decisionId, snapshot.parkedOriginals());
        BreakRegister.Raised raised =
                breaks.raise(
                        unitOfWork,
                        new BreakRegister.NewBreak(
                                ids.next(),
                                type,
                                cause,
                                BreakRegister.Subject.externalItem(item.id()),
                                run.sourceId(),
                                run.ruleSetId(),
                                item.amount(),
                                Optional.of(item.direction()),
                                Optional.empty(),
                                internal.map(
                                        InternalReferenceLookup.InternalReference
                                                ::classification),
                                internal.flatMap(
                                        InternalReferenceLookup.InternalReference
                                                ::operationRef),
                                internal.flatMap(
                                        InternalReferenceLookup.InternalReference::state),
                                Optional.empty(),
                                SecurityContext.require(),
                                now,
                                correlation));
        parks.add(
                new Suspense.ParkedItem(
                        item.id(), raised.breakId(), item.amount(),
                        positionAccount(unitOfWork, run, item)));
    }

    /**
     * The waiting decision. A value-date group that saw candidates and did not match names
     * its rule and stores what it saw ({@code group}, `P8-TSK-016`); every other wait names
     * no rule, as before.
     */
    private void applyWaiting(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            UUID decisionId,
            LocalDate decidedOn,
            Optional<Integer> graceHours,
            Snapshot snapshot,
            MatchingStore.Basis basis,
            Instant now,
            CorrelationId correlation) {
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        snapshot.rulePriority(),
                        snapshot.strategy(),
                        snapshot.keyKind(),
                        DecisionOutcome.UNMATCHED,
                        Optional.empty(),
                        snapshot.claimantCount(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation,
                        basis));
        store.insertCandidates(unitOfWork, decisionId, snapshot.candidates());
        store.insertParkedOriginals(unitOfWork, decisionId, snapshot.parkedOriginals());
        exitOrThrow(
                store.markItemUnmatched(
                        unitOfWork, item.id(), graceHours, SecurityContext.require(), now,
                        correlation),
                item.id(), "PENDING -> UNMATCHED");
    }

    /** No key reached anything: the lookup TYPES the remainder (ADR-0068 §6). */
    private void applyUnreached(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Resolution resolution,
            Snapshot snapshot,
            MatchingStore.Basis basis,
            UUID decisionId,
            LocalDate decidedOn,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        InternalReferenceLookup.InternalReference internal =
                classifyThroughLookup(unitOfWork, run, item);
        if (internal.classification() == InternalClassification.TERMINAL) {
            boolean refund = namesARefund(item, internal);
            applyDefinitive(
                    unitOfWork, run, item, snapshot, basis, decisionId, decidedOn,
                    refund ? BreakType.REFUND_MISMATCH : BreakType.REVERSAL_MISMATCH,
                    refund
                            ? BreakCause.REFUND_CONTRADICTED
                            : BreakCause.TERMINAL_STATE_CONTRADICTED,
                    Optional.of(internal), parks, now, correlation);
            return;
        }
        applyWaiting(
                unitOfWork, run, item, decisionId, decidedOn,
                resolution.waitingGraceHours(), snapshot, basis, now, correlation);
    }

    /** The lookup's frozen answer over the item's own keys — for typing only, never
     * for allocation (ADR-0068 §1). */
    private InternalReferenceLookup.InternalReference classifyThroughLookup(
            Connection unitOfWork, MatchingStore.RunRow run, MatchingStore.ChunkItem item) {
        UUID scope = item.keyScope(run.sourceId());
        Map<KeyKind, String> lookupKeys = new java.util.EnumMap<>(KeyKind.class);
        item.keys()
                .forEach(
                        (kind, value) ->
                                lookupKeys.put(KeyKind.valueOf(mapToLookup(kind)), value));
        // The item's key-scope source rides with the subject, so the composition can name the
        // rail a scheme reference is claimed under (P8-TSK-017) - reconciliation names none.
        return lookup.classify(
                unitOfWork,
                new InternalReferenceLookup.LookupSubject(
                        Optional.empty(), lookupKeys, Optional.of(scope)));
    }

    /**
     * Whether a terminal answer contradicts a refund (`P8-TSK-017`): a {@code REFUND} line, or
     * any line whose references named a refund - a scheme's {@code DEBIT_OUT} naming a return
     * the platform concluded {@code FAILED} is a refund's mismatch, never a reversal's.
     */
    private static boolean namesARefund(
            MatchingStore.ChunkItem item, InternalReferenceLookup.InternalReference internal) {
        return item.lineType() == ExternalLineType.REFUND
                || internal.subject().equals(Optional.of(InternalSubject.REFUND));
    }

    /** The item-side key vocabulary onto the lookup's (the design's D8, inverted). */
    private static String mapToLookup(ItemKeyKind kind) {
        return switch (kind) {
            case DISPUTE_REF -> KeyKind.DISPUTE_CB_REF.name();
            case ORIGINAL_REF -> KeyKind.PSP_CAPTURE_REF.name();
            default -> kind.name();
        };
    }

    private void containPoisoned(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            JudgedStatus judged,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation,
            RuntimeException poisoned) {
        log.warn(
                "A poisoned item was contained; the rows behind it are other people's"
                        + " money: {}",
                poisoned.getClass().getSimpleName());
        // Whatever the failed attempt queued for this item rolled back with it.
        parks.removeIf(queued -> queued.externalItemId().equals(item.id()));
        // A poisoned FEE line is contained WITHOUT a park (`P8-TSK-012`): its value was
        // expensed at acceptance, so nothing of it sits in the position - it is left
        // UNMATCHED with no grace clock, its ERRORED decision and unparked
        // PROCESSING_ERROR break the honest record. A bank fee is the same fact, and a
        // line standing in no position has nothing a park could move (`P8-TSK-016`).
        if (!item.lineType().allocating() || item.positionPurpose().isEmpty()) {
            UUID feeDecision = ids.next();
            insertDecision(
                    run,
                    unitOfWork,
                    new MatchingStore.NewDecision(
                            feeDecision,
                            item.id(),
                            run.id(),
                            DecisionOrigin.RUN,
                            run.ruleSetId(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            DecisionOutcome.ERRORED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            SecurityContext.require(),
                            now,
                            LocalDate.ofInstant(now, ZoneOffset.UTC),
                            correlation,
                            MatchingStore.Basis.of(
                                    DecisionVerdict.ERRORED, judged,
                                    item.amount().minorUnits())));
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.PROCESSING_ERROR, BreakCause.ITEM_ERRORED,
                            BreakRegister.Subject.externalItem(item.id()), item.amount(),
                            Optional.empty(), now));
            // Unchecked by design (IDEM-3's review): the grace leg contains an item already
            // UNMATCHED - the PENDING edge finds no row and the item simply keeps waiting.
            store.markItemUnmatched(
                    unitOfWork, item.id(), Optional.empty(), SecurityContext.require(),
                    now, correlation);
            return;
        }
        UUID decisionId = ids.next();
        insertDecision(
                run,
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        DecisionOutcome.ERRORED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        LocalDate.ofInstant(now, ZoneOffset.UTC),
                        correlation,
                        MatchingStore.Basis.of(
                                DecisionVerdict.ERRORED, judged,
                                item.amount().minorUnits())));
        BreakRegister.Raised raised =
                breaks.raise(
                        unitOfWork,
                        newBreak(
                                run, item, BreakType.PROCESSING_ERROR,
                                BreakCause.ITEM_ERRORED,
                                BreakRegister.Subject.externalItem(item.id()),
                                item.amount(), Optional.empty(), now));
        parks.add(
                new Suspense.ParkedItem(
                        item.id(), raised.breakId(), item.amount(),
                        positionAccount(unitOfWork, run, item)));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A conditional item exit is an arbiter (ADR-0068 §4): finding its locked row moved, the
     * decision written beside it must not stand, so the attempt throws and rolls back to the
     * item's savepoint — never a decision or an allocation committed over an item another
     * writer disposed (the Phase 8 -> 9 transition's IDEM-3; the lockItems status re-read
     * makes this unreachable in the run leg, and the late legs judge locked rows).
     */
    private static void exitOrThrow(boolean exited, UUID itemId, String edge) {
        if (!exited) {
            throw new IllegalStateException(
                    "the conditional exit " + edge + " of item " + itemId + " updated no"
                            + " row under its lock: the attempt rolls back (IDEM-3)");
        }
    }

    private BreakRegister.NewBreak newBreak(
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            BreakType type,
            BreakCause cause,
            BreakRegister.Subject subject,
            Money valueAtIssue,
            Optional<ExpectationKind> expectationKind,
            Instant now) {
        return new BreakRegister.NewBreak(
                ids.next(),
                type,
                cause,
                subject,
                run.sourceId(),
                run.ruleSetId(),
                valueAtIssue,
                Optional.of(item.direction()),
                expectationKind,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                SecurityContext.require(),
                now,
                CorrelationId.of(run.correlationId()));
    }

    private MatchEngine.ItemFacts facts(MatchingStore.ChunkItem item) {
        return new MatchEngine.ItemFacts(
                item.id(), item.lineType(), item.direction(), item.amount(),
                item.businessDate(), item.settlementDate(), false);
    }

    /**
     * The item's facts with its run's cycle — what the cycle comparison reads (`P8-TSK-017`) —
     * and its TRUE fingerprint verdict: every late leg judges with it (the Phase 8 -> 9
     * transition's REC-1; a hard-coded {@code false} here let a definitive duplicate allocate).
     */
    private static MatchEngine.ItemFacts facts(
            MatchingStore.RunRow run, MatchingStore.ChunkItem item, boolean fingerprintSeen) {
        return new MatchEngine.ItemFacts(
                item.id(), item.lineType(), item.direction(), item.amount(),
                item.businessDate(), item.settlementDate(), fingerprintSeen,
                run.settlementCycle());
    }

    /**
     * Whether an identical line stands EARLIER in claimant order (ADR-0068 §4) - the definitive
     * duplicate. Never for a bank statement's line: a statement is unique by its chain's sequence,
     * so a re-delivered statement never reaches here, and two equal same-day tranches of one
     * remittance are genuine cash sharing one canonical fingerprint - a tranche that finds its
     * remittance exhausted is the matching engine's {@code EXPECTATION_EXHAUSTED}, never a
     * fingerprint duplicate (the Phase 8 -> 9 transition's T-3).
     */
    private boolean fingerprintSeen(
            Connection unitOfWork, UUID sourceId, MatchingStore.ChunkItem item) {
        if (item.lineType().isBankLine()) {
            return false;
        }
        return store.fingerprintSeenEarlier(
                unitOfWork, sourceId, item.fingerprint(), item.sourceSequence(), item.lineNo());
    }

    private UUID positionAccount(
            Connection unitOfWork, MatchingStore.RunRow run, MatchingStore.ChunkItem item) {
        com.finapp.ledger.AccountPurpose position =
                item.positionPurpose()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "only a positioned item parks - a report line"
                                                        + " or an attributed bank line;"
                                                        + " a bank fee or an unattributed"
                                                        + " bank line stands in none"
                                                        + " (P8-TSK-016's position rule)"));
        // The item's own source settles it - an attributed bank line its attributed source's -
        // and a counterparty's source settles its OWN account (P9-TSK-011, ADR-0078).
        UUID source = item.attributedSourceId().orElse(run.sourceId());
        return positionAccounts
                .accountOf(unitOfWork, source, position, item.amount().currency())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart seeds every position per currency"));
    }

    /**
     * An item's savepoint and everything beside it the attempt can move: the count mark (a
     * rollback drops the counts the undone attempt deferred, `P8-TSK-024`'s gate), and the leg's
     * Java state - how many parks and releases were queued, and the claimant counts - so a
     * rollback undoes the attempt's Java effects with its rows, never only its rows (the
     * Phase 8 -> 9 transition's ATOM-02: a rolled-back allocation's queued unpark still fired in
     * the posting phase, and its claimant rank still counted). The run leg re-reads its chunk's
     * remainders from the restored rows besides.
     */
    private record ItemSavepoint(
            Savepoint savepoint,
            int countMark,
            int queuedParks,
            int queuedReleases,
            int queuedSameChunkOffsets,
            Map<UUID, Integer> claimantRanks) {}

    private ItemSavepoint savepoint(
            Connection unitOfWork,
            MatchingStore.ChunkItem item,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> releases,
            Map<UUID, Integer> claimantRanks) {
        return savepoint(unitOfWork, item, parks, releases, List.of(), claimantRanks);
    }

    private ItemSavepoint savepoint(
            Connection unitOfWork,
            MatchingStore.ChunkItem item,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> releases,
            List<QueuedOffset> sameChunkOffsets,
            Map<UUID, Integer> claimantRanks) {
        try {
            return new ItemSavepoint(
                    unitOfWork.setSavepoint("item_" + item.lineNo()), telemetry.countMark(),
                    parks.size(), releases.size(), sameChunkOffsets.size(),
                    Map.copyOf(claimantRanks));
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not set the item's savepoint", failure);
        }
    }

    /**
     * The posting phase's savepoint (the whole phase, or one park re-posted alone): its rows and
     * the counts it deferred. The queued parks are the phase's input, never its effect, so a
     * rollback here leaves them for the per-park re-post.
     */
    private record PhaseSavepoint(Savepoint savepoint, int countMark) {}

    private PhaseSavepoint phaseSavepoint(Connection unitOfWork, String name) {
        try {
            return new PhaseSavepoint(unitOfWork.setSavepoint(name), telemetry.countMark());
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not set the posting phase's savepoint", failure);
        }
    }

    private void rollbackToPhase(Connection unitOfWork, PhaseSavepoint savepoint) {
        try {
            unitOfWork.rollback(savepoint.savepoint());
            telemetry.discardCountsAfter(savepoint.countMark());
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not contain the failed posting phase", failure);
        }
    }

    private void rollbackTo(
            Connection unitOfWork,
            ItemSavepoint savepoint,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> releases,
            Map<UUID, Integer> claimantRanks) {
        rollbackTo(unitOfWork, savepoint, parks, releases, List.of(), claimantRanks);
    }

    private void rollbackTo(
            Connection unitOfWork,
            ItemSavepoint savepoint,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> releases,
            List<QueuedOffset> sameChunkOffsets,
            Map<UUID, Integer> claimantRanks) {
        try {
            unitOfWork.rollback(savepoint.savepoint());
            telemetry.discardCountsAfter(savepoint.countMark());
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not contain the poisoned item", failure);
        }
        parks.subList(savepoint.queuedParks(), parks.size()).clear();
        releases.subList(savepoint.queuedReleases(), releases.size()).clear();
        if (sameChunkOffsets.size() > savepoint.queuedSameChunkOffsets()) {
            sameChunkOffsets
                    .subList(savepoint.queuedSameChunkOffsets(), sameChunkOffsets.size())
                    .clear();
        }
        claimantRanks.clear();
        claimantRanks.putAll(savepoint.claimantRanks());
    }
}
