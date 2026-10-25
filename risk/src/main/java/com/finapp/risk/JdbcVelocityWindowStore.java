package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link VelocityWindowStore} over plain JDBC (`P13-TSK-006`; {@code risk V005}) - every statement a constant pinned by
 * {@code VelocityWindowStatementsTest}, every time the database's, every instant bound with its offset (never a
 * zone-less timestamp the session would re-interpret), every failure its operation and SQLSTATE only
 * ({@link RiskStorageException}).
 */
public final class JdbcVelocityWindowStore implements VelocityWindowStore {

    /**
     * risk's velocity namespace (`DISTRIBUTED_EXECUTION.md` section 3, registered by this task, its first writer):
     * (R2a), one key per (dimension, subject). {@code 13} is the policy administration's; {@code 14} is left to
     * Phase 14's administration - the 10-13 pattern.
     */
    public static final int NAMESPACE = 131;

    /**
     * R2a's statement: blocking, transaction-scoped, held to the caller's commit. The key is computed in Java
     * ({@link VelocityWindows#lockKey}) rather than by {@code hashtext} here, so the caller can take a movement's keys in
     * the keys' own ascending order - the one order every writer shares, collisions included.
     */
    static final String SERIALISE_SQL = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", ?)";

    /** The database's clock, on the caller's connection. */
    static final String NOW_SQL = "SELECT clock_timestamp()";

    private static final String COLUMNS = "w.dimension, w.subject_ref, w.window_kind, w.window_start, w.count,"
            + " w.sum_minor, w.currency, w.scale, w.last_counted_at";

    /** The current rows of a set of keys - one statement over four parallel arrays, no lock. */
    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM risk.velocity_window w"
            + " JOIN unnest(?::text[], ?::text[], ?::text[], ?::text[]) AS k(dimension, subject_ref, window_kind, window_start)"
            + " ON w.dimension = k.dimension AND w.subject_ref = k.subject_ref AND w.window_kind = k.window_kind"
            + " AND w.window_start = k.window_start::timestamptz";

    /**
     * (R2) the one statement - the row lock it takes on conflict is the arbiter, and the arithmetic is the database's,
     * so no figure read earlier is ever written back. The {@code WHERE} refuses to add a sum of another currency or
     * scale (a row is returned only when counted). {@code last_counted_at} is the trigger's stamp.
     */
    static final String UPSERT_SQL = "INSERT INTO risk.velocity_window AS w"
            + " (dimension, subject_ref, window_kind, window_start, count, sum_minor, currency, scale)"
            + " VALUES (?, ?, ?, ?, 1, ?, ?, ?)"
            + " ON CONFLICT (dimension, subject_ref, window_kind, window_start) DO UPDATE"
            + " SET count = w.count + 1, sum_minor = w.sum_minor + EXCLUDED.sum_minor"
            + " WHERE w.currency = EXCLUDED.currency AND w.scale = EXCLUDED.scale"
            + " RETURNING " + COLUMNS;

    /** The live windows: started within the longest window's length, and ending after the database clock now. */
    static final String LIVE_SQL = "SELECT " + COLUMNS + " FROM risk.velocity_window w"
            + " WHERE w.window_start > clock_timestamp() - interval '30 days'"
            + " AND w.window_start + risk.velocity_window_length(w.window_kind) > clock_timestamp()"
            + " ORDER BY w.dimension, w.subject_ref, w.window_kind, w.window_start";

    /**
     * A subject's first counted movement in a role (`P13-TSK-013`): the start of its earliest {@code H1} window - the
     * finest bucket, so the first sighting to the hour. One probe of the primary key's prefix
     * {@code (dimension, subject_ref, window_kind)}, no lock; the windows are never removed
     * ({@code velocity_window_is_never_removed}), so the earliest row stands.
     */
    static final String FIRST_COUNTED_SQL = "SELECT min(w.window_start) FROM risk.velocity_window w"
            + " WHERE w.dimension = ? AND w.subject_ref = ? AND w.window_kind = 'H1'";

