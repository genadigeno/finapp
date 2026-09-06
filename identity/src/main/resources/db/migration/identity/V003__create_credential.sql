-- P1-TSK-007: where a credential lives, and what the database refuses to store in it.
--
-- ADR-0032. The decision this table exists for is not which algorithm - it is that the ALGORITHM
-- AND THE PARAMETERS ARE RECORDED PER CREDENTIAL. A platform whose work factor is a global setting
-- cannot raise it: changing the setting changes what new credentials use, nothing records what the
-- old ones used, and the only exits are invalidating every credential - a forced reset for every
-- customer - or guessing.
--
-- Forward-only (ADR-0011). A mistake here is corrected by a further migration, never by editing
-- this file.

CREATE TABLE identity.credential (
    id                uuid        PRIMARY KEY,

    -- FK WITHIN this schema, which is permitted and correct: credential and identity are both owned
    -- by the identity module, exactly as party.customer references party.party. What ADR-0029
    -- forbids is an FK ACROSS module schemas, which is coupling neither Gradle nor ArchUnit can see.
    --
    -- No ON DELETE clause. An identity is never deleted - closing it is how a retired login is
    -- represented - so a cascade would be a rule for a case that must not happen, and the default
    -- RESTRICT means an attempt fails loudly.
    identity_id       uuid        NOT NULL REFERENCES identity.identity (id),

    type              text        NOT NULL,

    -- INV-IDN-02. NOT NULL is the invariant: a credential whose algorithm or cost factors are
    -- unknown cannot be assessed, cannot be upgraded, and cannot be defended.
    algorithm         text        NOT NULL,
    memory_kib        integer     NOT NULL,
    iterations        integer     NOT NULL,
    parallelism       integer     NOT NULL,

    -- The PHC-encoded output: '$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>'. Authoritative for
    -- verification. It also contains the parameters, which the columns above duplicate on purpose -
    -- see the index comment below for what that duplication buys.
    --
    -- RESTRICTED-PII. Not the plaintext, and still the most sensitive column on this platform: it
    -- is what an attacker with a copy of the database attacks offline.
    derivation        text        NOT NULL,

    status            text        NOT NULL,
    created_at        timestamptz NOT NULL,
    superseded_at     timestamptz,

    CONSTRAINT credential_type_is_known
        CHECK (type IN ('PASSWORD')),
    CONSTRAINT credential_algorithm_is_known
        CHECK (algorithm IN ('ARGON2ID')),
    CONSTRAINT credential_status_is_known
        CHECK (status IN ('ACTIVE', 'SUPERSEDED')),

    -- INV-IDN-01, AT THE STRONGEST MECHANISM AVAILABLE.
    --
    -- This is the constraint worth reading twice. A derivation must be in its algorithm's encoded
    -- form, which means a PLAINTEXT PASSWORD CANNOT PHYSICALLY BE STORED IN THIS COLUMN - not by a
    -- future writer, not by a migration, not by an operator, not by a bug in code that has not been
    -- written yet. DB-CONSTRAINT outranks DOMAIN and STATIC in the invariant catalogue, and this is
    -- the platform's most consequential secret, so it gets the strongest mechanism rather than the
    -- most convenient one.
    --
    -- The length floor is the second half: an Argon2id encoded string with a 16-byte salt and a
    -- 32-byte output is around 95 characters, so a short value cannot satisfy this even if it began
    -- with the right prefix.
    CONSTRAINT credential_derivation_is_encoded
        CHECK (algorithm <> 'ARGON2ID' OR derivation LIKE '$argon2id$%'),
    CONSTRAINT credential_derivation_is_bounded
        CHECK (length(derivation) BETWEEN 50 AND 512),

    -- Cost factors are positive. Zero is what an uninitialised integer holds, and a credential
    -- recording zero iterations would look parameterised while saying nothing.
    CONSTRAINT credential_parameters_are_positive
        CHECK (memory_kib > 0 AND iterations > 0 AND parallelism > 0),

    -- The two must agree, in both directions. A superseded credential with no timestamp cannot be
    -- aged or explained; an active one with a timestamp is a contradiction.
    CONSTRAINT credential_superseded_at_matches_status
        CHECK ((status = 'ACTIVE') = (superseded_at IS NULL)),
    CONSTRAINT credential_superseded_after_created
        CHECK (superseded_at IS NULL OR superseded_at >= created_at)
);

