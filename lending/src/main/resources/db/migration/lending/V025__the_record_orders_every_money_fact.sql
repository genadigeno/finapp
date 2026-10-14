-- The record orders every money fact (P11-TSK-029; PHASE_11_PLAN.md sections 12.8 and 13.1, ADR-0092 point 9, ADR-0096
-- point 3; INV-LND-01, INV-LND-04, INV-LND-05, INV-LND-09). The number is V025 - the next free lending version.
--
-- The replay proof (LoanReplayProof) re-derives every allocation, credit-balance application, billing, statement and accrual
-- from the state the rows left BEFORE it - so it needs the order in which every money fact of a loan took effect. That
-- order is the loan's row lock's (L3): every fact is written under it, so the facts of one transaction are contiguous and
-- each transaction's follow the previous holder's commit. Until this migration the record carried that order only as WALL
-- CLOCK stamps (created_at := statement_timestamp()), and a wall clock is not an order: a database clock stepped back -
-- an NTP step, a virtual machine's resync, the backward clock V019 already had to survive - stamps a later transaction's
-- facts before an earlier one's, and a replay walking the stamps re-derives a step against a state it never saw (found
-- by this task: a prepayment's agreement version 2 stamped half a second before the accruals of the catch-up that
-- preceded it). Two decisions were worse still: a waiver's (V021) and a repayment reversal's (V016, V024) were stamped
-- with transaction_timestamp() - the instant their transaction BEGAN, before it waited on L3.
--
--     lending.record_order        a sequence: nextval is taken in call order whatever the clock does, so a fact written
--                                 under L3 draws a number after every fact of the transaction that held L3 before it.
--     recorded_order              on every fact the replay walks - loan_agreement, repayment_schedule, credit_line_draw,
--                                 interest_accrual, instalment_billing, credit_line_statement, credit_balance_application,
--                                 fee_assessment, loan_amendment_rescheduling, repayment, loan_closure (DEFAULT
--                                 nextval: every domain writer inserts after taking L3) and waiver_decision (set by its
--                                 born-once trigger AFTER it takes L3 itself, for every writer).
--     repayment_reversal.decided_order
--                                 set by the reversal's edge function when the decision is written - a reversal decides
--                                 by UPDATE, so no default can stamp it.
--
-- Replaced, each its predecessor's body plus exactly the order: lending.waiver_decision_is_born_once (V021) and
-- lending.repayment_reversal_permits_only_machine_edges (V024) - each also stamps decided_at with clock_timestamp(), the
-- instant the decision is written after the locks it waited on, never the transaction's start. Rows written before this
-- migration keep a NULL order (the replay orders them by their stamps, before every ordered fact; Phase 11 has no
-- production loan - PHASE_11_PLAN.md section 1.1). The columns are no money and no one's data (INTERNAL); the application
-- role draws from the sequence (USAGE) and never sets an order itself through the domain. Nothing here writes money.

CREATE SEQUENCE lending.record_order AS bigint NO CYCLE;

COMMENT ON SEQUENCE lending.record_order IS
    'The order every lending money fact took effect in under its loan''s row lock (P11-TSK-029) - drawn by the facts'' recorded_order defaults and the decisions'' triggers; clock-independent, so the replay proof walks a loan''s history in the lock''s order whatever the database clock did.';

GRANT USAGE ON SEQUENCE lending.record_order TO finapp_app;

ALTER TABLE lending.loan_agreement ADD COLUMN recorded_order bigint;
ALTER TABLE lending.loan_agreement ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.loan_agreement.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.repayment_schedule ADD COLUMN recorded_order bigint;
ALTER TABLE lending.repayment_schedule ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.repayment_schedule.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.credit_line_draw ADD COLUMN recorded_order bigint;
ALTER TABLE lending.credit_line_draw ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.credit_line_draw.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.interest_accrual ADD COLUMN recorded_order bigint;
ALTER TABLE lending.interest_accrual ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.interest_accrual.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.instalment_billing ADD COLUMN recorded_order bigint;
ALTER TABLE lending.instalment_billing ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.instalment_billing.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.credit_line_statement ADD COLUMN recorded_order bigint;
ALTER TABLE lending.credit_line_statement ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.credit_line_statement.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.credit_balance_application ADD COLUMN recorded_order bigint;
ALTER TABLE lending.credit_balance_application ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.credit_balance_application.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.fee_assessment ADD COLUMN recorded_order bigint;
ALTER TABLE lending.fee_assessment ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.fee_assessment.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.loan_amendment_rescheduling ADD COLUMN recorded_order bigint;
ALTER TABLE lending.loan_amendment_rescheduling ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.loan_amendment_rescheduling.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.repayment ADD COLUMN recorded_order bigint;
ALTER TABLE lending.repayment ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.repayment.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.loan_closure ADD COLUMN recorded_order bigint;
ALTER TABLE lending.loan_closure ALTER COLUMN recorded_order SET DEFAULT nextval('lending.record_order');
COMMENT ON COLUMN lending.loan_closure.recorded_order IS
    'The fact''s place in its loan''s history - lending.record_order drawn at insert, under L3 (P11-TSK-029, V025); NULL for a fact written before V025. The replay proof''s order, never a clock.';

