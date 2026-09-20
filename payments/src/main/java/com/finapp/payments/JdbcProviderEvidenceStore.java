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

/**
 * {@link ProviderEvidenceStore} over JDBC (ADR-0033) — the store encrypts and hashes, so
 * retention has exactly one shape: AES-256-GCM ciphertext under {@link EvidenceCipher}, the
 * SHA-256 of the <strong>plaintext</strong> recorded at capture and verified on every read
 * ({@code V005}'s columns, {@code INV-HIST-02}).
 */
public final class JdbcProviderEvidenceStore implements ProviderEvidenceStore<Connection> {

    private final EvidenceCipher cipher;
    private final IdGenerator ids;

    public JdbcProviderEvidenceStore(EvidenceCipher cipher, IdGenerator ids) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

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
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        EvidenceCipher.Encrypted encrypted = cipher.encrypt(payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.provider_evidence"
                                + " (id, attempt_id, refund_id, kind, content_ciphertext,"
                                + " content_nonce, key_version, checksum_sha256,"
                                + " content_length, recorded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, attempt.map(id -> id.value()).orElse(null));
            insert.setObject(3, refund.map(id -> id.value()).orElse(null));
            insert.setString(4, kind.name());
            insert.setBytes(5, encrypted.ciphertext());
            insert.setBytes(6, encrypted.nonce());
            insert.setInt(7, encrypted.keyVersion());
            insert.setBytes(8, sha256(payload));
            insert.setInt(9, payload.length);
            insert.setTimestamp(10, Timestamp.from(recordedAt));
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
