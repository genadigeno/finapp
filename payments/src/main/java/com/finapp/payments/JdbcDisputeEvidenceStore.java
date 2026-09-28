package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.persistence.DatabaseFailure;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link DisputeEvidenceStore} over explicit SQL (ADR-0033) — `V022`'s
 * {@code payments.dispute_evidence}, {@code kyc.kyc_document}'s shape restated: AES-256-GCM
 * ciphertext under the dispute-evidence key, the plaintext's SHA-256 recorded at capture and
 * verified on every read, append-only by grant.
 *
 * <p>The cipher is {@link EvidenceCipher}'s class holding the DISPUTE-evidence key — never the
 * provider-evidence key: one key per concern is what lets each rotate alone (ADR-0036), and it is
 * what keeps a leak of one key from opening the other's rows.
 */
@RequiredArgsConstructor
public final class JdbcDisputeEvidenceStore implements DisputeEvidenceStore<Connection> {

    private static final String TABLE = "payments.dispute_evidence";

    private static final String METADATA =
            "e.id, e.dispute_id, e.kind, e.content_type, e.content_length, e.uploaded_by_id,"
                    + " e.uploaded_by_type, e.uploaded_at";

    private static final String CONTENT =
            METADATA + ", e.content_ciphertext, e.content_nonce, e.key_version, e.checksum_sha256";

    @NonNull private final EvidenceCipher cipher;

