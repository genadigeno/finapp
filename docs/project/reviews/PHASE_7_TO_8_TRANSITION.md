# Phase 7 → Phase 8 Transition

**Conducted:** 2026-09-28
**Parts:** integrated Phase 7 audit · financial correctness per flow · multi-instance audit ·
payment-rail boundary · idempotency · consistency and atomicity · security · reconciliation
readiness · testing · architecture drift · blocking issues and repair · Phase 7 completion ·
Phase 8 initialisation
**Constraint:** this transition ran under the rule that **CRITICAL and IMPORTANT Phase 7 defects
are resolved before the transition, never deferred as debt** — and under the owner's further rule
that financial, concurrency, security and distributed-system defects are not hidden as future
debt. So, like the Phase 6 → 7 transition, it wrote application code: the repairs in §11, each
tested, each broken on purpose to prove its test, each recorded. It wrote nothing of Phase 8.

---

## Verdict

| Part | Outcome |
|---|---|
| 1. Integrated Phase 7 audit | **`PASS` after repair.** Eight parallel read-only audits and an adversarial verification of every finding: **two CRITICAL** root causes (five audits found them independently), **twelve IMPORTANT** after de-duplication, **fifty-three MINOR** — every CRITICAL and IMPORTANT repaired, the MINOR correctness, concurrency and security ones repaired, the rest recorded with owners |
| 2. Financial correctness | **`FAIL` as found** (a withdrawal's echo parked as inbound value; the inquiry sweep crediting an amount the scheme did not execute; one execution credited AND parked; money stated on a concluded pay-in booked nowhere; authorizations left standing; a closed merchant credited by a win). **`PASS` after repair** |
| 3. *"Would Phase 7 remain financially correct if 10 instances executed relevant operations concurrently?"* | **`FAIL` as found** (§3: a credit/parking race with no arbiter, a dispute deadlock, a permit renewal that could stand still, an undispatched intent confirmable around its checks). **`PASS` after repair**, every repaired arbiter raced |
| 4. Payment-rail boundary | `PASS` — the rail port and its capabilities hold; the repairs added no rail-name branch (the grant and the transport guard at the edges, the claim keyed by the stored rail) |
| 5. Idempotency | **`FAIL` as found** (a parking re-posted with a later day's date; a second presentment absorbed as a repeat). **`PASS` after repair** |
| 6. Consistency and atomicity | **`FAIL` as found** (the cross-table credit/parking pair; the dispute's cross-entry lock order). **`PASS` after repair**; no atomicity is claimed across a provider or the scheme |
| 7. Security | **`FAIL` as found** (a customer's grant concatenated into a credentialed provider request; the provider hop's TLS enforced nowhere; a bank destination printable). **`PASS` after repair** |
| 8. Reconciliation readiness | **`PASS` after repair** — the parking now records what it named, its cycle, its cause and its evidence; one claim per scheme execution explains every instant-rail line; a second presentment is a distinct, counted outcome |
| 9. Testing | **The full battery fleet-wide**, inherited from the phase's recorded deviation: before repair on untouched `7598332`, `build databaseTest kafkaTest` green but for the app database tier — 1054 tests, 3 failed, a heap defect in the harness; after repair, **green — 1745 hermetic tests across 14 modules, 1241 database tests across 149 suites and 14 kafka tests across 4 suites, 0 failures**. Before repair the battery was **not** green — its three failures a harness defect, repaired first (§9) |
| 10. Architecture drift | Found and corrected in four ADRs amended with provenance (0053, 0059, 0061, 0062; the Phase 8 set 0064…0073 drafted beside them), five domain and architecture documents, the registers and the stale code comments §10 and §11 name |
| **Phase 7** | **`COMPLETE`**, confirmed after repair |
| **Phase 8** | **Entry gate: all twelve criteria hold → `READY`**; first task `P8-TSK-001` `READY`, not started |

Phase 7 was ruled `COMPLETE` by its exit review earlier today
([`PHASE_7_REVIEW.md`](PHASE_7_REVIEW.md), `P7-DOC-001`). This is the second, independent pass
the gate model requires. **The review ruled the phase complete and the phase was not correct**:
both CRITICAL defects sat on paths every Phase 7 test passed over, and five of the eight audits
found the first of them on their own.

---

## 1. Integrated Phase 7 audit

Phase 7 read as one capability — rails declared and routed; the card rail completed with its
void, clearing and refunds; the instant rail with its withdrawal, pay-in, returns and callback
door; the wallet as an instrument; disputes end to end — and against Phases 5 and 6, which it
extends. Eight read-only audits ran in parallel, one per area (card, instant, wallet, disputes,
the rail boundary, multi-instance, idempotency, security, callbacks and testing), and every
finding was then verified adversarially — each verifier told to refute it and to default to
refutation when unsure. Five audits found the first CRITICAL on their own.

| Area | Verdict | Evidence re-checked |
|---|---|---|
| Card | `PASS` after repair | The two-step machine, the void, clearing and refunds; **three conclusions left the issuer's authorization standing** (#5, #18) — now the void redirect and a re-sent void; a second, different clearing absorbed as a repeat (#29) — now `SECOND_PRESENTMENT` |
| Wallet | `PASS` after repair | The book rail in one transaction; **an unfunded wallet checkout confirmable for ever** (#11) — now re-checked, refused at the public door and cancelled at expiry |
| Account-to-account and instant | `PASS` after repair | **A withdrawal's echo parked as inbound value** (C1); **the inquiry sweep crediting an amount the scheme did not execute** (C2); one execution credited AND parked (#8, #17, #19); value on a concluded attempt dropped (#9); the grant concatenated into a credentialed request (#10); the expiry that never ends (critic) |
| Payment infrastructure integration | `PASS` | One outcome applier per aggregate; the rail port and capabilities; no core branch on a rail's name |
| Routing | `PASS` | Versioned, pinned, explainable, never re-routed after an ambiguous dispatch |
| Disputes and chargebacks | `PASS` after repair | The combined bound; **a merchant closed with a winnable chargeback** (#6, #14, #21, #27); **a loss reporting its fee deadlocking against other chargebacks** (#12, #16) |
| Ledger integration | `PASS` after repair | Every rail posts to its own clearing; the dispute stages' cross-entry lock order ordered (§3) |
| Reconciliation readiness | `PASS` after repair | §8 |
| Failure handling | `PASS` after repair | The poison delivery (#26); the door's totality holes (#72, #76) |
| Observability | `PASS`, gaps recorded | The rail and dispute meters; the parking now counted where it is written (the observer); the dashboard's pay-in and suspense rows and alert rules recorded (#71) |
| Security and audit | `PASS` after repair | §7 |
| Multi-instance correctness | `PASS` after repair | §3 |
| Testing and documentation | `PASS` after repair | §9, §10 |

**The separation holds.** A **payment intent** is the payer's instruction and an **attempt** its
one operation on one rail; an **authorization** is a promise with no ledger effect and a **void**
its release; a **capture** or an **execution** is the ledger's first touch; **clearing** is the
network's presentment, recorded, never posted; **settlement** is not recorded before Phase 8; a
**withdrawal** and a **return** are the platform's own outbound pushes, each its own aggregate; a
**dispute** is its own lifecycle whose chargeback never takes more than the capture credited. The
repairs moved none of these lines.

---

## 2. Financial correctness, per flow

Currency explicit and money in minor units with scale everywhere; no floating point; history
immutable; every correction a new entry. **As found, seven flows could lose, create or strand
value**, and each is repaired:

| Flow | Defect as found | Repair |
|---|---|---|
| Withdrawal (instant) | **C1**: the scheme's confirmation of a WITHDRAWAL, echoed to the pay-in door by our reference, named no initiation, so it parked DR `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED` — value that went OUT booked as value that came in | The door recognises every reference we mint on the rail; the completion claims its execution, so a restatement without our reference yields too |
| Pay-by-bank pay-in | **C2**: the callback refused a mismatched executed amount; two minutes later the inquiry sweep credited the initiation's ASK — value created (or a surplus unbooked), and `V018`'s return bound resting on a basis only one producer met | The executed amount judged by the one applier for both producers; a mismatch parks the executed value (`AMOUNT_MISMATCH`) and fails the pay-in `DECLINED` |
| Pay-by-bank pay-in | One execution credited to an attempt AND parked (#8) — sequentially when the parking came second, concurrently either way | `payments.scheme_execution_claim` (`V023`): one money fact per scheme execution |
| Pay-by-bank pay-in | An execution on a FAILED attempt, or a second one under another reference, dropped at `INFO` (#9, critic) | Parked (`ATTEMPT_CONCLUDED`), attributed, audited, counted |
| Card top-up and checkout | A capture never sent or never received, and a void never received, concluded `FAILED` with the authorization held (#5, #18) | The void redirect; a never-received void re-sent by its stored reference |
| Merchant close | A merchant closed with a winnable chargeback; the win credited a CLOSED merchant's payable (#6) — the Phase 6 → 7 I5 class, reintroduced by disputes | The close asks whether a chargeback can still be won, and closes the payable's ledger account |
| Refund | A trailing takeover renewed the send permit without changing it, so the first flight's refused connection still concluded `FAILED` — a refund paid at the PSP, failed on the books, its bound freed (#22) | Every renewal strictly advances the permit (refunds and dispute answers) |

**The per-flow chains, as repaired:**

- **Card payment**: `Customer → Intent → Attempt AUTH_DISPATCHED` (our minted reference, committed
  before the call) `→ AUTHORIZED` (no ledger effect) `→ capture` — `CAPTURED`: **one entry
  `payment-capture:<attemptId>`**, DR `SETTLEMENT_CLEARING` / CR the counterparty (four lines for a
  merchant, the fee in the same entry); **never sent, never received or declined**: `VOID_DISPATCHED`
  → `VOIDED`, nothing posted, the authorization released → clearing recorded (no posting,
  `INV-SET-01`), a second presentment counted → settlement: Phase 8.
- **Wallet payment**: `BOOK` born `EXECUTED` in the confirmation's one transaction — DR payer
  wallet / CR counterparty — or refused whole (`WalletPaymentUnfunded`); a checkout's undispatched
  intent re-checked, refused at the public door, cancelled at expiry.
- **Pay-by-bank**: `AWAITING_PAYER` → the payer's PSP decides → `EXECUTED` only when the claimed
  scheme execution's amount equals the ask: **one entry `payment-execution:<attemptId>`**, DR
  `INSTANT_CLEARING` / CR the counterparty; otherwise the executed value parks (DR
  `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED`) and the pay-in fails.
- **Withdrawal**: hold, dispatch under a permit, `COMPLETED` → DR wallet / CR `INSTANT_CLEARING`,
  the execution claimed; its echo is evidence.
- **Instant payment finality**: final on acceptance; settled on the scheme's cycle (Phase 8).
- **Refund and return**: dispatch, hold, the strictly-advancing permit, `COMPLETED` → the capture's
  inverse; a return claims its execution.
- **Chargeback, representment, dispute resolution**: stage postings in the platform rows' order
  (§3); the counterparty's share within the combined bound; a closed counterparty's share parked
  in `CHARGEBACK_RECOVERABLE`; a win the exact inverse; a loss writes off only the excess.

---

## 3. Multi-instance audit

Ten instances of everything. The audit asked each item of the gate's list of every Phase 7 path.

| Concern | As found | After repair |
|---|---|---|
| Concurrent card, wallet, A2A and instant payment requests | Correct: the intent's conditional transition, the claims | — |
| Duplicate payment initiation | Correct for the card; **a checkout's intent opened and never dispatched was confirmable later around its checks** (critic, #11) | Re-checked at the session's door; refused at the public route; cancelled by the expiry sweep, intent row first |
| Duplicate provider or scheme callbacks | **Wrong**: one execution could be credited and parked by two deliveries racing (#8, #17); a fresh-id duplicate on a later day re-posted and was refused for ever (#26) | The claim's primary key; only the claim's winner posts; the ten-way mixed race makes one money fact in three rounds |
| Duplicate routing decisions | Correct: one chosen decision per payment, `V013`'s index | — |
| Duplicate authorization, capture or settlement results | Correct: conditional edges; a second presentment is now distinct | — |
| Concurrent dispute notifications | **Wrong**: a loss that first reports its fee took the recoverable and the costs before the clearing every chargeback takes first — a `40P01` (#12, #16) | `PostingService.lockBalancesInOrder`: every dispute posting takes the platform's rows in the projection's order first; raced over eight rounds |
| Concurrent refunds and disputes | Correct: the attempt lock and the combined bound (`P7-TST-001`, `P7-TST-002`) | — |
| Retries; timeout then retry | **Wrong for refunds and dispute answers**: a trailing takeover's renewal left the permit unchanged (#22) | Strictly advancing renewal |
| Service restart during a payment flow | **Wrong for the card**: a capture stranded dispatched and never received concluded `FAILED` with the authorization standing (#5) | The void redirect |
| Delayed or reordered provider events | **Wrong at the instant door**: a statement read against a row concluded since was acknowledged and dropped (#55, #68) | The applier locks, re-reads and parks value on a concluded row |
| Outbox and inbox | Correct | — |
| Distributed schedulers | Correct: every sweep leaderless, per-row contained | The sweeps' starvation by a poisoned row stays recorded debt (the review widened the row) |
| Stale reads, lost updates | The instant door's unlocked read (above) | Repaired |
| Clock skew | **A hold released by a trailing instance's clock met `V008`'s CHECK** and failed the completion releasing it (#41) | `released_at = GREATEST(?, placed_at)` |

**Answer: *"Would Phase 7 remain financially correct if 10 instances executed relevant operations
concurrently?"* — `FAIL` as found; `PASS` after repair.** Every contended decision's arbiter is
PostgreSQL's — the claim's primary key, the attempt's row lock, the permit's conditional renewal,
the ordered balance-row locks, the conditional transitions — each named in
`DISTRIBUTED_EXECUTION.md` §3 and every repaired one raced in a test.

---

## 4. Payment-rail boundary

`Payment → Rail Selection → Rail Adapter → Provider/Scheme → Payment Result → Ledger` holds as a
chain of ownership: the rail is a stored birth fact of the attempt, every capability decision reads
the rail's declaration, and the adapters speak their vocabularies behind the ports. **Card, wallet,
A2A and instant flows are not hard-coded into unrelated components**: the repairs keyed the claim
by the stored rail, read the parking's clearing from the rail's declaration, bounded the grant at
the port record, and guarded the transport at the composition root. **No rail-specific rule leaks
into the ledger**: `PostingService.lockBalancesInOrder` is rail-blind. **One recorded gap**: the
card webhook door's refund attribution is not scoped to its rail (#34, #75) — defence in depth, an
instant return's reference cannot reach the card PSP's door; recorded.

---

## 5. Idempotency

| Operation | Mechanism | As found |
|---|---|---|
| Card authorization, capture, void | Our minted reference committed before the call; conditional edges | Correct; the void's re-send made permit-free for the never-received answer too |
| Wallet payment | The confirmation's conditional transition in one transaction | Correct |
| A2A and instant payment requests | The claim, the end-to-end reference, the scheme's dedupe | Correct |
| Provider callbacks and scheme notifications | Inbox primary key; conditional transitions; **the scheme-execution claim** (new) | **Wrong**: the parking re-posted on every delivery with that day's date in its fingerprint (#26); now only the claim's winner posts |
| Refunds and dispute answers | Two-transaction claim, dispatch key, send permit | **Wrong**: the renewal could stand still (#22); now strictly advancing |
| Clearing notices | `V015`'s arbiter | **Wrong**: a different ARN absorbed as a duplicate (#29); now `SECOND_PRESENTMENT` |
| Settlement events and reconciliation inputs | Phase 8 | — |

---

## 6. Consistency and atomicity

| Workflow | Authoritative state and owner | Transaction boundary | Locking and arbiter | External dependency and recovery |
|---|---|---|---|---|
| Card capture and void | The attempt (`payments`) | Dispatch, call, outcome — three steps | Conditional edges; the redirect in the outcome's transaction | The sweep re-sends a void, redirects a never-received capture |
| Pay-in execution | The attempt; the claim | The execution, its claim and its posting — one transaction | The attempt's row lock, then the claim's key | The inquiry sweep asks the payer's PSP; a mismatch parks |
| Suspense parking | The parking and its claim | One transaction with the delivery's evidence | The claim's key before any posting | Phase 8 resolves |
| Withdrawal and return completion | The withdrawal or refund; the claim | One transaction | The row lock; the claim (a loss is recorded loud, never refused) | The resolution sweeps |
| Dispute stages | The dispute | One transaction per notification | Attempt, then counterparty (share), then the platform's rows in order | The PSP's redelivery |
| Checkout expiry and an undispatched intent | The session (`checkout`); the intent (`payments`) | One transaction | The intent's row conditionally FIRST, then the session's — a wallet confirmation's order | — |
| Merchant close | The merchant; its payable's ledger account | One transaction | The merchant row, then the payables `FOR UPDATE` | — |

**No distributed transaction is assumed.** The scheme, the PSP and the platform are three
authorities; what holds them together is dispatch-before-call, the conditional transitions, the
claim and the sweeps.

---

## 7. Security

Every provider response untrusted and mapped totally; webhooks verified by HMAC before parsing;
tokens and credentials never logged; routes behind named permissions. **As found:**

- **The customer's bank-account grant was concatenated raw into the provider's JSON body under the
  platform's credential** (#10, #15, #23) — a quote closed the field and wrote the platform's
  reference or a provider parameter. Now the grant carries the destination reference's shape rule
  at the boundary and in the port record, and every adapter body value is a JSON string literal.
- **The provider hop's TLS was documented as enforced from Phase 5 and enforced nowhere** (#24),
  while Phase 7 widened what crosses it — the scheme's bearer key, customers' grants, withdrawal
  destinations, decrypted dispute evidence. `ProviderTransportGuard` refuses a non-https provider
  URL off loopback at startup.
- **`Withdrawals.Resolved` printed the bank destination** in its generated `toString` (#39) —
  redacted.
- **Recorded with owners** (none a live exposure): merchant dispute acts audited without the API
  key (#63); refused Phase 7 acts leave no audit outcome (#64); credential confinement keyed on a
  loopback database (#65); the OpenAPI contract declares no security scheme (#66); the retained
  push-rail bodies versus `INV-RAIL-03` (#67) — the tension written into ADR-0062's record.

---

## 8. Reconciliation readiness

For every relevant transaction the platform can identify — by stored identifiers only — the
payment, the rail, our references (the capture's, the end-to-end), the provider's and the
network's (the PSP operation, the ARN and network id, the scheme reference and cycle), the
ledger entries by their posting keys, the dispute and its stage entries, and now:

- **one claim per scheme execution** (`scheme_execution_claim`): a scheme line's reference
  resolves to exactly one explanation — a pay-in, a withdrawal, a return or a parking — and a
  reference with no claim is exactly an unexplained external line;
- **the parking's own facts**: the reference it named, its cycle, its cause, the attempt it named
  and its raw statements by the fifth evidence subject;
- **a second presentment** as a distinct, counted outcome.

**Phase 8's inputs recorded, not fixed here** (they are Phase 8's evidence tables by design): the
cleared amount and date on the clearing wire (#29), provider identity per row (#81), provider and
network timestamps and the expected settlement date (#82), the return's settlement cycle.

---

## 9. Testing — and the harness defect

**The full battery fleet-wide**, which the phase's standing instruction had kept from running
(the review's recorded deviation), ran twice:

- **Before repair**, on untouched `7598332`: `build databaseTest kafkaTest` — every tier green
  except the app database tier, **1054 tests, 3 failed**, all in
  `RegistrationConcurrencyDatabaseTest`, answering `500`. **The cause was the harness, not the
  code**: one test JVM runs the whole database tier on Gradle's 512 MiB default heap while Spring
  caches up to 32 application contexts, and late in the tier ten concurrent Argon2id derivations
  (19 MiB each) met `OutOfMemoryError`. No targeted tier could see it. Repaired first: the external
  tiers run with `maxHeapSize = "2g"` (`finapp.java-conventions`), held by
  `DatabaseTierHeapDatabaseTest` (probed: the setting removed, the guard failed at 536870912).
- **After repair**: `build databaseTest kafkaTest` again, fleet-wide: `build` green at 1745 hermetic tests across 14 modules, 0 failures, re-run after the last document landed; the database tier at 1241 tests across 149 suites, 0 failures — its first post-repair run failed one test and the app tier's re-run a second, each repaired and re-run (the transport guard refused the `false` that switches a provider off; the storm's harness PSP could not answer the void the card redirect now sends) before the app tier re-ran whole and fresh at 1077 tests across 130 suites; and the kafka tier at 14 tests across 4 suites, 0 failures.

Every repair carries a database test at the layer where its money, lock or state lives, the ten-way
race among them, and **thirty-four probe runs over thirty-three deliberate breaks** — each read from
the failing testcases, each file restored byte-identical — **thirty-three caught at once**; the one
survivor (T8: an answer with no amount treated as the ask) found the applier's no-amount rule
reachable only from the callback door, which no test drove; the test gained the door's statement
and the re-run was caught (`MUTATION_TESTING.md` §2, the transition's thirty-three rows).

---

## 10. Architecture drift

Corrected with provenance: ADR-0053 (§5's undispatched window closed), ADR-0059 (§1's frozen money
semantics gain the outcome deadline; §2's void and capture conclusions; §5's second presentment),
ADR-0061 (§5's merchant close; the lock order's second step), ADR-0062 (§2's grant; §5's one money
fact, the applier's amount, the inquiry's expiry, the concluded attempt's value);
`PAYMENT_LIFECYCLES.md`, `RAIL_AND_DISPUTE_LIFECYCLES.md` (the capture redirect, the one-money-fact
rule, the cross-entry lock order), `DISTRIBUTED_EXECUTION.md` §3 (the claim's row; the pay-in,
withdrawal, return and dispute-answer rows; **the seeded-accounts rule restated** — broader than
the truth on an upgraded database, and blind to seeded-to-seeded order across entries — as the
multi-entry pre-lock rule), `SECURITY_ARCHITECTURE.md` (the provider hop's row),
`DATA_CLASSIFICATION.md` (ten rows), `AUDITABLE_ACTIONS.md` (`PaymentCancelled`'s platform
actor), and three javadoc comments (`AccountPurpose`, the card door's contended comment, the checkout
payable participant). Phase 8's initialisation annotated the accepted decisions its ADRs settle or
re-assess: ADR-0036 (settlement files, by ADR-0066), ADR-0040 (the four purposes, ADR-0065),
ADR-0057 (the payout-return follow-up, paid by ADR-0073), ADR-0060 §6 (the cost meter, ADR-0072) and
ADR-0062 (§7's trigger not fired; the return's cycle learned from the report); `DECISIONS.md`'s
invariant count moved from 101 to 110.

---

## 11. Blocking issues and repair

| ID | Severity | Finding (audit #) | Repair | Proven by |
|---|---|---|---|---|
| C1 | **CRITICAL** | A withdrawal's own confirmation parked as inbound value (#0–#2, #4, #25) | The door recognises withdrawal and return echoes; the completion claims its execution | `aWithdrawalsOwnConfirmationNeverParks`; probes T1, T2, U4 |
| C2 | **CRITICAL** | The inquiry sweep credited an amount the scheme did not execute (#3, #7, #13, #20) | The applier judges the executed amount for both producers; the inquiry carries it; a mismatch parks and fails | `theInquirySweepJudgesTheExecutedAmount`, `aMismatchedAmountParksTheExecutedValueAndFailsThePayIn`; T7, T8 |
| I1 | IMPORTANT | One execution credited and parked, one direction guarded, no arbiter (#8, #17, #19) | `payments.scheme_execution_claim` (`V023`) for every producer | the credited-restatement test and the ten-way mixed race; T3, T4 |
| I2 | IMPORTANT | Value on a concluded pay-in dropped (#9, #47, #57, critic) | Parks `ATTEMPT_CONCLUDED`, at the door and in the applier on a stale read | `valueOnAConcludedAttemptParks`, `aStaleReadOfAConcludedAttemptStillParks`; T5, U1 |
| I3 | IMPORTANT | An expired initiation asked for ever (critic, #58) | The initiation inquiry's own vocabulary | `anExpiredInitiationEndsThroughTheSweep`, the adapter test; T9 |
| I4 | IMPORTANT | The parking's poison delivery (#26, #36) | Claim first; only the winner posts | `aLaterDaysDuplicateConvergesWithoutPosting`; T4 |
| I5 | IMPORTANT | The parking records neither what it named nor its cycle nor its evidence (#28) | `V023`'s columns and the fifth evidence subject | the parking tests' assertions; T6 |
| I6 | IMPORTANT | Authorizations left standing (#5, #18, #56) | The void redirect for a capture never sent or received; a never-received void re-sent | three void-suite tests and the capture unit; T10–T12 |
| I7 | IMPORTANT | A merchant closed with a winnable chargeback (#6, #14, #21, #27, critic MINOR) | The close asks the dispute store; closes the payable's ledger account | `aRestorableChargebackKeepsTheMerchantOpen`; T13, T14 |
| I8 | IMPORTANT | The grant concatenated into a credentialed request (#10, #15, #23) | The shape rule at the boundary and the port; escaped bodies | `aHostileGrantNeverReachesTheProvider`, the adapter unit; T15, T16 |
| I9 | IMPORTANT | An undispatched checkout intent confirmable around its checks (#11, critic) | Re-checked at the session's door; refused at the public route; cancelled at expiry | two checkout-flow tests; T17–T19 |
| I10 | IMPORTANT | A loss that first reports its fee deadlocks (#12, #16, #42) | The platform's rows pre-locked in order before any dispute posting | the eight-round race; T20 |
| I11 | IMPORTANT | The permit renewal could stand still (#22) | Strictly advancing renewal | `aTrailingTakeoversRenewalStillAdvancesThePermit`; T21 |
| I12 | IMPORTANT | The provider hop's TLS enforced nowhere (#24) | `ProviderTransportGuard` | its unit; T22 |
| I13 | IMPORTANT | A second presentment absorbed (#29, #59) | `SECOND_PRESENTMENT` | the webhook and clearing tests; T23 |
| M | MINOR | Repaired: the door's totality (#72, #76), the echoes' evidence (#77, #78), the redacted withdrawal record (#39), the skew-proof hold release (#41), the capture converging on a void (#31), a clearing of a concluded payment loud (#32, #73), the outcome deadline frozen (#43), an acceptance beside a failed withdrawal loud (#38), four comments (#33, #44, #48, #74) | — | U2–U10 |
| R | MINOR | Recorded with owners in `CURRENT_STATE.md` §Known Architectural Debt: the card door's unlocked source read (#30, #51, #55, #68 — the conditional edges keep the money right), the claim row's lock order across a lease takeover (#52), namespace 3's 32-bit key (#53), sweep starvation (#54, the existing row widened), the re-send's missing stage check (#60), the idempotency record's retention (#61), `MANUAL` capture latent (#62, #70), the audit gaps (#63, #64), confinement and OpenAPI (#65, #66), the routing counter (#69), the dashboard and alert rules (#71, owned by Phase 8's suspense ageing), the card door's rail scope (#34, #75), the VOIDED rendering (#35), the withdrawal replay gate (#37), the book refund to a closed wallet (#40), provider vocabulary placement (#46), the shared event version (#49), the drill-down's label (#50), the storm's reach (#79, #80), and Phase 8's evidence inputs (#81, #82) | — | — |

**The gate was repeated after repair**: every area re-read against the repaired code, the
ten-instances question answered again over the repaired arbiters, and the full battery re-run.
**Phase 7 passes.**

---

## 12. Phase 7 completion

**Phase 7 is `COMPLETE`** — ruled by `P7-DOC-001` and confirmed here after repair. **Delivered**:
the platform moves money over three materially different rails — the card's two-step promise with
its void, clearing and refunds; the instant push rail with withdrawals, pay-ins by bank and
returns; the wallet as an instrument — routed by a pinned, explainable policy, with disputes end to
end, every rail on its own clearing position, and now **one money fact per scheme execution**.
**Decisions**: ADR-0059…0062 `Accepted`, amended with this transition's provenance where it changed
what they describe. **Remaining non-blocking debt** is recorded with owners; none is
financial-correctness debt.

---

## 13. Phase 8 initialisation

**Objective.** Answer, continuously and provably, per counterparty, position, currency and item:
does the platform's internal financial state match what the card PSP, the instant scheme, the
payout provider and the platform's bank say happened — and make every disagreement a classified,
aged break with both sides preserved, resolved only by evidence or by a controlled, four-eyes
adjustment. Planned in [`PHASE_8_PLAN.md`](../PHASE_8_PLAN.md); decided in ADR-0064…0073
(`Proposed`; ADR-0063 is reserved by an unmerged branch); the machines in
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](../../domain/SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md)
and the model in [`RECONCILIATION_MODEL.md`](../../domain/RECONCILIATION_MODEL.md). The design was
reached by a judge panel of three independent designs, synthesised, drafted and then checked by a
consistency critic whose forty findings were resolved under recorded decisions before the
registers were written; a read-only re-check of the integrated set then found eighteen residual
disagreements between documents — the most material that a scheme reference no claim holds is
`MISSING_INTERNAL`, not `UNKNOWN_EXTERNAL`, while its operation is still in flight, that a
repudiation opens a fourth kind of suspense item, and that the Phase 8 lock orders still leaned on
the seed-order rule this transition's gate had disproved — each resolved the same way and
re-checked.

**Entry gate** (`PHASE_GATES.md` §2):

| # | Criterion | Holds |
|---|---|---|
| 1 | Hard dependency phases `COMPLETE` | Yes — Phases 5, 6 and 7 (`DELIVERY_PLAN.md` §Phase 8 §4), and every phase before them |
| 2 | `DELIVERY_PLAN.md` §Phase 8 current and specific | Yes — made current here (the addendum) |
| 3 | Bounded contexts and aggregates identified | Yes — `PHASE_8_PLAN.md` §3, §4: `settlement` and `reconciliation`, two new modules |
| 4 | Invariants identified by ID | Yes — thirteen existing and nine new, **twenty-two**, read from the catalogue (**110 invariants**) |
| 5 | Lifecycles drafted | Yes — nine machines and one born-once fact (the payout return) |
| 6 | Transaction and consistency boundaries stated | Yes — `PHASE_8_PLAN.md` §7 and each task |
| 7 | Idempotency stated for every money-moving command | Yes — §4 and each task |
| 8 | External dependencies and failure modes listed | Yes — §14's forty-four scenarios |
| 9 | Security, audit and reconciliation implications stated | Yes — §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | Yes — 27 items, the twenty-three fields each |
| 11 | Required decisions have ADRs at least `Proposed` | Yes — ADR-0064…0073 |
| 12 | `CURRENT_STATE.md` names the phase active | Yes |

**What Phase 7 handed over, disposed** (`PHASE_8_PLAN.md` §2): the full battery — run here, §9;
a return's missing settlement cycle — learned from the scheme's report (`P8-TSK-017`), no payments
migration; the per-rail cost meter — `PROCESSING_COSTS` recognised from evidence and reported,
never a metric (ADR-0072); per-principal idempotency scopes (`X-TSK-003`) — every Phase 8 scope
per principal from birth; the confirmation's missing second-push-rail guard — unaffected, with the
phase that adds a second push rail. Two cross-cutting items are **new**, each outside Phase 8's
modules: `X-TSK-008` (associated data in the four existing ciphers, which Phase 8's own cipher binds
from birth) and `X-TSK-009` (the posting-date doctrine against the Phase 5–7 practice, which every
Phase 8 posting already follows by taking its dates from stored rows).

**Exit criteria**: `PHASE_GATES.md` §5 Phase 8, the seven original criteria extended here by
twenty-one measurable ones.

**First task: `P8-TSK-001` — The `settlement` and `reconciliation` modules and schemas** —
`READY`, not started.
