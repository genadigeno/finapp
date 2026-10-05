-- The corridor provider's settlement report joins the settlement vocabulary (P9-TSK-014; ADR-0082,
-- PHASE_9_PLAN.md section 12.9.2; INV-SET-05).
--
-- WHAT IS APPENDED (declaration order; each list is its enum's whole sqlValueList(), reconciled by
-- SettlementV016MigrationTest)
--   SettlementFormatId + SIM_CORRIDOR_CSV (the format is SimCorridorCsvFormat v1), a
--                        PAYOUT_PROVIDER_REPORT by kind - SettlementSources keys by code and position,
--                        so the corridor needs no new SourceKind; it reuses PAYOUT_EXECUTED,
--                        PAYOUT_RETURNED and PAYOUT_FEE and the references END_TO_END_REF and
--                        PAYOUT_PROVIDER_REF, so no other vocabulary changes.
--
-- THE SOURCE ROW
--   corridor-sim-a.settlement, seeded like V015's (a hand-minted UUIDv7 stamped 2026-09-27T12:00:00Z,
--   below the seed ceiling). Its position (CORRIDOR_CLEARING), counterparty (corridor-sim-a) and settled
--   currencies (USD, JPY, BHD) are compiled facts on its descriptor - read off the corridor rail's
--   declaration in app - never columns here (ADR-0064). A file in any other currency is REJECTED
--   CURRENCY_NOT_SETTLED at the parse leg (V015's verdict).

ALTER TABLE settlement.file
    DROP CONSTRAINT file_format,
    ADD CONSTRAINT file_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV', 'SIM_CORRIDOR_CSV'));

ALTER TABLE settlement.batch
    DROP CONSTRAINT batch_format,
    ADD CONSTRAINT batch_format CHECK (format_id IN (
        'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV', 'SIM_CORRIDOR_CSV'));

INSERT INTO settlement.source (id, code, kind, status, next_sequence) VALUES
    ('01a0e2bc-8200-7005-8000-000000000006', 'corridor-sim-a.settlement', 'PAYOUT_PROVIDER_REPORT', 'ACTIVE', 1);
