-- The owner cannot empty the monitored transactions in one statement; risk's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   Each of these tables refuses DELETE by a row trigger, and a row trigger does not fire on TRUNCATE. The one role
--   that holds TRUNCATE on them, their owner finapp_migrator, could empty the table in a single statement. A
--   statement-level BEFORE TRUNCATE trigger refuses it, to every role, the owner included, as reconciliation V024 and
--   settlement V019 do.
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   1 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: monitored_transaction.
--   19 function(s), 53 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V002, V003, V004,
--   V005, V006, V010, V012, V013, V016, V018, V020, the last definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and
--   LOCALTIMESTAMP are grammar, not functions, and are never resolved through a path.
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
--
-- LOCKS
--   CREATE TRIGGER takes SHARE ROW EXCLUSIVE on each table, held to this transaction's end, which blocks writers for
--   the length of a catalogue change. The migrator's session bounds the wait with lock_timeout and fails rather than
--   queues (DATA_MIGRATIONS.md section 2.1).

CREATE OR REPLACE FUNCTION risk.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'risk.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION risk.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only risk table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER monitored_transaction_is_never_truncated
    BEFORE TRUNCATE ON risk.monitored_transaction
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.history_is_never_truncated();

-- risk.customer_risk_profile_moves_with_its_versions: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.customer_risk_profile_moves_with_its_versions() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- A head is born unassessed; its first rating arrives as version 1, beside its row.
        IF NEW.current_version <> 0 OR NEW.rating IS NOT NULL THEN
            RAISE EXCEPTION 'a customer risk profile is born unassessed: version 0, no rating (INV-RSK-10)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.created_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.party_id IS DISTINCT FROM OLD.party_id THEN
        RAISE EXCEPTION 'a customer risk profile is never moved to another party' USING ERRCODE = 'check_violation';
    END IF;
    NEW.created_at := OLD.created_at;
    IF NEW.current_version IS DISTINCT FROM OLD.current_version OR NEW.rating IS DISTINCT FROM OLD.rating THEN
        -- The head says what its newest version says: one step, beside a version row of the same rating.
        IF NEW.current_version <> OLD.current_version + 1 OR NOT EXISTS (
                SELECT 1 FROM risk.customer_risk_profile_version v
                 WHERE v.party_id = NEW.party_id AND v.version = NEW.current_version AND v.rating = NEW.rating) THEN
            RAISE EXCEPTION 'a customer risk profile moves one version at a time, beside that version (INV-RSK-10)'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- risk.customer_risk_profile_version_is_born_in_sequence: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.customer_risk_profile_version_is_born_in_sequence() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    newest integer;
    newest_rating text;
BEGIN
    SELECT v.version, v.rating INTO newest, newest_rating
      FROM risk.customer_risk_profile_version v
     WHERE v.party_id = NEW.party_id
     ORDER BY v.version DESC
     LIMIT 1;
    IF NEW.version <> COALESCE(newest, 0) + 1 THEN
        RAISE EXCEPTION 'a customer risk profile version is numbered without a gap (INV-RSK-10)'
            USING ERRCODE = 'check_violation';
    END IF;
    -- Only a disposition lifts PROHIBITED: the sweep never re-rates a prohibited party (INV-RSK-07).
    IF newest_rating = 'PROHIBITED' AND NEW.cause <> 'DISPOSITION' THEN
        RAISE EXCEPTION 'a recompute never follows a PROHIBITED version: only a disposition lifts it (INV-RSK-07)'
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.computed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- risk.decision_override_permits_only_machine_edges: V013's definition, 2 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.clock_timestamp();
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
        NEW.decided_at := pg_catalog.clock_timestamp();
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

-- risk.monitored_transaction_is_stamped_by_the_database: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.monitored_transaction_is_stamped_by_the_database()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.recorded_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- risk.monitoring_watermark_derives_its_sequence: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.monitoring_watermark_derives_its_sequence()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.last_sequence := risk.uuid7_sequence(NEW.last_entry_id);
    IF TG_OP = 'INSERT' OR NEW.last_entry_id IS DISTINCT FROM OLD.last_entry_id THEN
        NEW.advanced_at := pg_catalog.clock_timestamp();
    ELSE
        NEW.advanced_at := OLD.advanced_at;
    END IF;
    RETURN NEW;
