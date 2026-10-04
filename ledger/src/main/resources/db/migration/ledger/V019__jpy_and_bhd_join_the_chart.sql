-- JPY and BHD become postable: the operational chart in two more currencies (P9-TSK-003,
-- ADR-0074 section 9; INV-ACC-01, INV-BAL-03, INV-MON-05).
--
-- "Supported" means POSTABLE (SupportedCurrencies' own doctrine): a currency carries the FULL
-- operational chart, because a posting in a currency with no residual account has nowhere to put
-- what allocation leaves over (INV-BAL-03), and one with no suspense account gives
-- reconciliation nowhere to park what it cannot match (INV-REC-05). So the thirteen operational
-- purposes - every purpose but the two owned ones, CUSTOMER_WALLET and MERCHANT_PAYABLE, which
-- are opened per owner and never seeded - arrive for JPY (0 minor units) and BHD (3) together,
-- in one migration, never one purpose at a time.
--
-- WHY NOW AND NOT BEFORE
--   ADR-0074 D27: the minor units are PINNED first - SupportedCurrencies.PINNED_MINOR_UNITS, its
--   test and the startup guard (P9-TSK-002) - so no JDK that moved either currency's minor units
--   can ever post one. Only then does a 0- or 3-minor-unit currency become postable.
--
-- WHY ROWS ONLY
--   No purpose is added, so no CHECK the chart's enums feed is re-stated; and no constraint on
--   ledger_account names a currency (only its shape), so nothing else changes. The scale of every
--   amount posted to these accounts is the currency's own, carried by each journal line (V004's
--   one-scale-per-currency-per-entry rule holds it).
--
-- THE SEED ROWS
--   Thirteen per currency, hand-minted UUIDv7 literals stamped 2026-09-27T12:00:00Z
--   (01a0e2bc-8200) - below the 2026-09-28T00:00:00Z ceiling, so every seed still sorts before
--   every runtime id (OperationalChartMigrationTest#everySeededIdSortsBeforeEveryRuntimeId). The
--   types, normal balances and owner kinds are the pinned contract (SEEDED_TYPES), the same as
--   EUR's, GBP's and USD's. No account_balance rows: the projection's upsert creates each on its
--   first posting.

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7019-8000-000000000001', 'ASSET',     'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'SETTLEMENT_CLEARING',    NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000002', 'LIABILITY', 'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'PAYOUT_CLEARING',        NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000003', 'ASSET',     'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'INSTANT_CLEARING',       NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000004', 'ASSET',     'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'CASH_AT_BANK',           NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000005', 'ASSET',     'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'CHARGEBACK_RECOVERABLE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000006', 'EXPENSE',   'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'DISPUTE_COSTS',          NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000007', 'EXPENSE',   'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'PROCESSING_COSTS',       NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000008', 'EXPENSE',   'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'RECONCILIATION_LOSSES',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000009', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'FEE_REVENUE',            NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000a', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'RECONCILIATION_GAINS',   NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000b', 'ASSET',     'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'FX_POSITION',            NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000c', 'EXPENSE',   'DEBIT',  'JPY', 'OPERATIONAL', NULL, 'ROUNDING_RESIDUAL',      NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000d', 'LIABILITY', 'CREDIT', 'JPY', 'SUSPENSE',    NULL, 'SUSPENSE_UNMATCHED',     NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000e', 'ASSET',     'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'SETTLEMENT_CLEARING',    NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000000f', 'LIABILITY', 'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'PAYOUT_CLEARING',        NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000010', 'ASSET',     'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'INSTANT_CLEARING',       NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000011', 'ASSET',     'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'CASH_AT_BANK',           NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000012', 'ASSET',     'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'CHARGEBACK_RECOVERABLE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000013', 'EXPENSE',   'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'DISPUTE_COSTS',          NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000014', 'EXPENSE',   'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'PROCESSING_COSTS',       NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000015', 'EXPENSE',   'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'RECONCILIATION_LOSSES',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000016', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'FEE_REVENUE',            NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000017', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'RECONCILIATION_GAINS',   NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000018', 'ASSET',     'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'FX_POSITION',            NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-000000000019', 'EXPENSE',   'DEBIT',  'BHD', 'OPERATIONAL', NULL, 'ROUNDING_RESIDUAL',      NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7019-8000-00000000001a', 'LIABILITY', 'CREDIT', 'BHD', 'SUSPENSE',    NULL, 'SUSPENSE_UNMATCHED',     NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');
