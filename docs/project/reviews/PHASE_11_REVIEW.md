# Phase 11 Exit Review — Lending Infrastructure

**Conducted:** 2026-10-15 (`P11-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 11-specific criteria — the five originals made measurable
and the eighteen per-area blocks, read from the gate at review time, as extended by the Phase 10 → 11
transition)
**Phase objective under review:** lending owns the contract and its servicing facts and none of the money —
every principal, interest, fee and credit-balance figure a balance of a per-loan ledger account posted through
the ledger in lending's own transaction; two products (the amortising personal loan and the revolving credit
line) on one account model; a credit decision taken up only through credit's consumption port under the
party's profile lock; every loan and draw funded from lending capital recognised from bank evidence; disbursement
to the wallet or, through payments' withdrawal machinery, to an external account; ACT/365F interest born once
per date; an explicit, versioned allocation order; delinquency derived beside the lifecycle; every correction a
reasoned, four-eyes posting; every account proven and replayed.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, every one only after this review's corrections - see *What the review found* |
| Universal criteria (12) | **11 `PASS`, criterion 7 `PARTIAL`** - the owner's standing deviation (the fleet-wide database and kafka tiers skipped), recorded, not passed; criterion 10 **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** - F1, F2 over the storm's every reading; F4 with write-off deferred to Phase 14; F8 with the safeguarding proof deferred to Phase 14/15 (a debt row this review wrote) |
| Phase 11-specific criteria | **the five originals and the eighteen per-area blocks `PASS`**, read clause by clause from the gate; eight clauses met in a form their words did not anticipate, recorded under the gate (`PHASE_GATES.md`, the note after Phase 11's criteria); criterion 7's block the standing deviation; the production reality recorded as an accepted limitation |
| *"Correct with 10 concurrent instances?"* | **`PASS`** - every §7.4 row by its named test; every born-once arbiter refused alone with its locks bypassed; the lock order made true in code where it was not (the interest waiver); the storm's two instances ±5 s green three times on the final code with zero deadlocks |
| **Verdict** | **Phase 11 `COMPLETE` (2026-10-16)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → re-run the evidence → **flip the status,
which is the guarded act** → finalise with counted numbers.

**The assessment was eight read-only audits, and it found what no task's gate had.** The audits read the ten
mandatory scenarios and the exit tests; the invariants and the mutation register; multi-instance correctness
and the lock order; security, audit and observability; the per-area gate blocks (two audits); ADR-0090…0100 and
every Phase 11 document against the code; and the owner-decision and debt records across the phase. Each finding
was proven from the code with a file and a line. They found **a deadlock cycle in the lending lock order** — the
interest waiver took the borrower's wallet before the currency's shared `LOAN_INTEREST_INCOME` row, which the
servicing step's accrual, the payoff and the prepayment take before their wallet (`P11-TSK-028` had recorded it
for the owner, unchanged); **the storm's three green runs and seven probes standing on a commit (`cd549de5`) that
predates `lending V028`, the backward-clock remediation, the merge resolution and the dependency upgrades** —
and, re-run on the final code, the storm was red in its first round (§ *The storm on the final code*); **some
fifty born-once uniques never probed with the locks bypassed**, among them the disbursement's and the payout's
`UNIQUE (loan_id)`, whose raw second inserts every suite makes are refused by the trigger first, so a dropped one
would have left the build green; **`PHASE_11_PLAN.md` §13.4's re-dispatch probe never performed** (the tasks
performed "conclude on `UNKNOWN`" in its place); **gate clauses with no test or a weaker one** — the raw-SQL edges
out of terminal offers (`EXPIRED → ACCEPTED` above all), terms, disbursements and cancelled loans for every role;
a crash after a payoff; the subledger proof's non-negativity arm and a disbursement naming no entry; the
acceptance's evidence fields and "no consent purpose"; a lesser session refused with nothing written (proven
only against made-up ids); another party's real loan answering `404` on the payoff-quote and amendment routes;
the capital doors' negatives; self-dealing recorded `FAILED` through the API for waivers and amendments;
`lending.AmendmentRejected` asserted nowhere; twenty keyed doors never raced under one key; "no connection held
across payments' dispatch" proven by a test that stays green when the dispatch runs inside a transaction; DPD
over a generated history; `LOAN_WRITE_OFF_EXPENSE` and the entries' dates never asserted over data; a storm census
excusing a line for ever once it had one reversal; **a loan id in an exception message**; and **drift in all
eleven ADRs and every Phase 11 document** — three register cells of `DISTRIBUTED_EXECUTION.md` still opening
*Planned*, a lock-order cell claiming "no cycle" over the cycle it described, two ADRs saying "measured in the
storm" of things the storm never measured, ports that were never built and ports the documents never named, a
rule-7 ledger key that does not exist, an audited report nobody built, `-028` still a "cut candidate" in eleven
places, the debt rows the gate demands mostly missing. None was waived. Each was corrected here, in code or in
text, or recorded with an owner, and every correction in code was broken on purpose (§ *Probes*).

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **A deadlock cycle in the lending lock order.** The interest waiver (T-wvr) took the borrower's wallet (L7) before posting rule 9 to the currency's shared `LOAN_INTEREST_INCOME` row; the servicing step's accrual, the payoff's and the prepayment's catch-up accrual take that row before their wallet. Over two accounts of one party: `40P01`, one side rolled back whole (no money moves), the operator answered `500`. Recorded for the owner by `-028`, unchanged; invisible to the storm, which proposed only fee waivers | `Waivers.approve` takes the income row (`PostingService.lockBalancesInOrder`) before L7 for an interest waiver. `FeeAndWaiverDatabaseTest#anInterestWaiverTakesTheIncomeRowBeforeTheWallet` (held at the income row, the approval holds no wallet). The storm's load proposes interest waivers where it finds billed interest owing (few were found - the proof is the lock-wait test). `DISTRIBUTED_EXECUTION.md` §3, ADR-0099, the plan. Probed |
| **IMPORTANT** | **The storm's evidence was not on the final code.** Its three green runs and seven probes ran on `cd549de5`, before `lending V028` (eighteen trigger functions replaced), the merge resolution and the dependency upgrades; `-036`'s own record said the storm must follow it | Re-run on the final code (§ *The storm on the final code*): its first run was **red** - C11's "written after the closure" compared two statements' clock readings, and a database clock stepped back inside the closing transaction made rows written before the closure read after it (diagnosed by the next run's diagnostic: one transaction, an earlier record order). C11 now judges by `lending V025`'s record order, with a non-asserting stamp diagnostic beside it; the draw-after-request census by the clock's step; the storm's world reuses its connections (two runs void on ephemeral-port exhaustion). **Three consecutive green runs on `152f1728`** (1,571 s, 1,530 s, 1,546 s) |
| **IMPORTANT** | **Some fifty of the schema's seventy-one uniques never probed with the locks bypassed** (the tasks' records name about nineteen) - among them the disbursement's and the payout's `UNIQUE (loan_id)`, whose raw second inserts every suite makes are refused by the trigger first | `BornOnceArbiters` (platform test fixtures) and `LendingBornOnceArbitersDatabaseTest`: every unique read from the catalogue, each copy refused by the probed unique alone with every trigger suspended and every `CHECK` dropped; the seventy-one and the eight foreign-key targets pinned; fifty-nine refused alone in lending, the four capital uniques in `app` (`LendingCapitalDatabaseTest#everyCapitalArbiterRefusesACopyWithEveryTriggerOff`). Probed: two survivals corrected, then caught |
| **IMPORTANT** | **`PHASE_11_PLAN.md` §13.4's re-dispatch probe was never performed** - the tasks and the storm performed "conclude on `UNKNOWN`" in its place, and `INV-LND-07`'s verify text claimed it | Performed: **survived** - an equivalent mutant at the money's level (payments' key is the loan's own instruction: one withdrawal, one send). Recorded honestly; "never re-dispatched by lending" rests on the machine and payments' key; `INV-LND-07` corrected |
| **IMPORTANT** | **Raw-SQL edges out of terminal states untested** for the offer (`EXPIRED → ACCEPTED` above all - a test labelled "nothing out of a terminal offer" used an `OFFERED` one), terms `RETIRED`/`REJECTED`, a `FAILED` disbursement and a `CANCELLED` loan, and "for every role" proven for one role in places | `LoanAcceptanceDatabaseTest#nothingLeavesATerminalOfferForAnyRole`, `LoanTermsDatabaseTest#nothingLeavesATerminalVersionForAnyRole`, `LoanDisbursementDatabaseTest#nothingLeavesATerminalLoanOrDisbursementForAnyRole` - real terminal rows, both roles. Probed (five mutations) |
| **IMPORTANT** | **A crash after a payoff, re-driven by another instance, was untested** (the Failure-recovery clause names it; the storm's crash points include none) | `PayoffDatabaseTest#aCrashAfterThePayoffsPostingIsReDrivenOnce`. Probed twice |
| **IMPORTANT** | **The subledger proof's non-negativity arm and a disbursement naming no entry were never exercised** - the database admits a `POSTED` disbursement naming any entry id; only the proof can catch it | `LendingProofDatabaseTest#aNegativeDueAccountFailsTheNegativeRule`, `#aDisbursementNamingNoEntryFailsTheProof`. Probed |
| **IMPORTANT** | **The acceptance evidence was mostly unasserted** (identity, session, user agent, `accepted_at` on the database clock, template, document hash) and "creates no consent purpose" untested | `LoanAcceptanceDatabaseTest#theAcceptanceEvidenceHoldsEveryField` (the consent tables unchanged). Probed |
| **IMPORTANT** | **"A lesser session refused with nothing written" was proven on made-up ids only** for the acceptance, draw, closure, payoff, prepayment and the amendment's acceptance | `LoanAccountDatabaseTest#aLesserSessionIsRefusedOnTheOfferAndTheLine`, `#aLesserSessionIsRefusedOnTheLoan` - the caller's real records unchanged, the same key then succeeding under `MULTI_FACTOR`. Probed |
| **IMPORTANT** | **"Another party's id answers `404`"** unproven for the payoff-quote route and both amendment routes (a test named "another party's" used unknown ids) | `LoanAccountDatabaseTest#anotherPartysRealLoanAndAmendmentAreNotFound`. Probed |
| **IMPORTANT** | **The capital doors had no negatives** (`401` on all four, `403`/`404` on approval and rejection) | `LendingCapitalDatabaseTest#theCapitalDoorsAreClosed`. Probed twice |
| **IMPORTANT** | **Self-dealing recorded `FAILED` through the API was unproven for waivers and amendments** - the tests called `recordRefusal` themselves | `LoanAccountDatabaseTest#selfDealingIsRefusedThroughTheApiForWaiversAndAmendments`. Probed twice |
| **IMPORTANT** | **`lending.AmendmentRejected` was never asserted** (the storm exempted it from its "performed" check) | `AmendmentDatabaseTest#fourEyesAtBothRanks` asserts it. Probed |
| **IMPORTANT** | **Twenty keyed doors never raced under one key** - withdrawal, decline, closure, payoff quote, payoff, prepayment, amendment acceptance and every operator act | `OneKeyRace.tenUnderOneKey` and six cases over every door (every operator route is keyed but the read-only replay). Probed |
| **IMPORTANT** | **"No connection held across payments' dispatch" was half-proven** - the existing test stays green with the dispatch inside a lending transaction | `LoanPayoutDatabaseTest#noConnectionIsHeldAcrossPaymentsDispatch`. Probed both ways |
| **IMPORTANT** | **"DPD over a generated history" had no proof** | `DelinquencyStatePropertiesTest` - 400 generated histories against independent oracles. Probed |
| **IMPORTANT** | **`LOAN_WRITE_OFF_EXPENSE` carries zero lines, and every entry's posting date is its value date, were never asserted over data** | The storm's C23, every reading |
| **IMPORTANT** | **The storm's C10 excused a line for ever once it had one approved reversal** - a later genuine breach on it would be invisible | Bounded by exactly the approved reversals' amounts |
| **IMPORTANT** | **A loan id in an exception message** (`LoanAccounts`; the gate's Security criterion) | Removed; `LoanChartDatabaseTest#aConvergedAccountOfTheWrongTypeIsRefused` asserts it absent. Probed |
| **IMPORTANT** | **Drift in all eleven ADRs and every Phase 11 document** - three `DISTRIBUTED_EXECUTION.md` cells opening *Planned*; "no cycle" over a cycle; "measured in the storm" twice of things never measured; ports never built (`LendingObserver`, `TransactionRunner`) and ports never named; a rule-7 ledger key that does not exist; an audited drift report nobody built; `-028` a "cut candidate" in eleven places; `RETURNED` a payout state with no producer; "the only credit lock lending holds"; `INV-LND-04`'s "racing reversal", `-12`'s missing hysteresis, `-14`'s silence on the reversed paydown; `MUTATION_TESTING.md` §4 "work in progress" for a complete item | Every statement made true - area 7 |
| **IMPORTANT** | **The debt rows the gate demands were mostly missing** (write-off and provisioning, fee amortisation, the evidence purge, capital return, refinance, collections, the safeguarding proof) | Written with owners, and four more this review found - area 8 |
| **IMPORTANT, recorded** | **Three settlement suites red since `P9-TSK-026`**, their debt row's trigger "before the Phase 11 exit review" passed unpaid | Not fixed, on the owner's instruction for this review ("record, don't fix"); re-owned to the owner's decision at the Phase 11 → 12 transition |
| MINOR | The gate counts "twenty-five" contention rows; §7.4 has twenty-nine (`-023`, `-028`) | Recorded under the gate (not re-worded) |
| MINOR | Scenario 8's named test asserts the schedule change once; the lapse and the duplicates are its siblings' | Plan §13.2 annotated |
| MINOR | A lock-wait observer lacked the active-and-waiting filter `P11-TSK-035` introduced | Added (`LendingCreditExposureDatabaseTest`) |
| MINOR, recorded | The subledger proof refuses to read a record holding a non-UUIDv7 id a raw writer planted (it throws rather than failing the account) | Never silent: the gauge reads NaN and `LendingGaugesUnreadable` alerts; an owner's call |
| MINOR, recorded | The payoff's and the prepayment's lock waits are asserted without the waiting statement; the storm's "raced" scenes (last capital, the limit, the expiry) are tallied but not in its coverage minima; some races count rows but not exact answers | In-module tests assert those races; recorded |
| MINOR, recorded | The operator loan read does not refuse an employee reading their own party's loan (explanation and replay do); person-written lending reasons classified `CONFIDENTIAL` where the platform's precedent is `RESTRICTED-PII`; `product` tags upper-case on counters, lower-case on gauges; records carrying money without a redacted `toString` (`AllocationLine`, `DueState`) - none logged | Owner's calls, listed below |
| MINOR, recorded | The battery's last recorded run predates `-034`/`-035`/`-036` | It runs in every hermetic tier - this review's fresh tier ran it |

## The flip

Recording Phase 11 `COMPLETE` is the guarded act: the register guard begins demanding a §2 row for every `Phase: 11`
invariant and a §4 row for every `P11-TST` item, and `PlannedMetersExistTest` reads `PHASE_11_PLAN.md` §15 through the
generic union (`LendingMetersTest` had held the thirty-three series ahead of the flip). Against the real status,
`MutationDemonstrationTest` passed (nine cases). **And it was proven non-vacuous against the real status**: with
`INV-LND-12`'s seven rows withheld, the guard failed reporting `(currently 11)` and naming exactly `["INV-LND-12"]`. The
register was restored byte-identical (sha256 `d6578172…`, verified). As in Phase 10, no `Phase: 11` invariant was found
unrowed: every task filed its own, and this review's probes add eighteen rows more. *(The audit noted the guard's own blind
spot: a flip written in a shape it does not parse would leave it at Phase 10 and green; the "currently 11" above is the
check that this one parsed.)*

---

## Area 1 — Domain correctness: what the phase set out to build, and what it built

Thirty-three backlog items across nine milestones (M11.1–M11.9), every one `COMPLETE` with this review, plus the
six remediation items `P11-TSK-031`…`-036` added out of turn on the owner's instruction. The cut candidate
`P11-TSK-028` (partial prepayment) was **built**, so the gate's conditional clause is met by the task.
Migrations: lending `V001`–`V028`, and the neighbours credit `V021`, ledger `V026`, identity `V021`,
reconciliation `V022`, payments `V032`. The implemented concepts match `LENDING_LIFECYCLES.md` and the glossary
after this review's corrections (area 7): every machine read against its trigger (the loan `V026`, the offer and
the disbursement `V028`, the payout `V019`, the repayment's derived states and its deferred sum, the application
`V004`); two refinements recorded rather than invented — the payout has no `RETURNED` state (no producer), and
an application's exit carries no credit evidence the database could check (A16: domain-only). **`PASS`.**

## Area 2 — Financial correctness: a loan end to end

Walked hop by hop and driven together in the storm: a keyed, MFA-assured application opening credit's request
in the same transaction; the origination sweep reading credit's decision and making the offer (the hash, the
illustrative schedule, `expires_at = LEAST(valid_until, offered_at + validity)` on the database clock); the
acceptance under L0 → L2 → credit's consumption → L6, the agreement an exact copy of the offer's terms with its
evidence; the disbursement under L0 → L3 → L5, rule 1 `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET` (path X adding
L7, the hold, and the payout payments disposes of); the servicing step under L3 — rule 2 accrual per ended date
(`DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME`), rule 3 billing from actual accrual, rule 6 the credit
balance, auto-collection rule 5, the condition and the late fee (rule 4); the repayment allocated by the pinned
order; the reversal (rule 10, `ReversalService`); the waivers (rules 8, 8a, 9); the amendment and the prepayment
(rule 13, agreement and schedule v n+1); the payoff and the closure refund. Every rule of `PHASE_11_PLAN.md`
§12.8 has a test that exercises it (rules 0–13 mapped by the audit). Every figure is a balance of journal lines,
explained by `LoanSubledgerProof` and replayed by `LoanReplayProof` for every account in every storm reading.
**Found:** the lock order's inversion (fixed, area 3); "posting date = value date = the business date" asserted
for five of the rules only, and "`LOAN_WRITE_OFF_EXPENSE` carries no line" asserted nowhere over data — both now a
storm census (C23). **`PASS`** after the corrections.

