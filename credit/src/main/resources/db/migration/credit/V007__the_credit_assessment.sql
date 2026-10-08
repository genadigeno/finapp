-- The credit assessment (P10-TSK-011; ADR-0087 section 2, ADR-0088, ADR-0086 section 3; INV-CRD-06, INV-CRD-04,
-- INV-CRD-12, INV-HIST-04).
--
-- The three figures a decision's evaluation reads, computed from ONE snapshot and recorded once: affordability (engine
-- version 1's AffordabilityAssessment), exposure (ExposureAssessment) and the score (the pinned scorecard model). Each
-- figure is either assessed - every money term in the product's one currency, as minor units - or unassessable with
-- the attribute codes whose absence made it so; never a zero standing in for an absence. The inputs' references are
-- the snapshot itself (its id, UNIQUE, and its SHA-256) and the pinned policy, model and engine versions.
--
-- BORN ONCE PER SNAPSHOT: ten assessors of one snapshot leave one row (INSERT ... ON CONFLICT DO NOTHING, then read).
-- Never updated, deleted or truncated by any role; assessed_at is stamped by the database. The score is a figure of
-- the assessment, not a decision (INV-CRD-04).

CREATE TABLE credit.credit_assessment (
    id                        uuid        NOT NULL,
    snapshot_id               uuid        NOT NULL,
    decision_request_id       uuid        NOT NULL,
    snapshot_sha256           bytea       NOT NULL,
    policy_version_id         uuid        NOT NULL,
    model_version_id          uuid        NOT NULL,
    engine_version            integer     NOT NULL,
    currency                  char(3)     NOT NULL,
    affordability_assessed    boolean     NOT NULL,
    income_minor              bigint,
    expenditure_minor         bigint,
    obligations_minor         bigint,
    repayment_minor           bigint,
    disposable_minor          bigint,
    affordable                boolean,
    affordability_absent      text[]      NOT NULL,
    exposure_assessed         boolean     NOT NULL,
    exposure_minor            bigint,
    headroom_minor            bigint,
    within_limit              boolean,
    exposure_absent           text[]      NOT NULL,
    score                     integer     NOT NULL,
    assessed_at               timestamptz NOT NULL,
    CONSTRAINT credit_assessment_pk PRIMARY KEY (id),
    CONSTRAINT credit_assessment_once_per_snapshot UNIQUE (snapshot_id),
    CONSTRAINT credit_assessment_snapshot_fk FOREIGN KEY (snapshot_id) REFERENCES credit.decision_snapshot (id),
    CONSTRAINT credit_assessment_model_fk FOREIGN KEY (model_version_id) REFERENCES credit.scorecard_model_version (id),
    CONSTRAINT credit_assessment_engine_is_positive CHECK (engine_version >= 1),
    CONSTRAINT credit_assessment_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT credit_assessment_hash_shape CHECK (octet_length(snapshot_sha256) = 32),
    -- Assessed: every figure, nothing named absent. Unassessable: no figure, and what was absent named.
    CONSTRAINT credit_assessment_affordability_coherent CHECK (
        (affordability_assessed
            AND income_minor IS NOT NULL AND expenditure_minor IS NOT NULL AND obligations_minor IS NOT NULL
            AND repayment_minor IS NOT NULL AND disposable_minor IS NOT NULL AND affordable IS NOT NULL
            AND cardinality(affordability_absent) = 0)
        OR (NOT affordability_assessed
            AND income_minor IS NULL AND expenditure_minor IS NULL AND obligations_minor IS NULL
            AND repayment_minor IS NULL AND disposable_minor IS NULL AND affordable IS NULL
            AND cardinality(affordability_absent) >= 1)),
    CONSTRAINT credit_assessment_exposure_coherent CHECK (
        (exposure_assessed
            AND exposure_minor IS NOT NULL AND headroom_minor IS NOT NULL AND within_limit IS NOT NULL
            AND cardinality(exposure_absent) = 0)
        OR (NOT exposure_assessed
            AND exposure_minor IS NULL AND headroom_minor IS NULL AND within_limit IS NULL
            AND cardinality(exposure_absent) >= 1)),
    -- The disposable figure is its parts, exactly (INV-CRD-12).
    CONSTRAINT credit_assessment_disposable_is_its_parts CHECK (
        NOT affordability_assessed
        OR disposable_minor = income_minor - expenditure_minor - obligations_minor - repayment_minor),
    CONSTRAINT credit_assessment_codes_shape CHECK (
        array_position(affordability_absent, NULL) IS NULL AND array_position(exposure_absent, NULL) IS NULL)
);

COMMENT ON TABLE credit.credit_assessment IS
    'A decision''s three figures (P10-TSK-011): affordability, exposure and the score, from one snapshot (UNIQUE) under its pinned policy, model and engine versions - each assessed or named unassessable, never a silent zero. Born once per snapshot, never updated, deleted or truncated by any role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION credit.credit_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit assessment is never updated, deleted or truncated (P10-TSK-011, INV-CRD-06)';
    END IF;
    NEW.assessed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_assessment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_assessment
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_assessment_is_born_once();

CREATE TRIGGER credit_assessment_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_assessment
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_assessment_is_born_once();

GRANT SELECT, INSERT ON credit.credit_assessment TO finapp_app;
