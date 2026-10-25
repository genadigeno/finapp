-- Downstream actions through the owning domains (P13-TSK-017; PHASE_13_PLAN.md sections 3.1, 4, 5, 7.1 T-case and T-act,
-- 7.2 (R4, R6), 7.4, 7.5, 8, 9, 10, 11, 12.8, 14; RISK_LIFECYCLES.md section 4.7; ADR-0112; INV-RSK-02, INV-RSK-06,
-- INV-RSK-07, INV-AUD-04, INV-IDEM-01). The next free number at its build: the entry's V010 went to P13-TSK-015.
--
-- WHAT A RISK ACTION IS
--   The RECORD in risk of an EFFECT that belongs to another domain: suspending a party's identities, revoking their
--   sessions, suspending a merchant, releasing or denying a held withdrawal, capturing or voiding a held card payment.
--   Risk never writes the other half (INV-RSK-02): the effect is the owning domain's existing command, keyed
--   risk.action:<id>, audited by its owner as its own act with the platform as the executor. This table holds who
--   proposed it, who decided it, when T-act dispatched it, how many times, and what the owner answered.
--
-- THE MACHINE (risk_action_permits_only_machine_edges, every writer)
--   PROPOSED -> APPROVED (a second person) | REJECTED; APPROVED -> DISPATCHED (T-act's claim, committed before the owner
--   is called) -> DISPATCHED (a re-claim past the lease: the SAME key) | DONE (DONE or ALREADY) | FAILED (the owner's
--   refusal). Nothing moves out of DONE, FAILED or REJECTED: a failed action is re-proposable as a NEW action, four eyes
--   again, and is never re-dispatched by itself. A case's action is proposed and decided as steps of its case (the case
--   row FOR UPDATE, R4, and its revision); T-act holds this row alone (R6) and commits it before the owner works, so no
--   risk row is held during the owner's command.
--
-- THE KEY IS THE ID
--   A case's action's id is its ACTION_PROPOSED investigator action's (risk_action_proposed_on_its_case); an override's
--   action's id IS THE OVERRIDE'S (risk_action_source_coherent) - so every action P13-TSK-012 issued keeps the key
--   risk.action:<override id> it presented payments, across the fold below.
--
-- THE FOLD (P13-TSK-012's issued action, ADR-0112 decision 3)
--   decision_override held its issued action on its own row (action_outcome, action_due_at, action_attempts,
--   action_concluded_at). This migration copies every one into risk_action - an awaiting one DISPATCHED with its permit
--   and attempts (an attempt may have reached payments; the schedule re-drives it with the same key), a concluded one
--   DONE or FAILED with its outcome and instant - then drops those four columns (their constraints and the leg's index
--   named and dropped first), re-makes decision_override_action_shape for the action alone, and replaces the override's
--   machine function by name: an approval sets action = effect and nothing else, and decision_override_issues_its_action
--   (deferred) holds that an approved dispatchable override has its risk_action by commit. V007 and V009 are applied
--   history and are never edited.
--
-- WHAT A CASE MAY PROPOSE (ActionRules, mirrored by the trigger)
--   A party's case (FRAUD, ATO, AML): SUSPEND_IDENTITY, REINSTATE_IDENTITY, REVOKE_SESSIONS on the case's own party (C10:
--   never a credential reset - no such kind exists). A MERCHANT_ABUSE case: SUSPEND_MERCHANT on its own merchant. Any case
--   but COLLECTIONS HOLDING a held movement - its opening assessment, or one the system attached, sealed HOLD for a
--   WITHDRAWAL or a CARD_PAYMENT: its release or denial, its capture or void, on that very command. Nothing else: accounts'
--   suspension and crossborder's block have no owning command (unavailable), and RESCREEN_PARTY is P13-TSK-022's.
--
-- FOUR EYES (risk_action_four_eyes, every writer, provable alone)
--   A case's action is approved by someone other than its proposer (INV-RSK-07, INV-AUD-04). An override's action is
--   born APPROVED by the override's own approval, whose four eyes are decision_override_four_eyes' (C7).
--
-- A DISPOSITION WAITS FOR A STANDING PROPOSAL (risk_case_disposes_no_standing_action)
--   A case is not disposed while an action proposed on it awaits its decision - so no proposal is stranded on a case
--   that can no longer move. Approved actions dispatch and conclude after a disposition.
--
-- The stamps (proposed_at, decided_at, dispatched_at, concluded_at) are the trigger's clock_timestamp(); the permit is
-- the writer's clock_timestamp() + lease, judged by the trigger. The grants: SELECT and INSERT; a column-scoped UPDATE of
-- the machine's columns; no DELETE for any role. The migration names no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The action.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_action (
    id                  uuid        NOT NULL,
    source              text        NOT NULL,
    case_id             uuid,
    override_id         uuid,

    -- What it asks of whom: the kind, what it acts on, and the closed code the owner records.
    kind                text        NOT NULL,
    target_kind         text        NOT NULL,
    target_ref          uuid        NOT NULL,
    reason_code         text        NOT NULL,

    status              text        NOT NULL,

    -- The two persons: the proposer, and the decider with the case's act that decided it.
    proposed_by         text        NOT NULL,
    proposed_by_type    text        NOT NULL,
    proposed_at         timestamptz NOT NULL,
    decided_by          text,
    decided_by_type     text,
    decided_at          timestamptz,
    decision_action_id  uuid,

    -- T-act: the permit, the attempts, the first dispatch, the owner's answer.
    next_attempt_at     timestamptz,
    attempts            integer     NOT NULL DEFAULT 0,
    dispatched_at       timestamptz,
    outcome             text,
    concluded_at        timestamptz,

    correlation_id      text        NOT NULL,

    CONSTRAINT risk_action_pk PRIMARY KEY (id),

    CONSTRAINT risk_action_case_fk FOREIGN KEY (case_id) REFERENCES risk.risk_case (id),
    -- A case's action IS its ACTION_PROPOSED investigator action (the id, and so the key).
    CONSTRAINT risk_action_proposed_on_its_case
        FOREIGN KEY (id, case_id) REFERENCES risk.risk_case_action (id, case_id),
    CONSTRAINT risk_action_decided_on_its_case
        FOREIGN KEY (decision_action_id, case_id) REFERENCES risk.risk_case_action (id, case_id),
    CONSTRAINT risk_action_override_fk FOREIGN KEY (override_id) REFERENCES risk.decision_override (id),
    CONSTRAINT risk_action_one_per_override UNIQUE (override_id),

    -- The closed vocabularies, as ActionSource, ActionKind, ActionTarget, ActionStatus and ActionOutcome declare them
    -- (RiskActionMigrationTest holds each list to its enum).
    CONSTRAINT risk_action_source_is_known CHECK (source IN ('CASE', 'OVERRIDE')),
    CONSTRAINT risk_action_kind_is_known CHECK (kind IN ('SUSPEND_IDENTITY', 'REINSTATE_IDENTITY', 'REVOKE_SESSIONS',
        'SUSPEND_ACCOUNT', 'REINSTATE_ACCOUNT', 'SUSPEND_MERCHANT', 'BLOCK_BENEFICIARY', 'RELEASE_HELD_WITHDRAWAL',
        'DENY_HELD_WITHDRAWAL', 'CAPTURE_HELD_PAYMENT', 'VOID_HELD_PAYMENT', 'RESCREEN_PARTY')),
    CONSTRAINT risk_action_target_kind_is_known CHECK (target_kind IN ('PARTY', 'MERCHANT', 'ACCOUNT', 'BENEFICIARY',
        'WITHDRAWAL', 'PAYMENT_INTENT')),
    CONSTRAINT risk_action_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'DISPATCHED', 'DONE', 'FAILED',
        'REJECTED')),

    -- A kind acts on exactly its target kind (ActionKind.target()).
    CONSTRAINT risk_action_kind_targets CHECK (target_kind = CASE kind
        WHEN 'SUSPEND_IDENTITY' THEN 'PARTY'
        WHEN 'REINSTATE_IDENTITY' THEN 'PARTY'
        WHEN 'REVOKE_SESSIONS' THEN 'PARTY'
        WHEN 'SUSPEND_ACCOUNT' THEN 'ACCOUNT'
        WHEN 'REINSTATE_ACCOUNT' THEN 'ACCOUNT'
        WHEN 'SUSPEND_MERCHANT' THEN 'MERCHANT'
        WHEN 'BLOCK_BENEFICIARY' THEN 'BENEFICIARY'
        WHEN 'RELEASE_HELD_WITHDRAWAL' THEN 'WITHDRAWAL'
        WHEN 'DENY_HELD_WITHDRAWAL' THEN 'WITHDRAWAL'
        WHEN 'CAPTURE_HELD_PAYMENT' THEN 'PAYMENT_INTENT'
        WHEN 'VOID_HELD_PAYMENT' THEN 'PAYMENT_INTENT'
        WHEN 'RESCREEN_PARTY' THEN 'PARTY'
        END),

    -- A case's action names its case; an override's its override, under the override's own id - its key kept.
    CONSTRAINT risk_action_source_coherent CHECK (
        (source = 'CASE') = (case_id IS NOT NULL)
        AND (source = 'OVERRIDE') = (override_id IS NOT NULL)
        AND (override_id IS NULL OR override_id = id)
        AND (source = 'CASE' OR decision_action_id IS NULL)),

    -- An override issues payments' four held doors alone (ADR-0108, ADR-0112 decision 2).
    CONSTRAINT risk_action_override_kinds CHECK (source <> 'OVERRIDE' OR kind IN ('RELEASE_HELD_WITHDRAWAL',
        'DENY_HELD_WITHDRAWAL', 'CAPTURE_HELD_PAYMENT', 'VOID_HELD_PAYMENT')),

    -- The owner records a closed code - never a note (INV-RSK-08).
    CONSTRAINT risk_action_reason_code_shaped CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),

    -- A case's persons are authenticated humans: CUSTOMER-typed as the platform's session establishes every identity, or
    -- EMPLOYEE; never SYSTEM, SERVICE or MERCHANT (P13-TSK-016's rule).
    CONSTRAINT risk_action_by_persons CHECK (
        source <> 'CASE'
        OR (proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
            AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE')))),

    -- Decided exactly past PROPOSED, whole; a case's decision names the act of its case that decided it.
    CONSTRAINT risk_action_decided_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND num_nonnulls(decided_by, decided_by_type, decided_at) IN (0, 3)
        AND (source <> 'CASE' OR (decided_by IS NULL) = (decision_action_id IS NULL))),

    -- FOUR EYES, for every writer (INV-RSK-07, INV-AUD-04): a case's approved action was approved by someone other than
    -- its proposer. Deliberately not judged by the trigger, so the CHECK is provable alone.
    CONSTRAINT risk_action_four_eyes CHECK (
        source <> 'CASE' OR status IN ('PROPOSED', 'REJECTED') OR decided_by <> proposed_by),

    -- The permit exactly while T-act may claim it.
    CONSTRAINT risk_action_permit_coherent CHECK (
        (next_attempt_at IS NOT NULL) = (status IN ('APPROVED', 'DISPATCHED'))),

    -- An attempt is a claim: none before the approval, at least one once dispatched.
    CONSTRAINT risk_action_attempts_coherent CHECK (
        attempts BETWEEN 0 AND 1000000
        AND (status NOT IN ('PROPOSED', 'REJECTED') OR attempts = 0)
        AND (status NOT IN ('DISPATCHED', 'DONE', 'FAILED') OR attempts >= 1)),

    CONSTRAINT risk_action_dispatched_coherent CHECK (
        (dispatched_at IS NOT NULL) = (status IN ('DISPATCHED', 'DONE', 'FAILED'))),

    -- ActionOutcome, exactly when concluded: DONE is the owner's DONE or ALREADY; FAILED its refusal.
    CONSTRAINT risk_action_outcome_coherent CHECK (
        (outcome IS NULL) = (status NOT IN ('DONE', 'FAILED'))
        AND (outcome IS NULL OR outcome IN ('DONE', 'ALREADY', 'REFUSED'))
        AND (status <> 'DONE' OR outcome IN ('DONE', 'ALREADY'))
        AND (status <> 'FAILED' OR outcome = 'REFUSED')),

    CONSTRAINT risk_action_concluded_coherent CHECK ((concluded_at IS NULL) = (outcome IS NULL)),

    CONSTRAINT risk_action_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- ONE LIVE ACTION PER (case, kind, target) - INV-RSK-06: a replay, a second analyst or a double click proposes nothing
-- new while one is proposed, approved or dispatched; a DONE, FAILED or REJECTED one leaves it (re-proposable). An
-- override's action has no case, so it never meets this index (one per override by risk_action_one_per_override).
CREATE UNIQUE INDEX risk_action_one_live ON risk.risk_action (case_id, kind, target_ref)
    WHERE status IN ('PROPOSED', 'APPROVED', 'DISPATCHED');

-- T-act's claim: the due permits, oldest first (RiskActionSchedule, and the approval door's claim of its own action).
CREATE INDEX risk_action_due ON risk.risk_action (next_attempt_at, id) WHERE next_attempt_at IS NOT NULL;

-- A case's actions, oldest first; the disposition's check for a standing proposal.
CREATE INDEX risk_action_of_case ON risk.risk_action (case_id, proposed_at) WHERE case_id IS NOT NULL;

COMMENT ON TABLE risk.risk_action IS
    'A downstream risk action: the record in risk of an effect the owning domain performs through its existing command keyed risk.action:<id> (ADR-0112; INV-RSK-02). PROPOSED -> APPROVED (a second person, risk_action_four_eyes) | REJECTED; APPROVED -> DISPATCHED (T-act''s claim, committed before the owner works) -> DONE | FAILED (the owner''s answer, recorded once). A case''s action is its ACTION_PROPOSED investigator action''s id; an override''s is the override''s id (P13-TSK-012''s folded in, keys kept). One live per (case, kind, target). No money, no text. P13-TSK-017.';
COMMENT ON COLUMN risk.risk_action.next_attempt_at IS
    'The permit: clock_timestamp() + the lease when T-act claims it; a claim takes only a permit that has come, so ten instances claim one action once per lease and a re-claim presents the same key.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The fold: every action an approved override issued, copied under the override's own id.
-- ---------------------------------------------------------------------------------------------------------------------

INSERT INTO risk.risk_action (id, source, case_id, override_id, kind, target_kind, target_ref, reason_code, status,
    proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type, decided_at, decision_action_id,
    next_attempt_at, attempts, dispatched_at, outcome, concluded_at, correlation_id)
SELECT o.id, 'OVERRIDE', NULL, o.id, o.effect,
       CASE WHEN o.effect IN ('RELEASE_HELD_WITHDRAWAL', 'DENY_HELD_WITHDRAWAL') THEN 'WITHDRAWAL' ELSE 'PAYMENT_INTENT' END,
       o.target_id, o.reason_code,
       CASE WHEN o.action_outcome IS NULL THEN 'DISPATCHED'
            WHEN o.action_outcome = 'REFUSED' THEN 'FAILED'
            ELSE 'DONE' END,
       o.proposed_by, o.proposed_by_type, o.proposed_at, o.decided_by, o.decided_by_type, o.decided_at, NULL,
       CASE WHEN o.action_outcome IS NULL THEN COALESCE(o.action_due_at, clock_timestamp()) END,
       GREATEST(o.action_attempts, 1), o.decided_at, o.action_outcome, o.action_concluded_at, o.correlation_id
  FROM risk.decision_override o
 WHERE o.status = 'APPROVED' AND o.effect <> 'RESUBMISSION';

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The override's machine, replaced by name: an approval issues its action into risk_action, nothing on its own row
--    but the action's kind. V009's checks of the moved columns and the leg's index go with them.
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
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.action IS NOT NULL THEN
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
        IF NEW.status = 'REJECTED' AND NEW.action IS NOT NULL THEN
            RAISE EXCEPTION 'a rejection issues nothing - the decision stands' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status = 'APPROVED' AND NEW.action IS DISTINCT FROM NEW.effect THEN
            RAISE EXCEPTION 'an approval issues exactly the effect proposed' USING ERRCODE = 'check_violation';
        END IF;
        -- The issued action itself is risk_action's (P13-TSK-017): decision_override_issues_its_action holds it.
        RETURN NEW;
    END IF;

    -- APPROVED or REJECTED: nothing moves any more - the issued action's progress is its risk_action row's.
    RAISE EXCEPTION 'a decided override never moves (P13-TSK-012, P13-TSK-017)' USING ERRCODE = 'check_violation';
END;
$$;

ALTER TABLE risk.decision_override
    DROP CONSTRAINT decision_override_action_shape,
    DROP CONSTRAINT decision_override_outcome_is_known,
    DROP CONSTRAINT decision_override_permit_coherent,
    DROP CONSTRAINT decision_override_issued_awaits_or_concludes,
    DROP CONSTRAINT decision_override_conclusion_coherent,
    DROP CONSTRAINT decision_override_attempts_bounded;

DROP INDEX risk.decision_override_action_due;

ALTER TABLE risk.decision_override
    DROP COLUMN action_outcome,
    DROP COLUMN action_due_at,
    DROP COLUMN action_attempts,
    DROP COLUMN action_concluded_at;

-- V007's action shape, re-made for the action alone: a closed code, an approval's.
ALTER TABLE risk.decision_override
    ADD CONSTRAINT decision_override_action_shape CHECK (
        (action IS NULL OR action ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (action IS NULL OR status = 'APPROVED'));

-- An approved override that dispatches has its risk_action - under its own id, by commit (the approval inserts it in
-- the same transaction, after its own UPDATE).
CREATE FUNCTION risk.decision_override_issues_its_action() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND NEW.effect <> 'RESUBMISSION' AND NOT EXISTS (
            SELECT 1 FROM risk.risk_action a WHERE a.id = NEW.id AND a.override_id = NEW.id) THEN
        RAISE EXCEPTION 'an approved override issues its action into risk_action under its own id (P13-TSK-017)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER decision_override_issues_its_action
    AFTER UPDATE ON risk.decision_override
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'APPROVED' AND OLD.status = 'PROPOSED')
    EXECUTE FUNCTION risk.decision_override_issues_its_action();

COMMENT ON TABLE risk.decision_override IS
    'An override of a DENY, HOLD or REVIEW risk decision (the table: P13-TSK-004; the machine PROPOSED -> APPROVED | REJECTED: P13-TSK-012; ADR-0108 decision 7, ADR-0112, C7): never an edit of the assessment, which stays DECIDED and sealed; one standing override per assessment; the effect frozen at the proposal and judged against the assessment; a second RISK_OVERRIDE holder where requires_second_person (derived from C7 at birth), by CHECK. An approval issues its action into risk.risk_action under the override''s own id (P13-TSK-017: the action''s permit, attempts and outcome live there; the key is risk.action:<override id>). Never deleted.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. The machine.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_action_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    acted  record;
    issuer record;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: an action is a recorded decision and its effect's trail.
        RAISE EXCEPTION 'a risk action is never deleted or truncated (P13-TSK-017, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.source = 'CASE' THEN
            IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                    OR NEW.decided_at IS NOT NULL OR NEW.decision_action_id IS NOT NULL
                    OR NEW.next_attempt_at IS NOT NULL OR NEW.attempts <> 0 OR NEW.dispatched_at IS NOT NULL
                    OR NEW.outcome IS NOT NULL OR NEW.concluded_at IS NOT NULL THEN
                RAISE EXCEPTION 'a case''s action is born PROPOSED, undecided and unclaimed (P13-TSK-017)'
                    USING ERRCODE = 'check_violation';
            END IF;
            SELECT c.kind, c.subject_kind, c.subject_ref, c.status, c.opening_assessment_id
              INTO acted
              FROM risk.risk_case c
             WHERE c.id = NEW.case_id;
            IF NOT FOUND OR acted.status = 'DISPOSED' THEN
                RAISE EXCEPTION 'an action is proposed on a live case (P13-TSK-017)' USING ERRCODE = 'check_violation';
            END IF;
            IF NOT EXISTS (
                    SELECT 1 FROM risk.risk_case_action x
                     WHERE x.id = NEW.id AND x.case_id = NEW.case_id AND x.kind = 'ACTION_PROPOSED'
                       AND x.actor_id = NEW.proposed_by AND x.actor_type = NEW.proposed_by_type) THEN
                RAISE EXCEPTION 'a case''s action is its proposer''s ACTION_PROPOSED act (P13-TSK-017)'
                    USING ERRCODE = 'check_violation';
            END IF;
            -- What a case may propose, on what (ActionRules): never a target the case does not hold.
            IF NEW.kind IN ('SUSPEND_IDENTITY', 'REINSTATE_IDENTITY', 'REVOKE_SESSIONS') THEN
                IF acted.kind NOT IN ('FRAUD', 'ATO', 'AML') OR acted.subject_kind <> 'PARTY'
                        OR NEW.target_ref::text <> acted.subject_ref THEN
                    RAISE EXCEPTION 'an identity action acts on its party''s case''s own party (P13-TSK-017)'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.kind IN ('SUSPEND_MERCHANT') THEN
                IF acted.kind NOT IN ('MERCHANT_ABUSE') OR acted.subject_kind <> 'MERCHANT'
                        OR NEW.target_ref::text <> acted.subject_ref THEN
                    RAISE EXCEPTION 'a merchant action acts on its merchant-abuse case''s own merchant (P13-TSK-017)'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSIF NEW.kind IN ('RELEASE_HELD_WITHDRAWAL', 'DENY_HELD_WITHDRAWAL', 'CAPTURE_HELD_PAYMENT',
                    'VOID_HELD_PAYMENT') THEN
                IF acted.kind = 'COLLECTIONS' OR NOT EXISTS (
                        SELECT 1 FROM risk.risk_assessment a
                         WHERE a.decision = 'HOLD'
                           AND a.operation = CASE WHEN NEW.kind IN ('RELEASE_HELD_WITHDRAWAL', 'DENY_HELD_WITHDRAWAL')
                                                  THEN 'WITHDRAWAL' ELSE 'CARD_PAYMENT' END
                           AND a.command_ref = NEW.target_ref::text
                           AND (a.id = acted.opening_assessment_id OR a.id IN (
                                SELECT e.assessment_id FROM risk.risk_case_event e
                                 WHERE e.case_id = NEW.case_id AND e.assessment_id IS NOT NULL))) THEN
                    RAISE EXCEPTION 'a held movement''s action acts on a movement its case holds (P13-TSK-017)'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSE
                RAISE EXCEPTION 'this kind is not proposed on a case (P13-TSK-017)' USING ERRCODE = 'check_violation';
            END IF;
            NEW.proposed_at := clock_timestamp();
            RETURN NEW;
        END IF;

        -- An approved override's action: exactly what its approval issued, born APPROVED, due at once.
        SELECT o.status, o.effect, o.target_id, o.reason_code, o.proposed_by, o.proposed_by_type, o.proposed_at,
               o.decided_by, o.decided_by_type, o.decided_at
          INTO issuer
          FROM risk.decision_override o
         WHERE o.id = NEW.override_id;
        IF NOT FOUND OR issuer.status <> 'APPROVED' OR issuer.effect <> NEW.kind
                OR issuer.target_id IS DISTINCT FROM NEW.target_ref OR issuer.reason_code <> NEW.reason_code
                OR issuer.proposed_by <> NEW.proposed_by OR issuer.proposed_by_type <> NEW.proposed_by_type
                OR issuer.decided_by IS DISTINCT FROM NEW.decided_by
                OR issuer.decided_by_type IS DISTINCT FROM NEW.decided_by_type THEN
            RAISE EXCEPTION 'an override''s action is exactly what its approval issued (P13-TSK-017)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status <> 'APPROVED' OR NEW.attempts <> 0 OR NEW.dispatched_at IS NOT NULL OR NEW.outcome IS NOT NULL
                OR NEW.concluded_at IS NOT NULL OR NEW.next_attempt_at IS NULL THEN
            RAISE EXCEPTION 'an override''s action is born APPROVED under a permit, unclaimed (P13-TSK-017)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.proposed_at := issuer.proposed_at;
        NEW.decided_at := issuer.decided_at;
        NEW.next_attempt_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The proposal is frozen for every edge.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.source IS DISTINCT FROM OLD.source
            OR NEW.case_id IS DISTINCT FROM OLD.case_id OR NEW.override_id IS DISTINCT FROM OLD.override_id
            OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.target_kind IS DISTINCT FROM OLD.target_kind
            OR NEW.target_ref IS DISTINCT FROM OLD.target_ref OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a risk action''s proposal never changes (P13-TSK-017)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status IN ('DONE', 'FAILED', 'REJECTED') THEN
        -- Final: a failed action is re-proposed as a new action, never re-dispatched by itself.
        RAISE EXCEPTION 'a concluded or rejected risk action never moves (P13-TSK-017)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'PROPOSED' THEN
        -- The decision: a person, by an act of the action's live case, stamped by the database.
        IF NEW.status NOT IN ('APPROVED', 'REJECTED') OR NEW.decided_by IS NULL OR NEW.decided_by_type IS NULL
                OR NEW.decision_action_id IS NULL THEN
            RAISE EXCEPTION 'a proposed action is approved or rejected by a person''s act of its case'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM risk.risk_case c WHERE c.id = NEW.case_id AND c.status <> 'DISPOSED') THEN
            RAISE EXCEPTION 'an action is decided on a live case' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (
                SELECT 1 FROM risk.risk_case_action x
                 WHERE x.id = NEW.decision_action_id AND x.case_id = NEW.case_id
                   AND x.kind = CASE NEW.status WHEN 'APPROVED' THEN 'ACTION_APPROVED' ELSE 'ACTION_REJECTED' END
                   AND x.actor_id = NEW.decided_by AND x.actor_type = NEW.decided_by_type) THEN
            RAISE EXCEPTION 'an action''s decision is its decider''s ACTION_APPROVED or ACTION_REJECTED act'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts <> 0 OR NEW.dispatched_at IS NOT NULL OR NEW.outcome IS NOT NULL
                OR NEW.concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a decision claims nothing and concludes nothing' USING ERRCODE = 'check_violation';
        END IF;
        NEW.decided_at := clock_timestamp();
        IF NEW.status = 'APPROVED' THEN
            -- Due at once: the approval door claims it after its commit, the schedule if the door died.
            NEW.next_attempt_at := clock_timestamp();
        ELSIF NEW.next_attempt_at IS NOT NULL THEN
            RAISE EXCEPTION 'a rejection issues nothing' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    -- APPROVED or DISPATCHED: the decision is frozen.
    IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
            OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
            OR NEW.decision_action_id IS DISTINCT FROM OLD.decision_action_id THEN
        RAISE EXCEPTION 'an action''s decision never changes (P13-TSK-017)' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'DISPATCHED' THEN
        -- T-act's claim: only a permit that has come, one attempt counted, the permit moved ahead.
        IF OLD.next_attempt_at > clock_timestamp() THEN
            RAISE EXCEPTION 'an action''s permit is claimed only when it has come (P13-TSK-017)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts <> OLD.attempts + 1 OR NEW.next_attempt_at IS NULL OR NEW.next_attempt_at <= clock_timestamp()
                OR NEW.outcome IS NOT NULL THEN
            RAISE EXCEPTION 'a claim counts one attempt and moves the permit ahead' USING ERRCODE = 'check_violation';
        END IF;
        NEW.dispatched_at := COALESCE(OLD.dispatched_at, clock_timestamp());
        NEW.concluded_at := NULL;
        RETURN NEW;
    END IF;

    IF OLD.status = 'DISPATCHED' AND NEW.status IN ('DONE', 'FAILED') THEN
        -- The owner's answer, recorded once: no attempt counted, the permit cleared, the instant the database's.
        IF NEW.outcome IS NULL OR NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'the record names the owner''s answer and counts no attempt'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.next_attempt_at := NULL;
        NEW.dispatched_at := OLD.dispatched_at;
        NEW.concluded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a risk action moves only along its machine (P13-TSK-017)' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER risk_action_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_action
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_action_permits_only_machine_edges();

