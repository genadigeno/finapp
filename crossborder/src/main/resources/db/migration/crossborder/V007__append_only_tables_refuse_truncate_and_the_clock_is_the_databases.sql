-- The owner cannot empty a corridor's history and a payment's in one statement; crossborder's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
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
--   8 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: beneficiary, cancellation_request, corridor, corridor_availability,
--   corridor_enable_request, corridor_policy_event, corridor_policy_version, payment.
--   1 function(s), 1 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V006, the last
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

CREATE OR REPLACE FUNCTION crossborder.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'crossborder.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION crossborder.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only crossborder table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER beneficiary_is_never_truncated
    BEFORE TRUNCATE ON crossborder.beneficiary
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER cancellation_request_is_never_truncated
    BEFORE TRUNCATE ON crossborder.cancellation_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER corridor_is_never_truncated
    BEFORE TRUNCATE ON crossborder.corridor
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER corridor_availability_is_never_truncated
    BEFORE TRUNCATE ON crossborder.corridor_availability
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER corridor_enable_request_is_never_truncated
    BEFORE TRUNCATE ON crossborder.corridor_enable_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER corridor_policy_event_is_never_truncated
    BEFORE TRUNCATE ON crossborder.corridor_policy_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER corridor_policy_version_is_never_truncated
    BEFORE TRUNCATE ON crossborder.corridor_policy_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

CREATE TRIGGER payment_is_never_truncated
    BEFORE TRUNCATE ON crossborder.payment
    FOR EACH STATEMENT
    EXECUTE FUNCTION crossborder.history_is_never_truncated();

-- crossborder.cancellation_request_is_born_once: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION crossborder.cancellation_request_is_born_once() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a cancellation request is never changed or deleted (P9-TSK-024, INV-HIST-01)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM crossborder.payment p WHERE p.id = NEW.payment_id AND p.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'only a SUBMITTED payment can be cancelled: past acceptance a recall is refused (P9-TSK-024)';
    END IF;
    NEW.requested_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;
