-- The BNPL application at checkout (P12-TSK-010; PHASE_12_PLAN.md sections 5.1, 7.1 T-app and 8, BNPL_LIFECYCLES.md
-- section 4.2; INV-BNPL-03, INV-LIFE-01, INV-LIFE-02, INV-IDEM-01).
--
-- A BNPL APPLICATION is one customer's choice of one BNPL product and plan inside one checkout session. Its submission
-- is ONE customer act: in one transaction the application is born and credit's decision request is opened beside it
-- (DecisionRequests.submit from the BNPL_APPLICATION origin, through app's CreditBnplDecisions - bnpl has no build edge
-- to credit, ADR-0101 decision 6), so an application exists exactly when its credit request does (owner decision B5:
-- one decision per purchase):
--
--     (keyed submission) SUBMITTED --credit request opened, same transaction--> AWAITING_DECISION
--     AWAITING_DECISION --> OFFERED | DECLINED | CLOSED_UNDECIDED      (the origination step, P12-TSK-011)
--     AWAITING_DECISION --> WITHDRAWN                                   (the customer, while credit's request is cancellable)
--
-- SUBMITTED is transient: the row is born in it and moves on in the same transaction, so the history records the
-- submission - and a deferred trigger refuses to COMMIT a row still SUBMITTED, for every writer (lending V004's
-- pattern). Terminal: OFFERED (the offer continues the story, P12-TSK-011), DECLINED, CLOSED_UNDECIDED (its reason
-- always said), WITHDRAWN. A decline is never reconsidered - a new application.
--
-- NO MONEY HERE, deliberately (INV-BNPL-01). The purchase amount is the checkout session's gross - checkout's, fixed at
-- the session's creation - and credit's request's requested amount, the one record of what was asked, judged against
-- credit's product bounds (A16). A money column on this row would make it a money table, which may never take the
-- column-scoped UPDATE its machine needs (BnplSchemaHasNoMutableMoneyTest); so the application holds the product, the
-- plan, the session, the merchant, credit's request and the terms version ACTIVE at submission (read FOR SHARE -
-- informational: the offer pins the version active at its own instant, P12-TSK-011).
--
-- Held for EVERY writer: the edge trigger (born SUBMITTED on its product's ACTIVE terms version with a plan that version
-- offers; identity frozen; only the machine's edges; terminal states final; a permit-only update only while
-- AWAITING_DECISION; every stamp clock_timestamp() - the instant after the act's locks, never the transaction's start,
-- lending V030's repair - and clamped forward of the submission on a backward clock), the deferred never-rests-SUBMITTED
-- trigger, UNIQUE (decision_request_id), at most one OPEN application per checkout session (a partial unique over
-- SUBMITTED, AWAITING_DECISION and OFFERED - OFFERED is terminal, so an offered application holds its session for good,
-- its one offer the session's; whether a declined or expired offer frees the session is P12-TSK-011's to settle)
-- and per party and product (over SUBMITTED and AWAITING_DECISION - the backstop beneath credit's own one-open-request
-- partial unique, which is the arbiter a second key for the same party and product meets first), the status and reason
-- CHECKs and the append-only history. No cross-schema foreign key (ADR-0029): the session, the merchant and credit's
-- request are named by id; their preconditions are the domain's, through app's ports, in the same transaction.
-- P12-TSK-011 adds the "-> OFFERED only beside its offer" rank by replacing the edge function.

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_application (
    id                   uuid        NOT NULL,
    party_id             uuid        NOT NULL,
    product              text        NOT NULL,
    plan                 text        NOT NULL,
    status               text        NOT NULL,
    closure_reason       text,
    checkout_session_id  uuid        NOT NULL,
    merchant_id          uuid        NOT NULL,
    decision_request_id  uuid        NOT NULL,
    terms_version_id     uuid        NOT NULL,
    submitted_by         text        NOT NULL,
    submitted_by_type    text        NOT NULL,
    submitted_at         timestamptz NOT NULL,
    closed_at            timestamptz,
    next_step_at         timestamptz NOT NULL,
    correlation_id       text        NOT NULL,
    CONSTRAINT bnpl_application_pk PRIMARY KEY (id),
    -- One application per credit request: an application exists exactly when its request does (credit's id, by value).
    CONSTRAINT bnpl_application_decision_request_unique UNIQUE (decision_request_id),
    -- The terms version recorded at submission is the product's own (the composite key pins the product).
    CONSTRAINT bnpl_application_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES bnpl.bnpl_terms_version (id, product),
    -- BnplProduct.
    CONSTRAINT bnpl_application_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    -- BnplPlan, each of its one product (BnplProduct's plan lists).
    CONSTRAINT bnpl_application_plan_of_its_product CHECK (
        (product = 'BNPL_PAY_IN_4' AND plan = 'PAY_IN_4')
        OR (product = 'BNPL_PAY_IN_N' AND plan IN ('PAY_IN_3_MONTHS', 'PAY_IN_6_MONTHS', 'PAY_IN_12_MONTHS'))),
    -- BnplApplicationStatus - a membership list of the machine's states.
    CONSTRAINT bnpl_application_status_is_known CHECK (
        status IN ('SUBMITTED', 'AWAITING_DECISION', 'OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN')),
    -- BnplApplicationClosureReason - always said for CLOSED_UNDECIDED, and only there.
    CONSTRAINT bnpl_application_closure_reason_is_known CHECK (
        closure_reason IS NULL OR closure_reason IN ('CREDIT_REQUEST_CANCELLED', 'CREDIT_REQUEST_EXPIRED',
            'CREDIT_REQUEST_ABANDONED', 'SESSION_CLOSED', 'APPROVED_BELOW_PURCHASE', 'PRODUCT_NOT_OFFERED',
            'DECISION_LAPSED')),
    CONSTRAINT bnpl_application_closure_reason_coherent CHECK (
        (status = 'CLOSED_UNDECIDED') = (closure_reason IS NOT NULL)),
    -- The customer's own act (PHASE_12_PLAN.md section 11: origination is never a person's act on another's behalf).
    CONSTRAINT bnpl_application_submitted_by_the_customer CHECK (submitted_by_type = 'CUSTOMER'),
    CONSTRAINT bnpl_application_closed_coherent CHECK (
        (status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN')) = (closed_at IS NOT NULL)),
    CONSTRAINT bnpl_application_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_application IS
    'A customer''s BNPL application at checkout (P12-TSK-010, BNPL_LIFECYCLES.md section 4.2): SUBMITTED (transient, never committed) -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED (P12-TSK-011) | WITHDRAWN. Born in the same transaction as credit''s decision request from the BNPL origin (UNIQUE decision_request_id) - an application exists iff its request does; at most one open per checkout session (INV-BNPL-03) and per party and product. No money: the amount is the session''s gross and credit''s request''s. The terms version recorded is the one ACTIVE at submission - informational; the offer pins its own.';

-- At most one OPEN application per checkout session (INV-BNPL-03) - the arbiter ten keys for one session meet; an
-- OFFERED application holds the session for good - OFFERED is terminal and the offer (P12-TSK-011) is the session's one;
-- whether a declined or expired offer should free it is -011's to settle. A decline, a closure or a withdrawal of the
-- application frees it: a new application, never a reconsideration.
CREATE UNIQUE INDEX bnpl_application_one_open_per_session ON bnpl.bnpl_application (checkout_session_id)
    WHERE status IN ('SUBMITTED', 'AWAITING_DECISION', 'OFFERED');
-- At most one application per party and product awaiting a decision - the backstop beneath credit's one-open-request
-- partial unique; it also holds when credit's request has closed while the application awaits the origination step's
-- closure (P12-TSK-011): the application is open until bnpl closes it.
CREATE UNIQUE INDEX bnpl_application_one_open_per_party_product ON bnpl.bnpl_application (party_id, product)
    WHERE status IN ('SUBMITTED', 'AWAITING_DECISION');
CREATE INDEX bnpl_application_by_party ON bnpl.bnpl_application (party_id, submitted_at);
-- The origination sweep's claim (P12-TSK-011): the due permits of the applications awaiting a decision.
CREATE INDEX bnpl_application_awaiting_by_permit ON bnpl.bnpl_application (next_step_at, id)
    WHERE status = 'AWAITING_DECISION';

CREATE OR REPLACE FUNCTION bnpl.bnpl_application_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' OR NEW.closed_at IS NOT NULL OR NEW.closure_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL application is born SUBMITTED and open (P12-TSK-010)';
        END IF;
        -- Born on the product's ACTIVE terms version - the one the submission read FOR SHARE.
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms_version v
                        WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'a BNPL application records its product''s ACTIVE terms version (P12-TSK-010)';
        END IF;
        -- On a plan that version offers: pay-in-4's one fixed plan, or a pay-in-N term among its allowed terms.
        IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_terms t
                        WHERE t.terms_version_id = NEW.terms_version_id
                          AND ((NEW.plan = 'PAY_IN_4' AND t.instalment_count = 4)
                               OR (CASE NEW.plan WHEN 'PAY_IN_3_MONTHS' THEN 3 WHEN 'PAY_IN_6_MONTHS' THEN 6
                                       WHEN 'PAY_IN_12_MONTHS' THEN 12 END) = ANY (t.allowed_terms))) THEN
            RAISE EXCEPTION 'a BNPL application''s plan is one its terms version offers (P12-TSK-010)';
        END IF;
        -- The database stamps the submission after the act's locks, and the permit is due at once (the sweep's first
        -- look, P12-TSK-011).
        NEW.submitted_at := clock_timestamp();
        NEW.next_step_at := NEW.submitted_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL application is never deleted (P12-TSK-010, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.decision_request_id IS DISTINCT FROM OLD.decision_request_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.submitted_by IS DISTINCT FROM OLD.submitted_by
            OR NEW.submitted_by_type IS DISTINCT FROM OLD.submitted_by_type
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL application''s identity and choices are frozen from birth (P12-TSK-010)';
    END IF;
    IF NEW.status = OLD.status THEN
        -- A permit-only update (the sweep's claim, a hint - P12-TSK-011), admitted only while a decision is awaited.
        IF OLD.status <> 'AWAITING_DECISION' OR NEW.closure_reason IS DISTINCT FROM OLD.closure_reason
                OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'a BNPL application moves only its permit, and only while AWAITING_DECISION (P12-TSK-010)';
        END IF;
        RETURN NEW;
    END IF;
    -- The machine's edges and no other - a terminal application never moves, and SUBMITTED is never skipped.
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')
            OR (OLD.status = 'AWAITING_DECISION'
                AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a BNPL application moves SUBMITTED -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN; % -> % is not an edge (P12-TSK-010)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure after the act's locks, never before the submission on a backward clock; nobody
    -- writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL
                          ELSE GREATEST(clock_timestamp(), OLD.submitted_at) END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_application_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_application
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_application_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION bnpl.bnpl_application_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the BNPL applications and their history are never truncated (P12-TSK-010, INV-HIST-01)';
END;
$$;

CREATE TRIGGER bnpl_application_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_application
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_application_is_never_truncated();

-- SUBMITTED is transient: at COMMIT the application has moved on - its credit request opened in the same transaction.
CREATE OR REPLACE FUNCTION bnpl.bnpl_application_never_rests_submitted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_application a WHERE a.id = NEW.id AND a.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'a BNPL application committed SUBMITTED: its credit request is opened in the same transaction (P12-TSK-010)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_application_never_rests_submitted
    AFTER INSERT ON bnpl.bnpl_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_application_never_rests_submitted();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_application_event (
    id              uuid        NOT NULL,
    application_id  uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT bnpl_application_event_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_application_event_application_fk FOREIGN KEY (application_id)
        REFERENCES bnpl.bnpl_application (id),
    -- BnplApplicationStatus, and its only edges.
    CONSTRAINT bnpl_application_event_edge CHECK (
        (from_status IS NULL AND to_status = 'SUBMITTED')
        OR (from_status = 'SUBMITTED' AND to_status = 'AWAITING_DECISION')
        OR (from_status = 'AWAITING_DECISION' AND to_status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))),
    -- A reason CODE, never free text: nothing a customer typed is stored here.
    CONSTRAINT bnpl_application_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT bnpl_application_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_application_event_by_application ON bnpl.bnpl_application_event (application_id, occurred_at);

COMMENT ON TABLE bnpl.bnpl_application_event IS
    'A BNPL application''s machine as history (P12-TSK-010): every edge - the transient SUBMITTED included - its actor, reason code and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_application_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL application event is append-only (P12-TSK-010, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_application_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_application_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_application_event_is_append_only();

CREATE TRIGGER bnpl_application_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_application_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_application_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's status, closure reason and permit alone move; the history INSERT-only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_application TO finapp_app;
GRANT UPDATE (status, closure_reason, next_step_at) ON bnpl.bnpl_application TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_application_event TO finapp_app;
