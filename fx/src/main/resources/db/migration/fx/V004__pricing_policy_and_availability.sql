-- P9-TSK-007 (ADR-0075 section 7, D26, O7; INV-AUD-04, INV-HIST-04, INV-MON-03): the pricing
-- policy and FX availability.
--
-- THE PRICING POLICY is the data every quote freezes and later posts: per (pair, purpose) the
-- spread and markup (the platform's margin), the rate scale, the three rounding names, the quote
-- window, the cover margin, the plausibility band, the reference maximum age and the fixed leg's
-- notional bounds; per version, the open-quote cap. It changes only forward and only under four
-- eyes, on reconciliation's rule-set precedent - and with NO SEED EXEMPTION: no version is seeded
-- here; v1 is proposed and activated by two FX_CONTROLLERs (D26), in fixtures and the runbook.
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal)--> REJECTED
--
-- Held for EVERY writer: the edge trigger and frozen identity; content frozen from PROPOSED (a
-- pair row is insertable only while its version is PROPOSED, and never updated or deleted); one
-- ACTIVE and one PROPOSED version (partial uniques); activator <> proposer by CHECK, with no
-- seed exemption; a retirement committed only beside its successor (a deferred constraint
-- trigger); spread + markup > 0; a rate scale 0..10; rounding names from RoundingPolicy's own
-- list (PricingPolicyMigrationTest reconciles the CHECK lists against the enum).
--
-- AVAILABILITY - pair and provider - is an append-only FACT per change, not a machine. Disabling
-- takes one person with a reason, at once, because making money stop moving must never wait for a
-- second person; enabling goes through availability_enable_request (PROPOSED -> APPROVED |
-- REJECTED, four-eyes, one live proposal per subject), the enabling fact appended in the
-- approval's transaction and refused without an APPROVED request naming its subject. Writers of
-- one subject serialise on advisory namespace 7 (reserved in DISTRIBUTED_EXECUTION.md); a
-- subject with no fact is available.

-- ------------------------------------------------------------------ the pricing policy

CREATE TABLE fx.pricing_policy_version (
    id               UUID        NOT NULL,
    version          INTEGER     NOT NULL,
    status           TEXT        NOT NULL,
    open_quote_cap   INTEGER     NOT NULL,
    proposed_by      TEXT        NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL,
    proposal_reason  TEXT        NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    retired_at       TIMESTAMPTZ,
    CONSTRAINT pricing_policy_version_pk PRIMARY KEY (id),
    CONSTRAINT pricing_policy_version_number_unique UNIQUE (version),
    CONSTRAINT pricing_policy_version_number_positive CHECK (version >= 1),
    CONSTRAINT pricing_policy_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT pricing_policy_open_quote_cap_bounded CHECK (open_quote_cap BETWEEN 1 AND 100),
    CONSTRAINT pricing_policy_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    -- A decision exists exactly when the version has left PROPOSED.
    CONSTRAINT pricing_policy_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT pricing_policy_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, with no seed exemption: an ACTIVE or RETIRED version was activated by someone
    -- other than its proposer (a REJECTED one may be the proposer's withdrawal).
    CONSTRAINT pricing_policy_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT pricing_policy_retirement_coherent CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

COMMENT ON TABLE fx.pricing_policy_version IS
    'The FX pricing policy (P9-TSK-007, ADR-0075 section 7): versioned, four-eyes, no seed. One ACTIVE and one PROPOSED version; activator <> proposer by CHECK; content frozen from PROPOSED; a retirement only beside its successor. Every quote pins the version that priced it (INV-HIST-04).';

CREATE UNIQUE INDEX pricing_policy_one_active ON fx.pricing_policy_version (status) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX pricing_policy_one_proposed ON fx.pricing_policy_version (status) WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION fx.pricing_policy_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a pricing policy version is never deleted (P9-TSK-007)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.open_quote_cap IS DISTINCT FROM OLD.open_quote_cap
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a pricing policy version''s identity and content are frozen (P9-TSK-007)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a pricing policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P9-TSK-007)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'ACTIVE' AND (NEW.decided_by IS DISTINCT FROM OLD.decided_by
            OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
            OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason) THEN
        RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its instant (P9-TSK-007)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pricing_policy_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON fx.pricing_policy_version
    FOR EACH ROW
    EXECUTE FUNCTION fx.pricing_policy_permits_only_machine_edges();

-- A retirement commits only beside its successor: at COMMIT, a later version must be ACTIVE.
-- Deferred so the activation's own transaction (retire first, then activate - the one-ACTIVE
-- index admits one row at every statement) satisfies it; a lone retirement never does.
CREATE OR REPLACE FUNCTION fx.pricing_policy_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM fx.pricing_policy_version successor
             WHERE successor.status = 'ACTIVE' AND successor.version > NEW.version) THEN
        RAISE EXCEPTION 'pricing policy version % retired with no later ACTIVE successor: a retirement commits only beside its successor (P9-TSK-007)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER pricing_policy_retires_only_beside_its_successor
    AFTER UPDATE ON fx.pricing_policy_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION fx.pricing_policy_retires_only_beside_its_successor();

