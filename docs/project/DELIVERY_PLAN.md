# Master Delivery Plan

Authoritative per-phase engineering plan for the platform.

- Phase ordering and rationale: [`docs/product/ROADMAP.md`](../product/ROADMAP.md)
- Gate mechanics and status model: [`PHASE_GATES.md`](PHASE_GATES.md)
- Task-level decomposition: [`BACKLOG.md`](BACKLOG.md)
- Completion standard: [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)
- Working rules: [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)

Every phase is specified against the same eighteen headings. "Must NOT be implemented yet"
is a binding constraint, not advice — see `EXECUTION_PROTOCOL.md` rule 3.

Invariant references (`INV-*`) point at
[`docs/domain/FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md).

---

# Phase 0 — Domain and Architecture Foundation

**Status: READY**

### 1. Objective
Establish a buildable, tested, boundary-enforced modular monolith containing the financial
and platform kernel, with zero business capability. This is the only phase in which
money-representation, idempotency, outbox, audit and correlation primitives can be
introduced without migrating existing financial history.

### 2. Business capabilities
None. Phase 0 delivers no customer-facing or business capability by design.

### 3. Domains / bounded contexts involved
No business context is implemented. The phase defines the *context map* and creates the
platform-shared kernel that all contexts depend on (`platform`, `sharedkernel`).

### 4. Dependencies
None. This is the root phase.

### 5. Architecture work
- Ratify bounded-context map and initial module cut (`MODULE_ARCHITECTURE.md`).
- Decide deployment topology: modular monolith (ADR-0001).
- Define module boundary rules: package-per-module, schema-per-module, no cross-module
  foreign keys, no cross-module entity references, interaction via published interface or
  event only (ADR-0006).
- Define what belongs in the shared kernel and what may never enter it.
- Establish provider-adapter/anti-corruption-layer pattern (ADR-0008).

### 6. Data-model work
- Money representation: `amount_minor BIGINT` + `currency CHAR(3)` + `scale SMALLINT`
  (ADR-0003).
- Migration tooling and conventions; one migration history, schema-per-module namespacing.
- Idempotency record table (key, scope, request fingerprint, response, state, expiry).
- Outbox table (aggregate, event envelope, status, attempts, published_at).
- Inbox/dedupe table for inbound external events.
- Audit event table: append-only, no UPDATE/DELETE grant.
- No business tables. No accounts, no ledger, no customers.

### 7. API work
- API conventions: versioning, error contract (RFC 9457 problem+json shape), pagination,
  `Idempotency-Key` header semantics, correlation header propagation.
- Health, readiness and info endpoints only. No business endpoints.
- OpenAPI generation wired into the build.

### 8. Event work
- Event envelope: `eventId`, `eventType`, `aggregateId`, `aggregateType`, `occurredAt`,
  `producer`, `eventVersion`, `schemaVersion`, `correlationId`, `causationId`.
- Transactional outbox mechanics and relay (ADR-0005).
- Topic naming, partitioning key policy, schema-evolution and compatibility rules.
- No business events defined.

### 9. Security work
- Secrets handling policy; no secrets in source or config committed to the repo.
- Structured logging with field-level redaction of PII/credentials/PANs by default.
- Baseline dependency and secret scanning in the build.
- Security context abstraction (`ActorId`, `ActorType`) that later phases populate.
- Authentication itself is **not** implemented here.

### 10. Observability work
- OpenTelemetry tracing, metrics, structured JSON logging.
- Correlation ID ingress filter and propagation into logs, traces, events, outbox.
- Baseline dashboards and metric naming conventions.

### 11. Testing work
- Test taxonomy: unit / slice / integration (Testcontainers) / contract / architecture.
- `Money` arithmetic, rounding, currency-mismatch and overflow property tests.
- Outbox: at-least-once delivery, crash between commit and publish, relay restart.
- Idempotency kernel: concurrent identical keys, differing payload same key, expiry.
- ArchUnit rules enforcing module boundaries — failing build on violation.
- Testcontainers: PostgreSQL, Kafka, Redis wired and green in CI.

### 12. Failure-engineering work
- Prove: DB commits but response lost → client retry is safe (idempotency kernel).
- Prove: process crashes after commit, before publish → outbox relay recovers.
- Prove: duplicate inbound event → inbox dedupe suppresses second effect.
- Define timeout, retry and backoff defaults; define which operations may never be retried
  blindly.

### 13. Reconciliation implications
No reconciliation yet. Phase 0 guarantees the *evidence substrate*: immutable audit
records, correlation identifiers, and preserved raw external payloads, without which later
reconciliation cannot explain a break.

### 14. Documentation / ADR work
ADR-0001 through ADR-0010 (see [`docs/adr/README.md`](../adr/README.md)).
`MODULE_ARCHITECTURE.md`, expanded `FINANCIAL_INVARIANTS.md`, `DEFINITION_OF_DONE.md`,
`EXECUTION_PROTOCOL.md`, `PHASE_GATES.md`, `BACKLOG.md`.

### 15. Deliverables
- Gradle multi-module Spring Boot build, green in CI.
- `platform` and `sharedkernel` modules with Money, Ids, Clock, envelope, idempotency,
  outbox, inbox, audit, error contract.
- Local infrastructure via Docker Compose (PostgreSQL, Kafka, Redis).
- ArchUnit boundary tests, Testcontainers integration tests.
- Full documentation and ADR set.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 0. Summary: build green; boundary tests enforced; Money,
idempotency, outbox, inbox, audit each covered by passing integration tests including the
crash/duplicate/concurrency cases; observability emitting correlated traces; all ADRs
Accepted; `CURRENT_STATE.md` accurate.

### 17. Risks
- **Over-engineering the kernel.** Mitigation: kernel scope is fixed to the list above;
  anything else is deferred to the phase that needs it.
- **Premature module fragmentation.** Mitigation: fewer, larger modules; split only on
  evidence.
- **Money type churn.** Mitigation: settle ADR-0003 before any persistence exists.
- **Boundary rules unenforced in practice.** Mitigation: ArchUnit failures break the build.

### 18. What must NOT be implemented yet
Customers, identity, authentication, accounts, ledger, payments, any business endpoint,
any business event, any external provider integration, any UI, Kubernetes/Terraform
deployment, service extraction.

---

# Phase 1 — Identity and Customer Foundation

### 1. Objective
Establish who the actors are, prove who they claim to be, and make every subsequent
financial action attributable to an authenticated, authorised actor.

### 2. Business capabilities
Party/customer registration, authentication, MFA, session and device management, account
recovery, authorization model, actor-attributed audit.

### 3. Domains / bounded contexts involved
Party & Customer; Identity & Authentication; Audit.

### 4. Dependencies
Phase 0 (kernel, audit, outbox, idempotency).

### 5. Architecture work
- Separate Party (who exists), Customer (commercial relationship), User/Identity (who logs
  in), Credential, Device, Session — these are distinct aggregates, never one table.
- Decide authorization model: RBAC with attribute constraints; policy evaluation point
  location.
- Decide token strategy and session invalidation semantics (ADR to be written).
- Credential store isolation from customer profile data.

### 6. Data-model work
Party, Customer, Identity, Credential (hashed, never reversible), MFA enrolment, Device,
Session, Role/Permission assignment, Recovery request. Password hashing algorithm and
parameters recorded per credential for rotation.

### 7. API work
Registration, login, MFA challenge/verify, refresh, logout, session list/revoke, device
list/revoke, profile read/update, recovery initiation. Enumeration-safe error responses.
Rate limiting and lockout on all credential endpoints.

### 8. Event work
`PartyRegistered`, `CustomerCreated`, `IdentityCreated`, `AuthenticationSucceeded`,
`AuthenticationFailed`, `MfaEnrolled`, `SessionRevoked`, `CredentialChanged`. Events carry
no credential material and no unnecessary PII.

### 9. Security work
This is the phase where security is the product: password hashing (Argon2id), MFA/TOTP,
WebAuthn/passkey support, brute-force and credential-stuffing controls, session fixation
and rotation, secure recovery flow that cannot be used as an account-takeover vector,
least-privilege roles, and privileged-action audit.

### 10. Observability work
Authentication success/failure rates, lockouts, MFA challenge outcomes, session lifetimes,
recovery attempts. Security-relevant metrics must be alertable.

### 11. Testing work
Authentication and authorization integration tests; negative authorization tests for every
protected endpoint; MFA bypass attempts; session revocation effectiveness; recovery-flow
abuse cases; audit record produced for every privileged action.

### 12. Failure-engineering work
Duplicate registration with same idempotency key; concurrent login and credential change;
session store unavailable; partial MFA enrolment; recovery race with concurrent login.

### 13. Reconciliation implications
None financial. Actor attribution is a precondition for later financial auditability — a
posting whose actor cannot be identified is not auditable.

### 14. Documentation / ADR work
ADR on authentication and session strategy. ADR on authorization model. Update
`DOMAIN_MODEL.md` with settled Party/Customer/Identity distinctions.

### 15. Deliverables
Working registration and authentication with MFA, authorization enforced at the API
boundary, session/device management, actor-attributed audit trail.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 1.

### 17. Risks
- Collapsing Party/Customer/Identity into one entity — very expensive to unpick later.
- Rolling bespoke cryptography. Mitigation: use vetted libraries only.
- Recovery flow becoming the weakest link.

### 18. What must NOT be implemented yet
KYC verification, consent records, accounts, balances, any money movement, credit,
customer risk scoring.

---

# Phase 2 — KYC/KYB and Consent

### 1. Objective
Establish verified identity and lawful basis before the platform is permitted to hold or
move money on a party's behalf.

### 2. Business capabilities
KYC case lifecycle, KYB and beneficial ownership, document capture, sanctions/PEP/adverse
media screening at onboarding, manual review and case management, consent lifecycle.

### 3. Domains / bounded contexts involved
KYC/KYB; Consent; Party & Customer (consumer of verification outcome).

### 4. Dependencies
Phase 1.

### 5. Architecture work
- KYC decision is owned by the KYC context; Party stores only a *reference* to the current
  verification status, never a duplicate authority.
- Screening providers behind adapters; provider verdicts stored as evidence, never as the
  decision itself.
- Jurisdiction-specific requirements behind a policy/configuration boundary.
- Consent is separate from authentication and from authorization.

### 6. Data-model work
KYC Case, KYB Case, Verification Check, Document reference (object storage, encrypted),
Screening Result (raw provider evidence retained), Beneficial Owner, Risk Rating, Review
Task, Consent Record with versioned consent text and grant/withdraw history.

### 7. API work
Case initiation, document upload (pre-signed, size/type constrained), status query,
reviewer decision endpoints (privileged), consent grant/withdraw/query. Customer-facing
status must not leak internal screening detail.

### 8. Event work
`KycCaseOpened`, `KycVerificationCompleted`, `KycDecisionRecorded`, `ScreeningHitRaised`,
`ConsentGranted`, `ConsentWithdrawn`. Downstream contexts react to decisions; they do not
recompute them.

### 9. Security work
PII classification and encryption; document access strictly least-privilege and audited;
reviewer actions require elevated authorization and are audited with reason codes;
four-eyes on high-risk approvals.

### 10. Observability work
Case throughput and age, straight-through-processing rate, screening hit rate, provider
latency and error rate, manual review queue depth.

### 11. Testing work
State machine transitions including all invalid transitions; provider adapter contract
tests with WireMock; duplicate provider callbacks; consent withdrawal effects; reviewer
authorization negative tests.

### 12. Failure-engineering work
Provider timeout with unknown outcome; provider returns result after we timed out;
duplicate webhook; document upload succeeds but case update fails; screening list updated
after decision.

### 13. Reconciliation implications
Screening and verification evidence must be preserved verbatim to support later regulatory
inquiry and dispute of a decision. Evidence retention rules defined here.

### 14. Documentation / ADR work
ADR on screening adapter and evidence retention. ADR on consent modelling. Document the
KYC and consent state machines.

### 15. Deliverables
End-to-end KYC case with simulated provider, manual review path, consent lifecycle, and
an onboarding gate other phases can query.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 2.

### 17. Risks
- Treating a provider's response as the decision.
- Storing raw PII/documents without classification or access control.
- Conflating consent with authorization.

### 18. What must NOT be implemented yet
Ongoing transaction monitoring, behavioural AML, accounts, ledger, money movement,
periodic rescreening automation (Phase 13).

---

# Phase 3 — Accounts and Financial Ledger

### 1. Objective
Establish the authoritative financial record. This is the highest-risk phase in the
programme: everything downstream inherits its correctness.

### 2. Business capabilities
Chart of accounts, ledger accounts, customer accounts and wallets, double-entry journal
posting, balance derivation, holds/authorisations against available balance, statements.

### 3. Domains / bounded contexts involved
Ledger (authoritative postings); Accounts; Wallet. Ledger owns postings and balances;
Accounts owns the customer-facing account product.

### 4. Dependencies
Phase 0 (Money, outbox, audit, idempotency); Phase 1 (actor attribution).

### 5. Architecture work
- Ledger is the single writer of journal entries; no other module may write postings.
- Posting API is a command with an idempotency key, not a balance mutation.
- Balance is a derived projection anchored to postings (ADR-0009), with a documented
  recomputation and verification procedure.
- Distinguish Ledger Account (accounting) from Customer Account (product) from Wallet
  (stored-value product) from Operational Account (platform's own).
- Multi-currency structure defined now (seam for Phase 9); no FX conversion implemented.
- Suspense account type and break record introduced as a seam for Phase 8.

### 6. Data-model work
Chart of Accounts, Ledger Account (type, normal balance, currency), Journal Entry
(immutable, posting date, value date, reference, correlation), Journal Line (account,
direction, money), Balance Snapshot, Hold. Constraints: entries balance per currency;
lines immutable; no UPDATE/DELETE on posted entries; unique idempotency key per posting
scope.

### 7. API work
Account open/close/query, balance query (with explicit "as of" and projection-freshness
semantics), statement generation, hold place/release. Posting is an internal API, not a
public one — no external caller may post arbitrary journal entries.

### 8. Event work
`AccountOpened`, `AccountClosed`, `JournalEntryPosted`, `HoldPlaced`, `HoldReleased`,
`BalanceProjectionUpdated`. `JournalEntryPosted` is published via the outbox in the same
transaction as the posting.

### 9. Security work
Posting authority is a privileged capability. Manual/adjusting entries require elevated
authorization, a reason code, and four-eyes approval. Account data access is
ownership-scoped. Every posting is audited with actor and correlation.

### 10. Observability work
Posting rate and latency, projection lag, hold utilisation, failed posting reasons, a
continuously-evaluated trial-balance-equals-zero metric with alerting.

### 11. Testing work
This phase's tests *are* the deliverable as much as the code:
- balanced-entry invariant, including attempts to post unbalanced entries;
- immutability: UPDATE/DELETE on posted entries rejected at the database level;
- balance derived from postings equals projection, under concurrency;
- concurrent postings to the same account (isolation level and locking behaviour);
- hold cannot exceed available balance; released hold restores availability;
- reversal creates a new compensating entry referencing the original;
- currency mismatch rejected;
- idempotent posting under concurrent identical keys.

### 12. Failure-engineering work
Crash between posting commit and event publication; duplicate posting command; two
concurrent postings racing the same balance; projection rebuild while postings continue;
partial batch posting.

### 13. Reconciliation implications
Foundational. Every balance must be explainable from postings; every posting traceable to
an economic event; suspense accounts exist so that Phase 8 can park unmatched value
without corrupting customer balances.

### 14. Documentation / ADR program
ADR-0002 and ADR-0009 are ratified in practice here. New ADR on isolation level and
concurrency control for postings. New ADR on chart-of-accounts structure. Update
`LEDGER_MODEL.md` with the implemented model.

### 15. Deliverables
Working double-entry ledger with immutable postings, verified balance projections, holds,
statements, and a trial-balance verification job.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 3. No phase gate in this programme is stricter.

### 17. Risks
- **Balance-as-truth drift.** Mitigation: continuous verification job, alerting.
- **Silent history mutation via ORM.** Mitigation: database-level denial of UPDATE/DELETE.
- **Lost updates under concurrency.** Mitigation: explicit isolation/locking decision plus
  concurrency tests.
- **Chart of accounts modelled too narrowly.** Mitigation: design for later GL mapping.

### 18. What must NOT be implemented yet
Transfers between customers, external payments, FX conversion, settlement, reconciliation
matching, GL reporting, interest accrual.

---

# Phase 4 — Internal Transfers

### 1. Objective
Deliver the first complete, customer-visible money movement, entirely inside the ledger,
with an explicit lifecycle and enforced idempotency.

### 2. Business capabilities
Beneficiary management, internal transfer initiation, transfer lifecycle, limits and
velocity seams, transfer history and receipts.

### 3. Domains / bounded contexts involved
Transfers (new); Ledger; Accounts; Party (beneficiary references).

### 4. Dependencies
Phase 3 (ledger), Phase 1 (actor). Phase 2 gate check (verified party may transact).

### 5. Architecture work
- Transfer is its own aggregate with an explicit state machine; it *requests* postings from
  the ledger, it does not write them.
- Transaction boundary: transfer state transition and its posting commit atomically, or the
  transfer records an explicit compensating path.
- Limit-check and risk-decision seams defined (implemented Phase 13).

### 6. Data-model work
Beneficiary, Transfer (state, amount, currency, source/destination, idempotency key,
correlation), Transfer Lifecycle History. Unique constraint on (client, idempotency key).

### 7. API work
`POST /transfers` with mandatory `Idempotency-Key`; transfer status query; transfer list;
beneficiary CRUD. Explicit asynchronous-outcome semantics even though execution is
synchronous here — the contract must not assume synchrony forever.

### 8. Event work
`TransferCompleted`, `TransferFailed`, `TransferReversed` — the terminal facts. Consumers
must tolerate duplicates and ordering issues. *(`TransferInitiated` was listed here until the
Phase 3 → 4 transition: under ADR-0043 it would commit in the same transaction as its own
outcome, and an event that always accompanies its successor is one fact named twice
(ADR-0044). It joins the vocabulary with the first observable initiated state.)*

### 9. Security work
Ownership authorization on source account; beneficiary ownership; step-up authentication at
**beneficiary creation** — `MULTI_FACTOR` when a factor is enrolled; full audit of every
transfer command. *(This read "for high-value or new-beneficiary transfers" until the
Phase 3 → 4 transition: a value threshold is a per-currency versioned policy artefact with
nothing to calibrate it — the `P3-TSK-021` argument — so the structural trigger ships and the
value trigger is a recorded seam on the risk port, Phase 13's.)*

### 10. Observability work
Transfer volume by outcome, failure reasons, latency, idempotency-conflict rate. *(Two items
corrected by the Phase 3 → 4 transition: **value**-by-state meters are refused — an aggregate
money figure in a telemetry store is a financial number outside the ledger's authority — and
the **stuck-transfer detector has no subject** under ADR-0043, there being no durable
intermediate state to be stuck; it arrives with the first asynchronous execution path.)*

### 11. Testing work
Full lifecycle and every invalid transition; insufficient funds; self-transfer; closed or
frozen account; concurrent transfers draining a balance; duplicate submission with same
key; same key with different payload must be rejected, not silently accepted.

### 12. Failure-engineering work
Client timeout then retry; crash mid-transfer; ledger posting succeeds but transfer state
update fails; double submission from two nodes simultaneously.

### 13. Reconciliation implications
Internal transfers are self-reconciling (both legs internal) but must still be traceable:
economic event → transfer → journal entry → lines → balances.

### 14. Documentation / ADR work
ADR on transfer/ledger transaction boundary and compensation strategy. Document the
transfer state machine.

### 15. Deliverables
End-to-end internal transfer, idempotent, audited, with lifecycle and reversal.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 4.

### 17. Risks
- Transfer module writing postings directly, bypassing the ledger's authority.
- Idempotency applied at the HTTP layer only rather than the financial boundary.
- Treating "no funds" as a technical error rather than a domain outcome.

### 18. What must NOT be implemented yet
External payment rails, provider adapters, scheduled/recurring transfers, FX, fees,
merchant flows, actual limit/risk rule engines.

---

# Phase 5 — Payment Infrastructure

### 1. Objective
Introduce the external world: money movement whose outcome is determined by an unreliable
third party, with correct handling of unknown states.

### 2. Business capabilities
Payment intent, payment attempt, payment methods, provider adapters, authorization and
capture, refunds, webhook ingestion, provider state mapping.

### 3. Domains / bounded contexts involved
Payments; Payment Methods; Ledger; Accounts; (seam) Settlement, Disputes.

### 4. Dependencies
Phase 4.

### 5. Architecture work
- Payment Intent (customer-facing objective) is distinct from Payment Attempt (one try
  against one provider). One intent may have many attempts.
- Provider adapters translate provider states into the internal lifecycle; provider vocabulary
  must not appear in the domain.
- **Unknown state is a first-class state**, not an error. Define the reconciliation-by-query
  path for it.
- Authorization, capture, clearing and settlement modelled separately.
- Accounting treatment of authorization (memo/hold) vs capture (posting) decided explicitly.

### 6. Data-model work
Payment Intent, Payment Attempt, Payment Method (tokenised — never raw PAN), Provider
Reference, Authorization, Capture, Refund, Webhook Event (raw payload retained, signature,
dedupe key), Provider State Mapping.

### 7. API work
Intent create/confirm/cancel, attempt status, refund create, payment method attach/detach.
Mandatory idempotency on every money-moving command. Asynchronous completion modelled
explicitly with terminal-state semantics documented.

### 8. Event work
`PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`,
`RefundInitiated`, `RefundCompleted`, `RefundFailed`, `PaymentStateUnknown` *(the
`RefundFailed` addition is the Phase 4 → 5 transition's, with provenance: terminal facts
publish — ADR-0044's doctrine)*. Webhook-driven transitions
must be idempotent.

### 9. Security work
Webhook signature verification and replay-window enforcement; provider credentials in
secret management with rotation; tokenisation so raw card data never enters the platform;
refund authorization is privileged.

### 10. Observability work
Per-provider success/failure/latency, authorization and capture rates, unknown-state
count and age, webhook delivery lag and duplicate rate, stuck-attempt alerting.

### 11. Testing work
Provider adapter contract tests with WireMock including timeouts, 5xx, malformed
responses, and late responses; duplicate webhook delivery; out-of-order webhooks;
signature failure; refund exceeding captured amount; partial refunds; state-mapping table
tests for every provider state.

### 12. Failure-engineering work
The central concern of this phase. Provider times out but did authorise; provider returns
success after we marked failed; webhook arrives before the synchronous response; webhook
never arrives (reconciliation-by-query sweeper); provider returns a state we do not
recognise; duplicate capture.

### 13. Reconciliation implications
Every provider interaction must retain external evidence (request/response/webhook
payload, provider reference, timestamps) — this is the raw material Phase 8 consumes.
Internal "captured" is explicitly not "settled".

### 14. Documentation / ADR work
ADR on payment intent/attempt modelling. ADR on unknown-state handling and
reconciliation-by-query. ADR on webhook ingestion and dedupe. Update `PAYMENT_LIFECYCLES.md`.

### 15. Deliverables
End-to-end payment through a simulated provider with authorization, capture, refund,
webhooks, unknown-state recovery, and ledger postings.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 5.

### 17. Risks
- Assuming a timeout means failure — the single most expensive mistake in payments.
- Provider vocabulary leaking into the domain and into public API contracts.
- Webhook handler with financial side effects that is not idempotent.
- Capturing to the ledger before the money is genuinely capturable.

### 18. What must NOT be implemented yet
Merchants and checkout, settlement file ingestion, reconciliation matching, disputes and
chargebacks, multi-rail routing, FX.

---

# Phase 6 — Checkout and Merchant Platform

*(**Superseded by [`PHASE_6_PLAN.md`](PHASE_6_PLAN.md) and the implementation**, recorded at
the Phase 6 review, `P6-DOC-001`: this pre-phase plan is kept as written, and where it
disagrees with them they are right. Where it now reads wrong: the data model's "Merchant Ledger
Accounts (payable, fee income, reserve)" are one per-merchant `MERCHANT_PAYABLE` —
`FEE_REVENUE` is a platform operational account, and there is no reserve. "Merchant CRUD" has
no update or delete. The checkout has no "expire" route (the leaderless sweeper expires a
session) and no "hosted-checkout completion callback" (completion runs inside the capture's
transaction). `CheckoutSessionCreated` was never built (the session's creation is an audit
record, not an event), and `FeeReturned` is missing. Step-up on a destination change is
conditional, required only when the operator has an active TOTP factor. Fee accrual and
per-merchant error rates were not built. The payable reconciles to captured − fees −
refunded + fees returned − payouts, not captured − fees − payouts.)*

### 1. Objective
Introduce the merchant as a distinct commercial party, and the checkout session as the
customer-facing purchase experience, including fee economics and merchant payouts.

### 2. Business capabilities
Merchant onboarding, merchant accounts, checkout sessions, orders, fee calculation,
merchant payout initiation, merchant-facing reporting.

### 3. Domains / bounded contexts involved
Merchant; Checkout; Payments; Ledger; (seam) Settlement.

### 4. Dependencies
Phase 5; Phase 2 (KYB for merchants).

### 5. Architecture work
- Customer payment and merchant settlement are distinct financial flows and must not be
  collapsed.
- Fee model: who pays, when recognised, gross vs net settlement — decided explicitly.
- Checkout session is short-lived and expiring; it is not the payment.
- Merchant payable is a ledger liability, not a balance field on the merchant record.

### 6. Data-model work
Merchant, Merchant Account, Fee Schedule (versioned), Checkout Session (expiry, state),
Order, Merchant Payout, Merchant Ledger Accounts (payable, fee income, reserve).

### 7. API work
Merchant CRUD (privileged), checkout session create/retrieve/expire, hosted-checkout
completion callback, merchant payout initiation, merchant transaction reporting.

### 8. Event work
`MerchantOnboarded`, `CheckoutSessionCreated`, `CheckoutSessionExpired`, `OrderPaid`,
`FeeAssessed`, `MerchantPayoutInitiated`, `MerchantPayoutCompleted`, `MerchantPayoutFailed`
*(the completion/failure pair added by the Phase 5 → 6 transition, 2026-09-21: terminal
facts publish — ADR-0044's doctrine, the `RefundFailed` precedent — and a payout under
ADR-0051's dispatch-before-call has terminal facts its `Initiated` cannot carry)*.

### 9. Security work
Merchant API authentication distinct from customer authentication; strict tenant isolation
so no merchant can read another's data; payout destination changes require step-up
authorization, four-eyes, and a cooling-off period.

### 10. Observability work
Checkout conversion and abandonment, session expiry rate, fee accrual, payout volume and
age, per-merchant error rates.

### 11. Testing work
Multi-tenant isolation tests (negative authorization across merchants); session expiry
races; payment completing after session expiry; fee calculation and rounding; payout
against insufficient payable.

### 12. Failure-engineering work
Payment succeeds after checkout expired; duplicate order creation; payout initiated twice;
fee schedule changed mid-flight (version pinning).

### 13. Reconciliation implications
Merchant payable balances must reconcile to captured payments minus fees minus payouts.
This is the first true three-way reconciliation target and directly feeds Phase 8.

### 14. Documentation / ADR work
ADR on fee model and revenue recognition timing. ADR on merchant payout accounting.

### 15. Deliverables
Merchant onboarding, working checkout, fee assessment posted to the ledger, merchant
payout with correct accounting.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 6.

### 17. Risks
- Fee rounding creating cent-level ledger imbalance.
- Merchant balance kept as a mutable field instead of a ledger-derived figure.
- Cross-tenant data leakage.

### 18. What must NOT be implemented yet
Settlement file ingestion and matching, disputes, BNPL, multi-currency merchant payouts.

---

# Phase 7 — Cards, Wallets, A2A and Instant Payments

*(**Elaborated by [`PHASE_7_PLAN.md`](PHASE_7_PLAN.md)** and ADR-0059…0062 at the Phase 6 → 7
transition, 2026-09-24: eighteen items across eight milestones in `BACKLOG.md`. This section is
kept as written and made current where it had fallen behind the decisions: an instant rail is
**final on acceptance and settled on the scheme's cycle**, never "settled immediately" (ADR-0062,
`INV-SET-01`); the chargeback ratio per merchant is an operator report, never a metric tag
(ADR-0018's cardinality rule); disputes are context 29, merged into `payments` (ADR-0061); and
card issuing — cardholders, card accounts, physical and virtual cards — is external (ADR-0059
§5).)*

### 1. Objective
Generalise from one payment provider to multiple rails with genuinely different lifecycles,
timing and failure semantics — and introduce disputes.

### 2. Business capabilities
Card payments, wallet payments, account-to-account, instant-payment abstraction, rail
selection, disputes and chargebacks.

### 3. Domains / bounded contexts involved
Payments; Payment Methods; Ledger; Disputes (new); Merchant.

### 4. Dependencies
Phase 5; Phase 6 (disputes are merchant-affecting).

### 5. Architecture work
- Rail abstraction that does not flatten real differences: cards have
  authorization→capture→clearing→settlement; instant rails are final on acceptance and
  settle on the scheme's cycle; A2A may be irrevocable on acceptance.
- Irrevocability and finality per rail documented explicitly — this drives reversal
  strategy.
- Rail selection/routing policy, versioned and explainable.
- Dispute lifecycle with financial effects at each stage.

### 6. Data-model work
Rail, Rail Capability descriptor, Card Payment detail, Wallet Payment detail, A2A Payment
detail, Dispute, Dispute Evidence, Chargeback, Representment. *(Built as `DisputeResponse` - a
representment OR an acceptance, one aggregate because both need the same dispatch protocol -
`P7-TSK-014`; noted by the Phase 7 review.)*

### 7. API work
Rail-agnostic payment API with rail-specific detail objects; dispute notification, evidence
submission, dispute status.

### 8. Event work
`RailSelected`, `PaymentClearedOnRail`, `DisputeOpened`, `DisputeEvidenceSubmitted`,
`ChargebackReceived`, `DisputeResolved`. *(Built as `DisputeResponseSubmitted` for the evidence
fact - the Phase 7 review.)*

### 9. Security work
No storage of raw PAN, CVV or track data anywhere; card detail tokenised at the boundary;
PCI scope explicitly documented and minimised; dispute evidence access controlled.

### 10. Observability work
Per-rail success rate, latency and cost *(cost not built: no Phase 7 rail reports one - owned by
Phase 8 with the processor's fees, the Phase 7 review)*; routing decision distribution; dispute rate and
win rate; the chargeback ratio per merchant (regulatory-relevant — an operator report, since a
merchant tag is unbounded cardinality).

### 11. Testing work
Per-rail lifecycle tests; irrevocable-rail reversal must be rejected, not silently
attempted; dispute lifecycle including duplicate chargeback notifications; routing policy
determinism and version pinning.

### 12. Failure-engineering work
Rail unavailable mid-flight; clearing arrives days later; chargeback on an already-refunded
payment; duplicate chargeback; representment after resolution.

### 13. Reconciliation implications
Each rail produces different settlement evidence with different timing. Clearing files,
dispute adjustments and chargeback fees all become reconciliation inputs.

### 14. Documentation / ADR work
ADR on rail abstraction and finality semantics. ADR on dispute financial treatment.

### 15. Deliverables
Two or more simulated rails with materially different semantics, routing, and a working
dispute/chargeback lifecycle with correct postings.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 7.

### 17. Risks
- A lowest-common-denominator rail abstraction that hides finality differences.
- Attempting reversal on an irrevocable rail.
- Chargeback accounting that double-debits the merchant.

### 18. What must NOT be implemented yet
Settlement file ingestion (Phase 8), FX (Phase 9), fraud scoring (Phase 13).

---

# Phase 8 — Settlement and Reconciliation

### 1. Objective
Prove that internal financial truth agrees with external financial reality, and handle
disagreement through controlled, auditable resolution rather than silent correction.

### 2. Business capabilities
Settlement file ingestion, settlement expectation tracking, matching engine, break
classification, suspense management, investigation workflow, controlled adjustment.

### 3. Domains / bounded contexts involved
Settlement; Reconciliation; Ledger; Payments; Merchant.

### 4. Dependencies
Phase 5 (external evidence), Phase 6 (merchant payables), Phase 7 (multi-rail evidence).

### 5. Architecture work
- Settlement (money actually moved externally) is distinct from internal completion.
- Reconciliation is a first-class capability with its own context, not a batch script.
- Matching is deterministic, versioned, and explainable: every match records which rule
  and which tolerance produced it.
- Breaks are records with a lifecycle, never deletions.
- Resolution posts compensating entries; it never edits history (INV-REV-01).

### 6. Data-model work
Settlement Batch, Settlement File (raw retained in object storage with checksum),
Settlement Line, Expectation, Match, Match Rule (versioned), Tolerance (versioned),
Reconciliation Break (type, severity, state), Investigation, Resolution, Adjustment Entry,
Suspense Account postings.

### 7. API work
Operational APIs: batch upload/status, break list/filter, break assignment, investigation
notes, resolution proposal and approval. All privileged.

### 8. Event work
`SettlementBatchIngested`, `SettlementMatched`, `ReconciliationBreakRaised`,
`BreakInvestigationStarted`, `BreakResolved`, `AdjustmentPosted`.

### 9. Security work
Resolution authority is the most sensitive non-administrative privilege in the platform:
four-eyes approval, reason codes, value thresholds, full audit. Settlement files may
contain PII and must be access-controlled and encrypted.

### 10. Observability work
Match rate, unmatched value and count, break age distribution, break count by type,
suspense account balance and age, time-to-resolution. Ageing suspense balance is an
alertable operational risk indicator.

### 11. Testing work
Every break type: missing internal, missing external, amount difference, currency
difference, fee difference, timing difference, duplicate, reversal. Duplicate file
ingestion; partially corrupt file; out-of-order file arrival; resolution authorization
negative tests; adjustment posting invariants.

### 12. Failure-engineering work
File arrives twice; file arrives late or never; file references unknown internal records;
matching job crashes mid-batch and restarts; two operators resolve the same break
concurrently.

### 13. Reconciliation implications
This phase *is* the reconciliation capability. It must satisfy: preserve evidence,
classify the break, resolve through controlled adjustment (INV-REC-01..04).

### 14. Documentation / ADR work
ADR on matching strategy and tolerance model. ADR on suspense account policy. ADR on break
resolution authority and four-eyes. Update `RECONCILIATION_MODEL.md`.

### 15. Deliverables
Working settlement ingestion, matching engine, break management with investigation and
four-eyes resolution, suspense accounting, and reconciliation reporting.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 8.

### 17. Risks
- Auto-resolving breaks to make dashboards look clean — forbidden.
- Suspense used as a dumping ground with no ageing discipline.
- Non-deterministic matching that cannot be explained to an auditor.

### 18. What must NOT be implemented yet
FX-related reconciliation, GL close and financial statements (Phase 14), regulatory
reporting.

*(**Elaborated by [`PHASE_8_PLAN.md`](PHASE_8_PLAN.md)** and ADR-0064…0073 at the Phase 7 → 8
transition, 2026-09-28: twenty-seven items across eight milestones in `BACKLOG.md`, the model in
[`RECONCILIATION_MODEL.md`](../domain/RECONCILIATION_MODEL.md), rewritten by the transition, and
the machines in
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](../domain/SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md).
The eighteen sections above are kept as written and made current here where they had fallen behind
the decisions; where they disagree with this addendum or the plan, the addendum and the plan are
right. §18 stands, and `PHASE_8_PLAN.md` §17 extends it. Every item of the addendum is built —
`P8-TSK-001`…`-024`, `P8-TST-001` and `P8-TST-002` are complete, none deferred — and the Phase 8
exit review (`P8-DOC-001`, 2026-10-01) corrected each statement below to the code as built; it
read "Until Phase 8's first task lands, nothing in this addendum is implemented".)*

- *§3 and §5 — **two modules, and the expectation is reconciliation's** (ADR-0064). Settlement
  (context 13) and Reconciliation (context 14) are two modules, `settlement` and `reconciliation`,
  with **no build edge between them**: each depends on `ledger`, `platform` and `sharedkernel`
  alone, and `app` composes them with `payments` and `merchant` through ports that are required
  constructor parameters — `SettlementExpectations` (declared in `payments`),
  `PayoutSettlementExpectations` and `PayoutReturns` (`merchant`), `AcceptedBatchIntake` and
  `SettlementReportCollector` (`settlement`), `SettlementCycleReads` (`payments`), and
  `InternalReferenceLookup`, `WaitingPayoutReturns`, `EvidenceTargets`, `TraceEvidence` and the
  repudiation seam `SettlementBatchRepudiations` (`reconciliation`), the last letting `settlement`
  write a batch repudiation's `ACCEPTED → REPUDIATED`, its event and its audit record on the
  approval's connection while `reconciliation` posts the recognition's reversal through
  `ReversalService` itself (ADR-0064 §3; the fourth cross-module transaction, named by the
  transition's consistency review, B5) *(corrected 2026-10-01, `P8-DOC-001`: the ports the tasks
  added, and the reversal's poster as built — this read "letting `settlement` write a batch
  repudiation's reversal")*. `settlement` holds the external side: the source
  register, the Settlement File, the Refused Delivery, the Settlement Batch, the Settlement Line
  and the recognition posting of each accepted batch. `reconciliation` holds the
  internal side, the comparison and the outcome — and with them the **Settlement Expectation**,
  which moves here from `settlement` (`MODULE_ARCHITECTURE.md` M8 amended), because allocation and
  ageing drive its lifecycle and a module never mutates another's rows. §5's first bullet is
  sharpened by ADR-0065 — each counterparty's clearing position is discharged in two evidence hops,
  the counterparty's report recognising its fees and opening a remittance expectation and the
  bank's statement moving cash, with no in-transit account — and by ADR-0067: every externally
  settling completion opens its expectation in its own transaction. §5's matching bullet gains a
  stored snapshot of every candidate a decision saw, claimant order, and tolerances on processing
  fees and dates only, never on an amount already in a position (ADR-0068, `INV-REC-08`). §5's last
  bullet reads through the ledger's own machinery: a person's resolution posts through
  `ledger.AdjustmentService` with origin `RECONCILIATION` and a closed reason code, an `EVIDENCED`
  resolution posts nothing of its own, and accepted evidence is reversed only by a four-eyes
  `REPUDIATE_BATCH`, through `ReversalService`.*
- *§4 and §13 — **Phase 7's evidence, as the transition's gate repaired it.** Phase 8 reads the
  tree as it stands and claims no gap the gate closed (`PHASE_8_PLAN.md` §2). Each unmatched pay-in
  confirmation records its `cause` (`UNATTRIBUTED`, `ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`), the
  `named_reference` it named, its `settlement_cycle` and, exactly when attributed, its `attempt_id`
  (payments `V023`), its raw statement reached through `payments.provider_evidence`'s fifth subject;
  it still has no state and no resolution, and gains its suspense item and owning break in this
  phase (`P8-TSK-020`), an attributed parking resolved by a four-eyes `TRANSFER_TO_ACCOUNT` crediting
  the named attempt's counterparty, never by a guess. `payments.scheme_execution_claim` names one
  explanation per scheme reference, and a scheme reference no claim holds names no completed
  execution: after grace it types `MISSING_INTERNAL` when its other references name an operation
  still in flight, and `UNKNOWN_EXTERNAL` otherwise. A second, different network clearing of one
  capture (`SECOND_PRESENTMENT`) is loud but rests only in the retained evidence: the cleared amount
  reaches this phase as the PSP report's own `CAPTURE` line (ADR-0065), and a second presentment
  parks with its break, never absorbed. A closed merchant's payable account takes no posting, so a
  payout return to it parks (`REVERSAL_MISMATCH`, `RETURN_NOT_APPLICABLE`) for a four-eyes transfer
  to an account that can take it. Every Phase 8 transaction posting several entries over shared hot
  rows pre-locks their union in the balance projection's order before its first posting
  (`PostingService.lockBalancesInOrder`, the rule the gate wrote for dispute postings); the pull's
  `settlement.pull_permit` advances strictly on every renewal, the send permits' repaired shape; and
  every pull source's URL joins `ProviderTransportGuard`, startup refused unless it is `https` or
  `sftp` off loopback — and, as built, the pulls speak HTTPS only: the one adapter refuses any other
  scheme, `sftp` included, at construction, so such a source fails at startup *(`P8-DOC-001`,
  2026-10-01)*.*
- *§6 — **raw files encrypted in PostgreSQL, not object storage, and the terms as built**
  (ADR-0066). "Settlement File (raw retained in object storage with checksum)" now reads: raw
  retained **encrypted in PostgreSQL** behind the `SettlementFileStore` port — ordered chunks of at
  most 1 MiB, each AES-256-GCM under `FINAPP_SETTLEMENT_FILE_KEY` with its AAD bound to file,
  source, checksum and position, and the whole-plaintext SHA-256 verified on every read. ADR-0066
  re-assesses ADR-0036 and names the triggers for object storage — a source's daily volume above
  256 MiB, total evidence above 50 GiB, a real format exceeding the 8 MiB and 50,000-line bound, or
  production deployment — a move then being an adapter change plus a data migration. A delivery
  carrying a card-number or bank-identifier shape is refused at the door and only its metadata
  kept (O3): for a refused delivery, `INV-PAY-02` and `INV-RAIL-03` take precedence over
  `INV-HIST-02`. The terms: the **Settlement Line** is the canonical, immutable external record
  (`settlement.line`; the glossary's Settlement Record), and reconciliation disposes a working copy
  of it, the External Item, so the line stays evidence; the **Expectation** is the Settlement
  Expectation, reconciliation's, the `REMITTANCE` expectation a report's acceptance opens included;
  a **Match** is a Match Decision — its pinned rule set and a snapshot of every candidate it saw —
  together with the Match Allocations it produced; **Match Rule** and **Tolerance** are members of
  a Matching Rule Set versioned per source and activated four-eyes (the module register's labels,
  never a bare "Allocation" or "Rule Set", which would collide with `lending`'s and `risk`'s);
  **Investigation** is the break's case file, with no machine of its own; the **Adjustment Entry**
  is the ledger's `ADJUSTMENT` entry beneath an approved `RECONCILIATION`-origin
  `adjustment_proposal`; **Suspense Account postings** are `SUSPENSE_UNMATCHED` lines, each tracked
  as a Suspense Item owned by exactly one break (`INV-REC-09`). New beside them: the Remittance, the
  merchant's `payout_return` (ADR-0073), and four operational purposes, each arriving with its
  first poster — `PROCESSING_COSTS`, `RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS` and
  `CASH_AT_BANK`, the settlement account's cash. The merchant payable's drill-down gains
  `payoutsReturned` (`P8-TSK-019`) and `reconciliationAttributed` — every payable line in a
  `RECONCILIATION`-origin `ADJUSTMENT` entry, whatever it faces, built by `P8-TSK-015` with its
  first poster (`INV-MER-02`; the transition's consistency review, A12 and A13).*
- *§8 — **the events as decided** (ADR-0064). The list now reads:
  `settlement.SettlementFileRejected`; `settlement.SettlementBatchAccepted`, replacing
  `SettlementBatchIngested` — the fact is the batch recognised once, not the file read;
  `settlement.SettlementBatchRepudiated`; `reconciliation.ReconciliationRunCompleted`, replacing
  the per-record `SettlementMatched` with one fact per run carrying counts per outcome;
  `reconciliation.SettlementExpectationSettled`; `reconciliation.SettlementExpectationOverdue`,
  replacing the `SettlementExpectationUnmet` that `MODULE_ARCHITECTURE.md` planned;
  `reconciliation.ReconciliationBreakRaised`, `reconciliation.BreakInvestigationStarted` and
  `reconciliation.BreakResolved`, kept; and `merchant.MerchantPayoutReturned`. **`AdjustmentPosted`
  is dropped**: it collides with the ledger's audit action `ledger.AdjustmentPosted`, and
  `BreakResolved`'s `journalEntryId` with `ledger.JournalEntryPosted` already carries the fact.
  Every event carries identifiers, enums and counts only — never an amount, a reference value, a
  note or a file byte — and Phase 8 has no Kafka consumer: its events notify future consumers, and
  no correctness rests on them (`INV-EVT-04`).*
- *§9 — **authority as decided** (ADR-0066, ADR-0071). "Value thresholds" resolve to four-eyes
  **whenever value is at issue or the resolution posts**: a zero-value, zero-posting `ACKNOWLEDGE`
  is single-person only on a `TIMING_DIFFERENCE` raised by a timing detector (`LATE_MATCH`,
  `CYCLE_MISMATCH`), every other acknowledgement four-eyes (reconciliation `V014`, `P8-TST-002`)
  *(corrected 2026-10-01, `P8-DOC-001`: this read "a zero-value, zero-posting `ACKNOWLEDGE` is
  single-person", which let one person close a diverged replay)*, `EVIDENCED` is the platform's
  alone, and value-banded approver escalation
  (six-eyes) is deferred. The per-currency high-value threshold — 1,000.00 EUR, GBP and USD in rule
  set v1 (O7) — escalates a break's severity, never its approver count. Four permissions and two
  pairwise-disjoint roles (O1): `RECONCILIATION_OPERATOR` {`SETTLEMENT_INGEST`,
  `RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`} and `RECONCILIATION_CONTROLLER`
  {`RECONCILIATION_ADMINISTER`} — whoever can loosen a tolerance cannot resolve the breaks it would
  hide. An uploaded file is inert until a second person attests it, a pulled one arrives over its
  source's own confined credential, and a readmitted file inherits its original's authentication
  only when the original was pulled or attested (or is a readmission that inherited) — a
  `DECLINED` original passes nothing on — otherwise the readmission is itself attested, by a
  person distinct from every submitter along its chain (`INV-SET-07`; settlement `V009`); a
  `CONFLICTING_BATCH` original is readmissible once no live batch holds its identity
  (`ConflictingBatchStands` while one does) *(corrected 2026-10-01, `P8-DOC-001`: as `P8-TSK-022`
  built it)*; every read of a file's content is reasoned and audited (`INV-REC-10`).*
- *§10 — **no value in any metric; the value figures are audited operator reports** (ADR-0072).
  ADR-0018 keeps every amount out of metrics, so §10's value items become operator reports under
  `/v1/operator/` and `RECONCILIATION_INVESTIGATE`, each read writing `reconciliation.ReportRead`
  naming the report and period only (the `payments.ChargebackRatioRead` precedent): **unmatched
  value** is `/reports/reconciliation/unmatched`, the **suspense account balance**
  `/reports/reconciliation/suspense` (CREDIT and DEBIT items gross, never netted), beside
  `/positions`, `/summary` and `/provider-costs`. The counts and ages stay metrics: the match rate
  is `finapp.reconciliation.item` by `outcome`, the unmatched count
  `finapp.reconciliation.item.unmatched`, break count by type `finapp.reconciliation.break.raised`
  and `.open` by `type` and `severity`, break age `finapp.reconciliation.break.age` — the oldest
  open break per severity, alerted per severity — suspense age `finapp.reconciliation.suspense.age`,
  and time-to-resolution `finapp.reconciliation.resolution.latency`. "Ageing suspense balance is an
  alertable operational risk indicator" is met by alerting on suspense age and on
  `finapp.reconciliation.suspense.unowned`, which must read 0, the balance itself living in the
  report. Phase 7's per-rail cost, owned here, is settled the same way: the counterparties' fees
  are recognised from their evidence into `PROCESSING_COSTS` and reported by
  `/reports/reconciliation/provider-costs`, never as a metric. The full series list is
  `PHASE_8_PLAN.md` §15.*
- *§14 — **the ADRs written**: ADR-0064…0073, each Proposed (2026-09-28, the Phase 7 → 8
  transition). The three §14 asks for are ADR-0068 (matching strategy, rule versioning and
  tolerance model), ADR-0070 (suspense account policy and ageing) and ADR-0071 (break resolution
  authority and four-eyes thresholds). Seven more were needed: ADR-0064 (settlement holds external
  evidence; reconciliation holds the expectations and the comparison), ADR-0065 (each
  counterparty's clearing position is discharged in two evidence hops; cash moves only on the
  bank's statement), ADR-0066 (raw settlement files screened at the door, authenticated by pull
  credential or second-person attestation, and retained encrypted in PostgreSQL behind a port),
  ADR-0067 (every externally settling completion opens its expectation in its own transaction),
  ADR-0069 (break taxonomy and lifecycle), ADR-0072 (amounts never enter metrics) and ADR-0073 (a
  payout return is a merchant fact applied from settlement evidence; the payout's push-rail
  convergence trigger did not fire). ADR-0036, ADR-0040, ADR-0057, ADR-0060 §6 and ADR-0062 are
  annotated. `RECONCILIATION_MODEL.md` is rewritten, and
  `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` states every machine before the first task.
  ADR-0063 is `X-TSK-005`'s, so the numbering starts at ADR-0064;
  the two cross-cutting tasks the design found, `X-TSK-010` (AAD bound in the four existing
  ciphers) and `X-TSK-011` (the documentation of clock-read posting dates), sit outside the phase.*
- *§2 and §15 — **the milestone map.** The capabilities and the deliverables land across eight
  milestones:*
  - *M8.1 Evidence intake — `P8-TSK-001`…`-003`: the two modules, the encrypted file store and the
    door screen, upload with attestation and audited evidence access.*
  - *M8.2 Every settling completion is expected — `-004`…`-007`: settlement expectation tracking,
    adjustments carrying an origin and a reason code, the opening position and the position proof.*
  - *M8.3 Card settlement reported end to end — `-008`…`-013`: settlement file ingestion for the
    PSP, acceptance with fee recognition, breaks and suspense as records, the matching engine, fees
    and corrections, grace, ageing and late evidence.*
  - *M8.4 Investigation and controlled resolution — `-014`, `-015`: the investigation workflow,
    and controlled adjustment as four-eyes resolution through the ledger.*
  - *M8.5 Cash confirmed — `-016`: the bank statement recognising cash, remittances matched.*
  - *M8.6 Every counterparty — `-017`…`-020`: the instant scheme's cycle report, the payout
    provider's report, payout returns (O2) and Phase 7's unmatched confirmations under suspense
    management.*
  - *M8.7 Operating it — `-021`…`-024`: pull acquisition, rule-set administration, reprocessing,
    readmission, requeue and replay, batch repudiation, and reconciliation reporting with the
    meters.*
  - *M8.8 Proof — `P8-TST-001`, `P8-TST-002`, `P8-DOC-001`: the storm, the battery and the exit
    review against `PHASE_GATES.md` §Phase 8.*

  *If the phase must shrink, `P8-TSK-021` is cut first (upload with attestation suffices), then
  `-019` (the four-eyes `TRANSFER_TO_ACCOUNT` fallback), then `-023` (the attestation and pull
  controls remain), each recorded with an owner (O6). Not exercised: the phase did not shrink, and
  all three were built (`P8-DOC-001`, 2026-10-01).*
- *O1–O7 — **the transition's decisions, each the owner's to revisit** (recorded in
  `PHASE_8_PLAN.md` §2): O1, two pairwise-disjoint roles; O2, payout returns applied
  automatically, with a four-eyes `TRANSFER_TO_ACCOUNT` as the fallback; O3, PII-bearing files
  refused at the door; O4, the simulated bank opening at zero, an equity account waiting for
  Phase 14; O5, a gain recognised only after 90 days — the minimum age of the rule set the
  suspense item's owning break pins (`P8-DOC-001`'s correction) — four-eyes; O6, the cut order
  `P8-TSK-021`, `-019`, `-023`, never exercised; O7, a high-value severity threshold of 1,000.00
  per currency.*

---

# Phase 9 — FX and Cross-Border Payments

### 1. Objective
Introduce currency conversion, exposing the platform to rate risk, rounding asymmetry and
multi-currency accounting.

### 2. Business capabilities
FX quotes, spreads, rate locks and expiry, currency conversion, multi-currency balances,
cross-border payment workflow, corridor rules.

### 3. Domains / bounded contexts involved
FX; Cross-Border Payments; Ledger; Payments; Accounts.

### 4. Dependencies
Phase 3 (multi-currency ledger seam), Phase 5, Phase 8 (FX creates new break types).

### 5. Architecture work
- FX Quote, Exchange Rate and FX Trade are distinct concepts.
- A quote has an explicit validity window; expiry is a domain event, not an error.
- Conversion posts through an FX position/revaluation account — never a single-currency
  entry that silently changes currency (INV-FX-01).
- Rounding policy per currency pair is explicit, versioned and audited.
- Spread/margin is recognised as revenue explicitly, not hidden in the rate.

### 6. Data-model work
FX Quote (rate, spread, base/quote, validity, version), Exchange Rate source and snapshot,
FX Trade, FX Position, Currency configuration (minor units, rounding mode), Corridor.

### 7. API work
Quote request with explicit expiry, quote-locked conversion execution referencing quote id,
multi-currency balance query, cross-border payment initiation with disclosed rate and fees.

### 8. Event work
`FxQuoteIssued`, `FxQuoteExpired`, `FxTradeExecuted`, `CurrencyConverted`,
`CrossBorderPaymentInitiated`, `CrossBorderPaymentSettled`.

### 9. Security work
Rate source integrity and staleness detection; quote tampering prevention (server-side
quote authority); sanctions screening on cross-border counterparties; corridor-level
policy enforcement.

### 10. Observability work
Quote-to-trade conversion rate, quote expiry rate, rate staleness age, FX position by
currency, realised vs expected spread, rounding residual accumulation.

### 11. Testing work
Rounding in both directions across currencies with different minor units (JPY 0, USD 2,
BHD 3); expired quote rejected; stale rate rejected; conversion preserves total value
across the FX position account; multi-currency trial balance per currency.

### 12. Failure-engineering work
Rate feed unavailable; rate feed returns stale or implausible values; quote expires between
validation and execution; conversion succeeds but downstream payment fails; settlement in a
different currency than expected.

### 13. Reconciliation implications
New break types: rate difference, spread difference, conversion timing difference. Trial
balance must hold **per currency**, and FX position accounts must be explainable.

### 14. Documentation / ADR work
ADR on FX quote/rate-lock model. ADR on multi-currency accounting and revaluation. ADR on
rounding policy.

### 15. Deliverables
Working quote lifecycle, currency conversion with correct multi-currency postings, an
end-to-end cross-border payment, and per-currency trial balance verification.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 9.

### 17. Risks
- Rounding residuals silently created or destroyed (money creation — forbidden by INV-BAL-03).
- Client-supplied rates trusted.
- Single-currency assumptions baked into Phase 3 surfacing here as rework.

### 18. What must NOT be implemented yet
Hedging, treasury management, real market data connectivity, FX P&L reporting (Phase 14).

*(**Elaborated by [`PHASE_9_PLAN.md`](PHASE_9_PLAN.md)** and ADR-0074…0083 at the Phase 8 → 9
transition, 2026-10-02: thirty items across nine milestones in `BACKLOG.md`, with `X-TSK-013`
scheduled inside the phase, and the machines in
[`FX_AND_CROSS_BORDER_LIFECYCLES.md`](../domain/FX_AND_CROSS_BORDER_LIFECYCLES.md). The eighteen
sections above are kept as written and made current here where they had fallen behind the
decisions; where they disagree with this addendum or the plan, the addendum and the plan are
right. §18 stands, and `PHASE_9_PLAN.md` §17 extends it. Every item of the addendum is built —
`P9-TSK-001`…`-027`, `X-TSK-013`, `P9-TST-001` and `P9-TST-002` are complete, none cut under O8 —
and the Phase 9 exit review (`P9-DOC-001`, 2026-10-07) corrected each statement below to the code
as built; it read "Until Phase 9's first task lands, nothing in this addendum is implemented".)*

- *§3 and §5 — **two new modules, and every seam is a port** (ADR-0074…0080). FX (context 15)
  and Cross-Border Payments (context 16) are two modules, `fx` and `crossborder`, with **no build
  edge between them and none to `payments`, `kyc` or `accounts`**: each depends on `ledger` and
  `platform` alone, `sharedkernel` reaching it through `platform` (`FxModuleIsolationTest`,
  `CrossborderModuleIsolationTest`, each with planted probes), and `app` composes them through ports
  that are required constructor parameters — `FxProvider`, `RateSource`, `ConversionParticipants` and
  `FxSettlementExpectations` (`fx`); `CrossBorderFx`, `CrossBorderExecution`, `CorridorDirectory`,
  `CounterpartyScreening` and the Phase 13 seams `CrossBorderLimitCheck` and
  `CrossBorderRiskDecision` (`crossborder`) — *as built there is no `CrossBorderParticipants`: a
  return opens its wallet through fx's `ConversionParticipants` inside `app`'s
  `CrossBorderCompletion`* —;
  `CorridorRail` and `OutboundCreditComposition` (`payments`); `CounterpartyScreeningProvider`
  and `ScreeningOutcomeListener` (`kyc`); the scoped `WaitingPayoutReturns` and
  `ResolvedCorridorReturns` (`reconciliation`). §5's bullets are sharpened by the ADRs: the quote
  is a **frozen posting plan** with stored rate provenance — reference → provider firm quote (the
  lock) → internal → customer → executed → cover-executed — and the trade posts exactly the
  plan (ADR-0074, ADR-0075); conversion posts through `FX_POSITION` per currency, kept
  ASSET/DEBIT with its sign defined, and there is no revaluation before a reporting currency
  exists (ADR-0076, Phase 14); rounding policy and rate scale are per pair in the versioned,
  four-eyes pricing policy, every derivation under a named rounding (ADR-0074, `INV-HIST-04`);
  spread and markup are one explicit `FX_SPREAD_REVENUE` line with stored attribution, never
  concealed in the rate (`INV-FX-03`); and each accepted quote is covered with the FX provider
  exactly once, however the provider answers (ADR-0077).*
- *§4 — **the dependencies, and §18's contradiction resolved.** Phase 9 enters on Phase 3's
  multi-currency ledger seam, Phase 5's payment lifecycle and Phase 8's settlement and
  reconciliation machinery, as written. Phase 8's §18 deferred "FX-related reconciliation" with
  a "(Phase 14)" reading while `PHASE_8_PLAN.md` §17 said Phase 9; resolved here, as the
  transition's disposition of Phase 8's hand-overs records: **FX trade and corridor
  reconciliation are Phase 9's** — two new counterparty positions discharged in ADR-0065's two
  evidence hops, reconciliation never converting — and **FX P&L reporting and revaluation are
  Phase 14's**. The transition also repaired, before the boundary, Phase 8's latent `FeeCheck`
  defect (a cross-currency fee line threw instead of raising a typed `CURRENCY_MISMATCH`) and
  entered the inherited debts in the register, the Phase 6 0/3-minor fee-batch deferral
  (`P9-TSK-003` pays it) and the instance-stamped Phase 5–7 send permits (`X-TSK-013`, M9.9)
  among them.*
- *§6 — **the data model as decided.** "FX Quote (rate, spread, base/quote, validity, version)"
  now reads: the FX Quote, single-use, owner-bound, both fixed sides (O3), valid to `expires_at`
  on the database clock, frozen as a posting plan with its provenance and residual — the quote
  **is** the rate lock, backed by the provider's firm quote, which outlives it by the cover
  margin (ADR-0075). Beside it: the Exchange Rate snapshot (independent reference, observation
  and receipt times, plausibility band, fail-closed staleness); the FX Trade (born once per
  quote, `UNIQUE (quote_id)`); the **FX Cover** with its attempts, execution fact and realised
  result — the platform's back-to-back deal, not hedging, netting or treasury (ADR-0077); the
  versioned pricing and corridor policies with their availability and enable proposals; the
  Cross-Border Beneficiary (known by provider reference, screened by kyc before pricing); the
  Payment Offer (immutable facts on the quote, not a second machine); the Cross-Border Payment
  (five states) and `payments`' **Outbound Credit**, which owns the provider's ambiguity; the
  born-once cancellation request; `ledger.counterparty` with `OwnerKind.COUNTERPARTY` and the
  counterparty-keyed `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` (ADR-0078); and "Currency
  configuration" dissolved — minor units are the JDK's, pinned by a test and a startup guard
  (ADR-0074). `FX_POSITION` is the ledger's account, explained by `fx`'s proof, never a module's
  table. Corridor stays `crossborder`'s policy object: (source currency, destination currency,
  destination country) with ordered candidate rails, fees, limits and screening validity.*
- *§7 — **the API work as decided.** The customer doors: quote request (both fixed sides,
  keyed, the claim before the provider call), quote read and cancellation, pairs discovery,
  conversion by quote id (synchronous and final), add-a-currency, per-currency balances;
  beneficiary registration, read and revocation; cross-border quote (the offer), authorization
  (`202`, completion by hinted inquiry or sweep), status and cancellation by recall. The
  operator doors: pricing and corridor policies and availability (four-eyes), the counterparty
  screening decision, the FX trade reversal (`P9-TSK-025`), the first-rule-set door, and the
  audited FX and corridor reports, provenance and trace. **No request schema carries a rate**
  (`RatesAreNeverClientSuppliedTest` and the OpenAPI request-schema guard, each with a planted
  violation); multi-currency balance queries answer one balance per currency, never summed; the
  OpenAPI baseline grows by addition only.*
- *§8 — **the events as decided.** `FxQuoteIssued`, `FxQuoteExpired` and `FxTradeExecuted` are
  built as named; **`CurrencyConverted` is folded into `FxTradeExecuted`** (purpose
  `CONVERSION`; one fact, one event); `CrossBorderPaymentInitiated` is built; and
  **`CrossBorderPaymentSettled` is not built** — settlement is reconciliation's fact, carried by
  the existing `reconciliation.SettlementExpectationSettled` (kinds `FX_SELL_LEG`, `FX_BUY_LEG`,
  `CROSSBORDER_PAYOUT`, `CROSSBORDER_RETURN`). New beside them: `FxQuoteAccepted`,
  `FxQuoteCancelled`/`FxQuoteAbandoned`, `FxTradeReversed`, the cover outcomes, the policy and
  availability facts, the payment's `InTransit`/`Delivered`/`Failed`/`Returned`, the beneficiary
  lifecycle, `CrossBorderCancellationRequested`, `accounts.WalletCurrencyAdded` and
  `kyc.CounterpartyScreeningDecided`. Payloads carry identifiers, enums and minor-unit strings
  with currency and scale — **no rate, no name, no provider reference value** — and Phase 9 has
  no Kafka consumer: no correctness rests on an event (`INV-EVT-04`).*
- *§9 — **security as decided** (ADR-0075, ADR-0080, ADR-0081, ADR-0083). Rate-source
  integrity: the reference is independent, plausibility-banded and fail-closed on staleness, and
  an implausible or incoherent provider rate is never priced. Quote tampering: the quote is
  server-authoritative, a client rate refused `422`. Sanctions screening on counterparties is
  **kyc's transaction-time `CounterpartyScreening`**, on the beneficiary before pricing (O4): an
  unverified payee is always decided by a person, unavailability means unpayable, and the
  reviewer's permission is `COUNTERPARTY_SCREENING_REVIEW` under `KYC_REVIEWER`. Corridor-level
  policy is versioned and four-eyes under `CROSSBORDER_ADMINISTER`. Five permissions —
  `FX_ADMINISTER`, `CROSSBORDER_ADMINISTER`, `FX_TRADE_REVERSE`, `FX_INVESTIGATE`,
  `COUNTERPARTY_SCREENING_REVIEW` — and the `FX_CONTROLLER` role (identity `V019`; `P9-TSK-025` was
  built, so none was dropped), separated so whoever sets
  prices can neither reverse trades nor clear screenings; new confined credentials per concern;
  and **provider callbacks are hints** (ADR-0083): a signed-but-forged callback moves nothing,
  because outcomes are adopted only from an authenticated inquiry.*
- *§10 — **no value in any metric** (ADR-0072, unchanged). §10's value items become audited
  operator reports (`P9-TSK-027`): FX position by currency with its open legs
  (`/v1/operator/reports/fx/position`), realised vs expected spread, residual accumulation and
  realised P&L (`/v1/operator/reports/fx/revenue`), and the corridor report
  (`/v1/operator/reports/cross-border/corridors`). The counts and ages stay metrics: quote-to-trade and expiry rates by
  ratio from `finapp.fx.quote.closed`, rate staleness `finapp.fx.rate.age`, residual
  **frequency** (never an amount) `finapp.fx.residual`, the cover series, the proof gauges
  `finapp.fx.proof` and `finapp.fx.plan.verdict`, the outbound and in-transit ages, the review
  backlog and `finapp.reconciliation.rule.set.missing` (exported as
  `finapp_reconciliation_rule_set_missing`). The full series list is `PHASE_9_PLAN.md` §15, as built
  per its §20.*
- *§13 — **no new break types; reconciliation never converts** (ADR-0082). "Rate difference,
  spread difference, conversion timing difference" resolve into the existing fourteen-type
  taxonomy: FX legs reconcile as **single-currency expectations** (`FX_SELL_LEG`, `FX_BUY_LEG`,
  keyed by `COVER_REF` — as built currency-qualified, `Tn:<currency>`, so a cover's two legs hold
  two keys), so a rate discrepancy surfaces as `AMOUNT_MISMATCH` on the mis-stated leg's own
  currency under the new cause `FX_LEG_DIFFERS`, timing as `TIMING_DIFFERENCE` under
  `VALUE_DATE_DIFFERS`, and a mismatched amount is **never converted to compare**. A spread
  difference is not a reconciliation break — no external evidence states the platform's spread —
  and is proven instead by `FxPlanVerification` (`P9-TSK-013`). A batch in a currency its
  counterparty does not settle is rejected at parse, `CURRENCY_NOT_SETTLED` (settlement `V015`).
  `RECONCILIATION_MODEL.md` §16 states it as built. The trial balance holds per currency (all five), and `FX_POSITION` is explained
  by open legs — the FX books proof, flipped by a planted raw line.*
- *§14 — **the ADRs written**: ADR-0074…0083, each Proposed at the Phase 8 → 9 transition
  (2026-10-02) and Accepted by the exit review (`P9-DOC-001`, 2026-10-07), read against the code and
  corrected first. The three §14 asks for are ADR-0075 (the FX quote and rate-lock model),
  ADR-0076 (multi-currency accounting through `FX_POSITION`; revaluation explicitly deferred to
  Phase 14) and ADR-0074 (conversion arithmetic and rounding policy). Seven more were needed:
  ADR-0077 (the decoupled cover), ADR-0078 (counterparty-keyed clearing positions), ADR-0079
  (cross-border payments and the Outbound Credit), ADR-0080 (corridors, beneficiaries and
  selection), ADR-0081 (counterparty screening is kyc's), ADR-0082 (FX and corridor settlement
  and reconciliation) and ADR-0083 (callbacks are hints). ADR-0050 and ADR-0073 are annotated.
  `FX_AND_CROSS_BORDER_LIFECYCLES.md` stated every machine before the first task, and was
  corrected to the code by the exit review.*
- *§2 and §15 — **the milestone map.** The capabilities and the deliverables land across nine
  milestones:*
  - *M9.1 Foundations — `P9-TSK-001`…`-004`: the two modules and schema floors, `ExchangeRate`
    and the conversion plan, JPY and BHD postable everywhere, multi-currency wallets.*
  - *M9.2 Rates and quotes — `-005`…`-008`: reference rates, the FX provider port and
    simulator, the pricing policy and FX administration, the quote lifecycle — **a price**.*
  - *M9.3 A conversion, booked and covered — `-009`…`-012`: wallet conversion in one entry with
    margin and residual explicit, counterparty-keyed positions, the FX source, the cover
    executed exactly once.*
  - *M9.4 FX explained and settled to cash — `-013`, `P9-TST-002`: the FX books proof and plan
    verification, FX breaks typed, the value battery over ≥ 10,000 conversions.*
  - *M9.5 Corridors, beneficiaries, screening — `-014`…`-017`: the corridor rail and its
    source, corridor policy, counterparty screening in kyc, beneficiaries registered, screened,
    reviewed and revocable.*
  - *M9.6 A cross-border payment end to end — `-018`…`-022`: offers, authorization and
    dispatch, outbound resolution and completion, unwinds, corridor settlement to cash — **a
    payment abroad**.*
  - *M9.7 Return, cancellation, correction — `-023`…`-025`: returns exact, cancellation by
    recall, the operator FX trade reversal.*
  - *M9.8 A second provider of each kind — `-026`: quote-time failover and corridor selection,
    nothing netted across counterparties (cut first).*
  - *M9.9 Operating it, and proof — `-027`, `X-TSK-013`, `P9-TST-001`, `P9-DOC-001`: meters,
    reports and the trace, database-stamped send permits, the storm, the exit review against
    `PHASE_GATES.md` §Phase 9.*

  *If the phase had to shrink (O8), `P9-TSK-026` was to be cut first, then `P9-TSK-025`; as built
  neither was cut — every milestone above is complete.*
- *O1–O10 — **the transition's decisions, each the owner's to revisit** (recorded in
  `PHASE_9_PLAN.md` §2): O1, principal — book at acceptance, one back-to-back cover per accepted
  quote; O2, a return applied automatically only when exactly the instructed credit comes back,
  the fee refunded, every other return parked for a person; O3, both fixed sides; O4, compliance
  review on the beneficiary, before pricing, decided by kyc; O5, provider callbacks are hints;
  O6, JPY and BHD, with the four existing sources' v2 rule-set successors carrying their
  per-currency rows; O7, the pricing and corridor policy v1 defaults, activated four-eyes with
  no seed; O8, the cut order `P9-TSK-026` then `-025`; O9, `OUR` only — the beneficiary receives
  the quoted destination amount; O10, no conversion fee in Phase 9 — margin only, fees are
  `crossborder`'s.*

---

# Phase 10 — Credit Decisioning

### 1. Objective
Make reproducible, explainable, versioned credit decisions — decisions the platform can
defend to a regulator or a declined applicant years later.

### 2. Business capabilities
Credit profile, bureau data integration, affordability assessment, risk scoring, policy
rules engine, decision recording, reason codes, adverse action explanation.

### 3. Domains / bounded contexts involved
Credit; Party & Customer; Consent (bureau access requires lawful basis).

### 4. Dependencies
Phase 1, Phase 2 (identity, consent).

### 5. Architecture work
- Separate credit data, credit profile, risk assessment, underwriting and decision.
- Policy is a **versioned artefact**, not application conditionals (`CREDIT_MODEL.md`).
- A decision is reproducible from: input snapshot/reference, policy version, model version,
  reason codes, outcome, timestamp, context.
- Bureau adapters isolate provider formats; bureau responses retained as evidence.
- Score is not a decision; a decision is not an outcome.

### 6. Data-model work
Credit Profile, Bureau Request/Response (evidence retained), Credit Score, Risk Score,
Policy Version, Rule, Decision (immutable), Reason Code, Decision Input Snapshot, Exposure.

### 7. API work
Decision request (idempotent), decision retrieval, reason-code explanation, policy
management endpoints (privileged, versioned, audited).

### 8. Event work
`CreditProfileUpdated`, `BureauDataRetrieved`, `CreditDecisionRequested`,
`CreditDecisionRecorded`, `PolicyVersionActivated`.

### 9. Security work
Bureau access requires recorded consent and is itself audited; credit data is highly
sensitive PII with strict retention limits; policy changes require four-eyes and are
audited; decisions are immutable.

### 10. Observability work
Approval/decline rates by policy version, decision latency, bureau availability and cost,
reason-code distribution, policy-version drift detection.

### 11. Testing work
Decision reproducibility: replaying the same inputs against the same policy version yields
the identical outcome and reason codes; policy version pinning; consent-absent bureau call
rejected; every reason code exercised; adverse decision always carries reasons.

### 12. Failure-engineering work
Bureau unavailable (documented fallback: decline, refer, or degraded policy — chosen
explicitly); bureau returns partial data; duplicate decision requests; policy activated
mid-decision.

### 13. Reconciliation implications
No direct financial reconciliation. Decision reproducibility is the analogous control:
the ability to re-derive a past decision from retained evidence.

### 14. Documentation / ADR work
ADR on policy versioning and decision reproducibility. ADR on bureau adapter and evidence
retention. Update `CREDIT_MODEL.md`.

### 15. Deliverables
Versioned policy engine, simulated bureau adapter, reproducible and explainable decisions
with reason codes.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 10.

### 17. Risks
- Policy as opaque code — explicitly forbidden by `CREDIT_MODEL.md`.
- Decisions not reproducible because inputs were not snapshotted.
- Bureau PII over-retained.

### 18. What must NOT be implemented yet
Loan origination, disbursement, servicing, interest, collections, BNPL.

*(**Elaborated by [`PHASE_10_PLAN.md`](PHASE_10_PLAN.md)** and ADR-0084…0089 (`Proposed` then;
`Accepted` 2026-10-09, `P10-DOC-001`) at the Phase 9 → 10 transition, 2026-10-07: twenty-four items across eight milestones in `BACKLOG.md`
(`P10-TSK-001`…`-021`, `P10-TST-001`…`-002`, `P10-DOC-001`), the machines in
`CREDIT_DECISIONING_LIFECYCLES.md` and the invariants `INV-CRD-01`…`12` in
`FINANCIAL_INVARIANTS.md`. The eighteen sections above are kept as written and made current here
where they had fallen behind the decisions; where they disagree with this addendum or the plan,
the addendum and the plan are right. §18 stands, and `PHASE_10_PLAN.md` §17 extends it. Every
item of the addendum is built — `P10-TSK-001`…`-021`, `X-TSK-017`, `P10-TST-001` and
`P10-TST-002` are complete, none cut — and the Phase 10 exit review (`P10-DOC-001`, 2026-10-09)
corrected each statement below to the code as built; it read "Until Phase 10's first task lands,
nothing in this addendum is implemented".)*

- *§3 and §5 — **one new module, every seam a port** (ADR-0084). Credit (context 17) is one
  module, `credit`, depending on `platform` and `sharedkernel` alone (`CreditModuleIsolationTest`):
  consent, the party's standing and the risk signal are reached through ports `app` implements
  (`CreditConsentGate`, `CreditPartyStanding` — implemented by `PartyCreditStanding` over party's
  store, the live customer `ACTIVE` — `CreditRiskSignal`, `PlatformCreditExposure`, beside the
  provider-neutral `CreditDataSource` both provider ports implement, `CreditBureau` and
  `FinancialDataProvider`), with no edge to `consent`, `kyc`,
  `party` or `ledger`, and no module depends on `credit` in Phase 10 (Phase 11's `lending` uses
  the published decision-read port `CreditDecisions` and the consumption port through `app`, with no
  build edge — settled by the Phase 10 → 11 transition, ADR-0090; this read "Phase 11's `lending`
  will, through the published decision-read port" until then). Collecting credit data as a
  second module was weighed and refused: its only consumer is credit's own snapshot. §5's
  separation is kept and made physical — credit data, credit profile, assessment, underwriting and
  decision are distinct aggregates and tables — with two sharpenings: §5's "risk assessment" is
  **not** credit's (below), and **Phase 10 moves no money** — no posting, hold or disbursement; its
  output is a decision a later phase relies on and the exposure an approval reserves until it lapses
  or is consumed. §3's contexts gain **Risk** as an upstream seam and **Lending** as the Phase 11
  downstream. §4's dependencies stand, extended by Phase 9's database-clock rule and versioned
  four-eyes policy shape, which the credit policy, the scorecard and every credit window adopt.*
- *§2 and §6 — **the risk score moves to Phase 13.** §2's "risk scoring" and §6's "Risk Score"
  are not built here: a risk score answers a fraud question, so it is `risk`'s (Phase 13), the
  owner question the glossary had left open (`GLOSSARY.md` §10) settled by ADR-0084.
  `MODULE_ARCHITECTURE.md` §4 and §5 move it from `credit` to `risk`. Credit declares the
  `CreditRiskSignal` port, whose Phase 10 composition answers `NOT_ASSESSED` for every party,
  recorded in the snapshot with the seam's version, so a decision made now replays identically once
  Phase 13 answers. What §2 called scoring is the **Credit Score** — a versioned scorecard
  (`RETAIL_SCORECARD`, a points table as rows, integer arithmetic), the **Model Version** §5
  required a decision to pin.*
- *§6 — **the data model as decided** (ADR-0085…0089). "Credit Profile" becomes the party's credit
  identity — one row per party, holding no figures, the row every deciding transaction locks first.
  "Bureau Request/Response (evidence retained)" becomes the data request per source (bureau or
  financial data) with its attempts, the born-once **Credit Record** — one `credit_record` table for
  both source kinds, `source_kind` `BUREAU` or `FINANCIAL_DATA` *(this read "the born-once Credit
  Bureau Record (or financial-data record)" until the exit review)* — of normalised Credit Attributes, and the raw answer as encrypted evidence with a stored
  `retain_until` — the application role cannot read it, and no purge runs in Phase 10
  (crypto-shredding is Phase 15's). "Decision Input Snapshot" becomes the **Decision Snapshot** —
  the canonical attributes with provenance, sorted, its SHA-256 stored and re-verified, one per
  evaluation (`UNIQUE (decision_request_id, sequence)`: a successor only on an exposure change). "Policy
  Version" and "Rule" become the **Credit Policy**'s versions with rules as rows over a closed
  operator set, immutable from insert, four-eyes activated, one `ACTIVE` per product, with the
  **Model Version** beside them — both pinned on the request when collection begins. "Decision
  (immutable)" becomes the **Credit Decision**, born once per request, never updated or deleted by
  any role, with its ordered reason codes from the migration-seeded **Reason Code** catalogue; its
  consumption by a Phase 11 loan is a separate born-once fact, never a column of the decision. New beside them: the **Decision Request** envelope
  and its lifecycle (terminal `DECIDED`, `CANCELLED`, `EXPIRED` and the platform's `ABANDONED` —
  `STANDING_LOST`, `CONSENT_WITHDRAWN`), the **Credit Assessment** (affordability, exposure, score) and the policy
  evaluation, the **Underwriting Case** (terminal `DECIDED` or `CLOSED`), and the closed **Credit
  Product** (`PERSONAL_LOAN`, `CREDIT_LINE`, each declaring a 7-day request validity and a 30-day
  decision validity). "Exposure" is the per-request sum of bureau balance, platform outstanding credit
  (zero until Phase 11's loans, recorded) and the **reserved exposure** of the party's current
  approvals, re-read under the party's profile lock in the deciding transaction (`INV-CRD-09`).*
- *§2, §5 and §12 — **affordability, exposure and underwriting added.** §2 named affordability
  without a method: it is exact decimal in the product's one currency — income (the lower of
  verified and declared) less expenditure (the higher), bureau obligations and the repayment — an
  annuity at the policy's stress rate for `PERSONAL_LOAN`, the limit times the policy's minimum
  payment ratio for `CREDIT_LINE` — against the policy's minimum disposable income; a source in another currency
  is never converted (ADR-0088). Exposure gains its concurrency rule: decisions for one party
  serialise, so two approvals never together exceed the limit. And §5's "underwriting" gains its
  manual half: a `REFER` opens an **Underwriting Case** decided by a person with reason codes,
  four-eyes above the product's threshold, never overriding a hard decline (ADR-0089). §12's
  "documented fallback: decline, refer, or degraded policy" is decided: a source unavailable past
  its deadline leaves its attributes `ABSENT` and fires the policy's declared fallback, `REFER` or
  `DECLINE` — **no degraded policy**, never an approval (`INV-CRD-10`), and a policy without the
  fallback for every source it reads is refused at proposal — as, since the exit review
  (`P10-DOC-001`, 2026-10-09), is one with no rule guaranteed to stop an approval past its maximum
  exposure (`INV-CRD-09`), because the evaluator judges exposure only through rules.*
- *§7 — **the API work as decided.** The customer submits for self (`POST
  /v1/me/credit/decision-requests`, keyed, `202` — the decision arrives later — stepping up to a
  `MULTI_FACTOR` session only when the identity has a TOTP factor enrolled, the `P4-TSK-007`
  conditional; this read "MFA-assured" until the exit review), reads
  the request (owner-scoped; another party's is `404`) — when decided, the outcome and the adverse
  reasons' customer texts in order, which is §2's adverse action explanation, never a score,
  threshold, attribute or bureau datum — cancels before evaluation, and reads a profile summary.
  §7's "reason-code explanation" splits in two: the customer's texts, and the operator's full
  explanation (snapshot, versions, triggered rules) beside the replay door under
  `CREDIT_INVESTIGATE`. "Policy management endpoints" become policy and scorecard propose, approve
  and reject under `CREDIT_POLICY_ADMINISTER` (four-eyes, keyed) and the policy-active-at-an-instant
  read. New: the review queue under `CREDIT_UNDERWRITE`, and the audited outcomes, reasons and
  sources reports, the reasoned evidence read under `CREDIT_INVESTIGATE`, and the release of a
  taken case and the refusal of a second approval under `CREDIT_UNDERWRITE`.*
- *§8 — **the events renamed and re-cut** (`PHASE_10_PLAN.md` §10). `CreditDecisionRequested` and
  `CreditDecisionRecorded` are built as named. **`CreditProfileUpdated` is not built** — the profile
  holds no figures, so it has nothing to update. **`BureauDataRetrieved` becomes
  `CreditDataCollected`** (any source kind, never an attribute), with **`CreditDataUnavailable`**
  beside it. **`PolicyVersionActivated` becomes `CreditPolicyVersionActivated`**, with
  **`ScorecardModelVersionActivated`** for the model. New: `CreditAssessmentCreated`,
  `ManualReviewRequired` and `CreditDecisionRequestClosed`. Refused by decision: `UnderwritingStarted`
  (an audited internal step nobody consumes) and `CreditDecisionUpdated` (a decision is never
  updated; a change of mind is a new request). Payloads carry identifiers, versions, outcomes and
  reason codes, never an attribute, figure or payload; Phase 10 has no consumer outside `credit`.*
- *§9 — **security as decided** (ADR-0085). Bureau access requires recorded consent — two new
  purposes, `CREDIT_BUREAU_ACCESS` and `FINANCIAL_DATA_ACCESS`, the gate checked at submission
  (refused with consent's own `409 consent.ConsentRequired`), when a data request opens, at every
  retry and again when its answer is recorded, and re-read at the freeze and in the deciding
  transaction (`INV-CRD-03`) — and is itself audited.
  "Strict retention limits" becomes a stored `retain_until` per evidence row (the product's declared
  retention, default 25 months), enforced by Phase 15's purge. Evidence is encrypted under its own
  key purpose (`finapp.credit.evidence.key`); bureau data is `RESTRICTED-FINANCIAL` — every
  attribute's integer, money and boolean value and the evidence's ciphertext, an attribute's code
  value, absence and currency `CONFIDENTIAL` (`DATA_CLASSIFICATION.md`; "every attribute
  `RESTRICTED-FINANCIAL`" until the exit review) — absent from logs, metrics, spans, events and
  exceptions. Three permissions and two roles — `CREDIT_POLICY_ADMINISTER`
  and `CREDIT_INVESTIGATE` under `CREDIT_POLICY_OFFICER`, `CREDIT_UNDERWRITE` under `UNDERWRITER`.*
- *§10 — **counts, ages and verdicts, never a value** (ADR-0072, unchanged): the decision counter by
  product, outcome, policy version and decider, latency, reasons, data requests by source and
  outcome (the unavailability ratio alerting — §10's "bureau availability"; its "cost" is
  decided: `finapp.credit.data.request` per provider is the bureau-cost proxy, pulls counted per
  provider, and Phase 10 publishes no money cost series), the open-request and review ages, the replay verdict (any
  `DIVERGED` alerting) and the active policy per product — §10's "policy-version drift detection"
  is the replay proof plus `finapp.credit.policy.active`.*
- *§11 and §13 — **reproducibility is the reconciliation analogue, proven.** `CreditReplayProof`
  re-derives every past decision per reading and the operator replays one; the battery
  (`P10-TST-002`) generates ≥ 10,000 applicants across both products, exercises every reason code
  and replays every decision `IDENTICAL`, a perturbed snapshot or rule flipping the verdict; the
  storm (`P10-TST-001`) runs two instances with skewed clocks, shared parties, provider faults and
  activations mid-flight. A change to the evaluator's semantics is a new engine version, the old one
  kept for replay.*
- *§14 — **the ADRs written**: ADR-0084 (the credit bounded context; the risk score's owner),
  ADR-0085 (credit data collection, consent, evidence and retention), ADR-0086 (policy and model as
  versioned data — §14's "policy versioning"), ADR-0087 (the decision, its snapshot and replay —
  §14's "decision reproducibility"), ADR-0088 (affordability and exposure) and ADR-0089
  (underwriting), each read against the code and `Accepted` by the exit review (`P10-DOC-001`,
  2026-10-09). §14's "bureau
  adapter and evidence retention" ADR is ADR-0085. `CREDIT_MODEL.md` is updated by the tasks that
  build what it describes.*
- *§2 and §15 — **the milestone map.** The capabilities and the deliverables land across eight
  milestones:*
  - *M10.1 Foundations — `P10-TSK-001`…`-003`: the module, its schema and isolation; the consent
    purposes; the permissions and roles.*
  - *M10.2 Credit data — `-004`…`-007`: a profile per party; bureau and financial data collected
    under consent, normalised, evidence encrypted, duplicates and outages safe.*
  - *M10.3 Assessment — `-008`…`-011`: the snapshot sealed and fresh; affordability and exposure
    exact; the scorecard versioned.*
  - *M10.4 Policy — `-012`, `-013`: policy versions as data, four-eyes; the deterministic
    evaluator.*
  - *M10.5 Decisioning — `-014`…`-017`: requests decided end to end across instances; decisions
    immutable, explained to the customer.*
  - *M10.6 Underwriting — `-018`: referrals decided by people under four-eyes.*
  - *M10.7 Proof — `-019`…`-021`: every decision replayed; a second bureau and source selection
    (the first cut, and built); meters and reports.*
  - *M10.8 Exit — `P10-TST-001`, `P10-TST-002`, `P10-DOC-001`: the storm, the battery, the exit
    review against `PHASE_GATES.md` §Phase 10.*

  *If the phase must shrink, `P10-TSK-021` (a second bureau and source selection) is cut first,
  its deferral recorded with Phase 15 as owner, provider-neutrality then met by the port's contract
  suite and the single adapter. As built, nothing was cut: `P10-TSK-021` landed (2026-10-08) —
  `bureau-sim-b` and a provider order per source kind, the provider stamped on each data request
  at birth, no failover — and production selects only the fail-safes until unresolved questions
  #13 and #14 are answered (`P10-DOC-001`, 2026-10-09).*
- *§18 — **extended** (`PHASE_10_PLAN.md` §17): besides lending's origination, disbursement,
  servicing, interest, collections and BNPL, Phase 10 builds no loan application or offer, no
  counter-offer or credit pricing (Phase 11's offer), no ledger posting or hold, no risk score or
  fraud rule (Phase 13 — only the seam), no real bureau connectivity, no machine-learned model, and
  no evidence purge or crypto-shredding (Phase 15).*

---

# Phase 11 — Lending

### 1. Objective
Originate and service loans, introducing time-based financial mechanics: accrual,
amortisation, and obligations that persist and change over time.

### 2. Business capabilities
Loan application, offer, acceptance, disbursement, repayment schedule, interest accrual,
repayment allocation, early settlement, delinquency, restructuring.

### 3. Domains / bounded contexts involved
Lending; Credit; Ledger; Accounts; Transfers.

### 4. Dependencies
Phase 10 (decisioning), Phase 3 (ledger), Phase 4 (money movement).

### 5. Architecture work
- Loan Application, Loan Offer and Loan are distinct aggregates with distinct lifecycles.
- Accrual is a scheduled, idempotent, replayable financial process — running it twice for
  the same period must not double-accrue (INV-IDEM-02).
- Repayment allocation order (fees → interest → principal, or as configured) is explicit,
  versioned and testable.
- Loan accounting: principal, interest receivable, interest income, fee income, provision.
- Day-count convention and rounding policy explicit.

### 6. Data-model work
Loan Application, Loan Offer (with expiry), Loan, Repayment Schedule, Instalment, Accrual
Record (period-keyed, unique), Repayment, Allocation, Delinquency State, Exposure.

### 7. API work
Application submit, offer retrieve/accept/decline, loan detail, schedule, repayment
(idempotent), early settlement quote and execution.

### 8. Event work
`LoanApplicationSubmitted`, `LoanOffered`, `LoanAccepted`, `LoanDisbursed`,
`InterestAccrued`, `RepaymentReceived`, `LoanDelinquent`, `LoanClosed`.

### 9. Security work
Disbursement is a high-value money movement requiring strict authorization; schedule and
rate modifications require four-eyes and reason codes; restructuring is fully audited.

### 10. Observability work
Portfolio outstanding, accrual job success and duration, delinquency buckets, allocation
anomalies, schedule-vs-actual drift.

### 11. Testing work
Accrual idempotency across reruns and restarts; amortisation schedule totals exactly equal
principal plus interest with no rounding leakage; early settlement rebate; partial and
overpayment allocation; delinquency transitions; leap year and month-end day-count edge
cases.

### 12. Failure-engineering work
Accrual job crashes mid-run; accrual runs twice; disbursement posted but transfer fails;
repayment arrives for a closed loan; clock skew across the accrual boundary.

### 13. Reconciliation implications
Loan balances must reconcile to ledger postings; interest receivable must reconcile to
accruals; repayments must reconcile to incoming transfers. Rounding residual must be zero.

### 14. Documentation / ADR work
ADR on interest accrual and day-count convention. ADR on repayment allocation order. ADR on
loan accounting treatment.

### 15. Deliverables
Full loan lifecycle from application through disbursement, accrual, repayment and closure,
with correct double-entry accounting at every step.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 11.

### 17. Risks
- Double accrual from a rerun — a direct money-creation defect.
- Rounding leakage across an amortisation schedule.
- Interest calculated in floating point (forbidden, INV-MON-01).

### 18. What must NOT be implemented yet
BNPL, collections operations, securitisation, provisioning models, IFRS 9 staging.

*(**Elaborated by [`PHASE_11_PLAN.md`](PHASE_11_PLAN.md)** and ADR-0090…0100 (`Proposed`) at the
Phase 10 → 11 transition, 2026-10-10: thirty-three items across nine milestones in `BACKLOG.md`
(`P11-TSK-001`…`-030`, `P11-TST-001`…`-002`, `P11-DOC-001`), the machines in
`LENDING_LIFECYCLES.md` and the invariants `INV-LND-01`…`14` in `FINANCIAL_INVARIANTS.md`. The
eighteen sections above are kept as written and made current here where they had fallen behind the
decisions; where they disagree with this addendum or the plan, the addendum and the plan are right.
§18 stands, and `PHASE_11_PLAN.md` §17 extends it. Until Phase 11's first task lands, nothing in this
addendum is implemented; every statement is the decided design, corrected by the tasks that build
it.)*

- *§1 and §2 — **two products, owner decisions L1–L12** (`PHASE_11_PLAN.md` §2.3): the amortising
  `PERSONAL_LOAN` and the revolving `CREDIT_LINE` (draws against the approved limit, revolving
  interest, a monthly statement and minimum payment, the available limit recomputed from postings);
  a neutral EUR reference jurisdiction with no consumer-credit-law features; ACT/365F simple daily
  interest rounded once per period; allocation oldest-due first, fees → interest → principal, any
  excess held as a credit balance; default at 90 days past due; no penalty interest, prepayment fee
  or APR display — each a field of a versioned terms row pinned on the agreement.*
- *§3 and §5 — **one new module, every seam a port** (ADR-0090). Lending (context 18) is one module,
  `lending`, depending on `ledger`, `platform` and `sharedkernel` alone (`LendingModuleIsolationTest`):
  credit, party standing, the wallet and payments are reached through ports `app` implements. **It
  does not depend on `credit`** (the crossborder precedent; this plan's earlier "Phase 11's `lending`
  will depend on `credit`" in the Phase 10 addendum is settled the other way). Transfers (context 8)
  is not involved: `TransferExecution` posts wallet to wallet only, so a repayment is lending's own
  posting.*
- *§5 and §6 — **exposure is credit's, not lending's.** §6 listed "Exposure" in lending's data
  model; exposure is Credit's judgement (`INV-CRD-09`) over facts Lending supplies —
  `PlatformCreditExposure` version 2 (`P11-TSK-013`): committed and outstanding principal of loans
  and an open credit line's limit, in one statement, every exposure-raising lending transaction
  holding the party's credit profile lock first. Taking up an approval is credit's write through its
  own port (`P11-TSK-001`, the transition's R13), never lending's. The data model as decided: Loan
  Application, Loan Product Terms Version, Loan Offer (with expiry), Loan Agreement (immutable,
  versioned) and its Acceptance Evidence, Loan (identity, kind and state — no amount, rate or
  balance), Repayment Schedule and Loan Instalment, Instalment Billing, Credit Line Draw and
  Statement, Interest Accrual (born once per account and date), Fee Assessment, Disbursement, Loan
  Payout, Repayment and Allocation, Collection Attempt, the delinquency condition's append-only
  history, Payoff Quote, Loan Amendment, Lending Capital Contribution.*
- *§5 — **loan accounting** (ADR-0096): `OwnerKind.LOAN` with six per-loan accounts (principal,
  principal due, interest accrued, interest due, fees due, credit balance), interest and fee income,
  a seeded write-off expense posted by nothing, and **`LENDING_CAPITAL`** — the platform's own funds,
  recognised only from bank evidence, from which every loan and draw is funded and which no
  acceptance or draw may over-deploy (owner decision L1), so loan-funded wallet money is
  platform-funded and the safeguarding position stays exact. "Provision" is Phase 14's.*
- *§5 and §12 — **disbursement to the wallet and to an external bank account** (owner decision L2,
  ADR-0097): the receivable is born with the wallet credit in both paths; the external leg is a
  system-initiated withdrawal of the borrower's funds through payments' machinery, adopting a hold
  placed at disbursement, its outcome read by lending (payments never calls lending — no lock-order
  cycle); interest on an externally paid-out loan starts at the payout's terminal outcome. §12's
  "disbursement posted but transfer fails has a compensating path" becomes: a failed or returned
  payout leaves the funds in the borrower's wallet and the loan unaffected; nothing is compensated
  because nothing was wrongly booked.*
- *§7 — **API**, as decided (`PHASE_11_PLAN.md` §9): adds draw, closure, payoff quote and execution,
  amendment acceptance, and the operator's four-eyes waivers, reversals, amendments, terms versions
  and capital contributions; every financial command keyed per principal, the credit-creating acts
  on a `MULTI_FACTOR` session.*
- *§8 — **events**, as decided (`PHASE_11_PLAN.md` §10): `InterestAccrued` per day is **refused**
  (an internal process nobody consumes; the billing and statement events carry what became due);
  `LoanDelinquent` is renamed `LoanDelinquencyChanged` (it announces cure too); added
  `LoanApplicationClosed`, `LoanOfferClosed`, `LoanCancelled`, `LoanPayoutConcluded`,
  `InstalmentBilled`, `CreditLineStatementIssued`, `CreditLineDrawn`, `RepaymentReversed`,
  `CollectionFailed`, `LoanFeeAssessed`, `LoanFeeWaived`, `LoanInterestWaived`, `LoanDefaulted`,
  `LoanDefaultCleared`, `LoanAmended`, `LoanTermsVersionActivated` (a capital recognition is reconciliation's fact, announced by no lending event).*
- *§10 — portfolio outstanding and schedule-vs-actual drift are **audited reports**, not series
  (ADR-0072); the series are counts, ages and verdicts (`PHASE_11_PLAN.md` §15).*
- *§11 and §12 — the **ten mandatory test scenarios** (owner decision L12, `PHASE_11_PLAN.md`
  §13.2), each a named test and in the storm; the "early settlement rebate" is zero by construction
  (daily accrual) and tested as such.*
- *§13 — reconciliation: loan balances reconcile to postings and interest receivable to accruals
  by the subledger proof (internal flows have no external evidence); an external payout reconciles
  through payments' expectation; lending capital reconciles to the bank statement it was recognised
  from. "Repayments must reconcile to incoming transfers" applies to the deferred external inbound
  repayment (via wallet top-ups, payments' reconciliation).*
- ***The production reality** (owner decision L11): production has only fail-safe credit sources
  and a person may not approve on an absent bureau balance (the transition's R11), so Phase 11
  originates only against the simulators until a real bureau is connected (unresolved #13/#14); no
  lending terms version is activated in production, and the runbook names the activation gate.*
- *§18 — **extended** (`PHASE_11_PLAN.md` §17): besides BNPL, collections operations,
  securitisation, provisioning and IFRS 9 staging, Phase 11 builds no refinance (owner: the Phase 11
  → 12 transition), write-off or GL (Phase 14), consumer-credit-law feature, penalty interest,
  prepayment fee, acceleration, variable rate, external inbound repayment rail, capital return or
  real bureau connectivity.*

---

# Phase 12 — BNPL

### 1. Objective
Combine merchant commerce with credit: the merchant is paid now, the customer repays in
instalments, and refunds must unwind a credit agreement correctly.

### 2. Business capabilities
BNPL eligibility, instalment plan selection, merchant financing and settlement, customer
repayment obligations, refund and return handling, late fees.

### 3. Domains / bounded contexts involved
BNPL; Lending; Credit; Merchant; Checkout; Payments; Ledger.

### 4. Dependencies
Phase 6 (merchant), Phase 11 (lending mechanics), Phase 10 (eligibility).

### 5. Architecture work
- BNPL Agreement is distinct from a Loan and from an Order; it references both.
- Merchant is settled at purchase; the customer obligation is created simultaneously. These
  are two distinct financial flows in one economic event.
- Refund handling is the hard part: a partial refund must proportionally reduce remaining
  instalments under an explicit, documented policy.
- Merchant discount fee treatment defined explicitly.

### 6. Data-model work
BNPL Agreement, Instalment Plan template, Instalment, Merchant Financing record, Refund
Adjustment, Late Fee.

### 7. API work
Eligibility check at checkout, plan selection, agreement retrieval, instalment schedule,
early payoff, merchant-initiated refund.

### 8. Event work
`BnplEligibilityAssessed`, `BnplAgreementCreated`, `MerchantFinanced`,
`InstalmentDue`, `InstalmentPaid`, `BnplRefundApplied`, `BnplAgreementClosed`.

### 9. Security work
Eligibility must not leak credit data to the merchant; merchant-initiated refunds must be
authorised and bounded by the original order.

### 10. Observability work
Eligibility approval rate, plan mix, instalment delinquency, refund rate and its impact on
outstanding balance, merchant financing exposure.

### 11. Testing work
Refund before, during and after instalments; refund exceeding remaining balance producing a
customer credit; full refund closing the agreement; late fee application; early payoff with
refund in flight.

### 12. Failure-engineering work
Refund and instalment collection racing; merchant settled but agreement creation fails;
chargeback on a BNPL-funded order; customer repays after full refund.

### 13. Reconciliation implications
Three-way reconciliation: merchant settlement, customer obligation, and ledger. Refunds
make this the most reconciliation-sensitive product in the platform.

### 14. Documentation / ADR work
ADR on BNPL refund and instalment adjustment policy. ADR on merchant financing accounting.

### 15. Deliverables
End-to-end BNPL purchase with merchant financing, customer instalments, and correct refund
unwinding.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 12.

### 17. Risks
- Refund logic creating or destroying value.
- Modelling BNPL as an unsecured loan and ignoring the merchant leg.
- Chargeback plus refund double-crediting the customer.

### 18. What must NOT be implemented yet
Merchant credit risk, BNPL funding/securitisation, collections agencies.

---

# Phase 13 — Risk, Fraud and AML

### 1. Objective
Implement the seams left open since Phase 4: real-time risk decisioning on money movement,
and ongoing AML monitoring — without weakening any financial invariant.

### 2. Business capabilities
Signal collection, rules engine, real-time risk decisions, limits and velocity, device and
behavioural signals, account-takeover detection, AML transaction monitoring, alerting, case
management, manual review.

### 3. Domains / bounded contexts involved
Risk; Fraud; AML / Transaction Monitoring; Case Management; consumers in Transfers,
Payments, BNPL, Lending.

### 4. Dependencies
Phase 4, 5 (seams), Phase 2 (screening infrastructure), Phase 6/7 (merchant and rail data).

### 5. Architecture work
- Risk decisions are advisory inputs to a domain lifecycle, never a substitute for it: a
  blocked transfer is a transfer in a blocked state, with postings unwound explicitly.
- Rules and thresholds are versioned artefacts; decisions record the version used.
- Risk evaluation must fail safe: define explicitly whether unavailability blocks or allows,
  per operation and per value band.
- AML monitoring is asynchronous and retrospective; fraud decisioning is synchronous and
  latency-bounded.
- Case management is a shared capability across KYC, fraud and AML.

### 6. Data-model work
Signal, Rule Set (versioned), Risk Assessment, Risk Decision, Limit definition, Velocity
Counter, Alert, Case, Case Action, SAR/report artefact abstraction.

### 7. API work
Internal risk evaluation API with a strict latency budget; operational APIs for case
queues, alert triage, limit management (privileged), and manual override with reason codes.

### 8. Event work
`RiskSignalRecorded`, `RiskDecisionMade`, `LimitBreached`, `AlertRaised`, `CaseOpened`,
`CaseClosed`, `TransactionBlocked`.

### 9. Security work
Manual override is a high-privilege action requiring reason codes, four-eyes above
thresholds, and full audit. Tipping-off controls: AML case detail must not be exposed to
customer-facing surfaces. Risk model inputs may be sensitive.

### 10. Observability work
Decision latency percentiles, block and false-positive rates, rule hit distribution, alert
volume and queue age, case resolution time, limit breach rate.

### 11. Testing work
Rules engine determinism and version pinning; velocity counters under concurrency; fail-safe
behaviour when the risk service is unavailable; blocked transfer produces correct
compensating postings; alert deduplication; override authorization negative tests.

### 12. Failure-engineering work
Risk service timeout during a money-moving command; Redis velocity counter loss; duplicate
signal ingestion; rule set activated mid-evaluation; monitoring backlog.

### 13. Reconciliation implications
Blocked and reversed transactions must be fully explainable in the ledger; held funds must
be visible and aged; no value may be stranded outside an account.

### 14. Documentation / ADR work
ADR on synchronous risk evaluation and fail-safe policy. ADR on rules versioning. ADR on
case management model.

### 15. Deliverables
Working real-time risk decisioning on transfers and payments, limits and velocity, AML
monitoring with alerts, and a case management workflow.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 13.

### 17. Risks
- Risk service becoming a single point of failure for all money movement.
- Fail-open under load, silently.
- Blocking transactions without a defined unwind path, stranding value.

### 18. What must NOT be implemented yet
Machine-learning model training infrastructure, real sanctions list subscriptions, actual
regulatory filing.

---

# Phase 14 — Accounting and Financial Reporting

### 1. Objective
Turn the operational ledger into reportable financial information: trial balance, general
ledger, period close, and a regulatory reporting abstraction.

### 2. Business capabilities
GL account mapping, trial balance, period open/close, journal review, financial statement
production, regulatory reporting abstraction, audit-ready reporting.

### 3. Domains / bounded contexts involved
Accounting / General Ledger; Reporting; Ledger; Reconciliation.

### 4. Dependencies
Phases 3, 4, 5, 8 (and realistically 9, 11, 12 for meaningful reporting).

### 5. Architecture work
- The operational ledger and the general ledger are related but distinct: GL mapping is a
  documented, versioned transformation.
- Accounting periods with explicit open/closed state; postings into a closed period are
  rejected and must use a prior-period adjustment (INV-ACC-03).
- Reporting is a derived read model, never a writable authority.
- Regulatory reporting sits behind an abstraction with jurisdiction adapters.

### 6. Data-model work
GL Account, GL Mapping Rule (versioned), Accounting Period, Trial Balance snapshot, Period
Close record, Adjustment Entry, Report Definition, Report Run (immutable output retained).

### 7. API work
Trial balance query, GL query with drill-down to source postings, period close initiation
and approval (privileged), report generation and retrieval.

### 8. Event work
`AccountingPeriodOpened`, `TrialBalanceGenerated`, `PeriodCloseRequested`,
`AccountingPeriodClosed`, `ReportGenerated`.

### 9. Security work
Period close requires elevated authorization and four-eyes; generated reports are immutable
and retained; drill-down access is controlled; no reporting path may write to the ledger.

### 10. Observability work
Trial balance imbalance alert (must always be zero per currency), close duration, unposted
item count at close, report generation success.

### 11. Testing work
Trial balance sums to zero per currency across the full posting history; drill-down from a
GL figure to individual journal lines; posting into a closed period rejected; prior-period
adjustment path; GL mapping version pinning; report reproducibility.

### 12. Failure-engineering work
Postings arriving during close; close job crashes mid-run; mapping rule changed after
postings exist; report generated from a stale projection.

### 13. Reconciliation implications
This is where reconciliation becomes financially visible: unreconciled breaks and suspense
balances must be explicitly reported at close, not hidden.

### 14. Documentation / ADR work
ADR on operational-ledger-to-GL mapping. ADR on accounting period and close model. ADR on
reporting read-model architecture.

### 15. Deliverables
Trial balance with continuous verification, GL with drill-down, period close with approval,
and a reproducible financial report.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 14.

### 17. Risks
- Reporting projections drifting from the ledger.
- Close process that permits silent back-dated postings.
- GL mapping applied retroactively, changing historical reports.

### 18. What must NOT be implemented yet
Jurisdiction-specific statutory filings, tax computation, consolidation.

---

# Phase 15 — Production Hardening

### 1. Objective
Make the platform operable and defensible: security hardened, observable against SLOs,
and supported by tested runbooks.

### 2. Business capabilities
No new business capability. Operational and security capability only.

### 3. Domains / bounded contexts involved
All. Platform, Security, Observability, Audit are the primary owners.

### 4. Dependencies
Phases 0–14 complete.

### 5. Architecture work
Threat model across the whole platform; trust boundary review; blast-radius analysis;
review of every module boundary against actual coupling observed in implementation;
identify any module that now genuinely warrants extraction (and document why or why not).

### 6. Data-model work
Data classification review across all tables; retention and deletion policy implementation;
encryption-at-rest verification; PII minimisation pass; index and constraint review.

### 7. API work
Rate limiting and quota enforcement; API versioning and deprecation policy; contract
regression suite; public API documentation completeness.

### 8. Event work
Schema registry and compatibility enforcement; consumer lag alerting; dead-letter handling
and replay procedures; event retention policy.

### 9. Security work
Penetration-test-style review; dependency and container scanning in CI; secret rotation
procedures; key management and rotation; privileged access review; audit trail completeness
verification against a defined list of auditable actions; incident response plan.

### 10. Observability work
SLO definition per critical flow; alerting with runbook links; on-call escalation; log
retention; distributed tracing coverage across customer → request → domain → provider →
ledger → settlement → reconciliation.

### 11. Testing work
Full regression suite; performance baseline; security test suite; chaos experiments against
critical flows; restore-from-backup rehearsal.

### 12. Failure-engineering work
Systematic failure-mode review of every critical flow against the `CLAUDE.md` failure
checklist; documented and tested degradation modes.

### 13. Reconciliation implications
Reconciliation must be operable: staffed runbooks, break ageing SLAs, escalation paths, and
reporting to management.

### 14. Documentation / ADR work
Runbooks per critical flow; incident response plan; operational readiness review; threat
model document; ADRs for any boundary change.

### 15. Deliverables
Hardened, monitored, documented platform with tested runbooks and defined SLOs.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 15.

### 17. Risks
- Treating hardening as optional polish.
- Runbooks written but never rehearsed.
- Audit trail with gaps discovered only under scrutiny.

### 18. What must NOT be implemented yet
New business capability of any kind.

---

# Phase 16 — Scale, Resilience and Disaster Recovery

### 1. Objective
Establish how the platform behaves under load, partial failure and disaster — and prove
recovery preserves financial correctness.

### 2. Business capabilities
None new. Non-functional capability only.

### 3. Domains / bounded contexts involved
All; Platform owns the work.

### 4. Dependencies
Phase 15.

### 5. Architecture work
Load characterisation and capacity model; identify genuine scaling bottlenecks with
evidence; evaluate service extraction *only where measurement justifies it* (ADR-0001
revisit point); partitioning and sharding strategy if warranted; multi-AZ/region topology.

### 6. Data-model work
Partitioning of high-volume tables (journal lines, events, audit); archival strategy that
preserves financial history and auditability; read-replica routing rules that never serve
authoritative financial reads.

### 7. API work
Backpressure, load shedding with correct semantics for money-moving commands (shed before
the financial effect, never after), graceful degradation contracts.

### 8. Event work
Partition strategy and ordering guarantees under scale; consumer scaling; replay at volume;
outbox relay throughput.

### 9. Security work
Security controls must hold under degradation — no fail-open on authentication or
authorization under load.

### 10. Observability work
Saturation and capacity metrics; load-test observability; DR runbook instrumentation;
recovery verification dashboards.

### 11. Testing work
Load and soak tests (Gatling/k6) with financial invariant verification *during* load;
chaos tests: node loss, DB failover, Kafka partition loss, Redis loss, network partition;
backup restore with full trial-balance verification post-restore.

### 12. Failure-engineering work
Region loss; database failover mid-transaction; split brain; message broker outage;
cascading failure; recovery from a corrupted projection.

### 13. Reconciliation implications
After any DR event, a full reconciliation and trial-balance verification is mandatory
before resuming money movement. This procedure must be written and rehearsed.

### 14. Documentation / ADR work
DR plan with RPO/RTO targets; capacity model; ADR on any service extraction or partitioning
decision; post-DR verification runbook.

### 15. Deliverables
Load-tested platform with documented capacity limits, tested DR procedure with measured
RPO/RTO, and a proven post-recovery financial verification process.

### 16. Exit criteria
See `PHASE_GATES.md` §Phase 16.

### 17. Risks
- Scaling by distribution before measuring, violating ADR-0001's rationale.
- Recovery that restores availability but not financial correctness.
- Read replicas serving stale authoritative balances.

### 18. What must NOT be implemented yet
Nothing is deferred beyond this phase; further work is a new programme.
