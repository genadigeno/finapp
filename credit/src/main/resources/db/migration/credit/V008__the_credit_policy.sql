-- The credit policy and its versioning (P10-TSK-012; ADR-0086 sections 1, 4-8, PHASE_10_PLAN.md section 12.6;
-- INV-CRD-05, INV-CRD-10, INV-CRD-02, INV-HIST-04, INV-AUD-04).
--
-- A POLICY VERSION is one product's lending rulebook as rows: its parameters - the affordability stress rate and
-- minimum disposable income, a revolving line's minimum payment ratio, the maximum exposure, the maximum data age of
-- each source kind it reads, the fallback for an unavailable source and the auto-approval ceiling - and its RULES,
-- each (ordinal, rule code, attribute or derived figure, operator, typed operand, effect, reason code) over a closed
-- vocabulary. It changes only forward and only under four eyes, on the scorecard's shape (V006), per product:
--
--     PROPOSED --a different approver--> ACTIVE --only beside its successor--> RETIRED
--         \--anyone (a withdrawal or a correction)--> REJECTED
--
-- Held for EVERY writer, as V006 holds the scorecard: the edge trigger, identity and parameters frozen; the RULES born
-- with their version in its proposing transaction and immutable from insert, in every status (a correction is a
-- rejection and a new proposal); one PROPOSED and one ACTIVE version per product (partial uniques); activator <>
-- proposer by CHECK, with NO SEED EXEMPTION; a retirement committed only beside its successor; the effective period
-- stamped by the database, a predecessor's end its successor's start. Proposals of one product serialise on advisory
-- namespace 10 on hashtext(product) (DISTRIBUTED_EXECUTION.md section 3); the partial unique is the backstop.
--
-- NEVER APPROVE ON MISSING DATA (INV-CRD-10): the unavailable-source fallback is REFER or DECLINE by CHECK - an
-- approving fallback is unrepresentable - and a policy without a fallback rule for every source kind it reads is
-- refused at proposal by the domain (credit.PolicyIncomplete). The source kinds a policy reads are exactly those it
-- declares a maximum data age for.
--
-- Money is explicit (INV-MON-01): the version states its currency and scale once, every amount is integer minor
-- units in them; rates are integer basis points - no floating point anywhere.
--
-- v1 of each offered product is seeded HERE AS A PROPOSAL (proposed_by 'migration:V008'): no policy is
-- migration-activated - two persons holding CREDIT_POLICY_ADMINISTER activate it.

CREATE TABLE credit.credit_policy_version (
    id                                   uuid        NOT NULL,
    product                              text        NOT NULL,
    version                              integer     NOT NULL,
    status                               text        NOT NULL,
    currency                             text        NOT NULL,
    scale                                smallint    NOT NULL,
    assessment_rate_bps                  integer     NOT NULL,
    minimum_disposable_minor             bigint      NOT NULL,
    minimum_payment_ratio_bps            integer     NOT NULL,
    maximum_exposure_minor               bigint      NOT NULL,
    max_data_age_bureau_seconds          integer,
    max_data_age_financial_data_seconds  integer,
    unavailable_fallback                 text        NOT NULL,
    auto_approval_ceiling_minor          bigint      NOT NULL,
    proposed_by                          text        NOT NULL,
    proposed_at                          timestamptz NOT NULL,
    proposal_reason                      text        NOT NULL,
    decided_by                           text,
    decided_at                           timestamptz,
    decision_reason                      text,
    effective_from                       timestamptz,
    effective_to                         timestamptz,
    CONSTRAINT credit_policy_version_pk PRIMARY KEY (id),
    CONSTRAINT credit_policy_version_number_unique UNIQUE (product, version),
    CONSTRAINT credit_policy_version_number_positive CHECK (version >= 1),
    CONSTRAINT credit_policy_product_is_offered CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT credit_policy_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT credit_policy_currency_shape CHECK (currency ~ '^[A-Z]{3}$' AND scale BETWEEN 0 AND 4),
    CONSTRAINT credit_policy_rates_bounded CHECK (
        assessment_rate_bps BETWEEN 0 AND 10000 AND minimum_payment_ratio_bps BETWEEN 1 AND 10000),
    CONSTRAINT credit_policy_amounts_bounded CHECK (
        minimum_disposable_minor >= 0 AND maximum_exposure_minor > 0 AND auto_approval_ceiling_minor > 0),
    CONSTRAINT credit_policy_data_age_bounded CHECK (
        (max_data_age_bureau_seconds IS NULL OR max_data_age_bureau_seconds BETWEEN 1 AND 31536000)
        AND (max_data_age_financial_data_seconds IS NULL OR max_data_age_financial_data_seconds BETWEEN 1 AND 31536000)),
    -- INV-CRD-10: the fallback for an unavailable source is a referral or a decline - never an approval.
    CONSTRAINT credit_policy_fallback_never_approves CHECK (unavailable_fallback IN ('REFER', 'DECLINE')),
    CONSTRAINT credit_policy_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT credit_policy_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT credit_policy_decision_reasoned CHECK (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, with no seed exemption (INV-AUD-04).
    CONSTRAINT credit_policy_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    CONSTRAINT credit_policy_effective_coherent CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (effective_from IS NOT NULL)
        AND (status = 'RETIRED') = (effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_to >= effective_from))
);

