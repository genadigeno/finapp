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

/**
 * Plain-JDBC session store (ADR-0033).
 *
 * <h2>No state of its own, and that is the acceptance criterion</h2>
 *
 * <p>Every method reads or writes on the caller's connection and keeps nothing between calls. A
 * field here holding sessions would be a process-local cache, and {@code INV-IDN-03} would become
 * <em>"a revoked session is refused once every instance's cache has expired"</em> — which is not
 * revocation. {@code NoProcessLocalSessionStateTest} fails the build if any production type grows
 * one.
 *
 * <h2>Liveness is decided by the server, on every lookup</h2>
 *
 * <p>{@code now()} in the predicate rather than a timestamp this process computed. An instance
 * running six minutes fast would otherwise resurrect sessions its neighbours consider dead, or kill
 * live ones — the ADR-0014 defect {@code V004} had to correct for the idempotency lease, and the
 * reason every time comparison on this platform belongs to the database.
 */
public final class JdbcSessionStore implements SessionStore<Connection> {

    private static final String TABLE = "identity.session";

    private static final String COLUMNS =
            "id, identity_id, token_hash, assurance, status, issued_at, idle_expires_at,"
                    + " absolute_expires_at, device, revoked_at";

    @Override
    public void insert(Connection unitOfWork, Session session) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(session, "session must not be null");

