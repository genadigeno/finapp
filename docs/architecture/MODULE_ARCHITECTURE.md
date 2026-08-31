# Module Architecture — First Baseline

The architecture baseline: which bounded context maps to which module, what each module
owns, and the transactional, consistency, event, security and provider boundaries between
them.

Governing decisions: [ADR-0001](../adr/ADR-0001-modular-monolith.md) (modular monolith),
[ADR-0006](../adr/ADR-0006-module-boundary-enforcement.md) (boundary enforcement),
[ADR-0008](../adr/ADR-0008-provider-adapters.md) (provider adapters).

---

## 1. Deployment Shape

**One deployable unit: a modular monolith.**

Bounded contexts are domain boundaries. They are *not* an automatic list of microservices
(`BOUNDED_CONTEXTS.md`). A single deployable gives us the one property that is hardest to
recover once lost: the ability to make a state change and its accounting posting atomic in a
single database transaction.

Distribution is a Phase 16 question, answered with measurement, not anticipation.

---

## 2. Module Layering

```
                    ┌───────────────────────────────┐
                    │            app                │   composition root, HTTP, config
                    └───────────────┬───────────────┘
                                    │
     ┌──────────────────────────────┴──────────────────────────────┐
     │                       business modules                       │
     │  identity  party  kyc  consent  accounts  ledger             │
     │  transfers  payments  paymentmethods  merchant  checkout     │
     │  settlement  reconciliation  fx  crossborder  credit         │
     │  lending  bnpl  risk  accounting  notification               │
     └──────────────────────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │          platform             │   outbox, inbox, idempotency,
                    │                               │   audit, correlation, telemetry,
                    │                               │   error contract, provider SPI
                    └───────────────┬───────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │        sharedkernel           │   Money, CurrencyCode, typed Ids,
                    │                               │   Clock, event envelope
                    │      (no Spring, no JPA)      │
                    └───────────────────────────────┘
```

Dependency direction is strictly downward and acyclic.

**Enforcement, as it actually stands.** Gradle enforces the *direction* structurally today
(`P0-TSK-002`): a module sees only what its build file declares, and a reverse edge fails
configuration with a circular-dependency error. Classpath tests in `sharedkernel` and
`platform` assert that no module output from above appears below, and that `sharedkernel`
carries no Spring artefact.

Gradle cannot express the finer rules. Those are ArchUnit rules in
`ModuleBoundaryRulesTest`, and as of `P0-TSK-007` they **are** in place and run on every
build: dependency direction as defence in depth, framework leakage into `sharedkernel`,
cross-module internal access, and cross-module entity references. Each was proven by
introducing a violation and watching that specific rule fail.

One finer rule is still outstanding: no floating-point money (`INV-MON-01`). That is
`P0-TSK-008` and additionally needs `Money` to exist (`P0-TSK-009`).

### What may enter `sharedkernel`
Only concepts that are genuinely universal *and* stable: `Money`, `CurrencyCode`, rounding
policy, typed identifiers, the event envelope, `Clock` abstraction.

### What may never enter `sharedkernel`
Any business concept. `Account`, `Customer`, `Payment`, `Transfer` and every other domain
noun belong to exactly one module. A shared kernel that accumulates business types becomes
the coupling sink that a modular monolith exists to prevent.

---

## 3. Context-to-Module Map

Every bounded context in [`BOUNDED_CONTEXTS.md`](BOUNDED_CONTEXTS.md) maps to exactly one
module. A module may serve more than one context; a context is never split across modules,
because that would mean its state has two owners.

Where several contexts share a module, the reason is recorded, along with **what would
trigger a split**. Merging is the reversible direction: splitting a module later costs a
package move, whereas merging two modules that have both grown authoritative state is
expensive and risky. Fewer, larger modules is therefore the deliberate default until
evidence says otherwise.

| # | Bounded context | Module | Phase | Note |
|---|-----------------|--------|-------|------|
| 1 | Party & Customer | `party` | 1 | |
| 2 | Identity & Authentication | `identity` | 1 | Deliberately separate from `party` |
| 3 | KYC/KYB | `kyc` | 2 | |
| 4 | Consent | `consent` | 2 | |
| 5 | Accounts | `accounts` | 3 | |
| 6 | Wallet | `accounts` | 3 | **Merged — provisional.** See M1 |
| 7 | Ledger | `ledger` | 3 | The financial authority |
| 8 | Transfers | `transfers` | 4 | Added by this task. See M6 |
| 9 | Payments | `payments` | 5 | |
| 10 | Payment Methods | `paymentmethods` | 5 | Separate for PCI scope. See M7 |
| 11 | Checkout | `checkout` | 6 | **Provisional.** See M2 |
| 12 | Merchant | `merchant` | 6 | |
| 13 | Settlement | `settlement` | 8 | |
| 14 | Reconciliation | `reconciliation` | 8 | Separate from `settlement`. See M8 |
| 15 | FX | `fx` | 9 | |
| 16 | Cross-Border Payments | `crossborder` | 9 | |
| 17 | Credit | `credit` | 10 | |
| 18 | Lending | `lending` | 11 | |
| 19 | BNPL | `bnpl` | 12 | |
| 20 | Risk | `risk` | 13 | |
| 21 | Fraud | `risk` | 13 | **Merged.** See M3 |
| 22 | AML / Transaction Monitoring | `risk` | 13 | **Merged.** See M3 |
| 23 | Case Management | `risk` | 13 | Added by this task. See M6 |
| 24 | Notifications | `notification` | 1+ | |
| 25 | Accounting / General Ledger | `accounting` | 14 | Distinct from `ledger`. See M9 |
| 26 | Audit | `platform` | 0 | **Merged.** See M4 |
| 27 | Reporting | `accounting` | 14 | **Merged.** See M5 |
| 28 | API / Integration Platform | `platform` + `app` | 0 | **Merged.** See M10 |

### Merge and separation decisions

**M1 — Wallet is inside `accounts`, provisionally.** A wallet is a stored-value account: it
has a balance derived from the ledger, a lifecycle and a holder, exactly as a customer
account does. Two modules would duplicate that lifecycle and, worse, give two owners to
"the product a balance belongs to".
*Split trigger:* wallets acquiring a materially different lifecycle — multi-currency
sub-balances, third-party wallet custody, or a distinct regulatory treatment.
*Must resolve by Phase 3* (open question 4).