COMMENT ON TABLE credit.credit_policy_version IS
    'A credit policy version (P10-TSK-012, ADR-0086): one product''s parameters and rules, versioned per product, four-eyes, seeded only as a proposal. One ACTIVE and one PROPOSED per product; activator <> proposer by CHECK; identity, parameters and rules frozen from PROPOSED; the fallback never approves; the effective period stamped by the database; a retirement only beside its successor. Every decision pins the version that decided it (INV-HIST-04).';

CREATE UNIQUE INDEX credit_policy_one_active ON credit.credit_policy_version (product) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX credit_policy_one_proposed ON credit.credit_policy_version (product) WHERE status = 'PROPOSED';
CREATE INDEX credit_policy_effective ON credit.credit_policy_version (product, effective_from)
    WHERE effective_from IS NOT NULL;

CREATE OR REPLACE FUNCTION credit.credit_policy_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.effective_from IS NOT NULL
                OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a credit policy version is born PROPOSED and undecided: no policy is activated by insert (P10-TSK-012)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a credit policy version is never deleted (P10-TSK-012)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.assessment_rate_bps IS DISTINCT FROM OLD.assessment_rate_bps
            OR NEW.minimum_disposable_minor IS DISTINCT FROM OLD.minimum_disposable_minor
            OR NEW.minimum_payment_ratio_bps IS DISTINCT FROM OLD.minimum_payment_ratio_bps
            OR NEW.maximum_exposure_minor IS DISTINCT FROM OLD.maximum_exposure_minor
            OR NEW.max_data_age_bureau_seconds IS DISTINCT FROM OLD.max_data_age_bureau_seconds
            OR NEW.max_data_age_financial_data_seconds IS DISTINCT FROM OLD.max_data_age_financial_data_seconds
            OR NEW.unavailable_fallback IS DISTINCT FROM OLD.unavailable_fallback
            OR NEW.auto_approval_ceiling_minor IS DISTINCT FROM OLD.auto_approval_ceiling_minor
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a credit policy version''s identity and parameters are frozen (P10-TSK-012, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a credit policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN transaction_timestamp() END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-012)';
        END IF;
        NEW.effective_to := transaction_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_policy_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_policy_version
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_policy_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION credit.credit_policy_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the credit policy''s history is never truncated (P10-TSK-012, INV-HIST-04)';
END;
$$;

CREATE TRIGGER credit_policy_version_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_policy_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_policy_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a later version of the product must be ACTIVE, starting
-- where the retired one ends.
CREATE OR REPLACE FUNCTION credit.credit_policy_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM credit.credit_policy_version successor
             WHERE successor.product = NEW.product AND successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from = NEW.effective_to) THEN
        RAISE EXCEPTION 'credit policy version % retired with no later ACTIVE successor starting where it ends (P10-TSK-012)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER credit_policy_retires_only_beside_its_successor
    AFTER UPDATE ON credit.credit_policy_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_policy_retires_only_beside_its_successor();

-- ------------------------------------------------------------------ the rules

