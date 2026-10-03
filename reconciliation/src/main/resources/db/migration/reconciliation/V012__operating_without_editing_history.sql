-- P8-TSK-022: operating reconciliation without editing history (ADR-0068 sections 8 and 9;
-- INV-HIST-04, INV-REC-04, INV-AUD-04; protected INV-REC-07, INV-REC-08).
--
-- WHY MORE THAN run_replay
--   The P8-TSK-022 backlog entry claimed one reconciliation table, run_replay. The task's design
--   found three further facts the schema did not yet hold:
--
--   1. THE RULE SET HAD NO MACHINE. V002 admitted ACTIVE alone, made decided_by NOT NULL, and froze
--      every UPDATE - so not even ACTIVE -> RETIRED was possible - while the member tables froze
--      UPDATE and DELETE but still accepted an INSERT into an ACTIVE version (V010 did exactly that,
--      a recorded deviation). This migration gives the version its machine (PROPOSED -> ACTIVE |
--      REJECTED, ACTIVE -> RETIRED), the four-eyes rule at this rank, a history table, and closes
--      the member tables to every insert except the proposal's own: a member row joins only a
--      PROPOSED parent born in the SAME transaction, so an approver approves exactly what was
--      proposed - nothing can be appended after review.
--
--   2. A DECISION COULD NOT BE REPLAYED EXACTLY. ADR-0068 says a decision is a pure function of its
--      stored snapshot and pinned rule set; the snapshot lacked the engine's verdict (PARKED covers
--      five verdicts), the fingerprint input, the value the decision judged (a parked item's
--      remainder, not its amount), the value-date group's membership input, the fee's gross, and a
--      correction's parked originals. Six columns and one snapshot table complete it; every writer
--      fills them from now on, held by an insert trigger.
--
--   3. A DIVERGED REPLAY'S BREAK HAD NO CONVERGENCE. Its subject is the first divergent decision,
--      and the decision subject had no one-open unique (ADR-0069 section 4).
--
--   tolerance_once also gains NULLS NOT DISTINCT: a date window's currency is NULL, so V002's
--   unique let two SETTLEMENT_DATE_DAYS rows coexist in one version.

-- ===============================================================================================
-- 1. The rule set's machine.
-- ===============================================================================================
DROP TRIGGER rule_set_is_frozen ON reconciliation.rule_set;
DROP FUNCTION reconciliation.rule_set_is_frozen();

ALTER TABLE reconciliation.rule_set
    ALTER COLUMN decided_by DROP NOT NULL,
    ADD COLUMN decided_at TIMESTAMPTZ;

-- The V002 seed was born ACTIVE at its creation: its decision instant IS its creation instant.
UPDATE reconciliation.rule_set SET decided_at = created_at WHERE decided_at IS NULL;

ALTER TABLE reconciliation.rule_set
    DROP CONSTRAINT rule_set_status,
    ADD CONSTRAINT rule_set_status CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    -- Undecided exactly while PROPOSED; a decision names its person and its instant together.
    ADD CONSTRAINT rule_set_decided_iff_not_proposed CHECK ((status = 'PROPOSED') = (decided_by IS NULL)),
    ADD CONSTRAINT rule_set_decision_is_one_fact CHECK ((decided_by IS NULL) = (decided_at IS NULL)),
    -- Four eyes at this rank (INV-AUD-04, ADR-0068 section 8): an ACTIVE or RETIRED version was
    -- activated by someone other than its proposer. The one exemption is V002's seed, whose
    -- provenance is the migration on both sides; no new row can claim it (the insert trigger).
    -- A rejection may be the proposer's own: withdrawing a proposal changes no policy.
    ADD CONSTRAINT rule_set_activation_is_four_eyes CHECK (
        status IN ('PROPOSED', 'REJECTED') OR decided_by <> proposed_by
            OR proposed_by = 'migration:V002');

-- One pending proposal per source, for every writer: two would make "which one activates" an
-- ordering question, and a later activation could retire the earlier one's successor.
CREATE UNIQUE INDEX rule_set_one_proposed
    ON reconciliation.rule_set (source_id)
    WHERE status = 'PROPOSED';

