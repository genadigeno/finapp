-- Credit's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   16 function(s), 27 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V003, V004, V005,
--   V006, V007, V008, V009, V010, V011, V013, V014, V015, V022, the last definition of each. CURRENT_TIMESTAMP,
--   CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions, and are never resolved through a path.
--
-- WHAT IT DOES NOT CHANGE
--   No table, column, row or grant, and no existing trigger. It moves no money. No judgement on any path a production
--   role has: finapp_app holds no TRUNCATE and no CREATE on any schema, and the pg_catalog clocks are the ones its
--   path already resolved to. CREATE OR REPLACE keeps each function's OID, so every trigger stays bound.
--
-- WHAT IT DOES NOT CLAIM
--   A trigger is an object its owner can drop or disable, and a body can be replaced: this closes the unreviewed
--   path, a TRUNCATE that happens to be run or a role's path, not a deliberate migration, which is reviewed and
--   recorded in the history.

-- credit.credit_assessment_is_born_once: V007's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit assessment is never updated, deleted or truncated (P10-TSK-011, INV-CRD-06)';
    END IF;
    NEW.assessed_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.credit_decision_is_born_once: V011's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit decision is never updated, deleted or truncated - by any role (P10-TSK-016, INV-CRD-02)';
    END IF;
    NEW.decided_at := pg_catalog.statement_timestamp();
    NEW.valid_until := NEW.decided_at + NEW.decision_validity;
    RETURN NEW;
END;
$$;

-- credit.credit_evidence_is_born_once: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_evidence_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'credit evidence is never updated or deleted (P10-TSK-006, INV-HIST-02)';
    END IF;
    NEW.recorded_at := pg_catalog.statement_timestamp();
    NEW.retain_until := NEW.recorded_at + make_interval(months => NEW.retention_months);
    RETURN NEW;
END;
$$;

