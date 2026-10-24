-- The born-once alert (P13-TSK-015; PHASE_13_PLAN.md sections 3.1, 4, 5, 7.2 (R3, R5), 7.4, 8, 10, 11; RISK_LIFECYCLES.md
-- section 4.5; ADR-0110 decision 1; INV-RSK-06, INV-RSK-07, INV-RSK-08, INV-AUD-04).
--
-- WHAT AN ALERT IS
--   Risk's record that a rule saw something about a subject in a window: "party P tripped RECENT_CREDENTIAL_CHANGE in
--   the day starting 2026-10-25", "account A tripped STRUCTURING in the week starting Monday". Not a judgement (the
--   assessment), not an investigation (the case, P13-TSK-016), not a filing (the report). Its identity is the triple
--   (subject, rule, window start) - risk_alert_born_once - so any number of detections of that triple, by any number of
--   instances, a replayed event or a reprocessed history, are ONE row: the raise is INSERT ... ON CONFLICT ON CONSTRAINT
--   risk_alert_born_once DO NOTHING (INV-RSK-06; AlertDatabaseTest#twoInstancesRaiseOnce, scenario 2).
--
-- THE WINDOW IS A BUCKET, NEVER AN INSTANT
--   Two instances stamping a raise with their own clocks would never collide on the unique, so window_start is a
--   tumbling-window boundary of window_kind (H1, D1, D7, D30 - the velocity windows' lengths and anchor, Monday
--   1970-01-05, V005's risk.velocity_window_length), aligned by CHECK exactly as velocity_window's is, computed from a
--   database instant that is the EVIDENCE's own (T-eval's cut, a born-once row's database stamp), so a replay
--   recomputes the same start. The unique stays the plan's triple: an H1 and a D1 raise of one rule and subject that
--   start at the same midnight are one alert - conservative.
--
-- THE MACHINE: OPEN -> ATTACHED | SUPPRESSED, nothing out of either, nothing deleted
--   ATTACHED   - joined to a case: the system's dedupe (P13-TSK-016's folding - an alert raised while its subject has an
--                open case of the kind attaches to it) or an investigator's case opened from it. attached_case_id's
--                foreign key arrives with risk_case in P13-TSK-016's migration (the table does not exist yet).
--   SUPPRESSED - ONLY an operator's four-eyes act, never the system's: a RISK_INVESTIGATE holder proposes with a closed
--                reason code and a text (the proposal columns, OPEN -> OPEN), a DIFFERENT holder of RISK_CASE_APPROVE
--                approves with a reason; a rejection clears the proposal (OPEN -> OPEN), the audit trail keeping both
--                acts. Both actors are EMPLOYEEs by CHECK; whoever approved did not propose, by CHECK, for every writer
--                (risk_alert_suppression_four_eyes) - deliberately NOT in the trigger, so the CHECK is provable alone.
--   The two operational kinds (UNCLASSIFIED_TRANSACTION, VELOCITY_DIVERGENCE) name their own kind as rule, no policy,
--   and are never attached - they open no case. (No writer raises them today: they alert through their gauges - the
--   transition's settled reading, RISK_LIFECYCLES.md section 8; the table admits them for -023/-024.)
--
-- EVIDENCE BY REFERENCE ONLY
--   Signal ids, assessment ids, journal entry ids - references resolved through audited reads, never copies; each list
--   1-dimensional, distinct by the domain, at most 100; a rule's alert names at least one. Nothing here is money (no
--   *_minor column), a score, a rule's parameters or a name; the suppression texts are person-written, screened
--   (risk.holds_instrument_shape) and RESTRICTED-PII.
--
-- The stamps (raised_at, attached_at, suppression_proposed_at, suppression_decided_at) are the trigger's
-- clock_timestamp(), whatever a writer passes. The grants: SELECT, INSERT and a column-scoped UPDATE of the status, the
-- attachment and the suppression columns - never a fact, never a stamp; no DELETE for any role. The migration names no
-- other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The table.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_alert (
    id                            uuid        NOT NULL,
    kind                          text        NOT NULL,
    severity                      text        NOT NULL,
    subject_kind                  text        NOT NULL,
    subject_ref                   text        NOT NULL,
    rule_code                     text        NOT NULL,
    window_kind                   text        NOT NULL,
    window_start                  timestamptz NOT NULL,
    policy_version_id             uuid,

    -- The evidence, by reference.
    evidence_signal_ids           uuid[]      NOT NULL,
    evidence_assessment_ids       uuid[]      NOT NULL,
    evidence_journal_entry_ids    uuid[]      NOT NULL,

    status                        text        NOT NULL,
    attached_case_id              uuid,
    attached_at                   timestamptz,

    -- The suppression's proposal and decision (four eyes).
    suppression_reason_code       text,
    suppression_reason            text,
    suppression_proposed_by       text,
    suppression_proposed_by_type  text,
    suppression_proposed_at       timestamptz,
    suppression_decided_by        text,
    suppression_decided_by_type   text,
    suppression_decided_at        timestamptz,
    suppression_decision_reason   text,

    raised_at                     timestamptz NOT NULL,
    correlation_id                text        NOT NULL,
    causation_id                  text        NOT NULL,

    CONSTRAINT risk_alert_pk PRIMARY KEY (id),

    -- Born once per (subject, rule, window start): the arbiter every raiser meets (INV-RSK-06).
    CONSTRAINT risk_alert_born_once UNIQUE (subject_ref, rule_code, window_start),

    -- The policy version whose rule raised it (an operational alert names none).
    CONSTRAINT risk_alert_policy_version_fk FOREIGN KEY (policy_version_id) REFERENCES risk.risk_policy_version (id),

    -- The closed vocabularies, as AlertKind, AlertSeverity, SubjectKind, AlertRule, WindowKind and AlertStatus declare
    -- them (RiskAlertMigrationTest holds each list to its enum).
    CONSTRAINT risk_alert_kind_is_known CHECK (kind IN ('FRAUD', 'ACCOUNT_TAKEOVER', 'MERCHANT_ABUSE', 'AML',
        'SCREENING_HIT', 'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')),
    CONSTRAINT risk_alert_severity_is_known CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT risk_alert_subject_kind_is_known CHECK (subject_kind IN (
        'PARTY', 'ACCOUNT', 'DEVICE', 'MERCHANT', 'BENEFICIARY', 'SESSION', 'IDENTITY', 'CREDIT_AGREEMENT')),
    CONSTRAINT risk_alert_rule_code_is_known CHECK (rule_code IN (
        'AMOUNT_BAND', 'VELOCITY_COUNT', 'VELOCITY_SUM', 'NEW_PAYEE', 'NEW_DEVICE', 'SESSION_ASSURANCE_BELOW',
        'RECENT_AUTH_FAILURES', 'RECENT_CREDENTIAL_CHANGE', 'RECENT_RECOVERY', 'ACCOUNT_AGE_BELOW', 'KYC_NOT_APPROVED',
        'SCREENING_HIT', 'PROFILE_RATING', 'MERCHANT_SUSPENDED', 'CORRIDOR_HIGH_RISK', 'DORMANT_THEN_ACTIVE',
        'RAPID_IN_OUT', 'STRUCTURING', 'ROUND_AMOUNT', 'REFUND_RATIO', 'CHARGEBACK_RATIO', 'BNPL_MULTI_APPLICATION',
        'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')),
    CONSTRAINT risk_alert_window_kind_is_known CHECK (window_kind IN ('H1', 'D1', 'D7', 'D30')),
    CONSTRAINT risk_alert_status_is_known CHECK (status IN ('OPEN', 'ATTACHED', 'SUPPRESSED')),

    -- A device is its hash and nothing else; every other subject a canonical UUID (OQ-4; V003's rule).
    CONSTRAINT risk_alert_subject_ref_shaped CHECK (CASE subject_kind
        WHEN 'DEVICE' THEN subject_ref ~ '^[0-9a-f]{64}$'
        ELSE subject_ref ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        END),

    -- An operational kind's rule is its own code, with no policy; a rule's alert names a rule kind and its policy (a
    -- SCREENING_HIT alert of the SCREENING_HIT rule kind among them - the two vocabularies share that one name).
    CONSTRAINT risk_alert_rule_matches_kind CHECK (
        (kind IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE'))
            = (rule_code IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE'))
        AND (kind NOT IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE') OR rule_code = kind)),
    CONSTRAINT risk_alert_policy_named CHECK (
        (kind IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')) = (policy_version_id IS NULL)),

    -- The window is a bucket of its kind, anchored where the velocity windows are (V005).
    CONSTRAINT risk_alert_window_start_is_aligned CHECK (
        window_start = date_bin(risk.velocity_window_length(window_kind), window_start,
                                TIMESTAMPTZ '1970-01-05 00:00:00+00')),

    -- References, bounded: 1-dimensional, at most 100 each, no NULL element; a rule's alert names at least one.
    CONSTRAINT risk_alert_evidence_bounded CHECK (
        coalesce(array_ndims(evidence_signal_ids), 1) = 1 AND cardinality(evidence_signal_ids) <= 100
        AND array_position(evidence_signal_ids, NULL) IS NULL
        AND coalesce(array_ndims(evidence_assessment_ids), 1) = 1 AND cardinality(evidence_assessment_ids) <= 100
        AND array_position(evidence_assessment_ids, NULL) IS NULL
        AND coalesce(array_ndims(evidence_journal_entry_ids), 1) = 1 AND cardinality(evidence_journal_entry_ids) <= 100
        AND array_position(evidence_journal_entry_ids, NULL) IS NULL),
    CONSTRAINT risk_alert_evidence_present CHECK (
        kind IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')
        OR cardinality(evidence_signal_ids) + cardinality(evidence_assessment_ids)
           + cardinality(evidence_journal_entry_ids) >= 1),

    -- ATTACHED exactly when a case is named, and stamped; an operational alert opens no case.
    CONSTRAINT risk_alert_attachment_coherent CHECK (
        (status = 'ATTACHED') = (attached_case_id IS NOT NULL)
        AND (attached_case_id IS NULL) = (attached_at IS NULL)),
    CONSTRAINT risk_alert_operational_never_attached CHECK (
        status <> 'ATTACHED' OR kind NOT IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')),

    -- The proposal whole or absent; its code closed; its text bounded and screened.
    CONSTRAINT risk_alert_suppression_proposal_whole CHECK (
        num_nonnulls(suppression_reason_code, suppression_reason, suppression_proposed_by,
                     suppression_proposed_by_type, suppression_proposed_at) IN (0, 5)),
    CONSTRAINT risk_alert_suppression_reason_code_is_known CHECK (
        suppression_reason_code IS NULL
        OR suppression_reason_code IN ('FALSE_POSITIVE', 'ACTIVITY_EXPLAINED', 'RULE_DEFECT', 'DATA_DEFECT')),
    CONSTRAINT risk_alert_suppression_reasoned CHECK (
        suppression_reason IS NULL
        OR (char_length(suppression_reason) BETWEEN 1 AND 1000 AND NOT risk.holds_instrument_shape(suppression_reason))),

    -- SUPPRESSED exactly when decided, and only over a proposal; the decision whole, reasoned and screened.
    CONSTRAINT risk_alert_suppression_decision_coherent CHECK (
        (status = 'SUPPRESSED') = (suppression_decided_by IS NOT NULL)
        AND num_nonnulls(suppression_decided_by, suppression_decided_by_type, suppression_decided_at,
                         suppression_decision_reason) IN (0, 4)
        AND (suppression_decided_by IS NULL OR suppression_proposed_by IS NOT NULL)),
    CONSTRAINT risk_alert_suppression_decision_reasoned CHECK (
        suppression_decision_reason IS NULL
        OR (char_length(suppression_decision_reason) BETWEEN 1 AND 1000
            AND NOT risk.holds_instrument_shape(suppression_decision_reason))),

    -- Never the system: both acts are persons'.
    CONSTRAINT risk_alert_suppression_by_persons CHECK (
        (suppression_proposed_by_type IS NULL OR suppression_proposed_by_type = 'EMPLOYEE')
        AND (suppression_decided_by_type IS NULL OR suppression_decided_by_type = 'EMPLOYEE')),

    -- FOUR EYES, for every writer (INV-RSK-07, INV-AUD-04): whoever suppressed it did not propose it.
    CONSTRAINT risk_alert_suppression_four_eyes CHECK (
        status <> 'SUPPRESSED' OR suppression_decided_by <> suppression_proposed_by),

    CONSTRAINT risk_alert_correlation_bounded CHECK (
        char_length(correlation_id) BETWEEN 1 AND 200 AND char_length(causation_id) BETWEEN 1 AND 200)
);

-- The queue: the OPEN alerts, oldest first - P13-TSK-018's door and P13-TSK-016's backlog of alerts born before cases.
CREATE INDEX risk_alert_open_queue ON risk.risk_alert (raised_at, id) WHERE status = 'OPEN';

COMMENT ON TABLE risk.risk_alert IS
    'A born-once alert: one row per (subject, rule code, window start) - risk_alert_born_once, ON CONFLICT DO NOTHING (INV-RSK-06) - raised by a fraud or monitoring rule in T-eval or T-mon, with kind, severity, the policy version and evidence by reference (signal, assessment, journal entry ids). OPEN -> ATTACHED (to a case, P13-TSK-016) | SUPPRESSED (only an operator''s reasoned four-eyes act - two EMPLOYEEs, the approver never the proposer, by CHECK). Never deleted, never re-opened; its facts frozen for every writer. No money, no score, no parameters. P13-TSK-015.';
COMMENT ON COLUMN risk.risk_alert.window_start IS
    'The start of the window_kind bucket the evidence falls in - a database instant aligned to the velocity windows'' anchor (CHECK), never a raiser''s clock, so a replay recomputes the same start.';
COMMENT ON COLUMN risk.risk_alert.attached_case_id IS
    'The case the alert attached to; its foreign key to risk.risk_case arrives with P13-TSK-016''s migration.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The machine.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_alert_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: an alert is evidence that a rule fired; it is never removed.
        RAISE EXCEPTION 'a risk alert is never deleted or truncated (P13-TSK-015, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.attached_case_id IS NOT NULL OR NEW.attached_at IS NOT NULL
                OR num_nonnulls(NEW.suppression_reason_code, NEW.suppression_reason, NEW.suppression_proposed_by,
                                NEW.suppression_proposed_by_type, NEW.suppression_proposed_at,
                                NEW.suppression_decided_by, NEW.suppression_decided_by_type,
                                NEW.suppression_decided_at, NEW.suppression_decision_reason) <> 0 THEN
            RAISE EXCEPTION 'a risk alert is born OPEN, unattached and unproposed (P13-TSK-015)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.raised_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The facts are frozen for every edge.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.severity IS DISTINCT FROM OLD.severity OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind
            OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
            OR NEW.window_kind IS DISTINCT FROM OLD.window_kind OR NEW.window_start IS DISTINCT FROM OLD.window_start
            OR NEW.policy_version_id IS DISTINCT FROM OLD.policy_version_id
            OR NEW.evidence_signal_ids IS DISTINCT FROM OLD.evidence_signal_ids
            OR NEW.evidence_assessment_ids IS DISTINCT FROM OLD.evidence_assessment_ids
            OR NEW.evidence_journal_entry_ids IS DISTINCT FROM OLD.evidence_journal_entry_ids
            OR NEW.raised_at IS DISTINCT FROM OLD.raised_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
            OR NEW.causation_id IS DISTINCT FROM OLD.causation_id THEN
        RAISE EXCEPTION 'a risk alert''s facts never change (P13-TSK-015)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status <> 'OPEN' THEN
        -- ATTACHED and SUPPRESSED are terminal: nothing moves out of either, nothing re-opens.
        RAISE EXCEPTION 'an attached or suppressed risk alert never moves (P13-TSK-015)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'ATTACHED' THEN
        -- The attach: a case named, stamped by the database; the suppression columns untouched.
        IF NEW.attached_case_id IS NULL
                OR NEW.suppression_reason_code IS DISTINCT FROM OLD.suppression_reason_code
                OR NEW.suppression_reason IS DISTINCT FROM OLD.suppression_reason
                OR NEW.suppression_proposed_by IS DISTINCT FROM OLD.suppression_proposed_by
                OR NEW.suppression_proposed_by_type IS DISTINCT FROM OLD.suppression_proposed_by_type
                OR NEW.suppression_proposed_at IS DISTINCT FROM OLD.suppression_proposed_at
                OR NEW.suppression_decided_by IS NOT NULL OR NEW.suppression_decided_by_type IS NOT NULL
                OR NEW.suppression_decided_at IS NOT NULL OR NEW.suppression_decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'an alert attaches to a named case and to nothing else' USING ERRCODE = 'check_violation';
        END IF;
        NEW.attached_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF NEW.attached_case_id IS NOT NULL OR NEW.attached_at IS NOT NULL THEN
        RAISE EXCEPTION 'only the attach names a case' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'SUPPRESSED' THEN
        -- The approval: over the standing proposal, unchanged, by a person with a reason, stamped by the database.
        -- (Four eyes is risk_alert_suppression_four_eyes' - provable alone.)
        IF OLD.suppression_proposed_by IS NULL
                OR NEW.suppression_reason_code IS DISTINCT FROM OLD.suppression_reason_code
                OR NEW.suppression_reason IS DISTINCT FROM OLD.suppression_reason
                OR NEW.suppression_proposed_by IS DISTINCT FROM OLD.suppression_proposed_by
                OR NEW.suppression_proposed_by_type IS DISTINCT FROM OLD.suppression_proposed_by_type
                OR NEW.suppression_proposed_at IS DISTINCT FROM OLD.suppression_proposed_at
                OR NEW.suppression_decided_by IS NULL OR NEW.suppression_decided_by_type IS NULL
                OR NEW.suppression_decision_reason IS NULL THEN
            RAISE EXCEPTION 'a suppression approves the standing proposal, by a person, with a reason'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.suppression_decided_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF NEW.status = 'OPEN' THEN
        IF NEW.suppression_decided_by IS NOT NULL OR NEW.suppression_decided_by_type IS NOT NULL
                OR NEW.suppression_decision_reason IS NOT NULL OR NEW.suppression_decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'an open alert carries no suppression decision' USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.suppression_proposed_by IS NULL AND NEW.suppression_proposed_by IS NOT NULL THEN
            -- The proposal: whole, onto none, stamped by the database.
            NEW.suppression_proposed_at := clock_timestamp();
            RETURN NEW;
        END IF;
        IF OLD.suppression_proposed_by IS NOT NULL AND num_nonnulls(NEW.suppression_reason_code,
                NEW.suppression_reason, NEW.suppression_proposed_by, NEW.suppression_proposed_by_type) = 0 THEN
            -- The rejection: the proposal cleared - its stamp with it, the trigger's as it was - and the alert OPEN
            -- again (the audit trail keeps both acts).
            NEW.suppression_proposed_at := NULL;
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'an open alert takes a proposal onto none, or a rejection of the standing one'
            USING ERRCODE = 'check_violation';
    END IF;

    RAISE EXCEPTION 'an open alert is attached, suppressed, proposed for suppression or rejected - nothing else'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER risk_alert_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_alert
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_alert_permits_only_machine_edges();

CREATE TRIGGER risk_alert_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_alert
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_alert_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The grants: SELECT and INSERT; a column-scoped UPDATE of the status, the attachment and the suppression - never a
--    fact, never a stamp (the trigger's). No DELETE for any role.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.risk_alert TO finapp_app;
GRANT UPDATE (status, attached_case_id, suppression_reason_code, suppression_reason, suppression_proposed_by,
    suppression_proposed_by_type, suppression_decided_by, suppression_decided_by_type, suppression_decision_reason)
    ON risk.risk_alert TO finapp_app;
