-- E63's instant fault (P16-TSK-011; OPERATIONS_RUNBOOK.md section 11.56): every terminal idempotency record the workload has left, aged
-- forty days, so the replicas' own sweep takes it at its next ticks. Run by kind-rehearsal/experiments.sh (E63_fault) as the run's own
-- superuser, on the run's own test-only server, never anywhere else. One transaction: the records' freeze trigger (platform V003) is set
-- aside and enabled again inside it, so no state with it off is ever committed or seen by another session; a failure rolls the whole back.
-- Every date moves together, so every CHECK of V002 still holds. Prints counts and an epoch, never a key.
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep '|'

BEGIN;
ALTER TABLE platform.idempotency_record DISABLE TRIGGER idempotency_record_guard_transitions;
WITH aged AS (
    UPDATE platform.idempotency_record
       SET created_at = created_at - interval '40 days', completed_at = completed_at - interval '40 days',
           expires_at = expires_at - interval '40 days'
     WHERE state IN ('COMPLETED', 'FAILED') AND completed_at < transaction_timestamp() - interval '30 seconds'
    RETURNING 1)
SELECT 'aged', count(*), extract(epoch FROM transaction_timestamp())::bigint FROM aged;
SELECT 'young', count(*) FROM platform.idempotency_record
 WHERE state IN ('COMPLETED', 'FAILED') AND completed_at >= transaction_timestamp() - interval '30 seconds'
   AND completed_at <= transaction_timestamp();
ALTER TABLE platform.idempotency_record ENABLE TRIGGER idempotency_record_guard_transitions;
COMMIT;
SELECT 'trigger', tgenabled FROM pg_trigger WHERE tgname = 'idempotency_record_guard_transitions';
