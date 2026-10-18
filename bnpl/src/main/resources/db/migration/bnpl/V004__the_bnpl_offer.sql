-- The BNPL offer and the origination step (P12-TSK-011; PHASE_12_PLAN.md sections 5.1, 5.2, 7.1 T-off, 7.5 and 12.1,
-- BNPL_LIFECYCLES.md sections 4.2 and 4.3; INV-BNPL-03, INV-LIFE-01, INV-LIFE-02, INV-HIST-04, INV-EVT-04; lending V005's
-- shape, bnpl's own rows).
--
-- An OFFER turns one approved credit decision for EXACTLY the purchase amount into explicit, expiring terms - once per
-- application, whatever credit's or checkout's events do. The origination step (BnplOriginationSchedule, T-off) takes an
-- application AWAITING_DECISION under its row lock (B1), reads credit's decision authoritatively through app's
-- BnplCreditDecisions (an event only pulls the application's permit forward), reads the checkout session (still OPEN and
-- before its deadline on the database clock), pins the product's terms version ACTIVE at the offering instant FOR SHARE
-- and writes, in ONE transaction:
--
--     bnpl_offer        - the machine row: identity, status, the decision, the session and the pinned terms version by
--                         id, the offering instant and the expiry - stamped by the trigger on the database clock as
--                         LEAST(decision valid_until, the session's expires_at, offered_at + the pinned offer validity)
--                         (A17): an offer can never outlive its decision or its checkout session. NO MONEY, so it takes
--                         the machine tables' column-scoped UPDATE of its status and permit and can be locked (B2).
--     bnpl_offer_terms  - the terms: exactly the purchase amount, the pinned rate and schedule engine, the illustrative
--                         schedule's figures and dates, the canonical terms (BnplOfferTerms, format 1) as TEXT - never
--                         jsonb, the P11-TSK-012 lesson - with their SHA-256 held to the bytes by CHECK, and the SHA-256 of
--                         the offer document shown. INSERT-only for every role, immutable from insert: it carries money.
--     bnpl_offer_event  - the offer's machine as history, append-only.
--
-- and moves the application AWAITING_DECISION -> OFFERED beside it. The step's other exits need no offer: DECLINED, and
-- CLOSED_UNDECIDED with its reason (credit's request closed; the session closed - its credit request cancelled while
-- still cancellable; an approval below the purchase; no ACTIVE terms offering the plan; the decision lapsed).
--
--     (born by the step) OFFERED --customer declines, while expires_at > the database clock--> DECLINED
--                                --the sweep, once expires_at <= the database clock----------> EXPIRED
--                                --the customer accepts (P12-TSK-013, beside its purchase)----> ACCEPTED
--
-- Decline and expiry are complementary conditionals on the database clock under the offer's row lock: the domain's
-- conditional UPDATE judges clock_timestamp() after the lock; the trigger brackets it - a decline refused once the
-- statement's own start has reached the expiry, an expiry refused while the clock read in the trigger has not - so a
-- decline the domain admitted is never refused by the trigger, and exactly one of the two wins at the boundary. ACCEPTED
-- is P12-TSK-013's edge, refused here for every writer until that task replaces the edge function.
--
-- AN OFFERED APPLICATION HOLDS ITS SESSION FOR GOOD (the owner judgement P12-TSK-010 left to this task, settled here and
-- unchanged in V003's partial unique): a declined or expired offer does NOT free the checkout session for a second BNPL
-- application. Owner decision B5 is one credit decision per purchase; credit's approval keeps its reservation until its
-- own validity whether or not the offer is taken, so a second application on the same session would stack a second
-- reservation on one purchase (the exposure -012 counts) and invite plan-shopping across decisions. The session stays
-- OPEN for another instrument - the card or the wallet.
--
-- Held for EVERY writer: the offer's edge trigger (born OFFERED beside its application AWAITING_DECISION of the same
-- party, product, plan and session, on that product's ACTIVE terms version, the offering instant, the expiry and the
-- permit stamped by the trigger, never born expired; identity frozen; only the machine's edges, each on its side of the
-- clock); the deferred birth check (an offer commits only beside its application's OFFERED edge and with its terms);
-- UNIQUE (application_id) and UNIQUE (decision_id); the terms born once, beside an OFFERED offer, carrying exactly the
-- pinned version's currency, scale, rate and schedule engine, the hash the bytes' own; the application's replaced edge
-- function (-> OFFERED only beside its offer); the status CHECKs and the append-only histories. No cross-schema foreign
-- key (ADR-0029): credit's decision and checkout's session are named by id; their state is the domain's to judge,
-- through app's ports, in the same transaction.

-- ---------------------------------------------------------------------------------------------
-- The application's "-> OFFERED only beside its offer" rank (V003's function, replaced verbatim but for it).
-- ---------------------------------------------------------------------------------------------
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
    -- -> OFFERED only beside its offer (P12-TSK-011): the offer is born first, in the same transaction.
    IF NEW.status = 'OFFERED' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_offer o WHERE o.application_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL application is OFFERED only beside its offer (P12-TSK-011)';
    END IF;
    -- The database stamps the closure after the act's locks, never before the submission on a backward clock; nobody
    -- writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL
                          ELSE GREATEST(clock_timestamp(), OLD.submitted_at) END;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_offer (
    id                    uuid        NOT NULL,
    application_id        uuid        NOT NULL,
    party_id              uuid        NOT NULL,
    product               text        NOT NULL,
    plan                  text        NOT NULL,
    status                text        NOT NULL,
    decision_id           uuid        NOT NULL,
    terms_version_id      uuid        NOT NULL,
    checkout_session_id   uuid        NOT NULL,
    offered_at            timestamptz NOT NULL,
    decision_valid_until  timestamptz NOT NULL,
    session_expires_at    timestamptz NOT NULL,
    expires_at            timestamptz NOT NULL,
    next_step_at          timestamptz NOT NULL,
    closed_at             timestamptz,
    correlation_id        text        NOT NULL,
    CONSTRAINT bnpl_offer_pk PRIMARY KEY (id),
    -- One offer per application - the third rank beneath the application's row lock and its conditional edge.
    CONSTRAINT bnpl_offer_application_unique UNIQUE (application_id),
    -- One offer per approved decision (credit's id, by value): a decision is offered once.
    CONSTRAINT bnpl_offer_decision_unique UNIQUE (decision_id),
    -- The terms row's composite key pins its product and plan to this row's.
    CONSTRAINT bnpl_offer_product_pinned UNIQUE (id, product, plan),
    CONSTRAINT bnpl_offer_application_fk FOREIGN KEY (application_id) REFERENCES bnpl.bnpl_application (id),
    -- The pinned terms version is the product's own (the composite key pins the product).
    CONSTRAINT bnpl_offer_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES bnpl.bnpl_terms_version (id, product),
    -- BnplProduct.
    CONSTRAINT bnpl_offer_product_is_offered CHECK (product IN ('BNPL_PAY_IN_4', 'BNPL_PAY_IN_N')),
    -- BnplPlan, each of its one product.
    CONSTRAINT bnpl_offer_plan_of_its_product CHECK (
        (product = 'BNPL_PAY_IN_4' AND plan = 'PAY_IN_4')
        OR (product = 'BNPL_PAY_IN_N' AND plan IN ('PAY_IN_3_MONTHS', 'PAY_IN_6_MONTHS', 'PAY_IN_12_MONTHS'))),
    -- BnplOfferStatus - a membership list of the machine's states.
    CONSTRAINT bnpl_offer_status_is_known CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED')),
    -- The expiry (A17): after the offering, never after the decision's validity, never after the checkout session's
    -- deadline, never beyond the terms' 30-minute ceiling (BnplTerms.MAXIMUM_OFFER_VALIDITY_MINUTES) - stamped by the
    -- trigger, held here for the row as it stands.
    CONSTRAINT bnpl_offer_expiry_bounded CHECK (
        expires_at > offered_at AND expires_at <= decision_valid_until AND expires_at <= session_expires_at
        AND expires_at <= offered_at + interval '30 minutes'),
    CONSTRAINT bnpl_offer_closed_coherent CHECK ((status <> 'OFFERED') = (closed_at IS NOT NULL)),
    CONSTRAINT bnpl_offer_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_offer IS
    'A BNPL offer''s machine (P12-TSK-011, BNPL_LIFECYCLES.md section 4.3): born OFFERED by the origination step beside its application''s OFFERED edge, for exactly the purchase amount, on the product''s terms version ACTIVE at the offering instant (pinned FOR SHARE); expires_at = LEAST(decision validity, checkout session expiry, offered_at + the pinned validity) stamped by the database (A17); DECLINED by the customer while expires_at is ahead of the database clock, EXPIRED by the sweep once it is not, ACCEPTED (P12-TSK-013) beside its purchase. One per application and per decision. No money: the terms are bnpl.bnpl_offer_terms, born with it and never changed.';

CREATE INDEX bnpl_offer_due ON bnpl.bnpl_offer (next_step_at, id) WHERE status = 'OFFERED';
CREATE INDEX bnpl_offer_by_party ON bnpl.bnpl_offer (party_id, offered_at);

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
    -- The two clock-sided edges, bracketing the domain's conditional (its clock_timestamp() read after the row lock,
    -- between this statement's start and this trigger's clock): exactly one of them at the boundary instant.
    IF NEW.status = 'DECLINED' THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired BNPL offer cannot be declined: it expires (P12-TSK-011)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > clock_timestamp() THEN
            RAISE EXCEPTION 'a BNPL offer expires only once expires_at <= the database clock (P12-TSK-011)';
        END IF;
    ELSE
        -- ACCEPTED: P12-TSK-013 replaces this function and admits it only beside its purchase and the consumption.
        RAISE EXCEPTION 'a BNPL offer moves OFFERED -> DECLINED | EXPIRED here; % -> % is not an edge until its task admits it (P12-TSK-011)', OLD.status, NEW.status;
    END IF;
    -- The database stamps the closure after the lock, never before the offering on a backward clock.
    NEW.closed_at := GREATEST(clock_timestamp(), OLD.offered_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_offer_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_offer
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_offer_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the BNPL offers, their terms and their history are never truncated (P12-TSK-011, INV-HIST-01)';
END;
$$;

CREATE TRIGGER bnpl_offer_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_offer
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_offer_is_never_truncated();

-- An offer commits only beside its application's OFFERED edge and with its terms.
CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_born_beside_its_application()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_application a WHERE a.id = NEW.application_id AND a.status = 'OFFERED') THEN
        RAISE EXCEPTION 'a BNPL offer committed without its application''s OFFERED edge (P12-TSK-011)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_offer_terms t WHERE t.offer_id = NEW.id) THEN
        RAISE EXCEPTION 'a BNPL offer committed without its terms (P12-TSK-011)';
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The terms: the money, born with the offer, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_offer_terms (
    offer_id                  uuid     NOT NULL,
    product                   text     NOT NULL,
    plan                      text     NOT NULL,
    currency                  char(3)  NOT NULL,
    scale                     smallint NOT NULL,
    amount_minor              bigint   NOT NULL,
    nominal_annual_rate       text     NOT NULL,
    schedule_engine           text     NOT NULL,
    illustrative_start        date     NOT NULL,
    instalment_count          smallint NOT NULL,
    first_due_date            date     NOT NULL,
    last_due_date             date     NOT NULL,
    first_instalment_minor    bigint   NOT NULL,
    regular_instalment_minor  bigint   NOT NULL,
    final_instalment_minor    bigint   NOT NULL,
    total_interest_minor      bigint   NOT NULL,
    total_payable_minor       bigint   NOT NULL,
    canonical_format          smallint NOT NULL,
    canonical_terms           text     NOT NULL,
    terms_sha256              bytea    NOT NULL,
    document_sha256           bytea    NOT NULL,
    CONSTRAINT bnpl_offer_terms_pk PRIMARY KEY (offer_id),
    CONSTRAINT bnpl_offer_terms_offer_fk FOREIGN KEY (offer_id, product, plan)
        REFERENCES bnpl.bnpl_offer (id, product, plan),
    -- Both products are EUR (BnplProduct, A2); every amount in the currency's scale.
    CONSTRAINT bnpl_offer_terms_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Exactly the purchase amount (section 12.1) - positive; every instalment positive.
    CONSTRAINT bnpl_offer_terms_amounts_positive CHECK (
        amount_minor > 0 AND first_instalment_minor > 0 AND regular_instalment_minor > 0 AND final_instalment_minor > 0),
    -- The pinned version's rate, in its canonical spelling (bnpl V002's bnpl_terms_rate_canonical).
    CONSTRAINT bnpl_offer_terms_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    -- The illustration conserves: payable = amount + interest.
    CONSTRAINT bnpl_offer_terms_conserves CHECK (
        total_interest_minor >= 0 AND total_payable_minor = amount_minor + total_interest_minor
        AND last_due_date >= first_due_date AND first_due_date >= illustrative_start),
    -- Each product's own shape and no other's: pay-in-4 four interest-free instalments on BNPL_SCHEDULE_ENGINE_V1, the
    -- first on the start date and the last 42 days later (A4, A5); pay-in-N its plan's months on SCHEDULE_ENGINE_V1, the
    -- first after the start (A6).
    CONSTRAINT bnpl_offer_terms_shape_coherent CHECK (
        (plan = 'PAY_IN_4' AND schedule_engine = 'BNPL_SCHEDULE_ENGINE_V1' AND instalment_count = 4
            AND nominal_annual_rate = '0' AND total_interest_minor = 0
            AND first_due_date = illustrative_start AND last_due_date = illustrative_start + 42
            AND regular_instalment_minor = final_instalment_minor
            AND first_instalment_minor = amount_minor - 3 * regular_instalment_minor)
        OR (plan <> 'PAY_IN_4' AND schedule_engine = 'SCHEDULE_ENGINE_V1'
            AND instalment_count = CASE plan WHEN 'PAY_IN_3_MONTHS' THEN 3 WHEN 'PAY_IN_6_MONTHS' THEN 6
                                             WHEN 'PAY_IN_12_MONTHS' THEN 12 END
            AND first_due_date > illustrative_start AND final_instalment_minor <= regular_instalment_minor)),
    -- The canonical terms, format 1 (BnplOfferTerms): ASCII with no whitespace and no escape - and the hash IS the
    -- SHA-256 of exactly those bytes, for every writer: a stored hash cannot disagree with its terms.
    CONSTRAINT bnpl_offer_terms_canonical_shape CHECK (
        canonical_format = 1 AND char_length(canonical_terms) BETWEEN 2 AND 16384
        AND canonical_terms ~ '^\{"format":"1",[A-Za-z0-9_.:+",{}\[\]-]*\}$'),
    CONSTRAINT bnpl_offer_terms_hash_is_the_terms CHECK (
        octet_length(terms_sha256) = 32 AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))),
    CONSTRAINT bnpl_offer_terms_document_hash_shape CHECK (octet_length(document_sha256) = 32)
);