**M2 — `checkout` is its own module, provisionally.** A checkout session is short-lived and
expiring; a merchant is long-lived. Different lifecycles usually mean different modules.
*Merge trigger:* if `checkout` turns out to own no state that outlives a session and merely
orchestrates `merchant` and `payments`, it is a service inside `merchant`, not a module.
*Must resolve by Phase 6* (open question 7).

**M3 — Fraud and AML share the `risk` module.** All three contexts consume the same signals,
evaluate versioned rule sets, and produce decisions and cases. Splitting them would either
duplicate the rules engine or create a shared engine owned by nobody.
They remain **distinct capabilities** with different timing and obligations: fraud
decisioning is synchronous and latency-bounded; AML monitoring is asynchronous and
retrospective; onboarding screening is `kyc`, not `risk` (`ROADMAP.md` Refinement 3).
*Split trigger:* AML acquiring regulatory isolation or retention obligations that fraud does
not share; or the latency budget of synchronous fraud decisioning being harmed by monitoring
workload.

**M4 — Audit lives in `platform`.** The audit trail is cross-cutting mechanism, not a domain
(ADR-0010): every module writes to it, in the caller's transaction. A separate module would
be depended on by everything, which is what `platform` already is.
*Split trigger:* audit acquiring its own retention, export or regulatory-reporting behaviour
substantial enough to constitute a domain — plausible around Phase 15.

**M5 — Reporting lives in `accounting`.** Financial reports are derived read models over the
same GL mapping, periods and postings that `accounting` owns. A separate module would need
read access to all of it and would own nothing.
*Split trigger:* non-financial or regulatory reporting whose inputs are not the GL.

**M6 — Two contexts were missing from `BOUNDED_CONTEXTS.md`, and have been added.**
`Transfers` and `Case Management` are both real bounded contexts with their own aggregates
and lifecycles (`DELIVERY_PLAN.md` Phases 4 and 13), but neither appeared in the original
list of 26 — while modules were already planned for both. That is a defect in the context
list, found by doing the mapping rather than assuming it, and it is exactly the kind of gap
this task exists to catch. Recording them is not a new decision; the decisions were taken in
the delivery plan. The list now holds 28 contexts.

**M7 — `paymentmethods` is separate from `payments` deliberately.** It exists to hold the
tokenised-instrument boundary. Keeping it separate makes "no raw card data crosses this
line" a boundary that can be reviewed and, later, enforced — rather than a convention inside
a large payments module. This is the isolation criterion from §7, applied in advance because
PCI scope is the one boundary that is far more expensive to introduce later.

**M8 — `settlement` and `reconciliation` are separate.** Settlement owns external evidence
(files, batches, expectations). Reconciliation owns the comparison and its outcome (matches,
breaks, resolutions). Conflating them is exactly the mistake `CLAUDE.md` warns about —
"settlement is not reconciliation".

**M9 — `accounting` is not `ledger`.** The ledger is the operational, authoritative posting
store. Accounting is the derived general-ledger view with periods, mapping and reports. One
is a system of record, the other a system of reporting; giving them one owner would let a
reporting change alter financial truth.

**M10 — API/Integration Platform is split between `platform` and `app`.** The error
contract, correlation propagation and provider SPI are mechanism and live in `platform`; the
HTTP surface, routing and composition live in `app`. Neither owns business state, so no
state has two owners.

---

## 4. Module Register

Every module records the nine attributes `CLAUDE.md` §Architecture requires: **responsibility,
ownership of state, transaction boundary, consistency boundary, APIs, events, failure
behaviour, security boundary, operational responsibility.**

Modules from Phase 1 onward do not exist yet. Their entries are the design contract those
phases must satisfy, not a description of code.

### `app` — Phase 0
- **Responsibility:** composition root. Wires modules together and hosts the HTTP surface and configuration. Owns no business capability.
- **Owns:** no persistent state; configuration only.
- **Transaction:** opens none of its own. It delegates to the module that owns the transaction.
- **Consistency:** n/a — holds no state.
- **APIs:** the platform's outward HTTP surface — routing, content negotiation and error rendering against the `platform` error contract. Declares no business endpoints of its own.
- **Events:** none. Publishes and consumes nothing; a composition root that reacted to events would be a business module.
- **Failure:** a module that fails to start fails startup. `app` must never degrade to serving traffic with a module missing, because a partially-wired platform serves wrong answers rather than no answers.
- **Security:** authentication at the edge and the TLS termination boundary. It makes **no** business authorization decisions — those belong to each module's published interface, so that a second caller (a job, an operator tool) cannot bypass them.
- **Operations:** liveness and readiness endpoints, build info, startup success, request-level telemetry.
- **Note:** `app` may depend on every module; no module may depend on `app`.
- **Also hosts:** the platform-wide ArchUnit rules (`ModuleBoundaryRulesTest`). They live here because `app` is the only module that sees every other one, and enforcing a boundary requires observing both sides of it.

### `sharedkernel` — Phase 0
- **Responsibility:** framework-free value types shared by every module.
- **Owns:** no persistent state. Holds `Money`, `CurrencyCode` and `RoundingPolicy` (P0-TSK-009, P0-TSK-010); typed identifiers, `Clock` and the event envelope follow in later Phase 0 tasks.
- **Transaction:** none. Performs no I/O.
- **Consistency:** n/a — immutable value types.
- **APIs:** value types and their operations; no service interface.
- **Events:** none. Defines the envelope *type*; publishes nothing.
- **Failure:** arithmetic errors are thrown, never absorbed (`INV-MON-04`, `INV-MON-06`).
- **Security:** no I/O, no secrets, no framework. Test libraries come from the catalog, not the Spring BOM, so "no Spring dependency" is a fact rather than an argument; `SharedKernelIsolationTest` fails if any Spring artefact reaches the classpath.
- **Operations:** none — nothing to run or monitor.

