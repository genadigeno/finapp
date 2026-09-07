package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC contact channel store (`P1-TSK-023`, ADR-0033). */
public final class JdbcContactChannelStore implements ContactChannelStore<Connection> {

    private static final String TABLE = "identity.contact_channel";

    private static final String COLUMNS =
            "id, identity_id, kind, address, verified_at, added_at";

    @Override
    public void add(
            Connection unitOfWork,
            ContactChannel channel,
            SingleUseToken challenge,
            Instant expiresAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(challenge, "challenge must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        String sql =
                "INSERT INTO " + TABLE
                        + " (id, identity_id, kind, address, verification_token_hash,"
                        + " verification_expires_at, added_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, channel.id().value());
            insert.setObject(2, channel.identityId().value());
            insert.setString(3, channel.kind().name());
            insert.setString(4, channel.address().value());
            insert.setString(5, challenge.hash().expose());
            insert.setTimestamp(6, Timestamp.from(expiresAt));
            insert.setTimestamp(7, Timestamp.from(channel.addedAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not add a contact channel", e));
        }
    }

    @Override
    public Optional<ContactChannel> verify(
            Connection unitOfWork, SingleUseToken presented, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(presented, "presented must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // Conditional, and every clause is load-bearing:
        //   verification_token_hash = ?   the challenge itself
        //   verified_at IS NULL           a second verification would RESET verified_at, making
        //                                 verifiedFor() report a freshly changed channel as old -
        //                                 and that is the input to INV-IDN-06's "recently changed
        //                                 channel" abuse case
        //   verification_expires_at > ?   judged by the argument, which the caller takes from the
        //                                 injected Clock; the server's clock decides in every
        //                                 cross-instance comparison (ADR-0014)
        //
        // The token is CLEARED on success, so the challenge cannot be presented twice. That is the
        // single-use property, and it is here rather than in Java because the row count is what two
        // racing instances actually agree on.
        String sql =
                "UPDATE " + TABLE
                        + " SET verified_at = ?, verification_token_hash = NULL,"
                        + " verification_expires_at = NULL"
                        + " WHERE verification_token_hash = ?"
                        + " AND verified_at IS NULL"
                        + " AND verification_expires_at > ?"
                        + " RETURNING " + COLUMNS;
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setString(2, presented.hash().expose());
            update.setTimestamp(3, Timestamp.from(at));
            try (ResultSet rows = update.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not verify a contact channel", e));
        }
    }

    @Override
    public Optional<ContactChannel> findVerified(
            Connection unitOfWork, IdentityId identityId, ContactChannelKind kind) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        // `verified_at IS NOT NULL` is in the STATEMENT. INV-IDN-06 turns on this predicate: a
        // filter applied after the read is one a later caller can forget, and forgetting it means
        // recovery accepts an address nobody proved.
        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE identity_id = ? AND kind = ? AND verified_at IS NOT NULL";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            select.setString(2, kind.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read a contact channel", e));
        }
    }

    @Override
    public Optional<ContactChannel> findOwned(
            Connection unitOfWork, ContactChannelId id, IdentityId owner) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        // identity_id = ? IS the ownership check (ADR-0031, `P1-TSK-021`).
        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND identity_id = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, id.value());
            select.setObject(2, owner.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read a contact channel", e));
        }
    }

    private static ContactChannel read(ResultSet rows) throws SQLException {
        Timestamp verifiedAt = rows.getTimestamp("verified_at");
        return new ContactChannel(
                ContactChannelId.of((UUID) rows.getObject("id")),
                IdentityId.of((UUID) rows.getObject("identity_id")),
                ContactChannelKind.valueOf(rows.getString("kind")),
                new EmailAddress(rows.getString("address")),
                Optional.ofNullable(verifiedAt).map(Timestamp::toInstant),
                rows.getTimestamp("added_at").toInstant());
    }
}
