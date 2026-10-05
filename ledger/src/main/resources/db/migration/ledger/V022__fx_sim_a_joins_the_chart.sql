-- The FX provider fx-sim-a is admitted: its registry row and its five FX_PROVIDER_CLEARING accounts,
-- and the purpose joins the reconciled positions (P9-TSK-011; ADR-0078 section 4, ADR-0076 section 5;
-- INV-RAIL-04, INV-SET-05, INV-REC-06, INV-LED-06).
--
-- WHAT THE POSITION IS
--   What fx-sim-a owes the platform, per currency: an ASSET, DEBIT-normal (pinned in
--   OperationalChartMigrationTest). A cover's provider legs land here (P9-TSK-012), and fx-sim-a's own
--   trade report discharges it through its one declared source, fx-sim-a.trade-report - never another
--   counterparty's evidence, and never a shared account (there is none).
--
-- WHY THIS MIGRATION SEEDS THE ACCOUNTS
--   "Every counterparty clearing account is seeded by the migration that admits its counterparty"
--   (ADR-0078 section 4): the registry row and one account per settled currency (fx-sim-a settles all five
--   supported currencies, FxProviderDeclaration's settledCurrencies), hand-minted UUIDv7 literals stamped
--   2026-09-27T12:00:00Z (01a0e2bc-8200) - below the 2026-09-28T00:00:00Z ceiling, so every seed still
--   sorts before every runtime id. Nothing is minted at runtime; CounterpartyChartGuard refuses startup
--   if any of these rows is missing. The purpose itself was admitted by V021 (P9-TSK-010).
--
-- WHY IT JOINS THE RECONCILED POSITIONS
--   It opens expectations (the cover legs, FX_SELL_LEG/FX_BUY_LEG), so it is a reconciled position
--   (ADR-0076 section 5; ADR-0078): the position proof, completeness and the free-adjustment binding all
--   reach it. The binding trigger's function is RE-STATED with AccountPurpose.sqlClosedToFreeAdjustmentsList()
--   (the V015 -> V020 pattern; AdjustmentProposalMigrationTest reconciles it against THIS file).

INSERT INTO ledger.counterparty (id, code, kind) VALUES
    ('01a0e2bc-8200-7022-8000-000000000001', 'fx-sim-a', 'FX_PROVIDER');

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code, status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7022-8000-000000000101', 'ASSET',     'DEBIT',  'EUR', 'COUNTERPARTY', '01a0e2bc-8200-7022-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7022-8000-000000000102', 'ASSET',     'DEBIT',  'GBP', 'COUNTERPARTY', '01a0e2bc-8200-7022-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7022-8000-000000000103', 'ASSET',     'DEBIT',  'USD', 'COUNTERPARTY', '01a0e2bc-8200-7022-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7022-8000-000000000104', 'ASSET',     'DEBIT',  'JPY', 'COUNTERPARTY', '01a0e2bc-8200-7022-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7022-8000-000000000105', 'ASSET',     'DEBIT',  'BHD', 'COUNTERPARTY', '01a0e2bc-8200-7022-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_PROVIDER_CLEARING', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position or an FX book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, INV-REC-06, P8-TSK-006, P9-TSK-009, P9-TSK-011)';
    END IF;
    RETURN NEW;
END;
$$;
