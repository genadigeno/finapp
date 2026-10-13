-- Scheduled auto-collection (P11-TSK-019; PHASE_11_PLAN.md sections 7.1 T-svc, 7.4, 12.5 and 12.8 rule 5, LENDING_LIFECYCLES.md
-- section 4.8; ADR-0095 point 6, A21; INV-IDEM-02, INV-LND-04, INV-BAL-04). The number is V015 - the board's reservation (V014 is
-- P11-TSK-018's repayment, V016 P11-TSK-020's reversal); the backlog's "V012" is stale.
--
-- When the governing agreement's pinned terms say so (auto_collection), the servicing step (T-svc), after the accrual and the
-- billing with its credit-balance application and under the same loan row lock (L3), collects from the borrower's wallet what
-- each billed item has due - on its due date and on each later business date while it stays due - through P11-TSK-018's
-- repayment: kind AUTO_COLLECTION, the attempt's natural key as the requester's key, (L7) the wallet's ledger row and
-- AvailableBalance.underLock, the same allocation (oldest first, the pinned component order), posting rule 5 and the closure.
--
--     collection_attempt   - one per billed item and business date, whatever happened: the outcome (COLLECTED, PARTIAL,
--                            INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE, NOTHING_DUE), what the item had due when attempted,
--                            what was collected and - exactly when money moved - the repayment that moved it. A failed
--                            attempt posts nothing and is the evidence; tomorrow's attempt is a new row.
--
-- Held for EVERY writer: UNIQUE (due_item_id, attempt_date) - the scheduled process idempotent per period (INV-IDEM-02,
-- scenario 2's sweeper half) - and UNIQUE (repayment_id); an attempt only for an ACTIVE instalment loan (or one its own
-- collection's repayment has just closed) whose governing
-- agreement's pinned terms collect automatically and whose repayment method is the wallet; on a billed item of its own loan
-- already due; dated the business date of its transaction in the pinned zone; PARTIAL only when the pinned terms allow it;
-- the outcome's shape (a repayment exactly when money moved; COLLECTED all that was due, PARTIAL less, the failures and
-- NOTHING_DUE nothing); what was due less what was collected equal to what the item still has due beside it; the repayment
-- it names one of its own loan, of kind AUTO_COLLECTION under the attempt's key, dated the attempt's date, for the amount
-- collected, every line of it paying this billed item (never the credit balance - never more than due); and, at commit,
-- every AUTO_COLLECTION repayment named by its attempt (no automatic money without its record). INSERT-only, never truncated.

