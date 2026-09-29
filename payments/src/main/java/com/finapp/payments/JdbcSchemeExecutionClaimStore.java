package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** {@link SchemeExecutionClaimStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcSchemeExecutionClaimStore implements SchemeExecutionClaimStore<Connection> {

    @Override
    public SchemeExecutionClaim claim(Connection unitOfWork, SchemeExecutionClaim claim) {
        Objects.requireNonNull(claim, "claim must not be null");
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO payments.scheme_execution_claim"
                                    + " (rail, scheme_reference, subject_kind, subject_id,"
                                    + " claimed_at) VALUES (?, ?, ?, ?, ?)"
                                    // The arbiter: a concurrent claimant of the same key
                                    // waits on the in-progress row, then does nothing.
                                    + " ON CONFLICT (rail, scheme_reference) DO NOTHING")) {
                insert.setString(1, claim.rail().value());
                insert.setString(2, claim.schemeReference().value());
                insert.setString(3, claim.subject().name());
                insert.setObject(4, claim.subjectId());
                insert.setTimestamp(5, Timestamp.from(claim.claimedAt()));
                if (insert.executeUpdate() == 1) {
                    return claim;
                }
            }
            // Refused: the claim that stands. A fresh statement under READ COMMITTED sees the
            // winner committed (the conflict waited for it to end).
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT subject_kind, subject_id, claimed_at"
                                    + " FROM payments.scheme_execution_claim"
                                    + " WHERE rail = ? AND scheme_reference = ?")) {
                read.setString(1, claim.rail().value());
                read.setString(2, claim.schemeReference().value());
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        throw new PaymentsStorageException(
                                "a refused scheme-execution claim found no claimant on "
                                        + claim.rail().value()
                                        + ": the table is append-only, so one stands");
                    }
                    return new SchemeExecutionClaim(
                            claim.rail(),
                            claim.schemeReference(),
                            SchemeExecutionClaim.Subject.valueOf(row.getString("subject_kind")),
                            row.getObject("subject_id", UUID.class),
                            row.getTimestamp("claimed_at").toInstant());
                }
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "claiming a scheme execution on " + claim.rail().value(), failure));
        }
    }

    @Override
    public java.util.Optional<SchemeExecutionClaim> findByExecution(
            Connection unitOfWork, RailId rail, ProviderReference schemeReference) {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT subject_kind, subject_id, claimed_at"
                                + " FROM payments.scheme_execution_claim"
                                + " WHERE rail = ? AND scheme_reference = ?")) {
            read.setString(1, rail.value());
            read.setString(2, schemeReference.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(
                        new SchemeExecutionClaim(
                                rail,
                                schemeReference,
                                SchemeExecutionClaim.Subject.valueOf(
                                        row.getString("subject_kind")),
                                row.getObject("subject_id", UUID.class),
                                row.getTimestamp("claimed_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading a scheme-execution claim on " + rail.value(), failure));
        }
    }
}
