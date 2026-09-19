# Phase 4 Exit Review — Internal Transfers

**Conducted:** 2026-09-19 (`P4-DOC-001`) — the assessments, the flip and the post-flip
battery all ran on the 19th; the commit that carries this record lands on the 20th, which is
noted so a reader diffing `git log` against the dates below is not left to guess.
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 4-specific criteria)
**Phase objective under review:** the first customer-visible money movement — a verified
customer moves funds between two platform accounts, with an explicit lifecycle, idempotency at
the financial boundary, every command audited, a privileged reasoned reversal, second-factor
beneficiary creation, and the limit and risk seams as contracts Phase 13 can honour.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`** — area 2 walks a **transfer**, which is what this phase was for |
| Universal criteria (12) | **12 `PASS`**, one of them (7) with a recorded deviation — see below |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited |
| Phase 4-specific criteria | **16 `PASS`** — 6 original + 10 added by the Phase 3 → 4 transition, read from the gate at review time rather than from a remembered count |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — six contended decisions, each with its arbiter and its counted race |
| **Verdict** | **Phase 4 `COMPLETE` (2026-09-19)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act, because recording a phase `COMPLETE` changes what the build demands** →
re-run the battery → finalise with counted numbers.

**The post-flip battery is green: 1157 hermetic tests, 0 failures, across all ten modules** —
including both guards the flip arms, `MutationDemonstrationTest` (which now derives a demanded
set of five `Phase: 4` invariants from the catalogue) and `PlannedMetersExistTest` (whose
derived rule now unions `PHASE_4_PLAN.md` §15's meter table). **The flip surfaced nothing, and
that was pre-paid twice rather than lucky**: `P4-TST-002` landed every demanded row and
*probed* the flip — simulating `COMPLETE`, running the battery, then removing one row to prove
the demanded set had genuinely grown (the guard reported *currently 4*) — and `P4-TSK-011`
landed §15's meters behind a pinned guard the derived one takes over with no edit. Second
phase running that the gate machinery finished its work before the gate rather than at it.

**And the flip was proven non-vacuous against the real status, not only the simulated one.**
A guard that quietly kept reading *phase 3* would pass exactly as loudly as one enforcing
phase 4, so after the flip one `Phase: 4` row was removed: the build fails naming the
invariant and reporting **`(currently 4)`**. Restored byte-identical — `git` reports the file
unmodified — and the tier green again. The demanded set really did grow, and the five rows
`P4-TST-002` landed are what carries it.

## The one criterion met with a recorded deviation

Criterion 7 reads *"Full suite green against real infrastructure via Testcontainers"*. The
owner's standing instruction for this phase is that `build databaseTest kafkaTest` is
**skipped**, and every one of the fourteen items was verified by targeted tiers and recorded
in those words. This review does not resolve that by assertion:

- The **hermetic** tier was run **fleet-wide** — 1157 tests across ten modules, 0 failures —
  and that is where the flip's own guards live, so the act this review performs is fully
  verified.
- The **database and kafka** tiers were verified per task, suite by suite, throughout the
  phase. **No fleet-wide database or kafka count is claimed for Phase 4**, and the last one
  that was measured is `P4-TSK-003`'s (2026-09-17: 683 database, 14 kafka).

The criterion is assessed **`PASS` with the deviation recorded** rather than waived: the suites
exist, run and pass; what is absent is a single fleet-wide execution of two tiers, by the
owner's explicit instruction. It is listed in area 8 with an owner, because a limit stated is a
limit somebody can act on and a limit glossed is one nobody can.

---

## Area 1 — Scope: what the phase set out to build, and what it built

`PHASE_4_PLAN.md` §1 names one capability and the implementation carries it whole. Counted from
the repository rather than recalled:

| | Planned | Built |
|---|---|---|
| Endpoints | 7 (§9) | **7**, published in `docs/api/openapi.json` on 5 paths |
| Aggregates | Transfer, Beneficiary | **2** |
| Tables | transfer, transfer history, beneficiary | **3**, in 4 migrations (`V001`–`V004`) |
| Events | 3 terminal facts (§10) | **3** |
| Audit actions | 4 (§11) | **4**, all emitted — `NOT_YET_EMITTED` still holds exactly the three Phase-15 `outbox.*` actions |
| Error codes | — | **3** `transfers.*`, catalogued and reconciled by the build |
| Permissions | `TRANSFER_REVERSE` (§11) | **1**, on the existing `LEDGER_OPERATOR`; **no new role** |
| Meters | 4 (§15) | **4**, eager, with a dashboard row resolving against a live scrape |
| Invariants in scope | whatever the catalogue marks `Phase: 4` | **5**, token-exactly; **5** register rows |
| ADRs | ADR-0043, ADR-0044 | **2**, `Accepted` at this gate |
| Backlog | 14 items, 8 milestones | **14 of 14**, all milestones `CLOSED` |

**Three things the plan deliberately refused, and all three stayed refused** (§15, §17): value-
by-state meters (an aggregate money figure in a telemetry store is a financial number outside
the ledger's authority), the stuck-transfer detector (no durable intermediate state exists to be
stuck under ADR-0043 — it would monitor a fiction), and `TransferInitiated` (it would commit
beside its own outcome, so it is one fact named twice). Each absence is recorded with its
reasoning in the plan, the module register and the delivery plan.

## Area 2 — Walk one real transfer end to end

The programme's second phase with a subject for this area, and the first where the subject is a
*transfer*. The walk below is the acceptance chain of
`TransferEndpointDatabaseTest#theAcceptanceChainHolds`, driven over real HTTP against a real
PostgreSQL, with every step naming its code.

