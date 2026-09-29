package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** {@link RunReadings} over JDBC (ADR-0033: explicit SQL, no mapper); no locks. */
public final class JdbcRunReadings implements RunReadings {

    @Override
    public Map<UUID, Long> pendingCountBySource(Connection unitOfWork) {
        return counts(
                unitOfWork,
                "SELECT source_id, count(*) AS n FROM reconciliation.reconciliation_batch"
                        + " WHERE status IN ('OPEN', 'IN_PROGRESS') GROUP BY source_id");
    }

    @Override
    public Map<UUID, Instant> oldestPendingBySource(Connection unitOfWork) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT source_id, min(created_at) AS oldest"
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE status IN ('OPEN', 'IN_PROGRESS')"
                                + " GROUP BY source_id")) {
            try (ResultSet rows = read.executeQuery()) {
                Map<UUID, Instant> oldest = new HashMap<>();
                while (rows.next()) {
                    oldest.put(
                            rows.getObject("source_id", UUID.class),
                            rows.getObject("oldest", java.time.OffsetDateTime.class)
                                    .toInstant());
                }
                return Map.copyOf(oldest);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the oldest pending runs", failure);
        }
    }

    @Override
    public Map<UUID, Long> blockedCountBySource(Connection unitOfWork) {
        return counts(
                unitOfWork,
                "SELECT source_id, count(*) AS n FROM reconciliation.reconciliation_batch"
                        + " WHERE status = 'BLOCKED' GROUP BY source_id");
    }

    private Map<UUID, Long> counts(Connection unitOfWork, String sql) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            try (ResultSet rows = read.executeQuery()) {
                Map<UUID, Long> counts = new HashMap<>();
                while (rows.next()) {
                    counts.put(rows.getObject("source_id", UUID.class), rows.getLong("n"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the run counters", failure);
        }
    }
}
