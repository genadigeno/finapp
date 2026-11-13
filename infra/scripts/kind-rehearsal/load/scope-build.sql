-- The posting scope's census and the two builds P16-TSK-011's migrations make, during the soak's last stage, as the migrator would run
-- them (E64; ADR-0141; OPERATIONS_RUNBOOK.md section 11.56; THREAT_MODEL.md section 6 item 42 (b) asks the same of a plain index). Run by
-- kind-rehearsal/load.sh (load_scope_build) as the test-only server's superuser on the run's own server, never by a migration and never
-- anywhere else, after the soak's own build on ledger.journal_line (load/index-build.sql) has finished.
--
-- Each step is timed from its start to its commit: the census's one read of the journal (ledger V036's statement, as a SELECT: it writes
-- no census row here), then a unique index on journal_entry's idempotency_scope (V037's) and a partial index on idempotency_record's
-- expiry (platform V016's), each a throwaway twin under its own name, each holding SHARE on its table for its build, so every posting,
-- and every keyed command's claim, waits that long. The migrator's lock_timeout is passed in as :lock_timeout; a build past ten minutes
-- is cancelled by its own statement_timeout, the experiment's stop. Each twin is dropped at once. Nothing here reads a row's values: the
-- census's output is counts.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'

SELECT 'census-start', round(extract(epoch FROM clock_timestamp())::numeric, 3),
       (SELECT reltuples::bigint FROM pg_class WHERE oid = 'ledger.journal_entry'::regclass), pg_relation_size('ledger.journal_entry');
SELECT 'census', round(extract(epoch FROM clock_timestamp())::numeric, 3), coalesce(sum(per_scope.entries), 0), count(*),
       count(*) FILTER (WHERE per_scope.entries > 1)
  FROM (SELECT entry.idempotency_scope, count(*) AS entries FROM ledger.journal_entry entry GROUP BY entry.idempotency_scope) per_scope;

SELECT 'journal-start', round(extract(epoch FROM clock_timestamp())::numeric, 3);
BEGIN;
SET LOCAL lock_timeout = :'lock_timeout';
SET LOCAL statement_timeout = '600s';
CREATE UNIQUE INDEX rehearsal_scope_probe ON ledger.journal_entry (idempotency_scope);
COMMIT;
SELECT 'journal-built', round(extract(epoch FROM clock_timestamp())::numeric, 3), pg_relation_size('ledger.rehearsal_scope_probe');
DROP INDEX ledger.rehearsal_scope_probe;

SELECT 'records-start', round(extract(epoch FROM clock_timestamp())::numeric, 3),
       (SELECT reltuples::bigint FROM pg_class WHERE oid = 'platform.idempotency_record'::regclass),
       pg_relation_size('platform.idempotency_record');
BEGIN;
SET LOCAL lock_timeout = :'lock_timeout';
SET LOCAL statement_timeout = '600s';
CREATE INDEX rehearsal_sweepable_probe ON platform.idempotency_record (expires_at) WHERE state IN ('COMPLETED', 'FAILED');
COMMIT;
SELECT 'records-built', round(extract(epoch FROM clock_timestamp())::numeric, 3), pg_relation_size('platform.rehearsal_sweepable_probe');
DROP INDEX platform.rehearsal_sweepable_probe;

SELECT 'dropped', round(extract(epoch FROM clock_timestamp())::numeric, 3), count(*) FROM pg_class
 WHERE relname IN ('rehearsal_scope_probe', 'rehearsal_sweepable_probe');