### `platform` — Phase 0
- **Responsibility:** the correctness primitives every module depends on. Mechanism, never business rules.
- **Owns:** idempotency records, outbox, inbox, audit records.
- **Transaction:** participates in the caller's transaction and never opens its own. A platform component that opened its own transaction would defeat its purpose.
- **Consistency:** strong; always same-transaction with the caller's state change.
- **APIs:** internal only — idempotent execution wrapper, outbox writer, inbox consumer wrapper, audit writer, error contract, provider SPI. No business HTTP surface.
- **Events:** publishes none of its own; it *is* the publication mechanism.
- **Failure:** relay retries with backoff, attempt counting and a poison path; inbox dedupes; a crash between commit and publish is recovered by the relay (`INV-EVT-01`, `INV-IDEM-04`).
- **Security:** audit records are `INSERT`/`SELECT` only for the application role (`INV-HIST-03`); log redaction is default-deny (`INV-AUD-02`).
- **Operations:** outbox depth and age, relay lag, consumer lag, poison-message queue, idempotency-conflict rate.
- **Note:** the only module every other module may depend on directly.

### `party` — Phase 1
- **Responsibility:** who exists as a legal party and what commercial relationship they hold.
- **Owns:** Party, Customer, profile data.
- **Transaction:** own; single-aggregate.
- **Consistency:** strong internally. Other modules hold `PartyId` only.
- **APIs:** registration, profile read/update, party lookup by id.
- **Events:** publishes `PartyRegistered`, `CustomerCreated`, `PartyProfileChanged`. Consumes `KycDecisionRecorded` to project verification status — a projection it never treats as authoritative.
- **Failure:** duplicate registration is idempotent by key; a missing KYC projection degrades to "unknown", never to "verified".
- **Security:** PII, restricted classification; ownership-scoped access; profile changes audited.
- **Operations:** registration rate, profile-change audit volume.

### `identity` — Phase 1
- **Responsibility:** who can authenticate, with what credential, from which device or session.
- **Owns:** Identity, Credential, MFA enrolment, Device, Session, Role assignment.
- **Transaction:** own.
- **Consistency:** strong. Session revocation is immediate, never eventually consistent — an eventually-revoked session is an unrevoked session.
- **APIs:** register, login, MFA challenge/verify, refresh, logout, session and device list/revoke, recovery initiation. Enumeration-safe responses.
- **Events:** `IdentityCreated`, `AuthenticationSucceeded`, `AuthenticationFailed`, `MfaEnrolled`, `SessionRevoked`, `CredentialChanged`. No credential material in any payload.
- **Failure:** credential store unavailable fails closed — authentication is refused, never bypassed. Lockout and backoff on repeated failure.
- **Security:** the platform's highest-sensitivity module. Credential material is isolated from `party` profile data and never leaves the module in any form. Every privileged action audited.
- **Operations:** authentication success/failure rates, lockouts, MFA outcomes, session lifetimes, recovery attempts — all alertable.
- **Note:** `identity` and `party` are deliberately separate. Who can log in and who exists as a legal party are different questions with different lifecycles.

### `kyc` — Phase 2
- **Responsibility:** whether a party may be onboarded, and the evidence for that decision.
- **Owns:** KYC/KYB Case, Verification Check, Screening Result (evidence), Document reference, Beneficial Owner, Risk Rating, Review Task.
- **Transaction:** own; case-scoped. Never spans a provider call.
- **Consistency:** the KYC decision is authoritative here. `party` may project it; it never overrides it.
- **APIs:** case initiation, document upload, status query, reviewer decision (privileged). Customer-facing status never leaks screening detail.
- **Events:** `KycCaseOpened`, `KycVerificationCompleted`, `KycDecisionRecorded`, `ScreeningHitRaised`.
- **Failure:** provider timeout leaves the check in an explicit indeterminate state, never a decision by assumption (`INV-LIFE-03`); duplicate provider callbacks produce one decision (`INV-IDEM-04`); a provider verdict is evidence, never the decision.
- **Security:** restricted PII; document access least-privilege and audited; reviewer actions elevated with reason codes; four-eyes on high-risk approvals.
- **Operations:** case age and throughput, straight-through rate, screening hit rate, provider latency and error rate, review queue depth.
- **Providers:** sanctions, PEP, adverse media, document verification — all via adapters (ADR-0008).

### `consent` — Phase 2
- **Responsibility:** the lawful basis for processing, held separately from authentication and authorization.
- **Owns:** Consent Record, versioned consent text, grant/withdraw history.
- **Transaction:** own; single-aggregate.
- **Consistency:** strong. A withdrawn consent takes effect immediately.
- **APIs:** grant, withdraw, query current basis for a purpose.
- **Events:** `ConsentGranted`, `ConsentWithdrawn`.
- **Failure:** absence of a recorded consent is a refusal, never an assumed grant (`INV-CRD-03`).
- **Security:** consent history is immutable; withdrawal is recorded, not deleted.
- **Operations:** consent coverage per purpose, withdrawal rate, expiring-basis alerts.
- **Note:** consent is not authentication and is not authorization (`CLAUDE.md` §Domain Distinctions).

### `ledger` — Phase 3 — *the financial authority*
- **Responsibility:** the authoritative record of financial position. The sole writer of postings.
- **Owns:** Chart of Accounts, Ledger Account, Journal Entry, Journal Line, Balance projection, Hold.
- **Transaction:** owns the posting transaction. Callers request a posting; the ledger decides whether and how it is written. The balance projection and the outbox record commit inside it.
- **Consistency:** journal entries strongly consistent. The balance projection is transactional, so its staleness bound is zero and it is safe for financial decisions (ADR-0009).
- **APIs:** internal posting command (idempotent), balance query with explicit as-of semantics, statement generation, hold place/release. Posting is **not** a public API: no external caller may post arbitrary entries.
- **Events:** `JournalEntryPosted`, `HoldPlaced`, `HoldReleased`, published via the outbox in the posting transaction. Consumes nothing — the ledger is commanded, it does not react.
- **Failure:** an unbalanced entry is rejected by the domain *and* by a database constraint; concurrent postings are resolved by the Phase 3 locking strategy; a crash between posting and publication is recovered by the outbox relay; a duplicate posting command produces one effect.
- **Security:** posting authority is privileged. Manual entries require elevation, reason codes and four-eyes. Journal tables are `INSERT`/`SELECT` only for the application role.
- **Operations:** posting rate and latency, projection-vs-postings comparison, trial balance zero per currency, hold utilisation, failed-posting reasons — all alertable.
- **Invariants:** `INV-LED-*`, `INV-BAL-*`, `INV-HIST-01`, `INV-REV-01`, `INV-ACC-01`.
- **Hard rule:** no other module writes a posting (`INV-LED-04`).

