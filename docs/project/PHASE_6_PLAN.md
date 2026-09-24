# Phase 6 — Checkout and Merchant Platform

Written by the Phase 5 → 6 transition (2026-09-21). Decisions in
[ADR-0050](../adr/ADR-0050-fee-model-gross-capture-net-payable.md) (the fee model —
unresolved question 8 closed), [ADR-0051](../adr/ADR-0051-merchant-payout-accounting.md)
(payout accounting), [ADR-0052](../adr/ADR-0052-merchant-api-identity.md) (merchant API
identity and tenancy), and [ADR-0053](../adr/ADR-0053-checkout-session-and-order.md)
(checkout session and order — question 7 confirmed closed). The domain-facing statement of
the lifecycles is [`CHECKOUT_MERCHANT_LIFECYCLES.md`](../domain/CHECKOUT_MERCHANT_LIFECYCLES.md).

## 1. Objective

**Introduce the second commercial party**: the merchant, and with it the first place one
payment splits into two economic positions — what the merchant is owed and what the
platform earned. Concretely: an onboarded merchant (KYB-approved through Phase 2's
machinery) creates checkout sessions through an authenticated API; a customer completes a
session through Phase 5's payment infrastructure; the capture credits the merchant's
payable **gross with the fee assessed in the same entry** (ADR-0050); the merchant reads
its transactions and payable through tenant-isolated APIs; and a payout pays the net
payable out through the hold-then-dispatch discipline (ADR-0051).

Phase 5 proved money can enter and leave against an unreliable third party. Phase 6 holds
that machinery **fixed** — no new provider mechanics for payments — and changes the
commercial variable: who the money is for, who pays for the service, and who may see what.

## 2. Why this phase is shaped by decisions already taken

- **The payment machinery is consumed, not reopened.** A checkout's payment is a
  `PaymentIntent` created by the checkout's orchestration in `app`, which composes `checkout`,
  `merchant` and `payments` *("through a port" until the Phase 6 review, `P6-DOC-001`: no port
  exists, the composition root orchestrates)*; the capture's atomicity, ambiguity handling,
  webhooks and refunds are Phase 5's, verbatim. What changes is only the capture's
  **posting lines**, supplied through the composition seam ADR-0050 §6 names.
- **The payout is Phase 5's disciplines pointed outward**: dispatch-before-call
  (ADR-0046), hold-then-post (ADR-0048 §4), the two-transaction keyed command
  (`P5-TSK-016`) — on the payable instead of the wallet.
- **KYB is Phase 2's**: a merchant references a party whose KYB case is approved; no
  verification machinery is rebuilt.
- **The tenancy discipline is ADR-0031 promoted**: `merchant_id = ?` in the statement, the
  one-404 oracle, the beneficiary/instrument protocols on new subjects.
