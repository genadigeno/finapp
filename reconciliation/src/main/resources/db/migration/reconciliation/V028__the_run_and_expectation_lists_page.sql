-- The run and expectation lists, paged (P17-TSK-122; finapp-admin-panel ADR-C133 decision 2; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/reconciliation/runs was the newest 100 runs with a truncated flag, ordered by created_at alone (not a total
--   order: two runs born in the same microsecond had no stable place), and GET /v1/operator/reconciliation/expectations the
--   newest 100 expectations, the same flag; an investigator could not reach the 101st of either. Both are now section 7
--   collections: newest first, the row's id breaking the tie, every page resumed after the last row the page before served.
--   Keyset paging walks (created_at, id) and (opened_at, id) backwards from the cursor, so each page is an index range read in
--   reverse, never a sort of every run or expectation ever born. No index served either order: the existing ones lead with the
--   source (runs and expectations) or the expected-by date (expectations).
--
--   The third list this task pages, one source's rule set versions, needs no index: rule_set's UNIQUE (source_id, version)
--   already serves "WHERE source_id = ? AND version < ? ORDER BY version DESC".
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither
--   reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on
--   reconciliation.reconciliation_batch and reconciliation.expectation until this migration commits, so a run's birth or an
--   expectation's opening waits for it. A deployment with large tables builds them ahead by the same names, which is what
--   IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS reconciliation_batch_newest_first
    ON reconciliation.reconciliation_batch (created_at, id);

CREATE INDEX IF NOT EXISTS expectation_newest_first
    ON reconciliation.expectation (opened_at, id);
