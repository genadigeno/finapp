# Phase Gates

Formal entry and exit control for every phase in
[`DELIVERY_PLAN.md`](DELIVERY_PLAN.md).

A phase is **not** complete because code compiles, because tests pass, or because a demo
works. Completion is defined by the gate below.

---

## 1. Status Model

Every phase, epic and task carries exactly one status.

| Status | Meaning | Entry condition | Permitted transitions |
|--------|---------|-----------------|----------------------|
| `PLANNED` | Defined in the delivery plan; not yet assessed for readiness | Exists in `DELIVERY_PLAN.md` | → `READY`, → `BLOCKED` |
| `READY` | Entry gate passed; work may begin | Entry gate §2 satisfied | → `IN_PROGRESS`, → `BLOCKED` |
| `IN_PROGRESS` | Active implementation | A task has started | → `IN_REVIEW`, → `BLOCKED` |
| `BLOCKED` | Cannot proceed | Blocker recorded in `CURRENT_STATE.md` with owner and required resolution | → previous status once resolved |
| `IN_REVIEW` | Implementation believed complete; formal review under way | All exit-gate items claimed complete | → `COMPLETE`, → `IN_PROGRESS` |
| `COMPLETE` | Exit gate passed and recorded | Exit gate §3 and phase-specific §5 satisfied | terminal (reopen requires an ADR) |

Rules:

- Exactly one phase may be `IN_PROGRESS` or `IN_REVIEW` at a time.
- A phase may not become `READY` while any hard dependency is not `COMPLETE`.
- `IN_REVIEW` → `COMPLETE` is an explicit decision recorded in `CURRENT_STATE.md`. It never
  happens implicitly by finishing a task.
- Moving backwards from `IN_REVIEW` to `IN_PROGRESS` is normal and expected. It is not a
  failure; shipping through a failed gate is.

---

## 2. Universal Entry Gate

A phase may become `READY` only when **all** hold:

1. All hard dependency phases are `COMPLETE`.
2. The phase's section in `DELIVERY_PLAN.md` is current and specific — not aspirational.
3. Bounded contexts and aggregates in scope are identified.
4. The invariants the phase must protect are identified by ID from
   [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md).
5. The lifecycle/state machines in scope are drafted.
6. Transaction and consistency boundaries are stated.
7. Idempotency requirements are stated for every money-moving command in scope.
8. External dependencies and their failure modes are listed.
9. Security, audit and reconciliation implications are stated.
10. Backlog items exist at task granularity with acceptance criteria.
11. Any architectural decision required to start has an ADR in at least `Proposed` status.
12. `CURRENT_STATE.md` names the phase as the active phase.

---

## 3. Universal Exit Gate

A phase may become `COMPLETE` only when **all twelve** hold. Each must be demonstrable, not
asserted.

| # | Criterion | Evidence required |
|---|-----------|-------------------|
| 1 | Required functionality exists | Every phase deliverable implemented and exercisable end to end |
| 2 | Architectural boundaries respected | Boundary tests pass; no cross-module violation; no undeclared dependency |
| 3 | Required invariants tested | Every in-scope `INV-*` has at least one test that fails if the invariant is broken |
| 4 | Failure cases handled | Every failure scenario in the phase's §12 has a test or a documented, accepted rationale |
| 5 | Security requirements implemented | Authn/authz enforced and negatively tested; no secrets in source; sensitive data classified |
| 6 | Observability exists | Metrics, traces and structured logs for the phase's critical flows; correlation propagated |
| 7 | Integration tests pass | Full suite green against real infrastructure via Testcontainers |
| 8 | Documentation reflects reality | Domain/architecture docs match the implementation, not the intention |
| 9 | `CURRENT_STATE.md` updated | Accurately describes completed capability and next phase |
| 10 | Relevant ADRs exist | Every architectural decision taken during the phase is recorded and `Accepted` |
| 11 | No unresolved critical issues | Known-issues list contains no `critical` or `high` severity item |
| 12 | Formal phase review conducted | Review record written against `DEFINITION_OF_DONE.md` |

### Financial-phase supplement

Any phase that can affect money, balances, settlement, fees, credit exposure or accounting
(Phases 3, 4, 5, 6, 7, 8, 9, 11, 12, 14) additionally requires:

| # | Criterion |
|---|-----------|
| F1 | Trial balance sums to zero per currency across all postings created by the phase |
| F2 | Every balance touched is reproducible by replaying postings from zero |
| F3 | Every money-moving command has a tested idempotency guarantee at the financial boundary |
| F4 | Every reversal/compensation path is implemented and tested; no path mutates history |
| F5 | Duplicate external event delivery is proven to produce no second financial effect |
| F6 | Concurrency tests exist for every contended financial resource |
| F7 | No floating-point type appears anywhere in a monetary code path (statically verified) |
| F8 | Reconciliation implications are implemented or explicitly deferred with a named owning phase |

---

## 4. Phase Review

Conducted at `IN_REVIEW`, before `COMPLETE`. Output is a written record appended to
`CURRENT_STATE.md` history or stored under `docs/project/reviews/`.

The review covers, in order:

1. Domain correctness — do the implemented concepts match the domain model?
2. Financial correctness — walk one real posting end to end: economic event → domain
   operation → financial transaction → journal entry → lines → balances.
3. Boundary integrity — did any module reach into another's state?
4. Failure behaviour — pick two failure scenarios and trace them through the code.
5. Security — enumerate privileged actions and confirm each is authorised and audited.
6. Test quality — do the tests fail when the invariant is deliberately broken?
7. Documentation drift — diff docs against implementation.
8. Architectural debt — record anything knowingly deferred, with the phase that owns it.

A review that finds a gate failure returns the phase to `IN_PROGRESS`.

---

## 5. Phase-Specific Exit Criteria

These are **in addition** to §3 (and the financial supplement where applicable).

### Phase 0 — Domain and Architecture Foundation
- Multi-module Gradle build green in CI from a clean clone.
- ArchUnit rules fail the build on a deliberately introduced boundary violation (verified by
  temporarily introducing one).
- `Money` covered by property tests: associativity of addition, currency-mismatch rejection,
  rounding correctness for 0/2/3 minor-unit currencies, overflow rejection.
- No floating-point type in any monetary path — statically enforced.
- Idempotency kernel: concurrent identical keys produce one effect; same key with a
  different request fingerprint is rejected.
- Outbox: process killed between commit and publish; relay recovers and publishes exactly the
  committed events; consumer sees at-least-once with dedupe.
