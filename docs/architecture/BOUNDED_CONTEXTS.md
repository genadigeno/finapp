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

**Credit (17)** was bounded by the Phase 9 → 10 transition (ADR-0084, 2026-10-07;
`PHASE_10_PLAN.md` §3) and built by `P10-TSK-001`…`-021` (credit `V001`…`V015`) — *as built,
read against the code by the Phase 10 exit review, `P10-DOC-001`, 2026-10-09, which accepted
ADR-0084; this read "`Proposed` … planned: nothing of it is built" until then.* One context in one module, `credit`, with one consistency
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
  `FINANCIAL_DATA_ACCESS`, each with its own consent text; the gate (`ConsentBackedCreditConsentGate`
  over consent's `ConsentGate`) is read authoritatively in the acting transaction at submission, at
  a data request's opening, at every retry, at the recording of its answer, at the freeze and in the
  deciding transaction. A submission without it is refused with consent's own
  `409 consent.ConsentRequired`.
- **Party & Customer (1) and KYC/KYB (3)** — upstream: the applicant's standing is Party's
  customer status, read through credit's `CreditPartyStanding` port, implemented in `app` by
  `PartyCreditStanding` over party's `PartyStore` — the live customer `ACTIVE`, the
  `VerifiedAccountHolder` gate, which `INV-KYC-05` makes a projection of KYC's approval, so KYC is
  never read directly — in the acting transaction, per decision. The party facts a snapshot
  records (age, residence) are answered `ABSENT` until the platform holds them (unresolved question
  #13). Credit adds nothing to either context. *(This read "(`ACTIVE`, KYC `VERIFIED`) … through
  kyc's existing customer-standing port" until the Phase 10 exit review.)*
- **Identity, Authentication & Authorization (2)** — upstream: the sessions every credit door
  authenticates; the MFA enrolment the customer's submission and cancellation step up on (the
  submission requires a `MULTI_FACTOR` session unconditionally since the Phase 10 → 11 transition
  — R14, the owner's decision; the cancellation a `MULTI_FACTOR` session only when a TOTP factor is
  enrolled; `identity.AssuranceRequired` otherwise); and the three permissions `CREDIT_POLICY_ADMINISTER`, `CREDIT_INVESTIGATE` and
  `CREDIT_UNDERWRITE` with the two roles `CREDIT_POLICY_OFFICER` and `UNDERWRITER`, declared in
  identity's `PermissionName` and `RoleName` and admitted by identity `V020`. *(Added by the Phase
  10 exit review, `P10-DOC-001`, 2026-10-09.)*
- **Risk (20)** — upstream, through a seam: the **risk score is Risk's** (Phase 13), settled by the
  same transition (the glossary's §10). Credit declares the `CreditRiskSignal` port and records
  the answer it was given in the decision's snapshot; until Phase 13 the composition answers
  `NOT_ASSESSED` for every party, so a decision made before Risk exists replays identically after.
- **Lending (18)** — downstream, from Phase 11: a Loan Application references a credit decision
  through credit's published decision-read port (`CreditDecisions`) and may take
  `CreditDecisionRecorded` as a hint; the loan, its offer, disbursement and servicing are Lending's.
  **Taking up an approval is credit's write, not Lending's**: the born-once
  `credit_decision_consumption` fact is written only by credit's own command port
  (`CreditDecisionConsumptions.consume`, `P11-TSK-001`), under the party's `credit_profile` lock,
  behind a database guard; Lending's acceptance calls it and never writes a credit table. *(This read
  "and so is consuming the exposure an approval reserves — by writing credit's born-once
  `credit_decision_consumption` fact" until the Phase 10 → 11 transition, which found the table
  with no owner and the application role holding `INSERT` — R13.)* Lending supplies the facts
  credit's `PlatformCreditExposure` version 2 reads; credit keeps the judgement. **BNPL (19)** is a
  later downstream consumer of the same decision in Phase 12, through the same port.
