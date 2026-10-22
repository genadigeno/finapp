# Phase 12 → Phase 13 Transition

**Conducted:** 2026-10-22
**Parts:** the Phase 12 completion gate (the eighteen completion criteria and the twelve universal ones · the ten
financial-correctness checks · the multi-instance audit · architecture, reuse and security · test meaningfulness · the
open-items triage · findings and repair · Phase 12 completion) · Phase 13 initialisation (§10 — not started)
**Constraint:** the owner's — the gate trusts neither the tasks' word nor the exit review's. The exit review (`P12-DOC-001`,
[`PHASE_12_REVIEW.md`](PHASE_12_REVIEW.md)) had recorded Phase 12 `COMPLETE`, *pending this gate*, the same morning; the gate
re-judged every part of it from the code. CRITICAL and IMPORTANT Phase 12 defects are resolved before the transition, never
deferred as debt (`EXECUTION_PROTOCOL.md`). Like the transitions before it, this one wrote application code — the repairs in
§8, each tested, each broken on purpose to prove its test, each restored byte-identical (sha256-verified) and recorded in
`MUTATION_TESTING.md` §2. It wrote no Phase 13 code and initialised nothing of Phase 13: the transition's second part waits
for the owner (§10).

**Method.** Seven independent, adversarial, read-only audits over master `993eaec3` (Phase 12 recorded `COMPLETE` by
`P12-DOC-001`), each told not to trust the exit review and to prove every finding by file, line and a failure scenario:
**A1** completion against the documented criteria (the eighteen completion items, the universal criteria, nine tasks'
acceptance criteria spot-checked); **A2** financial correctness (ten checks, the ledger as the only money truth, Phase 11's
R1–R3 and RG-M1 classes hunted in `bnpl`); **A3** multi-instance correctness (every lock-taking statement against the lock
order, the storm's stale-read class swept); **A4** architecture, ownership and reuse (every neighbour reused through a port,
the six ADRs, the Phase 13 boundary); **A5** security, privacy, tenant isolation and audit (all forty-eight BNPL routes);
**A6** test meaningfulness (vacuous checks, fakes that diverge from PostgreSQL, sampled register rows, the database pass's
diffs); **A7** triage of every open item the exit review left — its thirty-eight-item owner list, the debt rows and PI-001 —
into **D** (a Phase 12 defect), **P** (the owner's decision) and **T** (owned debt). In parallel the coordinator ran a
**fresh baseline** on `993eaec3`. The coordinator deduplicated and classified the findings: **the first pass was `NOT
COMPLETE`**. The repairs ran on four branches in parallel and merged in order — **R1** disputes (`P12-GATE-R1`, bnpl `V018`,
merged `4a70ed32`), **R2** merchant keys, refunds, pricing and wallet holds (`P12-GATE-R2`, merchant `V012`, merged
`00be55f1`), **R3** the terms-pin race, reversals and lending (`P12-GATE-R3`, bnpl `V019`, lending `V031`, merged
`33ad3da3`), **R4** tests, documents and guards (`P12-GATE-R4`, no migration, merged `27bd7e1f`) — each branch merging
master in and re-running its own suites before its merge. **Unlike the Phase 11 → 12 precedent, no independent re-gate
verifier read the repaired tree and the tiers were not re-run fresh after the last merge — both by the owner's decision,
recorded as deviations (§7.2).**

---

## Verdict

| Part | Outcome |
|---|---|
| The eighteen completion criteria | **Four `PASS` as found, eleven `PARTIAL`, three `FAIL`** (refunds, delinquency, security) once every audit's findings are read against them — **every one `PASS` after repair** (§1). A1 on its own reading graded seventeen `PASS` and testing and documentation `PARTIAL` |
| The twelve universal criteria | As found: security `FAIL`, "no unresolved critical issues" not met (ten IMPORTANT open), failure handling, observability and documentation `PARTIAL`; **after repair eleven `PASS` and criterion 7 `PARTIAL`** — the owner's standing deviation, recorded, not passed (§1) |
| The ten financial-correctness checks | **All ten `PASS` as found and after repair** (A2); two MINOR residues repaired — a reversal could be valued before the repayment it reverses (A2-1), the refund floor's database rank never exercised live (A2-2) (§2) |
| *"Would Phase 12 remain correct if 10 service instances executed the relevant operations concurrently?"* | **`PASS`** — financially `PASS` as found (A3: no path creates, destroys or duplicates money; the coded lock order acyclic); the one IMPORTANT race, A3-I1 (an approved application closed for good during a terms activation, no money moved), repaired in `bnpl` and `lending`; every repair's own ten-instance answer `PASS` (§3) |
| Architecture and security | **A4: no CRITICAL or IMPORTANT** — every neighbour reused through a port, one copy of each engine, the Phase 13 boundary holds; **A5: `FAIL` as found** (an insider could refund their own purchase; a customer could loop disputes to never default), **`PASS` after repair** (§4) |
| Test meaningfulness | A6: mostly **STRONG**; five IMPORTANT gaps, four closed and the fifth — 123 register rows owing their live database half — discharged in part and recorded with its count (107) as a debt row (§7.1) |
| Blocking issues | **No CRITICAL. Ten IMPORTANT at the first pass (V015's hold-trigger gap, found by both A1 and A6, counted once) — every one repaired, tested and probed** in R1–R4; fifty-five probes, every one caught; no independent re-gate verifier (owner's decision) (§5) |
| MINOR findings | About thirty: most fixed in R1–R4; the rest debt rows, postponed issues or owner items, each with an owner (§6) |
| Testing | Baseline **4,511 tests, 3 failed** (G1–G3, all fixed; G4 found on a merge, fixed); every repair's fresh runs **0 failures**; criterion 7 `PARTIAL`; **no post-merge full re-run, no independent verifier, no database checks after R2's merge, the storm not re-run after the repairs** — recorded deviations (§7.2) |
| Phase 12 completion | **`COMPLETE`, confirmed after repair** (§9) |
| Phase 13 initialisation | **Not started — pending the owner's allowance** (§10) |

---

## 1. The Phase 12 completion audit — criterion by criterion

The eighteen criteria are the owner's brief for the gate, graded by A1 against the code, the migrations and the counted
tests; *as found* below folds in every other audit's findings against the criterion they touch (A1 graded seventeen `PASS`
on its own reading). *As found* is `993eaec3`; *after repair* is `27bd7e1f`.

