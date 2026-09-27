package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
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
        appendRow(unitOfWork, null, null, withdrawal.value(), null, kind, payload, recordedAt);
    }

    @Override
    public void appendForDisputeResponse(
            Connection unitOfWork,
            DisputeResponseId response,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(response, "response must not be null");
        appendRow(unitOfWork, null, null, null, response.value(), kind, payload, recordedAt);
    }

    private void appendRow(
            Connection unitOfWork,
            java.util.UUID attemptId,
            java.util.UUID refundId,
            java.util.UUID withdrawalId,
            java.util.UUID disputeResponseId,
            EvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        EvidenceCipher.Encrypted encrypted = cipher.encrypt(payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.provider_evidence"
                                + " (id, attempt_id, refund_id, withdrawal_id,"
                                + " dispute_response_id, kind,"
                                + " content_ciphertext, content_nonce, key_version,"
                                + " checksum_sha256, content_length, recorded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, attemptId);
            insert.setObject(3, refundId);
            insert.setObject(4, withdrawalId);
            insert.setObject(5, disputeResponseId);
            insert.setString(6, kind.name());
            insert.setBytes(7, encrypted.ciphertext());
            insert.setBytes(8, encrypted.nonce());
            insert.setInt(9, encrypted.keyVersion());
            insert.setBytes(10, sha256(payload));
            insert.setInt(11, payload.length);
            insert.setTimestamp(12, Timestamp.from(recordedAt));
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
                        "SELECT content_ciphertext, content_nonce, key_version, checksum_sha256"
                                + " FROM payments.provider_evidence"
                                + " WHERE attempt_id = ? ORDER BY recorded_at, id")) {
            read.setObject(1, attempt.value());
            try (ResultSet rows = read.executeQuery()) {
                List<byte[]> payloads = new ArrayList<>();
                while (rows.next()) {
                    byte[] plaintext =
                            cipher.decrypt(
                                    new EvidenceCipher.Encrypted(
                                            rows.getBytes("content_ciphertext"),
                                            rows.getBytes("content_nonce"),
                                            rows.getInt("key_version")));
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

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }
}
