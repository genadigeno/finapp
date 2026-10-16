# Phase 11 → Phase 12 Transition

**Conducted:** 2026-10-16
**Parts:** the Phase 11 completion gate (loan lifecycle and terms · offers and acceptance · disbursement ·
schedules and amortisation · accrual and rounding · fees and waivers · allocation · partial and over-payment ·
payoff and prepayment · delinquency and restructuring · ledger and payment integration · persistence and
transaction boundaries · security and authorisation · audit and observability · testing and documentation) ·
the nine financial-correctness checks · the distributed-system audit · findings and repair · Phase 11
completion · Phase 12 initialisation (§10)
**Constraint:** the owner's — *"Do not declare Phase 11 complete simply because its tasks were marked complete
previously."* The exit review (`P11-DOC-001`, [`PHASE_11_REVIEW.md`](PHASE_11_REVIEW.md)) had recorded Phase 11
`COMPLETE` a few hours before this gate opened; the gate trusted none of it. CRITICAL and IMPORTANT Phase 11
defects are resolved before the transition, never deferred as debt. Like the transitions before it, this one
wrote application code — the repairs in §8, each tested, each broken on purpose to prove its test, each restored
byte-identical (sha256-verified) and recorded in `MUTATION_TESTING.md` §2. It wrote no Phase 12 code — Phase 12
exists below only as documents.

**Method.** Six independent, adversarial, read-only audits over `phase-11-to-12-transition` at `c8b20071` (master,
Phase 11 recorded `COMPLETE` by `P11-DOC-001`), each under one shared brief (severity scale, evidence = file, line,
SQL or Java and a concrete failure scenario, no style findings, nothing the code already handles): **A1** financial
correctness; **A2** distributed systems (the ten-instance question); **A3** origination to accrual; **A4** servicing to
restructuring; **A5** security, audit, privacy and observability; **A6** tests, architecture and documentation. In
parallel the coordinator ran a **fresh baseline** of the tiers on `c8b20071`. Findings were deduplicated and
classified by the coordinator; the IMPORTANT ones were repaired on two branches — **repair-1** `732e46c4`
(`p11-gate-repair-1`, lending `V029`, merged at `2ecf971d`) and **repair-2** `36f1b201` (`p11-gate-repair-2`, lending
`V030`, the repair-1 tree merged in at `46509540`) — every repair with a test and a probe. An **independent
re-gate verifier** then read every finding against the repaired tree and the coordinator **re-ran the tiers fresh** on
`36f1b201`.

---

## Verdict

| Part | Outcome |
|---|---|
| The fifteen capability areas | **Ten `PASS` as found; five not** — accrual and rounding, fees and waivers and allocation `FAIL` as found (a figure could be wrong: R1, R3, R2), loan lifecycle and disbursement `PARTIAL` (a loan could be stranded: R5), audit `PARTIAL` (R4), testing `PARTIAL` (R6, twenty red database cases on master); partial and over-payment, payoff and prepayment, delinquency `PARTIAL` by R2's and R1's reach — **every one `PASS` after repair** (§1) |
| The nine financial-correctness checks | **Six `PASS` as found, three not** (interest exact under a clock step, allocation complete, fees bounded) — **all nine `PASS` after repair** (§2) |
| *"Would Phase 11 remain correct if 10 service instances executed the relevant operations concurrently?"* | **`PASS`** — A2 found no CRITICAL or IMPORTANT concurrency defect; the one clock defect three auditors found independently (R1) is repaired; the lock order written down in full (§3) |
| Persistence, transaction and consistency boundaries | **`PASS`** — every workflow's owner, transaction, arbiter and key identified; two decision stamps moved after their locks (A2-F3) (§4) |
| Blocking issues | **No CRITICAL. Six IMPORTANT (R1–R6, deduplicated from nine reports), every one repaired, tested, probed and `CLOSED` by the re-gate verifier** (§5) |
| MINOR findings | Thirteen fixed in the repair passes; the rest recorded as debt rows or owner items, each with an owner (§6) |
| Testing | Baseline **20 red** lending database cases (R6); after repair the whole lending database tier green twice in a row, every fresh count 0 failures; criterion 7 `PARTIAL`, the standing deviation (§7) |
| Phase 11 completion | **`COMPLETE`, confirmed after repair** (§9) |
| Phase 12 initialisation | **`READY`** — see §10 |

