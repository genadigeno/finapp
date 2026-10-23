-- The held withdrawal (P13-TSK-009; PHASE_13_PLAN.md sections 5, 7.3, 14; ADR-0062 section 6's hold-then-dispatch,
-- ADR-0108; INV-BAL-04, INV-RSK-02, INV-LIFE-01/-02/-04). Risk now judges a person's withdrawal inside the dispatch
-- transaction, after the hold is placed under the wallet account's lock and before anything commits, through payments'
-- own seam (WithdrawalRiskDecision). The verdict decides the state the withdrawal is BORN in:
--
--     PERMIT -> DISPATCHED            as before Phase 13: the wire follows the commit
--     HOLD   -> HELD                  the ledger hold KEPT, nothing sent, held_at/held_until stamped by THIS database
--     REFUSE -> FAILED(RISK_REFUSED)  the hold released in the same transaction, nothing sent
--
-- and a held withdrawal leaves HELD only by payments' keyed release (-> DISPATCHED, the first real send permit and the
-- door's key recorded), its keyed denial (-> FAILED(RISK_REFUSED), the key recorded, the hold released) or the expiry
-- leg of WithdrawalResolutionSchedule once held_until has passed on the database clock (-> FAILED(RISK_REFUSED) with
-- failure_detail HOLD_EXPIRED, the hold released). RISK_REFUSED never follows a dispatch: once the scheme may have the
-- money, only its word fails a withdrawal.
--
-- EVERY GENERATED LIST HAS ONE DEFINITION: the statuses from WithdrawalStatus.sqlValueList(), the birth states from
-- birthStates(), the edges from permittedTransitions(), the failure reasons from WithdrawalFailureReason.sqlValueList()
-- and the detail from WithdrawalFailureDetail.sqlValueList(); PaymentsMigrationTest fails the build on drift and holds
-- every constraint, index and trigger name declared here once (the Phase 12 V012 lesson). V016 and V032 are applied
-- history: the CHECKs are regenerated here by name, the birth trigger replaced, the machine function replaced (V032's
-- body otherwise verbatim).

-- ----------------------------------------------------------------- the vocabularies, regenerated

ALTER TABLE payments.withdrawal DROP CONSTRAINT withdrawal_status_is_known;
ALTER TABLE payments.withdrawal DROP CONSTRAINT withdrawal_failure_reason_is_known;

