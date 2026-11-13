-- The top statements by time, read every 10 s during a load (P16-TSK-006, PHASE_16_PLAN.md section 5.2) from pg_stat_statements, which
-- the kind overlay's test-only server preloads and kind-rehearsal/load.sh creates in the server's maintenance database (postgres), so
-- the finapp database gains no object. The view there reads every database's statements; only finapp's are taken. Statements are
-- normalised by the extension, so no value of any row appears. The final snapshot, with each statement's text, is load.sh's.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

SELECT 'statements', s.queryid, s.calls, round(s.total_exec_time::numeric, 1), round(s.mean_exec_time::numeric, 3), s.rows,
       s.shared_blks_hit, s.shared_blks_read, s.temp_blks_written
  FROM pg_stat_statements s
  JOIN pg_database d ON d.oid = s.dbid
 WHERE d.datname = 'finapp'
 ORDER BY s.total_exec_time DESC
 LIMIT 25;

-- Every statement of finapp's together (P16-TSK-007): the database's time and calls, cumulative, so a stage's database time is a difference.
SELECT 'statements_total', coalesce(sum(s.calls), 0), round(coalesce(sum(s.total_exec_time), 0)::numeric, 1)
  FROM pg_stat_statements s
  JOIN pg_database d ON d.oid = s.dbid
 WHERE d.datname = 'finapp';

-- The longest single execution of each of finapp's statements since the load began (P16-TSK-022, ADR-0139): the measured longest
-- legitimate statement, which the data source's socket timeout must stay above (a statement that arms no bound of its own is measured
-- here, the rest are bounded by the code). Cumulative, as the extension keeps it: the last sample is the run's.
SELECT 'statements_longest', s.queryid, s.calls, round(s.max_exec_time::numeric, 1)
  FROM pg_stat_statements s
  JOIN pg_database d ON d.oid = s.dbid
 WHERE d.datname = 'finapp'
 ORDER BY s.max_exec_time DESC
 LIMIT 10;
