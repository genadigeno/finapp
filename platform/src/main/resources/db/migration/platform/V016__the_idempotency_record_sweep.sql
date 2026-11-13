-- The idempotency record's sweep, and its one path to deleting (P16-TSK-011; THREAT_MODEL.md section 6 item 18, debt row #61;
-- DATA_MIGRATIONS.md section 8; INV-IDEM-01, INV-LIFE-03; ADR-0141).
-- finapp-contract: first-conforming-release=ANY; waives=REVOKE; reason=no release has ever deleted an idempotency record (NoIdempotencyRecordIsSweptTest held it), so no release uses the DELETE revoked here
--
-- WHAT THIS MIGRATION DOES
--   A partial index serving the sweep's predicate. One SECURITY DEFINER function deleting one batch of terminal records whose own
--   expiry passed more than a grace ago. And the application role's DELETE on the table revoked (V008 granted it for this sweep;
--   nothing ever used it), so a record can only leave through this function, and only when its predicate selects it.
--
-- WHY ONLY NOW
--   The record was the posting's second rank as well as its command's first: journal_entry.idempotency_scope was not UNIQUE, so a
--   swept record let a re-presented posting key post again (debt row #61, ruled by P15-TSK-002). Ledger V037 makes it unique. This
--   function refuses every batch (55000) unless that index stands - unique, valid, on exactly that one column, with no predicate or
--   expression - read from the catalogue before each batch, by shape and not by name. So a database where the ledger's migration has
--   not applied, or where the index was dropped or left invalid, keeps every record: the sweep fails loudly and deletes nothing. This
--   reads the ledger's catalogue entries and none of its rows.
--
-- WHAT IS NEVER SWEPT
--   Selected on an ALLOW-LIST: state IN ('COMPLETED', 'FAILED'). An IN_PROGRESS claim is a command whose outcome is unknown and is
--   resolved by the lease (V004, INV-LIFE-03), never by a sweep; any state a later migration adds is not in the list and stays. As
--   with the inbox (V012), the function carries the columns it was reviewed against, and a column outside the list stops it (55000),
--   since a later meaning could live in a column this predicate cannot see.
--   A record is kept until its expires_at - the retention its caller wrote, DATA_MIGRATIONS.md section 8's retry window - is more
--   than the grace behind the database's clock. Until it is swept it answers every retry as before (IdempotentExecutor does not read
--   expires_at), so a key is answered for its expiry plus the grace, never less.
--
-- THE GRACE HAS A FLOOR: ONE DAY
--   expires_at is written from an instance's clock (created_at plus the retention), and the instances' clocks are bounded, never equal
--   (ADR-0014). The function refuses a grace below 86400000 ms, a day (22023), so no setting and no caller can sweep a record within
--   a day of its expiry, whatever a skewed instance wrote. RetentionSweep.IDEMPOTENCY_GRACE_FLOOR refuses the same at start, held
--   equal to this figure by a test. The setting's default is 30 days (finapp.idempotency.sweep.grace): THREAT_MODEL.md section 6
--   item 63 says why, and what a sweep leaves to a command whose only arbiter is its record.
--
-- N SWEEPERS
--   FOR UPDATE SKIP LOCKED in a CTE written AS MATERIALIZED (V012's reasons): ten sweepers lock ten disjoint batches and a held row is
--   passed over. Under READ COMMITTED the predicate is re-evaluated on the locked row, and the DELETE repeats it. A terminal row is
--   frozen by V003, so nothing moves it back. Each call is one batch in its caller's transaction: a crash rolls it back for the next
--   tick.
--
-- THE GRANTS
--   SECURITY DEFINER runs as this schema's owner, the migrator; the search_path is pinned and every clock call qualified. EXECUTE to
--   the application role alone. The role keeps SELECT, INSERT and UPDATE, which the claim, the outcome and the takeover need. With
--   UPDATE a holder of its credential can move an in-flight claim's expiry back and complete it, and so sweep it a day later: the
--   same credential can already present any key it likes, and it cannot reach the journal, whose index keeps every posting once.
--   The REVOKE refuses no earlier release: no production statement has ever deleted from this table.
--
-- THE INDEX IS BUILT IN THIS TRANSACTION, NOT CONCURRENTLY
--   DATA_MIGRATIONS.md section 4.4 and V012's precedent: it holds SHARE on the table until this migration commits, so every keyed
--   command's claim waits for the build (E64 measures it beside the journal's). A deployment with a large table builds it ahead, by
--   this name, valid; IF NOT EXISTS then finds it. Partial on the predicate's states, so an in-flight claim costs it nothing; a
--   completion inserts one entry (that update was never heap-only: it already moves lease_expires_at, which V004 indexes).

