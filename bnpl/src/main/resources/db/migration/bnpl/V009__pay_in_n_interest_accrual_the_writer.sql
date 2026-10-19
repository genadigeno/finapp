-- Pay-in-N interest accrual: the writer (P12-TSK-017; PHASE_12_PLAN.md sections 7.1 T-acc and 12.11 F2a; ADR-0101
-- decision 11, ADR-0103 F2a; INV-LND-02, INV-LND-09 and INV-LND-10 by analogy, INV-IDEM-02, INV-BAL-05, INV-BNPL-01;
-- lending V010's shape, with the Phase 11 gate's R1 floor already in place from bnpl V008).
--
-- bnpl V008 (P12-TSK-015) created the accrual RECORD, bnpl.bnpl_interest_accrual - UNIQUE (obligation_id,
-- accrual_date), INSERT-only for every role, never truncated - and granted the application role SELECT only, so that the
-- billing's interest is held to rows for every writer from the first billing and the principal value floor has its
-- accrual arm (max(accrual_date) + 1) from the first table. This migration adds the WRITER, T-acc (BnplAccruals):
--
--   * one transaction per catch-up, its own - B4 (the obligation FOR UPDATE), the database clock read after it, every
--     elapsed date of the obligation's accrual span accrued oldest first, each date's row and (when it posts something)
--     F2a's entry DR BNPL_INTEREST_ACCRUED / CR BNPL_INTEREST_INCOME under bnpl.accrual:<obligation>:<date>, value-dated
--     the date - and NO wallet and NO payable, so the currency's shared BNPL_INTEREST_INCOME row (taken at L8 inside
--     T-acc) is never held across a wallet wait (the Phase 11 gate's A2-F1, designed out);
--   * the accrual span: from the purchase's business date (the schedule's start) up to, not including, the final due
--     date of the latest schedule version - the instalment periods [start, D1), [D1, D2), ... [D(n-1), Dn), each the
--     span ACCRUAL_ENGINE_V1 rounds within once. After the final due date nothing accrues: there is no billing to make it
--     due and PHASE_12_PLAN.md section 12.3 admits no penalty interest (P12-TSK-017's refinement, recorded in its entry);
--   * a date accrues on the principal outstanding at its end - BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE over the journal
--     lines valued on or before it, read in one statement under B4, never interest or fees, never the projection
--     (INV-BAL-05). A date with no principal outstanding writes no row; a date whose posting rounds to zero writes its
--     row and no entry.
--
-- The trigger is REPLACED: V008's ranks (an ACTIVE pay-in-N obligation - pay-in-4, whose terms CHECK the rate to zero,
-- never accrues; its currency and agreement; never early on the database clock in the pinned zone; never before the
-- purchase's business date; oldest first, each date once; never into a period already billed) plus the writer's:
-- B4 re-taken for every writer, so the ranks are judged under the lock every servicing act on the obligation holds;
-- the agreement version in force and the accrual engine it pins (INV-LND-09); the period an instalment period of the
-- obligation's latest schedule version, so nothing accrues at or after the final due date; and the period's exact
-- cumulative figure never falling within it (ACCRUAL_ENGINE_V1's chain).
--
-- No bnpl trigger reads a ledger table (the P12-TSK-013 refinement): the base's derivation from journal lines and the
-- entry's agreement with the row are the one transaction's and the subledger proof's (P12-TSK-025): BNPL_INTEREST_ACCRUED
-- = sum of posted accruals - sum of billed interest (- released, P12-TSK-021). Every instant is the database's,
-- clock_timestamp() after the act's locks. The correction key bnpl.accrual-correction:<obligation>:<date>:<engine> is
-- reserved; nothing posts under it.

CREATE OR REPLACE FUNCTION bnpl.bnpl_interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    previous_cumulative numeric(40, 20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P12-TSK-015, INV-LND-02)';
    END IF;
    -- (B4) The obligation, for every writer: every rank below is judged under the lock every servicing act on it holds
    -- (T-acc holds it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    -- A pay-in-N obligation in servicing - pay-in-4 is interest-free (A4) and never accrues.
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.product <> 'BNPL_PAY_IN_N'
            OR obligation.currency <> NEW.currency OR obligation.agreement_id <> NEW.agreement_id THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE pay-in-N obligation, in its currency, under its agreement (P12-TSK-015)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - and the accrual engine it pins (INV-LND-09).
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version AND a.obligation_id = NEW.obligation_id;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR agreement.accrual_engine IS DISTINCT FROM NEW.accrual_engine THEN
        RAISE EXCEPTION 'an accrual names its obligation''s agreement version in force and the accrual engine it pins (P12-TSK-017, INV-LND-09)';
    END IF;
    -- Never early (INV-LND-10 by analogy): the date has ended on the database clock in the pinned zone; never before the
    -- purchase's business date.
    IF NEW.accrual_date >= bnpl.business_date_latest(obligation.servicing_zone)
            OR NEW.accrual_date < obligation.purchase_date THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock, from the purchase''s business date (P12-TSK-015)';
    END IF;
    -- Within an instalment period of the latest schedule version: [the previous due date or the schedule's start, the
    -- instalment's due date) - so nothing accrues at or after the final due date (P12-TSK-017).
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                  WHERE x.obligation_id = NEW.obligation_id);
    IF schedule.id IS NULL OR schedule.start_date <> obligation.purchase_date OR NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_instalment i
             WHERE i.schedule_id = schedule.id AND i.due_date = NEW.period_end
               AND NEW.period_start = COALESCE((SELECT p.due_date FROM bnpl.bnpl_instalment p
                                                 WHERE p.schedule_id = schedule.id AND p.sequence = i.sequence - 1),
                                               schedule.start_date)) THEN
        RAISE EXCEPTION 'an accrual lies in an instalment period of its obligation''s latest schedule version, never at or after the final due date (P12-TSK-017)';
    END IF;
    -- Oldest first, each date once.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_interest_accrual a
                WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= NEW.accrual_date) THEN
        RAISE EXCEPTION 'an obligation''s dates accrue oldest first, each once (P12-TSK-015, INV-LND-02)';
    END IF;
    -- Never into a period already billed: a billing made its period's accrued interest due, so billed interest stays the
    -- accrued interest for ever (lending V012's rule).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                WHERE b.obligation_id = NEW.obligation_id AND b.value_date > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a period already billed never accrues afterwards (P12-TSK-015)';
    END IF;
    -- ACCRUAL_ENGINE_V1's chain: the period's exact cumulative figure never falls within it.
    SELECT a.cumulative_exact INTO previous_cumulative FROM bnpl.bnpl_interest_accrual a
     WHERE a.obligation_id = NEW.obligation_id AND a.period_start = NEW.period_start
     ORDER BY a.accrual_date DESC LIMIT 1;
    IF previous_cumulative IS NOT NULL AND NEW.cumulative_exact < previous_cumulative THEN
        RAISE EXCEPTION 'a period''s cumulative accrued interest never falls within it (P12-TSK-017, INV-LND-09)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

COMMENT ON TABLE bnpl.bnpl_interest_accrual IS
    'A pay-in-N obligation''s daily interest accrual (P12-TSK-015 created the record the billing reads; P12-TSK-017 its writer, T-acc, in its own transaction - no wallet, no payable): the date''s base (BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE valued on or before it, never interest, fees or the projection), the instalment period it is rounded within (the latest schedule version''s; nothing at or after the final due date), the period''s exact cumulative interest, what the date posts (ACCRUAL_ENGINE_V1), the agreement version in force and the engine it pins, and F2a''s entry by value (DR BNPL_INTEREST_ACCRUED / CR BNPL_INTEREST_INCOME, bnpl.accrual:<obligation>:<date>, value-dated the date) - null for a zero day. A billing''s interest is exactly its period''s posted_minor. One row per obligation and date, oldest first, never early; INSERT-only for every role (RESTRICTED-FINANCIAL).';

-- ---------------------------------------------------------------------------------------------
-- The grant: the writer may insert, never update or delete (the trigger refuses both for every role).
-- ---------------------------------------------------------------------------------------------
GRANT INSERT ON bnpl.bnpl_interest_accrual TO finapp_app;
