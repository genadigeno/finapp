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
        // The birth permit is the dispatch itself (V009): the same bound value as created_at, so
        // the two are stored identically and the CHECK that the permit follows birth holds.
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.refund (" + COLUMNS
                                + ", dispatch_key, last_dispatched_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
            insert.setTimestamp(13, Timestamp.from(refund.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting refund " + refund.id(), failure));
        }
    }

    @Override
    public Optional<LockedRefund> lockForOutcome(Connection unitOfWork, RefundId refund) {
        Objects.requireNonNull(refund, "refund must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + ", last_dispatched_at FROM payments.refund"
                                + " WHERE id = ? FOR UPDATE")) {
            read.setObject(1, refund.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(
                                new LockedRefund(
                                        rehydrate(row),
                                        row.getTimestamp("last_dispatched_at").toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("locking refund " + refund, failure));
        }
    }

    @Override
    public Optional<Instant> renewSendPermit(Connection unitOfWork, RefundId refund, Instant at) {
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(at, "at must not be null");
        // The conditional IS the permit (the payout's renewal, V007): a resolver that already
        // moved the refund out of the resolvable states leaves this matching no row, and then
        // nothing may be sent. GREATEST keeps the permit forward-only against an instance whose
        // clock trails the one that wrote the previous permit - V009's trigger refuses a step
        // back, and a refusal here would be a failed re-drive, not a safer one.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE payments.refund"
                                + " SET last_dispatched_at ="
                                + "   GREATEST(last_dispatched_at, CAST(? AS timestamptz))"
                                + " WHERE id = ? AND status IN ('DISPATCHED', 'UNKNOWN')"
                                + " RETURNING last_dispatched_at")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, refund.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getTimestamp(1).toInstant())
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("renewing refund " + refund + "'s send permit",
                            failure));
        }
    }

    @Override
    public List<Refund> findSweepable(
            Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit) {
        Objects.requireNonNull(dispatchedBefore, "dispatchedBefore must not be null");
        Objects.requireNonNull(unknownBefore, "unknownBefore must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        // The two resolvable states, pinned as literals beside RefundStatus's own exact values()
        // pin - the attempt store's idiom. A DISPATCHED refund has waited since its latest send
        // permit (a takeover re-drive is a new send); an UNKNOWN one since the move that made it
        // UNKNOWN - the latest transition, birth as the fallback.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.refund r"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.refund_event e"
                                + "   WHERE e.refund_id = r.id) h ON true"
                                + " WHERE (r.status = 'DISPATCHED' AND r.last_dispatched_at <= ?)"
                                + "    OR (r.status = 'UNKNOWN'"
                                + "        AND COALESCE(h.entered, r.created_at) <= ?)"
                                + " ORDER BY r.created_at, r.id"
                                + " LIMIT ?")) {
            read.setTimestamp(1, Timestamp.from(dispatchedBefore));
            read.setTimestamp(2, Timestamp.from(unknownBefore));
            read.setInt(3, limit);
            try (ResultSet rows = read.executeQuery()) {
                List<Refund> sweepable = new ArrayList<>();
                while (rows.next()) {
                    sweepable.add(rehydrate(rows));
                }
                return List.copyOf(sweepable);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading sweepable refunds", failure));
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
    public PaymentAttemptStore.UnknownReading unknownReading(
            Connection unitOfWork, java.time.Duration dispatchedBound) {
        Objects.requireNonNull(dispatchedBound, "dispatchedBound must not be null");
        // The attempt store's reading, one machine across: refunds strand with a STANDING
        // HOLD behind them, so an operator watching parked money needs them in the same
        // series (P5-TSK-016's UNKNOWN, P5-TSK-017's gauge). Since the Phase 6 -> 7 transition
        // it is the payout's shape (P6-TSK-013): every UNKNOWN, and every DISPATCHED whose latest
        // permit is past the sweep's own bound - before which it is mid-question, and after
        // which a crashed dispatch is exactly as stuck as an unknown one. The SERVER's clock
        // decides the age (ADR-0014), a whole number of seconds - floor()::bigint, never a
        // double for JDBC to round (INV-MON-01's discipline) - floored at zero, because an
        // application clock a moment ahead of the database's must not publish a negative wait.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now() - min("
                                + "   CASE WHEN r.status = 'UNKNOWN'"
                                + "        THEN COALESCE(h.entered, r.created_at)"
                                + "        ELSE r.last_dispatched_at END)))::bigint, 0))"
                                + " FROM payments.refund r"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.refund_event e"
                                + "   WHERE e.refund_id = r.id) h ON true"
                                + " WHERE r.status = 'UNKNOWN'"
                                + "    OR (r.status = 'DISPATCHED'"
                                + "        AND r.last_dispatched_at"
                                + "            <= now() - make_interval(secs => ?))")) {
            read.setLong(1, dispatchedBound.toSeconds());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new PaymentAttemptStore.UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the stuck-refund gauge", failure));
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
    public Money sumCompletedFor(
            Connection unitOfWork, PaymentAttemptId attempt, CurrencyCode currency) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // COMPLETED only - see the port's javadoc: reserved is not returned,
                        // and a sibling refund in flight can still fail.
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM payments.refund"
                                + " WHERE attempt_id = ? AND status = 'COMPLETED'")) {
            read.setObject(1, attempt.value());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return Money.ofMinorUnits(row.getLong(1), currency);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "summing completed refunds of attempt " + attempt, failure));
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
