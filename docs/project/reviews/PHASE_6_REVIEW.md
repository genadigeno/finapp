# Phase 6 Exit Review — Checkout and Merchant Platform

**Conducted:** 2026-09-24 (`P6-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 6-specific criteria, read from the gate at
review time)
**Phase objective under review:** a merchant the platform does not own can sell through it —
onboarded behind KYB, priced by an immutable versioned fee schedule, paid through a checkout
session whose expiry never orphans money that landed, credited net of its fee in the same
entry that records the capture, refunded out of its own payable, and paid out to a
destination no single person can change — with every tenant's data unreachable from every
other tenant's key.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, five of them only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **12 `PASS`**, one (7) with a recorded deviation, one (10) **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited; F6 only after four race tests this review added |
| Phase 6-specific criteria | **16 `PASS`** — 6 original + 10 added by the Phase 5 → 6 transition, counted from the gate; two (the four-eyes cooling-off and the failure list's scenario 11) only after corrections |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — sixteen contended decisions, each with its arbiter and its counted race |
| **Verdict** | **Phase 6 `COMPLETE` (2026-09-24)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act** → re-run the battery → finalise with counted numbers.

**This review is the first in the programme whose assessment found more than its inputs.**
It was routed eight inputs, and one of them — the red chart test — gated its own battery. Six
read-only audits then read every ADR, register and document against the code, mapped every gate
criterion, failure scenario and contended decision to the test that proves it, and enumerated
every privileged route. They found **four defects in production behaviour, one missing database
constraint, a class of wrong operator instructions, four contended decisions no test raced, and
drift in six of the eight ADRs**. None was waived. Each was corrected here, in code or in text,
and each correction was then broken on purpose to prove its test has teeth.

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **A suspended merchant could still be paid.** The confirmation never read the merchant's standing, so an offer made while the merchant traded could be paid after its suspension. `PHASE_6_PLAN.md` §14 scenario 11 said the confirmation refused, and nothing tested it | Refused at confirmation as `checkout.NotTrading`, before anything is written; the payment admitted before a suspension still lands. Both halves tested and probed |
| **IMPORTANT** | **One session per payment intent was never enforced.** ADR-0053 §3 named a partial unique index and `V002` never built it; the capture completes its session by finding it through that column, took the first row it met, and scanned the whole table under the capture's locks | checkout `V003`, the partial unique index; the column's false comment corrected. Tested and probed |
| **IMPORTANT** | **Every paid order was announced as `PAYMENT_PENDING`.** `checkout.OrderPaid`'s `completedAs` was built from the session as read, before its transition | Announced from the moved session; both endings asserted. Probed |
| **IMPORTANT** | **A zero never-received bound was accepted.** ADR-0057 names the bound as the safety of the `NEVER_RECEIVED` conclusion, and only a negative one was refused: at zero the sweep could fail a payout the provider then pays, releasing its hold | Refused at construction, the cooling-off's rule. Tested and probed |
| **IMPORTANT** | **Five of the platform's eight confined credentials named an environment variable nothing binds** — both payout keys and all three payment keys. Off loopback the application refused to start and its message pointed at the wrong fix | The refusals and ADR-0057 name the variables that bind; `ConfinedCredentialVariablesTest` pins every pair. Probed |
| **IMPORTANT** | **Nothing pinned which permission a route requires.** Every route declares *a* rule and the interceptor refuses whoever lacks it, but moving a route to another permission the same role holds passed every test | `RoutePermissionRegisterTest` pins all 32 guarded routes, derived from the served mapping. Probed |
| **IMPORTANT** | **The checkout session's idempotency claim shared one namespace across merchants** (ADR-0004; the payout's is per merchant). Nothing leaked — the fingerprint names the merchant — but one shop's key value refused another's request | Scoped per merchant. Tested and probed. The platform-wide remainder is `X-TSK-003`, with the migration hazard that makes it its own item |
| **IMPORTANT** | **The checkout session's audit records did not name the key that acted** (ADR-0052 §2; the payout's do) | Both records name it. Tested and probed |
| **IMPORTANT** | **The red chart test was two stale copies, and the recorded fix was wrong.** `P6-TSK-003` retired "only customers own accounts" from the seed's guard but not from `ChartOfAccounts.resolve` or the database test; the fix the blocker recorded ("skip every non-`OPERATIONAL` kind") would have dropped the seeded suspense seam | Both copies take `requiresOwnerRef()`, the seed guard's own predicate; a hermetic test pins the partition. Probed, including a planted seed gap the database test could not reach before |
| **IMPORTANT** | **The four-eyes gate criterion's cooling-off "proof" read the destination; it never dispatched** (§5's four-eyes bullet) | A real payout initiated inside the window pays the prior destination. Probed: with the read admitting the change, `V007`'s insert trigger refused the payout |
| **IMPORTANT** | **Four contended decisions had no race test** — fee-schedule assignment, merchant suspension, API-key revocation, and expiry racing a capture concurrently | Four ten-way races added, each counted to one effect. Each probed by removing its arbiter: the three row locks were caught; the expiry race's conditional survived behind its lock, and both ranks broken were caught at the order's key |
| MINOR | Drift in ADR-0050, 0051, 0052, 0053, 0056 and 0057 (six of eight), in `PHASE_6_PLAN.md` (§4, §8–§12, §14, §16), `MODULE_ARCHITECTURE.md`, `DELIVERY_PLAN.md`, the lifecycles, the glossary, the invariant catalogue (`INV-MER-02`, `-03`), the event architecture, four registers, the ADR index and `DECISIONS.md` (no Phase 6 section) | Every statement made true — see area 7 |
| MINOR | Two register-decay findings: a debt row whose trigger (`P6-TSK-007`) fired unpaid, and Phase 5's refund-sweeping deferral, owned by "Phase 6", closed unpaid | Both re-owned with a trigger — area 8 |
| **IMPORTANT** *(found at this item's gate)* | **The repository's `README.md` was frozen in mid-Phase 5**, under a sentence promising every number was counted from the repository | Every number recounted |
| **IMPORTANT** *(found at this item's gate)* | **The step-up criterion was passed without its reading stated**: the step-up is conditional, so an operator with no factor acts without one | The reading stated in the criteria table; a Phase 15 debt row |

## The flip

Recording Phase 6 `COMPLETE` is the guarded act, because it changes what the build demands: the
register guard begins demanding a row for every `Phase: 6` invariant and a §4 row for every
`P6-TST` item, and the derived meter guard takes `PHASE_6_PLAN.md` §15's table over from the
pinned one. **It surfaced nothing**, because `P6-TST-002` had simulated it and landed the one
row it asked for: against the real status, `MutationDemonstrationTest` (9) and
`PlannedMetersExistTest` (8) passed. **And it was proven non-vacuous against the real status**,
not only the simulated one: with `INV-HIST-04`'s two rows removed, the guard failed reporting
`(currently 6)` and naming exactly `["INV-HIST-04"]` — an invariant it demands only once Phase 6
is complete. The register was restored byte-identical.

---

## Area 1 — Scope: what the phase set out to build, and what it built

Eighteen backlog items — the sixteen the transition planned, plus `P6-TSK-014` (added by
`P6-TSK-005`'s gate) and `P6-TSK-015` (added by `P6-TSK-010`'s) — seventeen `COMPLETE` and this
review the eighteenth, across seven milestones. **Counted from the code at review time, never
from a plan** (the `P3-DOC-001` rule, which caught four wrong numbers in Phase 5's first pass):
**2 owning modules** (`merchant`, `checkout`), **17 tables** (14 in `merchant`, 3 in `checkout`),
**14 migrations** (merchant `V001`–`V007`, checkout `V001`–`V003` — `V003` this
review's — ledger `V011`–`V012`, platform `V010`, identity `V015`), **29 HTTP operations on 25
paths** (from the published contract), **23 auditable actions** (18 merchant + 5 checkout),
**22 error codes** (16 + 6), **8 event types** (6 + 2), **7 meters**, **5 permissions and 1
role**, **8 ADRs** (ADR-0055 is cross-cutting), and **9 `Phase: 6` invariants** out of the
platform's 94.

What a merchant can now do: be onboarded behind its party's KYB approval with a payable account
per settlement currency; hold API keys shown once; open checkout sessions priced at the moment
of the offer; receive the gross of every sale and pay its fee in the same journal entry; read
its own transactions and a payable that explains itself term by term; and be paid out under the
bound to a destination two operators approved and a cooling-off protected. What a customer can
do: pay a merchant's session with their own session and the session's token. What an operator
can do: onboard, price, suspend and close merchants, issue and revoke keys, propose and approve
payout destinations, and pay a merchant out on its behalf — each with a reason where the act is
a judgement.

`PASS`.

## Area 2 — Walk one real sale end to end, and then its payout

The economic event is a customer buying 100.00 EUR from a merchant priced at 2.9% + 0.30.

1. **The offer.** `POST /v1/checkout/sessions` over the merchant's key: the merchant's
   standing, its effective fee schedule version and whether the sale covers its fee are all read
   **before** the claim (ADR-0058), the claim is scoped to the merchant, and an `OPEN` session
   commits carrying **the version it was priced under**. Nothing financial happens.
2. **The confirmation.** `POST /v1/checkout/sessions/confirmation`, the customer's own session
   and the token in the body: the session's state, its clock and the merchant's standing are
   checked, then in **one transaction** the payment intent is created (crediting the merchant's
   payable), the fee version is **pinned onto the payment** (`merchant.payment_fee_pin`), and
   the session moves `OPEN → PAYMENT_PENDING` conditionally.
3. **The payment.** Phase 5's machinery, unchanged: dispatch-before-call, the provider called
   holding no connection, authorization touching no ledger (ADR-0048).
4. **The financial transaction.** The capture's outcome transaction commits, together: the
   attempt's conditional `CAPTURED`, the journal entry under `payment-capture:<attemptId>`, the
   session's `COMPLETED` (or `COMPLETED_LATE` after expiry), the order, `merchant.FeeAssessed`
   and `checkout.OrderPaid`.
5. **Journal entry → lines** (ADR-0050 §3, asserted by account and direction, never by count):
   **DR `SETTLEMENT_CLEARING` 100.00 / CR `MERCHANT_PAYABLE` 100.00 / DR `MERCHANT_PAYABLE` 3.20 /
   CR `FEE_REVENUE` 3.20** — fee computed once, net by subtraction, so the residual is zero by
   construction (`INV-MER-04`).
6. **Balances.** The payable's position is 96.80, reproducible by replay from zero and derived,
   never stored (`INV-MER-02`); the merchant's payable view explains it term by term.
7. **The payout.** `POST /v1/merchant/payouts`: the merchant row locked, the effective
   destination share-locked, and the amount **held** on the payable under that account's lock
   against everything already held (`INV-MER-05`), committed `DISPATCHED` with our minted
   reference before the wire. Completion releases the hold and posts `merchant-payout:<payoutId>`
   — **DR `MERCHANT_PAYABLE` / CR `PAYOUT_CLEARING`**, a credit-normal liability Phase 8 will
   settle against cash.
8. **The chain is walkable by identifier** — session → order → intent → entry → payable →
   payout — with no timestamp join (`CheckoutFlowDatabaseTest#theCustomerPaysTheMerchant`,
   `MerchantPayoutDatabaseTest#aPaidPayoutReleasesAndPosts`), and the whole book reconciles to
   the minor unit under load (`P6-TST-002`).

