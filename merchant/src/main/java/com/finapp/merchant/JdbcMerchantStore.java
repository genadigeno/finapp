package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MerchantStore} over JDBC (`P6-TSK-003`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>The transition writes are the three-layer discipline's middle: the aggregate refused the
 * illegal edge before this class ran; the conditional {@code WHERE status = ?} converges
 * concurrent administrators ({@code INV-CON-01} — the loser's row count is {@code false},
 * never a lost update); and `V002`'s trigger refuses raw SQL that skipped both. The history
 * row commits with the move it records — the same transaction, the refund store's idiom.
 */
public final class JdbcMerchantStore implements MerchantStore<Connection> {

    private static final String COLUMNS =
            "id, party_ref, legal_name, display_name, settlement_currency, status,"
                    + " created_at, status_changed_at";

    @Override
    public void insert(Connection unitOfWork, Merchant merchant) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, merchant.id().value());
            insert.setObject(2, merchant.partyRef());
            insert.setString(3, merchant.legalName());
            insert.setString(4, merchant.displayName());
            insert.setString(5, merchant.settlementCurrency().code());
            insert.setString(6, merchant.status().name());
            insert.setTimestamp(7, Timestamp.from(merchant.createdAt()));
            insert.setTimestamp(8, Timestamp.from(merchant.statusChangedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("inserting a merchant", failure));
        }
    }

    @Override
    public Optional<Merchant> findById(Connection unitOfWork, MerchantId id) {
        return read(unitOfWork, id, "");
    }

    @Override
    public Optional<Merchant> findByIdForUpdate(Connection unitOfWork, MerchantId id) {
        return read(unitOfWork, id, " FOR UPDATE");
    }

    @Override
    public boolean transition(Connection unitOfWork, Merchant before, Merchant transitioned) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE merchant.merchant SET status = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, transitioned.status().name());
            update.setTimestamp(2, Timestamp.from(transitioned.statusChangedAt()));
            update.setObject(3, transitioned.id().value());
            update.setString(4, before.status().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "moving a merchant " + before.status() + " -> "
                                    + transitioned.status(),
                            failure));
        }
        appendHistory(
                unitOfWork,
                transitioned.id(),
                before.status(),
                transitioned.status(),
                transitioned.statusChangedAt());
        return true;
    }

    private void appendHistory(
            Connection unitOfWork,
            MerchantId merchant,
            MerchantStatus from,
            MerchantStatus to,
            Instant occurredAt) {
        // The acting person, from the established context - a merchant transition is always
        // somebody's act (ADR-0021: an unestablished actor is an error, never a default).
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant_event"
                                + " (merchant_id, from_status, to_status, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, merchant.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(occurredAt));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe(
                            "recording merchant transition " + from + " -> " + to, failure));
        }
    }

    private Optional<Merchant> read(Connection unitOfWork, MerchantId id, String locking) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM merchant.merchant WHERE id = ?" + locking)) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        Merchant.rehydrate(
                                MerchantId.of(row.getObject(1, UUID.class)),
                                row.getObject(2, UUID.class),
                                row.getString(3),
                                row.getString(4),
                                CurrencyCode.of(row.getString(5)),
                                MerchantStatus.valueOf(row.getString(6)),
                                row.getTimestamp(7).toInstant(),
                                row.getTimestamp(8).toInstant()));
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a merchant", failure));
        }
    }
}
