# Phase 14 Exit Review — Accounting and Financial Reporting

**Conducted:** 2026-11-06 - 2026-11-07 (`P14-DOC-001`; the run was cut off by a usage limit at about 19:09 on 2026-11-06 and resumed on 2026-11-07)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit criteria and the financial
supplement), §5 (Phase 14-specific criteria — the six originals, the six made measurable and the per-area blocks, read from
the gate at review time)
**Phase objective under review:** the one authoritative ledger turned into reportable, closable, reproducible financial
information without a second ledger — the `accounting` module and its composition in `app`: a GL as a versioned mapping over
the journal, calendar-month periods whose lock moves a posting cutoff the ledger enforces at insert, a close run of recorded
readiness checks closed under four eyes and reopened only superseding, prior-period adjustments through an `ACCOUNTING`
origin, immutable reproducible report runs with drill-down, EUR translation at pinned four-eyes valuation rate sets, the
posting rule register and accounting breaks, regulatory reporting behind a simulated adapter, write-offs, a projection
rebuild door and a reference-gap way on.

| | Outcome |
|---|---|
| The owner-authorised database pass (C5) | **Run first, on the tree as merged** - the accounting battery's database half first and alone, then every database tier of every module Phase 14's production code touched: **454 suites, 3,682 tests on the first run, 66 failures in 32 suites**. Classified one by one: **49 test defects or shared-container interferences** repaired in the tests, **2 failures of the register a test holds** (`DATA_CLASSIFICATION.md`), **four production defects** - one fixed (merchant `V013`), one fix tried and withdrawn, two postponed by the owner (`PI-013`, `PI-014`). **Not re-run whole on the final tree, by the owner's decision** (`PI-012`); the targeted re-run of the failed suites and of every suite a fix touched: **105 suites, 1,068 tests, 2 failures** (both postponed) |
| Review areas (8) | **7 `PASS`, Area 4 `PARTIAL`** (two clock-step production defects postponed) - see *What the review found* |
| Universal criteria (12) | **8 `PASS`; criterion 1 `PASS` with `P14-TST-001` postponed (PI-004); criteria 6, 7 and 11 `PARTIAL`** - each a recorded deviation naming its PI; none passed informally |
| Financial supplement (F1–F8) | **F1, F2, F3, F4, F5, F7, F8 `Met`; F6 `Met` for the counted ten-way suites, its storm half deferred (PI-004)** |
| Phase 14-specific criteria | **The six originals hold clause by clause after this review's corrections** (one sub-clause made to hold by building the entry door's namespace); the per-area **Period close** block `PARTIAL` (`PI-013`); the rest hold, several in a form their words did not anticipate (recorded) |
| *"Correct with 10 concurrent instances?"* | **`PASS` on arbitration** - every counted contention row of `PHASE_14_PLAN.md` §8.3 green against PostgreSQL in the pass; the coded lock order matches the documented one with no inversion. **Beside it, not a race between instances:** a backward step of the database's own clock misplaced three deciding instants read apart - one fixed, two postponed (`PI-013`, `PI-014`). **Not counted:** the storm (PI-004) |
| **Verdict** | **Phase 14 `COMPLETE` (2026-11-07) with recorded deviations, pending the Phase 14 → 15 transition gate**: criterion 7 `PARTIAL` (C5's standing deviation; no clean final-tree run, `PI-012`), criteria 6 and 11 `PARTIAL` (production defects postponed by the owner, `PI-013`, `PI-014`), the register's moved rows struck (`PI-015`), the storm postponed (PI-004). The transition is the owner's to open |

Conducted in the `P2-DOC-001` order, as `P13-DOC-001` was: the owner-authorised database pass first → assess → land the
corrections → re-run → rule. Two owner decisions made during the pass shaped its end (both 2026-11-06, through the
coordinator): **no full clean re-run of every tier** (re-run only the failed suites and those a fix touched - `PI-012`), and
then **stop the `app` tier's triage and postpone what remains** (`PI-013`, `PI-014`). Both are recorded where they bite.

**The assessment was seven independent read-only audits** - A1 the gate's clauses one by one against their named tests (61
sub-clauses), A2 ADR-0113…0119 decision by decision, A3 financial correctness hunted in every write path, A4 multi-instance
(the §8.3 table, the lock order, the schedules, every task's ten-instance answer), A5 security and audit (every privileged
act, every free-text field, data classification), A6 documentation drift, A7 the owner items, decisions and debt - **then six
triage engineers** for the database pass's failures, **three probe engineers** for the register walk, and **one corrector**
for the documents. Each finding was proven from the code with a file and a line, and re-verified before it was acted on.

---

## What the review found, and what it did about it

**No CRITICAL defect was found: no path creates or destroys money.** Every Phase 14 write to the ledger balances per currency
in integer minor units with explicit currency and scale, serialised by a row or advisory lock and backed by a unique; nothing
mutates history. What the review found:

**Production defects, found by the database pass** (none moves money):

1. **A BNPL fee pin refused under a backward database clock** (merchant `V012`, the Phase 12 → 13 gate's rank): the domain read
   `clock_timestamp()`, then inserted; a step back between them made the rank refuse a correctly judged pin and the acceptance
   roll back as a `500` (`BnplStormDatabaseTest` red twice). **Fixed: merchant `V013`** - the pin is stamped by its own insert,
   `GREATEST(clock_timestamp(), transaction_timestamp())`, and judged by that stamp; the resolution's read is floored at the
   transaction's start; three probes caught (§2's new `INV-MER-03` row); the storm green after it.
2. **A retired terms version can end before an offer pinned to it** under the same kind of step (lending `V030`, bnpl `V002`;
   `LoanTermsDatabaseTest#anOfferMadeDuringAnActivationStandsInsideItsVersion` red once, reproduced with `ClockStep`). **A fix
   was tried and withdrawn**: lending `V033` and bnpl `V021` ended the version after its latest offer, but in the tier it broke
   the successor's coherence (`LoanTermsDatabaseTest#aBackwardClockIsClamped`, "retired with no later ACTIVE successor starting
   where it ends"). Not a finished, green fix, so the review removed it. **Postponed: `PI-014` (1).**
3. **A month's close can read an earlier lock's run as its own** after a backward step (`JdbcCloseRunStore.latestRun` orders by
   `started_at`): the present lock's month "has no close run yet" and cannot be proposed until a four-eyes unlock and relock.
   Reproduced; **postponed by the owner: `PI-013`**, with the fix (choose the run by the lock's identity).
4. **`AccountingGaugeRefresher` cannot be restarted**: after a context stop and start every accounting gauge reads `NaN` for
   good and the unreadable-gauge alerts fire (`AlertRulesResolveTest#theAccountingRulesResolve`). **Postponed: `PI-014` (2)**,
   with the fix.

**Defects found by the audits, fixed here** (each with its test; register rows where a probe applies):

- **The ledger refused an entry dated into a locked month for every writer - but not a line** (A3-F1): the application role may
  insert `journal_line`, so raw SQL could append a balancing pair to a committed entry of a closed month and move its figures.
  **Ledger `V032`** refuses a line whose entry is dated on or before the cutoff, under the same shared key and `LDCUT`
  (`LineCutoffMigrationTest`; `PostingCutoffDatabaseTest#aLineAppendedToALockedMonthsEntryIsRefused`, green in the re-run).
- **The manual adjustment's refused self-approval left no record, and its reason and reference were unscreened** (routed by
  `P14-TSK-026`): `AdjustmentService.recordRefusal` writes `ledger.SelfApprovalRefused` `FAILED` in a transaction of its own after
  the rollback (the rebuild's shape); `AdjustmentCommand` refuses an instrument shape in the reason or the reference with a
  typed `AdjustmentReasonRefusedException`, the door's `422`. *(A5 corrected the routed premise: Hibernate Validator 9.1's
  `@NotBlank` already uses `isBlank`, so an ideographic-space reason was a `422`, never a `500`; the unscreened shapes were real.)*
  *(Corrected at the gate, 2026-11-07: these two fixes had been landed with no test of their own - the gate found it reading
  this list against the diff. `ManualAdjustmentDoorTest` (ledger) and `LedgerAdjustmentsRefusalTest` (`app`) now hold the
  record, its own transaction after the rollback, its outcome and its words, and the screen's `422` with nothing echoed;
  three probes caught, §2.)*
- **A repeat under a new key, after another request ended, was answered `409`** (`P14-TSK-024`'s re-review (a)): an unlock's
  and a reopen's end now look up the request named - `period_unlock_end_once`'s key, the reopen by id - and a repeat by its
  ender converges; hermetic and database cases (`AccountingPeriodsTest`, `PeriodClosesTest`, `PeriodCloseDatabaseTest`,
  `PeriodReopenDatabaseTest#aRepeatUnderANewKeyConvergesWhateverEndedSince`).
- **Nothing pinned "judged holding the row"** (`-024`'s (b)): `PeriodReopenDatabaseTest#anApprovalIsJudgedHoldingThePeriodRow`
  holds the withdrawal and a second proposal uncommitted while the approval naming the first waits on the period row, then
  `RequestStale` - green in the pass.
- **The close's approver was not told the set holds a reference gap** (`-026`'s (c)): `GET .../close-proposal` serves
  `valuationRateSetReferenceGaps` (additive under `v1`; `docs/api/openapi.json`).
- **The drill-down never served a line's namespace** (A1-D1, criterion 2): `GET /operator/accounting/gl/entries/{id}` now serves
  the entry's `namespace`, read from its scope as the conformance judge reads it; the free-text reference stays withheld by
  design (`RESTRICTED-FINANCIAL`).
- **`ReportingNeverWritesTest`'s adapter rule named one ledger writer** (A1-D4): it names five now (posting, reversal,
  adjustment, hold, projection rebuild), each refused by a planted adapter.
- **The classification register missed ledger `V028`'s two tables and registered `break_event` bare in two schemas**
  (`ColumnClassificationTest`, red in the pass): registered and qualified.
- **Stale words**: `accounting.yml`'s four comments ((l), (af), (am), promtool), runbook section 10's owner items, and twenty
  documents (below).

**Recorded, not changed** (each an owner item with a recommendation - *Decisions for the owner*): the lock's grace and the open
leg's month read on the instance's clock (A4-F1); step-up absent on mapping activation and break acknowledgement (A5);
route-level negative tests for some 25 routes (A5, structurally guarded); the production register's shapes never judged
against real postings (A1-D2); a FINAL trial balance's unmapped rows have no drill-down route (A1-D3); regulatory reads in a
writable transaction (A1-D5); a `MANUAL` adjustment can correct a closed month without naming it (A3-F4); an `ACCOUNTING`
adjustment can overdraw a wallet (A3-F5).

---

## The database pass (C5, owner-authorised; the phase's one pass)

