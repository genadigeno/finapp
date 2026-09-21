# ADR-0051 — Merchant payout: hold-then-dispatch on the payable, nothing final before settlement

Status: Proposed
Date: 2026-09-21
Phase: 6
Context: Merchant · Ledger · Payments
Supersedes: nothing. The delivery plan's named "ADR on merchant payout accounting".

## Context

A payout is the platform paying the merchant what it owes them: the first money **leaving
the platform to an external party** on the platform's own initiative. Phase 5 built every
mechanism this needs — dispatch-before-call (ADR-0046), the honest `*_UNKNOWN`, hold-then-
post (ADR-0048 §4), the two-transaction keyed command (`P5-TSK-016`) — against money
*entering*. The payout is those disciplines pointed outward.

Two design temptations must be refused explicitly:

1. **A stored payable balance.** A `balance` column on the merchant row that payouts
   decrement is a second balance authority — the exact `INV-BAL-01` violation the module
   register has warned against since Phase 0.
2. **Treating the payout rail as Phase 7's multi-rail problem.** Phase 7 generalises
   *customer payment* rails and their finality semantics. A disbursement to a merchant's
   bank account is one operation against one simulated rail; building it does not build a
   rail abstraction, and must not (ADR-0049 §4's one-provider-wide discipline applies).

## Decision

1. **The payable is the merchant's `MERCHANT_PAYABLE` ledger position.** Nothing else. The
   amount available to pay out is derived **inside the account lock** at dispatch
   (`INV-BAL-05`), never from a projection or a stored field (`INV-MER-02`).

2. **Payout is hold-then-dispatch — the refund's shape on the liability side.** In the
   dispatch transaction: the payable account locked, the available position judged under
   the lock, a Phase 3 hold placed for the payout amount (in-flight payouts reduce what the
   next payout may take — `INV-MER-05`'s arbiter), the payout row committed `DISPATCHED`
   with our minted idempotency reference (`INV-PAY-04`'s discipline). The wire call runs
   between transactions holding no connection. Completion **releases-and-posts atomically**:
   DEBIT `MERCHANT_PAYABLE` / CREDIT `PAYOUT_CLEARING`, keyed `merchant-payout:<payoutId>`.
   Failure releases the hold with nothing posted. Ambiguity commits `UNKNOWN` with the hold
   **standing** — parked, visible, alertable (`INV-LIFE-03`, the standing-hold doctrine).

3. **`PAYOUT_CLEARING` is the counterpart, and nothing here is final.** Internally
   "completed" means the platform has irrevocably instructed the rail — the cash movement
   confirms at settlement (Phase 8), exactly `INV-SET-01`'s frame on the outbound side.
   `PAYOUT_CLEARING` is an operational account seeded per currency; its balance is
   continuously "instructed but unsettled", Phase 8's raw material.

4. **One simulated disbursement operation on the provider harness.** The payout speaks to
   a simulated payout provider through its own narrow port (dispatch, query — the
   `PaymentProvider` disciplines: enumerated verdicts, evidence verbatim, no provider
   vocabulary escaping). It is deliberately **not** added to `PaymentProvider`: a payout is
   not a payment attempt operation, and widening that port would couple the two lifecycles.

5. **The payout lifecycle is its own machine**: `REQUESTED → DISPATCHED →
   COMPLETED | FAILED | UNKNOWN`, `UNKNOWN` resolvable by query and by webhook through the
   established doors, every transition conditional, three-layer enforcement as always.

## Consequences

- The payable bound cannot be raced: ten instances paying out one merchant serialize on
  the payable account's lock, and the holds make the bound cumulative across in-flight
  payouts — the `INV-PAY-05` shape with the payable as the budget.
- Duplicate initiation is keyed (`@RequiresIdempotencyKey`); a retry after timeout
  converges on the committed dispatch and re-drives the wire with the stored reference —
  the `P5-TSK-016` two-transaction contract, reused not reinvented.
- The destination is not part of this decision: registering and changing payout
  destinations is a security workflow (step-up, four-eyes, cooling-off) owned by the
  backlog's destination task, and a payout dispatches only to the **currently effective**
  destination, read in the dispatch transaction.
- Multi-currency payouts, payout scheduling/netting windows, and settlement confirmation
  are explicitly later (Phases 9, product, 8 respectively).
