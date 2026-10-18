-- Instalment billing, the catch-up application and the servicing permit (P12-TSK-015; PHASE_12_PLAN.md sections 7.1
-- T-svc, 7.2, 7.5, 12.11 F2 and F3a; INV-BNPL-01, INV-BNPL-05, INV-LND-02 and INV-LND-04 by analogy, INV-IDEM-02,
-- INV-HIST-01; lending V012's shape, with lending V029's catch-up application and V030's floor from the first table -
-- the Phase 11 gate's R1 and R2).
--
-- The servicing step (T-svc, BnplServicing), one obligation at a time, under its row lock (B4), the database clock read
-- after it: for pay-in-N every elapsed date accrued, else nothing is written; then every instalment of the latest
-- schedule version whose due date has come on the database clock in the pinned zone is billed, oldest first, and money
-- already held settles what is due - at each billing's birth and, credit still held beside a past-due item, as the
-- obligation's next catch-up application. In the step's one transaction:
--
--     bnpl_billing                    - EXTENDED (P12-TSK-013 created it for pay-in-4's instalment 1 at purchase): the
--                                       schedule and agreement versions and engines the billing ran under, the period it
--                                       closes, the business date it was made on; its principal and pay-in-N interest
--                                       held by the trigger to the rows for every writer: the interest exactly the
--                                       period's posted accruals (never the projection), the principal
--                                       least(remaining, max(0, amount - interest)) and the last instalment all of the
--                                       remainder (INV-BNPL-05). A base-neutral reclassification, dated its due date
--                                       (the scheduler down three days bills each instalment at its own date), never
--                                       before the latest billing's. F2's entry by value - null for a billing of nothing.
--     bnpl_credit_balance_application - F3a: what of BNPL_CREDIT_BALANCE ALLOCATION_ENGINE_V1 applied over the billed
--     bnpl_credit_balance_allocation    items still due, oldest first, FEES -> INTEREST -> PRINCIPAL - at a billing's
--                                       birth (billing_id, UNIQUE, key bnpl.credit-application:<billing>) or as a
--                                       catch-up (billing_id NULL, catch_up_sequence >= 1, UNIQUE (obligation_id,
--                                       catch_up_sequence), key bnpl.credit-application:<obligation>:catch-up:<n>);
--                                       the rows sum to the application and no billed component is paid beyond what it
--                                       billed, at commit; value-dated never before the obligation's floor.
--     bnpl_interest_accrual           - the accrual RECORD the billing reads (one row per obligation and date). Its
--                                       writer, T-acc, is P12-TSK-017's: the application role is granted SELECT only
--                                       here, so until then no pay-in-N date accrues and every pay-in-N step past its
--                                       purchase date answers BEHIND_ACCRUAL (the owner-role accrual fixture writes it in
--                                       the database suites, the Phase 11 precedent). Created here so the billing's
--                                       interest is held to rows for every writer from the first billing, and the floor
--                                       has its accrual arm from the first table (R1).
--
-- The principal value floor, bnpl.principal_value_floor(obligation), replaced: the later of the day after the latest
-- accrual and the latest billing's date, never before the purchase's business date (PHASE_12_PLAN.md section 7). Every
-- principal-moving fact is refused before it and after the later of the latest date the clock showed and it - the
-- application here, the repayment since V006.
--
-- The obligation's edge function, replaced: V006's, plus the servicing permit's move alone (BNPL_LIFECYCLES.md section
-- 4.7: ACTIVE -> ACTIVE, permit only). The closure is P12-TSK-018's.
--
-- No bnpl trigger reads a ledger table (the P12-TSK-013 refinement): the entries' existence and their agreement with the
-- rows are the one transaction's and the subledger proof's (P12-TSK-025). Every instant is the database's,
-- clock_timestamp() after the act's locks. bnpl V005 stays the plan's gap.

-- ---------------------------------------------------------------------------------------------
-- The accrual record (P12-TSK-017 adds its writer).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_interest_accrual (
    id                 uuid            NOT NULL,
    obligation_id      uuid            NOT NULL,
    accrual_date       date            NOT NULL,
    period_start       date            NOT NULL,
    period_end         date            NOT NULL,
    currency           char(3)         NOT NULL,
    scale              smallint        NOT NULL,
    base_minor         bigint          NOT NULL,
    cumulative_exact   numeric(40, 20) NOT NULL,
    posted_minor       bigint          NOT NULL,
    agreement_id       uuid            NOT NULL,
    agreement_version  integer         NOT NULL,
    accrual_engine     text            NOT NULL,
    journal_entry_id   uuid,
    created_at         timestamptz     NOT NULL,
    correlation_id     text            NOT NULL,
    CONSTRAINT bnpl_interest_accrual_pk PRIMARY KEY (id),
    -- One row per obligation and date (INV-LND-02 by analogy), beneath B4 and the ledger key bnpl.accrual:<obligation>:<date>.
    CONSTRAINT bnpl_interest_accrual_date_unique UNIQUE (obligation_id, accrual_date),
    CONSTRAINT bnpl_interest_accrual_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_interest_accrual_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_interest_accrual_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    CONSTRAINT bnpl_interest_accrual_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_interest_accrual_within_its_period CHECK (
        period_start <= accrual_date AND accrual_date < period_end),
    CONSTRAINT bnpl_interest_accrual_amounts CHECK (base_minor > 0 AND posted_minor >= 0 AND cumulative_exact >= 0),
    -- An entry exactly when the date posts something - a zero day writes the row and no entry.
    CONSTRAINT bnpl_interest_accrual_entry_when_posted CHECK ((posted_minor > 0) = (journal_entry_id IS NOT NULL)),
    CONSTRAINT bnpl_interest_accrual_engine CHECK (accrual_engine = 'ACCRUAL_ENGINE_V1'),
    CONSTRAINT bnpl_interest_accrual_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_interest_accrual IS
    'A pay-in-N obligation''s daily interest accrual (P12-TSK-015 created the record the billing reads; P12-TSK-017 adds its writer, T-acc): the date''s base (BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE valued on or before it), the period''s exact cumulative interest, what the date posts (ACCRUAL_ENGINE_V1), the period it is rounded within, the agreement and engine versions, and F2a''s entry by value (bnpl.accrual:<obligation>:<date>) - null for a zero day. A billing''s interest is exactly its period''s posted_minor. INSERT-only for every role; the application role holds SELECT only until P12-TSK-017 (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_interest_accrual_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'an interest accrual is immutable from insert, for every role (P12-TSK-015, INV-LND-02)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    -- A pay-in-N obligation in servicing - pay-in-4 is interest-free (A4) and never accrues.
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.product <> 'BNPL_PAY_IN_N'
            OR obligation.currency <> NEW.currency OR obligation.agreement_id <> NEW.agreement_id THEN
        RAISE EXCEPTION 'interest accrues only on an ACTIVE pay-in-N obligation, in its currency, under its agreement (P12-TSK-015)';
    END IF;
    -- Never early (INV-LND-10 by analogy): the date has ended on the database clock in the pinned zone; never before the
    -- purchase's business date.
    IF NEW.accrual_date >= bnpl.business_date_latest(obligation.servicing_zone)
            OR NEW.accrual_date < obligation.purchase_date THEN
        RAISE EXCEPTION 'a date accrues only once it has ended on the database clock, from the purchase''s business date (P12-TSK-015)';
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
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_interest_accrual_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_interest_accrual
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_interest_accrual_is_born_once();

CREATE TRIGGER bnpl_interest_accrual_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_interest_accrual
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The billing, extended: the versions it ran under, its period, its business date; a billing of nothing.
-- ---------------------------------------------------------------------------------------------
-- NULL only on a row written before V008 (P12-TSK-013's instalment 1 at purchase, before this migration): every row
-- from here carries them - the trigger below refuses one without them, T-pur's included.
ALTER TABLE bnpl.bnpl_billing
    ADD COLUMN schedule_id        uuid,
    ADD COLUMN schedule_version   integer,
    ADD COLUMN schedule_engine    text,
    ADD COLUMN agreement_id       uuid,
    ADD COLUMN agreement_version  integer,
    ADD COLUMN accrual_engine     text,
    ADD COLUMN period_start       date,
    ADD COLUMN business_date      date,
    ADD COLUMN correlation_id     text;

ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_schedule_fk
    FOREIGN KEY (schedule_id, obligation_id) REFERENCES bnpl.bnpl_schedule (id, obligation_id);
ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_agreement_fk
    FOREIGN KEY (agreement_id, agreement_version) REFERENCES bnpl.bnpl_agreement (agreement_id, version);
-- The versions together, or (a row before V008) none of them.
ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_versions_coherent CHECK (
    (schedule_id IS NULL AND schedule_version IS NULL AND schedule_engine IS NULL AND agreement_id IS NULL
        AND agreement_version IS NULL AND accrual_engine IS NULL AND period_start IS NULL AND business_date IS NULL
        AND correlation_id IS NULL)
    OR (schedule_id IS NOT NULL AND schedule_version >= 1 AND schedule_engine IS NOT NULL AND agreement_id IS NOT NULL
        AND agreement_version >= 1 AND period_start IS NOT NULL AND period_start <= value_date
        AND business_date >= value_date AND char_length(correlation_id) BETWEEN 1 AND 200));
-- One billed item per due date of an obligation: oldest-first allocation is a total order (DueState).
ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_due_date_unique UNIQUE (obligation_id, value_date);
-- A billing of nothing writes its row and no entry (lending V012's shape): pay-in-N's principal can be zero where the
-- period's interest reaches the instalment.
ALTER TABLE bnpl.bnpl_billing ALTER COLUMN journal_entry_id DROP NOT NULL;
ALTER TABLE bnpl.bnpl_billing DROP CONSTRAINT bnpl_billing_amounts;
ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_amounts CHECK (principal_minor >= 0 AND interest_minor >= 0);
ALTER TABLE bnpl.bnpl_billing ADD CONSTRAINT bnpl_billing_entry_when_billed CHECK (
    (principal_minor + interest_minor > 0) = (journal_entry_id IS NOT NULL));

COMMENT ON TABLE bnpl.bnpl_billing IS
    'A BNPL Instalment billed (P12-TSK-013 for pay-in-4''s instalment 1 at purchase; P12-TSK-015 for every instalment at its due date, the servicing step): its principal - least(remaining, max(0, amount - interest)), the last instalment all of the remainder - and pay-in-N''s interest - exactly its period''s posted accruals, never the projection - reclassified due by the entry F2 (DR BNPL_PRINCIPAL_DUE / CR BNPL_PRINCIPAL, DR BNPL_INTEREST_DUE / CR BNPL_INTEREST_ACCRUED, key bnpl.billing:<instalment>, its id here; null for a billing of nothing), value-dated its due date, never before the latest billing''s; the schedule and agreement versions and engines it ran under and the business date it was made on. What is still due on it is billed less paid (bnpl.billing_component_paid). Born once per instalment and per due date, INSERT-only (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    schedule bnpl.bnpl_schedule%ROWTYPE;
    agreement bnpl.bnpl_agreement%ROWTYPE;
    period_from date;
    accrued bigint;
    accrued_through date;
    remaining bigint;
    expected bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL billing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- (B4) The obligation, for every writer: the order of billings and the principal billed are judged under the lock
    -- every servicing act on it holds (the step and the acceptance hold it already; re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL billing bills an ACTIVE obligation (P12-TSK-015)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = instalment.schedule_id;
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
    -- Dated its due date, which has come on the database clock in the pinned zone - never early.
    IF NEW.value_date IS DISTINCT FROM instalment.due_date
            OR instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL billing is dated its instalment''s due date, once that date has come on the database clock (P12-TSK-015)';
    END IF;
    -- Oldest first: every earlier instalment of the version billed, and never before the latest billing's date.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_instalment i
                WHERE i.schedule_id = schedule.id AND i.sequence < instalment.sequence
                  AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = i.id))
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
    -- projection - and only once every date of the period has accrued (the accrual gate, for every writer).
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1' THEN
        accrued := 0;
    ELSE
        SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id AND a.accrual_date >= period_from
           AND a.accrual_date < instalment.due_date;
        SELECT max(a.accrual_date) INTO accrued_through FROM bnpl.bnpl_interest_accrual a
         WHERE a.obligation_id = NEW.obligation_id;
        IF accrued_through IS NULL OR accrued_through < instalment.due_date - 1 THEN
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
    -- The business date it was made on and its instant - the database's, after the lock.
    NEW.business_date := GREATEST(bnpl.business_date_latest(obligation.servicing_zone), NEW.value_date);
    NEW.billed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The principal value floor, replaced: its accrual arm from the first table.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.principal_value_floor(obligation uuid)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(
        (SELECT o.purchase_date FROM bnpl.bnpl_obligation o WHERE o.id = obligation),
        (SELECT max(b.value_date) FROM bnpl.bnpl_billing b WHERE b.obligation_id = obligation),
        (SELECT max(a.accrual_date) + 1 FROM bnpl.bnpl_interest_accrual a WHERE a.obligation_id = obligation))
$$;

COMMENT ON FUNCTION bnpl.principal_value_floor(uuid) IS
    'The first business date an obligation''s record leaves open to a principal-moving fact (P12-TSK-013, extended by P12-TSK-015; the Phase 11 gate''s R1, lending V030''s shape): the later of the day after its latest accrual and its latest billing''s value date itself, never before its purchase''s business date. Read under B4 by every writer of a principal fact, which values it max(today, floor); its trigger refuses a value date before it and after the later of the latest date the clock showed and it.';

-- ---------------------------------------------------------------------------------------------
-- F3a: the credit-balance application and its allocation rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_credit_balance_application (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    billing_id         uuid,
    catch_up_sequence  integer,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    allocation_engine  text        NOT NULL,
    value_date         date        NOT NULL,
    journal_entry_id   uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_credit_balance_application_pk PRIMARY KEY (id),
    -- At a billing's birth: at most one per billing, beneath B4 and the key bnpl.credit-application:<billing>.
    CONSTRAINT bnpl_credit_balance_application_billing_unique UNIQUE (billing_id),
    -- A catch-up: one per number per obligation, beneath B4 and the key bnpl.credit-application:<obligation>:catch-up:<n>.
    CONSTRAINT bnpl_credit_balance_application_catch_up_unique UNIQUE (obligation_id, catch_up_sequence),
    CONSTRAINT bnpl_credit_balance_application_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_credit_balance_application_obligation_fk FOREIGN KEY (obligation_id)
        REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_credit_balance_application_billing_fk FOREIGN KEY (billing_id) REFERENCES bnpl.bnpl_billing (id),
    CONSTRAINT bnpl_credit_balance_application_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- Two occasions on one table (lending V029's shape): at a billing, or a catch-up numbered from 1.
    CONSTRAINT bnpl_credit_balance_application_occasion CHECK (
        (billing_id IS NULL) = (catch_up_sequence IS NOT NULL) AND (catch_up_sequence IS NULL OR catch_up_sequence >= 1)),
    CONSTRAINT bnpl_credit_balance_application_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Something applied: a credit balance that meets nothing due writes no application.
    CONSTRAINT bnpl_credit_balance_application_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT bnpl_credit_balance_application_engine CHECK (allocation_engine = 'ALLOCATION_ENGINE_V1'),
    CONSTRAINT bnpl_credit_balance_application_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_credit_balance_application IS
    'Money already held applied (P12-TSK-015; PHASE_12_PLAN.md section 12.11 F3a; the Phase 11 gate''s R2 from the first table): what of the obligation''s BNPL_CREDIT_BALANCE ALLOCATION_ENGINE_V1 applied over its billed items still due, oldest first, FEES -> INTEREST -> PRINCIPAL - at a billing''s birth (billing_id, key bnpl.credit-application:<billing>) or, credit held beside something already due, as a catch-up (catch_up_sequence, key bnpl.credit-application:<obligation>:catch-up:<n>) - the amount (its allocation rows sum to it at commit), the agreement and allocation engine versions, its value date (max(today, the floor)) and F3a''s entry by value (DR BNPL_CREDIT_BALANCE / CR <due accounts>). INSERT-only (RESTRICTED-FINANCIAL).';

CREATE INDEX bnpl_credit_balance_application_by_obligation
    ON bnpl.bnpl_credit_balance_application (obligation_id, created_at);

CREATE TABLE bnpl.bnpl_credit_balance_allocation (
    id              uuid     NOT NULL,
    application_id  uuid     NOT NULL,
    ordinal         smallint NOT NULL,
    billing_id      uuid     NOT NULL,
    component       text     NOT NULL,
    currency        char(3)  NOT NULL,
    scale           smallint NOT NULL,
    amount_minor    bigint   NOT NULL,
    CONSTRAINT bnpl_credit_balance_allocation_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_credit_balance_allocation_ordinal_unique UNIQUE (application_id, ordinal),
    -- One line per item and component (ALLOCATION_ENGINE_V1: two lines never pay one component).
    CONSTRAINT bnpl_credit_balance_allocation_component_unique UNIQUE (application_id, billing_id, component),
    CONSTRAINT bnpl_credit_balance_allocation_application_fk FOREIGN KEY (application_id)
        REFERENCES bnpl.bnpl_credit_balance_application (id),
    CONSTRAINT bnpl_credit_balance_allocation_billing_fk FOREIGN KEY (billing_id) REFERENCES bnpl.bnpl_billing (id),
    CONSTRAINT bnpl_credit_balance_allocation_component_is_known CHECK (component IN ('FEES', 'INTEREST', 'PRINCIPAL')),
    CONSTRAINT bnpl_credit_balance_allocation_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_credit_balance_allocation_shape CHECK (ordinal >= 1 AND amount_minor > 0)
);

CREATE INDEX bnpl_credit_balance_allocation_by_billing ON bnpl.bnpl_credit_balance_allocation (billing_id, component);

COMMENT ON TABLE bnpl.bnpl_credit_balance_allocation IS
    'One line of a credit-balance application (P12-TSK-015): what it paid of one billed item''s component - the per-item split F3a''s entry aggregates per due account. INSERT-only; with its application''s other rows it sums to the application, and no billed component is ever paid beyond what it billed, at commit (INV-LND-04 by analogy) (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_credit_balance_application_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance application is immutable from insert, for every role (P12-TSK-015)';
    END IF;
    -- (B4) The obligation, for every writer: a catch-up's number and the floor are judged under the lock every payer of it
    -- holds (lending V029's shape; the step holds it already, re-taking it is free).
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a credit balance is applied only on an ACTIVE obligation, in its currency (P12-TSK-015)';
    END IF;
    IF NEW.billing_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.id = NEW.billing_id AND b.obligation_id = NEW.obligation_id) THEN
            RAISE EXCEPTION 'a credit-balance application is born at its own obligation''s billing (P12-TSK-015)';
        END IF;
    ELSIF NEW.catch_up_sequence IS DISTINCT FROM 1 + coalesce(
            (SELECT max(c.catch_up_sequence) FROM bnpl.bnpl_credit_balance_application c
              WHERE c.obligation_id = NEW.obligation_id), 0) THEN
        -- The obligation's next - numbered in order, never a gap, never a second at one number.
        RAISE EXCEPTION 'a catch-up credit-balance application is its obligation''s next (P12-TSK-015, the Phase 11 gate''s R2)';
    END IF;
    -- Under the agreement version in force and its pinned allocation engine.
    IF obligation.agreement_id <> NEW.agreement_id
            OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x
                                          WHERE x.obligation_id = NEW.obligation_id)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'an application names its obligation''s agreement version in force and its pinned allocation engine (P12-TSK-015, INV-LND-09)';
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the latest
    -- date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a credit-balance application is value-dated on or after its obligation''s floor and never after today (P12-TSK-015, the Phase 11 gate''s R1)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_credit_balance_application_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_credit_balance_application
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_credit_balance_application_is_born_once();

CREATE TRIGGER bnpl_credit_balance_application_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_credit_balance_application
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE OR REPLACE FUNCTION bnpl.bnpl_credit_balance_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit-balance allocation is immutable from insert, for every role (P12-TSK-015)';
    END IF;
    -- A line of an application, paying a billing of the application's own obligation, in its currency and scale.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_credit_balance_application c
                     JOIN bnpl.bnpl_billing b ON b.obligation_id = c.obligation_id
                    WHERE c.id = NEW.application_id AND b.id = NEW.billing_id
                      AND c.currency = NEW.currency AND c.scale = NEW.scale) THEN
        RAISE EXCEPTION 'a credit-balance allocation pays a billing of its application''s own obligation, in its currency (P12-TSK-015)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_credit_balance_allocation_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_credit_balance_allocation
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_credit_balance_allocation_is_born_once();

CREATE TRIGGER bnpl_credit_balance_allocation_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_credit_balance_allocation
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- What a billing's component has had paid - every payer's allocation: the credit-balance applications here and the
-- repayments' due-item lines (V006, keyed by instalment). The one definition: P12-TSK-018 (repayments), -020 (fees),
-- -021 (refund cancellations) and -024 (reversals) replace it, so the never-over-paid rule below is one rule over every
-- payer.
CREATE OR REPLACE FUNCTION bnpl.billing_component_paid(billing uuid, paid_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT ((SELECT coalesce(sum(a.amount_minor), 0) FROM bnpl.bnpl_credit_balance_allocation a
              WHERE a.billing_id = billing AND a.component = paid_component)
          + (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation r
               JOIN bnpl.bnpl_billing b ON b.instalment_id = r.instalment_id
              WHERE b.id = billing AND r.target = 'DUE_ITEM' AND r.component = paid_component))::bigint
$$;

COMMENT ON FUNCTION bnpl.billing_component_paid(uuid, text) IS
    'What a billing''s component (FEES, INTEREST, PRINCIPAL) has had paid by every payer - credit-balance applications and repayments (P12-TSK-015). The one definition later payers replace (-018, -020, -021, -024); what is still due is billed less this.';

-- What a billing's component billed: its interest and principal; no fee is billed by a billing (a late fee is P12-TSK-020's
-- own assessment on the item, which joins here when it lands).
CREATE OR REPLACE FUNCTION bnpl.billing_component_billed(billing uuid, billed_component text)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT CASE billed_component
               WHEN 'INTEREST' THEN b.interest_minor
               WHEN 'PRINCIPAL' THEN b.principal_minor
               ELSE 0 END
      FROM bnpl.bnpl_billing b WHERE b.id = billing
$$;

-- At commit, for every writer: an application's rows sum to it exactly, and no billed component it paid is paid beyond
-- what it billed - so no due account of the obligation ever goes negative from the rows' side.
CREATE OR REPLACE FUNCTION bnpl.bnpl_credit_balance_application_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application uuid;
    allocated bigint;
BEGIN
    -- NEW is a different row type per table: each branch names only its own table's field.
    IF TG_TABLE_NAME = 'bnpl_credit_balance_application' THEN
        application := NEW.id;
    ELSE
        application := NEW.application_id;
    END IF;
    SELECT coalesce(sum(a.amount_minor), 0) INTO allocated FROM bnpl.bnpl_credit_balance_allocation a
     WHERE a.application_id = application;
    IF allocated <> (SELECT c.amount_minor FROM bnpl.bnpl_credit_balance_application c WHERE c.id = application) THEN
        RAISE EXCEPTION 'a credit-balance application''s allocations sum to it exactly (P12-TSK-015, INV-LND-04)';
    END IF;
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_credit_balance_allocation a
                WHERE a.application_id = application
                  AND bnpl.billing_component_paid(a.billing_id, a.component)
                      > bnpl.billing_component_billed(a.billing_id, a.component)) THEN
        RAISE EXCEPTION 'no billed component is ever paid beyond what it billed (P12-TSK-015, INV-LND-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_credit_balance_application_conserves
    AFTER INSERT ON bnpl.bnpl_credit_balance_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_credit_balance_application_conserves();

CREATE CONSTRAINT TRIGGER bnpl_credit_balance_allocation_conserves
    AFTER INSERT ON bnpl.bnpl_credit_balance_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_credit_balance_application_conserves();

-- ---------------------------------------------------------------------------------------------
-- The obligation's edge function, replaced: V006's, plus the servicing permit's move alone.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_obligation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL obligation is born ACTIVE (P12-TSK-013)';
        END IF;
        -- Born beside its AUTHORISED purchase, for the purchase's party, product, plan and currency and the offer's
        -- decision; its purchase date and zone the purchase's own.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
        IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.party_id <> NEW.party_id
                OR purchase.product <> NEW.product OR purchase.plan <> NEW.plan OR purchase.currency <> NEW.currency THEN
            RAISE EXCEPTION 'a BNPL obligation is born beside its AUTHORISED purchase, for its party, product, plan and currency (P12-TSK-013)';
        END IF;
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = purchase.offer_id;
        IF offer.decision_id <> NEW.decision_id THEN
            RAISE EXCEPTION 'a BNPL obligation names the decision its offer was made on (P12-TSK-013)';
        END IF;
        NEW.servicing_zone := purchase.servicing_zone;
        NEW.purchase_date := purchase.business_date;
        NEW.opened_at := clock_timestamp();
        -- The servicing sweep's first look is due at once (P12-TSK-015).
        NEW.next_servicing_at := NEW.opened_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL obligation is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.purchase_date IS DISTINCT FROM OLD.purchase_date OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL obligation''s identity, product and origin are frozen from birth (P12-TSK-015)';
    END IF;
    -- The servicing permit alone (P12-TSK-015, BNPL_LIFECYCLES.md section 4.7): an ACTIVE obligation moves its permit and
    -- nothing else - the sweep's claim and a completed servicing step (ten steps racing on one obligation each stamp the
    -- same next business date). Never a CLOSED obligation's, never beside another column.
    IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'
            AND NEW.closed_at IS NOT DISTINCT FROM OLD.closed_at
            AND NEW.closure_reason IS NOT DISTINCT FROM OLD.closure_reason THEN
        RETURN NEW;
    END IF;
    -- Every other edge is a later task's: the closure (P12-TSK-018, -021, -022) replaces this function and admits its edge
    -- beside its own fact.
    RAISE EXCEPTION 'a BNPL obligation moves only its servicing permit until its closure''s task admits the edge; % -> % is not an edge (P12-TSK-015)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the permit alone moves; the facts SELECT and INSERT only; the accrual record SELECT only (its writer is
-- P12-TSK-017's). Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT UPDATE (next_servicing_at) ON bnpl.bnpl_obligation TO finapp_app;
GRANT SELECT ON bnpl.bnpl_interest_accrual TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_credit_balance_application TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_credit_balance_allocation TO finapp_app;
