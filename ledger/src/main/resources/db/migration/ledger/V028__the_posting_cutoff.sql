-- The ledger posting cutoff (P14-TSK-004; ADR-0114 decisions 1-2; PHASE_14_PLAN.md sections 8.1-8.3, 13.5;
-- INV-ACC-03, INV-ACC-08, INV-LED-03).
--
-- WHAT IT IS
--   One date, closed_through: every journal entry whose posting_date is on or before it is refused at insert. It is
--   NULL until the first period lock, so this migration changes no behaviour until accounting locks a period. It moves
--   only through the ledger's PostingCutoffs API, by exactly one calendar month (C9) forward or back, each move
--   recorded once in posting_cutoff_event. The period machine is accounting's (P14-TSK-005); the date is the ledger's.
--
-- THE SERIALISER, AND WHY AN ADVISORY LOCK PAIR
--   Under READ COMMITTED a posting can read "open", a lock can commit, and the posting can commit after it - an entry
--   in a period whose trial balance was already taken. So every journal_entry insert takes advisory namespace 14 on
--   hashtext('cutoff') SHARED inside the trigger below, then reads the cutoff; every cutoff move takes the same key
--   EXCLUSIVE before its conditional UPDATE. Once a move commits, every posting dated in the period either committed
--   before it (the move waited for its shared holder) or reads the new cutoff after it (its shared request queued
--   behind the exclusive one) and is refused - raw SQL included (INV-ACC-08). Namespace 14 is accounting's
--   (DISTRIBUTED_EXECUTION.md section 3); key 'mapping' is P14-TSK-003's, this key is 'cutoff' - a different int4.
--   ADR-0114 rejected SELECT ... FOR SHARE on the row for every posting (a multixact on one hot row); the shared
--   advisory lock has the same semantics at the cost of one lock-table entry.
--
-- ISOLATION
--   Under READ COMMITTED the trigger's read takes a fresh statement snapshot AFTER the lock is granted, so it sees a
--   move that committed while it waited. A REPEATABLE READ or SERIALIZABLE poster's snapshot may predate the move: it
--   reads the row FOR SHARE instead, and PostgreSQL raises 40001 for a row version committed after its snapshot - a
--   retryable failure, never a missed cut.
--
-- COST ON THE HOT PATH
--   One shared advisory lock (re-entrant: a transaction that already holds it never queues again), one
--   current_setting, one primary-key read of a one-row table. No new index on journal_entry (the posting_date index
--   is P14-TSK-007's V029).
--
-- The refusal is SQLSTATE 'LDCUT' (the fx 'FXCAP' / lending 'LNEXP' precedent: matched by SQLState, never prose),
-- translated by JdbcJournalEntryStore to PeriodClosedException (ledger.PeriodClosed). Messages carry identifiers and
-- dates, never an amount (INV-AUD-02).

-- The last day of the calendar month holding d - the one period shape (C9). IMMUTABLE: d is cast to a timestamp
-- WITHOUT time zone, so no session setting enters a CHECK.
CREATE OR REPLACE FUNCTION ledger.posting_cutoff_month_end(d date)
    RETURNS date
    LANGUAGE sql
    IMMUTABLE
    STRICT
AS $$
    SELECT (date_trunc('month', d::timestamp) + interval '1 month' - interval '1 day')::date
$$;

CREATE TABLE ledger.posting_cutoff (
    id             smallint NOT NULL DEFAULT 1,
    closed_through date,
    revision       bigint   NOT NULL DEFAULT 0,
    CONSTRAINT posting_cutoff_pkey PRIMARY KEY (id),
    CONSTRAINT posting_cutoff_is_one_row CHECK (id = 1),
    CONSTRAINT posting_cutoff_is_a_month_end
        CHECK (closed_through IS NULL OR closed_through = ledger.posting_cutoff_month_end(closed_through)),
    CONSTRAINT posting_cutoff_revision_is_not_negative CHECK (revision >= 0)
);

-- Unset: nothing is refused until the first lock.
INSERT INTO ledger.posting_cutoff (id, closed_through, revision) VALUES (1, NULL, 0);

CREATE TABLE ledger.posting_cutoff_event (
    id                  uuid        NOT NULL,
    revision            bigint      NOT NULL,
    movement            text        NOT NULL,
    from_closed_through date,
    to_closed_through   date,
    actor_id            text        NOT NULL,
    correlation_id      text        NOT NULL,
    causation_id        text        NOT NULL,
    reason              text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    CONSTRAINT posting_cutoff_event_pkey PRIMARY KEY (id),
    CONSTRAINT posting_cutoff_event_one_per_revision UNIQUE (revision),
    CONSTRAINT posting_cutoff_event_revision_is_positive CHECK (revision > 0),
    CONSTRAINT posting_cutoff_event_movement_is_known CHECK (movement IN ('ADVANCE', 'MOVE_BACK')),
    CONSTRAINT posting_cutoff_event_dates_are_month_ends
        CHECK ((from_closed_through IS NULL OR from_closed_through = ledger.posting_cutoff_month_end(from_closed_through))
            AND (to_closed_through IS NULL OR to_closed_through = ledger.posting_cutoff_month_end(to_closed_through))),
    CONSTRAINT posting_cutoff_event_moves_one_period
        CHECK ((movement = 'ADVANCE' AND to_closed_through IS NOT NULL
                    AND (from_closed_through IS NULL
                        OR to_closed_through = ledger.posting_cutoff_month_end((from_closed_through + 1))))
            OR (movement = 'MOVE_BACK' AND from_closed_through IS NOT NULL
                    AND (to_closed_through IS NULL
                        OR to_closed_through = (date_trunc('month', from_closed_through::timestamp) - interval '1 day')::date))),
    CONSTRAINT posting_cutoff_event_actor_is_bounded CHECK (length(actor_id) BETWEEN 1 AND 200),
    CONSTRAINT posting_cutoff_event_correlation_is_bounded
        CHECK (length(correlation_id) BETWEEN 1 AND 200 AND length(causation_id) BETWEEN 1 AND 200),
    CONSTRAINT posting_cutoff_event_reason_is_bounded CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);

-- The derived-previous read of a move back (below): the latest ADVANCE into a date.
CREATE INDEX posting_cutoff_event_advances_into
    ON ledger.posting_cutoff_event (to_closed_through, revision)
    WHERE movement = 'ADVANCE';

-- THE REFUSAL (INV-ACC-03, INV-ACC-08), binding every writer of journal_entry. Fires before V009's
-- journal_entry_reversal_references_a_posting (BEFORE triggers fire in name order), so a refused entry costs nothing
-- further.
CREATE OR REPLACE FUNCTION ledger.journal_entry_respects_the_posting_cutoff()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    cut   date;
    present boolean := false;
BEGIN
    -- The serialiser (see header): granted at once unless a cutoff move holds or awaits the key exclusively.
    PERFORM pg_advisory_xact_lock_shared(14, hashtext('cutoff'));
    IF current_setting('transaction_isolation') = 'read committed' THEN
        SELECT closed_through, true INTO cut, present FROM ledger.posting_cutoff WHERE id = 1;
    ELSE
        SELECT closed_through, true INTO cut, present FROM ledger.posting_cutoff WHERE id = 1 FOR SHARE;
    END IF;
    IF NOT coalesce(present, false) THEN
        -- Fail closed: an unknown cutoff refuses rather than admits (the row cannot be deleted - see below).
        RAISE EXCEPTION 'ledger_posting_cutoff_missing: entry % is refused - the posting cutoff row is missing (INV-ACC-08)',
                NEW.id
            USING ERRCODE = '23514';
    END IF;
    IF cut IS NOT NULL AND NEW.posting_date <= cut THEN
        RAISE EXCEPTION 'ledger_posting_cutoff: entry % is dated % - on or before the posting cutoff %; the period is locked (INV-ACC-03, INV-ACC-08)',
                NEW.id, NEW.posting_date, cut
            USING ERRCODE = 'LDCUT';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_respects_the_posting_cutoff
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW
    EXECUTE FUNCTION ledger.journal_entry_respects_the_posting_cutoff();

-- THE MOVE'S SHAPE, binding every updater (the app role's column grant included): the exclusive lock (re-entrant for
-- PostingCutoffs, which took it first; for a raw updater it serialises the move with every poster all the same), the
-- revision by one, and exactly one period: forward to the next month end (or, from unset, to any past month end - the
-- first lock), back to the value the latest ADVANCE into the current one came from (unset or the previous month end).
CREATE OR REPLACE FUNCTION ledger.posting_cutoff_moves_one_period()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    came_from     date;
    advanced_into boolean := false;
