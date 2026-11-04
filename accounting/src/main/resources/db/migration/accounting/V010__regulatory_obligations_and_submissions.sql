-- Regulatory obligations and submissions: the reporting framework behind a simulated adapter (P14-TSK-018).
--
-- ADR-0118, owner decision C4, INV-ACC-13, INV-ACC-04, INV-HIST-02, INV-AUD-01, INV-AUD-04; PHASE_14_PLAN.md sections 6, 8.3 row 13, 8.4 (d),
-- 9, 13.7. This migration moves no money. Its worst failures are about what a regulator is told: a submitted line no FINAL figure explains; a
-- return drawn from a month since reopened and re-closed; one return sent twice, or never delivered and never noticed; a state no door leaves.
--
-- NO REAL JURISDICTION: `standing` admits 'REFERENCE' and nothing else, on the obligation and in every submission's content. A real one needs a
-- migration, a chosen jurisdiction and a form verified against its text. NO SEED: an obligation arrives through the four-eyes doors, never here.
--
-- A STATUS CAN ROUND-TRIP. A month closed, reopened and re-closed reads CLOSED again, so a status cannot say which books a return read. The
-- period's REVISION and the RUN's id and hash can. regulatory_submission_judge_stands compares them, under the period row FOR SHARE, at every edge
-- going forward, for every writer; the domain does the same first. A reopen's approval holds that row FOR UPDATE, supersedes the FINAL runs and
-- moves the revision before it commits, so it and an edge here serialise, and each sees what the other committed: an edge first leaves a frozen
-- return against the old revision, which a reader is told of; the reopen first leaves the edge refused. There is no third order.
--
-- THE LINES ARE HELD TWICE, AND HELD EQUAL. `content` is what a person attests and its hash what they name; regulatory_submission_line is what can be
-- NOT NULL and foreign-keyed. regulatory_submission_lines_agree compares them at the birth's commit (a deferred constraint trigger) and at every edge
-- going forward. A line with no run, hash or path cannot exist.
--
-- CONTENT IS TEXT, NOT jsonb (P14-TSK-008's finding): jsonb re-orders and re-spaces, and these bytes are hashed. Each hash is recomputed here at the
-- one moment its text is written; neither column is in any grant's UPDATE. Amounts are minor units in text with their currency and scale - output of a
-- FINAL run, never truth (V001): the journal is the truth, and these rows are a return's own record of what it said.
--
-- FOUR EYES, AND THE APPROVER DOES NOT SEND: regulatory_submission_four_eyes (the row), regulatory_submission_event_four_eyes (the history),
-- regulatory_obligation_four_eyes; regulatory_submission_approver_does_not_send. One identity can hold two roles (P14-TSK-001 deferred separation at
-- assignment to Phase 15), so each is held at the act.
--
-- SUBMITTED COMMITS BEFORE THE CALL, keyed at the provider by the submission's id. The first claimed attempt sends; every later one inquires, and sends
-- again only after the provider's definite NOT_RECEIVED, under the same id, counted and committed before the call with last_answer RESENDING, so a person
-- cannot conclude UNDELIVERED over a send in flight. The first definite answer takes the one-way edge; a later or contrary one is kept verbatim and flagged,
-- never an edge. A SEND DOES NOT GO ONCE THE MONTH HAS MOVED: the first attempt then only inquires, and a resend's permit is refused, here too. What no migration
-- can close: a send that began just before a reopen committed. The rule is that the month stood when the send began.
--
-- THE LOCKS (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): (A1) the period FOR SHARE; the obligation FOR SHARE; (A4) the submission FOR UPDATE, an
-- original before its correction; the run rows FOR SHARE beneath it, which nothing but a period holder FOR UPDATE moves. A driver's record takes the row
-- alone: it moves a frozen return and asks nothing of the month. Nothing that holds a submission row waits on a period a reopen holds while wanting that
-- submission. The application role holds the column UPDATE each FOR SHARE needs (V003, V004, and the grants below).
--
-- No DELETE for any role; never truncated. Every stamp clock_timestamp(). The migration names no other schema.

-- ---------------------------------------------------------------------------------------------
-- The obligation: configuration as data, four eyes, frozen but for its one decision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.regulatory_obligation (
    id                   uuid        NOT NULL,
    jurisdiction_code    text        NOT NULL,
    form_code            text        NOT NULL,
    form_version         integer     NOT NULL,
    period_type          text        NOT NULL,
    adapter_name         text        NOT NULL,
    standing             text        NOT NULL,
    configuration        text        NOT NULL,
    configuration_sha256 text        NOT NULL,
    status               text        NOT NULL,
    proposed_by          text        NOT NULL,
    proposed_by_type     text        NOT NULL,
    proposed_at          timestamptz NOT NULL,
    proposal_reason      text        NOT NULL,
    decided_by           text,
    decided_by_type      text,
    decided_at           timestamptz,
    decision_reason      text,
    retired_at           timestamptz,
    correlation_id       text        NOT NULL,
    CONSTRAINT regulatory_obligation_pk PRIMARY KEY (id),
    CONSTRAINT regulatory_obligation_one_version UNIQUE (jurisdiction_code, form_code, form_version),
    -- RegulatoryObligationStatus.
    CONSTRAINT regulatory_obligation_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED', 'RETIRED')),
    -- Reference only: no row here can claim a real jurisdiction's form until a migration says otherwise (owner decision C4).
    CONSTRAINT regulatory_obligation_is_reference CHECK (standing = 'REFERENCE'),
    -- A month is the only period this platform closes (owner decision C9).
    CONSTRAINT regulatory_obligation_period_type_is_known CHECK (period_type = 'MONTH'),
    CONSTRAINT regulatory_obligation_codes_shaped CHECK (
        jurisdiction_code ~ '^[A-Z]{2,8}(-[A-Z0-9]{1,16})?$'
        AND form_code ~ '^[A-Z][A-Z0-9_-]{1,63}$'
        AND adapter_name ~ '^[a-z][a-z0-9-]{1,63}$'
        AND form_version BETWEEN 1 AND 9999),
    CONSTRAINT regulatory_obligation_hash_shaped CHECK (configuration_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT regulatory_obligation_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    CONSTRAINT regulatory_obligation_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)
        AND (status = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT regulatory_obligation_reasoned CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever activated it did not propose it. A rejection may be the proposer's.
    CONSTRAINT regulatory_obligation_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by)
);

COMMENT ON TABLE accounting.regulatory_obligation IS
    'A regulatory obligation version (P14-TSK-018, ADR-0118, C4): configuration data, REFERENCE only - jurisdiction code, form code and version, period type, adapter, and in configuration the source definitions, line mapping and closed-vocabulary rules. PROPOSED -> ACTIVE (a second person) | REJECTED; ACTIVE -> RETIRED by its successor. Never SQL or script from a row. Never deleted.';
COMMENT ON COLUMN accounting.regulatory_obligation.configuration IS
    'Canonical JSON text, hashed by configuration_sha256, recomputed at the birth and frozen. Text and not jsonb because the bytes are hashed.';

-- One version in force per form - the backstop beneath the form's rows locked in id order.
CREATE UNIQUE INDEX regulatory_obligation_one_active ON accounting.regulatory_obligation (jurisdiction_code, form_code) WHERE status = 'ACTIVE';
-- What a controller finds waiting.
CREATE INDEX regulatory_obligation_awaiting ON accounting.regulatory_obligation (proposed_at, id) WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION accounting.regulatory_obligation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    configured jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a regulatory obligation is never deleted (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.decision_reason IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a regulatory obligation is born PROPOSED and undecided (P14-TSK-018)';
        END IF;
        -- THE CONFIGURATION, checked at the one moment it is written: bounded before it is parsed, an object, its hash its own, every rule of the closed vocabulary.
        IF octet_length(NEW.configuration) > 262144 THEN
            RAISE EXCEPTION 'regulatory_obligation_configuration_bounded: at most 256 KiB (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        configured := NEW.configuration::jsonb;
        IF jsonb_typeof(configured) <> 'object' THEN
            RAISE EXCEPTION 'regulatory_obligation_configuration_is_an_object (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.configuration_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.configuration, 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'regulatory_obligation_configuration_hashed: the hash is the text''s own (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        IF jsonb_typeof(configured -> 'rules') IS DISTINCT FROM 'array' OR jsonb_array_length(configured -> 'rules') = 0
                OR EXISTS (SELECT 1 FROM jsonb_array_elements(configured -> 'rules') AS rule
                            WHERE (rule ->> 'type') IS NULL OR (rule ->> 'type') NOT IN ('NON_NEGATIVE', 'EQUALS', 'NOT_LESS_THAN')) THEN
            RAISE EXCEPTION 'regulatory_obligation_rules_are_of_the_vocabulary: never SQL or script from a row (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code OR NEW.form_code IS DISTINCT FROM OLD.form_code
            OR NEW.form_version IS DISTINCT FROM OLD.form_version OR NEW.period_type IS DISTINCT FROM OLD.period_type
            OR NEW.adapter_name IS DISTINCT FROM OLD.adapter_name OR NEW.standing IS DISTINCT FROM OLD.standing
            OR NEW.configuration IS DISTINCT FROM OLD.configuration OR NEW.configuration_sha256 IS DISTINCT FROM OLD.configuration_sha256
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a regulatory obligation is frozen but for its one decision: a controller activates what they read (P14-TSK-018, INV-AUD-04)';
    END IF;
    IF OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED') THEN
        IF NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a decision records no retirement (P14-TSK-018)';
        END IF;
        -- NEVER BACKWARDS: an activation is a later form version than any already in force or retired. The form's rows are the domain's, locked in id order.
        IF NEW.status = 'ACTIVE' AND EXISTS (
                SELECT 1 FROM accounting.regulatory_obligation o
                 WHERE o.jurisdiction_code = NEW.jurisdiction_code AND o.form_code = NEW.form_code AND o.id <> NEW.id
                   AND o.status IN ('ACTIVE', 'RETIRED') AND o.form_version >= NEW.form_version) THEN
            RAISE EXCEPTION 'a regulatory obligation activates a form version later than any in force or retired (P14-TSK-018)';
        END IF;
        NEW.decided_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P14-TSK-018)';
        END IF;
        NEW.retired_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory obligation moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P14-TSK-018)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER regulatory_obligation_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_obligation
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_obligation_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.regulatory_record_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the regulatory record is never truncated (P14-TSK-018, INV-ACC-13, INV-HIST-02)';
END;
$$;

CREATE TRIGGER regulatory_obligation_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_obligation
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The submission: one return of one obligation version for one month at one revision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.regulatory_submission (
    id                       uuid        NOT NULL,
    obligation_id            uuid        NOT NULL,
    jurisdiction_code        text        NOT NULL,
    form_code                text        NOT NULL,
    form_version             integer     NOT NULL,
    period_id                uuid        NOT NULL,
    period_revision          integer     NOT NULL,
    corrects_submission_id   uuid,
    content                  text        NOT NULL,
    content_sha256           text        NOT NULL,
    status                   text        NOT NULL,
    prepared_by              text        NOT NULL,
    prepared_by_type         text        NOT NULL,
    prepared_at              timestamptz NOT NULL,
    preparation_reason       text        NOT NULL,
    validation_outcome       text,
    validation_passed        boolean,
    validated_by             text,
    validated_by_type        text,
    validated_at             timestamptz,
    approved_by              text,
    approved_by_type         text,
    approved_at              timestamptz,
    approval_reason          text,
    approved_content_sha256  text,
    approved_period_revision integer,
    submitted_by             text,
    submitted_by_type        text,
    submitted_at             timestamptz,
    submission_reason        text,
    closed_by                text,
    closed_by_type           text,
    closed_at                timestamptz,
    closing_reason           text,
    attempts                 integer     NOT NULL,
    resends                  integer     NOT NULL,
    next_inquiry_at          timestamptz,
    answer_horizon_at        timestamptz,
    last_answer              text,
    provider_reference       text,
    rejection_code           text,
    concluded_at             timestamptz,
    correlation_id           text        NOT NULL,
    CONSTRAINT regulatory_submission_pk PRIMARY KEY (id),
    CONSTRAINT regulatory_submission_obligation_fk FOREIGN KEY (obligation_id) REFERENCES accounting.regulatory_obligation (id),
    CONSTRAINT regulatory_submission_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    CONSTRAINT regulatory_submission_corrects_fk FOREIGN KEY (corrects_submission_id) REFERENCES accounting.regulatory_submission (id),
    -- SubmissionStatus.
    CONSTRAINT regulatory_submission_status_is_known CHECK (status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'REFUSED', 'WITHDRAWN', 'UNDELIVERED')),
    CONSTRAINT regulatory_submission_revision_positive CHECK (period_revision >= 1),
    CONSTRAINT regulatory_submission_content_hash_shaped CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT regulatory_submission_not_its_own_correction CHECK (corrects_submission_id IS DISTINCT FROM id),
    -- Persons only: an authenticated human prepares, validates, attests, sends and closes. Only the provider's answers are recorded by the platform, and they name no slot.
    CONSTRAINT regulatory_submission_persons CHECK (
        prepared_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (validated_by_type IS NULL OR validated_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (submitted_by_type IS NULL OR submitted_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (closed_by_type IS NULL OR closed_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- A validation is whole; what went forward passed.
    CONSTRAINT regulatory_submission_validation_coherent CHECK (
        (validated_by IS NULL) = (validated_by_type IS NULL)
        AND (validated_by IS NULL) = (validated_at IS NULL)
        AND (validated_by IS NULL) = (validation_outcome IS NULL)
        AND (validated_by IS NULL) = (validation_passed IS NULL)
        AND (status NOT IN ('VALIDATED', 'APPROVED', 'SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED') OR validation_passed IS TRUE)),
    -- An attestation is whole, names exactly this content and this revision; nothing is approved or sent without one.
    CONSTRAINT regulatory_submission_approval_coherent CHECK (
        (approved_by IS NULL) = (approved_by_type IS NULL)
        AND (approved_by IS NULL) = (approved_at IS NULL)
        AND (approved_by IS NULL) = (approval_reason IS NULL)
        AND (approved_by IS NULL) = (approved_content_sha256 IS NULL)
        AND (approved_by IS NULL) = (approved_period_revision IS NULL)
        AND (approved_content_sha256 IS NULL OR approved_content_sha256 = content_sha256)
        AND (approved_period_revision IS NULL OR approved_period_revision = period_revision)
        AND (status NOT IN ('APPROVED', 'SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED') OR approved_by IS NOT NULL)),
    -- FOUR EYES, for every writer (INV-ACC-13, INV-AUD-04): whoever attested it did not prepare it.
    CONSTRAINT regulatory_submission_four_eyes CHECK (approved_by IS NULL OR approved_by <> prepared_by),
    -- Whoever attested it does not send it: by role they cannot, and one identity can hold two roles.
    CONSTRAINT regulatory_submission_approver_does_not_send CHECK (submitted_by IS NULL OR submitted_by <> approved_by),
    -- A sending is whole, and every sent status has one; the drive's permit stands from it on.
    CONSTRAINT regulatory_submission_sending_coherent CHECK (
        (submitted_by IS NULL) = (submitted_by_type IS NULL)
        AND (submitted_by IS NULL) = (submitted_at IS NULL)
        AND (submitted_by IS NULL) = (submission_reason IS NULL)
        AND (submitted_by IS NULL) = (next_inquiry_at IS NULL)
        AND (submitted_by IS NULL) = (answer_horizon_at IS NULL)
        AND (status IN ('SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED')) = (submitted_by IS NOT NULL)),
    -- A person's closing is whole, and only of the three statuses a person ends.
    CONSTRAINT regulatory_submission_closing_coherent CHECK (
        (closed_by IS NULL) = (closed_by_type IS NULL)
        AND (closed_by IS NULL) = (closed_at IS NULL)
        AND (closed_by IS NULL) = (closing_reason IS NULL)
        AND (status IN ('REFUSED', 'WITHDRAWN', 'UNDELIVERED')) = (closed_by IS NOT NULL)),
    -- The provider's answer: an acknowledgement carries its reference, a rejection exactly its code, a conclusion its stamp.
    CONSTRAINT regulatory_submission_answer_coherent CHECK (
        (status <> 'ACKNOWLEDGED' OR provider_reference IS NOT NULL)
        AND (status = 'REJECTED') = (rejection_code IS NOT NULL)
        AND (status IN ('ACKNOWLEDGED', 'REJECTED')) = (concluded_at IS NOT NULL)
        AND (provider_reference IS NULL OR provider_reference ~ '^[A-Za-z0-9._:-]{1,128}$')
        AND (rejection_code IS NULL OR rejection_code ~ '^[A-Z][A-Z0-9_]{0,63}$')),
    CONSTRAINT regulatory_submission_last_answer_is_known CHECK (
        last_answer IS NULL OR last_answer IN ('ACKNOWLEDGED', 'REJECTED', 'UNKNOWN', 'NOT_RECEIVED', 'RESENDING')),
    CONSTRAINT regulatory_submission_drive_positive CHECK (attempts >= 0 AND resends >= 0),
    CONSTRAINT regulatory_submission_reasoned CHECK (
        char_length(preparation_reason) BETWEEN 1 AND 1000
        AND (approval_reason IS NULL OR char_length(approval_reason) BETWEEN 1 AND 1000)
        AND (submission_reason IS NULL OR char_length(submission_reason) BETWEEN 1 AND 1000)
        AND (closing_reason IS NULL OR char_length(closing_reason) BETWEEN 1 AND 1000))
);

COMMENT ON TABLE accounting.regulatory_submission IS
    'A regulatory submission (P14-TSK-018, ADR-0118, INV-ACC-13): one return, REFERENCE only, of one obligation version for one month at one revision, drawn from FINAL report runs. DRAFT -> VALIDATED -> APPROVED (a second person) -> SUBMITTED -> ACKNOWLEDGED | REJECTED (the provider); -> WITHDRAWN (a preparer), -> REFUSED (a controller), SUBMITTED -> UNDELIVERED (a person, on a definite not received). Content frozen from birth. A correction is a new row. Never deleted.';
COMMENT ON COLUMN accounting.regulatory_submission.content IS
    'The canonical JSON text a person attests: header, sources and lines, amounts as minor units in strings. CONFIDENTIAL. content_sha256 is recomputed at the birth; neither column is in the UPDATE grant. Text and not jsonb because the bytes are hashed.';
COMMENT ON COLUMN accounting.regulatory_submission.period_revision IS
    'The month''s revision when drawn. CLOSED round-trips and a revision does not, so this and the runs'' ids and hashes are what every edge compares.';

-- LIVE: not yet concluded. One live return per form and month - ten drafts, one row. A correction is live like any other, and an original it names is not.
CREATE UNIQUE INDEX regulatory_submission_one_live ON accounting.regulatory_submission (jurisdiction_code, form_code, period_id)
    WHERE status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED');
-- Two corrections of one original: one stands. One withdrawn or refused frees it for another, so the chain stays a line.
CREATE UNIQUE INDEX regulatory_submission_one_correction ON accounting.regulatory_submission (corrects_submission_id)
    WHERE corrects_submission_id IS NOT NULL AND status NOT IN ('REFUSED', 'WITHDRAWN');
-- The inquiry leg's claim.
CREATE INDEX regulatory_submission_due ON accounting.regulatory_submission (next_inquiry_at, id) WHERE status = 'SUBMITTED';
-- What a controller finds waiting.
CREATE INDEX regulatory_submission_awaiting ON accounting.regulatory_submission (validated_at, id) WHERE status = 'VALIDATED';
-- A form and month's rows - the plain draft's check.
CREATE INDEX regulatory_submission_of_form ON accounting.regulatory_submission (jurisdiction_code, form_code, period_id);
-- A month's rows - the attention gauge's join.
CREATE INDEX regulatory_submission_of_period ON accounting.regulatory_submission (period_id, status);

-- ---------------------------------------------------------------------------------------------
-- Lineage: each source run by id and hash, each line by run, hash and path. Born with the draft, append-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.regulatory_submission_source (
    submission_id      uuid    NOT NULL,
    run_id             uuid    NOT NULL,
    run_sha256         text    NOT NULL,
    definition         text    NOT NULL,
    definition_version integer NOT NULL,
    CONSTRAINT regulatory_submission_source_pk PRIMARY KEY (submission_id, run_id),
    CONSTRAINT regulatory_submission_source_one_per_definition UNIQUE (submission_id, definition),
    CONSTRAINT regulatory_submission_source_submission_fk FOREIGN KEY (submission_id) REFERENCES accounting.regulatory_submission (id),
    CONSTRAINT regulatory_submission_source_run_fk FOREIGN KEY (run_id) REFERENCES accounting.report_run (id),
    CONSTRAINT regulatory_submission_source_hash_shaped CHECK (run_sha256 ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE accounting.regulatory_submission_source IS
    'A submission''s lineage to a report run (P14-TSK-018, INV-ACC-13): the run''s id and hash, born only for a FINAL run of the submission''s month at its revision. Append-only.';

CREATE TABLE accounting.regulatory_submission_line (
    submission_id     uuid    NOT NULL,
    ordinal           integer NOT NULL,
    line_code         text    NOT NULL,
    label             text    NOT NULL,
    currency          text    NOT NULL,
    minor_units       text    NOT NULL,
    scale             integer NOT NULL,
    run_id            uuid    NOT NULL,
    run_sha256        text    NOT NULL,
    source_definition text    NOT NULL,
    figure_path       text    NOT NULL,
    CONSTRAINT regulatory_submission_line_pk PRIMARY KEY (submission_id, ordinal),
    CONSTRAINT regulatory_submission_line_one_per_currency UNIQUE (submission_id, line_code, currency),
    -- The line's run is one of the submission's sources: a line no named run explains cannot exist.
    CONSTRAINT regulatory_submission_line_source_fk FOREIGN KEY (submission_id, run_id) REFERENCES accounting.regulatory_submission_source (submission_id, run_id),
    CONSTRAINT regulatory_submission_line_ordinal_positive CHECK (ordinal >= 1),
    -- Minor units in text, the currency and scale explicit (INV-MON-01): never a float, and FigurePath's own shapes.
    CONSTRAINT regulatory_submission_line_amount_shaped CHECK (currency ~ '^[A-Z]{3}$' AND minor_units ~ '^-?[0-9]{1,40}$' AND scale BETWEEN 0 AND 99),
    CONSTRAINT regulatory_submission_line_hash_shaped CHECK (run_sha256 ~ '^[0-9a-f]{64}$'),
    -- FigurePath.MAX_LENGTH, and it starts at the body.
    CONSTRAINT regulatory_submission_line_bounded CHECK (char_length(figure_path) BETWEEN 6 AND 256 AND figure_path LIKE 'body.%' AND char_length(label) BETWEEN 1 AND 200)
);

COMMENT ON TABLE accounting.regulatory_submission_line IS
    'One line of a return (P14-TSK-018, INV-ACC-13): its amount as issued - minor units in text, currency, scale - and its lineage, the run, its hash and the resolved figure path. CONFIDENTIAL aggregates, output of a FINAL run and never truth. Append-only; held equal to the submission''s content.';

CREATE OR REPLACE FUNCTION accounting.regulatory_submission_source_is_a_final_run()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status  text;
    parent_period  uuid;
    parent_rev     integer;
    held_status    text;
    held_revision  integer;
    run_status     text;
    run_basis      text;
    run_period     uuid;
    run_rev        integer;
    run_definition text;
    run_version    integer;
    run_hash       text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a submission''s source is append-only (P14-TSK-018, INV-ACC-13)';
    END IF;
    SELECT s.status, s.period_id, s.period_revision INTO parent_status, parent_period, parent_rev
      FROM accounting.regulatory_submission s WHERE s.id = NEW.submission_id FOR SHARE;
    IF NOT FOUND OR parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'a submission''s sources are born with its draft (P14-TSK-018, INV-ACC-13)';
    END IF;
    SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = parent_period FOR SHARE;
    IF NOT FOUND OR held_status <> 'CLOSED' OR held_revision <> parent_rev THEN
        RAISE EXCEPTION 'a submission draws only from a CLOSED period at the revision it names (P14-TSK-018, INV-ACC-13)';
    END IF;
    SELECT r.status, r.basis, r.period_id, r.period_revision, r.definition, r.definition_version, encode(sha256(convert_to(r.content, 'UTF8')), 'hex')
      INTO run_status, run_basis, run_period, run_rev, run_definition, run_version, run_hash
      FROM accounting.report_run r WHERE r.id = NEW.run_id FOR SHARE;
    IF NOT FOUND OR run_status <> 'FINAL' OR run_basis <> 'FINAL' THEN
        RAISE EXCEPTION 'regulatory_submission_source_is_a_final_run: a submission draws only from a FINAL report run (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF run_period <> parent_period OR run_rev <> parent_rev OR run_definition <> NEW.definition OR run_version <> NEW.definition_version
            OR run_hash IS DISTINCT FROM NEW.run_sha256 THEN
        RAISE EXCEPTION 'regulatory_submission_source_is_a_final_run: the run is of the submission''s month at its revision, its bytes the hash named (P14-TSK-018, INV-ACC-13)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER regulatory_submission_source_is_a_final_run
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_submission_source
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_submission_source_is_a_final_run();

CREATE OR REPLACE FUNCTION accounting.regulatory_submission_line_is_born_with_its_draft()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status  text;
    parent_outcome text;
    source_hash    text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a submission''s line is append-only (P14-TSK-018, INV-ACC-13)';
    END IF;
    SELECT s.status, s.validation_outcome INTO parent_status, parent_outcome
      FROM accounting.regulatory_submission s WHERE s.id = NEW.submission_id FOR SHARE;
    IF NOT FOUND OR parent_status <> 'DRAFT' OR parent_outcome IS NOT NULL THEN
        RAISE EXCEPTION 'a submission''s lines are born with its draft (P14-TSK-018, INV-ACC-13)';
    END IF;
    -- The source row is append-only, so nothing moves under this read.
    SELECT o.run_sha256 INTO source_hash FROM accounting.regulatory_submission_source o WHERE o.submission_id = NEW.submission_id AND o.run_id = NEW.run_id;
    IF source_hash IS DISTINCT FROM NEW.run_sha256 THEN
        RAISE EXCEPTION 'a line names its source run''s hash (P14-TSK-018, INV-ACC-13)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER regulatory_submission_line_is_born_with_its_draft
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_submission_line
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_submission_line_is_born_with_its_draft();

-- ---------------------------------------------------------------------------------------------
-- The two judgements, for every writer.
-- ---------------------------------------------------------------------------------------------

-- THE RETURN STILL STANDS on the books it read: the month CLOSED at the revision pinned, the obligation in force, every source run FINAL, of that month and
-- revision, id and bytes as pinned. Period first, then the obligation, then the runs: the domain's order. The runs FOR SHARE, ordered: nothing but a holder of the
-- period row FOR UPDATE moves one, and that holder waits on this share.
CREATE OR REPLACE FUNCTION accounting.regulatory_submission_judge_stands(p_submission uuid, p_period uuid, p_revision integer, p_obligation uuid)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status   text;
    held_revision integer;
    held_standing text;
    sources       bigint := 0;
    source_row    record;
    run_status    text;
    run_period    uuid;
    run_rev       integer;
    run_hash      text;
BEGIN
    SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = p_period FOR SHARE;
    IF NOT FOUND OR held_status <> 'CLOSED' OR held_revision <> p_revision THEN
        RAISE EXCEPTION 'a regulatory submission moves only while its month is CLOSED at the revision it read: that month moved (P14-TSK-018, INV-ACC-13)';
    END IF;
    SELECT o.status INTO held_standing FROM accounting.regulatory_obligation o WHERE o.id = p_obligation FOR SHARE;
    IF NOT FOUND OR held_standing <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a regulatory submission moves only while its obligation version is in force (P14-TSK-018)';
    END IF;
    FOR source_row IN SELECT s.run_id, s.run_sha256 FROM accounting.regulatory_submission_source s WHERE s.submission_id = p_submission ORDER BY s.run_id LOOP
        sources := sources + 1;
        SELECT r.status, r.period_id, r.period_revision, encode(sha256(convert_to(r.content, 'UTF8')), 'hex')
          INTO run_status, run_period, run_rev, run_hash
          FROM accounting.report_run r WHERE r.id = source_row.run_id FOR SHARE;
        IF NOT FOUND OR run_status <> 'FINAL' OR run_period <> p_period OR run_rev <> p_revision OR run_hash IS DISTINCT FROM source_row.run_sha256 THEN
            RAISE EXCEPTION 'a regulatory submission moves only while each run it read stands FINAL, its bytes the hash it named (P14-TSK-018, INV-ACC-13, INV-ACC-04)';
        END IF;
    END LOOP;
    IF sources = 0 THEN
        RAISE EXCEPTION 'a regulatory submission draws from at least one run (P14-TSK-018, INV-ACC-13)';
    END IF;
END;
$$;

-- THE ROWS ARE THE CONTENT: every line stated is a row, every row is stated, field by field; every source likewise. Text compared with text: canonical JSON has no number.
CREATE OR REPLACE FUNCTION accounting.regulatory_submission_lines_agree(p_submission uuid, p_content text)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    stated_lines   jsonb := p_content::jsonb -> 'lines';
    stated_sources jsonb := p_content::jsonb -> 'sources';
    held_lines     bigint;
    held_sources   bigint;
BEGIN
    SELECT count(*) INTO held_lines FROM accounting.regulatory_submission_line l WHERE l.submission_id = p_submission;
    SELECT count(*) INTO held_sources FROM accounting.regulatory_submission_source s WHERE s.submission_id = p_submission;
    IF jsonb_typeof(stated_lines) IS DISTINCT FROM 'array' OR jsonb_typeof(stated_sources) IS DISTINCT FROM 'array' OR held_lines = 0 OR held_sources = 0
            OR held_lines <> jsonb_array_length(stated_lines) OR held_sources <> jsonb_array_length(stated_sources) THEN
        RAISE EXCEPTION 'regulatory_submission_lines_agree: a submission''s rows are the lines and sources its content states (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF EXISTS (SELECT 1 FROM accounting.regulatory_submission_line l
                WHERE l.submission_id = p_submission
                  AND ((stated_lines -> (l.ordinal - 1)) ->> 'code' IS DISTINCT FROM l.line_code
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'label' IS DISTINCT FROM l.label
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'currency' IS DISTINCT FROM l.currency
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'minor' IS DISTINCT FROM l.minor_units
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'scale' IS DISTINCT FROM l.scale::text
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'runId' IS DISTINCT FROM l.run_id::text
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'runSha256' IS DISTINCT FROM l.run_sha256
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'source' IS DISTINCT FROM l.source_definition
                    OR (stated_lines -> (l.ordinal - 1)) ->> 'figure' IS DISTINCT FROM l.figure_path)) THEN
        RAISE EXCEPTION 'regulatory_submission_lines_agree: a submission''s rows are the lines its content states (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF EXISTS (SELECT 1 FROM accounting.regulatory_submission_source s
                WHERE s.submission_id = p_submission
                  AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(stated_sources) AS stated
                                   WHERE stated ->> 'runId' = s.run_id::text AND stated ->> 'runSha256' = s.run_sha256
                                     AND stated ->> 'definition' = s.definition AND stated ->> 'definitionVersion' = s.definition_version::text)) THEN
        RAISE EXCEPTION 'regulatory_submission_lines_agree: a submission''s rows are the sources its content states (P14-TSK-018, INV-ACC-13)';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The machine.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION accounting.regulatory_submission_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status     text;
    held_revision   integer;
    held_month      date;
    held_obligation text;
    held_form       text;
    held_jurisdiction text;
    held_version    integer;
    held_configured text;
    original_status text;
    original_form   text;
    original_jurisdiction text;
    original_period uuid;
    stated          jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a regulatory submission is never deleted (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.validation_outcome IS NOT NULL OR NEW.validation_passed IS NOT NULL OR NEW.validated_by IS NOT NULL
                OR NEW.approved_by IS NOT NULL OR NEW.submitted_by IS NOT NULL OR NEW.closed_by IS NOT NULL OR NEW.attempts <> 0 OR NEW.resends <> 0
                OR NEW.next_inquiry_at IS NOT NULL OR NEW.answer_horizon_at IS NOT NULL OR NEW.last_answer IS NOT NULL
                OR NEW.provider_reference IS NOT NULL OR NEW.rejection_code IS NOT NULL OR NEW.concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a regulatory submission is born DRAFT with nothing acted (P14-TSK-018, INV-ACC-13)';
        END IF;
        -- ONLY FROM A CLOSED MONTH, AT THE REVISION IT NAMES. The period first: the domain's order.
        SELECT p.status, p.revision, p.period_start INTO held_status, held_revision, held_month FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
        IF NOT FOUND OR held_status <> 'CLOSED' OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a regulatory submission is drawn only from a CLOSED period at its present revision (P14-TSK-018, INV-ACC-13)';
        END IF;
        -- THE VERSION IN FORCE, ITS CODES COPIED FAITHFULLY - they are copied so the partial uniques can name them.
        SELECT o.status, o.jurisdiction_code, o.form_code, o.form_version, o.configuration_sha256 INTO held_obligation, held_jurisdiction, held_form, held_version, held_configured
          FROM accounting.regulatory_obligation o WHERE o.id = NEW.obligation_id FOR SHARE;
        IF NOT FOUND OR held_obligation <> 'ACTIVE' OR held_jurisdiction <> NEW.jurisdiction_code OR held_form <> NEW.form_code OR held_version <> NEW.form_version THEN
            RAISE EXCEPTION 'a regulatory submission pins its obligation version in force, its form as stated (P14-TSK-018)';
        END IF;
        -- THE CONTENT, checked at the one moment it is written: bounded, an object, its hash its own, REFERENCE, and its header this row's.
        IF octet_length(NEW.content) > 2097152 THEN
            RAISE EXCEPTION 'regulatory_submission_content_bounded: at most 2 MiB (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        stated := NEW.content::jsonb;
        IF jsonb_typeof(stated) <> 'object' THEN
            RAISE EXCEPTION 'regulatory_submission_content_is_an_object (P14-TSK-018)' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.content_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.content, 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'regulatory_submission_content_hashed: the hash is the text''s own (P14-TSK-018, INV-ACC-13)' USING ERRCODE = 'check_violation';
        END IF;
        IF stated ->> 'standing' IS DISTINCT FROM 'REFERENCE' OR stated ->> 'obligationId' IS DISTINCT FROM NEW.obligation_id::text
                OR stated ->> 'jurisdiction' IS DISTINCT FROM NEW.jurisdiction_code OR stated ->> 'form' IS DISTINCT FROM NEW.form_code
                OR stated ->> 'formVersion' IS DISTINCT FROM NEW.form_version::text OR stated ->> 'configurationSha256' IS DISTINCT FROM held_configured
                OR stated ->> 'period' IS DISTINCT FROM to_char(held_month, 'YYYY-MM')
                OR stated ->> 'periodRevision' IS DISTINCT FROM NEW.period_revision::text
                OR stated ->> 'correctsSubmissionId' IS DISTINCT FROM NEW.corrects_submission_id::text THEN
            RAISE EXCEPTION 'regulatory_submission_content_is_this_rows: REFERENCE, and its header this row''s (P14-TSK-018, INV-ACC-13)' USING ERRCODE = 'check_violation';
        END IF;
        -- THE CHAIN. SUBMITTED is read among the sent on purpose: an acknowledgement committing while this insert waits on the live unique leaves the old entry dead, so
        -- the unique alone would let a plain draft through; a row that has reached SUBMITTED never goes back, so one read here closes that. Raised as the unique's own
        -- SQLSTATE: the domain reads it as the same refusal.
        IF NEW.corrects_submission_id IS NULL THEN
            IF EXISTS (SELECT 1 FROM accounting.regulatory_submission s
                        WHERE s.jurisdiction_code = NEW.jurisdiction_code AND s.form_code = NEW.form_code AND s.period_id = NEW.period_id
                          AND s.status IN ('SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED')) THEN
                RAISE EXCEPTION 'regulatory_submission_names_what_was_sent: a return of this form and month was sent; a later one is a correction naming it (P14-TSK-018, INV-ACC-13)' USING ERRCODE = 'unique_violation';
            END IF;
        ELSE
            SELECT o.status, o.jurisdiction_code, o.form_code, o.period_id INTO original_status, original_jurisdiction, original_form, original_period
              FROM accounting.regulatory_submission o WHERE o.id = NEW.corrects_submission_id FOR SHARE;
            IF NOT FOUND OR original_status NOT IN ('ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED') OR original_jurisdiction <> NEW.jurisdiction_code
                    OR original_form <> NEW.form_code OR original_period <> NEW.period_id THEN
                RAISE EXCEPTION 'a correction references a concluded return of its own form and month (P14-TSK-018, INV-ACC-13)';
            END IF;
        END IF;
        NEW.prepared_at := clock_timestamp();
        RETURN NEW;
    END IF;
    -- What was drawn and who drew it never move, for any writer. SUBMITTED frozen a fortiori.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.obligation_id IS DISTINCT FROM OLD.obligation_id OR NEW.jurisdiction_code IS DISTINCT FROM OLD.jurisdiction_code
            OR NEW.form_code IS DISTINCT FROM OLD.form_code OR NEW.form_version IS DISTINCT FROM OLD.form_version OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision OR NEW.corrects_submission_id IS DISTINCT FROM OLD.corrects_submission_id
            OR NEW.content IS DISTINCT FROM OLD.content OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
            OR NEW.prepared_by IS DISTINCT FROM OLD.prepared_by OR NEW.prepared_by_type IS DISTINCT FROM OLD.prepared_by_type
            OR NEW.prepared_at IS DISTINCT FROM OLD.prepared_at OR NEW.preparation_reason IS DISTINCT FROM OLD.preparation_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'regulatory_submission_is_frozen: what a submission drew and who drew it never change (P14-TSK-018, INV-ACC-13)';
    END IF;
    -- Each act's slot is written once, on its own edge.
    IF OLD.validated_by IS NOT NULL AND (NEW.validated_by IS DISTINCT FROM OLD.validated_by OR NEW.validated_by_type IS DISTINCT FROM OLD.validated_by_type
            OR NEW.validated_at IS DISTINCT FROM OLD.validated_at OR NEW.validation_outcome IS DISTINCT FROM OLD.validation_outcome
            OR NEW.validation_passed IS DISTINCT FROM OLD.validation_passed) THEN
        RAISE EXCEPTION 'a validation is recorded once (P14-TSK-018)';
    END IF;
    IF OLD.validated_by IS NULL AND NEW.validated_by IS NOT NULL AND NOT (OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'VALIDATED')) THEN
        RAISE EXCEPTION 'a validation is recorded on a DRAFT (P14-TSK-018)';
    END IF;
    IF OLD.approved_by IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.approved_by_type IS DISTINCT FROM OLD.approved_by_type
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approval_reason IS DISTINCT FROM OLD.approval_reason
            OR NEW.approved_content_sha256 IS DISTINCT FROM OLD.approved_content_sha256 OR NEW.approved_period_revision IS DISTINCT FROM OLD.approved_period_revision) THEN
        RAISE EXCEPTION 'an attestation is recorded once (P14-TSK-018, INV-AUD-04)';
    END IF;
    IF OLD.approved_by IS NULL AND NEW.approved_by IS NOT NULL AND NOT (OLD.status = 'VALIDATED' AND NEW.status = 'APPROVED') THEN
        RAISE EXCEPTION 'an attestation is recorded on VALIDATED -> APPROVED (P14-TSK-018, INV-AUD-04)';
    END IF;
    IF OLD.submitted_by IS NOT NULL AND (NEW.submitted_by IS DISTINCT FROM OLD.submitted_by OR NEW.submitted_by_type IS DISTINCT FROM OLD.submitted_by_type
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at OR NEW.submission_reason IS DISTINCT FROM OLD.submission_reason
            OR NEW.answer_horizon_at IS DISTINCT FROM OLD.answer_horizon_at) THEN
        RAISE EXCEPTION 'a sending is recorded once (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF OLD.submitted_by IS NULL AND NEW.submitted_by IS NOT NULL AND NOT (OLD.status = 'APPROVED' AND NEW.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'a sending is recorded on APPROVED -> SUBMITTED (P14-TSK-018, INV-ACC-13)';
    END IF;
    IF OLD.closed_by IS NOT NULL THEN
        RAISE EXCEPTION 'a return a person closed is final (P14-TSK-018)';
    END IF;
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'a provider''s reference is kept once given (P14-TSK-018, INV-HIST-02)';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' THEN
        -- A FAILED VALIDATION, recorded once; nothing else moves. The way on is a withdrawal and a new draft.
        IF OLD.validation_outcome IS NOT NULL OR NEW.validation_outcome IS NULL OR NEW.validation_passed IS DISTINCT FROM FALSE THEN
            RAISE EXCEPTION 'a DRAFT records one failed validation and nothing else (P14-TSK-018)';
        END IF;
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.validated_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'VALIDATED' THEN
        IF OLD.validation_outcome IS NOT NULL OR NEW.validation_passed IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'a DRAFT is VALIDATED by one passing validation (P14-TSK-018)';
        END IF;
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.validated_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'VALIDATED' AND NEW.status = 'APPROVED' THEN
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.approved_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status = 'SUBMITTED' THEN
        IF NEW.answer_horizon_at IS NULL OR NEW.attempts <> 0 OR NEW.resends <> 0 OR NEW.last_answer IS NOT NULL THEN
            RAISE EXCEPTION 'SUBMITTED commits before any call: a horizon and no attempt (P14-TSK-018, INV-ACC-13)';
        END IF;
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.submitted_at := clock_timestamp();
        -- The drive's permit is due at once: the door's own drive or the inquiry leg takes it.
        NEW.next_inquiry_at := NEW.submitted_at;
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status = 'SUBMITTED' THEN
        -- THE DRIVE: an attempt counted, a resend counted, the permit, the last answer. Never a conclusion, never a slot.
        IF NEW.attempts < OLD.attempts OR NEW.resends < OLD.resends OR NEW.next_inquiry_at IS NULL OR NEW.rejection_code IS NOT NULL OR NEW.concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a SUBMITTED row''s drive moves its attempts, resends, permit and last answer, and nothing else (P14-TSK-018)';
        END IF;
        IF NEW.resends > OLD.resends AND NEW.last_answer IS DISTINCT FROM 'RESENDING' THEN
            RAISE EXCEPTION 'a resend is recorded before its call (P14-TSK-018, INV-ACC-13)';
        END IF;
        -- FIGURES THE BOOKS NO LONGER SAY ARE NOT SENT AGAIN. The period FOR SHARE, as the domain took it first: a resend and a reopen's approval serialise.
        IF NEW.resends > OLD.resends THEN
            SELECT p.revision INTO held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
            IF held_revision IS DISTINCT FROM NEW.period_revision THEN
                RAISE EXCEPTION 'a resend goes only while the month stands at the revision the return read (P14-TSK-018, INV-ACC-13)';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status IN ('ACKNOWLEDGED', 'REJECTED') THEN
        -- ONE WAY, AND ON THE PROVIDER'S WORD: the answer is on the record first.
        IF NOT EXISTS (SELECT 1 FROM accounting.regulatory_provider_answer a WHERE a.submission_id = NEW.id AND a.read_outcome = NEW.status) THEN
            RAISE EXCEPTION 'a submission is concluded only on a recorded answer of that outcome (P14-TSK-018, INV-HIST-02)';
        END IF;
        NEW.concluded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status = 'UNDELIVERED' THEN
        -- ON A DEFINITE "NOT RECEIVED", never over an unknown answer or a send in flight. It asks nothing of the month, which may well have moved: this is how a return that month overtook is ended, so a correction can go at the new revision.
        IF OLD.last_answer IS DISTINCT FROM 'NOT_RECEIVED' THEN
            RAISE EXCEPTION 'a return is concluded undelivered only on the provider''s definite not received (P14-TSK-018, INV-ACC-13)';
        END IF;
        NEW.closed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF (OLD.status IN ('DRAFT', 'VALIDATED', 'APPROVED') AND NEW.status = 'WITHDRAWN') OR (OLD.status IN ('VALIDATED', 'APPROVED') AND NEW.status = 'REFUSED') THEN
        -- A DOOR OUT asks nothing of the month: it is how a return overtaken by a reopen is ended.
        NEW.closed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory submission moves DRAFT -> VALIDATED -> APPROVED -> SUBMITTED -> ACKNOWLEDGED | REJECTED | UNDELIVERED, or out by a person''s door; % -> % is not an edge (P14-TSK-018, INV-ACC-13)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER regulatory_submission_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_submission
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_submission_permits_only_machine_edges();

CREATE TRIGGER regulatory_submission_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_submission
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

CREATE TRIGGER regulatory_submission_source_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_submission_source
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

CREATE TRIGGER regulatory_submission_line_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_submission_line
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

-- BORN WHOLE: at the commit of the draft's transaction the rows are what the content states, and there is at least one of each.
CREATE OR REPLACE FUNCTION accounting.regulatory_submission_is_born_whole()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER regulatory_submission_is_born_whole
    AFTER INSERT ON accounting.regulatory_submission
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_submission_is_born_whole();

-- ---------------------------------------------------------------------------------------------
-- The history: every edge, and a failed validation, append-only. FOUR EYES on it too.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.regulatory_submission_event (
    id             uuid        NOT NULL,
    submission_id  uuid        NOT NULL,
    act            text        NOT NULL,
    from_status    text,
    to_status      text        NOT NULL,
    actor          text        NOT NULL,
    actor_type     text        NOT NULL,
    prepared_by    text,
    reason         text,
    occurred_at    timestamptz NOT NULL,
    correlation_id text        NOT NULL,
    CONSTRAINT regulatory_submission_event_pk PRIMARY KEY (id),
    CONSTRAINT regulatory_submission_event_submission_fk FOREIGN KEY (submission_id) REFERENCES accounting.regulatory_submission (id),
    -- RegulatorySubmissionStore.Act, each on its edge.
    CONSTRAINT regulatory_submission_event_act_is_an_edge CHECK (
        (act = 'DRAFTED' AND from_status IS NULL AND to_status = 'DRAFT')
        OR (act = 'VALIDATION_FAILED' AND from_status = 'DRAFT' AND to_status = 'DRAFT')
        OR (act = 'VALIDATED' AND from_status = 'DRAFT' AND to_status = 'VALIDATED')
        OR (act = 'APPROVED' AND from_status = 'VALIDATED' AND to_status = 'APPROVED')
        OR (act = 'REFUSED' AND from_status IN ('VALIDATED', 'APPROVED') AND to_status = 'REFUSED')
        OR (act = 'WITHDRAWN' AND from_status IN ('DRAFT', 'VALIDATED', 'APPROVED') AND to_status = 'WITHDRAWN')
        OR (act = 'SUBMITTED' AND from_status = 'APPROVED' AND to_status = 'SUBMITTED')
        OR (act = 'ACKNOWLEDGED' AND from_status = 'SUBMITTED' AND to_status = 'ACKNOWLEDGED')
        OR (act = 'REJECTED' AND from_status = 'SUBMITTED' AND to_status = 'REJECTED')
        OR (act = 'UNDELIVERED' AND from_status = 'SUBMITTED' AND to_status = 'UNDELIVERED')),
    -- The provider's answers are the platform's to record; every other act a person's.
    CONSTRAINT regulatory_submission_event_actor_kind CHECK (
        (act IN ('ACKNOWLEDGED', 'REJECTED') AND actor_type = 'SYSTEM')
        OR (act NOT IN ('ACKNOWLEDGED', 'REJECTED') AND actor_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- FOUR EYES on the history (INV-AUD-04): an approval names its preparer, never the approver.
    CONSTRAINT regulatory_submission_event_four_eyes CHECK (
        (act = 'APPROVED') = (prepared_by IS NOT NULL) AND (prepared_by IS NULL OR prepared_by <> actor)),
    CONSTRAINT regulatory_submission_event_reasoned CHECK (
        (act NOT IN ('DRAFTED', 'APPROVED', 'REFUSED', 'WITHDRAWN', 'SUBMITTED', 'UNDELIVERED') OR reason IS NOT NULL)
        AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000))
);

CREATE INDEX regulatory_submission_event_of_submission ON accounting.regulatory_submission_event (submission_id, occurred_at);

COMMENT ON TABLE accounting.regulatory_submission_event IS
    'A regulatory submission''s machine as history (P14-TSK-018, INV-AUD-01, INV-AUD-04): each edge with its actor and reason, four eyes by CHECK. Append-only.';

CREATE OR REPLACE FUNCTION accounting.regulatory_submission_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a regulatory submission''s history is append-only (P14-TSK-018, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER regulatory_submission_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_submission_event
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_submission_event_is_append_only();

CREATE TRIGGER regulatory_submission_event_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_submission_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The provider's answers, verbatim (INV-HIST-02): bytes, never parsed here, never a decision of this table's. Append-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.regulatory_provider_answer (
    id                 uuid        NOT NULL,
    submission_id      uuid        NOT NULL,
    attempt            integer     NOT NULL,
    call_kind          text        NOT NULL,
    stated_outcome     text        NOT NULL,
    read_outcome       text        NOT NULL,
    provider_reference text,
    rejection_code     text,
    anomaly            text,
    flag               text,
    raw                bytea       NOT NULL,
    raw_sha256         text        NOT NULL,
    received_bytes     bigint      NOT NULL,
    truncated          boolean     NOT NULL,
    received_at        timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT regulatory_provider_answer_pk PRIMARY KEY (id),
    CONSTRAINT regulatory_provider_answer_submission_fk FOREIGN KEY (submission_id) REFERENCES accounting.regulatory_submission (id),
    CONSTRAINT regulatory_provider_answer_attempt_positive CHECK (attempt >= 1),
    CONSTRAINT regulatory_provider_answer_call_is_known CHECK (call_kind IN ('SUBMIT', 'INQUIRE', 'RESEND')),
    CONSTRAINT regulatory_provider_answer_outcomes_are_known CHECK (
        stated_outcome IN ('ACKNOWLEDGED', 'REJECTED', 'UNKNOWN', 'NOT_RECEIVED') AND read_outcome IN ('ACKNOWLEDGED', 'REJECTED', 'UNKNOWN', 'NOT_RECEIVED')),
    -- Why an answer decided nothing: the bytes kept, the outcome read UNKNOWN.
    CONSTRAINT regulatory_provider_answer_anomaly_is_known CHECK (anomaly IS NULL OR (anomaly IN ('OVERSIZED', 'MALFORMED', 'CONTRADICTORY') AND read_outcome = 'UNKNOWN')),
    -- A late or contrary answer: kept, flagged, never an edge.
    CONSTRAINT regulatory_provider_answer_flag_is_known CHECK (flag IS NULL OR flag IN ('LATE', 'CONTRARY')),
    -- What is read off an answer is held to shapes, so it cannot carry anything but a reference and a code.
    CONSTRAINT regulatory_provider_answer_read_shaped CHECK (
        (provider_reference IS NULL OR provider_reference ~ '^[A-Za-z0-9._:-]{1,128}$')
        AND (rejection_code IS NULL OR rejection_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (read_outcome <> 'ACKNOWLEDGED' OR provider_reference IS NOT NULL)
        AND (read_outcome = 'REJECTED') = (rejection_code IS NOT NULL)),
    -- THE BOUND AND ITS RULE (RegulatoryAnswerReading.MAX_RAW_BYTES): over 64 KiB the first 64 KiB, the whole length and the whole hash are kept, and the answer decides nothing.
    CONSTRAINT regulatory_provider_answer_raw_bounded CHECK (octet_length(raw) <= 65536 AND received_bytes >= octet_length(raw)),
    CONSTRAINT regulatory_provider_answer_truncation_coherent CHECK (
        truncated = (received_bytes > octet_length(raw)) AND (NOT truncated OR anomaly = 'OVERSIZED')),
    CONSTRAINT regulatory_provider_answer_hash_is_the_answers CHECK (
        raw_sha256 ~ '^[0-9a-f]{64}$' AND (truncated OR raw_sha256 = encode(sha256(raw), 'hex')))
);

CREATE INDEX regulatory_provider_answer_of_submission ON accounting.regulatory_provider_answer (submission_id, received_at, id);
CREATE INDEX regulatory_provider_answer_contrary ON accounting.regulatory_provider_answer (submission_id) WHERE flag = 'CONTRARY';

COMMENT ON TABLE accounting.regulatory_provider_answer IS
    'Every answer a regulatory adapter gave (P14-TSK-018, INV-HIST-02): the bytes verbatim, untrusted, CONFIDENTIAL, never logged, served only as base64 on the audited reads; what was read off them, and why an answer decided nothing. Append-only.';

CREATE OR REPLACE FUNCTION accounting.regulatory_provider_answer_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a provider''s answer is kept verbatim and append-only (P14-TSK-018, INV-HIST-02)';
    END IF;
    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER regulatory_provider_answer_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.regulatory_provider_answer
    FOR EACH ROW
    EXECUTE FUNCTION accounting.regulatory_provider_answer_is_append_only();

CREATE TRIGGER regulatory_provider_answer_is_never_truncated
    BEFORE TRUNCATE ON accounting.regulatory_provider_answer
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.regulatory_record_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the acts' columns only; the rest INSERT-only; no DELETE for any role. Neither content nor configuration is ever in an UPDATE grant.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.regulatory_obligation TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, retired_at) ON accounting.regulatory_obligation TO finapp_app;
GRANT SELECT, INSERT ON accounting.regulatory_submission TO finapp_app;
GRANT UPDATE (status, validation_outcome, validation_passed, validated_by, validated_by_type, validated_at,
    approved_by, approved_by_type, approved_at, approval_reason, approved_content_sha256, approved_period_revision,
    submitted_by, submitted_by_type, submitted_at, submission_reason,
    closed_by, closed_by_type, closed_at, closing_reason,
    attempts, resends, next_inquiry_at, answer_horizon_at, last_answer, provider_reference, rejection_code, concluded_at)
    ON accounting.regulatory_submission TO finapp_app;
GRANT SELECT, INSERT ON accounting.regulatory_submission_source TO finapp_app;
GRANT SELECT, INSERT ON accounting.regulatory_submission_line TO finapp_app;
GRANT SELECT, INSERT ON accounting.regulatory_submission_event TO finapp_app;
GRANT SELECT, INSERT ON accounting.regulatory_provider_answer TO finapp_app;
GRANT EXECUTE ON FUNCTION accounting.regulatory_submission_judge_stands(uuid, uuid, integer, uuid) TO finapp_app;
GRANT EXECUTE ON FUNCTION accounting.regulatory_submission_lines_agree(uuid, text) TO finapp_app;
