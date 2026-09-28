package com.finapp.payments;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
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

/**
 * {@link WithdrawalStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcMerchantPayoutStore} statement for statement, because the protocols are that
 * store's: the conditional transition with its history row, the permit renewal whose
 * conditional IS the permit, the dispatch-key convergence read, and the sweepable query
 * ageing {@code UNKNOWN} from its entering move on the event trail.
 */
public final class JdbcWithdrawalStore implements WithdrawalStore<Connection> {

    private static final String TABLE = "payments.withdrawal";

    private static final String COLUMNS =
            "id, party_id, customer_id, wallet_account_id, payment_method_id,"
                    + " destination_reference, amount_minor, currency, scale,"
                    + " end_to_end_reference, rail, status, failure_reason,"
                    + " scheme_reference, settlement_cycle, dispatch_key, hold_reference,"
                    + " created_at, last_dispatched_at";

    @Override
    public void insert(Connection unitOfWork, Withdrawal dispatched, String dispatchKey) {
        Objects.requireNonNull(dispatched, "dispatched must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, dispatched.id().value());
            insert.setObject(2, dispatched.partyId());
            insert.setObject(3, dispatched.customerId());
            insert.setObject(4, dispatched.walletAccountId().value());
            insert.setObject(5, dispatched.paymentMethodId());
            insert.setString(6, dispatched.destination().value());
            insert.setLong(7, MoneyColumns.amountMinorOf(dispatched.amount()));
            insert.setString(8, MoneyColumns.currencyOf(dispatched.amount()));
            insert.setShort(9, MoneyColumns.scaleOf(dispatched.amount()));
            insert.setString(10, dispatched.reference().value());
            insert.setString(11, dispatched.railId().value());
            insert.setString(12, dispatched.status().name());
            insert.setString(13, dispatched.failureReason().map(Enum::name).orElse(null));
            insert.setString(
                    14, dispatched.schemeReference().map(ProviderReference::value).orElse(null));
            insert.setString(15, dispatched.settlementCycle().orElse(null));
            insert.setString(16, dispatchKey);
            insert.setObject(17, dispatched.holdId().value());
            insert.setTimestamp(18, Timestamp.from(dispatched.createdAt()));
            insert.setTimestamp(19, Timestamp.from(dispatched.lastDispatchedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting a withdrawal", failure));
        }
    }

    @Override
    public Optional<Withdrawal> findOwned(
            Connection unitOfWork, WithdrawalId id, UUID customerId) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        // customer_id = ? IS the ownership check, in the statement (ADR-0031).
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND customer_id = ?",
                "reading an owned withdrawal",
                id.value(),
                customerId);
    }

