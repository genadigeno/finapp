package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

/**
 * Plain-JDBC storage for KYC cases (ADR-0033).
 *
 * <p><strong>Isolation this relies on:</strong> PostgreSQL's default {@code READ COMMITTED}. A
 * second insert against the one-open-case index <em>blocks</em> until the first transaction
 * ends, then reports a unique violation if it committed — so the database, not this code,
 * arbitrates between two instances opening for the same customer. The wait is bounded by the
 * winner's insert-to-commit interval, which is one short transaction for every caller this
 * store will have.
 */
public final class JdbcKycCaseStore implements KycCaseStore<Connection> {

    private static final String TABLE = "kyc.kyc_case";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes - the audit record, the outbox row - could
            // not follow a lost race, and the retry would re-run the command rather than
            // converge.
            Savepoint beforeInsert = unitOfWork.setSavepoint("kyc_case_open");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Opening(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return findOpenFor(unitOfWork, fresh.customerId())
                        .map(existing -> new Opening(existing, false))
                        .orElseThrow(
                                () ->
                                        new KycStorageException(
                                                "the open-case index refused an insert but no"
                                                        + " open case is visible for the"
                                                        + " customer - a concurrent opener may"
                                                        + " have rolled back; retry"));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "opening a KYC case for customer " + fresh.customerId(), failure));
        }
    }

    private static void insert(Connection unitOfWork, KycCase kycCase) throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, customer_id, status, policy_version, opened_at,"
                                + " status_changed_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, kycCase.id().value());
            insert.setObject(2, kycCase.customerId());
            insert.setString(3, kycCase.status().name());
            insert.setString(4, kycCase.policyVersion().value());
            insert.setTimestamp(5, Timestamp.from(kycCase.openedAt()));
            insert.setTimestamp(6, Timestamp.from(kycCase.statusChangedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        // The predicate is the index's own (status NOT IN the terminal set), so "the open case"
        // and "the case the index guards" cannot be two different questions.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, customer_id, status, policy_version, opened_at,"
                                + " status_changed_at FROM " + TABLE
                                + " WHERE customer_id = ? AND status NOT IN ("
                                + KycCaseStatus.sqlTerminalValueList() + ")")) {
            select.setObject(1, customerId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "reading the open KYC case of customer " + customerId, failure));
        }
    }

    @Override
    public boolean moveStatus(
            Connection unitOfWork, KycCaseId caseId, KycCaseStatus from, KycCaseStatus to, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, caseId.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "moving KYC case " + caseId + " from " + from + " to " + to, failure));
        }
    }

    private static KycCase rehydrate(ResultSet row) throws SQLException {
        return KycCase.rehydrate(
                KycCaseId.of(row.getObject("id", UUID.class)),
                row.getObject("customer_id", UUID.class),
                KycCaseStatus.valueOf(row.getString("status")),
                new KycPolicyVersion(row.getString("policy_version")),
                row.getTimestamp("opened_at").toInstant(),
                row.getTimestamp("status_changed_at").toInstant());
    }
}
