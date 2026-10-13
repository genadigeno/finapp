-- Credit line statements and the minimum payment (P11-TSK-022; PHASE_11_PLAN.md sections 7.1 T-svc, 7.2, 12.5, 12.6 and 12.8
-- rules 3, 5 and 6; ADR-0100 point 6, ADR-0095 points 2, 5 and 6, ADR-0094 points 6 and 8; A23, A24; INV-LND-01, INV-LND-04,
-- INV-LND-09, INV-LND-10, INV-IDEM-02). The number is V017 - the next free lending version (V015 is P11-TSK-019's, V016
-- P11-TSK-020's); the backlog's "V015" is stale.
--
-- The servicing step (T-svc), under the line's row lock (L3), after its accrual: every statement cycle whose statement date
-- has come on the database clock in the pinned zone - and every date of which has been accrued (or found with no principal
-- to accrue on) - is stated, oldest first, in the step's one transaction, by STATEMENT_ENGINE_V1:
--
--     credit_line_statement      - the line's billed item: the cycle, the interest it bills (exactly the sum of the cycle's
--                                  accrued posted_minor - interest actually accrued), the fees it carries (zero until
--                                  P11-TSK-025 assesses any), the minimum payment's principal part (A24, on the drawn
--                                  principal at the cycle's close, never beyond the principal not yet due), the minimum
--                                  payment, its due date (the statement date plus the pinned payment-due days), the agreement
--                                  and engine versions, and posting rule 3's journal entry by value - null for a statement of
--                                  nothing. ONLY what it billed: opening and closing balances are derived from the ledger,
--                                  never stored (ADR-0100 point 6).
--     the ledger entry (rule 3)  - DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED, DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL
--                                  under lending.statement:<line>:<statement date> (the statement's natural key, so the ledger
--                                  key is an arbiter of its own), dated the business date of the transaction (no back-valuing).
--     credit_balance_application - P11-TSK-017's rule 6, unchanged: money already held applied over the line's billed items at
--                                  the statement, born once per statement (UNIQUE (billing_id) - the billed item's identity).
--     collection_attempt         - P11-TSK-019's auto-collection, extended: on the statement's due date (and each later
--                                  business date while it stays due) what the statement still has due is collected from the
--                                  wallet through the repayment's one posting half, kind AUTO_COLLECTION.
--
-- A statement is a BILLED ITEM beside an instalment's billing: every table that names a billed item (the credit-balance
-- application and its rows, the repayment's rows, the collection attempt) now names either. A foreign key names one table,
-- so the four foreign keys to instalment_billing give way to the born-once triggers' check that the item is a billing or a
-- statement OF THE ROW'S OWN LOAN - stricter than the key was (the key never checked the loan), and as permanent: both parents
-- are INSERT-only and never deleted or truncated, for every role, so a reference valid at insert stays valid for ever.
-- lending.billing_component_billed reads a statement's figures; lending.billing_component_paid is keyed by the item and
-- unchanged, so V012's, V014's and V015's never-over-paid rules cover statements as they stand.
--
-- Held for EVERY writer: credit_line_statement UNIQUE (loan_id, cycle_end) - once per line and cycle - UNIQUE (loan_id,
-- due_date) - one billed item per due date, the allocation engine's total order - and UNIQUE (journal_entry_id); a statement
-- only for an ACTIVE or CLOSING line, under its governing agreement version, its currency, scale and pinned accrual and
-- statement engines, never before its statement date has come in the pinned zone (INV-LND-10), the cycles contiguous from the
-- line's opening business date and each ending on the statement day, oldest first, its due date the pinned payment-due days
-- after it, its interest the sum of its cycle's accruals, the minimum exactly interest + fees + principal; the accrual's
-- trigger, replaced, refuses a date inside a cycle already stated - so stated interest is accrued interest for ever
-- (INV-LND-01). A line's repayment only as an automatic collection (its customer repayment is P11-TSK-023's); a collection
-- attempt on a line's statement as on an instalment's billing. The new table INSERT-only and never truncated.

