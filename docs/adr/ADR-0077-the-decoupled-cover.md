# ADR-0077 — The decoupled cover: one back-to-back provider execution per accepted quote, never concluded from silence

Status: Accepted (2026-10-07, `P9-DOC-001` — read against the code and corrected first)
Date: 2026-10-02
Phase: 9
Context: FX · Ledger · Settlement · Reconciliation · App
Supersedes: nothing. Reuses ADR-0057's outbound dispatch discipline (permits, `UNKNOWN`,
knowledge-only conclusions, evidence) on the FX provider leg, with the send permit now stamped
by the database (`X-TSK-013` aligns the Phase 6/7 permits to the same rule). The customer side
it hedges is ADR-0076's; the provider boundary it crosses is ADR-0075's `FxProvider` port; its
callbacks follow ADR-0083. Treasury — netting, discretionary timing, position limits — stays
deferred (DELIVERY_PLAN §18).

## Context

ADR-0076 books the customer's conversion at acceptance, as principal, in one local transaction.
That leaves the platform holding `FX_POSITION`: source currency to be delivered, destination
currency to be received. The cover is the provider trade that closes that position — and it is
everything the customer leg is protected from (`INV-FX-09`): slow, duplicated, ambiguous, late
or unavailable. The forces:

1. **The position must close exactly.** The plan's position legs are the provider's stated
   amounts (ADR-0074 point 3), so a cover that honours the firm quote closes `FX_POSITION` to
   zero per currency. Anything the executed amounts differ by must become explicit P&L, never a
   residue in the position.
2. **A lost response is normal.** A send whose answer never arrives may or may not have
   executed. Concluding "never received" and re-covering risks a double cover the moment the
   first execution surfaces late — real money, twice. Phase 7's withdrawal discipline
   (ADR-0057) already solved this shape: no conclusion without knowledge.
3. **Ten instances dispatch.** Every sweep runs everywhere with no leader; exactly one provider
   execution per cover must survive ten dispatchers, a lost response, ten inquirers and a
   forged-but-signed callback.
4. **The lock can lapse.** The provider's firm quote has a validity the platform may overrun
   (a crash, a long outage). A definitive rejection then needs a *re-cover* at a fresh price —
   and that difference is the platform's realised result, never the customer's problem.
5. **A cover can become unwanted.** A cross-border payment can fail after its cover executed
   (ADR-0079), and an operator can reverse a trade (`P9-TSK-025`). The position those covers
   closed must reopen and re-close exactly, with the round trip's cost explained.

## Decision

1. **One cover of kind `COVER` per accepted quote, and one of kind `UNWIND` per quote whose
   subject was abandoned, or whose trade was reversed, after its cover executed** —
   `UNIQUE (fx.cover.quote_id, kind)`. **There is no netting, no discretionary timing and no
   position limit**: every accepted quote is covered back-to-back with the same provider whose
   firm quote priced it (D1, owner decision O1). Those refinements are treasury, deferred with
   their trigger recorded (DELIVERY_PLAN §18).
2. **The machine**: `DISPATCHED → EXECUTED | REJECTED | UNKNOWN`; `UNKNOWN → EXECUTED |
   REJECTED`; `REJECTED → DISPATCHED` (attempt n+1) `| VOIDED`. `EXECUTED` and `VOIDED` are
   terminal. **`DISPATCHED | UNKNOWN → VOIDED` is not an edge**, because a sent attempt may
   have executed — a cover leaves the board only through knowledge or through never having
   been attempted.
   - The cover is **born in the accepting transaction** (the conversion's, or cross-border
     authorization's Tx1; for an `UNWIND`, the abandonment or reversal writer's), with attempt
     1's client reference `T₁` minted and stored before any send (`UNIQUE NOT NULL`,
     `INV-PAY-04`) and the first send permit.
   - Attempts are rows in `fx.cover_attempt (cover_id, attempt) UNIQUE`, each with its own
     `client_reference` (`UNIQUE`) and `provider_quote_ref`.
3. **The send permit is stamped by the database.** `last_dispatched_at =
   statement_timestamp()` is committed before every send, by a conditional, strictly forward
   renewal held by a trigger — so ADR-0057 §4's premise (deadlines judged since the latest
   permit) survives instance clock skew. `X-TSK-013` aligns the Phase 6/7 send permits to the
   same rule.
