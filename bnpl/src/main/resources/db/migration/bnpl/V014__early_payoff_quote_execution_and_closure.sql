-- Early payoff, both products (P12-TSK-022; PHASE_12_PLAN.md sections 5.4, 5.5, 7.1 T-pay, 7.2, 7.4, 12.11 F6 and
-- scenario 11; A21, L8; lending V022's shape (P11-TSK-026); INV-LND-08 by analogy, INV-LND-02, INV-IDEM-01,
-- INV-LIFE-04, INV-BNPL-01, INV-BNPL-10). The number is V014 - the wave's board reservation (V013 is P12-TSK-020's,
-- merged first; Flyway runs with out-of-order off).
--
-- The customer learns, and pays, exactly what ends the obligation on a given day - no fee (L8), no rebate arithmetic
-- (daily accrual makes it zero by construction):
--
--     bnpl_payoff_quote      - the quote (C, keyed): immutable, born under the obligation's row lock (B4) with its
--                              inputs - the business date it was made on, the good-through date G (that date up to the
--                              pinned payoff horizon, on the database clock), the five receivables and the credit
--                              balance read from the ledger, the projection's base, the dates ACCRUAL_ENGINE_V1
--                              projected ([business date, G - 1], never at or after the final due date; pay-in-N only)
--                              and the interest they post, the agreement and engine versions - and the amount it names:
--                              P + PD + IA + ID + FD - CB + projected (negative when the credit balance exceeds
--                              everything owed - the payoff then refunds the difference).
--     bnpl_payoff_execution  - the execution (C, keyed, MULTI_FACTOR): born once per quote and once per obligation, on G
--                              only, first in T-pay, with the value date every fact of the payoff takes - max(G, floor)
--                              read under B4 before anything is written (the Phase 11 gate's RG-M1 not repeated). It
--                              admits, in its own transaction and only there, the early billing of every unbilled
--                              instalment, the payoff's one repayment (kind PAYOFF, keyed by the quote, dated that value
--                              date) and the closure PAID_OFF_EARLY; a deferred check holds it to its obligation CLOSED
--                              (PAID_OFF_EARLY) at commit, so nothing it admits can commit without the closure.
--
-- Posting rule F6 is F2 (and F3a), F3, then the closure's refund - no new posting shape: every unbilled instalment
-- billed (each followed by the credit-balance application at its birth), the held credit applied over what was already
-- due (the Phase 11 gate's A4-M1 - the wallet is debited only the net), the one repayment allocated to every component,
-- the credit balance refunded under bnpl.closure-refund:<obligation>, CLOSED (PAID_OFF_EARLY). Pay-in-N's accrual (F2a,
-- T-acc) commits before the payoff, in its own transaction - the payoff never holds the currency's income row.
--
-- Replaced, each its predecessor's body plus exactly the payoff's change:
--     bnpl_billing_is_born_once (V012)            - never early, except beside the obligation's payoff execution; an
--                                                   early billing waits only for the accruals of its period's elapsed
--                                                   dates (through G - 1) - the record caught up to the payoff;
--     principal_value_floor (V008)                - a billing beside a payoff execution counts for its value date
--                                                   floored at the execution's (an early billing never raises the floor
--                                                   past the payoff's own value date);
--     bnpl_repayment_is_born_once (V011)          - PAYOFF admitted, only beside its execution, keyed by its quote and
--                                                   dated the execution's value date;
--     bnpl_obligation_closure_is_born_once (V012) - PAID_OFF_EARLY beside the execution, and no other reason beside one.
-- The obligation's edge function (V010) already admits ACTIVE -> CLOSED beside a closure row for the same reason.
--
-- The ledger side of "the amount recomputed", "every receivable zero" and "every elapsed date accrued" is the
-- transaction's own, under B4 (no bnpl trigger reads a ledger table, the P12-TSK-013 refinement).

