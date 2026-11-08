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

    /**
     * The period's latest proposal, <strong>ranked by the rows, never by a stamp a stepped clock can reorder</strong> (`P15-TSK-033`, PI-013): the {@code PROPOSED}
     * one first - {@code period_close_one_pending} admits one, and while it stands every other proposal of the period was decided before it was born (a proposal
     * needs the period {@code LOCKED}; a {@code PROPOSED} one holds it {@code CLOSE_PROPOSED}); then the higher revision, which moves only at a reopen; then the
     * revision's {@code APPROVED} one ({@code period_close_one_approved}), its last; and only then {@code proposed_at}, which orders nothing but one revision's
     * {@code REJECTED} proposals - none of which an act reads ({@link PeriodCloses#approve} and {@link PeriodCloses#reject} name a proposal by its id and lock it,
     * and {@link PeriodCloses#proposalOf} says a decided one no longer stands). It read {@code ORDER BY c.proposed_at DESC, c.id DESC} until then: after a backward
     * step a rejected proposal could read as the latest, and a repeated proposal converge on it.
     */
    static final String LATEST_CLOSE_SQL = CLOSE + " WHERE c.period_id = ?"
            + " ORDER BY (c.status = 'PROPOSED') DESC, c.period_revision DESC, (c.status = 'APPROVED') DESC, c.proposed_at DESC, c.id DESC LIMIT 1";

    static final String CHECKS_SQL = "SELECT close_id, check_code, close_check_id, attempt, status, judged_by, evidence, disclosure_reason"
            + " FROM accounting.period_close_check WHERE close_id = ?";

    /**
     * The frozen attempts as they concluded - {@code close_check}'s own row, never rewritten: its evidence, and {@code concluded_at} as {@code V007}'s trigger stamped it on the database's clock
     * (`P14-TSK-024`). A frozen attempt is {@code PASSED} or {@code DISCLOSED} ({@code period_close_check_is_ready}), so both are {@code NOT NULL} ({@code close_check_outcome_coherent}).
     */
    static final String FROZEN_ATTEMPTS_SQL = "SELECT f.check_code, a.evidence, a.concluded_at FROM accounting.period_close_check f"
            + " JOIN accounting.close_check a ON a.id = f.close_check_id WHERE f.close_id = ?";

    /** The one decision: conditional on {@code PROPOSED}. */
    static final String DECIDE_SQL = "UPDATE accounting.period_close SET status = ?, decided_by = ?, decided_by_type = ?,"
            + " decided_at = clock_timestamp(), decision_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    static final String INSERT_REOPEN_SQL = "INSERT INTO accounting.period_reopen (id, period_id, period_revision, status, proposed_by,"
            + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)";

    private static final String REOPEN = "SELECT id, period_id, period_revision, status, proposed_by, proposed_at, proposal_reason, approved_by,"
            + " approved_at, ended_by, ended_at FROM accounting.period_reopen";

    /** The pending one: {@code period_reopen_one_pending} admits at most one per period (`P14-TSK-024`; {@code V008}'s one per revision until then). */
    static final String PENDING_REOPEN_SQL = REOPEN + " WHERE period_id = ? AND status = 'PROPOSED'";

    static final String LOCK_PENDING_REOPEN_SQL = PENDING_REOPEN_SQL + " FOR UPDATE";

    /** The approved one of a revision: {@code period_reopen_one_approved}. */
    static final String APPROVED_REOPEN_AT_SQL = REOPEN + " WHERE period_id = ? AND period_revision = ? AND status = 'APPROVED'";

    /**
     * The reopen named, by its id, when it ended without its effect - whatever ended or was proposed after it (`P14-DOC-001`, `P14-TSK-024`'s re-review (a)): the primary key admits at most one.
     * It read a revision's latest end until then, so a retry naming the first of two ended reopens was told there was none.
     */
    static final String ENDED_REOPEN_SQL = REOPEN + " WHERE period_id = ? AND id = ? AND status IN ('REJECTED', 'WITHDRAWN')";

    /** The one approval: conditional on {@code PROPOSED}. */
    static final String APPROVE_REOPEN_SQL = "UPDATE accounting.period_reopen SET status = 'APPROVED', approved_by = ?, approved_by_type = ?,"
            + " approved_at = clock_timestamp(), approval_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    /** An end instead (`P14-TSK-024`): conditional on {@code PROPOSED}, so an approval and an end never both move it; {@code V011}'s {@code CHECK} judges the four eyes. */
    static final String END_REOPEN_SQL = "UPDATE accounting.period_reopen SET status = ?, ended_by = ?, ended_by_type = ?, ended_at = clock_timestamp(), end_reason = ?"
            + " WHERE id = ? AND status = 'PROPOSED'";

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
    public java.util.Map<CloseCheckCode, FrozenAttempt> frozenAttempts(Connection unitOfWork, PeriodCloseId id) {
        try (PreparedStatement read = unitOfWork.prepareStatement(FROZEN_ATTEMPTS_SQL)) {
            read.setObject(1, id.value());
            java.util.Map<CloseCheckCode, FrozenAttempt> attempts = new java.util.EnumMap<>(CloseCheckCode.class);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    attempts.put(CloseCheckCode.valueOf(row.getString(1)), new FrozenAttempt(row.getString(2), row.getTimestamp(3).toInstant()));
                }
            }
            return attempts;
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
    public Optional<ReopenRow> pendingReopen(Connection unitOfWork, AccountingPeriodId period) {
        return oneReopen(unitOfWork, PENDING_REOPEN_SQL, period, Optional.empty(), "reading a pending reopen proposal");
    }

    @Override
    public Optional<ReopenRow> lockPendingReopen(Connection unitOfWork, AccountingPeriodId period) {
        return oneReopen(unitOfWork, LOCK_PENDING_REOPEN_SQL, period, Optional.empty(), "locking a pending reopen proposal");
    }

    @Override
    public Optional<ReopenRow> approvedReopenAt(Connection unitOfWork, AccountingPeriodId period, int revision) {
        return oneReopen(unitOfWork, APPROVED_REOPEN_AT_SQL, period, Optional.of(revision), "reading an approved reopen");
    }

    @Override
    public Optional<ReopenRow> endedReopen(Connection unitOfWork, AccountingPeriodId period, PeriodReopenId id) {
        return oneReopen(unitOfWork, ENDED_REOPEN_SQL, period, Optional.of(id.value()), "reading an ended reopen");
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

    @Override
    public boolean endReopen(Connection unitOfWork, PeriodReopenId id, PeriodRequestEnd end, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(END_REOPEN_SQL)) {
            update.setString(1, end.reopenStatus().name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("ending a reopen proposal", failure);
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

    /** One reopen row by the period and, when the statement names one, the revision or the reopen's id - each statement's own arbiter admits at most one. */
    private static Optional<ReopenRow> oneReopen(Connection unitOfWork, String sql, AccountingPeriodId period, Optional<Object> second, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, period.value());
            if (second.isPresent()) {
                read.setObject(2, second.get());
            }
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
                                instant(row.getTimestamp("approved_at")),
                                Optional.ofNullable(row.getString("ended_by")),
                                instant(row.getTimestamp("ended_at"))))
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