| # | Criterion | As found | After repair | Evidence |
|---|---|---|---|---|
| 1 | Eligibility and credit-decision integration | `PARTIAL` | `PASS` | Refusals `422`/`409` through the door, the session's lapse consuming nothing, the decline's exact payload (A1). **A3-I1**: the terms pin's `SELECT … status = 'ACTIVE' FOR SHARE` that waited on an activation re-checked only the retiring row, so an approved application closed `CLOSED_UNDECIDED (PRODUCT_NOT_OFFERED)` for good and a submission answered a spurious `422` — repaired by R3 in bnpl and lending (§5) |
| 2 | Offers and acceptance | `PARTIAL` | `PASS` | `MULTI_FACTOR`, the hash echo, acceptance against expiry exactly one, the evidence and no consent (A1). A3-I1 reaches T-off; **A6-F3**: instalment 1 from a wallet with an active hold never tested (the fakes modelled no holds) — R2's `#anAcceptanceNeverTakesInstalmentOneFromAHeldBalance` |
| 3 | Agreement lifecycle | `PASS` | `PASS` | The agreement `INSERT`-only for every role; version 2 on refunds; fifty-eight terminal edges on real rows for both roles, now each asserting its own rule's message (R4, A6-F2) |
| 4 | Purchase authorisation | `PASS` | `PASS` | BNPL and card on one session complete once; acceptances racing for the last capital admit one; (P) against a wallet-paid capture in both `UUID` orders |
| 5 | Merchant payment and settlement | `PASS` | `PASS` | The payable credited exactly G − f by F1's four lines; payouts against refunds counted both orders; B7's gauge from the registry. MINOR **A3-M1**: the BNPL fee version chosen and `pinned_at` stamped on the JVM clock — R2 moved both to `clock_timestamp()` after the share locks (merchant `V012`); the card side is unchanged (PI-002) |
| 6 | Customer repayment obligations | `PARTIAL` | `PASS` | The closure ranks and the last repayment's refund (A1). **A6-F2**: `BnplRepaymentDatabaseTest#aClosedObligationRefuses` was refused by the floor rule first — "no repayment on a `CLOSED` obligation" (`INV-LND-08`) unproven; **A6-F3**: a repayment against a held balance; **A6-F4**: a payoff after a partial refund (version 2) proven only hermetically — R2, R4 (§5) |
| 7 | Instalment scheduling and collection | `PARTIAL` | `PASS` | Golden P4 cases, billing once, ten sweepers collect once (A1). **A6-F3**: a collection never taking a held balance untested — R2; MINOR A4-F6: the P4 split also computed in credit's affordability, nothing holding the two equal — R4's `BnplPayIn4SplitAgreesWithCreditTest` |
| 8 | Repayment allocation | `PASS` | `PASS` | Σ allocation = amount by the deferred trigger and a raw writer, both roles; oldest due first; the over-pay rank |
| 9 | Full and partial refunds (and the dispute's refund outcome) | `FAIL` | `PASS` | Latest first, the fee telescoping, the battery's 20,106 orders (A1, A2). **A5-F1**: a `MERCHANT_ADMINISTER` holder could mint a `BNPL_REFUND`-scoped merchant key and refund any purchase, their own included — past `PAYMENT_REFUND` and the self-dealing rule — and ACCEPT a dispute on their own purchase with a scope-less key; **A7-1**: a dispute lapse whose refund could not post (obligation `CLOSED`, wallet closed or absent) retried for ever, the refund owed recorded nowhere; **A7-2**: beside a contested dispute every refund of the purchase was refused indefinitely; MINOR A2-2 — R1, R2 (§5) |
| 10 | Cancellations and reversals | `PARTIAL` | `PASS` | Four-eyes reversal over the real ledger with its reason (A1). Two narrow orders could leave a figure wrong (both MINOR): **A2-1** a reversal valued before the repayment it reverses after a backward clock step — a day's pay-in-N interest over-charged, the replay `IDENTICAL`; **A7-3** a reversal after a refund that cancelled an instalment whole re-opening a late fee `OQ-14` releases; and **A6-F4** the waiver of a reversal-reopened fee proven only hermetically; A1-5 no test of a waiver's reason — R3, R4 |
| 11 | Delinquency | `FAIL` | `PASS` | DPD at every storm reading (C19) (A1). **A5-F2**: each withdrawal restarted the DPD and late-fee clock while the hold blocked collection and fees — open and withdraw every ~89 days and a customer never defaults; **A7-2**: a contested dispute's held instalments never aged, charged or collected; MINOR A7-5 the bucket labels fixed while the terms door admitted other bounds, A1-2 the cap's database rank never hit — R1, R3, R4 |
| 12 | Ledger integration | `PARTIAL` | `PASS` | One poster per book, F1–F8, the three proofs at every storm reading (A1, A2). **G1**: `BnplBooksHaveOnePosterTest` red on master — `P12-TSK-026`'s report reads name BNPL accounts without reader permits (the baseline, §7.2); MINOR A1-4: the gate's clause named only `MERCHANT_PAYABLE` though bnpl also posts the customer's wallet — R4 |
| 13 | Settlement / reconciliation integration | `PARTIAL` | `PASS` | The three-way proof; payouts through Phase 8's machinery; dispatches counted both sides (A1). **G2**: `ExpectationOpenerRegisterTest` red on master — `'repayment-reversal:'`, a self-dealing audit label shaped like a posting key, unclassified; MINOR A6-F6: C11's "B7" arm did not census each purchase's refunds against its net — R4 |
| 14 | Persistence and transaction boundaries | `PARTIAL` | `PASS` | The deferred `INV-BNPL-02` trigger, `#aFailureAtEveryStepLeavesNothing`, crash-and-re-drive cases (A1). **A6-F1 = A1-1**: `bnpl V015`'s two "every writer" hold triggers never ran on PostgreSQL (`V011`'s born-once trigger refused the raw row first); **A6-F2**: 68 `P0001` expectations across 13 suites could not tell which rule refused — R1, R4 |
| 15 | Consistency and idempotency | `PARTIAL` | `PASS` | Eighteen keyed doors ten under one key; the arbiter set pinned (A1, A3). **A3-I1** (above); MINOR **A3-M2**: a merchant's ACCEPT at the deadline answered a `5xx` (the trigger raising after a (P) wait) — R1, R3 |
| 16 | Security and tenant isolation | `FAIL` | `PASS` | Every merchant route in the tenancy battery; self-approval through the API `403` and `FAILED` (A1, A5). **A5-F1**, **A5-F2** (above); MINOR A5-F3 (= A7-6) self-dealing failing open on an unplaced operator, A5-F4 the operator's dispute read unjudged, A5-F5 merchant BNPL reasons unscreened, A5-F6 the scope-less ACCEPT, A5-F7 the four-eyes threshold split across disputes — R1, R2 |
| 17 | Auditability and observability | `PARTIAL` | `PASS` | Every §11 read recorded; the three schedulers lease-protected (A1). **A7-1**: no alert on `not_postable`; **A7-2**: a contest published no event and had no gauge, alert, queue or the dispute report ADR-0106 promised — R1 (§5) |
| 18 | Testing and documentation | `PARTIAL` | `PASS` — criterion 7 `PARTIAL` (§7) | A1's `PARTIAL` (MINOR: A1-3's stale "owed a first run" verify texts, two over-claiming display names). **A6-F1…F5** (§5); the baseline's three reds (§7.2); documentation drift A4-F1…F5, F8. **Found by these records**: `BACKLOG.md`'s Phase 12 status line still read "`IN_PROGRESS` … 26 of 29" — every gate from `P12-TST-002` to `P12-DOC-001` left it, the stale-second-copy class in the phase's own header — corrected here |

**The twelve universal criteria.**

