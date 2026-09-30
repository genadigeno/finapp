package com.finapp.app.telemetry;

import com.finapp.app.reconciliation.PositionProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The reconciliation verdict gauges (`P8-TSK-007`, ADR-0067 §9, `PHASE_8_PLAN.md` §15):
 * {@code finapp.reconciliation.position.proof} and {@code .line.unattributed} per
 * {@code purpose} — <strong>both must read 0, both alerted</strong> — and
 * {@code .expectation.open} per {@code source}.
 *
 * <h2>Counts, never amounts</h2>
 *
 * <p>The proof series counts <em>currencies failing the identity</em>, and the completeness
 * series counts <em>lines nobody explains</em> (ADR-0072: a difference's size is the
 * report's to show, on the record — a metric carrying it would put amounts in every
 * scrape). {@code NaN} when the sweep is unreadable, never a comforting zero
 * (`P1-TSK-029`); eager per purpose and per declared source; fleet-wide per instance over
 * the shared database behind the floor, aggregated with {@code max()}, never {@code sum()}
 * — the {@code SettlementFileMetrics} shape, verbatim, and like it this class appears in no
 * {@code DISTRIBUTED_EXECUTION.md} §3 register: it decides nothing.
 *
 * <p>The sweep runs in one {@code REPEATABLE READ} transaction, so each reading's two sides
 * are one snapshot ({@code INV-ACC-01}'s discipline beside the trial balance).
 */
@Slf4j
public final class ReconciliationMetrics {

    /** {@code finapp.reconciliation.position.proof} — currencies failing, per purpose. */
    public static final String PROOF = "finapp.reconciliation.position.proof";

    /** {@code finapp.reconciliation.line.unattributed} — unexplained lines, per purpose. */
    public static final String UNATTRIBUTED = "finapp.reconciliation.line.unattributed";

    /** {@code finapp.reconciliation.expectation.open} — open expectations, per source. */
    public static final String OPEN = "finapp.reconciliation.expectation.open";

    /** {@code finapp.reconciliation.suspense.open} — items with a remainder (`P8-TSK-010`). */
    public static final String SUSPENSE_OPEN = "finapp.reconciliation.suspense.open";

    /** {@code finapp.reconciliation.suspense.age} — the oldest open item, in seconds. */
    public static final String SUSPENSE_AGE = "finapp.reconciliation.suspense.age";

    /** {@code finapp.reconciliation.suspense.unowned} — items no open break answers for. */
    public static final String SUSPENSE_UNOWNED = "finapp.reconciliation.suspense.unowned";

    /** {@code finapp.reconciliation.run.pending} — runs still owed work (`P8-TSK-011`). */
    public static final String RUN_PENDING = "finapp.reconciliation.run.pending";

    /** {@code finapp.reconciliation.run.age} — the oldest pending run, in seconds. */
    public static final String RUN_AGE = "finapp.reconciliation.run.age";

    /** {@code finapp.reconciliation.run.blocked} — runs a person must requeue. */
    public static final String RUN_BLOCKED = "finapp.reconciliation.run.blocked";

    /** {@code finapp.reconciliation.expectation.overdue} — aged gaps (`P8-TSK-013`). */
    public static final String OVERDUE = "finapp.reconciliation.expectation.overdue";

    /** {@code finapp.reconciliation.expectation.overdue.age} — the oldest, in seconds. */
    public static final String OVERDUE_AGE =
            "finapp.reconciliation.expectation.overdue.age";

    /** {@code finapp.reconciliation.item.unmatched} — items waiting inside grace. */
    public static final String ITEM_UNMATCHED = "finapp.reconciliation.item.unmatched";

    /**
     * {@code finapp.reconciliation.cash.proof} — per currency, 1 when {@code CASH_AT_BANK} is
     * not the head closing of an unbroken statement chain, else 0 (`P8-TSK-016`).
     */
    public static final String CASH_PROOF = "finapp.reconciliation.cash.proof";

    /** The floor: the sweep folds the open register, so it is dearer than a GROUP BY. */
    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The {@code LedgerMetrics.Connections} seam, for the same unit-testability reason. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final PositionProof proof;
    private final com.finapp.reconciliation.RunReadings runReadings;
    private final com.finapp.settlement.SettlementFileStore<Connection> sourceRows;
    private final SettlementSources sources;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(Cached.empty());
    private final AtomicReference<CachedRuns> cachedRuns =
            new AtomicReference<>(CachedRuns.empty());

    public ReconciliationMetrics(
            PositionProof proof,
            com.finapp.reconciliation.RunReadings runReadings,
            com.finapp.settlement.SettlementFileStore<Connection> sourceRows,
            SettlementSources sources,
            Connections connections,
            Clock clock,
            MeterRegistry registry) {
        this.proof = proof;
        this.runReadings = runReadings;
        this.sourceRows = sourceRows;
        this.sources = sources;
        this.connections = connections;
        this.clock = clock;

        for (AccountPurpose purpose : PositionProof.PROVEN) {
            Gauge.builder(PROOF, this, self -> self.proofOf(purpose))
                    .tag("purpose", purpose.name())
                    .description(
                            "Currencies of this clearing position failing the identity"
                                    + " DR-CR = signed open expectation remainders"
                                    + " (INV-REC-06). MUST read 0 and is alerted; a count,"
                                    + " never an amount - the difference's size is the"
                                    + " positions report's, on the record. NaN when"
                                    + " unreadable, never zero. Fleet-wide from every"
                                    + " instance: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            Gauge.builder(UNATTRIBUTED, this, self -> self.unattributedOf(purpose))
                    .tag("purpose", purpose.name())
                    .description(
                            "Journal lines on this reconciled position that no expectation"
                                    + " explains (ADR-0067 section 9: every-writer"
                                    + " detection - a raw-SQL poster or a missed opener"
                                    + " lands here). MUST read 0 on the clearing purposes"
                                    + " and is alerted; SUSPENSE_UNMATCHED truthfully"
                                    + " counts Phase 7's parking lines until P8-TSK-020"
                                    + " adopts them. NaN when unreadable, never zero."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        // The suspense proof publishes through the SAME proof series under its own purpose
        // (P8-TSK-010, ADR-0070 §7 - the observability table carries no separate series,
        // confirmed at design): CR-DR = CREDIT - DEBIT remainders + the named Phase 7 term.
        Gauge.builder(
                        PROOF,
                        this,
                        self -> self.proofOf(AccountPurpose.SUSPENSE_UNMATCHED))
                .tag("purpose", AccountPurpose.SUSPENSE_UNMATCHED.name())
                .description(
                        "Currencies of SUSPENSE_UNMATCHED failing the suspense identity"
                                + " CR-DR = CREDIT - DEBIT item remainders + Phase 7"
                                + " parkings not yet adopted (ADR-0070 section 7). MUST"
                                + " read 0 and is alerted; a count, never an amount. NaN"
                                + " when unreadable, never zero. Fleet-wide: aggregate"
                                + " with max(), never sum()")
                .strongReference(true)
                .register(registry);
        // The cash proof (P8-TSK-016, INV-SET-06): eager per supported currency.
        for (com.finapp.sharedkernel.money.CurrencyCode currency :
                com.finapp.ledger.SupportedCurrencies.ALL) {
            Gauge.builder(CASH_PROOF, this, self -> self.cashProofOf(currency))
                    .tag("currency", currency.code())
                    .description(
                            "1 when the cash-at-bank balance in this currency is not the"
                                    + " closing balance"
                                    + " at the head of an unbroken chain of accepted bank"
                                    + " statements (INV-SET-06: sequence 1 opens at zero,"
                                    + " every opening its predecessor's closing, no gap), else"
                                    + " 0. MUST read 0 and is alerted; a verdict, never an"
                                    + " amount - the difference is the positions report's."
                                    + " NaN when unreadable, never zero. Fleet-wide: aggregate"
                                    + " with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        Gauge.builder(SUSPENSE_OPEN, this, ReconciliationMetrics::suspenseOpen)
                .description(
                        "Suspense items still holding a remainder (P8-TSK-010, ADR-0070"
                                + " section 5). A count, never an amount - the amounts are"
                                + " the audited suspense report's. NaN when unreadable,"
                                + " never zero. Fleet-wide: aggregate with max(), never"
                                + " sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(SUSPENSE_AGE, this, ReconciliationMetrics::suspenseAgeSeconds)
                .baseUnit("seconds")
                .description(
                        "Age of the oldest suspense item with a remainder, from its stored"
                                + " opened_on (ADR-0070 section 5: never a permanent"
                                + " resting place - INV-REC-05). 0 when none is open; NaN"
                                + " when unreadable. Fleet-wide: aggregate with max(),"
                                + " never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(SUSPENSE_UNOWNED, this, ReconciliationMetrics::suspenseUnowned)
                .description(
                        "Suspense items with a remainder that no open break answers for"
                                + " (INV-REC-09's detector: break_id NOT NULL is the"
                                + " structural half, this counts a break closed over value"
                                + " it still owns). MUST read 0 and alerts above it. NaN"
                                + " when unreadable, never zero. Fleet-wide: aggregate"
                                + " with max(), never sum()")
                .strongReference(true)
                .register(registry);
        for (SettlementSourceDescriptor source : sources.declared()) {
            String code = source.code();
            Gauge.builder(RUN_PENDING, this, self -> self.runPending(code))
                    .tag("source", code)
                    .description(
                            "Reconciliation runs of this source still owed work - OPEN or"
                                    + " IN_PROGRESS (P8-TSK-011, ADR-0068 section 4). A"
                                    + " count, never an amount. NaN when unreadable, never"
                                    + " zero. Fleet-wide: aggregate with max(), never"
                                    + " sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(RUN_AGE, this, self -> self.runAgeSeconds(code))
                    .tag("source", code)
                    .baseUnit("seconds")
                    .description(
                            "Age of this source's oldest run still owed work, from its"
                                    + " stored created_at (P8-TSK-011:"
                                    + " evidence-to-disposition latency). 0 when none is"
                                    + " pending; NaN when unreadable. Fleet-wide:"
                                    + " aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(RUN_BLOCKED, this, self -> self.runBlocked(code))
                    .tag("source", code)
                    .description(
                            "This source's runs BLOCKED after consecutive chunk failures,"
                                    + " holding the source until a person requeues"
                                    + " (P8-TSK-011; the requeue arrives with P8-TSK-014)."
                                    + " MUST read 0 and alerts above it. NaN when"
                                    + " unreadable, never zero. Fleet-wide: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(OVERDUE, this, self -> self.overdue(code))
                    .tag("source", code)
                    .description(
                            "This source's expectations past their pinned window with no"
                                    + " settling evidence (P8-TSK-013, INV-SET-02 with an"
                                    + " age bound) - each owns a MISSING_EXTERNAL break."
                                    + " Alerted. A count, never an amount. NaN when"
                                    + " unreadable, never zero. Fleet-wide: aggregate"
                                    + " with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(OVERDUE_AGE, this, self -> self.overdueAgeSeconds(code))
                    .tag("source", code)
                    .baseUnit("seconds")
                    .description(
                            "Age of this source's oldest overdue expectation, from its"
                                    + " stored overdue_since (P8-TSK-013). 0 when none;"
                                    + " NaN when unreadable. Fleet-wide: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(ITEM_UNMATCHED, this, self -> self.itemsUnmatched(code))
                    .tag("source", code)
                    .description(
                            "This source's items waiting inside their grace window"
                                    + " (P8-TSK-013): late internal evidence is normal,"
                                    + " and the window bounds it. A count, never an"
                                    + " amount. NaN when unreadable, never zero."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
        for (SettlementSourceDescriptor source : sources.declared()) {
            String code = source.code();
            Gauge.builder(OPEN, this, self -> self.openOf(code))
                    .tag("source", code)
                    .description(
                            "Open settlement expectations tracked against this source -"
                                    + " the population ageing (P8-TSK-013) will judge."
                                    + " A count, never an amount. NaN when unreadable."
                                    + " Fleet-wide: aggregate with max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double cashProofOf(com.finapp.sharedkernel.money.CurrencyCode currency) {
        return reading()
                .report()
                .flatMap(report -> report.cashOf(currency))
                .map(verdict -> verdict.explained() ? 0.0 : 1.0)
                .orElse(Double.NaN);
    }

    private double proofOf(AccountPurpose purpose) {
        return reading()
                .report()
                .map(report -> (double) report.currenciesFailing(purpose))
                .orElse(Double.NaN);
    }

    private double unattributedOf(AccountPurpose purpose) {
        return reading()
                .report()
                .map(
                        report ->
                                (double)
                                        report.unattributedByPurpose()
                                                .getOrDefault(purpose, 0L))
                .orElse(Double.NaN);
    }

    private double openOf(String sourceCode) {
        return reading()
                .report()
                .map(
                        report ->
                                (double)
                                        report.openBySourceCode()
                                                .getOrDefault(sourceCode, 0L))
                .orElse(Double.NaN);
    }

    private double suspenseOpen() {
        return reading()
                .report()
                .map(report -> (double) report.suspenseOpenItems())
                .orElse(Double.NaN);
    }

    private double suspenseAgeSeconds() {
        return reading()
                .report()
                .map(
                        report ->
                                report.oldestSuspenseOpenedOn()
                                        .map(
                                                oldest ->
                                                        (double)
                                                                Duration.between(
                                                                                oldest.atStartOfDay(
                                                                                                java.time.ZoneOffset
                                                                                                        .UTC)
                                                                                        .toInstant(),
                                                                                clock.instant())
                                                                        .getSeconds())
                                        .orElse(0.0d))
                .orElse(Double.NaN);
    }

    private double suspenseUnowned() {
        return reading()
                .report()
                .map(report -> (double) report.suspenseUnowned())
                .orElse(Double.NaN);
    }

    private double runPending(String code) {
        return runReading()
                .counters()
                .map(byCode -> (double) counterOf(byCode, code).pending())
                .orElse(Double.NaN);
    }

    private double runAgeSeconds(String code) {
        return runReading()
                .counters()
                .map(
                        byCode ->
                                counterOf(byCode, code)
                                        .oldestCreatedAt()
                                        .map(
                                                oldest ->
                                                        (double)
                                                                Duration.between(
                                                                                oldest,
                                                                                clock
                                                                                    .instant())
                                                                        .getSeconds())
                                        .orElse(0.0d))
                .orElse(Double.NaN);
    }

    private double runBlocked(String code) {
        return runReading()
                .counters()
                .map(byCode -> (double) counterOf(byCode, code).blocked())
                .orElse(Double.NaN);
    }

    private double overdue(String code) {
        return runReading()
                .counters()
                .map(byCode -> (double) counterOf(byCode, code).overdue())
                .orElse(Double.NaN);
    }

    private double overdueAgeSeconds(String code) {
        return runReading()
                .counters()
                .map(
                        byCode ->
                                counterOf(byCode, code)
                                        .oldestOverdue()
                                        .map(
                                                oldest ->
                                                        (double)
                                                                Duration.between(
                                                                                oldest,
                                                                                clock
                                                                                    .instant())
                                                                        .getSeconds())
                                        .orElse(0.0d))
                .orElse(Double.NaN);
    }

    private double itemsUnmatched(String code) {
        return runReading()
                .counters()
                .map(byCode -> (double) counterOf(byCode, code).unmatched())
                .orElse(Double.NaN);
    }

    private static RunCounters counterOf(
            java.util.Map<String, RunCounters> byCode, String code) {
        return byCode.getOrDefault(
                code, new RunCounters(0, Optional.empty(), 0, 0, Optional.empty(), 0));
    }

    /** The run counters' own small read — three bounded counts, never the proof's fold. */
    private CachedRuns runReading() {
        CachedRuns current = cachedRuns.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        CachedRuns fresh;
        try (Connection connection = connections.open()) {
            java.util.Map<java.util.UUID, Long> pending =
                    runReadings.pendingCountBySource(connection);
            java.util.Map<java.util.UUID, Instant> oldest =
                    runReadings.oldestPendingBySource(connection);
            java.util.Map<java.util.UUID, Long> blocked =
                    runReadings.blockedCountBySource(connection);
            java.util.Map<java.util.UUID, Long> overdue =
                    runReadings.overdueCountBySource(connection);
            java.util.Map<java.util.UUID, Instant> oldestOverdue =
                    runReadings.oldestOverdueBySource(connection);
            java.util.Map<java.util.UUID, Long> unmatched =
                    runReadings.unmatchedCountBySource(connection);
            // The declared codes onto the reconciliation rows' source ids - the
            // expectation.open gauge's mapping (P8-TSK-007), read the same way.
            java.util.Map<String, RunCounters> byCode = new java.util.LinkedHashMap<>();
            for (SettlementSourceDescriptor declared : sources.declared()) {
                java.util.Optional<java.util.UUID> id =
                        sourceRows
                                .sourceByCode(connection, declared.code())
                                .map(row -> row.id());
                byCode.put(
                        declared.code(),
                        new RunCounters(
                                id.map(key -> pending.getOrDefault(key, 0L)).orElse(0L),
                                id.map(oldest::get).map(Optional::ofNullable).orElse(
                                        Optional.empty()),
                                id.map(key -> blocked.getOrDefault(key, 0L)).orElse(0L),
                                id.map(key -> overdue.getOrDefault(key, 0L)).orElse(0L),
                                id.map(oldestOverdue::get)
                                        .map(Optional::ofNullable)
                                        .orElse(Optional.empty()),
                                id.map(key -> unmatched.getOrDefault(key, 0L))
                                        .orElse(0L)));
            }
            fresh = new CachedRuns(clock.instant(), Optional.of(java.util.Map.copyOf(byCode)));
        } catch (Exception unreadable) {
            log.warn(
                    "Could not read the run counters; the gauges report absent rather"
                            + " than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new CachedRuns(clock.instant(), Optional.empty());
        }
        cachedRuns.set(fresh);
        return fresh;
    }

    private Cached reading() {
        Cached current = cached.get();
        if (!current.isStaleAt(clock.instant())) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                fresh = new Cached(clock.instant(), Optional.of(proof.sweep(connection)));
                connection.commit();
            } catch (Exception mid) {
                connection.rollback();
                throw mid;
            }
        } catch (Exception unreadable) {
            // Never the exception's message at INFO and never an identifier (INV-AUD-02).
            log.warn(
                    "Could not sweep the position proof; the gauges report absent rather"
                            + " than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private record RunCounters(
            long pending,
            Optional<Instant> oldestCreatedAt,
            long blocked,
            long overdue,
            Optional<Instant> oldestOverdue,
            long unmatched) {}

    /** The run counters per declared code — per instance, deciding nothing. */
    private record CachedRuns(
            Instant takenAt, Optional<java.util.Map<String, RunCounters>> counters) {

        static CachedRuns empty() {
            return new CachedRuns(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }

    /** One sweep and when it was taken — per instance, deciding nothing. */
    private record Cached(Instant takenAt, Optional<PositionProof.Report> report) {

        static Cached empty() {
            return new Cached(Instant.EPOCH, Optional.empty());
        }

        boolean isStaleAt(Instant now) {
            return takenAt.plus(MIN_REFRESH).isBefore(now);
        }
    }
}
