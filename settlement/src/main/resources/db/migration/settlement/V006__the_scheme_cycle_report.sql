-- P8-TSK-017: the instant scheme's cycle report - its line vocabulary and its references
-- (ADR-0065 section 2, ADR-0066 section 8; INV-RAIL-04, INV-PAY-03).
--
-- WHY A SETTLEMENT MIGRATION AT ALL
--   The P8-TSK-017 backlog entry recorded "Persistence: none new", naming the SCHEME_REF,
--   END_TO_END_REF and SETTLEMENT_CYCLE reference kinds as settlement V003's. They were not:
--   V003 admitted the PSP report's vocabulary alone, and V005 the bank statement's. This
--   migration is the recorded deviation - the scheme's three line types and its two reference
--   kinds arrive with their first producer, SIM_SCHEME_JSON v1.
--
-- THE CYCLE IS NOT A LINE REFERENCE
--   A v1 cycle report carries ONE cycle token, and that token IS the batch's external_batch_ref -
--   the live identity (source, external_batch_ref, currency) already keys on it, so a second
--   report of one cycle is CONFLICTING_BATCH like any repeated batch. Reconciliation copies it to
--   the run (reconciliation V009), never into a line reference, which the matcher would read as a
--   key.
--
-- The new values are appended (declaration order): the enums' sqlValueList() is the whole list,
-- the migration tests reconcile it.

ALTER TABLE settlement.batch_total
    DROP CONSTRAINT batch_total_line_type,
    ADD CONSTRAINT batch_total_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE'));

ALTER TABLE settlement.line
    DROP CONSTRAINT line_type,
    ADD CONSTRAINT line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE'));

-- The scheme's reference and the platform's end-to-end reference, under V003's shape CHECKs
-- (no account shape, no alias shape) exactly as every other reference.
ALTER TABLE settlement.line_reference
    DROP CONSTRAINT line_reference_kind,
    ADD CONSTRAINT line_reference_kind CHECK (kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF'));
