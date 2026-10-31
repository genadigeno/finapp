-- Valuation rate sets (P14-TSK-014).
--
-- ADR-0116 decision 2, INV-ACC-11, INV-FX-05, INV-AUD-04, INV-MON-01; PHASE_14_PLAN.md sections 5, 6, 9, 13.4 and owner
-- decisions C1 and C7 (recommended defaults, awaiting ratification): a valuation rate set is the frozen input a FINAL
-- translated report run pins (P14-TSK-015) - for one LOCKED accounting period, per valued currency the reference's last
-- EUR-based observation strictly before the first instant of the next month (UTC), no older than the set's staleness
-- bound, copied from fx.rate_snapshot with its provenance (source, snapshot id, observed_at, received_at) and its rate
-- exactly as fx stores it, NUMERIC(20,10). Never a customer quote, an executed trade rate or a cover rate: the set is read
-- through accounting's ValuationRates port, whose app adapter reads fx.rate_snapshot alone.
--
-- NO MONEY: no column here holds an amount, a balance or a line; nothing is posted and no journal line is re-valued
-- (C7 A). NO SEED. NO CROSS-SCHEMA REFERENCE: snapshot_id is a bare uuid (ADR-0029) and the provenance is copied, so a set
-- is evidence on its own.
--
-- The machine: born PROPOSED with all its rates in its capturing transaction (a set commits only whole); PROPOSED ->
-- APPROVED (a person other than its capturer, while the period is LOCKED) | REJECTED; both terminal; at most one APPROVED
-- set per period, for every writer. The locks (DISTRIBUTED_EXECUTION.md section 3, the Phase 14 lock order): the capture
-- takes the period row FOR SHARE (A1), the approval the period row FOR UPDATE (A1, every approval of the period
-- serialised) then the set row FOR UPDATE (A4), the rejection the set row alone. The statements below hold no lock; the
-- CHECKs, the partial unique and the triggers are the backstops for every writer, the migrator included.

-- ---------------------------------------------------------------------------------------------
-- The set: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.valuation_rate_set (
    id                  uuid        NOT NULL,
    period_id           uuid        NOT NULL,
    functional_currency char(3)     NOT NULL,
    as_of               timestamptz NOT NULL,
    max_age_hours       integer     NOT NULL,
    rate_count          integer     NOT NULL,
    status              text        NOT NULL,
    captured_by         text        NOT NULL,
    captured_by_type    text        NOT NULL,
    captured_at         timestamptz NOT NULL,
    capture_reason      text,
    decided_by          text,
    decided_by_type     text,
    decided_at          timestamptz,
    decision_reason     text,
    correlation_id      text        NOT NULL,
    CONSTRAINT valuation_rate_set_pk PRIMARY KEY (id),
    CONSTRAINT valuation_rate_set_period_fk FOREIGN KEY (period_id) REFERENCES accounting.accounting_period (id),
    -- ValuationRateSetStatus.
    CONSTRAINT valuation_rate_set_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    -- C1: every pair is based on the functional and reporting currency.
    CONSTRAINT valuation_rate_set_functional_is_eur CHECK (functional_currency = 'EUR'),
    -- ValuationStaleness: 1 hour to two weeks.
    CONSTRAINT valuation_rate_set_bound_in_range CHECK (max_age_hours BETWEEN 1 AND 336),
    CONSTRAINT valuation_rate_set_values_a_currency CHECK (rate_count >= 1),
    -- Persons only: an authenticated human captures and decides - never the platform, a service or a merchant.
    CONSTRAINT valuation_rate_set_persons CHECK (
        captured_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    CONSTRAINT valuation_rate_set_capture_reasoned CHECK (
        capture_reason IS NULL OR char_length(capture_reason) BETWEEN 1 AND 1000),
    -- A decision is whole and reasoned exactly when the set is no longer PROPOSED.
    CONSTRAINT valuation_rate_set_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT valuation_rate_set_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, for every writer (INV-AUD-04, INV-ACC-11): whoever approved a set did not capture it.
    CONSTRAINT valuation_rate_set_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> captured_by)
);

