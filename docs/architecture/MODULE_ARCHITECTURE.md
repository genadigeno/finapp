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
| 15 | FX | `fx` | 9 | Bounded by the Phase 8 → 9 transition (ADR-0074…0078): pricing, conversion and the cover; no build edge to `crossborder` |
| 16 | Cross-Border Payments | `crossborder` | 9 | Bounded by the Phase 8 → 9 transition (ADR-0079…0081): the payment's business lifecycle; pricing delegated to `fx` and execution to `payments` through `app`'s ports |
| 17 | Credit | `credit` | 10 | Bounded by the Phase 9 → 10 transition (ADR-0084, `Proposed`; planned, nothing built): data, assessment, policy and decision kept apart in one module; depends on `platform` and `sharedkernel` only; moves no money; the risk score is `risk`'s |
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

The entries for modules of Phases 0-9 describe code that exists; the entries for later phases'
modules are the design contract those phases must satisfy, not a description of code. *(This
read "modules from Phase 1 onward do not exist yet" until the Phase 6 review, `P6-DOC-001`,
"Phases 0-6" until the Phase 8 review, `P8-DOC-001`, and "Phases 0-8" until the Phase 9 → 10
transition, 2026-10-07, which also wrote every Phase 10 statement below as planned.)*

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
- **Owns:** no persistent state. Holds `Money`, `CurrencyCode` and `RoundingPolicy` (P0-TSK-009, P0-TSK-010); typed identifiers (P0-TSK-012); correlation and causation *identifiers* (moved down in P0-TSK-018, because the envelope carries them and the shared kernel may not depend upward); and the event envelope (P0-TSK-018). The correlation *context* — the mechanism that carries a flow across threads and writes it to the MDC — stays in `platform`, where its logging dependency belongs. *(Added 2026-10-02 by the Phase 8 → 9 transition: `security.InstrumentShapes`, the platform's one pure screen for card-number and account-identifier shapes in person-written prose (`INV-PAY-02`, `INV-RAIL-03`; the audit's `SEC-03`/`SEC-04`). It sits beside `Sensitive` because the rule is platform-wide and stable — ISO/IEC 7812's Luhn band and ISO 13616's printed form — and both `settlement` and `reconciliation` must apply the identical rule with no build edge between them; each schema carries its PL/pgSQL twin (settlement `V012`, reconciliation `V019`). A function of a string, no state and no I/O, so the kernel stays framework-free.)* *(Since `P9-TSK-002`: `ExchangeRate` - a directed price, precision ≤ 20 and scale ≤ 10, no inversion and no cross rate - and `CountryCode`, both representation primitives per ADR-0074 §§1, 10; `RateColumns.ddl()` in `platform` generates `NUMERIC(20,10)` from the type's constants.)*
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
- **Phase 8** *(planned by the Phase 7 → 8 transition, ADR-0071; the first two permissions and the first role exist since `P8-TSK-003`)*: four permissions — `SETTLEMENT_INGEST` and `RECONCILIATION_INVESTIGATE` (built, `P8-TSK-003`, with the settlement routes that check them), `RECONCILIATION_RESOLVE` (built with `P8-TSK-015`'s resolution doors) and `RECONCILIATION_ADMINISTER` (built with the opening-position backfill's route, `P8-TSK-007`, and the controller's doors of `P8-TSK-022`) *(corrected 2026-10-01, `P8-DOC-001`: this read "each arriving with its first route")* — and two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` (`V017`, built with `P8-TSK-003`, holding the first two; `RECONCILIATION_RESOLVE` joins it with `P8-TSK-015`) and `RECONCILIATION_CONTROLLER` (`V018`); `LEDGER_OPERATOR` is not extended, so the desk that moves money does not reconcile it.
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0075, ADR-0080 and ADR-0081; built by `P9-TSK-007`, `-013`, `-015`, `-016` and `-025`, each permission with its real check sites; read against the code by the exit review, `P9-DOC-001`)*: five permissions — `FX_ADMINISTER` (`P9-TSK-007`) and `CROSSBORDER_ADMINISTER` (`P9-TSK-015`) under the new seventh role `FX_CONTROLLER` (`V019`, `P9-TSK-007`), `FX_INVESTIGATE` joining `RECONCILIATION_OPERATOR` (`P9-TSK-013`; the provenance read and `P9-TSK-027`'s reports and trace), `FX_TRADE_REVERSE` joining `LEDGER_OPERATOR` beside `TRANSFER_REVERSE` (`P9-TSK-025`), and `COUNTERPARTY_SCREENING_REVIEW` joining `KYC_REVIEWER` (`P9-TSK-016`) — separated so whoever sets prices can neither reverse trades nor clear screenings.
- **Phase 10** *(planned by the Phase 9 → 10 transition, 2026-10-07, ADR-0084 and ADR-0089 (`Proposed`); built by `P10-TSK-003`, 2026-10-07 — the permissions and roles ahead of their routes, each route's check site and register row arriving with it)*: three permissions and two roles (`V020`) — `CREDIT_POLICY_ADMINISTER` (propose, approve and reject credit policy and scorecard versions, never one's own proposal) and `CREDIT_INVESTIGATE` (the decision explanation, the reasoned evidence read, replay, reports and the policy-at-an-instant read) under the new role `CREDIT_POLICY_OFFICER`, and `CREDIT_UNDERWRITE` (the manual review queue: take, release, decide, second-approve or refuse the second approval, never one's own case's first decision) under the new role `UNDERWRITER` — separated so whoever writes credit policy cannot decide the cases it refers.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0081; **built by `P9-TSK-016`** - the as-built note closes this entry)*: the **Counterparty Screening** aggregate (`V009`) — kyc's transaction-time sanctions check on a cross-border beneficiary, distinct from onboarding KYC: an encrypted subject, attempts over the existing screening adapter through `CounterpartyScreeningProvider` (the existing credential), the provider verdict held as evidence and never the decision, review decided only by a person under `COUNTERPARTY_SCREENING_REVIEW` with a reason, and every outcome recording its decision basis, policy version and time (`INV-KYC-01` and `INV-KYC-04`, amended); an unverified payee (`NO_MATCH`, `UNAVAILABLE` at the provider's payee check) is always decided by a person, an automatic `CLEAR` exists only with a payee `MATCH` (the decision-basis `CHECK`), and screening unavailability fails safe — held, never priced; `CounterpartyScreeningRetrySchedule` retries `UNAVAILABLE` leaderlessly; the beneficiary moves in the decision's own transaction through `ScreeningOutcomeListener` (T-e). Built by `P9-TSK-016`. *As built:* `kyc V009` holds `counterparty_screening` (the name AES-256-GCM under kyc's evidence key with the screening id's sixteen bytes as associated data; `request_reference UNIQUE`; the decision-basis `CHECK`s; an edge trigger and column grants freezing the subject and the payee verdict) and the append-only `counterparty_screening_attempt` (the verdict and the provider's bytes sealed under the same binding - the evidence table the plan named is this table's columns); `CounterpartyScreenings` (screen, re-screen from the stored subject, retry, review, clearance) runs on kyc's own `TransactionRunner`; the provider is the existing sanctions endpoint and credential as a port of its own (`UNAVAILABLE` when no endpoint is configured); `ScreeningOutcomeListener.REFUSING` was composed only until the beneficiary listened - since `P9-TSK-017` `app` composes the beneficiary's listener (`CrossBorderBeneficiaryBeans`, T-e), and a decision publishes `kyc.CounterpartyScreeningDecided`; the review door is `POST /v1/operator/kyc/counterparty-screenings/{id}/decision` (`COUNTERPARTY_SCREENING_REVIEW`, held by `KYC_REVIEWER`).
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
- **Phase 10** *(planned by the Phase 9 → 10 transition, 2026-10-07, ADR-0085 (`Proposed`); built by `P10-TSK-002`, 2026-10-07 — consent `V003` redefines both generated purpose `CHECK`s and seeds the two version-1 texts; `ConsentMigrationTest` reads the newest definitions and refuses a removed purpose)*: two purposes join the closed `ConsentPurpose`, each with its own consent text (`V003`) — `CREDIT_BUREAU_ACCESS` and `FINANCIAL_DATA_ACCESS` (the `ConsentPurpose` javadoc's single reserved Phase 10 member becomes two) — read by `credit` through its `CreditConsentGate` port, which `app` implements over the gate, authoritatively in the transaction that opens a data request, at every retry, in the one that records its answer, and again at the freeze and in the deciding transaction (`INV-CRD-03`, `INV-CNS-01`). No new state shape.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0074, ADR-0076 and ADR-0078; built by `P9-TSK-003`, `-009`…`-012`, `-014` and `-026`, and read against the code by the exit review, `P9-DOC-001`)*: JPY (0 minor units) and BHD (3) join the chart — the thirteen operational purposes seeded per new currency (`V019`, `P9-TSK-003`), minor units pinned by a test and `app`'s startup guard (`SupportedCurrencyMinorUnitsGuard`); `OwnerKind.COUNTERPARTY`, the append-only `ledger.counterparty` registry and the `owner_ref` trigger (`V021`, `P9-TSK-010`), so a clearing position is (purpose, counterparty) for counterparty-owned purposes and never nets two counterparties (`INV-RAIL-04`, amended); five purposes, each arriving with its first poster — `FX_SPREAD_REVENUE` (`V020`, `P9-TSK-009`), `FX_PROVIDER_CLEARING` (admitted in `V021`) with counterparty `fx-sim-a` and its accounts (`V022`, `P9-TSK-011`), `FX_REALISED_GAINS` and `FX_REALISED_LOSSES` (`V023`, `P9-TSK-012`), `CORRIDOR_CLEARING` with `corridor-sim-a` (`V024`, `P9-TSK-014`) — and the second providers `fx-sim-b` and `corridor-sim-b` with their own accounts (`V025`, `P9-TSK-026`), every counterparty account seeded below the id ceiling; and `closedToFreeAdjustments()` restated over the FX books — `FX_POSITION`, `FX_SPREAD_REVENUE`, `ROUNDING_RESIDUAL` and the realised results — by `V020`'s binding trigger (ADR-0076), the FX books not joining the reconciled positions.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0076, D28; built by `P9-TSK-004`)*: a wallet product holds n `CUSTOMER_WALLET` ledger accounts, one per currency, and every wallet resolver is keyed by currency (`WalletAccounts.resolve` — the wallet in the asked currency, else the first-opened for the caller's currency judgement to refuse; `WalletsAreResolvedByCurrencyTest` refuses a `findFirst()` pick); a wallet opener reads the agreement `FOR SHARE`, so it serialises with a close; the add-a-currency door (`POST /v1/me/accounts/{id}/currencies`, keyed) and open-if-absent in the caller's transaction — the ledger's `(owner_ref, CUSTOMER_WALLET, currency)` unique under `INSERT … ON CONFLICT DO NOTHING`, then a re-read, so ten racing openers converge on one account and one `accounts.WalletCurrencyAdded`, written by the act whose insert returned the row; balances answer one per currency, never summed. Built by `P9-TSK-004`.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0074; built by `P9-TSK-004`)*: resolvers only — `TransferParticipants` resolves per decision and keyed by currency (`app`'s `JdbcTransferParticipants`: each side's wallet in the transfer's currency, else its first-opened wallet so the refusal row stays constructible); a transfer still never converts, and `CURRENCY_MISMATCH` is kept, meaning exactly "a side holds no wallet in this currency" — never an arbitrary pick among several. No new state, no migration.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0079, ADR-0080 and ADR-0083; built by `P9-TSK-014`, `-019`, `-020`, `-023`, `-024` and `X-TSK-013`, the sweep's order fixed by `P9-TST-001` and its bound's clock by the exit review, `P9-DOC-001`)*: the **Outbound Credit** (`V025`) — one instruction on one corridor rail to one beneficiary, owning the provider's ambiguity (`DISPATCHED → RECEIVED | COMPLETED | FAILED | UNKNOWN`, `UNKNOWN → RECEIVED | COMPLETED | FAILED`, `RECEIVED → COMPLETED | FAILED`, `FAILED` carrying `DECLINED`, `PROVIDER_UNAVAILABLE`, `NEVER_RECEIVED` or `RECALLED`; the `WithdrawalResolution` shape with a database-stamped send permit from birth, resolved by `OutboundCreditResolution` under `app`'s `OutboundCreditResolutionSchedule` — every credit awaiting its outcome first, delivery polls rotating — `NEVER_RECEIVED` concluded only past the rail's declared outcome deadline plus margin since the latest permit, judged on the database clock in the row-locking transaction (`DatabaseTime.now`), and never from `RECEIVED`) — and its born-once return fact (`outbound_credit_return`, `V027`: `applied_by` `APPLIER` | `RESOLUTION`, `resolution_id`, the applier-amount trigger), applied by `OutboundCreditReturns` under `OutboundReturnSchedule`; the `CorridorRail` port with the corridor rail's truthful declaration (`RefundMode.NONE` and `RoutingRejection.DIRECTION_UNSUPPORTED`, `V024`); routing's third subject with `destination_country` as a routing-rule matcher and decision input and per-candidate reachability, routing version 5 seeded (`V025`, paying ADR-0060 §2); `scheme_execution_claim` gains subject `OUTBOUND_CREDIT` (`V026`) and `provider_evidence` its sixth subject (`V025`); the recall request relayed with the provider deciding — `recall_requested_at` and `recall_outcome` (`RECALLED | REFUSED`), no recall permit, paced by the sweep — and no permit renewed, so no re-send, after a recall request; corridor callbacks are hints (ADR-0083) at `/providers/payments/corridor/webhooks` — an outcome is adopted only from an authenticated inquiry; composition through `OutboundCreditComposition` (the `CaptureComposition` shape), implemented in `app` as `CrossBorderCompletion` over crossborder's `PaymentProgress` and fx's `CrossBorderCompletionBooking`, each applier running past its acting exit in the applier's transaction. `X-TSK-013` brought the Phase 5–7 send permits under the same rule (`V028`: a trigger per table re-stamping any forward write of `last_dispatched_at` to the database's instant, after the machine trigger).
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, O6; built by `P9-TSK-003` and `X-TSK-013`)*: `MerchantOnboarding` admits JPY and BHD settlement currencies — found already true, since it follows `SupportedCurrencies` — and the Phase 6 0/3-minor ledger fee batch — the recorded deferral — is paid, with fee-schedule scale guards added at the door; `P9-TSK-003` needed no merchant migration (the module holds no currency `CHECK`). The one Phase 9 merchant migration is `X-TSK-013`'s `V009`: the payout's send permit is the database's — born `GREATEST(created_at, statement_timestamp())`, every forward write re-stamped by a trigger running after the machine trigger — and `MerchantPayoutResolution` reads its candidacy and `NEVER_RECEIVED` bound from `DatabaseTime.now`, the latter under the row lock (ADR-0057 §4's skew premise removed). No multi-currency settlement: a merchant still settles in one currency, `FeeCurrencyMismatch` and `PayoutCurrencyMismatch` stand, and ADR-0062 §7's payout-convergence trigger, fired by the corridor rail, is declined with its re-recorded trigger (ADR-0079 §9).
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
- **Phase 9** *(planned by the Phase 8 → 9 transition; built by `P9-TSK-004`)*: resolvers only — `app`'s `CheckoutPaymentParticipants` credits the merchant's payable in the offer's currency, pinned from the session (already currency-keyed by construction), and delegates the payer's wallet to the currency-keyed `JdbcPaymentParticipants`. No new state, no new route, no migration.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0078 and ADR-0082; built by `P9-TSK-011`, `-014` and `-026`, and read against the code by the exit review, `P9-DOC-001`)*: the source descriptor becomes counterparty-keyed with its settled currencies — compiled facts read off each counterparty's declaration in `app`, never columns — one source per (purpose, counterparty), `EverySettlingPositionHasASource` extended over both new positions, a parsed batch in a currency the counterparty does not settle rejected `CURRENCY_NOT_SETTLED` at the parse leg (`V015`'s new rejection code) — and two sources join, composed and never named: the FX provider's trade report (`FX_PROVIDER_REPORT`, `SIM_FX_CSV` v1 as `SimFxCsvFormat` with line types `FX_SOLD`/`FX_BOUGHT`/`FX_FEE` and references `COVER_REF`/`FX_TRADE_REF`, source row `fx-sim-a.trade-report`, `V015`, `P9-TSK-011`) and the corridor provider's settlement report (`SIM_CORRIDOR_CSV` v1 as `SimCorridorCsvFormat` under `PAYOUT_PROVIDER_REPORT`, reusing the payout line types and references `END_TO_END_REF`/`PAYOUT_PROVIDER_REF`, source row `corridor-sim-a.settlement`, `V016`, `P9-TSK-014`), each ingesting by upload with attestation and by pull over its own confined credential (`FINAPP_FX_REPORT_KEY`, `FINAPP_CORRIDOR_REPORT_KEY`), with golden files and a fault test per field; M9.8 added the `-b` source rows `fx-sim-b.trade-report` and `corridor-sim-b.settlement` (`V017`, `P9-TSK-026`) with each counterparty's own remittance shape (`SettlementBeans.remittanceLetterOf`) and the register's refusal of a shared one.
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
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0082; built by `P9-TSK-003`, `-011`, `-013`, `-014`, `-022`, `-023` and `-026`, and read against the code by the exit review, `P9-DOC-001`)*: no new break type and **no conversion, ever** (`ReconciliationNeverConvertsTest`, a planted violation refused) — the FX cover's legs reconcile as single-currency expectations (`FX_SELL_LEG` and `FX_BUY_LEG` on `FX_PROVIDER_CLEARING(provider)`, keyed `COVER_REF` with alias `FX_TRADE_REF`, opened by the cover entry's transaction through fx's `FxSettlementExpectations`; `V020`, `P9-TSK-011`) and the corridor's as `CROSSBORDER_PAYOUT` (keyed `END_TO_END_REF`) and the operation-anchored `CROSSBORDER_RETURN` on `CORRIDOR_CLEARING(rail)`, with their mirrors (`V021`, `P9-TSK-014`); new causes `FX_LEG_DIFFERS` (`AMOUNT_MISMATCH`) and `VALUE_DATE_DIFFERS` (`TIMING_DIFFERENCE`), the raise-pairing trigger and the timing-cause list re-stated (`V020`), selected by expectation kind in matching, an overdue leg escalated to `CRITICAL` when its paired leg is allocated (`P9-TSK-013`), and `FX_FEE` a priced fee line; no rule set is ever migration-seeded — the first-version door path (`RuleSetAdministration` admits a version 1 for a source with no `ACTIVE` version, four-eyes as any other) with `finapp.reconciliation.rule.set.missing` alerting (`RuleSetMissing`), and a fee schedule in every currency a source can settle, priced in that currency's own minor units, the existing four sources' v2 successors through the same door (O6, `P9-TSK-003`); `WaitingPayoutReturns` scoped by the sources a worker is handed, so the merchant and corridor return workers never see each other's items, `InternalReferenceLookup` scoped by source, the source-to-rail mapping per counterparty (`P9-TSK-026`), and the new port `ResolvedCorridorReturns` (`P9-TSK-023`, implemented in `app` as `CorridorReturnResolutions`) — inside the four-eyes approval of a parked corridor return, the return fact (`applied_by = RESOLUTION`), the fee refund and the payment's `RETURNED` in the approval's own transaction (T-g; the `SettlementBatchRepudiations` precedent), its `ParkedReturn` naming the transfer entry's accounts so the recorder pre-locks both entries' projection rows in order before its first posting (`P9-DOC-001`).
- **Transaction:** own, in its own schema; the chunk is the unit — decisions, candidates, allocations, item and expectation transitions, breaks, suspense items and the run cursor in one transaction, parks and unparks last. Four seams join another module's transaction, each justified by ADR-0064 §6, listed in this document's §6, and all four built: an expectation is opened **inside the completing transaction** of `payments` or `merchant` (ADR-0067) — and since `P8-TSK-020` the same port also opens a parking's suspense item and its owning break in the parking's transaction (`ReconciliationExpectationRecorder` → `ParkedConfirmations`, arbitrated by `UNIQUE (origin_ref)`); acceptance hands its batch over inside `settlement`'s acceptance transaction; a payout return's expectation is opened inside `merchant`'s application of it (ADR-0073); and a batch repudiation's approval reverses the recognition itself, through `ledger.ReversalService`, and has `settlement` write only the batch's `ACCEPTED → REPUDIATED`, its history, event and audit on the approval's connection, through the port this module declares, `SettlementBatchRepudiations` (ADR-0064 §3; `P8-TSK-023`) *(the fourth added by the transition's consistency review, B5; corrected 2026-10-01, `P8-DOC-001`: this had `settlement` write the reversal)*. A resolution and its compensating ledger posting commit together, through `ledger.AdjustmentService` with origin `RECONCILIATION` and a closed reason code (ADR-0071).
- **Consistency:** strong, in one schema. An allocation never exceeds either side (`INV-REC-07`: `CHECK`s, deferred Σ triggers and uniques); every reconciled position equals the signed remainders of its open expectations less its unallocated, unparked items, at every commit (`INV-REC-06`). Matching is deterministic in the honest sense: a decision is a pure function of its stored candidate snapshot and its pinned rule set, so the same stored inputs always produce the same matches and replay is exact (`INV-REC-04`); which candidates a decision saw depends on what had been recorded when it ran, which is why the snapshot is stored.
- **APIs:** operator routes under `/v1/operator/reconciliation/…` and `/v1/operator/reports/reconciliation/…`, each with its `RoutePermissionRegisterTest` row, lists bounded at 100: expectations and the settlement-status trail; runs, requeue and replay; decision and allocation explanations; reprocessing and the opening-position backfill; breaks — list, detail, trace, assignment, notes, evidence links, reclassification; resolution proposal, approval, rejection and withdrawal; batch repudiation; rule sets, activated four-eyes; the positions, suspense, unmatched, summary and provider-costs reports, each read audited. `RECONCILIATION_INVESTIGATE` reads and investigates, `RECONCILIATION_RESOLVE` resolves, `RECONCILIATION_ADMINISTER` decides what counts as a match. All privileged and operational.
- **Events:** `ReconciliationRunCompleted` (replaces the planned per-record `SettlementMatched`), `SettlementExpectationSettled`, `SettlementExpectationOverdue` (replaces the planned `settlement.SettlementExpectationUnmet`: the expectation moved, so its producer moved), `ReconciliationBreakRaised`, `BreakInvestigationStarted`, `BreakResolved` — identifiers, enums and counts only. The planned `AdjustmentPosted` is dropped: `BreakResolved.journalEntryId` together with `ledger.JournalEntryPosted` already carries the fact. The module consumes nothing; no correctness rests on an event.
- **Failure:** a matching job that crashes mid-batch resumes from its committed cursor on any instance, in the same claimant order, without duplicate or lost matches; a poisoned item is contained — an `ERRORED` decision, its remainder parked, a `PROCESSING_ERROR` break — and the chunk continues; a poisoned run goes `BLOCKED` with a CRITICAL break and holds its source visibly until requeued; every unallocated remainder either waits out a grace window judged on the database clock or parks with a break in its own transaction, and a run never completes with an item `PENDING` (`INV-REC-02`); a late record, external or internal, is allocated like any other and resolves its break `EVIDENCED` (`INV-SET-03`); a reference collision at opening is recorded and raised as a break, never a failed payment; two operators resolving one break produce one resolution, and an approval that finds the residual moved is refused `reconciliation.ResolutionStale`.
- **Security:** resolution is the most sensitive non-administrative privilege in the platform — four-eyes for every person's resolution but the zero-value `ACKNOWLEDGE` of a `TIMING_DIFFERENCE` raised by a timing detector (cause `LATE_MATCH` or `CYCLE_MISMATCH`), refused at the domain, the `resolution` `CHECK`s and `V014`'s trigger keyed on the break's cause, and ledger `V010` *(corrected 2026-10-01, `P8-DOC-001`: this read "four-eyes whenever value is at issue or the resolution posts" — a diverged replay's zero-value acknowledgement is four-eyes, `V014`)*; closed reason codes; lines derived from the subject's remainder, never typed; reconciled positions closed to free adjustments (ADR-0071). Two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` and `RECONCILIATION_CONTROLLER`, so whoever can loosen a tolerance cannot resolve the breaks it would hide. References are `CONFIDENTIAL` and amounts `RESTRICTED-FINANCIAL`; notes and narratives never reach a log, an event or an audit body; the module holds no byte of a file. No `DELETE` anywhere in the schema; decisions, candidates, allocations, notes, evidence links, releases and parks are append-only (`INV-REC-01`).
- **Operations:** `finapp.reconciliation.run.pending`, `.run.age` (alert), `.run.blocked` (alert, must be 0) and `.run.latency`; `.item`, `.item.unmatched` and `.rematch`; `.expectation.open`, `.expectation.overdue` (alert) and `.expectation.overdue.age`; `.break.raised`, `.break.open` and `.break.age` (alert per severity); `.resolution`, `.resolution.latency` and `.adjustment`; `.suspense.open`, `.suspense.age` (alert) and `.suspense.unowned` (must be 0); `.position.proof`, `.line.unattributed` and `.cash.proof` (each must be 0); `.replay`. Counts, ages and verdicts only, under the new tag keys `source` (joining with `P8-TSK-002`) and `severity` (joining with the first severity-tagged series, the break meters of `P8-TSK-024`) *(when each key joins: the transition's consistency review, B6)*; unmatched value, suspense balance and provider costs are audited operator reports, never a series (ADR-0072).
- **Ports:** declares `InternalReferenceLookup` (implemented in `app` by `JdbcInternalReferenceLookup` over `payments`' and `merchant`'s public read stores — for the instant rail `payments.scheme_execution_claim` (`V023`, the Phase 7 → 8 transition's repair), which names exactly one explaining subject per scheme reference, so a scheme line whose reference no claim holds names no completed execution — break typing only, never allocation) and `SettlementBatchRepudiations`, the repudiation seam (implemented in `app` by `ComposedBatchRepudiations` over `settlement`'s `BatchRepudiation`, `P8-TSK-023`); `ExpectationRegister` is the entry `app`'s `ReconciliationExpectationRecorder` calls to implement `payments`' `SettlementExpectations` and `merchant`'s `PayoutSettlementExpectations` — and since `P8-TSK-007` the one path the opening-position backfill adopts history through, so a backfilled row is what the live opener would have written; `ExpectationReadings` is the register's read side for the proofs (`P8-TSK-007`): open remainders for the position proof's `Money` fold, the known `(entry, account)` pairs for the completeness verifier, and the open counts per source — composed in `app`'s `PositionProof` with the ledger's derivation and line reads, one `REPEATABLE READ` snapshot, report and never repair; the derivation is signed by each account's normal balance, so the proof re-reads it from the side its identity names — DR−CR on every clearing position, CR−DR on `SUSPENSE_UNMATCHED` — from the account's stored `normal_balance`, never from its purpose (`PAYOUT_CLEARING` is CREDIT-normal, ledger `V012`). `app`'s `ReconciliationIntake` implements `settlement`'s `AcceptedBatchIntake` through this module's API. It posts through `ledger`'s `PostingService` (parks, unparks, offsets, keyed `recon-suspense:<parkId>` — a key freshly minted per posting, so it converges nothing: a park's once-ness rests on the item's conditional edge and `UNIQUE (external_item_id)` on the suspense item, an unpark's on the suspense item's locked unreleased remainder), `ReversalService` (a repudiated batch's recognition, keyed `settlement-batch:<batchId>` in the `ledger.reverse` scope, `P8-TSK-023`) and `AdjustmentService` (`proposeOwned`, `approveOwned`, `rejectOwned` — called by `ResolutionMachine` and, for a pending proposal evidence overtakes, by the evidence writer, since `P8-TSK-015`); `app`'s `BreakResolutionDesk` is the resolution doors' one-transaction wrapper, and the attribution reads are the ledger's own (`PositionBreakdown` and `StatementDerivation` flag a `RECONCILIATION`-origin entry's lines), interpreted by `merchant`'s `MerchantPayable` and `app`'s statement view. Since `P8-TSK-014` it also declares `EvidenceTargets` (does an evidence link's settlement, ledger or payments target exist) and `TraceEvidence` (a batch's file and recognition entry, a line's batch, the provider statements retained about an operation), both implemented in `app` by `ComposedCaseFileEvidence` over those modules' public read stores — lock-free, identifiers and metadata only, never a byte of content. `ParkedConfirmations` is the entry `app`'s recorder calls for a parking's owner (`P8-TSK-020`); `WaitingPayoutReturns` is the module's public read of the payout returns awaiting their worker, implemented here by `JdbcWaitingPayoutReturns` and read by `app`'s return worker (`P8-TSK-019`); and the module declares two telemetry ports, `ReconciliationTelemetry` (implemented in `app` by `ReconciliationOutcomeMeters`, `P8-TSK-024`) and `ReplayObserver` (by `ReconciliationReplayMeters`, `P8-TSK-022`), whose implementations decide nothing. *(The ports from `P8-TSK-019` on named at the Phase 8 review, `P8-DOC-001`.)*
- **Depends on `ledger`, `platform` and `sharedkernel` only** (`ReconciliationModuleIsolationTest`): no edge to `settlement`, `payments` or `merchant`; other modules' facts are copied when their operation completes, never joined (ADR-0064 §4).
- **Invariants:** `INV-SET-02`, `INV-SET-03`, `INV-REC-01`…`-09`, `INV-HIST-04`, `INV-REV-04`, `INV-AUD-04`.
- **Hard rule:** no code path deletes a break (`INV-REC-01`, `INV-REC-02`) — no `DELETE` grant and a refusing trigger. Value enters `SUSPENSE_UNMATCHED` only in the transaction that records its owning break (`INV-REC-09`); no tolerance exists on an amount already in a position (`INV-REC-08`); the module names no `*_CLEARING` purpose.

### `fx` — Phase 9
- **Responsibility:** currency conversion at a server-authoritative, time-bounded, single-use price, and the platform's own FX exposure. The quote is a frozen posting plan with stored rate provenance — reference → provider firm quote (the lock) → internal → customer → executed → cover-executed — the trade posts exactly the plan, and every accepted quote is covered back-to-back with the FX provider exactly once, however the provider answers.
- **Owns:** Pricing Policy (versioned, four-eyes) with pair and provider availability and their enable proposals, Exchange Rate snapshot (the independent reference, with its observation and receipt times), FX provider declarations and encrypted provider evidence, FX Quote (+ request, sourcing steps, history), FX Trade, FX Cover (+ attempts, execution fact, realised result), Trade Reversal (`P9-TSK-025`).
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0074…0078 and ADR-0083; **built by `P9-TSK-001`, `-002`, `-005`…`-009`, `-012`, `-013`, `-021`, `-025` and `-026`** — `fx V001`…`V009`, each task's as-built note below — and read against the code by the exit review, `P9-DOC-001`)*: the quote's plan lines, amounts and residual are parts of the aggregate (`V005`), the residual within the policy's proven bound — 1 minor unit when amount and margin both round half, else 2 — asserted by `ConversionPlan` and `CHECK`-bounded at ±2 for every writer, the plan identity and the provenance frozen by trigger; the quote machine is six states (`ISSUED → ACCEPTED → EXECUTED`; `ISSUED → EXPIRED | CANCELLED`; `ACCEPTED → ABANDONED`; the `EXECUTED` edge admitted with the trade, `V006`), every edge a conditional on the locked row judged on the database clock, expiry a modelled event written exactly once by whichever conditional fires (the sweeper's, or an acceptance finding the quote past expiry); the live-quote cap is arbitrated by a `BEFORE INSERT` trigger under advisory namespace `5`, for every writer, the reference snapshot under `6` (`V002`), and the pair and provider availability — an append-only fact read unlocked — has its writers serialised under `7`; the cover (`V006`, `V007`) is re-sent under one stored reference until answered, requoted only after a definitive rejection via a fresh firm quote, and closes exactly the plan's position legs, the difference posted as realised FX result; the unwind is a cover of kind `UNWIND` (admitted by `V006`'s `CHECK`, `UNIQUE (quote_id, kind)` making it exactly one), its cover's mirror, born `DISPATCHED` with no attempt row, its `T1` and fresh firm quote stored by its first dispatch before any send (`V008`'s triggers, `P9-TSK-021`); the trade reversal is four-eyes (`V009`, `P9-TSK-025`). *This entry was rewritten by the transition: it owned "FX Position" and "Currency configuration" — `FX_POSITION` is the ledger's account per currency, explained by this module's proof, never a table here, and minor units are the JDK's, pinned by a test and a startup guard (ADR-0074); its event list named `CurrencyConverted`, folded into `FxTradeExecuted`; and "posts both ledger legs through an FX position account in one posting" stands, sharpened: the trade posts the quote's frozen plan in the acceptance's own transaction.*
- **Transaction:** own, composed into the named cross-module transactions (`PHASE_9_PLAN.md` §7, the ADR-0064 §6 style). Quote creation is two transactions with the provider call between them — the idempotency claim and the pinned policy version first, no connection held across the call (ADR-0046's discipline). A wallet conversion (T-a) is one transaction: the quote's `ISSUED → ACCEPTED → EXECUTED` with both history rows, the trade row (`UNIQUE (quote_id)`), the destination wallet opened if absent through `ConversionParticipants` (`INSERT … ON CONFLICT DO NOTHING`, then a re-read, D28), the posting from the plan, the cover row — postings last. A cover's dispatch and each outcome are their own transactions (T-d), the outcome's posting committed beside its leg-expectation inserts.
- **Consistency:** strong. Validity, reference staleness and the sweep bounds are judged in SQL on the database clock; nothing executable is cached — the reference and the policy version are read inside the transaction and pinned; a quote expiring between validation and execution is refused on the locked row (`INV-FX-02`).
- **APIs:** the customer's quote request (keyed, both fixed sides), quote read and cancellation, pairs discovery, conversion by quote id (synchronous and final) and trade read; the operator's pricing policies and pair/provider availability under `FX_ADMINISTER` (four-eyes; a disable is one person with a reason, an enable a proposal approved by a different person), the trade reversal under `FX_TRADE_REVERSE` (`P9-TSK-025`), the audited position, revenue and provenance reads under `FX_INVESTIGATE`; and the FX webhook door (HMAC + freshness) — a hint only.
- **Events:** `FxQuoteIssued`, `FxQuoteAccepted`, `FxQuoteExpired` (with `detectedBy`), `FxQuoteCancelled`, `FxQuoteAbandoned`, `FxTradeExecuted` (replaces the planned `CurrencyConverted`: one fact, one event), `FxTradeReversed`, `FxCoverExecuted`/`FxCoverRejected`/`FxCoverRequoted`, `FxAvailabilityChanged`, `PricingPolicyActivated` — identifiers, enums and minor-unit strings with currency and scale; no rate, no name, no provider reference value.
- **Failure:** an unavailable or stale reference fails closed — the quote is refused, never priced on an old rate, with failover across declared providers before the `503`; the cover owns the provider's ambiguity — a lost response is re-sent under the same reference, an unknown outcome is resolved by authenticated inquiry, no cover is ever concluded "never received", and an abandoned covered quote has exactly one unwind (`INV-FX-08`); a customer's booked conversion never waits on, and is never changed by, a cover outcome (`INV-FX-09`); the rounding residual is posted to `ROUNDING_RESIDUAL` in its own currency, never absorbed and never folded into margin (`INV-FX-07`, `INV-BAL-03`).
- **Security:** rates are server-authoritative — a request carrying a rate, or any unknown field, is refused `422` with nothing written (`RatesAreNeverClientSuppliedTest` and the OpenAPI request-schema guard, each with a planted violation); spread and markup are one explicit `FX_SPREAD_REVENUE` line with stored attribution, never concealed in the rate (`INV-FX-03`); policy and enabling are four-eyes, self-approval refused at the domain and the `CHECK`; the provider, webhook, evidence and reference credentials are confined (`FINAPP_FX_PROVIDER_KEY`, `FINAPP_FX_WEBHOOK_KEY`, `FINAPP_FX_EVIDENCE_KEY`, `FINAPP_FX_REFERENCE_KEY`), every provider URL through `ProviderTransportGuard`; callbacks are hints — a signed-but-forged callback moves nothing until the authenticated inquiry over the outbound credential confirms it (ADR-0083).
- **Operations:** `finapp.fx.quote` and `.quote.closed` (quote-to-trade and expiry rates by ratio, per pair and outcome), `.quote.open`, `.provider.quote.latency`, `.rate.age` (alert past the maximum age), `.rate.fetch` (`P9-TSK-005`), `.trade` (`executed`, `reversed`), `.residual` (frequency by `pair` and `direction` — `positive`, `negative`, `zero` — never an amount), `.cover` with `.cover.unknown.active`/`.unknown.age` and `.cover.open.age` (alerts) and `.cover.latency` (tagged `provider` and `type`), the proof gauges `.proof` (must be 0) and `.plan.verdict`, and the schedules' gauges `finapp.fx.rate.sweeper.enabled`, `.quote.expiry.sweeper.enabled` and `.cover.sweeper.enabled` — counts, ages and verdicts, never an amount (ADR-0072); the amounts live in the audited reports `/v1/operator/reports/fx/position` and `/v1/operator/reports/fx/revenue` (`Phase9Reports`, `P9-TSK-027`).
- **The reference, as built (`P9-TSK-005`):** `RateSource` (one fetch answers every declared pair, as values) with the `simulated-reference` adapter in `app` (`HttpReferenceRateSource`: `GET {base}/fx/reference/rates`, bearer `FINAPP_FX_REFERENCE_KEY`, a strict `BASE/QUOTE,RATE,OBSERVED_AT` line grammar so no rate passes through a double, the URL behind `ProviderTransportGuard`); `ReferenceSourceDeclaration`'s ten canonical pairs, held once each in market direction (no inversion, ADR-0074); `fx V002`'s append-only `rate_snapshot` (`NUMERIC(20,10)`, `received_at` forced to the database clock, newer-than-latest under namespace 6 for every writer) and the database-stamped `rate_fetch_permit`; `ReferenceRateFetch` behind the leaderless `FxRateFetchSchedule`; `RateSnapshotStore.freshLatest` judges staleness in SQL with the maximum age as a parameter (its value is the pricing policy's, `P9-TSK-007`).
- **The provider boundary, as built (`P9-TSK-006`):** `FxProvider` - `firmQuote` (information; failover safe), `execute` under our `T` (the money act; a lost response is never a "no") and `inquire(T)` - answering sealed verdicts (`Quoted`/`Declined`/`NothingSent`/`Indeterminate`; `Executed`/`Rejected`/`Unrecognised`/`NothingSent`/`Indeterminate`) with the received bytes for retention; `FxProviderDeclaration` (code, version, ordered pairs, settled currencies, maximum validity) and the `FxProviders` directory; `fx V003`'s append-only `fx_provider_evidence` (named with its domain beside `payments.provider_evidence`), AES-256-GCM under `FINAPP_FX_EVIDENCE_KEY` and keyed by our reference (`INV-PAY-04`). The `fx-sim-a` adapter in `app` maps its wire totally - only a refused connection is `NothingSent`, every unknown word, 5xx, malformed body or over-precise rate `Indeterminate`, no default a success - under `FINAPP_FX_PROVIDER_KEY` and the transport guard, its vocabulary confined (`FxProviderVocabularyIsConfinedTest`); `SimulatedFxEngine` (test scope) is the provider's honest side, deduping on `T` before validity, and the contract battery holds it.
- **The pricing policy and the kill switch, as built (`P9-TSK-007`):** `PricingPolicyAdministration` (propose a whole version, approve - the predecessor retired first in the same transaction - reject) and `FxAvailability` (disable at once; propose, approve, reject an enabling) over `fx V004`, behind `FxAdministrationDesk` and eight `/v1/operator/fx` routes under `FX_ADMINISTER` (identity `V019`'s `FX_CONTROLLER`, a population no other role holds). Four-eyes at the domain and by `CHECK`, each rank proven alone (`FxFourEyesTest`, the database suites); one `ACTIVE` and one `PROPOSED` by partial uniques; a retirement commits only beside its successor (deferred trigger); availability an append-only fact per change under advisory namespace `7`; every reason screened for instrument shapes at both ranks. Events `fx.PricingPolicyActivated` and `fx.FxAvailabilityChanged`; seven reasoned audit actions. No version is seeded - v1 is OPERATIONS_RUNBOOK §2.
- **The quote, as built (`P9-TSK-008`):** `fx V005`'s `quote` (the frozen plan: the per-currency plan identity, the residual within ±2, the scales, the copied terms equal to the pinned version's, the window computed by the insert trigger), `quote_request`, `quote_sourcing_step`, `quote_event`; `QuoteIssuance` (claim, wire, issue - three steps, no shared connection) and `QuoteLifecycle` (read with lazy `EXPIRED`, cancel, the expiry page) behind `FxQuoteDesk` and `/v1/me/fx/quotes`, `…/{id}`, `…/{id}/cancellation`, `/v1/me/fx/pairs`; `ConversionParticipants` implemented in `app` over the party projection (owner `ACTIVE`; the wallet methods with `P9-TSK-009`); the leaderless `FxQuoteExpirySchedule`; the cap under advisory namespace `5`; `finapp.fx.quote`, `.quote.closed`, `.quote.open`, `.quote.expiry.sweeper.enabled`. Every request body is a `ClosedBody`, held with no rate field by `RatesAreNeverClientSuppliedTest` and the OpenAPI request-schema guard.
- **The conversion, as built (`P9-TSK-009`):** `fx V006`'s `trade` (the ACCEPTED quote's plan copied and frozen, `UNIQUE (quote_id)`, `executed_rate = customer_rate` by `CHECK`, its entry attached once and required at commit by a deferred trigger), `cover` and `cover_attempt` (born `DISPATCHED` with `T₁`, the full machine and the database's permit held by trigger; sent by `P9-TSK-012`), the quote edge function replaced so `EXECUTED` needs its trade; `FxConversion` (T-a) and `ConversionLines` (the only code naming the FX books, `FxBooksHaveOnePosterTest`) behind `FxConversionDesk` and `POST/GET /v1/me/fx/conversions`; `ConversionParticipants.wallet`/`openIfAbsent` over the customer's live WALLET agreement; ledger `V020`'s `FX_SPREAD_REVENUE` and the FX books closed to free adjustment; `finapp.fx.trade`, `finapp.fx.residual{direction}`.
- **Counterparty-keyed clearing positions, as built (`P9-TSK-010`, ADR-0078):** ledger `V021` - `OwnerKind.COUNTERPARTY`, the append-only `ledger.counterparty` registry, the four chart rules restated, the `owner_ref` trigger, and `FX_PROVIDER_CLEARING` admitted as the first counterparty-owned purpose (no account until `V022`); `ChartOfAccounts.resolve(purpose, counterpartyCode, currency)` and the two-argument form's refusal; `CounterpartyChart` and the app's startup `CounterpartyChartGuard` over `CounterpartyClearings` (empty until `P9-TSK-011`); the source register keyed per (purpose, counterparty), recognitions and remittances on the counterparty's own account; `PositionProof`'s proven purposes derived from the register and completeness over every account of a reconciled purpose.
- **The FX provider's position, source and vocabulary, as built (`P9-TSK-011`):** ledger `V022` (`fx-sim-a` registered with its five `FX_PROVIDER_CLEARING` accounts; the purpose joins the reconciled positions); settlement `V015` (`FX_PROVIDER_REPORT`, `SIM_FX_CSV` v1 as `SimFxCsvFormat`, `FX_SOLD`/`FX_BOUGHT`/`FX_FEE`, `COVER_REF`/`FX_TRADE_REF`, `CURRENCY_NOT_SETTLED` at the parse leg, the source row); reconciliation `V020` (`FX_SELL_LEG`/`FX_BUY_LEG`, the key and line mirrors, `FX_FEE` priced, `FX_LEG_DIFFERS`/`VALUE_DATE_DIFFERS`); the `fx` port `FxSettlementExpectations` implemented by `ReconciliationExpectationRecorder` on the counterparty's own source; `FxProviderDeclaration.clearingPurpose()` read by `CounterpartyClearings` and `SettlementBeans.fxProviderSources()`; the pull collector under `FINAPP_FX_REPORT_KEY`; the reference lookup's `COVER_REF` over cover attempts; reconciliation's `PositionAccounts` port (composed in app from the register) so the matcher parks an item on its own source's account - a counterparty's own, never a shared one; the first-version rule-set path, `RuleSetMissing` and `finapp.reconciliation.rule.set.missing`; `ReconciliationNeverConvertsTest`.
- **The FX cover, as built (`P9-TSK-012`):** `fx` owns the cover end to end - `CoverStore`/`JdbcCoverStore` over `fx V007` (`cover_execution`, the restated machine), `CoverLines` (the second and last poster of the FX books, ledger `V023`'s `FX_REALISED_GAINS`/`LOSSES`), `FxCoverOutcomes` (T-d, the one applier) and `FxCoverDispatch` (the wire legs), observed through the `CoverObserver` port; `app` composes them (`FxCoverBeans`), runs `FxCoverSchedule` and the post-commit `FxCoverNudge`, meters them (`FxCoverMetrics`) and serves the callback door (`FxCallbackController`/`FxCallbackService`, key `FINAPP_FX_WEBHOOK_KEY`, `payments`' `WebhookSignature` reused); the legs open through `fx`'s own `FxSettlementExpectations` port; reconciliation's `CoverLegKey` qualifies a cover leg's key by its currency at both sides.
- **FX explained and settled to cash, as built (`P9-TSK-013`):** `fx` owns the proofs - `FxBooksProof` and `FxPlanVerification` over its own `FxProofStore` and ledger's `BalanceDerivation`/`JournalEntryStore` - and the provenance read model (`FxProvenanceStore`); `app` gauges them (`FxProofMetrics`) and serves `GET /v1/operator/fx/trades/{id}/provenance` (`FxProvenanceController`/`FxProvenanceDesk`) behind `identity`'s new `FX_INVESTIGATE`, held by `RECONCILIATION_OPERATOR`; `reconciliation`'s `Matching` names the FX causes by kind and `ReconciliationSweep` escalates a paired leg; the recorder dates a cover leg by the cover entry's value date.
- **The corridor rail, its position and its source, as built (`P9-TSK-014`, ADR-0080 sections 1-2, ADR-0082):** `payments` declares the credits-only rail - `RefundMode.NONE` (push only; routing refuses a `PAY_IN` on it with `RoutingRejection.DIRECTION_UNSUPPORTED`, payments `V024`), the `CorridorRail` port, `CorridorDeclaration` (coverage, windows, charge bearer; its counterparty is its rail id) and `SimulatedCorridorAdapter` (`corridor-sim-a`: the rail, its declaration and its wire) - and the `RailOperations` directory (`RailId -> PushRail | CorridorRail`), through which `Withdrawals` and `PaymentConfirmation` look their routed rail up and refuse, with nothing sent, a rail lacking the operation; `ledger` `V024` admits `CORRIDOR_CLEARING` (LIABILITY, credit-normal, counterparty-owned) with `corridor-sim-a`'s registry row and USD/JPY/BHD accounts, joining the reconciled positions; `settlement` `V016` adds `SIM_CORRIDOR_CSV` v1 (`SimCorridorCsvFormat`) under `PAYOUT_PROVIDER_REPORT` and the source row; `reconciliation` `V021` adds `CROSSBORDER_PAYOUT`/`CROSSBORDER_RETURN`; `WaitingPayoutReturns` is scoped by source (one reader per worker) and `JdbcInternalReferenceLookup` resolves `END_TO_END_REF`/`PAYOUT_PROVIDER_REF` within the item's source family; `app` composes the rail, the counterparty chart and the source from one declaration (`PaymentBeans.DECLARED_RAILS`, `CORRIDOR_DECLARATIONS`), with `CorridorProviderKey` and `CorridorReportKey`. Payments' expectation port gains the two kinds with their opener (`-019`).
- **The corridor policy and availability, as built (`P9-TSK-015`, ADR-0080 section 4):** `crossborder`'s first tables (`V002`) - `corridor_policy_version` on the pricing policy's machine and four-eyes `CHECK` (no seed exemption), its append-only `corridor_policy_event`, the frozen `corridor` rows (S, D, country, candidate rails in order, fee as fixed minor units in S plus a margin with a named rounding, maximum in D, screening validity, delivery estimate, required data), the append-only `corridor_availability` facts keyed by the stable code `S-D-CC` and the four-eyes `corridor_enable_request`, with the reason screen twinned from fx `V004`; `CorridorPolicyAdministration` and `CorridorAvailability` (advisory namespace 8) own the acts, judging the build through the `CorridorDirectory` port at proposal and at approval; `app` composes it all (`CrossborderBeans`, crossborder's own `TransactionRunner`), implements the directory over payments' `RailOperations`, and serves the operator doors behind `identity`'s new `CROSSBORDER_ADMINISTER` (held by `FX_CONTROLLER`) and the customer's `GET /v1/me/cross-border/corridors`.
- **Providers:** the simulated FX provider and reference source behind `FxProvider` and `RateSource` (`P9-TSK-006` and `P9-TSK-005`), provider vocabulary confined to the adapter (`INV-PAY-03`'s discipline), contract batteries with fault injection; M9.8 added the second FX provider, `fx-sim-b` — a declaration over the same simulated adapter with its own confined key (`FINAPP_FX_PROVIDER_B_KEY`) and transport row (`P9-TSK-026`); the reference source stays one.
- **Ports:** declares `FxProvider` and `RateSource` (implemented in `app` by the simulated adapters), `ConversionParticipants` (`app`, over `accounts` and the party projection — owner `ACTIVE` per decision with no cache, wallet by currency, open-if-absent in the caller's transaction) and `FxSettlementExpectations` (`app`, over `ReconciliationExpectationRecorder` — the cover legs' expectations opened in the cover outcome's transaction, ADR-0067). Its entries are what `app`'s implementations of `crossborder`'s `CrossBorderFx` and its share of `payments`' `OutboundCreditComposition` drive. It posts through `ledger`'s `PostingService`, and a trade reversal through `ReversalService` (`P9-TSK-025`).
- **Depends on `ledger`, `platform` and `sharedkernel` only** (`FxModuleIsolationTest`, with planted probes): no edge to `crossborder`, `payments`, `kyc` or `accounts`.
- **Invariants:** `INV-FX-01`…`-09`, `INV-ACC-01`, `INV-BAL-03`, `INV-HIST-04`, `INV-IDEM-01`, `INV-LED-04`.
- **Hard rule:** the FX books — `FX_POSITION`, `FX_SPREAD_REVENUE`, `ROUNDING_RESIDUAL`'s FX lines, `FX_REALISED_GAINS` and `FX_REALISED_LOSSES` — are named in production code only by their declaration and fx's two line composers, `ConversionLines` and `CoverLines` (`FxBooksHaveOnePosterTest`, a planted violation refused), and accept no free adjustment (`closedToFreeAdjustments()`, `INV-FX-06`); a trade reversal's exact mirror goes through ledger's `ReversalService`; `FX_PROVIDER_CLEARING` is named only by the provider's declaration (`FxProviderDeclaration.clearingPurpose()`, `CounterpartyClearingIsNamedByDeclarationsTest`) — the counterparty's accounts arrive as values (`INV-RAIL-04`).

### `crossborder` — Phase 9
- **Responsibility:** the customer's instruction to pay a beneficiary abroad, and the corridor rules governing it: corridors, beneficiaries and their screening state, offers, the payment's business lifecycle, and cancellation by recall. It decides; `fx` prices and books; `payments` executes (ADR-0079).
- **Owns:** Corridor Policy (versioned, four-eyes) with corridor availability and its enable proposals, Cross-Border Beneficiary, Corridor Selection (+ steps), Payment Offer (immutable facts on the quote, not a second machine), Cross-Border Payment (+ history), Cancellation Request (born once, never updated).
- **Phase 9** *(planned by the Phase 8 → 9 transition, ADR-0079…0081; **built by `P9-TSK-001`, `-015`, `-017`…`-020`, `-023` and `-024`** — `crossborder V001`…`V006`, each task's as-built note below — and read against the code by the exit review, `P9-DOC-001`)*: the payment machine (`V005`) is `SUBMITTED → IN_TRANSIT | FAILED`, `IN_TRANSIT → DELIVERED | RETURNED`, `DELIVERED → RETURNED`, held by a `CHECK` generated from the enum, an every-writer edge trigger and the history, the customer shown shaped statuses (`PROCESSING`, `SENT`, `DELIVERED`, `RETURNED`, `FAILED`, and `CANCELLED` for a recalled payment — `FAILED(RECALLED)`, or `FAILED(NEVER_RECEIVED)` after a cancellation request); a corridor is (source currency, destination currency, destination country) under a corridor policy version (`V002`), with ordered candidate rails, fee schedule, limit and screening validity, its availability an append-only fact read unlocked whose writers serialise under advisory namespace `8`; the beneficiary (`V003`) is known by an opaque provider reference, selected a corridor at registration (recomputable from its recorded steps), its registration serialised under namespace `9`, and revocable from every non-terminal state with one response; a clearance's validity lapse is judged on the database clock (`DatabaseTime.now`) at the offer and the authorization (`P9-DOC-001`). *This entry was rewritten by the transition: "delegates conversion to `fx` and execution to `payments`" stands, but through ports this module declares, with no build edge to either; its `CrossBorderPaymentSettled` is not built — settlement is reconciliation's fact, the existing `SettlementExpectationSettled` with kind `CROSSBORDER_PAYOUT`; and "sanctions screening on counterparties" is kyc's `CounterpartyScreening` on the beneficiary before pricing, decided there and mirrored here, never this module's own check (ADR-0081).*
- **Transaction:** own, composed into the named cross-module transactions. The authorization (T-b) is one transaction — the payment row, the quote's acceptance and the cover row through `CrossBorderFx`, routing, the outbound credit and the hold through `CrossBorderExecution` — with the provider dispatch after its commit; the outcome appliers (T-c) and an exact return (T-f) run inside `payments`' applying transaction through `OutboundCreditComposition`, moving the payment in the same transaction and never swallowing a failure; a screening decision (T-e) moves the beneficiary inside kyc's deciding transaction through `ScreeningOutcomeListener`, a no-op on a `REVOKED` beneficiary.
- **Consistency:** strong internally; the provider leg is eventually consistent and its gap always explained — settlement timing is corridor-dependent and often long, tracked by reconciliation's expectation, never by a payment state.
- **APIs:** corridors discovery; beneficiary registration (keyed, step-up, the provider grant exchange, the payee check acknowledged whenever it is not `MATCH`), read and revocation; the cross-border quote with its offer (rate, fee, guaranteed destination amount, expiry); the authorization (`202`; completion by hinted inquiry or sweep); the payment read; cancellation by recall; the operator's corridor policies and availability under `CROSSBORDER_ADMINISTER` (four-eyes), and the payment trace under `FX_INVESTIGATE`.
- **Events:** `CrossBorderPaymentInitiated`, `CrossBorderPaymentInTransit`/`Delivered`/`Failed`/`Returned` (one event per edge, in order), `CrossBorderCancellationRequested`, `BeneficiaryRegistered`/`BeneficiaryActivated`/`BeneficiaryBlocked`/`BeneficiaryRevoked` (internal topic, never a customer surface), `CorridorPolicyActivated`, `CorridorAvailabilityChanged` — identifiers, enums and minor-unit strings; never a name or a provider reference value. The planned `CrossBorderPaymentSettled` is not built.
- **Failure:** a conversion succeeding while the downstream payment fails strands nothing: a `FAILED` payment debits the customer nothing — the hold released, the quote `ABANDONED` and the cover unwound from the failing applier's transaction (`INV-XB-01`); a return in any shape but exactly the instructed credit is never posted automatically — it parks with its break for a person, whose four-eyes resolution credits the customer and records the return on the payment in one transaction (`INV-XB-04`, O2); a `TOO_LATE` recall is never shown as cancelled; `RECEIVED` is never concluded `NEVER_RECEIVED`; an answer implying acceptance on a credit not yet `COMPLETED` applies its facts in order, in one transaction.
- **Security:** no offer and no payment for a beneficiary that is not `ACTIVE` with a current `CLEAR` or person-`RELEASED` screening, consulted in-lock at the quote's and the authorization's transactions (`INV-XB-02`), every non-payable state answering the same byte-identical response; what the customer was shown is exactly what is held, posted and instructed (`INV-XB-03`); corridor policy is four-eyes; the limit and risk seams (`CrossBorderLimitCheck`, `CrossBorderRiskDecision`, `PermitAllUntilPhase13`, judging a `CrossBorderInstruction` - customer, corridor, source amount - into a two-valued `CrossBorderVerdict`; built by `P9-TSK-016`, field-free) are required constructor parameters with reserved refusal codes (`crossborder.LimitRefused`, `crossborder.RiskRefused`); beneficiary names are held only by kyc, encrypted — this module stores the provider reference, country, currency and entity type (`INV-RAIL-03`).
- **Operations:** `finapp.crossborder.payment` (per corridor and outcome), `.payment.latency` (accept, deliver), `.payment.in.transit.age` (alert), `.cancellation` (recalled, too_late — the free-option watch), `.return` (applied, already_returned, deferred, not_applicable, resolved) — `CrossBorderMetrics`' (`P9-TSK-027`), counted after commit — counts, ages and verdicts, never an amount (ADR-0072); the module owns no schedule, so it has no sweeper gauge; the corridor amounts live in the audited corridor report. Settlement latency per corridor comes from reconciliation's existing source meters on the new sources.
- **As built by `P9-TSK-017`:** the `CrossBorderBeneficiary` (`Beneficiaries`, `BeneficiaryStore`, `crossborder V003`), the pure `CorridorSelection`, the `CounterpartyScreening` port (`requestWithin` in crossborder's unit of work, `screenNow` after commit; `app` over kyc's `CounterpartyScreenings`), `CorridorDirectory` gaining `operable` and `exchange` (`app`'s `RailDirectory` over payments' `RailOperations`), kyc's `ScreeningOutcomeListener` composed in `app` over the beneficiary, and the customer's doors under `/v1/me/cross-border/beneficiaries`.
- **As built by `P9-TSK-018`:** the offer (`OfferIssuance`, `OfferStore`, `crossborder V004`'s `offer_request` and `payment_offer`), `CrossBorderFx` as built (`begin` on crossborder's connection under crossborder's claim, `firmQuote` on the wire, `issue` in crossborder's Tx2, `read`; `app`'s `FxCrossBorderQuotes` over fx's `QuoteIssuance` and `QuoteLifecycle`, fx's quote request gaining its `PricingPurpose`), the screening port gaining `clearance` and `rescreenWithin`, the listener naming the beneficiary by its screening's request reference (a decided re-screen becomes the current clearance), and the customer's `POST /v1/me/cross-border/quotes` and `GET .../{id}`.
- **As built by `P9-TSK-019`:** the payment's authorization and dispatch (`PaymentAuthorization`, `PaymentStore`, `crossborder V005`'s `payment` and `payment_event`), `CrossBorderExecution` as built (`route`, `dispatchWithin`, `dispatched`, `renewPermit`, `send`, `recordSend`; `app`'s `PaymentsCrossBorderExecution` over payments' routing, rails, `OutboundCreditStore` and evidence and ledger's `HoldService`), `CrossBorderFx.acceptWithin` and `dispatchCover` (`app`'s `FxCrossBorderQuotes` over fx's `CrossBorderAcceptance` and `FxCoverDispatch`), the Phase 13 seams wired as `PermitAllUntilPhase13`; payments `V025`'s `outbound_credit`, routing's third subject and the seeded routing version 5 (the corridor credit ahead of the domestic bank pay-out); the doors `POST /v1/me/cross-border/payments` (`202`) and `GET .../{id}`. The outcome appliers are `P9-TSK-020`'s.
- **As built by `P9-TSK-020`:** the outbound credit's resolution and completion - payments' `OutboundCreditOutcomes` (the one applier of every answer), `OutboundCreditResolution` (the sweep and the hinted inquiry) and the `OutboundCreditComposition` port, implemented in `app` as `CrossBorderCompletion` over crossborder's `PaymentProgress` (the payment's `IN_TRANSIT`, `DELIVERED` and `FAILED` edges) and fx's `CrossBorderCompletionBooking` (the completion's lines through `ConversionLines.composeCrossBorder`, the trade booked, the quote `EXECUTED` or `ABANDONED`); payments `V026` (the claim's `OUTBOUND_CREDIT` subject); the `CROSSBORDER_PAYOUT` kind and `PAYOUT_PROVIDER_REF` on payments' expectation port, its opening carrying the corridor counterparty; the corridor callback door `POST /v1/providers/payments/corridor/webhooks` (`FINAPP_CORRIDOR_WEBHOOK_KEY`); the schedule `OutboundCreditResolutionSchedule`. fx's plan replay learns the cross-border entry (the fee read off it, the clearing in place of a destination wallet) and prices under the quote's purpose.
- **As built by `P9-TSK-021`:** the unwind - fx's `CoverUnwinds` (the wanted-position rule, evaluated by `CrossBorderCompletionBooking.abandon` and by `FxCoverOutcomes` on executing an unwanted cover), the unwind's first price in `FxCoverDispatch`, `CoverLines.Plan.reversed` (the unwind closes its quote's plan reversed, so the cover's lines and the books proof are unchanged), fx `V008` (the unwind's birth as an executed cover's mirror, its execution's reversed plan legs); the requote's band terms now the quote's own pair under its purpose.
- **As built by `P9-TSK-022`:** the corridor's settlement to cash over the existing settlement and reconciliation machinery; `app`'s `JdbcInternalReferenceLookup` gains the corridor family (an `E` through payments' `OutboundCreditStore`, a provider reference through the completion's claim); reconciliation's `BreakSeverity` grades an unsettled `CROSSBORDER_PAYOUT` `HIGH`; the end-to-end reference is minted letters only (`PaymentsCrossBorderExecution.lettersOnly`).
- **As built by `P9-TSK-023`:** cross-border returns - payments `V027`'s `outbound_credit_return` (born once per credit, `applied_by` `APPLIER` | `RESOLUTION`, the applier-amount trigger) and its store; payments' `OutboundCreditReturns`, the one applier of a return from evidence (the entry `crossborder-return:<id>`, the fact, the payment `RETURNED` through `OutboundCreditComposition.returnLines/returned`, the `CROSSBORDER_RETURN` expectation), called by `OutboundCreditOutcomes` on an inquiry's `Returned` answer and by `app`'s `OutboundReturnWorker` (with `OutboundReturnSchedule`) on the corridor report's `PAYOUT_RETURNED` items, read through a second, corridor-scoped `WaitingPayoutReturns` (`waitingCorridorReturns`); crossborder's `PaymentProgress.returned`; reconciliation's `ResolvedCorridorReturns` port, consulted by `ResolutionMachine` inside a `TRANSFER_TO_ACCOUNT`'s proposal and four-eyes approval and implemented in `app` as `CorridorReturnResolutions` (the fact `RESOLUTION`, the fee refund `crossborder-return-fee:<id>`, the payment `RETURNED`).
- **As built by `P9-TSK-024`:** cancellation by recall - crossborder `V006`'s born-once `cancellation_request` and its store, crossborder's `PaymentCancellation` (the recall request in one transaction, through `CrossBorderExecution.requestRecall`, implemented in `app` over payments' `OutboundCreditStore`), the door `POST /v1/me/cross-border/payments/{id}/cancellation`; payments' recall facts on the credit (set once by conditionals) and `OutboundCreditOutcomes.applyRecallAnswer`, asked by `OutboundCreditResolution`; the customer-facing `CANCELLED` shape; `finapp.crossborder.cancellation{outcome}` counted by `app`'s `CrossBorderCompletion` through the composition's `recallAnswered`.
- **As built by `P9-TSK-025`:** the operator's FX trade reversal - fx `V009`'s four-eyes `trade_reversal` and its history, fx's `TradeReversals` (propose, approve - executing the exact mirror through ledger's `ReversalService`, the trade `REVERSED`, `CoverUnwinds` evaluated - and reject), the trade store's operator read, lock and `BOOKED -> REVERSED` edge; `identity`'s `FX_TRADE_REVERSE` held by `LEDGER_OPERATOR`; `app`'s `FxTradeReversalController`/`Desk`; the books proof counting `BOOKED` trades only.
- **As built by `P9-TSK-026`:** the second provider of each kind (M9.8) - `fx-sim-b` and `corridor-sim-b` as
  declarations over the existing simulated adapters (parameterised by code and by rail), each with its own
  confined key and transport row; ledger `V025` (their registry rows and clearing accounts), settlement `V017`
  (their sources); `RailOperations` composed from every configured corridor adapter; each counterparty's own
  remittance shape (`SettlementBeans.remittanceLetterOf`, the formats' `remittanceReference(letter)`) and the
  register's refusal of a shared one; reconciliation's source-to-rail mapping per counterparty
  (`ReconciliationBeans.railOfSource`). No domain change: quote-time failover and corridor selection were on the
  main line.
- **As built by `P9-TSK-027`:** Phase 9 operated by counts, ages, verdicts and audited reports - `app`'s
  `CrossBorderMetrics` (per corridor, eager, after commit; the in-transit age a database gauge), `Phase9Spans` with
  the `SpannedFxProvider`/`SpannedCorridorRail` decorators, `Phase9Reports` and `Phase9ReportController` (the three
  reports and the trace, `FX_INVESTIGATE`, audited), the alerts in `infra/prometheus/rules/fx.yml` and the FX and
  cross-border dashboard row.
- **Ports:** declares `CrossBorderFx` (implemented in `app` as `FxCrossBorderQuotes` over `fx`: `begin` on crossborder's connection under crossborder's claim, `firmQuote` on the wire, `issue`, `read`, `acceptWithin` in the authorization's transaction, `dispatchCover` after its commit), `CrossBorderExecution` (`app`'s `PaymentsCrossBorderExecution` over `payments`: `route`, `dispatchWithin`, `dispatched`, `renewPermit`, `send`, `recordSend`, `requestRecall`) and `CorridorDirectory` (`app`'s `RailDirectory`: the declared rails, operability, the provider grant exchange), `CounterpartyScreening` (`app` over `kyc`'s `CounterpartyScreenings`: `requestWithin`, `screenNow`, `rescreenWithin`, `clearance`), the two Phase 13 seams (its own `PermitAllUntilPhase13`) and its own stores; there is **no `CrossBorderParticipants` port** — a return opens the customer's wallet through fx's `ConversionParticipants` inside `app`'s `CrossBorderCompletion` and `CorridorReturnResolutions`. Completion and abandonment are not `CrossBorderFx` calls: they run through `payments`' `OutboundCreditComposition` → `app`'s `CrossBorderCompletion` → crossborder's `PaymentProgress` and fx's `CrossBorderCompletionBooking` (`book`, `abandon`); through `app`'s compositions it implements `kyc`'s `ScreeningOutcomeListener` (`CrossBorderBeneficiaryBeans`) and its share of `payments`' `OutboundCreditComposition` and `reconciliation`'s `ResolvedCorridorReturns`.
- **Depends on `ledger`, `platform` and `sharedkernel` only** (`CrossborderModuleIsolationTest`, with planted probes): no edge to `fx`, `payments`, `kyc` or `accounts`.
- **Invariants:** `INV-XB-01`…`-04`, `INV-RAIL-02`, `INV-RAIL-03`, `INV-REV-03`, `INV-HIST-04`, `INV-IDEM-01`.
- **Hard rule:** a cross-border payment is never reversed (`INV-REV-03`) — a return is the receiving side's act, admitted whenever it arrives; no customer line exists for a payment that is not `IN_TRANSIT`, `DELIVERED` or `RETURNED`; and the module names no `*_CLEARING` purpose (`INV-RAIL-04`).

### `credit` — Phase 10
- **Responsibility:** credit decisions the platform can defend years later, to a regulator or a declined applicant — a decision is a recorded, immutable fact, reproducible from a frozen snapshot of the data it used, evaluated by a versioned policy and a versioned model through a deterministic engine, carrying the ordered reason codes that explain it. The credit profile, the collection of credit data from provider-neutral bureau and financial-data adapters under recorded consent, the affordability and exposure assessments, the scorecard, the policy engine, the decision and its explanation, manual underwriting and the replay proof. **It moves no money**: no ledger posting, no hold, no disbursement — it stops where lending begins (ADR-0084).
- **Owns:** Credit Profile, Credit Data, Credit Data Source, Credit Bureau Record, Credit Attribute, Decision Request, Decision Snapshot, Credit Assessment, Affordability Assessment, Credit Score, Exposure, Credit Policy, Policy Version, Model Version, Reason Code, Credit Decision, Underwriting Case, Credit Product, data request and attempt history, credit evidence, policy evaluation and its triggered rules, the decision consumption fact (written only by Phase 11).
- **Phase 10** *(planned by the Phase 9 → 10 transition, 2026-10-07, ADR-0084…0089 (`Proposed`), `PHASE_10_PLAN.md`; built so far: `P10-TSK-001` (2026-10-07) — the module with edges to `platform` and `sharedkernel` only, the schema floor (`V001`), the immutable `reason_code` catalogue (`V002`, fourteen codes, `SELECT` only, refused to every writer by trigger) and the closed vocabularies `CreditProduct`, `CreditAttributeCode`, `ReasonCode` and `DecisionOutcome`; `P10-TSK-002` (2026-10-07) — `CreditSourceKind` (`BUREAU`, `FINANCIAL_DATA`) and the `CreditConsentGate` port, implemented in `app` by `ConsentBackedCreditConsentGate` (the kind-to-purpose mapping, unwired until `P10-TSK-006`); `P10-TSK-004` (2026-10-07) — the `credit_profile` (`V003`: one row per party, no figure, immutable by trigger, `UPDATE (party_id)` granted only to make the lock takeable) and `CreditProfiles` (`ensure`, `lockForDecision` - lock-order element (1)), unwired until `P10-TSK-014`; `P10-TSK-005` (2026-10-07) — the `CreditBureau` port (`BureauRequest`, the sealed `BureauAnswer` - `Received`, `Partial`, `Unavailable` - `CreditAttribute` with its typed `AttributeValue`, `Absent` included, and `AttributeProvenance`, `CreditEvidence`) and `app`'s `SimulatedBureauAdapter` (`bureau-sim-a`, normaliser version 1, its wire confined by `CreditProviderVocabularyIsConfinedTest`), unwired until `P10-TSK-006`; `P10-TSK-006` (2026-10-07) — bureau data collection: `V004`'s `data_request` (its machine by trigger, every window on the database clock), `data_request_attempt`, the born-once `credit_record` with its typed `credit_record_attribute` rows, and `credit_evidence` (AES-256-GCM under credit's own key, the evidence id as associated data, `INSERT` only to the application, read only through the reasoned `credit.read_evidence` definer function); `CreditDataCollection` (Tx1 in the caller's transaction, the ask holding no connection, Tx2 under the row lock with the gate re-read), the leaderless `CreditDataRetrySchedule`, `CreditDataCollected` and `CreditDataUnavailable`, `credit.BureauDataRequested`; the bureau fail-safe (`UnconfiguredBureau`) until party facts exist (unresolved question #13); `P10-TSK-007` (2026-10-07) — the source-neutral port: `CreditDataSource` (`code`, `kind`, `attributes`, `pull`), `CreditDataAnswer` and `CreditDataPull` (renamed from the bureau's), `CreditBureau` and `FinancialDataProvider` its kinds, `CreditDataCollection` collecting from a configured source per kind and auditing each kind's own act (`credit.FinancialDataRequested`); `app`'s `SimulatedFinancialDataAdapter` (`findata-sim-a`), fail-safe in production until the account connection exists (unresolved question #14); `P10-TSK-008` (2026-10-08) — the decision snapshot: `V005`'s `decision_snapshot` (born once per (request, sequence), its SHA-256 recomputed by a `CHECK`, never changed), `CanonicalSnapshot` (format 1, hand-written and strictly parsed back), `SnapshotContent` (every code held once, a missing read an error), the completed `AttributeProvenance` (`Record`, `Unavailable`, `NotRead`, `Declared`, `Port`), `SnapshotFreezer` (freshness on the freezing transaction's database instant, a stale record re-collecting, an unavailable source `ABSENT` with its marker) and the `CreditPartyStanding`, `CreditRiskSignal` and `ReservedExposure` ports; `app`'s `PartyFactsNotHeld` (both facts `ABSENT` until #13 is answered) and `NotAssessedUntilPhase13`, unwired until `P10-TSK-015`; every other statement here is the decided design, corrected by the tasks that build it)*: one module, one consistency boundary — data collection was weighed as a second module and refused (ADR-0084 §2): its only consumer is credit's own snapshot, the evidence's security boundary is a table grant inside one schema, and a second module would put the freshness and consent judgements across a port from the decision that depends on them. Inside it the concepts stay apart, each its own aggregate, table and lifecycle: the **Decision Request** (`SUBMITTED → COLLECTING → READY → EVALUATED → DECIDED`, `EVALUATED → IN_REVIEW → DECIDED`, the one backward edge `READY → COLLECTING` for a record found stale at the freeze; the policy and scorecard versions pinned at `SUBMITTED → COLLECTING`; closed `CANCELLED` by the applicant before evaluation, `EXPIRED` when no decision came within its validity (the product's, 7 days) on the database clock, or `ABANDONED` by the platform with a reason — `STANDING_LOST`, `CONSENT_WITHDRAWN`; one open per (party, product) by partial unique); the **data request** per source (`REQUESTED → RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN`, `UNAVAILABLE → REQUESTED` until the source's deadline, `UNAVAILABLE → CONSENT_WITHDRAWN`; deadline and retry cadence stamped from configuration at birth) and its born-once Credit Bureau Record or financial-data record; the **Decision Snapshot** — one per evaluation, `UNIQUE (decision_request_id, sequence)`: the first at the freeze, a successor only when the deciding transaction finds reserved exposure changed — and the born-once **Credit Assessment** (`UNIQUE (snapshot_id)`), policy evaluation (`UNIQUE (assessment_id)`) and **Credit Decision** (`UNIQUE (decision_request_id)`, naming its snapshot); the **Underwriting Case** (`OPEN → ASSIGNED → DECIDED`, `ASSIGNED → AWAITING_SECOND → DECIDED`, `ASSIGNED → OPEN`, `AWAITING_SECOND → ASSIGNED` when the second approver refuses, and the terminal `CLOSED` when its request closes undecided); the born-once **decision consumption** fact (`credit_decision_consumption`, `UNIQUE (decision_id)`, created empty, Phase 11 its only writer — the decision itself is never updated); and the **Policy Version** and scorecard **Model Version** (`PROPOSED → ACTIVE → RETIRED`, `PROPOSED → REJECTED`, the pricing policy's shape). The machines are in `CREDIT_DECISIONING_LIFECYCLES.md`; the invariants `INV-CRD-01`…`12`. *This entry was rewritten by the transition: it owned "Risk Score", which moves to `risk` (Phase 13; credit consumes a signal through the `CreditRiskSignal` seam — the glossary's §10); "Bureau request/response evidence", "Rule", "Decision (immutable)" and "Decision Input Snapshot" are now the data request, the Credit Policy's rule rows, the Credit Decision and the Decision Snapshot; its events `CreditProfileUpdated`, `BureauDataRetrieved` and `PolicyVersionActivated` are replaced by the plan's §10 set (the profile holds no figures, so it has nothing to update); and affordability, exposure and the manual review are named.*
- **Transaction:** own. Every credit writer follows **the Phase 10 lock order** (`DISTRIBUTED_EXECUTION.md` §3): the party's `credit_profile` row `FOR UPDATE` (deciding transactions only), the `decision_request` row, the `underwriting_case` row, the request's data requests by id, the pinned policy and model versions `FOR SHARE` — then only inserts. **The deciding transaction is profile-first** (ADR-0087 §5): lock the profile, lock the request (`EVALUATED`, unexpired on the database clock), re-read the party's standing and the consent gate (either lost abandons the request), read the pinned versions `FOR SHARE`, re-read the party's reserved exposure under the profile lock and, if it differs from the snapshot's, freeze a successor snapshot and re-assess and re-evaluate; insert the decision with its reason codes, move the request `DECIDED`, emit `CreditDecisionRecorded` and audit — one commit. A referral opens the case and moves the request `IN_REVIEW` instead; the person's decision runs the same deciding transaction, bounded by the evaluation's approved amount and the re-read exposure limit. The case's assignment, release and refusal of a second approval lock the request, then the case, so they serialise with the request's expiry. A provider is asked with no connection held; its answer is recorded under the data request's row lock, the consent gate re-read in that transaction. The snapshot, assessment and evaluation commit together with the request's move, or the decision is not reproducible. No credit transaction takes a lock outside `credit`; the consent and standing reads are plain authoritative reads in the acting transaction.
- **Consistency:** strong. A recorded decision never changes, for any role (`INV-CRD-02`); a decision request has at most one decision, each of its snapshots at most one assessment and one evaluation, and the decision names the snapshot it was made from (`INV-CRD-06`); decisions for one party serialise on the profile row, so concurrent approvals never together exceed the policy's exposure limit (`INV-CRD-09`); the pinned versions never change under a decision (`INV-HIST-04`).
- **APIs:** the customer's `POST /v1/me/credit/decision-requests` (keyed `credit.decision:CUSTOMER:<id>`, MFA-assured session, `202` — the decision arrives later), its read (own requests only, another party's `404`; when decided the outcome and the adverse reasons' customer texts in order, never a score, threshold, attribute or bureau datum), its cancellation (before evaluation) and `GET /v1/me/credit/profile`; the operator's explanation and replay (`CREDIT_INVESTIGATE`, audited), the reasoned evidence read (`POST /v1/operator/credit/records/{id}/evidence-read`, `CREDIT_INVESTIGATE`, audited), the review queue — take, release, decide, second-approve or refuse the second approval (`CREDIT_UNDERWRITE`, keyed `credit.review:EMPLOYEE:<id>`), policy and scorecard propose, approve and reject (`CREDIT_POLICY_ADMINISTER`, four-eyes, keyed), the policy-active-at-an-instant read and the audited outcomes, reasons and sources reports. Errors `credit.DecisionRequestOpen`, `RequestNotCancellable`, `CaseTaken`, `PolicyStale`, `ProposalPending` (`409`), `ProductNotOffered`, `AmountOutOfRange`, `HardDeclineNotOverridable`, `ReasonRequired`, `PolicyIncomplete`, `ScorecardInvalid`, `ExposureLimitExceeded` (`422`), `ConsentRequired`, `SelfApprovalRefused` (`403`), each catalogued by its task; a source in another currency is not an error but recorded partial data (`CURRENCY_NOT_SUPPORTED`).
- **Events:** `CreditDecisionRequested`, `CreditDataCollected`, `CreditDataUnavailable`, `CreditAssessmentCreated`, `ManualReviewRequired`, `CreditDecisionRecorded`, `CreditDecisionRequestClosed`, `CreditPolicyVersionActivated`, `ScorecardModelVersionActivated` — through the outbox, in the state change's transaction, identifiers, versions, outcomes and reason codes only: never an attribute, a figure or a payload. **Not built, by decision:** `UnderwritingStarted` (an audited internal step nobody consumes) and `CreditDecisionUpdated` (a decision is never updated). No Phase 10 consumer exists outside `credit`, and credit consumes no event; Phase 11 will consume `CreditDecisionRecorded`.
- **Failure:** a provider's unavailability or partial answer never becomes an approval — a source unavailable past its deadline leaves its attributes `ABSENT` and fires the policy's declared fallback (`REFER` or `DECLINE`, reason `CRD-SOURCE-UNAVAILABLE`), and a policy lacking that fallback for every source it reads is refused at proposal (`INV-CRD-10`); malformed data and an unknown provider status are `UNAVAILABLE` with the evidence kept, never parsed into attributes (`INV-LIFE-03`); a lost response is re-asked under the same reference, so the provider counts one pull; a duplicate answer makes one record; consent withdrawn mid-pull, or before a retry, yields `CONSENT_WITHDRAWN` with the payload discarded unread; stale data at the freeze is re-collected, judged on the database clock (`INV-CRD-08`); consent withdrawn after a source answered (the data request stays `RECEIVED`; the gate re-read at the freeze or in the deciding transaction finds it), or the applicant's standing lost, before the decision closes the request `ABANDONED` with that reason, nothing decided — never `EXPIRED`, which means only that no decision came within the validity; a crash at any step leaves the request in a state the progress sweep re-drives, every step idempotent; request expiry and decision at the boundary are complementary conditionals on the database clock, exactly one of `DECIDED`/`EXPIRED`; a policy or scorecard activated mid-decision does not change the decision's pinned versions.
- **Security:** no external credit data is retrieved without a recorded, current lawful basis — the consent gate checked when the data request opens, at every retry and again when its answer is recorded, and re-read at the freeze and in the deciding transaction, over two new purposes `CREDIT_BUREAU_ACCESS` and `FINANCIAL_DATA_ACCESS` (`INV-CRD-03`); a request without consent is refused `403 credit.ConsentRequired` before any provider is asked; the party's standing (`ACTIVE`, KYC `VERIFIED`) checked in-transaction; raw evidence encrypted under its own key purpose `credit-evidence` with `retain_until` stored, the application role unable to `SELECT` it — read only through the audited evidence-read door's definer function with a reason; bureau data and every attribute `RESTRICTED-FINANCIAL`, never in a log, metric tag, span, event or exception message; three permissions — `CREDIT_POLICY_ADMINISTER` and `CREDIT_INVESTIGATE` under `CREDIT_POLICY_OFFICER`, `CREDIT_UNDERWRITE` under `UNDERWRITER` (identity `V020`); four-eyes on every policy and model activation and on a review approval above the product's threshold, at the domain and the `CHECK` (`INV-CRD-11`); a person never overrides a hard decline; every provider access and privileged act audited. No purge runs in Phase 10 — crypto-shredding is Phase 15's, recorded as a debt.
- **Operations:** `finapp.credit.decision` (product, outcome, policy version, decided by), `.decision.latency`, `.reason`, `.data.request` (source kind, provider, outcome — the unavailability ratio alerting; per provider, the bureau-cost proxy, no money cost series in Phase 10), `.data.latency`, `.request.open.age`, `.review.age`, `.replay` (any `DIVERGED` alerting), `.policy.active`, and the two `…sweeper.enabled` gauges — counts, ages and verdicts, never an amount, score, attribute or party in a tag (ADR-0072). Two leaderless schedules in `app`: `CreditDecisionProgressSchedule` (requests due a step; expiry) and `CreditDataRetrySchedule` (data requests `UNAVAILABLE` or past their permit). `CreditReplayProof` replays every decision per reading.
- **Providers:** provider-neutral ports for credit bureaus and financial-data providers (ADR-0008, ADR-0085), each with a simulated adapter held to the port's contract suite and normalisation golden files; a second bureau and source selection is `P10-TSK-021`, the phase's first cut. No real bureau connectivity.
- **Ports:** declares `CreditBureau` and `FinancialDataProvider` (the provider-neutral data ports), `CreditConsentGate` (implemented in `app` over `consent`'s gate) and `CreditPartyStanding` (the party's standing and the party facts a snapshot records, implemented in `app` over `kyc`'s existing customer-standing port), `CreditRiskSignal` (Phase 10's composition answers `NOT_ASSESSED`, recorded with the seam's version; Phase 13's `risk` implements it) and `PlatformCreditExposure` (answers zero, recorded, until Phase 11's loans); publishes `CreditDecisions`, the decision-read port Phase 11's `lending` will use (no consumer in Phase 10).
- **Depends on `platform` and `sharedkernel` only** (`CreditModuleIsolationTest`, ADR-0084 §3): no edge to `consent`, `kyc`, `party` or `ledger`, and no module depends on `credit` in Phase 10.
- **Invariants:** `INV-CRD-01`…`12`, `INV-HIST-04`, `INV-IDEM-01`…`03`, `INV-CON-02`, `INV-AUD-01`…`04`, `INV-CNS-01`…`02`, `INV-MON-01`…`02`, `INV-LIFE-03`.
- **Hard rule:** credit decides and never lends — no loan application, offer, disbursement, interest or posting (Phase 11); no risk score or fraud rule (Phase 13 — only the seam); a score is never a decision, and an adverse decision without a reason code is unrepresentable.

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
- **Owns:** Signal, Rule Set (versioned), Risk Assessment, Risk Score, Risk Decision, Limit definition, Velocity Counter, Alert, Case, Case Action.
- **Risk Score moved here** *(the Phase 9 → 10 transition, 2026-10-07, ADR-0084 (`Proposed`), `PHASE_10_PLAN.md` §3)*: until then the register listed it under `credit`, the question the glossary's §10 left to "Phase 10 or 13". A risk score answers a fraud question, from fraud inputs, with a fraud consequence, so it is this module's. `credit` consumes a risk signal as one decision input through its `CreditRiskSignal` port — answered `NOT_ASSESSED` for every party by Phase 10's composition, deterministically and recorded in the decision's snapshot — and this module implements that port in Phase 13 without changing any earlier decision's replay.
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
| KYC/KYB Case, Verification Check, Screening Result, Beneficial Owner, Risk Rating, Counterparty Screening (`P9-TSK-016`) | `kyc` | `party` may project verification *status* (non-authoritative) |
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
| Pricing Policy (with pair and provider availability), Exchange Rate snapshot, FX provider evidence, FX Quote, FX Trade, FX Cover, Trade Reversal | `fx` | `FX_POSITION` is `ledger`'s account per currency, explained by `fx`'s proof, never a table here; minor units are the JDK's *(this row read "FX Position, Currency config" until the Phase 9 exit review, `P9-DOC-001`)* |
| Corridor Policy (with corridor availability), Cross-Border Beneficiary, Corridor Selection, Payment Offer, Cross-Border Payment, Cancellation Request | `crossborder` | The beneficiary's screening state mirrors `kyc`'s Counterparty Screening; the Outbound Credit is `payments`' |
| Credit Profile, Credit Data (data requests, bureau and financial-data records, encrypted evidence), Decision Request, Decision Snapshot, Credit Assessment, Credit Score, Exposure, Policy Version, Model Version, Reason Code, Credit Decision, Underwriting Case *(planned, Phase 10 — the Phase 9 → 10 transition, 2026-10-07)* | `credit` | The risk signal a decision records is `risk`'s answer through the `CreditRiskSignal` seam, never credit's own score; the reserved exposure is summed from credit's own decisions, never stored as a balance *(this row read "Credit Profile, Bureau evidence, Score, Policy Version, Decision, Exposure" until the transition; Risk Score moved to `risk`)* |
| Loan Application, Loan Offer, Loan, Repayment Schedule, **Loan Instalment**, Accrual Record, Repayment, Allocation, Delinquency State | `lending` | Loan balance derived from `ledger` |
| BNPL Agreement, Instalment Plan, **BNPL Instalment**, Merchant Financing record, Refund Adjustment, Late Fee | `bnpl` | References `merchant` and `lending` by id; owns neither |
| Signal, Rule Set, Risk Assessment, Risk Score, Risk Decision, Limit, Velocity Counter, Alert, Case, Case Action | `risk` | `credit` records the risk signal it was given in a decision's snapshot (a copy, never a second owner) *(Risk Score moved here from `credit` by the Phase 9 → 10 transition, 2026-10-07)* |
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

**Generic signatures are walked to the end.** The field and signature rules follow type
arguments, array components, wildcard bounds and type-variable bounds, so `List<Double>` and
`<T extends Supplier<Double>>` are violations rather than `List` and `T`. Each type variable is
walked once: a bound may name its own variable (`E extends Enum<E>`), and following it again
never ends - a `P8-TSK-014` helper crashed the suite with `StackOverflowError` that way until
the walk was made cycle-safe. Skipping only the repeat, never the variable's other bounds, keeps a
`Double` behind the cycle in view; both halves are proven in-suite by recursive-bound fixtures.

**The exemptions stay off money** (ADR-0072 point 1, `P8-TSK-024`). An exempt class publishes
counts, ages and verdicts, so it may not depend on `Money` or `MoneyColumns` - the types an
amount travels in. A `CurrencyCode` stays permitted, since a currency is a tag value. The
known edge: a store method returning a monetary `long` passes, which is why each exemption's
argument names what it counts. *(ArchUnit: `noExemptClassDependsOnMoney`)*

**Wallets are resolved by currency** (ADR-0076 §6, `P9-TSK-004`). A product holds one
`CUSTOMER_WALLET` per currency, so no production method that reads a product's owned accounts
(`LedgerAccountStore.findAllOwned`/`lockOwnedForUpdate`) or names `CUSTOMER_WALLET` may pick
one with `Stream.findFirst()`/`findAny()`; the one rule is `WalletAccounts.resolve`. Its anchor is
proven real at the resolvers, and planted picks are refused in-suite. The stated limit: a pick
split across two methods is not seen. *(ArchUnit: `noWalletIsPickedByFindFirst`, and its
non-vacuity guard `theAnchorIsReal`)*

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
