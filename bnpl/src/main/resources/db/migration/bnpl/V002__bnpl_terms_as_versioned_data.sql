-- BNPL product terms as versioned data, both products (P12-TSK-004; ADR-0102 decision 4, PHASE_12_PLAN.md sections 2.3,
-- 8 and 12.1-12.3, BNPL_LIFECYCLES.md section 4.1; INV-HIST-04, INV-AUD-04, INV-AUD-02, and INV-LND-05 / INV-LND-09 by
-- analogy). Lending V003's shape (P11-TSK-005) with the Phase 11 gate's repairs (lending V030) from the first row.
--
-- A TERMS VERSION is one BNPL product's plan shapes and servicing conventions as rows: pay-in-4's four instalments every
-- 14 days, the first at purchase, at a rate held to zero; pay-in-N's plans among 3, 6 and 12 months at a fixed nominal
-- annual rate, its first due date one month after the purchase (the first-period floor, A6), ACT/365F; the late fee
-- (amount, day, cap; A13), grace, the delinquency bucket bounds and the default threshold (A14), the allocation order and
-- the overpayment held as credit, auto- and partial collection and the retry cadence (A7), the rounding modes, the
-- servicing zone (A22), the offer validity cap (A17), the merchant's dispute response window (OQ-10) and the dispute
-- four-eyes threshold (ADR-0106 decision 5), the payoff horizon, the engine versions it is computed by and the agreement
-- template it is documented by. It changes only forward and only under four eyes, per product:
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal or a correction)--> REJECTED
--
-- WHY TWO TABLES FOR ONE VERSION (lending V003's reason). A terms version carries money - the late fee, its cap and the
-- dispute threshold - and every bnpl table carrying money is INSERT-only for every role but its owner
-- (BnplSchemaHasNoMutableMoneyTest, INV-BNPL-01). Its machine, though, needs a row that moves (one ACTIVE per product by
-- partial unique, the activation locking the active row FOR UPDATE, an offer pinning it FOR SHARE - both need an UPDATE
-- privilege on some column). So the version is split along that line, one id for both:
--
--     bnpl_terms_version - the machine row: identity, status, who decided, the effective period. NO MONEY, so it takes a
--                          column-scoped UPDATE of the decision columns, under the edge trigger.
--     bnpl_terms         - the term set: born with its version in the proposing transaction, never changed for any
--                          role (trigger), INSERT-only by grant - the money lives here.
--
-- Held for EVERY writer: the edge trigger (born PROPOSED and undecided - no version is activated by insert or by
-- migration; identity frozen), the term set immutable from insert, one PROPOSED and one ACTIVE version per product
-- (partial uniques), activator <> proposer by CHECK, a retirement committed only beside its successor (deferred), a
-- version committed only with its term set (deferred), the effective period stamped by the database AFTER the locks
-- (clock_timestamp(), lending V030's repair) and clamped on a backward clock (credit V014's lesson), pay-in-4's rate held
-- to zero, the conventions only the build's engines implement and every reason bounded and screened for instrument shapes
-- (the reason screen's twin, below). Proposals and decisions of one product serialise on advisory namespace 12 on
-- hashtext(product) - A0, JdbcBnplTermsStore.NAMESPACE; the partial uniques are the backstop.
--
-- Money is explicit (INV-MON-01): the term set states its currency and scale once, every amount integer minor units in
-- them; the rate is a canonical decimal string (no trailing zero, at most 8 places, 0 to 1) - no floating point anywhere.
-- NOTHING IS SEEDED: production ships with no version (ADR-0101 decision 14), so no BNPL product is offered until two
-- persons activate one. This migration names no other schema (BNPL_LIFECYCLES.md section 3).

-- ---------------------------------------------------------------------------------------------
-- The reason screen's twin (lending V002's functions - credit V016's - statement for statement, in this schema:
-- a schema owns its functions, ADR-0006). The domain screens first (BnplReasons, InstrumentShapes).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.luhn_valid(digits TEXT)
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
CREATE OR REPLACE FUNCTION bnpl.mod97_check_holds(compact TEXT)
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
CREATE OR REPLACE FUNCTION bnpl.holds_card_number_shape(body TEXT)
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
                    IF bnpl.luhn_valid(substr(grp, start_at, width)) THEN
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
                IF char_length(span) >= 12 AND bnpl.luhn_valid(span) THEN
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
CREATE OR REPLACE FUNCTION bnpl.holds_account_identifier_shape(body TEXT)
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
                            AND bnpl.mod97_check_holds(compact) THEN
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

CREATE OR REPLACE FUNCTION bnpl.holds_instrument_shape(body TEXT)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT bnpl.holds_card_number_shape(body)
        OR bnpl.holds_account_identifier_shape(body)
$$;

-- ---------------------------------------------------------------------------------------------
-- The arrays' shapes, for the CHECKs (a CHECK may not hold a subquery) - lending V003's function, in this schema.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.is_strictly_ascending(elements INTEGER[])
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS $$
DECLARE
    i INT;
BEGIN
    IF cardinality(elements) = 0 OR array_position(elements, NULL) IS NOT NULL THEN
        RETURN false;
    END IF;
    FOR i IN 2..cardinality(elements) LOOP
        IF elements[i] <= elements[i - 1] THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_terms_version (
    id                uuid        NOT NULL,
    product           text        NOT NULL,
    version           integer     NOT NULL,
    status            text        NOT NULL,
    proposed_by       text        NOT NULL,
    proposed_by_type  text        NOT NULL,
    proposed_at       timestamptz NOT NULL,
    proposal_reason   text        NOT NULL,
    decided_by        text,
    decided_by_type   text,
    decided_at        timestamptz,
    decision_reason   text,
    effective_from    timestamptz,
    effective_to      timestamptz,
    correlation_id    text        NOT NULL,
    CONSTRAINT bnpl_terms_version_pk PRIMARY KEY (id),
    -- The term set's and the history's composite keys pin their product to this row's.
    CONSTRAINT bnpl_terms_version_product_pinned UNIQUE (id, product),
    CONSTRAINT bnpl_terms_version_number_unique UNIQUE (product, version),
    CONSTRAINT bnpl_terms_version_number_positive CHECK (version >= 1),
    -- BnplProduct.
    CONSTRAINT bnpl_terms_version_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    -- BnplTermsVersionStatus.
    CONSTRAINT bnpl_terms_version_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT bnpl_terms_version_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_terms_version_proposal_reason_no_instrument_shape CHECK (
        NOT bnpl.holds_instrument_shape(proposal_reason)),
    CONSTRAINT bnpl_terms_version_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT bnpl_terms_version_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_terms_version_decision_reason_no_instrument_shape CHECK (
        decision_reason IS NULL OR NOT bnpl.holds_instrument_shape(decision_reason)),
    -- FOUR EYES, for every writer and with no seed exemption (INV-AUD-04): whoever activated a version did not propose it.
    CONSTRAINT bnpl_terms_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT bnpl_terms_version_effective_coherent CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (effective_from IS NOT NULL)
        AND (status = 'RETIRED') = (effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_to > effective_from))
);

