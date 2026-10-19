-- Delinquency, default and late fees (P12-TSK-020; PHASE_12_PLAN.md sections 7.1 T-svc, 7.2, 12.3, 12.6 point 4, 12.8 and
-- 12.11 F4/F5a; A13, A14, OQ-14; lending V020 and V021's shape - P11-TSK-024 and -025; INV-LND-12 and INV-LND-08 by
-- analogy, INV-IDEM-02, INV-HIST-01, INV-BNPL-01, INV-BNPL-06).
--
-- bnpl V013 is this task's - renumbered from the plan's V012 by the Phase 12 wave D board (P12-TSK-019 took V011,
-- P12-TSK-021 V012; Flyway runs with out-of-order off; P12-TSK-022 takes V014).
--
-- Delinquency and default are CONDITIONS, never lifecycle states: an ACTIVE obligation is ACTIVE whether current or 120
-- days past due. They are DERIVED - never a stored counter that can drift:
--
--     past due   - a billed instalment with something still unpaid at the end of its due date: value_date < the business
--                  date and billed - settled > 0 over its components (FEES, INTEREST, PRINCIPAL - every payer's rows and
--                  every refund's cancellations and releases, through bnpl.billing_component_billed/_paid);
--     DPD        - the business date less the oldest past-due instalment's due date; 0 when nothing is past due;
--     bucket     - CURRENT at DPD 0, else the pinned terms' bucket whose lower bound DPD has reached (delinquency_bounds,
--                  default 1/30/60/90 - B1_1_29, B2_30_59, B3_60_89, B4_90_PLUS);
--     default    - set when DPD reaches the pinned default_threshold_days (A14: 90), cleared at cure (DPD back to 0) with
--                  no probation: defaulted = DPD >= threshold OR (defaulted before AND DPD > 0).
--
-- The servicing step (T-svc), under the obligation row lock (B4), after the billing, the credit-balance applications, the
-- collection and the closure - and only on an obligation it did not close - derives the condition on its business date and
-- appends, then assesses the late fee:
--
--     bnpl_condition_event  - one row per CHANGE, born once per (obligation, business date, kind): kind DELINQUENCY when the
--                             bucket changed (previous_bucket -> bucket), kind DEFAULT when the flag changed; every row the
--                             condition after the change (DPD, bucket, flag) and the agreement version in force whose pinned
--                             terms judged it. Re-running a day writes nothing; a missed day is computed from the dates; a
--                             closed obligation is never evaluated. INSERT-only.
--     bnpl_fee_assessment   - the late fee (A13): once per billed instalment and kind (UNIQUE (instalment_id, kind)) on the
--                             business date its own age - the business date less its due date - reached the pinned
--                             late_fee_day (> grace: grace delays only the fee, never DPD), or later while it still owes
--                             interest or principal (a missed day is caught up); at most the pinned fee, the agreement's
--                             assessments within the pinned cap (a fee the cap only partly admits is assessed at what it
--                             admits); F4's entry (DR BNPL_FEES_DUE / CR BNPL_FEE_INCOME under bnpl.fee:<instalment>:<kind>)
--                             by value. INSERT-only.
--
-- A FEE IS PART OF WHAT ITS INSTALMENT OWES: bnpl.billing_component_billed(billing, 'FEES') - replaced here - is the
-- instalment's assessed fees, so every payer allocates FEES first by the pinned order, the closure's rows-side rule and the
-- never-over-paid rules read it, and bnpl.days_past_due keeps an instalment with an unpaid fee past due. (P12-TSK-024's
-- waiver subtracts its approved waivers from it.)
--
-- THE RELEASE ON A REFUND (OQ-14, carried here by P12-TSK-021, which landed first): a refund that cancels a billed
-- instalment's whole unpaid principal - or leaves no principal unpaid at all - releases the fees still due on it in its own
-- entry (F5a: DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE). bnpl_refund_adjustment.late_fee_released_minor (a whole billed-unpaid
-- row only) and bnpl_refund.late_fee_released_minor (their sum) record it; bnpl.billing_component_paid counts it as FEES
-- settled - so a fee is released at most once and never beside a waiver of the same money: every later release, payer and
-- waiver is bounded by billed - settled; and bnpl.bnpl_refund_conserves (replaced) holds, at commit for every writer, the
-- sum, no FEES settled beyond billed, and nothing left due of FEES on an instalment cancelled whole.
--
-- Held for EVERY writer, beyond the shape CHECKs: each trigger takes B4 itself - re-entrant for the servicing step, which
-- holds it already - so the change and the cap are judged under the lock that serialises every act on the obligation; only
-- an ACTIVE obligation; the agreement version in force; dated the business date the transaction's database clock showed in
-- the pinned zone; never dated before a row already recorded; DPD, bucket and flag the derivation above, from the rows
-- (INV-LND-12 by analogy - never a writer's figure); a change of its kind from the latest row of the kind (or from CURRENT,
-- not defaulted, when there is none); a fee on a billed instalment of its own obligation, its age, amount, cap and owing.
-- No bnpl trigger reads a ledger table (the P12-TSK-013 refinement): the entry's lines are the one transaction's and the
-- three-way proof's (P12-TSK-025). Every instant the database's, clock_timestamp() after the act's locks.

