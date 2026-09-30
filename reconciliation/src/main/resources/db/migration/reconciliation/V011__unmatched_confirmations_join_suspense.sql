-- P8-TSK-020: Phase 7's unmatched confirmations join suspense (ADR-0070 section 2's
-- UNMATCHED_CONFIRMATION row and section 8, ADR-0069 section 2; INV-REC-05, INV-REC-09).
--
-- WHY A RECONCILIATION MIGRATION AT ALL
--   The P8-TSK-020 backlog entry recorded "Persistence: none new" - suspense_item's
--   UNMATCHED_CONFIRMATION origin and the break exist (V004). Two facts did not hold:
--
--   1. THE BREAK COULD NOT BE BORN. A parking's owner is an UNKNOWN_EXTERNAL break whose SUBJECT is
--      the suspense item (ADR-0069 section 2's Subject column). suspense_item.break_id must name an
--      existing break (its NOT NULL foreign key and the owner-type trigger), while
--      break.suspense_item_id must name an existing suspense item (break_suspense_item_fk) and is
--      frozen once raised - so neither row can be inserted first. No writer had raised a
--      suspense-item-subject break before. The key is made DEFERRABLE INITIALLY IMMEDIATE: every
--      writer keeps immediate checking, and only the parking's opener defers it for the two
--      inserts (SET CONSTRAINTS ... DEFERRED, the break, the item, SET CONSTRAINTS ... IMMEDIATE -
--      which checks at once, so nothing waits for commit).
--
--   2. A PARKING V023'S BACKFILL LEFT UNCLAIMED IS NOT UNATTRIBUTED VALUE (ADR-0070 point 8, its
--      recorded design input). One scheme execution a credit, a withdrawal or a return already
--      explains must be adopted so that its value is never attributed a second time. It is raised
--      as the duplicate it is - DUPLICATE_EXTERNAL under the new cause EXECUTION_ALREADY_EXPLAINED -
--      whose admitted resolutions exclude the transfer (ResolutionTemplates).
--
--   This migration takes V011, so P8-TSK-022's run_replay moves to V012 and P8-TSK-023's
--   repudiation to V013 (recorded in the plan). The new cause is appended (declaration order):
--   BreakCause.sqlValueList() is the whole list, and the migration test reconciles it.

-- -----------------------------------------------------------------------------------------------
-- The owner's subject may be born in the same transaction as its owned item.
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.break
    ALTER CONSTRAINT break_suspense_item_fk DEFERRABLE INITIALLY IMMEDIATE;

-- -----------------------------------------------------------------------------------------------
-- The cause vocabulary (BreakCause) and its raise-time pairing, regenerated whole.
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.break
    DROP CONSTRAINT break_cause,
    ADD CONSTRAINT break_cause CHECK (cause IN (
        'EXPECTATION_OVERDUE', 'GRACE_EXPIRED', 'PARKED_ON_RECEIPT', 'BANK_LINE_UNATTRIBUTED', 'AMOUNT_DIFFERS', 'CURRENCY_DIFFERS', 'FEE_BEYOND_TOLERANCE', 'EXPECTATION_EXHAUSTED', 'REPEATED_FINGERPRINT', 'KEY_COLLISION', 'MULTIPLE_CANDIDATES', 'LATE_MATCH', 'CYCLE_MISMATCH', 'DIRECTION_CONTRADICTED', 'TERMINAL_STATE_CONTRADICTED', 'RETURN_NOT_APPLICABLE', 'REFUND_CONTRADICTED', 'REMITTANCE_DIFFERS', 'STATEMENT_GAP', 'OPENING_BALANCE', 'ITEM_ERRORED', 'RUN_BLOCKED', 'REPLAY_DIVERGED', 'EVIDENCE_REPUDIATED', 'EXECUTION_ALREADY_EXPLAINED'));

CREATE OR REPLACE FUNCTION reconciliation.break_is_raised_by_its_own_detector()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT ((NEW.cause = 'EXPECTATION_OVERDUE' AND NEW.type IN ('MISSING_EXTERNAL')) OR (NEW.cause = 'GRACE_EXPIRED' AND NEW.type IN ('MISSING_INTERNAL', 'UNKNOWN_EXTERNAL')) OR (NEW.cause = 'PARKED_ON_RECEIPT' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'BANK_LINE_UNATTRIBUTED' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'AMOUNT_DIFFERS' AND NEW.type IN ('AMOUNT_MISMATCH')) OR (NEW.cause = 'CURRENCY_DIFFERS' AND NEW.type IN ('CURRENCY_MISMATCH')) OR (NEW.cause = 'FEE_BEYOND_TOLERANCE' AND NEW.type IN ('FEE_MISMATCH')) OR (NEW.cause = 'EXPECTATION_EXHAUSTED' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'REPEATED_FINGERPRINT' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'KEY_COLLISION' AND NEW.type IN ('DUPLICATE_INTERNAL')) OR (NEW.cause = 'MULTIPLE_CANDIDATES' AND NEW.type IN ('AMBIGUOUS_MATCH')) OR (NEW.cause = 'LATE_MATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'CYCLE_MISMATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'DIRECTION_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'TERMINAL_STATE_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'RETURN_NOT_APPLICABLE' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'REFUND_CONTRADICTED' AND NEW.type IN ('REFUND_MISMATCH')) OR (NEW.cause = 'REMITTANCE_DIFFERS' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'STATEMENT_GAP' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'OPENING_BALANCE' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'ITEM_ERRORED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'RUN_BLOCKED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'REPLAY_DIVERGED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'EVIDENCE_REPUDIATED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'EXECUTION_ALREADY_EXPLAINED' AND NEW.type IN ('DUPLICATE_EXTERNAL'))) THEN
        RAISE EXCEPTION 'a break is raised only by its own detector: % never raises % (ADR-0069 section 2)',
            NEW.cause, NEW.type;
    END IF;
    RETURN NEW;
END;
$$;
