-- The owner cannot empty the evidence of what an external rail said in one statement; payments's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
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
--   11 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: clearing_record, outbound_credit, outbound_credit_return, provider_evidence,
--   routing_decision, routing_decision_step, routing_policy_version, routing_rule, routing_rule_rail,
--   scheme_execution_claim, unmatched_confirmation.
--   7 function(s), 9 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V027, V028, V030,
--   V034, the last definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions,
--   and are never resolved through a path.
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

CREATE OR REPLACE FUNCTION payments.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'payments.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION payments.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only payments table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER clearing_record_is_never_truncated
    BEFORE TRUNCATE ON payments.clearing_record
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER outbound_credit_is_never_truncated
    BEFORE TRUNCATE ON payments.outbound_credit
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER outbound_credit_return_is_never_truncated
    BEFORE TRUNCATE ON payments.outbound_credit_return
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER provider_evidence_is_never_truncated
    BEFORE TRUNCATE ON payments.provider_evidence
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER routing_decision_is_never_truncated
    BEFORE TRUNCATE ON payments.routing_decision
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER routing_decision_step_is_never_truncated
    BEFORE TRUNCATE ON payments.routing_decision_step
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER routing_policy_version_is_never_truncated
    BEFORE TRUNCATE ON payments.routing_policy_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER routing_rule_is_never_truncated
    BEFORE TRUNCATE ON payments.routing_rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER routing_rule_rail_is_never_truncated
    BEFORE TRUNCATE ON payments.routing_rule_rail
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER scheme_execution_claim_is_never_truncated
    BEFORE TRUNCATE ON payments.scheme_execution_claim
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

CREATE TRIGGER unmatched_confirmation_is_never_truncated
    BEFORE TRUNCATE ON payments.unmatched_confirmation
    FOR EACH STATEMENT
    EXECUTE FUNCTION payments.history_is_never_truncated();

-- payments.dispute_response_send_permit_is_the_databases: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.dispute_response_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.send_permit IS DISTINCT FROM OLD.send_permit THEN
        NEW.send_permit := GREATEST(OLD.send_permit + INTERVAL '1 microsecond', pg_catalog.statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

-- payments.outbound_credit_machine_is_legal: V030's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.outbound_credit_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := pg_catalog.statement_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DISPATCHED' THEN
            RAISE EXCEPTION 'an outbound credit is born DISPATCHED (P9-TSK-019)';
        END IF;
        NEW.created_at := now;
        NEW.last_dispatched_at := now;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an outbound credit is never deleted (P9-TSK-019)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at' - 'last_inquired_at')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at' - 'last_inquired_at') THEN
        RAISE EXCEPTION 'an outbound credit''s instruction is frozen (P9-TSK-019, INV-XB-03)';
    END IF;
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'an outbound credit''s provider reference is set once (P9-TSK-019)';
    END IF;
    IF OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
        RAISE EXCEPTION 'an outbound credit''s delivery is set once (P9-TSK-019)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        -- The send permit: stamped by the database, strictly forward, for every writer.
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', now);
    END IF;
    IF NEW.last_inquired_at IS DISTINCT FROM OLD.last_inquired_at THEN
        -- The sweep's inquiry stamp: the database's own clock, for every writer (the Phase 9 -> 10 transition).
        NEW.last_inquired_at := now;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            (OLD.status = 'DISPATCHED' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED'))
            OR (OLD.status = 'RECEIVED' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'an outbound credit moves % -> % only along its machine (P9-TSK-019)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- payments.outbound_credit_return_is_born_exact: V027's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.outbound_credit_return_is_born_exact()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c payments.outbound_credit%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an outbound credit''s return is append-only (P9-TSK-023)';
    END IF;
    SELECT * INTO c FROM payments.outbound_credit WHERE id = NEW.outbound_credit_id;
    IF NOT FOUND OR c.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'only a completed outbound credit returns (P9-TSK-023, INV-XB-04)';
    END IF;
    IF NEW.applied_by = 'APPLIER' AND (NEW.amount_minor <> c.amount_minor OR NEW.amount_currency <> c.amount_currency
            OR NEW.amount_scale <> c.amount_scale) THEN
        RAISE EXCEPTION 'an applied return is exactly the instructed credit coming back; any other is a person''s (P9-TSK-023, INV-XB-04)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- payments.payment_attempt_send_permit_is_the_databases: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.payment_attempt_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.last_dispatched_at IS NOT NULL
            AND NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', pg_catalog.statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

-- payments.payment_capture_hold_resolves_once: V034's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.payment_capture_hold_resolves_once() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.intent_id IS DISTINCT FROM OLD.intent_id
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
            OR NEW.held_at IS DISTINCT FROM OLD.held_at
            OR NEW.held_until IS DISTINCT FROM OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold''s intent, attempt and window never move (P13-TSK-010)';
    END IF;
    IF OLD.resolution IS NOT NULL THEN
        RAISE EXCEPTION 'a capture hold is resolved exactly once (P13-TSK-010)';
    END IF;
    IF NEW.resolution IS NULL THEN
        RAISE EXCEPTION 'a capture hold changes only by its resolution (P13-TSK-010)';
    END IF;
    IF NEW.resolution = 'CAPTURE' AND pg_catalog.statement_timestamp() >= OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold past its held_until is the expiry''s, never a late capture (P13-TSK-010)';
    END IF;
    IF NEW.resolution = 'EXPIRED' AND pg_catalog.statement_timestamp() < OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold expires only from its held_until (P13-TSK-010)';
    END IF;
    NEW.resolved_at := GREATEST(pg_catalog.statement_timestamp(), OLD.held_at);
    RETURN NEW;
END;
$$;

-- payments.refund_send_permit_is_the_databases: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.refund_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', pg_catalog.statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

-- payments.withdrawal_send_permit_is_the_databases: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION payments.withdrawal_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', pg_catalog.statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;
