-- Lending capital is recognised from the bank's own statement, and from nothing else (P11-TSK-004; ADR-0096
-- section 4, PHASE_11_PLAN.md section 12.9; INV-LND-13, INV-SET-06, INV-REC-06).
--
-- THE EXPECTATION KIND. A four-eyes capital contribution's approval (lending) opens a
-- LENDING_CAPITAL_CONTRIBUTION expectation: INBOUND on the currency's LENDING_CAPITAL account, under the
-- settlement bank's statement source, keyed REMITTANCE_REF = the contribution's reference. Like a REMITTANCE it
-- records no posting of ours - the approval posts nothing; capital exists only once the cash is on a statement.
-- Each list below is ExpectationKind's WHOLE sqlValueList(), reconciled by ReconciliationV022MigrationTest.
--
-- THE RECOGNITION IS THE MATCH, IN THE STATEMENT'S OWN ACCEPTANCE. Bank recognition (settlement's
-- BatchAcceptance) stays the one poster of CASH_AT_BANK: a statement credit line whose reference reaches an OPEN
-- contribution expectation under the statement's own source, in its currency, for EXACTLY its amount, inside its
-- window, is credited to LENDING_CAPITAL in the statement's one recognition entry instead of to suspense - and
-- matched there, by a RECOGNITION decision (verdict CAPITAL_RECOGNISED), never by a run. The decision's two new
-- values join their CHECKs; capital_recognition records which entry each recognised contribution explains.
--
-- WHAT EVERY WRITER IS HELD TO, beneath the domain:
--   * a contribution's expectation is never aged (no overdue_since) - its remainder is no position of the books,
--     so an overdue break on it would hold nothing to dispose of and a write-off would credit capital into being;
--   * it is allocated only by a RECOGNITION decision (or countered by a repudiation's counter-allocation);
--   * a RECOGNITION decision is always CAPITAL_RECOGNISED and the converse;
--   * capital_recognition is born once per contribution and once per statement line, and never edited.

-- ---------------------------------------------------------------------------------------------
-- The vocabulary.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE reconciliation.expectation
    DROP CONSTRAINT expectation_kind,
    ADD CONSTRAINT expectation_kind CHECK (kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN', 'LENDING_CAPITAL_CONTRIBUTION'));

ALTER TABLE reconciliation.rule
    DROP CONSTRAINT rule_expectation_kind,
    ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN', 'LENDING_CAPITAL_CONTRIBUTION'));

ALTER TABLE reconciliation.rule_set_lag
    DROP CONSTRAINT rule_set_lag_kind,
    ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE', 'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN', 'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE', 'FX_SELL_LEG', 'FX_BUY_LEG', 'CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN', 'LENDING_CAPITAL_CONTRIBUTION'));

-- V002's "a REMITTANCE records no posting of ours" becomes ExpectationKind.recordsAPosting(): the two statement
-- kinds record none, every other kind records exactly its completion's entry.
ALTER TABLE reconciliation.expectation
    DROP CONSTRAINT expectation_remittance_has_no_entry,
    ADD CONSTRAINT expectation_records_its_posting CHECK (
        (kind IN ('REMITTANCE', 'LENDING_CAPITAL_CONTRIBUTION')) = (journal_entry_id IS NULL)),
    -- ExpectationKind.agedByReconciliation(): the ageing sweep never stamps a contribution overdue.
    ADD CONSTRAINT expectation_capital_is_never_aged CHECK (
        kind <> 'LENDING_CAPITAL_CONTRIBUTION' OR overdue_since IS NULL);

-- DecisionOrigin and DecisionVerdict, whole, reconciled by ReconciliationV022MigrationTest.
ALTER TABLE reconciliation.match_decision
    DROP CONSTRAINT match_decision_origin,
    ADD CONSTRAINT match_decision_origin CHECK (origin IN (
        'RUN', 'REMATCH', 'REPROCESS', 'MANUAL', 'RECOGNITION')),
    DROP CONSTRAINT match_decision_verdict,
    ADD CONSTRAINT match_decision_verdict CHECK (verdict IS NULL OR verdict IN (
        'ALLOCATE', 'AMBIGUOUS', 'DIRECTION_CONTRADICTED', 'CURRENCY_CONTRADICTED', 'DUPLICATE', 'NO_CANDIDATES', 'NO_RULE', 'GROUP_MATCH', 'GROUP_NO_CANDIDATES', 'GROUP_TOTAL_DIFFERS', 'GROUP_MEMBERSHIP_MOVED', 'TOP_UP', 'OFFSET', 'CORRECTION_UNREACHED', 'FEE_WITHIN', 'FEE_BEYOND', 'MANUAL_CHOICE', 'ERRORED', 'CAPITAL_RECOGNISED')),
    -- A recognition is a capital recognition, and only a recognition concludes one.
    ADD CONSTRAINT match_decision_recognition_is_capital CHECK (
        (origin = 'RECOGNITION') = (verdict = 'CAPITAL_RECOGNISED'));

-- ---------------------------------------------------------------------------------------------
-- A contribution's expectation is allocated by a recognition alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.allocation_to_capital_is_recognition()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reverses_allocation_id IS NULL
            AND EXISTS (SELECT 1 FROM reconciliation.expectation e
                        WHERE e.id = NEW.expectation_id AND e.kind = 'LENDING_CAPITAL_CONTRIBUTION')
            AND NOT EXISTS (SELECT 1 FROM reconciliation.match_decision d
                            WHERE d.id = NEW.decision_id AND d.origin = 'RECOGNITION') THEN
        RAISE EXCEPTION 'a capital contribution is matched only by bank recognition of its statement line (INV-LND-13, P11-TSK-004): never by a run, a rematch or a person';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER allocation_to_capital_is_recognition
    BEFORE INSERT ON reconciliation.allocation
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.allocation_to_capital_is_recognition();

-- ---------------------------------------------------------------------------------------------
-- The recognitions: which entry each recognised contribution explains.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.capital_recognition (
    id               UUID        NOT NULL,
    expectation_id   UUID        NOT NULL,
    external_item_id UUID        NOT NULL,
    decision_id      UUID        NOT NULL,
    batch_id         UUID        NOT NULL,
    journal_entry_id UUID        NOT NULL,
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    scale            SMALLINT    NOT NULL,
    recognised_on    DATE        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    correlation_id   TEXT        NOT NULL,

    CONSTRAINT capital_recognition_pk PRIMARY KEY (id),
    -- Born once per contribution and once per statement line - the arbiters a duplicated or re-imported
    -- line, a retried acceptance and ten racing acceptors all meet.
    CONSTRAINT capital_recognition_once_per_contribution UNIQUE (expectation_id),
    CONSTRAINT capital_recognition_once_per_line UNIQUE (external_item_id),
    CONSTRAINT capital_recognition_expectation_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT capital_recognition_item_fk FOREIGN KEY (external_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT capital_recognition_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT capital_recognition_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT capital_recognition_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT capital_recognition_scale_bounded CHECK (scale BETWEEN 0 AND 9)
);

CREATE INDEX capital_recognition_by_entry ON reconciliation.capital_recognition (journal_entry_id);
CREATE INDEX capital_recognition_by_currency ON reconciliation.capital_recognition (currency);

COMMENT ON TABLE reconciliation.capital_recognition IS
    'Rule 0 applied (P11-TSK-004, ADR-0096 section 4): one row per contribution whose statement line bank recognition credited to LENDING_CAPITAL, naming the recognition entry (ledger, no cross-schema key) and the settlement batch. LendingCapitalProof proves every capital journal line against these rows. Amounts RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION reconciliation.capital_recognition_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a capital recognition is history, append-only for every writer (INV-LND-13, INV-HIST-01)';
END;
$$;

CREATE TRIGGER capital_recognition_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.capital_recognition
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.capital_recognition_is_append_only();

GRANT SELECT, INSERT ON reconciliation.capital_recognition TO finapp_app;
