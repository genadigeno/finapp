-- P7-TSK-009: pay-by-bank pay-ins (ADR-0062 section 5). The push attempt gains its own
-- facts in its own columns - OUR end-to-end reference minted at birth (INV-PAY-04), the
-- payer's authorization handle, the scheme's transaction reference and settlement cycle
-- (Phase 8's keys), and the initiation permit - and the push machine gains its inbound
-- edge (AWAITING_PAYER -> EXECUTED: the payer executes, the platform never dispatches).
-- V012 and V014 are applied history; this migration REPLACES the trigger function with a
-- definition regenerated from the same machine (InteractionModel.edges()), exactly as
-- V014 replaced V012's. An unattributable, money-carrying confirmation parks in
-- payments.unmatched_confirmation beside its SUSPENSE_UNMATCHED entry (INV-REC-05), and
-- routing version 3 carries both standing routes forward beside the bank pay-in.

-- ----------------------------------------------------------------------- the columns

ALTER TABLE payments.payment_attempt
    -- OUR reference: ISO 20022's own 35-character bound (EndToEndReference), unique
    -- platform-wide - the scheme's dedupe key, the callback's attribution key, Phase 8's
    -- join. Exactly on push rows, from birth, frozen with the birth facts.
    ADD COLUMN end_to_end_reference text
        CONSTRAINT payment_attempt_end_to_end_reference_shape
            CHECK (end_to_end_reference ~ '^[A-Za-z0-9-]{1,35}$'),
    ADD CONSTRAINT payment_attempt_end_to_end_reference_is_unique
        UNIQUE (end_to_end_reference),

    -- The payer's authorization handle: a capability URL, RESTRICTED - rendered once to
    -- its owner, never in a log, an event or an audit record. Bounded by the port's own
    -- constant (InitiationAnswer.MAX_HANDLE_LENGTH); NULL until the scheme opens the
    -- initiation, then frozen (one initiation, one handle - the scheme's dedupe).
    ADD COLUMN authorization_handle text
        CONSTRAINT payment_attempt_authorization_handle_bounded
            CHECK (char_length(authorization_handle) BETWEEN 1 AND 512),

    -- The scheme's transaction reference, exactly when a push row is EXECUTED (the
    -- withdrawal's pair, on the inbound machine): one scheme execution credits one
    -- attempt, platform-wide (the V015 acquirer-reference discipline).
    ADD COLUMN scheme_reference text
        CONSTRAINT payment_attempt_scheme_reference_shape
            CHECK (scheme_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    ADD CONSTRAINT payment_attempt_scheme_reference_is_unique UNIQUE (scheme_reference),
    ADD COLUMN settlement_cycle text
        CONSTRAINT payment_attempt_settlement_cycle_bounded
            CHECK (length(settlement_cycle) BETWEEN 1 AND 64),

    -- The initiation permit: the last outbound contact, forward-only for every writer
    -- (ADR-0062 section 3 adapted - the sweep's pacing arbiter, never a money guard,
    -- because an initiation moves nothing and re-initiating converges on the scheme's
    -- dedupe; the handle predicate below is the money-adjacent rule).
    ADD COLUMN last_dispatched_at timestamptz
        CONSTRAINT payment_attempt_permit_not_before_birth
            CHECK (last_dispatched_at >= created_at),

    -- The push facts exist exactly on push rows, both directions (the V012/V014
    -- foreign-model wall, mirrored for the second vocabulary).
    ADD CONSTRAINT payment_attempt_push_carries_its_reference
        CHECK ((interaction_model = 'PUSH') = (end_to_end_reference IS NOT NULL)),
    ADD CONSTRAINT payment_attempt_push_carries_its_permit
        CHECK ((interaction_model = 'PUSH') = (last_dispatched_at IS NOT NULL)),
    ADD CONSTRAINT payment_attempt_foreign_model_carries_no_push_facts
        CHECK (interaction_model = 'PUSH'
            OR (authorization_handle IS NULL
                AND scheme_reference IS NULL
                AND settlement_cycle IS NULL)),

    -- Which push facts each stage requires and forbids - the aggregate constructor's push
    -- rules, verbatim: the scheme's reference exactly with EXECUTED, the cycle only
    -- beside it.
    ADD CONSTRAINT payment_attempt_push_facts_match_status
        CHECK (interaction_model <> 'PUSH'
            OR ((scheme_reference IS NOT NULL) = (status = 'EXECUTED')
                AND (settlement_cycle IS NULL OR scheme_reference IS NOT NULL)));

-- ----------------------------------------------------------------------- the trigger

-- The V014 function, REPLACED (V014 is applied history): the push facts join the freezes,
-- the permit moves only forward, the push machine carries its inbound edge - and a
-- same-status write exists now, for exactly one reason: a push row records its handle and
-- renews its permit WITHOUT an edge. Every other model still moves or is refused whole.
CREATE OR REPLACE FUNCTION payments.payment_attempt_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.intent_id <> NEW.intent_id
        OR OLD.auth_reference IS DISTINCT FROM NEW.auth_reference
        OR OLD.end_to_end_reference IS DISTINCT FROM NEW.end_to_end_reference
        OR OLD.rail <> NEW.rail
        OR OLD.interaction_model <> NEW.interaction_model
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a payment attempt''s birth facts are immutable: identity, intent, the dispatch references, the rail and the interaction model are what the operation is (ADR-0046, ADR-0059, INV-HIST-01)';
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
        OR (OLD.authorization_handle IS NOT NULL AND NEW.authorization_handle IS DISTINCT FROM OLD.authorization_handle)
        OR (OLD.scheme_reference IS NOT NULL AND NEW.scheme_reference IS DISTINCT FROM OLD.scheme_reference)
        OR (OLD.settlement_cycle IS NOT NULL AND NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle)
        OR (OLD.failure_reason IS NOT NULL AND NEW.failure_reason IS DISTINCT FROM OLD.failure_reason) THEN
        RAISE EXCEPTION 'a recorded provider fact never changes: payload columns move only from NULL to a value (P5-TSK-008, INV-HIST-02)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at
        AND NEW.last_dispatched_at < OLD.last_dispatched_at THEN
        RAISE EXCEPTION 'an initiation permit only moves forward (P7-TSK-009, ADR-0062 section 3)';
    END IF;
    IF NEW.status = OLD.status THEN
        IF OLD.interaction_model <> 'PUSH' THEN
            RAISE EXCEPTION 'only a push row records a payload without an edge: the initiation handle and its permit (P7-TSK-009)';
        END IF;
        IF NEW.scheme_reference IS DISTINCT FROM OLD.scheme_reference
            OR NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle
            OR NEW.failure_reason IS DISTINCT FROM OLD.failure_reason THEN
            RAISE EXCEPTION 'an outcome fact arrives only with its edge: a same-status write carries the handle or the permit, nothing else (P7-TSK-009)';
        END IF;
        IF NEW.authorization_handle IS NOT DISTINCT FROM OLD.authorization_handle
            AND NEW.last_dispatched_at IS NOT DISTINCT FROM OLD.last_dispatched_at THEN
            -- The licence is exactly as wide as its two producers: record the handle, or
            -- move the permit. A write that does neither is no writer this machine knows.
            RAISE EXCEPTION 'a payment attempt moves only along its own model''s edges (ADR-0045, ADR-0059, INV-LIFE-02/-04)';
        END IF;
        RETURN NEW;
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
                AND ((OLD.status = 'AWAITING_PAYER' AND NEW.status IN ('FAILED', 'EXECUTION_DISPATCHED', 'EXECUTED'))
                    OR (OLD.status = 'EXECUTION_DISPATCHED' AND NEW.status IN ('FAILED', 'EXECUTION_UNKNOWN', 'EXECUTED'))
                    OR (OLD.status = 'EXECUTION_UNKNOWN' AND NEW.status IN ('FAILED', 'EXECUTED'))))) THEN
        RAISE EXCEPTION 'a payment attempt moves only along its own model''s edges (ADR-0045, ADR-0059, INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- The pay-in sweep's candidate read: waiting rows by their last outbound contact.
CREATE INDEX payment_attempt_awaiting_by_contact
    ON payments.payment_attempt (last_dispatched_at, id)
    WHERE interaction_model = 'PUSH' AND status = 'AWAITING_PAYER';

-- ----------------------------------------------------- the unmatched confirmation (INV-REC-05)

-- A scheme confirmation naming no initiation the platform made, carrying value: retained,
-- parked beside its DR <rail clearing> / CR SUSPENSE_UNMATCHED entry
-- (unmatched-confirmation:<rail>:<reference>), aged and alerted - never credited by
-- guesswork, never edited (append-only for every writer; resolution is Phase 8's and
-- arrives with its own migration).
CREATE TABLE payments.unmatched_confirmation (
    id                uuid        PRIMARY KEY,
    rail              text        NOT NULL
        CONSTRAINT unmatched_confirmation_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    scheme_reference  text        NOT NULL
        CONSTRAINT unmatched_confirmation_reference_shape
            CHECK (scheme_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    -- One parking per scheme transaction per rail: the fresh-id duplicate's arbiter
    -- (the V015 shape), race-free under ON CONFLICT DO NOTHING.
    CONSTRAINT unmatched_confirmation_one_per_reference UNIQUE (rail, scheme_reference),
    amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL, CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT unmatched_confirmation_amount_is_positive CHECK (amount_minor > 0),
    received_at       timestamptz NOT NULL,
    -- The suspense entry this parking posted: the chain stays walkable by stored id.
    entry_ref         uuid        NOT NULL
);

CREATE FUNCTION payments.unmatched_confirmation_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an unmatched confirmation is evidence: parked once, resolved by Phase 8''s own producer, edited by nobody (INV-REC-05, INV-HIST-02)';
END;
$$;

CREATE TRIGGER unmatched_confirmation_is_append_only
    BEFORE UPDATE OR DELETE ON payments.unmatched_confirmation
    FOR EACH ROW EXECUTE FUNCTION payments.unmatched_confirmation_is_append_only();

-- ----------------------------------------------------------------- seed: policy version 3

-- The bank pay-in must route on a fresh database exactly as the card's and the
-- withdrawal's do (V013's and V016's seeding argument verbatim: INV-HIST-04's NOT NULL
-- refuses an unpinned decision). A version is WHOLE (the newest-effective resolution), so
-- rules 0 and 1 carry the standing card pay-in and bank pay-out forward byte for byte and
-- rule 2 adds the bank pay-in onto the instant rail. created_by names the migration.
INSERT INTO payments.routing_policy_version
    (id, version, effective_from, created_at, created_by, reason)
VALUES
    ('019992e0-0000-7000-8000-000000000020', 3, now(), now(), 'V017',
     'the standing card pay-in and bank pay-out carried forward, plus the bank-account pay-in onto the instant rail (P7-TSK-009, ADR-0062 section 5)');

INSERT INTO payments.routing_rule
    (id, policy_version_id, rule_index, direction, instrument_kind, currency,
     ceiling_amount_minor, ceiling_currency, ceiling_scale)
VALUES
    ('019992e0-0000-7000-8000-000000000021', '019992e0-0000-7000-8000-000000000020',
     0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000022', '019992e0-0000-7000-8000-000000000020',
     1, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL),
    ('019992e0-0000-7000-8000-000000000023', '019992e0-0000-7000-8000-000000000020',
     2, 'PAY_IN', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL);

INSERT INTO payments.routing_rule_rail (rule_id, position, rail)
VALUES
    ('019992e0-0000-7000-8000-000000000021', 0, 'card'),
    ('019992e0-0000-7000-8000-000000000022', 0, 'instant'),
    ('019992e0-0000-7000-8000-000000000023', 0, 'instant');

-- ----------------------------------------------------------------- comments and grants

COMMENT ON COLUMN payments.payment_attempt.end_to_end_reference IS
    'OUR reference on the push model (INV-PAY-04): minted at birth, the scheme''s dedupe key, the callback''s attribution key, Phase 8''s join. Frozen among the birth facts.';
COMMENT ON COLUMN payments.payment_attempt.authorization_handle IS
    'The payer''s authorization handle (RESTRICTED): a capability URL rendered once to its owner - never in a log, an event or an audit record. NULL until the scheme opens the initiation, then frozen.';
COMMENT ON COLUMN payments.payment_attempt.last_dispatched_at IS
    'The initiation permit (P7-TSK-009, ADR-0062 section 3 adapted): the last outbound contact, forward-only - the sweep''s pacing arbiter; the handle predicate is the safety rule.';
COMMENT ON TABLE payments.unmatched_confirmation IS
    'A money-carrying confirmation naming no initiation the platform made (ADR-0062 section 5, INV-REC-05): parked beside its SUSPENSE_UNMATCHED entry, aged, alerted, resolved only by Phase 8''s producer.';

-- The app role writes the push payload columns like the two-step's (V003's list, widened
-- the V014 way).
GRANT UPDATE (authorization_handle, scheme_reference, settlement_cycle, last_dispatched_at)
    ON payments.payment_attempt TO finapp_app;

GRANT SELECT, INSERT ON payments.unmatched_confirmation TO finapp_app;
