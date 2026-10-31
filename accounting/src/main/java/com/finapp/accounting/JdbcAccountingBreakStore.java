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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link AccountingBreakStore} over plain JDBC (`P14-TSK-010`, ADR-0033; {@code JdbcAccountingPeriodStore}'s shape): the
 * birth an {@code INSERT ... ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING}, every move a conditional
 * {@code UPDATE} of the act columns under {@code accounting V006}'s edge trigger, the case file INSERT-only, every time the
 * database's, every failure its operation and SQLSTATE only ({@link AccountingStorageException}). Reasons are written,
 * never read back.
 */
public final class JdbcAccountingBreakStore implements AccountingBreakStore {

    private static final String ROW = "SELECT id, period_start, check_code, break_type, subject_ref, evidence, judged_by,"
            + " status, raised_at, ack_proposed_by, ack_proposed_at, disclosure, ack_approved_by, ack_approved_at,"
            + " resolved_by, resolved_at, resolving_entry_id FROM accounting.accounting_break";

    @Override
    public boolean insertIfAbsent(Connection unitOfWork, NewBreak born) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.accounting_break (id,"
                + " period_start, check_code, break_type, subject_ref, evidence, judged_by, status, raised_at, raised_by,"
                + " raised_by_type, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', clock_timestamp(), ?, ?, ?)"
                + " ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING")) {
            insert.setObject(1, born.id().value());
            insert.setDate(2, Date.valueOf(born.period().atDay(1)));
            insert.setString(3, born.finding().check().name());
            insert.setString(4, born.finding().type().name());
            insert.setString(5, born.finding().subject());
            insert.setString(6, born.finding().evidence());
            insert.setString(7, born.finding().judgedBy());
            insert.setString(8, born.raisedBy().id());
            insert.setString(9, born.raisedBy().type().name());
            insert.setString(10, born.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("raising an accounting break", failure);
        }
    }

    @Override
    public Optional<BreakRow> find(Connection unitOfWork, AccountingBreakId id) {
        return byId(unitOfWork, ROW + " WHERE id = ?", id, "reading an accounting break");
    }

    @Override
    public Optional<BreakRow> lock(Connection unitOfWork, AccountingBreakId id) {
        return byId(unitOfWork, ROW + " WHERE id = ? FOR UPDATE", id, "locking an accounting break");
    }

    @Override
    public List<BreakRow> list(Connection unitOfWork, YearMonth period, Optional<BreakStatus> status,
            Optional<AccountingBreakId> after, int limit) {
        String sql = ROW + " WHERE period_start = ?" + (status.isPresent() ? " AND status = ?" : "")
                + (after.isPresent() ? " AND id > ?" : "") + " ORDER BY id LIMIT ?";
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            int at = 1;
            read.setDate(at++, Date.valueOf(period.atDay(1)));
            if (status.isPresent()) {
                read.setString(at++, status.get().name());
            }
            if (after.isPresent()) {
                read.setObject(at++, after.get().value());
            }
            read.setInt(at, limit);
            List<BreakRow> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(row(row));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw failed("reading a period's accounting breaks", failure);
        }
    }

    @Override
    public Map<String, BreakStatus> held(Connection unitOfWork, YearMonth period, CloseCheckCode check) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT subject_ref, status FROM"
                + " accounting.accounting_break WHERE period_start = ? AND check_code = ?")) {
            read.setDate(1, Date.valueOf(period.atDay(1)));
            read.setString(2, check.name());
            Map<String, BreakStatus> subjects = new TreeMap<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    subjects.put(row.getString(1), BreakStatus.valueOf(row.getString(2)));
                }
            }
            return subjects;
        } catch (SQLException failure) {
            throw failed("reading a period's raised subjects", failure);
        }
    }

    @Override
    public Map<BreakType, Long> openByType(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT break_type, count(*) FROM"
                + " accounting.accounting_break WHERE status <> 'RESOLVED' GROUP BY break_type");
                ResultSet row = read.executeQuery()) {
            Map<BreakType, Long> open = new EnumMap<>(BreakType.class);
            while (row.next()) {
                open.put(BreakType.valueOf(row.getString(1)), row.getLong(2));
            }
            return open;
        } catch (SQLException failure) {
            throw failed("counting the open accounting breaks", failure);
        }
    }

    @Override
    public boolean proposeAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String disclosure) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_break"
                + " SET ack_proposed_by = ?, ack_proposed_by_type = ?, ack_proposed_at = clock_timestamp(), disclosure = ?"
                + " WHERE id = ? AND status = 'OPEN' AND ack_proposed_by IS NULL")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, disclosure);
            update.setObject(4, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("proposing an accounting break's acknowledgement", failure);
        }
    }

    @Override
    public boolean approveAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_break"
                + " SET status = 'ACKNOWLEDGED', ack_approved_by = ?, ack_approved_by_type = ?,"
                + " ack_approved_at = clock_timestamp(), ack_approval_reason = ?"
                + " WHERE id = ? AND status = 'OPEN' AND ack_proposed_by IS NOT NULL AND ack_approved_by IS NULL")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("approving an accounting break's acknowledgement", failure);
        }
    }

    @Override
    public boolean resolve(Connection unitOfWork, AccountingBreakId id, BreakStatus from, Actor actor,
            UUID correctingEntry, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.accounting_break"
                + " SET status = 'RESOLVED', resolved_by = ?, resolved_by_type = ?, resolved_at = clock_timestamp(),"
                + " resolving_entry_id = ?, resolution_reason = ? WHERE id = ? AND status = ?")) {
            update.setString(1, actor.id());
            update.setString(2, actor.type().name());
            update.setObject(3, correctingEntry);
            update.setString(4, reason);
            update.setObject(5, id.value());
            update.setString(6, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("resolving an accounting break", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, EventRow row) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.break_event (id, break_id,"
                + " act, from_status, to_status, actor, actor_type, proposed_by, resolving_entry_id, reason, occurred_at,"
                + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, row.id());
            insert.setObject(2, row.breakId().value());
            insert.setString(3, row.act().name());
            insert.setString(4, row.from().map(Enum::name).orElse(null));
            insert.setString(5, row.to().name());
            insert.setString(6, row.actor().id());
            insert.setString(7, row.actor().type().name());
            insert.setString(8, row.proposedBy().orElse(null));
            insert.setObject(9, row.resolvingEntry().orElse(null));
            insert.setString(10, row.reason().orElse(null));
            insert.setString(11, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending an accounting break's case file", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<BreakRow> byId(Connection unitOfWork, String sql, AccountingBreakId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(row(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static BreakRow row(ResultSet row) throws SQLException {
        return new BreakRow(
                AccountingBreakId.of(row.getObject("id", UUID.class)),
                YearMonth.from(row.getDate("period_start").toLocalDate()),
                CloseCheckCode.valueOf(row.getString("check_code")),
                BreakType.valueOf(row.getString("break_type")),
                row.getString("subject_ref"),
                row.getString("evidence"),
                row.getString("judged_by"),
                BreakStatus.valueOf(row.getString("status")),
                row.getTimestamp("raised_at").toInstant(),
                Optional.ofNullable(row.getString("ack_proposed_by")),
                instant(row.getTimestamp("ack_proposed_at")),
                Optional.ofNullable(row.getString("disclosure")),
                Optional.ofNullable(row.getString("ack_approved_by")),
                instant(row.getTimestamp("ack_approved_at")),
                Optional.ofNullable(row.getString("resolved_by")),
                instant(row.getTimestamp("resolved_at")),
                Optional.ofNullable(row.getObject("resolving_entry_id", UUID.class)));
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