-- ---------------------------------------------------------------------------------------------
-- The quote.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_payoff_quote (
    id                         uuid        NOT NULL,
    obligation_id              uuid        NOT NULL,
    agreement_id               uuid        NOT NULL,
    agreement_version          integer     NOT NULL,
    request_key                text        NOT NULL,
    business_date              date        NOT NULL,
    good_through               date        NOT NULL,
    currency                   char(3)     NOT NULL,
    scale                      smallint    NOT NULL,
    principal_minor            bigint      NOT NULL,
    principal_due_minor        bigint      NOT NULL,
    interest_accrued_minor     bigint      NOT NULL,
    interest_due_minor         bigint      NOT NULL,
    fees_due_minor             bigint      NOT NULL,
    credit_balance_minor       bigint      NOT NULL,
    projection_base_minor      bigint      NOT NULL,
    projected_days             integer     NOT NULL,
    projected_interest_minor   bigint      NOT NULL,
    amount_minor               bigint      NOT NULL,
    schedule_engine            text        NOT NULL,
    accrual_engine             text,
    allocation_engine          text        NOT NULL,
    created_at                 timestamptz NOT NULL,
    correlation_id             text        NOT NULL,
    CONSTRAINT bnpl_payoff_quote_pk PRIMARY KEY (id),
    -- One quote per obligation and requester's key (INV-IDEM-01): the arbiter behind the door's claim.
    CONSTRAINT bnpl_payoff_quote_key_unique UNIQUE (obligation_id, request_key),
    -- The execution's composite reference: a quote is executed only on its own obligation.
    CONSTRAINT bnpl_payoff_quote_of_its_obligation UNIQUE (id, obligation_id),
    CONSTRAINT bnpl_payoff_quote_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_payoff_quote_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    CONSTRAINT bnpl_payoff_quote_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_payoff_quote_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- G is the quote's date or later; the projection covers at most [business date, G - 1] - nothing when G is today,
    -- fewer dates when the accrual span ends first (nothing accrues at or after the final due date).
    CONSTRAINT bnpl_payoff_quote_horizon_shape CHECK (
        good_through >= business_date AND projected_days BETWEEN 0 AND good_through - business_date),
    CONSTRAINT bnpl_payoff_quote_amounts_shape CHECK (
        principal_minor >= 0 AND principal_due_minor >= 0 AND interest_accrued_minor >= 0 AND interest_due_minor >= 0
        AND fees_due_minor >= 0 AND credit_balance_minor >= 0 AND projection_base_minor >= 0
        AND projected_interest_minor >= 0),
    CONSTRAINT bnpl_payoff_quote_projection_when_projected CHECK (projected_days > 0 OR projected_interest_minor = 0),
    -- Pay-in-4 is interest-free (A4): no accrual engine, no interest of any kind, nothing projected.
    CONSTRAINT bnpl_payoff_quote_interest_free_without_an_engine CHECK (
        accrual_engine IS NOT NULL
        OR (interest_accrued_minor = 0 AND interest_due_minor = 0 AND projected_days = 0
            AND projected_interest_minor = 0)),
    -- The amount it names is exactly its inputs: the receivables less the credit balance, plus the projection.
    CONSTRAINT bnpl_payoff_quote_amount_is_its_parts CHECK (
        amount_minor = principal_minor + principal_due_minor + interest_accrued_minor + interest_due_minor
                       + fees_due_minor - credit_balance_minor + projected_interest_minor),
    CONSTRAINT bnpl_payoff_quote_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_payoff_quote IS
    'A BNPL payoff quote (P12-TSK-022; PHASE_12_PLAN.md section 7.1 T-pay, A21; lending V022''s shape): what ends the obligation on its good-through date G - born under the obligation''s row lock (B4), immutable, with its inputs: the business date it was made on, G (that date up to the pinned payoff horizon, on the database clock), the five receivables and the credit balance read from the ledger, the projection''s base and the dates [business date, G - 1] ACCRUAL_ENGINE_V1 projected on it (pay-in-N; never at or after the final due date) and the interest they post, the agreement and engine versions, and the amount (receivables - credit balance + projected interest; negative when the credit balance exceeds everything owed). Executed only on G, only while the recomputed amount equals it (bnpl_payoff_execution). No fee (L8). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_payoff_quote_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    horizon integer;
    schedule_scale smallint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL payoff quote is immutable from insert, for every role (P12-TSK-022, INV-LND-08)';
    END IF;
    -- (B4) The obligation, for every writer (the quote holds it already; re-taking it is free): an ACTIVE obligation.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a BNPL payoff is quoted only for an ACTIVE obligation, in its currency (P12-TSK-022, INV-LND-08)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - by its pinned engines (INV-LND-09), in the latest
    -- schedule version's scale.
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version
       AND a.obligation_id = NEW.obligation_id;
    SELECT s.scale INTO schedule_scale FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
     ORDER BY s.schedule_version DESC LIMIT 1;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.schedule_engine IS DISTINCT FROM agreement.schedule_engine
            OR NEW.accrual_engine IS DISTINCT FROM agreement.accrual_engine
            OR NEW.allocation_engine IS DISTINCT FROM agreement.allocation_engine
            OR schedule_scale IS NULL OR NEW.scale <> schedule_scale THEN
        RAISE EXCEPTION 'a BNPL payoff quote names its obligation''s agreement version in force, its pinned engines and its scale (P12-TSK-022, INV-LND-09)';
    END IF;
    SELECT t.payoff_horizon_days INTO horizon FROM bnpl.bnpl_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF horizon IS NULL THEN
        RAISE EXCEPTION 'a BNPL payoff is quoted under the terms its agreement pins (P12-TSK-022)';
    END IF;
    -- Made on the business date of the database clock in the pinned zone (read by the quote after its lock), good through
    -- that date up to the pinned horizon.
    IF NEW.business_date < bnpl.business_date(LEAST(statement_timestamp(), transaction_timestamp(), clock_timestamp()),
                                              obligation.servicing_zone)
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone)
            OR NEW.business_date < obligation.purchase_date
            OR NEW.good_through > NEW.business_date + horizon THEN
        RAISE EXCEPTION 'a BNPL payoff quote is made on the business date of the database clock in its pinned zone, good through at most the pinned horizon after it (P12-TSK-022)';
    END IF;
    -- The instant the database's, after the lock.
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_payoff_quote_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_payoff_quote
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_payoff_quote_is_born_once();

