package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
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

/** {@link PaymentIntentStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcPaymentIntentStore implements PaymentIntentStore<Connection> {

    private static final String COLUMNS =
            "id, party_id, customer_id, payment_method_id, wallet_account_id, amount_minor,"
                    + " currency, scale, status, created_at";

    @Override
    public void insert(Connection unitOfWork, PaymentIntent intent) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_intent (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, intent.id().value());
            insert.setObject(2, intent.partyId());
            insert.setObject(3, intent.customerId());
            insert.setObject(4, intent.paymentMethodId());
            insert.setObject(5, intent.walletAccount().value());
            insert.setLong(6, intent.amount().minorUnits());
            insert.setString(7, intent.amount().currency().code());
            insert.setShort(8, (short) intent.amount().scale());
            insert.setString(9, intent.status().name());
            insert.setTimestamp(10, Timestamp.from(intent.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting payment intent " + intent.id(), failure));
        }
    }

    @Override
    public Optional<PaymentIntent> findById(Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.payment_intent WHERE id = ?")) {
            read.setObject(1, intent.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading payment intent " + intent, failure));
        }
    }

    @Override
    public Optional<PaymentIntent> findOwned(
            Connection unitOfWork, PaymentIntentId intent, UUID partyId) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031).
                        "SELECT " + COLUMNS + " FROM payments.payment_intent"
                                + " WHERE id = ? AND party_id = ?")) {
            read.setObject(1, intent.value());
            read.setObject(2, partyId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading an owned payment intent", failure));
        }
    }

    @Override
    public List<PaymentIntent> listFor(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031).
                        // Newest first; the id (UUIDv7, time-ordered) breaks created_at ties
                        // deterministically, so two instances render one order.
                        "SELECT " + COLUMNS + " FROM payments.payment_intent"
                                + " WHERE party_id = ? ORDER BY created_at DESC, id DESC")) {
            read.setObject(1, partyId);
            try (ResultSet rows = read.executeQuery()) {
                List<PaymentIntent> intents = new ArrayList<>();
                while (rows.next()) {
                    intents.add(rehydrate(rows));
                }
                return List.copyOf(intents);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("listing a party's payment intents", failure));
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork,
            PaymentIntentId intent,
            PaymentIntentStatus from,
            PaymentIntentStatus to) {
        // The machine's own legality first (INV-LIFE-02 in the writer too): a store asked for
        // an edge the machine does not carry is a caller defect, loud - never a quiet 0.
        if (!from.canTransitionTo(to)) {
            throw new IllegalPaymentIntentTransitionException(intent, from, to);
        }
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        // The conditional transition: the row count IS the arbitration
                        // (PHASE_5_PLAN.md section 7), V002's trigger the layer beneath.
                        "UPDATE payments.payment_intent SET status = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setObject(2, intent.value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "transitioning payment intent " + intent + " from " + from + " to "
                                    + to,
                            failure));
        }
    }

    @Override
    public void recordTransition(
            Connection unitOfWork,
            PaymentIntentId intent,
            PaymentIntentStatus from,
            PaymentIntentStatus to,
            Actor actor,
            Instant occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.payment_intent_event"
                                + " (intent_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, intent.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "recording the transition of payment intent " + intent, failure));
        }
    }

    private static PaymentIntent rehydrate(ResultSet row) throws SQLException {
        return PaymentIntent.rehydrate(
                PaymentIntentId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                row.getObject("customer_id", UUID.class),
                row.getObject("payment_method_id", UUID.class),
                LedgerAccountId.of(row.getObject("wallet_account_id", UUID.class)),
                // The STORED scale, never re-derived (INV-MON-05).
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency")),
                        row.getShort("scale")),
                PaymentIntentStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant());
    }
}
