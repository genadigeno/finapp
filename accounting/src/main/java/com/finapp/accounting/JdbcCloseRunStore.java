package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CloseRunStore} over plain JDBC (`P14-TSK-011`, ADR-0033; {@code JdbcReportRunStore}'s shape): the arbiters are
 * these statements - pinned by {@code CloseRunStatementsTest} and proven against PostgreSQL by {@code
 * CloseRunDatabaseTest} (owed, C5). Every lease and every stamp the database's clock; every failure its operation and
 * SQLSTATE only ({@link AccountingStorageException}).
 */
public final class JdbcCloseRunStore implements CloseRunStore {

    private static final String LEASE = "clock_timestamp() + ? * interval '1 millisecond'";

    /**
     * The claim: one statement, so no row is held past it. The subselect takes one claimable row {@code FOR UPDATE SKIP
     * LOCKED} - ten workers take disjoint rows and none waits on another - and the {@code UPDATE} makes this worker its
     * holder; a holder still on the row had lapsed, so it counts one reclaim. The period's present lock is read here
     * too: a run an unlock left behind is never claimed. {@code OPEN_ACCOUNTING_BREAKS} waits for the run's raising
     * checks: it would otherwise pass before they raise.
     */
    static final String CLAIM_SQL = "UPDATE accounting.close_check attempt"
            + " SET reclaims = attempt.reclaims + CASE WHEN attempt.holder IS NULL THEN 0 ELSE 1 END, holder = ?,"
            + " lease_until = " + LEASE + ", claimed_at = clock_timestamp()"
            + " WHERE attempt.id = (SELECT candidate.id FROM accounting.close_check candidate"
            + " JOIN accounting.close_run run ON run.id = candidate.close_run_id"
            + " JOIN accounting.accounting_period period ON period.id = run.period_id"
            + " WHERE candidate.status = 'PENDING' AND candidate.lease_until <= clock_timestamp()"
            + " AND period.status = 'LOCKED' AND period.locked_at = run.period_locked_at"
            + " AND (candidate.check_code <> 'OPEN_ACCOUNTING_BREAKS' OR NOT EXISTS (SELECT 1"
            + " FROM accounting.close_check raising WHERE raising.close_run_id = candidate.close_run_id"
            + " AND raising.status = 'PENDING'"
            + " AND raising.check_code IN ('GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS')))"
            + " ORDER BY candidate.lease_until, candidate.id LIMIT 1 FOR UPDATE OF candidate SKIP LOCKED)"
            + " AND attempt.status = 'PENDING'"
            + " RETURNING attempt.id, attempt.close_run_id, attempt.check_code, attempt.attempt, attempt.status,"
            + " attempt.reclaims, attempt.born_at, attempt.claimed_at, attempt.judged_by, attempt.failures,"
            + " attempt.evidence, attempt.concluded_at, attempt.correlation_id";

    /** A live holder's renewal: only while it holds the attempt and within the run's bound of its claim. */
    static final String RENEW_SQL = "UPDATE accounting.close_check SET lease_until = " + LEASE
            + " WHERE id = ? AND status = 'PENDING' AND holder = ?"
            + " AND claimed_at + ? * interval '1 millisecond' > clock_timestamp()";

    /** The attempt given back - nobody's, claimable after the wait; not a death, so not a reclaim. */
    static final String DEFER_SQL = "UPDATE accounting.close_check SET holder = NULL, lease_until = " + LEASE
            + " WHERE id = ? AND status = 'PENDING' AND holder = ?";

    /** The conclusion, fenced: a claim overtaken by a reclaim moves nothing. */
    static final String CONCLUDE_SQL = "UPDATE accounting.close_check SET status = ?, judged_by = ?, failures = ?,"
            + " evidence = ?, concluded_at = clock_timestamp() WHERE id = ? AND status = 'PENDING' AND holder = ?";

    /** The first time nothing of the run is pending - stamped once, under the run's row lock. */
    static final String MARK_CONCLUDED_SQL = "UPDATE accounting.close_run run SET checks_concluded_at = clock_timestamp()"
            + " WHERE run.id = ? AND run.checks_concluded_at IS NULL AND NOT EXISTS (SELECT 1 FROM accounting.close_check attempt"
            + " WHERE attempt.close_run_id = run.id AND attempt.status = 'PENDING')"
            + " RETURNING (EXTRACT(EPOCH FROM (run.checks_concluded_at - run.started_at)) * 1000)::bigint";

    /** The periods locked under a lock no run answers. */
    static final String LOCKED_WITHOUT_RUN_SQL = "SELECT period.id FROM accounting.accounting_period period"
            + " WHERE period.status = 'LOCKED' AND NOT EXISTS (SELECT 1 FROM accounting.close_run run"
            + " WHERE run.period_id = period.id AND run.period_locked_at = period.locked_at) ORDER BY period.period_start";

    static final String INSERT_RUN_SQL = "INSERT INTO accounting.close_run (id, period_id, period_locked_at, period_revision,"
            + " started_at, correlation_id) VALUES (?, ?, ?, ?, clock_timestamp(), ?)"
            + " ON CONFLICT ON CONSTRAINT close_run_one_per_lock DO NOTHING";

    static final String INSERT_ATTEMPT_SQL = "INSERT INTO accounting.close_check (id, close_run_id, check_code, attempt, status,"
            + " requested_by, requested_by_type, request_reason, born_at, reclaims, lease_until, correlation_id)"
            + " VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, clock_timestamp(), 0, clock_timestamp(), ?)";

    private static final String RUN = "SELECT run.id, run.period_id, period.period_start, run.period_locked_at,"
            + " run.period_revision, run.started_at, run.checks_concluded_at, run.correlation_id"
            + " FROM accounting.close_run run JOIN accounting.accounting_period period ON period.id = run.period_id";

    private static final String ATTEMPT_COLUMNS = "attempt.id, attempt.close_run_id, attempt.check_code, attempt.attempt,"
            + " attempt.status, attempt.reclaims, attempt.born_at, attempt.claimed_at, attempt.judged_by, attempt.failures,"
            + " attempt.evidence, attempt.concluded_at, attempt.correlation_id";

    /** Per check its newest {@link CloseRunStore#HISTORY} attempts - one statement, newest first. */
    static final String ATTEMPTS_SQL = "SELECT " + ATTEMPT_COLUMNS + " FROM (SELECT " + ATTEMPT_COLUMNS.replace("attempt.", "c.")
            + ", row_number() OVER (PARTITION BY c.check_code ORDER BY c.attempt DESC) AS recency"
            + " FROM accounting.close_check c WHERE c.close_run_id = ?) attempt"
            + " WHERE attempt.recency <= ? ORDER BY attempt.check_code, attempt.attempt DESC";

    @Override
    public List<AccountingPeriodId> lockedWithoutRun(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement(LOCKED_WITHOUT_RUN_SQL);
                ResultSet row = read.executeQuery()) {
            List<AccountingPeriodId> periods = new ArrayList<>();
            while (row.next()) {
                periods.add(AccountingPeriodId.of(row.getObject(1, UUID.class)));
            }
            return periods;
        } catch (SQLException failure) {
            throw failed("reading the periods locked without a close run", failure);
        }
    }

    @Override
    public boolean insertRunIfAbsent(Connection unitOfWork, NewRun run) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_RUN_SQL)) {
            insert.setObject(1, run.id().value());
            insert.setObject(2, run.period().value());
            insert.setTimestamp(3, Timestamp.from(run.lockedAt()));
            insert.setInt(4, run.periodRevision());
            insert.setString(5, run.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("starting a close run", failure);
        }
    }

    @Override
    public void insertAttempt(Connection unitOfWork, NewAttempt attempt) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_ATTEMPT_SQL)) {
            insert.setObject(1, attempt.id().value());
            insert.setObject(2, attempt.run().value());
            insert.setString(3, attempt.check().name());
            insert.setInt(4, attempt.attempt());
            insert.setString(5, attempt.requestedBy().id());
            insert.setString(6, attempt.requestedBy().type().name());
            insert.setString(7, attempt.reason().orElse(null));
            insert.setString(8, attempt.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a close check attempt", failure);
        }
    }

    @Override
    public Optional<RunRow> run(Connection unitOfWork, CloseRunId id) {
        return oneRun(unitOfWork, RUN + " WHERE run.id = ?", id.value(), "reading a close run");
    }

    @Override
    public Optional<RunRow> runUnder(Connection unitOfWork, AccountingPeriodId period, Instant lockedAt) {
        try (PreparedStatement read = unitOfWork.prepareStatement(RUN + " WHERE run.period_id = ? AND run.period_locked_at = ?")) {
            read.setObject(1, period.value());
            read.setTimestamp(2, Timestamp.from(lockedAt));
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(run(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a lock's close run", failure);
        }
    }

    @Override
    public Optional<RunRow> latestRun(Connection unitOfWork, AccountingPeriodId period) {
        return oneRun(unitOfWork, RUN + " WHERE run.period_id = ? ORDER BY run.started_at DESC, run.id DESC LIMIT 1",
                period.value(), "reading a period's latest close run");
    }

    @Override
    public Optional<RunRow> lockRun(Connection unitOfWork, CloseRunId id) {
        return oneRun(unitOfWork, RUN + " WHERE run.id = ? FOR UPDATE OF run", id.value(), "locking a close run");
    }

    @Override
    public Optional<AttemptRow> claimNext(Connection unitOfWork, UUID holder, Duration lease) {
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_SQL)) {
            claim.setObject(1, holder);
            claim.setLong(2, lease.toMillis());
            try (ResultSet row = claim.executeQuery()) {
                return row.next() ? Optional.of(attempt(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("claiming a close check", failure);
        }
    }

    @Override
    public boolean renew(Connection unitOfWork, CloseCheckId id, UUID holder, Duration lease, Duration maxRun) {
        try (PreparedStatement renew = unitOfWork.prepareStatement(RENEW_SQL)) {
            renew.setLong(1, lease.toMillis());
            renew.setObject(2, id.value());
            renew.setObject(3, holder);
            renew.setLong(4, maxRun.toMillis());
            return renew.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("renewing a close check's lease", failure);
        }
    }

    @Override
    public boolean defer(Connection unitOfWork, CloseCheckId id, UUID holder, Duration wait) {
        try (PreparedStatement defer = unitOfWork.prepareStatement(DEFER_SQL)) {
            defer.setLong(1, wait.toMillis());
            defer.setObject(2, id.value());
            defer.setObject(3, holder);
            return defer.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deferring a close check", failure);
        }
    }

    @Override
    public boolean conclude(Connection unitOfWork, CloseCheckId id, UUID holder, CheckVerdict verdict) {
        try (PreparedStatement conclude = unitOfWork.prepareStatement(CONCLUDE_SQL)) {
            conclude.setString(1, verdict.status().name());
            conclude.setString(2, verdict.judgedBy());
            conclude.setString(3, verdict.failures().isEmpty() ? null
                    : String.join(CheckVerdict.SEPARATOR, verdict.failures()));
            conclude.setString(4, verdict.evidence());
            conclude.setObject(5, id.value());
            conclude.setObject(6, holder);
            return conclude.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("concluding a close check", failure);
        }
    }

    @Override
    public Optional<AttemptRow> latestAttempt(Connection unitOfWork, CloseRunId run, CloseCheckCode check) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT " + ATTEMPT_COLUMNS
                + " FROM accounting.close_check attempt WHERE attempt.close_run_id = ? AND attempt.check_code = ?"
                + " ORDER BY attempt.attempt DESC LIMIT 1")) {
            read.setObject(1, run.value());
            read.setString(2, check.name());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(attempt(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a close check's latest attempt", failure);
        }
    }

    @Override
    public List<AttemptRow> attempts(Connection unitOfWork, CloseRunId run) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ATTEMPTS_SQL)) {
            read.setObject(1, run.value());
            read.setInt(2, HISTORY);
            try (ResultSet row = read.executeQuery()) {
                List<AttemptRow> attempts = new ArrayList<>();
                while (row.next()) {
                    attempts.add(attempt(row));
                }
                return attempts;
            }
        } catch (SQLException failure) {
            throw failed("reading a close run's attempts", failure);
        }
    }

    @Override
    public Optional<Duration> markChecksConcluded(Connection unitOfWork, CloseRunId run) {
        try (PreparedStatement mark = unitOfWork.prepareStatement(MARK_CONCLUDED_SQL)) {
            mark.setObject(1, run.value());
            try (ResultSet row = mark.executeQuery()) {
                return row.next() ? Optional.of(Duration.ofMillis(row.getLong(1))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("recording a close run's checks concluded", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Optional<RunRow> oneRun(Connection unitOfWork, String sql, UUID id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(run(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static RunRow run(ResultSet row) throws SQLException {
        return new RunRow(
                CloseRunId.of(row.getObject("id", UUID.class)),
                AccountingPeriodId.of(row.getObject("period_id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                row.getTimestamp("period_locked_at").toInstant(),
                row.getInt("period_revision"),
                row.getTimestamp("started_at").toInstant(),
                instant(row.getTimestamp("checks_concluded_at")),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static AttemptRow attempt(ResultSet row) throws SQLException {
        String failures = row.getString("failures");
        return new AttemptRow(
                CloseCheckId.of(row.getObject("id", UUID.class)),
                CloseRunId.of(row.getObject("close_run_id", UUID.class)),
                CloseCheckCode.valueOf(row.getString("check_code")),
                row.getInt("attempt"),
                CloseCheckStatus.valueOf(row.getString("status")),
                row.getInt("reclaims"),
                row.getTimestamp("born_at").toInstant(),
                instant(row.getTimestamp("claimed_at")),
                Optional.ofNullable(row.getString("judged_by")),
                failures == null ? List.of() : Arrays.asList(failures.split(CheckVerdict.SEPARATOR)),
                Optional.ofNullable(row.getString("evidence")),
                instant(row.getTimestamp("concluded_at")),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