CREATE TRIGGER bnpl_payoff_quote_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_payoff_quote
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The execution.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_payoff_execution (
    id              uuid        NOT NULL,
    quote_id        uuid        NOT NULL,
    obligation_id   uuid        NOT NULL,
    request_key     text        NOT NULL,
    business_date   date        NOT NULL,
    value_date      date        NOT NULL,
    executed_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT bnpl_payoff_execution_pk PRIMARY KEY (id),
    -- A quote is executed once (the arbiter beneath B4 and the claim) ...
    CONSTRAINT bnpl_payoff_execution_quote_unique UNIQUE (quote_id),
    -- ... and an obligation paid off once (INV-LIFE-04): its execution closes it.
    CONSTRAINT bnpl_payoff_execution_obligation_unique UNIQUE (obligation_id),
    CONSTRAINT bnpl_payoff_execution_quote_fk FOREIGN KEY (quote_id, obligation_id)
        REFERENCES bnpl.bnpl_payoff_quote (id, obligation_id),
    CONSTRAINT bnpl_payoff_execution_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_payoff_execution_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    -- Every fact of the payoff is dated never before its business date.
    CONSTRAINT bnpl_payoff_execution_value_date_shape CHECK (value_date >= business_date),
    CONSTRAINT bnpl_payoff_execution_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_payoff_execution IS
    'A BNPL payoff quote executed (P12-TSK-022; PHASE_12_PLAN.md sections 5.4, 7.1 T-pay and 12.11 F6; lending V022''s shape): born once per quote and once per obligation, on the quote''s good-through date only, first in T-pay under the obligation''s row lock (B4), with the value date every fact of the payoff takes - max(business date, the principal value floor read before anything is written; the Phase 11 gate''s RG-M1 not repeated). Beside it, and only beside it, every unbilled instalment is billed early, the payoff''s one repayment (kind PAYOFF, keyed by the quote, dated the value date) is received and the obligation closes PAID_OFF_EARLY; a deferred check holds it to its obligation CLOSED (PAID_OFF_EARLY) at commit. The requester''s key, the dates and the instant. INSERT-only for every role. CONFIDENTIAL (the obligation and quote references).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_payoff_execution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    quote bnpl.bnpl_payoff_quote%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL payoff execution is immutable from insert, for every role (P12-TSK-022, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer: its own quote, on an ACTIVE obligation, under the agreement version the
    -- quote names - still the one in force.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    SELECT * INTO quote FROM bnpl.bnpl_payoff_quote q WHERE q.id = NEW.quote_id AND q.obligation_id = NEW.obligation_id;
    IF obligation.id IS NULL OR obligation.status <> 'ACTIVE' OR quote.id IS NULL
            OR quote.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                            WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL payoff executes its own obligation''s quote, on an ACTIVE obligation, under the agreement version it names (P12-TSK-022, INV-LND-08)';
    END IF;
    -- On the quote's good-through date, which is this transaction's business date in the pinned zone.
    IF NEW.business_date <> quote.good_through
            OR NEW.business_date < bnpl.business_date(LEAST(statement_timestamp(), transaction_timestamp(),
                                                            clock_timestamp()), obligation.servicing_zone)
            OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL payoff quote is executed only on its good-through date, on the database clock in its pinned zone (P12-TSK-022)';
    END IF;
    -- The value date every fact of the payoff takes: max(today, floor), the floor read now - before the payoff writes
    -- anything - so no billing, application or repayment of the payoff is dated before it (RG-M1).
    IF NEW.value_date <> GREATEST(NEW.business_date, bnpl.principal_value_floor(NEW.obligation_id)) THEN
        RAISE EXCEPTION 'a BNPL payoff is value-dated max(its date, the obligation''s floor), the floor read before the payoff writes (P12-TSK-022, the Phase 11 gate''s RG-M1)';
    END IF;
    NEW.executed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_payoff_execution_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_payoff_execution
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_payoff_execution_is_born_once();

CREATE TRIGGER bnpl_payoff_execution_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_payoff_execution
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- At commit, for every writer: an execution's obligation is CLOSED PAID_OFF_EARLY beside its closure - so the early
-- billings, the PAYOFF repayment and the PAID_OFF_EARLY closure the execution admits commit only together with it.
CREATE OR REPLACE FUNCTION bnpl.bnpl_payoff_execution_closes_its_obligation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation o
                     JOIN bnpl.bnpl_obligation_closure c ON c.obligation_id = o.id AND c.reason = o.closure_reason
                    WHERE o.id = NEW.obligation_id AND o.status = 'CLOSED' AND o.closure_reason = 'PAID_OFF_EARLY') THEN
        RAISE EXCEPTION 'a BNPL payoff execution committed without its obligation CLOSED PAID_OFF_EARLY (P12-TSK-022, INV-LND-08)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_payoff_execution_closes_its_obligation
    AFTER INSERT ON bnpl.bnpl_payoff_execution
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_payoff_execution_closes_its_obligation();

