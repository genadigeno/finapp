-- Accounting's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=each body is its latest definition byte for byte but for pg_catalog. before each clock call
--
-- WHY
--   A function of this schema is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts
--   a schema of its own before pg_catalog resolves statement_timestamp() to that schema's function, so what a
--   judgement reads as now could be moved without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   18 function(s), 50 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V002, V003, V004,
--   V005, V006, V007, V008, V009, V010, V011, the last definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and
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

-- accounting.accounting_break_permits_only_machine_edges: V008's definition, 4 clock call(s) qualified.
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
        NEW.raised_at := pg_catalog.clock_timestamp();
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
        NEW.ack_proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'ACKNOWLEDGED' THEN
        IF OLD.ack_proposed_by IS NULL OR NEW.ack_approved_by IS NULL OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'a break is acknowledged only by the approval of its pending proposal (P14-TSK-010, INV-AUD-04)';
        END IF;
        NEW.ack_approved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status IN ('OPEN', 'ACKNOWLEDGED') AND NEW.status = 'RESOLVED' THEN
        IF NEW.resolved_by IS NULL OR NEW.resolving_entry_id IS NULL
                OR NEW.ack_approved_by IS DISTINCT FROM OLD.ack_approved_by
                OR (OLD.ack_proposed_by IS NULL AND NEW.ack_proposed_by IS NOT NULL) THEN
            RAISE EXCEPTION 'a break is resolved only by reference to its correcting entry, and nothing else moves (P14-TSK-010)';
        END IF;
        NEW.resolved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an accounting break moves OPEN -> ACKNOWLEDGED (four eyes), OPEN | ACKNOWLEDGED -> RESOLVED; % -> % is not an edge (P14-TSK-010)', OLD.status, NEW.status;
END;
$$;

-- accounting.accounting_period_permits_only_machine_edges: V011's definition, 4 clock call(s) qualified.
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
        NEW.opened_at := pg_catalog.clock_timestamp();
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
        NEW.locked_at := pg_catalog.clock_timestamp();
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
        NEW.unlock_proposed_at := pg_catalog.clock_timestamp();
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
        NEW.unlock_approved_at := pg_catalog.clock_timestamp();
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

