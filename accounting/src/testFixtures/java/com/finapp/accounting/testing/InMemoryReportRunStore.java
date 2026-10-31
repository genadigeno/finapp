package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunFailure;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * {@link ReportRunStore} in memory (`P14-TSK-008`): the hermetic suites' store, holding the ranks of {@code accounting
 * V004} the machine relies on - the plan's key unique, the conditional edges (the reclaim only past the lease on the
 * "database" clock, the completion and the failure only from {@code GENERATING} at the attempt), the hash recomputed
 * from the content, the content frozen, a FINAL completion only under the period's present lock - so a defect the
 * database would refuse is refused here too, as an {@link IllegalStateException} naming the rank. Safe for the suites'
 * racing threads the way the database is for its connections: the key is won by one atomic {@code putIfAbsent}, every
 * move is one atomic {@code compute} of the row - no monitor and no lock (ADR-0014's rule binds test fixtures too). The
 * real ranks are V004's, proven by {@code ReportRunDatabaseTest} (owed, C5).
 */
public final class InMemoryReportRunStore implements ReportRunStore {

    private final Clock clock;
    private final AccountingPeriodStore periods;
    /** The rows by the plan's key - key and revision - the one arbiter of a birth. */
    private final ConcurrentHashMap<String, RunRow> byKey = new ConcurrentHashMap<>();
    /** Each born run's key, written before the row can be seen. */
    private final ConcurrentHashMap<ReportRunId, String> keyOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReportRunId, Content> contents = new ConcurrentHashMap<>();
    private final AtomicInteger completions = new AtomicInteger();

    /**
     * @param clock the "database" clock - every stamp and lease
     * @param periods the periods, for the FINAL completion's lock re-check (the edge trigger's rank)
     */
    public InMemoryReportRunStore(Clock clock, AccountingPeriodStore periods) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.periods = Objects.requireNonNull(periods, "periods");
    }

    /** Every run, in claim order. */
    public List<RunRow> all() {
        return byKey.values().stream().sorted(Comparator.comparing(RunRow::claimedAt)
                .thenComparing(row -> row.id().value())).toList();
    }

    /** How many completion edges were made - one per run, whatever raced. */
    public int completions() {
        return completions.get();
    }

    /** A test's tampering: the content overwritten in place - the freeze's rank refuses it. */
    public void overwriteContentForTest(ReportRunId id, String content) {
        if (contents.putIfAbsent(id, new Content(content, CanonicalJson.sha256(content))) != null) {
            throw new IllegalStateException("report_run_content_is_frozen");
        }
    }

    @Override
    public Optional<RunRow> latest(Connection unitOfWork, RunKey key) {
        return byKey.values().stream().filter(row -> row.key().equals(key))
                .max(Comparator.comparingInt(RunRow::revision));
    }

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewRun run) {
        if ((run.basis() == RunBasis.FINAL) != run.periodLockedAt().isPresent()) {
            throw new IllegalStateException("report_run_basis_coherent");
        }
        Instant now = clock.instant();
        String key = key(run.key(), run.revision());
        RunRow row = new RunRow(run.id(), run.key(), run.revision(), run.period(), run.periodRevision(),
                run.parameters(), run.basis(), RunStatus.GENERATING, run.periodLockedAt(), run.mappingVersionId(),
                run.mappingVersion(), now, run.requestedBy().id(), run.requestedBy().type().name(), now, 1,
                now.plus(run.lease()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                run.correlation());
        keyOf.put(run.id(), key);
        if (byKey.putIfAbsent(key, row) != null) {
            keyOf.remove(run.id());
            return false;
        }
        return true;
    }

    @Override
    public Optional<RunRow> at(Connection unitOfWork, RunKey key, int revision) {
        return Optional.ofNullable(byKey.get(key(key, revision)));
    }

    @Override
    public Optional<RunRow> find(Connection unitOfWork, ReportRunId id) {
        String key = keyOf.get(id);
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    @Override
    public Optional<Content> content(Connection unitOfWork, ReportRunId id) {
        return Optional.ofNullable(contents.get(id));
    }

    @Override
    public boolean reclaim(Connection unitOfWork, ReportRunId id, int attempt, Duration lease) {
        return move(id, row -> row.status() != RunStatus.GENERATING || row.attempt() != attempt
                || row.leaseUntil().isAfter(clock.instant())
                ? row
                : with(row, RunStatus.GENERATING, attempt + 1, clock.instant().plus(lease), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Override
    public boolean pastLease(Connection unitOfWork, ReportRunId id) {
        return find(unitOfWork, id)
                .filter(row -> row.status() == RunStatus.GENERATING && !row.leaseUntil().isAfter(clock.instant()))
                .isPresent();
    }

    @Override
    public List<RunRow> expired(Connection unitOfWork, int limit) {
        return byKey.values().stream()
                .filter(row -> row.status() == RunStatus.GENERATING && !row.leaseUntil().isAfter(clock.instant()))
                .sorted(Comparator.comparing(RunRow::leaseUntil)).limit(limit).toList();
    }

    @Override
    public boolean complete(
            Connection unitOfWork, ReportRunId id, int attempt, RunStatus status, String content, String sha256) {
        return move(id, row -> {
            if (row.status() != RunStatus.GENERATING || row.attempt() != attempt) {
                return row;
            }
            if (status != row.basis().completed()) {
                throw new IllegalStateException("report_run_permits_only_machine_edges: completes as its basis");
            }
            if (!CanonicalJson.sha256(content).equals(sha256)) {
                throw new IllegalStateException("report_run_content_hashed");
            }
            if (row.basis() == RunBasis.FINAL) {
                AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, row.period()).orElseThrow();
                if (period.status() == PeriodStatus.OPEN || !period.lockedAt().equals(row.periodLockedAt())) {
                    throw new IllegalStateException(
                            "report_run_permits_only_machine_edges: under the lock it was claimed");
                }
            }
            if (contents.putIfAbsent(id, new Content(content, sha256)) != null) {
                throw new IllegalStateException("report_run_content_is_frozen");
            }
            completions.incrementAndGet();
            return with(row, status, attempt, row.leaseUntil(), Optional.of(sha256), Optional.of(clock.instant()),
                    Optional.empty(), Optional.empty());
        });
    }

    @Override
    public boolean fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure) {
        return move(id, row -> row.status() != RunStatus.GENERATING || row.attempt() != attempt
                ? row
                : with(row, RunStatus.FAILED, attempt, row.leaseUntil(), Optional.empty(), Optional.empty(),
                        Optional.of(failure), Optional.empty()));
    }

    @Override
    public int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, CorrelationId correlation) {
        int superseded = 0;
        for (RunRow candidate : List.copyOf(byKey.values())) {
            if (candidate.periodId().equals(period) && move(candidate.id(), row -> row.status() != RunStatus.FINAL
                    ? row
                    : with(row, RunStatus.SUPERSEDED, row.attempt(), row.leaseUntil(), row.contentSha256(),
                            row.completedAt(), Optional.empty(), Optional.of(clock.instant())))) {
                superseded++;
            }
        }
        return superseded;
    }

    @Override
    public boolean cutWithin(Connection unitOfWork, ReportRunId id, Duration window) {
        return find(unitOfWork, id).filter(row -> row.cutAt().isAfter(clock.instant().minus(window))).isPresent();
    }

    // -----------------------------------------------------------------

    /** One atomic move of the row; {@code true} when {@code edge} answered a different row. */
    private boolean move(ReportRunId id, UnaryOperator<RunRow> edge) {
        String key = keyOf.get(id);
        if (key == null) {
            return false;
        }
        AtomicBoolean moved = new AtomicBoolean();
        byKey.computeIfPresent(key, (ignored, row) -> {
            RunRow next = edge.apply(row);
            moved.set(next != row);
            return next;
        });
        return moved.get();
    }

    private static String key(RunKey key, int revision) {
        return key.definition() + "|" + key.definitionVersion() + "|" + key.periodId().value() + "|" + revision + "|"
                + key.parametersHash();
    }

    private static RunRow with(RunRow row, RunStatus status, int attempt, Instant leaseUntil, Optional<String> sha256,
            Optional<Instant> completedAt, Optional<RunFailure> failure, Optional<Instant> supersededAt) {
        return new RunRow(row.id(), row.key(), row.revision(), row.period(), row.periodRevision(), row.parameters(),
                row.basis(), status, row.periodLockedAt(), row.mappingVersionId(), row.mappingVersion(), row.cutAt(),
                row.requestedBy(), row.requestedByType(), row.claimedAt(), attempt, leaseUntil, sha256, completedAt,
                failure, supersededAt, row.correlation());
    }
}
