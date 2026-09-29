-- P8-TSK-004: the expectation register and rule set v1 (ADR-0067, ADR-0068).
--
-- What the platform EXPECTS the outside world to report, opened inside each completing
-- transaction as a copy of its clearing journal line - and the versioned, frozen rules that
-- date and (from P8-TSK-011) match it. Six rule-set tables, the expectation with its whole
-- machine, and the key/alias index every counterparty reference resolves through.
--
--   * `rule_set` and its members - versioned data, content frozen by trigger, version 1
--     seeded ACTIVE per source with this migration as its provenance (the payments V013
--     routing precedent). The proposal/activation machine (PROPOSED, RETIRED, the four-eyes
--     CHECK) arrives with P8-TSK-022, regenerating the status CHECK and trigger.
--   * `expectation` - born OPEN here; the machine's every edge is stated NOW (generated from
--     ExpectationStatus.permittedTransitions(), reconciled by the migration test) because the
--     narrowed UPDATE grant and the frozen birth columns bind every writer, while the edge
--     producers arrive task by task (allocation -011, ageing -013, resolution -015,
--     repudiation -023).
--   * `expectation_event`, `expectation_key`, `reference_alias` - append-only for every
--     writer. Keys and aliases are scoped per source and NO kind is exempt (the transition's
--     A5: the announced cycle is a column on the expectation, never a key).
--
-- source_id and the seeded source UUIDs are COPIES of settlement's V002 seed - deliberately
-- no cross-schema foreign key (ADR-0029, ADR-0064): matching never reads another module's
-- tables, and the seeded identities are frozen there by trigger.

-- ---------------------------------------------------------------------------------------------
-- The versioned rule set: who decides what counts as a match, and when money is late.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.rule_set (
    id                UUID        NOT NULL,
    source_id         UUID        NOT NULL,
    version           INT         NOT NULL,
    status            TEXT        NOT NULL,
    funding_lag_days  INT         NOT NULL,
    gain_min_age_days INT         NOT NULL,
    effective_from    DATE        NOT NULL,
    proposed_by       TEXT        NOT NULL,
    decided_by        TEXT        NOT NULL,
    reason            TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    correlation_id    TEXT        NOT NULL,

    CONSTRAINT rule_set_pk PRIMARY KEY (id),
    CONSTRAINT rule_set_version_positive CHECK (version >= 1),
    -- The machine that exists: version 1 is born ACTIVE by seed, and nothing yet proposes
    -- or retires. PROPOSED and RETIRED arrive with P8-TSK-022's proposal flow, which
    -- regenerates this CHECK, the trigger below and the four-eyes rule with the edges its
    -- producer brings (the settlement.file precedent).
    CONSTRAINT rule_set_status CHECK (status IN ('ACTIVE')),
    CONSTRAINT rule_set_version_once UNIQUE (source_id, version),
    CONSTRAINT rule_set_funding_lag_counted CHECK (funding_lag_days >= 0),
    CONSTRAINT rule_set_gain_age_counted CHECK (gain_min_age_days >= 0),
    CONSTRAINT rule_set_reason_bounded CHECK (char_length(reason) <= 1000)
);

-- Exactly one ACTIVE version per source, for every writer: the opener reads it lock-free,
-- because either side of a racing activation is valid and the deciding version is pinned on
-- every row it dates (INV-HIST-04).
CREATE UNIQUE INDEX rule_set_one_active
    ON reconciliation.rule_set (source_id)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE reconciliation.rule_set IS
    'Versioned matching configuration, one ACTIVE per source (ADR-0068 §8): what counts as a match is a reviewed, dated decision, never code someone edits. Content frozen by trigger; a change is a NEW version (P8-TSK-022).';

CREATE OR REPLACE FUNCTION reconciliation.rule_set_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set version is immutable for every writer (ADR-0068 §8, INV-HIST-04): a change is a NEW version, activated by P8-TSK-022''s four-eyes flow';
END;
$$;

CREATE TRIGGER rule_set_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.rule_set
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_is_frozen();

