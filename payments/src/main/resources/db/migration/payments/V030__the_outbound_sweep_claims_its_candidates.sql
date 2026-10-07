-- The outbound sweep claims its candidates, least recently inquired first (the Phase 9 -> 10 transition).
--
-- The sweep read its due credits unlocked and ordered the ones awaiting an outcome by their send permit -
-- which an inquiry never moves. So the oldest RECEIVED or UNKNOWN credits (a corridor slow to conclude, or
-- one in an outage) held the head of every page on every instance, and a newer credit whose send was lost
-- was never inquired: never concluded NEVER_RECEIVED, its hold standing, its recall never sent.
--
-- last_inquired_at is stamped by the database each time the sweep claims a credit; candidates are taken
-- in ONE statement - UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) RETURNING - credits
-- awaiting their outcome first, then delivery polls, each least recently inquired first, so pages rotate and
-- N instances take disjoint pages. A returned credit is no longer polled for its delivery.
ALTER TABLE payments.outbound_credit ADD COLUMN last_inquired_at TIMESTAMPTZ;

CREATE OR REPLACE FUNCTION payments.outbound_credit_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := statement_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DISPATCHED' THEN
            RAISE EXCEPTION 'an outbound credit is born DISPATCHED (P9-TSK-019)';
        END IF;
        NEW.created_at := now;
        NEW.last_dispatched_at := now;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an outbound credit is never deleted (P9-TSK-019)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at' - 'last_inquired_at')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at' - 'last_inquired_at') THEN
        RAISE EXCEPTION 'an outbound credit''s instruction is frozen (P9-TSK-019, INV-XB-03)';
    END IF;
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'an outbound credit''s provider reference is set once (P9-TSK-019)';
    END IF;
    IF OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
        RAISE EXCEPTION 'an outbound credit''s delivery is set once (P9-TSK-019)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        -- The send permit: stamped by the database, strictly forward, for every writer.
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', now);
    END IF;
    IF NEW.last_inquired_at IS DISTINCT FROM OLD.last_inquired_at THEN
        -- The sweep's inquiry stamp: the database's own clock, for every writer (the Phase 9 -> 10 transition).
        NEW.last_inquired_at := now;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            (OLD.status = 'DISPATCHED' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED'))
            OR (OLD.status = 'RECEIVED' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'an outbound credit moves % -> % only along its machine (P9-TSK-019)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

GRANT UPDATE (last_inquired_at) ON payments.outbound_credit TO finapp_app;

CREATE INDEX outbound_credit_inquiry_rotation ON payments.outbound_credit (last_inquired_at)
    WHERE status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED', 'COMPLETED');
