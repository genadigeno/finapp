-- P9-TSK-015 (ADR-0080 section 4, D26, O7; INV-AUD-04, INV-HIST-04, INV-MON-03): the corridor
-- policy and corridor availability - the crossborder module's first tables.
--
-- THE CORRIDOR POLICY is the data every cross-border offer will freeze and every completion post
-- (P9-TSK-018/-019): per corridor (source currency S, destination currency D, destination country)
-- the candidate rails in policy order, the transfer fee - a fixed amount in S plus a margin, with a
-- named rounding - the maximum per payment in D, the screening validity, the data the corridor
-- requires and the delivery estimate. It changes only forward and only under four eyes, on the
-- pricing policy's shape (fx V004) - with NO SEED EXEMPTION: no version is seeded here; v1 (O7) is
-- proposed and activated by two FX_CONTROLLERs holding CROSSBORDER_ADMINISTER (D26).
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal)--> REJECTED
--
-- Held for EVERY writer: the edge trigger and frozen identity; content frozen from PROPOSED (a
-- corridor row is insertable only while its version is PROPOSED, never updated or deleted); one
-- ACTIVE and one PROPOSED version (partial uniques); activator <> proposer by CHECK, with no seed
-- exemption; a retirement committed only beside its successor (a deferred constraint trigger);
-- S <> D; a fee never negative and below one; a positive maximum; rounding and required-data names
-- from their enums (CrossborderMigrationTest reconciles the CHECK lists). That every candidate rail
-- is declared by the build and covers (country, D), and that the required data is satisfiable, are
-- the domain's judgements at proposal AND approval (the build is not a database fact).
--
-- AVAILABILITY is an append-only FACT per change, keyed by the corridor's stable code S-D-CC across
-- versions, not a machine. Disabling takes one person with a reason, at once; enabling goes through
-- corridor_enable_request (PROPOSED -> APPROVED | REJECTED, four-eyes, one live proposal per
-- corridor), the enabling fact appended in the approval's transaction and refused without an
-- APPROVED request naming its corridor. Writers of one corridor serialise on advisory namespace 8
-- (reserved in DISTRIBUTED_EXECUTION.md); a corridor with no fact is available.

-- ------------------------------------------------------------------ the corridor policy

CREATE TABLE crossborder.corridor_policy_version (
    id               UUID        NOT NULL,
    version          INTEGER     NOT NULL,
    status           TEXT        NOT NULL,
    proposed_by      TEXT        NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL,
    proposal_reason  TEXT        NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    retired_at       TIMESTAMPTZ,
    CONSTRAINT corridor_policy_version_pk PRIMARY KEY (id),
    CONSTRAINT corridor_policy_version_number_unique UNIQUE (version),
    CONSTRAINT corridor_policy_version_number_positive CHECK (version >= 1),
    CONSTRAINT corridor_policy_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT corridor_policy_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    -- A decision exists exactly when the version has left PROPOSED.
    CONSTRAINT corridor_policy_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT corridor_policy_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, with no seed exemption: an ACTIVE or RETIRED version was activated by someone
    -- other than its proposer (a REJECTED one may be the proposer's withdrawal).
    CONSTRAINT corridor_policy_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT corridor_policy_retirement_coherent CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

COMMENT ON TABLE crossborder.corridor_policy_version IS
    'The corridor policy (P9-TSK-015, ADR-0080 section 4): versioned, four-eyes, no seed. One ACTIVE and one PROPOSED version; activator <> proposer by CHECK; content frozen from PROPOSED; a retirement only beside its successor. Every offer pins the version that priced it (INV-HIST-04).';

CREATE UNIQUE INDEX corridor_policy_one_active ON crossborder.corridor_policy_version (status) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX corridor_policy_one_proposed ON crossborder.corridor_policy_version (status) WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION crossborder.corridor_policy_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a corridor policy version is never deleted (P9-TSK-015)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a corridor policy version''s identity and content are frozen (P9-TSK-015)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a corridor policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P9-TSK-015)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'ACTIVE' AND (NEW.decided_by IS DISTINCT FROM OLD.decided_by
            OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
            OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason) THEN
        RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its instant (P9-TSK-015)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER corridor_policy_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON crossborder.corridor_policy_version
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_policy_permits_only_machine_edges();

-- A retirement commits only beside its successor: at COMMIT, a later version must be ACTIVE.
CREATE OR REPLACE FUNCTION crossborder.corridor_policy_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM crossborder.corridor_policy_version successor
             WHERE successor.status = 'ACTIVE' AND successor.version > NEW.version) THEN
        RAISE EXCEPTION 'corridor policy version % retired with no later ACTIVE successor: a retirement commits only beside its successor (P9-TSK-015)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER corridor_policy_retires_only_beside_its_successor
    AFTER UPDATE ON crossborder.corridor_policy_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_policy_retires_only_beside_its_successor();

