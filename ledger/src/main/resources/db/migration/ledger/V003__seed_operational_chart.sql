-- The operational chart: the platform's own accounts, seeded by migration (P3-TSK-003).
--
-- WHY A MIGRATION AND NOT APPLICATION STARTUP
--   A double entry needs both sides, so these rows must exist before anything can post - and an
--   operational account created at run time is one whose existence depends on which instance
--   started first. A migration is the reviewed, immutable channel (ADR-0011, the consent_text
--   precedent), serialised by Flyway's own lock, identical in every environment.
--
-- ONE ROW PER OPERATIONAL PURPOSE PER SUPPORTED CURRENCY
--   The supported set is SupportedCurrencies.ALL (EUR, GBP, USD) - the one definition, and
--   OperationalChartMigrationTest refuses the build if this file and that set disagree in
--   either direction. Growing the set is a NEW seed migration in the same change that widens
--   the list. V002's partial unique index (purpose, currency) WHERE owner_ref IS NULL is what
--   makes ChartOfAccounts.resolve unambiguous - and INV-BAL-03's "designated account" stay
--   designated.
--
-- THE IDS ARE HAND-MINTED UUIDv7 LITERALS, DELIBERATELY
--   gen_random_uuid() is version 4, and LedgerAccountId.of validates version 7 (ADR-0013) - a
--   v4 seed would read back as malformed. Fixed literals are also deterministic across every
--   environment, which for operational accounts is a feature: a runbook or a dashboard can name
--   them. The embedded timestamp is the authoring instant, which is true.
--
-- THE TIMESTAMPS ARE LITERALS, NEVER now()
--   DOMAIN_MODEL.md §Time: a clock read is not a business fact - and a seed whose created_at
--   varies per environment records the deployment schedule, not the chart.
--
-- THE TYPES ARE THE SEED'S DECISION, with the reasoning here and the pairs pinned by test:
--   SETTLEMENT_CLEARING  ASSET     value in flight, due from an external counterparty
--   FEE_REVENUE          REVENUE   fees earned (Phase 6's seam; nothing posts to it yet)
--   FX_POSITION          ASSET     the platform's conversion position (Phase 9's seam)
--   ROUNDING_RESIDUAL    EXPENSE   allocation residue, posted and never absorbed (INV-BAL-03);
--                                  a residual in the platform's favour posts as a credit here,
--                                  which an EXPENSE account carries without ambiguity
--   SUSPENSE_UNMATCHED   LIABILITY value we hold that is not (yet) ours is value owed to
--                                  somebody (INV-REC-05); parked, aged, never a resting place
--
-- normal_balance is AccountType.normalBalance()'s derivation, and V002's coherence CHECK
-- refuses this seed if any pair below disagrees with it.

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a09b66-b58f-7715-bcb6-87073a1b6b52', 'ASSET',     'DEBIT',  'EUR', 'OPERATIONAL', NULL, 'SETTLEMENT_CLEARING', NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-72d9-9610-6b7125645a7b', 'ASSET',     'DEBIT',  'GBP', 'OPERATIONAL', NULL, 'SETTLEMENT_CLEARING', NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-7f60-8a8d-dbb46c5912af', 'ASSET',     'DEBIT',  'USD', 'OPERATIONAL', NULL, 'SETTLEMENT_CLEARING', NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-79be-b9db-835d27b3eb17', 'REVENUE',   'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'FEE_REVENUE',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-7734-9a3e-475a8233c3db', 'REVENUE',   'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'FEE_REVENUE',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-7028-a884-e1baa1e36f03', 'REVENUE',   'CREDIT', 'USD', 'OPERATIONAL', NULL, 'FEE_REVENUE',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-70a9-9c02-0bcfc04fa05b', 'ASSET',     'DEBIT',  'EUR', 'OPERATIONAL', NULL, 'FX_POSITION',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-74b3-9733-8c2e7882e1dd', 'ASSET',     'DEBIT',  'GBP', 'OPERATIONAL', NULL, 'FX_POSITION',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-75d9-aaea-352cba618066', 'ASSET',     'DEBIT',  'USD', 'OPERATIONAL', NULL, 'FX_POSITION',         NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-77c8-be29-be7060592839', 'EXPENSE',   'DEBIT',  'EUR', 'OPERATIONAL', NULL, 'ROUNDING_RESIDUAL',   NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b58f-7cf4-b403-059badcd358b', 'EXPENSE',   'DEBIT',  'GBP', 'OPERATIONAL', NULL, 'ROUNDING_RESIDUAL',   NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b590-75b6-9c2c-8839ccf4037b', 'EXPENSE',   'DEBIT',  'USD', 'OPERATIONAL', NULL, 'ROUNDING_RESIDUAL',   NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b590-76f6-9420-68dda2a8a71f', 'LIABILITY', 'CREDIT', 'EUR', 'SUSPENSE',    NULL, 'SUSPENSE_UNMATCHED',  NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b590-765b-8f19-6e7d6dbe00f3', 'LIABILITY', 'CREDIT', 'GBP', 'SUSPENSE',    NULL, 'SUSPENSE_UNMATCHED',  NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z'),
    ('01a09b66-b590-7fe9-b8f3-24d23a9db873', 'LIABILITY', 'CREDIT', 'USD', 'SUSPENSE',    NULL, 'SUSPENSE_UNMATCHED',  NULL, 'ACTIVE', '2026-09-13T00:00:00Z', '2026-09-13T00:00:00Z');
