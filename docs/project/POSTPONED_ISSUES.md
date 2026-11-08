# Postponed Issues

Issues the owner has deliberately postponed until **after the project's phases are finished**.
Each was found during a phase's work, judged not blocking, and parked here rather than fixed in the
task that found it. None is a financial-correctness defect that can be accepted silently; anything of
that kind belongs in the owning phase's gate, not here.

This file is the work list for the post-phase clean-up. When an issue is fixed, mark it `FIXED` with the
commit and the date; do not delete the entry.

| ID | Status | Found by | Area |
|---|---|---|---|
| PI-001 | `POSTPONED` | P12-TSK-003 (2026-10-17) | lending — loan account opener |
| PI-002 | `POSTPONED` | Phase 12 → 13 transition gate, R2 (2026-10-22) | merchant — card-payment fee version in force on the JVM clock |
| PI-003 | `POSTPONED` | Phase 12 → 13 transition gate, R2 (2026-10-22) | merchant — Phase 6 reason columns unscreened for card and account numbers |
| PI-004 | `POSTPONED` | P13-TST-001, by the owner (2026-10-27) | every later phase's storm (`TST-001`) — run after the project's phases (`P13-TST-001` itself closed by the owner's acceptance, 2026-10-27) |
| PI-005 | `POSTPONED` | P14-TSK-021, by the owner (2026-11-04) | lending, bnpl — a write-off approved in error has no way back; required before production use of write-offs |
| PI-006 | `POSTPONED` | P14-TSK-021, by the owner (2026-11-04) | lending, bnpl — a written-off account has no recovery rule; required before production use of write-offs |
| PI-007 | `POSTPONED` | P14-TSK-021, by the owner (2026-11-04) | bnpl — a written-off obligation has no refund rule and no dispute door; required before production use of write-offs |
| PI-008 | `POSTPONED` | P14-TSK-018, by the owner (2026-11-05) | accounting — a regulatory return answered `UNKNOWN` for good has no person's door; required before any real regulatory channel |
| PI-009 | `POSTPONED` | P14-TSK-018, by the owner (2026-11-05) | accounting — two returns can stand at the regulator after a non-delivery; required before any real regulatory channel |
| PI-010 | `POSTPONED` | P14-TSK-018, by the owner (2026-11-05) | accounting — `overtaken` cannot clear when no correction is owed; required before any real regulatory channel |
| PI-011 | `POSTPONED` | P14-TSK-019, by the owner (2026-11-05) | accounting — a safeguarding shortfall alerts nobody, and nothing issues the monthly run; required before any real regime |
| PI-012 | `POSTPONED` | P14-DOC-001, by the owner (2026-11-06) | every module — no clean run of every database tier on the final Phase 14 tree; due at the next phase's database pass at the latest |
| PI-013 | `POSTPONED` | P14-DOC-001, by the owner (2026-11-06) | accounting — a production defect: after a backward step of the database clock a month's close can read an earlier lock's run as its own, and cannot be proposed; due at the next phase's database pass at the latest; fix landed by P15-TSK-033 (2026-11-08), `FIXED` when P15-TST-002 runs its regression case green |
| PI-014 | `POSTPONED` | P14-DOC-001, by the owner (2026-11-06) | lending, bnpl, accounting, app — the database suites still red after the pass, two of them production defects; due at the next phase's database pass at the latest; item (1)'s fix landed by P15-TSK-033 (2026-11-08), proven when P15-TST-002 runs its regression cases green |
| PI-015 | `POSTPONED` | P14-DOC-001 (2026-11-06) | the mutation register — 330 rows struck on moved bytes and the database halves of about a hundred rows, not performed again; due at the next phase's database pass at the latest |
| PI-016 | `POSTPONED` | P15-TSK-001, by the owner (2026-11-08) | ledger, every posting module - a balanced journal entry written with the application's database credential outside the ledger's doors is not refused; required before production use |
| PI-017 | `POSTPONED` | P15-TSK-001, by the owner (2026-11-08) | identity - registration tells a caller that a login identifier is taken; required before the first real customer launch |

---

## PI-001 — Lending's loan account opener has no up-front judgement of existing accounts

- **Status:** `POSTPONED` (owner, 2026-10-17: "create a postponed issues md file and we will fix them after
  project phases finished").
- **Found by:** P12-TSK-003's completion gate, comparing `BnplAccounts` with its lending precedent.
- **Where:** `lending/src/main/java/com/finapp/lending/LoanAccounts.java` — `openFor(Connection, LoanId,
  CurrencyCode)` (~line 104).
- **What:** `LoanAccounts.openFor` converges the loan's six ledger accounts one by one through
  `createOrConverge` and judges each account only after converging it. It never reads the loan's existing
  accounts first. `BnplAccounts.openFor` (`bnpl/src/main/java/com/finapp/bnpl/BnplAccounts.java` ~line 107)
  reads the owner's existing accounts once (`LedgerAccountStore#findAllOwned`, no lock) and refuses before
  its first insert if any is mis-shaped, then judges each converged account again.
- **Consequence today:** a wrong-typed account is met only after earlier inserts in the same transaction (the
  whole transaction still rolls back — nothing is half-written); an account of the loan in another currency,
  or under a purpose outside the six, is never noticed by the opener, because the convergence key includes
  both the purpose and the currency. No production path creates such an account today, so this is a
  defensive gap, not a live defect.
- **Fix:** give `LoanAccounts.openFor` the same lock-free pre-read and refusal as `BnplAccounts.openFor`
  (keep the post-convergence judgement); add the matching hermetic cases to `LoanAccountsTest` and a
  database case to `LoanChartDatabaseTest`; probe it (pre-read disabled → the new case red).
- **Owner of the fix:** the post-phase clean-up.

## PI-002 — The card-payment fee version in force is chosen on the JVM clock

- **Status:** `POSTPONED` — recorded by the Phase 12 → 13 transition gate (2026-10-22) as out of its scope, at the
  coordinator's direction; **the owner confirms it as post-phase or schedules it** (the gate's owner list, N17). The
  debt row "Time decided across instances outside the session store" (`X-TSK-008`, `CURRENT_STATE.md` §Known
  Architectural Debt) already names this site and says "Phase 15 at the latest" and "the fee schedule is due before the
  next change to it"; whichever the owner chooses governs, and this entry is struck when either is paid.
- **Found by:** the gate's multi-instance audit (A3-M1) on merchant's BNPL pricing; repair R2 moved BNPL to the database
  clock and named the card side as left unchanged.
- **Where:** `app/src/main/java/com/finapp/app/checkout/CheckoutSessions.java` — `open` (~line 177) resolves
  `FeeSchedules.effectiveVersionFor(unitOfWork, merchant, Instant.now(clock))`, the instance's clock;
  `merchant/src/main/java/com/finapp/merchant/JdbcFeeScheduleStore.java` — `findEffectiveVersionFor` (~line 295) judges
  `v.effective_from <= ?` against that instant. The version is then pinned per payment (`PaymentFeePin`, merchant `V005`).
- **What:** two instances whose clocks disagree by Δ can, within Δ of a version's `effective_from`, price one session by
  the outgoing version and another by the incoming one, at the same database time. BNPL's pin now resolves at
  `clock_timestamp()` after its share locks and merchant `V012` binds `pinned_at` to `[transaction_timestamp(),
  clock_timestamp()]` (`BnplPricing`, `JdbcFeeScheduleStore.findEffectiveVersion`, R2); the card resolution does not.
- **Consequence today:** inside the skew window a card payment can be priced by a version not yet (or no longer) in force
  on the authoritative clock. The fee still conserves (`fee + net = gross`) and is explainable from its pinned version
  (`INV-MER-03`); it is the version choice, not the arithmetic, that can be off. No production merchant is priced yet.
- **Fix:** resolve the card version on the database clock after the session's locks, as R2 did for BNPL (and the same
  immediately-effective rule: a version effective at its creation is in force from its commit); add a trigger rank on the
  pin's instant like merchant `V012`'s; a regression case with the JVM clock skewed past a version boundary; probe it.
