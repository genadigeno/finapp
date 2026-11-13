-- PostgreSQL's own statistics, read every 10 s during a load (P16-TSK-006, PHASE_16_PLAN.md section 5.2) by the test-only server's
-- superuser over the pod's socket, in the finapp database. Each row starts with the name of the file kind-rehearsal/load.sh appends it to
-- (load/pg-<name>.tsv), behind the sample's time. Counters are cumulative: the reader takes differences. Nothing here reads a row of the
-- platform's data, only the server's views of itself.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

-- Sessions by state and wait: who is running, idle in a transaction, or waiting on a lock, I/O or the client.
SELECT 'activity', coalesce(datname, '-'), coalesce(usename, '-'), coalesce(state, '-'), coalesce(wait_event_type, '-'),
       coalesce(wait_event, '-'), count(*)
  FROM pg_stat_activity
 WHERE backend_type = 'client backend'
 GROUP BY 2, 3, 4, 5, 6
 ORDER BY 2, 3, 4, 5, 6;

-- Locks waited for, by kind, beside every lock held.
SELECT 'locks', count(*) FILTER (WHERE NOT granted), count(*) FILTER (WHERE NOT granted AND locktype = 'transactionid'),
       count(*) FILTER (WHERE NOT granted AND locktype = 'tuple'), count(*) FILTER (WHERE NOT granted AND locktype = 'relation'),
       count(*) FILTER (WHERE NOT granted AND locktype = 'advisory'), count(*)
  FROM pg_locks;

-- The database: commits, rollbacks, deadlocks, temporary files, the buffer cache and the rows written.
SELECT 'database', xact_commit, xact_rollback, deadlocks, temp_files, temp_bytes, blks_read, blks_hit, tup_inserted, tup_updated,
       tup_deleted, conflicts
  FROM pg_stat_database
 WHERE datname = 'finapp';

-- WAL volume, and the checkpointer and background writer behind it.
SELECT 'wal', wal_records, wal_fpi, wal_bytes, wal_buffers_full FROM pg_stat_wal;
SELECT 'checkpointer', num_timed, num_requested, buffers_written, write_time, sync_time FROM pg_stat_checkpointer;
SELECT 'bgwriter', buffers_clean, maxwritten_clean, buffers_alloc FROM pg_stat_bgwriter;

-- The busiest tables: their live and dead rows and what autovacuum has done to them.
SELECT 'tables', schemaname || '.' || relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del,
       autovacuum_count, autoanalyze_count, coalesce(to_char(last_autovacuum, 'HH24:MI:SS'), '-')
  FROM pg_stat_user_tables
 ORDER BY n_tup_ins + n_tup_upd + n_tup_del DESC, schemaname, relname
 LIMIT 15;
