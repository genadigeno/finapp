-- The soak's tables, read every 60 s during a load whose profile asks for it ("soak": true; P16-TSK-008, PHASE_16_PLAN.md sections 6.5
-- and 6.7, OPERATIONS_RUNBOOK.md section 11.48) by the test-only server's superuser over the pod's socket, in the finapp database. Each
-- row starts with the name of the file kind-rehearsal/load.sh appends it to (load/pg-soak-tables.tsv), behind the sample's time, as
-- statistics.sql's do. statistics.sql reads the fifteen busiest tables, whichever they are; these are named, so the append-heavy and the
-- update-heavy tables of section 6.7 are read at every sample whatever their rank: the journal, the balance projection, the outbox, the
-- inbox, the idempotency claims, the audit trail, the window counter (unlogged: a restart empties it), the payment attempts and their
-- history, and the sweeps' failure records (P16-TSK-017's side tables). Live and dead rows, the writes by kind, the vacuum and analyze
-- runs, the heap's, the indexes' and the whole relation's bytes: growth and bloat are read as differences. No row of any table is read.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'
\pset null ''

SELECT 'soak-tables', t.schemaname || '.' || t.relname, t.n_live_tup, t.n_dead_tup, t.n_tup_ins, t.n_tup_upd, t.n_tup_hot_upd,
       t.n_tup_del, t.autovacuum_count, t.autoanalyze_count, t.vacuum_count, pg_relation_size(t.relid), pg_indexes_size(t.relid),
       pg_total_relation_size(t.relid), coalesce(to_char(t.last_autovacuum, 'HH24:MI:SS'), '-')
  FROM pg_stat_user_tables t
 WHERE (t.schemaname, t.relname) IN (('ledger', 'journal_entry'), ('ledger', 'journal_line'), ('ledger', 'account_balance'),
                                     ('platform', 'outbox_event'), ('platform', 'inbox_message'), ('platform', 'idempotency_record'),
                                     ('platform', 'audit_record'), ('platform', 'window_counter'), ('payments', 'payment_attempt'),
                                     ('payments', 'payment_attempt_event'), ('payments', 'sweep_row_failure'),
                                     ('merchant', 'sweep_row_failure'))
 ORDER BY 2;

-- Every table's bytes together, and the WAL's position, so a sample's growth and WAL volume are differences of the same read.
SELECT 'soak-database', pg_database_size(current_database()), pg_current_wal_lsn() - '0/0'::pg_lsn;
