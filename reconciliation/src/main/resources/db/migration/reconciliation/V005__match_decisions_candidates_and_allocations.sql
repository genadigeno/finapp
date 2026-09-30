-- P8-TSK-011: match decisions, candidate snapshots and allocations (ADR-0068; INV-REC-04,
-- INV-REC-07).
--
-- Every evaluation stores its pinned rule set and a snapshot of every candidate it saw, so
-- a match is explainable from stored rows alone and replays exactly. Decisions, candidates
-- and allocations are APPEND-ONLY for every writer: no UPDATE or DELETE grant, and refusing
-- triggers beneath the absent grants. The deferred SUM triggers make over-allocation
-- unrepresentable for any writer, raw SQL included: the net of allocations equals the
-- denormalised allocated_minor on the item and on the expectation at every commit.

CREATE TABLE reconciliation.match_decision (
    id                    UUID        NOT NULL,
    external_item_id      UUID        NOT NULL,
    run_id                UUID,
    origin                TEXT        NOT NULL,
    rule_set_id           UUID        NOT NULL,
    rule_priority         INT,
    strategy              TEXT,
    matched_key_kind      TEXT,
    outcome               TEXT        NOT NULL,
    claimant_rank         INT,
    claimant_count        INT,
    date_deviation_days   INT,
    timing_tolerance_days INT,
    fee_expected_minor    BIGINT,
    fee_reported_minor    BIGINT,
    fee_tolerance_minor   BIGINT,
    decided_by            TEXT        NOT NULL,
    decided_by_type       TEXT        NOT NULL,
    decided_at            TIMESTAMPTZ NOT NULL,
    decided_on            DATE        NOT NULL,
    correlation_id        TEXT        NOT NULL,

    CONSTRAINT match_decision_pk PRIMARY KEY (id),
    CONSTRAINT match_decision_item_fk FOREIGN KEY (external_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT match_decision_run_fk FOREIGN KEY (run_id)
        REFERENCES reconciliation.reconciliation_batch (id),
    CONSTRAINT match_decision_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    -- DecisionOrigin.sqlValueList - RUN produced here; the others with their legs.
    CONSTRAINT match_decision_origin CHECK (origin IN (
        'RUN', 'REMATCH', 'REPROCESS', 'MANUAL')),
    -- DecisionOutcome.sqlValueList - stated whole; CHECKED and OFFSET are P8-TSK-012's.
    CONSTRAINT match_decision_outcome CHECK (outcome IN (
        'MATCHED', 'CHECKED', 'OFFSET', 'UNMATCHED', 'PARKED', 'ERRORED')),
    -- Cardinality's closed vocabulary, where a rule fired.
    CONSTRAINT match_decision_strategy CHECK (strategy IS NULL OR strategy IN (
        'ONE_TO_ONE', 'PARTIAL', 'GROUP_BY_VALUE_DATE', 'CORRECTION', 'CHECK')),
    -- KeyKind.sqlValueList - the expectation-side vocabulary the rules speak.
    CONSTRAINT match_decision_key_kind CHECK (matched_key_kind IS NULL
        OR matched_key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF')),
    CONSTRAINT match_decision_run_for_run_origin CHECK (
        origin <> 'RUN' OR run_id IS NOT NULL),
    CONSTRAINT match_decision_priority_positive CHECK (
        rule_priority IS NULL OR rule_priority >= 1),
    CONSTRAINT match_decision_rank_positive CHECK (
        claimant_rank IS NULL OR claimant_rank >= 1),
    CONSTRAINT match_decision_count_counted CHECK (
        claimant_count IS NULL OR claimant_count >= 0),
    CONSTRAINT match_decision_timing_counted CHECK (
        timing_tolerance_days IS NULL OR timing_tolerance_days >= 0)
);

CREATE INDEX match_decision_by_item
    ON reconciliation.match_decision (external_item_id, decided_at);
CREATE INDEX match_decision_by_run ON reconciliation.match_decision (run_id);

COMMENT ON TABLE reconciliation.match_decision IS
    'One evaluation''s stored verdict (ADR-0068 section 5): the pinned rule set, the rule, the key, the applied tolerance and the outcome - explainable from stored rows alone.';

CREATE OR REPLACE FUNCTION reconciliation.match_decision_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a match decision is history, append-only for every writer (INV-REC-04, INV-REC-07)';
END;
$$;

CREATE TRIGGER match_decision_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.match_decision
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.match_decision_is_append_only();

-- The decision-subject foreign key V004 deliberately left off: decisions exist now.
ALTER TABLE reconciliation.break
    ADD CONSTRAINT break_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id);

-- ---------------------------------------------------------------------------------------------
-- The candidate snapshot: what the decision SAW, kept because the live rows move on.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.match_candidate (
    decision_id            UUID        NOT NULL,
    expectation_id         UUID        NOT NULL,
    key_kind               TEXT        NOT NULL,
    amount_minor           BIGINT      NOT NULL,
    currency               CHAR(3)     NOT NULL,
    scale                  SMALLINT    NOT NULL,
    direction              TEXT        NOT NULL,
    remainder_before_minor BIGINT      NOT NULL,
    opened_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT match_candidate_pk PRIMARY KEY (decision_id, expectation_id),
    CONSTRAINT match_candidate_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT match_candidate_expectation_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT match_candidate_key_kind CHECK (key_kind IN (
        'PSP_CAPTURE_REF', 'PSP_REFUND_REF', 'OUR_REF', 'CARD_ATTEMPT', 'ACQUIRER_REF',
        'DISPUTE_CB_REF', 'DISPUTE_REV_REF', 'DISPUTE_FEE_REF', 'SCHEME_REF',
        'END_TO_END_REF', 'PAYOUT_PROVIDER_REF', 'REMITTANCE_REF')),
    CONSTRAINT match_candidate_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT match_candidate_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT match_candidate_scale_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT match_candidate_remainder_counted CHECK (remainder_before_minor >= 0)
);

COMMENT ON TABLE reconciliation.match_candidate IS
    'The candidates one decision considered, snapshotted (ADR-0068 section 5): amounts RESTRICTED-FINANCIAL; replay reads these rows, never the live register.';

CREATE OR REPLACE FUNCTION reconciliation.match_candidate_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a candidate snapshot is history, append-only for every writer (INV-REC-04)';
END;
$$;

CREATE TRIGGER match_candidate_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.match_candidate
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.match_candidate_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- Allocations: append-only; only a batch repudiation adds counter-allocations (V009).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reconciliation.allocation (
    id                      UUID        NOT NULL,
    decision_id             UUID        NOT NULL,
    external_item_id        UUID        NOT NULL,
    expectation_id          UUID        NOT NULL,
    amount_minor            BIGINT      NOT NULL,
    currency                CHAR(3)     NOT NULL,
    scale                   SMALLINT    NOT NULL,
    reverses_allocation_id  UUID,
    created_at              TIMESTAMPTZ NOT NULL,
    correlation_id          TEXT        NOT NULL,

    CONSTRAINT allocation_pk PRIMARY KEY (id),
    CONSTRAINT allocation_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT allocation_item_fk FOREIGN KEY (external_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT allocation_expectation_fk FOREIGN KEY (expectation_id)
        REFERENCES reconciliation.expectation (id),
    CONSTRAINT allocation_reverses_fk FOREIGN KEY (reverses_allocation_id)
        REFERENCES reconciliation.allocation (id),
    CONSTRAINT allocation_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT allocation_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT allocation_scale_bounded CHECK (scale BETWEEN 0 AND 9)
);

-- At most one POSITIVE allocation per (item, expectation), for any writer under any race
-- (INV-REC-07): a repudiation's counter-allocation and the genuine re-allocation that may
-- follow it live outside the predicate.
CREATE UNIQUE INDEX allocation_pair_once
    ON reconciliation.allocation (external_item_id, expectation_id)
    WHERE reverses_allocation_id IS NULL;

CREATE INDEX allocation_by_expectation ON reconciliation.allocation (expectation_id);

CREATE OR REPLACE FUNCTION reconciliation.allocation_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'an allocation is never edited: repudiation adds counter-allocations (INV-REV-01''s shape, INV-REC-07)';
END;
$$;

CREATE TRIGGER allocation_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.allocation
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.allocation_is_append_only();

-- ---------------------------------------------------------------------------------------------
-- The deferred SUM discipline (INV-REC-07): at every commit, the net of allocations equals
-- allocated_minor on the item and on the expectation. Fired from BOTH sides - an inserted
-- allocation, and a raw update of either denormalised column - so a writer can neither
-- allocate without recording nor record without allocating.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reconciliation.item_allocations_reconcile(item UUID)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    net      BIGINT;
    recorded BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN a.reverses_allocation_id IS NULL THEN a.amount_minor
                             ELSE -a.amount_minor END), 0)
        INTO net FROM reconciliation.allocation a WHERE a.external_item_id = item;
    SELECT allocated_minor INTO recorded
        FROM reconciliation.external_item WHERE id = item;
    IF net IS DISTINCT FROM recorded THEN
        RAISE EXCEPTION 'the item''s allocations (%) must equal its allocated_minor (%) at commit (INV-REC-07)',
            net, recorded;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION reconciliation.expectation_allocations_reconcile(target UUID)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    net      BIGINT;
    recorded BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN a.reverses_allocation_id IS NULL THEN a.amount_minor
                             ELSE -a.amount_minor END), 0)
        INTO net FROM reconciliation.allocation a WHERE a.expectation_id = target;
    SELECT allocated_minor INTO recorded
        FROM reconciliation.expectation WHERE id = target;
    IF net IS DISTINCT FROM recorded THEN
        RAISE EXCEPTION 'the expectation''s allocations (%) must equal its allocated_minor (%) at commit (INV-REC-07)',
            net, recorded;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION reconciliation.allocation_sums_reconcile()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM reconciliation.item_allocations_reconcile(NEW.external_item_id);
    PERFORM reconciliation.expectation_allocations_reconcile(NEW.expectation_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER allocation_sums_reconcile
    AFTER INSERT ON reconciliation.allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.allocation_sums_reconcile();

CREATE OR REPLACE FUNCTION reconciliation.item_allocated_column_reconciles()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM reconciliation.item_allocations_reconcile(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_allocated_column_reconciles
    AFTER UPDATE OF allocated_minor ON reconciliation.external_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.item_allocated_column_reconciles();

CREATE OR REPLACE FUNCTION reconciliation.expectation_allocated_column_reconciles()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM reconciliation.expectation_allocations_reconcile(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER expectation_allocated_column_reconciles
    AFTER UPDATE OF allocated_minor ON reconciliation.expectation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.expectation_allocated_column_reconciles();

-- A run never completes over an undisposed item (ADR-0068 section 6), for every writer.
CREATE OR REPLACE FUNCTION reconciliation.run_completes_only_disposed()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'COMPLETED'
            AND EXISTS (SELECT 1 FROM reconciliation.external_item
                        WHERE run_id = NEW.id AND status = 'PENDING') THEN
        RAISE EXCEPTION 'a run never completes with an item PENDING (ADR-0068 section 6)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER run_completes_only_disposed
    AFTER UPDATE OF status ON reconciliation.reconciliation_batch
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.run_completes_only_disposed();

-- The expectation history gains the allocation's own event - V002's list regenerated with
-- the producer this migration brings, exactly as its comment said it would be.
ALTER TABLE reconciliation.expectation_event
    DROP CONSTRAINT expectation_event_type;
ALTER TABLE reconciliation.expectation_event
    ADD CONSTRAINT expectation_event_type CHECK (event_type IN (
        'OPENED', 'KEY_COLLISION', 'ALLOCATED'));

-- ---------------------------------------------------------------------------------------------
-- Grants: SELECT and INSERT only - decisions, candidates and allocations are history the
-- moment they exist, for every writer.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON reconciliation.match_decision TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.match_candidate TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.allocation TO finapp_app;
