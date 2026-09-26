-- P7-TSK-003: rail routing (ADR-0060) - the versioned policy, the recorded availability
-- facts, and the pinned per-payment decisions with their step trails.
--
-- The policy tables follow merchant V004's fee-schedule discipline verbatim: immutable by
-- trigger for EVERY writer, version numbers minted by the unique index (the arbiter is also
-- the queue), effective forward so no version can ever claim to have routed a payment that
-- was decided before it existed (INV-HIST-04's keystone, the INV-MER-03 shape).
--
-- The decision is INV-RAIL-02 as data: the pinned version, the judged inputs, every
-- candidate's step. It commits in the confirmation's Tx1 beside the attempt it governs and
-- is frozen for every writer; recomputing the pinned version over the stored inputs
-- reproduces the choice, and the suite drives exactly that.

-- ----------------------------------------------------------------------- policy versions

CREATE TABLE payments.routing_policy_version (
    id            uuid        PRIMARY KEY,

    version       int         NOT NULL
        CONSTRAINT routing_policy_version_number_is_positive
            CHECK (version >= 1),

    effective_from timestamptz NOT NULL,

    -- Application-supplied from the injected Clock, never DEFAULT now() (P0-TSK-015's rule).
    created_at    timestamptz NOT NULL,

    created_by    text        NOT NULL
        CONSTRAINT routing_policy_version_created_by_bounded
            CHECK (length(created_by) BETWEEN 1 AND 200),

    -- The operator's own words, verbatim (INV-AUD-03): changing how money travels is an
    -- operational judgement, and an unexplained one is what a reviewer needs explained.
    reason        text        NOT NULL
        CONSTRAINT routing_policy_version_reason_bounded
            CHECK (length(reason) BETWEEN 1 AND 500),

    -- THE KEYSTONE (INV-HIST-04, the INV-MER-03 shape): a version cannot be created already
    -- effective in the past, so no decision's explanation can be rewritten by a backdated
    -- policy. The domain refuses it first; this refuses raw SQL that skipped the domain.
    CONSTRAINT routing_policy_version_takes_effect_forward
        CHECK (effective_from >= created_at),

    -- THE VERSION-MINTING ARBITER, and also its queue (merchant V004's discipline): a racer
    -- inserting a number an uncommitted transaction holds WAITS on this index and is refused
    -- when that transaction commits, then re-reads and takes the next. Ten instances produce
    -- ten distinct numbers with no gap, without any row being locked.
    CONSTRAINT routing_policy_version_number_is_unique
        UNIQUE (version)
);

-- The in-force resolution's index: newest-effective first, highest version breaking ties -
-- exactly the store's ORDER BY.
CREATE INDEX routing_policy_version_effective_order
    ON payments.routing_policy_version (effective_from DESC, version DESC);

CREATE TABLE payments.routing_rule (
    id                   uuid PRIMARY KEY,

    policy_version_id    uuid NOT NULL
        REFERENCES payments.routing_policy_version (id),

    rule_index           int  NOT NULL
        CONSTRAINT routing_rule_index_is_zero_based
            CHECK (rule_index >= 0),

    -- Generated from PaymentDirection.sqlValueList().
    direction            text NOT NULL
        CONSTRAINT routing_rule_direction_is_known
            CHECK (direction IN ('PAY_IN', 'PAY_OUT')),

    -- Generated from InstrumentKind.sqlValueList().
    instrument_kind      text NOT NULL
        CONSTRAINT routing_rule_instrument_kind_is_known
            CHECK (instrument_kind IN ('CARD_TOKEN', 'BANK_ACCOUNT', 'WALLET')),

    -- NULL matches any currency.
    currency             CHAR(3)
        CONSTRAINT routing_rule_currency_shape
            CHECK (currency ~ '^[A-Z]{3}$'),

    -- The optional inclusive upper bound, one-sided on purpose (an ordered rule list already
    -- expresses "and the rest" downward). All-or-nothing as a triple, positive, priced in
    -- the currency the rule matches - which is why a ceiling requires one.
    ceiling_amount_minor BIGINT, ceiling_currency CHAR(3), ceiling_scale SMALLINT, CHECK (ceiling_currency ~ '^[A-Z]{3}$'), CHECK (ceiling_scale BETWEEN 0 AND 9), CHECK ((ceiling_amount_minor IS NULL) = (ceiling_currency IS NULL) AND (ceiling_amount_minor IS NULL) = (ceiling_scale IS NULL)),

    CONSTRAINT routing_rule_ceiling_is_positive
        CHECK (ceiling_amount_minor IS NULL OR ceiling_amount_minor > 0),

    CONSTRAINT routing_rule_ceiling_requires_a_currency
        CHECK (ceiling_amount_minor IS NULL OR currency IS NOT NULL),

    CONSTRAINT routing_rule_ceiling_priced_in_the_matched_currency
        CHECK (ceiling_currency IS NULL OR ceiling_currency = currency),

    CONSTRAINT routing_rule_index_is_unique_per_version
        UNIQUE (policy_version_id, rule_index)
);

CREATE TABLE payments.routing_rule_rail (
    rule_id  uuid NOT NULL
        REFERENCES payments.routing_rule (id),

    position int  NOT NULL
        CONSTRAINT routing_rule_rail_position_is_zero_based
            CHECK (position >= 0),

    -- The candidate's name, shape-checked like the attempt's stored rail (V011). Deliberately
    -- NOT a foreign key to anything: the declared rails are compiled data (ADR-0059), and a
    -- policy may lawfully outlive a build's declarations - the decision records the mismatch
    -- as a step (UNDECLARED_BY_BUILD) rather than this schema refusing history.
    rail     text NOT NULL
        CONSTRAINT routing_rule_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),

    CONSTRAINT routing_rule_rail_one_per_position UNIQUE (rule_id, position),
    CONSTRAINT routing_rule_rail_named_once       UNIQUE (rule_id, rail)
);

-- ----------------------------------------------------------------------- availability

CREATE TABLE payments.rail_availability (
    rail       text        PRIMARY KEY
        CONSTRAINT rail_availability_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),

    available  boolean     NOT NULL,

    reason     text        NOT NULL
        CONSTRAINT rail_availability_reason_bounded
            CHECK (length(reason) BETWEEN 1 AND 500),

    changed_by text        NOT NULL
        CONSTRAINT rail_availability_changed_by_bounded
            CHECK (length(changed_by) BETWEEN 1 AND 200),

    changed_at timestamptz NOT NULL
);

-- ----------------------------------------------------------------------- decisions

CREATE TABLE payments.routing_decision (
    id                 uuid        PRIMARY KEY,

    intent_id          uuid        NOT NULL
        REFERENCES payments.payment_intent (id),

    -- INV-HIST-04's routing element: NOT NULL is the enforcement rank the invariant names.
    policy_version_id  uuid        NOT NULL
        REFERENCES payments.routing_policy_version (id),

    -- The judged inputs, snapshotted verbatim so the pinned version reproduces the choice.
    direction          text        NOT NULL
        CONSTRAINT routing_decision_direction_is_known
            CHECK (direction IN ('PAY_IN', 'PAY_OUT')),
    instrument_kind    text        NOT NULL
        CONSTRAINT routing_decision_instrument_kind_is_known
            CHECK (instrument_kind IN ('CARD_TOKEN', 'BANK_ACCOUNT', 'WALLET')),
    amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL, scale SMALLINT NOT NULL, CHECK (currency ~ '^[A-Z]{3}$'), CHECK (scale BETWEEN 0 AND 9),

    -- Which rule matched, or NULL when the version had no rule for this payment's shape -
    -- a recorded refusal with nothing to judge.
    matched_rule_index int
        CONSTRAINT routing_decision_matched_rule_is_zero_based
            CHECK (matched_rule_index >= 0),

    -- The rail dispatched on, or NULL for the recorded refusal (payments.NoEligibleRail).
    chosen_rail        text
        CONSTRAINT routing_decision_chosen_rail_shape
            CHECK (chosen_rail ~ '^[a-z][a-z0-9-]{0,31}$'),

    -- Steps exist only under a matched rule (the aggregate's rule, held here too).
    CONSTRAINT routing_decision_chooses_only_from_a_matched_rule
        CHECK (chosen_rail IS NULL OR matched_rule_index IS NOT NULL),

    created_at         timestamptz NOT NULL
);

-- ONE CHOSEN DECISION PER PAYMENT (INV-RAIL-02: routed once). Partial, deliberately: refused
-- decisions accumulate one per refused confirm attempt - each an audited, explainable fact -
-- and must not block the successful confirm that follows an operator's repair.
CREATE UNIQUE INDEX routing_decision_one_chosen_per_intent
    ON payments.routing_decision (intent_id)
    WHERE chosen_rail IS NOT NULL;

-- The explanation's read: the newest decision for a payment.
CREATE INDEX routing_decision_by_intent
    ON payments.routing_decision (intent_id, created_at DESC, id DESC);

CREATE TABLE payments.routing_decision_step (
    decision_id        uuid NOT NULL
        REFERENCES payments.routing_decision (id),

    step_index         int  NOT NULL
        CONSTRAINT routing_decision_step_index_is_zero_based
            CHECK (step_index >= 0),

    rail               text NOT NULL
        CONSTRAINT routing_decision_step_rail_shape
            CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),

    -- Generated from RoutingStepVerdict.sqlValueList().
    verdict            text NOT NULL
        CONSTRAINT routing_decision_step_verdict_is_known
            CHECK (verdict IN ('CHOSEN', 'REJECTED', 'ABANDONED')),

    -- Generated from RoutingRejection.sqlValueList().
    rejection          text
        CONSTRAINT routing_decision_step_rejection_is_known
            CHECK (rejection IN ('UNAVAILABLE', 'CURRENCY_UNSUPPORTED', 'AMOUNT_EXCEEDS_CEILING', 'MODEL_CANNOT_CARRY_INSTRUMENT', 'DESTINATION_UNREACHABLE', 'NOTHING_SENT', 'UNDECLARED_BY_BUILD')),

    -- A rejection exists exactly on a non-CHOSEN step (the aggregate's rule, at this rank).
    CONSTRAINT routing_decision_step_rejection_exactly_when_not_chosen
        CHECK ((verdict = 'CHOSEN') = (rejection IS NULL)),

    -- An abandonment is knowledge and nothing else (INV-RAIL-02): the one lawful
    -- post-dispatch verdict is NOTHING_SENT. An *_UNKNOWN outcome stays on its rail
    -- (INV-LIFE-03), and no row here can say otherwise, for any writer.
    CONSTRAINT routing_decision_step_abandons_only_on_knowledge
        CHECK (verdict <> 'ABANDONED' OR rejection = 'NOTHING_SENT'),

    -- The availability observation this step used (ADR-0060 section 4).
    rail_available     boolean NOT NULL,

    -- The declared capability descriptor's version (ADR-0060 section 2) - absent exactly
    -- when the build declared no such rail.
    descriptor_version int
        CONSTRAINT routing_decision_step_descriptor_version_is_positive
            CHECK (descriptor_version >= 1),
    CONSTRAINT routing_decision_step_descriptor_absent_exactly_when_undeclared
        CHECK ((rejection = 'UNDECLARED_BY_BUILD') = (descriptor_version IS NULL)),

    CONSTRAINT routing_decision_step_one_per_index PRIMARY KEY (decision_id, step_index)
);

-- ----------------------------------------------------------------------- immutability

-- IMMUTABILITY, BOUND TO EVERY WRITER INCLUDING THE MIGRATOR (merchant V004's discipline).
-- The step table's INSERT stays open: the fallback appends ABANDONED steps (ADR-0060
-- section 5) - appending is not editing, and the step CHECKs above bound what an appended
-- row can say.
CREATE FUNCTION payments.routing_records_are_immutable() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'a routing record is immutable: policy change creates a NEW version effective forward, and a decision is the pinned explanation of a payment that happened (ADR-0060, INV-HIST-04, INV-RAIL-02). Table %, operation %', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER routing_policy_version_is_immutable
    BEFORE UPDATE OR DELETE ON payments.routing_policy_version
    FOR EACH ROW
    EXECUTE FUNCTION payments.routing_records_are_immutable();

CREATE TRIGGER routing_rule_is_immutable
    BEFORE UPDATE OR DELETE ON payments.routing_rule
    FOR EACH ROW
    EXECUTE FUNCTION payments.routing_records_are_immutable();

CREATE TRIGGER routing_rule_rail_is_immutable
    BEFORE UPDATE OR DELETE ON payments.routing_rule_rail
    FOR EACH ROW
    EXECUTE FUNCTION payments.routing_records_are_immutable();

CREATE TRIGGER routing_decision_is_immutable
    BEFORE UPDATE OR DELETE ON payments.routing_decision
    FOR EACH ROW
    EXECUTE FUNCTION payments.routing_records_are_immutable();

CREATE TRIGGER routing_decision_step_is_immutable
    BEFORE UPDATE OR DELETE ON payments.routing_decision_step
    FOR EACH ROW
    EXECUTE FUNCTION payments.routing_records_are_immutable();

-- ----------------------------------------------------------------------- seed

-- Version 1: the platform's standing route, seeded WITH the machinery because INV-HIST-04's
-- NOT NULL means no payment can be confirmed without a version to pin - and every payment
-- the platform takes today is a card pay-in. The rail literal is the V011 backfill's
-- precedent: migration SQL records history, and the adapter's declaration is what the
-- vocabulary rule confines. created_by names the migration, not a person; the acts an
-- operator performs from here are audited with theirs.
INSERT INTO payments.routing_policy_version
    (id, version, effective_from, created_at, created_by, reason)
VALUES
    ('019992e0-0000-7000-8000-000000000001', 1, now(), now(), 'V013',
     'the platform''s standing card pay-in route, seeded with the routing machinery (P7-TSK-003, ADR-0060)');

INSERT INTO payments.routing_rule
    (id, policy_version_id, rule_index, direction, instrument_kind, currency,
     ceiling_amount_minor, ceiling_currency, ceiling_scale)
VALUES
    ('019992e0-0000-7000-8000-000000000002', '019992e0-0000-7000-8000-000000000001',
     0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL);

INSERT INTO payments.routing_rule_rail (rule_id, position, rail)
VALUES ('019992e0-0000-7000-8000-000000000002', 0, 'card');

-- ----------------------------------------------------------------------- comments

COMMENT ON TABLE payments.routing_policy_version IS
    'One immutable version of the routing policy (ADR-0060 section 1): ordered rules, '
    'effective forward, reasoned. INV-HIST-04''s third subject after fees.';
COMMENT ON TABLE payments.rail_availability IS
    'The recorded operator fact about a rail''s availability (ADR-0060 section 4): read '
    'inside the decision''s transaction, so N instances read the same answer. An absent row '
    'means available; no instance-local health state ever takes part in routing.';
COMMENT ON TABLE payments.routing_decision IS
    'The pinned routing decision of one payment (INV-RAIL-02): the policy version, the '
    'judged inputs, the chosen rail or the recorded refusal. Committed beside the attempt '
    'it governs; recomputing the pinned version over these inputs reproduces the choice.';
COMMENT ON TABLE payments.routing_decision_step IS
    'One candidate''s judgement on a decision, append-only: rejected with its enumerated '
    'reason, chosen, or abandoned on NOTHING_SENT - knowledge, never ambiguity '
    '(INV-RAIL-02; an unknown outcome stays on its rail, INV-LIFE-03).';

-- ----------------------------------------------------------------------- grants

-- THE GRANTS ARRIVE WITH THE TABLES (P5-TSK-001's floor). No UPDATE and no DELETE anywhere
-- but the availability fact, whose whole meaning is the newest act; the immutability
-- triggers stand behind the withheld grants for the writers grants cannot bind.
GRANT SELECT, INSERT ON payments.routing_policy_version TO finapp_app;
GRANT SELECT, INSERT ON payments.routing_rule TO finapp_app;
GRANT SELECT, INSERT ON payments.routing_rule_rail TO finapp_app;
GRANT SELECT, INSERT, UPDATE ON payments.rail_availability TO finapp_app;
GRANT SELECT, INSERT ON payments.routing_decision TO finapp_app;
GRANT SELECT, INSERT ON payments.routing_decision_step TO finapp_app;
