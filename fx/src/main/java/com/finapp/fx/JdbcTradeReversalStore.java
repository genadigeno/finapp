package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link TradeReversalStore} over JDBC (`P9-TSK-025`; ADR-0033: explicit SQL, no ORM). */
public final class JdbcTradeReversalStore implements TradeReversalStore {

    private static final String COLUMNS = "id, trade_id, status, proposed_by, decided_by, reversal_entry_id";

    @Override
    public boolean propose(
            Connection unitOfWork, UUID id, FxTradeId tradeId, String proposedBy, String reason, String correlationId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO fx.trade_reversal (id, trade_id, status, proposed_by, proposed_reason, proposed_at,"
                        + " correlation_id) VALUES (?, ?, 'PROPOSED', ?, ?, statement_timestamp(), ?)"
                        + " ON CONFLICT (trade_id) WHERE status = 'PROPOSED' DO NOTHING")) {
            insert.setObject(1, id);
            insert.setObject(2, tradeId.value());
            insert.setString(3, proposedBy);
            insert.setString(4, reason);
            insert.setString(5, correlationId);
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("proposing a trade reversal", failure));
        }
    }

    @Override
    public Optional<Row> find(Connection unitOfWork, UUID id) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM fx.trade_reversal WHERE id = ?", id, "reading a trade reversal");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, UUID id) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM fx.trade_reversal WHERE id = ? FOR UPDATE", id,
                "locking a trade reversal");
    }

    @Override
    public boolean decide(
            Connection unitOfWork, UUID id, String to, String decidedBy, String reason, Optional<UUID> reversalEntryId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE fx.trade_reversal SET status = ?, decided_by = ?, decided_reason = ?, decided_at = statement_timestamp(),"
                        + " reversal_entry_id = ? WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to);
            update.setString(2, decidedBy);
            update.setString(3, reason);
            if (reversalEntryId.isPresent()) {
                update.setObject(4, reversalEntryId.get());
            } else {
                update.setNull(4, Types.OTHER);
            }
            update.setObject(5, id);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("deciding a trade reversal", failure));
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork, UUID eventId, UUID reversalId, Optional<String> from, String to, String actor, String reason) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO fx.trade_reversal_event (id, reversal_id, from_status, to_status, actor, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, eventId);
            insert.setObject(2, reversalId);
            insert.setString(3, from.orElse(null));
            insert.setString(4, to);
            insert.setString(5, actor);
            insert.setString(6, reason);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("recording a trade reversal's edge", failure));
        }
    }

    private static Optional<Row> one(Connection unitOfWork, String sql, UUID id, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(
                        row.getObject("id", UUID.class),
                        FxTradeId.of(row.getObject("trade_id", UUID.class)),
                        row.getString("status"),
                        row.getString("proposed_by"),
                        Optional.ofNullable(row.getString("decided_by")),
                        Optional.ofNullable(row.getObject("reversal_entry_id", UUID.class))));
            }
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe(doing, failure));
        }
    }
}
