# Bounded Contexts

Initial candidate bounded contexts.

**Transfers (8)** and **Case Management (23)** were added by `P0-TSK-006`. Both are real
bounded contexts with their own aggregates and lifecycles (`DELIVERY_PLAN.md` Phases 4 and
13) that the original list omitted; the mapping exercise found modules planned for them with
no declared context. This records decisions already taken elsewhere — it is not a new
decision.

**Disputes (29)** was added by the Phase 6 → 7 transition (ADR-0061 §1). `DELIVERY_PLAN.md`
already named it a new context for Phase 7; this list never had. It maps to `payments`, merged,
with its split trigger recorded (`MODULE_ARCHITECTURE.md` M11).

**Settlement (13)** and **Reconciliation (14)** were re-bounded by the Phase 7 → 8 transition
(ADR-0064), not renamed or merged: they stay two contexts in two modules with no build edge
between them (`MODULE_ARCHITECTURE.md` M8). Settlement is the external side — each counterparty's
reports and the bank's statements, received, retained and recognised. Reconciliation is the
internal side, the comparison and its outcome — the settlement expectations every externally
settling completion opens, the matching, the breaks, the suspense and the resolutions. The
expectation belonged to Settlement until then; it is internal state that allocation and ageing
drive, so it is Reconciliation's.

**FX (15)** and **Cross-Border Payments (16)** were bounded by the Phase 8 → 9 transition
(ADR-0074…0081, 2026-10-02): two contexts in two modules, `fx` and `crossborder`, with no build
edge between them and none to `payments`, `kyc` or `accounts` (`MODULE_ARCHITECTURE.md` §4). FX
is pricing and conversion — the server-authoritative quote that is a frozen posting plan, the
trade that posts it, and the cover that squares the platform's position with the FX provider.
Cross-Border Payments is the customer's instruction to pay a beneficiary abroad — corridors,
beneficiaries, offers and the payment's business lifecycle — delegating pricing to `fx` and
execution to `payments`, whose Outbound Credit owns the provider's ambiguity (ADR-0079). Two
boundary decisions worth recording: counterparty screening is KYC/KYB's (context 3) — a
transaction-time sanctions check on an external party is a kyc decision with evidence, review
and a recorded basis, not a payment state (ADR-0081) — and the FX and corridor counterparties'
clearing positions are the Ledger's (context 7), keyed per counterparty through
`ledger.counterparty`, reconciled by Settlement and Reconciliation (13, 14) over their own
report sources (ADR-0078, ADR-0082).

*As built by Phase 9 (`P9-TSK-001`…`-027`, `X-TSK-013`, `P9-TST-001`; read at the exit review,
`P9-DOC-001`, 2026-10-07):* `fx` (schema `fx`, migrations `V001`…`V009`) and `crossborder` (schema
`crossborder`, `V001`…`V006`) each depend on `ledger` and `platform` only. Their joins are ports
composed in `app`: a cross-border payment prices through crossborder's `CrossBorderFx` port (implemented
in `app` over fx) and executes as payments' outbound credit (payments `V025`…`V027`), whose completion, failure and return call back through
payments' `OutboundCreditComposition`, implemented by `app`'s `CrossBorderCompletion` over crossborder's
payment and fx's `CrossBorderCompletionBooking`. Counterparty screening is kyc's
`counterparty_screening` (kyc `V009`, `P9-TSK-016`). A parked cross-border return is resolved by
Reconciliation's four-eyes `TRANSFER_TO_ACCOUNT` through its `ResolvedCorridorReturns` port, implemented
in `app` (`CorridorReturnResolutions`) - Reconciliation still names no payments or crossborder type.

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
29. Disputes

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
