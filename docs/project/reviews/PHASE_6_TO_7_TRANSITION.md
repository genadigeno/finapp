# Phase 6 → Phase 7 Transition

**Conducted:** 2026-09-24
**Parts:** Phase 6 completion audit · multi-instance audit · payment-boundary audit · financial
correctness · idempotency · consistency and atomicity · security · data and tenancy · webhooks and
events · reconciliation readiness · testing · architecture drift · blocking issues and repair ·
Phase 6 completion · Phase 7 initialisation
**Constraint:** this transition ran under the rule that **CRITICAL and IMPORTANT Phase 6 defects
are resolved before the transition, never deferred as debt**. So, unlike the five transitions
before it, it wrote application code: the repairs in §13, each tested, each broken on purpose to
prove its test, and each recorded. It wrote nothing of Phase 7.

---

## Verdict

| Part | Outcome |
|---|---|
| 1. Phase 6 completion audit | **`PASS` after repair.** The eight concepts are held apart; the audit found two CRITICAL and nine IMPORTANT defects, all repaired |
| 2. *"Would Phase 6 remain correct if 10 instances executed relevant operations concurrently?"* | **`FAIL` as found** (§2: seven contended paths). **`PASS` after repair**, every repaired arbiter raced |
| 3. Payment boundary | `PASS` — checkout and merchant consume the payments machinery through two ports and one surface; neither holds payment state |
| 4. Financial correctness | **`FAIL` as found** (the closed-wallet capture, the refund hold released on a re-send, the stranded payable). **`PASS` after repair** |
| 5. Idempotency | **`FAIL` as found** (a finished refund re-dispatched on takeover; a derived key another customer could claim first). **`PASS` after repair** |
| 6. Consistency and atomicity | **`FAIL` as found** (a close beneath a confirmation, twice). **`PASS` after repair**; no atomicity is claimed across the provider boundary |
| 7. Security | **`FAIL` as found** (a denied identity left on the worker thread; a token printed by a generated `toString`). **`PASS` after repair**, limits stated and owned |
| 8. Data and tenancy | `PASS` — tenancy in every statement; one minor disclosure repaired |
| 9. Webhooks and events | `PASS` after repair — the refund webhook now applies its answer on the locked row, and losing resolvers record nothing |
| 10. Reconciliation readiness | `PASS` — every link is a stored identifier, both directions |
| 11. Testing | **The full battery fleet-wide, twice**: before repair on untouched `82b2179`, **1550 hermetic / 1027 database / 14 kafka, 0 failures**; after repair from clean, **1558 / 1051 / 14, 0 failures**. Green before repair, which is §11's point |
| 12. Architecture drift | Found and corrected in six ADRs, eleven documents and registers, five Java comments and the README |
| **Phase 6** | **`COMPLETE`**, confirmed after repair |
| **Phase 7** | **Entry gate: all twelve criteria hold → `READY`** |

Phase 6 was ruled `COMPLETE` by its exit review earlier today
([`PHASE_6_REVIEW.md`](PHASE_6_REVIEW.md), `P6-DOC-001`). This is the second, independent pass
the gate model requires. It ran three read-only audits beside its own reading — sibling defects of
the review's findings, every money path, and tenancy and security — and ran the full battery the
standing instruction had kept from running all phase. **The battery was green and the phase was
not correct.** Both CRITICAL defects sat in code every test passed over.

---

## 1. Phase 6 completion audit

Phase 6 read as one capability — merchant, fee schedule, checkout session, order, payable, refund,
destination and payout — and against Phase 5's machinery it consumes.