CREATE TRIGGER risk_action_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_action
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_action_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------------------------------
-- 5. The case: its action acts are reasoned steps of its history, and it is not disposed past a standing proposal.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_case_action
    DROP CONSTRAINT risk_case_action_texted,
    ADD CONSTRAINT risk_case_action_texted CHECK (
        (kind NOT IN ('NOTE', 'EVIDENCE_ATTACHED', 'ESCALATED', 'ACTION_PROPOSED', 'ACTION_APPROVED', 'ACTION_REJECTED',
                      'DISPOSITION_PROPOSED', 'DISPOSITION_APPROVED', 'DISPOSITION_REJECTED') OR text IS NOT NULL)
        AND (kind <> 'TAKEN' OR text IS NULL));

ALTER TABLE risk.risk_case_event
    DROP CONSTRAINT risk_case_event_kind_is_known,
    ADD CONSTRAINT risk_case_event_kind_is_known CHECK (kind IN ('OPENED', 'ALERT_ATTACHED', 'ASSESSMENT_ATTACHED',
        'TAKEN', 'NOTED', 'EVIDENCE_ATTACHED', 'ESCALATED', 'DISPOSITION_PROPOSED', 'DISPOSITION_REJECTED', 'DISPOSED',
        'ACTION_PROPOSED', 'ACTION_APPROVED', 'ACTION_REJECTED')),
    DROP CONSTRAINT risk_case_event_names_its_act,
    ADD CONSTRAINT risk_case_event_names_its_act CHECK (
        (kind IN ('TAKEN', 'NOTED', 'EVIDENCE_ATTACHED', 'ESCALATED', 'DISPOSITION_PROPOSED', 'DISPOSITION_REJECTED',
                  'DISPOSED', 'ACTION_PROPOSED', 'ACTION_APPROVED', 'ACTION_REJECTED')) = (action_id IS NOT NULL)
        AND (kind <> 'ALERT_ATTACHED' OR alert_id IS NOT NULL)
        AND (kind <> 'ASSESSMENT_ATTACHED' OR assessment_id IS NOT NULL)
        AND (alert_id IS NULL OR kind IN ('OPENED', 'ALERT_ATTACHED'))
        AND (assessment_id IS NULL OR kind IN ('OPENED', 'ASSESSMENT_ATTACHED'))
        AND num_nonnulls(alert_id, assessment_id) <= 1);

CREATE FUNCTION risk.risk_case_disposes_no_standing_action() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM risk.risk_action a WHERE a.case_id = NEW.id AND a.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'a case is not disposed while an action proposed on it awaits its decision (P13-TSK-017)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_case_disposes_no_standing_action
    BEFORE UPDATE ON risk.risk_case
    FOR EACH ROW
    WHEN (NEW.status = 'DISPOSED' AND OLD.status <> 'DISPOSED')
    EXECUTE FUNCTION risk.risk_case_disposes_no_standing_action();

-- ---------------------------------------------------------------------------------------------------------------------
-- 6. The grants: SELECT and INSERT; a column-scoped UPDATE of the machine's columns - the stamps are the trigger's.
--    No DELETE for any role. (decision_override's grants on the moved columns went with them.)
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.risk_action TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decision_action_id, next_attempt_at, attempts, outcome)
    ON risk.risk_action TO finapp_app;
