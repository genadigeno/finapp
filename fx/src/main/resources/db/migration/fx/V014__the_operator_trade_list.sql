-- The operator's trade list (P17-TSK-037; finapp-admin-panel B9.2, gap G-102, ADR-C133).
--
-- WHY
--   GET /v1/operator/fx/trades pages the trades booked in a window, newest first by (booked_at, id), of one pair when named.
--   Each is a keyset walk that must be an index range rather than a scan and a sort: V006's trade_by_owner leads with the
--   customer, and nothing else orders the table by its booking. So two indexes:
--   - the window alone: (booked_at, id);
--   - a pair's trades in the window: (source_currency, destination_currency, booked_at, id).
--   The status filter narrows the window's range. The plans are recorded by app's FxCrossBorderReadsDatabaseTest and in
--   OPERATIONS_RUNBOOK.md section 11.48.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Two indexes. No column, row, constraint, trigger or grant. It moves no money and changes no figure. Expand-only: the
--   previous release neither reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (ledger V039's reasoning): each holds SHARE on fx.trade until this
--   migration commits, and every conversion's booking inserts there and waits for the build. A deployment with many trades
--   builds them ahead by the same names, CONCURRENTLY and outside a transaction, which is what IF NOT EXISTS is for
--   (OPERATIONS_RUNBOOK.md section 11.48).

CREATE INDEX IF NOT EXISTS trade_by_booking
    ON fx.trade (booked_at, id);

CREATE INDEX IF NOT EXISTS trade_by_pair_and_booking
    ON fx.trade (source_currency, destination_currency, booked_at, id);
