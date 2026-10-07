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

## Credit
- Credit profile
- Credit bureau integration
- Decisioning
- Underwriting
- Loan servicing
- BNPL
- Delinquency / collections

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
