-- Adjustments carry a reason code and an origin; reconciled positions are closed to free
-- adjustments (P8-TSK-006, ADR-0071 sections 5-6, INV-REV-04, INV-REC-06).
--
-- WHY BOTH COLUMNS ARRIVE WITH DEFAULTS
--   ADD COLUMN ... DEFAULT backfills without firing the payload-freeze trigger (an ALTER is
--   not an UPDATE), so pre-Phase-8 history stays valid and reads UNCODED / MANUAL
--   (INV-HIST-01: the rows were written before codes existed, and inventing one now would be
--   rewriting the record). The defaults STAY: a raw insert that omits the code lands on
--   UNCODED, and the BEFORE INSERT trigger below refuses UNCODED for every writer - so
--   "a raw insert without a code fails at the database" holds through the default, not
--   despite it.
--
-- THE PAIRING AND THE LISTS ARE GENERATED
--   The value lists and the code->origin pairing are AdjustmentReasonCode's and
--   AdjustmentOrigin's own (sqlValueList(), sqlPairingRule()), reconciled by
--   AdjustmentProposalMigrationTest - one definition, two artefacts (the V014 pattern).
--   RECONCILIATION_OFFSET was dropped from ADR-0071's drafted set at this task's design
--   (the transition's B14): no Phase 8 kind produces it, and the platform keeps no
--   producerless member. A future offset-posting kind widens the CHECK forward.
--
-- THE BINDING: NO MANUAL LINE ON A RECONCILED POSITION
--   The BEFORE INSERT trigger on adjustment_proposal_line reads the proposal's origin (the
--   parent row is always inserted first - V010's store order - and the payload freeze means
--   the origin it reads is the origin that stands forever) and the account's purpose (frozen
--   at the account's birth). A MANUAL-origin line on a reconciled position is refused for
--   EVERY writer: value in a clearing or suspense account moves only through a system
--   posting or a reconciliation-owned, four-eyes resolution (ADR-0071; INV-REC-06's
--   precondition - without this, a generic adjustment could create unowned suspense or
--   unexplained clearing, or "fix" a difference by destroying the evidence a break existed).
--   The purpose list is AccountPurpose.reconciledPositions(), generated; ledger V016-V018
--   re-state it as the four Phase 8 purposes join.
--
-- THE FREEZE, RE-STATED
--   adjustment_proposal_permits_only_decision names each payload column explicitly, so it is
--   re-stated here (CREATE OR REPLACE) with the two new columns in the frozen payload: a
--   proposal cannot change its category or change hands after a person has read it.

ALTER TABLE ledger.adjustment_proposal
    ADD COLUMN reason_code text NOT NULL DEFAULT 'UNCODED',
    ADD COLUMN origin      text NOT NULL DEFAULT 'MANUAL',

    ADD CONSTRAINT adjustment_proposal_reason_code_is_known
        CHECK (reason_code IN ('MANUAL_CORRECTION', 'RECONCILIATION_WRITE_OFF', 'RECONCILIATION_TRANSFER', 'RECONCILIATION_GAIN', 'UNCODED')),
    ADD CONSTRAINT adjustment_proposal_origin_is_known
        CHECK (origin IN ('MANUAL', 'RECONCILIATION')),
    -- The code->origin pairing, generated from the enum (AdjustmentReasonCode.sqlPairingRule):
    -- history satisfies it (UNCODED pairs with MANUAL, the backfill pair).
    ADD CONSTRAINT adjustment_proposal_code_matches_origin
        CHECK ((reason_code = 'MANUAL_CORRECTION' AND origin = 'MANUAL') OR (reason_code = 'RECONCILIATION_WRITE_OFF' AND origin = 'RECONCILIATION') OR (reason_code = 'RECONCILIATION_TRANSFER' AND origin = 'RECONCILIATION') OR (reason_code = 'RECONCILIATION_GAIN' AND origin = 'RECONCILIATION') OR (reason_code = 'UNCODED' AND origin = 'MANUAL'));

-- UNCODED is history's backfill value, never a new row's (INV-REV-04): refused at INSERT for
-- every writer, the migrator included.
CREATE OR REPLACE FUNCTION ledger.adjustment_proposal_requires_a_reason_code()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reason_code = 'UNCODED' THEN
        RAISE EXCEPTION 'a new adjustment proposal carries a real reason code - UNCODED is history''s backfill value (INV-REV-04, P8-TSK-006)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER adjustment_proposal_requires_a_reason_code
    BEFORE INSERT ON ledger.adjustment_proposal
    FOR EACH ROW
    EXECUTE FUNCTION ledger.adjustment_proposal_requires_a_reason_code();

-- The binding: a MANUAL-origin line may not touch a reconciled position, for any writer.
-- The purpose list is AccountPurpose.reconciledPositions(), generated and reconciled by the
-- migration test; V016-V018 re-state it as PROCESSING_COSTS, RECONCILIATION_LOSSES,
-- RECONCILIATION_GAINS and CASH_AT_BANK join.
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
        AND account_purpose IN ('SETTLEMENT_CLEARING', 'PAYOUT_CLEARING', 'INSTANT_CLEARING', 'SUSPENSE_UNMATCHED') THEN
        RAISE EXCEPTION 'a reconciled position is closed to free adjustments: value there moves only through a break resolution (ADR-0071, INV-REC-06, P8-TSK-006)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER adjustment_line_respects_reconciled_positions
    BEFORE INSERT ON ledger.adjustment_proposal_line
    FOR EACH ROW
    EXECUTE FUNCTION ledger.adjustment_line_respects_reconciled_positions();

-- The payload freeze, re-stated with the two new columns (V010's trigger named each column
-- explicitly, so widening the payload means re-stating the function - the same trigger
-- binding stands).
CREATE OR REPLACE FUNCTION ledger.adjustment_proposal_permits_only_decision()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.posting_date <> NEW.posting_date
        OR OLD.value_date <> NEW.value_date
        OR OLD.reference <> NEW.reference
        OR OLD.reason <> NEW.reason
        OR OLD.reason_code <> NEW.reason_code
        OR OLD.origin <> NEW.origin
        OR OLD.proposed_by <> NEW.proposed_by
        OR OLD.proposed_at <> NEW.proposed_at THEN
        RAISE EXCEPTION 'a proposal''s payload is immutable: the approver approves what they read (P3-TSK-021, INV-AUD-04)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED'
            AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a proposal''s only edges are PROPOSED -> APPROVED and PROPOSED -> REJECTED (INV-LIFE-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN ledger.adjustment_proposal.reason_code IS
    'The closed category of the justification (INV-REV-04, ADR-0071 section 5): '
    'MANUAL_CORRECTION for the generic door (assigned server-side), the RECONCILIATION_* '
    'codes for break resolutions, UNCODED for pre-V015 history only (refused on insert).';
COMMENT ON COLUMN ledger.adjustment_proposal.origin IS
    'Whose machinery may decide this proposal (ADR-0071 section 6): each door refuses the '
    'other''s proposals, so a resolution''s ledger half is decidable only by the flow that '
    'also moves the break.';
