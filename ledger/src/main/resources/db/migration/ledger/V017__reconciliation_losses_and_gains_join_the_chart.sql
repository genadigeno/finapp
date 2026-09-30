-- RECONCILIATION_LOSSES and RECONCILIATION_GAINS join the chart of accounts (P8-TSK-015,
-- ADR-0071 section 2, ADR-0070 section 4).
--
-- The break resolution's two P&L accounts, each posted by nothing but an APPROVED four-eyes
-- resolution through the ledger's owned approval:
--
--   RECONCILIATION_LOSSES  EXPENSE  a WRITE_OFF: an INBOUND remainder that will never arrive
--                                   (DR here / CR the position), or a DEBIT suspense item
--                                   nobody can recover (DR here / CR SUSPENSE_UNMATCHED).
--   RECONCILIATION_GAINS   REVENUE  a RECOGNISE_GAIN: a CREDIT suspense item older than the
--                                   pinned gain_min_age_days nobody claimed
--                                   (DR SUSPENSE_UNMATCHED / CR here).
--
-- WHY THIS TASK AND NOT EARLIER
--   Each purpose arrives with its first poster (AccountPurpose's own doctrine): nothing could
--   post here before the resolution machine exists.
--
-- WHY THIS IS A NEW MIGRATION AND NOT AN EDIT OF V016
--   V016 is applied history (ADR-0011). Adding enum members regenerates the four rules the
--   chart's enums feed, so the four constraints are RECREATED here from the enums' current
--   definitions, and LedgerAccountMigrationTest reconciles them against THIS file.
--
-- THE SEED ROWS
--   One per purpose per supported currency (SupportedCurrencies.ALL: EUR, GBP, USD),
--   hand-minted UUIDv7 literals below the 2026-09-28T00:00:00Z ceiling (the lock-order rule
--   OperationalChartMigrationTest holds) and literal timestamps. No account_balance rows: the
--   projection's ON CONFLICT upsert creates each on its first posting.
--
-- THE BINDING, RE-STATED (P8-TSK-006's V015, re-stated by V016 and now here)
--   Both join AccountPurpose.reconciledPositions(): only a RECONCILIATION-origin proposal
--   reaches them, so a MANUAL line on either is refused at the domain and by this trigger -
--   which is what makes "posted by nothing but approved resolutions" a database fact. The
--   generated list is reconciled by the migration test against THIS file.

ALTER TABLE ledger.ledger_account
    DROP CONSTRAINT ledger_account_owner_kind_is_known,
    ADD CONSTRAINT ledger_account_owner_kind_is_known
        CHECK (owner_kind IN ('CUSTOMER', 'MERCHANT', 'OPERATIONAL', 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_purpose_is_known,
    ADD CONSTRAINT ledger_account_purpose_is_known
        CHECK (purpose IN ('CUSTOMER_WALLET', 'MERCHANT_PAYABLE', 'SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'CHARGEBACK_RECOVERABLE', 'DISPUTE_COSTS', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'FEE_REVENUE', 'RECONCILIATION_GAINS', 'FX_POSITION', 'ROUNDING_RESIDUAL', 'SUSPENSE_UNMATCHED')),

    DROP CONSTRAINT ledger_account_owner_kind_matches_purpose,
    ADD CONSTRAINT ledger_account_owner_kind_matches_purpose
        CHECK ((purpose = 'CUSTOMER_WALLET' AND owner_kind = 'CUSTOMER') OR (purpose = 'MERCHANT_PAYABLE' AND owner_kind = 'MERCHANT') OR (purpose = 'SETTLEMENT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PAYOUT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'INSTANT_CLEARING' AND owner_kind = 'OPERATIONAL') OR (purpose = 'CHARGEBACK_RECOVERABLE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'DISPUTE_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'PROCESSING_COSTS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_LOSSES' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FEE_REVENUE' AND owner_kind = 'OPERATIONAL') OR (purpose = 'RECONCILIATION_GAINS' AND owner_kind = 'OPERATIONAL') OR (purpose = 'FX_POSITION' AND owner_kind = 'OPERATIONAL') OR (purpose = 'ROUNDING_RESIDUAL' AND owner_kind = 'OPERATIONAL') OR (purpose = 'SUSPENSE_UNMATCHED' AND owner_kind = 'SUSPENSE')),

    DROP CONSTRAINT ledger_account_owner_ref_matches_kind,
    ADD CONSTRAINT ledger_account_owner_ref_matches_kind
        CHECK ((owner_kind IN ('CUSTOMER', 'MERCHANT')) = (owner_ref IS NOT NULL));

INSERT INTO ledger.ledger_account
    (id, account_type, normal_balance, currency, owner_kind, owner_ref, purpose, gl_code,
     status, created_at, status_changed_at)
VALUES
    ('01a0e029-5400-7000-8000-000000000017', 'EXPENSE', 'DEBIT', 'EUR', 'OPERATIONAL', NULL, 'RECONCILIATION_LOSSES', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000018', 'EXPENSE', 'DEBIT', 'GBP', 'OPERATIONAL', NULL, 'RECONCILIATION_LOSSES', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-000000000019', 'EXPENSE', 'DEBIT', 'USD', 'OPERATIONAL', NULL, 'RECONCILIATION_LOSSES', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000001a', 'REVENUE', 'CREDIT', 'EUR', 'OPERATIONAL', NULL, 'RECONCILIATION_GAINS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000001b', 'REVENUE', 'CREDIT', 'GBP', 'OPERATIONAL', NULL, 'RECONCILIATION_GAINS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z'),
    ('01a0e029-5400-7000-8000-00000000001c', 'REVENUE', 'CREDIT', 'USD', 'OPERATIONAL', NULL, 'RECONCILIATION_GAINS', NULL, 'ACTIVE', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z');

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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'PROCESSING_COSTS', 'RECONCILIATION_LOSSES', 'RECONCILIATION_GAINS', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position is closed to free adjustments: value there moves only through a break resolution (ADR-0071, INV-REC-06, P8-TSK-006)';
    END IF;
    RETURN NEW;
END;
$$;
