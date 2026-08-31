---
paths:
  - "**/ledger/**"
  - "**/accounting/**"
  - "**/journal/**"
  - "**/posting/**"
---

# Ledger Domain Rules

- Journal entries are immutable once posted.
- Every journal entry must balance.
- Use explicit debit/credit lines.
- Posting must be atomic with the authoritative transactional state.
- Reversal is a new financial effect referencing the original.
- Balance projections are not the primary financial truth.
- Accounting rules must be explicit and testable.