- Inbox: duplicate inbound event produces one effect.
- Audit table rejects `UPDATE` and `DELETE` at the database privilege level.
- One request produces a correlated trace, log line and event carrying the same
  `correlationId`.
- ADR-0001..ADR-0010 all `Accepted`.

### Phase 1 — Identity and Customer Foundation
- Every protected endpoint has a passing negative authorization test.
- Credentials verifiably never appear in logs, events or API responses.
- MFA cannot be bypassed by any tested path; session revocation is immediate and tested.
- Account recovery cannot be used to take over an account under the tested abuse cases.
- Every privileged action produces an audit record with actor, time, operation, target,
  correlation and outcome.
- Party, Customer and Identity are separately persisted with distinct lifecycles.

### Phase 2 — KYC/KYB and Consent
- KYC state machine rejects every invalid transition (exhaustively tested).
- Provider verdict is stored as evidence and is never itself the decision.
- Duplicate provider callback produces no duplicate decision.
- Consent withdrawal demonstrably blocks the dependent capability.
- Documents are access-controlled, encrypted and every access is audited.
- Reviewer decisions require elevated authorization, a reason code, and are audited.

### Phase 3 — Accounts and Financial Ledger
The strictest gate in the programme.
- Unbalanced journal entry is impossible: rejected by the domain **and** by a database
  constraint.
- `UPDATE`/`DELETE` on a posted journal entry or line is denied at the database level.
- Balance recomputed from all postings equals the projection, verified continuously and
  under sustained concurrent posting.
- Concurrent postings to the same account produce no lost update (tested at the chosen
  isolation level).
- Hold cannot exceed available balance; release restores availability exactly.
- Reversal produces a new entry referencing the original; the original is byte-identical
  after reversal.
- Trial balance is zero per currency, asserted by an automated job with alerting.
- Posting is idempotent under concurrent identical keys.
- No module other than Ledger can write a posting (enforced by boundary test).

*Extended by the Phase 2 → 3 transition (2026-09-13), which found the list covered the ledger
and said nothing measurable about the account product, the authority to post, or the phase's own
observability — the three things a reader would check first:*

- A ledger account's type, normal balance and currency cannot be changed once a line references
  it (`INV-LED-06`), refused at the **database**, not only by the domain.
- A customer account can be opened only by a customer the KYC decision made `ACTIVE`, and
  closing it ends the agreement while leaving every posting intact (`INV-HIST-01`).
- Posting authority is a named permission with a passing negative test; a manual adjustment
  carries a reason code and is audited with its actor (`INV-REV-04`).
- Every balance-affecting decision (a hold, an overdraft refusal) is proven to derive its number
  **inside the account lock** rather than from the projection (`INV-BAL-05`, ADR-0041).
- The projection-drift and trial-balance metrics exist, are published by a freshly started
  instance, and report **absent rather than zero** when unreadable.
- A statement's opening balance, lines and closing balance reconcile (`INV-ACC-02`'s drill-down,
  early).
- `LEDGER_MODEL.md` describes the implemented model, and every `Phase: 3` invariant in
  `FINANCIAL_INVARIANTS.md` — **read from the catalogue, not from the phase plan** — has
  a mutation-register row.

### Phase 4 — Internal Transfers
- Transfer state machine rejects every invalid transition.
- Duplicate submission with the same idempotency key produces exactly one financial effect,
  proven under concurrent submission from two threads.
- Same key with a different payload is rejected with a distinct error, not silently accepted.
- Insufficient funds is a domain outcome with a defined state, not an exception leak.
- Ledger posting and transfer state transition are atomic, or the compensating path is
  implemented and tested.
- Limit and risk seams exist as interfaces with documented default behaviour and no Phase 13
  logic.

*Extended by the Phase 3 → 4 transition (2026-09-17): the original list predates ADR-0043/0044
— its "or the compensating path" branch is now decided (atomic, no compensating path exists to
implement), and it said nothing measurable about conservation, the lock, reversal,
beneficiaries, authority, observability or the register — the things this phase's review will
be judged on:*

- **Ten instances draining one account conserve value**: exactly the affordable transfers
  succeed, every loser is a committed `FAILED` domain outcome, the source is never negative,
  and the sum over both accounts is unchanged — counted in the tables, not inferred
  (`INV-CON-02` demonstrated).
- The atomicity branch is proven in ADR-0043's form: an injected failure at the last write of
  the execution transaction leaves **nothing** — no transfer row, no claim, no posting, no
  outbox row.
- The availability decision is proven to derive **inside the source account's lock** — the
  moved-outside-the-lock mutation (the `P3-TST-002` shape) is performed and caught.
- A reversal posts a new entry referencing the original, moves the transfer
  `COMPLETED → REVERSED` in the same transaction, leaves the original byte-identical, and a
  second reversal is refused at the aggregate **and** the ledger; reversal authority is a
  named permission with a passing negative test and a required reason.
- Beneficiary creation requires the second factor when one is enrolled, negatively tested;
  a removed beneficiary refuses new transfers while its row survives as evidence.
- The transfer surface carries the asynchronous-outcome contract shape (status on the
  response, a status query endpoint) even though execution is synchronous.
- The limit and risk seams are **required parameters** of the execution command — a caller
  that skips them does not compile — and their contracts state in-lock evaluation.
- The phase's meters are published by a freshly started instance; the transfer chain is
  traceable identifier-to-identifier (transfer ↔ entry ↔ reversal) with no timestamp join.
- Every `Phase: 4` invariant in `FINANCIAL_INVARIANTS.md` — **read from the catalogue, not
  from the phase plan** — has a mutation-register row, the transfers-context `INV-IDEM-01`
  row included.

### Phase 5 — Payment Infrastructure
- Provider timeout followed by a successful provider outcome is handled correctly and tested.
- `UNKNOWN` is a modelled state with a reconciliation-by-query sweeper that resolves it.
- Duplicate and out-of-order webhooks produce no duplicate financial effect.
- Webhook signature verification and replay-window rejection are tested.
- No provider vocabulary appears in the domain model or the public API contract (verified by
  review and boundary test).
- Raw PAN never persisted anywhere (verified by schema and code review).
- Every provider interaction retains external evidence sufficient for Phase 8.
- Refund cannot exceed the captured amount, including under concurrent partial refunds.

*Extended by the Phase 4 → 5 transition (2026-09-20): the original list predates
ADR-0045…0049 and said nothing measurable about the dispatch discipline, the accounting
treatment, conservation, the sweeper's multi-instance shape, the PCI boundary's mechanism,
or the register — the things this phase's review will be judged on:*

