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
| PI-002 | `FIXED` (2026-11-09, `c04dfbf3`, `P15-TSK-038`; its database case owed to `P15-TST-002`) | Phase 12 → 13 transition gate, R2 (2026-10-22) | merchant — card-payment fee version in force on the JVM clock |
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
| PI-018 | `POSTPONED` | P15-TSK-002's ruling, by the owner (2026-11-08) | reconciliation - a raw writer can close a break with another break's approved resolution; required before production use |
| PI-019 | `POSTPONED` | P15-TSK-002's ruling, by the owner (2026-11-08) | payments - a transfer can pre-empt an in-flight instant execution the platform cannot yet name; required before production use |
| PI-020 | `POSTPONED` | the privileged access review, by the owner (2026-11-08) | identity - one identity may hold both roles of a propose/approve pair; required before production use |
| PI-021 | `POSTPONED` | `P15-TSK-038`'s census, by the owner (2026-11-09) | payments - routing's version in force and the dispute door judged on an instance's clock; required before production use |
| PI-022 | `POSTPONED` | `P15-TSK-023`'s gate, by the owner (2026-11-11) | observability - an amount printed into an exception's message or a builder's generated form, which a pod's console keeps; required before production use |
| PI-023 | `POSTPONED` | the owner, during Phase 15 wave H (2026-11-12) | every module - the Phase 15 database pass (`P15-TST-002`): every database, own-container and Kafka tier, never run since Phase 15 began; run after the project's phases, with the storms |
| PI-024 | `POSTPONED` | the owner, at `P15-TSK-032`'s implementation (2026-11-12) | platform and every domain module - the platform rehearsals not run whole on `kind`: every `incidents` step, `signals` from §6.1's clearing on, the delivery's unseen steps, the release scope on the final tree, the cluster probes; run after the project's phases, with PI-004 and PI-023 |
| PI-025 | `POSTPONED` | the owner, during Phase 16 wave C (2026-11-13) | platform and every domain module - Phase 16's cluster runs not run: each task from wave C on builds its experiments, load profiles and drills and holds them by contract tests, and lists here the `kind` runs it did not perform; run after the project's phases, with PI-004, PI-023 and PI-024 |

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

- **Status:** `FIXED` (2026-11-09, `P15-TSK-038`; see the closing paragraph of this entry). `POSTPONED` until then — recorded by the Phase 12 → 13 transition gate (2026-10-22) as out of its scope, at the
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

