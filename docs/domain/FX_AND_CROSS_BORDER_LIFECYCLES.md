# FX and Cross-Border Lifecycles

Written by the Phase 8 → 9 transition (2026-10-02), the
`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` precedent: the document that names a phase's model
is written before the phase's first task, from the decisions in ADR-0074…0083, and corrected by
the tasks that implement it. *Every machine is built; the exit review (`P9-DOC-001`, 2026-10-07) read each against
its trigger and made every statement true.* The engineering plan
is [`PHASE_9_PLAN.md`](../project/PHASE_9_PLAN.md).

Related: ADR-0074 (conversion arithmetic) · ADR-0075 (the rate chain and the quote) · ADR-0076
(multi-currency accounting through `FX_POSITION`) · ADR-0077 (the decoupled cover) · ADR-0078
(counterparty-keyed clearing positions) · ADR-0079 (cross-border payments) · ADR-0080 (corridors,
beneficiaries and selection) · ADR-0081 (counterparty screening is kyc's) · ADR-0082 (FX and
corridor settlement and reconciliation) · ADR-0083 (callbacks are hints) — all Proposed at the
transition, indexed in [`docs/adr/README.md`](../adr/README.md) ·
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md) (the
settlement and reconciliation machinery Phase 9's legs and reports ride, unchanged) ·
[`RAIL_AND_DISPUTE_LIFECYCLES.md`](RAIL_AND_DISPUTE_LIFECYCLES.md) (the outbound discipline the
Outbound Credit reuses).

---

## 1. The concepts, kept apart

The canonical terms — FX Quote, Exchange Rate, FX Trade, Reference Rate, Provider Rate, Customer
Quote, Spread, Markup, Rate Lock, FX Cover, FX Position, Realised FX Result, Counterparty,
Corridor, Corridor Rail, Cross-Border Payment, Outbound Credit, Payment Offer, Cross-Border
Beneficiary, Counterparty Screening — are in [`GLOSSARY.md`](GLOSSARY.md); the rest is this
document's working vocabulary.

**The rate chain** (ADR-0075), every link stored and frozen on the executed conversion
(`INV-FX-05`):

| Link | Record | Role |
|---|---|---|
| Reference (mid) | `fx.rate_snapshot` | An independent source, for plausibility and disclosure only. Never executable; fails closed when stale on the database clock (`INV-FX-02`) |
| Provider (firm) | `fx.quote.provider_*` | The provider's firm quote: rate, stated counter-amount, reference, validity. The lock the cover later exercises |
| Internal | `fx.quote.internal_rate` | `round(provider_rate × (1 − spread), 10, rate_rounding)` — derived with a named rounding, stored for Phase 14's split, never left to a column |
| Customer | `fx.quote` | The customer rate and the frozen plan amounts; margin with stored spread/markup attribution; the disclosed margin over mid |
| Executed | `fx.trade` | `executed_rate = customer_rate` (equality `CHECK`); amounts copied from the plan, frozen |
| Cover executed | `fx.cover_execution` | The provider's executed sold/bought, rate, value date and the realised result |

**Five things that are not the same thing** (ADR-0079):

| Concept | Is | Owner | Ends when |
|---|---|---|---|
| **Cross-Border Payment** | The customer's instruction to deliver value abroad, at a disclosed price | `crossborder` | Delivered, returned or failed |
| **FX Conversion (Trade)** | The exchange of one currency for another at the accepted rate, posted through `FX_POSITION` | `fx` | Booked (or reversed): instantly |
| **Payment (Outbound Credit)** | One instruction on one rail to one destination, carrying the provider's ambiguity | `payments` | The provider completes or fails it |
| **Settlement** | Each counterparty's discharge of its clearing position in cash | `settlement` + `reconciliation` | The bank line is allocated |
| **Reconciliation** | The proof that our records match each counterparty's and the bank's | `reconciliation` | Every expectation allocated, every break closed |

| Kept apart | Why |
|---|---|
| **Quote** vs **Trade** | The quote is a frozen posting plan that may expire unexercised; the trade is its single execution, with postings (`INV-FX-04`) |
| **Trade** vs **Cover** | The trade is the customer's conversion, booked locally with no provider call in it (`INV-FX-09`); the cover is the platform's back-to-back provider leg, run separately (ADR-0077) |
| **Payment** vs **Outbound Credit** | The payment is the customer's product-level instruction; the credit is one instruction on one rail carrying `UNKNOWN` and `RECEIVED`, which never appear on the payment (D21) |
| **Beneficiary** vs **Counterparty** | The beneficiary is whom a customer pays, known only by the provider's opaque reference (`INV-RAIL-03`); a counterparty is an institution the platform settles with, owning its own clearing position (`INV-SET-05`, `INV-RAIL-04`) |
| **Screening** vs **the beneficiary's state** | The screening is kyc's recorded decision (`INV-KYC-01`); the beneficiary's state is crossborder's projection of it, shaped for tipping-off |
| **Return** vs **Reversal** | A return is the receiving side's act, admitted whenever it arrives (ADR-0073's rule); the platform never reverses an accepted credit (`INV-REV-03`). The operator FX trade reversal exists only for wallet conversions, four-eyes (§3.3) |

---

## 2. When the financial effect occurs

The pipeline is **Quote → Customer acceptance → FX execution → Financial posting → Cover →
Settlement → Reconciliation**, never collapsed: each step has its own record and identifier.

| Step | Record | Transaction | Financial effect |
|---|---|---|---|
| Quote | `fx.quote ISSUED` | Tx1 claim, then wire, then Tx2 insert | **None.** No hold, no posting |
| Customer acceptance | quote `ACCEPTED`, with actor, time and key | the acceptance transaction | For cross-border: a **hold** only, which is not a posting (ADR-0048) |
| FX execution | `fx.trade BOOKED` | conversion: the same transaction; cross-border: the outbound credit's completion | — |
| Financial posting | entry `fx-trade:<tradeId>` (conversion) / `outbound-credit:<id>` (cross-border) | the same transaction as execution | **Here.** Customer balances change; the position opens; margin and residual are recognised |
| Cover | `fx.cover` → `fx.cover_execution`, entry `fx-cover:<coverId>` | permit transaction, then wire, then outcome transaction | The position closes into a provider receivable/payable; expectations open |
| Settlement | provider reports (hop 1), bank statements (hop 2) | Phase 8 acceptance transactions | Fees recognised (hop 1); cash moves only on the bank's statement (`INV-SET-06`) |
| Reconciliation | items, allocations, breaks | Phase 8 matcher | None, except parking with its break, and four-eyes resolutions |

