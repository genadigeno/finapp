package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link VelocityEvidence} over the monitored transactions (`P13-TSK-006`; `-019`'s {@code risk V004}) - the journal's
 * transfers, counted for their source and destination accounts ({@link VelocityEvidence#transferOf}), complete through
 * the monitoring intake's position on the ledger journal (the watermark's last entry's millisecond). {@code SELECT}
 * only; risk's own tables, so no port to another module.
 */
public final class JdbcVelocityEvidence implements VelocityEvidence {

    /**
     * Every monitored transfer posted since an instant - risk's own rows, no lock. The range is entered through
     * {@code monitored_transaction_by_sequence} (the entry id's millisecond, a minute before the instant: the id is minted
     * in the posting's own transaction, so its millisecond and {@code posted_at} lie together) and then cut exactly on
     * {@code posted_at}.
     */
    static final String TRANSFERS_SQL = "SELECT id, journal_entry_id, journal_sequence, entry_type, posting_namespace,"
            + " kind, subject_kind, subject_ref, direction, counterparty_kind, counterparty_ref, counterparty_purpose,"
            + " amount_minor, currency, scale, value_date, posted_at, reverses_journal_entry_id"
            + " FROM risk.monitored_transaction WHERE journal_sequence >= ? AND kind = 'TRANSFER' AND posted_at >= ?"
            + " ORDER BY posted_at, id";

    /** How far before the instant the sequence range opens - the id's millisecond and the posting's stamp, apart. */
    static final long SEQUENCE_MARGIN_MILLIS = 60_000L;

    private final MonitoringWatermarkStore watermarks;

    public JdbcVelocityEvidence(MonitoringWatermarkStore watermarks) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
    }

    @Override
    public Set<VelocityDimension> provable() {
        return EnumSet.of(VelocityDimension.SOURCE_ACCOUNT, VelocityDimension.DESTINATION);
    }

    @Override
    public Record read(Connection snapshot, Instant from) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(from, "from");
        Instant completeThrough = watermarks.read(snapshot, MonitoringWatermark.LEDGER_JOURNAL)
                .map(watermark -> Instant.ofEpochMilli(watermark.lastSequence()))
                .orElse(Instant.EPOCH);
        List<Counted> movements = new ArrayList<>();
        try (PreparedStatement select = snapshot.prepareStatement(TRANSFERS_SQL)) {
            select.setLong(1, Math.max(0L, from.toEpochMilli() - SEQUENCE_MARGIN_MILLIS));
            select.setObject(2, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    VelocityEvidence.transferOf(monitored(row)).ifPresent(movements::add);
                }
            }
        } catch (SQLException failure) {
            throw new RiskStorageException(
                    "risk storage failed reading the velocity evidence: SQLSTATE " + failure.getSQLState());
        }
        return new Record(movements, completeThrough);
    }

    private static MonitoredTransaction monitored(ResultSet row) throws SQLException {
        return new MonitoredTransaction(
                MonitoredTransactionId.of(row.getObject("id", UUID.class)),
                row.getObject("journal_entry_id", UUID.class),
                row.getLong("journal_sequence"),
                MonitoredEntry.EntryType.valueOf(row.getString("entry_type")),
                row.getString("posting_namespace"),
                MonitoredKind.valueOf(row.getString("kind")),
                Optional.ofNullable(row.getString("subject_kind")).map(MonitoredSubjectKind::valueOf),
                Optional.ofNullable(row.getObject("subject_ref", UUID.class)),
                Optional.ofNullable(row.getString("direction")).map(MonitoredDirection::valueOf),
                MonitoredCounterpartyKind.valueOf(row.getString("counterparty_kind")),
                Optional.ofNullable(row.getObject("counterparty_ref", UUID.class)),
                Optional.ofNullable(row.getString("counterparty_purpose")),
                row.getLong("amount_minor"),
                row.getInt("scale"),
                CurrencyCode.of(row.getString("currency")),
                row.getObject("value_date", LocalDate.class),
                row.getObject("posted_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(row.getObject("reverses_journal_entry_id", UUID.class)));
    }
}
