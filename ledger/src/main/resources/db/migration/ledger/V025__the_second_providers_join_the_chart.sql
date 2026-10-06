-- The second providers are admitted (P9-TSK-026, M9.8; ADR-0078 section 4, ADR-0080; INV-RAIL-04, INV-SET-05,
-- INV-LED-06): fx-sim-b and corridor-sim-b, each its own registry row and its own clearing accounts.
--
-- WHAT THEIR POSITIONS ARE
--   fx-sim-b's FX_PROVIDER_CLEARING (an ASSET, DEBIT-normal, as fx-sim-a's, V022): what fx-sim-b owes the
--   platform, per currency it settles - EUR and USD, FxProviderDeclaration's settledCurrencies.
--   corridor-sim-b's CORRIDOR_CLEARING (a LIABILITY, CREDIT-normal, as corridor-sim-a's, V024): what the
--   platform owes corridor-sim-b, per currency its rail carries - USD only.
--   Each is discharged only by its own counterparty's declared source (settlement V017): never another
--   counterparty's evidence, never a shared account, and NOTHING NETS - fx-sim-b's position is not offset
--   against fx-sim-a's, nor corridor-sim-b's against corridor-sim-a's.
--
-- WHY THIS MIGRATION SEEDS THE ACCOUNTS
--   "Every counterparty clearing account is seeded by the migration that admits its counterparty" (ADR-0078
--   section 4): hand-minted UUIDv7 literals stamped 2026-09-27T12:00:00Z (01a0e2bc-8200), below the
--   2026-09-28T00:00:00Z ceiling. CounterpartyChartGuard refuses startup if any is missing.
--
-- WHY NOTHING ELSE CHANGES
--   No purpose is new - FX_PROVIDER_CLEARING (V021) and CORRIDOR_CLEARING (V024) are already admitted and
--   already reconciled positions - so no rule is recreated and the free-adjustment binding stands as V024
--   stated it.

INSERT INTO ledger.counterparty (id, code, kind) VALUES
    ('01a0e2bc-8200-7025-8000-000000000001', 'fx-sim-b', 'FX_PROVIDER'),
    ('01a0e2bc-8200-7025-8000-000000000002', 'corridor-sim-b', 'CORRIDOR_PROVIDER');

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code, status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7025-8000-000000000101', 'ASSET',     'DEBIT',  'EUR', 'COUNTERPARTY', '01a0e2bc-8200-7025-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7025-8000-000000000102', 'ASSET',     'DEBIT',  'USD', 'COUNTERPARTY', '01a0e2bc-8200-7025-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7025-8000-000000000201', 'LIABILITY', 'CREDIT', 'USD', 'COUNTERPARTY', '01a0e2bc-8200-7025-8000-000000000002', 'CORRIDOR_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');
