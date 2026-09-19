# Phase 4 → Phase 5 Transition

**Conducted:** 2026-09-20
**Parts:** Phase 4 completion audit · financial correctness audit · multi-instance audit ·
atomicity and consistency audit · idempotency audit · architecture audit · security audit ·
reconciliation-readiness audit · testing audit · findings and repair · Phase 5
initialisation
**Constraint:** a transition writes no application code. The one repair performed
(§9) is test infrastructure, made under the explicit repair-before-transition instruction
this transition was conducted with, and is recorded rather than absorbed.

---

## Verdict

| Part | Outcome |
|---|---|
| Phase 4 completion audit (18 categories) | **18 `PASS`** |
| Financial correctness audit (11 properties) | **11 `PASS`** |
| Multi-instance audit | **`PASS`** — *"would Phase 4 remain financially correct with 10 concurrent instances?"* |
| Atomicity and consistency audit | `PASS` — no atomicity is assumed across a boundary that does not have it |
| Idempotency audit | `PASS` — nothing rests on JVM-local memory |
| Architecture audit | **No drift**; the register-decay check found the register **current at a phase boundary for the first time** |
| Security audit | `PASS`, limits stated and owned |
| Reconciliation readiness | `PASS` — every link in the chain is a stored identifier |
| Testing audit | **The full battery, fleet-wide, for the first time in the phase: 1157 hermetic · 729 database · 14 kafka, 0 failures** — after one test-harness repair (§9) |
| **Phase 4** | **`COMPLETE`** — confirming `P4-DOC-001` |
| **Phase 5** | **Entry gate: all twelve criteria hold → `READY`** |

Phase 4 was ruled `COMPLETE` by its exit review yesterday
([`PHASE_4_REVIEW.md`](PHASE_4_REVIEW.md)). This audit is the **second, independent pass** —
the standing precedent: a gate assessed only by whoever just finished the work is not two
checks. Where the review's evidence is a day old and already counted, this audit re-checks
the claims against the code and probes what the review could not: the fleet-wide battery
the standing skip instruction had kept from ever running, the governance registers, the
single-instance sweep, and the planning inputs Phase 5 depends on.

---

## 1. Phase 4 completion audit

| # | Category | Verdict | Evidence |
|---|---|---|---|
| 1 | Transfer domain model | **`PASS`** | `Transfer` with one constructor holding every coherence rule (reason ⇔ `FAILED`, entry ⇔ money moved, the reversal triple, the pair rule); `rehydrate` refusing corrupt rows; typed ledger ids where the boundary permits, raw UUIDs where it forbids |
| 2 | Transfer lifecycle | **`PASS`** | ADR-0044's four states, five edges, enforced at aggregate (exhaustive derived sweep), schema (generated `CHECK`s + every-writer transition trigger, reconciled by `TransferMigrationTest`) and history (append-only, server-ordered) |
| 3 | Beneficiary handling | **`PASS`** | One-live partial index arbitrating the ten-way create, conditional removal with `party_id = ?` in the statement, every-writer freeze, step-up on creation exactly when a factor is enrolled, removed-refuses-transfers proven with row counts |
| 4 | Transfer authorization | **`PASS`** | Session + ownership on every customer surface (one-404 as equality between causes); `TRANSFER_REVERSE` on `LEDGER_OPERATOR` with negative test and required reason |
| 5 | Limits | **`PASS`** | The seams as **required constructor parameters** (removal fails compilation, performed), verdict-returning, consulted in-lock (the `FOR UPDATE NOWAIT` decorator probe), reasons reserved with their producers — and no Phase 13 logic, mechanised by the size guard |
| 6 | Account integration | **`PASS`** | Source resolved through the caller's live customer, destination through the ledger alone (no unowned `accounts` read exists); `transfers → accounts` refused in the build graph |
| 7 | Ledger integration | **`PASS`** | Postings commanded through `PostingService` on the caller's connection, never written (`INV-LED-04`); `ledger → transfers` a demonstrated Gradle cycle |
| 8 | Debit/credit correctness | **`PASS`** | One `POSTING` entry per transfer: debit source wallet, credit destination wallet, both `LIABILITY`; balanced per currency at COMMIT for every writer (`V004`); reversal swaps directions bounded by `V009` |
| 9 | Idempotency | **`PASS`** | Part 5 below |
| 10 | Concurrency | **`PASS`** | Part 3 below |
| 11 | Transaction boundaries | **`PASS`** | Part 4 below |
| 12 | Consistency | **`PASS`** | One local transaction per command (ADR-0043); the only eventually-consistent path — event delivery — carries no financial authority |
| 13 | Failure handling | **`PASS`** | All twelve plan §14 scenarios tested or recorded; the injected-failure probe leaves *nothing including the claim*; a seam refusal is a committed replayable `FAILED` |
| 14 | Security | **`PASS`** | Part 7 below |
| 15 | Auditability | **`PASS`** | Four actions, all emitted, actor/correlation/outcome, reason required on the reversal; denials audited by the interceptor |
| 16 | Observability | **`PASS`** | Four meters + dashboard row, eager from a plain context, judgement-vocabulary counting; the derived guard took §15's table over at the flip |
| 17 | Testing | **`PASS`** | Part 9 below — including the fleet-wide battery the review could not claim |
| 18 | Documentation | **`PASS`** | The review's two record corrections verified landed; the ADR registers build-reconciled; one stale second copy found by this audit (`ROADMAP.md`, §10) and corrected |

