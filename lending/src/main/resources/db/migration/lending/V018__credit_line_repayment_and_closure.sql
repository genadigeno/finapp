-- Credit line repayment and closure (P11-TSK-023; PHASE_11_PLAN.md sections 5.3, 7.1 T-rep, T-svc and T-clo, 7.2, 12.5,
-- 12.6 and 12.8 rule 5; ADR-0100 points 4, 8 and 9, ADR-0095 point 2; A26; INV-LND-04, INV-LND-08, INV-LND-14,
-- INV-LIFE-04, INV-IDEM-01). The number is V018 - the next free lending version (V016 is P11-TSK-020's, V017
-- P11-TSK-022's); the backlog's "V016" is stale.
--
-- The borrower repays their line and closes it:
--
--     the repayment (T-rep)  - P11-TSK-018's one posting half, now admitting a REVOLVING account ACTIVE or CLOSING: allocated
--                              by ALLOCATION_ENGINE_V1 under the pinned PAY_DOWN_PRINCIPAL treatment (A26) - billed
--                              statements oldest first (FEES -> INTEREST -> PRINCIPAL), then principal not yet due (CR
--                              LOAN_PRINCIPAL, the NOT_DUE_PRINCIPAL row target V014 already admits for a line, restoring
--                              the available limit at commit), and only what exceeds every receivable it reaches held as
--                              credit. Its automatic collection (P11-TSK-019/-022) continues on a CLOSING line.
--     line_closure_request   - the customer's closure request (T-clo, MULTI_FACTOR): born once per line (UNIQUE (loan_id) -
--                              ten requests, one request), only for an ACTIVE line, with the requester's key and its
--                              outcome at the request: CLOSING (the line takes no further draw, ADR-0100 point 8) or
--                              CLOSED (nothing outstanding - the line closes CLOSED_BY_CUSTOMER in the same transaction,
--                              its credit balance refunded). INSERT-only; a deferred check holds it to its loan's state at
--                              commit.
--     the closure            - V014's loan_closure, now also a line's: CLOSED_BY_CUSTOMER, only beside its closure request,
--                              from ACTIVE (at the request) or CLOSING (in the transaction that zeroes the last receivable
--                              - a repayment, an automatic collection, or the servicing step after a credit-balance
--                              application), every statement paid in full.
--
-- The loan's edge function is replaced: V014's edges, plus a line's ACTIVE -> CLOSING beside its request (outcome CLOSING),
-- ACTIVE -> CLOSED (CLOSED_BY_CUSTOMER) beside its request (outcome CLOSED) and its closure, and CLOSING -> CLOSED
-- (CLOSED_BY_CUSTOMER) beside its closure. CLOSING -> ACTIVE stays refused for every role (a closing line is never reopened
-- - a new application), as does anything out of CLOSED (INV-LIFE-04).
--
-- The ledger side of "nothing outstanding" - every receivable zero, every elapsed date accrued - is the closing
-- transaction's own, under the line's row lock (L3): lending's triggers never read the ledger.

-- ---------------------------------------------------------------------------------------------
-- The closure request.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.line_closure_request (
    id              uuid        NOT NULL,
    loan_id         uuid        NOT NULL,
    request_key     text        NOT NULL,
    outcome         text        NOT NULL,
    requested_at    timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT line_closure_request_pk PRIMARY KEY (id),
    -- One closure request per line (INV-IDEM-01, INV-LIFE-04): beneath the door's claim and the line's row lock (L3).
    CONSTRAINT line_closure_request_loan_unique UNIQUE (loan_id),
    CONSTRAINT line_closure_request_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT line_closure_request_outcome_is_known CHECK (outcome IN ('CLOSING', 'CLOSED')),
    CONSTRAINT line_closure_request_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT line_closure_request_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.line_closure_request IS
    'A credit line''s closure request (P11-TSK-023; ADR-0100 point 8, PHASE_11_PLAN.md section 5.3): the customer''s MULTI_FACTOR act, born once per line while it is ACTIVE, with the requester''s key and the outcome at the request - CLOSING (no further draws; the line closes in the transaction that zeroes its last receivable) or CLOSED (nothing outstanding: closed CLOSED_BY_CUSTOMER in the same transaction, the credit balance refunded). Never reopened: a closing line is never ACTIVE again. INSERT-only for every role. CONFIDENTIAL (the loan reference).';

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
    NEW.requested_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER line_closure_request_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.line_closure_request
    FOR EACH ROW
    EXECUTE FUNCTION lending.line_closure_request_is_born_once();

CREATE TRIGGER line_closure_request_is_never_truncated
    BEFORE TRUNCATE ON lending.line_closure_request
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- At commit, for every writer: the request's line is in the state its outcome names - CLOSING, or CLOSED by the customer.
CREATE OR REPLACE FUNCTION lending.line_closure_request_moves_its_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan l
                    WHERE l.id = NEW.loan_id
                      AND ((NEW.outcome = 'CLOSING' AND l.status = 'CLOSING')
                           OR (NEW.outcome = 'CLOSED' AND l.status = 'CLOSED'
                               AND l.closure_reason = 'CLOSED_BY_CUSTOMER'))) THEN
        RAISE EXCEPTION 'a closure request committed without its line in the state its outcome names (P11-TSK-023, INV-LIFE-04)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER line_closure_request_moves_its_line
    AFTER INSERT ON lending.line_closure_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.line_closure_request_moves_its_line();

