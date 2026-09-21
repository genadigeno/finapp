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
  authorization→capture→clearing→settlement; instant rails settle immediately;
  A2A may be irrevocable on acceptance.
- Irrevocability and finality per rail documented explicitly — this drives reversal
  strategy.
- Rail selection/routing policy, versioned and explainable.
- Dispute lifecycle with financial effects at each stage.

### 6. Data-model work
Rail, Rail Capability descriptor, Card Payment detail, Wallet Payment detail, A2A Payment
detail, Dispute, Dispute Evidence, Chargeback, Representment.

### 7. API work
Rail-agnostic payment API with rail-specific detail objects; dispute notification, evidence
submission, dispute status.

### 8. Event work
`RailSelected`, `PaymentClearedOnRail`, `DisputeOpened`, `DisputeEvidenceSubmitted`,
`ChargebackReceived`, `DisputeResolved`.

### 9. Security work
No storage of raw PAN, CVV or track data anywhere; card detail tokenised at the boundary;
PCI scope explicitly documented and minimised; dispute evidence access controlled.

### 10. Observability work
Per-rail success rate, latency and cost; routing decision distribution; dispute rate and
win rate; chargeback ratio per merchant (a regulatory-relevant metric).

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
