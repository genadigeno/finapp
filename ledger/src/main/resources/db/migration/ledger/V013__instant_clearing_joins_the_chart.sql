-- INSTANT_CLEARING joins the chart of accounts (P7-TSK-006, ADR-0062 section 4, ADR-0059
-- section 4).
--
-- The instant scheme's own clearing position: every accepted push lands here - a pay-in
-- DEBITS it (the scheme will settle to us on its cycle), a wallet withdrawal CREDITS it -
-- and Phase 8 discharges it against the scheme's settlement reports, cycle by cycle. One
-- counterparty's position never nets against another's (ADR-0059 section 4), which is why
-- the card PSP's SETTLEMENT_CLEARING cannot be reused.
--
-- WHY AN ASSET
--   ADR-0062 section 4 declares it: the position is the net receivable ON THE SCHEME.
--   Pay-ins grow it on the DEBIT side exactly as the confirmation debits it; withdrawals
--   reduce it. The payout rail's position is a LIABILITY for the opposite flow shape
--   (V012's reasoning); this one grows from money owed TO the platform.
--
-- WHY THIS TASK AND NOT THE FIRST POSTER'S
--   A settling rail's descriptor must state its clearing position at construction
--   (RailCapabilities' own rule), so the NAME arrives with the rail's declaration
--   (P7-TSK-006) - and the chart reconciliation demands every operational purpose seeded in
--   every currency, so the rows arrive beside it. The first POSTING stays with the flows
--   (P7-TSK-009): that is the substance of the ADRs' "added with its first poster", and
--   both ADRs carry the annotation.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V012
--   V012 is applied history (ADR-0011). Adding an enum member regenerates the four rules
--   the chart's enums feed, so the four constraints are RECREATED here from the enums'
--   current definitions - three of them unchanged, recreated so the latest definition lives
--   in one file - and LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE SEED ROWS
--   One per supported currency (SupportedCurrencies.ALL: EUR, GBP, USD), hand-minted UUIDv7
--   literals and literal timestamps for the reasons V003 records; OperationalChartMigrationTest
--   reads V003, V012 and this file together. No account_balance rows: the projection's
--   ON CONFLICT upsert creates each on its first posting.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e000-0000-7000-8000-00000000000b', 'ASSET', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'INSTANT_CLEARING',    NULL, 'ACTIVE', '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z'),
    ('01a0e000-0000-7000-8000-00000000000c', 'ASSET', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'INSTANT_CLEARING',    NULL, 'ACTIVE', '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z'),
    ('01a0e000-0000-7000-8000-00000000000d', 'ASSET', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'INSTANT_CLEARING',    NULL, 'ACTIVE', '2026-09-26T00:00:00Z', '2026-09-26T00:00:00Z');
