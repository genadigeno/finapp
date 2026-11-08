-- Lending's clock is the database's, whatever a role's search_path holds (P15-TSK-004; the C15 debt row "Lending triggers
-- resolve statement_timestamp() through search_path", A6-08; THREAT_MODEL.md T13, T33; INV-LND-05, INV-LND-10).
--
-- WHY
--   A lending trigger function is invoker's rights with no search_path of its own, and it called the clock functions
--   unqualified. PostgreSQL searches pg_catalog first only when the path does not name it: a session whose path puts a
--   schema of its own BEFORE pg_catalog resolves statement_timestamp() to that schema's function, so a role with such a
--   path could change what every judgement below reads as now, without touching a row or a rule.
--
-- WHAT CHANGES, AND ONLY THAT
--   Every function of this schema that reads a clock function unqualified, each replaced with its latest definition across
--   the whole chain, byte for byte but for pg_catalog. written before each clock call in code - never in a comment or a
--   string. That is 39 functions, 76 calls: 37 trigger functions, and the 2 helpers those triggers call
--   for the transaction's business date, without which a trigger would still read a shadowed clock through them.
--   Taken from V002, V003, V004, V005, V006, V018, V019, V021, V022, V023, V027, V028, V029, V030, V031, V032, V033, the last definition of each. V033's two (lending.loan_offer_permits_only_machine_edges and lending.loan_terms_version_permits_only_machine_edges) keep PI-014's fix.
--
-- WHAT IT DOES NOT CHANGE
--   No judgement on any path a production role has: pg_catalog's functions are the ones those paths already resolved to.
--   No signature, owner, grant, trigger binding, table, column or row. It moves no money. CREATE OR REPLACE keeps the
--   function's OID, so every trigger stays bound, and it takes no lock on any table.
--
-- CURRENT_TIMESTAMP, LOCALTIMESTAMP and CURRENT_DATE are not here because they are grammar, not functions: they are never
-- resolved through a path. Every other schema's functions are named, each with its owner, by
-- com.finapp.app.architecture.OwnerReachOverHistoryIsClosedTest.

