-- The owner cannot empty a fee schedule's versions and a payout's evidence in one statement; merchant's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
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
--   6 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: fee_schedule, fee_schedule_version, merchant_bnpl_fee_schedule_event,
--   payment_fee_pin, payout_evidence, payout_return.
--   3 function(s), 6 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V009, V010, V013,
--   the last definition of each. CURRENT_TIMESTAMP, CURRENT_DATE and LOCALTIMESTAMP are grammar, not functions, and
--   are never resolved through a path.
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

CREATE OR REPLACE FUNCTION merchant.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'merchant.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION merchant.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only merchant table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER fee_schedule_is_never_truncated
    BEFORE TRUNCATE ON merchant.fee_schedule
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

CREATE TRIGGER fee_schedule_version_is_never_truncated
    BEFORE TRUNCATE ON merchant.fee_schedule_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

CREATE TRIGGER merchant_bnpl_fee_schedule_event_is_never_truncated
    BEFORE TRUNCATE ON merchant.merchant_bnpl_fee_schedule_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

CREATE TRIGGER payment_fee_pin_is_never_truncated
    BEFORE TRUNCATE ON merchant.payment_fee_pin
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

CREATE TRIGGER payout_evidence_is_never_truncated
    BEFORE TRUNCATE ON merchant.payout_evidence
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

CREATE TRIGGER payout_return_is_never_truncated
    BEFORE TRUNCATE ON merchant.payout_return
    FOR EACH STATEMENT
    EXECUTE FUNCTION merchant.history_is_never_truncated();

-- merchant.bnpl_fee_pin_names_a_returning_version_in_force: V013's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION merchant.bnpl_fee_pin_names_a_returning_version_in_force() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    pinned merchant.fee_schedule_version%ROWTYPE;
BEGIN
    -- P14-DOC-001 (V013): the pin's instant is stamped HERE, by this insert's clock - never a value a writer supplies or
    -- an earlier statement read, which a database clock stepped back between the two put after this clock (V012 refused
    -- it) - and never before the transaction began (ADR-0063 decision 2). "In force by the pin's instant" below is judged
    -- on this stamp, on the one clock every instance shares (the Phase 12 -> 13 gate's A3-M1). The domain takes it back
    -- from RETURNING.
    NEW.pinned_at := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp());
    SELECT * INTO pinned FROM merchant.fee_schedule_version WHERE id = NEW.fee_schedule_version_id;
    IF pinned.refund_fee_policy <> 'RETURNED' THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version that returns its fee on refund (P12-TSK-006, ADR-0105)';
    END IF;
    IF pinned.fixed_currency <> NEW.gross_currency THEN
        RAISE EXCEPTION 'a BNPL fee pin''s gross is in its version''s currency (P12-TSK-006, INV-MON-04)';
    END IF;
    IF pinned.effective_from > NEW.pinned_at AND pinned.effective_from <> pinned.created_at THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version in force when it was pinned (P12-TSK-006, INV-MER-03)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_fee_schedule a
                    WHERE a.merchant_id = NEW.merchant_id AND a.product = NEW.product
                      AND a.fee_schedule_id = pinned.fee_schedule_id) THEN
        RAISE EXCEPTION 'a BNPL fee pin names a version of the merchant''s BNPL schedule for the product (P12-TSK-006)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM merchant.merchant_bnpl_enablement e
                    WHERE e.merchant_id = NEW.merchant_id AND e.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'a BNPL fee pin is born only for a BNPL-enabled merchant (P12-TSK-006, INV-AUD-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- merchant.merchant_bnpl_enablement_permits_only_machine_edges: V010's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION merchant.merchant_bnpl_enablement_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.decision_reason IS NOT NULL OR NEW.withdrawn_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL
                OR NEW.withdrawal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a merchant BNPL enablement is born PROPOSED and undecided: none is enabled by insert (P12-TSK-006)';
        END IF;
        NEW.proposed_at := pg_catalog.clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a merchant BNPL enablement is never deleted: its end is a status (P12-TSK-006)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a merchant BNPL enablement''s proposal is frozen: the approver approves what was proposed (P12-TSK-006, INV-AUD-04)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a merchant BNPL enablement moves PROPOSED -> {ACTIVE, REJECTED}, ACTIVE -> {WITHDRAWN}; % -> % is not an edge (P12-TSK-006)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        IF NEW.withdrawn_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL OR NEW.withdrawal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a proposal is decided before anything is withdrawn (P12-TSK-006)';
        END IF;
        NEW.decided_at := GREATEST(pg_catalog.clock_timestamp(), OLD.proposed_at);
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'a merchant BNPL enablement''s decision is frozen; a withdrawal records only itself (P12-TSK-006)';
        END IF;
        NEW.withdrawn_at := GREATEST(pg_catalog.clock_timestamp(), OLD.decided_at);
    END IF;
    RETURN NEW;
END;
$$;

-- merchant.merchant_payout_send_permit_is_the_databases: V009's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION merchant.merchant_payout_send_permit_is_the_databases()
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
