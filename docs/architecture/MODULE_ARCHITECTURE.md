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

No floating-point money (`INV-MON-01`) is enforced separately by
`NoFloatingPointMoneyRulesTest` as of `P0-TSK-008` — see §6, *Monetary type
boundary*. It is kept out of `ModuleBoundaryRulesTest` because it is a financial
invariant rather than a module boundary, and is scoped over every class rather than
by module.

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
| 2 | Identity, Authentication & Authorization | `identity` | 1 | Deliberately separate from `party`. **Authorization merged — see ADR-0031** for the justification and split trigger |
| 3 | KYC/KYB | `kyc` | 2 | |
| 4 | Consent | `consent` | 2 | |
| 5 | Accounts | `accounts` | 3 | |
| 6 | Wallet | `accounts` | 3 | **Merged — provisional.** See M1 |
| 7 | Ledger | `ledger` | 3 | The financial authority |
| 8 | Transfers | `transfers` | 4 | Added by this task. See M6 |
| 9 | Payments | `payments` | 5 | |
| 10 | Payment Methods | `paymentmethods` | 5 | Separate for PCI scope. See M7 |
| 11 | Checkout | `checkout` | 6 | Confirmed by ADR-0053 (2026-09-21); M2's merge trigger stands as watchdog |
| 12 | Merchant | `merchant` | 6 | |
| 13 | Settlement | `settlement` | 8 | The external evidence and its recognition. See M8 (amended by ADR-0064) |
| 14 | Reconciliation | `reconciliation` | 8 | Separate from `settlement`, with no build edge between them; owns the settlement expectations since ADR-0064. See M8 |
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
| 29 | Disputes | `payments` | 7 | **Merged.** See M11 (the Phase 6 → 7 transition, ADR-0061 §1) |

### Merge and separation decisions

**M1 — Wallet is inside `accounts`, provisionally.** A wallet is a stored-value account: it
has a balance derived from the ledger, a lifecycle and a holder, exactly as a customer
account does. Two modules would duplicate that lifecycle and, worse, give two owners to
"the product a balance belongs to".
*Split trigger:* wallets acquiring a materially different lifecycle — multi-currency
sub-balances, third-party wallet custody, or a distinct regulatory treatment.
*Must resolve by Phase 3* (open question 4).

**M2 — `checkout` is its own module, confirmed by ADR-0053.** A checkout session is short-lived
and expiring; a merchant is long-lived. Different lifecycles usually mean different modules.
*Merge trigger, kept as a watchdog:* if `checkout` turns out to own no state that outlives a
session and merely orchestrates `merchant` and `payments`, it is a service inside `merchant`, not
a module. It is not met: the order outlives every session, and the orchestration lives in `app`,
not in `checkout`.
*Open question 7 is closed* (ADR-0053 §2). *(This read "provisionally" and "must resolve by
Phase 6" until the Phase 6 review, `P6-DOC-001`.)*

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
(files, batches, lines) and recognises it. Reconciliation owns the expectations, the comparison
and its outcome (matches, breaks, resolutions, suspense items). Conflating them is exactly the
mistake `CLAUDE.md` warns about — "settlement is not reconciliation". *(Amended by the Phase 7 →
8 transition, ADR-0064: this read "Settlement owns external evidence (files, batches,
expectations). Reconciliation owns the comparison and its outcome (matches, breaks,
resolutions)". An expectation is internal state that allocation and ageing drive; in `settlement`
it would be a row `reconciliation` mutates, and `INV-REC-07` a cross-module invariant held by
care.)* There is no build edge between them: `app` composes both through ports.
*Merge trigger:* `reconciliation` needing `settlement`'s rows on its hot path beyond the copy the
intake makes, or `settlement` needing reconciliation's dispositions to decide acceptance. Either
reopens ADR-0064; the edge is never added quietly.

**M9 — `accounting` is not `ledger`.** The ledger is the operational, authoritative posting
store. Accounting is the derived general-ledger view with periods, mapping and reports. One
is a system of record, the other a system of reporting; giving them one owner would let a
reporting change alter financial truth.

**M10 — API/Integration Platform is split between `platform` and `app`.** The error
contract, the API version and correlation propagation are contract vocabulary and live in
`platform`, along with the provider SPI; the HTTP surface, routing, error rendering and the
application of the version prefix live in `app`. Neither owns business state, so no
state has two owners.

The published contract itself — [`docs/api/openapi.json`](../api/openapi.json) — is generated
from the running application on every build and compared against the committed copy
(ADR-0015). It is an artefact of `app`, because only `app` sees every route; nothing about
OpenAPI is deployed.

**M11 — Disputes live in `payments`** (the Phase 6 → 7 transition, ADR-0061 §1). A dispute is a
lifecycle on a card payment *(recorded against whatever card attempt the network names, whatever
its capture state - an attempt that captured nothing credited nobody, so its chargeback is all
excess; "on a captured card payment" until the Phase 7 review)*, and the chargeback's bound and the refund's bound are one
arithmetic: refunded plus charged back never exceeds captured, judged under the attempt row's
lock that both paths take. In two modules that lock would have two owners, or the bound a
cross-module read that races. *Split trigger:* disputes acquiring a lifecycle the payment does
not share — merchant-initiated arbitration with its own case management, or dispute handling for
payments this platform did not capture — at which point the bound moves behind a port `payments`
still arbitrates.

---

## 4. Module Register

Every module records the nine attributes `CLAUDE.md` §Architecture requires: **responsibility,
ownership of state, transaction boundary, consistency boundary, APIs, events, failure
behaviour, security boundary, operational responsibility.**

The entries for modules of Phases 0-8 describe code that exists; the entries for later phases'
modules are the design contract those phases must satisfy, not a description of code. *(This
read "modules from Phase 1 onward do not exist yet" until the Phase 6 review, `P6-DOC-001`, and
"Phases 0-6" until the Phase 8 review, `P8-DOC-001`.)*

### `app` — Phase 0
- **Responsibility:** composition root. Wires modules together and hosts the HTTP surface and configuration. Owns no business capability. **It may sequence two modules inside one transaction** where a use case spans bounded contexts and belongs wholly to neither — registration (`P1-TSK-006`) is the first, and `app` is the only module that *can* host it, since either business module hosting it would have to depend on the other and the isolation tests forbid that. The limit is that `app` owns no rule, no event and no audit record: each module writes its own, and `app` contributes two calls and a transaction. A class here that started deciding *what* to write would be a business module wearing the composition root's name.
- **Owns:** no persistent state; configuration only — plus the JDBC connection pool, which is infrastructure rather than state. Readiness has to be answered through the pool the application actually uses, since a health check with its own connection reports healthy while the pool is exhausted. A pool is not a data-access mechanism: that was unresolved question 12, left open by ADR-0016 §5 and **settled by ADR-0033** — explicit SQL through `JdbcClient`, no ORM.
- **Transaction:** opens none of its own. It delegates to the module that owns the transaction.
- **Consistency:** n/a — holds no state.
- **APIs:** the platform's outward HTTP surface — routing, content negotiation and error rendering against the `platform` error contract. Declares no business endpoints of its own.
- **Events:** none. Publishes and consumes nothing; a composition root that reacted to events would be a business module.
- **Failure:** a module that fails to start fails startup. `app` must never degrade to serving traffic with a module missing, because a partially-wired platform serves wrong answers rather than no answers. An unavailable **dependency** is the opposite case and is handled the opposite way: the application starts, reports NOT_READY, and receives no traffic (ADR-0016 §4). Refusing to boot would leave an orchestrator with a crash-loop instead of an instance able to say which dependency is broken.
- **Security:** authentication at the edge and the TLS termination boundary. It makes **no** business authorization decisions — those belong to each module's published interface, so that a second caller (a job, an operator tool) cannot bypass them.
- **Operations:** liveness, readiness and build-info endpoints (`P0-TSK-027`, ADR-0016), startup success, request-level telemetry. Liveness depends on nothing external; readiness includes PostgreSQL and excludes Kafka and Redis, which are transport and cache rather than truth. All three are unversioned, because an orchestrator's configuration is deployment-scoped rather than a contract.
- **Note:** `app` may depend on every module; no module may depend on `app`.
- **Also hosts:** the platform-wide ArchUnit rules — `ModuleBoundaryRulesTest` (module boundaries), `NoFloatingPointMoneyRulesTest` (`INV-MON-01`), their shared coverage derivation `ProductionModules`, and `ArchitectureRulesAreDocumentedTest`, which holds §6 of this document and the enforced rule set to each other. They live here because `app` is the only module that sees every other one, and enforcing a boundary requires observing both sides of it.

### `sharedkernel` — Phase 0
- **Responsibility:** framework-free value types shared by every module.
- **Owns:** no persistent state. Holds `Money`, `CurrencyCode` and `RoundingPolicy` (P0-TSK-009, P0-TSK-010); typed identifiers (P0-TSK-012); correlation and causation *identifiers* (moved down in P0-TSK-018, because the envelope carries them and the shared kernel may not depend upward); and the event envelope (P0-TSK-018). The correlation *context* — the mechanism that carries a flow across threads and writes it to the MDC — stays in `platform`, where its logging dependency belongs.
- **Transaction:** none. Performs no I/O.
- **Consistency:** n/a — immutable value types.
- **APIs:** value types and their operations; no service interface.
- **Events:** none. Defines the envelope *type*; publishes nothing.
- **Failure:** arithmetic errors are thrown, never absorbed (`INV-MON-04`, `INV-MON-06`).
- **Security:** no I/O, no secrets, no framework. Test libraries come from the catalog, not the Spring BOM, so "no Spring dependency" is a fact rather than an argument; `SharedKernelIsolationTest` fails if any Spring artefact reaches the classpath.
- **Operations:** none — nothing to run or monitor.

