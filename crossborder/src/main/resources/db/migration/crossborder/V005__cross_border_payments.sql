-- =============================================================================================
-- P9-TSK-019 - the cross-border payment (PHASE_9_PLAN.md section 12.8, the lifecycle document 3.5;
-- INV-XB-01, INV-XB-02, INV-XB-03, INV-FX-04).
--
-- A payment is born SUBMITTED in the authorization's one transaction (T-b): the offer's quote
-- accepted, the total debit held, the outbound credit dispatched with its end-to-end reference, the
-- cover born. One payment per quote and per offer, one per (owner, dispatch key). The machine
-- (SUBMITTED -> IN_TRANSIT | FAILED; IN_TRANSIT -> DELIVERED | RETURNED; DELIVERED -> RETURNED) is
-- held here for every writer, with its append-only history; the later edges are the outcome
-- appliers' (P9-TSK-020 on).
-- =============================================================================================

CREATE TABLE crossborder.payment (
    id                 uuid        PRIMARY KEY,
    owner_party        uuid        NOT NULL,
    beneficiary_id     uuid        NOT NULL REFERENCES crossborder.beneficiary (id),
    offer_id           uuid        NOT NULL REFERENCES crossborder.payment_offer (id),
    quote_id           uuid        NOT NULL,
    corridor           text        NOT NULL,
    dispatch_key       text        NOT NULL,
    outbound_credit_id uuid        NOT NULL,
    cover_id           uuid        NOT NULL,
    hold_id            uuid        NOT NULL,
    status             text        NOT NULL,
    failure_reason     text,
    created_at         timestamptz NOT NULL,
    CONSTRAINT payment_one_per_quote UNIQUE (quote_id),
    CONSTRAINT payment_one_per_offer UNIQUE (offer_id),
    CONSTRAINT payment_one_per_outbound_credit UNIQUE (outbound_credit_id),
    CONSTRAINT payment_one_per_dispatch_key UNIQUE (owner_party, dispatch_key),
    CONSTRAINT payment_dispatch_key_is_bounded CHECK (char_length(dispatch_key) BETWEEN 1 AND 400),
    CONSTRAINT payment_corridor_is_coded CHECK (corridor ~ '^[A-Z]{3}-[A-Z]{3}-[A-Z]{2}$'),
    CONSTRAINT payment_status_is_known CHECK (status IN ('SUBMITTED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED')),
    CONSTRAINT payment_failure_reason_is_known
        CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED', 'RECALLED')),
    CONSTRAINT payment_failure_reason_iff_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX payment_by_owner ON crossborder.payment (owner_party, created_at DESC);

COMMENT ON TABLE crossborder.payment IS
    'A cross-border payment (P9-TSK-019, the lifecycle document 3.5): born SUBMITTED in the authorization''s '
    'one transaction - the quote accepted, the total debit held, the outbound credit dispatched, the cover born. '
    'One per quote, per offer and per (owner, dispatch key). SUBMITTED -> IN_TRANSIT | FAILED; IN_TRANSIT -> '
    'DELIVERED | RETURNED; DELIVERED -> RETURNED, for every writer.';

CREATE OR REPLACE FUNCTION crossborder.payment_edge_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' THEN
            RAISE EXCEPTION 'a cross-border payment is born SUBMITTED (P9-TSK-019)';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a cross-border payment is never deleted (INV-HIST-01)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'failure_reason') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'failure_reason') THEN
        RAISE EXCEPTION 'a cross-border payment''s identity is frozen (P9-TSK-019, INV-XB-03)';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            (OLD.status = 'SUBMITTED' AND NEW.status IN ('IN_TRANSIT', 'FAILED'))
            OR (OLD.status = 'IN_TRANSIT' AND NEW.status IN ('DELIVERED', 'RETURNED'))
            OR (OLD.status = 'DELIVERED' AND NEW.status = 'RETURNED')) THEN
        RAISE EXCEPTION 'a cross-border payment moves % -> % only along its machine (P9-TSK-019)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_edge_is_legal
    BEFORE INSERT OR UPDATE OR DELETE ON crossborder.payment
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.payment_edge_is_legal();

CREATE TABLE crossborder.payment_event (
    id          uuid        PRIMARY KEY,
    payment_id  uuid        NOT NULL REFERENCES crossborder.payment (id),
    from_status text,
    to_status   text        NOT NULL,
    cause       text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT payment_event_from_is_known
        CHECK (from_status IN ('SUBMITTED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED')),
    CONSTRAINT payment_event_to_is_known
        CHECK (to_status IN ('SUBMITTED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'FAILED')),
    CONSTRAINT payment_event_cause_is_bounded CHECK (char_length(cause) BETWEEN 1 AND 64),
    CONSTRAINT payment_event_birth_has_no_origin CHECK ((from_status IS NULL) = (to_status = 'SUBMITTED'))
);

CREATE INDEX payment_event_by_payment ON crossborder.payment_event (payment_id, occurred_at);

GRANT SELECT, INSERT ON crossborder.payment TO finapp_app;
GRANT UPDATE (status, failure_reason) ON crossborder.payment TO finapp_app;
GRANT SELECT, INSERT ON crossborder.payment_event TO finapp_app;