-- ---------------------------------------------------------------------------------------------
-- The derivations: days past due and the bucket - one rule, read by the triggers; BnplDelinquencyState derives the same.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.delinquency_bucket(dpd integer, bounds integer[])
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT CASE
               WHEN dpd <= 0 THEN 'CURRENT'
               WHEN dpd >= bounds[4] THEN 'B4_90_PLUS'
               WHEN dpd >= bounds[3] THEN 'B3_60_89'
               WHEN dpd >= bounds[2] THEN 'B2_30_59'
               ELSE 'B1_1_29'
           END
$$;

COMMENT ON FUNCTION bnpl.delinquency_bucket(integer, integer[]) IS
    'The days-past-due bucket of a DPD under a terms version''s four ascending lower bounds (P12-TSK-020): CURRENT at 0; the highest bucket whose lower bound DPD reached. BnplDelinquencyBucket.of is the same rule.';

-- ---------------------------------------------------------------------------------------------
-- The condition history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_condition_event (
    id                  uuid        NOT NULL,
    obligation_id       uuid        NOT NULL,
    business_date       date        NOT NULL,
    kind                text        NOT NULL,
    days_past_due       integer     NOT NULL,
    bucket              text        NOT NULL,
    previous_bucket     text,
    defaulted           boolean     NOT NULL,
    agreement_id        uuid        NOT NULL,
    agreement_version   integer     NOT NULL,
    created_at          timestamptz NOT NULL,
    correlation_id      text        NOT NULL,
    CONSTRAINT bnpl_condition_event_pk PRIMARY KEY (id),
    -- Born once per obligation, business date and kind: beneath B4, which serialises every servicing step.
    CONSTRAINT bnpl_condition_event_once_a_day UNIQUE (obligation_id, business_date, kind),
    CONSTRAINT bnpl_condition_event_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_condition_event_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- BnplConditionKind.
    CONSTRAINT bnpl_condition_event_kind_is_known CHECK (kind IN ('DELINQUENCY', 'DEFAULT')),
    -- BnplDelinquencyBucket, CURRENT beside it.
    CONSTRAINT bnpl_condition_event_bucket_is_known CHECK (
        bucket IN ('CURRENT', 'B1_1_29', 'B2_30_59', 'B3_60_89', 'B4_90_PLUS')
        AND (previous_bucket IS NULL
             OR previous_bucket IN ('CURRENT', 'B1_1_29', 'B2_30_59', 'B3_60_89', 'B4_90_PLUS'))),
    -- CURRENT exactly at DPD 0; a defaulted obligation is past due (cure clears the flag).
    CONSTRAINT bnpl_condition_event_shape CHECK (
        days_past_due >= 0
        AND (bucket = 'CURRENT') = (days_past_due = 0)
        AND NOT (defaulted AND bucket = 'CURRENT')),
    -- A DELINQUENCY row names the bucket it changed from, and it is another; a DEFAULT row names none.
    CONSTRAINT bnpl_condition_event_kind_shape CHECK (
        (kind = 'DELINQUENCY') = (previous_bucket IS NOT NULL)
        AND (previous_bucket IS NULL OR previous_bucket <> bucket)),
    CONSTRAINT bnpl_condition_event_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_condition_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- The latest row of a kind per obligation: the step's "only on a change", the trigger's, and the gauges' read.
CREATE INDEX bnpl_condition_event_latest ON bnpl.bnpl_condition_event (obligation_id, kind, business_date DESC);

COMMENT ON TABLE bnpl.bnpl_condition_event IS
    'One change of a BNPL obligation''s condition (P12-TSK-020; PHASE_12_PLAN.md section 12.8, A14): DELINQUENCY when its days-past-due bucket changed (previous_bucket -> bucket, CURRENT at DPD 0), DEFAULT when its default flag changed (set at the pinned threshold - 90 days - and cleared at cure); every row carries the condition after the change - DPD derived from the billed and settled rows on the business date of the database clock in the pinned zone, never a stored counter - and the agreement version whose terms judged it. Appended by the servicing step under the obligation row lock (B4), only on a change. UNIQUE (obligation_id, business_date, kind). Never on a closed obligation; never shown to a merchant. INSERT-only for every role. RESTRICTED-FINANCIAL (DPD, bucket and flag).';

-- ---------------------------------------------------------------------------------------------
-- The late fee.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_fee_assessment (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    instalment_id      uuid        NOT NULL,
    kind               text        NOT NULL,
    business_date      date        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    journal_entry_id   uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_fee_assessment_pk PRIMARY KEY (id),
    -- One fee per billed instalment per kind, for ever: ten sweepers, a duplicate tick, a re-run day - one fee (A13); the
    -- ledger key bnpl.fee:<instalment>:<kind> beneath it.
    CONSTRAINT bnpl_fee_assessment_once_per_instalment UNIQUE (instalment_id, kind),
    -- One assessment per journal entry.
    CONSTRAINT bnpl_fee_assessment_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_fee_assessment_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_fee_assessment_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    CONSTRAINT bnpl_fee_assessment_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- BnplFeeKind.
    CONSTRAINT bnpl_fee_assessment_kind_is_known CHECK (kind IN ('LATE')),
    CONSTRAINT bnpl_fee_assessment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A fee of nothing is no fee: a zero fee or a cap reached writes no row.
    CONSTRAINT bnpl_fee_assessment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT bnpl_fee_assessment_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_fee_assessment_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_fee_assessment_by_obligation ON bnpl.bnpl_fee_assessment (obligation_id, business_date);

COMMENT ON TABLE bnpl.bnpl_fee_assessment IS
    'A fee assessed on a billed BNPL instalment (P12-TSK-020; A13, PHASE_12_PLAN.md sections 12.3 and 12.11 F4): kind LATE - the pinned late fee, once per instalment on the business date its own age reached the pinned late_fee_day (> grace) while it still owed interest or principal, capped per agreement (the sum never above the pinned late_fee_cap; a fee the cap partly admits is assessed at what it admits). Names the agreement version whose terms decided it and F4''s entry (DR BNPL_FEES_DUE / CR BNPL_FEE_INCOME, key bnpl.fee:<instalment>:<kind>). Joins its instalment''s FEES (bnpl.billing_component_billed), so an unpaid fee keeps its instalment past due; a refund cancelling the instalment whole releases it (OQ-14). UNIQUE (instalment_id, kind). INSERT-only for every role. RESTRICTED-FINANCIAL.';

-- ---------------------------------------------------------------------------------------------
-- What a billing's component billed, replaced: V008's interest and principal, and its instalment's assessed fees (FEES).
-- One sum every reader shares - the due state, the closure's rows-side rule, the never-over-paid rules, the collection
-- attempt and days_past_due.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT (CASE billed_component
                WHEN 'INTEREST' THEN b.interest_minor
                WHEN 'PRINCIPAL' THEN b.principal_minor
                ELSE (SELECT coalesce(sum(f.amount_minor), 0) FROM bnpl.bnpl_fee_assessment f
                       WHERE f.instalment_id = b.instalment_id)
            END)::bigint
      FROM bnpl.bnpl_billing b WHERE b.id = billing
$$;

COMMENT ON FUNCTION bnpl.billing_component_billed(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) billed (P12-TSK-015): its interest and principal, and - FEES - its instalment''s assessed late fees (P12-TSK-020). The one definition later tasks replace (-024: less approved waivers); what is still due is this less bnpl.billing_component_paid.';

-- ---------------------------------------------------------------------------------------------
-- The release on a refund-cancelled instalment (OQ-14): the adjustment's and the refund's columns.
-- ---------------------------------------------------------------------------------------------
-- DEFAULT 0 - nothing released - for P12-TSK-021's writers, which name no release; a writer that omits a release a whole
-- cancellation owes is refused at commit by bnpl.bnpl_refund_conserves below.
ALTER TABLE bnpl.bnpl_refund_adjustment ADD COLUMN late_fee_released_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE bnpl.bnpl_refund_adjustment DROP CONSTRAINT bnpl_refund_adjustment_amounts;
-- Something cancelled or released; an unbilled instalment has no billed interest or fee to release; a late fee released
-- only on a billed instalment cancelled whole.
ALTER TABLE bnpl.bnpl_refund_adjustment ADD CONSTRAINT bnpl_refund_adjustment_amounts CHECK (
    sequence >= 1 AND principal_cancelled_minor >= 0 AND interest_released_minor >= 0 AND late_fee_released_minor >= 0
    AND principal_cancelled_minor + interest_released_minor + late_fee_released_minor > 0
    AND (kind = 'BILLED_UNPAID' OR interest_released_minor = 0)
    AND (late_fee_released_minor = 0 OR (kind = 'BILLED_UNPAID' AND whole)));

ALTER TABLE bnpl.bnpl_refund ADD COLUMN late_fee_released_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_late_fee_released_bounded CHECK (late_fee_released_minor >= 0);

COMMENT ON COLUMN bnpl.bnpl_refund_adjustment.late_fee_released_minor IS
    'The late fees still due on a billed instalment the refund cancelled whole, released in the refund''s entry (DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE, F5a; OQ-14, P12-TSK-020). Zero otherwise.';
COMMENT ON COLUMN bnpl.bnpl_refund.late_fee_released_minor IS
    'Sigma of the refund''s adjustments'' late fees released (OQ-14, P12-TSK-020) - held equal at commit by bnpl.bnpl_refund_conserves.';

-- ---------------------------------------------------------------------------------------------
-- What a billing's component has had settled, replaced: V012's, and every refund's release of its late fees (FEES).
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
          + (SELECT coalesce(sum(CASE paid_component WHEN 'PRINCIPAL' THEN x.principal_cancelled_minor
                                                     WHEN 'INTEREST' THEN x.interest_released_minor
                                                     WHEN 'FEES' THEN x.late_fee_released_minor
                                                     ELSE 0 END), 0)
               FROM bnpl.bnpl_refund_adjustment x
               JOIN bnpl.bnpl_billing b ON b.instalment_id = x.instalment_id
              WHERE b.id = billing AND x.kind = 'BILLED_UNPAID'))::bigint
$$;

COMMENT ON FUNCTION bnpl.billing_component_paid(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) has had settled - by every payer (credit-balance applications, P12-TSK-015; repayments, P12-TSK-018) and by every refund''s cancellation of its principal, release of its interest (P12-TSK-021) and release of its late fees (OQ-14, P12-TSK-020). The one definition later payers replace (-024); what is still due is billed less this.';

-- ---------------------------------------------------------------------------------------------
-- The days past due of an obligation on a business date, derived from its billed instalments: the date less the oldest
-- due date of an instalment with something unpaid at the end of it - billed less settled over the three components - or 0.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.days_past_due(obligation uuid, on_date date)
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(on_date - min(b.value_date), 0)
      FROM bnpl.bnpl_billing b
     WHERE b.obligation_id = obligation AND b.value_date < on_date
       AND (SELECT sum(bnpl.billing_component_billed(b.id, c.component) - bnpl.billing_component_paid(b.id, c.component))
              FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)) > 0
