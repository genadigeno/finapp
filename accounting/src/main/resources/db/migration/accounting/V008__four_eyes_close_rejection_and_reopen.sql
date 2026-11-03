-- The four-eyes close, its rejection and the reopen; a break's generation (P14-TSK-012).
--
-- ADR-0114 (the close), ADR-0117 (the owner's ruling of 2026-11-02), INV-ACC-03, INV-ACC-09, INV-ACC-10, INV-ACC-12, INV-AUD-04;
-- PHASE_14_PLAN.md sections 6, 8.1 T-close and T-reopen, 8.2, 8.3 rows 3, 4 and 9, 13.5. This migration decides when a month's books
-- become immutable, so each of its rules holds for every writer, the migrator included.
--
-- THE MACHINE: OPEN -> LOCKED -> CLOSE_PROPOSED -> CLOSED; CLOSE_PROPOSED -> LOCKED (rejected); LOCKED -> OPEN (P14-TSK-005's unlock);
-- CLOSED -> OPEN (the reopen, revision + 1). V003's edge function is replaced whole: the edges it held stand as they were.
--
-- THE PROPOSAL IS A ROW (period_close), and so is the reopen (period_reopen): a rejected proposal stays what it was, and the next is a
-- new row carrying its own reasons. Nine rows beside it (period_close_check) name the attempts the person read, one per check, each
-- bound to its frozen attempt row - and, for a DISCLOSED attempt, a copy of what was disclosed and the person's reason for it (INV-ACC-09,
-- unamended). The trigger holds each to its attempt and to that attempt being the latest; an approval is refused while any is not.
--
-- A BREAK'S GENERATION (ADR-0117's ruling): a finding is compared with the evidence a person decided on. Unchanged counts as disclosed;
-- changed is a new break, generation + 1, with its own four eyes. accounting_break_born_once takes the generation into its key. Rows
-- already in the table are generation 1 by the column's default - ADD COLUMN fires no row trigger - and the old key's uniqueness implies
-- the new one's, so the rebuilt constraint holds for them. The birth still reads ON CONFLICT ON CONSTRAINT accounting_break_born_once.
--
-- The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): (A1) the period FOR UPDATE - the previous FOR SHARE first and the
-- next FOR SHARE after, for a reopen's approval, oldest first; (A4) the proposal row, and for a reopen's approval the FINAL report runs;
-- (A2) namespace 14 key cutoff exclusive and (A3) the ledger's posting_cutoff row, a reopen's approval only, inside the ledger's own API,
-- never a write here. The statements below hold no lock but the triggers' period FOR SHARE, each on a row the domain's path already holds: a proposal's period, a lock's previous, a reopen's next. A raw writer, holding the row it
-- updates when its trigger asks for a neighbour, takes them newer before older; that can only meet a deadlock's refusal, never a wrong edge. The application role holds the column UPDATE that FOR SHARE needs.
--
-- NO MONEY: no column here holds an amount. A copied evidence is canonical JSON text as the attempt froze it, aggregates per currency at
-- most. Nothing here posts. The migration names no other schema. No DELETE for any role; never truncated.

-- ---------------------------------------------------------------------------------------------
-- A break's generation.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE accounting.accounting_break ADD COLUMN generation integer NOT NULL DEFAULT 1;

ALTER TABLE accounting.accounting_break
    DROP CONSTRAINT accounting_break_born_once,
    -- BORN ONCE per (period, check, subject, generation): two workers judging one changed finding insert one row, and an overtaken one -
    -- whose generation number comes from what it read - inserts nothing once that generation exists.
    ADD CONSTRAINT accounting_break_born_once UNIQUE (period_start, check_code, subject_ref, generation),
    ADD CONSTRAINT accounting_break_generation_positive CHECK (generation >= 1);

