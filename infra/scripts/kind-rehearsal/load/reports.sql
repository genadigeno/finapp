-- Report generation's statements, read before and after each of E65's report runs (P16-TSK-012, ADR-0142; OPERATIONS_RUNBOOK.md
-- section 11.57) in the server's maintenance database, where kind-rehearsal/load.sh keeps pg_stat_statements, as statements.sql reads
-- it. Only the statements finapp_reporting ran in the finapp database: report generation's own pool connects as that role and nothing
-- else does (ReportingPool), so a statement here is a report's read. Normalised by the extension: no value of any row appears. Each
-- statement's calls and time are cumulative, so a run's cost is the difference of the two reads, statement by statement.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

SELECT 'reporting', s.queryid, s.calls, round(s.total_exec_time::numeric, 3), s.rows, s.shared_blks_hit + s.shared_blks_read
  FROM pg_stat_statements s
  JOIN pg_database d ON d.oid = s.dbid
  JOIN pg_roles r ON r.oid = s.userid
 WHERE d.datname = 'finapp'
   AND r.rolname = 'finapp_reporting'
 ORDER BY s.queryid;

-- The role exists on this server: a read of nothing for a role that is missing would be a blind reader, not a cheap report.
SELECT 'role', count(*) FROM pg_roles WHERE rolname = 'finapp_reporting';
