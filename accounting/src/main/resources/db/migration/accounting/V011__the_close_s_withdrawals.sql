-- The close's withdrawals: a pending unlock and a pending reopen ended without their effect (P14-TSK-024).
--
-- The owner's acceptance of P14-TSK-012's item (d), 2026-11-06; ADR-0114 (the close), INV-AUD-04, INV-ACC-07, INV-ACC-08, INV-ACC-10; PHASE_14_PLAN.md
-- sections 6 and 13.5. Until this, a pending unlock or reopen had one way on, its approval: the effect, performed to be rid of the request.
--
-- TWO ENDS, EACH ITS OWN TERMINAL STATE. REJECTED: a second person's decision against someone else's request, never its proposer's (four eyes, for every
-- writer). WITHDRAWN: the proposer's own retraction, nobody else's. Neither moves anything - no cutoff, no pin, no revision, no report run - and the month is
-- then free for a new request.
--
-- THE UNLOCK'S REQUEST LIVES ON THE PERIOD ROW (V003), and stays there. Its end is a row of its own, period_unlock_end, copied from the period row the
-- domain holds, so the request's identity - the lock instance and the instant it was proposed - is the row's own bytes and is never round-tripped through
-- the application; the row carries both persons, so the four-eyes CHECK can judge them. The row's proposal columns are cleared in the same transaction.
-- The period's edge function is replaced whole, V008's text with one branch added: the clearing is admitted only beside its end's row, the lock, its pin
-- and the approval's columns as they were.
--
-- THE REOPEN IS A ROW (V008): PROPOSED -> APPROVED | REJECTED | WITHDRAWN, once. V008's one-per-revision UNIQUE would leave an ended reopen holding its
-- revision for good, so it becomes one PROPOSED per period and one APPROVED per revision. Both hold for every row V008 admitted: a PROPOSED row could only
-- become APPROVED, and a revision moves only at that approval, so a period never held two PROPOSED rows nor two rows of one revision.
--
-- The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): an end takes (A1) the period FOR UPDATE and, for a reopen, (A4) the pending reopen row
-- FOR UPDATE - a subset of the approval's order, so it closes no cycle. The statements below hold no lock but the end trigger's period FOR SHARE, on the
-- row the domain's path already holds FOR UPDATE.
--
-- NO MONEY: no column here holds an amount. Nothing here posts. The migration names no other schema. No DELETE for any role; never truncated.

