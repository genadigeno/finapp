-- A merchant's payouts, read newest first (P17-TSK-033; finapp-admin-panel B7.3, gap G-51, ADR-C133).
--
-- WHY
--   GET /v1/operator/merchants/{merchantId}/payouts pages one merchant's payouts by (created_at, id), newest first. The table
--   is every merchant's payouts together and grows with each one; V007's indexes lead with the dispatch key and the sweep's
--   status, so without this one a page of one merchant's payouts reads every merchant's and sorts them. The walk is a range of
--   this index, read backwards. The merchants list needs none: merchant.merchant grows one onboarding at a time.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One index. No column, row, constraint, trigger or grant. It moves no money and changes no figure. Expand-only: the previous
--   release neither reads nor needs it.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (ledger V039's reasoning): it holds SHARE on merchant_payout until
--   this migration commits, so a payout dispatched meanwhile waits for the build. A deployment with a large payout table builds
--   it ahead by the same name, CONCURRENTLY and outside a transaction, which is what IF NOT EXISTS is for
--   (OPERATIONS_RUNBOOK.md section 11.48).

CREATE INDEX IF NOT EXISTS merchant_payout_by_merchant
    ON merchant.merchant_payout (merchant_id, created_at, id);
