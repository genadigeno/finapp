package com.finapp.accounts;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CustomerAccountStore} over explicit SQL (ADR-0033), on the caller's connection.
 *
 * <p>The {@code JdbcLedgerAccountStore} shape throughout: the savepoint converge, SQLState
 * rather than message matching, and refusals described through
 * {@link DatabaseFailure#describe} so no {@code SQLException} — whose constraint-violation
 * {@code DETAIL} carries the whole refused row — ever reaches a log.
 */
public final class JdbcCustomerAccountStore implements CustomerAccountStore<Connection> {

    private static final String TABLE = "accounts.customer_account";

    private static final String COLUMNS =
            "id, customer_id, product_type, status, opened_at, status_changed_at";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Creation openOrConverge(Connection unitOfWork, CustomerAccount fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes could not follow a lost race, and the retry
            // would re-run the command rather than converge.
            Savepoint beforeInsert = unitOfWork.setSavepoint("customer_account_open");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Creation(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return findLive(unitOfWork, fresh.customerId(), fresh.productType())
                        .map(existing -> new Creation(existing, false))
                        .orElseThrow(
                                () ->
                                        new AccountsStorageException(
                                                "the one-live-account index refused an insert"
                                                        + " but no live account is visible for"
                                                        + " the customer - a concurrent opener"
                                                        + " may have rolled back; retry"));
            }
        } catch (SQLException failure) {
            throw new AccountsStorageException(
                    DatabaseFailure.describe(
                            "opening a customer account for customer " + fresh.customerId(),
                            failure));
        }
    }

    private static void insert(Connection unitOfWork, CustomerAccount account)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, account.id().value());
            insert.setObject(2, account.customerId());
            insert.setString(3, account.productType().name());
            insert.setString(4, account.status().name());
            insert.setTimestamp(5, Timestamp.from(account.openedAt()));
            insert.setTimestamp(6, Timestamp.from(account.statusChangedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<CustomerAccount> findLive(
            Connection unitOfWork, UUID customerId, ProductType productType) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(productType, "productType must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                // The partial index's own predicate, generated from the same
                                // definition (CustomerAccountStatus.sqlTerminalValueList), so
                                // "live" here and "guarded there" cannot disagree.
                                + " WHERE customer_id = ? AND product_type = ?"
                                + " AND status NOT IN ("
                                + CustomerAccountStatus.sqlTerminalValueList()
                                + ")")) {
            read.setObject(1, customerId);
            read.setString(2, productType.name());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new AccountsStorageException(
                    DatabaseFailure.describe(
                            "reading the live account of customer " + customerId, failure));
        }
    }

    private static CustomerAccount rehydrate(ResultSet row) throws SQLException {
        return CustomerAccount.rehydrate(
                CustomerAccountId.of(row.getObject("id", UUID.class)),
                row.getObject("customer_id", UUID.class),
                ProductType.valueOf(row.getString("product_type")),
                CustomerAccountStatus.valueOf(row.getString("status")),
                row.getTimestamp("opened_at").toInstant(),
                row.getTimestamp("status_changed_at").toInstant());
    }
}