- **Owner of the fix:** the post-phase clean-up, unless the owner schedules it with `X-TSK-008`'s debt row (Phase 15 at
  the latest). *(2026-11-08: the owner did. C15, ratified 2026-11-07 and applied by `P15-TSK-002`, routes
  `X-TSK-008`'s five stores to `P15-TSK-033`, and this site, `JdbcFeeScheduleStore.findEffectiveVersionFor` behind
  `FeeSchedules`, is one of them. The fix above is that task's, and this entry is struck when its gate proves it. If
  `-033` closes before the ruling reaches it, the post-phase clean-up stays the owner.)* *(Same day: the ruling reached
  `P15-TSK-033` after its gate, which returned the five stores untaken to `X-TSK-008` and the owner; this fix is theirs
  to schedule again - recommended as its own Phase 15 item before `P15-TST-002`.)*

## PI-003 — Phase 6 merchant reason columns are not screened for card or account numbers

- **Status:** `POSTPONED` — recorded by the Phase 12 → 13 transition gate (2026-10-22) as out of its scope, at the
  coordinator's direction; **the owner confirms it as post-phase or schedules it** (the gate's owner list, N17).
- **Found by:** the gate's security audit (A5-F5) on merchant's BNPL reasons; repair R2 screened those and named the
  Phase 6 reasons as left unchanged.
- **Where:** four person-written reasons in `merchant`, each checked at most for presence and length:
  - API key revocation — `MerchantApiKeys` (~line 183), column `merchant.merchant_api_key_event.reason` (merchant `V003`);
  - card fee schedule assignment — `FeeSchedules` (~line 190), column `merchant.merchant_fee_schedule_event.reason`
    (merchant `V004`);
  - payout destination proposal — `PayoutDestination` (~line 378), column `merchant.payout_destination.proposal_reason`
    (merchant `V006`);
  - an operator's payout request — `MerchantPayouts` (~line 107), column `merchant.merchant_payout.reason` (merchant `V007`).
- **What:** every other reason door on the platform — settlement, reconciliation, credit, lending, bnpl and, since R2,
  merchant's BNPL enablement and BNPL fee schedule — passes `sharedkernel`'s `InstrumentShapes` and a PL/pgSQL twin as a
  `CHECK` (merchant `V012` added `merchant.holds_instrument_shape` and its helpers). These four do not.
- **Consequence today:** an operator could paste a card or account number into one of these reasons and it would be stored
  verbatim (and copied into `audit_record.reason`, handled at the `RESTRICTED-PII` ceiling). No written rule is broken —
  the audit record's reason is already at its ceiling — but the cross-cutting screening rule has four holes.
