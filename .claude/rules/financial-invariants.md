# Financial Invariants

These rules apply whenever code can affect money, balances, settlement, fees, credit exposure, or accounting.

- Never use floating point for monetary values.
- Currency must be explicit.
- Journal entries must balance.
- Never silently mutate financial history.
- Every financial correction must have an explicit compensating/reversal mechanism.
- Critical money-moving commands require idempotency.
- Duplicate external events/webhooks must be harmless.
- State transitions must be explicit and validated.
- Preserve external evidence needed for reconciliation.
- Add tests for financial invariants, not only happy paths.