COMMENT ON COLUMN accounting.accounting_break.generation IS
    'Which break of this (period, check, subject) this is (P14-TSK-012, ADR-0117): 1, then one more each time a finding differs from the evidence a person decided on - the earlier generation ACKNOWLEDGED or RESOLVED and left as it was. Frozen from birth.';

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
        -- A LATER GENERATION (P14-TSK-012, ADR-0117): only over the generation before it, decided, and reading differently. A racer that
        -- lost passes this and meets the unique's ON CONFLICT DO NOTHING - a loser writes nothing, it is not refused.
        IF NEW.generation > 1 AND NOT EXISTS (
                SELECT 1 FROM accounting.accounting_break earlier
                 WHERE earlier.period_start = NEW.period_start AND earlier.check_code = NEW.check_code
                   AND earlier.subject_ref = NEW.subject_ref AND earlier.generation = NEW.generation - 1
                   AND earlier.status IN ('ACKNOWLEDGED', 'RESOLVED') AND earlier.evidence <> NEW.evidence) THEN
            RAISE EXCEPTION 'a later generation of a break follows a decided one and reads differently (P14-TSK-012, ADR-0117)';
        END IF;
        NEW.raised_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an accounting break is never deleted (P14-TSK-010, INV-ACC-12)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_start IS DISTINCT FROM OLD.period_start
            OR NEW.check_code IS DISTINCT FROM OLD.check_code OR NEW.break_type IS DISTINCT FROM OLD.break_type
            OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref OR NEW.generation IS DISTINCT FROM OLD.generation
            OR NEW.evidence IS DISTINCT FROM OLD.evidence
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

-- ---------------------------------------------------------------------------------------------
-- The close proposal: one row per proposal, never rewritten but for its one decision.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.period_close (
    id                        uuid        NOT NULL,
    period_id                 uuid        NOT NULL,
    period_revision           integer     NOT NULL,
    period_locked_at          timestamptz NOT NULL,
    close_run_id              uuid        NOT NULL,
    pinned_mapping_version_id uuid        NOT NULL,
    valuation_rate_set_id     uuid        NOT NULL,
    breaks_acknowledged       bigint      NOT NULL,
    breaks_resolved           bigint      NOT NULL,
    status                    text        NOT NULL,
    proposed_by               text        NOT NULL,
    proposed_by_type          text        NOT NULL,
    proposed_at               timestamptz NOT NULL,
    proposal_reason           text        NOT NULL,
    decided_by                text,
    decided_by_type           text,
    decided_at                timestamptz,
    decision_reason           text,
    correlation_id            text        NOT NULL,
    CONSTRAINT period_close_pk PRIMARY KEY (id),
    CONSTRAINT period_close_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    CONSTRAINT period_close_run_fk FOREIGN KEY (close_run_id) REFERENCES accounting.close_run (id),
    CONSTRAINT period_close_pinned_version_fk FOREIGN KEY (pinned_mapping_version_id)
        REFERENCES accounting.gl_mapping_version (id),
    CONSTRAINT period_close_rate_set_fk FOREIGN KEY (valuation_rate_set_id)
        REFERENCES accounting.valuation_rate_set (id),
    -- PeriodCloseStatus.
    CONSTRAINT period_close_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT period_close_revision_positive CHECK (period_revision >= 1),
    CONSTRAINT period_close_counts_positive CHECK (breaks_acknowledged >= 0 AND breaks_resolved >= 0),
    -- Persons only: an authenticated human proposes and decides.
    CONSTRAINT period_close_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- A decision is whole and reasoned exactly when the proposal is no longer PROPOSED.
    CONSTRAINT period_close_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT period_close_reasoned CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-ACC-09, INV-AUD-04): whoever approved a close did not propose it. A rejection may be the proposer's.
    CONSTRAINT period_close_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)
);

COMMENT ON TABLE accounting.period_close IS
    'A close proposal (P14-TSK-012, ADR-0114, INV-ACC-09): an accountant''s statement of what they read - the run, the pinned GL mapping version, the approved valuation rate set, the month''s decided breaks and, in period_close_check, the nine attempts - for one lock instance of a LOCKED period. PROPOSED -> APPROVED by a second person (the period CLOSED) | REJECTED (the period LOCKED again, the proposal kept; the next is a new row). Never deleted. No money.';

-- One live proposal per period; one close per revision - the arbiters beneath the period row's lock (row 3).
CREATE UNIQUE INDEX period_close_one_pending ON accounting.period_close (period_id) WHERE status = 'PROPOSED';
CREATE UNIQUE INDEX period_close_one_approved ON accounting.period_close (period_id, period_revision) WHERE status = 'APPROVED';
CREATE INDEX period_close_by_period ON accounting.period_close (period_id, proposed_at);

