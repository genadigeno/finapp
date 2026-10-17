-- The BNPL book joins the chart of accounts (P12-TSK-003; ADR-0103 decision 1, PHASE_12_PLAN.md section
-- 12.11; INV-BNPL-01, INV-LED-06, INV-ACC-01, INV-RAIL-04). The V026 precedent, for the second credit product.
--
-- WHY BEFORE ANY PURCHASE EXISTS
--   Every figure a BNPL customer owes must be a balance of a typed, guarded ledger account derived from
--   journal lines (INV-BNPL-01): the accounts - and the guards on them - are in place before the first
--   purchase, billing or refund can exist. Nothing posts here.
--
-- THE BNPL_OBLIGATION OWNER KIND
--   OwnerKind.BNPL_OBLIGATION: owner_ref names the customer repayment obligation by value - opaque, no
--   foreign key into bnpl's schema (ADR-0029), exactly as LOAN names the loan. Deliberately not LOAN:
--   another contract with another poster (OQ-11) - one kind for both would let a loan-book reader count a
--   BNPL receivable. Deliberately not CUSTOMER: the receivables are the platform's assets on a contract,
--   not money held for the customer.
--
-- THE SIX PER-OBLIGATION PURPOSES (owner BNPL_OBLIGATION; opened at runtime, never seeded)
--   BNPL_PRINCIPAL, BNPL_PRINCIPAL_DUE, BNPL_INTEREST_ACCRUED, BNPL_INTEREST_DUE, BNPL_FEES_DUE - each an
--   ASSET, DEBIT-normal: a claim on the customer, due split from not-due, interest from principal (the
--   interest pair carries no line for pay-in-4). BNPL_CREDIT_BALANCE - a LIABILITY, CREDIT-normal: an
--   overpayment, or a refund's excess the wallet could not take, owed back. Separate from the LOAN_*
--   purposes (OQ-11, B8). Opened together, in the obligation's one currency, by bnpl's BnplAccounts over
--   LedgerAccountStore.createOrConverge, which declares each type; V002's partial unique index
--   (owner_ref, purpose, currency) WHERE owner_ref IS NOT NULL is the concurrency arbiter - concurrent
--   openers of one obligation converge on one set of six.
--
-- THE FOUR OPERATIONAL PURPOSES (seeded here, one per supported currency)
--   BNPL_MERCHANT_FEE_INCOME (the merchant discount, its own purpose and never merchant's FEE_REVENUE -
--   OQ-19), BNPL_INTEREST_INCOME (pay-in-N interest) and BNPL_FEE_INCOME (late fees) - REVENUE,
--   CREDIT-normal. BNPL_WRITE_OFF_EXPENSE - EXPENSE, DEBIT-normal; seeded so the account plan never
--   changes when Phase 14 builds the write-off, and posted by NOTHING in Phase 12.
--   Hand-minted UUIDv7 literals stamped 2026-09-27T12:00:00Z (01a0e2bc-8200, V026's stamp, with this
--   migration's own 7027 prefix), below the 2026-09-28T00:00:00Z ceiling, so every seed still sorts
--   before every runtime id - the lock-order rule OperationalChartMigrationTest pins. All five supported
--   currencies, as every operational purpose is (the chart's own rule), although both BNPL products are
--   EUR today. No account_balance rows: the projection's upsert creates each on its first posting. Each
--   seeded account holds zero, so every currency's trial balance is unchanged (INV-ACC-01).
--
-- NO *_CLEARING PURPOSE AND NO RECONCILED POSITION (INV-RAIL-04)
--   BNPL moves no money across a rail: a purchase finances the merchant's payable inside the ledger, a
--   repayment draws on the customer's wallet, a refund's excess credits it, and the payout is
--   merchant's own. None of the ten opens an expectation, so none joins reconciledPositions() - the
--   unattributed-lines gauge gains no BNPL series; bnpl's own proofs (P12-TSK-025) prove the book.
--
-- WHY THIS IS A NEW MIGRATION
--   V026 is applied history (ADR-0011). The four rules the chart's enums feed are RECREATED here from
--   the enums' current definitions (V026's text, the kind and the ten purposes added);
--   LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE BINDING, RE-STATED (V015, re-stated by V016, V017, V018, V020, V022, V023, V024, V026 and now here)
--   AccountPurpose.closedToFreeAdjustments() gains all ten. A MANUAL adjustment line on any of them is
--   refused at the domain (AdjustmentService) and by this trigger; bnpl's own corrections (a fee waived,
--   a repayment reversed) are four-eyes bnpl acts through ReversalService (F7, F8).
--   AdjustmentProposalMigrationTest reconciles the list against THIS file.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE', 'COUNTERPARTY', 'LOAN', 'BNPL_OBLIGATION')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_ACCRUED', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE', 'LOAN_CREDIT_BALANCE', 'BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE', 'BNPL_INTEREST_ACCRUED', 'BNPL_INTEREST_DUE', 'BNPL_FEES_DUE', 'BNPL_CREDIT_BALANCE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'LOAN_INTEREST_INCOME', 'LOAN_FEE_INCOME', 'LOAN_WRITE_OFF_EXPENSE', 'LENDING_CAPITAL', 'BNPL_MERCHANT_FEE_INCOME', 'BNPL_INTEREST_INCOME', 'BNPL_FEE_INCOME', 'BNPL_WRITE_OFF_EXPENSE', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'LOAN_PRINCIPAL' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_PRINCIPAL_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_INTEREST_ACCRUED' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_INTEREST_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_FEES_DUE' AND owner_kind = 'LOAN') OR (purpose = 'LOAN_CREDIT_BALANCE' AND owner_kind = 'LOAN') OR (purpose = 'BNPL_PRINCIPAL' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'BNPL_PRINCIPAL_DUE' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'BNPL_INTEREST_ACCRUED' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'BNPL_INTEREST_DUE' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'BNPL_FEES_DUE' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'BNPL_CREDIT_BALANCE' AND owner_kind = 'BNPL_OBLIGATION') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CASH_AT_BANK' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_SPREAD_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_REALISED_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_PROVIDER_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'CORRIDOR_CLEARING' AND owner_kind = 'COUNTERPARTY') OR (purpose = 'LOAN_INTEREST_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LOAN_FEE_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LOAN_WRITE_OFF_EXPENSE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'LENDING_CAPITAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'BNPL_MERCHANT_FEE_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'BNPL_INTEREST_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'BNPL_FEE_INCOME' AND owner_kind = 'OPERATIONAL') OR (purpose = 'BNPL_WRITE_OFF_EXPENSE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT', 'COUNTERPARTY', 'LOAN', 'BNPL_OBLIGATION')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e2bc-8200-7027-8000-000000000001', 'REVENUE', 'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'BNPL_MERCHANT_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000002', 'REVENUE', 'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'BNPL_MERCHANT_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000003', 'REVENUE', 'CREDIT', 'USD', 'OPERATIONAL', NULL, 'BNPL_MERCHANT_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000004', 'REVENUE', 'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'BNPL_MERCHANT_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000005', 'REVENUE', 'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'BNPL_MERCHANT_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000006', 'REVENUE', 'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'BNPL_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000007', 'REVENUE', 'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'BNPL_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000008', 'REVENUE', 'CREDIT', 'USD', 'OPERATIONAL', NULL, 'BNPL_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000009', 'REVENUE', 'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'BNPL_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000010', 'REVENUE', 'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'BNPL_INTEREST_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000011', 'REVENUE', 'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'BNPL_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000012', 'REVENUE', 'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'BNPL_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000013', 'REVENUE', 'CREDIT', 'USD', 'OPERATIONAL', NULL, 'BNPL_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000014', 'REVENUE', 'CREDIT', 'JPY', 'OPERATIONAL', NULL, 'BNPL_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000015', 'REVENUE', 'CREDIT', 'BHD', 'OPERATIONAL', NULL, 'BNPL_FEE_INCOME', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000016', 'EXPENSE', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'BNPL_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000017', 'EXPENSE', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'BNPL_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000018', 'EXPENSE', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'BNPL_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000019', 'EXPENSE', 'DEBIT', 'JPY', 'OPERATIONAL', NULL, 'BNPL_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z'),
    ('01a0e2bc-8200-7027-8000-000000000020', 'EXPENSE', 'DEBIT', 'BHD', 'OPERATIONAL', NULL, 'BNPL_WRITE_OFF_EXPENSE', NULL, 'ACTIVE', '2026-09-27T12:00:00Z', '2026-09-27T12:00:00Z');

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
        AND account_purpose IN ('LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_ACCRUED', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE', 'LOAN_CREDIT_BALANCE', 'BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE', 'BNPL_INTEREST_ACCRUED', 'BNPL_INTEREST_DUE', 'BNPL_FEES_DUE', 'BNPL_CREDIT_BALANCE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'LOAN_INTEREST_INCOME', 'LOAN_FEE_INCOME', 'LOAN_WRITE_OFF_EXPENSE', 'LENDING_CAPITAL', 'BNPL_MERCHANT_FEE_INCOME', 'BNPL_INTEREST_INCOME', 'BNPL_FEE_INCOME', 'BNPL_WRITE_OFF_EXPENSE', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position, an FX book, a loan book or a BNPL book is closed to free adjustments: value there moves only through its own poster or a break resolution (ADR-0071, ADR-0096, ADR-0103, INV-REC-06, INV-LND-01, INV-LND-13, INV-BNPL-01, P8-TSK-006, P9-TSK-009, P9-TSK-011, P9-TSK-012, P9-TSK-014, P11-TSK-003, P12-TSK-003)';
    END IF;
    RETURN NEW;
END;
$$;