    @Override
    public Stored appendOrConverge(
            Connection unitOfWork, DisputeEvidence candidate, DisputeEvidenceContent content) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (candidate.contentLength() != content.length()) {
            throw new IllegalArgumentException("the metadata's length is the content's own");
        }
        byte[] plaintext = content.value();
        byte[] checksum = sha256(plaintext);
        EvidenceCipher.Encrypted encrypted = cipher.encrypt(plaintext);
        // ON CONFLICT names the ONE arbiter it may absorb - the content address; any other
        // refusal (a key collision, a CHECK) still fails loudly rather than converging.
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, dispute_id, kind, content_type, content_ciphertext,"
                                + " content_nonce, key_version, checksum_sha256, content_length,"
                                + " uploaded_by_id, uploaded_by_type, uploaded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT ON CONSTRAINT"
                                + " dispute_evidence_one_per_dispute_and_checksum DO NOTHING")) {
            insert.setObject(1, candidate.id().value());
            insert.setObject(2, candidate.dispute().value());
            insert.setString(3, candidate.kind().name());
            insert.setString(4, candidate.contentType().name());
            insert.setBytes(5, encrypted.ciphertext());
            insert.setBytes(6, encrypted.nonce());
            insert.setInt(7, encrypted.keyVersion());
            insert.setBytes(8, checksum);
            insert.setInt(9, candidate.contentLength());
            insert.setString(10, candidate.uploadedById());
            insert.setString(11, candidate.uploadedByType());
            insert.setTimestamp(12, Timestamp.from(candidate.uploadedAt()));
            if (insert.executeUpdate() == 1) {
                return new Stored(candidate, true);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("storing dispute evidence", failure));
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + METADATA + " FROM " + TABLE + " e"
                                + " WHERE e.dispute_id = ? AND e.checksum_sha256 = ?")) {
            select.setObject(1, candidate.dispute().value());
            select.setBytes(2, checksum);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new PaymentsStorageException(
                            "the evidence content address refused an insert but no matching"
                                    + " document is visible");
                }
                return new Stored(metadata(row), false);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("converging on stored dispute evidence", failure));
        }
    }

    @Override
    public Optional<DisputeEvidence> findByContent(
            Connection unitOfWork, DisputeId dispute, DisputeEvidenceContent content) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(content, "content must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + METADATA + " FROM " + TABLE + " e"
                                + " WHERE e.dispute_id = ? AND e.checksum_sha256 = ?")) {
            select.setObject(1, dispute.value());
            select.setBytes(2, sha256(content.value()));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(metadata(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("finding dispute evidence by content", failure));
        }
    }

    @Override
    public int countFor(Connection unitOfWork, DisputeId dispute) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM " + TABLE + " WHERE dispute_id = ?")) {
            select.setObject(1, dispute.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("counting a dispute's evidence", failure));
        }
    }

    @Override
    public List<DisputeEvidence> listFor(Connection unitOfWork, DisputeId dispute) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + METADATA + " FROM " + TABLE + " e"
                                + " WHERE e.dispute_id = ? ORDER BY e.uploaded_at, e.id")) {
            select.setObject(1, dispute.value());
            List<DisputeEvidence> found = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    found.add(metadata(row));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("listing a dispute's evidence", failure));
        }
    }

    @Override
    public Optional<Content> readContentForCounterparties(
            Connection unitOfWork,
            DisputeId dispute,
            DisputeEvidenceId id,
            Set<LedgerAccountId> counterparties) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(counterparties, "counterparties must not be null");
        // THE TENANT PREDICATE IS IN THE STATEMENT (INV-MER-01): the document's dispute must
        // contest a payment that credited one of the caller's own accounts.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + CONTENT + " FROM " + TABLE + " e"
                                + " JOIN payments.dispute d ON d.id = e.dispute_id"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE e.id = ? AND e.dispute_id = ?"
                                + " AND i.credit_account_id = ANY (?)")) {
            select.setObject(1, id.value());
            select.setObject(2, dispute.value());
            select.setArray(
                    3,
                    unitOfWork.createArrayOf(
                            "uuid",
                            counterparties.stream().map(LedgerAccountId::value).toArray()));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(content(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a counterparty's dispute evidence",
                            failure));
        }
    }

    @Override
    public Optional<Content> readContent(
            Connection unitOfWork, DisputeId dispute, DisputeEvidenceId id) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + CONTENT + " FROM " + TABLE + " e"
                                + " WHERE e.id = ? AND e.dispute_id = ?")) {
            select.setObject(1, id.value());
            select.setObject(2, dispute.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(content(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading dispute evidence", failure));
        }
    }

    @Override
    public List<Content> contentsOf(
            Connection unitOfWork, DisputeId dispute, List<DisputeEvidenceId> ids) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(ids, "ids must not be null");
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Content> byId = new HashMap<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + CONTENT + " FROM " + TABLE + " e"
                                + " WHERE e.dispute_id = ? AND e.id = ANY (?)")) {
            select.setObject(1, dispute.value());
            select.setArray(
                    2,
                    unitOfWork.createArrayOf(
                            "uuid", ids.stream().map(DisputeEvidenceId::value).toArray()));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    Content read = content(row);
                    byId.put(read.evidence().id().value(), read);
                }
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the evidence a response carries", failure));
        }
        List<Content> ordered = new ArrayList<>(ids.size());
        for (DisputeEvidenceId id : ids) {
            Content read = byId.get(id.value());
            if (read == null) {
                // The response froze its evidence set and the table is append-only: a missing
                // document is corruption, never a document to skip silently.
                throw new PaymentsStorageException(
                        "dispute evidence " + id + " named by a response is not the dispute's");
            }
            ordered.add(read);
        }
        return List.copyOf(ordered);
    }

    // -----------------------------------------------------------------

    private Content content(ResultSet row) throws SQLException {
        DisputeEvidence evidence = metadata(row);
        byte[] plaintext =
                cipher.decrypt(
                        new EvidenceCipher.Encrypted(
                                row.getBytes("content_ciphertext"),
                                row.getBytes("content_nonce"),
                                row.getInt("key_version")));
        // GCM authenticates the ciphertext; the checksum says THESE are the bytes received at
        // capture - so silent corruption is a detected failure (ADR-0036's read rule).
        if (!MessageDigest.isEqual(sha256(plaintext), row.getBytes("checksum_sha256"))) {
            // Names the document, never the checksums: a checksum is a possession oracle over
            // the content (INV-AUD-02).
            throw new PaymentsStorageException(
                    "dispute evidence " + evidence.id() + " failed checksum verification");
        }
        return new Content(evidence, DisputeEvidenceContent.of(plaintext));
    }

    private static DisputeEvidence metadata(ResultSet row) throws SQLException {
        return new DisputeEvidence(
                DisputeEvidenceId.of(row.getObject("id", UUID.class)),
                DisputeId.of(row.getObject("dispute_id", UUID.class)),
                DisputeEvidenceKind.valueOf(row.getString("kind")),
                DisputeEvidenceContentType.valueOf(row.getString("content_type")),
                row.getInt("content_length"),
                row.getString("uploaded_by_id"),
                row.getString("uploaded_by_type"),
                row.getTimestamp("uploaded_at").toInstant());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JRE ships SHA-256 (the Java Security Standard Algorithm Names).
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