**Scope** - every database suite of every module whose production code a Phase 14 task changed (`git diff 36914579 683d3694`:
accounting, app, bnpl, fx, identity, ledger, lending, merchant, platform, reconciliation, risk, settlement): `:accounting`,
`:bnpl`, `:fx`, `:lending`, `:platform`, `:reconciliation`, `:risk`, `:settlement` `databaseTest`, and `app`'s **whole** tier,
shared and own-container (identity, ledger and merchant keep their database suites in `app`). This took in every owed case of
`MUTATION_TESTING.md` §2, the battery's database half, `DashboardQueriesResolveTest`, `ColumnClassificationTest`,
`AlertRulesResolveTest#theAccountingRulesResolve`, and the first execution of accounting `V008`–`V012`, ledger `V030`–`V031`,
lending `V032`, bnpl `V020` and settlement `V018`. **`kafkaTest` was not run**: Phase 14 changed no relay, inbox, consumer or
kafka-tier suite (its events are outbox rows written by the existing writer, with no consumer).

**Before the pass** (`P14-TST-002`'s routed note): `app`'s two own-container credit suites passed `-cp` on the command line
(32,300 characters on `app`'s classpath from a checkout root of 75). `ReplayInAnotherJvm.launch` now hands the child its
coordinates, options and classpath in an argument file, as the accounting battery's launcher does
(`ReplayInAnotherJvmLaunchTest`: a real child on a classpath past 100,000 characters, its quoting kept). creditmath's and
risk's `SecondJvm` were judged and left: measured at 2,210-8,016 characters, they would need a checkout some 600 characters
deeper, past Windows' path limits first; recorded.

**The battery first and alone** (its engineer's note: a red is read as wiring first). Run 1: `initializationError` at its
first stub verdict - the stub's judge name `battery:the-suite's-verdict` breaks `CheckVerdict.JUDGED_BY` (V007's
`close_check_judged_by_a_version`): **a test defect, the battery's wiring**, fixed. Run 2: **9 tests, 0 failed** (132 s): 10,000
entries posted through the real posting service, twelve months closed four-eyes, 36 FINAL runs regenerated `IDENTICAL`, every
fact the oracle's. This is also `P14-TSK-009`'s owed volume: two whole-history scans per generation over ~22,000 lines inside
that time.

**First run** (fresh worktree; the battery's fix and the launcher fix only): **454 suites, 3,682 tests, 66 failed in 32
suites** - modules 134 suites / 1,223 tests / 20 failed (bnpl 6, lending 12, platform 2; accounting, fx, reconciliation, risk,
settlement green); `app` 320 suites / 2,459 tests / 46 failed (shared 240 / 1,730 / 26; own-container 80 / 729 / 20).

| Suites (failures) | Class | Cause | Repair |
|---|---|---|---|
| `BnplMigrationTest`, `LendingMigrationTest`, `BnplBornOnceArbitersDatabaseTest`, `LendingBornOnceArbitersDatabaseTest` (4) | test defect | the censuses never listed `V020`/`V032`'s tables, functions and uniques - their tasks ran no database suite | listed; `*_write_off_pinned` a foreign-key target; each world runs a real write-off |
| `BnplChartDatabaseTest`, `LoanChartDatabaseTest`, `AdjustmentEndpointDatabaseTest` (3), `ReconciliationResolutionDatabaseTest` (6) | test defect | ledger `V030` re-worded the adjustment trigger ("closed to free and prior-period adjustments") | the new words |
| `BnplConditionDatabaseTest`, `DelinquencyDatabaseTest`, `ReportRunDatabaseTest` (3) | test defect | a new foreign key (write-off default; a submission's source run) makes PostgreSQL refuse a plain `TRUNCATE` (`0A000`) before the table's trigger | the plain one asserted `0A000`, `TRUNCATE … CASCADE` in a rolled-back transaction reaching the table's own trigger, and the trigger pinned present in `pg_trigger` |
| `BnplWriteOffDatabaseTest` (2) | test defect | counted pay-in-4's `AT_PURCHASE` repayment; a refund moves the version only by shortening an unbilled instalment | the race's own row; a pay-in-6 default |
| `LendingProofDatabaseTest` (2), `LoanWriteOffDatabaseTest` (5), `BnplProofDatabaseTest` (2) | test defect | date-shifted fixtures broke the calendar's repayment day in November (`ScheduleEngineV1` refuses; the replay names `SCHEDULE`) | re-projected schedules |
| `LoanTermsDatabaseTest` (1) | **production defect** | a retired version can end before its offer under a backward clock step (above) | **postponed, `PI-014` (1)** |
| `ColumnClassificationTest` (2) | the register the test holds | ledger `V028`'s tables unregistered; `break_event` bare in two schemas | `DATA_CLASSIFICATION.md` |
| `FxProofDatabaseTest` (2), `FxTradeReversalRaceDatabaseTest`, `FxValuePreservationBatteryDatabaseTest`, `UnwindRetryDatabaseTest` (4) | interference | `BalanceDerivationDatabaseTest` committed 300 USD on `ROUNDING_RESIDUAL`, an FX book every later FX proof reads whole | that fixture posts to `DISPUTE_COSTS` |
| `FxProviderSourceDatabaseTest` (2) | interference | `UnwindRetryDatabaseTest` activated the FX rule set without the `@Order` every activator carries | the `@Order` |
| `SettlementAcceptanceDatabaseTest`, `ReconciledPositionResidueDatabaseTest` | interference | the rebuild deletes every unheld expectation in the database (a hand-opened leg among them); a hidden planted expectation of `SettlementExpectationDatabaseTest` | both suites own-container |
| `Phase9ReportsDatabaseTest` | interference | the FX position report caps open legs at 100 and the shared database holds hundreds | own-container (activating its own corridor rule set) |
| `SecondCorridorRailDatabaseTest`, `MerchantPayoutDatabaseTest` (3), `CloseRunDatabaseTest` M-3, `CreditDecisionDatabaseTest` | test defect | **the database's clock**: Docker Desktop's VM clock runs ~10% fast and is pulled back ~0.35 s and ~2.4-2.8 s every 25-27 s (measured with busy loops); these judged a database deadline by a JVM sleep | each waits on the database's own clock |
| `ScorecardAdministrationEndpointDatabaseTest` | interference | `CreditRiskSeamDatabaseTest` (P13-TSK-007, first run in a shared container) activates the credit seeds | `CreditRiskSeamDatabaseTest` own-container |
| `MerchantOnboardingDatabaseTest` | test defect | `accounting.gl_account.normal_balance` is a DEBIT/CREDIT classification, insert-only | its register entry with the reason |
| `AlertRulesResolveTest#theAccountingRulesResolve` | **production defect** | `AccountingGaugeRefresher` cannot restart (above) | **postponed, `PI-014` (2)** |
| `PeriodCloseDatabaseTest`, `PeriodReopenDatabaseTest`, `StatementsDatabaseTest`, `SafeguardingReturnDatabaseTest` (not-ready cases; Safeguarding's two a cascade) | test defect | the close fixture's claim loop stopped at the first empty claim, which a backward step makes before every attempt is claimable | `PeriodCloseFixture.nextClaim` waits on the database's clock (the battery's loop too) |
| `PeriodCloseDatabaseTest`, `PeriodReopenDatabaseTest`, `PriorPeriodAdjustmentDatabaseTest`, `StatementsDatabaseTest` ("no close run yet" cases) | **production defect** | the close run read by `started_at` after a backward step (above) | **postponed, `PI-013`** |
| `OperationalReportsDatabaseTest`, `StatementsDatabaseTest` (v), the reopen receipts' case | test defect | no mapping in force in its own database; the served body's shape; a send answered "not received" read as `UNKNOWN` since `P14-TSK-018` | wiring corrected |
| `BnplStormDatabaseTest` | **production defect** | merchant `V012`'s pin rank under a backward step (above) | **fixed, merchant `V013`** |
| `RiskStormTest` | unclassified (load) | C1 counted two unforced `CARD_PAYMENT` `TIMEOUT` fail-safes; a later run met a restart-cut re-send's `409`, repaired in the storm's door | green twice after; recorded in `PI-014` (5) |

**Re-runs.** By the owner's decision there was **no clean run of every tier on the final tree** (`PI-012`). The evidence after
the repairs is: (a) **the targeted re-run on the final tree** - every suite that failed, every suite a fix touched, and the
module tiers whose code a fix changed (lending, bnpl, platform whole): **105 suites, 1,068 tests, 2 failed** - `AlertRulesResolveTest`
(`PI-014` (2)) and the lending terms fix's own regression case, which then failed in lending's tier and was withdrawn with the
fix (`PI-014` (1)); (b) **each triage engineer's fresh runs on its branch**: lending 326/0 and bnpl 209/0 (with ledger `V032`);
the shared tier whole (237 suites, 1,719 tests, the ten remaining failures all in a cluster another engineer then repaired:
106/0); the thirteen accounting own-container suites together (153 tests, 4 failed - all `PI-013`; every case this review
added green); `BnplProofDatabaseTest` 12/0 and `CreditDecisionDatabaseTest` 19/0 twice; `RiskStormTest` green twice (~42 min
each); merchant's 14/0 and 42/0 and `BnplStormDatabaseTest` green once after `V013` (47 min). The appendix lists every suite.

**Production fixes and the ten-instance question.** Merchant `V013`: the pin is still written in the acceptance's transaction
under its share locks; only the instant's source changes (the insert's own stamp), so ten acceptances leave one pin and nine
`AlreadyPinned` as before - **`PASS`**. Ledger `V032`: a per-row trigger under the same shared key the entry's insert holds;
a cutoff move takes it exclusive, so a line serialises with a lock exactly as an entry does - **`PASS`**. The adjustment
door's record: written after the rollback in a transaction of its own, no shared state - **`PASS`**. The ends' convergence: a
read under the period row's lock the end already takes, by a unique key - **`PASS`**. The read fields: reads - **`PASS`**.

---

## Area 1 — Domain correctness

One `accounting` module (`platform` and `sharedkernel` only, no ledger edge) owning the GL mapping, periods, the close run,
breaks, report runs, valuation sets and regulatory returns; the ledger keeps the journal, the cutoff, the `ACCOUNTING` origin
and the projection rebuild; lending and bnpl own their write-offs. The CLAUDE.md distinctions hold (journal entry vs report
figure; a break vs a correction; a GL account vs a ledger account). Built beyond the plan by the owner's rulings: write-offs
(C6), the close's ends and the approver's read (`-024`), a source's dates (`-025`), the rebuild and the reference gap
(`-026`). **`PASS`.**

## Area 2 — Financial correctness: a posting end to end

A3 walked a prior-period adjustment: a EUR 12.00 fee overcharged in a closed March → `POST .../prior-period-adjustments`
(`ACCOUNTANT`) → an `ACCOUNTING`-origin ledger proposal dated in the open month (`V030`'s pairing and one-date `CHECK`s) → a
`FINANCIAL_CONTROLLER`'s `MULTI_FACTOR` approval naming the posted set → `AdjustmentService.approvePriorPeriod` (four eyes,
`requireOpen` under the shared cutoff key, the entry built from the stored lines) → DR `FEE_REVENUE` / CR `CUSTOMER_WALLET`
1200 at scale 2 → the projection by relative increment → April's FINAL trial balance through the pinned mapping → April's
statement notes' `priorPeriodCorrections` naming March and its revision; March's FINAL output unchanged by a byte → the
drill-down's keyset and, now, the entry's namespace → the safeguarding return's wallet line by account. Balanced per currency
at every hop; nothing posted outside the journal; reversal by a further adjustment. **`PASS`.**

## Area 3 — Boundary integrity and multi-instance correctness

`AccountingModuleIsolationTest` and every sibling isolation test; `ReportingNeverWritesTest` (now five ledger writers);
`NoCustomerSurfaceReadsAccountingTest`. The coded lock order matches `DISTRIBUTED_EXECUTION.md` §3 for every Phase 14
transaction (A4: the lock with the previous period `FOR SHARE` then the period `FOR UPDATE`, mapping shared, the cutoff
exclusive; the ends a subset of the approvals' order; the rebuild in the posting's order); no inversion. Every §8.3 row's
named test exists; all but rows 6 (sequential by design) and 10 (the named method sequential, its sibling
`#tenRaisersOneBreak` races - the plan corrected) race for real, and **every one ran green against PostgreSQL in the pass**
(first run or targeted re-run). `AccountingCloseSchedule` is leaderless and the 32nd lease-protected scheduler. **`PASS` on
arbitration**; the clock-step findings are Area 4's.

### Every task's ten-instance answer, re-checked

Each names a test that exists; `-001`, `-002`, `-007`, `-015`, `-020`, `-023` are reads or determinism. `-025`'s "a source
admitted, retired or activated while a month is judged" and `-026`'s gap capture racing a lock have no racing test (A4-F6,
recorded). Every other answer's race ran green in the pass.

## Area 4 — Failure behaviour

Traced: **a posting racing a lock** (the shared key against the exclusive move; ten posters and a lock leave no entry after the
cut - green) and **a lost response on a regulatory send** (`SUBMITTED` committed before the call, the inquiry by the
submission's id, never a second send while the month has moved - green). **But the pass found the database's own clock
stepping back by seconds**, and three deciding instants read in one statement and compared with one written in another were
misplaced by it: the BNPL pin (fixed), the terms retirement (`PI-014`) and the close run's "latest" (`PI-013`), the last able to
stop a month's close until a four-eyes unlock and relock. **`PARTIAL`** - two postponed by the owner.

## Area 5 — Security and audit

Every Phase 14 privileged act enumerated (A5: some 45 routes): permission, role, step-up, four eyes by a domain check **and** a
named database `CHECK` on every approval, every self-approval refusal recorded `FAILED` in a transaction of its own (the
ledger adjustment's now too), every audit action in the catalogue, every error code in the contract with its status, every
route in the permission register. Every person-written reason, disclosure and attestation is screened (the adjustment's now
too). Recorded for the owner: step-up absent on mapping activation, break acknowledgement and resolution (the ordinary
valuation approval's absence is pinned deliberate); route-level negative tests exist for the four-eyes approvals and the
regulatory doors, the other ~25 routes are held by the register's equality and `RoleNameTest`'s exact grants (as Phase 13's
were, "by family"); two classification levels inconsistent. **`PASS` after corrections.**

## Area 6 — Test quality

The register walk (below): **103 rows performed again, every probe caught**; 330 struck as evidence of today (`PI-015`); every
`INV-ACC-*` keeps a demonstration standing today. The review's own probes are recorded in §2: merchant `V013`'s three; at the
gate, the adjustment door's screen, its refusal record and that record's outcome (three, each caught in the ledger's and the
`app`'s new suites) and ledger `V032`'s trigger on its text pin (caught; its database probe not performed - `PI-015`). The ends'
convergence, the two read fields and the namespace carry their tests (hermetic, and database cases green in the re-run) and
**no probe of their own** - the rows performed again on `PeriodCloses`, `AccountingPeriods` and `AdjustmentService` show those
files' older properties still caught on the new bytes, not the new behaviour. *(Read "this review's production changes are
hermetically probed by the rows performed again" until the gate, which found it overstated.)* **`PASS`**, the struck rows and
those probes owed.

## Area 7 — Documentation accuracy

Twenty documents corrected against the code, each correction dated in place: ADR-0113…0119 and the ADR index;
`DECISIONS.md`; `MODULE_ARCHITECTURE.md`, `BOUNDED_CONTEXTS.md`, `DISTRIBUTED_EXECUTION.md`, `TESTING.md`,
`DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md`; `FINANCIAL_INVARIANTS.md`, `GLOSSARY.md`, `DOMAIN_MODEL.md`;
`CAPABILITY_MAP.md`, `ROADMAP.md`; `PHASE_14_PLAN.md` (§2.2 ratified, §8.3 row 10, §25 errata); `OPERATIONS_RUNBOOK.md`
section 10; `infra/prometheus/rules/accounting.yml`; `docs/api/openapi.json`. Clauses met in a form their words did not
anticipate are recorded in the note after Phase 14's criteria in `PHASE_GATES.md`. **`PASS` after corrections.**

## Area 8 — Architectural debt, and the Phase 15 boundary

Nothing of `PHASE_14_PLAN.md` §21 is built: no closing entries posted (C8), no unrealised FX posted (C7), no provisioning or
EIR (C6), no real regulatory channel (C4), no separate database role (C10's Phase 15 half). The debt and the postponed issues
are below. **`PASS`.**

---

## The register walk

`MUTATION_TESTING.md` §2 walked by script: every hash token matched against the sha256 of every committed version of every
file (19,108 blobs). **At the review's tree, of 2,777 rows:** 605 on present bytes; **433 on older bytes** - 81 moved by this
review's own changes, 22 on the runbook three branches left unperformed, and the rest moved by earlier tasks (`P14-TSK-020`'s
finding across the whole register; `PeriodClosesTest`'s twenty-four were current after `-024`); 15 on bytes never committed;
1,713 older-form rows with no hash.

- **Performed again: 103 rows, 104 probes, every one caught** by the assertion it aims at - the 35 on `PeriodCloses` and its
  stores, the 36 on `PeriodCloseDesk`, `AccountingPeriods`, `AdjustmentService` and `ReportingNeverWritesTest`, the 10 on
  `accounting.yml` and the 22 on the runbook (the routed rows: section 2's "routed to `P14-DOC-001`"). Row 2807's second probe
  survived again, the equivalent mutant it records. Each row carries its result, its translation to the present code where
  the code moved, and its restored sha256.
- **Struck as evidence of today: 330 rows** (215 of Phase 14's tasks, 78 of Phase 13's, 19 of Phase 12's, 5 of Phase 11's, 13
  naming no task), each marked in place; their procedures stand as of their day; performing them again is `PI-015`.
- **Left: 15 rows** on uncommitted bytes (marked), and **1,713 rows with no hash** (not walkable; §3's Phase 14 block says how
  they stand).
- **Database halves:** about a hundred rows say their database half was unproven until the pass. The pass ran those suites;
  **no database probe was performed** by this review - owed under `PI-015`, said so in §3.
- **`MutationDemonstrationTest`'s §4 rule:** with Phase 14 `COMPLETE`, every `P14-TST-*` item needs a §4 row. `P14-TST-002`
  has its seventeen probes; **`P14-TST-001`'s row records the deferral, not a demonstration** - `P13-TST-001`'s convention,
  which the owner's acceptance of `P13-DOC-001`'s recommendations kept.
- **The flip** (2026-11-07, at the gate): Phase 14's status line recorded `COMPLETE` arms the guard for every `Phase: 14`
  invariant and every `P14-TST-*` item. With `INV-ACC-07`'s five §2 rows (the `Phase: 14` invariant with the fewest) and
  `P14-TST-001`'s §4 row withheld (register sha256 `01924d16…0a131180` → `8ae1fda9…744a77db`), `:app:architectureTest --rerun
  --tests com.finapp.app.architecture.MutationDemonstrationTest` failed **2 of 9** - the invariant case reporting
  `(currently 14)` and naming exactly `["INV-ACC-07"]`, the test-item case naming exactly `["P14-TST-001"]`; the register
  restored byte-identical (sha256 `01924d16…0a131180`, verified; the blob equal to the committed one).

## The storm — postponed (PI-004)

`P14-TST-001` is `POSTPONED` with every later phase's storm. This review claims nothing it was to show. **The evidence the
phase has:** the hermetic proofs and the register; the database pass's first run and targeted re-runs; the battery's two
halves (two JVMs hermetic; the database half `IDENTICAL` over 36 FINAL runs); the counted §8.3 races. **What it lacks:** the
storm's counted interleavings at volume, its probes and its gate. The coordinator reminds the owner at this exit (PI-004).

---

## The twelve universal exit criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | `PASS` - with `P14-TST-001` postponed (PI-004) | 27 tasks and `P14-TST-002` `COMPLETE`; the storm's deferral recorded |
| 2 | Architectural boundaries respected | `PASS` | isolation tests, `ReportingNeverWritesTest`, `NoCustomerSurfaceReadsAccountingTest`; the fleet architecture tier on the final tree, 294/0 (*Tests*) |
| 3 | Required invariants tested | `PASS` | every `INV-ACC-*` demonstrated on present bytes or in-suite; 103 rows performed again here; the struck rows owed (`PI-015`) |
| 4 | Failure cases handled | `PASS` | `PHASE_14_PLAN.md` §15 each a test or a rationale (A1, A2); the clock-step defects are criterion 11's |
| 5 | Security requirements implemented | `PASS` | Area 5; the adjustment door's two gaps closed here |
| 6 | Observability exists | **`PARTIAL`** | meters, alerts (`promtool`: 136 rules, every file `SUCCESS`), runbook section 10 held to the code; **but the accounting gauges cannot survive a context restart** (`PI-014` (2), `AlertRulesResolveTest` red) |
| 7 | Integration tests pass | **`PARTIAL`** - the standing C5 deviation | the pass ran on the tree as merged (454 suites); **no clean run of every tier on the final tree, by the owner's decision (`PI-012`)**; four suites still red or intermittent (`PI-013`, `PI-014`); `kafkaTest` not run; the storm postponed |
| 8 | Documentation reflects reality | `PASS` after corrections | Area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` | by this review |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` | ADR-0113…0119 `Accepted` after correction |
| 11 | No unresolved critical issues | **`PARTIAL`** | no CRITICAL; **three production defects found by the pass stand postponed by the owner** - the close run after a backward clock step (`PI-013`: a month that cannot be proposed until a four-eyes unlock and relock), the terms retirement (`PI-014` (1)), the gauges (`PI-014` (2)); none moves money |
| 12 | Formal phase review conducted | `PASS` | this document |

**Criterion 7.** The owner decided (C5, ratified 2026-11-06) to keep skipping the fleet-wide database tiers per task with one
owner-authorised pass before this review. The pass ran first, on the tree as merged, and found real defects; the owner then
decided against a clean re-run of every tier (`PI-012`) and to postpone what the triage had not finished (`PI-013`, `PI-014`).
A recorded deviation, not a pass.

## The financial supplement F1–F8

| | Verdict |
|---|---|
| F1 Trial balance zero per currency | `Met` - `TRIAL_BALANCE`'s per-currency zero and GL = ledger, green against PostgreSQL (`TrialBalanceRunDatabaseTest`, the battery's 36 FINAL runs); a planted imbalance fails only its own transaction |
| F2 Balances reproducible | `Met` - the rebuild door rebuilds from the journal; FINAL runs regenerate `IDENTICAL`; `PROJECTION_VERIFIED` |
| F3 Idempotency at the financial boundary | `Met` - the prior-period adjustment, the write-offs and the rebuild keyed; their replays green |
| F4 Reversal paths; history immutable | `Met` - corrections are prior-period adjustments; every accounting table append-only by trigger; ledger `V032` closes the line gap |
| F5 Duplicate external events | `Met` - a regulatory answer and a repudiation are each recorded once; breaks born once |
| F6 Concurrency for every contended resource | `Met` for the counted §8.3 races; the storm half deferred (PI-004) |
| F7 No floating point | `Met` - `NoFloatingPointMoneyRulesTest` over accounting |
| F8 Reconciliation | `Met` - GL = ledger, rule conformance, source completeness and the reconciliation disclosure are close checks |

---

## The Phase 14-specific criteria

Read clause by clause by A1 (61 sub-clauses) and re-read after this review's corrections and the pass.

| | Sub-clauses | Hold | Hold in another form | Partial | Rest on the storm | Do not hold |
|---|---|---|---|---|---|---|
| The six, made measurable | 27 | 19 | 8 | 0 | 0 | 0 *(2.2 the entry's namespace - built here; the reference withheld by design)* |
| Per area | 34 | 23 | 9 | 1 *(Period close: `PI-013`)* | 1 *(the storm's counts, PI-004)* | 0 *(DOC.1/DOC.3 - this review accepted the ADRs and recorded C1–C13)* |

*(Corrected at the gate, 2026-11-07: the per-area row read "22 / 9 / 1 / 0" with no storm column, which summed to 32 of 34;
recounted from A1's table after the pass - of the four per-area sub-clauses resting on a database suite, PC.4, MI.1 and MI.2
hold and TE.2 holds in another form; TE.3 holds with the battery's database half green; DOC.1 and DOC.3 hold; PC.2 is the
partial; MI.3 rests on the storm. Each clause in another form is named in the note after Phase 14's criteria in
`PHASE_GATES.md`.)*

1. *Trial balance zero per currency, continuously verified* - **holds**: the gauge (`INV-ACC-01`) and every FINAL trial balance
   zero and equal to the ledger, green in the pass; the planted imbalance case green; the battery's oracle equal over 10,000
   entries, five currencies, twelve months, against PostgreSQL and in two JVMs.
2. *Any GL figure drills down to its journal lines* - **holds in another form**: every figure's lines through the door; each
   line names its entry, and the entry now its namespace (built here); the source reference is withheld by design as
   free text; a FINAL trial balance's unmapped rows have no route (A1-D3, recorded - such a month cannot close).
3. *Posting into a closed period is rejected; the prior-period path is implemented* - **holds**: refused at insert for every
   writer, entries and now lines (`V032`); ten posters and a lock leave no entry after the cut; the accrual floored; the
   prior-period adjustment green against PostgreSQL.
4. *Reports are reproducible* - **holds**: 36 FINAL runs `IDENTICAL` against PostgreSQL; two JVMs in the hermetic half; the
   perturbations flip exactly their rows; a reopen supersedes, never deletes.
5. *Unreconciled breaks and suspense are disclosed at close* - **holds**: the disclosure check and the statements' notes, green
   against PostgreSQL.
6. *No reporting component holds write access to ledger tables* - **holds in C10's form**: no ledger edge, read-only snapshots,
   a planted write refused `25006`, the guard naming every ledger writer; a separate database role is Phase 15's.

**Per area:** rule correctness holds, its "every entry conforms" judged at each real close (the production register never
judged against real postings in a test - A1-D2, an owner item); traceability holds with the namespace; ledger reconciliation,
multi-currency, reporting, security and testing hold; **period close `PARTIAL`** - every edge green, but a backward clock step
can stop a month's proposal (`PI-013`); multi-instance - the counted rows green, the storm's counts open (PI-004);
documentation - this review.

---

## ADR-0113 … ADR-0119: accepted

Each read decision by decision against the code (A2), corrected in place with dated notes, then `Accepted` (the index
agreeing). The material corrections: **ADR-0113** - regulatory submissions hold amounts copied from FINAL runs; **ADR-0114** -
the lock is one person's act; "evented" is a history row; twelve back-dating rules; the ends converge by the request named;
the `v1` contract change confirmed; **ADR-0115** - the content checks are the completion trigger's, the hash verified by the
database, the PROVISIONAL cut `created_at <= cut_at`, FINAL admitted for any period not `OPEN`; **ADR-0116** - a set is
captured while `LOCKED` and pinned only by a translated run; the translated layout accepted as built (-026's (g) the one open
point); superseded in part by ADR-0119; **ADR-0117** - catalogue version 4, 61 rules; `SOURCE_DELAYED` never raised; a break's
generation in its key; M-7 ruled; **ADR-0118** - the submission's approval, send and non-delivery step up; the footings are the
reference configuration; **ADR-0119** - accepted; (c) built here.

## C1–C13, as the owner ratified them

| # | Decision | Ratified |
|---|---|---|
| C1 | EUR functional = reporting; transaction-currency columns always shown | 2026-11-06 |
| C2 | A reference chart as versioned data, mapped by purpose (+ owner kind); `gl_code` unwritten | 2026-11-06 (codes for qualified review) |
| C3 | The ledger refuses a posting dated into a locked period; system posters floor; adjustments refused at approval | 2026-10-30, widened to every back-dating rule |
| C4 | A simulated reference regulatory format; no legal obligation | 2026-11-06 |
| C5 | Criterion 7's standing deviation; one owner-authorised pass; the storm postponed | 2026-11-06 (run here; `PI-012`) |
| C6 | Write-off four-eyes with the headroom fix; provisioning and EIR deferred | 2026-11-04 |
| C7 | Report-time translation only; no unrealised FX posted | 2026-11-06 |
| C8 | No closing entries; result and retained earnings computed | 2026-11-06 |
| C9 | Calendar months, closed in order, lockable after a 2-business-day grace | 2026-11-06 |
| C10 | Reporting never writes: read-only transactions, no ledger edge, a static guard; a separate role in Phase 15 | 2026-11-06 |
| C11 | Accounting-owned breaks in reconciliation's shape | 2026-11-06 |
| C12 | `ACCOUNTANT` proposes, `FINANCIAL_CONTROLLER` approves, `REGULATORY_REPORTER` prepares and submits; four eyes by `CHECK` | 2026-11-06 |
| C13 | The reference safeguarding return | 2026-11-06, as corrected 2026-11-05: it subtracts `principalUnrepaid`; `committed` a line beside it |

**Still for a qualified reviewer before production** (the owner's words: "what still needs a qualified reviewer before
production stays so"): C3, C6, C7, C8; the write-off notice's wording; counterparties on a statement's face; the income
statement's period-end rate; the safeguarding return's regime (`PI-011`). **Before production, too**: a factor enrolled for
every `LEDGER_OPERATOR` and `FINANCIAL_CONTROLLER`; the channel behind "contact us" named and staffed (`PI-007`) and the notice's
invitation to repay decided with `PI-006`; `PI-005`…`PI-007` before production write-offs; `PI-008`…`PI-010` before any real
regulatory channel; `PI-011` before any real regime.

---

## The routed notes, ruled

| From | Note | Ruling |
|---|---|---|
| `P14-TSK-026` | ADR-0119; its two owed suites | Accepted; `ValuationReferenceGapDatabaseTest` 3/0 and `ProjectionRebuildDatabaseTest` green in the pass |
| `-026`'s fix round (1), (2) | the adjustment's unaudited self-approval; unscreened reasons | **Built** (above) |
| `-026`'s fix round (3) | twenty runbook rows on master's bytes | **Performed again** on the merged runbook (22 rows) |
| `-026` (c) | the close's approver not told of a gap | **Built** |
| `P14-TSK-024`'s re-review (a), (b) | a repeat under a new key; "judged holding the row" | **Built**, database cases green |
| `-024` (g) | `v1` bodies on unlock and reopen approvals | **Confirmed** under the owner's standing acceptance, no client existing |
| `P14-TST-002` | the battery's database half; the credit launchers' `-cp` | Run first and alone (green after a wiring fix); **moved to an argument file**, tested |
| `P14-TSK-025` | settlement `V018`; `accounting.yml`'s (l) | `V018` first executed green; **comment corrected** |
| `P14-TSK-023`, `-020` | the whole-register walk | **Done** (above) |
| `-020` (aj), (al) | `promtool`; the telemetry package once | `promtool`: 7 files, 136 rules, `SUCCESS`; the telemetry package: *Tests* below |
| `-019` (h) | `LendingReports.capital` by value date beside the return by posting date; the committed term's latest edge by instant | **Read and recorded**: two reports with two declared bases, each disclosed; the instant read is the clock-step class - accepted, the same remedy as `PI-013`'s when it is built |
| `-022` | `StatementLine.reconciliationAttribution` has no production reader | **Kept**: the ledger's fact stands and its suites read it; dropping it is a clean-up for the next statement change |
| `-009` (2) | a volume for the whole-history scans | The battery's database half: ~22,000 lines, 36 FINAL generations, 132 s with posting |
| Owner decisions | `PI-005`…`PI-011`; the qualified-reviewer list; production steps | Recorded (above) |

## Decisions for the owner

Every recommendation below the owner's standing acceptance covers is recorded as accepted; **⚑ marks what needs the owner's
own decision**.

| Item | Recommendation |
|---|---|
| ⚑ `PI-012` | Run every database tier, `kafkaTest` included, on the tree as it stands at the next phase's pass |
| ⚑ `PI-013` (production) | Fix before a month is closed in production: choose the close run by the lock's identity; `LATEST_CLOSE_SQL` the same |
| ⚑ `PI-014` (1) (production) | Fix the terms retirement with the successor's coherence kept; check bnpl's and risk's identical stamps |
| ⚑ `PI-014` (2) (production) | Fix the refresher (executor made in `start()`) before relying on gauges across a restart |
| ⚑ `PI-015` | Perform the 330 struck rows and the database halves at the next pass, or strike them for good |
| A4-F1 | The lock's grace and the open leg's month on the instance's clock: read `store.today` after the lock (small); until then a skewed instance can lock within its skew of the grace boundary, bounded by `V028`'s "never over today" |
| A5 | `MULTI_FACTOR` on the GL mapping's activation and the break acknowledgement's approval; align the adjustment's and the write-offs' reason classifications to `RESTRICTED-PII`; screen a GL account's name |
| A1-D2 | A shared-tier census judging every committed entry against the production register (ordered last, like the residue judge) |
| A1-D5 | Run the regulatory lineage reads read-only |
| A3-F4, F5 | Make a closed month's correction take the prior-period door (refuse a `MANUAL` line dated after a close for a closed month's error), or accept it as a process control; an `ACCOUNTING` adjustment's availability on a wallet |
| `-024` (a)–(g), `-025` (a)–(f), `P14-TST-002` (a)–(f), `-026` (a), (b), (d)–(g) | Accepted at their recommendations (the owner's standing acceptance); `-026` (g) decided with the translated layout before the first translated FINAL |

## Debt register (Phase 14's, after this review)

Every row is in `CURRENT_STATE.md` §Known Architectural Debt or `POSTPONED_ISSUES.md`.

| Debt | Owner |
|---|---|
| No clean final-tree run of every database tier (`PI-012`) | The next phase's database pass |
| The close run after a backward clock step (`PI-013`) | The next phase's database pass |
| The remaining red suites, two production defects (`PI-014`) | The next phase's database pass |
| The register's struck rows and database halves (`PI-015`) | The next phase's database pass |
| The storm (PI-004) | The post-phase clean-up |
| Time decided on an instance's clock: the lock's grace (A4-F1) | Phase 15 at the latest |
| A separate database role for reporting (C10) | Phase 15 |
| Gauges read on the scrape thread; a rule on `up` (`-020` (ak), (am)) | Phase 15 |
| Provisioning and EIR (C6) | A later phase |

---

## Tests, every count from a fresh run

The database pass: first run 454 suites, 3,682 tests, 66 failed; targeted re-run 105 suites, 1,068 tests, 2 failed; the
triage engineers' runs above (appendix). `promtool check rules` (prom/prometheus:v3.14.0, the version `compose.yaml` pins): 7
files, 136 rules, `SUCCESS`. The build check of this review's corrections: ledger 178/0, accounting 725/0, lending 166/0,
bnpl 602/0, `:app:test` over accounting, ledger, api 549/0, `:app:architectureTest` 237/0.

**The telemetry package once** (`P14-TSK-020`'s (al), 2026-11-07): `:app:test --tests "com.finapp.app.telemetry.*"` - **34
classes, 199 tests, 0 failed** (32 min 49 s); the package's seven database suites are the pass's (`AlertRulesResolveTest`'s
accounting case among them, red, `PI-014` (2)). A first attempt, on 2026-11-06, was interrupted when another Gradle run
started in the same worktree (199 passed before the `NoSuchFileException`); it is not counted.

**The flip** (2026-11-07): `:app:architectureTest --rerun --tests com.finapp.app.architecture.MutationDemonstrationTest`,
2 of 9 red as intended, then the register restored byte-identical (*The register walk* above).

**The fleet hermetic and architecture tiers once, on the final tree** (2026-11-07, `./gradlew test architectureTest
--continue`, the gate's records committed at `c1062b18`, 55 min 23 s; every XML written by that run): **hermetic - 24
modules, 917 classes, 6,247 tests, 0 failed, 0 skipped** (accounting 725, accounts 10, `app` 1,903, bnpl 602, checkout 54,
consent 14, credit 125, creditmath 100, crossborder 19, fx 48, identity 144, kyc 149, ledger 178, lending 166, merchant 190,
party 23, paymentmethods 46, payments 306, platform 187, reconciliation 192, risk 570, settlement 236, sharedkernel 225,
transfers 35); **architecture - 24 modules, 63 classes, 294 tests, 0 failed** (`app` 237). The doc guards over this review's
corrections (`AdrRegistersAreReconciledTest`, `DomainGlossaryTest`, `ArchitectureRulesAreDocumentedTest`, `TestTaxonomyTest`)
and `MutationDemonstrationTest`, armed for Phase 14, are among them. The database tiers and `kafkaTest` were not run again
(`PI-012`).

**After the fleet run, the gate's two test classes** (2026-11-07; the only code the final tree adds to the tree the fleet
ran - test code, no production change): `ManualAdjustmentDoorTest` and `LedgerAdjustmentsRefusalTest`, then their four
probes (§2), then on the restored bytes `:ledger:test` **180/0** (the module whole), `:ledger:architectureTest` 2/0,
`:app:test` over `com.finapp.app.ledger.*` and `com.finapp.app.architecture.*` **248/0**, `:app:architectureTest` 237/0; and,
after their register rows were written, `MutationDemonstrationTest` once more, 9/0.

## What the phase produced

One module (`accounting`, twelve migrations) and its composition in `app`; ledger `V028`–`V032`, lending `V032`, bnpl
`V020`, settlement `V018`, merchant `V013`, identity `V025`; the GL mapping, periods and the posting cutoff, the close run and
its nine checks, four-eyes close, rejection, reopen and their ends, prior-period adjustments, report runs and eleven FINAL
definitions plus two operational, translation at pinned valuation sets, the posting rule register and accounting breaks,
regulatory reporting and the reference safeguarding return, write-offs, the projection rebuild and the reference gap; meters,
32 alerts and runbook section 10; the battery - and, from this review, the line cutoff, the adjustment door's record and
screen, the ends' convergence, the two read fields, the merchant pin's stamp, the credit launchers' argument file, and the
database pass's repairs.

## What happens next

**The Phase 14 → 15 transition is the owner's to open** - this review marks nothing `READY` and initialises nothing of Phase
15. Its agenda: the ⚑ items above, `PI-012`…`PI-015` at the next phase's database pass at the latest, the qualified-reviewer
list before production, and PI-004's reminder - the storms of Phase 14 and every later phase are owed after the project's
phases.

---

## Appendix — the database pass, suite by suite

Every suite with its first run and its targeted re-run (tests/failed), read from each run's own JUnit XML. "-" is a suite not
in that run. `TermsRetirementClockDatabaseTest` was the withdrawn fix's regression case (removed with it). The triage
engineers' further runs are named in *The database pass*.

| Module | Tier | Suite | First run (tests/failed) | Targeted re-run (tests/failed) |
|---|---|---|---|---|
| accounting | shared | `AccountingMigrationTest` | 5/0 | - |
| app | shared | `AccountClosingDatabaseTest` | 7/0 | - |
| app | shared | `AccountEndpointDatabaseTest` | 8/0 | - |
| app | shared | `AccountMonitoredPartiesDatabaseTest` | 1/0 | - |
| app | own-container | `AccountTakeoverDatabaseTest` | 6/0 | - |
| app | own-container | `AccountingBatteryTest` | 9/0 | 9/0 |
| app | shared | `AccountingBreakDatabaseTest` | 10/0 | - |
| app | shared | `AccountingPeriodDatabaseTest` | 7/0 | - |
| app | shared | `AccountingSourceProofsDatabaseTest` | 1/0 | - |
| app | shared | `AdjustmentEndpointDatabaseTest` | 21/3 | 21/0 |
| app | shared | `AdjustmentProposalSchemaDatabaseTest` | 9/0 | - |
| app | shared | `AlertRulesResolveTest` | 11/1 | 11/1 |
| app | shared | `AlertSuppressionApiTest` | 2/0 | - |
| app | shared | `AmendmentApiTest` | 3/0 | - |
| app | shared | `AuditNamesTheActorDatabaseTest` | 3/0 | - |
| app | shared | `AuthenticationCostsTheSameDatabaseTest` | 2/0 | - |
| app | shared | `AuthenticationEndpointDatabaseTest` | 8/0 | - |
| app | shared | `AuthenticationFailsClosedDatabaseTest` | 4/0 | - |
| app | shared | `AuthenticationIssuesASessionDatabaseTest` | 14/0 | - |
| app | shared | `AuthenticationLockoutDatabaseTest` | 12/0 | - |
| app | shared | `BalanceDerivationDatabaseTest` | 7/0 | 7/0 |
| app | shared | `BalanceProjectionDatabaseTest` | 5/0 | - |
| app | shared | `BankAccountRegistrationDatabaseTest` | 6/0 | - |
| app | own-container | `BankStatementCashDatabaseTest` | 5/0 | - |
| app | own-container | `BatchRepudiationDatabaseTest` | 20/0 | - |
| app | shared | `BeneficiaryDatabaseTest` | 7/0 | - |
| app | shared | `BeneficiaryEndpointDatabaseTest` | 7/0 | - |
| app | own-container | `BnplAcceptanceApiTest` | 4/0 | - |
| app | shared | `BnplApplicationApiTest` | 5/0 | - |
| app | own-container | `BnplApplicationDatabaseTest` | 16/0 | - |
| app | own-container | `BnplCapitalDatabaseTest` | 3/0 | - |
| app | own-container | `BnplCollectionLedgerDatabaseTest` | 8/0 | - |
| app | own-container | `BnplCreditExposureDatabaseTest` | 11/0 | - |
| app | own-container | `BnplDisputeApiTest` | 14/0 | - |
| app | shared | `BnplMerchantEndpointDatabaseTest` | 3/0 | - |
| app | own-container | `BnplMerchantPayoutDatabaseTest` | 9/0 | - |
| app | shared | `BnplMerchantReadApiTest` | 1/0 | - |
| app | own-container | `BnplMerchantReadDatabaseTest` | 8/0 | - |
| app | shared | `BnplOfferApiTest` | 3/0 | - |
| app | own-container | `BnplOneKeyRaceDatabaseTest` | 18/0 | - |
| app | own-container | `BnplOriginationDatabaseTest` | 11/0 | - |
| app | own-container | `BnplOutboxAtomicityDatabaseTest` | 1/0 | - |
| app | own-container | `BnplPayoffApiTest` | 3/0 | - |
| app | own-container | `BnplProductDecisionDatabaseTest` | 5/0 | - |
| app | own-container | `BnplProofApiTest` | 3/0 | - |
| app | own-container | `BnplProofDatabaseTest` | 12/2 | 12/0 |
| app | own-container | `BnplPurchaseDatabaseTest` | 20/0 | - |
| app | own-container | `BnplRefundApiTest` | 6/0 | - |
| app | own-container | `BnplRepaymentApiTest` | 3/0 | - |
| app | own-container | `BnplReportsDatabaseTest` | 3/0 | - |
| app | own-container | `BnplServicingApiTest` | 6/0 | - |
| app | own-container | `BnplStormDatabaseTest` | 1/1 | - |
| app | shared | `BnplTermsEndpointDatabaseTest` | 4/0 | - |
| app | shared | `BnplWriteOffApiTest` | 4/0 | - |
| app | own-container | `BnplWriteOffLedgerDatabaseTest` | 6/0 | - |
| app | shared | `BureauCollectionDatabaseTest` | 20/0 | - |
| app | own-container | `BureauSelectionDatabaseTest` | 7/0 | - |
| app | shared | `ChangePasswordDatabaseTest` | 5/0 | - |
| app | shared | `ChangePasswordMfaDatabaseTest` | 1/0 | - |
| app | shared | `ChargebackAccountingDatabaseTest` | 21/0 | - |
| app | shared | `ChargebackRatioReportDatabaseTest` | 5/0 | - |
| app | shared | `CheckoutExpiryDatabaseTest` | 8/0 | - |
| app | own-container | `CheckoutFinancingDatabaseTest` | 10/0 | - |
| app | shared | `CheckoutFlowDatabaseTest` | 65/0 | - |
| app | shared | `CheckoutSessionDatabaseTest` | 15/0 | - |
| app | shared | `CloseRunDatabaseTest` | 18/1 | 18/0 |
| app | shared | `CloseRunStallDatabaseTest` | 6/0 | - |
| app | shared | `CollectionsCaseDatabaseTest` | 6/0 | - |
| app | shared | `ConsentEndpointDatabaseTest` | 10/0 | - |
| app | shared | `ConsentGateDatabaseTest` | 4/0 | - |
| app | shared | `ConsentHistoryDatabaseTest` | 7/0 | - |
| app | shared | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` | 1/0 | - |
| app | shared | `ConstraintFailuresDoNotLeakRowsDatabaseTest` | 3/0 | - |
| app | shared | `ContactChannelSecondVerificationDatabaseTest` | 3/0 | - |
| app | shared | `CorridorAdministrationEndpointDatabaseTest` | 5/0 | - |
| app | shared | `CorridorSettlementToCashDatabaseTest` | 3/0 | - |
| app | shared | `CorridorSourceDatabaseTest` | 4/0 | - |
| app | shared | `CounterpartyChartDatabaseTest` | 5/0 | - |
| app | shared | `CounterpartyScreeningDatabaseTest` | 15/0 | - |
| app | shared | `CredentialConcurrencyDatabaseTest` | 4/0 | - |
| app | shared | `CredentialNeverLeaksDatabaseTest` | 4/0 | - |
| app | shared | `CredentialSchemaDatabaseTest` | 14/0 | - |
| app | shared | `CredentialUpgradeConcurrencyDatabaseTest` | 2/0 | - |
| app | shared | `CredentialVerificationDatabaseTest` | 9/0 | - |
| app | shared | `CredentialVerifierLogsNothingSensitiveDatabaseTest` | 1/0 | - |
| app | own-container | `CreditDecisionDatabaseTest` | 19/1 | 19/0 |
| app | own-container | `CreditDecisionStormDatabaseTest` | 1/0 | - |
| app | shared | `CreditLineDrawApiTest` | 4/0 | - |
| app | shared | `CreditPolicyEndpointDatabaseTest` | 4/0 | - |
| app | shared | `CreditRiskSeamDatabaseTest` | 2/0 | 2/0 |
| app | shared | `CrossBorderBeneficiaryDatabaseTest` | 14/0 | - |
| app | shared | `CrossBorderCancellationDatabaseTest` | 9/0 | - |
| app | shared | `CrossBorderOfferDatabaseTest` | 11/0 | - |
| app | shared | `CrossBorderPaymentDatabaseTest` | 15/0 | - |
| app | shared | `CrossBorderReturnDatabaseTest` | 15/0 | - |
| app | own-container | `CrossBorderRiskSeamDatabaseTest` | 10/0 | - |
| app | shared | `CustomerAccountDatabaseTest` | 9/0 | - |
| app | shared | `CustomerAnswerIsIndifferentToACaseTest` | 2/0 | - |
| app | shared | `CustomerProjectionDatabaseTest` | 8/0 | - |
| app | shared | `DashboardQueriesResolveTest` | 7/0 | - |
| app | shared | `DatabaseTierHeapDatabaseTest` | 1/0 | - |
| app | shared | `DecisionDatabaseTest` | 10/0 | - |
| app | own-container | `DecisionExplanationDatabaseTest` | 9/0 | - |
| app | own-container | `DecisionOrchestrationDatabaseTest` | 14/0 | - |
| app | own-container | `DecisionReplayDatabaseTest` | 11/0 | - |
| app | own-container | `DecisionReproducibilityBatteryTest` | 13/0 | - |
| app | shared | `DecisionRequestApiTest` | 5/0 | - |
| app | own-container | `DecisionRequestDatabaseTest` | 12/0 | - |
| app | shared | `DenyByDefaultDatabaseTest` | 9/0 | - |
| app | shared | `DisputeBatteryDatabaseTest` | 1/0 | - |
| app | shared | `DisputeNotificationDatabaseTest` | 16/0 | - |
| app | shared | `DisputeResponseDatabaseTest` | 19/0 | - |
| app | shared | `DocumentUploadDatabaseTest` | 9/0 | - |
| app | shared | `DomainSpansDatabaseTest` | 1/0 | - |
| app | shared | `FeeScheduleDatabaseTest` | 19/0 | - |
| app | shared | `FinancialDataCollectionDatabaseTest` | 4/0 | - |
| app | shared | `FxAdministrationEndpointDatabaseTest` | 4/0 | - |
| app | shared | `FxCallbackDuplicateDatabaseTest` | 4/0 | - |
| app | shared | `FxConversionDatabaseTest` | 3/0 | - |
| app | shared | `FxConversionRaceDatabaseTest` | 7/0 | - |
| app | shared | `FxCoverDatabaseTest` | 13/0 | - |
| app | shared | `FxCoverRaceDatabaseTest` | 4/0 | - |
| app | own-container | `FxCrossBorderStormDatabaseTest` | 1/0 | - |
| app | shared | `FxProofDatabaseTest` | 4/2 | 4/0 |
| app | shared | `FxProvenanceDatabaseTest` | 1/0 | - |
| app | shared | `FxProviderSourceDatabaseTest` | 5/2 | 5/0 |
| app | shared | `FxQuoteEndpointDatabaseTest` | 7/0 | - |
| app | shared | `FxSettledToCashDatabaseTest` | 1/0 | - |
| app | shared | `FxTradeReversalRaceDatabaseTest` | 6/1 | 6/0 |
| app | shared | `FxValuePreservationBatteryDatabaseTest` | 1/1 | 1/0 |
| app | shared | `GlMappingDatabaseTest` | 6/0 | - |
| app | own-container | `GlReadModelDatabaseTest` | 10/0 | 10/0 |
| app | shared | `HealthReadinessDatabaseTest` | 4/0 | - |
| app | shared | `HoldDatabaseTest` | 10/0 | - |
| app | shared | `HostileInputProbeDatabaseTest` | 2/0 | - |
| app | shared | `IdentityAdministrationDatabaseTest` | 24/0 | - |
| app | shared | `JournalPostingDatabaseTest` | 8/0 | - |
| app | own-container | `JpyAndBhdPostableDatabaseTest` | 6/0 | - |
| app | shared | `KybCaseDatabaseTest` | 14/0 | - |
| app | shared | `KybEndpointDatabaseTest` | 10/0 | - |
| app | shared | `KycCaseDatabaseTest` | 6/0 | - |
| app | shared | `KycCaseEndpointDatabaseTest` | 7/0 | - |
| app | shared | `KycReasonScreenDatabaseTest` | 3/0 | - |
| app | shared | `LateEvidenceAndBreakTypingDatabaseTest` | 4/0 | - |
| app | shared | `LedgerAccountDatabaseTest` | 6/0 | - |
| app | own-container | `LendingCapitalDatabaseTest` | 20/0 | - |
| app | own-container | `LendingCreditExposureDatabaseTest` | 10/0 | - |
| app | shared | `LendingProofApiTest` | 2/0 | - |
| app | own-container | `LendingReportsDatabaseTest` | 3/0 | - |
| app | own-container | `LendingStormDatabaseTest` | 1/0 | - |
| app | shared | `LineClosureApiTest` | 3/0 | - |
| app | shared | `LoanAcceptanceApiTest` | 3/0 | - |
| app | own-container | `LoanAccountDatabaseTest` | 29/0 | - |
| app | shared | `LoanApplicationApiTest` | 4/0 | - |
| app | own-container | `LoanApplicationDatabaseTest` | 13/0 | - |
| app | shared | `LoanOfferApiTest` | 3/0 | - |
| app | own-container | `LoanOfferDatabaseTest` | 6/0 | - |
| app | own-container | `LoanPayoutDatabaseTest` | 10/0 | - |
| app | shared | `LoanTermsEndpointDatabaseTest` | 4/0 | - |
| app | shared | `LoanWriteOffApiTest` | 4/0 | - |
| app | own-container | `LoanWriteOffLedgerDatabaseTest` | 4/0 | - |
| app | shared | `LoginRacingACredentialChangeDatabaseTest` | 5/0 | - |
| app | own-container | `MerchantAbuseDatabaseTest` | 7/0 | - |
| app | shared | `MerchantApiKeyDatabaseTest` | 13/0 | - |
| app | shared | `MerchantBnplEnablementDatabaseTest` | 12/0 | 14/0 |
| app | shared | `MerchantCaptureDatabaseTest` | 25/0 | - |
| app | shared | `MerchantConservationStormDatabaseTest` | 1/0 | - |
| app | shared | `MerchantEndpointDatabaseTest` | 7/0 | - |
| app | shared | `MerchantOnboardingDatabaseTest` | 10/1 | 10/0 |
| app | shared | `MerchantPayoutDatabaseTest` | 30/3 | 30/0 |
| app | shared | `MerchantPayoutEndpointDatabaseTest` | 7/0 | - |
| app | shared | `MerchantPayoutMetricsDatabaseTest` | 1/0 | - |
| app | shared | `MerchantTenancyBatteryDatabaseTest` | 4/0 | - |
| app | shared | `MfaCannotBeBypassedDatabaseTest` | 7/0 | - |
| app | shared | `MfaChallengeDatabaseTest` | 13/0 | - |
| app | shared | `MfaEndpointDatabaseTest` | 6/0 | - |
| app | shared | `MfaEnrolmentDatabaseTest` | 12/0 | - |
| app | shared | `MonitoringIntakeDatabaseTest` | 6/0 | - |
| app | own-container | `MonitoringRulesDatabaseTest` | 5/0 | - |
| app | shared | `MultiCurrencyWalletFlowsDatabaseTest` | 3/0 | - |
| app | own-container | `MultiRailConservationStormDatabaseTest` | 1/0 | - |
| app | shared | `OperationalChartDatabaseTest` | 3/0 | - |
| app | own-container | `OperationalReportsDatabaseTest` | 16/1 | 16/0 |
| app | shared | `OutboundCreditResolutionDatabaseTest` | 18/0 | - |
| app | shared | `OutboxMetricsDatabaseTest` | 4/0 | - |
| app | shared | `PartyAndIdentityConcurrencyDatabaseTest` | 4/0 | - |
| app | shared | `PartyAndIdentitySchemaDatabaseTest` | 14/0 | - |
| app | shared | `PayByBankDatabaseTest` | 32/0 | - |
| app | shared | `PaymentAmbiguityDemonstrationDatabaseTest` | 5/0 | - |
| app | shared | `PaymentAuthorizationDatabaseTest` | 12/0 | - |
| app | shared | `PaymentCaptureDatabaseTest` | 6/0 | - |
| app | shared | `PaymentConservationDatabaseTest` | 1/0 | - |
| app | shared | `PaymentEndpointDatabaseTest` | 15/0 | - |
| app | shared | `PaymentMethodDatabaseTest` | 13/0 | - |
| app | shared | `PaymentMethodEndpointDatabaseTest` | 8/0 | - |
| app | shared | `PaymentMetricsDatabaseTest` | 6/0 | - |
| app | shared | `PaymentRefundDatabaseTest` | 29/0 | - |
| app | shared | `PaymentRefundEndpointDatabaseTest` | 6/0 | - |
| app | shared | `PaymentRiskMigrationDatabaseTest` | 5/0 | - |
| app | own-container | `PaymentRiskSeamDatabaseTest` | 12/0 | - |
| app | shared | `PaymentSurfaceUnconfiguredDatabaseTest` | 1/0 | - |
| app | shared | `PaymentSweeperDatabaseTest` | 12/0 | - |
| app | shared | `PaymentVoidDatabaseTest` | 9/0 | - |
| app | shared | `PaymentWebhookDatabaseTest` | 6/0 | - |
| app | shared | `PaymentWebhookTransitionDatabaseTest` | 15/0 | - |
| app | shared | `PaymentsSchemaDatabaseTest` | 24/0 | - |
| app | shared | `PayoffApiTest` | 4/0 | - |
| app | shared | `PayoutDestinationDatabaseTest` | 12/0 | - |
| app | shared | `PayoutDestinationEndpointDatabaseTest` | 7/0 | - |
| app | own-container | `PayoutReturnDatabaseTest` | 19/0 | - |
| app | own-container | `PayoutSettlementCashDatabaseTest` | 5/0 | - |
| app | own-container | `PeriodCloseDatabaseTest` | 11/3 | 12/0 |
| app | own-container | `PeriodReopenDatabaseTest` | 11/4 | 13/0 |
| app | shared | `PersonWrittenReasonsAreScreenedDatabaseTest` | 2/0 | - |
| app | own-container | `Phase10ReportsDatabaseTest` | 5/0 | - |
| app | own-container | `Phase10SpansDatabaseTest` | 1/0 | - |
| app | shared | `Phase9ReportsDatabaseTest` | 2/1 | 2/0 |
| app | shared | `PostingCutoffDatabaseTest` | 9/0 | 10/0 |
| app | shared | `PostingFloorDatabaseTest` | 6/0 | - |
| app | shared | `PostingServiceDatabaseTest` | 8/0 | - |
| app | shared | `PrepaymentApiTest` | 3/0 | - |
| app | own-container | `PriorPeriodAdjustmentDatabaseTest` | 24/1 | 24/0 |
| app | shared | `ProfileApiTest` | 2/0 | - |
| app | shared | `ProfileEndpointDatabaseTest` | 6/0 | - |
| app | shared | `ProjectionRebuildDatabaseTest` | 8/0 | - |
| app | shared | `ProjectionVerificationDatabaseTest` | 4/0 | - |
| app | shared | `ProviderCallbackDatabaseTest` | 9/0 | - |
| app | shared | `QueueApiTest` | 2/0 | - |
| app | own-container | `QuietMonthDatabaseTest` | 2/0 | 2/0 |
| app | shared | `ReconciledPositionResidueDatabaseTest` | 3/1 | 3/0 |
| app | shared | `ReconciliationAdministrationDatabaseTest` | 7/0 | - |
| app | shared | `ReconciliationInvestigationDatabaseTest` | 5/0 | - |
| app | shared | `ReconciliationMatchingDatabaseTest` | 2/0 | - |
| app | shared | `ReconciliationOpeningDatabaseTest` | 5/0 | - |
| app | shared | `ReconciliationReportsDatabaseTest` | 3/0 | - |
| app | shared | `ReconciliationResolutionDatabaseTest` | 5/1 | 5/0 |
| app | shared | `ReconciliationSuspenseDatabaseTest` | 3/0 | - |
| app | shared | `RecoveryAbuseDatabaseTest` | 12/0 | - |
| app | shared | `RecoveryConcurrencyDatabaseTest` | 2/0 | - |
| app | shared | `RecoveryEndpointDatabaseTest` | 8/0 | - |
| app | shared | `RegistrationAtomicityDatabaseTest` | 4/0 | - |
| app | shared | `RegistrationConcurrencyDatabaseTest` | 5/0 | - |
| app | shared | `RegistrationCostsTheSameDatabaseTest` | 2/0 | - |
| app | shared | `RegistrationCredentialDatabaseTest` | 10/0 | - |
| app | shared | `RegistrationEndpointDatabaseTest` | 16/0 | - |
| app | shared | `RegulatorySubmissionApiTest` | 5/0 | - |
| app | own-container | `RegulatorySubmissionDatabaseTest` | 23/0 | 23/0 |
| app | shared | `RepaymentApiTest` | 4/0 | - |
| app | shared | `RepaymentReversalApiTest` | 2/0 | - |
| app | shared | `ReportApiTest` | 2/0 | - |
| app | shared | `ReportRunDatabaseTest` | 13/1 | 13/0 |
| app | own-container | `RescreeningDatabaseTest` | 16/0 | - |
| app | own-container | `ResolutionBatteryDatabaseTest` | 7/0 | - |
| app | shared | `ReversalDatabaseTest` | 7/0 | - |
| app | shared | `ReviewDatabaseTest` | 13/0 | - |
| app | shared | `RiskActionApiTest` | 2/0 | - |
| app | shared | `RiskAssessmentApiTest` | 2/0 | - |
| app | shared | `RiskCaseApiTest` | 2/0 | - |
| app | shared | `RiskMonitoringEndpointDatabaseTest` | 2/0 | - |
| app | shared | `RiskOverrideApiTest` | 2/0 | - |
| app | own-container | `RiskPolicyEndpointDatabaseTest` | 4/0 | - |
| app | own-container | `RiskProofsDatabaseTest` | 11/0 | - |
| app | shared | `RiskReportsApiTest` | 4/0 | - |
| app | own-container | `RiskStormTest` | 1/1 | - |
| app | shared | `RoleAssignmentConcurrencyDatabaseTest` | 5/0 | - |
| app | shared | `RoutingPolicyDatabaseTest` | 6/0 | - |
| app | shared | `RulingsDoorsApiTest` | 3/0 | - |
| app | own-container | `SafeguardingCommittedDatabaseTest` | 6/0 | - |
| app | own-container | `SafeguardingReturnDatabaseTest` | 8/3 | 8/0 |
| app | own-container | `SchemeCycleCashDatabaseTest` | 4/0 | - |
| app | shared | `ScorecardAdministrationEndpointDatabaseTest` | 3/1 | 3/0 |
| app | shared | `ScreeningListApiTest` | 2/0 | - |
| app | shared | `ScreeningRunDatabaseTest` | 4/0 | - |
| app | shared | `SecondBureauCollectionDatabaseTest` | 20/0 | - |
| app | shared | `SecondCorridorRailDatabaseTest` | 1/1 | 1/0 |
| app | shared | `SecondFxProviderDatabaseTest` | 3/0 | - |
| app | shared | `SessionClockSkewDatabaseTest` | 8/0 | - |
| app | shared | `SessionClockSkewEndpointDatabaseTest` | 3/0 | - |
| app | shared | `SessionEndpointDatabaseTest` | 18/0 | - |
| app | shared | `SessionLifecycleDatabaseTest` | 10/0 | - |
| app | shared | `SessionLiveCountDatabaseTest` | 5/0 | - |
| app | shared | `SessionOwnershipDatabaseTest` | 10/0 | - |
| app | shared | `SessionRevocationDatabaseTest` | 10/0 | - |
| app | shared | `SessionRotationDatabaseTest` | 11/0 | - |
| app | shared | `SettlementAcceptanceDatabaseTest` | 3/1 | 3/0 |
| app | shared | `SettlementExpectationDatabaseTest` | 7/0 | 7/0 |
| app | shared | `SettlementPullDatabaseTest` | 12/0 | - |
| app | shared | `SettlementReadmissionDatabaseTest` | 10/0 | - |
| app | own-container | `SettlementReconciliationStormDatabaseTest` | 1/0 | - |
| app | shared | `SettlementRoutesDatabaseTest` | 8/0 | - |
| app | shared | `SettlementSourceRegisterDatabaseTest` | 1/0 | - |
| app | shared | `SignalIntakeDatabaseTest` | 7/0 | - |
| app | shared | `SourceRequirementDatabaseTest` | 1/0 | - |
| app | shared | `StatementEndpointDatabaseTest` | 4/0 | - |
| app | own-container | `StatementsDatabaseTest` | 11/3 | 11/0 |
| app | shared | `SuspensionConcurrencyDatabaseTest` | 4/0 | - |
| app | shared | `SustainedConcurrentPostingDatabaseTest` | 2/0 | - |
| app | own-container | `SystemWithdrawalDatabaseTest` | 6/0 | - |
| app | shared | `TraceAcrossDatabaseTest` | 3/0 | - |
| app | shared | `TransferConservationDatabaseTest` | 1/0 | - |
| app | shared | `TransferEndpointDatabaseTest` | 9/0 | - |
| app | shared | `TransferExecutionDatabaseTest` | 6/0 | - |
| app | shared | `TransferMetersDatabaseTest` | 5/0 | - |
| app | shared | `TransferReversalDatabaseTest` | 7/0 | - |
| app | own-container | `TransferRiskSeamDatabaseTest` | 10/0 | - |
| app | shared | `TransferSchemaDatabaseTest` | 5/0 | - |
| app | shared | `TransferSeamDatabaseTest` | 3/0 | - |
| app | shared | `TrialBalanceDatabaseTest` | 2/0 | - |
| app | own-container | `TrialBalanceRunDatabaseTest` | 7/0 | 7/0 |
| app | own-container | `UnderwritingCaseDatabaseTest` | 28/0 | - |
| app | own-container | `UnmatchedConfirmationSuspenseDatabaseTest` | 9/0 | - |
| app | shared | `UnwindRetryDatabaseTest` | 4/4 | 4/0 |
| app | own-container | `ValuationRateSetDatabaseTest` | 15/0 | 15/0 |
| app | own-container | `ValuationReferenceGapDatabaseTest` | 3/0 | 3/0 |
| app | shared | `VerificationRunDatabaseTest` | 5/0 | - |
| app | shared | `WaiverApiTest` | 2/0 | - |
| app | shared | `WalletOpenIfAbsentRaceDatabaseTest` | 5/0 | - |
| app | shared | `WithdrawalDatabaseTest` | 16/0 | - |
| app | shared | `WithdrawalHoldDatabaseTest` | 6/0 | - |
| app | shared | `WithdrawalMigrationDatabaseTest` | 4/0 | - |
| app | own-container | `WithdrawalRiskSeamDatabaseTest` | 10/0 | - |
| bnpl | shared | `BnplAcceptanceDatabaseTest` | 8/0 | 8/0 |
| bnpl | shared | `BnplAccrualDatabaseTest` | 12/0 | 12/0 |
| bnpl | shared | `BnplApplicationStoreDatabaseTest` | 5/0 | 5/0 |
| bnpl | shared | `BnplBillingDatabaseTest` | 15/0 | 15/0 |
| bnpl | shared | `BnplBornOnceArbitersDatabaseTest` | 1/1 | 1/0 |
| bnpl | shared | `BnplChartDatabaseTest` | 8/1 | 8/0 |
| bnpl | shared | `BnplCollectionDatabaseTest` | 10/0 | 10/0 |
| bnpl | shared | `BnplConditionDatabaseTest` | 11/1 | 11/0 |
| bnpl | shared | `BnplDisputeDatabaseTest` | 22/0 | 22/0 |
| bnpl | shared | `BnplLateFeeDatabaseTest` | 6/0 | 6/0 |
| bnpl | shared | `BnplMigrationTest` | 6/1 | 6/0 |
| bnpl | shared | `BnplPayoffDatabaseTest` | 15/0 | 15/0 |
| bnpl | shared | `BnplRefundDatabaseTest` | 15/0 | 15/0 |
| bnpl | shared | `BnplRepaymentDatabaseTest` | 19/0 | 19/0 |
| bnpl | shared | `BnplRepaymentReversalDatabaseTest` | 16/0 | 16/0 |
| bnpl | shared | `BnplSchemaHasNoMutableMoneyTest` | 8/0 | 8/0 |
| bnpl | shared | `BnplTerminalEdgesDatabaseTest` | 1/0 | 1/0 |
| bnpl | shared | `BnplTermsDatabaseTest` | 11/0 | 11/0 |
| bnpl | shared | `BnplWaiverDatabaseTest` | 9/0 | 9/0 |
| bnpl | shared | `BnplWriteOffDatabaseTest` | 11/2 | 11/0 |
| fx | shared | `FxAvailabilityDatabaseTest` | 5/0 | - |
| fx | shared | `FxMigrationTest` | 5/0 | - |
| fx | shared | `FxProviderEvidenceDatabaseTest` | 4/0 | - |
| fx | shared | `FxReasonScreenDatabaseTest` | 3/0 | - |
| fx | shared | `PricingPolicyDatabaseTest` | 8/0 | - |
| fx | shared | `QuoteIssuanceDatabaseTest` | 10/0 | - |
| fx | shared | `QuoteLifecycleDatabaseTest` | 4/0 | - |
| fx | shared | `QuoteSchemaDatabaseTest` | 6/0 | - |
| fx | shared | `RateFetchPermitDatabaseTest` | 3/0 | - |
| fx | shared | `RateFetchRaceDatabaseTest` | 2/0 | - |
| fx | shared | `RateSnapshotDatabaseTest` | 6/0 | - |
| fx | shared | `TradeSchemaDatabaseTest` | 5/0 | - |
| lending | shared | `AmendmentDatabaseTest` | 14/0 | 14/0 |
| lending | shared | `AutoCollectionDatabaseTest` | 14/0 | 14/0 |
| lending | shared | `BackwardClockDatabaseTest` | 19/0 | 19/0 |
| lending | shared | `CreditLineDrawDatabaseTest` | 14/0 | 14/0 |
| lending | shared | `CreditLineRepaymentDatabaseTest` | 16/0 | 16/0 |
| lending | shared | `CreditLineStatementDatabaseTest` | 11/0 | 11/0 |
| lending | shared | `DelinquencyDatabaseTest` | 12/1 | 12/0 |
| lending | shared | `FeeAndWaiverDatabaseTest` | 17/0 | 17/0 |
| lending | shared | `InstalmentBillingDatabaseTest` | 10/0 | 10/0 |
| lending | shared | `InterestAccrualDatabaseTest` | 14/0 | 14/0 |
| lending | shared | `LendingBornOnceArbitersDatabaseTest` | 1/1 | 1/0 |
| lending | shared | `LendingMigrationTest` | 7/1 | 7/0 |
| lending | shared | `LendingOutboxAtomicityDatabaseTest` | 1/0 | 1/0 |
| lending | shared | `LendingProofDatabaseTest` | 13/2 | 13/0 |
| lending | shared | `LendingSchemaHasNoMutableMoneyTest` | 8/0 | 8/0 |
| lending | shared | `LoanAcceptanceDatabaseTest` | 18/0 | 18/0 |
| lending | shared | `LoanAccrualCutoffDatabaseTest` | 4/0 | 4/0 |
| lending | shared | `LoanChartDatabaseTest` | 6/1 | 6/0 |
| lending | shared | `LoanDisbursementDatabaseTest` | 12/0 | 12/0 |
| lending | shared | `LoanOriginationDatabaseTest` | 19/0 | 19/0 |
| lending | shared | `LoanPayoutStepsDatabaseTest` | 11/0 | 11/0 |
| lending | shared | `LoanTermsDatabaseTest` | 15/1 | 15/0 |
| lending | shared | `LoanWriteOffDatabaseTest` | 12/5 | 12/0 |
| lending | shared | `PayoffDatabaseTest` | 16/0 | 16/0 |
| lending | shared | `PrepaymentDatabaseTest` | 9/0 | 9/0 |
| lending | shared | `RepaymentDatabaseTest` | 19/0 | 19/0 |
| lending | shared | `RepaymentReversalDatabaseTest` | 14/0 | 14/0 |
| lending | shared | `TermsRetirementClockDatabaseTest` | - | 1/1 |
| platform | shared | `ApplicationRoleGrantsTest` | 6/0 | 6/0 |
| platform | shared | `AuditImmutabilityTest` | 9/0 | 9/0 |
| platform | shared | `AuditRecordSchemaTest` | 8/0 | 8/0 |
| platform | shared | `AuditWriterTest` | 8/0 | 8/0 |
| platform | shared | `ColumnClassificationTest` | 5/2 | 5/0 |
| platform | shared | `CorrelationPropagationTest` | 6/0 | 6/0 |
| platform | shared | `IdempotencyFailureModeTest` | 6/0 | 6/0 |
| platform | shared | `IdempotencyRecordSchemaTest` | 18/0 | 18/0 |
| platform | shared | `IdempotentExecutorTest` | 19/0 | 19/0 |
| platform | shared | `InboxConsumerTest` | 14/0 | 14/0 |
| platform | shared | `InboxDeliveryOrderTest` | 6/0 | 6/0 |
| platform | shared | `InboxMessageSchemaTest` | 6/0 | 6/0 |
| platform | shared | `MoneyColumnsDatabaseTest` | 7/0 | 7/0 |
| platform | shared | `OutboxCrashRecoveryTest` | 5/0 | 5/0 |
| platform | shared | `OutboxEventSchemaTest` | 13/0 | 13/0 |
| platform | shared | `OutboxRelayTest` | 15/0 | 15/0 |
| platform | shared | `OutboxWriterTest` | 8/0 | 8/0 |
| platform | shared | `Phase1IdentityFitsTheAuditSchemaTest` | 3/0 | 3/0 |
| platform | shared | `RateColumnsDatabaseTest` | 2/0 | 2/0 |
| platform | shared | `RelayLoggingTest` | 4/0 | 4/0 |
| reconciliation | shared | `AtomicityByFailureInjectionDatabaseTest` | 6/0 | - |
| reconciliation | shared | `BankMatchingDatabaseTest` | 13/0 | - |
| reconciliation | shared | `BreakAndSuspenseDatabaseTest` | 11/0 | - |
| reconciliation | shared | `BreakCaseFileDatabaseTest` | 11/0 | - |
| reconciliation | shared | `ClaimantOrderAndReplayDatabaseTest` | 4/0 | - |
| reconciliation | shared | `ExpectationRegisterDatabaseTest` | 6/0 | - |
| reconciliation | shared | `FeeAndCorrectionDatabaseTest` | 8/0 | - |
| reconciliation | shared | `FxMatchingDatabaseTest` | 8/0 | - |
| reconciliation | shared | `GraceAndRematchDatabaseTest` | 9/0 | - |
| reconciliation | shared | `MatcherRacesDatabaseTest` | 9/0 | - |
| reconciliation | shared | `MatchingDatabaseTest` | 9/0 | - |
| reconciliation | shared | `MatchingLegCorrectionsDatabaseTest` | 20/0 | - |
| reconciliation | shared | `ParkedConfirmationsDatabaseTest` | 5/0 | - |
| reconciliation | shared | `PayoutMatchingDatabaseTest` | 16/0 | - |
| reconciliation | shared | `ReconciliationMigrationTest` | 5/0 | - |
| reconciliation | shared | `ReconciliationV013MigrationTest` | 11/0 | - |
| reconciliation | shared | `ReconciliationV014MigrationTest` | 4/0 | - |
| reconciliation | shared | `ReconciliationV015MigrationTest` | 5/0 | - |
| reconciliation | shared | `ReconciliationV016MigrationTest` | 3/0 | - |
| reconciliation | shared | `ReconciliationV017MigrationTest` | 3/0 | - |
| reconciliation | shared | `ReconciliationV019MigrationTest` | 3/0 | - |
| reconciliation | shared | `ResolutionMachineDatabaseTest` | 22/0 | - |
| reconciliation | shared | `ResolutionMatrixDatabaseTest` | 19/0 | - |
| reconciliation | shared | `RuleSetAdministrationDatabaseTest` | 12/0 | - |
| reconciliation | shared | `SchemeMatchingDatabaseTest` | 14/0 | - |
| reconciliation | shared | `StatementChainDatabaseTest` | 8/0 | - |
| risk | shared | `AlertDatabaseTest` | 9/0 | - |
| risk | shared | `CaseDatabaseTest` | 14/0 | - |
| risk | shared | `DecisionOverrideDatabaseTest` | 8/0 | - |
| risk | shared | `ProfileDatabaseTest` | 8/0 | - |
| risk | shared | `QueueDatabaseTest` | 4/0 | - |
| risk | shared | `RegulatoryReportDatabaseTest` | 8/0 | - |
| risk | shared | `RiskActionDatabaseTest` | 9/0 | - |
| risk | shared | `RiskBornOnceArbitersDatabaseTest` | 1/0 | - |
| risk | shared | `RiskEvaluationDatabaseTest` | 11/0 | - |
| risk | shared | `RiskMigrationTest` | 24/0 | - |
| risk | shared | `RiskPolicyDatabaseTest` | 7/0 | - |
| risk | shared | `RiskSchemaMoneyColumnsTest` | 2/0 | - |
| risk | shared | `VelocityWindowDatabaseTest` | 9/0 | - |
| settlement | shared | `BankStatementDatabaseTest` | 6/0 | - |
| settlement | shared | `BatchAcceptanceDatabaseTest` | 10/0 | - |
| settlement | shared | `BatchRepudiationDatabaseTest` | 6/0 | - |
| settlement | shared | `FileAttestationDatabaseTest` | 6/0 | - |
| settlement | shared | `FileParsingDatabaseTest` | 9/0 | - |
| settlement | shared | `FileReceptionDatabaseTest` | 9/0 | - |
| settlement | shared | `IntakeAtomicityAndRacesDatabaseTest` | 7/0 | - |
| settlement | shared | `PersonWrittenReasonsDatabaseTest` | 1/0 | - |
| settlement | shared | `PullPermitDatabaseTest` | 3/0 | - |
| settlement | shared | `ReadmissionDatabaseTest` | 13/0 | - |
| settlement | shared | `SettlementEvidenceGrantsDatabaseTest` | 6/0 | - |
| settlement | shared | `SettlementMigrationTest` | 6/0 | - |
| settlement | shared | `SettlementV010MigrationTest` | 5/0 | - |
| settlement | shared | `SettlementV012MigrationTest` | 3/0 | - |
| settlement | shared | `SourceDatesDatabaseTest` | 6/0 | - |

first run: suites=454 tests=3682 failed=66; targeted re-run: suites=105 tests=1068 failed=2
