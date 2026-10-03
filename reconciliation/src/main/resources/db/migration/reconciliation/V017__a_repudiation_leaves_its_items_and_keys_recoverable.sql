-- The Phase 8 -> 9 transition: a repudiation leaves what it reopened recoverable, for every writer
-- (ADR-0065 section 10, ADR-0070 sections 2 and 10, ADR-0067 section 6; INV-REC-09, INV-REC-01).
--
-- WHAT THE TRANSITION'S GATE FOUND (REC-3, REC-8 / ATOM-03)
--   1. One RECON_PARK item per EXTERNAL item, not per park. V004 made a suspense item once per
--      external item (suspense_item_external_item_once) and once per origin_ref
--      (suspense_item_origin_once), and a RECON_PARK item's origin_ref is its external item. A
--      batch repudiation (V013) reopens a bank item of ANOTHER batch MATCHED/PARKED -> UNMATCHED
--      with a fresh grace, while the item's earlier RECON_PARK row stays - RELEASED, by the
--      rematch's unpark or by the repudiation's own. When that grace expired, the park posted
--      and then failed on the unique; the grace batch rolled back whole, and the item, oldest
--      first, held the head of the source's grace worklist for ever: no later line of that bank
--      source was ever graced, parked or broken.
--   2. A repudiated remittance kept its key. V002's expectation_key_once is
--      UNIQUE (source_id, key_kind, key_value) over an append-only table, and a repudiation
--      closes the batch's REMITTANCE RESOLVED_BY_ADJUSTMENT without touching its
--      REMITTANCE_REF. The genuine re-presented batch - which carries the SAME remittance
--      reference, since it describes the same payout - opened its REMITTANCE keyless
--      (KEY_COLLISION, then DUPLICATE_INTERNAL), and the reopened bank cash reached only the
--      exhausted repudiated remittance: DUPLICATE, parked, and no kind joins the two.
--
-- WHAT THIS MIGRATION ENFORCES
--   1. suspense_item: one LIVE suspense item per external item
--      (suspense_item_external_item_live, partial on status <> 'RELEASED') - still the
--      ten-way park arbiter's backstop beneath the item's conditional transition - and one
--      RECON_PARK item per (external item, park) (suspense_item_recon_park_once): each park is
--      its own row, so a reopened item parks again, owned by a new break. suspense_item_origin_once
--      keeps its name and its strength for every OTHER origin (BANK_UNATTRIBUTED,
--      UNMATCHED_CONFIRMATION, REPUDIATION: the backfill's and the openers' one arbiter, and a
--      released value answered once); a RECON_PARK item's origin_ref stays its external item, read
--      as it always was. History is not edited: every existing row satisfies the new indexes,
--      since the old uniques were strictly stronger.
--   2. expectation_key.released_by_resolution_id: the one edge an append-only key may take -
--      NULL -> the REPUDIATE_BATCH resolution that closed the keyed REMITTANCE - and only for a
--      REMITTANCE closed RESOLVED_BY_ADJUSTMENT whose batch that resolution repudiates (BEFORE
--      UPDATE, every writer), its resolution APPROVED at commit (a DEFERRABLE INITIALLY DEFERRED
--      constraint trigger: the repudiation's row turns APPROVED as its transaction's last write,
--      V015's reason). expectation_key_once becomes partial on the unreleased keys, so the
--      genuine remittance registers the freed reference; a released key reaches nothing
--      (JdbcMatchingStore's key reads and the rematch predicate skip it).

-- ---------------------------------------------------------------------------------------------
-- 1. One RECON_PARK item per park.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.suspense_item
    DROP CONSTRAINT suspense_item_external_item_once,
    DROP CONSTRAINT suspense_item_origin_once;

-- The ten-way park arbiter's backstop: at most one LIVE suspense item holds an external item.
CREATE UNIQUE INDEX suspense_item_external_item_live
    ON reconciliation.suspense_item (external_item_id)
    WHERE status <> 'RELEASED';

-- Every origin but RECON_PARK keeps V004's once-ness by its origin row (a parking, a bank line,
-- a released value answered by a repudiation) - the openers' ON CONFLICT target.
CREATE UNIQUE INDEX suspense_item_origin_once
    ON reconciliation.suspense_item (origin_ref)
    WHERE origin <> 'RECON_PARK';

-- A RECON_PARK item is once per (external item, park): a reopened item's next park is a new row.
CREATE UNIQUE INDEX suspense_item_recon_park_once
    ON reconciliation.suspense_item (external_item_id, park_id)
    WHERE origin = 'RECON_PARK';

COMMENT ON INDEX reconciliation.suspense_item_external_item_live IS
    'At most one live (OPEN or PARTIALLY_RELEASED) suspense item per external item: the park arbiter''s backstop (V017; V004''s once-ever unique jammed a reopened item).';
COMMENT ON INDEX reconciliation.suspense_item_recon_park_once IS
    'One RECON_PARK item per (external item, park): a bank item a repudiation reopened parks again, owned by a new break (V017).';

-- ---------------------------------------------------------------------------------------------
-- 2. A repudiated remittance's key is released, once, by the repudiation that closed it.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.expectation_key
    ADD COLUMN released_by_resolution_id UUID,
    ADD CONSTRAINT expectation_key_released_by_fk FOREIGN KEY (released_by_resolution_id)
        REFERENCES reconciliation.resolution (id);

ALTER TABLE reconciliation.expectation_key
    DROP CONSTRAINT expectation_key_once;

-- Scoped per source, no kind exempt (V002's A5) - over the keys still bound.
CREATE UNIQUE INDEX expectation_key_once
    ON reconciliation.expectation_key (source_id, key_kind, key_value)
    WHERE released_by_resolution_id IS NULL;

COMMENT ON COLUMN reconciliation.expectation_key.released_by_resolution_id IS
    'The REPUDIATE_BATCH resolution that released this REMITTANCE_REF when it closed the keyed remittance (V017): NULL while the key is bound; set once, never moved.';

-- The append-only rule, with its one edge: the release.
CREATE OR REPLACE FUNCTION reconciliation.expectation_key_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'the key index is append-only for every writer (INV-REC-01): the first writer won, and the record of that is the point';
    END IF;
    IF NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.key_kind IS DISTINCT FROM OLD.key_kind
            OR NEW.key_value IS DISTINCT FROM OLD.key_value
            OR NEW.expectation_id IS DISTINCT FROM OLD.expectation_id
            OR OLD.released_by_resolution_id IS NOT NULL
            OR NEW.released_by_resolution_id IS NULL THEN
        RAISE EXCEPTION 'the key index is append-only for every writer (INV-REC-01): its one edge is a bound key released by the repudiation that closed its remittance (V017)';
    END IF;
    IF NOT EXISTS (
            SELECT 1
              FROM reconciliation.expectation e
              JOIN reconciliation.resolution r ON r.id = NEW.released_by_resolution_id
             WHERE e.id = NEW.expectation_id
               AND e.kind = 'REMITTANCE'
               AND e.status = 'RESOLVED_BY_ADJUSTMENT'
               AND r.kind = 'REPUDIATE_BATCH'
               AND r.settlement_batch_id::text = e.operation_ref) THEN
        RAISE EXCEPTION 'expectation_key_release_is_a_repudiation: only the REPUDIATE_BATCH resolution of a remittance''s own batch releases its key, once that remittance is closed RESOLVED_BY_ADJUSTMENT (ADR-0065 section 10, V017)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reconciliation.expectation_key_release_is_approved()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.released_by_resolution_id IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM reconciliation.resolution r
                 WHERE r.id = NEW.released_by_resolution_id
                   AND r.status = 'APPROVED') THEN
        RAISE EXCEPTION 'expectation_key_release_is_approved: a key is released only by an APPROVED repudiation (ADR-0065 section 10, V017)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER expectation_key_release_is_approved
    AFTER UPDATE OF released_by_resolution_id ON reconciliation.expectation_key
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_key_release_is_approved();

GRANT UPDATE (released_by_resolution_id) ON reconciliation.expectation_key TO finapp_app;
