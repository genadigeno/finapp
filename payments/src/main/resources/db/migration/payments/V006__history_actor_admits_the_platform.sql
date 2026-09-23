-- The lifecycle histories learn who the platform is (P5-TSK-009).
--
-- THE GAP, FOUND ONE TASK LATE AND FIXED BEFORE THE FIRST ROW: V002-V004 copied
-- transfer_event's actor model - actor_id uuid NOT NULL - which is correct for a domain where
-- every transition is a person's act, and wrong for this one: a payment outcome is applied AS
-- THE PLATFORM (PHASE_5_PLAN.md section 11 - a provider's answer has no session), the sweeper's
-- and webhook's transitions to come have no person at all, and Actor.SYSTEM's identifier is the
-- string 'system', not a UUID. Without this change the attempt's post-birth history would be
-- unwritable forever.
--
-- THE MODEL IS audit_record's (platform V009): actor_id TEXT + actor_type TEXT, both bounded -
-- the schema that has recorded system actors since Phase 0. The history rows carry the same
-- attribution vocabulary the audit trail does, so "who moved this row" and "who did this" are
-- one language.
--
-- SAFE BECAUSE THE TABLES ARE PROVABLY EMPTY: no writer of these tables existed before this
-- task - the stores arrive with it - so the type change and the NOT NULL addition apply to zero
-- rows everywhere, and a from-scratch database runs V002..V006 in order on empty tables. The
-- applied migrations stay untouched (ADR-0011).

ALTER TABLE payments.payment_intent_event
    ALTER COLUMN actor_id TYPE text USING actor_id::text;
ALTER TABLE payments.payment_intent_event
    ADD COLUMN actor_type text NOT NULL;
ALTER TABLE payments.payment_intent_event
    ADD CONSTRAINT payment_intent_event_actor_id_bounded
        CHECK (length(actor_id) BETWEEN 1 AND 200);
ALTER TABLE payments.payment_intent_event
    ADD CONSTRAINT payment_intent_event_actor_type_bounded
        CHECK (length(actor_type) BETWEEN 1 AND 50);

ALTER TABLE payments.payment_attempt_event
    ALTER COLUMN actor_id TYPE text USING actor_id::text;
ALTER TABLE payments.payment_attempt_event
    ADD COLUMN actor_type text NOT NULL;
ALTER TABLE payments.payment_attempt_event
    ADD CONSTRAINT payment_attempt_event_actor_id_bounded
        CHECK (length(actor_id) BETWEEN 1 AND 200);
ALTER TABLE payments.payment_attempt_event
    ADD CONSTRAINT payment_attempt_event_actor_type_bounded
        CHECK (length(actor_type) BETWEEN 1 AND 50);

ALTER TABLE payments.refund_event
    ALTER COLUMN actor_id TYPE text USING actor_id::text;
ALTER TABLE payments.refund_event
    ADD COLUMN actor_type text NOT NULL;
ALTER TABLE payments.refund_event
    ADD CONSTRAINT refund_event_actor_id_bounded
        CHECK (length(actor_id) BETWEEN 1 AND 200);
ALTER TABLE payments.refund_event
    ADD CONSTRAINT refund_event_actor_type_bounded
        CHECK (length(actor_type) BETWEEN 1 AND 50);

COMMENT ON COLUMN payments.payment_intent_event.actor_id IS
    'The audit_record actor model: a person''s identity UUID as text, or ''system'' for the platform''s own acts (outcome application, the sweeper, webhooks). actor_type says which vocabulary the id is in.';
COMMENT ON COLUMN payments.payment_attempt_event.actor_id IS
    'As payment_intent_event.actor_id: most of an attempt''s history is the platform''s writing, because the outcomes are a provider''s answers.';
