-- CHARGEBACK_RECOVERABLE and DISPUTE_COSTS join the chart of accounts (P7-TSK-013, ADR-0061
-- sections 3-5).
--
-- A chargeback is money the card network has ALREADY taken, so its books record two facts in
-- order: the external fact - the card rail's clearing position credited by exactly what the
-- network took - and the platform's attribution of it. These are the two accounts that
-- attribution needs:
--
--   CHARGEBACK_RECOVERABLE  ASSET    debited by the whole chargeback against the rail's
--                                    clearing (keyed dispute-chargeback:<id>), then credited by
--                                    the counterparty's attributed share (dispute-attribution:),
--                                    so its balance is exactly what nobody has been charged: the
--                                    value the network took that the platform had already
--                                    returned (the excess), and any share parked because the
--                                    counterparty's account takes no postings. A win empties it;
--                                    a loss writes the excess off.
--   DISPUTE_COSTS           EXPENSE  the excess of a lost chargeback written off
--                                    (dispute-loss:<id>) and the dispute fees the PSP reports
--                                    (dispute-fee:<id>) - the platform bears both in Phase 7.
--
-- WHY AN ASSET, AND WHY AN EXPENSE
--   ADR-0061 section 3 declares the recoverable "an operational asset": it is a claim - on the
--   network through representment, or on the counterparty for a parked share - and it grows on
--   the DEBIT side exactly as the chargeback debits it. The costs grow on the debit side the
--   write-off and the fee debit, which is an EXPENSE's normal balance; a recovered cost (a
--   counted refund failing after the loss) credits it back without ambiguity, as
--   ROUNDING_RESIDUAL's V003 reasoning records for an expense account.
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine, the
--   INSTANT_CLEARING precedent): nothing could post to either before the chargeback posting.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V013
--   V013 is applied history (ADR-0011). Adding enum members regenerates the four rules the
--   chart's enums feed, so the four constraints are RECREATED here from the enums' current
--   definitions - two of them unchanged, recreated so the latest definition lives in one file -
--   and LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE SEED ROWS
--   One per supported currency (SupportedCurrencies.ALL: EUR, GBP, USD) for each purpose,
--   hand-minted UUIDv7 literals and literal timestamps for the reasons V003 records;
--   OperationalChartMigrationTest reads V003, V012, V013 and this file together. No
--   account_balance rows: the projection's ON CONFLICT upsert creates each on its first posting.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e029-5400-7000-8000-00000000000e', 'ASSET', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'CHARGEBACK_RECOVERABLE', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000000f', 'ASSET', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'CHARGEBACK_RECOVERABLE', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000010', 'ASSET', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'CHARGEBACK_RECOVERABLE', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000011', 'EXPENSE', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'DISPUTE_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000012', 'EXPENSE', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'DISPUTE_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000013', 'EXPENSE', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'DISPUTE_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z');
