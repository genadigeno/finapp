-- The operator lists, paged (P17-TSK-122; finapp-admin-panel ADR-C133 decision 2, gap G-03; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/regulatory/obligations was a bare array of the newest 100 versions, and a preparer could not reach the
--   101st. It is now a section 7 collection: proposed newest first, the version's id breaking the tie, every page resumed
--   strictly before the last row the page before served. Keyset paging walks (proposed_at, id) backwards from the cursor, so
--   each page is an index range read in reverse, never a sort of every version ever proposed. No index served that order
--   across every status: V010's regulatory_obligation_awaiting holds the PROPOSED versions only.
--
--   GET /v1/operator/accounting/breaks?period= keeps its order - one period's breaks in id (birth) order - and now resumes from
--   an opaque cursor carrying the last id served. V006's accounting_break_by_period leads with (period_start, status), so a
--   page of every status of one period was a scan of the period's rows and a sort by id; (period_start, id) serves the walk
--   as a range read, and the status filter, when named, is applied along it.
--
--   The regulatory submissions' standing list needs nothing here: its keyset (recorded_order, id) is V014's.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither
--   reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on each table until this
--   migration commits, so a proposal of an obligation, or a close run raising a break, waits for it. A deployment with a large
--   table builds them ahead by the same names, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS regulatory_obligation_newest_first
    ON accounting.regulatory_obligation (proposed_at, id);

CREATE INDEX IF NOT EXISTS accounting_break_period_in_birth_order
    ON accounting.accounting_break (period_start, id);
