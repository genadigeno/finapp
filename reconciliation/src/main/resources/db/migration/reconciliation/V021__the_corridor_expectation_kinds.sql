-- The corridor's expectation kinds join the reconciliation vocabulary (P9-TSK-014; ADR-0082 section 3,
-- PHASE_9_PLAN.md section 12.9.2; INV-SET-02, INV-REC-06).
--
-- WHAT IS APPENDED (each list is ExpectationKind's WHOLE sqlValueList(), reconciled by
-- ReconciliationV021MigrationTest)
--   ExpectationKind + CROSSBORDER_PAYOUT (OUTBOUND, keyed END_TO_END_REF = E, alias PAYOUT_PROVIDER_REF),
--                     CROSSBORDER_RETURN (INBOUND, operation-anchored - no key of its own) - each on
--                     CORRIDOR_CLEARING(rail). Opened by the outbound credit's completion (P9-TSK-019)
--                     and its return fact (P9-TSK-023); admitted now with the corridor's source, so the
--                     source's version-1 rule set can name them.
-- with their mirrors: the rule's expectation kind and the lag's. The corridor reuses the payout line
-- types (PAYOUT_EXECUTED, PAYOUT_RETURNED, PAYOUT_FEE) and keys (END_TO_END_REF, PAYOUT_PROVIDER_REF),
-- so no other list changes. No rule set is seeded (D26): the corridor source's v1 goes through
-- RuleSetAdministration's first-version door, four-eyes (OPERATIONS_RUNBOOK).

ALTER TABLE reconciliation.expectation
    DROP CONSTRAINT expectation_kind,
    ADD CONSTRAINT expectation_kind CHECK (kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN'));

ALTER TABLE reconciliation.rule
    DROP CONSTRAINT rule_expectation_kind,
    ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN'));

ALTER TABLE reconciliation.rule_set_lag
    DROP CONSTRAINT rule_set_lag_kind,
    ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN'));