CREATE TABLE fx.pricing_policy_event (
    id            UUID        NOT NULL,
    policy_id     UUID        NOT NULL,
    from_status   TEXT,
    to_status     TEXT        NOT NULL,
    actor_id      TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pricing_policy_event_pk PRIMARY KEY (id),
    CONSTRAINT pricing_policy_event_policy_fk FOREIGN KEY (policy_id) REFERENCES fx.pricing_policy_version (id),
    CONSTRAINT pricing_policy_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('PROPOSED', 'ACTIVE'))
        AND to_status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT pricing_policy_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX pricing_policy_event_by_policy ON fx.pricing_policy_event (policy_id, occurred_at);

CREATE TABLE fx.pricing_pair (
    policy_id                  UUID          NOT NULL,
    source_currency            CHAR(3)       NOT NULL,
    destination_currency       CHAR(3)       NOT NULL,
    purpose                    TEXT          NOT NULL,
    providers                  TEXT[]        NOT NULL,
    spread                     NUMERIC(8,6)  NOT NULL,
    markup                     NUMERIC(8,6)  NOT NULL,
    rate_scale                 INTEGER       NOT NULL,
    rate_rounding              TEXT          NOT NULL,
    amount_rounding            TEXT          NOT NULL,
    margin_rounding            TEXT          NOT NULL,
    window_seconds             INTEGER       NOT NULL,
    cover_margin_seconds       INTEGER       NOT NULL,
    band                       NUMERIC(8,6)  NOT NULL,
    reference_max_age_seconds  INTEGER       NOT NULL,
    source_min_minor           BIGINT        NOT NULL,
    source_max_minor           BIGINT        NOT NULL,
    destination_min_minor      BIGINT        NOT NULL,
    destination_max_minor      BIGINT        NOT NULL,
    CONSTRAINT pricing_pair_pk PRIMARY KEY (policy_id, source_currency, destination_currency, purpose),
    CONSTRAINT pricing_pair_policy_fk FOREIGN KEY (policy_id) REFERENCES fx.pricing_policy_version (id),
    CONSTRAINT pricing_pair_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT pricing_pair_purpose_is_known CHECK (purpose IN ('CONVERSION', 'CROSS_BORDER')),
    CONSTRAINT pricing_pair_names_a_provider CHECK (cardinality(providers) BETWEEN 1 AND 8 AND array_position(providers, NULL) IS NULL),
    -- The margin: never negative, and a pair is never priced at zero (D26, O7).
    CONSTRAINT pricing_pair_margin_parts_non_negative CHECK (spread >= 0 AND markup >= 0),
    CONSTRAINT pricing_pair_margin_is_positive CHECK (spread + markup > 0),
    CONSTRAINT pricing_pair_margin_below_one CHECK (spread + markup < 1),
    CONSTRAINT pricing_pair_rate_scale_bounded CHECK (rate_scale BETWEEN 0 AND 10),
    -- RoundingPolicy's names, generated from the enum (INV-MON-03: no rounding is unnamed).
    CONSTRAINT pricing_pair_rate_rounding_is_named CHECK (rate_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    CONSTRAINT pricing_pair_amount_rounding_is_named CHECK (amount_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    CONSTRAINT pricing_pair_margin_rounding_is_named CHECK (margin_rounding IN ('HALF_EVEN', 'HALF_UP', 'TOWARDS_ZERO', 'AWAY_FROM_ZERO', 'FLOOR', 'CEILING')),
    -- A quote with under 5 s left is never issued, so a window shorter than that is no window.
    CONSTRAINT pricing_pair_window_bounded CHECK (window_seconds BETWEEN 5 AND 3600),
    CONSTRAINT pricing_pair_cover_margin_bounded CHECK (cover_margin_seconds BETWEEN 0 AND 600),
    CONSTRAINT pricing_pair_band_bounded CHECK (band > 0 AND band < 1),
    CONSTRAINT pricing_pair_reference_age_bounded CHECK (reference_max_age_seconds BETWEEN 1 AND 86400),
    CONSTRAINT pricing_pair_source_bounds_ordered CHECK (source_min_minor > 0 AND source_min_minor <= source_max_minor),
    CONSTRAINT pricing_pair_destination_bounds_ordered CHECK (destination_min_minor > 0 AND destination_min_minor <= destination_max_minor)
);

COMMENT ON TABLE fx.pricing_pair IS
    'One (pair, purpose) of a pricing policy version (P9-TSK-007): providers in sourcing order, spread and markup (spread + markup > 0), rate scale 0..10, three named roundings, window, cover margin, band, reference maximum age and the fixed leg''s notional bounds in minor units of each side. Insertable only while its version is PROPOSED; never updated or deleted.';

CREATE OR REPLACE FUNCTION fx.pricing_pair_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM fx.pricing_policy_version v
                        WHERE v.id = NEW.policy_id AND v.status = 'PROPOSED') THEN
            RAISE EXCEPTION 'a pricing pair joins only a PROPOSED version: content is frozen from proposal (P9-TSK-007)';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a pricing pair is frozen: a new price is a new version (P9-TSK-007)';
END;
$$;

CREATE TRIGGER pricing_pair_is_frozen
    BEFORE INSERT OR UPDATE OR DELETE ON fx.pricing_pair
    FOR EACH ROW
    EXECUTE FUNCTION fx.pricing_pair_is_frozen();

-- ------------------------------------------------------------------ availability

CREATE TABLE fx.availability_enable_request (
    id               UUID        NOT NULL,
    subject_kind     TEXT        NOT NULL,
    subject          TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    proposed_by      TEXT        NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL,
    proposal_reason  TEXT        NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    CONSTRAINT availability_enable_request_pk PRIMARY KEY (id),
    CONSTRAINT availability_enable_request_kind_is_known CHECK (subject_kind IN ('PAIR', 'PROVIDER')),
    CONSTRAINT availability_enable_request_subject_shape CHECK (
        (subject_kind = 'PAIR' AND subject ~ '^[A-Z]{3}-[A-Z]{3}$')
        OR (subject_kind = 'PROVIDER' AND subject ~ '^[a-z][a-z0-9-]{0,31}$')),
    CONSTRAINT availability_enable_request_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT availability_enable_request_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT availability_enable_request_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT availability_enable_request_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES: an enabling is approved by someone other than its proposer.
    CONSTRAINT availability_enable_request_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)
);