`PASS`.

## Area 3 — Multi-instance correctness

*"Would this be correct with 10 concurrent instances?"* — answered over the phase's contended
decisions, each with its arbiter and its counted race. The review mapped every decision to its
test and found four without one; those four rows are this review's.

| Decision | Arbiter | Counted |
|---|---|---|
| The merchant-bound capture's posting | The capture's single entry under `payment-capture:<attemptId>`, the attempt's conditional `CAPTURED` | Ten-way outcome race → one entry (`MerchantCaptureDatabaseTest#theDuplicateOutcomeRacePostsOnce`) |
| One fee pin per payment | The confirmation's claim and the session's conditional move serialise the racers; the pin's primary key stands behind them, a duplicate converging and a different price refused | Ten concurrent confirmations → one order and **one pin** (the count asserted by this review; it cannot see the key, which the earlier ranks shield — `MUTATION_TESTING.md` §3) |
| Ten creations of one session | The claim's `(scope, key)` key, the scope naming the merchant since this review | Ten-way → one session, one token |
| Ten confirmations of one session | The claim `checkout:<sessionId>` and the session's conditional `OPEN → PAYMENT_PENDING` | Ten-way → one intent, one order |
| **Expiry racing a capture** | The session row lock and conditional transitions on both sides | Five sweeps and five completions → one order, whichever wins, each edge once (**this review's**) |
| One order per session | `UNIQUE (session_ref)` under the session lock | Ten-way → one order |
| One session per intent | checkout `V003`'s partial unique index (this review's) | Constraint driven: a second session refused `23505` |
| Refunds and a capture on one payable | The payable account's lock; the refund's hold sized by its composition | Racing refunds stay inside the bound; the storm's ten movers |
| Ten payouts on one payable | Merchant row `FOR UPDATE`, destination `FOR SHARE`, the hold under the payable's lock | Ten-way → exactly the affordable set (four of ten) |
| A payout's outcome, its send permit, its resolution | The payout row lock, the conditional renewal, the conditional transitions | Ten sweeps → one outcome; a renewal racing the sweep and a takeover racing the verdict, each driven deterministically |
| One open destination change; one approval; one effect | Two partial unique indexes; the row lock with `proposed_by <> ?` | Ten proposals → one open; ten approvers → one approval; ten sweeps → one effective row |
| Ten fee-schedule versions | `UNIQUE (schedule, version)` with a retry | Ten-way → ten distinct numbers |
| **Ten assignments of one schedule** | The assignment row `FOR UPDATE` and the converging re-assignment | Ten-way → one act, ten `200`s (**this review's**) |
| **Ten revocations of one key** | The key row lock and the conditional `ACTIVE → REVOKED` | Ten-way → one revocation, ten `204`s (**this review's**) |
| **Ten suspensions of one merchant** | The merchant row lock and the conditional transition | Ten-way → one move, ten `200`s (**this review's**) |
| Ten onboardings with one key | The claim, then the payable's `UNIQUE (owner, purpose, currency)` | Ten-way → one merchant, one payable |

Nothing in the phase rests on one JVM: every sweeper is leaderless (checkout expiry, payout
resolution, destination effectuation — each exempted in `NoSingleInstanceAssumptionRulesTest`
with its reason, and each registered in `DISTRIBUTED_EXECUTION.md` §3), every per-instance
reading is a gauge aggregated with `max()`, and the whole book was driven by ten movers
contending in the database (`P6-TST-002`).

`PASS`.

## Area 4 — Failure behaviour: two traced through the code

**A payout provider times out.** The adapter's timeout is an honest `UNKNOWN`: the outcome
transaction commits it with the hold **standing**, and nothing is posted. A client retry with
the same key converges on the committed dispatch and re-sends **our stored reference** behind a
fresh send permit, which the provider cannot pay twice; the resolution sweep, leaderless,
queries that reference and lands the answer through the same conditional transitions every
resolver shares. It concludes `NEVER_RECEIVED` only past a **positive** bound, re-judged under
the row lock — so "never received" can never be followed by a send (ADR-0057 §4).
`MerchantPayoutDatabaseTest#aTimeoutIsResolvedByTheSweep` and
`MerchantPayoutEndpointDatabaseTest#aTimeoutIsHonest` drive it; the bound's floor is this
review's.

**A capture lands after the offer expired.** The sweeper expires the `PAYMENT_PENDING` session
by its conditional transition; the provider's late answer reaches the capture's outcome
transaction, which finds the session through its intent — now **unique**, checkout `V003` — and
completes it `EXPIRED → COMPLETED_LATE`: the four lines post, the merchant is credited 96.80,
the order is created, the edge is metered, and the event says `COMPLETED_LATE`, which it did not
before this review (`CheckoutFlowDatabaseTest#aCaptureLandingAfterExpiryStillProducesTheOrder`,
`INV-MER-06`).

`PASS`.

## Area 5 — Security and audit

Every one of the phase's 29 operations was enumerated with its caller, its rule, its audit
action and its negative test. Three populations, disjoint by type: **operators** by session and
a named permission (20 operations), **merchants** by an API key that carries no operator
permission and no customer capability (8 operations, all derived or tenant-scoped), and the
**customer** by their own session *plus* the checkout token (the confirmation).

- **Which permission guards which route is now pinned** — the review's own finding.
  `EveryEndpointDeclaresARuleTest` proved every route declares a rule and
  `DenyByDefaultDatabaseTest` proved the interceptor refuses and audits whoever lacks it; nothing
  proved the declaration was the right one, so moving the fee-version route to
  `MERCHANT_ADMINISTER` — which the same role holds — passed every test.
  `RoutePermissionRegisterTest` pins all 32 guarded routes platform-wide, derived from the served
  mapping; the two halves now compose into a negative test per route. The tenancy battery's table
  had also cited, for seven of its eleven operator rows, negative tests that did not exist; its
  permission column now rests on the register.
- **Tenancy is in every statement** (`INV-MER-01`) — the store review (`OwnershipIsScopedTest`,
  widened in the tenant's packages by `P6-TST-001`), the battery's one-refusal-and-zero-rows
  probes over the 23 merchant routes, and, from this review, **the idempotency claim**: the
  session's is scoped per merchant, as the payout's always was.
- **Four-eyes on the destination** (`INV-AUD-04`'s second subject): proposer ≠ approver at the
  aggregate, in the approval statement and by `CHECK`; the refused self-approval commits its own
  `DENIED` record; the step-up is conditional on an enrolled factor on both sides; the
  cooling-off is now proven by a **real dispatch** inside the window.
- **The key that acted is named** on every record a merchant command writes (ADR-0052 §2), the
  session's two records since this review. The ledger's `HoldPlaced` under a merchant's payout
  names the merchant and shares the command's correlation, one join from the key — stated in the
  ADR rather than taught to the ledger.
- **Secrets**: API keys hashed and shown once; checkout tokens hashed and never persisted, not even
  in the idempotency claim that replays them; bank details never enter (an opaque provider
  reference and a four-character suffix); provider evidence encrypted under its own key — whose
  variable, like four others, is now the one the configuration binds.
- **Known and recorded, not new**: every session actor is audited as `CUSTOMER`, operators
  included (the Phase 15 row); a refused step-up is answered `403 AssuranceRequired` and not
  audited (added to area 8).

`PASS` — after the permission register, the claim's scope and the key naming.

## Area 6 — Invariants, the register, and the demonstrations

Nine `Phase: 6` invariants, read from the **catalogue**: `INV-MER-01`…`07`, `INV-HIST-04`
(fees) and `INV-AUD-04` (the destination, its second subject). Every one carries rows in
`MUTATION_TESTING.md` §2 — the flip simulated at `P6-TST-002` and performed here — and both
`P6-TST` items carry §4 rows. `INV-HIST-04`'s single demonstrated row is now answered by a
performed mutation (`P6-TST-002`'s capture priced by the version in force).

The review's own corrections were held to the same standard: **eighteen probes,
seventeen caught, every restore verified byte-identical** — each correction broken on
purpose, with its test run fresh in a Gradle invocation of its own:

| # | The correction, broken | Caught by | Observed |
|---|---|---|---|
| R1 | The chart's guard back to `== CUSTOMER` | `ChartOfAccountsTest`, `OperationalChartDatabaseTest#anOwnedPurposeIsRefused` | A merchant payable asked of the chart answered as a missing seed — caught in the hermetic tier as well as the database one |
| R2 | `PAYOUT_CLEARING` unseeded in GBP | `OperationalChartMigrationTest` (two tests), `OperationalChartDatabaseTest#everyCombinationResolves` | The database test now reaches a purpose after `MERCHANT_PAYABLE`, which it could not since `P6-TSK-003` |
| R3 | The session's claim back under the constant scope | `CheckoutFlowDatabaseTest#oneKeyValueAcrossTwoMerchantsOpensTwoSessions` | The second merchant answered `409 api.Conflict` |
| R4 | The creation record without its key | `CheckoutFlowDatabaseTest#theSessionsAuditRecordsNameTheKey` | At the creation assertion |
| R5 | The withdrawal record without its key | The same test | At the withdrawal assertion, the creation's key in place |
| R6 | `OrderPaid` built from the row as read | Three `CheckoutFlowDatabaseTest` tests: the whole flow, the late capture, the concurrent race | Every ending announced as the state it left |
| R7 | checkout `V003` building no index | `CheckoutSessionDatabaseTest#anIntentBelongsToOneSession` | The second session took the intent |
| R8 | The fee-version route declared `MERCHANT_ADMINISTER` | `RoutePermissionRegisterTest` | Named the route; before the register this passed everything |
| R9 | A zero never-received bound accepted | `MerchantPayoutResolutionScheduleTest#aZeroDispatchedBoundIsRefused` | — |
| R10 | `PayoutEvidenceKey` naming `FINAPP_PAYOUT_EVIDENCE_KEY` again | `ConfinedCredentialVariablesTest` | The variable that does not bind, named |
| R11 | The confirmation not asking the merchant's standing | `CheckoutFlowDatabaseTest#aSuspendedMerchantsOpenSessionCannotBePaid` | The suspended merchant's offer was paid |
| R12 | The capture's completion refusing a suspended merchant | `CheckoutFlowDatabaseTest#aPaymentInFlightAtSuspensionStillLands` | Landed money refused its order — the negative half of scenario 11, pinned |
| R13 | The dispatch's read admitting the cooling-off change | `MerchantPayoutDatabaseTest#aPayoutDuringTheCoolingOffGoesToThePriorDestination` (and the supersession race) | By the rank below the read: `V007`'s insert trigger, `23514` |
| R14 | The assignment without the merchant row lock | `FeeScheduleDatabaseTest#tenConcurrentAssignmentsMakeOneAct` | Losers answered `500` |
| R15 | The suspension without the merchant row lock | `MerchantEndpointDatabaseTest#tenConcurrentSuspensionsMakeOneMove` | Losers answered `500` |
| R16 | The revocation without the key row lock | `MerchantApiKeyDatabaseTest#tenConcurrentRevocationsMakeOne` | Losers answered `500` |
| R17 | The session's transition unconditional | — | **SURVIVED, correctly**: both writers lock the row and judge under it; the conditional is the second rank |
| R17b | That, and both of the session's row locks removed | `CheckoutFlowDatabaseTest#anExpiryRacingTheCompletionLeavesOneOrder` | A second order refused by `UNIQUE (session_ref)`, `23505` — the third rank |

`PASS`.

## Area 7 — Documentation accuracy

Diffed against the implementation, document by document, by an audit that read the code
rather than the plans:

- **The ADRs**: six of eight had drifted — see *ADR-0050 … 0058: accepted*.
- **`PHASE_6_PLAN.md`**: §2 and §6 (four-eyes' *second* subject), §3 and §18 (no port: `app` orchestrates), §4 and §9 (the real commands and routes, the permissions named, the OpenAPI baseline as the authoritative list), §7 (no payout webhook; three sweeps; the version chosen when the session opens), §8 (`payment_fee_pin`, `V003`), §10 (the event list and the amounts), §11–§13 (the permissions, the key naming's scope, the payable's refund terms), §14 (renumbered 1–16, scenario 2's crash window, scenario 11 enforced) and §16 (the milestones' late additions).
- **`MODULE_ARCHITECTURE.md`, `DELIVERY_PLAN.md`, `CHECKOUT_MERCHANT_LIFECYCLES.md`,
  `GLOSSARY.md`, `EVENT_ARCHITECTURE.md`, `FINANCIAL_INVARIANTS.md`**: the merchant and checkout entries' state, routes, events, metrics and seams; a superseded note on the delivery plan's Phase 6 section; the session machine's `ABANDONED` (the merchant's alone) and the merchant's `CLOSED` (from `ACTIVE` only); no payout cycle, and `MerchantSettlement` named for what it is; fee versions immutable from creation; `INV-MER-02`'s refund terms and `INV-MER-03`'s nonexistent assessment rows; and the amount wire format the fee events had used unrecorded.
- **The registers**: `DISTRIBUTED_EXECUTION.md` §3 was missing two components (the merchant API
  key, `MerchantPayoutMetrics`), a cell, and the names of two sweep schedules — the
  register-decay class, again. The error contract, auditable actions and data classification
  agreed with the code row by row, but the first two filed the merchant and checkout rows inside
  the payments table, so payments' prose read as theirs; each now has its own sections. The
  classification register's scope sentence named three of its twelve schemas.
- **`DECISIONS.md`** had no Phase 6 section — the omission `P2-DOC-001` found once before — and
  now indexes ADR-0050…0054 and ADR-0056…0058.
- **`PHASE_GATES.md`** §5 still called the destination `INV-AUD-04`'s *first* subject; corrected
  in its parenthesis, the criterion unchanged. Its payable formula (*captured − fees − refunds −
  payouts*) omits the fee a `RETURNED` refund gives back; the criterion was judged by the storm's
  full arithmetic, and the gate's words were left as it wrote them.

`PASS` — after the corrections.

## Area 8 — Debt, and what is deliberately deferred

| Item | Owner |
|---|---|
| No fleet-wide `databaseTest`/`kafkaTest` run for the phase, by the owner's standing instruction. **Its cost was measured again**: `X-TSK-001`'s full run found `OperationalChartDatabaseTest` red since `P6-TSK-003`, invisible to every targeted tier for eleven tasks | The Phase 6 → 7 transition, which ran the battery last time; meanwhile the fleet-wide hermetic tier at every gate, and this review's hermetic twin of the chart test |
| `X-TSK-001` (Lombok) is `BLOCKED` now only on a fresh full database run — its one failing test is fixed here | The owner, who left it (2026-09-23); unblocked by the same run |
| Every other constant idempotency scope (ADR-0004), with the migration hazard that a retry spanning the change runs twice | `X-TSK-003` |
| Phase 5's stuck-payment gauges count only `*_UNKNOWN`, so a crashed dispatch is invisible whenever the sweep is down; **and** refund resolution by query, which Phase 5 deferred to "Phase 6 or a sweeper extension" and Phase 6 did not build. The payout's gauge and sweep are the shape both should take | Phase 7 (payments' next phase; the Phase 6 → 7 transition schedules it) |
| `payment_intent.wallet_account_id` names a merchant payable a wallet — its trigger (`P6-TSK-007`) fired and the rename was not made | Phase 7: the next migration that must recreate the intent's every-writer trigger anyway |
| The ledger-level fee batch runs only two-minor-unit currencies, because the chart holds no others | Phase 9 (the first 0- and 3-minor-unit currencies) |
| The conservation storm drives ten movers in one JVM; its prices only rise | Phase 16 (performance testing with invariant assertion); a falling price exercises no path a rising one does not |
| A refused step-up is not audited (`403 AssuranceRequired`), on the destination as on the Phase 4 surfaces | Phase 15 (audit completeness) |
| The dispatch's share-locked read can see neither destination for one statement when the effectuation sweep holds the old row — a fail-safe `NoEffectiveDestination` refusal, found by reading, not driven | ADR-0056 §5 states it; a lock-choreographed test when the dispatch is next changed |
| An intent stranded undispatched by a crash can be sent after the offer's deadline; if it captures, landed money wins (`COMPLETED_LATE`) | Accepted — ADR-0053 §5 states it |
| A failed payment is not retried within its session; the session expires and the customer opens another | ADR-0053 §3; attempts-per-intent, left at one by Phase 5, is where retry belongs |
| `checkout.CheckoutSessionCreated` was declared as an event and never built | Struck: the creation is audited, and an event arrives with its consumer |
| The conditional step-up in three services | Unchanged; the fourth caller extracts it |
| **The step-up is conditional**: an operator with no enrolled factor proposes and approves a payout destination without one — the gate's "requires step-up" read as the platform's step-up, found at the gate. Two distinct operators, the 72-hour cancellable cooling-off and the audit trail stand regardless | Phase 15 (privileged access review: a mandatory factor for privileged roles) |

`PASS` — everything above is recorded with an owner rather than carried silently.

---

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — onboarding, keys, fee schedules, sessions, confirmation, fee assessment at capture, refunds out of the payable, the payable view, the transaction report, destinations, payouts and the meters, all exercisable end to end over real HTTP |
| 2 | Architectural boundaries respected | `PASS` — `checkout` depends on `platform` alone and `merchant` on `ledger` and `platform`, both isolation suites green; no undeclared dependency |
| 3 | Required invariants tested | `PASS` — nine `Phase: 6` invariants, every one with a register row, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — **all sixteen** of `PHASE_6_PLAN.md` **§14** (the gate's generic wording says "§12"; this plan numbers its failure list §14, as Phase 5's did, and two items there were both numbered 13 until this review renumbered them). Fifteen have a test in this phase; scenario 16 (a crash between the payout's outcome commit and its event) rests on the outbox's at-least-once machinery, the facts written in the committing transaction. **Scenario 11 had neither test nor behaviour** until this review; **scenario 2** is stated with its crash window; **scenario 15** (a dispatch during the cooling-off) is now proven by a real dispatch |
| 5 | Security requirements implemented | `PASS` — see area 5, after the permission register, the claim's scope and the key naming |
| 6 | Observability exists | `PASS` — seven meters from a freshly started instance; the stuck-payout gauges alertable, `NaN` never zero, and read against the real schema by the running instance; correlation propagated |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — see below |
| 8 | Documentation reflects reality | `PASS` — see area 7, after the corrections |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's own finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0050…0054 and ADR-0056…0058, each read against the code and corrected before any was accepted. ADR-0055 is cross-cutting (`X-TSK-001`) and its own text reserves its acceptance to the owner |
| 11 | No unresolved critical issues | `PASS` — blockers: none left; nothing in area 8 is `critical` or `high` |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction skips
`build databaseTest kafkaTest`, and every item of the phase was verified by targeted tiers and
recorded in those words. This review does not resolve that by assertion:

- The **hermetic** tier was run **fleet-wide after the flip** — **1550 tests across 14 modules, 0 failures** — which is where both
  guards the flip arms live, and where this review put a hermetic twin of the chart test that
  the targeted database tiers had missed.
- The **database** tier was run over **224 tests across 23 suites, 0 failures** — every merchant and checkout suite, the chart suite
  that was red, the trial balance and the payment conservation storm. The one fleet-wide database
  failure on record (`X-TSK-001`'s run) is fixed and its suite green. **No fleet-wide database or
  kafka count is claimed for Phase 6.**

Assessed **`PASS` with the deviation recorded** rather than waived, and its cost stated: a
database test sat red for eleven tasks because no targeted tier included it.

## The financial supplement F1–F8 — re-assessed at the gate

| # | Criterion | Verdict |
|---|---|---|
| F1 | Trial balance zero per currency | `Met` — every sweep round of `P6-TST-002`'s storm and at its end, and across the 360-assessment fee batch |
| F2 | Balances reproducible by replay from zero | `Met` — each payable's projection against replay under the storm; `PAYOUT_CLEARING` and `FEE_REVENUE` reconciled by their lines over the storm's own entries |
| F3 | Money-moving commands idempotent at the financial boundary | `Met` — the session's claim (per merchant since this review), the confirmation's `checkout:<sessionId>`, the payout's per-merchant claim with `UNIQUE (merchant_id, dispatch_key)` behind it, and the postings' own claims |
| F4 | Reversal/compensation implemented, no path mutates history | `Met` — the merchant refund reverses the capture's shape per the pinned refund policy; pins, versions, orders and histories are immutable by trigger and grant; a payout's return is Phase 8's |
| F5 | Duplicate external delivery produces no second effect | `Met` — ten-way capture outcomes post once; ten sweeps leave one payout outcome; the payout webhook is deferred and recorded |
| F6 | Concurrency tests for every contended financial resource | `Met` — area 3, **after the four races this review added** |
| F7 | No floating point in a monetary path | `Met` — statically verified; Phase 6's only exemptions are two metrics classes publishing counts and ages |
| F8 | Reconciliation implemented or deferred with a named owner | `Met` — the payable reconciles term by term to the journal; `PAYOUT_CLEARING` against cash is Phase 8's, named in ADR-0057 |

## The Phase 6-specific criteria — sixteen

Counted from `PHASE_GATES.md` §5 at review time: **6 original + 10 added by the Phase 5 → 6
transition = 16**, every one `PASS`:

| Criterion | Evidence |
|---|---|
| Cross-merchant access impossible, negatively tested per endpoint | The tenancy battery over the 23 merchant routes, one refusal and zero rows; now also the claim's namespace |
| Payable derived from postings | No payable column in any schema; the payable reconciles to the journal |
| Fee rounding balanced across a high-volume batch | 360 assessments, 3 currencies, 6 rounding policies, residual zero |
| Late completion deterministic | `EXPIRED → COMPLETED_LATE`, merchant credited, order created, counted; **now also raced concurrently** |
| Destination change: step-up, four-eyes, audited | The four-eyes flow over HTTP, audited on both acts and on the refused self-approval. **Step-up read as the platform's conditional one** (`P4-TSK-007`, ADR-0056 §3): demanded of proposer and approver whenever they have an enrolled factor, so an operator with none acts without one — four-eyes and the cancellable cooling-off stand regardless, and a mandatory factor for privileged roles is Phase 15's (area 8) |
| Fee version pinned and recorded | The pin, immutable; a second pin at another price refused |
| The merchant-bound capture is one entry | `DIRECTION:PURPOSE` assertions; fee + net = capture across the batch |
| The payable reconciles as arithmetic under the storm | `P6-TST-002`, in one snapshot per round |
| The payout bound holds under a ten-way race | Exactly the affordable set |
| Payout ambiguity is the standing hold | Timeout → `UNKNOWN`, hold standing, the sweep resolves; one wire operation with the stored reference |
| The expiry-vs-capture race driven both ways | Both orderings, and **concurrently since this review** |
| Tenancy in the statement | The store review, the tenant-column guard, the battery's fingerprints; the claim's scope since this review |
| The four-eyes primitive is real | Proposer ≠ approver in the statement and by `CHECK`; the cooling-off **proven by a real dispatch** since this review (it was a read) |
| Fee determinism reproducible | Recomputation under the pinned version; a mid-flight version prices nothing already offered |
| Meters from a fresh instance; stuck-payout age alertable; the chain traceable | Seven meters; `NaN` never zero; session → order → intent → entry → payable → payout by identifier |
| Every `Phase: 6` invariant has a register row | Demanded by the guard after the flip, and proven non-vacuous — see *The flip* |

## ADR-0050 … ADR-0054, ADR-0056 … ADR-0058: accepted

Read against the implementation, corrected where they had drifted, and then moved from
`Proposed` to `Accepted` — none accepted before its corrections landed:

| ADR | Verdict at the reading | What was corrected |
|---|---|---|
| 0050 — the fee model | Drifted, text | A zero fee posts two lines; the version is chosen when the session opens (ADR-0058), not "at dispatch"; the payable's arithmetic gains its refund terms |
| 0051 — payout accounting | Drifted, one sentence | "Currently effective" binds the dispatch, not the takeover's re-send |
| 0052 — merchant API identity | Drifted, and the code short of it | Rotation is two operator acts; no pepper, and why; the ledger's `HoldPlaced` reaches the key by correlation. **Code fixed**: the session's records name the key, and its claim is per merchant |
| 0053 — session and order | Drifted most, and the code short of it | No retry within a session; the real machine; the expiry gate governs new work, with its crash window stated; no port, `app` orchestrates. **Code fixed**: the partial unique index it named (checkout `V003`), and the suspended merchant's confirmation |
| 0054 — refund funded by its net | Describes the code | One phrase dated: "which nothing refuses today" had been decided by ADR-0058 |
| 0056 — destination four-eyes | Drifted, two passages | The dispatch reads `FOR SHARE`, and can see neither destination once, fail-safe |
| 0057 — dispatch and resolution | Drifted, and the code short of it | **Code fixed**: its key variables are the ones the configuration binds, and its bound has a floor |
| 0058 — a sale must cover its fee | Describes the code | — |

## The inputs routed to this review, decided

| Input | Decided |
|---|---|
| `X-TSK-001`'s red chart test | Fixed in both stale copies, with the seed guard's own predicate — the recorded fix would have dropped a seeded account from the test |
| `DECISIONS.md` has no Phase 6 section | Written |
| ADR-0052 §2: the session's audit records do not name the key | The ADR's side: the code now names it |
| Every idempotency scope is a constant | ADR-0004's side: the session's claim fixed here, the rest `X-TSK-003` |
| `PHASE_6_PLAN.md` §10 "never amounts" vs the fee events | The code's side: the fee events carry amounts deliberately; the wire format is recorded in `EVENT_ARCHITECTURE.md` and §10 corrected |
| Phase 5's stuck-payment gauges | The payout's shape is right; the change is Phase 5's component and lands with refund resolution by query, Phase 7 |
| `checkout.NotPriceable`'s title | Retitled — a compatible contract change, the code unchanged, before any client exists; `NotTrading` retitled with it, for the confirmation's new refusal |
| The two-minor-unit fee batch | Phase 9 |
| `INV-HIST-04` demonstrated rather than mutated | Answered by `P6-TST-002`'s performed mutation |
| The storm's one JVM and rising prices | Accepted; multi-JVM driving is Phase 16's |

## What the phase produced

A merchant the platform does not own can now sell through it and be paid, with the books
explaining every minor unit of what it is owed: the gross recorded, the fee taken in the same
entry, refunds funded by the net, payouts bounded by what the ledger says and never by a stored
number, and no tenant able to see another. The mechanisms — tenancy in the statement, the pinned
price, the single capture entry, hold-then-dispatch with a send permit, four-eyes with a
cancellable cooling-off — are what Phase 7's rails and Phase 12's merchant financing will build
on.

## What happens next

Phase 7 — Cards, Wallets, A2A and Instant Payments — behind its own entry gate. The Phase 6 → 7
transition is the next act, and it inherits three things from this review: the fleet-wide
battery this phase's instruction skipped, `X-TSK-003`, and the payments work area 8 routes to
Phase 7 (refund resolution by query and the stuck-dispatch gauges). This review's only claim
about it is that nothing in area 8 blocks it.
