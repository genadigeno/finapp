-- Delinquency and default conditions, both products (P11-TSK-024; PHASE_11_PLAN.md sections 5.5, 7.1 T-svc and 12.7,
-- LENDING_LIFECYCLES.md section 5; ADR-0098; L7, A8, A25; INV-LND-12, INV-LND-10, INV-LND-08, INV-HIST-01). The number is
-- V020 - the next free lending version (V018 is P11-TSK-023's, V019 P11-TSK-033's); the backlog's "V017" is stale.
--
-- Delinquency and default are CONDITIONS, never lifecycle states: an ACTIVE loan is ACTIVE whether current or 120 days past
-- due. They are DERIVED - never a stored counter that can drift:
--
--     past due   - a billed item (an instalment's billing, a line's statement - its minimum payment) with something still
--                  unpaid at the end of its due date: due_date < the business date and billed - paid > 0 over its
--                  components (every payer's rows, through lending.billing_component_paid);
--     DPD        - the business date less the oldest past-due item's due date; 0 when nothing is past due;
--     bucket     - CURRENT at DPD 0, else the pinned terms' bucket whose lower bound DPD has reached
--                  (delinquency_bounds, default 1/30/60/90 - B1_1_29, B2_30_59, B3_60_89, B4_90_PLUS);
--     default    - set when DPD reaches the pinned default_threshold_days (L7: 90), cleared at cure - DPD back to 0 - with
--                  no probation (A8): defaulted = DPD >= threshold OR (defaulted before AND DPD > 0).
--
-- The servicing step (T-svc), under the loan row lock (L3), after the accrual, the billing (or a line's statement) and
-- auto-collection and only on an account it did not close, derives the condition on its business date and appends:
--
--     loan_condition_event - one row per CHANGE, born once per (loan, business date, kind): kind DELINQUENCY when the bucket
--                            changed (previous_bucket -> bucket), kind DEFAULT when the flag changed (defaulted the new
--                            flag); every row carries the condition after the change (DPD, bucket, the default flag) and
--                            the governing agreement version whose pinned terms judged it. Re-running a day writes
--                            nothing; a missed day is computed from the dates; a closed account is never evaluated.
--
-- Held for EVERY writer, beyond the shape CHECKs: UNIQUE (loan_id, business_date, kind) beneath L3; a row only for an
-- account in servicing (ACTIVE or CLOSING - a CLOSED or CANCELLED account takes no condition change, INV-LND-08); the
-- governing agreement version; dated the business date of its transaction in the pinned zone (INV-LND-10); its DPD the
-- derivation above, from the billed and allocated rows (INV-LND-12 - never a writer's figure); its bucket that DPD's under
-- the pinned bounds; its default flag the rule above; a change of its kind from the latest row of the kind (or from CURRENT
-- and not defaulted when there is none). INSERT-only, never truncated: the history is its own trail (INV-HIST-01).

-- ---------------------------------------------------------------------------------------------
-- The condition history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_condition_event (
    id                  uuid        NOT NULL,
    loan_id             uuid        NOT NULL,
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
    CONSTRAINT loan_condition_event_pk PRIMARY KEY (id),
    -- Born once per account, business date and kind (INV-LND-12): beneath L3, which serialises every servicing step.
    CONSTRAINT loan_condition_event_once_a_day UNIQUE (loan_id, business_date, kind),
    CONSTRAINT loan_condition_event_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_condition_event_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT loan_condition_event_kind_is_known CHECK (kind IN ('DELINQUENCY', 'DEFAULT')),
    CONSTRAINT loan_condition_event_bucket_is_known CHECK (
        bucket IN ('CURRENT', 'B1_1_29', 'B2_30_59', 'B3_60_89', 'B4_90_PLUS')
        AND (previous_bucket IS NULL OR previous_bucket IN ('CURRENT', 'B1_1_29', 'B2_30_59', 'B3_60_89', 'B4_90_PLUS'))),
    -- CURRENT exactly at DPD 0; a defaulted account is past due (A8: cure clears the flag).
    CONSTRAINT loan_condition_event_shape CHECK (
        days_past_due >= 0
        AND (bucket = 'CURRENT') = (days_past_due = 0)
        AND NOT (defaulted AND bucket = 'CURRENT')),
    -- A DELINQUENCY row names the bucket it changed from, and it is another; a DEFAULT row names none.
    CONSTRAINT loan_condition_event_kind_shape CHECK (
        (kind = 'DELINQUENCY') = (previous_bucket IS NOT NULL)
        AND (previous_bucket IS NULL OR previous_bucket <> bucket)),
    CONSTRAINT loan_condition_event_version_numbered CHECK (agreement_version >= 1),
    CONSTRAINT loan_condition_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- The latest row of a kind per account: the step's "only on a change", the trigger's, and the gauges' read.
CREATE INDEX loan_condition_event_latest ON lending.loan_condition_event (loan_id, kind, business_date DESC);

COMMENT ON TABLE lending.loan_condition_event IS
    'One change of a loan''s or line''s condition (P11-TSK-024; ADR-0098, PHASE_11_PLAN.md sections 5.5 and 12.7): DELINQUENCY when its days-past-due bucket changed (previous_bucket -> bucket, CURRENT at DPD 0), DEFAULT when its default flag changed (set at the pinned threshold - L7, 90 days - and cleared at cure, A8); every row carries the condition after the change - DPD derived from the billed and allocated rows on the business date of the database clock in the pinned zone, never a stored counter - and the agreement version whose terms judged it. Appended by the servicing step under the loan row lock, only on a change. UNIQUE (loan_id, business_date, kind). Never on a closed or cancelled account. INSERT-only for every role. RESTRICTED-FINANCIAL (DPD, bucket and flag).';

-- The days past due of a loan on a business date, derived from its billed items: the date less the oldest due date of an
-- item with something unpaid at the end of it - billed less every payer's allocations, over the three components - or 0.
-- One rule, read by the trigger below; the domain's DelinquencyState derives the same figure from the same rows.
CREATE OR REPLACE FUNCTION lending.days_past_due(account uuid, on_date date)
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(on_date - min(i.due_date), 0)
      FROM (SELECT b.id, b.due_date FROM lending.instalment_billing b WHERE b.loan_id = account AND b.due_date < on_date
            UNION ALL
            SELECT s.id, s.due_date FROM lending.credit_line_statement s
             WHERE s.loan_id = account AND s.due_date < on_date) i
     WHERE (SELECT sum(lending.billing_component_billed(i.id, c.component)
                       - lending.billing_component_paid(i.id, c.component))
              FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)) > 0
$$;

-- The bucket of a DPD under a terms version's ascending lower bounds (the first 1, the last the default threshold).
CREATE OR REPLACE FUNCTION lending.delinquency_bucket(dpd integer, bounds integer[])
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

CREATE OR REPLACE FUNCTION lending.loan_condition_event_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    derived integer;
    was_bucket text;
    was_defaulted boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a condition change is immutable from insert, for every role (P11-TSK-024, INV-LND-12, INV-HIST-01)';
    END IF;
    -- An account in servicing: a closed or cancelled one takes no condition change (INV-LND-08, scenario 10).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a condition is recorded only for an ACTIVE or CLOSING account (P11-TSK-024, INV-LND-08)';
    END IF;
    -- The governing agreement version, whose pinned terms judge the condition.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF agreement.id IS NULL OR NEW.agreement_id <> agreement.id OR NEW.agreement_version <> agreement.version THEN
        RAISE EXCEPTION 'a condition names its account''s governing agreement version (P11-TSK-024)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    -- Dated this transaction's business date in the pinned zone (INV-LND-10).
    IF NEW.business_date < lending.business_date(transaction_timestamp(), terms.servicing_zone)
            OR NEW.business_date > lending.business_date(statement_timestamp(), terms.servicing_zone) THEN
        RAISE EXCEPTION 'a condition is dated the business date of the database clock in its pinned zone (P11-TSK-024, INV-LND-10)';
    END IF;
    -- DPD derived from the rows, never a writer's figure (INV-LND-12); the bucket that DPD's under the pinned bounds.
    derived := lending.days_past_due(NEW.loan_id, NEW.business_date);
    IF NEW.days_past_due <> derived
            OR NEW.bucket <> lending.delinquency_bucket(derived, terms.delinquency_bounds) THEN
        RAISE EXCEPTION 'a condition''s days past due and bucket are derived from its billed and allocated rows (P11-TSK-024, INV-LND-12)';
    END IF;
    -- The latest recorded condition of each aspect: the bucket of the latest DELINQUENCY row, the flag of the latest
    -- DEFAULT row - CURRENT and not defaulted before any.
    SELECT coalesce((SELECT e.bucket FROM lending.loan_condition_event e
                      WHERE e.loan_id = NEW.loan_id AND e.kind = 'DELINQUENCY'
                      ORDER BY e.business_date DESC LIMIT 1), 'CURRENT'),
           coalesce((SELECT e.defaulted FROM lending.loan_condition_event e
                      WHERE e.loan_id = NEW.loan_id AND e.kind = 'DEFAULT'
                      ORDER BY e.business_date DESC LIMIT 1), false)
      INTO was_bucket, was_defaulted;
    -- The default flag: set at the pinned threshold (L7), held while past due, cleared at cure - no probation (A8).
    IF NEW.defaulted <> (derived >= terms.default_threshold_days OR (was_defaulted AND derived > 0)) THEN
        RAISE EXCEPTION 'a condition''s default flag is set at the default threshold and cleared at cure (P11-TSK-024, L7, A8)';
    END IF;
    -- Only on a change of its kind (ADR-0098 point 3).
    IF (NEW.kind = 'DELINQUENCY' AND NEW.previous_bucket <> was_bucket)
            OR (NEW.kind = 'DEFAULT' AND NEW.defaulted = was_defaulted) THEN
        RAISE EXCEPTION 'a condition is recorded only on a change, from the latest recorded one (P11-TSK-024, INV-LND-12)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_condition_event_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_condition_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_condition_event_is_born_once();

CREATE TRIGGER loan_condition_event_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_condition_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_condition_event TO finapp_app;