CREATE UNIQUE INDEX availability_enable_request_one_live ON fx.availability_enable_request (subject_kind, subject) WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION fx.availability_enable_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an enable request is never deleted (P9-TSK-007)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind
            OR NEW.subject IS DISTINCT FROM OLD.subject
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'an enable request''s identity is frozen (P9-TSK-007)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'an enable request moves PROPOSED -> APPROVED | REJECTED, once; % -> % is not an edge (P9-TSK-007)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER availability_enable_request_permits_only_machine_edges
    BEFORE UPDATE OR DELETE ON fx.availability_enable_request
    FOR EACH ROW
    EXECUTE FUNCTION fx.availability_enable_request_permits_only_machine_edges();

CREATE TABLE fx.pair_availability (
    seq                 BIGINT      GENERATED ALWAYS AS IDENTITY,
    id                  UUID        NOT NULL,
    source_currency     CHAR(3)     NOT NULL,
    destination_currency CHAR(3)    NOT NULL,
    available           BOOLEAN     NOT NULL,
    actor_id            TEXT        NOT NULL,
    reason              TEXT        NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    enable_request_id   UUID,
    CONSTRAINT pair_availability_pk PRIMARY KEY (id),
    CONSTRAINT pair_availability_seq_unique UNIQUE (seq),
    CONSTRAINT pair_availability_converts CHECK (source_currency <> destination_currency),
    CONSTRAINT pair_availability_reasoned CHECK (char_length(reason) BETWEEN 1 AND 1000),
    -- Enabling only through an approved request; disabling never names one.
    CONSTRAINT pair_availability_enabling_is_approved CHECK (available = (enable_request_id IS NOT NULL)),
    CONSTRAINT pair_availability_request_fk FOREIGN KEY (enable_request_id) REFERENCES fx.availability_enable_request (id)
);

CREATE INDEX pair_availability_latest ON fx.pair_availability (source_currency, destination_currency, seq DESC);

