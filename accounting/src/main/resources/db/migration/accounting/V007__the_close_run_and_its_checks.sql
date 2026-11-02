-- The close run: recorded, leaderless readiness of a locked period (P14-TSK-011).
--
-- ADR-0114 decision 4, INV-ACC-09, INV-ACC-05; PHASE_14_PLAN.md sections 6 (the Close Check lifecycle), 8.1 T-check, 8.3
-- rows 5 and 6, 8.4 and 13.5. A lock stops the journal moving; it does not say the books are right. The close run is the
-- evidence: nine checks, each a row per attempt with what it read, which version judged it and what it found.
--
-- ONE RUN PER LOCK INSTANCE (close_run_one_per_lock): an unlock and a new lock are a new run; the old run's rows stay, its
-- pending attempts never claimable again. A run is born by the schedule's check leg, insert-if-absent, under the period row
-- FOR SHARE - not inside T-lock (a recorded deviation from section 8.1): the lock is the ledger's protection and waits for
-- nothing. The edge trigger holds the birth to the period's present lock, for every writer.
--
-- ONE ATTEMPT ROW, NEVER REWRITTEN (close_check_attempt_once, the plan's UNIQUE (close_run_id, check_code, attempt)): a
-- re-run is attempt n + 1, at most one PENDING per check (close_check_one_pending). PENDING -> PASSED | FAILED | DISCLOSED,
-- final: a concluded attempt is frozen by the trigger.
--
-- THE CLAIM (row 5): one UPDATE whose subselect takes the row FOR UPDATE SKIP LOCKED where lease_until has passed on the
-- database's clock - ten workers take disjoint rows, one holder per attempt. THE LEASE (row 6): lease_until is the instant
-- from which the row is claimable; holder the present claim's token, the fence every later move names; a lapsed holder is
-- replaced with reclaims + 1. A live worker renews (conditional on holder and on claimed_at plus the run's bound); a
-- deferral clears holder and pushes lease_until. All of this moves only PENDING rows and only these columns.
--
-- WHICH CHECKS MAY BE DISCLOSED (close_check_disclosed_only_where_it_may; CloseCheckCode.disclosable): the rule
-- CloseCheckCode left to this task, held here for every writer. A must-pass check is PASSED or it blocks.
--
-- The evidence is canonical JSON text (CanonicalJson: no JSON number), codes, ids and counts - and
-- RECONCILIATION_DISCLOSURE's and ACCRUALS_COMPLETE's aggregate figures per currency, minor units in strings:
-- CONFIDENTIAL. Nothing in this schema posts.
--
-- The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 order): a conclusion and a re-run take (A1) the period FOR
-- SHARE, then (A4) the run FOR UPDATE, then the attempt; a claim, a renewal and a deferral only the attempt. No namespace 14
-- key, no money row, no other schema's table.

-- ---------------------------------------------------------------------------------------------
-- The run.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.close_run (
    id                  uuid        NOT NULL,
    period_id           uuid        NOT NULL,
    period_locked_at    timestamptz NOT NULL,
    period_revision     integer     NOT NULL,
    started_at          timestamptz NOT NULL,
    checks_concluded_at timestamptz,
    correlation_id      text        NOT NULL,
    CONSTRAINT close_run_pk PRIMARY KEY (id),
    CONSTRAINT close_run_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    -- One run per lock instance - the birth's arbiter.
    CONSTRAINT close_run_one_per_lock UNIQUE (period_id, period_locked_at),
    CONSTRAINT close_run_revision_positive CHECK (period_revision >= 1)
);

COMMENT ON TABLE accounting.close_run IS
    'A close run (P14-TSK-011, ADR-0114 decision 4, INV-ACC-09): the readiness evidence of one lock instance of a LOCKED accounting period, born by the check leg insert-if-absent. checks_concluded_at is stamped once, when no attempt of the run is PENDING for the first time - finapp.accounting.close.duration, observed once across instances. Never deleted.';

CREATE OR REPLACE FUNCTION accounting.close_run_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status    text;
    held_locked_at timestamptz;
    held_revision  integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT status, locked_at, revision INTO held_status, held_locked_at, held_revision
            FROM accounting.accounting_period WHERE id = NEW.period_id FOR SHARE;
        IF NOT FOUND OR held_status <> 'LOCKED' OR held_locked_at IS DISTINCT FROM NEW.period_locked_at
                OR held_revision <> NEW.period_revision THEN
            RAISE EXCEPTION 'a close run is born under its period''s present lock (P14-TSK-011, INV-ACC-09)';
        END IF;
        IF NEW.checks_concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a close run is born with nothing concluded (P14-TSK-011)';
        END IF;
        NEW.started_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a close run is never deleted (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.period_locked_at IS DISTINCT FROM OLD.period_locked_at
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision
            OR NEW.started_at IS DISTINCT FROM OLD.started_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a close run is frozen from its birth (P14-TSK-011)';
    END IF;
    IF OLD.checks_concluded_at IS NOT NULL OR NEW.checks_concluded_at IS NULL THEN
        RAISE EXCEPTION 'a close run records its checks concluded once (P14-TSK-011)';
    END IF;
    NEW.checks_concluded_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER close_run_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.close_run
    FOR EACH ROW
    EXECUTE FUNCTION accounting.close_run_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.close_run_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the close runs and their attempts are never truncated (P14-TSK-011, INV-ACC-09)';
END;
$$;

CREATE TRIGGER close_run_is_never_truncated
    BEFORE TRUNCATE ON accounting.close_run
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.close_run_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The attempts: one row per (run, check, attempt), concluded once, frozen.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.close_check (
    id                uuid        NOT NULL,
    close_run_id      uuid        NOT NULL,
    check_code        text        NOT NULL,
    attempt           integer     NOT NULL,
    status            text        NOT NULL,
    requested_by      text        NOT NULL,
    requested_by_type text        NOT NULL,
    request_reason    text,
    born_at           timestamptz NOT NULL,
    holder            uuid,
    reclaims          integer     NOT NULL,
    lease_until       timestamptz NOT NULL,
    claimed_at        timestamptz,
    judged_by         text,
    failures          text,
    evidence          text,
    concluded_at      timestamptz,
    correlation_id    text        NOT NULL,
    CONSTRAINT close_check_pk PRIMARY KEY (id),
    CONSTRAINT close_check_run_fk FOREIGN KEY (close_run_id) REFERENCES accounting.close_run (id),
    -- The plan's key: a re-run is a new attempt, never an edit.
    CONSTRAINT close_check_attempt_once UNIQUE (close_run_id, check_code, attempt),
    -- CloseCheckCode.
    CONSTRAINT close_check_code_is_known CHECK (check_code IN ('LEDGER_TRIAL_BALANCE', 'GL_EQUALS_LEDGER', 'RULE_CONFORMANCE', 'SOURCE_COMPLETENESS', 'ACCRUALS_COMPLETE', 'PROJECTION_VERIFIED', 'RECONCILIATION_DISCLOSURE', 'VALUATION_SET_APPROVED', 'OPEN_ACCOUNTING_BREAKS')),
    -- CloseCheckStatus.
    CONSTRAINT close_check_status_is_known CHECK (status IN ('PENDING', 'PASSED', 'FAILED', 'DISCLOSED')),
    -- CloseCheckCode.disclosable: every other check is PASSED or it blocks the close.
    CONSTRAINT close_check_disclosed_only_where_it_may CHECK (
        status <> 'DISCLOSED' OR check_code IN ('RULE_CONFORMANCE', 'SOURCE_COMPLETENESS', 'ACCRUALS_COMPLETE', 'RECONCILIATION_DISCLOSURE', 'OPEN_ACCOUNTING_BREAKS')),
    CONSTRAINT close_check_counts_positive CHECK (attempt >= 1 AND reclaims >= 0),
    -- The platform asks the first attempt and the re-judgement of the breaks; a person's re-run is reasoned.
    CONSTRAINT close_check_requester CHECK (
        requested_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE')
        AND (requested_by_type = 'SYSTEM') = (request_reason IS NULL)),
    CONSTRAINT close_check_reason_bounded CHECK (request_reason IS NULL OR char_length(request_reason) BETWEEN 1 AND 1000),
    -- An outcome is whole, recorded exactly when concluded, under a claim; failures exactly when FAILED.
    CONSTRAINT close_check_outcome_coherent CHECK (
        (status = 'PENDING') = (concluded_at IS NULL)
        AND (status = 'PENDING') = (judged_by IS NULL)
        AND (status = 'PENDING') = (evidence IS NULL)
        AND (status = 'FAILED') = (failures IS NOT NULL)
        AND (status = 'PENDING' OR holder IS NOT NULL)
        AND (holder IS NULL OR claimed_at IS NOT NULL)),
    -- VERSIONED JUDGEMENT: each attempt names the versions it read (posting-rules:v2, gl-mapping:v3, report:TRIAL_BALANCE:v1, ...).
    CONSTRAINT close_check_judged_by_a_version CHECK (
        judged_by IS NULL OR (char_length(judged_by) <= 120 AND judged_by ~ '^[a-z][a-z-]*(:[A-Za-z0-9_.-]+){1,2}$')),
    CONSTRAINT close_check_failures_bounded CHECK (failures IS NULL OR char_length(failures) BETWEEN 1 AND 2000),
    CONSTRAINT close_check_evidence_bounded CHECK (evidence IS NULL OR char_length(evidence) BETWEEN 2 AND 16000)
);

COMMENT ON TABLE accounting.close_check IS
    'One attempt of one close check (P14-TSK-011, INV-ACC-09, INV-ACC-05): PENDING -> PASSED | FAILED | DISCLOSED, concluded once by the claim that holds it and frozen; a re-run is a new row. Its evidence is canonical JSON text, codes, ids, counts and aggregate figures per currency - CONFIDENTIAL.';

-- At most one attempt of a check is PENDING - two re-runs, one new attempt.
CREATE UNIQUE INDEX close_check_one_pending ON accounting.close_check (close_run_id, check_code) WHERE status = 'PENDING';
-- What a worker claims: the claimable rows in the order they became claimable.
CREATE INDEX close_check_claimable ON accounting.close_check (lease_until, id) WHERE status = 'PENDING';

-- The edge trigger: every stamp clock_timestamp(), the instant written after the lock the act waited on.
CREATE OR REPLACE FUNCTION accounting.close_check_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.holder IS NOT NULL OR NEW.reclaims <> 0 OR NEW.claimed_at IS NOT NULL THEN
            RAISE EXCEPTION 'a close check attempt is born PENDING and unclaimed (P14-TSK-011)';
        END IF;
        IF NEW.attempt <> 1 + COALESCE((SELECT max(attempt) FROM accounting.close_check
                WHERE close_run_id = NEW.close_run_id AND check_code = NEW.check_code), 0) THEN
            RAISE EXCEPTION 'a close check attempt follows the last, with no gap (P14-TSK-011)';
        END IF;
        NEW.born_at := clock_timestamp();
        NEW.lease_until := NEW.born_at;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a close check attempt is never deleted (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a concluded close check attempt is final - a re-run is a new attempt (P14-TSK-011, INV-ACC-09)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.close_run_id IS DISTINCT FROM OLD.close_run_id
            OR NEW.check_code IS DISTINCT FROM OLD.check_code OR NEW.attempt IS DISTINCT FROM OLD.attempt
            OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.request_reason IS DISTINCT FROM OLD.request_reason OR NEW.born_at IS DISTINCT FROM OLD.born_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a close check attempt''s request is frozen from its birth (P14-TSK-011)';
    END IF;
    IF NEW.reclaims <> OLD.reclaims AND NEW.reclaims <> OLD.reclaims + 1 THEN
        RAISE EXCEPTION 'a close check attempt is reclaimed one claim at a time (P14-TSK-011)';
    END IF;
    IF NEW.status = 'PENDING' THEN
        -- A claim, a reclaim, a renewal or a deferral: the lease's columns and nothing else.
        IF NEW.judged_by IS NOT NULL OR NEW.failures IS NOT NULL OR NEW.evidence IS NOT NULL
                OR NEW.concluded_at IS NOT NULL THEN
            RAISE EXCEPTION 'a PENDING close check attempt records no outcome (P14-TSK-011)';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status IN ('PASSED', 'FAILED', 'DISCLOSED') THEN
        IF OLD.holder IS NULL OR NEW.holder IS DISTINCT FROM OLD.holder OR NEW.reclaims <> OLD.reclaims THEN
            RAISE EXCEPTION 'a close check attempt is concluded by the claim that holds it (P14-TSK-011)';
        END IF;
        NEW.concluded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a close check moves PENDING -> PASSED | FAILED | DISCLOSED; % -> % is not an edge (P14-TSK-011)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER close_check_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.close_check
    FOR EACH ROW
    EXECUTE FUNCTION accounting.close_check_permits_only_machine_edges();

CREATE TRIGGER close_check_is_never_truncated
    BEFORE TRUNCATE ON accounting.close_check
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.close_run_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine rows' moving columns only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.close_run TO finapp_app;
GRANT UPDATE (checks_concluded_at) ON accounting.close_run TO finapp_app;
GRANT SELECT, INSERT ON accounting.close_check TO finapp_app;
GRANT UPDATE (status, holder, reclaims, lease_until, claimed_at, judged_by, failures, evidence, concluded_at) ON accounting.close_check TO finapp_app;
