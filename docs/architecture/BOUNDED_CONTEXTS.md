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

**Credit (17)** was bounded by the Phase 9 → 10 transition (ADR-0084, `Proposed`, 2026-10-07;
`PHASE_10_PLAN.md` §3) — *planned: nothing of it is built, and the task that builds each part
corrects this paragraph to the code.* One context in one module, `credit`, with one consistency
boundary: the decision and the snapshot it was made from commit together, and the exposure an
approval reserves is judged in the transaction that records it. Inside it credit data, the credit
profile, the assessment (affordability, exposure, the scorecard's score), the versioned policy and
model, underwriting and the decision stay separate aggregates — `CLAUDE.md`'s Credit Score /
Risk Score / Credit Decision / Underwriting distinction made physical; collecting credit data as a
second context was weighed and refused (its only consumer is credit's own snapshot). **Credit moves
no money**: it posts nothing to the Ledger (7), holds nothing and disburses nothing. Its
relationships, every one through a port `app` implements and none a build edge (`credit` depends on
`platform` and `sharedkernel` only):
- **Consent (4)** — upstream, conformist: no credit data is retrieved without a recorded, current
  lawful basis (`INV-CRD-03`). Two new purposes, `CREDIT_BUREAU_ACCESS` and
  `FINANCIAL_DATA_ACCESS`, each with its own consent text; the gate is read authoritatively in the
  transaction that opens a data request and again in the one that records the answer.
- **Party & Customer (1) and KYC/KYB (3)** — upstream: the applicant's standing (`ACTIVE`, KYC
  `VERIFIED`) is read through kyc's existing customer-standing port, in the acting transaction.
  Credit adds nothing to either context.
- **Risk (20)** — upstream, through a seam: the **risk score is Risk's** (Phase 13), settled by the
  same transition (the glossary's §10). Credit declares the `CreditRiskSignal` port and records
  the answer it was given in the decision's snapshot; until Phase 13 the composition answers
  `NOT_ASSESSED` for every party, so a decision made before Risk exists replays identically after.
- **Lending (18)** — downstream, from Phase 11: a Loan Application will reference a credit
  decision through credit's published decision-read port and consume `CreditDecisionRecorded`; the
  loan, its offer, disbursement and servicing are Lending's, and so is consuming the exposure an
  approval reserves. **BNPL (19)** is a later downstream consumer of the same decision in Phase 12.
- **External credit bureaus and financial-data providers** — reached only through provider-neutral
  adapters (ADR-0008), their formats never leaking into the domain; the raw answer is kept as
  encrypted evidence with a declared retention deadline.

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
