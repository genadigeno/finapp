# Capability Map

## Customer and Identity
- Customer / Party
- Identity
- Authentication
- Authorization
- MFA / Passkeys
- Device and session management
- Account recovery

## Trust and Compliance
- KYC
- KYB
- Beneficial ownership
- Sanctions
- PEP / adverse media
- Consent
- AML transaction monitoring
- Case management

## Money and Accounts
- Accounts
- Wallets
- Balances / holds
- Transfers
- Statements
- Ledger
- Accounting GL

## Payments
- Payment intents
- Payment attempts
- Payment methods
- Gateway / processor adapters
- Authorization / capture
- Refunds
- Disputes / chargebacks
- Webhooks
- Checkout
- Merchant settlements

## Rails
- Cards
- Wallets
- A2A
- Instant-payment abstraction

## FX and Cross-Border *(Phase 9, delivered 2026-10-07 — `P9-DOC-001`)*
- FX conversion (the platform as principal) — delivered: reference rates and a four-eyes
  pricing policy; the server-authoritative, single-use quote that is a frozen posting plan;
  multi-currency wallets and wallet conversion booked locally through `FX_POSITION`; the
  back-to-back provider cover, exactly once however the provider answers, and its unwind; the
  four-eyes operator FX trade reversal; FX explained to cash per currency, never by conversion
- Cross-border payments (corridors, beneficiaries, screening) — delivered: the corridor rail and
  a four-eyes corridor policy; beneficiaries held by provider reference with a payee check, and
  kyc's counterparty screening before pricing; disclosed offers, a hold at authorization and one
  entry at the corridor provider's acceptance; returns, cancellation by recall, and the
  corridor's settlement to cash; a second FX and corridor provider each

*(Marked "planned" by the Phase 8 → 9 transition, 2026-10-02; marked delivered by the Phase 9
exit review. Earlier phases' capabilities were never annotated here — `CURRENT_STATE.md` and
`history/` are their record.)*

## Credit *(Phase 10, delivered 2026-10-09 — `P10-DOC-001`)*
- Credit profile — delivered: one born-once row per party, holding no figure, the row every
  deciding transaction for the party serialises on