ALTER TABLE lending.waiver_decision ADD COLUMN recorded_order bigint;
COMMENT ON COLUMN lending.waiver_decision.recorded_order IS
    'The decision''s place in its loan''s history - lending.record_order drawn by the born-once trigger after it takes L3, for every writer (P11-TSK-029, V025); NULL before V025.';

ALTER TABLE lending.repayment_reversal ADD COLUMN decided_order bigint;
COMMENT ON COLUMN lending.repayment_reversal.decided_order IS
    'The decision''s place in its loan''s history - lending.record_order drawn by the edge function when the decision is written (P11-TSK-029, V025); NULL while PROPOSED and for a decision before V025.';

CREATE OR REPLACE FUNCTION lending.waiver_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.waiver%ROWTYPE;
    loan_status text;
    room bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a waiver decision is immutable from insert, for every role (P11-TSK-025, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM lending.waiver w WHERE w.id = NEW.waiver_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a waiver decision decides a waiver (P11-TSK-025)';
    END IF;
    -- (L3) The account, for every writer: the bound below is judged under the lock every payer of the item holds.
    SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF loan_status NOT IN ('ACTIVE', 'CLOSING') THEN
            RAISE EXCEPTION 'a waiver is approved only on an ACTIVE or CLOSING account - a closed account is terminal (P11-TSK-025, INV-LND-08)';
        END IF;
        -- Bounded by what is due now, from the rows (lending.WaiverExceedsDue at the domain): this decision is not yet
        -- counted by billing_component_billed.
        IF proposal.kind = 'FEE' THEN
            room := lending.billing_component_billed(proposal.due_item_id, 'FEES')
                    - lending.billing_component_paid(proposal.due_item_id, 'FEES');
        ELSIF proposal.kind = 'INTEREST' THEN
            room := lending.billing_component_billed(proposal.due_item_id, 'INTEREST')
                    - lending.billing_component_paid(proposal.due_item_id, 'INTEREST');
        ELSE
            room := lending.billing_component_paid(proposal.due_item_id, 'FEES')
                    - (SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w
                         JOIN lending.waiver_decision d ON d.waiver_id = w.id
                        WHERE w.fee_assessment_id = proposal.fee_assessment_id AND w.kind = 'FEE_REFUND'
                          AND d.outcome = 'APPROVED');
        END IF;
        IF proposal.amount_minor > coalesce(room, 0) THEN
            RAISE EXCEPTION 'a waiver is bounded by what is due now (P11-TSK-025, INV-REV-02)';
        END IF;
    END IF;
    -- P11-TSK-029: the decision's place in the loan's history and the instant it is written, both after L3 (taken above,
    -- for every writer) - never the transaction's start, which precedes the wait for the lock.
    NEW.recorded_order := nextval('lending.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lending.repayment_reversal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a repayment reversal is never deleted (P11-TSK-020, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL
                OR NEW.decided_order IS NOT NULL THEN
            RAISE EXCEPTION 'a repayment reversal is born PROPOSED and undecided: nothing is reversed by insert (P11-TSK-020)';
        END IF;
        -- A repayment of its own loan, named under the repayment's own agreement version (nothing is recomputed).
        SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.loan_id <> NEW.loan_id
                OR repayment.agreement_version <> NEW.agreement_version THEN
            RAISE EXCEPTION 'a repayment reversal names a repayment of its own loan, under its agreement version (P11-TSK-020)';
        END IF;
        -- A prepayment moved the agreement and the schedule (P11-TSK-028): swapping its entry would leave the schedule
        -- amortising less principal than the loan owes. Its correction is an amendment, never a reversal.
        IF repayment.kind = 'PREPAYMENT' THEN
            RAISE EXCEPTION 'a prepayment is never reversed - it moved the agreement; a correction is an amendment (P11-TSK-028, ADR-0099)';
        END IF;
        SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = NEW.loan_id;
        IF loan_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a loan not ACTIVE is never reversed - a closed loan is terminal (P11-TSK-020, A12, INV-LIFE-04)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.repayment_id IS DISTINCT FROM OLD.repayment_id
            OR NEW.agreement_version IS DISTINCT FROM OLD.agreement_version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a repayment reversal''s identity and proposal are frozen (P11-TSK-020)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a repayment reversal moves PROPOSED -> APPROVED | REJECTED; % -> % is not an edge (P11-TSK-020)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT l.status INTO loan_status FROM lending.loan l WHERE l.id = NEW.loan_id;
        IF loan_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a loan not ACTIVE is never reversed - a closed loan is terminal (P11-TSK-020, A12, INV-LIFE-04)';
        END IF;
    END IF;
    -- P11-TSK-029: the decision's place in the loan's history and the instant it is written, after the locks its approval
    -- holds (L0, L3, L5) - never the transaction's start, which precedes the wait for them.
    NEW.decided_order := nextval('lending.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;