CREATE OR REPLACE FUNCTION reconciliation.rule_set_moves_only_on_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a rule set version is never deleted (ADR-0068 section 8, INV-HIST-04)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'a rule set version is born PROPOSED and undecided (ADR-0068 section 8): activation is a second person''s act';
        END IF;
        IF NEW.proposed_by LIKE 'migration:%' THEN
            RAISE EXCEPTION 'the migration provenance is the V002 seed''s alone: a new version names the person who proposed it';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.source_id IS DISTINCT FROM OLD.source_id
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.funding_lag_days IS DISTINCT FROM OLD.funding_lag_days
            OR NEW.gain_min_age_days IS DISTINCT FROM OLD.gain_min_age_days
            OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a rule set version''s content is immutable from PROPOSED for every writer (INV-HIST-04): a change is a NEW version';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'not a rule set edge: % -> % (ADR-0068 section 8)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'ACTIVE'
            AND (NEW.decided_by IS DISTINCT FROM OLD.decided_by
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
        RAISE EXCEPTION 'a retirement keeps the activation''s decision: who retired it is its successor''s activation, in the history';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rule_set_moves_only_on_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON reconciliation.rule_set
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_moves_only_on_machine_edges();

-- Exactly one ACTIVE version per source, for every writer: rule_set_one_active holds "at most",
-- and this holds "at least" - a retirement commits only beside its successor's activation (the
-- rule-set agent's find: a raw ACTIVE -> RETIRED with no successor would leave every opener of the
-- source without a version to date by). Deferred to commit, because the activation retires the
-- predecessor FIRST (the one-active unique) and activates its successor second.
CREATE OR REPLACE FUNCTION reconciliation.rule_set_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND OLD.status = 'ACTIVE'
            AND NOT EXISTS (SELECT 1 FROM reconciliation.rule_set s
                            WHERE s.source_id = NEW.source_id AND s.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'a source always has exactly one active rule set version (ADR-0068 section 8): a version retires only in its successor''s activation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER rule_set_retires_only_beside_its_successor
    AFTER UPDATE ON reconciliation.rule_set
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_retires_only_beside_its_successor();

-- The members join their proposal in the proposal's own transaction, or not at all. xmin is the
-- inserting transaction's 32-bit id; pg_current_xact_id() is the epoch-extended one, so the
-- comparison is modulo 2^32. The proposal is written at the top level of its transaction (no
-- savepoint), so its xmin IS the transaction's id; a writer that wrapped it in a savepoint would
-- be refused - fail-closed. A row whose key ALREADY stands adds nothing to any version: it is
-- left to its unique (a plain insert is refused there, a re-seed's ON CONFLICT DO NOTHING
-- converges), so only NEW content is judged here.
CREATE OR REPLACE FUNCTION reconciliation.rule_set_member_joins_its_proposal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'rule_set_lag' THEN
        IF EXISTS (SELECT 1 FROM reconciliation.rule_set_lag m
                   WHERE m.rule_set_id = NEW.rule_set_id
                     AND m.expectation_kind = NEW.expectation_kind) THEN
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME = 'rule' THEN
        IF EXISTS (SELECT 1 FROM reconciliation.rule m
                   WHERE m.rule_set_id = NEW.rule_set_id AND m.priority = NEW.priority) THEN
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME = 'tolerance' THEN
        IF EXISTS (SELECT 1 FROM reconciliation.tolerance m
                   WHERE m.rule_set_id = NEW.rule_set_id AND m.comparison = NEW.comparison
                     AND m.currency IS NOT DISTINCT FROM NEW.currency) THEN
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME = 'provider_fee_schedule' THEN
        IF EXISTS (SELECT 1 FROM reconciliation.provider_fee_schedule m
                   WHERE m.rule_set_id = NEW.rule_set_id AND m.line_type = NEW.line_type
                     AND m.currency = NEW.currency) THEN
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME = 'severity_threshold' THEN
        IF EXISTS (SELECT 1 FROM reconciliation.severity_threshold m
                   WHERE m.rule_set_id = NEW.rule_set_id AND m.currency = NEW.currency) THEN
            RETURN NEW;
        END IF;
    END IF;
    IF NOT EXISTS (
            SELECT 1 FROM reconciliation.rule_set p
            WHERE p.id = NEW.rule_set_id
              AND p.status = 'PROPOSED'
              AND p.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) THEN
        RAISE EXCEPTION 'a rule set''s content is written with its proposal, in the proposal''s own transaction, and immutable from PROPOSED (INV-HIST-04, INV-AUD-04): an approver approves exactly what was proposed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rule_set_lag_joins_its_proposal
    BEFORE INSERT ON reconciliation.rule_set_lag
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();

CREATE TRIGGER rule_joins_its_proposal
    BEFORE INSERT ON reconciliation.rule
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();

CREATE TRIGGER tolerance_joins_its_proposal
    BEFORE INSERT ON reconciliation.tolerance
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();

CREATE TRIGGER provider_fee_schedule_joins_its_proposal
    BEFORE INSERT ON reconciliation.provider_fee_schedule
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();

CREATE TRIGGER severity_threshold_joins_its_proposal
    BEFORE INSERT ON reconciliation.severity_threshold
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();

ALTER TABLE reconciliation.tolerance
    DROP CONSTRAINT tolerance_once,
    ADD CONSTRAINT tolerance_once UNIQUE NULLS NOT DISTINCT (rule_set_id, comparison, currency);

-- The version's history: proposed, activated, retired by a successor, rejected.
CREATE TABLE reconciliation.rule_set_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    rule_set_id    UUID        NOT NULL,
    from_status    TEXT,
    to_status      TEXT        NOT NULL,
    actor          TEXT        NOT NULL,
    actor_type     TEXT        NOT NULL,
    reason         TEXT        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT        NOT NULL,

    CONSTRAINT rule_set_event_pk PRIMARY KEY (seq),
    CONSTRAINT rule_set_event_rule_set_fk FOREIGN KEY (rule_set_id)
        REFERENCES reconciliation.rule_set (id),
    CONSTRAINT rule_set_event_edges CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('ACTIVE', 'REJECTED'))
        OR (from_status = 'ACTIVE' AND to_status = 'RETIRED')),
    CONSTRAINT rule_set_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX rule_set_event_by_rule_set
    ON reconciliation.rule_set_event (rule_set_id, seq);

