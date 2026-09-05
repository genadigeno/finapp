-- Party and Customer: who exists, and what relationship they hold (P1-TSK-005, ADR-0029).
--
-- TWO TABLES, NOT ONE, AND THAT IS THE POINT
--   The pressure to collapse these into a single `users` row comes from the simplest first story,
--   where every party is a customer and the distinction looks like ceremony. The cost lands in the
--   cases a merged table cannot represent at all: a beneficial owner who must be recorded and is
--   not a customer; an organisation that is a customer and is not a person; a party who holds two
--   relationships over time; and a party who ceases to be a customer while every record referring
--   to them stays exactly as it was.
--
--   DELIVERY_PLAN.md section 17 names collapsing them as this phase's top risk.
--
-- EVERY CHECK CONSTRAINT BELOW IS GENERATED FROM ITS ENUM
--   PartyKind.sqlValueList() and CustomerStatus.sqlValueList() produce these literal lists, and
--   PartyEnumMigrationTest fails the build if this file and the enums disagree. Adding a state
--   without a migration is therefore impossible to do quietly - the P0-TSK-022 pattern. Renaming a
--   constant is a schema change, not a refactor.

CREATE TABLE party.party (
    -- UUIDv7, minted by the application (ADR-0013). Time-ordered, so the primary key index appends
    -- rather than writing randomly across its pages.
    id                uuid        PRIMARY KEY,

    kind              text        NOT NULL,

    -- RESTRICTED-PII. Bounded to match PartyName.MAX_LENGTH, so a value the domain accepts always
    -- stores and a value the column accepts is always one the domain would have accepted.
    display_name      text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now(). The database's clock and
    -- the application's are different clocks, and a row whose timestamps come from both cannot be
    -- reasoned about (DOMAIN_MODEL.md section Time).
    registered_at     timestamptz NOT NULL,

    CONSTRAINT party_kind_is_known CHECK (kind IN ('PERSON', 'ORGANISATION')),
    CONSTRAINT party_display_name_is_bounded
        CHECK (length(display_name) BETWEEN 1 AND 200)
);

COMMENT ON TABLE party.party IS
    'Who exists. No lifecycle: existence has no states, and every state people reach for - '
    'inactive, closed, archived - is a statement about a relationship or a login, each of which '
    'has its own table. A party is never deleted; records reference it and INV-HIST-01 forbids '
    'rewriting them.';

CREATE TABLE party.customer (
    id                uuid        PRIMARY KEY,

    -- FK WITHIN this schema, which is permitted and correct: party and customer are both owned by
    -- the party module. What is forbidden is an FK ACROSS schemas (identity.identity holds a party
    -- id by value), because that is coupling neither Gradle nor ArchUnit can see.
    --
    -- No ON DELETE clause, deliberately. A party is never deleted, so a cascade would be a rule
    -- for a case that must not happen; leaving the default RESTRICT means an attempt fails loudly.
    party_id          uuid        NOT NULL REFERENCES party.party (id),

    status            text        NOT NULL,
    opened_at         timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,

    CONSTRAINT customer_status_is_known
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),

    -- A status change is never earlier than the opening. Cheap, and it catches a clock or a mapping
    -- defect at the moment it would otherwise be written down permanently.
    CONSTRAINT customer_status_change_is_not_before_opening
        CHECK (status_changed_at >= opened_at)
);

-- AT MOST ONE LIVE RELATIONSHIP PER PARTY, and closed ones unrestricted.
--
-- A partial unique index rather than a plain one, because the rule is about live relationships:
-- a party who closed a relationship and opened a new one has two rows and must be allowed to, while
-- a party with two simultaneous open relationships is a duplicate customer - the defect that makes
-- a retried registration expensive (P1-TSK-006).
--
-- This is the invariant the aggregate cannot enforce on its own: it is a rule ACROSS aggregates of
-- the same type, so only the database can arbitrate it between two concurrent transactions.
CREATE UNIQUE INDEX customer_one_live_relationship_per_party
    ON party.customer (party_id)
    WHERE status <> 'CLOSED';

-- Serves "the customers of this party", including closed ones, which the partial index above
-- cannot answer.
CREATE INDEX customer_by_party ON party.customer (party_id);

COMMENT ON TABLE party.customer IS
    'A relationship a party holds toward the platform. PENDING -> ACTIVE -> SUSPENDED <-> ACTIVE, '
    'and -> CLOSED which is terminal (INV-LIFE-04). Reopening is a new row, never a transition out '
    'of CLOSED: reports issued while it was closed must stay reproducible.';

-- The application role gets the DML these tables genuinely require and nothing more (V008's
-- principle). UPDATE is required on customer: a status legitimately changes, and unlike the audit
-- trail this is not history - the row records a current relationship, and its changes are recorded
-- as audit records rather than as versions of this row.
--
-- No UPDATE on party, because nothing about a party changes yet: the display name is set at
-- registration and profile editing is a later task, which will add the grant it needs in the
-- migration that adds the capability. Granting it now "for when it is needed" is precisely how a
-- privilege model stops meaning anything.
--
-- No DELETE on either. A party is never deleted, and a relationship ends by becoming CLOSED.
-- Withholding it is free here, which is exactly when least privilege is easiest to establish and
-- hardest to argue against later.
GRANT SELECT, INSERT ON party.party TO finapp_app;
GRANT SELECT, INSERT, UPDATE ON party.customer TO finapp_app;