    @Override
    public Optional<Instant> firstCounted(Connection unitOfWork, VelocityDimension dimension, String subjectRef) {
        try (PreparedStatement select = unitOfWork.prepareStatement(FIRST_COUNTED_SQL)) {
            select.setString(1, dimension.name());
            select.setString(2, subjectRef);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                OffsetDateTime first = row.getObject(1, OffsetDateTime.class);
                return first == null ? Optional.empty() : Optional.of(first.toInstant());
            }
        } catch (SQLException failure) {
            throw failed("reading a subject's first counted window", failure);
        }
    }

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(NOW_SQL);
                ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                throw new RiskStorageException("risk storage failed reading the database clock: no row");
            }
            return row.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public void serialise(Connection unitOfWork, int lockKey) {
        try (PreparedStatement lock = unitOfWork.prepareStatement(SERIALISE_SQL)) {
            lock.setInt(1, lockKey);
            lock.execute();
        } catch (SQLException failure) {
            throw failed("serialising a velocity subject", failure);
        }
    }

    @Override
    public List<VelocityWindow> find(Connection unitOfWork, Collection<VelocityWindowKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> dimensions = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        for (VelocityWindowKey key : keys) {
            dimensions.add(key.dimension().name());
            subjects.add(key.subjectRef());
            kinds.add(key.windowKind().name());
            // ISO-8601 with its 'Z' - read by timestamptz's input as the instant it is, whatever the session's zone.
            starts.add(key.windowStart().toString());
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(FIND_SQL)) {
            select.setArray(1, unitOfWork.createArrayOf("text", dimensions.toArray()));
            select.setArray(2, unitOfWork.createArrayOf("text", subjects.toArray()));
            select.setArray(3, unitOfWork.createArrayOf("text", kinds.toArray()));
            select.setArray(4, unitOfWork.createArrayOf("text", starts.toArray()));
            return rows(select);
        } catch (SQLException failure) {
            throw failed("reading velocity windows", failure);
        }
    }

    @Override
    public Optional<VelocityWindow> upsert(Connection unitOfWork, VelocityWindowKey key, Money amount) {
        try (PreparedStatement upsert = unitOfWork.prepareStatement(UPSERT_SQL)) {
            upsert.setString(1, key.dimension().name());
            upsert.setString(2, key.subjectRef());
            upsert.setString(3, key.windowKind().name());
            upsert.setObject(4, OffsetDateTime.ofInstant(key.windowStart(), ZoneOffset.UTC));
            upsert.setLong(5, amount.minorUnits());
            upsert.setString(6, amount.currency().code());
            upsert.setInt(7, amount.scale());
            List<VelocityWindow> counted = rows(upsert);
            return counted.isEmpty() ? Optional.empty() : Optional.of(counted.getFirst());
        } catch (SQLException failure) {
            throw failed("counting a velocity window", failure);
        }
    }

    @Override
    public List<VelocityWindow> live(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(LIVE_SQL)) {
            return rows(select);
        } catch (SQLException failure) {
            throw failed("reading the live velocity windows", failure);
        }
    }

    private static List<VelocityWindow> rows(PreparedStatement statement) throws SQLException {
        List<VelocityWindow> windows = new ArrayList<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                VelocityWindowKey key = new VelocityWindowKey(VelocityDimension.valueOf(row.getString("dimension")),
                        row.getString("subject_ref"), WindowKind.valueOf(row.getString("window_kind")),
                        row.getObject("window_start", OffsetDateTime.class).toInstant());
                Money sum = Money.ofPersisted(row.getLong("sum_minor"), CurrencyCode.of(row.getString("currency")),
                        row.getInt("scale"));
                windows.add(new VelocityWindow(key, row.getLong("count"), sum,
                        row.getObject("last_counted_at", OffsetDateTime.class).toInstant()));
            }
        }
        return windows;
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        // The SQLSTATE only, never the driver's message: it can carry a subject or a figure from the row.
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
