package com.finapp.crossborder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link CancellationStore} over JDBC (`P9-TSK-024`; ADR-0033: explicit SQL, no ORM). */
public final class JdbcCancellationStore implements CancellationStore {

    @Override
    public boolean insert(Connection unitOfWork, UUID id, UUID paymentId, String requestedBy, String correlationId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.cancellation_request (id, payment_id, requested_by, correlation_id, requested_at)"
                        + " VALUES (?, ?, ?, ?, statement_timestamp()) ON CONFLICT (payment_id) DO NOTHING")) {
            insert.setObject(1, id);
            insert.setObject(2, paymentId);
            insert.setString(3, requestedBy);
            insert.setString(4, correlationId);
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException("could not record a payment's cancellation request", failure);
        }
    }

    @Override
    public Optional<Instant> requestedAt(Connection unitOfWork, UUID paymentId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT requested_at FROM crossborder.cancellation_request WHERE payment_id = ?")) {
            select.setObject(1, paymentId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException("could not read a payment's cancellation request", failure);
        }
    }
}
