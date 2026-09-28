package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SettlementFileStore} over JDBC (ADR-0033: explicit SQL, no mapper) — and the place
 * the bytes stop being plaintext (`P8-TSK-002`, ADR-0066 §6).
 *
 * <p>Chunks of at most 1 MiB, each AES-256-GCM under {@link SettlementFileCipher} with the
 * associated data binding {@code file_id ‖ source_id ‖ content_sha256 ‖ seq}; the read path
 * decrypts every chunk under the same binding, reassembles, and verifies the whole plaintext
 * against the stored address before serving one byte ({@code INV-HIST-02}).
 */
public final class JdbcSettlementFileStore implements SettlementFileStore<Connection> {

    /** The 1 MiB provider-evidence bound, per chunk (ADR-0066 §6). */
    public static final int CHUNK_BYTES = 1_048_576;

    private final SettlementFileCipher cipher;

    public JdbcSettlementFileStore(SettlementFileCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    @Override
    public Optional<SourceRow> sourceByCode(Connection unitOfWork, String code) {
        Objects.requireNonNull(code, "code must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, code, kind, status FROM settlement.source WHERE code = ?")) {
            read.setString(1, code);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new SourceRow(
                                row.getObject("id", UUID.class),
                                row.getString("code"),
                                SourceKind.valueOf(row.getString("kind")),
                                "ACTIVE".equals(row.getString("status"))));
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read settlement source", failure);
        }
    }

    @Override
    public Stored insert(Connection unitOfWork, SettlementFile file, byte[] content) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(content, "content must not be null");
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO settlement.file (id, source_id, received_via, status,"
                                    + " business_date, format_id, format_version,"
                                    + " content_sha256, content_length, line_count,"
                                    + " key_version, received_by, received_at,"
                                    + " status_changed_at, correlation_id)"
                                    + " VALUES (?, ?, ?, 'RECEIVED', ?, ?, ?, ?, ?, ?, ?, ?,"
                                    + " ?, ?, ?)"
                                    // The arbiter (ADR-0066 §5): a concurrent delivery of the
                                    // same bytes waits on the in-progress row, then converges.
                                    + " ON CONFLICT (source_id, content_sha256)"
                                    + " WHERE readmits_file_id IS NULL DO NOTHING")) {
                insert.setObject(1, file.id());
                insert.setObject(2, file.sourceId());
                insert.setString(3, file.receivedVia().name());
                insert.setObject(4, file.businessDate().orElse(null));
                insert.setString(5, file.formatId().name());
                insert.setInt(6, file.formatVersion());
                insert.setBytes(7, file.contentSha256());
                insert.setInt(8, file.contentLength());
                insert.setInt(9, file.lineCount());
                insert.setInt(10, cipher.version());
                insert.setString(11, file.receivedBy().map(Actor::id).orElse(null));
                insert.setTimestamp(12, Timestamp.from(file.receivedAt()));
                insert.setTimestamp(13, Timestamp.from(file.receivedAt()));
                insert.setString(14, file.correlation().value());
                if (insert.executeUpdate() == 0) {
                    return new Stored.Duplicate(existingFileId(unitOfWork, file));
                }
            }
            writeChunks(unitOfWork, file, content);
            return new Stored.New(file.id());
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not store a settlement file", failure);
        }
    }

    private void writeChunks(Connection unitOfWork, SettlementFile file, byte[] content)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.file_chunk (file_id, seq, ciphertext, nonce,"
                                + " plaintext_length) VALUES (?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (int from = 0; from < content.length; from += CHUNK_BYTES, seq++) {
                byte[] plaintext =
                        Arrays.copyOfRange(
                                content, from, Math.min(from + CHUNK_BYTES, content.length));
                SettlementFileCipher.Encrypted encrypted =
                        cipher.encrypt(
                                plaintext,
                                SettlementFileCipher.associatedData(
                                        file.id(), file.sourceId(), file.contentSha256(), seq));
                insert.setObject(1, file.id());
                insert.setInt(2, seq);
                insert.setBytes(3, encrypted.ciphertext());
                insert.setBytes(4, encrypted.nonce());
                insert.setInt(5, plaintext.length);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The standing row a refused insert converged on — committed, so READ COMMITTED sees it. */
    private UUID existingFileId(Connection unitOfWork, SettlementFile file) throws SQLException {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM settlement.file WHERE source_id = ? AND"
                                + " content_sha256 = ? AND readmits_file_id IS NULL")) {
            read.setObject(1, file.sourceId());
            read.setBytes(2, file.contentSha256());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new SettlementStorageException(
                            "a refused settlement-file insert found no standing row: the"
                                    + " content unique is the arbiter, so one stands");
                }
                return row.getObject("id", UUID.class);
            }
        }
    }

    @Override
    public void appendReceipt(
            Connection unitOfWork,
            UUID receiptId,
            UUID fileId,
            ReceiptOutcome outcome,
            DeliveryChannel channel,
            Actor deliveredBy,
            Instant receivedAt,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.file_receipt (id, file_id, outcome, channel,"
                                + " actor, actor_type, received_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, receiptId);
            insert.setObject(2, fileId);
            insert.setString(3, outcome.name());
            insert.setString(4, channel.name());
            insert.setString(5, deliveredBy.id());
            insert.setString(6, deliveredBy.type().name());
            insert.setTimestamp(7, Timestamp.from(receivedAt));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not append a file receipt", failure);
        }
    }

    @Override
    public void appendBirthEvent(
            Connection unitOfWork,
            UUID fileId,
            Actor actor,
            Instant occurredAt,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.file_event (file_id, from_status, to_status,"
                                + " actor, actor_type, occurred_at, correlation_id)"
                                + " VALUES (?, NULL, 'RECEIVED', ?, ?, ?, ?)")) {
            insert.setObject(1, fileId);
            insert.setString(2, actor.id());
            insert.setString(3, actor.type().name());
            insert.setTimestamp(4, Timestamp.from(occurredAt));
            insert.setString(5, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not append a file event", failure);
        }
    }

    @Override
    public void recordRefusal(Connection unitOfWork, RefusedDelivery refusal) {
        Objects.requireNonNull(refusal, "refusal must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.refused_delivery (id, source_id,"
                                + " content_sha256, content_length, format_id, format_version,"
                                + " reason, line_no, field_name, channel, actor, actor_type,"
                                + " refused_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, refusal.id());
            insert.setObject(2, refusal.sourceId());
            insert.setBytes(3, refusal.contentSha256());
            insert.setInt(4, refusal.contentLength());
            insert.setString(5, refusal.formatId().name());
            insert.setInt(6, refusal.formatVersion());
            insert.setString(7, refusal.reason().name());
            insert.setObject(8, refusal.lineNo().orElse(null));
            insert.setString(9, refusal.fieldName().orElse(null));
            insert.setString(10, refusal.channel().name());
            insert.setString(11, refusal.deliveredBy().id());
            insert.setString(12, refusal.deliveredBy().type().name());
            insert.setTimestamp(13, Timestamp.from(refusal.refusedAt()));
            insert.setString(14, refusal.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not record a refused delivery", failure);
        }
    }

    @Override
    public byte[] readContent(Connection unitOfWork, UUID fileId) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        try {
            UUID sourceId;
            byte[] declaredSha;
            int declaredLength;
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT source_id, content_sha256, content_length"
                                    + " FROM settlement.file WHERE id = ?")) {
                read.setObject(1, fileId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        throw new SettlementStorageException(
                                "no settlement file " + fileId + " exists");
                    }
                    sourceId = row.getObject("source_id", UUID.class);
                    declaredSha = row.getBytes("content_sha256");
                    declaredLength = row.getInt("content_length");
                }
            }
            ByteArrayOutputStream plaintext = new ByteArrayOutputStream(declaredLength);
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT seq, ciphertext, nonce, plaintext_length"
                                    + " FROM settlement.file_chunk WHERE file_id = ?"
                                    + " ORDER BY seq")) {
                read.setObject(1, fileId);
                try (ResultSet rows = read.executeQuery()) {
                    int expectedSeq = 0;
                    while (rows.next()) {
                        int seq = rows.getInt("seq");
                        if (seq != expectedSeq++) {
                            throw new SettlementStorageException(
                                    "settlement file " + fileId + " is missing chunk "
                                            + (expectedSeq - 1) + " (INV-HIST-02): nothing is"
                                            + " served");
                        }
                        byte[] chunk;
                        try {
                            chunk =
                                    cipher.decrypt(
                                            new SettlementFileCipher.Encrypted(
                                                    rows.getBytes("ciphertext"),
                                                    rows.getBytes("nonce")),
                                            SettlementFileCipher.associatedData(
                                                    fileId, sourceId, declaredSha, seq));
                        } catch (IllegalStateException tampered) {
                            // Tampering, a transplanted chunk and the wrong key are one
                            // indistinguishable failure - and none serves a byte.
                            throw new SettlementStorageException(
                                    "settlement file " + fileId + " chunk " + seq
                                            + " failed authenticated decryption"
                                            + " (INV-HIST-02): nothing is served");
                        }
                        plaintext.write(chunk, 0, chunk.length);
                    }
                }
            }
            byte[] content = plaintext.toByteArray();
            if (content.length != declaredLength
                    || !MessageDigest.isEqual(sha256(content), declaredSha)) {
                throw new SettlementStorageException(
                        "settlement file " + fileId + " does not hash to its stored address"
                                + " (INV-HIST-02): nothing is served");
            }
            return content;
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a settlement file", failure);
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }
}
