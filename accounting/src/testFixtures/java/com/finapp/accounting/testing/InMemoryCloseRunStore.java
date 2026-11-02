package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckId;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link CloseRunStore} in memory (`P14-TSK-011`): the hermetic suites' store, holding the ranks the machine relies on
 * - one run per lock instance, one attempt per {@code (run, check, attempt)} with no gap, at most one {@code PENDING}
 * per check, the claim's own conditions (past the lease, the period under the run's lock, the breaks after the raising
 * checks), the holder's fence, a concluded attempt frozen, {@code DISCLOSED} only where the check admits it - so a
 * defect the database would refuse is refused here too, as an {@link IllegalStateException} naming the rank.
 * <strong>One statement at a time</strong>, as the row locks would have it (a {@link ConcurrentHashMap#compute compute}
 * on one key: the architecture's rules hold this class to no {@code synchronized} and no process-local lock, like any
 * other), so the ten-worker suites race for real. Its clock is the test's ({@link #advance}). The real ranks are {@code
 * accounting V007}'s, proven by {@code CloseRunDatabaseTest} (owed, C5).
 */
public final class InMemoryCloseRunStore implements CloseRunStore {

    private static final String THE_STATEMENT = "statement";

    private final AccountingPeriodStore periods;
    private final Map<CloseRunId, Run> runs = new LinkedHashMap<>();
    private final Map<CloseCheckId, Attempt> attempts = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Boolean> statement = new ConcurrentHashMap<>();
    private Instant now = Instant.parse("2097-04-05T09:00:00Z");
    private int claimsMade;

    public InMemoryCloseRunStore(AccountingPeriodStore periods) {
        this.periods = periods;
    }

    @SuppressWarnings("unchecked")
    private <R> R oneStatement(Supplier<R> work) {
        Object[] answer = new Object[1];
        statement.compute(THE_STATEMENT, (key, held) -> {
            answer[0] = work.get();
            return Boolean.TRUE;
        });
        return (R) answer[0];
    }

    /** The store's clock moved on - a lease runs out, a run's bound passes. */
    public void advance(Duration by) {
        oneStatement(() -> now = now.plus(by));
    }

    /** How many claims succeeded. */
    public int claimsMade() {
        return oneStatement(() -> claimsMade);
    }

    /** Every attempt of the run, in birth order. */
    public List<AttemptRow> all(CloseRunId run) {
        return oneStatement(() -> attempts.values().stream().filter(attempt -> attempt.run.equals(run)).map(Attempt::row).toList());
    }

    /** Every run held. */
    public List<RunRow> runs() {
        return oneStatement(() -> runs.values().stream().map(Run::row).toList());
    }

    @Override
    public List<AccountingPeriodId> lockedWithoutRun(Connection unitOfWork) {
        return oneStatement(() -> {
            List<AccountingPeriodId> found = new ArrayList<>();
            for (AccountingPeriodStore.PeriodRow period : periods.recent(unitOfWork, 1000)) {
                if (period.status() == PeriodStatus.LOCKED && runs.values().stream().noneMatch(run ->
                        run.period.equals(period.id()) && period.lockedAt().filter(run.lockedAt::equals).isPresent())) {
                    found.add(period.id());
                }
            }
            return found;
        });
    }

    @Override
    public long stalledLocks(Connection unitOfWork, Duration bound) {
        return oneStatement(() -> periods.recent(unitOfWork, 1000).stream()
                .filter(period -> period.status() == PeriodStatus.LOCKED)
                .filter(period -> period.lockedAt().filter(at -> !at.plus(bound).isAfter(now)).isPresent())
                .filter(period -> runs.values().stream().noneMatch(run -> run.period.equals(period.id())
                        && period.lockedAt().filter(run.lockedAt::equals).isPresent()))
                .count());
    }

    @Override
    public boolean insertRunIfAbsent(Connection unitOfWork, NewRun run) {
        return oneStatement(() -> {
            if (runs.values().stream().anyMatch(held -> held.period.equals(run.period()) && held.lockedAt.equals(run.lockedAt()))) {
                return false;
            }
            AccountingPeriodStore.PeriodRow period = periods.find(unitOfWork, run.period())
                    .orElseThrow(() -> new IllegalStateException("close_run_period_fk"));
            if (period.status() != PeriodStatus.LOCKED || period.lockedAt().filter(run.lockedAt()::equals).isEmpty()) {
                throw new IllegalStateException("close_run_permits_only_machine_edges: under its period's present lock");
            }
            runs.put(run.id(), new Run(run, period, now));
            return true;
        });
    }

