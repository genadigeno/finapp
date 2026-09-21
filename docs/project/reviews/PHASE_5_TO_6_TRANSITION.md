# Phase 5 → Phase 6 Transition

**Conducted:** 2026-09-21
**Parts:** Phase 5 completion audit · payment-lifecycle audit · financial correctness
audit · multi-instance audit · ambiguous-outcome audit · idempotency audit · webhook
audit · provider-abstraction audit · atomicity and consistency audit · security audit ·
reconciliation-readiness audit · testing audit · architecture-drift audit · findings and
repair · Phase 6 initialisation
**Constraint:** a transition writes no application code. The one repair performed (§10) is
a documentation-register row, made under the repair-before-transition instruction this
transition was conducted with, and recorded rather than absorbed.

---

## Verdict

| Part | Outcome |
|---|---|
| Phase 5 completion audit (23 categories) | **23 `PASS`** |
| Payment lifecycle audit | `PASS` — every distinction held apart; no "payment succeeded" abstraction exists |
| Financial correctness audit (13 properties) | **13 `PASS`** |
| Multi-instance audit | **`PASS`** — *"would Phase 5 remain financially correct with 10 concurrent instances?"* |
| Ambiguous-outcome audit (§5's scenario) | `PASS` — proven end to end, counted |
| Idempotency audit | `PASS` — nothing rests on JVM-local memory |
| Webhook audit | `PASS` — a duplicate webhook cannot create a duplicate financial effect |
| Provider abstraction audit | `PASS` — the port speaks four enumerated verdicts; provider vocabulary confined |
| Atomicity and consistency audit | `PASS` — no atomicity claimed across the provider boundary; everything else one transaction |
| Security audit | `PASS`, limits stated and owned |
| Reconciliation readiness | `PASS` — every link in the chain is a stored identifier, both directions |
| Testing audit | **The full battery, fleet-wide: 1323 hermetic · 829 database · 14 kafka — one failure, a register gap (§10), repaired; re-run green** |
| Architecture-drift audit | **No drift**; the register-decay check found `DISTRIBUTED_EXECUTION.md` §3 current at a phase boundary for the second consecutive time |
| **Phase 5** | **`COMPLETE`** — confirming `P5-DOC-001` |
| **Phase 6** | **Entry gate: all twelve criteria hold → `READY`** |

Phase 5 was ruled `COMPLETE` by its exit review earlier today
([`PHASE_5_REVIEW.md`](PHASE_5_REVIEW.md)). This audit is the **second, independent pass**
— the standing precedent: a gate assessed only by whoever just finished the work is not
two checks. Where the review's evidence is hours old and already counted, this audit
re-checks the claims against the code and probes what the review could not: the fleet-wide
**database and kafka tiers** the standing skip instruction kept from running all phase
(the review ran the hermetic tier fleet-wide; these two tiers had **no** fleet-wide run
since the Phase 4 → 5 transition), the governance registers, the single-instance sweep,
and the planning inputs Phase 6 depends on.

---

## 1. Phase 5 completion audit

Twenty-three categories — the review's areas re-derived from the code, not from the
review:

| # | Category | Verdict | Evidence re-checked |
|---|---|---|---|
| 1 | Payment Intent | **`PASS`** | Five states, one constructor holding coherence, `rehydrate` refusing corrupt rows, exhaustive derived sweep; `SUCCEEDED` pinned terminal |
| 2 | Payment Attempt | **`PASS`** | Seven states with both `*_DISPATCHED` and both `*_UNKNOWN` durable by design (ADR-0045/0046); one-live partial index per intent |
| 3 | Lifecycle machines | **`PASS`** | Three-layer enforcement everywhere: aggregate sweep + generated `CHECK`s and every-writer triggers + append-only history |
| 4 | Authorization (payment sense) | **`PASS`** | No ledger effect (ADR-0048) — re-verified: no posting call on the authorization path; the attempt records the issuer's promise as domain state |
| 5 | Capture | **`PASS`** | DR `SETTLEMENT_CLEARING` / CR wallet atomically with `CAPTURED` under claim `payment-capture:<attemptId>`; account-purpose assertions (`DIRECTION:PURPOSE`), not line counts |
| 6 | Reversal/refund | **`PASS`** | Hold-then-post (ADR-0048 §4): dispatch holds under the account lock, completion releases-and-posts `payment-refund:<refundId>`, failure releases, ambiguity parks visibly |
| 7 | Provider abstraction | **`PASS`** | Part 8 below |
| 8 | Provider adapters | **`PASS`** | `SimulatedCardPspAdapter`/`SimulatedTokenisationAdapter` behind ports; an exception escaping the port is a defect by contract |
| 9 | Provider references | **`PASS`** | Our minted per-operation reference (`NOT NULL` before dispatch, `UNIQUE`) beside the provider's (unique when present) — both durable, both walkable |
| 10 | Webhook ingestion | **`PASS`** | Part 7 below |
| 11 | Webhook verification | **`PASS`** | HMAC over `timestamp + "." + raw bytes`, before parsing, constant time, two-sided freshness window (`INV-PAY-01`) |
| 12 | Webhook deduplication | **`PASS`** | Platform inbox `(consumer, dedupe_key)` primary key; `CONTENDED` answers 409 unacknowledged so the provider redelivers into the dedupe |
| 13 | Idempotency | **`PASS`** | Part 6 below |
| 14 | Ledger integration | **`PASS`** | Postings commanded through `PostingService` on the caller's connection, never written (`INV-LED-04`); `payments → ledger` the declared edge |
| 15 | Persistence | **`PASS`** | Schemas at the default-deny floor; frozen columns; append-only evidence at the privilege; raw-SQL tests from scratch |
| 16 | Transaction boundaries | **`PASS`** | Part 9 below — dispatch-before-call structurally asserted |
| 17 | Consistency | **`PASS`** | Conditional transitions as the universal arbiter; projections never a decision's input |
| 18 | Failure recovery | **`PASS`** | All fifteen plan §14 scenarios tested or rationale-recorded (the review's criterion 4, recounted at its own gate) |
| 19 | Security | **`PASS`** | Part 10 below |
| 20 | Auditability | **`PASS`** | Eight actions across both modules, actor/correlation/outcome, reason on the refund; the platform's acts through five enumerated `enterSystem()` sites |
| 21 | Observability | **`PASS`** | Six meters eager from a plain context; NaN-never-zero gauges; acting-only counters proven by the ten-webhook race |
| 22 | Testing | **`PASS`** | Part 12 below — including the two tiers the review could not claim |
| 23 | Documentation | **`PASS`** | The review's three self-corrections verified landed; ADR registers build-reconciled; **one register gap found by this audit's battery (§10) and one stale second copy (`ROADMAP.md` §Current position, again) — both corrected** |

**Against the nineteen phase-specific Phase 5 gate criteria** (8 original + 11 extension)
**and the F1–F8 supplement**: all hold; `P5-DOC-001` assessed each with
named evidence, and this audit re-checked the load-bearing ones directly against the code
— the dispatch discipline (the `noConnectionIsHeldDuringTheCall` structural probe read at
its assertion), the port's enumerated verdict vocabulary, the sweeper's no-lease shape,
the webhook door's authenticate-before-parse order, and the conditional-transition core
with its acting bit.

## 2. Payment lifecycle audit

**Every distinction the platform is forbidden to collapse is held apart in code**, each
with its own record and vocabulary:

- **Intent ≠ attempt** (ADR-0045): two aggregates, two machines, the attempt born inside
  the confirm's transaction.
- **Authorization ≠ capture** (ADR-0048): the authorization is payment-domain state with
  no ledger effect; the ledger's first touch is the capture, atomically with `CAPTURED`.
- **Capture ≠ clearing ≠ settlement** (`INV-SET-01`, register row landed at `P5-TST-002`
  after its finding): the capture's debit lands on `SETTLEMENT_CLEARING` — a clearing
  *position*, continuously "captured but unsettled" — and nothing in Phase 5 records
  settlement. The distinction is asserted by account purpose, not inferred from balance.
- **Refund ≠ reversal**: the refund is its own aggregate with its own four-state machine,
  bounded by its capture (`INV-PAY-05` at two ranks), never an edit of history
  (`INV-HIST-01`).
- **Dispute/chargeback**: deliberately absent (Phase 7) with the seam recorded — the
  evidence and reference chain they will need is retained now.
- **Provider states are mapped, never adopted**: the total-vocabulary-understood
  classification (`P5-TSK-017`'s gate finding) makes an unrecognised provider word
  `unmappable` — indeterminate, never success (`INV-PAY-03`), evidence retained, metered.

No generic "payment succeeded" state exists anywhere; the closest thing, the intent's
`SUCCEEDED`, is earned only by a committed capture posting. Valid and invalid transitions
are exhaustively swept at the aggregates (cross-product, expectation derived from
`permittedTransitions()`) and refused by triggers for every writer including raw SQL.

## 3. Financial correctness audit

| # | Property | Verdict | Mechanism |
|---|---|---|---|
| 1 | No floating-point money | **`PASS`** | STATIC sweep; the one near-miss all phase (`ResultSet.getDouble` in a gauge) was refused by the guard and fixed at `floor(...)::bigint` (`P5-TSK-017`) |
| 2 | Explicit currency | **`PASS`** | `MoneyColumns` shape pinned; currency mismatch a distinct refusal |
| 3 | Deterministic rounding | **`PASS`** | No division exists on any Phase 5 money path (amounts pass through whole); named-mode rounding is `Money`'s standing property suite |
| 4 | No unexplained creation/destruction | **`PASS`** | `P5-TST-003`: wallets hold exactly captured − refunded − spent while three payment tables and the journal agree to the minor unit |
| 5 | Correct ledger effects | **`PASS`** | `DIRECTION:PURPOSE` assertions on capture and refund (the `P5-TST-002` probe class) |
| 6 | Balanced entries | **`PASS`** | `V004` deferred COMMIT triggers for every writer; trial-balance sweeps zero per currency throughout the storm |
| 7 | Immutable history | **`PASS`** | Evidence append-only at the privilege; attempt/refund histories append-only; frozen columns by trigger |
| 8 | Safe reversal/refund | **`PASS`** | Hold-then-post; the bound at domain and database rank; over-refund refused for every writer (23514) |
| 9 | Duplicates cannot duplicate effects | **`PASS`** | Part 6; the posting claims make a second entry structurally impossible |
| 10 | Balances explainable | **`PASS`** | `INV-BAL-02` replay-from-zero and projection verification running against the storm's traffic |
| 11 | Provider references preserved | **`PASS`** | Category 9 above; evidence verbatim with checksums (`INV-HIST-02`) |
| 12 | Settlement/reconciliation possible | **`PASS`** | Part 11 below |
| 13 | The full chain walkable | **`PASS`** | Economic event → intent → attempt → provider references → entry (`payment-capture:<attemptId>` in the claim, references on the rows) → lines → wallet/clearing balances → (Phase 8) settlement — every hop a stored identifier |

## 4. Multi-instance audit

**Would Phase 5 remain financially correct if 10 instances executed the relevant
operations concurrently?**

# `PASS`

The exit review's seven contended decisions stand; this audit adds what the review did not
run:

- **The single-instance sweep, fresh**: `synchronized`, `ReentrantLock`, `ThreadLocal`,
  `@Scheduled`, static mutable collections — **zero occurrences** across `payments`,
  `paymentmethods` and `app.payments` production code. The only atomic references in
  scope are the `*Metrics` per-instance cached readings, declared non-authoritative in
  the register — the standing exemption, verified still scoped to telemetry.
- **Every contended decision has a database arbiter, none a Java one** (re-read from
  `DISTRIBUTED_EXECUTION.md` §3 against the code): create/confirm the claim's unique
  constraint; attempt birth the partial index; outcome races conditional transitions;
  webhooks the inbox primary key; sweepers **no lease by design** (reads idempotent,
  writes conditional); capture the posting claim; refunds the two-rank bound under the
  pinned attempt → account lock order — the order proven load-bearing by `P5-TST-003`'s
  inversion probe (40P01).
- **Duplicate commands, provider requests, webhooks; retries; timeout-then-retry;
  restart; rebalance**: parts 5–7. **Stale reads**: every judgement derives inside a lock
  or a conditional write; projections are never inputs. **Lost updates**: rows are
  insert-carries-outcome or conditional updates. **Distributed scheduling**: both
  schedules (relay, sweeper) registered with their justifications — lease and leaderless
  respectively.
- The **fleet-wide database tier itself** ran at N-suites-in-one-JVM scale (829 tests)
  against the connection arithmetic the last transition repaired — no recurrence.

## 5. Critical ambiguous-outcome audit

The scenario — provider processes successfully, response lost, client times out and
retries — is **the phase's founding scenario**, and it is proven end to end, counted, not
argued:

- The operation cannot run twice at the provider: the dispatch transaction commits **our
  minted idempotency reference before anything is sent** (`INV-PAY-04`), so the retry —
  ours or the client's — converges on the committed attempt and never re-dispatches
  (`P5-TST-001`'s storm; the refund's reconstructed-crash test re-driving the wire with
  the *stored* `rfd-` reference, `requestCount == 1`).
- Two ledger effects are structurally impossible: the posting's own idempotency claim
  (`payment-capture:<attemptId>` / `payment-refund:<refundId>`) — a second winner has
  nowhere to post.
- A successful provider transaction cannot be marked failed: ambiguity commits
  `*_UNKNOWN` — **a modelled state, never an assumed outcome** (`INV-LIFE-03`) — and the
  sweeper resolves it by querying with our stored reference; a late truthful answer lands
  on the conditional edge; a contradictory one becomes evidence beside the untouched
  terminal.
- The external reference cannot be lost: it is a `NOT NULL`-before-dispatch column, not a
  log line.
- No unreconcilable state exists: every intermediate is durable, visible (the
  stuck-payment gauges alert on `*_UNKNOWN` age) and resolvable by query, webhook or
  retry — all three landing on the same conditional transitions.

## 6. Idempotency audit

Across instances, for every command: create and refund are **keyed** (the claim's unique
constraint; same key different payload the distinct 409; byte-for-byte replay of the
response of record — the refund's two-transaction form freezing the *judged* response,
`INV-LIFE-04`); confirm/cancel converge by machine; capture converges on the attempt's
conditional edge with the posting claim beneath; webhook processing dedupes on the inbox
primary key; provider retries are absorbed by the provider-side reference (`INV-PAY-04`).
Nothing consults process memory; a restart loses nothing because nothing lives outside
the rows; the crash-between-transactions case is the lease takeover, proven to converge
by dispatch key (`V008`).

## 7. Webhook audit

Authentication before parsing over the raw bytes, per-provider keys under the confinement
regime; freshness two-sided and server-judged; **evidence-first** — every authenticated
delivery retains verbatim bytes in the dedupe record's own transaction, duplicates
included; event identity is the provider's event id in the inbox key; ordering is
**nobody's promise** — out-of-order, duplicate-with-fresh-id, before-the-sync-response and
late-contradictory deliveries all land on conditional from-state transitions (ten-way race
counted to one entry, one transition, ten statements retained); acknowledgment is
2xx-after-commit, `CONTENDED` deliberately unacknowledged so the provider redelivers;
retry behaviour is therefore the provider's, absorbed. **Dead-lettering is deliberately
not a queue**: an authentic-but-unmappable delivery retains evidence, increments
`webhook{unmappable}` and stalls nothing (ADR-0047 §5) — the alert is the letter. A
duplicate webhook creating a duplicate financial effect was hunted directly (`P5-TSK-013`,
`P5-TSK-016`'s ten-way refund race, the `webhook-default-made-success` mutation): none
exists.

## 8. Provider abstraction audit

Core domain → `PaymentProvider` port → `SimulatedCardPspAdapter` → harness, and the port
speaks **four enumerated verdicts** (`APPROVED`, `DECLINED`, `INDETERMINATE`,
`NOTHING_SENT`) plus verbatim evidence bytes — no provider status word, payload shape or
error code crosses it (`INV-PAY-03`, boundary-tested; the mapping's default is never
success). Tokenisation has its own port with the same posture. **A second provider fits
without redesign**: the port is keyed by `providerName()`, per-provider webhook keys and
freshness windows already exist, and the deliberate one-provider width (ADR-0049 §4) is a
recorded guard against sample-of-one abstraction, not a coupling. The decorator seam
(`MeteredPaymentProvider`) proved the port's one-interface property under the compiler.

## 9. Atomicity and consistency audit

| Operation | One transaction contains | Arbiter | Recovery |
|---|---|---|---|
| Create intent | Claim + intent row + history + audit + outbox | Claim unique constraint | Replay renders stored response |
| Confirm / dispatch | Conditional `REQUIRES_CONFIRMATION → PROCESSING` + attempt birth + our reference + evidence + audit | Row count + partial index | Losers converge without calling the provider; crash strands visible `AUTH_DISPATCHED` for the sweeper |
| Outcome (any resolver) | Conditional transition + payload columns `NULL → value` + evidence + (capture) posting + intent edge + events | The edge triggers; the posting claim | Duplicate resolvers converge; replays render the record |
| Refund dispatch | Attempt `FOR UPDATE` → bound → hold → row + claim `IN_PROGRESS` + audit | Locks + `V004` trigger + claim | Takeover converges by dispatch key |
| Refund outcome | Release-and-post (or release) + claim completion with judged response | Conditional + posting claim | Replay byte-for-byte thereafter |
| Webhook | Dedupe insert + evidence + conditional effect | Inbox primary key | 409-unacknowledged redelivers; post-commit duplicate `SKIPPED_DUPLICATE` |

**No transaction spans the provider call** — asserted structurally (the no-connection
probe) and behaviourally (crash-mid-call tests). Nothing distributed is claimed atomic;
the two-transaction shapes are honest about their seam, and the seam's crash case is the
takeover contract.

## 10. Testing audit — and the finding

**The review's recorded deviation was criterion 7**: the hermetic tier ran fleet-wide
(1323/0) but the database and kafka tiers were verified per task, no fleet-wide count
claimed — none had run since the Phase 4 → 5 transition. This transition ran the full
battery.

**It failed — by exactly one test in 2,166.** `ColumnClassificationTest` (in
`:platform:databaseTest`, a tier no Phase 5 task ran in full): **`refund.dispatch_key`,
added by `V008` at `P5-TSK-016`, was never classified in `DATA_CLASSIFICATION.md` §4.**
The guard exists precisely because "a column with no entry is a column whose handling
nobody decided" — and it caught one, five tasks and one exit review after the column
landed. This is the `RoleNameTest` class recurring within twelve hours of that finding
being written down: a guard living in a tier the targeted regime never runs is a guard
that fires only at phase boundaries. **Severity ruled honestly**: a governance-register
gap, not a correctness defect — the column holds caller-chosen key material
(`idempotency_record.idempotency_key`'s class, `INTERNAL`) and no handling was actually
wrong; but the register's whole claim is that no column waits for its classification.
**Repaired here** (the row, classified by its precedent, with the finding recorded in the
row itself); the suite re-ran green; the full battery re-ran clean end to end:

**`BUILD SUCCESSFUL` — 1323 hermetic · 829 database · 14 kafka, 0 failures** — the first
genuine fleet-wide database and kafka count of Phase 5.

*(For the record, because a transition audits itself too: the confirmation re-runs also
caught **this transition's own documents in flight, twice** — the ADR-index guard red in
the window between the four new ADR files landing and their `README.md` rows, and the
glossary guard red until `DOMAIN_MODEL.md` named the five new terms. Both were the guards
doing exactly their job on the newest writer in the repository, each closed within
minutes; the final battery over the finished document set is the one whose verdict stands
above. A third near-miss was avoided by reading the register guard's parser before
committing: a provenance note placed on `INV-AUD-04`'s `Phase:` line would have made the
token parse demand that row for a completed phase — the note now lives on its own line and
says why.)*

Adequacy beyond green, re-checked rather than inherited: 11 of 11 `Phase: 5` invariants
with register rows and performed demonstrations; the mutation discipline held at every
gate (backup → mutate → intended-assertion-fails → restore verified byte-identical), with
every first-round survival investigated and each either closed by a sharper probe (the
funded-wallet race, the ten-way announce) or recorded as a masked-by-depth claim with its
owner in the register; storms ended by verifier floors, never sleeps; constraints proven
against raw SQL from scratch. Standing not-covered statement unchanged (no load testing before Phase 16;
providers simulated by design).

## 11. Reconciliation readiness

`PASS`. Both directions of the chain walk by stored identifier: intent ↔ attempt (FK) ↔
our references and the provider's (columns, unique) ↔ journal entry (the posting claim
embeds the attempt/refund id) ↔ lines ↔ balances; evidence rows carry verbatim bytes with
checksums for every request, response, query and webhook; amounts exact with currency and
scale; timestamps from the injected clock; lifecycle histories append-only and
server-ordered. `SETTLEMENT_CLEARING` per currency is continuously "captured but
unsettled" — Phase 8's opening position. **Gaps stated with owners, not hidden**: outbound
request bytes are not captured (evidence is everything *received*) — Phase 8; refund
sweeping — the recorded deferral with the standing hold as the loud symptom.

## 12. Security audit

`PASS`. The webhook door signature-first with per-cause negatives (nothing written);
provider and webhook keys under the generalised per-credential confinement (`P5-TSK-002`
— the debt row paid); the PCI boundary mechanical (module isolation both ways proven by
mutation at `P5-TST-002`, the `information_schema` column sweep, tokenisation-down fails
the attach with nothing stored); `PAYMENT_REFUND` on `LEDGER_OPERATOR` with negative test
and required verbatim-audited reason; step-up on instrument attach when a factor is
enrolled; no amount or provider vocabulary in any exception, event, metric tag or audit
detail (needle-asserted); evidence encrypted under versioned keys (AES-GCM, key outside
the database). Limits unchanged and owned: operational endpoints unauthenticated (Phase
15), per-source rate limiting (Phase 15), refund sweeping (owner recorded). **Phase 6
note**: the four-eyes control `INV-AUD-04` promises has had no subject through five
phases; the payout destination change is its first, by design of this plan.

## 13. Architecture-drift audit

**No drift.** Boundaries match `MODULE_ARCHITECTURE.md` §3 (the declared `payments →
ledger` edge and both refusals demonstrated in the build graph — the `paymentmethods`
refusal by performed mutation); `BOUNDED_CONTEXTS.md` contexts 9/10 ownership holds;
event discipline per ADR-0044 (terminal facts; `UNKNOWN` deliberately publishes nothing);
ADR registers build-reconciled, ADR-0045…0049 `Accepted` in both copies.

**The register-decay check found `DISTRIBUTED_EXECUTION.md` §3 current at a phase
boundary for the second consecutive time**: every Phase 5 component carries its row
(commands, webhook door, schema arbiters, instrument table, metrics caches, the sweeper
schedule exemption, advisory namespace 3) — most added by their own tasks, the practice
the register's note asks for.

**Found and corrected by this audit**: the `refund.dispatch_key` classification row (§10);
`ROADMAP.md` §Current position frozen at 2026-09-20 through twenty task gates and the
phase flip — **the stale-second-copy class in the same section for the second transition
running**; the delivery plan's Phase 6 event list missing the payout terminal pair
(`MerchantPayoutCompleted`/`Failed`) that ADR-0044's own doctrine demands — the exact
`RefundFailed` finding of the last transition, one phase later, corrected in both copies
with provenance.

## 14. Findings

| Severity | Finding | Blocks Phase 6? | Remediation |
|---|---|---|---|
| **IMPORTANT** | `refund.dispatch_key` unclassified in `DATA_CLASSIFICATION.md` §4 since `P5-TSK-016` — found only because this transition ran the tier the guard lives in (§10) | Would have (a schema column with undecided handling crossing a phase boundary) | **Repaired here**: classified by its precedent (`INTERNAL`, caller-chosen key material); battery re-run green fleet-wide |
| MINOR | `ROADMAP.md` §Current position stale by a phase flip, second occurrence of the class in this file | No | Corrected by this transition, occurrence counted in the section's own note |
| MINOR | Phase 6 event list missing the payout terminal-facts pair (`RefundFailed` class, one phase later) | No | Corrected in `DELIVERY_PLAN.md` and `MODULE_ARCHITECTURE.md` with provenance |
| MINOR | Unresolved questions 7 and 8 due at Phase 6 | Would have (question 8 is the restatement risk) | **Closed by this transition**: ADR-0050 (fee model), ADR-0053 (checkout module, confirming the recorded working position) |

**No CRITICAL findings. No financial-correctness findings of any severity.** No debt
hidden: the standing rows carry forward unchanged; the refund-sweep deferral keeps its
owner (Phase 6 or a sweeper extension — now a named candidate for the payout sweep's
sibling task).

## 15. Phase 5 completion

**Phase 5 is `COMPLETE` (2026-09-21)**, confirming `P5-DOC-001` — now with the full
battery behind it.

**Delivered**: money enters and leaves against an unreliable third party — 2 modules, 8
tables, 8 migrations (+`V008`), 6 operations on 4 payment paths + 2 instrument paths + the
webhook door, 4 aggregates and 4 machines, 8 auditable actions, 10 event types, 11 error
codes, 2 permissions' worth of new authority (`PAYMENT_REFUND` on the money-operating
population; the signed-callback class extended), 6 meters and a dashboard row, 5 ADRs
`Accepted`, the `INV-PAY` group catalogued and demonstrated (11 in-scope invariants, 11
register rows), 21 of 21 items across 9 milestones, and **1323 / 829 / 14 fleet-wide, 0
failures**.

## 16. Phase 6 initialisation

**Phase 6 — Checkout and Merchant Platform — is `READY`.**

Decisions taken, because Phase 6 cannot start without them: **ADR-0050** (the fee model —
merchant pays, recognised at capture, gross-to-books-net-to-merchant in one entry, the
subtraction split that cannot strand a residual — closes question 8, the standing High),
**ADR-0051** (payout accounting: hold-then-dispatch on the payable, `PAYOUT_CLEARING`,
nothing final before settlement), **ADR-0052** (merchant API identity: keys under the
credential regime, tenancy in the statement, the fourth authentication vocabulary),
**ADR-0053** (checkout session and order: two aggregates, expiry gates dispatch, landed
money always wins — closes question 7 as its working position stood). **The
`INV-MER-01…06` group catalogued** (93 invariants): tenant isolation, the ledger-derived
payable, version-pinned deterministic fees, the residual-free split, the payout bound,
and landed-money-never-orphaned — Phase 6's gate properties as stable IDs before any
merchant code exists, with `INV-AUD-04` going live on its first subject.
`CHECKOUT_MERCHANT_LIFECYCLES.md` written; the glossary extended (Checkout Session,
Order, Fee Schedule, Merchant Payable, Merchant Payout); `PHASE_GATES.md` §5 Phase 6
extended with the measurable criteria the original six bullets predate.

All twelve entry-gate criteria hold:

| # | Criterion | Evidence |
|---|---|---|
| 1 | Hard dependencies `COMPLETE` | Phase 5 (confirmed above) and Phase 2 (KYB — `COMPLETE` since 2026-09-13) |
| 2 | Delivery-plan section current and specific | §Phase 6, with this transition's event-list correction |
| 3 | Bounded contexts and aggregates identified | `PHASE_6_PLAN.md` §3, §4 — contexts 11 and 12, seven aggregates |
| 4 | Invariants identified by ID, from the catalogue | §6 — eight, token-parsed: `INV-MER-01…06`, `INV-HIST-04` (fees), `INV-AUD-04` (its phase list corrected — it named the payout destination while omitting 6) |
| 5 | Lifecycles drafted | ADR-0051/0053; `CHECKOUT_MERCHANT_LIFECYCLES.md` §2–§6 |
| 6 | Transaction and consistency boundaries stated | ADR-0050 §3/§6, ADR-0051 §2; plan §7 |
| 7 | Idempotency stated for every money-moving command | Plan §4/§9 — onboard, session create, payout keyed; completions converge by machine |
| 8 | External dependencies and failure modes listed | §14 — fifteen scenarios; the payout rail simulated behind its own port |
| 9 | Security, audit, reconciliation implications stated | §11, §12 — tenancy, four-eyes, the three-way reconciliation target |
| 10 | Backlog at task granularity with acceptance criteria | 16 items, 7 milestones |
| 11 | Required ADRs at least `Proposed` | ADR-0050…0053 |
| 12 | `CURRENT_STATE.md` names the active phase | Updated by this transition |

The first task is **`P6-TSK-001` — the `merchant` and `checkout` modules and schemas**
(`READY`), first for the standing reason — the privilege floor is what every later claim
rests on — and for the phase-specific one: the build-graph decisions (`merchant → ledger`
declared; `checkout → payments` and both `checkout ↔ merchant` edges **refused**) are
what keep the phase's named risks — a stored merchant balance, a god-orchestrator
checkout, tenancy as an afterthought — structurally unreachable before any merchant code
exists. Its scope carries this transition's own lesson forward: classification rows land
**in the task that creates the columns**.