### `accounts` — Phase 3
- **Responsibility:** the customer-facing account and wallet *product* — its lifecycle and status, not its money.
- **Owns:** Customer Account, Wallet, account product lifecycle and status.
- **Transaction:** own. Requests ledger postings for financial effects; does not write them.
- **Consistency:** account status strong. Balance is read from `ledger` and never stored here.
- **APIs:** open, close, freeze, query; balance query delegating to `ledger`.
- **Events:** `AccountOpened`, `AccountClosed`, `AccountStatusChanged`.
- **Failure:** a closed or frozen account rejects money movement as a domain outcome, not an exception; ledger unavailability fails the balance read rather than returning a stale figure.
- **Security:** ownership-scoped access; status changes are privileged and audited.
- **Operations:** open/close rates, frozen-account count, status-change audit volume.
- **Note:** `accounts` owns the product; `ledger` owns the money. A balance is not a field on an account.

### `transfers` — Phase 4
- **Responsibility:** movement of funds between two internal accounts, with an explicit lifecycle.
- **Owns:** Beneficiary, Transfer, transfer lifecycle history.
- **Transaction:** the transfer state transition and the ledger posting commit together — one database, one transaction. This is the principal benefit of ADR-0001.
- **Consistency:** strong.
- **APIs:** `POST /transfers` with mandatory `Idempotency-Key`; status query; history; beneficiary CRUD. Asynchronous outcome modelled explicitly even though execution is synchronous today.
- **Events:** `TransferInitiated`, `TransferCompleted`, `TransferFailed`, `TransferReversed`.
- **Failure:** a client timeout followed by retry produces one effect (`INV-IDEM-01`); racing requests produce one movement (`INV-CON-02`); insufficient funds is a domain outcome with a defined state; a blocked transfer has a defined unwind path that strands no value.
- **Security:** ownership-scoped on the source account; step-up authentication for high value or a new beneficiary; every command audited.
- **Operations:** volume and value by state, failure reasons, stuck-transfer detector, idempotency-conflict rate.
- **Seams:** limit/velocity check and risk decision — interfaces defined here, implemented in Phase 13.

### `payments` — Phase 5
- **Responsibility:** money movement whose outcome is determined by an unreliable third party.
- **Owns:** Payment Intent, Payment Attempt, Authorization, Capture, Refund, Webhook Event (raw evidence), Provider State Mapping.
- **Transaction:** own. **No transaction spans a provider call**: state is committed before the call and the outcome applied in a separate transaction.
- **Consistency:** strong internally. Provider truth is eventually consistent and may be permanently unknown.
- **APIs:** intent create/confirm/cancel, attempt status, refund create. Idempotency mandatory on every money-moving command; terminal-state semantics documented.
- **Events:** `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentStateUnknown`, `RefundInitiated`, `RefundCompleted`.
- **Failure:** the module's defining concern. A timeout is never treated as failure; unknown is a modelled state with a reconciliation-by-query sweeper (`INV-LIFE-03`); duplicate and out-of-order webhooks produce one effect; an unrecognised provider state maps to indeterminate, never to success.
- **Security:** webhook signature verification and replay-window enforcement; provider credentials in secret management; tokenised instruments only — no PAN, ever; refunds are privileged.
- **Operations:** per-provider success/failure/latency, unknown-state count **and age**, webhook lag and duplicate rate, stuck-attempt alerting.
- **Providers:** PSP/processor adapters (ADR-0008), retaining raw evidence for `settlement`.

### `paymentmethods` — Phase 5
- **Responsibility:** the tokenised-instrument boundary. Exists so that "no raw card data crosses this line" is a reviewable boundary rather than a convention.
- **Owns:** Payment Method token references, instrument metadata.
- **Transaction:** own; single-aggregate.
- **Consistency:** strong.
- **APIs:** attach, detach, list. Never returns anything from which an instrument could be reconstructed.
- **Events:** `PaymentMethodAttached`, `PaymentMethodDetached`.
- **Failure:** a tokenisation provider being unavailable fails the attach; it never falls back to storing raw detail.
- **Security:** the platform's PCI boundary. Tokens only; no PAN, CVV or track data is stored, logged or transported anywhere in this platform.
- **Operations:** tokenisation success rate, provider latency, detached-token cleanup.

### `merchant` — Phase 6
- **Responsibility:** the merchant as a commercial counterparty, its fees and its payouts.
- **Owns:** Merchant, Merchant Account, Fee Schedule (versioned), Merchant Payout.
- **Transaction:** own; payout initiation requests ledger postings.
- **Consistency:** strong for merchant state. **Payable is derived from `ledger` postings and never stored** — a stored payable would be a second balance authority.
- **APIs:** merchant CRUD (privileged), payout initiation, merchant transaction reporting. Strict tenant scoping on every call.
- **Events:** `MerchantOnboarded`, `FeeAssessed`, `MerchantPayoutInitiated`.
- **Failure:** a payout against insufficient payable is a domain rejection; duplicate payout initiation produces one effect; the fee schedule version is pinned per transaction so a mid-flight change cannot reprice history (`INV-HIST-04`).
- **Security:** merchant authentication distinct from customer authentication; cross-tenant access impossible; payout destination change requires step-up, four-eyes and a cooling-off period.
- **Operations:** fee accrual, payout volume and age, per-merchant error rates, chargeback ratio (regulatory-relevant).

