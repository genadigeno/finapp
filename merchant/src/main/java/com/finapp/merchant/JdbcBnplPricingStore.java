package com.finapp.merchant;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplPricingStore} over JDBC (`P12-TSK-006`; ADR-0033). The assignment is {@link JdbcFeeScheduleStore#assign}'s
 * shape per (merchant, product); the pin is {@link JdbcPaymentFeePinStore}'s - an insert behind a savepoint, arbitrated
 * by the primary key, and a read; nothing that could reprice a purchase.
 */
public final class JdbcBnplPricingStore implements BnplPricingStore<Connection> {

    private static final MoneyColumns.ColumnNames GROSS = MoneyColumns.columnsFor("gross");

    /** SQLState 23505 - the primary key's answer, not a failure. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String PRICED_PURCHASE_COLUMNS =
            "purchase_id, merchant_id, product, fee_schedule_version_id, " + GROSS.amountMinor() + ", "
                    + GROSS.currency() + ", " + GROSS.scale() + ", pinned_at, pinned_by";

    @Override
    public Optional<FeeScheduleId> findAssignment(
            Connection unitOfWork, MerchantId merchant, MerchantBnplProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT fee_schedule_id FROM merchant.merchant_bnpl_fee_schedule WHERE merchant_id = ? AND product = ?")) {
            select.setObject(1, merchant.value());
            select.setString(2, product.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(FeeScheduleId.of(rows.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a merchant's BNPL fee schedule", failure));
        }
    }

    @Override
    public void assign(
            Connection unitOfWork,
            MerchantId merchant,
            MerchantBnplProduct product,
            Optional<FeeScheduleId> previous,
            FeeScheduleId schedule,
            String reason,
            String actorId,
            String actorType,
            Instant at) {
        String sql = previous.isPresent()
                ? "UPDATE merchant.merchant_bnpl_fee_schedule SET fee_schedule_id = ?, assigned_at = ?, assigned_by = ?"
                        + " WHERE merchant_id = ? AND product = ?"
                : "INSERT INTO merchant.merchant_bnpl_fee_schedule"
                        + " (fee_schedule_id, assigned_at, assigned_by, merchant_id, product) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement write = unitOfWork.prepareStatement(sql)) {
            write.setObject(1, schedule.value());
            write.setTimestamp(2, Timestamp.from(at));
            write.setString(3, actorId);
            write.setObject(4, merchant.value());
            write.setString(5, product.name());
            if (write.executeUpdate() != 1) {
                // Unreachable under the merchant row's lock; loud if an unknown writer proves otherwise.
                throw new MerchantStorageException("a locked merchant's BNPL fee schedule assignment wrote no row");
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("assigning a merchant's BNPL fee schedule", failure));
        }
        try (PreparedStatement history = unitOfWork.prepareStatement(
                "INSERT INTO merchant.merchant_bnpl_fee_schedule_event (merchant_id, product, from_fee_schedule_id,"
                        + " to_fee_schedule_id, reason, actor_id, actor_type, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            history.setObject(1, merchant.value());
            history.setString(2, product.name());
            history.setObject(3, previous.map(FeeScheduleId::value).orElse(null));
            history.setObject(4, schedule.value());
            history.setString(5, reason);
            history.setString(6, actorId);
            history.setString(7, actorType);
            history.setTimestamp(8, Timestamp.from(at));
            history.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("recording a BNPL fee schedule assignment's history", failure));
        }
    }

    @Override
    public boolean insertPin(Connection unitOfWork, BnplFeePin pin) {
        Objects.requireNonNull(pin, "pin must not be null");
        Savepoint attempt;
        try {
            // The pin shares the purchase's transaction: a lost race must not poison it.
            attempt = unitOfWork.setSavepoint("bnpl_fee_pin");
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("preparing a BNPL fee pin", failure));
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO merchant.bnpl_fee_pin (" + PRICED_PURCHASE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, pin.purchaseRef());
            insert.setObject(2, pin.merchantId().value());
            insert.setString(3, pin.product().name());
            insert.setObject(4, pin.versionId().value());
            insert.setLong(5, MoneyColumns.amountMinorOf(pin.gross()));
            insert.setString(6, MoneyColumns.currencyOf(pin.gross()));
            insert.setShort(7, MoneyColumns.scaleOf(pin.gross()));
            insert.setTimestamp(8, Timestamp.from(pin.pinnedAt()));
            insert.setString(9, pin.pinnedBy());
            insert.executeUpdate();
            unitOfWork.releaseSavepoint(attempt);
            return true;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                try {
                    unitOfWork.rollback(attempt);
                } catch (SQLException abandon) {
                    throw new MerchantStorageException(
                            DatabaseFailure.describe("abandoning a lost BNPL fee pin race", abandon));
                }
                return false;
            }
            throw new MerchantStorageException(DatabaseFailure.describe("pinning a BNPL purchase's fee", failure));
        }
    }

    @Override
    public Optional<BnplFeePin> findPin(Connection unitOfWork, UUID purchaseRef) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + PRICED_PURCHASE_COLUMNS + " FROM merchant.bnpl_fee_pin WHERE purchase_id = ?")) {
            select.setObject(1, purchaseRef);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BnplFeePin(
                        rows.getObject(1, UUID.class),
                        MerchantId.of(rows.getObject(2, UUID.class)),
                        MerchantBnplProduct.valueOf(rows.getString(3)),
                        FeeScheduleVersionId.of(rows.getObject(4, UUID.class)),
                        MoneyColumns.read(rows.getLong(5), rows.getString(6), rows.getShort(7)),
                        rows.getTimestamp(8).toInstant(),
                        rows.getString(9)));
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading a BNPL purchase's fee pin", failure));
        }
    }
}
