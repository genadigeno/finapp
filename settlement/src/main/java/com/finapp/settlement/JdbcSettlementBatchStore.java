package com.finapp.settlement;

import com.finapp.platform.security.Actor;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SettlementBatchStore} over JDBC (ADR-0033: explicit SQL, no mapper).
 *
 * <p>{@link #insertParsedBatch} is one call because the batch is one statement
 * ({@code INV-SET-07}): the row, every canonical line, every typed reference, the folded
 * totals and the birth event land in the caller's one transaction, and the live unique's
 * refusal surfaces as {@link SettlementBatchStore.LiveBatchConflict} for the parse leg to
 * answer {@code CONFLICTING_BATCH}.
 */
public final class JdbcSettlementBatchStore implements SettlementBatchStore<Connection> {

    /** PostgreSQL's unique-violation SQLSTATE — the live unique's voice under a race. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final IdGenerator ids;

    public JdbcSettlementBatchStore(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    @Override
    public boolean liveBatchStands(
            Connection unitOfWork, UUID sourceId, String externalBatchRef, CurrencyCode currency) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM settlement.batch"
                                + " WHERE source_id = ? AND external_batch_ref = ?"
                                + " AND currency = ?"
                                + " AND status NOT IN ('REJECTED', 'REPUDIATED')")) {
            read.setObject(1, sourceId);
            read.setString(2, externalBatchRef);
            read.setString(3, currency.code());
            try (ResultSet row = read.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read the live batches", failure);
        }
    }

    @Override
    public boolean liveStatementStands(
            Connection unitOfWork, UUID sourceId, CurrencyCode currency, long statementSequence) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM settlement.batch"
                                + " WHERE source_id = ? AND currency = ?"
                                + " AND statement_sequence = ?"
                                + " AND status NOT IN ('REJECTED', 'REPUDIATED')")) {
            read.setObject(1, sourceId);
            read.setString(2, currency.code());
            read.setLong(3, statementSequence);
            try (ResultSet row = read.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read the live statements", failure);
        }
    }

    @Override
    public void insertParsedBatch(Connection unitOfWork, NewBatch batch) {
        ParsedBatch parsed = batch.parsed();
        CurrencyCode currency = parsed.declaredNet().currency();
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO settlement.batch (id, file_id, source_id,"
                                    + " external_batch_ref, currency, status, business_date,"
                                    + " format_id, format_version, line_count,"
                                    + " declared_line_count, net_minor, net_scale,"
                                    + " remittance_reference, created_at, status_changed_at,"
                                    + " correlation_id, statement_sequence, opening_minor,"
                                    + " closing_minor)"
                                    + " VALUES (?, ?, ?, ?, ?, 'PARSED', ?, ?, ?, ?, ?, ?, ?,"
                                    + " ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, batch.batchId());
                insert.setObject(2, batch.fileId());
                insert.setObject(3, batch.sourceId());
                insert.setString(4, parsed.externalBatchRef());
                insert.setString(5, currency.code());
                insert.setObject(6, parsed.businessDate());
                insert.setString(7, batch.formatId().name());
                insert.setInt(8, batch.formatVersion());
                insert.setInt(9, parsed.lines().size());
                insert.setInt(10, parsed.declaredLineCount());
                insert.setLong(11, parsed.declaredNet().minorUnits());
                insert.setShort(12, (short) parsed.declaredNet().scale());
                insert.setString(13, parsed.remittanceReference().orElse(null));
                insert.setTimestamp(14, Timestamp.from(batch.at()));
                insert.setTimestamp(15, Timestamp.from(batch.at()));
                insert.setString(16, batch.correlation().value());
                insert.setObject(17, parsed.statement().map(ParsedBatch.StatementFacts::sequence)
                        .orElse(null));
                insert.setObject(18, parsed.statement().map(facts -> facts.opening().minorUnits())
                        .orElse(null));
                insert.setObject(19, parsed.statement().map(facts -> facts.closing().minorUnits())
                        .orElse(null));
                insert.executeUpdate();
            }
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO settlement.line (id, batch_id, file_id, line_no,"
                                    + " line_type, direction, amount_minor, amount_scale,"
                                    + " currency, business_date, settlement_date, value_date,"
                                    + " raw_record_sha256, canonical_fingerprint,"
                                    + " attributed_source_id)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    PreparedStatement reference =
                            unitOfWork.prepareStatement(
                                    "INSERT INTO settlement.line_reference (line_id, kind,"
                                            + " value) VALUES (?, ?, ?)")) {
                for (ParsedLine line : parsed.lines()) {
                    UUID lineId = ids.next();
                    insert.setObject(1, lineId);
                    insert.setObject(2, batch.batchId());
                    insert.setObject(3, batch.fileId());
                    insert.setInt(4, line.lineNo());
                    insert.setString(5, line.type().name());
                    insert.setString(6, line.direction().name());
                    insert.setLong(7, line.amount().minorUnits());
                    insert.setShort(8, (short) line.amount().scale());
                    insert.setString(9, line.amount().currency().code());
                    insert.setObject(10, line.businessDate());
                    insert.setObject(11, line.settlementDate().orElse(null));
                    insert.setObject(12, line.valueDate().orElse(null));
                    insert.setBytes(13, line.rawRecordSha256());
                    insert.setBytes(14, line.canonicalFingerprint());
                    // Attribution is written with the line (append-only): the parse leg
                    // resolved it through the compiled register (P8-TSK-016).
                    insert.setObject(15, batch.attributions().get(line.lineNo()));
                    insert.addBatch();
                    for (Map.Entry<LineReferenceKind, String> each :
                            line.references().entrySet()) {
                        reference.setObject(1, lineId);
                        reference.setString(2, each.getKey().name());
                        reference.setString(3, each.getValue());
                        reference.addBatch();
                    }
                }
                insert.executeBatch();
                reference.executeBatch();
            }
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO settlement.batch_total (batch_id, line_type,"
                                    + " direction, line_count, amount_minor, amount_scale)"
                                    + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (TotalRow total : batch.totals()) {
                    insert.setObject(1, batch.batchId());
                    insert.setString(2, total.lineType().name());
                    insert.setString(3, total.direction().name());
                    insert.setLong(4, total.lineCount());
                    insert.setLong(5, total.amountMinor());
                    insert.setShort(6, (short) total.amountScale());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            appendBatchEvent(
                    unitOfWork,
                    batch.batchId(),
                    Optional.empty(),
                    BatchStatus.PARSED,
                    batch.actor(),
                    Optional.empty(),
                    batch.at(),
                    batch.correlation());
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                // The live unique or the per-file unique spoke: a racer's batch stands. The
                // transaction is dead either way; the parse leg answers in a fresh one.
                throw new LiveBatchConflict(batch.sourceId(), parsed.externalBatchRef());
            }
            throw new SettlementStorageException("could not write a settlement batch", failure);
        }
    }

    private static final String BATCH_ROW_COLUMNS =
            "SELECT b.id, b.file_id, b.source_id, s.code AS source_code,"
                    + " b.external_batch_ref, b.currency, b.status, b.business_date,"
                    + " b.format_id, b.format_version, b.line_count, b.declared_line_count,"
                    + " b.net_minor, b.net_scale, b.remittance_reference, b.created_at,"
                    + " b.correlation_id, b.statement_sequence, b.opening_minor,"
                    + " b.closing_minor"
                    + " FROM settlement.batch b"
                    + " JOIN settlement.source s ON s.id = b.source_id";

    @Override
    public Optional<BatchRow> batchByFileId(Connection unitOfWork, UUID fileId) {
        return oneBatch(unitOfWork, fileId, BATCH_ROW_COLUMNS + " WHERE b.file_id = ?");
    }

    @Override
    public Optional<BatchRow> batchById(Connection unitOfWork, UUID batchId) {
        return oneBatch(unitOfWork, batchId, BATCH_ROW_COLUMNS + " WHERE b.id = ?");
    }

    private static Optional<BatchRow> oneBatch(Connection unitOfWork, UUID id, String sql) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new BatchRow(
                                row.getObject("id", UUID.class),
                                row.getObject("file_id", UUID.class),
                                row.getObject("source_id", UUID.class),
                                row.getString("source_code"),
                                row.getString("external_batch_ref"),
                                CurrencyCode.of(row.getString("currency")),
                                BatchStatus.valueOf(row.getString("status")),
                                row.getObject("business_date", LocalDate.class),
                                SettlementFormatId.valueOf(row.getString("format_id")),
                                row.getInt("format_version"),
                                row.getInt("line_count"),
                                row.getInt("declared_line_count"),
                                row.getLong("net_minor"),
                                row.getShort("net_scale"),
                                Optional.ofNullable(row.getString("remittance_reference")),
                                row.getTimestamp("created_at").toInstant(),
                                CorrelationId.of(row.getString("correlation_id")),
                                row.getObject("statement_sequence") == null
                                        ? Optional.empty()
                                        : Optional.of(new StatementRow(
                                                row.getLong("statement_sequence"),
                                                row.getLong("opening_minor"),
                                                row.getLong("closing_minor")))));
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a settlement batch", failure);
        }
    }

    @Override
    public List<TotalRow> totalsOf(Connection unitOfWork, UUID batchId) {
        Objects.requireNonNull(batchId, "batchId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT line_type, direction, line_count, amount_minor, amount_scale"
                                + " FROM settlement.batch_total WHERE batch_id = ?"
                                + " ORDER BY line_type, direction")) {
            read.setObject(1, batchId);
            try (ResultSet rows = read.executeQuery()) {
                List<TotalRow> totals = new ArrayList<>();
                while (rows.next()) {
                    totals.add(
                            new TotalRow(
                                    SettlementLineType.valueOf(rows.getString("line_type")),
                                    LineDirection.valueOf(rows.getString("direction")),
                                    rows.getLong("line_count"),
                                    rows.getLong("amount_minor"),
                                    rows.getShort("amount_scale")));
                }
                return List.copyOf(totals);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a batch's totals", failure);
        }
    }

    @Override
    public boolean markBatchRejected(
            Connection unitOfWork,
            UUID batchId,
            Actor actor,
            Optional<String> reason,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        "UPDATE settlement.batch SET status = 'REJECTED',"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PARSED'")) {
            write.setTimestamp(1, Timestamp.from(at));
            write.setObject(2, batchId);
            if (write.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not reject a settlement batch", failure);
        }
        appendBatchEvent(
                unitOfWork,
                batchId,
                Optional.of(BatchStatus.PARSED),
                BatchStatus.REJECTED,
                actor,
                reason,
                at,
                correlation);
        return true;
    }

    // ----------------------------------------------------------- the accept leg (P8-TSK-009)

    @Override
    public List<LineRow> linesOf(Connection unitOfWork, UUID batchId) {
        Objects.requireNonNull(batchId, "batchId must not be null");
        java.util.Map<UUID, java.util.Map<LineReferenceKind, String>> references =
                new java.util.HashMap<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT r.line_id, r.kind, r.value FROM settlement.line_reference r"
                                + " JOIN settlement.line l ON l.id = r.line_id"
                                + " WHERE l.batch_id = ?")) {
            read.setObject(1, batchId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    references
                            .computeIfAbsent(
                                    rows.getObject("line_id", UUID.class),
                                    id -> new java.util.EnumMap<>(LineReferenceKind.class))
                            .put(
                                    LineReferenceKind.valueOf(rows.getString("kind")),
                                    rows.getString("value"));
                }
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a batch's references", failure);
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, line_no, line_type, direction, amount_minor, currency,"
                                + " amount_scale, business_date, settlement_date, value_date,"
                                + " canonical_fingerprint, attributed_source_id"
                                + " FROM settlement.line WHERE batch_id = ?"
                                + " ORDER BY line_no")) {
            read.setObject(1, batchId);
            try (ResultSet rows = read.executeQuery()) {
                List<LineRow> lines = new ArrayList<>();
                while (rows.next()) {
                    UUID lineId = rows.getObject("id", UUID.class);
                    lines.add(
                            new LineRow(
                                    lineId,
                                    rows.getInt("line_no"),
                                    SettlementLineType.valueOf(rows.getString("line_type")),
                                    LineDirection.valueOf(rows.getString("direction")),
                                    rows.getLong("amount_minor"),
                                    CurrencyCode.of(rows.getString("currency")),
                                    rows.getShort("amount_scale"),
                                    rows.getObject("business_date", LocalDate.class),
                                    Optional.ofNullable(
                                            rows.getObject("settlement_date", LocalDate.class)),
                                    Optional.ofNullable(
                                            rows.getObject("value_date", LocalDate.class)),
                                    rows.getBytes("canonical_fingerprint"),
                                    references.getOrDefault(lineId, java.util.Map.of()),
                                    Optional.ofNullable(
                                            rows.getObject("attributed_source_id", UUID.class))));
                }
                return List.copyOf(lines);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read a batch's lines", failure);
        }
    }

    @Override
    public boolean markAccepted(
            Connection unitOfWork,
            UUID batchId,
            long sourceSequence,
            LocalDate acceptedOn,
            Optional<UUID> journalEntryId,
            Instant at) {
        try (PreparedStatement write =
                unitOfWork.prepareStatement(
                        // One statement, all four facts with the edge: the honesty CHECKs
                        // and the once-only trigger admit no other shape (V004).
                        "UPDATE settlement.batch SET status = 'ACCEPTED',"
                                + " source_sequence = ?, accepted_on = ?,"
                                + " journal_entry_id = ?, posting_omitted = ?,"
                                + " status_changed_at = ?"
                                + " WHERE id = ? AND status = 'PARSED'")) {
            write.setLong(1, sourceSequence);
            write.setObject(2, acceptedOn);
            write.setObject(3, journalEntryId.orElse(null));
            write.setBoolean(4, journalEntryId.isEmpty());
            write.setTimestamp(5, Timestamp.from(at));
            write.setObject(6, batchId);
            return write.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not accept a settlement batch", failure);
        }
    }

    @Override
    public List<UUID> acceptedRecognitionEntries(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT journal_entry_id FROM settlement.batch"
                                + " WHERE status = 'ACCEPTED'"
                                + " AND journal_entry_id IS NOT NULL")) {
            try (ResultSet rows = read.executeQuery()) {
                List<UUID> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(rows.getObject("journal_entry_id", UUID.class));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the recognition entries", failure);
        }
    }

    @Override
    public List<AcceptedRow> pageAccepted(Connection unitOfWork, UUID after, int limit) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT b.id, b.source_id, s.code, b.currency, b.net_minor,"
                                + " b.net_scale, b.remittance_reference, b.business_date,"
                                + " b.accepted_on"
                                + " FROM settlement.batch b"
                                + " JOIN settlement.source s ON s.id = b.source_id"
                                + " WHERE b.status = 'ACCEPTED' AND b.id > ?"
                                // A statement opens no remittance (P8-TSK-016).
                                + " AND b.remittance_reference IS NOT NULL"
                                + " ORDER BY b.id"
                                + " LIMIT ?")) {
            read.setObject(1, after);
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<AcceptedRow> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(
                            new AcceptedRow(
                                    rows.getObject("id", UUID.class),
                                    rows.getObject("source_id", UUID.class),
                                    rows.getString("code"),
                                    CurrencyCode.of(rows.getString("currency")),
                                    rows.getLong("net_minor"),
                                    rows.getInt("net_scale"),
                                    rows.getString("remittance_reference"),
                                    rows.getObject("business_date", LocalDate.class),
                                    rows.getObject("accepted_on", LocalDate.class)));
                }
                return List.copyOf(page);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not page the accepted batches", failure);
        }
    }

    private static final String STATEMENT_LINK_COLUMNS =
            "SELECT id, source_id, currency, statement_sequence, opening_minor, closing_minor,"
                    + " net_scale FROM settlement.batch"
                    + " WHERE status = 'ACCEPTED' AND statement_sequence IS NOT NULL";

    private static StatementLink statementLink(ResultSet row) throws SQLException {
        return new StatementLink(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                CurrencyCode.of(row.getString("currency")),
                row.getLong("statement_sequence"),
                row.getLong("opening_minor"),
                row.getLong("closing_minor"),
                row.getShort("net_scale"));
    }

    @Override
    public Optional<StatementLink> acceptedStatement(
            Connection unitOfWork, UUID sourceId, CurrencyCode currency, long sequence) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        STATEMENT_LINK_COLUMNS
                                + " AND source_id = ? AND currency = ? AND statement_sequence = ?")) {
            read.setObject(1, sourceId);
            read.setString(2, currency.code());
            read.setLong(3, sequence);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(statementLink(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read an accepted statement", failure);
        }
    }

    @Override
    public List<StatementLink> acceptedStatements(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        STATEMENT_LINK_COLUMNS
                                + " ORDER BY source_id, currency, statement_sequence")) {
            try (ResultSet rows = read.executeQuery()) {
                List<StatementLink> chain = new ArrayList<>();
                while (rows.next()) {
                    chain.add(statementLink(rows));
                }
                return List.copyOf(chain);
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not read the statement chain", failure);
        }
    }

    @Override
    public Optional<UUID> recognitionEntryOf(Connection unitOfWork, UUID batchId) {
        return singleUuid(
                unitOfWork,
                "SELECT journal_entry_id FROM settlement.batch"
                        + " WHERE id = ? AND status = 'ACCEPTED'",
                batchId,
                "could not read the batch's recognition entry");
    }

    @Override
    public Optional<UUID> batchOfLine(Connection unitOfWork, UUID lineId) {
        return singleUuid(
                unitOfWork,
                "SELECT batch_id FROM settlement.line WHERE id = ?",
                lineId,
                "could not read the line's batch");
    }

    @Override
    public Optional<java.time.LocalDate> acceptedOnOf(Connection unitOfWork, UUID batchId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT accepted_on FROM settlement.batch"
                                + " WHERE id = ? AND status = 'ACCEPTED'")) {
            read.setObject(1, batchId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() && row.getDate(1) != null
                        ? Optional.of(row.getDate(1).toLocalDate())
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the batch's acceptance date", failure);
        }
    }

    @Override
    public java.util.Set<LocalDate> acceptedBusinessDates(
            Connection unitOfWork, UUID sourceId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT business_date FROM settlement.batch"
                                + " WHERE source_id = ? AND status = 'ACCEPTED'"
                                + " AND business_date BETWEEN ? AND ?")) {
            read.setObject(1, sourceId);
            read.setObject(2, from);
            read.setObject(3, to);
            java.util.Set<LocalDate> dates = new java.util.TreeSet<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    dates.add(row.getDate(1).toLocalDate());
                }
            }
            return dates;
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the source's accepted business dates", failure);
        }
    }

    @Override
    public java.util.Set<String> acceptedBatchRefs(
            Connection unitOfWork, UUID sourceId, java.util.Collection<String> refs) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(refs, "refs must not be null");
        if (refs.isEmpty()) {
            return java.util.Set.of();
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT DISTINCT external_batch_ref FROM settlement.batch"
                                + " WHERE source_id = ? AND status = 'ACCEPTED'"
                                + " AND external_batch_ref = ANY (?)")) {
            read.setObject(1, sourceId);
            read.setArray(2, unitOfWork.createArrayOf("text", refs.toArray()));
            java.util.Set<String> accepted = new java.util.TreeSet<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    accepted.add(row.getString(1));
                }
            }
            return accepted;
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the source's accepted batch references", failure);
        }
    }

    @Override
    public Map<UUID, Instant> lastAcceptedAt(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT source_id, max(status_changed_at) FROM settlement.batch"
                                + " WHERE status = 'ACCEPTED' GROUP BY source_id")) {
            Map<UUID, Instant> latest = new java.util.HashMap<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    latest.put(
                            row.getObject(1, UUID.class), row.getTimestamp(2).toInstant());
                }
            }
            return latest;
        } catch (SQLException failure) {
            throw new SettlementStorageException(
                    "could not read the sources' latest acceptances", failure);
        }
    }

    private static Optional<UUID> singleUuid(
            Connection unitOfWork, String sql, UUID id, String failureMessage) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.ofNullable(row.getObject(1, UUID.class))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException(failureMessage, failure);
        }
    }

    @Override
    public void appendBatchEvent(
            Connection unitOfWork,
            UUID batchId,
            BatchStatus from,
            BatchStatus to,
            Actor actor,
            Optional<String> reason,
            Instant occurredAt,
            CorrelationId correlation) {
        appendBatchEvent(
                unitOfWork, batchId, Optional.of(from), to, actor, reason, occurredAt,
                correlation);
    }

    private static void appendBatchEvent(
            Connection unitOfWork,
            UUID batchId,
            Optional<BatchStatus> from,
            BatchStatus to,
            Actor actor,
            Optional<String> reason,
            Instant occurredAt,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.batch_event (batch_id, from_status, to_status,"
                                + " actor, actor_type, reason, occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, batchId);
            insert.setString(2, from.map(BatchStatus::name).orElse(null));
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, reason.orElse(null));
            insert.setTimestamp(7, Timestamp.from(occurredAt));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not append a batch event", failure);
        }
    }
}
