package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PayoutDestinationStore} over JDBC (`P6-TSK-011`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>The transition writes are the three-layer discipline's middle: the aggregate refused the
 * illegal edge and the self-approval before this class ran; the conditional
 * {@code WHERE status = ?} — and, on the approval edge, {@code AND proposed_by <> ?} — converges
 * or refuses in the statement; `V006`'s trigger and {@code CHECK}s refuse raw SQL that skipped
 * both. The history row commits with the move it records.
 */
public final class JdbcPayoutDestinationStore implements PayoutDestinationStore<Connection> {

    private static final String TABLE = "merchant.payout_destination";

    private static final String COLUMNS =
            "id, merchant_id, destination_reference, display_suffix, status, proposed_by,"
                    + " proposed_at, proposal_reason, approved_by, approved_at, cooling_off_until,"
                    + " effective_at, superseded_at, ended_by, ended_at";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String SET_LIFECYCLE =
            "UPDATE " + TABLE
                    + " SET status = ?, approved_by = ?, approved_at = ?, cooling_off_until = ?,"
                    + " effective_at = ?, superseded_at = ?, ended_by = ?, ended_at = ?"
                    + " WHERE id = ? AND status = ?";

    @Override
    public void insert(Connection unitOfWork, PayoutDestination proposed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposed, "proposed must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006): the
            // caller's transaction stays usable after a lost race, and the refusal is a domain
            // answer rather than a poisoned connection.
            Savepoint beforeInsert = unitOfWork.setSavepoint("payout_destination_propose");
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO " + TABLE + " (" + COLUMNS + ")"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, proposed.id().value());
                insert.setObject(2, proposed.merchantId().value());
                // The reference's one production unwrap: onto its own column (registered in
                // SecretsAreUnwrappedInOnePlaceTest).
                insert.setString(3, proposed.reference().expose());
                insert.setString(4, proposed.displaySuffix());
                insert.setString(5, proposed.status().name());
                insert.setString(6, proposed.proposedBy());
                insert.setTimestamp(7, Timestamp.from(proposed.proposedAt()));
                insert.setString(8, proposed.proposalReason());
                setLifecycle(insert, 9, proposed);
                insert.executeUpdate();
                unitOfWork.releaseSavepoint(beforeInsert);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                // The only unique index a PROPOSED row can meet is the one-open index (the id is
                // a freshly minted UUIDv7; the one-effective index covers EFFECTIVE only).
                unitOfWork.rollback(beforeInsert);
                throw new PayoutDestinationChangePendingException();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("inserting a payout destination proposal", failure));
        }
    }

    @Override
    public Optional<PayoutDestination> find(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND merchant_id = ?",
                "reading a payout destination",
                id.value(),
                merchant.value());
    }

    @Override
    public Optional<PayoutDestination> findForUpdate(
            Connection unitOfWork, MerchantId merchant, PayoutDestinationId id) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE id = ? AND merchant_id = ? FOR UPDATE",
                "locking a payout destination",
                id.value(),
                merchant.value());
    }

    @Override
    public Optional<PayoutDestination> findOpen(Connection unitOfWork, MerchantId merchant) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE merchant_id = ? AND status IN ("
                        + PayoutDestinationStatus.openSqlValueList() + ")",
                "reading a merchant's open payout destination change",
                merchant.value());
    }

    @Override
    public Optional<PayoutDestination> findEffective(Connection unitOfWork, MerchantId merchant) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE merchant_id = ? AND status = 'EFFECTIVE'",
                "reading a merchant's effective payout destination",
                merchant.value());
    }

    @Override
    public Optional<PayoutDestination> findEffectiveForUpdate(
            Connection unitOfWork, MerchantId merchant) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE merchant_id = ? AND status = 'EFFECTIVE' FOR UPDATE",
                "locking a merchant's effective payout destination",
                merchant.value());
    }

    @Override
    public Optional<PayoutDestination> findEffectiveForShare(
            Connection unitOfWork, MerchantId merchant) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE merchant_id = ? AND status = 'EFFECTIVE' FOR SHARE",
                "share-locking a merchant's effective payout destination",
                merchant.value());
    }

    @Override
    public List<PayoutDestination> findDue(Connection unitOfWork, Instant now, int limit) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE status = 'APPROVED' AND cooling_off_until <= ?"
                                + " ORDER BY cooling_off_until, id LIMIT ?")) {
            select.setTimestamp(1, Timestamp.from(now));
            select.setInt(2, limit);
            List<PayoutDestination> due = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    due.add(map(row));
                }
            }
            return due;
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading due payout destinations", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, PayoutDestination before, PayoutDestination after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        boolean approval = after.status() == PayoutDestinationStatus.APPROVED;
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        approval
                                // INV-AUD-04 in the statement: an approval by the proposer
                                // matches no row, whatever the calling code believed.
                                ? SET_LIFECYCLE + " AND proposed_by <> ?"
                                : SET_LIFECYCLE)) {
            update.setString(1, after.status().name());
            setLifecycle(update, 2, after);
            update.setObject(9, after.id().value());
            update.setString(10, before.status().name());
            if (approval) {
                update.setString(11, after.approvedBy().orElseThrow());
            }
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "moving a payout destination " + before.status() + " -> "
                                    + after.status(),
                            failure));
        }
        appendHistory(unitOfWork, after.id(), before.status(), after.status(), transitionedAt(after));
        return true;
    }

    @Override
    public long countOpen(Connection unitOfWork) {
        try (PreparedStatement select =
                        unitOfWork.prepareStatement(
                                "SELECT count(*) FROM " + TABLE + " WHERE status IN ("
                                        + PayoutDestinationStatus.openSqlValueList() + ")");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("counting open payout destination changes", failure));
        }
    }

    /** The seven lifecycle columns after status, from {@code index} on, in {@link #SET_LIFECYCLE}'s order. */
    private static void setLifecycle(PreparedStatement statement, int index, PayoutDestination d)
            throws SQLException {
        statement.setString(index, d.approvedBy().orElse(null));
        statement.setTimestamp(index + 1, timestamp(d.approvedAt()));
        statement.setTimestamp(index + 2, timestamp(d.coolingOffUntil()));
        statement.setTimestamp(index + 3, timestamp(d.effectiveAt()));
        statement.setTimestamp(index + 4, timestamp(d.supersededAt()));
        statement.setString(index + 5, d.endedBy().orElse(null));
        statement.setTimestamp(index + 6, timestamp(d.endedAt()));
    }

    /** The instant the transition happened, which is the fact its target state recorded. */
    private static Instant transitionedAt(PayoutDestination after) {
        return switch (after.status()) {
            case APPROVED -> after.approvedAt().orElseThrow();
            case EFFECTIVE -> after.effectiveAt().orElseThrow();
            case SUPERSEDED -> after.supersededAt().orElseThrow();
            case REJECTED, WITHDRAWN -> after.endedAt().orElseThrow();
            case PROPOSED -> after.proposedAt();
        };
    }

    private void appendHistory(
            Connection unitOfWork,
            PayoutDestinationId destination,
            PayoutDestinationStatus from,
            PayoutDestinationStatus to,
            Instant occurredAt) {
        // The acting person or the platform, from the established context - a destination move
        // is always somebody's act (ADR-0021: an unestablished actor is an error, never a
        // default).
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.payout_destination_event"
                                + " (payout_destination_id, from_status, to_status, actor_id,"
                                + " actor_type, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, destination.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "recording payout destination transition " + from + " -> " + to,
                            failure));
        }
    }

    private Optional<PayoutDestination> one(
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

    private static PayoutDestination map(ResultSet row) throws SQLException {
        return PayoutDestination.rehydrate(
                PayoutDestinationId.of(row.getObject("id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                new PayoutDestinationReference(
                        Sensitive.of(row.getString("destination_reference"))),
                row.getString("display_suffix"),
                PayoutDestinationStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                Optional.ofNullable(row.getString("approved_by")),
                instant(row, "approved_at"),
                instant(row, "cooling_off_until"),
                instant(row, "effective_at"),
                instant(row, "superseded_at"),
                Optional.ofNullable(row.getString("ended_by")),
                instant(row, "ended_at"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static Timestamp timestamp(Optional<Instant> value) {
        return value.map(Timestamp::from).orElse(null);
    }
}