**Against the twenty-two Phase 4 gate criteria** (six original + sixteen extension): all
hold; `P4-DOC-001` assessed each with named evidence and this audit re-checked the claims —
including reading `TransferExecution` directly against the claim set (the claim → resolve →
judge → both-locks-in-fixed-order → availability-in-lock → seams → savepoint-guarded
posting → outcome ordering is as recorded).

## 2. Financial correctness audit

| # | Property | Verdict | Mechanism |
|---|---|---|---|
| 1 | Every balance-affecting transfer produces the correct financial effect | **`PASS`** | One balanced `POSTING` entry per completed transfer, counted in four tables by the acceptance suites; conservation storms sum to the funded amount exactly |
| 2 | Debit and credit postings balance | **`PASS`** | `V004`'s deferred COMMIT triggers for every writer; the trial-balance sweep zero per currency throughout `P4-TST-001`'s storm |
| 3 | No transfer creates or destroys money | **`PASS`** | The three-reading reconciliation: journal sum, independent recomputation from `transfers.transfer`, outcome tally — to the minor unit |
| 4 | Monetary precision and currency explicit | **`PASS`** | `MoneyColumns` shape pinned; currency-blind resolution refused as `CURRENCY_MISMATCH` carrying the real wallets; no floating point (STATIC, swept) |
| 5 | Financial history immutable | **`PASS`** | Journal at `DB-PRIVILEGE` + migrator-binding trigger; transfer rows insert-carries-outcome with `V002`'s freeze; the reversal leaves the original byte-identical as PostgreSQL's own renderings |
| 6 | Duplicate requests cannot duplicate effects | **`PASS`** | The unique claim; ten instances on one key produce one effect counted in four tables (`P4-TST-002`'s performed demonstration) |
| 7 | Reversal explicitly modelled | **`PASS`** | `COMPLETED → REVERSED` + a new referencing entry, one transaction, lock-then-look, machine + trigger + `V009` bound in depth |
| 8 | Failure cannot leave an unexplained state | **`PASS`** | ADR-0043: a domain refusal is a committed `FAILED` with its enumerated reason; a boundary mistake commits nothing at all; no durable intermediate state exists |
| 9 | Balances explainable from authoritative records | **`PASS`** | `INV-BAL-02`'s replay-from-zero and the projection verification, running against Phase 4's traffic in every storm |
| 10 | Limits cannot be bypassed concurrently | **`PASS`** (as scoped) | The seams are consulted **inside the source lock** — proven by the NOWAIT probe — so Phase 13's counters inherit atomicity; no limit *policy* exists yet, by design |
| 11 | Timeout-plus-retry cannot cause a second transfer | **`PASS`** | The claim survives exactly when its outcome committed: a lost response replays the stored judgement; an aborted execution leaves no claim and the retry executes afresh — both proven |

## 3. Multi-instance audit

**Would Phase 4 remain financially correct if 10 instances executed the relevant
operations concurrently?**

# `PASS`

The exit review's six contended decisions stand (execution drain, one-key race, reversal
race, beneficiary create/remove races, seam-in-lock); this audit adds what the review did
not run:

- **The single-instance sweep, fresh**: `synchronized`, process-local locks, `ThreadLocal`,
  `@Scheduled`, `ReentrantLock`, static mutable collections — **zero occurrences** across
  `transfers` and `app.transfers` production code and `TransferMetrics`.