COMMENT ON TABLE accounting.valuation_rate_set IS
    'A valuation rate set (P14-TSK-014, ADR-0116 decision 2, INV-ACC-11): for one LOCKED accounting period, the fx reference''s EUR-based rates as of the period''s end (as_of, the first instant of the next month UTC, exclusive), within max_age_hours, born PROPOSED with all its rates (valuation_rate) and immutable; PROPOSED -> APPROVED (a person other than its capturer, the period LOCKED) | REJECTED, terminal; one APPROVED per period. Pinned by FINAL translated report runs (P14-TSK-015). No money; nothing posted.';

-- At most one APPROVED set per period - the backstop beneath the approval's serialisation on the period row.
CREATE UNIQUE INDEX valuation_rate_set_one_approved_per_period ON accounting.valuation_rate_set (period_id)
    WHERE status = 'APPROVED';

CREATE INDEX valuation_rate_set_by_period ON accounting.valuation_rate_set (period_id, captured_at);

-- The edge trigger: every stamp clock_timestamp(), the instant written after the lock the act waited on.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
    held_end    date;
    held_rates  integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a valuation rate set is never deleted (P14-TSK-014, INV-ACC-11)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a valuation rate set is born PROPOSED and undecided: none is approved by insert (P14-TSK-014)';
        END IF;
        -- WHICH PERIOD: a LOCKED one - its end has passed and the ledger's cutoff stands.
        SELECT p.status, p.period_end INTO held_status, held_end FROM accounting.accounting_period p
         WHERE p.id = NEW.period_id;
        IF NOT FOUND OR held_status <> 'LOCKED' THEN
            RAISE EXCEPTION 'a valuation rate set is captured for a LOCKED accounting period (P14-TSK-014)';
        END IF;
        -- AS OF THE PERIOD'S END: the first instant of the next month, UTC.
        IF NEW.as_of IS DISTINCT FROM ((held_end + 1)::timestamp AT TIME ZONE 'UTC') THEN
            RAISE EXCEPTION 'a valuation rate set is as of its period''s end, the first instant after % UTC (P14-TSK-014)', held_end;
        END IF;
        NEW.captured_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.period_id IS DISTINCT FROM OLD.period_id
            OR NEW.functional_currency IS DISTINCT FROM OLD.functional_currency OR NEW.as_of IS DISTINCT FROM OLD.as_of
            OR NEW.max_age_hours IS DISTINCT FROM OLD.max_age_hours OR NEW.rate_count IS DISTINCT FROM OLD.rate_count
            OR NEW.captured_by IS DISTINCT FROM OLD.captured_by OR NEW.captured_by_type IS DISTINCT FROM OLD.captured_by_type
            OR NEW.captured_at IS DISTINCT FROM OLD.captured_at OR NEW.capture_reason IS DISTINCT FROM OLD.capture_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a valuation rate set''s capture is frozen (P14-TSK-014, INV-ACC-11)';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN
        RAISE EXCEPTION 'a valuation rate set moves PROPOSED -> APPROVED | REJECTED, once; % -> % is not an edge (P14-TSK-014)', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT p.status INTO held_status FROM accounting.accounting_period p WHERE p.id = NEW.period_id;
        IF held_status <> 'LOCKED' THEN
            RAISE EXCEPTION 'a valuation rate set is approved while its period is LOCKED, not % (P14-TSK-014)', held_status;
        END IF;
        SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.id;
        IF held_rates <> NEW.rate_count THEN
            RAISE EXCEPTION 'a valuation rate set is approved only whole: % of its % rates (P14-TSK-014)', held_rates, NEW.rate_count;
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER valuation_rate_set_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.valuation_rate_set
    FOR EACH ROW
    EXECUTE FUNCTION accounting.valuation_rate_set_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the valuation rate sets and their rates are never truncated (P14-TSK-014, INV-HIST-01)';
END;
$$;

