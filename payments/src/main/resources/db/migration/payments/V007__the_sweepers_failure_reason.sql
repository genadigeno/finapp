-- The sweeper's failure reason arrives with its producer (P5-TSK-014).
--
-- PaymentFailureReason gains NEVER_RECEIVED: the resolution of an explicit UNRECOGNISED query
-- answer - the provider stating in so many words that it never saw our reference, which is the
-- sweeper's licence to resolve a stranded dispatch to FAILED (QueryAnswer's recorded rule; a
-- 404 or any other status code never earns it). The reason CHECK is generated from
-- PaymentFailureReason.sqlValueList(), one definition - and since V003 is applied history
-- (ADR-0011, forward-only), the constraint is REPLACED here rather than edited there:
-- PaymentsMigrationTest's reason reconciliation reads THIS file from now on, so the enum and
-- the live constraint still cannot drift. V003's two-value text stands as what was true when
-- it ran.
--
-- Nothing else changes: the reason<->FAILED coherence CHECK, the stage-facts CASE and the
-- transition triggers are reason-agnostic, and no existing row can violate a WIDENED list.

ALTER TABLE payments.payment_attempt
    DROP CONSTRAINT payment_attempt_failure_reason_is_known;
ALTER TABLE payments.payment_attempt
    ADD CONSTRAINT payment_attempt_failure_reason_is_known
        CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED'));
