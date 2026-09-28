-- P7-TSK-011: the wallet as an instrument - the book rail (ADR-0059 section 6; INV-BAL-04,
-- INV-BAL-05, INV-PAY-05, INV-LIFE-02, INV-HIST-01).
--
-- Three changes, one task:
--
-- 1. THE INTENT'S INSTRUMENT-CHOICE XOR. Paying from the wallet is a shape of the intent,
--    not a registered method (the wallet stays in accounts; ADR-0042's split trigger
--    evaluated and not met): payment_method_id relaxes to NULL and debit_account_id
--    arrives - the payer's own wallet ledger account, resolved at creation and frozen -
--    with exactly-one-of held for every writer.
--
-- 2. THE INTENT TRIGGER, RECREATED (V012 becomes applied history for it). V012's freeze
--    compared payment_method_id with <>, which is NULL-blind - the exact class V003's
--    attempt trigger met in P7-TSK-007: with the column nullable, any writer could attach
--    a method to a wallet intent after birth and the clause would never fire. Both
--    instrument columns now freeze IS DISTINCT FROM; everything else is restated verbatim.
--
-- 3. THE REFUND BOUND'S BOOK ARM (V018 becomes applied history for the function). The book
--    refund's producer ships with this task, so the refusal V018 held for the BOOK model
--    gives way to the same judgement the push arm applies: only an EXECUTED subject has
--    anything to return, and the base is the intent's frozen ask. The card and push arms
--    are restated verbatim; the serializer survives byte for byte.
--
-- Plus routing version 4: the standing rules carried forward byte for byte and the wallet
-- pay-in onto the book rail (the newest-effective resolution, V013/V016/V017's argument).

-- ----------------------------------------------------------------- 1. the instrument XOR

ALTER TABLE payments.payment_intent
    ALTER COLUMN payment_method_id DROP NOT NULL;

ALTER TABLE payments.payment_intent
    ADD COLUMN debit_account_id uuid;

ALTER TABLE payments.payment_intent
    ADD CONSTRAINT payment_intent_carries_exactly_one_instrument
        CHECK ((payment_method_id IS NULL) <> (debit_account_id IS NULL));

-- ----------------------------------------------------------------- 2. the trigger handoff

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

-- ----------------------------------------------------------------- 3. the bound's book arm

CREATE OR REPLACE FUNCTION payments.refund_is_bounded_by_the_capture()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    attempt_status   text;
    attempt_model    text;
    attempt_intent   uuid;
    captured_minor   bigint;
    captured_ccy     char(3);
    captured_sc      smallint;
    executed_minor   bigint;
    executed_ccy     char(3);
    executed_sc      smallint;
    refunded_total   bigint;
BEGIN
    -- The serializer: every writer refunding this attempt queues here, and a resumed loser's
    -- reads below see what the winner committed. Namespace 3 (DISTRIBUTED_EXECUTION.md).
    PERFORM pg_advisory_xact_lock(3, hashtext(NEW.attempt_id::text));

    SELECT status, interaction_model, intent_id,
           captured_amount_minor, captured_currency, captured_scale
        INTO attempt_status, attempt_model, attempt_intent,
             captured_minor, captured_ccy, captured_sc
        FROM payments.payment_attempt
        WHERE id = NEW.attempt_id;

    IF attempt_status IS NULL THEN
        RAISE EXCEPTION 'payments_refund_is_bounded: refund % references attempt %, which does not exist (INV-PAY-05)',
                NEW.id, NEW.attempt_id
            USING ERRCODE = '23514';
    END IF;

    IF attempt_model = 'TWO_STEP' THEN
        -- The card arm: V004's judgement verbatim. The money that arrived is the capture.
        IF attempt_status <> 'CAPTURED' THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % references attempt % in %, and only a CAPTURED attempt has anything to return (INV-PAY-05)',
                    NEW.id, NEW.attempt_id, attempt_status
                USING ERRCODE = '23514';
        END IF;
        IF NEW.currency <> captured_ccy OR NEW.scale <> captured_sc THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % is not in attempt %''s captured currency and scale (INV-PAY-05, INV-MON-03)',
                    NEW.id, NEW.attempt_id
                USING ERRCODE = '23514';
        END IF;

        SELECT coalesce(sum(sibling.amount_minor), 0)
            INTO refunded_total
            FROM payments.refund sibling
            WHERE sibling.attempt_id = NEW.attempt_id
              AND sibling.status <> 'FAILED';

        IF refunded_total + NEW.amount_minor > captured_minor THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % would take attempt %''s refunded sum past its captured amount (INV-PAY-05)',
                    NEW.id, NEW.attempt_id
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF attempt_model IN ('PUSH', 'BOOK') THEN
        -- The executed arms (P7-TSK-010 for the push, P7-TSK-011 for the book): money
        -- arrived when the machine reached EXECUTED, and what arrived is the intent's
        -- frozen ask - the attempt captures nothing on either rail (the push door refused
        -- any confirmation stating a different amount; the book posting IS the ask). One
        -- judgement, the model named in the refusal so a break reads honestly.
        IF attempt_status <> 'EXECUTED' THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % references % attempt % in %, and only an EXECUTED payment has anything to return (INV-PAY-05, P7-TSK-010, P7-TSK-011)',
                    NEW.id, attempt_model, NEW.attempt_id, attempt_status
                USING ERRCODE = '23514';
        END IF;

        SELECT intent.amount_minor, intent.currency, intent.scale
            INTO executed_minor, executed_ccy, executed_sc
            FROM payments.payment_intent intent
            WHERE intent.id = attempt_intent;

        IF NEW.currency <> executed_ccy OR NEW.scale <> executed_sc THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % is not in attempt %''s executed currency and scale (INV-PAY-05, INV-MON-03)',
                    NEW.id, NEW.attempt_id
                USING ERRCODE = '23514';
        END IF;

        SELECT coalesce(sum(sibling.amount_minor), 0)
            INTO refunded_total
            FROM payments.refund sibling
            WHERE sibling.attempt_id = NEW.attempt_id
              AND sibling.status <> 'FAILED';

        IF refunded_total + NEW.amount_minor > executed_minor THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % would take attempt %''s returned sum past its executed amount (INV-PAY-05)',
                    NEW.id, NEW.attempt_id
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- No fourth model exists; a new one arrives with its own producer and its own arm,
    -- never a bound invented in advance (the V018 stance, carried forward).
    RAISE EXCEPTION 'payments_refund_is_bounded: refund % references attempt % on the % model, whose refund producer is not yet shipped (ADR-0059)',
            NEW.id, NEW.attempt_id, attempt_model
        USING ERRCODE = '23514';
END;
$$;

-- ----------------------------------------------------------------- seed: policy version 4

-- The wallet pay-in must route on a fresh database exactly as the card's, the withdrawal's
-- and the bank pay-in's do (V013/V016/V017's seeding argument verbatim: INV-HIST-04's NOT
-- NULL refuses an unpinned decision). A version is WHOLE (the newest-effective resolution),
-- so rules 0-2 carry the standing rules forward byte for byte and rule 3 adds the wallet
-- pay-in onto the book rail. created_by names the migration.
INSERT INTO payments.routing_policy_version
    (id, version, effective_from, created_at, created_by, reason)
