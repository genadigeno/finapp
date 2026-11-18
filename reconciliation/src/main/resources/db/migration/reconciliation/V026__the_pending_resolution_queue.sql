-- The approver's queue of resolutions awaiting a decision (P17-TSK-107; finapp-admin-panel ADR-C133 decision 3).
--
-- WHY
--   GET /v1/operator/reconciliation/resolutions?status=PROPOSED pages the proposals awaiting a second person oldest first, by
--   proposed_at, the resolution's id breaking the tie. Keyset paging walks (proposed_at, id) forwards from the cursor, so each
--   page is an index range. Partial, because the queue is only the PROPOSED rows: a resolution is decided once and the decided
--   ones, which grow without end, are never in it.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One partial index. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither
--   reads nor needs it.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on reconciliation.resolution
--   until this migration commits, so a proposal or a decision waits for it. Proposals are made by people, a few a day; a
--   deployment with a large table builds it ahead by the same name, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS resolution_pending_queue
    ON reconciliation.resolution (proposed_at, id)
    WHERE status = 'PROPOSED';