COMMENT ON TABLE bnpl.bnpl_terms_version IS
    'A BNPL product terms version''s machine (P12-TSK-004, ADR-0102 decision 4): PROPOSED -> ACTIVE (a second person) -> RETIRED (only beside its successor), or PROPOSED -> REJECTED. One ACTIVE and one PROPOSED per product; activator <> proposer by CHECK; identity frozen; the effective period stamped by the database after the locks, clamped on a backward clock; never activated by insert or by a migration. The term set is bnpl.bnpl_terms, born with it and never changed. Every offer and agreement pins the version that priced it (INV-HIST-04). No money.';

-- One of each per product - the backstops beneath A0.
CREATE UNIQUE INDEX bnpl_terms_one_active ON bnpl.bnpl_terms_version (product) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX bnpl_terms_one_proposed ON bnpl.bnpl_terms_version (product) WHERE status = 'PROPOSED';
CREATE INDEX bnpl_terms_effective ON bnpl.bnpl_terms_version (product, effective_from)
    WHERE effective_from IS NOT NULL;

-- The edge trigger: lending V003's body as lending V030 left it - every stamp clock_timestamp(), the instant written
-- after the lock the act waited on (the Phase 11 gate's A2-F3), never the transaction's start.
CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL terms version is born PROPOSED and undecided: no version is activated by insert (P12-TSK-004)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL terms version is never deleted (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL terms version''s identity is frozen (P12-TSK-004, INV-HIST-04)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a BNPL terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P12-TSK-004)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := clock_timestamp();
        -- Never before the product's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's lesson).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM bnpl.bnpl_terms_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P12-TSK-004)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE), so after every offer
        -- made under it; never before the transaction's start, so the successor's effective_from (the greater of the start
        -- and this) is exactly this end; never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_terms_version_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_terms_version
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_terms_version_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the BNPL terms history is never truncated (P12-TSK-004, INV-HIST-04)';
END;
$$;