END;
$$;

-- risk.regulatory_report_event_is_append_only: V016's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.regulatory_report_event_is_append_only() RETURNS trigger
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
        NEW.recorded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory report''s history is never edited, deleted or truncated (P13-TSK-021)';
END;
$$;

-- risk.regulatory_report_permits_only_machine_edges: V016's definition, 7 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.regulatory_report_permits_only_machine_edges() RETURNS trigger
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
        NEW.drafted_at := pg_catalog.clock_timestamp();
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
        NEW.approved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    IF OLD.status = 'APPROVED' THEN
        -- The filing: a person, committed before any call - the drive's permit due at once, nothing sent, nothing answered.
        IF NEW.status <> 'FILED' OR NEW.filed_by IS NULL OR NEW.filed_by_type IS NULL OR NEW.attempts <> 0
                OR num_nonnulls(NEW.provider_reference, NEW.rejection_code) <> 0 THEN
            RAISE EXCEPTION 'an approved report is filed by a person before anything is sent (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.filed_at := pg_catalog.clock_timestamp();
        NEW.next_inquiry_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    -- FILED: the drive's claim, an unknown standing re-permitted, or the authority's answer. The provider's reference is
    -- recorded once.
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'the authority''s reference is recorded once and never changes (P13-TSK-021)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'FILED' THEN
        IF NEW.rejection_code IS NOT NULL OR NEW.next_inquiry_at IS NULL OR NEW.next_inquiry_at <= pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'a filed report awaiting its answer keeps a permit ahead (P13-TSK-021)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts = OLD.attempts + 1 THEN
            -- The claim: only a permit that has come; nothing of the answer moves.
            IF OLD.next_inquiry_at > pg_catalog.clock_timestamp()
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
        NEW.concluded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a regulatory report moves only along its machine (P13-TSK-021)' USING ERRCODE = 'check_violation';
END;
$$;

