-- The underwriting review queue, paged across every status (P17-TSK-042; finapp-admin-panel ADR-C133 decision 2, gap G-03;
-- API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/credit/review-cases served the oldest 50 cases as a bare array, with nothing saying there were more. It is
--   now a section 7 collection: opened oldest first, the case's id breaking the tie, every page resumed after the last case the
--   page before served. Within one status V013's underwriting_case_queue (status, opened_at, id) already serves that walk; the
--   queue read without a status - every case, decided ones included, which grow without end - had no index in its order.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One index. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither reads
--   nor needs it.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (identity V027's reasoning): a SHARE lock on
--   credit.underwriting_case until this migration commits, so a referral opening a case, or an underwriter's act, waits for it.
--   Cases are referrals a person reviews, so the table is small; a deployment with a large one builds it ahead by the same name,
--   which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS underwriting_case_every_status
    ON credit.underwriting_case (opened_at, id);
