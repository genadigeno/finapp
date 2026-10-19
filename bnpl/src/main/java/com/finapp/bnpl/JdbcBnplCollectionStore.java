package com.finapp.bnpl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link BnplCollectionStore} over plain JDBC (`P12-TSK-019`, ADR-0033; lending's {@code JdbcRepaymentStore}'s attempts):
 * every instant the database's - {@code bnpl V011}'s trigger stamps it after the act's locks - every failure its operation
 * and SQLSTATE only ({@link BnplStorageException}) - never an amount or a party.
 */
public final class JdbcBnplCollectionStore implements BnplCollectionStore {

    @Override
    public Map<UUID, LocalDate> latestAttempts(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // An obligation the caller holds FOR UPDATE (B4): the servicing sweep's claimed step.
                "SELECT instalment_id, max(attempt_date) AS latest FROM bnpl.bnpl_collection_attempt"
                        + " WHERE obligation_id = ? GROUP BY instalment_id")) {
            select.setObject(1, obligation.value());
            Map<UUID, LocalDate> latest = new LinkedHashMap<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    latest.put(rows.getObject("instalment_id", UUID.class), rows.getDate("latest").toLocalDate());
                }
            }
            return latest;
        } catch (SQLException failure) {
            throw failed("reading an obligation's collection attempts", failure);
        }
    }

    @Override
    public void insertAttempt(Connection unitOfWork, BnplCollectionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's, clock_timestamp() after B4.
                "INSERT INTO bnpl.bnpl_collection_attempt (id, obligation_id, instalment_id, attempt_date, outcome,"
                        + " currency, scale, due_minor, collected_minor, agreement_version, repayment_id, created_at,"
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, attempt.id());
            insert.setObject(++i, attempt.obligation().value());
            insert.setObject(++i, attempt.instalment());
            insert.setObject(++i, attempt.attemptDate());
            insert.setString(++i, attempt.outcome().name());
            insert.setString(++i, attempt.due().currency().code());
            insert.setInt(++i, attempt.due().scale());
            insert.setLong(++i, attempt.due().minorUnits());
            insert.setLong(++i, attempt.collected().minorUnits());
            insert.setInt(++i, attempt.agreementVersion());
            if (attempt.repayment().isPresent()) {
                insert.setObject(++i, attempt.repayment().get());
            } else {
                insert.setNull(++i, Types.OTHER);
            }
            insert.setString(++i, attempt.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL collection attempt", failure);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