-- The dating lags, per expectation kind (ADR-0067 §4: expected_by = posting_date + lag).
CREATE TABLE reconciliation.rule_set_lag (
    rule_set_id      UUID NOT NULL,
    expectation_kind TEXT NOT NULL,
    lag_days         INT  NOT NULL,

    CONSTRAINT rule_set_lag_pk PRIMARY KEY (rule_set_id, expectation_kind),
    CONSTRAINT rule_set_lag_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN',
        'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE')),
    CONSTRAINT rule_set_lag_counted CHECK (lag_days >= 0)
);

CREATE OR REPLACE FUNCTION reconciliation.rule_set_lag_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s lags are immutable for every writer (ADR-0068 §8): a change is a NEW version';
END;
$$;

CREATE TRIGGER rule_set_lag_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.rule_set_lag
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_lag_is_frozen();

-- The matching rules, in priority order per version (ADR-0068 §2): keys are tried in
-- priority order and the first rule that yields any candidate fires. `key_kind` is the
-- reference kind consulted - an expectation key kind, or the line-side ORIGINAL_REF a fee or
-- adjustment names its original by; NULL for a rule that consults no key (a value-date group,
-- a batch-level CHECK). `operation_anchored` is ADR-0068 §2's rule: the key reaches its keyed
-- expectation only as the ANCHOR of its operation, and the candidate is that operation's
-- expectation of THIS rule's kind - v1's one anchored rule is the payout return, so an
-- INBOUND return line is never key-matched against its own OUTBOUND payout (the transition's
-- A4). A NULL expectation_kind means the key's own expectation decides; two kinds reachable
-- is the AMBIGUOUS_MATCH guard, made a defect signal by payments.scheme_execution_claim.
CREATE TABLE reconciliation.rule (
    rule_set_id        UUID    NOT NULL,
    priority           INT     NOT NULL,
    line_type          TEXT    NOT NULL,
    key_kind           TEXT,
    expectation_kind   TEXT,
    cardinality        TEXT    NOT NULL,
    operation_anchored BOOLEAN NOT NULL DEFAULT false,
    grace_hours        INT     NOT NULL,

    CONSTRAINT rule_pk PRIMARY KEY (rule_set_id, priority),
    CONSTRAINT rule_fk FOREIGN KEY (rule_set_id) REFERENCES reconciliation.rule_set (id),
    CONSTRAINT rule_priority_positive CHECK (priority >= 1),
    CONSTRAINT rule_line_type CHECK (line_type IN (
        'CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PROCESSING_FEE', 'COUNTERPARTY_ADJUSTMENT',
        'CREDIT_IN', 'DEBIT_OUT', 'SCHEME_FEE',
        'PAYOUT_EXECUTED', 'PAYOUT_RETURNED',
        'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE')),
    CONSTRAINT rule_key_kind CHECK (key_kind IS NULL OR key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF', 'ORIGINAL_REF')),
    CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN',
        'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE')),
    CONSTRAINT rule_cardinality CHECK (cardinality IN (
        'ONE_TO_ONE', 'PARTIAL', 'GROUP_BY_VALUE_DATE', 'CORRECTION', 'CHECK')),
    CONSTRAINT rule_grace_counted CHECK (grace_hours >= 0),
    -- An anchored rule reaches its candidate through an operation, so it names the kind it
    -- opens for that operation - anchoring with no kind would anchor to nothing.
    CONSTRAINT rule_anchor_names_its_kind CHECK (
        NOT operation_anchored OR expectation_kind IS NOT NULL)
);

CREATE OR REPLACE FUNCTION reconciliation.rule_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s rules are immutable for every writer (ADR-0068 §8): a change is a NEW version';
END;
$$;

CREATE TRIGGER rule_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.rule
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_is_frozen();

