package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.FormatDefect;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    public List<SourceRow> sources(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, code, kind, status FROM settlement.source ORDER BY code")) {
            try (ResultSet rows = read.executeQuery()) {
                List<SourceRow> sources = new ArrayList<>();
                while (rows.next()) {
                    sources.add(
                            new SourceRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getString("code"),
                                    SourceKind.valueOf(rows.getString("kind")),
                                    "ACTIVE".equals(rows.getString("status"))));
                }
                return List.copyOf(sources);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read the settlement sources", failure);
        }
    }

    /** The file row's columns, joined to the source's code — every reader shares one shape. */
    private static final String FILE_ROW_COLUMNS =
            "SELECT f.id, s.code AS source_code, f.source_id, f.received_via, f.status,"
                    + " f.business_date, f.format_id, f.format_version, f.content_sha256,"
                    + " f.content_length, f.line_count, f.received_by, f.attested_by,"
                    + " f.attested_at, f.received_at, f.correlation_id"
                    + " FROM settlement.file f"
                    + " JOIN settlement.source s ON s.id = f.source_id";

    @Override
    public Optional<FileRow> fileById(Connection unitOfWork, UUID fileId) {
        return oneFile(unitOfWork, fileId, FILE_ROW_COLUMNS + " WHERE f.id = ?");
    }

    @Override
    public Optional<FileRow> lockFileById(Connection unitOfWork, UUID fileId) {
        // FOR UPDATE OF f: the attestation's serialisation point. The joined source row is
        // deliberately not locked - retiring a source must never wait on an attestation.
        return oneFile(unitOfWork, fileId, FILE_ROW_COLUMNS + " WHERE f.id = ? FOR UPDATE OF f");
    }

    private Optional<FileRow> oneFile(Connection unitOfWork, UUID fileId, String sql) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, fileId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(fileRow(row));
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a settlement file row", failure);
        }
    }

    private static FileRow fileRow(ResultSet row) throws SQLException {
        Timestamp attestedAt = row.getTimestamp("attested_at");
        String attestedBy = row.getString("attested_by");
        return new FileRow(
                row.getObject("id", UUID.class),
                row.getString("source_code"),
                row.getObject("source_id", UUID.class),
                DeliveryChannel.valueOf(row.getString("received_via")),
                FileStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getObject("business_date", LocalDate.class)),
                SettlementFormatId.valueOf(row.getString("format_id")),
                row.getInt("format_version"),
                row.getBytes("content_sha256"),
                row.getInt("content_length"),
                row.getInt("line_count"),
                Optional.ofNullable(row.getString("received_by")),
                attestedBy == null
                        ? Optional.empty()
                        : Optional.of(new Attestation(attestedBy, attestedAt.toInstant())),
                row.getTimestamp("received_at").toInstant(),
                CorrelationId.of(row.getString("correlation_id")));
    }

    @Override
    public boolean recordAttestation(
            Connection unitOfWork, UUID fileId, String attestedBy, Instant attestedAt) {
        Objects.requireNonNull(attestedBy, "attestedBy must not be null");
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        // The conditional NULL -> value is the arbiter (ADR-0066 §2): under
                        // ten instances exactly one update matches, whatever happened to the
                        // row lock. The CHECKs and the transition trigger stand behind it.
                        "UPDATE settlement.file SET attested_by = ?, attested_at = ?"
                                + " WHERE id = ? AND attested_by IS NULL")) {
            write.setString(1, attestedBy);
            write.setTimestamp(2, Timestamp.from(attestedAt));
            write.setObject(3, fileId);
            return write.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not record an attestation", failure);
        }
    }

    @Override
    public List<FileRow> newestFiles(Connection unitOfWork, int limit) {
        // The id beside the timestamp: two files received in the same instant still list in
        // one stable order on every instance.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        FILE_ROW_COLUMNS + " ORDER BY f.received_at DESC, f.id LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<FileRow> files = new ArrayList<>();
                while (rows.next()) {
                    files.add(fileRow(rows));
                }
                return List.copyOf(files);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not list settlement files", failure);
        }
    }

    @Override
    public List<RefusalRow> newestRefusals(Connection unitOfWork, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT r.id, s.code AS source_code, r.content_sha256,"
                                + " r.content_length, r.format_id, r.format_version, r.reason,"
                                + " r.line_no, r.field_name, r.channel, r.actor, r.refused_at,"
                                + " r.correlation_id"
                                + " FROM settlement.refused_delivery r"
                                + " JOIN settlement.source s ON s.id = r.source_id"
                                + " ORDER BY r.refused_at DESC, r.id LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<RefusalRow> refusals = new ArrayList<>();
                while (rows.next()) {
                    Integer lineNo = rows.getObject("line_no", Integer.class);
                    refusals.add(
                            new RefusalRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getString("source_code"),
                                    rows.getBytes("content_sha256"),
                                    rows.getInt("content_length"),
                                    SettlementFormatId.valueOf(rows.getString("format_id")),
                                    rows.getInt("format_version"),
                                    RefusalReason.valueOf(rows.getString("reason")),
                                    Optional.ofNullable(lineNo),
                                    Optional.ofNullable(rows.getString("field_name")),
                                    DeliveryChannel.valueOf(rows.getString("channel")),
                                    rows.getString("actor"),
                                    rows.getTimestamp("refused_at").toInstant(),
                                    CorrelationId.of(rows.getString("correlation_id"))));
                }
                return List.copyOf(refusals);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not list refused deliveries", failure);
        }
    }

    @Override
    public List<PendingReading> pendingBySource(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Non-terminal by exclusion, deliberately: RECEIVED is the only value
                        // today, PARSED joins with P8-TSK-008, and a gauge that named the
                        // waiting statuses would silently stop counting the day one arrives.
                        "SELECT s.code, count(*) AS pending, min(f.received_at) AS oldest"
                                + " FROM settlement.file f"
                                + " JOIN settlement.source s ON s.id = f.source_id"
                                + " WHERE f.status NOT IN ('ACCEPTED', 'REJECTED')"
                                + " GROUP BY s.code")) {
            try (ResultSet rows = read.executeQuery()) {
                List<PendingReading> readings = new ArrayList<>();
                while (rows.next()) {
                    Timestamp oldest = rows.getTimestamp("oldest");
                    readings.add(
                            new PendingReading(
                                    rows.getString("code"),
                                    rows.getLong("pending"),
                                    Optional.ofNullable(oldest).map(Timestamp::toInstant)));
                }
                return List.copyOf(readings);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the pending settlement files", failure);
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

    // ------------------------------------------------------------ the parse leg (P8-TSK-008)

    @Override
    public List<UUID> dueForParse(Connection unitOfWork, Instant now, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Candidates only, no lock: the claim that matters is lockDueById in
                        // each file's own transaction. Oldest first (ADR-0066 §9).
                        "SELECT id FROM settlement.file"
                                + " WHERE status = 'RECEIVED'"
                                + " AND (next_parse_at IS NULL OR next_parse_at <= ?)"
                                + " ORDER BY received_at, id LIMIT ?")) {
            read.setTimestamp(1, Timestamp.from(now));
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> due = new ArrayList<>();
                while (rows.next()) {
                    due.add(rows.getObject("id", UUID.class));
                }
                return List.copyOf(due);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not list files due for parse", failure);
        }
    }

    @Override
    public Optional<FileRow> lockDueById(Connection unitOfWork, UUID fileId, Instant now) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        FILE_ROW_COLUMNS
                                + " WHERE f.id = ? AND f.status = 'RECEIVED'"
                                + " AND (f.next_parse_at IS NULL OR f.next_parse_at <= ?)"
                                // Contention hygiene: a held file is another instance's work.
                                // The conditional RECEIVED -> PARSED beneath is the arbiter
                                // even without this (the P8-TSK-003 recorded stance).
                                + " FOR UPDATE OF f SKIP LOCKED")) {
            read.setObject(1, fileId);
            read.setTimestamp(2, Timestamp.from(now));
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(fileRow(row));
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not claim a file for parse", failure);
        }
    }

    @Override
    public boolean markParsed(Connection unitOfWork, UUID fileId, Instant at) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        "UPDATE settlement.file SET status = 'PARSED', status_changed_at = ?"
                                + " WHERE id = ? AND status = 'RECEIVED'")) {
            write.setTimestamp(1, Timestamp.from(at));
            write.setObject(2, fileId);
            return write.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not mark a file parsed", failure);
        }
    }

    @Override
    public boolean markRejected(
            Connection unitOfWork,
            UUID fileId,
            FileStatus from,
            RejectionCode code,
            Optional<String> detail,
            Instant at) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        "UPDATE settlement.file SET status = 'REJECTED', rejection_code = ?,"
                                + " rejection_detail = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            write.setString(1, code.name());
            write.setString(2, detail.orElse(null));
            write.setTimestamp(3, Timestamp.from(at));
            write.setObject(4, fileId);
            write.setString(5, from.name());
            return write.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not mark a file rejected", failure);
        }
    }

    @Override
    public int bumpParseFailures(Connection unitOfWork, UUID fileId) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        "UPDATE settlement.file SET parse_failures = parse_failures + 1"
                                + " WHERE id = ? RETURNING parse_failures")) {
            write.setObject(1, fileId);
            try (ResultSet row = write.executeQuery()) {
                if (!row.next()) {
                    throw new SettlementStorageException(
                            "no settlement file " + fileId + " exists");
                }
                return row.getInt("parse_failures");
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not count a parse failure", failure);
        }
    }

    @Override
    public void scheduleNextParse(Connection unitOfWork, UUID fileId, Instant nextParseAt) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        "UPDATE settlement.file SET next_parse_at = ? WHERE id = ?")) {
            write.setTimestamp(1, Timestamp.from(nextParseAt));
            write.setObject(2, fileId);
            write.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not schedule the next parse", failure);
        }
    }

    @Override
    public void appendFileEvent(
            Connection unitOfWork,
            UUID fileId,
            FileStatus from,
            FileStatus to,
            Actor actor,
            Optional<String> reason,
            Instant occurredAt,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.file_event (file_id, from_status, to_status,"
                                + " actor, actor_type, reason, occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, fileId);
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, reason.orElse(null));
            insert.setTimestamp(7, Timestamp.from(occurredAt));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not append a file event", failure);
        }
    }

    @Override
    public void recordIngestionErrors(
            Connection unitOfWork, UUID fileId, List<FormatDefect> defects) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.ingestion_error (file_id, seq, line_no,"
                                + " error_code, field_name) VALUES (?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (FormatDefect defect : defects) {
                insert.setObject(1, fileId);
                insert.setInt(2, ++seq);
                insert.setObject(3, defect.lineNo().orElse(null));
                insert.setString(4, defect.code().name());
                insert.setString(5, defect.field().orElse(null));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not record ingestion errors", failure);
        }
    }
}
