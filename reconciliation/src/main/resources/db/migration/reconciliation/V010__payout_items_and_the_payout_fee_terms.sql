-- P8-TSK-018: the payout provider's items, and the payout fee terms rule set v1 was seeded
-- without (ADR-0065 section 2, ADR-0068 sections 7-8; INV-SET-05, INV-REC-08, INV-HIST-04).
--
-- WHY A RECONCILIATION MIGRATION AT ALL
--   The P8-TSK-018 backlog entry recorded "Persistence: none new". It did not hold: the item's
--   vocabulary never admitted the payout provider's lines or its reference, and the fee check the
--   entry named had nothing to judge by - P8-TSK-004 seeded the payout source's rule set v1 with
--   no fee rule and no fee schedule, so a payout fee line would have met NO_RULE and waited
--   UNMATCHED forever, never checked. This migration took V010, so P8-TSK-022's run_replay moves
--   to V011 and P8-TSK-023's repudiation to V012 (recorded in the plan).
--
-- COMPLETING A FROZEN SEED - THE RECORDED DEVIATION
--   A rule set version is immutable: "a change is a NEW version" (ADR-0068 section 8,
--   INV-HIST-04), and the freeze triggers refuse every UPDATE and DELETE. A new version is not
--   available here - superseding v1 would UPDATE its status, which the freeze refuses - and the
--   point of the rule is that no STORED decision's explanation may change under it. So the payout
--   terms are appended to v1 ONLY while that is provably true: the block below refuses unless no
--   run was ever pinned to the payout rule set and no decision ever named it. Before this task no
--   payout report could parse (SIM_PAYOUT_CSV had no adapter), so no decision exists to explain
--   differently; once one does, the block refuses for every writer, and a later change is a new
--   version through P8-TSK-022's four-eyes flow.
--
-- The new vocabulary values are appended (declaration order): the enums' sqlValueList() is the
-- whole list, the migration test reconciles it.

-- -----------------------------------------------------------------------------------------------
-- The item's vocabulary (ExternalLineType, ItemKeyKind).
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.external_item
    DROP CONSTRAINT external_item_line_type,
    ADD CONSTRAINT external_item_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT', 'OTHER_IN', 'OTHER_OUT', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE', 'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE'));

ALTER TABLE reconciliation.external_item_key
    DROP CONSTRAINT external_item_key_kind,
    ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'ACQUIRER_REF', 'DISPUTE_REF', 'OUR_REF', 'ORIGINAL_REF', 'REMITTANCE_REF', 'SCHEME_REF', 'END_TO_END_REF', 'PAYOUT_PROVIDER_REF'));

-- -----------------------------------------------------------------------------------------------
-- The rule set's vocabulary: the payout fee is a rule line type and a fee schedule line type,
-- beside the PSP's, the scheme's and the bank's.
-- -----------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.rule
    DROP CONSTRAINT rule_line_type,
    ADD CONSTRAINT rule_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT',
        'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE',
        'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE',
        'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE'));

ALTER TABLE reconciliation.provider_fee_schedule
    DROP CONSTRAINT provider_fee_line_type,
    ADD CONSTRAINT provider_fee_line_type CHECK (line_type IN (
        'PROCESSING_FEE', 'SCHEME_FEE', 'BANK_FEE', 'PAYOUT_FEE'));

-- -----------------------------------------------------------------------------------------------
-- The payout fee terms, appended to the payout source's rule set v1 under the guard.
-- -----------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM reconciliation.reconciliation_batch
               WHERE rule_set_id = '01a0e2bd-8300-7003-8000-000000000003')
       OR EXISTS (SELECT 1 FROM reconciliation.match_decision
                  WHERE rule_set_id = '01a0e2bd-8300-7003-8000-000000000003') THEN
        RAISE EXCEPTION 'the payout rule set v1 has deciding history: its terms can no longer be completed in place - a change is a NEW version (ADR-0068 section 8, INV-HIST-04)';
    END IF;
END;
$$;

-- The fee line is JUDGED, never allocated: the CHECK cardinality, its original reached by the
-- line type's own key (PAYOUT_FEE -> PAYOUT_PROVIDER_REF). The pinned terms: a flat 0.25 per
-- payout, no rate. No fee tolerance row is seeded - an absent tolerance reads zero (F2,
-- conservative, never loosened), exactly as the scheme's.
INSERT INTO reconciliation.rule
    (rule_set_id, priority, line_type, key_kind, expectation_kind, cardinality,
     operation_anchored, grace_hours)
VALUES
    ('01a0e2bd-8300-7003-8000-000000000003', 5, 'PAYOUT_FEE', NULL, NULL, 'CHECK', false, 48);

INSERT INTO reconciliation.provider_fee_schedule
    (rule_set_id, line_type, currency, rate, fixed_minor, scale, rounding_policy)
VALUES
    ('01a0e2bd-8300-7003-8000-000000000003', 'PAYOUT_FEE', 'EUR', 0.000000, 25, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7003-8000-000000000003', 'PAYOUT_FEE', 'GBP', 0.000000, 25, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7003-8000-000000000003', 'PAYOUT_FEE', 'USD', 0.000000, 25, 2, 'HALF_UP');
