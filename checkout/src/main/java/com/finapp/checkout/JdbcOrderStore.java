package com.finapp.checkout;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OrderStore} over JDBC (`P6-TSK-006`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>Two statements, and there will never be a third of a different kind: an {@code UPDATE} here
 * would be a rewritten commercial fact, and the application role holds no grant for one.
 */
public final class JdbcOrderStore implements OrderStore<Connection> {

    /**
     * The amount columns, named rather than prefixed: {@code columnsFor("amount")} would
     * generate {@code amount_amount_minor}, so this table follows {@code payment_intent}'s
     * own construction — the explicit record, whose {@code ddl()} is still the ONE
     * definition the migration is reconciled against.
     */
    private static final MoneyColumns.ColumnNames AMOUNT =
            new MoneyColumns.ColumnNames("amount_minor", "amount_currency", "amount_scale");

    private static final String COLUMNS =
            "id, session_ref, merchant_ref,"
                    + " " + AMOUNT.amountMinor() + ", " + AMOUNT.currency() + ", "
                    + AMOUNT.scale()
                    + ", captured_entry_ref, created_at";

    @Override
    public void insert(Connection unitOfWork, Order order) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO checkout.checkout_order (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, order.id().value());
            insert.setObject(2, order.sessionRef().value());
            insert.setObject(3, order.merchantRef());
            insert.setLong(4, MoneyColumns.amountMinorOf(order.amount()));
            insert.setString(5, MoneyColumns.currencyOf(order.amount()));
            insert.setShort(6, MoneyColumns.scaleOf(order.amount()));
            insert.setObject(7, order.capturedEntryRef());
            insert.setTimestamp(8, Timestamp.from(order.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe("recording an order", failure));
        }
    }

    @Override
    public Optional<Order> findById(Connection unitOfWork, OrderId id) {
        return read(unitOfWork, "id = ?", id.value());
    }

    @Override
    public Optional<Order> findBySession(Connection unitOfWork, CheckoutSessionId sessionRef) {
        return read(unitOfWork, "session_ref = ?", sessionRef.value());
    }

    private Optional<Order> read(Connection unitOfWork, String predicate, UUID value) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM checkout.checkout_order WHERE "
                                + predicate)) {
            select.setObject(1, value);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(orderFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CheckoutStorageException(
                    DatabaseFailure.describe("reading an order", failure));
        }
    }

    private static Order orderFrom(ResultSet rows) throws SQLException {
        Money amount = MoneyColumns.read(rows.getLong(4), rows.getString(5), rows.getShort(6));
        return Order.rehydrate(
                OrderId.of(rows.getObject(1, UUID.class)),
                CheckoutSessionId.of(rows.getObject(2, UUID.class)),
                rows.getObject(3, UUID.class),
                amount,
                rows.getObject(7, UUID.class),
                rows.getTimestamp(8).toInstant());
    }
}
