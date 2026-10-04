-- FX_SPREAD_REVENUE joins the chart of accounts, and the FX books close to free adjustment
-- (P9-TSK-009; ADR-0076 sections 3-4, PHASE_9_PLAN.md section 12.6; INV-FX-03, INV-REC-06).
--
-- The spread and markup a wallet conversion earns: a REVENUE account, CREDIT-normal, posted by
-- EXACTLY ONE poster - fx's ConversionLines, in the entry fx-trade:<tradeId> - recognising the
-- margin explicitly in the computed leg's currency, never inside the applied rate (INV-FX-03). A
-- static rule (FxBooksHaveOnePosterTest) refuses any other code that names it, FX_POSITION or
-- ROUNDING_RESIDUAL.
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine): the wallet
--   conversion is the first entry to recognise a spread.
--
-- WHY THIS IS A NEW MIGRATION
--   V019 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED here
--   from the enums' current definitions; LedgerAccountMigrationTest reconciles them against THIS
--   file.
--
-- THE SEED ROWS
--   One per supported currency (EUR, GBP, USD, JPY, BHD), hand-minted UUIDv7 literals stamped
--   2026-09-27T12:00:00Z (01a0e2bc-8200) - below the 2026-09-28T00:00:00Z ceiling, so every seed
--   still sorts before every runtime id (OperationalChartMigrationTest). No account_balance rows:
--   the projection's upsert creates each on its first posting.
--
-- THE BINDING, RE-STATED (V015, re-stated by V016, V017, V018 and now here)
--   With AccountPurpose.closedToFreeAdjustments() = reconciledPositions() + {FX_POSITION,
--   FX_SPREAD_REVENUE, ROUNDING_RESIDUAL}: a MANUAL adjustment line on any of them is refused at
--   the domain (AdjustmentService) and by this trigger. The FX books join the CLOSED set, not the
--   reconciled positions - they open no expectations, and the completeness proof would otherwise
--   report every conversion line unattributed. FX_REALISED_GAINS and FX_REALISED_LOSSES join with
--   their poster (P9-TSK-012, ledger V023).

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7020-8000-000000000001', 'REVENUE',   'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'FX_SPREAD_REVENUE',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7020-8000-000000000002', 'REVENUE',   'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'FX_SPREAD_REVENUE',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7020-8000-000000000003', 'REVENUE',   'CREDIT', 'USD', 'OPERATIONAL', NULL, 'FX_SPREAD_REVENUE',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7020-8000-000000000004', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'FX_SPREAD_REVENUE',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7020-8000-000000000005', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'FX_SPREAD_REVENUE',  NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

-- The binding trigger's function, re-stated with the closed list
-- (AccountPurpose.sqlClosedToFreeAdjustmentsList(); the V015 trigger binding stands unchanged).
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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position or an FX book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, INV-REC-06, P8-TSK-006, P9-TSK-009)';
    END IF;
    RETURN NEW;
END;
$$;
