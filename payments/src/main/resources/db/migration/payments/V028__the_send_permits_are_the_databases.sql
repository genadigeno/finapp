-- X-TSK-013: the Phase 5-7 send permits are stamped by the database.
--
-- Every outbound flow commits a send permit before each send of our reference, and a sweep concludes
-- (or re-drives) only once the latest permit is older than its bound (ADR-0057 section 4, ADR-0062
-- section 3). Until now the permit was stamped by the SENDING instance's clock and judged against the
-- SWEEPING instance's: a sweeper running ahead, or a takeover running behind, saw a live permit as
-- older than it was - correct only while every bound exceeded the instances' skew. The Outbound
-- Credit (V025) and the FX cover were born under a database-stamped permit; these four tables are
-- aligned to the same rule here, and merchant V009 aligns the payout.
--
-- Each trigger runs AFTER its table's machine trigger (BEFORE triggers fire in name order:
-- "..._permits_only_machine_edges" < "..._send_permit_is_the_databases"), so a backward write is still
-- refused exactly as before; any forward write is then re-stamped to the database's own instant,
-- strictly forward. No writer - the stores, a raw statement, a future caller - can leave an instance's
-- instant in a permit. A row's birth permit is written by its store as
-- GREATEST(<its created_at>, statement_timestamp()), held by SendPermitsAreTheDatabasesTest.

CREATE FUNCTION payments.payment_attempt_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.last_dispatched_at IS NOT NULL
            AND NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_attempt_send_permit_is_the_databases
    BEFORE UPDATE OF last_dispatched_at ON payments.payment_attempt
    FOR EACH ROW
    EXECUTE FUNCTION payments.payment_attempt_send_permit_is_the_databases();

CREATE FUNCTION payments.withdrawal_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER withdrawal_send_permit_is_the_databases
    BEFORE UPDATE OF last_dispatched_at ON payments.withdrawal
    FOR EACH ROW
    EXECUTE FUNCTION payments.withdrawal_send_permit_is_the_databases();

CREATE FUNCTION payments.refund_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER refund_send_permit_is_the_databases
    BEFORE UPDATE OF last_dispatched_at ON payments.refund
    FOR EACH ROW
    EXECUTE FUNCTION payments.refund_send_permit_is_the_databases();

CREATE FUNCTION payments.dispute_response_send_permit_is_the_databases()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.send_permit IS DISTINCT FROM OLD.send_permit THEN
        NEW.send_permit := GREATEST(OLD.send_permit + INTERVAL '1 microsecond', statement_timestamp());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_response_send_permit_is_the_databases
    BEFORE UPDATE OF send_permit ON payments.dispute_response
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_response_send_permit_is_the_databases();