-- ---------------------------------------------------------------------------------------------
-- The principal value floor, replaced: V008's, a billing beside a payoff counted at most at the payoff's value date.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.principal_value_floor(obligation uuid)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(
        (SELECT o.purchase_date FROM bnpl.bnpl_obligation o WHERE o.id = obligation),
        (SELECT max(CASE WHEN e.id IS NULL THEN b.value_date ELSE LEAST(b.value_date, e.value_date) END)
           FROM bnpl.bnpl_billing b
           LEFT JOIN bnpl.bnpl_payoff_execution e ON e.obligation_id = b.obligation_id
          WHERE b.obligation_id = obligation),
        (SELECT max(a.accrual_date) + 1 FROM bnpl.bnpl_interest_accrual a WHERE a.obligation_id = obligation))
$$;

COMMENT ON FUNCTION bnpl.principal_value_floor(uuid) IS
    'The first business date an obligation''s record leaves open to a principal-moving fact (P12-TSK-013, extended by P12-TSK-015 and P12-TSK-022; the Phase 11 gate''s R1, lending V030''s shape): the later of the day after its latest accrual and its latest billing''s value date itself - a billing beside a payoff execution counted at most at the execution''s value date, so a payoff''s early billings never raise the floor past the payoff''s own date - never before its purchase''s business date. Read under B4 by every writer of a principal fact, which values it max(today, floor); its trigger refuses a value date before it and after the later of the latest date the clock showed and it.';

