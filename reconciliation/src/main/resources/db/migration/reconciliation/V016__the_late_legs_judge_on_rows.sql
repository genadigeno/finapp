-- The Phase 8 -> 9 transition's corrections to the matcher's late legs (ADR-0068 sections 4, 6 and
-- 9.2, ADR-0069; INV-REC-02, INV-REC-04, INV-REC-07, INV-HIST-04).
--
-- WHY NOW
--   The transition's completion gate found the rematch leg judging a late reach on two clocks -
--   an expectation's opened_at against the item's latest decided_at, two Java instants stamped
--   before two commits, often on two instances - so a PARKED line whose expectation committed
--   inside that window never left suspense (the gate's rematch-keyed-valuedate-clocks), and a
--   reach that could never allocate re-locked the worklist's head on every tick (its
--   rematch-key-clause-reselection). Every clause now judges "no decision of the item has yet
--   judged this expectation" on rows, as the anchored clause already did, and a rematch that
--   cannot act records what it reached: the table below.
--
--   And a fee line whose check was contained was left UNMATCHED with an ITEM_ERRORED break no
--   evidence could ever close, although ADR-0069 promised "reprocess, then EVIDENCED" (the gate's
--   REC-6): a REPROCESS run now re-checks it, which needs the item edge UNMATCHED -> CHECKED.

-- ---------------------------------------------------------------------------------------------
-- 1. What a late leg's examination reached: consumed once, so the rematch predicate never
--    re-locks a reach a decision has already judged - and never consumes one it did not see (the
--    examination reads its reach before it judges).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.match_reach (
    decision_id    UUID NOT NULL,
    expectation_id UUID NOT NULL,

    CONSTRAINT match_reach_pk PRIMARY KEY (decision_id, expectation_id),
    CONSTRAINT match_reach_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT match_reach_expectation_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id)
);

COMMENT ON TABLE reconciliation.match_reach IS
    'The expectations a late leg''s examination reached and judged (the Phase 8 -> 9 transition): the rematch predicate''s "already judged" reading beside the decision''s candidates - rows, never clocks. Append-only.';

CREATE OR REPLACE FUNCTION reconciliation.match_reach_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a decision''s reach is history, append-only for every writer (INV-HIST-04)';
END;
$$;

CREATE TRIGGER match_reach_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.match_reach
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.match_reach_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- 2. The item: V013's function re-stated with ItemStatus.sqlTransitionRule() - one edge added,
--    UNMATCHED -> CHECKED, a REPROCESS run's re-check of a fee line left waiting.
-- ---------------------------------------------------------------------------------------------
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
            AND NOT ((OLD.status = 'PENDING' AND NEW.status IN ('MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED')) OR (OLD.status = 'MATCHED' AND NEW.status IN ('UNMATCHED', 'REPUDIATED')) OR (OLD.status = 'CHECKED' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'OFFSET' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'UNMATCHED' AND NEW.status IN ('MATCHED', 'CHECKED', 'PARKED', 'REPUDIATED')) OR (OLD.status = 'PARKED' AND NEW.status IN ('MATCHED', 'UNMATCHED', 'RESOLVED', 'REPUDIATED')) OR (OLD.status = 'RESOLVED' AND NEW.status IN ('REPUDIATED'))) THEN
        RAISE EXCEPTION 'not an external item edge: % -> % (section 5.4)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants: the reach is written by the rematch leg alone, insert-only.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.match_reach TO finapp_app;
