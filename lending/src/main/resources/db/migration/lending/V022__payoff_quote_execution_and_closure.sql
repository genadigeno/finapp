-- Payoff quote, execution and closure, both products (P11-TSK-026; PHASE_11_PLAN.md sections 5.3, 5.4, 7.1 T-pay, 7.2,
-- 12.3, 12.5 and 12.8 rule 7; ADR-0094 point 10, ADR-0095 point 8, ADR-0100 point 8; INV-LND-08, INV-LND-02, INV-LND-04,
-- INV-IDEM-01, INV-LIFE-04). The number is V022 - the board's reservation (V021 is P11-TSK-025's, built in parallel; the
-- backlog's "V019" is stale). V022 replaces none of V021's functions.
--
-- The borrower learns, and pays, exactly what ends the loan or line on a given day:
--
--     payoff_quote      - the quote (C, keyed): immutable, born under the loan's row lock (L3) with its inputs - the
--                         business date it was made on, the good-through date G (that date up to the pinned payoff
--                         horizon), the five receivables and the credit balance from the ledger, the base and the dates the
--                         accrual engine projected ([business date, G - 1], on the current base) and the interest they
--                         post, the agreement and engine versions - and the outstanding it names: P + PD + IA + ID + FD - CB
--                         + projected interest (negative when the credit balance exceeds everything owed).
--     payoff_execution  - the execution (C, keyed, MULTI_FACTOR): born once per quote and once per loan, on G only, first in
--                         T-pay - it admits, in its own transaction, the early billing of every unbilled instalment (and a
--                         line's early statement of its current cycle), the payoff's one repayment (kind PAYOFF, keyed by
--                         the quote) and the closure PAID_OFF_EARLY; a deferred check holds it to its loan CLOSED at commit,
--                         so nothing it admits can commit without the closure.
--
-- Posting rule 7 is rules 2, 3 (and 6), 5, then the closure's refund - no new posting shape: the catch-up accrual through
-- G - 1, every unbilled instalment billed (each followed by the credit-balance application), the one repayment allocated
-- to every component, the credit balance refunded under lending.closure-refund:<loan>, CLOSED. Daily accrual makes the
-- "early settlement rebate" zero by construction: no rebate arithmetic exists.
--
-- Replaced, each the predecessor's body plus exactly the payoff's change: instalment_billing_is_born_once (V012) and
-- credit_line_statement_is_born_once (V017) - never early, except beside the loan's payoff execution;
-- repayment_is_born_once (V018) - PAYOFF for either kind, only beside its execution; loan_closure_is_born_once (V018) -
-- PAID_OFF_EARLY beside the execution, REPAID never beside one; loan_permits_only_machine_edges (V018) - an instalment
-- loan's ACTIVE -> CLOSED for REPAID or PAID_OFF_EARLY beside its closure. A line's payoff closes CLOSED_BY_CUSTOMER through
-- V018's own edges, its closure request born by the payoff when the line is ACTIVE.
--
-- The ledger side of "the amount recomputed", "every receivable zero" and "every elapsed date accrued" is the transaction's
-- own, under L3 (lending's triggers never read the ledger).

-- ---------------------------------------------------------------------------------------------
-- The quote.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.payoff_quote (
    id                          uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    request_key                 text        NOT NULL,
    business_date               date        NOT NULL,
    good_through                date        NOT NULL,
    currency                    char(3)     NOT NULL,
    scale                       smallint    NOT NULL,
    principal_minor             bigint      NOT NULL,
    principal_due_minor         bigint      NOT NULL,
    interest_accrued_minor      bigint      NOT NULL,
    interest_due_minor          bigint      NOT NULL,
    fees_due_minor              bigint      NOT NULL,
    credit_balance_minor        bigint      NOT NULL,
    projection_base_minor       bigint      NOT NULL,
    projected_days              integer     NOT NULL,
    projected_interest_minor    bigint      NOT NULL,
    outstanding_minor           bigint      NOT NULL,
    agreement_id                uuid        NOT NULL,
    agreement_version           integer     NOT NULL,
    accrual_engine_version      smallint    NOT NULL,
    allocation_engine_version   smallint    NOT NULL,
    created_at                  timestamptz NOT NULL,
    correlation_id              text        NOT NULL,
    CONSTRAINT payoff_quote_pk PRIMARY KEY (id),
    -- One quote per loan and requester's key (INV-IDEM-01): the arbiter behind the door's claim.
    CONSTRAINT payoff_quote_key_unique UNIQUE (loan_id, request_key),
    CONSTRAINT payoff_quote_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT payoff_quote_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT payoff_quote_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT payoff_quote_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- G is the quote's date or later; the projection covers exactly [business date, G - 1] - nothing when G is today.
    CONSTRAINT payoff_quote_horizon_shape CHECK (
        good_through >= business_date AND projected_days = good_through - business_date),
    CONSTRAINT payoff_quote_amounts_shape CHECK (
        principal_minor >= 0 AND principal_due_minor >= 0 AND interest_accrued_minor >= 0 AND interest_due_minor >= 0
        AND fees_due_minor >= 0 AND credit_balance_minor >= 0 AND projection_base_minor >= 0
        AND projected_interest_minor >= 0),
    CONSTRAINT payoff_quote_projection_when_projected CHECK (projected_days > 0 OR projected_interest_minor = 0),
    -- The outstanding it names is exactly its inputs (PHASE_11_PLAN.md section 12.3's "outstanding", plus the projection).
    CONSTRAINT payoff_quote_outstanding_is_its_parts CHECK (
        outstanding_minor = principal_minor + principal_due_minor + interest_accrued_minor + interest_due_minor
                            + fees_due_minor - credit_balance_minor + projected_interest_minor),
    CONSTRAINT payoff_quote_versions_numbered CHECK (
        agreement_version >= 1 AND accrual_engine_version >= 1 AND allocation_engine_version >= 1),
    CONSTRAINT payoff_quote_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.payoff_quote IS
    'A payoff quote (P11-TSK-026; ADR-0095 point 8, PHASE_11_PLAN.md section 12.5): what ends the loan or line on its good-through date G - born under the loan''s row lock (L3), immutable, with its inputs: the business date it was made on, G (that date up to the pinned payoff horizon), the five receivables and the credit balance read from the ledger, the base and the dates [business date, G - 1] ACCRUAL_ENGINE_V1 projected on it and the interest they post, the agreement and engine versions, and the outstanding (receivables - credit balance + projected interest; negative when the credit balance exceeds everything owed). Executed only on G, only while the recomputed outstanding equals it (payoff_execution). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.payoff_quote_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a payoff quote is immutable from insert, for every role (P11-TSK-026, INV-LND-08)';
    END IF;
    -- An account a payoff can end: an ACTIVE instalment loan, or a credit line ACTIVE or CLOSING.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING'))) THEN
        RAISE EXCEPTION 'a payoff is quoted only for an ACTIVE instalment loan or a credit line ACTIVE or CLOSING (P11-TSK-026, INV-LND-08)';
    END IF;
    -- Under the account's governing agreement version - its highest - in its currency and scale, by its pinned engines
    -- (INV-LND-09); the terms that version pins decide the zone and the horizon.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'a payoff quote names its account''s governing agreement version, its currency and scale and its pinned engines (P11-TSK-026, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL THEN
        RAISE EXCEPTION 'a payoff is quoted under the terms its agreement pins (P11-TSK-026)';
    END IF;
    -- Made on this transaction's business date in the pinned zone (read by the quote after its lock), good through that
    -- date up to the pinned horizon (INV-LND-10).
    IF NEW.business_date < lending.business_date(transaction_timestamp(), terms.servicing_zone)
            OR NEW.business_date > lending.business_date(statement_timestamp(), terms.servicing_zone)
            OR NEW.good_through > NEW.business_date + terms.payoff_horizon_days THEN
        RAISE EXCEPTION 'a payoff quote is made on the business date of the database clock in its pinned zone, good through at most the pinned horizon after it (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER payoff_quote_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.payoff_quote
    FOR EACH ROW
    EXECUTE FUNCTION lending.payoff_quote_is_born_once();

CREATE TRIGGER payoff_quote_is_never_truncated
    BEFORE TRUNCATE ON lending.payoff_quote
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The execution.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.payoff_execution (
    id              uuid        NOT NULL,
    quote_id        uuid        NOT NULL,
    loan_id         uuid        NOT NULL,
    request_key     text        NOT NULL,
    business_date   date        NOT NULL,
    executed_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT payoff_execution_pk PRIMARY KEY (id),
    -- A quote is executed once (the backlog's arbiter beneath L3 and the claim) ...
    CONSTRAINT payoff_execution_quote_unique UNIQUE (quote_id),
    -- ... and a loan paid off once (INV-LIFE-04): its execution closes it.
    CONSTRAINT payoff_execution_loan_unique UNIQUE (loan_id),
    CONSTRAINT payoff_execution_quote_fk FOREIGN KEY (quote_id) REFERENCES lending.payoff_quote (id),
    CONSTRAINT payoff_execution_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT payoff_execution_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT payoff_execution_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.payoff_execution IS
    'A payoff quote executed (P11-TSK-026; ADR-0095 point 8, PHASE_11_PLAN.md sections 5.4 and 12.5): born once per quote and once per loan, on the quote''s good-through date only, first in T-pay under the loan''s row lock (L3) - beside it, and only beside it, every unbilled instalment is billed early (a line''s current cycle stated early), the payoff''s one repayment (kind PAYOFF, keyed by the quote) is received and the loan closes PAID_OFF_EARLY (a line CLOSED_BY_CUSTOMER); a deferred check holds it to its loan CLOSED at commit. The requester''s key, the business date and the instant. INSERT-only for every role. CONFIDENTIAL (the loan and quote references).';

CREATE OR REPLACE FUNCTION lending.payoff_execution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    quote lending.payoff_quote%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a payoff execution is immutable from insert, for every role (P11-TSK-026, INV-LIFE-04)';
    END IF;
    -- Its own loan's quote, on an account a payoff can still end.
    SELECT q.* INTO quote FROM lending.payoff_quote q WHERE q.id = NEW.quote_id AND q.loan_id = NEW.loan_id;
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF quote.id IS NULL OR account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING'))) THEN
        RAISE EXCEPTION 'a payoff executes its own loan''s quote, on an ACTIVE instalment loan or a credit line ACTIVE or CLOSING (P11-TSK-026, INV-LND-08)';
    END IF;
    -- On the quote's good-through date, which is this transaction's business date in the pinned zone (INV-LND-10).
    SELECT t.servicing_zone INTO zone
      FROM lending.loan_agreement a JOIN lending.loan_terms t ON t.terms_version_id = a.terms_version_id
     WHERE a.id = quote.agreement_id;
    IF zone IS NULL OR NEW.business_date <> quote.good_through
            OR NEW.business_date < lending.business_date(transaction_timestamp(), zone)
            OR NEW.business_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a payoff quote is executed only on its good-through date, on the database clock in its pinned zone (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.executed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER payoff_execution_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.payoff_execution
    FOR EACH ROW
    EXECUTE FUNCTION lending.payoff_execution_is_born_once();

CREATE TRIGGER payoff_execution_is_never_truncated
    BEFORE TRUNCATE ON lending.payoff_execution
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- At commit, for every writer: an execution's loan is CLOSED by its payoff - an instalment loan PAID_OFF_EARLY, a line
-- CLOSED_BY_CUSTOMER - beside its closure. So the early billings, the PAYOFF repayment and the PAID_OFF_EARLY closure the
-- execution admits commit only together with the closure (INV-LND-08).
CREATE OR REPLACE FUNCTION lending.payoff_execution_closes_its_loan()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan l
                     JOIN lending.loan_closure c ON c.loan_id = l.id AND c.reason = l.closure_reason
                    WHERE l.id = NEW.loan_id AND l.status = 'CLOSED'
                      AND ((l.kind = 'INSTALMENT' AND l.closure_reason = 'PAID_OFF_EARLY')
                           OR (l.kind = 'REVOLVING' AND l.closure_reason = 'CLOSED_BY_CUSTOMER'))) THEN
        RAISE EXCEPTION 'a payoff execution committed without its loan CLOSED by the payoff (P11-TSK-026, INV-LND-08)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER payoff_execution_closes_its_loan
    AFTER INSERT ON lending.payoff_execution
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.payoff_execution_closes_its_loan();

-- ---------------------------------------------------------------------------------------------
-- V012's billing trigger, replaced: never early - except beside the loan's payoff execution.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.instalment_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    schedule lending.repayment_schedule%ROWTYPE;
    instalment lending.loan_instalment%ROWTYPE;
    final_due date;
    accrued bigint;
    billed_principal bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a billing is immutable from insert, for every role (P11-TSK-017, INV-LND-01)';
    END IF;
    -- An instalment loan in servicing, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status NOT IN ('ACTIVE', 'CLOSING')
            OR zone IS NULL THEN
        RAISE EXCEPTION 'an instalment is billed only on an ACTIVE or CLOSING instalment loan (P11-TSK-017)';
    END IF;
    -- Never early (INV-LND-10): the due date has come in the pinned zone on the database clock - except beside the loan's
    -- payoff execution (P11-TSK-026), which bills everything unbilled in its own transaction. An execution exists only in
    -- the transaction that closes its loan (payoff_execution_closes_its_loan, deferred), and a billing only for an open
    -- loan, so an execution found here is this transaction's.
    IF NEW.due_date > lending.business_date(statement_timestamp(), zone)
            AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'an instalment is billed only once its due date has come on the database clock in its pinned zone, or by its loan''s payoff (P11-TSK-017, P11-TSK-026, INV-LND-10)';
    END IF;
    -- Under the account's own agreement version, in its currency and scale, by its pinned accrual engine.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version THEN
        RAISE EXCEPTION 'a billing names its account''s agreement version, its currency and scale and its pinned accrual engine (P11-TSK-017, INV-LND-09)';
    END IF;
    -- Of the loan's LATEST schedule version, by that version's engine (a superseded version's unbilled instalments are
    -- never billed - PHASE_11_PLAN.md section 12.2 step 7).
    SELECT s.* INTO schedule FROM lending.repayment_schedule s
     WHERE s.id = NEW.schedule_id AND s.loan_id = NEW.loan_id AND s.schedule_version = NEW.schedule_version
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM lending.repayment_schedule x WHERE x.loan_id = NEW.loan_id);
    IF schedule.id IS NULL OR NEW.schedule_engine_version <> schedule.schedule_engine_version
            OR NEW.currency <> schedule.currency OR NEW.scale <> schedule.scale THEN
        RAISE EXCEPTION 'a billing bills the loan''s latest schedule version (P11-TSK-017, INV-LND-03)';
    END IF;
    SELECT max(i.due_date) INTO final_due FROM lending.loan_instalment i WHERE i.schedule_id = schedule.id;
    IF NEW.kind = 'INSTALMENT' THEN
        -- The instalment itself: its schedule, its sequence, its period.
        SELECT i.* INTO instalment FROM lending.loan_instalment i
         WHERE i.id = NEW.instalment_id AND i.schedule_id = schedule.id AND i.loan_id = NEW.loan_id;
        IF instalment.id IS NULL OR instalment.sequence <> NEW.sequence
                OR instalment.period_start <> NEW.period_start OR instalment.due_date <> NEW.due_date THEN
            RAISE EXCEPTION 'a billing bills its own instalment''s period (P11-TSK-017)';
        END IF;
        -- Oldest first: every earlier instalment of the version already billed.
        IF EXISTS (SELECT 1 FROM lending.loan_instalment i
                    WHERE i.schedule_id = schedule.id AND i.sequence < NEW.sequence
                      AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a loan''s instalments are billed oldest first (P11-TSK-017)';
        END IF;
    ELSE
        -- After the final due date, every instalment of the version billed: the calendar continued, interest only.
        IF NEW.period_start < final_due
                OR EXISTS (SELECT 1 FROM lending.loan_instalment i
                            WHERE i.schedule_id = schedule.id
                              AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a post-maturity period is billed only after every instalment of the schedule (P11-TSK-017)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM lending.interest_accrual a
                        WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.period_start
                          AND a.period_end = NEW.due_date) THEN
            RAISE EXCEPTION 'a post-maturity period is billed only when interest accrued in it (P11-TSK-017)';
        END IF;
    END IF;
    -- Never before a billed item already due later: oldest first across both kinds.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                WHERE b.loan_id = NEW.loan_id AND b.due_date >= NEW.due_date) THEN
        RAISE EXCEPTION 'a loan''s billed items are born oldest first, one per due date (P11-TSK-017)';
    END IF;
    -- Interest actually accrued, never the projection (ADR-0094 point 6): exactly the sum of the period's accruals. The
    -- accrual's own trigger then refuses any later date inside a billed period, so the equality holds for ever.
    SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM lending.interest_accrual a
     WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.period_start AND a.period_end = NEW.due_date;
    IF NEW.interest_due_minor <> accrued THEN
        RAISE EXCEPTION 'a billing''s interest due is exactly its period''s accrued interest (P11-TSK-017, ADR-0094 point 6)';
    END IF;
    -- Principal is billed at most once over the loan's life: never beyond its agreement's principal (INV-LND-03).
    SELECT coalesce(sum(b.principal_due_minor), 0) INTO billed_principal FROM lending.instalment_billing b
     WHERE b.loan_id = NEW.loan_id;
    IF billed_principal + NEW.principal_due_minor > (SELECT a.amount_minor FROM lending.loan_agreement a
                                                       WHERE a.loan_id = NEW.loan_id AND a.version = 1) THEN
        RAISE EXCEPTION 'the principal billed over a loan''s life never exceeds its principal (P11-TSK-017, INV-LND-03)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V017's statement trigger, replaced: never early - except beside the line's payoff execution.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.credit_line_statement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    previous_end date;
    accrued bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a statement is immutable from insert, for every role (P11-TSK-022, INV-LND-01)';
    END IF;
    -- A line in servicing.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a statement is issued only for an ACTIVE or CLOSING credit line (P11-TSK-022)';
    END IF;
    -- Under the line's governing agreement version - its highest - in its currency and scale, by its pinned accrual and
    -- statement engines (INV-LND-09); the terms that version pins decide the zone and the payment-due days.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version
            OR NEW.statement_engine_version IS DISTINCT FROM agreement.statement_engine_version THEN
        RAISE EXCEPTION 'a statement names its line''s governing agreement version, its currency and scale and its pinned accrual and statement engines (P11-TSK-022, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL OR terms.payment_due_days IS NULL THEN
        RAISE EXCEPTION 'a statement is issued under the credit line terms its agreement pins (P11-TSK-022)';
    END IF;
    -- Never early (INV-LND-10): the statement date has come in the pinned zone on the database clock - except beside the
    -- line's payoff execution (P11-TSK-026), which states its current cycle on the accruals through yesterday in its own
    -- transaction (an execution exists only in the transaction that closes its line).
    IF NEW.cycle_end > lending.business_date(statement_timestamp(), terms.servicing_zone)
            AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a statement is issued only once its statement date has come on the database clock in its pinned zone, or by its line''s payoff (P11-TSK-022, P11-TSK-026, INV-LND-10)';
    END IF;
    -- Every cycle ends on the statement day and falls due the pinned payment-due days later (A23).
    IF extract(day FROM NEW.cycle_end) <> agreement.repayment_day
            OR NEW.due_date <> NEW.cycle_end + terms.payment_due_days THEN
        RAISE EXCEPTION 'a statement''s cycle ends on the statement day and its payment falls due the pinned days after it (P11-TSK-022, A23)';
    END IF;
    -- Oldest first and contiguous: never before a statement already issued, and each cycle starting where the last ended -
    -- the first at the line's opening business date.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end >= NEW.cycle_end) THEN
        RAISE EXCEPTION 'a line''s statements are issued oldest first, one per cycle (P11-TSK-022)';
    END IF;
    SELECT max(s.cycle_end) INTO previous_end FROM lending.credit_line_statement s WHERE s.loan_id = NEW.loan_id;
    IF NEW.cycle_start <> coalesce(previous_end, lending.business_date(account.opened_at, terms.servicing_zone)) THEN
        RAISE EXCEPTION 'a line''s cycles are contiguous from its opening (P11-TSK-022, A23)';
    END IF;
    -- Interest actually accrued, never the projection (ADR-0094 point 6): exactly the sum of the cycle's accruals. The
    -- accrual's own trigger then refuses any later date inside a stated cycle, so the equality holds for ever.
    SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM lending.interest_accrual a
     WHERE a.loan_id = NEW.loan_id AND a.period_start = NEW.cycle_start AND a.period_end = NEW.cycle_end;
    IF NEW.interest_billed_minor <> accrued THEN
        RAISE EXCEPTION 'a statement''s interest billed is exactly its cycle''s accrued interest (P11-TSK-022, ADR-0094 point 6)';
    END IF;
    -- A billed item's identity names one item: never an instalment billing's.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.id = NEW.id) THEN
        RAISE EXCEPTION 'a statement''s identity is its own, never a billing''s (P11-TSK-022)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V018's repayment trigger, replaced: a payoff's one repayment, for either kind, only beside its execution.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    -- An ACTIVE instalment loan, or a credit line ACTIVE or CLOSING - the customer's repayment, its automatic collection or
    -- its payoff's one repayment (P11-TSK-026) - and its pinned servicing zone. A CLOSED or CANCELLED account takes no money
    -- (INV-LND-08).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR zone IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING')
                        AND NEW.kind IN ('CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF'))) THEN
        RAISE EXCEPTION 'a repayment is received only by an ACTIVE instalment loan, or by a credit line ACTIVE or CLOSING (P11-TSK-018, P11-TSK-023, INV-LND-08)';
    END IF;
    -- A payoff's one repayment only beside its execution, its key the quote (P11-TSK-026): UNIQUE (loan_id, kind,
    -- request_key) and the execution's UNIQUE (loan_id) make it one per loan.
    IF NEW.kind = 'PAYOFF' AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e
                                            WHERE e.loan_id = NEW.loan_id AND e.quote_id::text = NEW.request_key) THEN
        RAISE EXCEPTION 'a payoff''s repayment is received only beside its execution, keyed by its quote (P11-TSK-026, INV-IDEM-01)';
    END IF;
    -- Under the loan's governing agreement version - its highest - in its currency and scale, by its pinned allocation
    -- engine (INV-LND-09).
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'a repayment names its loan''s governing agreement version, its currency and scale and its pinned allocation engine (P11-TSK-018, INV-LND-09)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (ADR-0094 point 8, INV-LND-10): read
    -- by the repayment after its locks, so between the transaction's start and this statement.
    IF NEW.value_date < lending.business_date(transaction_timestamp(), zone)
            OR NEW.value_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V018's closure trigger, replaced: an instalment loan's payoff closes it PAID_OFF_EARLY beside its execution.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_closure_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a closure is immutable from insert, for every role (P11-TSK-018, INV-LIFE-04)';
    END IF;
    -- An ACTIVE instalment loan closing REPAID - or PAID_OFF_EARLY beside its payoff's execution (P11-TSK-026), never
    -- REPAID beside one - or a credit line ACTIVE or CLOSING closing CLOSED_BY_CUSTOMER beside the customer's closure
    -- request (P11-TSK-023; a line's payoff births the request when the line is ACTIVE).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE' AND NEW.reason = 'REPAID'
                     AND NOT EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id))
                    OR (account.kind = 'INSTALMENT' AND account.status = 'ACTIVE' AND NEW.reason = 'PAID_OFF_EARLY'
                        AND EXISTS (SELECT 1 FROM lending.payoff_execution e WHERE e.loan_id = NEW.loan_id))
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING')
                        AND NEW.reason = 'CLOSED_BY_CUSTOMER'
                        AND EXISTS (SELECT 1 FROM lending.line_closure_request q WHERE q.loan_id = NEW.loan_id))) THEN
        RAISE EXCEPTION 'an ACTIVE instalment loan closes REPAID or, by its payoff, PAID_OFF_EARLY, and a credit line CLOSED_BY_CUSTOMER beside its closure request; nothing else closes here (P11-TSK-018, P11-TSK-023, P11-TSK-026, INV-LND-08)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a closure refunds in its loan''s currency and scale (P11-TSK-018)';
    END IF;
    IF NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM lending.repayment r WHERE r.id = NEW.repayment_id AND r.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a closure names a repayment of its own loan (P11-TSK-018)';
    END IF;
    -- A payoff's closure names its payoff's one repayment, or none when the credit balance paid everything (P11-TSK-026).
    IF NEW.reason = 'PAID_OFF_EARLY' AND NEW.repayment_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM lending.repayment r WHERE r.id = NEW.repayment_id AND r.kind = 'PAYOFF') THEN
        RAISE EXCEPTION 'a payoff''s closure names its payoff''s repayment (P11-TSK-026)';
    END IF;
    -- The rows' side of "every receivable zero". An instalment loan: every instalment of the latest schedule billed, and
    -- every billed component paid in full. A line: every statement's every component paid in full. The ledger's side is
    -- the closing transaction's own, under L3 (lending's triggers never read the ledger).
    IF account.kind = 'INSTALMENT' THEN
        IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id)
                OR EXISTS (SELECT 1 FROM lending.loan_instalment i
                            WHERE i.schedule_id = (SELECT s.id FROM lending.repayment_schedule s
                                                    WHERE s.loan_id = NEW.loan_id
                                                    ORDER BY s.schedule_version DESC LIMIT 1)
                              AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a loan closes only once every instalment of its schedule is billed (P11-TSK-018, INV-LND-08)';
        END IF;
        IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                     CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                    WHERE b.loan_id = NEW.loan_id
                      AND lending.billing_component_paid(b.id, c.component)
                          <> lending.billing_component_billed(b.id, c.component)) THEN
            RAISE EXCEPTION 'a loan closes only once every billed item is paid in full (P11-TSK-018, INV-LND-08)';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                    CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                   WHERE s.loan_id = NEW.loan_id
                     AND lending.billing_component_paid(s.id, c.component)
                         <> lending.billing_component_billed(s.id, c.component)) THEN
        RAISE EXCEPTION 'a line closes only once every statement is paid in full (P11-TSK-023, INV-LND-08)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The loan's edge function, replaced: V018's edges, an instalment loan's closure REPAID or PAID_OFF_EARLY.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer lending.loan_offer%ROWTYPE;
    offered_currency char(3);
    disbursement lending.loan_disbursement%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Born by kind (LENDING_LIFECYCLES.md section 4.5): a loan awaits its disbursement, a line is born serviceable.
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NOT ((NEW.kind = 'INSTALMENT' AND NEW.status = 'PENDING_DISBURSEMENT')
                        OR (NEW.kind = 'REVOLVING' AND NEW.status = 'ACTIVE')) THEN
            RAISE EXCEPTION 'a loan is born PENDING_DISBURSEMENT, a credit line ACTIVE, and open (P11-TSK-012)';
        END IF;
        -- Born under its offer's row lock (L2), for its own party's OFFERED, unexpired offer of the same product, on its
        -- decision, in its currency - the offer moves ACCEPTED in the same transaction.
        SELECT * INTO offer FROM lending.loan_offer o WHERE o.id = NEW.offer_id;
        IF NOT FOUND OR offer.status <> 'OFFERED' OR offer.expires_at <= statement_timestamp()
                OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a loan is born for its own party''s OFFERED, unexpired offer of the same product, on its decision (P11-TSK-012)';
        END IF;
        SELECT t.currency INTO offered_currency FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.offer_id;
        IF offered_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'a loan is held in its offer''s currency (P11-TSK-012)';
        END IF;
        NEW.opened_at := statement_timestamp();
        -- Due for servicing at once - an account is claimed only while ACTIVE or CLOSING (P11-TSK-016).
        NEW.next_servicing_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.offer_id IS DISTINCT FROM OLD.offer_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan''s identity, kind, currency and origin are frozen from birth (P11-TSK-012)';
    END IF;
    -- The servicing permit alone (P11-TSK-016): an account in servicing moves its permit and nothing else - the sweep's
    -- claim and a completed servicing step (which may re-stamp the instant it already holds: ten steps racing on one
    -- account each stamp the same next business date). Never a terminal account's, never beside another column.
    IF NEW.status = OLD.status THEN
        IF OLD.status IN ('ACTIVE', 'CLOSING')
                AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
                AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason
                AND NEW.cancellation_reason IS NOT DISTINCT FROM OLD.cancellation_reason THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a % loan moves only its servicing permit, alone (P11-TSK-016)', OLD.status;
    END IF;
    -- The disbursement's two edges (P11-TSK-014): each beside the disbursement's matching state, in its transaction.
    IF OLD.status = 'PENDING_DISBURSEMENT' AND NEW.status IN ('ACTIVE', 'CANCELLED') THEN
        SELECT * INTO disbursement FROM lending.loan_disbursement d WHERE d.loan_id = OLD.id;
        IF NEW.status = 'ACTIVE' THEN
            IF NOT FOUND OR disbursement.status <> 'POSTED' OR NEW.closed_at IS NOT NULL
                    OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL THEN
                RAISE EXCEPTION 'a loan is ACTIVE only beside its disbursement POSTED (P11-TSK-014, INV-LND-07)';
            END IF;
        ELSE
            IF NOT FOUND OR disbursement.status <> 'FAILED' OR NEW.closure_reason IS NOT NULL
                    OR NEW.cancellation_reason IS DISTINCT FROM disbursement.failure_reason THEN
                RAISE EXCEPTION 'a loan is CANCELLED only beside its disbursement FAILED, for the same reason (P11-TSK-014)';
            END IF;
            NEW.closed_at := statement_timestamp();
        END IF;
        RETURN NEW;
    END IF;
    -- The closure (P11-TSK-018): an instalment loan ACTIVE -> CLOSED (REPAID) - or (PAID_OFF_EARLY) by its payoff
    -- (P11-TSK-026) - only beside its loan_closure row born in this transaction for the same reason (which holds the rows'
    -- side of "every receivable zero" and, for a payoff, the execution beside it); the terminal instant is the trigger's.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' AND OLD.kind = 'INSTALMENT' THEN
        IF NEW.closure_reason IS NULL OR NEW.closure_reason NOT IN ('REPAID', 'PAID_OFF_EARLY')
                OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'an instalment loan is CLOSED (REPAID or PAID_OFF_EARLY) only beside its closure (P11-TSK-018, P11-TSK-026, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- A credit line's closure request (P11-TSK-023): ACTIVE -> CLOSING, only beside its request born for that outcome - the
    -- line then takes no draw (V013's draw trigger admits only ACTIVE) and is still serviced, open, never terminal.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSING' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSING') THEN
            RAISE EXCEPTION 'a credit line is CLOSING only beside the customer''s closure request (P11-TSK-023, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- A credit line's closure (P11-TSK-023): ACTIVE -> CLOSED at the request when nothing is outstanding (its request born
    -- for that outcome), CLOSING -> CLOSED in the transaction that zeroes its last receivable - each CLOSED_BY_CUSTOMER and
    -- only beside its loan_closure row (the closure trigger holds the rows' side and the request beside it).
    IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'CLOSED' AND OLD.kind = 'REVOLVING' THEN
        IF NEW.closure_reason IS DISTINCT FROM 'CLOSED_BY_CUSTOMER' OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason)
                OR (OLD.status = 'ACTIVE'
                    AND NOT EXISTS (SELECT 1 FROM lending.line_closure_request q
                                     WHERE q.loan_id = OLD.id AND q.outcome = 'CLOSED')) THEN
            RAISE EXCEPTION 'a credit line is CLOSED (CLOSED_BY_CUSTOMER) only beside its closure and the customer''s request (P11-TSK-023, INV-LND-08)';
        END IF;
        NEW.closed_at := statement_timestamp();
        RETURN NEW;
    END IF;
    -- Every other edge is never: out of CLOSED and CANCELLED nothing, ever (INV-LIFE-04); CLOSING -> ACTIVE never (a closing
    -- line is never reopened - a new application); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never
    -- (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge (P11-TSK-026, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.payoff_quote TO finapp_app;
GRANT SELECT, INSERT ON lending.payoff_execution TO finapp_app;
