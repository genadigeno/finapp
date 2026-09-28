-- P7-TSK-004: the card void (ADR-0059 section 1's declared reversal, INV-REV-03's revocable
-- half). The two-step machine gains VOID_DISPATCHED, VOID_UNKNOWN and VOIDED; the attempt
-- row gains the void's two facts - our reference, minted before the send (INV-PAY-04), and
-- the provider's acknowledgement, arriving exactly with VOIDED. V012's constraints and
-- trigger are applied history; this migration REPLACES them with definitions regenerated
-- from the same machine (InteractionModel.edges(), sqlStatusList(), sqlTerminalValueList()),
-- exactly as V012 replaced V011's.

-- ----------------------------------------------------------------------- the columns

ALTER TABLE payments.payment_attempt
    ADD COLUMN void_reference text
        CONSTRAINT payment_attempt_void_reference_shape
            CHECK (void_reference ~ '^[A-Za-z0-9-]{1,64}$'),
    ADD COLUMN void_provider_reference text
        CONSTRAINT payment_attempt_void_provider_reference_shape
            CHECK (void_provider_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    ADD CONSTRAINT payment_attempt_void_reference_is_unique UNIQUE (void_reference),
    ADD CONSTRAINT payment_attempt_void_provider_reference_is_unique
        UNIQUE (void_provider_reference);

-- ----------------------------------------------------------------------- the vocabulary

ALTER TABLE payments.payment_attempt
    -- Generated from InteractionModel.sqlStatusList() per model - V012's constraint gives
    -- way to one carrying the two-step machine's void states.
    DROP CONSTRAINT payment_attempt_status_matches_model,
    ADD CONSTRAINT payment_attempt_status_matches_model
        CHECK ((interaction_model = 'TWO_STEP'
                    AND status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'VOID_DISPATCHED', 'VOID_UNKNOWN', 'VOIDED'))
            OR (interaction_model = 'PUSH'
                    AND status IN ('FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED'))
            OR (interaction_model = 'BOOK'
                    AND status IN ('FAILED', 'EXECUTED'))),

    -- No void fact rides another model's row (the V012 closing CHECK, re-ADDed with the
    -- void columns in its list).
    DROP CONSTRAINT payment_attempt_foreign_model_carries_no_two_step_facts,
    ADD CONSTRAINT payment_attempt_foreign_model_carries_no_two_step_facts
        CHECK (interaction_model = 'TWO_STEP'
            OR (capture_reference IS NULL
                AND auth_provider_reference IS NULL
                AND capture_provider_reference IS NULL
                AND authorized_amount_minor IS NULL
                AND captured_amount_minor IS NULL
                AND void_reference IS NULL
                AND void_provider_reference IS NULL)),

    -- Which facts each stage requires and forbids - the aggregate constructor's switch,
    -- verbatim, now with the void's arms. FAILED has THREE legal shapes (P7-TSK-004):
    -- failed at authorization (bare), failed at capture (promise + capture reference, no
    -- void), or failed at the void (promise + void reference; the capture reference may
    -- ride along from the declined-capture redirect). A voided row captured nothing, ever.
    DROP CONSTRAINT payment_attempt_stage_facts_match_status,
    ADD CONSTRAINT payment_attempt_stage_facts_match_status
        CHECK (CASE status
            WHEN 'AUTH_DISPATCHED'    THEN authorized_amount_minor IS NULL     AND capture_reference IS NULL AND void_reference IS NULL
            WHEN 'AUTH_UNKNOWN'       THEN authorized_amount_minor IS NULL     AND capture_reference IS NULL AND void_reference IS NULL
            WHEN 'AUTHORIZED'         THEN authorized_amount_minor IS NOT NULL AND capture_reference IS NULL AND void_reference IS NULL
            WHEN 'CAPTURE_DISPATCHED' THEN authorized_amount_minor IS NOT NULL AND capture_reference IS NOT NULL AND void_reference IS NULL
            WHEN 'CAPTURE_UNKNOWN'    THEN authorized_amount_minor IS NOT NULL AND capture_reference IS NOT NULL AND void_reference IS NULL
            WHEN 'CAPTURED'           THEN authorized_amount_minor IS NOT NULL AND capture_reference IS NOT NULL AND void_reference IS NULL
            WHEN 'VOID_DISPATCHED'    THEN authorized_amount_minor IS NOT NULL AND void_reference IS NOT NULL AND captured_amount_minor IS NULL
            WHEN 'VOID_UNKNOWN'       THEN authorized_amount_minor IS NOT NULL AND void_reference IS NOT NULL AND captured_amount_minor IS NULL
            WHEN 'VOIDED'             THEN authorized_amount_minor IS NOT NULL AND void_reference IS NOT NULL AND captured_amount_minor IS NULL
            WHEN 'FAILED'             THEN (void_reference IS NOT NULL AND authorized_amount_minor IS NOT NULL)
                                        OR (void_reference IS NULL AND (authorized_amount_minor IS NULL) = (capture_reference IS NULL))
        END),

    -- The void's acknowledgement exactly when VOIDED, both directions (the captured-pair rule's sibling).
    ADD CONSTRAINT payment_attempt_void_ack_arrives_exactly_when_voided
        CHECK ((status = 'VOIDED') = (void_provider_reference IS NOT NULL));

