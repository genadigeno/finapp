# Phase 9 → Phase 10 Transition

**Conducted:** 2026-10-07
**Parts:** integrated Phase 9 audit · financial correctness · FX-specific provenance · multi-instance
audit · architecture and security · testing · blocking issues and repair · Phase 9 completion ·
Phase 10 initialisation
**Constraint:** this transition ran under the rule that **CRITICAL and IMPORTANT Phase 9 defects are
resolved before the transition, never deferred as debt**, and under the owner's further rule that
financial, concurrency, security and distributed-system defects are not hidden as future debt. So,
like the three transitions before it, it wrote application code: the repairs in §7, each tested,
each broken on purpose to prove its test, each restored byte-identical (sha256-verified) and
recorded. It wrote no Phase 10 code — Phase 10 exists below only as documents.

---

## Verdict

| Part | Outcome |
|---|---|
| 1. Integrated Phase 9 audit | **`PASS` after repair.** Seven parallel read-only audits (FX financial correctness, cross-border financial correctness, multi-instance, settlement and reconciliation, security and audit, architecture and events, test adequacy), each told to assume defects and to refute its own findings before filing them: **two CRITICAL** (one found independently by two auditors, one upgraded from IMPORTANT on verification), **twenty-three IMPORTANT**, and some forty MINOR. Every CRITICAL and IMPORTANT repaired; the MINOR correctness, concurrency and security ones repaired; the rest recorded with owners (`CURRENT_STATE.md` debt) |
| 2. Financial correctness | **`FAIL` as found** (a concluded credit re-sendable to a provider that never saw it — the beneficiary paid, the customer's hold released; a parked return approved as an ordinary transfer and then applied again from the inquiry — the customer credited twice; a cover's computed leg booked from the provider unchecked). **`PASS` after repair** |
| 3. FX provenance | **`PASS`** — every executed FX transaction identifies its pair, both amounts, the executed (customer) rate, the provider and its rate, the reference snapshot, the pricing policy version, the timestamps, the spread, markup and margin parts, the roundings and the residual (trade → quote); fees sit on the cross-border offer. **Since this transition** the cover execution also records the computed leg it was quoted at and whether the provider deviated from it. Stale and expired quotes cannot execute: both acceptance paths are conditional on `expires_at > statement_timestamp()`, the trigger edge agrees, and acceptance on an instance ±5 s skewed is now proven |
| 4. *"Would Phase 9 remain financially correct if 10 instances executed relevant operations concurrently?"* | **`FAIL` as found** (a takeover re-sending a concluded credit; two reachable deadlocks — the completion against the cover applier, the exit review's own pre-lock against the wallet deciders; a sweep page held for ever by the oldest awaiting credits). **`PASS` after repair**, every repaired arbiter proven by a deterministic test and broken on purpose |
| 5. Architecture and security | **`FAIL` as found** (storage failures logging the refused row, bank identifiers included; an exponent amount exhausting CPU and memory at every money door; a refused quote committing a kyc re-screen; a screening reviewable by its own requester; trade-reversal reasons unscreened and its decision acting on another trade's route; a published event's amounts without currencies). **`PASS` after repair**; ownership, provider isolation and the event envelope held throughout |
| 6. Testing | **The fleet-wide hermetic tier green at 2563 tests across 423 suites and 18 modules**, beside the architecture tier (169 across 31), the slice tier (109 across 19) and the database tier over every suite the repairs reach - 598 tests across 72 suites and 19 in their own containers, the storm among them - each from a fresh run; the database and kafka tiers ran module-wide and per-suite, never fleet-wide, on the owner's standing instruction - the recorded deviation |
| **Phase 9** | **`COMPLETE`**, confirmed after repair |
| **Phase 10** | **Entry gate: all twelve criteria hold → `READY`**; first task `P10-TSK-001` `READY`, not started |

Phase 9 was ruled `COMPLETE` by its exit review the same day
([`PHASE_9_REVIEW.md`](PHASE_9_REVIEW.md), `P9-DOC-001`). This is the second, independent pass the
gate model requires — and again **the review had ruled the phase complete while the phase was not
correct**: both CRITICAL defects sat on paths every Phase 9 test passed over, and one IMPORTANT
deadlock was introduced by the exit review's own repair.

---

## 1. Integrated Phase 9 audit

| Area | Verdict | Re-checked after repair |
|---|---|---|
| FX conversion, quote and cover | `PASS` after repair | The cover judged against its firm quote (fx `V010`): the requote's stated counter stored before any send, `computed_deviation` and `executed_rate_coherent` on every execution, both alerting; a cover born only from its quote's plan; a superseded attempt typed `TERMINAL` |
| Cross-border payment and outbound credit | `PASS` after repair | No re-send of a concluded credit (payments `V029`); the sweep claims and rotates (`V030`); a "too late" recall makes the credit `RECEIVED`; a delivery after a return is history; the completion's lock order |
| Returns and resolution | `PASS` after repair | A parked return found by either reference — one credit, one return; the pre-lock's key share before rows |
| Settlement and reconciliation | `PASS` after repair | A first rule-set version dates every kind its source settles; the missing-version precondition ruled (alerted, converging) |
| Security and audit | `PASS` after repair | No driver cause on any fx or crossborder storage failure (two build rules); `DecimalText` at every money door; savepoints in the quote desk; the self-review refusal at both ranks; screened reversal reasons |
| Architecture and events | `PASS` after repair | The event's currencies; routing on the database clock; the remaining causation and envelope-documentation drift corrected in text |
| Documentation | `PASS` | `PHASE_9_PLAN.md` §21, six ADR amendments, the lifecycle document, `DISTRIBUTED_EXECUTION.md` §3, `EVENT_ARCHITECTURE.md`, `DATA_CLASSIFICATION.md` (six new columns) |

## 2. Financial correctness

The money moves Phase 9 makes — conversion (`FX_POSITION`, spread, residual), cover and realised
result, unwind, operator reversal, cross-border completion, failure, recall, return and its fee
refund — were each walked from posting to cash. Balanced per currency in every entry; no floating
point; deterministic rounding proven by the battery; history immutable. **Found and repaired:** the
re-send after `NEVER_RECEIVED` (money paid out, hold released); the double credit through a parked
return; the cover's unchecked computed leg (a silent realised loss). **The distinctions held:** an FX
quote is not its execution (the trade is born once from the accepted quote), an execution is not
the payment (the outbound credit is its own aggregate), a payment is not its settlement (the
corridor's report and the bank's statement discharge the clearing), and settlement is not
reconciliation (expectations, matching and breaks are reconciliation's alone).

## 3. FX-specific audit

See the verdict's part 3. The plan-replay proof (`FxPlanVerification`) and the books proof stayed
clean throughout the repairs; the cover's new verdicts are recorded beside, never instead of, what
the provider executed.

## 4. Multi-instance audit

Every contention in `PHASE_9_PLAN.md` §7 re-checked against its arbiter. The repairs each answer
one interleaving the counted races had not exercised: a takeover after a sweep's conclusion
(`aConcludedCreditIsNeverResent`), the completion meeting the cover applier
(`theCompletionLocksTheQuoteBeforeTheFxPosition`), the pre-lock meeting a wallet decider
(`thePreLockSharesKeysBeforeRows`), a page of awaiting credits that never moves
(`creditsAwaitingTheirOutcomeRotate`), an inconsistent provider after "too late"
(`aRecallTooLateMakesTheCreditReceived`), and the applier's full state-by-answer matrix
(`theApplierMatrix`). No correctness rests on a process, a JVM-local lock or an instance's clock.

## 5. Architecture and security

Ownership held: crossborder decides, fx prices and books, payments executes, the ledger owns money,
`app` composes; no module writes another's state, and no provider vocabulary reaches a domain
class. The security repairs are listed in §7; each has its own negative test.

## 6. Testing

Counted from fresh runs on the repaired code. **Hermetic:** 2563 tests across 423 suites and 18 modules; **architecture:** 169 across 31; **slice:** 109 across 19. The three failures the first full run showed were all in this transition's own new or corrected guards (the decimal guard meeting the chargeback report's computed ratio; the counterparty-clearing rule meeting a mapping that named two counterparty purposes) - fixed, and re-run green with the new tests (38 across 12). **Database:** the app suites the repairs reach, the fx, crossborder, reconciliation, ledger and payments module tiers whole, and the classification guard - **598 tests across 72 suites**, and **19 in their own containers** (the FX and cross-border storm and the payout return suite) - with one failure: a Phase 7 routing test (`PaymentEndpointDatabaseTest`) red unseen since `P9-TSK-019` seeded routing version 5, corrected to read the version its decision pinned and re-run green (20 across 2). The fleet-wide database and kafka tiers were not run, on the owner's standing instruction; the cost of that skip is stated again below.

The test audit's eight gaps — `NothingSent` on a first send against a re-send, the corridor
adapter's timeout modes, the corridor kill switch's effect, a return line repeated within and
across files, the applier's edge matrix, acceptance and the expiry sweep on a skewed instance, the
FX door's dedupe count, and Tx1's failure injection — each now has a test. It also confirmed every
`INV-FX-*`/`INV-XB-*` register row cites a test that exists. Three guards had been red unseen since
the tasks that broke them, because the database tier was skipped: `FxReasonScreenDatabaseTest` and
`FxMigrationTest` (since `P9-TSK-025`) and `CrossborderMigrationTest` (since `P9-TSK-024`) — each
corrected; the skip's cost, stated again.

## 7. Blocking issues and repair

| Severity | Finding | Repair | Proven by (broken on purpose) |
|---|---|---|---|
| **CRITICAL** | A takeover under the same key after `FAILED(NEVER_RECEIVED)` re-sent `E`: the provider paid, the hold was released, the cover unwound | The renewal requires `DISPATCHED`/`UNKNOWN` and no recall — store and payments `V029`, every writer; the desk sends nothing otherwise | `OutboundCreditResolutionDatabaseTest#aConcludedCreditIsNeverResent` — two probes, one per rank |
| **CRITICAL** | A parked return whose `E` named nothing ours was approved as an ordinary transfer; the inquiry then applied the same return — a double credit | The resolution finds the credit by the provider reference through the claim too | `CrossBorderReturnDatabaseTest#aParkedReturnFoundByItsProviderReferenceIsTheCreditsReturn` |
| IMPORTANT | The outbound sweep's page starved by awaiting credits | One claiming statement, rotated by `last_inquired_at` (payments `V030`) | `#creditsAwaitingTheirOutcomeRotate` |
| IMPORTANT | A delivery after a return rolled every inquiry back and kept the credit due for ever | Delivery after return is history; returned credits are never delivery polls | `#aDeliveryReportedAfterTheReturnIsHistoryOnly`, `#aReturnedCreditIsNeverADeliveryPoll` |
| IMPORTANT | A "too late" recall left the credit concludable `NEVER_RECEIVED` | "Too late" moves an unconcluded credit `RECEIVED` | `#aRecallTooLateMakesTheCreditReceived` |
| IMPORTANT | Completion vs cover applier deadlock | The completion locks payment, then quote, first | `#theCompletionLocksTheQuoteBeforeTheFxPosition` |
| IMPORTANT | The exit review's pre-lock vs the wallet deciders | `lockBalancesInOrder` shares the accounts' keys first | `#thePreLockSharesKeysBeforeRows` |
| IMPORTANT | A first rule-set version lacking a lag rolled back every money movement of that kind | `SettledExpectationKinds` at the first-version door | `RuleSetAdministrationDatabaseTest#aFirstVersionMustDateEveryKindItsSourceSettles` |
| IMPORTANT | The cover's computed leg and rate booked unchecked | fx `V010`'s verdicts and alerts | `CoverLinesTest`, `FxCoverDatabaseTest` (attempt 1 and after a requote) |
| IMPORTANT | A superseded attempt executed late typed `COMPLETED` | Typed `TERMINAL` | `JdbcInternalReferenceLookupTest#aSupersededCoverAttemptIsTerminal` |
| IMPORTANT | A reversal decision acted on another trade's route | The trade id filters the reversal | `FxTradeReversalRaceDatabaseTest#aDecisionOnAnotherTradesRouteIsRefused` |
| IMPORTANT | Reversal reasons unscreened | Domain and fx `V011` | `#reversalReasonsHoldNoInstrumentShape` (two probes) |
| IMPORTANT | Storage failures carried the driver's exception — the refused row's `DETAIL`, bank identifiers included, reached the log | No cause, in fx and crossborder; two build rules; shaped provider references refused as provider faults | `FxExceptionsCarryNoDriverCauseTest`, `StorageFailuresCarryNoRowTest`, `CrossBorderBeneficiaryDatabaseTest#aShapedProviderReferenceIsRefusedAsAProviderFault` |
| IMPORTANT | An exponent amount (`1E+500000000`) exhausted CPU and memory, at the FX and cross-border quote doors and, by the same pattern, the payment, withdrawal, transfer, payout, adjustment and rule-set doors | `DecimalText` shapes every request decimal first | `CrossBorderOfferDatabaseTest`, `FxQuoteEndpointDatabaseTest` (200 ms bounds), `RequestDecimalsAreBoundedTest` |
| IMPORTANT | A refused cross-border quote committed a kyc re-screen and an offer request (and, in Tx2, a quote) | Savepoints in both transactions | `CrossBorderOfferDatabaseTest#aRefusedQuoteCommitsOnlyItsRefusal` |
| IMPORTANT | A reviewer could release their own beneficiary's screening | `requested_by`, refused at the domain and kyc `V010`, each alone | `CounterpartyScreeningDatabaseTest` (two probes), `CrossBorderBeneficiaryDatabaseTest#aReviewerNeverReleasesTheirOwnBeneficiary` |
| IMPORTANT | `CrossBorderPaymentInitiated` carried amounts without currencies | Both currencies, event version 1 | `CrossBorderPaymentDatabaseTest#anOfferIsAuthorizedAndDispatched` |
| IMPORTANT | Eight test gaps (§6) | Tests | §6 |

**Probes:** **thirty-one, all caught** - ten for the repairs made here (two of whose first tests SURVIVED - the delivery poll swept after the delivery was known; the completion's lock probed a currency the payment never touched - each recorded as no verdict, re-aimed and caught), nine by each of the two repair branches, and three for the follow-up repairs (the closed approval body, the bounded parser, the settled kinds); every restore byte-identical (sha256-verified). `MUTATION_TESTING.md` §2 +26 rows

## 8. Phase 9 completion

Phase 9 is **`COMPLETE`**, confirmed after repair. The exit review's verdict stands as amended: its
twenty-seven phase-specific criteria hold on the repaired code, its universal criterion 7 keeps its
recorded deviation (the fleet-wide database and kafka tiers are the owner's to run), and the debt
rows the transition recorded are owned by Phase 15.

## 9. Phase 10 initialisation

**Objective.** Make credit decisions the platform can defend years later: a decision is a recorded,
immutable fact, reproducible from a frozen snapshot of the data it used, evaluated by a versioned
policy and a versioned model through a deterministic engine, carrying the ordered reason codes
that explain it. Credit moves no money; Phase 11 (lending) begins where a decision is consumed.
Planned in [`PHASE_10_PLAN.md`](../PHASE_10_PLAN.md); decided in ADR-0084…0089 (`Proposed`); the
machines in [`CREDIT_DECISIONING_LIFECYCLES.md`](../../domain/CREDIT_DECISIONING_LIFECYCLES.md); the
invariants `INV-CRD-01`…`12` (**128 invariants** platform-wide); the exit criteria in
`PHASE_GATES.md` §5 Phase 10, extended by this transition; twenty-four backlog items across eight
milestones (M10.1–M10.8). The design was drafted from the plan by four writers in parallel,
reconciled against their reported inconsistencies by a consistency pass, and the owner's decisions
on every open point applied.

**Entry gate** (`PHASE_GATES.md` §2):

| # | Criterion | Holds |
|---|---|---|
| 1 | Hard dependency phases `COMPLETE` | Yes — Phases 1 and 2 (identity, consent), and every phase through 9 (confirmed here, §8) |
| 2 | `DELIVERY_PLAN.md` §Phase 10 current and specific | Yes — the Phase 10 addendum reconciles the original section with the plan (events renamed, the risk score moved to Phase 13, affordability, exposure and underwriting added) |
| 3 | Bounded contexts and aggregates identified | Yes — `PHASE_10_PLAN.md` §3–§4: one new module, `credit`; consent and identity changed |
| 4 | Invariants identified by ID | Yes — `INV-CRD-01`…`12`, eight new, read from the catalogue; restated ones named in §6 |
| 5 | Lifecycles drafted | Yes — decision request, data request, underwriting case, policy and scorecard versions, and the born-once facts |
| 6 | Transaction and consistency boundaries stated | Yes — §7's contention table and lock order; each task |
| 7 | Idempotency stated for every money-moving command | Yes — credit moves no money; every state-changing command's idempotency is stated (§4, each task) |
| 8 | External dependencies and failure modes listed | Yes — §14's thirty-one scenarios |
| 9 | Security, audit and reconciliation implications stated | Yes — §11 (reconciliation: none; replay is the analogous control, §12.8) |
| 10 | Backlog at task granularity with acceptance criteria | Yes — `P10-TSK-001`…`021`, `P10-TST-001`…`002`, `P10-DOC-001`, every field |
| 11 | Required decisions have ADRs at least `Proposed` | Yes — ADR-0084…0089 |
| 12 | `CURRENT_STATE.md` names the phase active | Yes — Phase 10 `READY`, `P10-TSK-001` the current task |

**What Phase 9 handed over, disposed:** the fleet battery — its skip recorded again, with this
phase's three guards found red as its cost; the debt rows above — Phase 15; the risk score's owner —
settled as `risk` (Phase 13), consumed through `CreditRiskSignal`.

**Exit criteria:** `PHASE_GATES.md` §5 Phase 10 — the five original criteria kept and made
measurable, nineteen added, each naming its owning tasks.

**First task: `P10-TSK-001`** — the credit module boundary and floors — `READY`, not started.