-- credit.credit_policy_event_is_append_only: V008's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_policy_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit policy event is append-only (P10-TSK-012)';
    END IF;
    NEW.occurred_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- credit.credit_policy_permits_only_machine_edges: V022's definition, 4 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.transaction_timestamp();
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
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.plan_stress_factor_bps IS DISTINCT FROM OLD.plan_stress_factor_bps THEN
        RAISE EXCEPTION 'a credit policy version''s identity and parameters are frozen (P10-TSK-012, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a credit policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := pg_catalog.transaction_timestamp();
        -- X-TSK-017: never before the product's latest end - its predecessor's, retired first in this transaction.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(pg_catalog.transaction_timestamp(),
                (SELECT max(v.effective_to) FROM credit.credit_policy_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-012)';
        END IF;
        -- X-TSK-017: never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(pg_catalog.transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- credit.credit_profile_is_born_once: V003's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_profile_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit profile is a lock target with nothing to change: it is never updated, deleted or truncated (P10-TSK-004)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.credit_record_is_born_once: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.credit_record_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit record is never updated or deleted (P10-TSK-006, INV-CRD-07)';
    END IF;
    IF TG_TABLE_NAME = 'credit_record' THEN
        NEW.recorded_at := pg_catalog.statement_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

-- credit.data_request_permits_only_machine_edges: V015's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.data_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' OR NEW.attempts <> 0 OR NEW.unavailable_reported THEN
            RAISE EXCEPTION 'a data request is born REQUESTED, unasked and unreported (P10-TSK-006)';
        END IF;
        -- P10-TST-001: never at or before an earlier data request of the same decision request, whatever the clock says.
        NEW.requested_at := GREATEST(pg_catalog.statement_timestamp(), (SELECT max(d.requested_at) + interval '1 microsecond'
                FROM credit.data_request d WHERE d.decision_request_id = NEW.decision_request_id));
        NEW.deadline_at := NEW.requested_at + NEW.collection_window;
        NEW.next_attempt_at := NEW.requested_at + NEW.retry_cadence;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a data request is never deleted (INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.source_kind IS DISTINCT FROM OLD.source_kind
            OR NEW.provider_code IS DISTINCT FROM OLD.provider_code
            OR NEW.request_reference IS DISTINCT FROM OLD.request_reference
            OR NEW.retry_cadence IS DISTINCT FROM OLD.retry_cadence
            OR NEW.collection_window IS DISTINCT FROM OLD.collection_window
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
            OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
        RAISE EXCEPTION 'a data request''s identity, reference, cadence, window and deadline are frozen';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'a data request''s attempts only grow';
    END IF;
    IF OLD.unavailable_reported AND NOT NEW.unavailable_reported THEN
        RAISE EXCEPTION 'a reported data request stays reported';
    END IF;
    IF NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('REQUESTED', 'RECEIVED', 'UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status IN ('UNAVAILABLE', 'CONSENT_WITHDRAWN'))
            OR (OLD.status = 'UNAVAILABLE' AND NEW.status = 'REQUESTED' AND pg_catalog.statement_timestamp() < OLD.deadline_at)) THEN
        RAISE EXCEPTION 'a data request cannot move from % to %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- credit.decision_request_event_is_append_only: V010's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.decision_request_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a decision request''s history is append-only (P10-TSK-014, INV-LIFE-02)';
    END IF;
    NEW.occurred_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.decision_request_permits_only_machine_edges: V022's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.decision_request_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    open_states CONSTANT text[] := ARRAY['SUBMITTED', 'COLLECTING', 'READY', 'EVALUATED', 'IN_REVIEW'];
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' OR NEW.closure_reason IS NOT NULL
                OR num_nonnulls(NEW.pinned_policy_version_id, NEW.pinned_model_version_id, NEW.pinned_engine_version) <> 0 THEN
            RAISE EXCEPTION 'a decision request is born SUBMITTED, open and unpinned (P10-TSK-014)';
        END IF;
        NEW.submitted_at := pg_catalog.statement_timestamp();
        NEW.expires_at := NEW.submitted_at + NEW.request_validity;
        NEW.next_step_at := NEW.submitted_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a decision request is never deleted (INV-HIST-01)';
    END IF;
    IF NOT (OLD.status = ANY (open_states)) THEN
        RAISE EXCEPTION 'a % decision request is terminal: it never changes again (INV-LIFE-02)', OLD.status;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.profile_id IS DISTINCT FROM OLD.profile_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.requested_minor IS DISTINCT FROM OLD.requested_minor
            OR NEW.term_months IS DISTINCT FROM OLD.term_months
            OR NEW.declared_income_minor IS DISTINCT FROM OLD.declared_income_minor
            OR NEW.declared_expenditure_minor IS DISTINCT FROM OLD.declared_expenditure_minor
            OR NEW.request_validity IS DISTINCT FROM OLD.request_validity
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
            OR NEW.origin IS DISTINCT FROM OLD.origin THEN
        RAISE EXCEPTION 'a decision request''s terms, origin, validity and correlation are frozen from submission (P10-TSK-014, P12-TSK-001)';
    END IF;
    IF OLD.pinned_policy_version_id IS NOT NULL THEN
        IF NEW.pinned_policy_version_id IS DISTINCT FROM OLD.pinned_policy_version_id
                OR NEW.pinned_model_version_id IS DISTINCT FROM OLD.pinned_model_version_id
                OR NEW.pinned_engine_version IS DISTINCT FROM OLD.pinned_engine_version THEN
            RAISE EXCEPTION 'a decision request''s pinned versions are written once (INV-HIST-04)';
        END IF;
    ELSIF NEW.pinned_policy_version_id IS NOT NULL
            AND NOT (OLD.status = 'SUBMITTED' AND NEW.status = 'COLLECTING') THEN
        RAISE EXCEPTION 'a decision request is pinned at SUBMITTED -> COLLECTING, and only there (P10-TSK-014)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status IN ('COLLECTING', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'COLLECTING' AND NEW.status IN ('READY', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'READY' AND NEW.status IN ('COLLECTING', 'EVALUATED', 'CANCELLED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('DECIDED', 'EXPIRED', 'ABANDONED'))) THEN
        RAISE EXCEPTION 'a decision request cannot move from % to % (INV-LIFE-01)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'EXPIRED' AND OLD.expires_at > pg_catalog.statement_timestamp() THEN
        RAISE EXCEPTION 'a decision request expires only at or after its expiry (P10-TSK-014)';
    END IF;
    -- The system's complementary conditionals: neither a decision nor a referral once expired.
    IF OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW') AND OLD.expires_at <= pg_catalog.statement_timestamp() THEN
        RAISE EXCEPTION 'an expired decision request is not decided or referred by the system (P10-TSK-014, -018)';
    END IF;
    IF NEW.status = 'EVALUATED' AND NOT EXISTS (
            SELECT 1 FROM credit.policy_evaluation e WHERE e.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is EVALUATED only beside its policy evaluation (P10-TSK-014)';
    END IF;
    IF NEW.status = 'DECIDED' AND NOT EXISTS (
            SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = NEW.id) THEN
        RAISE EXCEPTION 'a decision request is DECIDED only beside its credit decision (P10-TSK-016)';
    END IF;
    -- No case, no review (P10-TSK-018).
    IF NEW.status = 'IN_REVIEW' AND NOT EXISTS (
            SELECT 1 FROM credit.underwriting_case c WHERE c.decision_request_id = NEW.id AND c.status = 'OPEN') THEN
        RAISE EXCEPTION 'a decision request is IN_REVIEW only beside its open underwriting case (P10-TSK-018)';
    END IF;
    -- A taken case is decided by its person whatever the validity: only an OPEN case expires with its request.
    IF OLD.status = 'IN_REVIEW' AND NEW.status = 'EXPIRED' AND NOT EXISTS (
            SELECT 1 FROM credit.underwriting_case c WHERE c.decision_request_id = NEW.id AND c.status = 'OPEN') THEN
        RAISE EXCEPTION 'a request whose case is taken does not expire (P10-TSK-018, G8)';
    END IF;
    RETURN NEW;
END;
$$;

-- credit.decision_snapshot_is_born_once: V005's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.decision_snapshot_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a decision snapshot is never updated, deleted or truncated (P10-TSK-008, INV-CRD-07)';
    END IF;
    NEW.frozen_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.policy_evaluation_is_born_once: V009's definition, 1 clock call(s) qualified.
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
    NEW.evaluated_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.scorecard_model_event_is_append_only: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.scorecard_model_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a scorecard model event is append-only (P10-TSK-011)';
    END IF;
    NEW.occurred_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- credit.scorecard_model_permits_only_machine_edges: V014's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.scorecard_model_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.effective_from IS NOT NULL
                OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a scorecard model version is born PROPOSED and undecided: no model is activated by insert (P10-TSK-011)';
        END IF;
        NEW.proposed_at := pg_catalog.transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a scorecard model version is never deleted (P10-TSK-011)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.family IS DISTINCT FROM OLD.family
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.base_points IS DISTINCT FROM OLD.base_points
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a scorecard model version''s identity and content are frozen (P10-TSK-011, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a scorecard model version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-011)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := pg_catalog.transaction_timestamp();
        -- X-TSK-017: never before the family's latest end - its predecessor's, retired first in this transaction.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(pg_catalog.transaction_timestamp(),
                (SELECT max(v.effective_to) FROM credit.scorecard_model_version v WHERE v.family = NEW.family)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-011)';
        END IF;
        -- X-TSK-017: never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(pg_catalog.transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- credit.underwriting_case_event_is_append_only: V013's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.underwriting_case_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an underwriting case''s history is append-only (P10-TSK-018, INV-LIFE-02)';
    END IF;
    NEW.occurred_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- credit.underwriting_case_permits_only_machine_edges: V013's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION credit.underwriting_case_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    request_status  text;
    request_reason  text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an underwriting case is never deleted (P10-TSK-018, INV-HIST-01)';
    END IF;
    IF NEW.first_reason_codes IS NOT NULL THEN
        IF cardinality(NEW.first_reason_codes) <> (SELECT count(DISTINCT c) FROM unnest(NEW.first_reason_codes) AS c) THEN
            RAISE EXCEPTION 'a case''s decision names each reason code once (P10-TSK-018)';
        END IF;
        IF EXISTS (SELECT 1 FROM unnest(NEW.first_reason_codes) AS c
                    WHERE NOT EXISTS (SELECT 1 FROM credit.reason_code r WHERE r.code = c)) THEN
            RAISE EXCEPTION 'a case''s reason codes are the catalogue''s (P10-TSK-018, INV-CRD-11)';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'an underwriting case is born OPEN (P10-TSK-018)';
        END IF;
        IF NOT EXISTS (
                SELECT 1 FROM credit.decision_request r
                 WHERE r.id = NEW.decision_request_id AND r.status = 'EVALUATED' AND r.party_id = NEW.party_id
                   AND r.product = NEW.product AND r.currency = NEW.currency
                   AND r.requested_minor = NEW.requested_minor) THEN
            RAISE EXCEPTION 'a case is opened for an EVALUATED request, with its terms (P10-TSK-018)';
        END IF;
        IF NOT EXISTS (
                SELECT 1 FROM credit.policy_evaluation e
                 WHERE e.id = NEW.basis_evaluation_id AND e.decision_request_id = NEW.decision_request_id
                   AND e.outcome = 'REFER') THEN
            RAISE EXCEPTION 'a case''s basis is its request''s REFER evaluation (P10-TSK-018)';
        END IF;
        IF EXISTS (
                SELECT 1 FROM credit.policy_evaluation_rule r
                 WHERE r.evaluation_id = NEW.basis_evaluation_id AND r.effect = 'HARD_DECLINE' AND r.triggered) THEN
            RAISE EXCEPTION 'a hard decline is never referred, so no case holds one (P10-TSK-018, ADR-0089 point 2)';
        END IF;
        NEW.opened_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status IN ('DECIDED', 'CLOSED') THEN
        RAISE EXCEPTION 'a % underwriting case is terminal: it never changes again (INV-LIFE-02)', OLD.status;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.basis_evaluation_id IS DISTINCT FROM OLD.basis_evaluation_id
            OR NEW.requested_minor IS DISTINCT FROM OLD.requested_minor
            OR NEW.approvable_minor IS DISTINCT FROM OLD.approvable_minor
            OR NEW.four_eyes_threshold_minor IS DISTINCT FROM OLD.four_eyes_threshold_minor
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'a case''s request, basis, ceiling and threshold are frozen from its birth (P10-TSK-018)';
    END IF;
    IF NEW.status = OLD.status THEN
        RAISE EXCEPTION 'an underwriting case changes only by an edge of its machine (P10-TSK-018)';
    END IF;
    IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('ASSIGNED', 'CLOSED'))
            OR (OLD.status = 'ASSIGNED' AND NEW.status IN ('OPEN', 'AWAITING_SECOND', 'DECIDED', 'CLOSED'))
            OR (OLD.status = 'AWAITING_SECOND' AND NEW.status IN ('ASSIGNED', 'DECIDED', 'CLOSED'))) THEN
        RAISE EXCEPTION 'an underwriting case cannot move from % to % (INV-LIFE-01)', OLD.status, NEW.status;
    END IF;
    SELECT r.status, r.closure_reason INTO request_status, request_reason
      FROM credit.decision_request r WHERE r.id = NEW.decision_request_id;
    IF NEW.status = 'ASSIGNED' AND OLD.status = 'OPEN' AND request_status <> 'IN_REVIEW' THEN
        RAISE EXCEPTION 'a case is assigned only while its request is IN_REVIEW (P10-TSK-018)';
    END IF;
    IF NEW.status IN ('ASSIGNED', 'AWAITING_SECOND', 'DECIDED') AND OLD.status <> 'OPEN'
            AND NEW.assignee IS DISTINCT FROM OLD.assignee THEN
        RAISE EXCEPTION 'a taken case stays with its underwriter (P10-TSK-018)';
    END IF;
    -- The first decision: written at ASSIGNED -> AWAITING_SECOND | DECIDED by the assignee, carried unchanged to the
    -- second approval or a closure, and cleared only by a refused second approval.
    IF OLD.status = 'ASSIGNED' AND NEW.status IN ('AWAITING_SECOND', 'DECIDED') THEN
        IF NEW.first_decided_by IS DISTINCT FROM OLD.assignee THEN
            RAISE EXCEPTION 'a case is decided by its assignee (P10-TSK-018)';
        END IF;
        NEW.first_decided_at := pg_catalog.statement_timestamp();
    ELSIF NOT (OLD.status = 'AWAITING_SECOND' AND NEW.status = 'ASSIGNED')
            AND (NEW.first_outcome IS DISTINCT FROM OLD.first_outcome
                OR NEW.first_approved_minor IS DISTINCT FROM OLD.first_approved_minor
                OR NEW.first_reason_codes IS DISTINCT FROM OLD.first_reason_codes
                OR NEW.first_reason IS DISTINCT FROM OLD.first_reason
                OR NEW.first_decided_by IS DISTINCT FROM OLD.first_decided_by
                OR NEW.first_decided_at IS DISTINCT FROM OLD.first_decided_at) THEN
        RAISE EXCEPTION 'a case''s first decision is written once, at its decision (P10-TSK-018)';
    END IF;
    IF OLD.status = 'AWAITING_SECOND' AND NEW.status = 'DECIDED' THEN
        NEW.second_decided_at := pg_catalog.statement_timestamp();
    END IF;
    IF NEW.status = 'DECIDED' AND NOT (request_status = 'DECIDED' AND EXISTS (
            SELECT 1 FROM credit.credit_decision d WHERE d.decision_request_id = NEW.decision_request_id)) THEN
        RAISE EXCEPTION 'a case is DECIDED only beside its request''s decision (P10-TSK-018)';
    END IF;
    IF NEW.status = 'CLOSED' THEN
        IF OLD.status = 'OPEN' AND request_status NOT IN ('EXPIRED', 'ABANDONED') THEN
            RAISE EXCEPTION 'an open case closes only with its expired or abandoned request (P10-TSK-018, G8)';
        END IF;
        IF OLD.status <> 'OPEN' AND request_status <> 'ABANDONED' THEN
            RAISE EXCEPTION 'a taken case closes only with its abandoned request (P10-TSK-018, G8)';
        END IF;
        IF NEW.closure_reason IS DISTINCT FROM (CASE request_status WHEN 'EXPIRED' THEN 'EXPIRED' ELSE request_reason END) THEN
            RAISE EXCEPTION 'a closed case carries its request''s reason (P10-TSK-018, G8)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
