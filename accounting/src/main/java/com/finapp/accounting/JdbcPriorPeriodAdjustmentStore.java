package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PriorPeriodAdjustmentStore} over plain JDBC (`P14-TSK-013`, ADR-0033; {@code JdbcPeriodCloseStore}'s shape): the arbiters are these statements and {@code accounting V009} beneath them - pinned by
 * {@code PriorPeriodAdjustmentStatementsTest} and proven against PostgreSQL by {@code PriorPeriodAdjustmentDatabaseTest} (owed, C5). Every stamp the database's clock; every failure its operation and SQLSTATE
 * only ({@link AccountingStorageException}), so no reason reaches a message.
 */
public final class JdbcPriorPeriodAdjustmentStore implements PriorPeriodAdjustmentStore {

    static final String INSERT_SQL = "INSERT INTO accounting.prior_period_adjustment (id, corrected_period_id, corrected_revision, target_period_id, posting_date,"
            + " adjustment_proposal_id, status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)";

    private static final String ROW = "SELECT a.id, a.corrected_period_id, c.period_start AS corrected_start, a.corrected_revision, a.target_period_id,"
            + " t.period_start AS target_start, a.posting_date, a.adjustment_proposal_id, a.journal_entry_id, a.status, a.proposed_by, a.proposed_at,"
            + " a.proposal_reason, a.decided_by, a.decided_at, a.correlation_id FROM accounting.prior_period_adjustment a"
            + " JOIN accounting.accounting_period c ON c.id = a.corrected_period_id"
            + " JOIN accounting.accounting_period t ON t.id = a.target_period_id";

    static final String FIND_SQL = ROW + " WHERE a.id = ?";

    /** The row alone: the two periods are the caller's, {@code FOR SHARE} and first, or not at all for a rejection. */
    static final String LOCK_SQL = ROW + " WHERE a.id = ? FOR UPDATE OF a";

    /** In id order, so two approvals of one month take the same rows in the same order; a row {@code REJECTED} while this waited drops out of the result. */
    static final String LOCK_CORRECTIONS_SQL = ROW + " WHERE a.corrected_period_id = ? AND a.status <> 'REJECTED' ORDER BY a.id FOR UPDATE OF a";

    static final String OF_CORRECTED_SQL = ROW + " WHERE a.corrected_period_id = ? ORDER BY a.id";

    /** Selected in the statement: the filter is the database's, before the {@code LIMIT}. */
    static final String PENDING_SQL = ROW + " WHERE a.status = 'PROPOSED' ORDER BY a.proposed_at, a.id LIMIT ?";

    static final String POST_SQL = "UPDATE accounting.prior_period_adjustment SET status = 'POSTED', journal_entry_id = ?, decided_by = ?, decided_by_type = ?,"
            + " decided_at = clock_timestamp(), decision_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    static final String REJECT_SQL = "UPDATE accounting.prior_period_adjustment SET status = 'REJECTED', decided_by = ?, decided_by_type = ?,"
            + " decided_at = clock_timestamp(), decision_reason = ? WHERE id = ? AND status = 'PROPOSED'";

    @Override
    public void insert(Connection unitOfWork, NewAdjustment adjustment) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            insert.setObject(1, adjustment.id().value());
            insert.setObject(2, adjustment.corrected().value());
            insert.setInt(3, adjustment.correctedRevision());
            insert.setObject(4, adjustment.target().value());
            insert.setDate(5, Date.valueOf(adjustment.postingDate()));
            insert.setObject(6, adjustment.proposal());
            insert.setString(7, adjustment.proposedBy().id());
            insert.setString(8, adjustment.proposedBy().type().name());
            insert.setString(9, adjustment.reason());
            insert.setString(10, adjustment.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a prior-period adjustment", failure);
        }
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, PriorPeriodAdjustmentId id) {
        return one(unitOfWork, FIND_SQL, id, "reading a prior-period adjustment");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, PriorPeriodAdjustmentId id) {
        return one(unitOfWork, LOCK_SQL, id, "locking a prior-period adjustment");
    }

    @Override
    public List<Row> lockCorrectionsOf(Connection unitOfWork, AccountingPeriodId corrected) {
        return ofPeriod(unitOfWork, LOCK_CORRECTIONS_SQL, corrected, "locking a month's prior-period adjustments");
    }

    @Override
    public List<Row> ofCorrected(Connection unitOfWork, AccountingPeriodId corrected) {
        return ofPeriod(unitOfWork, OF_CORRECTED_SQL, corrected, "reading a month's prior-period adjustments");
    }

    @Override
    public List<Row> pending(Connection unitOfWork, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(PENDING_SQL)) {
            read.setInt(1, limit);
            return rows(read);
        } catch (SQLException failure) {
            throw failed("reading the prior-period adjustments standing", failure);
        }
    }

    @Override
    public boolean markPosted(Connection unitOfWork, PriorPeriodAdjustmentId id, UUID entry, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(POST_SQL)) {
            update.setObject(1, entry);
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("posting a prior-period adjustment", failure);
        }
    }

    @Override
    public boolean markRejected(Connection unitOfWork, PriorPeriodAdjustmentId id, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(REJECT_SQL)) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("rejecting a prior-period adjustment", failure);
        }
    }

    private static Optional<Row> one(Connection unitOfWork, String sql, PriorPeriodAdjustmentId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            List<Row> rows = rows(read);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static List<Row> ofPeriod(Connection unitOfWork, String sql, AccountingPeriodId period, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, period.value());
            return rows(read);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static List<Row> rows(PreparedStatement read) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet row = read.executeQuery()) {
            while (row.next()) {
                rows.add(new Row(
                        PriorPeriodAdjustmentId.of(row.getObject("id", UUID.class)),
                        AccountingPeriodId.of(row.getObject("corrected_period_id", UUID.class)),
                        YearMonth.from(row.getDate("corrected_start").toLocalDate()),
                        row.getInt("corrected_revision"),
                        AccountingPeriodId.of(row.getObject("target_period_id", UUID.class)),
                        YearMonth.from(row.getDate("target_start").toLocalDate()),
                        row.getDate("posting_date").toLocalDate(),
                        row.getObject("adjustment_proposal_id", UUID.class),
                        Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)),
                        PriorPeriodAdjustmentStatus.valueOf(row.getString("status")),
                        row.getString("proposed_by"),
                        row.getTimestamp("proposed_at").toInstant(),
                        row.getString("proposal_reason"),
                        Optional.ofNullable(row.getString("decided_by")),
                        instant(row.getTimestamp("decided_at")),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        }
        return rows;
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
