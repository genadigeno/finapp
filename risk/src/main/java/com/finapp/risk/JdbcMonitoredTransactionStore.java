package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plain-JDBC storage for monitored transactions (`P13-TSK-019`; ADR-0033) over {@code risk.monitored_transaction}:
 * inserts only, matching the grants - the application role holds no {@code UPDATE} and no {@code DELETE} here, and this
 * class contains no statement the role could not run. {@code recorded_at} is the trigger's (the database clock), so the
 * insert does not name it.
 *
 * <p><strong>The monitoring rules' reads</strong> (`P13-TSK-020`, {@link MonitoredMovementStore}): a party's movements
 * by its accounts - the subject side through V004's {@code monitored_transaction_by_subject}, the customer-counterparty
 * side (inverted) through {@code risk V015}'s {@code monitored_transaction_by_counterparty} - SELECT only.
 */
public final class JdbcMonitoredTransactionStore implements MonitoredTransactionStore, MonitoredMovementStore {

    private static final String TABLE = "risk.monitored_transaction";

    /** The record's corrections, which no party's readings count - {@link MonitoredMovement#COUNTED}'s complement. */
    private static final String CORRECTIONS = EnumSet.complementOf(EnumSet.copyOf(MonitoredMovement.COUNTED)).stream()
            .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));

    static final String MOVEMENTS = "SELECT journal_entry_id, journal_sequence, kind, seen, amount_minor, scale,"
            + " currency, value_date FROM ("
            // The subject side: the party's own account moved, the direction as stored.
            + "SELECT journal_entry_id, journal_sequence, kind, direction AS seen, amount_minor, scale, currency,"
            + " value_date FROM " + TABLE
            + " WHERE subject_kind = 'ACCOUNT' AND subject_ref = ANY (?) AND value_date >= ? AND value_date < ?"
            + " AND kind NOT IN (" + CORRECTIONS + ")"
            + " AND NOT COALESCE(counterparty_kind = 'CUSTOMER' AND counterparty_ref = ANY (?), false)"
            + " UNION ALL "
            // The counterparty side: another customer's row, the party's account its other side - inverted.
            + "SELECT journal_entry_id, journal_sequence, kind,"
            + " CASE direction WHEN 'IN' THEN 'OUT' ELSE 'IN' END, amount_minor, scale, currency, value_date"
            + " FROM " + TABLE
            + " WHERE counterparty_kind = 'CUSTOMER' AND counterparty_ref = ANY (?) AND value_date >= ?"
            + " AND value_date < ? AND kind NOT IN (" + CORRECTIONS + ")"
            + " AND NOT COALESCE(subject_kind = 'ACCOUNT' AND subject_ref = ANY (?), false)"
            + ") movements ORDER BY value_date, journal_sequence, journal_entry_id LIMIT ?";

    // One index probe per account and side - each index's last entry below the date, never a scan of a history.
    static final String LAST_MOVEMENT = "SELECT max(last_date) FROM unnest(?::uuid[]) AS account(ref)"
            + " CROSS JOIN LATERAL ("
            + "(SELECT value_date AS last_date FROM " + TABLE + " WHERE subject_kind = 'ACCOUNT'"
            + " AND subject_ref = account.ref AND value_date < ? AND kind NOT IN (" + CORRECTIONS + ")"
            + " ORDER BY value_date DESC LIMIT 1)"
            + " UNION ALL "
            + "(SELECT value_date FROM " + TABLE + " WHERE counterparty_kind = 'CUSTOMER'"
            + " AND counterparty_ref = account.ref AND value_date < ? AND kind NOT IN (" + CORRECTIONS + ")"
            + " ORDER BY value_date DESC LIMIT 1)) latest";

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

    @Override
    public List<MonitoredMovement> movementsOf(Connection unitOfWork, Collection<UUID> accounts, LocalDate from,
            LocalDate until, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(until, "until");
        if (limit < 1) {
            throw new IllegalArgumentException("a read of movements is at least one row");
        }
        if (accounts.isEmpty() || !from.isBefore(until)) {
            return List.of();
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(MOVEMENTS)) {
            java.sql.Array refs = unitOfWork.createArrayOf("uuid", accounts.toArray());
            select.setArray(1, refs);
            select.setObject(2, from);
            select.setObject(3, until);
            select.setArray(4, refs);
            select.setArray(5, refs);
            select.setObject(6, from);
            select.setObject(7, until);
            select.setArray(8, refs);
            select.setInt(9, limit);
            List<MonitoredMovement> movements = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    movements.add(new MonitoredMovement(row.getObject("journal_entry_id", UUID.class),
                            row.getLong("journal_sequence"), MonitoredKind.valueOf(row.getString("kind")),
                            MonitoredDirection.valueOf(row.getString("seen")), row.getLong("amount_minor"),
                            row.getShort("scale"),
                            com.finapp.sharedkernel.money.CurrencyCode.of(row.getString("currency")),
                            row.getObject("value_date", LocalDate.class)));
                }
            }
            return List.copyOf(movements);
        } catch (SQLException failure) {
            throw failed("reading a party's monitored movements", failure);
        }
    }

    @Override
    public Optional<LocalDate> lastMovementBefore(Connection unitOfWork, Collection<UUID> accounts, LocalDate before) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(before, "before");
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(LAST_MOVEMENT)) {
            select.setArray(1, unitOfWork.createArrayOf("uuid", accounts.toArray()));
            select.setObject(2, before);
            select.setObject(3, before);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Optional.ofNullable(row.getObject(1, LocalDate.class));
            }
        } catch (SQLException failure) {
            throw failed("reading a party's last monitored movement", failure);
        }
    }

    static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