ALTER TABLE payments.withdrawal
    ADD CONSTRAINT withdrawal_status_is_known
        CHECK (status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN', 'HELD')),
    ADD CONSTRAINT withdrawal_failure_reason_is_known
        CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED', 'RISK_REFUSED'));

ALTER TABLE payments.withdrawal_event DROP CONSTRAINT withdrawal_event_from_status_is_known;
ALTER TABLE payments.withdrawal_event DROP CONSTRAINT withdrawal_event_to_status_is_known;

ALTER TABLE payments.withdrawal_event
    ADD CONSTRAINT withdrawal_event_from_status_is_known
        CHECK (from_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN', 'HELD')),
    ADD CONSTRAINT withdrawal_event_to_status_is_known
        CHECK (to_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN', 'HELD'));

-- ----------------------------------------------------------------- the held facts

ALTER TABLE payments.withdrawal
    -- Stamped by the birth's insert on the DATABASE clock (held_at = the birth permit's own instant,
    -- GREATEST(created_at, statement_timestamp()); held_until = held_at + the pinned policy's hold validity, which the
    -- seam answered with its HOLD) and frozen for ever: the row's record that it was held.
    ADD COLUMN held_at             timestamptz,
    ADD COLUMN held_until          timestamptz,
    -- HOLD_EXPIRED on an expired hold; nothing else yet.
    ADD COLUMN failure_detail      text
        CONSTRAINT withdrawal_failure_detail_is_known
            CHECK (failure_detail IN ('HOLD_EXPIRED')),
    -- The key of the door (release or denial) that moved the row out of HELD - the release's takeover converges on it;
    -- NULL for an expiry and for a row never held.
    ADD COLUMN held_resolution_key text
        CONSTRAINT withdrawal_held_resolution_key_bounded
            CHECK (length(held_resolution_key) BETWEEN 1 AND 200),
    ADD CONSTRAINT withdrawal_held_window_is_whole
        CHECK ((held_at IS NULL) = (held_until IS NULL) AND (held_until IS NULL OR held_until > held_at)),
    ADD CONSTRAINT withdrawal_held_window_follows_creation
        CHECK (held_at IS NULL OR held_at >= created_at),
    ADD CONSTRAINT withdrawal_held_carries_its_window
        CHECK (status <> 'HELD' OR held_at IS NOT NULL),
    ADD CONSTRAINT withdrawal_failure_detail_is_a_held_risk_refusal
        CHECK (failure_detail IS NULL
            OR (failure_reason = 'RISK_REFUSED' AND held_at IS NOT NULL AND held_resolution_key IS NULL)),
    ADD CONSTRAINT withdrawal_held_resolution_key_needs_a_hold
        CHECK (held_resolution_key IS NULL OR held_at IS NOT NULL);

-- The expiry leg's claim: HELD rows by their deadline (FOR UPDATE SKIP LOCKED, one row per transaction).
CREATE INDEX withdrawal_hold_expiry
    ON payments.withdrawal (held_until, id)
    WHERE status = 'HELD';

-- ----------------------------------------------------------------- the judged birth, for every writer

DROP TRIGGER withdrawal_is_born_dispatched ON payments.withdrawal;
DROP FUNCTION payments.withdrawal_is_born_dispatched();

CREATE FUNCTION payments.withdrawal_is_born_judged() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT (NEW.status IN ('DISPATCHED', 'FAILED', 'HELD')
            AND (NEW.status <> 'FAILED' OR NEW.failure_reason = 'RISK_REFUSED')) THEN
        RAISE EXCEPTION 'a withdrawal is born as its judgement decides - DISPATCHED, HELD or FAILED(RISK_REFUSED); every other outcome arrives through the machine (P7-TSK-008, P13-TSK-009)'
            USING ERRCODE = '23514';
    END IF;
    IF (NEW.status = 'HELD') <> (NEW.held_at IS NOT NULL) THEN
        RAISE EXCEPTION 'a withdrawal is born with a held window exactly when it is born HELD (P13-TSK-009)'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.failure_detail IS NOT NULL OR NEW.held_resolution_key IS NOT NULL THEN
        RAISE EXCEPTION 'a door''s key and an expiry''s detail arrive with an edge out of HELD, never at birth (P13-TSK-009)'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.instruction_reference IS NOT NULL AND NEW.status <> 'DISPATCHED' THEN
        RAISE EXCEPTION 'an instructed withdrawal carries out a credit decision and is not judged: born DISPATCHED (P11-TSK-015, P13-TSK-009)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER withdrawal_is_born_judged
    BEFORE INSERT ON payments.withdrawal
    FOR EACH ROW
    EXECUTE FUNCTION payments.withdrawal_is_born_judged();

-- ----------------------------------------------------------------- the machine, regenerated

-- Edges generated from WithdrawalStatus.permittedTransitions(); bound to every writer including the migrator. Added to
-- V032's body: the held window frozen with the dispatch; the door's key and the expiry's detail arriving only with an
-- edge out of HELD and frozen after; the send permit movable on the release's edge (HELD -> DISPATCHED, the first real
-- permit - V028's trigger, which runs after this one, stamps it on the database clock); RISK_REFUSED exactly out of
-- HELD.
CREATE OR REPLACE FUNCTION payments.withdrawal_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
            OR NEW.wallet_account_id IS DISTINCT FROM OLD.wallet_account_id
            OR NEW.payment_method_id IS DISTINCT FROM OLD.payment_method_id
            OR NEW.destination_reference IS DISTINCT FROM OLD.destination_reference
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.end_to_end_reference IS DISTINCT FROM OLD.end_to_end_reference
            OR NEW.rail IS DISTINCT FROM OLD.rail
            OR NEW.dispatch_key IS DISTINCT FROM OLD.dispatch_key
            OR NEW.hold_reference IS DISTINCT FROM OLD.hold_reference
            OR NEW.instruction_reference IS DISTINCT FROM OLD.instruction_reference
            OR NEW.held_at IS DISTINCT FROM OLD.held_at
            OR NEW.held_until IS DISTINCT FROM OLD.held_until
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a withdrawal''s dispatch is frozen: wallet, instrument, destination, amount, reference, instruction, hold and held window never move (P7-TSK-008, P11-TSK-015, P13-TSK-009)';
    END IF;
    IF (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason)
            OR (OLD.scheme_reference IS NOT NULL
                AND NEW.scheme_reference IS DISTINCT FROM OLD.scheme_reference)
            OR (OLD.settlement_cycle IS NOT NULL
                AND NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle)
            OR (OLD.failure_detail IS NOT NULL
                AND NEW.failure_detail IS DISTINCT FROM OLD.failure_detail)
            OR (OLD.held_resolution_key IS NOT NULL
                AND NEW.held_resolution_key IS DISTINCT FROM OLD.held_resolution_key) THEN
        RAISE EXCEPTION 'a withdrawal''s recorded outcome never moves (INV-HIST-01''s discipline)';
    END IF;
    IF (NEW.failure_detail IS DISTINCT FROM OLD.failure_detail
                OR NEW.held_resolution_key IS DISTINCT FROM OLD.held_resolution_key)
            AND NOT (OLD.status = 'HELD' AND NEW.status IN ('DISPATCHED', 'FAILED')) THEN
        RAISE EXCEPTION 'a held withdrawal''s door key and expiry detail arrive only with its edge out of HELD (P13-TSK-009)';
    END IF;
    IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'a withdrawal''s send permit only moves forward (ADR-0057 section 4)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN')
            AND NOT (OLD.status = 'HELD' AND NEW.status = 'DISPATCHED') THEN
        RAISE EXCEPTION 'a resolved withdrawal is never sent again (ADR-0057 section 4), and a held one only once released (P13-TSK-009)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'FAILED'))
            OR (OLD.status = 'HELD' AND NEW.status IN ('DISPATCHED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a withdrawal moves only along the machine''s edges: DISPATCHED -> {COMPLETED, FAILED, UNKNOWN}, UNKNOWN -> {COMPLETED, FAILED}, HELD -> {DISPATCHED, FAILED} (INV-LIFE-02/-04; INV-REV-03: nothing leaves COMPLETED)';
    END IF;
    IF NEW.status = 'FAILED' AND ((OLD.status = 'HELD') <> (NEW.failure_reason = 'RISK_REFUSED')) THEN
        RAISE EXCEPTION 'a withdrawal fails RISK_REFUSED exactly out of HELD - never after a dispatch, and the scheme never refuses what it never received (P13-TSK-009)';
    END IF;
    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------- comments and grants

COMMENT ON COLUMN payments.withdrawal.held_at IS
    'When risk''s HOLD verdict held the withdrawal (P13-TSK-009): the birth permit''s own instant on the database clock, never before created_at; frozen. NULL on a withdrawal never held.';
COMMENT ON COLUMN payments.withdrawal.held_until IS
    'held_at plus the pinned WITHDRAWAL policy version''s hold validity (default 72 h): past it on the database clock the expiry leg fails the withdrawal RISK_REFUSED/HOLD_EXPIRED and releases its hold. Frozen.';
COMMENT ON COLUMN payments.withdrawal.failure_detail IS
    'HOLD_EXPIRED on a held withdrawal failed by its expiry (P13-TSK-009). Internal: the customer reads RISK_REFUSED, never the detail (INV-RSK-08).';
COMMENT ON COLUMN payments.withdrawal.held_resolution_key IS
    'The idempotency key of the keyed door - release or denial - that moved the withdrawal out of HELD (P13-TSK-009; risk.action:<id> from -012/-017). NULL for an expiry or a row never held. Frozen once set.';

-- The app writes the door's key and the expiry's detail on the edge; the held window is insert-only.
GRANT UPDATE (failure_detail, held_resolution_key) ON payments.withdrawal TO finapp_app;
