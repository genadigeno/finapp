-- P8-TSK-023: the repudiated batch - the batch machine's one designed exit from ACCEPTED (ADR-0065
-- point 10; SETTLEMENT_AND_RECONCILIATION_LIFECYCLES section 5.2; INV-HIST-01, INV-HIST-02,
-- INV-SET-04).
--
-- WHY A SETTLEMENT MIGRATION
--   An accepted batch proven fabricated or mis-normalised leaves the books by an approved
--   four-eyes REPUDIATE_BATCH resolution: its recognition reversed, its items and allocations
--   countered in reconciliation, and the batch itself moved ACCEPTED -> REPUDIATED. REPUDIATED
--   exists now because its producer does. The CHECK and the transition rule are BatchStatus's
--   mirrors, regenerated, reconciled by the migration test.
--
-- WHAT DOES NOT CHANGE
--   The live uniques (V003's batch_live_identity, V005's batch_live_statement_sequence) were
--   written whole, excluding REJECTED and REPUDIATED already: the repudiated batch frees its
--   identity at commit and the genuine file is admitted as a new batch, with no index touched.
--   batch_event's status vocabulary (V003) names REPUDIATED and ACCEPTED as a from_status
--   already. The application role already holds UPDATE (status, status_changed_at) - the edge
--   needs no grant. The FILE does not move: it stays ACCEPTED, its bytes retained byte-identical
--   (INV-HIST-02) - only the batch's verdict changes, and settlement.file is untouched here.
--
-- A REPUDIATED BATCH WAS ACCEPTED
--   Its acceptance facts (source_sequence, accepted_on, journal_entry_id, posting_omitted) are
--   history, never edited (INV-HIST-01): the recognition entry they name stands, now reversed,
--   and the sequence number stays spent. So the honesty CHECKs widen to hold the repudiated row
--   to them too, and the trigger keeps them frozen across the repudiation edge - restated from
--   V005 with only the generated edge rule and its message changed.

-- -----------------------------------------------------------------------------------------------
-- The vocabulary: BatchStatus.sqlValueList.
-- -----------------------------------------------------------------------------------------------
ALTER TABLE settlement.batch DROP CONSTRAINT batch_status;
ALTER TABLE settlement.batch ADD CONSTRAINT batch_status CHECK (status IN (
    'PARSED', 'ACCEPTED', 'REJECTED', 'REPUDIATED'));

-- -----------------------------------------------------------------------------------------------
-- The honesty rules hold the repudiated row as they held it accepted (V004's, widened).
-- -----------------------------------------------------------------------------------------------
ALTER TABLE settlement.batch DROP CONSTRAINT batch_accepted_carries_its_facts;
ALTER TABLE settlement.batch ADD CONSTRAINT batch_accepted_carries_its_facts CHECK (
    status NOT IN ('ACCEPTED', 'REPUDIATED')
        OR (source_sequence IS NOT NULL AND accepted_on IS NOT NULL));
ALTER TABLE settlement.batch DROP CONSTRAINT batch_posting_omitted_is_honest;
ALTER TABLE settlement.batch ADD CONSTRAINT batch_posting_omitted_is_honest CHECK (
    status NOT IN ('ACCEPTED', 'REPUDIATED') OR ((journal_entry_id IS NULL) = posting_omitted));

-- -----------------------------------------------------------------------------------------------
-- The transition rule: BatchStatus.sqlTransitionRule, the machine whole (V005's function
-- re-stated; nothing else in it moves).
-- -----------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION settlement.batch_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.file_id IS DISTINCT FROM OLD.file_id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.external_batch_ref IS DISTINCT FROM OLD.external_batch_ref
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.format_id IS DISTINCT FROM OLD.format_id
            OR NEW.format_version IS DISTINCT FROM OLD.format_version
            OR NEW.line_count IS DISTINCT FROM OLD.line_count
            OR NEW.declared_line_count IS DISTINCT FROM OLD.declared_line_count
            OR NEW.net_minor IS DISTINCT FROM OLD.net_minor
            OR NEW.net_scale IS DISTINCT FROM OLD.net_scale
            OR NEW.remittance_reference IS DISTINCT FROM OLD.remittance_reference
            OR NEW.statement_sequence IS DISTINCT FROM OLD.statement_sequence
            OR NEW.opening_minor IS DISTINCT FROM OLD.opening_minor
            OR NEW.closing_minor IS DISTINCT FROM OLD.closing_minor
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a settlement batch''s parse statement is frozen (INV-SET-07): a correction is a new batch in a later file, never an edit';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PARSED' AND NEW.status IN ('ACCEPTED', 'REJECTED')) OR (OLD.status = 'ACCEPTED' AND NEW.status IN ('REPUDIATED'))) THEN
        RAISE EXCEPTION 'not a settlement batch edge: % -> % (section 5.2; the machine is complete since P8-TSK-023)',
            OLD.status, NEW.status;
    END IF;
    -- The acceptance facts are set once - with the ACCEPTED edge - and never move, the
    -- repudiation edge included (INV-SET-04: re-acceptance converges instead of re-stating;
    -- INV-HIST-01: a repudiated batch keeps what its acceptance recorded).
    IF OLD.source_sequence IS NOT NULL
            AND (NEW.source_sequence IS DISTINCT FROM OLD.source_sequence
                OR NEW.accepted_on IS DISTINCT FROM OLD.accepted_on
                OR NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
                OR NEW.posting_omitted IS DISTINCT FROM OLD.posting_omitted) THEN
        RAISE EXCEPTION 'a batch''s acceptance facts are recorded once (INV-SET-04, P8-TSK-009)';
    END IF;
    IF OLD.source_sequence IS NULL AND NEW.source_sequence IS NOT NULL
            AND NEW.status <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'the acceptance facts arrive only with the ACCEPTED edge (P8-TSK-009)';
    END IF;
    RETURN NEW;
END;
$$;
