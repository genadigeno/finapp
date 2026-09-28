# ADR-0059 — A payment rail declares its capabilities, finality is modelled per rail, and the attempt's machine follows the rail's interaction model

Status: Accepted (2026-09-28, `P7-DOC-001` — read against the implementation at the phase review; nine passages corrected to it, one rule made where the text promised more than the code held, and one defect in the code fixed, first. Amended by the Phase 7 → 8 transition's gate the same day: §2's void and capture conclusions, and §4's second presentment)
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

   *(Every Phase 7 declaration leaves the last row unrestricted - no currency set, no maximum,
   compiled constants: the row says where a restriction would come from, not one that exists.)*

   The descriptor is immutable per adapter version, and every routing step records the
   **version** of the declaration it judged (ADR-0060). *(This read "recorded with every routing
   decision … stored, not re-derived from whatever the adapter says today" until the phase
   review, `P7-DOC-001`, found that only `descriptor_version` is stored, and that every resolver -
   the clearing account, the refund mode, the void gate - re-derives the capabilities from the
   running build through `PaymentRails.capabilitiesOf`. The review's ruling closes the gap that
   opens: **a rail's money semantics are frozen per `RailId`**. The interaction model, finality,
   reversals, refund mode, settlement, disputes and clearing position of a declared rail never
   change under its name *(and, since the Phase 7 → 8 transition's gate, its outcome deadline:
   it decides when a withdrawal's explicit "never seen" may conclude it and release its hold, so
   a shortened deadline under the same name could conclude an old in-flight withdrawal while a
   re-send was still live)*; a change to any of them is a new `RailId`, so a payment already made
   is always read under the semantics it was made under. `RailMoneySemanticsArePinnedTest`
   freezes each rail's tuple and its declaration version, and fails an edit that does not also
   mint a new rail.)* **No core code branches on a rail's name.** *(This went on "a second
   instant scheme is an adapter and a routing rule, never a core change" until the review: Phase
   7 wires one adapter per interaction model - one card provider, one push rail - and
   `Withdrawals` refuses, loudly, a routing choice naming a push rail other than the wired one.
   A second rail of the same model needs per-rail operation lookup in the composition root and,
   by §4, its own clearing position: an adapter, a routing rule and that wiring, still no core
   branch on a name. The confirmation's push branch initiates on the wired rail for the same
   reason - the directory declares exactly one push rail, so routing cannot choose another -
   and `Withdrawals`' guard is the pattern the phase adding a second scheme carries there.)*

2. **The attempt's machine follows the interaction model — three machines, one aggregate root.**
   `TWO_STEP` keeps Phase 5's seven states verbatim, plus the void *(the card-reversal task,
   `P7-TSK-004`, added three states - `VOID_DISPATCHED`, `VOID_UNKNOWN`, `VOIDED` - with the edge
   `AUTHORIZED → VOID_DISPATCHED` and the declined-capture redirect `CAPTURE_DISPATCHED |
   CAPTURE_UNKNOWN → VOID_DISPATCHED`; a declined void lands `FAILED` from the void states, and
   the drawn `AUTHORIZED → FAILED` edge, which had no producer, was deliberately not implemented
   - recorded here by the phase review, `P7-DOC-001`)* *(the Phase 7 → 8 transition's gate
   corrected this sentence, which read "a declined or never-received void lands `FAILED`": a
   void exists to release a standing authorization and a re-sent void is harmless
   by definition, so a void the provider says it never received is RE-SENT by its stored
   reference and concluded by that answer - `FAILED(NEVER_RECEIVED)` left the authorization held
   against the customer until it lapsed. And a CAPTURE that never left (a refused connection)
   or that the provider says it never received now takes the redirect on a rail declaring
   `VOID`, exactly as a declined capture does: the gate found both failing the payment with the
   authorization standing, so a retry took a second hold for one purchase)*. `PUSH` has
   its own vocabulary: an initiation `AWAITING_PAYER` (a pay-by-bank payment waiting for the
   payer's authorization at the payer's own PSP), `EXECUTION_DISPATCHED`, `EXECUTION_UNKNOWN`,
   `EXECUTED`, `FAILED`. *(The review: Phase 7's push attempt is the pay-in alone,
   `AWAITING_PAYER → EXECUTED | FAILED`. `EXECUTION_DISPATCHED` and `EXECUTION_UNKNOWN` are
   declared, carried by the generated `CHECK`s and triggers, and **produced by nothing**: the
   platform's outbound pushes are their own aggregates - the withdrawal (`DISPATCHED → UNKNOWN →
   COMPLETED | FAILED`, ADR-0062 §3) and the return payment, a refund row in the refund's own
   four-state machine (`P7-TSK-010`). The two states stay reserved for an outbound push that is
   an attempt, which no Phase 7 flow is.)* `BOOK` is born `EXECUTED` inside the confirmation's
   own transaction (ADR-0043's property), so it has no dispatch and no unknown *(this read "born
   `EXECUTED` or `FAILED`" until the review: `FAILED` is in the model's state set, but an
   unaffordable wallet payment rolls its whole transaction back - `WalletPaymentUnfunded`,
   nothing written - so no book attempt is ever born failed)*.
   The attempt's rail and model are frozen at birth. The generated `CHECK`s and every-writer
   triggers key each permitted transition on the model (the three-layer discipline, three
   times). **No non-terminal state name is shared across models**: `EXECUTED` is not
   `CAPTURED`, so every edge's from-state names its machine. *(This read "No state name is
   shared across models" until the review: the terminals are shared - `FAILED` by all three
   models, `EXECUTED` by `PUSH` and `BOOK` - and have no edges to blur. A query, report or
   reconciliation that reads a completion therefore filters on the attempt's
   `interaction_model` or rail, never on the status alone; no Phase 7 statement mixes them.)*
   The intent's machine (`REQUIRES_CONFIRMATION → PROCESSING → SUCCEEDED | FAILED`, and
   `REQUIRES_CONFIRMATION → CANCELLED`) is the customer's view and stays rail-agnostic.

3. **`INV-REV-03` is enforced at the capability, before anything is written or sent.** A reversal
   (a void, or a cancellation after dispatch) of an attempt whose rail does not list it in
   `reversals` is refused by the domain: `payments.ReversalNotSupported`, `409`, nothing written,
   nothing sent. It is never attempted and failed at the provider. **A refund is not a
   reversal** (`INV-REV-01`): it is a new forward movement, executed in the rail's `refundMode`.
   An instant payment can therefore be refunded by return payment, while nobody can reverse it.
   *(This read "A merchant can therefore refund" until the review: every refund is an operator's
   act under `PAYMENT_REFUND`, whoever the payment's merchant.)*

   *(The `refundMode` dispatch shipped `P7-TSK-010`: one refund command executes two of the
   declared modes — `PROVIDER_REFUND` against the capture, `RETURN_PAYMENT` as a new push
   citing the original's scheme reference over `PushRail.sendReturn` — with the eligible
   state and the bound base per model (`CAPTURED`/captured amount on the card, `EXECUTED`/the
   intent's frozen ask on the push rail), payments `V018` holding the same per-model bound
   for every writer and refusing the model whose refund producer has not shipped
   (`BOOK_REFUND`, `P7-TSK-011`). The return resolves against its own wire: the refund sweep
   partitioned by the attempt's model, `ReturnResolution` inquiring and re-driving under the
   send permit with the same reference.)* *(And since, recorded by the review: `P7-TSK-011`
   shipped the third mode, `BOOK_REFUND`, completing inside the refund claim's own transaction,
   payments `V019` lifting `V018`'s refusal of it; `P7-TSK-013` restated the bound as the
   combined refunds-plus-chargebacks bound, `V021` holding it for every writer - ADR-0061 §3.)*

4. **Finality is not settlement.** Finality says whether the payee's credit can be taken back.
   Settlement says whether the interbank obligation is discharged. An instant payment is final
   on acceptance and still unsettled until the scheme reports its cycle, so `INV-SET-01` holds
   on every rail without an exception. **Each external rail has its own clearing position**: the
   card PSP's is `SETTLEMENT_CLEARING` (its meaning narrowed to the card rail by this ADR, and
   carried into `AccountPurpose`'s javadoc by the review), and
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
     `settlement` (Phase 8). *(The Phase 7 → 8 transition's gate: a second, DIFFERENT network
     clearing of one capture is its own outcome, `SECOND_PRESENTMENT` - loud, counted
     unmappable, the first record standing - where it had been absorbed as the rail repeating
     itself. Its references rest in the retained evidence; the clearing-notice record with the
     cleared amount and date is Phase 8's, with its two evidence hops (ADR-0065).)*

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
  proof. The card rail's descriptor is written from what ADR-0049 §2 already decided. *(The
  review, `P7-DOC-001`: two card behaviours changed by later decisions, each recorded where it
  was made - a declined capture now releases the authorization by void (`P7-TSK-004`), and the
  refund bound counts standing chargebacks (ADR-0061 §3). The Phase 7 tasks were verified by
  targeted tiers on the owner's instruction; the review records that deviation.)*

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
  `P6-DOC-001` precedent. *(Done, `P7-DOC-001`: the passages above corrected with provenance;
  the withdrawal's completion found posting to a named clearing purpose rather than its rail's
  declared one - fixed, `WithdrawalOutcomes` resolving it from the withdrawal's stored rail, and
  held by `RailVocabularyIsConfinedTest`'s clearing-position rule; the money-semantics freeze
  ruled and guarded.)*
