-- The FX provider's trade report joins the settlement vocabulary, and a currency its counterparty does
-- not settle is refused (P9-TSK-011; ADR-0078 section 7, PHASE_9_PLAN.md section 12.9.2; INV-SET-05,
-- INV-SET-07).
--
-- WHAT IS APPENDED (declaration order; each list is its enum's whole sqlValueList(), reconciled by
-- SettlementV015MigrationTest)
--   SourceKind         + FX_PROVIDER_REPORT
--   SettlementFormatId + SIM_FX_CSV (the format is SimFxCsvFormat v1)
--   SettlementLineType + FX_SOLD, FX_BOUGHT, FX_FEE (FX_FEE a report fee, recognised at acceptance)
--   LineReferenceKind  + COVER_REF (the platform's cover reference, the key), FX_TRADE_REF (an alias)
--   RejectionCode      + CURRENCY_NOT_SETTLED: a parsed batch in a currency the source's counterparty
--                        does not settle has no position to land on - refused at the parse leg and
--                        RETAINED, never accepted, never posted, and never readmitted.
--
-- THE SOURCE ROW
--   fx-sim-a.trade-report, seeded like V002's four (a hand-minted UUIDv7 stamped 2026-09-27T12:00:00Z,
--   below the seed ceiling). Its position, counterparty and settled currencies are compiled facts on its
--   descriptor (read off FxProviderDeclaration in app), never columns here (ADR-0064).
--
-- THE READMISSION RULE, RE-STATED (V011)
--   A readmission names a REJECTED original whose verdict is recoverable - never SOURCE_RETIRED and now
--   never CURRENCY_NOT_SETTLED - or an ACCEPTED one whose batch is REPUDIATED.

ALTER TABLE settlement.source
    DROP CONSTRAINT source_kind,
    ADD CONSTRAINT source_kind CHECK (kind IN (
        'PSP_SETTLEMENT_REPORT', 'SCHEME_CYCLE_REPORT', 'PAYOUT_PROVIDER_REPORT', 'BANK_STATEMENT', 'FX_PROVIDER_REPORT'));

ALTER TABLE settlement.file
    DROP CONSTRAINT file_format,
    ADD CONSTRAINT file_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV'));

ALTER TABLE settlement.batch
    DROP CONSTRAINT batch_format,
    ADD CONSTRAINT batch_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV'));

ALTER TABLE settlement.file DROP CONSTRAINT file_rejection_code;
ALTER TABLE settlement.file ADD CONSTRAINT file_rejection_code CHECK (
    rejection_code IS NULL OR rejection_code IN (
        'MALFORMED', 'CONTROL_TOTAL_MISMATCH', 'UNKNOWN_CURRENCY', 'SCALE_MISMATCH', 'UNSUPPORTED_FORMAT', 'CONFLICTING_BATCH', 'DECLINED', 'SOURCE_RETIRED', 'CURRENCY_NOT_SETTLED'));

ALTER TABLE settlement.batch_total
    DROP CONSTRAINT batch_total_line_type,
    ADD CONSTRAINT batch_total_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE', 'FX_SOLD', 'FX_BOUGHT', 'FX_FEE'));

ALTER TABLE settlement.line
    DROP CONSTRAINT line_type,
    ADD CONSTRAINT line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE', 'FX_SOLD', 'FX_BOUGHT', 'FX_FEE'));

ALTER TABLE settlement.line_reference
    DROP CONSTRAINT line_reference_kind,
    ADD CONSTRAINT line_reference_kind CHECK (kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'COVER_REF', 'FX_TRADE_REF'));

INSERT INTO settlement.source (id, code, kind, status, next_sequence) VALUES
    ('01a0e2bc-8200-7005-8000-000000000005', 'fx-sim-a.trade-report', 'FX_PROVIDER_REPORT', 'ACTIVE', 1);

-- The readmission rule (V011), re-stated: the trigger binding stands unchanged.
CREATE OR REPLACE FUNCTION settlement.file_readmits_a_recoverable_original()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.received_via IS DISTINCT FROM 'READMISSION' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (
            SELECT 1
              FROM settlement.file o
             WHERE o.id = NEW.readmits_file_id
               AND ((o.status = 'REJECTED'
                     AND o.rejection_code NOT IN ('SOURCE_RETIRED', 'CURRENCY_NOT_SETTLED'))
                    OR (o.status = 'ACCEPTED'
                        AND EXISTS (
                            SELECT 1 FROM settlement.batch b
                             WHERE b.file_id = o.id AND b.status = 'REPUDIATED')))) THEN
        RAISE EXCEPTION USING MESSAGE =
            'file_readmits_a_recoverable_original: a readmission names a REJECTED original'
            || ' (never SOURCE_RETIRED or CURRENCY_NOT_SETTLED) or an ACCEPTED one whose batch is REPUDIATED'
            || ' (INV-SET-07, ADR-0065 section 10)';
    END IF;
    RETURN NEW;
END;
$$;
