-- Auto-collection from the wallet (P12-TSK-019; PHASE_12_PLAN.md sections 7.1 T-svc, 7.2, 12.11 F3 and A7; BNPL_LIFECYCLES.md
-- section 4.13; owner decision B6 - the wallet only, no card charge; INV-IDEM-02, INV-LND-04 by analogy, INV-BAL-04,
-- INV-BNPL-01; lending V015's shape). The number is V011 - the wave D board's reservation (P12-TSK-021 takes V012, P12-TSK-020
-- V013; Flyway runs outOfOrder = false, so this merges first).
--
-- When the agreement version in force pins terms that collect automatically (auto_collection), the servicing step (T-svc),
-- after the billing and the catch-up credit-balance application and under the same obligation row lock (B4), collects
-- from the customer's existing wallet what each billed instalment has due - on its due date, and on each later business
-- date the pinned retry cadence reaches while it stays due - through P12-TSK-018's repayment: kind AUTO_COLLECTION, the
-- attempt's natural key <instalment>:<date> as the requester's key, (P) the wallet's ledger row and its available balance
-- under that lock, ALLOCATION_ENGINE_V1 (every line on the attempted instalment - never the credit balance, so never more
-- than due), F3 (DR CUSTOMER_WALLET / CR <due accounts>) value-dated max(today, floor), and the step's closure after it.
--
--     bnpl_collection_attempt   - one per billed instalment and business date, whatever happened: the outcome (COLLECTED,
--                                 PARTIAL, INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE, NOTHING_DUE), what the instalment had
--                                 due when attempted, what was collected, the agreement version whose terms governed it
--                                 and - exactly when money moved - the repayment that moved it. A failed attempt posts
--                                 nothing and is the evidence; a later date's attempt is a new row. INSERT-only.
--
-- Held for EVERY writer: UNIQUE (instalment_id, attempt_date) - the scheduled process idempotent per instalment and date
-- (INV-IDEM-02, scenario 4) - and UNIQUE (repayment_id); B4 re-taken by the trigger; an ACTIVE obligation under its agreement
-- version in force, whose pinned terms collect automatically; PARTIAL only when they allow it; dated the business date the
-- transaction's database clock showed, in the pinned zone; a billed instalment of its own obligation, due by that date, never
-- before an earlier attempt of it (a missed date is never back-filled); what was due less what was collected equal to what
-- the instalment still has due beside it (every payer's rows counted); the repayment it names its own obligation's,
-- AUTO_COLLECTION under the attempt's key, for the amount collected, every line of it paying this instalment; and, at
-- commit, every AUTO_COLLECTION repayment named by its attempt (no automatic money without its record). The repayment's
-- trigger is replaced to admit AUTO_COLLECTION (PAYOFF stays refused until P12-TSK-022). Every instant the database's,
-- clock_timestamp() after the act's locks.

-- ---------------------------------------------------------------------------------------------
-- The collection attempt.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_collection_attempt (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    instalment_id      uuid        NOT NULL,
    attempt_date       date        NOT NULL,
    outcome            text        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    due_minor          bigint      NOT NULL,
    collected_minor    bigint      NOT NULL,
    agreement_version  integer     NOT NULL,
    repayment_id       uuid,
    created_at         timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_collection_attempt_pk PRIMARY KEY (id),
    -- Once per billed instalment and business date (INV-IDEM-02): beneath B4, which serialises every servicing act on the
    -- obligation, and the repayment's UNIQUE (obligation_id, kind, request_key) under the attempt's natural key.
    CONSTRAINT bnpl_collection_attempt_instalment_date_unique UNIQUE (instalment_id, attempt_date),
    -- A repayment is the money of one attempt.
    CONSTRAINT bnpl_collection_attempt_repayment_unique UNIQUE (repayment_id),
    CONSTRAINT bnpl_collection_attempt_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_collection_attempt_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    CONSTRAINT bnpl_collection_attempt_repayment_fk FOREIGN KEY (repayment_id) REFERENCES bnpl.bnpl_repayment (id),
    -- CollectionOutcome.
    CONSTRAINT bnpl_collection_attempt_outcome_is_known CHECK (
        outcome IN ('COLLECTED', 'PARTIAL', 'INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE', 'NOTHING_DUE')),
    CONSTRAINT bnpl_collection_attempt_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The outcome's shape: a repayment exactly when money moved, never more than was due.
    CONSTRAINT bnpl_collection_attempt_shape CHECK (
        ((outcome IN ('COLLECTED', 'PARTIAL')) = (repayment_id IS NOT NULL))
        AND (outcome <> 'COLLECTED' OR (due_minor > 0 AND collected_minor = due_minor))
        AND (outcome <> 'PARTIAL' OR (collected_minor > 0 AND collected_minor < due_minor))
        AND (outcome NOT IN ('INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE') OR (due_minor > 0 AND collected_minor = 0))
        AND (outcome <> 'NOTHING_DUE' OR (due_minor = 0 AND collected_minor = 0))),
    CONSTRAINT bnpl_collection_attempt_agreement_version_positive CHECK (agreement_version >= 1),
    CONSTRAINT bnpl_collection_attempt_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_collection_attempt_by_obligation ON bnpl.bnpl_collection_attempt (obligation_id, attempt_date);

COMMENT ON TABLE bnpl.bnpl_collection_attempt IS
    'One automatic collection attempt from the customer''s wallet (P12-TSK-019; A7, owner decision B6 - the wallet only): per billed instalment and business date - on its due date and on each later business date the pinned retry cadence reaches while it stays due - whatever happened: COLLECTED (all it had due), PARTIAL (less, the pinned terms allowing it), INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE or NOTHING_DUE (settled before the attempt), what the instalment had due and what was collected, the agreement version whose terms governed it, and the AUTO_COLLECTION repayment exactly when money moved (F3 under bnpl.repayment:<repayment>). A failed attempt posts nothing; a later date''s is a new row, never a back-filled one. UNIQUE (instalment_id, attempt_date). INSERT-only for every role (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_collection_attempt_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    instalment bnpl.bnpl_instalment%ROWTYPE;
    billing bnpl.bnpl_billing%ROWTYPE;
    repayment bnpl.bnpl_repayment%ROWTYPE;
    still_due bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL collection attempt is immutable from insert, for every role (P12-TSK-019, INV-IDEM-02)';
    END IF;
    -- (B4) The obligation, for every writer (T-svc holds it already; re-taking it is free): an ACTIVE one - a closed
    -- obligation takes no money (INV-LIFE-04). The step's closure comes after its collection.
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;
    IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'a BNPL collection is attempted only on an ACTIVE obligation (P12-TSK-019, INV-LIFE-04)';
    END IF;
    -- The authority: the agreement version in force pins terms that collect automatically (A7) - the repayment method
    -- the customer accepted with the agreement; there is no door.
    IF NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x WHERE x.obligation_id = NEW.obligation_id) THEN
        RAISE EXCEPTION 'a BNPL collection attempt names its obligation''s agreement version in force (P12-TSK-019)';
    END IF;
    SELECT t.* INTO terms FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    IF terms.terms_version_id IS NULL OR NOT terms.auto_collection THEN
        RAISE EXCEPTION 'a BNPL collection is attempted only under an agreement whose pinned terms collect automatically (P12-TSK-019, A7)';
    END IF;
    IF NEW.outcome = 'PARTIAL' AND NOT terms.partial_collection THEN
        RAISE EXCEPTION 'a partial BNPL collection only where the pinned terms allow it (P12-TSK-019, A7)';
    END IF;
    -- Dated the business date the transaction's database clock showed, in the pinned zone (the range it read).
    IF NEW.attempt_date < LEAST(bnpl.business_date(transaction_timestamp(), obligation.servicing_zone),
                                bnpl.business_date(statement_timestamp(), obligation.servicing_zone))
            OR NEW.attempt_date > bnpl.business_date_latest(obligation.servicing_zone) THEN
        RAISE EXCEPTION 'a BNPL collection attempt is dated the business date of the database clock in its pinned zone (P12-TSK-019)';
    END IF;
    -- A billed instalment of its own obligation, due by the attempt's date, in its currency and scale.
    SELECT i.* INTO instalment FROM bnpl.bnpl_instalment i
     WHERE i.id = NEW.instalment_id AND i.obligation_id = NEW.obligation_id;
    SELECT b.* INTO billing FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id;
    IF instalment.id IS NULL OR billing.id IS NULL OR instalment.due_date > NEW.attempt_date
            OR NEW.currency <> billing.currency OR NEW.scale <> billing.scale THEN
        RAISE EXCEPTION 'a BNPL collection is attempted on a billed instalment of its own obligation, on or after its due date (P12-TSK-019)';
    END IF;
    -- Never back-filled: no attempt of the instalment is dated after this one (the same date is the unique's).
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_collection_attempt c
                WHERE c.instalment_id = NEW.instalment_id AND c.attempt_date > NEW.attempt_date) THEN
        RAISE EXCEPTION 'a BNPL collection attempt is never dated before an earlier attempt of its instalment (P12-TSK-019)';
    END IF;
    -- What was due less what was collected is what the instalment still has due beside it (every payer's rows counted,
    -- this collection's included).
    SELECT sum(bnpl.billing_component_billed(billing.id, c.component) - bnpl.billing_component_paid(billing.id, c.component))
      INTO still_due
      FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF NEW.due_minor - NEW.collected_minor <> still_due THEN
        RAISE EXCEPTION 'a BNPL collection attempt records what its instalment had due and what it collected (P12-TSK-019, INV-LND-04)';
    END IF;
    -- The money, when it moved: an AUTO_COLLECTION repayment of this obligation under the attempt's key, for the amount
    -- collected, under the same agreement version, never valued before the attempt's date, every line paying this
    -- instalment - never the credit balance, so never more than due.
    IF NEW.repayment_id IS NOT NULL THEN
        SELECT r.* INTO repayment FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id;
        IF repayment.id IS NULL OR repayment.obligation_id <> NEW.obligation_id OR repayment.kind <> 'AUTO_COLLECTION'
                OR repayment.request_key <> NEW.instalment_id::text || ':' || NEW.attempt_date::text
                OR repayment.amount_minor <> NEW.collected_minor OR repayment.agreement_version <> NEW.agreement_version
                OR repayment.value_date < NEW.attempt_date
                OR EXISTS (SELECT 1 FROM bnpl.bnpl_repayment_allocation a
                            WHERE a.repayment_id = repayment.id
                              AND (a.target <> 'DUE_ITEM' OR a.instalment_id <> NEW.instalment_id)) THEN
            RAISE EXCEPTION 'a BNPL collection names its own AUTO_COLLECTION repayment, which pays only its instalment (P12-TSK-019, INV-LND-04)';
        END IF;
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_collection_attempt_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_collection_attempt
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_collection_attempt_is_born_once();

CREATE TRIGGER bnpl_collection_attempt_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_collection_attempt
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- At commit, for every writer: an AUTO_COLLECTION repayment is named by its collection attempt - money taken automatically
-- always has its record. A new trigger beside P12-TSK-018's; the conservation functions are not replaced.
CREATE OR REPLACE FUNCTION bnpl.bnpl_auto_collection_has_its_attempt()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'AUTO_COLLECTION'
            AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_collection_attempt c WHERE c.repayment_id = NEW.id) THEN
        RAISE EXCEPTION 'an automatic BNPL collection''s repayment is named by its collection attempt (P12-TSK-019, INV-IDEM-02)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_repayment_auto_collection_has_its_attempt
    AFTER INSERT ON bnpl.bnpl_repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_auto_collection_has_its_attempt();

-- ---------------------------------------------------------------------------------------------
-- The repayment's trigger, replaced: V010's, AUTO_COLLECTION admitted beside its attempt (PAYOFF still P12-TSK-022's).
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
    -- servicing act on it holds (T-pur, T-rep and T-svc hold it already; re-taking it is free).
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
    -- attempt's trigger, and to having one at all by bnpl_repayment_auto_collection_has_its_attempt at commit. The payoff
    -- (P12-TSK-022) replaces this function beside its own fact.
    IF NEW.kind = 'PAYOFF' THEN
        RAISE EXCEPTION 'a % repayment is born only beside its own fact, which its task admits (P12-TSK-022)', NEW.kind;
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
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_collection_attempt TO finapp_app;