CREATE OR REPLACE FUNCTION accounting.period_close_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status    text;
    held_locked_at timestamptz;
    held_revision  integer;
    held_pin       uuid;
    held_unlock    text;
    held_start     date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a close proposal is never deleted (P14-TSK-012, INV-ACC-09)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a close proposal is born PROPOSED and undecided (P14-TSK-012)';
        END IF;
        -- UNDER THE PRESENT LOCK: the period LOCKED, this lock, this revision, its pin, no unlock pending.
        SELECT p.status, p.locked_at, p.revision, p.pinned_mapping_version_id, p.unlock_proposed_by
          INTO held_status, held_locked_at, held_revision, held_pin, held_unlock
          FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
        IF NOT FOUND OR held_status <> 'LOCKED' OR held_locked_at IS DISTINCT FROM NEW.period_locked_at
                OR held_revision <> NEW.period_revision OR held_pin IS DISTINCT FROM NEW.pinned_mapping_version_id
                OR held_unlock IS NOT NULL THEN
            RAISE EXCEPTION 'a close is proposed for a LOCKED period under its present lock, its pin named, no unlock pending (P14-TSK-012, INV-ACC-09)';
        END IF;
        -- THE RUN ANSWERS THIS LOCK; THE SET IS THE PERIOD'S, APPROVED.
        IF NOT EXISTS (SELECT 1 FROM accounting.close_run r WHERE r.id = NEW.close_run_id AND r.period_id = NEW.period_id
                AND r.period_locked_at = NEW.period_locked_at) THEN
            RAISE EXCEPTION 'a close proposal names the close run of its present lock (P14-TSK-012, INV-ACC-09)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM accounting.valuation_rate_set s WHERE s.id = NEW.valuation_rate_set_id
                AND s.period_id = NEW.period_id AND s.status = 'APPROVED') THEN
            RAISE EXCEPTION 'a close proposal names its period''s approved valuation rate set (P14-TSK-012, INV-ACC-09)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision
            OR NEW.period_locked_at IS DISTINCT FROM OLD.period_locked_at
            OR NEW.close_run_id IS DISTINCT FROM OLD.close_run_id
            OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id
            OR NEW.valuation_rate_set_id IS DISTINCT FROM OLD.valuation_rate_set_id
            OR NEW.breaks_acknowledged IS DISTINCT FROM OLD.breaks_acknowledged
            OR NEW.breaks_resolved IS DISTINCT FROM OLD.breaks_resolved
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a close proposal is frozen but for its one decision (P14-TSK-012, INV-ACC-09)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a close proposal moves PROPOSED -> APPROVED | REJECTED, once; % -> % is not an edge (P14-TSK-012)', OLD.status, NEW.status;
    END IF;
    SELECT p.status, p.period_start INTO held_status, held_start FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
    IF held_status <> 'CLOSE_PROPOSED' THEN
        RAISE EXCEPTION 'a close proposal is decided while its period is CLOSE_PROPOSED, not % (P14-TSK-012)', held_status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        -- WHAT WAS READ IS WHAT STANDS: all nine, none overtaken, no break of the month OPEN.
        IF (SELECT count(*) FROM accounting.period_close_check f WHERE f.close_id = NEW.id) <> 9 THEN
            RAISE EXCEPTION 'a close is approved only with its nine checks recorded (P14-TSK-012, INV-ACC-09)';
        END IF;
        IF EXISTS (SELECT 1 FROM accounting.period_close_check f
                     JOIN accounting.close_check later ON later.close_run_id = NEW.close_run_id
                      AND later.check_code = f.check_code AND later.attempt > f.attempt
                    WHERE f.close_id = NEW.id) THEN
            RAISE EXCEPTION 'a close is approved only while every proposed attempt is still its check''s latest (P14-TSK-012, INV-ACC-09)';
        END IF;
        IF EXISTS (SELECT 1 FROM accounting.accounting_break b WHERE b.period_start = held_start AND b.status = 'OPEN') THEN
            RAISE EXCEPTION 'a close is approved only with no break of its month OPEN (P14-TSK-012, INV-ACC-09)';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER period_close_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_close
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_close_permits_only_machine_edges();