### `checkout` — Phase 6
- **Responsibility:** the customer-facing purchase experience and the order it produces.
- **Owns:** Checkout Session, Order.
- **Transaction:** own. Payment execution belongs to `payments`.
- **Consistency:** strong. Sessions expire; expiry is a domain event, not a side effect of a cleanup job.
- **APIs:** session create/retrieve/expire, hosted-checkout completion callback.
- **Events:** `CheckoutSessionCreated`, `CheckoutSessionExpired`, `OrderPaid`.
- **Failure:** a payment completing after session expiry is handled deterministically, never dropped; duplicate order creation produces one order.
- **Security:** session tokens are unguessable and single-purpose; no merchant may read another's sessions.
- **Operations:** conversion and abandonment, session expiry rate, late-completion count.

### `settlement` — Phase 8
- **Responsibility:** external evidence of money actually moving, and the expectation that it will.
- **Owns:** Settlement Batch, Settlement File (raw, checksummed), Settlement Line, Expectation.
- **Transaction:** own; ingestion is per-batch and resumable.
- **Consistency:** strong for ingested evidence. External settlement is inherently late relative to internal completion (`INV-SET-01`).
- **APIs:** batch upload and status (privileged, operational).
- **Events:** `SettlementBatchIngested`, `SettlementExpectationUnmet`.
- **Failure:** duplicate file ingestion produces no duplicate lines; a partially corrupt file fails the batch rather than importing half; late settlement is processed, not discarded (`INV-SET-03`); an unmet expectation ages and alerts rather than being assumed received.
- **Security:** settlement files may contain PII — encrypted, access-controlled, retained verbatim (`INV-HIST-02`).
- **Operations:** batch arrival punctuality, unmet-expectation ageing, ingestion failures.

### `reconciliation` — Phase 8
- **Responsibility:** proving internal truth agrees with external reality, and handling disagreement without erasing it.
- **Owns:** Match, Match Rule (versioned), Tolerance (versioned), Reconciliation Break, Investigation, Resolution.
- **Transaction:** a resolution and its compensating ledger posting commit together.
- **Consistency:** strong. Matching is deterministic: the same inputs always produce the same matches (`INV-REC-04`).
- **APIs:** break list/filter/assign, investigation notes, resolution proposal and approval. All privileged and operational.
- **Events:** `ReconciliationBreakRaised`, `BreakInvestigationStarted`, `BreakResolved`, `AdjustmentPosted`.
- **Failure:** a matching job that crashes mid-batch resumes without duplicate or lost matches; two operators resolving one break produce one resolution; an unmatched record always becomes a classified break (`INV-REC-02`).
- **Security:** resolution is the most sensitive non-administrative privilege in the platform — four-eyes above threshold, reason codes, full audit.
- **Operations:** match rate, unmatched value and count, break age distribution, suspense balance and age, time-to-resolution — all alertable.
- **Hard rule:** no code path deletes a break (`INV-REC-01`, `INV-REC-02`).

### `fx` — Phase 9
- **Responsibility:** currency conversion at a server-authoritative, time-bounded rate.
- **Owns:** FX Quote, Exchange Rate snapshot, FX Trade, FX Position, Currency configuration.
- **Transaction:** own; a conversion posts both ledger legs through an FX position account in one posting.
- **Consistency:** strong. A quote's validity window is explicit; expiry is a modelled event.
- **APIs:** quote request with explicit expiry, conversion execution referencing a quote id.
- **Events:** `FxQuoteIssued`, `FxQuoteExpired`, `FxTradeExecuted`, `CurrencyConverted`.
- **Failure:** a rate feed that is unavailable or stale causes the quote to be rejected rather than an old rate to be used; a quote expiring between validation and execution is rejected (`INV-FX-02`); rounding residual is posted, never absorbed (`INV-BAL-03`).
- **Security:** rates are server-authoritative; client-supplied rates are never trusted. Spread is recognised explicitly as revenue, never concealed in the applied rate (`INV-FX-03`).
- **Operations:** quote-to-trade rate, expiry rate, rate staleness age, FX position by currency, realised vs expected spread, rounding residual accumulation.

### `crossborder` — Phase 9
- **Responsibility:** payments that cross a currency or jurisdiction boundary, and the corridor rules governing them.
- **Owns:** Cross-Border Payment, Corridor policy.
- **Transaction:** own; delegates conversion to `fx` and execution to `payments`.
- **Consistency:** strong internally; settlement timing is corridor-dependent and often long.
- **APIs:** initiation with disclosed rate and fees, status query.
- **Events:** `CrossBorderPaymentInitiated`, `CrossBorderPaymentSettled`.
- **Failure:** conversion succeeding but the downstream payment failing has a defined unwind; settlement in an unexpected currency is a break, not a silent acceptance.
- **Security:** sanctions screening on counterparties; corridor-level policy enforcement.
- **Operations:** per-corridor volume, settlement latency, failure and unwind counts.

### `credit` — Phase 10
- **Responsibility:** reproducible, explainable credit decisions that can be defended years later.
- **Owns:** Credit Profile, Bureau request/response evidence, Credit Score, Risk Score, Policy Version, Rule, Decision (immutable), Reason Code, Decision Input Snapshot, Exposure.
- **Transaction:** own; a decision and its input snapshot commit together, or the decision is not reproducible.
- **Consistency:** strong. A recorded decision never changes.
- **APIs:** decision request (idempotent), decision retrieval, reason-code explanation, policy management (privileged, versioned, audited).
- **Events:** `CreditProfileUpdated`, `BureauDataRetrieved`, `CreditDecisionRequested`, `CreditDecisionRecorded`, `PolicyVersionActivated`.
- **Failure:** a bureau being unavailable follows an explicitly chosen path — decline, refer or degraded policy — never an assumed approval; partial bureau data does not silently become a decision; a policy activated mid-decision does not change that decision's pinned version (`INV-HIST-04`).
- **Security:** bureau access requires recorded consent (`INV-CRD-03`); credit data is restricted PII with retention limits; policy changes require four-eyes.
- **Operations:** approval/decline rates by policy version, decision latency, bureau availability and cost, reason-code distribution.
- **Providers:** credit bureau adapters (ADR-0008).

