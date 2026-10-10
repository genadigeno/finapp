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

*Extended by the Phase 8 → 9 transition (2026-10-02): the five criteria above predate
ADR-0074…0083 and said nothing measurable about the spread, the FX position, the frozen plan and
its provenance, the FX provider's ambiguity, cross-border exactly-once, unwinds and returns,
counterparty-keyed positions, FX and corridor reconciliation, compliance, security, audit,
observability or the ten-instance question — the things Phase 9's review will be judged on. Each
of the five is kept as written and made measurable by one criterion below: the first by Trial
balance per currency, the second by The rounding residual, the third by Quote expiry, the fourth
by Server-authoritative rates, the fifth by Minor units; criteria six onward are the additions.
Every count is taken from a fresh run, and each criterion names the tasks that build its proof
(**Owners**). A criterion that rests on a cut candidate — `P9-TSK-026`, then `P9-TSK-025`, cut in
that order if the phase must shrink (O8; like each of O1–O10, a transition decision the owner may
revisit) — is met by the task, or by the deferral recorded with Phase 15 as owner, and carries
its conditional clause:*

- **Trial balance per currency.** The trial balance is zero in all five currencies (EUR, GBP,
  USD, JPY, BHD) in one snapshot: after `P9-TST-002`'s ≥ 10,000 conversions over 20 directional
  pairs and both fixed sides, after every storm round, and at rest. **Owners**: `P9-TSK-003`
  (JPY and BHD on every flow), `P9-TSK-009`, `P9-TST-002`, `P9-TST-001`.
- **The rounding residual.** Every trade's residual equals its quote's, |r| ≤ 1 under policy v1
  with both signs present in the battery; an out-of-bound residual or a non-balancing plan is
  refused by the `CHECK` for a raw-SQL writer; `ROUNDING_RESIDUAL(c) = −Σ r(c)`,
  `FX_SPREAD_REVENUE(c) = Σ margin(c)` and `FX_POSITION(c) = 0` once covers execute, over the
  battery; a planted residual folded into the margin flips `finapp.fx.proof`. **Owners**:
  `P9-TSK-002` (the bound), `-008` (the `CHECK`s), `-009` (the posting), `-013` (the proof and
  its plant), `P9-TST-002`.
- **Quote expiry.** The boundary race over ≥ 200 quotes × (10 acceptors + 10 sweepers) yields
  exactly one of {`ACCEPTED` plus its trade, `EXPIRED` plus one `FxQuoteExpired`} for each; an
  instance at ±5 s of clock skew neither accepts late nor refuses early; a stale or replayed
  reference refuses quoting, and is counted. **Owners**: `P9-TSK-005` (staleness, replay),
  `-008` (expiry, the event), `-009` (the boundary race).
- **Server-authoritative rates.** Every request carrying a rate, an amount on acceptance, or any
  unknown field is refused `422` with nothing written; `RatesAreNeverClientSuppliedTest` and the
  OpenAPI request-schema guard are green, each with a planted violation refused, over every
  Phase 9 request record and schema; an implausible or incoherent provider rate is never priced.
  **Owners**: `P9-TSK-008` (both guards born, with their plants; plausibility and coherence at
  quote time), extended by `-009`, `-018`, `-019` and `-024` as each adds a request body.