CREATE TABLE credit.credit_policy_rule (
    policy_version_id  uuid    NOT NULL,
    ordinal            integer NOT NULL,
    rule_code          text    NOT NULL,
    subject_kind       text    NOT NULL,
    subject            text    NOT NULL,
    operator           text    NOT NULL,
    operand_integer    bigint,
    operand_money_minor bigint,
    operand_boolean    boolean,
    operand_codes      text[],
    effect             text    NOT NULL,
    cap_amount_minor   bigint,
    reason_code        text    NOT NULL,
    CONSTRAINT credit_policy_rule_pk PRIMARY KEY (policy_version_id, ordinal),
    CONSTRAINT credit_policy_rule_code_unique UNIQUE (policy_version_id, rule_code),
    CONSTRAINT credit_policy_rule_version_fk FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id),
    -- INV-CRD-02: every rule explains itself with a catalogued reason code.
    CONSTRAINT credit_policy_rule_reason_fk FOREIGN KEY (reason_code) REFERENCES credit.reason_code (code),
    CONSTRAINT credit_policy_rule_ordinal_positive CHECK (ordinal >= 1),
    CONSTRAINT credit_policy_rule_code_shape CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT credit_policy_rule_subject_shape CHECK (subject ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    -- The closed derived figures (ADR-0086 section 1); attributes are the vocabulary's, judged by the domain.
    CONSTRAINT credit_policy_rule_subject_kind CHECK (
        subject_kind = 'ATTRIBUTE'
        OR (subject_kind = 'FIGURE' AND subject IN ('SCORE', 'DISPOSABLE_INCOME', 'AFFORDABLE', 'EXPOSURE', 'EXPOSURE_HEADROOM'))),
    CONSTRAINT credit_policy_rule_operator_is_known CHECK (operator IN
        ('LT', 'LE', 'GT', 'GE', 'EQ', 'NE', 'IN', 'NOT_IN', 'IS_ABSENT', 'IS_PRESENT')),
    CONSTRAINT credit_policy_rule_effect_is_known CHECK (effect IN ('HARD_DECLINE', 'DECLINE', 'REFER', 'CAP_AMOUNT')),
    -- The operand's shape follows the operator: presence takes none (and only an attribute can be absent); a set takes
    -- codes; an ordering takes one number; an equality one value of any kind.
    CONSTRAINT credit_policy_rule_operand_shape CHECK (
        (operator IN ('IS_ABSENT', 'IS_PRESENT') AND subject_kind = 'ATTRIBUTE'
            AND num_nonnulls(operand_integer, operand_money_minor, operand_boolean, operand_codes) = 0)
        OR (operator IN ('IN', 'NOT_IN') AND operand_codes IS NOT NULL
            AND num_nonnulls(operand_integer, operand_money_minor, operand_boolean) = 0)
        OR (operator IN ('LT', 'LE', 'GT', 'GE') AND num_nonnulls(operand_integer, operand_money_minor) = 1
            AND num_nonnulls(operand_boolean, operand_codes) = 0)
        OR (operator IN ('EQ', 'NE') AND num_nonnulls(operand_integer, operand_money_minor, operand_boolean, operand_codes) = 1
            AND (operand_codes IS NULL OR cardinality(operand_codes) = 1))),
    CONSTRAINT credit_policy_rule_codes_shape CHECK (
        operand_codes IS NULL OR (cardinality(operand_codes) BETWEEN 1 AND 64 AND array_position(operand_codes, NULL) IS NULL)),
    -- A cap carries its ceiling, in the version's currency, and nothing else does.
    CONSTRAINT credit_policy_rule_cap_shape CHECK (
        (effect = 'CAP_AMOUNT') = (cap_amount_minor IS NOT NULL) AND (cap_amount_minor IS NULL OR cap_amount_minor > 0))
);

COMMENT ON TABLE credit.credit_policy_rule IS
    'One rule of a credit policy version (P10-TSK-012, ADR-0086 section 1): (ordinal, rule code, attribute or derived figure, operator, typed operand, effect, catalogued reason code). Born with its version in the proposing transaction; never updated or deleted, and refused for any version not created in the inserting transaction (INV-CRD-05).';

-- The rules join their version in the version's own transaction, or not at all - V006's band trigger, verbatim in
-- shape: xmin is the inserting transaction's 32-bit id, compared modulo 2^32 with the epoch-extended current one.
CREATE OR REPLACE FUNCTION credit.credit_policy_rule_is_born_with_its_version()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit policy rule is immutable from insert: a correction is a rejection and a new proposal (P10-TSK-012, INV-CRD-05)';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM credit.credit_policy_version v
             WHERE v.id = NEW.policy_version_id
               AND v.status = 'PROPOSED'
               AND v.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) THEN
        RAISE EXCEPTION 'a credit policy rule is born with its version, in the proposing transaction (P10-TSK-012, INV-CRD-05): an approver approves exactly what was proposed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_policy_rule_is_born_with_its_version
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_policy_rule
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_policy_rule_is_born_with_its_version();

