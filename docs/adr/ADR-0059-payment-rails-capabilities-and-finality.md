# ADR-0059 — A payment rail declares its capabilities, finality is modelled per rail, and the attempt's machine follows the rail's interaction model

Status: Proposed (2026-09-24, the Phase 6 → 7 transition)
Date: 2026-09-24
Phase: 7
Context: Payments · Payment Methods · Accounts · Ledger
Supersedes: nothing. Builds the rail abstraction ADR-0049 §4 deliberately deferred ("the port
is one provider wide") now that there is more than one sample, and gives `INV-REV-03` the
subject ADR-0049 §3 recorded it did not have.

## Context

Phase 5 built one provider port around a simulated card-style PSP (ADR-0049). It did so on
purpose: an abstraction designed from a sample of one becomes that sample's SPI. Phase 7 adds
rails whose semantics are **materially different**, not cosmetically different:

| | Card | Instant credit transfer | The platform's own wallet |
|---|---|---|---|
| Steps | Authorize, then capture | One push executes the payment | One book movement on our ledger |
| Who decides the outcome | Issuer, through PSP and network | The payer's PSP, the scheme, the payee's PSP | The platform, in one transaction |
| Can the payee's credit be taken back? | Yes: void before capture, refund after, chargeback for the scheme's dispute window | No: irrevocable once the scheme confirms | No: final when the posting commits |
| How ambiguity ends | Only when the provider or a query says so | The scheme bounds it: past its outcome deadline the status inquiry is authoritative | There is none |
| Settlement | Later, through clearing, reported by the PSP | On the scheme's own cycle (real-time gross or deferred net), reported by the scheme | Nothing external to settle |

The delivery plan names two risks for exactly this moment. The first is a lowest-common-denominator
abstraction: a generic "execute payment" with rail-neutral states, which hides that a card
capture stays reversible against us for months while an instant payment is final in seconds.
The second is attempting a reversal on an irrevocable rail. Phase 5's attempt machine
(`AUTH_*`, `CAPTURE_*`) is the two-step machine. Pushing an instant payment through
`AUTH_DISPATCHED` and `CAPTURED` would be the first risk made real.

## Decision

1. **A rail is a named adapter behind the payments domain, and it declares its capabilities as
   data.** The port `PaymentRail` carries a `RailCapabilities` descriptor that the domain
   consults before it acts:

   | Capability | Card | Instant | Wallet |
   |---|---|---|---|
   | `interactionModel` | `TWO_STEP` | `PUSH` | `BOOK` |
   | `finality` | `REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS` | `FINAL_ON_ACCEPTANCE` | `FINAL_ON_POSTING` |
   | `reversals` (operations the rail honours) | `VOID` of an uncaptured authorization | none | none |
   | `refundMode` | `PROVIDER_REFUND` against the capture | `RETURN_PAYMENT`: a new credit transfer back to the payer | `BOOK_REFUND`: a compensating book movement |
   | `settlement` | `DEFERRED_VIA_CLEARING` | `SCHEME_REPORTED` | `NONE` |
   | `outcomeDeadline` | none | the scheme's bound on a final answer | not applicable |
   | `disputes` | card-scheme chargebacks | none | none |
   | currencies and per-currency maximum | adapter configuration | the scheme's limits | the platform's |

   The descriptor is immutable per adapter version and recorded with every routing decision
   (ADR-0060). What the domain knew when it decided is therefore stored, not re-derived from
   whatever the adapter says today. **No core code branches on a rail's name**: a second instant
   scheme is an adapter and a routing rule, never a core change.

2. **The attempt's machine follows the interaction model — three machines, one aggregate root.**
   `TWO_STEP` keeps Phase 5's seven states verbatim, plus the void edges the card-reversal task
   adds. `PUSH` has its own vocabulary: an initiation `AWAITING_PAYER` (a pay-by-bank payment
   waiting for the payer's authorization at the payer's own PSP), `EXECUTION_DISPATCHED`,
   `EXECUTION_UNKNOWN`, `EXECUTED`, `FAILED`. `BOOK` is born `EXECUTED` or `FAILED` inside the
   confirmation's own transaction (ADR-0043's property), so it has no dispatch and no unknown.
   The attempt's rail and model are frozen at birth. The generated `CHECK`s and every-writer
   triggers key each permitted transition on the model (the three-layer discipline, three
   times). **No state name is shared across models**: `EXECUTED` is not `CAPTURED`, so no query,
   report or reconciliation can mistake one rail's completion for another's. The intent's machine
   (`REQUIRES_CONFIRMATION → PROCESSING → SUCCEEDED | FAILED | CANCELLED`) is the customer's
   view and stays rail-agnostic.

