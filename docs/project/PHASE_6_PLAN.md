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
  `PaymentIntent` created through a port; the capture's atomicity, ambiguity handling,
  webhooks and refunds are Phase 5's, verbatim. What changes is only the capture's
  **posting lines**, supplied through the composition seam ADR-0050 §6 names.
- **The payout is Phase 5's disciplines pointed outward**: dispatch-before-call
  (ADR-0046), hold-then-post (ADR-0048 §4), the two-transaction keyed command
  (`P5-TSK-016`) — on the payable instead of the wallet.
- **KYB is Phase 2's**: a merchant references a party whose KYB case is approved; no
  verification machinery is rebuilt.
- **The tenancy discipline is ADR-0031 promoted**: `merchant_id = ?` in the statement, the
  one-404 oracle, the beneficiary/instrument protocols on new subjects.
- **Four-eyes gets its first real subject**: `INV-AUD-04` has stood in the catalogue since
  initiation with no implementation; the payout destination change is the high-consequence
  action that finally builds the primitive.

## 3. Bounded contexts and modules

**Merchant** (`merchant`, context 12) and **Checkout** (`checkout`, context 11) — two new
modules per the registers `MODULE_ARCHITECTURE.md` §3 has carried since Phase 0, M2's
provisional status now confirmed (ADR-0053 §2). Consumed: Payments (intent creation and
status through a port), Ledger (payable accounts, postings, holds — commanded, never
written), KYC/KYB (the approval gate), Identity (operator permissions; the new merchant
key credential), Party (the merchant's legal party). Seams only: Settlement (payout
clearing evidence — Phase 8), BNPL (order references — Phase 12).

**Build-graph edges**: `merchant → ledger` declared (payout postings and payable reads are
commanded); `checkout → payments` **refused** — payment execution resolves through a port
`app` implements (the `InstrumentResolution` shape), so the purchase-experience module
cannot reach provider machinery; `checkout → merchant` and `merchant → checkout`
**refused** — references by identifier, resolution through ports, so neither owns the
other's lifecycle.

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `Merchant` | `merchant` | onboard (operator), suspend/reinstate (operator) | Onboard keyed; state moves converge by machine |
| `MerchantApiKey` | `merchant` | issue, revoke (operator) | Issue keyed; revoke converges by row count |
| `FeeSchedule` | `merchant` | create version, assign to merchant (operator) | Versions immutable; assignment conditional |
| `PayoutDestination` | `merchant` | propose, approve (four-eyes), effect after cooling-off | Propose keyed; approve conditional on proposer ≠ approver |
| `MerchantPayout` | `merchant` | initiate (merchant or operator), apply-outcome | Initiate keyed; outcomes conditional (ADR-0051) |
| `CheckoutSession` | `checkout` | create (merchant), confirm (customer, by session token), expire (sweeper), complete (outcome) | Create keyed per merchant; all transitions conditional |
| `Order` | `checkout` | created by completion only | Born in the completion's transaction; one per session (partial index) |

## 5. The lifecycles (ADR-0051, ADR-0053)

Stated in full in `CHECKOUT_MERCHANT_LIFECYCLES.md`: the session's six states (`OPEN`,
`PAYMENT_PENDING`, `COMPLETED`, `COMPLETED_LATE`, `EXPIRED`, `ABANDONED`), the payout's
five (`REQUESTED`, `DISPATCHED`, `COMPLETED`, `FAILED`, `UNKNOWN`), the merchant's three
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
- `INV-AUD-04` — four-eyes on high-consequence actions. **Live for the first time**: the
  payout destination change is its first implemented subject *(the catalogue's phase list
  omitted 6 while its statement named the subject — corrected by this transition)*.
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
| Payout outcome races (sync vs query vs webhook) | Conditional transitions through the shared outcomes shape; the posting claim `merchant-payout:<payoutId>` makes a second entry structurally impossible |
| Fee schedule version change racing a capture | The version is pinned at intent creation and travels with the intent; the capture prices with the pinned version whatever changed since (`INV-MER-03`) |
| Concurrent destination proposal/approval | Conditional transitions; approver ≠ proposer enforced in the statement (`INV-AUD-04`) |
| Duplicate order creation | One order per session — partial unique index; the completion's transaction births it or converges |
| Cross-tenant races | None exist to arbitrate: tenancy is in every statement (`INV-MER-01`), so contention is per-merchant by construction |

Nothing lives in process memory; both sweeps (session expiry, payout resolution) follow
the registered leaderless pattern.

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
- `payout_destination` — merchant FK, masked/tokenised destination reference (never raw
  account details in clear — classification at the ceiling), proposal state, proposer,
  approver (`CHECK proposer <> approver`), cooling-off-until, one-effective partial index.