- **Fix:** call `InstrumentShapes` in each door (mapping the refusal to `422 api.ValidationFailed`, as `MerchantBnplDesk`
  does); a merchant migration adding `CHECK (NOT merchant.holds_instrument_shape(...))` on the four columns, `NOT VALID`
  if existing rows must be kept; the corpus-parity and raw-writer cases of
  `MerchantBnplEnablementDatabaseTest#everyBnplReasonColumnRefusesAnInstrumentShape` extended to them; probe each rank.
- **Owner of the fix:** the post-phase clean-up, or Phase 15's data-handling review if the owner schedules it there.

---

## PI-004 — Every phase's storm (`TST-001`) is run after the project's phases

- **Status:** `POSTPONED` (owner, 2026-10-27, after the Phase 13 storm had run 11.5 hours: "postpone TST-001 for all
  next phases, we will back to them after all phases are finished. remember it and remind me later").
- **Scope:** `P13-TST-001` (the risk storm) and the storm task (`TST-001`) of every later phase. Each is still planned
  with its phase; when its phase reaches it, it is recorded `POSTPONED` under this entry, and its phase's exit review
  records the deferral in place of the storm's evidence — that phase's ten-instance answer on counts stays open until
  its storm runs.
- **`P13-TST-001` when parked:** branch `P13-TST-001` at `e353e11c` (four commits on master `f733d47f`; worktree
  `.claude/worktrees/agent-ab8a70173e8f392a5`):
  - `09471d19`, `7628a1c7`, `8f648796` — the owed risk and `app` database suites run for the first time and repaired:
    test defects only, no production defect;
  - `e353e11c` — the storm harness, work in progress (`RiskStormTest`, `RiskStormWorld`, `RiskStormCensus`,
    `RiskStormFaults`, `RiskStormClock`, `RiskStormLocks`, `RiskStormRelay`, `RiskStormLatencyReport`,
    `RiskStormRules`, `RiskStormCatalogue`, `RiskStormCredit`, `RiskStormCrossBorder`), its own-container
    registration and the in-progress records.
  - Its ninth full run (seed `1793081146028`, 12 cores, two instances, PostgreSQL 18 and Kafka): **57 census checks, 0
    failing, at every round** — before the storm, with no policy, with v1 in force, six rounds, each of the fifteen
    `PHASE_13_PLAN.md` §13.2 scenarios, the filing with a lost response, the named lock waits and the crash points
    (about 2,065 s into the storm). The latency table was produced but not finalised; the probes, the final run, the
    completion gate and the records were not done.
- **Consequence today:** Phase 13's multi-instance answer rests on each task's hermetic proofs, the independent
  reviews and the partial storm run above, not on a finished storm; the T-eval latency budget (M3's split trigger) is
  not measured on record. `P13-DOC-001` records this deferral.
- **For `P13-DOC-001`'s database pass:** the three suite-repair commits above are test-only fixes the pass needs — take
  them from the parked branch rather than repairing the same suites again.
- **Fix:** after the last phase, resume each parked storm from its branch (merged up to master), finish its probes,
  final run, completion gate and records, and settle its phase's ten-instance answer.
- **Owner of the fix:** the post-phase clean-up. The coordinator reminds the owner of this entry at each phase's exit
  and when the last phase closes.
- **Update (2026-10-27, the same day):** the owner closed `P13-TST-001` by acceptance on its partial evidence
  ("mark the missings as completed"): it is `COMPLETE`, not demonstrated - the latency table, its probes, its final run and
  its gate waived, not performed. Its harness is on master (own-container), so it can still be run. This entry now covers
  the storms of **later** phases only; the parked branch `P13-TST-001` is no longer needed.
- **Recorded by `P13-DOC-001` (2026-10-27):** the exit review took the three suite-repair commits, ran the database pass
  on the final tree (green after test-only repairs) and recorded the storm's deferral in place of its evidence
  (`reviews/PHASE_13_REVIEW.md`, *The storm*); `MUTATION_TESTING.md` §4 carries a `P13-TST-001` row recording the deferral,
  not a demonstration - a convention for the owner to keep or replace for every later postponed storm. **For the
  resumption:** the review's A4-2 - an inversion through risk's (R2a) resolves as a recorded `TIMEOUT` fail-safe at the
  250 ms read budget, below PostgreSQL's 1 s `deadlock_timeout`, so the storm's lock-cycle rounds need a larger budget (or
  an assertion that no racer's assessment is a fail-safe) to see a cycle at all; and its first run must use the
  own-container suites as they now stand.

---

## PI-005 — A write-off approved in error has no way back

- **Status:** `POSTPONED` (owner, 2026-11-04, accepting the recommendation after `P14-TSK-021`'s merge). **Required before a
  write-off is used in production.**
- **What:** `WRITTEN_OFF` is terminal (`INV-LIFE-04`). No door reverses the entry or returns the account, and the write-off
  expense is closed to free adjustments, so a second person's mistake cannot be corrected through the product.
- **Consequence today:** the entry stands and the books overstate the expense until an incident's correction. What the customer
  owes is unchanged. `OPERATIONS_RUNBOOK.md` section 7.13 is the operator's incident path.
- **Fix:** a reversal machine under four eyes through `ReversalService`, with a new edge `WRITTEN_OFF -> ACTIVE` that relaxes
  `INV-LIFE-04` by decision, taking L0 and L6 so that the headroom is consumed again, and a rule for the interest not accrued in
  the gap.
- **Owner of the fix:** the post-phase clean-up, or sooner if the owner schedules it.

---

## PI-006 — A written-off account has no recovery rule

- **Status:** `POSTPONED` (owner, 2026-11-04, accepting the recommendation after `P14-TSK-021`'s merge). **Required before a
  write-off is used in production.**
- **What:** a repayment on a written-off loan or obligation is refused, and the money stays in the customer's wallet. Nothing is
  lost, and nothing is collected.
- **Consequence today, each accepted by the owner until this is built:** credit exposure counts a written-off account
  indefinitely (`P14-TSK-021`'s item (f)); risk's collections case stays open, nothing consumes `LoanWrittenOff`, and nothing goes
  to a bureau (its item (g)).
- **Fix:** a posting rule, DR the wallet / CR a recovery account, reversible and four-eyed if manual. Decide (f) and (g) with it:
  what a recovery does to exposure, to the case and to any bureau report.
- **Decide with it (owner, 2026-11-05, `P14-TSK-022`'s item (ab)):** both notices first say "Please contact us about repaying it", and until this is
  built every door refuses that money. The owner and the qualified reviewer decide here whether that sentence stays. Until then
  `OPERATIONS_RUNBOOK.md` section 7.13 tells a person what to say to that caller.
- **Owner of the fix:** the post-phase clean-up, or sooner if the owner schedules it.

---

## PI-007 — A written-off BNPL obligation has no refund rule and no dispute door

- **Status:** `POSTPONED` (owner, 2026-11-04, accepting the recommendation after `P14-TSK-021`'s merge). **Required before a
  write-off is used in production.**
- **What:** a merchant's refund on a written-off obligation is refused with the neutral `bnpl.RefundNotPostable`, and the money
  stays on the payable. `BnplDisputes.open` refuses too, because a dispute's outcome may be a refund. So a customer told "still
  owed" cannot contest the debt through the product, even one who never received the goods. A purely accounting event should not
  remove that right, which is why this cannot reach production as it stands.
- **Consequence today:** `P14-TSK-022` gives the customer a route to a person, named in the notice and in the dispute's refusal.
  That person can review and escalate, but holds no door that settles the matter until this is built.
- **Also required before production (owner, 2026-11-05, `P14-TSK-022`'s item (ab)):** no channel stands behind "contact us": this product names no phone,
  address or queue to a customer anywhere, so a person reviews only what reaches one. Name the channel and who staffs it, and give
  it to the qualified reviewer with the notice's wording.
- **Fix:** a posting rule F12 for the refund, with the dispute door reopened on it.
- **Owner of the fix:** the post-phase clean-up, or sooner if the owner schedules it.

---

## PI-008 — A regulatory return answered `UNKNOWN` for good has no person's door

- **Status:** `POSTPONED` (owner, 2026-11-05, accepting the recommendation after `P14-TSK-018`'s merge). **Required before any
  real regulatory channel.**
- **What:** a return the provider never decides stays `SUBMITTED`, so it holds `regulatory_submission_one_live` and no correction of
  that form and month can be drafted, even after a reopen. Nobody here may assert what a regulator holds, so nothing is built. The
  instrument screen reaches the same place: it discards a definite acknowledgement whose reference merely holds an instrument's shape
  (`TX2026110500001` always does; a 16-digit reference often holds a Luhn-valid window), so the regulator has acknowledged and the row
  stays `SUBMITTED` for good.
- **Consequence today:** none in this phase, because the simulated adapter cannot reach it. Past the horizon an answer that decides
  nothing backs the inquiry off to at most one a day, and an `answer_anomaly` counts on `finapp.accounting.regulatory.submission`.
- **Fix:** a four-eyes manual answer recording the regulator's own out-of-band reference, and a reference format agreed with the real
  channel.
- **Owner of the fix:** whoever brings the first real channel; the post-phase clean-up at the latest.

---

## PI-009 — Two returns can stand at the regulator after a non-delivery

- **Status:** `POSTPONED` (owner, 2026-11-05, accepting the recommendation after `P14-TSK-018`'s merge). **Required before any
  real regulatory channel.**
- **What:** a return concluded `UNDELIVERED` and then corrected leaves two at the provider if the first send was only late. The
  inquiry stops at the conclusion, so nothing would say so. It is worse once a month has moved: the month-moved door drops the horizon's
  wait, so a send whose answer was lost, then a reopen, then one "not received" after the two-minute lease opens the door within minutes
  rather than a day.
- **Consequence today:** none in this phase, because the simulated adapter cannot hold two. `OPERATIONS_RUNBOOK.md` section 10.6 tells
  the operator to ask whoever runs the provider before drafting the correction.
- **Fix:** a correction's send names the id it supersedes in the adapter's own terms, the provider treating them as one; the inquiry
  goes on after `UNDELIVERED`, on the ceiling, until a correction is acknowledged, so a late arrival surfaces as a contrary answer; and a
  minimum dwell since the last send before the door opens.
- **Owner of the fix:** whoever brings the first real channel; the post-phase clean-up at the latest.

---

## PI-010 — `overtaken` cannot clear when no correction is owed

- **Status:** `POSTPONED` (owner, 2026-11-05, accepting the recommendation after `P14-TSK-018`'s merge). **Required before any
  real regulatory channel.**
- **What:** `overtaken` clears only once an acknowledged return of the form stands at the month's present revision. After a reopen
  that changed nothing a person may decide that no correction is owed, and the standing list and the gauge then hold that return for
  good.
- **Consequence today:** a standing line nobody can clear. Alerts read `attention{kind=overtaken}`'s increase and not its level, so
  this pages once and does not stay red.
- **Fix:** a reasoned, four-eyes "no correction owed" act, audited, clearing that return's flag.
- **Owner of the fix:** whoever brings the first real channel; the post-phase clean-up at the latest.

---

## PI-011 — A safeguarding shortfall alerts nobody, and nothing issues the monthly run

- **Status:** `POSTPONED` (owner, 2026-11-05, accepting the recommendation after `P14-TSK-020`'s merge). **Required before any
  real regime.**
- **What:** a person sees a shortfall only on the form's `POSITION_BEFORE` line, the figure that warns, and `POSITION` beside it, at validation
  and attestation, and in the run's two flags, `positionBeforePrincipalUnrepaidNegative` and `positionNegative`. Nothing pages. An accountant has to issue `SAFEGUARDING_POSITION` each month, nothing does it for them, and a
  month nobody issues shows nothing at all.
- **Consequence today:** none in this phase, because the return is a reference figure on a simulated channel and asserts no legal
  obligation. `OPERATIONS_RUNBOOK.md` section 10.7's close calendar names the run (`P14-TSK-023`).
- **Fix:** issue the run with the close, and a gauge `finapp.accounting.safeguarding.shortfall`, the currencies whose
  `positionBeforePrincipalUnrepaid` is negative in the latest FINAL at the month's present revision, with an alert. Keyed on that figure and
  never on `position`, which can read over in a short month, with no amount in any tag. Its alert reads `MEMO_CHARGEBACKS`, `MEMO_WALLET_DEBT` and
  `MEMO_FX_POSITION` beside the figure, since once capital is fully lent an ordinary chargeback in flight or a holder's debt reads short (`P14-TSK-023`'s (b), accepted 2026-11-06).
- **Owner of the fix:** whoever brings the first real regime; the post-phase clean-up at the latest.

---

## PI-012 — No clean run of every database tier on the final Phase 14 tree

- **Status:** `POSTPONED` (owner, 2026-11-06, at `P14-DOC-001`'s database pass: "don't do a full clean re-run of every database
  tier on the final code. Postpone it"). **Due at the next phase's database pass at the latest.**
- **What:** the Phase 14 exit review ran the phase's one database pass (C5) on the tree as merged: every database tier of
  every module Phase 14 changed - accounting, bnpl, fx, lending, platform, reconciliation, risk, settlement, and `app`'s
  whole tier, shared and own-container - first run 134 + 320 suites, 3,682 tests, 66 failed in 40 suites. It then landed test
  repairs and production fixes and re-ran only the suites that had failed and those that test what a fix changed - **on
  2026-11-06, on `f1427b8d`'s code, with lending `V033` and bnpl `V021` (the terms fix withdrawn afterwards) still in, so not
  on the final tree**: 105 suites, 1,068 tests, 2 failed - plus the storms once each on the triage branches. **On the final
  tree** (the review's fix round, 2026-11-07, at `9cb2e51b`) it ran what the withdrawal changed and what ledger `V032` touches
  that the re-run had not reached: lending's and bnpl's `databaseTest` whole (27 suites 326/0, 20 suites 209/0) and the eight
  `app` suites writing `journal_line` with no run after `V032` (48/0) - 55 suites, 583 tests, 0 failed. No clean run of every
  tier on the final tree was made. *(Read "re-ran only the suites that had failed and those that test what a fix changed (105
  suites, 1,068 tests, then lending's tier whole)" until the fix round.)*
- **Consequence:** an interaction between the fixes landed during the pass, or between them and suites that passed before
  them, is not proven. The pass's own evidence is named suite by suite in `reviews/PHASE_14_REVIEW.md`.
- **Fix:** run every module's `databaseTest` (and its own-container sibling) on the tree as it then stands, fresh, once; the
  `kafkaTest` tier too, which Phase 14 did not reach and the pass did not run.
- **Owner of the fix:** the next phase's database pass at the latest.

## PI-013 — After a backward step of the database clock, a month's close can read an earlier lock's run as its own

- **Status:** `POSTPONED` (owner, 2026-11-06: "stop the app tier's database triage now, including the close run born after a
  backward clock step, and postpone what remains"). **A production defect.** Due at the next phase's database pass at the
  latest.
- **Found by:** `P14-DOC-001`'s database pass: `PeriodCloseDatabaseTest`, `PeriodReopenDatabaseTest`,
  `PriorPeriodAdjustmentDatabaseTest` and `StatementsDatabaseTest` failed with `PeriodCloses$CloseNotReady: the period's present
  lock has no close run yet` on a month whose present lock's run had all nine checks concluded - a different case on each
  run. Docker Desktop's VM clock, the database's `clock_timestamp()`, was measured stepping back about 0.35 s and 2.4-2.8 s
  every 25-27 s; a production host's clock can step too.
- **Where:** `accounting/src/main/java/com/finapp/accounting/JdbcCloseRunStore.java` (~line 225): `latestRun` orders a
  period's runs by `run.started_at DESC`, and `started_at` is `clock_timestamp()` (accounting `V007`'s trigger). `CloseRuns.view`
  (`CloseRuns.java` ~397) takes that run as the period's; `holdsTheLock` (~452) then finds it is not the present lock's, so
  `readinessOf` (~419) reports no run and `PeriodCloses.propose` (~227) refuses. The approval's re-read (`PeriodCloses` ~408),
  `CloseRunDesk` (~96) and `AccountingCloseStatusReads` (~40) read the same.
- **What happens:** when the clock steps back between an earlier lock's run and the present lock's, the earlier run reads as
  the latest. Nothing is written wrongly and no money moves; **the month cannot be proposed for the life of that lock** - only
  a four-eyes unlock and a new lock get past it. Measured in the pass: a present lock at 13:45:09.444 whose run started at
  09.485, beside an earlier lock's run stamped 13:45:10.747 when the clock read 10.047.
- **Reproduction:** a ClockStep-style shadow of `clock_timestamp()` (lending's `ClockStep` is the model) with a frame of -5 s on
  the session that births the second lock's run: `CloseNotReady: the period's present lock has no close run yet`, the view
  returning the first run with `current=false`. The review kept the reproduction out of the tree.
- **Fix:** in `CloseRuns.view`, pick the run by the lock's identity - `close_run_one_per_lock` makes it exact:
  `period.lockedAt().flatMap(at -> store.runUnder(uow, id, at)).or(() -> store.latestRun(uow, id))` - or order by
  `(run.period_locked_at = period.locked_at) DESC` first. The same pattern, not seen failing: `JdbcPeriodCloseStore`'s
  `LATEST_CLOSE_SQL` orders proposals by `proposed_at DESC` (used by `PeriodCloses.repeatedProposal` and `proposalOf`), so a
  rejected proposal could read as the latest after a step; fix it at the same time. The reproduction as the regression case.
- **Owner of the fix:** the next phase's database pass at the latest; before a month is closed in production in any case.
- **Fix landed, not yet proven** (`P15-TSK-033`, 2026-11-08, commit `f672145e`): `CloseRuns.view` names the present lock's run
  by the lock's identity (`store.runUnder(period, locked_at)`, exact by `close_run_one_per_lock`), falling back to the store's
  latest only for an `OPEN` period or a lock with no run yet - never current, so never ready; `JdbcCloseRunStore.LATEST_RUN_SQL`
  ranks by the rows (the present lock's run, then the higher revision, `started_at` last) and `JdbcPeriodCloseStore
  .LATEST_CLOSE_SQL` the same way (the `PROPOSED` proposal, the higher revision, its `APPROVED` one, `proposed_at` last, which
  orders only one revision's rejected proposals, never read by an act). Hermetic: `CloseRunsTest#theRunIsTheLocksWhateverAStampSays`,
  `CloseRunStatementsTest#theLatestRunIsRankedByTheRows`, `PeriodCloseStatementsTest#theLatestCloseIsRankedByTheRows`. **The
  regression case is `CloseRunClockStepDatabaseTest`** (`app`, own container): the reproduction above - the present lock's run
  born on a session whose database clock reads ten minutes behind an earlier lock's run - and a proposal made behind a rejected
  one. Written and owed to `P15-TST-002` (C14); **`FIXED` only when that pass runs it green**. PI-014 (3)'s four suites are red
  by this defect alone, so the same pass is their proof.

## PI-014 — The database suites still red after Phase 14's pass

- **Status:** `POSTPONED` (owner, 2026-11-06, the same decision as `PI-013`). Due at the next phase's database pass at the latest.
  None is disabled. Items (1) and (2) are marked in their sources with this entry's number; item (3)'s four suites are
  marked through their shared fixture, `PeriodCloseFixture.proposed`, with `PI-013`, the defect that makes them red; items (4)
  and (5), unclassified intermittent cases, are not marked. *(Corrected by the fix round, 2026-11-07: read "each is marked in
  its source with this entry's number" until then.)*
- **The suites, each with its classification:**
  1. **`LoanTermsDatabaseTest#anOfferMadeDuringAnActivationStandsInsideItsVersion` (lending, shared tier) - a production
     defect.** A retired loan terms version's `effective_to` is `GREATEST(clock_timestamp(), transaction_timestamp(), its start +
     1 µs)` (lending `V030` ~247); an offer's `offered_at` is `GREATEST(statement_timestamp(), transaction_timestamp())`
     (`V028`). A backward step of the database clock between the offer's commit and the successor's activation ends the
     version **before** an offer pinned to it, so the offer reads as made outside its own version's period (`INV-LND-05`).
     Red once in the first run and once in a later lending run; reproduced deterministically with `ClockStep` (the activation's
     session stepped back 300 ms after the offer commits: `offered_at 17:35:36.208014`, `effective_to 17:35:36.172401`). bnpl
     `V002` (~343) and risk `V002` (~332) stamp a retirement the same way and were not checked. **A fix was tried and
     withdrawn:** lending `V033` and bnpl `V021` took the greater of that and the latest pinned `offered_at` + 1 µs; in the
     lending tier it met `LoanTermsDatabaseTest#aBackwardClockIsClamped` ("loan terms version N retired with no later ACTIVE
     successor starting where it ends", `V003`'s deferred check), and its own regression case met another suite's
     future-dated version in the shared container - so it was not a finished, green fix, and the review removed it. **Fix:** a
     new lending migration (and bnpl's twin) that ends the version after its latest offer **and** starts the successor exactly
     there, with the `ClockStep` reproduction as its regression case, run in a container of its own or with its own product.
  2. **`AlertRulesResolveTest#theAccountingRulesResolve` (app, shared tier) - a production defect.** `AccountingGaugeRefresher`
     cannot be restarted: its single-thread executor is made once in the constructor, `stop()` shuts it down for good, and
     `start()` only sets `running`. After a context stop and start (Spring's test-context pause and restart; in production any
     restart within one JVM, or a checkpoint and restore) every accounting gauge reads `NaN` for good - `AccountingGaugeRead`
     swallows the rejected read - and the unreadable-gauge alerts fire on every instance. Red whenever the suite runs after a
     paused context; green on a fresh one. **Fix:** make the executor in `start()` (an `AtomicReference`, as
     `AccountingCloseSchedule` does), shut it in `stop()`, and clear a gauge's in-flight flag for a read dropped at stop; a test:
     start, stop, start, every gauge a number.
  3. **`PeriodCloseDatabaseTest`, `PeriodReopenDatabaseTest`, `PriorPeriodAdjustmentDatabaseTest`, `StatementsDatabaseTest`
     (app, own-container) - red intermittently by `PI-013`'s defect**, a different case on each run ("the period's present lock
     has no close run yet"). Their test defects were repaired in the pass (the close fixture's claim loop now waits on the
     database's clock); they cannot be reliably green until `PI-013` is fixed. In the pass's targeted re-run all four were green.
  4. **`MerchantPayoutDatabaseTest` "a suspension racing a dispatch waits for it" (app, shared tier) - unclassified, most
     likely a load flake:** once, the provider call outlasted the suite's 0.7 s timeout on a machine running several builds
     (measured on the JVM's clock); green in every other run.
  5. **`RiskStormTest` (app, own-container) - unclassified, most likely load.** Its first run in the pass was red on its C1
     census - two `CARD_PAYMENT` `TIMEOUT` fail-safes no scene had forced (the 250 ms read budget met on a loaded machine:
     Phase 13's item 77 class); a later run met a restart-cut re-send answered `409 api.IdempotencyInProgress`, which the
     triage repaired in the storm's door (it retries the key shortly); two further runs were green (about 42 minutes each).
- **Not run at all:** the `kafkaTest` tier (Phase 14 changed nothing it covers); the storms (`BnplStormDatabaseTest`,
  `RiskStormTest`, `LendingStormDatabaseTest`, ...) ran only as the triage needed, on triage branches, not on the final tree.
- **Owner of the fix:** the next phase's database pass at the latest; item 2 before any production restart policy relies on
  the gauges.
- **Item (1)'s fix landed, not yet proven** (`P15-TSK-033`, 2026-11-08, commit `f672145e`): lending `V033` and bnpl `V021` end
  a retired version never before one microsecond after the latest offer pinned to it (read after the activation's lock), start
  the successor exactly there - its predecessor chosen by version number, never `max(effective_to)` over the product, the term
  the withdrawn draft kept - and floor an offering instant at its pinned version's start, without which the pushed end would
  leave the successor's first offer before its version began. `BackwardClockDatabaseTest
  #anOriginationAcrossAMidnightIllustratesTheOfferingDate` now asserts inside its transaction and rolls back, so no offer dated
  tomorrow is left in lending's shared tier for a later activation to follow (the "future-dated version" the draft's regression
  case met). Hermetic: `LoanTermsClockMigrationTest`, `BnplTermsClockMigrationTest` (text pins). **The regression cases are
  `LoanTermsDatabaseTest#anOfferBeforeABackwardStepStandsInsideItsVersion`** (the reproduction, the activation's session stepped
  back 5 s after the offer commits) **and `#anOfferAfterAnActivationOnAStepBackIsInsideItsVersion`**, with their twins in
  `BnplTermsDatabaseTest`; written and owed to `P15-TST-002` (C14); **`FIXED` only when that pass runs them green**. No stored end
  is moved (a retired version is frozen history); a past step's offer left outside its version stays visible, found by
  `SELECT o.id FROM lending.loan_offer o JOIN lending.loan_terms_version v ON v.id = o.terms_version_id WHERE o.offered_at <
  v.effective_from OR o.offered_at >= v.effective_to` (and bnpl's twin over `bnpl.bnpl_offer`). Risk `V002` stamps a policy
  version's retirement the same way and its comment makes the same claim about assessments; nothing reads a risk policy
  version by an instant, so it is latent - reported to the owner, not changed. Items (2)-(5) are not this task's: (2) is
  `P15-TSK-024`'s, (3) is PI-013's proof above, (4) and (5) are `P15-TST-002`'s to classify.

## PI-015 — The mutation register's rows struck on moved bytes, and the database halves not performed

- **Status:** `POSTPONED` (`P14-DOC-001`, 2026-11-06, under the owner's decision of that day to postpone what the pass did not
  finish - for the owner to confirm at the Phase 14 → 15 transition). Due at the next phase's database pass at the latest.
- **What:** the exit review walked every hash of `MUTATION_TESTING.md` section 2 against every committed version of every
  file. Of the 433 rows whose stated hash is older bytes of their file, it performed 103 again on present bytes (103 of
  104 probes caught; the equivalent mutant at row 2808 survived as recorded) and **struck 330 as evidence of today** - 215 of Phase 14's tasks, 78 of Phase 13's, 19 of Phase 12's, 5 of Phase 11's,
  13 naming no task - each marked in place. About a hundred rows name a database half "unproven until the database pass":
  the pass ran their suites, but none of their database probes was performed. 1,713 older-form rows carry no hash and cannot
  be walked; 15 name bytes never committed. *(Added at the gate, 2026-11-07:)* the exit review's own production changes are
  probed only in part - the adjustment door's screen, record and outcome (hermetic, caught) and ledger `V032` on its text pin;
  **not probed**: `V032` against PostgreSQL (`PostingCutoffDatabaseTest#aLineAppendedToALockedMonthsEntryIsRefused`), the
  unlock's and reopen's ends converging on the request named, `valuationRateSetReferenceGaps`, and the GL entry's `namespace`.
  *(Added by `P15-TSK-021`, 2026-11-08:)* its one-token scope change (`Actor.keyedType`) across 62 desk files, its error
  contract's step-up record and its role script reached 54 rows: it performed again on its bytes every hermetic one (45,
  each caught) and struck the nine database ones as evidence of today - rows 1084, 1396, 1436, 1437, 1438, 1827, 1829,
  1830 and 1865's probe (4) - owed here.
- **Consequence:** those rows say what was true when they were performed; that each still fails when its property is broken,
  on today's bytes, is not shown. Every `INV-ACC-*` keeps a row standing today; `INV-LED-01` and `INV-LED-05` stand on Phase 3's
  older-form rows only (section 3's Phase 14 block).
- **Fix:** perform the struck rows again on present bytes, and each database half against PostgreSQL; record each as
  performed, or strike it for good with its reason. **The method is the prose one** (`reviews/PHASE_14_REVIEW.md`, *The register
  walk*; `MUTATION_TESTING.md` section 3's Phase 14 block): match every section 2 hash token - the leading and trailing hex
  characters of a sha256, most often eight and eight - against the sha256 of every committed version of every file (`git rev-list --all --objects`, each
  blob hashed), classify each row as on present bytes, on older bytes of its file, on no committed bytes, or with no hash, and
  perform or strike the second class. The review's own walk script was not committed; any script doing that is the method.
  *(Read "the review's walk script and its brief are the method" until the fix round, 2026-11-07.)*
- **Owner of the fix:** the next phase's database pass at the latest.
- *(`P15-TSK-033`, 2026-11-08: the nine struck rows on `CloseRuns.java` - eight `CloseRunsTest` probes and
  `BreaksNeverPostTest`'s planted field - performed again on its branch's bytes of that file, each caught and restored
  byte-identical, their rows marked in place; 321 struck rows remain for the pass.)*

---

## PI-016 — A balanced journal entry written with the application's database credential is not refused

- **Status:** `POSTPONED` (owner, 2026-11-08, accepting `THREAT_MODEL.md` section 6 item 2's recommendation (b) after
  `P15-TSK-001`'s merge). **Required before production use.**
- **What:** the application's role `finapp_app` holds `INSERT` on the journal and `UPDATE` on the balance projection, and
  every module's code runs as that one role. The database refuses an unbalanced entry and any edit of the journal, but not a
  balanced entry of a registered shape inserted outside the ledger's doors, with both projection rows co-updated
  (`THREAT_MODEL.md` T15, CRITICAL).
- **Consequence until fixed:** `P15-TSK-035`'s source-join census detects an entry with no domain record behind it, on
  every book. An entry whose domain record was written first is neither refused nor detected. Phase 15 lowers the likelihood
  (the credential delivered only to the application's pods, the database reachable only from them) and not the impact.
- **Fix:** a definer-rights posting function as the journal's only `INSERT` path, validating each posting against the rule
  register and the domain record it names; the application role's `INSERT` on the journal and `UPDATE` on the balance
  projection revoked; a database role per module, so that one module's credential cannot write another's books or rows. It
  changes every module's write path and must be measured on the posting hot path. It does not stop a holder who writes the
  domain record first, and pods holding every module's credential are narrowed, not stopped.
- **Owner of the fix:** the post-phase clean-up, before production use, or sooner if the owner schedules it.

---

## PI-017 — Registration tells a caller that a login identifier is taken

- **Status:** `POSTPONED` (owner, 2026-11-08, accepting `THREAT_MODEL.md` section 6 item 6's recommendation; T47 is
  `ACCEPTED by the owner on 2026-11-08`). **Required before the first real customer launch.**
- **What:** `POST /v1/registrations` answers `422` for a taken login identifier, by design, so whether an identifier is
  registered can be learned. Authentication's paths are equal-cost (`INV-IDN-07`); the oracle is registration's alone.
- **Consequence today:** accepted, bounded by the cluster-safe registration throttle per source (`P15-TSK-022`).
- **Fix:** verify-then-tell: registration answers a taken and a free identifier alike and tells the outcome through a
  channel the identifier's owner controls. A business change to registration, which Phase 15 does not make.
- **Owner of the fix:** the first real customer launch, or sooner if the owner schedules it.