- **The ambiguity demonstration is end to end and counted**: a provider that succeeds while
  the response is lost leaves a committed `*_UNKNOWN`/`*_DISPATCHED` state, the sweeper
  resolves it by querying with **our** stored idempotency reference, and exactly **one**
  financial effect exists afterwards — counted in the journal and the payment tables, never
  inferred (`INV-LIFE-03`, `INV-PAY-04`).
- **No transaction spans a provider call**, asserted structurally (no connection held
  during the call) as well as behaviourally; a crash mid-call strands a visible
  `*_DISPATCHED` state that the sweeper resolves.
- **The sweeper is safe at N instances with no lease**: concurrent sweepers, a racing
  webhook and a late synchronous response all land on conditional transitions, and the
  race is demonstrated with one winner counted in the tables.
- **The ledger's first touch is capture** (ADR-0048): authorization posts nothing, the
  capture posting (`PSP_CLEARING` → wallet) commits atomically with the state transition
  under the claim `payment-capture:<attemptId>`, and a ten-way duplicate-outcome race
  produces exactly one entry.
- **Conservation under the capture/refund storm**: concurrent captures and partial refunds
  against the trial-balance and projection sweeps, every sweep zero per currency, the
  clearing and wallet positions reconciling exactly to captured − refunded.
- **The refund's funds are reserved**: dispatch places the hold inside the account lock,
  completion releases-and-posts atomically, failure releases with nothing posted; the sum
  bound holds at database rank under a concurrent-partials race (`INV-PAY-05`).
- **Webhook authenticity and freshness negatively tested per cause** (missing, wrong,
  stale signature — nothing written, `INV-PAY-01`); duplicate, out-of-order and
  before-the-sync-response deliveries each counted to one effect; an authentic
  unmappable webhook retains evidence, increments its meter and stalls nothing.
- **The PCI boundary is mechanical**: the `paymentmethods` isolation holds both ways, the
  `information_schema`-derived sweep finds instrument input in no column, and a
  tokenisation-provider outage fails the attach with nothing stored (`INV-PAY-02`).
- **Refund authority is a named permission** with a passing negative test and a required
  reason; instrument attach requires the second factor when one is enrolled, negatively
  tested.
- The phase's meters are published by a freshly started instance, the unknown-state age is
  alertable, and the chain intent ↔ attempt ↔ provider references ↔ entry is traceable
  identifier-to-identifier with no timestamp join.
- Every `Phase: 5` invariant in `FINANCIAL_INVARIANTS.md` — **read from the catalogue, not
  from the phase plan** — has a mutation-register row, the `INV-PAY` group included.

### Phase 6 — Checkout and Merchant Platform
- Cross-merchant data access is impossible (negative tests per endpoint).
- Merchant payable is derived from ledger postings, not a stored mutable field.
- Fee rounding produces no ledger imbalance across a high-volume test batch.
- Payment completing after checkout expiry is handled deterministically.
- Payout destination change requires step-up authorization, four-eyes and is audited.
- Fee schedule version used is pinned per transaction and recorded.

*Extended by the Phase 5 → 6 transition (2026-09-21): the original list predates
ADR-0050…0053 and said nothing measurable about the fee split's conservation, the payout's
bound and ambiguity handling, the late-completion race, the tenancy mechanism, the
four-eyes primitive's shape, or the register — the things this phase's review will be
judged on:*

- **The merchant-bound capture is one entry**: gross to the payable and the fee to revenue
  commit atomically with the attempt's `CAPTURED` transition (the `DIRECTION:PURPOSE`
  account assertions, not line counts — the `P5-TST-002` lesson applied from day one), and
  fee + net equals the capture to the minor unit for every assessment in the high-volume
  batch, zero cumulative residual (`INV-MER-04`).
- **The payable reconciles as arithmetic**: captured − fees − refunds − payouts equals the
  payable ledger position under the merchant conservation storm, against the trial-balance
  and projection sweeps, counted in the tables (`INV-MER-02`).
- **The payout bound holds under a ten-way race**: concurrent payouts against one payable
  dispatch exactly the affordable set — the bound judged inside the payable account's lock
  with in-flight amounts held; a sequential overrun is the honest domain refusal
  (`INV-MER-05`).
- **Payout ambiguity is the standing hold**: a payout provider timeout commits `UNKNOWN`
  with the hold standing and alertable; query resolution lands on conditional transitions;
  a retry after timeout converges on the committed dispatch and drives **one** wire
  operation with the stored reference (`INV-PAY-04`'s discipline outbound).
- **The expiry-vs-capture race is driven both ways**: expiry winning refuses new dispatch;
  the capture winning after expiry lands `EXPIRED → COMPLETED_LATE` with the merchant
  credited, the order created, and the edge counted (`INV-MER-06`) — never dropped, never
  auto-reversed.
- **Tenancy is in the statement**: every merchant-scoped query carries `merchant_id = ?`
  derived from the authenticated key (verified by review of the store layer), and the
  negative tests assert the one refusal **and zero rows touched** (`INV-MER-01`).
- **The four-eyes primitive is real**: proposer ≠ approver enforced in the statement, the
  cooling-off gate proven by a dispatch during the window using the prior destination, and
  the approve-by-proposer refusal negatively tested (`INV-AUD-04`'s second subject — *"first"
  until the Phase 6 review, `P6-DOC-001`: `P3-TSK-021` built the first, on manual
  adjustments*).
- **Fee determinism is reproducible**: recomputing any assessment under its pinned
  schedule version reproduces the amount to the minor unit; a mid-flight schedule change
  prices nothing already dispatched (`INV-MER-03`).
- The phase's meters are published by a freshly started instance, the stuck-payout age is
  alertable (NaN-never-zero), and the chain session ↔ order ↔ intent ↔ entry ↔ payable ↔
  payout is traceable identifier-to-identifier with no timestamp join.
- Every `Phase: 6` invariant in `FINANCIAL_INVARIANTS.md` — **read from the catalogue, not
  from the phase plan** — has a mutation-register row, the `INV-MER` group and
  `INV-AUD-04` included.

### Phase 7 — Cards, Wallets, A2A and Instant Payments
- At least two rails with materially different finality semantics are implemented.
- Reversal attempted on an irrevocable rail is rejected by the domain, not attempted and
  failed at the provider.
- Rail routing decisions are deterministic, version-pinned and explainable from stored data.
- Dispute lifecycle including duplicate chargeback notification produces correct, single
  financial effects.
- Chargeback on an already-refunded payment is handled without double-debiting.

*Extended by the Phase 6 → 7 transition (2026-09-24): the five criteria above predate
ADR-0059…0062 and said nothing measurable about wallets, A2A, the instant rail's abstraction,
per-rail clearing, the send permit or the four scenarios this gate's own review was asked to
name — the things Phase 7's review will be judged on:*

- **Card infrastructure.** The card rail runs as a declared rail, its capability descriptor
  recorded with every routing decision; an authorization is voided on the revocable rail
  through `VOID_DISPATCHED → VOIDED` with no ledger effect, its ambiguity `VOID_UNKNOWN` and
  resolved by query; network clearing evidence is recorded once per attempt with no ledger
  effect (`INV-SET-01`), carrying the acquirer's reference; and card data still stops at the
  tokenisation boundary (`INV-PAY-02`, the needle extended over every new sink).
- **Wallet infrastructure.** A checkout paid from a wallet posts in one transaction — DR the
  wallet, CR the payable and fee revenue — and is refused when unaffordable inside the wallet
  account's lock with holds counted (`INV-BAL-04`); no balance column exists for any wallet (a
  schema scan), the pending figure is a view over in-flight payments, and a wallet statement
  reconciles line by line to journal entries.
