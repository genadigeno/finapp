# Phase 7 Exit Review — Cards, Wallets, A2A and Instant Payments

**Conducted:** 2026-09-28 (`P7-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 7-specific criteria, read from the gate at
review time)
**Phase objective under review:** money moves on more than one rail, and the platform knows which
— a card rail that authorizes, captures, voids and is charged back; an instant credit-transfer
rail that is final on acceptance and settled on the scheme's cycle; and the platform's own books
as a third rail for wallet payments — each rail's semantics declared rather than assumed, every
payment routed once by a pinned policy, every external rail's money in flight on its own clearing
position, and a chargeback never taking more from the payment's counterparty than the capture
credited it.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, six of them only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **12 `PASS`**, one (7) with a recorded deviation, one (10) **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited; F5 and F6 only after the races and duplicate-delivery tests this review added |
| Phase 7-specific criteria | **21 `PASS`** — 5 original + 16 added by the Phase 6 → 7 transition, counted from the gate; the Testing bullet's full-battery clause as a recorded deviation, and four read against the code as stated below |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every contended decision in `PHASE_7_PLAN.md` §7 with its PostgreSQL arbiter and its test, three raced for the first time here |
| **Verdict** | **Phase 7 `COMPLETE` (2026-09-28)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act** → re-run the battery → finalise with counted numbers.

**The assessment was six read-only audits, and it found more than it was routed.** The review
was routed four inputs by `P7-TST-002`. The audits then read ADR-0059…0062 against the code,
mapped every gate criterion, contended decision and failure scenario to the test that proves it,
enumerated every Phase 7 route with its permission and its negative test, diffed every document
against the implementation, and walked every debt row, deferral and register. They found **one
posting that named a clearing position instead of reading its rail's declaration, a stored
"descriptor" that was only a version number while every resolver read the running build, bank
details printable by three port records, a lock order that held only by an unstated property of
identifiers, a debt row whose owning task closed without paying it, nineteen gate claims with a
partial or missing test, and drift in all four ADRs**. None was waived. Each was corrected here,
in code or in text, and every correction and every new test was then broken on purpose to prove
it has teeth — **twenty-four probe runs over twenty-three breaks, twenty-three caught at the first run, and the one survivor a test weaker than its name — tightened and caught**.

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **A withdrawal's completion posted to a clearing position it NAMED** — `WithdrawalOutcomes` wrote `AccountPurpose.INSTANT_CLEARING` where every payment-side posting reads the stored rail's declared `clearingPurpose()` (ADR-0059 §1: "the domain consults the descriptor before it acts"; `INV-RAIL-01`, `INV-RAIL-04`). Behaviour was equal today, because the instant rail declares exactly that position; a second push rail would have posted into the first's | The purpose resolved from the withdrawal's stored rail; `RailVocabularyIsConfinedTest#clearingPositionsAreNamedOnlyByTheirDeclarations` makes a clearing purpose nameable only by its rail's declaration anywhere in payments' main code. Probed (D1) |
| **IMPORTANT** | **"The descriptor is recorded with every routing decision" was one integer.** Only `descriptor_version` is stored, and the clearing account, the refund mode and the void gate all re-derive the capabilities from the running build — so a declaration change would re-read in-flight and historical payments under the new semantics, with nothing comparing versions | **Ruled: a rail's money semantics are frozen per `RailId`** — a change to its model, finality, reversals, refund mode, settlement, disputes or clearing position is a new rail. `RailMoneySemanticsArePinnedTest` freezes each rail's tuple and declaration version. ADR-0059 §1 corrected. Probed (D7) |
| **IMPORTANT** | **Bank details were printable past the `paymentmethods` boundary.** `PushRail.GrantExchange` carried the grant as a bare `String`, and `CreditTransfer` and `ExchangeAnswer` carried the customer's destination — all records whose generated `toString` printed them. Nothing logged them; the guarantee ADR-0062 states ("wrapped, their `expose()` sites registered") ended one module early, and one code comment claimed a re-wrapping that did not exist | Each record redacts the grant, destination, suffix and amount — the payout port's precedent — held by `PushRailRecordsRedactTest`; the false comment corrected. `INV-RAIL-03`'s needle now runs through a pay-in and a withdrawal with the log output captured. Probed (D2, D3, D4, D16) |
| **IMPORTANT** | **A lock order held only by an unstated property of identifiers** (`P7-TST-002`'s routed input). The projection sorts account rows within an entry; a chargeback stage posts two entries; its order agrees with a single-entry capture on the same counterparty only because every seeded chart account's UUIDv7 sorts before every runtime account's | **Confirmed as a rule and guarded**: seeded chart ids are chosen below a pinned ceiling, never minted fresh — `OperationalChartMigrationTest#everySeededIdSortsBeforeEveryRuntimeId`, which also compares a freshly minted id. Written into `DISTRIBUTED_EXECUTION.md` §3. Probed (D8) |
| **IMPORTANT** | **A debt row decayed with its owner.** "Three interleavings on the confirmation have no race test" was owned by `P7-TST-001`, whose storm drove none of them, and it closed unpaid | Paid here, each race deterministic with its loser observed waiting on the lock: cancel vs confirm both ways, a detach landing mid-confirmation and before it, a merchant's withdrawal of the offer holding the session row plus six raced rounds. Probed (D10–D13) — **D10 and D11 caught only by the race**: the sequential window test ran beside them and passed |
| **IMPORTANT** | **Nineteen gate claims had a partial or missing test** (the audit's A3 map): no failure injection for "a stage, its posting and its history commit together"; recomputation only in memory; no concurrent sweeps for withdrawals, initiations or returns; no identical-event-id instant callbacks and the instant door's inbox asserted nowhere; `INV-RAIL-03`'s needle through the registration alone and no log capture; `INV-PAY-02`'s sweep over one table; §14.14 only on the card rail; the wallet statement reconciled only over Phase 3's synthetic postings; the withdrawal's takeover path never driven; the operator's evidence upload, evidence read and representment with no negative of their own; the routing explanation's success path and its audit record, and the routing refusal's audit record, asserted nowhere; the decision's Tx1 co-commit unasserted; availability changing after a decision undemonstrated; the void on an instant payment unasserted for writes; no identifier-to-identifier chain walk; acting-only audits uncounted under duplicates | Every one closed by a test in the suite that owns the flow — see area 3 and the probe table in area 6. Probed where a single break reaches the test (D9, D14–D23); the rest recorded with their reason |
| **IMPORTANT** | **"Reserves are recorded as a risk with an owner" was not true** (ADR-0061). Merchant debt from chargebacks has no reserve, and a customer's receivable has no collection; neither was in `CURRENT_STATE.md` or `DECISIONS.md` | A Known Architectural Debt row owned by Phase 13, triggered by the negative-position gauge reading above zero in operation; dispute-fee pass-through a Deliberately Deferred row |
| MINOR | The operator's void reason was enforced only by request validation — the domain accepted an unreasoned operator void (the dispute acts already refuse one) | The domain refuses it. Probed (D5) |
| MINOR | The routing-version handler was the one keyed handler without `@RequiresIdempotencyKey`: a missing key met Spring's generic 400, and the key's charset went unchecked | Annotated; the refusal and nothing-written asserted. Probed (D6) |
| MINOR | Drift in all four ADRs (ADR-0059 nine passages, 0060 ten, 0061 seven, 0062 eight), in `RAIL_AND_DISPUTE_LIFECYCLES.md`, `PHASE_7_PLAN.md` (§§2, 4, 5, 7, 8, 9, 10, 12, 14, 15, 18), `MODULE_ARCHITECTURE.md`, `DISTRIBUTED_EXECUTION.md` §3, the glossary and domain model, `DELIVERY_PLAN.md`, the error contract, the data classification, the ADR index, `DECISIONS.md`, four invariants' Verify lines and five javadoc comments | Every statement made true — see area 7 |
| MINOR | Register decay: the conditional step-up's row said three copies while five exist (its trigger fired twice in Phase 7, unpaid); the idempotency-scope and sweep rows narrower than the code; a struck blocker left standing; two push-attempt states with no producer; flake fixes stranded on six unmerged commits | Each corrected, re-owned or recorded — area 8 |

## The flip

Recording Phase 7 `COMPLETE` is the guarded act, because it changes what the build demands: the
register guard begins demanding a row for every `Phase: 7` invariant and a §4 row for every
`P7-TST` item. **It surfaced nothing**: every one of the ten invariants already carried rows
(`INV-HIST-04` 4, `INV-REV-03` 3, `INV-SET-01` 2, `INV-RAIL-01` 9, `INV-RAIL-02` 6,
`INV-RAIL-03` 8, `INV-RAIL-04` 5, `INV-DSP-01` 19, `INV-DSP-02` 13, `INV-DSP-03` 7), both
`P7-TST` items their §4 rows, and `PlannedMetersExistTest` had read `PHASE_7_PLAN.md` §15 ahead
of the flip since `P7-TSK-015` — its table now five rows longer, each series published by a fresh
instance. Against the real status `MutationDemonstrationTest` (9) and `PlannedMetersExistTest` (9)
passed. **And it was proven non-vacuous against the real status**: with `INV-SET-01`'s two rows
removed, the guard failed reporting `(currently 7)` and naming exactly `["INV-SET-01"]` — an
invariant it demands only once Phase 7 is complete. The register was restored byte-identical.

---

## Area 1 — Scope: what the phase set out to build, and what it built

Eighteen backlog items, all planned by the transition — seventeen `COMPLETE` and this review the
eighteenth, across eight milestones. **Counted from the code at review time, never from a plan**:
**3 owning modules touched for state** (`payments`, `paymentmethods`, `ledger`; `app` composes),
**15 tables** (all in `payments`: routing's six, the withdrawal and its history, the clearing
record, the unmatched confirmation, the dispute and its history, the dispute response and its
history, the dispute evidence), **15 migrations** (payments `V011`–`V022`, ledger `V013`–`V014`,
paymentmethods `V003`), **21 HTTP operations on 21 new paths** (92 paths and 107 operations in the
published contract, from 71 and 86), **18 auditable actions** (77 → 95), **16 error codes** (70 → 86),
**11 event types**, **15 meters** in the plan's §15, **2 permissions and no role**, **3 confined
credentials** (the scheme key, its webhook key, the dispute-evidence key), **4 ADRs**, and **10
`Phase: 7` invariants** out of the platform's 101.

What a customer can now do: pay by card as before, and also by bank — handed to their own PSP to
authorize, the platform waiting for that PSP's word and never its own clock — or from their
wallet in one transaction; register a bank account without the platform ever holding its number;
withdraw to it, final on the scheme's acceptance; cancel an authorized card payment and see the
promise released. What a merchant can do: be paid on either external rail and from wallets, see
its disputes, and answer a chargeback with encrypted evidence or accept it. What an operator can
do: version the routing policy, take a rail out of service with a reason, read why any payment
went where it went, void or refund on a customer's behalf, answer a dispute for a payment with no
merchant, and read the chargeback ratio per merchant. What the network can do: dispute a card
sale at any stage, and the platform posts exactly what each stage owes.

`PASS`.

## Area 2 — Walk one real withdrawal end to end, and then one chargeback

**A withdrawal of 2.00 USD** from a wallet holding 5.00, to a bank account registered through the
grant exchange. *Tx1*, keyed per customer (`payments.withdrawal:<customer>`): the claim; the
instrument resolved as the customer's own `BANK_ACCOUNT` with its opaque destination; routing
judged `PAY_OUT`/`BANK_ACCOUNT` under the version in force and the decision pinned; the hold
placed under the wallet account's `FOR UPDATE` with availability derived in-lock
(`INV-BAL-04`); the withdrawal row born `DISPATCHED` with our minted end-to-end reference and its
first send permit; `WithdrawalInitiated`; `WithdrawalDispatched` audited as the person. *The
wire*, holding no connection: `PushRail.send` with our reference as the idempotency key.
*Tx2*, as the platform: the row locked; `ACCEPTED` → the hold released and
`wallet-withdrawal:<id>` posted, DR `CUSTOMER_WALLET` 2.00 / CR the rail's declared clearing
position — `INSTANT_CLEARING`, **read from the stored rail since this review** — with the
scheme's reference and cycle stored; `WithdrawalCompleted`; one `WithdrawalOutcomeApplied`.
`NOTHING_SENT` fails it only on the dispatch's own first send under an unchanged permit
(ADR-0057 §3; a takeover's refused connection concludes nothing, **driven since this review**).
The wallet's statement then shows the pay-in's credit and this debit, one line each, naming its
entry — **reconciled line by line to the journal since this review**.

**A chargeback of 10.00 on a card sale already refunded 3.00.** The network's `needs_response`
arrives on the card door, signed and inbox-deduplicated. Under the attempt row's `FOR UPDATE` —
the one row refunds lock too — the dispute is born `CHARGED_BACK`; the counterparty's account is
share-locked first; `dispute-chargeback:<id>` posts the external fact, DR
`CHARGEBACK_RECOVERABLE` 10.00 / CR `SETTLEMENT_CLEARING` 10.00; `dispute-attribution:<id>`
posts the counterparty's bounded share, `min(10.00, 10.00 − 3.00 − 0)` = 7.00, DR the
counterparty / CR the recoverable. The 3.00 excess stays in the recoverable: value the network
took that the platform had already returned. A win posts both entries' exact inverses; a loss
writes off only the excess to `DISPUTE_COSTS`. The stage, both postings, the trail row, the
stage record, the fact and the inbox record commit together or not at all — **proven by failure
injection since this review**: the restoration's posting refused after the win's first entry
was written left nothing of the stage, and the redelivery of the same event applied it once.

`PASS`.

## Area 3 — Multi-instance correctness

Every contended decision in `PHASE_7_PLAN.md` §7, with its arbiter and the test that races it:

| Contention | Arbiter | Race |
|---|---|---|
| **Ten confirmations of one intent** | The intent's conditional `REQUIRES_CONFIRMATION → PROCESSING`; the decision born only in the winner's Tx1 | One attempt, one decision, one provider call (`P7-TSK-003`); the decision's Tx1 co-commit asserted on a crashed dispatch (**this review's**) |
| **A confirmation racing a cancel, a detach or a withdrawal of the offer** | The intent's conditional (cancel); the instrument resolved at the act inside Tx1 (detach — two serial orders, no arbiter needed); the session's conditional open behind the withdrawal's row lock (abandon) | Each deterministic with the loser observed waiting, both ways where two ways exist; six raced rounds on the session (**this review's**) |
| **A fallback racing an ambiguous dispatch** | None can occur: Phase 7 has no cross-rail fallback after dispatch; `NOTHING_SENT` fails the payment | `PaymentConfirmationTest#indeterminateAppendsNothing` |
| **Availability changing mid-routing** | Read inside the decision's transaction and recorded on its step | A change after the decision reroutes nothing in flight (**this review's**) |
| **Concurrent wallet debits** | The wallet account's `FOR UPDATE`, availability derived in-lock with holds | Ten withdrawals admit exactly the affordable set; a wallet payment and a withdrawal likewise |
| **Concurrent credits** | Each keyed by its own operation | Ten capture resolvers post once; ten instant callbacks, five under one event id and five fresh, credit once with one inbox record (**this review's**) |
| **A hold released while other operations run** | The account lock both take | The storm: available never negative, holds equal the debits in flight, every round |
| **Duplicate sends, a takeover, the inquiry sweep** | Our reference stored before the send; the send permit; conditional outcomes on the locked row | Ten racers on one key → one row, one entry, one fact, one send; a takeover's refused re-send concludes nothing (**this review's counts and path**) |
| **Duplicate rail callbacks** | The inbox's primary key, then conditional transitions | Instant and clearing, five identical and five fresh each (**this review's**); chargebacks ten ways (`P7-TSK-012`, `P7-TST-002`) |
| **A chargeback racing a refund on one payment** | The attempt row's `FOR UPDATE`, both paths | Refunds racing a chargeback keep the bound; the battery forces the refund's way |
| **A dispute stage racing a refund of another payment to the same counterparty** | The counterparty share-locked before the stage's first posting; seeded chart ids sorting first (**ruled here**) | Eight rounds against the deadlock the storm found |
| **Duplicate chargeback notifications** | The unique network reference, the stage's conditional, the posting key per stage | Ten identical, ten fresh, and mixed stages converge |
| **A pay-in racing its session's expiry** | The payer PSP's answer decides; landed money completes late | Both orderings, and concurrently |
| **Concurrent sweeps** | The leaderless pattern: conditional writes, permitted sends | Attempts and refunds (Phase 5–6); withdrawals, initiations and returns, ten ways each (**this review's**); dispute answers (`P7-TSK-014`) |

Nothing lives in process memory; no routing input is instance-local; every schedule is registered
in `NoSingleInstanceAssumptionRulesTest`. *"Correct with 10 concurrent instances?"* — **`PASS`**.

## Area 4 — Failure behaviour: two traced through the code

**The scheme accepts a withdrawal and the answer is lost.** The adapter's timeout is an honest
`INDETERMINATE`: the row moves to `UNKNOWN` with its hold standing, audited, counted by the stuck
gauge (`UNKNOWN`, plus `DISPATCHED` past the sweep's bound, `NaN` never zero). Every instance's
sweep asks the scheme by our reference: `ACCEPTED` completes it with one entry; `REJECTED` fails
it and releases the hold; `UNRECOGNISED` concludes `NEVER_RECEIVED` only once the latest send
permit is older than the declared deadline plus margin — a re-send may be in flight until then.
Ten sweeps racing that one row resolve it once (**this review's**).

**A pay-in confirmation names no initiation.** The signed door retains the evidence, and the
money it carries is parked, not dropped and not credited to a guess: DR the rail's clearing / CR
`SUSPENSE_UNMATCHED`, keyed `unmatched-confirmation:<rail>:<reference>`, one row by a unique key
however often delivered, audited, logged at warn and counted (`finapp.payments.unmatched.*`,
named in the plan's §15 **since this review**).

`PASS`.

## Area 5 — Security and audit

Phase 7 adds 21 routes, every one in the published contract. The permission register pins all
twelve guarded Phase 7 routes (`RoutePermissionRegisterTest`); every new privileged act has a
named permission and a negative test of its own — **the operator's evidence upload, evidence read
and representment since this review**, where they had rested on the register and the
deny-by-default composition — and the six merchant dispute routes are in the derived tenancy
battery. Bank-account registration and withdrawal step up when a factor is enrolled, the key left
unburned on refusal. Dispute evidence is AES-256-GCM under its own confined key, checksum-verified
on every read, append-only by grant, and on the record at every read and transmission. Three new
confined credentials, pinned. **Found and fixed here**: the port records that printed bank
details, the operator void accepting no reason at the domain, and the routing-version handler
without its keyed gate. **Read and accepted**: a routing refusal is audited for a pay-in
(`PaymentRoutingRefused`, **asserted since this review**) and recorded for a withdrawal by its
frozen decision row and its failed claim under the customer's scoped key — nothing moved, and
those two rows attribute it (`Withdrawals`' javadoc). `INV-PAY-02`'s sweep now reads every
text-like column of every schema after a routed card payment: the grant nowhere, the token only
on its payment method. Standing debt, unchanged: operators audited with actor type `CUSTOMER`
(Phase 15).

`PASS` — after the corrections.

## Area 6 — Invariants, the register, and the demonstrations

Ten `Phase: 7` invariants, read from the **catalogue**: `INV-HIST-04` (its routing element),
`INV-REV-03`, `INV-SET-01`, `INV-RAIL-01`…`04` and `INV-DSP-01`…`03`. Every one carries rows in
`MUTATION_TESTING.md` §2, demanded by the guard from the flip; both `P7-TST` items carry §4 rows.

The review's own corrections and tests were held to the same standard: **twenty-four probe runs over
twenty-three breaks, twenty-three caught at once, every restore verified byte-identical** — each broken on purpose, its test run
fresh in a Gradle invocation of its own, the verdict read from the failing testcases and never the
exit code alone:

| # | The correction or new test, broken | Caught by | Observed |
|---|---|---|---|
| D1 | The withdrawal posting to a NAMED `INSTANT_CLEARING` again | `RailVocabularyIsConfinedTest#clearingPositionsAreNamedOnlyByTheirDeclarations` | Behaviour is equal today, so only the static rule can see the name return |
| D2 | `GrantExchange` printing the grant | `PushRailRecordsRedactTest#theGrantNeverPrints` | — |
| D3 | `CreditTransfer` printing the destination | `PushRailRecordsRedactTest#theDestinationNeverPrints` | — |
| D4 | `ExchangeAnswer` printing the destination | `PushRailRecordsRedactTest#theExchangeAnswerNeverPrintsTheDestination` | — |
| D5 | The domain accepting an unreasoned operator void | `PaymentVoidTest#theOperatorDoorRequiresAReason` | — |
| D6 | The routing-version handler without its keyed gate | `RoutingPolicyDatabaseTest#aVersionIsCreatedKeyedAuditedAndImmutable` | Spring's generic 400 where `api.IdempotencyKeyRequired` was due |
| D7 | The instant rail's settlement model changed under its own name (a coherent declaration) | `RailMoneySemanticsArePinnedTest#moneySemanticsAreFrozenPerRailId` | The frozen tuple differs: a money-semantics change must mint a new rail |
| D8 | A chart seed minted above the ceiling | `OperationalChartMigrationTest#everySeededIdSortsBeforeEveryRuntimeId` | The seed named as one a running instance could mint below |
| D9 | The routing refusal audited under the wrong operation | `PaymentEndpointDatabaseTest#routingPinsTheConfirm` | No `PaymentRoutingRefused` naming the refused decision |
| D10 | The losing confirm converging on a cancel | `PaymentAuthorizationDatabaseTest#aCancelAndAConfirmRacingTheWindowHaveOneWinner` | **By the race alone** — `cancelWinsOnlyTheWindow` ran beside it and passed |
| D11 | The losing cancel converging on a confirm | The same race | **By the race alone**, the sequential test passing |
| D12 | A detached card still resolving | `PaymentEndpointDatabaseTest#aDetachRacingAConfirmationIsJudgedAtTheAct` | The confirmation after the detach charged the card |
| D13 | A lost open not rolled back | `CheckoutFlowDatabaseTest#aWithdrawalRacingAConfirmationLeavesOneStory` | The withdrawn offer carried an intent and a pin |
| D14 | A stage posting's failure swallowed | `ChargebackAccountingDatabaseTest#aStageFailsWholeAndItsRedeliveryAppliesIt` | A `WON` dispute without its restoration, acknowledged |
| D15 | The completion refusing a suspended merchant | `CheckoutFlowDatabaseTest#aBankPaymentAdmittedBeforeTheSuspensionLands` and the card sibling | Landed money refused its order on both rails |
| D16 | The instant adapter printing the destination as it sends | `PayByBankDatabaseTest#theDestinationReachesNoSink` | In the captured log output |
| D17 | The token copied into an audit record | `PaymentEndpointDatabaseTest#instrumentInputRestsNowhereButItsReference` | Found by the every-schema sweep in `platform.audit_record.change_summary` |
| D18 | The chosen decision never pinned beside the attempt | `PaymentAuthorizationDatabaseTest#aCrashMidCallStrandsTheDispatchVisibly` | A stranded dispatch with no decision naming its rail |
| D19 | Every step recording its rail available | `PaymentEndpointDatabaseTest#routingPinsTheConfirm` | The stored-row recomputation of the refusal chose the card |
| D20 | A takeover's refused re-send concluding FAILED | `WithdrawalDatabaseTest#aTakeoversRefusedConnectionConcludesNothing` | The re-sent withdrawal failed with its hold released |
| D21 | The instant door's inbox bypassed | `PayByBankDatabaseTest#tenDuplicateCallbacksCreditOnce` | No inbox record for the shared event id; the conditional transition kept the effect single — the second rank |
| D22 | The void's capability gate dropped | `PayByBankDatabaseTest#noCancelNoReversalRouteExists` | **SURVIVED at first**: the push machine refused in its place (no void edge leaves `AWAITING_PAYER`), the same 409, nothing written — and the test asserted only the status. Tightened to the capability's own code, re-probed: caught |
| D23 | The operator's evidence upload under `MERCHANT_ADMINISTER` | `DisputeResponseDatabaseTest#anOperatorAnswersOnlyAPaymentWithNoMerchant` | A merchant administrator's upload admitted |

**Recorded without a probe, with the reason**: the ten-way sweeps for withdrawals, initiations and
returns and the instant callbacks' effect counts sit behind several ranks — the row lock, the
conditional transition, the posting key, the aggregate's guard — each probed at its own task, so
removing one is absorbed by the next; these tests demonstrate the composition. The availability
change is a property of absence (no path reroutes, so no single edit creates one). The chain walks
are joins over stored identifiers whose columns are frozen by trigger.

`PASS`.

## Area 7 — Documentation accuracy

Diffed against the implementation, document by document:

- **The ADRs**: all four had drifted — see *ADR-0059 … 0062: accepted*.
- **`RAIL_AND_DISPUTE_LIFECYCLES.md`**: the preamble still said nothing was implemented; the
  outbound push states claimed a producer (a return is a refund row); the book attempt was "born
  `EXECUTED` or `FAILED`"; a cross-rail re-dispatch was promised to two tasks that shipped without
  one; the trigger's latest regeneration is `V017`.
- **`PHASE_7_PLAN.md`**: the preamble; §2 (the void re-sends without a permit, deliberately); §4
  and §5 (the `DisputeResponse` aggregate and machine; the real push and book machines); §7 (no
  fallback after dispatch; the sweeps and the confirmation races added); §8 (six tables and the
  columns the plan never named); §9 (no merchant refund route; the routes it omitted); §10 (the
  fact is `DisputeResponseSubmitted`; the dispute facts carry ledger account ids by design; the
  void's unknown publishes); §12 (four posting prefixes; no "directory resolution"); §14.1 and
  §14.3; §15 (five shipped series unnamed); §18 (no pending figure exists — the holds are what is
  in flight).
- **The registers**: `DISTRIBUTED_EXECUTION.md` §3 counted judgements "at the door" after
  `P7-TSK-015` moved them to the commit, had no rows for Phase 7's five cached gauges or the
  commit-deferred counters, and one row lacked its authority cell — the register-decay class,
  again; the lock-order rule is now a row. `MODULE_ARCHITECTURE.md` missed four owned concepts,
  two ports and every Phase 7 API, and called a dispute "a lifecycle on a captured card payment".
  The data classification gave `withdrawal.wallet_account_id` a level its cited reasoning
  contradicts; the error contract's `ReversalNotSupported` title differed from the code's; the ADR
  index paraphrased the four headings.
- **The vocabulary**: the glossary's `Clearing` now names the clearing record's owner, and
  `Interaction Model`, `Void`, `Return Payment` and `Dispute Response` joined the canonical list
  and the glossary together (the guard demands the glossary be exactly that union).
- **`DECISIONS.md`** indexes ADR-0059…0062 as `Accepted`, and §Deliberately Deferred gained five
  rows that lived only in ADR text: recalls and batch rails, the payout's convergence, automatic
  availability (its two owners reduced to Phase 15), the per-rail cost meter (Phase 8) and
  dispute-fee pass-through.
- **Code comments**: `InteractionModel`, `Withdrawals` (a recall named as `reverse`'s future
  caller, contradicting ADR-0059), `PaymentsAuditAction` (the declined-capture redirect writes no
  `PaymentVoidDispatched`), `PaymentConfirmation` (the promised advance) and `AccountPurpose`
  (`SETTLEMENT_CLEARING` narrowed to the card rail).
- **`PHASE_GATES.md`** is left as it was written. Three of its Phase 7 phrases are read against
  the code in the criteria table rather than edited at the gate that judges them: "the pending
  figure" (the holds), "`EXECUTION_UNKNOWN`" (the withdrawal's and the return's `UNKNOWN`) and
  "the descriptor recorded with every decision" (its version, under the frozen-semantics rule).

`PASS` — after the corrections.

## Area 8 — Debt, and what is deliberately deferred

| Item | Owner |
|---|---|
| No fleet-wide `databaseTest`/`kafkaTest` run for the phase, by the owner's standing instruction — the Testing bullet asks for it by name | The Phase 7 → 8 transition, which ran the battery last time; meanwhile the fleet-wide hermetic tier at every gate and the targeted database tiers below |
| Chargeback debt has no reserve, and a customer's receivable no collection — credit risk, bounded by `INV-DSP-01`, every position on the books and counted | Phase 13 (new row; its trigger the negative-position gauge above zero in operation) |
| `EXECUTION_DISPATCHED` and `EXECUTION_UNKNOWN` declared with no producer (ADR-0044's rule bent, every reader now told "reserved") | The phase adding the next push rail (new row) |
| The conditional step-up in five services — its trigger fired twice in Phase 7, unpaid | Re-owned: Phase 15, due at the rule's next change or a sixth caller |
| A refused step-up is not audited — now on five surfaces | Phase 15 (audit completeness) |
| A corrupt id stalls a sweep while listing; a poisoned row delays one — both now on Phase 7's four new sweeps too | Phase 15 |
| Every other constant idempotency scope, recounted (Phase 7 added three principal-scoped claims and one constant) | `X-TSK-003` |
| A return keeps the scheme's reference but not its settlement cycle | Phase 8's input (ADR-0062) |
| A withdrawal's routing decision is neither metered nor readable through the operator explanation | Recorded (ADR-0060); a second push rail is when it matters |
| One adapter per interaction model; a second same-model rail needs per-rail wiring and its own clearing position. **Found at this item's gate**: `Withdrawals` refuses a routing choice naming any push rail but the wired one, and the confirmation's push branch has no such guard — safe while the directory declares one push rail (the model check already refuses a cross-model choice), silently wrong the day a second is declared; neither port exposes its rail's id, so the guard is a composition-root parameter, not an edit here | The phase adding the second same-model rail, which must add the guard with its wiring (ADR-0059 §1, ADR-0062 §1) |
| Recalls, batch rails, the payout's convergence, automatic availability, the cost meter, fee pass-through | `DECISIONS.md` §Deliberately Deferred, each with its owner |
| Flake fixes on six unmerged commits (`816f850`, `effd89f`, `47c5d36`, `647c088`, `bb86186`, `52992cd`); two flaky patterns stand here | Each branch's own session — recorded in `CURRENT_STATE.md` §Blockers, not merged here |

`PASS` — everything above is recorded with an owner rather than carried silently.

---

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — the card rail's void and clearing; the instant rail's pay-ins, withdrawals and returns; the wallet as an instrument with book refunds; routing; disputes with their accounting, answers and evidence; the meters and the report — all exercisable end to end over real HTTP |
| 2 | Architectural boundaries respected | `PASS` — `payments` depends on `ledger` and `platform` alone; every new join is a port `app` implements; the isolation suites green |
| 3 | Required invariants tested | `PASS` — ten `Phase: 7` invariants, every one with register rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — all fifteen of `PHASE_7_PLAN.md` §14 have a test; scenarios 1 and 3 re-worded to the code (no fallback after dispatch; the withdrawal's and return's `UNKNOWN`), and scenario 14 proven on the bank rail since this review |
| 5 | Security requirements implemented | `PASS` — area 5, after its corrections |
| 6 | Observability exists | `PASS` — fifteen §15 series from a freshly started instance; the stuck withdrawal and dispute answer alertable, `NaN` never zero; dashboards query published series only |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — see below |
| 8 | Documentation reflects reality | `PASS` — area 7, after the corrections |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's own finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0059…0062, each read against the code and corrected before any was accepted. ADR-0055 is cross-cutting and its acceptance the owner's |
| 11 | No unresolved critical issues | `PASS` — no blocker; nothing in area 8 is critical |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction skips
`build databaseTest kafkaTest`, and every item of the phase was verified by targeted tiers and
recorded in those words. The gate's own Testing bullet asks for "the full battery green
fleet-wide at the exit review", and this review does not pretend otherwise:

- The **hermetic** tier was run **fleet-wide after the flip** — **1740 tests across 14 modules, 0 failures**.
- The **database** tier was run over **408 tests across 38 suites, 0 failures** — every Phase 7 suite, the storm and the
  dispute battery among them — plus the platform classification guard. **No fleet-wide database
  or kafka count is claimed for Phase 7.**

Assessed **`PASS` with the deviation recorded**, and its cost stated from this very phase: the
Phase 7 tasks' own completion gates found three fixture flakes in suites no targeted tier had
included, each only when a gate happened to run a wider battery.

## The financial supplement F1–F8 — re-assessed at the gate

| # | Criterion | Verdict |
|---|---|---|
| F1 | Trial balance zero per currency | `Met` — every round of the multi-rail storm in one repeatable-read snapshot and at rest; the dispute battery over its own entries |
| F2 | Balances reproducible by replay from zero | `Met` — every wallet against an independent book and every payable against its sales, per round; the wallet statement reconciled line by line to the journal (**this review's**) |
| F3 | Money-moving commands idempotent at the financial boundary | `Met` — withdrawals keyed per customer, dispute answers per responder, refunds and pay-ins under their claims, every provider operation under our stored reference, every posting under its operation key |
| F4 | Reversal/compensation implemented, no path mutates history | `Met` — the void releases a promise and posts nothing; refunds, returns and book refunds are new movements; a win is the chargeback's exact inverse; decisions, dispute facts and evidence immutable by trigger and grant |
| F5 | Duplicate external delivery produces no second effect | `Met` — instant and clearing callbacks five identical and five fresh (**this review's**), chargebacks ten ways, every door through the inbox |
| F6 | Concurrency tests for every contended financial resource | `Met` — area 3, **after the races this review added** |
| F7 | No floating point in a monetary path | `Met` — statically verified; Phase 7's exemptions are metrics classes publishing counts and ages |
| F8 | Reconciliation implemented or deferred with a named owner | `Met` — each clearing position reconciles to its rail's operations under the storm; the chains walk identifier to identifier (**this review's**); settlement matching is Phase 8's |

## The Phase 7-specific criteria — twenty-one

Counted from `PHASE_GATES.md` §5 at review time: **5 original + 16 added by the Phase 6 → 7
transition = 21**, every one `PASS`:

| Criterion | Evidence |
|---|---|
| Two rails with materially different finality | The card (revocable until the dispute window ends) and the instant scheme (final on acceptance), each declared and each driven end to end; the wallet a third (final on posting) |
| A reversal on an irrevocable rail refused by the domain | `INV-REV-03`: the void, a cancel after the window and `Withdrawals.reverse` refuse from the declaration before anything is written or sent; for an instant pay-in, zero audit, event, history and wire (**counters since this review**) |
| Routing deterministic, version-pinned, explainable from stored data | Pinned in Tx1, frozen by trigger, and **recomputed from its stored row since this review** |
| Duplicate chargeback notifications, single financial effects | Ten ways per stage, identical and fresh, under load (`P7-TST-002`) |
| A chargeback on an already-refunded payment, no double debit | `REFUNDED_FIRST` and the combined bound at both ranks; against merchant payables under the storm |
| Card infrastructure | The void trio with no ledger effect, `VOID_UNKNOWN` resolved by query; clearing recorded once with the acquirer reference; **the descriptor read as its recorded version under the frozen-semantics rule ruled here**; `INV-PAY-02`'s sweep over every schema (**this review's**) |
| Wallet infrastructure | One-transaction wallet payment; unaffordable refused under the wallet's lock with holds counted; no balance column (the platform-wide schema scan); **"the pending figure" read as the holds** — no such figure exists, and the holds equal the debits in flight under the storm; the statement reconciled line by line (**this review's**) |
| A2A | Withdrawal over the simulated scheme with hold-then-dispatch under the permit; pay-by-bank funding a wallet and a checkout through `AWAITING_PAYER`; a return refunding it; `INV-RAIL-03` through every money path, logs captured (**this review's**) |
| Instant-payment abstraction | The rail-name rule green with planted violations refused, **the clearing-position rule added here**; **"`EXECUTION_UNKNOWN`" read as the withdrawal's and the return's `UNKNOWN`** — the attempt state has no producer — and the inquiry past the deadline authoritative for the withdrawal; `INV-REV-03` probed |
| Payment/ledger integration | Every completion on its rail's declared position — **the withdrawal's since this review**; each position reconciled; the trial balance zero under the storm |
| Multi-instance, atomicity, consistency | Area 3; the decision's Tx1 co-commit and the stage's all-or-nothing **proven since this review** |
| Idempotency — the four scenarios | Lost answers on a capture, a withdrawal and a pay-in; ten-way on confirm, withdraw, refund, void and stage; one callback ten times on all three doors (**the instant and clearing halves since this review**); a hold released under a concurrent wallet payment and transfer |
| Failure recovery | §14 all fifteen; **no cross-rail fallback exists, so the rule "advances only on `NOTHING_SENT`" holds with nothing to advance to** — the `ABANDONED` step recorded, `INDETERMINATE` appending nothing; every re-sending flow permitted, the refused connection concluding only on a first send (**the takeover driven since this review**) |
| Disputes | `INV-DSP-01` raced both ways; `INV-DSP-02`'s mirror and loss; each stage once |
| Routing explainability | The stored-row recomputation, and the explanation's success path with its audit record (**this review's**) |
| Security | Area 5 |
| Audit | Every new act catalogued; reasons where a person judges; acting-only outcome records counted under duplicates (**this review's**) |
| Observability | Fifteen series from a fresh instance; the stuck withdrawal alertable, `NaN` never zero |
| Reconciliation readiness | ARN → clearing record → attempt → capture entry, and scheme reference → withdrawal → entry, walked by identifier (**this review's**); the provider dispute reference unique and each stage's entry referencing its dispute |
| Testing | The storm and the battery green and probed; **the full battery as a recorded deviation** — criterion 7 above |
| Documentation | Ten invariants with rows, demanded after the flip; ADR-0059…0062 read against the code and accepted; the lifecycle document matching the machines as built |

## ADR-0059 … ADR-0062: accepted

Read against the implementation, corrected where they had drifted, and then moved from
`Proposed` to `Accepted` — none accepted before its corrections landed:

| ADR | Verdict at the reading | What was corrected |
|---|---|---|
| 0059 — rails, capabilities, finality | Drifted, and the code short of it | Only the descriptor's version is stored — **ruled: money semantics frozen per rail name, guarded**; one adapter per model; the void's real edges; the push machine's outbound states have no producer; the book attempt born `EXECUTED` only; terminals shared, non-terminals not; refunds are operators'. **Code fixed**: the withdrawal's clearing read from its rail |
| 0060 — routing | Drifted, text | Returns are never routed; reachability is not stored; recomputation now from a stored row; no operating hours; **no cross-rail fallback after dispatch**; `UNDECLARED_BY_BUILD`; the cost meter Phase 8's; the index's migration; the meter and explanation cover confirmations |
| 0061 — disputes | Drifted, text | `CLOSED`; the arbiter since the attempt lock; what the recoverable holds; the counterparty lock only on the stages that post to it; evidence retained in the effect's transaction; a later-reported deadline; reserves now recorded with an owner |
| 0062 — A2A and instant | Drifted, and the code short of it | The port's two return operations; the per-rail wiring a second scheme needs; `UNKNOWN`, not `EXECUTION_UNKNOWN`; the return's resolution; the follow-up's swapped tasks; the return's missing cycle. **Code fixed**: the port records redact bank details |

## The inputs routed to this review, decided

| Input (`P7-TST-002`) | Decided |
|---|---|
| (1) The dispute criteria's evidence under load, with wallets as the battery's counterparties | Accepted as evidence for both criteria. "Debits the merchant nothing twice" was read against both: the battery's wallets (the split is judged in `payments` whoever holds the account) and the storm's merchant payables, whose sales it disputes and reconciles per round through `MerchantBoundDisputeComposition` |
| (2) §14's dispute scenarios 7–10 each tested | Confirmed, and criterion 4 assessed over all fifteen |
| (3) The inbox never contended; the refund's side forced | Accepted as observation; the review's own instant and clearing 5 + 5 races assert the inbox record directly |
| (4) A lock order that holds by construction | **Confirmed as a rule**, written into `DISTRIBUTED_EXECUTION.md` §3 and guarded — removing the dependency would need a ledger pre-lock no flow needs |

## What the phase produced

Money now moves on three rails whose differences are data the domain acts on, not branches on a
name: a card rail that can be voided, cleared and charged back; an instant rail that is final on
acceptance and settled on its scheme's cycle; and the platform's own books. Every payment is
routed once and can say why; every external rail's money in flight sits on its own clearing
position, ready for Phase 8 to discharge against that counterparty's own evidence; bank details
never enter; and a chargeback is posted exactly once per stage and never takes more than the
capture credited. The mechanisms — declared capabilities, pinned decisions, the send permit on
every outbound push, the combined bound under one lock, suspense for what cannot be matched — are
what Phase 8's settlement and reconciliation will read.

## What happens next

Phase 8 — Settlement and Reconciliation — behind its own entry gate. The Phase 7 → 8 transition is
the next act, and it inherits from this review: the fleet-wide battery this phase's instruction
skipped, the return's missing settlement cycle, the per-rail cost meter, and `X-TSK-003`. This
review's only claim about it is that nothing in area 8 blocks it.
