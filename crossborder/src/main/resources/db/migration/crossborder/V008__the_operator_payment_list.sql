-- The operator's cross-border payment list (P17-TSK-037; finapp-admin-panel B9.2, gap G-103, ADR-C133).
--
-- WHY
--   GET /v1/operator/cross-border/payments pages the payments created in a window, newest first by (created_at, id), of one
--   corridor when named. Each is a keyset walk that must be an index range rather than a scan and a sort: V005's
--   payment_by_owner leads with the customer, and nothing else orders the table by its creation. So two indexes:
--   - the window alone: (created_at, id);
--   - a corridor's payments in the window: (corridor, created_at, id).
--   The status filter narrows the window's range; each row's offer is read on its primary key. The plans are recorded by app's
--   FxCrossBorderReadsDatabaseTest and in OPERATIONS_RUNBOOK.md section 11.48.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant. It moves no money and changes no figure. Expand-only: the
--   previous release neither reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (ledger V039's reasoning): each holds SHARE on crossborder.payment
--   until this migration commits, and every cross-border payment's authorisation inserts there and waits for the build. A
--   deployment with many payments builds them ahead by the same names, CONCURRENTLY and outside a transaction, which is what IF
--   NOT EXISTS is for (OPERATIONS_RUNBOOK.md section 11.48).

CREATE INDEX IF NOT EXISTS payment_by_creation
    ON crossborder.payment (created_at, id);

CREATE INDEX IF NOT EXISTS payment_by_corridor_and_creation
    ON crossborder.payment (corridor, created_at, id);
