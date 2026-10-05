-- The corridor provider corridor-sim-a is admitted: the purpose CORRIDOR_CLEARING, its registry row and
-- its three accounts, and the purpose joins the reconciled positions (P9-TSK-014; ADR-0078 section 4,
-- ADR-0080 section 1, ADR-0082; INV-RAIL-04, INV-SET-05, INV-REC-06, INV-LED-06).
--
-- WHAT THE POSITION IS
--   What the platform owes corridor-sim-a, per currency: a LIABILITY, CREDIT-normal (pinned in
--   OperationalChartMigrationTest). An accepted outbound credit credits it (P9-TSK-019), and
--   corridor-sim-a's own settlement report discharges it through its one declared source,
--   corridor-sim-a.settlement - never another counterparty's evidence, and never a shared account.
--   Nothing posts to it in this task: the rail is declared before anything is sent.
--
-- WHY THIS IS A NEW MIGRATION
--   V023 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED here from
--   the enums' current definitions (V023's text, the purpose added); LedgerAccountMigrationTest
--   reconciles them against THIS file.
--
-- WHY THIS MIGRATION SEEDS THE ACCOUNTS
--   "Every counterparty clearing account is seeded by the migration that admits its counterparty"
--   (ADR-0078 section 4): the registry row and one account per settled currency - USD, JPY and BHD,
--   SimulatedCorridorAdapter's declared currencies - hand-minted UUIDv7 literals stamped
--   2026-09-27T12:00:00Z (01a0e2bc-8200), below the 2026-09-28T00:00:00Z ceiling, so every seed still
--   sorts before every runtime id. Nothing is minted at runtime; CounterpartyChartGuard refuses startup
--   if any of these rows is missing.
--
-- WHY IT JOINS THE RECONCILED POSITIONS
--   It will open expectations (CROSSBORDER_PAYOUT, CROSSBORDER_RETURN), so it is a reconciled position
--   (ADR-0076 section 5; ADR-0078): the position proof, completeness and the free-adjustment binding all
--   reach it. The binding trigger's function is RE-STATED with
--   AccountPurpose.sqlClosedToFreeAdjustmentsList() (AdjustmentProposalMigrationTest reconciles it
--   against THIS file).

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE', 'COUNTERPARTY')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_PROVIDER_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'CORRIDOR_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT', 'COUNTERPARTY')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.counterparty (id, code, kind) VALUES
    ('01a0e2bc-8200-7024-8000-000000000001', 'corridor-sim-a', 'CORRIDOR_PROVIDER');

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code, status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7024-8000-000000000101', 'LIABILITY', 'CREDIT', 'USD', 'COUNTERPARTY', '01a0e2bc-8200-7024-8000-000000000001', 'CORRIDOR_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7024-8000-000000000102', 'LIABILITY', 'CREDIT', 'JPY', 'COUNTERPARTY', '01a0e2bc-8200-7024-8000-000000000001', 'CORRIDOR_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7024-8000-000000000103', 'LIABILITY', 'CREDIT', 'BHD', 'COUNTERPARTY', '01a0e2bc-8200-7024-8000-000000000001', 'CORRIDOR_CLEARING', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position or an FX book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, INV-REC-06, P8-TSK-006, P9-TSK-009, P9-TSK-011, P9-TSK-012, P9-TSK-014)';
    END IF;
    RETURN NEW;
END;
$$;
