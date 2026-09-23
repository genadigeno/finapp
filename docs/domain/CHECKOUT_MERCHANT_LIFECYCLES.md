# Checkout and Merchant Lifecycles

Written by the Phase 5 → 6 transition (2026-09-21), the domain-facing statement of
[ADR-0051](../adr/ADR-0051-merchant-payout-accounting.md) and
[ADR-0053](../adr/ADR-0053-checkout-session-and-order.md) — what the states *mean*, which
producer earns each edge, and what the money is doing at every moment. The engineering
plan is [`PHASE_6_PLAN.md`](../project/PHASE_6_PLAN.md).

Every machine below gets the platform's standing three-layer enforcement: the aggregate's
exhaustive transition sweep, the generated schema `CHECK` plus every-writer transition
trigger, and an append-only history table (`INV-LIFE-01/-02`, the ADR-0044 doctrine:
**states are earned by producers** — no state exists without the code that produces it).

---

## 1. The distinctions this phase must not collapse

- **Checkout Session / Order / Payment.** The session is an *offer to pay* — short-lived,
  expiring, abandonable. The order is the *commercial fact* a paid session produces —
  permanent. The payment is Phase 5's machinery — the session references its intent by id
  and owns nothing of its lifecycle. A session that dies unpaid produces **no order**.
- **Customer payment / merchant payable / merchant payout.** The customer's payment is
  money arriving against the PSP (Phase 5). The payable is what the platform consequently
  *owes the merchant* — a ledger liability position, stored nowhere else (`INV-MER-02`).
  The payout is a separate, later money movement with its own lifecycle and its own
  failure modes. Three facts, three records; none is a field on another.
- **Fee assessment / fee schedule.** The schedule is versioned configuration; the
  assessment is a historical fact pinned to the version that priced it (`INV-MER-03`).

## 2. The checkout session — six states

```
OPEN ──────────────► PAYMENT_PENDING ─────────► COMPLETED
 │                        │      │
 │                        │      └────────────► EXPIRED ──► COMPLETED_LATE
 │                        └───────────────────► EXPIRED
 ├───────────────────────────────────────────► EXPIRED
 └───────────────────────────────────────────► ABANDONED
```