-- accounting.break_event_is_append_only: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.break_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accounting break''s case file is append-only (P14-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.close_check_permits_only_machine_edges: V007's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.close_check_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.holder IS NOT NULL OR NEW.reclaims <> 0 OR NEW.claimed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a close check attempt is born PENDING and unclaimed (P14-TSK-011)';
        END IF;
        IF NEW.attempt <> 1 + COALESCE((SELECT max(attempt) FROM accounting.close_check
                WHERE close_run_id = NEW.close_run_id AND check_code = NEW.check_code), 0) THEN
            RAISE EXCEPTION 'a close check attempt follows the last, with no gap (P14-TSK-011)';
        END IF;
        NEW.born_at := pg_catalog.clock_timestamp();
        NEW.lease_until := NEW.born_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a close check attempt is never deleted (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a concluded close check attempt is final - a re-run is a new attempt (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.close_run_id IS DISTINCT FROM OLD.close_run_id
            OR NEW.check_code IS DISTINCT FROM OLD.check_code OR NEW.attempt IS DISTINCT FROM OLD.attempt
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.request_reason IS DISTINCT FROM OLD.request_reason OR NEW.born_at IS DISTINCT FROM OLD.born_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a close check attempt''s request is frozen from its birth (P14-TSK-011)';
    END IF;
    IF NEW.reclaims <> OLD.reclaims AND NEW.reclaims <> OLD.reclaims + 1 THEN
        RAISE EXCEPTION 'a close check attempt is reclaimed one claim at a time (P14-TSK-011)';
    END IF;
    IF NEW.status = 'PENDING' THEN
        -- A claim, a reclaim, a renewal or a deferral: the lease's columns and nothing else.
        IF NEW.judged_by IS NOT NULL OR NEW.failures IS NOT NULL OR NEW.evidence IS NOT NULL
                OR NEW.concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a PENDING close check attempt records no outcome (P14-TSK-011)';
        END IF;
        -- A live claim is its holder's: anyone else takes the row only once its lease has passed on this clock.
        IF NEW.holder IS NOT NULL AND NEW.holder IS DISTINCT FROM OLD.holder AND OLD.lease_until > pg_catalog.clock_timestamp() THEN
            RAISE EXCEPTION 'a close check attempt is claimed only once its lease has passed (P14-TSK-011)';
        END IF;
        -- reclaims counts replaced holders, exactly: not a first claim, a renewal or a deferral.
        IF (NEW.reclaims = OLD.reclaims + 1) <> (OLD.holder IS NOT NULL AND NEW.holder IS NOT NULL AND NEW.holder <> OLD.holder) THEN
            RAISE EXCEPTION 'a lapsed holder is replaced with reclaims + 1, and nothing else moves reclaims (P14-TSK-011)';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status IN ('PASSED', 'FAILED', 'DISCLOSED') THEN
        IF OLD.holder IS NULL OR NEW.holder IS DISTINCT FROM OLD.holder OR NEW.reclaims <> OLD.reclaims THEN
            RAISE EXCEPTION 'a close check attempt is concluded by the claim that holds it (P14-TSK-011)';
        END IF;
        NEW.concluded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a close check moves PENDING -> PASSED | FAILED | DISCLOSED; % -> % is not an edge (P14-TSK-011)', OLD.status, NEW.status;
END;
$$;

-- accounting.close_run_permits_only_machine_edges: V007's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.close_run_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status    text;
    held_locked_at timestamptz;
    held_revision  integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT status, locked_at, revision INTO held_status, held_locked_at, held_revision
            FROM accounting.accounting_period WHERE id = NEW.period_id FOR SHARE;
        IF NOT FOUND OR held_status <> 'LOCKED' OR held_locked_at IS DISTINCT FROM NEW.period_locked_at
                OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a close run is born under its period''s present lock (P14-TSK-011, INV-ACC-09)';
        END IF;
        IF NEW.checks_concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a close run is born with nothing concluded (P14-TSK-011)';
        END IF;
        NEW.started_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a close run is never deleted (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.period_locked_at IS DISTINCT FROM OLD.period_locked_at
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision
            OR NEW.started_at IS DISTINCT FROM OLD.started_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a close run is frozen from its birth (P14-TSK-011)';
    END IF;
    IF OLD.checks_concluded_at IS NOT NULL OR NEW.checks_concluded_at IS NULL THEN
        RAISE EXCEPTION 'a close run records its checks concluded once (P14-TSK-011)';
    END IF;
    NEW.checks_concluded_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.gl_mapping_version_event_is_append_only: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a GL mapping version event is append-only (P14-TSK-003, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.gl_mapping_version_permits_only_machine_edges: V003's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_to_period IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a GL mapping version is born PROPOSED and undecided: no version is activated by insert (P14-TSK-003)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a GL mapping version is never deleted (P14-TSK-003, INV-ACC-07)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.effective_from_period IS DISTINCT FROM OLD.effective_from_period
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a GL mapping version''s identity is frozen (P14-TSK-003, INV-ACC-07)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a GL mapping version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P14-TSK-003)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        IF NEW.effective_to_period IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a decision records no end: only a retirement does (P14-TSK-003)';
        END IF;
        -- NEVER RETROACTIVE (INV-ACC-07): an activation takes effect no earlier than any version already activated.
        IF NEW.status = 'ACTIVE' AND EXISTS (
                SELECT 1 FROM accounting.gl_mapping_version v
                 WHERE v.id <> NEW.id AND v.status IN ('ACTIVE', 'RETIRED')
                   AND v.effective_from_period > NEW.effective_from_period) THEN
            RAISE EXCEPTION 'a GL mapping version takes effect no earlier than a version already in force: % is retroactive (P14-TSK-003, INV-ACC-07)', NEW.effective_from_period;
        END IF;
        -- NEVER INTO A PINNED PERIOD (P14-TSK-005, INV-ACC-07): no period at or after the effective month is past OPEN.
        IF NEW.status = 'ACTIVE' AND EXISTS (
                SELECT 1 FROM accounting.accounting_period p
                 WHERE p.period_start >= NEW.effective_from_period AND p.status <> 'OPEN') THEN
            RAISE EXCEPTION 'a GL mapping version takes effect only after every locked or closed period: % is retroactive (P14-TSK-005, INV-ACC-07)', NEW.effective_from_period;
        END IF;
        NEW.decided_at := pg_catalog.clock_timestamp();
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P14-TSK-003)';
        END IF;
        NEW.retired_at := pg_catalog.clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

-- accounting.period_close_permits_only_machine_edges: V008's definition, 2 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.clock_timestamp();
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
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.period_event_is_append_only: V003's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.period_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accounting period event is append-only (P14-TSK-005, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.period_reopen_permits_only_machine_edges: V011's definition, 3 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.clock_timestamp();
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
        NEW.approved_at := pg_catalog.clock_timestamp();
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
        NEW.ended_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a reopen proposal moves PROPOSED -> APPROVED | REJECTED | WITHDRAWN, once; % -> % is not an edge (P14-TSK-024)', OLD.status, NEW.status;
END;
$$;

-- accounting.period_unlock_end_is_the_pending_proposal: V011's definition, 1 clock call(s) qualified.
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
    NEW.ended_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.prior_period_adjustment_permits_only_machine_edges: V009's definition, 4 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.prior_period_adjustment_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_corrected_status   text;
    held_corrected_revision integer;
    held_corrected_end      date;
    held_target_status      text;
    held_target_revision    integer;
    held_target_start       date;
    held_target_end         date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a prior-period adjustment is never deleted (P14-TSK-013, INV-ACC-10)';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.corrected_period_id IS DISTINCT FROM OLD.corrected_period_id
                OR NEW.corrected_revision IS DISTINCT FROM OLD.corrected_revision
                OR NEW.target_period_id IS DISTINCT FROM OLD.target_period_id
                OR NEW.target_revision IS DISTINCT FROM OLD.target_revision
                OR NEW.posting_date IS DISTINCT FROM OLD.posting_date
                OR NEW.adjustment_proposal_id IS DISTINCT FROM OLD.adjustment_proposal_id
                OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
                OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
                OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
            RAISE EXCEPTION 'a prior-period adjustment is frozen but for its one decision: the approver approves what they read (P14-TSK-013, INV-AUD-04)';
        END IF;
        IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('POSTED', 'REJECTED')) THEN
            RAISE EXCEPTION 'a prior-period adjustment moves PROPOSED -> POSTED | REJECTED, once; % -> % is not an edge (P14-TSK-013)', OLD.status, NEW.status;
        END IF;
        IF NEW.status = 'REJECTED' THEN
            -- The way on for one that can no longer post: it asks nothing of either month.
            NEW.decided_at := pg_catalog.clock_timestamp();
            RETURN NEW;
        END IF;
    ELSE
        IF NEW.status <> 'PROPOSED' OR NEW.journal_entry_id IS NOT NULL OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a prior-period adjustment is born PROPOSED and undecided (P14-TSK-013)';
        END IF;
    END IF;
    -- A birth and a POST, oldest first: the corrected month CLOSED at the revision named, the target OPEN at the revision named - what can move between the two.
    SELECT p.status, p.revision, p.period_end
      INTO held_corrected_status, held_corrected_revision, held_corrected_end
      FROM accounting.accounting_period p WHERE p.id = NEW.corrected_period_id FOR SHARE;
    IF NOT FOUND OR held_corrected_status <> 'CLOSED' OR held_corrected_revision <> NEW.corrected_revision THEN
        RAISE EXCEPTION 'a prior-period adjustment corrects a CLOSED period at the revision it names (P14-TSK-013, INV-ACC-10)';
    END IF;
    SELECT p.status, p.revision, p.period_start, p.period_end
      INTO held_target_status, held_target_revision, held_target_start, held_target_end
      FROM accounting.accounting_period p WHERE p.id = NEW.target_period_id FOR SHARE;
    IF NOT FOUND OR held_target_status <> 'OPEN' THEN
        RAISE EXCEPTION 'a prior-period adjustment lands in an OPEN period (P14-TSK-013, INV-ACC-10)';
    END IF;
    -- NEVER INTO A MONTH REOPENED WHILE IT STOOD: OPEN again, its revision moved. Frozen, so a POST compares what the birth stored.
    IF held_target_revision <> NEW.target_revision THEN
        RAISE EXCEPTION 'a prior-period adjustment lands in its target at the revision it names: that month was reopened (P14-TSK-013, INV-ACC-10)';
    END IF;
    -- NEVER IN OR BEFORE THE MONTH IT CORRECTS, AND NEVER SOMEWHERE ELSE THAN THE MONTH IT NAMES. Frozen, so a POST judges the same dates.
    IF held_target_start <= held_corrected_end OR NEW.posting_date < held_target_start OR NEW.posting_date > held_target_end THEN
        RAISE EXCEPTION 'a prior-period adjustment is dated inside its OPEN target, after the period it corrects (P14-TSK-013, INV-ACC-10)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- NEVER BORN INTO A REOPENED MONTH: being re-closed, its figures would carry it, the re-dating INV-ACC-10 forbids.
        IF NEW.target_revision <> 1 THEN
            RAISE EXCEPTION 'a prior-period adjustment is born into a month never reopened (P14-TSK-013, INV-ACC-10)';
        END IF;
        -- NEVER THE FUTURE. A day's grace for an instance's clock across a midnight: the domain dates it, and the database only bounds that.
        IF NEW.posting_date > (pg_catalog.now() AT TIME ZONE 'UTC')::date + 1 THEN
            RAISE EXCEPTION 'a prior-period adjustment is not dated in the future (P14-TSK-013, INV-ACC-10)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
    ELSE
        NEW.decided_at := pg_catalog.clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

-- accounting.regulatory_obligation_permits_only_machine_edges: V010's definition, 3 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.clock_timestamp();
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
        NEW.decided_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P14-TSK-018)';
        END IF;
        NEW.retired_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory obligation moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P14-TSK-018)', OLD.status, NEW.status;
