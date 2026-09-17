package com.finapp.transfers;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** {@link TransferStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcTransferStore implements TransferStore<Connection> {

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
}
