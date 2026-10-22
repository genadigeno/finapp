-- The Phase 12 -> 13 transition gate's repair R3 (A2-1, A7-3; ADR-0104, BNPL_LIFECYCLES.md section 4.11; INV-LND-10 and
-- INV-LND-11 by analogy, INV-BNPL-01, INV-BNPL-06, OQ-14). The number is V020: the repair board reserves V018 for R1 and V019
-- for R2, and merges R1 -> R2 -> R3 (Flyway runs with out-of-order off). Nothing here replaces a function V018 or V019 does.
--
-- 1. A REVERSAL IS NEVER VALUED BEFORE THE REPAYMENT IT REVERSES (A2-1). V016 judged an approval's value date against the
--    obligation's floor alone: [floor, max(latest date the clock showed, floor)]. The floor has no arm for the reversed
--    repayment's own date, so on a database clock stepped back across a midnight - the repayment valued D, the reversal
--    approved while the clock shows D - 1 and D - 1 not yet accrued - the reversal could be valued D - 1: the re-opened
--    amount then stands twice on D - 1 (the repayment's credit not yet valued, the reversal's debit already), and pay-in-N's
--    accrual of D - 1 charges a day's interest on it twice. Every accrual reads the same lines, so the replay re-derived it
--    IDENTICAL. Now the domain values it max(today, floor, the repayment's value date) and the edge trigger refuses, for
--    every writer, an approval valued before the repayment - its ceiling raised by exactly that date, so the domain's value
--    is always admitted.
--
-- Replaced: bnpl.bnpl_repayment_reversal_permits_only_machine_edges (V016's body and the change above). Migrator-owned;
-- like its predecessor schema-qualified throughout and invoker's rights, so no search_path is set.

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_reversal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    repayment bnpl.bnpl_repayment%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL repayment reversal is never deleted (P12-TSK-024, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.journal_entry_id IS NOT NULL
                OR NEW.value_date IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is born PROPOSED and undecided: nothing is reversed by insert (P12-TSK-024)';
        END IF;
        -- (B4) The obligation, for every writer: an ACTIVE one.
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
        IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a BNPL obligation not ACTIVE is never reversed - a closed obligation is terminal (P12-TSK-024, INV-LIFE-04)';
        END IF;
        -- A repayment of its own obligation, named under the repayment's own agreement version (nothing is recomputed) -
        -- never a payoff's (P12-TSK-022).
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.obligation_id <> NEW.obligation_id
                OR repayment.agreement_id <> NEW.agreement_id OR repayment.agreement_version <> NEW.agreement_version THEN
            RAISE EXCEPTION 'a BNPL repayment reversal names a repayment of its own obligation, under its agreement version (P12-TSK-024)';
        END IF;
        IF repayment.kind = 'PAYOFF' THEN
            RAISE EXCEPTION 'a PAYOFF repayment closed its obligation and is never reversed (P12-TSK-022, P12-TSK-024)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.obligation_id IS DISTINCT FROM OLD.obligation_id
            OR NEW.repayment_id IS DISTINCT FROM OLD.repayment_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id
            OR NEW.agreement_version IS DISTINCT FROM OLD.agreement_version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL repayment reversal''s identity and proposal are frozen (P12-TSK-024)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a BNPL repayment reversal moves PROPOSED -> APPROVED | REJECTED; % -> % is not an edge (P12-TSK-024)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        -- (B4) The obligation, for every writer: still ACTIVE.
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
        IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a repayment of a BNPL obligation not ACTIVE is never reversed - a closed obligation is terminal (P12-TSK-024, INV-LIFE-04)';
        END IF;
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        -- The principal re-instated never joins a date already accrued (the Phase 11 gate's R1): valued on or after the
        -- floor read under B4, never after the later of the latest date the clock showed, the floor and the repayment's
        -- own date (A2-1 - the one date a clock stepped back may leave ahead of both).
        value_floor := bnpl.principal_value_floor(NEW.obligation_id);
        IF NEW.value_date < value_floor
                OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor,
                                             repayment.value_date) THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is valued on or after its obligation''s floor and never after today (P12-TSK-024, the Phase 11 gate''s R1)';
        END IF;
        -- A2-1: never before the repayment it reverses - a date between the two would carry the re-opened amount twice.
        IF NEW.value_date < repayment.value_date THEN
            RAISE EXCEPTION 'a BNPL repayment reversal is never valued before the repayment it reverses (the Phase 12 transition gate''s A2-1)';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bnpl.bnpl_repayment_reversal_permits_only_machine_edges() IS
    'A BNPL repayment reversal''s machine for every writer (P12-TSK-024, V016; the Phase 12 transition gate''s A2-1, V020): born PROPOSED and undecided on an ACTIVE obligation for a non-PAYOFF repayment of it under its agreement version; identity frozen; PROPOSED -> APPROVED | REJECTED only; an approval only while ACTIVE, valued in [max(floor, the repayment''s value date), max(latest date the clock showed, floor, the repayment''s value date)] - never before the floor (R1) and never before the repayment it reverses (A2-1).';
