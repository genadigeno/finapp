-- The wallet withdrawal (P7-TSK-008, ADR-0062 sections 3 and 6) - the merchant payout's
-- machine (ADR-0057, merchant V007's discipline clause for clause) on the customer's wallet
-- over the push rail: four states, the send permit, the per-principal dispatch key, the
-- append-only event trail - plus the withdrawal's own three facts: it ROUTES (the decision
-- widened below), its rail is IRREVOCABLE (no edge leaves COMPLETED, and the machine trigger
-- binds every writer), and its destination is the platform's COPY of the instrument's opaque
-- reference, carrying INV-RAIL-03's shape rules physically so a bank identifier cannot rest
-- here either (paymentmethods V003's three refusals, restated for this column's writers).
--
-- NO CROSS-SCHEMA FOREIGN KEYS (ADR-0029): party_id, customer_id, payment_method_id and
-- wallet_account_id reference their owners by value; hold_reference names the ledger hold
-- the dispatch placed under the wallet account's lock (INV-BAL-04).
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION: statuses from WithdrawalStatus
-- .sqlValueList(), edges from permittedTransitions(), failure reasons from
-- WithdrawalFailureReason.sqlValueList(); PaymentsMigrationTest fails the build on drift.

CREATE TABLE payments.withdrawal (
    -- UUIDv7, minted by the application (ADR-0013).
    id                     uuid        PRIMARY KEY,

    party_id               uuid        NOT NULL,
    customer_id            uuid        NOT NULL,
    wallet_account_id      uuid        NOT NULL,
    payment_method_id      uuid        NOT NULL,

    -- The instrument's opaque destination, copied at dispatch across the PCI boundary's one
    -- registered bridge - so re-sends and inquiries never reach into paymentmethods, and a
    -- later detach cannot strand an in-flight withdrawal. INV-RAIL-03 at DB-CONSTRAINT rank,
    -- the V003 trio verbatim: the provider-reference charset, no letterless value, no
    -- international-identifier shape within an IBAN's own 34-character bound.
    destination_reference  text        NOT NULL
        CONSTRAINT withdrawal_destination_charset
            CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT withdrawal_destination_has_a_letter
        CHECK (destination_reference !~ '^[0-9_.:-]+$'),
    CONSTRAINT withdrawal_destination_is_not_an_account_identifier
        CHECK (NOT (char_length(destination_reference) <= 34
                AND destination_reference ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$')),

    amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL, CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT withdrawal_amount_is_positive CHECK (amount_minor > 0),

    -- OUR end-to-end reference (INV-PAY-04): minted before anything is sent, presented on
    -- every send and re-send, the scheme's dedupe key and Phase 8's join. ISO 20022's own
    -- 35-character bound (EndToEndReference), unique platform-wide.
    end_to_end_reference   text        NOT NULL UNIQUE
        CONSTRAINT withdrawal_reference_shape
            CHECK (end_to_end_reference ~ '^[A-Za-z0-9-]{1,35}$'),

    -- The routed rail, a frozen stored fact (V011's precedent): resolvers key on the row,
    -- and the vocabulary rule confines the literals to the adapters.
    rail                   text        NOT NULL
        CONSTRAINT withdrawal_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),

    -- Generated from WithdrawalStatus.sqlValueList().
    status                 text        NOT NULL
        CONSTRAINT withdrawal_status_is_known
            CHECK (status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),

    -- Generated from WithdrawalFailureReason.sqlValueList(); FAILED's exact companion.
    failure_reason         text
        CONSTRAINT withdrawal_failure_reason_is_known
            CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED')),
    CONSTRAINT withdrawal_failure_reason_matches_status
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),

    -- The scheme's word, exactly when COMPLETED (ADR-0062 section 1: their transaction
    -- reference and settlement cycle are Phase 8's reconciliation keys).
    scheme_reference       text        UNIQUE
        CONSTRAINT withdrawal_scheme_reference_shape
            CHECK (scheme_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT withdrawal_scheme_reference_matches_status
        CHECK ((status = 'COMPLETED') = (scheme_reference IS NOT NULL)),
    settlement_cycle       text
        CONSTRAINT withdrawal_settlement_cycle_bounded
            CHECK (length(settlement_cycle) BETWEEN 1 AND 64),
    CONSTRAINT withdrawal_settlement_cycle_only_when_completed
        CHECK (settlement_cycle IS NULL OR status = 'COMPLETED'),

    -- The claim's client key, kept per customer: the takeover's convergence target
    -- (ADR-0057 section 5, the scope's principal made a column).
    dispatch_key           text        NOT NULL
        CONSTRAINT withdrawal_dispatch_key_bounded
            CHECK (length(dispatch_key) BETWEEN 1 AND 200),

    -- The ledger hold the dispatch placed under the wallet account's lock (INV-BAL-04).
    hold_reference         uuid        NOT NULL,

    -- Application-supplied from the injected Clock, never DEFAULT now() (P0-TSK-015).
    created_at             timestamptz NOT NULL,

    -- THE SEND PERMIT (ADR-0057 section 4): committed before every send of our reference,
    -- forward-only for every writer (the trigger below), judged on the locked row.
    last_dispatched_at     timestamptz NOT NULL,
    CONSTRAINT withdrawal_permit_follows_creation
        CHECK (last_dispatched_at >= created_at)
);

CREATE UNIQUE INDEX withdrawal_one_per_dispatch_key
    ON payments.withdrawal (customer_id, dispatch_key);

-- The inquiry sweep's candidates: only rows still awaiting the scheme's word.
CREATE INDEX withdrawal_sweepable
    ON payments.withdrawal (created_at, id)
    WHERE status IN ('DISPATCHED', 'UNKNOWN');

-- The customer's own listing and read (ADR-0031's predicate rides the statements).
CREATE INDEX withdrawal_by_customer ON payments.withdrawal (customer_id, created_at DESC);

-- Every writer: a withdrawal is born DISPATCHED - its outcomes arrive through the machine.
CREATE FUNCTION payments.withdrawal_is_born_dispatched() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status <> 'DISPATCHED' THEN
        RAISE EXCEPTION 'a withdrawal is born DISPATCHED: its outcomes arrive through the machine (P7-TSK-008)'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER withdrawal_is_born_dispatched
    BEFORE INSERT ON payments.withdrawal
    FOR EACH ROW
    EXECUTE FUNCTION payments.withdrawal_is_born_dispatched();

-- The machine, the freeze and the permit, bound to every writer including the migrator
-- (merchant V007's function, this machine's columns; edges generated from
-- WithdrawalStatus.permittedTransitions()).
CREATE FUNCTION payments.withdrawal_permits_only_machine_edges() RETURNS trigger
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
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a withdrawal''s dispatch is frozen: wallet, instrument, destination, amount, reference and hold never move (P7-TSK-008)';
    END IF;
    IF (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason)
            OR (OLD.scheme_reference IS NOT NULL
                AND NEW.scheme_reference IS DISTINCT FROM OLD.scheme_reference)
            OR (OLD.settlement_cycle IS NOT NULL
                AND NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle) THEN
        RAISE EXCEPTION 'a withdrawal''s recorded outcome never moves (INV-HIST-01''s discipline)';
    END IF;
    IF NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'a withdrawal''s send permit only moves forward (ADR-0057 section 4)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
            AND OLD.status NOT IN ('DISPATCHED', 'UNKNOWN') THEN
        RAISE EXCEPTION 'a resolved withdrawal is never sent again (ADR-0057 section 4)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DISPATCHED' AND NEW.status IN ('COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'a withdrawal moves only along the machine''s edges: DISPATCHED -> {COMPLETED, FAILED, UNKNOWN}, UNKNOWN -> {COMPLETED, FAILED} (INV-LIFE-02/-04; INV-REV-03: nothing leaves COMPLETED)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER withdrawal_permits_only_machine_edges
    BEFORE UPDATE ON payments.withdrawal
    FOR EACH ROW
    EXECUTE FUNCTION payments.withdrawal_permits_only_machine_edges();

-- The append-only trail; the sweep ages an UNKNOWN from its entering move.
CREATE TABLE payments.withdrawal_event (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    withdrawal_id uuid        NOT NULL REFERENCES payments.withdrawal (id),
    from_status   text        NOT NULL
        CONSTRAINT withdrawal_event_from_status_is_known
            CHECK (from_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    to_status     text        NOT NULL
        CONSTRAINT withdrawal_event_to_status_is_known
            CHECK (to_status IN ('DISPATCHED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    actor_id      text        NOT NULL
        CONSTRAINT withdrawal_event_actor_id_bounded
            CHECK (length(actor_id) BETWEEN 1 AND 200),
    actor_type    text        NOT NULL
        CONSTRAINT withdrawal_event_actor_type_bounded
            CHECK (length(actor_type) BETWEEN 1 AND 50),
    occurred_at   timestamptz NOT NULL
);

CREATE INDEX withdrawal_event_by_withdrawal
    ON payments.withdrawal_event (withdrawal_id);

-- ----------------------------------------------------------------- provider evidence

-- The scheme's answers join the retained evidence (INV-HIST-02) as the THIRD subject: the
-- same cipher, the same classification, one evidence discipline. V005's at-most-one-subject
-- rule is recreated over the trio - the current definition lives here (ADR-0011).
ALTER TABLE payments.provider_evidence
    ADD COLUMN withdrawal_id uuid REFERENCES payments.withdrawal (id),
    DROP CONSTRAINT provider_evidence_has_at_most_one_subject,
    ADD CONSTRAINT provider_evidence_has_at_most_one_subject
        CHECK (num_nonnulls(attempt_id, refund_id, withdrawal_id) <= 1);

CREATE INDEX provider_evidence_by_withdrawal
    ON payments.provider_evidence (withdrawal_id)
    WHERE withdrawal_id IS NOT NULL;

-- ----------------------------------------------------------------- routing's second subject

-- ADR-0060 section 2's own sentence: routing happens when an intent is confirmed AND when a
-- withdrawal is initiated. The decision table gains the second subject with an exactly-one
-- XOR; the intent arm keeps its FK and its one-chosen index untouched.
--
-- WITHDRAWAL_ID CARRIES NO FOREIGN KEY, DELIBERATELY: a refused decision (NoEligibleRail)
-- is recorded BEFORE any withdrawal row can exist - the refusal is the fact, the wallet is
-- untouched, and the minted id it addresses was never born. The intent's FK survives
-- because an intent always exists before its confirmation routes.
ALTER TABLE payments.routing_decision
    ALTER COLUMN intent_id DROP NOT NULL,
    ADD COLUMN withdrawal_id uuid,
    ADD CONSTRAINT routing_decision_has_exactly_one_subject
        CHECK ((intent_id IS NULL) <> (withdrawal_id IS NULL));

-- ONE CHOSEN DECISION PER WITHDRAWAL (INV-RAIL-02: routed once), the intent index's twin;
-- refused decisions accumulate lawfully beneath it.
CREATE UNIQUE INDEX routing_decision_one_chosen_per_withdrawal
    ON payments.routing_decision (withdrawal_id)
    WHERE chosen_rail IS NOT NULL AND withdrawal_id IS NOT NULL;

CREATE INDEX routing_decision_by_withdrawal
    ON payments.routing_decision (withdrawal_id, created_at DESC, id DESC)
    WHERE withdrawal_id IS NOT NULL;

-- ----------------------------------------------------------------- seed: policy version 2

-- The withdrawal must route on a fresh database exactly as the confirmation must
-- (V013's seeding argument verbatim: INV-HIST-04's NOT NULL refuses an unpinned decision),
-- so version 2 ships with the machinery that needs it. A version is WHOLE (the
-- newest-effective resolution), so rule 0 carries the standing card pay-in route forward
-- byte for byte and rule 1 adds the bank pay-out onto the instant rail. created_by names
-- the migration; the rail literals are the V013 seed's precedent.
INSERT INTO payments.routing_policy_version
    (id, version, effective_from, created_at, created_by, reason)
VALUES
    ('019992e0-0000-7000-8000-000000000010', 2, now(), now(), 'V016',
     'the standing card pay-in route carried forward, plus the bank-account pay-out onto the instant rail (P7-TSK-008, ADR-0062 section 6)');

INSERT INTO payments.routing_rule
    (id, policy_version_id, rule_index, direction, instrument_kind, currency,
     ceiling_amount_minor, ceiling_currency, ceiling_scale)
VALUES
    ('019992e0-0000-7000-8000-000000000011', '019992e0-0000-7000-8000-000000000010',
     0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000012', '019992e0-0000-7000-8000-000000000010',
     1, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL);

INSERT INTO payments.routing_rule_rail (rule_id, position, rail)
VALUES
    ('019992e0-0000-7000-8000-000000000011', 0, 'card'),
    ('019992e0-0000-7000-8000-000000000012', 0, 'instant');

-- ----------------------------------------------------------------- comments and grants

COMMENT ON TABLE payments.withdrawal IS
    'One wallet withdrawal over the push rail (P7-TSK-008, ADR-0062 section 6): hold-then-dispatch under the wallet account''s lock, the send permit forward-only, COMPLETED irrevocable (INV-REV-03). The money facts are the hold and the wallet-withdrawal:<id> posting, never this row alone.';
COMMENT ON COLUMN payments.withdrawal.destination_reference IS
    'The instrument''s opaque destination, copied at dispatch (INV-RAIL-03; RESTRICTED-PII): presented to the scheme on every send, never in a response, a log or an event.';
COMMENT ON COLUMN payments.withdrawal.end_to_end_reference IS
    'OUR reference (INV-PAY-04): the scheme deduplicates on it, the sweep inquires by it, Phase 8 joins on it.';
COMMENT ON COLUMN payments.withdrawal.last_dispatched_at IS
    'The send permit (ADR-0057 section 4): committed before every send, forward-only, judged on the locked row against the rail''s DECLARED outcome deadline plus margin.';
COMMENT ON COLUMN payments.routing_decision.withdrawal_id IS
    'The decision''s second subject (P7-TSK-008): exactly one of intent_id and withdrawal_id. No FK, deliberately - a refused decision precedes any withdrawal row (the header records why).';

GRANT SELECT, INSERT ON payments.withdrawal TO finapp_app;
GRANT UPDATE (status, failure_reason, scheme_reference, settlement_cycle, last_dispatched_at)
    ON payments.withdrawal TO finapp_app;
GRANT SELECT, INSERT ON payments.withdrawal_event TO finapp_app;