    @Override
    public void insertAttempt(Connection unitOfWork, NewAttempt born) {
        oneStatement(() -> {
            List<Attempt> ofCheck = attempts.values().stream()
                    .filter(attempt -> attempt.run.equals(born.run()) && attempt.check == born.check()).toList();
            if (born.attempt() != ofCheck.size() + 1) {
                throw new IllegalStateException("close_check_permits_only_machine_edges: follows the last, with no gap");
            }
            if (ofCheck.stream().anyMatch(attempt -> attempt.status == CloseCheckStatus.PENDING)) {
                throw new IllegalStateException("close_check_one_pending");
            }
            if (born.requestedBy().type().name().equals("SYSTEM") != born.reason().isEmpty()) {
                throw new IllegalStateException("close_check_requester");
            }
            return attempts.put(born.id(), new Attempt(born, now));
        });
    }

    @Override
    public Optional<RunRow> run(Connection unitOfWork, CloseRunId id) {
        return oneStatement(() -> Optional.ofNullable(runs.get(id)).map(Run::row));
    }

    @Override
    public Optional<RunRow> runUnder(Connection unitOfWork, AccountingPeriodId period, Instant lockedAt) {
        return oneStatement(() -> runs.values().stream()
                .filter(run -> run.period.equals(period) && run.lockedAt.equals(lockedAt)).findFirst().map(Run::row));
    }

    @Override
    public Optional<RunRow> latestRun(Connection unitOfWork, AccountingPeriodId period) {
        return oneStatement(() -> runs.values().stream().filter(run -> run.period.equals(period))
                .reduce((first, second) -> second).map(Run::row));
    }

    @Override
    public Optional<RunRow> lockRun(Connection unitOfWork, CloseRunId id) {
        return run(unitOfWork, id);
    }

    @Override
    public Optional<AttemptRow> claimNext(Connection unitOfWork, UUID holder, Duration lease) {
        return oneStatement(() -> {
            Optional<Attempt> next = attempts.values().stream()
                    .filter(attempt -> attempt.status == CloseCheckStatus.PENDING && !attempt.leaseUntil.isAfter(now))
                    .filter(attempt -> holdsTheLock(unitOfWork, runs.get(attempt.run)))
                    .filter(attempt -> attempt.check != CloseCheckCode.OPEN_ACCOUNTING_BREAKS
                            || attempts.values().stream().noneMatch(raising -> raising.run.equals(attempt.run)
                                    && raising.status == CloseCheckStatus.PENDING && raising.check.raisesBreaks()))
                    .min(Comparator.comparing((Attempt attempt) -> attempt.leaseUntil));
            next.ifPresent(attempt -> {
                attempt.reclaims += attempt.holder == null ? 0 : 1;
                attempt.holder = holder;
                attempt.leaseUntil = now.plus(lease);
                attempt.claimedAt = now;
                claimsMade++;
            });
            return next.map(Attempt::row);
        });
    }

    @Override
    public boolean renew(Connection unitOfWork, CloseCheckId id, UUID holder, Duration lease, Duration maxRun) {
        return oneStatement(() -> {
            Attempt attempt = attempts.get(id);
            if (attempt == null || attempt.status != CloseCheckStatus.PENDING || !holder.equals(attempt.holder)
                    || !attempt.claimedAt.plus(maxRun).isAfter(now)) {
                return false;
            }
            attempt.leaseUntil = now.plus(lease);
            return true;
        });
    }

    @Override
    public boolean defer(Connection unitOfWork, CloseCheckId id, UUID holder, Duration wait) {
        return oneStatement(() -> {
            Attempt attempt = attempts.get(id);
            if (attempt == null || attempt.status != CloseCheckStatus.PENDING || !holder.equals(attempt.holder)) {
                return false;
            }
            attempt.holder = null;
            attempt.leaseUntil = now.plus(wait);
            return true;
        });
    }

