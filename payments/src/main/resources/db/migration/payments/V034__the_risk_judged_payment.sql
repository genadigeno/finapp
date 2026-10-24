-- The risk-judged checkout payment (P13-TSK-010; PHASE_13_PLAN.md sections 7.3, 12.1, 12.3, 12.8; C2, C6; ADR-0060
-- section 3, ADR-0108, ADR-0112; INV-RSK-02, INV-LIFE-01/-02/-04, INV-PAY-04). Risk now judges a card or wallet
-- checkout payment inside the confirmation's first transaction, under the intent row's lock, after the eligibility
-- checks and before anything of the routing decision, the attempt or the intent's move is written, through payments'
-- own seam (PaymentRiskDecision). The verdict decides what Tx1 commits:
--
--     PERMIT -> PROCESSING and the dispatch            as before Phase 13
--     REFUSE -> FAILED with the intent's RISK_REFUSED   no attempt, no routing decision, nothing sent - TERMINAL: the
--                                                      intent is never confirmed again (routing's refusal, by contrast,
--                                                      moves nothing and stays re-confirmable, ADR-0060 section 3)
--     HOLD   -> PROCESSING and the card's AUTH_DISPATCHED attempt, beside an OPEN capture hold (slice 2): the card is
--               authorised as any card payment is, and its capture waits until payments' keyed door captures or voids
--               it, or the expiry leg voids it once held_until has passed on the database clock.
--
-- Two halves:
--
-- 1. THE REFUSAL ON THE INTENT. payment_intent.failure_reason ('RISK_REFUSED', generated from
--    PaymentFailureReason.intentSqlValueList()) - the one status-dependent fact the intent carries, because a refusal
--    precedes every attempt and so has no attempt to carry it (every other reason stays the attempt's, INV-PAY-03). The
--    intent's edge function is REPLACED (V019's body is applied history for it): the edges generated from
--    PaymentIntentStatus.permittedTransitions() with REQUIRES_CONFIRMATION -> FAILED added, admitted ONLY with the
--    reason; the reason arriving with that edge alone and frozen after. No attempt is ever born for a refused intent.
--
-- 2. THE CAPTURE HOLD (slice 2). payment_capture_hold: one row per held intent, beside its card attempt; its window
--    stamped by THIS database in the insert (held_at = statement_timestamp(), held_until = that + the pinned policy's
--    hold validity); its resolution CAPTURE | VOID | EXPIRED recorded exactly once (resolution IS NULL the arbiter;
--    CAPTURE only before held_until, EXPIRED only from it - every writer); and the attempt's AUTHORIZED ->
--    CAPTURE_DISPATCHED refused while the hold stands unresolved or resolved other than CAPTURE.
--
-- EVERY GENERATED LIST HAS ONE DEFINITION; PaymentsMigrationTest fails the build on drift and holds every constraint,
-- index and trigger name declared here once (the Phase 12 V012 lesson).

-- ----------------------------------------------------------------- 1. the refusal on the intent

ALTER TABLE payments.payment_intent
    ADD COLUMN failure_reason text
        -- Generated from PaymentFailureReason.intentSqlValueList().
        CONSTRAINT payment_intent_failure_reason_is_known
            CHECK (failure_reason IN ('RISK_REFUSED')),
    ADD CONSTRAINT payment_intent_failure_reason_only_when_failed
        CHECK (failure_reason IS NULL OR status = 'FAILED');

