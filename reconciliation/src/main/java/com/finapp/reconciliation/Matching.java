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
            int sources, int chunks, int decided, int completedRuns, int blockedRuns) {}

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

    /** The sweep: every source with work, each until its eligible run rests or blocks. */
    @SuppressWarnings("try") // Scopes are used for their close side effect.
    public SweepResult sweep() {
        int chunks = 0;
        int decided = 0;
        int completed = 0;
        int blocked = 0;
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
        }
        return new SweepResult(sources.size(), chunks, decided, completed, blocked);
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
        if (run.status() == RunStatus.OPEN) {
            store.markRunInProgress(unitOfWork, run.id(), SecurityContext.require(), now);
        }
        List<MatchingStore.ChunkItem> items =
                store.chunkItems(unitOfWork, run.id(), run.cursor(), config.chunkSize());
        if (items.isEmpty()) {
            completeRun(unitOfWork, run, now, correlation);
            return new ChunkOutcome(ChunkOutcome.Kind.COMPLETED, run.id(), 0);
        }

        int tolerance = rules.settlementDateToleranceDays(unitOfWork, run.ruleSetId());
        // Resolve every item's fired rule and hit ids BEFORE any row lock, then lock
        // expectations and items sorted by id (the §3 order), then re-read the hit facts
        // on the locked rows - the snapshot is the locked truth.
        Map<UUID, Resolution> resolutions = new LinkedHashMap<>();
        Map<UUID, KeyKind> reachedBy = new HashMap<>();
        for (MatchingStore.ChunkItem item : items) {
            Resolution resolution = resolve(unitOfWork, run, item);
            resolutions.put(item.id(), resolution);
            resolution.hitIds().forEach(id -> reachedBy.put(id, resolution.reachedBy()));
        }
        Map<UUID, MatchEngine.HitFacts> lockedHits = new HashMap<>();
        for (MatchEngine.HitFacts hit :
                store.lockExpectations(unitOfWork, reachedBy.keySet(), reachedBy)) {
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
                        claimantRanks, tolerance, parks, offsets, now, correlation);
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
        // THE POSTINGS, LAST (the §3 rule). A chunk posting more than one entry over the
        // platform's shared projection rows pre-locks their union in the projection's own
        // order before its first posting: Suspense.park's internal pre-lock covers only
        // its OWN groups, so a one-park-plus-one-unpark chunk pre-locks here or nowhere.
        if (parks.size() + offsets.size() > 1) {
            preLockPostingUnion(unitOfWork, parks, offsets);
        }
        if (!parks.isEmpty()) {
            suspense.park(
                    unitOfWork,
                    new Suspense.ParkCommand(
                            run.sourceId(),
                            LocalDate.ofInstant(now, ZoneOffset.UTC),
                            parks,
                            SecurityContext.require(),
                            now,
                            correlation));
        }
        // The offsets (`P8-TSK-012`): each unpark is the original park's exact inverse
        // with cause CORRECTION_OFFSET, then the EVIDENCED resolution carrying its entry
        // - the rows after the posting are the transaction's own claims (the D3 shape).
        for (OffsetIntent offset : offsets) {
            applyOffsetPostingAndEvidence(unitOfWork, run, offset, now, correlation);
        }
        return new ChunkOutcome(
                lastChunk ? ChunkOutcome.Kind.COMPLETED : ChunkOutcome.Kind.CHUNKED,
                run.id(),
                decided);
    }

    /** One recorded offset awaiting its posting phase: the locked original's facts. */
    private record OffsetIntent(
            UUID decisionId, CorrectionEngine.ParkedOriginal parked, Money amount) {}

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
            MatchingStore.RunRow run,
            OffsetIntent offset,
            Instant now,
            CorrelationId correlation) {
        Suspense.Unparked unparked =
                suspense.unpark(
                        unitOfWork,
                        offset.parked().suspenseItemId(),
                        offset.amount(),
                        ReleaseCause.CORRECTION_OFFSET,
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
                                run.ruleSetId(),
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
        // currency over the run's CHECKED fee decisions (one currency's minor units -
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

    /** What one item's rules reached: the fired rule, or the facts of why none did. */
    private record Resolution(
            Optional<MatchingRules.RuleRow> firedRule,
            boolean anyLandedRule,
            List<UUID> hitIds,
            KeyKind reachedBy,
            Optional<Integer> waitingGraceHours,
            Optional<MatchingRules.RuleRow> unlandedRule) {}

    private Resolution resolve(
            Connection unitOfWork, MatchingStore.RunRow run, MatchingStore.ChunkItem item) {
        List<MatchingRules.RuleRow> lineRules =
                rules.rulesFor(unitOfWork, run.ruleSetId(), item.lineType());
        boolean anyLanded =
                lineRules.stream()
                        .anyMatch(rule -> rule.cardinality() == Cardinality.ONE_TO_ONE);
        // The CHECK or CORRECTION rule this line type rides when no landed rule serves
        // it (`P8-TSK-012`); GROUP_BY_VALUE_DATE stays `-016`'s, PARTIAL has no v1 rule.
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
            List<UUID> hits =
                    resolveKey(unitOfWork, run.sourceId(), keyKind, value.get());
            if (!hits.isEmpty()) {
                return new Resolution(
                        Optional.of(rule), anyLanded, hits, keyKind, waitingGrace,
                        unlanded);
            }
        }
        return new Resolution(
                Optional.empty(), anyLanded, List.of(), KeyKind.OUR_REF, waitingGrace,
                unlanded);
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
                        fingerprintSeen);
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
        Optional<MatchEngine.FiredRule> fired =
                resolution.firedRule()
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
                                                // This item's hits were reached by ITS
                                                // rule's kind, not another item's.
                                                .map(hit ->
                                                        new MatchEngine.HitFacts(
                                                                hit.expectationId(),
                                                                hit.kind(),
                                                                hit.direction(),
                                                                hit.amount(),
                                                                hit.remainderMinor(),
                                                                hit.openedAt(),
                                                                hit.expectedBy(),
                                                                rule.keyKind().orElseThrow(),
                                                                hit.operationRef()))
                                                .toList()));
        MatchEngine.Verdict verdict =
                MatchEngine.decide(facts, fired, resolution.anyLandedRule(), toleranceDays);

        UUID decisionId = ids.next();
        LocalDate decidedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        switch (verdict.kind()) {
            case ALLOCATE ->
                    applyAllocation(
                            unitOfWork, run, item, fired.orElseThrow(), verdict,
                            decisionId, decidedOn, lockedHits, claimantRanks, parks,
                            toleranceDays, now, correlation);
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
                            Optional.empty(), now, correlation);
            case NO_CANDIDATES ->
                    applyUnreached(
                            unitOfWork, run, item, resolution, decisionId, decidedOn,
                            parks, now, correlation);
            default ->
                    throw new IllegalStateException("unhandled verdict " + verdict.kind());
        }
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
            Instant now,
            CorrelationId correlation) {
        MatchEngine.HitFacts candidate = verdict.candidate().orElseThrow();
        Money allocation = verdict.allocation().orElseThrow();
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
                        DecisionOrigin.RUN,
                        run.ruleSetId(),
                        Optional.of(rule.priority()),
                        Optional.of(rule.cardinality()),
                        Optional.of(candidate.reachedBy()),
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
                        candidate.operationRef()));
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
                                    run, item, BreakType.AMOUNT_MISMATCH,
                                    BreakCause.AMOUNT_DIFFERS,
                                    BreakRegister.Subject.externalItem(item.id()), excess,
                                    Optional.empty(), now));
            parks.add(
                    new Suspense.ParkedItem(
                            item.id(), raised.breakId(), excess,
                            positionAccount(unitOfWork, item)));
        } else {
            store.markItemMatched(
                    unitOfWork, item.id(), allocation.minorUnits(), SecurityContext.require(), now,
                    correlation);
        }
        if (verdict.underRemainder().isPresent()) {
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.AMOUNT_MISMATCH, BreakCause.AMOUNT_DIFFERS,
                            BreakRegister.Subject.expectation(candidate.expectationId()),
                            verdict.underRemainder().get(),
                            Optional.of(candidate.kind()), now));
        }
        if (verdict.timing().isPresent()) {
            breaks.raise(
                    unitOfWork,
                    newBreak(
                            run, item, BreakType.TIMING_DIFFERENCE, BreakCause.LATE_MATCH,
                            BreakRegister.Subject.decision(decisionId),
                            Money.ofPersisted(
                                    0, item.amount().currency(), item.amount().scale()),
                            Optional.empty(), now));
        }
    }

    /**
     * The {@code CHECK} cardinality (`P8-TSK-012`, ADR-0068 §7): the reported fee against
     * round(rate × gross + fixed) under the pinned schedule and the per-line bound —
     * strict, so exactly at the tolerance raises nothing. The item is {@code CHECKED}
     * whatever the verdict; a breach is `FEE_MISMATCH`, commercial, NEVER parked — the
     * value was expensed at acceptance. An unreachable original or an absent schedule
     * prices the expected fee at zero (the design's F1/F2, conservative).
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
        if (originalRef != null) {
            gross =
                    store.expectationsByKey(
                                    unitOfWork, run.sourceId(), KeyKind.PSP_CAPTURE_REF,
                                    originalRef)
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
                                unitOfWork, run.sourceId(), kind, originalRef)) {
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
                offsets.add(new OffsetIntent(decisionId, parked, item.amount()));
            }
            case UNREACHED ->
                    applyUnreached(
                            unitOfWork, run, item, resolution, decisionId, decidedOn,
                            parks, now, correlation);
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
        // any other writer of that break.
        Optional<UUID> explained =
                settles
                        ? store.lockOpenBreakOn(
                                unitOfWork, candidate.expectationId(),
                                BreakType.AMOUNT_MISMATCH)
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
                        Optional.of(candidate.reachedBy()),
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
                                    run, item, BreakType.AMOUNT_MISMATCH,
                                    BreakCause.AMOUNT_DIFFERS,
                                    BreakRegister.Subject.externalItem(item.id()), excess,
                                    Optional.empty(), now));
            parks.add(
                    new Suspense.ParkedItem(
                            item.id(), raised.breakId(), excess,
                            positionAccount(unitOfWork, item)));
        } else {
            store.markItemMatched(
                    unitOfWork, item.id(), allocation.minorUnits(),
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

    private void applyWaiting(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            UUID decisionId,
            LocalDate decidedOn,
            Optional<Integer> graceHours,
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
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        DecisionOutcome.UNMATCHED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        SecurityContext.require(),
                        now,
                        decidedOn,
                        correlation));
        store.markItemUnmatched(
                unitOfWork, item.id(), graceHours, SecurityContext.require(), now, correlation);
    }

    /** No key reached anything: the lookup TYPES the remainder (ADR-0068 §6). */
    private void applyUnreached(
            Connection unitOfWork,
            MatchingStore.RunRow run,
            MatchingStore.ChunkItem item,
            Resolution resolution,
            UUID decisionId,
            LocalDate decidedOn,
            List<Suspense.ParkedItem> parks,
            Instant now,
            CorrelationId correlation) {
        Map<KeyKind, String> lookupKeys = new java.util.EnumMap<>(KeyKind.class);
        item.keys()
                .forEach(
                        (kind, value) ->
                                lookupKeys.put(KeyKind.valueOf(mapToLookup(kind)), value));
        InternalReferenceLookup.InternalReference internal =
                lookup.classify(
                        unitOfWork,
                        new InternalReferenceLookup.LookupSubject(
                                Optional.empty(), lookupKeys));
        if (internal.classification() == InternalClassification.TERMINAL) {
            boolean refund = item.lineType() == ExternalLineType.REFUND;
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
                resolution.waitingGraceHours(), now, correlation);
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
        // PROCESSING_ERROR break the honest record.
        if (item.lineType() == ExternalLineType.PROCESSING_FEE) {
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

    private UUID positionAccount(Connection unitOfWork, MatchingStore.ChunkItem item) {
        return accounts
                .findOperational(
                        unitOfWork, item.positionPurpose(), item.amount().currency())
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
