-- The decision override's machine (P13-TSK-012; PHASE_13_PLAN.md sections 4, 5, 7.1 T-ovr and T-act, 9, 11, 12.3; C7;
-- RISK_LIFECYCLES.md section 4.2a; ADR-0108 decision 7, ADR-0112; INV-RSK-02, INV-RSK-04, INV-RSK-07, INV-AUD-04,
-- INV-IDEM-01). V007 created decision_override with every UPDATE refused; this migration gives it the machine
-- PROPOSED -> APPROVED | REJECTED and the record of the action an approval issues. V007 is applied history and is never
-- edited: its function is REPLACED by name here (its two triggers keep calling it), its CHECKs and indexes stand.
--
-- WHAT AN OVERRIDE IS
--   A second, human judgement recorded BESIDE a sealed assessment, never in it - the assessment stays DECIDED and sealed
--   for every writer (V007), so the decision remains explainable from its own rows. The proposal names its EFFECT,
--   frozen from birth, judged here against the assessment it overrides:
--
--     WITHDRAWAL   + HOLD           RELEASE_HELD_WITHDRAWAL | DENY_HELD_WITHDRAWAL   payments' keyed doors (P13-TSK-009)
--     CARD_PAYMENT + HOLD           CAPTURE_HELD_PAYMENT    | VOID_HELD_PAYMENT      payments' keyed doors (P13-TSK-010)
--     any          + DENY | REVIEW  RESUBMISSION - nothing dispatched: a refused command is terminal in its owner's
--                                   machine, and the customer's resubmission is judged afresh (the one-time
--                                   allowance of the plan is not built - DESIGN-012 D4, an owner item)
--
--   An ALLOW (the money moved) and a CHALLENGE (the customer's to satisfy) are never overridden - V007's birth rule,
--   kept. A dispatchable effect's target is the judged command itself: the withdrawal's or the payment intent's id, which
--   is the assessment's command ref.
--
-- FOUR EYES, DERIVED HERE TOO (C7)
--   requires_second_person is fixed at the proposal; the birth refuses FALSE where C7 demands a second RISK_OVERRIDE
--   holder: the decision DENY, or the judged amount at or above the pinned version's override threshold - and, failing
--   closed, wherever the threshold cannot be compared (no pinned version, another currency or scale). V007's
--   decision_override_four_eyes CHECK (approver <> proposer where it is required) is live from this migration on, since
--   the application may now UPDATE; it holds every writer alone.
--
-- THE ISSUED ACTION (T-act's shape before P13-TSK-017's machine)
--   An approval records the action it issues - action = effect - and, for a dispatchable one, a PERMIT (action_due_at,
--   the approval's instant plus the caller's lease) and its first attempt. The dispatch runs AFTER the approval commits,
--   holding no risk row, through payments' own door under the key risk.action:<override id>; its answer is recorded by a
--   conditional edge (action_outcome NULL -> DONE | ALREADY | REFUSED), which clears the permit and stamps
--   action_concluded_at. A crash between the commit and the record leaves the permit due: the approval door's retry or
--   the re-drive leg (RiskMonitoringSchedule, SKIP LOCKED on the permit, re-stamping it and counting an attempt) re-drives
--   the SAME key, and payments' idempotency makes it one effect. A concluded action never moves; a RESUBMISSION issues
--   nothing and carries no permit.
--
-- The grants: a column-scoped UPDATE of the decision and the action's columns; the instants are the trigger's; no
-- DELETE for any role. The migration names no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The effect, its target and the issued action's permit. The table is empty by construction (no writer existed
--    before this machine), so effect is NOT NULL from birth; a non-empty table fails this statement loudly.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.decision_override
    ADD COLUMN effect              text        NOT NULL,
    ADD COLUMN target_id           uuid,
    ADD COLUMN action_due_at       timestamptz,
    ADD COLUMN action_attempts     integer     NOT NULL DEFAULT 0,
    ADD COLUMN action_concluded_at timestamptz;