- **Concurrent transfers from one account**: the drain — exactly the affordable transfers
  accepted, counted, never negative. **To one account**: the bidirectional storm, whose
  783-deadlock finding produced the fixed-order two-participant lock now recorded in §3 of
  the register, ADR-0039's follow-ups and the plan.
- **Duplicate commands / retry after timeout**: part 5. **Duplicate/delayed events,
  consumer restart**: no Phase 4 consumer exists; the outbox/inbox guarantees are the
  standing Phase 0/2-proven ones. **Stale reads**: every judgement derives inside the
  lock; the projection is never a decision's input. **Lost updates**: rows are
  insert-carries-outcome or conditional updates under `FOR UPDATE`. **Process-local state
  for correctness**: none (per-instance meters are declared non-authoritative readings).
- **The fleet-wide battery itself** (§9) is a multi-instance result of a different kind:
  the *test* fleet's connection arithmetic failed where the production arithmetic is
  guarded — the same class `P1-TSK-004` closed for deployments, now closed for the
  harness.

## 4. Atomicity and consistency audit

| Operation | One transaction contains | Arbiter | Recovery |
|---|---|---|---|
| Transfer execution | Claim + row + history + journal entry (via `PostingService`) + audit + outbox | The claim's unique constraint; both account rows `FOR UPDATE` in fixed UUID order | Nothing-at-all on failure (claim included) — retry executes afresh; committed outcomes replay |
| Domain refusal | `FAILED` row + reason + history + audit + outbox, **no posting, no money claim** | Same | The refusal replays to the retry |
| Mid-flight destination refusal | The savepoint converts `V007`'s abort into a committed `FAILED(DESTINATION_NOT_POSTABLE)` | The posting trigger | Deterministic, proven |
| Reversal | Machine check under the transfer row's `FOR UPDATE` + referencing entry + state move + audit + outbox | Lock-then-look; conditional row count as belt; `V009` bound beneath | One 201, nine 409s; the loser resumes onto the winner's commit |
| Beneficiary create/remove | Row + audit (+ event: none, by plan) | Partial unique index / conditional update | Savepoint converge; retries converge |

No distributed operation is assumed atomic: there is none — ADR-0043's whole point, and
the boundary at which that answer changes (Phase 5) is what this transition initialises.

## 5. Idempotency audit

