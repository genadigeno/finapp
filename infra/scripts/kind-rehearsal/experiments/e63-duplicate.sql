-- E63's check of the journal's arbiter on the swept journal (P16-TSK-011; ledger V037): a new entry under the scope of the newest posting
-- whose date the cutoff leaves open, inserted by the run's own superuser and rolled back, must be refused by the unique index. Terse, so
-- the refusal names the index and not the scope. Run by kind-rehearsal/experiments.sh (E63_recover), never anywhere else. The entry is
-- never committed: the transaction rolls back whatever the insert did, and no line is written, so no trigger at commit is reached.
\set VERBOSITY terse
\pset format unaligned
\pset tuples_only on

BEGIN;
INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, reason, actor_id, correlation_id, causation_id,
                                  idempotency_scope, created_at)
SELECT gen_random_uuid(), e.posting_date, e.value_date, e.entry_type, e.reference, e.reason, e.actor_id, e.correlation_id,
       e.causation_id, e.idempotency_scope, e.created_at
  FROM ledger.journal_entry e
 WHERE e.entry_type = 'POSTING'
   AND e.posting_date > coalesce((SELECT c.closed_through FROM ledger.posting_cutoff c WHERE c.id = 1), '-infinity'::date)
 ORDER BY e.id DESC
 LIMIT 1;
ROLLBACK;