BEGIN
    PERFORM pg_advisory_xact_lock(14, hashtext('cutoff'));
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.revision IS DISTINCT FROM OLD.revision + 1 THEN
        RAISE EXCEPTION 'ledger_posting_cutoff_moves_one_period: the cutoff row keeps its identity and moves by one revision (INV-ACC-08)'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.closed_through IS NOT NULL
            AND NEW.closed_through < (now() AT TIME ZONE 'UTC')::date
            AND (OLD.closed_through IS NULL
                OR NEW.closed_through = ledger.posting_cutoff_month_end(OLD.closed_through + 1)) THEN
        RETURN NEW;  -- an advance by one period, or the first lock; never over today
    END IF;
    IF OLD.closed_through IS NOT NULL THEN
        SELECT from_closed_through, true INTO came_from, advanced_into
            FROM ledger.posting_cutoff_event
            WHERE movement = 'ADVANCE' AND to_closed_through = OLD.closed_through
            ORDER BY revision DESC
            LIMIT 1;
        IF coalesce(advanced_into, false) AND NEW.closed_through IS NOT DISTINCT FROM came_from THEN
            RETURN NEW;  -- back by exactly the period the last advance into it took
        END IF;
    END IF;
    RAISE EXCEPTION 'ledger_posting_cutoff_moves_one_period: the cutoff may not move from % to % - one period forward or back only (INV-ACC-08)',
            OLD.closed_through, NEW.closed_through
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER posting_cutoff_moves_one_period
    BEFORE UPDATE ON ledger.posting_cutoff
    FOR EACH ROW
    EXECUTE FUNCTION ledger.posting_cutoff_moves_one_period();

