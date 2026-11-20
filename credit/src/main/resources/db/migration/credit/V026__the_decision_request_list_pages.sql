-- The decision request list, paged (P17-TSK-035, gap G-80; finapp-admin-panel B8.1, ADR-C133; API_CONVENTIONS.md section 7).
--
-- WHY
--   GET /v1/operator/credit/decision-requests reads the decision requests submitted newest first, the request's id breaking the
--   tie, every page resumed strictly after the last request the page before served, narrowed by status and product when named.
--   A request was reachable only by its identifier or through the review queue: V010's decision_request_by_party
--   (party_id, submitted_at) serves one party's, and decision_request_due (next_step_at, id) the progress sweep - nothing served
--   every party's in submission order, so a page would read and sort the whole table.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One index. No column, row, constraint, trigger or grant. It moves no money. Expand-only: the previous release neither reads
--   nor needs it.
--
-- THE NUMBER
--   V026: V025 is P17-TSK-042's (the review queue's index), on its own branch. The two are independent indexes; at the merge
--   V025 is applied before V026, as Flyway's order requires.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (EveryMigrationRunsInATransactionTest; DATA_MIGRATIONS.md section
--   4.4): a SHARE lock on credit.decision_request until this migration commits, so a submission or a progress step waits for
--   it. A request is a customer's application for credit - a few a customer a year - so the table is small; a deployment with a
--   large one builds it ahead, CONCURRENTLY and by the same name, which is what IF NOT EXISTS is for.

CREATE INDEX IF NOT EXISTS decision_request_by_submission
    ON credit.decision_request (submitted_at, id);