- **`FIXED` (2026-11-09, commit `c04dfbf3`, `P15-TSK-038`, struck at that task's merge as the owner scheduled it):** `CheckoutSessions.open` resolves the version in force after the merchant's share lock in `JdbcFeeScheduleStore.findEffectiveVersionFor`'s own statement, on `GREATEST(clock_timestamp(), transaction_timestamp())`, with the immediately-effective rule (a version the insert floored judged as scheduled until the clock reaches the floor); merchant `V014` binds the pin: the insert's own stamp over any writer's value and the version in force by it, `V013`'s corrected form of `V012`'s rank. Held by `StoredBoundsAreTheDatabasesTest` and `CheckoutSessionsFeeVersionOrderTest`; the regression case with the instance's clock and the database's disagreeing across a version boundary is `StoredBoundsClockSkewDatabaseTest#theVersionInForceIsTheDatabasesClock`, written and owed to `P15-TST-002` (C14).

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
- *Scheduled by the owner (2026-11-08): `P15-TSK-038` takes the card-side fix with `X-TSK-008`'s other four stores, before `P15-TST-002`; this entry is struck when that task's gate is merged.*

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
     start, stop, start, every gauge a number. *(Fixed by `P15-TSK-024`, 2026-11-08, as written here, the refresher moved to
     `platform` as `GaugeRefresher` behind every gauge: the executor made in `start()` through an `AtomicReference`, shut in
     `stop()`, and every read dropped at the stop putting its reading's flag down. Proven hermetically, with the probe that
     makes the executor in the constructor again turning the first red:
     `GaugeRefresherTest#startStopStartLeavesEveryReadingANumber`, `GaugeRefresherTest#aReadDroppedAtTheStopDoesNotWedgeItsReading`
     and, on a real context stopped and started, `NoGaugeReadsOnTheScrapeTest#theRefresherRestartsWithTheContext`. The
     suite above, on a restarted context, is owed to `P15-TST-002`'s pass, which marks this item `FIXED`.)*
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

---

## PI-018 — A raw writer can close a reconciliation break with another break's resolution

- **Status:** `POSTPONED` (owner, 2026-11-08, accepting the recommendation of `P15-TSK-002`'s ruling, its owner item 3). **Required before production use.**
- **What:** reconciliation `V015` proves that an approved resolution stands behind every closure, not that it is that break's own: a raw writer holding `finapp_app` could close a break with a `RESOLVED` event naming some unrelated `APPROVED` resolution.
- **Consequence until fixed:** only a raw writer holding the application's credential can exploit it (`THREAT_MODEL.md` T15); every domain path writes the true relation. Phase 15 narrows who holds the credential and leaves write paths as built.
- **Fix:** tie the two per resolution kind in the schema: `break_id` equal, a sibling on the same expectation, the offset item's break, a closure row. Beside `PI-016` and `P14-REM-004`.
- **Owner of the fix:** the post-phase clean-up, before production use, or sooner if the owner schedules it.

---

## PI-019 — A transfer can pre-empt an in-flight instant execution the platform cannot yet name

- **Status:** `POSTPONED` (owner, 2026-11-08, accepting the recommendation of `P15-TSK-002`'s ruling, its owner item 4). **Required before production use.**
- **What:** a scheme line carrying only `SCHEME_REF` (the optional end-to-end reference omitted) resolves through `scheme_execution_claim`, which is written at completion, so the lookup answers `UNKNOWN` while the execution is in flight and a four-eyes transfer is admitted; the late completion then posts its own credit (the re-gate's NEW-IDEM-1, 2026-10-03).
- **Consequence until fixed:** a double credit that is not silent: the completion opens an expectation that can never reach the `RESOLVED` item, goes overdue and surfaces as `MISSING_EXTERNAL` for a person's claw-back.
- **Fix:** the completion side learns to detect a person-resolved item (the payments-side design ADR-0071 §2 names). A payments lifecycle change, which Phase 15 must not make (`PHASE_15_PLAN.md` §22).
- **Owner of the fix:** the post-phase clean-up, before production use, or sooner if the owner schedules it.

---

## PI-020 — One identity may hold both roles of a propose/approve pair

- **Status:** `POSTPONED` (owner, 2026-11-08, accepting `THREAT_MODEL.md` section 6 item 9's recommendation (a)). **Required before production use.**
- **What:** `PRIVILEGED_ACCESS_REVIEW.md` §4 names the pairs of roles no identity should hold together (`ACCOUNTANT` and `FINANCIAL_CONTROLLER`, `REGULATORY_REPORTER` and `FINANCIAL_CONTROLLER`, `LOAN_SERVICING_AGENT` and `LENDING_OFFICER`, `BNPL_SERVICING_AGENT` and `BNPL_OFFICER`), and ADR-0071 §10 makes the break-resolution roles pairwise disjoint. Role assignment refuses none of these: an administrator can grant the holding, with a required, audited reason.
- **Consequence until fixed:** each such act still refuses its proposer by identity at the domain and at the database, so one person cannot propose and approve the same act; holding both roles narrows the population. The review of `PRIVILEGED_ACCESS_REVIEW.md` §5 finds such holdings.
- **Fix:** refuse the holding at assignment (a new refusal on `POST /v1/identities/{id}/roles`, its answer in the contract, and a check of the holdings that already exist), ADR-0071 §10's rule among them.
- **Owner of the fix:** the post-phase clean-up, before production use, or sooner if the owner schedules it.

---

## PI-021 — Routing's version in force and the dispute door judged on an instance's clock

- **Status:** `POSTPONED` (owner, 2026-11-09, accepting `THREAT_MODEL.md` section 6 item 27's recommendation). **Required before production use.**
- **What:** two stored bounds are still judged on an instance's clock. `findVersionInForce` is read with `Instant.now(clock)` at the payment confirmation and the withdrawal, so within the skew of a scheduled start a payment can be routed by the outgoing or the incoming version. `DisputeActs.requireRespondable`, from the response and the evidence upload, judges a stored `respond_by` in Java on the instance's clock, so within the skew of the deadline a response can be refused or admitted early or late.
- **Consequence until fixed:** bounded by the skew. Routing selects a rail and prices nothing; the dispute deadline is set by the network in days.
- **Fix:** move each read to the database's clock with its next change, one read each, as the card fee version's was (`P15-TSK-038`'s treatment).
- **Owner of the fix:** the next change to either, and in any case before production use.

## PI-022 — An amount printed into an exception's message, which the console keeps

- **Status:** `POSTPONED` (owner, 2026-11-11, accepting `THREAT_MODEL.md` section 6 item 37 (c)'s recommendation). **Required before production use.**
- **What:** read with ASM's analyser, thirteen production classes (`Money` itself, the merchant fee, FX conversion and refund classes among them) put an amount into an exception's message for a state that must not happen, or into a Lombok builder's generated form; `BigDecimal` figures appear in other messages the analyser cannot tell from any number. `MoneyReachesNoTextSinkTest#theTextResidueIsHeld` holds the set as its thirty-eight findings, per method and per instruction, so it cannot grow unnoticed.
- **Consequence until fixed:** such a message reaches no error body and no exported span, but an unhandled one is printed with its stack trace. The collector scrubs `Money`'s string form from the shipped record; the pod's console keeps it for the node's log rotation.
- **Fix:** rewrite each message to name the rule and the currency, never the figure, and suppress the two builders' generated forms: sixteen classes' messages and the tests that read them. The residue list then empties, and the test holds it empty.
- **Owner of the fix:** one small remediation entry before production use.

## PI-023 — The Phase 15 database pass (`P15-TST-002`) is run after the project's phases

- **Status:** `POSTPONED` (owner, 2026-11-12, during wave H: "no needed db pass", confirmed as postponed like the storms rather than
  run as wave I). Due after the project's phases, beside PI-004's storms.
- **What:** `P15-TST-002`, the one database pass C14 promised at the phase exit: every module's `databaseTest`, own-container and
  `kafkaTest` tiers run fresh once on the final tree (PI-012's full run); PI-013's and PI-014's regression cases; PI-014's
  unclassified suites classified; PI-015's struck rows performed again or struck for good. With it goes every database case a Phase
  15 task wrote and owed to that pass (C14): each task's report names its own, and `MUTATION_TESTING.md` and `THREAT_MODEL.md`'s
  `GAP | PI-023` rows list them.
- **Consequence until it runs:** those cases exist and were walked by hand against the DDL at their tasks' gates; none has run on
  PostgreSQL or Kafka in Phase 15. The reviews of this phase found database tests that could never have passed (a fixture breaking a
  `CHECK`, a missing child row), so some will fail when first run, and a failure may be a production defect as easily as a test
  defect. Among them are the concurrency cases the identity doors' correctness rests on (the last administrator, the role changes'
  locks), the reporting pool's capability refusals, and the regression cases of PI-013 and PI-014. PI-012, PI-014 and PI-015 stay
  `POSTPONED`: each says it is `FIXED` only when this pass runs it green. The Phase 15 exit records the pass as a deviation, as it
  records the storm; `PHASE_GATES.md` §5 Phase 15's database-pass exit item is not met.
- **Known red before it runs** *(found by the Phase 15 exit review, 2026-11-12)*: `MerchantApiKeyDatabaseTest` still asserts that an
  operator's key issuance is audited as `CUSTOMER` (its comment calls that a finding), and since `P15-TSK-021` an operator's act is
  typed `EMPLOYEE`; a test defect, its expectation to be corrected at the pass. `ColumnClassificationTest`, the classification
  register's only completeness guard, is database-tier: the review checked the 64 columns Phase 15's migrations add by hand against
  the register, and the whole schema's comparison is this pass's.
- **Fix:** run the pass as `P15-TST-002` describes, on the tree as it then stands; classify each red as a test defect (repaired) or a
  production defect (fixed in a task of its own); close PI-012, PI-014 and PI-015 or postpone them again by the owner.
- **Owner of the fix:** the owner, after the project's phases; the coordinator reminds the owner at each phase exit, with the storms.

## PI-024 — The platform rehearsals not run whole on `kind` (`P15-TSK-032`)

- **Status:** `POSTPONED` (owner, 2026-11-12, during `P15-TSK-032`'s implementation: "the remaining cluster runs are not needed").
  Due after the project's phases, beside PI-004's storms and PI-023's database pass.
- **What:** `P15-TSK-032` built the two platform scopes of the `kind` rehearsal (`infra/scripts/kind-rehearsal/platform.sh`,
  `REHEARSAL_SCOPE=signals` and `incidents`) and ran them only in development runs (`REHEARSAL_KEEP=1`), each stopping at its first
  red: dev1 to dev6 on 2026-11-12, the last of them reaching `OPERATIONS_RUNBOOK.md` §6.1. Not run on a cluster at all:
  - **every `incidents` step**: the broker outage and the refused credential (§11.6, §11.21, §11.8's freshness burn), the poison
    record and the abandoned event (§11.6, §11.20), the compromised callback key (§11.29), one instance down and the fleet blind
    (§11.8, §11.27), retention and abuse (§11.15, §11.30), the poisoned row (§11.39), break-glass under an incident with the
    broker brought to a position and the API server's audit log read (§11.25, §11.12, §11.35 step 6, §11.1.1's new group), the two
    raw writes to the books (§11.27, §11.20) and the database's unclean stop (§11.40);
  - **`signals` from §6.1's clearing on**: `CreditPolicyMissing` resolving after §6.1 (the rule's defect the run found is fixed and
    held by a promtool case, not seen to clear on a cluster), §7, §8 and §9's activations and their alerts, the domain step's
    census, the correctness pages (§11.20, §10.17's rebuild, §11.22), the operators' half (§11.26, `StaffRefusedUnmarked`), the
    telemetry stack's alerts (§11.34) and the close (§11.31's `ConfigFingerprintDisagrees`, §11.8's `CertificateExpiring`);
  - **the `delivery` scope's unseen steps** (runbook §11.36's record): the freshness order against the migrator hook, a rollback by
    digest with the migrator kept, and a failed migrator hook with its way back;
  - **the release scope** (`REHEARSAL_SCOPE=release`, K-rel) on the final tree;
  - **the cluster probes** (`REHEARSAL_PROBE`): each scope's plants removed, seen red on a cluster.
  Two parts were never built into a step, and come with this item's runs: §11.11's parked value (a merchant closed after a
  payout and the payout returned, a customer closed before a corridor return, an inbound credit no party owns, a book refund to a
  closed wallet), for which the `incidents` scope holds no step; and the access review's first performance
  (`PRIVILEGED_ACCESS_REVIEW.md` §5) past question 5, which dev5 performed alone (question 6 asks a holder, whom a script is not).
- **Consequence until it runs:** `PHASE_GATES.md` §5 Phase 15 criterion 4 (every §11 procedure rehearsed on `kind` or compose and
  a sample of each of §1-§10) is met in part: `OPERATIONS_RUNBOOK.md` §11.38 counts what ran and gives this item as the reason for
  the rest, and §5 and §7-§10 stand without a rehearsed sample. The harness, its steps and its contract tests
  (`PlatformRehearsalContractTest`, `KindRehearsalContractTest`) exist and hold the harness's text, not a run: no whole green run of
  either platform scope exists, and a step never run may fail on its first run for a reason of its own, as each of the six
  development runs did (five for a defect of the harness or of the `kind` overlay, one for a committed alerting rule's).
  `THREAT_MODEL.md`'s rows that rested on a run read `GAP | PI-024`.
- **Fix:** run `REHEARSAL_SCOPE=signals`, `incidents` and `delivery` whole, then the release scope, on the tree as it then
  stands (`.github/workflows/ci.yml` runs the two platform scopes on its nightly `schedule`); repair each red as a harness
  defect or fix it as a production defect in a task of its own; fill §11.38's counts and each section's last record from those runs
  and turn this item's `GAP` rows to `EXISTS`; perform the cluster probes and record them in `MUTATION_TESTING.md` §2.
- **Owner of the fix:** the owner, after the project's phases, with PI-004 and PI-023; the coordinator reminds the owner at each
  phase exit.

## PI-025 — Phase 16's cluster runs not run (`kind` experiments, load profiles and drills)

- **Status:** `POSTPONED` (owner, 2026-11-13, while wave C's `P16-TSK-007` and `P16-TSK-019` were running: "we dont need long running checks and tests").
  Due after the project's phases, beside PI-004's storms, PI-023's database pass and PI-024's platform rehearsals.
- **What:** from this word on, a Phase 16 task builds its harness steps, experiments (`RESILIENCE_EXPERIMENTS.md`), load
  profiles and drills, holds their text by its contract tests, and runs only short named test classes. It does not run them on a
  `kind` cluster, and it never records a run it did not perform. Runs already performed before the word keep their records,
  each on the commit it ran on. Each task lists below the runs it owes: the experiment, profile, replica count, drill or
  cluster probe, and the runbook section that would carry its record.
- **Consequence until it runs:** a measurement, limit, recovery time or verdict that rests on an owed run is not claimed. The
  experiment's register entry reads not run, and its runbook record stays empty. `THREAT_MODEL.md`'s rows that would have rested
  on such a run read `GAP | PI-025`. A mutation demonstration that needs a cluster is listed here, not recorded as performed in
  `MUTATION_TESTING.md` §2. Phase 16's exit criteria that rest on runs (`PHASE_GATES.md`, `PHASE_16_PLAN.md`) are met only in
  part, and the exit review names this item as the reason.
- **Fix:** run each owed item on the tree as it then stands, one cluster at a time. Repair each red as a harness defect, or fix it
  as a production defect in a task of its own. Fill each register entry, runbook record and capacity figure from the runs, and
  turn this item's `GAP` rows to `EXISTS`.
- **Owner of the fix:** the owner, after the project's phases, with PI-004, PI-023 and PI-024; the coordinator reminds the owner
  at each phase exit.
- **Owed runs, by task:**
  - **`P16-TSK-007`** (`OPERATIONS_RUNBOOK.md` §11.45, whose record lists what ran; `CAPACITY_MODEL.md` §3.4): **E29**, the normal,
    peak and sustained profiles at three and at seven replicas (`REHEARSAL_LOAD_PROFILE=sustained`, which holds all three, with
    `REHEARSAL_LOAD_REPLICAS` 3 and 7); **E30**, the stress profile at seven replicas (`REHEARSAL_LOAD_REPLICAS=7`), the per-replica
    saturation compared with E30's run at three; **E31**, the entry's probe, the planted slow statement (`REHEARSAL_PROBE=slow-statement`
    with the stress profile shortened) at three replicas, with its `MUTATION_TESTING.md` §2 row; and **E27's second run**, the
    demonstration on the task's tree, the entry's acceptance that its invariants hold at every sample. Ran before the word: E30 at three
    replicas (commit `89b8fc60`, red at its fourth step: the first limit) and the isolation profile at three (commit `f021383f`, green).
  - `P16-TSK-019` (the failure-injection toolkit, `OPERATIONS_RUNBOOK.md` §11.46): E01 at three replicas
    (`RESILIENCE_EXPERIMENTS.md`) on the branch's final bytes, with the scope's topology checks and the boundary's three plants of
    the same run, and the `guard-off`, `crash-with-faults` and `term-with-faults` probes on those bytes. Every cluster run of the task
    ran on commit `a6fee3a1`: E01 `PASSED` there, and the plants and the probes were seen there (`MUTATION_TESTING.md` §2). The later
    commits - the standby's phrase delivered by `secretKeyRef`, the replication role's SQL by placeholder, the names the secret
    discoverer reads - ran only in containers and through `ExperimentsRehearsalContractTest`.
  - `P16-TSK-008` (hot rows, locks, deadlocks and plans; `OPERATIONS_RUNBOOK.md` §11.48, whose record is empty; `CAPACITY_MODEL.md`
    §3.5, its measured cells `—`): **E38**, the concurrent-financial profile (`REHEARSAL_LOAD_PROFILE=concurrent`) at seven replicas and
    at three, with its control wait, its per-row waits, the server's lock log and every deadlock explained in the record; **E38's
    probe**, the transfer's fixed lock order dropped on a throwaway branch, at seven replicas, with its `MUTATION_TESTING.md` §2 row;
    **E39**, the eight-hour soak (`REHEARSAL_LOAD_PROFILE=soak`) at three replicas, with its plans window and the transactional index
    build on the journal that decides `THREAT_MODEL.md` §6 item 42 (b); **E40**, the sweeps' failure storm (`REHEARSAL_SCOPE=experiments
    REHEARSAL_EXPERIMENTS=E40`) at three replicas. Nothing ran before the word. The new SQL, the `\watch` sampler, the server's log formats
    and the readers were run in a throwaway container of the pinned PostgreSQL image (no `kind`, no application) on 2026-11-13; on a
    cluster they are unproven.
  - The coordinator's rules split (2026-11-13, branch `P16-chore-rules-split`; `docs/project/history/CHANGE_LOG.md`): kind's rules
    in two ConfigMaps, `finapp-prometheus-rules` and `finapp-prometheus-rules-domain`, projected into `/etc/prometheus-rules`. Owed:
    the observability overlay applied client-side on a `kind` cluster, both ConfigMaps admitted, and the scraper's
    `/api/v1/rules` listing every group of all thirteen rule files (`OPERATIONS_RUNBOOK.md` §11.34's alerts on `kind`). Proven
    without a cluster only: the stream rendered, `kubeconform -strict` and the pipeline's policies green.
  - `P16-TSK-021` (network, DNS, connection and disk faults, `OPERATIONS_RUNBOOK.md` §11.47, every record there empty): at three
    replicas in the `experiments` scope, **none ever run** - E07 (the database's slots held, the operator admitted), E11 (the WAL
    archive's object store stopped; it also needs that store, which `P16-TSK-024` brings, named by `REHEARSAL_E11_STORE`), E12 (the
    database hop's latency at 50, 200 and 1000 ms), E13 (resets at 1% and 10% of the links, then **the entry's probe**: a transfer cut
    inside its transaction left absent, one cut on its commit left whole, each retried by key to one effect), E14 (one replica
    black-holed from the database: **the black-holed connection's measurement**, without a socket timeout, that `P16-TSK-022` decides
    the timeout from; on the tree since that task it runs under the decided five minutes, its control without one E57's second leg), E15 (the broker's hop, then the provider's, black-holed), E16 (CoreDNS scaled to zero) and E24 (the primary's
    own 2 GiB filesystem filled; E24 must be named when the run makes its data tier). Also owed: the `crash-with-faults` and
    `term-with-faults` probes over the four new kinds of fault (a black hole, the holder, a lock, a ballast), and a first run of the
    loop-mounted volume and the local PersistentVolume on a cluster. What ran instead: `NetworkFaultExperimentsContractTest`, and short
    container checks of the node image's tools and of Toxiproxy 2.13.0 with PostgreSQL 18.6 (§11.47's last record).
  - **`P16-TSK-009`** (the connection budget at ten, `OPERATIONS_RUNBOOK.md` §11.49; `CAPACITY_MODEL.md` §4; ADR-0134): **E41**, the `load`
    scope at ten replicas with the normal profile (`REHEARSAL_LOAD_PROFILE=normal REHEARSAL_LOAD_REPLICAS=10`): the autoscaler reading
    metrics-server's CPU metric, each replica's pools at the engineering workload (`load/pools-per-replica.tsv`), the fleet's peak
    within 13 x 8 with an operator admitted at it and with the fleet held at 104; §11.49's record. Also owed by the same change, since it
    touches every scope's rendered copy: the first cluster run of `kind-rehearsal.sh`'s `fleet_patch` and of the `load` scope's new steps
    at any count (the release scope at three on these bytes is already PI-024's). The peak during a rollout at ten is E26's
    (`P16-TSK-014`). Nothing ran on a cluster: `fleet_patch` ran once by itself over a real render, and the manifest policies and the
    budget's plants in containers (§11.49's record).
  - **`P16-TSK-022`** (the database failover drill and the socket timeout; `OPERATIONS_RUNBOOK.md` §11.54, whose last record is empty,
    and §11.40's record; ADR-0139): **E06**, the primary's unclean stop in place (PI-024's step, under owner decision C11), at three
    replicas; **E57**, the database hop black-holed for the whole fleet with and without the socket timeout, at three replicas; **E05**,
    the failover on the synchronous pair, run last, at three replicas (`REHEARSAL_EXPERIMENTS="E06 E57 E05"`); **E56**, the entry's probe,
    an asynchronous standby promoted (`REHEARSAL_PROBE=async-standby` with `REHEARSAL_EXPERIMENTS=E05`), with its `MUTATION_TESTING.md` §2
    row; and the longest statement under the sustained profile, which the load scope now records (`pg-statements_longest.tsv`) and
    `P16-TSK-007`'s owed E29 run reads, the measurement ADR-0139's revision rule needs for the socket timeout. Nothing of the task ran on
    a cluster; its hermetic probes are in `MUTATION_TESTING.md` §2.
  - `P16-TSK-024` (restore and point-in-time recovery, `OPERATIONS_RUNBOOK.md` §11.35 and §11.55; ADR-0140): **E59**, the whole
    seeded restore rehearsal on the task's tree (`infra/scripts/restore-rehearsal.sh` with its defaults: the widened seed, the
    logical restore and its gate, every plant, `key-version-missing` among them), then the logical restore of a `kind` database after
    the soak (`RESTORE_SOURCE=kind:<node>` after `P16-TST-001`'s run with `REHEARSAL_KEEP=1`), its record in §11.35 and the two sizes'
    lines of `rates.tsv` in §11.55; **E60**, the point-in-time recovery to T and its `wal-gap` and `wal-damaged` plants (the same
    seeded run), its record in §11.55; **E61**, the post-recovery gate on both restores (the same run), and on `kind` the broker step
    of §11.35 step 6 for a restored database at three replicas, with **F15**, its record in §11.55. Ran instead, 2026-11-13, in
    containers: the seed's check (`RESTORE_STOP_AFTER=source`) and the recovery's mechanics over a bare server (§11.55's record).
