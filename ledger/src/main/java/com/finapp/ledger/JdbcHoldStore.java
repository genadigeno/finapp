package com.finapp.ledger;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
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

/** Plain-JDBC storage for holds (ADR-0033, `P3-TSK-015`). */
public final class JdbcHoldStore implements HoldStore<Connection> {

    private static final String TABLE = "ledger.hold";

    private static final String COLUMNS =
            "id, ledger_account_id, amount_minor, currency, scale, status, placed_at,"
                    + " released_at";

    @Override
    public void insert(Connection unitOfWork, Hold hold) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(hold, "hold must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, hold.id().value());
            insert.setObject(2, hold.account().value());
            insert.setLong(3, MoneyColumns.amountMinorOf(hold.amount()));
            insert.setString(4, MoneyColumns.currencyOf(hold.amount()));
            insert.setShort(5, MoneyColumns.scaleOf(hold.amount()));
            insert.setString(6, hold.status().name());
            insert.setTimestamp(7, Timestamp.from(hold.placedAt()));
            insert.setTimestamp(
                    8, hold.releasedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("placing hold " + hold.id(), failure));
        }
    }

    @Override
    public Optional<Hold> findById(Connection unitOfWork, HoldId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading hold " + id, failure));
        }
    }

    @Override
    public List<Hold> findActiveFor(Connection unitOfWork, LedgerAccountId account) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        // Ordered so the caller's Money fold meets rows deterministically;
                        // the partial index below serves exactly this read.
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE ledger_account_id = ? AND status = 'ACTIVE'"
                                + " ORDER BY id")) {
            select.setObject(1, account.value());
            try (ResultSet rows = select.executeQuery()) {
                List<Hold> holds = new ArrayList<>();
                while (rows.next()) {
                    holds.add(rehydrate(rows));
                }
                return List.copyOf(holds);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the active holds of account " + account, failure));
        }
    }

    @Override
    public boolean moveToReleased(Connection unitOfWork, HoldId id, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement move =
                unitOfWork.prepareStatement(
                        // The row count is the outcome: of N concurrent releasers exactly one
                        // sees ACTIVE, and the losers converge (HoldStore's contract).
                        "UPDATE " + TABLE + " SET status = 'RELEASED', released_at = ?"
                                + " WHERE id = ? AND status = 'ACTIVE'")) {
            move.setTimestamp(1, Timestamp.from(at));
            move.setObject(2, id.value());
            return move.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("releasing hold " + id, failure));
        }
    }

    private static Hold rehydrate(ResultSet row) throws SQLException {
        Timestamp releasedAt = row.getTimestamp("released_at");
        return Hold.rehydrate(
                HoldId.of(row.getObject("id", UUID.class)),
                LedgerAccountId.of(row.getObject("ledger_account_id", UUID.class)),
                MoneyColumns.read(
                        row.getLong("amount_minor"),
                        row.getString("currency"),
                        row.getShort("scale")),
                HoldStatus.valueOf(row.getString("status")),
                row.getTimestamp("placed_at").toInstant(),
                releasedAt == null ? null : releasedAt.toInstant());
    }
}