-- Tolerances (ADR-0068 §7, INV-REC-08 at the database rank): THERE IS NO AMOUNT MEMBER.
-- A tolerance on a principal amount cannot be represented - the comparison list holds the
-- two fee comparisons and the date window, and nothing else - so an absorbing tolerance is
-- unstorable, not merely refused by a check someone must remember.
CREATE TABLE reconciliation.tolerance (
    rule_set_id    UUID NOT NULL,
    comparison     TEXT NOT NULL,
    currency       CHAR(3),
    absolute_minor BIGINT,
    days           INT,

    CONSTRAINT tolerance_fk FOREIGN KEY (rule_set_id) REFERENCES reconciliation.rule_set (id),
    CONSTRAINT tolerance_comparison CHECK (comparison IN (
        'PROCESSING_FEE_PER_LINE', 'PROCESSING_FEE_PER_BATCH', 'SETTLEMENT_DATE_DAYS')),
    CONSTRAINT tolerance_once UNIQUE (rule_set_id, comparison, currency),
    -- A date window is days; a fee bound is minor units in a named currency. Exactly one
    -- shape per comparison, and both magnitudes are counted, never negative.
    CONSTRAINT tolerance_shape CHECK (
        (comparison = 'SETTLEMENT_DATE_DAYS'
            AND days IS NOT NULL AND absolute_minor IS NULL AND currency IS NULL)
        OR (comparison <> 'SETTLEMENT_DATE_DAYS'
            AND days IS NULL AND absolute_minor IS NOT NULL AND currency IS NOT NULL)),
    CONSTRAINT tolerance_days_counted CHECK (days IS NULL OR days >= 0),
    CONSTRAINT tolerance_minor_counted CHECK (absolute_minor IS NULL OR absolute_minor >= 0),
    CONSTRAINT tolerance_currency_shape CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

CREATE OR REPLACE FUNCTION reconciliation.tolerance_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s tolerances are immutable for every writer (ADR-0068 §8): a change is a NEW version';
END;
$$;

CREATE TRIGGER tolerance_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.tolerance
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.tolerance_is_frozen();

-- The provider's published terms, pinned per version (ADR-0068 §3, §7): a fee line's
-- expected value is round(rate x gross + fixed) under THIS row, so a FEE_MISMATCH is judged
-- against what was agreed when the version was decided, never against a live config.
CREATE TABLE reconciliation.provider_fee_schedule (
    rule_set_id     UUID          NOT NULL,
    line_type       TEXT          NOT NULL,
    currency        CHAR(3)       NOT NULL,
    rate            NUMERIC(7, 6) NOT NULL,
    fixed_minor     BIGINT        NOT NULL,
    scale           SMALLINT      NOT NULL,
    rounding_policy TEXT          NOT NULL,

    CONSTRAINT provider_fee_schedule_pk PRIMARY KEY (rule_set_id, line_type, currency),
    CONSTRAINT provider_fee_schedule_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT provider_fee_line_type CHECK (line_type IN (
        'PROCESSING_FEE', 'SCHEME_FEE', 'BANK_FEE')),
    CONSTRAINT provider_fee_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT provider_fee_rate_counted CHECK (rate >= 0),
    CONSTRAINT provider_fee_fixed_counted CHECK (fixed_minor >= 0),
    CONSTRAINT provider_fee_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT provider_fee_rounding CHECK (rounding_policy IN ('HALF_UP'))
);

CREATE OR REPLACE FUNCTION reconciliation.provider_fee_schedule_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s fee schedule is immutable for every writer (ADR-0068 §8): a change is a NEW version';
END;
$$;

CREATE TRIGGER provider_fee_schedule_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.provider_fee_schedule
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.provider_fee_schedule_is_frozen();

-- What counts as high value, per currency (owner decision O7; read by ADR-0069's severity).
CREATE TABLE reconciliation.severity_threshold (
    rule_set_id      UUID    NOT NULL,
    currency         CHAR(3) NOT NULL,
    high_value_minor BIGINT  NOT NULL,

    CONSTRAINT severity_threshold_pk PRIMARY KEY (rule_set_id, currency),
    CONSTRAINT severity_threshold_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT severity_threshold_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT severity_threshold_counted CHECK (high_value_minor >= 0)
);

CREATE OR REPLACE FUNCTION reconciliation.severity_threshold_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s severity thresholds are immutable for every writer (ADR-0068 §8): a change is a NEW version';
END;
$$;

CREATE TRIGGER severity_threshold_is_frozen
    BEFORE UPDATE OR DELETE ON reconciliation.severity_threshold
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.severity_threshold_is_frozen();

-- ---------------------------------------------------------------------------------------------
-- The expectation: the clearing journal line's tracked counterpart (ADR-0067 §4).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.expectation (
    id                UUID        NOT NULL,
    kind              TEXT        NOT NULL,
    operation_ref     TEXT        NOT NULL,
    posting_key       TEXT        NOT NULL,
    source_id         UUID        NOT NULL,
    position_purpose  TEXT        NOT NULL,
    ledger_account_id UUID        NOT NULL,
    direction         TEXT        NOT NULL,
    amount_minor      BIGINT      NOT NULL,
    currency          CHAR(3)     NOT NULL,
    scale             SMALLINT    NOT NULL,
    journal_entry_id  UUID,
    posting_date      DATE        NOT NULL,
    settlement_cycle  TEXT,
    expected_by       DATE        NOT NULL,
    rule_set_id       UUID        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'OPEN',
    allocated_minor   BIGINT      NOT NULL DEFAULT 0,
    resolved_minor    BIGINT      NOT NULL DEFAULT 0,
    overdue_since     TIMESTAMPTZ,
    opened_at         TIMESTAMPTZ NOT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL,
    correlation_id    TEXT        NOT NULL,

    CONSTRAINT expectation_pk PRIMARY KEY (id),
    CONSTRAINT expectation_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT expectation_kind CHECK (kind IN (
        'CARD_CAPTURE', 'CARD_REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'DISPUTE_FEE',
        'PUSH_PAY_IN', 'UNMATCHED_CONFIRMATION', 'PUSH_WITHDRAWAL', 'PUSH_RETURN',
        'MERCHANT_PAYOUT', 'PAYOUT_RETURN', 'REMITTANCE')),
    -- One expectation per operation and kind, and one per clearing journal line: the two
    -- identity arbiters every writer converges on ON CONFLICT DO NOTHING (ADR-0067 §4) -
    -- they hold even with an applier's acting guard removed.
    CONSTRAINT expectation_operation_once UNIQUE (kind, operation_ref),
    CONSTRAINT expectation_line_once UNIQUE (journal_entry_id, ledger_account_id),
    -- A REMITTANCE records no posting of ours; every other kind records exactly its
    -- completion's entry.
    CONSTRAINT expectation_remittance_has_no_entry CHECK (
        (kind = 'REMITTANCE') = (journal_entry_id IS NULL)),
    CONSTRAINT expectation_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT expectation_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT expectation_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT expectation_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT expectation_status CHECK (status IN (
        'OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'RESOLVED_BY_ADJUSTMENT')),
    -- Value is conserved: what is allocated plus what a resolution took never exceeds the
    -- amount (the deferred sum trigger tying allocated_minor to the allocation rows arrives
    -- with the allocation table, P8-TSK-011).
    CONSTRAINT expectation_value_conserved CHECK (
        allocated_minor >= 0 AND resolved_minor >= 0
            AND allocated_minor + resolved_minor <= amount_minor)
);

CREATE INDEX expectation_by_source_and_status
    ON reconciliation.expectation (source_id, status);
CREATE INDEX expectation_by_expected_by ON reconciliation.expectation (expected_by);

COMMENT ON TABLE reconciliation.expectation IS
    'The tracked counterpart of one clearing journal line (ADR-0067): copies of immutable facts taken at completion - amount, entry and account equal the line, the deciding rule set pinned (INV-HIST-04) - opened in the completing transaction and discharged only by allocation, resolution or repudiation.';
COMMENT ON COLUMN reconciliation.expectation.settlement_cycle IS
    'The cycle the completion announced, when it announced one: a matching attribute and a report dimension, never a key (ADR-0067 §5, the transition''s A5).';
COMMENT ON COLUMN reconciliation.expectation.overdue_since IS
    'A one-way NULL -> value fact set by the ageing sweep (P8-TSK-013), not a state: an overdue expectation still settles (INV-SET-03).';

-- The machine (SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md §5.5), for every writer: the
-- birth statement is frozen, the edges are exactly ExpectationStatus.permittedTransitions()
-- - the repudiation's reopening included - and overdue_since moves NULL -> value once.
CREATE OR REPLACE FUNCTION reconciliation.expectation_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.operation_ref IS DISTINCT FROM OLD.operation_ref
            OR NEW.posting_key IS DISTINCT FROM OLD.posting_key
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.position_purpose IS DISTINCT FROM OLD.position_purpose
            OR NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id
            OR NEW.direction IS DISTINCT FROM OLD.direction
            OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
            OR NEW.posting_date IS DISTINCT FROM OLD.posting_date
            OR NEW.settlement_cycle IS DISTINCT FROM OLD.settlement_cycle
            OR NEW.expected_by IS DISTINCT FROM OLD.expected_by
            OR NEW.rule_set_id IS DISTINCT FROM OLD.rule_set_id
            OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'an expectation''s birth statement is frozen (P8-TSK-004, ADR-0067 §4): copies of immutable facts taken at completion';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT ((OLD.status = 'OPEN' AND NEW.status IN
                        ('PARTIALLY_SETTLED', 'SETTLED', 'RESOLVED_BY_ADJUSTMENT'))
                OR (OLD.status = 'PARTIALLY_SETTLED' AND NEW.status IN
                        ('SETTLED', 'RESOLVED_BY_ADJUSTMENT', 'OPEN'))
                OR (OLD.status = 'SETTLED' AND NEW.status IN
                        ('OPEN', 'PARTIALLY_SETTLED'))) THEN
            RAISE EXCEPTION 'invalid expectation transition % -> % (SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md §5.5)',
                OLD.status, NEW.status;
        END IF;
    END IF;
    IF OLD.overdue_since IS NOT NULL
            AND NEW.overdue_since IS DISTINCT FROM OLD.overdue_since THEN
        RAISE EXCEPTION 'overdue_since is a one-way fact (P8-TSK-013): set once, never moved';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER expectation_permits_only_machine_edges
    BEFORE UPDATE ON reconciliation.expectation
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION reconciliation.expectation_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an expectation is never deleted, for any writer (INV-REC-01): it is discharged by allocation, resolution or repudiation';
END;
$$;

CREATE TRIGGER expectation_is_never_deleted
    BEFORE DELETE ON reconciliation.expectation
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_is_never_deleted();

-- ---------------------------------------------------------------------------------------------
-- The history: birth is the first entry, and a collision is a counted fact, never a failure.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.expectation_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    expectation_id UUID,
    event_type     TEXT        NOT NULL,
    detail         TEXT,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT expectation_event_pk PRIMARY KEY (seq),
    CONSTRAINT expectation_event_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    -- OPENED and KEY_COLLISION are P8-TSK-004's writers; later producers (allocation,
    -- ageing, resolution, repudiation) regenerate this list with the facts they bring.
    CONSTRAINT expectation_event_type CHECK (event_type IN ('OPENED', 'KEY_COLLISION')),
    -- An alias collision has no owning expectation - the alias never got one; every other
    -- event names its row.
    CONSTRAINT expectation_event_owner CHECK (
        expectation_id IS NOT NULL OR event_type = 'KEY_COLLISION'),
    CONSTRAINT expectation_event_detail_bounded CHECK (char_length(detail) <= 500)
);

CREATE INDEX expectation_event_by_expectation
    ON reconciliation.expectation_event (expectation_id, seq);
CREATE INDEX expectation_event_by_type ON reconciliation.expectation_event (event_type);

CREATE OR REPLACE FUNCTION reconciliation.expectation_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an expectation''s history is append-only for every writer (INV-REC-01)';
END;
$$;

CREATE TRIGGER expectation_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.expectation_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_event_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The key index and the alias: how a counterparty's reference reaches an expectation.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.expectation_key (
    source_id      UUID NOT NULL,
    key_kind       TEXT NOT NULL,
    key_value      TEXT NOT NULL,
    expectation_id UUID NOT NULL,

    -- Scoped per source, NO kind exempt (the transition's A5): counterparty tokens never
    -- collide across counterparties, and a collision within one source is recorded, skipped
    -- and raised as DUPLICATE_INTERNAL (P8-TSK-010) - never a failed payment.
    CONSTRAINT expectation_key_once UNIQUE (source_id, key_kind, key_value),
    CONSTRAINT expectation_key_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT expectation_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF')),
    CONSTRAINT expectation_key_value_bounded CHECK (char_length(key_value) BETWEEN 1 AND 200)
);

CREATE INDEX expectation_key_by_expectation
    ON reconciliation.expectation_key (expectation_id);

CREATE OR REPLACE FUNCTION reconciliation.expectation_key_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the key index is append-only for every writer (INV-REC-01): the first writer won, and the record of that is the point';
END;
$$;

CREATE TRIGGER expectation_key_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.expectation_key
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_key_is_append_only();

-- A reference that arrives apart from its expectation - the ARN - resolves to an anchor key
-- in a local, immutable two-hop join (alias -> anchor -> expectation), so the order never
-- matters (ADR-0067 §5).
CREATE TABLE reconciliation.reference_alias (
    source_id      UUID        NOT NULL,
    key_kind       TEXT        NOT NULL,
    key_value      TEXT        NOT NULL,
    anchor_kind    TEXT        NOT NULL,
    anchor_value   TEXT        NOT NULL,
    registered_at  TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT reference_alias_once UNIQUE (source_id, key_kind, key_value),
    CONSTRAINT reference_alias_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF')),
    CONSTRAINT reference_alias_anchor_kind CHECK (anchor_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF')),
    CONSTRAINT reference_alias_value_bounded CHECK (
        char_length(key_value) BETWEEN 1 AND 200
            AND char_length(anchor_value) BETWEEN 1 AND 200)
);

CREATE OR REPLACE FUNCTION reconciliation.reference_alias_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the alias index is append-only for every writer (INV-REC-01): the first writer won, and the record of that is the point';
END;
$$;

CREATE TRIGGER reference_alias_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.reference_alias
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.reference_alias_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Rule set version 1, seeded ACTIVE per source - this migration is its provenance (the
-- payments V013 routing precedent; owner decisions O5 and O7; grace and fee values fixed at
-- P8-TSK-004's design). The source UUIDs are settlement V002's seeds, copied - identity is
-- frozen there by trigger, and there is deliberately no cross-schema foreign key.
-- ---------------------------------------------------------------------------------------------
INSERT INTO reconciliation.rule_set
    (id, source_id, version, status, funding_lag_days, gain_min_age_days, effective_from,
     proposed_by, decided_by, reason, created_at, correlation_id) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', '01a0e2bc-8200-7001-8000-000000000001', 1,
     'ACTIVE', 2, 90, DATE '2026-09-29', 'migration:V002', 'migration:V002',
     'Rule set v1 for the simulated PSP settlement report, seeded by P8-TSK-004 (ADR-0068 §8; the four-eyes proposal flow arrives with P8-TSK-022)',
     TIMESTAMPTZ '2026-09-29T12:00:00Z', 'p8-tsk-004-migration'),
    ('01a0e2bd-8300-7002-8000-000000000002', '01a0e2bc-8200-7002-8000-000000000002', 1,
     'ACTIVE', 2, 90, DATE '2026-09-29', 'migration:V002', 'migration:V002',
     'Rule set v1 for the simulated scheme cycle report, seeded by P8-TSK-004 (ADR-0068 §8)',
     TIMESTAMPTZ '2026-09-29T12:00:00Z', 'p8-tsk-004-migration'),
    ('01a0e2bd-8300-7003-8000-000000000003', '01a0e2bc-8200-7003-8000-000000000003', 1,
     'ACTIVE', 2, 90, DATE '2026-09-29', 'migration:V002', 'migration:V002',
     'Rule set v1 for the simulated payout settlement report, seeded by P8-TSK-004 (ADR-0068 §8; the PAYOUT_RETURNED rule is operation-anchored, the transition''s A4)',
     TIMESTAMPTZ '2026-09-29T12:00:00Z', 'p8-tsk-004-migration'),
    ('01a0e2bd-8300-7004-8000-000000000004', '01a0e2bc-8200-7004-8000-000000000004', 1,
     'ACTIVE', 2, 90, DATE '2026-09-29', 'migration:V002', 'migration:V002',
     'Rule set v1 for the simulated bank statement, seeded by P8-TSK-004 (ADR-0068 §8; remittances date from funding_lag_days)',
     TIMESTAMPTZ '2026-09-29T12:00:00Z', 'p8-tsk-004-migration');

-- The dating lags (PHASE_8_PLAN §12: card 3, refund and dispute 3, instant 1, payout 2).
INSERT INTO reconciliation.rule_set_lag (rule_set_id, expectation_kind, lag_days) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', 'CARD_CAPTURE', 3),
    ('01a0e2bd-8300-7001-8000-000000000001', 'CARD_REFUND', 3),
    ('01a0e2bd-8300-7001-8000-000000000001', 'CHARGEBACK', 3),
    ('01a0e2bd-8300-7001-8000-000000000001', 'CHARGEBACK_REVERSAL', 3),
    ('01a0e2bd-8300-7001-8000-000000000001', 'DISPUTE_FEE', 3),
    ('01a0e2bd-8300-7002-8000-000000000002', 'PUSH_PAY_IN', 1),
    ('01a0e2bd-8300-7002-8000-000000000002', 'UNMATCHED_CONFIRMATION', 1),
    ('01a0e2bd-8300-7002-8000-000000000002', 'PUSH_WITHDRAWAL', 1),
    ('01a0e2bd-8300-7002-8000-000000000002', 'PUSH_RETURN', 1),
    ('01a0e2bd-8300-7003-8000-000000000003', 'MERCHANT_PAYOUT', 2),
    ('01a0e2bd-8300-7003-8000-000000000003', 'PAYOUT_RETURN', 2);

-- The matching rules, in priority order (ADR-0068 §2's table; grace 48h, the payout return
-- 72h - it rides the provider's slower return cycle and P8-TSK-019's worker gets the extra
-- day before the grace leg parks). The PAYOUT_RETURNED rows are OPERATION-ANCHORED: the
-- payout's keys reach only the operation, never its OUTBOUND expectation as a candidate.
INSERT INTO reconciliation.rule
    (rule_set_id, priority, line_type, key_kind, expectation_kind, cardinality,
     operation_anchored, grace_hours) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 2, 'CAPTURE', 'ACQUIRER_REF', 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 3, 'REFUND', 'PSP_REFUND_REF', 'CARD_REFUND', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 4, 'REFUND', 'OUR_REF', 'CARD_REFUND', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 5, 'CHARGEBACK', 'DISPUTE_CB_REF', 'CHARGEBACK', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 6, 'CHARGEBACK_REVERSAL', 'DISPUTE_REV_REF', 'CHARGEBACK_REVERSAL', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 7, 'DISPUTE_FEE', 'DISPUTE_FEE_REF', 'DISPUTE_FEE', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 8, 'PROCESSING_FEE', 'ORIGINAL_REF', NULL, 'CHECK', false, 48),
    ('01a0e2bd-8300-7001-8000-000000000001', 9, 'COUNTERPARTY_ADJUSTMENT', 'ORIGINAL_REF', NULL, 'CORRECTION', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 1, 'CREDIT_IN', 'SCHEME_REF', NULL, 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 2, 'CREDIT_IN', 'END_TO_END_REF', NULL, 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 3, 'DEBIT_OUT', 'SCHEME_REF', NULL, 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 4, 'DEBIT_OUT', 'END_TO_END_REF', NULL, 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 5, 'DEBIT_OUT', 'OUR_REF', NULL, 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7002-8000-000000000002', 6, 'SCHEME_FEE', NULL, NULL, 'CHECK', false, 48),
    ('01a0e2bd-8300-7003-8000-000000000003', 1, 'PAYOUT_EXECUTED', 'PAYOUT_PROVIDER_REF', 'MERCHANT_PAYOUT', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7003-8000-000000000003', 2, 'PAYOUT_EXECUTED', 'OUR_REF', 'MERCHANT_PAYOUT', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7003-8000-000000000003', 3, 'PAYOUT_RETURNED', 'PAYOUT_PROVIDER_REF', 'PAYOUT_RETURN', 'ONE_TO_ONE', true, 72),
    ('01a0e2bd-8300-7003-8000-000000000003', 4, 'PAYOUT_RETURNED', 'OUR_REF', 'PAYOUT_RETURN', 'ONE_TO_ONE', true, 72),
    ('01a0e2bd-8300-7004-8000-000000000004', 1, 'BANK_CREDIT', 'REMITTANCE_REF', 'REMITTANCE', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7004-8000-000000000004', 2, 'BANK_CREDIT', NULL, 'REMITTANCE', 'GROUP_BY_VALUE_DATE', false, 48),
    ('01a0e2bd-8300-7004-8000-000000000004', 3, 'BANK_DEBIT', 'REMITTANCE_REF', 'REMITTANCE', 'ONE_TO_ONE', false, 48),
    ('01a0e2bd-8300-7004-8000-000000000004', 4, 'BANK_DEBIT', NULL, 'REMITTANCE', 'GROUP_BY_VALUE_DATE', false, 48),
    ('01a0e2bd-8300-7004-8000-000000000004', 5, 'BANK_FEE', NULL, NULL, 'CHECK', false, 48);

-- Tolerances (ADR-0068 §7): the date window everywhere; the fee bounds only where fees are
-- reported (the PSP). Values fixed at P8-TSK-004's design: 2 days, 0.02 per line, 0.50 per
-- batch. NO amount tolerance exists - it cannot be represented (INV-REC-08).
INSERT INTO reconciliation.tolerance
    (rule_set_id, comparison, currency, absolute_minor, days) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),
    ('01a0e2bd-8300-7002-8000-000000000002', 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),
    ('01a0e2bd-8300-7003-8000-000000000003', 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),
    ('01a0e2bd-8300-7004-8000-000000000004', 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_LINE', 'EUR', 2, NULL),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_LINE', 'GBP', 2, NULL),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_LINE', 'USD', 2, NULL),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_BATCH', 'EUR', 50, NULL),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_BATCH', 'GBP', 50, NULL),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE_PER_BATCH', 'USD', 50, NULL);