    @Override
    public boolean conclude(Connection unitOfWork, CloseCheckId id, UUID holder, CheckVerdict verdict) {
        return oneStatement(() -> {
            Attempt attempt = attempts.get(id);
            if (attempt == null || attempt.status != CloseCheckStatus.PENDING || !holder.equals(attempt.holder)) {
                return false;
            }
            if (verdict.status() == CloseCheckStatus.DISCLOSED && !attempt.check.disclosable()) {
                throw new IllegalStateException("close_check_disclosed_only_where_it_may");
            }
            attempt.status = verdict.status();
            attempt.judgedBy = Optional.of(verdict.judgedBy());
            attempt.failures = verdict.failures();
            attempt.evidence = Optional.of(verdict.evidence());
            attempt.concludedAt = Optional.of(now);
            return true;
        });
    }

    @Override
    public Optional<AttemptRow> latestAttempt(Connection unitOfWork, CloseRunId run, CloseCheckCode check) {
        return oneStatement(() -> attempts.values().stream()
                .filter(attempt -> attempt.run.equals(run) && attempt.check == check)
                .max(Comparator.comparingInt(attempt -> attempt.number)).map(Attempt::row));
    }

    @Override
    public List<AttemptRow> attempts(Connection unitOfWork, CloseRunId run) {
        return oneStatement(() -> {
            List<AttemptRow> rows = new ArrayList<>();
            for (CloseCheckCode check : CloseCheckCode.values()) {
                attempts.values().stream().filter(attempt -> attempt.run.equals(run) && attempt.check == check)
                        .sorted(Comparator.comparingInt((Attempt attempt) -> attempt.number).reversed())
                        .limit(HISTORY).map(Attempt::row).forEach(rows::add);
            }
            return rows;
        });
    }

    @Override
    public Optional<Duration> markChecksConcluded(Connection unitOfWork, CloseRunId id) {
        return oneStatement(() -> {
            Run run = runs.get(id);
            if (run.checksConcludedAt.isPresent() || attempts.values().stream()
                    .anyMatch(attempt -> attempt.run.equals(id) && attempt.status == CloseCheckStatus.PENDING)) {
                return Optional.empty();
            }
            run.checksConcludedAt = Optional.of(now);
            return Optional.of(Duration.between(run.startedAt, now));
        });
    }

    private boolean holdsTheLock(Connection unitOfWork, Run run) {
        return periods.find(unitOfWork, run.period).filter(period -> period.status() == PeriodStatus.LOCKED
                && period.lockedAt().filter(run.lockedAt::equals).isPresent()).isPresent();
    }

    private static final class Run {
        final CloseRunId id;
        final AccountingPeriodId period;
        final YearMonth month;
        final Instant lockedAt;
        final int revision;
        final Instant startedAt;
        final CorrelationId correlation;
        Optional<Instant> checksConcludedAt = Optional.empty();

        Run(NewRun born, AccountingPeriodStore.PeriodRow period, Instant now) {
            this.id = born.id();
            this.period = born.period();
            this.month = period.month();
            this.lockedAt = born.lockedAt();
            this.revision = born.periodRevision();
            this.startedAt = now;
            this.correlation = born.correlation();
        }

        RunRow row() {
            return new RunRow(id, period, month, lockedAt, revision, startedAt, checksConcludedAt, correlation);
        }
    }

    private static final class Attempt {
        final CloseCheckId id;
        final CloseRunId run;
        final CloseCheckCode check;
        final int number;
        final Instant bornAt;
        final CorrelationId correlation;
        CloseCheckStatus status = CloseCheckStatus.PENDING;
        UUID holder;
        int reclaims;
        Instant leaseUntil;
        Instant claimedAt;
        Optional<String> judgedBy = Optional.empty();
        List<String> failures = List.of();
        Optional<String> evidence = Optional.empty();
        Optional<Instant> concludedAt = Optional.empty();

        Attempt(NewAttempt born, Instant now) {
            this.id = born.id();
            this.run = born.run();
            this.check = born.check();
            this.number = born.attempt();
            this.bornAt = now;
            this.leaseUntil = now;
            this.correlation = born.correlation();
        }

        AttemptRow row() {
            return new AttemptRow(id, run, check, number, status, reclaims, bornAt, Optional.ofNullable(claimedAt), judgedBy,
                    failures, evidence, concludedAt, correlation);
        }
    }
}