- **A2A.** A withdrawal to a bank account registered through the grant exchange is paid over the
  simulated scheme — hold-then-dispatch under the send permit, final on acceptance, DR wallet /
  CR `INSTANT_CLEARING`; a pay-by-bank payment funds a wallet and a checkout through
  `AWAITING_PAYER`, decided by the payer PSP, and a return payment refunds it; no bank identifier
  or alias reaches any table, log, event or response (`INV-RAIL-03`, scanned).
- **Instant-payment abstraction.** The push port is provider-neutral: the rail-name static rule is
  green with a planted violation refused (`INV-RAIL-01`); the scheme's outcome deadline bounds
  `EXECUTION_UNKNOWN` and the inquiry past it is authoritative; a reversal of an executed instant
  payment is refused by the domain before anything is written or sent (`INV-REV-03`, probed).
- **Payment/ledger integration.** Every completion posts to the clearing position its rail
  declares (`INV-RAIL-04`); each clearing position reconciles to its rail's completed
  operations; the trial balance is zero per currency under the multi-rail storm.
- **Multi-instance execution, concurrency, atomicity and consistency.** Every contended decision
  in `PHASE_7_PLAN.md` §7 names its PostgreSQL arbiter and has a counted ten-way race; the routing
  decision commits with the attempt in the confirmation's first transaction; a dispute stage, its
  posting and its history commit together; nothing correct depends on one process.
- **Idempotency — the four named scenarios, each a counted test.** The provider succeeds and the
  response is lost (a card capture, a withdrawal, a pay-in): `UNKNOWN`, resolved, exactly one
  entry. Two instances attempt one operation (confirm, withdraw, refund, void, dispute stage):
  one effect. One callback delivered ten times (instant confirmation, clearing, chargeback): one
  effect. A hold released while a wallet payment and a transfer run on the same wallet: available
  never negative, every posting explained. Every new money-moving command is keyed at the
  financial boundary, every provider operation carries our reference stored before the send
  (`INV-PAY-04`), and every rail callback passes the inbox (`INV-IDEM-04`).
- **Failure recovery.** Each of `PHASE_7_PLAN.md` §14's fifteen failure scenarios has a test or a
  documented, accepted rationale; routing advances to another rail only on `NOTHING_SENT` or an
  eligibility refusal, never after an ambiguous dispatch (`INV-RAIL-02`, probed); every re-sending
  flow carries a send permit, and a refused connection concludes only on a first send.
- **Disputes.** Refunded (non-failed) plus charged back never exceeds captured, under a refund
  racing a chargeback on one attempt (`INV-DSP-01`); a chargeback on an already-refunded payment
  debits the merchant nothing twice, the excess recorded in `CHARGEBACK_RECOVERABLE`; each stage
  posts once under duplicate notifications, and a won representment mirrors its chargeback
  exactly (`INV-DSP-02`).
- **Routing explainability.** A decision recomputed under its pinned policy version over its
  stored inputs reproduces the rail chosen and every rejection (`INV-RAIL-02`, `INV-HIST-04`).
- **Security.** Every new privileged act — routing versions, rail availability, operator voids,
  dispute acceptance and evidence on behalf — carries a named permission with a negative test and
  a `RoutePermissionRegisterTest` row; the dispute routes join the merchant tenancy battery;
  bank-account registration and withdrawal step up when a factor is enrolled; dispute evidence is
  encrypted under a key held outside the database, every access audited (`INV-DSP-03`); each
  rail's credentials are confined and pinned by `ConfinedCredentialVariablesTest`.
- **Audit.** Every privileged and platform act on the new aggregates has a catalogued action,
  a reason where a person judges, and outcome resolvers record acting transitions only.
- **Observability.** `PHASE_7_PLAN.md` §15's series are published by a freshly started instance;
  a stuck withdrawal is alertable — every `UNKNOWN` and every `DISPATCHED` past the sweep's bound,
  NaN never zero; dashboards query only published series.
- **Reconciliation readiness.** Each chain in `PHASE_7_PLAN.md` §12 is traceable
  identifier-to-identifier with no timestamp join: the network's clearing references for cards,
  the scheme's transaction reference for A2A and instant payments, the provider dispute
  reference for chargebacks.
- **Testing.** The multi-rail storm and the dispute battery green and probed; the full battery
  green fleet-wide at the exit review, counted from fresh results.
- **Documentation.** Every `Phase: 7` invariant — **read from the catalogue, not from the phase
  plan** — has a mutation-register row; ADR-0059…0062 read against the code and accepted or
  amended; `RAIL_AND_DISPUTE_LIFECYCLES.md` matches the machines as built.

### Phase 8 — Settlement and Reconciliation
- Every break type in `RECONCILIATION_MODEL.md` is detectable and covered by a test.
- Duplicate settlement file ingestion produces no duplicate matches or postings.
- No code path deletes or overwrites a break; resolution is always a new record plus a
  compensating posting.
- Every match records the rule version and tolerance that produced it.
- Resolution above threshold requires four-eyes, a reason code, and is audited.
- Suspense balances are aged, reported and alertable.
- Matching job crash mid-batch resumes without duplicate or lost matches.

