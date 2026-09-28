-- The refund carries its send permit (the Phase 6 -> 7 transition; ADR-0057 section 4's
-- discipline, brought to the flow that had the takeover first).
--
-- THE DEFECT THIS CLOSES. The refund is a two-transaction keyed command whose lease takeover
-- re-sends the stored reference (V008, P5-TSK-016). A refused connection (NOTHING_SENT) is
-- knowledge that THIS send transmitted nothing - never that an EARLIER send did not. So when a
-- crashed flight's send had reached the provider and the takeover's re-send met a refused
-- connection, the outcome transaction concluded FAILED, released the hold and posted nothing:
-- the customer refunded at the provider, the wallet or payable never debited, and the bound
-- freed for a second refund of the same money. The payout closed this exact hazard at
-- P6-TSK-012 with a send permit; the refund, which had the re-sending takeover first, had none.
--
-- THE PERMIT (last_dispatched_at). Committed before every send of our reference: the dispatch
-- itself (the birth permit, equal to created_at), a takeover's conditional renewal, and the
-- resolution sweep's re-drive of a reference the provider says it never saw. A refused
-- connection fails a refund only when it answers the FIRST send and the locked row's permit is
-- still that send's - then no send can have landed. Every other refused connection proves
-- nothing, and the refund stays honestly unknown with its hold standing (INV-LIFE-03). The one
-- column that moves without a state change: forward only, and only while DISPATCHED or UNKNOWN.
--
-- Backfilled from created_at for rows born before this migration - their only permit was their
-- own dispatch (ADR-0011, forward-only) - with the edge trigger held off for the backfill alone,
-- because a status-unchanged update is exactly what the V004 function refuses.

ALTER TABLE payments.refund
    ADD COLUMN last_dispatched_at timestamptz;

ALTER TABLE payments.refund DISABLE TRIGGER refund_permits_only_machine_edges;
UPDATE payments.refund SET last_dispatched_at = created_at;
ALTER TABLE payments.refund ENABLE TRIGGER refund_permits_only_machine_edges;

ALTER TABLE payments.refund
    ALTER COLUMN last_dispatched_at SET NOT NULL,
    ADD CONSTRAINT refund_permit_follows_birth
        CHECK (last_dispatched_at >= created_at);

-- The V004 function, REPLACED (V004 is applied history): the frozen record now names the
-- dispatch key too (V008 added the column and left it out of the freeze), and the permit is the
-- one change a status-unchanged update may carry. The edge conditions are restated verbatim -
-- PaymentsMigrationTest reads them from here as the current definition.
CREATE OR REPLACE FUNCTION payments.refund_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.attempt_id <> NEW.attempt_id
        OR OLD.amount_minor <> NEW.amount_minor
        OR OLD.currency <> NEW.currency
        OR OLD.scale <> NEW.scale
        OR OLD.reason <> NEW.reason
        OR OLD.hold_reference <> NEW.hold_reference
        OR OLD.provider_idempotency_reference <> NEW.provider_idempotency_reference
        OR OLD.created_at <> NEW.created_at
        OR OLD.dispatch_key IS DISTINCT FROM NEW.dispatch_key THEN
        RAISE EXCEPTION 'a refund''s record is immutable outside its outcome: the privileged act is what stays recorded (P5-TSK-008, INV-HIST-01)';
    END IF;
    IF OLD.provider_reference IS NOT NULL
        AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'a recorded provider fact never changes: the refund''s provider reference moves only from NULL to a value (INV-HIST-02)';
    END IF;
    IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'a refund''s send permit only moves forward (payments V009, ADR-0057 section 4)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN
        RAISE EXCEPTION 'a resolved refund is never sent again (payments V009, ADR-0057 section 4)';
    END IF;
    IF NEW.status = OLD.status THEN
        -- The permit's renewal - the one status-unchanged update, judged above.
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('UNKNOWN', 'COMPLETED', 'FAILED'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a refund moves only along the machine''s edges: DISPATCHED -> {UNKNOWN, COMPLETED, FAILED}, UNKNOWN -> {COMPLETED, FAILED} (ADR-0045, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.refund.last_dispatched_at IS
    'The latest send permit: committed before every send of our reference. A refused connection '
    'fails the refund only when it answers the first send and this is still that send''s permit '
    '(payments V009). Forward only, and only while DISPATCHED or UNKNOWN.';

GRANT UPDATE (last_dispatched_at) ON payments.refund TO finapp_app;