END;
$$;

-- accounting.regulatory_provider_answer_is_append_only: V010's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.regulatory_provider_answer_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a provider''s answer is kept verbatim and append-only (P14-TSK-018, INV-HIST-02)';
    END IF;
    NEW.received_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.regulatory_submission_event_is_append_only: V010's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.regulatory_submission_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a regulatory submission''s history is append-only (P14-TSK-018, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- accounting.regulatory_submission_permits_only_machine_edges: V010's definition, 8 clock call(s) qualified.
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
        NEW.prepared_at := pg_catalog.clock_timestamp();
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
        NEW.validated_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'VALIDATED' THEN
        IF OLD.validation_outcome IS NOT NULL OR NEW.validation_passed IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'a DRAFT is VALIDATED by one passing validation (P14-TSK-018)';
        END IF;
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.validated_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'VALIDATED' AND NEW.status = 'APPROVED' THEN
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.approved_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status = 'SUBMITTED' THEN
        IF NEW.answer_horizon_at IS NULL OR NEW.attempts <> 0 OR NEW.resends <> 0 OR NEW.last_answer IS NOT NULL THEN
            RAISE EXCEPTION 'SUBMITTED commits before any call: a horizon and no attempt (P14-TSK-018, INV-ACC-13)';
        END IF;
        PERFORM accounting.regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id);
        PERFORM accounting.regulatory_submission_lines_agree(NEW.id, NEW.content);
        NEW.submitted_at := pg_catalog.clock_timestamp();
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
        NEW.concluded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status = 'UNDELIVERED' THEN
        -- ON A DEFINITE "NOT RECEIVED", never over an unknown answer or a send in flight. It asks nothing of the month, which may well have moved: this is how a return that month overtook is ended, so a correction can go at the new revision.
        IF OLD.last_answer IS DISTINCT FROM 'NOT_RECEIVED' THEN
            RAISE EXCEPTION 'a return is concluded undelivered only on the provider''s definite not received (P14-TSK-018, INV-ACC-13)';
        END IF;
        NEW.closed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF (OLD.status IN ('DRAFT', 'VALIDATED', 'APPROVED') AND NEW.status = 'WITHDRAWN') OR (OLD.status IN ('VALIDATED', 'APPROVED') AND NEW.status = 'REFUSED') THEN
        -- A DOOR OUT asks nothing of the month: it is how a return overtaken by a reopen is ended.
        NEW.closed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a regulatory submission moves DRAFT -> VALIDATED -> APPROVED -> SUBMITTED -> ACKNOWLEDGED | REJECTED | UNDELIVERED, or out by a person''s door; % -> % is not an edge (P14-TSK-018, INV-ACC-13)', OLD.status, NEW.status;