-- lending.accrual_start_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.accrual_start_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
    payout lending.loan_payout%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an accrual start is immutable from insert, for every role (P11-TSK-014)';
    END IF;
    IF NEW.origin = 'DISBURSEMENT' THEN
        -- Path W: beside its instalment loan ACTIVE and its wallet disbursement POSTED, on that entry's value date.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF NOT FOUND OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NOT NULL
                OR NEW.start_date <> disbursement.value_date
                OR NEW.journal_entry_id IS DISTINCT FROM disbursement.journal_entry_id THEN
            RAISE EXCEPTION 'a wallet disbursement''s accrual start is its entry''s value date, beside the loan ACTIVE (P11-TSK-014, ADR-0097)';
        END IF;
    ELSE
        -- Path X (A30): beside its instalment loan ACTIVE, its path X disbursement POSTED and its payout terminal, on the
        -- business date of the payout's conclusion in the pinned zone - never before the disbursement's value date, never
        -- after this statement's business date.
        -- P11-TSK-033: "this statement's" read as GREATEST(the clock, the payout's birth) - the conclusion's own reference.
        -- P11-TSK-036: "the clock" the latest business date this transaction read (its start's or this statement's).
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT p.* INTO payout FROM lending.loan_payout p WHERE p.loan_id = NEW.loan_id;
        SELECT t.servicing_zone INTO zone
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id;
        IF disbursement.id IS NULL OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NULL
                OR payout.id IS NULL OR payout.status NOT IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED')
                OR NEW.journal_entry_id IS NOT NULL
                OR NEW.start_date <> lending.business_date(payout.concluded_at, zone)
                OR NEW.start_date < disbursement.value_date
                OR NEW.start_date > GREATEST(lending.business_date_latest(zone), lending.business_date(payout.created_at, zone)) THEN
            RAISE EXCEPTION 'a payout''s accrual start is the business date of its conclusion, beside the loan ACTIVE and the payout terminal (P11-TSK-015, A30)';
        END IF;
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.business_date_earliest: V028's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.business_date_earliest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT lending.business_date(LEAST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp()), servicing_zone)
$$;

-- lending.business_date_latest: V028's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.business_date_latest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT lending.business_date(GREATEST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp()), servicing_zone)
$$;

-- lending.capital_contribution_event_is_append_only: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.capital_contribution_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a capital contribution event is append-only (P11-TSK-004, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- lending.capital_contribution_is_born_once: V002's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.capital_contribution_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a capital contribution is a born-once fact (P11-TSK-004): its machine is its history';
    END IF;
    NEW.proposed_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- lending.collection_attempt_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.collection_attempt_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    item_loan uuid;
    item_due date;
    repayment lending.repayment%ROWTYPE;
    still_due bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a collection attempt is immutable from insert, for every role (P11-TSK-019, INV-IDEM-02)';
    END IF;
    -- An ACTIVE instalment loan or credit line, a CLOSING line (P11-TSK-023) - a closed or cancelled account takes no money,
    -- INV-LND-08 - or an account this attempt's own collection has just closed: the repayment closes it in its transaction
    -- (P11-TSK-018, P11-TSK-023) before the attempt naming it is recorded.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT (account.status = 'ACTIVE'
                    OR (account.kind = 'REVOLVING' AND account.status = 'CLOSING')
                    OR (account.status = 'CLOSED' AND NEW.repayment_id IS NOT NULL
                        AND EXISTS (SELECT 1 FROM lending.loan_closure c
                                     WHERE c.loan_id = NEW.loan_id AND c.repayment_id = NEW.repayment_id))) THEN
        RAISE EXCEPTION 'a collection is attempted only for an ACTIVE loan or credit line, or a CLOSING line (P11-TSK-019, P11-TSK-023, INV-LND-08)';
    END IF;
    -- The authority: the governing agreement's pinned terms collect automatically, and the borrower chose the wallet.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    IF terms.terms_version_id IS NULL OR NOT terms.auto_collection
            OR NOT EXISTS (SELECT 1 FROM lending.loan_offer o JOIN lending.loan_application p ON p.id = o.application_id
                            WHERE o.id = account.offer_id AND p.repayment_method = 'WALLET') THEN
        RAISE EXCEPTION 'a collection is attempted only under an agreement whose pinned terms collect automatically from the wallet (P11-TSK-019, A21)';
    END IF;
    IF NEW.outcome = 'PARTIAL' AND NOT terms.partial_collection THEN
        RAISE EXCEPTION 'a partial collection only where the pinned terms allow it (P11-TSK-019, A21)';
    END IF;
    IF NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a collection attempt is in its loan''s currency and scale (P11-TSK-019)';
    END IF;
    -- Dated this transaction's business date in the pinned zone (ADR-0094 point 8, INV-LND-10).
    IF NEW.attempt_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.attempt_date > lending.business_date_latest(terms.servicing_zone) THEN
        RAISE EXCEPTION 'a collection attempt is dated the business date of the database clock in its pinned zone (P11-TSK-019, INV-LND-10)';
    END IF;
    -- A billed item - a billing or a statement - of its own loan, already due (a statement on its payment due date).
    item_loan := lending.billed_item_loan(NEW.due_item_id);
    item_due := lending.billed_item_due_date(NEW.due_item_id);
    IF item_loan IS DISTINCT FROM NEW.loan_id OR item_due > NEW.attempt_date THEN
        RAISE EXCEPTION 'a collection is attempted on a billed item of its own loan, on or after its due date (P11-TSK-019, P11-TSK-022)';
    END IF;
    -- What was due less what was collected is what the item still has due beside it (every payer's rows counted).
    SELECT sum(lending.billing_component_billed(NEW.due_item_id, c.component)
               - lending.billing_component_paid(NEW.due_item_id, c.component))
      INTO still_due
      FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF NEW.due_minor - NEW.collected_minor <> still_due THEN
        RAISE EXCEPTION 'a collection attempt records what its item had due and what it collected (P11-TSK-019, INV-LND-04)';
    END IF;
    -- The money, when it moved: an AUTO_COLLECTION repayment of this loan under the attempt's key, dated its date, for the
    -- amount collected, every line paying this billed item - never the credit balance or principal not yet due, so never
    -- more than due.
    IF NEW.repayment_id IS NOT NULL THEN
        SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.loan_id <> NEW.loan_id OR repayment.kind <> 'AUTO_COLLECTION'
                OR repayment.request_key <> NEW.due_item_id::text || ':' || NEW.attempt_date::text
                OR repayment.value_date <> NEW.attempt_date OR repayment.amount_minor <> NEW.collected_minor
                OR EXISTS (SELECT 1 FROM lending.repayment_allocation a
                            WHERE a.repayment_id = repayment.id
                              AND (a.target <> 'DUE_ITEM' OR a.billing_id <> NEW.due_item_id)) THEN
            RAISE EXCEPTION 'a collection names its own AUTO_COLLECTION repayment, which pays only its billed item (P11-TSK-019, INV-LND-04)';
        END IF;
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.credit_balance_application_is_born_once: V029's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.credit_balance_application_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance application is immutable from insert, for every role (P11-TSK-017, INV-LND-01)';
    END IF;
    -- V029: (L3) the account, for every writer - a catch-up application's number is judged under the lock every payer of
    -- the loan holds (the platform's writer holds it already; re-taking it is free).
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a credit balance is applied only on an ACTIVE or CLOSING account (P11-TSK-017)';
    END IF;
    IF NEW.billing_id IS NOT NULL THEN
        -- At its own loan's billed item, in its transaction's servicing step: the billing or the statement is the loan's.
        IF lending.billed_item_loan(NEW.billing_id) IS DISTINCT FROM NEW.loan_id THEN
            RAISE EXCEPTION 'a credit-balance application is born at its own loan''s billing or statement (P11-TSK-017, P11-TSK-022)';
        END IF;
    ELSIF NEW.catch_up_sequence IS DISTINCT FROM 1 + coalesce(
            (SELECT max(c.catch_up_sequence) FROM lending.credit_balance_application c WHERE c.loan_id = NEW.loan_id), 0) THEN
        -- V029: a catch-up application is the loan's next - numbered in order, never a gap, never a second at one number.
        RAISE EXCEPTION 'a catch-up credit-balance application is its loan''s next (Phase 11 gate R2, INV-LND-04)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'an application names its account''s agreement version, its currency and scale and its pinned allocation engine (P11-TSK-017, INV-LND-09)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.credit_line_draw_is_born_once: V030's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.credit_line_draw_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit line draw is immutable from insert, for every role (P11-TSK-021, INV-LND-14)';
    END IF;
    -- An ACTIVE credit line, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status <> 'ACTIVE' OR zone IS NULL THEN
        RAISE EXCEPTION 'a draw is made only on an ACTIVE credit line (P11-TSK-021, INV-LND-14)';
    END IF;
    -- Under the line's governing agreement version - its highest - in its currency and scale, never above its limit.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.amount_minor > agreement.amount_minor THEN
        RAISE EXCEPTION 'a draw names its line''s governing agreement version, in its currency and scale, and never exceeds its limit (P11-TSK-021, INV-LND-14)';
    END IF;
    -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the step
    -- after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever order the clock
    -- read the two, which a clock stepped back across a midnight reverses (business_date_earliest / business_date_latest).
    -- The Phase 11 gate (R1): never a date the line's record has closed - the floor outranks the clock (ADR-0063 decision
    -- 2), so a step back across a midnight dates the draw on the floor, a date the clock showed before it stepped.
    value_floor := lending.principal_value_floor(NEW.loan_id);
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > GREATEST(lending.business_date_latest(zone), value_floor) THEN
        RAISE EXCEPTION 'a draw''s value date is the business date of the database clock in its pinned zone (P11-TSK-021, A10)';
    END IF;
    IF NEW.value_date < value_floor THEN
        RAISE EXCEPTION 'a draw is never value-dated a business date its line''s record has closed - accrued or stated (the Phase 11 gate R1, INV-LND-02, ADR-0063 decision 2)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.credit_line_statement_is_born_once: V028's definition, 1 clock call(s) qualified.
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
    IF NEW.cycle_end > lending.business_date_latest(terms.servicing_zone)
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
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.fee_assessment_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.fee_assessment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    assessed bigint;
    owing bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a fee assessment is immutable from insert, for every role (P11-TSK-025, INV-HIST-01)';
    END IF;
    -- (L3) The account, for every writer - the lock every act on it serialises on, re-entrant for the servicing step.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a fee is assessed only on an ACTIVE or CLOSING account (P11-TSK-025, INV-LND-08)';
    END IF;
    IF lending.billed_item_loan(NEW.due_item_id) IS DISTINCT FROM NEW.loan_id THEN
        RAISE EXCEPTION 'a fee is assessed on a billed item of its own account (P11-TSK-025)';
    END IF;
    -- Under the governing agreement version - its highest - in its currency and scale; its pinned terms decide.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL
            OR NEW.agreement_version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a fee names its account''s governing agreement version, in its currency and scale (P11-TSK-025, INV-LND-09)';
    END IF;
    SELECT t.* INTO terms FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
    -- Dated the business date of its transaction in the pinned zone - never early, never back-dated (INV-LND-10).
    IF NEW.business_date > lending.business_date_latest(terms.servicing_zone)
            OR NEW.business_date < lending.business_date_earliest(terms.servicing_zone) THEN
        RAISE EXCEPTION 'a fee is dated the business date of its transaction in the pinned zone (P11-TSK-025, INV-LND-10)';
    END IF;
    -- On the day the item's own age reaches the pinned late-fee day, or later (a missed day is caught up) - never in grace.
    IF NEW.business_date - lending.billed_item_due_date(NEW.due_item_id) < terms.late_fee_day THEN
        RAISE EXCEPTION 'a late fee is assessed only once its item''s age has reached the pinned late-fee day (P11-TSK-025, A18)';
    END IF;
    -- At most the pinned fee; the account's fees within the pinned cap.
    SELECT coalesce(sum(f.amount_minor), 0) INTO assessed FROM lending.fee_assessment f WHERE f.loan_id = NEW.loan_id;
    IF NEW.amount_minor > terms.late_fee_minor OR assessed + NEW.amount_minor > terms.late_fee_cap_minor THEN
        RAISE EXCEPTION 'a late fee is at most the pinned fee, and an account''s fees at most the pinned cap (P11-TSK-025, A18)';
    END IF;
    -- Only on an item that still owes interest or principal: a paid item is never charged late.
    SELECT sum(lending.billing_component_billed(NEW.due_item_id, c.component)
               - lending.billing_component_paid(NEW.due_item_id, c.component)) INTO owing
      FROM (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF coalesce(owing, 0) <= 0 THEN
        RAISE EXCEPTION 'a late fee is assessed only on an item still owing interest or principal (P11-TSK-025, A18)';
    END IF;
    -- A line's fee is never dated inside a cycle already stated: each fee is carried by exactly one statement.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end > NEW.business_date) THEN
        RAISE EXCEPTION 'a line''s fee is never dated inside a cycle already stated (P11-TSK-025, A24)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.instalment_billing_is_born_once: V028's definition, 1 clock call(s) qualified.
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
    prepaid_principal bigint;
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
    IF NEW.due_date > lending.business_date_latest(zone)
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
    -- never billed - PHASE_11_PLAN.md section 12.2 step 7) - and under the agreement version that schedule projects
    -- (P11-TSK-027: after an amendment, version n+1's, so the new rate's schedule is billed under the new agreement;
    -- P11-TSK-028: after a prepayment, its version's).
    SELECT s.* INTO schedule FROM lending.repayment_schedule s
     WHERE s.id = NEW.schedule_id AND s.loan_id = NEW.loan_id AND s.schedule_version = NEW.schedule_version
       AND s.schedule_version = (SELECT max(x.schedule_version) FROM lending.repayment_schedule x WHERE x.loan_id = NEW.loan_id);
    IF schedule.id IS NULL OR NEW.schedule_engine_version <> schedule.schedule_engine_version
            OR NEW.currency <> schedule.currency OR NEW.scale <> schedule.scale
            OR NEW.agreement_version <> schedule.agreement_version THEN
        RAISE EXCEPTION 'a billing bills the loan''s latest schedule version, under the agreement version it projects (P11-TSK-017, P11-TSK-027, INV-LND-03)';
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
    -- Principal is billed at most once over the loan's life: the principal billed NET OF RE-SCHEDULING (an amendment's
    -- arrears re-scheduled are billed again under the new schedule, P11-TSK-027) PLUS the principal PREPAID (P11-TSK-028 -
    -- paid before it was ever billed) never beyond its agreement's principal - version 1's, which no later version changes
    -- (INV-LND-03).
    SELECT coalesce(sum(lending.billing_component_billed(b.id, 'PRINCIPAL')), 0) INTO billed_principal
      FROM lending.instalment_billing b
     WHERE b.loan_id = NEW.loan_id;
    SELECT coalesce(sum(p.amount_minor), 0) INTO prepaid_principal
      FROM lending.repayment_allocation p
      JOIN lending.repayment r ON r.id = p.repayment_id
     WHERE r.loan_id = NEW.loan_id AND r.kind = 'PREPAYMENT' AND p.target = 'NOT_DUE_PRINCIPAL';
    IF billed_principal + prepaid_principal + NEW.principal_due_minor
            > (SELECT a.amount_minor FROM lending.loan_agreement a WHERE a.loan_id = NEW.loan_id AND a.version = 1) THEN
        RAISE EXCEPTION 'the principal billed and prepaid over a loan''s life never exceeds its principal (P11-TSK-017, P11-TSK-028, INV-LND-03)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.interest_accrual_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    started date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P11-TSK-016, INV-LND-02)';
    END IF;
    -- An account in servicing, and its pinned servicing zone.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') OR zone IS NULL THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE or CLOSING account (P11-TSK-016)';
    END IF;
    -- Never early (INV-LND-10): the date has ended in the pinned zone on the database clock.
    IF NEW.accrual_date >= lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock in its pinned zone (P11-TSK-016, INV-LND-10)';
    END IF;
    -- From the accrual start: an instalment loan's accrual_start row (none - nothing accrues: path X awaiting its payout),
    -- a line's opening business date.
    IF account.kind = 'INSTALMENT' THEN
        SELECT s.start_date INTO started FROM lending.accrual_start s WHERE s.loan_id = NEW.loan_id;
    ELSE
        started := lending.business_date(account.opened_at, zone);
    END IF;
    IF started IS NULL OR NEW.accrual_date < started THEN
        RAISE EXCEPTION 'interest accrues from the account''s accrual start, and not before it (P11-TSK-016, ADR-0094)';
    END IF;
    -- Oldest first: never a date at or before one already accrued.
    IF EXISTS (SELECT 1 FROM lending.interest_accrual a
                WHERE a.loan_id = NEW.loan_id AND a.accrual_date >= NEW.accrual_date) THEN
        RAISE EXCEPTION 'an account''s dates accrue oldest first, each once (P11-TSK-016, INV-LND-02)';
    END IF;
    -- Never into a period already billed (P11-TSK-017): a billing made its period's accrued interest due, so a date
    -- before the latest billed due date can never accrue afterwards - billed interest stays the accrued interest.
    IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a period already billed never accrues afterwards (P11-TSK-017, ADR-0094 point 6)';
    END IF;
    -- Never into a cycle already stated (P11-TSK-022): a statement made its cycle's accrued interest due, so a date before
    -- the latest statement date can never accrue afterwards - stated interest stays the accrued interest.
    IF EXISTS (SELECT 1 FROM lending.credit_line_statement s
                WHERE s.loan_id = NEW.loan_id AND s.cycle_end > NEW.accrual_date) THEN
        RAISE EXCEPTION 'a date inside a cycle already stated never accrues afterwards (P11-TSK-022, ADR-0094 point 6)';
    END IF;
    -- Under the account's own agreement version, in its currency and scale, by its pinned accrual engine.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.accrual_engine_version <> agreement.accrual_engine_version THEN
        RAISE EXCEPTION 'an accrual names its account''s agreement version, its currency and scale and its pinned accrual engine (P11-TSK-016, INV-LND-09)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.line_closure_request_is_born_once: V018's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.line_closure_request_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a closure request is immutable from insert, for every role (P11-TSK-023, INV-LIFE-04)';
    END IF;
    -- An ACTIVE credit line: an instalment loan closes by being repaid, a closing or closed line has been asked already.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'REVOLVING' OR account.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a closure is requested only for an ACTIVE credit line (P11-TSK-023, INV-LIFE-04)';
    END IF;
    NEW.requested_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_acceptance_is_born_once: V023's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_acceptance_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'acceptance evidence is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    IF NEW.agreement_version = 1 THEN
        -- The evidence binds what was accepted: its agreement's version, hash and template, and the offer's own document
        -- hash, the bytes the customer was shown.
        IF NOT EXISTS (
                SELECT 1
                  FROM lending.loan_agreement a
                  JOIN lending.loan_offer_terms t ON t.offer_id = a.offer_id
                 WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
                   AND a.offer_id = NEW.offer_id AND a.terms_sha256 = NEW.terms_sha256
                   AND a.agreement_template_id = NEW.agreement_template_id
                   AND a.agreement_template_version = NEW.agreement_template_version
                   AND t.document_sha256 = NEW.document_sha256) THEN
            RAISE EXCEPTION 'acceptance evidence binds its agreement''s version, terms hash, template and document hash (P11-TSK-012)';
        END IF;
    ELSIF NEW.offer_id IS NOT NULL OR NOT EXISTS (
            SELECT 1
              FROM lending.loan_agreement a
              JOIN lending.loan_amendment m ON m.id = a.amendment_id
             WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
               AND a.terms_sha256 = NEW.terms_sha256
               AND a.agreement_template_id = NEW.agreement_template_id
               AND a.agreement_template_version = NEW.agreement_template_version
               AND m.document_sha256 = NEW.document_sha256) THEN
        -- An amended version (P11-TSK-027): the amendment's own document hash - the document the customer was shown.
        RAISE EXCEPTION 'an amendment''s acceptance evidence binds its agreement''s version, terms hash and template and the amendment''s document hash (P11-TSK-027)';
    END IF;
    NEW.accepted_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_agreement_is_born_once: V027's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
    decision lending.loan_amendment_decision%ROWTYPE;
    previous lending.loan_agreement%ROWTYPE;
    prepaid lending.repayment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan agreement version is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    IF NEW.version = 1 THEN
        -- Version 1: born beside its loan, for the loan's own offer and decision, as an EXACT copy of the offer's terms
        -- (INV-LND-05) - the bytes the customer was shown and hashed, never re-rendered.
        IF NOT EXISTS (
                SELECT 1
                  FROM lending.loan l
                  JOIN lending.loan_offer o ON o.id = l.offer_id
                  JOIN lending.loan_offer_terms t ON t.offer_id = o.id
                 WHERE l.id = NEW.loan_id AND l.offer_id = NEW.offer_id AND l.decision_id = NEW.decision_id
                   AND o.terms_version_id = NEW.terms_version_id AND o.product = NEW.product
                   AND t.currency = NEW.currency AND t.scale = NEW.scale AND t.amount_minor = NEW.amount_minor
                   AND t.term_months IS NOT DISTINCT FROM NEW.term_months
                   AND t.nominal_annual_rate = NEW.nominal_annual_rate AND t.repayment_day = NEW.repayment_day
                   AND t.schedule_engine_version IS NOT DISTINCT FROM NEW.schedule_engine_version
                   AND t.accrual_engine_version = NEW.accrual_engine_version
                   AND t.allocation_engine_version = NEW.allocation_engine_version
                   AND t.statement_engine_version IS NOT DISTINCT FROM NEW.statement_engine_version
                   AND t.agreement_template_id = NEW.agreement_template_id
                   AND t.agreement_template_version = NEW.agreement_template_version
                   AND t.canonical_format = NEW.canonical_format
                   AND t.canonical_terms = NEW.canonical_terms
                   AND t.terms_sha256 = NEW.terms_sha256) THEN
            RAISE EXCEPTION 'agreement version 1 is born beside its loan as an exact copy of its offer''s terms (P11-TSK-012, INV-LND-05)';
        END IF;
    ELSIF NEW.amendment_id IS NOT NULL THEN
        -- Version n+1 (P11-TSK-027): an accepted amendment's, under the loan's row lock (L3), on an ACTIVE instalment loan.
        SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
        IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'an agreement is amended only on an ACTIVE instalment loan - a closed loan lapses its amendment (P11-TSK-027, INV-LND-08)';
        END IF;
        -- APPROVED and unexpired on the database clock, of this loan.
        SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id AND m.loan_id = NEW.loan_id;
        SELECT d.* INTO decision FROM lending.loan_amendment_decision d WHERE d.amendment_id = NEW.amendment_id;
        IF proposal.id IS NULL OR decision.id IS NULL OR decision.outcome <> 'APPROVED' THEN
            RAISE EXCEPTION 'an agreement version after the first is an APPROVED, unexpired amendment''s (P11-TSK-027, INV-LND-11)';
        END IF;
        -- THE VERSION CONDITIONAL: the agreement it amends is still the governing one, and this is the next version - a
        -- moved agreement (another amendment, a prepayment) lapses it.
        SELECT a.* INTO previous FROM lending.loan_agreement a
         WHERE a.loan_id = NEW.loan_id
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
        IF previous.id IS DISTINCT FROM proposal.amends_agreement_id OR previous.version <> proposal.amends_version
                OR NEW.version <> previous.version + 1 THEN
            RAISE EXCEPTION 'an amendment applies only to the agreement version it amends, as the next version - it lapsed (P11-TSK-027, INV-LND-05)';
        END IF;
        -- NEVER MORE CREDIT: the same principal, decision, terms version, product, currency, day, engines and template; the
        -- amended rate (never above the previous); the term the instalments billed plus the remaining instalments, never
        -- fewer than the current schedule has left.
        IF NEW.offer_id IS NOT NULL OR NEW.decision_id <> previous.decision_id
                OR NEW.terms_version_id <> previous.terms_version_id OR NEW.product <> previous.product
                OR NEW.currency <> previous.currency OR NEW.scale <> previous.scale
                OR NEW.amount_minor <> previous.amount_minor OR NEW.repayment_day <> previous.repayment_day
                OR NEW.schedule_engine_version IS DISTINCT FROM previous.schedule_engine_version
                OR NEW.accrual_engine_version <> previous.accrual_engine_version
                OR NEW.allocation_engine_version <> previous.allocation_engine_version
                OR NEW.statement_engine_version IS DISTINCT FROM previous.statement_engine_version
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.nominal_annual_rate <> proposal.nominal_annual_rate
                OR NEW.nominal_annual_rate::numeric > previous.nominal_annual_rate::numeric
                OR proposal.remaining_instalments < lending.unbilled_instalments(NEW.loan_id)
                OR NEW.term_months IS DISTINCT FROM ((SELECT count(*) FROM lending.instalment_billing b
                                                       WHERE b.loan_id = NEW.loan_id AND b.kind = 'INSTALMENT')
                                                     + proposal.remaining_instalments)::smallint THEN
            RAISE EXCEPTION 'an amended agreement keeps its principal, decision, terms version, day, engines and template and takes exactly the amendment''s rate and term - never more credit (P11-TSK-027)';
        END IF;
        -- The lapse, judged last - after the loan, the amendment's identity and decision, the version conditional and
        -- never more credit, so a breach of any of them is never answered as a lapse - and in its own SQLSTATE
        -- (P11-TSK-035): the acceptance judged the amendment unexpired at its L5 read and then accrued, billed and
        -- planned, so the database clock may cross the offer's expires_at before this statement - the customer's 409,
        -- not a breach.
        IF decision.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'an agreement version is never born for a lapsed amendment - its offer expired (P11-TSK-027, P11-TSK-035, INV-LND-11)'
                USING ERRCODE = 'LNAEX';
        END IF;
    ELSE
        -- Version n+1 (P11-TSK-028): a prepayment's, under the loan's row lock (L3), on an ACTIVE instalment loan.
        SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
        IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'a prepayment re-schedules only an ACTIVE instalment loan (P11-TSK-028, INV-LND-08)';
        END IF;
        -- The prepayment was received under the governing version, and this is the next one: a prepayment re-schedules the
        -- agreement it was paid under, once (UNIQUE (prepayment_id)).
        SELECT a.* INTO previous FROM lending.loan_agreement a
         WHERE a.loan_id = NEW.loan_id
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
        SELECT r.* INTO prepaid FROM lending.repayment r
         WHERE r.id = NEW.prepayment_id AND r.loan_id = NEW.loan_id AND r.kind = 'PREPAYMENT';
        IF prepaid.id IS NULL OR prepaid.agreement_id IS DISTINCT FROM previous.id
                OR prepaid.agreement_version <> previous.version OR NEW.version <> previous.version + 1 THEN
            RAISE EXCEPTION 'a prepayment''s version is the next version of the agreement it was received under (P11-TSK-028, INV-LND-05)';
        END IF;
        -- NOTHING BUT THE TERM: version n's principal, decision, terms version, product, currency, RATE, day, engines and
        -- template; the term never longer (exactly the instalments billed plus the new schedule's - held at commit).
        IF NEW.offer_id IS NOT NULL OR NEW.decision_id <> previous.decision_id
                OR NEW.terms_version_id <> previous.terms_version_id OR NEW.product <> previous.product
                OR NEW.currency <> previous.currency OR NEW.scale <> previous.scale
                OR NEW.amount_minor <> previous.amount_minor OR NEW.repayment_day <> previous.repayment_day
                OR NEW.nominal_annual_rate <> previous.nominal_annual_rate
                OR NEW.schedule_engine_version IS DISTINCT FROM previous.schedule_engine_version
                OR NEW.accrual_engine_version <> previous.accrual_engine_version
                OR NEW.allocation_engine_version <> previous.allocation_engine_version
                OR NEW.statement_engine_version IS DISTINCT FROM previous.statement_engine_version
                OR NEW.agreement_template_id <> previous.agreement_template_id
                OR NEW.agreement_template_version <> previous.agreement_template_version
                OR NEW.term_months IS NULL OR NEW.term_months > previous.term_months THEN
            RAISE EXCEPTION 'a prepayment''s version keeps everything of the version it follows but a term never longer (P11-TSK-028)';
        END IF;
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    NEW.effective_from := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_amendment_decision_is_born_once: V030's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_amendment_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_amendment%ROWTYPE;
    account lending.loan%ROWTYPE;
    validity integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an amendment decision is immutable from insert, for every role (P11-TSK-027, INV-HIST-01)';
    END IF;
    SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'an amendment decision decides an amendment (P11-TSK-027)';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    -- The Phase 11 gate (A2-F3): the instant written after L3, never the transaction's start (V025's decisions).
    NEW.decided_at := pg_catalog.clock_timestamp();
    IF NEW.outcome = 'APPROVED' THEN
        -- Only on an ACTIVE loan whose agreement has not moved since the proposal: otherwise the amendment lapsed.
        IF account.status <> 'ACTIVE'
                OR proposal.amends_version <> (SELECT max(x.version) FROM lending.loan_agreement x
                                                WHERE x.loan_id = proposal.loan_id) THEN
            RAISE EXCEPTION 'an amendment is approved only on an ACTIVE loan whose agreement has not moved - it lapsed (P11-TSK-027)';
        END IF;
        -- The customer's offer: open for the pinned offer validity, on the database clock.
        SELECT t.offer_validity_days INTO validity
          FROM lending.loan_agreement a JOIN lending.loan_terms t ON t.terms_version_id = a.terms_version_id
         WHERE a.id = proposal.amends_agreement_id;
        NEW.expires_at := NEW.decided_at + make_interval(days => validity);
    ELSE
        NEW.expires_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

-- lending.loan_amendment_is_born_once: V023's definition, 2 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_amendment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    unbilled integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an amendment is a born-once fact (P11-TSK-027): its decision is loan_amendment_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT' OR account.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'an amendment is proposed only on an ACTIVE instalment loan - a line has no schedule to restructure (P11-TSK-027, INV-LND-08)';
    END IF;
    -- It amends the loan's governing agreement version - its highest.
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF agreement.id IS DISTINCT FROM NEW.amends_agreement_id OR agreement.version <> NEW.amends_version THEN
        RAISE EXCEPTION 'an amendment amends its loan''s governing agreement version (P11-TSK-027, INV-LND-05)';
    END IF;
    -- A scheduled loan only: one awaiting its payout's outcome has nothing to restructure (P11-TSK-015's carry).
    IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'an amendment restructures a scheduled loan (P11-TSK-027)';
    END IF;
    -- Never a higher rate (a new decision), never a shorter remaining term, and something changes.
    unbilled := lending.unbilled_instalments(NEW.loan_id);
    IF NEW.nominal_annual_rate::numeric > agreement.nominal_annual_rate::numeric
            OR NEW.remaining_instalments < unbilled
            OR NOT (NEW.nominal_annual_rate::numeric < agreement.nominal_annual_rate::numeric
                    OR NEW.remaining_instalments > unbilled OR NEW.reschedule_arrears) THEN
        RAISE EXCEPTION 'an amendment lowers the rate, lengthens the remaining term or re-schedules the arrears - never a higher rate or a shorter term (P11-TSK-027)';
    END IF;
    -- One open amendment per loan: none undecided, none approved, unaccepted and unexpired, on the governing version.
    IF EXISTS (SELECT 1 FROM lending.loan_amendment m
                 LEFT JOIN lending.loan_amendment_decision d ON d.amendment_id = m.id
                WHERE m.loan_id = NEW.loan_id AND m.amends_version = agreement.version
                  AND (d.id IS NULL
                       OR (d.outcome = 'APPROVED' AND d.expires_at > pg_catalog.statement_timestamp()
                           AND NOT EXISTS (SELECT 1 FROM lending.loan_agreement x WHERE x.amendment_id = m.id)))) THEN
        RAISE EXCEPTION 'an amendment of this loan is already open (P11-TSK-027)';
    END IF;
    NEW.proposed_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_amendment_rescheduling_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_amendment_rescheduling_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_amendment%ROWTYPE;
    amended lending.loan_agreement%ROWTYPE;
    billing lending.instalment_billing%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a re-scheduling is immutable from insert, for every role (P11-TSK-027, INV-HIST-01)';
    END IF;
    -- (L3) The account, for every writer.
    PERFORM 1 FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = NEW.amendment_id AND m.loan_id = NEW.loan_id;
    -- In the acceptance alone: the amendment re-schedules arrears, its agreement version is the loan's latest, and its
    -- schedule version is not yet born (the deferred check holds the three to one transaction).
    SELECT a.* INTO amended FROM lending.loan_agreement a WHERE a.amendment_id = NEW.amendment_id;
    IF proposal.id IS NULL OR NOT proposal.reschedule_arrears OR amended.id IS NULL
            OR amended.version <> (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
            OR EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.agreement_id = amended.id) THEN
        RAISE EXCEPTION 'arrears are re-scheduled only in the acceptance of an amendment that re-schedules them, before its schedule (P11-TSK-027)';
    END IF;
    -- A billed item of the loan, past due on the business date of this transaction in the pinned zone.
    SELECT b.* INTO billing FROM lending.instalment_billing b WHERE b.id = NEW.billing_id AND b.loan_id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone FROM lending.loan_terms t WHERE t.terms_version_id = amended.terms_version_id;
    IF billing.id IS NULL OR billing.due_date >= lending.business_date_latest(zone)
            OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'arrears re-scheduled are a past-due billed item''s of the loan, in its currency and scale (P11-TSK-027)';
    END IF;
    -- Exactly the item's whole unpaid billed principal - never its interest or fees (never capitalised), never more.
    IF NEW.principal_minor <> lending.billing_component_billed(NEW.billing_id, 'PRINCIPAL')
                              - lending.billing_component_paid(NEW.billing_id, 'PRINCIPAL') THEN
        RAISE EXCEPTION 'a re-scheduling moves exactly its item''s unpaid billed principal (P11-TSK-027, INV-LND-03)';
    END IF;
    -- One rule 13 entry per amendment.
    IF EXISTS (SELECT 1 FROM lending.loan_amendment_rescheduling r
                WHERE r.amendment_id = NEW.amendment_id AND r.journal_entry_id <> NEW.journal_entry_id) THEN
        RAISE EXCEPTION 'an amendment''s re-scheduled arrears name its one rule 13 entry (P11-TSK-027)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_application_event_is_append_only: V004's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_application_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan application event is append-only (P11-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_application_permits_only_machine_edges: V005's definition, 3 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_application_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a loan application is born SUBMITTED and open (P11-TSK-010)';
        END IF;
        -- Born on the product's ACTIVE terms version - the one the submission read FOR SHARE - and on a day it offers.
        IF NOT EXISTS (SELECT 1 FROM lending.loan_terms_version v
                        WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'a loan application records its product''s ACTIVE terms version (P11-TSK-010)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM lending.loan_terms t
                        WHERE t.terms_version_id = NEW.terms_version_id AND NEW.repayment_day = ANY (t.allowed_days)) THEN
            RAISE EXCEPTION 'a loan application''s repayment or statement day is one its terms version offers (P11-TSK-010)';
        END IF;
        NEW.submitted_at := pg_catalog.transaction_timestamp();
        -- Due for the origination step at once (P11-TSK-011).
        NEW.next_step_at := pg_catalog.transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan application is never deleted (P11-TSK-010, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.repayment_day IS DISTINCT FROM OLD.repayment_day
            OR NEW.repayment_method IS DISTINCT FROM OLD.repayment_method
            OR NEW.payout_preference IS DISTINCT FROM OLD.payout_preference
            OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
            OR NEW.submitted_by_type IS DISTINCT FROM OLD.submitted_by_type
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan application''s identity and choices are frozen from birth (P11-TSK-010)';
    END IF;
    -- A permit-only move (P11-TSK-011): only an application awaiting its decision has a next step, the move moves it,
    -- and nothing but the permit moves with it - the closure stamp is the database's, at a terminal edge.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'AWAITING_DECISION' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an application AWAITING_DECISION has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    -- The machine's edges and no other - a terminal application never moves, and SUBMITTED is never skipped.
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')
            OR (OLD.status = 'AWAITING_DECISION'
                AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a loan application moves SUBMITTED -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN; % -> % is not an edge (P11-TSK-010)', OLD.status, NEW.status;
    END IF;
    -- -> OFFERED only beside its offer (P11-TSK-011): the offer is born first, in the same transaction.
    IF NEW.status = 'OFFERED' AND NOT EXISTS (SELECT 1 FROM lending.loan_offer o WHERE o.application_id = NEW.id) THEN
        RAISE EXCEPTION 'a loan application is OFFERED only beside its offer (P11-TSK-011)';
    END IF;
    -- The database stamps the closure; nobody writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL ELSE pg_catalog.transaction_timestamp() END;
    RETURN NEW;
END;
$$;

-- lending.loan_closure_is_born_once: V022's definition, 1 clock call(s) qualified.
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
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_condition_event_is_born_once: V028's definition, 1 clock call(s) qualified.
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
    IF NEW.business_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.business_date > lending.business_date_latest(terms.servicing_zone) THEN
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
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_disbursement_permits_only_machine_edges: V028's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_disbursement_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    deadline_hours integer;
    preference text;
    loan_status text;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'a disbursement is born PENDING (P11-TSK-012)';
        END IF;
        -- Beside its instalment loan awaiting disbursement; the deadline is its agreement's pinned terms version's.
        SELECT t.disbursement_deadline_hours, a.payout_preference INTO deadline_hours, preference
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_application a ON a.id = o.application_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'PENDING_DISBURSEMENT';
        IF NOT FOUND OR deadline_hours IS NULL THEN
            RAISE EXCEPTION 'a disbursement is born beside its instalment loan PENDING_DISBURSEMENT (P11-TSK-012)';
        END IF;
        -- Path X exactly when the application preferred an external account (A25; ADR-0097).
        IF (preference = 'EXTERNAL_ACCOUNT') <> (NEW.payout_method_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a disbursement names a payout method exactly when its application preferred an external account (P11-TSK-012)';
        END IF;
        NEW.created_at := pg_catalog.statement_timestamp();
        NEW.deadline_at := pg_catalog.statement_timestamp() + make_interval(hours => deadline_hours);
        -- Due for the disbursement sweep at once (P11-TSK-014).
        NEW.next_attempt_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a disbursement is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.payout_method_id IS DISTINCT FROM OLD.payout_method_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a disbursement''s identity, path, birth and deadline are frozen (P11-TSK-014)';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a % disbursement is terminal (P11-TSK-014, INV-LIFE-04)', OLD.status;
    END IF;
    -- A permit-only move: the sweep's claim re-stamps the permit and nothing else.
    IF NEW.status = 'PENDING' THEN
        IF NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at OR NEW.journal_entry_id IS NOT NULL
                OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a PENDING disbursement moves its permit and nothing else (P11-TSK-014)';
        END IF;
        RETURN NEW;
    END IF;
    -- PENDING -> POSTED | FAILED, while its loan still awaits it: the loan moves beside it, in this transaction, and the
    -- deferred check holds the pair at commit (on path X with its payout - loan_disbursed_beside_its_facts).
    SELECT l.status, t.servicing_zone INTO loan_status, zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = OLD.loan_id;
    IF loan_status IS DISTINCT FROM 'PENDING_DISBURSEMENT' THEN
        RAISE EXCEPTION 'a disbursement is decided only while its loan is PENDING_DISBURSEMENT (P11-TSK-014, INV-LND-07)';
    END IF;
    NEW.next_attempt_at := OLD.next_attempt_at;
    NEW.decided_at := pg_catalog.statement_timestamp();
    IF NEW.status = 'POSTED' THEN
        IF NEW.journal_entry_id IS NULL OR NEW.value_date IS NULL OR NEW.failure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a POSTED disbursement names its journal entry and value date, and no failure (P11-TSK-014)';
        END IF;
        -- The value date is this transaction's business date in the pinned servicing zone (A10, INV-LND-10): read by the
        -- step after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever order
        -- the clock read the two, which a clock stepped back across a midnight reverses (business_date_earliest / _latest).
        IF NEW.value_date < lending.business_date_earliest(zone)
                OR NEW.value_date > lending.business_date_latest(zone) THEN
            RAISE EXCEPTION 'a disbursement''s value date is the business date of the database clock in its pinned zone (P11-TSK-014, A10)';
        END IF;
    ELSIF NEW.status = 'FAILED' THEN
        IF NEW.journal_entry_id IS NOT NULL OR NEW.value_date IS NOT NULL OR NEW.failure_reason IS NULL THEN
            RAISE EXCEPTION 'a FAILED disbursement names its reason and posted nothing (P11-TSK-014, rule 11)';
        END IF;
        -- A wallet that cannot be posted is retried until the deadline: the failure is judged on the database clock.
        IF NEW.failure_reason = 'DISBURSEMENT_FAILED' AND OLD.deadline_at > pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'a disbursement fails only once its deadline has passed on the database clock (P11-TSK-014)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a disbursement moves PENDING -> POSTED | FAILED; % -> % is not an edge (P11-TSK-014)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

-- lending.loan_event_is_append_only: V006's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan event is append-only (P11-TSK-012, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_offer_event_is_append_only: V005's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_offer_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan offer event is append-only (P11-TSK-011, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_offer_permits_only_machine_edges: V033's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application lending.loan_application%ROWTYPE;
    validity_days integer;
    version_start timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a loan offer is born OFFERED and open (P11-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party and product.
        SELECT * INTO application FROM lending.loan_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product THEN
            RAISE EXCEPTION 'a loan offer is born for its own party''s application AWAITING_DECISION, of the same product (P11-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_days, v.effective_from INTO validity_days, version_start
          FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a loan offer pins its product''s ACTIVE terms version (P11-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is a database instant of this transaction, after the step's locks, so the illustrative
        -- schedule's first day and the expiry are one clock's. P11-TSK-036: stamped HERE, by this statement's clock - never
        -- a value an earlier statement read, which a database clock stepped back between the two put after this clock (or
        -- before the transaction's start) and V005/V006 refused - and never before the transaction began (ADR-0063
        -- decision 2, the order of facts over the clock's reading). P15-TSK-033 (PI-014): and never before the pinned
        -- version began, which a step can put ahead of this clock. The step takes it back from RETURNING.
        NEW.offered_at := GREATEST(pg_catalog.statement_timestamp(), pg_catalog.transaction_timestamp(), version_start);
        -- The expiry on the database clock: the earlier of the decision's validity and the pinned version's offer
        -- validity (PHASE_11_PLAN.md section 5.2) - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.offered_at + make_interval(days => validity_days));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a loan offer is never born expired: the decision has lapsed (P11-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan offer is never deleted (P11-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan offer''s identity, pin and expiry are frozen from birth (P11-TSK-011, INV-LND-05)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % loan offer is terminal (P11-TSK-011)', OLD.status;
    END IF;
    -- The three clock-sided edges: the customer's two while expires_at > the database clock, the expiry once <=  -
    -- exactly one of them at the boundary instant. The customer's side refused past the instant in its own SQLSTATE
    -- (P11-TSK-034), as the loan's birth is.
    IF NEW.status IN ('DECLINED', 'ACCEPTED') THEN
        IF OLD.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'an expired loan offer cannot be % - it expires (P11-TSK-011, P11-TSK-012)', lower(NEW.status)
                USING ERRCODE = 'LNEXP';
        END IF;
        -- ACCEPTED only beside its loan (P11-TSK-012): the loan is born first, in the same transaction, and the deferred
        -- check holds its agreement and evidence beside it at commit.
        IF NEW.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM lending.loan l WHERE l.offer_id = NEW.id) THEN
            RAISE EXCEPTION 'a loan offer is ACCEPTED only beside its loan (P11-TSK-012)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer expires only once expires_at <= the database clock (P11-TSK-011)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a loan offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P11-TSK-011)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_payout_permits_only_machine_edges: V019's definition, 13 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_payout_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    disbursement lending.loan_disbursement%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Born PENDING in the disbursement's transaction: its instalment loan ACTIVE beside its path X disbursement POSTED,
        -- for that disbursement's payout method.
        SELECT d.* INTO disbursement
          FROM lending.loan_disbursement d JOIN lending.loan l ON l.id = d.loan_id
         WHERE d.loan_id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF NOT FOUND OR disbursement.status <> 'POSTED' OR disbursement.payout_method_id IS NULL
                OR disbursement.payout_method_id <> NEW.payment_method_id THEN
            RAISE EXCEPTION 'a payout is born beside its loan ACTIVE and its path X disbursement POSTED, for its payout method (P11-TSK-015)';
        END IF;
        IF NEW.status <> 'PENDING' OR NEW.withdrawal_id IS NOT NULL OR NEW.not_dispatched_reason IS NOT NULL
                OR NEW.last_observed_at IS NOT NULL OR NEW.dispatched_at IS NOT NULL OR NEW.concluded_at IS NOT NULL
                OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a payout is born PENDING, undispatched (P11-TSK-015)';
        END IF;
        NEW.created_at := pg_catalog.statement_timestamp();
        -- Due for the payout sweep at once.
        NEW.next_attempt_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a payout is never deleted (P11-TSK-015, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.loan_id IS DISTINCT FROM OLD.loan_id
            OR NEW.payment_method_id IS DISTINCT FROM OLD.payment_method_id
            OR NEW.wallet_account_id IS DISTINCT FROM OLD.wallet_account_id OR NEW.hold_id IS DISTINCT FROM OLD.hold_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
            OR (OLD.withdrawal_id IS NOT NULL AND NEW.withdrawal_id IS DISTINCT FROM OLD.withdrawal_id)
            OR (OLD.dispatched_at IS NOT NULL AND NEW.dispatched_at IS DISTINCT FROM OLD.dispatched_at) THEN
        RAISE EXCEPTION 'a payout''s identity, method, wallet, hold, birth and withdrawal are frozen (P11-TSK-015)';
    END IF;
    IF OLD.status IN ('PAID_OUT', 'FAILED', 'NOT_DISPATCHED') THEN
        RAISE EXCEPTION 'a % payout is terminal (P11-TSK-015, INV-LIFE-04)', OLD.status;
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'DISPATCHING' THEN
        -- The claim: a permit stamped forward from the database clock, nothing else.
        IF NEW.next_attempt_at < pg_catalog.statement_timestamp() OR NEW.withdrawal_id IS NOT NULL
                OR NEW.not_dispatched_reason IS NOT NULL OR NEW.dispatched_at IS NOT NULL
                OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            RAISE EXCEPTION 'a payout''s claim stamps its permit forward and nothing else (P11-TSK-015)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'DISPATCHING' THEN
        -- A re-drive's claim: the permit moved forward, nothing else.
        IF NEW.next_attempt_at IS NOT DISTINCT FROM OLD.next_attempt_at OR NEW.next_attempt_at < pg_catalog.statement_timestamp()
                OR NEW.withdrawal_id IS NOT NULL OR NEW.not_dispatched_reason IS NOT NULL
                OR NEW.dispatched_at IS NOT NULL OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            RAISE EXCEPTION 'a DISPATCHING payout moves its permit and nothing else (P11-TSK-015)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'DISPATCHED' THEN
        -- Payments accepted the withdrawal: named by value, observed at once.
        IF NEW.withdrawal_id IS NULL OR NEW.not_dispatched_reason IS NOT NULL OR NEW.concluded_at IS NOT NULL
                OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a DISPATCHED payout names payments'' withdrawal and nothing concluded (P11-TSK-015)';
        END IF;
        NEW.dispatched_at := pg_catalog.statement_timestamp();
        NEW.next_attempt_at := pg_catalog.statement_timestamp();
        NEW.last_observed_at := OLD.last_observed_at;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHING' AND NEW.status = 'NOT_DISPATCHED' THEN
        -- Payments refused or could not route the withdrawal: the hold released beside this edge, interest from now.
        IF NEW.not_dispatched_reason IS NULL OR NEW.withdrawal_id IS NOT NULL OR NEW.dispatched_at IS NOT NULL THEN
            RAISE EXCEPTION 'a NOT_DISPATCHED payout names its reason and no withdrawal (P11-TSK-015)';
        END IF;
        NEW.next_attempt_at := OLD.next_attempt_at;
        NEW.last_observed_at := OLD.last_observed_at;
        -- P11-TSK-033: never before the payout's birth, whatever the clock (ADR-0063 decision 2).
        NEW.concluded_at := GREATEST(pg_catalog.statement_timestamp(), OLD.created_at);
        NEW.decided_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHED' AND NEW.status = 'DISPATCHED' THEN
        -- An observation of payments' undecided withdrawal (DISPATCHED or UNKNOWN there): the permit and the observation
        -- stamp move, nothing else - never a re-dispatch (INV-RAIL-02).
        IF NEW.not_dispatched_reason IS NOT NULL OR NEW.concluded_at IS NOT NULL OR NEW.decided_at IS NOT NULL
                OR (NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at
                    AND NEW.next_attempt_at < pg_catalog.statement_timestamp()) THEN
            RAISE EXCEPTION 'a DISPATCHED payout moves its permit and its observation and nothing else (P11-TSK-015)';
        END IF;
        IF NEW.last_observed_at IS DISTINCT FROM OLD.last_observed_at THEN
            NEW.last_observed_at := pg_catalog.statement_timestamp();
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISPATCHED' AND NEW.status IN ('PAID_OUT', 'FAILED') THEN
        -- Payments' terminal outcome, read: its conclusion instant held within the payout's life on the database clock
        -- (another process's clock may be seconds off - the accrual start must never precede the payout nor follow now).
        -- P11-TSK-033: "now" bounds payments' instant only; the payout's birth bounds it last, so on a database clock
        -- stepped back behind the birth the conclusion is the birth (ADR-0063 decision 2, credit V014's form).
        IF NEW.concluded_at IS NULL OR NEW.not_dispatched_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a concluded payout carries payments'' conclusion instant (P11-TSK-015)';
        END IF;
        -- P11-TSK-033: the birth's floor last - never before the payout, whatever the clock (ADR-0063 decision 2).
        NEW.concluded_at := GREATEST(LEAST(NEW.concluded_at, pg_catalog.statement_timestamp()), OLD.created_at);
        NEW.decided_at := pg_catalog.statement_timestamp();
        NEW.next_attempt_at := OLD.next_attempt_at;
        NEW.last_observed_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a payout moves PENDING -> DISPATCHING -> DISPATCHED -> PAID_OUT | FAILED, or DISPATCHING -> NOT_DISPATCHED; % -> % is not an edge (P11-TSK-015)', OLD.status, NEW.status;
END;
$$;

-- lending.loan_permits_only_machine_edges: V032's definition, 6 clock call(s) qualified.
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
        IF NOT FOUND OR offer.status <> 'OFFERED'
                OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a loan is born for its own party''s OFFERED, unexpired offer of the same product, on its decision (P11-TSK-012)';
        END IF;
        SELECT t.currency INTO offered_currency FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.offer_id;
        IF offered_currency IS DISTINCT FROM NEW.currency THEN
            RAISE EXCEPTION 'a loan is held in its offer''s currency (P11-TSK-012)';
        END IF;
        -- The expiry, judged last - after the offer's identity and its currency, so a breach of either is never
        -- answered as a lapse - and in its own SQLSTATE (P11-TSK-034): the acceptance judged the offer unexpired after
        -- L2 and then waited (credit's consumption, L6), so the database clock may cross expires_at before this
        -- statement - the customer's 409, not a breach.
        IF offer.expires_at <= pg_catalog.statement_timestamp() THEN
            RAISE EXCEPTION 'a loan is never born for an expired offer - it expires (P11-TSK-012, P11-TSK-034)'
                USING ERRCODE = 'LNEXP';
        END IF;
        NEW.opened_at := pg_catalog.statement_timestamp();
        -- Due for servicing at once - an account is claimed only while ACTIVE or CLOSING (P11-TSK-016).
        NEW.next_servicing_at := pg_catalog.statement_timestamp();
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
            NEW.closed_at := pg_catalog.statement_timestamp();
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
        NEW.closed_at := pg_catalog.statement_timestamp();
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
        NEW.closed_at := pg_catalog.statement_timestamp();
        RETURN NEW;
    END IF;
    -- The write-off (P14-TSK-021): ACTIVE or CLOSING -> WRITTEN_OFF, only beside its APPROVED decision born in this transaction;
    -- never closed - closed_at, the closure and cancellation reasons stay NULL, the permit unmoved: nothing was paid or
    -- forgiven, and nothing services the account again. Nothing leaves WRITTEN_OFF (below).
    IF OLD.status IN ('ACTIVE', 'CLOSING') AND NEW.status = 'WRITTEN_OFF' THEN
        IF NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL OR NEW.cancellation_reason IS NOT NULL
                OR NEW.next_servicing_at IS DISTINCT FROM OLD.next_servicing_at
                OR NOT EXISTS (SELECT 1 FROM lending.loan_write_off_decision d
                                WHERE d.loan_id = OLD.id AND d.outcome = 'APPROVED') THEN
            RAISE EXCEPTION 'a loan is WRITTEN_OFF only beside its approved write-off, and is never closed by it (P14-TSK-021, INV-LIFE-04)';
        END IF;
        RETURN NEW;
    END IF;
    -- Every other edge is never: out of CLOSED, CANCELLED and WRITTEN_OFF nothing, ever (INV-LIFE-04); CLOSING -> ACTIVE never (a closing
    -- line is never reopened - a new application); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never
    -- (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge (P11-TSK-026, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- lending.loan_terms_version_event_is_append_only: V003's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_terms_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan terms version event is append-only (P11-TSK-005, INV-HIST-01)';
    END IF;
    NEW.occurred_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_terms_version_permits_only_machine_edges: V033's definition, 5 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_terms_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_from IS NOT NULL OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a loan terms version is born PROPOSED and undecided: no version is activated by insert (P11-TSK-005)';
        END IF;
        NEW.proposed_at := pg_catalog.transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan terms version is never deleted (P11-TSK-005, INV-HIST-04)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan terms version''s identity is frozen (P11-TSK-005, INV-LND-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a loan terms version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P11-TSK-005)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        -- The Phase 11 gate (A2-F3): the instant the decision is written after the lock it waited on, never the
        -- transaction's start (V025's waiver and reversal decisions).
        NEW.decided_at := pg_catalog.clock_timestamp();
        -- P15-TSK-033 (PI-014): exactly where its predecessor ends - the product's highest-numbered RETIRED version, retired
        -- first in this transaction - chosen by row order, never by a stamp; the product's first version at the start.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(
                (SELECT v.effective_to FROM lending.loan_terms_version v
                  WHERE v.product = NEW.product AND v.status = 'RETIRED' AND v.version < NEW.version
                  ORDER BY v.version DESC LIMIT 1),
                pg_catalog.transaction_timestamp()) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P11-TSK-005)';
        END IF;
        -- Never at or before its own start, whatever the clock says (credit V014); written after the activation's lock on
        -- this row (FOR UPDATE, behind every origination's FOR SHARE pin), never the transaction's start, before the wait
        -- (the Phase 11 gate, A2-F3). P15-TSK-033 (PI-014): and never before one microsecond after the latest offer pinned to
        -- it - the clock can step back between that offer's stamp and this one.
        NEW.effective_to := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp(), OLD.effective_from + interval '1 microsecond',
                (SELECT max(o.offered_at) FROM lending.loan_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

-- lending.loan_write_off_decision_is_born_once: V032's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_write_off_decision_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    proposal lending.loan_write_off%ROWTYPE;
    account lending.loan%ROWTYPE;
    default_change lending.loan_condition_event%ROWTYPE;
    zone text;
    value_floor date;
    due_principal numeric;
    due_interest numeric;
    due_fees numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off decision is immutable from insert, for every role (P14-TSK-021, INV-HIST-01)';
    END IF;
    SELECT w.* INTO proposal FROM lending.loan_write_off w WHERE w.id = NEW.write_off_id;
    IF proposal.id IS NULL THEN
        RAISE EXCEPTION 'a write-off decision decides a write-off (P14-TSK-021)';
    END IF;
    -- (L3) The account, for every writer: everything below is judged under the lock every act on the account holds.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = proposal.loan_id FOR UPDATE;
    IF NEW.outcome = 'APPROVED' THEN
        IF account.status NOT IN ('ACTIVE', 'CLOSING') THEN
            RAISE EXCEPTION 'a write-off is approved only on an ACTIVE or CLOSING account (P14-TSK-021, INV-LIFE-04)';
        END IF;
        -- A condition that can leave and come back is compared by its revision: the same DEFAULT change, still set.
        SELECT e.* INTO default_change FROM lending.loan_condition_event e
         WHERE e.loan_id = proposal.loan_id AND e.kind = 'DEFAULT'
         ORDER BY e.business_date DESC LIMIT 1;
        IF default_change.id IS DISTINCT FROM proposal.default_event_id OR default_change.defaulted IS NOT TRUE THEN
            RAISE EXCEPTION 'a write-off is approved only while the DEFAULT change it stands on is the latest and still set (P14-TSK-021)';
        END IF;
        -- The agreement in force is the one it was proposed under: a restructuring accepted without re-scheduling arrears moves no
        -- journal line, so the revision cannot say so.
        IF proposal.agreement_version <> (SELECT max(a.version) FROM lending.loan_agreement a WHERE a.loan_id = proposal.loan_id) THEN
            RAISE EXCEPTION 'a write-off is approved only under the agreement version it was proposed on (P14-TSK-021, INV-LND-01)';
        END IF;
        -- What the billed items say is due is what the proposal named (the rows' side; the ledger's is the domain's under L3).
        SELECT coalesce(sum(lending.billing_component_billed(i.id, 'PRINCIPAL') - lending.billing_component_paid(i.id, 'PRINCIPAL')), 0),
               coalesce(sum(lending.billing_component_billed(i.id, 'INTEREST') - lending.billing_component_paid(i.id, 'INTEREST')), 0),
               coalesce(sum(lending.billing_component_billed(i.id, 'FEES') - lending.billing_component_paid(i.id, 'FEES')), 0)
          INTO due_principal, due_interest, due_fees
          FROM (SELECT b.id FROM lending.instalment_billing b WHERE b.loan_id = proposal.loan_id
                UNION ALL
                SELECT s.id FROM lending.credit_line_statement s WHERE s.loan_id = proposal.loan_id) i;
        IF due_principal <> proposal.principal_due_minor OR due_interest <> proposal.interest_due_minor
                OR due_fees <> proposal.fees_due_minor THEN
            RAISE EXCEPTION 'a write-off takes exactly what the billed items have due (P14-TSK-021, INV-LND-01)';
        END IF;
        -- The value date: never one the record has closed (the Phase 11 gate's R1), never after the clock.
        SELECT t.servicing_zone INTO zone
          FROM lending.loan_offer o
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE o.id = account.offer_id;
        value_floor := lending.principal_value_floor(proposal.loan_id);
        IF NEW.value_date < value_floor OR NEW.value_date > lending.business_date_latest(zone) THEN
            RAISE EXCEPTION 'a write-off is valued on a date the record leaves open, never after today (P14-TSK-021, ADR-0063 decision 2)';
        END IF;
    END IF;
    NEW.recorded_order := nextval('lending.record_order');
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- lending.loan_write_off_is_born_once: V032's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.loan_write_off_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    default_change lending.loan_condition_event%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a write-off proposal is a born-once fact (P14-TSK-021): its decision is loan_write_off_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a write-off is proposed only on an ACTIVE or CLOSING account (P14-TSK-021)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.agreement_version <> agreement.version OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a write-off names its account''s governing agreement version, in its currency and scale (P14-TSK-021)';
    END IF;
    -- The default it stands on is the account's latest DEFAULT change, and that change set the flag.
    SELECT e.* INTO default_change FROM lending.loan_condition_event e
     WHERE e.loan_id = NEW.loan_id AND e.kind = 'DEFAULT'
     ORDER BY e.business_date DESC LIMIT 1;
    IF default_change.id IS DISTINCT FROM NEW.default_event_id OR default_change.defaulted IS NOT TRUE THEN
        RAISE EXCEPTION 'a write-off stands on its account''s latest DEFAULT change, which set the flag (P14-TSK-021)';
    END IF;
    -- One proposal awaiting a decision per account: a second is lending.ProposalPending.
    IF EXISTS (SELECT 1 FROM lending.loan_write_off w
                WHERE w.loan_id = NEW.loan_id
                  AND NOT EXISTS (SELECT 1 FROM lending.loan_write_off_decision d WHERE d.write_off_id = w.id)) THEN
        RAISE EXCEPTION 'a write-off of this account already awaits a decision (P14-TSK-021)';
    END IF;
    NEW.proposed_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- lending.payoff_execution_is_born_once: V028's definition, 1 clock call(s) qualified.
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
            OR NEW.business_date < lending.business_date_earliest(zone)
            OR NEW.business_date > lending.business_date_latest(zone) THEN
        RAISE EXCEPTION 'a payoff quote is executed only on its good-through date, on the database clock in its pinned zone (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.executed_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.payoff_quote_is_born_once: V028's definition, 1 clock call(s) qualified.
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
    IF NEW.business_date < lending.business_date_earliest(terms.servicing_zone)
            OR NEW.business_date > lending.business_date_latest(terms.servicing_zone)
            OR NEW.good_through > NEW.business_date + terms.payoff_horizon_days THEN
        RAISE EXCEPTION 'a payoff quote is made on the business date of the database clock in its pinned zone, good through at most the pinned horizon after it (P11-TSK-026, INV-LND-10)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.repayment_is_born_once: V030's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.repayment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    zone text;
    agreement lending.loan_agreement%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    -- (L3) A prepayment's account, for every writer (P11-TSK-028): it re-schedules the loan, so it is judged under the lock
    -- that serialises every act on the account - re-entrant for the domain, which holds it already.
    IF NEW.kind = 'PREPAYMENT' THEN
        PERFORM 1 FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    END IF;
    -- An ACTIVE instalment loan, or a credit line ACTIVE or CLOSING - the customer's repayment, its automatic collection or
    -- its payoff's one repayment (P11-TSK-026) - and its pinned servicing zone. A CLOSED or CANCELLED account takes no money
    -- (INV-LND-08). A prepayment is an instalment loan's alone: a line's repayment already pays its principal down.
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
    IF NEW.kind = 'PREPAYMENT' THEN
        -- A scheduled loan only: one awaiting its payout's outcome has no schedule to recalculate.
        IF NOT EXISTS (SELECT 1 FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id) THEN
            RAISE EXCEPTION 'a prepayment re-schedules a scheduled instalment loan (P11-TSK-028)';
        END IF;
        -- ARREARS FIRST: nothing billed may be owing - past due or due today - before principal is paid early.
        IF EXISTS (SELECT 1 FROM lending.instalment_billing b
                    CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)
                    WHERE b.loan_id = NEW.loan_id
                      AND lending.billing_component_paid(b.id, c.component)
                          < lending.billing_component_billed(b.id, c.component)) THEN
            RAISE EXCEPTION 'a prepayment waits until nothing billed is owing - arrears first (P11-TSK-028, lending.ArrearsFirst)';
        END IF;
        -- PARTIAL: strictly less than the principal not yet billed - the whole of it is the payoff's.
        IF NEW.amount_minor >= lending.principal_not_yet_billed(NEW.loan_id) THEN
            RAISE EXCEPTION 'a prepayment pays less than the principal not yet billed - the whole is the payoff''s (P11-TSK-028, INV-LND-03)';
        END IF;
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
    -- by the repayment after its locks, so between the transaction's start and this statement - P11-TSK-036: in whichever
    -- order the clock read the two, which a clock stepped back across a midnight reverses (business_date_earliest/_latest).
    -- The Phase 11 gate (R1): never a date the loan's record has closed - the floor outranks the clock (ADR-0063 decision
    -- 2), so a step back across a midnight dates the repayment on the floor, a date the clock showed before it stepped.
    value_floor := lending.principal_value_floor(NEW.loan_id);
    IF NEW.value_date < lending.business_date_earliest(zone)
            OR NEW.value_date > GREATEST(lending.business_date_latest(zone), value_floor) THEN
        RAISE EXCEPTION 'a repayment''s value date is the business date of the database clock in its pinned zone (P11-TSK-018, INV-LND-10)';
    END IF;
    IF NEW.value_date < value_floor THEN
        RAISE EXCEPTION 'a repayment is never value-dated a business date its loan''s record has closed - accrued or stated (the Phase 11 gate R1, INV-LND-02, ADR-0063 decision 2)';
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.repayment_reversal_permits_only_machine_edges: V031's definition, 2 clock call(s) qualified.
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
        NEW.proposed_at := pg_catalog.transaction_timestamp();
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
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- lending.repayment_schedule_is_born_once: V028's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.repayment_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    agreement lending.loan_agreement%ROWTYPE;
    proposal lending.loan_amendment%ROWTYPE;
    previous lending.repayment_schedule%ROWTYPE;
    unbilled integer;
    started date;
    zone text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a schedule version is immutable from insert, for every role (P11-TSK-014, INV-LND-03)';
    END IF;
    SELECT s.start_date INTO started FROM lending.accrual_start s WHERE s.loan_id = NEW.loan_id;
    IF NEW.schedule_version = 1 THEN
        -- Version 1: the loan's agreement version 1, by its pinned engine, from the loan's accrual start, while ACTIVE.
        SELECT a.* INTO agreement
          FROM lending.loan_agreement a JOIN lending.loan l ON l.id = a.loan_id
         WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = 1
           AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        IF agreement.id IS NULL OR started IS NULL OR NEW.agreement_version <> 1
                OR NEW.schedule_engine_version IS DISTINCT FROM agreement.schedule_engine_version
                OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
                OR NEW.principal_minor <> agreement.amount_minor
                OR NEW.term_months IS DISTINCT FROM agreement.term_months
                OR NEW.intended_day <> agreement.repayment_day
                OR NEW.start_date <> started THEN
            RAISE EXCEPTION 'schedule version 1 projects its loan''s agreement version 1 by the pinned engine from the accrual start (P11-TSK-014, ADR-0093)';
        END IF;
    ELSE
        -- Version n+1 (P11-TSK-027, P11-TSK-028): the loan's latest agreement version - an amendment's or a prepayment's -
        -- by its pinned engine, the next schedule version, while ACTIVE.
        SELECT a.* INTO agreement
          FROM lending.loan_agreement a JOIN lending.loan l ON l.id = a.loan_id
         WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
           AND a.version > 1 AND (a.amendment_id IS NOT NULL OR a.prepayment_id IS NOT NULL)
           AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id)
           AND l.kind = 'INSTALMENT' AND l.status = 'ACTIVE';
        SELECT t.servicing_zone INTO zone FROM lending.loan_terms t WHERE t.terms_version_id = agreement.terms_version_id;
        -- The schedule it supersedes - the latest before this one - and what of it is left unbilled.
        SELECT s.* INTO previous FROM lending.repayment_schedule s WHERE s.loan_id = NEW.loan_id
         ORDER BY s.schedule_version DESC LIMIT 1;
        unbilled := lending.unbilled_instalments(NEW.loan_id);
        IF agreement.id IS NULL OR started IS NULL OR previous.id IS NULL
                OR NEW.schedule_version <> previous.schedule_version + 1
                OR NEW.schedule_engine_version IS DISTINCT FROM agreement.schedule_engine_version
                OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
                OR NEW.intended_day <> agreement.repayment_day THEN
            RAISE EXCEPTION 'a later schedule version projects its loan''s latest agreement version - an amendment''s or a prepayment''s - by the pinned engine, as the next version (P11-TSK-027, P11-TSK-028, ADR-0093)';
        END IF;
        IF agreement.amendment_id IS NOT NULL THEN
            -- An amendment's: over exactly the amendment's remaining instalments.
            SELECT m.* INTO proposal FROM lending.loan_amendment m WHERE m.id = agreement.amendment_id;
            IF proposal.id IS NULL OR NEW.term_months <> proposal.remaining_instalments THEN
                RAISE EXCEPTION 'an amendment''s schedule version runs over the amendment''s remaining instalments (P11-TSK-027, ADR-0093)';
            END IF;
        ELSIF NEW.term_months > unbilled
                OR NOT (NEW.instalment_minor = previous.instalment_minor
                        OR (NEW.term_months = unbilled AND NEW.instalment_minor > previous.instalment_minor)) THEN
            -- A prepayment's (P11-TSK-028): THE SAME LEVEL INSTALMENT over a term never longer than what the superseded
            -- schedule had left - the least such term, which the schedule's own shape (every period but the last closing
            -- positive, the last never above the instalment) makes it. Only when that instalment cannot amortise the
            -- principal within the remaining term (late payments left more outstanding than it can carry) is the term kept
            -- and the instalment the engine's least - necessarily above the old one.
            RAISE EXCEPTION 'a prepayment''s schedule version keeps the level instalment over a term never longer than the remaining one (P11-TSK-028, ADR-0093)';
        END IF;
        -- PRINCIPAL CONSERVED ACROSS VERSIONS (INV-LND-03): exactly the principal outstanding the rows explain - agreement
        -- version 1's principal less the principal billed net of re-scheduling and less what was prepaid - never the
        -- original principal, never more.
        IF NEW.principal_minor <> lending.principal_not_yet_billed(NEW.loan_id) THEN
            RAISE EXCEPTION 'a later schedule version amortises exactly the principal outstanding not yet billed (P11-TSK-027, P11-TSK-028, INV-LND-03)';
        END IF;
        -- From the open period's start: on or after the accrual start and every billed due date, never after today.
        IF NEW.start_date < started
                OR NEW.start_date > lending.business_date_latest(zone)
                OR EXISTS (SELECT 1 FROM lending.instalment_billing b
                            WHERE b.loan_id = NEW.loan_id AND b.due_date > NEW.start_date) THEN
            RAISE EXCEPTION 'a later schedule version starts at the open period''s start - after every billed due date, never after today (P11-TSK-027)';
        END IF;
        -- THE BILLING FIRST, for every writer (P11-TSK-028): no instalment of the superseded schedule already due - its due
        -- date come by the transaction's business date in the pinned zone - is superseded unbilled, so neither it nor the
        -- interest its period accrued is stranded (PHASE_11_PLAN.md section 12.2 step 7). The transaction's start, never a
        -- later statement's: the domain bills through a date read after its lock, never earlier, so this never refuses it.
        -- P11-TSK-036: the earliest business date this transaction read - its start's, or this statement's when a database
        -- clock stepped back across a midnight put the domain's read on the day before its start.
        IF EXISTS (SELECT 1 FROM lending.loan_instalment i
                    WHERE i.schedule_id = previous.id
                      AND i.due_date <= lending.business_date_earliest(zone)
                      AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)) THEN
            RAISE EXCEPTION 'a schedule is superseded only once every instalment of it already due is billed - the billing first (P11-TSK-028, INV-LND-03)';
        END IF;
    END IF;
    NEW.created_at := pg_catalog.statement_timestamp();
    RETURN NEW;
END;
$$;

-- lending.waiver_decision_is_born_once: V029's definition, 1 clock call(s) qualified.
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
            -- V029 (R3): and by what of the fee was never forgiven - its assessment less what was waived or refunded of it.
            -- A refund forgave the fee already; a reversal re-opening it on the item does not make it forgivable twice.
            room := least(
                lending.billing_component_billed(proposal.due_item_id, 'FEES')
                    - lending.billing_component_paid(proposal.due_item_id, 'FEES'),
                (SELECT f.amount_minor FROM lending.fee_assessment f WHERE f.id = proposal.fee_assessment_id)
                    - (SELECT coalesce(sum(w.amount_minor), 0) FROM lending.waiver w
                         JOIN lending.waiver_decision d ON d.waiver_id = w.id
                        WHERE w.fee_assessment_id = proposal.fee_assessment_id AND w.kind IN ('FEE', 'FEE_REFUND')
                          AND d.outcome = 'APPROVED'));
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
    NEW.decided_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END;
$$;

-- lending.waiver_is_born_once: V021's definition, 1 clock call(s) qualified.
CREATE OR REPLACE FUNCTION lending.waiver_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    assessment lending.fee_assessment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a waiver is a born-once fact (P11-TSK-025): its decision is waiver_decision';
    END IF;
    -- (L3) The account, for every writer.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id FOR UPDATE;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a waiver is proposed only on an ACTIVE or CLOSING account (P11-TSK-025, INV-LND-08)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.loan_id = NEW.loan_id
       AND a.version = (SELECT max(x.version) FROM lending.loan_agreement x WHERE x.loan_id = NEW.loan_id);
    IF NEW.agreement_version <> agreement.version OR NEW.currency <> agreement.currency
            OR NEW.scale <> agreement.scale THEN
        RAISE EXCEPTION 'a waiver names its account''s governing agreement version, in its currency and scale (P11-TSK-025)';
    END IF;
    IF NEW.kind = 'INTEREST' THEN
        IF lending.billed_item_loan(NEW.due_item_id) IS DISTINCT FROM NEW.loan_id THEN
            RAISE EXCEPTION 'an interest waiver names a billed item of its own account (P11-TSK-025)';
        END IF;
    ELSE
        SELECT f.* INTO assessment FROM lending.fee_assessment f WHERE f.id = NEW.fee_assessment_id;
        IF assessment.id IS NULL OR assessment.loan_id <> NEW.loan_id OR assessment.due_item_id <> NEW.due_item_id THEN
            RAISE EXCEPTION 'a fee waiver names an assessment of its own account, on its item (P11-TSK-025)';
        END IF;
    END IF;
    -- One proposal awaiting a decision per kind and item: a second is lending.ProposalPending.
    IF EXISTS (SELECT 1 FROM lending.waiver w
                WHERE w.loan_id = NEW.loan_id AND w.kind = NEW.kind AND w.due_item_id = NEW.due_item_id
                  AND NOT EXISTS (SELECT 1 FROM lending.waiver_decision d WHERE d.waiver_id = w.id)) THEN
        RAISE EXCEPTION 'a waiver of this kind on this item already awaits a decision (P11-TSK-025)';
    END IF;
    NEW.proposed_at := pg_catalog.transaction_timestamp();
    RETURN NEW;
END;
$$;