4. **No conclusion without knowledge** (D13, `INV-FX-08`). On `UNRECOGNISED` or
   `INDETERMINATE`, `FxCoverSchedule` renews the permit and **re-sends the same `Tn`**. The
   provider contract — contract-tested against the simulator — is *dedupe on our reference
   before judging the quote's validity*, so a re-send after the lock lapsed returns the
   original execution if one happened. A new reference `T(n+1)` is minted **only** after a
   definitive `REJECTED` (`QUOTE_EXPIRED`, `PRICE_CHANGED`, `LIMIT`), and then only after the
   sweeper has obtained a **fresh firm quote** for the exposure's fixed leg from the same
   provider and it has passed the plausibility band (ADR-0075). An implausible requote leaves
   the cover `REJECTED`, retried with backoff and alerted. The cover is never concluded "never
   received".
5. **One provider execution, one money fact.** The acting applier inserts
   `fx.cover_execution (cover_id PK, attempt, provider_code, provider_trade_ref,
   UNIQUE (provider_code, provider_trade_ref), journal_entry_id UNIQUE, executed sold/bought,
   value date, realised result)` — *as built* (fx `V007`) also the attempt's `client_reference`
   (a foreign key to `cover_attempt`), the plan's legs beside the executed ones, the realised
   result as `realised_sold_minor`/`realised_bought_minor` held to the differences by `CHECK`,
   and `executed_off_plan`. That row is the arbiter: it holds with the acting conditional
   removed (a counted lock-bypass probe), beside the posting key `fx-cover:<coverId>`. Answer,
   inquiry and hinted inquiry resolve through one applier under the cover row's lock; the
   losers record nothing.
6. **The cover always closes exactly the plan's position legs.** The cover entry debits and
   credits `FX_POSITION` at the plan's stored legs and moves them onto
   `FX_PROVIDER_CLEARING(provider)` (ADR-0078's counterparty position). Any difference between
   the executed amounts and the plan's legs posts to `FX_REALISED_GAINS`/`LOSSES` in that
   leg's currency — a requote differs only in the computed leg; a provider deviating on the
   fixed leg is flagged `executed_off_plan`, counted and alerted. The leg expectations
   (`FX_SELL_LEG`, `FX_BUY_LEG`, keyed `COVER_REF`, ADR-0082) copy the **executed** clearing
   lines, so reconciliation compares the provider's evidence with what it confirmed, not with
   what was planned.
7. **Wanted position, not commands.** A quote wants a cover iff its status is `ACCEPTED` or
   `EXECUTED` *and* its trade is not `REVERSED`. Hence: `REJECTED` + wanted ⇒ requote;
   `REJECTED` + unwanted ⇒ `VOIDED`; `EXECUTED` + unwanted ⇒ create the `UNWIND`. Both the
   cover's applier and the abandonment or reversal writer evaluate this under the lock order
   *quote → trade → cover*, and `UNIQUE (quote_id, kind)` makes exactly one unwind, whichever
   writer moves first — a rule over state, so no command can be lost, duplicated or applied
   out of order.
8. **The unwind** replicates the cover's fixed leg in the opposite direction — it buys back
   exactly the amount sold — at a fresh firm quote from the same provider, through the same
   dispatch discipline (permit, `T`, dedupe, inquiry). Its entry closes the cover's position
   legs, and the P&L lands in the other currency. The customer is untouched: an abandoned
   payment's wallet delta is zero (`INV-XB-01`'s failure half).
9. **Callbacks are hints** (D25, ADR-0083). A verified FX callback is authenticated, retained
   as evidence, deduped through the inbox, and triggers an immediate `inquire(T)`. **Only the
   inquiry's answer moves the cover** — a forged-but-signed callback moves nothing, proven by
   a planted test.

## Alternatives Considered

### Cover first, then book the customer
Pros: no open position between booking and cover.
Cons: the customer waits on the provider, and a provider `UNKNOWN` reaches a customer balance —
`INV-FX-09` violated by design. The open position is the platform's risk, taken knowingly,
bounded by the quote window and explained by the books proof (ADR-0076).

### Conclude `NEVER_RECEIVED` after a deadline, then re-cover
Pros: covers never linger `UNKNOWN`.
Cons: a late first execution after the re-cover is a **double cover** — the platform holds
twice the position it closed, and unwinding it costs a third trade. The provider's
dedupe-on-`T` contract makes re-sending the same reference strictly safer than concluding from
silence. Rejected as the accounting design's defect (D13).