CREATE TRIGGER period_close_is_never_truncated
    BEFORE TRUNCATE ON accounting.period_close
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- A proposal and its decision commit only with the period's edge: no proposal stands beside a period that did not move.
CREATE OR REPLACE FUNCTION accounting.period_close_commits_with_its_edge()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
BEGIN
    SELECT p.status INTO held_status FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
    IF (NEW.status = 'PROPOSED' AND held_status <> 'CLOSE_PROPOSED')
            OR (NEW.status = 'APPROVED' AND held_status <> 'CLOSED')
            OR (NEW.status = 'REJECTED' AND held_status <> 'LOCKED') THEN
        RAISE EXCEPTION 'a close proposal % committed beside a period % (P14-TSK-012)', NEW.status, held_status;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER period_close_commits_with_its_edge
    AFTER INSERT OR UPDATE ON accounting.period_close
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_close_commits_with_its_edge();

-- ---------------------------------------------------------------------------------------------
-- The close record: what the person read, one row per check, frozen.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.period_close_check (
    close_id          uuid    NOT NULL,
    check_code        text    NOT NULL,
    close_check_id    uuid    NOT NULL,
    attempt           integer NOT NULL,
    status            text    NOT NULL,
    judged_by         text    NOT NULL,
    evidence          text,
    disclosure_reason text,
    CONSTRAINT period_close_check_pk PRIMARY KEY (close_id, check_code),
    CONSTRAINT period_close_check_close_fk FOREIGN KEY (close_id) REFERENCES accounting.period_close (id),
    CONSTRAINT period_close_check_attempt_fk FOREIGN KEY (close_check_id) REFERENCES accounting.close_check (id),
    -- CloseCheckCode.
    CONSTRAINT period_close_check_code_is_known CHECK (check_code IN ('LEDGER_TRIAL_BALANCE', 'GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS', 'ACCRUALS_COMPLETE', 'PROJECTION_VERIFIED', 'RECONCILIATION_DISCLOSURE', 'VALUATION_SET_APPROVED', 'OPEN_ACCOUNTING_BREAKS')),
    -- CloseCheckStatus.readyForClose: what a close may be proposed on.
    CONSTRAINT period_close_check_is_ready CHECK (status IN ('PASSED', 'DISCLOSED')),
    -- INV-ACC-09: a DISCLOSED attempt carries a person's reason and what it disclosed; a PASSED one neither.
    CONSTRAINT period_close_check_disclosure_reasoned CHECK (
        (status = 'DISCLOSED') = (disclosure_reason IS NOT NULL)
        AND (status = 'DISCLOSED') = (evidence IS NOT NULL)),
    CONSTRAINT period_close_check_reason_bounded CHECK (
        disclosure_reason IS NULL OR char_length(disclosure_reason) BETWEEN 1 AND 1000),
    CONSTRAINT period_close_check_evidence_bounded CHECK (evidence IS NULL OR char_length(evidence) BETWEEN 2 AND 16000)
);

COMMENT ON TABLE accounting.period_close_check IS
    'One check of a close proposal (P14-TSK-012, INV-ACC-09, INV-ACC-05): the attempt the person read, frozen - its id, number, status and the versions that judged it; for a DISCLOSED attempt a copy of its evidence and the person''s reason for it (CONFIDENTIAL, served to the approver, never logged). The disclosed items frozen into the close record. INSERT-only for every writer.';

CREATE OR REPLACE FUNCTION accounting.period_close_check_is_the_attempt()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
    held_run    uuid;
    read_run    uuid;
    read_code   text;
    read_number integer;
    read_status text;
    read_judged text;
    read_body   text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'the close record is append-only (P14-TSK-012, INV-ACC-09)';
    END IF;
    SELECT c.status, c.close_run_id INTO held_status, held_run FROM accounting.period_close c WHERE c.id = NEW.close_id;
    IF NOT FOUND OR held_status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'a close''s checks are recorded with its proposal (P14-TSK-012)';
    END IF;
    SELECT a.close_run_id, a.check_code, a.attempt, a.status, a.judged_by, a.evidence
      INTO read_run, read_code, read_number, read_status, read_judged, read_body
      FROM accounting.close_check a WHERE a.id = NEW.close_check_id;
    IF NOT FOUND OR read_run <> held_run OR read_code <> NEW.check_code OR read_number <> NEW.attempt OR read_status <> NEW.status
            OR read_judged IS DISTINCT FROM NEW.judged_by
            OR (NEW.status = 'DISCLOSED' AND read_body IS DISTINCT FROM NEW.evidence) THEN
        RAISE EXCEPTION 'a close''s check is the attempt it names, of the proposal''s run, as that attempt concluded (P14-TSK-012, INV-ACC-09)';
    END IF;
    IF EXISTS (SELECT 1 FROM accounting.close_check later WHERE later.close_run_id = held_run
            AND later.check_code = NEW.check_code AND later.attempt > NEW.attempt) THEN
        RAISE EXCEPTION 'a close''s check is its check''s latest attempt (P14-TSK-012, INV-ACC-09)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER period_close_check_is_the_attempt
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_close_check
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_close_check_is_the_attempt();

