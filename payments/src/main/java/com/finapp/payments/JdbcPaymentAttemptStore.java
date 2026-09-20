package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link PaymentAttemptStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcPaymentAttemptStore implements PaymentAttemptStore<Connection> {

    private static final String COLUMNS =
            "id, intent_id, auth_reference, capture_reference, auth_provider_reference,"
                    + " capture_provider_reference, authorized_amount_minor,"
                    + " authorized_currency, authorized_scale, captured_amount_minor,"
                    + " captured_currency, captured_scale, failure_reason, status, created_at";

    @Override
    public void insert(Connection unitOfWork, PaymentAttempt attempt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_attempt (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, attempt.id().value());
            insert.setObject(2, attempt.intentId().value());
            insert.setString(3, attempt.authorizationReference().value());
            insert.setString(
                    4,
                    attempt.captureReference() == null
                            ? null
                            : attempt.captureReference().value());
            insert.setString(
                    5,
                    attempt.authorizationProviderReference() == null
                            ? null
                            : attempt.authorizationProviderReference().value());
            insert.setString(
                    6,
                    attempt.captureProviderReference() == null
                            ? null
                            : attempt.captureProviderReference().value());
            setMoney(insert, 7, attempt.authorizedAmount());
            setMoney(insert, 10, attempt.capturedAmount());
            insert.setString(
                    13, attempt.failureReason() == null ? null : attempt.failureReason().name());
            insert.setString(14, attempt.status().name());
            insert.setTimestamp(15, Timestamp.from(attempt.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "inserting payment attempt " + attempt.id(), failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findForIntent(
            Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE intent_id = ?"
                                + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
            read.setObject(1, intent.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading the attempt of intent " + intent, failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findByOperationReference(
            Connection unitOfWork, ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Either minted reference names the attempt: the authorization's or
                        // the capture's - both stored before anything was sent (INV-PAY-04),
                        // both carrying V003's plain UNIQUEs, so at most one row answers.
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt"
                                + " WHERE auth_reference = ? OR capture_reference = ?")) {
            read.setString(1, reference.value());
            read.setString(2, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an attempt by operation reference", failure));
        }
    }

    @Override
    public Optional<PaymentAttempt> findById(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_attempt WHERE id = ?")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean dispatchCapture(
            Connection unitOfWork, PaymentAttemptId attempt,
            ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?, capture_reference = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURE_DISPATCHED.name());
            update.setString(2, reference.value());
            update.setObject(3, attempt.value());
            update.setString(4, PaymentAttemptStatus.AUTHORIZED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "dispatching the capture of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean capture(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money capturedAmount) {
        requireLegal(attempt, from, PaymentAttemptStatus.CAPTURED);
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(capturedAmount, "capturedAmount must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " capture_provider_reference = ?, captured_amount_minor = ?,"
                                + " captured_currency = ?, captured_scale = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURED.name());
            update.setString(2, providerReference.value());
            update.setLong(3, capturedAmount.minorUnits());
            update.setString(4, capturedAmount.currency().code());
            update.setShort(5, (short) capturedAmount.scale());
            update.setObject(6, attempt.value());
            update.setString(7, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("capturing attempt " + attempt, failure));
        }
    }

    @Override
    public boolean markCaptureUnknown(Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.CAPTURE_UNKNOWN.name());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.CAPTURE_DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "marking the capture of attempt " + attempt + " unknown", failure));
        }
    }

    @Override
    public boolean authorize(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money authorizedAmount) {
        requireLegal(attempt, from, PaymentAttemptStatus.AUTHORIZED);
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(authorizedAmount, "authorizedAmount must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?,"
                                + " auth_provider_reference = ?, authorized_amount_minor = ?,"
                                + " authorized_currency = ?, authorized_scale = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.AUTHORIZED.name());
            update.setString(2, providerReference.value());
            update.setLong(3, authorizedAmount.minorUnits());
            update.setString(4, authorizedAmount.currency().code());
            update.setShort(5, (short) authorizedAmount.scale());
            update.setObject(6, attempt.value());
            update.setString(7, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "authorizing payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean fail(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentFailureReason reason) {
        requireLegal(attempt, from, PaymentAttemptStatus.FAILED);
        Objects.requireNonNull(reason, "reason must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?, failure_reason = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.FAILED.name());
            update.setString(2, reason.name());
            update.setObject(3, attempt.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("failing payment attempt " + attempt, failure));
        }
    }

    @Override
    public boolean markAuthUnknown(Connection unitOfWork, PaymentAttemptId attempt) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.payment_attempt SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, PaymentAttemptStatus.AUTH_UNKNOWN.name());
            update.setObject(2, attempt.value());
            update.setString(3, PaymentAttemptStatus.AUTH_DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "marking payment attempt " + attempt + " unknown", failure));
        }
    }

    @Override
    public void recordTransition(
            Connection unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentAttemptStatus to,
            Actor actor,
            Instant occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_attempt_event"
                                + " (attempt_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, attempt.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "recording the transition of payment attempt " + attempt, failure));
        }
    }

    /** The machine's legality in the writer too — an illegal ask is a caller defect, loud. */
    private static void requireLegal(
            PaymentAttemptId attempt, PaymentAttemptStatus from, PaymentAttemptStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalPaymentAttemptTransitionException(attempt, from, to);
        }
    }

    private static void setMoney(PreparedStatement statement, int firstIndex, Money amount)
            throws SQLException {
        if (amount == null) {
            statement.setObject(firstIndex, null);
            statement.setString(firstIndex + 1, null);
            statement.setObject(firstIndex + 2, null);
        } else {
            statement.setLong(firstIndex, amount.minorUnits());
            statement.setString(firstIndex + 1, amount.currency().code());
            statement.setShort(firstIndex + 2, (short) amount.scale());
        }
    }

    private static PaymentAttempt rehydrate(ResultSet row) throws SQLException {
        String captureReference = row.getString("capture_reference");
        String authProviderReference = row.getString("auth_provider_reference");
        String captureProviderReference = row.getString("capture_provider_reference");
        String reason = row.getString("failure_reason");
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.of(row.getObject("id", UUID.class)),
                PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                new ProviderIdempotencyReference(row.getString("auth_reference")),
                captureReference == null
                        ? null
                        : new ProviderIdempotencyReference(captureReference),
                authProviderReference == null
                        ? null
                        : new ProviderReference(authProviderReference),
                readMoney(row, "authorized_amount_minor", "authorized_currency",
                        "authorized_scale"),
                captureProviderReference == null
                        ? null
                        : new ProviderReference(captureProviderReference),
                readMoney(row, "captured_amount_minor", "captured_currency", "captured_scale"),
                reason == null ? null : PaymentFailureReason.valueOf(reason),
                PaymentAttemptStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant());
    }

    private static Money readMoney(
            ResultSet row, String minorColumn, String currencyColumn, String scaleColumn)
            throws SQLException {
        long minor = row.getLong(minorColumn);
        if (row.wasNull()) {
            return null;
        }
        // The STORED scale, never re-derived (INV-MON-05).
        return Money.ofPersisted(
                minor, CurrencyCode.of(row.getString(currencyColumn)),
                row.getShort(scaleColumn));
    }
}
