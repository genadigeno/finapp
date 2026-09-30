package com.finapp.settlement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PullPermitStore} over JDBC (`P8-TSK-021`, ADR-0033: explicit SQL, no ORM). The upsert
 * IS the permit: its {@code WHERE} admits a renewal only past the window, and every renewal
 * advances the instant strictly — the refund's send permit's rule, so two takers never both read
 * one instant as theirs.
 */
public final class JdbcPullPermitStore implements PullPermitStore<Connection> {

    private static final String ADVANCE =
            " last_attempt_at = GREATEST("
                    + "   settlement.pull_permit.last_attempt_at + interval '1 microsecond',"
                    + "   EXCLUDED.last_attempt_at),"
                    + " attempts = settlement.pull_permit.attempts + 1";

    @Override
    public boolean claim(
            Connection unitOfWork,
            UUID sourceId,
            String businessKey,
            Instant at,
            Duration window) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(window, "window must not be null");
        if (window.isNegative()) {
            throw new IllegalArgumentException("a pull window is never negative: " + window);
        }
        try (PreparedStatement upsert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.pull_permit (source_id, business_key,"
                                + " last_attempt_at, attempts, created_at)"
                                + " VALUES (?, ?, ?, 1, ?)"
                                + " ON CONFLICT (source_id, business_key) DO UPDATE SET"
                                + ADVANCE
                                + " WHERE settlement.pull_permit.last_attempt_at"
                                + "   <= EXCLUDED.last_attempt_at - CAST(? AS interval)"
                                + " RETURNING attempts")) {
            Timestamp instant = Timestamp.from(at);
            upsert.setObject(1, sourceId);
            upsert.setString(2, businessKey);
            upsert.setTimestamp(3, instant);
            upsert.setTimestamp(4, instant);
            upsert.setString(5, window.toMillis() + " milliseconds");
            try (ResultSet row = upsert.executeQuery()) {
                return row.next();
            }
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not take a pull permit", failure);
        }
    }

    @Override
    public void renew(Connection unitOfWork, UUID sourceId, String businessKey, Instant at) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement upsert =
                unitOfWork.prepareStatement(
                        "INSERT INTO settlement.pull_permit (source_id, business_key,"
                                + " last_attempt_at, attempts, created_at)"
                                + " VALUES (?, ?, ?, 1, ?)"
                                + " ON CONFLICT (source_id, business_key) DO UPDATE SET"
                                + ADVANCE)) {
            Timestamp instant = Timestamp.from(at);
            upsert.setObject(1, sourceId);
            upsert.setString(2, businessKey);
            upsert.setTimestamp(3, instant);
            upsert.setTimestamp(4, instant);
            upsert.executeUpdate();
        } catch (SQLException failure) {
            throw new SettlementStorageException("could not renew a pull permit", failure);
        }
    }
}
