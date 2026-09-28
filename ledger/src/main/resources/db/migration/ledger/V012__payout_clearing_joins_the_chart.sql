-- PAYOUT_CLEARING joins the chart of accounts (P6-TSK-012, ADR-0051 section 3, ADR-0057).
--
-- The counterpart of every completed merchant payout: DEBIT the merchant's MERCHANT_PAYABLE,
-- CREDIT PAYOUT_CLEARING, keyed merchant-payout:<payoutId>. Its balance is continuously
-- "instructed but unsettled" per currency - the platform has irrevocably told the rail to pay
-- and the cash has not yet been seen to leave (INV-SET-01 on the outbound side). Phase 8's
-- settlement will DEBIT it against cash; nothing in Phase 6 moves it onward.
--
-- WHY A LIABILITY
--   ADR-0051 said "an operational account seeded per currency" and left the type open; ADR-0057
--   decides it. The money has left the merchant's claim on us and not yet left our bank: it is
--   an obligation we have instructed and must still discharge, so it grows on the CREDIT side
--   exactly as the payout credits it. An ASSET here would carry a permanently negative balance,
--   which is a classification error rather than a position.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V011
--   V011 is applied history (ADR-0011). Adding an enum member regenerates the four rules the
--   chart's enums feed, so the four constraints are RECREATED here from the enums' current
--   definitions - two of them unchanged, recreated so the latest definition lives in one file -
--   and LedgerAccountMigrationTest reconciles these four against THIS file. The drop/add pairs
--   run in one transaction; every existing row satisfies the widened rules by construction.
--
-- THE SEED ROWS
--   One per supported currency (SupportedCurrencies.ALL: EUR, GBP, USD), hand-minted UUIDv7
--   literals and literal timestamps for the reasons V003 records; OperationalChartMigrationTest
--   reads V003 and this file together, so "every operational purpose seeded in every currency,
--   exactly once" still has one definition. No account_balance rows: the projection's
--   ON CONFLICT upsert creates each on its first posting, as it did for every V003 account.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0cb8f-e400-7dd3-977d-81506bfb4a06', 'LIABILITY', 'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'PAYOUT_CLEARING',     NULL, 'ACTIVE', '2026-09-23T00:00:00Z', '2026-09-23T00:00:00Z'),
    ('01a0cb8f-e400-75e0-95a8-664c8ecf7a30', 'LIABILITY', 'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'PAYOUT_CLEARING',     NULL, 'ACTIVE', '2026-09-23T00:00:00Z', '2026-09-23T00:00:00Z'),
    ('01a0cb8f-e400-7174-b142-92d5ed83ca4a', 'LIABILITY', 'CREDIT', 'USD', 'OPERATIONAL', NULL, 'PAYOUT_CLEARING',     NULL, 'ACTIVE', '2026-09-23T00:00:00Z', '2026-09-23T00:00:00Z');
