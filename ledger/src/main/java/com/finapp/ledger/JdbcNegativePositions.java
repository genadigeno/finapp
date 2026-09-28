package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link NegativePositions} over plain JDBC (`P7-TSK-013`): one grouped count over the balance
 * projection, joined to the chart for each account's purpose. {@code posted_minor} carries the
 * derivation's sign convention (normal side minus opposite side), so below zero means the
 * account holds a balance on its abnormal side — a payable or a wallet the counterparty owes.
 */
public final class JdbcNegativePositions implements NegativePositions<Connection> {

    @Override
    public Map<AccountPurpose, Long> countBelowZero(
            Connection unitOfWork, Set<AccountPurpose> purposes) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purposes, "purposes must not be null");
        Map<AccountPurpose, Long> counts = new EnumMap<>(AccountPurpose.class);
        purposes.forEach(purpose -> counts.put(purpose, 0L));
        if (purposes.isEmpty()) {
            return counts;
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT account.purpose, count(*)"
                                + " FROM ledger.account_balance balance"
                                + " JOIN ledger.ledger_account account"
                                + "   ON account.id = balance.ledger_account_id"
                                + " WHERE account.purpose = ANY (?)"
                                + "   AND balance.posted_minor < 0"
                                + " GROUP BY account.purpose")) {
            Array named =
                    unitOfWork.createArrayOf(
                            "text", purposes.stream().map(AccountPurpose::name).toArray());
            select.setArray(1, named);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.put(AccountPurpose.valueOf(row.getString(1)), row.getLong(2));
                }
            }
            return counts;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("counting accounts below zero", failure));
        }
    }
}
