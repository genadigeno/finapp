-- The cluster-wide counter behind the abuse limits (P15-TSK-022, THREAT_MODEL.md T1; DISTRIBUTED_EXECUTION.md section 3).
--
-- WHAT THIS IS
--   One row per scope, key and fixed window, incremented by a conditional upsert: exactly the limit is admitted across every
--   replica, because PostgreSQL arbitrates it, which is what a per-instance count cannot do (it multiplies the limit by the
--   instance count). The statement is WindowCounter's, and its window is read from this database's clock, never an instance's.
--   It is scope-agnostic: a per-source door is one scope, and a future per-identity count is another.
--
-- UNLOGGED, ON PURPOSE
--   A counter of unauthenticated requests is not a financial record (CLAUDE.md rule 12), so it pays nothing that financial records
--   pay for. An unlogged table's rows write no WAL, and a transaction that wrote only those waits on no fsync and no synchronous
--   standby: an admitted count adds a commit record and nothing else, and a journal entry's commit does not queue behind a
--   registration flood. A refused count adds nothing at all: its statement is read-only (WindowCounter), so it takes no row lock
--   and no transaction id.
--   The price: a crash or a failover truncates it. The cost of that is at most one extra window's budget for each key, once per
--   crash or failover.
--
-- BOUNDED BY ITS KEYS AND BY ITS WINDOWS
--   A row per source per window is attacker-sized, so the caller keys it on a 16-bit bucket of the source (SourceBucket), never the
--   address, and the table holds no address at all. A row is useless once its window ended, so the sweep below deletes it by the
--   row's own window_ends_at: no setting can delete a live window, and no bound needs a floor.
--   fillfactor 50 keeps an increment on its own page, so it is a HOT update and the index is not rewritten.
--
-- THE GRANTS
--   The application role increments rows and cannot delete one; only the function deletes, and only windows that have ended.

CREATE UNLOGGED TABLE platform.window_counter (
    scope          text        NOT NULL,
    counter_key    text        NOT NULL,
    window_start   timestamptz NOT NULL,
    window_ends_at timestamptz NOT NULL,
    admitted       integer     NOT NULL,

    CONSTRAINT window_counter_pk PRIMARY KEY (scope, counter_key, window_start),
    CONSTRAINT window_counter_scope_shape CHECK (scope ~ '^[a-z][a-z0-9.-]{0,62}$'),
    CONSTRAINT window_counter_key_shape CHECK (counter_key ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT window_counter_window_ends_after_it_starts CHECK (window_ends_at > window_start),
    CONSTRAINT window_counter_admitted_at_least_one CHECK (admitted >= 1)
) WITH (fillfactor = 50, autovacuum_vacuum_scale_factor = 0.05);

COMMENT ON TABLE platform.window_counter IS
    'How many requests each key was admitted in a fixed window (P15-TSK-022). UNLOGGED: not a financial record, and a crash resets it '
    'by design. Keyed on a bounded bucket of the source, never an address. A row is written by WindowCounter alone and deleted by '
    'sweep_expired_window_counters alone.';

CREATE INDEX window_counter_sweepable
    ON platform.window_counter (window_ends_at);

GRANT SELECT, INSERT, UPDATE ON platform.window_counter TO finapp_app;

CREATE FUNCTION platform.sweep_expired_window_counters(retain_for_ms bigint, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF retain_for_ms IS NULL OR retain_for_ms < 1 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'a sweep names a positive bound and a batch of 1 to 1000 (P15-TSK-022)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    WITH doomed AS MATERIALIZED (
        SELECT c.scope, c.counter_key, c.window_start
          FROM platform.window_counter c
         WHERE c.window_ends_at < now() - (retain_for_ms * INTERVAL '1 millisecond')
         ORDER BY c.window_ends_at
         LIMIT batch_size
           FOR UPDATE OF c SKIP LOCKED
    )
    DELETE FROM platform.window_counter w
     USING doomed
     WHERE w.scope = doomed.scope
       AND w.counter_key = doomed.counter_key
       AND w.window_start = doomed.window_start;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION platform.sweep_expired_window_counters(bigint, integer) IS
    'The only path to deleting from window_counter (P15-TSK-022): one batch of windows that ended before the bound, on the database '
    'clock, rows another sweeper holds skipped. SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to the application role only.';

REVOKE ALL ON FUNCTION platform.sweep_expired_window_counters(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.sweep_expired_window_counters(bigint, integer) TO finapp_app;
