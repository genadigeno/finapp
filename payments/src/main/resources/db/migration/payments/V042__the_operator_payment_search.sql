-- The operator's payment search (P17-TSK-032; finapp-admin-panel B7.2, gap G-40, ADR-C133).
--
-- WHY
--   GET /v1/operator/payments pages the payments created in a window, newest first by (created_at, id), of one payer party or
--   one merchant's credited accounts when named. Each is a keyset walk that must be an index range rather than a scan and a
--   sort: V002's payment_intent_by_customer leads with the customer, V010's index holds only the intents in flight, and nothing
--   else orders the table by its creation. So three indexes:
--   - the window alone, and the payments report's day: (created_at, id);
--   - a payer's payments in the window: (party_id, created_at, id);
--   - a merchant's payments in the window - the credited accounts its payables are: (credit_account_id, created_at, id).
--   The status and rail filters narrow the window's range. The plans are recorded by app's PaymentReadsApiDatabaseTest and in
--   OPERATIONS_RUNBOOK.md section 11.48.
--
-- WHAT IT ADDS, AND ONLY THAT
--   Three indexes. No column, row, constraint, trigger or grant. It moves no money and changes no figure. Expand-only: the
--   previous release neither reads nor needs them.
--
-- LOCKS
--   Built in this migration's transaction, not CONCURRENTLY (ledger V039's reasoning): each holds SHARE on payment_intent until
--   this migration commits, and every payment's creation inserts there and waits for the build. A deployment with many payments
--   builds them ahead by the same names, CONCURRENTLY and outside a transaction, which is what IF NOT EXISTS is for
--   (OPERATIONS_RUNBOOK.md section 11.48).

CREATE INDEX IF NOT EXISTS payment_intent_by_creation
    ON payments.payment_intent (created_at, id);

CREATE INDEX IF NOT EXISTS payment_intent_by_party_and_creation
    ON payments.payment_intent (party_id, created_at, id);

CREATE INDEX IF NOT EXISTS payment_intent_by_credit_account_and_creation
    ON payments.payment_intent (credit_account_id, created_at, id);