## Area 3 — Boundary integrity and multi-instance correctness

`lending` → `ledger`, `platform`, `sharedkernel` only; every other reach a port `app` implements (nine ports and
the unit-of-work seam — `LendingModuleIsolationTest` and its sixteen siblings); nothing depends on `lending` but
`app`. No module reached into another's state; lending reads payments' withdrawal plainly and never locks it.

The ten-instance answer re-checked for every task against its counted tests. The twenty-nine rows of
`PHASE_11_PLAN.md` §7.4 (the gate's text says twenty-five: four were added by `-023` and `-028`) each have their
arbiter and a test that raises it — the races ten ways where the row is a race; the rows that are fault scenarios
rather than races (scenario 5's lost response, scenario 9's crash, the hint that arrives twice, late or never) by
their named tests; "exposure read vs a disbursement committing" by a deterministic interleaving stronger than a race
(`LendingCreditExposureDatabaseTest#aDisbursementDuringTheReadIsNeverMissed` — the disbursement committed between
the read's statements, the principal counted once). The four leaderless schedules are in
`NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` (twenty-six) with their gauges; advisory
namespace `11` registered; no `synchronized`, static mutable state or instance-clock window in lending's main code.

**Found:**

- **A deadlock cycle in the lock order.** `PHASE_11_PLAN.md` §7.2 says "L8 — postings last", and the code never did
  that for one row. T-svc's accrual, T-pay's and T-ppy's catch-up accrual take the currency's `LOAN_INTEREST_INCOME`
  projection row before the borrower's wallet (L7); T-wvr's interest waiver took the wallet first, then posted rule
  9 to that row. Over two accounts of one party in one currency that is a cycle — `40P01`, one side rolled back whole,
  liveness not money, the operator answered `500`. `-028` recorded it for the owner and changed nothing; the storm's
  "no `40P01`" could not see it (it proposed only fee waivers). **Corrected:** `Waivers.approve` takes the income row
  (`PostingService.lockBalancesInOrder`) before L7 for an interest waiver, the order every other writer keeps;
  `FeeAndWaiverDatabaseTest#anInterestWaiverTakesTheIncomeRowBeforeTheWallet` holds the income row from another
  session and proves the waiting approval holds no wallet (`FOR UPDATE NOWAIT` on it succeeds); probed; the storm's load
  now proposes an interest waiver when it finds billed interest owing (its load found few waivable items: the runs'
  tallies are reported below, so the lock-wait test, not the storm, is this correction's proof);
  `DISTRIBUTED_EXECUTION.md` §3, ADR-0099 and the plan record it.
- **Born-once arbiters unprobed.** The tasks probed nineteen uniques with their triggers off; the schema holds
  seventy-one. **Corrected by a class, not by instances:** `BornOnceArbiters` (platform test fixtures) reads every
  unique index of a schema from the catalogue and, as the bootstrap superuser with every trigger suspended and every
  `CHECK` dropped inside the probe's own rolled-back savepoint, copies a real row under a fresh key - freshening every
  sibling unique's columns - and demands `23505` from the probed constraint itself.
  `LendingBornOnceArbitersDatabaseTest` builds its rows by running the module's own scenario cases, pins the schema's
  seventy-one uniques and its eight composite foreign-key targets by name, and finds fifty-nine refused alone (the four
  capital uniques are probed in `app`, where the treasury door writes them -
  `LendingCapitalDatabaseTest#everyCapitalArbiterRefusesACopyWithEveryTriggerOff`). Its first two probes **survived**
  — a dropped unique simply left the catalogue's list, and a unique widened to include the key passed as a
  foreign-key target — which is why both sets are now pinned; re-performed, both caught (§ *Probes*).
- **"No connection held across payments' dispatch" was half-proven:** `LoanPayoutStepsDatabaseTest#theDispatchHoldsNoLendingLock`
  stays green when the dispatch runs inside a lending transaction that locks nothing. `LoanPayoutDatabaseTest#noConnectionIsHeldAcrossPaymentsDispatch`
  asserts, at the moment of dispatch in the real composition, no Spring transaction or pooled connection bound to the
  stepping thread, the rows lockable, and no backend idle in a transaction holding a lending lock. Probed both ways.
- A lock-wait observer (`LendingCreditExposureDatabaseTest.awaitBlockedBy`) lacked the `state = 'active' AND
  wait_event_type = 'Lock'` filter `P11-TSK-035` introduced against stale activity rows — added.

**`PASS`** after the corrections, with the storm's owner-approved deviations (uncounted L3/L6/L7 waits, M3; the
cross-product decision/acceptance overlap not forced, I4) carried as recorded below.

## Area 4 — Failure behaviour

The fourteen rows of `PHASE_11_PLAN.md` §14 each have a test or an accepted rationale, the two the audit found
bare now recorded: **settlement arriving late** and **reconciliation finding a break on a payout** are payments' and
Phase 8's (the payout's withdrawal is reconciled by payments' machinery; the loan's receivable and the wallet credit
are unaffected by either, proven by `LoanPayoutDatabaseTest#aReturnLandsInTheWalletAndTheLoanIsUnaffected` and the
storm's return scene) — a rationale, accepted. **Found:** "a crash after a payoff is re-driven with nothing doubled"
had no test (the storm's crash points include none) — `PayoffDatabaseTest#aCrashAfterThePayoffsPostingIsReDrivenOnce`,
probed twice. Traced through the code: (1) a provider that succeeds while the response is lost (scenario 5) — the
payout `DISPATCHED` while payments is `UNKNOWN`, never re-sent, concluded by payments' authenticated inquiry, interest
from the conclusion; (2) a payoff racing the delinquency worker (scenario 10) — serialised on L3, the condition step
skipping a terminal account, the trigger refusing a condition on one for every writer. **`PASS`.**

## Area 5 — Security and audit

Every lending route enumerated with its permission, its assurance and its negatives (every operator route in
`RoutePermissionRegisterTest`); the five permissions and three roles pinned by
`RoleNameTest`; every four-eyes act refused at the domain and at the `CHECK`, each alone; standing re-read in the
transaction for application, acceptance and draw; no route lets an operator create a loan, reprice outside an
amendment, draw or disburse; every column classified (`ColumnClassificationTest`, fresh), the acceptance's client IP
and user agent `CONFIDENTIAL`, the payout destination an opaque payment-method id; every audit act of §11 catalogued
in `AUDITABLE_ACTIONS.md` (`AuditableActionRegistryTest` both ways). **Found and corrected:** a lesser session's refusal
"with nothing written" proven only on made-up ids — now on the caller's real offer, line, loan, quote and amendment,
and the same key then succeeds under `MULTI_FACTOR`, proving the refusal stored nothing; "another party's id answers
`404`" unproven for the payoff-quote and both amendment routes; the capital doors' `401`/`403`/`404`; self-dealing
recorded `FAILED` through the API for waivers and amendments (the tests had called `recordRefusal` themselves, so
deleting the desks' calls passed every test); `lending.AmendmentRejected` never asserted; **a loan id in an exception
message** (`LoanAccounts`, the gate's "no … loan id in … exception message") — removed and asserted absent. Each probed.
**`PASS`.**

## Area 6 — Test quality: invariants, the register and the demonstrations

Fifteen invariants carry Phase 11 in their catalogue line, read with the guard's own token rule: `INV-LND-01`…`14`
and `INV-IDEM-02` (already demanded since Phase 8). Every one has performed `MUTATION_TESTING.md` §2 rows (269 rows were
parsed: every one records a mutation, an observed red and a restore); `P11-TST-001` and `P11-TST-002` have their §4
rows. This review adds eighteen §2 rows for its own probes — three of them honest **survivals** (the arbiter probe twice,
before its sets were pinned; the re-dispatch probe, an equivalent mutant at the money's level) and one void
performance. The catalogue's verify texts corrected where the performed probe differed from the one named
(`INV-LND-04`'s "racing reversal" was a planted drift; `INV-LND-07`'s re-dispatch probe). **`PASS`.**

## Area 7 — Documentation accuracy

All eleven ADRs read against the code, corrected in place with dated notes, and accepted (below).
`PHASE_11_PLAN.md` (inline corrections and a new §21 *Errata — as built*), `LENDING_LIFECYCLES.md` (the planned
markers, `RETURNED`, the A16 domain-only refusal, the disbursement deadline, `-028`), `MODULE_ARCHITECTURE.md` (the
lending entry as built through `-036` and `V028`; `LendingObserver` and `TransactionRunner` never built),
`BOUNDED_CONTEXTS.md` (the second credit lock: the withdrawal's request row after L1), `GLOSSARY.md` and
`DOMAIN_MODEL.md` (every "planned" lending entry as built), `LEDGER_MODEL.md` (every lending purpose posts but the
write-off), `DISTRIBUTED_EXECUTION.md` §3 (the three cells still opening *Planned*; the lock order's shared income
row; the false "no cycle"), `FINANCIAL_INVARIANTS.md` (`INV-LND-04`, `-07`, `-12`'s hysteresis, `-13`'s repudiation,
`-14`'s reversed paydown), `DECISIONS.md` (the Phase 11 deferrals and their owners), `CAPABILITY_MAP.md` (Lending
delivered), `ROADMAP.md`, `MUTATION_TESTING.md` §4's stale "work in progress", the ADR index — all made true.
`OPERATIONS_RUNBOOK.md` §7 was read and is accurate. `ERROR_CONTRACT.md`'s thirty-two lending codes equal the code's.
**`PASS`.**

## Area 8 — Architectural debt, and the Phase 12 boundary

The rows the gate demands are now in `CURRENT_STATE.md` §Known Architectural Debt with their owners: the production
origination gate (present; #13/#14, Phase 15 at the latest); write-off, provisioning and effective-interest fee
amortisation (Phase 14); the agreement and acceptance-evidence purge (Phase 15); capital return and income recycling
(A29 named no owner — the owner's, at the Phase 11 → 12 transition); refinance (the Phase 11 → 12 transition);
collections operations (Phase 13); capital tranches (present; Phase 16 if a measurement demands); the safeguarding
proof that subtracts `lending_funded` (Phase 14/15) — plus four this review found: the servicing sweep's volume and the
DPD derivation's cost unmeasured (Phase 16), no schedule-versus-actual drift report (Phase 14), credit's expiry judged
on one statement's clock (the owner's), and a credit line's credit balance held rather than applied (the owner's).
`-028` was built, so no cut row. **The Phase 12 boundary holds**: no BNPL, merchant financing, write-off posting,
collections case, real bureau client, notification, refinance, penalty or default interest, prepayment fee,
acceleration, variable rate or capital return in any main source set (every hit read: `ConsumerKind`'s javadoc, the
seeded and unposted `LOAN_WRITE_OFF_EXPENSE`, Phase 11's own auto-collection, `CreditBeans` refusing a bureau URL,
comment-only deferrals, `FeeKind` = `LATE` and `RateType` = `FIXED` by `CHECK`). **`PASS`.**

## The storm on the final code

`P11-TST-001`'s three green runs and seven probes ran on `cd549de5`; the final code differs by `lending V028` (eighteen
trigger functions replaced), the merge resolution, the dependency upgrades and this review's corrections. The storm was
re-run, alone, in a worktree of its own, until three consecutive runs were green - every attempt recorded:

| Attempt | Code | Outcome |
|---|---|---|
| A | `e70ddbb8` | **Red, round 1** - C11 "a repayment, draw or condition written after the loan's closure": two rows, unlisted (the combined query dumped none) |
| B | `b4baf019` - C11 split per fact and judged by `lending V025`'s record order; a non-asserting stamp diagnostic | Void - the connection attempt failed ("Address already in use") with a database suite run beside it. Its diagnostic listed the class: billings written in the closing transaction itself (one `xmin`, an earlier record order) stamped up to a second after `closed_at` - the database clock stepped back between the transaction's statements |
| C | `f71a4aba` | Void - the same exhaustion, run alone: the storm's world opened a connection per read, about seven thousand sockets in `TIME_WAIT` within minutes on this host's 16,384 ephemeral ports. The diagnostic listed the same class again |
| D | `1cdced28` - the world's owner connections reused (a bounded idle set, one autocommit statement per borrow) | **Red, round 5** - C11 "a draw written after its line's closure request": a draw stamped 1.2 ms after the request, the same stamp class across two transactions serialised on L3 (a draw on a `CLOSING` line is refused by the line's re-read under L3 and by `lending V013`'s trigger for every writer, so the draw came first) |
| E | `152f1728` - that census given the clock's step (5 s) as its tolerance | **Green, three consecutive runs** |

The three green runs, each its own container, `:app:ownContainerDatabaseTest --tests LendingStormDatabaseTest --rerun`:

| Run | UTC | Duration | Seed | Rounds / readings (at rest) | Loans (lines) | Deadlocks |
|---|---|---|---|---|---|---|
| 1 | 19:43:51-20:10:39 | 1,571 s | 1792093453264 | 12 / 135 (56) | 322 (81) | 0 |
| 2 | 20:11:55-20:38:03 | 1,530 s | 1792095139168 | 12 / 138 (56) | 304 (72) | 0 |
| 3 | 20:39:19-21:05:43 | 1,546 s | 1792096783431 | 12 / 138 (56) | 304 (78) | 0 |

Each: one test, zero failures; every census exact at every reading; every scenario's minimum met; one restart; the
needle walk over 17-23 million characters of output finding no loan or party id and no amount; the scheme's sends
equal to the withdrawals; every bureau and financial-data report recorded once; the payout failed and returned. Run 1's
diagnostic listed, unasserted, one draw stamped 85 ms after its line's closure with an earlier record order in an
earlier transaction - the class C11 no longer mistakes.

**What the re-run does not re-prove.** The seven §13.4 probes were performed on `cd549de5` and not re-performed on the
final code: the censuses they proved red are unchanged but for C11's record order and C10's bound, which, with C23, are
this review's census additions and are **not probed** (recorded, MINOR - a probe of each is a 25-minute storm run).
The load's service desk proposed **no** waiver in any of the three runs (each tally's two waivers are the catalogue's
scene): billed interest owing was rarely found, so the storm is no evidence for the interest waiver's corrected lock
order - its lock-wait test and probe are.

## The ten mandatory scenarios

| # | Scenario | Named test | Asserts what it claims | In the storm |
|---|---|---|---|---|
| 1 | The same disbursement on two instances | `LoanDisbursementDatabaseTest#theSameDisbursementOnTwoInstancesPostsOnce`; `LoanPayoutDatabaseTest#tenDispatchersOneWithdrawal` | yes — one entry, one `ACTIVE` edge, the wallet credited once; one withdrawal, one send | yes (its two disbursers not seen contending — M3) |
| 2 | The same repayment more than once | `RepaymentDatabaseTest#theSameRepaymentTwiceRepaysOnce`; `AutoCollectionDatabaseTest#twoSweepersCollectOnce` | yes — one repayment, one entry, the answer replayed; two and ten sweepers | yes |
| 3 | Two repayments on one instalment | `RepaymentDatabaseTest#twoRepaymentsOnOneInstalmentSerialiseAndConserve` | yes — Σ allocations = Σ amounts, never over-allocated, the subledger proof (no due account negative) | yes |
| 4 | An accrual job runs twice | `InterestAccrualDatabaseTest#theAccrualRunTwiceAccruesOnce` | yes — twice, ten steps, ten sweepers, a crash mid-run; the unique proven with triggers off | yes (and a killed backend) |
| 5 | Provider succeeds, response lost | `LoanPayoutDatabaseTest#aLostResponseIsHeldThenConcludedByInquiryAndNeverResent` | yes — `DISPATCHED` while `UNKNOWN`, one send, concluded by inquiry, accrual from the conclusion | yes |
| 6 | A repayment reversed after allocation | `RepaymentReversalDatabaseTest#aReversalAfterAllocationReopensWhatItPaid` | yes — the entry swapped, rows negated, later rows byte-identical, the proof zero | yes |
| 7 | Payoff while a repayment processes | `PayoffDatabaseTest#payoffDuringARepaymentIsStaleOrClosedNeverWrong` | yes — both orders forced, then ten raced rounds; balances zero only after the payoff | yes |
| 8 | Schedule changed under an approved amendment | `AmendmentDatabaseTest#anApprovedAmendmentChangesTheScheduleOnce` | **partly** — v n+1 once, principal conserved, billed kept; the lapse and the duplicates are its siblings `#aMovedAgreementLapsesTheAmendment` and `#tenAcceptancesOneVersion` (recorded, plan §13.2 annotated) | yes |
| 9 | Crash after posting, before publication | `LendingOutboxAtomicityDatabaseTest#aCrashBetweenPostingAndPublicationLosesNothing` | yes — rollback leaves neither; commit then a relay that dies before acknowledging publishes once after restart (a kill after the broker accepted is at-least-once by design, not exercised) | yes |
| 10 | Payoff beside the delinquency worker | `PayoffDatabaseTest#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure` | yes — both orders, the wait proven, no condition after closure | yes |

## Invariants and probes

| Invariant | §2 rows (performed) | This review |
|---|---|---|
| `INV-LND-01` | 19 + 1 | the proof fails a disbursement naming no entry |
| `INV-LND-02` | 9 | — |
| `INV-LND-03` | 22 | — |
| `INV-LND-04` | 27 + 1 | the proof's non-negativity arm; the verify text's "racing reversal" corrected to the planted drift performed |
| `INV-LND-05` | 23 + 1 | the acceptance evidence, every field |
| `INV-LND-06` | 13 | — |
| `INV-LND-07` | 6 + 4 | the arbiters (two survivals, then caught), the re-dispatch probe (survived: equivalent), no connection across the dispatch |
| `INV-LND-08` | 17 + 1 | the payoff's crash and re-drive |
| `INV-LND-09` | 31 | — |
| `INV-LND-10` | 27 | — |
| `INV-LND-11` | 7 + 1 | the interest waiver's lock order |
| `INV-LND-12` | 6 + 1 | DPD over generated histories |
| `INV-LND-13` | 14 | the repudiation exception confirmed |
| `INV-LND-14` | 7 | the storm's C10 bounded by the approved reversals |
| `INV-IDEM-02` | 14 | — |
| also | — | `INV-LIFE-04` +2 (terminal edges), `INV-AUD-01` +2, `INV-AUD-03` +3, `INV-IDEM-01` +1 |

## Probes

Every correction in code, and every test this review added for a gate clause, broken on purpose - the mutation made,
the test run, the red observed, the file restored byte-identical (sha256 before and after, `git diff --exit-code`),
each recorded in `MUTATION_TESTING.md` §2 (eighteen rows, `P11-DOC-001`):

- **the interest waiver's income row before the wallet**, undone - caught: the approval waiting in the posting's
  projection upsert with the wallet already held;
- **the arbiter probe**: `loan_disbursement_loan_unique` dropped - **survived** (a catalogue-driven probe cannot see a
  unique that is gone); the seventy-one pinned; re-performed - caught. `loan_payout_loan_unique` widened to
  `(loan_id, id)` - **survived** (it passed as a foreign-key target); the eight targets pinned; one re-performance void
  (the build failed before the assertion), the next caught;
- **§13.4's re-dispatch probe** - **survived**, an equivalent mutant at the money's level (above);
- **the loan id in the exception message**, restored - caught;
- the payoff's crash and re-drive (two mutations), the terminal edges (five), the proof's non-negativity arm and its
  phantom entry, the amendment's rejection audit, the acceptance's user agent, DPD off by one, the capital route's
  permission (deleted, then weakened), the amendment read's party filter, the acceptance's and the draw's assurance
  checks, the waiver and amendment desks' `FAILED` records, the capital desk's key, the payout dispatch inside a
  transaction (twice) - every one red, several only at a backstop beneath the test's own assertion (a `CHECK` or
  another trigger branch refusing the edge first), each recorded as it fell.

One run was void and is recorded as no verdict. Three survivals are recorded as survivals: the two that exposed the
probe's own blind spots (both corrected, then caught) and the one equivalent mutant.

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` - both products from application to closure, end to end through HTTP, against the simulators (the production reality, below) |
| 2 | Architectural boundaries respected | `PASS` - `LendingModuleIsolationTest` and its siblings; `lending` → `ledger`, `platform`, `sharedkernel` only |
| 3 | Required invariants tested | `PASS` - fifteen `Phase: 11` invariants, every one with performed §2 rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` - all fourteen of §14, two by recorded rationale |
| 5 | Security requirements implemented | `PASS` - area 5 |
| 6 | Observability exists | `PASS` - the §15 series, sixteen alert rules each seen to fire, spans per step, the rules resolved against a live scrape |
| 7 | Integration tests pass | **`PARTIAL` - the standing deviation, recorded** (below) |
| 8 | Documentation reflects reality | `PASS` - area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` - this review's finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` - **by this review's act**: ADR-0090…0100, each read against the code and corrected first |
| 11 | No unresolved critical issues | `PASS` - none critical; every important finding corrected and probed, but the settlement suites recorded on the owner's instruction |
| 12 | Formal phase review conducted | `PASS` - this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction (R15, re-affirmed for
Phase 11) skips the fleet-wide `databaseTest` and `kafkaTest`; every Phase 11 item was verified by targeted tiers and
recorded in those words, and the owner's instruction for this review repeated it. This review does not pretend
otherwise - the criterion is graded **`PARTIAL`**, as the Phase 10 → 11 transition re-graded Phase 10's:

- The **hermetic** tier was run **fleet-wide on the final tree** - `./gradlew cleanTest test --continue`: **3,007 tests across
  497 suites in 20 modules, 0 failures** (lending 195 across 24, app 875 across 174 - the battery and every document guard
  among them); the **architecture** tier `cleanArchitectureTest architectureTest`: **216 across 52 suites in 20 modules, 0
  failures** (app 171 across 32). Both re-run for app after the last record edits, every document guard among them: **app's hermetic tier 875 across
  174, its architecture tier 171 across 32, 0 failures**.
- The **database** tier was run over every lending suite, fresh: lending's whole `databaseTest` (date-shifted by design)
  **289 tests across 25 suites**; app's own-container lending suites, in their own invocation, **84 across 7** (the storm
  apart); app's shared-tier lending suites **41 across 13**; platform's `ColumnClassificationTest` and
  `ApplicationRoleGrantsTest` **11 across 2** - **all 0 failures**. The storm, three consecutive green runs, above. Each
  correction's suite also ran before and after its probe.
- **Not run**, on the owner's instruction: `gradlew build`, the fleet-wide `databaseTest` and `kafkaTest`. The settlement
  suites `BatchAcceptanceDatabaseTest`, `FileReceptionDatabaseTest` and `ReadmissionDatabaseTest` are known red since
  `P9-TSK-026` and were not run (recorded, not fixed). A first lending tier and a first storm were spoiled by running
  database suites side by side (ephemeral-port exhaustion) and are not counted: every count above is from a run alone.

**No fleet-wide database or kafka count is claimed for Phase 11.** Three settlement suites have been red, unrun, since
`P9-TSK-026` - the cost the Phase 10 review named: a guard stood red unseen while the tier was skipped. The question
goes to the owner at the Phase 11 → 12 transition (decision 1).

## The financial supplement F1–F8 — re-assessed at the gate

| # | Verdict |
|---|---|
| F1 | `Met` — the trial balance zero per currency over every lending posting, at every storm reading (C5) |
| F2 | `Met` — every lending balance derivable from journal lines (`LendingSchemaHasNoMutableMoneyTest`, a planted violation refused) and replayed from zero (`LoanReplayProof`, C1 and C2 every reading) |
| F3 | `Met` — every money-moving command keyed at its door and born once beneath it (the Idempotency block); every keyed door now raced ten times under one key |
| F4 | `Met` — repayment reversal, fee and interest waivers and the fee refund implemented and tested; no `AdjustmentService` on a loan account (`LoanChartDatabaseTest`); write-off deferred to Phase 14 (a debt row) |
| F5 | `Met` — lending consumes no event for correctness: a duplicated `CreditDecisionRecorded` writes no second offer; payout outcomes are read; provider callbacks are payments' and the inbox's |
| F6 | `Met` — the Multi-instance block (area 3) |
| F7 | `Met` — `NoFloatingPointMoneyRulesTest` covers `lending` (the module-reach guard and the generic planted `double`) |
| F8 | `Met` — capital recognised through reconciliation (`-004`); the external payout reconciled by payments' and Phase 8's machinery; internal flows by the subledger proof; the safeguarding proof that subtracts `lending_funded` deferred with Phase 14/15 as owner (a debt row, written by this review) |

## The Phase 11-specific criteria

Counted from the gate: the five originals made measurable, the eighteen per-area blocks, criterion 7's standing
deviation and the production reality. Each read clause by clause against the code and its counted tests.

**The five originals.**

| # | Criterion | Verdict |
|---|---|---|
| 1 | Accrual idempotent per period | `PASS` - `interest_accrual UNIQUE (loan_id, accrual_date)` refused alone with the triggers off (`InterestAccrualDatabaseTest` and now the catalogue probe) and the ledger key's replay refused; scenario 4 (twice, ten steps, ten sweepers, a crash mid-run, a restart); the storm's accrual census every reading |
| 2 | Schedule totals reconcile exactly | `PASS` - the battery's 10,000 loans and 2,000 lines, zero rounding residual per loan, on-time billing equal to the projection, `IDENTICAL` in a second JVM (its hermetic suite run fresh in this review's tier) |
| 3 | Allocation order versioned and explicit | `PASS` - the order and the overpayment treatment fields of the four-eyes terms version pinned by the agreement; `ALLOCATION_ENGINE_V1`'s properties; full, partial, over and early payment |
| 4 | Month-end, leap-year and day-count edges | `PASS` - golden cases for 31 January and 29 February, the clamp from intended day 31, a zero-rate loan; disbursement on every day of a leap and a non-leap year and the 28th-31st by the property grid (the gate's "checked by hand" for every day is the grid's, recorded) |
| 5 | Every loan balance derivable from ledger postings | `PASS` - `LendingSchemaHasNoMutableMoneyTest` (a planted violation refused); every deciding figure from journal lines (`PurposeBalances`, never the projection - the gate's `BalanceDerivation` is the platform's name for the same read); `LoanSubledgerProof` zero for every account in every storm reading, gauged and alerting; its non-negativity arm and a phantom entry now exercised |

**Per area.**

| Block | Verdict |
|---|---|
| Loan lifecycle | `PASS` - every machine's invalid edges refused by its every-writer trigger for both roles, terminal edges included since this review; the status `CHECK` is a vocabulary rank only (it cannot refuse an edge between two known states, and four machines derive their status), recorded under the gate; `UNIQUE (decision_request_id)`; a decline never reconsidered; the withdrawal's `409`; ten sweepers one offer; the hint twice, late or never; standing lost before disbursement; `ACTIVE` whatever the condition |
| Offer and contract | `PASS` - the offer equals the approved amount and term (a line's limit asserted at the exposure and the storm); `expires_at` on the database clock; acceptance against expiry exactly one; `MULTI_FACTOR` (a lesser session on a real offer, nothing written, since this review); the hash echo; the agreement `INSERT`-only for every role; every evidence field stored and no consent purpose created (since this review); terms four-eyes, ten approvers one `ACTIVE` |
| Disbursement | `PASS` - both paths once (scenario 1; the external path's payout ten ways); the receivable born with the wallet credit; a failed disbursement posts nothing and an attempt failing at or after the deadline cancels (the gate's "past the deadline" refined, recorded in `LENDING_LIFECYCLES.md`); the hold adopted; one withdrawal under payments' key, never re-dispatched (the machine and the key - the probe's survival recorded); `FAILED`/`NOT_DISPATCHED` leave the receivable (`RETURNED` has no producer); interest from the terminal outcome; scenario 5 |
| Schedule | `PASS` - `UNIQUE (loan_id, schedule_version)`; v1 in the accrual start's transaction on both paths; non-amortising terms refused `422 lending.TermsNotAmortising` **at the terms proposal**, and an offer that cannot amortise closes its application `CLOSED_UNDECIDED` (the gate says "at the offer" - recorded); recalculation a new version, billed instalments kept; the illustrative schedule labelled |
| Interest and fees | `PASS` - accrued, due, paid and outstanding apart and proven; the base principal only; one late fee per item within its cap; no penalty interest and no prepayment fee (closed by `CHECK`); the fee waiver bounded through `ReversalService`, the paid fee refunded; the interest waiver explicit and reasoned (its lock order corrected); four-eyes at both ranks; reasons screened |
| Allocation | `PASS` - scenarios 2, 3 and 6; Σ allocation = amount by a deferred trigger proven alone; no due account negative (the proof's arm now exercised); one collection per item and date; the reversal by rows, refused for the closing repayment |
| Payoff | `PASS` - the quote immutable with its inputs; `MULTI_FACTOR`; its date only; `UNIQUE (quote_id)`; `409 lending.PayoffQuoteStale` with nothing posted; all six balances zero and the credit refunded; no rebate arithmetic; a line `CLOSED_BY_CUSTOMER`; scenarios 7 and 10; a crash after the posting re-driven (since this review) |
| Delinquency | `PASS` - DPD the date difference for every account at every storm rest (C9) and over 400 generated histories (since this review); buckets; 89/90 and the cure each once; `UNIQUE (loan_id, business_date, kind)`; a re-run day writes nothing, a missed day caught up, a terminal account skipped; the history append-only for every role; default posts nothing (`LoanConditions` imports no ledger - by construction, recorded) |
| Ledger | `PASS` - every rule of §12.8 exercised; derivable and replayable; the proofs and the trial balance at every reading; the ten purposes closed to free adjustments (a `MANUAL` origin refused by trigger; a reconciliation origin by the resolution templates only - recorded); one poster per book; `LOAN_WRITE_OFF_EXPENSE` and the entries' dates now a census (C23); F1-F8 below |
| Revolving credit line | `PASS` - the draw within the limit under L3, two racing one admitted; suspended while past due or defaulted; the wallet only; one statement per cycle; the minimum exact, due 25 days after; balances derived; a paydown restores the limit; `CLOSING` admits no draw; closure with the last receivable; exposure's arms. "No line's drawn principal above its limit at rest" holds **but by a four-eyes reversal of a paydown after a re-draw** (`-023`'s owner-approved path) - `INV-LND-14` and C10 now say so |
| Capital and exposure | `PASS` - headroom judged under L6 at every commit that deploys capital, the last capital raced (one admitted); **a repudiated statement may withdraw capital already deployed** - the recorded, alerted exception `-012` decided and this review confirmed (`INV-LND-13`'s note); capital only from bank evidence; consumption one-to-one under L0; `PlatformCreditExposure` v2 one statement, v1 replaying identically; the exposure census every reading |
| Multi-instance correctness | `PASS` - area 3: every one of §7.4's twenty-nine rows (the gate counts twenty-five) raised by its named test; every born-once arbiter refused alone (since this review); the lock order written and, since this review, true; the lock-wait tests for L0, L3 and L6; namespace `11`; the four schedules; no connection across the dispatch (since this review); M3's uncounted storm waits an owner-approved deviation |
| Idempotency | `PASS` - every keyed door raced ten times under one key (most since this review), one effect, one replayed answer, `409` in progress; every system step convergent by its unique, its edge and its key; a payout re-drive replays payments' key |
| Failure recovery | `PASS` - a crash after each step re-driven (the payoff since this review); scenario 9; the storm's crash points and killed backends; §14's fourteen rows, two by recorded rationale |
| Security and audit | `PASS` - area 5 |
| Observability | `PASS` - the thirty-three §15 series registered eagerly with closed tags (`LendingMetersTest`, and `PlannedMetersExistTest` armed by the flip); every alert broken and seen to fire (`LendingAlertRulesTest`); the rules resolved against a live scrape; amounts only in audited reports |
| Testing | `PASS` - the ten scenarios named, green and exercised in the storm (scenario 8's named test asserting part, its siblings the rest); the storm green in three consecutive fresh runs **on the final code** (above), its seven probes caught on `P11-TST-001`'s code and its three new census arms not probed (recorded); the battery's 10,000 loans and 2,000 lines `IDENTICAL` in two JVMs, a perturbed accrual, billing, statement, allocation or agreement byte flipping the verdict (run fresh in this review's hermetic tier); every task's probe in §2 and `P11-TST-*` in §4 |
| Documentation and invariants | `PASS` - area 7; every `Phase: 11` invariant with performed §2 rows; ADR-0090…0100 accepted |
| Universal criterion 7 | The standing deviation, recorded (below) - not a pass |
| The production reality | Recorded as an accepted limitation (below) |

*Gate clauses met in a form their words did not anticipate, recorded under the gate rather than re-worded (`PHASE_GATES.md`,
the note after Phase 11's criteria):* the twenty-nine contention rows; the status `CHECK` as a vocabulary rank; the
deadline's refinement; `TermsNotAmortising` at the terms proposal; `RETURNED`; the reversed paydown above a limit; the
repudiation's withdrawal of deployed capital; "checked by hand" for every day of two years.

## The owner's decisions, read against the code

The transition's L1–L12 hold as built (each ADR's acceptance below records where the built shape differs):
L1 lending capital from bank evidence (`-004`); L2 two disbursement paths (`-014`, `-015`); L3 the revolving line
(`-021`…`-023`); L8 no penalty interest, no prepayment fee, no acceleration (`FeeKind` and `RateType` closed by
`CHECK`); L11 production originates nothing (§1.1, below); L12 the ten scenarios (above). R13 — the consumption
write's owner — is credit's port, `-001`. R15 — criterion 7's standing deviation — below.

## ADR-0090 … ADR-0100: accepted

Each read against the code, every drifted passage corrected in place with a dated note, and only then accepted:
`Accepted (2026-10-15, P11-DOC-001 — read against the code and corrected first)`. None needed a material
amendment. The corrections: ADR-0090 (the ports as built — nine and the unit of work, `open` not `submit`,
`LendingObserver` and `TransactionRunner` never built, the neighbour migrations counted); ADR-0091 point 8 (L0 is
taken by the acceptance, the disbursement and the reversal, and the withdrawal takes credit's request row after L1 —
no cycle either way); ADR-0092 (`decided_by`, `text`, `MULTI_FACTOR` or `STRONG`, `-028` built); ADR-0093 (no drift
report built; `-028` built); ADR-0094 and ADR-0098 ("measured in the storm" → not measured, a debt row); ADR-0095
(accepted as written); ADR-0096 (the rule keys as built; rule 7 names no `lending.payoff:` ledger key; rule 9 never
waives accrued interest; the expectation never aged); ADR-0097 (L7 before the posting; the `payout` tag; no
`RETURNED`); ADR-0099 (`decided_by`; derived amendment states; **the interest waiver's lock order corrected in the
code**); ADR-0100 (the draw's order as built). Where the code had drifted from a decision that was right — the lock
order — the code was corrected instead.

## The production reality, recorded as accepted

Production originates nothing until unresolved #13 and #14 are answered: it composes only fail-safe credit sources,
no production decision can be `APPROVED`, and no lending terms version is activated (`422 lending.ProductNotOffered`).
Every functional criterion above is proven against the simulators. Recorded as an **accepted limitation, not a
deviation** (`PHASE_11_PLAN.md` §1.1; the debt row "Lending cannot originate in production"; `OPERATIONS_RUNBOOK.md`
§7.4's seven activation preconditions).

## Debt register (Phase 11's, after this review)

Every row is in `CURRENT_STATE.md` §Known Architectural Debt with its trigger.

| Debt | Owner |
|---|---|
| Lending cannot originate in production (#13, #14) | The phase that answers them; Phase 15 at the latest |
| Write-off, charge-off, provisioning; effective-interest amortisation of the origination fee | Phase 14 |
| The agreement and acceptance-evidence purge (`retain_until` never built) | Phase 15 |
| Capital return and income recycling (A29 named no owner) | **The owner, at the Phase 11 → 12 transition** |
| Refinance | The Phase 11 → 12 transition |
| Collections operations | Phase 13 |
| The capital row's contention unmeasured (capital tranches if a measurement demands) | Phase 16 |
| The safeguarding proof subtracting `lending_funded` | Phase 14/15 |
| The servicing sweep's volume and the DPD derivation's cost unmeasured | Phase 16 |
| No schedule-versus-actual drift report | Phase 14 |
| Credit's expiry judged on one statement's clock (fail-closed) | The owner's scheduling |
| A credit line's credit balance held, not applied to drawn principal | The owner's decision |
| The lending proofs read every account on every scraped instance | Phase 14 (present, `-030`) |
| A repudiated statement reversing capital outside L6 - **confirmed** by this review as the recorded exception | Settled |
| Three settlement suites red since `P9-TSK-026` | **The owner, at the Phase 11 → 12 transition** |

## Decisions for the owner

Collected from every Phase 11 record; none decided here.

**Carried to this review, or to the transition, by name**

1. **Universal criterion 7** - the fleet-wide `databaseTest` and `kafkaTest` tiers skipped through Phase 11 (R15, "asked again at the Phase 11 → 12 transition"): keep skipping, or run them before Phase 12 opens.
2. **The three red settlement suites** (`BatchAcceptanceDatabaseTest`, `FileReceptionDatabaseTest`, `ReadmissionDatabaseTest`, red since `P9-TSK-026`): their debt row's trigger was this review; on the owner's instruction recorded, not fixed - schedule the fixture repair (an `X-TSK`), or accept them dark another phase.
3. **`P11-TST-001`'s owner-approved deviations, carried in as findings**: I2 (five censuses at the final rest only), I4 (no forced cross-product decision/acceptance overlap), M3 (L3/L6/L7 waits uncounted; scenario 1's and 2's contention not observed), C19 (the meters' census windowed to the catalogue), I6 (C9's DPD reuses the platform's paid/billed definition - an independent Java oracle now exists, `DelinquencyStatePropertiesTest`, but not for the SQL), C12 (no scene produces a failed collection). This review grades them accepted deviations; confirm, or schedule their strengthening.
4. **Capital return and income recycling** - A29 deferred them and named no owner: assign a phase.
5. **Refinance** - the transition's decision, by the plan.
6. **A repudiated statement reversing capital outside L6** - `-012` decided "neither", this review confirmed it; overturn if wanted.

**Recorded "for the owner" by a task, never decided**

7. **A credit line's credit balance held, not applied to drawn principal** (`-022`, ADR-0100's Follow-up) - pay it down (a paydown target and a subledger arm), or keep.
8. **Credit's expiry judged on one statement's clock** (`-036`'s candidate; credit `V010`, `V011`, `V013`) - a remediation in `-036`'s shape, or accept the fail-closed late expiry.
9. **Credit's own customer door opens and cancels requests for lending's products outside lending** (`-010`) - such a request blocks a loan application (`409 lending.ApplicationOpen`); close the door for lending products, or keep.
10. **An amount origination fee is never judged against credit's minimum principal at the terms door** (`-014`) - a large fee leads to retries and a cancellation at the deadline; harmless, but a terms-time refusal would be clearer.
11. **No minimum first-cycle length for a line** (`-009`) - a line can be stated a day after opening.
12. **`-029`'s write-path change** (`decided_at` from `clock_timestamp()` after the locks) beyond its brief's "read-only" - ratify.
13. **`-030`'s choices** - the payout objective's 30 minutes, a gauge reading NaN rather than its last value, the `lending_offered` target label, and the `product` tag's case (upper on counters, lower on gauges) - ratify or rename.
14. **The payout's bare-clock stamps** (`dispatched_at`, `decided_at`, `last_observed_at` - `-033`, `X-TSK-006`'s class) - accept, or a remediation.
15. **Per-task judgements recorded as built** - `-015` (no `RETURNED` producer), `-020` (a repayment whose held credit was later spent is not reversible), `-022`/`-023` (a `CLOSING` line's reversal keeps the `ACTIVE`-only rule), `-024` (a)-(c), `-025` (1)-(3), `-026` (a)-(c), `-027`, `-028`, `-033` (1)-(5), `-036` (1)-(6), `P11-TST-002` Q1-Q7: ratify as a set.

**Found by this review**

16. **"Never re-dispatched by lending"** rests on the machine and payments' key (the probe survived as an equivalent mutant) - accept, or add a lending-side count of dispatches to scenario 5.
17. **The subledger proof throws, rather than failing the account, on a non-UUIDv7 id a raw writer planted** - accept (the gauge reads NaN and alerts), or harden the read.
18. **The operator loan read lets an employee read their own party's loan** (explanation and replay refuse it) - align, or accept as a read.
19. **Person-written lending reasons and actor ids classified `CONFIDENTIAL`**, where the platform's precedent (`audit_record.reason`, `payout_destination.proposal_reason`) is `RESTRICTED-PII` - align, or record the difference.
20. **No schedule-versus-actual drift report** - build it (a Phase 14 report), or drop it from the design.
21. **The servicing sweep's volume and the DPD derivation's cost** - characterise with the capital row's, at production volume.

## What the phase produced

One module (`lending`), twenty-eight lending migrations and five neighbours', two products on one account model, four
pure versioned engines (schedule, accrual, allocation, statement), the origination flow from application to the
agreement and its evidence, two disbursement paths, servicing (accrual, billing, statements, auto-collection,
conditions, late fees), repayment and its reversal, waivers, payoff, restructuring and prepayment, lending capital
from bank evidence, exposure counting committed and outstanding principal and open limits, the subledger and replay
proofs with the explanation door, four audited reports, thirty-three series and sixteen alert rules, four leaderless
schedules, the storm and the battery.

## What happens next

**The Phase 11 → 12 transition is the owner's to open** - this review marks nothing `READY` and initialises nothing of
Phase 12, on the owner's instruction. Its agenda is the owner-decision list above: criterion 7's standing deviation asked
again, the three red settlement suites, refinance and capital return, the storm's accepted deviations and the per-task
judgements. Phase 12 (BNPL) inherits an account model, four versioned engines, the proofs and a capital discipline it can
reuse, and the debt rows owned by Phases 13 to 16.
