package com.finapp.app.telemetry;

import com.finapp.platform.telemetry.Spans;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.DecisionOutcome;
import com.finapp.reconciliation.ReconciliationTelemetry;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionOutcome;
import com.finapp.reconciliation.Severity;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reconciliation's counters and timers (`P8-TSK-024`, `PHASE_8_PLAN.md` §15) - the
 * {@code CommittedRailOutcomes} shape: reconciliation reports each fact from inside the
 * transaction that makes it true, and it is counted only {@code afterCommit}, so a rolled-back
 * chunk, rematch or decision is never published; a meter never fails the committed operation.
 * The one exception is a stale approval's refusal, counted at once because nothing commits.
 *
 * <p>Counts, kinds and durations only - never an amount (ADR-0072). Every series is eager:
 * per declared source and outcome, per resolution kind and outcome, per type and severity. A
 * source is reported by its reconciliation id and published by its compiled code; an id the
 * register does not name is not counted (no tag value is ever invented).
 */
@Slf4j
public final class ReconciliationOutcomeMeters implements ReconciliationTelemetry {

    public static final String ITEM = "finapp.reconciliation.item";
    public static final String REMATCH = "finapp.reconciliation.rematch";
    public static final String RUN_LATENCY = "finapp.reconciliation.run.latency";
    public static final String BREAK_RAISED = "finapp.reconciliation.break.raised";
    public static final String RESOLUTION = "finapp.reconciliation.resolution";
    public static final String RESOLUTION_LATENCY = "finapp.reconciliation.resolution.latency";
    public static final String ADJUSTMENT = "finapp.reconciliation.adjustment";

    private final MeterRegistry registry;
    private final Function<UUID, Optional<String>> sourceCodes;
    private final Spans spans;

    /**
     * @param sourceCodes a reconciliation source id onto its declared code - the seeded
     *     register's literal ids, read by the composition
     */
    public ReconciliationOutcomeMeters(
            MeterRegistry registry,
            SettlementSources sources,
            Function<UUID, Optional<String>> sourceCodes,
            Spans spans) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sourceCodes = Objects.requireNonNull(sourceCodes, "sourceCodes must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
        for (SettlementSourceDescriptor source : sources.declared()) {
            for (DecisionOutcome outcome : DecisionOutcome.values()) {
                item(source.code(), outcome);
                rematch(source.code(), outcome);
            }
            runLatency(source.code());
        }
        for (BreakType type : BreakType.values()) {
            for (Severity severity : Severity.values()) {
                raised(type, severity);
            }
        }
        for (ResolutionKind kind : ResolutionKind.values()) {
            for (ResolutionOutcome outcome : ResolutionOutcome.values()) {
                resolution(kind, outcome);
            }
            resolutionLatency(kind);
            if (kind.postsAdjustment()) {
                adjustment(kind);
            }
        }
    }

    // ------------------------------------------------------------------ the port

    @Override
    public void runCompleted(
            UUID sourceId, Map<DecisionOutcome, Long> outcomes, Optional<Duration> sinceBirth) {
        afterCommit(
                () -> sourceCodes.apply(sourceId).ifPresent(code -> {
                    outcomes.forEach((outcome, count) -> item(code, outcome).increment(count));
                    sinceBirth.ifPresent(age -> runLatency(code).record(age));
                }));
    }

    @Override
    public void rematched(UUID sourceId, DecisionOutcome outcome) {
        afterCommit(
                () -> sourceCodes.apply(sourceId)
                        .ifPresent(code -> rematch(code, outcome).increment()));
    }

    @Override
    public void resolved(
            ResolutionKind kind, ResolutionOutcome outcome, Optional<Duration> sinceRaised) {
        afterCommit(() -> {
            resolution(kind, outcome).increment();
            sinceRaised.ifPresent(age -> resolutionLatency(kind).record(age));
        });
    }

    @Override
    public void adjusted(ResolutionKind kind) {
        afterCommit(() -> adjustment(kind).increment());
    }

    @Override
    public void staleRefused(ResolutionKind kind) {
        safely(() -> resolution(kind, ResolutionOutcome.STALE).increment());
    }

    @Override
    public Spans spans() {
        return spans;
    }