*Extended by the Phase 7 → 8 transition (2026-09-28): the seven criteria above predate
ADR-0064…0073 and said nothing measurable about how evidence enters and is authenticated, how it is
normalised, how a clearing position is discharged and cash recognised, the expectation every
settling completion opens, who owns suspense, late settlement, replay or the ten-instance question
— the things Phase 8's review will be judged on. Each of the seven is made measurable by one
criterion below and stays in force beside it: the first by Break types, the second by Duplicates,
the third by Break immutability, the fourth by Deterministic matching, the fifth by Controlled
resolution, the sixth by Suspense and adjustment, the seventh by Crash recovery. A criterion that
rests on a deferral candidate — `P8-TSK-021`, `-019`, `-023`, cut in that order if the phase must
shrink (O6; like each of O1–O7, a transition decision the owner may revisit) — is met by the task,
or by the deferral recorded with an owner. Before they were adopted, the same transition's
consistency review aligned the criteria with the ADRs and with what the Phase 7 gate repaired
before this phase opens: Controlled resolution and Suspense and adjustment (ADR-0069's per-type table the
one authority on which kinds a break type admits, the gain's exclusions among them, A1; Phase 7's
parkings keyed on what they now record), Settlement recognition (the operation-anchored
payout-return rule, A4; the return's order and item lock, A6; a statement cause closing only
`EVIDENCED`, A3; a return that cannot apply), Break types (the scheme execution claim and the
second presentment), Expectations (the Phase 7 parkings' suspense lines known only once adopted,
A7), Late settlement (the recovery's resolutions, A2), Replay and reprocessing (repudiation "if not
deferred", C12; the reopened items and the released suspense item, A9 and A10 — the reopening
confined to another batch's bank item, the batch's own items leaving by `→ REPUDIATED`, by the
transition's re-check, R2; readmission, A11),
Multi-instance and concurrency (the pre-lock of shared ledger rows) and Security (the provider
transport guard extended to the pull sources):*

- **Ingestion.** All four sources — `simulated-psp.settlement`, `simulated-scheme.cycle-report`,
  `simulated-payout.settlement` and `simulated-bank.statement` — ingest by operator upload with a
  second person's attestation, and by pull over the source's own confined credential where the
  source declares it. Stored bytes equal received bytes by SHA-256, verified on every read; every
  chunk is AES-256-GCM with its AAD bound to file, source, checksum and position, so a tampered or
  swapped chunk is refused with nothing served (`INV-REC-10`, `INV-HIST-02`). A PAN or IBAN shape
  in a free-text field — or in a field whose value fails its declared class, which is screened as
  free text — stores only a `settlement.refused_delivery` metadata row (a planted test), a
  Luhn-valid 15-digit network transaction id in its reference field is not refused, and every
  refusal is audited and alertable (`finapp.settlement.delivery.refused`); a file above 8 MiB or
  50,000 lines is refused at the door with nothing stored. An unattested upload is never accepted,
  and self-attestation is refused at the domain and the `CHECK` (`INV-SET-07`).
- **Duplicates.** The same file delivered ten ways — sequentially, by upload and by racing pulls —
  is exactly one file, one batch, one run and one recognition entry, with ten `file_receipt` rows
  (nine `DUPLICATE`) and no extra decision or park (`INV-SET-04`); a different file declaring an
  accepted batch or statement sequence is `REJECTED(CONFLICTING_BATCH)`, retained and alerted,
  never applied; each line repeated within one file or across files raises exactly one
  `DUPLICATE_EXTERNAL`, the first matched and the repeat parked. Every arbiter is a domain unique,
  independent of `platform.idempotency_record` retention (`INV-IDEM-02`).
- **Normalization.** Every format version — `SIM_PSP_CSV`, `SIM_SCHEME_JSON`, `SIM_PAYOUT_CSV` and
  `SIM_STATEMENT_TAGGED`, each v1 — has a golden file and a fault test per field; a malformed line,
  a trailer mismatch, an unknown currency or a scale mismatch rejects the whole file with at most
  100 content-free `ingestion_error` rows, and no line of it drives matching or posting
  (`INV-SET-07`). Provider vocabulary appears nowhere outside its adapter under
  `com.finapp.settlement.format.<format>` — `SettlementVocabularyIsConfinedTest` green with a
  planted violation refused (`INV-PAY-03`'s discipline); an unknown well-formed line type becomes
  `OTHER_IN` or `OTHER_OUT` and so an item, never dropped; `format_id` and `format_version` are
  recorded on every file and batch, and the re-parse verification reproduces a stored file's
  fingerprints under its recorded version.
- **Deterministic matching.** Every `match_decision` stores `rule_set_id NOT NULL`, the rule's
  priority, the matched key kind, the applied timing and fee tolerances, and one `match_candidate`
  row per candidate it saw (a schema scan); every allocation passes through `allocate(E)` in
  claimant order `(source_sequence, line_no)`, and the shuffled-order property test yields
  identical allocations. Decision replay is `IDENTICAL` over every storm and battery run, an item
  awaiting its rematch reported as `PENDING_REMATCH` and never as divergence; the
  replay-perturbation probe is caught as `DIVERGED` with a CRITICAL `PROCESSING_ERROR` break;
  activating a new rule-set version leaves every earlier decision replaying `IDENTICAL` and no
  position changed (`INV-REC-04`, `INV-REC-07`, `INV-HIST-04`). A fee exactly at its tolerance
  raises nothing and one minor unit beyond raises `FEE_MISMATCH`; a one-minor-unit principal
  difference raises a break in both directions, and a tolerance on an amount is unstorable
  (`INV-REC-08`).
- **Break types.** Each of the fourteen types `RECONCILIATION_MODEL.md` §8 catalogues —
  `MISSING_EXTERNAL`, `MISSING_INTERNAL`, `UNKNOWN_EXTERNAL`, `AMOUNT_MISMATCH`,
  `CURRENCY_MISMATCH`, `FEE_MISMATCH`, `DUPLICATE_EXTERNAL`, `DUPLICATE_INTERNAL`,
  `AMBIGUOUS_MATCH`, `TIMING_DIFFERENCE`, `REVERSAL_MISMATCH`, `REFUND_MISMATCH`,
  `SETTLEMENT_MISMATCH` and `PROCESSING_ERROR` — is raised by its detector in a counted test, its
  severity deterministic: the base by type and direction, one level per ageing band crossed, one
  more at the pinned `high_value_minor` (1,000.00 per currency in rule set v1, O7; ADR-0069). Every
  unallocated remainder either waits in a counted grace (`UNMATCHED` until `grace_until`, judged on
  the database clock) or parks with its break in its own transaction; no run completes with an
  item `PENDING`; one break is open per (type, subject), a recurrence a new break naming
  `follows_break_id`; a reference collision is a `DUPLICATE_INTERNAL`, never a failed payment. An
  instant scheme line is typed through Phase 7's `payments.scheme_execution_claim` (payments
  `V023`): its reference's one claim names the one internal explanation. A scheme reference no
  claim holds names no completed execution: after grace it types `MISSING_INTERNAL` when its other
  references name an operation still in flight, and `UNKNOWN_EXTERNAL` otherwise. A second,
  different network clearing of one capture — Phase 7's `SECOND_PRESENTMENT`, kept only in the
  retained evidence — reaches the matcher as the PSP report's own line and parks with its break,
  `DUPLICATE_EXTERNAL`, an `AMOUNT_MISMATCH` excess or `UNKNOWN_EXTERNAL`, never absorbed
  (ADR-0065; `INV-REC-02`).
- **Break immutability.** `finapp_app` holds no `DELETE` grant on any table of either schema and no
  `UPDATE` on decisions, candidates, allocations, notes, evidence links, releases or parks —
  privilege tests for every writer, and a refusing trigger on `break` (`INV-REC-01`); a break's
  status, type, severity, assignee and `residual_version` move only along trigger-checked edges,
  severity only upward. Resolution is always a new `resolution` row plus, for a posting kind, a
  compensating `ADJUSTMENT` entry through the ledger; an allocation is undone only by a
  repudiation's append-only counter-allocation (`INV-REC-07`).
- **Investigation.** Assignment, notes, evidence links and reclassification are append-only and
  audited, a note's body never in a log, event or audit record; reclassification happens only in
  `OPEN` and `INVESTIGATING`, with a reason; a note holding a Luhn-valid 13–19-digit run or an IBAN
  shape is refused (`INV-PAY-02`). Every break's `/trace` reaches the raw file, the settlement line,
  the decision and its candidates, the journal entry and `payments.provider_evidence` by
  identifiers alone, with no timestamp join, and raw content is read only through the reasoned,
  audited content read (`INV-REC-01`).
- **Controlled resolution.** Four-eyes whenever value is at issue or the resolution posts —
  self-approval refused at the domain, at `resolution`'s `CHECK` and at ledger `V010` beneath; only
  a zero-value, zero-posting `ACKNOWLEDGE` is single-person, and only the platform resolves
  `EVIDENCED`. A break admits only the kinds its type's row in ADR-0069's per-type table lists,
  every other kind refused (`reconciliation.ResolutionKindNotAllowed`). Reason codes are closed at
  both ranks — `ResolutionReasonCode`'s subset per kind, and ledger `V015`'s `reason_code` with an
  uncoded new proposal refused by trigger (`INV-REV-04`); an approval after an allocation, park,
  release or reclassification moved the subject is refused `409 reconciliation.ResolutionStale`,
  the resolution left `PROPOSED`; approval or `DELETE` of a `RECONCILIATION`-origin proposal
  through `/v1/ledger/adjustments` is refused `409 ledger.AdjustmentOriginMismatch`; a free
  adjustment on a reconciled position is refused at the domain and the database (`422
  ledger.AdjustmentOnReconciledPosition`). Every posting kind's lines are proven derived from the
  subject's current remainder, and ten racing approvals produce one entry (`INV-REC-03`,
  `INV-AUD-04`).
