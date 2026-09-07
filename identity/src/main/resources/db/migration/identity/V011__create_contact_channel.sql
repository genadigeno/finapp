-- P1-TSK-023: the verified channel INV-IDN-06 requires, and which did not exist.
--
-- WHY THIS TABLE ARRIVES WITH RECOVERY RATHER THAN BEFORE IT.
--
-- INV-IDN-06 forbids recovery "without proving control of a previously registered and verified
-- channel". PHASE_1_PLAN.md §Value objects asserts that an Identity carries "a separate, changeable,
-- separately-verified email" - and nothing in the platform did, and no backlog task owned one. That
-- is the seventh backlog defect of this class in Phase 1 and the most consequential, because the
-- others were missing endpoints and this is a missing PRECONDITION OF THE INVARIANT: without a
-- channel, recovery cannot satisfy INV-IDN-06 at all, only appear to.
--
-- Built here on the P1-TSK-016 precedent - the task's own precondition, not future-phase work.
--
-- WHY IT IS NOT THE LOGIN IDENTIFIER.
--
-- P1-TSK-005 excluded '@' from LoginIdentifier's charset specifically so this confusion could not
-- arrive silently. An identifier that is also a contact channel cannot be changed without changing
-- how somebody logs in, and cannot be verified without blocking login.
CREATE TABLE identity.contact_channel
(
    id                        uuid        PRIMARY KEY,

    identity_id               uuid        NOT NULL REFERENCES identity.identity (id),

    kind                      text        NOT NULL,

    -- RESTRICTED-PII. Normalised (lower-cased, trimmed) by EmailAddress before it reaches here, so
    -- a capital letter finds the row the unique index below claimed.
    address                   text        NOT NULL,

    -- The verification challenge. Hashed, never in clear: whoever holds the plaintext can verify the
    -- channel, so a database leak with plaintext tokens hands an attacker every pending
    -- verification with no work at all (SessionToken's argument).
    verification_token_hash   text,
    verification_expires_at   timestamptz,

    -- NULL until somebody proved control. This column IS the control INV-IDN-06 names.
    verified_at               timestamptz,

    added_at                  timestamptz NOT NULL,

    CONSTRAINT contact_channel_kind_is_known
        CHECK (kind IN ('EMAIL')),

    CONSTRAINT contact_channel_address_is_bounded
        CHECK (length(address) BETWEEN 3 AND 254),

    -- Narrower than EmailAddress's own rule and saying so, the V003 precedent: a POSIX class
    -- expresses "no control characters, no whitespace" exactly, and a constraint written to LOOK
    -- like parity while silently missing a category would be worse, because the next reader would
    -- trust it. The domain rule is the authority; this is the floor no writer can go under.
    CONSTRAINT contact_channel_address_is_printable
        CHECK (address ~ '^[^[:space:][:cntrl:]]+@[^[:space:][:cntrl:]]+$'),

    -- A pending verification is one fact in two columns, so they may not disagree.
    CONSTRAINT contact_channel_challenge_is_complete
        CHECK ((verification_token_hash IS NULL) = (verification_expires_at IS NULL)),

    -- Once verified, the challenge is spent. Keeping a live token on a verified channel would mean
    -- a leaked token could re-verify an address somebody had since taken over.
    CONSTRAINT contact_channel_verified_has_no_live_challenge
        CHECK (verified_at IS NULL OR verification_token_hash IS NULL)
);

-- At most one VERIFIED channel per identity per kind.
--
-- Partial and per KIND, both deliberately. Partial, because an unverified attempt must not block a
-- customer correcting a typo - that would be an account lockout caused by a mistyped address.
-- Per kind, because "one verified channel" becomes the wrong rule the moment a phone channel
-- exists, and rebuilding a unique index on a live table is not a migration anybody wants.
CREATE UNIQUE INDEX contact_channel_one_verified_per_identity_and_kind
    ON identity.contact_channel (identity_id, kind)
    WHERE verified_at IS NOT NULL;

-- The lookup verification runs: a pending challenge by its token hash.
CREATE UNIQUE INDEX contact_channel_verification_token_is_unique
    ON identity.contact_channel (verification_token_hash)
    WHERE verification_token_hash IS NOT NULL;

-- The lookup recovery initiation runs: does this identity have a verified channel?
CREATE INDEX contact_channel_verified_by_identity
    ON identity.contact_channel (identity_id)
    WHERE verified_at IS NOT NULL;

GRANT SELECT, INSERT, UPDATE ON identity.contact_channel TO finapp_app;

COMMENT ON TABLE identity.contact_channel IS
    'Where the platform can reach a person, and whether they have proven they control it. '
    'The verified channel INV-IDN-06 requires; deliberately never the login identifier.';
