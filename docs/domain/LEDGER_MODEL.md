# Ledger Model

The ledger represents authoritative financial postings.

Core concepts:
- Chart of Accounts
- Ledger Account
- Journal Entry
- Journal Line
- Debit
- Credit
- Currency
- Posting Date
- Value Date
- Reference
- Reversal
- Adjustment
- Suspense

Every posting operation must:
1. validate the business operation;
2. determine accounting entries;
3. write balanced journal lines atomically;
4. make the posting durable;
5. emit integration/domain signals reliably if required;
6. remain auditable and reconstructable.

Balances can be maintained as projections/snapshots for performance, but their correctness must be anchored to the ledger.