| Step | What happens | Where | Proven by |
|---|---|---|---|
| **Economic event** | A verified customer instructs a transfer of 3.00 USD to another customer's wallet | `POST /v1/transfers`, session + `@RequiresIdempotencyKey` | `theAcceptanceChainHolds` |
| **Domain operation** | The ownership chain resolves Session → Identity → live Customer → the caller's own product; the destination resolves through the ledger alone | `TransferService`, `JdbcTransferParticipants` | `sourceRefusalsAreOneAnswer`, `destinationRefusalsAreOneAnswer` |
| **Claim** | Scope `transfer.execute`, the fingerprint binding actor, both accounts, amount, currency, scale and reference | `IdempotentExecutor` (Phase 0's kernel, at the financial boundary) | `theIdempotencyContractHolds`, `tenConcurrentIdenticalKeysProduceOneTransfer` |
| **Judgement** | Self-transfer, then postability, then currency — the precedence forced by the aggregate's own pair rule; availability derived **inside** both participants' locks | `TransferExecution` | `theSiblingRefusalsEachCommitTheirReason`, `theTenWayDrainConservesValue` |
| **Financial transaction** | **One local transaction** on the caller's connection — no saga, nothing partial can exist | ADR-0043 | `anInjectedFailureAtTheLastWriteLeavesNothing` |
| **Journal entry** | One `POSTING` entry through `PostingService`, its own idempotency key `transfer:<id>`, its **reference carrying the transfer id** | `ledger` (the sole writer, `INV-LED-04`) | `theAcceptanceChainHolds` asserts the reference equals the transfer id |
| **Lines** | Debit the source wallet, credit the destination, balanced per currency, judged at COMMIT by `V004`'s deferred triggers for every writer | `JournalEntry` | `lineAmount` assertions; `INV-LED-01`'s register row |
| **Balances** | Source `"7.00"`, destination `"3.00"`, each read under its **owner's own token** — the transactional projection, never behind (ADR-0041) | `BalanceDisplay` | `theAcceptanceChainHolds` |
| **Statements** | Both parties' statements carry the entry id **and** the transfer id, so the chain walks in both directions with no timestamp join | Phase 3's derivation | `theAcceptanceChainHolds` |
| **Correction** | A reversal posts a **new** entry referencing the original; the original is byte-identical afterwards, asserted as PostgreSQL's own renderings | `TransferReversal` → `ReversalService` | `theReversalAcceptanceChainHolds` |

**Settlement and external reconciliation are not links in this chain and the plan says why**:
both legs are internal, so an internal transfer is **self-reconciling** — there is nothing
external to disagree with — and what remains is traceability, which is assessed at F8.

## Area 3 — Multi-instance correctness

*"Would this remain correct if 10 instances executed it concurrently?"* — **`PASS`**, answered
per contended decision with its arbiter and its counted race, never as an adjective.

| Contended decision | Arbiter | Raced by |
|---|---|---|
| The idempotency claim | Unique `(scope, key)`; the loser blocks on the index, then replays, or is honestly told in-progress | `tenConcurrentIdenticalKeysProduceOneTransfer` — one effect counted in four tables |
| Two accounts' availability | **Both** participants' rows `FOR UPDATE` in **one fixed order** | `theTenWayDrainConservesValue` (exactly 3 of 10 affordable); `valueIsConservedThroughoutSustainedBidirectionalMovement` |
| The beneficiary's one-live slot | Partial unique index over non-terminal states, savepoint converge | `tenConcurrentCreatesProduceOneLiveRow` — one row, nine converged |
| The reversal | Lock-then-look on the transfer row, conditional `UPDATE` as belt | `tenConcurrentReversalsProduceOneEntryAndOneMove`; the loser observed **Lock-waiting** in `pg_stat_activity` |
| The balance projection | The row's own lock, fixed order across accounts | Phase 3's, inherited unchanged |
| Event publication | The outbox's per-aggregate advisory lock | Phase 0's, inherited unchanged |

**The phase's defining multi-instance finding is `P4-TST-001`'s**, and it is the reason this
area passes rather than merely reads well: the execution locked only the **source** row while
the posting's foreign key takes `FOR KEY SHARE` on the destination regardless, so money moving
**both ways** between one pair was a lock cycle — 783 deadlocks against 203 domain outcomes.
Money was never at risk (a deadlocked transaction writes nothing), but `INV-CON-02` requires
the loser to fail with a **domain outcome**, and an infrastructure abort is not one. The
one-directional drain could not reach it, because a cycle needs two directions. Fixed by a
global total order over the locked rows; the storm went from 206s at 79% aborts to 4.5s at
none. **No single-instance assumption exists anywhere in the phase's code**: no `synchronized`,
no process-local lock, no static mutable state, no ambient scheduling — enforced by
`P0-TSK-041`'s four build rules over every module on `app`'s classpath, which `transfers`
joined in `P4-TSK-001`.

## Area 4 — Failure behaviour

`PHASE_4_PLAN.md` §14 names twelve scenarios. Criterion 4 requires each to have a test **or** a
documented accepted rationale; all twelve have one, and two are traced here as §4 requires.

| # | Scenario | Status |
|---|---|---|
| 1 | Client timeout then retry | `aTransferMovesMoneyOnceAndItsRetryReplays` |
| 2 | Crash mid-execution | `anInjectedFailureAtTheLastWriteLeavesNothing` — **traced below** |
| 3 | "Posting succeeds, transfer state fails" | **Unrepresentable** under ADR-0043; the probe in #2 is the proof, not a handler |
| 4 | Double submission from two nodes | `tenConcurrentIdenticalKeysProduceOneTransfer` |
| 5 | Insufficient funds | `insufficientFundsCommitsItsOutcome` |
| 6 | Self-transfer | `theSiblingRefusalsEachCommitTheirReason` |
| 7 | Destination closed mid-flight | `theSiblingRefusalsEachCommitTheirReason` — **traced below** |
| 8 | Currency mismatch | `theSiblingRefusalsEachCommitTheirReason`; `V005`'s composite FK for every writer |
| 9 | Same key, different payload | `theIdempotencyContractHolds` (409, nothing moved) |
| 10 | Reversal raced or repeated | `tenConcurrentReversalsProduceOneEntryAndOneMove`, `theLoserOfTheRaceIsRefusedWithNothingPosted` |
| 11 | Beneficiary removed racing a transfer | **Accepted race**, documented in §7 and bounded by destination postability |
| 12 | Broker down at commit | Phase 0's `INV-EVT-01`; the outbox holds the fact durably and the relay publishes when it can |

**Traced #2 — a crash at the last write.** A throwing outbox decorator fails the final write of
the execution transaction. Afterwards: no transfer row, no journal entry, no history row, no
audit record, **and no idempotency claim** — so the same key executes *afresh* rather than
replaying a failure that never committed. That last clause is the one that matters: a claim
surviving a rolled-back command would block the key for work that never happened. This is
ADR-0043's whole argument as a demonstration — there is no partial state to compensate,
because there is no second transaction in which to be partial.

**Traced #7 — the destination closed mid-flight.** The execution resolves the destination and
judges it postable; a concurrent close then commits. The posting's `V007` trigger read takes
`FOR KEY SHARE`, blocks on the closer's `FOR UPDATE`, resumes onto the committed `CLOSED` row
and refuses. The refusal arrives inside an aborted PostgreSQL transaction state — so the
execution wraps the posting in a **savepoint**, rolls back to it, and commits
`FAILED(DESTINATION_NOT_POSTABLE)`. The reason can be named with certainty because the *source*
was verified under our own lock. A domain outcome, committed and replayable, out of an
infrastructure-level refusal.

## Area 5 — Security and audit

Privileged actions of the phase, enumerated, each with its control and its **negative** test:

| Action | Control | Negative test |
|---|---|---|
| Reverse a transfer | `@RequiresPermission(TRANSFER_REVERSE)` on `LEDGER_OPERATOR`; reason required and bounded in three reconciled places | `theControlsHold` — the transfer's **own customer** gets 403 with every count zero and the status still `COMPLETED` |
| Create a beneficiary | Conditional `MULTI_FACTOR` — required **exactly of an identity that has a factor** (a static annotation would lock out every password-only customer) | `theStepUpGateHoldsAtCreation` — 403 `identity.AssuranceRequired` with **zero rows counted**, then 201 after the factor is proven over the real challenge endpoint |
| Initiate a transfer | **Deliberately unprivileged** — a customer moving their own money holds no permission (`P3-TSK-007`'s reasoning). The control is the ownership chain: `customer_id = ?` in the statement, so a closed customer's products are unreachable by construction | `aStrangersTransferIdIsOne404`, `theListShowsOnlyTheCallersTransfersNewestFirst`, and `OwnershipIsScopedTest`'s build-time classification |

**Every command is audited** — `transfers.TransferExecuted` for `COMPLETED` **and** `FAILED`,
because a committed refusal is an act; `transfers.TransferReversed` with its reason;
`BeneficiaryAdded`/`BeneficiaryRemoved` by the acting call only. **Refusals write nothing**,
structurally: the `ApiException` rolls the transaction back. **No amount reaches a message, a
log line or an event payload** (`INV-AUD-02`), asserted with planted needles; `failure_reason`
is classified `CONFIDENTIAL` because `INSUFFICIENT_FUNDS` is a fact about a person's finances,
and `reference` `RESTRICTED-PII` because it is free text a person writes.

**Four-eyes is deliberately not required on a reversal, and the reasoning is recorded**:
`INV-AUD-04` names manual adjustments, and a reversal is **bounded by the original** — it can
return money only whence it came. The proposal-row seam exists if a later phase decides
otherwise.

## Area 6 — Invariants, the register, and the demonstrations

The in-scope set is **whatever `FINANCIAL_INVARIANTS.md` marks `Phase: 4`**, read at review
time with the guard's own token-exact rule: **five**. All five have a register row; the guard
proves every named class and method exists on every build.

| Invariant | Row landed by | Enforcement |
|---|---|---|
| `INV-IDEM-01` (transfers) | `P4-TST-002` | `DB-CONSTRAINT` — unique `(scope, key)` at the financial boundary |
| `INV-CON-02` | `P4-TST-001` | `DB-CONSTRAINT` + `DOMAIN` — both rows locked in fixed order, availability in-lock |
| `INV-LIFE-01` | `P4-TST-002` | `DOMAIN` + `V002`'s `CHECK`s generated from the machine |
| `INV-LIFE-02` | `P4-TST-002` | `DOMAIN` + `V002`'s every-writer transition trigger |
| `INV-LIFE-04` | `P4-TST-002` | `DOMAIN` + trigger; terminals appear as **no** edge's source |

**83 mutations and probes across the phase's fourteen items, every one caught by the intended
assertion**, four cut on analysis and recorded, and **two survived mid-task — each strengthening
the suite rather than the code**: the rounding shape in `P4-TSK-008` (masked by a refusal until
the probe was re-aimed at an otherwise-valid pair) and the pre-lock availability derivation in
`P4-TST-001` (which corrected the storm's amounts so the boundary is contested continuously).
**Zero survived wrongly.**

**`INV-LIFE-04` carries a recorded reading rather than a silence.** `COMPLETED → REVERSED` is an
outgoing edge from a state a reader might call terminal; ADR-0044 decides `COMPLETED` is
**stable, not terminal**, chosen over storing *what happened to this transfer* in two places
free to disagree. The invariant's own clause — *subsequent economic changes are new operations*
— is honoured literally: the reversal is a new referencing entry, never an edit.

## Area 7 — Documentation accuracy

Hand-diffed plan against implementation, which is the method that has found the real defect in
every prior review and no guard covers. **Two findings, both in the record rather than the
code, both corrected here.**

**1. The component register had no `transfers.beneficiary` row.** `DISTRIBUTED_EXECUTION.md` §3
is an **enforced exemption set**, not a description: ADR-0024's build rules permit process-local
state only where this register names a component, so an absent row is a component whose next
author finds no precedent and no recorded reason. `transfers.beneficiary` has a partial unique
one-live index arbitrating a ten-way race, a conditional removal whose row count is the outcome,
and an every-writer freeze trigger — exactly the shape every sibling table has a row for. **The
fifth occurrence of the register-decay class, and the first inside a phase rather than at its
boundary**, which sharpens rather than softens the pattern: `P4-TSK-009` added its own row
precisely because the note says a task should, and `P4-TSK-006`'s table never got one. Row
landed, with the provenance in it.

**2. `P4-TSK-008`'s backlog block recorded Completion notes where every sibling records Gate
evidence, and its mutation sweep existed only in `CURRENT_STATE.md`.** The backlog is the
record; a reader comparing two documents should not be the mechanism by which a sweep is
discovered. The eight-mutation sweep, its one survivor and its one cut are restated in the
backlog from this document, with the provenance noted.

**What was checked and found accurate**, so the two findings are bounded rather than a sample:
the plan's §9 endpoint table against the published contract (7 for 7, on 5 paths); §10's three
events against what is emitted; §11's four audit actions against the registry and the
completeness guard; §15's four meters against the live registry; the module register's
`transfers` entry (responsibility, ownership, transaction, events, failure, security, seams);
the delivery plan's Phase 4 section, whose three transition-era corrections all carry their
provenance; `DECISIONS.md`, which indexes both of the phase's ADRs — an improvement on Phase 2,
where it had omitted an entire phase; and the backlog's Phase 4 header, which `P4-TSK-008`'s
gate had already corrected from a stale `READY`.

**The ADR index needed no repair, and that is a result rather than an absence.** Phases 2 and 3
each found the index's second copy of every ADR status stale by hand; `P4-TSK-002` made it a
build failure that names the ADR, and this gate's acceptance of ADR-0043 and ADR-0044 flipped
both copies with the guard reconciling them. The governance decay that recurred at three
consecutive gates did not recur at this one.

## Area 8 — Debt

Nothing new is created by this phase. What it records:

| Item | Owner |
|---|---|
| **No fleet-wide database or kafka count for Phase 4** — the standing skip; the suites pass per task and the hermetic tier was run fleet-wide | The owner's instruction; the Phase 4 → 5 transition may choose to run one |
| The limit and risk seams are stateless by contract, with no Phase 13 logic | Phase 13 — the contracts state where its authority must live: durable rows on the passed unit of work, judged in-lock |
| The step-up's **value** trigger | Phase 13 — a per-currency versioned policy artefact with nothing to calibrate it; the structural trigger ships and the value trigger is a seam on the risk port |
| The stuck-transfer detector | The first asynchronous execution path (Phase 5) — under ADR-0043 it has no subject |
| Four-eyes on reversal | Not required (`INV-AUD-04` names manual adjustments); the proposal-row seam exists if a later phase decides otherwise |
| Beneficiary removal racing a transfer | **Accepted race**, documented in plan §7 and bounded by destination postability |

**None of it is financial-correctness debt**, which `EXECUTION_PROTOCOL.md` never permits.

---

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | **PASS** — all seven endpoints exercisable end to end over real HTTP, the chain walked by identifier from both parties' sides |
| 2 | Architectural boundaries respected | **PASS** — `transfers → ledger` declared, `transfers → accounts` **refused** through a port `app` implements; a planted reverse edge fails Gradle configuration as a cycle; all six sibling isolation tests forbid `transfers` |
| 3 | Required invariants tested | **PASS** — five in scope, five register rows, every named method proven to exist by the guard on every build |
| 4 | Failure cases handled | **PASS** — all twelve of plan §14, two traced in area 4 |
| 5 | Security requirements implemented | **PASS** — two privileged actions, each negatively tested with nothing written; **29** columns classified at their ceiling across the phase's three tables (22 + 7, counted); no secret in source |
| 6 | Observability exists | **PASS** — four meters eager from a fresh instance, latency from the injected clock, a dashboard row resolving against a live scrape, correlation on every span and record |
| 7 | Integration tests pass | **PASS, with the deviation recorded** — hermetic fleet-wide (1157 tests, 0 failures); database and kafka per task, by the owner's standing skip |
| 8 | Documentation reflects reality | **PASS** — two drifts found by hand-diff and corrected in this review |
| 9 | `CURRENT_STATE.md` updated | **PASS** — status, capability and next phase all current as of this review |
| 10 | Relevant ADRs exist and are `Accepted` | **PASS** — ADR-0043 and ADR-0044, both copies, build-reconciled |
| 11 | No unresolved critical issues | **PASS** — no `critical` or `high` item; the blockers section is empty |
| 12 | Formal phase review conducted | **PASS** — this record |

## The financial supplement F1–F8 — re-assessed at the gate

| # | Criterion | Verdict |
|---|---|---|
| F1 | Trial balance zero per currency | **Met** — `valueIsConservedThroughoutSustainedBidirectionalMovement` sweeps the **global** trial balance every round under ten instances moving money both ways, with a `currenciesVerified >= 1` guard so a sweep that saw nothing cannot pass |
| F2 | Every balance reproducible from zero | **Met** — every mid-storm projection verdict `CLEAN`/`IN_FLIGHT`, never `DRIFTING`; Phase 3's derivation is the definition and Phase 4 adds no second one |
| F3 | Idempotency at the financial boundary, tested | **Met** — the claim is the Phase 0 kernel's, at the command and never an HTTP filter (ADR-0004); sequential replay, concurrent duplicate and the distinct conflict all tested |
| F4 | Reversal implemented and tested; no path mutates history | **Met** — a new referencing entry, the original byte-identical as PostgreSQL's own renderings, `UPDATE`/`DELETE` denied per column on both journal tables |
| F5 | Duplicate external delivery produces no second effect | **Met, with its Phase-4 reading stated** — no external event produces a financial effect in this phase; the mechanism that will bind is the Phase 0/2-proven inbox, and the phase's own duplicate vector (a retried command) is F3's |
| F6 | Concurrency tests for every contended financial resource | **Met** — the six-row table in area 3, each with a counted race |
| F7 | No floating point in a monetary path | **Met** — statically, over every module on `app`'s classpath, `transfers` included since `P4-TSK-001`; the planted-`double` probe named the module |
| F8 | Reconciliation implemented or deferred with an owner | **Met** — internal transfers are self-reconciling; the obligation that remains is traceability, and every link is a **stored identifier**: the transfer row holds `journal_entry_id` (`UNIQUE` — one movement, one entry), the entry's reference holds the transfer id, the reversal holds both. An investigator walks it in either direction with **no timestamp join**. External reconciliation is Phase 8's |

## The Phase 4-specific criteria — sixteen

Read from `PHASE_GATES.md` §5 **at review time**: six original plus ten added by the Phase 3 → 4
transition, which extended the list because the original predates ADR-0043/0044 and said nothing
measurable about conservation, the lock, reversal, beneficiaries, authority, observability or
the register.

| # | Criterion | Verdict |
|---|---|---|
| 1 | The machine rejects every invalid transition | **PASS** — the cross-product sweep derived from `values()`, both terminals swept separately, the machine pinned exactly |
| 2 | Duplicate key, one effect, **under concurrent submission** | **PASS** — `tenConcurrentIdenticalKeysProduceOneTransfer`; the gap this criterion names was found and closed by `P4-TST-002` |
| 3 | Same key, different payload, distinct error | **PASS** — `IdempotencyConflictException` at the command, `409 api.Conflict` at HTTP |
| 4 | Insufficient funds is a domain outcome | **PASS** — a committed `FAILED(INSUFFICIENT_FUNDS)` whose retry replays the refusal |
| 5 | Posting and transition atomic | **PASS** — ADR-0043's one transaction; the compensating branch is decided, not implemented, because no partial state exists |
| 6 | Seams exist, documented defaults, no Phase 13 logic | **PASS** — and mechanised: the default holds no field, each port declares one method, the verdict is exactly two values |
| 7 | Ten instances draining one account conserve value | **PASS** — exactly 3 of 10, source never negative, the pair summing to the funded total, counted in the tables |
| 8 | The injected-failure atomicity probe | **PASS** — nothing exists afterwards, the claim included |
| 9 | Availability derived **inside** the lock | **PASS** — the moved-outside mutation performed and caught (`expected: 3 but was: 10`) |
| 10 | Reversal: referencing entry, same transaction, original byte-identical, second refused at both layers, named permission with a negative test and a required reason | **PASS** |
| 11 | Beneficiary step-up when enrolled, negatively tested; removed beneficiary refuses new transfers while its row survives | **PASS** |
| 12 | The asynchronous-outcome contract shape | **PASS** — status on the `201`, plus a status query endpoint; a `FAILED` outcome is never an HTTP error |
| 13 | Seams are **required parameters** — a caller that skips them does not compile | **PASS** — demonstrated at the composition root and restored |
| 14 | Meters published by a freshly started instance | **PASS** — the pinned guard boots the plain context and runs no flow |
| 15 | The chain traceable identifier-to-identifier | **PASS** — transfer ↔ entry ↔ reversal, no timestamp join |
| 16 | Every `Phase: 4` invariant has a register row, **read from the catalogue** | **PASS** — five of five, the transfers-context `INV-IDEM-01` row included |

## ADR-0043 and ADR-0044: accepted

Both are implemented, tested and load-bearing, so both move to `Accepted` on the standing
precedent that criterion 10 is a **precondition** of the gate rather than a reward for passing
it. Holding a decision at `Proposed` because some other criterion was open would be theatre.

**ADR-0043 — the transfer and its posting commit in one local transaction, and no internal saga
exists.** Its claim is falsifiable and was falsified in the right direction: the injected-failure
probe leaves nothing, so the state the rejected designs exist to repair cannot occur. The
boundary at which the answer changes is named in the ADR itself — an outcome a third party
decides is a *payment*, and Phase 5's lifecycle owns that shape.

**ADR-0044 — the lifecycle's four states, each earned by a producer.** Every state has a
producer, `PROCESSING` and `CANCELLED` stayed out, `TransferInitiated` was not published, and
`COMPLETED` is stable-not-terminal with the reading recorded where `INV-LIFE-04` meets it.

## What the phase produced

Counted from the repository, never recalled:

| | |
|---|---|
| Modules | 1 new (`transfers`), with the build-graph asymmetry that makes the phase's top risk structurally unreachable |
| Tables | 3, in 4 migrations |
| Endpoints | 7 operations on 5 paths (platform: 39 paths, 47 operations) |
| Aggregates | 2 — `Transfer`, `Beneficiary` |
| Audit actions | 4, all emitted (platform: 46) |
| Events | 3 terminal facts |
| Error codes | 3 (platform: 35) |
| Permissions | 1, on an existing role — a permission is never a column (ADR-0031) |
| Meters | 4, plus a dashboard row |
| ADRs | 2, `Accepted` |
| Invariants | 0 new — the catalogue stays at **82**; 5 in scope, 5 register rows |
| Backlog | 14 of 14, across 8 milestones, all `CLOSED` |
| Mutations | **83**, every one caught by the intended assertion; 4 cut on analysis; 2 survived mid-task and each improved a test; **0 survived wrongly** |
| Tests | **1157 hermetic, 0 failures** after the flip; database and kafka per task, by the standing skip |

**What a customer can now do**: hold accounts, save a destination behind a second factor, move
money between two platform accounts idempotently, see the judgement immediately and query it
later, read both statements and walk from a statement line to the transfer and back — and have
an operator correct a mistake with a reasoned, audited reversal that leaves the original
history byte-identical.

## What happens next

The **Phase 4 → Phase 5 transition**, which is its own act and not a backlog task. Phase 5 is
payment infrastructure: the first money movement whose outcome is decided by an **unreliable
third party**, which is the one variable this phase deliberately held fixed. Its open decisions
are already named in `CURRENT_STATE.md` §Unresolved Architectural Questions — the accounting
treatment of authorization versus capture (6, High), the first rail and its finality semantics
(9), and the fee model (8, High) one phase later — and ADR-0043's own boundary sentence is the
warning the transition must read first: **this phase's atomicity answer must not be inherited by
analogy** by a phase whose outcome arrives from outside the transaction.
