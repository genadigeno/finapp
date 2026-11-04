-- The ACCOUNTING origin and the PRIOR_PERIOD_ADJUSTMENT reason (P14-TSK-013; ADR-0071 section 6, ADR-0113 decision 4, ADR-0114 decision 5;
-- INV-ACC-10, INV-REV-04, INV-AUD-04, INV-REC-06; PHASE_14_PLAN.md sections 4.2, 8.1 T-ppa, 13.5).
--
-- A prior-period adjustment is the ledger's own four-eyes adjustment, proposed and decided only through the accounting-owned door of
-- AdjustmentService, in the transaction that moves accounting's row (accounting V009). This migration widens what the proposal may say and
-- nothing else: no new column, no new table, no new grant.
--
-- WHAT NAMES AN ORIGIN OR A REASON CODE ON THE PROPOSAL, WALKED
--   V010: no constraint names either (they did not exist); adjustment_entry_is_approved reads status only, so an ACCOUNTING entry needs its
--         APPROVED proposal like any other; adjustment_proposal_approver_is_not_initiator is origin-agnostic, so the ledger's four eyes hold.
--   V015: three CHECKs (below, rebuilt); adjustment_proposal_requires_a_reason_code refuses UNCODED only, untouched;
--         adjustment_proposal_permits_only_decision freezes origin and reason_code by name, untouched - it already covers the new values;
--         adjustment_line_respects_reconciled_positions, re-stated by V016-V027, reads the origin (below, re-stated).
--   Each CHECK is dropped and added whole: no row can hold a new value yet, every row satisfies the wider predicate, so the ADD's validating
--   scan refuses nothing. The migration runs in one transaction, so no writer sees the table with the constraints gone.
--
-- THE LISTS ARE GENERATED: AdjustmentReasonCode.sqlValueList(), AdjustmentOrigin.sqlValueList(), AdjustmentReasonCode.sqlPairingRule() and
-- AccountPurpose.sqlClosedToFreeAdjustmentsList(), reconciled by AdjustmentProposalMigrationTest - one definition, two artefacts.
--
-- NEVER RE-DATED: a prior-period adjustment's value date is its posting date, for every writer. The month it corrects is named on accounting's
-- row; nothing about the entry's dates points into a closed month, so no read of the journal by value date can move what that month issued.
-- The cutoff (V028) refuses a posting date the cutoff covers; C3's floor is a PostingCommand's and reaches no adjustment.

ALTER TABLE ledger.adjustment_proposal
    DROP CONSTRAINT adjustment_proposal_reason_code_is_known,
    DROP CONSTRAINT adjustment_proposal_origin_is_known,
    DROP CONSTRAINT adjustment_proposal_code_matches_origin,

    ADD CONSTRAINT adjustment_proposal_reason_code_is_known
        CHECK (reason_code IN ('MANUAL_CORRECTION', 'RECONCILIATION_WRITE_OFF', 'RECONCILIATION_TRANSFER', 'RECONCILIATION_GAIN', 'PRIOR_PERIOD_ADJUSTMENT', 'UNCODED')),
    ADD CONSTRAINT adjustment_proposal_origin_is_known
        CHECK (origin IN ('MANUAL', 'RECONCILIATION', 'ACCOUNTING')),
    ADD CONSTRAINT adjustment_proposal_code_matches_origin
        CHECK ((reason_code = 'MANUAL_CORRECTION' AND origin = 'MANUAL') OR (reason_code = 'RECONCILIATION_WRITE_OFF' AND origin = 'RECONCILIATION') OR (reason_code = 'RECONCILIATION_TRANSFER' AND origin = 'RECONCILIATION') OR (reason_code = 'RECONCILIATION_GAIN' AND origin = 'RECONCILIATION') OR (reason_code = 'PRIOR_PERIOD_ADJUSTMENT' AND origin = 'ACCOUNTING') OR (reason_code = 'UNCODED' AND origin = 'MANUAL')),
    ADD CONSTRAINT adjustment_proposal_accounting_is_dated_once
        CHECK (origin <> 'ACCOUNTING' OR value_date = posting_date);

-- The binding, re-stated (V027's body, its purpose list unchanged): a MANUAL-origin or an ACCOUNTING-origin line may not touch a purpose closed
-- to free adjustments, for any writer. Only a RECONCILIATION-origin line may: a resolution is what that closure leaves open. Each closed
-- purpose has its own domain's correction door (a loan's waiver or reversal, a BNPL act, an FX trade's reversal, a break's resolution).
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
    IF proposal_origin IN ('MANUAL', 'ACCOUNTING')
        AND account_purpose IN ('LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_ACCRUED', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE', 'LOAN_CREDIT_BALANCE', 'BNPL_PRINCIPAL', 'BNPL_PRINCIPAL_DUE', 'BNPL_INTEREST_ACCRUED', 'BNPL_INTEREST_DUE', 'BNPL_FEES_DUE', 'BNPL_CREDIT_BALANCE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CASH_AT_BANK', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'FX_SPREAD_REVENUE', 'FX_REALISED_GAINS', 'FX_REALISED_LOSSES', 'FX_PROVIDER_CLEARING', 'CORRIDOR_CLEARING', 'LOAN_INTEREST_INCOME', 'LOAN_FEE_INCOME', 'LOAN_WRITE_OFF_EXPENSE', 'LENDING_CAPITAL', 'BNPL_MERCHANT_FEE_INCOME', 'BNPL_INTEREST_INCOME', 'BNPL_FEE_INCOME', 'BNPL_WRITE_OFF_EXPENSE', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position, an FX book, a loan book or a BNPL book is closed to free and prior-period adjustments: value there moves only through its own poster or a break resolution (ADR-0071, ADR-0096, ADR-0103, INV-ACC-10, INV-REC-06, INV-LND-01, INV-LND-13, INV-BNPL-01, P8-TSK-006, P9-TSK-009, P9-TSK-011, P9-TSK-012, P9-TSK-014, P11-TSK-003, P12-TSK-003, P14-TSK-013)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN ledger.adjustment_proposal.reason_code IS
    'The closed category of the justification (INV-REV-04, ADR-0071 section 5): MANUAL_CORRECTION for the generic door (assigned server-side), '
    'the RECONCILIATION_* codes for break resolutions, PRIOR_PERIOD_ADJUSTMENT for accounting''s correction of a closed period (P14-TSK-013), '
    'UNCODED for pre-V015 history only (refused on insert).';
COMMENT ON COLUMN ledger.adjustment_proposal.origin IS
    'Whose machinery may decide this proposal (ADR-0071 section 6): MANUAL the generic door, RECONCILIATION the break resolution, ACCOUNTING the '
    'prior-period adjustment (P14-TSK-013). Each door refuses every other''s proposals, so a proposal''s ledger half is decidable only by the '
    'flow that also moves what owns it.';