| # | Criterion | As found | After repair |
|---|---|---|---|
| 1 | Required functionality exists | `PASS` | `PASS` |
| 2 | Architectural boundaries respected | `PASS` | `PASS` (A4) |
| 3 | Required invariants tested | `PASS` | `PASS` — every `Phase: 12` invariant with §2 rows; **107 rows still owe their live database half**, a debt row (§7.1) |
| 4 | Failure cases handled | `PARTIAL` (A7-1, A3-M2) | `PASS` |
| 5 | Security requirements implemented | `FAIL` (A5-F1, A5-F2) | `PASS` |
| 6 | Observability exists | `PARTIAL` (A7-1, A7-2) | `PASS` |
| 7 | Integration tests pass | `PARTIAL` | **`PARTIAL`** — the owner's standing deviation and this gate's three test decisions (§7.2) |
| 8 | Documentation reflects reality | `PARTIAL` (A1-3, A1-4, A4-F1…F5, F8; `BACKLOG.md`'s status line) | `PASS` |
| 9 | `CURRENT_STATE.md` updated | `PASS` | `PASS` — these records |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` | `PASS` — ADR-0106 amended (R1); ADR-0092, -0094, -0099 and -0101…-0105 corrected in place with dated notes |
| 11 | No unresolved critical issues | not met — ten IMPORTANT open | `PASS` — none CRITICAL; every IMPORTANT repaired |
| 12 | Formal phase review conducted | `PASS` | `PASS` — the exit review, then this gate |

**The Phase 13 boundary holds** (A4): a grep of `bnpl` and `app/bnpl` for write-off, provisioning, fraud, AML, sanctions,
spending limit, refinance, collections case, card-funded, consumer credit, velocity and risk score finds three javadoc
deferrals only; `BNPL_WRITE_OFF_EXPENSE` appears only in `AccountPurpose` and ledger `V027`'s seed; bnpl holds no `RiskSignal`
or `NotAssessed` reference; BNPL is EUR-only by `CHECK`; bnpl has no build edge to `payments`, so it cannot charge a card.
The repairs added nothing across it.

## 2. The ten financial-correctness checks

| # | Check | As found | After repair | How it is held |
|---|---|---|---|---|
| 1 | Every obligation explainable: event → operation → entry → lines → balances | `PASS` | `PASS` | Every posting through `BnplBook.post`/`reverse` under a `bnpl.` key naming its row, each row storing its entry; `BnplSubledgerProof` (six identities and an unexplained-entry count), `BnplReplayProof`, `BnplExplanations`. R3 adds F8a — DR `BNPL_FEE_INCOME` / CR `BNPL_FEES_DUE` under `bnpl.reversal-fee-release:<reversal>` — counted by the subledger, the replay and the event |
| 2 | Purchase authorisation separate from merchant settlement and customer debt | `PASS` | `PASS` | `AUTHORISED → FINANCED` beside `merchant_financing` and F1; the obligation `UNIQUE (purchase_id)`; settlement the merchant's own payout; checkout's completion inserts an order naming the financing entry, no capture |
| 3 | Merchant payable separate from `BNPL_PRINCIPAL`/`_DUE` | `PASS` | `PASS` | F1 credits the merchant-owned payable; the debt on obligation-owned accounts; only F5 touches both |
| 4 | Amounts explicit; no floating point; currency and scale explicit | `PASS` | `PASS` | `Money` everywhere; `*_minor bigint`; `double` only in gauge suppliers; `MerchantFeeSplit` G = f + net |
| 5 | Allocation follows `ALLOCATION_ENGINE_V1` | `PASS` | `PASS` | Oldest due first; the order a pinned terms field; principal not yet due refused; the due state matched to the ledger before allocating |
| 6 | Full and partial refunds hit merchant and customer correctly | `PASS` | `PASS` | Latest first, R = Σ cancelled + E; the fee by merchant's cumulative `returnedFee`, telescoping to f; B7 by arithmetic and `V012`'s bound. A2-2 (MINOR): `V012`'s floor rank never written past live — R2's raw writer for both roles, probed |
| 7 | Reversals and waivers compensate, never mutate | `PASS` | `PASS` | Through `ReversalService`, insert-only negating rows, `UNIQUE (allocation_id)`, waiver decisions `UNIQUE (waiver_id)`. A7-3 (MINOR, A7's — A2 had excluded it as an owner item): the reversal's exact swap re-opened a late fee a refund had released — R3 releases it in the reversal's transaction (F8a, `bnpl_reversal_fee_release`, `bnpl V019`); both orders reach one end state on hermetic tests and PostgreSQL |
| 8 | Every posting balances; no path to a wrong purpose | `PASS` | `PASS` | Lines balance by construction, `PostingService` validates, each component to its own due account, the trial balance in `BnplThreeWayProof` |
| 9 | No duplicate financial effect | `PASS` | `PASS` | An arbiter per act (A2's list: the purchase's claim, B2 edge, three uniques and `bnpl.financing:` key; the repayment, collection, refund, reversal, waiver, payoff, accrual, billing, credit-application, late-fee and closure uniques); R3's release born once beside its reversal |
| 10 | Internal state reconciles with external payment and settlement records | `PASS` | `PASS` | The three-way proof's merchant leg against `INV-MER-02`; payouts merchant's and Phase 8's; BNPL adds no external evidence |

**Beyond the ten** (A2): **BNPL is not a competing source of truth** — balances by `BalanceDerivation` over journal lines,
`account_balance` read nowhere in `bnpl` or `app/bnpl`, the exposure view carrying no figures; **rounding** exact (P4's
remainder on instalment 1, accrual cumulative per period, the fee by cumulative difference); **value dating** at
`max(today, floor)` with a database rank for every mover — **A2-1** (MINOR) the one gap: the reversal's value had no arm for
its original's date, so after a backward clock step a reversal could be valued the day before its repayment and that day's
accrual base count the reversed amount twice. R3 values every reversal `max(today, floor, the reversed repayment's value
date)` in Java, refuses an approval valued earlier at `bnpl V019`'s edge trigger for every writer, and gives the replay a
`VALUE_DATE` rank; the same shape in lending is fixed alike (lending `V031` adds `repayment_reversal.value_date`, the replay's
`REVERSAL` check reading the entry's lines). **Phase 11's defect classes in BNPL**: R1, R2, R3 and RG-M1 each looked for and
refuted in `bnpl` — and RG-M1 itself, Phase 11's own residual in **lending**'s payoff, is now repaired (R3, §6).

## 3. The multi-instance audit — the ten-instance question

A3 read the code of every BNPL transaction, the three schedules, every trigger that takes a lock, every idempotency door, the
ports into credit, checkout, merchant, lending capital and accounts, and payments' pair lock. **Every money-moving act is
arbitrated by a database row lock, a unique or the ledger key, and re-judged in a statement that begins after the lock is
held; claims and their effects commit in one transaction; no in-memory lease and no JVM clock decides anything financial in
`bnpl`; the coded lock order** (L0 → B1 → B2 → B3 → B5 → B4 → B6 → L6 → (P) → L8, with the documented T-svc/T-pay exception on an
obligation's own L8 rows) **is acyclic**; every trigger that locks takes B5 then B4 under writers already holding both; the
three schedules claim by one `UPDATE … FOR UPDATE SKIP LOCKED … RETURNING` re-stamping the permit on the database clock, each
step re-judging under its row lock; every door's claim and effect in one transaction, a lapsed claim arbitrated by the
domain's key lookup under the lock plus a unique. The storm's stale-read class (`P12-TST-001`'s B4 finding) swept: no other
`FOR UPDATE` reads a mutable joined row — **one remaining instance, the predicate variant A3-I1**.

- **A3-I1 (IMPORTANT)** — the terms pin returned "neither" during an activation: the `FOR SHARE` select that waited on the
  retiring version re-checked only that row (`RETIRED`), and its successor was never in the statement's snapshot. No money
  moved; an approved application reached a wrong terminal state and its approval lapsed reserved. **R3**: when the first read
  is empty, take A0 in shared mode (`pg_advisory_xact_lock_shared(12|11, hashtext(product))`) and read again in a new
  statement — a retirement commits only beside its successor, so a second empty read is genuine; T-off and lending's
  `LoanOrigination` treat an empty pin as `WAITING`, never terminal. The common path takes nothing new.
- **A3-M1 (MINOR)** — merchant's BNPL fee version in force on the JVM clock: R2 (§5, §6).
- **A3-M2 (MINOR)** — a time read before a lock wait: R1's conditional edges.
- **Observation (liveness, not correctness)** — L6 is one row per currency, held by T-pur from the capital check to commit
  across its (P) and L8 waits (T-rev likewise when it re-instates principal): one slow wallet or payable holder stalls every
  BNPL purchase and loan acceptance in that currency. Recorded as a debt row beside the capital row's measured contention.

**Workflows the repairs touched** (the rest as `PHASE_12_PLAN.md` §7.4 and `DISTRIBUTED_EXECUTION.md` §3 record them):

| Workflow | Concurrency control | Idempotency / arbiter | Gate change |
|---|---|---|---|
| Dispute opening | B5 → B4 → (P) — the payable alone on an `ACTIVE` obligation, payable and wallet as one element on a `CLOSED` one | one open per purchase; the key unique; `bnpl V018`'s cooldown trigger | R1: a `CLOSED` obligation needs an `ACTIVE` wallet; the cooldown read under B5 on the database clock |
| Merchant answer / deadline lapse | B6; the edge `UPDATE` conditional on `response_deadline_at` vs `clock_timestamp()` | the complementary conditionals; the trigger beneath | R1 (A3-M2): zero rows → `409 bnpl.DisputeNotActionable` |
| Dispute resolution (four eyes) | B5; the purchase's cumulative dispute refunds read under it | `bnpl.dispute_four_eyes_amount` in the edge and proposal triggers | R1 (A5-F7) |
| Wallet close (accounts) | the customer row, the wallet's ledger row `FOR UPDATE`, then an unlocked bnpl read | `PendingCredits.anyFor` → `BnplWalletCredits` | R1: ordered against an opening or refund outcome through the wallet row; residual recorded in ADR-0106's gate amendment (an `ACTIVE`-obligation opening racing a close falls back to the credit balance) |
| Merchant refund door, dispute ACCEPT | the key's issuer and the purchase's party judged in the act's transaction before any write; the refusal recorded in its own | unchanged | R2 (A5-F1, A5-F6) |
| BNPL fee pin (in T-app and T-pur) | the standing's share locks → `clock_timestamp()` → the version → the pin | `pinned_at` bound to `[transaction_timestamp(), clock_timestamp()]` by merchant `V012`'s trigger | R2 (A3-M1); an immediately-effective version in force from its commit |
| Terms pin (T-app, T-off; lending origination) | `FOR SHARE`; when empty, A0 shared → re-read | — | R3 (A3-I1) |
| Repayment reversal (T-rev) | L0 → B4 → B6 → L6 (principal re-instated) → (P) → L8, the fee-income row inside T-rev's one L8 call | one live proposal; `UNIQUE (allocation_id)`; the release born once with its commit-time rank | R3 (A2-1 value date, A7-3 release under B4); a reversal and a refund serialise on B4, T-rev takes no B5 — no cycle |
| Lending payoff | L3 → the catch-up → the floor | unchanged | R3 (RG-M1): applications at `max(today, floor)` |

**Answer: `PASS`.** As found, A3 answered `PASS` for money and withheld a clean answer for `-004`, `-010` and `-011` until
A3-I1 was fixed or accepted; it is fixed. Each repair answered the question for its own change: **R1 `PASS`** (the wallet
close against an opening or refund ordered through the wallet row; the cumulative four-eyes sum stable under B5, one open
dispute per purchase; ten instances opening after a withdrawal meet one B5 and one stamp), **R2 `PASS`** (no JVM state; the
issuer and the party frozen facts judged in-transaction; the pin on the one database clock, `V012` binding every writer),
**R3 `PASS`** (a pin that waited on the retiring row is granted the shared A0 once the activation releases it, no deadlock —
the shared A0 is taken only by a pin holding no terms row, and no administration act takes B1, credit, session or merchant
rows; ten approvers one reversal and one release), **R4** changed no production concurrency (a start latch in one test).
**What the answer rests on, honestly**: the storm's two-instance evidence is `P12-TST-001`'s and the exit review's, and it ran
green again inside the pre-repair baseline; **it was not re-run after the repairs** (§7.2), so R1's dispute paths, R2's key
issuer and R3's pin and release have their concurrency proven by their own database cases — the counted races and
interleavings named in §5 — not by a storm.

## 4. Architecture and security

**A4 — architecture, ownership and reuse: no CRITICAL or IMPORTANT finding.** `bnpl` reuses every neighbour through a port
— credit decisioning (`CreditBnplDecisions`; no score, bureau or attribute in `bnpl`), party standing, MFA (a schema rank on
`bnpl_acceptance`), credit's consent gate, the product bounds, `creditmath`'s engines (exactly one copy of each), lending
capital (`LendingBnplCapital` over `LendingCapitalHeadroom.underLock`), exposure v3, the wallet (`AvailableBalance.underLock`,
the (P) order identical to payments'), the ledger (`PostingService`, `ReversalService`; no `AdjustmentService`, all ten BNPL
purposes closed to free adjustments; every schema-qualified table in bnpl's SQL `bnpl.*`), merchant's fee arithmetic,
checkout, the proofs' reads and the acting party. `BnplModuleIsolationTest` runs in CI; `BnplSchemaHasNoMutableMoneyTest` only
in `databaseTest` (so not per task under the standing deviation). **ADR-0101…0106 sound as built**, none needing a material
amendment. MINOR, every one closed by R4: A4-F1 ADR-0101 decision 7's "Used by" column; A4-F2 ADR-0103 and ADR-0105 still
saying `HALF_EVEN` (the pinned schedule's policy rounds); A4-F3 ADR-0104's view "carries account ids" (it carries none);
A4-F4 the glossary's F1 in three lines (it is four — `DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G;
CR BNPL_MERCHANT_FEE_INCOME f`; `PHASE_12_REVIEW.md`:117-118 says the same and is left as the historical record, corrected
here); A4-F5 `BNPL_LIFECYCLES.md`:460's stale "effective when `-021` lands"; ADR-0106 decision 4's "+14 days" (a pinned field
of 1–60 days); A4-F6 the P4 split in credit's affordability held equal by `BnplPayIn4SplitAgreesWithCreditTest` (95,001
amounts and 20,000 random); A4-F7 one `app/accounts/ExistingWallets` resolver for lending and BNPL; A4-F8
`JdbcBnplReportReads`' comment corrected and the as-of semantics confirmed intended, one narrow edge recorded (a backward
clock step past midnight dates a posting made on D after D — a debt row).

**A5 — security, privacy, tenant isolation and audit: `FAIL` as found, `PASS` after repair.** Every one of the forty-eight
BNPL routes read for authentication, MFA, ownership or scope, idempotency and leaks; `RoutePermissionRegisterTest` pins all
twenty-eight operator routes (twenty-nine with R1's queue). **Verified clean**: logs (class names, counts, currency codes
only), exception messages, error handling, metric tags (bounded enums), spans (no attributes), events (codes only),
`toString` (redacted), audit coverage (all twenty-nine `BnplAuditAction` constants called, every refusal `FAILED` in its own
transaction), database privileges (no `DELETE` grant, column-level `UPDATE` only on state-machine rows, no `SECURITY
DEFINER`, key scopes frozen). **IMPORTANT**: **A5-F1** the refund-capable key an insider could mint for themselves, and
**A5-F2** the dispute loop — §5. **MINOR**, all repaired: A5-F3 self-dealing failing open on an unplaced operator at the
operator refund door and the dispute's operator acts (R2, R1 — now fail closed, as the corrections do; unreachable over HTTP
today, live once `EMPLOYEE` actors exist); A5-F4 the operator's dispute read let an operator read their own party's dispute
and the merchant's statement (R1: refused `403`, recorded `FAILED`; the new queue never lists the operator's own party);
A5-F5 merchant BNPL reasons unscreened for card and account numbers (R2: `InstrumentShapes` in `BnplEnablement.reasoned`,
merchant `V012`'s `merchant.holds_*` twin and `CHECK`s on four reason columns); A5-F6 a dispute ACCEPT moving merchant money
with a scope-less key (R2, with A5-F1); A5-F7 the dispute four-eyes threshold split across sequential disputes (R1: judged on
the purchase's cumulative dispute refunds). The `CUSTOMER`-typed operator sessions stay the existing Phase 15 debt row, and
with them the absence of MFA on operator acts.

## 5. Findings — CRITICAL and IMPORTANT

**CRITICAL: none.** The first pass recorded **ten IMPORTANT** findings belonging to Phase 12, one row per root cause below — V015's hold-trigger
gap was found by both A1 (graded MINOR) and A6 (graded IMPORTANT) and is counted once. Every one was
repaired before this transition, tested and probed. **No independent re-gate verifier graded them** — by the owner's
decision (§7.2); the evidence is each repair's own tests, probes and fresh runs.

| # | Finding (sources) | Root cause | Repair | Test | Probe |
|---|---|---|---|---|---|
| **I-1** | A dispute lapse whose refund cannot post stays `OPEN` for ever, retried `not_postable` with no alert and no operator path; the merchant can never close; the refund owed recorded nowhere (A7-1, with `-021`'s unbuilt pending-credits arm) | A payoff mid-dispute closes the obligation; the wallet close had no BNPL arm; the opening never judged the wallet | **R1**: `BnplDisputeRefundNotPostable` alert (runbook §8.9, §8.6 corrected); a wallet cannot close while an open BNPL dispute may refund to it (`PendingCredits.anyFor` → `BnplWalletCredits` → `BnplDisputes.anyMayRefundToWalletOf`, bnpl rows only, no accounts migration); an opening on a `CLOSED` obligation takes (P) as one element and refuses `409 bnpl.WalletUnavailable` without an `ACTIVE` wallet | `BnplDisputeApiTest#aWalletCannotCloseWhileADisputeMayRefundToIt` (the real `AccountClosing`), `BnplDisputesTest#aDisputeOfAClosedAgreementNeedsAnActiveWallet`, `BnplDisputeDatabaseTest#aClosedAgreementsDisputeRefundsToTheWallet` (a payoff mid-dispute, the lapse posting the whole refund to the wallet), `BnplAlertRulesTest` | Caught: the alert removed (2 of 6 red); the BNPL arm removed from `AccountsBeans` (the wallet closed beside an open dispute); the wallet refusal removed (hermetic and database) |
| **I-2** | A contested (`MERCHANT_RESPONDED`) dispute has no deadline, event, queue, gauge or alert; its instalments never collected, aged or charged, every refund refused, the merchant close blocked; the dispute report ADR-0106 promised never built (A7-2) | Only an operator who already knew the id could conclude it | **R1**: `bnpl.BnplDisputeContested` on the contest; the eager gauge `finapp.bnpl.dispute.contested.age` with `BnplDisputeContestedTooLong` at 7 days (NaN into `BnplGaugesUnreadable`); the operator's queue `GET /v1/operator/bnpl-disputes?status=MERCHANT_RESPONDED\|OPEN&after=` under `BNPL_DISPUTE_RESOLVE` — oldest first, keyset pages of 100, no free text, never the operator's own party, audited `bnpl.DisputeQueueRead`, `bnpl V018`'s index `bnpl_dispute_contested` — recorded in ADR-0106 as the promised report. SLA auto-resolution not built (the owner's) | `BnplDisputesTest#aContestIsAnnouncedAndQueued`, `#theQueuePagesAtAHundred`, `BnplDisputeDatabaseTest#theQueue`, `BnplDisputeApiTest#theContestedQueue`, `BnplDisputeDeskTest#theQueueIsClosed`, `#theContestedAge` | Caught: the event removed; the queue's own-party exclusion defeated |
| **I-3** | An operator holding `MERCHANT_ADMINISTER` could mint a `BNPL_REFUND`-scoped merchant key (the secret returned to them) and refund any purchase at that merchant, their own included, audited as the merchant's act; a dispute ACCEPT needed no scope (A5-F1, A5-F6; `P12-TSK-021`) | Issuance under `MERCHANT_ADMINISTER` alone; the merchant door judged scope and tenant only; self-dealing judged at the operator door only | **R2**: issuing a `BNPL_REFUND` key needs `PAYMENT_REFUND` beside `MERCHANT_ADMINISTER` (`403`, `identity.AuthorizationDenied` `FAILED`); the merchant refund door refuses a key whose `issued_by` is the purchase's party and fails closed on an issuer it cannot place (`403 bnpl.SelfDealingRefused`, `FAILED`, via `BnplActingParty.partyOfIssuer`); an ACCEPT needs the `BNPL_REFUND` scope (`403 bnpl.RefundScopeRequired`, `bnpl.RefundScopeRefused` `FAILED`) and the same issuer rule; CONTEST any key | `MerchantApiKeyDatabaseTest#aRefundScopeAsksPaymentRefundBesideMerchantAdminister`, `RoutePermissionRegisterTest#everyConditionalPermissionIsRegistered`, `BnplRefundsTest#theMerchantsDoorRefusesAKeyItsCustomersPartyIssued`, `BnplDisputesTest#anAcceptanceThroughAKeyTheCustomerIssuedIsSelfDealing`, `BnplDisputeApiTest#anInsiderMintedKeyIsSelfDealingAtBothMerchantDoors`, `#aKeyWithoutTheScopeCannotAccept` | Caught: the issuance check removed (`201` with a refund-scoped secret); the issuer rule skipped; the ACCEPT guard skipped (hermetic and live); the desk's scope check skipped |
| **I-4** | A customer could open and withdraw a dispute in a loop — each withdrawal restarting the DPD and fee clock while the hold blocked collection and fees — and never default (A5-F2; `P12-TSK-023`, ADR-0106 decision 3) | `bnpl.dispute_clock_start` counted every lift; re-opening limited only by one open dispute per purchase | **R1**: a `WITHDRAWN` dispute's lift never moves the clock (`bnpl V018` replaces `dispute_clock_start`; `BnplDisputeHold.clockFrom` the Java mirror, read by the delinquency state, the conditions and the replay); and a customer's withdrawal starts a **30-day cooldown** on its purchase (`BnplDisputes.WITHDRAWAL_COOLDOWN` = `V018`'s `dispute_withdrawal_cooldown()`, `409 bnpl.DisputeCoolingOff`, `V018`'s `BEFORE INSERT` trigger for every writer) | `BnplDisputeHoldTest#aWithdrawalNeverMovesTheClock`; the 90-day open/withdraw/re-open loop still defaulting, hermetic and database (`#theOpenWithdrawLoopStillDefaults`); `BnplDisputesTest#aWithdrawalStartsACooldown`, `BnplDisputeDatabaseTest#aWithdrawalStartsACooldownForEveryWriter`, `BnplDisputeApiTest` | Caught: the Java clock rule removed (3 of 39); `V018`'s `WITHDRAWN` arm removed (1 of 21); the cooldown check removed (2 of 35, 2 of 2); the cooldown trigger removed (1 of 1) |
| **I-5** | The terms pin returned "neither" during an activation — an approved application closed `CLOSED_UNDECIDED` for good, a spurious `422` (A3-I1; `-004`, `-010`, `-011`; the same shape in lending) | READ COMMITTED's re-check of a waited-on row sees only that row; the successor was never in the snapshot | **R3**, bnpl and lending: an empty first read takes A0 shared and reads again in a new statement; an empty pin is `WAITING` in T-off and `LoanOrigination` | `BnplTermsDatabaseTest` and `LoanTermsDatabaseTest#aPinBehindAnActivationPinsTheSuccessor` (the activation held open past retire and activate, the pin started, the commit — the successor pinned), `BnplOriginationTest#anEmptyPinWaits`, `LoanOriginationDatabaseTest#anEmptyPinWaits` | Caught: the re-read skipped (bnpl 11/1, lending 15/1); the terminal closure restored (bnpl 21/1, lending 19/2) |
| **I-6** | `bnpl V015`'s two "every writer" dispute-hold triggers never ran on PostgreSQL — the raw collection attempt was refused by `V011`'s born-once trigger first (alphabetical order), and the fee trigger had no raw test (A6-F1 = A1-1) | The raw row dated `current_date` on an unframed connection | **R1**: a raw attempt and a raw `LATE` fee on held instalment 2, dated due + 6 on a framed connection, each asserting `P0001` and the trigger's own message | `BnplDisputeDatabaseTest#theHoldTriggersAreTheRanksThatRefuse`, `#theHoldSuspendsCollectionDpdAndFees` | In-suite, caught: each trigger dropped in a rolled-back owner transaction, the row then admitted |
| **I-7** | Raw-SQL refusal tests asserting only `P0001` or `SQLException` could not tell which rule refused — 68 expectations across 13 suites; `aClosedObligationRefuses` was refused by the floor first, so `INV-LND-08`'s `CLOSED` rule was unproven; a `TRUNCATE … CASCADE` hit the waiver tables' triggers first (A6-F2) | A generic SQLSTATE every bnpl trigger shares | **R4**: `refused(conn, sql, state, reason)`; every `P0001` in the 13 bnpl database suites asserts its rule's message (41 sites, the 58 edges × 2 roles, 5 store-level); the app `SQLException`-only sites made specific; `aClosedObligationRefuses` dated on the floor; `truncateRefusedByItsOwnTrigger` isolates the fee table's trigger | the suites named; none left unspecific | Caught: `V014:461` — **master's version of the case survived the same probe**, the new one caught it; `V014:520` caught by the SQLSTATE assertion |
| **I-8** | The fakes modelled the wallet's available balance without holds; no BNPL test debited a wallet carrying an active hold (A6-F3) | `BnplApplicationFakes`' book read posted lines only | **R2**: the fake subtracts holds; app database cases with real `HoldService` holds — a repayment (100.01 refused, 100.00 admitted), a collection (`INSUFFICIENT_FUNDS`, nothing posted), instalment 1 at acceptance (`422 bnpl.InsufficientFunds`, no purchase) | `BnplRepaymentsTest#anActiveHoldIsNotSpendable`, `BnplCollectionLedgerDatabaseTest#aRepaymentNeverSpendsAHeldBalance`, `#aCollectionNeverTakesAHeldBalance`, `#anAcceptanceNeverTakesInstalmentOneFromAHeldBalance` | Caught: the fake ignoring holds (26/1); `LedgerBnplBook.availableUnderLock` reading the posted balance (8/3) |
| **I-9** | A payoff after a partial refund (version 2+) and the waiver of a reversal-reopened fee had only hermetic proof (A6-F4) | No database case | **R4**: `BnplProofDatabaseTest#aPayoffAfterAPartialRefundAtVersionTwoReplaysIdentical` (pay-in-4 and pay-in-N with accruals: versions 2 and 2, `PAID_OFF_EARLY`, the subledger zero, the replay `IDENTICAL`); `BnplWaiverDatabaseTest#aReopenedFeeIsForgivableOnlyToWhatWasNeverForgiven` (3.01 refused by the domain and the trigger for both roles, 3.00 waived, the room then 0) | as named | **Not probed** — the two cases prove existing rules on PostgreSQL; their probes stay owed among the 107 (`MUTATION_TESTING.md` §3, `:1724`, `:1725`) |
| **I-10** | 123 Phase 12 register rows owed their live database half; 27 `INV-` rows were caught only by a fake's copy of the rule (A6-F5) | The database pass ran the suites green but performed few live probes | **R4**: seven live probes performed — `bnpl_refund_conserves`, `V014:461`, `:520`, `:554`, `V010:266`, `:339`, `V013:390` — and the rest written into `MUTATION_TESTING.md` §3 row by row: **107 remain owed** as R4 recorded them (106 counting R2's merged refund-floor probe, §3's own reconciliation); a debt row | the seven `P12-GATE` rows at the end of §2 | The seven, every one caught |

