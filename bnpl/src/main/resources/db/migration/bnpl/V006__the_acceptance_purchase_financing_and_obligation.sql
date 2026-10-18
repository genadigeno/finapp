-- The acceptance: the BNPL purchase, its merchant financing and the customer's obligation, atomically (P12-TSK-013;
-- PHASE_12_PLAN.md sections 5.3-5.5, 7.1 T-pur, 7.2, 8 and 12.4, BNPL_LIFECYCLES.md sections 4.4-4.8; INV-BNPL-02,
-- INV-BNPL-03, INV-BNPL-04, INV-BNPL-05, INV-HIST-01, INV-HIST-04, INV-LIFE-01; lending V006's shape, bnpl's own rows).
--
-- bnpl V005 is unassigned by the plan and stays a gap (Flyway applies V004 then V006; outOfOrder = false refuses any
-- later V005, so none may be written).
--
-- The customer's acceptance IS the purchase authorisation. In ONE transaction (BnplAcceptances, T-pur) the offer is
-- accepted, credit's decision consumed, the capital judged, and these rows are born, with the postings F1 (and for
-- pay-in-4 F2 and F3) committed beside them through the ledger:
--
--     purchase                    - the BNPL Purchase's machine row: the offer, the checkout session, the merchant and the
--                                   party by value, the product and plan, the business date stamped by the database after
--                                   the locks. NO MONEY: its gross is the financing row's (BnplSchemaHasNoMutableMoneyTest -
--                                   a table whose status moves carries no money column). Born AUTHORISED and moved
--                                   FINANCED in the same transaction, beside its financing and with checkout's order id;
--                                   never committed AUTHORISED. UNIQUE (checkout_session_id), UNIQUE (offer_id).
--     purchase_event              - its machine as history, append-only.
--     merchant_financing          - the merchant leg: gross, fee and net with CHECK (gross = fee + net), the fee schedule
--                                   version pinned (merchant's bnpl_fee_pin, by value) and the financing entry F1 by id.
--                                   INSERT-only. UNIQUE (purchase_id), UNIQUE (journal_entry_id).
--     bnpl_obligation             - the customer's repayment obligation: no money column, born ACTIVE, its purchase date
--                                   the purchase's, its servicing permit. UNIQUE (purchase_id), UNIQUE (agreement_id),
--                                   UNIQUE (decision_id). No edge is admitted here - servicing, refunds and payoffs replace
--                                   the edge function with their tasks.
--     bnpl_obligation_event       - its machine as history, append-only.
--     bnpl_agreement              - the financing agreement, version 1: an EXACT copy of the offer's canonical terms (text,
--                                   never jsonb) and their SHA-256, the document's SHA-256, the pinned terms version, the
--                                   engines and the merchant fee schedule version. INSERT-only and immutable for every
--                                   role. UNIQUE (obligation_id, version); later versions refused until P12-TSK-021.
--     bnpl_acceptance             - the evidence: the agreement version, the offer, the two hashes, the identity, session
--                                   and assurance (MULTI_FACTOR by CHECK), the channel, the client address and agent, the
--                                   instant on the database clock. INSERT-only.
--     bnpl_schedule, _instalment  - the BNPL Schedule, version 1, from the pinned engine over the purchase's own business
--                                   date: the instalments' principal sums to the schedule's principal, the financed
--                                   gross, held by a deferred trigger at commit (INV-BNPL-05). INSERT-only.
--     bnpl_billing                - born-once billing of an instalment (pay-in-4's instalment 1 at purchase, F2);
--                                   UNIQUE (instalment_id). Extended by P12-TSK-015.
--     bnpl_repayment, _allocation - born-once repayment (pay-in-4's instalment 1 collected from the wallet at purchase,
--                                   kind AT_PURCHASE, F3) and its allocation, the lines summing to the amount at commit;
--                                   UNIQUE (obligation_id, kind, request_key). Extended by P12-TSK-018.
--
-- THE PRINCIPAL VALUE FLOOR from the first table (the Phase 11 gate's R1, lending V030's shape):
-- bnpl.principal_value_floor(obligation) - the purchase's business date until the first billing, then the latest billing's
-- value date itself (so a same-day refund of a pay-in-4 purchase is dated the purchase's date) - and P12-TSK-017 adds the
-- day after the latest accrual. Every principal-moving fact born here (billing, repayment) is refused before it and after
-- the later of the latest date the clock showed and it.
--
-- INV-BNPL-02 at the database: deferred constraint triggers make a purchase, its financing and its obligation one fact -
-- none commits without the others (and the purchase only FINANCED, beside its ACCEPTED offer, agreement version 1, its
-- evidence and schedule version 1). The financing entry is named by id (NOT NULL, UNIQUE): NO bnpl trigger reads a ledger
-- table - each module migrates its own schema in no guaranteed order (ADR-0006/0011, the P11-TSK-013 refinement), so the
-- entry's existence and its lines' agreement with the row are the one transaction's and the three-way proof's
-- (P12-TSK-025), as lending's acceptance holds credit's consumption (the P11-TSK-012 refinement).
--
-- The offer's edge function is replaced (V004's body verbatim but for ACCEPTED): OFFERED -> ACCEPTED only beside its
-- FINANCED purchase, and refused once the statement's own start has reached the expiry - the complement of the sweep's
-- expiry, bracketing the domain's conditional (expires_at > clock_timestamp() after the row lock), so exactly one of
-- ACCEPTED and EXPIRED at the boundary.
--
-- No cross-schema foreign key (ADR-0029): credit's decision, checkout's session and order, the merchant, the fee schedule
-- version and the journal entries are named by id; their state is the domain's to judge through app's ports in the same
-- transaction. Every instant is the database's, clock_timestamp() after the act's locks.

-- ---------------------------------------------------------------------------------------------
-- The business date, in a servicing zone, as the database clock shows it.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.business_date(instant timestamptz, servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT (instant AT TIME ZONE servicing_zone)::date
$$;

COMMENT ON FUNCTION bnpl.business_date(timestamptz, text) IS
    'The business date of an instant in the pinned servicing zone (P12-TSK-013; A22 - UTC).';

CREATE OR REPLACE FUNCTION bnpl.business_date_latest(servicing_zone text)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT bnpl.business_date(GREATEST(statement_timestamp(), transaction_timestamp(), clock_timestamp()), servicing_zone)
$$;

COMMENT ON FUNCTION bnpl.business_date_latest(text) IS
    'The latest business date in the pinned zone the database clock has shown this transaction (P12-TSK-013; lending V028''s shape): every "never after today" rank''s today.';

-- ---------------------------------------------------------------------------------------------
-- The BNPL Purchase: the machine row, no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.purchase (
    id                   uuid        NOT NULL,
    offer_id             uuid        NOT NULL,
    checkout_session_id  uuid        NOT NULL,
    party_id             uuid        NOT NULL,
    merchant_id          uuid        NOT NULL,
    product              text        NOT NULL,
    plan                 text        NOT NULL,
    currency             char(3)     NOT NULL,
    status               text        NOT NULL,
    servicing_zone       text        NOT NULL,
    business_date        date        NOT NULL,
    authorised_at        timestamptz NOT NULL,
    financed_at          timestamptz,
    order_id             uuid,
    correlation_id       text        NOT NULL,
    CONSTRAINT purchase_pk PRIMARY KEY (id),
    -- One purchase per checkout session (checkout's id, by value) - a session is paid by at most one BNPL purchase
    -- (INV-BNPL-03; checkout V004's financed_by_ref beside it, exclusive with a payment intent).
    CONSTRAINT purchase_checkout_session_unique UNIQUE (checkout_session_id),
    -- One purchase per offer - and so per decision (bnpl_offer UNIQUE (decision_id)).
    CONSTRAINT purchase_offer_unique UNIQUE (offer_id),
    CONSTRAINT purchase_offer_fk FOREIGN KEY (offer_id) REFERENCES bnpl.bnpl_offer (id),
    CONSTRAINT purchase_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    CONSTRAINT purchase_plan_of_its_product CHECK (
        (product = 'BNPL_PAY_IN_4' AND plan = 'PAY_IN_4')
        OR (product = 'BNPL_PAY_IN_N' AND plan IN ('PAY_IN_3_MONTHS', 'PAY_IN_6_MONTHS', 'PAY_IN_12_MONTHS'))),
    CONSTRAINT purchase_currency_is_the_products CHECK (currency = 'EUR'),
    -- BnplPurchaseStatus - a membership list of the machine's states.
    CONSTRAINT purchase_status_is_known CHECK (status IN ('AUTHORISED', 'FINANCED', 'FULLY_REFUNDED')),
    -- Financed exactly when it carries the instant and checkout's order.
    CONSTRAINT purchase_financed_coherent CHECK (
        (status = 'AUTHORISED') = (financed_at IS NULL AND order_id IS NULL)),
    CONSTRAINT purchase_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.purchase IS
    'A BNPL Purchase (P12-TSK-013, BNPL_LIFECYCLES.md section 4.5): the financed commercial fact - born AUTHORISED by the customer''s acceptance and moved FINANCED in the same transaction, beside its merchant financing, its obligation and checkout''s order (never committed AUTHORISED); FULLY_REFUNDED when refunds total the gross (P12-TSK-021). One per checkout session and per offer. No money: its gross, fee and net are bnpl.merchant_financing''s.';

CREATE INDEX purchase_by_party ON bnpl.purchase (party_id, authorised_at);
CREATE INDEX purchase_by_merchant ON bnpl.purchase (merchant_id, authorised_at);

CREATE OR REPLACE FUNCTION bnpl.purchase_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer bnpl.bnpl_offer%ROWTYPE;
    application bnpl.bnpl_application%ROWTYPE;
    zone text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'AUTHORISED' OR NEW.financed_at IS NOT NULL OR NEW.order_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL purchase is born AUTHORISED (P12-TSK-013)';
        END IF;
        -- Born under its offer's row lock (B2), while it is OFFERED, for the offer's own party, product, plan and session,
        -- at the application's merchant. The expiry is judged at the offer's ACCEPTED edge, the last write of the act.
        SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
        IF NOT FOUND OR offer.status <> 'OFFERED' OR offer.party_id <> NEW.party_id OR offer.product <> NEW.product
                OR offer.plan <> NEW.plan OR offer.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL purchase is born for its own party''s OFFERED offer, of the same product, plan and checkout session (P12-TSK-013)';
        END IF;
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = offer.application_id;
        IF application.merchant_id <> NEW.merchant_id THEN
            RAISE EXCEPTION 'a BNPL purchase pays the merchant its checkout session pays (P12-TSK-013)';
        END IF;
        SELECT t.servicing_zone INTO zone FROM bnpl.bnpl_terms t WHERE t.terms_version_id = offer.terms_version_id;
        -- The instant and the business date are the database's, after the act's locks - never an instance's clock.
        NEW.servicing_zone := zone;
        NEW.authorised_at := clock_timestamp();
        NEW.business_date := bnpl.business_date(NEW.authorised_at, zone);
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL purchase is never deleted (P12-TSK-013, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.offer_id IS DISTINCT FROM OLD.offer_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.product IS DISTINCT FROM OLD.product OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.servicing_zone IS DISTINCT FROM OLD.servicing_zone
            OR NEW.business_date IS DISTINCT FROM OLD.business_date
            OR NEW.authorised_at IS DISTINCT FROM OLD.authorised_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL purchase''s identity is frozen from birth (P12-TSK-013)';
    END IF;
    -- AUTHORISED -> FINANCED, once, beside its merchant financing and with checkout's order. FULLY_REFUNDED is
    -- P12-TSK-021's edge, refused here until that task replaces this function. Never FINANCED -> AUTHORISED.
    IF NOT (OLD.status = 'AUTHORISED' AND NEW.status = 'FINANCED') THEN
        RAISE EXCEPTION 'a BNPL purchase moves AUTHORISED -> FINANCED here; % -> % is not an edge until its task admits it (P12-TSK-013)', OLD.status, NEW.status;
    END IF;
    IF NEW.order_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED with checkout''s order (P12-TSK-013)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL purchase is FINANCED only beside its merchant financing (P12-TSK-013, INV-BNPL-02)';
    END IF;
    NEW.financed_at := GREATEST(clock_timestamp(), OLD.authorised_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.purchase
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.purchase_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION bnpl.acceptance_rows_are_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the BNPL purchases, financings, obligations, agreements and their servicing facts are never truncated (P12-TSK-013, INV-HIST-01)';
END;
$$;

CREATE TRIGGER purchase_is_never_truncated
    BEFORE TRUNCATE ON bnpl.purchase
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE TABLE bnpl.purchase_event (
    id              uuid        NOT NULL,
    purchase_id     uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT purchase_event_pk PRIMARY KEY (id),
    CONSTRAINT purchase_event_purchase_fk FOREIGN KEY (purchase_id) REFERENCES bnpl.purchase (id),
    CONSTRAINT purchase_event_edge CHECK (
        (from_status IS NULL AND to_status = 'AUTHORISED')
        OR (from_status = 'AUTHORISED' AND to_status = 'FINANCED')
        OR (from_status = 'FINANCED' AND to_status = 'FULLY_REFUNDED')),
    CONSTRAINT purchase_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT purchase_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX purchase_event_by_purchase ON bnpl.purchase_event (purchase_id, occurred_at);

CREATE OR REPLACE FUNCTION bnpl.acceptance_history_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL purchase or obligation event is append-only (P12-TSK-013, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.purchase_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.acceptance_history_is_append_only();

CREATE TRIGGER purchase_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.purchase_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The merchant financing: the merchant leg's money, INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.merchant_financing (
    purchase_id              uuid        NOT NULL,
    journal_entry_id         uuid        NOT NULL,
    merchant_id              uuid        NOT NULL,
    currency                 char(3)     NOT NULL,
    scale                    smallint    NOT NULL,
    gross_minor              bigint      NOT NULL,
    fee_minor                bigint      NOT NULL,
    net_minor                bigint      NOT NULL,
    fee_schedule_version_id  uuid        NOT NULL,
    financed_at              timestamptz NOT NULL,
    -- One financing per purchase (INV-BNPL-03).
    CONSTRAINT merchant_financing_pk PRIMARY KEY (purchase_id),
    -- One financing entry per financing: the ledger key bnpl.financing:<purchase> beneath it.
    CONSTRAINT merchant_financing_journal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT merchant_financing_purchase_fk FOREIGN KEY (purchase_id) REFERENCES bnpl.purchase (id),
    CONSTRAINT merchant_financing_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- INV-BNPL-04: the financing conserves the purchase - G = f + net, 0 <= f < G, the merchant credited something.
    CONSTRAINT merchant_financing_conserves CHECK (
        gross_minor > 0 AND fee_minor >= 0 AND net_minor > 0 AND gross_minor = fee_minor + net_minor)
);

COMMENT ON TABLE bnpl.merchant_financing IS
    'A BNPL purchase''s merchant financing (P12-TSK-013, B2, INV-BNPL-02, INV-BNPL-04): the gross G financed, the merchant discount f and the net G - f credited to the merchant''s MERCHANT_PAYABLE by the entry F1 (bnpl.financing:<purchase>, its id here), the merchant''s BNPL fee schedule version pinned (merchant''s bnpl_fee_pin, by value). INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL).';

CREATE INDEX merchant_financing_by_merchant ON bnpl.merchant_financing (merchant_id, financed_at);

CREATE OR REPLACE FUNCTION bnpl.merchant_financing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a merchant financing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
    IF NOT FOUND OR purchase.status <> 'AUTHORISED' OR purchase.merchant_id <> NEW.merchant_id
            OR purchase.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a merchant financing is born beside its AUTHORISED purchase, for its merchant and currency (P12-TSK-013)';
    END IF;
    NEW.financed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_financing_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.merchant_financing
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.merchant_financing_is_born_once();

CREATE TRIGGER merchant_financing_is_never_truncated
    BEFORE TRUNCATE ON bnpl.merchant_financing
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The customer's repayment obligation: the machine row, no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_obligation (
    id                 uuid        NOT NULL,
    purchase_id        uuid        NOT NULL,
    agreement_id       uuid        NOT NULL,
    party_id           uuid        NOT NULL,
    decision_id        uuid        NOT NULL,
    product            text        NOT NULL,
    plan               text        NOT NULL,
    currency           char(3)     NOT NULL,
    status             text        NOT NULL,
    servicing_zone     text        NOT NULL,
    purchase_date      date        NOT NULL,
    opened_at          timestamptz NOT NULL,
    next_servicing_at  timestamptz NOT NULL,
    closed_at          timestamptz,
    closure_reason     text,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_obligation_pk PRIMARY KEY (id),
    -- One obligation per purchase, per agreement and per consumed decision (INV-BNPL-03).
    CONSTRAINT bnpl_obligation_purchase_unique UNIQUE (purchase_id),
    CONSTRAINT bnpl_obligation_agreement_unique UNIQUE (agreement_id),
    CONSTRAINT bnpl_obligation_decision_unique UNIQUE (decision_id),
    CONSTRAINT bnpl_obligation_purchase_fk FOREIGN KEY (purchase_id) REFERENCES bnpl.purchase (id),
    CONSTRAINT bnpl_obligation_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    CONSTRAINT bnpl_obligation_plan_of_its_product CHECK (
        (product = 'BNPL_PAY_IN_4' AND plan = 'PAY_IN_4')
        OR (product = 'BNPL_PAY_IN_N' AND plan IN ('PAY_IN_3_MONTHS', 'PAY_IN_6_MONTHS', 'PAY_IN_12_MONTHS'))),
    CONSTRAINT bnpl_obligation_currency_is_the_products CHECK (currency = 'EUR'),
    -- BnplObligationStatus.
    CONSTRAINT bnpl_obligation_status_is_known CHECK (status IN ('ACTIVE', 'CLOSED')),
    -- BnplClosureReason - always said for CLOSED, and only there.
    CONSTRAINT bnpl_obligation_closure_coherent CHECK (
        (status = 'ACTIVE' AND closed_at IS NULL AND closure_reason IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL
            AND closure_reason IN ('REPAID', 'PAID_OFF_EARLY', 'REFUNDED', 'REFUNDED_PARTIALLY_REPAID'))),
    CONSTRAINT bnpl_obligation_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_obligation IS
    'A customer repayment obligation (P12-TSK-013, BNPL_LIFECYCLES.md section 4.7): born ACTIVE by the acceptance beside its purchase, financing, agreement version 1 and schedule version 1; CLOSED by the transaction that zeroes its last receivable (servicing, refund and payoff tasks). No money column: every figure it owes is a balance of its six ledger accounts (INV-BNPL-01). One per purchase, agreement and consumed decision.';

CREATE INDEX bnpl_obligation_by_party ON bnpl.bnpl_obligation (party_id, opened_at);
CREATE INDEX bnpl_obligation_servicing_due ON bnpl.bnpl_obligation (next_servicing_at, id) WHERE status = 'ACTIVE';

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
    -- No edge and no permit move is admitted yet, for any writer: the servicing (P12-TSK-015), refund (-021) and payoff
    -- (-022) tasks replace this function with their own edges, and grant the columns they move.
    RAISE EXCEPTION 'a BNPL obligation does not move until its servicing task admits the edge (P12-TSK-013)';
END;
$$;

CREATE TRIGGER bnpl_obligation_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_obligation
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_obligation_permits_only_machine_edges();

CREATE TRIGGER bnpl_obligation_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_obligation
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE TABLE bnpl.bnpl_obligation_event (
    id              uuid        NOT NULL,
    obligation_id   uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT bnpl_obligation_event_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_obligation_event_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_obligation_event_edge CHECK (
        (from_status IS NULL AND to_status = 'ACTIVE') OR (from_status = 'ACTIVE' AND to_status = 'CLOSED')),
    CONSTRAINT bnpl_obligation_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT bnpl_obligation_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_obligation_event_by_obligation ON bnpl.bnpl_obligation_event (obligation_id, occurred_at);

CREATE TRIGGER bnpl_obligation_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_obligation_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.acceptance_history_is_append_only();

CREATE TRIGGER bnpl_obligation_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_obligation_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The financing agreement: versioned, immutable rows.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_agreement (
    agreement_id                uuid        NOT NULL,
    version                     integer     NOT NULL,
    obligation_id               uuid        NOT NULL,
    offer_id                    uuid,
    decision_id                 uuid        NOT NULL,
    terms_version_id            uuid        NOT NULL,
    product                     text        NOT NULL,
    plan                        text        NOT NULL,
    schedule_engine             text        NOT NULL,
    accrual_engine              text,
    allocation_engine           text        NOT NULL,
    refund_allocation_engine    text        NOT NULL,
    fee_schedule_version_id     uuid        NOT NULL,
    agreement_template_id       text        NOT NULL,
    agreement_template_version  integer     NOT NULL,
    canonical_format            smallint    NOT NULL,
    canonical_terms             text        NOT NULL,
    terms_sha256                bytea       NOT NULL,
    document_sha256             bytea       NOT NULL,
    effective_from              timestamptz NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT bnpl_agreement_pk PRIMARY KEY (agreement_id, version),
    -- One row per version of one obligation's agreement (INV-HIST-04): a change of terms is version n+1.
    CONSTRAINT bnpl_agreement_version_unique UNIQUE (obligation_id, version),
    CONSTRAINT bnpl_agreement_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_agreement_offer_fk FOREIGN KEY (offer_id) REFERENCES bnpl.bnpl_offer (id),
    CONSTRAINT bnpl_agreement_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES bnpl.bnpl_terms_version (id, product),
    -- Version 1 is the offer's, accepted; a later version is a refund's (P12-TSK-021), refused until then.
    CONSTRAINT bnpl_agreement_origin CHECK (version = 1 AND offer_id IS NOT NULL),
    CONSTRAINT bnpl_agreement_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    -- Each product's engines and no other's: pay-in-4 its own schedule engine and no accrual, pay-in-N lending's.
    CONSTRAINT bnpl_agreement_engines_coherent CHECK (
        (product = 'BNPL_PAY_IN_4' AND plan = 'PAY_IN_4' AND schedule_engine = 'BNPL_SCHEDULE_ENGINE_V1'
            AND accrual_engine IS NULL)
        OR (product = 'BNPL_PAY_IN_N' AND plan IN ('PAY_IN_3_MONTHS', 'PAY_IN_6_MONTHS', 'PAY_IN_12_MONTHS')
            AND schedule_engine = 'SCHEDULE_ENGINE_V1' AND accrual_engine = 'ACCRUAL_ENGINE_V1')),
    CONSTRAINT bnpl_agreement_allocation_engines CHECK (
        allocation_engine = 'ALLOCATION_ENGINE_V1' AND refund_allocation_engine = 'REFUND_ALLOCATION_ENGINE_V1'),
    -- The canonical terms, format 1 (BnplOfferTerms), copied byte for byte - and the hash IS their SHA-256.
    CONSTRAINT bnpl_agreement_canonical_shape CHECK (
        canonical_format = 1 AND char_length(canonical_terms) BETWEEN 2 AND 16384
        AND canonical_terms ~ '^\{"format":"1",[A-Za-z0-9_.:+",{}\[\]-]*\}$'),
    CONSTRAINT bnpl_agreement_hash_is_the_terms CHECK (
        octet_length(terms_sha256) = 32 AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))),
    CONSTRAINT bnpl_agreement_document_hash_shape CHECK (octet_length(document_sha256) = 32)
);

COMMENT ON TABLE bnpl.bnpl_agreement IS
    'A BNPL financing agreement''s versions (P12-TSK-013, BNPL_LIFECYCLES.md section 4.6, INV-HIST-04): version 1 the accepted offer''s canonical terms copied byte for byte (text, the hash held to the bytes by CHECK), the document''s hash, the pinned terms version, the schedule, accrual, allocation and refund allocation engines and the merchant fee schedule version. INSERT-only and immutable for every role; status lives on the obligation. A later version is a refund''s (P12-TSK-021).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
    offer bnpl.bnpl_offer%ROWTYPE;
    terms bnpl.bnpl_offer_terms%ROWTYPE;
    pinned bnpl.bnpl_terms%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL agreement is immutable from insert, for every role (P12-TSK-013, INV-HIST-04)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    IF NOT FOUND OR obligation.agreement_id <> NEW.agreement_id OR obligation.decision_id <> NEW.decision_id
            OR obligation.product <> NEW.product OR obligation.plan <> NEW.plan THEN
        RAISE EXCEPTION 'a BNPL agreement is its own obligation''s, for its decision, product and plan (P12-TSK-013)';
    END IF;
    -- Version 1 is an EXACT copy of the OFFERED offer's terms the obligation's purchase accepts (INV-HIST-04).
    SELECT * INTO offer FROM bnpl.bnpl_offer o WHERE o.id = NEW.offer_id;
    SELECT * INTO terms FROM bnpl.bnpl_offer_terms t WHERE t.offer_id = NEW.offer_id;
    IF offer.status IS DISTINCT FROM 'OFFERED'
            OR NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.id = obligation.purchase_id AND p.offer_id = NEW.offer_id)
            OR offer.decision_id <> NEW.decision_id OR offer.terms_version_id <> NEW.terms_version_id
            OR terms.canonical_format <> NEW.canonical_format OR terms.canonical_terms <> NEW.canonical_terms
            OR terms.terms_sha256 <> NEW.terms_sha256 OR terms.document_sha256 <> NEW.document_sha256
            OR terms.schedule_engine <> NEW.schedule_engine THEN
        RAISE EXCEPTION 'a BNPL agreement''s version 1 is an exact copy of its purchase''s OFFERED offer''s terms (P12-TSK-013, INV-HIST-04)';
    END IF;
    -- The engines and the template the pinned terms version names.
    SELECT * INTO pinned FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.terms_version_id;
    IF pinned.allocation_engine <> NEW.allocation_engine
            OR pinned.refund_allocation_engine <> NEW.refund_allocation_engine
            OR pinned.accrual_engine IS DISTINCT FROM NEW.accrual_engine
            OR pinned.agreement_template_id <> NEW.agreement_template_id
            OR pinned.agreement_template_version <> NEW.agreement_template_version THEN
        RAISE EXCEPTION 'a BNPL agreement pins the engines and the template of its terms version (P12-TSK-013, INV-HIST-04)';
    END IF;
    NEW.created_at := clock_timestamp();
    NEW.effective_from := NEW.created_at;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_agreement_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_agreement
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_agreement_is_born_once();

CREATE TRIGGER bnpl_agreement_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_agreement
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The acceptance evidence.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_acceptance (
    id                 uuid        NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    offer_id           uuid        NOT NULL,
    terms_sha256       bytea       NOT NULL,
    document_sha256    bytea       NOT NULL,
    identity_id        uuid        NOT NULL,
    session_id         uuid        NOT NULL,
    assurance          text        NOT NULL,
    channel            text        NOT NULL,
    client_ip          inet,
    user_agent         text,
    accepted_at        timestamptz NOT NULL,
    correlation_id     text        NOT NULL,
    CONSTRAINT bnpl_acceptance_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_acceptance_agreement_unique UNIQUE (agreement_id, agreement_version),
    CONSTRAINT bnpl_acceptance_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    CONSTRAINT bnpl_acceptance_offer_fk FOREIGN KEY (offer_id) REFERENCES bnpl.bnpl_offer (id),
    -- A18: the acceptance creates credit and binds the customer - a MULTI_FACTOR session, always.
    CONSTRAINT bnpl_acceptance_multi_factor CHECK (assurance = 'MULTI_FACTOR'),
    CONSTRAINT bnpl_acceptance_channel_is_known CHECK (channel = 'API'),
    CONSTRAINT bnpl_acceptance_hashes_shape CHECK (octet_length(terms_sha256) = 32 AND octet_length(document_sha256) = 32),
    CONSTRAINT bnpl_acceptance_agent_bounded CHECK (user_agent IS NULL OR char_length(user_agent) BETWEEN 1 AND 512),
    CONSTRAINT bnpl_acceptance_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_acceptance IS
    'A BNPL acceptance''s evidence (P12-TSK-013, A18; INV-IDN-04 - acceptance is not consent): the agreement version accepted, the offer, the terms and document hashes, the identity, the session and its MULTI_FACTOR assurance, the channel, the client address and agent (CONFIDENTIAL) and the instant on the database clock. INSERT-only.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_acceptance_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL acceptance''s evidence is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- Bound to its agreement version: the same offer and the same two hashes.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a
                    WHERE a.agreement_id = NEW.agreement_id AND a.version = NEW.agreement_version
                      AND a.offer_id = NEW.offer_id AND a.terms_sha256 = NEW.terms_sha256
                      AND a.document_sha256 = NEW.document_sha256) THEN
        RAISE EXCEPTION 'a BNPL acceptance is its agreement version''s, over its offer''s two hashes (P12-TSK-013)';
    END IF;
    NEW.accepted_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_acceptance_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_acceptance
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_acceptance_is_born_once();

CREATE TRIGGER bnpl_acceptance_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_acceptance
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The BNPL Schedule and its instalments: INSERT-only, conserving at commit.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_schedule (
    id                uuid        NOT NULL,
    obligation_id     uuid        NOT NULL,
    schedule_version  integer     NOT NULL,
    engine            text        NOT NULL,
    currency          char(3)     NOT NULL,
    scale             smallint    NOT NULL,
    principal_minor   bigint      NOT NULL,
    start_date        date        NOT NULL,
    instalment_count  smallint    NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT bnpl_schedule_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_schedule_version_unique UNIQUE (obligation_id, schedule_version),
    CONSTRAINT bnpl_schedule_identity UNIQUE (id, obligation_id),
    CONSTRAINT bnpl_schedule_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    -- Version 1 here; a version n+1 is a refund's (P12-TSK-021).
    CONSTRAINT bnpl_schedule_version_one CHECK (schedule_version = 1),
    CONSTRAINT bnpl_schedule_engine_is_known CHECK (engine IN ('BNPL_SCHEDULE_ENGINE_V1', 'SCHEDULE_ENGINE_V1')),
    CONSTRAINT bnpl_schedule_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_schedule_principal_positive CHECK (principal_minor > 0 AND instalment_count BETWEEN 2 AND 12)
);

COMMENT ON TABLE bnpl.bnpl_schedule IS
    'A BNPL Schedule version (P12-TSK-013, INV-BNPL-05): version 1 projected by the pinned engine (pay-in-4 BNPL_SCHEDULE_ENGINE_V1, pay-in-N SCHEDULE_ENGINE_V1) from the purchase''s business date over exactly the financed gross; its instalments'' principal sums to its principal exactly, held at commit. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE TABLE bnpl.bnpl_instalment (
    id               uuid     NOT NULL,
    schedule_id      uuid     NOT NULL,
    obligation_id    uuid     NOT NULL,
    sequence         smallint NOT NULL,
    due_date         date     NOT NULL,
    currency         char(3)  NOT NULL,
    scale            smallint NOT NULL,
    principal_minor  bigint   NOT NULL,
    interest_minor   bigint   NOT NULL,
    amount_minor     bigint   NOT NULL,
    CONSTRAINT bnpl_instalment_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_instalment_sequence_unique UNIQUE (schedule_id, sequence),
    CONSTRAINT bnpl_instalment_identity UNIQUE (id, obligation_id),
    CONSTRAINT bnpl_instalment_schedule_fk FOREIGN KEY (schedule_id, obligation_id)
        REFERENCES bnpl.bnpl_schedule (id, obligation_id),
    CONSTRAINT bnpl_instalment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- A BNPL Instalment: a positive principal, interest never negative, the amount their sum.
    CONSTRAINT bnpl_instalment_amounts CHECK (
        sequence >= 1 AND principal_minor > 0 AND interest_minor >= 0 AND amount_minor = principal_minor + interest_minor)
);

COMMENT ON TABLE bnpl.bnpl_instalment IS
    'A BNPL Instalment of a schedule version (P12-TSK-013): its sequence, due date, principal, scheduled interest (zero for pay-in-4) and amount. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_schedule_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    obligation bnpl.bnpl_obligation%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL schedule is immutable from insert, for every role (P12-TSK-013, INV-BNPL-05)';
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    -- Version 1 starts on the purchase's own business date, in the obligation's currency, on its agreement's engine.
    IF NOT FOUND OR NEW.start_date <> obligation.purchase_date OR NEW.currency <> obligation.currency
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.obligation_id = NEW.obligation_id
                              AND a.version = 1 AND a.schedule_engine = NEW.engine) THEN
        RAISE EXCEPTION 'a BNPL schedule version 1 starts on its obligation''s purchase date, in its currency, on its agreement''s engine (P12-TSK-013)';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_schedule_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_schedule
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_schedule_is_born_once();

CREATE TRIGGER bnpl_schedule_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_schedule
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE OR REPLACE FUNCTION bnpl.bnpl_instalment_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    schedule bnpl.bnpl_schedule%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL instalment is immutable from insert, for every role (P12-TSK-013, INV-BNPL-05)';
    END IF;
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = NEW.schedule_id;
    IF NEW.currency <> schedule.currency OR NEW.scale <> schedule.scale OR NEW.sequence > schedule.instalment_count
            OR NEW.due_date < schedule.start_date THEN
        RAISE EXCEPTION 'a BNPL instalment is its schedule''s, in its currency and scale, within its count and on or after its start (P12-TSK-013)';
    END IF;
    -- Pay-in-4 (A4, A5): the first on the purchase date, every 14 days after, no interest.
    IF schedule.engine = 'BNPL_SCHEDULE_ENGINE_V1'
            AND (NEW.interest_minor <> 0 OR NEW.due_date <> schedule.start_date + 14 * (NEW.sequence - 1)) THEN
        RAISE EXCEPTION 'a pay-in-4 instalment is interest-free and falls due 14 days after its predecessor, the first on the purchase date (P12-TSK-013, A5)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_instalment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_instalment
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_instalment_is_born_once();

CREATE TRIGGER bnpl_instalment_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_instalment
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- INV-BNPL-05 at commit: the instalments are numbered 1..n, n the schedule's count, and their principal sums to the
-- schedule's principal exactly - which for version 1 is the financed gross.
CREATE OR REPLACE FUNCTION bnpl.bnpl_schedule_conserves()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    schedule bnpl.bnpl_schedule%ROWTYPE;
    counted integer;
    highest integer;
    scheduled numeric;
BEGIN
    SELECT * INTO schedule FROM bnpl.bnpl_schedule s WHERE s.id = NEW.id;
    SELECT count(*), max(i.sequence), sum(i.principal_minor) INTO counted, highest, scheduled
      FROM bnpl.bnpl_instalment i WHERE i.schedule_id = schedule.id;
    IF counted <> schedule.instalment_count OR highest IS DISTINCT FROM counted
            OR scheduled IS DISTINCT FROM schedule.principal_minor THEN
        RAISE EXCEPTION 'a BNPL schedule conserves: its instalments are numbered 1 to n and their principal sums to its principal exactly (P12-TSK-013, INV-BNPL-05)';
    END IF;
    IF schedule.schedule_version = 1 AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_obligation o JOIN bnpl.merchant_financing f ON f.purchase_id = o.purchase_id
             WHERE o.id = schedule.obligation_id AND f.gross_minor = schedule.principal_minor
               AND f.scale = schedule.scale) THEN
        RAISE EXCEPTION 'a BNPL schedule version 1 schedules exactly the financed gross (P12-TSK-013, INV-BNPL-04, INV-BNPL-05)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_schedule_conserves
    AFTER INSERT ON bnpl.bnpl_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_schedule_conserves();

-- ---------------------------------------------------------------------------------------------
-- The billing (pay-in-4's instalment 1 at purchase; extended by P12-TSK-015).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_billing (
    id                uuid        NOT NULL,
    instalment_id     uuid        NOT NULL,
    obligation_id     uuid        NOT NULL,
    value_date        date        NOT NULL,
    currency          char(3)     NOT NULL,
    scale             smallint    NOT NULL,
    principal_minor   bigint      NOT NULL,
    interest_minor    bigint      NOT NULL,
    journal_entry_id  uuid        NOT NULL,
    billed_at         timestamptz NOT NULL,
    CONSTRAINT bnpl_billing_pk PRIMARY KEY (id),
    -- An instalment is billed once (the ledger key bnpl.billing:<instalment> beneath it).
    CONSTRAINT bnpl_billing_instalment_unique UNIQUE (instalment_id),
    CONSTRAINT bnpl_billing_journal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_billing_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    CONSTRAINT bnpl_billing_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_billing_amounts CHECK (principal_minor > 0 AND interest_minor >= 0)
);

COMMENT ON TABLE bnpl.bnpl_billing IS
    'A BNPL Instalment billed (P12-TSK-013 for pay-in-4''s instalment 1 at purchase; P12-TSK-015 for the rest): its principal (and pay-in-N''s interest) reclassified due by the entry F2 (bnpl.billing:<instalment>, its id here), value-dated never before the obligation''s floor. Born once per instalment, INSERT-only (RESTRICTED-FINANCIAL).';

-- ---------------------------------------------------------------------------------------------
-- The principal value floor: the first date the record leaves open to a principal-moving fact.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.principal_value_floor(obligation uuid)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(
        (SELECT o.purchase_date FROM bnpl.bnpl_obligation o WHERE o.id = obligation),
        (SELECT max(b.value_date) FROM bnpl.bnpl_billing b WHERE b.obligation_id = obligation))
$$;

COMMENT ON FUNCTION bnpl.principal_value_floor(uuid) IS
    'The first business date an obligation''s record leaves open to a principal-moving fact (P12-TSK-013; the Phase 11 gate''s R1, lending V030''s shape): its purchase''s business date, then its latest billing''s value date itself; P12-TSK-017 adds the day after its latest accrual. A billing, a repayment and later every principal fact is never value-dated before it.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_billing_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    instalment bnpl.bnpl_instalment%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    value_floor date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL billing is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i WHERE i.id = NEW.instalment_id;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    IF obligation.status <> 'ACTIVE' OR NEW.currency <> instalment.currency OR NEW.scale <> instalment.scale
            OR NEW.principal_minor <> instalment.principal_minor OR instalment.due_date > NEW.value_date THEN
        RAISE EXCEPTION 'a BNPL billing bills its ACTIVE obligation''s instalment''s whole principal, on or after its due date (P12-TSK-013)';
    END IF;
    -- The Phase 11 gate's R1 from the first table: never a date the record has closed, never after the later of the
    -- latest date the clock showed and the floor.
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a BNPL billing is value-dated on or after its obligation''s floor and never after today (P12-TSK-013, the Phase 11 gate''s R1)';
    END IF;
    NEW.billed_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_billing_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_billing
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_billing_is_born_once();

CREATE TRIGGER bnpl_billing_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_billing
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The repayment and its allocation (pay-in-4's instalment 1 at purchase; extended by P12-TSK-018).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_repayment (
    id                 uuid        NOT NULL,
    obligation_id      uuid        NOT NULL,
    kind               text        NOT NULL,
    request_key        text        NOT NULL,
    currency           char(3)     NOT NULL,
    scale              smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    agreement_id       uuid        NOT NULL,
    agreement_version  integer     NOT NULL,
    allocation_engine  text        NOT NULL,
    journal_entry_id   uuid        NOT NULL,
    value_date         date        NOT NULL,
    received_at        timestamptz NOT NULL,
    CONSTRAINT bnpl_repayment_pk PRIMARY KEY (id),
    -- One repayment per requester's key (the ledger key bnpl.repayment:<repayment> beneath it).
    CONSTRAINT bnpl_repayment_request_unique UNIQUE (obligation_id, kind, request_key),
    CONSTRAINT bnpl_repayment_journal_entry_unique UNIQUE (journal_entry_id),
    CONSTRAINT bnpl_repayment_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_repayment_agreement_fk FOREIGN KEY (agreement_id, agreement_version)
        REFERENCES bnpl.bnpl_agreement (agreement_id, version),
    -- Only the kind this task births; P12-TSK-018 and -019 add theirs.
    CONSTRAINT bnpl_repayment_kind_is_known CHECK (kind IN ('AT_PURCHASE')),
    CONSTRAINT bnpl_repayment_request_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    CONSTRAINT bnpl_repayment_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_repayment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT bnpl_repayment_allocation_engine CHECK (allocation_engine = 'ALLOCATION_ENGINE_V1')
);

COMMENT ON TABLE bnpl.bnpl_repayment IS
    'A BNPL repayment (P12-TSK-013 for pay-in-4''s instalment 1 collected from the customer''s wallet at purchase, kind AT_PURCHASE; P12-TSK-018 and -019 for the rest): the amount the entry F3 (bnpl.repayment:<repayment>, its id here) debited the wallet, allocated by the pinned engine, value-dated never before the obligation''s floor. Born once per key, INSERT-only (RESTRICTED-FINANCIAL).';

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
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id;
    IF obligation.status <> 'ACTIVE' OR obligation.currency <> NEW.currency OR obligation.agreement_id <> NEW.agreement_id
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.agreement_id = NEW.agreement_id
                              AND a.version = NEW.agreement_version AND a.allocation_engine = NEW.allocation_engine) THEN
        RAISE EXCEPTION 'a BNPL repayment repays its ACTIVE obligation, in its currency, under its agreement''s allocation engine (P12-TSK-013)';
    END IF;
    -- AT_PURCHASE: pay-in-4's instalment 1, inside the purchase, keyed by the purchase, dated the purchase's date.
    IF NEW.kind = 'AT_PURCHASE' AND (obligation.product <> 'BNPL_PAY_IN_4'
            OR NEW.request_key <> obligation.purchase_id::text OR NEW.value_date <> obligation.purchase_date) THEN
        RAISE EXCEPTION 'an AT_PURCHASE repayment is a pay-in-4 purchase''s own, keyed by it and dated its business date (P12-TSK-013, A5)';
    END IF;
    value_floor := bnpl.principal_value_floor(NEW.obligation_id);
    IF NEW.value_date < value_floor
            OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN
        RAISE EXCEPTION 'a BNPL repayment is value-dated on or after its obligation''s floor and never after today (P12-TSK-013, the Phase 11 gate''s R1)';
    END IF;
    NEW.received_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_repayment_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_repayment
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_is_born_once();

CREATE TRIGGER bnpl_repayment_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_repayment
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE TABLE bnpl.bnpl_repayment_allocation (
    id              uuid     NOT NULL,
    repayment_id    uuid     NOT NULL,
    sequence        smallint NOT NULL,
    target          text     NOT NULL,
    instalment_id   uuid,
    component       text,
    currency        char(3)  NOT NULL,
    scale           smallint NOT NULL,
    amount_minor    bigint   NOT NULL,
    CONSTRAINT bnpl_repayment_allocation_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_repayment_allocation_sequence_unique UNIQUE (repayment_id, sequence),
    CONSTRAINT bnpl_repayment_allocation_repayment_fk FOREIGN KEY (repayment_id) REFERENCES bnpl.bnpl_repayment (id),
    CONSTRAINT bnpl_repayment_allocation_instalment_fk FOREIGN KEY (instalment_id) REFERENCES bnpl.bnpl_instalment (id),
    -- AllocationLine.Target and AllocationComponent: a due item names its instalment and component; the credit balance
    -- and principal not yet due name neither.
    CONSTRAINT bnpl_repayment_allocation_target CHECK (
        (target = 'DUE_ITEM' AND instalment_id IS NOT NULL AND component IN ('FEES', 'INTEREST', 'PRINCIPAL'))
        OR (target IN ('CREDIT_BALANCE', 'NOT_DUE_PRINCIPAL') AND instalment_id IS NULL AND component IS NULL)),
    CONSTRAINT bnpl_repayment_allocation_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_repayment_allocation_amount_positive CHECK (sequence >= 1 AND amount_minor > 0)
);

COMMENT ON TABLE bnpl.bnpl_repayment_allocation IS
    'A BNPL repayment''s allocation (P12-TSK-013; extended by P12-TSK-018): one line per due item component paid, or held as credit, in order; the lines sum to the repayment, held at commit. INSERT-only (RESTRICTED-FINANCIAL).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_allocation_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL repayment allocation is immutable from insert, for every role (P12-TSK-013)';
    END IF;
    -- In the repayment's currency and scale, on one of its own obligation's instalments.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_repayment r WHERE r.id = NEW.repayment_id
                      AND r.currency = NEW.currency AND r.scale = NEW.scale
                      AND (NEW.instalment_id IS NULL OR EXISTS (
                          SELECT 1 FROM bnpl.bnpl_instalment i
                           WHERE i.id = NEW.instalment_id AND i.obligation_id = r.obligation_id))) THEN
        RAISE EXCEPTION 'a BNPL repayment allocation is its repayment''s, in its currency, on its obligation''s instalment (P12-TSK-013)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_repayment_allocation_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_repayment_allocation
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_allocation_is_born_once();

CREATE TRIGGER bnpl_repayment_allocation_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_repayment_allocation
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- The lines sum to the amount, at commit.
CREATE OR REPLACE FUNCTION bnpl.bnpl_repayment_is_allocated_whole()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT COALESCE(sum(l.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation l WHERE l.repayment_id = NEW.id)
            <> NEW.amount_minor THEN
        RAISE EXCEPTION 'a BNPL repayment is allocated whole: its lines sum to its amount (P12-TSK-013)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_repayment_is_allocated_whole
    AFTER INSERT ON bnpl.bnpl_repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_repayment_is_allocated_whole();

-- ---------------------------------------------------------------------------------------------
-- INV-BNPL-02 at commit: the purchase, its financing and its obligation are one fact.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.purchase_is_financed_at_commit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'purchase' THEN
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.id;
    ELSE
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id;
    END IF;
    SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = purchase.id;
    IF purchase.status IS DISTINCT FROM 'FINANCED'
            OR NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = purchase.id)
            OR obligation.id IS NULL THEN
        RAISE EXCEPTION 'a BNPL purchase, its merchant financing and its obligation commit together, the purchase FINANCED (P12-TSK-013, INV-BNPL-02)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_offer o WHERE o.id = purchase.offer_id AND o.status = 'ACCEPTED')
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_agreement a WHERE a.obligation_id = obligation.id AND a.version = 1)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_acceptance e
                            WHERE e.agreement_id = obligation.agreement_id AND e.agreement_version = 1)
            OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_schedule s WHERE s.obligation_id = obligation.id
                              AND s.schedule_version = 1) THEN
        RAISE EXCEPTION 'a BNPL purchase commits beside its ACCEPTED offer, agreement version 1, its evidence and schedule version 1 (P12-TSK-013, INV-BNPL-02)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER purchase_is_financed_at_commit
    AFTER INSERT ON bnpl.purchase
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.purchase_is_financed_at_commit();