### `lending` — Phase 11
- **Responsibility:** originating and servicing loans, including time-based mechanics.
- **Owns:** Loan Application, Loan Offer, Loan, Repayment Schedule, Loan Instalment, Accrual Record, Repayment, Allocation, Delinquency State.
- **Transaction:** own; disbursement and repayment request ledger postings.
- **Consistency:** strong. Loan balances are derived from `ledger` postings, never stored independently.
- **APIs:** application submit, offer retrieve/accept, loan detail and schedule, repayment (idempotent), early settlement quote and execution.
- **Events:** `LoanApplicationSubmitted`, `LoanOffered`, `LoanAccepted`, `LoanDisbursed`, `InterestAccrued`, `RepaymentReceived`, `LoanDelinquent`, `LoanClosed`.
- **Failure:** an accrual job that crashes and re-runs produces no second accrual (`INV-IDEM-02`) — double accrual is money creation; a repayment for a closed loan is a domain outcome; disbursement posted but transfer failing has a compensating path.
- **Security:** disbursement is high-value and strictly authorised; schedule or rate modification requires four-eyes and reason codes; restructuring is fully audited.
- **Operations:** portfolio outstanding, accrual job success and duration, delinquency buckets, allocation anomalies, schedule-vs-actual drift.
- **Invariants:** `INV-IDEM-02` is the critical one here.

### `bnpl` — Phase 12
- **Responsibility:** merchant-financed instalment credit — one economic event producing two financial flows.
- **Owns:** BNPL Agreement, Instalment Plan, BNPL Instalment, Merchant Financing record, Refund Adjustment, Late Fee.
- **Transaction:** merchant financing and customer obligation are created atomically, or a tested compensating path runs.
- **Consistency:** strong. References `merchant` and `lending` concepts by identifier; owns neither.
- **APIs:** eligibility check at checkout, plan selection, agreement retrieval, schedule, early payoff, merchant-initiated refund.
- **Events:** `BnplEligibilityAssessed`, `BnplAgreementCreated`, `MerchantFinanced`, `InstalmentDue`, `InstalmentPaid`, `BnplRefundApplied`, `BnplAgreementClosed`.
- **Failure:** the hard case is refunds — a partial refund reduces remaining instalments under an explicit policy; a full refund closes the agreement leaving no residual obligation or stranded value; a chargeback plus refund on one order cannot double-credit the customer.
- **Security:** eligibility must not leak credit data to the merchant; merchant-initiated refunds are bounded by the original order.
- **Operations:** eligibility approval rate, plan mix, instalment delinquency, refund rate and its effect on outstanding, merchant financing exposure.

### `risk` — Phase 13
- **Responsibility:** signals, versioned rules, decisions and cases for fraud, AML monitoring and limits. Advisory to a domain lifecycle — never a substitute for it.
- **Owns:** Signal, Rule Set (versioned), Risk Assessment, Risk Decision, Limit definition, Velocity Counter, Alert, Case, Case Action.
- **Transaction:** own. **`risk` never writes another module's state and never posts to the ledger.** A blocked transfer is a transfer in a blocked state, unwound by `transfers`.
- **Consistency:** synchronous fraud decisions are strongly consistent and latency-bounded; AML monitoring is asynchronous and retrospective. Velocity counters are authoritative against durable state, not cache alone (`INV-CON-03`).
- **APIs:** internal risk evaluation with a strict latency budget; operational APIs for case queues, alert triage, limit management (privileged) and manual override with reason codes.
- **Events:** `RiskSignalRecorded`, `RiskDecisionMade`, `LimitBreached`, `AlertRaised`, `CaseOpened`, `CaseClosed`, `TransactionBlocked`.
- **Failure:** fail-safe behaviour on unavailability is explicit per operation and value band — a wrong default is either an outage or an open door; counter-store loss must not silently disable limits; duplicate signals do not double-count.
- **Security:** manual override requires reason codes and four-eyes above threshold. **Tipping-off control:** AML case detail is never exposed on a customer-facing surface.
- **Operations:** decision latency percentiles, block and false-positive rates, rule hit distribution, alert volume and queue age, case resolution time, limit breach rate.

### `accounting` — Phase 14
- **Responsibility:** turning the operational ledger into reportable financial information.
- **Owns:** GL Account, GL Mapping Rule (versioned), Accounting Period, Trial Balance snapshot, Period Close record, Report Definition, Report Run (immutable output retained).
- **Transaction:** own; period close is a controlled, approved transaction.
- **Consistency:** a derived read model over `ledger`. Reports are reproducible because mapping versions are pinned (`INV-ACC-04`).
- **APIs:** trial balance query, GL query with drill-down to source postings, period close initiation and approval (privileged), report generation and retrieval.
- **Events:** `AccountingPeriodOpened`, `TrialBalanceGenerated`, `PeriodCloseRequested`, `AccountingPeriodClosed`, `ReportGenerated`.
- **Failure:** postings arriving during close are handled deterministically; a close job crashing mid-run resumes; a posting into a closed period is rejected and must use a prior-period adjustment (`INV-ACC-03`).
- **Security:** period close requires elevation and four-eyes; issued reports are immutable and retained.
- **Operations:** trial-balance imbalance alert (must always be zero per currency), close duration, unposted items at close, report generation success.
- **Hard rule:** **no write access to ledger tables**, verified at the database privilege level.

### `notification` — Phase 1 onward
- **Responsibility:** telling people things. Never part of a money-moving decision.
- **Owns:** notification records and delivery state.
- **Transaction:** own. **Never inside a money-moving transaction** — a failed email must not roll back a payment.
- **Consistency:** eventually consistent by design.
- **APIs:** internal send request; template management (privileged).
- **Events:** consumes integration events from many modules; publishes `NotificationSent`, `NotificationFailed`.
- **Failure:** duplicate events must not send duplicate notifications (inbox dedupe); provider unavailability retries with backoff; permanent failure is recorded, not silently dropped.
- **Security:** no sensitive data in notification bodies (`INV-AUD-02`) — a notification is an unencrypted channel to an address the platform does not control.
- **Operations:** send and failure rates by channel, provider latency, retry depth, dead-letter volume.

---

## 5. Authoritative State Ownership

`CLAUDE.md` forbids shared mutable ownership of the same authoritative state across
independent domains.

**The `Owns:` lines in §4 are the authoritative enumeration.** This table groups them for
readability and adds the derived-state column; it deliberately does not repeat every item
verbatim, because two lists of the same thing drift and the drift is silent.

