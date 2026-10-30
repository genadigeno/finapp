-- Accounting periods: open, lock, unlock (P14-TSK-005).
--
-- ADR-0114 decision 3, INV-ACC-03, INV-ACC-07, INV-ACC-08, INV-AUD-04; PHASE_14_PLAN.md sections 6, 8.1 (T-lock,
-- T-unlock), 8.2, 8.3 rows 2 and 8, 13.5 and owner decision C9 (recommended default A, awaiting ratification): a period
-- is a calendar month of `posting_date`; periods are contiguous and lock in order; the lock advances the ledger's posting
-- cutoff to the period end (through the ledger's own PostingCutoffs API, in the same transaction - never a write here) and
-- pins the GL mapping version in force for the month; an unlock is four-eyes and moves the cutoff back one period.
--
-- NO MONEY: no column here holds an amount, a balance or a line. NO SEED: the first period is born by the system's open
-- (`AccountingPeriods.openNext`, scheduled by P14-TSK-011's AccountingCloseSchedule), never by a migration.
--
-- Built edges: birth OPEN (revision 1), OPEN -> LOCKED, LOCKED + an unlock proposal, LOCKED -> OPEN (the approval). The
-- close, its rejection and the reopen (LOCKED -> CLOSE_PROPOSED -> CLOSED, CLOSE_PROPOSED -> LOCKED, CLOSED -> OPEN with
-- revision + 1) are P14-TSK-012's, which replaces the edge trigger and the event's act list.
--
-- The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 lock order): (A1) the period rows, oldest first - the
-- previous period FOR SHARE, then the period FOR UPDATE; (A2) namespace 14 on hashtext('mapping') SHARED for the pin read
-- (an activation takes it exclusive), then hashtext('cutoff') exclusive inside the ledger's move; (A3) the ledger's
-- posting_cutoff row. The statements below hold no lock; the CHECKs, the partial unique and the triggers are the
-- backstops for every writer, the migrator included.

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.accounting_period (
    id                        uuid        NOT NULL,
    period_start              date        NOT NULL,
    period_end                date        NOT NULL,
    status                    text        NOT NULL,
    revision                  integer     NOT NULL,
    opened_at                 timestamptz NOT NULL,
    correlation_id            text        NOT NULL,
    locked_by                 text,
    locked_by_type            text,
    locked_at                 timestamptz,
    pinned_mapping_version_id uuid,
    unlock_proposed_by        text,
    unlock_proposed_by_type   text,
    unlock_proposed_at        timestamptz,
    unlock_proposal_reason    text,
    unlock_approved_by        text,
    unlock_approved_by_type   text,
    unlock_approved_at        timestamptz,
    unlock_approval_reason    text,
    CONSTRAINT accounting_period_pk PRIMARY KEY (id),
    -- One period per month (C9) - the backstop beneath the open's insert-if-absent.
    CONSTRAINT accounting_period_one_per_month UNIQUE (period_start),
    CONSTRAINT accounting_period_pinned_version_fk FOREIGN KEY (pinned_mapping_version_id)
        REFERENCES accounting.gl_mapping_version (id),
    -- PeriodStatus.
    CONSTRAINT accounting_period_status_is_known CHECK (status IN ('OPEN', 'LOCKED', 'CLOSE_PROPOSED', 'CLOSED')),
    -- A calendar month: the first day to the last.
    CONSTRAINT accounting_period_is_a_calendar_month CHECK (
        EXTRACT(DAY FROM period_start) = 1
        AND period_end = (period_start + interval '1 month')::date - 1),
    CONSTRAINT accounting_period_revision_positive CHECK (revision >= 1),
    -- Persons only: an authenticated human locks, proposes and approves - never the platform, a service or a merchant.
    CONSTRAINT accounting_period_persons CHECK (
        (locked_by_type IS NULL OR locked_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (unlock_proposed_by_type IS NULL OR unlock_proposed_by_type IN ('CUSTOMER', 'EMPLOYEE'))
        AND (unlock_approved_by_type IS NULL OR unlock_approved_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- The lock is recorded exactly while the period is not OPEN; the pin only then (none when no version was in force).
    CONSTRAINT accounting_period_lock_coherent CHECK (
        (status = 'OPEN') = (locked_by IS NULL)
        AND (locked_by IS NULL) = (locked_by_type IS NULL)
        AND (locked_by IS NULL) = (locked_at IS NULL)
        AND (status <> 'OPEN' OR pinned_mapping_version_id IS NULL)),
    -- An unlock proposal is whole and pending only while LOCKED; its approval is whole, only of a proposal, and leaves the
    -- period OPEN.
    CONSTRAINT accounting_period_unlock_coherent CHECK (
        (unlock_proposed_by IS NULL) = (unlock_proposed_by_type IS NULL)
        AND (unlock_proposed_by IS NULL) = (unlock_proposed_at IS NULL)
        AND (unlock_proposed_by IS NULL) = (unlock_proposal_reason IS NULL)
        AND (unlock_approved_by IS NULL) = (unlock_approved_by_type IS NULL)
        AND (unlock_approved_by IS NULL) = (unlock_approved_at IS NULL)
        AND (unlock_approved_by IS NULL) = (unlock_approval_reason IS NULL)
        AND (unlock_approved_by IS NULL OR (unlock_proposed_by IS NOT NULL AND status = 'OPEN'))
        AND (unlock_proposed_by IS NULL OR unlock_approved_by IS NOT NULL OR status = 'LOCKED')),
    CONSTRAINT accounting_period_unlock_reasoned CHECK (
        (unlock_proposal_reason IS NULL OR char_length(unlock_proposal_reason) BETWEEN 1 AND 1000)
        AND (unlock_approval_reason IS NULL OR char_length(unlock_approval_reason) BETWEEN 1 AND 1000)),
    -- FOUR EYES, for every writer (INV-AUD-04): whoever approved an unlock did not propose it.
    CONSTRAINT accounting_period_unlock_four_eyes CHECK (
        unlock_approved_by IS NULL OR unlock_approved_by <> unlock_proposed_by)
);

COMMENT ON TABLE accounting.accounting_period IS
    'An accounting period''s machine (P14-TSK-005, ADR-0114 decision 3, C9): a calendar month of posting_date, contiguous, one per month; born OPEN by the system''s open; OPEN -> LOCKED (the lock: the ledger cutoff advanced to period_end and the GL mapping version in force pinned, in the same transaction; in order - the previous period CLOSED); LOCKED -> OPEN only by a pending unlock proposal''s approval by a second person (the cutoff moved back one period). P14-TSK-012 adds the close and the reopen. No money.';

-- At most one period LOCKED or CLOSE_PROPOSED at a time - the backstop beneath "in order".
CREATE UNIQUE INDEX accounting_period_one_in_close ON accounting.accounting_period ((status IN ('LOCKED', 'CLOSE_PROPOSED')))
    WHERE status IN ('LOCKED', 'CLOSE_PROPOSED');

-- The edge trigger: every stamp clock_timestamp(), the instant written after the lock the act waited on.
CREATE OR REPLACE FUNCTION accounting.accounting_period_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    first_start     date;
    latest_start    date;
    previous_status text;
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
            OR NEW.period_end IS DISTINCT FROM OLD.period_end OR NEW.revision IS DISTINCT FROM OLD.revision
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an accounting period''s identity and revision are frozen (P14-TSK-005)';
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'LOCKED' THEN
        -- IN ORDER: the previous period, when there is one, is CLOSED.
        SELECT p.status INTO previous_status FROM accounting.accounting_period p
         WHERE p.period_start = (OLD.period_start - interval '1 month')::date;
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
    RAISE EXCEPTION 'an accounting period moves OPEN -> LOCKED, LOCKED -> OPEN (four eyes); % -> % is not an edge built by P14-TSK-005', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER accounting_period_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.accounting_period
    FOR EACH ROW
    EXECUTE FUNCTION accounting.accounting_period_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.accounting_period_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the accounting periods and their history are never truncated (P14-TSK-005, INV-HIST-01)';
END;
$$;

CREATE TRIGGER accounting_period_is_never_truncated
    BEFORE TRUNCATE ON accounting.accounting_period
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The history: every edge and the unlock proposal, append-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.period_event (
    id                        uuid        NOT NULL,
    period_id                 uuid        NOT NULL,
    act                       text        NOT NULL,
    from_status               text,
    to_status                 text        NOT NULL,
    revision                  integer     NOT NULL,
    actor                     text        NOT NULL,
    actor_type                text        NOT NULL,
    proposed_by               text,
    pinned_mapping_version_id uuid,
    reason                    text,
    occurred_at               timestamptz NOT NULL,
    correlation_id            text        NOT NULL,
    CONSTRAINT period_event_pk PRIMARY KEY (id),
    CONSTRAINT period_event_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    CONSTRAINT period_event_pinned_version_fk FOREIGN KEY (pinned_mapping_version_id)
        REFERENCES accounting.gl_mapping_version (id),
    -- AccountingPeriodStore.Act, each on its one edge.
    CONSTRAINT period_event_act_is_an_edge CHECK (
        (act = 'OPENED' AND from_status IS NULL AND to_status = 'OPEN')
        OR (act = 'LOCKED' AND from_status = 'OPEN' AND to_status = 'LOCKED')
        OR (act = 'UNLOCK_PROPOSED' AND from_status = 'LOCKED' AND to_status = 'LOCKED')
        OR (act = 'UNLOCKED' AND from_status = 'LOCKED' AND to_status = 'OPEN')),
    -- The open is the system's; every other act a person's.
    CONSTRAINT period_event_actor_kind CHECK (
        actor_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE') AND (act = 'OPENED' OR actor_type IN ('CUSTOMER', 'EMPLOYEE'))),
    -- FOUR EYES on the history too: the unlock names its proposer, never the approver.
    CONSTRAINT period_event_four_eyes CHECK (
        (act = 'UNLOCKED') = (proposed_by IS NOT NULL) AND (proposed_by IS NULL OR proposed_by <> actor)),
    CONSTRAINT period_event_pin_only_at_lock CHECK (act = 'LOCKED' OR pinned_mapping_version_id IS NULL),
    CONSTRAINT period_event_reasoned CHECK (
        (act NOT IN ('UNLOCK_PROPOSED', 'UNLOCKED') OR reason IS NOT NULL)
        AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 1000)),
    CONSTRAINT period_event_revision_positive CHECK (revision >= 1)
);

CREATE INDEX period_event_by_period ON accounting.period_event (period_id, occurred_at);

COMMENT ON TABLE accounting.period_event IS
    'An accounting period''s machine as history (P14-TSK-005): the open (the system''s), every lock with its pinned GL mapping version, every unlock proposal and every unlock with its proposer, each with its actor, reason (RESTRICTED-PII, screened by AccountingReasons) and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION accounting.period_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accounting period event is append-only (P14-TSK-005, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER period_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.period_event
    FOR EACH ROW
    EXECUTE FUNCTION accounting.period_event_is_append_only();

CREATE TRIGGER period_event_is_never_truncated
    BEFORE TRUNCATE ON accounting.period_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.accounting_period_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The GL mapping's activation re-read against the periods (P14-TSK-003's "P14-TSK-005 adds that the month is OPEN"):
-- V002's edge function, replaced whole, with one rule added - an activation is refused when a period at or after its
-- effective month is no longer OPEN, its mapping already pinned (INV-ACC-07). The domain re-reads the same under
-- namespace 14 key 'mapping' exclusive, which the lock's pin read takes shared.
-- ---------------------------------------------------------------------------------------------
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
        NEW.proposed_at := clock_timestamp();
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
        NEW.decided_at := clock_timestamp();
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P14-TSK-003)';
        END IF;
        NEW.retired_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's act columns only; the history INSERT-only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.accounting_period TO finapp_app;
GRANT UPDATE (status, locked_by, locked_by_type, locked_at, pinned_mapping_version_id, unlock_proposed_by, unlock_proposed_by_type, unlock_proposed_at, unlock_proposal_reason, unlock_approved_by, unlock_approved_by_type, unlock_approved_at, unlock_approval_reason)
    ON accounting.accounting_period TO finapp_app;
GRANT SELECT, INSERT ON accounting.period_event TO finapp_app;