| Area | Verdict | Evidence re-checked |
|---|---|---|
| Merchant domain and account | `PASS` after repair | Onboarding behind KYB, one payable per currency; **a close could strand a payable no payout can reach** (I5), now refused as `merchant.NotSettled` |
| Checkout session | `PASS` after repair | Priced at creation, token hashed and shown once, expiry by a leaderless conditional sweep; **a confirmation that lost the open was told `EXPIRED`** (sibling #9), now converges |
| Order | `PASS` | Created in the capture's own transaction; `UNIQUE (session_ref)` the second rank; `COMPLETED_LATE` for a capture landing after expiry |
| Payment intent and attempt integration | `PASS` after repair | The intent opened in the confirming transaction under a derived key — **in the public command's scope** (I9), now its own |
| Merchant authorization and configuration | `PASS` | Keys hashed, the standing in the authentication join, 32 routes pinned to their permissions; the fee version pinned on the session |
| Fees and pricing | `PASS` | Fee computed once, net by subtraction; a sale that does not cover its fee refused at the price |
| Payment lifecycle boundaries | `PASS` | §3 |
| Settlement readiness | `PASS` | Per-attempt references and the payable's derivation; settlement itself is Phase 8's |
| Webhook and event integration | `PASS` after repair | §9 |
| Idempotency, concurrency, persistence, transaction boundaries, consistency | `PASS` after repair | §2, §5, §6 |
| Failure handling | `PASS` after repair | The refund re-send (C1), the stranded authorization (I6), the zero bound (I1) |
| Security and audit | `PASS` after repair | §7 |
| Observability | `PASS` after repair | The stuck-dispatch gauges were blind to a crashed dispatch (I2), now counted |
| Testing and documentation | `PASS` after repair | §11, §12 |

**The separation holds, and Phase 6 duplicates nothing Phase 5 owns.** A **checkout session** is an
offer and its price; an **order** is the commercial fact of a paid session, created only by a
landed capture; a **payment intent** is the payer's instruction and an **attempt** its one
operation at the provider; **authorization** is a payment-domain fact with no ledger effect;
**capture** is the ledger's first touch; **settlement** is not recorded at all before Phase 8; and a
**merchant payout** is a separate outbound operation bounded by the payable. `checkout` holds a
reference into `payments` and never the reverse; `merchant` never sees `payments`; the payment
states are the payment's own.

---

## 2. Multi-instance audit

Ten instances of everything. The audit asked each item of the gate's list of every Phase 6 path.

| Concern | As found | After repair |
|---|---|---|
| Concurrent checkout creation, duplicate checkout requests | Correct: the claim keyed per merchant, ten creates one session and one token | — |
| Duplicate order creation | Correct: the conditional completion and `UNIQUE (session_ref)` | — |
| Duplicate payment initiation | **Wrong answer to a loser**: ten confirmations converged on one intent, but the losers of the open were told `EXPIRED` (sibling #9); and **a stranger could claim the derived key first** (I9) | The loser rolls back and re-reads; the key claims in `checkout.payment` |
| Concurrent order updates | Correct: conditional edges | — |
| Concurrent merchant configuration changes | **Wrong**: the confirmation read the merchant's standing without a lock, so a close committing a moment later was read around (I5) | `FOR SHARE` against the close's `FOR UPDATE`, raced |
| Duplicate webhooks | Correct: the inbox's primary key, then conditional transitions | The refund webhook's answer is now applied on the locked row |
| Payment retries; timeout then retry | **Wrong for refunds**: a takeover's re-send whose connection was refused concluded `FAILED` and released the hold (C1); a takeover that found its refund finished dispatched a fresh one into `V008`'s index, a `500` with the claim stuck (I4) | The send permit on every refund send; the takeover converges on the found refund |
| Service restart | **Wrong for a stranded `AUTHORIZED`**: only the HTTP surface chained the capture, so an authorization resolved by query or webhook was never captured (I6) | The sweep's stranded-authorization leg |
| Database race conditions, lost updates, stale reads | **Two races**: a customer's account close beneath a confirmation (C2); a merchant close beneath a confirmation (I5) | Both serialised by a lock pair, both raced deterministically with the loser observed lock-waiting |
| Duplicate events, event ordering | Correct: outbox and inbox, order-blind consumers | — |
| Distributed scheduling | **Wrong in two details**: a zero bound accepted (I1); losing resolvers audited acts they did not perform (I8) | Refused at construction; acting-only audit, ten sweepers raced |
| Partial failures | **Wrong for the payout's first send**: judged on the request, not the locked row (I7) | Judged against the permit the flight stored |
| Inconsistent state between checkout, order and payment | Correct: the order and the session's completion commit with the capture | — |

**Answer: *"Would Phase 6 remain correct if 10 instances executed relevant operations
concurrently?"* — `FAIL` as found; `PASS` after repair.** Every contended decision's arbiter is
named in `DISTRIBUTED_EXECUTION.md` §3, every repaired one is raced in a test, and nothing rests on
one process: the permit is a committed column, the locks are PostgreSQL's, and the sweeps stay
leaderless — queries idempotent, writes conditional, sends only under a permit.

---

## 3. Payment boundary audit

`Checkout → Order → Payment Intent → Payment Attempt → Payment Infrastructure → Provider → Payment
State` holds as a chain of ownership. The checkout opens its payment through `PaymentCreation`
with its own `PaymentParticipants` wiring (the account credited is the merchant's payable, read
from the ledger by the merchant the session names) and confirms it through `PaymentService.confirm`,
the one implementation of "confirm and chain the capture". The capture posts the lines
`CaptureComposition` hands it, so `payments` knows no fee and no merchant. **Checkout and merchant
never become the source of payment state**: the session reads its intent's truth from `payments`,
and the provider's vocabulary stays behind the adapter (`INV-PAY-03`). The transition's repairs
kept this: the closed-account check is asked of the port (`creditable`), the in-flight question is
a port `app` implements for `accounts` and `merchant` (`PendingCredits`, `PayableInFlight`), and no
build-graph edge moved.

---

## 4. Financial correctness

Currency explicit and money in minor units with scale everywhere; the fee computed once under a
pinned version, the net by subtraction, so no residual exists; history immutable and corrections
by new entries. **As found, three flows could lose or strand value**, and each is repaired:

| Flow | Defect | Repair |
|---|---|---|
| Wallet top-up | **C2**: the customer closes the wallet between creation and confirmation; the card is captured; the ledger refuses the posting to the closed account; the payment sticks at `CAPTURE_DISPATCHED` — money taken, nothing booked | The close refuses while a payment crediting the account is in flight (payments `V010`'s index serves the question); the confirmation share-locks the account and refuses a closed one before anything is sent |
| Refund | **C1**: a re-send's refused connection released the hold of a refund a first send may already have paid — value leaving twice | The send permit (payments `V009`); a refused connection proves nothing unless it answered the first send |
| Merchant close | **I5**: a merchant still owed money could be closed, leaving its payable where no payout can reach it | `requireSettled`: balance zero, no hold standing, no payment in flight |

The checkout's flow, end to end:

`Customer → Checkout session` (priced at creation under the version it carries; no ledger effect)
`→ confirmation` (the intent opened under `checkout:<checkoutId>` in `checkout.payment`, the fee
pinned, the session `PAYMENT_PENDING`, one transaction; no ledger effect) `→ Payment Attempt`
(`AUTH_DISPATCHED` with our minted reference, committed before the call) `→ Provider` (called
holding no connection) `→ Payment Result` (`AUTHORIZED`, no ledger effect; capture chained)
`→ Ledger`: **one entry keyed `payment-capture:<attemptId>`** — DR `SETTLEMENT_CLEARING` gross, CR
`MERCHANT_PAYABLE` gross, DR `MERCHANT_PAYABLE` fee, CR `FEE_REVENUE` fee — committed with
`CAPTURED`, the intent's `SUCCEEDED`, the session's completion and the order `→ Merchant settlement
preparation`: the payable is the ledger position; a refund holds on it what its composition takes;
a payout holds, dispatches under a permit and posts DR `MERCHANT_PAYABLE` / CR `PAYOUT_CLEARING` on
completion.

**Where Phase 6 has no new ledger effect**: session creation, confirmation, expiry and withdrawal;
onboarding, suspension and close; fee schedule versions and assignments; destination proposals and
approvals. Each is a domain fact or an audited act, never a posting.

---

## 5. Idempotency

| Operation | Mechanism | As found |
|---|---|---|
| Checkout creation | Claim `checkout.session:<merchantId>`; the replay renders no token | Correct |
| Order creation | Conditional completion; `UNIQUE (session_ref)` | Correct |
| Payment initiation | Claim on the derived key | **I9**: in `payment.create`, any customer could claim `checkout:<checkoutId>` through `POST /v1/payments` and leave the session unpayable. Now `checkout.payment` |
| Payment confirmation | The intent's conditional transition; the losers converge | **Sibling #9**: the checkout's loser was told `EXPIRED`. Now converges |
| Refunds | Two-transaction claim, then the `V008` dispatch key | **I4**: a takeover finding its refund finished dispatched fresh. Now converges, or refuses a reused key with different facts |
| Webhooks | Inbox primary key; conditional transitions | Correct |
| Payouts | Claim per merchant; `UNIQUE (merchant_id, dispatch_key)`; the send permit | Correct, with I7's judgement moved to the row |

Nothing rests on process memory: every claim, key and permit is a row.

---

## 6. Consistency and atomicity

| Workflow | Authoritative state and owner | Transaction boundary | Locking | Idempotency | External dependency and recovery |
|---|---|---|---|---|---|
| Order ↔ payment | The attempt (`payments`); the order (`checkout`) | The capture's one transaction creates the order | Conditional edges | Posting key; `UNIQUE (session_ref)` | The sweep resolves an unknown capture |
| Checkout expiry ↔ payment | The session (`checkout`) | One transition per row | Conditional `OPEN`/`PAYMENT_PENDING → EXPIRED` | The machine | A capture landing late: `COMPLETED_LATE` |
| Cancellation ↔ payment | The intent | One transaction | Conditional `REQUIRES_CONFIRMATION → CANCELLED` | The machine | None: cancellation wins only the confirmation window |
| Refund ↔ order | The refund (`payments`); the order unchanged | Dispatch, provider call, outcome — three steps, never one | Attempt row, then the account's `FOR UPDATE` | Claim, dispatch key, **send permit** | The sweep's refund leg (new) |
| Merchant configuration ↔ active checkout | The merchant (`merchant`); the pinned version | The confirmation's transaction | **`FOR SHARE` on the merchant row** (new) | — | — |
| Payment success, delayed webhook | The attempt | The first resolver's transaction | Conditional; the loser records nothing (new) | Inbox | The sweep usually wins; the webhook converges |
| Payment timeout, unknown state | The attempt | `*_UNKNOWN` committed, nothing posted | Conditional | Our stored reference | Query; a refund the provider never saw is re-driven (new) |

**No distributed transaction is assumed.** Order, payment and provider are three authorities in
three steps; what holds them together is the dispatch-before-call discipline, the conditional
transitions and the sweeps.

---

## 7. Security

Merchant authentication by hashed, shown-once API keys with the standing in the lookup; operator
routes behind named permissions, 32 pinned by `RoutePermissionRegisterTest`; the customer's
confirmation demands a session and the token; webhooks verified by HMAC before parsing; confined
credentials pinned to the variables their configuration binds. **As found:**

- **I3 — a permission denial left the denied identity on the worker thread.** The interceptor
  closed its scope only in `afterCompletion`, which Spring never runs for an interceptor whose
  `preHandle` threw; the thread's next request could have run as somebody nobody authorised. The
  scope now closes on the refusal path; the probe's leak bled into the next test on the thread.
- **S-F3 — the checkout's records printed the token, the amount and the line summary** through
  generated `toString`s. Three guards exempt the creation view's token on the stated ground that an
  override closes the logging half; it had none. Overridden, and tested, with the merchant key's
  view given the test its exemption claimed.
- **S-F5** — §8.

`SECURITY_ARCHITECTURE.md` was silent on Phase 6; it now states the merchant's credentials and the
refusal-path rule. **Limits, owned:** no per-source rate limiting (Phase 15, recorded); operators
audited with the customer actor type (recorded, attributability intact); a refused step-up leaves
no audit record (recorded).

---

## 8. Data and tenancy

Tenancy is a predicate in every statement (`merchant_id = ?`), held by `OwnershipIsScopedTest`
(widened in the tenant's two packages) and by the tenancy battery that derives its routes from the
handler mapping. Unique constraints, composite foreign keys and partial indexes carry the arbiters;
the transition added `V009`'s permit column and constraints and `V010`'s in-flight index, each
classified. **As found: S-F5** — a second holder of a checkout token, on a session mid-payment,
reached the payments surface's own `404`, whose words (*no such payment*) told them the token was
live and the session being paid; the confirmation now answers the session's one `404`. No merchant
can read or change another's data; the one tenancy-adjacent defect, I9, was a shared claim
namespace, repaired.

---

## 9. Webhooks and events

Authenticated before parsing, freshness-bounded, evidence first, deduplicated by the inbox, applied
through conditional transitions, order-blind. Events carry the full envelope, publish through the
outbox and are consumed through the inbox; an abandoned event has a documented manual procedure
(tooling, Phase 15). **As found:** the refund webhook applied its answer without the row lock the
permit judgement needs — it now locks through `lockForOutcome` (C1's fix) — and every losing
resolver, webhook or sweep, appended an outcome audit record for an act it did not perform (I8). A
duplicate or delayed event cannot move a checkout, order or payment state twice.

---

## 10. Reconciliation readiness

Payment ↔ merchant order ↔ provider transaction ↔ settlement ↔ merchant payout, every link a
stored identifier: the session holds its intent; the order its session; the attempt our minted
authorization and capture references and the provider's; the journal entry its key
(`payment-capture:<attemptId>`, `payment-refund:<refundId>`, `merchant-payout:<payoutId>`); the payout
our reference, the destination's opaque provider reference, and the provider's answer as encrypted
evidence. Settlement files are Phase 8's; everything they will match against is recorded.

---

## 11. Testing — and the finding

**The full battery ran fleet-wide twice.**

| Run | Hermetic | Database | Kafka |
|---|---|---|---|
| Before repair — untouched `82b2179`, `clean build databaseTest kafkaTest --continue` | 1550 tests, 238 suites, 14 modules, 0 failures | 1027 tests, 136 suites, 2 modules, 0 failures | 14 tests, 4 suites, 2 modules, 0 failures |
| After repair — from clean, the same command | 1558 tests, 240 suites, 14 modules, 0 failures | 1051 tests, 136 suites, 2 modules, 0 failures | 14 tests, 4 suites, 2 modules, 0 failures |

**Green before repair is the finding.** The meaningful categories the gate names each have suites —
checkout and order lifecycles, payment integration, duplicate creation, concurrent operations,
idempotency, webhook duplication and delay, provider timeout, payment failure, cancellation, refund
interactions, authorization, tenant isolation, persistence — and all of them passed over two
CRITICAL defects. What the tests had not asked: whether a re-send's refused connection proves
anything, and whether the account a payment will credit still exists when the card is charged.

**The repairs arrive with tests that fail when the repair is removed**: thirty-three probes across
thirteen findings, all caught, every restore verified byte-identical, recorded in
`MUTATION_TESTING.md` §2 with the limits in §3. Two were caught only by the race test that
choreographs the lock, as designed: the confirmation's share lock on the account, and the standing
read's share lock on the merchant.

---

## 12. Architecture drift

| Where | Drift | Correction |
|---|---|---|
| ADR-0004, -0045, -0046, -0048, -0053, -0057 | Accepted ADRs silent on what the repairs changed; ADR-0045's `VOIDED` revisit owed to Phase 6 and not done | Amendment notes with provenance; `VOIDED` re-owned to `P7-TSK-004` |
| `PAYMENT_LIFECYCLES.md` §4, §7 | No send permit; the sweep "attempts only" | Stated |
| `DISTRIBUTED_EXECUTION.md` §3 | The refund, schema, account, merchant and checkout rows and the sweeper section predated the repairs | Updated |
| `AUDITABLE_ACTIONS.md` | Four rows said what the records do not (two accounts named that are not, a key "not yet" carried that is, losers not excluded) | Corrected |
| `ERROR_CONTRACT.md` | The close's code, the confirmation's inherited codes | Added |
| `SECURITY_ARCHITECTURE.md` | Silent on Phase 6 | The merchant's credentials and the refusal-path rule |
| `DATA_MIGRATIONS.md`, `SECRET_MANAGEMENT.md`, `compose.yaml`, `README.md`, `TESTING.md` | Wrong operator instructions: the migrator's variables, the ordinary roles' passwords, the application's variables, the inbox retention default, the test database hatch | Corrected to what the code reads |
| `README.md` | The roadmap table read Phases 5 and 6 `PLANNED`; the verification state was Phase 4's | Recounted |
| `DECISIONS.md` | The invariant count read 87 | 101, from the catalogue |
| Java comments | Five said what is not so (a retry on the same intent; a lock on the attempt row; a capture chained by a resolver; two store javadocs) | Corrected |
| `CURRENT_STATE.md` | "Last updated" stale since `P5-TST-003`; a debt row's "no second rank" | Corrected |

No shared mutable state, no single-instance assumption and no hidden coupling was found beyond the
defects above; no decision was found in code without its record.

---

## 13. Blocking issues and repair

| ID | Severity | Finding | Repair | Proven by |
|---|---|---|---|---|
| C1 | **CRITICAL** | A taken-over refund's re-send whose connection is refused concluded `FAILED` and released the hold, though the first send may have paid | Payments `V009` (the refund's send permit, forward-only, never on a resolved refund, for every writer); `PaymentRefund.judged` on the locked row; the webhook and sweep lock the row | Three permit tests and the schema test; probes R1–R3, R12, R13 |
| C2 | **CRITICAL** | A customer could close the wallet an open top-up credits; the card was captured into a posting the ledger refused | `PendingCredits` asked under the close's lock (payments `V010`); the confirmation's `creditable` under `FOR SHARE` | The close's test, both confirmation tests, the race; probes C2a–c |
| I1 | IMPORTANT | The payments sweeper accepted a zero bound | Refused at construction | Probe T1 |
| I2 | IMPORTANT | No refund resolution by query; the stuck gauges blind to a crashed dispatch (Phase 5's debt) | The sweep's refund leg with re-drive under a permit; both gauges count `DISPATCHED` past the bound | Probes R6–R10 |
| I3 | IMPORTANT | A permission denial left the identity on the worker thread | The scope closed on the refusal path | Probe T2 |
| I4 | IMPORTANT | A takeover finding its refund finished dispatched fresh — a `500`, the claim stuck | Converge on the found refund; `RefundKeyReusedException` for different facts | Probes R4, R5 |
| I5 | IMPORTANT | A merchant still owed money could be closed; the confirmation read its standing unlocked | `requireSettled` under the close's locks; `merchant.NotSettled`; the standing read `FOR SHARE` | Probes I5a–d |
| I6 | IMPORTANT | An `AUTHORIZED` nothing chained was never captured | The sweep's stranded-authorization leg | Probe R11 |
| I7 | IMPORTANT | The payout's first send judged on the request, not the row | Judged against the stored permit | Probe I7 |
| I8 | IMPORTANT | Losing resolvers audited and reported the verdict | Acting-only | Probe I8 |
| I9 | IMPORTANT | The checkout's derived key could be claimed first through the public command | `checkout.payment` | Probe K1 |
| S-F3 | MINOR | Generated `toString`s printed the token, the amount and the line summary | Overrides and tests | Probes K5–K9 |
| S-F4 | MINOR | A foreign instrument at checkout answered `500` | `payments.UnknownInstrument` | Probe K3 |
| S-F5 | MINOR | A token holder learned a session was mid-payment | The session's one `404` | Probe K4 |
| #9 | MINOR | A confirmation that lost the open was told `EXPIRED` | Roll back and re-read | Probe K2 |
| Drift | MINOR | §12 | Corrected | — |
| Recorded | MINOR | A second verified contact channel answers `500` (Phase 1's code); three confirmation interleavings unraced; a poisoned row can take a sweep slot | Recorded with owners in `CURRENT_STATE.md` | — |

**The gate was repeated after repair**: every area above re-read against the repaired code, the
ten-instances question answered again over the repaired arbiters, and the full battery re-run from
clean. **Phase 6 passes.**

---

## 14. Phase 6 completion

**Phase 6 is `COMPLETE`** — ruled by `P6-DOC-001` and confirmed here after repair. **Delivered**: a
merchant the platform does not own can sell through it and be paid — onboarded behind KYB, priced by
an immutable versioned schedule pinned when the offer is made, credited the gross and charged its
fee in the capture's one entry, refunded out of its own payable, and paid out under the bound to a
destination two operators approved — with tenancy in every statement. **Decisions**: ADR-0050…0054
and ADR-0056…0058 `Accepted`, amended with this transition's provenance where it changed what they
describe; ADR-0055 the owner's to accept. **Remaining non-blocking debt** is recorded with owners in
`CURRENT_STATE.md` §Known Architectural Debt; none is financial-correctness debt.

---

## 15. Phase 7 initialisation

**Objective.** Extend the platform from one payment provider to several payment rails with
materially different lifecycles, timing and failure semantics, and introduce disputes — keeping the
payment infrastructure, the ledger, the merchant and checkout boundaries and the distributed-system
guarantees exactly as they are. Planned in [`PHASE_7_PLAN.md`](../PHASE_7_PLAN.md); decided in
ADR-0059…0062 (`Proposed`); the machines in
[`RAIL_AND_DISPUTE_LIFECYCLES.md`](../../domain/RAIL_AND_DISPUTE_LIFECYCLES.md).

**Entry gate** (`PHASE_GATES.md` §2):

| # | Criterion | Holds |
|---|---|---|
| 1 | Hard dependency phases `COMPLETE` | Yes — Phases 5 and 6 |
| 2 | `DELIVERY_PLAN.md` §Phase 7 current and specific | Yes — made current here (instant finality, the chargeback ratio, context 29, card issuing external) |
| 3 | Bounded contexts and aggregates identified | Yes — `PHASE_7_PLAN.md` §3, §4; context 29 added |
| 4 | Invariants identified by ID | Yes — `INV-RAIL-01…04`, `INV-DSP-01…03`, `INV-REV-03`, `INV-SET-01`, `INV-HIST-04`: **ten**, read from the catalogue (101 invariants) |
| 5 | Lifecycles drafted | Yes — the two-step, push and book attempts, the withdrawal and the dispute |
| 6 | Transaction and consistency boundaries stated | Yes — `PHASE_7_PLAN.md` §7 and each task |
| 7 | Idempotency stated for every money-moving command | Yes — §4 and each task |
| 8 | External dependencies and failure modes listed | Yes — §14's fifteen scenarios |
| 9 | Security, audit and reconciliation implications stated | Yes — §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | Yes — 18 items, every field the gate names |
| 11 | Required decisions have ADRs at least `Proposed` | Yes — ADR-0059…0062 |
| 12 | `CURRENT_STATE.md` names the phase active | Yes |

**Exit criteria**: `PHASE_GATES.md` §5 Phase 7, extended here to measurable criteria over card,
wallet, A2A and instant infrastructure, payment/ledger integration, multi-instance execution,
idempotency with the four named scenarios, failure recovery, disputes, routing, security, audit,
observability, reconciliation, testing and documentation.

**First task: `P7-TSK-001` — The rail port, the capability descriptor, and the card rail
declared** — `READY`, not started.
