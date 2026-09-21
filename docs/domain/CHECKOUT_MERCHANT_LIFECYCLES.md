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

**The money at each state**: nothing moves before `PAYMENT_PENDING`; from dispatch to
outcome the money's state is the *attempt's* (Phase 5's honest `*_UNKNOWN` doctrine
applies unchanged); at `COMPLETED`/`COMPLETED_LATE` the capture entry exists — gross to
the merchant's payable, fee to revenue, one entry (ADR-0050 §3).

## 3. The order — a fact, barely a machine

Created in the completion's own transaction, one per session (`UNIQUE`), append-only. Its
only derived quality is refund standing — computed from the payment's refund rows at read
time (the ADR-0045 derivation discipline), never stored. Fulfilment is the merchant's
business, outside the platform's books.

## 4. The merchant payout — five states

```
REQUESTED ──► DISPATCHED ──► COMPLETED
                  │    │
                  │    └────► FAILED
                  └─────────► UNKNOWN ──(query/webhook)──► COMPLETED | FAILED
```

| State | Meaning | The money |
|---|---|---|
| `REQUESTED` | Command accepted, not yet judged against the payable | Nothing held, nothing moved |
| `DISPATCHED` | Bound judged under the payable's lock; hold placed; our reference minted and committed; the wire call runs after commit | The payout amount is **held** against the payable (`INV-MER-05`) |
| `COMPLETED` | The rail accepted irrevocably | Hold released and posting committed atomically: DR payable / CR `PAYOUT_CLEARING`, keyed `merchant-payout:<payoutId>` — instructed, not settled (`INV-SET-01`) |
| `FAILED` | The rail refused | Hold released, nothing posted; the payable is whole |
| `UNKNOWN` | The rail's answer is missing or ambiguous | **The hold stands** — money visibly parked until query or webhook resolves it (`INV-LIFE-03`, the standing-hold doctrine) |

Dispatch-before-call throughout (ADR-0046's shape); every outcome edge conditional; the
takeover convergence by dispatch key (`P5-TSK-016`'s contract) applies verbatim.

## 5. The merchant — three states

`ACTIVE → SUSPENDED → ACTIVE` (reversible, operator, reasoned, audited) and `→ CLOSED`
(terminal). Suspension gates **new** dispatches — sessions, payouts — and never touches
arrived outcomes or the payable: a suspended merchant's money stays theirs and stays
explainable.

## 6. The payout destination — a proposal flow, not a field

`PROPOSED → APPROVED → EFFECTIVE` with `REJECTED` and `WITHDRAWN` terminal. The proposer
and approver are distinct authenticated actors (`INV-AUD-04`, enforced in the statement);
approval starts the cooling-off clock; only an `EFFECTIVE` destination receives payouts,
and a dispatch reads the currently effective one in its own transaction. Every step
audited with actor, reason and correlation. One `EFFECTIVE` destination per merchant
(partial unique index); a new one taking effect supersedes the old in the same
transaction.
