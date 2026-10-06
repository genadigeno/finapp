-- P9-TSK-024: the cancellation request (ADR-0079 point 5, D22; the lifecycle document section 3.11).
--
-- A customer's cancellation of an authorized cross-border payment is a RECALL REQUEST, honoured only on the
-- corridor provider's definitive word. The request is a born-once fact, not a machine: one row per payment,
-- never updated, never deleted. Its outcome lives on the outbound credit (payments.outbound_credit.recall_outcome,
-- V025) and on the payment (FAILED with reason RECALLED, or the ordinary completion when the provider refused).
--
-- Held for every writer by the trigger below: an insert only while the payment is SUBMITTED (a payment in transit
-- is past recall - a return is the receiving side's act), the request time stamped by the database, and no UPDATE
-- or DELETE ever.

CREATE TABLE crossborder.cancellation_request (
    id             uuid        PRIMARY KEY,
    payment_id     uuid        NOT NULL REFERENCES crossborder.payment (id),
    requested_by   text        NOT NULL,
    correlation_id text        NOT NULL,
    requested_at   timestamptz NOT NULL,
    CONSTRAINT cancellation_request_one_per_payment UNIQUE (payment_id),
    CONSTRAINT cancellation_request_actor_is_bounded CHECK (char_length(requested_by) BETWEEN 1 AND 200),
    CONSTRAINT cancellation_request_correlation_is_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

CREATE FUNCTION crossborder.cancellation_request_is_born_once() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a cancellation request is never changed or deleted (P9-TSK-024, INV-HIST-01)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM crossborder.payment p WHERE p.id = NEW.payment_id AND p.status = 'SUBMITTED') THEN
        RAISE EXCEPTION 'only a SUBMITTED payment can be cancelled: past acceptance a recall is refused (P9-TSK-024)';
    END IF;
    NEW.requested_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER cancellation_request_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON crossborder.cancellation_request
    FOR EACH ROW
    EXECUTE FUNCTION crossborder.cancellation_request_is_born_once();

GRANT SELECT, INSERT ON crossborder.cancellation_request TO finapp_app;