ALTER TABLE risk.decision_override
    -- OverrideEffect: the four action kinds an override issues through payments (ADR-0112) and the resubmission.
    ADD CONSTRAINT decision_override_effect_is_known CHECK (effect IN ('RELEASE_HELD_WITHDRAWAL',
        'DENY_HELD_WITHDRAWAL', 'CAPTURE_HELD_PAYMENT', 'VOID_HELD_PAYMENT', 'RESUBMISSION')),

    -- An approval issues exactly the effect proposed - nothing else, ever.
    ADD CONSTRAINT decision_override_action_is_the_effect CHECK (action IS NULL OR action = effect),

    -- A dispatchable effect names the judged command it acts on; a resubmission names none.
    ADD CONSTRAINT decision_override_target_coherent CHECK ((effect = 'RESUBMISSION') = (target_id IS NULL)),

    -- ActionOutcome: what the owning domain answered - an outcome, never an error.
    ADD CONSTRAINT decision_override_outcome_is_known CHECK (
        action_outcome IS NULL OR action_outcome IN ('DONE', 'ALREADY', 'REFUSED')),

    -- A permit only while an approved, dispatchable action awaits its outcome.
    ADD CONSTRAINT decision_override_permit_coherent CHECK (
        action_due_at IS NULL OR (status = 'APPROVED' AND effect <> 'RESUBMISSION' AND action_outcome IS NULL)),

    -- An approved dispatchable action is awaiting (a permit) or concluded (an outcome) - exactly one.
    ADD CONSTRAINT decision_override_issued_awaits_or_concludes CHECK (
        status <> 'APPROVED' OR effect = 'RESUBMISSION' OR ((action_due_at IS NULL) <> (action_outcome IS NULL))),

    -- The conclusion's instant exactly beside its outcome.
    ADD CONSTRAINT decision_override_conclusion_coherent CHECK ((action_concluded_at IS NULL) = (action_outcome IS NULL)),

    ADD CONSTRAINT decision_override_attempts_bounded CHECK (action_attempts BETWEEN 0 AND 1000000);

-- One STANDING override per assessment - a live proposal or an approved override: a rejected proposal may be followed by
-- a new one, an approved one may not (its effect stands). V007's decision_override_one_live (PROPOSED) stays beside it.
CREATE UNIQUE INDEX decision_override_one_standing ON risk.decision_override (assessment_id)
    WHERE status IN ('PROPOSED', 'APPROVED');

-- The re-drive leg's claim: the due permits, oldest first.
CREATE INDEX decision_override_action_due ON risk.decision_override (action_due_at, id) WHERE action_due_at IS NOT NULL;

