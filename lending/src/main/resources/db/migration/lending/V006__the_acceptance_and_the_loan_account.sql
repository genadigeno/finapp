-- The acceptance: the agreement, the loan account and its evidence (P11-TSK-012; PHASE_11_PLAN.md sections 5.2, 5.3,
-- 7.1 T-acc, 8, 11 and 12.1, LENDING_LIFECYCLES.md sections 4.4, 4.5, 4.6 and 4.11; ADR-0091 point 4, ADR-0092 points 5
-- and 6, ADR-0096 section 5; INV-LND-05, INV-LND-06, INV-LND-13, INV-CRD-09, INV-IDEM-01, INV-IDN-04).
--
-- The customer's acceptance of an OFFERED, unexpired offer is ONE transaction (T-acc): under the party's credit profile
-- lock (L0, through credit's port) and the offer's row lock (L2), credit's decision consumed through credit's own port
-- (its consumer reference the agreement's id), for a loan the currency's capital row locked (L6) and headroom judged
-- under it, and then, in this order:
--
--     loan               - the loan account's machine row, either kind: identity, kind, status and the stamps - NO
--                          amount, rate or balance column (every figure is a ledger balance of the six per-loan accounts
--                          opened beside it, INV-LND-01). Born PENDING_DISBURSEMENT (a loan) or ACTIVE (a line).
--     loan_event         - its machine as history, append-only.
--     loan_agreement     - agreement version 1: the offer's canonical terms (format 1) and their SHA-256 COPIED, byte for
--                          byte, with the figures a statement needs beside them (the principal or limit, the term, the
--                          rate, the day, the engines, the template). INSERT-only and immutable for every role: it carries
--                          money (INV-LND-05).
--     loan_acceptance    - the evidence of the act (A13): the offer, the agreement version and hash, the template, the
--                          SHA-256 of the document bytes shown, the identity, session and assurance level, the database's
--                          instant, the channel, the client address and user agent. INSERT-only for every role.
--     loan_disbursement  - a loan's disbursement, born PENDING with its deadline and permit stamped by the database and,
--                          for path X, the customer's verified payout method by opaque reference (INV-RAIL-03). It does
--                          not move until P11-TSK-014 admits its edges.
--
-- and the offer OFFERED -> ACCEPTED beside them, conditional on expires_at > statement_timestamp() - the expiry's
-- complement, so exactly one of ACCEPTED and EXPIRED at the boundary instant. No money moves: the acceptance commits
-- exposure (credit's reservation becomes lending's commitment, in the same commit) and, for a loan, capital headroom;
-- the first posting is the disbursement's (P11-TSK-014).
--
-- Held for EVERY writer: the offer's replaced edge function (-> ACCEPTED only beside its loan, only while unexpired);
-- two deferred checks - a loan commits only beside its ACCEPTED offer, its agreement v1, its evidence and (a loan) its
-- PENDING disbursement, and an offer commits ACCEPTED only beside its loan, agreement and evidence; the loan's edge
-- trigger (born by kind, for its own party's OFFERED unexpired offer, identity frozen, no edge until its task admits
-- it); UNIQUE (offer_id) and UNIQUE (decision_id) on the loan, UNIQUE (loan_id, version) on the agreement, UNIQUE
-- (agreement_id) on the evidence, UNIQUE (loan_id) on the disbursement; the agreement born as an exact copy of its
-- offer's terms with a CHECK holding the hash to its bytes; the status CHECKs; the append-only history. No cross-schema
-- foreign key (ADR-0029): credit's decision is named by value, and credit's consumption names the agreement - the two
-- are bound by credit's UNIQUE (consumer_kind, consumer_ref) and UNIQUE (decision_id), and by the one transaction.

-- ---------------------------------------------------------------------------------------------
-- The loan account: the machine row, no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan (
    id                   uuid        NOT NULL,
    party_id             uuid        NOT NULL,
    product              text        NOT NULL,
    kind                 text        NOT NULL,
    status               text        NOT NULL,
    currency             char(3)     NOT NULL,
    offer_id             uuid        NOT NULL,
    decision_id          uuid        NOT NULL,
    opened_at            timestamptz NOT NULL,
    closed_at            timestamptz,
    closure_reason       text,
    cancellation_reason  text,
    correlation_id       text        NOT NULL,
    CONSTRAINT loan_pk PRIMARY KEY (id),
    -- One loan per offer - the third rank beneath the offer's row lock and its conditional edge (INV-LND-06).
    CONSTRAINT loan_offer_unique UNIQUE (offer_id),
    -- One loan per credit decision (credit's id, by value) - lending's backstop beside credit's UNIQUE (decision_id).
    CONSTRAINT loan_decision_unique UNIQUE (decision_id),
    CONSTRAINT loan_offer_fk FOREIGN KEY (offer_id) REFERENCES lending.loan_offer (id),
    -- LoanProduct, and the kind it decides (ADR-0090 point 3): the personal loan instalment, the credit line revolving.
    CONSTRAINT loan_kind_is_the_products CHECK (
        (product = 'PERSONAL_LOAN' AND kind = 'INSTALMENT') OR (product = 'CREDIT_LINE' AND kind = 'REVOLVING')),
    -- LoanStatus - a membership list of the machine's states.
    CONSTRAINT loan_status_is_known CHECK (
        status IN ('PENDING_DISBURSEMENT', 'ACTIVE', 'CLOSING', 'CLOSED', 'CANCELLED')),
    -- Each kind's own states (LENDING_LIFECYCLES.md section 4.5): a line is never awaiting disbursement and never
    -- cancelled; a loan never closing.
    CONSTRAINT loan_status_is_the_kinds CHECK (
        (kind = 'INSTALMENT' AND status IN ('PENDING_DISBURSEMENT', 'ACTIVE', 'CLOSED', 'CANCELLED'))
        OR (kind = 'REVOLVING' AND status IN ('ACTIVE', 'CLOSING', 'CLOSED'))),
    -- Both products are EUR (LoanProduct).
    CONSTRAINT loan_currency_is_the_products CHECK (currency = 'EUR'),
    -- Terminal with its reason, and only terminal with one: ClosureReason, CancellationReason.
    CONSTRAINT loan_terminal_coherent CHECK (
        ((status IN ('CLOSED', 'CANCELLED')) = (closed_at IS NOT NULL))
        AND ((status = 'CLOSED') = (closure_reason IS NOT NULL))
        AND ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL))),
    CONSTRAINT loan_closure_reason_is_known CHECK (
        closure_reason IS NULL OR closure_reason IN ('REPAID', 'PAID_OFF_EARLY', 'CLOSED_BY_CUSTOMER')),
    CONSTRAINT loan_cancellation_reason_is_known CHECK (
        cancellation_reason IS NULL OR cancellation_reason IN ('STANDING_LOST', 'DISBURSEMENT_FAILED')),
    CONSTRAINT loan_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan IS
    'A loan account, either kind (P11-TSK-012, LENDING_LIFECYCLES.md section 4.5): born at the customer''s acceptance of an OFFERED, unexpired offer - a PERSONAL_LOAN INSTALMENT PENDING_DISBURSEMENT, a CREDIT_LINE REVOLVING ACTIVE - beside its agreement v1, its acceptance evidence, (a loan) its PENDING disbursement, the six per-loan ledger accounts and the offer''s ACCEPTED edge. One per offer and per credit decision. No amount, rate or balance column: every figure is a ledger balance (INV-LND-01); the price is the agreement''s. Its edges are P11-TSK-014''s (disbursement, cancellation), -023''s (a line''s closure) and -026''s (closure) - refused until each task admits them.';

-- The committed principal the capital headroom reads (ADR-0096 section 5) and the disbursement sweep's claim (-014).
CREATE INDEX loan_pending_disbursement ON lending.loan (currency, id) WHERE status = 'PENDING_DISBURSEMENT';
CREATE INDEX loan_by_party ON lending.loan (party_id, opened_at);

CREATE OR REPLACE FUNCTION lending.loan_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    offer lending.loan_offer%ROWTYPE;
    offered_currency char(3);
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
    -- Every edge is a later task's: the disbursement and the cancellation (P11-TSK-014), a line's closure request
    -- (P11-TSK-023), the closure (P11-TSK-026) - each replaces this function and admits its edge beside its own fact.
    RAISE EXCEPTION 'a loan does not move here; % -> % is not an edge until its task admits it (P11-TSK-012)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER loan_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION lending.loan_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the loans, their agreements, their evidence, their disbursements and their history are never truncated (P11-TSK-012, INV-HIST-01)';
END;
$$;

CREATE TRIGGER loan_is_never_truncated
    BEFORE TRUNCATE ON lending.loan
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The agreement: the money, version 1 copied from the offer, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_agreement (
    id                          uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    version                     integer     NOT NULL,
    offer_id                    uuid,
    amendment_id                uuid,
    decision_id                 uuid        NOT NULL,
    terms_version_id            uuid        NOT NULL,
    product                     text        NOT NULL,
    currency                    char(3)     NOT NULL,
    scale                       smallint    NOT NULL,
    amount_minor                bigint      NOT NULL,
    term_months                 smallint,
    nominal_annual_rate         text        NOT NULL,
    repayment_day               smallint    NOT NULL,
    schedule_engine_version     smallint,
    accrual_engine_version      smallint    NOT NULL,
    allocation_engine_version   smallint    NOT NULL,
    statement_engine_version    smallint,
    agreement_template_id       text        NOT NULL,
    agreement_template_version  integer     NOT NULL,
    canonical_format            smallint    NOT NULL,
    canonical_terms             text        NOT NULL,
    terms_sha256                bytea       NOT NULL,
    effective_from              timestamptz NOT NULL,
    created_at                  timestamptz NOT NULL,
    CONSTRAINT loan_agreement_pk PRIMARY KEY (id),
    -- One row per version of one loan's agreement (INV-LND-05): a change of terms is version n+1 (P11-TSK-027).
    CONSTRAINT loan_agreement_version_unique UNIQUE (loan_id, version),
    CONSTRAINT loan_agreement_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_agreement_offer_fk FOREIGN KEY (offer_id) REFERENCES lending.loan_offer (id),
    CONSTRAINT loan_agreement_terms_version_fk FOREIGN KEY (terms_version_id, product)
        REFERENCES lending.loan_terms_version (id, product),
    -- Version 1 is the offer's, accepted; every later version an amendment's (P11-TSK-027).
    CONSTRAINT loan_agreement_origin CHECK (
        (version = 1 AND offer_id IS NOT NULL AND amendment_id IS NULL)
        OR (version > 1 AND offer_id IS NULL AND amendment_id IS NOT NULL)),
    CONSTRAINT loan_agreement_product_is_offered CHECK (product IN ('PERSONAL_LOAN', 'CREDIT_LINE')),
    CONSTRAINT loan_agreement_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    -- The approved principal (a loan) or limit (a line) - a positive amount.
    CONSTRAINT loan_agreement_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT loan_agreement_rate_canonical CHECK (nominal_annual_rate ~ '^(0|0\.[0-9]{0,7}[1-9]|1)$'),
    CONSTRAINT loan_agreement_day_in_every_month CHECK (repayment_day BETWEEN 1 AND 28),
    CONSTRAINT loan_agreement_kind_coherent CHECK (
        (product = 'PERSONAL_LOAN' AND term_months >= 1 AND schedule_engine_version >= 1
            AND statement_engine_version IS NULL)
        OR (product = 'CREDIT_LINE' AND term_months IS NULL AND schedule_engine_version IS NULL
            AND statement_engine_version >= 1)),
    CONSTRAINT loan_agreement_engines_numbered CHECK (accrual_engine_version >= 1 AND allocation_engine_version >= 1),
    CONSTRAINT loan_agreement_template_shape CHECK (
        agreement_template_id ~ '^[A-Z][A-Z0-9_]{2,63}$' AND agreement_template_version >= 1),
    -- The canonical terms - the offer's format 1, copied - and the hash IS the SHA-256 of exactly those bytes, for every
    -- writer (INV-LND-05): text, never jsonb, because jsonb would reorder and re-space the very bytes that were hashed.
    CONSTRAINT loan_agreement_canonical_shape CHECK (
        canonical_format = 1 AND char_length(canonical_terms) BETWEEN 2 AND 16384
        AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$'),
    CONSTRAINT loan_agreement_hash_is_the_terms CHECK (
        octet_length(terms_sha256) = 32 AND terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8')))
);

COMMENT ON TABLE lending.loan_agreement IS
    'A loan agreement''s versions (P11-TSK-012, ADR-0092 point 5, INV-LND-05): version 1 born in the acceptance as an exact copy of its offer''s terms - the canonical terms (format 1) and their SHA-256, held to the bytes by CHECK, with the principal or limit, term, rate, day, engines and template beside them; later versions are amendments'' (P11-TSK-027). INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL). Every servicing row will name the version it was computed under.';

CREATE OR REPLACE FUNCTION lending.loan_agreement_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan agreement version is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    IF NEW.version <> 1 THEN
        RAISE EXCEPTION 'an agreement version after the first is an accepted amendment''s - not admitted until P11-TSK-027';
    END IF;
    -- Version 1: born beside its loan, for the loan's own offer and decision, as an EXACT copy of the offer's terms
    -- (INV-LND-05) - the bytes the customer was shown and hashed, never re-rendered.
    IF NOT EXISTS (
            SELECT 1
              FROM lending.loan l
              JOIN lending.loan_offer o ON o.id = l.offer_id
              JOIN lending.loan_offer_terms t ON t.offer_id = o.id
             WHERE l.id = NEW.loan_id AND l.offer_id = NEW.offer_id AND l.decision_id = NEW.decision_id
               AND o.terms_version_id = NEW.terms_version_id AND o.product = NEW.product
               AND t.currency = NEW.currency AND t.scale = NEW.scale AND t.amount_minor = NEW.amount_minor
               AND t.term_months IS NOT DISTINCT FROM NEW.term_months
               AND t.nominal_annual_rate = NEW.nominal_annual_rate AND t.repayment_day = NEW.repayment_day
               AND t.schedule_engine_version IS NOT DISTINCT FROM NEW.schedule_engine_version
               AND t.accrual_engine_version = NEW.accrual_engine_version
               AND t.allocation_engine_version = NEW.allocation_engine_version
               AND t.statement_engine_version IS NOT DISTINCT FROM NEW.statement_engine_version
               AND t.agreement_template_id = NEW.agreement_template_id
               AND t.agreement_template_version = NEW.agreement_template_version
               AND t.canonical_format = NEW.canonical_format
               AND t.canonical_terms = NEW.canonical_terms
               AND t.terms_sha256 = NEW.terms_sha256) THEN
        RAISE EXCEPTION 'agreement version 1 is born beside its loan as an exact copy of its offer''s terms (P11-TSK-012, INV-LND-05)';
    END IF;
    NEW.created_at := statement_timestamp();
    NEW.effective_from := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_agreement_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_agreement
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_agreement_is_born_once();

CREATE TRIGGER loan_agreement_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_agreement
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The acceptance evidence (A13): what was accepted, by whom, how.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_acceptance (
    id                          uuid        NOT NULL,
    loan_id                     uuid        NOT NULL,
    agreement_id                uuid        NOT NULL,
    agreement_version           integer     NOT NULL,
    offer_id                    uuid,
    terms_sha256                bytea       NOT NULL,
    agreement_template_id       text        NOT NULL,
    agreement_template_version  integer     NOT NULL,
    document_sha256             bytea       NOT NULL,
    identity_id                 uuid        NOT NULL,
    session_id                  uuid        NOT NULL,
    assurance_level             text        NOT NULL,
    accepted_at                 timestamptz NOT NULL,
    channel                     text        NOT NULL,
    client_ip                   inet,
    user_agent                  text,
    correlation_id              text        NOT NULL,
    CONSTRAINT loan_acceptance_pk PRIMARY KEY (id),
    -- One accepting act per agreement version.
    CONSTRAINT loan_acceptance_agreement_unique UNIQUE (agreement_id),
    CONSTRAINT loan_acceptance_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    CONSTRAINT loan_acceptance_agreement_fk FOREIGN KEY (agreement_id) REFERENCES lending.loan_agreement (id),
    CONSTRAINT loan_acceptance_offer_fk FOREIGN KEY (offer_id) REFERENCES lending.loan_offer (id),
    -- A19: the acceptance is a MULTI_FACTOR act (or stronger) - for every writer.
    CONSTRAINT loan_acceptance_multi_factor CHECK (assurance_level IN ('MULTI_FACTOR', 'STRONG')),
    CONSTRAINT loan_acceptance_channel_is_known CHECK (channel = 'API'),
    CONSTRAINT loan_acceptance_hashes_shape CHECK (octet_length(terms_sha256) = 32 AND octet_length(document_sha256) = 32),
    CONSTRAINT loan_acceptance_user_agent_bounded CHECK (user_agent IS NULL OR char_length(user_agent) BETWEEN 1 AND 512),
    CONSTRAINT loan_acceptance_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_acceptance IS
    'The evidence of a customer''s acceptance (P11-TSK-012, A13, ADR-0092 point 6): the offer, the agreement version and its terms hash, the template, the SHA-256 of the document bytes shown, the identity, session and assurance level (MULTI_FACTOR or stronger, A19), the database''s instant, the channel, the client address and user agent (CONFIDENTIAL). INSERT-only for every role. Acceptance is not consent (INV-IDN-04): no consent purpose is created.';

CREATE OR REPLACE FUNCTION lending.loan_acceptance_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'acceptance evidence is immutable from insert, for every role (P11-TSK-012, INV-LND-05)';
    END IF;
    -- The evidence binds what was accepted: its agreement's version, hash and template, and - for version 1 - the
    -- offer's own document hash, the bytes the customer was shown.
    IF NOT EXISTS (
            SELECT 1
              FROM lending.loan_agreement a
              JOIN lending.loan_offer_terms t ON t.offer_id = a.offer_id
             WHERE a.id = NEW.agreement_id AND a.loan_id = NEW.loan_id AND a.version = NEW.agreement_version
               AND a.offer_id = NEW.offer_id AND a.terms_sha256 = NEW.terms_sha256
               AND a.agreement_template_id = NEW.agreement_template_id
               AND a.agreement_template_version = NEW.agreement_template_version
               AND t.document_sha256 = NEW.document_sha256) THEN
        RAISE EXCEPTION 'acceptance evidence binds its agreement''s version, terms hash, template and document hash (P11-TSK-012)';
    END IF;
    NEW.accepted_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_acceptance_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_acceptance
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_acceptance_is_born_once();

CREATE TRIGGER loan_acceptance_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_acceptance
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- A loan's disbursement: born PENDING, moved by P11-TSK-014.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_disbursement (
    id                uuid        NOT NULL,
    loan_id           uuid        NOT NULL,
    status            text        NOT NULL,
    payout_method_id  uuid,
    created_at        timestamptz NOT NULL,
    deadline_at       timestamptz NOT NULL,
    next_attempt_at   timestamptz NOT NULL,
    correlation_id    text        NOT NULL,
    CONSTRAINT loan_disbursement_pk PRIMARY KEY (id),
    -- A loan is disbursed at most once (INV-LND-07's first rank, P11-TSK-014's scenario 1).
    CONSTRAINT loan_disbursement_loan_unique UNIQUE (loan_id),
    CONSTRAINT loan_disbursement_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    -- The disbursement machine (LENDING_LIFECYCLES.md section 4.6).
    CONSTRAINT loan_disbursement_status_is_known CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
    CONSTRAINT loan_disbursement_deadline_after_birth CHECK (deadline_at > created_at),
    CONSTRAINT loan_disbursement_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE lending.loan_disbursement IS
    'An instalment loan''s disbursement (P11-TSK-012 births it, P11-TSK-014 moves it; LENDING_LIFECYCLES.md section 4.6): born PENDING beside its loan PENDING_DISBURSEMENT, its deadline (the pinned terms version''s disbursement deadline) and its permit stamped by the database; the customer''s verified payout method present exactly for path X - an opaque payments reference, never an account identifier (INV-RAIL-03). One per loan.';

CREATE OR REPLACE FUNCTION lending.loan_disbursement_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    deadline_hours integer;
    preference text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'a disbursement is born PENDING (P11-TSK-012)';
        END IF;
        -- Beside its instalment loan awaiting disbursement; the deadline is its agreement's pinned terms version's.
        SELECT t.disbursement_deadline_hours, a.payout_preference INTO deadline_hours, preference
          FROM lending.loan l
          JOIN lending.loan_offer o ON o.id = l.offer_id
          JOIN lending.loan_application a ON a.id = o.application_id
          JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id
         WHERE l.id = NEW.loan_id AND l.kind = 'INSTALMENT' AND l.status = 'PENDING_DISBURSEMENT';
        IF NOT FOUND OR deadline_hours IS NULL THEN
            RAISE EXCEPTION 'a disbursement is born beside its instalment loan PENDING_DISBURSEMENT (P11-TSK-012)';
        END IF;
        -- Path X exactly when the application preferred an external account (A25; ADR-0097).
        IF (preference = 'EXTERNAL_ACCOUNT') <> (NEW.payout_method_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a disbursement names a payout method exactly when its application preferred an external account (P11-TSK-012)';
        END IF;
        NEW.created_at := statement_timestamp();
        NEW.deadline_at := statement_timestamp() + make_interval(hours => deadline_hours);
        -- Due for the disbursement sweep at once (P11-TSK-014).
        NEW.next_attempt_at := statement_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a disbursement is never deleted (P11-TSK-012, INV-HIST-01)';
    END IF;
    -- PENDING -> POSTED | FAILED, each beside the loan's own edge, and the sweep's permit: P11-TSK-014's.
    RAISE EXCEPTION 'a disbursement does not move until P11-TSK-014 admits its edges (P11-TSK-012)';
END;
$$;

CREATE TRIGGER loan_disbursement_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_disbursement
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_disbursement_permits_only_machine_edges();

CREATE TRIGGER loan_disbursement_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_disbursement
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_event (
    id              uuid        NOT NULL,
    loan_id         uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    reason          text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT loan_event_pk PRIMARY KEY (id),
    CONSTRAINT loan_event_loan_fk FOREIGN KEY (loan_id) REFERENCES lending.loan (id),
    -- LoanStatus, and its only edges (LENDING_LIFECYCLES.md section 4.5).
    CONSTRAINT loan_event_edge CHECK (
        (from_status IS NULL AND to_status IN ('PENDING_DISBURSEMENT', 'ACTIVE'))
        OR (from_status = 'PENDING_DISBURSEMENT' AND to_status IN ('ACTIVE', 'CANCELLED'))
        OR (from_status = 'ACTIVE' AND to_status IN ('CLOSING', 'CLOSED'))
        OR (from_status = 'CLOSING' AND to_status = 'CLOSED')),
    -- A reason CODE, never free text.
    CONSTRAINT loan_event_reason_is_a_code CHECK (reason ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT loan_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE INDEX loan_event_by_loan ON lending.loan_event (loan_id, occurred_at);

COMMENT ON TABLE lending.loan_event IS
    'A loan account''s machine as history (P11-TSK-012): every edge, its actor, reason code and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION lending.loan_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a loan event is append-only (P11-TSK-012, INV-HIST-01)';
    END IF;
    NEW.occurred_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_event
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_event_is_append_only();

CREATE TRIGGER loan_event_is_never_truncated
    BEFORE TRUNCATE ON lending.loan_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION lending.loan_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The two deferred checks: the loan and the offer's ACCEPTED edge commit together, with the agreement and evidence.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.loan_born_beside_its_acceptance()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM lending.loan_offer o WHERE o.id = NEW.offer_id AND o.status = 'ACCEPTED') THEN
        RAISE EXCEPTION 'a loan committed without its offer''s ACCEPTED edge (P11-TSK-012)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM lending.loan_agreement a WHERE a.loan_id = NEW.id AND a.version = 1)
            OR NOT EXISTS (SELECT 1 FROM lending.loan_acceptance e
                            JOIN lending.loan_agreement a ON a.id = e.agreement_id
                           WHERE e.loan_id = NEW.id AND a.version = 1) THEN
        RAISE EXCEPTION 'a loan committed without its agreement v1 and its acceptance evidence (P11-TSK-012)';
    END IF;
    IF NEW.kind = 'INSTALMENT'
            AND NOT EXISTS (SELECT 1 FROM lending.loan_disbursement d WHERE d.loan_id = NEW.id) THEN
        RAISE EXCEPTION 'a loan committed without its disbursement (P11-TSK-012)';
    END IF;
    IF NEW.kind = 'REVOLVING' AND EXISTS (SELECT 1 FROM lending.loan_disbursement d WHERE d.loan_id = NEW.id) THEN
        RAISE EXCEPTION 'a credit line has no disbursement - it draws (P11-TSK-012, A25)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_born_beside_its_acceptance
    AFTER INSERT ON lending.loan
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION lending.loan_born_beside_its_acceptance();

CREATE OR REPLACE FUNCTION lending.loan_offer_accepted_beside_its_loan()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1
              FROM lending.loan l
              JOIN lending.loan_agreement a ON a.loan_id = l.id AND a.version = 1 AND a.offer_id = l.offer_id
              JOIN lending.loan_acceptance e ON e.agreement_id = a.id
             WHERE l.offer_id = NEW.id) THEN
        RAISE EXCEPTION 'an offer committed ACCEPTED without its loan, agreement v1 and acceptance evidence (P11-TSK-012)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loan_offer_accepted_beside_its_loan
    AFTER UPDATE ON lending.loan_offer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'ACCEPTED')
    EXECUTE FUNCTION lending.loan_offer_accepted_beside_its_loan();

-- ---------------------------------------------------------------------------------------------
-- The offer's edge function, replaced: OFFERED -> ACCEPTED admitted, beside its loan and unexpired.
-- ---------------------------------------------------------------------------------------------
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
    -- The three clock-sided edges: the customer's two while expires_at > the database clock, the expiry once <=  -
    -- exactly one of them at the boundary instant.
    IF NEW.status IN ('DECLINED', 'ACCEPTED') THEN
        IF OLD.expires_at <= statement_timestamp() THEN
            RAISE EXCEPTION 'an expired loan offer cannot be % - it expires (P11-TSK-011, P11-TSK-012)', lower(NEW.status);
        END IF;
        -- ACCEPTED only beside its loan (P11-TSK-012): the loan is born first, in the same transaction, and the deferred
        -- check holds its agreement and evidence beside it at commit.
        IF NEW.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM lending.loan l WHERE l.offer_id = NEW.id) THEN
            RAISE EXCEPTION 'a loan offer is ACCEPTED only beside its loan (P11-TSK-012)';
        END IF;
    ELSIF NEW.status = 'EXPIRED' THEN
        IF OLD.expires_at > statement_timestamp() THEN
            RAISE EXCEPTION 'a loan offer expires only once expires_at <= the database clock (P11-TSK-011)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a loan offer moves OFFERED -> ACCEPTED | DECLINED | EXPIRED; % -> % is not an edge (P11-TSK-011)', OLD.status, NEW.status;
    END IF;
    NEW.closed_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- The grants: every table here SELECT and INSERT only - nothing born by the acceptance moves until its task admits the
-- edge and grants the column. Never DELETE.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON lending.loan TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_event TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_agreement TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_acceptance TO finapp_app;
GRANT SELECT, INSERT ON lending.loan_disbursement TO finapp_app;
