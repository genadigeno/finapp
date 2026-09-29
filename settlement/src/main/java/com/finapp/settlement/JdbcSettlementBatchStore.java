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
                                    + " correlation_id)"
                                    + " VALUES (?, ?, ?, ?, ?, 'PARSED', ?, ?, ?, ?, ?, ?, ?,"
                                    + " ?, ?, ?, ?)")) {
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
                insert.setString(13, parsed.remittanceReference());
                insert.setTimestamp(14, Timestamp.from(batch.at()));
                insert.setTimestamp(15, Timestamp.from(batch.at()));
                insert.setString(16, batch.correlation().value());
                insert.executeUpdate();
            }
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO settlement.line (id, batch_id, file_id, line_no,"
                                    + " line_type, direction, amount_minor, amount_scale,"
                                    + " currency, business_date, settlement_date, value_date,"
                                    + " raw_record_sha256, canonical_fingerprint)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
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
                    + " b.correlation_id"
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
                                row.getString("remittance_reference"),
                                row.getTimestamp("created_at").toInstant(),
                                CorrelationId.of(row.getString("correlation_id"))));
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