CREATE TRIGGER period_close_check_is_never_truncated
    BEFORE TRUNCATE ON accounting.period_close_check
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The reopen proposal: one per revision of a CLOSED period.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.period_reopen (
    id               uuid        NOT NULL,
    period_id        uuid        NOT NULL,
    period_revision  integer     NOT NULL,
    status           text        NOT NULL,
    proposed_by      text        NOT NULL,
    proposed_by_type text        NOT NULL,
    proposed_at      timestamptz NOT NULL,
    proposal_reason  text        NOT NULL,
    approved_by      text,
    approved_by_type text,
    approved_at      timestamptz,
    approval_reason  text,
    correlation_id   text        NOT NULL,
    CONSTRAINT period_reopen_pk PRIMARY KEY (id),
    CONSTRAINT period_reopen_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    -- One reopen per revision: a second person approves it or it stands; the next revision may have its own.
    CONSTRAINT period_reopen_one_per_revision UNIQUE (period_id, period_revision),
    -- PeriodReopenStatus.
    CONSTRAINT period_reopen_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED')),
    CONSTRAINT period_reopen_revision_positive CHECK (period_revision >= 1),
    CONSTRAINT period_reopen_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- An approval is whole and reasoned exactly when APPROVED.
    CONSTRAINT period_reopen_approval_coherent CHECK (
        (status = 'APPROVED') = (approved_by IS NOT NULL)
        AND (approved_by IS NULL) = (approved_by_type IS NULL)
        AND (approved_by IS NULL) = (approved_at IS NULL)
        AND (approved_by IS NULL) = (approval_reason IS NULL)),
    CONSTRAINT period_reopen_reasoned CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (approval_reason IS NULL OR char_length(approval_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-ACC-10, INV-AUD-04): whoever approved a reopen did not propose it.
    CONSTRAINT period_reopen_four_eyes CHECK (approved_by IS NULL OR approved_by <> proposed_by)
);

COMMENT ON TABLE accounting.period_reopen IS
    'A reopen proposal (P14-TSK-012, ADR-0114, INV-ACC-10): a person''s proposal to return the latest CLOSED period to OPEN, nothing moving until a second person approves it. Approved: the ledger''s cutoff back one period, the period''s FINAL runs SUPERSEDED, the period OPEN at revision + 1, in one transaction. One per revision. Never deleted. No money.';

CREATE OR REPLACE FUNCTION accounting.period_reopen_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status   text;
    held_revision integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a reopen proposal is never deleted (P14-TSK-012, INV-ACC-10)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.approved_by IS NOT NULL OR NEW.approved_by_type IS NOT NULL
                OR NEW.approved_at IS NOT NULL OR NEW.approval_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a reopen proposal is born PROPOSED and undecided (P14-TSK-012)';
        END IF;
        SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
        IF NOT FOUND OR held_status <> 'CLOSED' OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a reopen is proposed for a CLOSED period at its present revision (P14-TSK-012, INV-ACC-10)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a reopen proposal is frozen but for its approval (P14-TSK-012, INV-ACC-10)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status = 'APPROVED') THEN
        RAISE EXCEPTION 'a reopen proposal moves PROPOSED -> APPROVED, once; % -> % is not an edge (P14-TSK-012)', OLD.status, NEW.status;
    END IF;
    SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
    IF held_status <> 'CLOSED' OR held_revision <> NEW.period_revision THEN
        RAISE EXCEPTION 'a reopen is approved while its period is CLOSED at the proposal''s revision (P14-TSK-012, INV-ACC-10)';
    END IF;
    NEW.approved_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER period_reopen_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_reopen
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_reopen_permits_only_machine_edges();