CREATE INDEX IF NOT EXISTS idempotency_record_sweepable
    ON platform.idempotency_record (expires_at)
    WHERE state IN ('COMPLETED', 'FAILED');

CREATE FUNCTION platform.sweep_expired_idempotency_records(grace_ms bigint, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF grace_ms IS NULL OR grace_ms < 86400000 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'an idempotency sweep names a grace of at least a day past each record''s expiry and a batch of 1 to 1000 (P16-TSK-011)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_index i
          JOIN pg_catalog.pg_attribute a
            ON a.attrelid = i.indrelid
           AND a.attnum = i.indkey[0]
         WHERE i.indrelid = pg_catalog.to_regclass('ledger.journal_entry')
           AND i.indisunique
           AND i.indisvalid
           AND i.indisready
           AND i.indnatts = 1
           AND i.indpred IS NULL
           AND i.indexprs IS NULL
           AND a.attname = 'idempotency_scope'
           AND NOT a.attisdropped
    ) THEN
        RAISE EXCEPTION 'ledger.journal_entry.idempotency_scope is not unique, so an idempotency record is still a posting''s second rank: nothing is swept (P16-TSK-011)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = 'platform.idempotency_record'::pg_catalog.regclass
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND a.attname <> ALL (ARRAY['scope', 'idempotency_key', 'request_fingerprint', 'fingerprint_algorithm', 'state',
                                       'response_body', 'response_media_type', 'correlation_id', 'created_at', 'completed_at',
                                       'expires_at', 'lease_expires_at']::pg_catalog.name[])
    ) THEN
        RAISE EXCEPTION 'platform.idempotency_record has a column this sweep was not reviewed against, which may carry a state: nothing is swept (P16-TSK-011)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    WITH doomed AS MATERIALIZED (
        SELECT r.scope, r.idempotency_key
          FROM platform.idempotency_record r
         WHERE r.state IN ('COMPLETED', 'FAILED')
           AND r.expires_at < pg_catalog.now() - (grace_ms * INTERVAL '1 millisecond')
         ORDER BY r.expires_at
         LIMIT batch_size
           FOR UPDATE OF r SKIP LOCKED
    )
    DELETE FROM platform.idempotency_record d
     USING doomed
     WHERE d.scope = doomed.scope
       AND d.idempotency_key = doomed.idempotency_key
       AND d.state IN ('COMPLETED', 'FAILED')
       AND d.expires_at < pg_catalog.now() - (grace_ms * INTERVAL '1 millisecond');

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION platform.sweep_expired_idempotency_records(bigint, integer) IS
    'The only path to deleting from idempotency_record (P16-TSK-011): one batch of COMPLETED or FAILED records whose expiry is more '
    'than the grace (at least a day) behind the database clock, rows another sweeper holds skipped; refuses while '
    'ledger.journal_entry.idempotency_scope is not uniquely indexed, and at a column it was not reviewed against. SECURITY DEFINER as '
    'the migrator, search_path pinned; EXECUTE to the application role only.';

REVOKE ALL ON FUNCTION platform.sweep_expired_idempotency_records(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.sweep_expired_idempotency_records(bigint, integer) TO finapp_app;

REVOKE DELETE ON platform.idempotency_record FROM finapp_app;
