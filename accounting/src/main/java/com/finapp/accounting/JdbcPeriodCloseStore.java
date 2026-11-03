package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PeriodCloseStore} over plain JDBC (`P14-TSK-012`, ADR-0033; {@code JdbcAccountingBreakStore}'s shape): the arbiters are these statements and {@code accounting V008} beneath them -
 * pinned by {@code PeriodCloseStatementsTest} and proven against PostgreSQL by {@code PeriodCloseDatabaseTest} and {@code PeriodReopenDatabaseTest} (owed, C5). Every stamp the database's clock;
 * every failure its operation and SQLSTATE only ({@link AccountingStorageException}), so no reason reaches a message.
 */
public final class JdbcPeriodCloseStore implements PeriodCloseStore {

    static final String INSERT_CLOSE_SQL = "INSERT INTO accounting.period_close (id, period_id, period_revision, period_locked_at,"
            + " close_run_id, pinned_mapping_version_id, valuation_rate_set_id, breaks_acknowledged, breaks_resolved, status, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_reason, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)";

    static final String INSERT_CHECK_SQL = "INSERT INTO accounting.period_close_check (close_id, check_code, close_check_id, attempt, status,"
            + " judged_by, evidence, disclosure_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String CLOSE = "SELECT c.id, c.period_id, p.period_start, c.period_revision, c.period_locked_at, c.close_run_id,"
            + " c.pinned_mapping_version_id, c.valuation_rate_set_id, c.breaks_acknowledged, c.breaks_resolved, c.status, c.proposed_by, c.proposed_at,"
            + " c.proposal_reason, c.decided_by, c.decided_at, c.correlation_id FROM accounting.period_close c"
            + " JOIN accounting.accounting_period p ON p.id = c.period_id";

    static final String FIND_CLOSE_SQL = CLOSE + " WHERE c.id = ?";

    static final String LOCK_CLOSE_SQL = CLOSE + " WHERE c.id = ? FOR UPDATE OF c";

    /** The latest by the database's own stamp; the id, a UUIDv7, settles a tie. */
    static final String LATEST_CLOSE_SQL = CLOSE + " WHERE c.period_id = ? ORDER BY c.proposed_at DESC, c.id DESC LIMIT 1";

    static final String CHECKS_SQL = "SELECT close_id, check_code, close_check_id, attempt, status, judged_by, evidence, disclosure_reason"
            + " FROM accounting.period_close_check WHERE close_id = ?";

    /** The frozen attempts' own evidence - {@code close_check}'s row, never rewritten. */
    static final String ATTEMPT_EVIDENCE_SQL = "SELECT f.check_code, a.evidence FROM accounting.period_close_check f"
            + " JOIN accounting.close_check a ON a.id = f.close_check_id WHERE f.close_id = ?";

    /** The one decision: conditional on {@code PROPOSED}. */
    static final String DECIDE_SQL = "UPDATE accounting.period_close SET status = ?, decided_by = ?, decided_by_type = ?,"
            + " decided_at = clock_timestamp(), decision_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    static final String INSERT_REOPEN_SQL = "INSERT INTO accounting.period_reopen (id, period_id, period_revision, status, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)";

    private static final String REOPEN = "SELECT id, period_id, period_revision, status, proposed_by, proposed_at, proposal_reason, approved_by,"
            + " approved_at FROM accounting.period_reopen WHERE period_id = ? AND period_revision = ?";

    static final String REOPEN_AT_SQL = REOPEN;

    static final String LOCK_REOPEN_AT_SQL = REOPEN + " FOR UPDATE";

    /** The one approval: conditional on {@code PROPOSED}. */
    static final String APPROVE_REOPEN_SQL = "UPDATE accounting.period_reopen SET status = 'APPROVED', approved_by = ?, approved_by_type = ?,"
            + " approved_at = clock_timestamp(), approval_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    @Override
    public void insert(Connection unitOfWork, NewClose close) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_CLOSE_SQL)) {
            insert.setObject(1, close.id().value());
            insert.setObject(2, close.period().value());
            insert.setInt(3, close.revision());
            insert.setTimestamp(4, Timestamp.from(close.lockedAt()));
            insert.setObject(5, close.run().value());
            insert.setObject(6, close.pinned().value());
            insert.setObject(7, close.rateSet().value());
            insert.setLong(8, close.acknowledged());
            insert.setLong(9, close.resolved());
            insert.setString(10, close.proposedBy().id());
            insert.setString(11, close.proposedBy().type().name());
            insert.setString(12, close.reason());
            insert.setString(13, close.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a close proposal", failure);
        }
    }

    @Override
    public void insertCheck(Connection unitOfWork, CheckRow check) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_CHECK_SQL)) {
            insert.setObject(1, check.close().value());
            insert.setString(2, check.check().name());
            insert.setObject(3, check.attemptId().value());
            insert.setInt(4, check.attempt());
            insert.setString(5, check.status().name());
            insert.setString(6, check.judgedBy());
            insert.setString(7, check.evidence().orElse(null));
            insert.setString(8, check.disclosureReason().orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a close proposal's check", failure);
        }
    }

    @Override
    public Optional<CloseRow> find(Connection unitOfWork, PeriodCloseId id) {
        return oneClose(unitOfWork, FIND_CLOSE_SQL, id.value(), "reading a close proposal");
    }

    @Override
    public Optional<CloseRow> lock(Connection unitOfWork, PeriodCloseId id) {
        return oneClose(unitOfWork, LOCK_CLOSE_SQL, id.value(), "locking a close proposal");
    }

    @Override
    public Optional<CloseRow> latest(Connection unitOfWork, AccountingPeriodId period) {
        return oneClose(unitOfWork, LATEST_CLOSE_SQL, period.value(), "reading a period's latest close proposal");
    }

    @Override
    public List<CheckRow> checks(Connection unitOfWork, PeriodCloseId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(CHECKS_SQL)) {
            read.setObject(1, id.value());
            List<CheckRow> checks = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    checks.add(new CheckRow(
                            PeriodCloseId.of(row.getObject("close_id", UUID.class)),
                            CloseCheckCode.valueOf(row.getString("check_code")),
                            CloseCheckId.of(row.getObject("close_check_id", UUID.class)),
                            row.getInt("attempt"),
                            CloseCheckStatus.valueOf(row.getString("status")),
                            row.getString("judged_by"),
                            Optional.ofNullable(row.getString("evidence")),
                            Optional.ofNullable(row.getString("disclosure_reason"))));
                }
            }
            checks.sort(Comparator.comparing(CheckRow::check));
            return checks;
        } catch (SQLException failure) {
            throw failed("reading a close proposal's checks", failure);
        }
    }

    @Override
    public java.util.Map<CloseCheckCode, String> attemptEvidence(Connection unitOfWork, PeriodCloseId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ATTEMPT_EVIDENCE_SQL)) {
            read.setObject(1, id.value());
            java.util.Map<CloseCheckCode, String> evidence = new java.util.EnumMap<>(CloseCheckCode.class);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    evidence.put(CloseCheckCode.valueOf(row.getString(1)), row.getString(2));
                }
            }
            return evidence;
        } catch (SQLException failure) {
            throw failed("reading the attempts a close proposal names", failure);
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, PeriodCloseId id, PeriodCloseStatus decision, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(DECIDE_SQL)) {
            update.setString(1, decision.name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding a close proposal", failure);
        }
    }

    @Override
    public void insertReopen(Connection unitOfWork, NewReopen reopen) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_REOPEN_SQL)) {
            insert.setObject(1, reopen.id().value());
            insert.setObject(2, reopen.period().value());
            insert.setInt(3, reopen.revision());
            insert.setString(4, reopen.proposedBy().id());
            insert.setString(5, reopen.proposedBy().type().name());
            insert.setString(6, reopen.reason());
            insert.setString(7, reopen.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a reopen proposal", failure);
        }
    }

    @Override
    public Optional<ReopenRow> reopenAt(Connection unitOfWork, AccountingPeriodId period, int revision) {
        return oneReopen(unitOfWork, REOPEN_AT_SQL, period, revision, "reading a reopen proposal");
    }

    @Override
    public Optional<ReopenRow> lockReopenAt(Connection unitOfWork, AccountingPeriodId period, int revision) {
        return oneReopen(unitOfWork, LOCK_REOPEN_AT_SQL, period, revision, "locking a reopen proposal");
    }

    @Override
    public boolean approveReopen(Connection unitOfWork, PeriodReopenId id, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(APPROVE_REOPEN_SQL)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("approving a reopen proposal", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<CloseRow> oneClose(Connection unitOfWork, String sql, UUID id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(close(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static CloseRow close(ResultSet row) throws SQLException {
        return new CloseRow(
                PeriodCloseId.of(row.getObject("id", UUID.class)),
                AccountingPeriodId.of(row.getObject("period_id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                row.getInt("period_revision"),
                row.getTimestamp("period_locked_at").toInstant(),
                CloseRunId.of(row.getObject("close_run_id", UUID.class)),
                GlMappingVersionId.of(row.getObject("pinned_mapping_version_id", UUID.class)),
                ValuationRateSetId.of(row.getObject("valuation_rate_set_id", UUID.class)),
                row.getLong("breaks_acknowledged"),
                row.getLong("breaks_resolved"),
                PeriodCloseStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                Optional.ofNullable(row.getString("decided_by")),
                instant(row.getTimestamp("decided_at")),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static Optional<ReopenRow> oneReopen(Connection unitOfWork, String sql, AccountingPeriodId period, int revision, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, period.value());
            read.setInt(2, revision);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(new ReopenRow(
                                PeriodReopenId.of(row.getObject("id", UUID.class)),
                                AccountingPeriodId.of(row.getObject("period_id", UUID.class)),
                                row.getInt("period_revision"),
                                PeriodReopenStatus.valueOf(row.getString("status")),
                                row.getString("proposed_by"),
                                row.getTimestamp("proposed_at").toInstant(),
                                row.getString("proposal_reason"),
                                Optional.ofNullable(row.getString("approved_by")),
                                instant(row.getTimestamp("approved_at"))))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
