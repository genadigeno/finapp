-- Credit learns BNPL: the two products, the term shape, the request's origin and the BNPL consumer (P12-TSK-001;
-- PHASE_12_PLAN.md sections 2.1, 12.1 and 22, ADR-0102 point 6, ADR-0091; INV-CRD-02, INV-CRD-09, INV-CRD-12,
-- INV-LND-06 generalised, INV-BNPL-03, INV-IDEM-01, INV-HIST-01).
--
-- Every vocabulary below is restated from its Java enum, and CreditMigrationTest holds each CHECK to it:
--
--   1. THE PRODUCTS. BNPL_PAY_IN_4 and BNPL_PAY_IN_N join PERSONAL_LOAN and CREDIT_LINE in the product CHECKs of
--      data_request (V004), credit_policy_version (V008), decision_request (V010) and credit_decision (V011). The fifth,
--      underwriting_case (V013), is restated from the REFERABLE products only - still the two lending products: a BNPL
--      request is decided automatically only (CreditProduct.referable(), this task's design), so a BNPL underwriting
--      case is unrepresentable and a deciding transaction that tried to open one would roll back.
--   2. THE TERM SHAPE, per product (CreditProduct.TermShape): NONE (CREDIT_LINE) and FIXED_PLAN (BNPL_PAY_IN_4) carry no
--      term; MONTHS (PERSONAL_LOAN, BNPL_PAY_IN_N) carries one. The database holds the shape - the term's presence - as
--      V010 did for the loan; the bounds (6 to 60 months, the set {3, 6, 12}) are the domain's, judged at submission, so a
--      request recorded under older bounds still reads back. Restated on the request and on the decision's approval.
--   3. THE ORIGIN (RequestOrigin): decision_request.origin, CUSTOMER_DOOR or BNPL_APPLICATION, every existing row
--      CUSTOMER_DOOR (a column default, so no frozen row is touched by an UPDATE; the default then dropped, so every
--      writer names it), bound to the product's family by CHECK - a BNPL request only from the BNPL application, a
--      lending one only from the customer door - and frozen from submission by the machine trigger, restated from
--      V013's with that one column added.
--   4. THE FIXED PLAN'S STRESS FACTOR: credit_policy_version.plan_stress_factor_bps, present exactly for BNPL_PAY_IN_4's
--      versions, 10,000 (one instalment) to 40,000 (the whole plan) basis points of one; frozen with the version's other
--      parameters by the machine trigger, restated from V014's with that one column added (the application's column
--      UPDATE grant does not name it either). A BNPL version's unavailable-source fallback is DECLINE - never REFER.
--      No BNPL policy is seeded or activated here: two persons propose and activate one (the tests' fixtures only).
--   5. THE BNPL CONSUMER: credit_decision_consumption_kind_is_known admits BNPL_AGREEMENT beside LOAN_AGREEMENT; and
--      credit.consume_decision, restated from V021's, refuses a known kind taking up another family's decision (a loan
--      agreement a BNPL approval, or the reverse) with check_violation before any lock. Its signature, owner, SECURITY
--      DEFINER, pinned search_path, grants, the INSERT revoke and the only-through-the-port trigger are unchanged - the
--      trigger matches the function's frame by that unchanged signature.

-- ------------------------------------------------------------------ 1. the products

ALTER TABLE credit.data_request DROP CONSTRAINT data_request_product_is_known;
ALTER TABLE credit.data_request ADD CONSTRAINT data_request_product_is_known
    CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE', 'BNPL_PAY_IN_4', 'BNPL_PAY_IN_N'));

ALTER TABLE credit.credit_policy_version DROP CONSTRAINT credit_policy_product_is_offered;
ALTER TABLE credit.credit_policy_version ADD CONSTRAINT credit_policy_product_is_offered
    CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE', 'BNPL_PAY_IN_4', 'BNPL_PAY_IN_N'));

ALTER TABLE credit.decision_request DROP CONSTRAINT decision_request_product_is_known;
ALTER TABLE credit.decision_request ADD CONSTRAINT decision_request_product_is_known
    CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE', 'BNPL_PAY_IN_4', 'BNPL_PAY_IN_N'));

ALTER TABLE credit.credit_decision DROP CONSTRAINT credit_decision_product_is_known;
ALTER TABLE credit.credit_decision ADD CONSTRAINT credit_decision_product_is_known
    CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE', 'BNPL_PAY_IN_4', 'BNPL_PAY_IN_N'));

-- The referable products only: a BNPL request is never put to a person.
ALTER TABLE credit.underwriting_case DROP CONSTRAINT underwriting_case_product_is_known;
ALTER TABLE credit.underwriting_case ADD CONSTRAINT underwriting_case_product_is_known
    CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE'));

-- ------------------------------------------------------------------ 2. the term shape

ALTER TABLE credit.decision_request DROP CONSTRAINT decision_request_term_per_product;
ALTER TABLE credit.decision_request ADD CONSTRAINT decision_request_term_per_product CHECK (
    (product IN ('CREDIT_LINE', 'BNPL_PAY_IN_4') AND term_months IS NULL)
    OR (product IN ('PERSONAL_LOAN', 'BNPL_PAY_IN_N') AND term_months IS NOT NULL AND term_months >= 1));

-- An approval, and only an approval, approves an amount - positive, at most the request - and a term exactly for a
-- product whose term is in months.
ALTER TABLE credit.credit_decision DROP CONSTRAINT credit_decision_approval_coherent;
ALTER TABLE credit.credit_decision ADD CONSTRAINT credit_decision_approval_coherent CHECK (
    (outcome = 'APPROVED' AND approved_minor IS NOT NULL AND approved_minor > 0 AND approved_minor <= requested_minor
        AND ((product IN ('PERSONAL_LOAN', 'BNPL_PAY_IN_N')) = (term_months IS NOT NULL)))
    OR (outcome = 'DECLINED' AND approved_minor IS NULL AND term_months IS NULL));

-- ------------------------------------------------------------------ 3. the request's origin

ALTER TABLE credit.decision_request ADD COLUMN origin text NOT NULL DEFAULT 'CUSTOMER_DOOR';
ALTER TABLE credit.decision_request ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE credit.decision_request ADD CONSTRAINT decision_request_origin_is_known
    CHECK (origin IN ('CUSTOMER_DOOR', 'BNPL_APPLICATION'));
ALTER TABLE credit.decision_request ADD CONSTRAINT decision_request_origin_per_product CHECK (
    (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')) = (origin = 'BNPL_APPLICATION'));

COMMENT ON COLUMN credit.decision_request.origin IS
    'Where the request was opened (P12-TSK-001): CUSTOMER_DOOR (credit''s customer door, and lending''s application) for a lending product, BNPL_APPLICATION for a BNPL product - bound by CHECK, frozen from submission. CONFIDENTIAL.';

-- V013's machine trigger, verbatim but for the frozen origin.
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
        NEW.submitted_at := statement_timestamp();
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
    IF NEW.status = 'EXPIRED' AND OLD.expires_at > statement_timestamp() THEN
        RAISE EXCEPTION 'a decision request expires only at or after its expiry (P10-TSK-014)';
    END IF;
    -- The system's complementary conditionals: neither a decision nor a referral once expired.
    IF OLD.status = 'EVALUATED' AND NEW.status IN ('DECIDED', 'IN_REVIEW') AND OLD.expires_at <= statement_timestamp() THEN
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

-- ------------------------------------------------------------------ 4. the fixed plan's stress factor

ALTER TABLE credit.credit_policy_version ADD COLUMN plan_stress_factor_bps integer;
ALTER TABLE credit.credit_policy_version ADD CONSTRAINT credit_policy_plan_stress_per_product CHECK (
    (product = 'BNPL_PAY_IN_4') = (plan_stress_factor_bps IS NOT NULL)
    AND (plan_stress_factor_bps IS NULL OR plan_stress_factor_bps BETWEEN 10000 AND 40000));
-- A product decided automatically only never falls back to a referral.
ALTER TABLE credit.credit_policy_version ADD CONSTRAINT credit_policy_bnpl_never_refers CHECK (
    product NOT IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N') OR unavailable_fallback = 'DECLINE');

COMMENT ON COLUMN credit.credit_policy_version.plan_stress_factor_bps IS
    'A fixed plan''s affordability stress (P12-TSK-001): how many of its largest instalments a month carries, in basis points of one (30000 = three); BNPL_PAY_IN_4''s versions only. A threshold - CONFIDENTIAL.';

-- V014's machine trigger, verbatim but for the frozen stress factor.
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
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.plan_stress_factor_bps IS DISTINCT FROM OLD.plan_stress_factor_bps THEN
        RAISE EXCEPTION 'a credit policy version''s identity and parameters are frozen (P10-TSK-012, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a credit policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        -- X-TSK-017: never before the product's latest end - its predecessor's, retired first in this transaction.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM credit.credit_policy_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-012)';
        END IF;
        -- X-TSK-017: never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- ------------------------------------------------------------------ 5. the BNPL consumer

ALTER TABLE credit.credit_decision_consumption DROP CONSTRAINT credit_decision_consumption_kind_is_known;
ALTER TABLE credit.credit_decision_consumption ADD CONSTRAINT credit_decision_consumption_kind_is_known
    CHECK (consumer_kind IN ('LOAN_AGREEMENT', 'BNPL_AGREEMENT'));

-- V021's port, verbatim but for the product read beside the party and the family check (a') after it. CREATE OR REPLACE
-- keeps the owner, the EXECUTE grants and the absent PUBLIC grant; SECURITY DEFINER and the search_path are restated.
CREATE OR REPLACE FUNCTION credit.consume_decision(decision uuid, consumption uuid, kind text, ref uuid)
    RETURNS TABLE (answer text, consumption_id uuid, decision_valid_until timestamptz, held_by_kind text,
                   held_by_ref uuid)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_party       uuid;
    v_product     text;
    v_outcome     text;
    v_valid_until timestamptz;
    v_now         timestamptz;
    v_held_id     uuid;
    v_held_kind   text;
    v_held_ref    uuid;
BEGIN
    IF decision IS NULL OR consumption IS NULL OR kind IS NULL OR ref IS NULL THEN
        RAISE EXCEPTION 'a consumption names its decision, its own id and its consumer (P11-TSK-001)'
            USING ERRCODE = 'null_value_not_allowed';
    END IF;

    -- (a) The decision's party, read plain: the party is what the lock is on - and its product, which never changes.
    SELECT d.party_id, d.product INTO v_party, v_product FROM credit.credit_decision d WHERE d.id = decision;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOT_FOUND'::text, NULL::uuid, NULL::timestamptz, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- (a') A known kind takes up only its own family's decisions (P12-TSK-001): a loan agreement never a BNPL approval,
    -- a BNPL agreement never a loan's. The caller's defect, refused before any lock; an unknown kind falls through to
    -- the kind's CHECK at the insert.
    IF (kind = 'LOAN_AGREEMENT' AND v_product NOT IN ('PERSONAL_LOAN', 'CREDIT_LINE'))
            OR (kind = 'BNPL_AGREEMENT' AND v_product NOT IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')) THEN
        RAISE EXCEPTION 'a % does not take up a % decision (P12-TSK-001)', kind, v_product
            USING ERRCODE = 'check_violation';
    END IF;

    -- (b) The party's profile FOR UPDATE - lock-order element (1): a consumption and a decision for one party serialise.
    -- Re-entrant when the caller (the Java port) already holds it.
    PERFORM 1 FROM credit.credit_profile p WHERE p.party_id = v_party FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a decision''s party has a credit profile (credit_decision_profile_fk)';
    END IF;

    -- The database clock, read under the lock.
    v_now := clock_timestamp();

    -- (c) The decision re-read under the lock (it never changes - INV-CRD-02 - but it is judged here, after the lock).
    SELECT d.outcome, d.valid_until INTO v_outcome, v_valid_until FROM credit.credit_decision d WHERE d.id = decision;

    -- (d) Only an approval is consumable.
    IF v_outcome <> 'APPROVED' THEN
        RETURN QUERY SELECT 'NOT_APPROVED'::text, NULL::uuid, v_valid_until, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- (f) Already consumed: by this consumer - a replay - or by another.
    SELECT c.id, c.consumer_kind, c.consumer_ref INTO v_held_id, v_held_kind, v_held_ref
      FROM credit.credit_decision_consumption c WHERE c.decision_id = decision;
    IF FOUND THEN
        IF v_held_kind = kind AND v_held_ref = ref THEN
            RETURN QUERY SELECT 'REPLAYED'::text, v_held_id, v_valid_until, v_held_kind, v_held_ref;
        ELSE
            RETURN QUERY SELECT 'ALREADY_CONSUMED'::text, NULL::uuid, v_valid_until, v_held_kind, v_held_ref;
        END IF;
        RETURN;
    END IF;

    -- (e) Lapsed: the negation of the reservation's predicate, at the instant read under the lock.
    IF NOT credit.decision_reserves(v_valid_until, v_now) THEN
        RETURN QUERY SELECT 'LAPSED'::text, NULL::uuid, v_valid_until, NULL::text, NULL::uuid;
        RETURN;
    END IF;

    -- The fact, stamped with the instant judged under the lock - so consumed_at is after the lock was held and before
    -- valid_until, for every consumption (V011's trigger no longer re-stamps it: see below).
    INSERT INTO credit.credit_decision_consumption (id, decision_id, consumed_at, consumer_kind, consumer_ref)
        VALUES (consumption, decision, v_now, kind, ref);
    RETURN QUERY SELECT 'CONSUMED'::text, consumption, v_valid_until, kind, ref;
END;
$$;

COMMENT ON FUNCTION credit.consume_decision(uuid, uuid, text, uuid) IS
    'The only path to credit_decision_consumption (P11-TSK-001, ADR-0091; P12-TSK-001): under the party''s credit_profile row lock, an APPROVED decision still valid on the database clock read after the lock is consumed once, by one consumer of its own product family (LOAN_AGREEMENT a lending decision, BNPL_AGREEMENT a BNPL one); the same consumer replays. SECURITY DEFINER as credit''s owner, search_path pinned; EXECUTE to the application role only.';
