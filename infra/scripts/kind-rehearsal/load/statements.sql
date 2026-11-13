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
