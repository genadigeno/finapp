-- P9-TSK-005 (ADR-0075 §1, INV-FX-02): the independent reference rate, and the herd's pacing.
--
-- fx.rate_snapshot is the reference's evidence: one row per (source, canonical pair, observed_at),
-- append-only, the rate in RateColumns.ddl()'s NUMERIC(20,10) (ADR-0074) so every rate the domain
-- admits is held exactly. A reference is never executable: it is read for plausibility and
-- disclosure only, and only when FRESH ON THE DATABASE CLOCK.
--
-- THE THREE GUARANTEES, EACH HELD FOR EVERY WRITER:
--   1. received_at is the database's own statement_timestamp(), forced by the insert trigger -
--      no writer can claim freshness it does not have.
--   2. A snapshot is stored only when NEWER than the pair's latest observation. The insert
--      trigger takes pg_advisory_xact_lock(6, ...) - namespace 6, reserved in
--      DISTRIBUTED_EXECUTION.md - so racing writers of one pair serialise, and its post-lock
--      query (a fresh READ COMMITTED snapshot inside a volatile function) sees every committed
--      observation; an equal or older one is silently not stored (RETURN NULL). A stuck feed
--      replaying an old observation therefore never looks fresh: its latest row simply ages.
--   3. A snapshot is never updated and never deleted.
--
-- Pairs are CANONICAL (market direction, ReferenceSourceDeclaration): each unordered pair is held
-- once, so a reference is never divided to serve the other direction (ADR-0074: no inversion) -
-- the quote path judges the inverse direction by the exact product |rp x ref - 1| <= band.
--
-- fx.rate_fetch_permit paces the leaderless fetch (the pull_permit shape, P8-TSK-021), with one
-- difference Phase 9 section 7 requires: the attempt is stamped by the DATABASE inside the
-- conditional upsert, never by an instance's clock. It is pacing, never correctness - the
-- snapshot's unique and its trigger decide.

CREATE TABLE fx.rate_snapshot (
    id               UUID           NOT NULL,
    source           TEXT           NOT NULL,
    base_currency    CHAR(3)        NOT NULL,
    quote_currency   CHAR(3)        NOT NULL,
    rate             NUMERIC(20,10) NOT NULL,
    observed_at      TIMESTAMPTZ    NOT NULL,
    received_at      TIMESTAMPTZ    NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT rate_snapshot_pk PRIMARY KEY (id),
    CONSTRAINT rate_snapshot_one_per_observation UNIQUE (source, base_currency, quote_currency, observed_at),
    CONSTRAINT rate_snapshot_source_shape CHECK (source ~ '^[a-z][a-z0-9.-]{0,63}$'),
    CONSTRAINT rate_snapshot_rate_is_positive CHECK (rate > 0),
    -- The declaration's ten canonical pairs, and no other: a pair outside it has no row.
    CONSTRAINT rate_snapshot_pair_is_canonical CHECK ((base_currency, quote_currency) IN (
        ('EUR', 'GBP'), ('EUR', 'USD'), ('EUR', 'JPY'), ('EUR', 'BHD'),
        ('GBP', 'USD'), ('GBP', 'JPY'), ('GBP', 'BHD'),
        ('USD', 'JPY'), ('USD', 'BHD'),
        ('BHD', 'JPY'))),
    -- A source whose clock runs ahead would otherwise plant an observation every honest later one
    -- is "older" than, freezing the pair; a minute of tolerance, judged against the database.
    CONSTRAINT rate_snapshot_not_observed_in_the_future CHECK (observed_at <= received_at + interval '1 minute')
);

COMMENT ON TABLE fx.rate_snapshot IS
    'The independent reference rate (P9-TSK-005, ADR-0075, INV-FX-02): append-only; received_at is the database clock, forced; stored only when newer than the pair''s latest observation (namespace 6, every writer). Never executable - read fresh on the database clock for plausibility and disclosure only.';