CREATE TRIGGER credit_policy_rule_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_policy_rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_policy_is_never_truncated();

-- ------------------------------------------------------------------ the history

CREATE TABLE credit.credit_policy_event (
    id                 uuid        NOT NULL,
    policy_version_id  uuid        NOT NULL,
    from_status        text,
    to_status          text        NOT NULL,
    actor_id           text        NOT NULL,
    reason             text        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    CONSTRAINT credit_policy_event_pk PRIMARY KEY (id),
    CONSTRAINT credit_policy_event_version_fk FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id),
    CONSTRAINT credit_policy_event_statuses_known CHECK (
        (from_status IS NULL OR from_status IN ('PROPOSED', 'ACTIVE'))
        AND to_status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    CONSTRAINT credit_policy_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX credit_policy_event_by_version ON credit.credit_policy_event (policy_version_id, occurred_at);

CREATE OR REPLACE FUNCTION credit.credit_policy_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit policy event is append-only (P10-TSK-012)';
    END IF;
    NEW.occurred_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_policy_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_policy_event
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_policy_event_is_append_only();

CREATE TRIGGER credit_policy_event_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_policy_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_policy_is_never_truncated();

-- ------------------------------------------------------------------ the pinned policy's references (V005's, V007's promise)

ALTER TABLE credit.decision_snapshot
    ADD CONSTRAINT decision_snapshot_policy_version_fk
    FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id);

ALTER TABLE credit.credit_assessment
    ADD CONSTRAINT credit_assessment_policy_fk
    FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id);

-- ------------------------------------------------------------------ v1 of each offered product, as proposals

