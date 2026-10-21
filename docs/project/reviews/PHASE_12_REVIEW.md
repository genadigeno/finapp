# Phase 12 Exit Review — Buy Now, Pay Later (BNPL)

**Conducted:** 2026-10-22 (`P12-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit criteria and the financial
supplement), §5 (Phase 12-specific criteria — the five originals made measurable and the per-area blocks, read from the gate
at review time, as extended by the Phase 11 → 12 transition)
**Phase objective under review:** `bnpl` owns the BNPL agreement, the purchase, the merchant financing record, the customer's
repayment obligation and its servicing facts, and none of the money — every principal, interest, fee and credit-balance
figure a balance of a per-obligation ledger account; two products (pay-in-4, pay-in-N) on lending's engines extracted
unchanged into `creditmath`; one credit decision per purchase taken up under the party's profile lock; the purchase, the
merchant financing, the obligation, the consumption and the capital commitment in one transaction, funded from lending
capital under the shared headroom; instalments from the wallet only; refunds latest instalments first, the excess to the
customer, the merchant fee pro-rated back; disputes under the purchase's one refund bound; the three-way proof.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, every one only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **11 `PASS`, criterion 7 `PARTIAL`** — the owner's standing deviation (B4: the fleet-wide database and kafka tiers, and previous phases' database suites, not run), recorded, not passed; the owner-authorised Phase 12 database pass and this review's own database runs stated below; criterion 10 **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** — F4 with write-off deferred to Phase 14; F8 with the safeguarding adjustment widened by BNPL principal deferred to Phase 14/15 (a debt row) |
| Phase 12-specific criteria | **the five originals and the eighteen per-area blocks `PASS`**, read clause by clause; clauses met in a form their words did not anticipate recorded under the gate (`PHASE_GATES.md`, the note after Phase 12's criteria); criterion 7's block the standing deviation; the production reality recorded as an accepted limitation |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every §7.4 row (twenty-one) raised by its named test, three of them strengthened here (row 1's one-key race, row 7's credit-line draw, row 8's payout against a refund); every one of the schema's sixty-eight uniques probed with every trigger off (sixty-one refused alone, seven foreign-key targets); the coded lock order read for every transaction, hidden ledger locks included — acyclic; (P) raced against a real wallet-paid capture and a real payments refund in both `UUID` orders; the storm green on the final code with zero deadlocks |
| **Verdict** | **Phase 12 `COMPLETE` (2026-10-22), pending the Phase 12 → 13 transition gate** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → re-run the evidence → **flip the status, which is the
guarded act** → finalise with counted numbers.

**The assessment was ten read-only audits, and it found what no task's gate had.** Two read the gate's per-area blocks clause
by clause against their named tests; one the multi-instance design (every transaction's acquisition order in code, the §7.4
table, every task's ten-instance answer); one security, audit and observability; one the invariants and the mutation register
and the flip's mechanics; one ADR-0101…0106 against the code; two every named document; one the owner decisions, the debt rows
and the Phase 13 boundary; and one, in the Phase 11 → 12 transition's shape, hunted financial defects of R1–R6's classes in
`bnpl`. Each finding was proven from the code with a file and a line.

**No CRITICAL defect and no production defect was found.** The classes that broke Phase 11 after its exit review were each
looked for and refuted in `bnpl`: every principal-moving writer is floored at the obligation's floor in Java and at the
database rank (R1, RG-M1); held credit is applied to past-due items by the catch-up in every servicing step and the payoff
(R2); a waiver is bounded by `least(billed − paid, assessed − waived − released)` in Java and the trigger, and the subledger
proof's fee-income arm stands behind it (R3); every four-eyes refusal is audited `FAILED` (R4); a pay-in-N re-amortisation
cannot become unschedulable (R5); and the terms suite clears its pending proposals (R6). What the audits found was evidence:
**gate clauses with no test or a weaker one** — the plan's own §13.4 probe "skip the purchase lock in a refund" never
performed live; Σ allocation = amount never proven by a raw writer; no raw edge out of any terminal state tested on a real
row; "a session that expires" proven by abandonment; an acceptance-against-expiry test that admitted `OFFERED`; the
acceptance evidence and "no consent purpose" unasserted; a payout racing a BNPL refund, a merchant suspended with open
purchases, a credit-line draw among the last-capital racers, (P) against a real refund — none tested; scenario 10's and 11's
"both orders" asserted nowhere; the storm with no DPD census; twelve of fourteen keyed doors never raced ten under one key;
the dispute's and the waiver's self-approval and three doors' self-dealing never proven through the API; a crash after a
collection, a payoff and a dispute resolution never re-driven by another instance; the schema's born-once uniques mostly never
probed alone — **and drift in all six ADRs and every Phase 12 document**, the most consequential being `DISTRIBUTED_EXECUTION.md`
§3's rows still opening *Planned*, a debt row the plan and the runbook both claimed attached and was not, the glossary out
of step with `DOMAIN_MODEL.md` since `P12-TSK-006` (which would have turned `DomainGlossaryTest` red), and an invariant
verify text naming a probe that was never performed. None was waived. Each was corrected here, in a test or in text, or
recorded with an owner, and every new test was broken on purpose (§ *Probes*).

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **`PHASE_12_PLAN.md` §13.4's "skip the purchase lock in a refund → scenario 10 red" was never performed live** — the only live demonstration planned for the third original; `-021` and `-023` recorded it owed, the storm's nine probes did not include it, and `INV-BNPL-07`/`-08`'s verify texts claimed it was in §2 | Performed: B5 skipped in Java, then also `bnpl V012`'s trigger re-take — **caught both times by `BnplRefunds.derive`'s position check under B4** (the refunded-before figure read without B5 disagrees with the adjustments), before the Σ ≤ G assertion or the trigger's bound; the strengthened suite's staggered rounds alone survived (recorded). Verify texts corrected; §2 rows |
| **IMPORTANT** | **Every born-once arbiter never probed alone** — the tasks' live probes of `-011`, `-014`…`-024` were recorded "written and not run … not performed" | `BnplBornOnceArbitersDatabaseTest` (the Phase 11 review's `BornOnceArbiters` applied to `bnpl`): the sixty-eight uniques and seven foreign-key targets pinned; sixty-one refused alone with every trigger off and every `CHECK` dropped; probed twice (a unique dropped, one widened) — caught both |
| **IMPORTANT** | **"Σ allocation = amount for every repayment (the deferred trigger, proven alone by a raw-SQL writer)" had no test** — the one raw writer's sum held and was refused by the over-pay rank | `BnplRepaymentDatabaseTest#aRepaymentNotAllocatedWholeIsRefusedAtCommit`, both roles. Probed |
| **IMPORTANT** | **No raw edge out of any terminal state tested on a real row**, for any machine (the purchase's `FINANCED → AUTHORISED` and `FULLY_REFUNDED` only by text-matching the trigger body) | `BnplTerminalEdgesDatabaseTest#nothingLeavesATerminalStateForAnyRole` — fifty-eight edges, nine machines, both roles, real terminal rows. Probed |
| **IMPORTANT** | **"A session that expires closes the application and the approval lapses unconsumed"** proven by abandonment, with no late approval and no consumption assertion | `BnplOriginationDatabaseTest#aSessionThatExpiresLetsTheLaterApprovalLapseUnconsumed`. Probed (the old test stayed green under the same mutation) |
| **IMPORTANT** | **`#acceptanceAndExpiryLeaveExactlyOne` admitted `OFFERED`** and never counted both outcomes; session expiry only through checkout's port | Rewritten; `#acceptanceAndSessionExpiryLeaveExactlyOne` added. Probed |
| **IMPORTANT** | **The acceptance evidence and "no consent purpose" unasserted**; `credit.DecisionConsumed` with `BNPL_AGREEMENT` asserted only by credit's own port test | `BnplPurchaseDatabaseTest#theAcceptanceStoresItsEvidenceAndNoConsent`; `#aFailureAtEveryStepLeavesNothing` widened to financing, obligation and bnpl-keyed entries. Probed |
| **IMPORTANT** | **§7.4 row 8: a payout racing a BNPL refund** untested; B7's negative-position gauge asserted only on a synthetic sample | `BnplMerchantPayoutDatabaseTest#aPayoutRacingABnplRefundIsSerialised` — both orders counted, `INV-MER-05` held, the gauge read from the registry. Probed twice |
| **IMPORTANT** | **§14 row 11: a merchant suspended with open purchases keeps them serviced and refundable** — only "new BNPL refused" tested | `BnplPurchaseDatabaseTest#aSuspendedMerchantsOpenPurchaseStaysServicedAndRefundable` (collected; refunded through the operator door; the merchant's own key `401`). Probed |
| **IMPORTANT** | **"Purchases, loan acceptances AND DRAWS racing for the last capital"** — no draw ever raced | `BnplCapitalDatabaseTest#theLastCapitalAdmitsOneOfBnplLoansAndDraws`. Probed |
| **IMPORTANT** | **(P) never raced against a real wallet-paid refund**; the test faked the capture and asserted only that one order was seen | `BnplRefundApiTest#aRefundRacingAWalletPaidCaptureNeverDeadlocks` — a real capture and a real payments refund, both `UUID` orders asserted [3,3]. Probed (a reversed order deadlocks) |
| **IMPORTANT** | **Scenario 10's "both orders"** — order two serial; **scenario 11's "both orders occurring"** asserted nowhere | Scenario 10 races a merchant refund against a dispute opening over eight rounds, both orders asserted; scenario 11 counts both |
| **IMPORTANT** | **"DPD equals the date difference for 100% of obligations in every storm round and over a generated history"** — no storm census, no generated history | The storm's C19 at every reading (the Java rule over billed items and the hold against `bnpl.days_past_due`, non-vacuity asserted); `BnplDelinquencyStatePropertiesTest`, 400 histories with holds and lifts against independent oracles. Probed (two) |
| **IMPORTANT** | **Twelve of fourteen keyed doors never raced ten times under one key** | `BnplOneKeyRaceDatabaseTest` — eighteen doors, one effect, one byte-identical receipt, only `409 api.IdempotencyInProgress` otherwise. Probed (two survivals: a second, independent guard) |
| **IMPORTANT** | **The dispute resolution's four-eyes door had no API proof of `403` + a `FAILED` record**, nor `401`/`403` on approval and rejection; **the waiver's self-approval** only a storm total; **self-dealing through the API** proven only at investigation | `BnplDisputeApiTest#aProposersOwnApprovalIs403AndRecordedFailed`, `BnplServicingApiTest#aWaiverProposersOwnApprovalIs403AndRecordedFailed`, three insider cases (dispute, waiver, operator refund). Each probed |
| **IMPORTANT** | **"A crash after a collection, a payoff, a dispute resolution is re-driven by another instance"** — collection and payoff hermetic and same-instance, the resolution untested | Four crash-and-re-drive cases (`BnplInstanceCrash`): collection, payoff, operator resolution, lapse. Probed (five) |
| **IMPORTANT** | **The application's "lesser session, nothing written"** proven on a made-up checkout token | `BnplApplicationDatabaseTest#aLesserSessionWritesNothingAgainstARealSession`. Probed |
| **IMPORTANT** | **Drift in all six ADRs and every Phase 12 document** — `DISTRIBUTED_EXECUTION.md` §3's writer, lock-order and servicing rows still opening *Planned*; B6 said to lock the payoff quote and the waiver proposal (neither is locked); `BnplUnitOfWork` named and never built, three ports built and never named; the refund's arbiter written with two columns (it has three); `PENDING` named a merchant signal (never built); "posting date = value date" (each rule has its own value date); the INV-MER-07 amendment "effective when -021 lands"; `BnplWallets` said to open a wallet (it never does); the glossary six entries ahead of `DOMAIN_MODEL.md` (the union guard red-in-waiting); an invariant naming a test method that does not exist; the "BNPL cannot originate in production" debt row claimed attached and not | Every statement made true — area 7 |
| **IMPORTANT** | **The debt rows the gate demands were mostly missing** (BNPL in the production-origination row, write-off and the merchant discount's effective-interest treatment, the BNPL evidence purge, BNPL collections, `INV-DSP-01`'s extension) and two were stale (the storm's measurement never written on the capital row; the replay's ordering half paid by `V017`) | Written — area 8 |
| **IMPORTANT, recorded** | **The replay re-derives every refund through the production refund functions** — a defect in `BnplRefunds.derive` would be reproduced, not caught, and the three-way identities do not constrain how a refund splits into cancellation and excess (the financial-defect audit) | No such defect exists — the battery's independent oracles re-derive every refund without `derive`; a debt row (Phase 14 or the next proof change, `RG-M5`'s precedent) and an owner item. Beyond the gate's demand (the three-way proof zero, a planted foreign line failing it — both hold) |
| MINOR, recorded | The three schedules log a claim-level sweep failure but never meter it; a dispute lapse whose refund cannot post retries for ever unalerted | A debt row (Phase 15); owner item |
| MINOR | Two time-of-day and order defects in the bnpl suites (found writing the new cases): `BnplDisputeDatabaseTest` relied on another suite's terms; `BnplPayoffDatabaseTest#theRanksHoldForEveryWriter` used `current_date` in a UTC+4 session (red 20:00–24:00 UTC) | Both fixed (the `P11-TSK-032` class) |
| MINOR | `bnpl_refund.merchant_id`, `bnpl_dispute.merchant_id` classified `INTERNAL` (plan §11: `CONFIDENTIAL`); `bnpl_offer_terms.nominal_annual_rate` `INTERNAL` (its source is `RESTRICTED-FINANCIAL`) | Raised in `DATA_CLASSIFICATION.md` |
| MINOR, recorded | Owner-level residues (self-dealing fail-open where the operator has no party; a contested dispute without a deadline; a reversal re-opening a released late fee; the bucket labels fixed while terms admit another threshold; `recorded_order` not enforced for every writer; `Instant.EPOCH` stamps; the claim-failure meter; the operator-identity classification split; product-tag casing) | § *Decisions for the owner* |

## The flip

Recording Phase 12 `COMPLETE` is the guarded act: the register guard begins demanding a §2 row for every `Phase: 12`
invariant and a §4 row for every `P12-TST` item, and `PlannedMetersExistTest` reads `PHASE_12_PLAN.md` §15 through the
generic union (`BnplMetersTest` had held the thirty-two series ahead of the flip). Against the real status, `MutationDemonstrationTest`
passed (below). **And it was proven non-vacuous against the real status**: with `INV-BNPL-08`'s five §2 rows withheld, the guard
failed reporting `(currently 12)` - the flip parsed - and naming exactly `["INV-BNPL-08"]`; the register was restored
byte-identical (sha256 `c0599f2e…`, verified). As in Phases 10 and 11, no `Phase: 12` invariant was found unrowed: every task
filed its own, and this review's probes add twenty-nine rows more. The same run caught one of this review's own new rows naming no
test the parser reads (a row citing "the four cases above") - corrected to name them, the guard's second assertion doing exactly
its job.

---

## Area 1 — Domain correctness: what the phase set out to build, and what it built

Twenty-nine backlog items across nine milestones (M12.1–M12.9), every one `COMPLETE` with this review, plus the two
remediation items of the owner-authorised database pass (`P12-TSK-027`, `-028`) and `X-TSK-018` beside them. Both cut
candidates were **built** — `-023`'s operator dispute resolution and `-022`'s pay-in-N payoff — so the gate's conditional
clauses are met by the tasks. Migrations: bnpl `V001`–`V017` (no `V005`: the plan never assigned it, the `-012`/`-013` pair
took `V007` and `V006`), and the neighbours credit `V022`, ledger `V027`, checkout `V004`, merchant `V010` and `V011`. The
implemented concepts match `BNPL_LIFECYCLES.md` and the glossary after this review's corrections (area 7): every machine read
against its trigger, the terminal edges now proven for both roles on real rows. Refinements recorded rather than invented: a
payoff makes no agreement or schedule version (it bills early, lending's shape); an `OFFERED` application holds its session
(`-011`); the merchant's `PENDING` signal is the absence of the eligibility event; the refund has no status column.
**`PASS`.**

## Area 2 — Financial correctness: a purchase end to end

Walked hop by hop, and driven together in the storm: a keyed, MFA-assured application opening credit's request in the same
transaction; the origination sweep reading credit's decision and making the offer (`expires_at = LEAST(decision valid_until,
session expires_at, offered_at + validity)` on the database clock); the acceptance T-pur under L0 → B2 → B3 → credit's
consumption → L6 → (P) → L8 — the purchase, the agreement with its evidence, rule F1 (`DR BNPL_PRINCIPAL G / CR
MERCHANT_PAYABLE G − f / CR BNPL_MERCHANT_FEE_INCOME f`), the obligation, schedule v1 and (pay-in-4) instalment 1 billed and
collected, all in one commit; T-acc accruing pay-in-N once per date (F2a); billing (F2), the catch-up application (F3a),
auto-collection and customer repayment (F3), each value-dated at the obligation's floor; the refund (F5, F5a) latest
instalments first, the excess to the wallet, the fee pro-rated back by merchant's cumulative arithmetic, B7's per-purchase
bound on the payable; the dispute's refund outcome through the same body under the same B5; the payoff (F6); the reversal and
the waiver (F7, F8); the closure refund. Every rule of `PHASE_12_PLAN.md` §12.11 has a test that exercises it (F11 deferred).
Every figure is a balance of journal lines, explained by `BnplSubledgerProof`, replayed by `BnplReplayProof` and closed three
ways by `BnplThreeWayProof`, at every storm reading. **Found:** no defect in the money; the replay's refund derivation shares
the production functions (recorded, above). The financial-defect audit's three MINOR residues — a reversal re-opening a late
fee a refund had made unowed, a contested dispute with no deadline, a lapse that cannot post — are owner items, each failing
toward the customer or closed, none moving money wrongly. **`PASS`.**

## Area 3 — Boundary integrity and multi-instance correctness

`bnpl` → `creditmath`, `ledger`, `platform`, `sharedkernel` only; every other reach a port `app` implements (nine, and
`app`'s `BnplTransactions` as the transaction seam — `BnplUnitOfWork` was never built); nothing depends on `bnpl` but `app`
(`BnplModuleIsolationTest` and its siblings). Wallet-only collection by construction: `bnpl` cannot see `payments`.

The ten-instance answer re-checked for every task against its counted tests (the table below). The twenty-one rows of
`PHASE_12_PLAN.md` §7.4 each have their arbiter and a test that raises it — ten ways where the row is a race, two instances
where it is a pair, the fault rows (scenario 5, the hints twice, late or never, the partial failures) by their scenario tests.
The coded acquisition order was read for every transaction, the ledger's hidden locks (`FOR KEY SHARE` on accounts,
`account_balance` rows sorted by id) and the triggers' re-takes included: L0 first wherever taken; B5 before B4; B3 judged at
once; (P) one pair in `UUID` order; every shared income row only after (P); T-acc commits alone and holds no (P). The one
exception to "one order" — T-svc and T-pay taking the obligation's own projection rows before (P) — is acyclic (rows written
only under B4) and now written in §7.2 and `DISTRIBUTED_EXECUTION.md` §3. The three leaderless schedules are in
`NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` (twenty-nine) with their `…sweeper.enabled` gauges; advisory
namespace `12` registered; no `synchronized`, static mutable state or instance-clock window in `bnpl`'s main code; `bnpl` calls
no provider.

**Found and corrected** (above): the arbiters never probed alone; row 1 (the one-key race), row 7 (no draw racer), row 8 (no
payout-against-refund race), row 9 ((P) against a faked capture, one order). **Recorded**: the bnpl-module database races run
over an in-memory book, so the ledger's (P) and L8 row locks are exercised only by the `app` suites and the storm; the
repayment's refinement (`-018`: T-rep posts at the floored date and only the closure waits for T-acc) against the gate's "a
repayment finding an unaccrued date writes nothing" — sound by the floor, recorded under the gate. **`PASS`.**

### Every task's ten-instance answer, re-checked

| Task | Answer | Counted tests | Verdict |
|---|---|---|---|
| `-001` | `PASS` | credit `CreditDecisionConsumptionDatabaseTest` (ten consumers, `BNPL_AGREEMENT`) | holds |
| `-002`, `-008`, `-009` | `PASS` (pure engines) | two-JVM digests; the battery | holds |
| `-003` | `PASS` | `BnplChartDatabaseTest#tenOpenersConvergeOnOneSet` | holds |
| `-004` | `PASS` | `BnplTermsDatabaseTest`, `BnplTermsEndpointDatabaseTest` (ten approvers) | holds |
| `-005` | `PASS` | `BnplCapitalDatabaseTest` (both orders; since this review, draws) | holds |
| `-006` | `PASS` | `MerchantBnplEnablementDatabaseTest` (ten pinners) | holds |
| `-007` | `PASS` | `CheckoutFinancingDatabaseTest` (pairwise instrument races) | holds |
| `-010` | `PASS` | `BnplApplicationDatabaseTest` (ten applications, one session) | holds; row 1's one-key race since this review |
| `-011` | `PASS` | `BnplOriginationDatabaseTest#tenSweepersOfferOnce`, `#hintsTwiceLateOrNeverConverge` | holds (first run in the database pass) |
| `-012` | `PASS` | `BnplCreditExposureDatabaseTest` | holds |
| `-013` | `PASS` | `BnplPurchaseDatabaseTest` (scenarios 1–3, the last capital), `BnplAcceptanceDatabaseTest` | holds |
| `-014` | `PASS` (read-only) | `BnplMerchantReadDatabaseTest` | holds |
| `-015` | `PASS` | `BnplBillingDatabaseTest#tenSweepersBillOnce` | holds |
| `-016` | `PASS` | `BnplMerchantPayoutDatabaseTest` (since this review, against a refund) | holds |
| `-017` | `PASS` | `BnplAccrualDatabaseTest#accruesOncePerDate` (ten, a restart, ±5 s) | holds |
| `-018` | `PASS` | `BnplRepaymentDatabaseTest` (two repayments, ten keys, the billing race) | **was wrong at its gate** — the storm found B4 reading a stale agreement version beside the lock (`4f9035ef`); `PASS` since |
| `-019` | `PASS` | `BnplCollectionDatabaseTest#tenSweepersCollectOnce`, `BnplCollectionLedgerDatabaseTest` | holds |
| `-020` | `PASS` | `BnplConditionDatabaseTest#tenSweepersOneConditionChange`, `BnplLateFeeDatabaseTest#tenSweepersAssessOnce` | holds |
| `-021` | `PASS` | `BnplRefundDatabaseTest` (scenario 7, two instances, ten refunds) | as `-018` (the same B4 defect reached refunds' racers); `PASS` since |
| `-022` | `PASS` | `BnplPayoffDatabaseTest` (scenario 11, ten executions) | holds; both orders counted since this review |
| `-023` | `PASS` | `BnplDisputeDatabaseTest` (ten resolvers, ten lapse steps) | holds; scenario 10's order two raced since this review |
| `-024` | `PASS` | `BnplRepaymentReversalDatabaseTest`, `BnplWaiverDatabaseTest` (ten approvers) | holds |
| `-025`, `-026` | `PASS` | `BnplProofDatabaseTest`, `BnplReportsDatabaseTest` | holds |
| `-027`, `-028`, `X-TSK-018` | `PASS` (no production change but `V012`'s name collision) | the database pass | holds |
| `P12-TST-001`, `-002` | `PASS` | the storm (two instances, ten-way scenes, 0 × `40P01`); the battery | holds |

*Every database case `-011`…`-024` cited at its gate first ran in the owner-authorised database pass (2026-10-20,
`P12-TSK-027`/`-028`); two `PASS` answers (`-018`, `-021`) rested on a defect only the storm found. That is the cost of
answering on unrun tests, and it is why this review re-ran the bnpl tier whole.*

## Area 4 — Failure behaviour

The twenty rows of `PHASE_12_PLAN.md` §14 each have a test or an accepted rationale: sixteen by test (row 11, the suspended
merchant, since this review; row 16's agreement version 2 now asserted at the database), four by recorded rationale — row 6
(a webhook duplicated: bnpl consumes no provider callback; payments' inbox does), row 7 (two requests race: §7.4, area 3),
row 8 (settlement late: the merchant payout is payments' and Phase 8's), row 9 (a reconciliation break: the payout's, proven
by the storm's provider report matching every send). Traced through the code: (1) a payout provider that succeeds while the
response is lost (scenario 5) — the payout `UNKNOWN` with its hold, the merchant's retry replaying the key, the inquiry
concluding and posting once, dispatches counted on both sides; (2) a refund racing a collection (scenario 7) — B5 → B4
against B4, both orders, conserving. **Found and corrected:** the crash-and-re-drive of a collection, a payoff and a dispute
resolution by another instance (above). **`PASS`.**

## Area 5 — Security and audit

Every bnpl route enumerated with its permission, assurance and negatives; the five permissions and two roles pinned by
`RoleNameTest`; every operator route in `RoutePermissionRegisterTest` (merchant routes in `MerchantTenancyBatteryDatabaseTest`,
customer routes in `OwnershipIsScopedTest` — the gate's "every route is in `RoutePermissionRegisterTest`" is impossible by
construction for routes without `@RequiresPermission`, recorded under the gate); another party's and another merchant's real
records answering `404 bnpl.NotFound`; every four-eyes act refused at the domain and at the `CHECK`, each alone; no route lets
an operator create a purchase, finance a merchant, cancel an instalment outside a refund or a dispute, or edit a figure; every
column classified (two raised here); every audit act of §11 catalogued in `AUDITABLE_ACTIONS.md` (29 both ways); `ERROR_CONTRACT.md`'s
31 bnpl codes equal the code's; no amount, party, merchant, agreement id or DPD in any bnpl log line or exception message.
**Found and corrected:** the dispute's and the waiver's self-approval and three doors' self-dealing through the API; the
application's lesser session on a real session; the eligibility event's exact payload (no credit data to merchants: only
`{checkoutSessionId, outcome}`). **Recorded for the owner:** self-dealing fails OPEN at the operator refund door and the
dispute's operator acts when the platform cannot place the operator in a party (it fails closed at the corrections and the
investigation); the operator's dispute read carries no self-dealing check. **`PASS`.**

## Area 6 — Test quality: invariants, the register and the demonstrations

Ten invariants carry Phase 12 in their catalogue line, read with the guard's own token rule: `INV-BNPL-01`…`10`
(`INV-MER-02` and `-07` carry `Phase: 6` and have been demanded since; their BNPL terms and the amendment each have Phase 12
rows). Every one has performed `MUTATION_TESTING.md` §2 rows; `P12-TST-001` and `-002` have their §4 rows. This review adds
twenty-nine §2 rows for its own probes — three honest **survivals** (the staggered rounds alone against a B5 skip; the
application's bounds check, held by credit's; the dispute's and the repayment's claims, each held by a second independent
guard) — and a note discharging the "written and not run" notes the database pass ran, keeping their unperformed live probes
recorded as owed rather than claimed. Verify texts corrected where the performed probe differed from the one named
(`INV-BNPL-06`'s catcher; `INV-BNPL-07`/`-08`'s B5 probe; `INV-BNPL-02`'s class; `INV-BNPL-07`'s three-column arbiter).
**`PASS`.**

## Area 7 — Documentation accuracy

All six ADRs read against the code, corrected in place with dated notes and accepted (below). `PHASE_12_PLAN.md` (inline
corrections and a new §25 *Errata — as built*), `BNPL_LIFECYCLES.md` (every machine as built, the lock and clock tables, the
uniques, a new §9 of cross-cutting facts), `MODULE_ARCHITECTURE.md` (the ports as built), `BOUNDED_CONTEXTS.md` (every lock
BNPL takes in another context), `GLOSSARY.md` and `DOMAIN_MODEL.md` (twenty-four planned entries as built; the six terms the
union guard was missing), `LEDGER_MODEL.md` §11 (the posting rules as built; nine of ten purposes post,
`BNPL_WRITE_OFF_EXPENSE` none), `DISTRIBUTED_EXECUTION.md` §3 (the three rows opening *Planned*; B6; the hidden locks),
`DATA_CLASSIFICATION.md` (three levels), `AUDITABLE_ACTIONS.md` (two rows and a count), `FINANCIAL_INVARIANTS.md` (eleven
corrections), `OPERATIONS_RUNBOOK.md` §8 (the first refusal a customer meets, a missing alert line, a wrong remediation),
`CAPABILITY_MAP.md` (BNPL delivered), `ROADMAP.md`, `DECISIONS.md` and the ADR index — all made true. `ERROR_CONTRACT.md`
was read and is accurate. **`PASS`.**

## Area 8 — Architectural debt, and the Phase 13 boundary

The rows the gate demands are now in `CURRENT_STATE.md` §Known Architectural Debt with their owners: BNPL cannot originate
in production (attached to lending's row, #13/#14); write-off, provisioning and the merchant discount's effective-interest
treatment (Phase 14); the BNPL agreement and evidence purge (Phase 15); the capital and fee-income rows as the storm measured
them (`LENDING_CAPITAL` 65 waits, p95 142 ms; `BNPL_MERCHANT_FEE_INCOME` 12 waits, max 43 ms — the trigger, a production
volume characterisation, not met; Phase 16); collections (Phase 13); `INV-DSP-01`'s extension should card-funded instalments
ever be added; capital return and income recycling (still unowned — asked again at the transition) — plus four this review
found: the replay's shared refund derivation (Phase 14 or the next proof change), the schedules' unmetered claim failures
(Phase 15), the bnpl database-error and record-store residues (the owner's), and the settlement register's string-compared
shapes (`X-TSK-018`'s, the owner's). Neither cut candidate was cut, so no cut row. **The Phase 13 boundary holds**: no
collections case, risk, fraud or AML decisioning, write-off posting, provisioning, card-funded instalment or system-initiated
card charge, spending limit, refinance, multi-currency BNPL or consumer-credit-law code in any main source set (every hit read:
javadoc deferrals; the seeded and unposted `BNPL_WRITE_OFF_EXPENSE` and the `CHECK`s closing the vocabulary; Phase 10's
`NotAssessedUntilPhase13` fail-safe; payments' card-dispute vocabulary; EUR only by `BnplProduct` and the door; the excluded
features listed in `BnplTerms`). **`PASS`.**

## The storm on the final code

`P12-TST-001`'s three consecutive green runs (2,802 s, 2,815 s, 2,748 s) ran on the code merged at `2b0d68b6`. The final code
differs from it by no production change: this review changed tests and documents only. The storm was run once more, alone,
with this review's census addition (C19, days past due at every reading), `:app:ownContainerDatabaseTest --tests
BnplStormDatabaseTest --rerun` in its own container, nothing else running:

| Run | UTC start | Duration | Rounds / readings (at rest) | Obligations (pay-in-4) | Deadlocks | C19 |
|---|---|---|---|---|---|---|
| R | 2026-10-21 21:46:14 | 2,760 s (the test 2,749 s) | 8 / 107 (47) | 383 (216), 256 closed | 0 | 107 readings, every ACTIVE obligation's DPD equal both ways; at most 4 past due at one (non-vacuous, asserted) |

One test, zero failures; every census exact at every reading; every scenario's minimum met; one restart; 477 refunds (99
through disputes), 132 disputes, 2,139 repayments, 176 approved reversals, 236 payoffs, 220 payouts, 76 loans beside them; the
census cost 5.9 s a reading on average. **What the re-run does not re-prove:** `P12-TST-001`'s nine storm probes were performed
on its own code and not re-performed (the code they mutate is unchanged); C19 itself is not probed (a probe is a storm run -
recorded, MINOR; its non-vacuity is asserted). One run, not three: the three consecutive green runs the gate demands are
`P12-TST-001`'s, on production code this review did not change.

## The eighteen mandatory scenarios

| # | Scenario | Named test | Asserts what it claims | In the storm |
|---|---|---|---|---|
| 1 | Duplicate purchase authorisation | `BnplPurchaseDatabaseTest` (same key, ten keys) | yes — one purchase; the ten-under-one-key race at every door since this review | yes |
| 2 | BNPL completion vs card confirmation | `BnplPurchaseDatabaseTest` (both orders) | yes | yes |
| 3 | Acceptance vs a loan on the profile | `BnplPurchaseDatabaseTest#acceptanceAndADecisionSerialiseOnTheProfile` | partly — the waits; exposure counted once by `BnplCreditExposureDatabaseTest` | yes |
| 4 | Ten collection sweepers | `BnplCollectionDatabaseTest#tenSweepersCollectOnce` | yes, the arbiter alone | yes |
| 5 | Provider succeeds, response lost | `BnplMerchantPayoutDatabaseTest#aLostPayoutResponseIsHeldAndTheRetryReplays` | yes — dispatches counted on both sides | yes, and the card top-up variant |
| 6 | Two repayments | `BnplRepaymentDatabaseTest#twoRepaymentsSerialiseAndConserve` | yes | yes |
| 7 | Refund racing a collection | `BnplRefundDatabaseTest#aRefundRacingACollectionConservesInBothOrders` | yes — both orders by the storm's per-order coverage and the hermetic forced orders | yes, both orders counted |
| 8 | The same refund twice | `BnplRefundDatabaseTest#theSameRefundTwiceRefundsOnce` | partly — no lapsed claim is staged; the key's unique refused alone by the arbiter probe since this review | yes |
| 9 | Refund before, during, after | `#refundBeforeDuringAfter`, `#excessGoesToTheCustomerAndAPartialRefundReducesTheLatestInstalments`, `#aFullRefundClosesWithNothingStranded` | yes — the "before" refund's adjustments, the excess's destination and agreement v2 asserted since this review | yes |
| 10 | Dispute refund and merchant refund | `BnplDisputeDatabaseTest#disputeRefundAndMerchantRefundShareOneBound` | yes — both orders raced since this review; the B5 probe performed | yes (dispute first) |
| 11 | Payoff beside a refund | `BnplPayoffDatabaseTest#payoffBesideARefundIsStaleOrClosedNeverWrong` | yes — both orders counted since this review | yes |
| 12 | A reversal re-opens what it paid | `BnplRepaymentReversalDatabaseTest#aReversalReopensWhatItPaid` | yes, DPD recomputed | yes |
| 13 | No condition after closure | `BnplConditionDatabaseTest#noConditionAfterClosure` | yes — by `recorded_order` since this review | yes |
| 14 | Crash between posting and publication | `BnplOutboxAtomicityDatabaseTest`, `BnplPurchaseDatabaseTest#aFailureAtEveryStepLeavesNothing` | yes | yes (the relay killed after the broker received) |
| 15 | Hints twice, late or never; session expiry | `BnplOriginationDatabaseTest#hintsTwiceLateOrNeverConverge`, `#aSessionThatExpiresLetsTheLaterApprovalLapseUnconsumed` | yes since this review | yes |
| 16 | A repayment after a full refund | `BnplRefundDatabaseTest#aRepaymentAfterAFullRefundIsRefused` | yes — nothing written asserted since this review; the `409` in the storm | yes |
| 17 | Late fee once | `BnplLateFeeDatabaseTest#assessedOnceAtTheLateFeeDay`, `#tenSweepersAssessOnce`, `#aCancelledInstalmentsLateFeeIsReleased` | yes | yes |
| 18 | Accrual once per date | `BnplAccrualDatabaseTest#accruesOncePerDate`, `#skewNeverAccruesEarlyOrTwice` | yes | yes |

## Probes

Every test this review added for a gate clause, and the plan's one never-performed probe, broken on purpose — the mutation
made inside the serialised Gradle run (an init script applying it and restoring the original bytes when the build closed, so no
other run compiled a mutant), the test run, the red observed, the file restored byte-identical (sha256 before and after) — each
recorded in `MUTATION_TESTING.md` §2 (twenty-nine rows, `P12-DOC-001`): §13.4's purchase-lock skip (caught by the refund's own
position check under B4; the staggered rounds alone survived); the arbiter suite (a unique dropped, one widened — both caught,
the sets pinned from the first run, the Phase 11 review's two survivals applied forward); the allocation trigger; the terminal
edges; scenario 13's record order; the crash-and-re-drives (five); the cure and the DPD properties (three); the session's
lapse; acceptance against expiry; the evidence; the payout against a refund and its gauge; the suspended merchant; the draw
racer; (P) reversed (a real deadlock); the lesser session; the bounds and the currency (one survival: credit's bound behind
bnpl's); the four-eyes and self-dealing doors (five); the eligibility payload; the one-key races (two survivals: a second,
independent guard). One test-side plant (`#aFailureAtEveryStepLeavesNothing`: no production mutation can leak past the
rollback). Not probed: the storm's new C19 (a probe of it is a storm run; recorded, MINOR — its non-vacuity is asserted).

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — both products from checkout to closure, end to end through HTTP, against the simulators (the production reality, below) |
| 2 | Architectural boundaries respected | `PASS` — `BnplModuleIsolationTest` and its siblings; `bnpl` → `creditmath`, `ledger`, `platform`, `sharedkernel` only |
| 3 | Required invariants tested | `PASS` — ten `Phase: 12` invariants, every one with performed §2 rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — all twenty of §14, four by recorded rationale |
| 5 | Security requirements implemented | `PASS` — area 5 |
| 6 | Observability exists | `PASS` — the thirty-two §15 series registered eagerly with closed tags, sixteen alert rules each seen to fire, the rules resolved against a live scrape (`AlertRulesResolveTest`, run fresh by this review), spans per step with no attributes; the claim-level sweep failure a recorded debt row |
| 7 | Integration tests pass | **`PARTIAL` — the standing deviation, recorded** (below) |
| 8 | Documentation reflects reality | `PASS` — area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0101…0106, each read against the code and corrected first |
| 11 | No unresolved critical issues | `PASS` — none critical or high; every IMPORTANT finding corrected and probed, but the replay's shared refund derivation, recorded as a detective-control debt with no defect behind it |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner decided (B4, 2026-10-16) to keep skipping the fleet-wide
`databaseTest` and `kafkaTest` tiers through Phase 12, asked again at the Phase 12 → 13 transition. **On 2026-10-20 the owner
chose "run a DB pass now"**, and Phase 12's own database suites ran: `:bnpl:databaseTest` 174, the app selection 305,
settlement 89 (the three suites red since `P9-TSK-026` repaired by `X-TSK-018`), the platform guards, and the storm three
times. **The pass found real database-only defects** — `bnpl V012`'s constraint-trigger name collision (`P12-TSK-027`) and the
two the storm found (`V017`'s record order; B4 reading the committed agreement version) — exactly the class a skipped tier
hides. This review graded the criterion as the evidence supports — **`PARTIAL`**:

- **Run, fresh, on the final tree**:
  - `:bnpl:test` (the hermetic tier) **549 tests across 51 suites**, `:bnpl:architectureTest` **3**, and **`:bnpl:databaseTest`
    whole - 182 tests across 19 suites** (every suite this review touched beside every other, so the tier's order dependence is
    exercised) - **0 failures**. One hermetic case was red on its first fresh run: `BnplAcceptancesTest#noFigureLeaks` found
    the needle "576" inside a time-ordered id's hex - a test flake, the amounts now judged with ids masked; re-run 549/0.
  - The review's `app` database cases, routed through `:app:databaseTest`: **98 tests across 9 suites** (`BnplApplication`,
    `BnplMerchantPayout`, `BnplOrigination`, `BnplPurchase` and `BnplOneKeyRace` `DatabaseTest`s, `BnplRefund`, `BnplDispute`
    and `BnplServicing` `ApiTest`s, `lending.BnplCapitalDatabaseTest`) - one red on a fixture's clock (the session's deadline
    planted behind an instance-clock `created_at`; the fixture now waits for the database clock), its suite re-run **11/0**;
    `AlertRulesResolveTest` **9** (the bnpl rules resolved against a live scrape - the clause `-026` recorded owed);
    `ColumnClassificationTest` **5** (after the three classification changes) - **0 failures**.
  - The storm once on the final code (above): **1 test, 0 failures, 2,760 s**.
  - After the flip: `:app:architectureTest` **171 across 32** (every document guard: `MutationDemonstrationTest`,
    `AdrRegistersAreReconciledTest`, `TestTaxonomyTest`, `ErrorCodeRegistryTest`, `AuditableActionRegistryTest` among them);
    `PlannedMetersExistTest`, `OpenApiContractTest`, `RoutePermissionRegisterTest` **22 across 3**; `DomainGlossaryTest` **9** -
    **0 failures**.
  - Every count above is from a fresh run's XML, every Gradle run serialised (one at a time) through the review's mutex.
- **Not run**, on the owner's instruction: `gradlew build`, the fleet-wide `databaseTest` and `kafkaTest`, and every previous
  phase's module database suites. No fleet-wide database or kafka count is claimed for Phase 12. The question is the
  transition's, not this review's.

## The financial supplement F1–F8 — re-assessed at the gate

| # | Verdict |
|---|---|
| F1 | `Met` — the trial balance zero per currency over every BNPL posting, at every storm reading (C6) |
| F2 | `Met` — every obligation's balances explained by its rows (`BnplSubledgerProof`, C2) and replayed from zero (`BnplReplayProof` `IDENTICAL`, C3) every reading; `BnplSchemaHasNoMutableMoneyTest` |
| F3 | `Met` — every keyed door raced ten times under one key (since this review), every system step born once beneath it, every arbiter refused alone |
| F4 | `Met` — refund, dispute refund, repayment reversal and fee waiver as postings; no `AdjustmentService` on a BNPL account (`BnplBookPurposesTest`); write-off deferred to Phase 14 (a debt row) |
| F5 | `Met` — bnpl consumes no event for correctness (hints only): a duplicated `CreditDecisionRecorded` or checkout hint writes no second offer or purchase |
| F6 | `Met` — area 3 |
| F7 | `Met` — `NoFloatingPointMoneyRulesTest` over `bnpl` and `creditmath`, a planted `double` refused |
| F8 | `Met` — BNPL adds no external evidence: the merchant payout reconciled by payments' and Phase 8's machinery (the storm's provider report), capital by bank recognition, internal flows by the three-way and subledger proofs; the safeguarding adjustment widened by BNPL principal deferred with Phase 14/15 as owner |

## The Phase 12-specific criteria

Counted from the gate: the five originals made measurable, the per-area blocks, criterion 7's standing deviation and the
production reality, each read clause by clause against the code and its counted tests.

**The five originals.**

| # | Criterion | Verdict |
|---|---|---|
| 1 | Refund at every point, value-preserving | `PASS` — the allocator's properties over every generated refund order (R = Σ cancelled latest-first + excess, no paid amount cancelled, the fee telescoping to f), the battery's 20,106 refund orders, `#refundBeforeDuringAfter` and `#excessGoesToTheCustomerAndAPartialRefundReducesTheLatestInstalments` (the gate's `#excessGoesToTheCustomer`), the three-way proof zero at every storm reading |
| 2 | Full refund closes, nothing stranded | `PASS` — `#aFullRefundClosesWithNothingStranded` (six accounts zero, Σ fee returned = f), the subledger proof's `CLOSED` arm on the real ledger, `#aRepaymentAfterAFullRefundIsRefused` (nothing written since this review; the `409` in the storm) |
| 3 | Chargeback plus refund cannot double-credit | `PASS` — scenario 10 in both orders (raced since this review), Σ refunds ≤ G at every storm reading; §13.4's probe performed — caught by the refund's position check under B4, not by scenario 10's Σ assertion (recorded under the gate) |
| 4 | Financing and obligation atomic | `PASS` — one transaction; `#aFailureAtEveryStepLeavesNothing` at every T-pur step, now counting financing, obligation and bnpl-keyed entries; the deferred trigger's raw writer. The entry is not a trigger arm (no bnpl trigger reads the ledger): it is carried by the single transaction, the storm's C7 and the three-way merchant leg (recorded) |
| 5 | Three-way reconciliation balances | `PASS` — `BnplThreeWayProof` per currency in one `REPEATABLE READ` snapshot, a planted foreign line on either leg failing it, gauged and alerting, zero at every storm reading and over the battery |

**Per area.**

| Block | Verdict |
|---|---|
| Purchase and origination lifecycle | `PASS` — every machine's invalid edges refused by its every-writer trigger for both roles, the terminal edges on real rows since this review; one open application per session; the decision request in the application's transaction; an approval below the gross `CLOSED_UNDECIDED`; a session that expires closes the application and the approval lapses unconsumed (since this review); ten sweepers one offer; the hints twice, late or never; checkout's edge by financing; the BNPL completion, card confirmation and expiry sweeper pairwise (never all three at once — recorded); `422` refusals through the door (since this review) |
| Offer and agreement | `PASS` — the offer's expiry `LEAST` on the database clock; acceptance against offer and session expiry exactly one (since this review); `MULTI_FACTOR`, a lesser session on a real record writing nothing; the hash echo; the agreement `INSERT`-only for every role; every evidence field and no consent purpose (since this review); terms four-eyes under namespace `12`; version n+1 for every refund that changes the schedule and every dispute refund (a payoff makes none — recorded) |
| Merchant financing and settlement | `PASS` — F1 in the obligation's entry, the fee from the pinned schedule; enablement four-eyes; `INV-MER-02` against independent SQL (the drill-down classifies by posting-key namespace — recorded); a payout racing a financing and a refund serialised and counted (the refund since this review); B7's bound and the negative-position gauge on a real refund after payout (since this review); a suspended merchant's purchases serviced and refundable through the operator door, its own key `401` (since this review); the merchant reads carrying no credit data |
| Customer obligation and schedules | `PASS` — pay-in-4 golden cases by hand; pay-in-N on `SCHEDULE_ENGINE_V1`'s overload, every Phase 11 identity unchanged after the extraction; one schedule per version, billed instalments kept; billing once; every principal fact floored; the catch-up in every step and the payoff; `ACTIVE → CLOSED` only |
| Interest and fees | `PASS` — accrual once per date under `UNIQUE (obligation_id, accrual_date)` (refused alone since this review) and its key; ten sweepers, a restart, ±5 s; ACT/365F, cumulative rounding once; pay-in-4 no accrual; late fee once and capped; the waiver four-eyes and bounded; the merchant fee as income at purchase, never capital |
| Allocation and collection (wallet only) | `PASS` — wallet only by construction; scenarios 4, 6 and 12; Σ allocation = amount by the deferred trigger proven alone (since this review); oldest due first; one collection per (instalment, date); short and non-postable wallets; instalment 1 short refused; the reversal by rows |
| Refunds | `PASS` — latest first, the excess to the wallet, the fee pro-rated cumulatively; the worked check at engine level exactly and at 6× scale through postings (recorded); a late fee on a cancelled instalment released; pay-in-N accrued first; a refund after closure posting only payable and wallet; scenarios 7, 8, 9 and 16; the merchant door bounded and scoped, the operator door beside it |
| Disputes | `PASS` — the machine under its triggers, terminal edges since this review; one open per purchase; the hold on DPD, fees and collection; silence resolved once by ten sweepers; the refund outcome under B5 and the bound; upheld lifting the hold; four-eyes above the threshold through the API (since this review); scenario 10; `-023`'s operator resolution built |
| Payoff | `PASS` — the quote immutable; MFA; `UNIQUE (quote_id)`, `UNIQUE (obligation_id)` refused alone (since this review); stale `409` with nothing posted; no fee; six balances zero; every application at the floor (RG-M1 not repeated); scenario 11 both orders (since this review); a crash re-driven by another instance (since this review); `-022`'s pay-in-N payoff built |
| Delinquency | `PASS` — DPD at every storm reading (C19, since this review) and over 400 generated histories (since this review); buckets; 89/90; default once and a real cure clearing it once (since this review); once a day; a re-run writes nothing, a missed day caught up, a closed obligation skipped by the record's order; append-only for every role; default posts and accelerates nothing (since this review); no collections case |
| Ledger | `PASS` — the ten purposes closed to free adjustments, one poster per book, `BNPL_WRITE_OFF_EXPENSE` carrying no line (seed and source scan — no at-rest census, recorded); F1–F10 exercised, each value-dated per its rule (the gate's "posting date = value date" corrected in ADR-0103); the proofs and the trial balance at every reading |
| Capital and exposure | `PASS` — headroom judged under L6 at every commit that deploys capital; purchases, loans and draws racing for the last capital, one admitted (draws since this review); a principal-re-instating reversal under L6; ten consumers once for `BNPL_AGREEMENT`; scenario 3; exposure v3 one statement, v1/v2 replaying; the census every reading; fee income never in headroom (a source scan) |
| Multi-instance execution | `PASS` — area 3 |
| Idempotency | `PASS` — every keyed door ten under one key (since this review); every system step convergent; the merchant refund's key unique refused alone (since this review); the payout retry replaying its key |
| Failure recovery | `PASS` — area 4 |
| Security | `PASS` — area 5 |
| Audit | `PASS` — every §11 act catalogued and recorded in its transaction, losers recording nothing (the storm's C16), `credit.DecisionConsumed` with `BNPL_AGREEMENT` on bnpl's own path (since this review) |
| Observability | `PASS` — the series eager with closed tags, `PlannedMetersExistTest` armed by the flip; every alert fires in a test; the rules resolved against a live scrape (`AlertRulesResolveTest`, run fresh by this review); no amount or id in a tag; spans with no attributes |
| Testing | `PASS` — the eighteen scenarios named, green and in the storm; the storm three consecutive green runs on `P12-TST-001`'s code and one more on the final code with C19; the battery `IDENTICAL` in two JVMs; every §13.4 probe recorded and caught (the purchase-lock skip since this review); every arbiter probed alone (since this review); the re-dispatch probe caught by a two-sided count; the §4 rows; the settlement suites fixed by `X-TSK-018` |
| Documentation and invariants | `PASS` — area 7; every `Phase: 12` invariant with performed §2 rows; ADR-0101…0106 accepted |
| Universal criterion 7 | The standing deviation, recorded — not a pass |
| The production reality | Recorded as an accepted limitation (below) |

*Gate clauses met in a form their words did not anticipate, recorded under the gate rather than re-worded (`PHASE_GATES.md`,
the note after Phase 12's criteria):* the purchase-lock probe caught by the refund's position check, not scenario 10's
assertion; the earliest-first and fee-clamp storm probes caught upstream (the owner accepted defence in depth); a payoff making
no agreement version; the drill-down by posting-key namespace; the entry leg of `INV-BNPL-02` carried by the transaction; the
three-instrument race pairwise; wallet-only by construction; `PENDING` the absence of the event; "every route in
`RoutePermissionRegisterTest`" for operator routes only; the dispute response on the merchant's key without the refund scope
(ADR-0106); the repayment's floored posting before T-acc; per-rule value dates; the storm on two instances with ten-way scenes;
C19 reading paid amounts through `V008`'s one definition (the Phase 11 storm's I6).

## ADR-0101 … ADR-0106: accepted

Each read against the code, every drifted passage corrected in place with a dated note, and only then accepted:
`Accepted (2026-10-22, P12-DOC-001 — read against the code and corrected first)`. None needed a material amendment, and no
code had drifted from a decision that was right. The corrections: **ADR-0101** — the ports as built (nine; `BnplUnitOfWork`
never built, `app`'s `BnplTransactions` the seam; `BnplProductBounds`, `BnplActingParty`, `BnplProofReads` added; `BnplWallets`
never opens a wallet), merchant `V011` the sixth neighbour migration, B6 never on the quote or the waiver proposal, B4 in two
statements, the storm's measurement; **ADR-0102** — the amount bounds are `CreditProduct` constants, not configuration; the
terms key CUSTOMER-typed (A5-04); the terms fields as built; **ADR-0103** — value dates per rule; the catch-up key; F5's credit
balance and its refusal on a closed obligation; F5a as built; F6 without F2a; F7's `bnpl.waiver:` key; **ADR-0104** — L6 on a
reversal only when principal is re-instated; exposure v3 over every obligation; the measured figures; **ADR-0105** — the
`INV-MER-07` amendment in force since `-021`; the wallet never opened; the adjustment rows as built; the closure refund; the
three-column arbiter; no refund status; the `AccountClosing` pending-credits port not built (an owner item); **ADR-0106** — the
hold as rows, a touched instalment held whole; `clock_timestamp()` after the locks; the merchant statement as bounded text; the
resolution's key and sub-routes; the withdrawal's event and audit.

## The production reality, recorded as accepted

Production originates no BNPL purchase until unresolved #13 and #14 are answered: it composes only fail-safe credit sources, no
production decision can be `APPROVED`, and no BNPL terms version is `ACTIVE` (`422 bnpl.ProductNotOffered`; before it, at a
merchant not yet enabled, `422 bnpl.MerchantNotEnabled`). Every functional criterion above is proven against the simulators.
The runbook's activation preconditions (§8.1) add legal review of the merchant agreement and of the consumer terms and an
approved merchant-fee schedule to lending's. Recorded as an **accepted limitation, not a deviation** (`PHASE_12_PLAN.md` §1.1;
the debt row "Lending - and BNPL - cannot originate in production", BNPL attached by this review).

## Debt register (Phase 12's, after this review)

Every row is in `CURRENT_STATE.md` §Known Architectural Debt with its trigger.

| Debt | Owner |
|---|---|
| BNPL cannot originate in production (#13, #14) — attached to lending's row | The phase that answers them; Phase 15 at the latest |
| Write-off and provisioning; the merchant discount's effective-interest treatment | Phase 14 |
| The BNPL agreement and acceptance-evidence purge | Phase 15 |
| The capital and merchant-fee-income rows' contention (measured by the storm; trigger not met) | Phase 16 |
| Collections operations (BNPL's condition events emitted, no case) | Phase 13 |
| `INV-DSP-01`'s extension, should card-funded instalments be added | The phase that adds them |
| Capital return and income recycling (BNPL's income too) | **Unowned — the owner's, at the Phase 12 → 13 transition** |
| The safeguarding adjustment widened by BNPL principal outstanding | Phase 14/15 |
| The BNPL replay's per-reading cost (its ordering half paid by `V017`) | Phase 16 or a measured reading |
| The replay re-derives refunds through the production functions (found here) | Phase 14 or the next proof change |
| The schedules' claim-level failures unmetered; the unpostable lapse unalerted (found here) | Phase 15 |
| BNPL database-error and record-store residues (found here, and recorded by `-027`, `P12-TST-002`) | The owner's |
| The settlement register compares remittance shapes as strings (`X-TSK-018`) | The owner's |

## Decisions for the owner

Collected from every Phase 12 record — each task's owner judgements in `BACKLOG.md` and `TASK_HISTORY.md`, `CURRENT_STATE.md`'s
open items, `POSTPONED_ISSUES.md` — and from this review's audits; de-duplicated; none decided here.

### Open decisions

1. **Universal criterion 7** (B4) — the fleet-wide `databaseTest` and `kafkaTest` tiers and previous phases' database suites
   skipped through Phase 12; Phase 12's own pass found three database-only defects. Keep skipping, or run them before Phase 13
   opens.
2. **Closed merchant, closed wallet** (`-021`, `-023`; the financial-defect audit): a customer has no platform path to dispute
   a purchase at a merchant that has closed (`409 bnpl.RefundNotPostable`); a refund on a `CLOSED` obligation whose wallet
   cannot be credited is refused; a dispute lapse whose refund cannot post is counted `not_postable` and retried for ever with
   no alert and no operator path. A product decision — a refund-pending liability, an operator path, or accept.
3. **Lending's RG-M1** — the loan payoff's own billing applications still dated `today`, not floored (`Payoffs`; `-022`
   applied the floor to BNPL only). Schedule the `X-TSK` in R1's shape.
4. **Self-dealing fails open** at the operator refund door and the dispute's operator acts when the platform cannot place the
   operator in a party (it fails closed at the corrections and the investigation); the operator's dispute read has no
   self-dealing check. Align (fail closed), or accept.
5. **A contested dispute (`MERCHANT_RESPONDED`) has no deadline** — its held instalments are never collected, charged or aged,
   and every refund of the purchase is refused, until an operator acts. An age gauge and alert, an SLA sweep, or accept.
6. **A reversal after a refund can re-open a late fee OQ-14 says is not owed** — a repayment that paid a fee on an instalment a
   refund later cancelled whole, then reversed. Release it in the reversal's transaction, or accept (order-dependent, small).
7. **The replay's shared refund derivation** (found here) — schedule an independent arm (excess only when nothing was left
   unpaid, judged as of the refund's entry), or keep the battery as its only independent check until Phase 14.
8. **The schedules' claim-level failures** (found here) — one line in each `sweepQuietly` and a test; now, or Phase 15.
9. **`P12-TSK-027`'s suggestions** — a hermetic guard refusing a constraint trigger and a table constraint that share a name;
   distinct SQLSTATEs (or tagged messages) per bnpl trigger.
10. **`P12-TSK-028`'s balance-named columns** — the permitted list with a reason each and the role check (kept), or rename the
    seven columns across lending and bnpl.
11. **Capital return and income recycling** — still unowned (A29 then, BNPL's income now): assign a phase.
12. **`P12-TST-001`'s upstream catches** — demonstrate C8's latest-first and telescoping arms with a committed wrong row, or
    accept the domain constructor and `V012`'s trigger as the catching ranks.
13. **`Instant.EPOCH` stamps** (`P12-TST-002`) — `JdbcBnplRecordStore` stamps a fact without a stamp as the epoch, which
    escapes every dispute hold in the replay; fail loud (a production change), or accept.
14. **Operator-identity classification** — `CONFIDENTIAL` on the terms, reversal and waiver rows, `RESTRICTED-PII` on the
    dispute proposal's; align.
15. **The `product` metric tag's case** — upper on the application, purchase and terms series, lower on the condition and
    obligation series (Phase 11's #13 again); pick one.
16. **The delinquency bucket labels** — fixed (`B2_30_59` … `B4_90_PLUS`) while a terms version may pin another default
    threshold (30–180); constrain the terms door or derive the labels.
17. **A16's wording and A8's default** — the product bounds are `CreditProduct` constants, not configuration; the merchant fee's
    `HALF_EVEN` is a default an operator may change, and fee schedules are created by one `FEE_ADMINISTER` holder without four
    eyes, so the activation's "approved merchant-fee schedule" is procedural. Accept, or harden before activation.
18. **The settlement register's overlapping remittance shapes** (`X-TSK-018`, "record as debt, review later") — refuse at
    composition, or keep parking at hop 2.
19. **Phase 11's items still open** — #4, #6–#15, #17, #18, #20 and A5-10 (`reviews/PHASE_11_TO_12_TRANSITION.md` §9).
20. **PI-001** (`POSTPONED_ISSUES.md`) — lending's account opener without BNPL's up-front judgement; confirmed post-phase.

### Owner accepted default, review later (each built as recorded; ratify as a set)

21. **B8's defaults** — OQ-2, 3, 4, 6 and 8–20 as the draft defaulted them (`PHASE_12_PLAN.md` §24), every one built so.
22. **`-001`** — BNPL decisions automatic only, never referred; pay-in-4 affordability the three largest instalments a month.
23. **`-004`** — three added terms fields (collection retry cadence, dispute four-eyes threshold, payoff horizon); the
    conservative pay-in-N terms door.
24. **`-005`** — one outstanding figure in the operator capital reports, no loan/BNPL split.
25. **`-006`** — `HALF_EVEN` a default, not a refusal; merchant enablement reasons not screened for instrument shapes.
26. **`-009`** — merchant's `FeeCalculation.SHARE_SCALE` of 12 a double rounding, one minor unit away above about EUR 100M.
27. **`-011`** — an `OFFERED` application holds its checkout session; a declined or expired offer does not free it.
28. **`-013`** — `bnpl.MerchantFinanced` audited under the accepting customer; schedule v1 from the purchase's own business date.
29. **`-014`** — a storage fault answers `500`, not `503`.
30. **`-017`** — no interest at or after the final due date (post-maturity interest would need post-maturity billing).
31. **`-018`** — the repayment posts at the floored date and only the closure waits for T-acc (the gate's clause recorded).
32. **`-020`** — a pay-in-N instalment cancelled whole while its billed interest is owed may later carry a late fee; a released
    fee frees no cap room; a cure by closure keeps the last `DEFAULT` row; `V013`'s fee trigger admits a fee below
    `least(fee, cap room)`.
33. **`-021`** — pay-in-N accrued interest released when nothing is left to bill; the wallet never opened by a refund; the
    `AccountClosing` pending-credits port not built.
34. **`-023`/`-024`** — payoff and voluntary repayment not paused by an open dispute; a waiver or reversal on a disputed
    obligation not refused; no interest waiver; no hold on corrections; `PAYOFF` never reversed; a `CLOSED` obligation terminal.
35. **`-028`** — the problem body's `instance` echoes the requested id.
36. **`P12-TST-001`** — J1 (UTC only, dates crossed by ageing), J2 (scenario 5's wallet variant through a card pay-in), J3 (hot
    rows sampled every 20 ms); the instances' relays off; the provider report over hop 1; the A6-03 control plants under the
    owner role; two instances, the ten-way races inside scenes.
37. **The payoff execution is `MULTI_FACTOR` always** — stricter than the plan's conditional step-up.
38. **Operator idempotency keys `CUSTOMER`-typed** until operator sessions carry `EMPLOYEE` actors (A5-04, inherited).

### Resolved since they were recorded

- `-025`'s replay ordered by stamps — paid by `P12-TST-001`'s bnpl `V017` (the record's order).
- OQ-20 / B8, the three settlement suites — paid by `X-TSK-018`.
- `-027`'s finding 1, `V012`'s name collision — fixed in that task.
- The A6 lending test hardening — paid for the BNPL world by `P12-TST-001`.

## What the phase produced

Two modules (`bnpl`, `creditmath`), seventeen bnpl migrations and five neighbours', two products on lending's extracted engines
and two BNPL engines (the pay-in-4 schedule, the refund allocator), origination at checkout from application to an immutable
agreement with its evidence, the purchase-financing-obligation transaction, merchant enablement and its fee schedule, merchant
reads and the payable's drill-down, servicing (billing, the catch-up, accrual, auto-collection, delinquency and default, late
fees), repayment and its four-eyes reversal, the waiver, refunds latest first, disputes under one bound, payoff, the three
proofs and the explanation door, reports, thirty-two series and sixteen alerts, three leaderless schedules, the storm and the
battery — and, from this review, `BnplBornOnceArbitersDatabaseTest`, `BnplTerminalEdgesDatabaseTest`,
`BnplOneKeyRaceDatabaseTest`, `BnplDelinquencyStatePropertiesTest` and some thirty strengthened cases.

## What happens next

**The Phase 12 → 13 transition is the owner's to open** — this review marks nothing `READY` and initialises nothing of Phase
13. Its agenda is the owner list above: criterion 7's standing deviation asked again, closed merchants and wallets, lending's
RG-M1, the self-dealing alignment, the contested dispute's deadline, capital return, and the defaults to ratify. Phase 13
(risk, fraud and AML) inherits BNPL's condition events, the `NotAssessedUntilPhase13` seams, and the collections boundary.
