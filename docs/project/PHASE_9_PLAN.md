# Phase 9 — FX and Cross-Border Payments

Written by the Phase 8 → 9 transition (2026-10-02). Decisions in ADR-0074 (conversion arithmetic:
`ExchangeRate` in the kernel, both fixed sides, one margin line with stored attribution, the
residual proven bounded and posted), ADR-0075 (the rate chain and the quote: an independent
reference, the provider's firm quote as the lock, keyed two-transaction creation, the six-state
machine, expiry as an event, versioned four-eyes pricing), ADR-0076 (multi-currency accounting
through `FX_POSITION`: the quote as a frozen posting plan, the principal model, the FX books proof),
ADR-0077 (the decoupled cover: one back-to-back cover per accepted quote, re-sent under one
reference until answered), ADR-0078 (counterparty-keyed clearing positions: `OwnerKind.COUNTERPARTY`,
the `ledger.counterparty` registry, accounts seeded below the ceiling), ADR-0079 (cross-border
payments: `crossborder` decides, `fx` prices and books, `payments` executes; the Outbound Credit;
hold then one entry at provider acceptance; returns and recall; ADR-0062 §7's convergence trigger
fired and declined with reasons), ADR-0080 (corridors, beneficiaries and selection: the truthful
corridor declaration, routing's third subject, selection pinned at registration), ADR-0081
(counterparty screening is kyc's: every outcome a recorded decision, an unverified payee always
reviewed, fail safe), ADR-0082 (FX and corridor settlement and reconciliation: legs as
single-currency expectations, operation-anchored returns, new causes and no new break types,
reconciliation never converts) and ADR-0083 (callbacks are hints: the outcome adopted only from an
authenticated inquiry) — each Proposed (2026-10-02, the Phase 8 → 9 transition) and indexed in
[`docs/adr/README.md`](../adr/README.md). ADR-0063 is `X-TSK-005`'s,
so Phase 9's numbering starts at ADR-0074 and its cross-cutting tasks at
`X-TSK-013` *(written `X-TSK-010`; renumbered with its two siblings when this plan merged, the main
line holding `-010` and `-011`)*. The domain-facing statement of the machines is
[`FX_AND_CROSS_BORDER_LIFECYCLES.md`](../domain/FX_AND_CROSS_BORDER_LIFECYCLES.md), written by the
transition. Until Phase 9's first task lands, nothing in this plan is implemented.

## 1. Objective

**A customer converts money between currencies, and sends money abroad, at a server-authoritative,
time-bounded, single-use price that is a frozen posting plan — and no lost response, retry,
duplicate callback, rate move, expiry race or set of ten racing instances can execute twice, apply
FX twice, charge twice, lose a provider reference, or turn a success into a failure.** Concretely,
Phase 9 delivers four things:

- **Conversion.** A customer converts between any of five currencies (EUR, GBP, USD, JPY, BHD) at
  a quoted rate, by source or by destination amount; every executed amount is explainable from
  stored provenance, and replaying the stored inputs reproduces the posted entry exactly.
- **Cover.** Each conversion is covered back-to-back with the FX provider exactly once, however
  the provider answers; the platform's position is zero at rest, and only slippage is P&L.
- **Payments abroad.** Money is sent through a declared corridor rail to a screened beneficiary:
  a hold at authorization, one entry at the provider's acceptance, delivery, return, and
  cancellation by recall. A payment that fails debits the customer nothing.
- **Reconciliation.** Every position, fee, spread, residual and settlement leg is reconciled to
  the provider's and the bank's own evidence, per currency and per counterparty, and never by
  conversion.

The phase ships in three vertical slices:

| Slice | A customer can | An operator can | Milestones |
|---|---|---|---|
| **A. A price** | Hold JPY and BHD wallets; request a quote for any of 20 directional pairs, by source or by destination amount; see the rate, both amounts, the disclosed margin and the expiry; cancel it, or watch it expire | Propose and approve pricing policies (four-eyes); suspend a pair or a provider; read reference-rate staleness | M9.1, M9.2 |
| **B. A conversion** | Accept a quote and have EUR become USD in one atomic step; read balances per currency | See every trade's rate chain; see `FX_POSITION` proven against open legs; see each cover executed exactly once and settled to cash; read the FX revenue and position reports | M9.3, M9.4 |
| **C. A payment abroad** | Register a beneficiary; get an offer (rate, fee, guaranteed destination amount, expiry); authorize it; follow it to delivery; cancel it by recall; get money back on failure or return | Review screening hits; administer corridors; follow the full identifier trail; reconcile the corridor provider | M9.5, M9.6, M9.7 |

**Scope, concern by concern:**

| Concern | Verdict | Precisely |
|---|---|---|
| Currencies | IN | JPY (0 minor units) and BHD (3) join EUR, GBP and USD (2); minor units stay the JDK's, pinned by a test and a startup guard (ADR-0074) |
| Rates | IN | The chain reference → provider firm quote (the lock) → internal → customer → executed → cover-executed, every link stored (§12.3) |
| Quotes | IN | Both fixed sides (`FIXED_SOURCE`, `FIXED_DESTINATION`); the frozen plan; database-clock validity; expiry a modelled event; cancellable; capped at 5 live per owner |
| Conversion | IN | Wallet to wallet, booked at acceptance through `FX_POSITION` in one transaction; final on posting for the customer |
| FX execution with the provider | IN, as the cover | One cover per accepted quote, plus an unwind per abandoned quote or reversed trade. **Not** hedging, netting, position limits or treasury (§17) |
| Multi-currency wallets | IN | n `CUSTOMER_WALLET` accounts per product, one per currency, opened if absent inside the caller's transaction |
| Cross-border payment | IN | A hold, then provider acceptance; one entry; delivery; return; cancellation by recall; `OUR` only (the beneficiary receives the quoted amount) |
| Corridors, beneficiaries, screening | IN | Versioned four-eyes corridor policy; the beneficiary known by provider reference, no account identifier stored; kyc screening before pricing |
| Fees and spreads | IN | Spread and markup → one `FX_SPREAD_REVENUE` line with stored attribution; transfer fee → `FEE_REVENUE`; residual → `ROUNDING_RESIDUAL`; provider and intermediary fees → `PROCESSING_COSTS` from evidence |
| Settlement and reconciliation | IN | Two new counterparty-keyed positions settled in ADR-0065's two hops; reconciliation never converts (D29, ADR-0082) |
| Multiple providers | IN | Ports, declarations, ordered selection and counterparty-keyed positions on the main line; the second provider of each kind in M9.8 (cut first, O8) |
| Revaluation, functional currency, FX P&L reporting | OUT | Phase 14 (ADR-0076) |
| Cross rates, `SHA`/`BEN`, merchant multi-currency, multi-currency internal transfers | OUT | §17 |

## 2. Why this phase is shaped by decisions already taken

- **The platform is principal, and the customer never meets the provider's ambiguity.** The
  customer's conversion is booked when the quote is accepted, in one local transaction with no
  provider call in it; the back-to-back cover runs separately, once per accepted quote (ADR-0076,
  ADR-0077). A provider's `UNKNOWN` never reaches a customer balance, and no provider port is
  reachable from the conversion transaction (`INV-FX-09`, a static rule).
- **A quote is a frozen posting plan** (ADR-0074, ADR-0075). Every amount the trade will post is
  computed once, at quote time, by one pure function over stored inputs and pinned policy
  versions, and frozen on `fx.quote` under a per-currency plan-identity `CHECK`, a freeze trigger
  and `UNIQUE (fx.trade.quote_id)`. Execution posts the plan and never re-prices (`INV-FX-04`).
- **ADR-0003 already decided that a second representation type gets ADR-0006's argument made
  once.** `ExchangeRate` therefore lives in `sharedkernel.money` — directional, `NUMERIC(20,10)`
  generated from constants through platform `RateColumns.ddl()`, no inversion and no cross rates
  in arithmetic — and `CountryCode` beside `CurrencyCode` (ADR-0074).
- **The counterparty split trigger fires on accounts that have no history.** ADR-0062's
  Consequences recorded that per-counterparty clearing arrives with the phase that needs it:
  `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` are keyed by counterparty from birth
  (`OwnerKind.COUNTERPARTY`, the seeded `ledger.counterparty` registry), every account seeded by
  migration below the UUIDv7 ceiling, and the existing operational clearings untouched (ADR-0078).
  A second provider becomes a declaration, a seed migration and an adapter.
- **ADR-0062 §7's payout convergence trigger is fired by the corridor rail — and convergence is
  declined, with reasons** (ADR-0079 §9): the merchant payout's settlement shape is already
  canonical (ADR-0073 §8), and its port carries a single-currency merchant flow Phase 9 does not
  touch. The re-recorded trigger is a merchant payout in a currency other than the settlement
  currency, or on a rail other than `PayoutProvider`.
- **The outbound discipline whose defects three gates repaired is reused, not reinvented.** The
  Outbound Credit is a new `payments` aggregate on ADR-0057's shape — the permit, `UNKNOWN`, the
  sweep and the evidence machinery — plus `RECEIVED` (the provider acknowledged but has not
  committed), and its journal lines come from the `OutboundCreditComposition` port in the
  `CaptureComposition` shape (ADR-0079).
- **ADR-0060's recorded seams are paid, and §6 holds unamended.** Routing gains its third subject
  (intent XOR withdrawal XOR outbound credit), `destination_country` as a rule matcher and
  decision input, and per-candidate reachability (`ADR-0060 §2`'s "the phase that makes
  reachability real stores it"). Only the beneficiary's issuing rail is reachable, so a re-route
  is impossible, and price comes from the corridor, never from the rail. Routing policy v5 goes
  through ADR-0060's existing single-person operator door, by that ADR's own decision (D26).
- **ADR-0059 §1's per-rail operation lookup is paid**: `app` composes a `RailOperations`
  directory (`RailId → PushRail | CorridorRail`), and `Withdrawals` and `PaymentConfirmation`
  refuse, with nothing sent, a routed rail that lacks the operation they need (`P9-TSK-014`).
- **Callbacks are hints, as one doctrine** (ADR-0083, amending ADR-0047 for outbound money flows):
  authenticate, retain evidence, dedupe through the inbox, then adopt the outcome only from an
  authenticated inquiry. A stolen webhook key moves no money. Phase 9's flows are built on it from
  birth; `X-TSK-015` aligns the Phase 5/7 providers (owner Phase 15).
- **No policy version is seeded by migration** (D26). Phase 8's "migration provenance is `V002`'s
  alone" rule stands: pricing policy v1, corridor policy v1, each new source's first rule set (a
  version-1 path in `RuleSetAdministration` with a typed `RuleSetMissing` refusal) and the four
  existing sources' JPY/BHD v2 successors are all activated four-eyes through doors.
- **kyc is the only verification authority** (`INV-KYC-05`). Counterparty screening is a kyc
  aggregate on the existing screening adapter and credential; the compliance hold sits on the
  beneficiary, before pricing, so a review lasting hours never sits behind a 60 s rate lock
  (ADR-0081).
- **No amount ever enters a metric** (ADR-0072). Position, spread, residual and P&L are audited
  operator reports; meters carry counts, ages and verdicts (D32).
- **The transition repaired a Phase 8 latent defect before the boundary**: `FeeCheck` threw on a
  cross-currency `ORIGINAL_REF` (`FeeCheck.java:50-53`; `Matching.applyFeeCheck` did not
  pre-filter). A currency pre-filter now yields a typed `CURRENCY_MISMATCH` instead of
  `ITEM_ERRORED`, probed and recorded in `MUTATION_TESTING.md`.

### The transition's decisions, each the owner's to revisit

The design left ten questions to the owner. The transition settled each on the design's
recommendation and records it here; revisiting one amends the ADR and the tasks named.

| # | Question | Decided | Binds |
|---|---|---|---|
| O1 | Principal or agent; when to cover | Principal; book at acceptance; one back-to-back cover per accepted quote | ADR-0076, ADR-0077 |
| O2 | A returned cross-border payment | Applied automatically only when exactly the instructed credit comes back (its currency and amount), credited in that currency; transfer fee refunded; spread stands. Any other return (partial, another currency, a closed customer) is parked and decided by a person, whose resolution records the return on the payment (§12.9.3, T-g) | ADR-0079 |
| O3 | Quote modes | `FIXED_SOURCE` and `FIXED_DESTINATION`, for conversions and cross-border alike | ADR-0074 |
| O4 | Where compliance review lives | On the beneficiary, before pricing; decided by kyc; unavailable means unpayable | ADR-0081 |
| O5 | Provider callbacks | Hints: evidence retained, outcome adopted only from an authenticated inquiry | ADR-0083 |
| O6 | Currencies | JPY and BHD. The four existing sources' **v2 successors** (activated through the existing four-eyes door, so no stored decision changes) carry, for JPY (scale 0) and BHD (scale 3), every per-currency row their v1 holds for EUR/GBP/USD, at values of about the same worth (1 USD ≈ 150 JPY ≈ 0.376 BHD): **severity thresholds** JPY `150000` and BHD `400000` (= 400.000) on every source; **`provider_fee_schedule`** (rate unchanged, `HALF_UP`) — PSP `PROCESSING_FEE` 0.015000 + JPY `40` / BHD `100` (= 0.100); scheme `SCHEME_FEE` 0 + JPY `15` / BHD `40`; payout `PAYOUT_FEE` 0 + JPY `40` / BHD `100`; bank `BANK_FEE` 0 + JPY `75` / BHD `200`; **fee tolerances** (PSP only, as in v1) `PROCESSING_FEE_PER_LINE` JPY `3` / BHD `10`, `PROCESSING_FEE_PER_BATCH` JPY `75` / BHD `200`. The rows are carried on every source whether or not its rail declares the currency, so no JPY/BHD fee line ever meets a missing schedule (which `FeeCheck` prices at zero) | `P9-TSK-003` |
| O7 | Pricing and corridor defaults (policy v1, activated four-eyes) | Every one of the 20 directional pairs: spread 0.003500, markup 0.001500 (a pair needs `spread + markup > 0`); **rate scale 10 for the four JPY-source pairs and 6 for the other sixteen**; rate rounding `TOWARDS_ZERO`, amount and margin rounding `HALF_EVEN`; window 30 s (conversion) / 60 s (cross-border); cover margin 10 s; band 150 bps (EUR/GBP/USD crosses) / 300 bps (any JPY or BHD pair); reference max age 120 s; open-quote cap 5. **Notional bounds bind the fixed leg only**, in its currency: EUR/GBP/USD 1.00–50,000.00; JPY 100–7,500,000; BHD 0.500–20,000.000. Corridors: EUR→USD/US, EUR→JPY/JP, USD→BHD/BH, GBP→USD/US. Transfer fee EUR 2.50 / GBP 2.00 / USD 3.00 + 0 bps. Corridor maxima USD 10,000.00 / JPY 1,500,000 / BHD 4,000.000. Screening validity 7 days. **First rule sets:** the FX source's `FX_FEE` schedule is 0 + 0 in all five currencies (the simulated FX provider earns its spread and bills nothing, so any reported FX fee is a `FEE_MISMATCH`); the corridor source's `PAYOUT_FEE` schedule is 0 + USD `120` (= 1.20) / JPY `180` / BHD `450` (= 0.450); neither source has a fee tolerance (absent reads zero) | ADR-0075, ADR-0080, ADR-0082 |
| O8 | Cut order if scope must shrink | (1) M9.8, the second providers (`P9-TSK-026`); (2) the operator FX trade reversal (`P9-TSK-025`), each recorded with Phase 15 as owner. Never cut: unwinds, returns, cancellation, the proofs, the ten scenarios. Cutting `-025` leaves scenario 8 met by `UnwindRetryDatabaseTest` (`-021`), and gate criteria 9, 16 and 20 carry a conditional clause for it, as criterion 13 does for M9.8 | §16, `PHASE_GATES.md` |
| O9 | Charge bearer | `OUR` only: the beneficiary receives the quoted destination amount. `SHA`/`BEN` are deferred, triggered by a corridor whose provider cannot guarantee the delivered amount | ADR-0079 |
| O10 | Conversion fee | None in Phase 9 (margin only); fees are `crossborder`'s | ADR-0075 |

### Phase 8's recorded inputs, disposed

| Input | Disposition |
|---|---|
| "Reconciliation never converts; `CURRENCY_MISMATCH` stays a break" (`PHASE_8_REVIEW.md`) | Kept (ADR-0082). FX legs reconcile as single-currency expectations |
| `CURRENT_STATE.md`'s re-owned row ("Phase 9 has no reconciliation scope in `PHASE_GATES.md`") | The row stays with Phase 15 (it is unrelated to FX). Phase 9's gate gains reconciliation criteria (`PHASE_GATES.md` §Phase 9, the transition's additions) |
| `DELIVERY_PLAN.md` "FX-related reconciliation (Phase 14)" vs `PHASE_8_PLAN.md` §17 (Phase 9) | Resolved: FX trade and corridor reconciliation are Phase 9's; FX P&L reporting and revaluation are Phase 14's. The DELIVERY_PLAN addendum records it |
| The Phase 6 0/3-minor fee-batch deferral, recorded only in BACKLOG | Entered in the debt register by the transition; `P9-TSK-003` pays it |
| `FeeCheck` throws on a cross-currency `ORIGINAL_REF` | A Phase 8 latent defect, **repaired by the transition before the boundary** (§2 above) |
| ADR-0062 §7: "a second outbound rail" triggers payout convergence | **Fired** by the corridor rail; convergence **declined** with reasons (ADR-0079 §9); the trigger re-recorded |
| ADR-0059 §1: a second rail of one model needs per-rail operation lookup | Paid by `P9-TSK-014`: the `RailOperations` directory in `app` |
| ADR-0060 §2: "the phase that makes reachability real stores it" | Paid by `P9-TSK-019` (routing's third subject, `destination_country`, per-candidate reachability) |
| DECISIONS deferred: "recall requests — until a rail that needs them" | Fired by the corridor rail; built by `P9-TSK-024` |
| `CAPABILITY_MAP.md` files FX under "Rails" | FX moves to a new "Foreign exchange" section (quotes, conversion, cover). Cross-border stays under Rails, because a corridor rail is one |
| `ROADMAP.md`'s stale current position and missing Phase 8 dependency | Corrected by the transition |
| `sharedkernel/money/package-info.java` says nothing rounds | Corrected in `P9-TSK-002` |
| The rematch clock-skew debt (Phase 15) | The new sources inherit it. Recorded, unchanged |
| The Phase 5–7 send permits are instance-stamped (ADR-0057 §4's premise) | `X-TSK-013`, scheduled in M9.9 before `P9-TST-001`: every permit database-stamped; the debt row states the bound that holds until it lands (every rail's outcome-deadline margin exceeds the maximum instance skew) |
| Amount-bearing events carry no explicit scale | `X-TSK-014` (owner Phase 15; trigger: the first consumer that reads an amount). Phase 9's own events carry `<x>Scale` from birth (§10) |
| The Phase 5/7 webhook pipelines adopt the callback's own outcome | `X-TSK-015` (owner Phase 15; trigger: a suspected key compromise, or a provider with no inquiry) |

## 3. Bounded contexts and modules

| Context | Module | In Phase 9 |
|---|---|---|
| 15 **FX** (new module) | `fx` | Sole writer: pricing policy (versioned), pair and provider availability, rate snapshots, FX provider declarations and evidence, the FX Quote (+ sourcing steps), the FX Trade, the FX Cover (+ attempts, the execution fact), the trade reversal |
| 16 **Cross-Border Payments** (new module) | `crossborder` | Sole writer: corridor policy (versioned), corridor availability, the Cross-Border Beneficiary, corridor selection, the Payment Offer, the Cross-Border Payment, the cancellation request |
| 9 Payments | `payments` | The **Outbound Credit** (+ its return fact), the corridor rail declaration and the `CorridorRail` port, routing's third subject, `destination_country`, per-candidate reachability, `RefundMode.NONE`, `scheme_execution_claim` and `provider_evidence` widened |
| KYC | `kyc` | **Counterparty Screening** (+ encrypted subject, evidence, review, decision basis) |
| 7 Ledger | `ledger` | JPY/BHD chart; `OwnerKind.COUNTERPARTY` and `ledger.counterparty`; five new purposes; `closedToFreeAdjustments()` |
| Accounts | `accounts` | Add a currency to a wallet product; currency-keyed resolution; open-if-absent |
| 12 Merchant | `merchant` | `MerchantOnboarding` admits JPY/BHD settlement currencies; the Phase 6 0/3-minor ledger fee batch (`P9-TSK-003`). No migration (merchant holds no currency `CHECK`) |
| 13/14 Settlement / Reconciliation | both | `FX_PROVIDER_REPORT`; `SIM_FX_CSV`, `SIM_CORRIDOR_CSV`; FX and corridor vocabulary (`FX_FEE` a priced fee line); the counterparty-keyed descriptor with its settled currencies and `CURRENCY_NOT_SETTLED`; the first-rule-set door path; the new causes, their selection and severities; the waiting-return reader and the reference lookup scoped by source; the resolved-return port |
| 2 Identity | `identity` | The `FX_CONTROLLER` role; five permissions |
| Transfers / Checkout | both | Resolvers only: currency-keyed wallet resolution |

**Build graph.** `fx → ledger, platform, sharedkernel`; `crossborder → ledger, platform,
sharedkernel`; **no build edge between `fx`, `crossborder`, `payments`, `kyc` and `accounts`.**
`FxModuleIsolationTest` and `CrossborderModuleIsolationTest` require exactly `{ledger, platform,
sharedkernel}` and refuse every sibling, each other included, with planted probes; every sibling
isolation test gains both. Every seam is a port declared by its caller, implemented in `app`, and
handed in as a required constructor parameter (the `RailOutcomeObserver` precedent, ADR-0064 §3):

| Port | Declared by | Implemented in `app` over | Purpose |
|---|---|---|---|
| `FxProvider`, `RateSource` | `fx` | the simulated adapters | The provider and reference boundary |
| `ConversionParticipants` | `fx` | `accounts`, the party projection | Owner `ACTIVE` (per decision, no cache); wallet by currency; `openIfAbsent` in the caller's transaction (`INSERT … ON CONFLICT DO NOTHING`, then a re-read) |
| `FxSettlementExpectations` | `fx` | `ReconciliationExpectationRecorder` | Opens the cover legs' expectations in the cover outcome's transaction (ADR-0067) |
| `CrossBorderFx` | `crossborder` | `fx` | Issue a `CROSS_BORDER` quote in crossborder's two transactions (`beginQuote` pinning the pricing version on crossborder's connection, the firm quote, the insert); read a quote for the offer route; `lockForAcceptance`; `acceptWithin` (no claim of its own); the completion's plan lines and trade booking; `abandonWithin` |
| `CrossBorderExecution` | `crossborder` | `payments` | Route and dispatch an outbound credit inside the caller's transaction; request a recall |
| `CorridorDirectory` | `crossborder` | `payments` (`PaymentRails`, `CorridorRail`) | Declared corridor rails' coverage; the beneficiary grant exchange |
| `CounterpartyScreening` | `crossborder` | `kyc` (`CounterpartyScreenings`) | Screen or re-screen a beneficiary, handing kyc the beneficiary's payee-check verdict; read clearance |
| `CrossBorderParticipants` | `crossborder` | `accounts`, the party projection | Owner `ACTIVE`; wallet by currency; `openIfAbsent` in the caller's transaction (returns) |
| `CrossBorderLimitCheck`, `CrossBorderRiskDecision` | `crossborder` | `PermitAllUntilPhase13` (crossborder's own) | The Phase 13 seams, verdict `CrossBorderVerdict {PERMIT, REFUSE}` with the `SeamVerdict` contract |
| `CorridorRail` | `payments` | the simulated corridor adapter | The provider boundary |
| `OutboundCreditComposition` | `payments` | `crossborder` + `fx` (`CrossBorderCompletion`) | `completionLines`/`completed`, `returnLines`/`returned`, `failed`, `delivered` — each in the applier's transaction, past its acting exit, never swallowing a failure (the `CaptureComposition` shape) |
| `CounterpartyScreeningProvider` | `kyc` | `ScreeningAdapter` (a counterparty subject) | The existing screening client and credential |
| `ScreeningOutcomeListener` | `kyc` | `crossborder` | Moves the beneficiary in the screening decision's transaction; a no-op on a `REVOKED` beneficiary |
| `WaitingPayoutReturns` (existing, now scoped) | `reconciliation` | itself (`JdbcWaitingPayoutReturns`), one instance per worker | Waiting `PAYOUT_RETURNED` items **of the sources the worker is handed** (`i.source_id = ANY(?)`): the merchant worker gets the sources settling `PAYOUT_CLEARING`, the corridor worker those settling `CORRIDOR_CLEARING` (any counterparty), both read off the compiled register |
| `ResolvedCorridorReturns` | `reconciliation` | `payments` + `crossborder` (the `SettlementBatchRepudiations` precedent) | Inside the four-eyes approval of a parked corridor return: record the return fact (`applied_by = RESOLUTION`), refund the transfer fee and move the payment to `RETURNED` (§12.9.3) |

**Cross-module transactions, each named and justified** (the ADR-0064 §6 style). No reconciliation
**worker** ever waits on an `fx`, `crossborder`, `payments` or `kyc` lock. Completions, cover
outcomes and inquiry-applied returns only *insert* reconciliation rows, so ADR-0064 §6's no-cycle
argument holds. The one reconciliation transaction that locks a `payments` or `crossborder` row is
a person's four-eyes approval of a parked corridor return (T-g); it takes those rows *after* its
own reconciliation rows, which is the order every other meeting of the two already follows (the
return worker re-reads its item `FOR SHARE` before it touches an outbound credit), so no cycle can
form.

| # | Transaction | Spans | Why one transaction |
|---|---|---|---|
| T-a | Wallet conversion | fx (quote, trade, cover row), accounts (open-if-absent by `ON CONFLICT DO NOTHING` + re-read), ledger (posting) | The customer's commitment and its effect must be indivisible; a wallet opened outside it could outlive a rolled-back conversion |
| T-b | Cross-border authorization (Tx1) | crossborder (payment), fx (quote accept, cover row), payments (routing, outbound credit, hold), ledger (hold) | A dispatch without a hold is unfunded; a hold without a dispatch strands money |
| T-c | Outbound outcome | payments (transition, claim), crossborder (payment), fx (trade, quote), ledger (hold release, posting), reconciliation (expectation insert) | The money fact, its posting and its expectation are one fact (ADR-0067) |
| T-d | Cover outcome | fx (cover, execution fact, unwind row), ledger (posting), reconciliation (expectation inserts) | As T-c |
| T-e | Screening decision | kyc (screening), crossborder (beneficiary) | The beneficiary's payability must never lag its clearance |
| T-f | Return | reconciliation (the item re-read `FOR SHARE`, on the worker's channel only), payments (return fact, claim), crossborder (payment), accounts (open-if-absent), ledger (posting), reconciliation (expectation insert) | As T-c |
| T-g | Parked corridor return resolved | reconciliation (namespace-4 advisory, break, item, suspense, resolution — the existing `TRANSFER_TO_ACCOUNT` approval), payments (return fact, `applied_by = RESOLUTION`), crossborder (payment `RETURNED`), ledger (the transfer and the fee refund, postings last) | The person's decision, the customer's credit and the payment's state are one fact; a resolved return with the payment still `DELIVERED` would show the customer a payment that never came back |

**Isolation.** `settings.gradle.kts` includes both modules; `ProductionModules` picks them up from
the classpath; `NoFloatingPointMoneyRulesTest`'s module guard covers both from `P9-TSK-001`.

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `FxQuote` | `fx` | create (keyed, two-transaction: claim, wire, insert); accept (the conversion door, or cross-border Tx1 through `CrossBorderFx`); cancel (the owner, keyed `fx.quote-cancel`); expire (the sweeper, or a late acceptance) | Scope `fx.quote:<actorType>:<actorId>`, claimed **before** the provider call; the conditional `ISSUED → ACCEPTED` on the database clock; the cap trigger under namespace 5; `UNIQUE (fx.trade.quote_id)` |
| `fx.quote_request` | `fx` | the claim's row: our request reference `QR`, `requested_at`, the **pinned** `pricing_policy_version_id` | A takeover converges on the same `QR` and pinned version; `409 fx.PolicyStale` when the version was superseded before the insert |
| `FxTrade` | `fx` | book (the conversion's transaction, or the outbound credit's completion); reverse (an approved operator reversal, `P9-TSK-025`) | `UNIQUE (quote_id)`; posting key `fx-trade:<tradeId>`; `executed_rate = customer_rate` by `CHECK` |
| `FxCover` (+ `cover_attempt`, `cover_execution`) | `fx` | born `DISPATCHED` in the accepting (or abandonment) transaction with `T₁` minted and stored before any send; send/re-send; inquire; requote after a definitive `REJECTED` via a fresh firm quote; void; execute | `UNIQUE (quote_id, kind)`; `UNIQUE client_reference` per attempt; the database-stamped, strictly-forward send permit; `fx.cover_execution` PK and `UNIQUE (provider_code, provider_trade_ref)`; posting key `fx-cover:<coverId>` |
| `FxTradeReversal` | `fx` | propose; approve; reject (people, four-eyes) | Keyed per principal; partial `UNIQUE (trade_id) WHERE status='PROPOSED'`; ledger `V009`'s one-reversal bound; `reversal:fx-trade:<tradeId>` |
| `PricingPolicyVersion` / `PricingPair` | `fx` | propose; approve (a different person); reject | Keyed; one `PROPOSED` at a time; partial `UNIQUE … WHERE status='ACTIVE'`; retirement only beside its successor (deferred trigger); **no seed exemption** |
| Pair / provider availability (+ `availability_enable_request`) | `fx` | disable (one person + reason, at once); enable (a proposal approved by a different person) | Append-only facts; partial `UNIQUE (subject) WHERE status='PROPOSED'` |
| `RateSnapshot` | `fx` | fetched by the leaderless schedule | `ON CONFLICT (source, pair, observed_at) DO NOTHING`, and only newer than the pair's latest |
| `CrossBorderBeneficiary` | `crossborder` | register (keyed; the grant single-use); revoke (the customer, from every non-terminal state, one identical response); moved by the screening listener | Keyed per principal; the selection pinned at registration and recomputable |
| `CorridorPolicyVersion` / `Corridor` (+ `corridor_enable_request`) | `crossborder` | propose; approve; reject; availability as fx's | As the pricing policy's |
| `PaymentOffer` / `offer_request` | `crossborder` | created with its quote in one transaction (Tx2); frozen | Scope `crossborder.quote:<actorType>:<actorId>`; the **pinned** `corridor_policy_version_id` on `offer_request`; `UNIQUE (quote_id)` |
| `CrossBorderPayment` | `crossborder` | authorize (Tx1, T-b); moved by the outbound credit's appliers; returned by the applier or by `ResolvedCorridorReturns` | Scope `crossborder.payment:<actorType>:<actorId>` (two-transaction); `UNIQUE (payment.quote_id)` |
| `CancellationRequest` | `crossborder` | request (the customer, keyed `crossborder.cancel`, step-up) | A born-once fact: `UNIQUE (payment_id)`, append-only (an every-writer trigger refuses `UPDATE` and `DELETE`) |
| `OutboundCredit` (+ `outbound_credit_return`) | `payments` | born `DISPATCHED` in Tx1 with `E` minted and stored before any send; resolved by `OutboundCreditOutcomes` (answer, inquiry, hinted inquiry); recall; the return fact | `dispatch_key` unique per customer; the database-stamped permit; the claim `(rail, provider_reference)` subject `OUTBOUND_CREDIT`; posting key `outbound-credit:<id>`; `UNIQUE (outbound_credit_return.outbound_credit_id)`; the claim `(rail, return_reference)` subject `CROSSBORDER_RETURN`; posting keys `crossborder-return:<id>`, `crossborder-return-fee:<id>` |
| `CounterpartyScreening` | `kyc` | screen; re-screen; retry `UNAVAILABLE`; review (release or block, a person with a reason) | The screening row's conditionals; `UNIQUE (screening_id, attempt)`; the review keyed |
| `ledger.counterparty` + counterparty accounts | `ledger` | Seeded by migration only, below the UUIDv7 ceiling; **nothing minted at runtime** | Migrations; the `CounterpartyChartGuard` startup refusal |
| Wallet currency | `accounts` | add a currency (`POST …/currencies`, keyed `accounts.add-currency`); `openIfAbsent` on the conversion, return and resolved-return paths | The ledger unique `(owner_ref, CUSTOMER_WALLET, currency)` under `ON CONFLICT DO NOTHING` + re-read; the act whose insert returned the row writes `accounts.WalletCurrencyAdded`, once |

## 5. The lifecycles

Stated in full in `FX_AND_CROSS_BORDER_LIFECYCLES.md`:

- **the FX quote**: `ISSUED → ACCEPTED | EXPIRED | CANCELLED`; `ACCEPTED → EXECUTED | ABANDONED`;
  the four non-`ISSUED` states terminal. `ACCEPTED` is earned: a cross-border quote holds it from
  authorization until the corridor provider accepts or fails; a conversion passes through it
  inside one transaction, writing both history rows. **Expiry is a modelled event, written exactly
  once** — `FxQuoteExpirySchedule` runs the conditional `UPDATE … WHERE status='ISSUED' AND
  expires_at <= statement_timestamp() RETURNING id` in pages, and an acceptance that finds the
  quote past expiry performs the same conditional transition and writes the same event
  (`detectedBy` `SWEEP` | `ACCEPTANCE`); the row lock serialises them. A suspended pair refuses
  acceptance and lets the quote expire;
- **the FX trade**: `BOOKED → REVERSED` (conversions only, by the approved operator reversal);
- **the FX cover**: `DISPATCHED → EXECUTED | REJECTED | UNKNOWN`; `UNKNOWN → EXECUTED | REJECTED`;
  `REJECTED → DISPATCHED` (attempt n+1) `| VOIDED`. `DISPATCHED | UNKNOWN → VOIDED` is **not** an
  edge — a sent attempt may have executed. `EXECUTED` and `VOIDED` terminal. A cover is never
  concluded "never received": it is re-sent under the same `Tn` until the provider knows of it,
  and a new reference is minted only after a definitive `REJECTED`, after a fresh firm quote has
  passed the band (`INV-FX-08`);
- **the trade reversal** and the two **availability-enable proposals**:
  `PROPOSED → APPROVED | REJECTED`, both terminal; approval executes in the approval's
  transaction; four-eyes `CHECK`; one live proposal per subject;
- **the cross-border payment**: `SUBMITTED → IN_TRANSIT | FAILED`;
  `IN_TRANSIT → DELIVERED | RETURNED`; `DELIVERED → RETURNED`. `FAILED` and `RETURNED` terminal.
  `DELIVERED`'s only edge is `RETURNED`: a return is the receiving side's act, admitted whenever
  it arrives (the ADR-0073 rule) — the corridor's declared return window only governs alert
  ageing. `FAILED` reasons: `DECLINED`, `PROVIDER_UNAVAILABLE` (first-send `NOTHING_SENT`),
  `NEVER_RECEIVED`, `RECALLED`. When one inquiry answer reports several facts at once (accepted
  and delivered, or accepted and returned), the payment takes each edge in order inside one
  transaction, one history row and one event per edge. Customer-facing shapes: `PROCESSING`
  (`SUBMITTED`, whatever the credit's state), `SENT`, `DELIVERED`, `RETURNED`, `CANCELLED`
  (`FAILED(RECALLED)`, or `FAILED(NEVER_RECEIVED)` after a cancellation request), `FAILED`;
- **the outbound credit**: `DISPATCHED → RECEIVED | COMPLETED | FAILED | UNKNOWN`;
  `UNKNOWN → RECEIVED | COMPLETED | FAILED`; `RECEIVED → COMPLETED | FAILED`. `COMPLETED` and
  `FAILED` terminal. `RECEIVED` (the provider acknowledged but has not committed) can never
  become `NEVER_RECEIVED`; `NOTHING_SENT` fails only a first send; `NEVER_RECEIVED` requires
  `DISPATCHED` or `UNKNOWN`, re-judged on the locked row past the rail's declared
  `outcomeDeadline` + margin since the **latest** permit; an instruction with a recall requested
  is never re-sent. Facts beside the machine, each set once by a conditional: the provider
  reference, `delivered_at`, the recall columns, the born-once return fact;
- **the beneficiary**: `PENDING_SCREENING → ACTIVE | IN_REVIEW`; `IN_REVIEW → ACTIVE | BLOCKED`;
  `ACTIVE → IN_REVIEW` (a re-screen hit or an unverified payee);
  `PENDING_SCREENING | IN_REVIEW | BLOCKED | ACTIVE → REVOKED` (the customer, with an identical
  response from every state); `REVOKED` terminal — a later screening or review outcome leaves it
  `REVOKED`, and a re-registration is a new beneficiary with a new screening. Customer-facing
  shapes: `PENDING_VERIFICATION` (screening or review), `UNAVAILABLE` (`BLOCKED`);
- **the counterparty screening**: `REQUESTED → CLEAR | IN_REVIEW | UNAVAILABLE`;
  `UNAVAILABLE → CLEAR | IN_REVIEW` (retry); `IN_REVIEW → RELEASED | BLOCKED` (a person, with a
  reason). Every outcome records `decision_basis` (`AUTOMATIC` | `REVIEWER`), the kyc
  `policy_version` and `decided_at`; an `AUTOMATIC` `CLEAR` exists only with a payee `MATCH`;
- **the pricing and corridor policy versions**: `PROPOSED → ACTIVE | REJECTED`;
  `ACTIVE → RETIRED` only beside its successor (a deferred trigger); four-eyes with **no seed
  exemption**;
- **pair, provider and corridor availability**: an append-only fact row per change — disabling
  takes one person and a reason, at once; enabling takes a proposal and a different approver;
- **the cancellation request**: a born-once fact, not a machine; its outcome lives on the
  outbound credit (`recall_outcome`) and the payment.

Every stored machine gets the three-layer enforcement: the aggregate refuses invalid edges; a
generated schema `CHECK` and an every-writer transition trigger; and an append-only `*_event`
history carrying actor, actor type, occurrence time and reason. The quote adds a freeze trigger
over everything but the lifecycle columns, and the cap trigger; the outbound credit freezes `E`,
rail, destination reference, instructed `Money`, held `Money`, hold id and subject.

## 6. Financial invariants Phase 9 must preserve

The in-scope set is **whatever the catalogue marks `Phase: 9`, token-parsed** — the standing rule,
never this list. At planning time that is **fourteen**: `INV-FX-01`…`-09`, `INV-XB-01`…`-04` and
`INV-ACC-01`.

**New invariants** (ten), each entering the catalogue with Statement, Why, Enforce, Verify and
`Phase: 9`:

- `INV-FX-04` — an FX quote is a frozen posting plan, balanced per currency; executed at most
  once, only while valid on the database clock, only by its owner; the trade posts exactly the
  quote's stored amounts.
- `INV-FX-05` — every executed conversion carries its complete, frozen rate provenance, and
  replaying it reproduces its posted entry exactly, and its internal rate and disclosed margin,
  each derived with a named rounding and never left to a column.
- `INV-FX-06` — per currency, `FX_POSITION` equals the sum of its open legs and is zero at rest
  once covers execute; spread, residual and realised results equal their trades' and executions'
  stored parts; these books have one poster and accept no free adjustment.
- `INV-FX-07` — a conversion's residual is computed from stored amounts, bounded (\|r\| ≤ 2 by
  `CHECK`; ≤ 1 under half policies by the domain), and posted to `ROUNDING_RESIDUAL` in its own
  currency, never folded into margin, customer amount or position.
- `INV-FX-08` — a provider FX execution carries a reference stored before sending; it is re-sent
  only under that reference; a new reference is minted only after a definitive rejection; a cover
  always closes exactly its plan's position legs, the difference posted as realised FX result.
- `INV-FX-09` — a customer's booked conversion never waits on, and is never changed by, a
  provider outcome.
- `INV-XB-01` — a cross-border payment is priced by one accepted quote, holds the customer's
  funds until the corridor provider accepts, then posts its debit, conversion, fee and clearing
  credit in one entry, once; a payment that fails debits the customer nothing.
- `INV-XB-02` — funds are instructed, and offers priced, only for a beneficiary that is `ACTIVE`
  with a current `CLEAR` or person-`RELEASED` screening, on an available corridor; an automatic
  `CLEAR` exists only where the provider's payee check matched; a hit, an indeterminate result or
  an unverified payee is never auto-cleared or auto-rejected; every non-payable state is refused
  with the same response.
- `INV-XB-03` — what the customer was shown (destination amount, fee, total debit) is exactly
  what is held, posted and instructed.
- `INV-XB-04` — a return is applied once; automatically only when it is exactly the instructed
  credit coming back (the instructed currency and amount), credited in that currency and never
  re-converted at the original rate, with the fee refunded; any other return parks with its
  break, and a person's resolution is the only way it reaches the customer, recording the return
  on the payment in the same transaction.

**Amended at the transition, each with provenance** (thirteen): `INV-FX-01` (Enforce gains the
`STATIC` one-poster rule and the FX books proof); `INV-FX-02` (the plausibility band against an
independent fresh reference; validity from durations on the database clock; a client rate
*refused* with `422`, not ignored); `INV-FX-03` (spread and markup on one revenue line, the
attribution stored); `INV-ACC-01` (five currencies at 0/2/3 minor units); `INV-BAL-03` (the FX
residual's destination); `INV-HIST-04` (pricing policy, corridor policy and rounding names as
subjects); `INV-RAIL-02` (corridor selection pinned at registration; the outbound credit routed
once with per-candidate reachability); `INV-RAIL-03` (provider-attested country, currency and
entity type admitted; names held only by kyc, encrypted); `INV-RAIL-04` (a clearing position is
(purpose, counterparty) for counterparty-owned purposes; still never nets two counterparties);
`INV-SET-05` (one source per (purpose, counterparty)); `INV-PAY-04` (the FX provider's `T` and
the corridor's `E` and recall); `INV-KYC-01` (a counterparty screening's provider verdict is
evidence; every outcome records its decision basis, policy version and time); `INV-KYC-04`
(extended to counterparty screening, an unverified payee included).

The catalogue goes from **110 to 120 invariants**, and DECISIONS' count line is restated.
Protected throughout: `INV-MON-*`, `INV-LED-*`, `INV-BAL-*`, `INV-IDEM-*`, `INV-CON-01`/`-02`,
`INV-LIFE-*`, `INV-REV-*` (`INV-REV-03`: a cross-border payment is never reversed; a return is
the receiving side's act), `INV-PAY-*`, `INV-RAIL-*`, `INV-SET-01`/`-02`/`-05`/`-06`/`-07`,
`INV-REC-*` (reconciliation never converts, `INV-MON-04`), `INV-KYC-01`/`-04`/`-05`,
`INV-AUD-*`, `INV-EVT-*`, `INV-HIST-*`.

## 7. Multi-instance architecture

Every instance runs everything. There is no leader, no lease and no in-process truth. Every
window is judged **in SQL on the database clock**: quote validity, reference staleness, screening
validity, sweep bounds and the `NEVER_RECEIVED` deadline. Every send permit is database-stamped
(`statement_timestamp()` inside the conditional, strictly forward, held by a trigger), which
leaves ADR-0057 §4's skew premise; `X-TSK-013` aligns the Phase 5–7 permits in M9.9.

**Schedules.** Each is a `SmartLifecycle` on `scheduleWithFixedDelay`, refuses zero bounds,
contains failures per row, works oldest first, and has its own `sweeper.enabled` gauge. Each
joins `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` and gets a
`DISTRIBUTED_EXECUTION.md` §3 row, written *planned* by the transition and rewritten by the task
that builds it:

| Schedule (module) | Legs | Coordination | Gauge (task) |
|---|---|---|---|
| `FxRateFetchSchedule` (`fx`) | fetch reference snapshots | `rate_fetch_permit` forward renewal paces it; `ON CONFLICT DO NOTHING` plus the newer-than-latest rule | `finapp.fx.rate.sweeper.enabled` (`P9-TSK-005`) |
| `FxQuoteExpirySchedule` (`fx`) | `ISSUED → EXPIRED` and its event | Conditional update in pages | `finapp.fx.quote.expiry.sweeper.enabled` (`-008`) |
| `FxCoverSchedule` (`fx`) | send/re-send, inquire, requote (fresh firm quote, then `T(n+1)`), dispatch unwinds | Cover rows `FOR UPDATE SKIP LOCKED`; the permit judged on the locked row | `finapp.fx.cover.sweeper.enabled` (`-012`) |
| `OutboundCreditResolutionSchedule` (`payments`) | inquire `DISPATCHED`/`UNKNOWN`/`RECEIVED`; conclude `NEVER_RECEIVED`; send recalls; delivery follow-up | The `WithdrawalResolution` shape; permit and recall permit re-judged on the locked row | `finapp.payments.outbound.sweeper.enabled` (`-020`) |
| `OutboundReturnWorker` (**`app`**, the `PayoutReturnSweep` precedent: it spans reconciliation's waiting items, settlement's acceptance date and payments' applier) | apply `PAYOUT_RETURNED` items from the corridor sources only (the scoped reader, §12.9.2), operation-anchored; defer while the credit is non-terminal | ADR-0073's shape: the item re-read `FOR SHARE`, the born-once fact, the claim | `finapp.payments.outbound.return.sweeper.enabled` (`-023`) |
| `CounterpartyScreeningRetrySchedule` (`kyc`) | retry `UNAVAILABLE` screenings | Screening row conditionals | `finapp.kyc.counterparty.sweeper.enabled` (`-016`) |

`crossborder` owns no schedule.

**Arbiters.** Every contended decision has a PostgreSQL arbiter and a counted ten-way test. The
six **born-once arbiters**, marked ★, each also survive a lock-bypass probe with the Java guard
removed.

| Contention | Arbiter | The loser | Counted test (task) |
|---|---|---|---|
| Ten quote requests, one key | The idempotency claim per principal, taken **before** the provider call | Replay or `IdempotencyInProgress`; provider RFQ count 1 | `-008` |
| Concurrent quotes past the cap | A `BEFORE INSERT` trigger on `fx.quote` taking `pg_advisory_xact_lock(5, hashtext(owner_party_id))` and counting `status='ISSUED' AND expires_at > statement_timestamp()`, for every writer (the Tx1 pre-check only spares provider calls) | `429 fx.TooManyOpenQuotes`; 10 racers at 4 live → exactly 1 issued; an owner at the cap makes no provider call; a lapsed, unswept quote does not count | `-008` |
| Ten accepts of one quote (any keys) | The conditional `ISSUED → ACCEPTED WHERE expires_at > statement_timestamp()`; ★ `UNIQUE (fx.trade.quote_id)`; ★ `UNIQUE (crossborder.payment.quote_id)` | `409 fx.QuoteAlreadyAccepted`, or replay; 1 trade, 1 entry, 1 cover | `-009`, `-019` |
| Accept vs expiry sweeper vs cancel | The quote row lock; complementary clock predicates on `status='ISSUED'` | Exactly one terminal path; the event once (200 quotes × (10 acceptors + 10 sweepers)) | `-008`, `-009` |
| Concurrent debits of one wallet (conversion, cross-border hold, withdrawal, transfer, wallet payment) | `ledger_account` `FOR UPDATE` (ADR-0039); availability = derived − holds, judged under it (`INV-BAL-04/05`) | `422` insufficient funds; the quote stays `ISSUED`; never negative | `-009`, `-019` |
| Ten concurrent openers of one wallet currency (conversions, returns, the add-currency door) | The ledger unique `(owner_ref, CUSTOMER_WALLET, currency)` under `INSERT … ON CONFLICT DO NOTHING`, then a re-read, in the caller's transaction | Waits for the winner, reads its row, never aborts; one account, one `WalletCurrencyAdded` | `-004` (`WalletOpenIfAbsentRaceDatabaseTest`), `-009` |
| Ten instances sending one cover | The database-stamped, conditional, forward-only permit; provider dedupe on `T` | Does not send; simulator execution count 1 | `-012` |
| Cover outcome: sync answer vs inquiries vs hinted inquiries | The cover row `FOR UPDATE` + conditional; ★ the `fx.cover_execution` PK and `UNIQUE (provider_code, provider_trade_ref)`; posting key `fx-cover:<id>` | Records nothing | `-012` |
| Requote | The conditional `REJECTED → DISPATCHED` on the locked row; `UNIQUE (cover_id, attempt)`; `UNIQUE client_reference` | 0 rows | `-012` |
| Unwind creation (cover applier vs abandonment or reversal writer) | The lock order quote → trade → cover; ★ `UNIQUE (fx.cover.quote_id, kind)` | Converges; exactly one unwind | `-021` |
| Ten cross-border authorizations | The claim per principal; the quote conditional; the hold under the wallet lock; `dispatch_key` unique per customer | Replay or 409; 1 payment, 1 hold, 1 outbound credit, 1 cover | `-019` |
| Authorization (Tx1) vs a screening hit (T-e) on its beneficiary | Tx1 holds the beneficiary `FOR SHARE`; T-e needs it `FOR UPDATE`; each re-judges on the locked row | Coherent: authorized while `ACTIVE` and clear, then the beneficiary `IN_REVIEW`; or refused `BeneficiaryNotPayable`. Never a Tx1 committed against an `IN_REVIEW` beneficiary | `-019` |
| Beneficiary revocation vs a screening or review decision | The beneficiary row `FOR UPDATE` in both; conditional edges; the listener a no-op on `REVOKED` | Either order coherent; a revoked beneficiary never becomes `ACTIVE` | `-017` |
| Outbound credit: takeover re-send vs resolution sweep | The permit re-judged on the locked row; first-send-only `NOTHING_SENT`; `NEVER_RECEIVED` only past the declared deadline + margin since the **latest** permit (ADR-0057 §4) | Either order is safe; provider instruction count 1 | `-019`, `-020` |
| Outbound outcome appliers (answer, inquiry, hinted inquiry) | The row `FOR UPDATE` + conditional; ★ the `payments.scheme_execution_claim (rail, provider_reference)` PK, subject `OUTBOUND_CREDIT`; the posting key; the expectation unique | Records nothing; 1 entry, 1 fee line, 1 expectation | `-020` |
| Recall vs acceptance | The provider decides; one conditional transition wins locally; no re-send after a recall request | Coherent: `COMPLETED` (recall `TOO_LATE`) or `FAILED(RECALLED)`, never both | `-024` |
| Return: hinted inquiry vs settlement-line worker vs ten redeliveries | ★ `UNIQUE (outbound_credit_return.outbound_credit_id)`; the claim `(rail, return_reference)` subject `CROSSBORDER_RETURN`; the posting key; the item re-read `FOR SHARE` (ADR-0073 §7) | Converges; 1 return, 1 credit, 1 fee refund | `-023` |
| A parked return's resolution vs an inquiry-applied return | The same ★ unique, inserted by the port before the transfer posts; reconciliation's namespace-4 advisory and the item lock against the rematch leg | One wins: the approval (the applier then writes nothing) or the inquiry (the approval rolls back `409 ResolutionStale`, the break closes `EVIDENCED`); never two credits | `-023` |
| Ten sweepers escalating one overdue FX leg whose pair is allocated | The namespace-4 advisory first; the expected-value severity step (`… WHERE severity <> 'CRITICAL'`); reconciliation `V004`'s severity-only-rises trigger | 0 rows; one `SEVERITY_ESCALATED` event | `-013` |
| Screening answers vs review | The screening row lock + conditionals; `UNIQUE (screening_id, attempt)`; then the beneficiary conditional | 409 or converges | `-016`, `-017` |
| Policy activation (pricing, corridor, first rule set) | Partial `UNIQUE … WHERE status='ACTIVE'`; `CHECK` approver ≠ proposer; conditional activation; retirement only beside its successor (deferred trigger) | 409; 10 approvers → 1 activation | `-007`, `-011`, `-015` |
| Trade-reversal approvals | Partial `UNIQUE (trade_id) WHERE status='PROPOSED'`; conditional approve; ledger `V009`'s one-reversal bound (namespace 2); `UNIQUE (quote_id, kind)` | 409 or converges | `-025` |
| Ten instances fetching rates | The permit paces; the snapshot unique | No duplicate | `-005` |
| Hot projection rows | The projection's row lock, in account-id order (seeded counterparty ids sort first); postings last; `lockBalancesInOrder` for multi-entry transactions | Waits, never deadlocks | storm |
| Stale rate reads | Nothing executable is cached; the reference and policy version are read inside the transaction and pinned | Refused | `-005`, `-008` |
| Restart mid-flow | Every state is a committed row; every leg resumes from rows | — | storm crash points |
| Message duplication | The outbox is at least once; **Phase 9 has no Kafka consumer** and no correctness rests on an event (`INV-EVT-04`) | — | — |

**The global lock order** is one `DISTRIBUTED_EXECUTION.md` §3 row, followed by every writer:

0. *(T-g, and the return worker's T-f, only)* reconciliation's namespace-4 advisory and its own
   rows (break, item, suspense, resolution; the worker's item `FOR SHARE`), before any row below.
1. Advisory namespace 5 (the quote insert only, inside the trigger).
2. `kyc.counterparty_screening` (screening decisions only).
3. Subject rows `FOR UPDATE`: **outbound credit → payment → quote → trade → cover**.
4. The beneficiary (`FOR SHARE`; `FOR UPDATE` only in T-e, which holds nothing from step 3), then
   pair, provider and corridor availability `FOR SHARE`.
5. Customer wallet `ledger_account` rows `FOR UPDATE`, sorted by id.
6. Ledger projection rows: **postings last**.

After that come only inserts of new rows (trade, execution fact, claims, expectations, events,
audit). An applier that starts from a cover reads it unlocked, then locks along the order and
re-judges.

**Advisory namespace 5** (the open-quote cap, `hashtext(owner_party_id)`, blocking, inside the
trigger) is registered in `DISTRIBUTED_EXECUTION.md` §3 and pinned by `FxMigrationTest`. It
*arbitrates* the cap, whereas namespace 4 only orders.

**Isolation** is `READ COMMITTED`, plus row locks, conditionals and uniques (ADR-0039). Proofs
and reports run in one `REPEATABLE READ` snapshot. **Recovery** is from rows. **Consistency:** a
customer's balances are strongly consistent at commit; the platform's provider legs and
settlement are eventually consistent, and their gap is always explained (§12.9.4).

**Would FX and cross-border processing remain financially correct if ten instances executed the
same operation concurrently? — `PASS`, resting on counted tests, never on construction.** Every
contention above has a counted ten-way race in its task; each of the six born-once arbiters has a
lock-bypass probe; and `P9-TST-001` races two application contexts with clocks skewed by ±5 s
plus ten movers on the same quote, cover, payment, recall and return. Any task whose counted
tests do not exist answers `UNKNOWN`, and is not complete.

## 8. Data architecture

Every table arrives with the task that creates it, with its `DATA_CLASSIFICATION.md` §4 rows in
the same change (`ColumnClassificationTest`). Money is `amount_minor BIGINT` / `currency CHAR(3)`
/ `scale SMALLINT` (ADR-0003), a signed amount (residual, realised result) a signed
`_amount_minor` beside its currency and scale; rates are `NUMERIC(20,10)` embedded verbatim from
platform `RateColumns.ddl()` and margins `NUMERIC(7,6)`; ids are UUIDv7 (ADR-0013); times come
from the injected clock while windows are judged on the server clock; migrations are forward-only
(ADR-0011) with explicit SQL (ADR-0033); no cross-schema foreign key; every machine has a
generated `CHECK`, an every-writer transition trigger and a `*_event` history; every vocabulary
addition is appended at the end of its enum (the `EnumSet.range` rule) with the mirror guard.

**The migration table** (expected order). The Phase 8 → 9 transition's repairs hold
reconciliation `V016`–`V019` and settlement `V011`–`V014` (`V011`–`V012` from the first repair
round, `V013` and `V014` from its second and re-gate rounds; merchant `V009` was reserved and
never needed), so Phase 9's first numbers are reconciliation `V020` and settlement `V015`, and `ledger V019`, `payments V024`, `identity V019` and `kyc V009`
are the next free numbers. Each migration takes the next free number when it lands, and this
table is corrected with provenance if the order changes (ADR-0011).

| Schema | Version | Task | Content |
|---|---|---|---|
| `fx` | `V001` | `-001` | Schema floor (no tables) |
| `crossborder` | `V001` | `-001` | Schema floor (no tables) |
| `ledger` | `V019` | `-003` | The thirteen operational purposes × JPY, BHD (26 accounts below the ceiling) |
| `fx` | `V002` | `-005` | `rate_snapshot`, `rate_fetch_permit` |
| `fx` | `V003` | `-006` | `fx_provider_evidence` (encrypted; named with its domain because the classification register keys on `table.column` and `payments.provider_evidence` exists - `P9-TSK-006`) |
| `identity` | `V019` | `-007` | The `FX_CONTROLLER` role *(written `V018`; renumbered `V019` when this plan merged, identity `V018` being the reconciliation controller role there)* |
| `fx` | `V004` | `-007` | Pricing policy (`spread + markup > 0`, per-pair rate scale), pair and provider availability, `availability_enable_request` |
| `fx` | `V005` | `-008` | `quote` (plan, residual, freeze, edge and live-quote cap triggers), `quote_event`, `quote_request` (pinned pricing version), `quote_sourcing_step` |
| `fx` | `V006` | `-009` | `trade`, `cover`, `cover_attempt` |
| `ledger` | `V020` | `-009` | `FX_SPREAD_REVENUE`; the `closedToFreeAdjustments()` binding restated |
| `ledger` | `V021` | `-010` | `OwnerKind.COUNTERPARTY`, `ledger.counterparty`, the four constraints, the `owner_ref` trigger; *as built, also the purpose `FX_PROVIDER_CLEARING` with no account, so every counterparty rule is proven against a real purpose* |
| `ledger` | `V022` | `-011` | counterparty `fx-sim-a` and its five `FX_PROVIDER_CLEARING` accounts (the purpose admitted by `V021`) |
| `settlement` | `V015` | `-011` | `FX_PROVIDER_REPORT`, `SIM_FX_CSV`, `FX_SOLD`/`FX_BOUGHT`/`FX_FEE`, `COVER_REF`/`FX_TRADE_REF`, `RejectionCode.CURRENCY_NOT_SETTLED`, source row `fx-sim-a.trade-report` |
| `reconciliation` | `V020` | `-011` | `FX_SELL_LEG`/`FX_BUY_LEG`, key `COVER_REF`, the three FX line types in the item and rule `CHECK`s, `FX_FEE` in `provider_fee_line_type`, causes `FX_LEG_DIFFERS`/`VALUE_DATE_DIFFERS`, the pairing trigger and the `V014` list restated |
| `fx` | `V007` | `-012` | `cover_execution`, the realised result |
| `ledger` | `V023` | `-012` | `FX_REALISED_GAINS`, `FX_REALISED_LOSSES` |
| `payments` | `V024` | `-014` | `RefundMode.NONE`, `RoutingRejection.DIRECTION_UNSUPPORTED` (the enum `CHECK`s regenerated) |
| `ledger` | `V024` | `-014` | `CORRIDOR_CLEARING`, counterparty `corridor-sim-a` and its three accounts |
| `settlement` | `V016` | `-014` | `SIM_CORRIDOR_CSV` under `PAYOUT_PROVIDER_REPORT`, source row `corridor-sim-a.settlement` |
| `reconciliation` | `V021` | `-014` | `CROSSBORDER_PAYOUT`, `CROSSBORDER_RETURN` and their mirrors |
| `crossborder` | `V002` | `-015` | Corridor policy, corridor availability, `corridor_enable_request` |
| `kyc` | `V009` | `-016` | `counterparty_screening` (encrypted subject, attempts, evidence, review, decision basis, policy version, payee verdict) |
| `crossborder` | `V003` | `-017` | `beneficiary` (revocation from every state), `corridor_selection` and its steps |
| `crossborder` | `V004` | `-018` | `offer_request` (pinned corridor version), `payment_offer` |
| `payments` | `V025` | `-019` | `outbound_credit`; routing's third subject, `destination_country`, per-step reachability; `routing_rule.requires_destination_country`; `provider_evidence.outbound_credit_id` (the six-subject `CHECK`) |
| `crossborder` | `V005` | `-019` | `payment`, `payment_event` |
| `payments` | `V026` | `-020` | `scheme_execution_claim` subject `OUTBOUND_CREDIT` |
| `payments` | `V027` | `-023` | `outbound_credit_return` (`applied_by`, `resolution_id`, the applier-amount trigger); claim subject `CROSSBORDER_RETURN` |
| `crossborder` | `V006` | `-024` | `cancellation_request` (born once) |
| `fx` | `V008` | `-025` | `trade_reversal` |
| `ledger` | `V025` | `-026` | Counterparties `fx-sim-b`, `corridor-sim-b` and their accounts |
| `settlement` | `V017` | `-026` | The `-b` source rows |
| `merchant` | — | — | None planned (the next free number would be `V009`: the transition reserved it and never needed it) |

`P9-TSK-013`, `-021`, `-022` and the test, review and observability tasks add no migration.
**No migration seeds a rule set or a policy version** (D26; the four-eyes doors of §9).

**Seeding.** Every counterparty registry row and clearing account is seeded by the migration that
admits its counterparty, one account per declared currency, with hand-minted UUIDv7 ids stamped
`2026-09-27T12:00:00Z` — below the ceiling, so `everySeededIdSortsBeforeEveryRuntimeId` holds.
Nothing is minted at runtime; a `CounterpartyChartGuard` refuses startup when a declared
counterparty, or a counterparty × declared currency, lacks its row or account.
`OperationalChartMigrationTest` gains the counterparty part.

**Classification.** The beneficiary's name transits `crossborder` and is stored only by kyc,
encrypted with AAD bound to the screening id (`RESTRICTED-PII`); provider references
`CONFIDENTIAL`; amounts `RESTRICTED-FINANCIAL`, never in logs, traces, metrics or events; quotes
and rates `CONFIDENTIAL` and owner-scoped; the nickname and the customer reference screened for
PAN and IBAN shapes; every new column with its `DATA_CLASSIFICATION.md` row.

## 9. API architecture

**Conventions for every route:** money amounts are exact decimal strings plus currency, in and
out; rates appear **in responses only**, and no `fx` or `crossborder` request body has a rate
field — an unknown field, `rate` included, is `422 VALIDATION_FAILED` (strict deserialisation),
proven statically by `RatesAreNeverClientSuppliedTest` over every request record and by an
OpenAPI request-schema guard, each with a planted violation (`P9-TSK-008`, extended by `-009`,
`-018`, `-019`, `-024`). Every keyed scope is `<command>:<actorType>:<actorId>`, per principal
from birth; a key reused with a different body is `409 platform.IdempotencyConflict`, a key in
flight `409 platform.IdempotencyInProgress`. The OpenAPI baseline grows by addition only,
regenerated and diffed structurally. Every synchronous route that calls a provider states a
per-call and a total budget and holds no connection while it waits; a budget exhausted is the
call's `INDETERMINATE`, never a success. Lists are bounded at 100 with `truncated`.

| Operation | Door | Idempotency | Authorization | Behaviour |
|---|---|---|---|---|
| Request a quote | `POST /v1/me/fx/quotes {sourceCurrency, destinationCurrency, fixedSide, amount}` | Required, `fx.quote`, claimed **before** the provider call | Session; owner `ACTIVE` | Synchronous (2 s per candidate, 5 s total): `201` with the customer rate, amounts, disclosed margin over mid, `expiresAt`. `422 fx.PairNotOffered`/`fx.AmountOutOfRange`, `409 fx.PairSuspended`/`fx.PolicyStale` (retry with a new key), `429 fx.TooManyOpenQuotes`, `503 fx.RateUnavailable` (retryable, new key) |
| Read / cancel a quote | `GET /v1/me/fx/quotes/{id}`; `POST …/{id}/cancellation` | Cancel keyed, `fx.quote-cancel` | Owner (else a uniform `404`) | Shows `EXPIRED` once past `expires_at`, before the sweeper writes it; `409 fx.QuoteNotCancellable` |
| Discover pairs | `GET /v1/me/fx/pairs` | Read | Session | The active policy's pairs × availability, with bounds |
| Convert | `POST /v1/me/fx/conversions {quoteId}` | Required, `fx.convert`; posting key `fx-trade:<id>` | Owner, `ACTIVE` | **Synchronous, final** (no provider call precedes it): `201` with the trade. `409 fx.QuoteExpired`/`QuoteAlreadyAccepted`/`QuoteNotAcceptable`/`PairSuspended`, `422 fx.QuoteKindMismatch`/`SourceWalletMissing`/`InsufficientFunds` |
| Read a conversion | `GET /v1/me/fx/conversions/{tradeId}` | Read | Owner | — |
| Add a wallet currency | `POST /v1/me/accounts/{id}/currencies {currency}` | Required, `accounts.add-currency`; converges on the ledger unique | Owner | `201`, or the existing account; `422 accounts.UnsupportedCurrency` |
| Balances per currency | `GET /v1/me/accounts/{id}/balances` | Read | Owner | One balance per currency, never summed; the existing `/balance` keeps its shape and answers the first-opened currency |
| Discover corridors | `GET /v1/me/cross-border/corridors` | Read | Session | Active, available corridors whose rail is declared by the running build |
| Register a beneficiary | `POST /v1/me/cross-border/beneficiaries {country, currency, grant, name, nickname, entityType, acknowledgeNoMatch?}` | Required, `crossborder.beneficiary` (the grant is single-use) | Owner; step-up if a factor is enrolled | Synchronous exchange (3 s) then screening (3 s), no connection held: `201` with `PENDING_VERIFICATION` or `ACTIVE`. `422 crossborder.CorridorNotOffered`/`NoMatchUnacknowledged`, `403 identity.AssuranceRequired`, `503 crossborder.ProviderUnavailable` |
| Read / revoke a beneficiary | `GET …/beneficiaries[/{id}]`; `POST …/{id}/revocation` | Revocation keyed | Owner | Shaped status (tipping-off); revocation admitted from every state but `REVOKED`, answering the same `200 {status: REVOKED}` from each |
| Cross-border quote (the offer) | `POST /v1/me/cross-border/quotes {beneficiaryId, fixedSide, amount}` | Required, `crossborder.quote`, claimed before any call; both policy versions pinned at the claim | Owner | Synchronous (re-screen 3 s when the clearance lapsed; firm quote 2 s per candidate, 5 s total; 8 s for the route): the offer — customer rate, disclosed margin, fee, total debit, guaranteed destination amount, estimate, `validUntil`. `422 crossborder.BeneficiaryNotPayable` (byte-identical for every non-payable state)/`AmountExceedsCorridorLimit`, `409 crossborder.PolicyStale`, `503 crossborder.ScreeningUnavailable`, plus the FX quote errors |
| Read the offer | `GET /v1/me/cross-border/quotes/{id}` | Read | Owner (uniform `404`) | Served by `crossborder`, which reads the quote through `CrossBorderFx` (`fx` has no edge to `crossborder`) |
| Authorize a payment | `POST /v1/me/cross-border/payments {quoteId}` | Required, `crossborder.payment` (two-transaction); `dispatch_key`; `E` | Owner; step-up | **Asynchronous**: `202 {paymentId, status}` (`PROCESSING`, or `SENT`/`FAILED` if the provider answered within the bound — the cover first, 2 s, then the corridor, 3 s; 6 s for the route). `409 fx.QuoteExpired`/`QuoteAlreadyAccepted`/`crossborder.ScreeningRequired`, `422 crossborder.BeneficiaryNotPayable`/`LimitRefused`/`RiskRefused`/`fx.InsufficientFunds`, `503 crossborder.CorridorUnavailable`, `403 identity.AssuranceRequired` |
| Follow a payment | `GET /v1/me/cross-border/payments/{id}` | Read | Owner | The shaped status; amounts, rate, fee, our reference |
| Cancel a payment | `POST …/payments/{id}/cancellation` | Required, `crossborder.cancel`; the recall idempotent at the provider by `E` | Owner; step-up | **Asynchronous**: `202 {status: PROCESSING, cancellationRequested: true}`; the outcome (`CANCELLED`, or `SENT` on `TOO_LATE`) arrives on `GET`. `409 crossborder.NotCancellable` |
| Returns / reversals | — | — | — | **No customer door.** Returns arrive from the provider's evidence; a conversion is final for the customer |
| Pricing policies | `GET/POST /v1/operator/fx/pricing-policies`, `POST …/{id}/approval`, `…/{id}/rejection` | Proposal keyed per principal | `FX_ADMINISTER` | Four-eyes; one proposal at a time; provider codes declared by the build. `409 fx.SelfApprovalRefused`/`ProposalNotPending`/`PolicyStale`, `422 fx.ProviderNotDeclared` |
| Pair / provider availability | `POST /v1/operator/fx/pairs/{pair}/availability`, `…/fx/providers/{code}/availability` (+ `…/enable-requests/{rid}/approval\|rejection`) | Keyed | `FX_ADMINISTER` | Disable: one person + reason, at once; enable: a proposal approved by a different person |
| Corridor policies and availability | `GET/POST /v1/operator/cross-border/corridor-policies[…]`, `POST …/corridors/{id}/availability[…]` | Keyed | `CROSSBORDER_ADMINISTER` | Four-eyes; the rail declared and covering D; required data satisfiable. `409 crossborder.SelfApprovalRefused`/`ProposalNotPending`, `422 crossborder.RailNotDeclared` |
| Screening review | `POST /v1/operator/kyc/counterparty-screenings/{id}/decision {RELEASE\|BLOCK, reasonCode, narrative}` | Keyed | `COUNTERPARTY_SCREENING_REVIEW` | Synchronous; the beneficiary moves in the same transaction. `409 kyc.ScreeningNotInReview` |
| FX trade reversal (`P9-TSK-025`) | `POST /v1/operator/fx/trades/{id}/reversal`, `…/reversal/{rid}/approval\|rejection` | Keyed | `FX_TRADE_REVERSE` | Four-eyes; trade `BOOKED`, purpose `CONVERSION`; the unwind cover is asynchronous. `409 fx.TradeNotReversible`/`SelfApprovalRefused`/`ProposalNotPending` |
| First rule sets | `POST /v1/operator/reconciliation/rule-sets` (existing) | Keyed | `RECONCILIATION_ADMINISTER` | **Now admits a version 1 for a source with no `ACTIVE` version**, four-eyes as any other; `409 reconciliation.RuleSetExists` |
| Reports, provenance, trace | `GET /v1/operator/reports/fx/position?asOf=`, `…/fx/revenue?month=`, `…/cross-border/corridors?month=`; `GET /v1/operator/fx/trades/{id}/provenance`; `GET /v1/operator/cross-border/payments/{id}/trace` | Read | `FX_INVESTIGATE`, audited | One `REPEATABLE READ` snapshot; bounded at 100 with `truncated`; the `ReportRead` audit in the read's transaction; a bad period `422` before any read |
| Provider webhooks | `POST /v1/providers/fx/webhooks`, `POST /v1/providers/payments/corridor/webhooks` | Inbox dedupe | HMAC + freshness (`@Unauthenticated` + signature) | `202`; the hint triggers an inquiry; `401` unsigned or stale, nothing written; an unknown reference is evidence only |

Routing policy v5 goes through ADR-0060's existing single-person door
(`POST /v1/operator/routing-policy/versions`, `PAYMENT_ROUTING_ADMINISTER`, a reason, audited),
never a migration (D26); its request body gains the optional `requiresDestinationCountry`
(additive). New `ERROR_CONTRACT.md` codes join with the task that raises each;
`ERROR_CONTRACT.md` is the one list.

## 10. Event architecture

Terminal facts publish (ADR-0044's doctrine), written through the outbox on the acting connection
(`INV-EVT-01`) with the full envelope (`INV-EVT-03`), event version 1 and schema version 1.
Payloads carry identifiers, enums, and minor-unit strings with `Currency` **and `Scale`** (the 0-
and 3-minor currencies make the scale explicit; `X-TSK-014` retrofits the pre-Phase-9 amount
events, owner Phase 15). **No rate, no name, and no provider reference value travels on any
event**: `EventPayload` cannot carry a decimal and is not extended — a consumer needing the rate
reads the trade from `fx` by identifier. At least once, `INV-IDEM-04`; no consumer decides
anything financial (`INV-EVT-04`); Phase 9 has no Kafka consumer.

| Event | Producer / aggregate | Fact (payload beyond ids) | Causation |
|---|---|---|---|
| `fx.FxQuoteIssued` | fx / quote | purpose, pair, fixed side, source/destination amounts, `expiresAt`, policy version | the request |
| `fx.FxQuoteAccepted` | fx / quote | subject kind and id | the acceptance command |
| `fx.FxQuoteExpired` | fx / quote | `detectedBy` (`SWEEP` \| `ACCEPTANCE`) — the gate's modelled event | `FxQuoteIssued` |
| `fx.FxQuoteCancelled` / `fx.FxQuoteAbandoned` | fx / quote | reason | the cancellation / the failed payment |
| `fx.FxTradeExecuted` | fx / trade | purpose, source/destination amounts, posting reference. **Replaces DELIVERY_PLAN's `CurrencyConverted`** (one fact, one event) | `FxQuoteAccepted` |
| `fx.FxTradeReversed` | fx / trade | reversal reference | the approval |
| `fx.FxCoverExecuted` / `FxCoverRejected` / `FxCoverRequoted` | fx / cover | kind, attempt, provider code, executed amounts, realised-result direction (never the amount as a metric) | `FxQuoteAccepted` for a `COVER`; the `FxQuoteAbandoned` or `FxTradeReversed` for an `UNWIND` |
| `fx.FxAvailabilityChanged` | fx / pair or provider | subject, enabled or disabled | the disabling act, or the enable proposal's approval |
| `fx.PricingPolicyActivated` | fx / policy | version id | the approval |
| `crossborder.CrossBorderPaymentInitiated` | crossborder / payment | beneficiary, corridor, quote ids, total debit, destination amount | the authorization |
| `crossborder.CrossBorderPaymentInTransit` / `Delivered` / `Failed(reason)` / `Returned` | crossborder / payment | returned amount and basis (`APPLIED` \| `RESOLVED`) where applicable | the outbound credit's outcome (same transaction); for a `RESOLVED` return, the resolution's approval. One answer reporting several facts writes one event per edge, in order |
| `crossborder.CrossBorderCancellationRequested` | crossborder / payment | — | the customer's request |
| `crossborder.BeneficiaryRegistered` / `Activated` / `Blocked` / `Revoked` | crossborder / beneficiary | country, currency (an internal topic, never a customer surface); `Revoked` from whichever state it left | registration / screening / review / the customer |
| `crossborder.CorridorPolicyActivated` | crossborder / policy | version id | the approval |
| `crossborder.CorridorAvailabilityChanged` | crossborder / corridor | enabled or disabled | the disabling act, or the enable proposal's approval |
| `accounts.WalletCurrencyAdded` | accounts / wallet product | product id, ledger account id, currency | the add-currency request, or the conversion, return or resolved return that opened it — and, as built by `P9-TSK-004`, the account opening for its first currency, so every wallet account is announced; written **once, by the act whose insert returned the row**, a converging loser writing nothing |
| `kyc.CounterpartyScreeningDecided` | kyc / screening | outcome (internal) | request / review |
| *(settled)* | reconciliation | the existing `SettlementExpectationSettled`, kinds `FX_SELL_LEG`, `FX_BUY_LEG`, `CROSSBORDER_PAYOUT`, `CROSSBORDER_RETURN` | — |

**DELIVERY_PLAN §8 names, disposed:** `FxQuoteIssued`, `FxQuoteExpired`, `FxTradeExecuted` and
`CrossBorderPaymentInitiated` are built as named; `CurrencyConverted` is folded into
`FxTradeExecuted` (purpose `CONVERSION`); `CrossBorderPaymentSettled` is **not built** —
settlement is reconciliation's fact (`reconciliation.SettlementExpectationSettled`, kind
`CROSSBORDER_PAYOUT`). The outbound credit publishes no event of its own: its business
consequence is the payment's event, written in the same transaction.

## 11. Security and audit

**Permissions** — a permission exists when a distinct trust decision does; each arrives with its
first route. `RoleName` stays pairwise disjoint, pinned by `RoleNameTest` and
`RoutePermissionRegisterTest`:

| Permission | Role | Trust decision |
|---|---|---|
| `FX_ADMINISTER` | **`FX_CONTROLLER`** (new; identity `V019`) | Pricing policy propose / approve / reject (four-eyes: same permission, different persons, `CHECK`-held); pair and provider availability (disable: one person + reason; enable: four-eyes) |
| `CROSSBORDER_ADMINISTER` | `FX_CONTROLLER` | Corridor policy versions (four-eyes); corridor availability (the same asymmetry) |
| `FX_INVESTIGATE` | `RECONCILIATION_OPERATOR` | FX and corridor reports, trade provenance, payment traces (audited reads) |
| `FX_TRADE_REVERSE` | `LEDGER_OPERATOR` | Propose / approve an FX trade reversal (four-eyes), beside `TRANSFER_REVERSE`. Built by `P9-TSK-025`; if that task is cut (O8), the permission is not added and Phase 9 has four |
| `COUNTERPARTY_SCREENING_REVIEW` | `KYC_REVIEWER` | Release / block a counterparty screening in review |

Whoever sets prices (`FX_CONTROLLER`) can neither reverse trades nor clear screenings.
**Deliberately absent:** manual rate entry, manual trade booking, manual cover execution, and any
"override price" door — rates come only from providers (`INV-FX-02`).

**Four-eyes subjects** (`INV-AUD-04`): pricing policy, corridor policy and first-rule-set
activation; the availability-enable proposals; the FX trade reversal; the parked corridor
return's `TRANSFER_TO_ACCOUNT` (reconciliation's existing machinery). Person-distinctness is by
actor id at the domain and the `CHECK`, each rank proven alone. Routing policy v5 is the
exception, by ADR-0060's own decision: one person, a reason, audited (D26) — price never varies
with the rail (ADR-0060 §6) and only the beneficiary's issuing rail is reachable, so the
single-person act cannot redirect money to a rail the customer was not priced on.

**Sensitive data** (`INV-RAIL-03`, restated by ADR-0080): provider-attested country, currency and
entity type are admitted; account numbers, IBANs, routing codes and aliases still never enter.
The beneficiary is known by the corridor provider's opaque `destination_reference` and a 4-char
suffix; the name transits `crossborder` (the exchange and the screening) and is stored only by
kyc, encrypted. Port records redact in `toString`. **The needle test** carries a beneficiary's
name and grant through registration, quote, payment, recall and return, and asserts both absent
from every column except kyc's ciphertext, and from every log, event, audit body and response —
each leg built by the task that builds its flow (`-016`…`-019`, `-023`, `-024`), the full walk by
`P9-TST-001`.

**Credentials** — one confined credential per concern, each with a loopback-only default and a
`ConfinedCredentialVariablesTest` row: `FINAPP_FX_PROVIDER_KEY`, `FINAPP_FX_WEBHOOK_KEY`,
`FINAPP_FX_EVIDENCE_KEY`, `FINAPP_FX_REFERENCE_KEY`, `FINAPP_FX_REPORT_KEY`,
`FINAPP_CORRIDOR_PROVIDER_KEY`, `FINAPP_CORRIDOR_WEBHOOK_KEY`, `FINAPP_CORRIDOR_REPORT_KEY`;
M9.8 adds the second providers'. Counterparty screening reuses kyc's screening credential. Every
provider URL goes through `ProviderTransportGuard` (`https` off loopback, or startup is refused).

**Threats, each with its mitigation:** quote manipulation (the server-side frozen plan; strict
deserialisation refusing a `rate` or any unknown field; the two static guards; four-eyes pricing;
the band against an independent reference); replay (per-principal keys, single-use quotes,
webhook HMAC over timestamp + body with freshness and inbox dedupe, posting keys); stale quote
execution (the database-clock conditional inside the transitioning statement; validity bounded by
the provider lock minus the cover margin; the pair kill switch); duplicate transfer (one payment
per quote, the claim, `dispatch_key`, one outbound credit per payment); unauthorized corridor
(the corridor derived from provider-attested attributes, checked at quote and at authorization;
four-eyes corridor policy); provider credential compromise (confined keys; the transport guard;
callbacks are hints, so a forged-but-signed callback moves nothing unless the authenticated
inquiry confirms it — a planted test; a forged "accepted" cannot create money, because
reconciliation of the provider's own evidence catches a payout that never happened); rate-feed
tampering (the independent reference failing closed; the newer-than-latest rule; plausibility on
every provider rate, quotes and requotes; off-plan executions flagged; `FxPlanVerification`);
insider pricing (immutable four-eyes pinned versions; every trade names its version; replay;
every act audited with negative tests); the free option (short validity, the open-quote cap, the
notional bounds, no execution after expiry, recall honoured only before acceptance and its rate
alerted); enumeration (UUIDv7 ids, ownership checks, a uniform `404`).

**Audit.** New `FxAuditAction` and `CrossborderAuditAction` enums, plus kyc's and payments'
additions, two-way synced with `AUDITABLE_ACTIONS.md` §3 by `AuditableActionRegistryTest`. Each
act's audit record is written **in the act's own transaction** (`INV-AUD-01`, `INV-AUD-03`), with
actor, time, operation, target, reason (where a person judges), correlation and outcome; change
summaries carry identifiers only. Policy and availability acts, customer acts (quote cancelled,
conversion executed, beneficiary registered or revoked, payment authorized, cancellation
requested), reviews and corrections (screening reviewed, trade reversal acts, a parked return's
resolution) all catalogued; platform acts audited acting-only, losers recording nothing (cover
executed, requoted or unwound; outbound credit completed or failed; payment delivered; return
applied); every report and provenance read audited (`fx.ReportRead`, report and period only).
Phase 9's new operator acts are recorded under the existing `ActorType.CUSTOMER` debt row
(owner Phase 15), with one added line naming them; the actor id is right, and every four-eyes
rule compares identity ids, never the type.

## 12. The money model, execution and reconciliation

### 12.1 Which currency is which, and the types

| Currency of | Rule |
|---|---|
| **Account** | One per ledger account, always (ADR-0040). A wallet product holds n `CUSTOMER_WALLET` accounts, unique on `(owner_ref, purpose, currency)` |
| **Transaction** | Each journal line's currency is its account's. A conversion is **one entry with lines in two currencies, balanced in each** (ledger `V004`'s per-currency deferred trigger, one scale per currency per entry) |
| **Quote** | **Source** S (what the customer sells) and **destination** D (what the customer buys). The rate is D per 1 S |
| **Base / counter** | Market convention, **display only**: the pair's `market_base` drives the customer-facing quotation form and is never used in arithmetic |
| **Fixed / computed leg** | `FIXED_SOURCE` (the customer sells exactly S) or `FIXED_DESTINATION` (exactly D is delivered). Margin and residual are in the computed leg's currency |
| **Cross-border payment** | Debit currency S (amount + fee), instructed currency D, fee currency S |
| **Settlement** | One currency per batch (`INV-SET-07`). A cover settles as two single-currency legs in two batches. A corridor payout settles in D |
| **Functional / reporting** | None in Phase 9 (Phase 14). Nothing is ever totalled across currencies |

**Types.** `Money` is unchanged (`long` minor units, `CurrencyCode`, stored scale; inbound
amounts exact or `422`, never rounded; a signed amount stored as a signed `_amount_minor`).
**`ExchangeRate`** is new in `sharedkernel.money`: a record `(source, destination, BigDecimal
value)` with `value > 0`, `source ≠ destination`, precision ≤ 20 and scale ≤ 10; equality by
`compareTo`; no Spring, persistence or policy; operations each ending in **one** named rounding
— `exactProduct`, `convert`, `sourceFor` (one exactly-rounded division), `marginAgainst` (same
direction only) — and **no inversion and no cross rate**. An adapter refuses a provider rate
with more than 10 decimals; it never rounds one. **`Margin`** is new in `fx`: a fraction in
`[0, 0.1)`, scale ≤ 6, `NUMERIC(7,6)`; its attribution weight is `value × 10⁶`, an exact `long`.
**`CountryCode`** is new in `sharedkernel` beside `CurrencyCode` (ADR-0074 records ADR-0006's
argument for both kernel types). **No currency table**: minor units come from
`CurrencyCode.minorUnits()`, pinned by `SupportedCurrencyMinorUnitsArePinnedTest` (EUR 2, GBP 2,
USD 2, JPY 0, BHD 3) and by a `SupportedCurrencyMinorUnitsGuard` that refuses startup if the
running JDK disagrees (`INV-MON-05`). What enables a currency is three things:
`SupportedCurrencies` makes it *postable*, the active pricing policy's pairs make it *quotable*,
and the active corridor policy's corridors make it *sendable*.

### 12.2 Pricing: the one pure function, and the bounded residual

`ConversionPlan.compute(fixedSide, fixedAmount, ProviderQuote{rp, counter, validFor},
PricingPair v)` returns a `Plan` or a typed refusal. Let `m = v.spread + v.markup` and
`rc = round(rp × (1 − m), v.rateScale, v.rateRounding)` — the customer rate, with `v.rateScale`
per pair (O7: 10 for JPY-source pairs, 6 otherwise). The rate rounding cannot hide margin,
because the margin is computed from the *rounded* `rc`.

| | `FIXED_SOURCE` (S given) | `FIXED_DESTINATION` (D given) |
|---|---|---|
| Provider leg | `Dp` = the provider's stated counter, coherent iff `\|Dp − S·rp\| < 1` minor unit of D | `Sp` = the provider's stated counter, coherent iff `\|Sp·rp − D\| < rp × 10^−m(S)` (cross-multiplication, no division) |
| Customer leg | `Dc = round(S × rc, m(D), v.amountRounding)` (delivered) | `Sc = round(D ÷ rc, m(S), v.amountRounding)`: **one** exactly-rounded division (charged) |
| Margin (one line) | `M = round(S × (rp − rc), m(D), v.marginRounding)` in D | `M = round(D × (rp − rc) ÷ (rc × rp), m(S), v.marginRounding)` in S |
| Residual | `r = Dp − Dc − M` (D) | `r = Sc − Sp − M` (S) |
| Position legs (posted) | CR `FX_POSITION(S)` S; DR `FX_POSITION(D)` `Dp` | CR `FX_POSITION(S)` `Sp`; DR `FX_POSITION(D)` D |
| Attribution | `M.allocateByWeights(spread×10⁶, markup×10⁶)` → (spread part, markup part); ties go to the earlier part | the same |

The position legs are the provider's *stated* amounts, accepted only if coherent — a confirmed
cover then closes `FX_POSITION` exactly, and a provider's rounding never masquerades as market
P&L. The attribution is a stated convention: it splits the whole posted margin `M`, including
the bounded rate-rounding gain (at most 0.03 bps of the notional under O7's scales), which is
derivable exactly from stored inputs. A pair needs `spread + markup > 0` (a `CHECK` and the
domain), because `allocateByWeights` refuses all-zero weights. **Refusals**, never a shipped
price: `PROVIDER_QUOTE_INCOHERENT` (the next provider is tried), `MARGIN_NEGATIVE`,
`MARGIN_UNATTRIBUTABLE`, `422 fx.AmountOutOfRange` (the fixed leg outside the pair's notional
bounds, judged in Tx1 before any provider call, or a computed leg ≤ 0), and
`PLAN_INVARIANT_VIOLATED` (a residual beyond the policy's proven bound; CRITICAL, not issued).

**Two derived figures**, each ending in one named rounding, stored on the quote, neither ever
posted: the **internal rate** `round(rp × (1 − spread), 10, v.rateRounding)` (the dealing rate,
stored for Phase 14's split), and the **disclosed margin over mid**, `NUMERIC(7,6)` — reference
in the pair's direction: `round((ref − rc) ÷ ref, 6, HALF_EVEN)`; inverse direction:
`round(1 − rc × ref, 6, HALF_EVEN)`, an exact product, no inversion taken. Every rate column
receives a value already at or below its scale; the domain refuses one that is not
(`INV-MON-03`, `INV-HIST-04`).

**The residual, bounded and posted.** `r` is the integral remainder of independently rounded
amounts. Coherence bounds the provider term strictly below 1 minor unit; under half policies
each rounding term is at most ½, so **\|r\| ≤ 1**; under directed policies each is below 1, so
**\|r\| ≤ 2**. `fx.quote` and `fx.trade` therefore carry
`CHECK (residual_amount_minor BETWEEN -2 AND 2)` — universal, so no named policy can produce an
unstorable plan — and the domain additionally asserts the policy's own bound. The residual posts
to `ROUNDING_RESIDUAL` in its currency (CR when positive — the platform kept the fraction; DR
when negative — the platform bears it; no line at zero), and is never folded into the margin,
the customer amount or the position (`INV-BAL-03`, `INV-FX-07`).

**Worked figures** (policy v1: spread 0.003500, markup 0.001500, rate rounding `TOWARDS_ZERO`,
amount and margin rounding `HALF_EVEN`; every case has a non-JPY source, so its rate scale is 6):

| Case | Provider | rc | Customer leg | Margin (spread/markup) | Residual |
|---|---|---|---|---|---|
| **EUR→USD, FIXED_SOURCE** S = 1,000.00 EUR | rp 1.085024; Dp **1,085.02** USD (exact 1,085.024) | 1.079598 | Dc **1,079.60** USD (1,079.598) | **5.43** (5.426) = 3.80 / 1.63 | 1,085.02 − 1,079.60 − 5.43 = **−0.01 USD** → DR `ROUNDING_RESIDUAL` |
| **EUR→USD, FIXED_DESTINATION** D = 1,000.00 USD | rp 1.085024; Sp **921.64** EUR (exact 921.6385997…; coherent: \|921.64×1.085024 − 1000\| = 0.0015194 < 0.0108502) | 1.079598 | Sc **926.27** EUR (1000 ÷ 1.079598 = 926.2707…) | **4.63** EUR (4.632104…) = 3.24 / 1.39 | 926.27 − 921.64 − 4.63 = **0.00** |
| **USD→JPY (2→0)** S = 250.00 USD | rp 149.8742; Dp **37,469** JPY (exact 37,468.55) | 149.124829 | Dc **37,281** (37,281.207…) | **187** (187.34…) = 131 / 56 | 37,469 − 37,281 − 187 = **+1 JPY** → CR `ROUNDING_RESIDUAL` |
| **EUR→BHD (2→3)** S = 1,234.57 EUR | rp 0.411242; Dp **507.707** BHD (exact 507.70703594) | 0.409185 | Dc **505.168** | **2.540** (2.5395105) = 1.778 / 0.762 | 507.707 − 505.168 − 2.540 = **−0.001 BHD** → DR |
| **BHD→JPY (3→0)** S = 12.345 BHD | rp 397.5121; Dp **4,907** JPY (exact 4,907.2868745) | 395.524539 | Dc **4,883** (4,882.75…) | **25** (24.536…) = 18 / 7 (a tie goes to spread, the earlier part) | 4,907 − 4,883 − 25 = **−1 JPY** → DR |

Both signs of the residual appear, and each is bounded by 1. v1 chooses `TOWARDS_ZERO` for the
rate, so a displayed rate never exceeds the priced one, and `HALF_EVEN` for amounts — unbiased,
so the residual random-walks around zero and its drift is a defect signal. **Wire formats:**
amounts are exact decimal strings plus currency; rates appear in responses only; Phase 9's
amount-bearing events carry `<x>Minor`, `<x>Currency` and `<x>Scale` (§10).

### 12.3 The rate chain, the quote and the lock

```
Market / reference   fx.rate_snapshot        independent source; observed_at (source), received_at (DB clock)
      │  plausibility: |rp·ref − 1| ≤ band (inverse direction) or |rp − ref| ≤ band·ref; fresh on the DB clock
Provider (firm)      fx.quote.provider_*     rp, stated counter, provider_quote_ref, validFor, value date, requested_at, obtained_at
      │  internal rate = round(rp × (1 − spread), 10, rate_rounding)
Customer             fx.quote                rc, plan amounts, margin + attribution, residual, expires_at, disclosed margin over mid
      │  single use, DB clock, frozen
Executed             fx.trade                executed_rate = rc (equality CHECK); amounts = plan (copied, frozen)
      │
Cover executed       fx.cover_execution      provider_trade_ref, executed sold/bought, executed rate, value date, realised result
```

The reference comes from an independent source (`simulated-reference`, distinct from the FX
provider so the band is an independent check), fetched by the leaderless `FxRateFetchSchedule`
paced by a forward-renewed `rate_fetch_permit`, inserted `ON CONFLICT DO NOTHING` and only when
newer than the pair's latest (a stuck feed replaying an old observation never looks fresh).
Freshness is judged in SQL (`received_at > statement_timestamp() − reference_max_age`); **a
stale reference fails closed** — no quote, `503 fx.RateUnavailable`, the cause counted and never
shown; no in-process rate cache takes part in any decision. An implausible provider rate is
refused, counted and alerted, and the next provider is tried; it is never executed.

**The `ISSUED` quote is the lock**: exclusive to its owner (another principal's id answers a
uniform `404`), single-use (one `ACCEPTED` edge, `UNIQUE (fx.trade.quote_id)`), and bounded on
the database clock — `expires_at = least(requested_at + provider_valid_for − cover_margin,
issued_at + window)`, where `requested_at` is the claim transaction's `statement_timestamp()`
committed *before* the provider call. Network time and provider skew can only shorten the
window; with under 5 s left, no quote is issued; no path accepts a quote without the clock
comparison inside the transitioning statement.

**Creation is keyed and two-transaction** (`IdempotentExecutor.begin`/`complete`): **Tx1** — the
claim; the owner `ACTIVE`; the `ACTIVE` pricing version read, the pair enabled and unsuspended
in it; the amount exact and the fixed leg within the pair's bounds; the cap pre-checked
(advisory, `429` before any provider call, so an owner at the cap cannot farm provider RFQs —
the insert trigger stays the arbiter); `fx.quote_request` inserted with our reference `QR`,
`requested_at`, and the **pinned `pricing_policy_version_id`**. **Wire, holding no connection** —
each candidate provider in the pinned version's order is asked for a firm quote (2 s per
candidate, 5 s total; a firm quote moves no money, so failover is safe), each outcome a stored
sourcing step. **Tx2** — the pinned version re-read (`409 fx.PolicyStale` if superseded; the
client retries with a new key and is priced under the successor); the latest snapshot fresh or
refused; band and coherence; `ConversionPlan.compute` under the **pinned** pair; `expires_at`
computed and refused below 5 s; `fx.quote` inserted (the cap trigger fires here) with its
sourcing steps, history and outbox. Ten same-key requests make **one** provider call. A crash at
any point leaves either the claim `IN_PROGRESS`, taken over by the same key and converging on
the same `QR`, or a committed outcome.

**The cross-border quote** runs the same two transactions under crossborder's claim, with the
corridor version pinned on `crossborder.offer_request`, the beneficiary read `FOR SHARE` and
payable in state, the corridor's static limit, the fee computed from the pinned corridor
version, and — when `clear_until` has lapsed — a re-screen between the transactions whose `HIT`,
`INDETERMINATE` or unverified-payee outcome moves the beneficiary `IN_REVIEW` in a T-e
transaction and completes the claim `FAILED 422 crossborder.BeneficiaryNotPayable`, so nothing
is priced (`503 crossborder.ScreeningUnavailable` on `UNAVAILABLE`). `fx.quote` and
`crossborder.payment_offer` are inserted in one transaction (§9's budgets).

### 12.4 Execution: exactly when the financial effect occurs

The pipeline is **Quote → Customer acceptance → FX execution → Financial posting → Cover →
Settlement → Reconciliation**, never collapsed: each step has its own record and identifier.

| Step | Record | Financial effect |
|---|---|---|
| Quote | `fx.quote ISSUED` | **None.** No hold, no posting |
| Customer acceptance | quote `ACCEPTED` | For cross-border: a **hold** only, which is not a posting (ADR-0048) |
| FX execution | `fx.trade BOOKED` (the conversion's transaction; cross-border: the outbound credit's completion) | — |
| Financial posting | entry `fx-trade:<tradeId>` / `outbound-credit:<id>`, the same transaction as execution | **Here.** Customer balances change; the position opens; margin and residual are recognised |
| Cover | `fx.cover` → `fx.cover_execution`, entry `fx-cover:<coverId>` | The position closes into a provider receivable/payable; the leg expectations open |
| Settlement | provider reports (hop 1), bank statements (hop 2) | Fees recognised at hop 1; cash moves only on the bank's statement (`INV-SET-06`) |
| Reconciliation | items, allocations, breaks | None, except parking with its break and four-eyes resolutions |

A wallet conversion's customer effect is final on posting at the acceptance transaction's
commit, not reversible by the customer; a cross-border payment's customer effect occurs at the
outbound credit's **completion** — the corridor provider's acceptance.

**The worked example: 1,000.00 EUR → USD** (the figures above; reference mid 1.085200, provider
`fx-sim-a` rp 1.085024 for 1,000.00 EUR, stated counter 1,085.02 USD, ref `PQ-7`, valid 60 s;
the band check 0.016% ≤ 1.50%; the internal rate 1.0812264160, the disclosed margin 0.005162,
shown as 0.52%).

**(a) Entry `fx-trade:<T>`** (`POSTING`, actor the customer, posting and value date the trade's
stored `booked_on`):

| Currency | Account | DR | CR | Economic reasoning |
|---|---|---|---|---|
| EUR | `CUSTOMER_WALLET` (C, EUR) | 1,000.00 | | The platform owes the customer 1,000.00 EUR less |
| EUR | `FX_POSITION` (EUR) | | 1,000.00 | The platform holds EUR it will deliver into its cover |
| USD | `FX_POSITION` (USD) | 1,085.02 | | The platform will receive exactly the provider's locked amount |
| USD | `ROUNDING_RESIDUAL` (USD) | 0.01 | | The independently rounded parts exceed the provider amount by one cent; the platform bears it, posted |
| USD | `CUSTOMER_WALLET` (C, USD) | | 1,079.60 | The platform owes the customer these dollars |
| USD | `FX_SPREAD_REVENUE` (USD) | | 5.43 | Spread 3.80 + markup 1.63, earned now and explicit |

Per currency: EUR 1,000.00 = 1,000.00; USD 1,085.03 = 1,085.03. The plan identity holds:
1,085.02 = 1,079.60 + 5.43 + (−0.01).

**(b) Entry `fx-cover:<K1>`** (system, at the provider's `EXECUTED` honouring `PQ-7`, value date
T+2), opening `FX_SELL_LEG` (OUTBOUND 1,000.00 EUR) and `FX_BUY_LEG` (INBOUND 1,085.02 USD) on
`FX_PROVIDER_CLEARING(fx-sim-a)`, keyed `COVER_REF = T₁`: EUR — DR `FX_POSITION` 1,000.00 / CR
`FX_PROVIDER_CLEARING` 1,000.00; USD — DR `FX_PROVIDER_CLEARING` 1,085.02 / CR `FX_POSITION`
1,085.02. `FX_POSITION` is now **0** in both currencies.

**(c) Settlement on the value date** (ADR-0065, unchanged). Hop 1: the provider's EUR file has
`FX_SOLD` 1,000.00 and its USD file `FX_BOUGHT` 1,085.02, each carrying `COVER_REF = T₁` and the
provider's `FX_TRADE_REF`, each allocated to its leg expectation; no fee lines, so nothing
posts; each file opens one `REMITTANCE`. Hop 2: DR `FX_PROVIDER_CLEARING(EUR)` / CR
`CASH_AT_BANK(EUR)` 1,000.00; DR `CASH_AT_BANK(USD)` / CR `FX_PROVIDER_CLEARING(USD)` 1,085.02.
The clearing is flat. **At rest:** EUR wallet −1,000.00 = cash −1,000.00; USD wallet +1,079.60,
revenue +5.43, residual expense 0.01, cash +1,085.02 — 1,079.60 + 5.43 − 0.01 = 1,085.02. Every
position and clearing is zero, and nothing was converted inside a line.

**(d) The same plan, fixed destination** ("exactly 1,000.00 USD"; the customer pays 926.27 EUR):
EUR — DR wallet 926.27 / CR `FX_POSITION` 921.64 / CR `FX_SPREAD_REVENUE` 4.63 (residual 0.00,
no line); USD — DR `FX_POSITION` 1,000.00 / CR wallet 1,000.00. The cover sells 921.64 EUR and
buys 1,000.00 USD.

**(e) Zero and three minor units.** USD→JPY (250.00 USD): USD — DR wallet 250.00 / CR
`FX_POSITION` 250.00; JPY — DR `FX_POSITION` 37,469 / CR wallet 37,281 / CR `FX_SPREAD_REVENUE`
187 / CR `ROUNDING_RESIDUAL` 1 (37,469 = 37,469). BHD→JPY (12.345 BHD): BHD — DR wallet 12.345 /
CR `FX_POSITION` 12.345; JPY — DR `FX_POSITION` 4,907 + DR `ROUNDING_RESIDUAL` 1 / CR wallet
4,883 + CR `FX_SPREAD_REVENUE` 25 (4,908 = 4,908).

**(f) The cover slips** (the provider definitively refuses `PQ-7` with
`REJECTED(QUOTE_EXPIRED)`). Attempt 2 obtains a fresh firm quote for the exposure's fixed leg —
still "sell exactly 1,000.00 EUR" — at rp 1.084100 (within the band), executing 1,084.10 USD:
EUR — DR `FX_POSITION` 1,000.00 / CR `FX_PROVIDER_CLEARING` 1,000.00; USD — DR
`FX_PROVIDER_CLEARING` 1,084.10 + DR **`FX_REALISED_LOSSES` 0.92** / CR `FX_POSITION` 1,085.02.
The cover **always closes exactly what the conversion opened**; only the difference is P&L, in
the computed leg's currency; the customer is untouched.

**(g) As a cross-border payment** (corridor EUR→USD/US on `corridor-sim-a`, transfer fee
2.50 EUR; total debit **1,002.50 EUR**, the beneficiary receives **1,079.60 USD** exactly,
`OUR`). Authorization places a 1,002.50 EUR hold and dispatches the cover. At the provider's
acceptance, entry `outbound-credit:<OC>` posts with the hold released in the same transaction:

| Currency | Account | DR | CR |
|---|---|---|---|
| EUR | `CUSTOMER_WALLET` (C, EUR) | 1,002.50 | |
| EUR | `FEE_REVENUE` (EUR) | | 2.50 |
| EUR | `FX_POSITION` (EUR) | | 1,000.00 |
| USD | `FX_POSITION` (USD) | 1,085.02 | |
| USD | `ROUNDING_RESIDUAL` (USD) | 0.01 | |
| USD | `FX_SPREAD_REVENUE` (USD) | | 5.43 |
| USD | `CORRIDOR_CLEARING` (corridor-sim-a, USD) | | 1,079.60 |

It opens `CROSSBORDER_PAYOUT` (OUTBOUND 1,079.60 USD, keyed `END_TO_END_REF = E`); the cover
entry is (b); because the cover usually executes *before* the completion, `FX_POSITION` carries
the cover's legs in the meantime — the FX books proof explains them as "executed covers whose
quote is not yet `EXECUTED`". The corridor report's USD file has `PAYOUT_EXECUTED` 1,079.60
(allocated) and `PAYOUT_FEE` 1.20, which `FeeCheck` finds exactly at the corridor source's v1
schedule (0 + USD 120, O7); hop 1 posts DR `PROCESSING_COSTS` / CR `CORRIDOR_CLEARING` 1.20 and
opens a REMITTANCE (OUTBOUND 1,080.80); hop 2 posts DR `CORRIDOR_CLEARING` / CR
`CASH_AT_BANK(USD)` 1,080.80. **USD cash at rest:** 1,085.02 − 1,080.80 = **4.22** = 5.43 spread
− 0.01 residual − 1.20 corridor fee. EUR: wallet −1,002.50 = cash −1,000.00 − fee revenue 2.50.
Every cent is explainable from records.

**(h) The payment fails instead** (corridor `REJECTED` after the cover executed). The hold is
released, **no customer line exists**, the quote is `ABANDONED`; the executed cover is unwound
with the same provider — buy back exactly 1,000.00 EUR at a fresh firm quote, say 1,083.00 USD —
entry `fx-cover:<U>` (kind `UNWIND`): EUR — DR `FX_PROVIDER_CLEARING` 1,000.00 / CR
`FX_POSITION` 1,000.00; USD — DR `FX_POSITION` 1,085.02 / CR `FX_PROVIDER_CLEARING` 1,083.00 /
CR **`FX_REALISED_GAINS` 2.02**. `FX_POSITION` is zero again; the platform's result is one
explained line; the customer's wallet delta is zero.

**(i) The payment is returned after delivery** (the provider reports a return of 1,079.60 USD —
exactly the instructed credit, so it applies automatically; any other amount or currency parks
for a person, §12.9.3). Entry `crossborder-return:<OC>`: USD — DR
`CORRIDOR_CLEARING(corridor-sim-a)` 1,079.60 / CR `CUSTOMER_WALLET(C, USD)` 1,079.60 (the USD
wallet opened if absent); EUR — DR `FEE_REVENUE` 2.50 / CR `CUSTOMER_WALLET(C, EUR)` 2.50 (the
fee refund). It opens `CROSSBORDER_RETURN` (INBOUND 1,079.60, operation-anchored). The spread
stands, and the customer may convert back at a new quote.

### 12.5 The FX cover (ADR-0077)

There is **one cover of kind `COVER` per accepted quote**, and **one of kind `UNWIND` per quote
whose subject was abandoned, or whose trade was reversed, after its cover executed**
(`UNIQUE (quote_id, kind)`). No netting, no discretionary timing, no position limit — those are
treasury. The machine is §5's; attempts are rows (`UNIQUE (cover_id, attempt)`), each with its
own `client_reference` (`UNIQUE`) and `provider_quote_ref`.

- **The send permit is stamped by the database** — `last_dispatched_at =
  statement_timestamp()`, conditional and strictly forward, held by a trigger — so instance skew
  leaves ADR-0057 §4's premise (`X-TSK-013` aligns the Phase 6/7 permits).
- **No conclusion without knowledge.** On `UNRECOGNISED` or `INDETERMINATE`, `FxCoverSchedule`
  renews the permit and re-sends the same `Tn`; the provider contract is *dedupe on our
  reference before judging the quote's validity* (contract-tested), so a re-send after the lock
  lapsed returns the original execution if one happened. A new reference is minted **only**
  after a definitive `REJECTED`, after a fresh firm quote for the exposure's fixed leg has
  passed the band (`INV-FX-08`); an implausible requote leaves the cover `REJECTED`, retried
  with backoff and alerted.
- **One provider execution, one money fact.** The acting applier inserts `fx.cover_execution`
  (PK `cover_id`; `UNIQUE (provider_code, provider_trade_ref)`; `journal_entry_id UNIQUE`;
  executed sold/bought, value date, realised result) — the arbiter, holding with the conditional
  removed, beside the posting key `fx-cover:<coverId>`.
- **The cover always closes exactly the plan's position legs**; any difference posts to
  `FX_REALISED_GAINS`/`LOSSES` in that leg's currency; a provider deviating on the fixed leg is
  flagged `executed_off_plan`, counted and alerted. The leg expectations copy the **executed**
  clearing lines.
- **Wanted position, not commands.** A quote wants a cover iff its status is `ACCEPTED` or
  `EXECUTED` *and* its trade is not `REVERSED`: `REJECTED` + wanted ⇒ requote; `REJECTED` +
  unwanted ⇒ `VOIDED`; `EXECUTED` + unwanted ⇒ the `UNWIND` — evaluated by the cover's applier
  and the abandonment or reversal writer alike under the lock order quote → trade → cover, with
  `UNIQUE (quote_id, kind)` making exactly one unwind. The unwind replicates the cover's fixed
  leg in the opposite direction at a fresh firm quote from the same provider, through the same
  dispatch discipline. **A cover never moves to another provider.**
- **Callbacks are hints**: a verified FX callback is retained and triggers an immediate
  `inquire(T)`; only the inquiry's answer moves the cover.

### 12.6 Ledger additions and the posting catalogue

| Purpose | Type / normal | Owner kind | Arrives with | Posted by, and only by | Meaning |
|---|---|---|---|---|---|
| `FX_POSITION` (exists) | ASSET / DEBIT, kept (`INV-LED-06`) | OPERATIONAL | seeded `V003`; JPY/BHD `V019` | `fx`'s `ConversionLines` and `CoverLines` | Per currency, DR − CR = the amount the open legs will *receive* from covers; a credit balance = to be *delivered*; **0 at rest** |
| `ROUNDING_RESIDUAL` (exists) | EXPENSE / DEBIT | OPERATIONAL | `V003`; `V019` | `ConversionLines`, its **first production poster** | Conversion rounding, either sign, explained per trade |
| `FX_SPREAD_REVENUE` | REVENUE / CREDIT | OPERATIONAL | ledger `V020` (`P9-TSK-009`) | `ConversionLines` | Spread + markup earned |
| `FX_REALISED_GAINS` / `FX_REALISED_LOSSES` | REVENUE / CREDIT; EXPENSE / DEBIT | OPERATIONAL | ledger `V023` (`-012`) | `CoverLines` | A cover or unwind executed off the plan; never netted with each other |
| `FX_PROVIDER_CLEARING` | ASSET / DEBIT | **COUNTERPARTY** | ledger `V022`, with counterparty `fx-sim-a` (`-011`) | Cover entries; hop 1/2 recognitions of that provider's source | Per (provider, currency), what the provider owes the platform |
| `CORRIDOR_CLEARING` | LIABILITY / CREDIT | **COUNTERPARTY** | ledger `V024`, with counterparty `corridor-sim-a` (`-014`) | Outbound-credit completions, returns, hop 1/2 of that rail's source | What the platform owes the corridor provider for accepted, unsettled credits |
| The thirteen existing operational purposes | existing | | `V019` adds JPY/BHD (26 rows) | existing posters | |

**Counterparty keying** (ADR-0078): the seeded `ledger.counterparty` registry (id uuid, code,
kind `FX_PROVIDER` | `CORRIDOR_PROVIDER`; SELECT and INSERT grants only); ledger `V021` restates
the four generated constraints (`owner_kind_matches_purpose` with the two purposes ⇒
`COUNTERPARTY`; `owner_ref_matches_kind` ⇒ a registry row, by trigger);
`ChartOfAccounts.resolve(uow, purpose, counterpartyCode, currency)` serves counterparty
purposes, and the existing `resolve(purpose, currency)` refuses them; the existing operational
clearings keep their accounts and history.

**Closed to free adjustments.** `AccountPurpose.closedToFreeAdjustments()` =
`reconciledPositions()` ∪ {`FX_POSITION`, `FX_SPREAD_REVENUE`, `FX_REALISED_GAINS`,
`FX_REALISED_LOSSES`, `ROUNDING_RESIDUAL`}. `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` join
`reconciledPositions()` itself, because they open expectations; **`FX_POSITION` and the P&L
purposes must not**, or completeness would report every conversion line unattributed. Ledger
`V020`, `V022`, `V023` and `V024` each restate `V015`'s binding trigger as the set grows; a
`MANUAL` line on any of them is refused at the domain and by the trigger
(`422 ledger.AdjustmentOnReconciledPosition`); `ReversalService` mirrors and
reconciliation-origin resolutions remain admitted.

**The posting catalogue** — every entry keyed through `PostingService`'s `ledger.post` scope and
dated from stored rows:

| Posting key | Event | Lines |
|---|---|---|
| `fx-trade:<tradeId>` | Wallet conversion | §12.4(a)/(d)/(e): wallet ↔ `FX_POSITION` per currency; margin and residual in the computed leg |
| `outbound-credit:<id>` | The provider accepted the cross-border instruction | §12.4(g) |
| `fx-cover:<coverId>` | Cover or unwind executed | §12.4(b)/(f)/(h): `FX_POSITION` ↔ `FX_PROVIDER_CLEARING(provider)` ± `FX_REALISED_*` |
| `crossborder-return:<outboundCreditId>` | A return applied automatically | §12.4(i) |
| `crossborder-return-fee:<outboundCreditId>` | A parked return resolved by a person (T-g) | `FEE_REVENUE` → wallet (S) only; the principal is the resolution's own `TRANSFER_TO_ACCOUNT` (DR `SUSPENSE_UNMATCHED` / CR wallet in D), keyed by reconciliation as today |
| `reversal:fx-trade:<tradeId>` | Approved operator reversal | The exact mirror through `ReversalService` (ledger `V009`'s bound) |
| Hop 1 / hop 2 | Counterparty report / bank statement | ADR-0065's shapes on the counterparty positions |

**Other ledger rules:** balanced per currency by the unchanged `V004` trigger (every line at its
currency's JDK scale); `BalanceDisplay` answers one balance per account, so per currency, and
the API never sums across currencies; `TrialBalance` and `finapp.ledger.trial.balance{currency}`
eager for five; every conversion updates the `FX_POSITION`, `FX_SPREAD_REVENUE` and
`ROUNDING_RESIDUAL` projection rows of its two currencies — a per-currency serialisation point,
correct by the projection's row lock, postings last, `lockBalancesInOrder` for multi-entry
transactions, the storm recording the p99 lock wait, and the sub-account scale-out path recorded
for Phase 16; no revaluation (ADR-0076). Static rules: `FxBooksHaveOnePosterTest` (only `fx`'s
line composers name the five FX books) and `CounterpartyClearingIsNamedByDeclarationsTest` (only
the declarations and the settlement composition name the counterparty purposes).

### 12.7 Fees and spreads

| Component | Set by | Currency | Posting | Reconciliation |
|---|---|---|---|---|
| Provider FX rate | The provider's firm quote | — | `FX_POSITION` legs at the provider's stated amounts | `FX_SELL_LEG`/`FX_BUY_LEG`; a different settled amount → `AMOUNT_MISMATCH(FX_LEG_DIFFERS)` |
| Internal spread | Pricing policy (four-eyes) | The computed leg | Part of the one `FX_SPREAD_REVENUE` line | None external; `FxPlanVerification` |
| Customer markup | Pricing policy, per purpose | The computed leg | The same line; attribution stored | The same |
| Transfer fee | Corridor policy: fixed `Money` + `Margin` × customer source amount, a named rounding | Source | DR wallet / CR `FEE_REVENUE` inside the completion entry; refunded on return | None |
| Intermediary fee | Correspondents, behind the provider | Destination | If billed: hop 1 DR `PROCESSING_COSTS` / CR `CORRIDOR_CLEARING`. If deducted from principal: no posting — an `AMOUNT_MISMATCH` break on the payout expectation, never a silent short delivery (`OUR`) | `FeeCheck` against the corridor source's pinned schedule → `FEE_MISMATCH` |
| Beneficiary fee | The beneficiary's bank | — | Never ours, never posted; disclosed | — |
| Settlement / provider fee | FX or corridor provider | Its batch currency | Hop 1 DR `PROCESSING_COSTS` / CR its clearing; bank fees at hop 2 (existing) | Pinned schedule → `FEE_MISMATCH` |
| Conversion fee | — | — | None in Phase 9 (O10) | — |

Every margin and fee is computed **once, at quote time**, by a pure function over stored inputs
and pinned versions, frozen on the quote or offer, and never recomputed at authorization or
completion; a policy activated between quote and execution changes nothing; the completion posts
the stored figures under one posting key, behind the acting conditional and the claim PK, so ten
racing appliers post one fee line; a golden replay re-derives every stored fee and margin
(`INV-HIST-04`); on a destination-fixed offer the fee is computed on `Sc`. **Provider fees are
judged against a schedule in every currency they can arrive in** — `FeeCheck` prices an absent
schedule at zero, so a missing row would turn every genuine fee into a `FEE_MISMATCH`: the four
existing sources' v2 successors carry JPY and BHD rows (O6), the FX source's v1 carries `FX_FEE`
in all five currencies, and the corridor source's v1 carries `PAYOUT_FEE` in its three settled
currencies (O7). `FX_FEE` becomes a priced fee line (reconciliation `V020`).

### 12.8 The ambiguous outcome, and the forbidden outcomes

The canonical sequence — *customer confirms → submitted → provider executes → response lost →
timeout → customer retries* — runs: **Tx1** (T-b, one commit: the step-up rule; the quote locked
and judged `ISSUED`, unexpired, owned, `CROSS_BORDER`; the beneficiary `FOR SHARE`, `ACTIVE` and
clear (`INV-XB-02` in-lock; a lapsed clearance `409 crossborder.ScreeningRequired`); corridor
availability `FOR SHARE`; routing with reachable = {the beneficiary's rail}; the limit and risk
seams in-lock; the wallet locked and funds judged; the quote `ISSUED → ACCEPTED`
(`acceptWithin` takes no claim of its own — the caller's claim covers the transaction); the
hold; the payment born `SUBMITTED`; the outbound credit born `DISPATCHED` with `E` and the first
database-stamped permit; the cover born `DISPATCHED` with `T₁`; audit and outbox). A refusal
commits only the claim's failed outcome. **The wire, holding no connection, cover first** (2 s),
then the corridor (3 s) — the cover reaches the provider within the 10 s cover margin whatever
the corridor does. A lost answer runs **Tx2**: the credit `DISPATCHED → UNKNOWN`, the timeout
recorded as evidence, `complete(K, 202 PROCESSING)`, **the hold stands**. The same key replays
the stored `202`; a new key on the same quote answers `409 fx.QuoteAlreadyAccepted` naming the
payment; a crashed flight is taken over by the same key, converging on the committed row by
`dispatch_key` and **re-sending the same `E`** (the provider dedupes). Resolution comes through
one `OutboundCreditOutcomes.apply` — the signed callback as a hint triggering an authenticated
`inquire(E)`, or the resolution schedule: `ACCEPTED` runs the completion transaction (T-c, in
order — the acting exit, the claim, the hold release, the entry, the trade booked, the
quote `EXECUTED`, the payment `IN_TRANSIT`, the expectation); `RECEIVED` stores the reference
once and keeps inquiring; an answer **implying acceptance** (`Accepted` with `deliveredAt`, or
`Returned`) on a credit still `DISPATCHED`/`UNKNOWN`/`RECEIVED` applies the completion, then the
delivery, then an applicable return, in order, in one transaction; `UNRECOGNISED` waits, unless
the latest permit is older than the rail's `outcomeDeadline` + margin, re-judged on the locked
row — then `FAILED(NEVER_RECEIVED)`, the hold released, the quote `ABANDONED`, the cover
unwound. A provider that breaks its own deadline and executes after `FAILED` is caught by its
settlement line: no expectation, no claim, after grace `UNKNOWN_EXTERNAL`, parked, loud,
resolved four-eyes.

| Forbidden outcome | Why it cannot happen |
|---|---|
| Executed twice | The provider dedupes on `E`; no new `E` is ever minted for a payment; the permit rule prevents a conclusion racing a re-send; the payment is never re-routed (one reachable candidate) |
| FX applied twice | `UNIQUE (fx.trade.quote_id)`, and the quote's single `ACCEPTED → EXECUTED` edge |
| Fees charged twice | The fee line exists only in the one completion entry: posting key + acting conditional + claim PK |
| Duplicate postings | The same three arbiters; plus the cover's `fx.cover_execution` PK |
| The provider reference lost | `E` is ours and stored before the send; the provider's reference arrives on any of three channels, is stored once, and can be obtained again by `inquire(E)`; it is held in the claim |
| A success treated as failure | `INDETERMINATE` is `UNKNOWN`, never `FAILED`; `NEVER_RECEIVED` needs the declared deadline; `RECEIVED` can never become `NEVER_RECEIVED` |

The FX cover has the same shape with one difference: a cover never concludes "never received" —
it re-sends `T` until answered, and only a definitive `REJECTED` mints `T(n+1)`. A wallet
conversion has no provider call before the customer's effect, so the customer can never observe
an ambiguous conversion: a retry replays a committed booking.

### 12.9 Reconciliation

#### 12.9.1 Identifiers

| Pair | Ours (minted before send, stored) | Theirs (stored once) | Evidence reference on the line | Expectation (opened by) |
|---|---|---|---|---|
| FX trade ↔ FX provider | `fx.cover_attempt.client_reference` `Tn` (per attempt) | `fx.cover_execution.provider_trade_ref` | `COVER_REF` = `Tn` (key); `FX_TRADE_REF` (alias, trace) | `FX_SELL_LEG` (OUTBOUND), `FX_BUY_LEG` (INBOUND) on `FX_PROVIDER_CLEARING(provider)`, by the cover entry |
| Cross-border payment ↔ provider / network | `payments.outbound_credit.end_to_end_reference` `E` | `provider_reference` (claimed in `scheme_execution_claim`) | `END_TO_END_REF` (key); `PAYOUT_PROVIDER_REF` (alias) | `CROSSBORDER_PAYOUT` (OUTBOUND) on `CORRIDOR_CLEARING(rail)`, by the completion |
| Return ↔ provider | the outbound credit (the operation anchor) | `return_reference` (claimed, subject `CROSSBORDER_RETURN`) | none of its own: **operation-anchored** (ADR-0067 §5, the payout-return precedent) | `CROSSBORDER_RETURN` (INBOUND), `UNIQUE (kind, operation_ref)`, by the return |
| Settlement ↔ ledger | every expectation names `(journal_entry_id, ledger_account_id)` | — | — | recognitions keyed `settlement-batch:<id>` |
| Fees ↔ provider settlement | the source's pinned `provider_fee_schedule` | fee lines (`FX_FEE`, `PAYOUT_FEE`) with `ORIGINAL_REF` = `E` or `Tn` | `FeeCheck` per line (now currency-safe, §2) | `FEE_MISMATCH` beyond tolerance |
| Customer trade ↔ cover | `fx.cover.quote_id`, `fx.trade.quote_id` | — | internal | proven by the FX books proof |

#### 12.9.2 Sources, formats and vocabulary

Every vocabulary addition is appended at the end of its enum, with the `CHECK` migrations and
the mirror guard. `SourceKind` gains `FX_PROVIDER_REPORT`; the corridor reuses
`PAYOUT_PROVIDER_REPORT` (no new kind — `SettlementSources` keys by code and position, not by
kind). The formats are `SIM_FX_CSV` v1 (one file per provider, currency and value date) and
`SIM_CORRIDOR_CSV` v1, each one currency per batch with a golden file and a fault test per
field. `SettlementSourceDescriptor` gains `Optional<String> settledCounterparty` — present
exactly when the settled purpose is counterparty-owned — and with it the counterparty's
**settled currencies** (all five for `fx-sim-a`, `{USD, JPY, BHD}` for `corridor-sim-a`);
`SettlementSources.of` refuses two sources on one (purpose, counterparty) (`INV-SET-05`); a
parsed batch in a currency its counterparty does not settle is rejected and retained with the
appended `RejectionCode.CURRENCY_NOT_SETTLED` (settlement `V015`) — never accepted, never
posted, loud. The composed sources are `fx-sim-a.trade-report` (position `FX_PROVIDER_CLEARING`,
counterparty `fx-sim-a`, remittance pattern `FXA-…`) and `corridor-sim-a.settlement`
(`CORRIDOR_CLEARING`, `corridor-sim-a`, `XBA-…`); M9.8 adds the `-b` pair; `PositionProof`'s
`PROVEN` list is **derived from the composed register**. Line types `FX_SOLD`, `FX_BOUGHT`,
`FX_FEE` (contiguous, `EnumSet.range`); references `COVER_REF`, `FX_TRADE_REF`; the corridor
reuses `PAYOUT_EXECUTED`/`PAYOUT_RETURNED`/`PAYOUT_FEE` and
`END_TO_END_REF`/`PAYOUT_PROVIDER_REF`. Expectation kinds `FX_SELL_LEG`, `FX_BUY_LEG`,
`CROSSBORDER_PAYOUT`, `CROSSBORDER_RETURN`, with the port mirrors. `SIM_STATEMENT_TAGGED`'s
per-currency settlement-account references gain JPY and BHD (`P9-TSK-003`).

`JdbcInternalReferenceLookup` reads covers in flight (a `COVER_REF` naming a dispatched or
unknown attempt ⇒ `MISSING_INTERNAL`, else `UNKNOWN_EXTERNAL`) and outbound-credit claims, and
**every operation key is resolved within the item's own source family** through the existing
`RailOfSource`: `END_TO_END_REF` and `PAYOUT_PROVIDER_REF` name an outbound credit only for a
corridor source, and an attempt, withdrawal or merchant payout only for their own sources — a
reference colliding across providers is never typed as the other family's operation.
`WaitingPayoutReturns` is scoped the same way (§3's port table), so the merchant
`PayoutReturnSweep` can never see a corridor return, nor the corridor worker a merchant one.

**Rule sets.** No migration seeds one (D26). Each new source's **version 1** goes through
`RuleSetAdministration`'s new first-version path, four-eyes: a proposal admitted when the source
has no `ACTIVE` version; activation retires nothing; a batch from a source with no active rule
set gets a typed **`RuleSetMissing`** refusal — the file waits `PARSED` with backoff, and
`finapp.reconciliation.rule_set.missing{source}` reads 1 and alerts, instead of retrying without
end. **The FX v1**: legs `ONE_TO_ONE` keyed `COVER_REF`, grace 24 h, lag 2 d; an `FX_FEE` rule
of cardinality `CHECK` (original by `ORIGINAL_REF` = `Tn`) with its 0 + 0 schedule in all five
currencies; `SETTLEMENT_DATE_DAYS` 2; no fee tolerance; thresholds for five currencies. **The
corridor v1**: payout `ONE_TO_ONE` (keys `END_TO_END_REF`, then `PAYOUT_PROVIDER_REF`); returns
operation-anchored (grace 72 h, the payout-return precedent); a `PAYOUT_FEE` rule of cardinality
`CHECK` with its schedule 0 + USD 120 / JPY 180 / BHD 450; no fee tolerance; lag 2 d;
`SETTLEMENT_DATE_DAYS` 2; thresholds for its three currencies. The four existing sources get
**v2 successors** carrying O6's JPY/BHD thresholds, fee schedules and fee tolerances, through
the existing successor door; earlier decisions replay `IDENTICAL` under their pinned v1.

#### 12.9.3 Break taxonomy: new causes, no new types (ADR-0069 amended)

ADR-0069 splits a type only where resolution or severity differs; expressed as single-currency
legs, FX discrepancies resolve exactly like the existing types. **Reconciliation never
converts, and there is no fifteenth break type.**

| DELIVERY_PLAN names | Answer |
|---|---|
| Rate difference | `AMOUNT_MISMATCH`, cause `FX_LEG_DIFFERS`: a leg settled ≠ the cover's executed leg. **Selected by the expectation's kind** in `Matching`'s `differenceCause` (the `REMITTANCE_DIFFERS` precedent): `FX_SELL_LEG` and `FX_BUY_LEG` name `FX_LEG_DIFFERS`, every other kind keeps `AMOUNT_DIFFERS`. Its kinds are `AMOUNT_MISMATCH`'s, `RECOGNISE_GAIN` on credit after the minimum age included. A provider breaching its own confirmation is a counterparty shortfall (`RECONCILIATION_LOSSES`/`GAINS`), not market FX result |
| Spread difference | **Not a reconciliation break.** No external evidence states our spread; `FxPlanVerification` proves it (a divergence is our defect: CRITICAL, a verdict gauge). A cover executed off plan is posted as confirmed, flagged, counted, alerted and reported |
| Conversion timing difference | `TIMING_DIFFERENCE`, cause `VALUE_DATE_DIFFERS` (value 0, one-person `ACKNOWLEDGE` as a timing detector's cause — reconciliation `V014`'s list restated). The detector is `MatchEngine`'s existing timing verdict: the line's value date differs from the expectation's `expected_by` beyond the source's `SETTLEMENT_DATE_DAYS`. Precedence, one break per decision: an open `MISSING_EXTERNAL` on the leg already states the timing and nothing is raised; otherwise an FX leg kind names `VALUE_DATE_DIFFERS`; otherwise `CYCLE_MISMATCH`, else `LATE_MATCH`, as today. A cycle shift cannot arise on an FX leg (the cover announces no cycle) |
| Settlement in an unexpected currency | `CURRENCY_MISMATCH` (existing; never converted, never a gain) for a line in a currency its source settles but its candidate expectation does not (a JPY corridor line naming a USD payout). A currency the source's counterparty does not settle at all is rejected at parse, `CURRENCY_NOT_SETTLED` (§12.9.2) |
| One leg settled, the other never (principal risk) | `MISSING_EXTERNAL` on the leg, base severity **HIGH** for `FX_SELL_LEG`, `FX_BUY_LEG` and `CROSSBORDER_PAYOUT` (the `MERCHANT_PAYOUT` precedent). `ReconciliationSweep`'s escalation leg gains one rule: an open `MISSING_EXTERNAL` on an FX leg whose paired leg (the same cover attempt's other leg, by `COVER_REF`) is allocated is raised to **CRITICAL**, under the source's namespace-4 advisory, by the existing expected-value severity step, with a `SEVERITY_ESCALATED` `break_event` detailed `PAIRED_LEG_ALLOCATED`; ten sweepers write one event; no migration — `V004`'s `break_event` already admits it |
| A return that cannot apply | `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)` (ADR-0073's precedent): a different amount or declared currency, a closed customer, or a credit still in flight at grace; `TERMINAL_STATE_CONTRADICTED` at once for a `FAILED` credit, beside the late execution's `UNKNOWN_EXTERNAL`, a person offsetting the two |
| A late provider execution after we concluded `FAILED` | `UNKNOWN_EXTERNAL`, parked |

**Returns: two channels, one fact, and the way out.** A corridor return reaches the platform on
the inquiry channel (hinted or swept) and on the settlement report's `PAYOUT_RETURNED` line
(read by `OutboundReturnWorker` from the scoped waiting items), both converging on
`OutboundCreditComposition.returned` behind `UNIQUE (outbound_credit_return.outbound_credit_id)`,
the claim `(rail, return_reference)` and the posting key. The one applicability rule, judged on
the locked rows: a `COMPLETED` credit, the instructed currency, exactly the instructed amount
and an `ACTIVE` customer ⇒ **applied** (§12.4(i), the wallet opened if absent, the fee refunded,
the payment `RETURNED`); a credit whose completion is not yet known ⇒ **the worker defers**,
writing nothing, the inquiry completing the credit first; anything else ⇒ **not applicable**,
nothing written, the grace leg parking it `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)` (HIGH, DR
`CORRIDOR_CLEARING` / CR `SUSPENSE_UNMATCHED`, owned by its break). A partial return is not
credited automatically because the provider's line and our posting would agree — no break could
surface the shortfall, and the customer would silently bear a deduction under a promise of
`OUR`; a person sees every such case. **The way out of a parked corridor return** is the
four-eyes `TRANSFER_TO_ACCOUNT` (T-g): its approval, when the item's operation is an outbound
credit, also calls `ResolvedCorridorReturns` in the same transaction, after its own rows — the
born-once return fact with `applied_by = RESOLUTION`, the fee refund
`crossborder-return-fee:<id>`, the payment `RETURNED` (basis `RESOLVED`); it opens no
expectation, because the park already moved the value off `CORRIDOR_CLEARING`. If the return
fact already exists, the port's insert conflicts, the whole approval rolls back
`409 reconciliation.ResolutionStale`, and the rematch leg closes the break `EVIDENCED`; if the
approval wins, an automated application finds the fact and writes nothing — nothing is ever
credited twice. The port also refuses while the credit is not `COMPLETED`; a credit that ends
`FAILED` leaves the parked line to be offset against the late execution's `UNKNOWN_EXTERNAL`,
never transferred. A closed customer's value stays parked with its HIGH break, aged and
escalated — recorded debt with Phase 15 as owner.

#### 12.9.4 The proofs

Report-only, one `REPEATABLE READ` snapshot, `Money` folds, never repairing:

- **Reconciliation's existing proofs** — position, suspense, cash and completeness — cover
  `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` per (counterparty, currency), unchanged in
  principle, now over five currencies.
- **The FX books proof** (`fx.FxBooksProof`, `INV-FX-06`), per currency `c`:
  `FX_POSITION(c)` DR−CR = Σ over every quote of its plan's position legs in `c` × (booked
  trade − executed `COVER` + executed `UNWIND` − reversal), every term cancelling to 0 at rest;
  `FX_SPREAD_REVENUE(c)` CR−DR = Σ margin of booked, unreversed trades;
  `ROUNDING_RESIDUAL(c)` DR−CR = −Σ residual of booked, unreversed trades;
  `FX_REALISED_GAINS(c)`/`LOSSES(c)` = Σ realised results of executions in `c`. Verdicts only
  reach `finapp.fx.proof{purpose}`: the number of failing currencies, which must be 0.
- **`FxPlanVerification`**: every trade's plan recomputed from its stored columns alone, through
  the same pure function, compared with the posted entry line by line, and the recomputed
  internal rate and disclosed margin with the stored ones — the ADR-0068 §9 replay discipline
  applied to FX. A divergence is CRITICAL, sets `finapp.fx.plan.verdict` to 0 and alerts.
- **Holds**: a `SUBMITTED` payment has exactly one active hold of its total debit; a terminal
  one has none.

## 13. Testing strategy

Per `TESTING.md`'s tiers: hermetic unit tests for every machine and the pure arithmetic;
property tests over `ExchangeRate` and `ConversionPlan` (exactness; the residual bound over 10⁶
random (amount, rate, policy) cases per policy family — 1 for half, 2 for directed; the plan
identity; the attribution summing exactly; over-precision refused; 0↔2, 0↔3 and 2↔3 both ways;
provider rates at the full 10 decimals on every one of the 20 pairs, each at its own rate scale;
a zero-margin pair refused); golden replay of every stored quote's plan from its pinned policy
and provider facts (`INV-HIST-04`, `INV-FX-05`); contract batteries for the three simulators
(refuse before send, execute then drop the response, duplicate callback, late callback, wrong
amount, unknown state, lock expiry, provider deviation, dedupe on our reference before validity
(FX) and on `E` (corridor)); database tests on Testcontainers for every transaction boundary,
constraint and race, concurrency through `SimulatedInstance`; atomicity by failure injection for
every Phase 9 transaction (quote creation, conversion, Tx1, completion, cover outcome, unwind,
return, a resolved return (T-g), recall request, reversal approval, screening decision, policy
activation, enable-proposal approval); exhaustive machine tests (every invalid edge refused by
the domain and by raw SQL through the trigger; the cancellation request refused any update);
static rules (`NoFloatingPointMoneyRulesTest` over both modules; `FxBooksHaveOnePosterTest`;
`CounterpartyClearingIsNamedByDeclarationsTest`; `RatesAreNeverClientSuppliedTest` and the
OpenAPI request-schema guard; `ReconciliationNeverConvertsTest` — no `ExchangeRate` reachable
from `settlement` or `reconciliation`, with a planted use refused; `NoProviderPortInConversionTest`
(`INV-FX-09`); `WalletsAreResolvedByCurrencyTest` — no `findFirst()` on wallets); the
`INV-RAIL-03` needle (§11); regression inside the risky tasks (the Phase 7 storm and dispute
battery re-run inside `-004` and `-014`; the Phase 8 storm and proof-group suites inside
`-003`, `-010` and `-011`); and mutation probes recorded in `MUTATION_TESTING.md` §2 for every
`Phase: 9` invariant read from the catalogue, every restore byte-identical (sha256).

**The owner's ten scenarios, each a counted test, not an argument:**

1. **Two instances execute the same FX** — `FxDoubleExecutionDatabaseTest` (ten threads, same
   and different keys, `-009`) and `TwoInstanceFxRaceTest` (two app contexts, ±5 s clocks,
   `P9-TST-001`): 1 trade, 1 entry, 1 cover, 1 execution fact; losers replay or `409`; the
   lock-bypass probe on `UNIQUE (quote_id)` caught.
2. **A quote expires during execution** — `QuoteExpiryRaceDatabaseTest` (`-009`): 200 quotes
   expiring mid-race, 10 acceptors + 10 sweepers each, both orders forced by latches; exactly
   one of {`ACCEPTED` + trade with `accepted_at < expires_at`, `EXPIRED` with one event and no
   trade}; an instance at ±5 s decides nothing.
3. **The provider rate changes** — `ProviderRateMovesDatabaseTest` (`-012`): the market moving
   after issue leaves the customer on the frozen plan and the cover at the firm rate, zero
   result; the provider refusing its own lock yields 1 rejection, 1 fresh firm quote, 1 requote,
   exactly one realised-result line equal to the difference, customer lines byte-identical,
   `FX_POSITION` 0.
4. **The provider succeeds but the response is lost** — `CoverResponseLostDatabaseTest`
   (`-012`), `OutboundCreditResponseLostDatabaseTest` (`-020`): simulator execution count 1;
   `UNKNOWN` → inquiry → one transition, one posting; the provider reference stored.
5. **The customer retries** — `CrossBorderRetryDatabaseTest` (`-020`): the same key replays
   byte-identically with no wire; a new key answers `409 QuoteAlreadyAccepted`; ten in-flight
   retries answer `IdempotencyInProgress` or the replay; a takeover re-sends the same `E`,
   provider instruction count 1.
6. **A callback arrives twice** — `CorridorCallbackDuplicateDatabaseTest` (`-020`),
   `FxCallbackDuplicateDatabaseTest` (`-012`): ten deliveries plus a racing sweep, one effect;
   unsigned or stale, nothing; signed-but-forged, nothing without the inquiry's confirmation.
7. **The transfer is returned** — `CrossBorderReturnDatabaseTest` (`-023`): before and after
   delivery, and reported before the completion is known (the worker defers; the inquiry applies
   completion then return in one transaction); the line duplicated within and across files; 10
   workers + a hinted inquiry → 1 return fact, 1 credit in D (the wallet opened once), 1 fee
   refund, 1 expectation, the payment `RETURNED`; a closed customer, a partial return or another
   declared currency parked, nothing posted automatically, the person's resolution moving the
   payment and refunding the fee once; identical provider references in a merchant and a
   corridor source, each worker applying only its own.
8. **A reversal is retried** — `FxTradeReversalRaceDatabaseTest` (`-025`),
   `UnwindRetryDatabaseTest` (`-021`): ten approvals → 1 reversal entry, 1 unwind cover, 1
   realised line; ten abandonment appliers → 1 unwind; the customer wallet delta over a failed
   payment's life = 0. If `-025` is cut (O8), the scenario is met by `UnwindRetryDatabaseTest`
   alone and the reversal half's deferral is recorded with Phase 15 as owner.
9. **A fee is calculated twice** — `FeeOnceDatabaseTest` (`-020`): ten completion appliers → one
   fee line; a corridor or pricing policy activated between quote and completion changes
   nothing; golden replay reproduces every stored fee and margin.
10. **Reconciliation finds a settlement mismatch** — `FxReconciliationBreaksDatabaseTest`
    (`-013`), `CorridorReconciliationBreaksDatabaseTest` (`-022`): a leg off by one minor unit →
    `AMOUNT_MISMATCH(FX_LEG_DIFFERS)` both directions; a JPY corridor line naming a USD payout →
    `CURRENCY_MISMATCH` parked; a EUR corridor file → rejected `CURRENCY_NOT_SETTLED`, nothing
    posted; a value date beyond tolerance → `TIMING_DIFFERENCE(VALUE_DATE_DIFFERS)`; an unpaired
    leg → `MISSING_EXTERNAL` escalated CRITICAL; a corridor deduction → `AMOUNT_MISMATCH`;
    proofs 0 after resolution.

**`P9-TST-002`, the value-preservation and rounding battery** (runnable from M9.4): ≥ 10,000
conversions over all 20 directional pairs and both fixed sides, amounts from minimum to maximum,
some covers rejected and requoted; then, in one snapshot: the trial balance zero in all five
currencies; every \|r\| ≤ 1 with both signs present; `ROUNDING_RESIDUAL(c)` = −Σ r;
`FX_SPREAD_REVENUE(c)` = Σ margin; `FX_POSITION` = 0 once covers execute; `FxPlanVerification`
clean; customer deltas = plan amounts; golden replay of every quote.

**`P9-TST-001`, the FX and cross-border storm**: **two application contexts with clocks skewed
by ±5 s**, plus ten movers; every fault seeded (response lost, duplicate and forged callbacks,
lock expiry, off-plan executions, decline, `RECEIVED` then reject, never received, recall wins
or loses, returns before and after delivery and before the completion is known, a partial return
parked and resolved, settlement mismatches, an unexpected currency); crashes after Tx1,
mid-cover and mid-completion. Every round, in one `REPEATABLE READ` snapshot: the per-currency
trial balance, the FX books proof, reconciliation's proofs and the holds check; simulator
execution counts = cover execution facts = trades; provider instruction count = outbound
credits. At rest: exact censuses, `FX_POSITION` 0, replay `IDENTICAL`, the hot-row p99 recorded,
and the needle walked end to end, absent everywhere but kyc's ciphertext. Three consecutive
fresh runs. It depends on every `P9-TSK` (`-026` unless cut) and on `X-TSK-013`, so the
skewed-clock race meets no instance-stamped permit.

## 14. Failure scenarios

Each has a test or a documented, accepted rationale (the gate's testing criterion):

1. The rate feed is down → quotes refused; `rate.age` alerts.
2. A stale reference, or an old observation replayed → refused.
3. An implausible provider rate → refused, alerted, the next provider tried.
4. The provider firm-quote times out → failover; then `503`.
5. An incoherent provider counter → refused.
6. The quote expires before acceptance → `409`.
7. The quote expires during acceptance → the database clock decides.
8. Acceptance on a skewed instance → the database clock decides.
9. The cap is raced → the trigger holds.
10. Insufficient funds at acceptance → rollback; the quote is reusable.
11. A crash after the conversion commits, before the cover is sent → the sweeper sends `T₁`.
12. The cover's response is lost → inquiry.
13. The cover is `UNRECOGNISED` → `T` is re-sent.
14. The cover's lock expired → a fresh firm quote, a requote, P&L.
15. The requote is implausible → `REJECTED` held, alerted.
16. The provider executes off plan → posted, flagged.
17. A provider callback arrives before the synchronous answer → hint, inquiry, one effect.
18. A duplicate callback → the inbox dedupes.
19. A forged but signed callback → no effect.
20. A crash inside cross-border Tx1 → nothing or everything.
21. A crash after Tx1, before the send → takeover or sweep.
22. The corridor response is lost → `UNKNOWN`; the hold stands.
23. The provider answers `RECEIVED`, then rejects hours later → `FAILED`; hold released; unwind.
24. The corridor answers `NOTHING_SENT` on the first send → `FAILED`; hold released; unwind.
25. `NOTHING_SENT` on a re-send → nothing.
26. Never received past the deadline → `FAILED`; unwind.
27. The corridor declines → `FAILED`; unwind.
28. The conversion succeeds but the payment fails → the customer is never debited; the quote is
    abandoned; the cover unwound.
29. A recall wins → `FAILED(RECALLED)`.
30. A recall arrives too late → `IN_TRANSIT`.
31. A recall and an acceptance race → one coherent outcome.
32. The delivery confirmation is lost → the sweep recovers it.
33. A return before delivery.
34. A return after delivery.
35. A return to a closed customer → parked.
36. A return redelivered → one effect.
37. An FX leg settles late → `EVIDENCED`.
38. One leg is missing → CRITICAL.
39. A leg amount differs → `AMOUNT_MISMATCH`.
40. Settlement in an unexpected currency → `CURRENCY_MISMATCH` (a settled currency, the wrong
    expectation), or `CURRENCY_NOT_SETTLED` at parse (a currency the counterparty does not
    settle).
41. A corridor fee beyond the schedule → `FEE_MISMATCH`.
42. The provider executes after we concluded `FAILED` → parked `UNKNOWN_EXTERNAL`.
43. The screening provider is down → nothing priced, nothing held.
44. A screening hit → `IN_REVIEW`; payments refused, shaped.
45. The clearance lapses before a quote → re-screened.
46. The clearance lapses between quote and authorization → `409 ScreeningRequired`.
47. A corridor is disabled mid-flight → new authorizations refused; in-flight payments
    unaffected.
48. A pricing policy is activated mid-quote → between the claim and the insert,
    `409 fx.PolicyStale` and nothing issued; after issue, the quote's pinned version applies.
49. A source has no active rule set → `RuleSetMissing`, alerted, no infinite retry.
50. The JDK changes a minor-unit count → the build fails and startup refuses.
51. A hot projection row under load → serialised, measured.
52. A restart mid-sweep → rows resume.
53. A return reported before the completion is known → the worker defers; the inquiry completes
    the credit, then applies the return, in one transaction.
54. A partial return, or one in another declared currency → nothing posted automatically; parked
    at grace; a person resolves it, and the payment becomes `RETURNED`.
55. A corridor file in a currency the counterparty does not settle → rejected
    `CURRENCY_NOT_SETTLED`, retained, never posted.
56. Identical provider references in the merchant payout source and a corridor source → each
    worker and the reference lookup stay within their own sources.
57. A beneficiary revoked during review → `REVOKED` at once with the common response; the
    review's outcome is recorded in kyc and leaves the beneficiary `REVOKED`.
58. A payee check of `NO_MATCH` or `UNAVAILABLE` with a `CLEAR` name screen → `IN_REVIEW`, never
    auto-cleared.
59. A screening hit racing an authorization → coherent; never a Tx1 committed against an
    `IN_REVIEW` beneficiary.
60. An owner at the live-quote cap → `429` before any provider call; a lapsed, unswept quote
    does not count.

## 15. Observability

**No amount in any tag or value** (ADR-0072, D32). Gauges are eager, read NaN when unreadable
(never zero), and aggregate with `max()` fleet-wide; counters count committed facts after
commit. Three new tag keys join `MetricNames` with written arguments, each bounded: **`pair`**
by the active pricing policy, **`corridor`** by the corridor policy, and **`provider`** by the
declarations.

| Series | Kind and tags | What it answers |
|---|---|---|
| `finapp.fx.quote` | counter `pair`, `outcome` (`issued`, `refused_rate_unavailable`, `refused_reference_stale`, `refused_implausible`, `refused_incoherent`, `refused_cap`) | Quote volume; rate trouble |
| `finapp.fx.quote.closed` | counter `pair`, `outcome` (`accepted`, `expired`, `cancelled`, `abandoned`) | Quote-to-trade conversion and expiry rates, by ratio |
| `finapp.fx.quote.open` | gauge `pair` | Live quotes |
| `finapp.fx.provider.quote.latency` | timer `provider`, `outcome` | Provider quoting health; failover |
| `finapp.fx.rate.age` | gauge `pair` | Seconds since the latest reference snapshot (alert past the maximum age) |
| `finapp.fx.rate.fetch` | counter `outcome` (`stored`, `not_newer`, `rejected`, `paced`, the four fetch failures, `crashed`) | The reference fetch's rounds and rows - why a pair is ageing (added by `P9-TSK-005`) |
| `finapp.fx.trade` | counter `pair`, `outcome` (`executed`, `reversed`) | Conversions |
| `finapp.fx.residual` | counter `currency`, `direction` (`debit`, `credit`, `none`) | Residual *frequency* (the amount is the revenue report) |
| `finapp.fx.cover` | counter `provider`, `kind`, `outcome` (`executed`, `rejected`, `requoted`, `off_plan`, `voided`) | Cover health |
| `finapp.fx.cover.unknown.active` / `.unknown.age` | gauge `provider` | `INV-LIFE-03` for covers (alert) |
| `finapp.fx.cover.open.age` | gauge `provider` | The oldest uncovered position leg (alert) |
| `finapp.fx.cover.latency` | timer `pair` | Acceptance → cover executed |
| `finapp.fx.proof` | gauge `purpose` | Currencies failing the FX books proof (must be 0) |
| `finapp.fx.plan.verdict` | gauge | 1 clean / 0 diverged |
| `finapp.payments.outbound.unknown.active` / `.unknown.age` | gauge `rail` | `INV-LIFE-03` for outbound credits (alert) |
| `finapp.payments.outbound.received.age` | gauge `rail` | The oldest provider-undecided instruction (alert past the declared decision deadline) |
| `finapp.crossborder.payment` | counter `corridor`, `outcome` (`submitted`, `in_transit`, `delivered`, `failed`, `cancelled`, `returned`) | Per-corridor volume and failure |
| `finapp.crossborder.payment.latency` | timer `corridor`, `stage` (`accept`, `deliver`) | Provider acceptance and delivery latency |
| `finapp.crossborder.payment.in.transit.age` | gauge `corridor` | The oldest undelivered payment (alert) |
| `finapp.crossborder.cancellation` | counter `outcome` (`recalled`, `too_late`) | The cancellation rate (free-option watch) |
| `finapp.crossborder.return` | counter `corridor`, `outcome` (`applied`, `deferred`, `not_applicable`, `resolved`) | Returns: applied automatically, deferred while the credit is in flight, not applicable (parked later by reconciliation, whose break meters count the park), resolved by a person |
| `finapp.kyc.counterparty.screening` | counter `outcome` (`clear`, `in_review`, `released`, `blocked`, `unavailable`) | Screening |
| `finapp.kyc.counterparty.review.pending` / `.review.age` | gauge | The review backlog (alert) |
| `finapp.reconciliation.rule_set.missing` | gauge `source` | A declared source without an active rule set (alert) |
| `finapp.fx.rate.sweeper.enabled`, `finapp.fx.quote.expiry.sweeper.enabled`, `finapp.fx.cover.sweeper.enabled`, `finapp.kyc.counterparty.sweeper.enabled`, `finapp.payments.outbound.sweeper.enabled`, `finapp.payments.outbound.return.sweeper.enabled` | gauge, one per §7 schedule, each built by its task | Schedules on (`crossborder` owns no schedule, so it has no such gauge) |
| `finapp.ledger.trial.balance` | gauge `currency` (existing) | Now eager for five currencies |

**Reports, not metrics** (ADR-0072): each audited, bounded at 100 rows with `truncated`, read in
one snapshot — **FX position by currency** with the open legs (`/reports/fx/position`); **FX
revenue** (margin expected vs realised per pair, the spread/markup split, residual
accumulation, realised P&L, off-plan covers; `/reports/fx/revenue?month=`); the **corridor
report** (volume, fees charged vs provider costs, returns;
`/reports/cross-border/corridors?month=`); and **trade provenance**. Settlement latency per
corridor comes from reconciliation's existing source meters on the new sources.

**The trace** Customer → Quote → Execution → Provider → Payment → Ledger → Settlement →
Reconciliation is carried twice. Correlation and causation: the quote request's correlation on
the quote, the authorization's on the payment, every sweeper restoring it per row; events
chaining by causation; spans through the platform `Spans` port with identifier attributes only
(`fx.quote.issue`, `fx.provider.quote`, `fx.convert`, `fx.cover.dispatch`, `fx.cover.resolve`,
`crossborder.authorize`, `payments.outbound.dispatch`, `payments.outbound.resolve`,
`payments.outbound.recall`, `payments.outbound.return.apply`, `kyc.counterparty.screen`). The
durable identifier chain: `GET …/cross-border/payments/{id}/trace` walks, by identifiers alone,
customer → quote → offer → payment → outbound credit (`E`, provider ref) → completion entry →
`CROSSBORDER_PAYOUT` expectation → settlement line → batch → bank line → allocation, and quote →
trade → cover → attempt (`Tn`, provider trade ref) → cover entry → leg expectations → FX lines.

**Alerts** (in `infra/prometheus/rules`, resolved against a live scrape): reference stale
beyond 120 s; implausible refusals > 0 in 5 min; cover unknown age > 5 min, or cover open age >
5 min; `finapp.fx.proof` > 0, or `finapp.fx.plan.verdict == 0`; outbound unknown age > 15 min,
or received age past the decision deadline; in-transit age past the delivery estimate; review
age > 24 h; a missing rule set; a cancellation-rate spike. **The dashboard row has twelve
panels.**

## 16. Milestones

Each milestone is small and vertical, and ships something a customer or operator can use, gated
by its own demonstrable acceptance.

| Milestone | Items | Ships | Acceptance (demonstrable) |
|---|---|---|---|
| **M9.1 Foundations** | `P9-TSK-001`…`-004` | A customer holds EUR, USD, JPY and BHD wallets | Both modules are build-graph facts with floors; `ExchangeRate` and the plan reproduce every §12.2 figure; JPY and BHD post on every flow; the 0/3 fee batch is green; every wallet resolver is keyed by currency |
| **M9.2 Rates and quotes** | `-005`…`-008` | **A price** | A quote at a provider-locked, band-checked rate under a four-eyes policy, in any of 20 pairs, by source or destination; expiry is an event exactly once under ten sweepers; a client `rate` field is refused |
| **M9.3 A conversion, booked and covered** | `-009`…`-012` | **A conversion** | 1,000.00 EUR → 1,079.60 USD booked in one entry, with margin and residual explicit; covered exactly once through a lost response; `FX_POSITION` 0; counterparty positions keyed; the FX source composed |
| **M9.4 FX explained and settled to cash** | `-013`, `P9-TST-002` | The operator sees the books proven | The FX books proof and plan verification are clean, and flipped by plants; FX legs reach cash in two hops; every FX discrepancy is typed; the value battery is green over ≥ 10,000 conversions |
| **M9.5 Corridors, beneficiaries, screening** | `-014`…`-017` | A beneficiary abroad, screened | The corridor rail and its source are declared truthfully, and no merchant reader can see a corridor line; corridor policy is four-eyes; a beneficiary is registered by provider reference, screened by kyc (an unverified payee always by a person), held and released by a reviewer, and revocable from any state without revealing it |
| **M9.6 A cross-border payment end to end** | `-018`…`-022` | **A payment abroad** | 1,000.00 EUR reaches a US beneficiary as 1,079.60 USD through an ambiguous dispatch and a retry, with one debit, one conversion, one fee and one clearing credit; a failed payment debits nothing and unwinds its cover; the corridor reaches cash |
| **M9.7 Return, cancellation, correction** | `-023`…`-025` | Money back | An exact return credits once in the destination currency and refunds the fee, even when reported before the completion is known; any other return parks and a person's resolution returns it, the payment `RETURNED`; a recall cancels before acceptance; an operator reversal unwinds exactly |
| **M9.8 A second provider of each kind** *(cut first, O8)* | `-026` | Failover and choice | Quote-time failover and corridor selection over overlapping coverage; each counterparty settles on its own position and never nets |
| **M9.9 Operating it, and proof** | `-027`, `X-TSK-013`, `P9-TST-001`, `P9-DOC-001` | Run and prove | Every §15 series from a fresh instance; reports audited; the trace walks every identifier; the Phase 6/7 send permits database-stamped; the storm green and probed; the Phase 9 exit review (`PHASE_GATES.md` §Phase 9: the twelve universal criteria, F1–F8, the five original Phase 9 criteria made measurable as criteria 1–5, and the transition's additions 6–22 — the exit criteria are stated there, not here) |

| Item | Title | Cx |
|---|---|---|
| `P9-TSK-001` | The `fx` and `crossborder` modules and schemas | S |
| `P9-TSK-002` | `ExchangeRate`, `Margin` and the conversion plan | M |
| `P9-TSK-003` | JPY and BHD become postable | M |
| `P9-TSK-004` | Multi-currency wallets | M |
| `P9-TSK-005` | Reference rates | M |
| `P9-TSK-006` | The FX provider port and simulator | M |
| `P9-TSK-007` | The pricing policy and FX administration | M |
| `P9-TSK-008` | The quote lifecycle | L |
| `P9-TSK-009` | Wallet conversion | L |
| `P9-TSK-010` | Counterparty-keyed clearing positions | M |
| `P9-TSK-011` | The FX provider's position, source and vocabulary | L |
| `P9-TSK-012` | The FX cover | L |
| `P9-TSK-013` | FX explained and settled to cash | M |
| `P9-TST-002` | The value-preservation and rounding battery | L |
| `P9-TSK-014` | The corridor rail, its position and its source | L |
| `P9-TSK-015` | The corridor policy and availability | M |
| `P9-TSK-016` | Counterparty screening in kyc, and the Phase 13 seams | M |
| `P9-TSK-017` | Cross-border beneficiaries | L |
| `P9-TSK-018` | Cross-border offers | M |
| `P9-TSK-019` | Cross-border authorization and dispatch | L |
| `P9-TSK-020` | Outbound resolution and completion | L |
| `P9-TSK-021` | Unwinding covers | M |
| `P9-TSK-022` | Corridor settlement to cash | M |
| `P9-TSK-023` | Cross-border returns | L |
| `P9-TSK-024` | Cancellation by recall | M |
| `P9-TSK-025` | Operator FX trade reversal *(deferral candidate, second in the cut order)* | M |
| `P9-TSK-026` | A second FX provider and a second corridor rail *(deferral candidate, first in the cut order)* | L |
| `P9-TSK-027` | Meters, spans, reports, the trace and the dashboard row | M |
| `P9-TST-001` | The FX and cross-border storm | L |
| `P9-DOC-001` | The Phase 9 exit review | L |

The cross-cutting items are `X-TSK-013` (database-stamped send permits for the Phase 5–7
outbound flows — scheduled in M9.9, after `P9-TSK-020` and before `P9-TST-001`), `X-TSK-014`
(explicit scale on pre-Phase-9 amount events, owner Phase 15) and `X-TSK-015` (callbacks as
hints for the Phase 5 and Phase 7 providers, owner Phase 15), each with its debt-register row.

Every task states the gate's twenty-three fields at design time; every task that can affect
money carries `DOD-FIN` with F1–F8; the ten-instance answer must be `PASS`, backed by counted
tests — a task without them answers `UNKNOWN` and is not complete. **If the phase must shrink**
(O8), `P9-TSK-026` goes first (one provider of each kind suffices; criterion 13 carries its
conditional clause), then `P9-TSK-025` (scenario 8 is then met by `UnwindRetryDatabaseTest`
alone, and criteria 9, 16 and 20 carry their conditional clauses), each cut recorded with
Phase 15 as owner. Never cut: unwinds, returns, cancellation, the proofs, the ten scenarios.

## 17. What Phase 9 must NOT implement

Treasury (hedging, netting of covers, position limits, liquidity, prefunding); Phase 14's
revaluation, functional or reporting currency, unrealised P&L and FX P&L reporting; real market
data and real provider connectivity (simulated only); cross rates and triangulation;
best-execution pricing across providers; `SHA`/`BEN` charge bearers and correspondent-chain
deductions; recall after acceptance; scheduled or recurring payments; merchant multi-currency
settlement and cross-currency merchant fees (`merchant.FeeCurrencyMismatch` and
`PayoutCurrencyMismatch` stay); moving the merchant payout onto a push rail (declined, ADR-0079
§9); multi-currency internal transfers (`CURRENCY_MISMATCH` stays, narrowed to "recipient has no
wallet in this currency"); reconciliation converting a mismatched amount; cross-currency balance
totals; Phase 13's KYC tiers, party residence, AML monitoring, rescreening the book, velocity
limits, risk scoring and transaction-level holds (the seams are built, `PermitAllUntilPhase13`);
any Kafka consumer deciding money; the operator actor type (Phase 15); manual rate entry or
price overrides; a live capability probe (discovery is the declared directories).

## 18. Risks

- **Scope: 27 tasks plus three, and `X-TSK-013` scheduled inside the phase.** Mitigated by
  vertical milestones (a price at M9.2, a conversion at M9.3, a payment at M9.6), `P9-TST-002`
  running from M9.4, and the cut order O8.
- **Phase 8 regression from counterparty keying** (`P9-TSK-010`). The change is additive
  (`Optional` counterparty; existing descriptors empty; no history moves); the Phase 8 storm and
  proof-group suites re-run inside `-010`, `-011` and `-014`, not only at the review.
- **The resolver rewrite** (`-004`) touches Phase 3–7 call sites. Mitigated by the static rule
  (`WalletsAreResolvedByCurrencyTest`) and the Phase 7 storm and dispute battery re-run inside
  the task.
- **Platform market risk** on a missed lock, a slow corridor decision (`RECEIVED`) or a
  cancelled payment's unwind. Bounded by the cover margin, the decision-deadline alert and
  recall-only-before-acceptance; visible as realised P&L, open-leg age and the cancellation-rate
  alert — never hidden in a customer amount.
- **Hot `FX_POSITION` rows**: every conversion updates its two currencies' projection rows.
  Measured in the storm (the p99 lock wait); Phase 16's sub-account path recorded.
- **Callbacks as hints add latency and provider load** (one inquiry per callback). Accepted for
  the security property; `X-TSK-015` unifies the doctrine.
- **Simulated providers are cleaner than real ones.** Mitigated by the contract batteries with
  fault injection, total mappings whose default is never success, and the recorded adapter
  obligations (dedupe before validity; screen provider answers for account data).
- **Operational readiness: policies and first rule sets need two persons before any traffic.**
  The `rule_set.missing` gauge and the empty-policy refusal are loud; runbook entries; the
  fixtures activate as two actors.

## 19. The first task

`P9-TSK-001` — the `fx` and `crossborder` modules and schemas — is marked `READY` by the
transition (2026-10-02), the one task that is. It lays build-graph facts and privilege floors
only, before any domain code (the `P5-TSK-001`, `P6-TSK-001` and `P8-TSK-001` precedent):

- **Scope:** the `settings.gradle.kts` includes and the build files (per-schema Flyway, from the
  sibling templates); `fx` `V001` and `crossborder` `V001` — owner `finapp_migrator`,
  `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app`, no tables; `FxModuleIsolationTest`
  and `CrossborderModuleIsolationTest`, each requiring exactly `{ledger, platform, sharedkernel}`
  and refusing every sibling (each other, `payments`, `kyc`, `accounts`, `settlement`,
  `reconciliation`) with planted probes; every sibling isolation test gaining both;
  `ProductionModules` from the classpath; `NoFloatingPointMoneyRulesTest`'s module guard over
  both.
- **Out:** every table, type, port, bean, route, permission and event.
- **Invariant:** `INV-LED-04` — neither module writes journal rows directly — and module
  isolation.
- **Ten instances:** no state; migrations run under Flyway's lock.
- **Accept:** the build green; the floors proven live (the ACL exactly
  `{finapp_migrator=UC, finapp_app=U}`, no `PUBLIC`); the isolation asymmetries demonstrated;
  every planted probe caught.
- **Definition of done:** `DOD-BUILD`, `DOD-ARCH`, `DOD-SEC`.

`P9-TSK-002` and `P9-TSK-016` depend only on `-001`, and `P9-TSK-003` on `-001` and `-002` (the
minor-unit pin test and the startup guard must exist before 0/3-minor currencies become
postable). The first task's completion gate marks exactly one next task `READY`; `P9-TSK-002` is
recommended, because it de-risks the arithmetic on which every later task posts.
