package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link PayoutEvidenceStore} over JDBC (`P6-TSK-012`): AES-256-GCM ciphertext, the nonce, the
 * key version and the plaintext's SHA-256, into `V007`'s append-only table — the
 * {@code payments.provider_evidence} writer's shape.
 */
@RequiredArgsConstructor
public final class JdbcPayoutEvidenceStore implements PayoutEvidenceStore<Connection> {

    @NonNull private final PayoutEvidenceCipher cipher;
    @NonNull private final IdGenerator ids;

    @Override
    public void append(
            Connection unitOfWork,
            MerchantPayoutId payout,
            PayoutEvidenceKind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(payout, "payout must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            // The adapter never yields empty or oversized evidence; a caller that did is a
            // defect, refused before the CHECK would refuse it three layers down.
            throw new IllegalArgumentException(
                    "retained evidence must be 1-" + MAX_PAYLOAD_BYTES + " bytes");
        }
        PayoutEvidenceCipher.Encrypted encrypted = cipher.encrypt(payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.payout_evidence"
                                + " (id, payout_id, kind, content_ciphertext, content_nonce,"
                                + " key_version, checksum_sha256, content_length, recorded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, payout.value());
            insert.setString(3, kind.name());
            insert.setBytes(4, encrypted.ciphertext());
            insert.setBytes(5, encrypted.nonce());
            insert.setInt(6, encrypted.keyVersion());
            insert.setBytes(7, sha256(payload));
            insert.setInt(8, payload.length);
            insert.setTimestamp(9, Timestamp.from(recordedAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("retaining payout evidence", failure));
        }
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
