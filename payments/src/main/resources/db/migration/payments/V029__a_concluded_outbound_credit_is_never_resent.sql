-- A concluded outbound credit is never re-sent (the Phase 9 -> 10 transition, 2026-10-07).
--
-- The send permit (last_dispatched_at) moves only when an instruction is put on the wire again, and the
-- payment desk's takeover renews it before re-sending E. V025's trigger stamped the permit forward for every
-- writer but never asked whether the credit was still unconcluded, and the store's renewal guarded only the
-- recall. So a takeover under the same idempotency key after the credit had been concluded FAILED
-- (NEVER_RECEIVED - by definition a provider that never saw E) re-sent the instruction: the provider paid the
-- beneficiary while the customer's hold was already released and the cover unwound.
--
-- From here a permit moves only on a DISPATCHED or UNKNOWN credit with no recall requested, for every
-- writer; JdbcOutboundCreditStore.renewPermit asks the same question and answers false, and the desk then
-- sends nothing.
CREATE FUNCTION payments.outbound_credit_resends_only_while_unconcluded() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND (OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') OR OLD.recall_requested_at IS NOT NULL) THEN
        RAISE EXCEPTION 'outbound credit % is % and is never re-sent (the Phase 9 -> 10 transition)', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER outbound_credit_resends_only_while_unconcluded
    BEFORE UPDATE OF last_dispatched_at ON payments.outbound_credit
    FOR EACH ROW EXECUTE FUNCTION payments.outbound_credit_resends_only_while_unconcluded();
