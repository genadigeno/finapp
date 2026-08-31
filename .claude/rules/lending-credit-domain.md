---
paths:
  - "**/lending/**"
  - "**/credit/**"
  - "**/bnpl/**"
  - "**/underwriting/**"
  - "**/decisioning/**"
---

# Credit / Lending Rules

Keep separate:
- credit data
- risk assessment
- policy evaluation
- credit decision
- loan servicing

Where decisions matter financially, record or version the policy/model context and reason codes required to explain the result.

Loan repayment allocation, interest accrual, fees, delinquency, and payoff must be modeled explicitly rather than hidden in a single balance field.
