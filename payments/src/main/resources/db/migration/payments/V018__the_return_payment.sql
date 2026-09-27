-- P7-TSK-010: refunds on push rails are return payments (ADR-0059 section 3, ADR-0062
-- section 6; INV-PAY-05, INV-RAIL-02, INV-MON-03).
--
-- The refund table itself does not change. A return payment is the same financial object the
-- card refund is - money going back to the payer, bounded by what actually arrived, through
-- the same four-state machine - and the row already carries everything a return needs: our
-- reference in provider_idempotency_reference (minted per rail by the aggregate), the
-- scheme's transaction reference landing in provider_reference exactly with COMPLETED, and
-- the amount triple the bound judges. What changes is the JUDGE: V004's bound knew one rail
-- and wrote "only a CAPTURED attempt has anything to return" - true for the card's two-step
-- machine, meaningless for the push machine, whose money-arrived state is EXECUTED and whose
-- authoritative arrived-amount is the intent's frozen ask (the attempt captures nothing; the
-- scheme confirmed the initiation's own amount, and the V017 door refused any confirmation
-- whose stated amount differed).
--
-- So the bound becomes per-model, exactly like the attempt machine did in V012: one function,
-- dispatched on the attempt's interaction_model, each arm judging against ITS rail's
-- money-arrived fact. The TWO_STEP arm is V004's judgement verbatim. The PUSH arm requires
-- EXECUTED and bounds the non-FAILED sum by the intent's amount. Any other model is refused
-- outright: the BOOK rail's refund producer is P7-TSK-011's, and a bound invented here for a
-- producer that does not exist would be a guess wearing a constraint's clothes.
--
-- V004 stays byte-for-byte as applied history (the migration handoff discipline, V012 -> V014
-- -> V017): this file re-states the function from the current rail reality, and the trigger
-- keeps pointing at the same name.
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

    IF attempt_model = 'PUSH' THEN
        -- The return arm (P7-TSK-010): money arrived when the scheme confirmed EXECUTED, and
        -- what arrived is the intent's frozen ask - the attempt captures nothing on this
        -- rail, and the V017 door refused any confirmation stating a different amount, so
        -- the intent triple IS the authoritative arrived-money fact (INV-RAIL-02: final on
        -- acceptance means the return is a NEW outbound payment bounded by the old one, never
        -- a reversal of it).
        IF attempt_status <> 'EXECUTED' THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % references push attempt % in %, and only an EXECUTED pay-in has anything to return (INV-PAY-05, P7-TSK-010)',
                    NEW.id, NEW.attempt_id, attempt_status
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

    -- No third arm exists yet: the BOOK rail's refund is its own producer's task, and a bound
    -- written before that producer would be a guess. Refuse loudly instead (P7-TSK-011).
    RAISE EXCEPTION 'payments_refund_is_bounded: refund % references attempt % on the % model, whose refund producer is not yet shipped (P7-TSK-011, ADR-0059)',
            NEW.id, NEW.attempt_id, attempt_model
        USING ERRCODE = '23514';
END;
$$;
