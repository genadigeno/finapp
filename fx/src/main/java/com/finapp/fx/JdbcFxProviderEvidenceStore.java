package com.finapp.fx;

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
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC FX provider evidence (`P9-TSK-006`, ADR-0033) - the {@code payments}
 * {@code JdbcProviderEvidenceStore} shape: encrypt, checksum the plaintext, insert; on read,
 * decrypt and verify. The cipher's arithmetic is also {@code fx V003}'s {@code CHECK}s, so a
 * writer that never passed through this class cannot store plaintext as ciphertext.
 */
@RequiredArgsConstructor
public final class JdbcFxProviderEvidenceStore implements FxProviderEvidenceStore<Connection> {

    @NonNull private final FxEvidenceCipher cipher;
    @NonNull private final IdGenerator ids;

    @Override
    public void append(
            Connection unitOfWork,
            String providerCode,
            String clientReference,
            Kind kind,
            byte[] payload,
            Instant recordedAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(providerCode, "providerCode must not be null");
        FxProvider.reference(clientReference);
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "evidence is 1.." + MAX_PAYLOAD_BYTES + " bytes, never empty");
        }
        java.util.UUID id = ids.next();
        FxEvidenceCipher.Encrypted encrypted =
                cipher.encrypt(associatedData(id, providerCode, clientReference, kind.name()), payload);
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.fx_provider_evidence (id, provider_code, client_reference,"
                                + " kind, content_ciphertext, content_nonce, key_version,"
                                + " checksum_sha256, content_length, recorded_at, cipher_format)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, providerCode);
            insert.setString(3, clientReference);
            insert.setString(4, kind.name());
            insert.setBytes(5, encrypted.ciphertext());
            insert.setBytes(6, encrypted.nonce());
            insert.setInt(7, encrypted.keyVersion());
            insert.setBytes(8, sha256(payload));
            insert.setInt(9, payload.length);
            insert.setTimestamp(10, Timestamp.from(recordedAt));
            insert.setInt(11, encrypted.format().code());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("retaining FX provider evidence", failure));
        }
    }

    @Override
    public List<byte[]> payloadsFor(
            Connection unitOfWork, String providerCode, String clientReference) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(providerCode, "providerCode must not be null");
        Objects.requireNonNull(clientReference, "clientReference must not be null");
        List<byte[]> payloads = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, kind, content_ciphertext, content_nonce, key_version, checksum_sha256,"
                                + " cipher_format FROM fx.fx_provider_evidence"
                                + " WHERE provider_code = ? AND client_reference = ?"
                                + " ORDER BY recorded_at, id")) {
            read.setString(1, providerCode);
            read.setString(2, clientReference);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    byte[] plaintext =
                            cipher.decrypt(
                                    associatedData(row.getObject("id", java.util.UUID.class), providerCode,
                                            clientReference, row.getString("kind")),
                                    new FxEvidenceCipher.Encrypted(
                                            row.getBytes("content_ciphertext"),
                                            row.getBytes("content_nonce"),
                                            row.getInt("key_version"),
                                            CipherFormat.of(row.getInt("cipher_format"))));
                    if (!MessageDigest.isEqual(sha256(plaintext), row.getBytes("checksum_sha256"))) {
                        throw new IllegalStateException(
                                "FX evidence failed its checksum - retained bytes changed");
                    }
                    payloads.add(plaintext);
                }
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading FX provider evidence", failure));
        }
        return payloads;
    }

    /**
     * The row's associated data (`X-TSK-010`): its identity, and the provider, reference and kind that name where the evidence
     * belongs - so a ciphertext copied into another row, or under another reference, fails to decrypt once writes are bound.
     */
    static byte[] associatedData(java.util.UUID id, String providerCode, String clientReference, String kind) {
        return AssociatedData.of(PURPOSE, id, providerCode, clientReference, kind);
    }

    /** What the ciphertext is, in its associated data. */
    static final String PURPOSE = "fx.fx_provider_evidence.content";

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is mandated by the platform", absent);
        }
    }
}