COMMENT ON TABLE bnpl.bnpl_offer_terms IS
    'A BNPL offer''s terms (P12-TSK-011, section 12.1): exactly the purchase amount, the pinned terms version''s rate and schedule engine, the illustrative schedule''s figures, the canonical terms (format 1, text) with their SHA-256 - held to the bytes by CHECK - and the SHA-256 of the offer document shown. Born once beside an OFFERED offer, INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL). The acceptance (P12-TSK-013) echoes the hash; the agreement copies the canonical terms.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_terms_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL offer''s terms are immutable from insert, for every role (P12-TSK-011)';
    END IF;
    -- Born beside an OFFERED offer, carrying exactly its pinned terms version's currency, scale, rate and schedule
    -- engine (INV-HIST-04).
    IF NOT EXISTS (
            SELECT 1
              FROM bnpl.bnpl_offer o
              JOIN bnpl.bnpl_terms t ON t.terms_version_id = o.terms_version_id
             WHERE o.id = NEW.offer_id AND o.status = 'OFFERED'
               AND t.currency = NEW.currency AND t.scale = NEW.scale
               AND t.nominal_annual_rate = NEW.nominal_annual_rate
               AND t.schedule_engine = NEW.schedule_engine) THEN
        RAISE EXCEPTION 'a BNPL offer''s terms are born beside its OFFERED offer and are exactly its pinned version''s (P12-TSK-011, INV-HIST-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_offer_terms_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_offer_terms
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_offer_terms_is_born_once();

