-- P1-TSK-017: a second factor an identity is enrolling, or has enrolled.
--
-- WHY THIS TABLE HOLDS A RECOVERABLE SECRET, WHEN identity.credential CANNOT.
--
-- INV-IDN-01 requires a credential to be stored so that the original cannot be recovered, and
-- identity.credential satisfies it because verification compares DERIVATIONS. A TOTP secret cannot:
-- the server computes the expected code FROM the secret on every challenge, so holding it is the
-- mechanism rather than a shortcut. There is nothing to compare a derivation against.
--
-- So irreversibility is unavailable here and CONFIDENTIALITY replaces it - INV-IDN-08, catalogued
-- rather than left as a comment, because a reader who found a recoverable secret in an identity
-- table would otherwise have to guess whether it was a defect. The key is not in this database.
--
-- WHAT THAT DOES NOT BUY. A leaked secret lets an attacker generate valid codes indefinitely while
-- the customer's authenticator keeps working, so nothing looks wrong to anybody. That is why the
-- ciphertext is authenticated (AES-GCM) rather than merely encrypted: an attacker with WRITE access
-- to this column must not be able to substitute a secret they control.
CREATE TABLE identity.mfa_enrolment
(
    id                uuid        PRIMARY KEY,

    -- A real foreign key: both tables are in `identity`, so this crosses no module boundary. The
    -- ADR-0029 argument that kept identity.identity.party_id unconstrained does not apply here.
    identity_id       uuid        NOT NULL REFERENCES identity.identity (id),

    type              text        NOT NULL,

    -- The encrypted secret, its nonce, and the key version that wrote it.
    --
    -- key_version is INV-HIST-04's rule applied to a key: without it, rotating the encryption key
    -- means guessing which rows were written under which, and the only exits are decrypt-everything
    -- -and-hope or invalidating every enrolment. Same argument ADR-0032 made for recording
    -- derivation parameters per credential rather than as a global setting.
    --
    -- The nonce is stored, not derived. A nonce reused under one AES-GCM key does not weaken the
    -- cipher - it breaks it, leaking the authentication key - so it is random per encryption and
    -- there is deliberately nothing here that could regenerate one.
    secret_ciphertext bytea       NOT NULL,
    secret_nonce      bytea       NOT NULL,
    key_version       integer     NOT NULL,

    -- Recorded per enrolment, for the reason ADR-0032 gives: an authenticator app is configured
    -- once, from the QR code, and can never be told the period changed. A global setting could not
    -- be altered without silently breaking every existing enrolment.
    algorithm         text        NOT NULL,
    digits            integer     NOT NULL,
    period_seconds    integer     NOT NULL,

    status            text        NOT NULL,
    created_at        timestamptz NOT NULL,
    confirmed_at      timestamptz,
    discarded_at      timestamptz,

    -- Generated from MfaFactorType; MfaEnrolmentMigrationTest fails the build if they drift. A
    -- value the domain produces and the database refuses fails at the last write, after all the
    -- work, for a reason no error message explains (the P1-TSK-013 finding).
    CONSTRAINT mfa_enrolment_type_is_known
        CHECK (type IN ('TOTP')),

    CONSTRAINT mfa_enrolment_algorithm_is_known
        CHECK (algorithm IN ('SHA1', 'SHA256', 'SHA512')),

    CONSTRAINT mfa_enrolment_status_is_known
        CHECK (status IN ('PENDING', 'ACTIVE', 'DISCARDED')),

    -- Status and timestamp are ONE fact. A row where they disagree is a row nobody can interpret,
    -- and this is the same rule V005 applies to revoked_at.
    CONSTRAINT mfa_enrolment_confirmed_at_matches_status
        CHECK ((status = 'ACTIVE') = (confirmed_at IS NOT NULL)),

    CONSTRAINT mfa_enrolment_confirmed_after_created
        CHECK (confirmed_at IS NULL OR confirmed_at >= created_at),

    CONSTRAINT mfa_enrolment_discarded_at_matches_status
        CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL)),

    CONSTRAINT mfa_enrolment_discarded_after_created
        CHECK (discarded_at IS NULL OR discarded_at >= created_at),

    -- RFC 4226 requires at least six digits; eight is the practical ceiling because the truncation
    -- reads 31 bits. A period of zero would divide by zero in the step calculation.
    CONSTRAINT mfa_enrolment_parameters_are_sane
        CHECK (digits BETWEEN 6 AND 8 AND period_seconds > 0 AND key_version > 0),

    -- AES-GCM: a 96-bit nonce, and a ciphertext that is at least the 128-bit tag.
    CONSTRAINT mfa_enrolment_nonce_is_sized
        CHECK (octet_length(secret_nonce) = 12),
    CONSTRAINT mfa_enrolment_ciphertext_is_sized
        CHECK (octet_length(secret_ciphertext) BETWEEN 16 AND 1024)
);

-- At most one ACTIVE factor of a type per identity.
--
-- Partial, so a superseded enrolment could free its slot later - the identity.credential shape
-- rather than the login-identifier shape, and the difference is deliberate: replacing a second
-- factor is an ordinary thing a person does, whereas reissuing a retired login name would make
-- every audit record naming it ambiguous (P1-TSK-005).
CREATE UNIQUE INDEX mfa_enrolment_one_active_per_identity
    ON identity.mfa_enrolment (identity_id, type)
    WHERE status = 'ACTIVE';

-- And at most one PENDING, so two concurrent enrolment starts cannot leave two live secrets - only
-- one of which the customer scanned, with no way to tell which.
CREATE UNIQUE INDEX mfa_enrolment_one_pending_per_identity
    ON identity.mfa_enrolment (identity_id, type)
    WHERE status = 'PENDING';

-- The application inserts, reads, and updates PENDING -> ACTIVE or PENDING -> DISCARDED. It holds
-- NO DELETE, deliberately: an abandoned enrolment is evidence that somebody began adding a factor,
-- which is exactly what an investigator wants after an account takeover attempt. Replacing a
-- pending enrolment is therefore a conditional UPDATE to DISCARDED, never a row that disappears.
GRANT SELECT, INSERT, UPDATE ON identity.mfa_enrolment TO finapp_app;

COMMENT ON TABLE identity.mfa_enrolment IS
    'TOTP second factors. The secret is encrypted at rest under a key held outside this database '
    '(INV-IDN-08): its mechanism requires the platform to hold it, so INV-IDN-01 irreversibility '
    'is unavailable and confidentiality replaces it.';

COMMENT ON COLUMN identity.mfa_enrolment.secret_ciphertext IS
    'AES-256-GCM. Authenticated, so a substituted ciphertext fails rather than decrypting to a '
    'secret an attacker controls. RESTRICTED-PII at its ceiling.';