-- ---------------------------------------------------------------------------------------------
-- The billing, replaced: V012's body verbatim but for the payoff's arm - early only beside the obligation's execution.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    payoff bnpl.bnpl_payoff_execution%ROWTYPE;
    period_from date;
    accrued bigint;
    accrued_through date;
    reach date;
    remaining bigint;
    expected bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL billing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the order of billings and the principal billed are judged under the lock
    -- every servicing act on it holds (the step, the acceptance and the payoff hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL billing bills an ACTIVE obligation (P12-TSK-015)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = instalment.schedule_id;
    -- The obligation's payoff execution, if this is its payoff's transaction (an ACTIVE obligation has one only there:
    -- the execution's deferred check closes it in the same commit).
    SELECT * INTO payoff FROM bnpl.bnpl_payoff_execution e WHERE e.obligation_id = NEW.obligation_id;
    -- Of the obligation's LATEST schedule version, on that version's engine, in its currency and scale.
    IF instalment.id IS NULL OR schedule.id IS NULL OR NEW.schedule_id IS DISTINCT FROM schedule.id
            OR NEW.schedule_version IS DISTINCT FROM schedule.schedule_version
            OR schedule.schedule_version <> (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                              WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.schedule_engine IS DISTINCT FROM schedule.engine
            OR NEW.currency <> instalment.currency OR NEW.scale <> instalment.scale THEN
        RAISE EXCEPTION 'a BNPL billing bills an instalment of its obligation''s latest schedule version, on its engine, in its currency (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- Under the agreement version in force - the obligation's highest - and its pinned accrual engine.
    SELECT * INTO agreement FROM bnpl.bnpl_agreement a
     WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version AND a.obligation_id = NEW.obligation_id;
    IF agreement.agreement_id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NEW.accrual_engine IS DISTINCT FROM agreement.accrual_engine
            OR NEW.correlation_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL billing names its obligation''s agreement version in force and its pinned accrual engine (P12-TSK-015, INV-LND-09)';
    END IF;
    -- Dated its due date, which has come on the database clock in the pinned zone - never early, except beside the
    -- obligation's payoff execution (P12-TSK-022: the payoff bills every unbilled instalment, whatever its due date).
    IF NEW.value_date IS DISTINCT FROM instalment.due_date
            OR (instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) AND payoff.id IS NULL) THEN
        RAISE EXCEPTION 'a BNPL billing is dated its instalment''s due date, once that date has come on the database clock - early only beside its obligation''s payoff (P12-TSK-015, P12-TSK-022)';
    END IF;
    -- Oldest first: every earlier instalment of the version billed - judged by its due date (P12-TSK-021: a schedule
    -- version n+1 carries its billed instalments as new rows beside the billings that name version n's) - and never
    -- before the latest billing's date.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id AND i.sequence < instalment.sequence
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date))
            OR EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                        WHERE b.obligation_id = NEW.obligation_id AND b.value_date >= NEW.value_date) THEN
        RAISE EXCEPTION 'an obligation''s instalments are billed oldest first, one per due date (P12-TSK-015)';
    END IF;
    -- The period it closes: from the previous instalment's due date, the first from the schedule's start.
    period_from := COALESCE((SELECT p.due_date FROM bnpl.bnpl_instalment p
                              WHERE p.schedule_id = schedule.id AND p.sequence = instalment.sequence - 1),
                            schedule.start_date);
    IF NEW.period_start IS DISTINCT FROM period_from THEN
        RAISE EXCEPTION 'a BNPL billing closes its instalment''s own period (P12-TSK-015)';
    END IF;
    -- The interest: pay-in-4 none (A4); pay-in-N exactly the period's posted accruals - interest actually accrued, never the
    -- projection - and only once every date of the period that has elapsed by the billing's reach has accrued (the accrual
    -- gate, for every writer): the due date itself, or, for a payoff's early billing, the payoff's own date - every
    -- elapsed date through G - 1, the record caught up (no rebate arithmetic: what has not accrued is not owed).
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1' THEN
        accrued := 0;
    ELSE
        SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= period_from
           AND a.accrual_date < instalment.due_date;
        SELECT max(a.accrual_date) INTO accrued_through FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id;
        reach := CASE WHEN payoff.id IS NULL THEN instalment.due_date
                      ELSE LEAST(instalment.due_date, payoff.business_date) END;
        IF reach > period_from AND (accrued_through IS NULL OR accrued_through < reach - 1) THEN
            RAISE EXCEPTION 'a pay-in-N billing waits for every accrual of its period (P12-TSK-015, INV-LND-02)';
        END IF;
    END IF;
    IF NEW.interest_minor <> accrued THEN
        RAISE EXCEPTION 'a BNPL billing''s interest is exactly its period''s accrued interest (P12-TSK-015, INV-LND-02)';
    END IF;
    -- The principal (INV-BNPL-05): never beyond the version's principal not yet billed; the last instalment all of it, every
    -- other least(remaining, max(0, amount - interest)) - for pay-in-4 exactly its scheduled principal.
    remaining := schedule.principal_minor - (SELECT coalesce(sum(b.principal_minor), 0) FROM bnpl.bnpl_billing b
                                               WHERE b.obligation_id = NEW.obligation_id);
    IF instalment.sequence = schedule.instalment_count THEN
        expected := remaining;
    ELSE
        expected := LEAST(remaining, GREATEST(0, instalment.amount_minor - NEW.interest_minor));
    END IF;
    IF remaining < 0 OR NEW.principal_minor IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'a BNPL billing bills least(remaining, amount - interest) of principal, the last instalment the remainder (P12-TSK-015, INV-BNPL-05)';
    END IF;
    -- The business date it was made on and its instant - the database's, after the lock; a payoff's early billing is
    -- made on the payoff's date, never its future due date.
    NEW.business_date := GREATEST(bnpl.business_date_latest(obligation.servicing_zone),
                                  CASE WHEN payoff.id IS NULL THEN NEW.value_date
                                       ELSE LEAST(NEW.value_date, payoff.value_date) END);
    NEW.billed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The repayment's trigger, replaced: V011's, PAYOFF admitted - only beside its execution, keyed by its quote, dated its
