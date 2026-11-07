# The Phase 14 → 15 Transition, Part A — the Phase 14 Completion Gate

**Conducted:** 2026-11-07, on branch `P14-GATE` from master `7840c716`
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §1 (the status model), §3 (universal exit criteria and the
financial supplement), §5 Phase 14 (the six originals, the six made measurable and the per-area blocks);
[`EXECUTION_PROTOCOL.md`](../EXECUTION_PROTOCOL.md); [`DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md); the owner's gate
prompt of 2026-11-07 (PART 1–7)
**Precedent:** [`PHASE_13_TO_14_TRANSITION.md`](PHASE_13_TO_14_TRANSITION.md) (Part A, the Phase 13 gate)
**Not done here:** Phase 15's initialisation (Part B, branch `P15-INIT`) - this gate marks nothing of Phase 15 `READY`
and leaves `CURRENT_STATE.md`'s Current Phase, Current Task and Next Task fields to it.

| | Outcome |
|---|---|
| **Gate result** | **`PASS` - Phase 14 `COMPLETE`, confirmed with its recorded deviations** (criteria 6, 7 and 11 `PARTIAL` on the owner's postponements PI-012…PI-015; the storm postponed, PI-004). No repair was needed inside the gate |
| *"Would Phase 14 remain financially correct if 10 service instances executed the relevant operations concurrently?"* | **`PASS`** - every deciding instant and arbiter is the database's (Part 4); the three clock-step defects the pass found are not races between instances, and the two still open fail closed (refuse, never mis-post); the storm's counts are not a measurement (PI-004) |
| Findings | **CRITICAL 0. IMPORTANT 0.** **MINOR 12** (G-1…G-12): eleven remediation entries `P14-REM-001`…`-011` `PLANNED`, none blocking Phase 15; three document defects corrected (G-12) |
| Repairs | **None** - no production code, migration or test changed; so no regression test and no probe (`MUTATION_TESTING.md` unchanged) |
| Universal criterion 7 | **`PARTIAL`, the standing C5 deviation** (as at every earlier transition), with no clean final-tree run of every database tier by the owner's decision (PI-012) |

**The evidence this gate rests on, by the owner's instruction** ("in this phase tests are already created and all passed,
there is not necessary extra test checks"): **no test suite was re-run as gate evidence.** The gate relies on (a) the exit
review [`PHASE_14_REVIEW.md`](PHASE_14_REVIEW.md) (`P14-DOC-001`) - the owner-authorised database pass, its final-tree run
of 55 suites, 583 tests, 0 failures, and its fleet hermetic (24 modules, 6,247 tests) and architecture (294 tests) runs, 0
failed; and (b) the coordinator's guard run on master `7840c716`, fresh and green: accounting 725/0, ledger 180/0
(architecture 2/0), merchant 190/0, lending 166/0, bnpl 602/0, settlement 236/0, `:app:test` 1,289/0 over 222 classes (the
accounting, ledger, merchant, bnpl, credit and telemetry packages and the architecture and api guard packages),
`:app:architectureTest` 237/0. Tests were **read** to judge whether they are meaningful (Part 6), not executed.

**How it was assessed.** The gate engineer read the code, migrations and tests narrowly - ledger `V028`–`V032`, accounting
`V002`–`V012` and the period, close-run, close, prior-period, report-run, statement-note and check machinery, the ledger's
adjustment door - and commissioned three independent read-only audits, each told to hunt defects with a file and a line
rather than confirm the exit review: **(S)** every Phase 14 privileged door (permission, step-up, four eyes, audit,
screening, classification); **(W)** the write-offs (lending `V032`, bnpl `V020`), settlement `V018` and merchant `V013`;
**(R)** regulatory reporting (`V010`), valuation sets (`V005`, `V012`), accounting breaks (`V006`, `V008`), the GL mapping
(`V002`) and the posting rule register. Every finding below was re-read in the code by the gate engineer before it was
classified; where an audit's rating was changed, the reason is given.

---

## PART 1 — Phase 14 as an integrated capability, against the backlog and the exit criteria

| Capability | Result | Basis (file:line read at this gate; database evidence is the exit review's pass) |
|---|---|---|
| Accounting model | `PASS` | one `accounting` module, `platform` only (`accounting/build.gradle.kts:71-79`), no table holding a balance or a line (accounting `V004:45-46`); `AccountingModuleIsolationTest` |
| Chart of accounts | `PASS` | the reference chart as versioned data, mapped by ledger purpose + owner kind (`V002`); content immutable from insert (`V002:229-243`); C2 ratified, codes for qualified review |
| Accounting mappings and posting rules | `PASS` | four-eyes activation (`gl_mapping_version_four_eyes`, `V002:61`); the posting rule register deterministic (`PostingRuleRegister.java:74-103`, audit R), every entry judged at each close (`AccountingChecks.java:169-196`) |
| Rule versioning | `PASS` | the lock pins the version in force under the mapping key shared (`AccountingPeriods.java:308-310`); a FINAL run born only under that pin (accounting `V004:195-197`); `TrialBalanceDefinition.PinMismatch` |
| GL integration | `PASS` | the GL a function of the journal (`GeneralLedger`, `TrialBalanceDefinition` statement "no projection, cache or gauge"); `GL_EQUALS_LEDGER` non-disclosable (`CloseCheckCode.java:45-51`) |
| Existing operational and product ledgers | `PASS` | the ledger unchanged as authority; product proofs read through `SourceProofs` (nine, `SourceProofs.java:44-62`) |
| Subledger-to-GL reconciliation | `PASS` | `SOURCE_COMPLETENESS` over the product proofs, every proof answered once and completely or unavailable (`SourceCompleteness.java:41-71`); `AccountingSourceProofsDatabaseTest` |
| Accruals and adjustments | `PASS` | system posters floored at the first open date under the cutoff's shared key (`PostingCutoffs.java:144-162`); a person's adjustment refused at approval when dated into a locked month (`AdjustmentService.java:374-383`); `ACCRUALS_COMPLETE` |
| Reversals and corrections | `PASS` | prior-period adjustments through the ledger's `ACCOUNTING` origin (ledger `V030:24-36`), accounting's row frozen but for its decision (accounting `V009:112-126`) |
| Multi-currency | `PASS` | per currency at one scale, never summed across currencies; EUR translation at a four-eyes, pinned set, rates `NUMERIC(20,10)` written exactly (`Translation.java:563-568`), `HALF_EVEN` named (`:85-88`) |
| Period management | `PASS` | open insert-if-absent (`AccountingPeriods.java:243-268`); lock in order after the grace, cutoff advanced in the same transaction (`:282-335`); unlock four-eyes, superseding FINAL runs before the cutoff moves (`:388-428`) |
| Period close | `PARTIAL` (recorded, PI-013) | four-eyes close on the run answering the present lock, re-judged at approval (`PeriodCloses.java:205-271`, `:321-365`, `:407-439`; `period_close_four_eyes`, accounting `V008:170`). After a backward database clock step a month can read "no close run yet" until a four-eyes unlock and relock - **fail-closed, verified**: readiness requires the run to hold the present lock (`CloseRuns.java:397-405`, `PeriodCloses.java:225-235`) |
| Trial balance | `PASS` | FINAL at lock, zero per currency and equal to the ledger, its verdict in the frozen content (`TrialBalanceDefinition.java:69-73`); `TrialBalanceRunDatabaseTest`, the battery's 36 FINAL runs |
| Financial reporting | `PASS` | statements issued only for a `CLOSED` month (`ReportRuns.java:297`; `StatementFigures.java:177`) and refused when unbalanced, unmapped, not the ledger's or untranslatable (`ReportRefused.java:26-39`) |
| Reporting data lineage | `PASS` | each run names definition, version, period revision, lock, mapping version and rate set (accounting `V004:51-123`); drill-down to lines with each entry's namespace; submissions name run, run hash and figure path per line (`V010:349-371`) |
| Regulatory reporting abstractions | `PASS` | a simulated reference adapter; obligations four-eyes (`V010:92`); submissions only from FINAL runs of `CLOSED` months (audit R: `V010:574-577`); `SUBMITTED` committed before the call, keyed by the submission's id |
| Persistence and transaction boundaries | `PASS` | every act one transaction under the Phase 14 lock order (period rows → namespace 14 → the cutoff row → child rows); checks judge in read-only snapshots and raise each break in its own transaction (`AccountingChecks.java:21-54`) |
| Idempotency and concurrency | `PASS` | Part 4 |
| Failure recovery | `PASS` | leases and reclaims for checks, runs and submissions; nothing held in memory across a request; PI-014 (2) the gauges' refresher only |
| Security and access control | `PASS` | Part 5; MINOR G-1…G-3 as remediation |
| Auditability | `PASS` | every act audited with actor, target, reason, correlation and outcome; refused self-approvals recorded `FAILED` in their own transaction (audit S, eight desks named) |
| Observability | `PARTIAL` (recorded, PI-014 (2)) | meters, 32 alerts, runbook §10; the accounting gauges do not survive a context stop and start |
| Testing | `PASS` for meaning (Part 6); integration runs `PARTIAL` (criterion 7, C5, PI-012) | |
| Documentation | `PASS` after the gate's corrections (G-12) | |

## PART 2 — the accounting and financial correctness checks

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | The authoritative ledger remains the source of truth | `PASS` | `accounting` has no ledger edge (`accounting/build.gradle.kts:71-79`); the journal is read through ports in `REPEATABLE READ READ ONLY` snapshots (`ReadOnlySnapshot`, a planted write `25006` - `ReportRunDatabaseTest#aWriteInsideGenerationIsRefused`); `ReportingNeverWritesTest` names five ledger writers |
| 2 | No competing writable financial history | `PASS` | no accounting table holds a balance or a line (accounting `V004:45-46`, `V009:29`); report content is output, frozen once complete (`V004:144-163`); submission lines are copies of FINAL figures with their run's hash (`V010:349-371`) - a return's record, not truth (`V010:22`) |
| 3 | Every entry balances | `PASS` | the ledger's deferred per-currency, one-scale balance trigger for every writer (ledger `V004:137-177`); a prior-period adjustment posts the stored lines through `JournalEntry.balanced` (`AdjustmentService.java:387-393`); a write-off credits each receivable balance exactly and debits the expense for the total (audit W: `LoanWriteOffPosition.java:67-88`, `BnplWriteOffPosition.java:65-86`) |
| 4 | Mappings deterministic and versioned | `PASS` | versions with content immutable from insert (`V002:229-243`); the lock pins the version in force (`AccountingPeriods.java:308-310`); a FINAL run only under the pin (`V004:195-197`); a mapping change cannot rewrite a FINAL run (its inputs frozen, `V004:208-220`) |
| 5 | Duplicate source events cannot duplicate accounting effects | `PASS` | accounting consumes no event; every keyed door is arbitrated in the database: prior-period adjustment `UNIQUE (adjustment_proposal_id)`, `UNIQUE (journal_entry_id)` (`V009:55-56`, `PriorPeriodAdjustmentDatabaseTest#aReplayedApprovalPostsOnce`); write-offs one approved per loan, `UNIQUE (journal_entry_id)`, the ledger key `lending.write-off:<loan>` (audit W: `V032:114-115,133`); breaks born once per `(period, check, subject, generation)` (`V008:38`); `report_run_key` (`V004:81`); one close run per lock (`JdbcCloseRunStore.java:112-114`); periods insert-if-absent (`AccountingPeriods.java:252`) |
| 6 | Historical entries not silently overwritten | `PASS` | journal entries and lines append-only by trigger (ledger `V004:203-221`); a line refused when its entry is dated on or before the cutoff (`V032:31-65`); every accounting table append-only or frozen but for its machine's edge. *Residual (G-4)*: a raw writer with the application role can still append a balanced pair to a committed entry of an **open** month (`V032:27-29` says so) - no production path does it |
| 7 | Reversals and adjustments preserve evidence | `PASS` | the prior-period adjustment names the corrected month and revision and is frozen (`V009:112-126`); a reopen supersedes FINAL runs and never deletes them (`V004:264-270`, `:299-315`); a projection rebuild records what was observed, found and written (ledger `V031:28-113`) |
| 8 | Period cutoff and posting rules consistent | `PASS` | entries (`V028:100-133`) and lines (`V032:31-65`) refused at insert under one advisory key; system posters floored, persons' adjustments refused at approval (C3, `PostingCutoffs.java:144-180`); a prior-period adjustment dated inside its open target after the corrected month (`V009:156-158`); the cutoff never over the database's today (`V028:152-157`). Recorded (A4-F1, G-9): the lock's grace is read on the instance's clock (`AccountingPeriods.java:301-305`, `:604-606`), bounded by that last rule |
| 9 | Closed periods cannot be silently modified | `PASS` | the cutoff covers every closed month for every writer; a reopen is the latest closed month only, four-eyes (`V008:411`), `MULTI_FACTOR`, naming the adjustments posted against it, superseding its FINAL runs, at revision + 1 (`PeriodCloses.java:504-557`) |
| 10 | Reports traceable to authoritative records | `PASS` | Part 1 "lineage"; a FINAL run regenerates `IDENTICAL` and a divergence is detected and never repaired (`ReportRunDatabaseTest#regenerationIsIdentical`, `#aDivergenceIsDetectedAndNeverRepaired`); the battery's 36 FINAL runs |
| 11 | Multi-currency preserves amounts, rates, provenance | `PASS` | each translated line keeps its original part (`Translation.java:316-322`); the set id, as-of and each rate's source, snapshot and observation are written (audit R: `:445-449`, `:498-505`); a missing rate is an explicit hole, never a zero; no float in the accounting module (`NoFloatingPointMoneyRulesTest`); rate sets frozen and four-eyes (`V005:66`, `:115`, `:200`); no unrealised FX posted (C7) |
| 12 | Reconciliation identifies missing, duplicate and inconsistent postings | `PASS` | `GL_EQUALS_LEDGER` - lines dropped or doubled per currency, by count and sum, and unmapped keys; `RULE_CONFORMANCE` - unregistered namespaces, shape mismatches, unbalanced entries; `SOURCE_COMPLETENESS` - the product proofs' missing, duplicate and amount discrepancies, an unreadable proof failing the check (`SourceCompleteness.java:50-55`); `PROJECTION_VERIFIED`; never a false pass (`CheckOutcome.java:73-86`). Limits recorded: conformance judges shape, not amount (ADR-0117); a duplicate subject in one run is dropped (G-8) |

## PART 3 — period close and reporting

| Concern | Result | Basis |
|---|---|---|
| Concurrent posting and period closure | `PASS` | the shared/exclusive key pair (`V028:10-25`); `PostingCutoffDatabaseTest#tenPostersAndALockLeaveNoEntryAfterTheCut` - five posters in flight commit, five queued behind the lock are refused, the count at the cut equals the count after (read at this gate, `:137-183`) |
| Accrual processing | `PASS` | floored at the first open date with the value date kept (C3); `ACCRUALS_COMPLETE` blocks or discloses an accrual landed past the cutoff |
| Adjustment authorisation | `PASS` | prior-period: `ACCOUNTANT` proposes, `FINANCIAL_CONTROLLER` approves under `MULTI_FACTOR`, four eyes in accounting and in the ledger (`V009:77`, ledger `V010:59`); refused into a reopened month (`V009:151-163`) |
| Period reopening | `PASS` | Part 2 check 9; `PeriodReopenDatabaseTest` (green in the pass) |
| Trial balance generation | `PASS` | FINAL at lock, its verdict and `fullyMapped` in the content; a `false` fails the close's check and is never corrected |
| Report reproducibility | `PASS` | Part 2 check 10 |
| Reporting date and currency semantics | `PASS` | a line belongs to the period of its posting date; value dates kept; per currency at one scale; translation at the period's one approved set, each column footing through its difference line |
| Data lineage | `PASS` | Part 1 |
| Delayed or missing source events | `PASS` | a late system posting lands floored in the open month; a missing source posting is a product proof's discrepancy; `SOURCE_DELAYED` is never raised - a delay reads as a discrepancy, fail-safe (ADR-0117) |
| Reconciliation before close | `PASS` | `RECONCILIATION_DISCLOSURE` and the month-run coverage (settlement `V018`), re-judged at the close's approval (`PeriodCloses.java:434-437`) |

**The explicit-status question - can an incomplete or inconsistent dataset be presented as a finalized result without an
explicit status? No.** A statement is issued only for a `CLOSED` month and refuses to stand when the books do not balance,
a line is unmapped, the figures are not the ledger's, or a currency cannot be translated (`ReportRuns.java:297`,
`StatementFigures.java:177`, `ReportRefused.java:26-39`) - the run ends `FAILED`, its reason recorded. A FINAL trial
balance or GL activity carries `passes`/`fullyMapped` and its unmapped rows in its content (`TrialBalanceDefinition.java:69-73`,
`GlActivityDefinition.java:115-132`); a translated run is `complete:false` with the hole named; a check that could not read
a proof is `FAILED UNAVAILABLE`, never passed; a close admits only `PASSED` or `DISCLOSED` attempts, a `DISCLOSED` one only
with a person's reason, and the statement's notes carry every check's status (`StatementNotes.java:129-172`); a PROVISIONAL
run is labelled and never issued; a regulatory return draws only from FINAL runs of `CLOSED` months and is flagged
`overtaken` once its month moves. Two refinements are remediation, not failures: a FINAL non-statement run of a `LOCKED`
month reads `FINAL` on its view with no close status beside it (G-11), and a one-person resolution can make an unchanged
finding read `DISCLOSED` (G-1) - explicitly disclosed, so not hidden.