- **Suspense and adjustment.** Suspense is aged (`finapp.reconciliation.suspense.age`, NaN never
  zero), reported (`/reports/reconciliation/suspense`, audited, CREDIT and DEBIT items gross, never
  netted) and alertable. Value enters `SUSPENSE_UNMATCHED` only in the transaction that records its
  owning break and leaves only by evidence, an approved resolution or a repudiation (`INV-REC-09`);
  the suspense proof and `finapp.reconciliation.suspense.unowned` read 0 at rest. Phase 7's
  unmatched confirmations are adopted exactly once under a ten-way backfill, each with its CREDIT
  suspense item and `UNKNOWN_EXTERNAL` break (`PARKED_ON_RECEIPT`), keyed on the parking's stored
  `cause`, `named_reference` and, exactly when attributed, `attempt_id` (payments `V023`) — an
  attributed parking (`ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`) resolved by a four-eyes
  `TRANSFER_TO_ACCOUNT` crediting the named attempt's counterparty, never by a guess — and
  `finapp.payments.unmatched.active` and `.age` are described as "parked, ever". `RECOGNISE_GAIN`
  is refused before `gain_min_age_days` (90 in rule set v1, O5) and on every break type
  ADR-0069's per-type table excludes it from — `REVERSAL_MISMATCH`, `REFUND_MISMATCH`,
  `CURRENCY_MISMATCH` — and `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` are posted only by
  approved resolutions (`INV-REC-05`).
- **Settlement recognition.** Each clearing position is discharged only by its own source, whose
  position is read from the counterparty's own declaration, and `EverySettlingPositionHasASource`
  fails on a planted uncovered rail (`INV-SET-05`). A report's recognition posts only the
  counterparty's fees (DR `PROCESSING_COSTS`) and opens one `REMITTANCE` expectation on the same
  position — dated from the batch's stored `accepted_on`, last in its transaction, at most 16 lines;
  a bank line matching no declared remittance pattern, or two, parks at recognition with an
  `UNKNOWN_EXTERNAL` break (`BANK_LINE_UNATTRIBUTED`). `CASH_AT_BANK` is posted only by recognising
  an accepted bank statement or repudiating one — a static rule with a planted violation — and,
  whenever the chain of statements is unbroken, equals the latest statement's closing balance; the
  simulated bank opens at zero (O4), and a gap or a non-zero opening raises `SETTLEMENT_MISMATCH`
  with nothing posted to fit, closed only `EVIDENCED` (`INV-SET-06`). A returned payout is applied
  once by the return worker — the posting first, then the append-only `payout_return` row naming
  its entry, its money bound to the payout's by a composite foreign key — the payout staying
  `COMPLETED`. Its `PAYOUT_RETURNED` line is never key-matched against the OUTBOUND payout's
  expectation: rule set v1 declares that rule operation-anchored, so the line waits `UNMATCHED`,
  no break raised by the matcher, until the worker reaches the payout through its stored provider
  reference and allocates it to the return's own expectation by `UNIQUE (kind, operation_ref)`;
  the worker raced against the grace leg on one item converges both ways (the item re-read under a
  share lock); and a return that cannot apply — a closed merchant's payable among the causes —
  parks as `REVERSAL_MISMATCH` (`RETURN_NOT_APPLICABLE`) for a four-eyes `TRANSFER_TO_ACCOUNT` to
  an account that can take it (O2). The chain completion → reported → cash — `/settlement-status`
  answering `PENDING`, `REPORTED`, `CASH_CONFIRMED` — is demonstrated for card, instant and payout
  (`INV-SET-01`).
