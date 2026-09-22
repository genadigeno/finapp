package com.finapp.checkout;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CheckoutSessionStore} over JDBC (`P6-TSK-006`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>The transition write is the three-layer discipline's middle: the aggregate refused the
 * illegal edge before this class ran; the conditional {@code WHERE status = ?} converges
 * concurrent writers ({@code INV-CON-01} — the loser's row count is {@code false}, never a lost
 * update); and `V002`'s trigger refuses raw SQL that skipped both. The history row commits with
 * the move it records.
 *
 * <p><strong>The token is never selected.</strong> {@link #findByToken} hashes what was
 * presented and looks the hash up by its unique index — so authentication is one row by index
 * and the stored value never travels back out ({@code INV-IDN-01}).
 */
public final class JdbcCheckoutSessionStore implements CheckoutSessionStore<Connection> {

    /**
     * The amount columns, named rather than prefixed: {@code columnsFor("amount")} would
     * generate {@code amount_amount_minor}, so this table follows {@code payment_intent}'s
     * own construction — the explicit record, whose {@code ddl()} is still the ONE
     * definition the migration is reconciled against.
     */
    private static final MoneyColumns.ColumnNames AMOUNT =
            new MoneyColumns.ColumnNames("amount_minor", "amount_currency", "amount_scale");

    private static final String COLUMNS =
            "id, merchant_ref,"
                    + " " + AMOUNT.amountMinor() + ", " + AMOUNT.currency() + ", "
                    + AMOUNT.scale()
                    + ", line_summary, fee_schedule_version_ref, token_hash, algorithm,"
                    + " payment_intent_ref, status, expires_at, created_at, status_changed_at";

    @Override
    public void insert(Connection unitOfWork, CheckoutSession session) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO checkout.checkout_session (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, session.id().value());
            insert.setObject(2, session.merchantRef());
            insert.setLong(3, MoneyColumns.amountMinorOf(session.amount()));
            insert.setString(4, MoneyColumns.currencyOf(session.amount()));
            insert.setShort(5, MoneyColumns.scaleOf(session.amount()));
            insert.setString(6, session.lineSummary());
            insert.setObject(7, session.feeScheduleVersionRef());
            // THE ONE UNWRAP ON THIS PATH: the hash goes into the column it belongs in, and
            // nowhere else (SecretsAreUnwrappedInOnePlaceTest's regime).
            insert.setString(8, session.tokenHash().expose());
            insert.setString(9, session.algorithm());
            setNullableUuid(insert, 10, session.paymentIntentRef());
            insert.setString(11, session.status().name());
            insert.setTimestamp(12, Timestamp.from(session.expiresAt()));
            insert.setTimestamp(13, Timestamp.from(session.createdAt()));
            insert.setTimestamp(14, Timestamp.from(session.statusChangedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe("inserting a checkout session", failure));
        }
    }

    @Override
    public Optional<CheckoutSession> findById(Connection unitOfWork, CheckoutSessionId id) {
        return read(unitOfWork, id, "");
    }

    @Override
    public Optional<CheckoutSession> findByIdForUpdate(
            Connection unitOfWork, CheckoutSessionId id) {
        return read(unitOfWork, id, " FOR UPDATE");
    }

    @Override
    public Optional<CheckoutSession> findByToken(
            Connection unitOfWork, CheckoutSessionToken presented) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM checkout.checkout_session"
                                + " WHERE token_hash = ?")) {
            select.setString(1, presented.hash().expose());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(sessionFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe("resolving a checkout session token", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, CheckoutSession before, CheckoutSession transitioned) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE checkout.checkout_session"
                                + " SET status = ?, status_changed_at = ?,"
                                // COALESCE, not a plain assignment: a transition that does not
                                // attach an intent must not blank one that is already there,
                                // and V002's trigger would refuse it if it tried.
                                + " payment_intent_ref = COALESCE(payment_intent_ref, ?)"
                                + " WHERE id = ? AND status = ?"
                                // FOUND BY THE COMPLETION GATE. Without this clause a caller
                                // carrying a DIFFERENT intent than the row already holds was
                                // silently discarded by the COALESCE - the write landed, the
                                // trigger never fired because the column did not change, and
                                // the caller was told it had succeeded. The set-once rule was
                                // loud at the trigger and silent here, which is the worst
                                // place for a rule to be quiet. Now the row count refuses it,
                                // and the caller re-reads (INV-CON-01's own idiom).
                                + " AND (payment_intent_ref IS NULL"
                                + "      OR ?::uuid IS NULL"
                                + "      OR payment_intent_ref = ?::uuid)")) {
            update.setString(1, transitioned.status().name());
            update.setTimestamp(2, Timestamp.from(transitioned.statusChangedAt()));
            setNullableUuid(update, 3, transitioned.paymentIntentRef());
            update.setObject(4, transitioned.id().value());
            update.setString(5, before.status().name());
            setNullableUuid(update, 6, transitioned.paymentIntentRef());
            setNullableUuid(update, 7, transitioned.paymentIntentRef());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe(
                            "moving a checkout session " + before.status() + " -> "
                                    + transitioned.status(),
                            failure));
        }
        appendHistory(
                unitOfWork,
                transitioned.id(),
                before.status(),
                transitioned.status(),
                transitioned.statusChangedAt());
        return true;
    }

    private void appendHistory(
            Connection unitOfWork,
            CheckoutSessionId session,
            CheckoutSessionStatus from,
            CheckoutSessionStatus to,
            Instant occurredAt) {
        // The acting party, from the established context - a customer confirming, a merchant
        // abandoning, the platform expiring or completing (ADR-0021: an unestablished actor is
        // an error, never a default).
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO checkout.checkout_session_event"
                                + " (session_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, session.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe(
                            "recording checkout session transition " + from + " -> " + to,
                            failure));
        }
    }

    private Optional<CheckoutSession> read(
            Connection unitOfWork, CheckoutSessionId id, String locking) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM checkout.checkout_session"
                                + " WHERE id = ?" + locking)) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(sessionFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe("reading a checkout session", failure));
        }
    }

    private static CheckoutSession sessionFrom(ResultSet rows) throws SQLException {
        Money amount = MoneyColumns.read(rows.getLong(3), rows.getString(4), rows.getShort(5));
        return CheckoutSession.rehydrate(
                CheckoutSessionId.of(rows.getObject(1, UUID.class)),
                rows.getObject(2, UUID.class),
                amount,
                rows.getString(6),
                rows.getObject(7, UUID.class),
                // Re-wrapped the moment it leaves the driver: the hash never exists as a bare
                // String anywhere a log could reach it.
                Sensitive.of(rows.getString(8)),
                rows.getString(9),
                Optional.ofNullable(rows.getObject(10, UUID.class)),
                CheckoutSessionStatus.valueOf(rows.getString(11)),
                rows.getTimestamp(12).toInstant(),
                rows.getTimestamp(13).toInstant(),
                rows.getTimestamp(14).toInstant());
    }

    private static void setNullableUuid(
            PreparedStatement statement, int index, Optional<UUID> value) throws SQLException {
        if (value.isPresent()) {
            statement.setObject(index, value.get());
        } else {
            statement.setNull(index, Types.OTHER);
        }
    }
}
