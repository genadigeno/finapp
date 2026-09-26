-- The attempt's machine per interaction model, the intent's capture mode, and the
-- credit-account rename (P7-TSK-002, ADR-0059 section 2).
--
-- THREE MACHINES, ONE TABLE. A card attempt negotiates in two acts with an issuer; a push
-- attempt is one credit transfer other institutions decide; a book attempt is our own posting
-- and is born finished. Which machine a row lives in is a birth fact beside its rail
-- (interaction_model, backfilled TWO_STEP: every attempt that exists was a card attempt), and
-- every layer keys its judgement on it - the vocabulary CHECK, the payload CHECKs, and the
-- edge trigger, whose conditions are generated per model from InteractionModel.edges() and
-- reconciled by PaymentsMigrationTest exactly as V003's were. No non-terminal state name is
-- shared across models, so no query can mistake one rail's completion for another's.
--
-- THE CAPTURE MODE. The sweeper's stranded-chain leg (the Phase 6 -> 7 transition's) finishes
-- a capture a crash interrupted. The card void (P7-TSK-004) will make "an authorization meant
-- to rest" a real state of affairs, and the leg must be structurally unable to take that
-- money: capture_mode records the payer's arrangement at birth, frozen, AUTOMATIC for every
-- intent any current door creates, and the leg's candidate query honours it from today.
--
-- THE RENAME, LANDING WITH THE TRIGGER IT WAS WAITING FOR. payment_intent.wallet_account_id
-- has held a merchant payable since P6-TSK-005: the MEANING was right - where the capture
-- will credit - and the name narrower than the meaning, recorded debt because renaming a
-- column of applied history needs the every-writer trigger function replaced (a plpgsql body
-- does not follow a rename). This migration replaces that function anyway (capture_mode joins
-- the frozen facts), so the debt is paid here: credit_account_id, the meaning's own name.
-- V010's index was named payment_intent_in_flight_by_credit_account on the day it was built,
-- for exactly this day; an index follows its column's rename by itself.

-- ----------------------------------------------------------------------- the attempt

ALTER TABLE payments.payment_attempt
    ADD COLUMN interaction_model text;

ALTER TABLE payments.payment_attempt DISABLE TRIGGER payment_attempt_permits_only_machine_edges;
UPDATE payments.payment_attempt SET interaction_model = 'TWO_STEP';
ALTER TABLE payments.payment_attempt ENABLE TRIGGER payment_attempt_permits_only_machine_edges;