-- EVERY MOVE IS EVENTED: at commit, the revision a move produced has its one event row (the UNIQUE above makes it
-- exactly one). Deferred, so the API's UPDATE ... RETURNING revision may precede its INSERT.
CREATE OR REPLACE FUNCTION ledger.posting_cutoff_move_is_evented()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger.posting_cutoff_event WHERE revision = NEW.revision) THEN
        RAISE EXCEPTION 'ledger_posting_cutoff_move_is_evented: cutoff revision % has no event row (INV-ACC-08)', NEW.revision
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER posting_cutoff_move_is_evented
    AFTER UPDATE ON ledger.posting_cutoff
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.posting_cutoff_move_is_evented();

-- Neither the row nor its history ever disappears, for any role, the migrator included (the V004 append-only
-- pattern): a deleted row would make the refusal fail closed for every posting, and a deleted event would erase who
-- moved the cutoff and why.
CREATE OR REPLACE FUNCTION ledger.posting_cutoff_is_never_erased()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger_posting_cutoff_is_never_erased: % on % is refused - the cutoff moves only through its one-period edge and its history is append-only (INV-ACC-08, INV-HIST-01)',
            TG_OP, TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER posting_cutoff_is_never_deleted
    BEFORE DELETE ON ledger.posting_cutoff
    FOR EACH ROW
    EXECUTE FUNCTION ledger.posting_cutoff_is_never_erased();

CREATE TRIGGER posting_cutoff_is_never_truncated
    BEFORE TRUNCATE ON ledger.posting_cutoff
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.posting_cutoff_is_never_erased();

CREATE TRIGGER posting_cutoff_event_is_append_only
    BEFORE UPDATE OR DELETE ON ledger.posting_cutoff_event
    FOR EACH ROW
    EXECUTE FUNCTION ledger.posting_cutoff_is_never_erased();

CREATE TRIGGER posting_cutoff_event_is_never_truncated
    BEFORE TRUNCATE ON ledger.posting_cutoff_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.posting_cutoff_is_never_erased();

-- The grants: the application role reads the cutoff (every posting's trigger runs as it) and updates only its two
-- moving columns, through the one-period trigger above; the history is SELECT, INSERT. No DELETE anywhere. The
-- journal's grants are unchanged.
GRANT SELECT ON ledger.posting_cutoff TO finapp_app;
GRANT UPDATE (closed_through, revision) ON ledger.posting_cutoff TO finapp_app;
GRANT SELECT, INSERT ON ledger.posting_cutoff_event TO finapp_app;

COMMENT ON TABLE ledger.posting_cutoff IS
    'The posting cutoff (P14-TSK-004, ADR-0114): one row; closed_through NULL until the first period lock; every '
    'journal entry dated on or before it is refused at insert under advisory namespace 14 key cutoff shared; moved '
    'only one calendar month at a time under the same key exclusive, each move evented (INV-ACC-03, INV-ACC-08).';
COMMENT ON TABLE ledger.posting_cutoff_event IS
    'Every move of the posting cutoff, append-only: revision, movement, from and to, actor, correlation, causation, '
    'reason (from accounting''s lock, unlock or reopen act) and time (P14-TSK-004).';
