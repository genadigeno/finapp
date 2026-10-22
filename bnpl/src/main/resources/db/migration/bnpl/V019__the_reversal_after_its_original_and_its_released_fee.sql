-- The Phase 12 -> 13 transition gate's repair R3 (A2-1, A7-3; ADR-0104, BNPL_LIFECYCLES.md section 4.11; INV-LND-10 and
-- INV-LND-11 by analogy, INV-BNPL-01, INV-BNPL-06, OQ-14). The number is V019: the repair board reserved V018 for R1 and V019
-- for R2, which used none (merchant V012 alone), so the coordinator gave V019 to this repair, merged last (R1 -> R2 -> R3; Flyway
-- runs with out-of-order off). Nothing here replaces a function V018 does.
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
-- 2. A REVERSAL RELEASES THE LATE FEE IT RE-OPENS ON AN INSTALMENT A REFUND CANCELLED WHOLE (A7-3, OQ-14). A refund that
--    cancels a billed instalment's whole unpaid principal releases the instalment's unpaid late fee (V013: a fee on an
--    instalment that no longer exists is not owed). But a fee already PAID when the refund came is not released - and an
--    approved reversal of the repayment that paid it negates that payment exactly (V016), re-opening the fee on an
--    instalment the refund had cancelled whole: owed after the reversal, though the same two acts in the other order (the
--    reversal first, then the refund) release it. Now the reversal releases it in its own transaction, by the refund's
--    rule, so both orders reach one end state:
--
--        bnpl_reversal_fee_release   - one row per instalment an earlier refund cancelled whole (a BILLED_UNPAID adjustment
--                                      with whole) on which the reversal re-opened a fee: exactly the FEES due on it once
--                                      the negating rows are written, posted DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE under ONE
--                                      entry of the reversal's own (bnpl.reversal-fee-release:<reversal>), never F8's.
--                                      INSERT-only, under B4, only beside an APPROVED reversal of its obligation.
--        bnpl.billing_component_paid - V016's body, FEES plus these releases (what a refund's release counts, V013).
--        bnpl.fee_waiver_room        - V016's body, the never-forgiven arm less these releases too (the Phase 11 gate's R3:
--                                      nothing released is forgiven again).
--        bnpl_repayment_reversal_releases_refunded_fees
--                                    - at commit, for every writer: an APPROVED reversal leaves no FEES due on an
--                                      instalment a refund of its obligation cancelled whole - so a raw approval that
--                                      re-opens such a fee without releasing it never commits.
--
-- Replaced: bnpl.bnpl_repayment_reversal_permits_only_machine_edges (V016's body and the change in 1),
-- bnpl.billing_component_paid and bnpl.fee_waiver_room (V016's bodies and the releases in 2). New: the release table, its
-- born-once trigger and the reversal's commit-time rank. Migrator-owned; like their predecessors schema-qualified
-- throughout and invoker's rights, so no search_path is set.

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
    'A BNPL repayment reversal''s machine for every writer (P12-TSK-024, V016; the Phase 12 transition gate''s A2-1, V019): born PROPOSED and undecided on an ACTIVE obligation for a non-PAYOFF repayment of it under its agreement version; identity frozen; PROPOSED -> APPROVED | REJECTED only; an approval only while ACTIVE, valued in [max(floor, the repayment''s value date), max(latest date the clock showed, floor, the repayment''s value date)] - never before the floor (R1) and never before the repayment it reverses (A2-1).';

-- ---------------------------------------------------------------------------------------------
-- 2. The reversal's release of a fee OQ-14 releases (A7-3).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_reversal_fee_release (
    id               uuid     NOT NULL,
    reversal_id      uuid     NOT NULL,
    obligation_id    uuid     NOT NULL,
    instalment_id    uuid     NOT NULL,
    currency         char(3)  NOT NULL,
    scale            smallint NOT NULL,
    amount_minor     bigint   NOT NULL,
    journal_entry_id uuid     NOT NULL,
    CONSTRAINT bnpl_reversal_fee_release_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_reversal_fee_release_reversal_fk FOREIGN KEY (reversal_id)
        REFERENCES bnpl.bnpl_repayment_reversal (id),
    CONSTRAINT bnpl_reversal_fee_release_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    -- Each instalment once per reversal: its fee is released whole, never in part.
    CONSTRAINT bnpl_reversal_fee_release_once UNIQUE (reversal_id, instalment_id),
    CONSTRAINT bnpl_reversal_fee_release_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_reversal_fee_release_amount_positive CHECK (amount_minor > 0)
);

COMMENT ON TABLE bnpl.bnpl_reversal_fee_release IS
    'A BNPL repayment reversal''s release of a late fee it re-opened (the Phase 12 transition gate''s A7-3, V019; OQ-14, INV-BNPL-06): on an instalment an earlier refund cancelled whole, the fee its negating rows made due again - exactly what is due of the instalment''s FEES once they are written - released in the reversal''s own transaction under one entry of its own (DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE, bnpl.reversal-fee-release:<reversal>). bnpl.billing_component_paid counts it as FEES settled, bnpl.fee_waiver_room as forgiven. INSERT-only for every role (RESTRICTED-FINANCIAL).';

CREATE INDEX bnpl_reversal_fee_release_by_instalment ON bnpl.bnpl_reversal_fee_release (instalment_id);
CREATE INDEX bnpl_reversal_fee_release_by_obligation ON bnpl.bnpl_reversal_fee_release (obligation_id, reversal_id);

CREATE OR REPLACE FUNCTION bnpl.bnpl_reversal_fee_release_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    reversal bnpl.bnpl_repayment_reversal%ROWTYPE;
    billing uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL reversal''s fee release is INSERT-only, for every role (the Phase 12 transition gate''s A7-3, INV-HIST-01)';
    END IF;
    -- (B4) The obligation, for every writer - re-entrant for the domain, which holds it: an ACTIVE one.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL fee release is written only on an ACTIVE obligation (the Phase 12 transition gate''s A7-3, INV-LIFE-04)';
    END IF;
    -- Its reversal: APPROVED - its decision written first, in this transaction - of this obligation, the release under an
    -- entry of its own (never F8's), one entry for all its releases.
    SELECT * INTO reversal FROM bnpl.bnpl_repayment_reversal r WHERE r.id = NEW.reversal_id;
    IF reversal.id IS NULL OR reversal.status <> 'APPROVED' OR reversal.obligation_id <> NEW.obligation_id
            OR NEW.journal_entry_id = reversal.journal_entry_id
            OR EXISTS (SELECT 1 FROM bnpl.bnpl_reversal_fee_release x
                        WHERE x.reversal_id = NEW.reversal_id AND x.journal_entry_id <> NEW.journal_entry_id) THEN
        RAISE EXCEPTION 'a BNPL fee release belongs to an APPROVED reversal of its own obligation, under one entry of its own (the Phase 12 transition gate''s A7-3)';
    END IF;
    -- OQ-14's instalment: one a refund of this obligation cancelled whole.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_refund_adjustment a
                    WHERE a.instalment_id = NEW.instalment_id AND a.obligation_id = NEW.obligation_id
                      AND a.kind = 'BILLED_UNPAID' AND a.whole) THEN
        RAISE EXCEPTION 'a reversal releases a late fee only on an instalment a refund cancelled whole (OQ-14, the Phase 12 transition gate''s A7-3)';
    END IF;
    -- Exactly what is due of its FEES now - the fee the negating rows re-opened - never more, never in part.
    SELECT b.id INTO billing FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id;
    IF billing IS NULL OR NEW.amount_minor <> bnpl.billing_component_billed(billing, 'FEES')
                                             - bnpl.billing_component_paid(billing, 'FEES') THEN
        RAISE EXCEPTION 'a reversal releases exactly the late fee still due on its instalment (OQ-14, the Phase 12 transition gate''s A7-3)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION bnpl.bnpl_reversal_fee_release_is_born_once() IS
    'A BNPL reversal''s fee release, for every writer (the Phase 12 transition gate''s A7-3, V019): INSERT-only; under B4 on an ACTIVE obligation; beside an APPROVED reversal of it, under one entry of its own; only on an instalment a refund cancelled whole; exactly the FEES due on it.';

CREATE TRIGGER bnpl_reversal_fee_release_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_reversal_fee_release
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_reversal_fee_release_is_born_once();

CREATE TRIGGER bnpl_reversal_fee_release_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_reversal_fee_release
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- What a billing's component has had settled, replaced: V016's body, FEES plus the reversals' releases.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM bnpl.bnpl_credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation r
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component)
          - (SELECT coalesce(sum(n.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation_reversal n
               JOIN bnpl.bnpl_repayment_allocation r ON r.id = n.allocation_id
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component)
          + (SELECT coalesce(sum(CASE paid_component WHEN 'PRINCIPAL' THEN x.principal_cancelled_minor
                                                     WHEN 'INTEREST' THEN x.interest_released_minor
                                                     WHEN 'FEES' THEN x.late_fee_released_minor
                                                     ELSE 0 END), 0)
               FROM bnpl.bnpl_refund_adjustment x
               JOIN bnpl.bnpl_billing b ON b.instalment_id = x.instalment_id
              WHERE b.id = billing AND x.kind = 'BILLED_UNPAID')
          + (SELECT coalesce(sum(f.amount_minor), 0) FROM bnpl.bnpl_reversal_fee_release f
               JOIN bnpl.bnpl_billing b ON b.instalment_id = f.instalment_id
              WHERE b.id = billing AND paid_component = 'FEES'))::bigint
$$;

COMMENT ON FUNCTION bnpl.billing_component_paid(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) has had settled - by every payer (credit-balance applications, P12-TSK-015; repayments, P12-TSK-018) less what an approved reversal un-paid (the negating rows, P12-TSK-024), by every refund''s cancellation of its principal, release of its interest (P12-TSK-021) and release of its late fees (OQ-14, P12-TSK-020), and by a reversal''s release of a late fee it re-opened on an instalment a refund cancelled whole (the Phase 12 transition gate''s A7-3, V019). What is still due is billed less this.';

-- ---------------------------------------------------------------------------------------------
-- What of an assessment may still be forgiven, replaced: V016's body, the reversals' releases never forgiven again.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.fee_waiver_room(assessment uuid)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT LEAST(
        bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id, 'FEES'),
        f.amount_minor
            - (SELECT coalesce(sum(w.amount_minor), 0) FROM bnpl.bnpl_waiver w
                 JOIN bnpl.bnpl_waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'
                WHERE w.fee_assessment_id = f.id)
            - (SELECT coalesce(sum(x.late_fee_released_minor), 0) FROM bnpl.bnpl_refund_adjustment x
                WHERE x.instalment_id = f.instalment_id AND x.kind = 'BILLED_UNPAID')
            - (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_reversal_fee_release r
                WHERE r.instalment_id = f.instalment_id))::bigint
      FROM bnpl.bnpl_fee_assessment f
      JOIN bnpl.bnpl_billing b ON b.instalment_id = f.instalment_id
     WHERE f.id = assessment