CREATE OR REPLACE FUNCTION reconciliation.rule_set_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rule set''s history is append-only for every writer (INV-AUD-04)';
END;
$$;

CREATE TRIGGER rule_set_event_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.rule_set_event
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.rule_set_event_is_append_only();

-- ===============================================================================================
-- 2. The decision snapshot, completed (ADR-0068 sections 5 and 9).
-- ===============================================================================================
ALTER TABLE reconciliation.match_decision
    ADD COLUMN verdict TEXT,
    ADD COLUMN judged_status TEXT,
    ADD COLUMN judged_minor BIGINT,
    ADD COLUMN fingerprint_seen_earlier BOOLEAN,
    ADD COLUMN group_membership_complete BOOLEAN,
    ADD COLUMN fee_gross_minor BIGINT,
    -- DecisionVerdict, reconciled by ReconciliationV012MigrationTest.
    ADD CONSTRAINT match_decision_verdict CHECK (verdict IS NULL OR verdict IN (
        'ALLOCATE', 'AMBIGUOUS', 'DIRECTION_CONTRADICTED', 'CURRENCY_CONTRADICTED', 'DUPLICATE', 'NO_CANDIDATES', 'NO_RULE', 'GROUP_MATCH', 'GROUP_NO_CANDIDATES', 'GROUP_TOTAL_DIFFERS', 'GROUP_MEMBERSHIP_MOVED', 'TOP_UP', 'OFFSET', 'CORRECTION_UNREACHED', 'FEE_WITHIN', 'FEE_BEYOND', 'MANUAL_CHOICE', 'ERRORED')),
    ADD CONSTRAINT match_decision_judged_status CHECK (judged_status IS NULL OR judged_status IN (
        'PENDING', 'UNMATCHED', 'PARKED')),
    ADD CONSTRAINT match_decision_judged_counted CHECK (judged_minor IS NULL OR judged_minor >= 0),
    ADD CONSTRAINT match_decision_fee_gross_counted CHECK (
        fee_gross_minor IS NULL OR fee_gross_minor >= 0);

-- Every decision written from now on carries what its replay needs, for every writer; a decision
-- of the matching engine names its fingerprint input, a value-date group its membership input.
CREATE OR REPLACE FUNCTION reconciliation.match_decision_carries_its_replay()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.verdict IS NULL OR NEW.judged_status IS NULL OR NEW.judged_minor IS NULL THEN
        RAISE EXCEPTION 'a match decision stores its verdict and what it judged, so its replay is exact (INV-REC-04, ADR-0068 section 9)';
    END IF;
    IF NEW.verdict IN ('ALLOCATE', 'AMBIGUOUS', 'DIRECTION_CONTRADICTED', 'CURRENCY_CONTRADICTED',
                       'DUPLICATE', 'NO_CANDIDATES', 'NO_RULE')
            AND NEW.fingerprint_seen_earlier IS NULL THEN
        RAISE EXCEPTION 'a matching-engine decision stores its fingerprint input (INV-REC-04)';
    END IF;
    IF NEW.verdict IN ('GROUP_MATCH', 'GROUP_NO_CANDIDATES', 'GROUP_TOTAL_DIFFERS',
                       'GROUP_MEMBERSHIP_MOVED')
            AND NEW.group_membership_complete IS NULL THEN
        RAISE EXCEPTION 'a value-date group decision stores its membership input (INV-REC-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER match_decision_carries_its_replay
    BEFORE INSERT ON reconciliation.match_decision
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.match_decision_carries_its_replay();

