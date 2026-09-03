# Bounded Contexts

Initial candidate bounded contexts.

**Transfers (8)** and **Case Management (23)** were added by `P0-TSK-006`. Both are real
bounded contexts with their own aggregates and lifecycles (`DELIVERY_PLAN.md` Phases 4 and
13) that the original list omitted; the mapping exercise found modules planned for them with
no declared context. This records decisions already taken elsewhere — it is not a new
decision.

1. Party & Customer
2. Identity, Authentication & Authorization
3. KYC/KYB
4. Consent
5. Accounts
6. Wallet
7. Ledger
8. Transfers
9. Payments
10. Payment Methods
11. Checkout
12. Merchant
13. Settlement
14. Reconciliation
15. FX
16. Cross-Border Payments
17. Credit
18. Lending
19. BNPL
20. Risk
21. Fraud
22. AML / Transaction Monitoring
23. Case Management
24. Notifications
25. Accounting / General Ledger
26. Audit
27. Reporting
28. API / Integration Platform

Every context is mapped to exactly one module in
[`MODULE_ARCHITECTURE.md`](MODULE_ARCHITECTURE.md) §3, with merges justified and split
triggers recorded.

Important rule:

These are domain boundaries, not an automatic list of microservices.

For every proposed technical service, document:
- bounded context
- ownership
- authoritative state
- consistency boundary
- data store
- commands
- queries
- domain events
- integration events
- failure behavior
- security boundary

Prefer fewer, stronger modules over premature distribution.
