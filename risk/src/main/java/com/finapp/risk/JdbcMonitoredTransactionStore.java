package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * Plain-JDBC storage for monitored transactions (`P13-TSK-019`; ADR-0033) over {@code risk.monitored_transaction}:
 * inserts only, matching the grants - the application role holds no {@code UPDATE} and no {@code DELETE} here, and this
 * class contains no statement the role could not run. {@code recorded_at} is the trigger's (the database clock), so the
 * insert does not name it.
 */
public final class JdbcMonitoredTransactionStore implements MonitoredTransactionStore {

    private static final String TABLE = "risk.monitored_transaction";

    @Override
    public int insertAbsent(Connection unitOfWork, List<MonitoredTransaction> rows) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return 0;
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + TABLE + " (id, journal_entry_id, journal_sequence, entry_type, posting_namespace, kind,"
                        + " subject_kind, subject_ref, direction, counterparty_kind, counterparty_ref,"
                        + " counterparty_purpose, amount_minor, currency, scale, value_date, posted_at, recorded_at,"
                        + " reverses_journal_entry_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)"
                        // INV-RSK-09: born once; a row already present - another sweeper's, an earlier batch's, a
                        // reprocessing's - is absorbed, never rewritten.
                        + " ON CONFLICT (journal_entry_id) DO NOTHING")) {
            for (MonitoredTransaction row : rows) {
                insert.setObject(1, row.id().value());
                insert.setObject(2, row.journalEntryId());
                insert.setLong(3, row.journalSequence());
                insert.setString(4, row.entryType().name());
                insert.setString(5, row.postingNamespace());
                insert.setString(6, row.kind().name());
                insert.setString(7, row.subjectKind().map(Enum::name).orElse(null));
                insert.setObject(8, row.subjectRef().orElse(null));
                insert.setString(9, row.direction().map(Enum::name).orElse(null));
                insert.setString(10, row.counterpartyKind().name());
                insert.setObject(11, row.counterpartyRef().orElse(null));
                insert.setString(12, row.counterpartyPurpose().orElse(null));
                insert.setLong(13, row.amountMinor());
                insert.setString(14, row.currency().code());
                insert.setShort(15, (short) row.scale());
                insert.setObject(16, row.valueDate());
                insert.setTimestamp(17, Timestamp.from(row.postedAt()));
                insert.setObject(18, row.reversesJournalEntryId().orElse(null));
                insert.addBatch();
            }
            int born = 0;
            boolean uncounted = false;
            for (int count : insert.executeBatch()) {
                // DO NOTHING answers 0 for an absorbed row; a born row answers 1. A driver that rewrote the batch
                // answers SUCCESS_NO_INFO for every row, and then the rows are counted by their own fresh ids below.
                if (count == java.sql.Statement.SUCCESS_NO_INFO) {
                    uncounted = true;
                } else if (count > 0) {
                    born += count;
                }
            }
            return uncounted ? bornOf(unitOfWork, rows) : born;
        } catch (SQLException failure) {
            throw failed("inserting monitored transactions", failure);
        }
    }

    /** The rows this batch bore: those carrying the ids it minted - an absorbed row keeps its earlier id. */
    private static int bornOf(Connection unitOfWork, List<MonitoredTransaction> rows) throws SQLException {
        try (PreparedStatement count = unitOfWork.prepareStatement(
                "SELECT count(*) FROM " + TABLE + " WHERE id = ANY (?)")) {
            count.setArray(1, unitOfWork.createArrayOf("uuid", rows.stream().map(row -> row.id().value()).toArray()));
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    @Override
    public long countUnclassified(Connection unitOfWork) {
        return count(unitOfWork, "SELECT count(*) FROM " + TABLE + " WHERE kind = 'UNCLASSIFIED'",
                "counting unclassified monitored transactions");
    }

    @Override
    public long count(Connection unitOfWork) {
        return count(unitOfWork, "SELECT count(*) FROM " + TABLE, "counting monitored transactions");
    }

    private static long count(Connection unitOfWork, String sql, String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql); ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
