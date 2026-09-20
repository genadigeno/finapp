package com.finapp.payments;

import com.finapp.ledger.HoldId;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link RefundStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcRefundStore implements RefundStore<Connection> {

    private static final String COLUMNS =
            "id, attempt_id, amount_minor, currency, scale, reason, hold_reference,"
                    + " provider_idempotency_reference, provider_reference, status, created_at";

    @Override
    public void insert(Connection unitOfWork, Refund refund, String dispatchKey) {
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null (V008)");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.refund (" + COLUMNS + ", dispatch_key)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, refund.id().value());
            insert.setObject(2, refund.attemptId().value());
            insert.setLong(3, refund.amount().minorUnits());
            insert.setString(4, refund.amount().currency().code());
            insert.setShort(5, (short) refund.amount().scale());
            insert.setString(6, refund.reason());
            insert.setObject(7, refund.holdReference().value());
            insert.setString(8, refund.providerIdempotencyReference().value());
            insert.setString(
                    9,
                    refund.providerReference() == null
                            ? null
                            : refund.providerReference().value());
            insert.setString(10, refund.status().name());
            insert.setTimestamp(11, Timestamp.from(refund.createdAt()));
            insert.setString(12, dispatchKey);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting refund " + refund.id(), failure));
        }
    }

    @Override
    public Optional<Refund> findById(Connection unitOfWork, RefundId refund) {
        Objects.requireNonNull(refund, "refund must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.refund WHERE id = ?")) {
            read.setObject(1, refund.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading refund " + refund, failure));
        }
    }

    @Override
    public Optional<Refund> findByDispatchKey(Connection unitOfWork, String dispatchKey) {
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.refund"
                                + " WHERE dispatch_key = ?"
                                + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
            read.setString(1, dispatchKey);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading refund by dispatch key", failure));
        }
    }

    @Override
    public Optional<Refund> findByOperationReference(
            Connection unitOfWork, ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Both INV-PAY-04 columns (the attempt-store shape): the reference we
                        // minted before the wire, and the provider's own from a committed
                        // completion. Distinct vocabularies, so at most one row matches.
                        "SELECT " + COLUMNS + " FROM payments.refund"
                                + " WHERE provider_idempotency_reference = ?"
                                + " OR provider_reference = ?")) {
            read.setString(1, reference.value());
            read.setString(2, reference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading refund by operation reference", failure));
        }
    }

    @Override
    public List<Refund> listFor(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.refund"
                                + " WHERE attempt_id = ? ORDER BY created_at, id")) {
            read.setObject(1, attempt.value());
            try (ResultSet rows = read.executeQuery()) {
                List<Refund> refunds = new ArrayList<>();
                while (rows.next()) {
                    refunds.add(rehydrate(rows));
                }
                return List.copyOf(refunds);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("listing refunds of attempt " + attempt, failure));
        }
    }

    @Override
    public Money sumNonFailedFor(
            Connection unitOfWork, PaymentAttemptId attempt, CurrencyCode currency) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // Valid only under the command's FOR UPDATE on the attempt row (the
                        // lock-then-look contract, P5-TSK-015): the lock is what makes this
                        // sum current rather than a write-skew snapshot.
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM payments.refund"
                                + " WHERE attempt_id = ? AND status <> 'FAILED'")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return Money.ofMinorUnits(row.getLong(1), currency);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "summing refunds of attempt " + attempt, failure));
        }
    }

    @Override
    public boolean complete(
            Connection unitOfWork,
            RefundId refund,
            RefundStatus from,
            ProviderReference providerReference) {
        requireLegal(refund, from, RefundStatus.COMPLETED);
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.refund SET status = ?, provider_reference = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, RefundStatus.COMPLETED.name());
            update.setString(2, providerReference.value());
            update.setObject(3, refund.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("completing refund " + refund, failure));
        }
    }

    @Override
    public boolean fail(Connection unitOfWork, RefundId refund, RefundStatus from) {
        requireLegal(refund, from, RefundStatus.FAILED);
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.refund SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, RefundStatus.FAILED.name());
            update.setObject(2, refund.value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("failing refund " + refund, failure));
        }
    }

    @Override
    public boolean markUnknown(Connection unitOfWork, RefundId refund) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.refund SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, RefundStatus.UNKNOWN.name());
            update.setObject(2, refund.value());
            update.setString(3, RefundStatus.DISPATCHED.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("marking refund " + refund + " unknown", failure));
        }
    }

    @Override
    public void recordTransition(
            Connection unitOfWork,
            RefundId refund,
            RefundStatus from,
            RefundStatus to,
            Actor actor,
            Instant occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.refund_event"
                                + " (refund_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, refund.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "recording refund transition " + from + " -> " + to, failure));
        }
    }

    private static void requireLegal(RefundId refund, RefundStatus from, RefundStatus to) {
        // The machine's own legality first (INV-LIFE-02 in the writer too): a store asked for
        // an edge the machine does not carry is a caller defect, loud - never a quiet 0.
        if (!from.canTransitionTo(to)) {
            throw new IllegalRefundTransitionException(refund, from, to);
        }
    }

    private static Refund rehydrate(ResultSet row) throws SQLException {
        String providerReference = row.getString("provider_reference");
        return Refund.rehydrate(
                RefundId.of(row.getObject("id", UUID.class)),
                PaymentAttemptId.of(row.getObject("attempt_id", UUID.class)),
                Money.ofMinorUnits(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency"))),
                row.getString("reason"),
                HoldId.of(row.getObject("hold_reference", UUID.class)),
                new ProviderIdempotencyReference(
                        row.getString("provider_idempotency_reference")),
                providerReference == null ? null : new ProviderReference(providerReference),
                RefundStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant());
    }
}