ALTER TABLE payments.payment_attempt
    ALTER COLUMN interaction_model SET NOT NULL,
    -- Generated from InteractionModel.sqlValueList().
    ADD CONSTRAINT payment_attempt_model_is_known
        CHECK (interaction_model IN ('TWO_STEP', 'PUSH', 'BOOK')),
    -- The old whole-vocabulary CHECK gives way to one that binds vocabulary AND model at
    -- once, each list generated from InteractionModel.sqlStatusList(). V003's constraint is
    -- applied history; this is the current definition.
    DROP CONSTRAINT payment_attempt_status_is_known,
    ADD CONSTRAINT payment_attempt_status_matches_model
        CHECK ((interaction_model = 'TWO_STEP'
                    AND status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED'))
            OR (interaction_model = 'PUSH'
                    AND status IN ('FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED'))
            OR (interaction_model = 'BOOK'
                    AND status IN ('FAILED', 'EXECUTED'))),
    -- The dispatch reference is the TWO-STEP birth fact (ADR-0046: committed before the
    -- provider is asked). A push execution's and a book movement's own references arrive with
    -- their rails' tasks, in their own columns - reusing this one would be the vocabulary
    -- blur ADR-0059 refuses, one level down.
    ALTER COLUMN auth_reference DROP NOT NULL,
    ADD CONSTRAINT payment_attempt_two_step_carries_its_dispatch_reference
        CHECK ((interaction_model = 'TWO_STEP') = (auth_reference IS NOT NULL)),
    -- No two-step fact on another model's row, ever: the stage CHECKs above bind the
    -- two-step shapes, and this closes the other direction. The monetary triples'
    -- all-or-nothing CHECKs make the minor-unit columns stand for their triples.
    ADD CONSTRAINT payment_attempt_foreign_model_carries_no_two_step_facts
        CHECK (interaction_model = 'TWO_STEP'
            OR (capture_reference IS NULL
                AND auth_provider_reference IS NULL
                AND capture_provider_reference IS NULL
                AND authorized_amount_minor IS NULL
                AND captured_amount_minor IS NULL));

-- One live attempt per intent, now over three machines: EXECUTED frees the slot exactly as
-- CAPTURED does. The predicate is generated from sqlTerminalValueList(), which is why the
-- index is recreated here rather than left reading two of three terminals (V003 is applied
-- history; this is the current definition).
DROP INDEX payments.payment_attempt_one_live_per_intent;
CREATE UNIQUE INDEX payment_attempt_one_live_per_intent
    ON payments.payment_attempt (intent_id)
    WHERE status NOT IN ('CAPTURED', 'FAILED', 'EXECUTED');

-- The history's vocabulary widens with the machines (generated from
-- PaymentAttemptStatus.sqlValueList()). No model column here, deliberately: every legal
-- edge's from-state is model-exclusive and the book machine has no edges at all, so a history
-- row's own vocabulary says which machine moved - and the attempt row it references stores
-- the model besides.
ALTER TABLE payments.payment_attempt_event
    DROP CONSTRAINT payment_attempt_event_from_status_is_known,
    ADD CONSTRAINT payment_attempt_event_from_status_is_known
        CHECK (from_status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED')),
    DROP CONSTRAINT payment_attempt_event_to_status_is_known,
    ADD CONSTRAINT payment_attempt_event_to_status_is_known
        CHECK (to_status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED'));

-- The V011 function, REPLACED (V011 is applied history): the model joins the frozen birth
-- facts, and the edge conditions become each model's own, generated from
-- InteractionModel.edges() - the book machine contributes none, so any status move on a book
-- row is refused for every writer. The payload freeze is restated verbatim.
CREATE OR REPLACE FUNCTION payments.payment_attempt_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.intent_id <> NEW.intent_id
        OR OLD.auth_reference IS DISTINCT FROM NEW.auth_reference
        OR OLD.rail <> NEW.rail
        OR OLD.interaction_model <> NEW.interaction_model
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a payment attempt''s birth facts are immutable: identity, intent, the dispatch reference, the rail and the interaction model are what the operation is (ADR-0046, ADR-0059, INV-HIST-01)';
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
    IF NOT ((OLD.interaction_model = 'TWO_STEP'
                AND ((OLD.status = 'AUTH_DISPATCHED' AND NEW.status IN ('AUTH_UNKNOWN', 'AUTHORIZED', 'FAILED'))
                    OR (OLD.status = 'AUTH_UNKNOWN' AND NEW.status IN ('AUTHORIZED', 'FAILED'))
                    OR (OLD.status = 'AUTHORIZED' AND NEW.status IN ('CAPTURE_DISPATCHED'))
                    OR (OLD.status = 'CAPTURE_DISPATCHED' AND NEW.status IN ('CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED'))
                    OR (OLD.status = 'CAPTURE_UNKNOWN' AND NEW.status IN ('CAPTURED', 'FAILED'))))
            OR (OLD.interaction_model = 'PUSH'
                AND ((OLD.status = 'AWAITING_PAYER' AND NEW.status IN ('FAILED', 'EXECUTION_DISPATCHED'))
                    OR (OLD.status = 'EXECUTION_DISPATCHED' AND NEW.status IN ('FAILED', 'EXECUTION_UNKNOWN', 'EXECUTED'))
                    OR (OLD.status = 'EXECUTION_UNKNOWN' AND NEW.status IN ('FAILED', 'EXECUTED'))))) THEN
        RAISE EXCEPTION 'a payment attempt moves only along its own model''s edges (ADR-0045, ADR-0059, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.payment_attempt.interaction_model IS
    'Which machine this attempt lives in (ADR-0059 section 2) - a birth fact beside the rail, '
    'frozen by the edge trigger for every writer. The machines are declared on '
    'InteractionModel and generated into this schema; no non-terminal state name is shared '
    'across models, so vocabulary and model bind each other.';

-- ----------------------------------------------------------------------- the intent

ALTER TABLE payments.payment_intent
    ADD COLUMN capture_mode text;

ALTER TABLE payments.payment_intent DISABLE TRIGGER payment_intent_permits_only_machine_edges;
UPDATE payments.payment_intent SET capture_mode = 'AUTOMATIC';
ALTER TABLE payments.payment_intent ENABLE TRIGGER payment_intent_permits_only_machine_edges;

ALTER TABLE payments.payment_intent
    ALTER COLUMN capture_mode SET NOT NULL,
    -- Generated from CaptureMode.sqlValueList().
    ADD CONSTRAINT payment_intent_capture_mode_is_known
        CHECK (capture_mode IN ('AUTOMATIC', 'MANUAL'));

ALTER TABLE payments.payment_intent
    RENAME COLUMN wallet_account_id TO credit_account_id;

-- The V002 function, REPLACED (V002 is applied history): the frozen facts now say the
-- renamed column's name - the reason the rename waited for this migration - and the capture
-- mode joins them. The edge conditions are restated verbatim; the intent's machine is
-- deliberately rail-agnostic and does not change (ADR-0059 section 2).
CREATE OR REPLACE FUNCTION payments.payment_intent_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.party_id <> NEW.party_id
        OR OLD.customer_id <> NEW.customer_id
        OR OLD.payment_method_id <> NEW.payment_method_id
        OR OLD.credit_account_id <> NEW.credit_account_id
        OR OLD.capture_mode <> NEW.capture_mode
        OR OLD.amount_minor <> NEW.amount_minor
        OR OLD.currency <> NEW.currency
        OR OLD.scale <> NEW.scale
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a payment intent changes nothing but its status after birth: every status-dependent fact lives where its fact lives (ADR-0045, INV-HIST-01)';
    END IF;
    IF NOT ((OLD.status = 'REQUIRES_CONFIRMATION' AND NEW.status IN ('PROCESSING', 'CANCELLED'))
            OR (OLD.status = 'PROCESSING' AND NEW.status IN ('SUCCEEDED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a payment intent moves only along the machine''s edges: REQUIRES_CONFIRMATION -> {PROCESSING, CANCELLED}, PROCESSING -> {SUCCEEDED, FAILED} (ADR-0045, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.payment_intent.credit_account_id IS
    'The ledger account the capture will credit (ADR-0048): the customer''s wallet for a '
    'top-up, the merchant''s payable for a checkout payment (ADR-0050 section 6). Renamed '
    'from wallet_account_id by V012 - the P6-TSK-005 recorded debt, paid with the trigger '
    'replacement the rename was waiting for.';

COMMENT ON COLUMN payments.payment_intent.capture_mode IS
    'Whether the authorization is an instruction to take the money (AUTOMATIC - every intent '
    'any current door creates) or a reservation awaiting a person (MANUAL - no producer yet; '
    'P7-TSK-004''s territory). A birth fact, frozen; the sweeper''s stranded-chain leg '
    'captures AUTOMATIC intents only (P7-TSK-002, ADR-0059).';