CREATE TRIGGER bnpl_offer_terms_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_offer_terms
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_offer_is_never_truncated();

CREATE CONSTRAINT TRIGGER bnpl_offer_born_beside_its_application
    AFTER INSERT ON bnpl.bnpl_offer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_offer_born_beside_its_application();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_offer_event (
    id              uuid        NOT NULL,
    offer_id        uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT bnpl_offer_event_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_offer_event_offer_fk FOREIGN KEY (offer_id) REFERENCES bnpl.bnpl_offer (id),
    -- BnplOfferStatus, and its only edges.
    CONSTRAINT bnpl_offer_event_edge CHECK (
        (from_status IS NULL AND to_status = 'OFFERED')
        OR (from_status = 'OFFERED' AND to_status IN ('ACCEPTED', 'DECLINED', 'EXPIRED'))),
    -- A reason CODE, never free text.
    CONSTRAINT bnpl_offer_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT bnpl_offer_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX bnpl_offer_event_by_offer ON bnpl.bnpl_offer_event (offer_id, occurred_at);

COMMENT ON TABLE bnpl.bnpl_offer_event IS
    'A BNPL offer''s machine as history (P12-TSK-011): every edge, its actor, reason code and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL offer event is append-only (P12-TSK-011, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_offer_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_offer_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_offer_event_is_append_only();

CREATE TRIGGER bnpl_offer_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_offer_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.bnpl_offer_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine row's status and permit alone move; the terms and the history INSERT-only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_offer TO finapp_app;
GRANT UPDATE (status, next_step_at) ON bnpl.bnpl_offer TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_offer_terms TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_offer_event TO finapp_app;
