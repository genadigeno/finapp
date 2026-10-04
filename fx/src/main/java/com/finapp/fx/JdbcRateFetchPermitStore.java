package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Plain-JDBC pacing permits (`P9-TSK-005`): the {@code JdbcPullPermitStore} upsert, stamped by
 * the database. Racing claimants serialise on the row inside {@code ON CONFLICT DO UPDATE}, and
 * each re-evaluates the {@code WHERE} against the winner's committed attempt, so one claims per
 * window. {@code GREATEST(last + 1 µs, now)} keeps the trigger's strictly-forward rule even for a
 * claimant whose statement began before the winner's commit.
 */
public final class JdbcRateFetchPermitStore implements RateFetchPermitStore<Connection> {

    @Override
    public boolean claim(Connection unitOfWork, String source, Duration window) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative()) {
            throw new IllegalArgumentException("a fetch window is never negative: " + window);
        }
        try (PreparedStatement upsert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.rate_fetch_permit (source, last_attempt_at, attempts,"
                                + " created_at)"
                                + " VALUES (?, statement_timestamp(), 1, statement_timestamp())"
                                + " ON CONFLICT (source) DO UPDATE SET"
                                + " last_attempt_at = GREATEST("
                                + "   fx.rate_fetch_permit.last_attempt_at"
                                + "     + interval '1 microsecond',"
                                + "   statement_timestamp()),"
                                + " attempts = fx.rate_fetch_permit.attempts + 1"
                                + " WHERE fx.rate_fetch_permit.last_attempt_at"
                                + "   <= statement_timestamp() - CAST(? AS interval)"
                                + " RETURNING attempts")) {
            upsert.setString(1, source);
            upsert.setString(2, window.toMillis() + " milliseconds");
            try (ResultSet row = upsert.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new FxStorageException(
                    DatabaseFailure.describe("taking a rate fetch permit", failure), failure);
        }
    }
}
