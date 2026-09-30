-- CASH_AT_BANK joins the chart of accounts (P8-TSK-016, ADR-0065 section 3, INV-SET-06).
--
-- The platform's cash at its settlement bank: an ASSET, DEBIT-normal, posted by EXACTLY ONE
-- poster - the recognition of an accepted bank statement, keyed settlement-batch:<batchId>
-- (DR the statement's net credits / CR its net debits, netted to one line, against each
-- attributed counterparty's clearing position) - and, from P8-TSK-023, by the reversal of that
-- recognition. Cash always follows the bank's own statement, never a report; a static rule
-- (CashAtBankHasOnePosterTest) refuses any other code that names it.
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine).
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V017
--   V017 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED
--   here from the enums' current definitions; LedgerAccountMigrationTest reconciles them against
--   THIS file.
--
-- THE SEED ROWS
--   One per supported currency (EUR, GBP, USD), hand-minted UUIDv7 literals below the
--   2026-09-28T00:00:00Z ceiling (OperationalChartMigrationTest's lock-order rule), stamped
--   2026-09-27T12:00:00Z. No account_balance rows: the projection's upsert creates each on its
--   first posting. The simulated bank opens at zero (transition decision O4): no row is seeded
--   with a balance, and a non-zero first statement opening raises OPENING_BALANCE instead.
--
-- THE BINDING, RE-STATED (V015, re-stated by V016, V017 and now here)
--   CASH_AT_BANK joins AccountPurpose.reconciledPositions(): a MANUAL adjustment line on it is
--   refused at the domain and by this trigger - cash is never adjusted to fit.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e029-5400-7000-8000-00000000001d', 'ASSET', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'CASH_AT_BANK', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000001e', 'ASSET', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'CASH_AT_BANK', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000001f', 'ASSET', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'CASH_AT_BANK', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

-- The binding trigger's function, re-stated with the grown generated list
-- (AccountPurpose.sqlReconciledPositionsList(); the V015 trigger binding stands unchanged).
CREATE OR REPLACE FUNCTION ledger.adjustment_line_respects_reconciled_positions()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal_origin text;
    account_purpose text;
BEGIN
    SELECT proposal.origin INTO proposal_origin
        FROM ledger.adjustment_proposal proposal
        WHERE proposal.id = NEW.proposal_id;
    SELECT account.purpose INTO account_purpose
        FROM ledger.ledger_account account
        WHERE account.id = NEW.ledger_account_id;
    IF proposal_origin = 'MANUAL'
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position is closed to free adjustments: value there moves only through a break resolution (ADR-0071, INV-REC-06, P8-TSK-006)';
    END IF;
    RETURN NEW;
END;
$$;
