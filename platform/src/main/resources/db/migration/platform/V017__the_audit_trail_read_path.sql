-- The audit trail's read path (P17-TSK-017; finapp-admin-panel ADR-C134 decisions 1, 4 and 5).
--
-- WHY
--   GET /v1/operator/audit-records reads a required, bounded window newest first, (occurred_at, audit_id) descending, the id
--   breaking the tie, walked by keyset from the cursor: audit_record_by_time serves it as one index range per page, with no sort.
--   A target's history - the console's History tab on every detail page - is the same read with target_type and target_id:
--   audit_record_by_target_time serves it, the existing (target_type, target_id, audit_id) index ordering by the writer-minted id
--   rather than the stamp the read orders by. An actor's filter rides V009's (actor_id, audit_id). The plans are recorded in
--   OPERATIONS_RUNBOOK.md section 11.48 from AuditTrailDatabaseTest.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant: the application role keeps SELECT and INSERT on the table and
--   nothing else, so the read path can neither update nor delete a record (INV-HIST-03, ADR-0010). Expand-only: the previous
--   release neither reads nor needs them.
--
-- LOCKS: BUILT HERE, OR AHEAD BY THE SAME NAMES
--   In the migration's transaction, not CONCURRENTLY: the build refuses a migration outside a transaction
--   (EveryMigrationRunsInATransactionTest; DATA_MIGRATIONS.md section 4.4), because a failed concurrent build leaves an INVALID index no
--   deployed environment can clear. Each build holds a SHARE lock on platform.audit_record until this migration commits, and every
--   audited act inserts there, so on a table of any size the indexes are built AHEAD, by an operator, CONCURRENTLY and by these
--   names (OPERATIONS_RUNBOOK.md section 11.48), and IF NOT EXISTS makes this migration a no-op over them.

CREATE INDEX IF NOT EXISTS audit_record_by_time
    ON platform.audit_record (occurred_at, audit_id);

CREATE INDEX IF NOT EXISTS audit_record_by_target_time
    ON platform.audit_record (target_type, target_id, occurred_at, audit_id);