3. **`INV-REV-03` is enforced at the capability, before anything is written or sent.** A reversal
   (a void, or a cancellation after dispatch) of an attempt whose rail does not list it in
   `reversals` is refused by the domain: `payments.ReversalNotSupported`, `409`, nothing written,
   nothing sent. It is never attempted and failed at the provider. **A refund is not a
   reversal** (`INV-REV-01`): it is a new forward movement, executed in the rail's `refundMode`.
   A merchant can therefore refund an instant payment by return payment, while nobody can reverse
   it.

   *(The `refundMode` dispatch shipped `P7-TSK-010`: one refund command executes two of the
   declared modes — `PROVIDER_REFUND` against the capture, `RETURN_PAYMENT` as a new push
   citing the original's scheme reference over `PushRail.sendReturn` — with the eligible
   state and the bound base per model (`CAPTURED`/captured amount on the card, `EXECUTED`/the
   intent's frozen ask on the push rail), payments `V018` holding the same per-model bound
   for every writer and refusing the model whose refund producer has not shipped
   (`BOOK_REFUND`, `P7-TSK-011`). The return resolves against its own wire: the refund sweep
   partitioned by the attempt's model, `ReturnResolution` inquiring and re-driving under the
   send permit with the same reference.)*

4. **Finality is not settlement.** Finality says whether the payee's credit can be taken back.
   Settlement says whether the interbank obligation is discharged. An instant payment is final
   on acceptance and still unsettled until the scheme reports its cycle, so `INV-SET-01` holds
   on every rail without an exception. **Each external rail has its own clearing position**: the
   card PSP's is `SETTLEMENT_CLEARING` (its meaning narrowed to the card rail by this ADR), and
   the instant scheme's is `INSTANT_CLEARING`, added by the task that first posts to it (the
   member's own doctrine, `AccountPurpose`) *(refined by `P7-TSK-006`: the member and its
   seeds arrive with the descriptor that must name it — ledger `V013` — and the first
   posting stays with the flows; the doctrine's substance intact)*. One counterparty's receivable never nets against
   another's payable, because Phase 8 must discharge each against that counterparty's own
   settlement evidence.

5. **The platform accepts cards; it does not issue them.** Cardholder, Card Account, Physical
   Card and Virtual Card belong to the issuer (`external`). The platform issues no card and holds
   no card account. Its card concepts are:
   - the **Card Token**: a payment method in `paymentmethods`, with `INV-PAY-02` unchanged;
   - the payment's **authorization, capture, void and refund**, in `payments`;
   - the **chargeback and dispute**, in `payments` (ADR-0061);
   - the **clearing and settlement evidence**, preserved by `payments` and reconciled by
     `settlement` (Phase 8).

   Network routing, 3-D Secure and clearing belong to the processor and the network, behind the
   adapter (`INV-PAY-03`). Issuing is a product decision this ADR does not take: were it ever
   taken, it would be a new bounded context with its own PCI scope, not an extension of this
   one.

6. **The wallet rail is internal, and the wallet stays in `accounts`.** Paying a checkout from a
   wallet is a *payment*: it has an intent, an order, a fee and a refund lifecycle. It is realised
   by a book movement that commits with its posting in one transaction. It is therefore a rail
   with no third party, not a transfer, and not a second balance: the wallet's money is the
   ledger's `CUSTOMER_WALLET` account, judged under its lock (`INV-BAL-04`, `INV-BAL-05`).

   ADR-0042's split trigger is evaluated here and **not met**. Funding, withdrawal and wallet
   payments are payments on rails, owned by `payments`; the wallet product acquires no lifecycle
   of its own. A wallet-to-wallet movement between two customers stays Phase 4's `Transfer`.

   *(Shipped `P7-TSK-011`: `BookRail.RAIL` declared standalone — no adapter exists because no
   wire does — with the coherence rules forcing exactly this combination; the instrument is
   the INTENT's shape (`payment_method_id` XOR `debit_account_id`, `V019`, the wallet resolved
   never named — the withdrawal's precedent); the whole payment commits in the confirmation's
   one transaction through the shared execution settle block — pair-locked in fixed order
   with availability judged under the wallet's own lock via place-release-post, the
   transfer's `P4-TST-001` deadlock lesson designed in — and the book refund completes inside
   the refund claim's own transaction through the same `applyRefund`, the counterpart resolved
   to the intent's debit wallet where no clearing exists. Routing version 4 seeds the wallet
   pay-in onto the rail.)*

7. **Provider vocabulary stays behind each adapter** (`INV-PAY-03`, per rail). Scheme message
   types, reason codes, alias and identifier formats, time-outs and operating hours are adapter
   configuration. The core sees our verdicts, our references and the descriptor. This is also
   what keeps the core country-neutral: no identifier format and no scheme's rulebook is a
   domain type.

## Alternatives Considered

### One generic `execute` operation with rail-neutral states
Pros: one machine, one sweeper, one table.
Cons: the named risk. `AUTHORIZED` and the refundable window disappear, and the chargeback
exposure a card capture carries becomes invisible in exactly the place Phase 8 will need it.

### One aggregate family per rail, with no shared root
Pros: each rail's machine is local and obvious.
Cons: the intent, the idempotency claim, the evidence store, the webhook door and the
one-live-attempt rule would each be built three times. The intent's "one live attempt" index
would have to span tables.

### Rail behaviour as branches on the rail's name
Pros: fast to write.
Cons: the core learns rail names, and the second instant scheme becomes a core change. This is
exactly the leak `INV-PAY-03` exists to prevent, one level up.

### Model an instant-payment recall as a reversal
Pros: symmetric with the card void.
Cons: a recall is a *request* the payee's PSP may refuse, days later. Modelling it as a reversal
fabricates an outcome. If a recall is ever built, it is a new operation with its own lifecycle.

## Consequences

Positive:
- `INV-REV-03` has a subject and an enforcement point that runs before any write or send.
- The gate's "materially different finality semantics" is a property of stored data and three
  machines, not a claim about adapters.
- Every Phase 5 and Phase 6 behaviour on the card rail is unchanged, and the full battery is the
  proof. The card rail's descriptor is written from what ADR-0049 §2 already decided.

Negative:
- The attempt table carries three machines. Its generated constraints grow, and every sweep and
  gauge must say which model's states it reads.
- The descriptor is a second place where a rail's truth is stated beside the adapter's
  behaviour. Each rail's contract tests must assert that the adapter behaves as its descriptor
  says.

Operational impact: per-rail meters (the rail as a bounded tag); per-rail stuck-state gauges
in the payout's shape. *(Shipped `P7-TSK-015`: `finapp.payments.rail.outcome` and
`.rail.latency`, their series registered from each declaration's capabilities - never a rail's
name; the stuck gauges per machine, not per rail, because the machines own the unknown.)*
Security impact: none by itself. The PCI boundary is unchanged, and bank data follows ADR-0062.
Financial impact: each external rail gets its own clearing position; the wallet rail posts in
the confirmation's own transaction.

## Invariants / Constraints

`INV-REV-03` (subject given here), `INV-SET-01` (per rail, no exception), `INV-PAY-03` (per
adapter), `INV-LIFE-01`, `INV-LIFE-02` and `INV-LIFE-04` (three machines, each under the
three-layer discipline), `INV-BAL-01` (the wallet has no second balance), `INV-RAIL-01`,
`INV-RAIL-03` (catalogued with this ADR).

## Follow-up

- `P7-TSK-001` builds the port and the descriptor, declares the card rail, and adds the attempt's
  rail. `P7-TSK-002` builds the per-model machines. The card void, the instant rail and the
  wallet rail each land with their own task (`PHASE_7_PLAN.md` §16).
- The Phase 7 review reads this ADR against the code before accepting it — the
  `P6-DOC-001` precedent.