$$;

COMMENT ON FUNCTION bnpl.days_past_due(uuid, date) IS
    'An obligation''s days past due on a date (P12-TSK-020): the date less the oldest due date of a billed instalment still owing anything (fees, interest or principal) at the end of it; 0 when none. A difference of dates, never a counter. BnplDelinquencyState.derive is the same rule.';

-- ---------------------------------------------------------------------------------------------
-- The condition's trigger: born once, only on a change, derived from the rows - for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_condition_event_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    derived integer;
    was_bucket text;
    was_defaulted boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL condition change is immutable from insert, for every role (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- (B4) The obligation, for every writer (T-svc holds it already; re-taking it is free): an ACTIVE one - a closed
    -- obligation takes no condition change (INV-LND-08 by analogy).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL condition is recorded only for an ACTIVE obligation (P12-TSK-020, INV-LND-08)';
    END IF;
    -- The agreement version in force, whose pinned terms judge the condition.
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL condition names its obligation''s agreement version in force (P12-TSK-020)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    -- Dated the business date the transaction's database clock showed, in the pinned zone.
    IF NEW.business_date < LEAST(bnpl.business_date(transaction_timestamp(), obligation.servicing_zone),
                                 bnpl.business_date(statement_timestamp(), obligation.servicing_zone))
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL condition is dated the business date of the database clock in its pinned zone (P12-TSK-020)';
    END IF;
    -- Appended in date order: never dated before a change already recorded (the same date is the unique's, per kind).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_condition_event e
                WHERE e.obligation_id = NEW.obligation_id AND e.business_date > NEW.business_date) THEN
        RAISE EXCEPTION 'a BNPL condition is never dated before a change already recorded (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- DPD derived from the rows, never a writer's figure; the bucket that DPD's under the pinned bounds.
    derived := bnpl.days_past_due(NEW.obligation_id, NEW.business_date);
    IF NEW.days_past_due <> derived
            OR NEW.bucket <> bnpl.delinquency_bucket(derived, terms.delinquency_bounds) THEN
        RAISE EXCEPTION 'a BNPL condition''s days past due and bucket are derived from its billed and settled rows (P12-TSK-020, INV-LND-12)';
    END IF;
    -- The latest recorded condition of each aspect: CURRENT and not defaulted before any.
    SELECT coalesce((SELECT e.bucket FROM bnpl.bnpl_condition_event e
                      WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DELINQUENCY'
                      ORDER BY e.business_date DESC LIMIT 1), 'CURRENT'),
           coalesce((SELECT e.defaulted FROM bnpl.bnpl_condition_event e
                      WHERE e.obligation_id = NEW.obligation_id AND e.kind = 'DEFAULT'
                      ORDER BY e.business_date DESC LIMIT 1), false)
      INTO was_bucket, was_defaulted;
    -- The default flag: set at the pinned threshold (A14), held while past due, cleared at cure - no probation.
    IF NEW.defaulted <> (derived >= terms.default_threshold_days OR (was_defaulted AND derived > 0)) THEN
        RAISE EXCEPTION 'a BNPL condition''s default flag is set at the default threshold and cleared at cure (P12-TSK-020, A14)';
    END IF;
    -- Only on a change of its kind, from the latest recorded one.
    IF (NEW.kind = 'DELINQUENCY' AND (NEW.previous_bucket <> was_bucket OR NEW.bucket = was_bucket))
            OR (NEW.kind = 'DEFAULT' AND NEW.defaulted = was_defaulted) THEN
        RAISE EXCEPTION 'a BNPL condition is recorded only on a change, from the latest recorded one (P12-TSK-020, INV-LND-12)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_condition_event_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_condition_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_condition_event_is_born_once();

CREATE TRIGGER bnpl_condition_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_condition_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The fee's trigger: once, on its day, at most the fee, within the cap, on an instalment still owing - for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_fee_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    billing bnpl.bnpl_billing%ROWTYPE;
    assessed bigint;
    owing bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL fee assessment is immutable from insert, for every role (P12-TSK-020, INV-HIST-01)';
    END IF;
    -- (B4) The obligation, for every writer: the cap is judged under the lock every act on it holds.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL fee is assessed only on an ACTIVE obligation (P12-TSK-020, INV-LIFE-04)';
    END IF;
    -- A billed instalment of its own obligation, in its currency and scale.
    SELECT b.* INTO billing FROM bnpl.bnpl_billing b
     WHERE b.instalment_id = NEW.instalment_id AND b.obligation_id = NEW.obligation_id;
    IF billing.id IS NULL OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'a BNPL fee is assessed on a billed instalment of its own obligation, in its currency (P12-TSK-020)';
    END IF;
    -- Under the agreement version in force; its pinned terms decide.
    IF NEW.agreement_id <> obligation.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL fee names its obligation''s agreement version in force (P12-TSK-020)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    -- Dated the business date the transaction's database clock showed, in the pinned zone - never early, never back-dated.
    IF NEW.business_date < LEAST(bnpl.business_date(transaction_timestamp(), obligation.servicing_zone),
                                 bnpl.business_date(statement_timestamp(), obligation.servicing_zone))
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL fee is dated the business date of the database clock in its pinned zone (P12-TSK-020)';
    END IF;
    -- On the day the instalment's own age reaches the pinned late-fee day, or later (a missed day is caught up) - never in
    -- grace (late_fee_day > grace_days, bnpl V002).
    IF NEW.business_date - billing.value_date < terms.late_fee_day THEN
        RAISE EXCEPTION 'a BNPL late fee is assessed only once its instalment''s age has reached the pinned late-fee day (P12-TSK-020, A13)';
    END IF;
    -- At most the pinned fee; the agreement's fees within the pinned cap.
    SELECT coalesce(sum(f.amount_minor), 0) INTO assessed FROM bnpl.bnpl_fee_assessment f
     WHERE f.obligation_id = NEW.obligation_id;
    IF NEW.amount_minor > terms.late_fee_minor OR assessed + NEW.amount_minor > terms.late_fee_cap_minor THEN
        RAISE EXCEPTION 'a BNPL late fee is at most the pinned fee, and an agreement''s fees at most the pinned cap (P12-TSK-020, A13)';
    END IF;
    -- Only on an instalment still owing interest or principal: a settled instalment is never charged late.
    SELECT sum(bnpl.billing_component_billed(billing.id, c.component) - bnpl.billing_component_paid(billing.id, c.component))
      INTO owing
      FROM (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF coalesce(owing, 0) <= 0 THEN
        RAISE EXCEPTION 'a BNPL late fee is assessed only on an instalment still owing interest or principal (P12-TSK-020, A13)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_fee_assessment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_fee_assessment
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_fee_assessment_is_born_once();

CREATE TRIGGER bnpl_fee_assessment_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_fee_assessment
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The refund's conservation, replaced: V012's body, and the late fees released - summed, never over-settled, and
-- nothing left due of FEES on an instalment cancelled whole.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    refund bnpl.bnpl_refund%ROWTYPE;
    unbilled bigint;
    billed bigint;
    released bigint;
    fees_released bigint;
BEGIN
    IF TG_TABLE_NAME = 'bnpl_refund' THEN
        SELECT * INTO refund FROM bnpl.bnpl_refund r WHERE r.id = NEW.id;
    ELSE
        SELECT * INTO refund FROM bnpl.bnpl_refund r WHERE r.id = NEW.refund_id;
    END IF;
    SELECT coalesce(sum(a.principal_cancelled_minor) FILTER (WHERE a.kind = 'UNBILLED'), 0),
           coalesce(sum(a.principal_cancelled_minor) FILTER (WHERE a.kind = 'BILLED_UNPAID'), 0),
           coalesce(sum(a.interest_released_minor), 0),
           coalesce(sum(a.late_fee_released_minor), 0)
      INTO unbilled, billed, released, fees_released
      FROM bnpl.bnpl_refund_adjustment a WHERE a.refund_id = refund.id;
    IF unbilled <> refund.cancelled_unbilled_minor OR billed <> refund.cancelled_billed_minor
            OR released <> refund.interest_due_released_minor OR fees_released <> refund.late_fee_released_minor THEN
        RAISE EXCEPTION 'a BNPL refund''s adjustments sum to its cancellations and releases: Sigma adjustments + excess = R (P12-TSK-021, P12-TSK-020, INV-BNPL-06)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_refund_adjustment a
                 JOIN bnpl.bnpl_billing b ON b.instalment_id = a.instalment_id
                 CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE a.refund_id = refund.id AND a.kind = 'BILLED_UNPAID'
                  AND bnpl.billing_component_paid(b.id, c.component)
                      > bnpl.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'no billed component is ever settled beyond what it billed (P12-TSK-021, INV-LND-04)';
    END IF;
    -- OQ-14: an instalment the refund cancelled whole owes no late fee after it - released exactly, never in part.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_refund_adjustment a
                 JOIN bnpl.bnpl_billing b ON b.instalment_id = a.instalment_id
                WHERE a.refund_id = refund.id AND a.kind = 'BILLED_UNPAID' AND a.whole
                  AND bnpl.billing_component_paid(b.id, 'FEES') <> bnpl.billing_component_billed(b.id, 'FEES')) THEN
        RAISE EXCEPTION 'a BNPL refund that cancels a billed instalment whole releases every late fee still due on it (P12-TSK-020, OQ-14)';
    END IF;
    IF refund.cancelled_unbilled_minor > 0 AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_schedule s WHERE s.refund_id = refund.id
                                                 AND s.schedule_version = refund.schedule_version) THEN
        RAISE EXCEPTION 'a BNPL refund that cancels unbilled principal commits beside the schedule version it made (P12-TSK-021, INV-HIST-04)';
    END IF;
    IF (SELECT p.status = 'FULLY_REFUNDED' FROM bnpl.purchase p WHERE p.id = refund.purchase_id)
            IS DISTINCT FROM ((SELECT sum(r.amount_minor) FROM bnpl.bnpl_refund r WHERE r.purchase_id = refund.purchase_id)
                              = (SELECT f.gross_minor FROM bnpl.merchant_financing f
                                  WHERE f.purchase_id = refund.purchase_id)) THEN
        RAISE EXCEPTION 'a BNPL purchase is FULLY_REFUNDED exactly when its refunds total its gross (P12-TSK-021, INV-BNPL-07)';
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_condition_event TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_fee_assessment TO finapp_app;
