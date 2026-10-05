-- The FX provider's reconciliation vocabulary (P9-TSK-011; PHASE_9_PLAN.md sections 12.9.1-12.9.3,
-- ADR-0069 amended; INV-REC-06, INV-REC-08).
--
-- Every addition is appended at the end of its enum and each list below is the enum's WHOLE
-- sqlValueList() (ReconciliationV020MigrationTest reconciles them):
--   ExpectationKind  + FX_SELL_LEG (OUTBOUND), FX_BUY_LEG (INBOUND) - a cover's two legs, each on
--                      FX_PROVIDER_CLEARING(provider) in its own currency; opened by the cover entry through
--                      FxSettlementExpectations (P9-TSK-012 posts the first)
--   KeyKind          + COVER_REF (the platform's cover reference Tn - the key), FX_TRADE_REF (an alias)
--   ItemKeyKind      + COVER_REF, FX_TRADE_REF (the settlement mirror)
--   ExternalLineType + FX_SOLD, FX_BOUGHT, FX_FEE (the settlement mirror); FX_FEE allocates nothing and
--                      names its original by COVER_REF
--   rule_line_type   + FX_SOLD, FX_BOUGHT, FX_FEE (RuleSetProposal.RULE_LINE_TYPES)
--   provider_fee_line_type + FX_FEE - a PRICED fee line (RuleSetProposal.FEE_LINE_TYPES): the FX source's
--                      v1 prices it 0 + 0 in every currency, so any reported FX fee is a FEE_MISMATCH
--   BreakCause       + FX_LEG_DIFFERS (AMOUNT_MISMATCH: a leg settled for another amount - reconciliation
--                      never converts, so a rate difference IS a leg's amount difference),
--                      VALUE_DATE_DIFFERS (TIMING_DIFFERENCE, a timing detector's cause)
-- with the raise-pairing trigger (V011) and V014's timing-cause list re-stated. No fifteenth break type.

ALTER TABLE reconciliation.expectation
    DROP CONSTRAINT expectation_kind,
    ADD CONSTRAINT expectation_kind CHECK (kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG'));

ALTER TABLE reconciliation.rule
    DROP CONSTRAINT rule_expectation_kind,
    ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG')),
    DROP CONSTRAINT rule_key_kind,
    ADD CONSTRAINT rule_key_kind CHECK (key_kind IS NULL OR key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF', 'ORIGINAL_REF')),
    DROP CONSTRAINT rule_line_type,
    ADD CONSTRAINT rule_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'FX_SOLD', 'FX_BOUGHT', 'FX_FEE'));

ALTER TABLE reconciliation.rule_set_lag
    DROP CONSTRAINT rule_set_lag_kind,
    ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG'));

ALTER TABLE reconciliation.expectation_key
    DROP CONSTRAINT expectation_key_kind,
    ADD CONSTRAINT expectation_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF'));

ALTER TABLE reconciliation.reference_alias
    DROP CONSTRAINT reference_alias_kind,
    ADD CONSTRAINT reference_alias_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF')),
    DROP CONSTRAINT reference_alias_anchor_kind,
    ADD CONSTRAINT reference_alias_anchor_kind CHECK (anchor_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF'));

ALTER TABLE reconciliation.match_candidate
    DROP CONSTRAINT match_candidate_key_kind,
    ADD CONSTRAINT match_candidate_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF'));

ALTER TABLE reconciliation.match_decision
    DROP CONSTRAINT match_decision_key_kind,
    ADD CONSTRAINT match_decision_key_kind CHECK (matched_key_kind IS NULL OR matched_key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF', 'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'COVER_REF', 'FX_TRADE_REF'));

ALTER TABLE reconciliation.external_item
    DROP CONSTRAINT external_item_line_type,
    ADD CONSTRAINT external_item_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE', 'FX_SOLD', 'FX_BOUGHT', 'FX_FEE'));

ALTER TABLE reconciliation.external_item_key
    DROP CONSTRAINT external_item_key_kind,
    ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'COVER_REF', 'FX_TRADE_REF'));

ALTER TABLE reconciliation.provider_fee_schedule
    DROP CONSTRAINT provider_fee_line_type,
    ADD CONSTRAINT provider_fee_line_type CHECK (line_type IN (
        'PROCESSING_FEE', 'SCHEME_FEE', 'BANK_FEE', 'PAYOUT_FEE', 'FX_FEE'));

ALTER TABLE reconciliation.break
    DROP CONSTRAINT break_cause,
    ADD CONSTRAINT break_cause CHECK (cause IN (
        'EXPECTATION_OVERDUE', 'GRACE_EXPIRED', 'PARKED_ON_RECEIPT', 'BANK_LINE_UNATTRIBUTED', 'AMOUNT_DIFFERS', 'CURRENCY_DIFFERS', 'FEE_BEYOND_TOLERANCE', 'EXPECTATION_EXHAUSTED', 'REPEATED_FINGERPRINT', 'KEY_COLLISION', 'MULTIPLE_CANDIDATES', 'LATE_MATCH', 'CYCLE_MISMATCH', 'DIRECTION_CONTRADICTED', 'TERMINAL_STATE_CONTRADICTED', 'RETURN_NOT_APPLICABLE', 'REFUND_CONTRADICTED', 'REMITTANCE_DIFFERS', 'STATEMENT_GAP', 'OPENING_BALANCE', 'ITEM_ERRORED', 'RUN_BLOCKED', 'REPLAY_DIVERGED', 'EVIDENCE_REPUDIATED', 'EXECUTION_ALREADY_EXPLAINED', 'FX_LEG_DIFFERS', 'VALUE_DATE_DIFFERS'));

-- The raise-pairing rule (BreakCause.sqlRaisePairingRule()), re-stated whole; the V004 trigger binding stands.
CREATE OR REPLACE FUNCTION reconciliation.break_is_raised_by_its_own_detector()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT ((NEW.cause = 'EXPECTATION_OVERDUE' AND NEW.type IN ('MISSING_EXTERNAL')) OR (NEW.cause = 'GRACE_EXPIRED' AND NEW.type IN ('MISSING_INTERNAL', 'UNKNOWN_EXTERNAL')) OR (NEW.cause = 'PARKED_ON_RECEIPT' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'BANK_LINE_UNATTRIBUTED' AND NEW.type IN ('UNKNOWN_EXTERNAL')) OR (NEW.cause = 'AMOUNT_DIFFERS' AND NEW.type IN ('AMOUNT_MISMATCH')) OR (NEW.cause = 'CURRENCY_DIFFERS' AND NEW.type IN ('CURRENCY_MISMATCH')) OR (NEW.cause = 'FEE_BEYOND_TOLERANCE' AND NEW.type IN ('FEE_MISMATCH')) OR (NEW.cause = 'EXPECTATION_EXHAUSTED' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'REPEATED_FINGERPRINT' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'KEY_COLLISION' AND NEW.type IN ('DUPLICATE_INTERNAL')) OR (NEW.cause = 'MULTIPLE_CANDIDATES' AND NEW.type IN ('AMBIGUOUS_MATCH')) OR (NEW.cause = 'LATE_MATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'CYCLE_MISMATCH' AND NEW.type IN ('TIMING_DIFFERENCE')) OR (NEW.cause = 'DIRECTION_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'TERMINAL_STATE_CONTRADICTED' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'RETURN_NOT_APPLICABLE' AND NEW.type IN ('REVERSAL_MISMATCH')) OR (NEW.cause = 'REFUND_CONTRADICTED' AND NEW.type IN ('REFUND_MISMATCH')) OR (NEW.cause = 'REMITTANCE_DIFFERS' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'STATEMENT_GAP' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'OPENING_BALANCE' AND NEW.type IN ('SETTLEMENT_MISMATCH')) OR (NEW.cause = 'ITEM_ERRORED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'RUN_BLOCKED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'REPLAY_DIVERGED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'EVIDENCE_REPUDIATED' AND NEW.type IN ('PROCESSING_ERROR')) OR (NEW.cause = 'EXECUTION_ALREADY_EXPLAINED' AND NEW.type IN ('DUPLICATE_EXTERNAL')) OR (NEW.cause = 'FX_LEG_DIFFERS' AND NEW.type IN ('AMOUNT_MISMATCH')) OR (NEW.cause = 'VALUE_DATE_DIFFERS' AND NEW.type IN ('TIMING_DIFFERENCE'))) THEN
        RAISE EXCEPTION 'a break is raised only by its own detector: % never raises % (ADR-0069 section 2)',
            NEW.cause, NEW.type;
    END IF;
    RETURN NEW;
END;
$$;

-- The timing-cause list (ResolutionTemplates.timingCause), re-stated: VALUE_DATE_DIFFERS is a timing
-- detector's cause, so its zero-value ACKNOWLEDGE is one person's (the V014 trigger binding stands).
CREATE OR REPLACE FUNCTION reconciliation.resolution_acknowledgement_four_eyes_by_break()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    break_type  TEXT;
    break_cause TEXT;
    one_person  BOOLEAN;
BEGIN
    IF NEW.kind = 'ACKNOWLEDGE' AND NEW.proposed_amount_minor = 0 THEN
        -- FOR SHARE: a concurrent raw type change waits for this insert's transaction, and this
        -- read waits for its - the flag is derived from the type that stands (the second gate
        -- pass's find; the application path already holds the break FOR UPDATE).
        SELECT b.type, b.cause INTO break_type, break_cause
            FROM reconciliation.break b WHERE b.id = NEW.break_id FOR SHARE;
        -- ResolutionTemplates.timingCause: the causes whose detector raises TIMING_DIFFERENCE.
        one_person := coalesce(break_type = 'TIMING_DIFFERENCE'
                AND break_cause IN ('LATE_MATCH', 'CYCLE_MISMATCH', 'VALUE_DATE_DIFFERS'), false);
        IF NEW.four_eyes = one_person THEN
            RAISE EXCEPTION 'resolution_four_eyes_derived_from_the_break: a zero-value ACKNOWLEDGE is one person''s only on a TIMING_DIFFERENCE raised by a timing detector, four-eyes on a % break raised by % (ADR-0071 section 3, P8-TST-002)', coalesce(break_type, 'missing'), coalesce(break_cause, 'missing');
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