| Flow | The customer's financial effect occurs | The platform's provider effect occurs |
|---|---|---|
| Wallet conversion | At commit of the acceptance transaction: a book movement, **final on posting**, not reversible by the customer | At the cover's `EXECUTED` |
| Cross-border payment | At commit of the outbound credit's **completion**, which is the corridor provider's acceptance | Cover dispatched at authorization; executed at its outcome |

A payment that fails debits the customer nothing: the hold is released, the quote abandoned, the
executed cover unwound (`INV-XB-01`, D14). What the customer was shown — destination amount, fee,
total debit — is exactly what is held, posted and instructed (`INV-XB-03`).

---

## 3. The state machines

Every stored machine below gets the platform's standing three-layer enforcement: the aggregate's
exhaustive transition sweep refusing invalid edges; a generated schema `CHECK` plus an every-writer
transition trigger, both from the aggregate's `permittedTransitions()`; and an append-only history
recording actor id, actor type, occurred at and reason (`INV-LIFE-01/-02`, the ADR-0044 doctrine:
**states are earned by producers**). §3.12 lists the tables. Every window — quote validity,
reference staleness, screening validity, sweep bounds, the `NEVER_RECEIVED` deadline — is judged
**on the database clock** (in SQL, or against `DatabaseTime.now` read in the deciding transaction), never on an
instance clock (screening validity and the outbound credit's deadline since `P9-DOC-001`). `finapp_app` holds no `DELETE` on
any table of the `fx` or `crossborder` schemas.

### 3.1 FX quote (`fx.quote`)

```
 (creation: claim, wire, insert — `PHASE_9_PLAN.md` §12.3, keyed, two transactions)
                   │
                   v
                ISSUED ──accept──> ACCEPTED ──trade inserted──> EXECUTED
                │  │  │                 │
                │  │  │                 └──subject failed──> ABANDONED
                │  │  └──owner cancels──> CANCELLED
                │  └──expiry (sweep, or an acceptance that finds it late)──> EXPIRED
```

| Edge | Driver | Condition (on the locked row, `statement_timestamp()`) |
|---|---|---|
| (birth) → `ISSUED` | Quote creation (the customer, or `crossborder` through `CrossBorderFx`), keyed and two-transaction: the claim pins the `ACTIVE` pricing version on `fx.quote_request` **before** the provider call, the insert prices under that version or refuses `409 fx.PolicyStale` | Owner `ACTIVE`; pair enabled and unsuspended in the pinned version; provider coherent and plausible against a fresh reference; fewer than the cap of live quotes (pre-checked before any provider call, arbitrated by the `BEFORE INSERT` trigger under advisory namespace 5); at least 5 s of validity. Publishes `fx.FxQuoteIssued` |
| `ISSUED → ACCEPTED` | The conversion door, or cross-border authorization Tx1 | `expires_at > now`; owner and purpose match; pair unsuspended; the subject stored. Publishes `fx.FxQuoteAccepted` |
| `ISSUED → EXPIRED` | `FxQuoteExpirySchedule` (every instance, no lease, conditional update in pages), **or** an acceptance that finds `expires_at ≤ now` | `expires_at ≤ now`. One `fx.FxQuoteExpired` per row (`detectedBy` `SWEEP` \| `ACCEPTANCE`), written by whichever writer's conditional matched — the row lock serialises them |
| `ISSUED → CANCELLED` | The owner (keyed) | Still `ISSUED` and unexpired (`OLD.expires_at > now`; a lapsed, unswept quote answers `QuoteNotCancellable`). Publishes `fx.FxQuoteCancelled` |
| `ACCEPTED → EXECUTED` | The trade's booking: the same transaction for a conversion; the outbound credit's completion for cross-border | The trade row inserted (`UNIQUE (fx.trade.quote_id)`) |
| `ACCEPTED → ABANDONED` | The outbound credit's `FAILED` applier, through payments' `OutboundCreditComposition` → `app`'s `CrossBorderCompletion` → fx's `CrossBorderCompletionBooking.abandon` | The subject failed before booking. Publishes `fx.FxQuoteAbandoned` |

- **Invalid:** any edge out of `EXECUTED`, `ABANDONED`, `EXPIRED` or `CANCELLED`; `ISSUED →
  EXECUTED` (acceptance is never skipped); `EXECUTED` without a trade (the edge trigger refuses
  it).
- **Terminal:** `EXECUTED`, `ABANDONED`, `EXPIRED`, `CANCELLED`.
- **The `ISSUED` quote is the rate lock** (`INV-FX-04`): exclusive to its owner (another
  principal's id answers a uniform `404`), single-use (one `ACCEPTED` edge, and
  `UNIQUE (fx.trade.quote_id)`), bounded on the database clock —
  `expires_at = least(requested_at + provider_valid_for − cover_margin, issued_at + window)`,
  where `requested_at` is the claim transaction's `statement_timestamp()`, committed before the
  provider call. Provider skew and network time can only shorten the window, never lengthen it.
  No path accepts a quote without the clock comparison inside the transitioning statement.
- **The quote is a frozen posting plan.** Every amount the trade will post is computed once, at
  quote time, under the pinned pricing version, and frozen: the per-currency plan-identity
  `CHECK`, a freeze trigger guarding everything but the lifecycle columns, and
  `UNIQUE (fx.trade.quote_id)` make a value-creating plan unstorable (`INV-FX-04`, ADR-0076).
- **`ACCEPTED` is earned**: a cross-border quote holds it from authorization until the corridor
  provider accepts or fails. A conversion passes through it inside one transaction, writing both
  history rows.
- **A suspended pair** refuses acceptance (`409 fx.PairSuspended`) and lets the quote expire: the
  kill switch beats the outstanding quote. There is no `WITHDRAWN` state, because no producer
  would own it (states are earned).
- Candidate sourcing is recorded per provider in `fx.quote_sourcing_step` (the
  `routing_decision_step` shape); with every candidate exhausted, `503 fx.RateUnavailable`,
  nothing issued, **never a fallback to a cached or older rate**.

### 3.2 FX trade (`fx.trade`)

```
 (born in the booking transaction, with its entry)
BOOKED ──an approved operator reversal (conversions only)──> REVERSED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `BOOKED` | A conversion's acceptance transaction, or the outbound credit's completion | `UNIQUE (quote_id)`; `executed_rate = customer_rate` by `CHECK`; the amounts copied from the plan, frozen; the entry `fx-trade:<tradeId>` (conversion) or `outbound-credit:<id>` (cross-border) in the same transaction |
| `BOOKED → REVERSED` | The approval of an FX trade reversal (§3.3), conversions only | The exact mirror posts through `ReversalService` (`fx-trade:<tradeId>` in the `ledger.reverse` scope, ledger `V009`'s one-reversal bound); the cover consequence is evaluated under the lock order quote → trade → cover (§3.4) |

- **Invalid:** any edge out of `REVERSED`; reversing a cross-border trade (the credit was
  accepted; `INV-REV-03` — a return is the receiving side's act, §4).
- **Terminal:** `REVERSED`. `BOOKED` is final but for the one four-eyes exit.
- The trade is the frozen provenance record (`INV-FX-05`): `FxPlanVerification` recomputes every
  trade's plan from its stored columns alone, through the same pure function, and compares it with
  the posted entry line by line — report-only, a divergence CRITICAL.

### 3.3 FX trade reversal (`fx.trade_reversal`) — `P9-TSK-025`

```
PROPOSED ──a different approver──> APPROVED   (the reversal executes in the approval's transaction)
    └──a different rejecter──> REJECTED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `PROPOSED` | A holder of `FX_TRADE_REVERSE`, reasoned | The trade `BOOKED`, a conversion's; partial `UNIQUE (trade_id) WHERE status='PROPOSED'` |
| `PROPOSED → APPROVED` | A **different** person holding `FX_TRADE_REVERSE`, reasoned | Four-eyes `CHECK` (`approved_by ≠ proposed_by`); the approval's transaction executes the reversal: the trade `BOOKED → REVERSED`, the mirror entry, and the cover consequence (an executed cover gains its `UNWIND`, an unanswered one is re-judged by the wanted-position rule, §3.4) |
| `PROPOSED → REJECTED` | A different person, reasoned | The same `CHECK` shape for the rejecter |

- **Invalid:** any edge out of `APPROVED` or `REJECTED`; self-approval (refused at the domain and
  by the `CHECK`, each proven alone).
- **Terminal:** `APPROVED`, `REJECTED`. `INV-AUD-04`; ten racing approvers converge on one
  reversal (ledger `V009`'s bound under namespace 2 arbitrates).
- If the phase shrinks, this machine is the second cut (owner decision O8), recorded with
  Phase 15 as owner; scenario 8 is then met by the unwind path alone.
- *As built by `P9-TSK-025` (2026-10-06):* fx `V009` (not `V008`, which became the unwind): the
  machine held by the edge trigger, the four-eyes `CHECK`, the partial unique while `PROPOSED` and one
  `APPROVED` per trade, with an append-only `trade_reversal_event`; the approval locks quote -> trade ->
  reversal -> wallets, judges the destination wallet's available balance under its lock, posts the
  exact mirror, moves the trade `REVERSED` and evaluates the wanted-position rule under the held quote
  lock. Routes `POST /v1/operator/fx/trades/{id}/reversal` and `.../reversal/{rid}/approval|rejection`,
  `FX_TRADE_REVERSE` held by `LEDGER_OPERATOR`.

### 3.4 FX cover (`fx.cover`)

One cover of kind `COVER` per accepted quote, and one of kind `UNWIND` per quote whose subject was
abandoned, or whose trade was reversed, after its cover executed (`UNIQUE (quote_id, kind)`).
There is no netting, no discretionary timing and no position limit: those are treasury, which
Phase 9 must not implement (ADR-0077).

```
 (born in the accepting transaction — COVER — or the abandonment/reversal writer's — UNWIND)
          │
          v
     DISPATCHED ──the provider confirmed──> EXECUTED
        │    │
        │    └──answer lost──> UNKNOWN ──inquiry──> EXECUTED | REJECTED
        │
        └──definitive refusal──> REJECTED ──fresh firm quote passed the band──> DISPATCHED (attempt n+1)
                                     │
                                     └──no longer wanted──> VOIDED
```

| State | Meaning |
|---|---|
| `DISPATCHED` | A `COVER` is born with attempt 1's client reference `T₁` minted and stored before any send (`UNIQUE NOT NULL`, `INV-PAY-04`, `INV-FX-08`) and the first database-stamped send permit; an `UNWIND` is born with no attempt row - its first dispatch stores `T₁` and a fresh firm quote, still before any send (fx `V008`) |
| `UNKNOWN` | A send's answer was lost; resolved only by inquiry (`INV-LIFE-03`) |
| `EXECUTED` | The provider confirmed. The execution fact (`fx.cover_execution`, §3.11) and the cover entry `fx-cover:<coverId>` exist |
| `REJECTED` | A **definitive** refusal of the current attempt (`QUOTE_EXPIRED`, `PRICE_CHANGED`, `LIMIT`). Non-terminal |
| `VOIDED` | No longer wanted: its quote was abandoned (or its trade reversed) before any attempt executed |

- **Invalid:** `DISPATCHED | UNKNOWN → VOIDED` — a sent attempt may have executed, so an unwanted
  cover is voided only from `REJECTED`; any edge out of `EXECUTED` or `VOIDED`.
- **Terminal:** `EXECUTED`, `VOIDED`. Attempts are rows in `fx.cover_attempt
  ((cover_id, attempt) UNIQUE)`, each with its own `client_reference` (`UNIQUE`) and
  `provider_quote_ref`.
- **The send permit is stamped by the database**: `last_dispatched_at = statement_timestamp()`
  committed before every send, by a conditional, strictly forward renewal held by a trigger
  (`X-TSK-013` aligned the Phase 5–7 permits to the same rule, 2026-10-07).
- **No conclusion without knowledge, and the cover is never concluded "never received"** (D13).
  On `UNRECOGNISED` or `INDETERMINATE`, `FxCoverSchedule` renews the permit and re-sends the same
  `Tn`; the provider's contract-tested obligation is *dedupe on our reference before judging the
  quote's validity*. A new reference is minted **only** after a definitive `REJECTED`, after a
  **fresh firm quote** for the exposure's fixed leg from the same provider has passed the band
  (`INV-FX-08`). An implausible requote leaves the cover `REJECTED`, retried with backoff,
  alerted.
- **The cover always closes exactly the plan's position legs.** Any difference between the
  executed amounts and the plan's legs posts to `FX_REALISED_GAINS`/`LOSSES` in that leg's
  currency; a provider deviating on the fixed leg is flagged `executed_off_plan`, counted and
  alerted. The leg expectations copy the **executed** clearing lines, so reconciliation compares
  the provider's evidence with what it confirmed.
- **Wanted position, not commands.** A quote wants a cover iff its status is `ACCEPTED` or
  `EXECUTED` *and* its trade is not `REVERSED`: `REJECTED` + wanted ⇒ requote; `REJECTED` +
  unwanted ⇒ `VOIDED`; `EXECUTED` + unwanted ⇒ create the `UNWIND`. Both the cover's applier and
  the abandonment or reversal writer evaluate this under the lock order *quote → trade → cover*,
  and `UNIQUE (quote_id, kind)` makes exactly one unwind. The unwind replicates the cover's fixed
  leg in the opposite direction at a fresh firm quote from the same provider, through the same
  dispatch discipline.
- **Callbacks are hints** (ADR-0083): a verified FX callback is retained and triggers an immediate
  `inquire(T)`. Only the inquiry's answer moves the cover.

### 3.5 Cross-border payment (`crossborder.payment`)

```
SUBMITTED ──the corridor provider accepted──> IN_TRANSIT ──delivery confirmed──> DELIVERED
    │                                             │                                 │
    └──failed──> FAILED                           └────────> RETURNED <─────────────┘
```

| State | Business meaning | Producer | Money |
|---|---|---|---|
| `SUBMITTED` | The customer authorized the offer. The rate is locked (quote `ACCEPTED`), funds are held, the instruction is committed for dispatch, and the cover is dispatched | Authorization Tx1 | Hold only |
| `IN_TRANSIT` | The corridor provider took irrevocable responsibility. The customer is debited, the conversion booked, the fee earned, and the provider owed | `OutboundCreditComposition.completed` | The completion entry |
| `DELIVERED` | The provider confirms the beneficiary's institution credited the beneficiary | `OutboundCreditComposition.delivered` | None |
| `RETURNED` | Funds came back after acceptance; the return was applied | `OutboundCreditComposition.returned` (automatic), or `ResolvedCorridorReturns` inside a person's approval (§4) | The return entry, or the person's transfer plus the fee refund |
| `FAILED` | The instruction will never execute: `DECLINED`; `PROVIDER_UNAVAILABLE` (first-send `NOTHING_SENT`); `NEVER_RECEIVED` (past the declared deadline + margin since the latest permit); `RECALLED` (the customer's cancellation, confirmed by the provider) | `OutboundCreditComposition.failed` | **None.** Hold released, quote `ABANDONED`, cover unwound if it executed |

- **Edges:** `SUBMITTED → IN_TRANSIT | FAILED`; `IN_TRANSIT → DELIVERED | RETURNED`;
  `DELIVERED → RETURNED`.
- **Invalid:** any edge out of `FAILED` or `RETURNED`; `SUBMITTED → DELIVERED | RETURNED` directly
  — when one inquiry answer reports several facts at once (accepted and delivered, or accepted
  and returned), the payment takes each edge **in order inside one transaction**, writing one
  history row and one event per edge, as a conversion's quote passes through `ACCEPTED` (§3.6).
- **Terminal:** `FAILED`, `RETURNED`. `DELIVERED`'s only edge is `RETURNED`: a return is the
  receiving side's act and is admitted whenever it arrives, because the external fact comes first
  (the ADR-0073 rule); the corridor's declared return window only governs alert ageing.
- The edges are held by a generated `CHECK`, the every-writer trigger `payment_edge_is_legal`,
  the domain, and `crossborder.payment_event` history.
- **Customer-facing statuses** (shaped): `PROCESSING` (`SUBMITTED`, whatever the outbound credit's
  `DISPATCHED`, `UNKNOWN` or `RECEIVED`), `SENT` (`IN_TRANSIT`), `DELIVERED`, `RETURNED`,
  `CANCELLED` (`FAILED(RECALLED)`, or `FAILED(NEVER_RECEIVED)` after a cancellation request),
  `FAILED` (every other `FAILED`).
- **States deliberately absent**, each with its reason: `INITIATED`/`VALIDATED` (no committed row
  holds them — validation runs inside Tx1; states are earned), `COMPLIANCE_REVIEW` (screening
  precedes pricing and holds the *beneficiary*, §3.7 — a payment holding a locked rate cannot
  wait hours for a person), `FX_QUOTED` (an unaccepted offer is not a payment), `AUTHORIZED`
  (Tx1 commits the dispatch, so the state is `SUBMITTED`), `PROCESSING` (a customer-facing shape
  only), `SETTLED` (settlement is reconciliation's expectation status, never a payment state,
  `INV-SET-01`), `REVERSED` (`INV-REV-03`), `CANCELLED` (a confirmed recall is
  `FAILED(RECALLED)`).

### 3.6 Outbound credit (`payments.outbound_credit`)

ADR-0057's four outbound states plus `RECEIVED`, under the same permit, `UNKNOWN`, sweep and
evidence machinery (D16).

```
DISPATCHED ──accepted──> COMPLETED
   │  │ └──acknowledged, not committed──> RECEIVED ──> COMPLETED | FAILED
   │  └──answer lost──> UNKNOWN ──inquiry──> RECEIVED | COMPLETED | FAILED
   └──failed──> FAILED
```

| State | Meaning |
|---|---|
| `DISPATCHED` | Born in authorization Tx1 with the end-to-end reference `E` minted and stored before any send, the pinned routing decision and the first database-stamped permit. The provider may have it |
| `UNKNOWN` | A send's or an inquiry's answer was lost. Resolution is by inquiry only (`INV-LIFE-03`) |
| `RECEIVED` | The provider acknowledged the instruction with its reference but has not committed: its own screening is pending. `NEVER_RECEIVED` can no longer apply |
| `COMPLETED` | The provider accepted irrevocable responsibility (`FINAL_ON_ACCEPTANCE`). The claim, the hold released, the completion entry, the trade booked, the quote `EXECUTED`, the payment `IN_TRANSIT` and the `CROSSBORDER_PAYOUT` expectation, all in the acting applier's transaction |
| `FAILED` | `DECLINED` \| `PROVIDER_UNAVAILABLE` (first send only) \| `NEVER_RECEIVED` \| `RECALLED` |

- **Edges:** `DISPATCHED → RECEIVED | COMPLETED | FAILED | UNKNOWN`;
  `UNKNOWN → RECEIVED | COMPLETED | FAILED`; `RECEIVED → COMPLETED | FAILED`.
- **Terminal:** `COMPLETED`, `FAILED`.
- **Rules:** `dispatch_key` unique per customer; `last_dispatched_at` database-stamped and
  strictly forward; `NOTHING_SENT` fails only a first send; `NEVER_RECEIVED` requires
  `DISPATCHED` or `UNKNOWN`, re-judged on the locked row past the rail's declared
  `outcomeDeadline` + margin since the **latest** permit; an instruction with a recall requested
  is **never re-sent**.
- **Frozen by trigger:** `E`, the rail, the destination reference, the instructed `Money`, the
  held `Money`, the hold id and the subject (`INV-XB-03`).
- **An answer that implies acceptance, on a credit not yet `COMPLETED`** — the provider can
  execute, lose its response, then deliver or see the money returned before our sweep inquires.
  An inquiry (or a hinted inquiry) answering `Accepted{providerRef, deliveredAt}` or
  `Returned{returnRef, amount, at}` while the credit is `DISPATCHED`, `UNKNOWN` or `RECEIVED` is
  applied by `OutboundCreditOutcomes` **in one transaction, in order**: (1) the completion;
  (2) then, if the answer carries `deliveredAt`, the delivery; (3) then, if the answer is
  `Returned` and the return is applicable (§4), the return. A return that is not applicable is
  not applied; the completion and any delivery still are. No new edge is needed: a `Returned`
  answer proves the provider accepted, so the credit's own edge is the ordinary one to
  `COMPLETED`.
- The outbound credit publishes no event of its own: its business consequence is the payment's
  event, written in the same transaction (one fact, one event). Its facts recorded beside the
  machine — `provider_reference`, `delivered_at`, the recall columns, the return — are each set
  once by a conditional (§3.11).

### 3.7 Cross-border beneficiary (`crossborder.beneficiary`)

```
PENDING_SCREENING ──CLEAR with a payee MATCH──> ACTIVE
      │                                          │   ^
      └──hit · indeterminate · unverified──> IN_REVIEW ──a person releases──┘
                                                 │
                                                 └──a person blocks──> BLOCKED

 PENDING_SCREENING | IN_REVIEW | BLOCKED | ACTIVE ──the customer──> REVOKED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `PENDING_SCREENING` | Registration: the corridor provider tokenises the destination (`corridor_selection`, recomputable), the payee check runs, and the screening is requested synchronously | Step-up applied; no account identifier and no name stored in `crossborder` (`INV-RAIL-03`) — the name goes to kyc only, encrypted |
| `PENDING_SCREENING → ACTIVE` | `ScreeningOutcomeListener`, in the screening decision's transaction | The screening is `CLEAR` with `decision_basis` rules satisfied — an automatic `CLEAR` only with a payee `MATCH` (`INV-XB-02`) |
| `PENDING_SCREENING → IN_REVIEW` | The listener | The screening went `IN_REVIEW`: a hit, an indeterminate result, or an unverified payee (`NO_MATCH`/`UNAVAILABLE` payee check, reason `PAYEE_UNVERIFIED`) |
| `IN_REVIEW → ACTIVE` | The listener, on a person's `RELEASED` | `INV-KYC-04`: a reviewer with `COUNTERPARTY_SCREENING_REVIEW`, a reason, audited |
| `IN_REVIEW → BLOCKED` | The listener, on a person's `BLOCKED` | The same door |
| `ACTIVE → IN_REVIEW` | The listener, on a re-screen hit or an unverified payee at re-screen | Re-screening runs at quote time when the clearance's `decided_at` plus the pinned corridor's screening validity has passed on the database's clock (there is no `clear_until` column) |
| any non-terminal → `REVOKED` | The customer | **An identical `200` body (`REVOKED`) from every state**, so revocation reveals no review status |

- **Invalid:** `PENDING_SCREENING → PENDING_SCREENING` (`UNAVAILABLE` keeps the row and records
  an attempt); any edge out of `REVOKED` — a revoked beneficiary is never re-activated: a later
  screening or review outcome leaves it `REVOKED` (the listener is a no-op; the kyc screening and
  its review run on, their outcome recorded in kyc only), and a re-registration is a new
  beneficiary with a new screening.
- **Terminal:** `REVOKED`.
- **Payability** (`INV-XB-02`): funds are instructed, and offers priced, only for a beneficiary
  that is `ACTIVE` with a current `CLEAR` or person-`RELEASED` screening, on an available
  corridor — judged in-lock (`FOR SHARE`) at the quote's Tx1 and the authorization's Tx1. Every
  non-payable state is refused with the same bytes: `422 crossborder.BeneficiaryNotPayable`.
- **Tipping-off shapes:** the customer-facing status shows `PENDING_VERIFICATION` for both
  `PENDING_SCREENING` and `IN_REVIEW`, and `UNAVAILABLE` for `BLOCKED`.

### 3.8 Counterparty screening (`kyc.counterparty_screening`)

```
REQUESTED ──the provider answered──> CLEAR | IN_REVIEW
    │
    └──provider unavailable──> UNAVAILABLE ──retry──> CLEAR | IN_REVIEW

IN_REVIEW ──a person, with a reason──> RELEASED | BLOCKED
```

| Edge | Driver | Condition |
|---|---|---|
| (birth) → `REQUESTED` | Registration, or a re-screen inserted in the quote's Tx1 when the clearance has lapsed | The subject is name + country + entity type, **no bank identifier**; the name encrypted with AAD bound to the screening id, held only by kyc |
| `REQUESTED → CLEAR` | The screening decision transaction, from the provider's verdict **as evidence** (`INV-KYC-01`) | `decision_basis = AUTOMATIC` only with a payee `MATCH` (`CHECK` on the stored payee verdict); the kyc `policy_version` and `decided_at` recorded on every outcome, `CLEAR` included; `clear_until` renewed |
| `REQUESTED → IN_REVIEW` | The decision transaction | A `HIT` or `INDETERMINATE` verdict, or an unverified payee (`PAYEE_UNVERIFIED`) — never auto-cleared, never auto-rejected (`INV-KYC-04`) |
| `REQUESTED → UNAVAILABLE` | The decision transaction | The provider did not answer. The beneficiary stays unpayable; nothing is held, nothing is priced (fail safe) |
| `REQUESTED \| UNAVAILABLE → CLEAR \| IN_REVIEW` | `CounterpartyScreeningRetrySchedule` (due `REQUESTED` rows past their two-minute permit, and `UNAVAILABLE` ones) | Row conditionals; `UNIQUE (screening_id, attempt)` |
| `IN_REVIEW → RELEASED \| BLOCKED` | A person with `COUNTERPARTY_SCREENING_REVIEW` (`KYC_REVIEWER`) | `decision_basis = REVIEWER` ⇔ a deciding person (`CHECK ((decision_basis = 'REVIEWER') = (decided_by IS NOT NULL))`), a reason code and narrative required, audited |

- **Terminal:** `CLEAR`, `RELEASED`, `BLOCKED` (for that screening — no edge leaves any of them;
  a re-screen, a lapsed clearance included, is a new screening row).
- The screening is kyc's recorded decision; the beneficiary's state (§3.7) is crossborder's
  projection, moved by the listener in the same transaction. Four-eyes clearance is recorded as a
  Phase 13 policy option; rescreening the whole book, list-change sweeps and ongoing monitoring
  are Phase 13's.

### 3.9 Pricing and corridor policy versions

One machine, two tables (`fx`'s pricing policy version, `P9-TSK-007`; `crossborder`'s corridor
policy version, `P9-TSK-015`), on reconciliation's rule-set precedent — with **no seed
exemption**: no policy version is seeded by migration, and v1 of each is activated four-eyes
(D26).

```
PROPOSED ──a different approver──> ACTIVE ──only beside its successor──> RETIRED
    └──a different rejecter──> REJECTED
```

- Content frozen from `PROPOSED`; one `PROPOSED` and one `ACTIVE` per scope (partial uniques);
  activator ≠ proposer by `CHECK`; a retirement committed only beside its successor, by a
  deferred constraint trigger, for every writer.
- A decision pins the version it was priced under — on `fx.quote_request` and
  `crossborder.offer_request` at the claim, `409 PolicyStale` past a successor (`INV-HIST-04`).
  A version activated **after** issue changes nothing: the quote carries its pinned version.
- Routing policy v5 is the stated exception, by ADR-0060's own decision: it goes through
  ADR-0060's existing single-person operator door, never a migration (D26).

### 3.10 Availability facts and the enable proposals

**Availability — pair, provider, corridor — is an append-only fact row per change, not a
machine.** Disabling (the kill switch) takes one person with a reason, written at once, because
making money *stop* moving must never wait for a second person. Enabling goes the other way,
through a proposal machine (`fx.availability_enable_request` for pairs and providers,
`P9-TSK-007`; `crossborder.corridor_enable_request`, `P9-TSK-015`):

```
PROPOSED ──a different approver──> APPROVED   (the enabling fact is appended in the approval's transaction)
    └──a different rejecter──> REJECTED
```

- Both edges terminal; every-writer edge trigger; four-eyes `CHECK`; partial
  `UNIQUE (subject) WHERE status='PROPOSED'`; a reason on each act; `INV-AUD-04`.
- A corridor whose policy needs data the platform does not hold (the `required_data` flag) cannot
  be approved.
- Availability is the newest append-only fact, read unlocked under READ COMMITTED inside the deciding transaction,
  never from memory; only its writers serialise (advisory namespaces 7 and 8), so a disable committing beside an
  acceptance does not block it: a suspension beats every later decision, and outstanding quotes simply expire.

### 3.11 The born-once facts

Facts, not machines: each is one row, written once, with no status column to walk. Each is held
append-only (an every-writer trigger refuses `UPDATE` and `DELETE` where noted) and each
contended one is a ★ born-once arbiter with a lock-bypass probe in its task.

| Fact | Table | Born | Once by |
|---|---|---|---|
| Quote request | `fx.quote_request` | The quote claim's Tx1, pinning the pricing version before the provider call | The idempotency claim; a takeover converges on the same `QR` |
| Offer request | `crossborder.offer_request` | The cross-border quote's Tx1, pinning the corridor version | The same claim |
| Rate snapshot | `fx.rate_snapshot` | `FxRateFetchSchedule` | `ON CONFLICT (source, pair, observed_at) DO NOTHING`, inserted only when newer than the pair's latest — a stuck feed replaying an old observation never looks fresh |
| Trade | `fx.trade` | The booking transaction | ★ `UNIQUE (quote_id)` |
| Cover execution | `fx.cover_execution` | The acting applier's outcome transaction | ★ `cover_id` PK, `UNIQUE (provider_code, provider_trade_ref)`, `journal_entry_id UNIQUE` — the arbiter that holds with the conditional removed |
| Unwind | `fx.cover` (kind `UNWIND`) | The abandonment or reversal writer, or the cover applier — whichever finds the position unwanted | ★ `UNIQUE (quote_id, kind)` under the lock order quote → trade → cover |
| Payment | `crossborder.payment` | Authorization Tx1 | ★ `UNIQUE (quote_id)`; the claim per principal |
| Outbound credit return | `payments.outbound_credit_return` | The applier (`applied_by = 'APPLIER'`) or a resolution's approval (`'RESOLUTION'`, with its `resolution_id` — `CHECK ((applied_by = 'RESOLUTION') = (resolution_id IS NOT NULL))`) | ★ `UNIQUE (outbound_credit_id)` (the sole arbiter as built: no return-reference claim, `V027`); for `APPLIER`, the returned `Money` equal to the instructed `Money` by an every-writer trigger reading the frozen credit (`INV-XB-04`) |
| Cancellation request | `crossborder.cancellation_request` (`P9-TSK-024`) | The customer | `UNIQUE (payment_id)`, append-only (the every-writer trigger refuses `UPDATE` and `DELETE`). Its outcome lives on the outbound credit (`recall_outcome`) and the payment: a recall is honoured only on the provider's definitive `RECALLED`, never assumed, and an instruction with a recall requested is never re-sent (D22) |
| The outbound credit's side facts | `payments.outbound_credit` columns | `provider_reference` from the first answer that carries it; `delivered_at`; `recall_requested_at`, `recall_outcome` (`RECALLED \| REFUSED`; the recall is paced by the sweep's poll, no recall permit) | Each set once by a conditional |

### 3.12 The three layers, per machine

| Machine | Table (migration, task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| FX quote | `fx.quote` (`V005`, `P9-TSK-008`) | `quote_event` | The per-currency plan-identity `CHECK`; the freeze trigger; the live-quote cap `BEFORE INSERT` trigger under namespace 5; `expires_at` bounds `CHECK`; `EXECUTED` refused without a trade |
| FX trade | `fx.trade` (`V006`, `P9-TSK-009`; `REVERSED` already in `V006`'s machine, reached by `P9-TSK-025`) | The row and its entry; `quote_event` carries the quote's side | ★ `UNIQUE (quote_id)`; the rate-equality `CHECK`; amounts frozen |
| FX trade reversal | `fx.trade_reversal` (`V009`, `P9-TSK-025`) | The row's decision columns, once, and the append-only `fx.trade_reversal_event` | Partial `UNIQUE (trade_id) WHERE status='PROPOSED'`; the four-eyes `CHECK`; ledger `V009`'s one-reversal bound |
| FX cover | `fx.cover`, `fx.cover_attempt` (`V006`, `P9-TSK-009`; execution by `V007`, `P9-TSK-012`) | `cover_attempt` rows; the permit columns | `UNIQUE (quote_id, kind)`; `UNIQUE (cover_id, attempt)`; `UNIQUE client_reference`; the forward-only permit trigger; ★ `fx.cover_execution` (`V007`) |
| Cross-border payment | `crossborder.payment` (`V005`, `P9-TSK-019`) | `payment_event` | ★ `UNIQUE (quote_id)`; `payment_edge_is_legal`; the offer's disclosed amounts copied and frozen (`INV-XB-03`) |
| Outbound credit | `payments.outbound_credit` (`V025`, `P9-TSK-019`; the claim subject by `V026`, `P9-TSK-020`; the return by `V027`, `P9-TSK-023`) | The payment's event (one fact, one event); the evidence rows | `dispatch_key` unique per customer; the frozen-columns trigger; the forward-only permit; ★ `scheme_execution_claim (rail, provider_reference)`; ★ `outbound_credit_return` and its applier-amount trigger |
| Beneficiary | `crossborder.beneficiary` (`V003`, `P9-TSK-017`) | `corridor_selection` and its steps; the screening projection columns | The edge trigger with `REVOKED` reachable from every non-terminal state; no identifier or name columns exist to leak (`INV-RAIL-03`) |
| Counterparty screening | `kyc.counterparty_screening` (kyc `V009`, `P9-TSK-016`) | Attempt and evidence rows, append-only | `UNIQUE (screening_id, attempt)`; the decision-basis `CHECK`s (a person with a reason for `REVIEWER`; no `AUTOMATIC` `CLEAR` without a payee `MATCH`) |
| Pricing policy version | `fx` `V004` (`P9-TSK-007`) | The decision columns, once | One `ACTIVE`/`PROPOSED` partial uniques; approver ≠ proposer `CHECK` with no seed exemption; retirement only beside its successor (deferred); `spread + markup > 0` |
| Corridor policy version | `crossborder` `V002` (`P9-TSK-015`) | The decision columns, once | The same shape |
| Availability-enable proposals | `fx.availability_enable_request` (`V004`), `crossborder.corridor_enable_request` (`V002`) | The decision columns, once | Partial `UNIQUE (subject) WHERE status='PROPOSED'`; the four-eyes `CHECK` |
| Cancellation request | `crossborder.cancellation_request` (`V006`, `P9-TSK-024`) | The row itself — born once | `UNIQUE (payment_id)`; append-only by trigger |

Each state arrives with its producer's task, so a deferral removes states rather than stranding
them (the Phase 8 precedent — its cut-order decision, Phase 8's O6): without `P9-TSK-025` there is no
`REVERSED` on the trade and no reversal machine; without `P9-TSK-024` there is no cancellation
request and no `FAILED(RECALLED)` producer; cutting `P9-TSK-026` (M9.8) removes the `-b`
counterparties and sources, nothing else.

---

## 4. Returns: two channels, one fact, and the way out

A corridor return reaches the platform on two channels: the corridor provider's inquiry answer
(`Returned{returnRef, amount, at}`, hinted by a callback or found by the resolution sweep), and
its settlement report's `PAYOUT_RETURNED` line, which `OutboundReturnWorker` (in `app`, the
`PayoutReturnSweep` precedent) reads from reconciliation's waiting items. Both converge on
`OutboundCreditComposition.returned`, behind ★ `UNIQUE (outbound_credit_return.outbound_credit_id)`,
and the posting key `crossborder-return:<id>` (`INV-XB-04`) - no return-reference claim was built (`V027`).

**Which items the worker sees.** Reconciliation's `WaitingPayoutReturns` is scoped by source: the
corridor worker is handed the sources whose settled position is `CORRIDOR_CLEARING` (any
counterparty), the merchant `PayoutReturnSweep` those whose position is `PAYOUT_CLEARING`, both
read off the compiled register. Neither can ever see the other's lines, so identical provider
references in the two sources can never credit a merchant with a cross-border customer's money,
or the reverse. `JdbcInternalReferenceLookup` is scoped the same way.

**When a return applies automatically** (one rule for both channels, judged on the locked rows):

| The outbound credit, and the return | Outcome |
|---|---|
| `COMPLETED`; the return is in the instructed currency `D`, for exactly the instructed amount; the customer is `ACTIVE` | **Applied**: DR `CORRIDOR_CLEARING` / CR the customer's wallet in `D` (opened if absent), the transfer fee refunded (DR `FEE_REVENUE` / CR the wallet in `S`), the payment `→ RETURNED`, a `CROSSBORDER_RETURN` expectation (operation-anchored). The value is **never re-converted at the original rate**; the spread stands, because the conversion was performed |
| `DISPATCHED`, `UNKNOWN` or `RECEIVED` (the completion not yet known) | **The worker defers**: nothing written, nothing parked, the item left `UNMATCHED`. The resolution sweep inquires the credit; an inquiry answer implying acceptance completes it first (§3.6), and the next worker tick (or the same inquiry's `Returned`) applies the return — ADR-0073 §4's "a return reported before its payout resolves waits for it" |
| `COMPLETED`, but a different amount (a partial return, or more than was sent), a different declared currency, or a customer no longer `ACTIVE` | **Not applicable**: nothing written. At grace the item parks as `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)`, HIGH, DR `CORRIDOR_CLEARING` / CR `SUSPENSE_UNMATCHED`, owned by its break (ADR-0073 §5). An inquiry channel's non-applicable `Returned` retains its evidence and counts `finapp.crossborder.return{outcome="not_applicable"}` |
| `FAILED` (we concluded `NEVER_RECEIVED` and the provider executed anyway) | **Contradiction**: the lookup's terminal answer parks the line at once as `REVERSAL_MISMATCH(TERMINAL_STATE_CONTRADICTED)`, beside the late execution's `UNKNOWN_EXTERNAL`; a person offsets the two |
| Still non-terminal when the item's grace expires | The grace leg parks it as `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)`. If an inquiry later completes the credit and applies the return, the return's new `CROSSBORDER_RETURN` expectation is found by the matcher's rematch leg, which unparks the line and closes the break `EVIDENCED` (the existing late-evidence path) |

A currency the corridor counterparty does not settle at all never reaches this table: the
corridor source's descriptor carries the declaration's settled currencies, and a file in any
other currency is rejected and retained at parse with `CURRENCY_NOT_SETTLED`, never posted.

**Why a partial return is not credited automatically.** The provider's line and our posting would
agree, so no reconciliation break could ever surface the shortfall, and the customer would
silently bear an intermediary's deduction under a promise of `OUR`. A person sees every such case
(`INV-XB-04`).

**The way out of a parked corridor return** (T-g, ADR-0073 §5's precedent extended):

- The usual decision is a four-eyes `TRANSFER_TO_ACCOUNT` (ADR-0071) of the parked value to the
  customer's `CUSTOMER_WALLET` in the item's currency, `ACTIVE` and share-locked. A customer
  holding no wallet in that currency adds it first (`P9-TSK-004`'s door); the resolution never
  opens accounts.
- Its approval, when the break's item is a corridor `PAYOUT_RETURNED` line whose operation is an
  outbound credit, also calls **`ResolvedCorridorReturns`** (declared by reconciliation,
  implemented in `app`, the `SettlementBatchRepudiations` precedent) in the same transaction:
  it inserts the born-once `outbound_credit_return` with `applied_by = RESOLUTION`, posts the fee
  refund (`crossborder-return-fee:<outboundCreditId>`), and moves the payment
  `IN_TRANSIT | DELIVERED → RETURNED` (basis `RESOLVED`). It opens no expectation: the park
  already moved the value off `CORRIDOR_CLEARING`.
- If the return fact already exists (an inquiry applied the return first), the port's insert
  conflicts, the whole approval rolls back `409 reconciliation.ResolutionStale`, and the
  matcher's rematch leg closes the break `EVIDENCED` instead. If the approval wins, an automated
  application finds the fact and writes nothing. Nothing is ever credited twice.
- The port also refuses, rolling the approval back the same way, while the outbound credit is not
  `COMPLETED`: a person resolves a return only once its credit's outcome is known. A credit that
  ends `FAILED` leaves the parked line to be offset against the late execution's
  `REVERSAL_MISMATCH(TERMINAL_STATE_CONTRADICTED)` (a four-eyes `OFFSET_SUSPENSE`; *corrected by the Phase 9 → 10
  transition, 2026-10-07 — the lookup answers `TERMINAL` for a `FAILED` credit, never `UNKNOWN_EXTERNAL`*), never
  transferred to the customer. The credit is found by the line's end-to-end reference **or its provider reference**
  (the claim's path, as the matcher and the worker find it; *the Phase 9 → 10 transition: a line whose `E` named nothing
  ours was approved as an ordinary transfer and a later inquiry applied the same return again*).
- A closed customer's value stays parked with its HIGH break, aged and escalated: there is no
  `ACTIVE` wallet to credit. Funds owed to a closed customer are recorded as debt with Phase 15
  as owner.

---

## 5. The owner's transition decisions stated here

Settled at the Phase 8 → 9 transition (2026-10-02) on the design's recommendations; each is a
transition decision the owner may revisit, recorded with the phase's plan.

| # | Decision | Where it bites |
|---|---|---|
| O1 | The platform is principal; the conversion is booked at acceptance; one back-to-back cover per accepted quote | §2, §3.1, §3.4 |
| O2 | A return is applied automatically only when it is exactly the instructed credit coming back, credited in that currency with the fee refunded; any other return is parked and decided by a person, whose resolution records the return on the payment | §4 |
| O3 | Both fixed sides, `FIXED_SOURCE` and `FIXED_DESTINATION`, for conversions and cross-border alike | §1, §3.1 |
| O4 | The compliance review sits on the beneficiary, before pricing, decided by kyc; screening unavailable means unpayable, with nothing held and nothing priced | §3.7, §3.8 |
| O5 | Provider callbacks are hints; an outbound money flow adopts its outcome only from an authenticated inquiry over the outbound credential | §3.4, §3.6 |
| O8 | If scope must shrink: cut `P9-TSK-026` (the second providers) first, then `P9-TSK-025` (the trade reversal), each recorded with Phase 15 as owner. Never cut: unwinds, returns, cancellation, the proofs | §3.3, §3.12 |
| O9 | `OUR` only: the beneficiary receives the quoted destination amount; `SHA`/`BEN` deferred | §4 |
| O10 | No conversion fee in Phase 9 (margin only); fees are `crossborder`'s, on the payment offer | §2, §3.1 |
