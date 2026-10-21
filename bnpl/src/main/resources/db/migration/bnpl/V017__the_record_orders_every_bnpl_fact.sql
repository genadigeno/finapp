-- The record orders every BNPL fact (P12-TST-001's production defect; lending V025's fix, P11-TSK-029, carried to bnpl;
-- PHASE_12_PLAN.md section 12.12, ADR-0103; INV-BNPL-01, INV-BNPL-05, INV-BNPL-06). The number is V017 - the next free bnpl
-- version (left free by P12-TSK-027 and -028).
--
-- The replay proof (BnplReplayProof, P12-TSK-025) re-derives every billing, application, allocation, refund, fee and payoff
-- of an obligation from the state the facts before it left - so it needs the order in which they took effect. That order is
-- the obligation's row lock's (B4): every fact is written under it, so the facts of one transaction are contiguous and each
-- transaction's follow the previous holder's commit. Until this migration the record carried that order only as WALL CLOCK
-- stamps (clock_timestamp(), the triggers' and the stores'), and a wall clock is not an order: a database clock stepped back -
-- an NTP step, a virtual machine's resync (measured up to 1.7 s on the test VM; ADR-0063) - stamps a later fact before an
-- earlier one, within one transaction as well as across two, and a replay walking the stamps re-derives a step against a
-- state it never saw. Found by P12-TST-001's first load round: a pay-in-4 purchase whose instalment-1 billing was stamped
-- 0.57 s BEFORE its own schedule version 1 in the same transaction (BILLING, ALLOCATION), and a repayment stamped before the
-- purchase that created its obligation had committed (ALLOCATION, APPLICATION_MISSING) - each a false DIVERGED on a correct
-- history, the replay gauge and the investigator's door alerting on nothing. Lending learned it in Phase 11 (V025); bnpl's
-- record kept the clock.
--
--     bnpl.record_order   a sequence: nextval is taken in call order whatever the clock does, so a fact written under B4
--                         draws a number after every fact of the transaction that held B4 before it.
--     recorded_order      on every fact the replay walks (DEFAULT nextval - every bnpl writer inserts after taking B4, and
--                         the purchase's transaction creates the obligation): merchant_financing, bnpl_agreement, bnpl_schedule, bnpl_billing, bnpl_interest_accrual, bnpl_credit_balance_application, bnpl_repayment, bnpl_collection_attempt, bnpl_refund, bnpl_condition_event, bnpl_fee_assessment, bnpl_payoff_quote, bnpl_payoff_execution, bnpl_obligation_closure, bnpl_repayment_reversal, bnpl_waiver, bnpl_waiver_decision, bnpl_dispute, bnpl_dispute_event.
--     bnpl_repayment_reversal.decided_order
--                         set when the decision is written - a reversal decides by UPDATE, so no default can order it: a
--                         trigger of its own, after the edge trigger has judged the edge (triggers of one event fire in
--                         name order), for every writer; the application role holds no UPDATE of the column.
--
-- The record store (JdbcBnplRecordStore) walks the obligation's facts in this order and re-stamps them monotonically - each
-- fact at the later of its own stamp and a microsecond after the fact before it - so every comparison the proofs make reads
-- the lock's order, never a clock. Rows written before this migration keep a NULL order (ordered by their stamps, before
-- every ordered fact); Phase 12 has no production obligation (PHASE_12_PLAN.md section 1.1). The columns are no money and no
-- one's data (INTERNAL); the application role draws from the sequence (USAGE) and never sets an order itself. Each column is
-- added then given its default - two statements, so existing rows are never rewritten with fresh numbers. Nothing here
-- writes money.

CREATE SEQUENCE bnpl.record_order AS bigint NO CYCLE;

COMMENT ON SEQUENCE bnpl.record_order IS
    'The order every BNPL fact took effect in under its obligation''s row lock (P12-TST-001, V017; lending V025''s rule) - drawn by the facts'' recorded_order defaults and the reversal''s decision trigger; clock-independent, so the replay proof walks an obligation''s history in the lock''s order whatever the database clock did.';