        // NO EXPLICIT LOCK HERE, and that is a measured decision rather than an omission.
        //
        // The first version took one, on the reasoning that both sides of the race must. A mutation
        // removing it SURVIVED, and the pair of mutations explains why: removing the FOR UPDATE from
        // revokeAll is caught, removing this is not. PostgreSQL takes a FOR KEY SHARE lock on the
        // referenced row for every insert that has a foreign key, and FOR UPDATE conflicts with it -
        // so the serialisation this needed already existed, supplied by
        // `identity_id REFERENCES identity.identity (id)`.
        //
        // Keeping a redundant lock would read as the mechanism and hide the real one, which is worse
        // than not having it: the next person to remove the foreign key would see a lock two lines
        // away and conclude the serialisation was safe.
        String sql =
                "INSERT INTO " + TABLE + " (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, session.id().value());
            insert.setObject(2, session.identityId().value());
            // The one unwrap on the write path. SecretsAreUnwrappedInOnePlaceTest pins it.
            insert.setString(3, session.tokenHash().expose());
            insert.setString(4, session.assurance().name());
            insert.setString(5, session.status().name());
            insert.setTimestamp(6, Timestamp.from(session.issuedAt()));
            insert.setTimestamp(7, Timestamp.from(session.idleExpiresAt()));
            insert.setTimestamp(8, Timestamp.from(session.absoluteExpiresAt()));
            insert.setString(9, session.device().orElse(null));
            insert.setTimestamp(
                    10, session.revokedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        } catch (SQLException e) {
            // Never the token hash: an exception message reaches a log line (INV-AUD-02), and the
            // hash identifies which session to go looking for.
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not insert session " + session.id(), e));
        }
    }

    @Override
    public Optional<Session> findLive(Connection unitOfWork, SessionToken token, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // One query, one answer. The predicate folds "no such token", "revoked", "idle-expired" and
        // "absolutely expired" into an empty result, so there is no branch anybody could later
        // report on - which is how INV-IDN-07's reasoning applies to a session identifier.
        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE token_hash = ?"
                        + " AND status = 'ACTIVE'"
                        + " AND idle_expires_at > ?"
                        + " AND absolute_expires_at > ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setString(1, token.hash().expose());
            select.setTimestamp(2, Timestamp.from(at));
            select.setTimestamp(3, Timestamp.from(at));
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read a session by token", e));
        }
    }

    @Override
    public boolean revoke(Connection unitOfWork, SessionId sessionId, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // Conditional, and the row count is the outcome: ten instances revoking one session produce
        // one transition and nine are told they lost. No read-then-write, so nothing to lose.
        //
        // No identity lock here: this targets one row by primary key, and a concurrent insert of a
        // DIFFERENT session is not in conflict with it.
        String sql =
                "UPDATE " + TABLE + " SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, sessionId.value());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not revoke session " + sessionId, e));
        }
    }

    @Override
    public int revokeAllFor(Connection unitOfWork, IdentityId identityId, Instant at) {
        return revokeAll(unitOfWork, identityId, null, at);
    }

    @Override
    public int revokeAllForExcept(
            Connection unitOfWork, IdentityId identityId, SessionId spare, Instant at) {
        Objects.requireNonNull(spare, "spare must not be null");
        return revokeAll(unitOfWork, identityId, spare, at);
    }

    private int revokeAll(
            Connection unitOfWork, IdentityId identityId, SessionId spare, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(at, "at must not be null");

        lockIdentity(unitOfWork, identityId);

        // Scoped by identity_id, and that scope is load-bearing rather than obvious: a predicate of
        // `id <> ?` alone would revoke every session on the platform except one.
        String sql =
                "UPDATE " + TABLE + " SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE identity_id = ? AND status = 'ACTIVE'"
                        + (spare == null ? "" : " AND id <> ?");
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, identityId.value());
            if (spare != null) {
                update.setObject(3, spare.value());
            }
            return update.executeUpdate();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not revoke the sessions of " + identityId, e));
        }
    }

    /**
     * Serialises session issuance against bulk revocation, on the identity's own row.
     *
     * <p>The row is only read - nothing about the identity changes - and the lock is released when
     * the caller's transaction ends. It exists so that the two operations cannot interleave: either
     * a session is inserted before a revocation and the revocation catches it, or the revocation
     * commits first and the login that would have issued the session verifies against whatever the
     * same transaction changed.
     *
     * <p>Verified before it was written: without it, a session issued concurrently with a revoke-all
     * is <strong>still live</strong> afterwards.
     */
    private static void lockIdentity(Connection unitOfWork, IdentityId identityId) {
        try (PreparedStatement lock =
                unitOfWork.prepareStatement(
                        "SELECT 1 FROM identity.identity WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, identityId.value());
            lock.executeQuery().close();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not lock identity " + identityId, e));
        }
    }

    @Override
    public boolean touch(
            Connection unitOfWork, SessionId sessionId, Instant at, SessionPolicy policy) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        // LEAST(...) is the whole safety property: the idle bound is extended, but never beyond the
        // absolute bound. Without it an attacker holding a stolen token and using it steadily would
        // keep the session alive for ever, and the absolute lifetime would be advisory.
        //
        // Conditional on the session still being live, so a touch cannot resurrect one that another
        // instance has just revoked or that has expired between the read and this write.
        String sql =
                "UPDATE " + TABLE
                        + " SET idle_expires_at = LEAST(?::timestamptz, absolute_expires_at)"
                        + " WHERE id = ?"
                        + " AND status = 'ACTIVE'"
                        + " AND idle_expires_at > ?"
                        + " AND absolute_expires_at > ?";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at.plus(policy.idleTimeout())));
            update.setObject(2, sessionId.value());
            update.setTimestamp(3, Timestamp.from(at));
            update.setTimestamp(4, Timestamp.from(at));
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not extend session " + sessionId, e));
        }
    }

    private static Session read(ResultSet rows) throws SQLException {
        Timestamp revokedAt = rows.getTimestamp("revoked_at");
        return Session.rehydrate(
                SessionId.of((UUID) rows.getObject("id")),
                IdentityId.of((UUID) rows.getObject("identity_id")),
                com.finapp.sharedkernel.security.Sensitive.of(rows.getString("token_hash")),
                AssuranceLevel.valueOf(rows.getString("assurance")),
                SessionStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("issued_at").toInstant(),
                rows.getTimestamp("idle_expires_at").toInstant(),
                rows.getTimestamp("absolute_expires_at").toInstant(),
                rows.getString("device"),
                revokedAt == null ? null : revokedAt.toInstant());
    }
}
