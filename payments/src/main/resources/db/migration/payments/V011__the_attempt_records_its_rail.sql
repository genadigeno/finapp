-- The attempt records its rail (P7-TSK-001, ADR-0059 section 1).
--
-- WHY A COLUMN AND NOT WIRING. An outcome is resolved by any instance - a webhook, a sweeper
-- tick, a resolver on a machine that never dispatched the payment - and the rail decides
-- financial facts: which clearing position the completion posts to, how a refund executes,
-- whether a reversal exists to honour. The only multi-instance-honest source for "which rail
-- is this payment on" is therefore the row itself, stamped in the dispatching transaction and
-- frozen ever after, exactly as the provider idempotency reference is (ADR-0046, INV-PAY-04's
-- reasoning pointed at capabilities). The declared capability set the name keys into is code
-- (RailCapabilities, immutable per build); this column is the per-payment fact.
--
-- Backfilled with the card rail: every attempt that exists was dispatched on the one rail the
-- platform has ever had (ADR-0049), so the backfill records history, it does not guess. The
-- edge trigger is held off for the backfill alone - a status-unchanged update is exactly what
-- the V003 function refuses (the V009 idiom).

ALTER TABLE payments.payment_attempt
    ADD COLUMN rail text;

ALTER TABLE payments.payment_attempt DISABLE TRIGGER payment_attempt_permits_only_machine_edges;
UPDATE payments.payment_attempt SET rail = 'card';
ALTER TABLE payments.payment_attempt ENABLE TRIGGER payment_attempt_permits_only_machine_edges;

ALTER TABLE payments.payment_attempt
    ALTER COLUMN rail SET NOT NULL,
    ADD CONSTRAINT payment_attempt_rail_shape
        CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$');

-- The V003 function, REPLACED (V003 is applied history): the birth facts now name the rail
-- too, so no writer - the migrator included - can move a payment to another rail after it was
-- dispatched. The payload and edge conditions are restated verbatim; PaymentsMigrationTest
-- reads them from here as the current definition.
CREATE OR REPLACE FUNCTION payments.payment_attempt_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.intent_id <> NEW.intent_id
        OR OLD.auth_reference <> NEW.auth_reference
        OR OLD.rail <> NEW.rail
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a payment attempt''s birth facts are immutable: identity, intent, the dispatch reference and the rail are what the operation is (ADR-0046, ADR-0059, INV-HIST-01)';
    END IF;
    IF (OLD.capture_reference IS NOT NULL AND NEW.capture_reference IS DISTINCT FROM OLD.capture_reference)
        OR (OLD.auth_provider_reference IS NOT NULL AND NEW.auth_provider_reference IS DISTINCT FROM OLD.auth_provider_reference)
        OR (OLD.capture_provider_reference IS NOT NULL AND NEW.capture_provider_reference IS DISTINCT FROM OLD.capture_provider_reference)
        OR (OLD.authorized_amount_minor IS NOT NULL AND NEW.authorized_amount_minor IS DISTINCT FROM OLD.authorized_amount_minor)
        OR (OLD.authorized_currency IS NOT NULL AND NEW.authorized_currency IS DISTINCT FROM OLD.authorized_currency)
        OR (OLD.authorized_scale IS NOT NULL AND NEW.authorized_scale IS DISTINCT FROM OLD.authorized_scale)
        OR (OLD.captured_amount_minor IS NOT NULL AND NEW.captured_amount_minor IS DISTINCT FROM OLD.captured_amount_minor)
        OR (OLD.captured_currency IS NOT NULL AND NEW.captured_currency IS DISTINCT FROM OLD.captured_currency)
        OR (OLD.captured_scale IS NOT NULL AND NEW.captured_scale IS DISTINCT FROM OLD.captured_scale)
        OR (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason) THEN
        RAISE EXCEPTION 'a recorded provider fact never changes: payload columns move only from NULL to a value (P5-TSK-008, INV-HIST-02)';
    END IF;
    IF NOT ((OLD.status = 'AUTH_DISPATCHED' AND NEW.status IN ('AUTH_UNKNOWN', 'AUTHORIZED', 'FAILED'))
            OR (OLD.status = 'AUTH_UNKNOWN' AND NEW.status IN ('AUTHORIZED', 'FAILED'))
            OR (OLD.status = 'AUTHORIZED' AND NEW.status IN ('CAPTURE_DISPATCHED'))
            OR (OLD.status = 'CAPTURE_DISPATCHED' AND NEW.status IN ('CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED'))
            OR (OLD.status = 'CAPTURE_UNKNOWN' AND NEW.status IN ('CAPTURED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a payment attempt moves only along the machine''s edges (ADR-0045, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.payment_attempt.rail IS
    'The rail this attempt was dispatched on (ADR-0059) - a birth fact, frozen by the edge '
    'trigger for every writer. The name keys into the build''s declared RailCapabilities; '
    'the declaration is code, this column is the per-payment fact an outcome resolver on any '
    'instance reads.';
