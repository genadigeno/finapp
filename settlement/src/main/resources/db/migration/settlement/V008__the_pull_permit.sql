-- P8-TSK-021: the pull permit - how a herd of instances pulling one source's report makes one
-- attempt per window (ADR-0066 sections 1 and 10; the send permits' shape, ADR-0057 section 4).
--
-- WHAT IT IS, AND WHAT IT IS NOT
--   Pacing, never correctness. The content unique on settlement.file already makes ten instances
--   pulling one report - or a pull racing an upload of the same bytes - ONE file with a DUPLICATE
--   receipt per delivery. The permit only keeps a herd from hammering the counterparty: each
--   (source, business key) holds the last attempt's instant, and a renewal is a conditional
--   upsert that takes the permit only when the window since that instant has passed.
--
-- STRICTLY FORWARD, FOR EVERY WRITER
--   Every renewal strictly advances last_attempt_at - the refund's and the dispute response's
--   send permits since the Phase 7 -> 8 transition's repair: an EQUAL value is refused as a step
--   back, so two takers can never both read their renewal as "mine". attempts only grows.
--
-- The business key is what a pull fetches: an ISO business date for a daily report, or a scheme
-- settlement cycle token for the cycle report (the batch's external_batch_ref).

CREATE TABLE settlement.pull_permit (
    source_id        UUID        NOT NULL,
    business_key     TEXT        NOT NULL,
    last_attempt_at  TIMESTAMPTZ NOT NULL,
    attempts         INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT pull_permit_pk PRIMARY KEY (source_id, business_key),
    CONSTRAINT pull_permit_source_fk FOREIGN KEY (source_id) REFERENCES settlement.source (id),
    CONSTRAINT pull_permit_business_key_shape CHECK (
        business_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT pull_permit_attempts_counted CHECK (attempts >= 1),
    CONSTRAINT pull_permit_attempt_after_birth CHECK (last_attempt_at >= created_at)
);

COMMENT ON TABLE settlement.pull_permit IS
    'Pacing for pulls (P8-TSK-021, ADR-0066): the last attempt per (source, business key), renewed by a conditional upsert that strictly advances it - never a correctness arbiter; the content unique on settlement.file is.';

CREATE OR REPLACE FUNCTION settlement.pull_permit_moves_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.business_key IS DISTINCT FROM OLD.business_key
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a pull permit''s identity is frozen (P8-TSK-021)';
    END IF;
    IF NEW.last_attempt_at <= OLD.last_attempt_at THEN
        RAISE EXCEPTION 'a pull permit only moves strictly forward: an equal or earlier attempt is a step back (P8-TSK-021)';
    END IF;
    IF NEW.attempts <= OLD.attempts THEN
        RAISE EXCEPTION 'a pull permit''s attempts only grow (P8-TSK-021)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pull_permit_moves_forward_only
    BEFORE UPDATE ON settlement.pull_permit
    FOR EACH ROW
    EXECUTE FUNCTION settlement.pull_permit_moves_forward_only();

CREATE OR REPLACE FUNCTION settlement.pull_permit_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a pull permit is never deleted: it is the herd''s memory of its last attempt (P8-TSK-021)';
END;
$$;

CREATE TRIGGER pull_permit_is_never_deleted
    BEFORE DELETE ON settlement.pull_permit
    FOR EACH ROW
    EXECUTE FUNCTION settlement.pull_permit_is_never_deleted();

GRANT SELECT, INSERT ON settlement.pull_permit TO finapp_app;
GRANT UPDATE (last_attempt_at, attempts) ON settlement.pull_permit TO finapp_app;
