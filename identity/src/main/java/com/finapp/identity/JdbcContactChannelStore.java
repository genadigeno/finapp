package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
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

    /** SQLState 23505. The one-verified-per-kind index answering, not a failure (`X-TSK-004`). */
    private static final String UNIQUE_VIOLATION = "23505";

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

        // Behind a savepoint (`X-TSK-004`, JdbcPaymentFeePinStore's shape). Setting verified_at
        // enters V011's one-verified-per-identity-and-kind index, so verifying a second channel of a
        // kind is a unique violation - and a unique violation aborts the caller's whole transaction
        // unless it is rolled back to here. It is an ANSWER, not a failure: the identity already has
        // a verified channel, and INV-IDN-06 keeps that one rather than letting a second mailbox
        // displace it. Until X-TSK-004 it was reported as a storage failure, which is a 500.
        //
        // SQLState alone is unambiguous for THIS statement, because the only unique index it can
        // ENTER is that one: it leaves the token-hash index (the hash becomes NULL) and does not touch
        // the primary key. A unique index this UPDATE could enter would have to be told apart here.
        Savepoint attempt;
        try {
            attempt = unitOfWork.setSavepoint("contact_channel_verification");
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not prepare a contact channel verification", e));
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setString(2, presented.hash().expose());
            update.setTimestamp(3, Timestamp.from(at));
            Optional<ContactChannel> verified;
            try (ResultSet rows = update.executeQuery()) {
                verified = rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
            unitOfWork.releaseSavepoint(attempt);
            return verified;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                rollbackTo(unitOfWork, attempt);
                throw new VerifiedChannelAlreadyExistsException();
            }
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not verify a contact channel", e));
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint attempt) {
        try {
            unitOfWork.rollback(attempt);
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe(
                            "Could not abandon a refused contact channel verification", e));
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
