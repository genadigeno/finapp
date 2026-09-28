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
 * <h2>Liveness is decided by the server, on every lookup, against bounds the server stamped</h2>
 *
 * <p>{@code now()} in the predicate rather than a timestamp this process computed. An instance
 * running six minutes fast would otherwise resurrect sessions its neighbours consider dead, or kill
 * live ones — the ADR-0014 defect {@code V004} had to correct for the idempotency lease, and the
 * reason every time comparison on this platform belongs to the database.
 *
 * <p><strong>Until {@code X-TSK-007} this paragraph described a design the code did not
 * have.</strong> Every predicate compared the bounds with an instant the caller read from its own
 * clock, and the bounds themselves were stamped by the issuing instance's clock and extended by the
 * touching one's. Moving only the judgement would have left a fast issuer's sessions outliving the
 * absolute lifetime by its skew, and a slow issuer's dead on arrival. So both halves are the
 * database's: {@link #LIVE} is judged at {@code now()}, and every bound is written from
 * {@code now()} as well. Nothing this class binds is ever compared with a bound.
 */
public final class JdbcSessionStore implements SessionStore<Connection> {

    private static final String TABLE = "identity.session";

    private static final String COLUMNS =
            "id, identity_id, token_hash, assurance, status, issued_at, idle_expires_at,"
                    + " absolute_expires_at, device, revoked_at";

    /**
     * What "live" means, defined once ({@code X-TSK-007}).
     *
     * <p>{@code ACTIVE}, and inside both bounds <strong>at the database's {@code now()}</strong>,
     * which is the start of the caller's transaction. So a lookup and the touch after it, or a
     * rotation's revoke and the insert after it, are judged at one instant. Every statement that
     * asks the question uses this constant verbatim, so the lookup, the listing, the gauge, the
     * touch and rotation cannot disagree about what a session is.
     *
     * <p>Strictly {@code >}: at the bound itself the session is over.
     */
    private static final String LIVE =
            "status = 'ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now()";

    /**
     * A duration bound as integer milliseconds and scaled in SQL: {@code INV-MON-01}'s rule, as
     * the idempotency lease applies it.
     */
    private static final String NOW_PLUS_MILLIS = "now() + (? * INTERVAL '1 millisecond')";

