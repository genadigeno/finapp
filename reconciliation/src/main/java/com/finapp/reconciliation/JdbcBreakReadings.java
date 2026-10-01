package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** {@link BreakReadings} over explicit JDBC - one GROUP BY, per refresh floor. */
public final class JdbcBreakReadings implements BreakReadings {

    @Override
    public List<OpenBreaks> openBreaks(Connection connection) {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT type, severity, count(*) AS open_count,"
                                + " min(raised_at) AS oldest FROM reconciliation.break"
                                + " WHERE status <> 'RESOLVED' GROUP BY type, severity")) {
            List<OpenBreaks> rows = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(
                            new OpenBreaks(
                                    BreakType.valueOf(row.getString("type")),
                                    Severity.valueOf(row.getString("severity")),
                                    row.getLong("open_count"),
                                    row.getObject("oldest", OffsetDateTime.class).toInstant()));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not count the open breaks", failure);
        }
    }
}