Single ownership is therefore checked against §4, by comparing every `Owns:` line and
failing on any state named by two modules — not by reading this table and hoping. That check
is what found the `Instalment` conflict below.

| Authoritative state | Sole owner | Derived state elsewhere |
|---------------------|-----------|-------------------------|
| *(none — pure value types)* | `sharedkernel` | Owns no persistent state |
| *(none — configuration only)* | `app` | Composition root; owns no persistent state |
| Idempotency record, outbox, inbox, audit record | `platform` | — |
| Party, Customer, profile | `party` | — |
| Identity, Credential, MFA enrolment, Device, Session, Role assignment | `identity` | — |
| KYC/KYB Case, Verification Check, Screening Result, Beneficial Owner, Risk Rating | `kyc` | `party` may project verification *status* (non-authoritative) |
| Consent Record, consent text version | `consent` | — |
| Chart of Accounts, Ledger Account, Journal Entry, Journal Line, Hold | `ledger` | — |
| Balance | `ledger` (projection of its own postings, ADR-0009) | `accounts`, `merchant`, `lending` **read** it; none store it |
| Customer Account, Wallet, account lifecycle/status | `accounts` | — |
| Beneficiary, Transfer, transfer lifecycle | `transfers` | — |
| Payment Intent, Attempt, Authorization, Capture, Refund, Webhook evidence | `payments` | — |
| Payment Method token reference, instrument metadata | `paymentmethods` | — |
| Merchant, Merchant Account, Fee Schedule, Merchant Payout | `merchant` | Merchant **payable** is derived from `ledger` postings, never stored |
| Checkout Session, Order | `checkout` | — |
| Settlement Batch, File, Line, Expectation | `settlement` | — |
| Match, Match Rule, Tolerance, Break, Investigation, Resolution | `reconciliation` | — |
| FX Quote, Exchange Rate snapshot, FX Trade, FX Position, Currency config | `fx` | — |
| Cross-Border Payment, Corridor policy | `crossborder` | — |
| Credit Profile, Bureau evidence, Score, Policy Version, Decision, Exposure | `credit` | — |
| Loan Application, Loan Offer, Loan, Repayment Schedule, **Loan Instalment**, Accrual Record, Repayment, Allocation, Delinquency State | `lending` | Loan balance derived from `ledger` |
| BNPL Agreement, Instalment Plan, **BNPL Instalment**, Merchant Financing record, Refund Adjustment, Late Fee | `bnpl` | References `merchant` and `lending` by id; owns neither |
| Signal, Rule Set, Risk Assessment, Risk Decision, Limit, Velocity Counter, Alert, Case, Case Action | `risk` | — |
| GL Account, GL Mapping Rule, Accounting Period, Trial Balance snapshot, Report Run | `accounting` | Derived read model over `ledger`; **no write access to ledger tables** |
| Notification record, delivery state | `notification` | — |

### Ownership conflicts found and resolved

**"Case" was at risk of two owners.** `DELIVERY_PLAN.md` Phase 13 describes case management
as "a shared capability across KYC, fraud and AML", and `kyc` separately owns a Review Task.
Shared ownership of one mutable aggregate is exactly what `CLAUDE.md` forbids.

Resolved: these are **two different states, not one shared state**. `kyc` owns the KYC
Review Task — a step inside a KYC case, scoped to that case's lifecycle. `risk` owns the
generic Case used by fraud and AML. Neither writes the other.
If a genuinely shared case store is later required, it must become its own module with a
single owner; it may not become a table two modules write. Decided by Phase 13.

**"Screening result" reads like one state but is two.** `kyc` owns onboarding-time screening
evidence; `risk` owns ongoing monitoring and rescreening results. They share adapter
infrastructure, not state (`ROADMAP.md` Refinement 3).

**"Instalment" was claimed by two modules.** The register had both `lending` and `bnpl`
owning an `Instalment`. They are **not** the same state: a loan instalment belongs to a
Repayment Schedule and is governed by amortisation and accrual; a BNPL instalment belongs to
an Instalment Plan and can be reduced by a merchant refund. They share a word, not a
lifecycle. Renamed to **Loan Instalment** and **BNPL Instalment** so the distinction is in
the name rather than in someone's head — the same discipline `CLAUDE.md` §Domain
Distinctions applies to Payment/Transfer/Transaction.

This was found by mechanically comparing the `Owns` lines, not by reading them. A table that
asserts single ownership is only worth as much as the check behind it.

**Balance is read by four modules and owned by one.** `accounts`, `merchant` and `lending`
all present balances. None stores one: each reads `ledger`. A stored balance in any of them
would violate `INV-BAL-01`.

## 6. Boundary Rules

### Module boundary

**Package convention.** A module's root package is `com.finapp.<module>`. Everything under
`com.finapp.<module>.internal` is private to that module; everything else in the module root
is its published surface. The ArchUnit rules depend on this convention, so a module that
ignores it is not protected by them.

**What enforces what.** Gradle enforces dependency direction structurally. `ModuleBoundaryRulesTest`
enforces the rest on every build. Anything below marked *(review)* has no mechanical check.

- Each module owns a package root; internals are not accessible across modules. *(ArchUnit)*
- Every production class sits under `com.finapp.<module>`, never directly in `com.finapp`.
  The rules are scoped by the module a class belongs to, so a class with no module would be
  silently exempt from all of them. *(ArchUnit)*
- A module exposes a published interface (commands, queries) and integration events. *(review)*
- No cross-module entity or ORM-relationship references. References are typed identifiers. *(ArchUnit)*
- Dependency direction is acyclic and enforced. *(Gradle, plus ArchUnit as defence in depth)*
- Every module applies the `java-library` plugin and uses the `api`/`implementation`
  distinction deliberately. `implementation` keeps a dependency off consumers' compile
  classpaths so a module cannot leak its internals downstream by accident; `api` makes
  exposure a reviewed choice. Where process isolation is absent, controlling transitive
  exposure is one of the few boundary mechanisms that genuinely holds. `platform` exposes
  `sharedkernel` via `api` because kernel value types appear in its own signatures.

### Monetary type boundary

`INV-MON-01` — no binary floating point on the path of a monetary value — is enforced by
`NoFloatingPointMoneyRulesTest` on every build (`P0-TSK-008`).