- **External credit bureaus and financial-data providers** — reached only through provider-neutral
  adapters (ADR-0008), their formats never leaking into the domain; the raw answer is kept as
  encrypted evidence with a declared retention deadline. Only simulators exist (`bureau-sim-a`,
  `bureau-sim-b`, `findata-sim-a`): production names no provider and selects each kind's fail-safe
  until unresolved questions #13 and #14 are answered.

**Lending (18)** was bounded by the Phase 10 → 11 transition (ADR-0090, `Proposed`, 2026-10-10;
`PHASE_11_PLAN.md` §3) — *planned: nothing of it is built, and the task that builds each part
corrects this paragraph to the code.* One context in one module, `lending`, serving two products —
the amortising personal loan and the revolving credit line — on one account model. **It owns the
contract and its servicing facts and none of the money**: the application, the versioned product
terms, the offer, the immutable versioned agreement and its acceptance evidence, the loan account
(identity, kind and state only), the schedule and instalments, billings, statements, draws,
accruals, fee assessments, repayments and their allocations, collection attempts, the delinquency
and default conditions, payoff quotes, amendments and lending-capital contributions — while every
principal, interest, fee and credit-balance figure is a balance of a per-loan account in the Ledger
(7), posted through the ledger's services in Lending's own transaction, never stored. Its consistency
boundary is the loan account: every servicing act on one account serialises on its row, and each
money effect commits with the fact that explains it. Its relationships, every one through a port
`app` implements and none a build edge (`lending` depends on `ledger`, `platform` and `sharedkernel`
only):
- **Credit (17)** — upstream, customer-supplier: the decision is Credit's; Lending's application
  opens Credit's decision request in its own transaction, its offer reads the decision
  authoritatively, and its acceptance takes the approval up through Credit's consumption port under
  the party's profile lock — the only credit lock Lending ever holds, always first. Exposure stays
  Credit's judgement over facts Lending supplies (`PlatformCreditExposure` version 2: committed and
  outstanding principal, and an open credit line's limit, in one statement).
- **Ledger (7)** — downstream conformist to the ledger's contract: ten new account purposes under a
  new `OwnerKind.LOAN`, `LENDING_CAPITAL` among them — the platform's own funds, recognised only from
  bank evidence, from which every loan and draw is funded and which no acceptance or draw may
  over-deploy.
- **Accounts / Wallet (5, 6)** — the borrower's wallet is the disbursement's destination and the
  repayment's source; Lending never owns it.
- **Payments (9)** — an external payout is a system-initiated withdrawal of the borrower's disbursed
  funds on their recorded instruction, through Payments' own machinery (routing, ambiguity held,
  inquiry-only outcomes, returns, reconciliation's expectation); **Lending reads Payments' outcome
  and is never called by Payments**, so no lock-order cycle can form. Lending does not implement
  `OutboundCreditComposition`.
- **Reconciliation (14) and Settlement (13)** — a lending-capital contribution is an expectation
  bank recognition matches; cash is never adjusted to fit.
- **Party & Customer (1), Identity (2)** — standing read in the acting transaction; the permissions
  `LENDING_ADMINISTER`, `LENDING_INVESTIGATE`, `LENDING_CAPITAL_ADMINISTER`, `LOAN_SERVICE` and
  `LOAN_SERVICE_APPROVE`; the customer's credit-creating acts on a `MULTI_FACTOR` session.
- **Downstream, deferred:** collections (Case Management, 23 — Phase 13) consumes
  `LoanDelinquencyChanged` and `LoanDefaulted`; write-off, provisioning and the GL (Accounting, 25 —
  Phase 14) map Lending's purposes; refinance is decided by the Phase 11 → 12 transition.
  **Production originates nothing** until a real bureau is connected (unresolved #13/#14): Phase 11
  proves origination against the simulators.

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
