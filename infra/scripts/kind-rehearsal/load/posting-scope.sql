-- The posting scope's uniqueness on the posting hot path (P16-TSK-011, E62; ADR-0141; OPERATIONS_RUNBOOK.md section 11.56). Read once
-- after a load whose profile has "contention": true (concurrent.json, E38's), by kind-rehearsal/load.sh (load_posting_scope), from the
-- server's maintenance database (postgres), where pg_stat_statements lives, and from finapp's statistics views. Statements are normalised
-- by the extension and the views hold counts, so no value of any row appears. On a tree before P16-TSK-011 (E38's control run) the
-- lookup and the conditional insert do not exist: statements.tsv's last snapshot is read instead, as section 11.56 says.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

-- Each statement of the posting's write set and of the idempotency claim, by its text: calls, total and mean time, rows.
SELECT 'statement', CASE
           WHEN s.query LIKE 'SELECT id FROM ledger.journal_entry WHERE idempotency_scope = %' THEN 'scope_lookup'
           WHEN s.query LIKE 'INSERT INTO ledger.journal_entry %ON CONFLICT (idempotency_scope) DO NOTHING%' THEN 'entry_insert'
           WHEN s.query LIKE 'INSERT INTO ledger.journal_line %' THEN 'line_insert'
           WHEN s.query LIKE 'INSERT INTO platform.idempotency_record %' THEN 'claim_insert'
           WHEN s.query LIKE 'UPDATE platform.idempotency_record SET state = %' THEN 'claim_complete'
       END, s.calls, round(s.total_exec_time::numeric, 1), round(s.mean_exec_time::numeric, 3), s.rows
  FROM pg_stat_statements s
  JOIN pg_database d ON d.oid = s.dbid
 WHERE d.datname = 'finapp'
   AND (s.query LIKE 'SELECT id FROM ledger.journal_entry WHERE idempotency_scope = %'
        OR s.query LIKE 'INSERT INTO ledger.journal_entry %'
        OR s.query LIKE 'INSERT INTO ledger.journal_line %'
        OR s.query LIKE 'INSERT INTO platform.idempotency_record %'
        OR s.query LIKE 'UPDATE platform.idempotency_record SET state = %')
 ORDER BY 2;
