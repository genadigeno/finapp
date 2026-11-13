-- The session sweep's one path to deleting (P16-TSK-010; THREAT_MODEL.md section 6 item 18; DATA_RETENTION.md sections 1 and 2;
-- ADR-0030).
--
-- WHAT THIS MIGRATION DOES
--   One index and one SECURITY DEFINER function that deletes one batch of session rows that are dead and past a grace. The
--   application role is granted no DELETE on identity.session (V005 withheld it and nothing since has granted it): this function is
--   the only way a session row goes, and only a row its predicate selects.
--
-- NEVER A LIVE SESSION
--   A session is live while status = 'ACTIVE' AND idle_expires_at > now() AND absolute_expires_at > now(), judged on this database's
--   clock (V016, JdbcSessionStore's LIVE). The sweep selects idle_expires_at < now() - grace, with grace at least a minute, so a
--   selected row is past its idle bound and cannot be live. It cannot come back to life either: the only writes to a session row
--   are the touch, which requires the row live, and the revocations, which set its status and leave its bounds alone (JdbcSessionStore);
--   idle_expires_at <= absolute_expires_at (V005), so the idle bound is the earlier of the two and the one to wait on. A revoked
--   row keeps its idle bound, so it is swept a grace after that bound, never earlier. Under READ COMMITTED the predicate is judged
--   again on the locked row, so a row that changed between the read and the lock is judged as it now is.
--
-- AN ALLOW-LIST OF STATES, AND THE COLUMNS REVIEWED
--   status IN ('ACTIVE', 'REVOKED'): a state a later migration adds is never selected. And a column of identity.session this sweep
--   was not reviewed against stops it (55000, deleting nothing), because a new column may carry a hold this predicate cannot see:
--   the inbox sweep's rule (platform V012).
--
-- WHAT A DELETION COSTS
--   Operational state only (DATA_RETENTION.md section 1). The login, rotation and revocation of every session stay in the audit
--   trail, which nothing deletes; a session's audit records then name an id no row holds. The row's device label and timings, a
--   person's data, go with it.
--
-- N SWEEPERS
--   FOR UPDATE SKIP LOCKED in a MATERIALIZED CTE (V012's shape): ten sweepers delete ten disjoint batches, a held row is passed over,
--   and a crash rolls its batch back for the next tick.
--
-- THE INDEX IS BUILT IN THIS TRANSACTION, NOT CONCURRENTLY
--   It holds a SHARE lock on the table until this migration commits, so sign-ins wait for it: acceptable while no production data
--   exists. A real deployment with a large session table builds it ahead, by the same name, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS session_sweepable
    ON identity.session (idle_expires_at);

CREATE FUNCTION identity.sweep_expired_sessions(grace_ms bigint, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF grace_ms IS NULL OR grace_ms < 60000 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'the session sweep names a grace of a minute or more and a batch of 1 to 1000 (P16-TSK-010)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = 'identity.session'::regclass
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND a.attname <> ALL (ARRAY['id', 'identity_id', 'token_hash', 'assurance', 'status', 'issued_at', 'idle_expires_at',
                                       'absolute_expires_at', 'device', 'revoked_at', 'live_from']::name[])
    ) THEN
        RAISE EXCEPTION 'identity.session has a column the session sweep was not reviewed against, which may carry a hold: nothing is swept (P16-TSK-010)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    WITH doomed AS MATERIALIZED (
        SELECT s.id
          FROM identity.session s
         WHERE s.status IN ('ACTIVE', 'REVOKED')
           AND s.idle_expires_at < now() - (grace_ms * INTERVAL '1 millisecond')
         ORDER BY s.idle_expires_at
         LIMIT batch_size
           FOR UPDATE OF s SKIP LOCKED
    )
    DELETE FROM identity.session x
     USING doomed
     WHERE x.id = doomed.id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION identity.sweep_expired_sessions(bigint, integer) IS
    'The only path to deleting from identity.session (P16-TSK-010): one batch of ACTIVE or REVOKED rows whose idle bound passed more '
    'than the grace ago, on the database clock, so never a live session; rows another sweeper holds skipped; an unreviewed column '
    'stops it. SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to the application role only.';

REVOKE ALL ON FUNCTION identity.sweep_expired_sessions(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.sweep_expired_sessions(bigint, integer) TO finapp_app;
