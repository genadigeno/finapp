-- The loan book and the lending capital join the chart of accounts (P11-TSK-003; ADR-0096 section 1,
-- PHASE_11_PLAN.md sections 12.8 and 12.9; INV-LED-06, INV-LND-01, INV-LND-13, INV-LED-04, INV-RAIL-04).
--
-- WHY BEFORE ANY LOAN EXISTS
--   Every figure a borrower is charged must be a balance of a typed, guarded ledger account derived
--   from journal lines (INV-LND-01), and every unit of wallet money a disbursement creates must be
--   matched by recognised platform capital (INV-LND-13). Both need their accounts - and the guards on
--   them - in place before the first loan, contribution or posting can exist. Nothing posts here.
--
-- THE LOAN OWNER KIND
--   OwnerKind.LOAN: owner_ref names the loan (or credit line) by value - opaque, no foreign key into
--   lending's schema (ADR-0029), exactly as CUSTOMER names the customer account. One kind for both
--   products. Deliberately not CUSTOMER: the receivables are the platform's assets on a contract, not
--   money held for the borrower.
--
-- THE SIX PER-LOAN PURPOSES (owner LOAN; opened at runtime, never seeded)
--   LOAN_PRINCIPAL, LOAN_PRINCIPAL_DUE, LOAN_INTEREST_ACCRUED, LOAN_INTEREST_DUE, LOAN_FEES_DUE - each
--   an ASSET, DEBIT-normal: a claim on the borrower, due split from not-due, interest from principal.
--   LOAN_CREDIT_BALANCE - a LIABILITY, CREDIT-normal: money received beyond every receivable, owed
--   back. Opened together, in the loan's one currency, by lending's LoanAccounts over
--   LedgerAccountStore.createOrConverge, which declares each type; V002's partial unique index
--   (owner_ref, purpose, currency) WHERE owner_ref IS NOT NULL is the concurrency arbiter - ten
--   openers of one loan converge on one set of six.
--
-- THE FOUR OPERATIONAL PURPOSES (seeded here, one per supported currency)
--   LOAN_INTEREST_INCOME and LOAN_FEE_INCOME - REVENUE, CREDIT-normal.
--   LOAN_WRITE_OFF_EXPENSE - EXPENSE, DEBIT-normal; seeded so the account plan never changes when
--   Phase 14 builds the write-off, and posted by NOTHING in Phase 11.
--   LENDING_CAPITAL - EQUITY, CREDIT-normal: the platform's own funds committed to lending, credited
--   ONLY by bank recognition of a four-eyes contribution's statement line (rule 0, P11-TSK-004).
--   Hand-minted UUIDv7 literals stamped 2026-09-27T12:00:00Z (01a0e2bc-8200), below the
--   2026-09-28T00:00:00Z ceiling, so every seed still sorts before every runtime id - the lock-order
--   rule OperationalChartMigrationTest pins. All five supported currencies, as every operational
--   purpose is (the chart's own rule), although both loan products are EUR today. No account_balance
--   rows: the projection's upsert creates each on its first posting.
--
-- NO *_CLEARING PURPOSE (INV-RAIL-04)
--   Lending moves no money across a rail itself: a disbursement credits a wallet inside the ledger,
--   and a payout to an external account is payments' own Withdrawal on INSTANT_CLEARING (rule 12).
--
-- WHY THIS IS A NEW MIGRATION
--   V025 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED here from
--   the enums' current definitions (V024's text, the kind and the ten purposes added);
--   LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE BINDING, RE-STATED (V015, re-stated by V016, V017, V018, V020, V022, V023, V024 and now here)
--   AccountPurpose.closedToFreeAdjustments() gains all ten: the six per-loan purposes and the three
--   loan P&L purposes join directly, and LENDING_CAPITAL joins through reconciledPositions(), whose
--   completeness proof will report any line on it that no recognition explains. A MANUAL adjustment
--   line on any of them is refused at the domain (AdjustmentService) and by this trigger; lending's
--   own corrections are four-eyes lending acts posting through PostingService / ReversalService.
--   AdjustmentProposalMigrationTest reconciles the list against THIS file.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE', 'COUNTERPARTY', 'LOAN')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_ACCRUED', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE', 'LOAN_CREDIT_BALANCE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'LOAN_INTEREST_INCOME', 'LOAN_FEE_INCOME', 'LOAN_WRITE_OFF_EXPENSE', 'LENDING_CAPITAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'LOAN_PRINCIPAL' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_PRINCIPAL_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_INTEREST_ACCRUED' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_INTEREST_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_FEES_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_CREDIT_BALANCE' AND owner_kind = 'LOAN') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_PROVIDER_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'CORRIDOR_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'LOAN_INTEREST_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LOAN_FEE_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LOAN_WRITE_OFF_EXPENSE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LENDING_CAPITAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT', 'COUNTERPARTY', 'LOAN')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7026-8000-000000000001', 'REVENUE',   'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'LOAN_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000002', 'REVENUE',   'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'LOAN_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000003', 'REVENUE',   'CREDIT', 'USD', 'OPERATIONAL', NULL, 'LOAN_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000004', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'LOAN_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000005', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'LOAN_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000006', 'REVENUE',   'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'LOAN_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000007', 'REVENUE',   'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'LOAN_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000008', 'REVENUE',   'CREDIT', 'USD', 'OPERATIONAL', NULL, 'LOAN_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000009', 'REVENUE',   'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'LOAN_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000010', 'REVENUE',   'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'LOAN_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000011', 'EXPENSE',   'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'LOAN_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000012', 'EXPENSE',   'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'LOAN_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000013', 'EXPENSE',   'DEBIT', 'USD', 'OPERATIONAL', NULL, 'LOAN_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000014', 'EXPENSE',   'DEBIT', 'JPY', 'OPERATIONAL', NULL, 'LOAN_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000015', 'EXPENSE',   'DEBIT', 'BHD', 'OPERATIONAL', NULL, 'LOAN_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000016', 'EQUITY',    'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'LENDING_CAPITAL', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000017', 'EQUITY',    'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'LENDING_CAPITAL', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000018', 'EQUITY',    'CREDIT', 'USD', 'OPERATIONAL', NULL, 'LENDING_CAPITAL', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000019', 'EQUITY',    'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'LENDING_CAPITAL', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7026-8000-000000000020', 'EQUITY',    'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'LENDING_CAPITAL', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

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
        AND account_purpose IN ('LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_ACCRUED', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE', 'LOAN_CREDIT_BALANCE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'LOAN_INTEREST_INCOME', 'LOAN_FEE_INCOME', 'LOAN_WRITE_OFF_EXPENSE', 'LENDING_CAPITAL', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position, an FX book or a loan book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, ADR-0096, INV-REC-06, INV-LND-01, INV-LND-13, P8-TSK-006, P9-TSK-009, P9-TSK-011, P9-TSK-012, P9-TSK-014, P11-TSK-003)';
    END IF;
    RETURN NEW;
END;
$$;