CREATE TRIGGER bnpl_terms_version_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_terms_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_terms_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a later version of the product must be ACTIVE, starting
-- where the retired one ends - so the effective periods meet and one version is active at every instant.
CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_terms_version successor
             WHERE successor.product = NEW.product AND successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from = NEW.effective_to) THEN
        RAISE EXCEPTION 'BNPL terms version % retired with no later ACTIVE successor starting where it ends (P12-TSK-004)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_terms_retires_only_beside_its_successor
    AFTER UPDATE ON bnpl.bnpl_terms_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_terms_retires_only_beside_its_successor();

-- ---------------------------------------------------------------------------------------------
-- The term set: the money, born with its version, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_terms (
    terms_version_id                  uuid      NOT NULL,
    product                           text      NOT NULL,
    currency                          char(3)   NOT NULL,
    scale                             smallint  NOT NULL,
    nominal_annual_rate               text      NOT NULL,
    frequency                         text      NOT NULL,
    late_fee_minor                    bigint    NOT NULL,
    late_fee_day                      integer   NOT NULL,
    late_fee_cap_minor                bigint    NOT NULL,
    grace_days                        integer   NOT NULL,
    delinquency_bounds                integer[] NOT NULL,
    default_threshold_days            integer   NOT NULL,
    allocation_sequence               text      NOT NULL,
    allocation_order                  text[]    NOT NULL,
    overpayment_treatment             text      NOT NULL,
    auto_collection                   boolean   NOT NULL,
    partial_collection                boolean   NOT NULL,
    collection_retry_days             integer   NOT NULL,
    instalment_rounding               text      NOT NULL,
    interest_rounding                 text,
    day_count                         text,
    servicing_zone                    text      NOT NULL,
    instalment_count                  integer,
    interval_days                     integer,
    allowed_terms                     integer[],
    min_first_period_days             integer,
    offer_validity_minutes            integer   NOT NULL,
    dispute_response_days             integer   NOT NULL,
    dispute_four_eyes_threshold_minor bigint    NOT NULL,
    payoff_horizon_days               integer   NOT NULL,
    schedule_engine                   text      NOT NULL,
    accrual_engine                    text,
    allocation_engine                 text      NOT NULL,
    refund_allocation_engine          text      NOT NULL,
    agreement_template_id             text      NOT NULL,
    agreement_template_version        integer   NOT NULL,
    CONSTRAINT bnpl_terms_pk PRIMARY KEY (terms_version_id),
    CONSTRAINT bnpl_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES bnpl.bnpl_terms_version (id, product),
    -- Both products are EUR (BnplProduct, A2); every amount in the currency's scale.
    CONSTRAINT bnpl_terms_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The price: a canonical decimal fraction 0..1 (no trailing zero, at most 8 places), fixed for the version's life
    -- (OQ-4) - the only rate any BNPL engine computes, so no rate-type column.
    CONSTRAINT bnpl_terms_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    -- The late fee (A13): an amount, the DPD it is assessed at - beyond grace, at or before default - and the cap per
    -- agreement it may reach. Tests configure one; production activates none.
    CONSTRAINT bnpl_terms_late_fee_bounded CHECK (
        late_fee_minor >= 0 AND late_fee_cap_minor >= late_fee_minor
        AND grace_days BETWEEN 0 AND 30 AND late_fee_day > grace_days AND late_fee_day <= default_threshold_days),
    -- Delinquency (A14, ADR-0098's shape): four buckets' lower bounds, the first 1, ascending, the last the default.
    CONSTRAINT bnpl_terms_delinquency_bounds CHECK (
        default_threshold_days BETWEEN 30 AND 180
        AND cardinality(delinquency_bounds) = 4 AND delinquency_bounds[1] = 1
        AND bnpl.is_strictly_ascending(delinquency_bounds)
        AND delinquency_bounds[4] = default_threshold_days),
    -- Allocation (ALLOCATION_ENGINE_V1, both products): oldest due first, over an order naming every component exactly
    -- once; an overpayment held as credit (applied at the next billing, refunded at closure).
    CONSTRAINT bnpl_terms_allocation_complete CHECK (
        allocation_sequence = 'OLDEST_DUE_FIRST'
        AND cardinality(allocation_order) = 3
        AND allocation_order @> ARRAY['FEES', 'INTEREST', 'PRINCIPAL']::text[]
        AND overpayment_treatment = 'HOLD_AS_CREDIT'),
    -- Collection (A7): wallet only, partial allowed or not, retried every 1 to 7 days while due (the default daily).
    CONSTRAINT bnpl_terms_collection_cadence CHECK (collection_retry_days BETWEEN 1 AND 7),
    -- The servicing zone (A22): UTC - the one this build services in.
    CONSTRAINT bnpl_terms_servicing_zone_implemented CHECK (servicing_zone = 'UTC'),
    -- The windows: the offer cap (A17, OQ-18: at most 30 minutes), the merchant's dispute response window (OQ-10: 14
    -- days), the dispute four-eyes threshold (ADR-0106 decision 5), the payoff horizon.
    CONSTRAINT bnpl_terms_windows_bounded CHECK (
        offer_validity_minutes BETWEEN 1 AND 30
        AND dispute_response_days BETWEEN 1 AND 60
        AND dispute_four_eyes_threshold_minor >= 0
        AND payoff_horizon_days BETWEEN 1 AND 90),
    -- The engines both products share.
    CONSTRAINT bnpl_terms_shared_engines_implemented CHECK (
        allocation_engine = 'ALLOCATION_ENGINE_V1' AND refund_allocation_engine = 'REFUND_ALLOCATION_ENGINE_V1'),
    -- Each product's own plan terms and no other's. Pay-in-4 (A4, A5, ADR-0102 decision 2): BIWEEKLY, four instalments
    -- every 14 days, NO INTEREST - its rate held to zero - the instalment rounded DOWN with the remainder on instalment
    -- 1, on BNPL_SCHEDULE_ENGINE_V1, no day count and no accrual. Pay-in-N (A6, ADR-0102 decision 3): MONTHLY, an
    -- ascending choice among 3, 6 and 12 months, the first-period floor 1 to 28 days, ACT/365F, instalment UP and interest
    -- HALF_EVEN, on SCHEDULE_ENGINE_V1 and ACCRUAL_ENGINE_V1 - only the conventions those engines implement (INV-LND-09
    -- by analogy). A new convention is a new engine version AND a migration widening these.
    CONSTRAINT bnpl_terms_kind_coherent CHECK (
        (product = 'BNPL_PAY_IN_4'
            AND frequency = 'BIWEEKLY'
            AND nominal_annual_rate = '0'
            AND instalment_count = 4 AND interval_days = 14
            AND allowed_terms IS NULL AND min_first_period_days IS NULL
            AND instalment_rounding = 'DOWN' AND interest_rounding IS NULL AND day_count IS NULL
            AND schedule_engine = 'BNPL_SCHEDULE_ENGINE_V1' AND accrual_engine IS NULL)
        OR (product = 'BNPL_PAY_IN_N'
            AND frequency = 'MONTHLY'
            AND instalment_count IS NULL AND interval_days IS NULL
            AND allowed_terms <@ ARRAY[3, 6, 12] AND bnpl.is_strictly_ascending(allowed_terms)
            AND min_first_period_days BETWEEN 1 AND 28
            AND instalment_rounding = 'UP' AND interest_rounding = 'HALF_EVEN' AND day_count = 'ACT_365F'
            AND schedule_engine = 'SCHEDULE_ENGINE_V1' AND accrual_engine = 'ACCRUAL_ENGINE_V1')),
    CONSTRAINT bnpl_terms_template_shape CHECK (
        agreement_template_id ~ '^[A-Z][A-Z0-9_]{2,63}$' AND agreement_template_version >= 1)
);

