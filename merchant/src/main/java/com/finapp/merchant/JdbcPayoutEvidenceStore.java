package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.AssociatedData;
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
 * {@code payments.provider_evidence} writer's shape — and, since `V017` (`X-TSK-010`), whether the
 * ciphertext is bound to its row.
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
        // The id is minted before the encryption: it is part of what a bound ciphertext authenticates (X-TSK-010).
        java.util.UUID id = ids.next();
        PayoutEvidenceCipher.Encrypted encrypted = cipher.encrypt(associatedData(id, payout, kind), payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.payout_evidence"
                                + " (id, payout_id, kind, content_ciphertext, content_nonce,"
                                + " key_version, checksum_sha256, content_length, recorded_at,"
                                + " cipher_format)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, payout.value());
            insert.setString(3, kind.name());
            insert.setBytes(4, encrypted.ciphertext());
            insert.setBytes(5, encrypted.nonce());
            insert.setInt(6, encrypted.keyVersion());
            insert.setBytes(7, sha256(payload));
            insert.setInt(8, payload.length);
            insert.setTimestamp(9, Timestamp.from(recordedAt));
            insert.setInt(10, encrypted.format().code());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("retaining payout evidence", failure));
        }
    }

    /**
     * The row's associated data (`X-TSK-010`): its identity, and the payout and kind that name where the evidence belongs - so
     * a ciphertext copied into another row, or under another payout, fails to decrypt once writes are bound. Nothing in
     * production reads this table back; a reader rebuilds these bytes from the row it read, and presents them only when the
     * row's {@code cipher_format} says bound.
     */
    static byte[] associatedData(java.util.UUID id, MerchantPayoutId payout, PayoutEvidenceKind kind) {
        return AssociatedData.of(PURPOSE, id, payout.value().toString(), kind.name());
    }

    /** What the ciphertext is, in its associated data. */
    static final String PURPOSE = "merchant.payout_evidence.content";

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