-- ---------------------------------------------------------------------------------------------
-- V017's repayment trigger, replaced: a line ACTIVE or CLOSING takes the customer's repayment and its automatic collection.
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
    -- An ACTIVE instalment loan, or a credit line ACTIVE or CLOSING - the customer's repayment or its automatic collection
    -- (a line's payoff is P11-TSK-026's) - and its pinned servicing zone. A CLOSED or CANCELLED account takes no money
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
                        AND NEW.kind IN ('CUSTOMER', 'AUTO_COLLECTION'))) THEN
        RAISE EXCEPTION 'a repayment is received only by an ACTIVE instalment loan, or by a credit line ACTIVE or CLOSING (P11-TSK-018, P11-TSK-023, INV-LND-08)';
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
-- V017's collection attempt trigger, replaced: a CLOSING line's statement is collected as an ACTIVE one's, and a line its
-- own collection has just closed admits the attempt naming that collection (as an instalment loan does, V015).
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
-- V014's closure trigger, replaced: an ACTIVE instalment loan closes REPAID (as before); a credit line closes
-- CLOSED_BY_CUSTOMER, only beside its closure request, from ACTIVE (at the request) or CLOSING, every statement paid.
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
    -- An ACTIVE instalment loan closing REPAID, or a credit line ACTIVE or CLOSING closing CLOSED_BY_CUSTOMER beside the
    -- customer's closure request (P11-TSK-023). A payoff's PAID_OFF_EARLY is P11-TSK-026's, which replaces this function.
    SELECT l.* INTO account FROM lending.loan l WHERE l.id = NEW.loan_id;
    IF account.id IS NULL
            OR NOT ((account.kind = 'INSTALMENT' AND account.status = 'ACTIVE' AND NEW.reason = 'REPAID')
                    OR (account.kind = 'REVOLVING' AND account.status IN ('ACTIVE', 'CLOSING')
                        AND NEW.reason = 'CLOSED_BY_CUSTOMER'
                        AND EXISTS (SELECT 1 FROM lending.line_closure_request q WHERE q.loan_id = NEW.loan_id))) THEN
        RAISE EXCEPTION 'an ACTIVE instalment loan closes REPAID, and a credit line CLOSED_BY_CUSTOMER beside its closure request; nothing else closes here (P11-TSK-018, P11-TSK-023, INV-LND-08)';
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
-- The loan's edge function, replaced: V014's edges, plus a credit line's closure edges.
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
    -- The closure (P11-TSK-018): an instalment loan ACTIVE -> CLOSED (REPAID), only beside its loan_closure row born in
    -- this transaction for the same reason (which holds the rows' side of "every receivable zero"); the terminal instant is
    -- the trigger's. A payoff's PAID_OFF_EARLY (P11-TSK-026) replaces this function.
    IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' AND OLD.kind = 'INSTALMENT' THEN
        IF NEW.closure_reason IS DISTINCT FROM 'REPAID' OR NEW.cancellation_reason IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM lending.loan_closure c
                                WHERE c.loan_id = OLD.id AND c.reason = NEW.closure_reason) THEN
            RAISE EXCEPTION 'an instalment loan is CLOSED (REPAID) only beside its closure (P11-TSK-018, INV-LND-08)';
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
    -- Every other edge is a later task's or never: a payoff (P11-TSK-026) replaces this function and admits its edge beside
    -- its own fact. Out of CLOSED and CANCELLED nothing, ever (INV-LIFE-04); CLOSING -> ACTIVE never (a closing line is
    -- never reopened - a new application); ACTIVE -> CANCELLED and ACTIVE -> PENDING_DISBURSEMENT never
    -- (LENDING_LIFECYCLES.md section 4.5).
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge (P11-TSK-023, INV-LIFE-04)', OLD.status, NEW.status;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the request SELECT and INSERT only - never UPDATE, never DELETE. The loan's status and closure reason are
-- already granted (V008, V014); its edges are the function's.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.line_closure_request TO finapp_app;
