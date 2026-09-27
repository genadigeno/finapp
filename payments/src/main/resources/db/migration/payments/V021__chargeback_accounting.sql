-- P7-TSK-013: chargeback accounting and the combined bound (ADR-0061 sections 3-5; INV-DSP-01,
-- INV-DSP-02, INV-MER-07 amended).
--
-- A chargeback is money the network has already taken. Its books record the external fact
-- (the card rail's clearing position credited by exactly what was taken) and the platform's
-- attribution of it: the payment's counterparty - the merchant's payable, or the customer's
-- wallet for a top-up - bears at most what the capture credited it, net of the refunds already
-- given back and of the chargebacks already standing; the rest is the network taking value the
-- platform had already returned (the EXCESS), which rests in CHARGEBACK_RECOVERABLE and is
-- never the counterparty's. This migration gives that attribution its columns and holds the
-- combined bound for EVERY writer, at the rank the refund bound already holds it.
--
-- 1. THE ATTRIBUTION ON THE DISPUTE ROW: counterparty_share (posted to the counterparty) and
--    parked_share (the counterparty's by attribution, parked in the recoverable because its
--    account takes no postings - ADR-0061 section 5); the excess is the chargeback minus both,
--    derived. Nullable money fragments, present EXACTLY with the chargeback, in its currency
--    and scale, never negative, never more than it. They arrive with the chargeback (the edge
--    entering CHARGED_BACK) and afterwards move only by RE-ATTRIBUTION - never down, only on a
--    standing dispute, never on an edge: whenever headroom is freed (a capture landing after the
--    chargeback was stated, a counted refund failing, a sibling chargeback won), the standing
--    excess comes back to the counterparty.
--
-- 2. THE DISPUTE FEE the PSP reports (dispute_fee): NULL -> value once, only on a charged-back
--    dispute, in the chargeback's currency - posted DR DISPUTE_COSTS / CR the rail's clearing.
--
-- 3. THE COMBINED BOUND FOR EVERY WRITER, both directions, under the refund bound's own
--    serializer (advisory namespace 3 on the attempt, DISTRIBUTED_EXECUTION.md):
--      - dispute_attribution_is_bounded: a standing attribution never takes the attempt's
--        refunds and standing attributions past its captured amount (nothing, when nothing
--        was captured);
--      - refund_is_bounded_by_the_capture, re-stated from V019 (which becomes applied history
--        for the function): the card arm adds the standing attributions to the refunded sum.
--        The push and book arms and the serializer survive byte for byte - neither rail
--        declares chargebacks.
--
-- 4. THE MACHINE TRIGGER, re-stated from V020 (applied history for the function) with the
--    attribution's and the fee's movement rules added; the freeze, the chargeback's
--    NULL -> value rule and every edge survive byte for byte.
--
-- A DATABASE HOLDING A CHARGED-BACK DISPUTE FROM BEFORE THIS TASK REFUSES THIS MIGRATION: such a
-- row has a chargeback and no attribution, and the coherence CHECK below will not be satisfied
-- by an invented one. No deployment holds such rows (the platform is pre-production, and every
-- test database migrates empty); the loud refusal is the honest answer if one ever did.
--
-- EVERY GENERATED LIST HAS ONE DEFINITION: the standing stages from
-- DisputeStage.standingSqlValueList(), the money fragments from MoneyColumns' naming, the scale
-- bound from Money.MAX_SUPPORTED_SCALE; PaymentsMigrationTest fails the build on drift.

-- ----------------------------------------------------------------- 1-2. the columns

ALTER TABLE payments.dispute
    ADD COLUMN counterparty_share_amount_minor BIGINT,
    ADD COLUMN counterparty_share_currency CHAR(3),
    ADD COLUMN counterparty_share_scale SMALLINT,
    ADD COLUMN parked_share_amount_minor BIGINT,
    ADD COLUMN parked_share_currency CHAR(3),
    ADD COLUMN parked_share_scale SMALLINT,
    ADD COLUMN dispute_fee_amount_minor BIGINT,
    ADD COLUMN dispute_fee_currency CHAR(3),
    ADD COLUMN dispute_fee_scale SMALLINT,

    -- Each fragment's value rules and all-or-nothing rule: MoneyColumns.nullableDdl()'s
    -- clauses, named, because ALTER TABLE cannot take the CREATE-time fragment verbatim.
    ADD CONSTRAINT dispute_counterparty_share_currency_shape
        CHECK (counterparty_share_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT dispute_counterparty_share_scale_bound
        CHECK (counterparty_share_scale BETWEEN 0 AND 9),
    ADD CONSTRAINT dispute_counterparty_share_all_or_nothing
        CHECK ((counterparty_share_amount_minor IS NULL) = (counterparty_share_currency IS NULL) AND (counterparty_share_amount_minor IS NULL) = (counterparty_share_scale IS NULL)),
    ADD CONSTRAINT dispute_parked_share_currency_shape
        CHECK (parked_share_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT dispute_parked_share_scale_bound
        CHECK (parked_share_scale BETWEEN 0 AND 9),
    ADD CONSTRAINT dispute_parked_share_all_or_nothing
        CHECK ((parked_share_amount_minor IS NULL) = (parked_share_currency IS NULL) AND (parked_share_amount_minor IS NULL) = (parked_share_scale IS NULL)),
    ADD CONSTRAINT dispute_dispute_fee_currency_shape
        CHECK (dispute_fee_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT dispute_dispute_fee_scale_bound
        CHECK (dispute_fee_scale BETWEEN 0 AND 9),
    ADD CONSTRAINT dispute_dispute_fee_all_or_nothing
        CHECK ((dispute_fee_amount_minor IS NULL) = (dispute_fee_currency IS NULL) AND (dispute_fee_amount_minor IS NULL) = (dispute_fee_scale IS NULL)),

    -- The attribution arrives WITH the chargeback and is part of it: present exactly when the
    -- amount is, in its currency and scale, never negative, never more than what was taken.
    ADD CONSTRAINT dispute_attribution_matches_chargeback
        CHECK ((chargeback_amount_minor IS NULL) = (counterparty_share_amount_minor IS NULL) AND (chargeback_amount_minor IS NULL) = (parked_share_amount_minor IS NULL)),
    ADD CONSTRAINT dispute_attribution_in_chargeback_currency
        CHECK (counterparty_share_currency = chargeback_currency AND counterparty_share_scale = chargeback_scale AND parked_share_currency = chargeback_currency AND parked_share_scale = chargeback_scale),
    ADD CONSTRAINT dispute_attribution_within_chargeback
        CHECK (counterparty_share_amount_minor >= 0 AND parked_share_amount_minor >= 0 AND counterparty_share_amount_minor + parked_share_amount_minor <= chargeback_amount_minor),

    -- The fee is charged with the chargeback: only on a charged-back dispute, positive, in its
    -- currency and scale.
    ADD CONSTRAINT dispute_fee_rides_the_chargeback
        CHECK (dispute_fee_amount_minor IS NULL OR (chargeback_amount_minor IS NOT NULL AND dispute_fee_amount_minor > 0 AND dispute_fee_currency = chargeback_currency AND dispute_fee_scale = chargeback_scale));

-- ----------------------------------------------------------------- 3a. the dispute's bound

-- Every writer: a standing chargeback's attribution never takes the attempt's non-failed
-- refunds and standing attributions past what the capture credited (INV-DSP-01). A won
-- chargeback stands no more - its attribution was reversed with the funds.
CREATE FUNCTION payments.dispute_attribution_is_bounded() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    captured_minor   bigint;
    captured_ccy     char(3);
    captured_sc      smallint;
    refunded_total   bigint;
    attributed_total bigint;
BEGIN
    IF NEW.counterparty_share_amount_minor IS NULL
            OR NEW.stage NOT IN ('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED') THEN
        RETURN NEW;
    END IF;

    -- The refund bound's serializer: refunds and chargebacks on one attempt judge ONE sum.
    PERFORM pg_advisory_xact_lock(3, hashtext(NEW.attempt_id::text));

    SELECT captured_amount_minor, captured_currency, captured_scale
        INTO captured_minor, captured_ccy, captured_sc
        FROM payments.payment_attempt
        WHERE id = NEW.attempt_id;

    IF captured_minor IS NOT NULL
            AND (captured_ccy <> NEW.chargeback_currency OR captured_sc <> NEW.chargeback_scale) THEN
        RAISE EXCEPTION 'payments_dispute_attribution_is_bounded: dispute % is not in attempt %''s captured currency and scale (INV-DSP-01, INV-MON-03)',
                NEW.id, NEW.attempt_id
            USING ERRCODE = '23514';
    END IF;

    SELECT coalesce(sum(refund.amount_minor), 0)
        INTO refunded_total
        FROM payments.refund refund
        WHERE refund.attempt_id = NEW.attempt_id
          AND refund.status <> 'FAILED';

    SELECT coalesce(sum(sibling.counterparty_share_amount_minor + sibling.parked_share_amount_minor), 0)
        INTO attributed_total
        FROM payments.dispute sibling
        WHERE sibling.attempt_id = NEW.attempt_id
          AND sibling.id <> NEW.id
          AND sibling.stage IN ('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED');

    -- Nothing captured credits nobody: then nothing may be attributed at all.
    IF refunded_total + attributed_total
            + NEW.counterparty_share_amount_minor + NEW.parked_share_amount_minor
            > coalesce(captured_minor, 0) THEN
        RAISE EXCEPTION 'payments_dispute_attribution_is_bounded: dispute % would take attempt %''s refunds and standing chargebacks together past what its capture credited (INV-DSP-01)',
                NEW.id, NEW.attempt_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER dispute_attribution_is_bounded
    BEFORE INSERT OR UPDATE OF counterparty_share_amount_minor, parked_share_amount_minor, stage
    ON payments.dispute
    FOR EACH ROW
    EXECUTE FUNCTION payments.dispute_attribution_is_bounded();

-- ----------------------------------------------------------------- 3b. the refund's bound

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
    charged_back_total bigint;
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
        -- The card arm: V004's judgement, and since P7-TSK-013 the COMBINED bound - the
        -- chargebacks standing on the attempt count beside its refunds (INV-DSP-01): a refund
        -- after a chargeback took the value back would give the same money away twice.
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

        SELECT coalesce(sum(dispute.counterparty_share_amount_minor + dispute.parked_share_amount_minor), 0)
            INTO charged_back_total
            FROM payments.dispute dispute
            WHERE dispute.attempt_id = NEW.attempt_id
              AND dispute.stage IN ('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED');

        IF refunded_total + charged_back_total + NEW.amount_minor > captured_minor THEN
            RAISE EXCEPTION 'payments_refund_is_bounded: refund % would take attempt %''s refunds and standing chargebacks together past its captured amount (INV-PAY-05, INV-DSP-01)',
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

-- ----------------------------------------------------------------- 4. the machine trigger

CREATE OR REPLACE FUNCTION payments.dispute_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.provider IS DISTINCT FROM OLD.provider
            OR NEW.provider_dispute_reference IS DISTINCT FROM OLD.provider_dispute_reference
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'a dispute''s opening statement is frozen: provider, reference, attempt and reason never move (P7-TSK-012, INV-HIST-01''s discipline)';
    END IF;
    -- The chargeback's amount moves only NULL -> value (the attempt's payload discipline):
    -- the CHECK above lets it arrive only with the funds taken, and this refuses its
    -- revision or its removal once recorded.
    IF (OLD.chargeback_amount_minor IS NOT NULL
                AND NEW.chargeback_amount_minor IS DISTINCT FROM OLD.chargeback_amount_minor)
            OR (OLD.chargeback_currency IS NOT NULL
                AND NEW.chargeback_currency IS DISTINCT FROM OLD.chargeback_currency)
            OR (OLD.chargeback_scale IS NOT NULL
                AND NEW.chargeback_scale IS DISTINCT FROM OLD.chargeback_scale) THEN
        RAISE EXCEPTION 'a recorded chargeback amount never changes: it arrives with the funds taken and moves only from NULL to a value (P7-TSK-012, INV-HIST-02)';
    END IF;
    -- The attribution arrives with the chargeback (the CHECKs pair it with the amount) and
    -- afterwards moves only as a RE-ATTRIBUTION: its shares never shrink, and they grow only
    -- on a standing dispute whose stage the same statement does not move (P7-TSK-013).
    IF OLD.counterparty_share_amount_minor IS NOT NULL
            AND (NEW.counterparty_share_amount_minor IS DISTINCT FROM OLD.counterparty_share_amount_minor
                 OR NEW.parked_share_amount_minor IS DISTINCT FROM OLD.parked_share_amount_minor) THEN
        IF NEW.counterparty_share_amount_minor IS NULL
                OR NEW.parked_share_amount_minor IS NULL
                OR NEW.counterparty_share_amount_minor < OLD.counterparty_share_amount_minor
                OR NEW.parked_share_amount_minor < OLD.parked_share_amount_minor
                OR NEW.stage IS DISTINCT FROM OLD.stage
                OR OLD.stage NOT IN ('CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED') THEN
            RAISE EXCEPTION 'a chargeback''s attribution only grows, by re-attribution on a standing dispute whose stage does not move (P7-TSK-013, INV-DSP-01)';
        END IF;
    END IF;
    -- The PSP's dispute fee moves only NULL -> value.
    IF (OLD.dispute_fee_amount_minor IS NOT NULL
                AND NEW.dispute_fee_amount_minor IS DISTINCT FROM OLD.dispute_fee_amount_minor)
            OR (OLD.dispute_fee_currency IS NOT NULL
                AND NEW.dispute_fee_currency IS DISTINCT FROM OLD.dispute_fee_currency)
            OR (OLD.dispute_fee_scale IS NOT NULL
                AND NEW.dispute_fee_scale IS DISTINCT FROM OLD.dispute_fee_scale) THEN
        RAISE EXCEPTION 'a recorded dispute fee never changes: it moves only from NULL to a value (P7-TSK-013, INV-HIST-02)';
    END IF;
    IF NEW.stage = OLD.stage THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.stage = 'INQUIRY' AND NEW.stage IN ('CHARGED_BACK', 'CLOSED'))
            OR (OLD.stage = 'CHARGED_BACK' AND NEW.stage IN ('REPRESENTED', 'LOST', 'ACCEPTED'))
            OR (OLD.stage = 'REPRESENTED' AND NEW.stage IN ('WON', 'LOST'))) THEN
        RAISE EXCEPTION 'a dispute moves only along the machine''s edges: INQUIRY -> {CHARGED_BACK, CLOSED}, CHARGED_BACK -> {REPRESENTED, LOST, ACCEPTED}, REPRESENTED -> {WON, LOST}; WON, LOST, ACCEPTED and CLOSED are terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------- comments and grants

COMMENT ON COLUMN payments.dispute.counterparty_share_amount_minor IS
    'The part of the chargeback charged to the payment''s counterparty (its merchant payable, or the customer''s wallet), posted to its account (dispute-attribution:<id>): at most what the capture credited it, net of non-failed refunds and standing chargebacks, judged under the attempt lock (INV-DSP-01). Grows only by re-attribution.';
COMMENT ON COLUMN payments.dispute.parked_share_amount_minor IS
    'The counterparty''s share parked in CHARGEBACK_RECOVERABLE because its account took no postings (a closed wallet, ADR-0061 section 5): counted against the bound, recovered by an operator''s act, never written off by a loss.';
COMMENT ON COLUMN payments.dispute.dispute_fee_amount_minor IS
    'The dispute fee the PSP reported, recorded once and posted DR DISPUTE_COSTS / CR the rail''s clearing (dispute-fee:<id>) - the platform bears it in Phase 7 (ADR-0061 section 4).';

GRANT UPDATE (counterparty_share_amount_minor, counterparty_share_currency,
              counterparty_share_scale, parked_share_amount_minor, parked_share_currency,
              parked_share_scale, dispute_fee_amount_minor, dispute_fee_currency,
              dispute_fee_scale)
    ON payments.dispute TO finapp_app;
