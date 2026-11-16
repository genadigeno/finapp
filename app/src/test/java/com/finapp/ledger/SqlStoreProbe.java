package com.finapp.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * A ledger store adapter, for {@code ModuleBoundaryRulesTest}'s teeth (`P15-REM-001`): it executes SQL and implements a port
 * the ledger declares, and it nests the record its port returns, so a caller that names only {@link Row} is shown to be
 * naming the adapter. A test class claiming {@code com.finapp.ledger}; see {@code SystemClockProbe} for why.
 */
public final class SqlStoreProbe implements SqlStorePortProbe {

    /** What a row reads as; nested in the adapter, so it is the adapter's. */
    public record Row(String value) {}

    @Override
    public Optional<Row> find(Connection unitOfWork, String key) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT value FROM ledger.probe WHERE key = ?")) {
            select.setString(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(new Row(row.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
