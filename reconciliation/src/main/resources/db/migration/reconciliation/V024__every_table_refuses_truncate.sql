-- Every reconciliation table refuses TRUNCATE, to every role (P15-TSK-004; the C15 debt row "The reconciliation tables' owner can TRUNCATE";
-- THREAT_MODEL.md T33; INV-HIST-01).
--
-- WHY
--   Every one of the 33 tables refuses DELETE to every writer by a row trigger and to finapp_app by privilege. A row
--   trigger does not fire on TRUNCATE, so the one role that holds TRUNCATE on them, their owner finapp_migrator, could
--   empty financial evidence in a single statement. A statement-level BEFORE TRUNCATE trigger refuses it, the owner
--   included, as accounting, bnpl, credit, lending and risk already do. The list is the migrations' own, recounted when
--   this was written (33: P8-TST-002's scan found 31, and match_reach and capital_recognition came later).
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

CREATE OR REPLACE FUNCTION reconciliation.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation.% is never truncated (P15-TSK-004, INV-HIST-01): matching evidence and its decisions are corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION reconciliation.history_is_never_truncated() IS
    'Refuses TRUNCATE on every reconciliation table, the owner included (P15-TSK-004, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER allocation_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.allocation
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER break_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.break
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER break_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.break_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER break_evidence_link_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.break_evidence_link
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER break_note_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.break_note
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER capital_recognition_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.capital_recognition
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER expectation_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.expectation
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER expectation_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.expectation_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER expectation_key_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.expectation_key
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER external_item_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.external_item
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER external_item_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.external_item_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER external_item_key_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.external_item_key
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER match_candidate_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.match_candidate
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER match_decision_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.match_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER match_parked_original_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.match_parked_original
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER match_reach_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.match_reach
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER park_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.park
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER provider_fee_schedule_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.provider_fee_schedule
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER reconciliation_batch_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.reconciliation_batch
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER reconciliation_batch_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.reconciliation_batch_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER reference_alias_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.reference_alias
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER repudiation_closure_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.repudiation_closure
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER resolution_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.resolution
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER resolution_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.resolution_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER rule_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER rule_set_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.rule_set
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER rule_set_event_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.rule_set_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER rule_set_lag_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.rule_set_lag
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER run_replay_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.run_replay
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER severity_threshold_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.severity_threshold
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER suspense_item_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.suspense_item
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER suspense_release_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.suspense_release
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();

CREATE TRIGGER tolerance_is_never_truncated
    BEFORE TRUNCATE ON reconciliation.tolerance
    FOR EACH STATEMENT
    EXECUTE FUNCTION reconciliation.history_is_never_truncated();