-- ---------------------------------------------------------------------------------------------
-- An unlock's end: one row per request ended.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.period_unlock_end (
    id               uuid        NOT NULL,
    period_id        uuid        NOT NULL,
    period_revision  integer     NOT NULL,
    period_locked_at timestamptz NOT NULL,
    proposed_by      text        NOT NULL,
    proposed_by_type text        NOT NULL,
    proposed_at      timestamptz NOT NULL,
    outcome          text        NOT NULL,
    ended_by         text        NOT NULL,
    ended_by_type    text        NOT NULL,
    ended_at         timestamptz NOT NULL,
    end_reason       text        NOT NULL,
    correlation_id   text        NOT NULL,
    CONSTRAINT period_unlock_end_pk PRIMARY KEY (id),
    CONSTRAINT period_unlock_end_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    -- ONE END PER REQUEST: the request is the proposal the period row held, under its lock instance, at its instant.
    CONSTRAINT period_unlock_end_once UNIQUE (period_id, period_locked_at, proposed_at),
    -- PeriodRequestEnd.
    CONSTRAINT period_unlock_end_outcome_is_known CHECK (outcome IN ('REJECTED', 'WITHDRAWN')),
    CONSTRAINT period_unlock_end_revision_positive CHECK (period_revision >= 1),
    -- Persons only: an authenticated human proposes and ends.
    CONSTRAINT period_unlock_end_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE') AND ended_by_type IN ('CUSTOMER', 'EMPLOYEE')),
    CONSTRAINT period_unlock_end_reasoned CHECK (char_length(end_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, for every writer (INV-AUD-04): a rejection is a second person's; a withdrawal its proposer's own.
    CONSTRAINT period_unlock_end_four_eyes CHECK (
        (outcome = 'REJECTED' AND ended_by <> proposed_by) OR (outcome = 'WITHDRAWN' AND ended_by = proposed_by))
);

COMMENT ON TABLE accounting.period_unlock_end IS
    'A pending unlock ended without its effect (P14-TSK-024, INV-AUD-04): REJECTED by a second person, or WITHDRAWN by its proposer. Copied from the LOCKED period row holding the proposal - its lock instance, revision, proposer and the instant it was proposed - in the transaction that clears the proposal from that row. The period stays LOCKED under the same lock and pin; the cutoff does not move. INSERT-only. No money.';

CREATE OR REPLACE FUNCTION accounting.period_unlock_end_is_the_pending_proposal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status   text;
    held_revision integer;
    held_locked   timestamptz;
    held_by       text;
    held_by_type  text;
    held_at       timestamptz;
    held_approver text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an unlock''s end is append-only (P14-TSK-024, INV-HIST-01)';
    END IF;
    -- THE PENDING PROPOSAL OF THE PRESENT LOCK, as the period row holds it now. FOR SHARE, as the domain's path already holds it FOR UPDATE.
    SELECT p.status, p.revision, p.locked_at, p.unlock_proposed_by, p.unlock_proposed_by_type, p.unlock_proposed_at, p.unlock_approved_by
      INTO held_status, held_revision, held_locked, held_by, held_by_type, held_at, held_approver
      FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
    IF NOT FOUND OR held_status <> 'LOCKED' OR held_by IS NULL OR held_revision <> NEW.period_revision OR held_locked IS DISTINCT FROM NEW.period_locked_at
            OR held_by IS DISTINCT FROM NEW.proposed_by OR held_by_type IS DISTINCT FROM NEW.proposed_by_type
            OR held_at IS DISTINCT FROM NEW.proposed_at OR held_approver IS NOT NULL THEN
        RAISE EXCEPTION 'an unlock''s end names the pending unlock of a LOCKED period under its present lock (P14-TSK-024)';
    END IF;
    NEW.ended_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER period_unlock_end_is_the_pending_proposal
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_unlock_end
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_unlock_end_is_the_pending_proposal();

CREATE TRIGGER period_unlock_end_is_never_truncated
    BEFORE TRUNCATE ON accounting.period_unlock_end
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- An end commits only with the proposal gone from the period row: no end stands beside a request still pending.
CREATE OR REPLACE FUNCTION accounting.period_unlock_end_commits_with_its_edge()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounting.accounting_period p WHERE p.id = NEW.period_id
            AND p.unlock_proposed_by = NEW.proposed_by AND p.unlock_proposed_at = NEW.proposed_at) THEN
        RAISE EXCEPTION 'an unlock''s end committed beside a period still holding the proposal it ended (P14-TSK-024)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER period_unlock_end_commits_with_its_edge
    AFTER INSERT ON accounting.period_unlock_end
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_unlock_end_commits_with_its_edge();

-- ---------------------------------------------------------------------------------------------
-- The period's machine, replaced whole: V008's edges as they were, an unlock's end added.
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
        -- AN UNLOCK'S END (P14-TSK-024): the pending proposal cleared whole and nothing else moved - the lock, its pin and the approval's columns as they
        -- were (INV-ACC-07, INV-ACC-08: an end moves no cutoff and no pin) - and only beside its end's row, which names this very proposal.
        IF OLD.unlock_proposed_by IS NOT NULL AND NEW.unlock_proposed_by IS NULL THEN
            IF NEW.unlock_proposed_by_type IS NOT NULL OR NEW.unlock_proposed_at IS NOT NULL OR NEW.unlock_proposal_reason IS NOT NULL
                    OR NEW.unlock_approved_by IS NOT NULL OR NEW.unlock_approved_by_type IS NOT NULL
                    OR NEW.unlock_approved_at IS NOT NULL OR NEW.unlock_approval_reason IS NOT NULL
                    OR NEW.locked_by IS DISTINCT FROM OLD.locked_by OR NEW.locked_by_type IS DISTINCT FROM OLD.locked_by_type
                    OR NEW.locked_at IS DISTINCT FROM OLD.locked_at
                    OR NEW.pinned_mapping_version_id IS DISTINCT FROM OLD.pinned_mapping_version_id THEN
                RAISE EXCEPTION 'a pending unlock is ended by clearing its proposal whole, and nothing else moves (P14-TSK-024)';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM accounting.period_unlock_end u WHERE u.period_id = OLD.id AND u.period_locked_at = OLD.locked_at
                    AND u.proposed_by = OLD.unlock_proposed_by AND u.proposed_at = OLD.unlock_proposed_at) THEN
                RAISE EXCEPTION 'a pending unlock is ended only beside the record of its end (P14-TSK-024, INV-AUD-04)';
            END IF;
            RETURN NEW;
        END IF;
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
-- The reopen: an end beside the approval, and the arbiters that leave an ended month free.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE accounting.period_reopen
    ADD COLUMN ended_by      text,
    ADD COLUMN ended_by_type text,
    ADD COLUMN ended_at      timestamptz,
    ADD COLUMN end_reason    text;