CREATE TABLE crossborder.corridor_policy_event (
    id            UUID        NOT NULL,
    policy_id     UUID        NOT NULL,
    from_status   TEXT,
    to_status     TEXT        NOT NULL,
    actor_id      TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT corridor_policy_event_pk PRIMARY KEY (id),
    CONSTRAINT corridor_policy_event_policy_fk FOREIGN KEY (policy_id) REFERENCES crossborder.corridor_policy_version (id),
    CONSTRAINT corridor_policy_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('PROPOSED', 'ACTIVE'))
        AND to_status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT corridor_policy_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX corridor_policy_event_by_policy ON crossborder.corridor_policy_event (policy_id, occurred_at);

CREATE OR REPLACE FUNCTION crossborder.corridor_policy_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a corridor policy event is append-only (P9-TSK-015)';
END;
$$;

CREATE TRIGGER corridor_policy_event_is_append_only
    BEFORE UPDATE OR DELETE ON crossborder.corridor_policy_event
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_policy_event_is_append_only();

CREATE TABLE crossborder.corridor (
    policy_id                 UUID          NOT NULL,
    source_currency           CHAR(3)       NOT NULL,
    destination_currency      CHAR(3)       NOT NULL,
    destination_country       CHAR(2)       NOT NULL,
    rails                     TEXT[]        NOT NULL,
    fee_fixed_minor           BIGINT        NOT NULL,
    fee_margin                NUMERIC(8,6)  NOT NULL,
    fee_rounding              TEXT          NOT NULL,
    maximum_minor             BIGINT        NOT NULL,
    screening_validity_hours  INTEGER       NOT NULL,
    delivery_estimate_hours   INTEGER       NOT NULL,
    required_data             TEXT[]        NOT NULL,
    CONSTRAINT corridor_pk PRIMARY KEY (policy_id, source_currency, destination_currency, destination_country),
    CONSTRAINT corridor_policy_fk FOREIGN KEY (policy_id) REFERENCES crossborder.corridor_policy_version (id),
    CONSTRAINT corridor_currency_shape CHECK (source_currency ~ '^[A-Z]{3}$' AND destination_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT corridor_country_shape CHECK (destination_country ~ '^[A-Z]{2}$'),
    -- A corridor converts: the customer pays in S and the beneficiary receives D.
    CONSTRAINT corridor_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT corridor_names_a_rail CHECK (cardinality(rails) BETWEEN 1 AND 8 AND array_position(rails, NULL) IS NULL),
    CONSTRAINT corridor_fee_fixed_non_negative CHECK (fee_fixed_minor >= 0),
    CONSTRAINT corridor_fee_margin_bounded CHECK (fee_margin >= 0 AND fee_margin < 1),
    -- RoundingPolicy's names, generated from the enum (INV-MON-03: no rounding is unnamed).
    CONSTRAINT corridor_fee_rounding_is_named CHECK (fee_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    CONSTRAINT corridor_maximum_positive CHECK (maximum_minor > 0),
    CONSTRAINT corridor_screening_validity_bounded CHECK (screening_validity_hours BETWEEN 1 AND 720),
    CONSTRAINT corridor_delivery_estimate_bounded CHECK (delivery_estimate_hours BETWEEN 1 AND 720),
    -- RequiredData's names, generated from the enum.
    CONSTRAINT corridor_required_data_is_known CHECK (
        array_position(required_data, NULL) IS NULL
        AND required_data <@ ARRAY['BENEFICIARY_NAME', 'ENTITY_TYPE', 'BENEFICIARY_ADDRESS', 'PAYMENT_PURPOSE']::text[])
);

COMMENT ON TABLE crossborder.corridor IS
    'One corridor (S, D, destination country) of a corridor policy version (P9-TSK-015): candidate rails in policy order, the transfer fee as a fixed amount in S''s minor units plus a margin with a named rounding, the maximum per payment in D''s minor units, the screening validity, the delivery estimate and the required data. Insertable only while its version is PROPOSED; never updated or deleted.';

CREATE OR REPLACE FUNCTION crossborder.corridor_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM crossborder.corridor_policy_version v
                        WHERE v.id = NEW.policy_id AND v.status = 'PROPOSED') THEN
            RAISE EXCEPTION 'a corridor joins only a PROPOSED version: content is frozen from proposal (P9-TSK-015)';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a corridor is frozen: new terms are a new version (P9-TSK-015)';
END;
$$;

CREATE TRIGGER corridor_is_frozen
    BEFORE INSERT OR UPDATE OR DELETE ON crossborder.corridor
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_is_frozen();

-- ------------------------------------------------------------------ availability

CREATE TABLE crossborder.corridor_enable_request (
    id               UUID        NOT NULL,
    corridor         TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    proposed_by      TEXT        NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL,
    proposal_reason  TEXT        NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    CONSTRAINT corridor_enable_request_pk PRIMARY KEY (id),
    CONSTRAINT corridor_enable_request_corridor_shape CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT corridor_enable_request_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT corridor_enable_request_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT corridor_enable_request_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT corridor_enable_request_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES: an enabling is approved by someone other than its proposer.
    CONSTRAINT corridor_enable_request_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)
);

