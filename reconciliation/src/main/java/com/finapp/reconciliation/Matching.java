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
 * The run leg (`P8-TSK-011`, ADR-0068 §§3–6): per source, under
 * {@code pg_try_advisory_xact_lock(4, hashtext(source_id::text))} per chunk — an instance
 * refused the lock moves to the next source (the {@code OutboxRelay} argument) — the
 * lowest-sequence eligible run is walked chunk by chunk in {@code line_no} order, each
 * chunk one transaction that re-reads the cursor and advances it, so a crash resumes on
 * any instance exactly where the last commit left off.
 *
 * <p><strong>The lock only orders; the arbiters are PostgreSQL's</strong>: the item's
 * conditional exit from {@code PENDING}, `V005`'s allocation unique and deferred Σ
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
            int rematched) {}

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
            for (UUID source : transactions.inTransaction(store::sourcesWithRematchWork)) {
                rematched += legContained("rematch", source, this::rematchBatch);
            }
        }
        return new SweepResult(
                sources.size(), chunks, decided, completed, blocked, graced, rematched);
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
                step = transactions.inTransaction(unitOfWork -> batch.apply(unitOfWork, source));
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
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        for (MatchingStore.ResidualItem residual : residuals) {
            CorrelationId correlation = CorrelationId.of(residual.correlationId());
            try (CorrelationContext.Scope scope =
                    CorrelationContext.enter(Correlation.startingWith(correlation))) {
                Savepoint savepoint = savepoint(unitOfWork, residual.item());
                try {
                    graceOne(unitOfWork, residual, claimantRanks, parks, now, correlation);
                } catch (RuntimeException poisoned) {
                    rollbackTo(unitOfWork, savepoint);
                    containPoisoned(
                            unitOfWork, syntheticRun(residual), residual.item(), parks,
                            now, correlation, poisoned);
                }
            }
        }
        // The batch's postings carry the leg's own correlation (INV-LED-05's scope);
        // each item's records above carry the ingesting flow's.
        CorrelationId batchCorrelation = CorrelationId.generate(ids);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(Correlation.startingWith(batchCorrelation))) {
            postPhase(unitOfWork, source, residuals.get(0).ruleSetId(), parks, List.of(),
                    now, batchCorrelation);
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
        // The fingerprint was judged at run time; a grace re-judgement never re-parks
        // a waiting item as a duplicate of itself.
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        facts(run, item), fired, resolution.anyLandedRule(), tolerance);
        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        switch (verdict.kind()) {
            case ALLOCATE ->
                    applyAllocation(
                            unitOfWork, run, item, fired.orElseThrow(), verdict,
                            decisionId, decidedOn, lockedHits, claimantRanks, parks,
                            tolerance, DecisionOrigin.RUN, "UNMATCHED", now, correlation);
            case AMBIGUOUS ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                            Optional.empty(), parks, now, correlation);
            case DIRECTION_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.DIRECTION_CONTRADICTED,
                            Optional.empty(), parks, now, correlation);
            case CURRENCY_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                            Optional.empty(), parks, now, correlation);
            case DUPLICATE ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.DUPLICATE_EXTERNAL, BreakCause.EXPECTATION_EXHAUSTED,
                            Optional.empty(), parks, now, correlation);
            case NO_CANDIDATES, NO_RULE -> {
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
                }
                InternalReferenceLookup.InternalReference internal =
                        classifyThroughLookup(unitOfWork, run, item);
                if (internal.classification() == InternalClassification.TERMINAL) {
                    boolean refund = namesARefund(item, internal);
                    applyDefinitive(
                            unitOfWork, run, item, Optional.empty(), decisionId,
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
                            unitOfWork, run, item, Optional.empty(), decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.RETURN_NOT_APPLICABLE,
                            Optional.of(internal), parks, now, correlation);
                    return;
                }
                // Grace has run out: the remainder is OWNED now, its classification the
                // lookup's frozen answer (INV-REC-02).
                applyDefinitive(
                        unitOfWork, run, item, Optional.empty(), decisionId, decidedOn,
                        internal.classification() == InternalClassification.UNKNOWN
                                ? BreakType.UNKNOWN_EXTERNAL
                                : BreakType.MISSING_INTERNAL,
                        BreakCause.GRACE_EXPIRED,
                        Optional.of(internal), parks, now, correlation);
            }
        }
    }

    /**
     * The rematch leg (`P8-TSK-013`): residual items whose keys now reach an expectation
     * opened after their latest decision, re-decided under origin {@code REMATCH}
     * through the shared allocation path — {@code UNMATCHED → MATCHED}; {@code PARKED →
     * MATCHED} with the unpark and the owning break resolved {@code EVIDENCED}. Only the
     * {@code ALLOCATE} verdict acts; committed decisions, parks and breaks are never
     * edited ({@code INV-HIST-04}). Returns the number ACTED on — zero stops the drain,
     * and a skipped candidate simply waits for later evidence.
     */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    private int rematchBatch(Connection unitOfWork, UUID source) {
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
        Instant now = Instant.now(clock);
        List<Suspense.ParkedItem> parks = new ArrayList<>();
        List<OffsetIntent> unparks = new ArrayList<>();
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        int acted = 0;
        for (MatchingStore.ResidualItem residual : residuals) {
            CorrelationId correlation = CorrelationId.of(residual.correlationId());
            try (CorrelationContext.Scope scope =
                    CorrelationContext.enter(Correlation.startingWith(correlation))) {
                Savepoint savepoint = savepoint(unitOfWork, residual.item());
                try {
                    if (rematchOne(
                            unitOfWork, residual, claimantRanks, parks, unparks, now,
                            correlation)) {
                        acted++;
                    }
                } catch (RuntimeException poisoned) {
                    // A rematch is opportunistic: the residual keeps its committed
                    // record and waits for the next tick or later evidence.
                    rollbackTo(unitOfWork, savepoint);
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
            postPhase(unitOfWork, source, residuals.get(0).ruleSetId(), parks, unparks,
                    now, batchCorrelation);
        }
        return acted;
    }

    private boolean rematchOne(
            Connection unitOfWork,
            MatchingStore.ResidualItem residual,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> unparks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        Resolution resolution =
                resolve(unitOfWork, residual.ruleSetId(), residual.sourceId(), item);
        Map<UUID, MatchEngine.HitFacts> lockedHits =
                lockResolutionHits(unitOfWork, resolution);
        int tolerance = rules.settlementDateToleranceDays(unitOfWork, residual.ruleSetId());
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(
                        facts(syntheticRun(residual), item), fired, resolution.anyLandedRule(),
                        tolerance);
        if (verdict.kind() == MatchEngine.VerdictKind.NO_CANDIDATES
                && resolution.groupRule().isPresent()) {
            // A waiting item's value-date group, re-judged now a candidate opened after its
            // decision (P8-TSK-016); a PARKED item re-matches by its reference alone.
            if (!"UNMATCHED".equals(store.itemStatus(unitOfWork, item.id()))) {
                return false;
            }
            GroupMatch.Verdict group =
                    groupVerdict(
                            unitOfWork, item, residual.sourceId(), resolution, lockedHits,
                            java.util.Set.of());
            if (group.kind() != GroupMatch.Kind.MATCH) {
                return false;
            }
            applyGroupMatch(
                    unitOfWork, syntheticRun(residual), item, resolution.groupRule().get(),
                    group, ids.next(), LocalDate.ofInstant(now, ZoneOffset.UTC), lockedHits,
                    claimantRanks, tolerance, DecisionOrigin.REMATCH, "UNMATCHED", now,
                    correlation);
            return true;
        }
        if (verdict.kind() != MatchEngine.VerdictKind.ALLOCATE) {
            return false;
        }
        String status = store.itemStatus(unitOfWork, item.id());
        if ("UNMATCHED".equals(status)) {
            applyAllocation(
                    unitOfWork, syntheticRun(residual), item, fired.orElseThrow(),
                    verdict, ids.next(), LocalDate.ofInstant(now, ZoneOffset.UTC),
                    lockedHits, claimantRanks, parks, tolerance, DecisionOrigin.REMATCH,
                    "UNMATCHED", now, correlation);
            return true;
        }
        if (!"PARKED".equals(status)) {
            return false; // Disposed meanwhile: converge.
        }
        return applyParkedRematch(
                unitOfWork, residual, verdict, claimantRanks, unparks, now, correlation);
    }

    /** {@code PARKED → MATCHED}: the parked value allocated whole, unparked, EVIDENCED. */
    private boolean applyParkedRematch(
            Connection unitOfWork,
            MatchingStore.ResidualItem residual,
            MatchEngine.Verdict verdict,
            Map<UUID, Integer> claimantRanks,
            List<OffsetIntent> unparks,
            Instant now,
            CorrelationId correlation) {
        MatchingStore.ChunkItem item = residual.item();
        List<CorrectionEngine.ParkedOriginal> parked =
                store.lockParkedOriginals(unitOfWork, List.of(item.id()));
        if (parked.isEmpty()) {
            return false; // Released meanwhile: converge.
        }
        CorrectionEngine.ParkedOriginal suspenseRow = parked.get(0);
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        long parkedRemainder = suspenseRow.remainderMinor();
        if (candidate.remainderMinor() < parkedRemainder
                || !candidate.amount().currency().equals(suspenseRow.currency())) {
            // A partial unpark would leave a split record; the residual waits for a
            // fuller candidate or a person (the design's G2 refinement, recorded).
            return false;
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
        UUID decisionId = ids.next();
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        residual.runId(),
                        DecisionOrigin.REMATCH,
                        residual.ruleSetId(),
                        Optional.empty(),
                        Optional.of(Cardinality.ONE_TO_ONE),
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
                        correlation));
        store.insertCandidates(unitOfWork, decisionId, List.of(candidate));
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
                    candidate.operationRef(), residual.sourceId(), now, correlation);
        }
        store.markItemMatchedFrom(
                unitOfWork, item.id(), "PARKED", parkedRemainder,
                SecurityContext.require(), now, correlation);
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
                            syntheticRun(residual), item, BreakType.TIMING_DIFFERENCE,
                            BreakCause.CYCLE_MISMATCH, BreakRegister.Subject.decision(decisionId),
                            Money.ofPersisted(0, amount.currency(), amount.scale()),
                            Optional.empty(), now));
        }
        if (residual.runCycle().isPresent() && candidate.settlementCycle().isEmpty()) {
            store.recordLearnedCycle(unitOfWork, item.id(), residual.runCycle().get());
        }
        unparks.add(
                new OffsetIntent(decisionId, suspenseRow, amount, ReleaseCause.UNPARK));
        overdueBreak.ifPresent(
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
                                        residual.ruleSetId(),
                                        SecurityContext.require(),
                                        now,
                                        correlation)));
        return true;
    }

    /** The synthetic run view over a residual's stored facts — never persisted. */
    private MatchingStore.RunRow syntheticRun(MatchingStore.ResidualItem residual) {
        return new MatchingStore.RunRow(
                residual.runId(),
                residual.sourceId(),
                null,
                RunStatus.COMPLETED,
                residual.ruleSetId(),
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

    /** One chunk, contained: its failure is the run's failure, never the sweep's. */
    private ChunkOutcome chunkContained(UUID source) {
        UUID[] runHolder = new UUID[1];
        try {
            return transactions.inTransaction(uow -> chunk(uow, source, runHolder));
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
        store.lockItems(unitOfWork, items.stream().map(MatchingStore.ChunkItem::id).toList());

        List<Suspense.ParkedItem> parks = new ArrayList<>();
        List<OffsetIntent> offsets = new ArrayList<>();
        // The chunk's own claimant ledger: each allocation diminishes the remainder the
        // NEXT item in claimant order sees (INV-REC-07 inside one chunk), and ranks count.
        Map<UUID, Integer> claimantRanks = new HashMap<>();
        int decided = 0;
        long lastLineNo = run.cursor();
        for (MatchingStore.ChunkItem item : items) {
            Savepoint savepoint = savepoint(unitOfWork, item);
            try {
                decideAndApply(
                        unitOfWork, run, item, resolutions.get(item.id()), lockedHits,
                        keyClaims, claimantRanks, tolerance, parks, offsets, now,
                        correlation);
            } catch (RuntimeException poisoned) {
                rollbackTo(unitOfWork, savepoint);
                containPoisoned(unitOfWork, run, item, parks, now, correlation, poisoned);
            }
            decided++;
            lastLineNo = item.lineNo();
        }
        store.advanceCursor(unitOfWork, run.id(), lastLineNo, now);
        boolean lastChunk = items.size() < config.chunkSize();
        if (lastChunk) {
            completeRun(unitOfWork, run, now, correlation);
        }
        postPhase(unitOfWork, run.sourceId(), run.ruleSetId(), parks, offsets, now,
                correlation);
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
            Instant now,
            CorrelationId correlation) {
        if (parks.size() + offsets.size() > 1) {
            preLockPostingUnion(unitOfWork, parks, offsets);
        }
        if (!parks.isEmpty()) {
            suspense.park(
                    unitOfWork,
                    new Suspense.ParkCommand(
                            sourceId,
                            LocalDate.ofInstant(now, ZoneOffset.UTC),
                            parks,
                            SecurityContext.require(),
                            now,
                            correlation));
        }
        for (OffsetIntent offset : offsets) {
            applyOffsetPostingAndEvidence(unitOfWork, ruleSetId, offset, now, correlation);
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
        // The per-batch fee comparison (`P8-TSK-012`, ADR-0068 §7), judged once by the
        // completing edge's one winner: the signed fold of reported - expected per
        // currency over the run's CHECKED PROCESSING_FEE decisions - a bank fee is judged
        // per line only, P8-TSK-016 - (one currency's minor units -
        // Java arithmetic, never SQL SUM), its subject the run's FIRST fee item in
        // claimant order (the design's D: the taxonomy names the item, and the batch's
        // fee narrative starts at its first line), converging on the one-open unique.
        Map<String, long[]> foldByCurrency = new LinkedHashMap<>();
        Map<String, MatchingStore.FeeDecisionRow> firstByCurrency = new LinkedHashMap<>();
        for (MatchingStore.FeeDecisionRow fee : store.feeDecisionsOf(unitOfWork, run.id())) {
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
            Instant now,
            CorrelationId correlation) {
        boolean fingerprintSeen =
                store.fingerprintSeenEarlier(
                        unitOfWork,
                        run.sourceId(),
                        item.fingerprint(),
                        item.sourceSequence(),
                        item.lineNo());
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
                applyFeeCheck(unitOfWork, run, item, unlanded, now, correlation);
                return;
            }
            if (unlanded.cardinality() == Cardinality.CORRECTION) {
                applyCorrection(
                        unitOfWork, run, item, facts, resolution, unlanded,
                        claimantRanks, parks, offsets, now, correlation);
                return;
            }
        }
        Optional<MatchEngine.FiredRule> fired = firedRule(resolution, lockedHits);
        MatchEngine.Verdict verdict =
                MatchEngine.decide(facts, fired, resolution.anyLandedRule(), toleranceDays);

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
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                            Optional.empty(), parks, now, correlation);
            case DIRECTION_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.REVERSAL_MISMATCH, BreakCause.DIRECTION_CONTRADICTED,
                            Optional.empty(), parks, now, correlation);
            case CURRENCY_CONTRADICTED ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                            Optional.empty(), parks, now, correlation);
            case DUPLICATE ->
                    applyDefinitive(
                            unitOfWork, run, item, fired, decisionId, decidedOn,
                            BreakType.DUPLICATE_EXTERNAL,
                            fingerprintSeen
                                    ? BreakCause.REPEATED_FINGERPRINT
                                    : BreakCause.EXPECTATION_EXHAUSTED,
                            Optional.empty(), parks, now, correlation);
            case NO_RULE ->
                    applyWaiting(
                            unitOfWork, run, item, decisionId, decidedOn,
                            Optional.empty(), Optional.empty(), now, correlation);
            case NO_CANDIDATES -> {
                // No key reached anything: a line type riding a value-date group is judged
                // against its date's untouched candidates before the lookup types what is
                // left (`P8-TSK-016`).
                Optional<GroupEvaluation> group = Optional.empty();
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
                    group = Optional.of(
                            new GroupEvaluation(resolution.groupRule().get(), grouped));
                }
                applyUnreached(
                        unitOfWork, run, item, resolution, group, decisionId, decidedOn,
                        parks, now, correlation);
            }
            default ->
                    throw new IllegalStateException("unhandled verdict " + verdict.kind());
        }
    }

    /**
     * A value-date group that was judged and did not match (`P8-TSK-016`) — recorded on the
     * waiting decision with the candidates it saw, so the wait explains itself from stored
     * rows (ADR-0068 §5: every evaluation snapshots what it saw).
     */
    private record GroupEvaluation(MatchingRules.RuleRow rule, GroupMatch.Verdict verdict) {}

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
        store.insertDecision(
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
                        correlation));
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
                        member.operationRef(), run.sourceId(), now, correlation);
            }
            allocated.put(member.expectationId(), allocation);
        }
        store.markItemMatchedFrom(
                unitOfWork, item.id(), itemFromStatus, item.amount().minorUnits(),
                SecurityContext.require(), now, correlation);
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
        store.insertDecision(
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
                        correlation));
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
                    candidate.operationRef(), run.sourceId(), now, correlation);
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
                            positionAccount(unitOfWork, item)));
        } else {
            store.markItemMatchedFrom(
                    unitOfWork, item.id(), itemFromStatus, allocation.minorUnits(),
                    SecurityContext.require(), now, correlation);
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
     */
    private void applyFeeCheck(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
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
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        ids.next(),
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
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
                        correlation));
        store.markItemChecked(
                unitOfWork, item.id(), SecurityContext.require(), now, correlation);
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
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            List<OffsetIntent> offsets,
            Instant now,
            CorrelationId correlation) {
        if (facts.fingerprintSeenEarlier()) {
            applyDefinitive(
                    unitOfWork, run, item, Optional.empty(), ids.next(),
                    LocalDate.ofInstant(now, ZoneOffset.UTC),
                    BreakType.DUPLICATE_EXTERNAL, BreakCause.REPEATED_FINGERPRINT,
                    Optional.empty(), parks, now, correlation);
            return;
        }
        String originalRef = item.keys().get(ItemKeyKind.ORIGINAL_REF);
        List<MatchEngine.HitFacts> hits = List.of();
        List<CorrectionEngine.ParkedOriginal> parkedOriginals = List.of();
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
            java.util.LinkedHashSet<UUID> originalItems = new java.util.LinkedHashSet<>();
            for (ItemKeyKind kind :
                    List.of(ItemKeyKind.PSP_CAPTURE_REF, ItemKeyKind.PSP_REFUND_REF)) {
                originalItems.addAll(
                        store.itemsByKey(unitOfWork, run.sourceId(), kind, originalRef));
            }
            originalItems.remove(item.id()); // Never its own original.
            parkedOriginals = store.lockParkedOriginals(unitOfWork, originalItems);
        }
        CorrectionEngine.Verdict verdict =
                CorrectionEngine.decide(facts, hits, parkedOriginals);
        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        switch (verdict.kind()) {
            case TOP_UP ->
                    applyTopUp(
                            unitOfWork, run, item, rule, verdict, decisionId, decidedOn,
                            claimantRanks, parks, now, correlation);
            case OFFSET -> {
                CorrectionEngine.ParkedOriginal parked = verdict.offset().orElseThrow();
                store.insertDecision(
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
                                correlation));
                store.markItemOffset(
                        unitOfWork, item.id(), item.amount().minorUnits(),
                        SecurityContext.require(), now, correlation);
                store.markParkedItemResolved(
                        unitOfWork, parked.originalItemId(), SecurityContext.require(),
                        now, correlation);
                offsets.add(
                        new OffsetIntent(
                                decisionId, parked, item.amount(),
                                ReleaseCause.CORRECTION_OFFSET));
            }
            case UNREACHED ->
                    applyUnreached(
                            unitOfWork, run, item, resolution, Optional.empty(), decisionId,
                            decidedOn, parks, now, correlation);
        }
    }

    /** The correction's allocating half: `ONE_TO_ONE`'s arithmetic, `EVIDENCED` at zero. */
    private void applyTopUp(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            MatchingRules.RuleRow rule,
            CorrectionEngine.Verdict verdict,
            UUID decisionId,
            LocalDate decidedOn,
            Map<UUID, Integer> claimantRanks,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        Money allocation = verdict.allocation().orElseThrow();
        boolean settles = allocation.minorUnits() == candidate.remainderMinor();
        // The break BEFORE the settling allocation (the §3 order): the one this top-up
        // may close is locked first, so the evidence write serialises break-first with
        // any other writer of that break. A REMITTANCE's shortfall is its
        // SETTLEMENT_MISMATCH (P8-TSK-016), closed exactly as AMOUNT_MISMATCH is.
        Optional<UUID> explained =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                differenceType(candidate.kind()))
                        : Optional.empty();
        int claimantRank =
                claimantRanks.merge(candidate.expectationId(), 1, Integer::sum);
        store.insertDecision(
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
                        correlation));
        store.insertCandidates(unitOfWork, decisionId, List.of(candidate));
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
        if (status == ExpectationStatus.SETTLED) {
            ReconciliationEvents.expectationSettled(
                    outbox, unitOfWork, ids, candidate.expectationId(), candidate.kind(),
                    candidate.operationRef(), run.sourceId(), now, correlation);
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
                            positionAccount(unitOfWork, item)));
        } else {
            store.markItemMatchedFrom(
                    unitOfWork, item.id(), "PENDING", allocation.minorUnits(),
                    SecurityContext.require(), now, correlation);
        }
        // A zero remainder EXPLAINS the under-payment's own break: EVIDENCED, in this
        // transaction, no posting of its own - the allocation is the effect.
        explained.ifPresent(
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

    private void applyDefinitive(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Optional<MatchEngine.FiredRule> fired,
            UUID decisionId,
            LocalDate decidedOn,
            BreakType type,
            BreakCause cause,
            Optional<InternalReferenceLookup.InternalReference> internal,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        fired.map(MatchEngine.FiredRule::priority),
                        fired.map(MatchEngine.FiredRule::cardinality),
                        fired.map(MatchEngine.FiredRule::keyKind),
                        DecisionOutcome.PARKED,
                        Optional.empty(),
                        fired.map(rule -> rule.hits().size()),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation));
        fired.ifPresent(
                rule -> store.insertCandidates(unitOfWork, decisionId, rule.hits()));
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
                        positionAccount(unitOfWork, item)));
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
            Optional<GroupEvaluation> group,
            Instant now,
            CorrelationId correlation) {
        Optional<GroupEvaluation> seen =
                group.filter(evaluation -> !evaluation.verdict().candidates().isEmpty());
        store.insertDecision(
                unitOfWork,
                new MatchingStore.NewDecision(
                        decisionId,
                        item.id(),
                        run.id(),
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        seen.map(evaluation -> evaluation.rule().priority()),
                        seen.map(evaluation -> Cardinality.GROUP_BY_VALUE_DATE),
                        Optional.empty(),
                        DecisionOutcome.UNMATCHED,
                        Optional.empty(),
                        seen.map(evaluation -> evaluation.verdict().candidates().size()),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation));
        seen.ifPresent(
                evaluation ->
                        store.insertCandidates(
                                unitOfWork, decisionId, evaluation.verdict().candidates()));
        store.markItemUnmatched(
                unitOfWork, item.id(), graceHours, SecurityContext.require(), now, correlation);
    }

    /** No key reached anything: the lookup TYPES the remainder (ADR-0068 §6). */
    private void applyUnreached(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Resolution resolution,
            Optional<GroupEvaluation> group,
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
                    unitOfWork, run, item, Optional.empty(), decisionId, decidedOn,
                    refund ? BreakType.REFUND_MISMATCH : BreakType.REVERSAL_MISMATCH,
                    refund
                            ? BreakCause.REFUND_CONTRADICTED
                            : BreakCause.TERMINAL_STATE_CONTRADICTED,
                    Optional.of(internal), parks, now, correlation);
            return;
        }
        applyWaiting(
                unitOfWork, run, item, decisionId, decidedOn,
                resolution.waitingGraceHours(), group, now, correlation);
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
            store.insertDecision(
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
                            correlation));
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.PROCESSING_ERROR, BreakCause.ITEM_ERRORED,
                            BreakRegister.Subject.externalItem(item.id()), item.amount(),
                            Optional.empty(), now));
            store.markItemUnmatched(
                    unitOfWork, item.id(), Optional.empty(), SecurityContext.require(),
                    now, correlation);
            return;
        }
        UUID decisionId = ids.next();
        store.insertDecision(
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
                        correlation));
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
                        positionAccount(unitOfWork, item)));
    }

    // ------------------------------------------------------------------ plumbing

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

    /** The item's facts with its run's cycle — what the cycle comparison reads (`P8-TSK-017`). */
    private static MatchEngine.ItemFacts facts(
            MatchingStore.RunRow run, MatchingStore.ChunkItem item) {
        return new MatchEngine.ItemFacts(
                item.id(), item.lineType(), item.direction(), item.amount(),
                item.businessDate(), item.settlementDate(), false, run.settlementCycle());
    }

    private UUID positionAccount(Connection unitOfWork, MatchingStore.ChunkItem item) {
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
        return accounts
                .findOperational(unitOfWork, position, item.amount().currency())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart seeds every position per currency"))
                .id()
                .value();
    }

    private Savepoint savepoint(Connection unitOfWork, MatchingStore.ChunkItem item) {
        try {
            return unitOfWork.setSavepoint("item_" + item.lineNo());
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not set the item's savepoint", failure);
        }
    }

    private void rollbackTo(Connection unitOfWork, Savepoint savepoint) {
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not contain the poisoned item", failure);
        }
    }
}