-- One live attempt per intent, now over four terminals: VOIDED frees the slot exactly as
-- the others do. Generated from sqlTerminalValueList() (V012 is applied history).
DROP INDEX payments.payment_attempt_one_live_per_intent;
CREATE UNIQUE INDEX payment_attempt_one_live_per_intent
    ON payments.payment_attempt (intent_id)
    WHERE status NOT IN ('CAPTURED', 'FAILED', 'EXECUTED', 'VOIDED');

-- The history's vocabulary widens with the machine (generated from
-- PaymentAttemptStatus.sqlValueList()).
ALTER TABLE payments.payment_attempt_event
    DROP CONSTRAINT payment_attempt_event_from_status_is_known,
    ADD CONSTRAINT payment_attempt_event_from_status_is_known
        CHECK (from_status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED', 'VOID_DISPATCHED', 'VOID_UNKNOWN', 'VOIDED')),
    DROP CONSTRAINT payment_attempt_event_to_status_is_known,
    ADD CONSTRAINT payment_attempt_event_to_status_is_known
        CHECK (to_status IN ('AUTH_DISPATCHED', 'AUTH_UNKNOWN', 'AUTHORIZED', 'CAPTURE_DISPATCHED', 'CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'AWAITING_PAYER', 'EXECUTION_DISPATCHED', 'EXECUTION_UNKNOWN', 'EXECUTED', 'VOID_DISPATCHED', 'VOID_UNKNOWN', 'VOIDED'));

-- ----------------------------------------------------------------------- the trigger

-- The V012 function, REPLACED (V012 is applied history): the void's two facts join the
-- NULL-to-value payload freeze, and the two-step disjunction carries the machine's ten
-- states' edges, regenerated from InteractionModel.edges().
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
        OR (OLD.void_reference IS NOT NULL AND NEW.void_reference IS DISTINCT FROM OLD.void_reference)
        OR (OLD.void_provider_reference IS NOT NULL AND NEW.void_provider_reference IS DISTINCT FROM OLD.void_provider_reference)
        OR (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason) THEN
        RAISE EXCEPTION 'a recorded provider fact never changes: payload columns move only from NULL to a value (P5-TSK-008, INV-HIST-02)';
    END IF;
    IF NOT ((OLD.interaction_model = 'TWO_STEP'
                AND ((OLD.status = 'AUTH_DISPATCHED' AND NEW.status IN ('AUTH_UNKNOWN', 'AUTHORIZED', 'FAILED'))
                    OR (OLD.status = 'AUTH_UNKNOWN' AND NEW.status IN ('AUTHORIZED', 'FAILED'))
                    OR (OLD.status = 'AUTHORIZED' AND NEW.status IN ('CAPTURE_DISPATCHED', 'VOID_DISPATCHED'))
                    OR (OLD.status = 'CAPTURE_DISPATCHED' AND NEW.status IN ('CAPTURE_UNKNOWN', 'CAPTURED', 'FAILED', 'VOID_DISPATCHED'))
                    OR (OLD.status = 'CAPTURE_UNKNOWN' AND NEW.status IN ('CAPTURED', 'FAILED', 'VOID_DISPATCHED'))
                    OR (OLD.status = 'VOID_DISPATCHED' AND NEW.status IN ('FAILED', 'VOID_UNKNOWN', 'VOIDED'))
                    OR (OLD.status = 'VOID_UNKNOWN' AND NEW.status IN ('FAILED', 'VOIDED'))))
            OR (OLD.interaction_model = 'PUSH'
                AND ((OLD.status = 'AWAITING_PAYER' AND NEW.status IN ('FAILED', 'EXECUTION_DISPATCHED'))
                    OR (OLD.status = 'EXECUTION_DISPATCHED' AND NEW.status IN ('FAILED', 'EXECUTION_UNKNOWN', 'EXECUTED'))
                    OR (OLD.status = 'EXECUTION_UNKNOWN' AND NEW.status IN ('FAILED', 'EXECUTED'))))) THEN
        RAISE EXCEPTION 'a payment attempt moves only along its own model''s edges (ADR-0045, ADR-0059, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payments.payment_attempt.void_reference IS
    'OUR void reference (P7-TSK-004, INV-PAY-04): minted and stored with the transition '
    'into VOID_DISPATCHED, before the provider is asked to release the authorization - '
    'stored beside the authorization''s reference for reconciliation. NULL-to-value once, '
    'held by the payload freeze for every writer.';
COMMENT ON COLUMN payments.payment_attempt.void_provider_reference IS
    'The provider''s acknowledgement of the release - exactly when VOIDED, both directions '
    '(payment_attempt_void_ack_arrives_exactly_when_voided).';

-- ----------------------------------------------------------------------- grants

-- The app role writes the void's two payload columns like the capture's (V003's list).
GRANT UPDATE (void_reference, void_provider_reference)
    ON payments.payment_attempt TO finapp_app;