CREATE UNIQUE INDEX corridor_enable_request_one_live ON crossborder.corridor_enable_request (corridor) WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION crossborder.corridor_enable_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a corridor enable request is never deleted (P9-TSK-015)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.corridor IS DISTINCT FROM OLD.corridor
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a corridor enable request''s identity is frozen (P9-TSK-015)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a corridor enable request moves PROPOSED -> APPROVED | REJECTED, once; % -> % is not an edge (P9-TSK-015)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER corridor_enable_request_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON crossborder.corridor_enable_request
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_enable_request_permits_only_machine_edges();

CREATE TABLE crossborder.corridor_availability (
    seq                 BIGINT      GENERATED ALWAYS AS IDENTITY,
    id                  UUID        NOT NULL,
    corridor            TEXT        NOT NULL,
    available           BOOLEAN     NOT NULL,
    actor_id            TEXT        NOT NULL,
    reason              TEXT        NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    enable_request_id   UUID,
    CONSTRAINT corridor_availability_pk PRIMARY KEY (id),
    CONSTRAINT corridor_availability_seq_unique UNIQUE (seq),
    CONSTRAINT corridor_availability_corridor_shape CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT corridor_availability_reasoned CHECK (char_length(reason) BETWEEN 1 AND 1000),
    -- Enabling only through an approved request; disabling never names one.
    CONSTRAINT corridor_availability_enabling_is_approved CHECK (available = (enable_request_id IS NOT NULL)),
    CONSTRAINT corridor_availability_request_fk FOREIGN KEY (enable_request_id) REFERENCES crossborder.corridor_enable_request (id)
);

CREATE INDEX corridor_availability_latest ON crossborder.corridor_availability (corridor, seq DESC);