- `merchant_payout` — merchant FK, amount (`MoneyColumns`), status (5 values, generated),
  our minted idempotency reference (`INV-PAY-04`'s discipline), provider references,
  hold reference, destination version ref. History table.

Schema `checkout`:

- `checkout_session` — merchant ref (by id — no FK across schemas, the established
  boundary), amount, currency, line summary (display data, classified), session token hash
  (single-purpose, unguessable — stored hashed, the credential discipline), payment intent
  ref (nullable until confirmation), pinned fee schedule version ref, status (6 values,
  generated), expires-at, timestamps. History table.
- `checkout_order` — session ref (`UNIQUE` — one order per session), merchant ref, amount,
  captured entry ref, created-at. Append-only: an order is a fact.

Grants per table in the migration that creates it; frozen columns by every-writer trigger
per the established ceremony.

## 9. API architecture

| Operation | Path | Auth | Idempotency |
|---|---|---|---|
| Onboard merchant | `POST /v1/operator/merchants` | `@RequiresPermission(MERCHANT_ONBOARD)` | `@RequiresIdempotencyKey` |
| Issue / revoke API key | `POST/DELETE /v1/operator/merchants/{id}/api-keys` | operator permission | keyed / converge |
| Fee schedule CRUD | `/v1/operator/fee-schedules…` | operator permission | versions immutable |
| Create session | `POST /v1/checkout/sessions` | **merchant API key** (ADR-0052) | `@RequiresIdempotencyKey` (scoped per merchant) |
| Read session | `GET /v1/checkout/sessions/{id}` | merchant key (own only) or session token | — |
| Confirm session | `POST /v1/checkout/sessions/{token}/confirmation` | session token + customer session | machine convergence |
| Merchant transactions | `GET /v1/merchant/transactions` | merchant key, tenant-scoped in the statement | — |
| Merchant payable | `GET /v1/merchant/payable` | merchant key | — |
| Propose / approve destination | `POST /v1/merchant/payout-destination…` | step-up + four-eyes (`INV-AUD-04`) | propose keyed |
| Initiate payout | `POST /v1/merchant/payouts` | merchant key (or operator) | `@RequiresIdempotencyKey` |
| Payout status | `GET /v1/merchant/payouts/{id}` | merchant key, own only | — |

Error vocabulary: merchant/checkout-domain codes only; the one-404 discipline for
cross-tenant and unknown alike (`INV-MER-01`); the asynchronous-outcome shape on payouts
(honestly `DISPATCHED`/`UNKNOWN` when they are).

## 10. Event architecture

Producer `merchant`: `merchant.MerchantOnboarded`, `merchant.FeeAssessed`,
`merchant.MerchantPayoutInitiated`, `merchant.MerchantPayoutCompleted`,
`merchant.MerchantPayoutFailed` *(the completion/failure pair added to the delivery plan's
list by this transition with provenance — terminal facts publish, ADR-0044's doctrine, the
`RefundFailed` precedent)*. Producer `checkout`: `checkout.CheckoutSessionCreated`,
`checkout.CheckoutSessionExpired`, `checkout.OrderPaid`. All through the outbox in the
transaction that commits the fact; `MerchantPayoutInitiated` is legitimate pre-outcome for
the ADR-0046 reason (the dispatch commits durably before its outcome exists);
`PayoutUnknown` publishes nothing (the standing-hold is the record — the refund
precedent). Payloads: identifiers and enumerated names, never amounts, never another
tenant's identifiers.

## 11. Security and audit

- **Merchant identity**: API keys under the full credential regime (ADR-0052);
  `ActorType.MERCHANT`; key id in every merchant-API audit record.
- **Tenancy**: `INV-MER-01` — in the statement, negatively tested per endpoint.
- **Four-eyes + step-up + cooling-off** on payout destination change (`INV-AUD-04` live;
  the delivery plan's own requirement): proposer and approver distinct operators (or
  merchant-then-operator — fixed by the task design), step-up on both sides when a factor
  is enrolled, effect only after the cooling-off elapses, every step audited with reason.
- **Privileged actions**: `MERCHANT_ONBOARD`, `MERCHANT_ADMINISTER`, `FEE_ADMINISTER`,
  `PAYOUT_APPROVE` — new permissions with negative tests; the money-operating population
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
from the attempt id); the payable position is continuously captured − fees − payouts
(`INV-MER-02` makes this arithmetic, not aspiration); every payout carries our reference,
the provider's reference and the destination version; `PAYOUT_CLEARING` is continuously
"instructed but unsettled" per currency (`INV-SET-01` outbound). Orders reference their
captured entries, so merchant statements reconcile to the journal identifier-to-identifier.

## 13. Testing strategy

The tiers as established; per task: hermetic machine sweeps, schema tests against raw SQL,
database races counted in tables, tenant-isolation negatives per endpoint, fee property
tests across currencies and rounding modes, payout races against the payable bound, and
the mutation discipline. The phase's composition demonstrations: `P6-TST-001` (the tenancy
and fee-conservation battery — the high-volume batch with zero residual) and `P6-TST-002`
(the merchant conservation storm: concurrent checkouts, captures-with-fees, refunds and
payouts against the trial-balance and projection sweeps, the payable reconciling to
captured − fees − refunds − payouts).

