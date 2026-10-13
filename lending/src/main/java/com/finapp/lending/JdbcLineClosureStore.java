package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LineClosureStore} over plain JDBC (`P11-TSK-023`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never the key.
 */
public final class JdbcLineClosureStore implements LineClosureStore {

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public Instant insert(Connection unitOfWork, LineClosureRequest request, CorrelationId correlation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.line_closure_request (id, loan_id, request_key, outcome, requested_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, statement_timestamp(), ?) RETURNING requested_at")) {
            int i = 0;
            insert.setObject(++i, request.id());
            insert.setObject(++i, request.loan().value());
            insert.setString(++i, request.requestKey());
            insert.setString(++i, request.outcome().name());
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a line closure request", failure);
        }
    }

    @Override
    public Optional<LineClosureRequest> findByLoan(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, request_key, outcome, requested_at FROM lending.line_closure_request WHERE loan_id = ?")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LineClosureRequest(
                        row.getObject("id", UUID.class),
                        loan,
                        row.getString("request_key"),
                        LineClosureOutcome.valueOf(row.getString("outcome")),
                        row.getTimestamp("requested_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw failed("reading a line closure request", failure);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
