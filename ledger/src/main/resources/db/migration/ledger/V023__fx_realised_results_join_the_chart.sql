-- FX_REALISED_GAINS and FX_REALISED_LOSSES join the chart of accounts with their poster
-- (P9-TSK-012; ADR-0077 section 6, PHASE_9_PLAN.md sections 12.4(f) and 12.6; INV-FX-06,
-- INV-FX-08).
--
-- A cover always closes EXACTLY the plan's FX_POSITION legs. When the provider executes at
-- anything but the plan - a requote after a definitive rejection re-prices the computed leg, a
-- provider deviating on the fixed leg is flagged executed_off_plan - the difference is the
-- platform's realised FX result, posted in that leg's own currency by EXACTLY ONE poster: fx's
-- CoverLines, in the entry fx-cover:<coverId>. A gain is REVENUE, credit-normal; a loss is
-- EXPENSE, debit-normal; the two are never netted with each other. FxBooksHaveOnePosterTest
-- refuses any other code that names them.
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine): the cover entry
--   is the first to realise an FX result.
--
-- WHY THIS IS A NEW MIGRATION
--   V022 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED here
--   from the enums' current definitions (V021's text, the two purposes added);
--   LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE SEED ROWS
--   One per supported currency (EUR, GBP, USD, JPY, BHD) for each purpose, hand-minted UUIDv7
--   literals stamped 2026-09-27T12:00:00Z (01a0e2bc-8200) - below the 2026-09-28T00:00:00Z
--   ceiling, so every seed still sorts before every runtime id (OperationalChartMigrationTest).
--   No account_balance rows: the projection's upsert creates each on its first posting.
--
-- THE BINDING, RE-STATED (V015, re-stated by V016, V017, V018, V020, V022 and now here)
--   AccountPurpose.closedToFreeAdjustments() gains both purposes: a MANUAL adjustment line on
--   either is refused at the domain and by this trigger. They do NOT join the reconciled
--   positions - they open no expectations, and the completeness proof would otherwise report
--   every cover's P&L line unattributed (PHASE_9_PLAN.md section 12.6).

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE', 'COUNTERPARTY')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_PROVIDER_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT', 'COUNTERPARTY')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7023-8000-000000000001', 'REVENUE',   'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'FX_REALISED_GAINS', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000002', 'REVENUE',   'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'FX_REALISED_GAINS', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000003', 'REVENUE',   'CREDIT', 'USD', 'OPERATIONAL', NULL, 'FX_REALISED_GAINS', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000004', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'FX_REALISED_GAINS', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000005', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'FX_REALISED_GAINS', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000006', 'EXPENSE',   'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'FX_REALISED_LOSSES', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000007', 'EXPENSE',   'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'FX_REALISED_LOSSES', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000008', 'EXPENSE',   'DEBIT', 'USD', 'OPERATIONAL', NULL, 'FX_REALISED_LOSSES', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000009', 'EXPENSE',   'DEBIT', 'JPY', 'OPERATIONAL', NULL, 'FX_REALISED_LOSSES', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7023-8000-000000000010', 'EXPENSE',   'DEBIT', 'BHD', 'OPERATIONAL', NULL, 'FX_REALISED_LOSSES', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position or an FX book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, INV-REC-06, P8-TSK-006, P9-TSK-009, P9-TSK-011, P9-TSK-012)';
    END IF;
    RETURN NEW;
END;
$$;