-- The V019 function, REPLACED (V019 is applied history for it): the frozen facts restated verbatim; the refusal's
-- reason frozen once set and arriving only with its own edge; the edges generated from permittedTransitions().
CREATE OR REPLACE FUNCTION payments.payment_intent_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.party_id <> NEW.party_id
        OR OLD.customer_id <> NEW.customer_id
        OR OLD.payment_method_id IS DISTINCT FROM NEW.payment_method_id
        OR OLD.debit_account_id IS DISTINCT FROM NEW.debit_account_id
        OR OLD.credit_account_id <> NEW.credit_account_id
        OR OLD.capture_mode <> NEW.capture_mode
        OR OLD.amount_minor <> NEW.amount_minor
        OR OLD.currency <> NEW.currency
        OR OLD.scale <> NEW.scale
        OR OLD.created_at <> NEW.created_at
        OR (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason) THEN
        RAISE EXCEPTION 'a payment intent changes nothing but its status after birth, and its refusal''s reason once: every status-dependent fact lives where its fact lives (ADR-0045, INV-HIST-01, P13-TSK-010)';
    END IF;
    IF NOT ((OLD.status = 'REQUIRES_CONFIRMATION' AND NEW.status IN ('PROCESSING', 'FAILED', 'CANCELLED'))
            OR (OLD.status = 'PROCESSING' AND NEW.status IN ('SUCCEEDED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a payment intent moves only along the machine''s edges: REQUIRES_CONFIRMATION -> {PROCESSING, FAILED, CANCELLED}, PROCESSING -> {SUCCEEDED, FAILED} (ADR-0045, INV-LIFE-02/-04, P13-TSK-010)';
    END IF;
    IF (OLD.status = 'REQUIRES_CONFIRMATION' AND NEW.status = 'FAILED')
            <> (NEW.failure_reason IS NOT NULL AND OLD.failure_reason IS NULL) THEN
        RAISE EXCEPTION 'a payment intent fails before its dispatch only as risk''s refusal, with its RISK_REFUSED, and the reason arrives with that edge alone (P13-TSK-010)';
    END IF;
    RETURN NEW;
END;
$$;

-- No attempt is ever born for a refused intent: the refusal precedes every dispatch, and FAILED is terminal.
CREATE FUNCTION payments.payment_attempt_is_never_born_for_a_refused_intent() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payments.payment_intent i
               WHERE i.id = NEW.intent_id AND i.failure_reason IS NOT NULL) THEN
        RAISE EXCEPTION 'a payment intent refused by risk is never dispatched: no attempt is born for it (P13-TSK-010)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_attempt_is_never_born_for_a_refused_intent
    BEFORE INSERT ON payments.payment_attempt
    FOR EACH ROW
    EXECUTE FUNCTION payments.payment_attempt_is_never_born_for_a_refused_intent();

COMMENT ON COLUMN payments.payment_intent.failure_reason IS
    'RISK_REFUSED exactly on an intent risk refused at its confirmation (P13-TSK-010): REQUIRES_CONFIRMATION -> FAILED in the confirmation''s first transaction, before any attempt - the intent''s own reason because no attempt exists to carry it; frozen once set. Internal: the customer''s and the merchant''s answers render the generic FAILED with no reason (INV-RSK-08).';

-- The confirmation's refusal writes the edge and its reason in one statement.
GRANT UPDATE (failure_reason) ON payments.payment_intent TO finapp_app;

-- ----------------------------------------------------------------- 2. the capture hold (slice 2)

CREATE TABLE payments.payment_capture_hold (
    intent_id      uuid        NOT NULL,
    attempt_id     uuid        NOT NULL,
    -- Stamped by the insert on the DATABASE clock and frozen for ever: the expiry leg and the capture door compare
    -- held_until with statement_timestamp(), so the window is the same clock's.
    held_at        timestamptz NOT NULL,
    held_until     timestamptz NOT NULL,
    -- Generated from CaptureHold.Resolution.sqlValueList().
    resolution     text
        CONSTRAINT payment_capture_hold_resolution_is_known
            CHECK (resolution IN ('CAPTURE', 'VOID', 'EXPIRED')),
    -- The key of the door that resolved it (risk.action:<id> from -012/-017); NULL for an expiry.
    resolution_key text
        CONSTRAINT payment_capture_hold_resolution_key_bounded
            CHECK (length(resolution_key) BETWEEN 1 AND 200),
    -- Stamped by the trigger with the resolution, on the database clock.
    resolved_at    timestamptz,
    CONSTRAINT payment_capture_hold_pkey PRIMARY KEY (intent_id),
    CONSTRAINT payment_capture_hold_intent_fk FOREIGN KEY (intent_id) REFERENCES payments.payment_intent (id),
    CONSTRAINT payment_capture_hold_attempt_fk FOREIGN KEY (attempt_id) REFERENCES payments.payment_attempt (id),
    CONSTRAINT payment_capture_hold_one_per_attempt UNIQUE (attempt_id),
    CONSTRAINT payment_capture_hold_window_is_positive CHECK (held_until > held_at),
    CONSTRAINT payment_capture_hold_resolved_whole CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    CONSTRAINT payment_capture_hold_key_is_a_doors
        CHECK (CASE WHEN resolution IN ('CAPTURE', 'VOID') THEN resolution_key IS NOT NULL
                    ELSE resolution_key IS NULL END),
    CONSTRAINT payment_capture_hold_resolved_after_held CHECK (resolved_at IS NULL OR resolved_at >= held_at)
);

-- The expiry leg's claim: the open holds by their deadline (FOR UPDATE SKIP LOCKED, one row per transaction).
CREATE INDEX payment_capture_hold_expiry
    ON payments.payment_capture_hold (held_until, intent_id)
    WHERE resolution IS NULL;

-- Born open, beside its own card attempt, for every writer.
CREATE FUNCTION payments.payment_capture_hold_is_born_open() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.resolution IS NOT NULL OR NEW.resolution_key IS NOT NULL OR NEW.resolved_at IS NOT NULL THEN
        RAISE EXCEPTION 'a capture hold is born open: its resolution arrives with a door or the expiry (P13-TSK-010)'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payments.payment_attempt a
                   WHERE a.id = NEW.attempt_id AND a.intent_id = NEW.intent_id
                     AND a.interaction_model = 'TWO_STEP') THEN
        RAISE EXCEPTION 'a capture hold rides its own intent''s card attempt: only a card authorisation holds (C2, P13-TSK-010)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_capture_hold_is_born_open
    BEFORE INSERT ON payments.payment_capture_hold
    FOR EACH ROW
    EXECUTE FUNCTION payments.payment_capture_hold_is_born_open();

-- Resolved exactly once, on the database clock, for every writer: the window and the birth facts frozen; the
-- resolution moving only from NULL, its instant stamped here; CAPTURE only before held_until, EXPIRED only from it.
CREATE FUNCTION payments.payment_capture_hold_resolves_once() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.intent_id IS DISTINCT FROM OLD.intent_id
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
            OR NEW.held_at IS DISTINCT FROM OLD.held_at
            OR NEW.held_until IS DISTINCT FROM OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold''s intent, attempt and window never move (P13-TSK-010)';
    END IF;
    IF OLD.resolution IS NOT NULL THEN
        RAISE EXCEPTION 'a capture hold is resolved exactly once (P13-TSK-010)';
    END IF;
    IF NEW.resolution IS NULL THEN
        RAISE EXCEPTION 'a capture hold changes only by its resolution (P13-TSK-010)';
    END IF;
    IF NEW.resolution = 'CAPTURE' AND statement_timestamp() >= OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold past its held_until is the expiry''s, never a late capture (P13-TSK-010)';
    END IF;
    IF NEW.resolution = 'EXPIRED' AND statement_timestamp() < OLD.held_until THEN
        RAISE EXCEPTION 'a capture hold expires only from its held_until (P13-TSK-010)';
    END IF;
    NEW.resolved_at := GREATEST(statement_timestamp(), OLD.held_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_capture_hold_resolves_once
    BEFORE UPDATE ON payments.payment_capture_hold
    FOR EACH ROW
    EXECUTE FUNCTION payments.payment_capture_hold_resolves_once();

-- The capture waits for its hold, for every writer: the conditional dispatch (JdbcPaymentAttemptStore) answers false
-- while the hold stands, and this refuses the edge to anybody else.
CREATE FUNCTION payments.payment_attempt_capture_waits_for_its_hold() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'AUTHORIZED' AND NEW.status = 'CAPTURE_DISPATCHED'
            AND EXISTS (SELECT 1 FROM payments.payment_capture_hold h
                        WHERE h.attempt_id = NEW.id AND h.resolution IS DISTINCT FROM 'CAPTURE') THEN
        RAISE EXCEPTION 'a held card payment is captured only once its capture hold is released for CAPTURE (P13-TSK-010)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_attempt_capture_waits_for_its_hold
    BEFORE UPDATE ON payments.payment_attempt
    FOR EACH ROW
    EXECUTE FUNCTION payments.payment_attempt_capture_waits_for_its_hold();

COMMENT ON TABLE payments.payment_capture_hold IS
    'A held card payment''s capture hold (P13-TSK-010): the risk seam''s HOLD authorised the card and deferred its capture; born open beside the attempt in the confirmation''s first transaction, its window on the database clock, resolved exactly once - CAPTURE by payments'' keyed door within held_until, VOID by the keyed void door, EXPIRED by the expiry leg from held_until. The capture or the void is then performed by the existing commands. Internal: nothing of it reaches a customer or merchant surface.';
COMMENT ON COLUMN payments.payment_capture_hold.held_until IS
    'held_at plus the pinned CARD_PAYMENT policy version''s hold validity: past it on the database clock the capture door refuses and the expiry leg resolves EXPIRED and voids the authorisation. Frozen.';
COMMENT ON COLUMN payments.payment_capture_hold.resolution_key IS
    'The idempotency key of the keyed door - capture or void - that resolved the hold (risk.action:<id>); NULL for an expiry. Frozen once set.';

-- SELECT and INSERT for the row's life; UPDATE narrowed to the resolution the doors and the leg write (the trigger
-- stamps resolved_at); no DELETE - a hold's end is its resolution, never an absence.
GRANT SELECT, INSERT ON payments.payment_capture_hold TO finapp_app;
GRANT UPDATE (resolution, resolution_key) ON payments.payment_capture_hold TO finapp_app;