-- risk.rescreening_permit_permits_only_machine_edges: V018's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.rescreening_permit_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'a rescreening permit is never deleted or truncated (P13-TSK-022, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.attempts <> 0
                OR num_nonnulls(NEW.outcome, NEW.cause, NEW.signal_id, NEW.concluded_at) <> 0 THEN
            RAISE EXCEPTION 'a rescreening permit is born PENDING, unclaimed and unconcluded (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Enqueued only for a version in force.
        IF NOT EXISTS (SELECT 1 FROM risk.screening_list_version v
                        WHERE v.id = NEW.list_version_id AND v.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'a rescreening permit is enqueued for a screening list version in force (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.enqueued_at := pg_catalog.clock_timestamp();
        NEW.next_attempt_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. Who and under what are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.list_version_id IS DISTINCT FROM OLD.list_version_id
            OR NEW.enqueued_at IS DISTINCT FROM OLD.enqueued_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a rescreening permit''s party and version never change (P13-TSK-022)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a concluded rescreening permit never moves (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'PENDING' THEN
        IF NEW.outcome IS NOT NULL OR NEW.signal_id IS NOT NULL OR NEW.next_attempt_at IS NULL
                OR NEW.next_attempt_at <= pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'a pending rescreening permit keeps a permit ahead and no outcome (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts = OLD.attempts + 1 THEN
            -- The claim: only a permit that has come; nothing of the answer moves.
            IF OLD.next_attempt_at > pg_catalog.clock_timestamp() OR NEW.cause IS DISTINCT FROM OLD.cause THEN
                RAISE EXCEPTION 'a rescreening permit is claimed only when its permit has come, recording no answer'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.attempts = OLD.attempts THEN
            -- The deferral: after a claim, with the answer's cause.
            IF OLD.attempts < 1 OR NEW.cause IS NULL THEN
                RAISE EXCEPTION 'a rescreening permit is deferred after a claim, with its cause (P13-TSK-022)'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'a claim counts exactly one attempt (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status IN ('DONE', 'FAILED') THEN
        -- The conclusion, once, after a claim, counting no attempt; the instant the database's.
        IF NEW.attempts <> OLD.attempts OR OLD.attempts < 1 THEN
            RAISE EXCEPTION 'a rescreening permit is concluded after a claim, counting no attempt (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.next_attempt_at := NULL;
        NEW.concluded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a rescreening permit moves only along its machine (P13-TSK-022)' USING ERRCODE = 'check_violation';
END;
$$;

-- risk.risk_action_permits_only_machine_edges: V013's definition, 8 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_action_permits_only_machine_edges() RETURNS trigger
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
            NEW.proposed_at := pg_catalog.clock_timestamp();
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
        NEW.next_attempt_at := pg_catalog.clock_timestamp();
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
        NEW.decided_at := pg_catalog.clock_timestamp();
        IF NEW.status = 'APPROVED' THEN
            -- Due at once: the approval door claims it after its commit, the schedule if the door died.
            NEW.next_attempt_at := pg_catalog.clock_timestamp();
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
        IF OLD.next_attempt_at > pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'an action''s permit is claimed only when it has come (P13-TSK-017)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts <> OLD.attempts + 1 OR NEW.next_attempt_at IS NULL OR NEW.next_attempt_at <= pg_catalog.clock_timestamp()
                OR NEW.outcome IS NOT NULL THEN
            RAISE EXCEPTION 'a claim counts one attempt and moves the permit ahead' USING ERRCODE = 'check_violation';
        END IF;
        NEW.dispatched_at := COALESCE(OLD.dispatched_at, pg_catalog.clock_timestamp());
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
        NEW.concluded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a risk action moves only along its machine (P13-TSK-017)' USING ERRCODE = 'check_violation';
END;
$$;

-- risk.risk_alert_permits_only_machine_edges: V010's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_alert_permits_only_machine_edges() RETURNS trigger
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
        NEW.raised_at := pg_catalog.clock_timestamp();
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
        NEW.attached_at := pg_catalog.clock_timestamp();
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
        NEW.suppression_decided_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    IF NEW.status = 'OPEN' THEN
        IF NEW.suppression_decided_by IS NOT NULL OR NEW.suppression_decided_by_type IS NOT NULL
                OR NEW.suppression_decision_reason IS NOT NULL OR NEW.suppression_decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'an open alert carries no suppression decision' USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.suppression_proposed_by IS NULL AND NEW.suppression_proposed_by IS NOT NULL THEN
            -- The proposal: whole, onto none, stamped by the database.
            NEW.suppression_proposed_at := pg_catalog.clock_timestamp();
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

-- risk.risk_assessment_is_born_stamped: V020's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_assessment_is_born_stamped() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    version_start timestamptz;
BEGIN
    -- P15-TSK-037: never before the pinned version began, which a step can put ahead of this clock (see the header); an
    -- unavailable evaluation pins no version, so no floor is read. The evaluation holds the version FOR SHARE.
    SELECT v.effective_from INTO version_start
      FROM risk.risk_policy_version v WHERE v.id = NEW.policy_version_id;
    NEW.assessed_at := GREATEST(pg_catalog.clock_timestamp(), version_start);
    RETURN NEW;
END;
$$;

-- risk.risk_case_action_is_append_only: V012's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_case_action_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.recorded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an investigator action is never edited, deleted or truncated (P13-TSK-016, ADR-0110 decision 4)';
END;
$$;

-- risk.risk_case_event_is_append_only: V012's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_case_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.action_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM risk.risk_case_action a WHERE a.id = NEW.action_id AND a.ordinal = NEW.revision) THEN
            RAISE EXCEPTION 'a person''s step names the action of that step (P13-TSK-016)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.recorded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a case''s history is never edited, deleted or truncated (P13-TSK-016)';
END;
$$;

-- risk.risk_case_permits_only_machine_edges: V012's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_case_permits_only_machine_edges() RETURNS trigger
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
        NEW.opened_at := pg_catalog.clock_timestamp();
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
            NEW.disposition_proposed_at := pg_catalog.clock_timestamp();
        ELSE
            -- Decided by its proposal: a one-person close (four eyes stays the CHECK's - it is not judged here).
            IF NEW.disposition NOT IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION') OR NEW.disposition_prohibits THEN
                RAISE EXCEPTION 'only a one-person close is decided by its own proposal'
                    USING ERRCODE = 'check_violation';
            END IF;
            NEW.disposition_proposed_at := pg_catalog.clock_timestamp();
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
            NEW.disposed_at := pg_catalog.clock_timestamp();
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

-- risk.risk_policy_version_event_is_append_only: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_policy_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a risk policy version event is append-only (P13-TSK-002, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- risk.risk_policy_version_permits_only_machine_edges: V020's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_policy_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a risk policy version is born PROPOSED and undecided: no version is activated by insert (P13-TSK-002)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a risk policy version is never deleted (P13-TSK-002, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.operation IS DISTINCT FROM OLD.operation
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.engine IS DISTINCT FROM OLD.engine
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a risk policy version''s identity is frozen (P13-TSK-002, INV-HIST-04, INV-RSK-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a risk policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P13-TSK-002)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := pg_catalog.clock_timestamp();
        -- P15-TSK-037: exactly where its predecessor ends - the operation's highest-numbered RETIRED version, retired first in
        -- this transaction - chosen by row order, never by a stamp; the operation's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM risk.risk_policy_version v
                  WHERE v.operation = NEW.operation AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                pg_catalog.transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P13-TSK-002)';
        END IF;
        -- Written after the activation's lock on this row (FOR UPDATE, behind every pin's FOR SHARE); never before the
        -- transaction's start, so the successor's effective_from (exactly this end) is never earlier than V002's; never at or
        -- before its own start, whatever the clock says. P15-TSK-037: and never before one microsecond after the latest
        -- assessment pinned to it - the clock can step back between that assessment's stamp and this one.
        NEW.effective_to := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(a.assessed_at) FROM risk.risk_assessment a WHERE a.policy_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- risk.risk_signal_is_born_once: V003's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.risk_signal_is_born_once() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- The row's own stamp is the database's clock, not an instance's - whatever the writer passed (RISK_LIFECYCLES.md
    -- section 3). observed_at stays the event's: that one is the producer's fact, not this transaction's.
    NEW.recorded_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- risk.screening_list_version_permits_only_machine_edges: V018's definition, 7 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.screening_list_version_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: a list version is the record of what was in force.
        RAISE EXCEPTION 'a screening list version is never deleted or truncated (P13-TSK-022)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RECORDED' OR NEW.enqueued <> 0
                OR num_nonnulls(NEW.activated_by, NEW.activated_by_type, NEW.activated_at, NEW.retired_at,
                                NEW.enqueue_next_at, NEW.enqueue_cursor, NEW.enqueue_completed_at) <> 0 THEN
            RAISE EXCEPTION 'a screening list version is born RECORDED, unactivated and enqueuing nothing (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Numbered in the list's recording order without a gap: the list's lock serialises its writers, the ordinal's
        -- unique refuses any that bypassed it.
        IF NEW.ordinal <> (SELECT coalesce(max(v.ordinal), 0) + 1 FROM risk.screening_list_version v
                            WHERE v.list = NEW.list) THEN
            RAISE EXCEPTION 'a list''s versions are numbered in recording order without a gap (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.recorded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The fact and its recorder are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.list IS DISTINCT FROM OLD.list
            OR NEW.version IS DISTINCT FROM OLD.version OR NEW.ordinal IS DISTINCT FROM OLD.ordinal
            OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by
            OR NEW.recorded_by_type IS DISTINCT FROM OLD.recorded_by_type
            OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at THEN
        RAISE EXCEPTION 'a screening list version''s fact never changes (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'RETIRED' THEN
        RAISE EXCEPTION 'a retired screening list version never moves (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'RECORDED' THEN
        -- The activation: a person (four eyes is screening_list_version_four_eyes' - provable alone), the enqueue due at
        -- once from nothing.
        IF NEW.status <> 'ACTIVE' OR NEW.activated_by IS NULL OR NEW.activated_by_type IS NULL
                OR NEW.enqueued <> 0 OR NEW.enqueue_cursor IS NOT NULL OR NEW.enqueue_completed_at IS NOT NULL
                OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a recorded list version is activated by a person, its enqueue starting from nothing'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.activated_at := pg_catalog.clock_timestamp();
        NEW.enqueue_next_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;

    -- ACTIVE: the activation, the correlation and the census so far are frozen.
    IF NEW.activated_by IS DISTINCT FROM OLD.activated_by
            OR NEW.activated_by_type IS DISTINCT FROM OLD.activated_by_type
            OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a list version''s activation never changes (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'RETIRED' THEN
        -- Retired beside its successor's activation: the enqueue stops where it stood.
        IF NEW.enqueue_cursor IS DISTINCT FROM OLD.enqueue_cursor OR NEW.enqueued <> OLD.enqueued
                OR NEW.enqueue_completed_at IS DISTINCT FROM OLD.enqueue_completed_at THEN
            RAISE EXCEPTION 'a retirement moves no part of the census (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        NEW.retired_at := pg_catalog.clock_timestamp();
        NEW.enqueue_next_at := NULL;
        RETURN NEW;
    END IF;

    IF NEW.status = 'ACTIVE' THEN
        -- The enqueue's advance, inside its claim: only a permit that has come, the cursor forward, the count up, the
        -- permit due again at once or cleared with the census complete.
        IF OLD.enqueue_next_at IS NULL OR OLD.enqueue_next_at > pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'a list version''s enqueue advances only within a claim of its due permit (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.enqueued < OLD.enqueued
                OR (OLD.enqueue_cursor IS NOT NULL
                    AND (NEW.enqueue_cursor IS NULL OR NEW.enqueue_cursor < OLD.enqueue_cursor)) THEN
            RAISE EXCEPTION 'a list version''s enqueue only moves forward (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.enqueue_next_at IS NULL THEN
            NEW.enqueue_completed_at := pg_catalog.clock_timestamp();
        ELSE
            NEW.enqueue_next_at := pg_catalog.clock_timestamp();
            NEW.enqueue_completed_at := NULL;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a screening list version moves only along its machine (P13-TSK-022)'
        USING ERRCODE = 'check_violation';
END;
$$;

-- risk.velocity_window_counts_forward: V005's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION risk.velocity_window_counts_forward()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    counted_at timestamptz := pg_catalog.clock_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.count <> 1 THEN
            RAISE EXCEPTION 'a velocity window is born by one permitted movement: its count starts at 1 (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NEW.dimension IS DISTINCT FROM OLD.dimension
           OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref
           OR NEW.window_kind IS DISTINCT FROM OLD.window_kind
           OR NEW.window_start IS DISTINCT FROM OLD.window_start
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.scale IS DISTINCT FROM OLD.scale THEN
            RAISE EXCEPTION 'a velocity window''s key, currency and scale never change (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.count <> OLD.count + 1 THEN
            RAISE EXCEPTION 'a velocity window counts forward by exactly one movement per update - never back, never twice (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.sum_minor < OLD.sum_minor THEN
            RAISE EXCEPTION 'a velocity window''s sum never decreases (P13-TSK-006, INV-RSK-03)'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.window_start > counted_at
       OR counted_at >= NEW.window_start + risk.velocity_window_length(NEW.window_kind) + interval '1 minute' THEN
        RAISE EXCEPTION 'a velocity window is counted only while the database clock is inside it - its start is cut from clock_timestamp() after the caller''s locks, never from an instance clock (P13-TSK-006, INV-RSK-03)'
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.last_counted_at := counted_at;
    RETURN NEW;
END;
$$;
