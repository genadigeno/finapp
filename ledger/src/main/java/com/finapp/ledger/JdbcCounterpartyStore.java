package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC reads of {@code ledger.counterparty} (ADR-0033; `P9-TSK-010`). */
public final class JdbcCounterpartyStore implements CounterpartyStore<Connection> {

    private static final String SELECT = "SELECT id, code, kind FROM ledger.counterparty";

    @Override
    public Optional<Counterparty> findByCode(Connection unitOfWork, String code) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(code, "code must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " WHERE code = ?")) {
            select.setString(1, code);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the counterparty " + code, failure));
        }
    }

    @Override
    public List<Counterparty> all(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " ORDER BY code");
                ResultSet rows = select.executeQuery()) {
            List<Counterparty> registered = new ArrayList<>();
            while (rows.next()) {
                registered.add(rehydrate(rows));
            }
            return List.copyOf(registered);
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the counterparty registry", failure));
        }
    }

    private static Counterparty rehydrate(ResultSet row) throws SQLException {
        return new Counterparty(
                row.getObject("id", UUID.class),
                row.getString("code"),
                CounterpartyKind.valueOf(row.getString("kind")));
    }
}
