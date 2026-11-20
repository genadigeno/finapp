-- The break list, paged (P17-TSK-042; finapp-admin-panel ADR-C133 decision 2, gap G-121; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/reconciliation/breaks was the newest 100 breaks with a truncated flag, and an investigator could not reach
--   the 101st. It is now a section 7 collection: raised newest first, the break's id breaking the tie, every page resumed after
--   the last row the page before served. Keyset paging walks (raised_at, id) backwards from the cursor, so each page is an index
--   range read in reverse, never a sort of every break ever raised. No index served that order across every source: the
--   existing ones lead with severity (open breaks only) or with the source.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One index. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither reads
--   nor needs it.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on reconciliation.break
--   until this migration commits, so a matching run raising a break waits for it. A deployment with a large table builds it
--   ahead by the same name, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS break_newest_first
    ON reconciliation.break (raised_at, id);
