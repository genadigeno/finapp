-- Identity: who can authenticate (P1-TSK-005, ADR-0029).
--
-- THE PARTY REFERENCE IS A VALUE, NOT A FOREIGN KEY
--   party_id below is a uuid with NO REFERENCES clause, and that is deliberate rather than an
--   oversight. A foreign key across a module schema boundary is coupling that neither Gradle nor
--   ArchUnit can see, and it would turn ADR-0001's stated escape - extracting a module into its own
--   service - into a data migration.
--
--   The cost is accepted and stated: nothing at the database level stops an identity referencing a
--   party that does not exist. What prevents it is that the only code creating an identity creates
--   the party in the same transaction (P1-TSK-006), which is a property a test can assert.
--
-- CHECK CONSTRAINTS ARE GENERATED FROM IdentityStatus, and IdentityEnumMigrationTest fails the
-- build if they disagree - the P0-TSK-022 pattern.

CREATE TABLE identity.identity (
    id                uuid        PRIMARY KEY,

    -- By value. See above.
    party_id          uuid        NOT NULL,

    -- What someone types to log in. NOT an email address: an identifier that is also a contact
    -- channel cannot be changed without changing how someone logs in, and cannot be verified
    -- without blocking login (PHASE_1_PLAN.md section 4).
    --
    -- Normalised to lower case by LoginIdentifier before it arrives, which is what makes the unique
    -- index below mean what it appears to mean.
    login_identifier  text        NOT NULL,

    status            text        NOT NULL,
    created_at        timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,

    CONSTRAINT identity_status_is_known
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT identity_login_identifier_is_bounded
        CHECK (length(login_identifier) BETWEEN 3 AND 64),

    -- The charset LoginIdentifier enforces, enforced again here. Not redundant: the domain type is
    -- what the application goes through, and this is what anything else does - a migration, an
    -- operator, a future writer nobody has written yet.
    CONSTRAINT identity_login_identifier_charset
        CHECK (login_identifier ~ '^[a-z0-9._-]+$'),

    CONSTRAINT identity_status_change_is_not_before_creation
        CHECK (status_changed_at >= created_at)
);

-- A LOGIN IDENTIFIER IS UNIQUE ACROSS ALL IDENTITIES, INCLUDING CLOSED ONES.
--
-- Not a partial index, and the difference from party.customer is the point. A closed relationship
-- may be replaced by a new one, but a retired login identifier must NOT become available again:
-- reissuing it would let a new person authenticate with a name that appears in somebody else's
-- audit history, and every record naming it would become ambiguous about which person it meant.
CREATE UNIQUE INDEX identity_login_identifier_is_unique
    ON identity.identity (login_identifier);

-- Serves "the identities of this party", which registration needs and which an operator needs when
-- a person reports they cannot log in.
CREATE INDEX identity_by_party ON identity.identity (party_id);

COMMENT ON TABLE identity.identity IS
    'A means of proving presence. ACTIVE <-> SUSPENDED, and -> CLOSED which is terminal. Closing is '
    'not deletion: audit records and past sessions reference the identity, and INV-HIST-01 forbids '
    'rewriting history that points at it. Holds no credential - that is a separate table.';

-- UPDATE is required for the status transitions and for nothing else. No DELETE: a closed identity
-- is how a retired login is represented, and destroying the row would destroy the evidence that it
-- was ever used.
GRANT SELECT, INSERT, UPDATE ON identity.identity TO finapp_app;
