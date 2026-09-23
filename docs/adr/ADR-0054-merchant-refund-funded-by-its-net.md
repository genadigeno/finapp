# ADR-0054 — A merchant refund is funded by its net; the only credit it extends is the fee the platform keeps

Status: Proposed
Date: 2026-09-23
Phase: 6
Context: Merchant · Payments · Ledger
Supersedes: nothing. Extends ADR-0048 §4 (the refund's hold) to merchant-bound refunds, and
ADR-0050's consequence on refunds ("fee treatment per the schedule's refund policy") to what a
refund must be able to fund.

## Context

`P6-TSK-010`'s end-to-end test found that a merchant could not refund a sale in full, under
either fee policy. Phase 5's refund places a hold on the account it debits and judges it
against that account's available balance (`INV-BAL-04`) — **for the gross**. For a wallet
top-up that is right: the refund takes the gross from the customer's wallet. For a checkout
payment the debit account is the merchant's payable, which after a capture of 100.00 with a
3.20 fee holds the **net**, 96.80. So the hold for 100.00 was always refused.

The two policies make it two different problems:

- **`RETURNED` is a defect.** The refund entry (`P6-TSK-014`) takes the gross out of the
  payable and puts the fee share back in the same entry, so it takes exactly 96.80 and lands
  the payable at zero. The bound never saw the fee coming back.
- **`RETAINED` is a decision nobody had made.** The merchant received 96.80 and owes the
  customer 100.00. Either the platform refuses, or it lets the payable go below zero: the
  merchant owing the platform the fee. That is credit extended to a counterparty.

A second fact makes even the `RETURNED` case subtler than subtracting the fee. The returned
share is allocated cumulatively in the order refunds **complete** (`P6-TSK-014`), the only
order that stays exact when a sibling refund fails. When a refund is dispatched, siblings may
still be in flight and later refunds may complete first, so its share is not yet known. It can
move by a minor unit depending on the order: a one-cent fee refunded in halves returns the
cent to one of the halves, and which one depends on the order they complete. A hold that
assumed the larger share would take a cent more than it reserved when the smaller one
happened.

## Decision

1. **The funding bound judges what the refund will take, asked of the composition.** The hold
   is sized by `RefundComposition.reserve`, asked in the dispatch transaction and in payment
   vocabulary: *what must the account this refund debits have available?* `payments` still
   never learns what a fee is (`INV-PAY-03`). The question goes to **the same composition
   that will write the lines** (through `PaymentOutcomes`), so the hold and the posting cannot
   be priced by two different composers.

2. **A merchant-bound refund reserves its net, under either policy:** the refunded amount less
   the **least fee share any completion order can still give it** (`FeeCalculation.leastReturnedFee`).
   - When the refund covers everything left of the capture (every full refund, and the last
     of a series), only one order is possible, and the share is exact: a full `RETURNED`
     refund reserves 96.80 and lands the payable at exactly 0.00.
   - Otherwise the share is `⌊fee × refunded / gross⌋`, less one under `HALF_EVEN` when that
     is odd. That is never more than any order returns, and at most one minor unit less (two
     under `HALF_EVEN`). The completion releases the difference.
   - The reservation is floored at one minor unit, because a hold is positive by definition.

3. **Under `RETAINED`, the fee share the merchant does not get back may take the payable below
   zero: the merchant owes the platform that fee, and nothing more.** The merchant funds the
   net of what it received. The share is the only credit a refund extends: a receivable
   against the merchant, held in the merchant's own payable account rather than reclassified,
   so the payable stays the merchant's single position (`INV-MER-02`). Together with the one
   pre-existing path below (a capture whose fee exceeds its sale), a merchant's payable goes
   below zero **only by fee the platform has charged and not collected, never by money the
   platform paid out**. The invariant is `INV-MER-07`.

4. **Beyond that, a refund is refused as unfunded:** `payments.RefundUnfunded`, a 409 with
   nothing written. The platform extends no general credit to merchants.

5. **Recovery needs no mechanism.** Every later capture credits the same position, and
   ADR-0051's payout bound (`available ≥ amount`, judged inside the payable's lock) pays out
   nothing while the position is negative. The debt is repaid from the merchant's next sales
   before any money leaves the platform.

## Alternatives considered

### Refuse under `RETAINED`
Pros: no credit exposure at all; the simplest rule.
Cons: a merchant could never fully refund its first sale. The customer is pushed toward a
chargeback, which costs every party more, and the platform refuses a refund to protect a fee it
keeps either way.

### Permit a negative payable, unbounded
Pros: every refund is admitted.
Cons: once payouts exist this is the refund-after-payout loss. A merchant paid out and then
refunding everything leaves the platform funding the refunds with its own money. That is the
bust-out pattern, and a credit decision no one made.

### Hold the refund pending funds
Pros: no exposure, no refusal.
Cons: a new lifecycle state with its own sweeper, and a customer who is owed money waiting on a
merchant's next sale.

### Reserve the gross when available, and the net otherwise
Pros: extends credit only when the payable cannot cover the fee at dispatch time.
Cons: the reservation would depend on timing, for an exposure already bounded by the fee.

## Consequences

Positive:
- A merchant can refund a sale in full under either policy.
- The platform's worst case on a payment is not collecting its fee; it never funds a
  merchant's refund with its own money.
- What must be available is the same under both policies. The policy decides only what is
  posted.

Negative:
- Concurrent partial refunds that together empty a payable holding only their own sale's net
  may see the last one refused while a sibling is in flight. A retry after the siblings
  resolve reserves the exact remainder.
- A capture whose fee meets or exceeds its gross (a large fixed part on a small sale, which
  nothing refuses today) leaves the payable negative at capture. Its refund reserves the
  one-unit floor and is refused while the payable is negative, conservatively. The real fix is
  deciding whether such a price should be accepted at all; recorded with an owner, not decided
  here.

Operational impact: a negative merchant payable is visible in the merchant's own payable view
(`GET /v1/merchant/payable`, signed) and derivable from the journal. An operator-facing view
of merchant debt, and collecting it from a merchant that stops trading (direct debit,
invoicing, write-off with its accounting), are later capabilities.

Security impact: none. The refund surface, its permission and its audit are unchanged.

Financial impact: a new receivable class (merchant debt from retained fees), bounded per
refund by its fee share and recovered before any payout.

## Invariants / constraints

- `INV-MER-07` (new): a refund is funded by its net, and the only credit it extends is the fee
  the platform keeps. A merchant's payable goes below zero only by fee charged and not collected.
- `INV-BAL-04`: no hold is ever placed beyond available. The negative arises only from the
  completion's posting, bounded by the reservation's allowance.
- `INV-MER-05`: a payout is bounded by the payable, so a negative payable refuses every payout.
- `INV-PAY-05`: the capture bound is judged first and is unchanged.

## Follow-up

- `P6-TSK-012` (payouts): a negative payable refuses every payout, tested with its bound.
- `P6-TST-002` (the conservation storm): refunds against a drained payable as a counted
  refusal kind; merchant debt from retained fees bounded as this ADR states.
- A fee meeting or exceeding its gross: refuse it at the price, or let its refund reserve
  nothing. Recorded by `P6-TSK-015`'s completion with an owner.
