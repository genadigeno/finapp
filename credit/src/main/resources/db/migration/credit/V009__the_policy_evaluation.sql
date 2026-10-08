-- The policy evaluation (P10-TSK-013; PHASE_10_PLAN.md section 12.6, ADR-0086 sections 1-2; INV-CRD-01, INV-CRD-02,
-- INV-CRD-04, INV-CRD-06, INV-CRD-10).
--
-- What the pinned policy concluded about one assessment, under the pinned engine version: the outcome (APPROVE, REFER,
-- DECLINE, HARD_DECLINE - the evaluator's word, a record distinct from the decision that follows it, INV-CRD-04), the
-- approved amount (an approval's alone, at most the request, in minor units of the product's currency), the ordered
-- reason codes, whether the policy's unavailable fallback decided, and - beside it - every rule's result.
--
-- BORN ONCE PER ASSESSMENT: ten evaluators of one assessment leave one row (INSERT ... ON CONFLICT DO NOTHING, then
-- read). Never updated, deleted or truncated by any role; evaluated_at is stamped by the database. An adverse outcome
-- with no reason code is refused by CHECK (INV-CRD-02), and every code named must be in the catalogue.

CREATE TABLE credit.policy_evaluation (
    id                   uuid        NOT NULL,
    assessment_id        uuid        NOT NULL,
    decision_request_id  uuid        NOT NULL,
    policy_version_id    uuid        NOT NULL,
    engine_version       integer     NOT NULL,
    outcome              text        NOT NULL,
    currency             char(3)     NOT NULL,
    requested_minor      bigint      NOT NULL,
    approved_minor       bigint,
    reason_codes         text[]      NOT NULL,
    fallback_applied     boolean     NOT NULL,
    evaluated_at         timestamptz NOT NULL,
    CONSTRAINT policy_evaluation_pk PRIMARY KEY (id),
    CONSTRAINT policy_evaluation_once_per_assessment UNIQUE (assessment_id),
    CONSTRAINT policy_evaluation_assessment_fk FOREIGN KEY (assessment_id) REFERENCES credit.credit_assessment (id),
    CONSTRAINT policy_evaluation_policy_fk FOREIGN KEY (policy_version_id) REFERENCES credit.credit_policy_version (id),
    CONSTRAINT policy_evaluation_engine_is_positive CHECK (engine_version >= 1),
    CONSTRAINT policy_evaluation_outcome_is_known CHECK (outcome IN ('APPROVE', 'REFER', 'DECLINE', 'HARD_DECLINE')),
    CONSTRAINT policy_evaluation_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT policy_evaluation_requested_positive CHECK (requested_minor > 0),
    CONSTRAINT policy_evaluation_codes_shape CHECK (array_position(reason_codes, NULL) IS NULL),
    -- INV-CRD-02: an adverse outcome is explained.
    CONSTRAINT policy_evaluation_adverse_has_a_reason CHECK (outcome = 'APPROVE' OR cardinality(reason_codes) >= 1),
    -- An approval, and only an approval, approves an amount - positive, at most the request; below it, one code says why.
    CONSTRAINT policy_evaluation_approval_coherent CHECK (
        (outcome = 'APPROVE' AND approved_minor IS NOT NULL AND approved_minor > 0 AND approved_minor <= requested_minor
            AND cardinality(reason_codes) = CASE WHEN approved_minor < requested_minor THEN 1 ELSE 0 END)
        OR (outcome <> 'APPROVE' AND approved_minor IS NULL)),
    -- INV-CRD-10: the unavailable fallback refers or declines, never approves.
    CONSTRAINT policy_evaluation_fallback_never_approves CHECK (
        NOT fallback_applied OR (outcome IN ('REFER', 'DECLINE') AND 'CRD-SOURCE-UNAVAILABLE' = ANY (reason_codes)))
);

CREATE INDEX policy_evaluation_by_decision_request ON credit.policy_evaluation (decision_request_id);

COMMENT ON TABLE credit.policy_evaluation IS
    'What a pinned credit policy concluded about one assessment under the pinned engine version (P10-TSK-013): the outcome, an approval''s amount, the ordered reason codes and whether the unavailable fallback decided - a record distinct from the decision (INV-CRD-04). Born once per assessment (UNIQUE), never updated, deleted or truncated by any role. CONFIDENTIAL.';

CREATE OR REPLACE FUNCTION credit.policy_evaluation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a policy evaluation is never updated, deleted or truncated (P10-TSK-013, INV-CRD-06)';
    END IF;
    IF cardinality(NEW.reason_codes) <> (SELECT count(DISTINCT c) FROM unnest(NEW.reason_codes) AS c) THEN
        RAISE EXCEPTION 'a policy evaluation names each reason code once (P10-TSK-013)';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(NEW.reason_codes) AS c
                WHERE NOT EXISTS (SELECT 1 FROM credit.reason_code r WHERE r.code = c)) THEN
        RAISE EXCEPTION 'a policy evaluation''s reason codes are the catalogue''s (P10-TSK-013, INV-CRD-02)';
    END IF;
    NEW.evaluated_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER policy_evaluation_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.policy_evaluation
    FOR EACH ROW
    EXECUTE FUNCTION credit.policy_evaluation_is_born_once();