-- The simulated providers' published terms (values fixed at P8-TSK-004's design; the
-- simulated report formats of P8-TSK-008 produce fees under exactly these terms).
INSERT INTO reconciliation.provider_fee_schedule
    (rule_set_id, line_type, currency, rate, fixed_minor, scale, rounding_policy) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE', 'EUR', 0.015000, 25, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE', 'GBP', 0.015000, 25, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7001-8000-000000000001', 'PROCESSING_FEE', 'USD', 0.015000, 25, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7002-8000-000000000002', 'SCHEME_FEE', 'EUR', 0.000000, 10, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7002-8000-000000000002', 'SCHEME_FEE', 'GBP', 0.000000, 10, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7002-8000-000000000002', 'SCHEME_FEE', 'USD', 0.000000, 10, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7004-8000-000000000004', 'BANK_FEE', 'EUR', 0.000000, 50, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7004-8000-000000000004', 'BANK_FEE', 'GBP', 0.000000, 50, 2, 'HALF_UP'),
    ('01a0e2bd-8300-7004-8000-000000000004', 'BANK_FEE', 'USD', 0.000000, 50, 2, 'HALF_UP');

-- What counts as high value (owner decision O7: 1,000.00 per currency, every source).
INSERT INTO reconciliation.severity_threshold
    (rule_set_id, currency, high_value_minor) VALUES
    ('01a0e2bd-8300-7001-8000-000000000001', 'EUR', 100000),
    ('01a0e2bd-8300-7001-8000-000000000001', 'GBP', 100000),
    ('01a0e2bd-8300-7001-8000-000000000001', 'USD', 100000),
    ('01a0e2bd-8300-7002-8000-000000000002', 'EUR', 100000),
    ('01a0e2bd-8300-7002-8000-000000000002', 'GBP', 100000),
    ('01a0e2bd-8300-7002-8000-000000000002', 'USD', 100000),
    ('01a0e2bd-8300-7003-8000-000000000003', 'EUR', 100000),
    ('01a0e2bd-8300-7003-8000-000000000003', 'GBP', 100000),
    ('01a0e2bd-8300-7003-8000-000000000003', 'USD', 100000),
    ('01a0e2bd-8300-7004-8000-000000000004', 'EUR', 100000),
    ('01a0e2bd-8300-7004-8000-000000000004', 'GBP', 100000),
    ('01a0e2bd-8300-7004-8000-000000000004', 'USD', 100000);

-- ---------------------------------------------------------------------------------------------
-- Least privilege (INV-REC-01, the break-immutability floor's shape): SELECT and INSERT,
-- the expectation's UPDATE narrowed to what its machine moves, and NO DELETE for the
-- application role anywhere in this schema.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.rule_set TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.rule_set_lag TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.rule TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.tolerance TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.provider_fee_schedule TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.severity_threshold TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.expectation TO finapp_app;
GRANT UPDATE (allocated_minor, resolved_minor, status, overdue_since, status_changed_at)
    ON reconciliation.expectation TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.expectation_event TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.expectation_key TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.reference_alias TO finapp_app;