END;
$$;

-- accounting.report_run_permits_only_machine_edges: V004's definition, 6 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.report_run_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    period_status   text;
    period_rev      integer;
    period_locked   timestamptz;
    period_pin      uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a report run is never deleted (P14-TSK-008, INV-ACC-04)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'GENERATING' OR NEW.attempt <> 1 OR NEW.content IS NOT NULL OR NEW.content_sha256 IS NOT NULL
                OR NEW.completed_at IS NOT NULL OR NEW.failure_code IS NOT NULL OR NEW.failed_at IS NOT NULL
                OR NEW.superseded_at IS NOT NULL OR NEW.superseded_correlation_id IS NOT NULL THEN
            RAISE EXCEPTION 'a report run is born GENERATING at attempt 1 with no content (P14-TSK-008)';
        END IF;
        -- The period FOR SHARE (A1): the claim already holds it; a writer that does not waits for a lock or an unlock.
        SELECT p.status, p.revision, p.locked_at, p.pinned_mapping_version_id
          INTO period_status, period_rev, period_locked, period_pin
          FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
        IF FOUND THEN
            IF NEW.period_revision <> period_rev THEN
                RAISE EXCEPTION 'a report run records its period''s revision (P14-TSK-008)';
            END IF;
            IF NEW.basis = 'FINAL' AND (period_status = 'OPEN' OR NEW.period_locked_at IS DISTINCT FROM period_locked
                    OR NEW.mapping_version_id IS DISTINCT FROM period_pin) THEN
                RAISE EXCEPTION 'a FINAL report run is born only for a period no longer OPEN, under its lock and the GL mapping version the lock pinned (P14-TSK-008, INV-ACC-04)';
            END IF;
            IF NEW.basis = 'PROVISIONAL' AND period_status <> 'OPEN' THEN
                RAISE EXCEPTION 'a PROVISIONAL report run is born only for an OPEN period (P14-TSK-008)';
            END IF;
        END IF;
        -- The cut is the claim's database clock.
        NEW.claimed_at := pg_catalog.clock_timestamp();
        NEW.cut_at := NEW.claimed_at;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.definition IS DISTINCT FROM OLD.definition
            OR NEW.definition_version IS DISTINCT FROM OLD.definition_version
            OR NEW.period_id IS DISTINCT FROM OLD.period_id OR NEW.revision IS DISTINCT FROM OLD.revision
            OR NEW.parameters IS DISTINCT FROM OLD.parameters OR NEW.parameters_hash IS DISTINCT FROM OLD.parameters_hash
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision OR NEW.basis IS DISTINCT FROM OLD.basis
            OR NEW.period_locked_at IS DISTINCT FROM OLD.period_locked_at
            OR NEW.mapping_version_id IS DISTINCT FROM OLD.mapping_version_id
            OR NEW.valuation_rate_set_id IS DISTINCT FROM OLD.valuation_rate_set_id
            OR NEW.cut_at IS DISTINCT FROM OLD.cut_at OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.claimed_at IS DISTINCT FROM OLD.claimed_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a report run''s question and inputs are frozen from its birth (P14-TSK-008, INV-ACC-04)';
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status = 'GENERATING' THEN
        -- THE RECLAIM: one attempt more, only past the lease, the new lease ahead - never a second run racing the first.
        IF NEW.attempt <> OLD.attempt + 1 OR OLD.lease_until > pg_catalog.clock_timestamp() OR NEW.lease_until <= pg_catalog.clock_timestamp()
                OR NEW.content IS NOT NULL OR NEW.failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'a GENERATING report run is reclaimed only past its lease, one attempt more (P14-TSK-008)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status IN ('FINAL', 'PROVISIONAL') THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until OR NEW.status <> NEW.basis THEN
            RAISE EXCEPTION 'a report run completes as its basis, at the attempt that holds it (P14-TSK-008)';
        END IF;
        IF NEW.basis = 'FINAL' THEN
            SELECT p.status, p.locked_at INTO period_status, period_locked
              FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
            IF period_status = 'OPEN' OR period_locked IS DISTINCT FROM NEW.period_locked_at THEN
                RAISE EXCEPTION 'a FINAL report run completes only while its period holds the lock it was claimed under (P14-TSK-008, INV-ACC-04)';
            END IF;
        END IF;
        -- THE CONTENT, checked at the one edge that sets it (the freeze keeps it so): bounded (ReportGeneration.
        -- MAX_CONTENT_BYTES) before it is parsed, a JSON object, and THE HASH THE CONTENT'S, for every writer (INV-ACC-04).
        IF octet_length(NEW.content) > 8388608 THEN
            RAISE EXCEPTION 'report_run_content_bounded: a report run''s content is at most 8 MiB (P14-TSK-008)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.content IS NOT NULL AND jsonb_typeof(NEW.content::jsonb) <> 'object' THEN
            RAISE EXCEPTION 'report_run_content_is_a_json_object: a report run''s content is a JSON object (P14-TSK-008)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.content IS NOT NULL AND NEW.content_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.content, 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'report_run_content_hashed: a report run''s content_sha256 is its content''s sha256 (P14-TSK-008, INV-ACC-04)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.completed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status = 'FAILED' THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
            RAISE EXCEPTION 'a report run fails at the attempt that holds it (P14-TSK-008)';
        END IF;
        NEW.failed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'FINAL' AND NEW.status = 'SUPERSEDED' THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
            RAISE EXCEPTION 'a FINAL report run is superseded and nothing else (P14-TSK-008)';
        END IF;
        NEW.superseded_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a report run moves GENERATING -> FINAL | PROVISIONAL | FAILED (GENERATING -> GENERATING past its lease), FINAL -> SUPERSEDED; % -> % is not an edge (P14-TSK-008)', OLD.status, NEW.status;
