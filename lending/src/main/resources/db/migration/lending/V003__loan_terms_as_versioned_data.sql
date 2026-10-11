-- Loan product terms as versioned data, both products (P11-TSK-005; ADR-0092 sections 1-3, PHASE_11_PLAN.md sections
-- 8 and 12.1, LENDING_LIFECYCLES.md section 4.1; INV-LND-05, INV-LND-09, INV-HIST-04, INV-AUD-04, INV-AUD-02).
--
-- A TERMS VERSION is one product's price and servicing conventions as rows: the nominal annual rate (FIXED only), the
-- origination fee (an amount or basis points, DEDUCTED), the late fee (amount, day, cap), grace, the delinquency bucket
-- bounds and the default threshold, the allocation order and the overpayment treatment, auto- and partial collection,
-- the rounding modes, the day count and the servicing zone, the allowed repayment / statement days, the first-period,
-- offer-validity, payoff-horizon and disbursement windows, a line's minimum-payment rule, the engine versions it is
-- computed by and the agreement template it is documented by. It changes only forward and only under four eyes, per
-- product, on credit's policy shape (credit V008, V014):
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal or a correction)--> REJECTED
--
-- WHY TWO TABLES FOR ONE VERSION. A terms version carries money - the fees and a line's minimum-payment floor - and
-- every lending table carrying money is INSERT-only for every role but its owner (LendingSchemaHasNoMutableMoneyTest,
-- PHASE_11_PLAN.md section 8). Its machine, though, needs a row that moves (one ACTIVE per product by partial unique,
-- the activation locking the active row FOR UPDATE, an offer pinning it FOR SHARE - both need an UPDATE privilege on
-- some column). So the version is split along that line, one id for both:
--
--     loan_terms_version - the machine row: identity, status, who decided, the effective period. NO MONEY, so it takes
--                          credit's column-scoped UPDATE of the decision columns, under the edge trigger.
--     loan_terms         - the term set: born with its version in the proposing transaction, never changed for any
--                          role (trigger), INSERT-only by grant - the money lives here.
--
-- Held for EVERY writer, as credit V008 holds its policy: the edge trigger (born PROPOSED and undecided - no version is
-- activated by insert or by migration; identity frozen), the term set immutable from insert, one PROPOSED and one
-- ACTIVE version per product (partial uniques), activator <> proposer by CHECK, a retirement committed only beside its
-- successor (deferred), a version committed only with its term set (deferred), the effective period stamped by the
-- database and clamped on a backward clock (credit V014's lesson, X-TSK-017), the conventions only the build's engines
-- implement (INV-LND-09) and every reason bounded and screened for instrument shapes (V002's twin functions).
-- Proposals and decisions of one product serialise on advisory namespace 11 on hashtext(product) - A0 - registered by
-- JdbcCapitalContributionStore.NAMESPACE (P11-TSK-004); the partial uniques are the backstop.
--
-- Money is explicit (INV-MON-01): the term set states its currency and scale once, every amount integer minor units in
-- them; the rate is a canonical decimal string (no trailing zero, at most 8 places, 0 to 1), every ratio basis points
-- - no floating point anywhere. NOTHING IS SEEDED: production ships with no version (ADR-0090 section 9, L11).

-- ---------------------------------------------------------------------------------------------
-- The arrays' shapes, for the CHECKs (a CHECK may not hold a subquery).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.is_strictly_ascending(elements INTEGER[])
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
CREATE TABLE lending.loan_terms_version (
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
    CONSTRAINT loan_terms_version_pk PRIMARY KEY (id),
    -- The term set's composite key pins its product to this row's.
    CONSTRAINT loan_terms_version_product_pinned UNIQUE (id, product),
    CONSTRAINT loan_terms_version_number_unique UNIQUE (product, version),
    CONSTRAINT loan_terms_version_number_positive CHECK (version >= 1),
    -- LoanProduct.
    CONSTRAINT loan_terms_version_product_is_offered CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    -- LoanTermsVersionStatus.
    CONSTRAINT loan_terms_version_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT loan_terms_version_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_terms_version_proposal_reason_no_instrument_shape CHECK (
        NOT lending.holds_instrument_shape(proposal_reason)),
    CONSTRAINT loan_terms_version_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT loan_terms_version_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_terms_version_decision_reason_no_instrument_shape CHECK (
        decision_reason IS NULL OR NOT lending.holds_instrument_shape(decision_reason)),
    -- FOUR EYES, for every writer and with no seed exemption (INV-AUD-04): whoever activated a version did not propose it.
    CONSTRAINT loan_terms_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT loan_terms_version_effective_coherent CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (effective_from IS NOT NULL)
        AND (status = 'RETIRED') = (effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_to > effective_from))
);

COMMENT ON TABLE lending.loan_terms_version IS
    'A loan product terms version''s machine (P11-TSK-005, ADR-0092): PROPOSED -> ACTIVE (a second person) -> RETIRED (only beside its successor), or PROPOSED -> REJECTED. One ACTIVE and one PROPOSED per product; activator <> proposer by CHECK; identity frozen; the effective period stamped by the database, clamped on a backward clock; never activated by insert or by a migration. The term set is lending.loan_terms, born with it and never changed. Every offer and agreement pins the version that priced it (INV-LND-05, INV-HIST-04).';

-- One of each per product - the backstops beneath A0.
CREATE UNIQUE INDEX loan_terms_one_active ON lending.loan_terms_version (product) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX loan_terms_one_proposed ON lending.loan_terms_version (product) WHERE status = 'PROPOSED';
CREATE INDEX loan_terms_effective ON lending.loan_terms_version (product, effective_from)
    WHERE effective_from IS NOT NULL;

CREATE OR REPLACE FUNCTION lending.loan_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a loan terms version is born PROPOSED and undecided: no version is activated by insert (P11-TSK-005)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan terms version is never deleted (P11-TSK-005, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan terms version''s identity is frozen (P11-TSK-005, INV-LND-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a loan terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P11-TSK-005)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        -- Never before the product's latest end - its predecessor's, retired first in this transaction - whatever the
        -- database clock did since (credit V014's lesson, X-TSK-017).
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM lending.loan_terms_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P11-TSK-005)';
        END IF;
        -- Never at or before its own start, whatever the clock says (credit V014).
        NEW.effective_to := GREATEST(transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_terms_version_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_terms_version
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_terms_version_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION lending.loan_terms_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the loan terms history is never truncated (P11-TSK-005, INV-HIST-04)';
END;
$$;

CREATE TRIGGER loan_terms_version_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_terms_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_terms_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a later version of the product must be ACTIVE, starting
-- where the retired one ends - so the effective periods meet and one version is active at every instant.
CREATE OR REPLACE FUNCTION lending.loan_terms_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM lending.loan_terms_version successor
             WHERE successor.product = NEW.product AND successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from = NEW.effective_to) THEN
        RAISE EXCEPTION 'loan terms version % retired with no later ACTIVE successor starting where it ends (P11-TSK-005)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_terms_retires_only_beside_its_successor
    AFTER UPDATE ON lending.loan_terms_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_terms_retires_only_beside_its_successor();

-- ---------------------------------------------------------------------------------------------
-- The term set: the money, born with its version, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_terms (
    terms_version_id             uuid     NOT NULL,
    product                      text     NOT NULL,
    currency                     char(3)  NOT NULL,
    scale                        smallint NOT NULL,
    nominal_annual_rate          text     NOT NULL,
    rate_type                    text     NOT NULL,
    origination_fee_minor        bigint,
    origination_fee_bps          integer,
    origination_fee_treatment    text     NOT NULL,
    late_fee_minor               bigint   NOT NULL,
    late_fee_day                 integer  NOT NULL,
    late_fee_cap_minor           bigint   NOT NULL,
    grace_days                   integer  NOT NULL,
    delinquency_bounds           integer[] NOT NULL,
    default_threshold_days       integer  NOT NULL,
    allocation_sequence          text     NOT NULL,
    allocation_order             text[]   NOT NULL,
    overpayment_treatment        text     NOT NULL,
    auto_collection              boolean  NOT NULL,
    partial_collection           boolean  NOT NULL,
    instalment_rounding          text     NOT NULL,
    interest_rounding            text     NOT NULL,
    day_count                    text     NOT NULL,
    servicing_zone               text     NOT NULL,
    allowed_days                 integer[] NOT NULL,
    min_first_period_days        integer,
    offer_validity_days          integer  NOT NULL,
    payoff_horizon_days          integer  NOT NULL,
    disbursement_deadline_hours  integer,
    minimum_payment_floor_minor  bigint,
    minimum_payment_ratio_bps    integer,
    payment_due_days             integer,
    schedule_engine_version      smallint,
    accrual_engine_version       smallint NOT NULL,
    allocation_engine_version    smallint NOT NULL,
    statement_engine_version     smallint,
    agreement_template_id        text     NOT NULL,
    agreement_template_version   integer  NOT NULL,
    CONSTRAINT loan_terms_pk PRIMARY KEY (terms_version_id),
    CONSTRAINT loan_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES lending.loan_terms_version (id, product),
    -- Both products are EUR (LoanProduct); every amount in the currency's scale.
    CONSTRAINT loan_terms_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The price: a canonical decimal fraction 0..1 (no trailing zero, at most 8 places), FIXED - the only rate type
    -- any engine implements (no reference-rate source exists, PHASE_11_PLAN.md section 12.1).
    CONSTRAINT loan_terms_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    CONSTRAINT loan_terms_rate_type_implemented CHECK (rate_type = 'FIXED'),
    -- The origination fee: an amount or basis points, never both (A17), deducted from the disbursement.
    CONSTRAINT loan_terms_origination_fee_one_basis CHECK (
        num_nonnulls(origination_fee_minor, origination_fee_bps) = 1
        AND (origination_fee_minor IS NULL OR origination_fee_minor >= 0)
        AND (origination_fee_bps IS NULL OR origination_fee_bps BETWEEN 0 AND 9999)),
    CONSTRAINT loan_terms_origination_fee_deducted CHECK (origination_fee_treatment = 'DEDUCTED'),
    -- The late fee (A18): an amount, the DPD it is assessed at - beyond grace, at or before default - and the cap per
    -- account it may reach.
    CONSTRAINT loan_terms_late_fee_bounded CHECK (
        late_fee_minor >= 0 AND late_fee_cap_minor >= late_fee_minor
        AND grace_days BETWEEN 0 AND 30 AND late_fee_day > grace_days AND late_fee_day <= default_threshold_days),
    -- Delinquency (L7, ADR-0098): four buckets' lower bounds, the first 1, ascending, the last the default threshold.
    CONSTRAINT loan_terms_delinquency_bounds CHECK (
        default_threshold_days BETWEEN 30 AND 180
        AND cardinality(delinquency_bounds) = 4 AND delinquency_bounds[1] = 1
        AND lending.is_strictly_ascending(delinquency_bounds)
        AND delinquency_bounds[4] = default_threshold_days),
    -- Allocation (L6, ADR-0095): oldest due first, over an order naming every component exactly once.
    CONSTRAINT loan_terms_allocation_complete CHECK (
        allocation_sequence = 'OLDEST_DUE_FIRST'
        AND cardinality(allocation_order) = 3
        AND allocation_order @> ARRAY['FEES', 'INTEREST', 'PRINCIPAL']::text[]),
    -- INV-LND-09: only the conventions this build's engines implement - ACT_365F (ACCRUAL_ENGINE_V1), instalment
    -- rounding UP (SCHEDULE_ENGINE_V1, STATEMENT_ENGINE_V1), interest HALF_EVEN (ACCRUAL_ENGINE_V1), a fixed-offset
    -- servicing zone (A2, A15). A new convention is a new engine version AND a migration widening these.
    CONSTRAINT loan_terms_conventions_implemented CHECK (
        day_count = 'ACT_365F' AND instalment_rounding = 'UP' AND interest_rounding = 'HALF_EVEN'
        AND servicing_zone ~ '^(UTC|[+-](0[0-9]|1[0-4]):[0-5][0-9])$'),
    -- Repayment / statement days within every month (1-28), ascending, at least one.
    CONSTRAINT loan_terms_allowed_days CHECK (
        lending.is_strictly_ascending(allowed_days) AND allowed_days[1] >= 1
        AND allowed_days[cardinality(allowed_days)] <= 28),
    -- The windows: an offer never outlives the product's declared validity (14 days); the payoff horizon.
    CONSTRAINT loan_terms_windows_bounded CHECK (
        offer_validity_days BETWEEN 1 AND 14 AND payoff_horizon_days BETWEEN 1 AND 90),
    CONSTRAINT loan_terms_engines_numbered CHECK (
        accrual_engine_version >= 1 AND allocation_engine_version >= 1
        AND (schedule_engine_version IS NULL OR schedule_engine_version >= 1)
        AND (statement_engine_version IS NULL OR statement_engine_version >= 1)),
    -- Each product's own terms and no other's: the instalment loan's first period, disbursement deadline, schedule
    -- engine and overpayment held as credit; the line's minimum payment (A23, A24), statement engine and overpayment
    -- paying down principal (A26).
    CONSTRAINT loan_terms_kind_coherent CHECK (
        (product = 'PERSONAL_LOAN'
            AND min_first_period_days BETWEEN 1 AND 28
            AND disbursement_deadline_hours BETWEEN 1 AND 24
            AND schedule_engine_version IS NOT NULL AND statement_engine_version IS NULL
            AND minimum_payment_floor_minor IS NULL AND minimum_payment_ratio_bps IS NULL AND payment_due_days IS NULL
            AND overpayment_treatment = 'HOLD_AS_CREDIT')
        OR (product = 'CREDIT_LINE'
            AND min_first_period_days IS NULL AND disbursement_deadline_hours IS NULL
            AND schedule_engine_version IS NULL AND statement_engine_version IS NOT NULL
            AND minimum_payment_floor_minor > 0 AND minimum_payment_ratio_bps BETWEEN 1 AND 10000
            AND payment_due_days BETWEEN 1 AND 27
            AND overpayment_treatment = 'PAY_DOWN_PRINCIPAL')),
    CONSTRAINT loan_terms_template_shape CHECK (
        agreement_template_id ~ '^[A-Z][A-Z0-9_]{2,63}$' AND agreement_template_version >= 1)
);

COMMENT ON TABLE lending.loan_terms IS
    'A loan terms version''s term set (P11-TSK-005, ADR-0092 section 2): born with its version in the proposing transaction and never changed for any role (INV-LND-05) - INSERT-only, because it carries money (the fees and a line''s minimum-payment floor, RESTRICTED-FINANCIAL). Only the conventions the build''s engines implement (INV-LND-09). The offer copies it into its canonical terms (P11-TSK-011).';

CREATE OR REPLACE FUNCTION lending.loan_terms_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan terms version''s term set is immutable from insert, for every role (P11-TSK-005, INV-LND-05)';
    END IF;
    -- Born with its version, never late: only a PROPOSED version - in its proposing transaction - takes a term set.
    IF NOT EXISTS (SELECT 1 FROM lending.loan_terms_version v
                    WHERE v.id = NEW.terms_version_id AND v.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'a term set is born with its PROPOSED version (P11-TSK-005)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_terms_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_terms
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_terms_is_born_once();

CREATE TRIGGER loan_terms_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_terms
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_terms_is_never_truncated();

-- A version commits only with its term set: at COMMIT, the proposal's loan_terms row must exist.
CREATE OR REPLACE FUNCTION lending.loan_terms_version_has_its_terms()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan_terms t WHERE t.terms_version_id = NEW.id) THEN
        RAISE EXCEPTION 'loan terms version % committed without its term set (P11-TSK-005)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_terms_version_has_its_terms
    AFTER INSERT ON lending.loan_terms_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_terms_version_has_its_terms();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_terms_version_event (
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
    CONSTRAINT loan_terms_version_event_pk PRIMARY KEY (id),
    CONSTRAINT loan_terms_version_event_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES lending.loan_terms_version (id, product),
    -- LoanTermsVersionStatus, and its only edges.
    CONSTRAINT loan_terms_version_event_edge CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('ACTIVE', 'REJECTED'))
        OR (from_status = 'ACTIVE' AND to_status = 'RETIRED')),
    CONSTRAINT loan_terms_version_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000),
    CONSTRAINT loan_terms_version_event_reason_no_instrument_shape CHECK (NOT lending.holds_instrument_shape(reason))
);

CREATE INDEX loan_terms_version_event_by_version ON lending.loan_terms_version_event (terms_version_id, occurred_at);

COMMENT ON TABLE lending.loan_terms_version_event IS
    'A loan terms version''s machine as history (P11-TSK-005): every edge, its actor, reason and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION lending.loan_terms_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan terms version event is append-only (P11-TSK-005, INV-HIST-01)';
    END IF;
    NEW.occurred_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_terms_version_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_terms_version_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_terms_version_event_is_append_only();

CREATE TRIGGER loan_terms_version_event_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_terms_version_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_terms_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's decision columns; the term set and the history INSERT-only.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_terms_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_from, effective_to)
    ON lending.loan_terms_version TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_terms TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_terms_version_event TO finapp_app;
