-- The batch browse (P17-TSK-107; finapp-admin-panel ADR-C133; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/settlement/batches pages the batches newest first by business date, the batch's id breaking the tie, inside
--   a bounded window of business dates and optionally for one source. Keyset paging walks (business_date, id) backwards from the
--   cursor, so each page is an index range, never a scan of what came before it.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes: the window across every source, and the same within one source. Nothing else: no column, row, constraint,
--   trigger or grant. It moves no money. Expand-only: the previous release neither reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): each holds a SHARE lock on
--   settlement.batch until this migration commits, so a parse writing a batch waits for it. A batch is one report of one source,
--   a handful a day, so the build is short; a deployment with a large table builds them ahead by the same names, which is what
--   IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS batch_browse_by_business_date
    ON settlement.batch (business_date, id);

CREATE INDEX IF NOT EXISTS batch_browse_by_source
    ON settlement.batch (source_id, business_date, id);