### Requote by market order after a rejection
Pros: the re-cover always fills.
Cons: an unbounded price on real money, with no band and no firm quote to verify coherence
against (ADR-0074 point 3 would have no stated counter). A fresh *firm* quote that must pass
the band keeps the re-cover inside the same controls as the original. Rejected as the
distributed design's choice.

### Net covers, or batch them on a timer
Pros: fewer provider trades, tighter spreads at volume.
Cons: the position stops being explainable trade by trade (`INV-FX-06`'s identity is per
quote), a netting engine is a treasury function with its own risk mandate, and a timer is
discretionary timing — a trading decision. All three are deliberately out (DELIVERY_PLAN §18);
the trigger for revisiting is recorded there, not here.

### Allow `DISPATCHED | UNKNOWN → VOIDED`
Pros: an unwanted cover dies immediately.
Cons: a sent attempt may have executed; voiding it loses a real execution the inquiry would
have found, leaving the position silently open. Unwanted-ness waits for knowledge: `REJECTED`
voids, `EXECUTED` unwinds (point 7).

## Consequences

Positive:
- Exactly one provider execution per cover under ten dispatchers, a lost response, duplicated
  callbacks and a lapsed lock — held by the execution PK and the posting key even with the
  Java guard removed.
- `FX_POSITION` closes exactly; every deviation from the plan is one explicit realised-P&L
  line in a named currency, and an off-plan fixed leg is loud.
- A stolen webhook key moves no money (hints + inquiry), and a provider that stops answering
  leaves covers visibly `UNKNOWN` with ageing gauges, never wrongly concluded.
- Abandonment and reversal converge on exactly one unwind through state, not through a command
  channel that could duplicate or drop.

Negative:
- Between acceptance and cover execution the platform carries open rate risk on its own book —
  the principal model's cost, bounded by the quote window minus the cover margin (ADR-0075)
  and visible in `finapp.fx.cover.open.age`.
- A definitively rejected cover re-prices at the market: the difference lands in
  `FX_REALISED_GAINS/LOSSES`. That is honest — the alternative designs hid it in the customer
  rate or the position.
- One more sweeper, one more webhook door and one more permit discipline to operate; mitigated
  by reusing ADR-0057's shapes verbatim and the Phase 8 observability patterns.

Operational impact: `finapp.fx.cover{provider, type, outcome}` — the kind under the registered
`type` key; outcomes `executed`, `off_plan`, `rejected`, `unknown`, `requoted`,
`requote_refused`, `voided`, `anomaly` — `finapp.fx.cover.unknown.active`/`.unknown.age` (the
`INV-LIFE-03` alert for covers), `finapp.fx.cover.open.age` (oldest uncovered position leg,
alerting), `finapp.fx.cover.latency{provider, type}` (birth → executed),
`finapp.fx.cover.sweeper.enabled`. *(Corrected 2026-10-07, `P9-DOC-001`: the tag read `kind`
and three outcomes were missing.)* Counts, ages and verdicts only (ADR-0072); the realised
amounts are the revenue report's.
Security impact: the webhook door authenticates (HMAC, freshness), stores evidence first,
dedupes through the inbox, and adopts outcomes only from an authenticated inquiry (ADR-0083); a
forged-but-signed callback is a counted no-op. Client references and provider trade references
are CONFIDENTIAL evidence; amounts never enter a metric or log. Cover acts are platform acts,
audited acting-only.
Financial impact: the cover entry moves the position onto the provider's counterparty clearing
at the plan's legs; slippage and unwind round trips post as realised results in their leg's
currency; the customer's entry is never touched by any cover outcome (`INV-FX-09`). Settlement
then discharges the counterparty position in two evidence hops (ADR-0065, ADR-0082), cash
moving only on the bank's statement (`INV-SET-06`).

## Invariants / Constraints

`INV-FX-08` (new: a reference stored before sending; re-sent only under it; a new reference
only after a definitive rejection; a cover closes exactly its plan's position legs, the
difference posted as realised FX result), `INV-FX-06` (the closing identity, ADR-0076),
`INV-FX-09` (no cover outcome reaches a customer line), `INV-PAY-04` (the reference minted and
stored before any send), `INV-LIFE-03` (no silent `UNKNOWN`: aged, alerted, resolved only by
knowledge), `INV-SET-02` (the executed cover opens its leg expectations in the outcome
transaction, ADR-0067's co-commit rule via `FxSettlementExpectations`), `INV-IDEM-02` (the
execution fact and posting key hold under every duplicate path), ADR-0046 (no transaction
spans the provider call: permit transaction, wire, outcome transaction).