- **Four-eyes gets its second real subject**: `P3-TSK-021` built the primitive on manual
  adjustments; the payout destination change is the high-consequence action that applies it
  again, in the same shape. *(Corrected at the Phase 6 review, `P6-DOC-001`: this read "first
  real subject", with `INV-AUD-04` unimplemented since initiation.)*

## 3. Bounded contexts and modules

**Merchant** (`merchant`, context 12) and **Checkout** (`checkout`, context 11) — two new
modules per the registers `MODULE_ARCHITECTURE.md` §3 has carried since Phase 0, M2's
provisional status now confirmed (ADR-0053 §2). Consumed: Payments (intent creation and
status, composed in `app`), Ledger (payable accounts, postings, holds — commanded, never
written), KYC/KYB (the approval gate), Identity (operator permissions; the new merchant
key credential), Party (the merchant's legal party). Seams only: Settlement (payout
clearing evidence — Phase 8), BNPL (order references — Phase 12).

**Build-graph edges**: `merchant → ledger` declared (payout postings and payable reads are
commanded); `checkout → payments` **refused** — payment execution is orchestrated in `app`
(`CheckoutService`, `CheckoutSessions`), so the purchase-experience module cannot reach
provider machinery *(planned as a port `app` implements; built as the composition root's own
orchestration, which ADR-0053's consequences now record)*; `checkout → merchant` and `merchant → checkout`
**refused** — references by identifier, composed in `app`, so neither owns the other's
lifecycle.

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `Merchant` | `merchant` | onboard (operator), suspend/reinstate/close (operator) | Onboard keyed; state moves converge by machine |
| `MerchantApiKey` | `merchant` | issue, revoke (operator) | Issue keyed; revoke converges by row count |
| `FeeSchedule` | `merchant` | create version, assign to merchant (operator) | Versions immutable; assignment conditional |
| `PayoutDestination` | `merchant` | propose, approve (four-eyes), reject, withdraw; effected by the platform after the cooling-off (`P6-TSK-011`, ADR-0056) | Propose keyed; approve conditional on proposer ≠ approver, in the statement; the rest converge by machine |
| `MerchantPayout` | `merchant` | initiate (the merchant's key, or an operator over `MERCHANT_PAYOUT` — two routes, ADR-0057 §6); outcomes applied by the platform (the synchronous answer, a takeover's re-send, the resolution sweep) | Initiate keyed per merchant (ADR-0057 §5); outcomes conditional on the row their resolver locked (ADR-0051, ADR-0057 §12) |
| `CheckoutSession` | `checkout` | create (merchant), abandon (merchant, reasoned), confirm (customer: their own session plus the session token, in the body), expire (sweeper), complete (the capture's outcome, in its transaction) | Create keyed per merchant (`checkout.session:<merchantId>`); all transitions conditional |
| `Order` | `checkout` | created by completion only | Born in the completion's transaction; one per session (a plain `UNIQUE` on `session_ref`, checkout `V002`) |

*(Close and abandon added, the confirmation's credential stated, and the order's constraint
corrected — it read "partial index" — at the Phase 6 review, `P6-DOC-001`.)*

## 5. The lifecycles (ADR-0051, ADR-0053)

Stated in full in `CHECKOUT_MERCHANT_LIFECYCLES.md`: the session's six states (`OPEN`,
`PAYMENT_PENDING`, `COMPLETED`, `COMPLETED_LATE`, `EXPIRED`, `ABANDONED`), the payout's
four (`DISPATCHED`, `COMPLETED`, `FAILED`, `UNKNOWN` — the planned `REQUESTED` refused by
ADR-0057 §1, since the dispatch commits `DISPATCHED` atomically), the merchant's three
(`ACTIVE`, `SUSPENDED`, `CLOSED`), the destination's proposal flow. Every machine gets the
established three-layer enforcement: exhaustive aggregate sweep, generated schema `CHECK` +
transition trigger binding every writer, append-only history.

## 6. Financial invariants Phase 6 must preserve

The in-scope set is **whatever the catalogue marks `Phase: 6`, token-parsed** — the
standing rule. At planning time that is **eight**:

- `INV-MER-01…06` — the new group the transition catalogued (tenant isolation; the
  ledger-derived payable; version-pinned deterministic fees; the residual-free split; the
  payout bound; landed money never orphaned). The platform stands at **93 invariants**.
- `INV-HIST-04` (fees element) — the fee assessment records the schedule version that
  produced it; `INV-MER-03` is its sharpened form.
- `INV-AUD-04` — four-eyes on high-consequence actions. **Its second implemented subject**:
  the payout destination change, after `P3-TSK-021`'s manual adjustments *(the catalogue's
  phase list omitted 6 while its statement named the subject — corrected by this transition;
  "live for the first time … its first implemented subject" corrected at the Phase 6 review,
  `P6-DOC-001`)*.
- `INV-MER-07` — **added by `P6-TSK-015`** (ADR-0054), taking the in-scope set to nine and
  the platform to 94: a refund is funded by its net, and the only credit it extends is the fee
  the platform keeps.

The standing families (`INV-LIFE-*`, `INV-EVT-*`, `INV-AUD-01…03`, `INV-MON-*`,
`INV-LED-*`, `INV-BAL-*`, `INV-IDEM-*`, `INV-CON-*`, `INV-HIST-*`) apply as always; the
three new machines are `INV-LIFE-01`'s next subjects.

## 7. Multi-instance architecture

Ten instances execute everything concurrently; every contended decision names its
PostgreSQL arbiter:

| Contention | Arbiter |
|---|---|
| Duplicate merchant onboarding / session creation / payout initiation | The idempotency claim's unique constraint (`INV-IDEM-01`) |
| Two instances completing one session (webhook vs late sync vs retry) | The session's conditional transition; one row count wins, the losers converge |
| Session expiry racing payment completion | Conditional transitions on the session row; the `COMPLETED_LATE` edge makes the late winner modelled rather than lost (ADR-0053 §5, `INV-MER-06`) |
| Concurrent expiry sweepers | None needed — the `PaymentSweeperSchedule` precedent: reads idempotent, writes conditional, no lease, no leader |
| Two payouts racing one payable | The payable account's lock + holds: available derived in-lock, in-flight amounts held, the bound cumulative (`INV-MER-05`) |
| Refunds racing one payable, and a capture landing among them (`P6-TSK-015`) | The same lock + holds: each refund reserves its net in-lock, so exactly the affordable set is admitted; a capture's in-flight posting blocks the placement until it commits, so a refund is funded only by money that has landed (`INV-MER-07`) |
| Payout outcome races (sync vs takeover re-send vs query sweep; there is no payout webhook — deferred, ADR-0057 §10) *(read "sync vs query vs webhook" until `P6-DOC-001`)* | Conditional transitions through the shared outcomes shape; the posting claim `merchant-payout:<payoutId>` makes a second entry structurally impossible |
| Fee schedule version change racing a capture | The version is chosen when the session opens (the price the offer was made at, ADR-0058 §2), copied onto the payment's pin at confirmation and travels with the intent; the capture prices with the pinned version whatever changed since (`INV-MER-03`) *(read "pinned at intent creation" until `P6-DOC-001`)* |
| Concurrent destination proposal/approval | Conditional transitions; approver ≠ proposer enforced in the statement (`INV-AUD-04`) |
| Duplicate order creation | One order per session — a plain `UNIQUE` on `session_ref` (checkout `V002`) *(read "partial unique index" until `P6-DOC-001`)*; the completion's transaction births it or converges |
| Cross-tenant races | None exist to arbitrate: tenancy is in every statement (`INV-MER-01`), so contention is per-merchant by construction |

Nothing lives in process memory; all three sweeps (session expiry, payout resolution, payout
destination effectuation) follow the registered leaderless pattern. *(This read "both sweeps"
until the Phase 6 review, `P6-DOC-001`; `P6-TSK-011` added the third.)*

## 8. Data architecture

Schema `merchant` (owner `finapp_migrator`, default-deny floor first):

- `merchant` — party ref (KYB-approved, checked at onboarding against the Phase 2
  decision), legal/display names, status + generated `CHECK`s, timestamps by injected
  clock. History table, append-only. **No balance column of any kind** (`INV-MER-02`).
- `merchant_api_key` — merchant FK, public key id prefix, hash + derivation parameters
  (`INV-IDN-01/-02` verbatim), status, issued/revoked timestamps. Never the secret.
- `fee_schedule` / `fee_schedule_version` — immutable versions (rate, fixed part, rounding
  mode, refund-fee policy), effective-from, frozen by trigger; assignment table
  merchant ↔ schedule.
- `payment_fee_pin` — the pin of a fee schedule version onto a payment intent: the intent ref
  (by value, the primary key — one pin per payment), merchant FK, version FK, the agreed gross
  (`MoneyColumns`), pinned-at/by; immutable (no `UPDATE`/`DELETE` grant, `V004`'s trigger
  function reused). Through checkout the version is the session's, copied at confirmation;
  the capture prices under it (`INV-MER-03`). *(Built by `P6-TSK-005` as `merchant` `V005`,
  ADR-0050 §5; missing from this list until the Phase 6 review, `P6-DOC-001`.)*
- `payout_destination` — merchant FK, the provider's opaque destination reference and a
  four-character display suffix (never raw account details — refused by `CHECK` if shaped like
  one), proposal state, proposer, approver (`CHECK approved_by <> proposed_by`), the pinned
  cooling-off deadline (`CHECK effective_at >= cooling_off_until`), one-open and one-effective
  partial indexes. History table (`payout_destination_event`). *(Built by `P6-TSK-011` as
  `merchant` `V006`, ADR-0056.)*
- `merchant_payout` — merchant FK, amount (`MoneyColumns`), status (4 values, generated) and
  its failure reason, our minted idempotency reference (`INV-PAY-04`'s discipline), the
  provider's reference, hold reference, destination version ref (a composite FK to the
  merchant's own destination; an insert trigger admits only an `EFFECTIVE` one), the dispatch
  key (unique per merchant), the requester and an operator's reason, and the send permit
  (`last_dispatched_at`, ADR-0057 §4). History table; encrypted, append-only evidence table.
  *(Built by `P6-TSK-012` as `merchant` `V007`, ADR-0057; `PAYOUT_CLEARING` seeded by `ledger`
  `V012`.)*

Schema `checkout`:

- `checkout_session` — merchant ref (by id — no FK across schemas, the established
  boundary), amount, currency, line summary (display data, classified), session token hash
  (single-purpose, unguessable — stored hashed, the credential discipline), payment intent
  ref (nullable until confirmation, then set once; one session per intent by a partial unique
  index — checkout `V003`, added by the Phase 6 review, `P6-DOC-001`), pinned fee schedule
  version ref, status (6 values, generated), expires-at, timestamps. History table.
- `checkout_order` — session ref (`UNIQUE` — one order per session), merchant ref, amount,
  captured entry ref, created-at. Append-only: an order is a fact.

Grants per table in the migration that creates it; frozen columns by every-writer trigger
per the established ceremony.

## 9. API architecture

| Operation | Path | Auth | Idempotency |
|---|---|---|---|
| Onboard merchant | `POST /v1/operator/merchants` | `@RequiresPermission(MERCHANT_ONBOARD)` | `@RequiresIdempotencyKey` |
| Read / suspend / reinstate / close merchant | `GET /v1/operator/merchants/{id}`; `POST …/{id}/suspension`, `/reinstatement`, `/closure` | `MERCHANT_ADMINISTER`; the three standing changes reasoned | the standing changes converge by machine |
| Issue / list / revoke API key | `POST`/`GET /v1/operator/merchants/{id}/api-keys`, `DELETE …/api-keys/{keyId}` | `MERCHANT_ADMINISTER` | issue keyed / revoke converges |
| Fee schedules, versions, assignment | `POST`/`GET /v1/operator/fee-schedules`, `GET …/{id}`, `POST …/{id}/versions`; `PUT`/`GET /v1/operator/merchants/{merchantId}/fee-schedule` | `FEE_ADMINISTER` | versions immutable; assignment conditional |
| Create session | `POST /v1/checkout/sessions` | **merchant API key** (ADR-0052) | `@RequiresIdempotencyKey` (the claim scoped per merchant, `checkout.session:<merchantId>`) |
| Read session | `GET /v1/checkout/sessions/{id}` | merchant key, own only — there is no read by session token | — |
| Abandon session | `POST /v1/checkout/sessions/{id}/abandonment` | merchant key, own only, reason required | machine convergence |
| Confirm session | `POST /v1/checkout/sessions/confirmation` | the customer's own session + the session token **in the body** (a token in a path ends up in logs) | machine convergence |
| Merchant self | `GET /v1/merchant/me` | merchant key | — |
| Merchant transactions | `GET /v1/merchant/transactions` | merchant key, tenant-scoped in the statement | — |
| Merchant payable | `GET /v1/merchant/payable` | merchant key | — |
| Propose / list destination | `POST`/`GET /v1/operator/merchants/{merchantId}/payout-destinations` | operator session, `MERCHANT_ADMINISTER`; step-up when a factor is enrolled | propose keyed |
| Approve / reject / withdraw | `POST …/payout-destinations/{destinationId}/approval`, `/rejection`, `/withdrawal` | `PAYOUT_DESTINATION_APPROVE` (approve, reject — four-eyes, `INV-AUD-04`; step-up on approve when a factor is enrolled) or `MERCHANT_ADMINISTER` (withdraw) | machine convergence |
| Initiate payout | `POST /v1/merchant/payouts` | merchant key | `@RequiresIdempotencyKey` (the claim scoped per merchant) |
| Initiate payout for a merchant | `POST /v1/operator/merchants/{merchantId}/payouts` | operator session, `MERCHANT_PAYOUT`, reason required — the plan's "(or operator)", a route of its own because the populations are disjoint (ADR-0057 §6) | `@RequiresIdempotencyKey` |
| Payout status | `GET /v1/merchant/payouts/{id}` | merchant key, own only | — |

No route expires a session — the leaderless sweeper does — and none completes one: completion
runs inside the capture's transaction. The authoritative route list is the OpenAPI baseline,
[`docs/api/openapi.json`](../api/openapi.json) — 29 operations under these paths at the phase
review; this table names their families. *(Corrected at the Phase 6 review, `P6-DOC-001`: the
confirmation's path carried the token, a read by session token was listed and never built, the
key revoke named no key, "operator permission" named none, fee schedules were "CRUD" with no
update or delete, and the merchant's standing, key-list, abandonment and self routes were
missing.)*

Error vocabulary: merchant/checkout-domain codes only; the one-404 discipline for
cross-tenant and unknown alike (`INV-MER-01`); the asynchronous-outcome shape on payouts
(honestly `DISPATCHED`/`UNKNOWN` when they are).

## 10. Event architecture

Producer `merchant`: `merchant.MerchantOnboarded`, `merchant.FeeAssessed`,
`merchant.FeeReturned` *(the refund's fee return, `P6-TSK-014`)*,
`merchant.MerchantPayoutInitiated`, `merchant.MerchantPayoutCompleted`,
`merchant.MerchantPayoutFailed` *(the completion/failure pair added to the delivery plan's
list by this transition with provenance — terminal facts publish, ADR-0044's doctrine, the
`RefundFailed` precedent)*. Producer `checkout`: ~~`checkout.CheckoutSessionCreated`~~
*(struck by the Phase 6 review, `P6-DOC-001` — declared here, never built, owned by no task;
the session's creation is audited as `checkout.CheckoutSessionCreated`, an audit action, and
the event arrives with a consumer that needs it)*, `checkout.CheckoutSessionExpired`,
`checkout.OrderPaid`. All through the outbox in the transaction that commits the fact;
`MerchantPayoutInitiated` is legitimate pre-outcome for the ADR-0046 reason (the dispatch
commits durably before its outcome exists); `PayoutUnknown` publishes nothing (the
standing-hold is the record — the refund precedent). Payloads: identifiers and enumerated
names, never another tenant's identifiers; only an event whose fact is an amount carries
one — `FeeAssessed` and `FeeReturned`, minor units as base-10 integer strings beside the
currency (`EVENT_ARCHITECTURE.md`'s wire format). *(This read "never amounts" until the
Phase 6 review, `P6-DOC-001`: both fee events have carried amounts since `P6-TSK-005` and
`P6-TSK-014`.)*

## 11. Security and audit

- **Merchant identity**: API keys under the full credential regime (ADR-0052);
  `ActorType.MERCHANT`; the key id in every audit record a merchant-API command writes (a
  record another module writes in the same transaction reaches it by correlation, ADR-0052 §2).
- **Tenancy**: `INV-MER-01` — in the statement, negatively tested per endpoint.
- **Four-eyes + step-up + cooling-off** on payout destination change (`INV-AUD-04` live;
  the delivery plan's own requirement): proposer and approver distinct **operators** — fixed
  by `P6-TSK-011`'s design (ADR-0056 §1: a merchant has only a machine key this phase, so it
  reaches no destination route) — step-up on both sides when a factor is enrolled, effect only
  after the cooling-off elapses (and withdrawable during it), every step audited with reason.
- **Privileged actions**: `MERCHANT_ONBOARD` (onboard), `MERCHANT_ADMINISTER` (read the
  merchant, suspend/reinstate/close, issue/list/revoke keys, propose/list/withdraw
  destinations), `FEE_ADMINISTER` (schedules, versions, assignment) and
  `PAYOUT_DESTINATION_APPROVE` (approve/reject a destination; planned as `PAYOUT_APPROVE`,
  named for what it approves by `P6-TSK-011`), the four `MERCHANT_ADMINISTRATOR` holds; and
  `MERCHANT_PAYOUT` (the operator's payout; `P6-TSK-012`, held by `LEDGER_OPERATOR` — money
  leaving the platform) — new permissions with negative tests; the money-operating population
  (`LEDGER_OPERATOR`) stays distinct from merchant administration.
- **Auditable actions**: onboarding, key issue/revoke, schedule changes, destination
  proposal/approval, payout initiation (with reason where operator-driven), session
  administrative acts. The platform's acts (payout outcomes, expiry) through enumerated
  `enterSystem()` sites.
- **Customer surface**: session tokens single-purpose and unguessable, hashed at rest;
  possession grants that session only.
- **Data**: destination details tokenised/masked at the ceiling; line summaries classified;
  no cross-tenant identifier in any event payload or error.

## 12. Reconciliation

The phase builds Phase 8's first true three-way target: **payments ↔ merchant payable ↔
payouts**. Every fee assessment is a journal line pair inside the capture's entry (walkable
from the attempt id); the payable position is continuously captured − fees − refunded + fees
returned − payouts (`INV-MER-02` makes this arithmetic, not aspiration; a `RETAINED` refund may
leave it below zero by the fee kept, ADR-0054) *(the refund terms added at the Phase 6 review,
`P6-DOC-001`)*; every payout carries our reference, the provider's reference and the
destination version; `PAYOUT_CLEARING` is continuously "instructed but unsettled" per currency
(`INV-SET-01` outbound). Orders reference their captured entries, so merchant statements
reconcile to the journal identifier-to-identifier.

## 13. Testing strategy

The tiers as established; per task: hermetic machine sweeps, schema tests against raw SQL,
database races counted in tables, tenant-isolation negatives per endpoint, fee property
tests across currencies and rounding modes, payout races against the payable bound, and
the mutation discipline. The phase's composition demonstrations: `P6-TST-001` (the tenancy
and fee-conservation battery — the high-volume batch with zero residual) and `P6-TST-002`
(the merchant conservation storm: concurrent checkouts, captures-with-fees, refunds and
payouts against the trial-balance and projection sweeps, the payable reconciling to
captured − fees − refunded + fees returned − payouts — §12's arithmetic, the fee-return term
added by `P6-DOC-001`).

## 14. Failure scenarios

1. Payment completes after session expiry → `EXPIRED → COMPLETED_LATE`, merchant credited,
   order created, counted (`INV-MER-06`).
2. Session expires between confirm and dispatch → expiry's conditional loses or wins
   cleanly; the gate governs new work — a confirmation after the deadline is refused — not
   work already admitted. **Accepted sub-case, the crash window**: an intent created before
   the deadline and stranded undispatched by a crash can still be dispatched afterwards (a
   retried confirmation, or the payment's own confirmation route), and if it captures it
   lands as `COMPLETED_LATE` — scenario 1, landed money wins (`INV-MER-06`). *(This read "no
   dispatch after expiry wins" until the Phase 6 review, `P6-DOC-001`, found the window.)*
3. Duplicate session creation / duplicate payout initiation → keyed, one effect.
4. Payout provider times out → `UNKNOWN`, hold standing, query resolves; the refund's
   ambiguity doctrine verbatim.
5. Payout provider succeeds, response lost → dispatch-before-call: the retry converges on
   the committed dispatch, one wire operation (`INV-PAY-04`).
6. Payout against insufficient payable → committed domain refusal, nothing held.
7. Two payouts racing one payable → locks + holds; exactly the affordable set dispatches.
8. Fee schedule changed mid-flight → the version the session opened under prices, pinned
   onto the payment at confirmation; the new version prices only sessions opened after it
   takes effect (`INV-MER-03`, ADR-0058 §2) *(read "only later intents" until the Phase 6
   review, `P6-DOC-001`)*.
9. Fee rounding at volume → the subtraction split leaves zero residual by construction
   (`INV-MER-04`); asserted in bulk.
10. Capture succeeds but fee lines wrong → impossible to commit unbalanced
    (`INV-LED-01`); wrong-account shape caught by the `DIRECTION:PURPOSE` probes (the
    `P5-TST-002` lesson applied from day one).
11. Merchant suspended mid-session → the confirmation refuses (409 `checkout.NotTrading`); a
    payment admitted before the suspension still lands (suspension gates new dispatches, not
    arrived outcomes). *(The confirmation never checked until the Phase 6 review,
    `P6-DOC-001`, found the gap and added the refusal.)*
12. KYB not approved → onboarding refuses; no merchant, no accounts, nothing partial.
13. A merchant refund the payable cannot fund → committed refusal, nothing held; under
    `RETAINED` the fee share may leave the payable negative, and never further
    (`INV-MER-07`, ADR-0054, added by `P6-TSK-015`).
14. Destination approved by its proposer → refused in the statement (`INV-AUD-04`).
15. Payout dispatched while destination change is cooling off → the effective destination
    at dispatch is used; the pending proposal changes nothing until effected.
16. Crash between payout outcome commit and event publication → the outbox's standing
    guarantee.

*(Two scenarios were numbered 13 until the Phase 6 review, `P6-DOC-001`: `P6-TSK-015`
inserted its refund scenario without renumbering, so the three after it each shift by one.)*

## 15. Observability

| Meter | Kind | Notes |
|---|---|---|
| `finapp.checkout.session` | counter by `outcome` (completed / completed_late / expired / abandoned) | acting transitions only |
| `finapp.checkout.conversion.age` | timer by `outcome` (completed / completed_late) | creation → completion; split because in-window measures the customer and late measures the provider (`P6-TSK-013`) |
| `finapp.merchant.fee.assessed` | counter | assessments, not amounts (`INV-AUD-02`) |
| `finapp.merchant.payout` | counter by `outcome` (completed / failed / unknown) | acting judgements only |
| `finapp.merchant.payout.unknown.active` | gauge | the stuck-payout alert: every `UNKNOWN` payout, and every `DISPATCHED` one past the sweep's own bound (`P6-TSK-013`); NaN never zero; fleet-wide, `max()` |
| `finapp.merchant.payout.unknown.age` | gauge (max seconds) | the oldest of those, aged the sweep's way |
| `finapp.merchant.destination.pending` | gauge | proposals awaiting approval/cooling-off — built by `P6-TSK-011` with the flow it measures |

All eager from a plain context; a dashboard row; the derived guard takes this table over at
the flip. No new tag keys expected (`outcome` exists); if one is needed it walks the
`ALLOWED_TAG_KEYS` designed path.

*(Until `P6-TSK-013`'s design the two stuck-payout gauges shared one row, written
"`….unknown.active` / `.age`". The guard reads a row's first backticked name, so the age gauge
would never have been held; `PHASE_5_PLAN.md` gives each gauge its own row, and so does this
table now.)*

## 16. Milestones

| # | Milestone | Items | Acceptance |
|---|---|---|---|
| M6.1 | Foundations | `P6-TSK-001…003` | Both modules and schemas with their floors; the merchant key authenticates and scopes; an onboarded merchant has its payable account |
| M6.2 | Fee economics | `P6-TSK-004…005` | A versioned schedule prices a merchant-bound capture gross-with-fee in one entry; the split conserves to the minor unit |
| M6.3 | Checkout | `P6-TSK-006…008`, `P6-TSK-014` | A session is created, confirmed, paid end to end over HTTP; expiry is modelled and swept; the late-completion race lands `COMPLETED_LATE` |
| M6.4 | The merchant surface | `P6-TSK-009…010`, `P6-TSK-015` | Transactions and payable readable, tenant-isolated, reconciling to the ledger |
| M6.5 | Payouts | `P6-TSK-011…012` | A destination survives four-eyes and cooling-off; a payout pays the net payable under the bound with honest outcomes |
| M6.6 | Observability and demonstration | `P6-TSK-013`, `P6-TST-001`, `P6-TST-002` | Meters from a fresh instance; every `Phase: 6` catalogue row landed; conservation holds under the merchant storm |
| M6.7 | The gate | `P6-DOC-001` | The exit review's own verdict flips the phase |

*(`P6-TSK-014` and `P6-TSK-015` joined M6.3 and M6.4 at `P6-TSK-005`'s and `P6-TSK-010`'s
completion gates — the backlog's milestone line records why; this table caught up at the
Phase 6 review, `P6-DOC-001`.)*

## 17. What Phase 6 must NOT implement

Settlement file ingestion and matching (Phase 8); disputes and chargebacks (Phase 7); a
second customer-payment rail or rail routing (Phase 7); multi-currency merchant payouts
(Phase 9); BNPL and merchant financing (Phase 12); merchant self-service users and roles (a
product surface owed a later decision); payout scheduling/netting windows (product);
per-merchant pricing negotiation workflows (product); inventory (never — the platform is
not a shop).

## 18. Risks

- **Fee rounding creating cent-level imbalance** — closed structurally by ADR-0050 §4's
  subtraction split; demonstrated in bulk by `P6-TST-001`.
- **A stored merchant balance sneaking in** — `INV-MER-02`'s schema sweep; review of every
  `merchant` migration.
- **Cross-tenant leakage** — `INV-MER-01` in the statement; negatives per endpoint; no
  merchant identifier in shared error or event vocabulary.
- **The checkout module becoming a god-orchestrator** — the refused build edges; payment
  execution orchestrated in `app`, never in `checkout`; M2's merge trigger stands as the watchdog in the other
  direction.
- **The payout rail growing into Phase 7's abstraction** — ADR-0051 §4: one operation, its
  own narrow port, deliberately not a rail model.
- **Expiry treated as a query filter instead of a state** — ADR-0053 §4; the sweeper
  pattern is established and the machine's three-layer enforcement makes the shortcut
  unrepresentable.
