# ADR-0058 — A sale that does not cover its fee is refused at the price

Status: Proposed

Date: 2026-09-23

## Context

ADR-0050 §4 prices a sale as `fee = round(gross × rate, policy) + fixed` and derives the
net by subtraction. `FeeCalculation` computes a fee larger than its gross honestly rather than
clamping it, because a clamped fee would disagree with its own recomputation under the pinned
version (`INV-MER-03`). `FeeAssessment.exceedsGross()` names the case, and until this decision
nothing consulted it.

So a sale whose fee met or exceeded it was accepted. A fixed part of 100.00 on a sale of 50.00
priced, pinned and captured, and the capture's one entry took the merchant's payable to −50.00
by itself, with no refund and no payout involved. `P6-TSK-015` (ADR-0054) found the
consequence one step later: the refund of such a sale reserves the one-unit floor, because a
hold is positive by definition, and is refused as unfunded while the payable stays negative.
That refusal is conservative, but it lets a customer's refund wait on the merchant's other
sales. ADR-0054 recorded the question, *refuse it at the price, or let its refund reserve
nothing*, and gave it to `P6-TST-001`.

The case is almost always a configuration error: a fixed part entered in major units, or a
schedule meant for a larger basket. Accepting it has three costs:

- **The platform extends credit at the moment of sale.** That is a second path to a merchant
  debt beside ADR-0054's retained fee, and `INV-MER-07` had to name it.
- **The merchant pays to sell.** A sale that nets nothing moves a customer's money through the
  platform for the platform's fee alone. A sale that nets less than nothing is a price no
  merchant agrees to knowingly.
- **The refund is stuck.** It cannot be funded until other sales refill the payable.

## Decision

1. **A sale must net the merchant at least one minor unit.** A sale whose fee meets or exceeds
   its gross is refused, judged under the version the sale will carry: `net ≤ 0`.
   - *Meets* is included. A net of zero leaves the payable where it was, so its `RETURNED`
     refund reserves a floor against money the sale never raised. Its `RETAINED` refund takes
     the whole gross out of the payable.
   - The smallest admitted sale nets one minor unit. Its full `RETURNED` refund is funded by
     the sale it reverses and lands the payable at exactly zero.

2. **Refused at the price, where the checkout already refuses what it cannot price.**
   `CheckoutSessions.open` prices the offer under the merchant's effective version, after the
   pricing lookup and before the idempotency claim, beside `checkout.NotTrading` and
   `checkout.NotPriceable`. The refusal is `checkout.SaleBelowFee` (422): nothing is written and
   the key is not spent. The session carries the version it was judged under, so the price
   judged is the price the customer later agrees to.

3. **Re-asserted where the price is agreed.** `MerchantSettlement.pin` judges every pin under
   its own version before the insert, whoever the caller is. The pin runs in the confirmation's
   transaction, so a refusal rolls back the payment intent it would have priced, and the surface
   answers `checkout.SaleBelowFee`. The pin carries the session's version, so through checkout
   only a session opened before this rule can reach the refusal. The re-assertion is for every
   other path: that one, and a future caller that is not a checkout.

4. **Never at capture.** A capture reports money the provider has already moved. Refusing to
   record it would leave the journal short of the money (ADR-0046: the provider acts on its own
   schedule). The capture's composition is unchanged, and a pin that exists is settled as
   pinned. `FeeCalculation` is unchanged too and still does not clamp: the rule decides whether
   to accept a price, and does not change how a price is computed.

5. **An offer in a currency its schedule does not price is `checkout.NotPriceable`.** The same
   pricing step surfaces it. A schedule prices one currency, the one its assignment checked
   against the merchant's settlement currency, and `FeeCalculation` refuses a foreign gross by
   name. Before this decision such an offer was accepted at creation and refused only at
   confirmation, where the merchant has no payable in that currency to credit
   (`NoWalletForPaymentException`), after the customer had reached the payment page. The pin's
   judgement meets a foreign gross the same way, by name and before the insert, so the
   capture's own backstop (no payable in that currency) is reached only by a pin written before
   this rule.