-- A correction's other input: the original items' open parked values it judged, in its order.
CREATE TABLE reconciliation.match_parked_original (
    decision_id      UUID     NOT NULL,
    ordinal          INT      NOT NULL,
    original_item_id UUID     NOT NULL,
    suspense_item_id UUID     NOT NULL,
    break_id         UUID     NOT NULL,
    side             TEXT     NOT NULL,
    remainder_minor  BIGINT   NOT NULL,
    currency         CHAR(3)  NOT NULL,
    scale            SMALLINT NOT NULL,

    CONSTRAINT match_parked_original_pk PRIMARY KEY (decision_id, ordinal),
    CONSTRAINT match_parked_original_decision_fk FOREIGN KEY (decision_id)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT match_parked_original_item_fk FOREIGN KEY (original_item_id)
        REFERENCES reconciliation.external_item (id),
    CONSTRAINT match_parked_original_suspense_fk FOREIGN KEY (suspense_item_id)
        REFERENCES reconciliation.suspense_item (id),
    CONSTRAINT match_parked_original_break_fk FOREIGN KEY (break_id)
        REFERENCES reconciliation.break (id),
    CONSTRAINT match_parked_original_ordinal_counted CHECK (ordinal >= 0),
    CONSTRAINT match_parked_original_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT match_parked_original_remainder_counted CHECK (remainder_minor >= 0),
    CONSTRAINT match_parked_original_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT match_parked_original_scale_bounded CHECK (scale BETWEEN 0 AND 9)
);

CREATE OR REPLACE FUNCTION reconciliation.match_parked_original_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a decision''s snapshot is history, append-only for every writer (INV-REC-04, INV-REC-07)';
END;
$$;

CREATE TRIGGER match_parked_original_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.match_parked_original
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.match_parked_original_is_append_only();

-- ===============================================================================================
-- 3. The replay record (ADR-0068 section 9.1): one row per replay, nothing else written.
-- ===============================================================================================
CREATE TABLE reconciliation.run_replay (
    id                       UUID        NOT NULL,
    run_id                   UUID        NOT NULL,
    requested_by             TEXT        NOT NULL,
    requested_by_type        TEXT        NOT NULL,
    verdict                  TEXT        NOT NULL,
    replayed                 INT         NOT NULL,
    not_replayed             INT         NOT NULL,
    divergences              INT         NOT NULL,
    pending_rematch          INT         NOT NULL,
    first_divergent_decision UUID,
    at                       TIMESTAMPTZ NOT NULL,
    correlation_id           TEXT        NOT NULL,

    CONSTRAINT run_replay_pk PRIMARY KEY (id),
    CONSTRAINT run_replay_run_fk FOREIGN KEY (run_id)
        REFERENCES reconciliation.reconciliation_batch (id),
    CONSTRAINT run_replay_decision_fk FOREIGN KEY (first_divergent_decision)
        REFERENCES reconciliation.match_decision (id),
    CONSTRAINT run_replay_verdict CHECK (verdict IN ('IDENTICAL', 'DIVERGED')),
    CONSTRAINT run_replay_counts CHECK (
        replayed >= 0 AND not_replayed >= 0 AND divergences >= 0 AND pending_rematch >= 0
            AND divergences <= replayed),
    -- DIVERGED exactly when a decision diverged, and then the first one is named.
    CONSTRAINT run_replay_verdict_is_its_count CHECK ((verdict = 'DIVERGED') = (divergences > 0)),
    CONSTRAINT run_replay_names_first_divergence CHECK (
        (divergences > 0) = (first_divergent_decision IS NOT NULL))
);

CREATE INDEX run_replay_by_run ON reconciliation.run_replay (run_id, at);

CREATE OR REPLACE FUNCTION reconciliation.run_replay_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a replay verdict is history, append-only for every writer (INV-HIST-04)';
END;
$$;

CREATE TRIGGER run_replay_is_append_only
    BEFORE UPDATE OR DELETE ON reconciliation.run_replay
    FOR EACH ROW
    EXECUTE FUNCTION reconciliation.run_replay_is_append_only();

-- ===============================================================================================
-- 4. One open break per (type, decision): a diverged replay's break converges.
-- ===============================================================================================
CREATE UNIQUE INDEX break_one_open_per_decision
    ON reconciliation.break (type, decision_id) WHERE status <> 'RESOLVED';

-- ===============================================================================================
-- Grants: the version moves only by its machine; everything new is insert-only.
-- ===============================================================================================
GRANT UPDATE (status, decided_by, decided_at) ON reconciliation.rule_set TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.rule_set_event TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.match_parked_original TO finapp_app;
GRANT SELECT, INSERT ON reconciliation.run_replay TO finapp_app;
