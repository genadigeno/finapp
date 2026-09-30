-- P8-TSK-018: the payout provider's report - its line vocabulary and its reference
-- (ADR-0065 section 2, ADR-0066 section 8; INV-SET-05, INV-PAY-03).
--
-- WHY A SETTLEMENT MIGRATION AT ALL
--   The P8-TSK-018 backlog entry recorded "Persistence: none new". It did not hold: V003
--   admitted the PSP report's vocabulary, V005 the bank statement's and V006 the scheme's - no
--   migration ever admitted the payout provider's lines or its reference. They arrive here with
--   their first producer, SIM_PAYOUT_CSV v1:
--     PAYOUT_EXECUTED - a payout the provider executed (OUTBOUND, allocating);
--     PAYOUT_RETURNED - a payout the beneficiary bank returned (INBOUND, allocating);
--     PAYOUT_FEE      - the provider's own charge, recognised at acceptance like the PSP's and
--                       the scheme's fees (its own type: its original is a payout, reached by
--                       PAYOUT_PROVIDER_REF - the P8-TSK-017 precedent of SCHEME_FEE);
--     PAYOUT_PROVIDER_REF - the provider's reference, the MERCHANT_PAYOUT expectation's first key.
--
-- The new values are appended (declaration order): the enums' sqlValueList() is the whole list,
-- the migration tests reconcile it.

ALTER TABLE settlement.batch_total
    DROP CONSTRAINT batch_total_line_type,
    ADD CONSTRAINT batch_total_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE'));

ALTER TABLE settlement.line
    DROP CONSTRAINT line_type,
    ADD CONSTRAINT line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE'));

-- The payout provider's reference, under V003's shape CHECKs (no account shape, no alias shape)
-- exactly as every other reference.
ALTER TABLE settlement.line_reference
    DROP CONSTRAINT line_reference_kind,
    ADD CONSTRAINT line_reference_kind CHECK (kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF'));
