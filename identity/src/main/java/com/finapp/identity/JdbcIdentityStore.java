package com.finapp.identity;

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

/**
 * Plain-JDBC identity reader.
 *
 * <p>Explicit SQL on the connection it is handed (ADR-0033), like every other writer and reader
 * here, so a lookup and the command that acts on it share one transaction and one view of the data.
 */
public final class JdbcIdentityStore implements IdentityStore<Connection> {

    private static final String TABLE = "identity.identity";

    private static final String COLUMNS =
            "id, party_id, login_identifier, status, created_at, status_changed_at";

    @Override
    public Optional<Identity> findByLoginIdentifier(
            Connection unitOfWork, LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE login_identifier = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            // Already normalised by LoginIdentifier, which is what makes the unique index mean what
            // it appears to mean - and therefore what makes this lookup find the row a person's
            // capitalisation would otherwise miss.
            select.setString(1, loginIdentifier.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            // The message never names the identifier: it is CONFIDENTIAL because it carries
            // existence, and an exception message reaches a log line (INV-AUD-02, INV-IDN-07).
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read an identity by login identifier", e));
        }
    }

    @Override
    public Optional<Identity> findById(Connection unitOfWork, IdentityId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read identity " + id, e));
        }
    }

    @Override
    public List<IdentityId> findIdsByParty(Connection unitOfWork, UUID partyId, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }

        String sql = "SELECT id FROM " + TABLE + " WHERE party_id = ? ORDER BY id LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, partyId);
            select.setInt(2, limit);
            List<IdentityId> ids = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ids.add(IdentityId.of((UUID) rows.getObject("id")));
                }
            }
            return List.copyOf(ids);
        } catch (SQLException e) {
            // Never the party in the message: which party holds an identity is CONFIDENTIAL (INV-AUD-02).
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read a party's identities", e));
        }
    }

    @Override
    public boolean moveStatus(
            Connection unitOfWork,
            IdentityId id,
            IdentityStatus from,
            Identity to,
            Optional<SuspensionCause> cause) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        boolean suspending = to.status() == IdentityStatus.SUSPENDED;
        if (suspending && cause.isEmpty()) {
            throw new IllegalArgumentException("a suspension records its cause (identity V024)");
        }
        // The cause is part of the transition (the Phase 13 gate, item 25): recorded on the way in, cleared on the way
        // out, and - when the caller names one on the way out - a condition of the move, so risk's reinstatement never
        // lifts a suspension an administrator decided.
        boolean ownCauseOnly = !suspending && from == IdentityStatus.SUSPENDED && cause.isPresent();

        // AND status = ? is the whole concurrency protocol. Without it the second administrator's
        // UPDATE succeeds against a row that has already moved, and two audit records describe one
        // transition - which is worse than a lost update, because the trail then says two people
        // suspended somebody and only one of them did.
        String sql =
                "UPDATE " + TABLE + " SET status = ?, status_changed_at = ?, suspension_cause = ?"
                        + " WHERE id = ? AND status = ?"
                        + (ownCauseOnly ? " AND suspension_cause = ?" : "");
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setString(1, to.status().name());
            update.setTimestamp(2, java.sql.Timestamp.from(to.statusChangedAt()));
            update.setString(3, suspending ? cause.get().name() : null);
            update.setObject(4, id.value());
            update.setString(5, from.name());
            if (ownCauseOnly) {
                update.setString(6, cause.get().name());
            }
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not move the status of identity " + id, e));
        }
    }

    private static Identity read(ResultSet rows) throws SQLException {
        return Identity.rehydrate(
                IdentityId.of((UUID) rows.getObject("id")),
                (UUID) rows.getObject("party_id"),
                new LoginIdentifier(rows.getString("login_identifier")),
                IdentityStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("status_changed_at").toInstant());
    }
}