-- value date.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the repayment, its floor and its allocation are judged under the lock every
    -- servicing act on it holds (T-pur, T-rep, T-svc and T-pay hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency
            OR obligation.agreement_id <> NEW.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'a BNPL repayment repays its ACTIVE obligation, in its currency, under its agreement version in force and its allocation engine (P12-TSK-018, INV-LND-08)';
    END IF;
    -- AT_PURCHASE: pay-in-4's instalment 1, inside the purchase, keyed by the purchase, dated the purchase's date.
    IF NEW.kind = 'AT_PURCHASE' AND (obligation.product <> 'BNPL_PAY_IN_4'
            OR NEW.request_key <> obligation.purchase_id::text OR NEW.value_date <> obligation.purchase_date) THEN
        RAISE EXCEPTION 'an AT_PURCHASE repayment is a pay-in-4 purchase''s own, keyed by it and dated its business date (P12-TSK-013, A5)';
    END IF;
    -- AUTO_COLLECTION (P12-TSK-019) is admitted here and held to its attempt - its key, amount, lines and version - by the
    -- attempt's trigger, and to having one at all by bnpl_repayment_auto_collection_has_its_attempt at commit.
    -- PAYOFF (P12-TSK-022): only beside its obligation's payoff execution, keyed by the executed quote and dated the
    -- execution's value date - one per obligation, since the execution is (and the deferred check closes it with it).
    IF NEW.kind = 'PAYOFF' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_payoff_execution e
                                            WHERE e.obligation_id = NEW.obligation_id
                                              AND e.quote_id::text = NEW.request_key
                                              AND e.value_date = NEW.value_date) THEN
        RAISE EXCEPTION 'a PAYOFF repayment is born only beside its obligation''s payoff execution, keyed by the quote and dated its value date (P12-TSK-022, INV-LND-08)';
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the
    -- latest date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a BNPL repayment is value-dated on or after its obligation''s floor and never after today (P12-TSK-013, the Phase 11 gate''s R1)';
    END IF;
    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The closure, replaced: V012's, plus PAID_OFF_EARLY beside the obligation's payoff execution - and no other reason