    /** A break raised (a created raise, never a converged one) - {@code MeteredBreakRegister}. */
    public void countRaised(BreakType type, Severity severity) {
        afterCommit(() -> raised(type, severity).increment());
    }

    // ------------------------------------------------------------------ the series

    private Counter item(String sourceCode, DecisionOutcome outcome) {
        return Counter.builder(ITEM)
                .tag("source", sourceCode)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description(
                        "Settlement items a completed run decided, by source and outcome - the"
                                + " match rate, counted when the run's completion commits")
                .register(registry);
    }

    private Counter rematch(String sourceCode, DecisionOutcome outcome) {
        return Counter.builder(REMATCH)
                .tag("source", sourceCode)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description(
                        "Items a late leg decided again - the rematch and the controller's"
                                + " reprocess runs - by source and outcome")
                .register(registry);
    }

    private Timer runLatency(String sourceCode) {
        return Timer.builder(RUN_LATENCY)
                .tag("source", sourceCode)
                .description("A reconciliation run's age at completion, from its birth at"
                        + " acceptance")
                .register(registry);
    }

    private Counter raised(BreakType type, Severity severity) {
        return Counter.builder(BREAK_RAISED)
                .tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("severity", severity.name().toLowerCase(Locale.ROOT))
                .description("Reconciliation breaks raised, by type and severity - break volume")
                .register(registry);
    }

    private Counter resolution(ResolutionKind kind, ResolutionOutcome outcome) {
        return Counter.builder(RESOLUTION)
                .tag("type", kind.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description(
                        "Resolutions by kind and outcome - approved, rejected, withdrawn,"
                                + " evidenced, and approvals refused stale")
                .register(registry);
    }

    private Timer resolutionLatency(ResolutionKind kind) {
        return Timer.builder(RESOLUTION_LATENCY)
                .tag("type", kind.name().toLowerCase(Locale.ROOT))
                .description("A break's age when its resolution closed it - raised to resolved")
                .register(registry);
    }

    private Counter adjustment(ResolutionKind kind) {
        return Counter.builder(ADJUSTMENT)
                .tag("type", kind.name().toLowerCase(Locale.ROOT))
                .description(
                        "Ledger adjustments a resolution's approval posted, by kind - counted,"
                                + " never an amount (the adjustments are the ledger's)")
                .register(registry);
    }

    // ------------------------------------------------------------------ plumbing

    /** The transaction-bound resource key of the deferred counts. */
    private static final String PENDING = ReconciliationOutcomeMeters.class.getName() + ".pending";

    private static void afterCommit(Runnable count) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(count);
            return;
        }
        pending().add(count);
    }

    /**
     * The current transaction's deferred counts, bound once beside the one synchronization that
     * runs them after commit - a list, not a synchronization per count, so a savepoint's mark
     * can drop the counts deferred after it ({@link #discardCountsAfter}). Suspended and resumed
     * with its transaction, so an inner transaction keeps its own.
     */
    @SuppressWarnings("unchecked")
    private static List<Runnable> pending() {
        Object bound = TransactionSynchronizationManager.getResource(PENDING);
        if (bound != null) {
            return (List<Runnable>) bound;
        }
        List<Runnable> counts = new ArrayList<>();
        TransactionSynchronizationManager.bindResource(PENDING, counts);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void suspend() {
                        TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                    }

                    @Override
                    public void resume() {
                        TransactionSynchronizationManager.bindResource(PENDING, counts);
                    }

                    @Override
                    public void afterCommit() {
                        counts.forEach(ReconciliationOutcomeMeters::safely);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                    }
                });
        return counts;
    }

    @Override
    public int countMark() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return 0;
        }
        Object bound = TransactionSynchronizationManager.getResource(PENDING);
        return bound instanceof List<?> counts ? counts.size() : 0;
    }

    @Override
    public void discardCountsAfter(int mark) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Object bound = TransactionSynchronizationManager.getResource(PENDING);
        if (bound instanceof List<?> counts && mark >= 0 && mark < counts.size()) {
            counts.subList(mark, counts.size()).clear();
        }
    }

    private static void safely(Runnable count) {
        try {
            count.run();
        } catch (RuntimeException meterFailed) {
            log.warn(
                    "A committed reconciliation fact could not be counted; the rows stand and"
                            + " only the meter is short: {}",
                    meterFailed.getClass().getSimpleName());
        }
    }
}