CREATE TRIGGER policy_evaluation_is_never_truncated
    BEFORE TRUNCATE ON credit.policy_evaluation
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.policy_evaluation_is_born_once();

-- ------------------------------------------------------------------ every rule's result

CREATE TABLE credit.policy_evaluation_rule (
    evaluation_id  uuid    NOT NULL,
    ordinal        integer NOT NULL,
    rule_code      text    NOT NULL,
    effect         text    NOT NULL,
    triggered      boolean NOT NULL,
    assessed       boolean NOT NULL,
    CONSTRAINT policy_evaluation_rule_pk PRIMARY KEY (evaluation_id, ordinal),
    CONSTRAINT policy_evaluation_rule_code_unique UNIQUE (evaluation_id, rule_code),
    CONSTRAINT policy_evaluation_rule_evaluation_fk FOREIGN KEY (evaluation_id) REFERENCES credit.policy_evaluation (id),
    CONSTRAINT policy_evaluation_rule_ordinal_positive CHECK (ordinal >= 1),
    CONSTRAINT policy_evaluation_rule_code_shape CHECK (rule_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT policy_evaluation_rule_effect_is_known CHECK (effect IN ('HARD_DECLINE', 'DECLINE', 'REFER', 'CAP_AMOUNT')),
    -- A rule that could not read its subject did not trigger.
    CONSTRAINT policy_evaluation_rule_triggered_was_assessed CHECK (assessed OR NOT triggered)
);

COMMENT ON TABLE credit.policy_evaluation_rule IS
    'Every rule''s result in one policy evaluation (P10-TSK-013): ordinal, code, effect, whether it was assessed and whether it triggered. Born with its evaluation in the evaluating transaction; append-only, never updated, deleted or truncated. CONFIDENTIAL.';

-- The rule results join their evaluation in the evaluation's own transaction, or not at all - V006's band trigger in
-- shape: xmin is the inserting transaction's 32-bit id, compared modulo 2^32 with the epoch-extended current one.
CREATE OR REPLACE FUNCTION credit.policy_evaluation_rule_is_born_with_its_evaluation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a policy evaluation''s rule results are append-only (P10-TSK-013)';
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM credit.policy_evaluation e
             WHERE e.id = NEW.evaluation_id
               AND e.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) THEN
        RAISE EXCEPTION 'a rule result is born with its evaluation, in the evaluating transaction (P10-TSK-013)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER policy_evaluation_rule_is_born_with_its_evaluation
    BEFORE INSERT OR UPDATE OR DELETE ON credit.policy_evaluation_rule
    FOR EACH ROW
    EXECUTE FUNCTION credit.policy_evaluation_rule_is_born_with_its_evaluation();

CREATE TRIGGER policy_evaluation_rule_is_never_truncated
    BEFORE TRUNCATE ON credit.policy_evaluation_rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.policy_evaluation_is_born_once();

GRANT SELECT, INSERT ON credit.policy_evaluation TO finapp_app;
GRANT SELECT, INSERT ON credit.policy_evaluation_rule TO finapp_app;
