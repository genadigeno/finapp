package com.finapp.transfers;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link TransferStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcTransferStore implements TransferStore<Connection> {

    private static final String COLUMNS =
            "id, customer_id, source_account_id, destination_account_id, amount_minor,"
                    + " currency, scale, reference, status, failure_reason, journal_entry_id,"
                    + " reversal_entry_id, reversed_by, reversed_at, initiated_by, initiated_at";

    @Override
    public void insert(Connection unitOfWork, Transfer transfer) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO transfers.transfer (id, customer_id, source_account_id,"
                                + " destination_account_id, amount_minor, currency, scale,"
                                + " reference, status, failure_reason, journal_entry_id,"
                                + " reversal_entry_id, reversed_by, reversed_at, initiated_by,"
                                + " initiated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, transfer.id().value());
            insert.setObject(2, transfer.customerId());
            insert.setObject(3, transfer.sourceAccount().value());
            insert.setObject(4, transfer.destinationAccount().value());
            insert.setLong(5, transfer.amount().minorUnits());
            insert.setString(6, transfer.amount().currency().code());
            insert.setShort(7, (short) transfer.amount().scale());
            insert.setString(8, transfer.reference());
            insert.setString(9, transfer.status().name());
            insert.setString(
                    10, transfer.failureReason() == null ? null : transfer.failureReason().name());
            insert.setObject(
                    11, transfer.journalEntryId() == null ? null : transfer.journalEntryId().value());
            insert.setObject(
                    12,
                    transfer.reversalEntryId() == null ? null : transfer.reversalEntryId().value());
            insert.setObject(13, transfer.reversedBy());
            insert.setTimestamp(
                    14,
                    transfer.reversedAt() == null ? null : Timestamp.from(transfer.reversedAt()));
            insert.setObject(15, transfer.initiatedBy());
            insert.setTimestamp(16, Timestamp.from(transfer.initiatedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe("inserting transfer " + transfer.id(), failure));
        }
    }

    @Override
    public void recordTransition(
            Connection unitOfWork,
            Transfer transfer,
            TransferStatus from,
            UUID actorId,
            Instant occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO transfers.transfer_event"
                                + " (transfer_id, from_status, to_status, actor_id, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, transfer.id().value());
            insert.setString(2, from.name());
            insert.setString(3, transfer.status().name());
            insert.setObject(4, actorId);
            insert.setTimestamp(5, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe(
                            "recording the transition of transfer " + transfer.id(), failure));
        }
    }

    @Override
    public Optional<Transfer> findById(Connection unitOfWork, TransferId transfer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(transfer, "transfer must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM transfers.transfer WHERE id = ?")) {
            read.setObject(1, transfer.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe("reading transfer " + transfer, failure));
        }
    }

    @Override
    public Optional<Transfer> findOwned(
            Connection unitOfWork, TransferId transfer, UUID customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(transfer, "transfer must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // customer_id = ? IS the ownership check, in the statement (ADR-0031).
                        "SELECT " + COLUMNS + " FROM transfers.transfer"
                                + " WHERE id = ? AND customer_id = ?")) {
            read.setObject(1, transfer.value());
            read.setObject(2, customerId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe("reading an owned transfer", failure));
        }
    }

    @Override
    public List<Transfer> listFor(Connection unitOfWork, UUID customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Newest first (PHASE_4_PLAN.md section 9); the id breaks the tie of two
                        // transfers initiated in the same instant deterministically.
                        "SELECT " + COLUMNS + " FROM transfers.transfer"
                                + " WHERE customer_id = ?"
                                + " ORDER BY initiated_at DESC, id DESC")) {
            read.setObject(1, customerId);
            try (ResultSet rows = read.executeQuery()) {
                List<Transfer> owned = new ArrayList<>();
                while (rows.next()) {
                    owned.add(rehydrate(rows));
                }
                return List.copyOf(owned);
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe(
                            "listing the transfers of customer " + customerId, failure));
        }
    }

    private static Transfer rehydrate(ResultSet row) throws SQLException {
        UUID entryId = row.getObject("journal_entry_id", UUID.class);
        UUID reversalEntryId = row.getObject("reversal_entry_id", UUID.class);
        String reason = row.getString("failure_reason");
        Timestamp reversedAt = row.getTimestamp("reversed_at");
        return Transfer.rehydrate(
                TransferId.of(row.getObject("id", UUID.class)),
                row.getObject("customer_id", UUID.class),
                LedgerAccountId.of(row.getObject("source_account_id", UUID.class)),
                LedgerAccountId.of(row.getObject("destination_account_id", UUID.class)),
                // The STORED scale, never re-derived from the currency (INV-MON-05): a
                // historical row must read back as written whatever the currency data says now.
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency")),
                        row.getShort("scale")),
                row.getString("reference"),
                TransferStatus.valueOf(row.getString("status")),
                reason == null ? null : FailureReason.valueOf(reason),
                entryId == null ? null : JournalEntryId.of(entryId),
                reversalEntryId == null ? null : JournalEntryId.of(reversalEntryId),
                row.getObject("reversed_by", UUID.class),
                reversedAt == null ? null : reversedAt.toInstant(),
                row.getObject("initiated_by", UUID.class),
                row.getTimestamp("initiated_at").toInstant());
    }
}