## Alternatives Considered

### Option A — Refuse at the price (chosen)
Pros: the only point at which nothing exists yet, where a refusal costs the merchant a
correction and nobody money. It is the checkout's existing pattern for what it cannot price
(`NotPriceable`, `NotTrading`), so there is one discipline, not a new one. It closes the second
debt path, so `INV-MER-07` again names one source of merchant debt for new sales.
Cons: a merchant who genuinely wants to sell at a loss cannot. That is not a use case this
platform has, and it would need an explicit, audited term of the agreement rather than an
accident of arithmetic. A creation that succeeded under one version and is retried with the same
key after a dearer version became effective is refused, not replayed. That is the pre-claim
reads' recorded property: a suspension between the two calls does the same.

### Option B — Let the refund of such a sale reserve nothing
Pros: the sale's refund would no longer be refused while the payable is negative.
Cons: it accepts the debt created at capture and adds a refund path that reserves nothing
against a negative payable. The platform would then fund part of the merchant's refund with
its own money, which is exactly what ADR-0054 exists to prevent. It treats the symptom, the
stuck refund, and keeps the cause, the negative payable at capture.

### Option C — Clamp the fee to the gross
Pros: no refusal anywhere, and the payable never goes below zero at capture.
Cons: the recorded fee would no longer be what the pinned version computes, so `INV-MER-03`
breaks for cosmetics, and a zero net still remains. `FeeCalculationTest` pins the refusal to
clamp.

### Option D — Refuse at capture
Pros: none that survive. Cons: the money has already moved at the provider. A refused capture
is a journal that no longer explains the settlement, the one outcome worse than a negative
payable.

## Consequences

Positive:
- No sale accepted under this rule takes a payable below zero at capture. A merchant's
  payable goes below zero only by a retained refund share (ADR-0054).
- A single full refund of any accepted sale reserves that sale's own net, which is positive,
  so the sale it reverses funds it under either policy until the merchant's payouts spend it. A
  partial series on a sale netting a unit or two can still leave its last refund one unit
  short: the reservation's floor, conservative by design (`MerchantSettlement.reservation`).
- A misconfigured fixed part shows up as a refused offer on the merchant's first sale, not as
  a debt discovered at reconciliation.

Negative:
- A retried creation can be refused after a repricing, as described under Option A.
- A pin written before this rule still settles as pinned. The rule holds for pins it judged,
  and `INV-MER-07` says so.

Operational impact: `checkout.SaleBelowFee` is a merchant-actionable 422: sell a larger
amount, or ask for different terms. A burst of it after a schedule version is published means
the version's fixed part is wrong for the merchant's basket, which is an operator's signal.
No new meter: the refusal writes nothing and moves nothing, and the merchant reads it in the
response.

Security impact: none. The refusal's message and detail name no amount and no merchant.

Financial impact: the merchant-debt surface narrows to one source, the fee a `RETAINED`
refund keeps (ADR-0054). No posting shape changes.

## Invariants / Constraints

- `INV-MER-04`: unchanged. The split still conserves the gross exactly, and the rule
  consults the net that subtraction produced.
- `INV-MER-03`: unchanged. The fee is judged under the version the sale carries, never
  recomputed differently to pass the rule.
- `INV-MER-07`: its statement narrows. A fee exceeding its sale is no longer a path to debt
  for any sale this rule judged.
- `INV-IDEM-01`: a refusal happens before the claim, so a refused creation consumes no key.

## Follow-up

- `P6-TST-002` (the conservation storm): merchant debt is bounded by the retained fees on
  refunded sales alone.
- A term that genuinely allows a sale below its fee would be a new ADR, with an explicit,
  audited agreement term.
