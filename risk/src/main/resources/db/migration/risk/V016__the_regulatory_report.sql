-- The regulatory report - the filing artefact, distinct from the case (P13-TSK-021; PHASE_13_PLAN.md sections 5, 7.1
-- T-rep, 7.2 (R4), 8, 9, 10, 11, 12.9, 14; RISK_LIFECYCLES.md section 4.8; ADR-0111 decisions 5-7; INV-RSK-06,
-- INV-RSK-07, INV-RSK-08, INV-AUD-02, INV-AUD-04). The next free number at its build: the entry's V011 went to
-- P13-TSK-014 (and V012-V015 to -016, -017, -018, -020).
--
-- WHAT A REPORT IS
--   A communication to an authority about one party, drafted from an AML case disposed CONFIRMED_SUSPICIOUS by an
--   AML_REPORT_APPROVE holder, approved by a SECOND holder, filed by an AML_REPORT_FILE holder through the filing port
--   (simulated in Phase 13) and answered by the authority: DRAFT -> APPROVED -> FILED -> ACKNOWLEDGED | REJECTED. Its
--   content - the jurisdiction and format version its adapter declares, the reporting entity's reference, the subject (a
--   party) and its accounts, the transactions referenced (monitored transaction ids, the case's evidence), the grounds
--   (the case's alerts' rule codes) and the narrative (RESTRICTED-PII, screened), the attachments' references - is fixed
--   when it is drafted.
--
-- ONE LIVE OR ACCEPTED REPORT PER CASE (regulatory_report_one_live_per_case, INV-RSK-06)
--   UNIQUE (case_id) WHERE status <> 'REJECTED' - the entry's predicate (DRAFT, APPROVED) widened by DESIGN-021 D3: the
--   entry's would let a second draft be born, approved and filed while the first report stood FILED or ACKNOWLEDGED - a
--   second filing of one case. A rejected filing frees the case for a new draft.
--
-- THE CASE'S ACT AND THE REPORT, BOUND BOTH WAYS (DESIGN-021 D2)
--   A report's id IS its REPORT_DRAFTED investigator action's (regulatory_report_drafted_on_its_case, the P13-TSK-017
--   "the key is the id" precedent), written in the same transaction under the case row's lock (R4). A disposed case never
--   steps (V012's machine), so the act's ordinal is past the case's last revision and it has no case history row;
--   risk_case_action_report_drafted_coherent admits the kind only on an AML case of a party disposed CONFIRMED_SUSPICIOUS,
--   and risk_case_action_report_drafted_has_its_report (deferred) holds that every such act has its report by commit.
--
-- IMMUTABLE, BY BOTH SHAPES (DESIGN-021 D4)
--   The content and the drafter are frozen from birth for EVERY writer, the migrator included (the trigger), and no content
--   column is in the application role's UPDATE grant (the grant's shape) - so "immutable once FILED" holds a fortiori.
--   What moves is the machine: the status, the approver's and the filer's slots once, the drive's permit and attempts,
--   the provider's reference once, the rejection's code.
--
-- FILED BEFORE THE CALL; ASKED, NEVER RE-FILED (ADR-0111 decision 6; the payout precedent)
--   The filing commits FILED with the drive's permit due at once and no attempt counted - nothing has been sent. The
--   drive claims the row FOR UPDATE SKIP LOCKED on a permit that has come (one attempt counted, the permit a lease ahead,
--   committed), calls the provider on no connection - the first attempt files under the filing reference (the report's
--   id), every later one inquires by it - and records the answer conditionally on FILED and the claimed attempt:
--   ACKNOWLEDGED with the authority's reference, REJECTED with its code, or FILED kept with the permit an inquiry interval
--   ahead. A crash anywhere leaves the permit, and the next claim inquires.
--
-- Persons are authenticated humans - CUSTOMER-typed as the platform's session establishes every operator, or EMPLOYEE -
-- never SYSTEM, SERVICE or MERCHANT (P13-TSK-016's routed defect, the meaning V012-V014 adopted); the authority's answer is
-- recorded by the platform (SYSTEM) in the history. Four eyes: regulatory_report_four_eyes, for every writer, not judged by
-- the trigger, so the CHECK is provable alone (INV-RSK-07, INV-AUD-04). The stamps are the triggers' clock_timestamp().
-- No money column. No DELETE for any role. The migration names no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The report.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.regulatory_report (
    id                       uuid        NOT NULL,
    case_id                  uuid        NOT NULL,

    -- The content: fixed at the draft, for every writer.
    jurisdiction             text        NOT NULL,
    format_version           smallint    NOT NULL,
    reporting_entity_ref     text        NOT NULL,
    subject_kind             text        NOT NULL,
    subject_ref              uuid        NOT NULL,
    account_refs             uuid[]      NOT NULL,
    transaction_ids          uuid[]      NOT NULL,
    ground_codes             text[]      NOT NULL,
    narrative                text        NOT NULL,
    attachment_refs          uuid[]      NOT NULL,

    status                   text        NOT NULL,

    -- The three persons: the drafter, the approver, the filer.
    drafted_by               text        NOT NULL,
    drafted_by_type          text        NOT NULL,
    drafted_at               timestamptz NOT NULL,
    approved_by              text,
    approved_by_type         text,
    approved_at              timestamptz,
    filed_by                 text,
    filed_by_type            text,
    filed_at                 timestamptz,

    -- The authority's answer and the drive: its reference, a rejection's code, the conclusion, the permit, the attempts.
    provider_reference       text,
    rejection_code           text,
    concluded_at             timestamptz,
    next_inquiry_at          timestamptz,
    attempts                 integer     NOT NULL DEFAULT 0,

    correlation_id           text        NOT NULL,

    CONSTRAINT regulatory_report_pk PRIMARY KEY (id),
    CONSTRAINT regulatory_report_case_fk FOREIGN KEY (case_id) REFERENCES risk.risk_case (id),
    -- A report IS its drafter's REPORT_DRAFTED act on its case (the id, and so the filing reference).
    CONSTRAINT regulatory_report_drafted_on_its_case
        FOREIGN KEY (id, case_id) REFERENCES risk.risk_case_action (id, case_id),

    -- The closed vocabulary RegulatoryReportStatus declares (RegulatoryReportMigrationTest holds the two to one set).
    CONSTRAINT regulatory_report_status_is_known CHECK (status IN ('DRAFT', 'APPROVED', 'FILED', 'ACKNOWLEDGED',
        'REJECTED')),
    CONSTRAINT regulatory_report_subject_is_a_party CHECK (subject_kind = 'PARTY'),

    -- The shapes ReportContent and the port's records hold.
    CONSTRAINT regulatory_report_jurisdiction_shaped CHECK (jurisdiction ~ '^[A-Z]{2,3}(-[A-Z0-9]{2,8})?$'),
    CONSTRAINT regulatory_report_format_version_bounded CHECK (format_version BETWEEN 1 AND 999),
    CONSTRAINT regulatory_report_entity_ref_shaped CHECK (reporting_entity_ref ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT regulatory_report_references_bounded CHECK (
        coalesce(array_ndims(account_refs), 1) = 1 AND cardinality(account_refs) <= 50
        AND array_position(account_refs, NULL) IS NULL
        AND coalesce(array_ndims(transaction_ids), 1) = 1 AND cardinality(transaction_ids) <= 100
        AND array_position(transaction_ids, NULL) IS NULL
        AND coalesce(array_ndims(attachment_refs), 1) = 1 AND cardinality(attachment_refs) <= 20
        AND array_position(attachment_refs, NULL) IS NULL),
    CONSTRAINT regulatory_report_grounds_are_codes CHECK (
        coalesce(array_ndims(ground_codes), 1) = 1 AND cardinality(ground_codes) <= 50
        AND array_position(ground_codes, NULL) IS NULL
        AND array_to_string(ground_codes, ',') ~ '^([A-Z][A-Z0-9_]{0,63}(,[A-Z][A-Z0-9_]{0,63})*)?$'),

    -- The narrative: a person's text, RESTRICTED-PII, bounded and screened (risk V002's instrument-shape twin).
    CONSTRAINT regulatory_report_narrative_screened CHECK (
        char_length(narrative) BETWEEN 1 AND 8000 AND btrim(narrative) <> ''
        AND NOT risk.holds_instrument_shape(narrative)),

    -- Persons: authenticated humans, never the system.
    CONSTRAINT regulatory_report_by_persons CHECK (
        drafted_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (filed_by_type IS NULL OR filed_by_type IN ('CUSTOMER', 'EMPLOYEE'))),

    -- FOUR EYES, for every writer (INV-RSK-07, INV-AUD-04): the approver is not the drafter. Deliberately not judged by
    -- the trigger, so the CHECK is provable alone.
    CONSTRAINT regulatory_report_four_eyes CHECK (approved_by IS NULL OR approved_by <> drafted_by),

    -- The persons' slots whole, and present exactly past their state.
    CONSTRAINT regulatory_report_approved_coherent CHECK (
        num_nonnulls(approved_by, approved_by_type, approved_at) IN (0, 3)
        AND (status <> 'DRAFT') = (approved_by IS NOT NULL)),
    CONSTRAINT regulatory_report_filed_coherent CHECK (
        num_nonnulls(filed_by, filed_by_type, filed_at) IN (0, 3)
        AND (status IN ('FILED', 'ACKNOWLEDGED', 'REJECTED')) = (filed_by IS NOT NULL)),

    -- The drive's permit exactly while FILED; concluded exactly when answered; an attempt is a claim.
    CONSTRAINT regulatory_report_permit_coherent CHECK ((next_inquiry_at IS NOT NULL) = (status = 'FILED')),
    CONSTRAINT regulatory_report_concluded_coherent CHECK (
        (concluded_at IS NOT NULL) = (status IN ('ACKNOWLEDGED', 'REJECTED'))),
    CONSTRAINT regulatory_report_attempts_coherent CHECK (
        attempts BETWEEN 0 AND 1000000
        AND (status IN ('FILED', 'ACKNOWLEDGED', 'REJECTED') OR attempts = 0)
        AND (status NOT IN ('ACKNOWLEDGED', 'REJECTED') OR attempts >= 1)),

    -- The authority's answer: a rejection carries its closed code; an acknowledgement the authority's reference; nothing
    -- before FILED.
    CONSTRAINT regulatory_report_answer_coherent CHECK (
        (rejection_code IS NOT NULL) = (status = 'REJECTED')
        AND (rejection_code IS NULL OR rejection_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (provider_reference IS NULL OR provider_reference ~ '^[A-Za-z0-9._:-]{1,128}$')
        AND (status <> 'ACKNOWLEDGED' OR provider_reference IS NOT NULL)
        AND (status IN ('FILED', 'ACKNOWLEDGED', 'REJECTED') OR provider_reference IS NULL)),

    CONSTRAINT regulatory_report_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- ONE LIVE OR ACCEPTED REPORT PER CASE (INV-RSK-06; DESIGN-021 D3). The store's birth is INSERT ... ON CONFLICT (case_id)
-- WHERE status <> 'REJECTED' DO NOTHING; the draft reads the live report under the case row's lock first.
CREATE UNIQUE INDEX regulatory_report_one_live_per_case ON risk.regulatory_report (case_id) WHERE status <> 'REJECTED';

-- The drive's claim: the due permits, oldest first (RiskActionSchedule's report leg, and the filing door's own claim).
CREATE INDEX regulatory_report_due ON risk.regulatory_report (next_inquiry_at, id) WHERE next_inquiry_at IS NOT NULL;

COMMENT ON TABLE risk.regulatory_report IS
    'A regulatory report: the filing artefact to an authority, distinct from the case it was drafted from (ADR-0111). DRAFT (from an AML case of a party disposed CONFIRMED_SUSPICIOUS, under the drafter''s REPORT_DRAFTED act''s id) -> APPROVED (a second person, regulatory_report_four_eyes) -> FILED (committed before the provider is called) -> ACKNOWLEDGED | REJECTED (the platform''s drive). One live or accepted per case. The content frozen from birth for every writer and never granted for UPDATE. The narrative RESTRICTED-PII. P13-TSK-021.';
COMMENT ON COLUMN risk.regulatory_report.narrative IS
    'The approved narrative of the grounds - a person''s text, RESTRICTED-PII, 1 to 8000 characters, screened for instrument shapes; served only by the AML desk''s doors (AML_REPORT_*), never logged, audited, announced or read by the case file.';
COMMENT ON COLUMN risk.regulatory_report.next_inquiry_at IS
    'The drive''s permit while FILED: clock_timestamp() at the filing (due at once), + the lease at a claim, + the inquiry interval after an unknown answer. A claim takes only a permit that has come, so ten instances drive one report once per lease.';
COMMENT ON COLUMN risk.regulatory_report.provider_reference IS
    'The authority''s own reference for the filing, recorded once when it is first given; the filing reference the platform sends and inquires by is the report''s id.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The history - one row per status reached, append-only.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.regulatory_report_event (
    report_id         uuid        NOT NULL,
    status            text        NOT NULL,
    actor_id          text        NOT NULL,
    actor_type        text        NOT NULL,
    recorded_at       timestamptz NOT NULL,
    correlation_id    text        NOT NULL,

    -- Each status is reached once: a second FILED row is a key violation (filed once, INV-RSK-06).
    CONSTRAINT regulatory_report_event_pk PRIMARY KEY (report_id, status),
    CONSTRAINT regulatory_report_event_report_fk FOREIGN KEY (report_id) REFERENCES risk.regulatory_report (id),
    CONSTRAINT regulatory_report_event_status_is_known CHECK (status IN ('DRAFT', 'APPROVED', 'FILED', 'ACKNOWLEDGED',
        'REJECTED')),
    -- A person's act names a person; the authority's answer is recorded by the platform.
    CONSTRAINT regulatory_report_event_actor_coherent CHECK (
        (status IN ('DRAFT', 'APPROVED', 'FILED') AND actor_type IN ('CUSTOMER', 'EMPLOYEE'))
        OR (status IN ('ACKNOWLEDGED', 'REJECTED') AND actor_type = 'SYSTEM')),
    CONSTRAINT regulatory_report_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE risk.regulatory_report_event IS
    'A regulatory report''s history: one row per status reached (PRIMARY KEY (report_id, status); regulatory_report_has_its_history at commit), naming the person who moved it there - or SYSTEM for the authority''s answer. INSERT-only for every writer. P13-TSK-021.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The machine.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.regulatory_report_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: a report is a regulatory record; it is never removed.
        RAISE EXCEPTION 'a regulatory report is never deleted or truncated (P13-TSK-021, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.attempts <> 0
                OR num_nonnulls(NEW.approved_by, NEW.approved_by_type, NEW.approved_at, NEW.filed_by, NEW.filed_by_type,
                                NEW.filed_at, NEW.provider_reference, NEW.rejection_code, NEW.concluded_at,
                                NEW.next_inquiry_at) <> 0 THEN
            RAISE EXCEPTION 'a regulatory report is born DRAFT, unapproved, unfiled and unanswered (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Drafted from an AML case of its very subject, disposed CONFIRMED_SUSPICIOUS (DESIGN-021 D12).
        IF NOT EXISTS (
                SELECT 1 FROM risk.risk_case c
                 WHERE c.id = NEW.case_id AND c.kind = 'AML' AND c.status = 'DISPOSED'
                   AND c.disposition = 'CONFIRMED_SUSPICIOUS' AND c.subject_kind = 'PARTY'
                   AND c.subject_ref = NEW.subject_ref::text) THEN
            RAISE EXCEPTION 'a regulatory report is drafted from an AML case of its subject disposed CONFIRMED_SUSPICIOUS'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Its drafter's REPORT_DRAFTED act on that case, under its own id (DESIGN-021 D2).
        IF NOT EXISTS (
                SELECT 1 FROM risk.risk_case_action a
                 WHERE a.id = NEW.id AND a.case_id = NEW.case_id AND a.kind = 'REPORT_DRAFTED'
                   AND a.actor_id = NEW.drafted_by AND a.actor_type = NEW.drafted_by_type) THEN
            RAISE EXCEPTION 'a regulatory report is its drafter''s REPORT_DRAFTED act on its case (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Every transaction referenced is a monitored transaction touching one of its accounts (DESIGN-021 D5).
        IF EXISTS (
                SELECT 1 FROM unnest(NEW.transaction_ids) AS referenced(id)
                 WHERE NOT EXISTS (
                        SELECT 1 FROM risk.monitored_transaction m
                         WHERE m.id = referenced.id
                           AND ((m.subject_kind = 'ACCOUNT' AND m.subject_ref = ANY (NEW.account_refs))
                                OR (m.counterparty_kind = 'CUSTOMER' AND m.counterparty_ref = ANY (NEW.account_refs))))) THEN
            RAISE EXCEPTION 'a report references monitored transactions of its subject''s accounts (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Every ground is the rule code of an alert attached to the case: the case's own reason codes.
        IF EXISTS (
                SELECT 1 FROM unnest(NEW.ground_codes) AS ground(code)
                 WHERE NOT EXISTS (
                        SELECT 1 FROM risk.risk_alert al
                         WHERE al.attached_case_id = NEW.case_id AND al.rule_code = ground.code)) THEN
            RAISE EXCEPTION 'a report''s grounds are its case''s alerts'' rule codes (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.drafted_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The content and the drafter are frozen from birth, for every writer (DESIGN-021 D4).
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.case_id IS DISTINCT FROM OLD.case_id
            OR NEW.jurisdiction IS DISTINCT FROM OLD.jurisdiction
            OR NEW.format_version IS DISTINCT FROM OLD.format_version
            OR NEW.reporting_entity_ref IS DISTINCT FROM OLD.reporting_entity_ref
            OR NEW.subject_kind IS DISTINCT FROM OLD.subject_kind OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref
            OR NEW.account_refs IS DISTINCT FROM OLD.account_refs
            OR NEW.transaction_ids IS DISTINCT FROM OLD.transaction_ids
            OR NEW.ground_codes IS DISTINCT FROM OLD.ground_codes OR NEW.narrative IS DISTINCT FROM OLD.narrative
            OR NEW.attachment_refs IS DISTINCT FROM OLD.attachment_refs
            OR NEW.drafted_by IS DISTINCT FROM OLD.drafted_by OR NEW.drafted_by_type IS DISTINCT FROM OLD.drafted_by_type
            OR NEW.drafted_at IS DISTINCT FROM OLD.drafted_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a regulatory report''s content never changes (P13-TSK-021)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status IN ('ACKNOWLEDGED', 'REJECTED') THEN
        -- Answered: nothing moves any more - a rejected filing is followed by a new draft, never edited back.
        RAISE EXCEPTION 'an answered regulatory report never moves (P13-TSK-021)' USING ERRCODE = 'check_violation';
    END IF;

    -- A person's slot, once decided, never changes.
    IF OLD.approved_by IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_by_type IS DISTINCT FROM OLD.approved_by_type
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at) THEN
        RAISE EXCEPTION 'a report''s approval never changes (P13-TSK-021)' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.filed_by IS NOT NULL AND (NEW.filed_by IS DISTINCT FROM OLD.filed_by
            OR NEW.filed_by_type IS DISTINCT FROM OLD.filed_by_type OR NEW.filed_at IS DISTINCT FROM OLD.filed_at) THEN
        RAISE EXCEPTION 'a report''s filing never changes (P13-TSK-021)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'DRAFT' THEN
        -- The approval: a person (four eyes is regulatory_report_four_eyes' - provable alone), nothing else.
        IF NEW.status <> 'APPROVED' OR NEW.approved_by IS NULL OR NEW.approved_by_type IS NULL OR NEW.attempts <> 0
                OR num_nonnulls(NEW.filed_by, NEW.filed_by_type, NEW.filed_at, NEW.provider_reference,
                                NEW.rejection_code, NEW.next_inquiry_at) <> 0 THEN
            RAISE EXCEPTION 'a draft is approved by a person, and nothing else moves (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.approved_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF OLD.status = 'APPROVED' THEN
        -- The filing: a person, committed before any call - the drive's permit due at once, nothing sent, nothing answered.
        IF NEW.status <> 'FILED' OR NEW.filed_by IS NULL OR NEW.filed_by_type IS NULL OR NEW.attempts <> 0
                OR num_nonnulls(NEW.provider_reference, NEW.rejection_code) <> 0 THEN
            RAISE EXCEPTION 'an approved report is filed by a person before anything is sent (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.filed_at := clock_timestamp();
        NEW.next_inquiry_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- FILED: the drive's claim, an unknown standing re-permitted, or the authority's answer. The provider's reference is
    -- recorded once.
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'the authority''s reference is recorded once and never changes (P13-TSK-021)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'FILED' THEN
        IF NEW.rejection_code IS NOT NULL OR NEW.next_inquiry_at IS NULL OR NEW.next_inquiry_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'a filed report awaiting its answer keeps a permit ahead (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts = OLD.attempts + 1 THEN
            -- The claim: only a permit that has come; nothing of the answer moves.
            IF OLD.next_inquiry_at > clock_timestamp()
                    OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
                RAISE EXCEPTION 'a report''s permit is claimed only when it has come, and the claim records no answer'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.attempts <> OLD.attempts THEN
            RAISE EXCEPTION 'a claim counts exactly one attempt (P13-TSK-021)' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status IN ('ACKNOWLEDGED', 'REJECTED') THEN
        -- The authority's answer, recorded once after a claim: no attempt counted, the permit cleared, the instant the
        -- database's.
        IF NEW.attempts <> OLD.attempts OR OLD.attempts < 1 THEN
            RAISE EXCEPTION 'the authority''s answer follows a claim and counts no attempt (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.next_inquiry_at := NULL;
        NEW.concluded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a regulatory report moves only along its machine (P13-TSK-021)' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER regulatory_report_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.regulatory_report
    FOR EACH ROW
    EXECUTE FUNCTION risk.regulatory_report_permits_only_machine_edges();

CREATE TRIGGER regulatory_report_is_never_truncated
    BEFORE TRUNCATE ON risk.regulatory_report
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.regulatory_report_permits_only_machine_edges();

-- Every status a report reaches has its history row (checked at commit: an act writes the row after its statement).
CREATE FUNCTION risk.regulatory_report_has_its_history() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM risk.regulatory_report_event e WHERE e.report_id = NEW.id AND e.status = NEW.status) THEN
        RAISE EXCEPTION 'every status a regulatory report reaches has its history row (P13-TSK-021)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER regulatory_report_has_its_history
    AFTER INSERT OR UPDATE ON risk.regulatory_report
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION risk.regulatory_report_has_its_history();

-- The history: born once and stamped, naming the state the report reached and the person who moved it there (the
-- platform for the authority's answer); never edited, deleted or truncated - for every writer.
CREATE FUNCTION risk.regulatory_report_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    reached record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT r.status, r.drafted_by, r.drafted_by_type, r.approved_by, r.approved_by_type, r.filed_by, r.filed_by_type
          INTO reached
          FROM risk.regulatory_report r
         WHERE r.id = NEW.report_id;
        IF NOT FOUND OR reached.status <> NEW.status OR NOT (CASE NEW.status
                WHEN 'DRAFT' THEN NEW.actor_id = reached.drafted_by AND NEW.actor_type = reached.drafted_by_type
                WHEN 'APPROVED' THEN NEW.actor_id = reached.approved_by AND NEW.actor_type = reached.approved_by_type
                WHEN 'FILED' THEN NEW.actor_id = reached.filed_by AND NEW.actor_type = reached.filed_by_type
                ELSE NEW.actor_type = 'SYSTEM' END) THEN
            RAISE EXCEPTION 'a report''s history row names the state it reached and who moved it there (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory report''s history is never edited, deleted or truncated (P13-TSK-021)';
END;
$$;

CREATE TRIGGER regulatory_report_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON risk.regulatory_report_event
    FOR EACH ROW
    EXECUTE FUNCTION risk.regulatory_report_event_is_append_only();

CREATE TRIGGER regulatory_report_event_is_never_truncated
    BEFORE TRUNCATE ON risk.regulatory_report_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.regulatory_report_event_is_append_only();

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. The case's REPORT_DRAFTED act (V012 admitted the kind and wrote nothing of it): only on an AML case of a party
--    disposed CONFIRMED_SUSPICIOUS, past its last revision - a disposed case never steps - and never without its report.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE FUNCTION risk.risk_case_action_report_drafted_coherent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM risk.risk_case c
             WHERE c.id = NEW.case_id AND c.kind = 'AML' AND c.status = 'DISPOSED'
               AND c.disposition = 'CONFIRMED_SUSPICIOUS' AND c.subject_kind = 'PARTY' AND NEW.ordinal > c.revision) THEN
        RAISE EXCEPTION 'a REPORT_DRAFTED act is recorded on a confirmed-suspicious AML case, past its revision'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER risk_case_action_report_drafted_coherent
    BEFORE INSERT ON risk.risk_case_action
    FOR EACH ROW
    WHEN (NEW.kind = 'REPORT_DRAFTED')
    EXECUTE FUNCTION risk.risk_case_action_report_drafted_coherent();

CREATE FUNCTION risk.risk_case_action_report_drafted_has_its_report() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM risk.regulatory_report r WHERE r.id = NEW.id AND r.case_id = NEW.case_id) THEN
        RAISE EXCEPTION 'a REPORT_DRAFTED act has its regulatory report by commit (P13-TSK-021)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER risk_case_action_report_drafted_has_its_report
    AFTER INSERT ON risk.risk_case_action
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.kind = 'REPORT_DRAFTED')
    EXECUTE FUNCTION risk.risk_case_action_report_drafted_has_its_report();

-- ---------------------------------------------------------------------------------------------------------------------
-- 5. The grants: SELECT and INSERT; a column-scoped UPDATE of the machine's columns alone - no content column, no stamp
--    (the trigger's). No DELETE for any role.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.regulatory_report TO finapp_app;
GRANT UPDATE (status, approved_by, approved_by_type, filed_by, filed_by_type, provider_reference, rejection_code,
    next_inquiry_at, attempts) ON risk.regulatory_report TO finapp_app;
GRANT SELECT, INSERT ON risk.regulatory_report_event TO finapp_app;
