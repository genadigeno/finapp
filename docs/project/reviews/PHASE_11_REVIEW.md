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

⟨OUTCOME TABLE⟩

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

⟨FINDINGS TABLE⟩

## The flip

⟨FLIP⟩

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
  session and proves the waiting approval holds no wallet (`FOR UPDATE NOWAIT` on it succeeds); probed; the storm now
  proposes interest waivers too (C14 judges the order under load); `DISTRIBUTED_EXECUTION.md` §3, ADR-0099 and the plan
  record it.
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

Every lending route enumerated with its permission, its assurance and its negatives (the audit's table: twenty-eight
routes, every operator route in `RoutePermissionRegisterTest`); the five permissions and three roles pinned by
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
rows. This review adds ⟨N⟩ §2 rows for its own probes — three of them honest **survivals** (the arbiter probe twice,
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

⟨STORM SECTION⟩

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

⟨PROBES SECTION⟩

## The twelve universal exit criteria

⟨UNIVERSAL TABLE⟩

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

⟨PHASE SPECIFIC⟩

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

⟨DEBT TABLE⟩

## Decisions for the owner

⟨OWNER LIST⟩

## What the phase produced

One module (`lending`), twenty-eight lending migrations and five neighbours', two products on one account model, four
pure versioned engines (schedule, accrual, allocation, statement), the origination flow from application to the
agreement and its evidence, two disbursement paths, servicing (accrual, billing, statements, auto-collection,
conditions, late fees), repayment and its reversal, waivers, payoff, restructuring and prepayment, lending capital
from bank evidence, exposure counting committed and outstanding principal and open limits, the subledger and replay
proofs with the explanation door, four audited reports, thirty-three series and sixteen alert rules, four leaderless
schedules, the storm and the battery.

## What happens next

⟨NEXT⟩