-- Serves "the pair's latest observation" - the freshness read and the trigger's own check.
CREATE INDEX rate_snapshot_latest ON fx.rate_snapshot (source, base_currency, quote_currency, observed_at DESC);

CREATE OR REPLACE FUNCTION fx.rate_snapshot_is_newer_than_latest()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Namespace 6: every writer of one (source, pair) serialises here, raw SQL included.
    PERFORM pg_advisory_xact_lock(
        6, hashtext(NEW.source || ':' || NEW.base_currency || '/' || NEW.quote_currency));
    -- The database's clock, whatever the writer supplied.
    NEW.received_at := statement_timestamp();
    IF EXISTS (
            SELECT 1 FROM fx.rate_snapshot s
             WHERE s.source = NEW.source
               AND s.base_currency = NEW.base_currency
               AND s.quote_currency = NEW.quote_currency
               AND s.observed_at >= NEW.observed_at) THEN
        -- Equal (a duplicate) or older (a replay): not stored. Silent by design - a stuck feed
        -- is not an error to the writer, it is a pair whose latest row ages (the age gauge).
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rate_snapshot_is_newer_than_latest
    BEFORE INSERT ON fx.rate_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION fx.rate_snapshot_is_newer_than_latest();

CREATE OR REPLACE FUNCTION fx.rate_snapshot_is_immutable()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a reference rate snapshot is evidence: it is never updated or deleted (P9-TSK-005)';
END;
$$;

CREATE TRIGGER rate_snapshot_is_immutable
    BEFORE UPDATE OR DELETE ON fx.rate_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION fx.rate_snapshot_is_immutable();

GRANT SELECT, INSERT ON fx.rate_snapshot TO finapp_app;

CREATE TABLE fx.rate_fetch_permit (
    source           TEXT        NOT NULL,
    last_attempt_at  TIMESTAMPTZ NOT NULL,
    attempts         INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT rate_fetch_permit_pk PRIMARY KEY (source),
    CONSTRAINT rate_fetch_permit_source_shape CHECK (source ~ '^[a-z][a-z0-9.-]{0,63}$'),
    CONSTRAINT rate_fetch_permit_attempts_counted CHECK (attempts >= 1),
    CONSTRAINT rate_fetch_permit_attempt_after_birth CHECK (last_attempt_at >= created_at)
);

COMMENT ON TABLE fx.rate_fetch_permit IS
    'Pacing for the leaderless reference fetch (P9-TSK-005): the last attempt per source, stamped by the database inside a conditional upsert that strictly advances it - never a correctness arbiter; fx.rate_snapshot''s unique and trigger are.';

CREATE OR REPLACE FUNCTION fx.rate_fetch_permit_moves_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source IS DISTINCT FROM OLD.source
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a rate fetch permit''s identity is frozen (P9-TSK-005)';
    END IF;
    IF NEW.last_attempt_at <= OLD.last_attempt_at THEN
        RAISE EXCEPTION 'a rate fetch permit only moves strictly forward (P9-TSK-005)';
    END IF;
    IF NEW.attempts <= OLD.attempts THEN
        RAISE EXCEPTION 'a rate fetch permit''s attempts only grow (P9-TSK-005)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rate_fetch_permit_moves_forward_only
    BEFORE UPDATE ON fx.rate_fetch_permit
    FOR EACH ROW
    EXECUTE FUNCTION fx.rate_fetch_permit_moves_forward_only();

CREATE OR REPLACE FUNCTION fx.rate_fetch_permit_is_never_deleted()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'a rate fetch permit is never deleted: it is the herd''s memory of its last attempt (P9-TSK-005)';
END;
$$;

CREATE TRIGGER rate_fetch_permit_is_never_deleted
    BEFORE DELETE ON fx.rate_fetch_permit
    FOR EACH ROW
    EXECUTE FUNCTION fx.rate_fetch_permit_is_never_deleted();

GRANT SELECT, INSERT ON fx.rate_fetch_permit TO finapp_app;
GRANT UPDATE (last_attempt_at, attempts) ON fx.rate_fetch_permit TO finapp_app;
