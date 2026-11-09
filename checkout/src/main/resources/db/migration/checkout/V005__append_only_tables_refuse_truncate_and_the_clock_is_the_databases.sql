-- The owner cannot empty a completed checkout's order in one statement; checkout's clock is the database's whatever a role's search_path holds (P15-TSK-040; THREAT_MODEL.md T13, T33; INV-HIST-01).
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
--   1 table(s) gain a statement-level BEFORE TRUNCATE trigger, read from the chain by
--   OwnerReachOverHistoryIsClosedTest: checkout_order.
--   1 function(s), 1 clock call(s): each function's latest definition across the whole chain, byte for byte but for
--   pg_catalog. written before each clock call in code, never in a comment or a string. Taken from V004, the last
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

CREATE OR REPLACE FUNCTION checkout.history_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'checkout.% is never truncated (P15-TSK-040, INV-HIST-01): a recorded fact is corrected forward, never erased', TG_TABLE_NAME;
END;
$$;

COMMENT ON FUNCTION checkout.history_is_never_truncated() IS
    'Refuses TRUNCATE on an append-only checkout table, the owner included (P15-TSK-040, INV-HIST-01). A row trigger does not fire on TRUNCATE.';

CREATE TRIGGER checkout_order_is_never_truncated
    BEFORE TRUNCATE ON checkout.checkout_order
    FOR EACH STATEMENT
    EXECUTE FUNCTION checkout.history_is_never_truncated();

-- checkout.checkout_session_permits_only_machine_edges: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION checkout.checkout_session_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_ref IS DISTINCT FROM OLD.merchant_ref
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.amount_currency IS DISTINCT FROM OLD.amount_currency
            OR NEW.amount_scale IS DISTINCT FROM OLD.amount_scale
            OR NEW.line_summary IS DISTINCT FROM OLD.line_summary
            OR NEW.fee_schedule_version_ref IS DISTINCT FROM OLD.fee_schedule_version_ref
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a checkout session''s offer is frozen: only status, status_changed_at, the one payment intent reference and the one financing reference ever change (INV-HIST-01)';
    END IF;
    IF OLD.payment_intent_ref IS NOT NULL
            AND NEW.payment_intent_ref IS DISTINCT FROM OLD.payment_intent_ref THEN
        RAISE EXCEPTION 'a checkout session opens ONE payment intent and keeps it: the reference is set once (ADR-0053 section 3)';
    END IF;
    -- P12-TSK-007: the financing reference is set once, and only by the financing edge.
    IF OLD.financed_by_ref IS NOT NULL
            AND NEW.financed_by_ref IS DISTINCT FROM OLD.financed_by_ref THEN
        RAISE EXCEPTION 'a checkout session is financed by ONE purchase and keeps it: the financing reference is set once (P12-TSK-007, INV-BNPL-03)';
    END IF;
    IF OLD.financed_by_ref IS NULL AND NEW.financed_by_ref IS NOT NULL
            AND NOT (OLD.status = 'OPEN' AND NEW.status = 'COMPLETED') THEN
        RAISE EXCEPTION 'a financing reference is set only by the financing edge OPEN -> COMPLETED (P12-TSK-007)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('PAYMENT_PENDING', 'COMPLETED', 'EXPIRED', 'ABANDONED'))
            OR (OLD.status = 'PAYMENT_PENDING' AND NEW.status IN ('COMPLETED', 'EXPIRED'))
            OR (OLD.status = 'EXPIRED' AND NEW.status IN ('COMPLETED_LATE'))) THEN
        RAISE EXCEPTION 'a checkout session moves only along the machine''s edges: OPEN -> {PAYMENT_PENDING, COMPLETED by financing, EXPIRED, ABANDONED}, PAYMENT_PENDING -> {COMPLETED, EXPIRED}, EXPIRED -> {COMPLETED_LATE} (ADR-0053, INV-LIFE-02/-04)';
    END IF;
    -- P12-TSK-007: THE FINANCING EDGE BESIDE ITS EVIDENCE. Intent-less, with its financing reference, and only before
    -- the deadline on the database clock - statement_timestamp() of this UPDATE, which runs under the row lock the
    -- caller already holds, the same instant the store's conditional write judges.
    IF OLD.status = 'OPEN' AND NEW.status = 'COMPLETED' THEN
        IF NEW.financed_by_ref IS NULL OR NEW.payment_intent_ref IS NOT NULL THEN
            RAISE EXCEPTION 'the machine''s OPEN -> COMPLETED edge is by financing only: it carries its financing reference and no payment intent (P12-TSK-007, INV-BNPL-03)';
        END IF;
        IF OLD.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'the machine''s financing edge closes at the deadline: an expired checkout session is not financed (P12-TSK-007, INV-LIFE-04)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