---

## 1. The Phase 11 completion audit — criterion by criterion

Graded against the code, the migrations and the counted tests, not against the exit review's word. *As found* is
`c8b20071`; *after repair* is `36f1b201`.

| # | Area (the owner's brief) | As found | After repair | Evidence |
|---|---|---|---|---|
| 1 | Loan lifecycle and terms | `PARTIAL` | `PASS` | Every machine's three ranks (domain, every-writer trigger, status vocabulary) held; terms versions four-eyes, never migration-activated. **R5**: a terms version the door admitted could still produce a schedule the engine refused at disbursement or at the payout's conclusion — path W stuck `PENDING_DISBURSEMENT` holding committed capital, path X `ACTIVE`, paid out, never accruing (no terminal edge). Repaired by narrowing the door (§5). A3-M1: engine versions `CHECK >= 1` admitted a version no code holds — now `= 1`. |
| 2 | Offers and acceptance | `PASS` | `PASS` | Offer = the approved amount and term; expiry complementary on the database clock; `MULTI_FACTOR` and the echoed `terms_sha256`; evidence complete; consumption under L0. A2-F3 (MINOR): the terms version's `decided_at`/`effective_to` and the amendment decision stamped `transaction_timestamp()` before their locks, so `effective_from` could precede offers made under the predecessor and `GET terms?at=` answer wrongly — moved to `clock_timestamp()` after the lock (lending `V030`). |
| 3 | Disbursement | `PARTIAL` | `PASS` | One disbursement and one entry per loan on both paths; payout dispatched once, ambiguity held by payments; **R5**'s stranded paths (above). |
| 4 | Schedules and amortisation | `PASS` | `PASS` | Conservation and zero leakage over the battery's ≥ 10,000 loans; R5 found an admitted bounds set (EUR 500.19 over 60 months at 23.10 %) the engine could not schedule — the door now judges three bounds at the minimum principal with a half-unit margin (the maximum personal-loan rate admitted at 60 months 23.1064 % → 23.0908 %). |
| 5 | Accrual and rounding | `FAIL` | `PASS` | Born once per (loan, date), rounded once per period. **R1**: after a backward database-clock step across midnight a principal-moving fact (repayment, prepayment, draw, collection, payoff, a reversal re-instating principal) could be value-dated a business date already accrued — the day's interest over-charged (repayment) or under-charged (draw), and the replay proof accepted it. |
| 6 | Fees and waivers | `FAIL` | `PASS` | Late fee once per item, capped; waivers four-eyes. **R3**: a fee paid, refunded (rule 8a) and then re-opened by the repayment's reversal could be waived again — `LOAN_FEE_INCOME` −15 for the loan while the borrower still held the 15 refunded; the subledger proof did not check income. |
| 7 | Allocation | `FAIL` | `PASS` | Oldest-due first, fees → interest → principal, a pinned version. **R2**: held credit was applied only at a new billing (rule 6 keyed per billed item), so after a refund or reversal, or after maturity, past-due items stayed past due beside the credit that covered them — DPD, late fees and default accrued, and a matured loan could never close without new money; ADR-0095 claimed otherwise. |
| 8 | Partial and over-payment | `PARTIAL` | `PASS` | Partial and over-payment allocate exactly; the overpayment's held credit is R2's. |
| 9 | Payoff and prepayment | `PARTIAL` | `PASS` | Quote immutable, execution born once, stale quote refused. A4-M1 (same root as R2): a payoff beside held credit debited the wallet the gross receivable and refunded the credit beside it — now applies the credit first and debits only the net. R1 reached the prepayment's value date. Residual RG-M1 (§6). |
| 10 | Delinquency and restructuring | `PARTIAL` | `PASS` | DPD derived, append-only, default at 90; amendments four-eyes with acceptance. R2's consequence (DPD and late fees on covered arrears). A4-M2…M4 MINOR, recorded (§6). |
| 11 | Ledger and payment integration | `PASS` | `PASS` | Every figure a ledger balance; posting rules balanced; payments' withdrawal for path X; the capital proof. A1-F4 (MINOR): `capital_contribution` and its event table lacked the `BEFORE TRUNCATE` refusal every other lending table has — added (lending `V029`). |
| 12 | Persistence and transaction boundaries | `PASS` | `PASS` | One transaction per act, outbox in it, born-once uniques probed alone by `P11-DOC-001`; §4. |
| 13 | Security and authorisation | `PASS` | `PASS` | Route permissions with negatives, owner scoping, self-dealing refused, MFA on credit-creating acts. MINOR: A5-02 the operator loan read admits an employee's own party (= exit review owner item #18), A5-04's documentation said `EMPLOYEE` actors where the code produces `CUSTOMER` (corrected), A5-05, A5-07, A5-08 (§6). |
| 14 | Audit and observability | `PARTIAL` | `PASS` | **R4**: a self-approval refused at any of the five lending four-eyes doors (repayment reversal, waiver, amendment, terms, capital) left only a meter and a `403`, no audit record — the credit and merchant precedents audit it `FAILED`. A5-03: an origination or offer-expiry sweep failure was neither metered nor alerted and the runbook's claim was untrue — a failed-step counter, an oldest-awaiting-decision gauge, two alerts and runbook §7.5/§7.11/§7.12 added. |
| 15 | Testing and documentation | `PARTIAL` | `PASS` | **R6**: the baseline's `:lending:databaseTest` ran 289 across 25 suites with **20 red** — a suite left a `PROPOSED` line-terms version pending and every later suite proposing line terms failed in fixture setup; the exit review's "289/0" was order-lucky. Documentation: A6-01 (`CURRENT_STATE.md` §Active Work stale since Phase 8), A6-05 (the gate's `RETURNED` payout, a mis-named test in the catalogue, the testing clause vs the survived re-dispatch probe), A1-F3 (the plan's rules 3/4 key texts reversed by a "correction"), A5-09 (plan §9 "step-up" where the code requires `MULTI_FACTOR`), A2-F2/A6-02 (the lock order's shared income rows unwritten) — all corrected. Criterion 7 `PARTIAL` (§7). |

**The Phase 12 boundary holds:** no BNPL code, no merchant-financed agreement, `LoanProduct` and
`ConsumerKind` still refuse BNPL (`LoanProductTest`, `CreditDecisionConsumptionDatabaseTest`), and
`lending/package-info.java` still states that lending never knows a merchant.

## 2. The nine financial-correctness checks

| # | Check | As found | After repair | How it is held |
|---|---|---|---|---|
| 1 | No floating point; currency explicit on every amount | `PASS` | `PASS` | `NoFloatingPointMoneyRulesTest` over `lending`; every `*_minor` with its `currency`; `LendingSchemaHasNoMutableMoneyTest` |
| 2 | Every journal entry balances per currency | `PASS` | `PASS` | `PostingService`'s balance rule; trial balance zero in every storm round |
| 3 | No balance outside the ledger; every figure explainable | `PASS` | `PASS` | Every figure a per-loan account balance; `LoanSubledgerProof` and `LoanReplayProof` at rest in every storm round |
| 4 | A schedule conserves principal with zero rounding leakage | `PASS` | `PASS` | The battery's leakage census; R5 removes the admitted-but-unschedulable terms |
| 5 | Interest accrued once per date, on the right base, rounded once per period | `FAIL` | `PASS` | R1: `lending.principal_value_floor(loan)` (the day after the latest accrual; for a line also the latest statement's cycle end) floors every principal fact in Java (`LoanStore#principalValueDate`, read under L3) and in the repayment and draw ranks (`V030`); the servicing step writes nothing while the clock is behind its record; the replay compares each accrual base with the final ledger lines valued on or before its date (`DIVERGED(ACCRUAL)`) |
| 6 | Every repayment and held credit allocated completely, by the pinned order | `FAIL` | `PASS` | R2: the catch-up application (`credit_balance_application` with `billing_id` NULL and `catch_up_sequence`, key `lending.credit-application:<loan>:catch-up:<n>`) at the start of every servicing step and in the payoff before its recomputation; ADR-0095 point 5 corrected |
| 7 | Fees and waivers bounded; income never negative | `FAIL` | `PASS` | R3: a `FEE` waiver bounded by `least(billed − paid, assessed − fee waived − refunded)` at the domain (`Waivers.room`) and re-judged by `V029`'s `waiver_decision_is_born_once`; the subledger proof's `FEE_INCOME_NEGATIVE` arm |
| 8 | Corrections by reversal and compensation only; history immutable | `PASS` | `PASS` | Four-eyes reversals and waivers through `ReversalService`; `INSERT`-only facts; truncate refused on every lending table since A1-F4 |
| 9 | Capital never over-deployed; exposure without a gap | `PASS` | `PASS` | L6 headroom at every deploying commit; L0 first in every exposure-raising transaction; `ExposureCensus` and `LendingCapitalProof` in every storm round |

## 3. The distributed-system audit — the ten-instance question

A2 read every lending writer, schedule and proof for in-memory authority, single-process assumptions, clock use,
lock order, transaction scope and connection-across-a-call. **No `synchronized`, JVM lock, in-memory authoritative
state or process-local idempotency; no connection held across a provider call; every window judged on the database
clock after the relevant lock; every schedule leaderless (`SKIP LOCKED` claims, database-stamped permits); every
keyed door's claim and born-once arbiter in PostgreSQL.** A2 graded nothing CRITICAL or IMPORTANT.

- **R1 is a clock defect, not a lock defect** — found independently by A1 (F1), A3 (I1) and A2 (F4, graded MINOR "low
  likelihood"); the coordinator graded it IMPORTANT because it mis-charges interest and three audits found it. It
  needs only a backward step of the database clock across midnight between an accrual and a principal fact — the
  Docker Desktop VM this project's suites run on steps its clock (`P11-TSK-036`).
- **A2-F2 / A6-02** — the lock order rested on an unwritten convention for the currency's two shared income rows
  (`LOAN_INTEREST_INCOME` → L7 → `LOAN_FEE_INCOME`), and `DISTRIBUTED_EXECUTION.md` said only one was shared: written
  into §3's Phase 11 lock-order row by repair-2. No lock-order guard exists — held by review and the counted races, as
  before (recorded).
- **A2-F3** — two decision stamps taken before their locks (§1 area 2), repaired.
- **A2-F1** — the currency-wide income rows serialise all lending servicing and are held while a step waits on a wallet
  row: correct, but a throughput ceiling that becomes important at BNPL volume. Recorded as debt with the capacity
  limit, carried into Phase 12's design (§10, `PHASE_12_PLAN.md` §7.2 and §21).

**Answer: `PASS`** — on the counted tests of every contention row (`P11-DOC-001`'s evidence, re-run here), the
storm on two instances with clocks ±5 s green on the repaired tree (`LendingStormDatabaseTest`, 1,677 s), and R1's
deterministic reproductions (`BackwardClockDatabaseTest`, the `ClockStep` shadow of the clock functions).

## 4. Atomicity, consistency and idempotency

| Workflow | Owner | Transaction | Concurrency control | Idempotency | Gate change |
|---|---|---|---|---|---|
| Application, offer | `loan_application`, `loan_offer` | one each | L1, L2; one open application per (party, product) | keyed door; `UNIQUE (decision_request_id)`, `UNIQUE (application_id)` | — |
| Acceptance | `loan`, agreement, evidence | one | L0 → L2 → consume → L6 | keyed door; consumption uniques | — |
| Disbursement / payout | `loan_disbursement`, `loan_payout` | one per attempt | L0 → L3 → L5; payments' key | `lending.disbursement:<loan>`; `payments.withdrawal:lending:<loan>` | R5: no unschedulable terms reach it |
| Servicing step | the loan's rows | one per (loan, date) | L3 → (`LOAN_INTEREST_INCOME`) → L7 → (`LOAN_FEE_INCOME`) | born-once per date / item; ledger keys | R1 gate at the top; R2 catch-up after billing |
| Repayment, prepayment, draw | repayment, draw | one | L3 → (L6 for a draw) → L7 → L8 | keyed door; `UNIQUE (loan_id, kind, request_key)` | R1 value-date floor |
| Reversal, waiver | proposal, decision | one per act | L0 (reversal) → L3 → L5 → L6? → L7 | one live proposal; four-eyes `CHECK` | R3 bound; R4 refusal audited |
| Payoff | quote, execution | one | L3 → … | `UNIQUE (quote_id)` | A4-M1 credit first; R1 floor |
| Terms, amendment, capital administration | version / decision rows | one | advisory namespace `11` first, then rows | keyed; four-eyes | A2-F3 stamps after the lock; R4 |

## 5. Findings — CRITICAL and IMPORTANT

**CRITICAL: none.** Every IMPORTANT finding below was repaired before this transition, tested, broken on purpose
(the probe) and read again by the independent re-gate verifier, who graded every one **`CLOSED`**.

| # | Finding (sources) | Root cause | Repair | Test | Probe | Verifier |
|---|---|---|---|---|---|---|
| **R1** | A principal-moving fact value-dated a business date already accrued after a backward clock step across midnight — interest over- or under-charged, the replay blind to it (A1-F1 = A2-F4 = A3-I1) | Value-date ranks `[business_date_earliest, latest]` had no floor at the latest accrual; Java dated by its own clock read; the accrual base for *d* is the principal lines valued ≤ *d* at accrual time, and the replay compared it with lines written before | repair-2, lending **`V030`**: `lending.principal_value_floor(loan)`; `LoanStore#principalValueDate` = `GREATEST(today, floor)` under L3 for every mover (repayment of every kind, prepayment, draw, reversal, the payoff's held-credit application); the repayment and draw DB ranks floored; `LoanServicing.service` writes nothing while today < floor; the replay's `DIVERGED(ACCRUAL)` arm | `BackwardClockDatabaseTest` (`#aRepaymentSteppedBackBehindAnAccruedDateIsValuedAfterIt`, `#aDrawSteppedBack…`, `#aReversalSteppedBack…`, `#aPrepaymentSteppedBack…`, `#aServicingStepBehindItsRecordWritesNothing`, `#theDatabaseFloorsAPrincipalValueDate`), `LendingProofDatabaseTest#aPrincipalLineValuedIntoAnAccruedDateDiverges` | five, caught: Java floor answering the clock's date; the repayment floor off; the draw floor off; the replay arm skipped; the servicing gate skipped | `CLOSED` |
| **R2** | Held credit never applied to already-past-due items except at a new billing; a matured loan could not close without new money (A1-F2 = A4-F2; A4-M1) | Rule 6 keyed per billed item (`UNIQUE (billing_id)`) | repair-1, lending **`V029`**: the catch-up application (`billing_id` NULL, `catch_up_sequence`, its own ledger key) at the start of every servicing step (order: bill → catch-up → collect → close → condition and fee) and in the payoff before its recomputation; ADR-0095 corrected | `FeeAndWaiverDatabaseTest#aRefundHeldBesidePastDueArrearsIsApplied`, `#aMaturedLoansArrearsAreSettledByCreditHeld`, `PayoffDatabaseTest#aPayoffBesideCreditHeldDebitsOnlyTheNet` | three, caught: the catch-up step disabled; the payoff's application removed; the trigger's numbering check neutralised | `CLOSED` |
| **R3** | A refunded fee could be forgiven again after the repayment that paid it was reversed — fee income negative (A4-F1) | The waiver's bound (billed − paid) did not subtract refunds | repair-1, `V029`: `FEE` bound `least(billed − paid, assessed − fee waived − refunded)` in `Waivers.room` and in `waiver_decision_is_born_once`; the proof's `FEE_INCOME_NEGATIVE` arm | `FeeAndWaiverDatabaseTest#aRefundedFeeIsNeverForgivenTwice` | three, caught: the domain bound, the trigger bound, the proof arm | `CLOSED` |
| **R4** | Self-approval refusals at the five lending four-eyes doors left no audit record (A5-01) | Refused with a `403` and a meter only, against the credit and merchant precedents | repair-2: `lending.SelfApprovalRefused` recorded `FAILED` in its own transaction after the rollback (`SelfApprovalRefusals.record`), at all five doors; `AUDITABLE_ACTIONS.md` | `LoanTermsEndpointDatabaseTest#twoOfficersActivate`, `LoanAccountDatabaseTest` (reversal, waiver, amendment end to end), `LendingCapitalDatabaseTest#selfApprovalRefusedAtBothRanks` | one, caught: the recorder returning before its append | `CLOSED` |
| **R5** | A schedule refused at disbursement or the payout's conclusion had no terminal path — stuck holding capital, or paid out and never accruing (upgraded from A3-M3) | The terms door judged schedulability without margin; an admitted product could yield an unschedulable loan | repair-1: the door judges three bounds at the minimum principal with a half-unit margin (the maximum admitted rate narrowed); the old gap found and pinned | `ScheduleEngineV1Test#theOldDoorsGap`, `#theLaterPeriodsBound`, `#theExhaustionBound` | two, caught: the margin removed; the exhaustion bound neutralised | `CLOSED` (residual RG-M4) |
| **R6** | Twenty lending database cases red on master, suite-order dependent (baseline) | `LoanTermsDatabaseTest#tenProposersOneProposal` left a `PROPOSED` line-terms version; later suites' fixtures failed `ProposalPending` | repair-1, test-only: `DisbursementWorld.clearPendingTerms`, `activate` clearing its product's pending proposal first, `@AfterEach` in `LoanTermsDatabaseTest` and `LoanOriginationDatabaseTest` | the whole `:lending:databaseTest` tier green twice in a row on one commit | the break is the code before the fix (20 red on `c8b20071`); no assertion weakened | `CLOSED` |

Nineteen probes in all (ten in repair-1, nine in repair-2), every one caught on its first run, every restore
byte-identical, each a row in `MUTATION_TESTING.md` §2 (R6, a fixture defect rather than an invariant, has its note
there instead).

## 6. MINOR findings and where each went

| Finding | Disposition |
|---|---|
| A1-F3 plan §12.8 rules 3/4 key texts reversed; A1-F4 capital truncate triggers; A3-M1 engine `CHECK = 1`; A2-F2 + A6-02 lock-order shared rows; A2-F3 stamps after the lock; A5-03 origination failure meter, gauge, alerts, runbook; A5-04 `EMPLOYEE`→`CUSTOMER` in the docs; A5-06 stray `finapp-platform.json.new` deleted; A5-09 plan §9 MFA; A6-01 §Active Work; A6-05 gate `RETURNED`, mis-named catalogue test, testing clause; A4-M1 payoff gross debit | **Fixed** in repair-1 or repair-2 |
| RG-M6 `CURRENT_STATE.md` said `COMPLETE` while the gate was in repair | **Resolved by this transition** (the status is now confirmed by it) |
| A2-F1 the currency-wide income rows serialise servicing | **Debt row** (existing, from repair-2), carried into `PHASE_12_PLAN.md` §7.2/§21 — Phase 12 design note, Phase 16 owner |
| RG-M1 the payoff's own billing applications dated `today`, not floored (`Payoffs`) — a clock-step window can over-charge a day's interest; the replay reports it `DIVERGED` | **Owner item** — the one residual that can mis-date a figure (detected, not prevented); `EXECUTION_PROTOCOL.md` forbids accepting financial-correctness debt, so it is recorded as a remediation to schedule (an `X-TSK` in R1's shape), recommended before `P12-TSK-022` (BNPL payoff, which reuses the pattern) |
| RG-M2 a prepayment refused `P0001` (a `500`) when the floor is a due date; its test skips by `assumeTrue` | **Debt row** — fails closed, no money moves |
| RG-M3 `LoanStore`'s javadoc claims a DB rank for reversals that exists only in Java | **Debt row** (documentation, with RG-M2's remediation) |
| RG-M4 R5's guarantee holds for the build's product bounds; an `ACTIVE` terms version is not re-judged when credit's bounds widen | **Debt row** — the terms door re-judges at the next proposal; owner: the next change to credit's bounds |
| RG-M5 the replay has no "catch-up application expected" check | **Debt row** — Phase 14 (reporting) or the next proof change |
| A3-M2 a fixed origination fee not judged against the minimum principal (= exit review owner item #10) | **Owner item** (#10, unchanged) |
| A3-M4 agreement columns not bound to the canonical text at the database | **Debt row** — replay compares columns to the re-rendered text |
| A4-M2 `PAID_OFF_EARLY` after maturity; A4-M3 the payoff's agreement version not re-checked at the DB rank; A4-M4 late-fee stacking after maturity / a caught-up fee on a reversal-reopened item | **Debt row** (servicing residues) |
| A5-02 operator loan read of one's own party (= owner item #18) | **Owner item** (#18, unchanged) |
| A5-05 `LendingFlowScope` links a span into another party's correlation before ownership is judged; A5-07 `LoanApplicationSubmitted` carries `partyId`; A5-08 more unredacted money records (`Money.toString`, `LoanAmendments.Accepted/Plan`, `DueItem`, `JdbcRepaymentStore.Head`) | **Debt row** (privacy residues) — Phase 15's privacy pass; A5-07 an owner decision inside it |
| A5-10 customer non-posting acts (closure request, withdrawal, decline) carry no audit record or identity | **Owner item** |
| A6-03 the storm's closure censuses tolerate 5 s, unprobed; A6-04 app lending suites not date-shifted; A6-06 the storm's default/bucket coverage date-dependent; A6-07 scenario 9's relay kill before receive | **Debt row** (test hardening) — before `P12-TST-001` reuses the storm's shape |
| A6-08 lending triggers' unqualified `statement_timestamp()` resolve through `search_path` | **Debt row** — schema-qualify in one migration (touches every function); Phase 15 |

## 7. Testing — baseline, fresh counts, deviations

**Baseline** (coordinator, `c8b20071`, before any repair): fleet hermetic **3,007** tests across 497 suites, 0
failures; fleet architecture **216** across 52, 0; `:lending:databaseTest` **289** across 25 — **20 failures** (R6:
`CreditLineRepayment` 6, `CreditLineStatement` 11, `Delinquency` 2, `FeeAndWaiver` 1, all fixture setup).

**After repair** — fresh, by the coordinator, on `36f1b201`:

| Tier | Tests | Suites | Failures |
|---|---|---|---|
| Fleet hermetic (`test`) | 3,009 | 497 | 0 |
| Fleet architecture (`architectureTest`) | 216 | 52 | 0 |
| Whole `:lending:databaseTest` (date-shifted zone) | 305 | 25 | 0 |
| `app` lending own-container | 60 | 6 | 0 — `LendingStormDatabaseTest` green, 1,677 s |

The repair branches also ran their own tiers (repair-1: the lending database tier 296 across 25 twice green; repair-2:
305 across 25, `app` 875 across 174) — recorded in their commits, superseded by the fresh counts above.

**Deviations.** Not run, on the owner's instruction: `gradlew build`, the fleet-wide `databaseTest` and `kafkaTest`
tiers. **Universal criterion 7 is `PARTIAL`** — a standing, recorded deviation, not a pass. **Owner decision
(2026-10-16, B4): keep skipping**, re-affirmed through Phase 12 (per task: touched modules plus own and adjacent
database suites; fleet hermetic and architecture once per merged wave; asked again at the Phase 12 → 13 transition).

## 8. Repairs

- **repair-1** `732e46c4` (merged `2ecf971d`, lending **`V029`** — replaces `credit_balance_application_is_born_once`
  (from V017) and `waiver_decision_is_born_once` (from V025), adds `capital_contribution_is_never_truncated`, alters
  `credit_balance_application` and `loan_terms`): R2, R3, R5, R6, A1-F4, A3-M1, A4-M1. Ten probes.
- **repair-2** `36f1b201` (lending **`V030`** — replaces `repayment_is_born_once`, `credit_line_draw_is_born_once` (from
  V028), `loan_terms_version_permits_only_machine_edges` (from V003), `loan_amendment_decision_is_born_once` (from V023),
  adds `lending.principal_value_floor(uuid)`): R1, R4, A2-F3, A5-03 and the documentation corrections (lock order,
  `PHASE_GATES.md` `RETURNED`, the catalogue's test name, `EMPLOYEE` → `CUSTOMER`, §9 MFA, the stray file, §Active
  Work, the income-row debt row). Nine probes. At the merge (`46509540`) the payoff's held-credit application
  (repair-1's A4-M1) was value-dated by repair-2's floor; the servicing step's catch-up runs after repair-2's gate and
  needs nothing.
- **Documents the repairs updated**: ADR-0063, ADR-0093, ADR-0095, ADR-0099 (follow-ups dated 2026-10-16),
  `AUDITABLE_ACTIONS.md`, `DATA_CLASSIFICATION.md`, `DISTRIBUTED_EXECUTION.md`, `OPERATIONS_RUNBOOK.md`,
  `FINANCIAL_INVARIANTS.md`, `GLOSSARY.md`, `LENDING_LIFECYCLES.md`, `MUTATION_TESTING.md`, `PHASE_11_PLAN.md`,
  `PHASE_GATES.md`. This transition added the follow-ups ADR-0092 (R5, A2-F3, A3-M1), ADR-0094 (R1) and ADR-0096
  (A1-F4, A2-F1's capacity limit) lacked.

## 9. Phase 11 completion

**Phase 11 — Lending Infrastructure — `COMPLETE`, confirmed after repair (2026-10-16).** Delivered: the `lending`
module — two products (the amortising personal loan and the revolving credit line) on one account model, every figure
a per-loan ledger balance; four pure versioned engines (schedule, accrual, allocation, statement); origination from a
keyed, MFA-assured application through credit's decision to an immutable agreement with its evidence, credit's
consumption under the party's profile lock and exposure counting committed and outstanding principal and an open
line's limit; lending capital from bank evidence judged under its own lock; disbursement to the wallet and on through
payments' withdrawal; servicing — accrual born once per date, billing from actual accrual, statements,
auto-collection, delinquency and default derived, late fees; repayment by a versioned allocation order and its
four-eyes reversal; fee and interest waivers; payoff; restructuring and partial prepayment; the subledger and replay
proofs; reports, series and alerts; the storm and the battery. **What this gate added**: every principal fact floored
at the record's first open date (R1); held credit settling what is already due (R2); a refunded fee never forgiven
twice (R3); self-approval refusals on the record (R4); no admitted terms version that cannot be scheduled (R5); a
lending database tier that is green in any suite order (R6). **The production constraint** is unchanged: production
originates nothing until unresolved #13 and #14 are answered — an accepted limitation. **Non-blocking debt carried
forward**: §6's debt rows and owner items, and the exit review's (`CURRENT_STATE.md` §Known Architectural Debt).

**The exit review's twenty-one owner items, after this gate** (`PHASE_11_REVIEW.md` §Decisions for the owner):

| # | Item | After the gate |
|---|---|---|
| 1 | Criterion 7 | **Answered** — B4: keep skipping through Phase 12 |
| 2 | The three red settlement suites | **Answered** — B8 accepts `PHASE_12_PLAN.md` OQ-20's default: fix them first (an `X-TSK`) before `P12-TST-001`, whose scenario 5 runs through merchant payouts and settlement |
| 3 | The storm's accepted deviations (I2, I4, M3, C19, I6, C12) | **Carried forward**: Phase 12's gate requires every census at every round and every named lock-wait counted (`PHASE_GATES.md` §5 Phase 12) — the lesson applied; Phase 11's own stay accepted as recorded |
| 4 | Capital return and income recycling | **Open** — still unowned; BNPL fee income is not capital (`PHASE_12_PLAN.md` §12.9) |
| 5 | Refinance | **Decided by this transition**: not in Phase 12 (`PHASE_12_PLAN.md` §20); the debt row stays with the owner |
| 6 | A repudiated statement reversing capital outside L6 | Open — unchanged |
| 7 | A line's credit balance held, not applied to drawn principal | Open — unchanged |
| 8 | Credit's expiry judged on one statement's clock | Open — unchanged |
| 9 | Credit's customer door opening requests for lending's products | Open — unchanged; Phase 12 adds two BNPL products to the same door (`P12-TSK-001` keeps BNPL requests opened only by the BNPL application) |
| 10 | The amount origination fee vs the minimum principal | Open — confirmed by the gate (A3-M2) |
| 11 | No minimum first-cycle length for a line | Open — unchanged |
| 12 | `-029`'s write-path change (`clock_timestamp()` after the locks) | Open for ratification — the gate applied the same rule to two more stamps (A2-F3) |
| 13 | `-030`'s metric choices | Open — unchanged |
| 14 | The payout's bare-clock stamps | Open — unchanged |
| 15 | Per-task judgements, as a set | Open — unchanged |
| 16 | "Never re-dispatched by lending" vs a lending-side dispatch count | Open for lending; **applied forward** — Phase 12's probes count dispatches on both sides (`PHASE_12_PLAN.md` §13.4) |
| 17 | The subledger proof throwing on a planted non-UUIDv7 id | Open — unchanged |
| 18 | The operator loan read of one's own party | Open — confirmed by the gate (A5-02) |
| 19 | Person-written lending reasons classified `CONFIDENTIAL` | Open for lending; **resolved forward** — BNPL's are `RESTRICTED-PII` (`PHASE_12_PLAN.md` §11) |
| 20 | No schedule-versus-actual drift report | Open — Phase 14 |
| 21 | The servicing sweep's volume, the DPD cost | Open — joined by A2-F1; Phase 12's storm measures the shared rows (`PHASE_12_PLAN.md` §21) |
| — | **Added by this gate**: RG-M1 (the payoff's unfloored billing applications — recommended remediation before `P12-TSK-022`); A5-10 (customer non-posting acts unaudited) | Open |