-- PERSONAL_LOAN v1 (EUR, scale 2): 9% stress rate, EUR 100.00 minimum disposable, 3% minimum payment ratio (a
-- revolving line's parameter, declared by every policy), EUR 40,000.00 maximum exposure, both source kinds read and
-- at most 30 days old, unavailable -> REFER, EUR 10,000.00 auto-approval ceiling.
INSERT INTO credit.credit_policy_version (id, product, version, status, currency, scale, assessment_rate_bps,
        minimum_disposable_minor, minimum_payment_ratio_bps, maximum_exposure_minor, max_data_age_bureau_seconds,
        max_data_age_financial_data_seconds, unavailable_fallback, auto_approval_ceiling_minor, proposed_by, proposed_at,
        proposal_reason)
VALUES ('0190a1b2-5c0e-7000-8000-00000000d001', 'PERSONAL_LOAN', 1, 'PROPOSED', 'EUR', 2, 900, 10000, 300, 4000000,
        2592000, 2592000, 'REFER', 1000000, 'migration:V008', transaction_timestamp(),
        'PERSONAL_LOAN policy version 1 (PHASE_10_PLAN.md section 12.6): seeded as a proposal, activated only by two persons'),
       ('0190a1b2-5c0e-7000-8000-00000000d002', 'CREDIT_LINE', 1, 'PROPOSED', 'EUR', 2, 900, 10000, 500, 2000000,
        2592000, 2592000, 'REFER', 250000, 'migration:V008', transaction_timestamp(),
        'CREDIT_LINE policy version 1 (PHASE_10_PLAN.md section 12.6): seeded as a proposal, activated only by two persons');

INSERT INTO credit.credit_policy_rule (policy_version_id, ordinal, rule_code, subject_kind, subject, operator,
        operand_integer, operand_money_minor, operand_boolean, operand_codes, effect, cap_amount_minor, reason_code)
SELECT v.id, r.ordinal, r.rule_code, r.subject_kind, r.subject, r.operator, r.operand_integer, r.operand_money_minor,
       r.operand_boolean, r.operand_codes, r.effect,
       CASE WHEN r.effect = 'CAP_AMOUNT' THEN CASE v.product WHEN 'PERSONAL_LOAN' THEN 500000 ELSE 100000 END END,
       r.reason_code
  FROM credit.credit_policy_version v
 CROSS JOIN (VALUES
    -- INV-CRD-10: any unavailable source refers - the fallback for both kinds the policy reads.
    (1,  'SOURCE_UNAVAILABLE_FALLBACK', 'ATTRIBUTE', 'SOURCE_UNAVAILABLE',       'IS_PRESENT', NULL::bigint, NULL::bigint, NULL::boolean, NULL::text[],      'REFER',        'CRD-SOURCE-UNAVAILABLE'),
    (2,  'INSOLVENCY',                  'ATTRIBUTE', 'BUREAU_INSOLVENCY_FLAG',   'EQ',         NULL,        NULL,        TRUE,           NULL,              'HARD_DECLINE', 'CRD-INSOLVENCY'),
    (3,  'PRIOR_DEFAULT',               'ATTRIBUTE', 'BUREAU_DEFAULTS_72M',      'GE',         1,           NULL,        NULL,           NULL,              'DECLINE',      'CRD-PRIOR-DEFAULT'),
    (4,  'RECENT_DELINQUENCY',          'ATTRIBUTE', 'BUREAU_DELINQUENCIES_24M', 'GE',         3,           NULL,        NULL,           NULL,              'DECLINE',      'CRD-RECENT-DELINQUENCY'),
    (5,  'SCORE_FLOOR',                 'FIGURE',    'SCORE',                    'LT',         450,         NULL,        NULL,           NULL,              'DECLINE',      'CRD-SCORE-INSUFFICIENT'),
    (6,  'SCORE_REFERRAL',              'FIGURE',    'SCORE',                    'LT',         520,         NULL,        NULL,           NULL,              'REFER',        'CRD-RISK-REFERRAL'),
    (7,  'AFFORDABILITY',               'FIGURE',    'AFFORDABLE',               'EQ',         NULL,        NULL,        FALSE,          NULL,              'DECLINE',      'CRD-AFFORDABILITY-INSUFFICIENT'),
    (8,  'EXPOSURE_LIMIT',              'FIGURE',    'EXPOSURE_HEADROOM',        'LT',         NULL,        0,           NULL,           NULL,              'DECLINE',      'CRD-EXPOSURE-LIMIT'),
    (9,  'INCOME_UNVERIFIED',           'ATTRIBUTE', 'FINDATA_MONTHLY_INCOME',   'IS_ABSENT',  NULL,        NULL,        NULL,           NULL,              'REFER',        'CRD-INCOME-UNVERIFIED'),
    (10, 'FOREIGN_CURRENCY',            'ATTRIBUTE', 'CURRENCY_NOT_SUPPORTED',   'IS_PRESENT', NULL,        NULL,        NULL,           NULL,              'REFER',        'CRD-CURRENCY-NOT-SUPPORTED'),
    (11, 'LOW_SCORE_CAP',               'FIGURE',    'SCORE',                    'LT',         600,         NULL,        NULL,           NULL,              'CAP_AMOUNT',   'CRD-SCORE-INSUFFICIENT')
 ) AS r (ordinal, rule_code, subject_kind, subject, operator, operand_integer, operand_money_minor, operand_boolean,
         operand_codes, effect, reason_code)
 WHERE v.id IN ('0190a1b2-5c0e-7000-8000-00000000d001', '0190a1b2-5c0e-7000-8000-00000000d002');

INSERT INTO credit.credit_policy_event (id, policy_version_id, from_status, to_status, actor_id, reason, occurred_at)
VALUES ('0190a1b2-5c0e-7000-8000-00000000d0e1', '0190a1b2-5c0e-7000-8000-00000000d001', NULL, 'PROPOSED',
        'migration:V008', 'seeded as a proposal (P10-TSK-012)', transaction_timestamp()),
       ('0190a1b2-5c0e-7000-8000-00000000d0e2', '0190a1b2-5c0e-7000-8000-00000000d002', NULL, 'PROPOSED',
        'migration:V008', 'seeded as a proposal (P10-TSK-012)', transaction_timestamp());

-- ------------------------------------------------------------------ the grants

GRANT SELECT, INSERT ON credit.credit_policy_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, decision_reason, effective_from, effective_to)
    ON credit.credit_policy_version TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_policy_rule TO finapp_app;
GRANT SELECT, INSERT ON credit.credit_policy_event TO finapp_app;