    @Override
    public Optional<Withdrawal> findForUpdate(Connection unitOfWork, WithdrawalId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("locking a withdrawal", failure));
        }
    }

    @Override
    public Optional<Withdrawal> findByDispatchKey(
            Connection unitOfWork, UUID customerId, String dispatchKey) {
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE customer_id = ? AND dispatch_key = ?",
                "reading a withdrawal by its dispatch key",
                customerId,
                dispatchKey);
    }

    @Override
    public Optional<Withdrawal> findByEndToEndReference(
            Connection unitOfWork, EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE end_to_end_reference = ?")) {
            select.setString(1, reference.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading a withdrawal by its end-to-end reference", failure));
        }
    }

    @Override
    public boolean renewSendPermit(Connection unitOfWork, Withdrawal before, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(at, "at must not be null");
        // The conditional IS the permit: a resolver that already moved the withdrawal out
        // of the resolvable states leaves this matching no row, and then nothing may be
        // sent (ADR-0057 §4's either/or).
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET last_dispatched_at = ?"
                                + " WHERE id = ? AND status IN ("
                                + WithdrawalStatus.resolvableSqlValueList() + ")"
                                + " AND last_dispatched_at <= ?")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, before.id().value());
            update.setTimestamp(3, Timestamp.from(at));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("renewing a withdrawal's send permit", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, Withdrawal before, Withdrawal after, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?, failure_reason = ?, scheme_reference = ?,"
                                + " settlement_cycle = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, after.status().name());
            update.setString(2, after.failureReason().map(Enum::name).orElse(null));
            update.setString(
                    3, after.schemeReference().map(ProviderReference::value).orElse(null));
            update.setString(4, after.settlementCycle().orElse(null));
            update.setObject(5, after.id().value());
            update.setString(6, before.status().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "moving a withdrawal " + before.status() + " -> " + after.status(),
                            failure));
        }
        appendHistory(unitOfWork, after.id(), before.status(), after.status(), at);
        return true;
    }

    @Override
    public List<Withdrawal> findSweepable(
            Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit) {
        // A DISPATCHED row ages from its latest send permit - a takeover's re-send restarts
        // the clock, which is what keeps NEVER_RECEIVED from racing it; an UNKNOWN row ages
        // from the move that made it UNKNOWN (the payout sweep's reasoning verbatim).
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " w"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.withdrawal_event e"
                                + "   WHERE e.withdrawal_id = w.id) h ON true"
                                + " WHERE (w.status = 'DISPATCHED'"
                                + "        AND w.last_dispatched_at <= ?)"
                                + "    OR (w.status = 'UNKNOWN'"
                                + "        AND COALESCE(h.entered, w.created_at) <= ?)"
                                + " ORDER BY w.created_at, w.id"
                                + " LIMIT ?")) {
            select.setTimestamp(1, Timestamp.from(dispatchedBefore));
            select.setTimestamp(2, Timestamp.from(unknownBefore));
            select.setInt(3, limit);
            List<Withdrawal> sweepable = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    sweepable.add(map(row));
                }
            }
            return sweepable;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading sweepable withdrawals", failure));
        }
    }

    @Override
    public PaymentAttemptStore.UnknownReading unknownReading(
            Connection unitOfWork, java.time.Duration dispatchedBound) {
        Objects.requireNonNull(dispatchedBound, "dispatchedBound must not be null");
        // findSweepable's own expressions with the UNKNOWN bound at zero - the payout store's
        // reading statement for statement (P6-TSK-013): an UNKNOWN withdrawal has waited since
        // the move that made it UNKNOWN, a DISPATCHED one since its latest permit, counted only
        // once that permit is past the bound. The SERVER's clock decides the age, never an
        // instance's (ADR-0014), a whole number of seconds - floor()::bigint, never a double -
        // floored at zero, so an application clock a moment ahead cannot publish a negative wait.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now() - min("
                                + "   CASE WHEN w.status = 'UNKNOWN'"
                                + "        THEN COALESCE(h.entered, w.created_at)"
                                + "        ELSE w.last_dispatched_at END)))::bigint, 0))"
                                + " FROM " + TABLE + " w"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM payments.withdrawal_event e"
                                + "   WHERE e.withdrawal_id = w.id) h ON true"
                                + " WHERE w.status = 'UNKNOWN'"
                                + "    OR (w.status = 'DISPATCHED'"
                                + "        AND w.last_dispatched_at"
                                + "            <= now() - make_interval(secs => ?))")) {
            read.setLong(1, dispatchedBound.toSeconds());
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new PaymentAttemptStore.UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the stuck-withdrawal gauge", failure));
        }
    }

    // -----------------------------------------------------------------

    private Optional<Withdrawal> one(
            Connection unitOfWork, String sql, String what, Object first, Object second) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, first);
            select.setObject(2, second);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(what, failure));
        }
    }

    private void appendHistory(
            Connection unitOfWork,
            WithdrawalId id,
            WithdrawalStatus from,
            WithdrawalStatus to,
            Instant at) {
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.withdrawal_event"
                                + " (withdrawal_id, from_status, to_status, actor_id,"
                                + " actor_type, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("appending a withdrawal history row", failure));
        }
    }

    private static Withdrawal map(ResultSet row) throws SQLException {
        String failureReason = row.getString("failure_reason");
        String schemeReference = row.getString("scheme_reference");
        String settlementCycle = row.getString("settlement_cycle");
        return Withdrawal.rehydrate(
                WithdrawalId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                row.getObject("customer_id", UUID.class),
                LedgerAccountId.of(row.getObject("wallet_account_id", UUID.class)),
                row.getObject("payment_method_id", UUID.class),
                new ProviderReference(row.getString("destination_reference")),
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency")),
                        row.getShort("scale")),
                new EndToEndReference(row.getString("end_to_end_reference")),
                RailId.of(row.getString("rail")),
                WithdrawalStatus.valueOf(row.getString("status")),
                Optional.ofNullable(failureReason).map(WithdrawalFailureReason::valueOf),
                Optional.ofNullable(schemeReference).map(ProviderReference::new),
                Optional.ofNullable(settlementCycle),
                HoldId.of(row.getObject("hold_reference", UUID.class)),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("last_dispatched_at").toInstant());
    }
}
