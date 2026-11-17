package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.AssociatedData;
import com.finapp.platform.security.CipherFormat;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link ProviderEvidenceStore} over JDBC (ADR-0033) — the store encrypts and hashes, so
 * retention has exactly one shape: AES-256-GCM ciphertext under {@link EvidenceCipher}, the
 * SHA-256 of the <strong>plaintext</strong> recorded at capture and verified on every read
 * ({@code V005}'s columns, {@code INV-HIST-02}).
 */
@RequiredArgsConstructor
public final class JdbcProviderEvidenceStore implements ProviderEvidenceStore<Connection> {

    @NonNull private final EvidenceCipher cipher;
    @NonNull private final IdGenerator ids;

    @Override
    public void append(
            Connection unitOfWork,
            Optional<PaymentAttemptId> attempt,
            Optional<RefundId> refund,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(refund, "refund must not be null");
        appendRow(
                unitOfWork,
                attempt.map(id -> id.value()).orElse(null),
                refund.map(id -> id.value()).orElse(null),
                null,
                null,
                null,
                null,
                kind,
                payload,
                recordedAt);
    }

    @Override
    public void appendForWithdrawal(
            Connection unitOfWork,
            WithdrawalId withdrawal,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(withdrawal, "withdrawal must not be null");
        appendRow(unitOfWork, null, null, withdrawal.value(), null, null, null, kind, payload,
                recordedAt);
    }

    @Override
    public void appendForOutboundCredit(
            Connection unitOfWork, OutboundCreditId credit, EvidenceKind kind, byte[] payload, Instant recordedAt) {
        Objects.requireNonNull(credit, "credit must not be null");
        appendRow(unitOfWork, null, null, null, null, null, credit.value(), kind, payload, recordedAt);
    }

    @Override
    public void appendForDisputeResponse(
            Connection unitOfWork,
            DisputeResponseId response,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(response, "response must not be null");
        appendRow(unitOfWork, null, null, null, response.value(), null, null, kind, payload,
                recordedAt);
    }

    @Override
    public void appendForUnmatched(
            Connection unitOfWork,
            java.util.UUID unmatchedConfirmation,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(unmatchedConfirmation, "unmatchedConfirmation must not be null");
        appendRow(unitOfWork, null, null, null, null, unmatchedConfirmation, null, kind, payload,
                recordedAt);
    }

    private void appendRow(
            Connection unitOfWork,
            java.util.UUID attemptId,
            java.util.UUID refundId,
            java.util.UUID withdrawalId,
            java.util.UUID disputeResponseId,
            java.util.UUID unmatchedConfirmationId,
            java.util.UUID outboundCreditId,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        java.util.UUID id = ids.next();
        EvidenceCipher.Encrypted encrypted =
                cipher.encrypt(
                        associatedData(id, attemptId, refundId, withdrawalId, disputeResponseId,
                                unmatchedConfirmationId, outboundCreditId, kind.name()),
                        payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.provider_evidence"
                                + " (id, attempt_id, refund_id, withdrawal_id,"
                                + " dispute_response_id, unmatched_confirmation_id, outbound_credit_id, kind,"
                                + " content_ciphertext, content_nonce, key_version,"
                                + " checksum_sha256, content_length, recorded_at, cipher_format)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, attemptId);
            insert.setObject(3, refundId);
            insert.setObject(4, withdrawalId);
            insert.setObject(5, disputeResponseId);
            insert.setObject(6, unmatchedConfirmationId);
            insert.setObject(7, outboundCreditId);
            insert.setString(8, kind.name());
            insert.setBytes(9, encrypted.ciphertext());
            insert.setBytes(10, encrypted.nonce());
            insert.setInt(11, encrypted.keyVersion());
            insert.setBytes(12, sha256(payload));
            insert.setInt(13, payload.length);
            insert.setTimestamp(14, Timestamp.from(recordedAt));
            insert.setInt(15, encrypted.format().code());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("retaining provider evidence", failure));
        }
    }

    @Override
    public List<byte[]> payloadsFor(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, attempt_id, refund_id, withdrawal_id, dispute_response_id,"
                                + " unmatched_confirmation_id, outbound_credit_id, kind,"
                                + " content_ciphertext, content_nonce, key_version, checksum_sha256,"
                                + " cipher_format FROM payments.provider_evidence"
                                + " WHERE attempt_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, attempt.value());
            try (ResultSet rows = read.executeQuery()) {
                List<byte[]> payloads = new ArrayList<>();
                while (rows.next()) {
                    byte[] plaintext =
                            cipher.decrypt(
                                    associatedData(
                                            rows.getObject("id", java.util.UUID.class),
                                            rows.getObject("attempt_id", java.util.UUID.class),
                                            rows.getObject("refund_id", java.util.UUID.class),
                                            rows.getObject("withdrawal_id", java.util.UUID.class),
                                            rows.getObject("dispute_response_id", java.util.UUID.class),
                                            rows.getObject("unmatched_confirmation_id", java.util.UUID.class),
                                            rows.getObject("outbound_credit_id", java.util.UUID.class),
                                            rows.getString("kind")),
                                    new EvidenceCipher.Encrypted(
                                            rows.getBytes("content_ciphertext"),
                                            rows.getBytes("content_nonce"),
                                            rows.getInt("key_version"),
                                            CipherFormat.of(rows.getInt("cipher_format"))));
                    if (!Arrays.equals(sha256(plaintext), rows.getBytes("checksum_sha256"))) {
                        // Verified on every read (INV-HIST-02): bytes that do not match the
                        // capture-time checksum are not the evidence, whatever decrypted.
                        throw new PaymentsStorageException(
                                "provider evidence for attempt " + attempt
                                        + " failed its checksum - retained bytes are corrupt");
                    }
                    payloads.add(plaintext);
                }
                return List.copyOf(payloads);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading the evidence of attempt " + attempt, failure));
        }
    }

    @Override
    public List<EvidenceMetadata> evidenceMetadataFor(
            Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, recorded_at FROM payments.provider_evidence"
                                + " WHERE attempt_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, attempt.value());
            return metadataRows(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading provider evidence metadata", failure));
        }
    }

    @Override
    public List<EvidenceMetadata> evidenceMetadataFor(Connection unitOfWork, RefundId refund) {
        Objects.requireNonNull(refund, "refund must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, recorded_at FROM payments.provider_evidence"
                                + " WHERE refund_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, refund.value());
            return metadataRows(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading provider evidence metadata", failure));
        }
    }

    @Override
    public List<EvidenceMetadata> evidenceMetadataFor(
            Connection unitOfWork, WithdrawalId withdrawal) {
        Objects.requireNonNull(withdrawal, "withdrawal must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, recorded_at FROM payments.provider_evidence"
                                + " WHERE withdrawal_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, withdrawal.value());
            return metadataRows(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading provider evidence metadata", failure));
        }
    }

    @Override
    public List<EvidenceMetadata> evidenceMetadataForUnmatched(
            Connection unitOfWork, java.util.UUID unmatchedConfirmation) {
        Objects.requireNonNull(unmatchedConfirmation, "unmatchedConfirmation must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, recorded_at FROM payments.provider_evidence"
                                + " WHERE unmatched_confirmation_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, unmatchedConfirmation);
            return metadataRows(read);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading provider evidence metadata", failure));
        }
    }

    /** Metadata only: no ciphertext column is selected, so no content can leave these reads. */
    private static List<EvidenceMetadata> metadataRows(PreparedStatement read)
            throws SQLException {
        try (ResultSet rows = read.executeQuery()) {
            List<EvidenceMetadata> found = new ArrayList<>();
            while (rows.next()) {
                found.add(
                        new EvidenceMetadata(
                                rows.getObject("id", java.util.UUID.class),
                                EvidenceKind.valueOf(rows.getString("kind")),
                                rows.getTimestamp("recorded_at").toInstant()));
            }
            return List.copyOf(found);
        }
    }

    @Override
    public boolean evidenceExists(Connection unitOfWork, java.util.UUID evidenceId) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM payments.provider_evidence WHERE id = ?)")) {
            read.setObject(1, evidenceId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("probing provider evidence", failure));
        }
    }

    /**
     * The row's associated data (`X-TSK-010`): its identity, and what names where the evidence belongs - its six subjects in a
     * fixed order (an absent one as the empty value, which no identifier is) and its kind - so a ciphertext copied into another
     * row, or under another subject, fails to decrypt once writes are bound.
     */
    static byte[] associatedData(
            java.util.UUID id,
            java.util.UUID attemptId,
            java.util.UUID refundId,
            java.util.UUID withdrawalId,
            java.util.UUID disputeResponseId,
            java.util.UUID unmatchedConfirmationId,
            java.util.UUID outboundCreditId,
            String kind) {
        return AssociatedData.of(PURPOSE, id, subject(attemptId), subject(refundId), subject(withdrawalId),
                subject(disputeResponseId), subject(unmatchedConfirmationId), subject(outboundCreditId), kind);
    }

    /** What the ciphertext is, in its associated data. */
    static final String PURPOSE = "payments.provider_evidence.content";

    private static String subject(java.util.UUID subject) {
        return subject == null ? "" : subject.toString();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }
}
