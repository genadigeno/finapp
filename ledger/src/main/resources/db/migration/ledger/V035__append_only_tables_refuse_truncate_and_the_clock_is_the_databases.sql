-- The owner cannot empty the journal and what stands behind it in one statement; ledger's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
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
--   4 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: journal_entry, journal_line, adjustment_proposal_line, counterparty.
--   1 function(s), 1 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V028, the last
--   definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions, and are never
--   resolved through a path.
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

CREATE OR REPLACE FUNCTION ledger.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION ledger.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only ledger table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER journal_entry_is_never_truncated
    BEFORE TRUNCATE ON ledger.journal_entry
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.history_is_never_truncated();

CREATE TRIGGER journal_line_is_never_truncated
    BEFORE TRUNCATE ON ledger.journal_line
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.history_is_never_truncated();

CREATE TRIGGER adjustment_proposal_line_is_never_truncated
    BEFORE TRUNCATE ON ledger.adjustment_proposal_line
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.history_is_never_truncated();

CREATE TRIGGER counterparty_is_never_truncated
    BEFORE TRUNCATE ON ledger.counterparty
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.history_is_never_truncated();

-- ledger.posting_cutoff_moves_one_period: V028's definition, 1 clock call(s) qualified.
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
            AND NEW.closed_through < (pg_catalog.now() AT TIME ZONE 'UTC')::date
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
