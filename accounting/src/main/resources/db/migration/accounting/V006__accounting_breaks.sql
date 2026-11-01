-- Accounting breaks: born once, acknowledged under four eyes with a disclosure, resolved by reference (P14-TSK-010).
--
-- ADR-0117 decision 3, owner decision C11, INV-ACC-12, INV-ACC-05, INV-REC-02, INV-AUD-04; PHASE_14_PLAN.md sections 6,
-- 8.3 row 10 and 13.3: every GL-side discrepancy - GL = ledger (MAPPING_GAP, DOUBLE_MAPPING), rule conformance
-- (UNREGISTERED_POSTING, SHAPE_MISMATCH, UNBALANCED_ENTRY), source completeness (SOURCE_DISCREPANCY, SOURCE_DELAYED) - is
-- recorded once per (period, check, subject) and NEVER corrected here: a break is resolved only by REFERENCE to the
-- correcting journal entry the owning domain posted through its own door. Nothing in this schema posts, and no column
-- holds an amount, a balance or a line: the evidence is codes, ids and counts.
--
-- THE PERIOD IS ITS MONTH: period_start is the calendar month's first day - an accounting period's identity (V003's
-- accounting_period_one_per_month) - held by value, not as a reference to the period row, so a check judges a month
-- whether or not its period has been born, and the unique key below is the plan's (period, check, subject).
--
-- BORN ONCE (section 8.3 row 10): the raise is INSERT ... ON CONFLICT ON CONSTRAINT accounting_break_born_once DO NOTHING,
-- so two checks or ten instances raising one discrepancy leave one row, one RAISED case-file row, one audit record and one
-- outbox row - the losers insert nothing. The break, its RAISED row and the outbox row commit in one transaction.
--
-- The lock order (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): (A4) an accounting child row - the break row
-- alone, FOR UPDATE, for an acknowledgement or a resolution; no period row, no namespace 14 key, no money row.

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.accounting_break (
    id                   uuid        NOT NULL,
    period_start         date        NOT NULL,
    check_code           text        NOT NULL,
    break_type           text        NOT NULL,
    subject_ref          text        NOT NULL,
    evidence             text        NOT NULL,
    judged_by            text        NOT NULL,
    status               text        NOT NULL,
    raised_at            timestamptz NOT NULL,
    raised_by            text        NOT NULL,
    raised_by_type       text        NOT NULL,
    correlation_id       text        NOT NULL,
    ack_proposed_by      text,
    ack_proposed_by_type text,
    ack_proposed_at      timestamptz,
    disclosure           text,
    ack_approved_by      text,
    ack_approved_by_type text,
    ack_approved_at      timestamptz,
    ack_approval_reason  text,
    resolved_by          text,
    resolved_by_type     text,
    resolved_at          timestamptz,
    resolving_entry_id   uuid,
    resolution_reason    text,
    CONSTRAINT accounting_break_pk PRIMARY KEY (id),
    -- BORN ONCE per (period, check, subject) - the plan's row-10 arbiter (INV-ACC-12).
    CONSTRAINT accounting_break_born_once UNIQUE (period_start, check_code, subject_ref),
    CONSTRAINT accounting_break_period_is_a_month CHECK (EXTRACT(DAY FROM period_start) = 1),
    -- The three checks that raise breaks (CloseCheckCode); the other six are the close run's (P14-TSK-011).
    CONSTRAINT accounting_break_check_is_known CHECK (check_code IN ('GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS')),
    -- BreakType, each on its check (PHASE_14_PLAN.md section 13.3's three levels).
    CONSTRAINT accounting_break_type_is_its_checks CHECK (
        (check_code = 'GL_EQUALS_LEDGER' AND break_type IN ('MAPPING_GAP', 'DOUBLE_MAPPING'))
        OR (check_code = 'RULE_CONFORMANCE' AND break_type IN ('UNREGISTERED_POSTING', 'SHAPE_MISMATCH', 'UNBALANCED_ENTRY'))
        OR (check_code = 'SOURCE_COMPLETENESS' AND break_type IN ('SOURCE_DISCREPANCY', 'SOURCE_DELAYED'))),
    -- BreakStatus.
    CONSTRAINT accounting_break_status_is_known CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT accounting_break_subject_bounded CHECK (char_length(subject_ref) BETWEEN 1 AND 300),
    CONSTRAINT accounting_break_evidence_bounded CHECK (char_length(evidence) BETWEEN 1 AND 2000),
    -- VERSIONED JUDGEMENT: the register version the check judged by.
    CONSTRAINT accounting_break_judged_by_a_version CHECK (judged_by ~ '^(posting-rules|gl-mapping|source-proofs):v[1-9][0-9]{0,8}$'),
    -- The raiser is the run's: the platform's schedule or a person's re-run; every act after it a person's.
    CONSTRAINT accounting_break_persons CHECK (
        raised_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE')
        AND (ack_proposed_by_type IS NULL OR ack_proposed_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (ack_approved_by_type IS NULL OR ack_approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (resolved_by_type IS NULL OR resolved_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- An acknowledgement proposal is whole; its approval is whole, only of a proposal, and leaves the break ACKNOWLEDGED
    -- (or RESOLVED after it); a proposal pending on an OPEN break lapses when the break is resolved.
    CONSTRAINT accounting_break_acknowledgement_coherent CHECK (
        (ack_proposed_by IS NULL) = (ack_proposed_by_type IS NULL)
        AND (ack_proposed_by IS NULL) = (ack_proposed_at IS NULL)
        AND (ack_proposed_by IS NULL) = (disclosure IS NULL)
        AND (ack_approved_by IS NULL) = (ack_approved_by_type IS NULL)
        AND (ack_approved_by IS NULL) = (ack_approved_at IS NULL)
        AND (ack_approved_by IS NULL) = (ack_approval_reason IS NULL)
        AND (ack_approved_by IS NULL OR ack_proposed_by IS NOT NULL)
        AND (status <> 'ACKNOWLEDGED' OR ack_approved_by IS NOT NULL)
        AND (status <> 'OPEN' OR ack_approved_by IS NULL)),
    -- A resolution is whole, names its correcting entry, and is recorded exactly when RESOLVED.
    CONSTRAINT accounting_break_resolution_coherent CHECK (
        (status = 'RESOLVED') = (resolved_by IS NOT NULL)
        AND (resolved_by IS NULL) = (resolved_by_type IS NULL)
        AND (resolved_by IS NULL) = (resolved_at IS NULL)
        AND (resolved_by IS NULL) = (resolving_entry_id IS NULL)
        AND (resolved_by IS NULL) = (resolution_reason IS NULL)),
    CONSTRAINT accounting_break_reasoned CHECK (
        (disclosure IS NULL OR char_length(disclosure) BETWEEN 1 AND 1000)
        AND (ack_approval_reason IS NULL OR char_length(ack_approval_reason) BETWEEN 1 AND 1000)
        AND (resolution_reason IS NULL OR char_length(resolution_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever approved an acknowledgement did not propose it.
    CONSTRAINT accounting_break_acknowledgement_four_eyes CHECK (
        ack_approved_by IS NULL OR ack_approved_by <> ack_proposed_by)
);

COMMENT ON TABLE accounting.accounting_break IS
    'An accounting break (P14-TSK-010, ADR-0117 decision 3, C11, INV-ACC-12): a GL-side discrepancy one of the three checks found - GL = ledger, rule conformance, source completeness - born OPEN once per (period month, check, subject) and never corrected: OPEN -> ACKNOWLEDGED by a second person''s approval of a disclosed acknowledgement (four eyes), OPEN | ACKNOWLEDGED -> RESOLVED by reference to the correcting journal entry the owning domain posted. Never deleted. No money: the evidence is codes, ids and counts.';

-- The open breaks by type - finapp.accounting.break.open{type}.
CREATE INDEX accounting_break_open_by_type ON accounting.accounting_break (break_type) WHERE status <> 'RESOLVED';
-- A period's breaks by status - the operator's list and the close's OPEN_ACCOUNTING_BREAKS (P14-TSK-011).
CREATE INDEX accounting_break_by_period ON accounting.accounting_break (period_start, status);

-- The edge trigger: every stamp clock_timestamp(), the instant written after the lock the act waited on.
CREATE OR REPLACE FUNCTION accounting.accounting_break_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.ack_proposed_by IS NOT NULL OR NEW.ack_approved_by IS NOT NULL
                OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an accounting break is born OPEN with nothing acted (P14-TSK-010)';
        END IF;
        NEW.raised_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an accounting break is never deleted (P14-TSK-010, INV-ACC-12)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_start IS DISTINCT FROM OLD.period_start
            OR NEW.check_code IS DISTINCT FROM OLD.check_code OR NEW.break_type IS DISTINCT FROM OLD.break_type
            OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref OR NEW.evidence IS DISTINCT FROM OLD.evidence
            OR NEW.judged_by IS DISTINCT FROM OLD.judged_by OR NEW.raised_at IS DISTINCT FROM OLD.raised_at
            OR NEW.raised_by IS DISTINCT FROM OLD.raised_by OR NEW.raised_by_type IS DISTINCT FROM OLD.raised_by_type
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an accounting break''s finding is frozen from its birth (P14-TSK-010, INV-ACC-12)';
    END IF;
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'a RESOLVED accounting break is final (P14-TSK-010)';
    END IF;
    IF OLD.ack_proposed_by IS NOT NULL AND (NEW.ack_proposed_by IS DISTINCT FROM OLD.ack_proposed_by
            OR NEW.ack_proposed_by_type IS DISTINCT FROM OLD.ack_proposed_by_type
            OR NEW.ack_proposed_at IS DISTINCT FROM OLD.ack_proposed_at
            OR NEW.disclosure IS DISTINCT FROM OLD.disclosure) THEN
        RAISE EXCEPTION 'an acknowledgement proposal is frozen once made (P14-TSK-010)';
    END IF;
    IF OLD.ack_approved_by IS NOT NULL AND (NEW.ack_approved_by IS DISTINCT FROM OLD.ack_approved_by
            OR NEW.ack_approved_by_type IS DISTINCT FROM OLD.ack_approved_by_type
            OR NEW.ack_approved_at IS DISTINCT FROM OLD.ack_approved_at
            OR NEW.ack_approval_reason IS DISTINCT FROM OLD.ack_approval_reason) THEN
        RAISE EXCEPTION 'an acknowledgement is frozen once approved (P14-TSK-010)';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'OPEN' THEN
        -- The acknowledgement proposal: recorded once on an OPEN break, nothing else moves.
        IF OLD.ack_proposed_by IS NOT NULL OR NEW.ack_proposed_by IS NULL OR NEW.ack_approved_by IS NOT NULL
                OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an OPEN accounting break records one acknowledgement proposal and nothing else (P14-TSK-010)';
        END IF;
        NEW.ack_proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'ACKNOWLEDGED' THEN
        IF OLD.ack_proposed_by IS NULL OR NEW.ack_approved_by IS NULL OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'a break is acknowledged only by the approval of its pending proposal (P14-TSK-010, INV-AUD-04)';
        END IF;
        NEW.ack_approved_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status IN ('OPEN', 'ACKNOWLEDGED') AND NEW.status = 'RESOLVED' THEN
        IF NEW.resolved_by IS NULL OR NEW.resolving_entry_id IS NULL
                OR NEW.ack_approved_by IS DISTINCT FROM OLD.ack_approved_by
                OR (OLD.ack_proposed_by IS NULL AND NEW.ack_proposed_by IS NOT NULL) THEN
            RAISE EXCEPTION 'a break is resolved only by reference to its correcting entry, and nothing else moves (P14-TSK-010)';
        END IF;
        NEW.resolved_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an accounting break moves OPEN -> ACKNOWLEDGED (four eyes), OPEN | ACKNOWLEDGED -> RESOLVED; % -> % is not an edge (P14-TSK-010)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER accounting_break_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.accounting_break
    FOR EACH ROW
    EXECUTE FUNCTION accounting.accounting_break_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.accounting_break_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the accounting breaks and their case files are never truncated (P14-TSK-010, INV-REC-02)';
END;
$$;

CREATE TRIGGER accounting_break_is_never_truncated
    BEFORE TRUNCATE ON accounting.accounting_break
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_break_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The case file: every act, append-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.break_event (
    id                 uuid        NOT NULL,
    break_id           uuid        NOT NULL,
    act                text        NOT NULL,
    from_status        text,
    to_status          text        NOT NULL,
    actor              text        NOT NULL,
    actor_type         text        NOT NULL,
    proposed_by        text,
    resolving_entry_id uuid,
    reason             text,
    occurred_at        timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT break_event_pk PRIMARY KEY (id),
    CONSTRAINT break_event_break_fk FOREIGN KEY (break_id) REFERENCES accounting.accounting_break (id),
    -- AccountingBreakStore.Act, each on its one edge.
    CONSTRAINT break_event_act_is_an_edge CHECK (
        (act = 'RAISED' AND from_status IS NULL AND to_status = 'OPEN')
        OR (act = 'ACKNOWLEDGEMENT_PROPOSED' AND from_status = 'OPEN' AND to_status = 'OPEN')
        OR (act = 'ACKNOWLEDGED' AND from_status = 'OPEN' AND to_status = 'ACKNOWLEDGED')
        OR (act = 'RESOLVED' AND from_status IN ('OPEN', 'ACKNOWLEDGED') AND to_status = 'RESOLVED')),
    -- The raise is the run's (the platform's or a person's); every other act a person's.
    CONSTRAINT break_event_actor_kind CHECK (
        actor_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE') AND (act = 'RAISED' OR actor_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- FOUR EYES on the case file too: the acknowledgement names its proposer, never the approver.
    CONSTRAINT break_event_four_eyes CHECK (
        (act = 'ACKNOWLEDGED') = (proposed_by IS NOT NULL) AND (proposed_by IS NULL OR proposed_by <> actor)),
    CONSTRAINT break_event_resolution_names_its_entry CHECK ((act = 'RESOLVED') = (resolving_entry_id IS NOT NULL)),
    CONSTRAINT break_event_reasoned CHECK (
        (act = 'RAISED' OR reason IS NOT NULL) AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000))
);

CREATE INDEX break_event_by_break ON accounting.break_event (break_id, occurred_at);

COMMENT ON TABLE accounting.break_event IS
    'An accounting break''s case file (P14-TSK-010): the raise (the run''s actor), the acknowledgement proposal with its disclosure, the acknowledgement naming its proposer, the resolution naming its correcting entry - each with its actor, reason (the disclosure is CONFIDENTIAL, every other reason RESTRICTED-PII, screened by AccountingReasons) and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION accounting.break_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accounting break''s case file is append-only (P14-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER break_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.break_event
    FOR EACH ROW
    EXECUTE FUNCTION accounting.break_event_is_append_only();

CREATE TRIGGER break_event_is_never_truncated
    BEFORE TRUNCATE ON accounting.break_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_break_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's act columns only; the case file INSERT-only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.accounting_break TO finapp_app;
GRANT UPDATE (status, ack_proposed_by, ack_proposed_by_type, ack_proposed_at, disclosure, ack_approved_by, ack_approved_by_type, ack_approved_at, ack_approval_reason, resolved_by, resolved_by_type, resolved_at, resolving_entry_id, resolution_reason)
    ON accounting.accounting_break TO finapp_app;
GRANT SELECT, INSERT ON accounting.break_event TO finapp_app;
