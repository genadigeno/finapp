-- The loan application, both products (P11-TSK-010; PHASE_11_PLAN.md sections 5.1, 7.1 T-app and 8,
-- LENDING_LIFECYCLES.md section 4.3; INV-LIFE-01, INV-LIFE-02, INV-IDEM-01).
--
-- A LOAN APPLICATION is one customer's request for one product - a PERSONAL_LOAN or a CREDIT_LINE. Its submission is
-- ONE customer act: in one transaction the application is born and credit's decision request is opened beside it
-- (DecisionRequests.submit, through app's LoanCreditDecisions adapter - lending has no build edge to credit, A16), so
-- an application exists exactly when its credit request does:
--
--     (keyed submission) SUBMITTED --credit request opened, same transaction--> AWAITING_DECISION
--     AWAITING_DECISION --> OFFERED | DECLINED | CLOSED_UNDECIDED      (the origination step, P11-TSK-011)
--     AWAITING_DECISION --> WITHDRAWN                                   (the customer, while credit's request is cancellable)
--
-- SUBMITTED is transient: the row is born in it and moves on in the same transaction, so the history records the
-- submission - and a deferred trigger refuses to COMMIT a row still SUBMITTED, for every writer. Terminal: OFFERED (the
-- offer continues the story), DECLINED, CLOSED_UNDECIDED, WITHDRAWN. A decline is never reconsidered - a new
-- application.
--
-- NO MONEY HERE, deliberately. The amount or limit applied for, the term and the declared monthly income and
-- expenditure are credit's request's (credit V010) - the one record of what was asked, judged against credit's product
-- bounds, the one truth for those bounds (the P11-TSK-002 / -005 refinement). The offer carries exactly the APPROVED
-- figures (A20), read from credit's decision. So this table is a machine row with no `*_minor` column and takes the
-- machine tables' column-scoped UPDATE of its status (PHASE_11_PLAN.md section 8; LendingSchemaHasNoMutableMoneyTest).
-- What lending itself records is what lending will need and credit does not hold: the product, credit's request id,
-- the terms version ACTIVE at submission (read FOR SHARE - informational: the offer pins the version active at the
-- offering instant, P11-TSK-011), the repayment or statement day chosen from that version's allowed days, the
-- repayment method and the payout destination PREFERENCE (verified only at acceptance, P11-TSK-012 - no destination
-- and no account identifier enters lending, INV-RAIL-03).
--
-- Held for EVERY writer: the edge trigger (born SUBMITTED on an ACTIVE terms version with a day it offers; identity
-- frozen; only the machine's edges; terminal states final), the deferred never-rests-SUBMITTED trigger, UNIQUE
-- (decision_request_id), at most one OPEN application per party and product (a partial unique - the backstop beneath
-- credit's own one-open-request partial unique, which is the arbiter a racing second key meets first), the status
-- CHECK and the append-only history. No cross-schema foreign key (ADR-0029): credit's request is named by id.
-- P11-TSK-011 adds its permit column and the "-> OFFERED only beside its offer" rank by replacing the edge function.

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_application (
    id                   uuid        NOT NULL,
    party_id             uuid        NOT NULL,
    product              text        NOT NULL,
    status               text        NOT NULL,
    decision_request_id  uuid        NOT NULL,
    terms_version_id     uuid        NOT NULL,
    repayment_day        smallint    NOT NULL,
    repayment_method     text        NOT NULL,
    payout_preference    text        NOT NULL,
    submitted_by         text        NOT NULL,
    submitted_by_type    text        NOT NULL,
    submitted_at         timestamptz NOT NULL,
    closed_at            timestamptz,
    correlation_id       text        NOT NULL,
    CONSTRAINT loan_application_pk PRIMARY KEY (id),
    -- One application per credit request: an application exists exactly when its request does (credit's id, by value).
    CONSTRAINT loan_application_decision_request_unique UNIQUE (decision_request_id),
    -- The terms version recorded at submission is the product's own (the composite key pins the product).
    CONSTRAINT loan_application_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES lending.loan_terms_version (id, product),
    -- LoanProduct.
    CONSTRAINT loan_application_product_is_offered CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    -- LoanApplicationStatus - a membership list of the machine's states.
    CONSTRAINT loan_application_status_is_known CHECK (
        status IN ('SUBMITTED', 'AWAITING_DECISION', 'OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN')),
    -- A repayment (loan) or statement (line) day every month has (LoanTerms.LAST_ALLOWED_DAY); the recorded version's
    -- own allowed days are judged by the trigger.
    CONSTRAINT loan_application_repayment_day_in_every_month CHECK (repayment_day BETWEEN 1 AND 28),
    -- RepaymentMethod: from the borrower's wallet - the only method Phase 11 implements (A11: no direct debit, no
    -- external inbound repayment).
    CONSTRAINT loan_application_repayment_method_implemented CHECK (repayment_method = 'WALLET'),
    -- PayoutPreference: the wallet, or an external account named and verified at acceptance - a line draws to the
    -- wallet only (A25).
    CONSTRAINT loan_application_payout_preference_known CHECK (payout_preference IN ('WALLET', 'EXTERNAL_ACCOUNT')),
    CONSTRAINT loan_application_line_pays_out_to_the_wallet CHECK (
        product = 'PERSONAL_LOAN' OR payout_preference = 'WALLET'),
    -- The customer's own act (PHASE_11_PLAN.md section 11: origination is never a person's act on another's behalf).
    CONSTRAINT loan_application_submitted_by_the_customer CHECK (submitted_by_type = 'CUSTOMER'),
    CONSTRAINT loan_application_closed_coherent CHECK (
        (status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN')) = (closed_at IS NOT NULL)),
    CONSTRAINT loan_application_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_application IS
    'A customer''s loan application (P11-TSK-010, LENDING_LIFECYCLES.md section 4.3): SUBMITTED (transient, never committed) -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED (P11-TSK-011) | WITHDRAWN. Born in the same transaction as credit''s decision request (UNIQUE decision_request_id) - an application exists iff its request does; at most one open per party and product. No money: the amount, term and declared figures are credit''s request''s. The terms version recorded is the one ACTIVE at submission - informational; the offer pins its own.';

-- At most one OPEN application per party and product - the backstop beneath credit's one-open-request partial unique.
-- It also holds when credit's request has closed while the application still awaits the origination step's closure
-- (P11-TSK-011): the application is open until lending closes it.
CREATE UNIQUE INDEX loan_application_one_open ON lending.loan_application (party_id, product)
    WHERE status IN ('SUBMITTED', 'AWAITING_DECISION');
CREATE INDEX loan_application_by_party ON lending.loan_application (party_id, submitted_at);

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
        NEW.submitted_at := transaction_timestamp();
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
    -- The machine's edges and no other - a terminal application never moves, and SUBMITTED is never skipped.
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')
            OR (OLD.status = 'AWAITING_DECISION'
                AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a loan application moves SUBMITTED -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN; % -> % is not an edge (P11-TSK-010)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure; nobody writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL ELSE transaction_timestamp() END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_application_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_application
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_application_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION lending.loan_application_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the loan applications and their history are never truncated (P11-TSK-010, INV-HIST-01)';
END;
$$;

CREATE TRIGGER loan_application_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_application
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_application_is_never_truncated();

-- SUBMITTED is transient: at COMMIT the application has moved on - its credit request opened in the same transaction.
CREATE OR REPLACE FUNCTION lending.loan_application_never_rests_submitted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM lending.loan_application a WHERE a.id = NEW.id AND a.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'a loan application committed SUBMITTED: its credit request is opened in the same transaction (P11-TSK-010)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_application_never_rests_submitted
    AFTER INSERT ON lending.loan_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_application_never_rests_submitted();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_application_event (
    id              uuid        NOT NULL,
    application_id  uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT loan_application_event_pk PRIMARY KEY (id),
    CONSTRAINT loan_application_event_application_fk FOREIGN KEY (application_id)
        REFERENCES lending.loan_application (id),
    -- LoanApplicationStatus, and its only edges.
    CONSTRAINT loan_application_event_edge CHECK (
        (from_status IS NULL AND to_status = 'SUBMITTED')
        OR (from_status = 'SUBMITTED' AND to_status = 'AWAITING_DECISION')
        OR (from_status = 'AWAITING_DECISION' AND to_status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))),
    -- A reason CODE, never free text: nothing a customer typed is stored here.
    CONSTRAINT loan_application_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT loan_application_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_application_event_by_application ON lending.loan_application_event (application_id, occurred_at);

COMMENT ON TABLE lending.loan_application_event IS
    'A loan application''s machine as history (P11-TSK-010): every edge - the transient SUBMITTED included - its actor, reason code and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION lending.loan_application_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan application event is append-only (P11-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := transaction_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_application_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_application_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_application_event_is_append_only();

CREATE TRIGGER loan_application_event_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_application_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_application_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's status alone moves; the history INSERT-only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan_application TO finapp_app;
GRANT UPDATE (status) ON lending.loan_application TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_application_event TO finapp_app;
