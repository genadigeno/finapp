-- =============================================================================================
-- P9-TSK-017 - the cross-border beneficiary and its corridor selection (ADR-0080 sections 3 and
-- 5a, ADR-0081; INV-XB-02, INV-RAIL-02, INV-RAIL-03, INV-KYC-05).
--
-- A beneficiary abroad is known by the corridor provider's opaque destination reference, a
-- four-character suffix, the provider's payee check (and the customer's acknowledgement when it
-- is not MATCH) and the provider-attested country, currency and entity type. No name and no
-- account identifier is stored here: the name goes to kyc for screening, encrypted there, and the
-- grant is exchanged and forgotten. The provider is selected at registration under the pinned
-- corridor policy version, every candidate rail judged and recorded, so the selection recomputes.
--
-- The machine (the lifecycle document 3.7):
--   PENDING_SCREENING -> ACTIVE | IN_REVIEW
--   IN_REVIEW         -> ACTIVE | BLOCKED
--   ACTIVE            -> IN_REVIEW                 (a re-screen hit or an unverified payee)
--   PENDING_SCREENING | IN_REVIEW | BLOCKED | ACTIVE -> REVOKED   (the customer)
-- REVOKED is terminal: a later screening outcome leaves it REVOKED.
-- =============================================================================================

CREATE TABLE crossborder.corridor_selection (
    id                   uuid        PRIMARY KEY,
    policy_id            uuid        NOT NULL REFERENCES crossborder.corridor_policy_version (id),
    destination_country  text        NOT NULL,
    destination_currency text        NOT NULL,
    entity_type          text        NOT NULL,
    -- The corridors observed available when the selection was judged - its availability input.
    available_corridors  text[]      NOT NULL,
    chosen_rail          text        NOT NULL,
    selected_at          timestamptz NOT NULL,
    CONSTRAINT corridor_selection_country_is_alpha2 CHECK (destination_country ~ '^[A-Z]{2}$'),
    CONSTRAINT corridor_selection_currency_is_alpha3 CHECK (destination_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT corridor_selection_entity_type_is_known CHECK (entity_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT corridor_selection_rail_is_shaped CHECK (chosen_rail ~ '^[a-z][a-z0-9-]{0,31}$')
);

CREATE TABLE crossborder.corridor_selection_step (
    selection_id uuid    NOT NULL REFERENCES crossborder.corridor_selection (id),
    ordinal      integer NOT NULL,
    corridor     text    NOT NULL,
    rail         text    NOT NULL,
    outcome      text    NOT NULL,
    PRIMARY KEY (selection_id, ordinal),
    CONSTRAINT corridor_selection_step_ordinal_is_positive CHECK (ordinal > 0),
    CONSTRAINT corridor_selection_step_corridor_is_coded CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT corridor_selection_step_rail_is_shaped CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT corridor_selection_step_outcome_is_known CHECK (outcome IN ('UNDECLARED_BY_BUILD', 'CURRENCY_UNSUPPORTED', 'NO_COVERAGE', 'UNAVAILABLE', 'CHOSEN'))
);

CREATE UNIQUE INDEX corridor_selection_one_chosen
    ON crossborder.corridor_selection_step (selection_id) WHERE outcome = 'CHOSEN';

COMMENT ON TABLE crossborder.corridor_selection IS
    'The provider selected at registration (P9-TSK-017, ADR-0080 5a): the pinned corridor policy '
    'version, the inputs (country, currency, entity type), the corridors observed available, and every '
    'candidate rail judged in policy order (corridor_selection_step) up to the CHOSEN one. Recomputing '
    'the pinned version over these inputs reproduces it (INV-RAIL-02). Append-only by grant.';

-- ---------------------------------------------------------------------------------------------
-- One registration per (owner, grant): the exchange reference is derived from both, so the same
-- grant presented again - an unacknowledged NO_MATCH resubmitted with the acknowledgement, or a
-- retried 503 - reuses the provider's deduped answer and converges here. The grant is never stored.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE crossborder.beneficiary_registration (
    id                   uuid        PRIMARY KEY,
    owner_party          uuid        NOT NULL,
    exchange_reference   text        NOT NULL,
    selection_id         uuid        NOT NULL REFERENCES crossborder.corridor_selection (id),
    rail                 text        NOT NULL,
    destination_country  text        NOT NULL,
    destination_currency text        NOT NULL,
    entity_type          text        NOT NULL,
    created_at           timestamptz NOT NULL,
    CONSTRAINT beneficiary_registration_one_per_grant UNIQUE (exchange_reference),
    CONSTRAINT beneficiary_registration_reference_is_shaped CHECK (exchange_reference ~ '^XBB[0-9a-f]{32}$'),
    CONSTRAINT beneficiary_registration_rail_is_shaped CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT beneficiary_registration_country_is_alpha2 CHECK (destination_country ~ '^[A-Z]{2}$'),
    CONSTRAINT beneficiary_registration_currency_is_alpha3 CHECK (destination_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT beneficiary_registration_entity_type_is_known CHECK (entity_type IN ('INDIVIDUAL', 'BUSINESS'))
);

CREATE TABLE crossborder.beneficiary (
    id                    uuid        PRIMARY KEY,
    owner_party           uuid        NOT NULL,
    registration_id       uuid        NOT NULL REFERENCES crossborder.beneficiary_registration (id),
    rail                  text        NOT NULL,
    destination_reference text        NOT NULL,
    suffix                text        NOT NULL,
    payee_check           text        NOT NULL,
    acknowledged_no_match boolean     NOT NULL,
    destination_country   text        NOT NULL,
    destination_currency  text        NOT NULL,
    entity_type           text        NOT NULL,
    nickname              text        NOT NULL,
    status                text        NOT NULL,
    screening_id          uuid        NOT NULL,
    registered_at         timestamptz NOT NULL,
    revoked_at            timestamptz,
    CONSTRAINT beneficiary_one_per_registration UNIQUE (registration_id),
    CONSTRAINT beneficiary_rail_is_shaped CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    -- INV-RAIL-03: the provider's opaque reference and a display suffix, never a bank identifier.
    CONSTRAINT beneficiary_destination_reference_is_opaque
        CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT beneficiary_suffix_is_four CHECK (suffix ~ '^[A-Za-z0-9]{4}$'),
    CONSTRAINT beneficiary_payee_check_is_known CHECK (payee_check IN ('MATCH', 'NO_MATCH', 'UNAVAILABLE')),
    -- A payee check that is not MATCH is registered only with the customer's acknowledgement.
    CONSTRAINT beneficiary_no_match_is_acknowledged CHECK (payee_check = 'MATCH' OR acknowledged_no_match),
    CONSTRAINT beneficiary_country_is_alpha2 CHECK (destination_country ~ '^[A-Z]{2}$'),
    CONSTRAINT beneficiary_currency_is_alpha3 CHECK (destination_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT beneficiary_entity_type_is_known CHECK (entity_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT beneficiary_nickname_is_bounded
        CHECK (char_length(nickname) BETWEEN 1 AND 40 AND nickname !~ '[[:cntrl:]]'),
    CONSTRAINT beneficiary_status_is_known CHECK (status IN ('PENDING_SCREENING', 'ACTIVE', 'IN_REVIEW', 'BLOCKED', 'REVOKED')),
    CONSTRAINT beneficiary_revoked_at_iff_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT beneficiary_revoked_after_registered CHECK (revoked_at IS NULL OR revoked_at >= registered_at)
);

CREATE INDEX beneficiary_by_owner ON crossborder.beneficiary (owner_party, registered_at DESC);
CREATE INDEX beneficiary_by_screening ON crossborder.beneficiary (screening_id);

COMMENT ON TABLE crossborder.beneficiary IS
    'A beneficiary abroad (P9-TSK-017, ADR-0080 3): known by the corridor provider''s opaque reference, '
    'a suffix, the payee check and attested attributes - never a name or an account identifier '
    '(INV-RAIL-03; the name is kyc''s, encrypted). PENDING_SCREENING -> ACTIVE | IN_REVIEW; IN_REVIEW -> '
    'ACTIVE | BLOCKED; ACTIVE -> IN_REVIEW; any non-terminal -> REVOKED (the customer). Payable only ACTIVE '
    'with a current clearance (INV-XB-02).';

-- The identity is frozen; the status moves only along the machine; REVOKED is final; nothing is
-- ever deleted.
CREATE OR REPLACE FUNCTION crossborder.beneficiary_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a beneficiary is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.owner_party IS DISTINCT FROM OLD.owner_party
            OR NEW.registration_id IS DISTINCT FROM OLD.registration_id
            OR NEW.rail IS DISTINCT FROM OLD.rail
            OR NEW.destination_reference IS DISTINCT FROM OLD.destination_reference
            OR NEW.suffix IS DISTINCT FROM OLD.suffix
            OR NEW.payee_check IS DISTINCT FROM OLD.payee_check
            OR NEW.acknowledged_no_match IS DISTINCT FROM OLD.acknowledged_no_match
            OR NEW.destination_country IS DISTINCT FROM OLD.destination_country
            OR NEW.destination_currency IS DISTINCT FROM OLD.destination_currency
            OR NEW.entity_type IS DISTINCT FROM OLD.entity_type
            OR NEW.nickname IS DISTINCT FROM OLD.nickname
            OR NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
        RAISE EXCEPTION 'a beneficiary''s identity is frozen';
    END IF;
    IF OLD.status = 'REVOKED' THEN
        RAISE EXCEPTION 'a revoked beneficiary is final';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
            (OLD.status = 'PENDING_SCREENING' AND NEW.status IN ('ACTIVE', 'IN_REVIEW', 'REVOKED'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('ACTIVE', 'BLOCKED', 'REVOKED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('IN_REVIEW', 'REVOKED'))
            OR (OLD.status = 'BLOCKED' AND NEW.status = 'REVOKED')) THEN
        RAISE EXCEPTION 'a beneficiary cannot move from % to %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER beneficiary_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON crossborder.beneficiary
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.beneficiary_permits_only_machine_edges();

-- The machine's history, append-only by grant: every edge, its cause and the screening behind it.
CREATE TABLE crossborder.beneficiary_status_event (
    id             uuid        PRIMARY KEY,
    beneficiary_id uuid        NOT NULL REFERENCES crossborder.beneficiary (id),
    from_status    text,
    to_status      text        NOT NULL,
    cause          text        NOT NULL,
    screening_id   uuid,
    occurred_at    timestamptz NOT NULL,
    CONSTRAINT beneficiary_status_event_from_is_known CHECK (from_status IN ('PENDING_SCREENING', 'ACTIVE', 'IN_REVIEW', 'BLOCKED', 'REVOKED')),
    CONSTRAINT beneficiary_status_event_to_is_known CHECK (to_status IN ('PENDING_SCREENING', 'ACTIVE', 'IN_REVIEW', 'BLOCKED', 'REVOKED')),
    CONSTRAINT beneficiary_status_event_cause_is_known CHECK (cause IN ('REGISTERED', 'SCREENING', 'CUSTOMER')),
    CONSTRAINT beneficiary_status_event_birth_has_no_origin CHECK ((cause = 'REGISTERED') = (from_status IS NULL))
);

CREATE INDEX beneficiary_status_event_by_beneficiary
    ON crossborder.beneficiary_status_event (beneficiary_id, occurred_at);

-- Customer-written prose holds no instrument shape, for every writer (INV-RAIL-03, INV-AUD-02):
-- V002's twin of InstrumentShapes, reused for the nickname and, as a second rank beside the shape
-- CHECK, for the provider's reference.
ALTER TABLE crossborder.beneficiary
    ADD CONSTRAINT beneficiary_nickname_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(nickname));

ALTER TABLE crossborder.beneficiary
    ADD CONSTRAINT beneficiary_destination_reference_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(destination_reference));

GRANT SELECT, INSERT ON crossborder.corridor_selection TO finapp_app;
GRANT SELECT, INSERT ON crossborder.corridor_selection_step TO finapp_app;
GRANT SELECT, INSERT ON crossborder.beneficiary_registration TO finapp_app;
GRANT SELECT, INSERT ON crossborder.beneficiary TO finapp_app;
GRANT UPDATE (status, screening_id, revoked_at) ON crossborder.beneficiary TO finapp_app;
GRANT SELECT, INSERT ON crossborder.beneficiary_status_event TO finapp_app;