## 14. Failure scenarios

1. Payment completes after session expiry → `EXPIRED → COMPLETED_LATE`, merchant credited,
   order created, counted (`INV-MER-06`).
2. Session expires between confirm and dispatch → expiry's conditional loses or wins
   cleanly; no dispatch after expiry wins.
3. Duplicate session creation / duplicate payout initiation → keyed, one effect.
4. Payout provider times out → `UNKNOWN`, hold standing, query resolves; the refund's
   ambiguity doctrine verbatim.
5. Payout provider succeeds, response lost → dispatch-before-call: the retry converges on
   the committed dispatch, one wire operation (`INV-PAY-04`).
6. Payout against insufficient payable → committed domain refusal, nothing held.
7. Two payouts racing one payable → locks + holds; exactly the affordable set dispatches.
8. Fee schedule changed mid-flight → the pinned version prices; the new version prices
   only later intents (`INV-MER-03`).
9. Fee rounding at volume → the subtraction split leaves zero residual by construction
   (`INV-MER-04`); asserted in bulk.
10. Capture succeeds but fee lines wrong → impossible to commit unbalanced
    (`INV-LED-01`); wrong-account shape caught by the `DIRECTION:PURPOSE` probes (the
    `P5-TST-002` lesson applied from day one).
11. Merchant suspended mid-session → the session's confirm refuses; landed money still
    lands (suspension gates new dispatches, not arrived outcomes).
12. KYB not approved → onboarding refuses; no merchant, no accounts, nothing partial.
13. A merchant refund the payable cannot fund → committed refusal, nothing held; under
    `RETAINED` the fee share may leave the payable negative, and never further
    (`INV-MER-07`, ADR-0054, added by `P6-TSK-015`).
13. Destination approved by its proposer → refused in the statement (`INV-AUD-04`).
14. Payout dispatched while destination change is cooling off → the effective destination
    at dispatch is used; the pending proposal changes nothing until effected.
15. Crash between payout outcome commit and event publication → the outbox's standing
    guarantee.

## 15. Observability

| Meter | Kind | Notes |
|---|---|---|
| `finapp.checkout.session` | counter by `outcome` (completed / completed_late / expired / abandoned) | acting transitions only |
| `finapp.checkout.conversion.age` | timer | creation → completion |
| `finapp.merchant.fee.assessed` | counter | assessments, not amounts (`INV-AUD-02`) |
| `finapp.merchant.payout` | counter by `outcome` | acting judgements only |
| `finapp.merchant.payout.unknown.active` / `.age` | gauges | the stuck-payout alert; NaN never zero; fleet-wide, `max()` |
| `finapp.merchant.destination.pending` | gauge | proposals awaiting approval/cooling-off |

All eager from a plain context; a dashboard row; the derived guard takes this table over at
the flip. No new tag keys expected (`outcome` exists); if one is needed it walks the
`ALLOWED_TAG_KEYS` designed path.

## 16. Milestones

| # | Milestone | Items | Acceptance |
|---|---|---|---|
| M6.1 | Foundations | `P6-TSK-001…003` | Both modules and schemas with their floors; the merchant key authenticates and scopes; an onboarded merchant has its payable account |
| M6.2 | Fee economics | `P6-TSK-004…005` | A versioned schedule prices a merchant-bound capture gross-with-fee in one entry; the split conserves to the minor unit |
| M6.3 | Checkout | `P6-TSK-006…008` | A session is created, confirmed, paid end to end over HTTP; expiry is modelled and swept; the late-completion race lands `COMPLETED_LATE` |
| M6.4 | The merchant surface | `P6-TSK-009…010` | Transactions and payable readable, tenant-isolated, reconciling to the ledger |
| M6.5 | Payouts | `P6-TSK-011…012` | A destination survives four-eyes and cooling-off; a payout pays the net payable under the bound with honest outcomes |
| M6.6 | Observability and demonstration | `P6-TSK-013`, `P6-TST-001`, `P6-TST-002` | Meters from a fresh instance; every `Phase: 6` catalogue row landed; conservation holds under the merchant storm |
| M6.7 | The gate | `P6-DOC-001` | The exit review's own verdict flips the phase |

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
  execution behind a port; M2's merge trigger stands as the watchdog in the other
  direction.
- **The payout rail growing into Phase 7's abstraction** — ADR-0051 §4: one operation, its
  own narrow port, deliberately not a rail model.
- **Expiry treated as a query filter instead of a state** — ADR-0053 §4; the sweeper
  pattern is established and the machine's three-layer enforcement makes the shortcut
  unrepresentable.
