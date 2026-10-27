# Phase 13 Exit Review — Risk, Fraud and AML

**Conducted:** 2026-10-27 (`P13-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit criteria and the financial
supplement), §5 (Phase 13-specific criteria — the six originals made measurable and the per-area blocks, read from the gate
at review time, as extended by the Phase 12 → 13 transition)
**Phase objective under review:** every money movement and every account **judged** before it happens and **watched** after it
happens, by a platform capability that owns the judgement and none of the money — the seams the platform carried since Phase 4
implemented and two added (the wallet withdrawal, the checkout payment confirmation); behind them one channel-neutral `risk`
module: versioned policies of rules as data, a deterministic engine, durable velocity windows written in the caller's
transaction, one sealed assessment per judged command, a fail-safe that is a recorded decision, overrides under four eyes, a
case for every judgement a person must make; after the money moves, a leaderless sweep over the ledger's journal into monitored
transactions, monitoring rules, alerts born once, cases, downstream actions through the owning domains, the regulatory report as
a distinct artefact, rescreening on list updates, the proofs, the explanation and replay doors, meters, alerts and the runbook.

| | Outcome |
|---|---|
| The owner-authorised database pass (C5) | **Run first, on the final tree** — 102 suites, 895 tests on its first run, **23 failures in 10 suites, every one a test defect or a shared-container interference; no production defect, no migration changed or added**. Repaired test-only (the storm branch's three repair commits taken first), re-run fresh: **103 suites, 897 tests, 0 failures** |
| Review areas (8) | **8 `PASS`**, every one only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **10 `PASS`, criterion 1 `PASS` with `P13-TST-001` postponed by the owner (PI-004), criterion 7 `PARTIAL`** — the standing C5 deviation, the database pass run, the storm postponed; recorded, not passed |
| Financial supplement (F1–F8) | Phase 13 posts nothing: **F3, F5, F7 `Met`; F1, F2, F4, F8 `Met` in the owning domains' form; F6 `Met` for the named ten-way suites, its storm half deferred (PI-004)** |
| Phase 13-specific criteria | **The six originals and the fifteen per-area blocks hold, read clause by clause** (269 sub-clauses across five audits: none `DOES-NOT-HOLD` after the corrections; 54 hold in a form their words did not anticipate, recorded under the gate; **17 rest on the postponed storm**, each with the evidence that exists named); the production reality recorded as an accepted limitation |
| *"Correct with 10 concurrent instances?"* | **`PASS` on arbitration, the counted storm half deferred** — every arbiter is in the database and every deciding instant is the database's; the coded lock order read for every transaction, acyclic; every §7.4 row's named test exists and races (12 of 16 with real concurrency); **every born-once arbiter of the schema refused alone with triggers off** (`RiskBornOnceArbitersDatabaseTest`: 18 refused alone, 3 foreign-key targets) and **every four-eyes `CHECK` refused alone by name**; the database suites green. **Not counted:** the storm's census at every round, the named lock waits, the latency budget (PI-004), and two owner questions on contention (items 77, 95) |
| **Verdict** | **Phase 13 `COMPLETE` (2026-10-27), pending the Phase 13 → 14 transition gate and the owner's ratification** — with `P13-TST-001` postponed by the owner (PI-004): its evidence deferred, named, and not claimed |

Conducted in the `P2-DOC-001` order: the owner-authorised database pass first → assess → land the corrections → re-run the
evidence → **flip the status, which is the guarded act** → finalise with counted numbers.

**The assessment was ten independent read-only audits, then five document correctors.** A1 and A2 read the gate's clauses
one by one against their named tests (224 sub-clauses); A3 hunted financial defects in a phase that moves no money; A4 the
multi-instance design (the §7.4 table, the coded lock order, every task's ten-instance answer); A5 architecture, reuse and
ADR-0107…0112 decision by decision; A6 security and audit (40 routes, seven four-eyes acts, the self-dealing doors); A7
privacy, tipping-off and the observability surface; A8 test meaningfulness and the mutation register; A9 the owner list
(items 1–74, C1–C10, OQ-1…10), the debt rows and the plan's errata; A10 the domain and operations documents. Each finding was
proven from the code with a file and a line, and each was re-verified before it was acted on. Five correctors then made
twenty-five documents true against the code; none of them decided an owner question.

**No CRITICAL defect was found, and no production defect.** Risk posts no money, holds none and writes no other domain's row;
every seam runs before any posting; a refusal strands nothing; the hold, capture-hold and action machines leave exactly one
outcome; every monetary figure is integer minor units with its currency; a credit decision sealed under seam version 1 still
replays `IDENTICAL`. What the audits found was **evidence weaker than its claim** (a four-eyes probe that passed with its
`CHECK` dropped; a race test that passed whether or not the unique decided; seventeen born-once arbiters with no probe alone;
the filing's step-up with no test at all; a phase flip that would have turned the register red), **documents that described
the plan rather than the code** (no document was wrong about money; many were wrong about which transaction takes which row,
what is built and what the storm proved), and **design questions the as-built code answers in a way the owner has not ratified**
— recorded as owner items 75–95, three of them security-relevant (75, 77, 78) and one a fail-open self-dealing path (79).

---

## What the review found, and what it did about it

**Corrected — tests (no production code changed):**

- **`regulatory_report_four_eyes` was not proven alone** (A8-3). The raw self-approval ran on an autocommit connection, so the
  implicit commit fired `V016`'s deferred history trigger, which also answers 23514: the test passed with the `CHECK` dropped.
  Now in its own transaction, the refusal asserted by the constraint's name. The same name assertion added to the case's, the
  policy's and the screening list's four-eyes probes (A2-5, A6-9): **all seven four-eyes `CHECK`s are now refused alone by
  name** (`RiskPolicyDatabaseTest#theProposerCannotActivate`, `DecisionOverrideDatabaseTest#theProposerCannotApprove`,
  `AlertDatabaseTest#aSuppressionNeedsASecondPerson`, `CaseDatabaseTest#theProposerCannotApprove`,
  `RiskActionDatabaseTest#theProposerCannotApprove`, `RegulatoryReportDatabaseTest#theApproverIsNotTheDrafter`,
  `RescreeningDatabaseTest#theRanksBeneathTheDomain`).
