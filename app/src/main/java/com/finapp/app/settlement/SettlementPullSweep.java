package com.finapp.app.settlement;

import com.finapp.payments.SettlementCycleReads;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.ExpectedArrivals;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementPull;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * One tick of the pull schedule (`P8-TSK-021`, ADR-0066 §1): for every source that declares a
 * pull and has a collector, the keys it owes now — DERIVED from stored rows
 * ({@link ExpectedArrivals}), never a stored schedule — each pulled through {@link SettlementPull}
 * under the windowed permit. Composed here because the scheme's cycles are payments' records,
 * read through payments' own store.
 *
 * <p>Leaderless: ten instances derive the same worklist, and the permit makes the herd one
 * attempt per window; the content unique makes any two that both fetch one file. Each pull runs
 * as the platform in its own correlation, a failing one contained and counted; nothing is held
 * across a provider call.
 */
@Slf4j
public final class SettlementPullSweep {

    /** The cycle tokens one keyset page reads; the tick walks every page. */
    static final int CYCLE_PAGE = 200;

    private final SettlementPull pull;
    private final SettlementSources sources;
    private final SettlementFileStore<Connection> files;
    private final SettlementBatchStore<Connection> batches;
    private final SettlementCycleReads<Connection> cycles;
    private final TransactionRunner transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration window;
    private final int lookbackDays;
    private final Duration cutOff;
    private final Duration cycleLag;

    public SettlementPullSweep(
            SettlementPull pull,
            SettlementSources sources,
            SettlementFileStore<Connection> files,
            SettlementBatchStore<Connection> batches,
            SettlementCycleReads<Connection> cycles,
            TransactionRunner transactions,
            IdGenerator ids,
            Clock clock,
            Duration window,
            int lookbackDays,
            Duration cutOff,
            Duration cycleLag) {
        this.pull = Objects.requireNonNull(pull, "pull must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.files = Objects.requireNonNull(files, "files must not be null");
        this.batches = Objects.requireNonNull(batches, "batches must not be null");
        this.cycles = Objects.requireNonNull(cycles, "cycles must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        this.cutOff = Objects.requireNonNull(cutOff, "cutOff must not be null");
        this.cycleLag = Objects.requireNonNull(cycleLag, "cycleLag must not be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("the pull window must be positive: " + window);
        }
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("the lookback is at least one day: " + lookbackDays);
        }
        this.lookbackDays = lookbackDays;
    }

    /** One tick's tally — telemetry, never the count of record. */
    public record SweepResult(int owed, int pulled, int paced, int failedRows) {}

    /** One tick: every pulled source's owed keys, each through the windowed permit. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        int owed = 0;
        int pulled = 0;
        int paced = 0;
        int failedRows = 0;
        for (SettlementSourceDescriptor source : sources.declared()) {
            if (!pull.pullable(source.code())) {
                continue;
            }
            List<String> keys;
            try {
                keys = owedKeys(source);
            } catch (RuntimeException unreadable) {
                // One source's unreadable worklist never costs another source its pulls.
                failedRows++;
                log.warn(
                        "A settlement pull worklist could not be derived; the next tick"
                                + " retries: {}",
                        unreadable.getClass().getSimpleName());
                continue;
            }
            owed += keys.size();
            for (String key : keys) {
                try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                        CorrelationContext.Scope flow =
                                CorrelationContext.enter(
                                        Correlation.startingWith(CorrelationId.generate(ids)))) {
                    SettlementPull.Outcome outcome =
                            pull.pull(
                                    source.code(),
                                    key,
                                    Optional.of(window),
                                    SecurityContext.require(),
                                    CorrelationContext.current().orElseThrow());
                    if (outcome instanceof SettlementPull.Outcome.Paced) {
                        paced++;
                    } else {
                        pulled++;
                    }
                } catch (RuntimeException failure) {
                    failedRows++;
                    // The class only: a message can name a source's reference or a path.
                    log.warn(
                            "A settlement pull failed; the next tick retries under the permit: {}",
                            failure.getClass().getSimpleName());
                }
            }
        }
        return new SweepResult(owed, pulled, paced, failedRows);
    }

    /** The keys {@code source} owes now, derived from its rows (and payments', for cycles). */
    private List<String> owedKeys(SettlementSourceDescriptor source) {
        Instant now = clock.instant();
        return transactions.inTransaction(
                unitOfWork -> {
                    Optional<SettlementFileStore.SourceRow> row =
                            files.sourceByCode(unitOfWork, source.code());
                    if (row.isEmpty() || !row.get().active()) {
                        return List.<String>of();
                    }
                    if (source.kind() == SourceKind.SCHEME_CYCLE_REPORT) {
                        Instant since = now.minus(Duration.ofDays(lookbackDays));
                        return owedCycles(
                                new CyclePages() {
                                    @Override
                                    public List<SettlementCycleReads.SeenCycle> after(
                                            Optional<String> last) {
                                        return cycles.cyclesSince(
                                                unitOfWork, since, last, CYCLE_PAGE);
                                    }

                                    @Override
                                    public Set<String> accepted(List<String> named) {
                                        return batches.acceptedBatchRefs(
                                                unitOfWork, row.get().id(), named);
                                    }
                                },
                                now,
                                cycleLag,
                                CYCLE_PAGE);
                    }
                    Set<LocalDate> accepted =
                            batches.acceptedBusinessDates(
                                    unitOfWork,
                                    row.get().id(),
                                    ExpectedArrivals.windowStart(now, lookbackDays),
                                    LocalDate.ofInstant(now, ZoneOffset.UTC));
                    return ExpectedArrivals.daily(now, lookbackDays, cutOff, accepted);
                });
    }

    /** The cycle worklist's two reads: a keyset page, and the received refs among tokens. */
    interface CyclePages {
        List<SettlementCycleReads.SeenCycle> after(Optional<String> last);

        Set<String> accepted(List<String> named);
    }

    /**
     * Every owed cycle, walking EVERY keyset page: a fixed first page would starve each later
     * cycle behind tokens already received — the walk ends at the first short page.
     */
    static List<String> owedCycles(
            CyclePages pages, Instant now, Duration lag, int pageSize) {
        List<String> owed = new ArrayList<>();
        Optional<String> after = Optional.empty();
        while (true) {
            List<SettlementCycleReads.SeenCycle> page = pages.after(after);
            if (!page.isEmpty()) {
                List<ExpectedArrivals.ReferencedCycle> referenced =
                        page.stream()
                                .map(seen -> new ExpectedArrivals.ReferencedCycle(
                                        seen.cycle(), seen.firstSeen()))
                                .toList();
                Set<String> accepted =
                        pages.accepted(
                                referenced.stream()
                                        .map(ExpectedArrivals.ReferencedCycle::cycle)
                                        .toList());
                owed.addAll(ExpectedArrivals.cycles(referenced, now, lag, accepted));
            }
            if (page.size() < pageSize) {
                return List.copyOf(owed);
            }
            after = Optional.of(page.get(page.size() - 1).cycle());
        }
    }
}
