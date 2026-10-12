-- Instalment billing and credit-balance application (P11-TSK-017; PHASE_11_PLAN.md sections 7.1 T-svc, 7.2, 12.2 step 5,
-- 12.3, 12.5 and 12.8 rules 3 and 6; ADR-0094 point 6, ADR-0095 point 5; INV-LND-01, INV-LND-03, INV-LND-04, INV-LND-10,
-- INV-IDEM-02). The number is V012: lending V011 is left free on purpose (the owner's separate servicing-zone fix) and V013
-- is P11-TSK-021's, built in parallel.
--
-- The servicing step (T-svc), under the loan's row lock (L3), after its accrual: every instalment of the latest schedule
-- version whose due date has come on the database clock in the pinned zone - and whose period's accruals all exist - is
-- billed, oldest first, in the step's one transaction:
--
--     instalment_billing        - the billed item: the instalment it bills (or, after the final due date, the post-maturity
--                                 period of the monthly calendar continued - interest only), its period, the interest due
--                                 (exactly the sum of the period's accrued posted_minor - interest actually accrued, never
--                                 the projection), the principal due (max(0, A - interest due), never beyond the principal
--                                 not yet due; the final instalment all of it), the agreement, schedule and engine versions,
--                                 and posting rule 3's journal entry by value - null for a billing of nothing.
--     the ledger entry (rule 3) - DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED, DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL
--                                 under lending.billing:<instalment> (a post-maturity period: lending.billing:<loan>:<due
--                                 date>), dated the business date of the transaction (ADR-0094 point 8: no back-valuing).
--     credit_balance_application - money already held (LOAN_CREDIT_BALANCE) applied by ALLOCATION_ENGINE_V1 over the billed
--     credit_balance_allocation    items, oldest first: the application, its per-item, per-component rows and posting rule
--                                 6 (DR LOAN_CREDIT_BALANCE / CR <due accounts>) under lending.credit-application:<billing>.
--
-- Held for EVERY writer: instalment_billing UNIQUE (instalment_id), UNIQUE (loan_id, due_date) - one billed item per due
-- date, the allocation engine's total order - and UNIQUE (journal_entry_id); a billing only for an ACTIVE or CLOSING
-- instalment loan, of its latest schedule version, never before its due date has come in the pinned zone (INV-LND-10),
-- oldest first, its interest the sum of its period's accruals, the principal billed over the loan's life never beyond its
-- principal, a post-maturity billing interest only; and the accrual's trigger, replaced, refuses a date inside a period
-- already billed - so billed interest is accrued interest for ever (INV-LND-01). The application UNIQUE (billing_id) and
-- UNIQUE (journal_entry_id); its rows summing to it and no billed component ever paid beyond what it billed, at commit
-- (INV-LND-04). Every new table INSERT-only and never truncated.

-- ---------------------------------------------------------------------------------------------
-- The billed item.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.instalment_billing (
    id                        uuid        NOT NULL,
    loan_id                   uuid        NOT NULL,
    kind                      text        NOT NULL,
    schedule_id               uuid        NOT NULL,
    schedule_version          integer     NOT NULL,
    instalment_id             uuid,
    sequence                  smallint    NOT NULL,
    period_start              date        NOT NULL,
    due_date                  date        NOT NULL,
    currency                  char(3)     NOT NULL,
    scale                     smallint    NOT NULL,
    interest_due_minor        bigint      NOT NULL,
    principal_due_minor       bigint      NOT NULL,
    agreement_id              uuid        NOT NULL,
    agreement_version         integer     NOT NULL,
    schedule_engine_version   smallint    NOT NULL,
    accrual_engine_version    smallint    NOT NULL,
    journal_entry_id          uuid,
    created_at                timestamptz NOT NULL,
    correlation_id            text        NOT NULL,
    CONSTRAINT instalment_billing_pk PRIMARY KEY (id),
    -- Born once per instalment (the backlog's arbiter beneath L3 and the ledger key).
    CONSTRAINT instalment_billing_instalment_unique UNIQUE (instalment_id),
    -- One billed item per due date of a loan: oldest-first allocation is a total order (DueState), and a post-maturity
    -- period - which has no instalment row - is born once too.
    CONSTRAINT instalment_billing_due_date_unique UNIQUE (loan_id, due_date),
    -- One billing per journal entry: the ledger key gives one entry per billed item, this one row per entry.
    CONSTRAINT instalment_billing_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT instalment_billing_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT instalment_billing_schedule_fk FOREIGN KEY (schedule_id) REFERENCES lending.repayment_schedule (id),
    CONSTRAINT instalment_billing_instalment_fk FOREIGN KEY (instalment_id) REFERENCES lending.loan_instalment (id),
    CONSTRAINT instalment_billing_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT instalment_billing_kind_is_known CHECK (kind IN ('INSTALMENT', 'POST_MATURITY')),
    -- An instalment's billing names it; a post-maturity period has no instalment row and bills interest alone.
    CONSTRAINT instalment_billing_kind_coherent CHECK (
        (kind = 'INSTALMENT' AND instalment_id IS NOT NULL)
        OR (kind = 'POST_MATURITY' AND instalment_id IS NULL AND principal_due_minor = 0)),
    CONSTRAINT instalment_billing_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT instalment_billing_period_shape CHECK (sequence >= 1 AND due_date > period_start),
    CONSTRAINT instalment_billing_amounts_shape CHECK (interest_due_minor >= 0 AND principal_due_minor >= 0),
    -- An entry exactly when something is billed - a billing of nothing writes the row and no entry.
    CONSTRAINT instalment_billing_entry_when_billed CHECK (
        (interest_due_minor + principal_due_minor > 0) = (journal_entry_id IS NOT NULL)),
    CONSTRAINT instalment_billing_versions_numbered CHECK (
        schedule_version >= 1 AND agreement_version >= 1 AND schedule_engine_version >= 1
        AND accrual_engine_version >= 1),
    CONSTRAINT instalment_billing_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.instalment_billing IS
    'A billed item of an instalment loan (P11-TSK-017; ADR-0094 point 6, PHASE_11_PLAN.md sections 12.2 step 5 and 12.3): an instalment of the latest schedule version - or, after the final due date, a post-maturity period of the monthly calendar continued (interest only) - made due on or after its due date: the interest due (exactly the sum of the period''s interest_accrual posted_minor - interest actually accrued, never the projection), the principal due (max(0, A - interest due), never beyond the principal not yet due; the final instalment all remaining principal), the agreement, schedule and engine versions, and posting rule 3''s journal entry by value (DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED, DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL, key lending.billing:<instalment>) - null for a billing of nothing. What is still due on it is billed less allocated (credit_balance_allocation; repayment_allocation from P11-TSK-018). Explains LOAN_INTEREST_DUE and LOAN_PRINCIPAL_DUE with the allocations (the subledger proof, P11-TSK-029). INSERT-only for every role. RESTRICTED-FINANCIAL.';

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
    -- Never early (INV-LND-10): the due date has come in the pinned zone on the database clock.
    IF NEW.due_date > lending.business_date(statement_timestamp(), zone) THEN
        RAISE EXCEPTION 'an instalment is billed only once its due date has come on the database clock in its pinned zone (P11-TSK-017, INV-LND-10)';
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

CREATE TRIGGER instalment_billing_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.instalment_billing
    FOR EACH ROW
    EXECUTE FUNCTION lending.instalment_billing_is_born_once();

CREATE TRIGGER instalment_billing_is_never_truncated
    BEFORE TRUNCATE ON lending.instalment_billing
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The accrual's trigger, replaced: V010's rules, plus no accrual into a period already billed.
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
-- The credit-balance application and its allocation rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.credit_balance_application (
    id                          uuid        NOT NULL,
    billing_id                  uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    currency                    char(3)     NOT NULL,
    scale                       smallint    NOT NULL,
    amount_minor                bigint      NOT NULL,
    agreement_id                uuid        NOT NULL,
    agreement_version           integer     NOT NULL,
    allocation_engine_version   smallint    NOT NULL,
    journal_entry_id            uuid        NOT NULL,
    created_at                  timestamptz NOT NULL,
    correlation_id              text        NOT NULL,
    CONSTRAINT credit_balance_application_pk PRIMARY KEY (id),
    -- Born once per billing (ADR-0095 point 5), beneath L3 and the ledger key lending.credit-application:<billing>.
    CONSTRAINT credit_balance_application_billing_unique UNIQUE (billing_id),
    CONSTRAINT credit_balance_application_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT credit_balance_application_billing_fk FOREIGN KEY (billing_id) REFERENCES lending.instalment_billing (id),
    CONSTRAINT credit_balance_application_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT credit_balance_application_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT credit_balance_application_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Something applied: a credit balance that meets nothing due writes no application.
    CONSTRAINT credit_balance_application_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT credit_balance_application_versions_numbered CHECK (
        agreement_version >= 1 AND allocation_engine_version >= 1),
    CONSTRAINT credit_balance_application_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.credit_balance_application IS
    'Money already held applied at a billing (P11-TSK-017; ADR-0095 point 5, PHASE_11_PLAN.md section 12.8 rule 6): what of the loan''s LOAN_CREDIT_BALANCE ALLOCATION_ENGINE_V1 applied over the billed items, oldest first, in the billing''s transaction - the amount (its credit_balance_allocation rows sum to it at commit), the agreement and allocation engine versions, and posting rule 6''s journal entry by value (DR LOAN_CREDIT_BALANCE / CR <due accounts>, key lending.credit-application:<billing>). At most one per billing. INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE TABLE lending.credit_balance_allocation (
    id               uuid     NOT NULL,
    application_id   uuid     NOT NULL,
    ordinal          smallint NOT NULL,
    billing_id       uuid     NOT NULL,
    component        text     NOT NULL,
    currency         char(3)  NOT NULL,
    scale            smallint NOT NULL,
    amount_minor     bigint   NOT NULL,
    CONSTRAINT credit_balance_allocation_pk PRIMARY KEY (id),
    CONSTRAINT credit_balance_allocation_ordinal_unique UNIQUE (application_id, ordinal),
    -- One line per item and component (AllocationEngineV1: two lines never pay one component).
    CONSTRAINT credit_balance_allocation_component_unique UNIQUE (application_id, billing_id, component),
    CONSTRAINT credit_balance_allocation_application_fk FOREIGN KEY (application_id)
        REFERENCES lending.credit_balance_application (id),
    CONSTRAINT credit_balance_allocation_billing_fk FOREIGN KEY (billing_id) REFERENCES lending.instalment_billing (id),
    CONSTRAINT credit_balance_allocation_component_is_known CHECK (component IN ('FEES', 'INTEREST', 'PRINCIPAL')),
    CONSTRAINT credit_balance_allocation_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT credit_balance_allocation_shape CHECK (ordinal >= 1 AND amount_minor > 0)
);

CREATE INDEX credit_balance_allocation_by_billing ON lending.credit_balance_allocation (billing_id, component);

COMMENT ON TABLE lending.credit_balance_allocation IS
    'One line of a credit-balance application (P11-TSK-017; ADR-0095 point 1): what it paid of one billed item''s component (FEES, INTEREST or PRINCIPAL) - the per-item split rule 6''s entry aggregates per due account. INSERT-only for every role; with its application''s other rows it sums to the application, and no billed component is ever paid beyond what it billed, at commit (INV-LND-04). RESTRICTED-FINANCIAL.';

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
    -- At its own loan's billing, in its transaction's servicing step: the billing is the loan's.
    IF NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.id = NEW.billing_id AND b.loan_id = NEW.loan_id) THEN
        RAISE EXCEPTION 'a credit-balance application is born at its own loan''s billing (P11-TSK-017)';
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

CREATE TRIGGER credit_balance_application_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.credit_balance_application
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_balance_application_is_born_once();

CREATE TRIGGER credit_balance_application_is_never_truncated
    BEFORE TRUNCATE ON lending.credit_balance_application
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

CREATE OR REPLACE FUNCTION lending.credit_balance_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance allocation is immutable from insert, for every role (P11-TSK-017, INV-LND-04)';
    END IF;
    -- A line of an application, paying a billed item of the application's own loan, in its currency and scale.
    IF NOT EXISTS (SELECT 1 FROM lending.credit_balance_application c
                     JOIN lending.instalment_billing b ON b.loan_id = c.loan_id
                    WHERE c.id = NEW.application_id AND b.id = NEW.billing_id
                      AND c.currency = NEW.currency AND c.scale = NEW.scale) THEN
        RAISE EXCEPTION 'a credit-balance allocation pays a billed item of its application''s own loan, in its currency and scale (P11-TSK-017)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_balance_allocation_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.credit_balance_allocation
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_balance_allocation_is_born_once();

CREATE TRIGGER credit_balance_allocation_is_never_truncated
    BEFORE TRUNCATE ON lending.credit_balance_allocation
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- What a billed item's component has had paid - every allocation that pays a billed item. P11-TSK-018's
-- repayment_allocation joins this sum when it lands (it replaces this function), so the never-over-paid rule below is
-- one rule over every payer.
CREATE OR REPLACE FUNCTION lending.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(sum(a.amount_minor), 0)::bigint FROM lending.credit_balance_allocation a
     WHERE a.billing_id = billing AND a.component = paid_component
$$;

-- What a billed item's component billed: its interest and principal; no fee is billed by a billing (a late fee is
-- P11-TSK-025's own assessment on the item, which joins here when it lands).
CREATE OR REPLACE FUNCTION lending.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT CASE billed_component
               WHEN 'INTEREST' THEN b.interest_due_minor
               WHEN 'PRINCIPAL' THEN b.principal_due_minor
               ELSE 0 END
      FROM lending.instalment_billing b WHERE b.id = billing
$$;

-- At commit, for every writer (INV-LND-04): an application's rows sum to it exactly, and no billed component it paid is
-- paid beyond what it billed - so no due account of the loan ever goes negative from the rows' side.
CREATE OR REPLACE FUNCTION lending.credit_balance_application_conserves_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application uuid;
    allocated bigint;
BEGIN
    -- NEW is a different row type per table: each branch names only its own table's field.
    IF TG_TABLE_NAME = 'credit_balance_application' THEN
        application := NEW.id;
    ELSE
        application := NEW.application_id;
    END IF;
    SELECT coalesce(sum(a.amount_minor), 0) INTO allocated FROM lending.credit_balance_allocation a
     WHERE a.application_id = application;
    IF allocated <> (SELECT c.amount_minor FROM lending.credit_balance_application c WHERE c.id = application) THEN
        RAISE EXCEPTION 'a credit-balance application''s allocations sum to it exactly (P11-TSK-017, INV-LND-04)';
    END IF;
    IF EXISTS (SELECT 1 FROM lending.credit_balance_allocation a
                WHERE a.application_id = application
                  AND lending.billing_component_paid(a.billing_id, a.component)
                      > lending.billing_component_billed(a.billing_id, a.component)) THEN
        RAISE EXCEPTION 'no billed component is ever paid beyond what it billed (P11-TSK-017, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER credit_balance_application_conserves
    AFTER INSERT ON lending.credit_balance_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_balance_application_conserves_at_commit();

CREATE CONSTRAINT TRIGGER credit_balance_allocation_conserves
    AFTER INSERT ON lending.credit_balance_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.credit_balance_application_conserves_at_commit();

-- ---------------------------------------------------------------------------------------------
-- The grants: the facts SELECT and INSERT only. Never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.instalment_billing TO finapp_app;
GRANT SELECT, INSERT ON lending.credit_balance_application TO finapp_app;
GRANT SELECT, INSERT ON lending.credit_balance_allocation TO finapp_app;
