-- P8-TSK-017: scheme items - the scheme's line vocabulary and references on the working copy, the
-- cycle on the run, and the cycle a return learns from the report (ADR-0065 section 2, ADR-0067
-- section 5, ADR-0062's follow-up; INV-RAIL-04, INV-SET-01).
--
-- WHY A RECONCILIATION MIGRATION AT ALL
--   The P8-TSK-017 backlog entry recorded "Persistence: none new", naming
--   external_item.learned_cycle as reconciliation V003's. It never existed, and the external
--   item's line-type and key-kind CHECKs admitted neither the scheme's lines nor its references.
--   This migration is the recorded deviation. It takes V009, which P8-TSK-016 had renumbered for
--   P8-TSK-022's run_replay: that moves to V010 and P8-TSK-023's repudiation to V011.
--
-- THE CYCLE LIVES ON THE RUN
--   A v1 cycle report is one cycle (settlement V006's header), so the cycle is the RUN's birth
--   fact - frozen like every birth fact, NULL for every run that is not a scheme cycle report. The
--   matcher compares it with a matched expectation's announced settlement_cycle (V002, an
--   attribute, never a key) and raises a zero-value TIMING_DIFFERENCE when they differ.
--
-- THE LEARNED CYCLE
--   A return (PUSH_RETURN) announced no cycle - nor does a pay-in or a parking whose cycle went
--   unannounced: the report is where the platform learns it. The item that allocates to any such
--   expectation records its run's cycle in learned_cycle - written ONCE,
--   in the allocating chunk, equal to the run's cycle, never at birth and never again (the
--   every-writer rule below; the UPDATE grant narrowed to it).

-- -----------------------------------------------------------------------------------------------
-- The vocabulary (ExternalLineType, ItemKeyKind; the migration test reconciles both lists).
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.external_item
    DROP CONSTRAINT external_item_line_type,
    ADD CONSTRAINT external_item_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE')),
    ADD COLUMN learned_cycle TEXT,
    ADD CONSTRAINT external_item_learned_cycle_bounded CHECK (
        char_length(learned_cycle) BETWEEN 1 AND 64);

ALTER TABLE reconciliation.external_item_key
    DROP CONSTRAINT external_item_key_kind,
    ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF'));

COMMENT ON COLUMN reconciliation.external_item.learned_cycle IS
    'The cycle a cycle-less expectation (a PUSH_RETURN always; a pay-in or parking whose cycle went unannounced) learned from this item''s report: the run''s settlement_cycle, written once by the allocating chunk (P8-TSK-017, ADR-0062''s follow-up); NULL otherwise.';

-- -----------------------------------------------------------------------------------------------
-- The cycle on the run - a frozen birth fact (V003's function re-stated with it).
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.reconciliation_batch
    ADD COLUMN settlement_cycle TEXT,
    ADD CONSTRAINT reconciliation_batch_cycle_bounded CHECK (
        char_length(settlement_cycle) BETWEEN 1 AND 64);

COMMENT ON COLUMN reconciliation.reconciliation_batch.settlement_cycle IS
    'The scheme cycle a cycle report settles (the batch''s cycle token, P8-TSK-017): compared with each matched expectation''s announced settlement_cycle; NULL for every other run.';

CREATE OR REPLACE FUNCTION reconciliation.reconciliation_batch_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.source_sequence IS DISTINCT FROM OLD.source_sequence
            OR NEW.item_count IS DISTINCT FROM OLD.item_count
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a reconciliation run''s birth statement is frozen (INV-HIST-01''s discipline, P8-TSK-009)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('IN_PROGRESS', 'COMPLETED', 'BLOCKED')) OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('COMPLETED', 'BLOCKED')) OR (OLD.status = 'BLOCKED' AND NEW.status IN ('IN_PROGRESS'))) THEN
        RAISE EXCEPTION 'not a reconciliation run edge: % -> % (section 5.3)',
            OLD.status, NEW.status;
    END IF;
    IF NEW.cursor < OLD.cursor THEN
        RAISE EXCEPTION 'a run''s cursor never walks backwards: replay is by decision records, never by re-running the walk (INV-REC-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- -----------------------------------------------------------------------------------------------
-- The item: its copied line frozen as before (V008's function re-stated), the learned cycle
-- written once, equal to its run's cycle.
-- -----------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.external_item_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    run_cycle TEXT;
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.run_id IS DISTINCT FROM OLD.run_id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.settlement_line_id IS DISTINCT FROM OLD.settlement_line_id
            OR NEW.line_no IS DISTINCT FROM OLD.line_no
            OR NEW.line_type IS DISTINCT FROM OLD.line_type
            OR NEW.direction IS DISTINCT FROM OLD.direction
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.position_purpose IS DISTINCT FROM OLD.position_purpose
            OR NEW.attributed_source_id IS DISTINCT FROM OLD.attributed_source_id
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.settlement_date IS DISTINCT FROM OLD.settlement_date
            OR NEW.value_date IS DISTINCT FROM OLD.value_date
            OR NEW.canonical_fingerprint IS DISTINCT FROM OLD.canonical_fingerprint
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an external item''s copied line is frozen (ADR-0064, INV-HIST-01''s discipline): a corrected line is a NEW line in a later batch';
    END IF;
    IF NEW.learned_cycle IS DISTINCT FROM OLD.learned_cycle THEN
        IF OLD.learned_cycle IS NOT NULL THEN
            RAISE EXCEPTION 'a learned cycle is recorded once (P8-TSK-017): it never moves again';
        END IF;
        SELECT settlement_cycle INTO run_cycle
            FROM reconciliation.reconciliation_batch WHERE id = NEW.run_id;
        IF run_cycle IS NULL OR NEW.learned_cycle IS DISTINCT FROM run_cycle THEN
            RAISE EXCEPTION 'a learned cycle is the item''s own report''s cycle (P8-TSK-017)';
        END IF;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PENDING' AND NEW.status IN ('MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED')) OR (OLD.status = 'MATCHED' AND NEW.status IN ('UNMATCHED', 'REPUDIATED')) OR (OLD.status = 'CHECKED' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'OFFSET' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'UNMATCHED' AND NEW.status IN ('MATCHED', 'PARKED', 'REPUDIATED')) OR (OLD.status = 'PARKED' AND NEW.status IN ('MATCHED', 'RESOLVED', 'REPUDIATED'))) THEN
        RAISE EXCEPTION 'not an external item edge: % -> % (section 5.4)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- An item is never BORN knowing a cycle it has not learned.
CREATE OR REPLACE FUNCTION reconciliation.external_item_learns_no_cycle_at_birth()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.learned_cycle IS NOT NULL THEN
        RAISE EXCEPTION 'a cycle is learned by an allocation, never at birth (P8-TSK-017)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER external_item_learns_no_cycle_at_birth
    BEFORE INSERT ON reconciliation.external_item
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.external_item_learns_no_cycle_at_birth();

GRANT UPDATE (learned_cycle) ON reconciliation.external_item TO finapp_app;
