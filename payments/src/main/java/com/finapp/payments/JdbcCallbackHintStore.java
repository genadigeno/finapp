package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** {@link CallbackHintStore} over JDBC (ADR-0033, {@code V036}). */
@RequiredArgsConstructor
public final class JdbcCallbackHintStore implements CallbackHintStore<Connection> {

    private static final String COLUMNS = "id, kind, attempt_id, asked_about, next_inquiry_at";

    @NonNull private final IdGenerator ids;

    @Override
    public CallbackHint insert(
            Connection unitOfWork, CallbackHint.Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout,
            Duration firstAsk) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(askedAbout, "askedAbout must not be null");
        UUID id = ids.next();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.callback_hint (id, kind, attempt_id, asked_about, next_inquiry_at)"
                                + " VALUES (?, ?, ?, ?, statement_timestamp() + make_interval(secs => ?))"
                                + " RETURNING next_inquiry_at")) {
            insert.setObject(1, id);
            insert.setString(2, kind.name());
            insert.setObject(3, attempt.map(PaymentAttemptId::value).orElse(null));
            insert.setString(4, askedAbout);
            insert.setDouble(5, seconds(firstAsk));
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new CallbackHint(id, kind, attempt, askedAbout, row.getTimestamp(1).toInstant());
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("recording a callback hint", failure));
        }
    }

    @Override
    public List<CallbackHint> findDue(Connection unitOfWork, int batchSize) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.callback_hint"
                                + " WHERE concluded_at IS NULL AND next_inquiry_at <= statement_timestamp()"
                                + " ORDER BY next_inquiry_at LIMIT ?")) {
            read.setInt(1, batchSize);
            try (ResultSet rows = read.executeQuery()) {
                List<CallbackHint> due = new ArrayList<>();
                while (rows.next()) {
                    UUID attempt = rows.getObject("attempt_id", UUID.class);
                    due.add(new CallbackHint(
                            rows.getObject("id", UUID.class),
                            CallbackHint.Kind.valueOf(rows.getString("kind")),
                            Optional.ofNullable(attempt).map(PaymentAttemptId::of),
                            rows.getString("asked_about"),
                            rows.getTimestamp("next_inquiry_at").toInstant()));
                }
                return due;
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading the due callback hints", failure));
        }
    }

    @Override
    public boolean renewPermit(Connection unitOfWork, CallbackHint hint, Duration retryAfter) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.callback_hint"
                                + " SET next_inquiry_at = statement_timestamp() + make_interval(secs => ?)"
                                + " WHERE id = ? AND concluded_at IS NULL AND next_inquiry_at = ?")) {
            update.setDouble(1, seconds(retryAfter));
            update.setObject(2, hint.id());
            update.setTimestamp(3, Timestamp.from(hint.nextInquiryAt()));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("renewing a callback hint's permit", failure));
        }
    }

    @Override
    public boolean conclude(Connection unitOfWork, UUID hint, HintedOutcome conclusion) {
        Objects.requireNonNull(hint, "hint must not be null");
        if (!conclusion.concludes()) {
            throw new IllegalArgumentException("UNDECIDED concludes nothing");
        }
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.callback_hint SET concluded_at = statement_timestamp(), conclusion = ?"
                                + " WHERE id = ? AND concluded_at IS NULL")) {
            update.setString(1, conclusion.name());
            update.setObject(2, hint);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("concluding a callback hint", failure));
        }
    }

    @Override
    public int expire(Connection unitOfWork, Duration giveUpAfter, int batchSize) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.callback_hint SET concluded_at = statement_timestamp(), conclusion = ?"
                                + " WHERE id IN (SELECT id FROM payments.callback_hint"
                                + " WHERE concluded_at IS NULL"
                                + " AND received_at <= statement_timestamp() - make_interval(secs => ?)"
                                + " ORDER BY received_at LIMIT ? FOR UPDATE SKIP LOCKED)")) {
            update.setString(1, HintedOutcome.EXPIRED.name());
            update.setDouble(2, seconds(giveUpAfter));
            update.setInt(3, batchSize);
            return update.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("expiring callback hints", failure));
        }
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000d;
    }
}