VALUES
    ('019992e0-0000-7000-8000-000000000030', 4, now(), now(), 'V019',
     'the standing card pay-in, bank pay-out and bank pay-in carried forward, plus the wallet pay-in onto the book rail (P7-TSK-011, ADR-0059 section 6)');

INSERT INTO payments.routing_rule
    (id, policy_version_id, rule_index, direction, instrument_kind, currency,
     ceiling_amount_minor, ceiling_currency, ceiling_scale)
VALUES
    ('019992e0-0000-7000-8000-000000000031', '019992e0-0000-7000-8000-000000000030',
     0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000032', '019992e0-0000-7000-8000-000000000030',
     1, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000033', '019992e0-0000-7000-8000-000000000030',
     2, 'PAY_IN', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000034', '019992e0-0000-7000-8000-000000000030',
     3, 'PAY_IN', 'WALLET', NULL, NULL, NULL, NULL);

INSERT INTO payments.routing_rule_rail (rule_id, position, rail)
VALUES
    ('019992e0-0000-7000-8000-000000000031', 0, 'card'),
    ('019992e0-0000-7000-8000-000000000032', 0, 'instant'),
    ('019992e0-0000-7000-8000-000000000033', 0, 'instant'),
    ('019992e0-0000-7000-8000-000000000034', 0, 'book');

-- ----------------------------------------------------------------- comments

COMMENT ON COLUMN payments.payment_intent.debit_account_id IS
    'The payer''s own wallet ledger account, present exactly when the instrument IS the wallet (P7-TSK-011, ADR-0059 section 6): the book rail''s debit side, resolved at creation, frozen at birth, re-verified at the act.';
COMMENT ON COLUMN payments.payment_intent.payment_method_id IS
    'The paymentmethods row''s identifier (INV-PAY-02) - or NULL exactly when the instrument is the payer''s own wallet (debit_account_id; P7-TSK-011''s XOR).';
