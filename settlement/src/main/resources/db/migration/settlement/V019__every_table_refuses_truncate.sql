-- Every settlement table refuses TRUNCATE, to every role (P15-TSK-004; the C15 debt row "The reconciliation tables' owner can TRUNCATE";
-- THREAT_MODEL.md T33; INV-HIST-01).
--
-- WHY
--   Every one of the 13 tables refuses DELETE to every writer by a row trigger and to finapp_app by privilege. A row
--   trigger does not fire on TRUNCATE, so the one role that holds TRUNCATE on them, their owner finapp_migrator, could
--   empty financial evidence in a single statement. A statement-level BEFORE TRUNCATE trigger refuses it, the owner
--   included, as accounting, bnpl, credit, lending and risk already do. The list is the migrations' own, recounted when
--   this was written (13).
--
-- WHAT IT DOES NOT CLAIM
--   A trigger is an object its owner can drop or disable. So this closes the unreviewed path, a TRUNCATE that happens
--   to be run, not a deliberate one: removing a trigger is a migration, which is reviewed and recorded in the history.
--
-- LOCKS
--   CREATE TRIGGER takes SHARE ROW EXCLUSIVE on each table, held to this transaction's end, which blocks writers for
--   the length of a catalogue change. The migrator's session bounds the wait for each with lock_timeout, and fails
--   rather than queues (DATA_MIGRATIONS.md section 2.1).
--
-- WHAT IT DOES NOT TOUCH
--   No table, column, row, grant or existing trigger. It moves no money.

CREATE OR REPLACE FUNCTION settlement.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'settlement.% is never truncated (P15-TSK-004, INV-HIST-01): what an institution said is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION settlement.history_is_never_truncated() IS
    'Refuses TRUNCATE on every settlement table, the owner included (P15-TSK-004, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER batch_is_never_truncated
    BEFORE TRUNCATE ON settlement.batch
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER batch_event_is_never_truncated
    BEFORE TRUNCATE ON settlement.batch_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER batch_total_is_never_truncated
    BEFORE TRUNCATE ON settlement.batch_total
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER file_is_never_truncated
    BEFORE TRUNCATE ON settlement.file
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER file_chunk_is_never_truncated
    BEFORE TRUNCATE ON settlement.file_chunk
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER file_event_is_never_truncated
    BEFORE TRUNCATE ON settlement.file_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER file_receipt_is_never_truncated
    BEFORE TRUNCATE ON settlement.file_receipt
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER ingestion_error_is_never_truncated
    BEFORE TRUNCATE ON settlement.ingestion_error
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER line_is_never_truncated
    BEFORE TRUNCATE ON settlement.line
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER line_reference_is_never_truncated
    BEFORE TRUNCATE ON settlement.line_reference
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER pull_permit_is_never_truncated
    BEFORE TRUNCATE ON settlement.pull_permit
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER refused_delivery_is_never_truncated
    BEFORE TRUNCATE ON settlement.refused_delivery
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();

CREATE TRIGGER source_is_never_truncated
    BEFORE TRUNCATE ON settlement.source
    FOR EACH STATEMENT
    EXECUTE FUNCTION settlement.history_is_never_truncated();