    @Override
    public Session insert(Connection unitOfWork, Session.Draft draft) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(draft, "draft must not be null");

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
        //
        // THE DATABASE STAMPS BOTH BOUNDS (X-TSK-007). The draft carries lifetimes, never instants.
        // A rotation's absolute bound is copied from its predecessor's ROW, which the rotation's
        // revoke has just locked in this transaction - not from the Session the caller holds, a
        // copy read in an earlier one. live_from is the database's own instant for the row, which
        // V016's session_bounds_follow_liveness compares the bounds with on one clock. issued_at
        // is the deciding instance's business time and is compared with nothing. RETURNING hands
        // the stamped row back, so no caller holds bounds the database did not write.
        String absolute =
                switch (draft.absolute()) {
                    case SessionAbsoluteBound.Lifetime ignored -> NOW_PLUS_MILLIS;
                    case SessionAbsoluteBound.Inherited ignored ->
                            "(SELECT absolute_expires_at FROM " + TABLE + " WHERE id = ?)";
                };
        String sql =
                "INSERT INTO " + TABLE + " (" + COLUMNS + ", live_from)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?,"
                        + " LEAST(" + NOW_PLUS_MILLIS + ", " + absolute + "),"
                        + " " + absolute + ", ?, NULL, now())"
                        + " RETURNING " + COLUMNS;
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, draft.id().value());
            insert.setObject(2, draft.identityId().value());
            // The one unwrap on the write path. SecretsAreUnwrappedInOnePlaceTest pins it.
            insert.setString(3, draft.tokenHash().expose());
            insert.setString(4, draft.assurance().name());
            insert.setTimestamp(5, Timestamp.from(draft.issuedAt()));
            insert.setLong(6, draft.idleTimeout().toMillis());
            // The absolute expression appears twice - inside the idle bound's clamp and as the
            // bound itself - and now() is one instant for the whole transaction, so both are the
            // same value and the idle bound can never exceed the absolute one.
            bindAbsolute(insert, 7, draft.absolute());
            bindAbsolute(insert, 8, draft.absolute());
            insert.setString(9, draft.device().orElse(null));
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return read(row);
            }
        } catch (SQLException e) {
            // Never the token hash: an exception message reaches a log line (INV-AUD-02), and the
            // hash identifies which session to go looking for.
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not insert session " + draft.id(), e));
        }
    }

    private static void bindAbsolute(
            PreparedStatement insert, int index, SessionAbsoluteBound absolute)
            throws SQLException {
        switch (absolute) {
            case SessionAbsoluteBound.Lifetime lifetime ->
                    insert.setLong(index, lifetime.value().toMillis());
            case SessionAbsoluteBound.Inherited inherited ->
                    insert.setObject(index, inherited.predecessor().value());
        }
    }

    @Override
    public Optional<Session> findLive(Connection unitOfWork, SessionToken token) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(token, "token must not be null");

        // One query, one answer. The predicate folds "no such token", "revoked", "idle-expired" and
        // "absolutely expired" into an empty result, so there is no branch anybody could later
        // report on - which is how INV-IDN-07's reasoning applies to a session identifier.
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE token_hash = ? AND " + LIVE;
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setString(1, token.hash().expose());
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
        // LIVE rather than ACTIVE (X-TSK-007): rotation is the only caller, and it inherits this
        // session's absolute bound. Judged at the same now() the replacement is then stamped from,
        // so a session that expired since the boundary proved it is not rotated at all.
        //
        // No identity lock here: this targets one row by primary key, and a concurrent insert of a
        // DIFFERENT session is not in conflict with it.
        String sql =
                "UPDATE " + TABLE + " SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE id = ? AND " + LIVE;
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
    public java.util.List<Session> findLiveFor(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        // The ownership control for listing IS this WHERE clause. Reading every session and
        // filtering in Java would put the control somewhere a future caller can skip; a predicate
        // cannot be skipped by the code that runs the query.
        //
        // Ordered by issued_at, which is business time: the order the listing displays. It decides
        // nothing, so two instances' clocks disagreeing about it costs a display order at most.
        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE identity_id = ? AND " + LIVE
                        + " ORDER BY issued_at DESC, id DESC";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            try (ResultSet rows = select.executeQuery()) {
                java.util.List<Session> live = new java.util.ArrayList<>();
                while (rows.next()) {
                    live.add(read(rows));
                }
                return java.util.List.copyOf(live);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not list the sessions of " + identityId, e));
        }
    }

    @Override
    public java.util.OptionalLong revokeOwned(
            Connection unitOfWork, SessionId sessionId, IdentityId owner, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // identity_id = ? IS the ownership check (ADR-0031). It is in the statement rather than in
        // a load-then-compare because the compare-then-act is a TOCTOU race, and because a check
        // performed against a row read a moment ago is a check against a copy of the truth.
        //
        // RETURNING carries the session's lifetime out (P1-TSK-029). Whole seconds, cast in SQL:
        // INV-MON-01 forbids floating point on any production path, and a duration measured in
        // seconds gains nothing from a double (OutboxBacklog's precedent).
        //
        // now() - live_from, both the database's (X-TSK-007). It was revoked_at - issued_at: this
        // instance's clock minus the issuing instance's, a measurement of two machines as much as
        // of the session, and negative whenever the issuer ran ahead of the revoker by more than
        // the session had lived. revoked_at is still this instance's business time, beside the
        // audit record that carries the same reading.
        //
        // ACTIVE, not LIVE, deliberately: this ends a session its owner named, and whether an
        // already-expired one should count is recorded against X-TSK-008 rather than changed here.
        String sql =
                "UPDATE " + TABLE + " SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE id = ? AND identity_id = ? AND status = 'ACTIVE'"
                        + " RETURNING extract(epoch FROM now() - live_from)::bigint";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, sessionId.value());
            update.setObject(3, owner.value());
            try (ResultSet revoked = update.executeQuery()) {
                // Empty is "nothing was ended", which is what the boolean used to say. One row is
                // guaranteed by the primary key, so there is no "which of several" to resolve.
                return revoked.next()
                        ? java.util.OptionalLong.of(revoked.getLong(1))
                        : java.util.OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not revoke session " + sessionId, e));
        }
    }

    @Override
    public long countLive(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");

        // THE SAME PREDICATE AS findLive, and that is the point rather than tidiness. There is no
        // EXPIRED status and no sweep (ADR-0030, P1-TSK-013), so counting `status = 'ACTIVE'` alone
        // would count sessions nobody can use - and it would be wrong in the REASSURING direction,
        // reporting live customers indefinitely. A gauge that disagreed with the lookup about what
        // a session is would be a number an operator could not act on. Since X-TSK-007 it is the
        // same constant, so the two cannot drift.
        String sql = "SELECT count(*) FROM " + TABLE + " WHERE " + LIVE;
        try (PreparedStatement count = unitOfWork.prepareStatement(sql)) {
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not count live sessions", e));
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
    public boolean touch(Connection unitOfWork, SessionId sessionId, SessionPolicy policy) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(policy, "policy must not be null");

        // LEAST(...) is the whole safety property: the idle bound is extended, but never beyond the
        // absolute bound. Without it an attacker holding a stolen token and using it steadily would
        // keep the session alive for ever, and the absolute lifetime would be advisory.
        //
        // GREATEST(idle_expires_at, ...) keeps it monotonic (X-TSK-007). now() is a transaction's
        // start, so a touch whose transaction began first can reach this row last; without the
        // floor it would pull a later touch's extension back. The extension is measured from the
        // database's now(), never from this instance's clock.
        //
        // Conditional on the session still being live, so a touch cannot resurrect one that another
        // instance has just revoked or that has expired between the read and this write.
        String sql =
                "UPDATE " + TABLE
                        + " SET idle_expires_at = GREATEST(idle_expires_at,"
                        + " LEAST(" + NOW_PLUS_MILLIS + ", absolute_expires_at))"
                        + " WHERE id = ? AND " + LIVE;
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setLong(1, policy.idleTimeout().toMillis());
            update.setObject(2, sessionId.value());
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