- Credit bureau integration — delivered: bureau and financial-data collection behind
  provider-neutral ports, under consent re-read at every step, raw evidence encrypted with a
  stored retention (its purge Phase 15's); two simulated bureaus (`bureau-sim-a`, `bureau-sim-b`)
  with source selection and a simulated financial-data provider, each through the port's contract
  suite. **No real bureau is connected**: production composes the fail-safe sources and refuses a
  named provider until unresolved questions #13 and #14 are answered
- Affordability assessment — delivered: exact single-currency arithmetic (the annuity at scale
  10, rounded once), a foreign-currency source partial data, never converted
- Exposure — delivered: the party's bureau balance, the platform's outstanding credit (since
  Phase 11 `PlatformCreditExposure` version 2, `P11-TSK-013`: every loan's committed and
  outstanding principal and every open credit line's limit, one statement), the reserved exposure
  of current approvals and the request, judged under the party's profile lock; a policy that does
  not bound its own limit refused at proposal *(read "the platform's outstanding credit (zero until
  Phase 11)" until the Phase 11 exit review, `P11-DOC-001`, 2026-10-15)*
- Decisioning — delivered: a versioned scorecard and a versioned policy of rules as data, both
  four-eyes, through a deterministic engine with its own version; a keyed asynchronous decision
  request decided once from a sealed snapshot, immutable with ordered reason codes; the customer's
  adverse-action explanation, the operator's audited explanation, evidence read and replay, and a
  proof that replays every decision `IDENTICAL`
- Underwriting (manual review) — delivered: a referral opens one case, taken by one underwriter,
  decided with reasons, never overriding a hard decline, a second underwriter above the product's
  threshold who may refuse it back, and a case that closes with its request
- **Production constraint** *(the Phase 10 → 11 transition, 2026-10-10)* — confirmed after repair:
  every submission requires a `MULTI_FACTOR` session, an underwriter never decides their own
  party's case, a decision rests on its own request's snapshot, nothing is decided on stale data,
  and **a person may not approve on an absent bureau balance** — so, with production composing only
  the fail-safe sources, **production approves no credit until a real bureau is connected**
  (unresolved #13, #14)
- BNPL *(Phase 12)*

*(The Credit section's Phase 10 capabilities were marked "planned" by the Phase 9 → 10 transition,
2026-10-07 (`PHASE_10_PLAN.md`; ADR-0084…0089, then `Proposed`), marked delivered by the Phase
10 exit review, `P10-DOC-001` (ADR-0084…0089 `Accepted`), and confirmed after repair by the Phase
10 → 11 transition, 2026-10-10 (`reviews/PHASE_10_TO_11_TRANSITION.md`), which also moved loan
servicing and delinquency to the Lending section below. The risk score is the Risk section's,
Phase 13: credit consumes a risk signal through a seam and computes none.)*

## Lending *(Phase 11, delivered 2026-10-15 — `P11-DOC-001`)*
- Loan application, offer and acceptance — delivered: one customer act opens credit's decision
  request; an approved decision becomes an expiring offer pinned to the four-eyes terms version
  active at the offering instant, its canonical terms sealed by a hash; the acceptance (a
  `MULTI_FACTOR` or stronger session, the hash echoed) commits an immutable, versioned agreement and
  its evidence, takes the approval up through credit's consumption port and commits the exposure
  and, for a loan, the capital, atomically under the party's profile lock
- Personal loan servicing — delivered: a deterministic amortisation schedule, ACT/365F simple daily
  interest accrued once per date and billed from actual accrual, repayment from the wallet and
  scheduled auto-collection, allocation oldest-due first (fees, interest, principal; any excess held
  as a credit balance), payoff quote and execution, and partial prepayment with recalculation (the
  same instalment over a shorter term)
- Revolving credit line — delivered: draws to the wallet against the approved limit, revolving
  interest, a monthly statement and minimum payment, the available limit derived from postings
  under the line's lock, closure through `CLOSING`; exposure counts the committed limit
- Disbursement — delivered: to the borrower's wallet, or on to an external bank account through
  payments' system-actor withdrawal on the borrower's recorded instruction, its ambiguity held by
  payments and concluded only by inquiry; interest starts at the payout's terminal outcome
- Lending capital — delivered: every loan and draw funded from the platform's own capital, a
  four-eyes contribution recognised only from bank evidence and never over-deployed, so loan-funded
  wallet money is platform-funded, proven per currency by the capital proof
- Delinquency and default — delivered: days past due derived, buckets, default at 90 days past due,
  late fees as configured and capped, waivers and repayment reversals four-eyes, restructuring by an
  amendment the customer accepts
- Loan accounting — delivered: per-loan ledger accounts, due split from not-due, every figure a
  ledger balance, proven by the subledger and replay proofs, explained per account to an
  investigator, and reported (portfolio, delinquency, accrual, capital)
- **Production constraint** — production originates nothing: no terms version is `ACTIVE` in
  production, so the application door answers `422 lending.ProductNotOffered` until a real bureau is
  connected (unresolved #13, #14) and `OPERATIONS_RUNBOOK.md` §7.4's activation gate is passed;
  Phase 11 proves origination end to end against the simulators
- Collections operations *(later phases — Phase 13's case management consumes lending's facts)*;
  refinance *(the Phase 11 → 12 transition decides)*; write-off and provisioning *(Phase 14)*

*(Marked "planned" by the Phase 10 → 11 transition, 2026-10-10 (`PHASE_11_PLAN.md`; ADR-0090…0100,
then `Proposed`), and marked delivered by the Phase 11 exit review, `P11-DOC-001`, 2026-10-15
(ADR-0090…0100 `Accepted`) - each capability built by `P11-TSK-001`…`-036` and proven by
`P11-TST-001` (the lending storm) and `P11-TST-002` (the battery). This note read "to be marked
delivered by the Phase 11 exit review" until then.)*

## Risk
- Fraud
- Transaction risk
- Account takeover
- AML monitoring
- Limits / velocity
- Manual review

## Financial Operations
- Settlement
- Reconciliation
- Exceptions / breaks
- Suspense
- Financial reporting
- Regulatory reporting abstraction
- Audit

## Platform
- API gateway
- Eventing
- Notification
- Observability
- Policy/configuration
- Security
- Data governance
