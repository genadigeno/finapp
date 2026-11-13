-- One journal entry per posting scope, for ever (P16-TSK-011; THREAT_MODEL.md section 6 item 18, debt row #61; INV-IDEM-01, INV-LED-04; ADR-0141).
-- finapp-contract: first-conforming-release=ANY; waives=UNIQUE,CHECK; data-fix=V036; reason=V036 counts the duplicated posting scopes as a row; the CHECK stops this file before the index at any count but zero, and no release ever writes two entries under one scope
--
-- WHY
--   journal_entry.idempotency_scope is the scope-and-key of the command that wrote the entry (V004). Until this migration it was not
--   UNIQUE, so the idempotency record (platform V002) was the posting's only arbiter twice over: the command's and the journal's. A
--   record swept away would let a re-presented posting key claim afresh and post a second entry. From here the journal arbitrates
--   by itself: whatever happens to the record, one scope has at most one entry. Platform V016's sweep refuses to run while this
--   index is absent or invalid.
--
-- WHAT IT DOES, IN ORDER
--   1. The census's CHECK: every census row (V036's, taken in its own transaction just before this one) must say zero duplicated
--      scopes (a count that is unknown, a null, fails it too). If one does not, this statement fails with the constraint's name and no row, the migration rolls back whole, the index
--      is never built and nothing is deleted. The duplicates are a finding for a person (OPERATIONS_RUNBOOK.md section 11.56), and
--      this environment stays on the release before, which never sweeps.
--   2. The unique index. Built over every existing entry, so it also re-proves the census for any entry written between the two
--      migrations (a duplicate there fails this build, and is listed by the same query).
--
-- WHAT EVERY RELEASE ALREADY DOES
--   Every writer of the journal is the ledger (INV-LED-04): PostingService and ReversalService under the idempotency claim of their
--   key, AdjustmentService once per proposal under its row lock. None writes two entries under one scope, so the index refuses no
--   write a release makes: hence first-conforming-release=ANY. What changes with this release is the answer to a write the index
--   refuses: JdbcJournalEntryStore inserts ON CONFLICT (idempotency_scope) DO NOTHING and the posting answers the recorded entry. A
--   release before it would meet the index only through a record removed by hand, and would fail that posting rather than double it.
--
-- AN INDEX, NOT A CONSTRAINT
--   CREATE UNIQUE INDEX holds SHARE on the journal for the build: reads go on and every insert waits. ALTER TABLE ... ADD UNIQUE
--   would hold ACCESS EXCLUSIVE, stopping reads as well. ON CONFLICT (idempotency_scope) infers the index as its arbiter. Not
--   deferrable, because ON CONFLICT cannot arbitrate on a deferrable one.
--
-- IN THIS MIGRATION'S TRANSACTION, NOT CONCURRENTLY
--   DATA_MIGRATIONS.md section 4.4 and V029's precedent: the build refuses a non-transactional migration until the migrator gains a
--   reviewed mode for one. Every posting waits for the build: E64 measures that stop on a soak's journal (THREAT_MODEL.md section 6
--   item 42 (b) is the same question for a plain index). A deployment over a large live journal builds this index ahead of the
--   migration, CONCURRENTLY, by hand and by this name; IF NOT EXISTS then finds it. It does not look at what it finds, so such a
--   build must have finished valid (platform V016's sweep checks that it is unique, valid and of this shape before every batch).

ALTER TABLE ledger.posting_scope_census
    ADD CONSTRAINT posting_scope_census_found_no_duplicate
        CHECK (coalesce(duplicated_scopes, -1) = 0 AND coalesce(surplus_entries, -1) = 0 AND entries_read IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS journal_entry_one_per_idempotency_scope
    ON ledger.journal_entry (idempotency_scope);

COMMENT ON INDEX ledger.journal_entry_one_per_idempotency_scope IS
    'One journal entry per command scope-and-key, for ever (P16-TSK-011, INV-IDEM-01): the posting''s arbiter whatever becomes of its '
    'idempotency record. The arbiter of JdbcJournalEntryStore''s ON CONFLICT (idempotency_scope) DO NOTHING.';