CREATE TABLE fx.provider_availability (
    seq                 BIGINT      GENERATED ALWAYS AS IDENTITY,
    id                  UUID        NOT NULL,
    provider_code       TEXT        NOT NULL,
    available           BOOLEAN     NOT NULL,
    actor_id            TEXT        NOT NULL,
    reason              TEXT        NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    enable_request_id   UUID,
    CONSTRAINT provider_availability_pk PRIMARY KEY (id),
    CONSTRAINT provider_availability_seq_unique UNIQUE (seq),
    CONSTRAINT provider_availability_code_shape CHECK (provider_code ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT provider_availability_reasoned CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT provider_availability_enabling_is_approved CHECK (available = (enable_request_id IS NOT NULL)),
    CONSTRAINT provider_availability_request_fk FOREIGN KEY (enable_request_id) REFERENCES fx.availability_enable_request (id)
);

CREATE INDEX provider_availability_latest ON fx.provider_availability (provider_code, seq DESC);

COMMENT ON TABLE fx.pair_availability IS
    'Append-only pair availability facts (P9-TSK-007): a disable by one person with a reason, an enable only beside an APPROVED enable request naming the pair. The newest fact (seq) is the pair''s availability; none means available.';
COMMENT ON TABLE fx.provider_availability IS
    'Append-only provider availability facts (P9-TSK-007): the pair table''s rules for a declared provider code.';

-- An enabling fact names an APPROVED request for exactly its subject; facts are append-only.
CREATE OR REPLACE FUNCTION fx.availability_fact_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    expected_kind TEXT;
    expected_subject TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an availability fact is append-only: a change is a new fact (P9-TSK-007)';
    END IF;
    IF NEW.enable_request_id IS NOT NULL THEN
        IF TG_TABLE_NAME = 'pair_availability' THEN
            expected_kind := 'PAIR';
            expected_subject := NEW.source_currency || '-' || NEW.destination_currency;
        ELSE
            expected_kind := 'PROVIDER';
            expected_subject := NEW.provider_code;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM fx.availability_enable_request r
                        WHERE r.id = NEW.enable_request_id AND r.status = 'APPROVED'
                          AND r.subject_kind = expected_kind AND r.subject = expected_subject) THEN
            RAISE EXCEPTION 'an enabling fact names an APPROVED enable request for its own subject (P9-TSK-007, INV-AUD-04)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pair_availability_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON fx.pair_availability
    FOR EACH ROW
    EXECUTE FUNCTION fx.availability_fact_is_append_only();

CREATE TRIGGER provider_availability_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON fx.provider_availability
    FOR EACH ROW
    EXECUTE FUNCTION fx.availability_fact_is_append_only();

CREATE OR REPLACE FUNCTION fx.pricing_policy_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a pricing policy event is append-only (P9-TSK-007)';
END;
$$;

CREATE TRIGGER pricing_policy_event_is_append_only
    BEFORE UPDATE OR DELETE ON fx.pricing_policy_event
    FOR EACH ROW
    EXECUTE FUNCTION fx.pricing_policy_event_is_append_only();

-- =============================================================================================
-- Person-written prose holds no instrument shape, for every writer (INV-AUD-02; the Phase 8 -> 9
-- transition's SEC-03/SEC-04 rule, settlement V012 and reconciliation V019 its precedents).
--   Every reason above is a controller's free prose, kept in tables that can never be cleaned.
--   The domain screens each one first (FxReasons.refuse over InstrumentShapes); this rank
--   refuses a writer that bypassed it. The twin below is statement for statement identical to
--   settlement V012's and reconciliation V019's, each masking the platform's own UUIDs first;
--   the parity of the two ranks is proven over a corpus by FxReasonScreenDatabaseTest. A NULL
--   decision reason (a pending row) passes: the function is STRICT and a CHECK admits NULL.
--   platform.audit_record.reason is platform's schema; every door screens before it writes there.
-- =============================================================================================
-- ---------------------------------------------------------------------------------------------
-- The checksums.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fx.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION fx.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION fx.holds_card_number_shape(body TEXT)
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
                    IF fx.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND fx.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION fx.holds_account_identifier_shape(body TEXT)
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
                            AND fx.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION fx.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT fx.holds_card_number_shape(body)
        OR fx.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The reason columns.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE fx.pricing_policy_version
    ADD CONSTRAINT pricing_policy_version_proposal_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(proposal_reason));

ALTER TABLE fx.pricing_policy_version
    ADD CONSTRAINT pricing_policy_version_decision_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(decision_reason));

ALTER TABLE fx.pricing_policy_event
    ADD CONSTRAINT pricing_policy_event_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(reason));

ALTER TABLE fx.availability_enable_request
    ADD CONSTRAINT availability_enable_request_proposal_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(proposal_reason));

ALTER TABLE fx.availability_enable_request
    ADD CONSTRAINT availability_enable_request_decision_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(decision_reason));

ALTER TABLE fx.pair_availability
    ADD CONSTRAINT pair_availability_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(reason));

ALTER TABLE fx.provider_availability
    ADD CONSTRAINT provider_availability_reason_no_instrument_shape CHECK (
        NOT fx.holds_instrument_shape(reason));

GRANT SELECT, INSERT ON fx.pricing_policy_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason, retired_at) ON fx.pricing_policy_version TO finapp_app;
GRANT SELECT, INSERT ON fx.pricing_policy_event TO finapp_app;
GRANT SELECT, INSERT ON fx.pricing_pair TO finapp_app;
GRANT SELECT, INSERT ON fx.availability_enable_request TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason) ON fx.availability_enable_request TO finapp_app;
GRANT SELECT, INSERT ON fx.pair_availability TO finapp_app;
GRANT SELECT, INSERT ON fx.provider_availability TO finapp_app;
