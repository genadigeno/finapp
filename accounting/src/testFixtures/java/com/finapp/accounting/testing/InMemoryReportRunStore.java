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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ReportRunStore} in memory (`P14-TSK-008`): the hermetic suites' store, holding the ranks of {@code accounting
 * V004} the machine relies on - the plan's key unique, the conditional edges (the reclaim only past the lease on the
 * "database" clock, the completion and the failure only from {@code GENERATING} at the attempt), the hash recomputed
 * from the content, the content frozen, a FINAL completion only under the period's present lock - so a defect the
 * database would refuse is refused here too, as an {@link IllegalStateException} naming the rank. Every method is
 * synchronized: the database's serialisation of one row, for the suites' racing threads. The real ranks are V004's,
 * proven by {@code ReportRunDatabaseTest} (owed, C5).
 */
public final class InMemoryReportRunStore implements ReportRunStore {

    private final Clock clock;
    private final AccountingPeriodStore periods;
    private final Map<ReportRunId, RunRow> rows = new LinkedHashMap<>();
    private final Map<ReportRunId, Content> contents = new LinkedHashMap<>();
    private int completions;

    /**
     * @param clock the "database" clock - every stamp and lease
     * @param periods the periods, for the FINAL completion's lock re-check (the edge trigger's rank)
     */
    public InMemoryReportRunStore(Clock clock, AccountingPeriodStore periods) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.periods = Objects.requireNonNull(periods, "periods");
    }

    /** Every run, in birth order. */
    public synchronized List<RunRow> all() {
        return List.copyOf(rows.values());
    }

    /** How many completion edges were made - one per run, whatever raced. */
    public synchronized int completions() {
        return completions;
    }

    /** A test's tampering: the content overwritten in place - the freeze's rank refuses it. */
    public synchronized void overwriteContentForTest(ReportRunId id, String content) {
        if (contents.containsKey(id)) {
            throw new IllegalStateException("report_run_content_is_frozen");
        }
        contents.put(id, new Content(content, CanonicalJson.sha256(content)));
    }

    @Override
    public synchronized Optional<RunRow> latest(Connection unitOfWork, RunKey key) {
        return rows.values().stream().filter(row -> row.key().equals(key))
                .max(Comparator.comparingInt(RunRow::revision));
    }

    @Override
    public synchronized boolean insertIfAbsent(Connection unitOfWork, NewRun run) {
        if (rows.values().stream().anyMatch(row -> row.key().equals(run.key()) && row.revision() == run.revision())) {
            return false;
        }
        if ((run.basis() == RunBasis.FINAL) != run.periodLockedAt().isPresent()) {
            throw new IllegalStateException("report_run_basis_coherent");
        }
        Instant now = clock.instant();
        rows.put(run.id(), new RunRow(run.id(), run.key(), run.revision(), run.period(), run.periodRevision(),
                run.parameters(), run.basis(), RunStatus.GENERATING, run.periodLockedAt(), run.mappingVersionId(),
                run.mappingVersion(), now, run.requestedBy().id(), run.requestedBy().type().name(), now, 1,
                now.plus(run.lease()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                run.correlation()));
        return true;
    }

    @Override
    public synchronized Optional<RunRow> at(Connection unitOfWork, RunKey key, int revision) {
        return rows.values().stream().filter(row -> row.key().equals(key) && row.revision() == revision).findFirst();
    }

    @Override
    public synchronized Optional<RunRow> find(Connection unitOfWork, ReportRunId id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized Optional<Content> content(Connection unitOfWork, ReportRunId id) {
        return Optional.ofNullable(contents.get(id));
    }

    @Override
    public synchronized boolean reclaim(Connection unitOfWork, ReportRunId id, int attempt, Duration lease) {
        RunRow row = rows.get(id);
        if (row == null || row.status() != RunStatus.GENERATING || row.attempt() != attempt
                || row.leaseUntil().isAfter(clock.instant())) {
            return false;
        }
        rows.put(id, with(row, RunStatus.GENERATING, attempt + 1, clock.instant().plus(lease), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
        return true;
    }

    @Override
    public synchronized boolean pastLease(Connection unitOfWork, ReportRunId id) {
        RunRow row = rows.get(id);
        return row != null && row.status() == RunStatus.GENERATING && !row.leaseUntil().isAfter(clock.instant());
    }

    @Override
    public synchronized List<RunRow> expired(Connection unitOfWork, int limit) {
        return rows.values().stream()
                .filter(row -> row.status() == RunStatus.GENERATING && !row.leaseUntil().isAfter(clock.instant()))
                .sorted(Comparator.comparing(RunRow::leaseUntil)).limit(limit).toList();
    }

    @Override
    public synchronized boolean complete(
            Connection unitOfWork, ReportRunId id, int attempt, RunStatus status, String content, String sha256) {
        RunRow row = rows.get(id);
        if (row == null || row.status() != RunStatus.GENERATING || row.attempt() != attempt) {
            return false;
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
                throw new IllegalStateException("report_run_permits_only_machine_edges: under the lock it was claimed");
            }
        }
        if (contents.containsKey(id)) {
            throw new IllegalStateException("report_run_content_is_frozen");
        }
        contents.put(id, new Content(content, sha256));
        rows.put(id, with(row, status, attempt, row.leaseUntil(), Optional.of(sha256), Optional.of(clock.instant()),
                Optional.empty(), Optional.empty()));
        completions++;
        return true;
    }

    @Override
    public synchronized boolean fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure) {
        RunRow row = rows.get(id);
        if (row == null || row.status() != RunStatus.GENERATING || row.attempt() != attempt) {
            return false;
        }
        rows.put(id, with(row, RunStatus.FAILED, attempt, row.leaseUntil(), Optional.empty(), Optional.empty(),
                Optional.of(failure), Optional.empty()));
        return true;
    }

    @Override
    public synchronized int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, CorrelationId correlation) {
        List<RunRow> finals = new ArrayList<>(rows.values().stream()
                .filter(row -> row.periodId().equals(period) && row.status() == RunStatus.FINAL).toList());
        for (RunRow row : finals) {
            rows.put(row.id(), with(row, RunStatus.SUPERSEDED, row.attempt(), row.leaseUntil(), row.contentSha256(),
                    row.completedAt(), Optional.empty(), Optional.of(clock.instant())));
        }
        return finals.size();
    }

    private static RunRow with(RunRow row, RunStatus status, int attempt, Instant leaseUntil, Optional<String> sha256,
            Optional<Instant> completedAt, Optional<RunFailure> failure, Optional<Instant> supersededAt) {
        return new RunRow(row.id(), row.key(), row.revision(), row.period(), row.periodRevision(), row.parameters(),
                row.basis(), status, row.periodLockedAt(), row.mappingVersionId(), row.mappingVersion(), row.cutAt(),
                row.requestedBy(), row.requestedByType(), row.claimedAt(), attempt, leaseUntil, sha256, completedAt,
                failure, supersededAt, row.correlation());
    }
}
