-- The KYC review queue, read as one range (P17-TSK-029; gap G-60; finapp-admin-panel ADR-C133; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/kyc/cases serves the open cases oldest opening first, the case's id breaking a tie, each page resumed after
--   the last case the page before served. V002's kyc_case_one_open_per_customer holds exactly the open cases, but by customer:
--   reading the queue from it sorts every open case on every page. This index holds the same rows in the queue's own order,
--   so a page is one range of it, and the decided history - which grows without end - is never read.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One partial index, on V002's predicate. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the
--   previous release neither reads nor needs it.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning, credit V025's): a SHARE lock on
--   kyc.kyc_case until this migration commits, so a case opening or moving waits for it. The index holds only the open cases -
--   work in progress - so it is small; a deployment with a large open set builds it ahead by the same name, which is what
--   IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS kyc_case_review_queue
    ON kyc.kyc_case (opened_at, id)
    WHERE status NOT IN ('APPROVED', 'REJECTED');