CREATE TRIGGER valuation_rate_set_is_never_truncated
    BEFORE TRUNCATE ON accounting.valuation_rate_set
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.valuation_rate_set_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The rates: born with their PROPOSED set, in its capturing transaction, never changed.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.valuation_rate (
    set_id         uuid           NOT NULL,
    position       integer        NOT NULL,
    base_currency  char(3)        NOT NULL,
    quote_currency char(3)        NOT NULL,
    rate           numeric(20,10) NOT NULL,
    source         text           NOT NULL,
    snapshot_id    uuid           NOT NULL,
    observed_at    timestamptz    NOT NULL,
    received_at    timestamptz    NOT NULL,
    -- One rate per valued currency.
    CONSTRAINT valuation_rate_pk PRIMARY KEY (set_id, quote_currency),
    CONSTRAINT valuation_rate_of_its_set_fk FOREIGN KEY (set_id) REFERENCES accounting.valuation_rate_set (id),
    CONSTRAINT valuation_rate_position_once UNIQUE (set_id, position),
    CONSTRAINT valuation_rate_snapshot_once UNIQUE (set_id, snapshot_id),
    CONSTRAINT valuation_rate_position_positive CHECK (position >= 1),
    -- EUR-based, of another currency - fx's canonical direction for every EUR pair (no inversion, ADR-0074).
    CONSTRAINT valuation_rate_is_eur_based CHECK (
        base_currency = 'EUR' AND quote_currency ~ '^[A-Z]{3}$' AND quote_currency <> base_currency),
    -- The rate exactly as fx.rate_snapshot holds it: NUMERIC(20,10), positive (INV-MON-01: never floating point).
    CONSTRAINT valuation_rate_is_positive CHECK (rate > 0),
    -- fx V002's rate_snapshot_source_shape: a reference source's code.
    CONSTRAINT valuation_rate_source_shape CHECK (source ~ '^[a-z][a-z0-9.-]{0,63}$'),
    -- The source's clock may run up to a minute ahead of the database's (fx V002), never more.
    CONSTRAINT valuation_rate_observed_by_receipt CHECK (observed_at <= received_at + interval '1 minute')
);

COMMENT ON TABLE accounting.valuation_rate IS
    'A valuation rate set''s rates (P14-TSK-014, INV-ACC-11, INV-FX-05): EUR/quote_currency at the fx reference''s rate exactly as stored (NUMERIC(20,10)), with its provenance - source, the reference snapshot''s id in fx (bare, no cross-schema key), observed_at (strictly before the set''s as_of, within its max_age_hours) and received_at. Born with its PROPOSED set in the capturing transaction, the set holding exactly rate_count of them; INSERT-only for every writer.';

-- Born only with a PROPOSED set, up to its count, inside its window; immutable from insert, for every role.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_is_born_with_its_set()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
    held_as_of  timestamptz;
    held_hours  integer;
    held_count  integer;
    held_rates  integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a valuation rate is immutable from insert, for every role (P14-TSK-014, INV-ACC-11)';
    END IF;
    -- The set row locked: every writer of one set's rates serialises on it, so the count below is exact.
    SELECT s.status, s.as_of, s.max_age_hours, s.rate_count INTO held_status, held_as_of, held_hours, held_count
      FROM accounting.valuation_rate_set s WHERE s.id = NEW.set_id FOR UPDATE;
    IF NOT FOUND OR held_status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'a valuation rate is born with its PROPOSED set (P14-TSK-014)';
    END IF;
    SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.set_id;
    IF held_rates >= held_count THEN
        RAISE EXCEPTION 'a valuation rate set holds exactly its % rates: none is added late (P14-TSK-014, INV-ACC-11)', held_count;
    END IF;
    -- THE WINDOW: observed strictly before the period's end, within the set's staleness bound.
    IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at >= held_as_of - make_interval(hours => held_hours)) THEN
        RAISE EXCEPTION 'a valuation rate is observed before its set''s period end and within its % hour bound (P14-TSK-014)', held_hours;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER valuation_rate_is_born_with_its_set
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.valuation_rate
    FOR EACH ROW
    EXECUTE FUNCTION accounting.valuation_rate_is_born_with_its_set();

CREATE TRIGGER valuation_rate_is_never_truncated
    BEFORE TRUNCATE ON accounting.valuation_rate
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.valuation_rate_set_is_never_truncated();

-- A set commits only whole: at COMMIT, exactly its rate_count rates.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_commits_whole()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_rates integer;
BEGIN
    SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.id;
    IF held_rates <> NEW.rate_count THEN
        RAISE EXCEPTION 'valuation rate set % committed with % of its % rates (P14-TSK-014)', NEW.id, held_rates, NEW.rate_count;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER valuation_rate_set_commits_whole
    AFTER INSERT ON accounting.valuation_rate_set
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.valuation_rate_set_commits_whole();

-- ---------------------------------------------------------------------------------------------
-- The grants: the set's decision columns only; the rates INSERT-only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.valuation_rate_set TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason)
    ON accounting.valuation_rate_set TO finapp_app;
GRANT SELECT, INSERT ON accounting.valuation_rate TO finapp_app;