COMMENT ON TABLE crossborder.corridor_availability IS
    'Append-only corridor availability facts (P9-TSK-015), keyed by the stable corridor code S-D-CC: a disable by one person with a reason, an enable only beside an APPROVED enable request naming the corridor. The newest fact (seq) is the corridor''s availability; none means available.';

-- An enabling fact names an APPROVED request for exactly its corridor; facts are append-only.
CREATE OR REPLACE FUNCTION crossborder.corridor_availability_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a corridor availability fact is append-only: a change is a new fact (P9-TSK-015)';
    END IF;
    IF NEW.enable_request_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM crossborder.corridor_enable_request r
             WHERE r.id = NEW.enable_request_id AND r.status = 'APPROVED' AND r.corridor = NEW.corridor) THEN
        RAISE EXCEPTION 'an enabling fact names an APPROVED enable request for its own corridor (P9-TSK-015, INV-AUD-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER corridor_availability_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON crossborder.corridor_availability
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.corridor_availability_is_append_only();

-- =============================================================================================
-- Person-written prose holds no instrument shape, for every writer (INV-AUD-02; fx V004,
-- settlement V012 and reconciliation V019 its precedents).
--   Every reason above is a controller's free prose, kept in tables that can never be cleaned.
--   The domain screens each one first (CrossborderReasons.refuse over InstrumentShapes); this rank
--   refuses a writer that bypassed it. The twin below is statement for statement fx V004's - itself
--   settlement V012's and reconciliation V019's - each masking the platform's own UUIDs first; the
--   parity is proven over a corpus by CrossborderReasonScreenDatabaseTest. A NULL decision reason
--   (a pending row) passes: the function is STRICT and a CHECK admits NULL.
-- =============================================================================================
-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION crossborder.luhn_valid(digits TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    width INT := char_length(digits);
    total INT := 0;
    digit INT;
    i     INT;
BEGIN
    FOR i IN 1..width LOOP
        digit := ascii(substr(digits, width - i + 1, 1)) - 48;
        IF i % 2 = 0 THEN
            digit := digit * 2;
            IF digit > 9 THEN
                digit := digit - 9;
            END IF;
        END IF;
        total := total + digit;
    END LOOP;
    RETURN total % 10 = 0;
END;
$$;

-- ISO 13616: the opening four moved to the end, letters read as 10..35, the remainder mod 97 is 1.
CREATE OR REPLACE FUNCTION crossborder.mod97_check_holds(compact TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    rearranged TEXT := upper(substr(compact, 5) || substr(compact, 1, 4));
    remainder  INT := 0;
    code       INT;
    i          INT;
BEGIN
    FOR i IN 1..char_length(rearranged) LOOP
        code := ascii(substr(rearranged, i, 1));
        IF code BETWEEN 48 AND 57 THEN
            remainder := (remainder * 10 + code - 48) % 97;
        ELSE
            remainder := (remainder * 100 + code - 55) % 97;
        END IF;
    END LOOP;
    RETURN remainder = 1;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The card number: InstrumentShapes.cardNumberIn.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION crossborder.holds_card_number_shape(body TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    masked   TEXT;
    run      TEXT;
    pieces   TEXT[];
    grp      TEXT;
    span     TEXT;
    len      INT;
    width    INT;
    start_at INT;
    first_at INT;
    last_at  INT;
BEGIN
    masked := regexp_replace(body,
        '(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15})(?![0-9A-Za-z])',
        '#', 'g');
    FOR run IN SELECT (regexp_matches(masked, '[0-9]+(?:[ -][0-9]+)*', 'g'))[1] LOOP
        pieces := regexp_split_to_array(run, '[ -]');
        -- Every 12..19-digit window of one contiguous group.
        FOREACH grp IN ARRAY pieces LOOP
            len := char_length(grp);
            width := 12;
            WHILE width <= 19 AND width <= len LOOP
                start_at := 1;
                WHILE start_at + width - 1 <= len LOOP
                    IF crossborder.luhn_valid(substr(grp, start_at, width)) THEN
                        RETURN true;
                    END IF;
                    start_at := start_at + 1;
                END LOOP;
                width := width + 1;
            END LOOP;
        END LOOP;
        -- Every span of two or more whole pieces, 12..19 digits once joined.
        FOR first_at IN 1..array_length(pieces, 1) LOOP
            span := pieces[first_at];
            FOR last_at IN first_at + 1..array_length(pieces, 1) LOOP
                span := span || pieces[last_at];
                EXIT WHEN char_length(span) > 19;
                IF char_length(span) >= 12 AND crossborder.luhn_valid(span) THEN
                    RETURN true;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The account identifier: InstrumentShapes.accountIdentifierAt.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION crossborder.holds_account_identifier_shape(body TEXT)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    masked   TEXT;
    run      TEXT;
    tokens   TEXT[];
    compact  TEXT;
    grp      TEXT;
    total    INT;
    first_at INT;
    next_at  INT;
BEGIN
    masked := regexp_replace(body,
        '(?<![0-9A-Za-z])(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9a-f]{12}7[0-9a-f]{3}[89ab][0-9a-f]{15})(?![0-9A-Za-z])',
        '#', 'g');
    FOR run IN SELECT (regexp_matches(masked, '[A-Za-z0-9]+(?:[ -][A-Za-z0-9]+)*', 'g'))[1] LOOP
        tokens := regexp_split_to_array(run, '[ -]');
        total := array_length(tokens, 1);
        FOR first_at IN 1..total LOOP
            IF tokens[first_at] ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{11,30}$' THEN
                RETURN true;
            END IF;
            IF tokens[first_at] ~ '^[A-Za-z]{2}[0-9]{2}$' THEN
                compact := tokens[first_at];
                FOR next_at IN first_at + 1..total LOOP
                    grp := tokens[next_at];
                    EXIT WHEN char_length(grp) > 4;
                    compact := compact || grp;
                    EXIT WHEN char_length(compact) > 34;
                    IF char_length(compact) >= 15
                            AND crossborder.mod97_check_holds(compact) THEN
                        RETURN true;
                    END IF;
                    EXIT WHEN char_length(grp) < 4;
                END LOOP;
            END IF;
        END LOOP;
    END LOOP;
    RETURN false;
END;
$$;

CREATE OR REPLACE FUNCTION crossborder.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT crossborder.holds_card_number_shape(body)
        OR crossborder.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The reason columns.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE crossborder.corridor_policy_version
    ADD CONSTRAINT corridor_policy_version_proposal_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(proposal_reason));

ALTER TABLE crossborder.corridor_policy_version
    ADD CONSTRAINT corridor_policy_version_decision_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(decision_reason));

ALTER TABLE crossborder.corridor_policy_event
    ADD CONSTRAINT corridor_policy_event_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(reason));

ALTER TABLE crossborder.corridor_enable_request
    ADD CONSTRAINT corridor_enable_request_proposal_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(proposal_reason));

ALTER TABLE crossborder.corridor_enable_request
    ADD CONSTRAINT corridor_enable_request_decision_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(decision_reason));

ALTER TABLE crossborder.corridor_availability
    ADD CONSTRAINT corridor_availability_reason_no_instrument_shape CHECK (
        NOT crossborder.holds_instrument_shape(reason));

GRANT SELECT, INSERT ON crossborder.corridor_policy_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason, retired_at) ON crossborder.corridor_policy_version TO finapp_app;
GRANT SELECT, INSERT ON crossborder.corridor_policy_event TO finapp_app;
GRANT SELECT, INSERT ON crossborder.corridor TO finapp_app;
GRANT SELECT, INSERT ON crossborder.corridor_enable_request TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason) ON crossborder.corridor_enable_request TO finapp_app;
GRANT SELECT, INSERT ON crossborder.corridor_availability TO finapp_app;
