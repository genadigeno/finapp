-- The case: its machine, its investigator actions, its history (P13-TSK-016; PHASE_13_PLAN.md sections 3.1, 4, 5, 7.1
-- T-case, 7.2 (R4, R5), 7.4, 8, 11, 12.8; RISK_LIFECYCLES.md section 4.6; ADR-0110 decisions 2-7; INV-RSK-06,
-- INV-RSK-07, INV-RSK-08, INV-RSK-10, INV-AUD-04). The next free number at its build: the entry's V009 went to
-- P13-TSK-012, V010 to P13-TSK-015, V011 to P13-TSK-014.
--
-- WHAT A CASE IS
--   The unit of a person's judgement about one subject and one kind (FRAUD, ATO, MERCHANT_ABUSE, AML; COLLECTIONS
--   reserved for P13-TSK-025): opened by T-eval on a REVIEW or HOLD decision (the assessment its opening), by an alert's
--   folding (P13-TSK-015's AlertFolding - the alert its opening), or by hand; taken, worked, escalated, disposed.
--   ONE OPEN PER (subject, kind) - risk_case_one_open, a partial unique over the three live states: ten alerts or
--   decisions racing for a subject meet on it, the losers finding the winner's row (INV-RSK-06;
--   CaseDatabaseTest#tenAlertsOneOpenCase). A case NEVER RE-OPENS (OQ-6): nothing moves out of DISPOSED, and a later
--   alert or decision opens a new case naming the disposed one as its predecessor.
--
-- THE REVISION
--   Every step - a person's act or the system's attach - moves revision by exactly one (the trigger), and every
--   revision has exactly one history row (risk_case_event's primary key; the deferred risk_case_has_its_history). A
--   person's act is UPDATE ... WHERE id = ? AND revision = ? under the row's FOR UPDATE (R4), the domain having compared
--   the revision the investigator's screen read: the loser of two concurrent acts writes nothing and answers 409
--   risk.CaseRevisionStale, both earlier acts kept (ADR-0110 decision 5; CaseDatabaseTest#concurrentUpdatesKeepEveryAction).
--
-- THE DISPOSITION SLOT AND FOUR EYES
--   The case holds one disposition slot: a standing proposal (proposer, no approver) or the decision (proposer and
--   approver). CLOSED_FALSE_POSITIVE and CLOSED_NO_ACTION are one person's act, decided by their proposal - from OPEN
--   too; CONFIRMED_FRAUD, CONFIRMED_SUSPICIOUS, REFERRED and any disposition that sets the profile PROHIBITED are
--   proposed on a taken case and approved by a SECOND person: risk_case_disposition_four_eyes, for every writer,
--   deliberately NOT judged by the trigger, so the CHECK is provable alone (INV-RSK-07, INV-AUD-04). A rejection clears
--   the slot; the investigator actions keep every act. The slot's id is its DISPOSITION_PROPOSED action's - the
--   disposition's identity, which customer_risk_profile_version.disposition_id now references.
--
-- EVIDENCE BY REFERENCE, TEXT SCREENED
--   An action's evidence is signal, assessment and journal entry ids (at most 100 each), never a copy; its text is a
--   person's, 1 to 1000 characters, screened for instrument shapes (risk.holds_instrument_shape) and RESTRICTED-PII.
--   Nothing here is money, a score or a rule's parameters.
--
-- The stamps (opened_at, sla_deadline, disposition_proposed_at, disposed_at, recorded_at) are the triggers'
-- clock_timestamp(), whatever a writer passes. The grants: SELECT and INSERT on the three tables; a column-scoped
-- UPDATE of risk_case's status, revision, level, queue, assignee and disposition slot - never a fact, never a stamp; no
-- DELETE for any role. The migration names no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The case.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_case (
    id                              uuid        NOT NULL,
    kind                            text        NOT NULL,
    subject_kind                    text        NOT NULL,
    subject_ref                     text        NOT NULL,
    status                          text        NOT NULL,
    revision                        integer     NOT NULL,
    level                           text        NOT NULL,
    queue                           text        NOT NULL,

    -- The investigator who took it (a person), present past OPEN.
    taken_by                        text,
    taken_by_type                   text,

    -- The SLA, stamped on the database clock at the opening (SlaDeadlines' figure per kind).
    sla_hours                       smallint    NOT NULL,
    sla_deadline                    timestamptz NOT NULL,

    -- What opened it - an alert or an assessment, or neither (by hand) - and the disposed case it follows.
    opening_alert_id                uuid,
    opening_assessment_id           uuid,
    predecessor_case_id             uuid,
    opened_at                       timestamptz NOT NULL,

    -- The disposition slot: a standing proposal, or the decision.
    disposition                     text,
    disposition_id                  uuid,
    disposition_prohibits           boolean,
    disposition_proposed_by         text,
    disposition_proposed_by_type    text,
    disposition_proposed_at         timestamptz,
    disposition_approved_by         text,
    disposition_approved_by_type    text,
    disposed_at                     timestamptz,

    correlation_id                  text        NOT NULL,

    CONSTRAINT risk_case_pk PRIMARY KEY (id),

    CONSTRAINT risk_case_opening_alert_fk FOREIGN KEY (opening_alert_id) REFERENCES risk.risk_alert (id),
    CONSTRAINT risk_case_opening_assessment_fk
        FOREIGN KEY (opening_assessment_id) REFERENCES risk.risk_assessment (id),
    CONSTRAINT risk_case_predecessor_fk FOREIGN KEY (predecessor_case_id) REFERENCES risk.risk_case (id),

    -- The closed vocabularies, as CaseKind, SubjectKind, CaseStatus, CaseLevel, CaseQueue and Disposition declare them
    -- (RiskCaseMigrationTest holds each list to its enum).
    CONSTRAINT risk_case_kind_is_known CHECK (kind IN ('FRAUD', 'ATO', 'MERCHANT_ABUSE', 'AML', 'COLLECTIONS')),
    CONSTRAINT risk_case_subject_kind_is_known CHECK (subject_kind IN (
        'PARTY', 'ACCOUNT', 'DEVICE', 'MERCHANT', 'BENEFICIARY', 'SESSION', 'IDENTITY', 'CREDIT_AGREEMENT')),
    CONSTRAINT risk_case_status_is_known CHECK (status IN ('OPEN', 'IN_INVESTIGATION', 'ESCALATED', 'DISPOSED')),
    CONSTRAINT risk_case_level_is_known CHECK (level IN ('L1', 'L2', 'L3')),
    CONSTRAINT risk_case_queue_is_known CHECK (queue IN ('FIRST_LINE', 'SECOND_LINE', 'SENIOR_REVIEW', 'SPECIALIST')),
    CONSTRAINT risk_case_disposition_is_known CHECK (disposition IS NULL OR disposition IN (
        'CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION', 'CONFIRMED_FRAUD', 'CONFIRMED_SUSPICIOUS', 'REFERRED')),

    -- A device is its hash and nothing else; every other subject a canonical UUID (OQ-4; V003's rule).
    CONSTRAINT risk_case_subject_ref_shaped CHECK (CASE subject_kind
        WHEN 'DEVICE' THEN subject_ref ~ '^[0-9a-f]{64}$'
        ELSE subject_ref ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        END),

    CONSTRAINT risk_case_revision_counts CHECK (revision >= 0),
    CONSTRAINT risk_case_sla_bounded CHECK (sla_hours BETWEEN 1 AND 2160 AND sla_deadline > opened_at),
    CONSTRAINT risk_case_opened_from_one CHECK (num_nonnulls(opening_alert_id, opening_assessment_id) <= 1),

    -- Taken past OPEN, by a person - an authenticated human: CUSTOMER-typed as the platform's session establishes
    -- every identity, or EMPLOYEE; never SYSTEM, SERVICE or MERCHANT. The level and queue above the first line
    -- exactly once escalated.
    CONSTRAINT risk_case_taken_coherent CHECK (
        num_nonnulls(taken_by, taken_by_type) IN (0, 2)
        AND (taken_by_type IS NULL OR taken_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (status NOT IN ('IN_INVESTIGATION', 'ESCALATED') OR taken_by IS NOT NULL)),
    CONSTRAINT risk_case_escalation_coherent CHECK (
        (status NOT IN ('OPEN', 'IN_INVESTIGATION') OR (level = 'L1' AND queue = 'FIRST_LINE'))
        AND (status <> 'ESCALATED' OR (level <> 'L1' AND queue <> 'FIRST_LINE'))),

    -- The slot whole or empty; decided exactly when DISPOSED; persons only.
    CONSTRAINT risk_case_disposition_slot_whole CHECK (
        num_nonnulls(disposition, disposition_id, disposition_prohibits, disposition_proposed_by,
                     disposition_proposed_by_type, disposition_proposed_at) IN (0, 6)),
    CONSTRAINT risk_case_disposition_decided_coherent CHECK (
        (status = 'DISPOSED') = (disposition_approved_by IS NOT NULL)
        AND num_nonnulls(disposition_approved_by, disposition_approved_by_type, disposed_at) IN (0, 3)
        AND (disposition_approved_by IS NULL OR disposition IS NOT NULL)),
    CONSTRAINT risk_case_disposition_by_persons CHECK (
        (disposition_proposed_by_type IS NULL OR disposition_proposed_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (disposition_approved_by_type IS NULL OR disposition_approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))),

    -- PROHIBITED is asked of a confirming disposition of a party's case alone (DESIGN-016 D3; INV-RSK-10).
    CONSTRAINT risk_case_prohibits_only_confirming CHECK (
        disposition_prohibits IS NOT TRUE
        OR (disposition IN ('CONFIRMED_FRAUD', 'CONFIRMED_SUSPICIOUS') AND subject_kind = 'PARTY')),

    -- FOUR EYES, for every writer (INV-RSK-07, INV-AUD-04): a disposed case's confirming, referring or prohibiting
    -- disposition was approved by someone other than its proposer.
    CONSTRAINT risk_case_disposition_four_eyes CHECK (
        status <> 'DISPOSED'
        OR (disposition IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION') AND NOT disposition_prohibits)
        OR disposition_approved_by <> disposition_proposed_by),

    CONSTRAINT risk_case_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- ONE OPEN PER (subject, kind): the arbiter every opener meets (INV-RSK-06). The store's birth is
-- INSERT ... ON CONFLICT (subject_ref, kind) WHERE status <> 'DISPOSED' DO NOTHING; a loser's lockOpen finds the winner.
CREATE UNIQUE INDEX risk_case_one_open ON risk.risk_case (subject_ref, kind) WHERE status <> 'DISPOSED';

-- The predecessor a new case names: the subject's most recently disposed case of the kind.
CREATE INDEX risk_case_disposed_by_subject ON risk.risk_case (subject_ref, kind, disposed_at DESC)
    WHERE status = 'DISPOSED';

-- The case leg's re-drive of approved prohibitions (DESIGN-016 D7): few rows, oldest first.
CREATE INDEX risk_case_prohibitions ON risk.risk_case (disposed_at, id)
    WHERE status = 'DISPOSED' AND disposition_prohibits;

COMMENT ON TABLE risk.risk_case IS
    'A case: the unit of a person''s judgement about one subject and one kind - one OPEN per (subject, kind) by risk_case_one_open (INV-RSK-06), OPEN -> IN_INVESTIGATION -> ESCALATED -> DISPOSED (a one-person close from OPEN too), never re-opened (a new case names its predecessor). Every step moves revision by one and has its risk_case_event row; a person''s act is conditional on the revision their screen read (409 risk.CaseRevisionStale). The disposition slot holds a standing proposal or the decision - four eyes for CONFIRMED_*, REFERRED and PROHIBITED by CHECK. No money, no score. P13-TSK-016.';
COMMENT ON COLUMN risk.risk_case.sla_deadline IS
    'opened_at + sla_hours, stamped by the trigger on the database clock; nothing transitions on it - P13-TSK-018''s gauge and alert read it.';
COMMENT ON COLUMN risk.risk_case.disposition_id IS
    'The disposition''s identity: its DISPOSITION_PROPOSED risk_case_action''s id - a cleared slot''s next proposal names a new one.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The investigator actions - append-only.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_case_action (
    id                            uuid        NOT NULL,
    case_id                       uuid        NOT NULL,
    ordinal                       integer     NOT NULL,
    kind                          text        NOT NULL,
    actor_id                      text        NOT NULL,
    actor_type                    text        NOT NULL,
    text                          text,

    -- Evidence, by reference.
    evidence_signal_ids           uuid[]      NOT NULL,
    evidence_assessment_ids       uuid[]      NOT NULL,
    evidence_journal_entry_ids    uuid[]      NOT NULL,

    -- A proposal's disposition; an escalation's queue.
    disposition                   text,
    prohibits                     boolean     NOT NULL,
    queue                         text,

    recorded_at                   timestamptz NOT NULL,
    correlation_id                text        NOT NULL,

    CONSTRAINT risk_case_action_pk PRIMARY KEY (id),
    -- The slot and the history name an action of their own case (composite foreign keys below).
    CONSTRAINT risk_case_action_of_its_case UNIQUE (id, case_id),
    -- One action per step: its ordinal is the revision it produced.
    CONSTRAINT risk_case_action_one_per_step UNIQUE (case_id, ordinal),
    CONSTRAINT risk_case_action_case_fk FOREIGN KEY (case_id) REFERENCES risk.risk_case (id),

    CONSTRAINT risk_case_action_kind_is_known CHECK (kind IN ('NOTE', 'EVIDENCE_ATTACHED', 'TAKEN', 'ESCALATED',
        'ACTION_PROPOSED', 'ACTION_APPROVED', 'ACTION_REJECTED', 'DISPOSITION_PROPOSED', 'DISPOSITION_APPROVED',
        'DISPOSITION_REJECTED', 'REPORT_DRAFTED')),
    CONSTRAINT risk_case_action_ordinal_counts CHECK (ordinal >= 1),
    CONSTRAINT risk_case_action_by_a_person CHECK (actor_type IN ('CUSTOMER', 'EMPLOYEE')),

    -- Reasoned where a person's text is the act's reason; bounded and screened.
    CONSTRAINT risk_case_action_texted CHECK (
        (kind NOT IN ('NOTE', 'EVIDENCE_ATTACHED', 'ESCALATED', 'DISPOSITION_PROPOSED', 'DISPOSITION_APPROVED',
                      'DISPOSITION_REJECTED') OR text IS NOT NULL)
        AND (kind <> 'TAKEN' OR text IS NULL)),
    CONSTRAINT risk_case_action_text_screened CHECK (
        text IS NULL OR (char_length(text) BETWEEN 1 AND 1000 AND NOT risk.holds_instrument_shape(text))),

    -- References, bounded: 1-dimensional, at most 100 each, no NULL element; present exactly for EVIDENCE_ATTACHED.
    CONSTRAINT risk_case_action_evidence_bounded CHECK (
        coalesce(array_ndims(evidence_signal_ids), 1) = 1 AND cardinality(evidence_signal_ids) <= 100
        AND array_position(evidence_signal_ids, NULL) IS NULL
        AND coalesce(array_ndims(evidence_assessment_ids), 1) = 1 AND cardinality(evidence_assessment_ids) <= 100
        AND array_position(evidence_assessment_ids, NULL) IS NULL
        AND coalesce(array_ndims(evidence_journal_entry_ids), 1) = 1 AND cardinality(evidence_journal_entry_ids) <= 100
        AND array_position(evidence_journal_entry_ids, NULL) IS NULL),
    CONSTRAINT risk_case_action_evidence_only_attached CHECK (
        (kind = 'EVIDENCE_ATTACHED') = (cardinality(evidence_signal_ids) + cardinality(evidence_assessment_ids)
                                        + cardinality(evidence_journal_entry_ids) >= 1)),

    CONSTRAINT risk_case_action_disposition_only_proposed CHECK (
        (kind = 'DISPOSITION_PROPOSED') = (disposition IS NOT NULL)
        AND (disposition IS NULL OR disposition IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION', 'CONFIRMED_FRAUD',
                                                    'CONFIRMED_SUSPICIOUS', 'REFERRED'))),
    CONSTRAINT risk_case_action_prohibits_only_confirming CHECK (
        NOT prohibits OR disposition IN ('CONFIRMED_FRAUD', 'CONFIRMED_SUSPICIOUS')),
    CONSTRAINT risk_case_action_queue_only_escalated CHECK (
        (kind = 'ESCALATED') = (queue IS NOT NULL)
        AND (queue IS NULL OR queue IN ('SECOND_LINE', 'SENIOR_REVIEW', 'SPECIALIST'))),

    CONSTRAINT risk_case_action_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE risk.risk_case_action IS
    'An investigator action, born once and never edited (INSERT-only for every writer): a note, evidence by reference, a take, an escalation, a disposition proposed, approved or rejected (ACTION_* for P13-TSK-017, REPORT_DRAFTED for P13-TSK-021 admitted, written by nothing yet). A person''s act; its text bounded, screened and RESTRICTED-PII; its ordinal the case revision it produced. P13-TSK-016.';

-- The slot names its case's own DISPOSITION_PROPOSED action (the action is written before the step that names it).
ALTER TABLE risk.risk_case ADD CONSTRAINT risk_case_disposition_fk
    FOREIGN KEY (disposition_id, id) REFERENCES risk.risk_case_action (id, case_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The history - one row per revision, append-only.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.risk_case_event (
    case_id           uuid        NOT NULL,
    revision          integer     NOT NULL,
    kind              text        NOT NULL,
    status            text        NOT NULL,
    level             text        NOT NULL,
    queue             text        NOT NULL,
    actor_id          text        NOT NULL,
    actor_type        text        NOT NULL,
    action_id         uuid,
    alert_id          uuid,
    assessment_id     uuid,
    recorded_at       timestamptz NOT NULL,
    correlation_id    text        NOT NULL,

    CONSTRAINT risk_case_event_pk PRIMARY KEY (case_id, revision),
    CONSTRAINT risk_case_event_case_fk FOREIGN KEY (case_id) REFERENCES risk.risk_case (id),
    CONSTRAINT risk_case_event_action_fk FOREIGN KEY (action_id, case_id) REFERENCES risk.risk_case_action (id, case_id),
    CONSTRAINT risk_case_event_alert_fk FOREIGN KEY (alert_id) REFERENCES risk.risk_alert (id),
    CONSTRAINT risk_case_event_assessment_fk FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessment (id),

    CONSTRAINT risk_case_event_kind_is_known CHECK (kind IN ('OPENED', 'ALERT_ATTACHED', 'ASSESSMENT_ATTACHED', 'TAKEN',
        'NOTED', 'EVIDENCE_ATTACHED', 'ESCALATED', 'DISPOSITION_PROPOSED', 'DISPOSITION_REJECTED', 'DISPOSED')),
    CONSTRAINT risk_case_event_state_is_known CHECK (
        status IN ('OPEN', 'IN_INVESTIGATION', 'ESCALATED', 'DISPOSED') AND level IN ('L1', 'L2', 'L3')
        AND queue IN ('FIRST_LINE', 'SECOND_LINE', 'SENIOR_REVIEW', 'SPECIALIST')),
    CONSTRAINT risk_case_event_actor_type_is_known CHECK (
        actor_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE', 'MERCHANT')),
    CONSTRAINT risk_case_event_opened_at_zero CHECK (revision >= 0 AND (kind = 'OPENED') = (revision = 0)),

    -- A person's step names its action; the system's names what it attached; an opening at most one of them.
    CONSTRAINT risk_case_event_names_its_act CHECK (
        (kind IN ('TAKEN', 'NOTED', 'EVIDENCE_ATTACHED', 'ESCALATED', 'DISPOSITION_PROPOSED', 'DISPOSITION_REJECTED',
                  'DISPOSED')) = (action_id IS NOT NULL)
        AND (kind <> 'ALERT_ATTACHED' OR alert_id IS NOT NULL)
        AND (kind <> 'ASSESSMENT_ATTACHED' OR assessment_id IS NOT NULL)
        AND (alert_id IS NULL OR kind IN ('OPENED', 'ALERT_ATTACHED'))
        AND (assessment_id IS NULL OR kind IN ('OPENED', 'ASSESSMENT_ATTACHED'))
        AND num_nonnulls(alert_id, assessment_id) <= 1),

    CONSTRAINT risk_case_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE risk.risk_case_event IS
    'A case''s history: exactly one row per revision (PRIMARY KEY (case_id, revision); risk_case_has_its_history at commit), written in the step''s own transaction - the kind, the state after it, the actor (a person; for the system''s openings and attaches the acting principal), the action a person wrote or the alert or assessment the system attached. INSERT-only for every writer. P13-TSK-016.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. The machine.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_case_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: a case is a person's recorded judgement; it is never removed.
        RAISE EXCEPTION 'a risk case is never deleted or truncated (P13-TSK-016, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.revision <> 0 OR NEW.level <> 'L1' OR NEW.queue <> 'FIRST_LINE'
                OR NEW.taken_by IS NOT NULL OR NEW.taken_by_type IS NOT NULL
                OR num_nonnulls(NEW.disposition, NEW.disposition_id, NEW.disposition_prohibits,
                                NEW.disposition_proposed_by, NEW.disposition_proposed_by_type,
                                NEW.disposition_proposed_at, NEW.disposition_approved_by,
                                NEW.disposition_approved_by_type, NEW.disposed_at) <> 0 THEN
            RAISE EXCEPTION 'a risk case is born OPEN at revision 0, L1 on the first line, untaken and undisposed (P13-TSK-016)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.predecessor_case_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM risk.risk_case p
                 WHERE p.id = NEW.predecessor_case_id AND p.status = 'DISPOSED'
                   AND p.subject_ref = NEW.subject_ref AND p.kind = NEW.kind) THEN
            RAISE EXCEPTION 'a case''s predecessor is a disposed case of its own subject and kind (OQ-6)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.opened_at := clock_timestamp();
        NEW.sla_deadline := NEW.opened_at + make_interval(hours => NEW.sla_hours);
        RETURN NEW;
    END IF;

    -- UPDATE. The facts are frozen for every step.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref
            OR NEW.sla_hours IS DISTINCT FROM OLD.sla_hours OR NEW.sla_deadline IS DISTINCT FROM OLD.sla_deadline
            OR NEW.opening_alert_id IS DISTINCT FROM OLD.opening_alert_id
            OR NEW.opening_assessment_id IS DISTINCT FROM OLD.opening_assessment_id
            OR NEW.predecessor_case_id IS DISTINCT FROM OLD.predecessor_case_id
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a risk case''s facts never change (P13-TSK-016)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'DISPOSED' THEN
        -- DISPOSED is final: nothing moves out of it, nothing re-opens (OQ-6) - a new case references it.
        RAISE EXCEPTION 'a disposed risk case never moves and never re-opens (P13-TSK-016, OQ-6)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'a risk case moves one revision at a time (P13-TSK-016)' USING ERRCODE = 'check_violation';
    END IF;

    -- The status edges: OPEN -> IN_INVESTIGATION | DISPOSED; IN_INVESTIGATION -> ESCALATED | DISPOSED;
    -- ESCALATED -> DISPOSED; every live state to itself (a note, evidence, an attach, a re-take, a proposal, a rejection,
    -- a further escalation).
    IF NEW.status <> OLD.status AND NOT (
            (OLD.status = 'OPEN' AND NEW.status IN ('IN_INVESTIGATION', 'DISPOSED'))
            OR (OLD.status = 'IN_INVESTIGATION' AND NEW.status IN ('ESCALATED', 'DISPOSED'))
            OR (OLD.status = 'ESCALATED' AND NEW.status = 'DISPOSED')) THEN
        RAISE EXCEPTION 'a risk case moves only along its machine (P13-TSK-016)' USING ERRCODE = 'check_violation';
    END IF;

    -- An escalation raises the level exactly one step and names a queue above the first line; nothing else moves them.
    IF NEW.level IS DISTINCT FROM OLD.level OR NEW.queue IS DISTINCT FROM OLD.queue
            OR (OLD.status = 'IN_INVESTIGATION' AND NEW.status = 'ESCALATED') THEN
        IF NEW.status <> 'ESCALATED' OR OLD.status NOT IN ('IN_INVESTIGATION', 'ESCALATED')
                OR NOT ((OLD.level = 'L1' AND NEW.level = 'L2') OR (OLD.level = 'L2' AND NEW.level = 'L3'))
                OR NEW.queue = 'FIRST_LINE' THEN
            RAISE EXCEPTION 'an escalation raises a taken case one level and names a queue above the first line'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    -- A taken case keeps an investigator; a take names a person.
    IF OLD.taken_by IS NOT NULL AND NEW.taken_by IS NULL THEN
        RAISE EXCEPTION 'a taken risk case keeps its investigator' USING ERRCODE = 'check_violation';
    END IF;

    -- The disposition slot.
    NEW.disposed_at := OLD.disposed_at;
    IF OLD.disposition_id IS NULL AND NEW.disposition_id IS NOT NULL THEN
        IF NEW.disposition_approved_by IS NULL THEN
            -- A proposal awaiting a second person: on a taken case only.
            IF OLD.status = 'OPEN' THEN
                RAISE EXCEPTION 'a two persons'' disposition is proposed on a case an investigator has taken'
                    USING ERRCODE = 'check_violation';
            END IF;
            NEW.disposition_proposed_at := clock_timestamp();
        ELSE
            -- Decided by its proposal: a one-person close (four eyes stays the CHECK's - it is not judged here).
            IF NEW.disposition NOT IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION') OR NEW.disposition_prohibits THEN
                RAISE EXCEPTION 'only a one-person close is decided by its own proposal'
                    USING ERRCODE = 'check_violation';
            END IF;
            NEW.disposition_proposed_at := clock_timestamp();
            NEW.disposed_at := NEW.disposition_proposed_at;
        END IF;
    ELSIF OLD.disposition_id IS NOT NULL AND NEW.disposition_id IS NULL THEN
        -- The rejection: the slot cleared whole - its stamp with it - and the status unchanged.
        IF num_nonnulls(NEW.disposition, NEW.disposition_prohibits, NEW.disposition_proposed_by,
                        NEW.disposition_proposed_by_type, NEW.disposition_approved_by,
                        NEW.disposition_approved_by_type) <> 0 OR NEW.status <> OLD.status THEN
            RAISE EXCEPTION 'a rejection clears the standing proposal and moves nothing else'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.disposition_proposed_at := NULL;
    ELSIF OLD.disposition_id IS NOT NULL THEN
        -- The standing proposal is decided as proposed, or stands unchanged.
        IF NEW.disposition_id <> OLD.disposition_id OR NEW.disposition IS DISTINCT FROM OLD.disposition
                OR NEW.disposition_prohibits IS DISTINCT FROM OLD.disposition_prohibits
                OR NEW.disposition_proposed_by IS DISTINCT FROM OLD.disposition_proposed_by
                OR NEW.disposition_proposed_by_type IS DISTINCT FROM OLD.disposition_proposed_by_type THEN
            RAISE EXCEPTION 'a standing disposition is decided as it was proposed' USING ERRCODE = 'check_violation';
        END IF;
        NEW.disposition_proposed_at := OLD.disposition_proposed_at;
        IF NEW.disposition_approved_by IS NOT NULL THEN
            -- The approval (four eyes is risk_case_disposition_four_eyes' - provable alone).
            NEW.disposed_at := clock_timestamp();
        END IF;
    ELSE
        NEW.disposition_proposed_at := NULL;
    END IF;

    IF OLD.status = 'OPEN' AND NEW.status = 'DISPOSED'
            AND (NEW.disposition NOT IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION') OR NEW.disposition_prohibits) THEN
        RAISE EXCEPTION 'a case is closed from OPEN only as a false positive or with no action, by one person'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_case_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_case
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_case_permits_only_machine_edges();

CREATE TRIGGER risk_case_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_case
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_case_permits_only_machine_edges();

-- Every revision has its history row, in the state the case reached (checked at commit: a step writes the row after
-- its UPDATE).
CREATE FUNCTION risk.risk_case_has_its_history() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM risk.risk_case_event e
             WHERE e.case_id = NEW.id AND e.revision = NEW.revision
               AND e.status = NEW.status AND e.level = NEW.level AND e.queue = NEW.queue) THEN
        RAISE EXCEPTION 'every step of a risk case has its history row (P13-TSK-016)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER risk_case_has_its_history
    AFTER INSERT OR UPDATE ON risk.risk_case
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_case_has_its_history();

-- The actions: born once and stamped; never edited, deleted or truncated - for every writer.
CREATE FUNCTION risk.risk_case_action_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an investigator action is never edited, deleted or truncated (P13-TSK-016, ADR-0110 decision 4)';
END;
$$;

CREATE TRIGGER risk_case_action_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_case_action
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_case_action_is_append_only();

CREATE TRIGGER risk_case_action_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_case_action
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_case_action_is_append_only();

-- The history: born once and stamped, a person's step naming the action of that very step; never edited, deleted or
-- truncated - for every writer.
CREATE FUNCTION risk.risk_case_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.action_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM risk.risk_case_action a WHERE a.id = NEW.action_id AND a.ordinal = NEW.revision) THEN
            RAISE EXCEPTION 'a person''s step names the action of that step (P13-TSK-016)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a case''s history is never edited, deleted or truncated (P13-TSK-016)';
END;
$$;

CREATE TRIGGER risk_case_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_case_event
    FOR EACH ROW
    EXECUTE FUNCTION risk.risk_case_event_is_append_only();

CREATE TRIGGER risk_case_event_is_never_truncated
    BEFORE TRUNCATE ON risk.risk_case_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.risk_case_event_is_append_only();

-- ---------------------------------------------------------------------------------------------------------------------
-- 5. The foreign keys the earlier tasks left for this one.
-- ---------------------------------------------------------------------------------------------------------------------

-- P13-TSK-015: an attached alert names a case that exists. (Nothing attached before this migration: the folding was
-- NONE, so the constraint validates over no row - a database where something did is refused loudly.)
ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_attached_case_fk
    FOREIGN KEY (attached_case_id) REFERENCES risk.risk_case (id);

-- P13-TSK-007: a DISPOSITION profile version names the disposition that set it - its DISPOSITION_PROPOSED action - and
-- one disposition writes one version (the prohibition's arbiter under ten re-drives; INV-RSK-10). No disposition
-- version exists before this migration (nothing wrote one), so the constraint validates over no row.
ALTER TABLE risk.customer_risk_profile_version ADD CONSTRAINT customer_risk_profile_version_disposition_fk
    FOREIGN KEY (disposition_id) REFERENCES risk.risk_case_action (id);
CREATE UNIQUE INDEX customer_risk_profile_version_one_per_disposition
    ON risk.customer_risk_profile_version (disposition_id) WHERE disposition_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- 6. The grants: SELECT and INSERT; a column-scoped UPDATE of the case's status, revision, level, queue, assignee and
--    disposition slot - never a fact, never a stamp (the triggers'). No DELETE for any role.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.risk_case TO finapp_app;
GRANT UPDATE (status, revision, level, queue, taken_by, taken_by_type, disposition, disposition_id,
    disposition_prohibits, disposition_proposed_by, disposition_proposed_by_type, disposition_approved_by,
    disposition_approved_by_type) ON risk.risk_case TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_case_action TO finapp_app;
GRANT SELECT, INSERT ON risk.risk_case_event TO finapp_app;