-- beside one.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    purchase_status text;
    paid_off boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL closure is immutable from insert, for every role (P12-TSK-018, INV-LIFE-04)';
    END IF;
    -- (B4) The obligation, for every writer; an ACTIVE obligation closing REPAID, or - P12-TSK-021 - REFUNDED when its
    -- purchase is FULLY_REFUNDED, or REFUNDED_PARTIALLY_REPAID beside a partial refund of a purchase not fully refunded,
    -- or - P12-TSK-022 - PAID_OFF_EARLY beside its payoff execution; beside an execution nothing else.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    SELECT p.status INTO purchase_status FROM bnpl.purchase p WHERE p.id = obligation.purchase_id;
    paid_off := EXISTS (SELECT 1 FROM bnpl.bnpl_payoff_execution e WHERE e.obligation_id = NEW.obligation_id);
    IF obligation.id IS NULL OR obligation.status <> 'ACTIVE'
            OR NOT ((paid_off AND NEW.reason = 'PAID_OFF_EARLY' AND NEW.refund_id IS NULL)
                    OR (NOT paid_off
                        AND ((NEW.reason = 'REPAID' AND purchase_status <> 'FULLY_REFUNDED')
                             OR (NEW.reason = 'REFUNDED' AND purchase_status = 'FULLY_REFUNDED')
                             OR (NEW.reason = 'REFUNDED_PARTIALLY_REPAID' AND purchase_status = 'FINANCED'
                                 AND NEW.refund_id IS NOT NULL)))) THEN
        RAISE EXCEPTION 'an ACTIVE BNPL obligation closes REPAID, REFUNDED (its purchase fully refunded), REFUNDED_PARTIALLY_REPAID (beside a partial refund) or PAID_OFF_EARLY (beside its payoff execution), and nothing else does (P12-TSK-022, INV-LND-08)';
    END IF;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s
     WHERE s.obligation_id = NEW.obligation_id
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x
                                  WHERE x.obligation_id = NEW.obligation_id);
    IF schedule.id IS NULL OR NEW.currency <> obligation.currency OR NEW.scale <> schedule.scale THEN
        RAISE EXCEPTION 'a BNPL closure refunds in its obligation''s currency and scale (P12-TSK-018)';
    END IF;
    IF NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment r
                             WHERE r.id = NEW.repayment_id AND r.obligation_id = NEW.obligation_id
                               AND (NEW.reason <> 'PAID_OFF_EARLY' OR r.kind = 'PAYOFF')) THEN
        RAISE EXCEPTION 'a BNPL closure names a repayment of its own obligation - a payoff''s its PAYOFF repayment (P12-TSK-018, P12-TSK-022)';
    END IF;
    IF NEW.refund_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_refund r
                             WHERE r.id = NEW.refund_id AND r.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL closure names a refund of its own obligation (P12-TSK-021)';
    END IF;
    -- The rows' side of "every receivable zero": every instalment of the latest schedule billed - by its due date
    -- (P12-TSK-021) - and every billed component settled in full by every payer and every refund. The ledger's side is
    -- the closing transaction's own, under B4.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                   WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every instalment of its schedule is billed (P12-TSK-018, INV-LND-08)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                 CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                WHERE b.obligation_id = NEW.obligation_id
                  AND bnpl.billing_component_paid(b.id, c.component)
                      <> bnpl.billing_component_billed(b.id, c.component)) THEN
        RAISE EXCEPTION 'a BNPL obligation closes only once every billed item is paid in full (P12-TSK-018, INV-LND-08)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the quote and the execution SELECT and INSERT only. Never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_payoff_quote TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_payoff_execution TO finapp_app;