- **Seventeen of the schema's twenty-one uniques had no probe alone** (A2-1, A4-3, A8-2). New
  **`RiskBornOnceArbitersDatabaseTest`** (the Phase 11 and 12 reviews' `BornOnceArbiters` applied to `risk`): every unique read
  from the catalogue, a seeded source row in each probe's own rolled-back savepoint, every trigger and foreign key suspended and
  every `CHECK` dropped — **21 uniques pinned, 18 refused alone, 3 foreign-key targets**. payments `V034`'s
  `payment_capture_hold_one_per_attempt` and kyc `V011`'s re-made `verification_check_one_in_flight_per_type` gained raw
  probes by name (`PaymentRiskMigrationDatabaseTest#aHoldIsBornOpenAndResolvesOnce`,
  `RescreeningDatabaseTest#theRanksBeneathTheDomain`).
- **`UNIQUE (operation, command_ref)` was never shown to decide** (A1-4, A8-4): `#anUnserialisedDuplicateLosesAtTheSeal`
  passed when the two duplicates merely serialised. Now a holder takes the `ACTIVE` version's row `FOR UPDATE`, both duplicates
  wait at the pin with their look-ups done, and exactly one `RaceLost` is asserted.
- **`risk_assessment_pinned_unless_unpinnable` was never proven alone** (A1-3): new
  `RiskEvaluationDatabaseTest#theVersionIsNamedUnlessNoneCouldBePinned` — a raw copy with no version and a `TIMEOUT` cause
  refused by name; the same copy with `PIN_UNREADABLE` admitted.
- **The filing's `MULTI_FACTOR` had no evidence** (A6-1: deleting the annotation passed every test). New hermetic
  `RiskStepUpRoutesTest` pins the step-up on the report's approval and filing, the policy's activation and the list's
  activation, and its absence on the single-holder acts and the reads.
- **The explanation walk** (A1-6, the gate's "the exit review's walk"): performed in-suite — a `HELD` withdrawal
  (`WithdrawalRiskSeamDatabaseTest#aHoldCommitsHeldWithTheHoldKept`), a refused transfer
  (`TransferRiskSeamDatabaseTest#aRefusedTransferCountsNothingAndCommitsRiskRefused`) and a `REVIEW` case
  (`RiskEvaluationDatabaseTest#aReviewDecisionOpensItsCaseInTheMoneyTransaction`) each walked by id from the operation's
  outcome to its assessment, its fired hits and its ordered reasons. No operator route maps a command to its assessment — the
  walk's first step is a query (owner item 88's neighbour; recorded).
- **A repair that weakened an assertion** (A8-7): the storm branch's `TRUNCATE … CASCADE` let any truncated table's trigger
  answer; the signal's and the alert's own truncate triggers are now pinned present. **A catch-all** (A8-8): scenario 10's ten
  dispatchers swallowed every `RuntimeException`; now only the in-flight key's.
- **The database pass's repairs** — the section below.

**Corrected — documents** (the five correctors' and this review's; each correction dated in place): ADR-0107…0112 corrected
and `Accepted`; `PHASE_13_PLAN.md` (§1.2 items 75–95 and five restatements, inline corrections through §3–§24, **§25 errata**);
`FINANCIAL_INVARIANTS.md` (`INV-RSK-01` no `CREDIT_SIGNAL` assessment; `-02`'s writes and effects; `-03` `HOLD` uncounted,
`SESSION`, the fail-safe's `UNCOUNTED`; `-07` C7's one-holder band, every four-eyes `CHECK` and where it lives; `-09` an id-set
census and its settle horizon; `-10` `PROHIBITED` refuses only through a rule; a PI-004 note on every Verify line that cited the
storm); `RISK_LIFECYCLES.md` (built header; the held withdrawal and card payment resolved by an override or a case's own action,
never a disposition; the alert key; the permit machine); `DISTRIBUTED_EXECUTION.md` §3 (the lock order rewritten from the code
for every transaction; namespace 13's second writer registered); `MODULE_ARCHITECTURE.md`, `BOUNDED_CONTEXTS.md` 20–23,
`DOMAIN_MODEL.md`, `GLOSSARY.md` §8a; `OPERATIONS_RUNBOOK.md` §9 (the activation gate's read-back door, step 2 blocked by the
postponed storm, step 3's owner decisions, the held card payment's §9.5b, the held withdrawal's two paths);
`AUDITABLE_ACTIONS.md` (forty acts, not thirty-two); `ERROR_CONTRACT.md` (twenty codes, not twelve); `DATA_CLASSIFICATION.md`
(judgements beside a subject `CONFIDENTIAL`, amounts in canonical inputs `RESTRICTED-FINANCIAL`, notes `RESTRICTED-PII`);
`TESTING.md`; `CAPABILITY_MAP.md`; `ROADMAP.md`; `DECISIONS.md`; ADR-0045 and ADR-0062 cross-referenced to payments `V033`/`V034`;
`MUTATION_TESTING.md` (§2's new in-suite rows, §3's Phase 13 block, §4's `P13-TST-001` row); `CURRENT_STATE.md` (six debt rows,
three stale ones corrected).

**Recorded, not changed** (each an owner item in `PHASE_13_PLAN.md` §1.2 with a recommendation; none decided here):

- **Item 79 — the evidence door fails open on the reference** (A6-2, IMPORTANT, security). The case file's evidence door judges
  the case's subject, not each reference it serves: an analyst can attach a reference of their own party (their transfer's
  journal entry, a signal id) to another party's taken case and read it back. Live from the first deployment (cases open on
  defaults and screening hits with no policy). **Recommended: fix before production use** — judge each served reference's own
  parties (a journal entry's subject and customer counterparty, a signal's subject) as the file's subject is judged, failing
  closed. Not a repair this review could make without deciding which parties of an entry count.
- **Item 78 — four-eyes suppression can be reached alone** (A6-3). One `RISK_INVESTIGATE` holder can open a case on an open
  alert by hand and dispose it `CLOSED_*` alone (`CLOSED_*` is one person's act; `V012`'s `CHECK` exempts it), AML included.
  **Recommended: fix before production use** (the case desk is live from the first deployment) — a second holder for `CLOSED_*` of a case holding alerts (at least `AML`).
- **Item 75 — an unreadable pin answers `ALLOW` for every operation** (A1-1, A9-1; ADR-0108's follow-up reading (ii), left to
  this review): `WITHDRAWAL` and `CROSS_BORDER`, whose C1 bands are `DENY` everywhere, then move money on the compiled `ALLOW`.
  A realistic trigger is a release that tightens policy validation so an `ACTIVE` version no longer decodes. **Recommended:
  decide before activation** — a compiled per-operation answer (`DENY` for withdrawals and cross-border), and the runbook step
  that every `ACTIVE` version decodes under a new build before release.
- **Item 77 — a hot subject's movements become band-only fail-safes** (A1-2, A4-1). (R2a) is taken inside the 250 ms read
  budget; a burst of one party's movements from distinct accounts queues on it, the late ones are `TIMEOUT` fail-safes judged by
  the amount band alone (C1: `ALLOW` below EUR 1,000 / EUR 250), their counts possibly `UNCOUNTED` — so velocity limits can be
  outrun by concurrency, against `INV-CON-03`'s "not bypassable by concurrent requests". Meters and `RiskEvaluationUnavailable`
  see it. **Recommended: decide before activation** — a fail-safe `DENY` for a `TIMEOUT` met at (R2a), or (R2a) outside the
  budget with its own `lock_timeout` and a retryable refusal; the storm (PI-004) to measure.
- Items 80–95: the session rule's challenge without an alert (80); operations reports carrying amounts (81); the monitoring
  census by id, not amount (82); `PROHIBITED` refusing only through a rule (83); the prohibition driver's starvation (84);
  payments' stuck gauge counting held card payments (85); three risk roles on one identity (86); the unsalted device hash (87);
  the report doors' answers serving the narrative unaudited (88); the engine not checking scale (89); the unbounded corridor set
  (90); the replay body not `@ClosedBody` (91); the unenforced settle horizon (92); dead vocabulary (93); a `DENY` opening no case
  (94); the untimed case opening inside T-eval (95). And restatements of items 3, 18, 20, 58 and OQ-4.

---

## The database pass (C5, owner-authorised 2026-10-26, re-confirmed 2026-10-27)

**Scope** — every database suite Phase 13 added, changed or owed, plus the touched modules' schema owners
(`docs/project/BACKLOG.md`'s owed lists, `CURRENT_STATE.md`'s C5 row, and every `@Tag("database")` class changed since the
Phase 12 → 13 transition): `:risk:databaseTest` (12 suites, then 13 with this review's arbiter suite); `:credit:databaseTest`
(10 — credit `V023`); `:crossborder:databaseTest` (4 — its seam inputs changed); `:platform:databaseTest`'s
`ColumnClassificationTest` and `ApplicationRoleGrantsTest` (every risk column and role); and 74 `app` suites — the 44 Phase 13
changed or added, every `app` payments and kyc database suite (payments `V033`/`V034` and kyc `V011` changed their code), the
cited re-runs (`DecisionReplayDatabaseTest`, `TransferExecutionDatabaseTest`, `CrossBorderPaymentDatabaseTest`), identity
`V023`'s schema suite and ledger's journal read (`JournalPostingDatabaseTest`, `ReversalDatabaseTest` - `entriesAfter` refactored
the row mapping). **No Kafka suite** — Phase 13 added or changed none; the kafka tier's four suites are earlier phases' and
untouched. Not run (C5): the fleet tiers beyond this set, previous phases' other module suites. The storm (`RiskStormTest`) is
not on master and was not run (PI-004).

**First run** (fresh worktree, no prior results; the storm branch's three repair commits `09471d19`, `7628a1c7`, `8f648796`
cherry-picked first): **102 suites, 895 tests, 23 failures in 10 suites** — risk 12/111/1, credit 10/106/0, crossborder 4/23/0,
platform 2/11/0, `app` shared 65/568/22, `app` own-container 9/76/0.

| Suite (first-run failures) | Cause | Repair |
|---|---|---|
| `RiskActionDatabaseTest` (1) `#tenApproversOneDispatch` | the test drove the dispatch inside the race: the winner's `SKIP LOCKED` claim met a loser still holding the action row (`lockedOf`) | the dispatch driven once after the approvals (the schedule's shape); the claim asserted |
| `MonitoringIntakeDatabaseTest` (2), `MonitoringRulesDatabaseTest` (4) | **the container's clock**: Docker Desktop's VM clock ran 1.2–2.5 s behind the JVM and stepped (a 6 s sleep spanned 3.9 s of `clock_timestamp()`); a zero settle horizon reads an entry only once the database's clock passes its instance-minted millisecond, and each drain ended at its first empty read | `DatabaseClock.awaitPast*` (a new test helper): wait for the database's own clock before draining or classifying |
| `WithdrawalHoldDatabaseTest` (1) `#theExpiryReleasesTheHoldOnce` | the same clock: `held_at` is never earlier than the instance's `created_at`, so an instance ahead holds the row past a fixed 400 ms sleep | wait for the expiry leg's own predicate on the database's clock |
| `RiskProofsDatabaseTest` (1) `#theVelocityProofNamesADifferenceAndCorrectsNothing` | the same clock: the window stayed pending (counted within `SKEW` of the evidence) | wait for `last_counted_at + SKEW` on the database's clock |
| `PaymentAuthorizationDatabaseTest` (1), `PaymentEndpointDatabaseTest` (2) | **`P13-TSK-010`'s intent-first lock order**: the confirmation now takes the intent row `FOR UPDATE` before the instrument; two Phase 5 interleavings waited for "`SET status`" and the detach race's "mid-confirmation" point no longer exists there; the second endpoint failure was its cascade (the abandoned confirm authorised under the next test's stubbed PSP reference: 23505 → 500) | markers moved to the intent row's lock; the detach race holds the credited wallet's ledger account, which the confirm share-locks right after the instrument |
| `PaymentsSchemaDatabaseTest` (1) `#theIntentsUpdateGrantIsOneColumn` | payments `V034` granted `UPDATE (failure_reason)` on purpose | the sweep admits the two columns |
| `MultiRailConservationStormDatabaseTest` (1) | the duplicator's round-robin passed a `CHARGEBACK` burst that found no undisputed sale; a slower storm (every confirmation judged) ended with none — and, on the re-run, **shared container**: its card sweep chains every stranded authorization in the database and met seven another suite left on fixture accounts (`UnknownPostingAccountException`; the debt row "Suites that sweep with a fixed capture body") | the kind advances only once its burst ran; `own-container`, as every other storm already is |
| `RescreeningDatabaseTest` (9) | **shared container**: every activation enumerates every `ACTIVE`/`PENDING` party in the database, and a Phase 1 schema suite inserts a party under a random v4 id, which `PartyId.of` refuses (ADR-0013) — green alone | `own-container` (its effect is database-wide: it rescreens every party through kyc) |
| `MonitoringRulesDatabaseTest` (4, again on the re-run) | the container's clock (above) — and, once its drains read the whole journal, **shared container**: its `MONITORING` version judges every recompute and its rule leg joins every journal entry's customer account to a party, and another suite's fixture account has a non-v7 `owner_ref`, which `CustomerAccountId.of` refuses — the batch rolls back with the watermark | `own-container`; the production class (one corrupt id stalls the sweep) recorded on the existing debt row |

**Found by the repairs' own verification run:** `RiskProofsDatabaseTest` commits what no real path writes — a phantom monitored
transaction, an assessment of no command — append-only rows that then failed `MonitoringIntakeDatabaseTest`'s whole-journal
census: `own-container`. The detach race's first repair (holding risk's namespace-13 pin) was wrong — a card top-up is not judged
by risk (item 7) — and was replaced by the wallet hold above. Three failures of that run were the container's clock stepping
backwards between two statements (a routing version seeded by `V025` read as "in the future" by the JVM's instant — the routing
version in force is chosen on the JVM clock since `P7-TSK-003`, `POSTPONED_ISSUES.md` PI-002's class, not Phase 13's — and an
override's action not yet "due" a statement after its approval); they passed on the re-run.

**Re-run** (the whole set, fresh, 34 min; then the five suites a repair touched after that run's compile, fresh; then the
risk tier whole, fresh, after the arbiter suite's seed fix): **103 suites, 897 tests, 0 failures** — risk 13/113/0
(`RiskBornOnceArbitersDatabaseTest` added), credit 10/106/0, crossborder 4/23/0, platform 2/11/0, `app` shared 61/535/0,
`app` own-container 13/109/0 (four suites moved there by this pass: `RescreeningDatabaseTest`, `RiskProofsDatabaseTest`,
`MonitoringRulesDatabaseTest`, `MultiRailConservationStormDatabaseTest`, each with its reason in `app/build.gradle.kts`).
Every suite, its first run and its re-run, is in the appendix.

**No production defect; no migration changed or added.** Every failure was a test's assumption — about the database's clock
keeping the JVM's value and pace, about the shared container, about an interleaving point a Phase 13 lock-order change moved —
or a test race. The C5 deviation's database pass is **done**: the owed suites have their first run on record, green.

---

## The flip

Phase 13's status line was recorded `COMPLETE` in `CURRENT_STATE.md` (from `IN_REVIEW`, which this review set first), which
arms `MutationDemonstrationTest` for every `Phase: 13` invariant (`INV-RSK-01`…`10`, `INV-CON-03`) and every `P13-TST-*` item.
Before it, `P13-TST-001`'s §4 row was written - **as a deferral, not a demonstration**: the guard demands a row for every test
item of a completed phase, and the postponed storm has none to give; the row says so, and the owner is asked to keep or change
this convention for every later postponed storm (PI-004). With **`INV-RSK-09`'s five §2 rows withheld** (the invariant with the
fewest rows; register sha256 `18faaeb7…6ab1c832` → `8d0915cc…4664a293`), `:app:architectureTest --rerun --tests
*.MutationDemonstrationTest` failed **1 of 9**, `everyInvariantOfEveryPhaseReachedHasADemonstration`, reporting `(currently 13)`
and naming exactly `["INV-RSK-09"]`; the test-item case stayed green. The register was restored **byte-identical** (sha256
`18faaeb7…6ab1c832`, verified) before this review's own probe rows were added below.

---

## Area 1 — Domain correctness

The `risk` module owns the judgement and none of the money: policy versions, assessments, signals, velocity windows, monitored
transactions, profiles, alerts, cases, overrides, risk actions, regulatory reports, screening-list versions and rescreening
permits (`V001`…`V018`). The concepts match the domain model after this review's corrections; the distinctions CLAUDE.md names
hold — a risk score is not a credit score (`Score`, `INV-CRD-04`), an assessment is not a case, an internal investigation is
not a filing (`OQ-7`), onboarding screening stays kyc's and rescreening asks kyc through its own new door (O1). Built beyond the
plan by the owner's decisions: collections case (C3, `-025`), the kyc rescreen door (O1, `-022`), the card `HOLD` (`-010`'s slice
2). Not built and recorded: the one-time override allowance (item 13), refunds to new accounts (item 18), a `DENY` opening a case
(item 94), `RESCREEN_PARTY` on demand (item 50), accounts and beneficiary actions (item 24). **`PASS`.**

## Area 2 — Financial correctness: a movement end to end, for a phase that posts nothing

A3 traced every seam (A3's verdict: **holds**): a transfer judged after `lockBothInFixedOrder` and the availability read, its
refusal `FAILED(RISK_REFUSED)` with zero journal entries; a withdrawal judged under the wallet lock after its hold, `DENY`
releasing the hold in the same transaction, `HOLD` keeping exactly one, release, denial and expiry each one outcome on the row's
lock; a checkout confirmation judged after routing and before anything is written, its refusal the intent's terminal `FAILED`
with `RISK_REFUSED`, a held card payment authorised with its capture held, captured or voided once; a cross-border authorisation
judged before the offer is consumed. Risk's own writes are `risk.*`, platform's audit, outbox and idempotency rows; every
downstream effect is the owning domain's keyed command under `risk.action:<id>`, on no risk transaction. Money is integer minor
units with its currency throughout (`NoFloatingPointMoneyRulesTest` over risk; mixed currencies refused). Recorded: a transfer
refused `DESTINATION_NOT_POSTABLE` after the seams keeps its count and then reads as a false `VelocityDiverged` for its windows'
life (item 58, restated); the monitoring census compares ids, not amounts (item 82); the engine checks currency, not scale
(item 89); payments' stuck gauge counts held card payments (item 85). **`PASS`.**

## Area 3 — Boundary integrity and multi-instance correctness

`risk` depends on `platform` and `sharedkernel` only and no module depends on it (`RiskModuleIsolationTest`, all twenty sibling
isolation tests); risk's SQL names `risk.` only; the adapters live in `app`; the census's raw reads of five owners' tables are
recorded in ADR-0107. The lock order read from the code for every transaction (now `DISTRIBUTED_EXECUTION.md` §3) is acyclic;
no forbidden order was found. **Every §7.4 row's named test exists and races** (12 of 16 with real concurrency; rows 3, 9, 10
and 14 sequential by nature or by fixture, each recorded); every arbiter is refused alone (the arbiter suite, the four-eyes
probes by name). Two schedules leaderless and registered (31 in `LEASE_PROTECTED_SCHEDULERS`); every boundary on the database's
clock after the lock but the ATO lookback's producer instant (A4-11, wording). **Rests on the postponed storm:** the census at
every round, the named lock waits for (R1), (R4), (R7), (R8) (only (R6) is asserted in-suite), "no `40P01` in any storm run" —
and A4-2's caution that an (R2a) inversion becomes a `TIMEOUT` fail-safe below `deadlock_timeout`, so the resumed storm needs a
larger budget for that round (a debt row). **`PASS` on arbitration; the counted half deferred (PI-004).**

### Every task's ten-instance answer, re-checked

Every task's recorded answer names a test that exists and races; none had run before this pass, and each is green in it. `-001`
and `-024` are vacuous for correctness (no writer, no lock); `-003` rests on determinism (two JVMs); `-023` is reads only. The
answers that needed this pass's repairs to be *counted*: `-017`'s ten approvers (the drive), `-019`/`-020` (the container's
clock), `-022` (its own container), `-011` and `-010` (the storm branch's repairs: the open-quote cap, the PSP references).

## Area 4 — Failure behaviour

Two scenarios traced through the code: **the pin's read failing** (`RiskEvaluation` → `RiskEngineV1.failSafe` with no policy →
the compiled `ALLOW`, `RISK_UNAVAILABLE`, `PIN_UNREADABLE`, the alert — item 75), and **a filing whose response is lost**
(`FILED` committed before the call, `UNKNOWN` inquired by reference and never re-filed; on `NOT_RECEIVED` re-submitted under the
same reference, so no second filing rests on the adapter's dedupe — `INV-RSK-06`'s note corrected). Every one of §14's ten
situations has a test or a rationale (A2's map); three rows the plan lacked were added in its errata (the held card's validity
lapse, the rescreening provider's failure, the collections stall). **`PASS`.**

## Area 5 — Security and audit

Six permissions and three roles with exact grants, pairwise disjoint (`RoleNameTest`); forty operator routes, all registered
(`RoutePermissionRegisterTest`; per-route negatives for the policy desk, the rest by family and `DenyByDefaultDatabaseTest` —
A6-4, recorded); `MULTI_FACTOR` on every four-eyes approval, now pinned for all of them (`RiskStepUpRoutesTest`); four eyes at
both ranks for seven acts, each `CHECK` refused alone by name; every self-approval refusal audited `FAILED` in a transaction of
its own; self-dealing failing closed at every domain door **but the evidence door's references** (item 79); forty audit acts,
code and catalogue equal; twenty error codes, statuses equal. Open for the owner: items 78 (suppression reached alone), 79, 86
(three roles on one identity), 88, 91. **`PASS` after corrections**, item 79 recorded as IMPORTANT for remediation before
production use.

## Area 6 — Test quality

**Every `Phase: 13` invariant has a performed demonstration** — eleven newly demanded by the flip (`INV-RSK-01`…`10`,
`INV-CON-03`), 210 §2 rows, every named test and method resolving (A8, by script). The review's own corrections are above; its
probes are below. The fifteen mandatory scenarios each have a named database test, green in this pass (§13.2's table, with the
backlog's eight wrong test names corrected in the plan's errata); none has storm evidence (PI-004). **`PASS`.**

## Area 7 — Documentation accuracy

Twenty-five documents corrected against the code (listed above), every correction dated. The plan gained §25's errata. The gate's
own clause texts are not re-worded; the clauses met in an unanticipated form are recorded after the gate's criteria
(`PHASE_GATES.md`, the note after Phase 13's). **`PASS` after corrections.**

## Area 8 — Architectural debt, and the Phase 14 boundary

Nothing of `PHASE_13_PLAN.md` §20 is built: no machine-learned model, no cache, no write-off, no reversal by risk, no credential
reset, the FIU simulated, collections facts only. The debt register is below. **`PASS`.**

---

## The storm — postponed, its evidence deferred (PI-004)

`P13-TST-001` was postponed by the owner on 2026-10-27, with every later phase's storm, until after the project's phases. This
review records the deferral in place of its evidence and claims nothing the storm was to show.

**The evidence the phase has:** each task's hermetic proofs and sha256-verified probes (`MUTATION_TESTING.md` §2, 210 Phase 13
rows); the database suites, run for the first time in this pass and green; the production censuses and proofs over live rows
(`AssessmentCensus`, `MonitoringCensus`, `VelocityProof`, `RescreeningCensus`, `FilingCensus`, `AlertBornOnceCensus`,
`CaseOneOpenCensus`) with their gauges and `RiskCensusDiverged`; the independent reviews at every merge and this review's ten;
and the storm's partial ninth run on the parked branch (seed `1793081146028`, two instances, PostgreSQL 18 and Kafka): **57
census checks, 0 failing, at every round** — before the storm, with no policy, with v1 in force, six rounds, each of the fifteen
scenarios, the filing with a lost response, the named lock waits and the crash points (about 2,065 s in).

**What it lacks:** the finished storm — three consecutive fresh runs, its probes, its final run and its gate; the T-eval latency
budget measured per operation (p50/p99/max, M3's split trigger — items 76, 95); the named lock waits asserted on the final
code; "no `40P01` in any storm run" (and A4-2's caution). **Phase 13's ten-instance answer on storm counts stays open under
PI-004**; its answer on arbitration is `PASS` (Area 3).


**Addendum (2026-10-27, after this review): the owner closed `P13-TST-001` by acceptance.** It is `COMPLETE` on its partial evidence above, not demonstrated: the latency budget, the storm's probes, its final run and its gate were waived by the owner, not performed. The seventeen sub-clauses this review counted as resting on the storm now read **held by the owner's acceptance** - accepted, not counted; the ten-instance answer on storm counts is the owner's acceptance, not a measurement. The harness is on master (own-container) so the storm can be run again; PI-004 keeps the storms of later phases.

---

## The fifteen mandatory scenarios

| # | Scenario | Named test (green in the database pass) | Storm |
|---|---|---|---|
| 1 | Duplicate, delayed, reordered events | `SignalIntakeDatabaseTest#theSameEventTwiceRecordsOnce`, `AlertDatabaseTest#theSameEventTwiceRaisesOnce`, `CaseDatabaseTest#aDuplicateAlertAttachesAndIsNeverSuppressed`, end to end `CollectionsCaseDatabaseTest#theSameEventTwiceOpensOnce` | deferred |
| 2 | Concurrent alert creation | `AlertDatabaseTest#twoInstancesRaiseOnce` | deferred |
| 3 | A policy change during evaluation | `RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor`, `RiskEvaluationDatabaseTest` | deferred |
| 4 | A risk action retried | `RiskActionDatabaseTest#aReDriveReplays`, `#tenApproversOneDispatch`; real owner `WithdrawalHoldDatabaseTest#anOverridesReleaseDispatchesOnce` | deferred |
| 5 | Concurrent case updates | `CaseDatabaseTest#concurrentUpdatesKeepEveryAction` | deferred |
| 6 | A delayed event after disposition | `CaseDatabaseTest#aLateEventAfterDispositionOpensANewCase` | deferred |
| 7 | A downstream failure after a decision | `TransferRiskSeamDatabaseTest`, `WithdrawalRiskSeamDatabaseTest#aFailedDispatchLeavesTheDecisionStanding`, `PaymentRiskSeamDatabaseTest#aProviderFailureAfterAPermitLeavesTheDecision`, `RiskActionDatabaseTest#aDomainRefusalIsRecordedNotRetried` | deferred |
| 8 | A replay of history | `MonitoringIntakeDatabaseTest#reprocessingCreatesNothingNew`, `MonitoringRulesDatabaseTest#reprocessingRaisesNoSecondAlert` | deferred |
| 9 | The fail-safe per operation and band | `RiskEvaluationDatabaseTest#aTimeoutSealsTheFailSafe`, each seam's `riskUnavailable*` and `noPolicy*`; the battery's band edges; `RiskAlertRulesTest` | deferred |
| 10 | The held withdrawal released or denied once | `WithdrawalHoldDatabaseTest` | deferred |
| 11 | Account takeover | `AccountTakeoverDatabaseTest` (the payee step judged on no withdrawal - items 4, 6) | deferred |
| 12 | Structuring | `MonitoringRulesDatabaseTest#aStructuringSequenceAlertsOncePerWindow` | deferred |
| 13 | Credit signal v2 | `CreditRiskSeamDatabaseTest` | deferred |
| 14 | A refused movement counts nothing | `VelocityWindowDatabaseTest`, `RiskEvaluationDatabaseTest#aRefusedMovementCountsNothing` (exceptions items 2, 58) | deferred |
| 15 | Four eyes on activation, override, disposition | `RiskPolicyDatabaseTest`, `DecisionOverrideDatabaseTest`, `CaseDatabaseTest` (each `CHECK` by name) | deferred |

---

## Probes

Four probes of the review's own corrections, each performed, caught, restored byte-identical (sha256-verified) and recorded in
`MUTATION_TESTING.md` §2:

| Probe | Caught by | Observed |
|---|---|---|
| `regulatory_report_four_eyes` deleted from `V016` | `RegulatoryReportDatabaseTest#theApproverIsNotTheDrafter` | red - the raw self-approval admitted (before the review this probe could not fail) |
| `risk_action_one_live` made a plain index in `V013` | `RiskBornOnceArbitersDatabaseTest` | red - "every one still standing": 17 refused alone, the live action's arbiter gone |
| `risk_assessment_pinned_unless_unpinnable` deleted from `V007` | `RiskEvaluationDatabaseTest#theVersionIsNamedUnlessNoneCouldBePinned` | red - the unpinned `TIMEOUT` copy admitted |
| `@RequiresAssurance` removed from the report's filing route | `RiskStepUpRoutesTest#theReportsApprovalAndFilingAreSteppedUp` | red - 1 of 3 |

The three database probes ran together on a fresh container: **3 of 20 red, each the one aimed at**. The arbiter suite's first
probe run also failed for two reasons of its own - its seeds for the overlapping override uniques and for the alert's
generated `fact_signal_id` - which the shared container's real rows had hidden in the pass; both seeds were corrected and the
risk tier re-run green before the probes were repeated. And the flip above. The tasks' own 210 §2 rows and their sha256
records stand; the database probes their notes owe are counted in §3 (Phase 13's block) and a debt row.

---

## The twelve universal exit criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | `PASS` — with `P13-TST-001` postponed by the owner | 25 tasks and `P13-TST-002` `COMPLETE`; the storm's deferral recorded (PI-004) |
| 2 | Architectural boundaries respected | `PASS` | `RiskModuleIsolationTest`, sibling isolation tests, `NoCustomerSurfaceReadsRiskTest`; app `architectureTest` at the gate |
| 3 | Required invariants tested | `PASS` | every `Phase: 13` invariant demonstrated; the flip below |
| 4 | Failure cases handled | `PASS` | §14's situations each a test or a rationale (A2) |
| 5 | Security requirements implemented | `PASS` | Area 5; item 79 recorded for remediation |
| 6 | Observability exists | `PASS` | 22 series with closed tags, eager (`PlannedMetersExistTest`); nine rules each firing only on its break (`RiskAlertRulesTest`); spans without attributes |
| 7 | Integration tests pass | **`PARTIAL`** — the standing C5 deviation | the owner-authorised database pass run on the final tree (above); the fleet tiers beyond the Phase 13 set and the storm not run |
| 8 | Documentation reflects reality | `PASS` after corrections | Area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` | by this review |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` | ADR-0107…0112 `Accepted` after correction; the kyc door under ADR-0035's amendment; payments `V033`/`V034` cross-referenced from ADR-0045 and ADR-0062 |
| 11 | No unresolved critical issues | `PASS` | no CRITICAL; the IMPORTANT open items are owner items with recommendations (75, 77, 78, 79) |
| 12 | Formal phase review conducted | `PASS` | this document |

**Criterion 7.** The owner decided (C5, 2026-10-22) to keep skipping the fleet-wide `databaseTest` and `kafkaTest` tiers through
Phase 13 with one owner-authorised database pass before the exit review. The pass ran (above) and found no production defect;
the fleet tiers beyond it, previous phases' other suites and the storm were not run. A standing, recorded deviation, not a pass —
to be asked again at the Phase 13 → 14 transition.

## The financial supplement F1–F8 — as it applies to a phase that posts nothing

| | Verdict |
|---|---|
| F1 Trial balance zero | `Met` in the owning domains' form — risk posts nothing; a refused transfer has no journal entry; a withdrawal's release is the existing hold release |
| F2 Balances reproducible | `Met` in the owning domains' form — re-run under their existing proofs in the pass (payments, ledger's journal read) |
| F3 Idempotency at the financial boundary | `Met` — one assessment per command under the caller's key, every seam (`#tenRetriesOneAssessment`, each seam's retry) |
| F4 Reversal paths; history immutable | `Met` in the owning domains' form — risk reverses nothing; every risk table append-only by trigger |
| F5 Duplicate external events | `Met` — signal intake, the alert, the case, the collections path each born once |
| F6 Concurrency for every contended resource | `Met` for the named ten-way suites; the storm half deferred (PI-004) |
| F7 No floating point | `Met` — `NoFloatingPointMoneyRulesTest` over risk, probed |
| F8 Reconciliation | `Met` — the censuses and proofs implemented and gauged; the monitoring census by id (item 82) |

---

## The Phase 13-specific criteria

Read clause by clause by A1 (the six originals and the risk evaluation, policy versioning, decision explainability and fraud
workflow blocks: 100 sub-clauses), A2 (AML monitoring, alert deduplication, case management, domain integrations, idempotency
and recovery, testing, observability, criterion 7 and the production reality: 124), A4 (multi-instance: 13), A6 (security and
audit: 19) and A7 (privacy: 13), each sub-clause against its named test, read in the test's body.

| | Sub-clauses | Hold | Hold in another form | Rest on the storm | Do not hold |
|---|---|---|---|---|---|
| The six originals | 58 | 41 | 11 | 6 | 0 *(1.2 → item 75; 4.3 → the new pin `CHECK` probe; 5.9 → item 13's deferral)* |
| Risk evaluation · policy versioning · explainability · fraud | 42 | 31 | 11 | 0 | 0 *(RE3 → the race and the arbiter suite; DE3 → the walk; FW10 → item 80)* |
| AML · alerts · cases · integrations · idempotency · testing · observability · criterion 7 · production | 124 | 94 | 22 | 8 | 0 *(the jurisdiction debt row written; the probe record → the arbiter suite and the named four-eyes probes)* |
| Multi-instance | 13 | 8 | 2 | 3 | 0 *(lock-bypass → the arbiter suite; lock-wait tests → rest on the storm, (R6) in-suite)* |
| Security and privacy · auditability | 32 | 24 | 8 | 0 | 0 *(the filing's step-up → `RiskStepUpRoutesTest`; classification → reclassified; reports' amounts → item 81)* |

*(Addendum 2026-10-27: the 17 "rest on the storm" sub-clauses are held by the owner's acceptance of `P13-TST-001` - see "The storm".)*

**Each clause met in a form its words did not anticipate** is recorded in the note after Phase 13's criteria in `PHASE_GATES.md`
— among them: the fail-safe's unpinned answer (item 75); `RiskEvaluationUnavailable` firing on `RISK_UNAVAILABLE` only,
`NO_POLICY_ACTIVE` raising `RiskPolicyNoneActive` where judging is expected; the replay "door" of the battery being the
`AssessmentReplay` function; the unclassified and divergence "alerts" being Prometheus rules (item 93); the alert key's fourth
column (`fact_signal_id`); the `NOT_ASSESSED` answer kept for an unassessed party; the cross-border input gaining `quoteId`;
rescreening through kyc's new door; C7's threshold the pinned version's; `rule` bounded by the `RuleKind` vocabulary; the ATO
sequence's payee step judged on no withdrawal (items 4, 6); the session rule a challenge without an alert (item 80); operations
reports carrying amounts beyond `-023`'s bucketed sums (item 81).

---

## ADR-0107 … ADR-0112: accepted

Each read decision by decision against the code (A5) and corrected in place, dated `P13-DOC-001`, then set `Accepted`
(`docs/adr/README.md` agreeing). The material corrections: **ADR-0107** — the port table as built, the sum guard a trigger,
fact alerts without a policy, the legs' schedules and namespace 13's second writer, the lock order (T-eval takes (R3)–(R5)), M3
unmeasured; **ADR-0108** — `CARD_PAYMENT` admits `HOLD`, a `DENY` opens no case (item 94), the unpinned `ALLOW` (item 75), the
latency budget metered not measured, (R1)–(R3) false as built (item 95), C7 the pinned threshold; **ADR-0109** — the version's
currency, hold validity and rating bands, dead rule kinds admitted, policy reads `RISK_POLICY_ADMINISTER` only and unaudited;
**ADR-0110** — four eyes on `REFERRED` and prohibiting dispositions, no disposition issues an action, the evidence door (item
79), `SCREENING_HIT` a second fact origin; **ADR-0111** — no journal sequence (a UUIDv7 keyset behind a settle horizon, item 92),
an id-set census (item 82), rescreening as built (`V018`, `RiskActionSchedule`, kyc's door, parties only); **ADR-0112** — eight
dispatchable kinds, three unavailable, `RESCREEN_PARTY` never built. ADR-0045 and ADR-0062 cross-reference payments `V034` and
`V033`.

## The production reality, recorded as accepted

Production evaluates nothing until a risk policy version is activated under four eyes and `OPERATIONS_RUNBOOK.md` §9.1's
activation gate is passed: every seam answers `ALLOW` with `NO_POLICY_ACTIVE`, recorded; the monitoring sweep raises nothing; the
filing adapter is simulated; the reference jurisdiction is the neutral EUR one (C4). Two fact alerts run from the first deployment
— a `COLLECTIONS` case on every default and a `SCREENING_HIT` alert on a list activation's hit — so the case desk, and with it
item 79's evidence door, is live before any activation. The gate's step 2 (the storm) cannot be met until the storm runs (PI-004)
and blocks activation unless the owner's written allowance names it deferred; step 3 lists the owner decisions activation waits
on. Recorded as an **accepted limitation, not a deviation** (`PHASE_13_PLAN.md` §1.1; the debt row).

## Debt register (Phase 13's, after this review)

Every row is in `CURRENT_STATE.md` §Known Architectural Debt with its trigger.

| Debt | Owner |
|---|---|
| Production judges nothing until a risk policy version is activated (the activation gate's owner decisions) | The owner |
| A real FIU, screening-list and device-intelligence provider | A later phase; Phase 15 at the latest |
| The reporting jurisdiction and §12.9's verification items (C4) | The phase that chooses it |
| The extraction decision and M3's split triggers (unmeasured) | Phase 16; the measurement with PI-004 |
| Phase 13's storm evidence (PI-004; A4-2's budget caution) | The post-phase clean-up |
| Phase 13's register owes recorded database probes (`MUTATION_TESTING.md` §3) | The owner; with PI-004 |
| Regulatory report retention (kept for ever) | Phase 15 |
| The risk proofs read whole days | Phase 15 |
| The rescreening census counts each version whole | Phase 15 |
| The assessment census dates a payment intent by its creation | The owner (payments); Phase 15 recommended |
| Collections operations beyond the case | Phase 14 (write-off) and a later collections context |

---

## Decisions for the owner — for ratification

The owner list is `PHASE_13_PLAN.md` §1.2 (items 1–95), §2.2 (C1–C10) and §24 (OQ-1…OQ-10), each item read against the code by
A9 and by this review. **None is decided here.** Each carries a recommendation; **⚑ marks where this review recommends the owner
decide differently from the as-built**.

### Decide before the first policy activation (the runbook's step 3 lists them)

| # | Subject | Recommendation |
|---|---|---|
| 1, 10, 12 | Every version is EUR; a non-EUR movement meets the open-ended band and is `DENY`ed; corridors with no EUR leg always `DENY`; one currency per window | ⚑ Decide: a pinned EUR conversion (per-currency versions need a code change - the door refuses non-EUR) |
| 75 | An unreadable pin answers `ALLOW` everywhere | ⚑ A compiled per-operation answer (`DENY` for withdrawals and cross-border) and a decode-before-release step |
| 77 | A hot subject's `TIMEOUT` fail-safes judged by band alone, possibly uncounted | ⚑ Fail-safe `DENY` for a `TIMEOUT` met at (R2a), or (R2a) outside the budget with a retryable refusal |
| 86 | One identity may hold all three risk roles | Decide: one risk role per identity, or ratify (four eyes compares actors, not roles) |
| 83 | `PROHIBITED` refuses money only through a `PROFILE_RATING` rule | ⚑ The door to require the rule in every money operation's version, or the engine to refuse a `PROHIBITED` party itself |
| 72 | A `SOURCE_ACCOUNT` count on `CARD_PAYMENT` never fires | ⚑ The door to refuse dimensions an operation never carries |
| 36, 66 | Every fired AML kind alerts (a round-amount flood); queue capacities and the flood objective | Decide in the production policy, at the gate |
| 92 | The settle horizon unenforced | Ratify as the recorded bound (the plan's recommendation), or bound posting transactions below it / alert on a late commit - the runbook's step 3 lists it so activation asks |

### Fix before production use (the case desk is live from the first deployment)

| # | Subject | Recommendation |
|---|---|---|
| 79 | The evidence door serves references of the reader's own party (IMPORTANT, security) | ⚑ Judge each served reference's parties, fail closed - a remediation item |
| 78 | Suppression's four eyes reached by one person's case closure | ⚑ A second holder for `CLOSED_*` of a case holding alerts (at least `AML`) |
| 87, 88, 89, 90, 91 | The unsalted device hash; the report doors' answers serving the narrative unaudited; scale unchecked; the corridor set unbounded; the replay body not closed | Each small: a keyed hash before any producer; an id-and-status receipt or a recorded read; refuse a scale mismatch; bound the set; `@ClosedBody` |
| 84 | One failing prohibition starves those behind it | A per-item catch and back-off (small) |
| 85 | Payments' stuck gauge counts held card payments | Exclude unresolved capture holds (small) |
| 68 | The held-withdrawal expiry leg logs and meters nothing | A meter and an alert (small) |
| 21 | A `PROHIBITED` rating has no lifting door | ⚑ A four-eyes lift before activation |
| 25 | `REINSTATE_IDENTITY` lifts every suspension of the party's identities | ⚑ Identity records the cause; risk lifts only its own |
| 45, 41 | A draft the approver refuses has no exit, and holds the case's only report slot | ⚑ A `DRAFT → WITHDRAWN` edge before a real FIU |
| 27 | The AML approver cannot read the evidence it attests | ⚑ An evidence door under `AML_REPORT_APPROVE` before a real FIU |

### Ratify as built (the as-built is recommended)

C1's bands, C2, C3 (built by `-025`), C4, C6, C7 (the threshold the pinned version's), C8 (with items 16, 72's door, 74), C9 (`rule`
the `RuleKind`), C10; OQ-1…OQ-3, OQ-5…OQ-10 (OQ-4 moot: no device reaches risk); items 5, 11, 14, 16, 17, 19, 22, 23 (but AML's
720 h with C4), 26, 28–31, 33, 37, 39, 40, 42–44, 47, 48, 49 (the kyc door's semantics, fail-closed included), 54, 57, 58 (with
its false-page consequence noted), 60–65, 67, 69, 71 (amend the token contract now, while nothing is active), 74 (each documented
meaning listed in `PHASE_13_PLAN.md` §1.2), 80, 81 (or drop the velocity report's per-subject sums), 82 (amend `INV-RSK-09`, done in
the catalogue's note), 93 (retire or document the dead words), 94.

### Defer, with the owner named

Items 3 (identity's auth-failure source), 4 and 87 (a device provider; a keyed hash before the first producer), 6, 7 (⚑ card
top-ups are a classic stolen-card vector: judge them, or defer with an owner), 8 (⚑ cap a card `HOLD` at the authorisation's
life), 9, 76 and 95 (the storm's measurements, PI-004), 13 (⚑ a false-positive transfer `DENY` has no remedy but a policy change), 15 (⚑ payee
novelty per payer), 18 (refunds to new accounts — a later fraud task), 24, 32 (Phase 15), 34, 35, 38 (⚑ the highest-volume
parties' ratings freeze past ~20,000 movements — Phase 15 with a trigger), 46, 50–53, 55, 56, 59 (Phase 15 recommended), 70 (with
C4).

### Resolved since recorded

Item 20 (`risk.yml`, `-024`); the list of §1.2's "Resolved" paragraph; `CreditRiskSignal`'s `NOT_ASSESSED` retired for assessed
parties (`-007`).

---

## Tests, every count from a fresh run

The database pass: first run 102 suites, 895 tests, 23 failed; re-run 103 suites, 897 tests, 0 failed (the appendix). The
probes: 3 of 20 red as aimed, then restored; the step-up probe 1 of 3 red. `:risk:test --rerun`: 84 suites, 565 tests, 0
failed. `:app:test --rerun`, filtered to the risk package and the guards (`RiskStepUpRoutesTest`, `DomainGlossaryTest`,
`CredentialReachesNoEmittedSinkTest`, `PlannedMetersExistTest`, `OpenApiContractTest`, `RoutePermissionRegisterTest`,
`RiskAlertRulesTest`): 66 suites, 335 tests, 0 failed. `:app:architectureTest --rerun`, once at the gate, after the flip: 35
suites, 192 tests, 0 failed (`MutationDemonstrationTest` 9, `TestTaxonomyTest` 17, `AdrRegistersAreReconciledTest` 4,
`ArchitectureRulesAreDocumentedTest` 2 among them). Not run (C5): the fleet tiers beyond the Phase 13 set, previous phases'
other suites, `build`; the storm (PI-004).

## What the phase produced

One module (`risk`, eighteen migrations) and its composition (`app/risk`: the seam adapters, doors, schedules, ports); payments
`V033`/`V034` (the held withdrawal, the risk-judged payment and the card's capture hold), kyc `V011` (the rescreen door), identity
`V023` (three roles), credit `V023` (the snapshot names the profile version); the engine, twenty-two rule kinds, policy versions
under four eyes, the sealed assessment and its replay, velocity windows in the caller's transaction, signals from the platform's
events, the customer risk profile and credit's signal version 2, five seams, the override, alerts born once, cases, downstream
actions through identity, merchant and payments, queues under the tipping-off control, the monitoring intake from the journal,
the monitoring rules, the AML case and the regulatory report, rescreening through kyc, the collections case, the proofs and the
explanation door, thirty-five series and thirty alerts, runbook §9, the policy battery — and, from this review,
`RiskBornOnceArbitersDatabaseTest`, `RiskStepUpRoutesTest`, the deterministic seal race, the pin `CHECK` probe, the explanation
walk, the four-eyes probes by name, and the database pass's repairs.

## What happens next

**The Phase 13 → 14 transition is the owner's to open** — this review marks nothing `READY` and initialises nothing of Phase 14.
Its agenda is the owner list above: the activation-gate decisions (1, 10, 12, 72, 75, 77, 83, 86), the fixes before production use - the security remediation of
item 79, item 78, the defaults to ratify, criterion 7's standing deviation asked again, and PI-004's reminder — the storms of Phase 13 and
every later phase are owed after the project's phases.

---

## Appendix — the database pass, suite by suite

Every suite of the pass with its first run and its re-run (tests/failed), read from each run's own JUnit XML. "Final run" marks
the suites re-run after the whole-set re-run (the five a repair touched after its compile; the risk tier re-run whole after the
arbiter suite's seed fix).

| Tier | Suite | First run (tests/failed) | Re-run (tests/failed) |
|---|---|---|---|
| app-databaseTest | `AccountMonitoredPartiesDatabaseTest` | 1/0 | 1/0 |
| app-databaseTest | `AlertRulesResolveTest` | 10/0 | 10/0 |
| app-databaseTest | `AlertSuppressionApiTest` | 2/0 | 2/0 |
| app-databaseTest | `ChargebackAccountingDatabaseTest` | 21/0 | 21/0 |
| app-databaseTest | `ChargebackRatioReportDatabaseTest` | 5/0 | 5/0 |
| app-databaseTest | `CollectionsCaseDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `CounterpartyScreeningDatabaseTest` | 15/0 | 15/0 |
| app-databaseTest | `CreditRiskSeamDatabaseTest` | 2/0 | 2/0 |
| app-databaseTest | `CrossBorderPaymentDatabaseTest` | 15/0 | 15/0 |
| app-databaseTest | `CustomerAnswerIsIndifferentToACaseTest` | 2/0 | 2/0 |
| app-databaseTest | `CustomerProjectionDatabaseTest` | 8/0 | 8/0 |
| app-databaseTest | `DashboardQueriesResolveTest` | 7/0 | 7/0 |
| app-databaseTest | `DecisionDatabaseTest` | 10/0 | 10/0 |
| app-databaseTest | `DisputeBatteryDatabaseTest` | 1/0 | 1/0 |
| app-databaseTest | `DisputeNotificationDatabaseTest` | 16/0 | 16/0 |
| app-databaseTest | `DisputeResponseDatabaseTest` | 19/0 | 19/0 |
| app-databaseTest | `DocumentUploadDatabaseTest` | 9/0 | 9/0 |
| app-databaseTest | `IdentityAdministrationDatabaseTest` | 23/0 | 23/0 |
| app-databaseTest | `JournalPostingDatabaseTest` | 8/0 | 8/0 |
| app-databaseTest | `KybCaseDatabaseTest` | 14/0 | 14/0 |
| app-databaseTest | `KybEndpointDatabaseTest` | 10/0 | 10/0 |
| app-databaseTest | `KycReasonScreenDatabaseTest` | 3/0 | 3/0 |
| app-databaseTest | `MerchantCaptureDatabaseTest` | 25/0 | 25/0 |
| app-databaseTest | `MonitoringIntakeDatabaseTest` | 6/2 | 6/0 |
| app-ownContainerDatabaseTest | `MonitoringRulesDatabaseTest` | 5/4 | 5/0 (final run) |
| app-ownContainerDatabaseTest | `MultiRailConservationStormDatabaseTest` | 1/1 | 1/0 (final run) |
| app-databaseTest | `PartyAndIdentitySchemaDatabaseTest` | 14/0 | 14/0 |
| app-databaseTest | `PayByBankDatabaseTest` | 32/0 | 32/0 |
| app-databaseTest | `PaymentAmbiguityDemonstrationDatabaseTest` | 5/0 | 5/0 |
| app-databaseTest | `PaymentAuthorizationDatabaseTest` | 12/1 | 12/0 |
| app-databaseTest | `PaymentCaptureDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `PaymentConservationDatabaseTest` | 1/0 | 1/0 |
| app-databaseTest | `PaymentEndpointDatabaseTest` | 15/2 | 15/0 |
| app-databaseTest | `PaymentRefundDatabaseTest` | 29/0 | 29/0 |
| app-databaseTest | `PaymentRefundEndpointDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `PaymentRiskMigrationDatabaseTest` | 5/0 | 5/0 |
| app-databaseTest | `PaymentSurfaceUnconfiguredDatabaseTest` | 1/0 | 1/0 |
| app-databaseTest | `PaymentSweeperDatabaseTest` | 12/0 | 12/0 |
| app-databaseTest | `PaymentVoidDatabaseTest` | 9/0 | 9/0 |
| app-databaseTest | `PaymentWebhookDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `PaymentWebhookTransitionDatabaseTest` | 15/0 | 15/0 |
| app-databaseTest | `PaymentsSchemaDatabaseTest` | 24/1 | 24/0 |
| app-databaseTest | `ProfileApiTest` | 2/0 | 2/0 |
| app-databaseTest | `ProviderCallbackDatabaseTest` | 9/0 | 9/0 |
| app-databaseTest | `QueueApiTest` | 2/0 | 2/0 |
| app-databaseTest | `ReportApiTest` | 2/0 | 2/0 |
| app-databaseTest | `ReversalDatabaseTest` | 7/0 | 7/0 |
| app-databaseTest | `ReviewDatabaseTest` | 13/0 | 13/0 |
| app-databaseTest | `RiskActionApiTest` | 2/0 | 2/0 |
| app-databaseTest | `RiskAssessmentApiTest` | 2/0 | 2/0 |
| app-databaseTest | `RiskCaseApiTest` | 2/0 | 2/0 |
| app-databaseTest | `RiskMonitoringEndpointDatabaseTest` | 2/0 | 2/0 |
| app-databaseTest | `RiskOverrideApiTest` | 2/0 | 2/0 |
| app-databaseTest | `RiskReportsApiTest` | 4/0 | 4/0 |
| app-databaseTest | `RoutingPolicyDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `ScreeningListApiTest` | 2/0 | 2/0 |
| app-databaseTest | `ScreeningRunDatabaseTest` | 4/0 | 4/0 |
| app-databaseTest | `SignalIntakeDatabaseTest` | 7/0 | 7/0 |
| app-databaseTest | `TransferExecutionDatabaseTest` | 6/0 | 6/0 |
| app-databaseTest | `VerificationRunDatabaseTest` | 5/0 | 5/0 |
| app-databaseTest | `WithdrawalDatabaseTest` | 16/0 | 16/0 |
| app-databaseTest | `WithdrawalHoldDatabaseTest` | 6/1 | 6/0 |
| app-databaseTest | `WithdrawalMigrationDatabaseTest` | 4/0 | 4/0 |
| app-ownContainerDatabaseTest | `AccountTakeoverDatabaseTest` | 6/0 | 6/0 |
| app-ownContainerDatabaseTest | `CrossBorderRiskSeamDatabaseTest` | 10/0 | 10/0 |
| app-ownContainerDatabaseTest | `DecisionReplayDatabaseTest` | 11/0 | 11/0 |
| app-ownContainerDatabaseTest | `MerchantAbuseDatabaseTest` | 7/0 | 7/0 |
| app-ownContainerDatabaseTest | `PaymentRiskSeamDatabaseTest` | 12/0 | 12/0 |
| app-ownContainerDatabaseTest | `RescreeningDatabaseTest` | 16/9 | 16/0 |
| app-ownContainerDatabaseTest | `RiskPolicyEndpointDatabaseTest` | 4/0 | 4/0 |
| app-ownContainerDatabaseTest | `RiskProofsDatabaseTest` | 11/1 | 11/0 |
| app-ownContainerDatabaseTest | `SystemWithdrawalDatabaseTest` | 6/0 | 6/0 |
| app-ownContainerDatabaseTest | `TransferRiskSeamDatabaseTest` | 10/0 | 10/0 (final run) |
| app-ownContainerDatabaseTest | `WithdrawalRiskSeamDatabaseTest` | 10/0 | 10/0 (final run) |
| credit-databaseTest | `CreditAssessmentDatabaseTest` | 5/0 | 5/0 |
| credit-databaseTest | `CreditDecisionConsumptionDatabaseTest` | 19/0 | 19/0 |
| credit-databaseTest | `CreditMigrationTest` | 14/0 | 14/0 |
| credit-databaseTest | `CreditPolicyVersionDatabaseTest` | 16/0 | 16/0 |
| credit-databaseTest | `CreditProfileDatabaseTest` | 8/0 | 8/0 |
| credit-databaseTest | `CreditReasonScreenDatabaseTest` | 5/0 | 5/0 |
| credit-databaseTest | `DecisionSnapshotDatabaseTest` | 16/0 | 16/0 |
| credit-databaseTest | `JdbcReservedExposureContractTest` | 6/0 | 6/0 |
| credit-databaseTest | `PolicyEvaluationDatabaseTest` | 6/0 | 6/0 |
| credit-databaseTest | `ScorecardVersionDatabaseTest` | 11/0 | 11/0 |
| crossborder-databaseTest | `CorridorAvailabilityDatabaseTest` | 5/0 | 5/0 |
| crossborder-databaseTest | `CorridorPolicyDatabaseTest` | 10/0 | 10/0 |
| crossborder-databaseTest | `CrossborderMigrationTest` | 5/0 | 5/0 |
| crossborder-databaseTest | `CrossborderReasonScreenDatabaseTest` | 3/0 | 3/0 |
| platform-databaseTest | `ApplicationRoleGrantsTest` | 6/0 | 6/0 |
| platform-databaseTest | `ColumnClassificationTest` | 5/0 | 5/0 |
| risk-databaseTest | `AlertDatabaseTest` | 9/0 | 9/0 (final run) |
| risk-databaseTest | `CaseDatabaseTest` | 13/0 | 13/0 (final run) |
| risk-databaseTest | `DecisionOverrideDatabaseTest` | 8/0 | 8/0 (final run) |
| risk-databaseTest | `ProfileDatabaseTest` | 8/0 | 8/0 (final run) |
| risk-databaseTest | `QueueDatabaseTest` | 4/0 | 4/0 (final run) |
| risk-databaseTest | `RegulatoryReportDatabaseTest` | 8/0 | 8/0 (final run) |
| risk-databaseTest | `RiskActionDatabaseTest` | 9/1 | 9/0 (final run) |
| risk-databaseTest | `RiskBornOnceArbitersDatabaseTest` | - | 1/0 (final run) |
| risk-databaseTest | `RiskEvaluationDatabaseTest` | 10/0 | 11/0 (final run) |
| risk-databaseTest | `RiskMigrationTest` | 24/0 | 24/0 (final run) |
| risk-databaseTest | `RiskPolicyDatabaseTest` | 7/0 | 7/0 (final run) |
| risk-databaseTest | `RiskSchemaMoneyColumnsTest` | 2/0 | 2/0 (final run) |
| risk-databaseTest | `VelocityWindowDatabaseTest` | 9/0 | 9/0 (final run) |

first run: suites=102 tests=895 failed=23; final: suites=103 tests=897 failed=0