- **Minor units.** Conversions 0↔2, 0↔3 and 2↔3 minor units, both directions and both fixed
  sides; JPY and BHD exercised on every pre-existing flow, every JPY/BHD fee line judged against
  its own schedule; the Phase 6 0/3-minor ledger fee batch green; minor units pinned (EUR/GBP/USD
  2, JPY 0, BHD 3) with a planted drift failing both the build and startup. **Owners**:
  `P9-TSK-002` (the pin test and the guard; the property tests), `-003` (the flows, the four
  sources' v2 fee schedules, the fee batch), `-009` and `P9-TST-002` (the conversions).
- **Spread is explicit.** One `FX_SPREAD_REVENUE` line per margined trade, equal to the quote's
  margin, with its spread/markup attribution summing exactly; `FxBooksHaveOnePosterTest` is green
  with a planted violation refused. **Owners**: `P9-TSK-002` (the attribution), `-009` (the
  line, the static rule).
- **The FX position is explained.** `finapp.fx.proof` reads 0 in every storm round and at rest,
  and the FX books refuse a `MANUAL` line at the domain and at the trigger, each demonstrated.
  **Owners**: `P9-TSK-009` (the refusals), `-013` (the proof), `P9-TST-001`.
- **A quote is a frozen plan with provenance.** Every trade's provenance columns are `NOT NULL`;
  `FxPlanVerification` is clean over the battery and the storm, and `DIVERGED` under a
  perturbation (the internal rate and the disclosed margin replayed too); every quote replays
  `IDENTICAL`. **Owners**: `P9-TSK-008`, `-009`, `-013`, `P9-TST-002`.
- **Provider ambiguity ends only in knowledge.** Under every seeded fault, the simulator's
  execution count equals the cover execution facts, which equal the trades; a new cover
  reference appears only after a definitive rejection; no cover is ever concluded "never
  received"; every abandoned covered quote has exactly one unwind. With `P9-TSK-025` landed,
  every reversed trade has exactly one unwind too; if `-025` is cut, no trade can be reversed,
  and its deferral is recorded with Phase 15 as owner. **Owners**: `P9-TSK-012`, `-021`, `-025`
  (conditional), `P9-TST-001`.
- **Cross-border happens exactly once.** Ten authorizers, a lost response and retries produce
  one payment, hold, outbound credit, provider instruction (counted at the provider), trade, fee
  line, clearing credit and expectation; what the customer was shown equals what was held,
  posted and instructed (`INV-XB-03`). **Owners**: `P9-TSK-019`, `-020`.
- **The lifecycles hold.** Every invalid transition of quote, cover, trade, payment, outbound
  credit, beneficiary, screening, pricing and corridor policy, the availability-enable proposals
  and (with `-025` landed) the trade reversal is refused by the domain and by raw SQL; the
  cancellation request is born once and never updated; no customer line exists for a payment
  that is not `IN_TRANSIT`, `DELIVERED` or `RETURNED`; `RECEIVED` is never concluded
  `NEVER_RECEIVED`; an answer implying acceptance on a credit not yet `COMPLETED` applies its
  facts in order, in one transaction. **Owners**: `P9-TSK-007` and `-015` (policies, enable
  proposals), `-008` (quote), `-012` (cover), `-009` and `-025` (trade, reversal), `-016`
  (screening), `-017` (beneficiary), `-019` and `-020` (payment, outbound credit), `-024` (the
  cancellation request).
- **Unwind, cancellation and return are exact.** A failed or cancelled payment leaves the
  customer's wallet delta at zero and its hold released; a `TOO_LATE` recall is never shown as
  cancelled; an exact return credits once, in the instructed currency, refunds the fee once, and
  is never lost while its credit is in flight; any other return is never posted without a
  person, and its resolution moves the payment to `RETURNED`. **Owners**: `P9-TSK-021`, `-023`,
  `-024`.
- **Counterparty positions.** `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` are keyed per
  counterparty, and every counterparty account is seeded below the id ceiling; one settlement
  source per (purpose, counterparty), with the startup refusal tested; the Phase 8 proofs are
  unchanged in verdict. With M9.8 landed, failover and selection are proven and nothing is
  netted across counterparties; if M9.8 is cut, its deferral is recorded with an owner.
  **Owners**: `P9-TSK-010`, `-011`, `-014`, `-026` (conditional).
- **Reconciliation never converts.** Both new sources are composed (the counterparty-keyed
  descriptor with its settled currencies, the pull beans, the remittance attribution) and ingest
  by upload with attestation and by pull, with golden files and a fault test per field; a
  currency the counterparty does not settle is rejected; `EverySettlingPositionHasASourceTest`
  covers both new positions; in the storm, every FX leg and outbound credit is matched to cash,
  and the position, suspense, cash and completeness proofs read 0 per (counterparty, currency);
  each FX and corridor discrepancy raises exactly its typed break, by its selection rule, and a
  merchant and a corridor return can never be confused; `ReconciliationNeverConvertsTest` is
  green, with a planted violation refused; no rule set is migration-seeded, and every source has
  an active version activated by two persons, with a fee schedule in every currency it can
  settle. **Owners**: `P9-TSK-011` (the FX source, the guard, the first-version door), `-014`
  (the corridor source, the scoped reader and lookup), `-013` and `-022` (the typed breaks,
  cash), `-003` (the existing sources' v2), `P9-TST-001`.
- **Compliance.** No payment to, and no offer for, a beneficiary that is not `ACTIVE` with a
  current `CLEAR` or `RELEASED` screening, and an automatic `CLEAR` only with a payee `MATCH`; a
  hit, an indeterminate result or an unverified payee reaches review and is decided only by a
  person with the permission and a reason, every outcome recording its decision basis; screening
  unavailability holds and prices nothing; customer responses for screening, review, block and
  revocation are byte-identical in shape at the beneficiary read, the revocation door, the quote
  door and the authorization door; an authorization racing a screening hit is never committed
  against an `IN_REVIEW` beneficiary; the limit and risk seams are required parameters with
  reserved refusal codes, consulted in-lock. **Owners**: `P9-TSK-016`, `-017`, `-018`, `-019`.
- **Security.** The permissions (five with `P9-TSK-025` landed, four if it is cut) and the
  `FX_CONTROLLER` role carry `RoleNameTest`'s exact grants; every route is negatively tested and
  in `RoutePermissionRegisterTest`; four-eyes self-approval is refused at the domain and the
  `CHECK`, each alone; the new confined credentials are pinned, and every provider URL goes
  through the transport guard; the `INV-RAIL-03` needle is absent everywhere except kyc's
  ciphertext, on every leg; a signed-but-forged callback moves nothing. **Owners**: `P9-TSK-007`,
  `-013`, `-015`, `-016`, `-025` (conditional); the needle's legs `-016`, `-017`, `-018`,
  `-019`, `-023` and `-024` and the full walk `P9-TST-001`; the callbacks `-012`, `-020`.
- **Audit.** Every privileged and platform act is catalogued in `AUDITABLE_ACTIONS.md`, with
  `requiresReason` where a person judges, and recorded in the act's own transaction; losers
  record nothing; every report and provenance read is audited. **Owners**: every task that adds
  an act; `P9-TSK-027` (the reports).
- **Observability.** `PHASE_9_PLAN.md` §15's series are published by a freshly started instance
  (`PlannedMetersExistTest`, armed by the phase's flip to `COMPLETE`), with no amount in any
  series, each schedule's gauge included; every "must be 0" gauge, unknown age, received age,
  rate staleness, review age, in-transit age and missing rule set alerts, resolved against a
  live scrape. **Owners**: `P9-TSK-027`, and each task for the series it ships.
- **Multi-instance and concurrency.** Every contention in `PHASE_9_PLAN.md` §7 has a counted
  ten-way race; the six born-once arbiters — `UNIQUE (fx.trade.quote_id)` (`P9-TSK-009`),
  `UNIQUE (crossborder.payment.quote_id)` (`-019`), the `fx.cover_execution` PK (`-012`),
  `UNIQUE (fx.cover.quote_id, kind)` (`-021`), the `scheme_execution_claim` PK, subject
  `OUTBOUND_CREDIT` (`-020`), and `UNIQUE (outbound_credit_return.outbound_credit_id)` (`-023`)
  — each survive a lock-bypass probe; two application contexts with clocks skewed by ±5 s race
  the same quote, cover, payment, recall and return, with one effect each; the six schedules are
  registered with their arguments, and advisory namespace `5` is registered in
  `DISTRIBUTED_EXECUTION.md` §3 and pinned by `FxMigrationTest`; `X-TSK-013` is complete, so no
  send permit on the platform is written from an instance clock; every task's ten-instance
  answer is `PASS` on its named tests, never by construction. **Owners**: the tasks of
  `PHASE_9_PLAN.md` §7's table; `P9-TST-001`; `X-TSK-013`.
- **Atomicity.** Each Phase 9 cross-module transaction (T-a…T-g) and each local one — quote
  creation, the recall request, policy activation, an enable-proposal approval and, with
  `P9-TSK-025` landed, a reversal approval — is proven all-or-nothing by failure injection.
  **Owners**: the task that builds each transaction (`P9-TSK-007`, `-008`, `-009`, `-012`,
  `-015`, `-016`, `-019`, `-020`, `-023`, `-024`, `-025` conditional).
- **Testing.** The FX and cross-border storm (`P9-TST-001`), green in three consecutive fresh
  runs, and the value-preservation and rounding battery (`P9-TST-002`), both probed; the owner's
  ten scenarios in `PHASE_9_PLAN.md` §13 each a counted test (scenario 8 met by
  `UnwindRetryDatabaseTest` alone if `-025` is cut); each of the sixty failure scenarios a test
  or a documented, accepted rationale; the fleet-wide battery counted from fresh results, or its
  deliberate skip recorded as a deviation with the owner's instruction. **Owners**: `P9-TST-001`,
  `P9-TST-002`, and the tasks `PHASE_9_PLAN.md` §13 names.
- **Documentation and invariants.** Every `Phase: 9` invariant in `FINANCIAL_INVARIANTS.md` —
  **read from the catalogue, not from the phase plan** — has a `MUTATION_TESTING.md` §2
  demonstration, and `P9-TST-*` have their §4 rows; ADR-0074…0083 are read against the code and
  accepted or amended; `FX_AND_CROSS_BORDER_LIFECYCLES.md` (every machine, the two proposal
  machines and the born-once cancellation request included), `RECONCILIATION_MODEL.md`,
  `LEDGER_MODEL.md`, the glossary, `DOMAIN_MODEL.md`, `MODULE_ARCHITECTURE.md`,
  `BOUNDED_CONTEXTS.md`, `DISTRIBUTED_EXECUTION.md` §3, `DATA_CLASSIFICATION.md`,
  `AUDITABLE_ACTIONS.md`, `ERROR_CONTRACT.md` and the `DELIVERY_PLAN.md` Phase 9 addendum are
  current, the glossary carrying the design's twenty-three Phase 9 terms. **Owners**:
  `P9-DOC-001`, with each task writing its own documents as it lands.

### Phase 10 — Credit Decisioning
- Replaying a stored decision's inputs against its pinned policy version reproduces the
  identical outcome and reason codes.
- Every decline carries reason codes sufficient for adverse-action explanation.
- Bureau access without recorded consent is rejected and tested.
- Policy changes require four-eyes and are audited; active policy version is always
  identifiable for any point in time.
- Decisions are immutable.

*Extended by the Phase 9 → 10 transition (2026-10-07): the five criteria above predate
ADR-0084…0089 and said nothing measurable about the credit profile, data collection and its
providers, freshness, affordability, exposure under concurrency, the scorecard, the policy
engine, underwriting, the frozen snapshot, security, audit, observability or the ten-instance
question — the things Phase 10's review will be judged on. Each of the five is kept as written
and made measurable by one criterion below: the first by The policy engine, the second by
Explainability, the third by Credit data, the fourth by Versioning, the fifth by Decisioning;
the other criteria are the additions. Every count is taken from a fresh run, and each criterion
names the tasks that build its proof (**Owners**). A criterion that rests on the cut candidate —
`P10-TSK-021`, the second bureau and source selection (`PHASE_10_PLAN.md` §16) — is met by the
task, or by the deferral recorded with Phase 15 as owner, and carries its conditional clause.
Nothing of Phase 10 is built at the transition:*

- **Credit data.** No data request is opened, and no answer recorded, without a current grant of
  its source kind's purpose (`CREDIT_BUREAU_ACCESS`, `FINANCIAL_DATA_ACCESS`) read in that
  transaction; a submission without it is refused `403 credit.ConsentRequired` with every
  provider's pull count unchanged; a withdrawal between the ask and the record — or before a
  retry — ends `CONSENT_WITHDRAWN` with the payload discarded unread (a retry asks nothing) and the
  decision request `ABANDONED` (`CONSENT_WITHDRAWN`), nothing decided; a withdrawal after the
  answer leaves the data request `RECEIVED` and, found by the gate's re-read at the freeze or in the
  deciding transaction, abandons the request the same way, nothing frozen or decided. Under lost
  responses, retries, timeouts and duplicate answers each provider counts one pull per reference,
  the references answered `RECEIVED` equal the records, and each reference has at most one record,
  a duplicate's evidence marked; every evidence row is encrypted under key purpose
  `credit-evidence` with `retain_until` stored, and the application role's `SELECT` on it is
  refused — the raw payload readable only through the audited, reasoned evidence-read door; a
  record one second past the pinned policy's maximum age (database clock) is re-collected, and an
  instance skewed ±5 s neither uses stale data nor refuses fresh. **Owners**: `P10-TSK-002` (the
  purposes), `-005` (the bureau port and normalisation), `-006` (the data request, the consent
  reads at open, retry and record, the retry sweep, duplicates and lost responses), `-007`
  (financial data), `-008` (freshness at the freeze), `-014` (the refusal at submission), `-015`
  and `-016` (the re-read at the freeze and in the deciding transaction), `-017` (the evidence
  read).
- **Credit profile.** Ten concurrent first submissions for one party leave exactly one
  `credit_profile` row; the row holds no figures, and the application role's `UPDATE` and
  `DELETE` on it are refused; every deciding transaction takes it first. **Owners**:
  `P10-TSK-004`, `-016`.
- **Affordability.** The worked cases of `PHASE_10_PLAN.md` §12.3 are exact to the minor unit for
  both products (the annuity and the `CREDIT_LINE` minimum-payment ratio); property tests show
  disposable income monotone in income, amount and rate; the annuity rounds once, at its declared
  point; a source in a currency other than the product's is normalised as partial data — the
  attribute `ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker — never a conversion and
  never an error; `NoFloatingPointMoneyRulesTest` covers `credit`, with a planted violation
  refused. **Owners**: `P10-TSK-005`, `-007` (normalisation), `-008` (the marker in the snapshot),
  `-009`.
- **Exposure.** Exposure is the sum of the snapshot's bureau balance, the platform-exposure
  seam's recorded zero, the reserved exposure and the requested amount; reserved exposure counts
  exactly the party's `APPROVED` decisions with `valid_until` after the database's now and no
  `credit_decision_consumption` row, read under the profile lock — the decision row itself never
  updated; two products for one party at the limit, raced ten ways, never both approve beyond it;
  a person's approval beyond the evaluation's approved amount or the re-read limit is refused `422
  credit.ExposureLimitExceeded`, nothing recorded; at rest in the storm no party's reserved
  exposure exceeds its limit. **Owners**: `P10-TSK-010`, `-016`, `-018`, `P10-TST-001` (the
  census).
- **Underwriting.** Every invalid transition of the underwriting case is refused by the domain
  and by raw SQL; two underwriters taking one case leave one `ASSIGNED` and one `409
  credit.CaseTaken`; an approval above the product's threshold needs a second, different
  underwriter, self-approval refused at the domain and at the `CHECK`, each alone; no case exists
  for a hard-declined evaluation and a person's approval of one is refused `422
  credit.HardDeclineNotOverridable`; a decision without a reason code is refused `422
  credit.ReasonRequired`; an unassigned case's request expiring races its assignment (both taking
  the request, then the case) to exactly one of `EXPIRED`, `ASSIGNED`; a taken case (`ASSIGNED` or
  `AWAITING_SECOND`) is decided by its person after the request's validity has passed, and its
  request is never `EXPIRED`; a disagreeing second approver refuses the second approval
  (`AWAITING_SECOND → ASSIGNED`, reasoned, audited) and the first underwriter decides again; a case
  whose request closes undecided ends `CLOSED` with the request's reason, never stranded; release
  and the refusal are audited. **Owners**: `P10-TSK-018`.
- **The policy engine.** Decision-rule tests cover every operator, effect and severity
  combination, caps, the fallback and reason-code ordering and deduplication; the evaluator reads
  only the snapshot; replaying every stored decision against its pinned policy, model and engine
  versions reproduces outcome, approved amount and ordered reason codes `IDENTICAL` — over the
  battery, the storm and at rest — and `CreditReplayProof` reads `DIVERGED` under a perturbed
  snapshot, a perturbed rule row and a changed evaluator kept under its old `engine_version`.
  **Owners**: `P10-TSK-011` (the scorecard), `-013` (the evaluator), `-019` (the replay proof),
  `P10-TST-002`.
- **Decisioning.** Requests are decided end to end across instances; every invalid transition of
  the decision request and the data request is refused by the domain and by raw SQL; a `DECIDED`
  request carries exactly one decision, naming the snapshot it was made from, with its pinned
  versions and snapshot hash, and a successor snapshot exists only where the deciding transaction
  found the reserved exposure changed; `UPDATE` and `DELETE` on the decision tables are refused
  for every role, by privilege and by trigger, each proven alone; an expiry and a decision racing
  at the boundary over ≥ 200 requests leave exactly one of `DECIDED`, `EXPIRED` each; a party
  whose standing is lost, or whose consent is withdrawn, before the decision leaves its request
  `ABANDONED` with that reason, never `EXPIRED` and never decided. **Owners**: `P10-TSK-006`,
  `-008`, `-014`, `-015`, `-016`.
- **Explainability.** Every adverse decision carries at least one adverse reason code, and every
  code in the catalogue is exercised by the battery; every decision explains from its rows alone
  — attributes with provenance, versions, rules fired, reasons, outcome, when and by whom —
  through the audited explanation door; the customer's read returns the adverse reasons'
  customer texts in order and, by a needle per figure, no score, attribute, threshold or risk
  signal. **Owners**: `P10-TSK-001` (the catalogue), `-013`, `-016`, `-017`, `-019`.
- **Versioning.** Policy and scorecard versions are proposed, activated and rejected only through
  their doors, four-eyes at the domain and the `CHECK`, each act audited; no version is
  migration-activated; ten racing approvers leave one `ACTIVE`; a rule or band updated, deleted,
  or inserted outside its version's proposing transaction is refused for a raw-SQL writer, a
  `PROPOSED` version's included; the version active at an instant is answered exactly
  over a generated history, the instants of each switch included; a policy or scorecard
  activated mid-decision leaves the pinned version deciding (`PHASE_10_PLAN.md` §14 scenarios 16
  and 17); a policy lacking a fallback for a source kind it reads is refused
  `422 credit.PolicyIncomplete`, a points table with overlapping or gapped bands `422
  credit.ScorecardInvalid`; the versions are pinned on the request at `SUBMITTED → COLLECTING`
  and an activation after the pin changes nothing for it. **Owners**: `P10-TSK-011`, `-012`,
  `-015` (the pin), `-016`.
- **Provider abstraction.** Each adapter passes the port's contract suite (normal, partial,
  malformed, timeout, duplicate, unknown status ending `UNAVAILABLE`, never data) and its
  normalisation golden files; no provider type is reachable outside its adapter, with a planted
  violation refused; no provider fault ever yields an approval on an absent source. With
  `P10-TSK-021` landed, two bureaus serve one port and source selection is proven; if it is cut,
  the criterion is met by the contract suite and the single adapter, its deferral recorded with
  Phase 15 as owner. **Owners**: `P10-TSK-005`, `-007`, `-021` (conditional).
- **Multi-instance correctness.** The born-once arbiters — `UNIQUE (credit_profile.party_id)`
  (`P10-TSK-004`), `request_reference` and `UNIQUE (credit_record.data_request_id)` (`-006`),
  `UNIQUE (decision_snapshot.decision_request_id, sequence)` (`-008`),
  `UNIQUE (credit_assessment.snapshot_id)` (`-011`), `UNIQUE (policy_evaluation.assessment_id)`
  (`-013`), `UNIQUE (credit_decision.decision_request_id)` (`-016`) and
  `UNIQUE (underwriting_case.decision_request_id)` (`-018`) — each survive a lock-bypass probe;
  two application contexts with clocks skewed by ±5 s race the same submission, step, decision,
  activation and case with one effect each; both schedules are registered in
  `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` (twenty-two) with their
  gauges; the lock order and advisory namespace `10` (credit's own lock, the one-`PROPOSED`
  partial unique its backstop: ten proposers leave one proposal; registered by `-011`, its first
  writer) are registered in `DISTRIBUTED_EXECUTION.md` §3; every task's ten-instance answer is
  `PASS` on its named tests, never by construction. **Owners**: `P10-TSK-004` (the profile), the
  tasks of `PHASE_10_PLAN.md` §7's table (`-006`, `-008`, `-011`, `-012`, `-014`, `-015`, `-016`,
  `-018`, `-019`); `P10-TST-001`.
- **Concurrency.** Every contention of `PHASE_10_PLAN.md` §7 has a counted race: ten submissions
  per key; two keys per party and product; ten progress sweepers per request; a bureau answer
  delivered twice; consent withdrawn mid-pull; a freeze racing a fresher record; two products for
  one party at the limit; an activation racing a decision; ten approvers per proposal; two
  underwriters per case; an assignment against its request's expiry; ten second approvers per
  case; expiry against decision. **Owners**: `P10-TSK-006`, `-008`, `-011`, `-012`, `-014`,
  `-015`, `-016`, `-018`.
- **Idempotency.** Ten submissions under one key create one request and replay one response,
  `409` while in progress; the cancellation and every keyed operator act (assignment, release,
  decision, second approval or its refusal, propose, approve, reject) replays its response; every
  internal step converges on a retry, a duplicate or a second instance by its born-once unique and
  conditional edge, nothing doubled. **Owners**: `P10-TSK-006`, `-011`, `-012`, `-014`, `-015`,
  `-016`, `-018`.
- **Failure recovery.** A crash after each step — submission, collection, the freeze, the
  evaluation, between evaluation and decision — is re-driven by another instance with nothing
  doubled; each of the thirty-one failure scenarios of `PHASE_10_PLAN.md` §14 is a test or a
  documented, accepted rationale. **Owners**: the tasks §14 names; `-015`, `-016`.
- **Security.** The three permissions and two roles carry `RoleNameTest`'s exact grants; every
  route is negatively tested and in `RoutePermissionRegisterTest`; another party's request
  answers `404`; submission requires an MFA-assured session and the party's standing,
  in-transaction; every new column is classified under `ColumnClassificationTest`; the
  `INV-RAIL-03` needle walk extends to credit's doors, and no attribute, score, threshold or
  reason text appears in a log line, metric tag, span attribute, event or exception message.
  **Owners**: `P10-TSK-001`, `-003`, `-006`, `-011`, `-012`, `-014`, `-017`, `-018`, `-019`,
  `-020`; the full walk `P10-TST-001`.
- **Audit.** Every act of `PHASE_10_PLAN.md` §11 is catalogued in `AUDITABLE_ACTIONS.md`, with
  `requiresReason` where a person judges, and recorded in the act's own transaction; losers
  record nothing; every pull, explanation, replay and report read is audited. **Owners**: every
  task that adds an act; `P10-TSK-020` (the reports).
- **Observability.** `PHASE_10_PLAN.md` §15's series are published by a freshly started instance
  (`PlannedMetersExistTest`, armed by the phase's flip to `COMPLETE`), with no amount, score,
  attribute or party in any tag, both sweeper gauges included; the latency, unavailability,
  request-age, review-age, replay and no-active-policy alerts resolve against a live scrape.
  **Owners**: `P10-TSK-020`, and each task for the series it ships.
- **Testing.** The credit decisioning storm (`P10-TST-001`), green in three consecutive fresh
  runs, and the reproducibility battery (`P10-TST-002`) of ≥ 10,000 generated applicants across
  both products, every decision replayed `IDENTICAL`, both probed; the fleet-wide battery counted
  from fresh results, or its deliberate skip recorded as a deviation with the owner's
  instruction. **Owners**: `P10-TST-001`, `P10-TST-002`.
- **Documentation and invariants.** Every `Phase: 10` invariant in `FINANCIAL_INVARIANTS.md` —
  **read from the catalogue, not from the phase plan** — has a `MUTATION_TESTING.md` §2
  demonstration, and `P10-TST-*` have their §4 rows; ADR-0084…0089 are read against the code and
  accepted or amended; `CREDIT_DECISIONING_LIFECYCLES.md` (every machine, the born-once facts and
  each of its §5 points — settled at the transition — read against the code), `CREDIT_MODEL.md`,
  the glossary, `DOMAIN_MODEL.md`,
  `MODULE_ARCHITECTURE.md` (the risk score moved to `risk`), `BOUNDED_CONTEXTS.md`,
  `DISTRIBUTED_EXECUTION.md` §3, `DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md` and
  `ERROR_CONTRACT.md` are current. **Owners**: `P10-DOC-001`, with each task writing its own
  documents as it lands.

*Read at the exit review (`P10-DOC-001`, 2026-10-09), every clause against the code and its counted tests
([`reviews/PHASE_10_REVIEW.md`](reviews/PHASE_10_REVIEW.md)). Three clauses are met in a form their words did not
anticipate, each recorded rather than re-worded: **Credit data**'s `403 credit.ConsentRequired` is built as the
platform's one consent refusal, `409 consent.ConsentRequired` naming the purpose (`P10-TSK-014`; ADR-0087's follow-up,
`ERROR_CONTRACT.md` §3) - the clause's substance, every provider's pull count unchanged, is what
`DecisionRequestDatabaseTest#consentAbsentIsRefusedBeforeAnyProviderIsAsked` proves; **Exposure**'s "raced ten ways" is
two deciders per party over a hundred rounds (`CreditDecisionDatabaseTest#twoProductsAtTheExposureLimitSerialise`), a
person beside the system over thirty (`UnderwritingCaseDatabaseTest#aManualApprovalAndASystemDecisionForOnePartySerialise`)
and the storm's twelve parties on two skewed instances - one lock per party, so ten racers add nothing two do not; and
**Decisioning**'s "≥ 200 requests" was one request and the storm's six until this review added
`CreditDecisionDatabaseTest#expiryAndDecisionAtTheBoundaryOverTwoHundredRequests`.*

### Phase 11 — Lending
- Accrual is idempotent per period: rerunning produces no additional accrual, proven under
  crash-and-restart.
- Amortisation schedule totals reconcile exactly to principal plus interest with zero
  rounding leakage.
- Repayment allocation order is versioned, explicit and tested including partial and
  overpayment.
- Month-end, leap-year and day-count edge cases are covered.
- Every loan balance is derivable from ledger postings.

**The five, made measurable** (each kept as written above; task IDs are `PHASE_11_PLAN.md`'s, a
bare `-nnn` meaning `P11-TSK-nnn`):

1. *Accrual is idempotent per period.* `interest_accrual UNIQUE (loan_id, accrual_date)` and the
   ledger key `lending.accrual:<loan>:<date>` each survive a lock-bypass probe; scenario 4,
   `InterestAccrualDatabaseTest#theAccrualRunTwiceAccruesOnce` — run twice, ten sweepers, a crash
   mid-run, a restart — leaves one row and one entry per (account, date); in every storm round's at-rest
   snapshot every elapsed accrual date of every account in servicing is present exactly once
   (`INV-LND-02`). **Owners**: `-016`, `P11-TST-001`.
2. *Schedule totals reconcile exactly.* For 100% of the battery's ≥ 10,000 generated loans, Σ
   principal portions = P and Σ instalment amounts = P + Σ period interest to the minor unit; on an
   on-time path the billed amounts equal the projection to the minor unit (property-tested); the
   battery's leakage census reads zero (`INV-LND-03`). **Owners**: `-006`…`-009` (the engines),
   `-017` (billing), `P11-TST-002`.
3. *Allocation order versioned and explicit.* The order and the overpayment treatment are fields of
   a four-eyes terms version pinned on the agreement, each allocation naming the agreement version
   and `ALLOCATION_ENGINE_V1`; the engine's properties (conservation, order, never above due, both
   overpayment treatments) and `RepaymentDatabaseTest`'s full, partial, over and early cases
   (`INV-LND-04`). **Owners**: `-005`, `-006`…`-009`, `-018`.
4. *Month-end, leap-year and day-count edges.* Golden cases checked by hand for disbursement on the
   28th to the 31st and on every day of a leap and a non-leap year, a repayment day of 28 with the
   end-of-month clamp from the intended day (31 January → 28/29 February → 31 March), 29 February
   accruing one day under ACT/365F, a zero-rate loan; the battery's start dates span leap and
   non-leap years and month ends (`INV-LND-09`). **Owners**: `-006`…`-009`, `-016`, `P11-TST-002`.
5. *Every loan balance derivable from ledger postings.* `LendingSchemaHasNoMutableMoneyTest` — every
   `*_minor` column on an `INSERT`-only table — with a planted violation refused; every displayed and
   every deciding figure derived from journal lines (`BalanceDerivation`), never the projection;
   `LoanSubledgerProof` zero for every account at rest in every storm round, gauged and alerting on
   any failure (`INV-LND-01`). **Owners**: `-002`, `-003`, `-029`, `P11-TST-001`.

**Per area:**

- **Loan lifecycle.** Every invalid transition of the application, offer, loan (both kinds),
  disbursement, payout, repayment, proposal, terms-version and capital-contribution machines
  (`LENDING_LIFECYCLES.md`) is refused at the domain, by the every-writer trigger and by the status
  `CHECK`, raw-SQL edges out of `CLOSED` and `CANCELLED` refused for every role; an application and
  its credit decision request are opened in one transaction (`UNIQUE (decision_request_id)`); a
  decline is never reconsidered; a withdrawal after credit's evaluation is `409
  lending.ApplicationNotWithdrawable`; ten origination sweepers leave one offer, and a
  `CreditDecisionRecorded` delivered twice, late or never converges within one sweep interval;
  standing lost before disbursement ends `CANCELLED (STANDING_LOST)` with the commitment released and
  capital headroom restored; an `ACTIVE` account stays `ACTIVE` whatever its delinquency, default or
  payout condition. **Owners**: `-010`, `-011`, `-012`, `-014`, `-023`.
- **Offer and contract.** The offer equals the approved amount and term, or limit, exactly;
  `expires_at = LEAST(valid_until, offered_at + offer_validity)` on the database clock, and
  acceptance against expiry at the boundary leaves exactly one of `ACCEPTED`, `EXPIRED`; acceptance
  requires a `MULTI_FACTOR` session (a lesser one refused, nothing written) and echoes the offer's
  `terms_sha256` (`409 lending.TermsChanged` on a mismatch); the agreement version is `INSERT`-only
  for every role, its raw-SQL `UPDATE` and `DELETE` refused, its canonical terms self-contained and
  its hash re-verified by `LoanReplayProof`; every acceptance stores its evidence — offer, agreement
  version, `terms_sha256`, template id and version, the SHA-256 of the document shown, identity,
  session, assurance level, `accepted_at` on the database clock, channel, client IP and user agent
  (`CONFIDENTIAL`) — and creates no consent purpose (`INV-IDN-04`); terms versions are four-eyes,
  never migration-activated, ten approvers leaving one `ACTIVE` (`INV-LND-05`). **Owners**: `-005`,
  `-011`, `-012`, `-029`.
- **Disbursement.** On both paths one disbursement and one entry per loan; the receivable
  (`LOAN_PRINCIPAL`) debited in the entry that credits the wallet, and the loan `ACTIVE` exactly when
  that entry exists; a failed disbursement posts nothing and past `disbursement_deadline` ends
  `CANCELLED (DISBURSEMENT_FAILED)`; on the external path the hold placed at disbursement is adopted,
  not duplicated, by payments' withdrawal, the payout dispatched once under
  `payments.withdrawal:lending:<loanId>` and never re-dispatched by lending, a `FAILED`, `RETURNED`
  or `NOT_DISPATCHED` payout leaving the receivable and the disbursement entry unchanged and the
  funds in the borrower's wallet, and interest starting at the payout's terminal outcome. Scenario 1
  (`LoanDisbursementDatabaseTest#theSameDisbursementOnTwoInstancesPostsOnce`,
  `LoanPayoutDatabaseTest#tenDispatchersOneWithdrawal`) and scenario 5
  (`LoanPayoutDatabaseTest#aLostResponseIsHeldThenConcludedByInquiryAndNeverResent`) green; one
  disbursement per non-cancelled loan in every storm round (`INV-LND-07`). **Owners**: `-014`, `-015`
  (with payments `V032`), `P11-TST-001`.
- **Schedule.** Beyond the second and fourth originals: one schedule per (loan, version) (`UNIQUE
  (loan_id, schedule_version)`); schedule version 1 generated in the accrual start's transaction — at
  disbursement on the wallet path, at the payout's terminal outcome on the external path; terms that
  would amortise negatively refused `422 lending.TermsNotAmortising` at the offer; a recalculation
  writes a new version, billed instalments of the old version kept and nothing updated; the offer's
  illustrative schedule labelled as such, with total interest and total payable. **Owners**:
  `-006`…`-009`, `-011`, `-014`, `-015`, `-027`.
- **Interest and fees.** Accrued, due, paid and outstanding are kept apart — accrued in
  `interest_accrual` rows and `LOAN_INTEREST_ACCRUED`, due in billings and statements and the `…_DUE`
  accounts, paid in allocations, outstanding derived and never stored — and the subledger proof holds
  each identity for every account; the accrual base is principal only, never interest or fees; a late
  fee is assessed at most once per due item (`UNIQUE (due_item_id, kind)`) and within its cap; no
  penalty interest and no prepayment fee exist; a fee waiver reverses only the unpaid remainder
  through `ReversalService`, bounded by the assessment, a paid fee refunded to the credit balance; an
  interest waiver is an explicit, reasoned posting; every waiver is four-eyes (self-approval refused
  at the domain and at the `CHECK`, each alone), reasoned and its reason screened for card and account
  numbers (`INV-LND-09`, `INV-LND-11`). **Owners**: `-016`, `-017`, `-025`.
- **Allocation.** Scenario 2 (`RepaymentDatabaseTest#theSameRepaymentTwiceRepaysOnce`,
  `AutoCollectionDatabaseTest#twoSweepersCollectOnce`), scenario 3
  (`RepaymentDatabaseTest#twoRepaymentsOnOneInstalmentSerialiseAndConserve`) and scenario 6
  (`RepaymentReversalDatabaseTest#aReversalAfterAllocationReopensWhatItPaid`) green; Σ allocation =
  amount for every repayment (the deferred trigger, proven alone by a raw-SQL writer); no due account
  negative at rest; a repayment, an auto-collection and a billing on one due date never collect twice
  (`collection_attempt UNIQUE (due_item_id, attempt_date)`); a reversal negates allocations by rows,
  leaves later allocations untouched and is refused for a repayment that closed the account
  (`INV-LND-04`). **Owners**: `-018`, `-019`, `-020`.
- **Payoff.** A quote is immutable with its inputs; execution names the quote, requires a
  `MULTI_FACTOR` session, runs on its good-through date only and is born once (`UNIQUE
  (quote_id)`); a recomputed amount different from the quote is `409 lending.PayoffQuoteStale` with
  nothing posted; after a payoff all six per-account balances are zero and any credit balance is
  refunded; the early-settlement rebate is zero by construction, tested; a line's payoff closes it
  `CLOSED_BY_CUSTOMER`. Scenario 7
  (`PayoffDatabaseTest#payoffDuringARepaymentIsStaleOrClosedNeverWrong`, both orders occurring) and
  scenario 10 (`PayoffDatabaseTest#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure`)
  green (`INV-LND-08`). **Owners**: `-026`.
- **Delinquency.** DPD equals the date difference between the oldest past-due item's due date and
  the business date (database clock, pinned zone) for 100% of accounts in every storm round and over
  a generated history; buckets follow the terms bounds; DPD 89 is not defaulted and DPD 90 is
  (`LoanDefaulted` once), cure clears it (`LoanDefaultCleared` once); each condition change is
  recorded once (`UNIQUE (loan_id, business_date, kind)`), a re-run day writes nothing, a missed day
  is caught up, a terminal account is skipped; the condition table refuses `UPDATE` and `DELETE` for
  every role; default posts nothing and accelerates nothing (`INV-LND-12`). **Owners**: `-024`,
  `P11-TST-001`.
- **Ledger.** Every lending balance derivable from journal lines and replayable from zero; the
  subledger proof zero for every account and the trial balance zero per currency over every lending
  posting, at rest in every storm round; the ten new purposes in the `AccountPurpose` `CHECK` guard,
  all closed to free adjustments; only lending's operations post to loan purposes and only capital
  recognition to `LENDING_CAPITAL` (`LendingBooksHaveOnePosterTest`, a planted poster refused);
  `LOAN_WRITE_OFF_EXPENSE` carries zero journal lines; every posting rule of `PHASE_11_PLAN.md` §12.8
  exercised, each entry balanced per currency with `posting_date = value_date =` the database
  business date. The financial supplement as it applies: **F1** the trial balance above; **F2**
  `LoanReplayProof` and the subledger proof; **F3** the Idempotency criterion; **F4** repayment
  reversal, fee and interest waivers and the fee refund implemented and tested, no `AdjustmentService`
  on a loan account, write-off deferred to Phase 14; **F5** lending consumes no event for correctness
  — a duplicated `CreditDecisionRecorded` writes no second offer, payout outcomes are read, provider
  callbacks remain payments' and the inbox's; **F6** the Multi-instance criterion; **F7**
  `NoFloatingPointMoneyRulesTest` over `lending`, a planted `double` refused; **F8** capital
  recognised through reconciliation (`-004`), the external payout reconciled by payments' and Phase
  8's machinery, internal flows proven by the subledger proof, and the safeguarding proof that
  subtracts `lending_funded` recorded with Phase 14/15 as owner. **Owners**: `-003`, `-004`, `-029`,
  `P11-TST-001`.
- **Revolving credit line.** Every draw at most the available limit — the limit less drawn principal,
  derived under the line's row lock — two draws racing for it leaving one admitted and one `422
  lending.LimitExceeded`, and no line's drawn principal above its limit at rest; a draw refused `409
  lending.DrawsSuspended` while past due or defaulted; draws credit the wallet only; one statement per
  (line, cycle end) under a duplicate production; the minimum payment exact under every floor and
  ratio interaction, due 25 days after the statement date; opening and closing balances derived,
  never stored; a principal repayment restores the limit at commit, interest and fees never consume
  it; `ACTIVE → CLOSING` admits no further draw and the line closes in the transaction that zeroes its
  last receivable (at the request when nothing is drawn); exposure counts an `ACTIVE` line's committed
  limit and a `CLOSING` line's drawn principal, while capital is consumed by drawn principal only
  (`INV-LND-14`). **Owners**: `-006`…`-009`, `-013`, `-021`, `-022`, `-023`.
- **Capital and exposure.** Capital headroom ≥ 0 at every commit — acceptances and draws racing for
  the last capital leaving one admitted and `422 lending.CapitalUnavailable`, and `LendingCapitalProof`
  green at rest in every storm round; capital only from bank evidence — a contribution four-eyes,
  recognised only by bank recognition matching its `LENDING_CAPITAL_CONTRIBUTION` expectation, lapsed
  when the expectation ages out, every capital journal line a recognised contribution's; consumption
  one-to-one under the profile lock — ten consumers of one decision consume once, consume versus decide
  for one party serialise, the `valid_until` boundary under ±5 s skew yields exactly one of *reserves*
  and *consumable*, and the application role's direct `INSERT` is refused; `PlatformCreditExposure`
  version 2 one statement over journal lines, decisions made under version 1 replaying identically;
  the `ExposureCensus` zero violations in every storm round (`INV-LND-06`, `INV-LND-13`). **Owners**:
  `-001`, `-004`, `-012`, `-013`, `-021`, `-029`, `P11-TST-001`.
- **Multi-instance correctness.** Every one of the twenty-five contention rows of `PHASE_11_PLAN.md`
  §7.4 raised ten ways (two instances where the row is a pair) and counted, each arbiter surviving a
  lock-bypass probe; the lending lock order (L0 the party's profile … L8 the projections) written in
  `DISTRIBUTED_EXECUTION.md` §3 by its first multi-lock writer and no `40P01` in any storm run; the
  lock-wait tests for L0, L3 and L6 asserting the waiting statement; advisory namespace `11` registered
  by its first writer; the four leaderless schedules (`LoanOriginationSchedule`,
  `LoanDisbursementSchedule`, `LoanPayoutSchedule`, `LoanServicingSchedule`) registered in
  `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` (twenty-two → twenty-six) with
  their `…sweeper.enabled` gauges; no connection held across payments' dispatch; every task's
  ten-instance answer `PASS` on its named tests, never by construction. **Owners**: `-001`, `-004`,
  `-005`, `-010`…`-027`, `P11-TST-001`.
- **Idempotency.** Every keyed door of `PHASE_11_PLAN.md` §9 — application, withdrawal, acceptance,
  decline, repayment, draw, closure, payoff quote and payoff, prepayment, amendment acceptance and
  every operator act (waiver, reversal, amendment, terms, capital) — under ten requests with one key
  makes one effect and replays one response, `409` while in progress; every system step (offer,
  disbursement, payout, accrual, billing, statement, collection, condition) converges on a retry,
  duplicate or second instance by its born-once unique, its conditional edge and its ledger key,
  nothing doubled; a payout re-drive replays payments' key. **Owners**: each task that builds a door or
  a step.
- **Failure recovery.** A crash after each step — acceptance, disbursement, the payout's claim,
  dispatch and record, an accrual run, billing, a repayment, a payoff — is re-driven by another
  instance with nothing doubled; scenario 9
  (`LendingOutboxAtomicityDatabaseTest#aCrashBetweenPostingAndPublicationLosesNothing`) green — a
  rollback leaves neither entry nor event, a commit with the relay killed publishes once after
  restart; the storm's crash points, two killed backends included; each of the fourteen failure
  scenarios of `PHASE_11_PLAN.md` §14 a test or a documented, accepted rationale. **Owners**: `-014`,
  `-015`, `-016`, `-018`, `-026`, `P11-TST-001`.
- **Security and audit.** The five permissions and three roles carry `RoleNameTest`'s exact grants;
  every route is in `RoutePermissionRegisterTest` with its negatives, another party's id answering
  `404 lending.NotFound`; a `MULTI_FACTOR` session required for the application, the acceptance, a
  draw, a payout destination, a closure, a payoff and an amendment's acceptance, a lesser session
  refused with nothing written; standing checked in-transaction for application, acceptance and draw;
  no route lets an operator create a loan, reprice outside an amendment, draw or disburse; every
  four-eyes act self-approval-refused at the domain and the `CHECK`, each alone, and an employee
  refused `lending.SelfDealingRefused` (audited `FAILED`) on their own party's loan; every new column
  classified under `ColumnClassificationTest`, the payout destination an opaque payments reference
  (`INV-RAIL-03`); the needle walk extended to lending's doors, no amount, rate, balance, limit, DPD,
  party or loan id in a log line, metric tag, span attribute, event beyond its own amounts, or
  exception message; every audit act of `PHASE_11_PLAN.md` §11 catalogued in `AUDITABLE_ACTIONS.md`
  and recorded in its act's transaction, losers recording nothing, every explanation, replay and report
  read audited. **Owners**: `-001`, `-002`, `-004`, `-005`, `-010`…`-027`, `-029`, `-030`; the full walk
  `P11-TST-001`.
- **Observability.** `PHASE_11_PLAN.md` §15's series are registered eagerly at startup with their
  closed tags and published by a freshly started instance (`PlannedMetersExistTest`, armed by the
  phase's flip to `COMPLETE`), no amount, party or loan id in any tag; every alert fires in a test
  that breaks its condition — `disbursement.pending.age`, `payout.dispatched.age`, `accrual.lag`,
  `allocation.anomaly`, `subledger.proof`, `replay`, `capital.proof`, `capital.headroom.low`,
  `terms.active`, the four `…sweeper.enabled` gauges and `finapp.credit.exposure.census`; the rules in
  `infra/prometheus/rules/lending.yml` resolve against a live scrape; portfolio, delinquency and
  capital amounts are audited reports, never series. **Owners**: `-030`, and each task for the series
  it ships.
- **Testing.** The ten mandatory scenarios of `PHASE_11_PLAN.md` §13.2 are each a named test, green,
  and each exercised in the storm; the lending storm (`P11-TST-001`) green in three consecutive fresh
  runs with its probes caught; the battery (`P11-TST-002`) of ≥ 10,000 generated loans and ≥ 2,000
  generated lines, every figure replayed `IDENTICAL` in two JVMs, a perturbed accrual, billing,
  statement, allocation or agreement byte flipping the verdict; each task's probe recorded in
  `MUTATION_TESTING.md` §2 and caught, and `P11-TST-*` given their §4 rows. **Owners**: every task;
  `P11-TST-001`, `P11-TST-002`.
- **Documentation and invariants.** Every `Phase: 11` invariant in `FINANCIAL_INVARIANTS.md` —
  `INV-LND-01`…`14`, **read from the catalogue, not from the phase plan** — has a
  `MUTATION_TESTING.md` §2 demonstration; ADR-0090…0100 are read against the code and `Accepted` (or
  amended) at `P11-DOC-001`; `LENDING_LIFECYCLES.md`, the glossary, `DOMAIN_MODEL.md`,
  `MODULE_ARCHITECTURE.md`, `BOUNDED_CONTEXTS.md` (credit the only writer of the consumption),
  `DISTRIBUTED_EXECUTION.md` §3, `DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md`, `ERROR_CONTRACT.md`
  and `OPERATIONS_RUNBOOK.md` §7 (the conditions of the first production terms activation) are
  current; the production-origination constraint is recorded in the review; the debt rows are written
  with their owners — lending cannot originate in production until a real bureau exists (the phase
  that answers unresolved #13/#14, Phase 15 at the latest), refinance (the Phase 11 → 12 transition),
  write-off and provisioning (Phase 14), collections (Phase 13), the agreement and evidence purge
  (Phase 15), the safeguarding proof (Phase 14/15), capital tranches if the storm's measurement
  demands them (Phase 16), and `-028` with its owner if cut. **Owners**: `P11-DOC-001`, with each task
  writing its own documents as it lands.
- **Universal criterion 7.** The owner re-affirmed (2026-10-10) skipping the fleet-wide
  `databaseTest` and `kafkaTest` tiers for Phase 11: each task runs its own tests, the exit runs the
  hermetic and architecture tiers, the storm and the battery. A standing, recorded deviation, not a
  pass — asked again at the Phase 11 → 12 transition.
- **The production reality.** Production originates nothing until unresolved #13 and #14 are
  answered: it composes only fail-safe credit sources, no production decision can be `APPROVED`, and
  no lending terms version is activated (`422 lending.ProductNotOffered`). Every functional criterion
  above is proven by the test tiers and the storm against the simulators; the gate review records the
  constraint as an accepted limitation, not a deviation from any criterion (`PHASE_11_PLAN.md` §1.1).

*Extended by the Phase 10 → 11 transition (2026-10-10): the five criteria above predate ADR-0090…0100
and said nothing measurable about origination, the contract, disbursement on two paths, the revolving
credit line, lending capital, exposure under concurrency, delinquency, servicing corrections,
security, audit, observability or the ten-instance question — the things Phase 11's review will be
judged on. Each of the five is kept as written and made measurable above; the per-area criteria are
the additions, written from `PHASE_11_PLAN.md` (whose §20 summarises them) and the invariants
`INV-LND-01`…`14`. Every count is taken from a fresh run, and each criterion names the tasks that
build its proof (**Owners**). A criterion that rests on the cut candidate `-028` (partial
prepayment) is met by the task or by its deferral recorded with an owner. Nothing of Phase 11 is
built at the transition.*

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