END;
$$;

-- accounting.valuation_rate_set_permits_only_machine_edges: V005's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
    held_end    date;
    held_rates  integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a valuation rate set is never deleted (P14-TSK-014, INV-ACC-11)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a valuation rate set is born PROPOSED and undecided: none is approved by insert (P14-TSK-014)';
        END IF;
        -- WHICH PERIOD: a LOCKED one - its end has passed and the ledger's cutoff stands.
        SELECT p.status, p.period_end INTO held_status, held_end FROM accounting.accounting_period p
         WHERE p.id = NEW.period_id;
        IF NOT FOUND OR held_status <> 'LOCKED' THEN
            RAISE EXCEPTION 'a valuation rate set is captured for a LOCKED accounting period (P14-TSK-014)';
        END IF;
        -- AS OF THE PERIOD'S END: the first instant of the next month, UTC.
        IF NEW.as_of IS DISTINCT FROM ((held_end + 1)::timestamp AT TIME ZONE 'UTC') THEN
            RAISE EXCEPTION 'a valuation rate set is as of its period''s end, the first instant after % UTC (P14-TSK-014)', held_end;
        END IF;
        NEW.captured_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.functional_currency IS DISTINCT FROM OLD.functional_currency OR NEW.as_of IS DISTINCT FROM OLD.as_of
            OR NEW.max_age_hours IS DISTINCT FROM OLD.max_age_hours OR NEW.rate_count IS DISTINCT FROM OLD.rate_count
            OR NEW.captured_by IS DISTINCT FROM OLD.captured_by OR NEW.captured_by_type IS DISTINCT FROM OLD.captured_by_type
            OR NEW.captured_at IS DISTINCT FROM OLD.captured_at OR NEW.capture_reason IS DISTINCT FROM OLD.capture_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a valuation rate set''s capture is frozen (P14-TSK-014, INV-ACC-11)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a valuation rate set moves PROPOSED -> APPROVED | REJECTED, once; % -> % is not an edge (P14-TSK-014)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT p.status INTO held_status FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
        IF held_status <> 'LOCKED' THEN
            RAISE EXCEPTION 'a valuation rate set is approved while its period is LOCKED, not % (P14-TSK-014)', held_status;
        END IF;
        SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.id;
        IF held_rates <> NEW.rate_count THEN
            RAISE EXCEPTION 'a valuation rate set is approved only whole: % of its % rates (P14-TSK-014)', held_rates, NEW.rate_count;
        END IF;
    END IF;
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;
