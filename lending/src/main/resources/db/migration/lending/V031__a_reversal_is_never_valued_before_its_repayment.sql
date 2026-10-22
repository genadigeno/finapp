-- A repayment reversal is never valued before the repayment it reverses (the Phase 12 -> 13 transition gate's A2-1, repair
-- R3; ADR-0099 point 4, ADR-0063 decision 2; INV-LND-10, INV-LND-11). The number is V031 - the next free lending version,
-- reserved for R3 on the gate's repair board.
--
-- RepaymentReversals valued the reversal max(today, floor) - the floor read under L3 (the Phase 11 gate's R1, V030) - and
-- the floor has no arm for the reversed repayment's own date. On a database clock stepped back across a midnight - the
-- repayment valued D, the reversal approved while the clock shows D - 1 and D - 1 not yet accrued - the reversal was valued
-- D - 1: the re-opened amount then stands twice on D - 1 (the repayment's credit not yet valued, the reversal's debit
-- already), and the accrual of D - 1 charges a day's interest on it twice. Every accrual reads the same lines, so the replay
-- re-derived it IDENTICAL. The domain now values it max(today, floor, the repayment's value date).
--
-- Lending's triggers never read the ledger (P11-TSK-014's rule) and V016's reversal row carried no value date, so no rank
-- could see the reversal's date. This adds it to the row - the date the approval's entry is valued, written by the domain
-- beside the entry it names - and V025's edge function, replaced, holds it for every writer:
--
--     an APPROVED reversal names its value date (a REJECTED one, and a proposal, none); it is never before the loan's floor
--     (lending.principal_value_floor, V030 - R1's rank, now held for the reversal too) and NEVER BEFORE THE REPAYMENT IT
--     REVERSES (A2-1), and never after the later of the latest date the clock showed this transaction, the floor and that
--     repayment's own date.
--
-- That the entry is valued on the row's date is the replay's to prove (LoanReplayProof's reversal arm reads the entry's
-- lines - the ledger, every writer - and finds a reversal valued before its repayment REVERSAL). Rows decided before this
-- migration keep a NULL value date (their entries carry theirs; Phase 11 has no production loan - PHASE_11_PLAN.md section
-- 1.1). The column is no money and no one's data (INTERNAL); the application role may write it only beside the decision
-- (the column-scoped UPDATE grant), and the edge function decides whether it may.
--
-- Replaced: lending.repayment_reversal_permits_only_machine_edges (V025's body, plus the value date above). Migrator-owned;
-- like its predecessor schema-qualified throughout and invoker's rights, so no search_path is set.

ALTER TABLE lending.repayment_reversal ADD COLUMN value_date date;

COMMENT ON COLUMN lending.repayment_reversal.value_date IS
    'The value date of the approval''s reversal entry (the Phase 12 transition gate''s A2-1, V031): max(today, the loan''s floor, the reversed repayment''s value date), written by the domain beside the entry it names; never before the repayment it reverses, held for every writer by the edge function. NULL while PROPOSED, when REJECTED, and for a decision written before V031.';

GRANT UPDATE (value_date) ON lending.repayment_reversal TO finapp_app;

CREATE OR REPLACE FUNCTION lending.repayment_reversal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_status text;
    zone text;
    value_floor date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a repayment reversal is never deleted (P11-TSK-020, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL
                OR NEW.decided_order IS NOT NULL OR NEW.value_date IS NOT NULL THEN
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
        -- The Phase 12 transition gate's A2-1: the approval names the date its entry is valued - never before the loan's
        -- floor (the Phase 11 gate's R1), never before the repayment it reverses, never after the later of the latest date
        -- the clock showed, the floor and that repayment's own date.
        IF NEW.value_date IS NULL THEN
            RAISE EXCEPTION 'an approved repayment reversal names the value date of its entry (the Phase 12 transition gate''s A2-1)';
        END IF;
        SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
        SELECT t.servicing_zone INTO zone
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id;
        value_floor := lending.principal_value_floor(NEW.loan_id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(lending.business_date_latest(zone), value_floor, repayment.value_date) THEN
            RAISE EXCEPTION 'a repayment reversal is valued on or after its loan''s floor and never after today (the Phase 11 gate R1, ADR-0063 decision 2)';
        END IF;
        IF NEW.value_date < repayment.value_date THEN
            RAISE EXCEPTION 'a repayment reversal is never valued before the repayment it reverses (the Phase 12 transition gate''s A2-1)';
        END IF;
    ELSIF NEW.value_date IS NOT NULL THEN
        RAISE EXCEPTION 'a rejected repayment reversal posts nothing and names no value date (the Phase 12 transition gate''s A2-1)';
    END IF;
    -- P11-TSK-029: the decision's place in the loan's history and the instant it is written, after the locks its approval
    -- holds (L0, L3, L5) - never the transaction's start, which precedes the wait for them.
    NEW.decided_order := nextval('lending.record_order');
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION lending.repayment_reversal_permits_only_machine_edges() IS
    'A repayment reversal''s machine for every writer (P11-TSK-020, V016; V024, V025; the Phase 12 transition gate''s A2-1, V031): born PROPOSED and undecided for a non-prepayment repayment of its own ACTIVE loan; identity frozen; PROPOSED -> APPROVED | REJECTED only, an approval only while ACTIVE and naming its value date in [max(floor, the repayment''s value date), max(latest date the clock showed, floor, the repayment''s value date)]; the decision ordered and stamped after its locks.';
