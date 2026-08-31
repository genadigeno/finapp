# Bounded Contexts

Initial candidate bounded contexts:

1. Party & Customer
2. Identity & Authentication
3. KYC/KYB
4. Consent
5. Accounts
6. Wallet
7. Ledger
8. Payments
9. Payment Methods
10. Checkout
11. Merchant
12. Settlement
13. Reconciliation
14. FX
15. Cross-Border Payments
16. Credit
17. Lending
18. BNPL
19. Risk
20. Fraud
21. AML / Transaction Monitoring
22. Notifications
23. Accounting / General Ledger
24. Audit
25. Reporting
26. API / Integration Platform

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