- **Expectations.** Every externally settling completion's posting key (`PHASE_8_PLAN.md` §12.2)
  opens exactly one expectation in its own completing transaction, its amount equal to its clearing
  journal line (the expectation-opener register), and a book-rail completion opens none
  (`SettlementModel.NONE`); a forced failure inside the port rolls the completion back and its
  redelivery completes both, and the Phase 7 storm and dispute battery stay green (ADR-0067). After
  the opening-position backfill, `finapp.reconciliation.position.proof` and
  `finapp.reconciliation.line.unattributed` read 0 at rest on the clearing purposes, and
  `line.unattributed` under `SUSPENSE_UNMATCHED` reads 0 once `P8-TSK-020` has adopted Phase 7's
  parkings — a line is known when an expectation names its `(journal_entry_id,
  ledger_account_id)`, a suspense item owns it, or its entry is a batch's recognition, a park's, a
  resolution's, a repudiation's or a payout return's (ADR-0067 §9) — and a planted missing
  expectation and a raw-SQL clearing line each flip them (`INV-REC-06`, `INV-SET-02`); an overdue
  expectation raises exactly one `MISSING_EXTERNAL` under ten ageing sweepers and alerts
  (`finapp.reconciliation.expectation.overdue`), NaN never zero.
- **Late settlement.** Nothing is refused as stale (`INV-SET-03`): a late line allocates like any
  other and resolves its overdue break `EVIDENCED` with the timing recorded; a late internal record
  — a capture the sweeper resolves after the PSP settled it, a chargeback posted after its report
  line — is found by the rematch leg, unparked, and resolves its break `EVIDENCED`; a late
  earlier-dated file is accepted in arrival order; a line arriving after its expectation was written
  off parks as a recovery (`DUPLICATE_EXTERNAL`) for a four-eyes resolution — `RECOGNISE_GAIN` once
  past the minimum age, or `TRANSFER_TO_ACCOUNT`.
- **Replay and reprocessing.** Re-acceptance on a later clock day converges on every key —
  `settlement-batch:`, `recon-suspense:`, `merchant-payout-return:` — because every Phase 8 posting
  is dated from stored rows (`INV-SET-04`); a `REPROCESS` run touches residual items only
  (`UNMATCHED`, `PARKED`), its decisions new rows and never edits; readmission and requeue are
  reasoned and audited under `RECONCILIATION_ADMINISTER`, a readmitted file inheriting its
  original's pull or attestation and an unattested original's readmission accepted only once a
  person distinct from the readmitter and from the original's uploader attests it (`INV-SET-07`); a batch repudiation, if not deferred, restores
  cash, remittances and suspense exactly, by `ReversalService` on the recognition entry and
  append-only counter-allocations (`INV-REV-01`): the repudiated batch's own items leave `MATCHED`
  by `→ REPUDIATED`; a bank item of another batch whose allocation named the repudiated batch's
  remittance expectation reopens `MATCHED → UNMATCHED`, that allocation counter-allocated in the
  same transaction, to wait for the genuine one; a `BANK_UNATTRIBUTED` item a posting resolution
  had already released is answered by a new opposite-side item with its `PROCESSING_ERROR` break
  (`INV-REC-09`); and a payout return applied from it stands, its reopened expectation ageing into
  `MISSING_EXTERNAL`. The genuine file — readmitted if it was rejected `CONFLICTING_BATCH` beside
  the repudiated batch — is then accepted normally.
- **Crash recovery.** A crash mid-parse, between parse and accept, mid-acceptance after the posting
  call, mid-chunk with the connection killed, and between acceptance and the first chunk each
  resumes on another instance with no duplicate or lost decision, allocation, park, entry or event,
  counted in the tables. Our own parser failure leaves the file `RECEIVED`, backed off and visible,
  never `REJECTED`; a poisoned item is `ERRORED` and parked with a `PROCESSING_ERROR` break while
  its run completes; a poisoned run is `BLOCKED` with a CRITICAL break, holds its source visibly and
  resumes only on a reasoned requeue.
- **Multi-instance and concurrency.** Every contention in `PHASE_8_PLAN.md` §7 names its PostgreSQL
  arbiter and has a counted ten-way race, and the allocation and parking lock-bypass variants — the
  try-lock removed — are caught by the uniques and deferred Σ triggers alone: at most one positive
  allocation per (item, expectation), no over-allocation. Advisory namespace `4` is registered in
  `DISTRIBUTED_EXECUTION.md` §3 and pinned by `ReconciliationMigrationTest`; it orders allocation
  and arbitrates nothing. The lock order is `DISTRIBUTED_EXECUTION.md` §3's row, and every
  transaction posting several entries over shared hot rows — a resolution's `ADJUSTMENT` beside its
  unpark, a repudiation's reversal beside its unparks — pre-locks their union in the balance
  projection's order before its first posting (`PostingService.lockBalancesInOrder`, the rule the
  Phase 7 → 8 gate wrote for dispute postings), raced on shared rows with no deadlock. The five
  schedules — `SettlementIntakeSchedule`, `ReconciliationSchedule`,
  `ReconciliationSweepSchedule`, `PayoutReturnSchedule` and `SettlementPullSchedule` — are
  registered with their arguments in
  `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` and the scheduler register, and
  every window is judged in SQL on the database clock. The ten-instance answer is `PASS`, resting on
  those tests and never claimed by construction.
- **Atomicity and consistency.** Each local transaction — receiving a file, refusing a delivery,
  attesting, parsing, accepting with the recognition posting last, opening an expectation inside
  the completing transaction, allocating a chunk, the rematch and grace legs, ageing, proposing,
  approving, applying a payout return — is proven all-or-nothing by failure injection; file → run →
  matched → aged → resolved is an eventually consistent workflow whose every hand-off is a
  committed row and every leg idempotent and leaderless. The position, suspense, cash and
  completeness proofs and the trial balance read 0 per currency in every storm round, in one
  `REPEATABLE READ` snapshot, and again at rest.