-- ---------------------------------------------------------------------------------------------
-- The statement.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.credit_line_statement (
    id                          uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    cycle_start                 date        NOT NULL,
    cycle_end                   date        NOT NULL,
    due_date                    date        NOT NULL,
    currency                    char(3)     NOT NULL,
    scale                       smallint    NOT NULL,
    interest_billed_minor       bigint      NOT NULL,
    fees_carried_minor          bigint      NOT NULL,
    principal_billed_minor      bigint      NOT NULL,
    minimum_payment_minor       bigint      NOT NULL,
    agreement_id                uuid        NOT NULL,
    agreement_version           integer     NOT NULL,
    statement_engine_version    smallint    NOT NULL,
    accrual_engine_version      smallint    NOT NULL,
    journal_entry_id            uuid,
    created_at                  timestamptz NOT NULL,
    correlation_id              text        NOT NULL,
    CONSTRAINT credit_line_statement_pk PRIMARY KEY (id),
    -- Born once per line and cycle (the backlog's arbiter beneath L3 and the ledger key).
    CONSTRAINT credit_line_statement_cycle_unique UNIQUE (loan_id, cycle_end),
    -- One billed item per due date of a line: oldest-first allocation is a total order (DueState).
    CONSTRAINT credit_line_statement_due_date_unique UNIQUE (loan_id, due_date),
    -- One statement per journal entry: the ledger key gives one entry per statement, this one row per entry.
    CONSTRAINT credit_line_statement_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT credit_line_statement_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT credit_line_statement_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT credit_line_statement_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A cycle ends after it starts, at most a month after (StatementEngineV1's cycle); the payment falls due after it.
    CONSTRAINT credit_line_statement_cycle_shape CHECK (
        cycle_end > cycle_start AND cycle_start >= (cycle_end - interval '1 month')::date AND due_date > cycle_end),
    CONSTRAINT credit_line_statement_amounts_shape CHECK (
        interest_billed_minor >= 0 AND fees_carried_minor >= 0 AND principal_billed_minor >= 0),
    -- No fee is assessed before P11-TSK-025, which widens this check when it lands.
    CONSTRAINT credit_line_statement_no_fee_yet CHECK (fees_carried_minor = 0),
    -- A24: the minimum payment is exactly the interest billed, the fees carried and the principal billed.
    CONSTRAINT credit_line_statement_minimum_is_its_parts CHECK (
        minimum_payment_minor = interest_billed_minor + fees_carried_minor + principal_billed_minor),
    -- An entry exactly when something is billed - a statement of nothing writes the row and no entry.
    CONSTRAINT credit_line_statement_entry_when_billed CHECK (
        (interest_billed_minor + principal_billed_minor > 0) = (journal_entry_id IS NOT NULL)),
    CONSTRAINT credit_line_statement_versions_numbered CHECK (
        agreement_version >= 1 AND statement_engine_version >= 1 AND accrual_engine_version >= 1),
    CONSTRAINT credit_line_statement_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.credit_line_statement IS
    'A credit line''s statement - its billed item (P11-TSK-022; ADR-0100 point 6, A23, A24, PHASE_11_PLAN.md section 12.6): one per line and cycle, the cycle ending on the agreement''s statement day - the interest the cycle billed (exactly the sum of its interest_accrual posted_minor), the fees it carries (zero until P11-TSK-025), the minimum payment''s principal part (min(principal not yet due, max(round_UP(ratio x drawn principal at the cycle''s close), floor - interest - fees, 0))), the minimum payment, its due date (the statement date plus the pinned payment-due days), the agreement and engine versions, and posting rule 3''s journal entry by value (DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED, DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL, key lending.statement:<line>:<statement date>) - null for a statement of nothing. Only what it billed: opening and closing balances are derived from the ledger, never stored. What is still due on it is billed less allocated (credit_balance_allocation, repayment_allocation less repayment_allocation_reversal). Explains LOAN_INTEREST_DUE and LOAN_PRINCIPAL_DUE of the line with the allocations (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

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
    -- Never early (INV-LND-10): the statement date has come in the pinned zone on the database clock.
    IF NEW.cycle_end > lending.business_date(statement_timestamp(), terms.servicing_zone) THEN
        RAISE EXCEPTION 'a statement is issued only once its statement date has come on the database clock in its pinned zone (P11-TSK-022, INV-LND-10)';
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

CREATE TRIGGER credit_line_statement_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.credit_line_statement
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_line_statement_is_born_once();

CREATE TRIGGER credit_line_statement_is_never_truncated
    BEFORE TRUNCATE ON lending.credit_line_statement
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- A billed item: an instalment's billing (V012) or a line's statement (here).
-- ---------------------------------------------------------------------------------------------

-- The loan whose billed item {item} is - null when it names none. Both parents are INSERT-only and never deleted, so a
-- reference this answers for at insert stays answered.
CREATE OR REPLACE FUNCTION lending.billed_item_loan(item uuid)
    RETURNS uuid
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce((SELECT b.loan_id FROM lending.instalment_billing b WHERE b.id = item),
                    (SELECT s.loan_id FROM lending.credit_line_statement s WHERE s.id = item))
$$;

-- The date a billed item falls due - an instalment's due date, a statement's payment due date; null when it names none.
CREATE OR REPLACE FUNCTION lending.billed_item_due_date(item uuid)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce((SELECT b.due_date FROM lending.instalment_billing b WHERE b.id = item),
                    (SELECT s.due_date FROM lending.credit_line_statement s WHERE s.id = item))
$$;

-- What a billed item's component billed: a billing's interest and principal (V012), a statement's interest and principal;
-- no fee is billed by either (a late fee is P11-TSK-025's own assessment on the item, which joins here when it lands; a
-- statement's carried fees are already due on the item they were assessed on).
CREATE OR REPLACE FUNCTION lending.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(
        (SELECT CASE billed_component
                    WHEN 'INTEREST' THEN b.interest_due_minor
                    WHEN 'PRINCIPAL' THEN b.principal_due_minor
                    ELSE 0 END
           FROM lending.instalment_billing b WHERE b.id = billing),
        (SELECT CASE billed_component
                    WHEN 'INTEREST' THEN s.interest_billed_minor
                    WHEN 'PRINCIPAL' THEN s.principal_billed_minor
                    ELSE 0 END
           FROM lending.credit_line_statement s WHERE s.id = billing))
$$;

-- The four foreign keys to instalment_billing give way to the born-once triggers below: a billed item is a billing or a
-- statement, of the row's own loan.
ALTER TABLE lending.credit_balance_application DROP CONSTRAINT credit_balance_application_billing_fk;
ALTER TABLE lending.credit_balance_allocation DROP CONSTRAINT credit_balance_allocation_billing_fk;
ALTER TABLE lending.repayment_allocation DROP CONSTRAINT repayment_allocation_billing_fk;
ALTER TABLE lending.collection_attempt DROP CONSTRAINT collection_attempt_billing_fk;

-- ---------------------------------------------------------------------------------------------
-- V012's credit-balance application triggers, replaced: at its own loan's billed item - a billing or a statement.
-- ---------------------------------------------------------------------------------------------
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
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status NOT IN ('ACTIVE', 'CLOSING') THEN
        RAISE EXCEPTION 'a credit balance is applied only on an ACTIVE or CLOSING account (P11-TSK-017)';
    END IF;
    -- At its own loan's billed item, in its transaction's servicing step: the billing or the statement is the loan's.
    IF lending.billed_item_loan(NEW.billing_id) IS DISTINCT FROM NEW.loan_id THEN
        RAISE EXCEPTION 'a credit-balance application is born at its own loan''s billing or statement (P11-TSK-017, P11-TSK-022)';
    END IF;
    SELECT a.* INTO agreement FROM lending.loan_agreement a
     WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version;
    IF agreement.id IS NULL OR NEW.currency <> agreement.currency OR NEW.scale <> agreement.scale
            OR NEW.allocation_engine_version <> agreement.allocation_engine_version THEN
        RAISE EXCEPTION 'an application names its account''s agreement version, its currency and scale and its pinned allocation engine (P11-TSK-017, INV-LND-09)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lending.credit_balance_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance allocation is immutable from insert, for every role (P11-TSK-017, INV-LND-04)';
    END IF;
    -- A line of an application, paying a billed item (a billing or a statement) of the application's own loan, in its
    -- currency and scale.
    IF NOT EXISTS (SELECT 1 FROM lending.credit_balance_application c
                    WHERE c.id = NEW.application_id AND c.currency = NEW.currency AND c.scale = NEW.scale
                      AND lending.billed_item_loan(NEW.billing_id) = c.loan_id) THEN
        RAISE EXCEPTION 'a credit-balance allocation pays a billed item of its application''s own loan, in its currency and scale (P11-TSK-017, P11-TSK-022)';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V014's repayment triggers, replaced: a line's repayment is admitted as an automatic collection of its statement (its
-- customer repayment stays P11-TSK-023's); a repayment's rows pay a billed item - a billing or a statement - of its loan.
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
    -- An ACTIVE instalment loan - or an ACTIVE line's automatic collection of its statement (P11-TSK-022) - and its pinned
    -- servicing zone. A CLOSED or CANCELLED account takes no money (INV-LND-08); a line's customer repayment is
    -- P11-TSK-023's, which replaces this function.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    SELECT t.servicing_zone INTO zone
      FROM lending.loan l
      JOIN lending.loan_offer o ON o.id = l.offer_id
      JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
     WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.status <> 'ACTIVE' OR zone IS NULL
            OR NOT (account.kind = 'INSTALMENT' OR (account.kind = 'REVOLVING' AND NEW.kind = 'AUTO_COLLECTION')) THEN
        RAISE EXCEPTION 'a repayment is received only by an ACTIVE instalment loan, or as an ACTIVE line''s automatic collection (P11-TSK-018, P11-TSK-022, INV-LND-08)';
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

CREATE OR REPLACE FUNCTION lending.repayment_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    repayment lending.repayment%ROWTYPE;
    loan_kind text;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a repayment allocation is immutable from insert, for every role (P11-TSK-018, INV-LND-04)';
    END IF;
    SELECT r.* INTO repayment FROM lending.repayment r WHERE r.id = NEW.repayment_id;
    IF repayment.id IS NULL OR NEW.currency <> repayment.currency OR NEW.scale <> repayment.scale THEN
        RAISE EXCEPTION 'a repayment allocation is a line of its repayment, in its currency and scale (P11-TSK-018)';
    END IF;
    -- A billed item - a billing or a statement - of the repayment's own loan.
    IF NEW.target = 'DUE_ITEM' AND lending.billed_item_loan(NEW.billing_id) IS DISTINCT FROM repayment.loan_id THEN
        RAISE EXCEPTION 'a repayment allocation pays a billed item of its repayment''s own loan (P11-TSK-018, P11-TSK-022)';
    END IF;
    -- An instalment loan holds the remainder as credit (HOLD_AS_CREDIT, ADR-0095 point 2): an overpayment never reduces its
    -- principal - a prepayment is its own command (P11-TSK-028).
    SELECT l.kind INTO loan_kind FROM lending.loan l WHERE l.id = repayment.loan_id;
    IF NEW.target = 'NOT_DUE_PRINCIPAL' AND loan_kind = 'INSTALMENT' THEN
        RAISE EXCEPTION 'an instalment loan''s repayment never pays principal not yet due (HOLD_AS_CREDIT, P11-TSK-018, ADR-0095)';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- V015's collection attempt trigger, replaced: an ACTIVE line's statement is collected as an instalment's billing is.
-- ---------------------------------------------------------------------------------------------
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
    -- An ACTIVE instalment loan or credit line (a closed or cancelled account takes no money, INV-LND-08) - or an instalment
    -- loan this attempt's own collection has just closed: the repayment closes the loan in its transaction (P11-TSK-018)
    -- before the attempt naming it is recorded. A CLOSING line's collection is P11-TSK-023's, beside its closure.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT (account.status = 'ACTIVE'
                    OR (account.kind = 'INSTALMENT' AND account.status = 'CLOSED' AND NEW.repayment_id IS NOT NULL
                        AND EXISTS (SELECT 1 FROM lending.loan_closure c
                                     WHERE c.loan_id = NEW.loan_id AND c.repayment_id = NEW.repayment_id))) THEN
        RAISE EXCEPTION 'a collection is attempted only for an ACTIVE loan or credit line (P11-TSK-019, P11-TSK-022, INV-LND-08)';
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
    IF NEW.attempt_date < lending.business_date(transaction_timestamp(), terms.servicing_zone)
            OR NEW.attempt_date > lending.business_date(statement_timestamp(), terms.servicing_zone) THEN
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
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The accrual's trigger, replaced: V012's rules, plus no accrual into a cycle already stated.
-- ---------------------------------------------------------------------------------------------
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
    IF NEW.accrual_date >= lending.business_date(statement_timestamp(), zone) THEN
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
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the statement SELECT and INSERT only. Never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.credit_line_statement TO finapp_app;