COMMENT ON TABLE risk.decision_override IS
    'An override of a DENY, HOLD or REVIEW risk decision (the table: P13-TSK-004; the machine PROPOSED -> APPROVED | REJECTED and the issued action: P13-TSK-012; ADR-0108 decision 7, ADR-0112, C7): never an edit of the assessment, which stays DECIDED and sealed; one standing override per assessment; the effect frozen at the proposal and judged against the assessment; a second RISK_OVERRIDE holder where requires_second_person (derived from C7 at birth), by CHECK; the action dispatched after the approval commits through payments'' keyed door (risk.action:<id>), its outcome recorded once. Never deleted.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The machine - V007's function replaced by name.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION risk.decision_override_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    judged   record;
    admitted text[];
    demanded boolean;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'a decision override is never deleted or truncated (P13-TSK-004)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.action IS NOT NULL
                OR NEW.action_outcome IS NOT NULL OR NEW.action_due_at IS NOT NULL OR NEW.action_attempts <> 0
                OR NEW.action_concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a decision override is born PROPOSED, undecided and issuing nothing (P13-TSK-012)'
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT a.operation, a.decision, a.command_ref, a.amount_minor, a.currency, a.scale,
               p.override_threshold_minor, p.currency::text AS policy_currency, p.scale AS policy_scale
          INTO judged
          FROM risk.risk_assessment a
          LEFT JOIN risk.risk_policy p ON p.policy_version_id = a.policy_version_id
         WHERE a.id = NEW.assessment_id;
        IF NOT FOUND OR judged.decision NOT IN ('DENY', 'HOLD', 'REVIEW') THEN
            -- An ALLOW is never overridden - the money has moved, a case is the remedy (OQ-5); a CHALLENGE is the
            -- customer's to satisfy.
            RAISE EXCEPTION 'only a DENY, HOLD or REVIEW decision is overridden (ADR-0108 decision 7)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- OverrideEffect.of: a HOLD is resolved through its owner's door; a refusal is terminal - a resubmission.
        IF judged.decision = 'HOLD' AND judged.operation = 'WITHDRAWAL' THEN
            admitted := ARRAY['RELEASE_HELD_WITHDRAWAL', 'DENY_HELD_WITHDRAWAL'];
        ELSIF judged.decision = 'HOLD' AND judged.operation = 'CARD_PAYMENT' THEN
            admitted := ARRAY['CAPTURE_HELD_PAYMENT', 'VOID_HELD_PAYMENT'];
        ELSIF judged.decision IN ('DENY', 'REVIEW') THEN
            admitted := ARRAY['RESUBMISSION'];
        ELSE
            admitted := ARRAY[]::text[];
        END IF;
        IF NOT (NEW.effect = ANY (admitted)) THEN
            RAISE EXCEPTION 'the override''s effect is not one the overridden decision admits (P13-TSK-012)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.effect <> 'RESUBMISSION' AND NEW.target_id::text IS DISTINCT FROM judged.command_ref THEN
            RAISE EXCEPTION 'an override''s action targets the judged command itself (P13-TSK-012)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- C7, failing closed: a second holder for a DENY, at or above the pinned threshold, or wherever the threshold
        -- cannot be compared with the judged amount.
        demanded := COALESCE(judged.decision = 'DENY'
            OR judged.override_threshold_minor IS NULL
            OR judged.currency <> judged.policy_currency
            OR judged.scale <> judged.policy_scale
            OR judged.amount_minor >= judged.override_threshold_minor, true);
        IF demanded AND NOT NEW.requires_second_person THEN
            RAISE EXCEPTION 'C7: this override needs a second RISK_OVERRIDE holder (INV-RSK-07)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The proposal is frozen for every edge.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.assessment_id IS DISTINCT FROM OLD.assessment_id
            OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.requires_second_person IS DISTINCT FROM OLD.requires_second_person
            OR NEW.effect IS DISTINCT FROM OLD.effect OR NEW.target_id IS DISTINCT FROM OLD.target_id
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an override''s proposal never changes (P13-TSK-012)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'PROPOSED' THEN
        -- The decision: by a person, with a reason, stamped by the database after the lock.
        IF NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
            RAISE EXCEPTION 'a proposed override is approved or rejected' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.decided_by IS NULL OR NEW.decided_by_type IS NULL OR NEW.decision_reason IS NULL THEN
            RAISE EXCEPTION 'an override is decided by a person, with a reason' USING ERRCODE = 'check_violation';
        END IF;
        NEW.decided_at := clock_timestamp();
        IF NEW.action_outcome IS NOT NULL OR NEW.action_concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'no action is concluded by its own decision' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status = 'REJECTED' THEN
            IF NEW.action IS NOT NULL OR NEW.action_due_at IS NOT NULL OR NEW.action_attempts <> 0 THEN
                RAISE EXCEPTION 'a rejection issues nothing - the decision stands' USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.action IS DISTINCT FROM NEW.effect THEN
            RAISE EXCEPTION 'an approval issues exactly the effect proposed' USING ERRCODE = 'check_violation';
        ELSIF NEW.effect = 'RESUBMISSION' THEN
            IF NEW.action_due_at IS NOT NULL OR NEW.action_attempts <> 0 THEN
                RAISE EXCEPTION 'a resubmission dispatches nothing' USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.action_due_at IS NULL OR NEW.action_attempts <> 1 THEN
            RAISE EXCEPTION 'an issued action awaits its outcome under a permit, its first attempt the approval''s'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'APPROVED' AND OLD.action_due_at IS NOT NULL THEN
        -- The issued action's progress: the permit re-stamped and an attempt counted by a re-drive, or the outcome
        -- recorded once - and nothing of the decision.
        IF NEW.status IS DISTINCT FROM OLD.status OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
                OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.action IS DISTINCT FROM OLD.action THEN
            RAISE EXCEPTION 'an approved override''s decision never changes' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.action_attempts < OLD.action_attempts THEN
            RAISE EXCEPTION 'an action''s attempts only count up' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.action_outcome IS NOT NULL THEN
            NEW.action_due_at := NULL;
            NEW.action_concluded_at := clock_timestamp();
        ELSIF NEW.action_due_at IS NULL OR NEW.action_concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'an awaiting action keeps its permit until its outcome' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    -- REJECTED, a concluded action, a resubmission: nothing moves any more.
    RAISE EXCEPTION 'a decided override never moves (P13-TSK-012)' USING ERRCODE = 'check_violation';
END;
$$;

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The grants: the decision and the action's columns - the instants are the trigger's. Still no DELETE.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT UPDATE (status, decided_by, decided_by_type, decision_reason, action, action_outcome, action_due_at,
    action_attempts) ON risk.decision_override TO finapp_app;
