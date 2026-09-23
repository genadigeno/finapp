package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC document storage (ADR-0033, ADR-0036).
 *
 * <p>Encryption happens here, not in the domain: where the bytes live and how they rest is the
 * adapter's concern, so a future object-store adapter reuses {@link DocumentCipher} unchanged and
 * the port keeps speaking plaintext.
 *
 * <p><strong>Isolation this relies on:</strong> PostgreSQL's default {@code READ COMMITTED}. A
 * second insert against the {@code (case_id, checksum_sha256)} unique index blocks until the
 * first transaction ends, then reports a unique violation if it committed — the database
 * arbitrates between two instances storing the same bytes, exactly as {@code JdbcKycCaseStore}
 * relies on for the one-open-case rule.
 */
@RequiredArgsConstructor
public final class JdbcDocumentStore implements DocumentStore<Connection> {

    private static final String TABLE = "kyc.kyc_document";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @NonNull private final DocumentCipher cipher;

    @Override
    public Capture appendOrConverge(
            Connection unitOfWork, KycDocument document, DocumentBytes content) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(content, "content must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes could not follow a lost race, and the retry
            // would fail rather than converge. A pre-flight SELECT is not a substitute - two
            // instances would both see the checksum free, both insert, and one would get 23505
            // anyway.
            Savepoint beforeInsert = unitOfWork.setSavepoint("kyc_document_append");
            try {
                insert(unitOfWork, document, cipher.encrypt(content));
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Capture(document, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return findByChecksum(unitOfWork, document.caseId(), document.checksum())
                        .map(existing -> new Capture(existing, false))
                        .orElseThrow(
                                () ->
                                        new KycStorageException(
                                                "the document checksum index refused an insert"
                                                        + " but no matching document is visible -"
                                                        + " a concurrent uploader may have rolled"
                                                        + " back; retry"));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "storing a document on case " + document.caseId(), failure));
        }
    }

    private static void insert(
            Connection unitOfWork, KycDocument document, DocumentCipher.Encrypted encrypted)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, case_id, document_type, content_type,"
                                + " content_ciphertext, content_nonce, key_version,"
                                + " checksum_sha256, content_length, uploaded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, document.id().value());
            insert.setObject(2, document.caseId().value());
            insert.setString(3, document.type().name());
            insert.setString(4, document.contentType().name());
            insert.setBytes(5, encrypted.ciphertext());
            insert.setBytes(6, encrypted.nonce());
            insert.setInt(7, encrypted.keyVersion());
            insert.setBytes(8, document.checksum());
            insert.setInt(9, document.contentLength());
            insert.setTimestamp(10, Timestamp.from(document.uploadedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<DocumentContent> readContent(Connection unitOfWork, DocumentId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, case_id, document_type, content_type, content_ciphertext,"
                                + " content_nonce, key_version, checksum_sha256, content_length,"
                                + " uploaded_at FROM " + TABLE + " WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                KycDocument document = rehydrate(row);
                byte[] content =
                        cipher.decrypt(
                                new DocumentCipher.Encrypted(
                                        row.getBytes("content_ciphertext"),
                                        row.getBytes("content_nonce"),
                                        row.getInt("key_version")));
                verifyChecksum(document, content);
                return Optional.of(new DocumentContent(document, content));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading document " + id, failure));
        }
    }

    /**
     * ADR-0036: the checksum is verified on read, so silent corruption is a detected failure.
     *
     * <p>Nearly unreachable while GCM authenticates every ciphertext — which is exactly why it is
     * cheap — and load-bearing the day the bytes move to an adapter whose at-rest story differs,
     * or a migration re-encrypts them. The two controls answer different questions: GCM says
     * <em>this ciphertext is the one this key wrote</em>; the checksum says <em>these bytes are
     * the ones received at capture</em>.
     */
    private static void verifyChecksum(KycDocument document, byte[] content) {
        if (!MessageDigest.isEqual(KycDocument.checksumOf(content), document.checksum())) {
            // Names the document, never the checksums: a message reaches a log line, and a
            // checksum is a possession oracle over RESTRICTED-PII content (INV-AUD-02).
            throw new KycStorageException(
                    "document " + document.id() + " failed checksum verification: the stored"
                            + " bytes are not the bytes received at capture");
        }
    }

    private static Optional<KycDocument> findByChecksum(
            Connection unitOfWork, KycCaseId caseId, byte[] checksum) throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id, case_id, document_type, content_type, checksum_sha256,"
                                + " content_length, uploaded_at FROM " + TABLE
                                + " WHERE case_id = ? AND checksum_sha256 = ?")) {
            select.setObject(1, caseId.value());
            select.setBytes(2, checksum);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        }
    }

    private static KycDocument rehydrate(ResultSet row) throws SQLException {
        return KycDocument.rehydrate(
                DocumentId.of(row.getObject("id", UUID.class)),
                KycCaseId.of(row.getObject("case_id", UUID.class)),
                DocumentType.valueOf(row.getString("document_type")),
                DocumentContentType.valueOf(row.getString("content_type")),
                row.getBytes("checksum_sha256"),
                row.getInt("content_length"),
                row.getTimestamp("uploaded_at").toInstant());
    }
}
