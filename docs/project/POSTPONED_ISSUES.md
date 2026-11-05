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
  the latest).

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
  never on `position`, which can read over in a short month, with no amount in any tag.
- **Owner of the fix:** whoever brings the first real regime; the post-phase clean-up at the latest.
