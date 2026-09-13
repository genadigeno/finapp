package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
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
 * Plain-JDBC storage for the chart of accounts (ADR-0033).
 *
 * <p><strong>Isolation this relies on:</strong> PostgreSQL's default {@code READ COMMITTED}. A
 * second insert against the one-per-owner index <em>blocks</em> until the first transaction
 * ends, then reports a unique violation if it committed — the database, not this code,
 * arbitrates between two instances creating the same owned account
 * ({@code JdbcKycCaseStore}'s recorded reasoning).
 */
public final class JdbcLedgerAccountStore implements LedgerAccountStore<Connection> {

    private static final String TABLE = "ledger.ledger_account";

    private static final String COLUMNS =
            "id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose,"
                    + " gl_code, status, created_at, status_changed_at";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Creation createOrConverge(Connection unitOfWork, LedgerAccount fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        UUID ownerRef =
                fresh.ownerRef()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "createOrConverge is for owned accounts; the"
                                                    + " operational chart is seeded by migration"
                                                    + " (P3-TSK-003) and has no code path"));
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes - the product row, the audit record - could
            // not follow a lost race, and the retry would re-run the command rather than
            // converge.
            Savepoint beforeInsert = unitOfWork.setSavepoint("ledger_account_create");
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
                return findOwned(unitOfWork, ownerRef, fresh.purpose(), fresh.currency())
                        .map(existing -> new Creation(existing, false))
                        .orElseThrow(
                                () ->
                                        new LedgerStorageException(
                                                "the one-per-owner index refused an insert but"
                                                        + " no account is visible for the owner"
                                                        + " - a concurrent creator may have"
                                                        + " rolled back; retry"));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "creating a ledger account for owner " + ownerRef, failure));
        }
    }

    private static void insert(Connection unitOfWork, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, account.id().value());
            insert.setString(2, account.accountType().name());
            insert.setString(3, account.normalBalance().name());
            insert.setString(4, account.currency().code());
            insert.setString(5, account.ownerKind().name());
            insert.setObject(6, account.ownerRef().orElse(null));
            insert.setString(7, account.purpose().name());
            insert.setString(8, account.glCode().orElse(null));
            insert.setString(9, account.status().name());
            insert.setTimestamp(10, Timestamp.from(account.createdAt()));
            insert.setTimestamp(11, Timestamp.from(account.statusChangedAt()));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<LedgerAccount> findOwned(
            Connection unitOfWork,
            UUID ownerRef,
            AccountPurpose purpose,
            CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE owner_ref = ? AND purpose = ? AND currency = ?")) {
            select.setObject(1, ownerRef);
            select.setString(2, purpose.name());
            select.setString(3, currency.code());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the " + purpose + " account of owner " + ownerRef,
                            failure));
        }
    }

    private static LedgerAccount rehydrate(ResultSet row) throws SQLException {
        String glCode = row.getString("gl_code");
        UUID ownerRef = row.getObject("owner_ref", UUID.class);
        return LedgerAccount.rehydrate(
                LedgerAccountId.of(row.getObject("id", UUID.class)),
                AccountType.valueOf(row.getString("account_type")),
                NormalBalance.valueOf(row.getString("normal_balance")),
                // ofPersisted-style leniency is not needed here: the column is the CHAR(3)
                // MoneyColumns shape and CurrencyCode validates it - stripTrailing for CHAR
                // padding, the MoneyColumns.read discipline.
                CurrencyCode.of(
                        row.getString("currency").stripTrailing()),
                OwnerKind.valueOf(row.getString("owner_kind")),
                ownerRef,
                AccountPurpose.valueOf(row.getString("purpose")),
                glCode,
                LedgerAccountStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("status_changed_at").toInstant());
    }
}
