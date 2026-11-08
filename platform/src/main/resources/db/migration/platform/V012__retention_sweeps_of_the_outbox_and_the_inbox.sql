-- The retention sweeps' one path to deleting (P15-TSK-029, C12; DATA_MIGRATIONS.md sections 8 and 9).
--
-- WHAT THIS MIGRATION DOES
--   Two indexes, one per sweep predicate. Two SECURITY DEFINER functions, each deleting one batch of rows past its bound.
--   And the application role's DELETE on both tables revoked (V008 granted it for this sweep), so a deletion from either
--   table can only be one of these two functions' and only of a row its predicate selects.
--
-- WHAT IS NEVER SWEPT
--   A row is selected on an ALLOW-LIST, never on "anything not in these states".
--     * The outbox: published_at IS NOT NULL. Pending and abandoned rows have it NULL, and any state a later migration adds
--       that is not "published" has it NULL too, so it stays.
--     * The inbox has no state column: every row is a handling that committed (V007). A state added to this table would
--       live in a column this predicate cannot see, so the function carries the list of columns it was reviewed against, and
--       a column outside the list stops the sweep. It deletes nothing, it raises, and the schedule counts a failure, until a
--       migration reviews that column and says which of its values are terminal. An unknown state is never selected.
--     * platform.idempotency_record is never named here (P15-TSK-002, C15, on debt row #61:
--       journal_entry.idempotency_scope is not UNIQUE, so that record is posting once-ness's second rank).
--   Nothing financial is in either table (INV-EVT-02, V005, V007), so INV-HIST-01 is not touched.
--
-- THE INBOX'S BOUND IS TWO CONJUNCTS, AND BOTH ARE FLOORS
--   expires_at < now(): the instant the server computed at write, on the one clock every instance shares (ADR-0014).
--   processed_at < now() - retain_for_ms: the retention in force NOW, finapp.inbox.retention as the caller read it. Raising
--   that setting keeps rows written under the old value; without this conjunct they would go at their old expiry and a
--   redelivery inside the new window would be a second effect (INV-IDEM-04). A row must pass both, so the conjunct only ever
--   narrows the set: processed_at is the application's clock, and its skew can delay a deletion but cannot start one before
--   expires_at. A NULL in either column is never selected.
--
-- N SWEEPERS
--   FOR UPDATE SKIP LOCKED in a materialised CTE: ten sweepers lock ten disjoint batches and a held row is passed over, not
--   waited for (ADR-0014). The CTE is what keeps the LIMIT honest: a plain "DELETE ... WHERE key IN (SELECT ... LIMIT n FOR
--   UPDATE SKIP LOCKED)" can re-run its subquery once per row and delete more than n. Under READ COMMITTED the predicate is
--   re-evaluated on the locked row, so a row a concurrent writer moved back out of its terminal state no longer qualifies. Each
--   call is one batch in the caller's one transaction: a crash rolls the batch back and the next tick takes it.
--
-- THE GRANTS
--   SECURITY DEFINER runs as this schema's owner, the migrator, which is why the search_path is pinned; EXECUTE goes to the
--   application role alone (credit.consume_decision's shape, ADR-0091). The caller supplies the bound, so a caller passing a
--   tiny one can reach no more than a published outbox row or an inbox record past its written expiry: never a pending row,
--   and never a dedupe record inside the window it was written with. The REVOKE refuses no earlier release: no production
--   statement has ever deleted from either table (P15-TSK-005's lint, when a release tag exists, reads this file).
--
-- THE INDEXES ARE BUILT IN THIS TRANSACTION, NOT CONCURRENTLY
--   DATA_MIGRATIONS.md section 4.4 and ledger V029's precedent: no production table exists yet, and a CONCURRENTLY build needs
--   a non-transactional migration that can leave an invalid index behind. A real deployment with large tables builds these
--   ahead of the migration, by the same names.
--
--   Each is the shape its query needs: the query orders by the indexed column and stops at the batch. BRIN (V007's other
--   candidate) cannot serve that order. The outbox's is partial on the same condition as the predicate, so a pending row
--   costs it nothing.

CREATE INDEX outbox_event_sweepable
    ON platform.outbox_event (published_at)
    WHERE published_at IS NOT NULL;

CREATE INDEX inbox_message_sweepable
    ON platform.inbox_message (expires_at);

CREATE FUNCTION platform.sweep_published_outbox_events(retain_for_ms bigint, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF retain_for_ms IS NULL OR retain_for_ms < 1 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'a sweep names a positive bound and a batch of 1 to 1000 (P15-TSK-029)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    WITH doomed AS (
        SELECT o.event_id
          FROM platform.outbox_event o
         WHERE o.published_at IS NOT NULL
           AND o.published_at < now() - (retain_for_ms * INTERVAL '1 millisecond')
         ORDER BY o.published_at
         LIMIT batch_size
           FOR UPDATE OF o SKIP LOCKED
    )
    DELETE FROM platform.outbox_event e
     USING doomed
     WHERE e.event_id = doomed.event_id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION platform.sweep_published_outbox_events(bigint, integer) IS
    'The only path to deleting from outbox_event (P15-TSK-029): one batch of published rows older than the bound, on the '
    'database clock, rows another sweeper holds skipped. SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to '
    'the application role only.';

CREATE FUNCTION platform.sweep_expired_inbox_messages(retain_for_ms bigint, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_deleted integer;
BEGIN
    IF retain_for_ms IS NULL OR retain_for_ms < 1 OR batch_size IS NULL OR batch_size NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'a sweep names a positive bound and a batch of 1 to 1000 (P15-TSK-029)'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = 'platform.inbox_message'::regclass
           AND a.attnum > 0
           AND NOT a.attisdropped
           AND a.attname <> ALL (ARRAY['consumer', 'dedupe_key', 'message_type', 'correlation_id', 'processed_at', 'expires_at']::name[])
    ) THEN
        RAISE EXCEPTION 'platform.inbox_message has a column this sweep was not reviewed against, which may carry a state: nothing is swept (P15-TSK-029)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    WITH doomed AS (
        SELECT m.consumer, m.dedupe_key
          FROM platform.inbox_message m
         WHERE m.expires_at < now()
           AND m.processed_at < now() - (retain_for_ms * INTERVAL '1 millisecond')
         ORDER BY m.expires_at
         LIMIT batch_size
           FOR UPDATE OF m SKIP LOCKED
    )
    DELETE FROM platform.inbox_message i
     USING doomed
     WHERE i.consumer = doomed.consumer
       AND i.dedupe_key = doomed.dedupe_key;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION platform.sweep_expired_inbox_messages(bigint, integer) IS
    'The only path to deleting from inbox_message (P15-TSK-029): one batch of records past both their written expiry and the '
    'retention in force, on the database clock, rows another sweeper holds skipped; an unreviewed column of the table stops '
    'it. SECURITY DEFINER as the migrator, search_path pinned; EXECUTE to the application role only.';

REVOKE ALL ON FUNCTION platform.sweep_published_outbox_events(bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.sweep_expired_inbox_messages(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.sweep_published_outbox_events(bigint, integer) TO finapp_app;
GRANT EXECUTE ON FUNCTION platform.sweep_expired_inbox_messages(bigint, integer) TO finapp_app;

REVOKE DELETE ON platform.outbox_event FROM finapp_app;
REVOKE DELETE ON platform.inbox_message FROM finapp_app;