$$;

COMMENT ON FUNCTION bnpl.fee_waiver_room(uuid) IS
    'What of a BNPL fee assessment may still be waived (P12-TSK-024; the Phase 11 gate''s R3; the Phase 12 transition gate''s A7-3, V019): least(its instalment''s FEES billed less settled, the assessment less its approved waivers less the refunds'' and the reversals'' releases on its instalment) - so nothing released, paid or waived is ever forgiven again. Read under B4.';

-- ---------------------------------------------------------------------------------------------
-- At commit, for every writer: an approved reversal leaves no late fee due on an instalment a refund cancelled whole.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_reversal_releases_refunded_fees()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND EXISTS (
            SELECT 1 FROM bnpl.bnpl_refund_adjustment a
              JOIN bnpl.bnpl_billing b ON b.instalment_id = a.instalment_id
             WHERE a.obligation_id = NEW.obligation_id AND a.kind = 'BILLED_UNPAID' AND a.whole
               AND bnpl.billing_component_billed(b.id, 'FEES') > bnpl.billing_component_paid(b.id, 'FEES')) THEN
        RAISE EXCEPTION 'an approved BNPL repayment reversal leaves no late fee due on an instalment a refund cancelled whole - it releases what it re-opens (OQ-14, the Phase 12 transition gate''s A7-3)';
    END IF;
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION bnpl.bnpl_repayment_reversal_releases_refunded_fees() IS
    'At the commit of an APPROVED BNPL repayment reversal, for every writer (the Phase 12 transition gate''s A7-3, V019): no instalment a refund of its obligation cancelled whole has any FEES due - the fee the reversal re-opened there is released beside it (bnpl_reversal_fee_release), as the refund would have released it (OQ-14).';

CREATE CONSTRAINT TRIGGER bnpl_repayment_reversal_releases_refunded_fees
    AFTER UPDATE ON bnpl.bnpl_repayment_reversal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_reversal_releases_refunded_fees();

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only. Never UPDATE, DELETE or TRUNCATE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_reversal_fee_release TO finapp_app;
