# Phase 5 Exit Review — Payment Infrastructure

**Conducted:** 2026-09-21 (`P5-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 5-specific criteria, read from the gate at
review time)
**Phase objective under review:** money that enters and leaves the platform through a party
that can fail, lie, answer late, or not answer at all — a payment with an explicit lifecycle,
an idempotency reference the provider honours, an honest `*_UNKNOWN` state when the answer is
lost, resolvers that settle it, a bounded refund that reserves the customer's funds, and a
ledger that never records what the provider did not do.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`** — area 2 walks a **payment and its refund**, which is what this phase was for |
| Universal criteria (12) | **12 `PASS`**, one of them (7) with a recorded deviation, one of them (10) **met by this review's own act** — see below |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited |
| Phase 5-specific criteria | **19 `PASS`** — 8 original + 11 added by the Phase 4 → 5 transition, counted from the gate at review time rather than from a remembered number |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — seven contended decisions, each with its arbiter and its counted race |
| **Verdict** | **Phase 5 `COMPLETE` (2026-09-21)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act, because recording a phase `COMPLETE` changes what the build demands** →
re-run the battery → finalise with counted numbers.

**The flip surfaced nothing, and that was pre-paid three times rather than lucky.**
`P5-TST-002` landed every demanded `Phase: 5` invariant row and *probed* the flip;
`P5-TSK-017` landed §15's six meters behind a pinned Phase-5 guard the derived rule takes
over with no edit; `P5-TST-003` closed the one item that probe deliberately left red. Third
phase running that the gate machinery finished its work **before** the gate rather than at it.

**And the flip was proven non-vacuous against the real status, not only the simulated one.**
A guard quietly still reading *phase 4* would pass exactly as loudly as one enforcing phase 5,
so after the flip one `Phase: 5` row was removed: the build fails naming **`INV-PAY-01`** and
reporting **`(currently 5)`**. Restored byte-identical — `git` reports the file unmodified —
and the tier green again. The demanded set really did grow, and the nine rows `P5-TST-002`
landed are what carries it.

**THE POST-FLIP BATTERY FOUND A RED TEST, WHICH IS THE POINT OF RUNNING IT.**
`RoleNameTest#ledgerOperatorGrantsExactlyThree` had been failing since `P5-TSK-015` widened
`LEDGER_OPERATOR` with `PAYMENT_REFUND`: the phase was verified by **targeted** tiers —
`:payments:test`, `:platform:test`, `:app:test` and the payment database suites — and
**`:identity:test` was in none of them**, so a role gaining a permission passed every check
the task itself ran. The pin is corrected to the four money-operating permissions (with the
episode recorded in the test's own comment), and the finding is recorded in area 8 as the
measured cost of the skip instruction: not a hypothetical risk, an actual red test that
survived **four subsequent completion gates** (P5-TSK-016, -017, P5-TST-002, P5-TST-003),
each of which verified exactly the tiers its own task touched.

## The two criteria that were not free

**Criterion 10 — every ADR `Accepted`.** ADR-0045…0049 all read `Status: Proposed`, deferred
across the phase with this audit named as the owner every time. Accepting them is the review's
own act, and it was not a rubber stamp: each was read against the code that now exists.
ADR-0046 §3–§4 describes a crash mid-call stranding a visible `*_DISPATCHED` that a
**leaderless, leaseless** sweeper resolves by querying **our** stored reference — which is
`PaymentSweeper` as built, down to the absent lease. ADR-0048 §4 describes the refund holding
the customer's wallet inside the account lock and completion releasing-and-posting under the
key `payment-refund:<refundId>` — which is `PaymentOutcomes.applyRefund` as built, down to the
key. ADR-0047's evidence-first, freshness-bounded, conditionally-idempotent door is
`PaymentWebhookService` as built, including the §5 anti-stall inversion. **No drift found, so
all five are accepted**; had one drifted, that would have been a gate finding and the
acceptance would have waited for the correction.

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction for
this phase skips `build databaseTest kafkaTest`, and every one of the twenty items was verified
by targeted tiers and recorded in those words. This review does not resolve that by assertion:

- The **hermetic** tier was run **fleet-wide after the flip** — **1323 tests across twelve
  modules, 0 failures** — and that is where both guards the flip arms live, so the act this
  review performs is fully verified.
- The **database and kafka** tiers were verified per task, suite by suite, throughout the
  phase; the payment database suites stood at **81** and the telemetry ones at **11** at
  `P5-TST-003`. **No fleet-wide database or kafka count is claimed for Phase 5.**

Assessed **`PASS` with the deviation recorded** rather than waived: the suites exist, run and
pass; what is absent is a single fleet-wide execution of two tiers, by explicit instruction.
Listed in area 8 with an owner, because a limit stated is a limit somebody can act on and a
limit glossed is one nobody can.

---

## Area 1 — Scope: what the phase set out to build, and what it built

Twenty-one backlog items, twenty `COMPLETE` and this review the twenty-first. Counted at review
time — **and recounted at this item's own completion gate, which found three of these numbers
wrong on the first pass**, which is the `P3-DOC-001` rule earning its place inside the review
that cites it: **2 modules** touched as owners (`payments`, `paymentmethods`), **8 tables**
(7 in `payments` — intent, attempt, refund and their three history tables, plus provider
evidence — and 1 in `paymentmethods`), **8 migrations**, **6 HTTP operations on 4 payment
paths** plus 2 instrument paths and the machine-facing webhook door, **8 auditable actions**
(6 payment + 2 instrument), **11 error codes** (9 payment + 2 instrument), **10 event types** (8 payment + 2 instrument),
**5 ADRs**, **6 meters**, and **11 `Phase: 5` invariants** out of the platform's 87.

What a customer can now do that they could not before: attach a card through a tokenisation
provider that never lets a PAN into the platform, pay with it, watch the payment tell the
truth while the provider is thinking (`PROCESSING`, honestly), and have the money come back —
in part or in full — with the funds reserved from the moment the refund is dispatched. What an
operator can do: refund with a reason that lands in the audit trail, and see stuck money on a
dashboard.

`PASS`.

## Area 2 — Walk one real payment end to end, and then its refund

The economic event is a customer paying 10.00 EUR with an attached card.

1. **Domain operation.** `POST /v1/payments` claims `payment.create` (fingerprint binding the
   actor and the money's meaning), resolves the caller's own wallet and instrument, and writes
   a `REQUIRES_CONFIRMATION` intent. **Nothing financial happens.**
2. **Dispatch.** `POST /v1/payments/{id}/confirmation` takes the intent's conditional
   `REQUIRES_CONFIRMATION → PROCESSING`, births the attempt, and **commits with the minted
   idempotency reference stored** — before the provider is asked (ADR-0046, `INV-PAY-04`).
3. **The provider call** runs holding **no database connection**, asserted structurally.
4. **Authorization outcome.** `AUTHORIZED` commits. **The ledger is untouched** — the issuer
   holds the customer's external funds and nothing about our books has changed (ADR-0048).
5. **Financial transaction.** The chained capture dispatches, calls, and applies its outcome:
   in **one transaction**, the attempt's conditional `CAPTURE_DISPATCHED → CAPTURED`, the
   **journal entry** under the claim `payment-capture:<attemptId>`, and the intent's
   `SUCCEEDED`.
6. **Journal entry → lines.** Two lines: **DR `SETTLEMENT_CLEARING` / CR the customer's
   wallet**, 10.00 EUR each, the entry's `reference` being the attempt id — which is what makes
   the chain walkable by stored identifier with no timestamp join. `P5-TST-002` made the
   *accounts* checkable, having found that every payment test counted lines and none asserted
   which accounts they land on.
7. **Balances.** The wallet's settled balance is 10.00, reproducible by replaying postings from
   zero, and the clearing position carries the counterpart — **captured is not settled**
   (`INV-SET-01`), which is why the counterpart is a clearing account and not cash.
8. **The refund reverses it.** `POST /v1/payments/{id}/refund` takes the attempt row lock
   first, judges `sum(non-FAILED) + this ≤ captured` under it, places a **hold** on the wallet
   inside the account lock, and commits before calling. Completion releases-and-posts
   atomically — **DR wallet / CR clearing**, the capture's exact inverse, under
   `payment-refund:<refundId>`; failure releases with nothing posted; ambiguity leaves the hold
   standing so the money is visibly parked.

`PASS`.

## Area 3 — Multi-instance correctness

*"Would this be correct with 10 concurrent instances?"* — answered over the phase's contended
decisions, each with its arbiter and its counted race:

| Decision | Arbiter | Counted |
|---|---|---|
| Two clients create one payment | The idempotency claim's unique constraint | Ten-way, one intent |
| Two confirm one intent | The intent's conditional `REQUIRES_CONFIRMATION → PROCESSING` | Ten instances, one attempt, losers converge **without calling the provider** |
| Two capture one attempt | `dispatchCapture`'s row count + the posting claim | Ten-way, **one journal entry** |
| Two resolvers answer one operation | The outcome's conditional transitions (sync / webhook / sweeper) | Ten webhooks → one entry, one transition; sweeper vs webhook → one effect |
| Two refunds against one capture | The attempt row lock (lock-then-look) + `V004`'s advisory-locked trigger | Ten-way, exactly the bounded set; the funded-wallet race proves the lock load-bearing |
| Duplicate webhook delivery | The inbox primary key, then the machine | Triple delivery → one dedupe record, three evidence rows, one effect |
| **A capture and a refund on one wallet** | The pinned **attempt → account** lock order | `P5-TST-003`'s storm: green as built, **`40P01` when inverted** |

The last row is the one this phase could most easily have got wrong, and it is the one the
composition suite had to be corrected to prove — see area 6.

`PASS`.

## Area 4 — Failure behaviour: two traced through the code

**The provider answers after we gave up.** The adapter's timeout is folded to
`INDETERMINATE` — *never* `NOTHING_SENT` — so the outcome transaction commits `*_UNKNOWN` with
nothing posted. The payment's own `GET` says `PROCESSING`, honestly. The sweeper later asks the
provider about **our** stored reference, holding no connection, and applies the answer through
the same conditional transitions every other resolver uses: the capture posts **once**, counted
by the attempt-id reference. `P5-TST-001` drives exactly this end to end, including the client
retry that converges mid-ambiguity with **zero wire calls**.

**A crash between the dispatch commit and the provider call.** The attempt is left visibly
`*_DISPATCHED` — the state exists precisely so that this is not silence — and either a webhook
or the sweeper resolves it. The retry path cannot double-charge, because the reference was
stored before anything was sent (`INV-PAY-04`) and the resolver re-drives that same reference.
`P5-TSK-016`'s takeover test extends this to the refund: a crashed flight's hold and row are
converged upon, never duplicated, with `V008`'s unique index standing behind the convergence.

`PASS`.

## Area 5 — Security and audit

Privileged actions enumerated, each authorised and audited:

- **Refund** — `@RequiresPermission(PAYMENT_REFUND)` on `LEDGER_OPERATOR`, **negatively tested**
  (a permissionless session gets 403 with refund and hold counts unmoved), reason **required**
  and asserted verbatim in the operator's own audit record.
- **The webhook door** — signature over the raw bytes **before parsing**, within a two-sided
  freshness window, constant-time comparison pinned structurally; seven forgery shapes are one
  uniform 401 with nothing written.
- **Instrument attach** — second factor required when one is enrolled, negatively tested; the
  PCI boundary is mechanical (`payments` cannot even compile against `paymentmethods`, proven
  by mutation at `P5-TST-002`'s gate).
- **Five `enterSystem()` sites**, each enumerated with the reason there is no honest
  alternative: a provider's answer has no session, whichever resolver carries it.

`PASS`.

## Area 6 — Invariants, the register, and the demonstrations

Eleven `Phase: 5` invariants, read from the **catalogue** with the guard's own token regex.
Every one carries a `MUTATION_TESTING.md` §2 row, nine of them landed by `P5-TST-002` — five
that had none and **four that carried only an earlier phase's row**, which the guard
structurally cannot demand because it keys on the invariant identifier.

Two findings in this area are worth the phase's memory:

- **`INV-SET-01` had no test that could fail.** Pointing the capture's debit at a suspense
  account left all eighty payment database tests green. The probe was built before the row was
  written, and the register records the general lesson: *a posting test that counts lines proves
  the entry exists, not that it is the right entry.*
- **`P5-TST-003`'s headline claim was false until its own gate probed it.** Inverting the
  refund's pinned lock order survived the whole storm, because refunders only ever saw attempts
  *after* capture returned. They now read attempts from the database, and the mutation fails
  with `40P01`.

Both are the register's doctrine working: a row whose demonstration was inferred is worse than
a missing one, and the same standard applied to the audit item's own rows (four cited
demonstrations nobody had performed; two were performed at that gate, one dropped with its
limit recorded, one restated as the standing needle it is).

`PASS`.

## Area 7 — Documentation accuracy

`MODULE_ARCHITECTURE.md`'s payments context, `PAYMENT_LIFECYCLES.md`, `ERROR_CONTRACT.md` §3,
`AUDITABLE_ACTIONS.md`, `DISTRIBUTED_EXECUTION.md` §3 and the `payments` package javadoc were
each updated by the task that changed the behaviour they describe, and the build enforces the
agreements that can be enforced: the error catalogue is reconciled with the enum, the auditable
actions with theirs, the OpenAPI baseline with the live document, the planned meters with the
registry, and the register with the catalogue. The phase's own plan §15 table is now read by
two guards rather than one.

`PASS`.

## Area 8 — Debt, and what is deliberately deferred

| Item | Owner |
|---|---|
| No fleet-wide `databaseTest`/`kafkaTest` run for the phase (owner's standing instruction) — **and its cost is now measured, not hypothetical**: a hermetic test in a module no targeted tier covered survived four subsequent completion gates (see above) | The owner, whenever the instruction is lifted; mitigated meanwhile by running the **fleet-wide hermetic** tier at every phase gate |
| Refund sweeping — an `UNKNOWN` refund is resolved by webhook, not yet by the sweeper; the standing hold is the visible symptom | Phase 6 or a sweeper extension |
| `V004`'s refund-bound trigger is not itself mutation-demonstrated (mutating an applied migration needs a from-scratch database) | Phase 8's reconciliation work, or a dedicated item |
| `P5-TSK-005` and `P5-TSK-008` recorded no mutation sweep; their invariants were covered at `P5-TST-002`'s gate instead | Closed by that item |
| Outbound request bytes are not captured as evidence (evidence is everything *received*) | Phase 8 |
| Settlement itself — captured is not settled, and nothing here pretends otherwise | Phases 7–8 |

`PASS` — everything above is recorded with an owner rather than carried silently.

---

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — attach, pay, confirm, capture, refund, webhooks, sweeper, meters, all exercisable end to end over real HTTP |
| 2 | Architectural boundaries respected | `PASS` — module isolation green, and the `payments → paymentmethods` refusal proven by mutation |
| 3 | Required invariants tested | `PASS` — eleven `Phase: 5` invariants, every one with a register row |
| 4 | Failure cases handled | `PASS` — **all fifteen** of `PHASE_5_PLAN.md` **§14** (the gate's generic wording says "§12"; this phase's plan numbers its failure list §14, and this item's own gate caught the review citing the wrong section with seven of the fifteen). Thirteen have a test in this phase: timeout-with-authorisation, lost response, success-after-resolved-failure, webhook-before-sync, webhook-never-arrives, unrecognised state, duplicate webhook, out-of-order webhooks, crash mid-call, duplicate capture after timeout, concurrent partial refunds, connection refused, tokenisation down. **Scenario 10** (crash between outcome commit and event publication) rests on the outbox's at-least-once machinery, tested at `P0-TSK-020`/`P2-TSK-001` and unchanged here — the facts are written **in the committing transaction**, which is why this phase adds no new failure mode. **Scenario 13** (refund completes after the customer spent the wallet) the plan calls impossible *because of the hold* — and `P5-TST-003` drove exactly that contention on purpose, with spenders draining wallets while refunds held them |
| 5 | Security requirements implemented | `PASS` — see area 5; every privileged action negatively tested |
| 6 | Observability exists | `PASS` — six meters from a freshly started instance with no database and no provider configured; the stuck-payment gauges alertable; correlation propagated |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — see above |
| 8 | Documentation reflects reality | `PASS` — see area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's own finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0045…0049 read against the code, then accepted |
| 11 | No unresolved critical issues | `PASS` — blockers: none; nothing in area 8 is `critical` or `high` |
| 12 | Formal phase review conducted | `PASS` — this document |

## The financial supplement F1–F8 — re-assessed at the gate

| # | Criterion | Verdict |
|---|---|---|
| F1 | Trial balance zero per currency | `Met` — asserted on every sweep round of `P5-TST-003`'s storm and at its end |
| F2 | Balances reproducible by replay from zero | `Met` — the capture suite's derivation check; the storm's projection verdicts end `CLEAN` |
| F3 | Money-moving commands idempotent at the financial boundary | `Met` — `payment.create`, `payment.refund`, and the postings' own claims (`payment-capture:`, `payment-refund:`) |
| F4 | Reversal/compensation implemented, no path mutates history | `Met` — the refund is the compensation, bounded by its capture; histories are insert-only by privilege |
| F5 | Duplicate external delivery produces no second effect | `Met` — triple delivery, distinct-id duplicate, ten-way webhook race, all counted to one |
| F6 | Concurrency tests for every contended financial resource | `Met` — the seven rows of area 3 |
| F7 | No floating point in a monetary path | `Met` — statically verified, and the rule caught a real `ResultSet.getDouble` in the gauge reads at `P5-TSK-017` |
| F8 | Reconciliation implemented or deferred with a named owner | `Met` — the sweeper is reconciliation-by-query; settlement reconciliation is Phase 8, named |

## The Phase 5-specific criteria — nineteen

Counted from `PHASE_GATES.md` §5 at review time: **8 original + 11 added by the Phase 4 → 5
transition = 19**, every one `PASS`. The originals: timeout-then-success handled and tested;
`UNKNOWN` modelled with a sweeper that resolves it; duplicate and out-of-order webhooks
producing no duplicate effect; signature and replay-window rejection tested; no provider
vocabulary in the domain or contract; no raw PAN anywhere; evidence retained for Phase 8;
refunds bounded under concurrent partials. The transition's additions are the measurable ones —
the ambiguity demonstration counted end to end, no transaction spanning a provider call, the
leaseless sweeper's race, capture as the ledger's first touch, conservation under the storm,
the refund's reserved funds, per-cause webhook negatives, the mechanical PCI boundary, refund
authority as a named permission, the meters and the traceable chain, and every `Phase: 5`
invariant carrying a register row.

## ADR-0045 … ADR-0049: accepted

Read against the implementation, found to describe it, and moved from `Proposed` to `Accepted`
by this review — the phase's five architectural decisions: the two aggregates and three
machines; no transaction spanning a provider call; the webhook door's four rules; authorization
as a payment fact with capture as the ledger's first touch; and the simulated card PSP with
nothing final before settlement.

## What the phase produced

A customer's money can now enter the platform through a third party that may fail in every way
a third party can, and leave it again, with the books explaining both. The mechanisms that
carry it — dispatch-before-call, the honest `*_UNKNOWN`, resolution by query, conditional
transitions as the universal arbiter, hold-then-post — are the phase's real output, and they
are the ones Phases 6, 7 and 8 will build merchants, rails and settlement on.

## What happens next

Phase 6 — Checkout and Merchant Platform — behind its own entry gate. The Phase 5 → 6
transition is the next planning act; this review's only claim about it is that nothing in area
8 blocks it.