-- AT MOST ONE ACTIVE CREDENTIAL PER IDENTITY AND TYPE.
--
-- An invariant ACROSS credentials of one identity, which is why it is here and not in the
-- aggregate: an entity sees itself, and only the database can arbitrate between two concurrent
-- transactions - which ADR-0014 says is the normal case rather than the exception.
--
-- Partial, and the contrast with identity_login_identifier_is_unique one file over is the point. A
-- superseded credential MUST free the slot, because replacing a password is the ordinary thing a
-- person does; a retired login identifier must NOT free its name, because reissuing it would let a
-- new person authenticate with a name in somebody else's audit history. Same mechanism, opposite
-- answers, both deliberate.
CREATE UNIQUE INDEX credential_one_active_per_identity_and_type
    ON identity.credential (identity_id, type)
    WHERE status = 'ACTIVE';

-- Serves "this identity's credentials", which an operator needs when somebody reports they cannot
-- log in, and which the superseded history answers.
CREATE INDEX credential_by_identity ON identity.credential (identity_id);

-- THE REASON THE PARAMETERS ARE COLUMNS AT ALL (ADR-0032 Option D).
--
-- "Which credentials are below current policy?" is what an upgrade campaign is, and with the
-- parameters left inside the encoded string it would be a full table scan with a parse per row.
-- Here it is an index scan. The duplication between this and the encoded form is deliberate, and
-- the encoded form remains authoritative for verification.
CREATE INDEX credential_by_strength
    ON identity.credential (algorithm, memory_kib, iterations, parallelism)
    WHERE status = 'ACTIVE';

-- A CREDENTIAL IS SUPERSEDED, NEVER EDITED.
--
-- A CHECK constraint sees only the row being written, so it cannot express "this column did not
-- change". Only a trigger can, and without one an UPDATE could rewrite a derivation in place, move
-- a credential to a different identity, or un-supersede one - none of which any application code
-- does, and all of which the application ROLE is able to do because superseding needs UPDATE.
--
-- This is the P0-TSK-015 pattern, applied for the same reason it was applied to the idempotency
-- record: the grant is wider than the intent, so the schema narrows it.
CREATE OR REPLACE FUNCTION identity.credential_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.identity_id IS DISTINCT FROM OLD.identity_id
        OR NEW.type IS DISTINCT FROM OLD.type
        OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
        OR NEW.memory_kib IS DISTINCT FROM OLD.memory_kib
        OR NEW.iterations IS DISTINCT FROM OLD.iterations
        OR NEW.parallelism IS DISTINCT FROM OLD.parallelism
        OR NEW.derivation IS DISTINCT FROM OLD.derivation
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'a credential is superseded, never edited: only status and superseded_at may change'
            USING ERRCODE = 'check_violation';
    END IF;

    -- The only permitted transition, and it is one-way. SUPERSEDED is terminal because the whole
    -- reason a credential was superseded is that it must not authenticate anybody again, and a
    -- machine that allows the way back makes that a question of who calls what.
    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'SUPERSEDED' THEN
        RAISE EXCEPTION 'a credential may only move from ACTIVE to SUPERSEDED'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER credential_is_append_only
    BEFORE UPDATE ON identity.credential
    FOR EACH ROW
    EXECUTE FUNCTION identity.credential_is_append_only();

COMMENT ON TABLE identity.credential IS
    'A derivation of a secret, plus the algorithm and cost factors that produced it (INV-IDN-02). '
    'Never the plaintext - the derivation column will not accept a value that is not in the '
    'encoded form of its algorithm, so a plaintext cannot be stored here at all (INV-IDN-01). '
    'ACTIVE -> SUPERSEDED, terminal; a change writes a new row so the superseded ones record when '
    'protection changed.';

COMMENT ON COLUMN identity.credential.derivation IS
    'RESTRICTED-PII. PHC-encoded Argon2id output, authoritative for verification. Offline-crackable '
    'material: never logged, never emitted in an event, never in an API response.';

-- UPDATE is required to supersede and for nothing else, and the trigger above is what narrows it to
-- that. No DELETE: a superseded credential is the evidence that protection changed, and destroying
-- it destroys the only record of when.
GRANT SELECT, INSERT, UPDATE ON identity.credential TO finapp_app;
