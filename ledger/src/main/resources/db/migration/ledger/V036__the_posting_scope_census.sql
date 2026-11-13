-- The census the posting scope's uniqueness is judged by (P16-TSK-011; THREAT_MODEL.md section 6 item 18, debt row #61; INV-IDEM-01,
-- INV-LED-04; ADR-0141).
--
-- WHY
--   journal_entry.idempotency_scope ties an entry to the command that wrote it, and V037 makes it UNIQUE so that a posting is once
--   for ever, whatever becomes of its idempotency record. A unique index judges every row the journal already holds, and no journal
--   row can be deleted or edited (V004, INV-HIST-01), so a duplicate already there could never be removed to let it build. This
--   migration counts the duplicates first, on every database the release reaches, and keeps the count as a row: zero is the evidence
--   V037 stands on, and anything else stops V037 at its first statement (the census's CHECK), before the index, with no row read
--   into any message.
--
-- WHAT IT RECORDS, AND ONLY THAT
--   One row per run: when (the database's clock), how many entries and distinct scopes it read, how many scopes are held by more
--   than one entry and how many entries are surplus to one per scope. Counts only, never a scope, a key, an entry or an amount, so
--   nothing a client wrote reaches the migrator's log or this table. Listing the duplicates is OPERATIONS_RUNBOOK.md section 11.56's
--   query, run by a person when the count is not zero.
--
-- WHY THE TABLE HAS NO CONSTRAINT
--   So the census always records what it found: a CHECK here would refuse the row that says "duplicates", and the finding would be
--   lost with the failed migration. The judgement is V037's, a separate transaction, so this row stands when V037 stops. The table
--   is append-only for every role by trigger (DELETE, UPDATE and TRUNCATE refused, the owner included): a census is evidence.
--
-- GRANTS
--   None. The application never reads or writes it; the migrator owns it, and a person reads it as the migrator or the superuser.
--
-- LOCKS
--   The census reads ledger.journal_entry once, under ACCESS SHARE: writers are not blocked. Its cost is one scan of the journal,
--   measured with the unique build on a soak's journal by E64.

CREATE TABLE ledger.posting_scope_census (
    taken_at          timestamptz,
    entries_read      bigint,
    scopes_read       bigint,
    duplicated_scopes bigint,
    surplus_entries   bigint
);

COMMENT ON TABLE ledger.posting_scope_census IS
    'One row per run of the posting scope census (P16-TSK-011, V036): counts only. V037 refuses to make journal_entry.idempotency_scope '
    'unique unless every row says zero duplicated scopes. Append-only for every role.';

CREATE FUNCTION ledger.posting_scope_census_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'ledger.posting_scope_census is evidence and is never edited or deleted (P16-TSK-011): a later census is a new row'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER posting_scope_census_is_append_only
    BEFORE UPDATE OR DELETE ON ledger.posting_scope_census
    FOR EACH ROW
    EXECUTE FUNCTION ledger.posting_scope_census_is_append_only();

CREATE TRIGGER posting_scope_census_is_never_truncated
    BEFORE TRUNCATE ON ledger.posting_scope_census
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.history_is_never_truncated();

INSERT INTO ledger.posting_scope_census (taken_at, entries_read, scopes_read, duplicated_scopes, surplus_entries)
SELECT pg_catalog.statement_timestamp(),
       coalesce(sum(per_scope.entries), 0),
       count(*),
       count(*) FILTER (WHERE per_scope.entries > 1),
       coalesce(sum(per_scope.entries - 1), 0)
  FROM (SELECT entry.idempotency_scope, count(*) AS entries
          FROM ledger.journal_entry entry
         GROUP BY entry.idempotency_scope) per_scope;
