-- The record orders every money decision (P11-TSK-029; PHASE_11_PLAN.md sections 12.8 and 13.1, ADR-0092 point 9, ADR-0096
-- point 3; INV-LND-01, INV-LND-04, INV-LND-05, INV-LND-09). The number is V025 - the next free lending version.
--
-- The replay proof (LoanReplayProof) re-derives every allocation, credit-balance application, billing and statement from the
-- due state the rows left BEFORE it - so it needs the order in which every money-moving fact of a loan took effect. Every
-- such fact is written under the loan's row lock (L3) and stamped with the database clock of its own statement, so its
-- stamp falls after the lock was granted, after every fact of the transaction that held the lock before it, and before
-- every fact of the one after: the stamps ARE the lock's order. Two were not: a waiver's decision (V021) and a repayment
-- reversal's decision (V016, V024) were stamped with transaction_timestamp() - the instant their transaction BEGAN, before
-- it waited on L3. A decision queued behind a servicing step was therefore stamped before that step's billings and
-- applications although it took effect after them, and a replay ordering by the record would re-derive the step against
-- a due state it never saw - a DIVERGED that is the record's fault, not the money's (the 10-instance question: two acts
-- racing on L3 are exactly the case).
--
-- Replaced, each its predecessor's body with exactly one change - the decision is stamped with clock_timestamp(), the
-- instant the trigger runs, after every lock its statement and its transaction waited on (the waiver's trigger takes L3
-- itself, for every writer; the reversal's decision is written by its approval after L3): lending.waiver_decision_is_born_once
-- (V021) and lending.repayment_reversal_permits_only_machine_edges (V024). No column, no constraint and no grant changes;
-- rows decided before this migration keep the stamps they have (Phase 11 has no production loan - PHASE_11_PLAN.md
-- section 1.1). Nothing here writes money.

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
    -- P11-TSK-029: the instant the decision is written, after L3 (taken above, for every writer) - never the
    -- transaction's start, which precedes the wait for the lock.
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
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL THEN
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
    -- P11-TSK-029: the instant the decision is written, after the locks its approval holds (L0, L3, L5) - never the
    -- transaction's start, which precedes the wait for them.
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;