CREATE CONSTRAINT TRIGGER merchant_financing_is_financed_at_commit
    AFTER INSERT ON bnpl.merchant_financing
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.purchase_is_financed_at_commit();

CREATE CONSTRAINT TRIGGER bnpl_obligation_is_financed_at_commit
    AFTER INSERT ON bnpl.bnpl_obligation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.purchase_is_financed_at_commit();

-- ---------------------------------------------------------------------------------------------
-- The offer's edge function: V004's body verbatim but for ACCEPTED.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application bnpl.bnpl_application%ROWTYPE;
    validity_minutes integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL offer is born OFFERED and open (P12-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party, product, plan and session.
        SELECT * INTO application FROM bnpl.bnpl_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product
                OR application.plan <> NEW.plan OR application.checkout_session_id <> NEW.checkout_session_id THEN
            RAISE EXCEPTION 'a BNPL offer is born for its own party''s application AWAITING_DECISION, of the same product, plan and checkout session (P12-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_minutes INTO validity_minutes
          FROM bnpl.bnpl_terms_version v JOIN bnpl.bnpl_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a BNPL offer pins its product''s ACTIVE terms version (P12-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is the database's, after the step's locks - never the step's read, never an instance's.
        NEW.offered_at := clock_timestamp();
        -- The expiry on the database clock (A17): the earliest of the decision's validity, the checkout session's
        -- deadline and the pinned offer validity - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.session_expires_at,
                                NEW.offered_at + make_interval(mins => validity_minutes));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a BNPL offer is never born expired: its decision or its checkout session has lapsed (P12-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL offer is never deleted (P12-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.plan IS DISTINCT FROM OLD.plan
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.checkout_session_id IS DISTINCT FROM OLD.checkout_session_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.session_expires_at IS DISTINCT FROM OLD.session_expires_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL offer''s identity, pin and expiry are frozen from birth (P12-TSK-011)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P12-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % BNPL offer is terminal (P12-TSK-011)', OLD.status;
    END IF;
    -- The clock-sided edges, bracketing the domain's conditional (its clock_timestamp() read after the row lock,
    -- between this statement's start and this trigger's clock): exactly one of them at the boundary instant.
    IF NEW.status = 'DECLINED' THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be declined: it expires (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > clock_timestamp() THEN
            RAISE EXCEPTION 'a BNPL offer expires only once expires_at <= the database clock (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'ACCEPTED' THEN
        -- P12-TSK-013: the acceptance's side of the clock, the decline's bracket - and only beside its FINANCED purchase,
        -- born in the same transaction with its financing, obligation and agreement (INV-BNPL-02).
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be accepted: it expires (P12-TSK-013)';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.offer_id = NEW.id AND p.status = 'FINANCED') THEN
            RAISE EXCEPTION 'a BNPL offer is ACCEPTED only beside its FINANCED purchase (P12-TSK-013, INV-BNPL-02)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a BNPL offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P12-TSK-013)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure after the lock, never before the offering on a backward clock.
    NEW.closed_at := GREATEST(clock_timestamp(), OLD.offered_at);
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: the purchase's status and order alone move; everything else INSERT-only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.purchase TO finapp_app;
GRANT UPDATE (status, order_id) ON bnpl.purchase TO finapp_app;
GRANT SELECT, INSERT ON bnpl.purchase_event TO finapp_app;
GRANT SELECT, INSERT ON bnpl.merchant_financing TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_obligation TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_obligation_event TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_agreement TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_acceptance TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_schedule TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_instalment TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_billing TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_repayment TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_repayment_allocation TO finapp_app;