Same key, same request → byte-for-byte replay of the original body, success and failure
both (the view renders the replayed judgement, so `P4-TSK-009`'s reversal cannot leak into
a replay — proven). Same key, different payload → the distinct 409 (`INV-IDEM-03`,
fingerprint binding the actor and the money's meaning). Cross-instance keys → the claim is
a database constraint; ten instances, one effect, counted. Retry after timeout → the claim
survives iff the outcome committed (both directions proven). Duplicate events → no Phase 4
consumer; the inbox mechanism stands ready. Restart → no in-memory state to lose.
`CONTENDED` → the honest `IdempotencyInProgressException`, bounded, reported as unknown —
never assumed failed.

## 6. Architecture audit

**No drift.** Boundaries match `MODULE_ARCHITECTURE.md` §3 (the `transfers → ledger` edge
and both refusals demonstrated in the build graph); `BOUNDED_CONTEXTS.md` context 8
ownership holds; `DOMAIN_MODEL.md` §Time honoured (posting/value dates explicit inputs);
event discipline per ADR-0044 (terminal facts only); the ADR registers build-reconciled by
`P4-TSK-002`'s guard — the second-copy decay found by hand at three consecutive earlier
gates produced **zero** findings here, which is the guard succeeding, and ADR-0043/0044
read `Accepted` in both copies.

**The register-decay check — the named transition-audit step since Phase 3 — found
`DISTRIBUTED_EXECUTION.md` §3 current at a phase boundary for the first time**: the
`transfers.transfer` row landed with `P4-TSK-009` (the register's note finally heeded by a
task), the `transfers.beneficiary` row with `P4-DOC-001`'s hand-diff, both seam rows with
`P4-TSK-010`. Five occurrences of the decay class, and the first boundary with nothing to
repair.

**One stale second copy found by this audit and corrected**: `ROADMAP.md` §Current
position, frozen at 2026-09-18 with Phase 4 `IN_PROGRESS` at 8 of 14 — through the
review, the flip and two further commits. And one **overdue row in the unresolved-questions
table**: question 10 (due Phase 2) sat open three phases after Phase 2's plan and delivery
answered it — ruled resolved with provenance (§11).

## 7. Security audit

`PASS`. Transfer surfaces under session + ownership with the one-404 disciplines;
beneficiary creation under the conditional step-up (negatively tested, nothing written on
refusal); reversal behind `TRANSFER_REVERSE` with a required reason travelling in the body
(`INV-AUD-02`), negatively tested, denials audited; no amount in any exception, message,
event payload or metric tag (needle-asserted throughout the phase); `reference`
`RESTRICTED-PII` and `failure_reason` `CONFIDENTIAL` at their ceilings; no new credential
this phase; service-to-service authentication has no subject (one deployable). Limits
unchanged and owned: operational endpoints unauthenticated (Phase 15), per-source rate
limiting blocked on topology (Phase 15), the per-credential confinement generalisation —
**now scheduled as `P5-TSK-002`** rather than merely owed.

## 8. Reconciliation readiness

`PASS`. The chain instruction → transfer → journal entry → lines → balances → statement is
walkable by stored identifier in both directions (transfer id in the entry's `reference`,
`journalEntryId` and `reversalEntryId` on the row, the claim's scope joining the commanding
flow); amounts exact with currency and scale; timestamps from the injected clock; lifecycle
history append-only with server-assigned order. **Provider/external reference fields have
no subject in Phase 4** — both legs internal — and Phase 5's schema introduces them
(`INV-PAY-04`'s references, provider references, verbatim evidence) as the raw material
Phase 8 consumes. Nothing found that would make settlement integration difficult; the
`PSP_CLEARING` account Phase 5 will post to has stood seeded since `P3-TSK-003`.

## 9. Testing audit — and the finding

**The exit review's one recorded deviation was criterion 7**: the owner's standing
instruction skipped `build databaseTest kafkaTest`, so the hermetic tier was fleet-wide
(1157/0) and the database/kafka tiers were verified per task, with **no fleet-wide count
claimed**. This transition exercised the review's own closing suggestion and ran the full
battery.

**It failed.** Every `com.finapp.app.transfers` database suite — thirty-odd tests across
five suites, each green in its targeted fresh-JVM runs all phase — failed with
`FATAL: remaining connection slots are reserved for roles with the SUPERUSER attribute`.
**Zero assertion failures anywhere in the run.**

**Root cause, established by arithmetic rather than guessed**: the per-JVM test container
runs the image default `max_connections = 100`; Spring caches every distinct context
configuration for the JVM's life and each cached context holds a **fixed** pool of 8
(`minimum-idle = maximum-pool-size`, `P1-TSK-004`'s deliberate design); the app database
tier now runs ~89 suites' worth of contexts in one JVM, plus the suites' own raw
connections (ten-way races hold ten each). The fleet of cached contexts stopped fitting,
and the alphabetically-last suites — `app.transfers.*` — paid. This is `P1-TSK-004`'s
*"the fleet does not fit"* finding arriving in the test fleet, and it was **structurally
invisible to every targeted run**: a fresh JVM caches too few contexts to matter. The last
fleet-wide database run predates Phase 4 (the Phase 3 → 4 transition, 683 tests); Phase
4's additions crossed the ceiling.

**Severity ruled honestly**: a test-infrastructure defect, not a correctness defect — no
assertion failed, and no production claim is touched (the production connection arithmetic
is asserted by `ConnectionPoolSizingGuard` and its build test against declared deployment
configuration, not against this container). But it made exit criterion 7's full-suite
claim **structurally unsatisfiable**, which blocks the transition under the
repair-before-transition rule — so it was repaired rather than recorded: `DatabaseUnderTest`
now provisions `max_connections=400` on the container whose only client is one test JVM,
with the finding recorded in a comment at the line.

**The re-run: `BUILD SUCCESSFUL` — 1157 hermetic · 729 database · 14 kafka tests, 0
failures, 0 skips** — the first genuine fleet-wide database and kafka count of Phase 4.
The deviation's recorded wording (*"no fleet-wide count is claimed"*) is vindicated in the
sharpest way: the count that was not claimed did not, at that moment, exist to claim.

Adequacy beyond green, re-checked rather than inherited: 5 of 5 `Phase: 4` invariants with
register rows and performed demonstrations; 83 mutations across the phase, every one
caught by the intended assertion, 0 wrong survivals; coordination asserted (losers observed
Lock-waiting; storms ended by verifier floors); constraints proven against raw SQL from
scratch. Standing not-covered statement unchanged (no load testing before Phase 16;
providers simulated by design).

## 10. Findings

| Severity | Finding | Blocks Phase 5? | Remediation |
|---|---|---|---|
| **IMPORTANT** | The fleet-wide database tier could not run: the test container's connection ceiling vs the cached-context fleet (§9) — found only because this transition ran the battery the standing skip had kept from ever running | Would have (criterion 7 unsatisfiable) | **Repaired here**: the harness provisions the ceiling; battery green fleet-wide |
| MINOR | `ROADMAP.md` §Current position frozen at 2026-09-18, two days and one phase-flip stale | No | Corrected by this transition |
| MINOR | Unresolved question 10 three phases overdue in the open table | No | Ruled resolved with provenance |
| MINOR | The event lists carried no `RefundFailed` while ADR-0044's own doctrine (terminal facts publish) demands it for Phase 5 | No | Corrected in `MODULE_ARCHITECTURE.md` and `DELIVERY_PLAN.md` with provenance |

**No CRITICAL findings. No financial-correctness findings of any severity.** No debt
hidden: the standing rows carry forward unchanged except the confinement row, which is now
scheduled (`P5-TSK-002`).

## 11. Phase 4 completion

**Phase 4 is `COMPLETE` (2026-09-19)**, confirming `P4-DOC-001` — now with the full
battery behind it.

**Delivered**: money moves between customers — 1 module, 3 tables, 4 migrations, 7
operations on 5 paths, 2 aggregates, 4 audit actions all emitted, 3 terminal events, 3
error codes, 1 permission, 4 meters and a dashboard row, 2 ADRs `Accepted`, 0 new
invariants (5 in scope, 5 register rows), 14 of 14 items across 8 milestones, 83 mutations
all caught, and **1157 / 729 / 14 fleet-wide, 0 failures**.

## 12. Phase 5 initialisation

**Phase 5 — Payment Infrastructure — is `READY`.**

Decisions taken, because Phase 5 cannot start without them: **ADR-0045** (intent/attempt,
three machines, every state earned), **ADR-0046** (no transaction spans a provider call;
`UNKNOWN` modelled; reconciliation by query, no lease), **ADR-0047** (webhooks:
authenticated before parsing, freshness-bounded, evidence-first, order-blind),
**ADR-0048** (authorization posts nothing; the ledger's first touch is capture — closes
question 6), **ADR-0049** (a simulated card-style PSP first; nothing final before
settlement — closes question 9). **The `INV-PAY-01…05` group catalogued** (87 invariants):
Phase 5's gate properties existed only as prose bullets, the exact weaker regime the
`INV-IDN` and `INV-KYC` groups were created to escape, and Phase 5's product is surviving
an unreliable provider.

All twelve entry-gate criteria hold:

| # | Criterion | Evidence |
|---|---|---|
| 1 | Hard dependencies `COMPLETE` | Phase 4 (internal movement proven end to end), confirmed above |
| 2 | Delivery-plan section current and specific | §Phase 5, with this transition's event-list correction |
| 3 | Bounded contexts and aggregates identified | `PHASE_5_PLAN.md` §3, §4 |
| 4 | Invariants identified by ID, from the catalogue | §6 — eleven, token-parsed, `INV-PAY` included |
| 5 | Lifecycles drafted | ADR-0045; `PAYMENT_LIFECYCLES.md` §2–§4 |
| 6 | Transaction and consistency boundaries stated | ADR-0046, ADR-0048; plan §7 |
| 7 | Idempotency stated for every money-moving command | Plan §4/§9 — create and refund keyed; confirm/cancel by machine; provider-side per `INV-PAY-04` |
| 8 | External dependencies and failure modes listed | §14 — fifteen scenarios; the provider is the phase's subject |
| 9 | Security, audit, reconciliation implications stated | §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | 21 items, 9 milestones |
| 11 | Required ADRs at least `Proposed` | ADR-0045…0049 |
| 12 | `CURRENT_STATE.md` names the active phase | Updated by this transition |

The first task is **`P5-TSK-001` — the `payments` and `paymentmethods` modules and
schemas** (`READY`), first for the standing reason — the privilege floor is what every
later grant claim rests on — and for the phase-specific one: the build-graph decisions
(`payments → ledger` declared; `payments → paymentmethods` **refused**, the instrument
resolving through a port) are what keep the phase's named risks — the payment module
writing postings, and raw card data crossing the PCI line — structurally unreachable
before any payment code exists.