| State | Meaning | Earned by |
|---|---|---|
| `OPEN` | The merchant created the session; the customer has not committed | Session creation (merchant API, keyed) |
| `PAYMENT_PENDING` | The customer confirmed; a payment intent is dispatched and the provider is deciding | The confirmation (customer, by session token) — the transition that creates/confirms the intent through the port |
| `COMPLETED` | The payment captured; the order exists; the merchant is credited | The payment outcome (webhook / sync / sweeper resolution reaching the session's conditional edge) |
| `COMPLETED_LATE` | The capture landed **after** expiry; the order exists anyway | The payment outcome arriving at an `EXPIRED` row — the modelled race loser's win (`INV-MER-06`) |
| `EXPIRED` | The clock ran out before money landed | The expiry sweeper (leaderless, conditional) |
| `ABANDONED` | The merchant or customer explicitly cancelled an `OPEN` session | The cancellation |

**The race rule (ADR-0053 §5)**: expiry gates *dispatch* — an expired session starts
nothing new — but **landed money always wins**: a capture that arrives after expiry moves
`EXPIRED → COMPLETED_LATE`, credits the merchant per ADR-0050, and is counted and
operator-visible. Money is never auto-reversed by a clock.

**What refuses to create `OPEN`** (`P6-TST-001`, ADR-0058): the offer is priced when it is
created, under the version the session will carry, so a session exists only for a sale the
platform can price and the merchant is paid for. A merchant not trading (`checkout.NotTrading`),
no schedule, or none in the offer's currency (`checkout.NotPriceable`), and an amount whose fee
meets or exceeds it (`checkout.SaleBelowFee`) are all refused before the idempotency claim,
with nothing written. The rule is re-asserted when the confirmation pins the fee, for a session
opened before it existed, and never at capture: money that landed is recorded as it landed.

**The money at each state**: nothing moves before `PAYMENT_PENDING`; from dispatch to
outcome the money's state is the *attempt's* (Phase 5's honest `*_UNKNOWN` doctrine
applies unchanged); at `COMPLETED`/`COMPLETED_LATE` the capture entry exists — gross to
the merchant's payable, fee to revenue, one entry (ADR-0050 §3).

## 3. The order — a fact, barely a machine

Created in the completion's own transaction, one per session (`UNIQUE`), append-only. Its
only derived quality is refund standing — computed from the payment's refund rows at read
time (the ADR-0045 derivation discipline), never stored. Fulfilment is the merchant's
business, outside the platform's books.

## 4. The merchant payout — four states

```
DISPATCHED ──► COMPLETED
    │    └───► FAILED
    └───────► UNKNOWN ──(query)──► COMPLETED | FAILED
```

| State | Meaning | The money |
|---|---|---|
| `DISPATCHED` | Bound judged under the payable's lock; hold placed; our reference minted and committed, with the first send permit; the wire call runs after commit | The payout amount is **held** against the payable (`INV-MER-05`) |
| `COMPLETED` | The rail accepted irrevocably | Hold released and posting committed atomically: DR payable / CR `PAYOUT_CLEARING`, keyed `merchant-payout:<payoutId>` — instructed, not settled (`INV-SET-01`) |
| `FAILED` | The rail refused (`DECLINED`), nothing was sent on the first send (`PROVIDER_UNAVAILABLE`), or the provider has no record past the sweep's bound (`NEVER_RECEIVED`) — the reason on the row | Hold released, nothing posted; the payable is whole |
| `UNKNOWN` | The rail's answer is missing or ambiguous | **The hold stands** — money visibly parked until a query resolves it (`INV-LIFE-03`, the standing-hold doctrine) |

**Four states, not the five first planned** (ADR-0057 §1, `P6-TSK-012`): the dispatch
transaction judges, holds and commits `DISPATCHED` atomically, so the planned `REQUESTED` would
be a state no committed row could hold — ADR-0044 refuses a state with no producer, and the
refund, the same shape, has four.

Dispatch-before-call throughout (ADR-0046's shape); every outcome edge conditional on the row
its resolver locked; the takeover convergence by dispatch key (`P5-TSK-016`'s contract) applies,
with two refinements the payout needed because it is the first flow with both a re-sending
takeover and a sweep that can conclude "never received" (ADR-0057 §3–4):

- **a refused connection fails a payout only on its first send** — a re-send's says nothing about
  the send before it;
- **every send is preceded by a committed send permit** (`last_dispatched_at`), and
  `NEVER_RECEIVED` is concluded only when the latest permit is older than the sweep's bound,
  judged on the locked row — so no send can follow the conclusion.

The query sweep is the resolver of `DISPATCHED` rows a crash stranded and of `UNKNOWN` ones; the
provider webhook ADR-0051 §5 named is deferred (ADR-0057 §10).

## 5. The merchant — three states

`ACTIVE → SUSPENDED → ACTIVE` (reversible, operator, reasoned, audited) and `→ CLOSED`
(terminal). Suspension gates **new** dispatches — sessions, payouts — and never touches
arrived outcomes or the payable: a suspended merchant's money stays theirs and stays
explainable.

## 6. The payout destination — a proposal flow, not a field

Stated in full by `P6-TSK-011` and [ADR-0056](../adr/ADR-0056-payout-destination-four-eyes.md):

```
PROPOSED ──approve (a second operator, step-up)──► APPROVED ──cooling-off elapsed (the platform)──► EFFECTIVE ──a later one effected──► SUPERSEDED
   │ reject (an approver) ──► REJECTED               │ withdraw ──► WITHDRAWN
   └ withdraw ──► WITHDRAWN
```

| State | Meaning | Produced by |
|---|---|---|
| `PROPOSED` | An operator proposed the destination. Nothing pays to it | An operator with `MERCHANT_ADMINISTER`, keyed |
| `APPROVED` | A second, distinct operator approved it; the cooling-off deadline is pinned on the row. Nothing pays to it yet | An operator with `PAYOUT_DESTINATION_APPROVE` who is not the proposer (`INV-AUD-04`) |
| `EFFECTIVE` | The destination payouts go to — at most one per merchant | The platform's effectuation sweep, once the deadline has passed |
| `SUPERSEDED` | A later destination took effect in its place, in the same transaction | The same sweep |
| `REJECTED` | The second pair of eyes said no | An approver |
| `WITHDRAWN` | Stopped before it took effect — during the proposal **or the cooling-off** | An operator with `MERCHANT_ADMINISTER` |

- The proposer and approver are distinct authenticated operators, enforced in the domain, in
  the approval statement and by `CHECK` — a merchant's API key reaches none of this (ADR-0056
  §1). Each needs a `MULTI_FACTOR` session exactly when they have an active factor.
- **The cooling-off is a control only because it can be acted on**: `APPROVED → WITHDRAWN` is
  the edge that stops a change nobody meant. The deadline is `approved_at` plus the configured
  period (default 72 hours), pinned at approval; the schema refuses an effect before it.
- **One open change per merchant** (proposed or approved) and **one `EFFECTIVE` destination**,
  each a partial unique index. The effectuation supersedes the old row before it effects the
  new one, and both commit together.
- A payout dispatch reads the currently effective destination in its own transaction; a change
  still cooling off changes nothing there. Each change is its own immutable row, so the id a
  payout records is the destination version it was sent to.
- Every step is audited with actor, reason and correlation — the refused self-approval
  included, as `DENIED`.
- The platform holds the provider's opaque reference and a four-character suffix, never bank
  details.
