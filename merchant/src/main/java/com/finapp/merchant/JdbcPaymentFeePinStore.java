package com.finapp.merchant;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PaymentFeePinStore} over JDBC (`P6-TSK-005`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>Two statements, and there will never be a third of a different kind: an {@code UPDATE}
 * here would be a repricing, and the application role holds no grant for one.
 */
public final class JdbcPaymentFeePinStore implements PaymentFeePinStore<Connection> {

    private static final MoneyColumns.ColumnNames GROSS = MoneyColumns.columnsFor("gross");

    private static final String COLUMNS =
            "payment_intent_ref, merchant_id, fee_schedule_version_id,"
                    + " " + GROSS.amountMinor() + ", " + GROSS.currency() + ", " + GROSS.scale()
                    + ", pinned_at, pinned_by";

    @Override
    public void insert(Connection unitOfWork, PaymentFeePin pin) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.payment_fee_pin (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, pin.paymentIntentRef());
            insert.setObject(2, pin.merchantId().value());
            insert.setObject(3, pin.versionId().value());
            insert.setLong(4, MoneyColumns.amountMinorOf(pin.gross()));
            insert.setString(5, MoneyColumns.currencyOf(pin.gross()));
            insert.setShort(6, MoneyColumns.scaleOf(pin.gross()));
            insert.setTimestamp(7, Timestamp.from(pin.pinnedAt()));
            insert.setString(8, pin.pinnedBy());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("pinning a payment's fee schedule version", failure));
        }
    }

    @Override
    public Optional<PaymentFeePin> findFor(Connection unitOfWork, UUID paymentIntentRef) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM merchant.payment_fee_pin"
                                + " WHERE payment_intent_ref = ?")) {
            select.setObject(1, paymentIntentRef);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(pinFrom(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a payment's fee pin", failure));
        }
    }

    private static PaymentFeePin pinFrom(ResultSet rows) throws SQLException {
        return new PaymentFeePin(
                rows.getObject(1, UUID.class),
                MerchantId.of(rows.getObject(2, UUID.class)),
                FeeScheduleVersionId.of(rows.getObject(3, UUID.class)),
                MoneyColumns.read(rows.getLong(4), rows.getString(5), rows.getShort(6)),
                rows.getTimestamp(7).toInstant(),
                rows.getString(8));
    }
}