### `platform` — Phase 0
- **Responsibility:** the correctness primitives every module depends on. Mechanism, never business rules.
- **Owns:** idempotency records, outbox, inbox, audit records, the correlation/causation context (`P0-TSK-014`).
- **Transaction:** participates in the caller's transaction and never opens its own. A platform component that opened its own transaction would defeat its purpose.
- **Consistency:** strong; always same-transaction with the caller's state change.
- **APIs:** internal only — idempotent execution wrapper, outbox writer, outbox relay, inbox consumer wrapper, audit writer, auditable-action registry, error contract, provider SPI. No business HTTP surface.
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
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0071; the first two permissions and the first role exist since `P8-TSK-003`)*: four permissions — `SETTLEMENT_INGEST` and `RECONCILIATION_INVESTIGATE` (built, `P8-TSK-003`, with the settlement routes that check them), `RECONCILIATION_RESOLVE` (built with `P8-TSK-015`'s resolution doors) and `RECONCILIATION_ADMINISTER` (built with the opening-position backfill's route, `P8-TSK-007`, and the controller's doors of `P8-TSK-022`) *(corrected 2026-10-01, `P8-DOC-001`: this read "each arriving with its first route")* — and two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` (`V016`, built with `P8-TSK-003`, holding the first two; `RECONCILIATION_RESOLVE` joins it with `P8-TSK-015`) and `RECONCILIATION_CONTROLLER` (`V017`); `LEDGER_OPERATOR` is not extended, so the desk that moves money does not reconcile it.
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
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0065 and ADR-0071; the adjustment's reason code, origin and binding built by `P8-TSK-006`, `PROCESSING_COSTS` by `P8-TSK-009`, the two reconciliation purposes and the owned door's use by `P8-TSK-015`, `CASH_AT_BANK` by `P8-TSK-016`)*: four operational purposes, each arriving with its first poster — `PROCESSING_COSTS` (`V016`), `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` (`V017`), `CASH_AT_BANK` (`V018`) — and adjustments carrying a closed `reason_code` and an `origin` (`V015`): the generic route assigns `MANUAL_CORRECTION` with its request unchanged, a `MANUAL`-origin line on a reconciled position is refused at the domain and the database (`ledger.AdjustmentOnReconciledPosition`), and the generic approval and `DELETE` refuse `RECONCILIATION`-origin proposals (`ledger.AdjustmentOriginMismatch`), which reconciliation decides through `proposeOwned`, `approveOwned` and `rejectOwned`.
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
- **Events:** `TransferCompleted`, `TransferFailed`, `TransferReversed` — the terminal facts. *(`TransferInitiated` removed by the Phase 3 → 4 transition: under ADR-0043 it commits beside its own outcome — ADR-0044.)*
- **Failure:** a client timeout followed by retry produces one effect (`INV-IDEM-01`); racing requests produce one movement (`INV-CON-02`); insufficient funds is a committed domain outcome with a defined state; a refused transfer strands no value because nothing partial can exist (ADR-0043 — one transaction).
- **Security:** ownership-scoped on the source account; step-up authentication at **beneficiary creation** (`MULTI_FACTOR` when enrolled — the value-threshold trigger is a recorded Phase 13 seam, per the transition); every command audited; reversal behind `TRANSFER_REVERSE` with a required reason.
- **Operations:** volume by outcome, failure reasons, latency, idempotency-conflict rate. *(Value-by-state and the stuck-transfer detector corrected by the transition — the first is a financial figure outside the ledger's authority, the second has no subject under ADR-0043.)*
- **Seams:** limit/velocity check and risk decision — interfaces defined here, implemented in Phase 13.

### `payments` — Phase 5
- **Responsibility:** money movement whose outcome is determined by an unreliable third party.
- **Owns:** Payment Intent, Payment Attempt, Authorization, Capture, Refund, Webhook Event (raw evidence), Provider State Mapping, Payment Rail, Routing Decision, A2A Payment, Instant Payment, Withdrawal, Dispute, Chargeback, Dispute Response, Dispute Evidence, Clearing Record, Unmatched Confirmation *(the last four added by the Phase 7 review, `P7-DOC-001`)*.
- **Phase 7** *(planned by the Phase 6 → 7 transition, ADR-0059…0062; shipped by `P7-TSK-001`…`-015`, and read against the code by the phase review, `P7-DOC-001`)*: the per-model attempt machines, routing, the card void and clearing evidence, pay-ins, withdrawals, return payments and disputes — context 29 merged here (M11). Every new join (a dispute's counterparty lines, a pay-in's credit account, a bank instrument's reference) is a port `app` implements, the `CaptureComposition` shape; the build-graph edges do not change.
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0064 and ADR-0067; the port and the card openers built by `P8-TSK-004`, every other opener by `P8-TSK-005`)*: the completing appliers — `PaymentOutcomes` (capture, card refund, push execution and return, the refund's kind chosen by the refunded rail's DECLARED refund mode), `WithdrawalOutcomes`, `ChargebackAccounting` (chargeback, won, fee — each after its own clearing posting, keyed by the network's reference under the stage's kind), `UnmatchedConfirmations` (the claim winner only, keyed by the scheme reference alone) and `PaymentClearing` (the acquirer reference as an alias, the `RECORDED` branch alone), every one **built**, each in its acting branch after its posting — call the port **`SettlementExpectations`**, a required constructor parameter implemented in `app`, past their acting exit and only when the stored rail declares a clearing purpose, inside the completing transaction; a collision never fails a payment. No payments migration: what Phase 8 reads of Phase 7's records the transition's repairs already store (`V023`) — each parking's `named_reference`, `settlement_cycle`, `cause` (`UNATTRIBUTED`, `ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`) and, exactly when attributed, `attempt_id`, on which `P8-TSK-020`'s suspense item keys, its raw statement found through `provider_evidence`'s fifth subject; and `scheme_execution_claim`, one explaining subject per `(rail, scheme reference)`, which `InternalReferenceLookup` reads to type a scheme line's break. A second, different clearing of one capture (`SECOND_PRESENTMENT`) stays in the retained evidence only; its cleared amount is Phase 8's first evidence hop, the PSP report's line, not a `payments` table (ADR-0065). The unmatched-confirmation gauges' descriptions become "parked, ever", and their alertable signal moves to `finapp.reconciliation.suspense.*` (`P8-TSK-020`). *(The Phase 7 → 8 transition's repairs, reflected by its consistency review.)* *(`P8-TSK-020` added the port's `parked`: a parking's CREDIT suspense item and its owning break, opened in the parking's own transaction through reconciliation's `ParkedConfirmations`.)* *(`P8-TSK-021` added the public read store `SettlementCycleReads`: the cycle tokens the pay-ins, withdrawals and parkings name, each with its earliest record, one keyset page at a time — the scheme's pull worklist, read by `app`, never by SQL across schemas.)*
- **Transaction:** own. **No transaction spans a provider call**: state is committed before the call and the outcome applied in a separate transaction.
- **Consistency:** strong internally. Provider truth is eventually consistent and may be permanently unknown.
- **APIs:** intent create/confirm/cancel, attempt status, refund create. Idempotency mandatory on every money-moving command; terminal-state semantics documented. *(Phase 7 added, listed by the review: the void, customer and operator; the customer's withdrawal and its read; the operator's routing versions, rail availability and routing explanation; the merchant's and the operator's dispute reads, evidence upload and read, representment and acceptance; the chargeback-ratio report; and the instant scheme's signed callback door. `docs/api/openapi.json` is the contract.)*
- **Events:** `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentStateUnknown`, `RefundInitiated`, `RefundCompleted`, `RefundFailed`, `AuthorizationVoided` *(`P7-TSK-004`: the promise released — a terminal fact publishes under ADR-0044's doctrine, with intent identifier and state, never an amount)*, `PaymentClearedOnRail` *(`P7-TSK-005`: the capture cleared on the network — evidence announced with the recording transaction, identifiers and the rail, never an amount and never the network references, whose home is the classified table)*, `RailSelected` *(`P7-TSK-003`: the routed dispatch is a decided fact, published with the Tx1 that pinned it — attempt, decision, rail and policy version, no amount, ADR-0060)*, `WithdrawalInitiated`, `WithdrawalCompleted`, `WithdrawalFailed` *(`P7-TSK-008`: the dispatch's durable fact and the scheme's two terminal words — identifiers, status, rail and failure class only, never an amount or a reference; `UNKNOWN` publishes nothing, the standing hold is its record)*, `PaymentExecuted` *(`P7-TSK-009`: the push pay-in's terminal fact — `EXECUTED` is not `CAPTURED` in the event vocabulary either, so no consumer can mistake one rail's completion for another's; identifiers and status only, never an amount, never the handle)*, `DisputeOpened`, `ChargebackReceived`, `DisputeResolved` *(`P7-TSK-012`, ADR-0061: the dispute is its own aggregate, so these carry the dispute as aggregate with the attempt and intent in the payload — opened at its entry stage, the network having taken the funds, and the terminal outcome; published once each by the acting transaction, in causal order, never an amount, never the network's reference or code; since `P7-TSK-013` `ChargebackReceived` and `DisputeResolved` carry the split's accounts — `counterpartyAccountId` when the counterparty bears a posted share, `recoverableAccountId` when part rests in `CHARGEBACK_RECOVERABLE` — identifiers still, never amounts)*, `DisputeResponseSubmitted` *(`P7-TSK-014`, ADR-0061 §7: the PSP took a responder's answer - a representment or an acceptance - carried as the response's own aggregate with its kind, the dispute, the attempt and the intent; published once from inside the `SUBMITTED` conditional; it moves no stage, which the network's own statements still decide - never a reference, a document or an amount)* *(added by the Phase 4 → 5 transition with provenance: terminal facts publish — ADR-0044's doctrine — and a refund's failure is one; `RefundInitiated` stays, legitimately, because under ADR-0046 the dispatch commits durably before its own outcome exists — the opposite of the `TransferInitiated` case)*.
- **Failure:** the module's defining concern. A timeout is never treated as failure; unknown is a modelled state with a reconciliation-by-query sweeper (`INV-LIFE-03`); duplicate and out-of-order webhooks produce one effect; an unrecognised provider state maps to indeterminate, never to success.
- **Security:** webhook signature verification and replay-window enforcement; provider credentials in secret management; tokenised instruments only — no PAN, ever; refunds are privileged.
- **Operations:** per-provider success/failure/latency, unknown-state count **and age**, webhook lag and duplicate rate, stuck-attempt alerting. *(Phase 7, `P7-TSK-015`: `finapp.payments.rail.outcome` — every acting judgement by rail, type and outcome, reported by the three appliers through the `RailOutcomeObserver` port and counted once its transaction commits, which also feeds `finapp.payments.attempt` and `.refund`, so the doors count no judgement any more; `finapp.payments.rail.latency` by rail and operation beside `finapp.payments.provider.latency`, which names the true provider — the instant scheme is timed under its own name; `finapp.payments.routing.decision`; the stuck pairs `finapp.payments.withdrawal.unknown.*` and `finapp.payments.dispute.response.unknown.*`; `finapp.payments.dispute` by stage; `finapp.payments.dispute.deadline.near`; and the chargeback ratio per merchant as the audited operator report `GET /v1/operator/reports/chargeback-ratio`, never a tag.)*
- **Providers:** PSP/processor adapters (ADR-0008), retaining raw evidence for `settlement`.
- **Ports `app` implements:** `PaymentParticipants` (the caller's wallet and instrument, resolved from authoritative state — `P5-TSK-009`) and, from `P6-TSK-005`, **`CaptureComposition`** — *the lines an approved capture posts*. The second exists because a merchant-bound capture settles in four lines (ADR-0050 §3) and composing them here would mean this module knowing what a merchant is, what a fee is and which schedule version priced it. **Fee vocabulary never enters this domain model**, which is `INV-PAY-03`'s discipline at a second vocabulary; the flow that created the intent supplies the lines and the capture posts what it is handed. **`RefundComposition`** (`P6-TSK-014`) is the capture port's mirror, for the same reason — *the lines a completed refund posts* and, from `P6-TSK-015`, *the amount its dispatch holds*. *(Added at the Phase 6 review, `P6-DOC-001`.)* **`DisputeComposition`** (`P7-TSK-013`, implemented by `MerchantBoundDisputeComposition`) - *whose account a chargeback's share is attributed to*, the merchant's payable checked against the sale's pin or the payer's wallet, so this module never learns whose money an account holds; and **`RailOutcomeObserver`** (`P7-TSK-015`, implemented by `CommittedRailOutcomes`) - each acting judgement reported by its applier and counted after its commit. *(Both added by the Phase 7 review, `P7-DOC-001`.)*

### `paymentmethods` — Phase 5
- **Responsibility:** the tokenised-instrument boundary. Exists so that "no raw card data crosses this line" is a reviewable boundary rather than a convention.
- **Owns:** Payment Method token references, instrument metadata.
- **Phase 7** *(shipped `P7-TSK-007`, ADR-0062 §2)*: the `BANK_ACCOUNT` instrument, registered through the grant exchange — an opaque provider reference (`DestinationReference`), a four-character suffix and the confirmation-of-payee result (`PayeeCheck`, `NO_MATCH` only with the recorded acknowledgement), never an account number or an alias (`INV-RAIL-03`). One aggregate, two kinds keyed on the frozen `kind` birth fact; the register keyed per party because the grant is single-use at the provider.
- **Transaction:** own; single-aggregate.
- **Consistency:** strong.
- **APIs:** attach, register bank account (keyed), detach, list. Never returns anything from which an instrument could be reconstructed.
- **Events:** `PaymentMethodAttached`, `PaymentMethodDetached`.
- **Failure:** a tokenisation provider being unavailable fails the attach; it never falls back to storing raw detail.
- **Security:** the platform's PCI boundary. Tokens only; no PAN, CVV or track data is stored, logged or transported anywhere in this platform.
- **Operations:** tokenisation success rate, provider latency, detached-token cleanup.

### `merchant` — Phase 6
- **Responsibility:** the merchant as a commercial counterparty, its fees and its payouts.
- **Owns:** Merchant, Merchant API Key (a hash, never a secret; `P6-TSK-002`, ADR-0052), Fee Schedule (versioned — schedule and versions immutable from creation; `P6-TSK-004`) and each merchant's assignment to one, Payment Fee Pin (the schedule version each merchant-bound payment is priced by; `P6-TSK-005`), Payout Destination (a proposal flow; `P6-TSK-011`, ADR-0056), Merchant Payout (hold-then-dispatch, four states, encrypted evidence; `P6-TSK-012`, ADR-0051, ADR-0057), Payout Return (a born-once fact applied from settlement evidence; `P8-TSK-019`, ADR-0073) — each machine with its append-only history. *(Corrected at the Phase 6 review, `P6-DOC-001`: this listed a "Merchant Account", which is the ledger's, and omitted the key and the pin.)*
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0064 and ADR-0073; the declaration built by `P8-TSK-002`, the port by `P8-TSK-005`)*: `PayoutSettlementDeclaration.CLEARING_PURPOSE`, the one constant `MerchantPayoutOutcomes` posts to and settlement's payout source reads; the port `PayoutSettlementExpectations` (**built**: a required constructor parameter of `MerchantPayoutOutcomes` with no do-nothing production implementation, called at `COMPLETED` after the posting inside the completing transaction, whichever resolver answered, keys `PAYOUT_PROVIDER_REF` and `OUR_REF`); and the payout return (`V008`), applied by `PayoutReturns.apply` from settlement evidence under the payout row's lock — its own posting (`merchant-payout-return:<payoutId>`), then the `payout_return` row naming the entry, its money held equal to the payout's by a composite foreign key, then the expectation, in one transaction, publishing `MerchantPayoutReturned` (identifiers only), the payout staying `COMPLETED` (`INV-LIFE-04`). A closed merchant's return is not applicable — the close, since the transition's repair, refuses while a chargeback can still be won and closes the payable's ledger account — and falls to a four-eyes resolution. `MerchantPayable` gains `payoutsReturned` (`P8-TSK-019`) and `reconciliationAttributed` — every payable line in a `RECONCILIATION`-origin `ADJUSTMENT` entry, whatever it faces (`P8-TSK-015`, with its first poster) — (`INV-MER-02`, amended) *(order, owners and rule settled by the transition's consistency review, A6, A12 and A13)*. *(The return built by `P8-TSK-019`: `PayoutReturns.apply` answers a typed outcome, every one but `APPLIED` writing nothing; `app`'s `PayoutReturnSweep`, run by the leaderless `PayoutReturnSchedule`, composes it with reconciliation's declared port `WaitingPayoutReturns` and settlement's `acceptedOnOf`.)*
- **Transaction:** own; a payout's dispatch places a `ledger` hold on the payable, and its completion releases it and requests the `merchant-payout:<payoutId>` posting (DR payable / CR `PAYOUT_CLEARING`) in one transaction.
- **Consistency:** strong for merchant state. **Payable is derived from `ledger` postings and never stored** — a stored payable would be a second balance authority. Every account involved is `ledger`'s: each merchant's `MERCHANT_PAYABLE`, and the platform's operational `FEE_REVENUE` and `PAYOUT_CLEARING`; there is no reserve account.
- **APIs:** merchant administration, operator-only and privileged — onboard, read, suspend, reinstate, close, and nothing else: no update and no delete, because a merchant's identity is frozen (`V002`) — plus API key issuance, listing and revocation, fee schedules, their versions and assignment; payout destination proposal and decisions (operator only — two operators, four-eyes); payout initiation (the merchant's key, or an operator over `MERCHANT_PAYOUT` — two routes) and read; the merchant's own view, payable and transaction report over its key. Strict tenant scoping on every call. *(This read "merchant CRUD" until the Phase 6 review, `P6-DOC-001`.)*
- **Events:** `MerchantOnboarded`, `FeeAssessed`, `FeeReturned`, `MerchantPayoutInitiated`, `MerchantPayoutCompleted`, `MerchantPayoutFailed` *(pair added by the Phase 5 → 6 transition — terminal facts publish, the `RefundFailed` precedent; `FeeReturned`, a refund returning a fee share since `P6-TSK-014`, added at the Phase 6 review, `P6-DOC-001`)*.
- **Failure:** a payout against insufficient payable is a domain rejection; duplicate payout initiation produces one effect; an ambiguous payout is `UNKNOWN` with its hold standing until the resolution sweep's query resolves it, and "never received" is concluded only behind the send permit (ADR-0057); the fee schedule version is pinned per transaction so a mid-flight change cannot reprice history (`INV-HIST-04`).
- **Security:** merchant authentication distinct from customer authentication; cross-tenant access impossible; payout destination change requires four-eyes, a cooling-off period and — when the operator has an active TOTP factor — step-up (the conditional `P4-TSK-007` pattern, ADR-0056). *(This read as unconditional step-up until the Phase 6 review, `P6-DOC-001`.)*
- **Operations:** `finapp.merchant.fee.assessed` (a count of assessments, never an amount), `finapp.merchant.payout` (acting judgements, by outcome), `finapp.merchant.payout.unknown.active` and `.age` (every unknown payout and every dispatch past the sweep's bound — the stuck-payout alert) and `finapp.merchant.destination.pending` (open destination changes). Not built: fee accrual as an amount, per-merchant error rates, and the chargeback ratio — chargebacks are Phase 7's. *(Corrected at the Phase 6 review, `P6-DOC-001`, to the series `P6-TSK-011` and `P6-TSK-013` built.)*
- **Composes, never orchestrates** (`P6-TSK-005`): `MerchantSettlement` returns ADR-0050 §3's four journal lines and announces `FeeAssessed`, on the capture's own connection, through the `CaptureComposition` port `app` wires. `merchant` knows an intent only as a `UUID` it was handed and cannot see `payments`; `payments` cannot see `merchant`. The join is the composition root's, the `JdbcPaymentParticipants` shape. **The refund's seam sits beside it** (`P6-TSK-014`): `MerchantBoundRefundComposition` in `app` implements `payments`' `RefundComposition` by asking `MerchantSettlement.refund` for the lines — announcing `FeeReturned` when a fee comes back — and, since `P6-TSK-015`, for the net the dispatch holds (ADR-0054); a payment with no fee pin, a wallet top-up, posts Phase 5's two lines unchanged. *(Added at the Phase 6 review, `P6-DOC-001`.)*

### `checkout` — Phase 6
- **Responsibility:** the customer-facing purchase experience and the order it produces.
- **Owns:** Checkout Session, Order.
- **Transaction:** own writes, in the transactions `app` composes (below). Payment execution belongs to `payments`.
- **Consistency:** strong. Sessions expire; expiry is a domain event, not a side effect of a cleanup job.
- **APIs:** session create, read and abandon (the merchant's key; the abandonment reasoned), and the customer's confirmation (`POST /v1/checkout/sessions/confirmation` — the customer's own session, the session token in the body). There is no expire route — the sweeper expires — and no completion callback: completion runs in the capture's transaction. *(This read "session create/retrieve/expire, hosted-checkout completion callback" until the Phase 6 review, `P6-DOC-001`.)*
- **Events:** `CheckoutSessionExpired`, `OrderPaid`. *(`CheckoutSessionCreated` struck at the Phase 6 review, `P6-DOC-001`: never built, and no task owns it; a session's creation is audited, `checkout.CheckoutSessionCreated`.)*
- **Failure:** a payment completing after session expiry is handled deterministically, never dropped; duplicate order creation produces one order.
- **Security:** session tokens are unguessable and single-purpose; no merchant may read another's sessions.
- **Operations:** `finapp.checkout.session` by outcome — completed, completed-late, expired, abandoned, so conversion, abandonment, expiry and late completion are one counted series — and `finapp.checkout.conversion.age`, split by the same outcome (`P6-TSK-008`, `P6-TSK-013`). *(Named at the Phase 6 review, `P6-DOC-001`.)*
- **Depends on `platform` only** (`CheckoutModuleIsolationTest`, ADR-0053): it cannot see `merchant` or `payments`, and composes with them only through `app` — `CheckoutService` and `CheckoutSessions` open the payment intent in the confirmation's transaction, and the capture's composition seam completes the session and creates its order inside the capture's own. *(Added at the Phase 6 review, `P6-DOC-001`.)*

### `settlement` — Phase 8
- **Responsibility:** external evidence that money moved — each counterparty's settlement report and the settlement bank's statements — received whole and authenticated, retained encrypted, normalised into canonical lines, and recognised once. Recognition posts only what the platform had not already recorded: a report's fees, a statement's cash. It judges nothing: which of our operations a line settles is `reconciliation`'s question.
- **Owns:** Settlement Source, Settlement File, Refused Delivery, Settlement Batch, Settlement Line, Ingestion Error, Pull Permit.
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0064, ADR-0065 and ADR-0066; the module's boundary and schema floor exist since `P8-TSK-001` — no build edge to `reconciliation` in either direction, `REVOKE ALL FROM PUBLIC`, `USAGE` alone; the source register, the encrypted file store and the door screen since `P8-TSK-002`; the upload door, attestation and the audited evidence reads since `P8-TSK-003`; the `SettlementFormat` SPI, `SIM_PSP_CSV` v1's field-class screen, the parse leg of `SettlementIntakeSchedule`, the canonical batch with its lines, typed references and folded totals (`V003`, the live-batch unique written whole) and the decline since `P8-TSK-008`; the accept leg with the gapless sequence, the acceptance facts (`V004`), hop 1's recognition and the `AcceptedBatchIntake` handover since `P8-TSK-009`; the bank statement (`SIM_STATEMENT_TAGGED` v1, its continuity facts and the live statement-sequence unique in `V005`), attribution at parse through the compiled register, hop 2's recognition (`BankRecognition`) with the statement's place in its chain handed to the intake, and the single-poster rule on `CASH_AT_BANK` since `P8-TSK-016`; `SIM_SCHEME_JSON` v1 (`V006`), the scheme's fees at hop 1 and the cycle token carried on the accepted batch since `P8-TSK-017`; `SIM_PAYOUT_CSV` v1 (`V007`) and the payout provider's fees at hop 1 since `P8-TSK-018`; the pull since `P8-TSK-021` (`V008`, below); the readmission of a file our own validation wrongly refused and the re-parse verification since `P8-TSK-022` (`V009`, the readmission rule); settlement's half of a batch repudiation — the batch's `ACCEPTED → REPUDIATED` edge, its history, event and audit, the file retained byte-identical — since `P8-TSK-023` (`V010`); and the accept-stage series through its observers since `P8-TSK-024`. Every statement here describes the module as built (corrected 2026-10-01, `P8-DOC-001`: this read "and every other statement here is the decided design, corrected by the tasks that build it", and stopped at `P8-TSK-018`))*: a file's encrypted chunks, receipts and history, a batch's control totals and a line's typed references are parts of their aggregates; the source register is compiled descriptors plus a `settlement.source` identity row holding code, kind, status and `next_sequence` and **no position column**. *This entry was rewritten by the transition: it listed the Expectation among this module's state until ADR-0064 moved it to `reconciliation`, named `SettlementBatchIngested` and `SettlementExpectationUnmet` as its events, and read "retained verbatim" where ADR-0066 now refuses, at the door, a delivery bearing card-number or bank-identifier shapes.* *(`P8-TSK-021` built the pull: the `SettlementReportCollector` SPI and `SettlementPull` — the permit in its own transaction, the fetch holding no connection, the ONE door as `PULL`, accepted unattested — `pull_permit` (`V008`), the pure `ExpectedArrivals` derivation, and the operator's fetch-now route; `app` composes the four HTTP collectors, each over its own confined credential, the leaderless `SettlementPullSchedule` and the silence and pull-failure meters.)*
- **Transaction:** own; each step one local transaction in its own schema. Receiving a file writes the file row, its encrypted chunks, a receipt, the idempotency record and the audit together; a refused delivery writes its metadata row and audit and nothing else; attestation and decline take the file row's lock; a parse writes lines, references, batch and totals, or the ingestion errors and `REJECTED`; acceptance stamps the gapless `source_sequence` and `accepted_on`, moves the batch to `ACCEPTED`, hands the run, the items and the `REMITTANCE` expectation to `reconciliation` through `AcceptedBatchIntake` on the same connection, and requests the recognition posting of `ledger` keyed `settlement-batch:<batchId>` and dated from the row — since `P8-TSK-009` the posting PRECEDES the accepting `UPDATE` (the honesty `CHECK` wants the entry id in that statement) and stays the last CONTENDED write, the rows after it the transaction's own claims. A bank statement's acceptance (`P8-TSK-016`) reads the accepted neighbours of its sequence under the same source row lock, hands the intake no remittance but its continuity, and calls the intake again AFTER the posting (`recognised`) for the unattributed lines' suspense items that carry the entry id. A pull holds no connection across the call (ADR-0046). There is no partial ingestion: a crash rolls a step back whole and any instance re-claims it.
- **Consistency:** strong for received evidence and for recognition. A batch is recognised at most once per (source, external batch reference, currency) among live batches, from its own stored dates, so a retry, a crash or a later-day replay converges (`INV-SET-04`); no line drives matching or posting unless the whole file is valid and authenticated (`INV-SET-07`). External settlement is inherently late relative to internal completion (`INV-SET-01`): the completion posts at once, the counterparty reports days later, and the bank's cash follows (ADR-0065).
- **APIs:** operator routes under `/v1/operator/settlement/…`, each with its `RoutePermissionRegisterTest` row: upload (`202` with `duplicateOf` when the bytes are already held; keyed per principal and content-addressed), attestation and decline, fetch-now, reads of sources, files, refused deliveries and batches, reasoned content reads, readmission of a file our own validation wrongly rejected (or a declined one, or a conflicting batch's whose conflict is gone; keyed per principal, `P8-TSK-022`), and the re-parse verification under the recorded format version. `SETTLEMENT_INGEST` introduces and attests evidence, `RECONCILIATION_INVESTIGATE` reads and verifies it, `RECONCILIATION_ADMINISTER` readmits. Nothing customer- or merchant-facing.
- **Events:** `SettlementFileRejected`, `SettlementBatchAccepted` (replaces the planned `SettlementBatchIngested`: a batch is recognised once, at acceptance, not at ingestion) and `SettlementBatchRepudiated` — identifiers, enums and counts only, never an amount, a reference value or a file byte.
- **Failure:** our own failure never rejects evidence — a parser exception leaves the file `RECEIVED`, backed off and visible as a stuck file; the same bytes delivered again on any channel append a `DUPLICATE` receipt and change nothing; different bytes declaring a live batch or an accepted statement sequence are `REJECTED(CONFLICTING_BATCH)`, retained and alerted, never applied; a malformed line, a control-total mismatch, an unknown currency or a scale mismatch rejects the whole file, so a partially corrupt file fails the batch rather than importing half; an unknown well-formed line type becomes `OTHER_IN` or `OTHER_OUT`, never dropped; a lost, truncated, garbage or not-ready pull is re-pulled on its permit, and a truncated body fails its trailer; late and earlier-dated files are accepted in arrival order (`INV-SET-03`); a file that never arrives shows as source silence and as expectations ageing in `reconciliation`.
- **Security:** a delivery is screened in memory before anything is stored, by field class — reference fields by their shapes, declared free text for Luhn-valid card numbers and IBAN, account or alias shapes — and a dirty one is refused with metadata only: `INV-RAIL-03` and `INV-PAY-02` take precedence over `INV-HIST-02` for it (ADR-0066). An accepted file is retained verbatim as AES-256-GCM chunks under `FINAPP_SETTLEMENT_FILE_KEY`, the AAD binding file, source, checksum and position, its SHA-256 verified on every read; content is read only through a reasoned content read, audited per access (`INV-REC-10`). An upload is inert until a second person holding `SETTLEMENT_INGEST` attests it; a pull runs over its source's own confined credential (`INV-SET-07`), to a source URL `ProviderTransportGuard` admits — `https` or `sftp` off loopback, startup refused otherwise, the guard the Phase 7 → 8 transition's repair gave every provider URL, extended to the pull sources by `P8-TSK-021` (ADR-0066 §1); as built every pull is HTTP, because `app`'s `HttpSettlementReportCollector` refuses any non-HTTP scheme at construction, so an `sftp` source URL the guard admits fails startup rather than a pull. Evidence tables are `SELECT, INSERT` only, append-only for every writer; no `DELETE` anywhere in the schema.
- **Operations:** `finapp.settlement.file.received`, `finapp.settlement.delivery.refused` (alert), `finapp.settlement.file.rejected`, `finapp.settlement.file.pending` and `.file.age` (alert), `finapp.settlement.batch.accepted`, `finapp.settlement.ingestion.latency`, `finapp.settlement.source.silence` (alert) and `finapp.settlement.pull.failure` — counts, ages and verdicts, never an amount (ADR-0072).
- **Providers:** the four simulated sources' formats behind the pure `SettlementFormat` SPI (`screen` and `parse`; no I/O, no clock, no database), each version frozen by golden files, their vocabulary confined to `com.finapp.settlement.format.<format>` (`SettlementVocabularyIsConfinedTest`, `INV-PAY-03`'s discipline); pull clients are `app`'s adapters of `SettlementReportCollector` (ADR-0008).
- **Sources are composed, never named:** `app`'s `SettlementBeans` gives each rail declaring `settlement() != NONE` exactly one source whose position is read from `PaymentRails.capabilities(rail).clearingPurpose()`, the payout source reads `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE`, and the bank source's position is `CASH_AT_BANK`; `EverySettlingPositionHasASource` fails the build on an uncovered settling position (`INV-SET-05`, `INV-RAIL-01`).
- **Ports:** declares `SettlementFileStore` (the store behind which object storage would be an adapter change, ADR-0066), `SettlementBatchStore` and `PullPermitStore` (each implemented by the module's own JDBC store); `AcceptedBatchIntake` (implemented in `app` by `ReconciliationIntake`); the `SettlementReportCollector` SPI (implemented in `app` by `HttpSettlementReportCollector`, one per configured source, `P8-TSK-021`); and the observers `ReceptionOutcomeObserver`, `IntakeOutcomeObserver` and `PullOutcomeObserver` (implemented in `app` by `CommittedReceptionOutcomes`, `CommittedIntakeOutcomes` and `SettlementPullMetrics`). Its entry `BatchRepudiation` — the batch's edge, history, event and audit, on the caller's connection — is what `app`'s `ComposedBatchRepudiations` drives to implement `reconciliation`'s `SettlementBatchRepudiations` (`P8-TSK-023`). It requests postings of `ledger` through `PostingService` and requests no reversal: a repudiation's reversal of the recognition is `reconciliation`'s, through `ReversalService`. *(Corrected 2026-10-01, `P8-DOC-001`: this read "and its repudiation reversal through `ReversalService`, reached through the port `P8-TSK-023` names", and named two ports.)*
- **Depends on `ledger`, `platform` and `sharedkernel` only** (`SettlementModuleIsolationTest`): no edge to `reconciliation`, `payments` or `merchant` (ADR-0064).
- **Invariants:** `INV-SET-01`, `INV-SET-04`…`-07`, `INV-HIST-02`, `INV-IDEM-02`, `INV-REC-10`, `INV-PAY-02`, `INV-PAY-03`, `INV-RAIL-03`, `INV-RAIL-04`.
- **Hard rule:** only recognising an accepted bank statement posts `CASH_AT_BANK` — `BatchAcceptance` is the one production class that names the purpose (`INV-SET-06`, `CashAtBankHasOnePosterTest` with a planted poster in each spelling, `PositionProof` its one reader). A repudiated statement's cash moves only as the ledger's reversal of that recognition entry, posted by `reconciliation.BatchRepudiations` through `ReversalService`, which mirrors the entry's own lines and names no purpose *(corrected 2026-10-01, `P8-DOC-001`: this read "or repudiating one", with a repudiation poster to arrive with `P8-TSK-023`)*; and the module names no `*_CLEARING` purpose — positions arrive as values from the composed register (`INV-RAIL-04`).

### `reconciliation` — Phase 8
- **Responsibility:** proving internal truth agrees with external reality, and handling disagreement without erasing it: the settlement expectation every externally settling completion opens, the deterministic comparison of each counterparty's evidence against those expectations, the break for every difference nothing explains, the suspense that holds unexplained value under an owning break, and the controlled resolution of each break.
- **Owns:** Settlement Expectation, Expectation Key, Reference Alias, Reconciliation Batch, External Item, Match Decision, Match Allocation, Matching Rule Set, Match Rule, Tolerance, Provider Fee Schedule, Severity Threshold, Reconciliation Break, Investigation, Resolution, Suspense Item, Park, Run Replay.
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0064 and ADR-0067…0072; the module's boundary and schema floor exist since `P8-TSK-001` — no build edge to `settlement` in either direction, `REVOKE ALL FROM PUBLIC`, `USAGE` alone, no `DELETE` grantable by default anywhere; the expectation register, its keys and aliases, and rule set v1 seeded `ACTIVE` per source since `P8-TSK-004` (V002), opened by every externally settling completion — the card capture, the card refund and the ARN alias (`P8-TSK-004`), the dispute stages, the push execution, withdrawal and return, the parking and the merchant payout (`P8-TSK-005`, each proven by the expectation-opener register) — through `payments`' and `merchant`'s ports and `app`'s one recorder —, and the run (kind `BATCH`, rule set pinned), the `PENDING` external items with their typed keys and the `REMITTANCE` expectation born in the acceptance transaction through `settlement`'s `AcceptedBatchIntake` since `P8-TSK-009` (`V003`, both machines stated whole); the fourteen-type break register with its computed, forward-only severity, the owned suspense items with park, unpark and the release primitive, the `InternalReferenceLookup` port `app` implements over payments' and merchant's public read stores, and the suspense proof and gauges since `P8-TSK-010` (`V004`, no `DELETE` for any writer); the matcher's ordered allocation with decision snapshots and its explanation doors since `P8-TSK-011` (`V005`); the fee check, the counterparty corrections and the platform's `EVIDENCED` resolution since `P8-TSK-012` (`V006`); grace, rematch, ageing, escalation and late evidence since `P8-TSK-013`; the investigator's desk — break reads, the case file, the trace, expectation reads and the settlement-status trail — since `P8-TSK-014`; and the person's resolution machine — the template-bound kinds under four-eyes, proposed, approved, rejected and withdrawn through `RECONCILIATION_RESOLVE`'s four doors, the ledger half through `AdjustmentService`'s owned door, `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` posted by nothing else — since `P8-TSK-015` (`V007`, ledger `V017`); the bank item — attributed, in its attributed source's position and key scope — with `REMITTANCE_REF` and `GROUP_BY_VALUE_DATE` matching, the remittance difference, the unattributed lines' owned suspense, statement continuity (`StatementChain`, a gap closed `EVIDENCED` naming its filling statement) and the cash proof `app` computes, since `P8-TSK-016` (`V008`, ledger `V018`); the scheme's cycle on the run and compared in `decide`, a return's learned cycle, a fee's original reached by its line type's key, and the lookup's scope answered with a rail by `app`, since `P8-TSK-017` (`V009`); the operation-anchored rule read in `resolve`, the returned payout typed `RETURN_NOT_APPLICABLE` at grace, and the payout fee terms completed in rule set v1, since `P8-TSK-018` (`V010`) *(the clauses from `P8-TSK-011` on added by `P8-TSK-014`'s record: this paragraph had stopped at `P8-TSK-010` through three gates)*; the keyless `PAYOUT_RETURN` expectation opened inside `merchant`'s application of a return, reached by the rematch leg's operation-anchored clause, and the `WaitingPayoutReturns` read `app`'s return worker walks, since `P8-TSK-019` (no reconciliation migration; merchant `V008`); every parking's CREDIT suspense item and owning break born in the parking's own transaction through `ParkedConfirmations`, and Phase 7's parkings adopted by the backfill, since `P8-TSK-020` (`V011`); the rule set's machine — proposed, activated four-eyes, rejected, retired by its successor, with its history — the `REPROCESS` run and its leg, the requeue, the run replay, the decision snapshot completed for replay, and the rematch leg deciding under the source's active version, since `P8-TSK-022` (`V012`); the batch repudiation — `REPUDIATE_BATCH` under four-eyes, a counter-allocation per allocation, the batch-subject resolution and its `repudiation_closure` rows, the `REPUDIATION` suspense origin, the recognition reversed through `ReversalService` — since `P8-TSK-023` (`V013`); the `ReconciliationTelemetry` and `ReplayObserver` ports with `app`'s meters and the four audited operator reports since `P8-TSK-024`; the zero-value acknowledgement one person's only on a timing detector's `TIMING_DIFFERENCE` since `P8-TST-002` (`V014`); and, at the Phase 8 review (`P8-DOC-001`), a break that becomes `RESOLVED` only when, at commit, a `RESOLVED` `break_event` of it names an `APPROVED` resolution through the new `resolution_id`, for every writer (`V015`, an insert trigger and a deferred constraint trigger; that the named resolution is the break's own stays the domain's, recorded debt for Phase 15), and `RECOGNISE_GAIN`'s minimum age read from the rule set the owning break pins rather than the source's active one. Every statement here describes the module as built (corrected 2026-10-01, `P8-DOC-001`: this read "and every other statement here is the decided design, corrected by the tasks that build it", and stopped at `P8-TSK-018`))*: a Match is a Match Decision, with its stored candidate snapshot — for a correction, also the parked originals it judged (`match_parked_original`, `V012`) — together with the Match Allocations it produced; Match Rule, Tolerance, Provider Fee Schedule, Severity Threshold and the dating lags (`rule_set_lag`, `V002`) are members of a versioned Matching Rule Set, one `ACTIVE` per source, whose history (`rule_set_event`, `V012`) is part of the aggregate; a batch-subject Resolution carries one `repudiation_closure` row per break it closed (`V013`); Investigation is the break's case file — assignment, append-only notes, evidence links, reclassification — with no machine of its own; an External Item is reconciliation's working copy of one immutable `settlement` line, carrying the contended disposition. *(Match Allocation and Matching Rule Set are named so here, not Allocation and Rule Set, because `lending`'s and `risk`'s `Owns:` lines already name different states so; the tables are `reconciliation.allocation` and `reconciliation.rule_set`.)* *This entry was rewritten by the transition: it owned Match, Match Rule (versioned) and Tolerance (versioned) and not the Settlement Expectation, which ADR-0064 moved here from `settlement`; its events listed `AdjustmentPosted`, which collided with the audit action `ledger.AdjustmentPosted`; and its operations named "unmatched value" and "suspense balance", which ADR-0072 makes audited operator reports rather than series.*
- **Transaction:** own, in its own schema; the chunk is the unit — decisions, candidates, allocations, item and expectation transitions, breaks, suspense items and the run cursor in one transaction, parks and unparks last. Four seams join another module's transaction, each justified by ADR-0064 §6, listed in this document's §6, and all four built: an expectation is opened **inside the completing transaction** of `payments` or `merchant` (ADR-0067) — and since `P8-TSK-020` the same port also opens a parking's suspense item and its owning break in the parking's transaction (`ReconciliationExpectationRecorder` → `ParkedConfirmations`, arbitrated by `UNIQUE (origin_ref)`); acceptance hands its batch over inside `settlement`'s acceptance transaction; a payout return's expectation is opened inside `merchant`'s application of it (ADR-0073); and a batch repudiation's approval reverses the recognition itself, through `ledger.ReversalService`, and has `settlement` write only the batch's `ACCEPTED → REPUDIATED`, its history, event and audit on the approval's connection, through the port this module declares, `SettlementBatchRepudiations` (ADR-0064 §3; `P8-TSK-023`) *(the fourth added by the transition's consistency review, B5; corrected 2026-10-01, `P8-DOC-001`: this had `settlement` write the reversal)*. A resolution and its compensating ledger posting commit together, through `ledger.AdjustmentService` with origin `RECONCILIATION` and a closed reason code (ADR-0071).
- **Consistency:** strong, in one schema. An allocation never exceeds either side (`INV-REC-07`: `CHECK`s, deferred Σ triggers and uniques); every reconciled position equals the signed remainders of its open expectations less its unallocated, unparked items, at every commit (`INV-REC-06`). Matching is deterministic in the honest sense: a decision is a pure function of its stored candidate snapshot and its pinned rule set, so the same stored inputs always produce the same matches and replay is exact (`INV-REC-04`); which candidates a decision saw depends on what had been recorded when it ran, which is why the snapshot is stored.
- **APIs:** operator routes under `/v1/operator/reconciliation/…` and `/v1/operator/reports/reconciliation/…`, each with its `RoutePermissionRegisterTest` row, lists bounded at 100: expectations and the settlement-status trail; runs, requeue and replay; decision and allocation explanations; reprocessing and the opening-position backfill; breaks — list, detail, trace, assignment, notes, evidence links, reclassification; resolution proposal, approval, rejection and withdrawal; batch repudiation; rule sets, activated four-eyes; the positions, suspense, unmatched, summary and provider-costs reports, each read audited. `RECONCILIATION_INVESTIGATE` reads and investigates, `RECONCILIATION_RESOLVE` resolves, `RECONCILIATION_ADMINISTER` decides what counts as a match. All privileged and operational.
- **Events:** `ReconciliationRunCompleted` (replaces the planned per-record `SettlementMatched`), `SettlementExpectationSettled`, `SettlementExpectationOverdue` (replaces the planned `settlement.SettlementExpectationUnmet`: the expectation moved, so its producer moved), `ReconciliationBreakRaised`, `BreakInvestigationStarted`, `BreakResolved` — identifiers, enums and counts only. The planned `AdjustmentPosted` is dropped: `BreakResolved.journalEntryId` together with `ledger.JournalEntryPosted` already carries the fact. The module consumes nothing; no correctness rests on an event.
- **Failure:** a matching job that crashes mid-batch resumes from its committed cursor on any instance, in the same claimant order, without duplicate or lost matches; a poisoned item is contained — an `ERRORED` decision, its remainder parked, a `PROCESSING_ERROR` break — and the chunk continues; a poisoned run goes `BLOCKED` with a CRITICAL break and holds its source visibly until requeued; every unallocated remainder either waits out a grace window judged on the database clock or parks with a break in its own transaction, and a run never completes with an item `PENDING` (`INV-REC-02`); a late record, external or internal, is allocated like any other and resolves its break `EVIDENCED` (`INV-SET-03`); a reference collision at opening is recorded and raised as a break, never a failed payment; two operators resolving one break produce one resolution, and an approval that finds the residual moved is refused `reconciliation.ResolutionStale`.
- **Security:** resolution is the most sensitive non-administrative privilege in the platform — four-eyes for every person's resolution but the zero-value `ACKNOWLEDGE` of a `TIMING_DIFFERENCE` raised by a timing detector (cause `LATE_MATCH` or `CYCLE_MISMATCH`), refused at the domain, the `resolution` `CHECK`s and `V014`'s trigger keyed on the break's cause, and ledger `V010` *(corrected 2026-10-01, `P8-DOC-001`: this read "four-eyes whenever value is at issue or the resolution posts" — a diverged replay's zero-value acknowledgement is four-eyes, `V014`)*; closed reason codes; lines derived from the subject's remainder, never typed; reconciled positions closed to free adjustments (ADR-0071). Two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` and `RECONCILIATION_CONTROLLER`, so whoever can loosen a tolerance cannot resolve the breaks it would hide. References are `CONFIDENTIAL` and amounts `RESTRICTED-FINANCIAL`; notes and narratives never reach a log, an event or an audit body; the module holds no byte of a file. No `DELETE` anywhere in the schema; decisions, candidates, allocations, notes, evidence links, releases and parks are append-only (`INV-REC-01`).
- **Operations:** `finapp.reconciliation.run.pending`, `.run.age` (alert), `.run.blocked` (alert, must be 0) and `.run.latency`; `.item`, `.item.unmatched` and `.rematch`; `.expectation.open`, `.expectation.overdue` (alert) and `.expectation.overdue.age`; `.break.raised`, `.break.open` and `.break.age` (alert per severity); `.resolution`, `.resolution.latency` and `.adjustment`; `.suspense.open`, `.suspense.age` (alert) and `.suspense.unowned` (must be 0); `.position.proof`, `.line.unattributed` and `.cash.proof` (each must be 0); `.replay`. Counts, ages and verdicts only, under the new tag keys `source` (joining with `P8-TSK-002`) and `severity` (joining with the first severity-tagged series, the break meters of `P8-TSK-024`) *(when each key joins: the transition's consistency review, B6)*; unmatched value, suspense balance and provider costs are audited operator reports, never a series (ADR-0072).
- **Ports:** declares `InternalReferenceLookup` (implemented in `app` by `JdbcInternalReferenceLookup` over `payments`' and `merchant`'s public read stores — for the instant rail `payments.scheme_execution_claim` (`V023`, the Phase 7 → 8 transition's repair), which names exactly one explaining subject per scheme reference, so a scheme line whose reference no claim holds names no completed execution — break typing only, never allocation) and `SettlementBatchRepudiations`, the repudiation seam (implemented in `app` by `ComposedBatchRepudiations` over `settlement`'s `BatchRepudiation`, `P8-TSK-023`); `ExpectationRegister` is the entry `app`'s `ReconciliationExpectationRecorder` calls to implement `payments`' `SettlementExpectations` and `merchant`'s `PayoutSettlementExpectations` — and since `P8-TSK-007` the one path the opening-position backfill adopts history through, so a backfilled row is what the live opener would have written; `ExpectationReadings` is the register's read side for the proofs (`P8-TSK-007`): open remainders for the position proof's `Money` fold, the known `(entry, account)` pairs for the completeness verifier, and the open counts per source — composed in `app`'s `PositionProof` with the ledger's derivation and line reads, one `REPEATABLE READ` snapshot, report and never repair. `app`'s `ReconciliationIntake` implements `settlement`'s `AcceptedBatchIntake` through this module's API. It posts through `ledger`'s `PostingService` (parks, unparks, offsets, keyed `recon-suspense:<parkId>` — a key freshly minted per posting, so it converges nothing: a park's once-ness rests on the item's conditional edge and `UNIQUE (external_item_id)` on the suspense item, an unpark's on the suspense item's locked unreleased remainder), `ReversalService` (a repudiated batch's recognition, keyed `settlement-batch:<batchId>` in the `ledger.reverse` scope, `P8-TSK-023`) and `AdjustmentService` (`proposeOwned`, `approveOwned`, `rejectOwned` — called by `ResolutionMachine` and, for a pending proposal evidence overtakes, by the evidence writer, since `P8-TSK-015`); `app`'s `BreakResolutionDesk` is the resolution doors' one-transaction wrapper, and the attribution reads are the ledger's own (`PositionBreakdown` and `StatementDerivation` flag a `RECONCILIATION`-origin entry's lines), interpreted by `merchant`'s `MerchantPayable` and `app`'s statement view. Since `P8-TSK-014` it also declares `EvidenceTargets` (does an evidence link's settlement, ledger or payments target exist) and `TraceEvidence` (a batch's file and recognition entry, a line's batch, the provider statements retained about an operation), both implemented in `app` by `ComposedCaseFileEvidence` over those modules' public read stores — lock-free, identifiers and metadata only, never a byte of content. `ParkedConfirmations` is the entry `app`'s recorder calls for a parking's owner (`P8-TSK-020`); `WaitingPayoutReturns` is the module's public read of the payout returns awaiting their worker, implemented here by `JdbcWaitingPayoutReturns` and read by `app`'s return worker (`P8-TSK-019`); and the module declares two telemetry ports, `ReconciliationTelemetry` (implemented in `app` by `ReconciliationOutcomeMeters`, `P8-TSK-024`) and `ReplayObserver` (by `ReconciliationReplayMeters`, `P8-TSK-022`), whose implementations decide nothing. *(The ports from `P8-TSK-019` on named at the Phase 8 review, `P8-DOC-001`.)*
- **Depends on `ledger`, `platform` and `sharedkernel` only** (`ReconciliationModuleIsolationTest`): no edge to `settlement`, `payments` or `merchant`; other modules' facts are copied when their operation completes, never joined (ADR-0064 §4).
- **Invariants:** `INV-SET-02`, `INV-SET-03`, `INV-REC-01`…`-09`, `INV-HIST-04`, `INV-REV-04`, `INV-AUD-04`.
- **Hard rule:** no code path deletes a break (`INV-REC-01`, `INV-REC-02`) — no `DELETE` grant and a refusing trigger. Value enters `SUSPENSE_UNMATCHED` only in the transaction that records its owning break (`INV-REC-09`); no tolerance exists on an amount already in a position (`INV-REC-08`); the module names no `*_CLEARING` purpose.

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
| Merchant, Merchant API Key, Fee Schedule (versions, assignment), Payment Fee Pin, Payout Destination, Merchant Payout, Payout Return | `merchant` | Merchant **payable** is derived from `ledger` postings, never stored — the `MERCHANT_PAYABLE` account is `ledger`'s, as are the operational `FEE_REVENUE` and `PAYOUT_CLEARING` *(corrected at the Phase 6 review, `P6-DOC-001`)* |
| Checkout Session, Order | `checkout` | — |
| Settlement Source, File, Refused Delivery, Batch, Line, Ingestion Error, Pull Permit | `settlement` | Each accepted batch's recognition entry is `ledger`'s, requested at acceptance; a source's position is read from its counterparty's declaration, never stored *(the Expectation moved to `reconciliation` at the Phase 7 → 8 transition, ADR-0064)* |
| Settlement Expectation, Reconciliation Batch, External Item, Match Decision, Match Allocation, Matching Rule Set, Break, Investigation, Resolution, Suspense Item, Park | `reconciliation` | An expectation copies its completion's immutable facts and an External Item copies an immutable `settlement` line, each joined by identifier; the `SUSPENSE_UNMATCHED` line beneath a Suspense Item and the adjustment proposal beneath a posting Resolution are `ledger`'s |
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

**What enforces what.** Gradle enforces dependency direction structurally; the ArchUnit
suites in `app` enforce the rest on every build. Every claim of mechanical enforcement below
names the rule that makes it true, and `ArchitectureRulesAreDocumentedTest` fails the build if
that naming and the rule set stop agreeing. Anything marked *(review)* has no mechanical check.

- Each module owns a package root; internals are not accessible across modules.
  *(ArchUnit: `moduleInternalsArePrivateToTheirModule`)*
- Every production class sits under `com.finapp.<module>`, never directly in `com.finapp`.
  The rules are scoped by the module a class belongs to, so a class with no module would be
  silently exempt from all of them. *(ArchUnit: `productionClassesLiveInAModulePackage`)*
- A module exposes a published interface (commands, queries) and integration events. *(review)*
- No cross-module entity or ORM-relationship references. References are typed
  identifiers. *(ArchUnit: `entitiesAreNotReferencedAcrossModules`)*
- Dependency direction is acyclic and enforced. *(Gradle, plus ArchUnit as defence in
  depth: `sharedkernelDependsOnNoOtherModule`, `platformDependsOnlyOnSharedkernel`,
  `nothingDependsOnApp`)*
- No framework reaches `sharedkernel` — no Spring, no JPA, no Hibernate, no
  transaction annotations. This is what keeps the financial kernel unit-testable
  without a container, and stops a persistence concern from shaping a monetary type.
  `SharedKernelIsolationTest` asserts the same thing one level lower, at the
  classpath. *(ArchUnit: `sharedkernelIsFrameworkFree`)*
- Every module applies the `java-library` plugin and uses the `api`/`implementation`
  distinction deliberately. `implementation` keeps a dependency off consumers' compile
  classpaths so a module cannot leak its internals downstream by accident; `api` makes
  exposure a reviewed choice. Where process isolation is absent, controlling transitive
  exposure is one of the few boundary mechanisms that genuinely holds. `platform` exposes
  `sharedkernel` via `api` because kernel value types appear in its own signatures.

### Monetary type boundary

`INV-MON-01` — no binary floating point on the path of a monetary value — is enforced by
`NoFloatingPointMoneyRulesTest` on every build (`P0-TSK-008`). This implements the rule
ADR-0006 lists under *Additional enforced rules*; the implemented scope is deliberately wider
than the "monetary code paths" phrasing there, for the reason below.

**Scope is default-deny over every production class**, not a list of financial packages. A
list of financial packages is a denylist, and a denylist fails in the case that matters: a new
package is unprotected by default and nothing reports the omission. Since this repository is
financial infrastructure, a package that cannot touch money is the exception. Exemptions live
in one named set in the rule (`EXEMPT_CLASSES`), so each one is a visible diff. **It is not
empty:** every entry publishes a count, an age in seconds or a verdict — never an amount —
through the `double` a Micrometer gauge or counter imposes at the registry boundary. They are the
gauge classes `OutboxMetrics`, `IdentityMetrics`, `KycMetrics`, `LedgerMetrics`,
`PaymentMetrics`, and Phase 6's `MerchantMetrics` and `MerchantPayoutMetrics`, each with its
cached readings, and the counters of `OutboxRelaySchedule` and `InboxConsumers$Loop`. *(This said
"currently empty" until the Phase 6 review, `P6-DOC-001`.)* Phases 7 and 8 added `PayInMetrics`,
`NegativePositionMetrics`, `DisputeDeadlineMetrics`, `DisputeStageMetrics`,
`StuckOperationMetrics`, `SettlementFileMetrics`, `ReconciliationMetrics`, `SettlementPullMetrics`,
`BreakMetrics` and the counters of `ReconciliationOutcomeMeters` (`P8-TSK-024`), on the same argument.

Four surfaces are checked:

- fields, including `double[]` and generic arguments such as `List<Double>`.
  *(ArchUnit: `noFieldHoldsAFloatingPointValue`)*
- method and constructor parameters and return types.
  *(ArchUnit: `noSignatureCarriesAFloatingPointValue`)*
- calls to any method or constructor that takes or returns a floating-point value — this is
  what catches `new BigDecimal(0.1)`, `BigDecimal::doubleValue` and `ResultSet::getDouble`,
  none of which appear in any declaration of ours.
  *(ArchUnit: `noCallReachesAFloatingPointApi`)*
- reads and writes of a floating-point field.
  *(ArchUnit: `noFloatingPointFieldIsAccessed`)*

**The exemptions stay off money** (ADR-0072 point 1, `P8-TSK-024`). An exempt class publishes
counts, ages and verdicts, so it may not depend on `Money` or `MoneyColumns` - the types an
amount travels in. A `CurrencyCode` stays permitted, since a currency is a tag value. The
known edge: a store method returning a monetary `long` passes, which is why each exemption's
argument names what it counts. *(ArchUnit: `noExemptClassDependsOnMoney`)*

**Coverage guard.** Both rule suites derive the set of modules they must have analysed from
the classpath (`ProductionModules`), because an ArchUnit rule is vacuously satisfied over
classes it never imported. A guard that names what it expects to see by hand does not notice a
module dropping out of the sweep — proven during the `P0-TSK-008` review, where narrowing the
sweep left every floating-point rule green while a `double` planted in `platform` went
undetected and the build passed.
*(ArchUnit: `everyModuleWithProductionCodeIsAnalysed`, one per rule suite)*

**Known limit.** A `double` local computed only from compile-time constants and narrowed by a
cast is not detectable: a cast is a bytecode instruction rather than a declaration or access,
and javac inlines `static final double` literals so even `Math.PI` leaves no field access
behind. Such a value is inert unless it is stored, returned, passed or derived from something
non-constant, all of which are caught — but the limit is recorded because a rule believed to
be total is more dangerous than one whose edge is known. *(review)*

### Time boundary

Time is injected, never read from the environment (`P0-TSK-013`, implementing ADR-0006's
*Additional enforced rules*).

**Why a build failure and not a review note.** Accrual, fee assessment, period close, value
dating, hold expiry, settlement ageing and idempotency-key expiry are behaviours whose entire
content is what happens as time passes. A component that reads `Instant.now()` cannot be placed
at a boundary by a test — just before midnight, just after a rate expires, on the last day of a
closing period — and cannot be replayed, so a decision it made yesterday cannot be reproduced
tomorrow (`INV-CRD-01`, `INV-ACC-04`). The defect is not that such code is wrong; it is that
nothing can ever demonstrate whether it is.

Two rules, because there are two different things to control:

- No production class reads the environment directly: no zero-argument `Instant.now()`,
  `LocalDate.now()` or the other `java.time` `now()` methods, no `System.currentTimeMillis()`,
  no `System.nanoTime()`, no `new Date()`. Method *references* count too — `Instant::now` is an
  `invokedynamic` rather than a call, and a rule one syntax away from being bypassed is not
  enforcement. The environment's **zone** is included for the same reason as its clock: which
  *date* an instant falls on depends on the zone it is read in, so
  `instant.atZone(ZoneId.systemDefault())` makes a cut-off, a period boundary or a value date
  depend on how the server happens to be configured. Forbidden everywhere, no exemption.
  *(ArchUnit: `noAmbientTimeIsRead`)*
- Only the composition root constructs a system clock — `Clock.systemUTC()` and friends. A
  system clock has to be built somewhere or nothing can be injected, and `app` is the module
  whose job that is; anywhere else it is ambient time with an abstraction wrapped round it.
  *(ArchUnit: `onlyTheCompositionRootBuildsASystemClock`)*

`Instant.now(clock)`, `LocalDate.now(clock)`, an explicitly-passed `ZoneId`, and the pure
`TemporalAdjusters` are deliberately **allowed** — they take what they need as an argument and
are the idiomatic calls. A rule that forbade them would push people off the correct
API, which is how a well-meant rule makes a codebase worse.

**What no rule can check.** An injected clock still has to be the *right* clock. System time is
not a business date: posting date, value date and system time are three different things and
substituting one for another is a domain error that reads perfectly. See
[`DOMAIN_MODEL.md`](../domain/DOMAIN_MODEL.md) §Time. *(review)*

### Event publication boundary

Nothing publishes to a message broker except the outbox relay (ADR-0005, `INV-EVT-01`).

**Why a rule rather than a convention.** There is no safe moment for domain code to publish
directly. Inside the transaction the broker cannot know whether it will commit, so a rolled-back
fact is announced as though it happened. After the transaction there is a window in which the
process dies having committed a fact nobody will ever hear about. Both failures are silent, both
surface later as a reconciliation break with no explanation attached, and both look like
ordinary code at review. The outbox removes the window by making the fact and its publication
record one commit — and that guarantee lasts exactly as long as nobody takes the shortcut.

- No production class calls, references or holds a broker client — Kafka, AMQP, JMS, SNS/SQS.
  Matched by **package name rather than by type**, because no broker client is on the classpath
  yet and a rule that only worked once someone added the dependency would be missing at the
  moment it is first needed. Method references count: an `invokedynamic` is not a call, and a
  rule one syntax away from being bypassed is not enforcement.
  *(ArchUnit: `nothingPublishesToABrokerDirectly`)*

The exemptions are the two broker **adapter packages** — `platform.outbox`
(`KafkaEventPublisher`, `P2-TSK-001`) and `platform.inbox.kafka` (`KafkaEventReceiver`,
`P2-TSK-002`) — matched exactly, so the inbox parent package where `InboxConsumer` lives stays
forbidden, and each is proven load-bearing in both directions by the rule's own teeth. *(This
paragraph previously said the exemption was "a module" and "still empty after `P0-TSK-020`" —
true when written, stale from the day `P2-TSK-001` narrowed the granularity to a package and
added the first entry, and caught by `P2-TSK-002`'s widening rather than by any guard: the
equivalence test pins rule names, not prose about their exemption sets.)* Each exemption arrived
**with** its adapter — the dependency, the wire format, the config it brings — never in advance,
which keeps the list at exactly the packages that have actually taken the privilege; and the
rule's condition is deliberately broader than its name, because consuming directly past the
inbox is the symmetric defect to publishing past the outbox (`INV-IDEM-04` and `INV-EVT-01`
respectively, each losing its guarantee silently).

### Secrets cannot be held in a field that would print itself

`INV-AUD-02` does not merely forbid credentials in logs; it specifies the enforcement as
**default-deny redaction**. The usual approach is the opposite - annotate the sensitive fields -
and it fails the first time somebody adds a field without thinking about it, which is every time
somebody is in a hurry.

The accident is Java's own. A record generates a `toString()` printing every component, so
`log.info("authenticating {}", credentials)` prints the password with no getter call, no
concatenation, and nothing a reviewer would stop at.

- No production class declares a field, or a no-argument accessor, whose **name** says it holds a
  secret unless the type is `Sensitive<?>`. Accessors are checked as well as fields because a
  serialiser reads accessors: a private `pw` behind a `getPassword()` is invisible to a
  field-only rule and is exactly what Jackson and a record's `toString` reach for.
  *(ArchUnit: `secretsAreWrapped`)*

The vocabulary is **narrow on purpose**, and `key` is not in it. An idempotency key is not a
secret - `API_CONVENTIONS.md` §6 says so, and it is recorded on the audit record deliberately. A
rule that flagged `idempotencyKey` is a rule somebody turns off, and a rule that is off protects
nothing. Matching is on camel-case word boundaries, so `companyName` is not a PAN and `spinLock`
is not a PIN.

- No production class calls `org.slf4j.MDC` except `CorrelationContext`. The MDC takes a
  `String`, so the wrapper above cannot protect it, and the ECS encoder lifts every MDC entry to a
  **top-level field** - `MDC.put("apiToken", token)` publishes it verbatim, queryable, with no
  wrapper anywhere on the path. Confirmed by probe rather than by reasoning: the value appeared in
  the emitted JSON exactly as written. Confining the writes makes the MDC's contents a decision
  made in one place, by the component whose job is deciding what belongs in a log line's context.
  *(ArchUnit: `onlyCorrelationContextWritesTheMdc`)*

- No production class reads `Actor.SYSTEM` except `SecurityContext`. Every audit record needs an
  actor, so every call site that writes one has a parameter to satisfy - and when none has been
  established, the shortest way to make the code compile is the constant that is public, final and
  right there. The result is a record saying the platform did what a person did: nothing fails, it
  looks complete, and `INV-HIST-03` makes it permanent. Claiming the platform acted is therefore a
  deliberate act through `SecurityContext.enterSystem()`, which establishes a scope and says so;
  everything else asks `require()` and is told the truth when nobody was established.
  *(ArchUnit: `onlyTheSecurityContextClaimsTheSystemActor`)*

**What that one does not catch**: a caller writing `new Actor("system", ActorType.SYSTEM)` by hand.
That is forgery rather than a shortcut, and no static rule can tell it from an actor built out of a
real identity - which is the reason actors are constructible at all. The rule removes the easy
path, which is the one people take.

**What it does not catch**, stated so it is not mistaken for total coverage: a secret held only in
a local variable and passed straight to a log call. The rules cover values a type *stores* and the
one context map a log line carries; a transient value has no declaration to inspect. Closing that
needs a logging facade that only accepts declared-safe arguments, which is a larger change than
this task, and is recorded as debt.

### Multi-instance execution

ADR-0014 says every service runs as N concurrent instances and N is never 1. That was a written
rule, and written rules decay — the audit that produced ADR-0014 found a real defect in reviewed
code (`P0-TSK-016`'s lease compared one instance's clock against another's). These rules are the
mechanically detectable half (`P0-TSK-041`, ADR-0024).

- No production method is declared `synchronized`, and no production method enters a monitor —
  that is, no `synchronized` **block** either. A monitor is held inside one JVM, so with N
  instances the invariant it appears to protect is protected in none of them, and the code reads
  as though the race was handled. The block check is **not** an ArchUnit rule: ArchUnit models
  accesses, not instructions, and a block is a `MONITORENTER` with no access flag — verified by
  probe, where the block method reported no modifiers at all. It reads bytecode directly.
  *(ArchUnit: `noMethodIsSynchronized`; bytecode: `NoSingleInstanceAssumptionRulesTest.noSynchronizedBlocks`)*
- No production class uses a process-local lock — `ReentrantLock`, `Semaphore`, `CountDownLatch`,
  `CyclicBarrier` and their neighbours. They coordinate threads within one process and say nothing
  to the other instances. Coordination that must hold across instances belongs in the database: an
  advisory lock, a unique constraint, or a conditional `UPDATE` (`DISTRIBUTED_EXECUTION.md` §5).
  *(ArchUnit: `nothingUsesAProcessLocalLock`)*
- No production class schedules ambiently — `ScheduledExecutorService`, `Timer`, `@Scheduled`.
  Every instance runs the scheduler, so a job with no lease runs N times; a scheduled financial
  process must be idempotent per period (`INV-IDEM-02`) or take an explicit lease, and a bare
  scheduler declares neither. *(ArchUnit: `nothingSchedulesAmbiently`)*
- No production class holds static mutable state: a non-final static field, a static field of a
  mutable type (arrays included — a `final` reference to an array protects nothing), or a mutable
  collection built in a static initialiser. A cache, counter or registry
  in a static field is per-instance, so every replica has a different answer and none is
  authoritative (`CLAUDE.md` rule 12). *(ArchUnit: `noStaticMutableState`)*

**The exemption set is the register**, not this rule's own list: the two `ThreadLocal`s recorded as
non-authoritative in `DISTRIBUTED_EXECUTION.md` §3, named individually rather than by type. A
type-wide exemption for `ThreadLocal` would admit the next one without anyone deciding, and the
register exists to force that decision. Both are proven load-bearing — the same rule with an empty
exemption set fires on both.

**A known gap, stated rather than left to be discovered.** A mutable collection built by a
*factory method* and assigned to an interface-typed static field escapes: the construction is not
in the static initialiser and the field's type is an interface. Closing it would flag the common
and correct pattern of building a local collection and returning an immutable copy.

**What these do not claim.** They do not make a design multi-instance correct; no rule can. They
remove the constructs that *only* mean something in one process, so a claim about coordination
cannot be made silently. The design question — would this still be correct if ten instances ran it
concurrently — stays a review question, and `P0-TST-009` is the test convention for it.

### Persistence boundary

Authoritative writes and aggregate loads use **explicit SQL through `JdbcClient`**. There is no
object-relational mapper (ADR-0033, `P1-TSK-001`).

**Why a build failure and not a convention.** Three of the strongest invariants in the catalogue
are statements about a privilege the application must *not* hold — `INV-HIST-03` (audit
append-only), `INV-HIST-01` (financial history never edited) and `INV-LED-03` (posted entries
immutable), all enforced at `DB-PRIVILEGE` by `finapp_app` holding no `UPDATE` and no `DELETE`
(`V008`, `V009`). A privilege model is worth exactly as much as the guarantee that nothing emits a
statement nobody wrote, and dirty checking emits `UPDATE` on its own initiative at a flush point
decided by code far from the write.

`NoObjectRelationalMapperTest` asserts that no JPA, Hibernate or Spring Data artefact is on the
application's **runtime** classpath, which also catches one arriving transitively behind a starter
— the way it would actually arrive. It is a classpath assertion rather than an ArchUnit rule, so it
is named here in prose, as `SharedKernelIsolationTest` is. *(test:
`NoObjectRelationalMapperTest`)*

**The gap this closed.** Until `P1-TSK-001` this section forbade JPA and Hibernate in
`sharedkernel` only — and `sharedkernel` is not where an ORM would ever be added. `platform`,
`app`, `party` and `identity` were unprotected.

**A carve-out, proven load-bearing.** `hibernate-validator` is Bean Validation and stays: it
arrives with `spring-boot-starter-validation`, which `P0-TSK-025` added to reject requests at the
boundary. The first version of the forbidden list matched `hibernate-` and failed on the real
classpath. The names are therefore the ORM's own artefacts rather than its publisher's prefix, and
a test asserts the carve-out is still needed — a rule that forbids a correct dependency is a rule
somebody turns off.

**The unit of work is a JDBC `Connection`.** The four kernel ports generic over it —
`AuditWriter<T>`, `OutboxWriter<T>`, `InboxRecordStore<T>`, `IdempotencyRecordStore<T>` — keep the
type parameter, which is a deliberate non-change rather than an oversight: removing it is a
refactor of proven Phase 0 code with no correctness benefit.

**Transactions are begun explicitly** — `TransactionTemplate`, not `@Transactional` on service
methods, because `@Transactional` fails *silently* on self-invocation and
`DEFINITION_OF_DONE.md` §1.4 requires a boundary that is deliberate rather than an accident of
annotation placement. *(review)*

### Data boundary
- Schema per module in one PostgreSQL database (ADR-0006).
- **No foreign keys across module schemas.** Referential integrity across contexts is a
  domain concern, enforced at the boundary, not by the database.
- One writer per table. Cross-module reads go through the owning module's API, not by
  querying its tables.

### Transaction boundary
- A transaction never spans a call to an external provider.
- A transaction never spans two modules' authoritative state **except** where a documented
  ADR justifies it — currently registration (`party` plus `identity`, ADR-0029),
  transfer-plus-posting and resolution-plus-adjustment, all three of which are the explicit
  reason for choosing a monolith. Registration is the first of them to exist: `P1-TSK-006`
  creates a Party, a Customer and an Identity in one commit, and because ADR-0029 deliberately
  places **no foreign key** across the schema boundary, that transaction is the only thing
  making the Identity's reference to its Party true. The list was stale before this task —
  it had been written when neither of the other two existed either.
- **Phase 8 adds four such seams, each justified by ADR-0064 §6, all four built** *(planned by
  the Phase 7 → 8 transition; built by `P8-TSK-004`/`-005`, `-009`, `-019` and `-023`)*: a
  completion opening its settlement expectation (`payments` or `merchant`, then
  `reconciliation`; ADR-0067) — and since `P8-TSK-020` a parking opening its suspense item and
  owning break the same way (`ParkedConfirmations`, arbitrated by `UNIQUE (origin_ref)`) —
  acceptance handing its batch to reconciliation (`settlement`, then `reconciliation`), a
  payout return opening its expectation (`merchant`, then `reconciliation`; ADR-0073), and a
  batch repudiation's approval — reconciliation's rows, then `settlement`'s
  `ACCEPTED → REPUDIATED` and its history, then the recognition's reversal, which
  `reconciliation` posts through `ledger.ReversalService`, then `settlement`'s event and audit
  (ADR-0064 §3, `P8-TSK-023`) *(the fourth named by the transition's consistency review, B5:
  this list and ADR-0064 §6's table had said three)*. *(Corrected 2026-10-01, `P8-DOC-001`:
  this read "nothing built yet", and gave the reversal no owner.)* Resolution-plus-adjustment, already
  listed, becomes real with them (`reconciliation`, then `ledger`; ADR-0071). The transaction is
  shared and the tables are not: each module writes only its own schema, through its own API, on
  the caller's connection. Where a seam's transaction posts more than one entry over shared hot
  rows, it pre-locks their union in the balance projection's order before its first posting
  (`PostingService.lockBalancesInOrder`, the rule the transition's repair gave dispute postings;
  `DISTRIBUTED_EXECUTION.md` §3's Phase 8 lock order).
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
| Is `checkout` a module or part of `merchant`? | Its own module — **closed by ADR-0053** (§3, M2); the merge trigger stays as a watchdog | Phase 6 |
| Does a shared case store exist, and who owns it? | No shared store: `kyc` owns Review Task, `risk` owns Case (§5) | Phase 13 |
| Isolation level and locking strategy for concurrent postings | `READ COMMITTED`, postings as inserts, balance-dependent decisions under the account lock — **closed by ADR-0039** | Phase 3 |
| Chart-of-accounts structure and its relation to the Phase 14 GL | A flat account with a typed classification, the GL mapping Phase 14's — **closed by ADR-0040** | Phase 3 |
| Balance projection placement: ledger schema or separate read store | Ledger schema, transactional (ADR-0009) | Phase 3 |

### What this map does and does not enforce

As of `P0-TSK-007`, §6's structural rules are mechanical. `ModuleBoundaryRulesTest` fails the
build if a module reaches into another's internals, references another's persistence
entities, depends upward, or lets a framework into `sharedkernel`. As of `P0-TSK-008`,
`NoFloatingPointMoneyRulesTest` fails the build on any floating point in production
code. Each rule was proven by a deliberate violation rather than assumed to work.

`P0-DOC-002` closes the loop between this document and those rules:
`ArchitectureRulesAreDocumentedTest` fails the build if §6 names a rule that no
longer exists, or if a rule exists that §6 does not name. This document had drifted
from the rules six times by the time that check was written, so the equivalence is
now asserted rather than claimed.

One thing remains on review, and is worth stating plainly rather than letting a
reader assume the diagram is guaranteed throughout:

- **Single ownership of authoritative state.** §5 is checked by comparing the register's
  `Owns:` lines, which catches a *declared* second owner. Nothing detects a module that
  quietly starts writing state another module declares — that needs schema-level privileges
  (`P0-TSK-022`) and, ultimately, review.

The rules only protect modules that follow the package convention in §6. A module whose
internals are not under `com.finapp.<module>.internal` is invisible to the internals rule —
though a class belonging to no module at all is now itself a violation.

Coverage is self-checking: each rule suite carries an
`everyModuleWithProductionCodeIsAnalysed` guard that derives, from the classpath,
every module output holding at least one real class, and fails if any of them was not
imported. Both use the same derivation (`ProductionModules`), because writing a
second guard by hand is not hypothetical: the `P0-TSK-008` review found exactly that,
and a `double` planted in `platform` went undetected while the build passed. Without
these guards the rules would report success for a module dropped from the analysis,
which is the failure mode that makes architecture tests worse than useless.
