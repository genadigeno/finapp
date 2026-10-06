-- X-TSK-013: the payout's send permit is stamped by the database (payments V028's rule, and its
-- reasons: ADR-0057 section 4's skew premise removed).
--
-- Runs AFTER merchant_payout_permits_only_machine_edges (BEFORE triggers fire in name order), so a
-- backward write is still refused exactly as before; any forward write is then re-stamped to the
-- database's own instant, strictly forward. The birth permit is written by JdbcMerchantPayoutStore as
-- GREATEST(<its created_at>, statement_timestamp()), held by SendPermitsAreTheDatabasesTest.

CREATE FUNCTION merchant.merchant_payout_send_permit_is_the_databases()
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

CREATE TRIGGER merchant_payout_send_permit_is_the_databases
    BEFORE UPDATE OF last_dispatched_at ON merchant.merchant_payout
    FOR EACH ROW
    EXECUTE FUNCTION merchant.merchant_payout_send_permit_is_the_databases();
