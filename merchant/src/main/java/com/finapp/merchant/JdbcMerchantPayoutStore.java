package com.finapp.merchant;

import com.finapp.ledger.HoldId;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
 * {@link MerchantPayoutStore} over JDBC (`P6-TSK-012`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>The transition writes are the three-layer discipline's middle: the aggregate refused the
 * illegal edge before this class ran; the conditional {@code WHERE status = ?} converges or
 * refuses in the statement; `V007`'s trigger and {@code CHECK}s refuse raw SQL that skipped both.
 * The history row commits with the move it records.
 */
public final class JdbcMerchantPayoutStore implements MerchantPayoutStore<Connection> {

    private static final String TABLE = "merchant.merchant_payout";

    private static final String COLUMNS =
            "id, merchant_id, amount_minor, currency, scale, destination_id, hold_reference,"
                    + " provider_idempotency_reference, provider_reference, status, failure_reason,"
                    + " requested_by, requested_by_type, reason, created_at, last_dispatched_at";

    @Override
    public void insert(Connection unitOfWork, MerchantPayout dispatched, String dispatchKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(dispatched, "dispatched must not be null");
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS + ", dispatch_key)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, dispatched.id().value());
            insert.setObject(2, dispatched.merchantId().value());
            insert.setLong(3, dispatched.amount().minorUnits());
            insert.setString(4, dispatched.amount().currency().code());
            insert.setShort(5, (short) dispatched.amount().scale());
            insert.setObject(6, dispatched.destinationId().value());
            insert.setObject(7, dispatched.holdId().value());
            insert.setString(8, dispatched.reference().value());
            insert.setString(
                    9,
                    dispatched.providerReference().map(PayoutProviderReference::value).orElse(null));
            insert.setString(10, dispatched.status().name());
            insert.setString(11, dispatched.failureReason().map(Enum::name).orElse(null));
            insert.setString(12, dispatched.requestedBy());
            insert.setString(13, dispatched.requestedByType().name());
            insert.setString(14, dispatched.reason().orElse(null));
            insert.setTimestamp(15, Timestamp.from(dispatched.createdAt()));
            insert.setTimestamp(16, Timestamp.from(dispatched.lastDispatchedAt()));
            insert.setString(17, dispatchKey);
            insert.executeUpdate();
        } catch (SQLException failure) {
            // Every unique index a fresh row could meet is arbitrated upstream - the claim for
            // the dispatch key, a freshly minted UUIDv7 for the id and reference - so a
            // violation here is a defect, refused loudly rather than guessed at.
            throw new MerchantStorageException(
                    DatabaseFailure.describe("inserting a merchant payout", failure));
        }
    }

    @Override
    public Optional<MerchantPayout> find(
            Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND merchant_id = ?",
                "reading a merchant payout",
                id.value(),
                merchant.value());
    }

    @Override
    public Optional<MerchantPayout> findForUpdate(
            Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE id = ? AND merchant_id = ? FOR UPDATE",
                "locking a merchant payout",
                id.value(),
                merchant.value());
    }

    @Override
    public Optional<MerchantPayout> findByDispatchKey(
            Connection unitOfWork, MerchantId merchant, String dispatchKey) {
        Objects.requireNonNull(dispatchKey, "dispatchKey must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE merchant_id = ? AND dispatch_key = ?")) {
            select.setObject(1, merchant.value());
            select.setString(2, dispatchKey);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a payout by its dispatch key", failure));
        }
    }

    @Override
    public boolean renewSendPermit(Connection unitOfWork, MerchantPayout before, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(at, "at must not be null");
        // The conditional IS the permit: a resolver that already moved the payout out of the
        // resolvable states leaves this matching no row, and then nothing may be sent.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET last_dispatched_at = ?"
                                + " WHERE id = ? AND merchant_id = ? AND status IN ("
                                + MerchantPayoutStatus.resolvableSqlValueList() + ")"
                                + " AND last_dispatched_at <= ?")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, before.id().value());
            update.setObject(3, before.merchantId().value());
            update.setTimestamp(4, Timestamp.from(at));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("renewing a payout's send permit", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, MerchantPayout before, MerchantPayout after, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = ?, failure_reason = ?, provider_reference = ?"
                                + " WHERE id = ? AND merchant_id = ? AND status = ?")) {
            update.setString(1, after.status().name());
            update.setString(2, after.failureReason().map(Enum::name).orElse(null));
            update.setString(
                    3, after.providerReference().map(PayoutProviderReference::value).orElse(null));
            update.setObject(4, after.id().value());
            update.setObject(5, after.merchantId().value());
            update.setString(6, before.status().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "moving a merchant payout " + before.status() + " -> " + after.status(),
                            failure));
        }
        appendHistory(unitOfWork, after.id(), before.status(), after.status(), at);
        return true;
    }

    @Override
    public List<MerchantPayout> findSweepable(
            Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit) {
        // A DISPATCHED row ages from its latest send permit - a takeover's re-send restarts the
        // clock, which is what keeps NEVER_RECEIVED from racing it; an UNKNOWN row ages from the
        // move that made it UNKNOWN. The statuses are pinned literals, the payment sweep's
        // reasoning: this query's scope is a decision, not a derivation.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " p"
                                + " LEFT JOIN LATERAL (SELECT max(occurred_at) AS entered"
                                + "   FROM merchant.merchant_payout_event e"
                                + "   WHERE e.payout_id = p.id) h ON true"
                                + " WHERE (p.status = 'DISPATCHED' AND p.last_dispatched_at <= ?)"
                                + "    OR (p.status = 'UNKNOWN'"
                                + "        AND COALESCE(h.entered, p.created_at) <= ?)"
                                + " ORDER BY p.created_at, p.id"
                                + " LIMIT ?")) {
            select.setTimestamp(1, Timestamp.from(dispatchedBefore));
            select.setTimestamp(2, Timestamp.from(unknownBefore));
            select.setInt(3, limit);
            List<MerchantPayout> sweepable = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    sweepable.add(map(row));
                }
            }
            return sweepable;
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading sweepable merchant payouts", failure));
        }
    }

    private void appendHistory(
            Connection unitOfWork,
            MerchantPayoutId payout,
            MerchantPayoutStatus from,
            MerchantPayoutStatus to,
            Instant occurredAt) {
        // The platform applies every outcome (an enumerated enterSystem() site): an
        // unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant_payout_event"
                                + " (payout_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, payout.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "recording merchant payout transition " + from + " -> " + to,
                            failure));
        }
    }

    private Optional<MerchantPayout> one(
            Connection unitOfWork, String sql, String what, UUID... parameters) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                select.setObject(index + 1, parameters[index]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe(what, failure));
        }
    }

    private static MerchantPayout map(ResultSet row) throws SQLException {
        return MerchantPayout.rehydrate(
                MerchantPayoutId.of(row.getObject("id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency").stripTrailing()),
                        row.getShort("scale")),
                PayoutDestinationId.of(row.getObject("destination_id", UUID.class)),
                HoldId.of(row.getObject("hold_reference", UUID.class)),
                new PayoutReference(row.getString("provider_idempotency_reference")),
                MerchantPayoutStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("failure_reason"))
                        .map(PayoutFailureReason::valueOf),
                Optional.ofNullable(row.getString("provider_reference"))
                        .map(PayoutProviderReference::new),
                row.getString("requested_by"),
                ActorType.valueOf(row.getString("requested_by_type")),
                Optional.ofNullable(row.getString("reason")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("last_dispatched_at").toInstant());
    }
}