## Follow-up

- `P9-TSK-009`: the cover row born `DISPATCHED` with `T₁` and the permit in the conversion's
  accepting transaction; a post-commit nudge to the dispatcher.
- **Built by `P9-TSK-012` (2026-10-05)** - as decided above, with these settled at build: a cover leg's reconciliation key is qualified by the leg's currency at both sides (reconciliation's `CoverLegKey`, applied by its own `NewExpectation` and `ExternalItems.NewItem`) so a cover's two legs, sharing `T`, hold two keys under one source's unique; the cover row carries `caused_by_event_id` (the acceptance) and `requote_failures` (the refused-requote backoff, `requote-base x 2^min(n, 6)`); a targeted `FxCoverDispatch.dispatchNow` claims any open cover, the post-commit nudge one of them; the meter's kind rides the registered `type` tag key; an execution in other currencies than the plan's is not knowledge (`DISPATCHED -> UNKNOWN`, counted `anomaly`), and one for a superseded attempt is evidence only. The legs' FX source must have its v1 ACTIVE (runbook section 3) or the outcome transaction refuses - fail closed, the cover re-sent until it can book.
- `P9-TSK-012`: `FxCoverSchedule`, `FxCoverOutcomes` (answer, inquiry, hinted inquiry through
  one class), the permit trigger, re-send and requote, `fx V007` (`cover_execution`, the
  realised result), ledger `V023` (`FX_REALISED_GAINS`/`LOSSES`), the cover entry, the leg
  expectations, the FX webhook door — scenarios 3, 4 and 6, `PHASE_9_PLAN.md` §12.4(b)/(f) posted exactly, the
  lock-bypass probe on `cover_execution`.
- `P9-TSK-019`: the cross-border cover born in authorization's Tx1 and sent post-commit,
  cover first, before the corridor instruction (ADR-0079).
- `P9-TSK-020`: abandonment on the outbound credit's `FAILED`, feeding the wanted-position
  rule.
- `P9-TSK-021`: `UNWIND` creation under the lock order and `UNIQUE (quote_id, kind)`, both
  race orders, `PHASE_9_PLAN.md` §12.4(h) posted exactly.
- `P9-TSK-025` (owner decision O8: cut second): the trade reversal as the second unwind
  trigger; if cut, scenario 8's unwind half is carried by `UnwindRetryDatabaseTest` (`-021`).
- `X-TSK-013`: the Phase 5–7 outbound send permits database-stamped to this ADR's rule.
- `P9-TST-001`: the storm's two skewed application contexts and ten movers on one cover; the
  simulator's execution count equal to the execution facts under every fault.
- Built by `P9-TSK-009`, `-012`, `-019`, `-020`, `-021`, `-025`, `X-TSK-013` and `P9-TST-001`.
  *(This read "Until `P9-TSK-009` lands, nothing in this ADR is implemented" until `P9-DOC-001`.)*
- **Acceptance.** The Phase 9 review (`P9-DOC-001`) read this ADR against the code before
  accepting it on 2026-10-07, following the `P8-DOC-001` precedent. It completed point 5's
  execution-fact columns and corrected the meters' tag and outcomes; the unwind's deviation
  from decision 2 stands as recorded below (fx `V008`, the unwind; fx `V009`, the trade
  reversal that is its second trigger).
- *As built by `P9-TSK-021` (2026-10-06):* decisions 7 and 8 - `CoverUnwinds` evaluates the wanted position for
  the abandonment writer (every call, under the quote's row lock) and the cover's applier (executing a cover its
  quote no longer wants); `UNIQUE (quote_id, kind)` is the arbiter through an unconditional `ON CONFLICT DO
  NOTHING`. The unwind is the executed cover's mirror - fx `V008` holds it for every writer - and closes the
  quote's plan reversed, so `CoverLines` posts section 12.4(h) unchanged and the FX books proof needs no change.
  **Deviation from decision 2:** an unwind is born in its writer's transaction, which cannot call the provider, so
  it is born without an attempt row: its first dispatch obtains the fresh firm quote, judges it by the band and
  stores attempt 1 and `T1` - before any send - then sends; an implausible price leaves it waiting, alerted. A
  requote of an unwind is always wanted; the requote's band terms are the quote's own pair under its purpose.