**Scope is default-deny over every production class**, not a list of financial packages. A
list of financial packages is a denylist, and a denylist fails in the case that matters: a new
package is unprotected by default and nothing reports the omission. Since this repository is
financial infrastructure, a package that cannot touch money is the exception. Exemptions live
in one named, currently empty set in the rule, so each one is a visible diff.

Four surfaces are checked: *(ArchUnit)*

- fields, including `double[]` and generic arguments such as `List<Double>`;
- method and constructor parameters and return types;
- calls to any method or constructor that takes or returns a floating-point value — this is
  what catches `new BigDecimal(0.1)`, `BigDecimal::doubleValue` and `ResultSet::getDouble`,
  none of which appear in any declaration of ours;
- reads and writes of a floating-point field.

**Known limit.** A `double` local computed only from compile-time constants and narrowed by a
cast is not detectable: a cast is a bytecode instruction rather than a declaration or access,
and javac inlines `static final double` literals so even `Math.PI` leaves no field access
behind. Such a value is inert unless it is stored, returned, passed or derived from something
non-constant, all of which are caught — but the limit is recorded because a rule believed to
be total is more dangerous than one whose edge is known. *(review)*

### Data boundary
- Schema per module in one PostgreSQL database (ADR-0006).
- **No foreign keys across module schemas.** Referential integrity across contexts is a
  domain concern, enforced at the boundary, not by the database.
- One writer per table. Cross-module reads go through the owning module's API, not by
  querying its tables.

### Transaction boundary
- A transaction never spans a call to an external provider.
- A transaction never spans two modules' authoritative state **except** where a documented
  ADR justifies it — currently only transfer-plus-posting and resolution-plus-adjustment,
  both of which are the explicit reason for choosing a monolith.
- The outbox write always shares the transaction of the state change (`INV-EVT-01`).

### Consistency boundary
- Authoritative state is strongly consistent within its owning module.
- Cross-module state is eventually consistent via events.
- No financial decision reads a projection with an unbounded staleness (`INV-BAL-05`).

### Event boundary
- Domain events stay inside a module. Integration events are published contracts and are
  versioned.
- Publication is via outbox only; no domain code publishes to the broker directly.
- Consumers are duplicate-, delay-, reorder- and replay-safe (`INV-EVT-04`).

### Security boundary
- Authentication at the edge; authorization at the module's published interface, not only at
  the HTTP layer.
- Privileged financial operations (posting, adjustment, break resolution, payout change,
  policy activation, period close) require elevation and produce audit records.
- Data classification governs handling; restricted data does not cross into modules that do
  not require it.

### External provider boundary
- Every external provider sits behind an adapter implementing a domain-owned interface.
- Provider vocabulary, error codes and state strings never appear in the domain or in public
  API contracts.
- Adapters retain raw request/response evidence (`INV-HIST-02`).
- Providers are assumed slow, duplicating, inconsistent, late and unavailable.

---

## 7. Extraction Criteria

A module may be extracted into a separate service only when at least one is demonstrated
with evidence, and an ADR records it:

1. Measured, materially different scaling requirement.
2. Isolation requirement (regulatory, PCI scope, or blast radius) that co-location cannot
   satisfy.
3. Independent deployment cadence blocked by co-location, with evidence of the block.
4. Separate ownership requiring an independent release boundary.

Explicitly **not** valid reasons: the module is large; the domain has a noun; microservices
are conventional; a diagram looks cleaner.

`ledger` is the module least likely to be extracted, because the atomicity it provides to
its callers is the primary architectural asset of this design.

`paymentmethods` is the module most likely to be extracted, on criterion 2: PCI scope is the
one isolation boundary that is far more expensive to introduce after the fact, which is why
it is a separate module from the outset (§3, M7).

---

## 8. Known Open Questions

Tracked in `CURRENT_STATE.md` §Unresolved Architectural Questions. Each has a deadline
because an unmade decision that becomes load-bearing is worse than a decision made early and
revisited.

| Question | Recorded position | Must resolve by |
|----------|-------------------|-----------------|
| Are `accounts` and `wallet` one module or two? | One (§3, M1), with a stated split trigger | Phase 3 |
| Is `checkout` a module or part of `merchant`? | Its own module (§3, M2), with a stated merge trigger | Phase 6 |
| Does a shared case store exist, and who owns it? | No shared store: `kyc` owns Review Task, `risk` owns Case (§5) | Phase 13 |
| Isolation level and locking strategy for concurrent postings | Undecided — ADR required | Phase 3 |
| Chart-of-accounts structure and its relation to the Phase 14 GL | Undecided — ADR required | Phase 3 |
| Balance projection placement: ledger schema or separate read store | Ledger schema, transactional (ADR-0009) | Phase 3 |

### What this map does and does not enforce

As of `P0-TSK-007`, §6's structural rules are mechanical. `ModuleBoundaryRulesTest` fails the
build if a module reaches into another's internals, references another's persistence
entities, depends upward, or lets a framework into `sharedkernel`. Each rule was proven by a
deliberate violation rather than assumed to work.

Two things remain on review, and are worth stating plainly rather than letting a reader
assume the diagram is guaranteed throughout:

- **No floating-point money.** `INV-MON-01` is the platform's most fundamental rule and is
  still unenforced. `P0-TSK-008`, which also needs `Money` to exist (`P0-TSK-009`).
- **Single ownership of authoritative state.** §5 is checked by comparing the register's
  `Owns:` lines, which catches a *declared* second owner. Nothing detects a module that
  quietly starts writing state another module declares — that needs schema-level privileges
  (`P0-TSK-022`) and, ultimately, review.

The rules only protect modules that follow the package convention in §6. A module whose
internals are not under `com.finapp.<module>.internal` is invisible to the internals rule —
though a class belonging to no module at all is now itself a violation.

Coverage is self-checking: `everyModuleWithProductionCodeIsAnalysed` derives, from the
classpath, every module output holding at least one real class, and fails if any of them was
not imported. Without it the rules would pass silently for a module that had been dropped
from the analysis, which is the failure mode that makes architecture tests worse than
useless — they would still report success.
