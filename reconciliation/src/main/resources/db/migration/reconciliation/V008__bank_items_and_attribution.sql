-- P8-TSK-016: bank items - the bank line vocabulary, attribution on the working copy, and the
-- value-date group's keyless candidates (ADR-0065 section 3, ADR-0068 sections 1-3; INV-SET-05,
-- INV-REC-06, INV-REC-09).
--
-- WHY A RECONCILIATION MIGRATION AT ALL
--   The P8-TSK-016 backlog entry recorded "no reconciliation migration". The rule table (V002)
--   already named the bank lines and REMITTANCE_REF, but the external item it copies from a
--   settlement line did not: its line type and key kind CHECKs lacked the bank members, its
--   position was NOT NULL and it had no attribution. This migration is the recorded deviation.
--   It takes the number V008, which the transition's plan had reserved for P8-TSK-022's
--   run_replay; that moves to V009 and P8-TSK-023's repudiation to V010 (recorded in the plan).
--
-- ATTRIBUTION ON THE WORKING COPY
--   A bank credit or debit is attributed at parse to the unique source whose remittance pattern
--   matches (settlement.line.attributed_source_id). Its working copy carries that source - the
--   KEY SCOPE its REMITTANCE_REF is looked up under (ADR-0068 section 1: keys are per source;
--   "same source" for a bank item means its attributed source) - and that source's clearing
--   position, the one its recognition credited. An unattributed bank line carries neither: it is
--   born PARKED at acceptance with its BANK_UNATTRIBUTED suspense item and its break. A bank fee
--   carries neither too: its effect is the recognition's PROCESSING_COSTS line.
--
-- A KEYLESS CANDIDATE
--   A GROUP_BY_VALUE_DATE decision reaches its candidates by the value date, not a key: its
--   candidate rows carry key_kind NULL (the only rows that may).

ALTER TABLE reconciliation.external_item
    ADD COLUMN attributed_source_id UUID,
    ALTER COLUMN position_purpose DROP NOT NULL,
    DROP CONSTRAINT external_item_line_type,
    ADD CONSTRAINT external_item_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE')),
    -- A report line always stands in its source's position and is never attributed; a bank
    -- credit or debit stands in a position exactly when it is attributed; a bank fee in neither.
    ADD CONSTRAINT external_item_position_rule CHECK (
        (line_type NOT IN ('BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE')
            AND position_purpose IS NOT NULL AND attributed_source_id IS NULL)
        OR (line_type IN ('BANK_CREDIT', 'BANK_DEBIT')
            AND (position_purpose IS NULL) = (attributed_source_id IS NULL))
        OR (line_type = 'BANK_FEE'
            AND position_purpose IS NULL AND attributed_source_id IS NULL));

CREATE INDEX external_item_by_attribution
    ON reconciliation.external_item (attributed_source_id, status)
    WHERE attributed_source_id IS NOT NULL;

COMMENT ON COLUMN reconciliation.external_item.attributed_source_id IS
    'A bank credit or debit''s attributed source (settlement.line.attributed_source_id, copied): the key scope its REMITTANCE_REF and value-date group are judged in (ADR-0068 section 1). NULL for report lines, bank fees and unattributed bank lines.';
COMMENT ON COLUMN reconciliation.external_item.position_purpose IS
    'The clearing position this item''s value stands in: its source''s declared position for a report line, the attributed source''s for a bank line; NULL for an unattributed bank line (parked at acceptance) and a bank fee (the recognition''s PROCESSING_COSTS line is its effect).';

-- The copied line stays frozen, its attribution with it (V003's function re-stated).
CREATE OR REPLACE FUNCTION reconciliation.external_item_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
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
    IF NEW.status IS DISTINCT FROM OLD.status
            AND NOT ((OLD.status = 'PENDING' AND NEW.status IN ('MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED')) OR (OLD.status = 'MATCHED' AND NEW.status IN ('UNMATCHED', 'REPUDIATED')) OR (OLD.status = 'CHECKED' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'OFFSET' AND NEW.status IN ('REPUDIATED')) OR (OLD.status = 'UNMATCHED' AND NEW.status IN ('MATCHED', 'PARKED', 'REPUDIATED')) OR (OLD.status = 'PARKED' AND NEW.status IN ('MATCHED', 'RESOLVED', 'REPUDIATED'))) THEN
        RAISE EXCEPTION 'not an external item edge: % -> % (section 5.4)',
            OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- ItemKeyKind.sqlValueList - the bank line's structured remittance reference.
ALTER TABLE reconciliation.external_item_key
    DROP CONSTRAINT external_item_key_kind,
    ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF'));

-- A value-date group's candidates carry no key (ADR-0068 section 3).
ALTER TABLE reconciliation.match_candidate
    ALTER COLUMN key_kind DROP NOT NULL;

COMMENT ON COLUMN reconciliation.match_candidate.key_kind IS
    'The key that reached the candidate; NULL exactly for a GROUP_BY_VALUE_DATE candidate, reached by its value date (P8-TSK-016).';
