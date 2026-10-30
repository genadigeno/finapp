package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
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
 * {@link AccountingPeriodStore} over plain JDBC (`P14-TSK-005`, ADR-0033; {@code JdbcGlMappingStore}'s shape): every time
 * the database's, every move a conditional {@code UPDATE} of the machine row's act columns under {@code accounting V003}'s
 * edge trigger, the history INSERT-only, every failure its operation and SQLSTATE only ({@link AccountingStorageException}).
 * The pinned version's number is read beside the row (a same-schema join) so no view names a version by id alone.
 */
public final class JdbcAccountingPeriodStore implements AccountingPeriodStore {

    private static final String ROW = "SELECT p.id, p.period_start, p.status, p.revision, p.opened_at, p.locked_at,"
            + " p.pinned_mapping_version_id, v.version AS pinned_version, p.unlock_proposed_by, p.unlock_proposed_at,"
            + " p.unlock_approved_by, p.unlock_approved_at FROM accounting.accounting_period p"
            + " LEFT JOIN accounting.gl_mapping_version v ON v.id = p.pinned_mapping_version_id";

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewPeriod period) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.accounting_period (id,"
                + " period_start, period_end, status, revision, opened_at, correlation_id)"
                + " VALUES (?, ?, ?, 'OPEN', 1, clock_timestamp(), ?) ON CONFLICT (period_start) DO NOTHING")) {
            insert.setObject(1, period.id().value());
            insert.setDate(2, Date.valueOf(period.month().atDay(1)));
            insert.setDate(3, Date.valueOf(period.month().atEndOfMonth()));
            insert.setString(4, period.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("opening an accounting period", failure);
        }
    }

    @Override
    public Optional<YearMonth> latest(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement(
                        "SELECT max(period_start) FROM accounting.accounting_period");
                ResultSet row = read.executeQuery()) {
            row.next();
            Date latest = row.getDate(1);
            return latest == null ? Optional.empty() : Optional.of(YearMonth.from(latest.toLocalDate()));
        } catch (SQLException failure) {
            throw failed("reading the latest accounting period", failure);
        }
    }

    @Override
    public Optional<PeriodRow> find(Connection unitOfWork, AccountingPeriodId id) {
        return byId(unitOfWork, ROW + " WHERE p.id = ?", id, "reading an accounting period");
    }

    @Override
    public Optional<PeriodRow> lock(Connection unitOfWork, AccountingPeriodId id) {
        return byId(unitOfWork, ROW + " WHERE p.id = ? FOR UPDATE OF p", id, "locking an accounting period");
    }

    @Override
    public Optional<PeriodRow> lockShared(Connection unitOfWork, YearMonth month) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " WHERE p.period_start = ? FOR SHARE OF p")) {
            read.setDate(1, Date.valueOf(month.atDay(1)));
            return first(read);
        } catch (SQLException failure) {
            throw failed("locking the previous accounting period", failure);
        }
    }

    @Override
    public Optional<PeriodRow> firstOpen(Connection unitOfWork) {
        return unparameterised(unitOfWork, ROW + " WHERE p.status = 'OPEN' ORDER BY p.period_start LIMIT 1",
                "reading the first open accounting period");
    }

    @Override
    public Optional<PeriodRow> oldestUnclosed(Connection unitOfWork) {
        return unparameterised(unitOfWork, ROW + " WHERE p.status <> 'CLOSED' ORDER BY p.period_start LIMIT 1",
                "reading the oldest unclosed accounting period");
    }

    @Override
    public List<PeriodRow> recent(Connection unitOfWork, int limit) {
        try (PreparedStatement read = unitOfWork.prepareStatement(ROW + " ORDER BY p.period_start DESC LIMIT ?")) {
            read.setInt(1, limit);
            List<PeriodRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(row(row));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed("reading the accounting periods", failure);
        }
    }

    @Override
    public boolean markLocked(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, Optional<Pin> pin) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_period"
                + " SET status = 'LOCKED', locked_by = ?, locked_by_type = ?, locked_at = clock_timestamp(),"
                + " pinned_mapping_version_id = ?, unlock_proposed_by = NULL, unlock_proposed_by_type = NULL,"
                + " unlock_proposed_at = NULL, unlock_proposal_reason = NULL, unlock_approved_by = NULL,"
                + " unlock_approved_by_type = NULL, unlock_approved_at = NULL, unlock_approval_reason = NULL"
                + " WHERE id = ? AND status = 'OPEN' AND revision = ?")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setObject(3, pin.map(p -> p.id().value()).orElse(null));
            update.setObject(4, id.value());
            update.setInt(5, revision);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("locking an accounting period's edge", failure);
        }
    }

    @Override
    public boolean proposeUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_period"
                + " SET unlock_proposed_by = ?, unlock_proposed_by_type = ?, unlock_proposed_at = clock_timestamp(),"
                + " unlock_proposal_reason = ? WHERE id = ? AND status = 'LOCKED' AND revision = ?"
                + " AND unlock_proposed_by IS NULL")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            update.setInt(5, revision);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("proposing an accounting period's unlock", failure);
        }
    }

    @Override
    public boolean approveUnlock(Connection unitOfWork, AccountingPeriodId id, int revision, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_period"
                + " SET status = 'OPEN', locked_by = NULL, locked_by_type = NULL, locked_at = NULL,"
                + " pinned_mapping_version_id = NULL, unlock_approved_by = ?, unlock_approved_by_type = ?,"
                + " unlock_approved_at = clock_timestamp(), unlock_approval_reason = ?"
                + " WHERE id = ? AND status = 'LOCKED' AND revision = ? AND unlock_proposed_by IS NOT NULL"
                + " AND unlock_approved_by IS NULL")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            update.setInt(5, revision);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("approving an accounting period's unlock", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow row) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.period_event (id, period_id,"
                + " act, from_status, to_status, revision, actor, actor_type, proposed_by, pinned_mapping_version_id,"
                + " reason, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, row.id());
            insert.setObject(2, row.periodId().value());
            insert.setString(3, row.act().name());
            insert.setString(4, row.from().map(Enum::name).orElse(null));
            insert.setString(5, row.to().name());
            insert.setInt(6, row.revision());
            insert.setString(7, row.actor().id());
            insert.setString(8, row.actor().type().name());
            insert.setString(9, row.proposedBy().orElse(null));
            insert.setObject(10, row.pinned().map(GlMappingVersionId::value).orElse(null));
            insert.setString(11, row.reason().orElse(null));
            insert.setString(12, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending accounting period history", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<PeriodRow> byId(Connection unitOfWork, String sql, AccountingPeriodId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            return first(read);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<PeriodRow> unparameterised(Connection unitOfWork, String sql, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            return first(read);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<PeriodRow> first(PreparedStatement read) throws SQLException {
        try (ResultSet row = read.executeQuery()) {
            return row.next() ? Optional.of(row(row)) : Optional.empty();
        }
    }

    private static PeriodRow row(ResultSet row) throws SQLException {
        UUID pinned = row.getObject("pinned_mapping_version_id", UUID.class);
        return new PeriodRow(
                AccountingPeriodId.of(row.getObject("id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                PeriodStatus.valueOf(row.getString("status")),
                row.getInt("revision"),
                row.getTimestamp("opened_at").toInstant(),
                instant(row.getTimestamp("locked_at")),
                pinned == null ? Optional.empty()
                        : Optional.of(new Pin(GlMappingVersionId.of(pinned), row.getInt("pinned_version"))),
                Optional.ofNullable(row.getString("unlock_proposed_by")),
                instant(row.getTimestamp("unlock_proposed_at")),
                Optional.ofNullable(row.getString("unlock_approved_by")),
                instant(row.getTimestamp("unlock_approved_at")));
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
