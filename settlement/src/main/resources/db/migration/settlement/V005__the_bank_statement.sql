-- P8-TSK-016: the bank statement - its continuity facts, its line vocabulary, the live
-- statement-sequence arbiter and the attribution rule (ADR-0065 section 3, ADR-0066 sections 3
-- and 5; INV-SET-06, INV-SET-05, INV-RAIL-03).
--
-- WHY A SETTLEMENT MIGRATION AT ALL
--   The P8-TSK-016 backlog entry recorded "no settlement migration: the statement columns and the
--   live statement-sequence unique already exist (V003)". They did not - only
--   line.attributed_source_id did. This migration is the recorded deviation: the columns, the
--   unique and the bank vocabulary arrive with their first producer.
--
-- A BATCH IS A REPORT OR A STATEMENT, EXACTLY ONE
--   A counterparty's report carries its remittance reference and no statement facts; a bank
--   statement carries its sequence and its signed opening and closing balances (scale net_scale,
--   a debit balance negative) and no remittance reference of its own - its lines carry the
--   references. The facts are the parse's words, frozen with the rest of the parse statement.
--
-- ONE ACCEPTED OR PENDING STATEMENT PER SEQUENCE PER ACCOUNT CURRENCY
--   The partial unique is the arbiter any writer converges on: a second statement of a sequence
--   already live is refused at parse (REJECTED(CONFLICTING_BATCH), retained - ADR-0066 section 5),
--   exactly as the live batch identity is.

-- -----------------------------------------------------------------------------------------------
-- The statement's facts on the batch.
-- -----------------------------------------------------------------------------------------------
ALTER TABLE settlement.batch
    ADD COLUMN statement_sequence BIGINT,
    ADD COLUMN opening_minor      BIGINT,
    ADD COLUMN closing_minor      BIGINT,
    ALTER COLUMN remittance_reference DROP NOT NULL,
    ADD CONSTRAINT batch_statement_facts_whole CHECK (
        (statement_sequence IS NULL) = (opening_minor IS NULL)
            AND (statement_sequence IS NULL) = (closing_minor IS NULL)),
    ADD CONSTRAINT batch_statement_iff_bank_format CHECK (
        (statement_sequence IS NOT NULL) = (format_id = 'SIM_STATEMENT_TAGGED')),
    ADD CONSTRAINT batch_remittance_iff_report CHECK (
        (remittance_reference IS NULL) = (statement_sequence IS NOT NULL)),
    ADD CONSTRAINT batch_statement_sequence_positive CHECK (statement_sequence >= 1),
    ADD CONSTRAINT batch_statement_net_is_its_balances CHECK (
        statement_sequence IS NULL OR net_minor = closing_minor - opening_minor);

CREATE UNIQUE INDEX batch_live_statement_sequence
    ON settlement.batch (source_id, currency, statement_sequence)
    WHERE statement_sequence IS NOT NULL AND status NOT IN ('REJECTED', 'REPUDIATED');

COMMENT ON COLUMN settlement.batch.statement_sequence IS
    'A bank statement''s place in its account''s chain (:28C:), per currency - the continuity rule''s (INV-SET-06) and the live unique''s key; NULL for a report.';
COMMENT ON COLUMN settlement.batch.opening_minor IS
    'A statement''s opening balance, signed (a debit balance negative), scale net_scale; NULL for a report.';
COMMENT ON COLUMN settlement.batch.closing_minor IS
    'A statement''s closing balance, signed, scale net_scale - what CASH_AT_BANK equals at the head of an unbroken chain; NULL for a report.';

-- The parse statement stays frozen, the statement facts with it (V004's function re-stated).
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
            AND NOT ((OLD.status = 'PARSED' AND NEW.status IN ('ACCEPTED', 'REJECTED'))) THEN
        RAISE EXCEPTION 'not a settlement batch edge: % -> % (section 5.2; REPUDIATED arrives with P8-TSK-023)',
            OLD.status, NEW.status;
    END IF;
    -- The acceptance facts are set once - with the ACCEPTED edge - and never move
    -- (INV-SET-04: re-acceptance converges instead of re-stating).
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

-- -----------------------------------------------------------------------------------------------
-- The bank vocabulary (SettlementLineType, LineReferenceKind; the migration test reconciles).
-- -----------------------------------------------------------------------------------------------
ALTER TABLE settlement.batch_total
    DROP CONSTRAINT batch_total_line_type,
    ADD CONSTRAINT batch_total_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE'));

ALTER TABLE settlement.line
    DROP CONSTRAINT line_type,
    ADD CONSTRAINT line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE')),
    -- Attribution is a bank line's alone (ADR-0065 section 3): a credit or debit the unique
    -- matching remittance pattern names; a fee is the bank's own and names nobody.
    ADD CONSTRAINT line_attribution_is_a_bank_line CHECK (
        attributed_source_id IS NULL OR line_type IN ('BANK_CREDIT', 'BANK_DEBIT'));

ALTER TABLE settlement.line_reference
    DROP CONSTRAINT line_reference_kind,
    ADD CONSTRAINT line_reference_kind CHECK (kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF'));

COMMENT ON COLUMN settlement.line.attributed_source_id IS
    'The unique declared source whose remittance pattern matches this bank line''s REMITTANCE_REF (ADR-0065 section 3) - written at parse, deterministic, pinned by the compiled register; NULL for a report line, a bank fee, or a bank line zero or two patterns match (parked at acceptance with its break).';
