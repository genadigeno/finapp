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
- Exposure — delivered: the party's bureau balance, the platform's outstanding credit (zero
  until Phase 11), the reserved exposure of current approvals and the request, judged under the
  party's profile lock; a policy that does not bound its own limit refused at proposal
- Decisioning — delivered: a versioned scorecard and a versioned policy of rules as data, both
  four-eyes, through a deterministic engine with its own version; a keyed asynchronous decision
  request decided once from a sealed snapshot, immutable with ordered reason codes; the customer's
  adverse-action explanation, the operator's audited explanation, evidence read and replay, and a
  proof that replays every decision `IDENTICAL`
- Underwriting (manual review) — delivered: a referral opens one case, taken by one underwriter,
  decided with reasons, never overriding a hard decline, a second underwriter above the product's
  threshold who may refuse it back, and a case that closes with its request
- Loan servicing *(Phase 11)*
- BNPL *(Phase 12)*
- Delinquency / collections *(later phases — delinquency with Phase 11's lending; collections operations not yet scheduled)*

*(The Credit section's Phase 10 capabilities were marked "planned" by the Phase 9 → 10 transition,
2026-10-07 (`PHASE_10_PLAN.md`; ADR-0084…0089, then `Proposed`), and marked delivered by the Phase
10 exit review, `P10-DOC-001` (ADR-0084…0089 `Accepted`). The risk score is the Risk section's,
Phase 13: credit consumes a risk signal through a seam and computes none.)*

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
