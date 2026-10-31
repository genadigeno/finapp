package com.finapp.accounting;

import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link ReportRunStore} over plain JDBC (`P14-TSK-008`; {@code JdbcAccountingPeriodStore}'s shape): the birth an
 * {@code INSERT ... ON CONFLICT ON CONSTRAINT report_run_key DO NOTHING}, every move one conditional {@code UPDATE} under
 * {@code accounting V004}'s freeze and edge triggers, every stamp and lease the database's clock, every failure its
 * operation and SQLSTATE only ({@link AccountingStorageException}). The run's parameters are read back from their stored
 * canonical text by {@code jsonb_each_text} and re-rendered: a text this code would not have written is refused, never
 * trusted.
 *
 * <p>The conditional statements are pinned ({@link #COMPLETE_SQL}, {@link #RECLAIM_SQL}, {@link #FAIL_SQL},
 * {@link #SUPERSEDE_SQL}) by {@code ReportRunStatementsTest} - they are the arbiters of contention row 7 and of the lease.
 */
public final class JdbcReportRunStore implements ReportRunStore {

    /** The completion edge: content and hash in one statement, only from GENERATING at the attempt that holds it. */
    static final String COMPLETE_SQL = "UPDATE accounting.report_run SET status = ?, content = ?, content_sha256 = ?,"
            + " completed_at = clock_timestamp() WHERE id = ? AND status = 'GENERATING' AND attempt = ?";

    /** The lease's reclaim: one attempt more, only past the lease, only at the attempt read. */
    static final String RECLAIM_SQL = "UPDATE accounting.report_run SET attempt = attempt + 1,"
            + " lease_until = clock_timestamp() + ? * interval '1 millisecond' WHERE id = ? AND status = 'GENERATING'"
            + " AND attempt = ? AND lease_until <= clock_timestamp()";

    /** The failure edge, only from GENERATING at the attempt that holds it. */
    static final String FAIL_SQL = "UPDATE accounting.report_run SET status = 'FAILED', failure_code = ?,"
            + " failed_at = clock_timestamp() WHERE id = ? AND status = 'GENERATING' AND attempt = ?";

    /** Every FINAL run of a period, superseded - the unlock's and the reopen's. */
    static final String SUPERSEDE_SQL = "UPDATE accounting.report_run SET status = 'SUPERSEDED',"
            + " superseded_at = clock_timestamp(), superseded_correlation_id = ? WHERE period_id = ? AND status = 'FINAL'";

    /**
     * A completed PROVISIONAL run's freshness (`P14-TSK-009`): its cut younger than the window on the database's clock -
     * never an instance's.
     */
    static final String CUT_WITHIN_SQL = "SELECT cut_at > clock_timestamp() - ? * interval '1 millisecond'"
            + " FROM accounting.report_run WHERE id = ?";

    /** The birth: insert-or-read on the plan's key. */
    static final String INSERT_SQL = "INSERT INTO accounting.report_run (id, definition, definition_version, period_id,"
            + " revision, parameters, parameters_hash, period_revision, basis, period_locked_at, mapping_version_id,"
            + " valuation_rate_set_id, cut_at, requested_by, requested_by_type, claimed_at, status, attempt, lease_until,"
            + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, clock_timestamp(),"
            + " 'GENERATING', 1, clock_timestamp() + ? * interval '1 millisecond', ?)"
            + " ON CONFLICT ON CONSTRAINT report_run_key DO NOTHING";

    private static final String ROW = "SELECT r.id, r.definition, r.definition_version, r.period_id, r.revision,"
            + " r.parameters, r.parameters_hash, r.period_revision, r.basis, r.status, r.period_locked_at,"
            + " r.mapping_version_id, v.version AS mapping_version, r.valuation_rate_set_id, r.cut_at, r.requested_by,"
            + " r.requested_by_type, r.claimed_at, r.attempt, r.lease_until, r.content_sha256, r.completed_at,"
            + " r.failure_code, r.superseded_at, r.correlation_id, p.period_start,"
            + " (SELECT array_agg(e.key ORDER BY e.key) FROM jsonb_each_text(r.parameters::jsonb -> 'values') e)"
            + " AS parameter_names,"
            + " (SELECT array_agg(e.value ORDER BY e.key) FROM jsonb_each_text(r.parameters::jsonb -> 'values') e)"
            + " AS parameter_values"
            + " FROM accounting.report_run r JOIN accounting.accounting_period p ON p.id = r.period_id"
            + " LEFT JOIN accounting.gl_mapping_version v ON v.id = r.mapping_version_id";

    @Override
    public Optional<RunRow> latest(Connection unitOfWork, RunKey key) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE r.definition = ?"
                + " AND r.definition_version = ? AND r.period_id = ? AND r.parameters_hash = ?"
                + " ORDER BY r.revision DESC LIMIT 1")) {
            bindKey(read, key);
            return first(read);
        } catch (SQLException failure) {
            throw failed("reading a report run's latest revision", failure);
        }
    }

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewRun run) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            insert.setObject(1, run.id().value());
            insert.setString(2, run.key().definition());
            insert.setInt(3, run.key().definitionVersion());
            insert.setObject(4, run.key().periodId().value());
            insert.setInt(5, run.revision());
            insert.setString(6, run.parameters().canonical());
            insert.setString(7, run.key().parametersHash());
            insert.setInt(8, run.periodRevision());
            insert.setString(9, run.basis().name());
            insert.setTimestamp(10, run.periodLockedAt().map(Timestamp::from).orElse(null));
            insert.setObject(11, run.mappingVersionId().map(GlMappingVersionId::value).orElse(null));
            insert.setObject(12, run.parameters().valuationRateSet().orElse(null));
            insert.setString(13, run.requestedBy().id());
            insert.setString(14, run.requestedBy().type().name());
            insert.setLong(15, run.lease().toMillis());
            insert.setString(16, run.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("claiming a report run", failure);
        }
    }

    @Override
    public Optional<RunRow> at(Connection unitOfWork, RunKey key, int revision) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE r.definition = ?"
                + " AND r.definition_version = ? AND r.period_id = ? AND r.parameters_hash = ? AND r.revision = ?")) {
            bindKey(read, key);
            read.setInt(5, revision);
            return first(read);
        } catch (SQLException failure) {
            throw failed("reading a report run at its key", failure);
        }
    }

    @Override
    public Optional<RunRow> find(Connection unitOfWork, ReportRunId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE r.id = ?")) {
            read.setObject(1, id.value());
            return first(read);
        } catch (SQLException failure) {
            throw failed("reading a report run", failure);
        }
    }

    @Override
    public Optional<Content> content(Connection unitOfWork, ReportRunId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(
                "SELECT content, content_sha256 FROM accounting.report_run WHERE id = ? AND content IS NOT NULL")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(new Content(row.getString("content"), row.getString("content_sha256")))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a report run's content", failure);
        }
    }

    @Override
    public boolean reclaim(Connection unitOfWork, ReportRunId id, int attempt, Duration lease) {
        try (PreparedStatement update = unitOfWork.prepareStatement(RECLAIM_SQL)) {
            update.setLong(1, lease.toMillis());
            update.setObject(2, id.value());
            update.setInt(3, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("reclaiming a report run's lease", failure);
        }
    }

    @Override
    public boolean pastLease(Connection unitOfWork, ReportRunId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT lease_until <= clock_timestamp()"
                + " FROM accounting.report_run WHERE id = ? AND status = 'GENERATING'")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a report run's lease", failure);
        }
    }

    @Override
    public List<RunRow> expired(Connection unitOfWork, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE r.status = 'GENERATING'"
                + " AND r.lease_until <= clock_timestamp() ORDER BY r.lease_until LIMIT ? FOR UPDATE OF r SKIP LOCKED")) {
            read.setInt(1, limit);
            List<RunRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(row(row));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed("claiming the expired report runs", failure);
        }
    }

    @Override
    public boolean complete(
            Connection unitOfWork, ReportRunId id, int attempt, RunStatus status, String content, String sha256) {
        try (PreparedStatement update = unitOfWork.prepareStatement(COMPLETE_SQL)) {
            update.setString(1, status.name());
            update.setString(2, content);
            update.setString(3, sha256);
            update.setObject(4, id.value());
            update.setInt(5, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("completing a report run", failure);
        }
    }

    @Override
    public boolean fail(Connection unitOfWork, ReportRunId id, int attempt, RunFailure failure) {
        try (PreparedStatement update = unitOfWork.prepareStatement(FAIL_SQL)) {
            update.setString(1, failure.name());
            update.setObject(2, id.value());
            update.setInt(3, attempt);
            return update.executeUpdate() == 1;
        } catch (SQLException failed) {
            throw failed("failing a report run", failed);
        }
    }

    @Override
    public int supersedeFinal(Connection unitOfWork, AccountingPeriodId period, CorrelationId correlation) {
        try (PreparedStatement update = unitOfWork.prepareStatement(SUPERSEDE_SQL)) {
            update.setString(1, correlation.value());
            update.setObject(2, period.value());
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw failed("superseding a period's FINAL report runs", failure);
        }
    }

    @Override
    public boolean cutWithin(Connection unitOfWork, ReportRunId id, Duration window) {
        try (PreparedStatement read = unitOfWork.prepareStatement(CUT_WITHIN_SQL)) {
            read.setLong(1, window.toMillis());
            read.setObject(2, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a report run's freshness", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void bindKey(PreparedStatement read, RunKey key) throws SQLException {
        read.setString(1, key.definition());
        read.setInt(2, key.definitionVersion());
        read.setObject(3, key.periodId().value());
        read.setString(4, key.parametersHash());
    }

    private static Optional<RunRow> first(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(row(row)) : Optional.empty();
        }
    }

    private static RunRow row(ResultSet row) throws SQLException {
        UUID mapping = row.getObject("mapping_version_id", UUID.class);
        UUID rateSet = row.getObject("valuation_rate_set_id", UUID.class);
        RunParameters parameters = parameters(row, Optional.ofNullable(rateSet));
        String failure = row.getString("failure_code");
        return new RunRow(
                ReportRunId.of(row.getObject("id", UUID.class)),
                new RunKey(row.getString("definition"), row.getInt("definition_version"),
                        AccountingPeriodId.of(row.getObject("period_id", UUID.class)), row.getString("parameters_hash")),
                row.getInt("revision"),
                YearMonth.from(row.getObject("period_start", Date.class).toLocalDate()),
                row.getInt("period_revision"),
                parameters,
                RunBasis.valueOf(row.getString("basis")),
                RunStatus.valueOf(row.getString("status")),
                instant(row.getTimestamp("period_locked_at")),
                mapping == null ? Optional.empty() : Optional.of(GlMappingVersionId.of(mapping)),
                mapping == null ? Optional.empty() : Optional.of(row.getInt("mapping_version")),
                row.getTimestamp("cut_at").toInstant(),
                row.getString("requested_by"),
                row.getString("requested_by_type"),
                row.getTimestamp("claimed_at").toInstant(),
                row.getInt("attempt"),
                row.getTimestamp("lease_until").toInstant(),
                Optional.ofNullable(row.getString("content_sha256")),
                instant(row.getTimestamp("completed_at")),
                failure == null ? Optional.empty() : Optional.of(RunFailure.valueOf(failure)),
                instant(row.getTimestamp("superseded_at")),
                CorrelationId.of(row.getString("correlation_id")));
    }

    /** The stored parameters, rebuilt and re-rendered - the stored text must be exactly what this code writes. */
    private static RunParameters parameters(ResultSet row, Optional<UUID> rateSet) throws SQLException {
        TreeMap<String, String> values = new TreeMap<>();
        java.sql.Array names = row.getArray("parameter_names");
        java.sql.Array texts = row.getArray("parameter_values");
        if (names != null && texts != null) {
            String[] keys = (String[]) names.getArray();
            String[] vals = (String[]) texts.getArray();
            for (int i = 0; i < keys.length; i++) {
                values.put(keys[i], vals[i]);
            }
        }
        RunParameters parameters = new RunParameters(values, rateSet);
        if (!parameters.canonical().equals(row.getString("parameters"))) {
            throw new AccountingStorageException("reading a report run: its stored parameters are not canonical");
        }
        return parameters;
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
