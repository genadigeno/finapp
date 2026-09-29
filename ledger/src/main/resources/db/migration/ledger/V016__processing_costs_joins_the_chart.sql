-- PROCESSING_COSTS joins the chart of accounts (P8-TSK-009, ADR-0065 section 2).
--
-- Hop 1's one posting fact: the counterparty's own fees, learned only from its accepted
-- settlement report and recognised once per batch - DR PROCESSING_COSTS / CR that
-- counterparty's clearing position (the mirror for a net rebate), keyed
-- settlement-batch:<batchId>. A report's transaction lines post NOTHING: their value is
-- already in the position, posted by the completions themselves (ADR-0065's subtraction).
--
--   PROCESSING_COSTS  EXPENSE  the PSP's, scheme's, payout provider's and bank's processing
--                              fees, each recognised at its report's acceptance. Settles the
--                              per-rail cost-meter deferral (DECISIONS.md:671, ADR-0060
--                              section 6) as a LEDGER fact: provider costs are an audited
--                              report over these rows, never a metric (ADR-0072).
--
-- WHY AN EXPENSE
--   The fee grows on the DEBIT side exactly as the recognition debits it; a net rebate
--   credits it back without ambiguity (the DISPUTE_COSTS/V014 reasoning for an expense).
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine): nothing
--   could post here before the accept leg exists.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V014
--   V014 is applied history (ADR-0011). Adding an enum member regenerates the four rules the
--   chart's enums feed, so the four constraints are RECREATED here from the enums' current
--   definitions, and LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE SEED ROWS
--   One per supported currency (SupportedCurrencies.ALL: EUR, GBP, USD), hand-minted UUIDv7
--   literals below the 2026-09-28T00:00:00Z ceiling (the lock-order rule
--   OperationalChartMigrationTest holds) and literal timestamps. No account_balance rows:
--   the projection's ON CONFLICT upsert creates each on its first posting.
--
-- THE BINDING, RE-STATED (P8-TSK-006's V015, the announced V016 re-statement)
--   PROCESSING_COSTS joins AccountPurpose.reconciledPositions(): its every line belongs to a
--   recognition entry, so a free MANUAL adjustment there would un-explain an accepted report
--   or destroy the evidence a break existed (ADR-0071, INV-REC-06). The generated list is
--   reconciled by the migration test against THIS file now.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'FEE_REVENUE', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e029-5400-7000-8000-000000000014', 'EXPENSE', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'PROCESSING_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000015', 'EXPENSE', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'PROCESSING_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000016', 'EXPENSE', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'PROCESSING_COSTS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z');

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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'PROCESSING_COSTS', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position is closed to free adjustments: value there moves only through a break resolution (ADR-0071, INV-REC-06, P8-TSK-006)';
    END IF;
    RETURN NEW;
END;
$$;
