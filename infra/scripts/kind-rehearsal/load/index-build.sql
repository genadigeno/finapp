-- A transactional index build on the journal, during the soak's last stage, as a migration would run one (THREAT_MODEL.md section 6
-- item 42 (b); P16-TSK-008, PHASE_16_PLAN.md section 6.5, OPERATIONS_RUNBOOK.md section 11.48). Run by kind-rehearsal/load.sh
-- (load_index_build) as the test-only server's superuser on the run's own server, never by a migration and never anywhere else.
--
-- A plain CREATE INDEX takes SHARE on its table: every insert waits for as long as it builds, and an insert arriving while the build
-- waits for its lock queues behind that request too. The migrator bounds the wait for the lock (its lock_timeout, which
-- kind-rehearsal/load.sh reads from MigrationPlan.java and passes here as :lock_timeout), not the build once it holds it; a build past
-- ten minutes is cancelled by its own statement_timeout, the experiment's stop. So the write stop is measured from the moment the build
-- asks for its lock to its commit, beside the table's size and rows, and the posting latency over that window is the load's own (k6's
-- series, cut by these two times). ledger.journal_line is the
-- platform's largest and most written table; the index is the shape of the platform's own (an account and an id) and is dropped at once.
-- Nothing here reads a row's values.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'

SELECT 'before', round(extract(epoch FROM clock_timestamp())::numeric, 3), pg_relation_size('ledger.journal_line'),
       pg_total_relation_size('ledger.journal_line'), (SELECT reltuples::bigint FROM pg_class WHERE oid = 'ledger.journal_line'::regclass),
       current_setting('maintenance_work_mem');
BEGIN;
SET LOCAL lock_timeout = :'lock_timeout';
SET LOCAL statement_timeout = '600s';
CREATE INDEX rehearsal_build_probe ON ledger.journal_line (ledger_account_id, id);
COMMIT;
SELECT 'built', round(extract(epoch FROM clock_timestamp())::numeric, 3), pg_relation_size('ledger.rehearsal_build_probe');
DROP INDEX ledger.rehearsal_build_probe;
SELECT 'dropped', round(extract(epoch FROM clock_timestamp())::numeric, 3), count(*) FROM pg_class
 WHERE relname = 'rehearsal_build_probe';
