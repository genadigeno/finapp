package com.finapp.risk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-JDBC storage for the monitoring watermark (`P13-TSK-019`; ADR-0033) over {@code risk.monitoring_watermark}:
 * the claim {@code FOR UPDATE SKIP LOCKED} judged on {@code clock_timestamp()} (the bnpl sweeps' idiom), the waiting
 * lock for the door, the lock-free read for the gauges, the move under the caller's lock with the permit re-stamped on
 * the database clock, and the hint's one conditional statement. {@code last_sequence} and {@code advanced_at} are
 * never named in a write: the schema's trigger derives them.
 */
public final class JdbcMonitoringWatermarkStore implements MonitoringWatermarkStore {

    private static final String TABLE = "risk.monitoring_watermark";
    private static final String COLUMNS = "source, last_entry_id, last_sequence, next_sweep_at";

    @Override
    public Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source) {
        return select(unitOfWork, source,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE source = ? AND next_sweep_at <= clock_timestamp() FOR UPDATE SKIP LOCKED",
                "claiming the monitoring watermark");
    }

    @Override
    public Optional<MonitoringWatermark> lock(Connection unitOfWork, String source) {
        return select(unitOfWork, source,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE source = ? FOR UPDATE",
                "locking the monitoring watermark");
    }

    @Override
    public Optional<MonitoringWatermark> read(Connection unitOfWork, String source) {
        return select(unitOfWork, source,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE source = ?",
                "reading the monitoring watermark");
    }

    @Override
    public void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lastEntryId, "lastEntryId");
        Objects.requireNonNull(permitDelay, "permitDelay");
        if (permitDelay.isNegative()) {
            throw new IllegalArgumentException("a permit delay is not negative");
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET last_entry_id = ?,"
                        + " next_sweep_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE source = ?")) {
            update.setObject(1, lastEntryId);
            update.setLong(2, permitDelay.toMillis());
            update.setString(3, source);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("the monitoring watermark being moved is not there");
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("moving the monitoring watermark", failure);
        }
    }

    @Override
    public boolean hint(Connection unitOfWork, String source) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // ONE statement: the row a sweep holds is skipped (it is sweeping), a permit already due is left.
                "UPDATE " + TABLE + " SET next_sweep_at = clock_timestamp()"
                        + " WHERE source IN (SELECT source FROM " + TABLE
                        + " WHERE source = ? AND next_sweep_at > clock_timestamp() FOR UPDATE SKIP LOCKED)")) {
            update.setString(1, source);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed("hinting the monitoring watermark", failure);
        }
    }

    private static Optional<MonitoringWatermark> select(Connection unitOfWork, String source, String sql,
            String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setString(1, source);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MonitoringWatermark(row.getString("source"),
                        row.getObject("last_entry_id", UUID.class), row.getLong("last_sequence"),
                        row.getTimestamp("next_sweep_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw JdbcMonitoredTransactionStore.failed(operation, failure);
        }
    }
}
