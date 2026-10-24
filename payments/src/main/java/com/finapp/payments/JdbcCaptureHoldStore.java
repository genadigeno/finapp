package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** {@link CaptureHoldStore} over JDBC (ADR-0033: explicit SQL, no mapper; payments {@code V034}). */
public final class JdbcCaptureHoldStore implements CaptureHoldStore<Connection> {

    private static final String TABLE = "payments.payment_capture_hold";

    private static final String COLUMNS =
            "intent_id, attempt_id, held_at, held_until, resolution, resolution_key, resolved_at";

    @Override
    public void insert(
            Connection unitOfWork, PaymentIntentId intent, PaymentAttemptId attempt, Duration validity) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(validity, "validity must not be null");
        if (validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("a capture hold's validity is positive");
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        // The window on the DATABASE clock: the expiry leg and the capture door compare held_until
                        // with statement_timestamp(), so it is stamped by the same clock, never an instance's.
                        "INSERT INTO " + TABLE + " (intent_id, attempt_id, held_at, held_until)"
                                + " VALUES (?, ?, statement_timestamp(),"
                                + " statement_timestamp() + CAST(? AS bigint) * INTERVAL '1 microsecond')")) {
            insert.setObject(1, intent.value());
            insert.setObject(2, attempt.value());
            insert.setLong(3, TimeUnit.NANOSECONDS.toMicros(validity.toNanos()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("holding the capture of payment intent " + intent, failure));
        }
    }

    @Override
    public Optional<CaptureHold> findForIntent(Connection unitOfWork, PaymentIntentId intent) {
        return one(unitOfWork, intent, "", "reading the capture hold of " + intent);
    }

    @Override
    public Optional<CaptureHold> lock(Connection unitOfWork, PaymentIntentId intent) {
        return one(unitOfWork, intent, " FOR UPDATE", "locking the capture hold of " + intent);
    }

    @Override
    public boolean resolveCapture(Connection unitOfWork, PaymentIntentId intent, String key) {
        return resolved(unitOfWork, intent, "CAPTURE", key, " AND held_until > statement_timestamp()");
    }

    @Override
    public boolean resolveVoid(Connection unitOfWork, PaymentIntentId intent, String key) {
        return resolved(unitOfWork, intent, "VOID", key, "");
    }

    @Override
    public Optional<CaptureHold> claimExpired(
            Connection unitOfWork, java.util.Collection<PaymentIntentId> passedOver) {
        Objects.requireNonNull(passedOver, "passedOver must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // The partial index payment_capture_hold_expiry serves this; SKIP LOCKED so a door holding the
                        // row is never waited on - it decides, and the next tick finds nothing to expire.
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE resolution IS NULL AND held_until <= statement_timestamp()"
                                + " AND NOT (intent_id = ANY (?))"
                                + " ORDER BY held_until, intent_id LIMIT 1"
                                + " FOR UPDATE SKIP LOCKED")) {
            read.setArray(1, unitOfWork.createArrayOf(
                    "uuid", passedOver.stream().map(PaymentIntentId::value).toArray()));
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("claiming an expired capture hold", failure));
        }
    }

    @Override
    public boolean expire(Connection unitOfWork, PaymentIntentId intent) {
        return resolved(unitOfWork, intent, "EXPIRED", null, " AND held_until <= statement_timestamp()");
    }

    @Override
    public List<CaptureHold> voidsToFinish(Connection unitOfWork, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Driven from the attempt side: AUTHORIZED attempts are the few; a resolved hold beside a
                        // released or captured attempt is history and never read here.
                        "SELECT h.intent_id, h.attempt_id, h.held_at, h.held_until, h.resolution,"
                                + " h.resolution_key, h.resolved_at"
                                + " FROM payments.payment_attempt a"
                                + " JOIN " + TABLE + " h ON h.attempt_id = a.id"
                                + " WHERE a.status = 'AUTHORIZED' AND h.resolution IN ('VOID', 'EXPIRED')"
                                + " ORDER BY h.resolved_at, h.intent_id LIMIT ?")) {
            read.setInt(1, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<CaptureHold> holds = new ArrayList<>();
                while (rows.next()) {
                    holds.add(rehydrate(rows));
                }
                return List.copyOf(holds);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the capture holds with a void to finish", failure));
        }
    }

    private Optional<CaptureHold> one(
            Connection unitOfWork, PaymentIntentId intent, String lock, String doing) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE intent_id = ?" + lock)) {
            read.setObject(1, intent.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    /** One conditional statement: {@code resolution IS NULL} the arbiter, the trigger stamping {@code resolved_at}. */
    private boolean resolved(
            Connection unitOfWork, PaymentIntentId intent, String resolution, String key, String window) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET resolution = ?, resolution_key = ?"
                                + " WHERE intent_id = ? AND resolution IS NULL" + window)) {
            update.setString(1, resolution);
            update.setString(2, key);
            update.setObject(3, intent.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("resolving the capture hold of " + intent, failure));
        }
    }

    private static CaptureHold rehydrate(ResultSet row) throws SQLException {
        Timestamp resolvedAt = row.getTimestamp("resolved_at");
        String resolution = row.getString("resolution");
        return new CaptureHold(
                PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                PaymentAttemptId.of(row.getObject("attempt_id", UUID.class)),
                row.getTimestamp("held_at").toInstant(),
                row.getTimestamp("held_until").toInstant(),
                Optional.ofNullable(resolution).map(CaptureHold.Resolution::valueOf),
                Optional.ofNullable(row.getString("resolution_key")),
                Optional.ofNullable(resolvedAt).map(Timestamp::toInstant));
    }
}