CREATE TRIGGER period_reopen_is_never_truncated
    BEFORE TRUNCATE ON accounting.period_reopen
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- An approved reopen commits only with the period's edge.
CREATE OR REPLACE FUNCTION accounting.period_reopen_commits_with_its_edge()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_revision integer;
BEGIN
    SELECT p.revision INTO held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
    IF NEW.status = 'APPROVED' AND held_revision <= NEW.period_revision THEN
        RAISE EXCEPTION 'an approved reopen committed beside a period that did not move (P14-TSK-012, INV-ACC-10)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER period_reopen_commits_with_its_edge
    AFTER UPDATE ON accounting.period_reopen
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_reopen_commits_with_its_edge();

-- ---------------------------------------------------------------------------------------------
-- The period's machine, replaced whole: V003's edges as they were, the close's and the reopen's added.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION accounting.accounting_period_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    first_start     date;
    latest_start    date;
    previous_status text;
    next_status     text;
    in_force        uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.revision <> 1 OR NEW.locked_by IS NOT NULL OR NEW.locked_by_type IS NOT NULL
                OR NEW.locked_at IS NOT NULL OR NEW.pinned_mapping_version_id IS NOT NULL
                OR NEW.unlock_proposed_by IS NOT NULL OR NEW.unlock_approved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an accounting period is born OPEN at revision 1 with no act recorded (P14-TSK-005)';
        END IF;
        -- CONTIGUOUS: the month after the latest, or a month already held (the unique then decides - the open's ON
        -- CONFLICT DO NOTHING); never a gap, never before the first.
        SELECT min(period_start), max(period_start) INTO first_start, latest_start FROM accounting.accounting_period;
        IF latest_start IS NOT NULL AND (NEW.period_start > (latest_start + interval '1 month')::date
                OR NEW.period_start < first_start) THEN
            RAISE EXCEPTION 'accounting periods are contiguous calendar months: % is not the month after % (P14-TSK-005)', NEW.period_start, latest_start;
        END IF;
        NEW.opened_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an accounting period is never deleted (P14-TSK-005, INV-ACC-03)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_start IS DISTINCT FROM OLD.period_start
            OR NEW.period_end IS DISTINCT FROM OLD.period_end
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an accounting period''s identity is frozen (P14-TSK-005)';
    END IF;
    -- THE REVISION moves only at a reopen, by one (INV-ACC-10).
    IF NEW.revision IS DISTINCT FROM OLD.revision AND NOT (OLD.status = 'CLOSED' AND NEW.status = 'OPEN') THEN
        RAISE EXCEPTION 'an accounting period''s revision moves only at a reopen (P14-TSK-012, INV-ACC-10)';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'LOCKED' THEN
        -- IN ORDER: the previous period, when there is one, is CLOSED - held FOR SHARE, as lockWithPrevious already holds it on the domain's path, so a raw writer's read cannot be overtaken.
        SELECT p.status INTO previous_status FROM accounting.accounting_period p
         WHERE p.period_start = (OLD.period_start - interval '1 month')::date FOR SHARE;
        IF FOUND AND previous_status <> 'CLOSED' THEN
            RAISE EXCEPTION 'accounting periods lock in order: the period before % is %, not CLOSED (P14-TSK-005)', OLD.period_start, previous_status;
        END IF;
        -- THE PIN (INV-ACC-07): the activated version whose month range holds the period, or none when none does.
        SELECT v.id INTO in_force FROM accounting.gl_mapping_version v
         WHERE v.status IN ('ACTIVE', 'RETIRED') AND v.effective_from_period <= OLD.period_start
           AND (v.effective_to_period IS NULL OR v.effective_to_period > OLD.period_start);
        IF NEW.pinned_mapping_version_id IS DISTINCT FROM in_force THEN
            RAISE EXCEPTION 'a locked period pins the GL mapping version in force for its month (P14-TSK-005, INV-ACC-07)';
        END IF;
        IF NEW.unlock_proposed_by IS NOT NULL OR NEW.unlock_approved_by IS NOT NULL THEN
            RAISE EXCEPTION 'a lock starts the period''s lock afresh: no unlock is carried into it (P14-TSK-005)';
        END IF;
        NEW.locked_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'LOCKED' AND NEW.status = 'LOCKED' THEN
        IF OLD.unlock_proposed_by IS NOT NULL OR NEW.unlock_proposed_by IS NULL OR NEW.unlock_approved_by IS NOT NULL
                OR NEW.locked_by IS DISTINCT FROM OLD.locked_by OR NEW.locked_by_type IS DISTINCT FROM OLD.locked_by_type
                OR NEW.locked_at IS DISTINCT FROM OLD.locked_at
                OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id THEN
            RAISE EXCEPTION 'a LOCKED period records one unlock proposal and nothing else (P14-TSK-005)';
        END IF;
        NEW.unlock_proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'LOCKED' AND NEW.status = 'OPEN' THEN
        IF OLD.unlock_proposed_by IS NULL OR NEW.unlock_approved_by IS NULL
                OR NEW.unlock_proposed_by IS DISTINCT FROM OLD.unlock_proposed_by
                OR NEW.unlock_proposed_by_type IS DISTINCT FROM OLD.unlock_proposed_by_type
                OR NEW.unlock_proposed_at IS DISTINCT FROM OLD.unlock_proposed_at
                OR NEW.unlock_proposal_reason IS DISTINCT FROM OLD.unlock_proposal_reason THEN
            RAISE EXCEPTION 'a period unlocks only by the approval of its pending unlock proposal (P14-TSK-005)';
        END IF;
        NEW.unlock_approved_at := clock_timestamp();
        RETURN NEW;
    END IF;
    -- THE CLOSE (P14-TSK-012): the lock and its pin stand through CLOSE_PROPOSED and CLOSED - the lock instance a FINAL run reproduces under.
    IF (OLD.status = 'LOCKED' AND NEW.status = 'CLOSE_PROPOSED') OR (OLD.status = 'CLOSE_PROPOSED' AND NEW.status IN ('CLOSED', 'LOCKED')) THEN
        IF NEW.locked_by IS DISTINCT FROM OLD.locked_by OR NEW.locked_by_type IS DISTINCT FROM OLD.locked_by_type
                OR NEW.locked_at IS DISTINCT FROM OLD.locked_at
                OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id
                OR OLD.unlock_proposed_by IS NOT NULL OR NEW.unlock_proposed_by IS NOT NULL THEN
            RAISE EXCEPTION 'the close moves a lock with no unlock pending, and leaves the lock and its pin as they were (P14-TSK-012)';
        END IF;
        IF OLD.status = 'LOCKED' THEN
            -- PROPOSED: under this lock, at this revision, with its nine checks recorded (INV-ACC-09).
            IF NOT EXISTS (SELECT 1 FROM accounting.period_close c WHERE c.period_id = OLD.id AND c.status = 'PROPOSED'
                    AND c.period_locked_at = OLD.locked_at AND c.period_revision = OLD.revision
                    AND (SELECT count(*) FROM accounting.period_close_check f WHERE f.close_id = c.id) = 9) THEN
                RAISE EXCEPTION 'a period is CLOSE_PROPOSED only by a proposal under its present lock with its nine checks (P14-TSK-012, INV-ACC-09)';
            END IF;
        ELSIF NEW.status = 'CLOSED' THEN
            -- APPROVED: FOUR EYES on the row beneath this edge (INV-ACC-09, INV-AUD-04).
            IF NOT EXISTS (SELECT 1 FROM accounting.period_close c WHERE c.period_id = OLD.id AND c.status = 'APPROVED'
                    AND c.period_locked_at = OLD.locked_at AND c.period_revision = OLD.revision
                    AND c.decided_by <> c.proposed_by) THEN
                RAISE EXCEPTION 'a period is CLOSED only by a second person''s approval of its proposal (P14-TSK-012, INV-ACC-09, INV-AUD-04)';
            END IF;
        ELSE
            IF EXISTS (SELECT 1 FROM accounting.period_close c WHERE c.period_id = OLD.id AND c.status = 'PROPOSED') THEN
                RAISE EXCEPTION 'a period returns to LOCKED only with its proposal rejected (P14-TSK-012)';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    -- THE REOPEN (P14-TSK-012, INV-ACC-10): revision + 1, the lock and pin released, a second person's approval; the latest closed period only.
    IF OLD.status = 'CLOSED' AND NEW.status = 'OPEN' THEN
        IF NEW.revision IS DISTINCT FROM OLD.revision + 1 THEN
            RAISE EXCEPTION 'a reopen moves the revision by one (P14-TSK-012, INV-ACC-10)';
        END IF;
        IF NEW.locked_by IS NOT NULL OR NEW.locked_by_type IS NOT NULL OR NEW.locked_at IS NOT NULL
                OR NEW.pinned_mapping_version_id IS NOT NULL OR NEW.unlock_proposed_by IS NOT NULL OR NEW.unlock_approved_by IS NOT NULL THEN
            RAISE EXCEPTION 'a reopen releases the lock and its pin, and records nothing else on the period (P14-TSK-012)';
        END IF;
        -- FOR SHARE, as approveReopen already holds it on the domain's path, oldest first.
        SELECT p.status INTO next_status FROM accounting.accounting_period p
         WHERE p.period_start = (OLD.period_start + interval '1 month')::date FOR SHARE;
        IF FOUND AND next_status <> 'OPEN' THEN
            RAISE EXCEPTION 'only the latest closed period reopens, the next not locked: the period after % is % (P14-TSK-012, INV-ACC-10)', OLD.period_start, next_status;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM accounting.period_reopen r WHERE r.period_id = OLD.id AND r.period_revision = OLD.revision
                AND r.status = 'APPROVED' AND r.approved_by <> r.proposed_by) THEN
            RAISE EXCEPTION 'a period reopens only by a second person''s approval of its proposal (P14-TSK-012, INV-ACC-10, INV-AUD-04)';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an accounting period moves OPEN -> LOCKED -> CLOSE_PROPOSED -> CLOSED, CLOSE_PROPOSED -> LOCKED, LOCKED -> OPEN and CLOSED -> OPEN (each four eyes but the lock, the proposal and the rejection); % -> % is not an edge (P14-TSK-012)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The history: the new acts, each on its one edge, four eyes on the close and the reopen too.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE accounting.period_event
    DROP CONSTRAINT period_event_act_is_an_edge,
    -- AccountingPeriodStore.Act, each on its one edge.
    ADD CONSTRAINT period_event_act_is_an_edge CHECK (
        (act = 'OPENED' AND from_status IS NULL AND to_status = 'OPEN')
        OR (act = 'LOCKED' AND from_status = 'OPEN' AND to_status = 'LOCKED')
        OR (act = 'UNLOCK_PROPOSED' AND from_status = 'LOCKED' AND to_status = 'LOCKED')
        OR (act = 'UNLOCKED' AND from_status = 'LOCKED' AND to_status = 'OPEN')
        OR (act = 'CLOSE_PROPOSED' AND from_status = 'LOCKED' AND to_status = 'CLOSE_PROPOSED')
        OR (act = 'CLOSED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'CLOSED')
        OR (act = 'CLOSE_REJECTED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'LOCKED')
        OR (act = 'REOPEN_PROPOSED' AND from_status = 'CLOSED' AND to_status = 'CLOSED')
        OR (act = 'REOPENED' AND from_status = 'CLOSED' AND to_status = 'OPEN')),
    DROP CONSTRAINT period_event_four_eyes,
    -- FOUR EYES on the history too: the unlock, the close and the reopen name their proposer, never the approver.
    ADD CONSTRAINT period_event_four_eyes CHECK (
        (act IN ('UNLOCKED', 'CLOSED', 'REOPENED')) = (proposed_by IS NOT NULL) AND (proposed_by IS NULL OR proposed_by <> actor)),
    DROP CONSTRAINT period_event_reasoned,
    ADD CONSTRAINT period_event_reasoned CHECK (
        (act NOT IN ('UNLOCK_PROPOSED', 'UNLOCKED', 'CLOSE_PROPOSED', 'CLOSED', 'CLOSE_REJECTED', 'REOPEN_PROPOSED', 'REOPENED') OR reason IS NOT NULL)
        AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000));

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine rows' moving columns only; the record INSERT-only; no DELETE for any role. V003's column grant did not hold the
-- revision, frozen until now.
-- ---------------------------------------------------------------------------------------------
GRANT UPDATE (revision) ON accounting.accounting_period TO finapp_app;
GRANT SELECT, INSERT ON accounting.period_close TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason) ON accounting.period_close TO finapp_app;
GRANT SELECT, INSERT ON accounting.period_close_check TO finapp_app;
GRANT SELECT, INSERT ON accounting.period_reopen TO finapp_app;
GRANT UPDATE (status, approved_by, approved_by_type, approved_at, approval_reason) ON accounting.period_reopen TO finapp_app;