ALTER TABLE accounting.period_reopen
    -- V008's one per revision: an ended reopen would hold its revision for good. The two partial uniques below replace it.
    DROP CONSTRAINT period_reopen_one_per_revision,
    DROP CONSTRAINT period_reopen_status_is_known,
    -- PeriodReopenStatus.
    ADD CONSTRAINT period_reopen_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    DROP CONSTRAINT period_reopen_persons,
    ADD CONSTRAINT period_reopen_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (ended_by_type IS NULL OR ended_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- An end is whole and reasoned exactly when REJECTED or WITHDRAWN.
    ADD CONSTRAINT period_reopen_end_coherent CHECK (
        (status IN ('REJECTED', 'WITHDRAWN')) = (ended_by IS NOT NULL)
        AND (ended_by IS NULL) = (ended_by_type IS NULL)
        AND (ended_by IS NULL) = (ended_at IS NULL)
        AND (ended_by IS NULL) = (end_reason IS NULL)),
    ADD CONSTRAINT period_reopen_end_reasoned CHECK (end_reason IS NULL OR char_length(end_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, for every writer (INV-AUD-04): a rejection is a second person's; a withdrawal its proposer's own.
    ADD CONSTRAINT period_reopen_end_four_eyes CHECK (
        (status <> 'REJECTED' OR ended_by <> proposed_by) AND (status <> 'WITHDRAWN' OR ended_by = proposed_by));

COMMENT ON COLUMN accounting.period_reopen.ended_by IS
    'Who ended the reopen without its effect (P14-TSK-024): REJECTED by a second person, WITHDRAWN by its proposer. Its type, instant (the database''s clock) and reason beside it; the period stayed CLOSED at the proposal''s revision.';

-- One pending reopen per period and one approved per revision - the arbiters beneath the period row's lock. An ended reopen holds neither.
CREATE UNIQUE INDEX period_reopen_one_pending ON accounting.period_reopen (period_id) WHERE status = 'PROPOSED';
CREATE UNIQUE INDEX period_reopen_one_approved ON accounting.period_reopen (period_id, period_revision) WHERE status = 'APPROVED';
-- What the dropped UNIQUE's index served: a revision's reopens, ended ones among them.
CREATE INDEX period_reopen_by_revision ON accounting.period_reopen (period_id, period_revision);

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
                OR NEW.approved_at IS NOT NULL OR NEW.approval_reason IS NOT NULL
                OR NEW.ended_by IS NOT NULL OR NEW.ended_by_type IS NOT NULL OR NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL THEN
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
        RAISE EXCEPTION 'a reopen proposal is frozen but for its one decision (P14-TSK-012, INV-ACC-10)';
    END IF;
    IF OLD.status = 'PROPOSED' AND NEW.status = 'APPROVED' THEN
        IF NEW.ended_by IS NOT NULL OR NEW.ended_by_type IS NOT NULL OR NEW.ended_at IS NOT NULL OR NEW.end_reason IS NOT NULL THEN
            RAISE EXCEPTION 'an approved reopen records no end (P14-TSK-024)';
        END IF;
        SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
        IF held_status <> 'CLOSED' OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a reopen is approved while its period is CLOSED at the proposal''s revision (P14-TSK-012, INV-ACC-10)';
        END IF;
        NEW.approved_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'PROPOSED' AND NEW.status IN ('REJECTED', 'WITHDRAWN') THEN
        -- AN END (P14-TSK-024): this row and nothing else; the period stays CLOSED at the proposal's revision, so nothing is superseded and no cutoff moves.
        IF NEW.approved_by IS NOT NULL OR NEW.approved_by_type IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approval_reason IS NOT NULL THEN
            RAISE EXCEPTION 'an ended reopen records no approval (P14-TSK-024)';
        END IF;
        SELECT p.status, p.revision INTO held_status, held_revision FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
        IF held_status <> 'CLOSED' OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a reopen is ended while its period is CLOSED at the proposal''s revision (P14-TSK-024)';
        END IF;
        NEW.ended_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a reopen proposal moves PROPOSED -> APPROVED | REJECTED | WITHDRAWN, once; % -> % is not an edge (P14-TSK-024)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The history: the four ends, each on its one edge; four eyes on a rejection, the proposer's own hand on a withdrawal.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE accounting.period_event
    DROP CONSTRAINT period_event_act_is_an_edge,
    -- AccountingPeriodStore.Act, each on its one edge.
    ADD CONSTRAINT period_event_act_is_an_edge CHECK (
        (act = 'OPENED' AND from_status IS NULL AND to_status = 'OPEN')
        OR (act = 'LOCKED' AND from_status = 'OPEN' AND to_status = 'LOCKED')
        OR (act = 'UNLOCK_PROPOSED' AND from_status = 'LOCKED' AND to_status = 'LOCKED')
        OR (act = 'UNLOCKED' AND from_status = 'LOCKED' AND to_status = 'OPEN')
        OR (act = 'UNLOCK_REJECTED' AND from_status = 'LOCKED' AND to_status = 'LOCKED')
        OR (act = 'UNLOCK_WITHDRAWN' AND from_status = 'LOCKED' AND to_status = 'LOCKED')
        OR (act = 'CLOSE_PROPOSED' AND from_status = 'LOCKED' AND to_status = 'CLOSE_PROPOSED')
        OR (act = 'CLOSED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'CLOSED')
        OR (act = 'CLOSE_REJECTED' AND from_status = 'CLOSE_PROPOSED' AND to_status = 'LOCKED')
        OR (act = 'REOPEN_PROPOSED' AND from_status = 'CLOSED' AND to_status = 'CLOSED')
        OR (act = 'REOPENED' AND from_status = 'CLOSED' AND to_status = 'OPEN')
        OR (act = 'REOPEN_REJECTED' AND from_status = 'CLOSED' AND to_status = 'CLOSED')
        OR (act = 'REOPEN_WITHDRAWN' AND from_status = 'CLOSED' AND to_status = 'CLOSED')),
    DROP CONSTRAINT period_event_four_eyes,
    -- FOUR EYES on the history too: an effect and a rejection name their proposer, never the actor; a withdrawal names its proposer, who is the actor;
    -- every other act names none.
    ADD CONSTRAINT period_event_four_eyes CHECK (
        CASE
            WHEN act IN ('UNLOCKED', 'CLOSED', 'REOPENED', 'UNLOCK_REJECTED', 'REOPEN_REJECTED') THEN proposed_by IS NOT NULL AND proposed_by <> actor
            WHEN act IN ('UNLOCK_WITHDRAWN', 'REOPEN_WITHDRAWN') THEN proposed_by IS NOT NULL AND proposed_by = actor
            ELSE proposed_by IS NULL
        END),
    DROP CONSTRAINT period_event_reasoned,
    ADD CONSTRAINT period_event_reasoned CHECK (
        (act NOT IN ('UNLOCK_PROPOSED', 'UNLOCKED', 'CLOSE_PROPOSED', 'CLOSED', 'CLOSE_REJECTED', 'REOPEN_PROPOSED', 'REOPENED',
                'UNLOCK_REJECTED', 'UNLOCK_WITHDRAWN', 'REOPEN_REJECTED', 'REOPEN_WITHDRAWN') OR reason IS NOT NULL)
        AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000));

-- ---------------------------------------------------------------------------------------------
-- The grants: the end's row INSERT-only; the reopen's end columns; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.period_unlock_end TO finapp_app;
GRANT UPDATE (ended_by, ended_by_type, ended_at, end_reason) ON accounting.period_reopen TO finapp_app;
