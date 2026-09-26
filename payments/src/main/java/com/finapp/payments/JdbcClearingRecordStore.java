package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for {@link ClearingRecord} over payments `V015` (`P7-TSK-005`).
 *
 * <p>The insert is {@code ON CONFLICT DO NOTHING}: the two unique arbiters — one record per
 * attempt, one per acquirer reference — decide the race, and a refused insert is an answer
 * ({@code false}), never an exception, because a duplicate clearing notice is the rail
 * behaving normally ({@code INV-IDEM-04}). Ten instances inserting one notice leave one row
 * and nine quiet refusals, with no lock taken beyond the insert's own.
 */
public final class JdbcClearingRecordStore implements ClearingRecordStore<Connection> {

    private static final String COLUMNS =
            "id, attempt_id, acquirer_reference, network_transaction_id, recorded_at";

    @Override
    public boolean insert(Connection unitOfWork, ClearingRecord fresh) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.clearing_record (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            insert.setObject(1, fresh.id().value());
            insert.setObject(2, fresh.attemptId().value());
            insert.setString(3, fresh.acquirerReference().value());
            insert.setString(4, fresh.networkTransactionId().value());
            insert.setTimestamp(5, Timestamp.from(fresh.recordedAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "recording the clearing of attempt " + fresh.attemptId(), failure));
        }
    }

    @Override
    public Optional<ClearingRecord> findForAttempt(
            Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.clearing_record"
                                + " WHERE attempt_id = ?")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new ClearingRecord(
                                ClearingRecordId.of(row.getObject("id", UUID.class)),
                                PaymentAttemptId.of(
                                        row.getObject("attempt_id", UUID.class)),
                                new ProviderReference(row.getString("acquirer_reference")),
                                new ProviderReference(
                                        row.getString("network_transaction_id")),
                                row.getTimestamp("recorded_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading the clearing record of attempt " + attempt, failure));
        }
    }
}