-- ---------------------------------------------------------------------------------------------
-- The collection attempt.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.collection_attempt (
    id                uuid        NOT NULL,
    loan_id           uuid        NOT NULL,
    due_item_id       uuid        NOT NULL,
    attempt_date      date        NOT NULL,
    outcome           text        NOT NULL,
    currency          char(3)     NOT NULL,
    scale             smallint    NOT NULL,
    due_minor         bigint      NOT NULL,
    collected_minor   bigint      NOT NULL,
    repayment_id      uuid,
    created_at        timestamptz NOT NULL,
    correlation_id    text        NOT NULL,
    CONSTRAINT collection_attempt_pk PRIMARY KEY (id),
    -- Once per billed item and business date (INV-IDEM-02): beneath L3, which serialises every servicing step on the loan.
    CONSTRAINT collection_attempt_item_date_unique UNIQUE (due_item_id, attempt_date),
    -- A repayment is the money of one attempt.
    CONSTRAINT collection_attempt_repayment_unique UNIQUE (repayment_id),
    CONSTRAINT collection_attempt_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT collection_attempt_billing_fk FOREIGN KEY (due_item_id) REFERENCES lending.instalment_billing (id),
    CONSTRAINT collection_attempt_repayment_fk FOREIGN KEY (repayment_id) REFERENCES lending.repayment (id),
    CONSTRAINT collection_attempt_outcome_is_known CHECK (
        outcome IN ('COLLECTED', 'PARTIAL', 'INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE', 'NOTHING_DUE')),
    CONSTRAINT collection_attempt_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The outcome's shape: a repayment exactly when money moved, never more than was due.
    CONSTRAINT collection_attempt_shape CHECK (
        ((outcome IN ('COLLECTED', 'PARTIAL')) = (repayment_id IS NOT NULL))
        AND (outcome <> 'COLLECTED' OR (due_minor > 0 AND collected_minor = due_minor))
        AND (outcome <> 'PARTIAL' OR (collected_minor > 0 AND collected_minor < due_minor))
        AND (outcome NOT IN ('INSUFFICIENT_FUNDS', 'WALLET_NOT_POSTABLE') OR (due_minor > 0 AND collected_minor = 0))
        AND (outcome <> 'NOTHING_DUE' OR (due_minor = 0 AND collected_minor = 0))),
    CONSTRAINT collection_attempt_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX collection_attempt_by_loan ON lending.collection_attempt (loan_id, attempt_date);

COMMENT ON TABLE lending.collection_attempt IS
    'One automatic collection attempt (P11-TSK-019; ADR-0095 point 6, A21): per billed item and business date - on its due date and each later business date while it stays due - whatever happened: COLLECTED (all it had due), PARTIAL (less, the pinned terms allowing it), INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE or NOTHING_DUE (settled before the attempt), what the item had due and what was collected, and the AUTO_COLLECTION repayment exactly when money moved (posting rule 5 under lending.repayment:<repayment>). A failed attempt posts nothing; tomorrow''s is a new row. UNIQUE (due_item_id, attempt_date). INSERT-only for every role. RESTRICTED-FINANCIAL.';

CREATE OR REPLACE FUNCTION lending.collection_attempt_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    account lending.loan%ROWTYPE;
    agreement lending.loan_agreement%ROWTYPE;
    terms lending.loan_terms%ROWTYPE;
    billing lending.instalment_billing%ROWTYPE;
    repayment lending.repayment%ROWTYPE;
    still_due bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a collection attempt is immutable from insert, for every role (P11-TSK-019, INV-IDEM-02)';
    END IF;
    -- An ACTIVE instalment loan (a closed or cancelled one takes no money, INV-LND-08; a line's statement minimum is
    -- P11-TSK-022's, which replaces this function) - or one this attempt's own collection has just closed: the repayment
    -- closes the loan in its transaction (P11-TSK-018) before the attempt naming it is recorded.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL OR account.kind <> 'INSTALMENT'
            OR NOT (account.status = 'ACTIVE'
                    OR (account.status = 'CLOSED' AND NEW.repayment_id IS NOT NULL
                        AND EXISTS (SELECT 1 FROM lending.loan_closure c
                                     WHERE c.loan_id = NEW.loan_id AND c.repayment_id = NEW.repayment_id))) THEN
        RAISE EXCEPTION 'a collection is attempted only for an ACTIVE instalment loan (P11-TSK-019, INV-LND-08)';
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
    -- A billed item of its own loan, already due.
    SELECT b.* INTO billing FROM lending.instalment_billing b WHERE b.id = NEW.due_item_id;
    IF billing.id IS NULL OR billing.loan_id <> NEW.loan_id OR billing.due_date > NEW.attempt_date THEN
        RAISE EXCEPTION 'a collection is attempted on a billed item of its own loan, on or after its due date (P11-TSK-019)';
    END IF;
    -- What was due less what was collected is what the item still has due beside it (every payer's rows counted).
    SELECT sum(lending.billing_component_billed(billing.id, c.component)
               - lending.billing_component_paid(billing.id, c.component))
      INTO still_due
      FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component);
    IF NEW.due_minor - NEW.collected_minor <> still_due THEN
        RAISE EXCEPTION 'a collection attempt records what its item had due and what it collected (P11-TSK-019, INV-LND-04)';
    END IF;
    -- The money, when it moved: an AUTO_COLLECTION repayment of this loan under the attempt's key, dated its date, for the
    -- amount collected, every line paying this billed item - never the credit balance, so never more than due.
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

CREATE TRIGGER collection_attempt_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.collection_attempt
    FOR EACH ROW
    EXECUTE FUNCTION lending.collection_attempt_is_born_once();

CREATE TRIGGER collection_attempt_is_never_truncated
    BEFORE TRUNCATE ON lending.collection_attempt
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- At commit, for every writer: an AUTO_COLLECTION repayment is named by its collection attempt - money taken automatically
-- always has its record. A new trigger beside P11-TSK-018's; none of its functions is replaced.
CREATE OR REPLACE FUNCTION lending.auto_collection_has_its_attempt()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'AUTO_COLLECTION'
            AND NOT EXISTS (SELECT 1 FROM lending.collection_attempt c WHERE c.repayment_id = NEW.id) THEN
        RAISE EXCEPTION 'an automatic collection''s repayment is named by its collection attempt (P11-TSK-019, INV-IDEM-02)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER repayment_auto_collection_has_its_attempt
    AFTER INSERT ON lending.repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.auto_collection_has_its_attempt();

-- ---------------------------------------------------------------------------------------------
-- The grants: SELECT and INSERT only - never UPDATE, never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.collection_attempt TO finapp_app;