## PART 4 — the multi-instance audit

| Concern | Result | How it is decided |
|---|---|---|
| Concurrent accounting postings | `PASS` | each posting one transaction; the cutoff's shared key; the ledger's existing account and projection locks |
| Duplicate accounting events | `PASS` | Part 2 check 5 - every arbiter a unique or a conditional edge |
| Concurrent accrual processing | `PASS` | the floor read under the cutoff's shared key, held to commit: a poster racing a lock posts before it or floored after it, never refused (`PostingCutoffs.java:149-155`) |
| Concurrent adjustments | `PASS` | the proposal row `FOR UPDATE`; ten approvers, one entry (`PriorPeriodAdjustmentDatabaseTest#aReplayedApprovalPostsOnce`, read at this gate, `:232-259`); sibling adjustments of one month locked in id order (`#twoApprovalsOfOneMonthCannotBothBeUnaware`) |
| Concurrent period-close requests | `PASS` | the period row `FOR UPDATE`, conditional edges, one standing proposal; `PeriodCloseDatabaseTest#twoApprovalsOneClose` |
| Posting racing period closure | `PASS` | Part 3 row 1 |
| Concurrent report generation | `PASS` | `report_run_key`; a lease and a reclaim one attempt more; completion fenced by the attempt (`V004:221-232`); `ReportRunDatabaseTest#tenClaimersOneRun` |
| Replayed source events | `PASS` | no consumer; keyed doors replay their first answer |
| Delayed events | `PASS` | Part 3 |
| Duplicate reconciliation jobs | `PASS` | close checks claimed one statement `FOR UPDATE SKIP LOCKED`, concluded only by their holder (`JdbcCloseRunStore.java:46-71`); breaks born once (`AccountingBreakDatabaseTest#tenRaisersOneBreak`) |
| Service restarts | `PASS` | every claim a database row with a lease; `AccountingCloseSchedule` leaderless (`AccountingCloseSchedule.java:23-45`); no JVM-local lock or set guards correctness in `accounting` or `app/accounting` (searched: one `AtomicBoolean`, a gauge read's in-flight flag) |
| Database races and lost updates | `PASS` | revision compare-and-set and conditional edges throughout; the coded lock order acyclic (the exit review's A4, re-read for the doors above) |
| Partial posting batches | `PASS` | an entry and its lines commit together (deferred balance trigger); a run's birth and its nine attempts one transaction (`CloseRuns.java:196-222`); each break its own commit, a crash leaving the committed ones whole |
| Inconsistent reporting projections | `PASS` | reports read the journal, never a projection; `PROJECTION_VERIFIED` blocks; a drift is rebuilt four-eyes from the journal (ledger `V031`) |
| The clock steps (not races) | recorded | merchant `V013` fixed; PI-013 (the close run read by `started_at`, `JdbcCloseRunStore.java:224-226`) and PI-014 (1) fail closed - verified for PI-013 above; audit W verified PI-014 (1)'s money paths read the terms by pinned id |

**"Would Phase 14 remain financially correct if 10 service instances executed the relevant operations concurrently?" →
`PASS`.** Every deciding instant and arbiter is the database's - unique keys, conditional edges, `SKIP LOCKED` claims under
leases, the cutoff's advisory key pair, four-eyes `CHECK`s binding every writer - and no correctness depends on a JVM-local
lock, process-local idempotency or a single scheduler. The counted contention rows of `PHASE_14_PLAN.md` §8.3 ran green
against PostgreSQL in the exit review's pass. **Not measured:** the storm (`P14-TST-001`, PI-004) - its interleavings at
volume are the owner's postponement, not evidence. **Beside it:** the database clock stepping back can stop a month's
proposal until a four-eyes unlock and relock (PI-013) - a liveness defect that never admits a wrong close.

## PART 5 — architecture and security

| Area | Result |
|---|---|
| Accounting ownership boundaries | `PASS` - accounting owns the mapping, period, close record, run and submission and none of the money; lending and bnpl own their write-offs; the ledger owns the journal, the cutoff, the `ACCOUNTING` origin and the rebuild |
| Ledger authority | `PASS` - Part 2 checks 1, 2 |
| Posting rule ownership | `PASS` - the register is accounting's judgement, never a posting path's (audit R); each domain's poster unchanged |
| Reporting data ownership | `PASS` - runs and content accounting's; no raw `accounting.` SQL outside accounting and `app/accounting` (audit S) |
| Transaction boundaries and consistency | `PASS` - Part 4 |
| Idempotency | `PASS` - Part 2 check 5 |
| Security and authorisation | `PASS` - every mutating route permissioned and registered (`RoutePermissionRegisterTest`); roles coherent: `ACCOUNTANT` proposes, `FINANCIAL_CONTROLLER` approves, `REGULATORY_REPORTER` prepares and submits, no role holding both the proposal and the approval of one act (audit S, `RoleName.java:345-380`) |
| Separation of duties | `PASS`, with G-1 recorded - **four eyes in the domain and by a database `CHECK` binding every writer** on the unlock (`V003:83`, `V011:55`), close (`V008:170`), reopen (`V008:411`), prior-period adjustment (`V009:77`, ledger `V010:59`), mapping (`V002:61`), valuation set (`V005:66`), break acknowledgement (`V006:97`), obligation (`V010:92`), submission (`V010:274`, the approver never the sender), write-offs (lending `V032:119`, bnpl `V020:120`), rebuild (ledger `V031:76`); system actors refused by `person()` and the `*_persons` `CHECK`s; a proposer's own rejection is terminal and moves nothing. **The one single-person decision** is a break's resolution (G-1) |
| `MULTI_FACTOR` approvals | `PASS`, with G-2 recorded - on the unlock, close and reopen approvals, the prior-period adjustment, the reference-gap approval, the obligation and submission approvals, the send and the non-delivery, the ledger adjustment and rebuild approvals and both write-off approvals (audit S's route table); **absent** on the mapping activation and the break acknowledgement's approval (A5, recorded) **and on the break resolution** (G-1); the ordinary valuation approval's absence is pinned deliberate (`ApprovalAssuranceTest`) |
| Auditability of adjustments and closure | `PASS` - every edge audited and evented as a history row; refusals `FAILED` in their own transaction |
| Sensitive financial data | `PASS` - drill-down and journal reads never select a reference, reason or actor (audit S); reasons screened and bounded in Java; G-3 for classification and SQL-side screening |
| Architecture drift, duplicated authority, undocumented decisions | none found beyond G-12's three stale sentences; the decisions are ADR-0113…0119 (`Accepted`) and C1–C13 (ratified) |

## PART 6 — tests read for meaning; findings

| Area | Meaningful tests (read, not re-run) |
|---|---|
| Accounting rules | `PostingRuleRegisterTest`, `RuleConformanceTest`, `AccountingBreakDatabaseTest#aPlantedShapeMismatchRaisesOneBreak`, `#anUnregisteredNamespaceRaisesOneBreak` |
| Debit/credit invariants | `TrialBalanceRunDatabaseTest`, `CloseRunDatabaseTest#aPlantedImbalanceFailsOnlyItsOwnTransaction`, the battery's oracle (`AccountingBatteryTest`, `TrialBalanceOracleTest`) |
| Duplicate postings | `PriorPeriodAdjustmentDatabaseTest#aReplayedApprovalPostsOnce` (ten approvers, then ten replays: one entry), the write-offs' replays |
| Subledger reconciliation | `SourceCompletenessTest`, `ProductSourceProofsTest`, `AccountingSourceProofsDatabaseTest`, `GlEqualsLedgerTest` |
| Multi-currency | `TranslationTest`, `TranslatedTrialBalanceRunsTest`, `PinnedValuationRatesTest`, `ValuationRateSetDatabaseTest` |
| Accruals | `PostingFloorDatabaseTest`, `ServicingAccrualCompletenessTest` |
| Period cutoff | `PostingCutoffDatabaseTest` - ten posters and a lock with the lock waiters observed in `pg_locks`; raw SQL refused; a line appended to a locked month's entry refused |
| Concurrent posting / closure | the above; `PeriodCloseDatabaseTest#twoApprovalsOneClose`, `#anUnlocksRejectionRacingItsApprovalOneWins`; `PeriodReopenDatabaseTest` |
| Reporting correctness | `FinancialStatementsTest`, `StatementsDatabaseTest`, `StatementNotesAreFrozenTest`, the battery's 36 FINAL runs `IDENTICAL` |
| Replay and recovery | `AccountingBatteryInAnotherJvmTest`, `ReportRunDatabaseTest#regenerationIsIdentical`, `#aDeadGeneratorIsReclaimed`, `#aDivergenceIsDetectedAndNeverRepaired` |
| Authorisation | `RoutePermissionRegisterTest`, `ApprovalAssuranceTest`, each suite's `#theProposerCannotApprove` refused by the domain and by the `CHECK` alone |
| Auditability | `AuditableActionRegistryTest`, `AuditCompletenessTest`, the desks' refusal-record cases |

**Gaps, recorded not run:** `-025`'s source admitted or retired while a month is judged and `-026`'s gap capture racing a
lock have no racing test (A4-F6, `P14-REM-008`); the break resolution's aggregate path has no test that the close sees it
(`P14-REM-001`); the route register pins permissions, not step-up (`P14-REM-002`).

### Findings

**CRITICAL - none.** No path creates or destroys money, overwrites history or lets one person approve their own
money-moving act.

**IMPORTANT - none.** Two candidates were rated IMPORTANT by an independent audit and are ruled MINOR here, with the reason:
- *The one-person break resolution* (audits S and R; G-1 below). Ruled MINOR: it is ADR-0117's recorded residual ("one
  person decides it", accepted with the ADR), the checks that could hide a ledger defect - trial balance, GL = ledger,
  projections - are not disclosable (`CloseCheckCode.java:45-51`) so a resolution cannot wave them through, and the
  outcome is explicit (`DISCLOSED`, needing the close proposer's reason and a second person's `MULTI_FACTOR` approval, and
  printed in the statement's notes). No figure moves. Its step-up and its aggregate-subject rule are `P14-REM-001`, before
  production use.
- *A regulatory resend not fenced by its lease* (audit R; G-5). Ruled MINOR: safe at the shipped defaults (lease `PT2M`,
  inquiry interval `PT15M`, `RegulatoryReportingBeans.java:110-111`), reachable only by configuring the interval below a
  provider call, the call keyed by the submission's id, and the only adapter simulated.

**MINOR** (each a remediation entry, or a document corrected here):

- **G-1 - a break's resolution is one person's, with no step-up, and for a disclosable check it turns an aggregate
  subject's unchanged finding into `DISCLOSED`.** `AccountingBreaks.java:341-391` (a non-entry subject accepts any entry
  posted on or after the month's first day, `:371-378`); `AccountingBreakController.java:92-93` (no assurance); an
  unchanged finding behind `RESOLVED` counts as decided (`CheckOutcome.java:95-97`, `CloseChecks.java:215-219`). The exit
  review's Area 5 names the resolution's missing step-up (`PHASE_14_REVIEW.md:251`) but its owner row A5 omits it (`:476`)
  - the recorded item is mis-described; corrected by `P14-REM-001`.
- **G-2 - step-up absent on the GL mapping's activation** (`GlMappingController.java:60-61`) **and the break
  acknowledgement's approval** (`AccountingBreakController.java:78-79`) - A5, confirmed; the register checks permissions
  only (`RoutePermissionRegisterTest`). `P14-REM-002`.
- **G-3 - classification and screening** (A5, confirmed and wider): the adjustment's reason is `RESTRICTED-PII` but the
  rebuild's reason `RESTRICTED-FINANCIAL`, lending's write-off reason `CONFIDENTIAL` and bnpl's `RESTRICTED-PII`; a GL
  account's name unscreened (`GlAccount.java:24`); accounting reasons screened in Java only (`AccountingReasons.java:9-10`);
  `AdjustmentCommand.toString` prints the reference (`:72`). `P14-REM-003`.
- **G-4 - raw-writer gaps in the Phase 14 schema** (no production path): a `projection_rebuild` row can be inserted already
  decided - its trigger is `BEFORE UPDATE OR DELETE` only (ledger `V031:163-166`); a line can join a committed entry of an
  open month (`V032:27-29`); the GL mapping's content trigger reads its version unlocked (`V002:237-239`) and the lock's
  and activation's triggers do not take the mapping key themselves. `P14-REM-004`.
- **G-5 - regulatory submission hardening**: the resend above (`JdbcRegulatorySubmissionStore.java:114-120`); the
  `UNDELIVERED` edge's trigger looser than the store's statement (`V010:725-731` vs `JdbcRegulatorySubmissionStore.java:101-103`);
  a line's figure not re-proved after the draft (`RegulatorySubmissions.java:890-911`); a provider reference that can differ
  from the acknowledgement's (`JdbcRegulatorySubmissionStore.java:95-96`); any preparer may withdraw another's return
  (`RegulatorySubmissions.java:459-476`). `P14-REM-005`.
- **G-6 - write-offs and the BNPL fee pin**: "one pending proposal" is an `EXISTS` a raw `REPEATABLE READ` writer can
  outrun (lending `V032:169-173`, bnpl `V020:176-180`); bnpl's proposal trigger compares currency, not scale
  (`V020:159-161`; lending's both, `V032:158`); the write-off stamps bare `clock_timestamp()`; merchant `V013:61` refuses a
  version not yet in force but not a superseded one; `V013`'s residual still answers `500` (`JdbcBnplPricingStore.java:137`).
  `P14-REM-006`.
- **G-7 - the settlement source's lifecycle**: the application role keeps `UPDATE (status, next_sequence)` on
  `settlement.source` (settlement `V002:408`), so a raw `ACTIVE → RETIRED` is unaudited and single-person - it fails closed
  (the month then lacks a run and cannot close); a source a build stops declaring is required in no month
  (`ReconciliationRunCoverage.java:66-67`, `:116`). `P14-REM-007`.
- **G-8 - close-check robustness**: a second finding with the same subject in one run is dropped
  (`AccountingChecks.java:231-233`); two builds in a rolling deploy can judge one month under two register versions
  (`RuleConformance.java:128-130`); A4-F6's racing cases. `P14-REM-008`.
- **G-9 - time on an instance's clock**: the lock's grace (A4-F1, `AccountingPeriods.java:301-305`); the submissions'
  keyset by `prepared_at` and newest answers by `received_at` across a clock step. `P14-REM-009`.
- **G-10 - the exit review's accepted owner items not yet built**: A1-D2, A1-D3, A1-D5, A3-F4, A3-F5. `P14-REM-010`.
- **G-11 - read surfaces**: a FINAL run's view carries no close status (`ReportRunDesk.java:61-78`); the ledger's adjustment
  read serves every origin's proposal, unaudited (audit S: `AdjustmentService.java:260-265`). `P14-REM-011`.
- **G-12 - three stale sentences, corrected here**: `ROADMAP.md`'s exit-review position dated the verdict 2026-11-06 (the
  review's verdict is 2026-11-07); `BACKLOG.md`'s Phase 14 header still read "ADR-0113…ADR-0118 (`Proposed`)"; the review's
  A5 owner row omits the resolution (recorded here and in `P14-REM-001`; the review itself is a record and is not edited).

## The recorded deviations, re-read

| Deviation | The gate's reading |
|---|---|
| PI-004 - the storm `P14-TST-001` postponed | Accurately recorded; criterion 1 `PASS` with it, F6's storm half deferred; reminded at this exit |
| PI-012 - no clean final-tree run of every tier | Accurately recorded; criterion 7 `PARTIAL`, the standing C5 deviation |
| PI-013 - the close run read by `started_at` after a backward step | **Accurately described**: a liveness defect that fails closed (verified above); the fix (choose the run by the lock's identity) stands as recorded |
| PI-014 (1) - a retired terms version ending before its offer | **"None moves money" verified** by audit W: money paths read the version by its pinned id; the effect is the "in force at" view |
| PI-014 (2) - the gauges' refresher not restartable | Accurately recorded; criterion 6 `PARTIAL`; observability only |
| PI-015 - the struck register rows | Accurately recorded; each `Phase: 14` invariant keeps unstruck rows on present bytes (the review's count) |

None hides a defect the exit review did not name. One recorded owner item is mis-described (A5 omits the resolution, G-1).

## Universal criteria and the financial supplement

Re-read against the code, not copied: criteria 1–5, 8, 9, 10 and 12 **`PASS`** (8 after G-12's corrections); **criterion 6
`PARTIAL`** (PI-014 (2)); **criterion 7 `PARTIAL`** - the standing C5 deviation and PI-012; **criterion 11 `PARTIAL`** - no
CRITICAL, the postponed PI-013 and PI-014 production defects, none moving money. F1–F5, F7, F8 **`Met`**; F6 **`Met`** for
the counted ten-way suites, its storm half deferred (PI-004). Each `PARTIAL` is a recorded deviation naming its PI, not a
pass.

## The Phase 14-specific criteria

The six originals hold as the exit review ruled them (19 clauses hold, 8 in another form, recorded in `PHASE_GATES.md`):
trial balance zero per currency and continuously verified; drill-down to lines with each entry's namespace; posting into a
closed period refused for every writer, entries and lines, and the prior-period path built; FINAL runs reproducible;
breaks and suspense disclosed at close; reporting holding no write path to the ledger (C10's form; the separate database
role is Phase 15's). Per area: **Period close `PARTIAL`** (PI-013), the multi-instance block's storm counts open (PI-004),
the rest hold; Security holds with G-1 and G-2 as remediation.

## What Phase 14 delivered

The `accounting` module (twelve migrations) and its composition in `app`; ledger `V028`–`V032` (the posting cutoff, the
journal by posting date, the `ACCOUNTING` origin, the projection rebuild, the line cutoff), lending `V032` and bnpl `V020`
(write-offs under four eyes), settlement `V018` (a source's admission and retirement), merchant `V013` (the fee pin stamped
by its own insert), identity `V025` (the three accounting roles). The GL as a versioned, four-eyes mapping over the one
journal, pinned at each lock; calendar-month periods with a ledger-enforced cutoff; a close run of nine recorded checks and
a four-eyes close, rejection, reopen and their ends; prior-period adjustments; immutable, reproducible report runs - the
trial balance, GL activity, the purpose reports, both statements with frozen notes, the reference safeguarding position -
and two operational reports; EUR translation at pinned four-eyes valuation sets; the posting rule register and accounting
breaks; regulatory submissions from FINAL runs behind a simulated adapter; loan and BNPL write-offs; a projection rebuild and
a disclosed reference gap; meters, 32 alerts and runbook §10; the accounting battery.

## Decisions

The owner's acceptance of every open recommendation (2026-11-06) and decisions C1–C13 stand as the exit review recorded
them; the postponements PI-004 and PI-012…PI-015 stand. This gate adds no decision: it rules two audit ratings down with
reasons (above) and records eleven remediation entries for the owner's scheduling.

## The remediation entries created (`PLANNED`; none blocks Phase 15)

| ID | Horizon | Subject |
|---|---|---|
| `P14-REM-001` | before production use | a break's resolution under `MULTI_FACTOR`, and an aggregate subject's unchanged finding not disclosed by one person (G-1) |
| `P14-REM-002` | before production use | step-up on the mapping activation and the acknowledgement approval; the register pins assurance (G-2, A5) |
| `P14-REM-003` | before production use | reason classifications aligned; GL account names and accounting reasons screened in SQL; no reference in a `toString` (G-3, A5) |
| `P14-REM-004` | before production use | the Phase 14 schema closed to raw writers: the rebuild born undecided, no line joining a committed entry, the mapping's triggers locked (G-4) |
| `P14-REM-005` | before any real regulatory channel | the resend fenced by its lease, the `UNDELIVERED` rule in the trigger, line figures re-proved, the reference checked, a preparer's own withdrawal (G-5) |
| `P14-REM-006` | before production write-offs and BNPL | one pending write-off for every writer, bnpl's scale, the stamps, a superseded fee version refused, a retryable `409` (G-6) |
| `P14-REM-007` | before production use | a settlement source retired only through a reviewed door; an undeclared source still required (G-7) |
| `P14-REM-008` | before production use | a duplicate subject unavailable; the run pins the register version; A4-F6's racing cases (G-8) |
| `P14-REM-009` | Phase 15 at the latest | the lock's grace and the submissions' orderings on the database's clock (G-9, A4-F1) |
| `P14-REM-010` | before production use | the exit review's accepted owner items A1-D2, A1-D3, A1-D5, A3-F4, A3-F5 (G-10) |
| `P14-REM-011` | before production use | a FINAL run's view states its close status; the ledger adjustment read by origin or audited (G-11) |

## Remaining non-blocking debt

PI-004 (the storm, after the project's phases); PI-012…PI-015 (the next phase's database pass); the remediation entries
above; the Phase 15 items the exit review assigned - a separate database role for reporting (C10), gauges read on the scrape
thread and a rule on `up`; provisioning and EIR (C6, a later phase); the qualified-reviewer list before production (C3, C6,
C7, C8, the write-off notice, counterparties on a statement's face, the income statement's period-end rate, PI-011) and the
production steps (a factor for every `LEDGER_OPERATOR` and `FINANCIAL_CONTROLLER`; PI-005…PI-010).

## Tests

**None run by this gate**, by the owner's instruction; no code changed, so the one exception (a repair's regression test,
its probe and the document guards) did not arise. The counts above are the exit review's and the coordinator's, each from
its own run. The record edits keep `CURRENT_STATE.md`'s Phase 14 `Status:` line and its `` `COMPLETE` `` word, which
`MutationDemonstrationTest` and `PlannedMetersExistTest` parse; no `P14-REM-*` heading names a `P14-TST-*` item.

## What happens next

The coordinator merges this gate with Part B (Phase 15's initialisation, `P15-INIT`) and flips the phase pointer; this gate
marks nothing `READY`. The remediation entries wait for the owner's scheduling. PI-004's reminder: the storms of Phase 14
and every later phase are owed after the project's phases.
