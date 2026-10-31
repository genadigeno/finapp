-- Report runs: immutable, reproducible (P14-TSK-008).
--
-- ADR-0115 decisions 2-3, INV-ACC-04, INV-ACC-14; PHASE_14_PLAN.md sections 6 (the Report Run lifecycle), 8.1 T-report,
-- 8.3 row 7, 8.4 leg (c), 9 and 13.6. A report run is the issued answer to one question - one report definition and
-- version, one period, one parameter set - born once by insert-or-read on the plan's key, generated in a REPEATABLE READ
-- READ ONLY transaction on a separate connection (the application's; nothing here can be written from it), completed by
-- a conditional edge carrying its content and content_sha256 in one statement, and frozen.
--
-- THE KEY (report_run_key): UNIQUE (definition, definition_version, period_id, revision, parameters_hash) - the plan's,
-- verbatim. `revision` is the RUN's: 1, then one more each time the latest run can no longer answer its question (FAILED,
-- SUPERSEDED, a PROVISIONAL run once its period is locked or a newer snapshot is asked, a FINAL-basis run whose period
-- moved). Ten claimers of one question insert one row; the other nine read it back.
--
-- FINAL OR PROVISIONAL (basis): FINAL for a period no longer OPEN (LOCKED, CLOSE_PROPOSED, CLOSED) - its posting-date range
-- immutable after the ledger's cutoff - recorded with the lock it read under (period_locked_at = the period's locked_at)
-- and the mapping version the lock pinned; PROVISIONAL for an OPEN period, cut at the claim's database clock (cut_at),
-- labelled, never issued. The edge trigger holds the basis to the period at birth and a FINAL completion to the same
-- lock. A period returns to OPEN (P14-TSK-005's unlock now, P14-TSK-012's reopen later) only with its FINAL runs
-- SUPERSEDED - the trigger on accounting_period below - so no FINAL run ever stands over an open period (INV-ACC-04).
--
-- THE CONTENT IS ITS CANONICAL TEXT (an as-built correction of the plan's "content jsonb"): jsonb re-orders and re-spaces,
-- so it would not retain the output as issued (INV-ACC-04's own words) and the database could not check the hash. The
-- content is the canonical JSON text the application wrote (CanonicalJson: sorted keys, no whitespace, no JSON number -
-- amounts as minor units in strings), checked here as a JSON object and HASHED HERE: content_sha256 is recomputed from it
-- for every writer (risk V006/V007's inputs_canonical precedent). Read it as content::jsonb. The three content checks
-- (bounded, a JSON object, the hash its own) run in the machine trigger AT THE ONE EDGE THAT SETS THE CONTENT - not as
-- CHECKs, which PostgreSQL re-evaluates on every UPDATE of the row and would re-parse up to 8 MB at each supersession;
-- the freeze keeps the content as checked from then on (P14-TSK-008's review, M-2).
--
-- THE FREEZE (report_run_content_is_frozen): once a run has content, its content, hash and completion never change - its
-- own trigger, firing first by name and only for an UPDATE naming one of the three columns (so a supersession, which
-- names none, never reads the content), so its refusal is its own (the entry's probe drops it). The machine
-- (report_run_permits_only_machine_edges): GENERATING -> FINAL | PROVISIONAL (the basis) | FAILED; GENERATING ->
-- GENERATING only as a lease's reclaim (one attempt more, only past the lease, on the database clock); FINAL ->
-- SUPERSEDED. Never deleted, never truncated.
--
-- THE VALUATION RATE SET (valuation_rate_set_id): uuid NULL with NO FOREIGN KEY - the rate set's table is P14-TSK-014's
-- accounting V005, which lands after this migration; the reference is added by the task that first fills it
-- (P14-TSK-014 / -015). It is a parameter of the question (report_run_rate_set_is_a_parameter), so two runs pinning
-- different sets are different runs.
--
-- The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 lock order): the claim and the completion hold the period
-- row FOR SHARE (A1) before the run row (A4); the unlock holds it FOR UPDATE, supersedes the period's FINAL runs (A4) and
-- then moves the period - so a completion and an unlock serialise on the period row, and the edge trigger re-takes the
-- period FOR SHARE for a writer that did not. NO MONEY: no column here is a balance or a line; the content is output,
-- never truth (the journal is). The migration names no other schema.

-- ---------------------------------------------------------------------------------------------
-- The run.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.report_run (
    id                        uuid        NOT NULL,
    definition                text        NOT NULL,
    definition_version        integer     NOT NULL,
    period_id                 uuid        NOT NULL,
    revision                  integer     NOT NULL,
    parameters                text        NOT NULL,
    parameters_hash           text        NOT NULL,
    period_revision           integer     NOT NULL,
    basis                     text        NOT NULL,
    period_locked_at          timestamptz,
    mapping_version_id        uuid,
    valuation_rate_set_id     uuid,
    cut_at                    timestamptz NOT NULL,
    requested_by              text        NOT NULL,
    requested_by_type         text        NOT NULL,
    claimed_at                timestamptz NOT NULL,
    status                    text        NOT NULL,
    attempt                   integer     NOT NULL,
    lease_until               timestamptz NOT NULL,
    content                   text,
    content_sha256            text,
    completed_at              timestamptz,
    failure_code              text,
    failed_at                 timestamptz,
    superseded_at             timestamptz,
    superseded_correlation_id text,
    correlation_id            text        NOT NULL,
    CONSTRAINT report_run_pk PRIMARY KEY (id),
    -- The plan's key: one run per question and revision - the arbiter of every claim.
    CONSTRAINT report_run_key UNIQUE (definition, definition_version, period_id, revision, parameters_hash),
    CONSTRAINT report_run_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    CONSTRAINT report_run_mapping_version_fk FOREIGN KEY (mapping_version_id)
        REFERENCES accounting.gl_mapping_version (id),
    -- RunStatus, RunBasis and RunFailure.
    CONSTRAINT report_run_status_is_known CHECK (status IN ('GENERATING', 'FINAL', 'PROVISIONAL', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT report_run_basis_is_known CHECK (basis IN ('FINAL', 'PROVISIONAL')),
    CONSTRAINT report_run_failure_is_known CHECK (failure_code IS NULL OR failure_code IN ('DEFINITION_UNKNOWN', 'DEFINITION_FAILED', 'PERIOD_MOVED', 'CONTENT_INVALID', 'ATTEMPTS_EXHAUSTED', 'GENERATION_TIMED_OUT')),
    -- ReportDefinitions.CODE and MAX_VERSION.
    CONSTRAINT report_run_definition_shaped CHECK (definition ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT report_run_versions_bounded CHECK (
        definition_version BETWEEN 1 AND 9999 AND revision >= 1 AND period_revision >= 1 AND attempt >= 1),
    -- The parameters: a canonical JSON object, bounded, its hash recomputed here for every writer.
    CONSTRAINT report_run_parameters_canonical CHECK (
        octet_length(parameters) <= 4096
        AND jsonb_typeof(parameters::jsonb) = 'object'
        AND parameters_hash ~ '^[0-9a-f]{64}$'
        AND parameters_hash = encode(sha256(convert_to(parameters, 'UTF8')), 'hex')),
    -- The pinned rate set is the question's own parameter, never a second input beside it.
    CONSTRAINT report_run_rate_set_is_a_parameter CHECK (
        valuation_rate_set_id IS NOT DISTINCT FROM (parameters::jsonb ->> 'valuationRateSet')::uuid),
    -- Who asked: a person, or the platform (the close run's FINAL trial balance, P14-TSK-011) - never a service or a
    -- merchant.
    CONSTRAINT report_run_requester_kind CHECK (
        requested_by_type IN ('SYSTEM', 'CUSTOMER', 'EMPLOYEE') AND char_length(requested_by) BETWEEN 1 AND 128),
    CONSTRAINT report_run_lease_after_claim CHECK (lease_until > claimed_at),
    -- The basis: a FINAL run names the lock it read under, and only a FINAL run does; the completed status is the basis.
    CONSTRAINT report_run_basis_coherent CHECK (
        (basis = 'FINAL') = (period_locked_at IS NOT NULL)
        AND (status NOT IN ('FINAL', 'SUPERSEDED') OR basis = 'FINAL')
        AND (status <> 'PROVISIONAL' OR basis = 'PROVISIONAL')),
    -- The content: present exactly once complete, whole - with its hash and its completion - and never on a failure.
    CONSTRAINT report_run_content_coherent CHECK (
        (status IN ('FINAL', 'PROVISIONAL', 'SUPERSEDED')) = (content IS NOT NULL)
        AND (content IS NULL) = (content_sha256 IS NULL)
        AND (content IS NULL) = (completed_at IS NULL)),
    -- The hash's shape - cheap on every UPDATE; that it IS the content's hash is the completion edge's check (below).
    CONSTRAINT report_run_content_hash_shaped CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT report_run_failure_coherent CHECK (
        (status = 'FAILED') = (failure_code IS NOT NULL) AND (failure_code IS NULL) = (failed_at IS NULL)),
    CONSTRAINT report_run_supersession_coherent CHECK (
        (status = 'SUPERSEDED') = (superseded_at IS NOT NULL)
        AND (superseded_at IS NULL) = (superseded_correlation_id IS NULL))
);

COMMENT ON TABLE accounting.report_run IS
    'A report run (P14-TSK-008, ADR-0115, INV-ACC-04): the issued answer to one question - a report definition and version, a period, a parameter set - born once by report_run_key, generated in a REPEATABLE READ READ ONLY transaction, completed with its content (the canonical JSON text, CONFIDENTIAL aggregates) and its sha256 (recomputed here) in one statement, then frozen. FINAL for a period no longer OPEN, under the lock it read (period_locked_at) and the mapping version the lock pinned; PROVISIONAL for an OPEN period, cut at its claim. FINAL -> SUPERSEDED when its period returns to OPEN, never deleted. valuation_rate_set_id has no foreign key until P14-TSK-014''s table exists.';

COMMENT ON COLUMN accounting.report_run.content IS
    'The issued output: canonical JSON text (sorted keys, no whitespace, no JSON number - amounts as minor units in strings), CONFIDENTIAL; content_sha256 is its sha256, recomputed at the completion edge (report_run_content_hashed). Frozen once set (report_run_content_is_frozen).';

COMMENT ON COLUMN accounting.report_run.valuation_rate_set_id IS
    'The valuation rate set the run pins, if any - uuid with NO foreign key: the rate set table is P14-TSK-014''s accounting V005, landing after this migration; the reference is added when it is first filled (P14-TSK-014 / -015). Equal to the parameters'' valuationRateSet by report_run_rate_set_is_a_parameter.';

-- The orphan leg's scan (P14-TSK-011 schedules it): GENERATING runs by lease.
CREATE INDEX report_run_orphans ON accounting.report_run (lease_until) WHERE status = 'GENERATING';

-- A period's runs - the supersession at an unlock or a reopen.
CREATE INDEX report_run_by_period ON accounting.report_run (period_id, status);

-- ---------------------------------------------------------------------------------------------
-- THE FREEZE: once complete, the content, its hash and its completion never change, for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION accounting.report_run_content_is_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.content IS NOT NULL AND (NEW.content IS DISTINCT FROM OLD.content
            OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
            OR NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
        RAISE EXCEPTION 'report_run_content_is_frozen: a report run''s content, hash and completion never change once complete (P14-TSK-008, INV-ACC-04)';
    END IF;
    RETURN NEW;
END;
$$;

-- Named to fire first (triggers of one event fire in name order): the freeze's refusal is its own. Only for an UPDATE
-- naming the content, its hash or its completion: every way to change them names them, and a supersession names none.
CREATE TRIGGER report_run_content_is_frozen
    BEFORE UPDATE OF content, content_sha256, completed_at ON accounting.report_run
    FOR EACH ROW
    EXECUTE FUNCTION accounting.report_run_content_is_frozen();

-- ---------------------------------------------------------------------------------------------
-- THE MACHINE: birth coherent with the period, the edges, every stamp clock_timestamp().
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION accounting.report_run_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    period_status   text;
    period_rev      integer;
    period_locked   timestamptz;
    period_pin      uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a report run is never deleted (P14-TSK-008, INV-ACC-04)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'GENERATING' OR NEW.attempt <> 1 OR NEW.content IS NOT NULL OR NEW.content_sha256 IS NOT NULL
                OR NEW.completed_at IS NOT NULL OR NEW.failure_code IS NOT NULL OR NEW.failed_at IS NOT NULL
                OR NEW.superseded_at IS NOT NULL OR NEW.superseded_correlation_id IS NOT NULL THEN
            RAISE EXCEPTION 'a report run is born GENERATING at attempt 1 with no content (P14-TSK-008)';
        END IF;
        -- The period FOR SHARE (A1): the claim already holds it; a writer that does not waits for a lock or an unlock.
        SELECT p.status, p.revision, p.locked_at, p.pinned_mapping_version_id
          INTO period_status, period_rev, period_locked, period_pin
          FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
        IF FOUND THEN
            IF NEW.period_revision <> period_rev THEN
                RAISE EXCEPTION 'a report run records its period''s revision (P14-TSK-008)';
            END IF;
            IF NEW.basis = 'FINAL' AND (period_status = 'OPEN' OR NEW.period_locked_at IS DISTINCT FROM period_locked
                    OR NEW.mapping_version_id IS DISTINCT FROM period_pin) THEN
                RAISE EXCEPTION 'a FINAL report run is born only for a period no longer OPEN, under its lock and the GL mapping version the lock pinned (P14-TSK-008, INV-ACC-04)';
            END IF;
            IF NEW.basis = 'PROVISIONAL' AND period_status <> 'OPEN' THEN
                RAISE EXCEPTION 'a PROVISIONAL report run is born only for an OPEN period (P14-TSK-008)';
            END IF;
        END IF;
        -- The cut is the claim's database clock.
        NEW.claimed_at := clock_timestamp();
        NEW.cut_at := NEW.claimed_at;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.definition IS DISTINCT FROM OLD.definition
            OR NEW.definition_version IS DISTINCT FROM OLD.definition_version
            OR NEW.period_id IS DISTINCT FROM OLD.period_id OR NEW.revision IS DISTINCT FROM OLD.revision
            OR NEW.parameters IS DISTINCT FROM OLD.parameters OR NEW.parameters_hash IS DISTINCT FROM OLD.parameters_hash
            OR NEW.period_revision IS DISTINCT FROM OLD.period_revision OR NEW.basis IS DISTINCT FROM OLD.basis
            OR NEW.period_locked_at IS DISTINCT FROM OLD.period_locked_at
            OR NEW.mapping_version_id IS DISTINCT FROM OLD.mapping_version_id
            OR NEW.valuation_rate_set_id IS DISTINCT FROM OLD.valuation_rate_set_id
            OR NEW.cut_at IS DISTINCT FROM OLD.cut_at OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
            OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
            OR NEW.claimed_at IS DISTINCT FROM OLD.claimed_at OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a report run''s question and inputs are frozen from its birth (P14-TSK-008, INV-ACC-04)';
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status = 'GENERATING' THEN
        -- THE RECLAIM: one attempt more, only past the lease, the new lease ahead - never a second run racing the first.
        IF NEW.attempt <> OLD.attempt + 1 OR OLD.lease_until > clock_timestamp() OR NEW.lease_until <= clock_timestamp()
                OR NEW.content IS NOT NULL OR NEW.failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'a GENERATING report run is reclaimed only past its lease, one attempt more (P14-TSK-008)';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status IN ('FINAL', 'PROVISIONAL') THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until OR NEW.status <> NEW.basis THEN
            RAISE EXCEPTION 'a report run completes as its basis, at the attempt that holds it (P14-TSK-008)';
        END IF;
        IF NEW.basis = 'FINAL' THEN
            SELECT p.status, p.locked_at INTO period_status, period_locked
              FROM accounting.accounting_period p WHERE p.id = NEW.period_id FOR SHARE;
            IF period_status = 'OPEN' OR period_locked IS DISTINCT FROM NEW.period_locked_at THEN
                RAISE EXCEPTION 'a FINAL report run completes only while its period holds the lock it was claimed under (P14-TSK-008, INV-ACC-04)';
            END IF;
        END IF;
        -- THE CONTENT, checked at the one edge that sets it (the freeze keeps it so): bounded (ReportGeneration.
        -- MAX_CONTENT_BYTES) before it is parsed, a JSON object, and THE HASH THE CONTENT'S, for every writer (INV-ACC-04).
        IF octet_length(NEW.content) > 8388608 THEN
            RAISE EXCEPTION 'report_run_content_bounded: a report run''s content is at most 8 MiB (P14-TSK-008)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.content IS NOT NULL AND jsonb_typeof(NEW.content::jsonb) <> 'object' THEN
            RAISE EXCEPTION 'report_run_content_is_a_json_object: a report run''s content is a JSON object (P14-TSK-008)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.content IS NOT NULL AND NEW.content_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.content, 'UTF8')), 'hex') THEN
            RAISE EXCEPTION 'report_run_content_hashed: a report run''s content_sha256 is its content''s sha256 (P14-TSK-008, INV-ACC-04)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.completed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'GENERATING' AND NEW.status = 'FAILED' THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
            RAISE EXCEPTION 'a report run fails at the attempt that holds it (P14-TSK-008)';
        END IF;
        NEW.failed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'FINAL' AND NEW.status = 'SUPERSEDED' THEN
        IF NEW.attempt <> OLD.attempt OR NEW.lease_until IS DISTINCT FROM OLD.lease_until THEN
            RAISE EXCEPTION 'a FINAL report run is superseded and nothing else (P14-TSK-008)';
        END IF;
        NEW.superseded_at := clock_timestamp();
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'a report run moves GENERATING -> FINAL | PROVISIONAL | FAILED (GENERATING -> GENERATING past its lease), FINAL -> SUPERSEDED; % -> % is not an edge (P14-TSK-008)', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER report_run_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.report_run
    FOR EACH ROW
    EXECUTE FUNCTION accounting.report_run_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.report_run_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the report runs are never truncated (P14-TSK-008, INV-ACC-04, INV-HIST-01)';
END;
$$;

CREATE TRIGGER report_run_is_never_truncated
    BEFORE TRUNCATE ON accounting.report_run
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.report_run_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- A period returns to OPEN only with its FINAL runs superseded - the unlock (P14-TSK-005) and the reopen (P14-TSK-012),
-- for every writer: the domain supersedes them in the same transaction (ReportRunSupersession), this refuses the edge
-- if it did not. No FINAL run ever stands over a period whose range can change again (INV-ACC-04).
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION accounting.accounting_period_reopens_with_its_runs_superseded()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'OPEN' AND NEW.status = 'OPEN' AND EXISTS (
            SELECT 1 FROM accounting.report_run r WHERE r.period_id = NEW.id AND r.status = 'FINAL') THEN
        RAISE EXCEPTION 'a period returns to OPEN only with its FINAL report runs superseded (P14-TSK-008, INV-ACC-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounting_period_reopens_with_its_runs_superseded
    BEFORE UPDATE ON accounting.accounting_period
    FOR EACH ROW
    EXECUTE FUNCTION accounting.accounting_period_reopens_with_its_runs_superseded();

-- ---------------------------------------------------------------------------------------------
-- The grants: the machine's and the output's columns only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.report_run TO finapp_app;
GRANT UPDATE (status, attempt, lease_until, content, content_sha256, completed_at, failure_code, failed_at, superseded_at, superseded_correlation_id)
    ON accounting.report_run TO finapp_app;
