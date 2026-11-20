package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link MerchantCollections} over JDBC (`P17-TSK-033`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>Each page is one keyset statement - {@code (created_at, id) < (?, ?)} in the order it declares - over the store's own row
 * columns and mapper ({@link JdbcMerchantStore#COLUMNS}, {@link JdbcMerchantPayoutStore#COLUMNS}). The payouts page walks
 * merchant `V018`'s {@code merchant_payout_by_merchant}; the merchants page reads a table one onboarding at a time grows, and
 * filters and sorts it whole.
 */
public final class JdbcMerchantCollections implements MerchantCollections {

    @Override
    public List<Merchant> merchants(
            Connection unitOfWork, MerchantFilter filter, Optional<MerchantCursor> after, int limit) {
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder("SELECT " + JdbcMerchantStore.COLUMNS + " FROM merchant.merchant WHERE true");
        List<Object> arguments = new ArrayList<>();
        filter.status().ifPresent(status -> {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        });
        filter.name().ifPresent(name -> {
            // Exactly, against either name: never a pattern, never a prefix (finapp-admin-panel B7.3).
            sql.append(" AND (legal_name = ? OR display_name = ?)");
            arguments.add(name);
            arguments.add(name);
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (created_at, id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(select, arguments);
            try (ResultSet rows = select.executeQuery()) {
                List<Merchant> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(JdbcMerchantStore.map(rows));
                }
                return List.copyOf(page);
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("paging the merchants", failure));
        }
    }

    @Override
    public List<MerchantPayout> payouts(
            Connection unitOfWork, MerchantId merchant, Optional<PayoutCursor> after, int limit) {
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(after, "after must not be null");
        List<Object> arguments = new ArrayList<>();
        arguments.add(merchant.value());
        String resume = "";
        if (after.isPresent()) {
            resume = " AND (created_at, id) < (?, ?)";
            arguments.add(Timestamp.from(after.get().createdAt()));
            arguments.add(after.get().id());
        }
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + JdbcMerchantPayoutStore.COLUMNS + " FROM " + JdbcMerchantPayoutStore.TABLE
                        + " WHERE merchant_id = ?" + resume + " ORDER BY created_at DESC, id DESC LIMIT ?")) {
            bind(select, arguments);
            try (ResultSet rows = select.executeQuery()) {
                List<MerchantPayout> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(JdbcMerchantPayoutStore.map(rows));
                }
                return List.copyOf(page);
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("paging a merchant's payouts", failure));
        }
    }

    private static void bind(PreparedStatement statement, List<Object> arguments) throws SQLException {
        for (int i = 0; i < arguments.size(); i++) {
            statement.setObject(i + 1, arguments.get(i));
        }
    }
}
