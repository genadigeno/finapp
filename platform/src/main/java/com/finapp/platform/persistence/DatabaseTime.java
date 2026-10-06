package com.finapp.platform.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;

/**
 * The database's clock, read inside a unit of work ({@code X-TSK-013}).
 *
 * <p>A send permit is stamped by the database ({@code statement_timestamp()}), so a bound judged
 * against it must come from the same clock: a bound from the sweeping instance's clock judged
 * against a permit from the database's sees every permit older than it is whenever that instance
 * runs ahead - the skew premise ADR-0057 section 4 had to state, and this removes. Each instance's
 * clock still stamps what it alone decides; a window over a database-stamped value reads this.
 */
public final class DatabaseTime {

    private DatabaseTime() {}

    /** {@code statement_timestamp()} of a statement run on this unit of work's connection. */
    public static Instant now(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (Statement read = unitOfWork.createStatement();
                ResultSet row = read.executeQuery("SELECT statement_timestamp()")) {
            if (!row.next()) {
                throw new IllegalStateException("the database answered no time");
            }
            return row.getTimestamp(1).toInstant();
        } catch (SQLException unreadable) {
            throw new IllegalStateException(
                    DatabaseFailure.describe("reading the database clock", unreadable));
        }
    }
}
