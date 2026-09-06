-- P1-TSK-013: where an authenticated presence lives.
--
-- ADR-0030. Sessions are SERVER-SIDE AND AUTHORITATIVE HERE, which is what makes INV-IDN-03 -
-- "a revoked session is refused on the next request, on every instance" - true by construction
-- rather than by discipline. A self-contained token would make validity a property of a signature
-- instead of of current state, and "log out everywhere" would become a promise the architecture
-- cannot keep.
--
-- NO REDIS, deliberately, and it is running and unused precisely so this is a decision rather than
-- a drift: a session store whose durability is weaker than the account it protects can resurrect a
-- revoked session after a restore, and no test on a healthy system finds that.
--
-- Forward-only (ADR-0011).

CREATE TABLE identity.session (

    -- The AGGREGATE's identifier, and NOT the thing a client presents. A UUIDv7 encodes its
    -- creation time, which is structure ADR-0030 forbids in the presented value. Two identifiers,
    -- two jobs: this one goes in foreign keys, logs and audit records; the token does not.
    id                   uuid        PRIMARY KEY,

    identity_id          uuid        NOT NULL REFERENCES identity.identity (id),

    -- SHA-256 of the token, base64. THE TOKEN ITSELF IS NEVER STORED.
    --
    -- A session token is a bearer credential: whoever holds it is the customer. A database leak
    -- with plaintext tokens hands an attacker every live session with no work at all, which is
    -- worse than the credential table, where Argon2 at least buys time.
    --
    -- SHA-256 rather than Argon2, and that is not an inconsistency with the credential table. A
    -- password needs a work factor because it is LOW-ENTROPY - a human chose it. A 256-bit random
    -- token has nothing to guess, so a work factor would buy no security while costing ~46 ms on
    -- every authenticated request.
    token_hash           text        NOT NULL,

    -- INV-IDN-05. A LEVEL, never a boolean: every real MFA bypass is a route that produces a
    -- session a boolean says is fine.
    assurance            text        NOT NULL,

    status               text        NOT NULL,

    issued_at            timestamptz NOT NULL,

    -- BOTH BOUNDS, ON THE ROW. Idle alone lets an active attacker hold a session for ever;
    -- absolute alone logs a working customer out mid-task.
    --
    -- Recorded here rather than read from policy at check time, which is INV-HIST-04's reasoning:
    -- a change of policy must not retroactively extend sessions issued under the old one.
    idle_expires_at      timestamptz NOT NULL,
    absolute_expires_at  timestamptz NOT NULL,

    -- Recorded, NEVER SCORED. Whether a device is trusted is a Phase 13 risk decision, and a column
    -- that quietly became an input to an access decision would be that decision taken by accident.
    -- Nothing populates this yet; P1-TSK-016 does. It is here because the alternative is a second
    -- migration in the same milestone for a column this table's own task already names.
    device               text,

    -- P1-TSK-014 sets this. Present now because the lookup must already treat REVOKED exactly as it
    -- treats expiry, and a nullable column costs nothing.
    revoked_at           timestamptz,

    -- Generated from AssuranceLevel; SessionMigrationTest fails the build if they drift.
    -- (The first version of this comment named IdentityEnumMigrationTest, which covers
    -- IdentityStatus and nothing else - a claim about a test that did not cover the thing
    -- it named, found by the completion gate.)
    CONSTRAINT session_assurance_is_known
        CHECK (assurance IN ('PASSWORD', 'MULTI_FACTOR', 'STRONG')),

    -- Generated from SessionStatus, reconciled by SessionMigrationTest. There is
    -- deliberately no EXPIRED: expiry is DERIVED from the
    -- bounds above, because a stored one needs a sweep to write it and until that sweep runs the
    -- database would say ACTIVE about a session that is not.
    CONSTRAINT session_status_is_known
        CHECK (status IN ('ACTIVE', 'REVOKED')),

    -- A session that is already expired when issued is not a session.
    CONSTRAINT session_bounds_follow_issue
        CHECK (idle_expires_at > issued_at AND absolute_expires_at > issued_at),

    -- The idle bound may equal the absolute one - a very short-lived session - but never exceed it,
    -- or the absolute lifetime is advisory and an attacker using a stolen token steadily keeps the
    -- session alive for ever.
    CONSTRAINT session_idle_bound_within_absolute
        CHECK (idle_expires_at <= absolute_expires_at),

    -- A revoked session has a time of revocation, and an active one does not. Without this, a row
    -- could claim to be revoked with nothing saying when - which is the one fact an investigation
    -- needs.
    CONSTRAINT session_revocation_is_dated
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

COMMENT ON TABLE identity.session IS
    'Server-side authenticated presence (P1-TSK-013, ADR-0030). Authoritative: every request that '
    'presents a token reads this table, so revocation is immediate on every instance by '
    'construction (INV-IDN-03) rather than by a cache that has to be told.';

COMMENT ON COLUMN identity.session.token_hash IS
    'SHA-256 of the presented token. The token itself is never stored, never logged and exists in '
    'this platform only for the moment it is handed to the client.';

-- UNIQUE, and the per-request lookup ADR-0030 accepts the cost of. Unique because at 256 bits a
-- collision is not a real event - and "not a real event" is not a guarantee, which is what a
-- constraint is for.
CREATE UNIQUE INDEX session_token_hash_is_unique
    ON identity.session (token_hash);

-- No index by identity yet. Listing a person's sessions and revoking them in bulk are the queries
-- that need one, and they arrive with P1-TSK-014 and P1-TSK-016. An index without a stated query is
-- removed (PHASE_1_PLAN.md §6).

-- The application role reads, writes and updates sessions in place: touching the idle bound and
-- revoking are both updates. That is not a weakening of INV-HIST-01 - a session is operational
-- state, not financial history - and what must stay immutable is the AUDIT RECORD of the login,
-- which lives in platform.audit_record where the role holds INSERT and SELECT only.
--
-- No DELETE: a retention sweep is Phase 15's and will run as a different role. Withholding it now
-- means the application cannot make a session disappear, so an investigation always has the row.
GRANT SELECT, INSERT, UPDATE ON identity.session TO finapp_app;