GRANT USAGE ON SEQUENCE bnpl.record_order TO finapp_app;

ALTER TABLE bnpl.merchant_financing ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.merchant_financing ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.merchant_financing.recorded_order IS
    'The financing''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_agreement ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_agreement ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_agreement.recorded_order IS
    'An agreement version''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_schedule ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_schedule ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_schedule.recorded_order IS
    'A schedule version''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_billing ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_billing ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_billing.recorded_order IS
    'A billing''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_interest_accrual ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_interest_accrual ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_interest_accrual.recorded_order IS
    'An accrual''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_credit_balance_application ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_credit_balance_application ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_credit_balance_application.recorded_order IS
    'A credit-balance application''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_repayment ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_repayment ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_repayment.recorded_order IS
    'A repayment''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_collection_attempt ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_collection_attempt ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_collection_attempt.recorded_order IS
    'A collection attempt''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_refund ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_refund ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_refund.recorded_order IS
    'A refund''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_condition_event ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_condition_event ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_condition_event.recorded_order IS
    'A condition change''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_fee_assessment ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_fee_assessment ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_fee_assessment.recorded_order IS
    'A late fee''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_payoff_quote ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_payoff_quote ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_payoff_quote.recorded_order IS
    'A payoff quote''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_payoff_execution ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_payoff_execution ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_payoff_execution.recorded_order IS
    'The payoff''s execution''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_obligation_closure ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_obligation_closure ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_obligation_closure.recorded_order IS
    'The closure''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_repayment_reversal ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_repayment_reversal ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_repayment_reversal.recorded_order IS
    'A reversal''s proposal''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_waiver ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_waiver ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_waiver.recorded_order IS
    'A waiver''s proposal''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_waiver_decision ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_waiver_decision ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_waiver_decision.recorded_order IS
    'A waiver''s decision''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_dispute ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_dispute ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_dispute.recorded_order IS
    'A dispute''s opening''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_dispute_event ADD COLUMN recorded_order bigint;
ALTER TABLE bnpl.bnpl_dispute_event ALTER COLUMN recorded_order SET DEFAULT nextval('bnpl.record_order');
COMMENT ON COLUMN bnpl.bnpl_dispute_event.recorded_order IS
    'A dispute''s transition''s place in its obligation''s history - bnpl.record_order drawn at insert, under B4 (P12-TST-001, V017); NULL for a fact written before V017. The replay proof''s order, never a clock.';

ALTER TABLE bnpl.bnpl_repayment_reversal ADD COLUMN decided_order bigint;
COMMENT ON COLUMN bnpl.bnpl_repayment_reversal.decided_order IS
    'The reversal''s decision''s place in its obligation''s history - bnpl.record_order drawn when PROPOSED moves to APPROVED or REJECTED, under B4, by bnpl_repayment_reversal_stamps_its_decided_order for every writer (P12-TST-001, V017); NULL while PROPOSED and for a decision written before V017. The replay proof''s order, never a clock.';

CREATE FUNCTION bnpl.bnpl_repayment_reversal_stamps_its_decided_order() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- The edge trigger (bnpl_repayment_reversal_permits_only_machine_edges, which fires first - name order) has judged the
    -- edge; this one only orders it: the decision draws its number as it is written, and no writer sets one.
    IF OLD.status = 'PROPOSED' AND NEW.status <> 'PROPOSED' THEN
        NEW.decided_order := nextval('bnpl.record_order');
    ELSE
        NEW.decided_order := OLD.decided_order;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bnpl.bnpl_repayment_reversal_stamps_its_decided_order() IS
    'Orders a BNPL repayment reversal''s decision (P12-TST-001, V017): decided_order drawn from bnpl.record_order as PROPOSED moves on, kept as it was on any other update - after the edge trigger judged the edge.';

CREATE TRIGGER bnpl_repayment_reversal_stamps_its_decided_order
    BEFORE UPDATE ON bnpl.bnpl_repayment_reversal
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_reversal_stamps_its_decided_order();