- **Security.** Four permissions — `SETTLEMENT_INGEST`, `RECONCILIATION_INVESTIGATE`,
  `RECONCILIATION_RESOLVE`, `RECONCILIATION_ADMINISTER` — and two pairwise-disjoint roles,
  `RECONCILIATION_OPERATOR` and `RECONCILIATION_CONTROLLER` (O1; `RoleNameTest`'s exact grants),
  every route with a negative test and a `RoutePermissionRegisterTest` row; self-attestation,
  self-approval and self-activation refused at every rank. `ConfinedCredentialVariablesTest` pins
  sixteen confined credentials — the eleven before Phase 8, `FINAPP_SETTLEMENT_FILE_KEY` and the
  four report keys; twelve if `P8-TSK-021` is deferred — and every pull source URL passes
  `ProviderTransportGuard` (`https` or `sftp` off loopback, startup refused otherwise).
  `INV-PAY-02`'s column sweep and `INV-RAIL-03`'s needle extend over both new schemas and a
  settlement flow and are absent from every new table and captured log; every content read is
  audited with its reason (`INV-REC-10`).
- **Audit.** Every privileged and platform act has a catalogued action in `AUDITABLE_ACTIONS.md`,
  with `requiresReason` wherever a person judges — a content read, a decline, a readmission, a
  reclassification, a proposal, a rejection, a requeue, a reprocess, a rule set, the backfill.
  Platform acts — `SettlementFileReceivedByPull`, `SettlementFileRejected`,
  `SettlementBatchAccepted`, one `ReconciliationRunCompleted` per run, `BreakRaised`,
  `BreakResolvedByEvidence`, `PayoutReturnApplied` — are audited acting-only, and losers record
  nothing; change summaries carry identifiers only; every report read writes
  `reconciliation.ReportRead`, naming the report and period only. The operator actor-type debt
  (operators audited as `CUSTOMER`, Phase 15's) is assessed at the review; it does not weaken
  four-eyes, because the resolution rows hold both people.
- **Observability.** `PHASE_8_PLAN.md` §15's series are published by a freshly started instance
  (`PlannedMetersExistTest`, armed by the phase's flip to `COMPLETE`), gauges eager, NaN when
  unreadable and never zero, aggregated with `max()`; the tag keys `source` and `severity` join
  `MetricNames` with their written arguments; no amount appears in any tag or value — unmatched
  value, the suspense balance and provider costs are audited operator reports (ADR-0072); every
  "must be 0" gauge, source silence, overdue expectations, suspense age, break age per severity,
  refused deliveries and blocked runs alert; dashboards query only published series.
- **Testing.** The settlement and reconciliation storm (`P8-TST-001`) and the break and resolution
  battery (`P8-TST-002`) green and probed — in every storm round each seeded fault produces exactly
  its break type and no other, and the meters' tally equals the tables'; the owner's ten scenarios
  in `PHASE_8_PLAN.md` §13 each a counted test; each of §14's forty-four failure scenarios a test
  or a documented, accepted rationale; the full battery green fleet-wide at the exit review,
  counted from fresh results.
- **Documentation.** Every `Phase: 8` invariant in `FINANCIAL_INVARIANTS.md` — **read from the
  catalogue, not from the phase plan** — has a mutation-register row, the nine this transition
  catalogues (`INV-SET-04`…`-07`, `INV-REC-06`…`-10`) included; ADR-0064…0073 read against the
  code and accepted or amended; `RECONCILIATION_MODEL.md` and
  `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` match the build; the `DELIVERY_PLAN.md` Phase 8
  addendum, the glossary, `DOMAIN_MODEL.md` and `MODULE_ARCHITECTURE.md` §4 and §5 are current.

### Phase 9 — FX and Cross-Border Payments
- Trial balance is zero **per currency**, including after conversions.
- Rounding residual is explicitly posted to a designated account; no value is created or
  destroyed across a high-volume conversion test.
- Expired or stale quotes are rejected; expiry is a modelled event.
- Rates are server-authoritative; client-supplied rates are rejected.
- Currencies with 0, 2 and 3 minor units are all covered by tests.

### Phase 10 — Credit Decisioning
- Replaying a stored decision's inputs against its pinned policy version reproduces the
  identical outcome and reason codes.
- Every decline carries reason codes sufficient for adverse-action explanation.
- Bureau access without recorded consent is rejected and tested.
- Policy changes require four-eyes and are audited; active policy version is always
  identifiable for any point in time.
- Decisions are immutable.

### Phase 11 — Lending
- Accrual is idempotent per period: rerunning produces no additional accrual, proven under
  crash-and-restart.
- Amortisation schedule totals reconcile exactly to principal plus interest with zero
  rounding leakage.
- Repayment allocation order is versioned, explicit and tested including partial and
  overpayment.
- Month-end, leap-year and day-count edge cases are covered.
- Every loan balance is derivable from ledger postings.

### Phase 12 — BNPL
- Refund at every point in the instalment lifecycle produces correct, value-preserving
  adjustments.
- Full refund closes the agreement and leaves no residual obligation or stranded value.
- Chargeback plus refund on the same order cannot double-credit the customer.
- Merchant financing and customer obligation are created atomically or with a tested
  compensating path.
- Three-way reconciliation (merchant settlement, customer obligation, ledger) balances.

### Phase 13 — Risk, Fraud and AML
- Fail-safe behaviour on risk-service unavailability is explicit per operation and value
  band, and is tested.
- A blocked money movement produces correct compensating postings and strands no value.
- Velocity counters are correct under concurrency and survive counter-store loss safely.
- Rule set version used is recorded on every decision.
- Manual override requires reason codes, four-eyes above threshold, and is audited.
- AML case detail is not exposed on any customer-facing surface (tipping-off control).

### Phase 14 — Accounting and Financial Reporting
- Trial balance zero per currency across the entire posting history, continuously verified.
- Any GL figure drills down to the individual journal lines that compose it.
- Posting into a closed period is rejected; prior-period adjustment path is implemented.
- Reports are reproducible: regenerating a past report produces identical output.
- Unreconciled breaks and suspense balances are explicitly disclosed at close.
- No reporting component holds write access to ledger tables (verified by privilege review).

### Phase 15 — Production Hardening
- Threat model complete; identified critical and high risks mitigated or formally accepted.
- Dependency, container and secret scanning green in CI.
- SLOs defined for every critical flow with alerting and linked runbooks.
- Every runbook rehearsed at least once with the result recorded.
- Audit trail verified complete against the defined list of auditable actions.
- Backup restore rehearsed with post-restore trial-balance verification passing.
- Distributed tracing covers customer → request → domain → provider → ledger → settlement →
  reconciliation for at least one real flow.

### Phase 16 — Scale, Resilience and Disaster Recovery
- Load test at target volume with financial invariants asserted **during** load, not only
  after.
- Chaos experiments (node loss, DB failover, broker outage, cache loss, network partition)
  each leave the ledger correct.
- Measured RPO and RTO meet documented targets.
- Post-DR verification procedure executed successfully: full reconciliation and trial
  balance before money movement resumes.
- Any service extraction or partitioning decision is backed by measurement and an ADR.
- Read replicas provably never serve authoritative financial reads.
