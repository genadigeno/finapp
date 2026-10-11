-- The loan offer (P11-TSK-011; PHASE_11_PLAN.md sections 5.1, 5.2, 7.1 T-off and 7.5, LENDING_LIFECYCLES.md sections
-- 4.3 and 4.4; INV-LND-05, INV-HIST-04, INV-EVT-04, INV-LIFE-01).
--
-- An OFFER turns one approved credit decision into explicit, expiring terms - once per application, whatever credit's
-- events do. The origination step (LoanOriginationSchedule, T-off) takes an application AWAITING_DECISION under its row
-- lock (L1), reads credit's decision authoritatively through app's LoanCreditDecisions (an event is only a hint that
-- pulls the application's permit forward), pins the product's terms version ACTIVE at the offering instant FOR SHARE
-- and writes, in ONE transaction:
--
--     loan_offer        - the machine row: identity, status, the decision and the pinned terms version by id, the
--                         offering instant and the expiry - stamped by the trigger on the database clock as
--                         LEAST(decision valid_until, offered_at + the pinned version's offer validity). NO MONEY, so it
--                         takes the machine tables' column-scoped UPDATE of its status and permit (PHASE_11_PLAN.md
--                         section 8; LendingSchemaHasNoMutableMoneyTest) and can be locked (L2).
--     loan_offer_terms  - the terms: exactly the approved principal or limit and term (A20), the pinned version's rate,
--                         engines and template, the loan's illustrative schedule figures (A5) or the line's minimum-
--                         payment rule, the canonical terms (format 1) and their SHA-256 - the hash held to the bytes by a
--                         CHECK - and the SHA-256 of the offer document the customer is shown. INSERT-only for every role
--                         but its owner, immutable from insert: it carries money (INV-LND-05).
--     loan_offer_event  - the offer's machine as history, append-only.
--
-- and moves the application AWAITING_DECISION -> OFFERED beside it. Its other exits, from the same step: a declined
-- decision -> DECLINED; credit's request CANCELLED, EXPIRED or ABANDONED -> CLOSED_UNDECIDED; an approval that cannot be
-- offered - no ACTIVE terms version, the chosen day no longer offered, the decision already lapsed, or a specific loan
-- whose schedule would not amortise - -> CLOSED_UNDECIDED with its reason code, and no offer.
--
--     (born by the step) OFFERED --customer declines, while expires_at > statement_timestamp()--> DECLINED
--                                --the sweep, once expires_at <= statement_timestamp()----------> EXPIRED
--                                --the customer accepts (P11-TSK-012, beside its loan)----------> ACCEPTED
--
-- Decline and expiry are complementary conditionals on the database clock under the offer's row lock: exactly one at
-- the boundary. ACCEPTED is P11-TSK-012's edge - refused here for every writer until that task replaces the edge
-- function and admits it only beside its loan.
--
-- Held for EVERY writer: the offer's edge trigger (born OFFERED beside an application AWAITING_DECISION of the same
-- party and product, on that product's ACTIVE terms version, at an instant of this transaction; the expiry and the
-- permit stamped by the trigger; identity frozen; only the machine's edges, each on its side of the clock); the
-- deferred birth check (an offer commits only beside its application's OFFERED edge and with its terms); UNIQUE
-- (application_id) and UNIQUE (decision_id); the terms born once, beside an OFFERED offer, carrying exactly the pinned
-- version's rate, engines, template and line rule and the application's day; the application's replaced edge function
-- (-> OFFERED only beside its offer; its permit moves only while it awaits a decision); the status CHECKs and the
-- append-only histories. No cross-schema foreign key (ADR-0029): credit's decision is named by id.

-- ---------------------------------------------------------------------------------------------
-- The application's permit, and its "-> OFFERED only beside its offer" rank.
-- ---------------------------------------------------------------------------------------------
-- The permit the origination sweep claims by (PHASE_11_PLAN.md section 7.5): stamped from statement_timestamp() by the
-- one-statement claim, pulled forward by a credit event's hint. Existing applications are due at once.
ALTER TABLE lending.loan_application ADD COLUMN next_step_at timestamptz NOT NULL DEFAULT transaction_timestamp();
ALTER TABLE lending.loan_application ALTER COLUMN next_step_at DROP DEFAULT;

COMMENT ON COLUMN lending.loan_application.next_step_at IS
    'The origination sweep''s permit (P11-TSK-011): due at birth, re-stamped from statement_timestamp() by every claim, pulled forward by a credit event''s hint. Binds nothing - the step re-judges everything under the row lock.';

CREATE INDEX loan_application_due ON lending.loan_application (next_step_at, id) WHERE status = 'AWAITING_DECISION';

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
        -- Due for the origination step at once (P11-TSK-011).
        NEW.next_step_at := transaction_timestamp();
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
    -- A permit-only move (P11-TSK-011): only an application awaiting its decision has a next step, the move moves it,
    -- and nothing but the permit moves with it - the closure stamp is the database's, at a terminal edge.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'AWAITING_DECISION' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an application AWAITING_DECISION has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    -- The machine's edges and no other - a terminal application never moves, and SUBMITTED is never skipped.
    IF NOT ((OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')
            OR (OLD.status = 'AWAITING_DECISION'
                AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'a loan application moves SUBMITTED -> AWAITING_DECISION -> OFFERED | DECLINED | CLOSED_UNDECIDED | WITHDRAWN; % -> % is not an edge (P11-TSK-010)', OLD.status, NEW.status;
    END IF;
    -- -> OFFERED only beside its offer (P11-TSK-011): the offer is born first, in the same transaction.
    IF NEW.status = 'OFFERED' AND NOT EXISTS (SELECT 1 FROM lending.loan_offer o WHERE o.application_id = NEW.id) THEN
        RAISE EXCEPTION 'a loan application is OFFERED only beside its offer (P11-TSK-011)';
    END IF;
    -- The database stamps the closure; nobody writes it.
    NEW.closed_at := CASE WHEN NEW.status = 'AWAITING_DECISION' THEN NULL ELSE transaction_timestamp() END;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_offer (
    id                    uuid        NOT NULL,
    application_id        uuid        NOT NULL,
    party_id              uuid        NOT NULL,
    product               text        NOT NULL,
    status                text        NOT NULL,
    decision_id           uuid        NOT NULL,
    terms_version_id      uuid        NOT NULL,
    offered_at            timestamptz NOT NULL,
    decision_valid_until  timestamptz NOT NULL,
    expires_at            timestamptz NOT NULL,
    next_step_at          timestamptz NOT NULL,
    closed_at             timestamptz,
    correlation_id        text        NOT NULL,
    CONSTRAINT loan_offer_pk PRIMARY KEY (id),
    -- One offer per application - the third rank beneath the application's row lock and its conditional edge.
    CONSTRAINT loan_offer_application_unique UNIQUE (application_id),
    -- One offer per approved decision (credit's id, by value): a decision is offered once.
    CONSTRAINT loan_offer_decision_unique UNIQUE (decision_id),
    -- The terms row's composite key pins its product to this row's.
    CONSTRAINT loan_offer_product_pinned UNIQUE (id, product),
    CONSTRAINT loan_offer_application_fk FOREIGN KEY (application_id) REFERENCES lending.loan_application (id),
    -- The pinned terms version is the product's own (the composite key pins the product).
    CONSTRAINT loan_offer_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES lending.loan_terms_version (id, product),
    -- LoanProduct.
    CONSTRAINT loan_offer_product_is_offered CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    -- LoanOfferStatus - a membership list of the machine's states.
    CONSTRAINT loan_offer_status_is_known CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED')),
    -- The expiry: after the offering, never after the decision's validity, never beyond the products' 14-day ceiling
    -- (LoanProduct.offerValidity) - stamped by the trigger, held here for the row as it stands.
    CONSTRAINT loan_offer_expiry_bounded CHECK (
        expires_at > offered_at AND expires_at <= decision_valid_until
        AND expires_at <= offered_at + interval '14 days'),
    CONSTRAINT loan_offer_closed_coherent CHECK ((status <> 'OFFERED') = (closed_at IS NOT NULL)),
    CONSTRAINT loan_offer_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_offer IS
    'A loan offer''s machine (P11-TSK-011, LENDING_LIFECYCLES.md section 4.4): born OFFERED by the origination step beside its application''s OFFERED edge, on the product''s terms version ACTIVE at the offering instant (pinned FOR SHARE); DECLINED by the customer while expires_at > statement_timestamp(), EXPIRED by the sweep once expires_at <= statement_timestamp(), ACCEPTED (P11-TSK-012) beside its loan. One per application and per decision. No money: the terms are lending.loan_offer_terms, born with it and never changed.';

CREATE INDEX loan_offer_due ON lending.loan_offer (next_step_at, id) WHERE status = 'OFFERED';
CREATE INDEX loan_offer_by_party ON lending.loan_offer (party_id, offered_at);

CREATE OR REPLACE FUNCTION lending.loan_offer_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    application lending.loan_application%ROWTYPE;
    validity_days integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OFFERED' OR NEW.closed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a loan offer is born OFFERED and open (P11-TSK-011)';
        END IF;
        -- Born under its application's row lock, from AWAITING_DECISION, for the same party and product.
        SELECT * INTO application FROM lending.loan_application a WHERE a.id = NEW.application_id;
        IF NOT FOUND OR application.status <> 'AWAITING_DECISION'
                OR application.party_id <> NEW.party_id OR application.product <> NEW.product THEN
            RAISE EXCEPTION 'a loan offer is born for its own party''s application AWAITING_DECISION, of the same product (P11-TSK-011)';
        END IF;
        -- On the product's terms version ACTIVE now - the one the step pinned FOR SHARE (INV-HIST-04).
        SELECT t.offer_validity_days INTO validity_days
          FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id
         WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a loan offer pins its product''s ACTIVE terms version (P11-TSK-011, INV-HIST-04)';
        END IF;
        -- The offering instant is a database instant of this transaction - read by the step after its locks - so the
        -- illustrative schedule's first day and the expiry are one clock's.
        IF NEW.offered_at < transaction_timestamp() OR NEW.offered_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer''s offering instant is the database clock''s, within its transaction (P11-TSK-011)';
        END IF;
        -- The expiry on the database clock: the earlier of the decision's validity and the pinned version's offer
        -- validity (PHASE_11_PLAN.md section 5.2) - stamped here, never written by the step.
        NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.offered_at + make_interval(days => validity_days));
        IF NEW.expires_at <= NEW.offered_at THEN
            RAISE EXCEPTION 'a loan offer is never born expired: the decision has lapsed (P11-TSK-011)';
        END IF;
        -- The expiry sweep's permit: due at the expiry.
        NEW.next_step_at := NEW.expires_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a loan offer is never deleted (P11-TSK-011, INV-HIST-01)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.application_id IS DISTINCT FROM OLD.application_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.decision_id IS DISTINCT FROM OLD.decision_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.offered_at IS DISTINCT FROM OLD.offered_at
            OR NEW.decision_valid_until IS DISTINCT FROM OLD.decision_valid_until
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a loan offer''s identity, pin and expiry are frozen from birth (P11-TSK-011, INV-LND-05)';
    END IF;
    -- A permit-only move: only an open offer has a next step, the move moves it, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OFFERED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
                OR NEW.next_step_at IS NOT DISTINCT FROM OLD.next_step_at THEN
            RAISE EXCEPTION 'only an OFFERED offer has a permit to move, and only its permit; % is not (P11-TSK-011)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OFFERED' THEN
        RAISE EXCEPTION 'a % loan offer is terminal (P11-TSK-011)', OLD.status;
    END IF;
    -- The two clock-sided edges: exactly one of them at the boundary instant.
    IF NEW.status = 'DECLINED' THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired loan offer cannot be declined: it expires (P11-TSK-011)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer expires only once expires_at <= the database clock (P11-TSK-011)';
        END IF;
    ELSE
        -- ACCEPTED: P11-TSK-012 replaces this function and admits it only beside its loan and the decision's consumption.
        RAISE EXCEPTION 'a loan offer moves OFFERED -> DECLINED | EXPIRED here; % -> % is not an edge until its task admits it (P11-TSK-011)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_offer_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_offer
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_offer_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION lending.loan_offer_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the loan offers, their terms and their history are never truncated (P11-TSK-011, INV-HIST-01)';
END;
$$;

CREATE TRIGGER loan_offer_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_offer
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_offer_is_never_truncated();

-- An offer commits only beside its application's OFFERED edge and with its terms.
CREATE OR REPLACE FUNCTION lending.loan_offer_born_beside_its_application()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan_application a WHERE a.id = NEW.application_id AND a.status = 'OFFERED') THEN
        RAISE EXCEPTION 'a loan offer committed without its application''s OFFERED edge (P11-TSK-011)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM lending.loan_offer_terms t WHERE t.offer_id = NEW.id) THEN
        RAISE EXCEPTION 'a loan offer committed without its terms (P11-TSK-011)';
    END IF;
    RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The terms: the money, born with the offer, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_offer_terms (
    offer_id                     uuid     NOT NULL,
    product                      text     NOT NULL,
    currency                     char(3)  NOT NULL,
    scale                        smallint NOT NULL,
    amount_minor                 bigint   NOT NULL,
    term_months                  smallint,
    nominal_annual_rate          text     NOT NULL,
    repayment_day                smallint NOT NULL,
    illustrative_start           date,
    first_due_date               date,
    instalment_minor             bigint,
    final_instalment_minor       bigint,
    total_interest_minor         bigint,
    total_payable_minor          bigint,
    minimum_payment_floor_minor  bigint,
    minimum_payment_ratio_bps    integer,
    payment_due_days             integer,
    schedule_engine_version      smallint,
    accrual_engine_version       smallint NOT NULL,
    allocation_engine_version    smallint NOT NULL,
    statement_engine_version     smallint,
    agreement_template_id        text     NOT NULL,
    agreement_template_version   integer  NOT NULL,
    canonical_format             smallint NOT NULL,
    canonical_terms              text     NOT NULL,
    terms_sha256                 bytea    NOT NULL,
    document_sha256              bytea    NOT NULL,
    CONSTRAINT loan_offer_terms_pk PRIMARY KEY (offer_id),
    CONSTRAINT loan_offer_terms_offer_fk FOREIGN KEY (offer_id, product) REFERENCES lending.loan_offer (id, product),
    -- Both products are EUR (LoanProduct); every amount in the currency's scale.
    CONSTRAINT loan_offer_terms_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- Exactly the approved principal or limit (A20) - a positive amount.
    CONSTRAINT loan_offer_terms_amount_positive CHECK (amount_minor > 0),
    -- The pinned version's rate, in its canonical spelling (lending V003's loan_terms_rate_canonical).
    CONSTRAINT loan_offer_terms_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    CONSTRAINT loan_offer_terms_day_in_every_month CHECK (repayment_day BETWEEN 1 AND 28),
    -- Each product's own figures and no other's: the loan's term, illustrative schedule (A5) and schedule engine; the
    -- line's minimum-payment rule (A23, A24) and statement engine. The schedule conserves: payable = principal + interest.
    CONSTRAINT loan_offer_terms_kind_coherent CHECK (
        (product = 'PERSONAL_LOAN'
            AND term_months >= 1 AND illustrative_start IS NOT NULL AND first_due_date > illustrative_start
            AND instalment_minor > 0 AND final_instalment_minor > 0 AND final_instalment_minor <= instalment_minor
            AND total_interest_minor >= 0 AND total_payable_minor = amount_minor + total_interest_minor
            AND schedule_engine_version >= 1 AND statement_engine_version IS NULL
            AND minimum_payment_floor_minor IS NULL AND minimum_payment_ratio_bps IS NULL AND payment_due_days IS NULL)
        OR (product = 'CREDIT_LINE'
            AND term_months IS NULL AND illustrative_start IS NULL AND first_due_date IS NULL
            AND instalment_minor IS NULL AND final_instalment_minor IS NULL
            AND total_interest_minor IS NULL AND total_payable_minor IS NULL
            AND schedule_engine_version IS NULL AND statement_engine_version >= 1
            AND minimum_payment_floor_minor > 0 AND minimum_payment_ratio_bps BETWEEN 1 AND 10000
            AND payment_due_days BETWEEN 1 AND 27)),
    CONSTRAINT loan_offer_terms_engines_numbered CHECK (accrual_engine_version >= 1 AND allocation_engine_version >= 1),
    CONSTRAINT loan_offer_terms_template_shape CHECK (
        agreement_template_id ~ '^[A-Z][A-Z0-9_]{2,63}$' AND agreement_template_version >= 1),
    -- The canonical terms, format 1 (CanonicalOfferTerms): ASCII with no whitespace and no escape - and the hash IS the
    -- SHA-256 of exactly those bytes, for every writer (INV-LND-05): a stored hash cannot disagree with its terms.
    CONSTRAINT loan_offer_terms_canonical_shape CHECK (
        canonical_format = 1 AND char_length(canonical_terms) BETWEEN 2 AND 16384
        AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$'),
    CONSTRAINT loan_offer_terms_hash_is_the_terms CHECK (
        octet_length(terms_sha256) = 32 AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))),
    CONSTRAINT loan_offer_terms_document_hash_shape CHECK (octet_length(document_sha256) = 32)
);

COMMENT ON TABLE lending.loan_offer_terms IS
    'A loan offer''s terms (P11-TSK-011, A20, A5): exactly the approved principal or limit and term, the pinned terms version''s rate, engines and template, the loan''s illustrative schedule figures or the line''s minimum-payment rule, the canonical terms (format 1) with their SHA-256 - held to the bytes by CHECK - and the SHA-256 of the offer document shown. Born once beside an OFFERED offer, INSERT-only and immutable for every role (INV-LND-05): it carries money (RESTRICTED-FINANCIAL). The agreement (P11-TSK-012) copies the canonical terms and their hash.';

CREATE OR REPLACE FUNCTION lending.loan_offer_terms_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan offer''s terms are immutable from insert, for every role (P11-TSK-011, INV-LND-05)';
    END IF;
    -- Born beside an OFFERED offer, carrying exactly its pinned terms version's rate, engines, template and line rule
    -- (INV-HIST-04) and its application's repayment or statement day.
    IF NOT EXISTS (
            SELECT 1
              FROM lending.loan_offer o
              JOIN lending.loan_application a ON a.id = o.application_id
              JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
             WHERE o.id = NEW.offer_id AND o.status = 'OFFERED'
               AND a.repayment_day = NEW.repayment_day
               AND t.currency = NEW.currency AND t.scale = NEW.scale
               AND t.nominal_annual_rate = NEW.nominal_annual_rate
               AND t.schedule_engine_version IS NOT DISTINCT FROM NEW.schedule_engine_version
               AND t.accrual_engine_version = NEW.accrual_engine_version
               AND t.allocation_engine_version = NEW.allocation_engine_version
               AND t.statement_engine_version IS NOT DISTINCT FROM NEW.statement_engine_version
               AND t.agreement_template_id = NEW.agreement_template_id
               AND t.agreement_template_version = NEW.agreement_template_version
               AND t.minimum_payment_floor_minor IS NOT DISTINCT FROM NEW.minimum_payment_floor_minor
               AND t.minimum_payment_ratio_bps IS NOT DISTINCT FROM NEW.minimum_payment_ratio_bps
               AND t.payment_due_days IS NOT DISTINCT FROM NEW.payment_due_days) THEN
        RAISE EXCEPTION 'a loan offer''s terms are born beside its OFFERED offer and are exactly its pinned version''s and its application''s day (P11-TSK-011, INV-HIST-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_offer_terms_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_offer_terms
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_offer_terms_is_born_once();

CREATE TRIGGER loan_offer_terms_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_offer_terms
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_offer_is_never_truncated();

CREATE CONSTRAINT TRIGGER loan_offer_born_beside_its_application
    AFTER INSERT ON lending.loan_offer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_offer_born_beside_its_application();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_offer_event (
    id              uuid        NOT NULL,
    offer_id        uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT loan_offer_event_pk PRIMARY KEY (id),
    CONSTRAINT loan_offer_event_offer_fk FOREIGN KEY (offer_id) REFERENCES lending.loan_offer (id),
    -- LoanOfferStatus, and its only edges.
    CONSTRAINT loan_offer_event_edge CHECK (
        (from_status IS NULL AND to_status = 'OFFERED')
        OR (from_status = 'OFFERED' AND to_status IN ('ACCEPTED', 'DECLINED', 'EXPIRED'))),
    -- A reason CODE, never free text.
    CONSTRAINT loan_offer_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT loan_offer_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_offer_event_by_offer ON lending.loan_offer_event (offer_id, occurred_at);

COMMENT ON TABLE lending.loan_offer_event IS
    'A loan offer''s machine as history (P11-TSK-011): every edge, its actor, reason code and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION lending.loan_offer_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan offer event is append-only (P11-TSK-011, INV-HIST-01)';
    END IF;
    NEW.occurred_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_offer_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_offer_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_offer_event_is_append_only();

CREATE TRIGGER loan_offer_event_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_offer_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_offer_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine rows' status and permit alone move; the terms and the history INSERT-only. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT UPDATE (next_step_at) ON lending.loan_application TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_offer TO finapp_app;
GRANT UPDATE (status, next_step_at) ON lending.loan_offer TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_offer_terms TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_offer_event TO finapp_app;
