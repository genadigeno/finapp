package com.finapp.party;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link CustomerReports} over JDBC (`P17-TSK-039`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>One statement, so the standing counts and the window's are one snapshot's. It reads the table whole: the standing counts are
 * every customer's, and no index would spare that - the overview reads it rarely, and its plan on a large table is owed
 * (`OPERATIONS_RUNBOOK.md` §11.48). An opening sets {@code status_changed_at} to {@code opened_at}, so a change in the window is
 * one after the opening.
 */
public final class JdbcCustomerReports implements CustomerReports {

    private static final String COUNTS =
            "SELECT status, count(*) AS standing,"
                    + " count(*) FILTER (WHERE opened_at >= ? AND opened_at < ?) AS opened,"
                    + " count(*) FILTER (WHERE status_changed_at >= ? AND status_changed_at < ?"
                    + " AND status_changed_at > opened_at) AS changed"
                    + " FROM party.customer GROUP BY status ORDER BY status";

    @Override
    public List<StatusCount> counts(Connection unitOfWork, Instant from, Instant to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(COUNTS)) {
            Timestamp start = Timestamp.from(from);
            Timestamp end = Timestamp.from(to);
            select.setTimestamp(1, start);
            select.setTimestamp(2, end);
            select.setTimestamp(3, start);
            select.setTimestamp(4, end);
            List<StatusCount> counts = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.add(new StatusCount(CustomerStatus.valueOf(row.getString("status")), row.getLong("standing"),
                            row.getLong("opened"), row.getLong("changed")));
                }
            }
            return List.copyOf(counts);
        } catch (SQLException failure) {
            throw new PartyStorageException(DatabaseFailure.describe("counting the customers", failure));
        }
    }
}
