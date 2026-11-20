-- The file list and the refused-delivery list, paged (P17-TSK-042; finapp-admin-panel ADR-C133 decision 2, gap G-03;
-- API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/settlement/files and /refused-deliveries were the newest 100 rows with a truncated flag, and an investigator
--   could not reach the 101st. Each is now a section 7 collection: newest first, the row's id breaking the tie, every page resumed
--   after the last row the page before served. Keyset paging walks (received_at, id) and (refused_at, id) backwards from the
--   cursor, so each page is an index range read in reverse. Neither order had an index: the existing ones lead with the source
--   or the content address.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither reads
--   nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on settlement.file and on
--   settlement.refused_delivery until this migration commits, so an upload or a refusal waits for it. A file is one report of one
--   source, a handful a day, and a refusal rarer; a deployment with large tables builds them ahead by the same names, which is
--   what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS file_newest_first
    ON settlement.file (received_at, id);

CREATE INDEX IF NOT EXISTS refused_delivery_newest_first
    ON settlement.refused_delivery (refused_at, id);