COMMENT ON TABLE bnpl.bnpl_terms IS
    'A BNPL terms version''s term set (P12-TSK-004, ADR-0102 decision 4): born with its version in the proposing transaction and never changed for any role (INV-HIST-04) - INSERT-only, because it carries money (the late fee, its cap and the dispute threshold, RESTRICTED-FINANCIAL). Pay-in-4''s rate held to zero; only the conventions the build''s engines implement. The offer copies it into its canonical terms (P12-TSK-011).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL terms version''s term set is immutable from insert, for every role (P12-TSK-004, INV-HIST-04)';
    END IF;
    -- Born with its version, never late: only a PROPOSED version - in its proposing transaction - takes a term set.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms_version v
                    WHERE v.id = NEW.terms_version_id AND v.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'a BNPL term set is born with its PROPOSED version (P12-TSK-004)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_terms_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_terms
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_terms_is_born_once();

CREATE TRIGGER bnpl_terms_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_terms
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_terms_is_never_truncated();

-- A version commits only with its term set: at COMMIT, the proposal's bnpl_terms row must exist.
CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_has_its_terms()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.id) THEN
        RAISE EXCEPTION 'BNPL terms version % committed without its term set (P12-TSK-004)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_terms_version_has_its_terms
    AFTER INSERT ON bnpl.bnpl_terms_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_terms_version_has_its_terms();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_terms_version_event (
    id                uuid        NOT NULL,
    terms_version_id  uuid        NOT NULL,
    product           text        NOT NULL,
    from_status       text,
    to_status         text        NOT NULL,
    actor             text        NOT NULL,
    actor_type        text        NOT NULL,
    reason            text        NOT NULL,
    occurred_at       timestamptz NOT NULL,
    correlation_id    text        NOT NULL,
    CONSTRAINT bnpl_terms_version_event_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_terms_version_event_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES bnpl.bnpl_terms_version (id, product),
    -- BnplTermsVersionStatus, and its only edges.
    CONSTRAINT bnpl_terms_version_event_edge CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('ACTIVE', 'REJECTED'))
        OR (from_status = 'ACTIVE' AND to_status = 'RETIRED')),
    CONSTRAINT bnpl_terms_version_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT bnpl_terms_version_event_reason_no_instrument_shape CHECK (NOT bnpl.holds_instrument_shape(reason))
);

CREATE INDEX bnpl_terms_version_event_by_version ON bnpl.bnpl_terms_version_event (terms_version_id, occurred_at);

COMMENT ON TABLE bnpl.bnpl_terms_version_event IS
    'A BNPL terms version''s machine as history (P12-TSK-004): every edge, its actor, reason (RESTRICTED-PII) and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL terms version event is append-only (P12-TSK-004, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_terms_version_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_terms_version_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_terms_version_event_is_append_only();

CREATE TRIGGER bnpl_terms_version_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_terms_version_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_terms_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants (bnpl's first, written beside the tables they apply to - V001 sets no default privilege): the machine row's
-- decision columns; the term set and the history INSERT-only.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_terms_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_from, effective_to)
    ON bnpl.bnpl_terms_version TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_terms TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_terms_version_event TO finapp_app;
