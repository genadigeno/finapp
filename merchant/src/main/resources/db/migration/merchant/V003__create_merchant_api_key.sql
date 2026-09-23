-- The merchant's API credential and its revocation history (P6-TSK-002, ADR-0052).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: the status CHECKs come from
-- MerchantApiKeyStatus.sqlValueList(), the live-key index predicate from
-- sqlTerminalValueList(), the entropy bound from MerchantApiKeySecret.ENTROPY_BYTES.
-- MerchantApiKeyMigrationTest fails the build if this file and the code disagree.
--
-- THERE IS NO COLUMN FOR A SECRET, AND THAT IS THE DESIGN (INV-IDN-01). What is stored is a
-- SHA-256 hash of 32 random bytes, with the algorithm that produced it recorded beside it
-- (INV-IDN-02): a credential that misreports its own derivation is worse than one that does
-- not report it. The plaintext exists for the length of one issuance response and is then
-- unreachable from every read path on the platform - there is nothing here to leak.
--
-- WHY THE ID IS PUBLIC AND THE LOOKUP KEYS ON IT (ADR-0052's prefix rule): the presented
-- credential is <keyId>.<secret>. The id selects ONE row by primary key and the secret is
-- then verified against that row's hash in constant time, so authentication never scans
-- hashes and an audit record can name WHICH key acted without ever naming the secret.
--
-- NO CROSS-SCHEMA FOREIGN KEYS: merchant_id is same-schema, so that FK is legal and right.
--
-- THE MERCHANT'S STANDING IS NOT DUPLICATED HERE. A suspended merchant's keys must stop
-- working, and they do - by the authenticating query JOINING merchant.merchant and requiring
-- ACTIVE there. Copying a status into this table would create a second authority that drifts;
-- the join asks the one authoritative question per request instead.

CREATE TABLE merchant.merchant_api_key (
    -- UUIDv7, minted by the application (ADR-0013). PUBLIC: it is the lookup prefix and the
    -- value audit records name.
    id          uuid        PRIMARY KEY,

    merchant_id uuid        NOT NULL REFERENCES merchant.merchant (id),

    -- Base64 of a SHA-256 digest: 44 characters, always. Bounded rather than left free so a
    -- column that somehow received a plaintext secret would not fit quietly.
    secret_hash text        NOT NULL
        CONSTRAINT merchant_api_key_secret_hash_shape
            CHECK (secret_hash ~ '^[A-Za-z0-9+/]{43}=$'),

    -- INV-IDN-02: what produced the stored value, stored beside it.
    algorithm   text        NOT NULL
        CONSTRAINT merchant_api_key_algorithm_bounded
            CHECK (length(algorithm) BETWEEN 1 AND 50),

    status      text        NOT NULL
        CONSTRAINT merchant_api_key_status_is_known
            CHECK (status IN ('ACTIVE', 'REVOKED')),

    -- Application-supplied from one injected Clock, never DEFAULT now().
    issued_at   timestamptz NOT NULL,
    issued_by   text        NOT NULL
        CONSTRAINT merchant_api_key_issued_by_bounded
            CHECK (length(issued_by) BETWEEN 1 AND 200),
    revoked_at  timestamptz,

    -- A revocation instant exists exactly when the key is revoked. The equality form catches
    -- both defects with one predicate (the ledger owner_ref rule's shape).
    CONSTRAINT merchant_api_key_revoked_at_matches_status
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT merchant_api_key_revocation_is_not_before_issuance
        CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

-- ONE HASH, EVER, ACROSS THE PLATFORM. Not a per-merchant uniqueness: two merchants holding
-- the same secret would let either authenticate as a key that is not theirs, whoever owns the
-- row. At 32 bytes of entropy a collision is not a practical concern - this constraint exists
-- so that a defect in generation (a fixed seed, a reused value, a test double reaching
-- production) fails LOUDLY at the insert rather than silently creating two doors with one key.
CREATE UNIQUE INDEX merchant_api_key_one_row_per_hash
    ON merchant.merchant_api_key (secret_hash);

-- The authenticating lookup's index: live keys only, so a revoked key is not merely filtered
-- but absent from the index the authenticator reads.
CREATE INDEX merchant_api_key_live_by_merchant
    ON merchant.merchant_api_key (merchant_id)
    WHERE status NOT IN ('REVOKED');

-- THE MACHINE'S ONE EDGE BINDS EVERY WRITER (INV-LIFE-02): ACTIVE -> REVOKED and nothing
-- else, so a revoked credential cannot be reinstated by raw SQL any more than by the domain.
-- Reinstating a credential whose secret may have been disclosed is precisely the act a
-- security model must make impossible, which is why this is a trigger and not a convention.
-- The frozen columns make identity, the hash and the algorithm insert-time facts.
CREATE FUNCTION merchant.merchant_api_key_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.secret_hash IS DISTINCT FROM OLD.secret_hash
            OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.issued_by IS DISTINCT FROM OLD.issued_by THEN
        RAISE EXCEPTION 'a merchant api key''s identity and secret are frozen: only status and revoked_at ever change (INV-IDN-01)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('REVOKED')) THEN
        RAISE EXCEPTION 'a merchant api key moves only along the machine''s one edge: ACTIVE -> {REVOKED}, and REVOKED is terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_api_key_permits_only_machine_edges
    BEFORE UPDATE ON merchant.merchant_api_key
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_api_key_permits_only_machine_edges();

CREATE TABLE merchant.merchant_api_key_event (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    key_id      uuid        NOT NULL REFERENCES merchant.merchant_api_key (id),

    from_status text        NOT NULL
        CONSTRAINT merchant_api_key_event_from_status_is_known
            CHECK (from_status IN ('ACTIVE', 'REVOKED')),
    to_status   text        NOT NULL
        CONSTRAINT merchant_api_key_event_to_status_is_known
            CHECK (to_status IN ('ACTIVE', 'REVOKED')),

    -- Revoking a counterparty's access is a security judgement; the why is part of the record
    -- (INV-AUD-03). NOT NULL because the only transition this table records requires one.
    reason      text        NOT NULL
        CONSTRAINT merchant_api_key_event_reason_bounded
            CHECK (length(reason) BETWEEN 1 AND 4000),

    actor_id    text        NOT NULL
        CONSTRAINT merchant_api_key_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type  text        NOT NULL
        CONSTRAINT merchant_api_key_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),

    occurred_at timestamptz NOT NULL
);

CREATE INDEX merchant_api_key_event_by_key ON merchant.merchant_api_key_event (key_id);

COMMENT ON TABLE merchant.merchant_api_key IS
    'A merchant''s API credential (ADR-0052): a SHA-256 hash of 32 random bytes, never a secret (INV-IDN-01), with its algorithm recorded (INV-IDN-02). The id is PUBLIC - it is the lookup prefix of <keyId>.<secret> and the value audit records name. ACTIVE -> REVOKED, terminal.';
COMMENT ON COLUMN merchant.merchant_api_key.secret_hash IS
    'SHA-256 and deliberately not Argon2: the input is 32 random bytes, so there is nothing to guess and a work factor would cost ~46ms on every merchant API request while buying no security (SessionToken''s recorded argument).';
COMMENT ON TABLE merchant.merchant_api_key_event IS
    'Append-only revocation history with the operator''s required reason. Server-assigned order; SELECT and INSERT only.';

-- THE GRANTS ARRIVE WITH THE TABLE. SELECT and INSERT for the row's life; UPDATE narrowed to
-- the two columns a revocation changes. No DELETE: a key's end is a status, never an absence -
-- and a deleted credential row is a revocation nobody can prove happened.
GRANT SELECT, INSERT ON merchant.merchant_api_key TO finapp_app;
GRANT UPDATE (status, revoked_at) ON merchant.merchant_api_key TO finapp_app;

-- The history is append-only at the privilege (the audit_record model).
GRANT SELECT, INSERT ON merchant.merchant_api_key_event TO finapp_app;
