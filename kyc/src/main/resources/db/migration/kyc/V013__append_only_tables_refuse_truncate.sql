-- The owner cannot empty a counterparty screening's result in one statement (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
--
-- WHY
--   Each of these tables refuses DELETE by a row trigger, and a row trigger does not fire on TRUNCATE. The one role
--   that holds TRUNCATE on them, their owner finapp_migrator, could empty the table in a single statement. A
--   statement-level BEFORE TRUNCATE trigger refuses it, to every role, the owner included, as reconciliation V024 and
--   settlement V019 do.
--
-- WHAT CHANGES, AND ONLY THAT
--   1 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: counterparty_screening.
--
-- WHAT IT DOES NOT CHANGE
--   No table, column, row or grant, and no existing trigger. It moves no money. No judgement on any path a production
--   role has: finapp_app holds no TRUNCATE and no CREATE on any schema, and the pg_catalog clocks are the ones its
--   path already resolved to.
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

CREATE OR REPLACE FUNCTION kyc.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'kyc.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION kyc.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only kyc table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER counterparty_screening_is_never_truncated
    BEFORE TRUNCATE ON kyc.counterparty_screening
    FOR EACH STATEMENT
    EXECUTE FUNCTION kyc.history_is_never_truncated();
