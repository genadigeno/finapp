package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OpeningCheckpointStore} over {@code accounting V017} (`P16-REM-003`).
 *
 * <p><strong>The reads</strong> ({@link #LATEST}, {@link #GROUPS}) are single {@code SELECT}s a report's {@code ReadOnlySnapshot} view
 * admits, under the reporting role's {@code SELECT}: the validity is one join - the period row not {@code OPEN}, its
 * {@code locked_at} the checkpoint's - so no checkpoint whose month moved back is ever served.
 *
 * <p><strong>The writes</strong> are the openings leg's alone. {@link #CLAIM_SQL} is a transaction-scoped try-lock on namespace 14
 * (ADR-0114's accounting namespace; key {@value #KEY}, registered in {@code DISTRIBUTED_EXECUTION.md} section 3): never a wait, so a
 * second instance skips its tick rather than fold the same month again. {@link #STORE} reads the period row again inside the insert
 * and writes nothing unless the lock the checkpoint was folded under still stands; the unique {@code (period_id, period_locked_at)}
 * makes a second checkpoint of one lock a no-op.
 */
public final class JdbcOpeningCheckpointStore implements OpeningCheckpointStore {

    /** accounting's advisory namespace - the posting cutoff's and the mapping activation's, each its own key. */
    public static final int NAMESPACE = 14;

    /** The openings leg's key within the namespace. */
    public static final String KEY = "opening-checkpoint";

    static final String CLAIM_SQL = "SELECT pg_try_advisory_xact_lock(" + NAMESPACE + ", hashtext('" + KEY + "'))";

    /** The latest valid checkpoint before a day; a cut appends {@link #UNDER_THE_CUT}. */
    public static final String LATEST = "SELECT checkpoint.id, checkpoint.period_id, period.period_end, checkpoint.period_locked_at,"
            + " checkpoint.latest_created"
            + " FROM accounting.opening_checkpoint checkpoint"
            + " JOIN accounting.accounting_period period"
            + " ON period.id = checkpoint.period_id AND period.locked_at = checkpoint.period_locked_at"
            + " WHERE period.status <> 'OPEN' AND period.period_end < ?";

    /** Under a PROVISIONAL cut: only a checkpoint whose every entry the cut keeps. */
    public static final String UNDER_THE_CUT = " AND (checkpoint.latest_created IS NULL OR checkpoint.latest_created <= ?)";

    public static final String NEWEST = " ORDER BY period.period_end DESC LIMIT 1";

    public static final String GROUPS = "SELECT shape, purpose, owner_kind, counterparty, currency, scale, direction, total, lines"
            + " FROM accounting.opening_checkpoint_group WHERE checkpoint_id = ?";

    /** The oldest month locked, or past it, whose present lock has no checkpoint. */
    static final String PENDING = "SELECT period.id, period.period_end, period.locked_at FROM accounting.accounting_period period"
            + " WHERE period.status <> 'OPEN' AND period.locked_at IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM accounting.opening_checkpoint checkpoint"
            + " WHERE checkpoint.period_id = period.id AND checkpoint.period_locked_at = period.locked_at)"
            + " ORDER BY period.period_start LIMIT 1";

    /** The checkpoint, only under the lock it was folded under - the row read again by the insert. */
    static final String STORE = "INSERT INTO accounting.opening_checkpoint (id, period_id, period_locked_at, latest_created)"
            + " SELECT ?, period.id, period.locked_at, ? FROM accounting.accounting_period period"
            + " WHERE period.id = ? AND period.status <> 'OPEN' AND period.locked_at = ?"
            + " ON CONFLICT (period_id, period_locked_at) DO NOTHING";

    static final String STORE_GROUP = "INSERT INTO accounting.opening_checkpoint_group"
            + " (checkpoint_id, shape, purpose, owner_kind, counterparty, currency, scale, direction, total, lines)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String GL = "GL";
    static final String LEDGER = "LEDGER";
    static final String COUNTERPARTY = "COUNTERPARTY";

    @Override
    public Optional<OpeningCheckpoint> latest(Connection snapshot, LocalDate before, Optional<Instant> cut) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(cut, "cut");
        try {
            UUID id;
            AccountingPeriodId period;
            LocalDate through;
            Instant lockedAt;
            Optional<Instant> latestCreated;
            try (PreparedStatement read = snapshot.prepareStatement(LATEST + (cut.isPresent() ? UNDER_THE_CUT : "") + NEWEST)) {
                read.setDate(1, Date.valueOf(before));
                if (cut.isPresent()) {
                    read.setTimestamp(2, Timestamp.from(cut.get()));
                }
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    id = row.getObject("id", UUID.class);
                    period = AccountingPeriodId.of(row.getObject("period_id", UUID.class));
                    through = row.getDate("period_end").toLocalDate();
                    lockedAt = row.getTimestamp("period_locked_at").toInstant();
                    Timestamp latest = row.getTimestamp("latest_created");
                    latestCreated = latest == null ? Optional.empty() : Optional.of(latest.toInstant());
                }
            }
            List<JournalReads.Aggregate> gl = new ArrayList<>();
            List<JournalReads.Total> ledger = new ArrayList<>();
            List<JournalReads.Aggregate> counterparties = new ArrayList<>();
            try (PreparedStatement read = snapshot.prepareStatement(GROUPS)) {
                read.setObject(1, id);
                try (ResultSet row = read.executeQuery()) {
                    while (row.next()) {
                        String currency = row.getString("currency");
                        int scale = row.getInt("scale");
                        Side side = side(row.getString("direction"));
                        BigInteger total = row.getBigDecimal("total").toBigIntegerExact();
                        long lines = row.getLong("lines");
                        switch (row.getString("shape")) {
                            case GL -> gl.add(new JournalReads.Aggregate(row.getString("purpose"), row.getString("owner_kind"),
                                    Optional.empty(), currency, scale, side, total, lines));
                            case LEDGER -> ledger.add(new JournalReads.Total(currency, scale, side, total, lines));
                            case COUNTERPARTY -> counterparties.add(new JournalReads.Aggregate(row.getString("purpose"),
                                    row.getString("owner_kind"), Optional.ofNullable(row.getString("counterparty")), currency, scale,
                                    side, total, lines));
                            default -> throw new IllegalStateException("an opening checkpoint's group is GL, LEDGER or COUNTERPARTY");
                        }
                    }
                }
            }
            return Optional.of(new OpeningCheckpoint(period, through, lockedAt, latestCreated, gl, ledger, counterparties));
        } catch (SQLException failure) {
            throw failed("reading the opening checkpoint", failure);
        }
    }

    @Override
    public boolean claim(Connection unitOfWork) {
        try (PreparedStatement claim = unitOfWork.prepareStatement(CLAIM_SQL); ResultSet row = claim.executeQuery()) {
            return row.next() && row.getBoolean(1);
        } catch (SQLException failure) {
            throw failed("claiming the opening checkpoints' writing", failure);
        }
    }

    @Override
    public Optional<Pending> pending(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement(PENDING); ResultSet row = read.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.of(new Pending(AccountingPeriodId.of(row.getObject("id", UUID.class)),
                    row.getDate("period_end").toLocalDate(), row.getTimestamp("locked_at").toInstant()));
        } catch (SQLException failure) {
            throw failed("reading the month whose lock has no opening checkpoint", failure);
        }
    }

    @Override
    public boolean store(Connection unitOfWork, UUID id, OpeningCheckpoint checkpoint) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(STORE)) {
                insert.setObject(1, id);
                if (checkpoint.latestCreated().isPresent()) {
                    insert.setTimestamp(2, Timestamp.from(checkpoint.latestCreated().get()));
                } else {
                    insert.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
                }
                insert.setObject(3, checkpoint.period().value());
                insert.setTimestamp(4, Timestamp.from(checkpoint.lockedAt()));
                if (insert.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(STORE_GROUP)) {
                for (JournalReads.Aggregate group : checkpoint.gl()) {
                    group(insert, id, GL, Optional.of(group.purpose()), Optional.of(group.ownerKind()), Optional.empty(),
                            group.currency(), group.scale(), group.side(), group.minorUnits(), group.lines());
                }
                for (JournalReads.Total total : checkpoint.ledger()) {
                    group(insert, id, LEDGER, Optional.empty(), Optional.empty(), Optional.empty(), total.currency(), total.scale(),
                            total.side(), total.minorUnits(), total.lines());
                }
                for (JournalReads.Aggregate group : checkpoint.counterparties()) {
                    group(insert, id, COUNTERPARTY, Optional.of(group.purpose()), Optional.of(group.ownerKind()), group.counterparty(),
                            group.currency(), group.scale(), group.side(), group.minorUnits(), group.lines());
                }
                insert.executeBatch();
            }
            return true;
        } catch (SQLException failure) {
            throw failed("storing the opening checkpoint", failure);
        }
    }

    private static void group(PreparedStatement insert, UUID checkpoint, String shape, Optional<String> purpose, Optional<String> ownerKind,
            Optional<String> counterparty, String currency, int scale, Side side, BigInteger total, long lines) throws SQLException {
        insert.setObject(1, checkpoint);
        insert.setString(2, shape);
        insert.setString(3, purpose.orElse(null));
        insert.setString(4, ownerKind.orElse(null));
        insert.setString(5, counterparty.orElse(null));
        insert.setString(6, currency);
        insert.setInt(7, scale);
        insert.setString(8, side.name());
        insert.setBigDecimal(9, new BigDecimal(total));
        insert.setLong(10, lines);
        insert.addBatch();
    }

    private static Side side(String direction) {
        return switch (direction) {
            case "DEBIT" -> Side.DEBIT;
            case "CREDIT" -> Side.CREDIT;
            default -> throw new IllegalStateException("a checkpoint's group is a debit or a credit");
        };
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
