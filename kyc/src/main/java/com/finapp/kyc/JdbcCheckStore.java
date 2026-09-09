package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-JDBC storage for verification checks and their evidence (ADR-0033, `P2-TSK-009`).
 *
 * <p>Encryption of evidence happens here, the {@code JdbcDocumentStore} split: where bytes rest
 * is the adapter's concern, and the same {@link DocumentCipher} serves both because documents
 * and provider evidence are one at-rest concern (ADR-0036 groups them; one key per concern
 * means one key here, and {@code key_version} per row keeps a later split one rotation away).
 *
 * <h2>The convergence rule, and its one residual race</h2>
 *
 * <p>{@code requestOrConverge} converges on an existing check of the type in <em>any</em> state
 * — a run must not re-ask an answered question — while the partial unique index arbitrates the
 * concurrent-insert race for in-flight checks. The pre-flight read is the <em>business rule</em>
 * (one question per type), not a substitute for the index (`P1-TSK-006`'s distinction). The
 * residual: an instance reading just before another's terminal commit can insert a redundant
 * second question, because the terminal check has left the index. That race produces a wasted
 * provider call and an extra answer, <strong>never a wrong one</strong> — an extra {@code CLEAR}
 * changes no assessment and an extra {@code HIT} only blocks harder — which is the safe
 * direction, and why it is recorded rather than locked away.
 */
public final class JdbcCheckStore implements CheckStore<Connection> {

    private static final String TABLE = "kyc.verification_check";
    private static final String EVIDENCE_TABLE = "kyc.verification_evidence";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DocumentCipher cipher;

    public JdbcCheckStore(DocumentCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    @Override
    public Requested requestOrConverge(Connection unitOfWork, VerificationCheck fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            Optional<VerificationCheck> existing =
                    newestOfType(unitOfWork, fresh.caseId(), fresh.type());
            if (existing.isPresent()) {
                return new Requested(existing.get(), false);
            }
            Savepoint beforeInsert = unitOfWork.setSavepoint("verification_check_request");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Requested(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return newestOfType(unitOfWork, fresh.caseId(), fresh.type())
                        .map(winner -> new Requested(winner, false))
                        .orElseThrow(
                                () ->
                                        new KycStorageException(
                                                "the one-in-flight index refused a check insert"
                                                        + " but no check is visible - a"
                                                        + " concurrent requester may have rolled"
                                                        + " back; retry"));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "requesting a " + fresh.type() + " check on case " + fresh.caseId(),
                            failure));
        }
    }

    private static void insert(Connection unitOfWork, VerificationCheck check)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, case_id, check_type, status, requested_at,"
                                + " status_changed_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, check.id().value());
            insert.setObject(2, check.caseId().value());
            insert.setString(3, check.type().name());
            insert.setString(4, check.status().name());
            insert.setTimestamp(5, Timestamp.from(check.requestedAt()));
            insert.setTimestamp(6, Timestamp.from(check.statusChangedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public boolean dispatch(Connection unitOfWork, CheckId checkId, Instant at) {
        return move(unitOfWork, checkId, CheckStatus.REQUESTED, CheckStatus.DISPATCHED, at);
    }

    @Override
    public boolean complete(
            Connection unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return move(unitOfWork, checkId, CheckStatus.DISPATCHED, outcome.toStatus(), at);
    }

    /** The conditional transition whose row count is the outcome — the platform's protocol. */
    private static boolean move(
            Connection unitOfWork, CheckId checkId, CheckStatus from, CheckStatus to, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, checkId.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "moving check " + checkId + " from " + from + " to " + to, failure));
        }
    }

    @Override
    public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, case_id, check_type, status, requested_at, status_changed_at"
                                + " FROM " + TABLE + " WHERE case_id = ?")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                List<VerificationCheck> checks = new ArrayList<>();
                while (rows.next()) {
                    checks.add(rehydrate(rows));
                }
                return checks;
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading the checks of case " + caseId, failure));
        }
    }

    @Override
    public void appendEvidence(
            Connection unitOfWork,
            EvidenceId id,
            CheckId checkId,
            byte[] payload,
            Instant receivedAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(checkId, "checkId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");

        DocumentCipher.Encrypted encrypted = cipher.encrypt(DocumentBytes.of(payload));
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + EVIDENCE_TABLE
                                + " (id, check_id, content_ciphertext, content_nonce,"
                                + " key_version, checksum_sha256, content_length, received_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id.value());
            insert.setObject(2, checkId.value());
            insert.setBytes(3, encrypted.ciphertext());
            insert.setBytes(4, encrypted.nonce());
            insert.setInt(5, encrypted.keyVersion());
            insert.setBytes(6, KycDocument.checksumOf(payload));
            insert.setInt(7, payload.length);
            insert.setTimestamp(8, Timestamp.from(receivedAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "retaining evidence for check " + checkId, failure));
        }
    }

    private Optional<VerificationCheck> newestOfType(
            Connection unitOfWork, KycCaseId caseId, CheckType type) throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, case_id, check_type, status, requested_at, status_changed_at"
                                + " FROM " + TABLE
                                + " WHERE case_id = ? AND check_type = ?"
                                + " ORDER BY requested_at DESC, id DESC LIMIT 1")) {
            select.setObject(1, caseId.value());
            select.setString(2, type.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rehydrate(rows)) : Optional.empty();
            }
        }
    }

    private static VerificationCheck rehydrate(ResultSet row) throws SQLException {
        return VerificationCheck.rehydrate(
                CheckId.of(row.getObject("id", UUID.class)),
                KycCaseId.of(row.getObject("case_id", UUID.class)),
                CheckType.valueOf(row.getString("check_type")),
                CheckStatus.valueOf(row.getString("status")),
                row.getTimestamp("requested_at").toInstant(),
                row.getTimestamp("status_changed_at").toInstant());
    }
}