**The baseline's reds and one found on a merge** — test and register defects, none in production code, each fixed by R4:

| # | Red | Cause | Fix | Evidence |
|---|---|---|---|---|
| **G1** | `BnplBooksHaveOnePosterTest` (`app` hermetic) | `P12-TSK-026`'s `BnplReports` and `JdbcBnplReportReads` name BNPL accounts without reader permits | Both permitted as readers, with reasons (they read, never post) | Green by name and in every later guard run |
| **G2** | `ExpectationOpenerRegisterTest` (`app` hermetic) | `'repayment-reversal:'` is `BnplRepaymentReversals.notSelfDealing`'s audit act label, a bare literal of a posting key's shape — no posting key | The three call sites pass the whole label (`repayment-reversal:propose`, …, the waiver's form); runtime strings identical; no register row (its proof would be vacuous) — the owner to confirm | Green by name |
| **G3** | `CheckoutFinancingDatabaseTest` (own-container) — "closes at the deadline" expected, the deferred order rule's message received | The session's deadline stamped from the JVM clock (+200 ms, a 400 ms sleep) while checkout `V004` judges on the database clock, which lagged | The arm waits (≤ 30 s) until `expires_at <= statement_timestamp()` on the database clock, the assertion unchanged; its sibling `#aSessionNotOpenIsRefusedAtOnce` (red once on a re-run) fixed alike | 10/10, 1/1, 1/1 by name, then 10/10 with the sibling — before R4's merge |
| **G4** | `BnplDisputeDatabaseTest#anAcceptanceThatWaitsPastTheDeadlineIsNotActionable` (R1's new case) answered `Acted` on R3's merge (bnpl database 195 with 1 red) | A 1 s margin against a test VM whose database clock steps by up to 1.7 s | The case polls its own framed database clock (`clock_timestamp() > deadline + 2 s`, ≤ 30 s) before releasing the held payable, never a fixed JVM sleep | The class by name twice on the final code: 22/22, 22/22 |

**Fifty-five probes in all** — R1 seventeen, R2 seventeen (fourteen and three for its merge follow-up), R3 fourteen, R4 seven —
every one caught on its run, every restore byte-identical (sha256), forty-five new rows in `MUTATION_TESTING.md` §2 (one
row re-filed as a note, A6-F12).

## 6. MINOR findings and where each went

| Finding | Disposition |
|---|---|
| A7-3 a reversal re-opening a refund-released late fee; A2-1 a reversal valued before its repayment (bnpl and lending); A7-4 lending's RG-M1 (the payoff's billing applications dated `today`) | **Fixed** by R3 — F8a and `bnpl_reversal_fee_release` (`bnpl V019`); the value date `max(today, floor, the original's)` with `V019`'s and lending `V031`'s ranks and the replays' checks; `Payoffs` passing `max(today, floor)` to `billForPayoff` and `stateForPayoff` — **and the credit line payoff's statement applications, the same defect, beyond the brief** (owner to ratify). RG-M1 has no database rank (lending's triggers never read the ledger; the replay's `ACCRUAL` check detects it) |
| A7-5 the bucket labels fixed while the terms door admitted other bounds | **Fixed** by R3 — the door admits only `(1, 30, 60, 90)`, else `422 bnpl.TermsInvalid`; deriving the labels from the bounds stays the owner's |
| A7-6 / A5-F3 self-dealing failing open; A5-F4 the operator's dispute read; A5-F7 the four-eyes split; A3-M2 the ACCEPT at the deadline | **Fixed** by R1 (dispute half, read, threshold, edges) and R2 (refund half) |
| A5-F5 merchant BNPL reasons; A5-F6 the scope-less ACCEPT; A3-M1 the BNPL fee version on the JVM clock; A2-2 the refund floor's live rank | **Fixed** by R2 (merchant `V012`); A3-M1's merge follow-up: an immediately-effective version is in force from its commit (owner to ratify) |
| A1-2 the cap's database rank; A1-3 the "owed a first run" verify texts; A1-4 the gate's two clauses; A1-5 a waiver's reason | **Fixed** by R4 — the raw cap test and probe; the discharge notes on `INV-BNPL-02`, -04, -05, -06, -07, -09, -10; the `PHASE_GATES.md` note after Phase 12's criteria; `BnplWaiversTest#aWaiverNeedsAReason`, `BnplWaiverDatabaseTest#aWaiverNeedsAReasonForEveryWriter` |
| A4-F1…F5, ADR-0106 decision 4, A4-F6, A4-F7, A4-F8's comment | **Fixed** by R4 (§4) |
| A6-F6 C11's per-purchase B7 arm; A6-F7 C8's latest-first census; A6-F9 a start latch; A6-F11 the small holes; A6-F12 the misfiled row | **Fixed** by R4 (A6-F11 in part by R1 and R2). **C8's new full latest-first walk found 0 violations over 72 refunds on bnpl's database tier and caught a planted swap; neither it nor C11's new arm has run inside the storm** (§7.2) |
| A6-F8 the storm's C1–C5, C18 judging with the production proofs, successes never counted against the driver's tally; A6-F10 the hermetic "ten…" tests are sequential loops | **Recorded** in `MUTATION_TESTING.md` §3 by R4 — beside the 107 owed rows' debt row |
| A4-F8's narrow edge — a backward clock step past midnight dates a report's posting made on D after D | **Debt row** |
| A3's observation — L6 held across (P) and L8 waits | **Debt row** (liveness), beside the capital row's measured contention |
| R3's lending empty-pin wait — no bound and no alert (only a broken database produces it) | **Debt row** and owner item |
| A7's T items — the replay's shared refund derivation; the schedules' unmetered claim failures; operator keys `CUSTOMER`-typed; PI-001 | **Existing debt rows** confirmed — Phase 14, Phase 15, Phase 15, post-phase; the claim-failure row's "unpostable lapse" half is struck, paid by R1 |
| R2's out-of-scope items — card-payment fee pins still resolved on the JVM clock; Phase 6 merchant reason columns unscreened | **Postponed issues PI-002, PI-003** (the owner to confirm, owner list N17); PI-002 also sits in the debt row "Time decided across instances outside the session store" (`X-TSK-008`), which names Phase 15 at the latest |
| `X-TSK-018`'s overlapping settlement remittance shapes | **Already routed** — the exit review's owner item #18 and its debt row (the owner's); no postponed issue added |
| A7's P items | **Owner items** (§9) |

## 7. Testing

### 7.1 What the tests prove (A6)

A6 found **no disabled or skipping test** in `bnpl` or `app/bnpl`, **every one of the 325 `Class#method` names in Phase 12's
§2 rows existing**, and `P12-TSK-027`'s and `-028`'s diffs keeping their intent (several strengthened; one over-claim, the
`TRUNCATE … CASCADE` "both refusals proven", now isolated). By category: lifecycle edges, duplicate purchases, concurrent
acceptance, repayment and allocation, duplicate collections, reversal and recovery, provider lost responses, ledger
invariants, security and tenancy **STRONG**; full and partial refunds **STRONG** at the engine (the battery's oracle is
independent) and **WEAK** in the storm's latest-first census (A6-F7, since strengthened); duplicate events and
settlement/reconciliation **ADEQUATE** (consumer-side duplicates belong to the kafka tier, not run); multi-instance **STRONG
with caveats** — in the bnpl module's suites the "instances" are threads sharing a `synchronized` fake ledger whose lock holds
nothing, so the real interleavings are the app suites' and the storm's. The five IMPORTANT gaps (A6-F1…F5) are I-6 to I-10
in §5: four closed; the fifth recorded with its count. **107 register rows still owe their live database half** — the rule
exists and its database case runs green, but nobody has yet broken the rule on PostgreSQL and watched the case go red.

### 7.2 Test evidence, honestly

**Baseline** (coordinator, master `993eaec3`, before any repair; 3 h 18 min; counted from fresh XML): every module's
hermetic and architecture tiers plus Phase 12's database selection, the storm inside it.

| Tier | Tests | Suites | Failures |
|---|---|---|---|
| Hermetic (`test`, every module) | 3,798 | 593 | **2** — G1, G2 |
| Architecture (`architectureTest`, every module) | 222 | 54 | 0 |
| Database (`databaseTest`: bnpl 182, app 39, settlement 31, platform 11) | 263 | 32 | 0 |
| `app` own-container (the Phase 12 selection, the storm among it) | 228 | 26 | **1** — G3 |
| **Total** | **4,511** | | **3** |

All three were fixed (§5), with G4, found on R3's merge.

**Each repair's fresh runs** (from fresh XML; 0 failures unless stated):

| Repair | On | Runs |
|---|---|---|
| R1 | `c9b658ad`, then `6c8604f7` (master unmoved, merged as-is) | `:bnpl:test` 558 (51 classes); `:bnpl:databaseTest` 189 (19); `BnplDisputeApiTest` 12; `:app:test` `bnpl.*` + `BnplAlertRulesTest` + ten guard suites 249 (51); `:accounts:test` 10; the accounts and app database classes 50; `:app:architectureTest` 171 (32) — the first bnpl database run had 2 red (the fail-closed rule meeting a fixture's unplaced operator), re-run green after the fixture fix |
| R2 | `852e285e` (merging R1) | merchant `BnplEnablement*`, `BnplPricing*`, `MerchantApiKey*` 50; `:bnpl:test` 562; `:bnpl:databaseTest` 190; app database 29 shared + 89 own-container; `:app:test` `bnpl.*` + eight guards 227; `:app:architectureTest` 171 — **1,318** — the first run on the merge had **18 of 28 own-container cases red** (`NOT_PRICED`: an immediately-effective fee version stamped by a JVM clock leading the database), fixed and probed before these counts |
| R3 | `94dc62dd` (merging R1 and R2) | `:bnpl:test` 574; `:bnpl:architectureTest` 3; `:lending:test` 123; `:lending:architectureTest` 3; `:app:test` `bnpl.*`, `lending.*` + eleven guards 265; `:app:architectureTest` 171; `ExpectationOpenerRegisterTest` and `BnplBooksHaveOnePosterTest` red only on the baseline's G1/G2. **Database runs on the merge, before the owner's stop**: lending database classes 126/0; app 28/0 shared and 66/0 own-container; **`:bnpl:databaseTest` 195 with 1 red** (G4) — not re-run |
| R4 | before its last merge | `:bnpl:databaseTest` 184; `:bnpl:test` 550; `:bnpl:architectureTest` 3; `:credit:test` (affordability) 15; **`:app:test` whole 1,031**; app database `BnplProofDatabaseTest` 12, `BnplOutboxAtomicityDatabaseTest` 1, `BnplOriginationDatabaseTest` 11, `MerchantBnplEnablementDatabaseTest` 9, `CheckoutFinancingDatabaseTest` 10, `LoanAccountDatabaseTest` 29; `:app:architectureTest` 171 |
| R4 | `fe0ad779` — **the tree of master `27bd7e1f`** (`git diff` empty) | `:bnpl:test` **575** (53 suites); `:lending:test` **123** (18); `:app:test` `bnpl.*` with MutationDemonstration, AdrRegisters, TestTaxonomy, DomainGlossary, PlannedMeters, ExpectationOpenerRegister, BnplBooksHaveOnePoster, OpenApiContract, RoutePermissionRegister, ErrorCodeRegistry, AuditableActionRegistry **239** (52); `:app:architectureTest` **171** (32); `BnplDisputeDatabaseTest` **22/22 twice** (G4 only) |

**This record's own runs** (documents only, branch `P12-GATE-RECORDS`, no code): the document guards by name and one
`:app:architectureTest`, fresh, from fresh XML: `:app:test --tests` `MutationDemonstrationTest`, `AdrRegistersAreReconciledTest`, `DomainGlossaryTest`, `TestTaxonomyTest`, `PlannedMetersExistTest` `--rerun` — **74 tests across 13 suites** (the five guards 51; the ArchUnit rule suites the filter selects beside them 23), **0 failures**; then `:app:architectureTest --rerun`, last — **171 tests across 32 suites** (every document guard among them), **0 failures**. This review and `CHANGE_LOG.md`, which no guard reads, took their counts after those runs.

**Deviations — recorded, not passed:**

1. **Criterion 7 is `PARTIAL`.** The owner chose **"Keep skipping fleet tiers"** (2026-10-22): `gradlew build` and the
   fleet-wide `databaseTest` and `kafkaTest` tiers were not run, before or after the repairs; previous phases' database
   suites ran only where a repair changed their code, by name. No fleet-wide database or kafka count is claimed for Phase 12.
2. **No post-merge full re-gate and no independent verifier** (owner, 2026-10-22). The baseline's 4,511 were not re-run on
   the repaired tree, and nobody independent of the repairs read the merged code against the findings. The precedent had
   both; this gate has neither.
3. **No more database checks after R2's merge** (owner, 2026-10-22). R3's and R4's merge verification was hermetic, guards
   and architecture; R3's database results on its merge predate the stop, and R4 ran exactly one database class by name, for
   G4. **On the final tree `27bd7e1f` the only database evidence is `BnplDisputeDatabaseTest` 22/22 twice.** The last whole
   `:bnpl:databaseTest` ran on R3's merge — 195 with G4 red, before G4's fix — and on R4's branch before its final merge
   (184/0, without R2's and R3's code); merchant `V012`'s database cases ran on R2's merge; bnpl `V019`'s and lending
   `V031`'s on R3's branch and merge.
4. **The storm was not re-run after the repairs.** Its last runs are `P12-DOC-001`'s on the exit review's code and the
   baseline's. Its code changed since — R1's C14 outbox fact, R2's refund-key issuer in its fixtures, R4's C8 walk and C11 B7
   arm — compiled, never run in a storm.
5. **The fleet hermetic tier was not re-run on the final tree.** The final runs cover `bnpl`, `lending`, the `app` BNPL
   selection with the guards, and the `app` architecture tier; `merchant`, `accounts` and `credit` classes ran on their
   repair's branch, by name; the whole `:app:test` (1,031) ran on R4's branch before its last merge.

So **the final evidence is each repair's own fresh runs plus R4's post-final-merge hermetic, guard and architecture runs on
code equal to master `27bd7e1f`** — every count 0 failures — with the baseline before the repairs. That is weaker than the
precedent's, by the owner's choice; it is recorded here rather than described as more.

## 8. Repairs

- **R1** — disputes. `c9b658ad`, then `6c8604f7` (the cooldown), merged `4a70ed32`. **bnpl `V018`** — replaces
  `bnpl.dispute_clock_start` (from `V015`), `bnpl_dispute_permits_only_machine_edges` and
  `bnpl_dispute_proposal_permits_only_machine_edges` (verbatim but for the cumulative amount); adds
  `dispute_withdrawal_cooldown()`, `dispute_four_eyes_amount(uuid)`, the `bnpl_dispute_respects_the_withdrawal_cooldown`
  trigger and the partial index `bnpl_dispute_contested`. No accounts migration. Items A7-1, A7-2, A5-F2 (with the
  cooldown), A5-F4, A5-F7, A3-M2, A1-1/A6-F1, A7-6 (dispute half), A6-F11 (part). Seventeen probes. Documents: ADR-0106's gate
  amendment, `BNPL_LIFECYCLES.md`, `FINANCIAL_INVARIANTS.md`, `ERROR_CONTRACT.md`, `AUDITABLE_ACTIONS.md`,
  `OPERATIONS_RUNBOOK.md` §8.6/§8.9, `infra/prometheus/rules/bnpl.yml`, `openapi.json`, the route register.
- **R2** — merchant keys, refunds, merchant pricing, wallet holds. `5733f437`, master `4a70ed32` merged in at `6d01f976`,
  `852e285e` (the immediately-effective version), merged `00be55f1`. **merchant `V012`** — the `merchant.holds_*` instrument
  screen (credit `V016`'s twin, statement for statement), `CHECK`s on the enablement's proposal, decision and withdrawal reasons
  and the BNPL fee schedule event's reason, and the BNPL pin trigger replaced to bound `pinned_at` to the transaction's span.
  Items A5-F1, A5-F6, A7-6 (refund half), A5-F5, A3-M1, A2-2, A6-F3, A6-F11 (part). Seventeen probes. Documents: ADR-0105,
  ADR-0106, `ERROR_CONTRACT.md`, `AUDITABLE_ACTIONS.md`, `DATA_CLASSIFICATION.md`, `FINANCIAL_INVARIANTS.md` (`INV-AUD-02`,
  `-03`, `INV-MER-03`), `MODULE_ARCHITECTURE.md`.
- **R3** — the terms-pin race, reversals, lending. `feb7b994` (its bnpl migration renumbered from `V020` to `V019`), master
  `00be55f1` merged in at `94dc62dd`, merged `33ad3da3`. **bnpl `V019`** — replaces
  `bnpl_repayment_reversal_permits_only_machine_edges`, `billing_component_paid` and `fee_waiver_room`; adds the
  `INSERT`-only `bnpl_reversal_fee_release` with its born-once and never-truncated triggers and the commit-time rank
  `bnpl_repayment_reversal_releases_refunded_fees`. **lending `V031`** — `repayment_reversal.value_date` (NULL on rows decided
  before `V031`) and `repayment_reversal_permits_only_machine_edges` replaced. **bnpl `V020` is unused.** Items A3-I1 (bnpl
  and lending), A2-1 (bnpl and lending), A7-3, A7-4 (RG-M1, and the line payoff), A7-5. Fourteen probes. Documents: ADR-0092,
  ADR-0094, ADR-0099, ADR-0102, ADR-0103 (the F8a row), `BNPL_LIFECYCLES.md`, `LENDING_LIFECYCLES.md`,
  `FINANCIAL_INVARIANTS.md` (`INV-LND-05`, `-10`, `INV-BNPL-06`), `DISTRIBUTED_EXECUTION.md` (namespaces 11 and 12, the
  Phase 12 lock order), `LEDGER_MODEL.md`, `DATA_CLASSIFICATION.md`.
- **R4** — tests, documents, guards. `9edf576f` (G1, G2), `dad55927` (G3), master `4a70ed32` merged in at `cfcc7566`,
  `f7d3edd1` (G3's sibling), master `33ad3da3` merged in at `ba240a98`, `fe0ad779` (G4), merged `27bd7e1f`. **No
  migration**, no production defect found. Items A6-F2, A6-F4, A6-F5, A1-2, A1-3, A1-4, A1-5, A4-F1…F8, ADR-0106 decision
  4, A6-F6, F7, F9, F11 (rest), F12, G1–G4. Seven probes. Documents: ADR-0101, -0103, -0104, -0105, -0106, `GLOSSARY.md`,
  `BNPL_LIFECYCLES.md`, `FINANCIAL_INVARIANTS.md`, `PHASE_GATES.md` (two clauses), `MUTATION_TESTING.md` §2 and §3 (the owed
  list), `JdbcBnplReportReads`' comment, `BnplBooksHaveOnePosterTest`'s javadoc.
- **The gate as a whole**, `993eaec3..27bd7e1f`: 157 files, 7,289 lines added and 497 removed — 60 production files, 73 test
  files, the rest documents and the alert rules; four migrations; forty-five §2 rows. **This transition's records**
  (`P12-GATE-RECORDS`) wrote no code: this review, `CURRENT_STATE.md`, `BACKLOG.md`, `PHASE_GATES.md`, `ROADMAP.md`,
  `CAPABILITY_MAP.md`, `TASK_HISTORY.md`, `CHANGE_LOG.md` and `POSTPONED_ISSUES.md`. The ADRs needed no follow-up the repairs
  had not written; `DECISIONS.md` and the ADR index are unchanged, as at the precedent.

## 9. Phase 12 completion

**Phase 12 — Buy Now, Pay Later — `COMPLETE`, confirmed after repair (2026-10-22).** Delivered (the exit review's): two
modules, `bnpl` and `creditmath`; two products on lending's extracted engines — pay-in-4 (four equal instalments, the first at
purchase, then every fourteen days, no interest) and pay-in-N (3, 6 or 12 monthly instalments, ACT/365F) — and two BNPL
engines (the pay-in-4 schedule, the refund allocator); origination at checkout from a keyed, MFA-assured application through
one credit decision per purchase to an immutable agreement with its evidence; the purchase, the merchant financing (the payable
credited the gross less its fee, paid through the merchant's existing payout), the obligation, credit's consumption and the
capital commitment in one transaction under the Phase 12 lock order, funded from lending capital under the shared headroom and
counted by exposure v3; merchant enablement and the BNPL fee schedule; merchant reads and the payable's drill-down; servicing
— billing, the catch-up, accrual, auto-collection from the wallet only, delinquency and default, capped late fees; repayment
and its four-eyes reversal; the fee waiver; refunds latest instalments first with the excess to the customer and the fee
pro-rated back, bounded after payout by the purchase's own net (`INV-MER-07` amended); disputes under the one refund bound;
payoff; the three-way, subledger and replay proofs and the explanation door; reports, series and alerts with the procedures of
`OPERATIONS_RUNBOOK.md` §8 (the gate added a gauge, two alert rules and §8.9); three leaderless schedules; the storm and the
battery. **What this gate added**: a dispute lapse that is never silent and a wallet that cannot close beneath it (I-1);
contested disputes announced, gauged, alerted and queued for operators (I-2); a refund-capable merchant key that needs
`PAYMENT_REFUND` and is refused on its issuer's own customer's purchase, and an ACCEPT that needs the refund scope (I-3); a
dispute loop that ends in default — a withdrawal never moves the clock and starts a 30-day cooldown (I-4); a terms pin that is
never "neither" (I-5, bnpl and lending); the dispute holds, the refusal ranks, held wallet balances and two money paths proven
on PostgreSQL (I-6 to I-9); seven owed probes performed and the rest counted (I-10); a reversal never valued before its
repayment and releasing the fee a refund released; lending's RG-M1 closed; the BNPL fee pinned on the database clock;
merchant BNPL reasons screened; self-dealing failing closed everywhere; the delinquency bounds the labels name. **Important
decisions**: ADR-0101…0106 `Accepted` by the exit review, ADR-0106 amended by the gate (repair R1), ADR-0092, -0094, -0099 and
-0101…-0105 corrected in place; the owner's B1–B8; the owner's three test decisions of this gate (§7.2). **The production
constraint** is unchanged: production originates no BNPL purchase until unresolved #13 and #14 are answered and
`OPERATIONS_RUNBOOK.md` §8.1's activation gate is passed — an accepted limitation. **Non-blocking debt carried forward**: the
exit review's Phase 12 rows and this gate's — the 107 owed database halves, L6 held across (P) and L8, the report's
backward-clock edge, lending's unbounded empty-pin wait; the replay's shared refund derivation (Phase 14) and the schedules'
unmetered claim failures (Phase 15) confirmed; PI-001, PI-002 and PI-003 after the phases (`CURRENT_STATE.md` §Known
Architectural Debt, `POSTPONED_ISSUES.md`).

### The owner list, after this gate

De-duplicated from the exit review's thirty-eight items (`PHASE_12_REVIEW.md` § *Decisions for the owner*, numbered `#`
there), A7's triage and the four repairs' choices.

**Resolved by the gate** — recorded, no decision needed:

| Item | How |
|---|---|
| #3 Lending's RG-M1 | Closed by R3 (A7-4) — and the line payoff's statement applications (ratify, D12) |
| #4 Self-dealing failing open; the operator's dispute read | Closed — fail closed at the operator refund door (R2) and the dispute's operator acts (R1); the read refuses self-dealing, recorded `FAILED` (R1) |
| #6 A reversal re-opening a released late fee | Closed by R3 (A7-3, F8a, `bnpl V019`) — one consequence to confirm (D11) |
| #16 The bucket labels misstating a band | Closed by R3 (A7-5) — the door admits only the labels' bounds; deriving the labels stays open (N13) |
| #2, in part — the dispute lapse that cannot post | Closed by R1 (A7-1); a closed merchant and the off-boarded customer stay open (N2) |
| #5, in part — contested-dispute visibility | Closed by R1 (A7-2): event, gauge, alert at 7 days, the operator's queue; the SLA auto-resolution stays open (N3) |
| The dispute loop (A5-F2 — new, not on the exit review's list) | Closed by R1: the clock unmoved by a withdrawal, a 30-day cooldown (ratify the length, D2) |
| #12, in part — C8's latest-first arm | An independent full-walk census, a planted swap caught on bnpl's database tier (R4, A6-F7); not yet run in a storm, the telescoping arm still the upstream catch (N9) |
| #25, in part — merchant enablement reasons unscreened | Screened (R2, A5-F5, merchant `V012`); `HALF_EVEN` as a default stays to ratify (D1) |
| #33 (c) — `AccountClosing`'s pending-credits port not built | Built for BNPL disputes (R1, A7-1 (b)) |
| #19, in part — Phase 11's RG-M1 | Closed (above); the rest of Phase 11's list stays open (N16) |

**Decisions needed:**

| # | Decision | Source | State after the gate |
|---|---|---|---|
| N1 | **Criterion 7 for Phase 13** — keep skipping the fleet `databaseTest`/`kafkaTest` tiers, or run them | #1; the owner's three decisions at this gate | Answered **for this gate** — keep skipping; no post-merge re-gate or verifier; no more database checks. Open for Phase 13 |
| N2 | **A closed merchant, an off-boarded customer** — a dispute at a closed merchant (`409 bnpl.RefundNotPostable`); a refund on a `CLOSED` obligation whose customer has no wallet (alerted, not built): a refund-pending liability, an operator path, or accept | #2; R1 choice 4 | Narrowed — the lapse half closed |
| N3 | **SLA auto-resolution of contested disputes** | #5; R1 choice 1 | Narrowed — visible, alerted, queued; not auto-resolved |
| N4 | **The replay's shared refund derivation** — an independent arm now, or the battery until Phase 14 | #7 | Open — debt row, Phase 14 |
| N5 | **The schedules' claim-level failures** — meter now, or Phase 15 | #8 | Open — debt row, Phase 15 |
| N6 | **`P12-TSK-027`'s suggestions** — a name-collision guard; per-trigger SQLSTATEs | #9 | Open — R4's message assertions are the interim discrimination |
| N7 | **`P12-TSK-028`'s balance-named columns** — the permitted list, or rename | #10 | Open |
| N8 | **Capital return and income recycling** — assign a phase | #11 | Open, unowned |
| N9 | **`P12-TST-001`'s upstream catches** — demonstrate C8's telescoping arm with a committed wrong row, or accept the domain constructor and `V012`'s trigger | #12 | Narrowed (above) |
| N10 | **`Instant.EPOCH` stamps** in `JdbcBnplRecordStore` — fail loud, or accept | #13 | Open |
| N11 | **Operator-identity classification** — `CONFIDENTIAL` vs `RESTRICTED-PII` | #14 | Open |
| N12 | **The `product` metric tag's case** | #15 | Open |
| N13 | **Derive the bucket labels from the pinned bounds**, then lift the door's restriction | #16; R3 choice 1 | Narrowed — the door constrained meanwhile |
| N14 | **A16's wording, A8's default, four eyes on fee schedules** before activation | #17 | Open |
| N15 | **The settlement register's overlapping remittance shapes** — refuse at composition, or keep parking at hop 2 | #18 (`X-TSK-018`) | Open — the existing debt row |
| N16 | **Phase 11's items still open** — #4, #6–#15, #17, #18, #20 and A5-10 (`PHASE_11_TO_12_TRANSITION.md` §9) | #19 | Open — RG-M1 closed |
| N17 | **The postponed issues** — PI-001 confirmed post-phase; PI-002 (card fee pins on the JVM clock) and PI-003 (Phase 6 merchant reasons unscreened) recorded by this gate, to confirm as post-phase or schedule (PI-002 against the `X-TSK-008` debt row's "Phase 15 at the latest") | #20; R2 out of scope | PI-002, PI-003 added |
| N18 | **Lending's empty-pin wait** — a bound or an alert (lending has no session deadline to end it) | R3 choice 4 | Open — debt row |

**Accepted defaults to ratify** — each built as recorded:

| # | Default | Source |
|---|---|---|
| D1 | The exit review's #21–#38 — B8's defaults (OQ-2, 3, 4, 6, 8–20); `-001`, `-004`, `-005`, `-006` (`HALF_EVEN` a default — its unscreened reasons now screened), `-009`, `-011`, `-013`, `-014`, `-017`, `-018`, `-020`, `-021` (a), (b) — (c) now built, `-023`/`-024`, `-028`, `P12-TST-001`'s J1–J3, the payoff always `MULTI_FACTOR`, operator keys `CUSTOMER`-typed until `EMPLOYEE` actors (Phase 15) | `PHASE_12_REVIEW.md` #21–#38 |
| D2 | The withdrawal cooldown: 30 days, a constant (`BnplDisputes.WITHDRAWAL_COOLDOWN` = `V018`'s `dispute_withdrawal_cooldown()`), not a terms field; only a customer's withdrawal starts it | R1 |
| D3 | The contested-age alert at 7 days (`BnplObjectives.CONTESTED_DISPUTE`) | R1 |
| D4 | `not_postable` counts door refusals too (an opening against a closed merchant) | R1 choice 5 |
| D5 | The residual: an opening on an `ACTIVE` obligation racing a wallet close falls back to the credit balance (ADR-0106's gate amendment) | R1 |
| D6 | Existing `BNPL_REFUND` keys issued under `MERCHANT_ADMINISTER` alone stay live; the issuer rule applies at every use (revoke and re-issue is the alternative) | R2 choice 1 |
| D7 | An issuer the platform cannot place in a party is refused at both merchant doors | R2 choice 2 |
| D8 | A distinct permission (`PAYMENT_REFUND` beside `MERCHANT_ADMINISTER`) for refund-scoped keys, rather than four eyes or delivering the secret to the merchant | R2 choice 3 |
| D9 | `bnpl.SelfDealingRefused` at the merchant door tells the merchant its key's issuer is linked to the customer (an opaque code the alternative) | R2 choice 4 |
| D10 | An immediately-effective BNPL fee version (`effective_from = created_at`) is in force from its commit even when its creating instance's clock leads the database; a scheduled version waits for the database clock; the card side unchanged | R2 merge follow-up |
| D11 | A reversed repayment that also paid principal of an instalment a refund cancelled whole makes that principal due again while the instalment's fee stays released (the refund's own rule) — confirm | R3 choice 2 |
| D12 | R3 also floored the credit line payoff's statement applications (the same defect, beyond the brief) | R3 choice 3 |
| D13 | Lending `V031` brings the floor rule to lending reversals for the first time | R3 choice 5 |
| D14 | No app real-ledger proof case for A7-3 — proven over the hermetic proof world and PostgreSQL's ranks | R3 choice 6 |
| D15 | G2 resolved by whole audit labels (`repayment-reversal:propose` …), not an expectation-opener register row | R4 |
| D16 | A4-F8's backward-clock-past-midnight report edge recorded as debt, not fixed | R4 |

## 10. Phase 13 initialisation

Not started — pending the owner's allowance (owner, 2026-10-22).
