# ADR-0079 — A cross-border payment holds the customer's funds until the corridor provider accepts, posts once, and comes back only as exactly what was sent — or through a person

Status: Accepted (2026-10-07, `P9-DOC-001` — read against the code and corrected first)
Date: 2026-10-02
Phase: 9
Context: Cross-Border · Payments · FX · Ledger · Reconciliation
Supersedes: nothing. Reuses ADR-0057's outbound dispatch discipline (send permit, `UNKNOWN`,
resolution by query, evidence) on a new rail and a new aggregate. Extends ADR-0073's
payout-return precedent to the corridor. Evaluates ADR-0062 §7's **first** convergence trigger —
a second outbound rail — which the corridor rail fires, and **declines convergence with
reasons** (§9 below), re-recording the trigger. Fires DECISIONS' deferred "recall requests —
until a rail that needs them" row (D22). Settles owner decisions O2 (returns) and O9 (charge
bearer, `OUR` only).

## Context

A cross-border payment is the customer's instruction to deliver value abroad, at a disclosed
price: 1,000.00 EUR leaves a wallet and 1,079.60 USD reaches a beneficiary's account in the
United States, through a corridor provider the platform pays on a clearing position. It
combines everything the platform has learned to keep apart:

1. **An FX conversion at a locked rate.** The price is an accepted `fx` quote — a frozen
   posting plan (ADR-0074, ADR-0076) — and the platform covers it back-to-back with the FX
   provider (ADR-0077). The customer's rate must never move after acceptance (`INV-XB-03`).
2. **A provider that can accept, decline, lose our response, sit undecided on its own
   screening, deliver, or send the money back days later.** This is ADR-0046's and ADR-0057's
   territory: no transaction spans a provider call, `UNKNOWN` is a modelled state, and
   resolution is by inquiry.
3. **Money that must not be debited for a payment that did not happen.** Phase 6 decided this
   for payouts with the hold-then-dispatch model (ADR-0051, ADR-0057): no customer reversal
   entries, quieter statements, no recognise-then-reverse revenue.
4. **A return channel.** What the provider accepted, the beneficiary's bank can still send
   back — exactly, partially, or in a shape nobody instructed. ADR-0073 decided for payouts
   that the routine case applies automatically from evidence and everything else goes to a
   person.
5. **Cancellation.** Brief 27 asks for it, and an irrevocable rail only offers it as a
   *request* the provider may refuse.
6. **Ten instances**, duplicated callbacks, duplicated settlement lines, racing retries and
   crashed flights, on every one of these paths.

The registered name is **Cross-Border Payment** (D31): in this codebase *Transfer* is the
`transfers` module's internal book movement, and the distinction is load-bearing
(`MODULE_ARCHITECTURE` registers both).

## Decision

1. **`crossborder` decides, `fx` prices and books, `payments` executes, `kyc` screens — and no
   business module depends on a sibling** (D15). Two new modules, `fx` and `crossborder`, each
   build-depend on exactly `{ledger, platform, sharedkernel}`. There is **no build edge**
   between `fx`, `crossborder`, `payments`, `kyc` and `accounts`; every seam is a port the
   caller declares and `app` implements as a required constructor parameter (ADR-0064 §3's
   discipline): `CrossBorderFx` (the quote and its acceptance: `begin`, `firmQuote`, `issue`,
   `read`, `acceptWithin`, `dispatchCover`), `CrossBorderExecution` (routing and dispatch),
   `CorridorDirectory`, `CounterpartyScreening` (ADR-0081), `CrossBorderParticipants`, and
   `payments`' `OutboundCreditComposition` implemented over `crossborder` and `fx` (the
   `CaptureComposition` shape) — the completion's lines and the abandonment go through it, to
   `app`'s `CrossBorderCompletion` and fx's `CrossBorderCompletionBooking` (`book`, `abandon`).
   *(As built (`P9-DOC-001`): no `CrossBorderParticipants` port was built — a return opens its
   wallet through fx's `ConversionParticipants` inside `app`'s `CrossBorderCompletion`.)* The named
   cross-module transactions — authorization (T-b), outbound outcome (T-c), screening decision
   (T-e), return (T-f), resolved parked return (T-g) — are each justified in `PHASE_9_PLAN.md`
   §3's table, and no reconciliation worker ever waits on an `fx`, `crossborder`, `payments` or
   `kyc` lock.

2. **The customer's money waits under a hold, and one entry posts at provider acceptance**
   (D14, `INV-XB-01`). Authorization (Tx1) locks the quote and judges it `ISSUED`, unexpired on
   the database clock, owned and `CROSS_BORDER`; checks the beneficiary payable `FOR SHARE`
   (`INV-XB-02`, ADR-0081); routes; consults the Phase 13 limit and risk seams in-lock; places
   the hold for the total debit (principal + transfer fee); accepts the quote (the port's
   `acceptWithin` takes **no idempotency claim of its own** — the route's claim covers the
   transaction); births the payment `SUBMITTED`, the outbound credit `DISPATCHED` with `E`
   minted and the first database-stamped permit, and the cover `DISPATCHED` with `T₁`; and
   commits once. On the provider's acceptance, one transaction releases the hold and posts the
   one completion entry `outbound-credit:<id>`: the wallet debit (principal + fee),
   `FEE_REVENUE`, the conversion's `FX_POSITION` legs, `FX_SPREAD_REVENUE`, the bounded
   `ROUNDING_RESIDUAL`, and the credit to `CORRIDOR_CLEARING` of the corridor rail's
   counterparty — the frozen plan's figures exactly, never re-priced (`INV-XB-03`,
   ADR-0074/ADR-0076). The trade is booked (`UNIQUE (fx.trade.quote_id)`), the quote moves
   `ACCEPTED → EXECUTED`, the payment `SUBMITTED → IN_TRANSIT`, and the `CROSSBORDER_PAYOUT`
   expectation opens in the same transaction (ADR-0067, ADR-0082). **A payment that fails
   debits the customer nothing**: the hold is released, the quote `ABANDONED`, the cover
   unwound if it executed (ADR-0077), and no reversal entry ever appears on the customer's
   statement. *(As built (`P9-DOC-001`), Tx1's order is `PaymentAuthorization`'s: the offer
   owned; the beneficiary `FOR SHARE` (`ACTIVE`, clear, the clearance not lapsed on the database
   clock); the corridor available; risk, then limit; fx's `acceptWithin` (the quote locked, judged
   and accepted, the cover born with `T₁`); routing; the hold and the credit born `DISPATCHED`;
   the payment `SUBMITTED`, audited and announced — one commit, every refusal rolling it back.)*

3. **The Outbound Credit is a new `payments` aggregate carrying the provider's ambiguity**
   (D16): ADR-0057's four states plus `RECEIVED` — the provider acknowledged the instruction
   with its reference but has not committed, its own screening pending. Edges:
   `DISPATCHED → RECEIVED | COMPLETED | FAILED | UNKNOWN`;
   `UNKNOWN → RECEIVED | COMPLETED | FAILED`; `RECEIVED → COMPLETED | FAILED`; `COMPLETED` and
   `FAILED` terminal. It reuses the machinery whose defects three gates repaired: the
   database-stamped send permit, `UNKNOWN` resolved by inquiry only (`INV-LIFE-03`), the
   leaderless resolution sweep, and evidence retained verbatim. Rules: `dispatch_key` unique
   per customer; `last_dispatched_at` database-stamped and strictly forward; `NOTHING_SENT`
   fails only a first send; `NEVER_RECEIVED` requires `DISPATCHED` or `UNKNOWN`, re-judged on
   the locked row past the rail's declared `outcomeDeadline` plus margin since the **latest**
   permit, that bound read from the database clock in the transaction that locked the row
   (`OutboundCreditResolution.neverReceivedBound`, `DatabaseTime.now` — the recall's
   `UNRECOGNISED` judged by the same bound; `P9-DOC-001` found it on the resolver's clock, held
   by `OutboundCreditResolutionDatabaseTest#aSkewedResolverConcludesNothingEarly`) — and
   `RECEIVED` can never become `NEVER_RECEIVED`; an instruction with a recall requested is
   **never re-sent**. The sweep takes every credit awaiting its outcome first, oldest permit
   first, and only then the `COMPLETED` credits still awaiting delivery, least recently
   inquired first (`JdbcOutboundCreditStore.findDue`; `P9-TST-001`'s starvation find, held by
   `#undeliveredCreditsNeverStarveOneAwaitingItsOutcome`). `E`, rail, destination reference, instructed and held
   `Money`, hold id and subject are frozen by trigger. The credit publishes no event of its
   own: its business consequence is the payment's event, written in the same transaction.

4. **The payment machine is small, and every state has a producer** (D21):
   `SUBMITTED → IN_TRANSIT | FAILED`; `IN_TRANSIT → DELIVERED | RETURNED`;
   `DELIVERED → RETURNED`. `DELIVERED` keeps one edge — a return is the receiving side's act
   and is admitted whenever it arrives, because the external fact comes first (the ADR-0073
   rule); the corridor's declared return window governs alert ageing only. `UNKNOWN` and
   `RECEIVED` belong to the outbound credit, never to the payment; the customer sees
   `PROCESSING` for all of `SUBMITTED`'s internal shades, and `CANCELLED` for
   `FAILED(RECALLED)`. The brief's candidate states are disposed one by one: `INITIATED` and
   `VALIDATED` hold no committed row (ADR-0044, states are earned); `COMPLIANCE_REVIEW` cannot
   sit behind a 60 s rate lock, so screening holds the *beneficiary*, before pricing
   (ADR-0081); `SETTLED` is reconciliation's expectation status, never a payment state
   (`INV-SET-01`); `REVERSED` cannot exist (`INV-REV-03` — a credit the provider accepted
   cannot be reversed by us). The edges are held by a generated `CHECK`, an every-writer
   trigger, the domain and `payment_event` history. When one inquiry answer reports several
   facts at once — accepted and delivered, or accepted and returned — the payment takes each
   edge **in order inside one transaction**, one history row and one event per edge.

5. **Cancellation is a recall request, honoured only on the provider's word** (D22). Before
   acceptance, cancelling means cancelling the offer (the quote). After authorization,
   `crossborder.cancellation_request` is a **born-once fact, not a machine**
   (`UNIQUE (payment_id)`, append-only by trigger): it marks the credit so that no re-send ever
   follows, and `CorridorRail.recall(E)` is asked. Only the provider's definitive `RECALLED`
   concludes `FAILED(RECALLED)`; `TooLate` concludes nothing. Knowledge-only conclusions hold:
   a recall is never assumed. This fires DECISIONS' deferred row recorded by ADR-0062's
   follow-up — the corridor is the rail that needs recall requests.

6. **A return is applied automatically only when it is exactly the instructed credit coming
   back** (D23, O2, `INV-XB-04`). Two channels converge on one fact: the provider's inquiry
   answer (`Returned{returnRef, amount, at}`, hinted by a callback under ADR-0083 or found by
   the sweep) and the settlement report's `PAYOUT_RETURNED` line, read by `OutboundReturnWorker`
   in `app` (the `PayoutReturnSweep` precedent) from reconciliation's waiting items, **scoped to
   the corridor sources** (ADR-0082): the corridor worker is handed only sources settling
   `CORRIDOR_CLEARING`, the merchant sweep only those settling `PAYOUT_CLEARING`, so identical
   provider references across the two source families can never credit the wrong party. Both
   channels sit behind `UNIQUE (outbound_credit_return.outbound_credit_id)`
   (`outbound_credit_return_once`, judged under the credit's row lock) and the posting key
   `crossborder-return:<outboundCreditId>` *(as built, no `CROSSBORDER_RETURN` claim on
   `(rail, return_reference)` exists: the report line carries no return reference — `P9-TSK-023`'s
   recorded deviation)*. The rule, judged on the locked rows:
   - **`COMPLETED`, the instructed currency `D`, exactly the instructed amount, customer
     `ACTIVE`** → applied: the wallet in `D` is credited (opened if absent, in the same
     transaction, D28), **never re-converted at the original rate**; the transfer fee is
     refunded in `S`; the spread stands, because the conversion was performed; the payment
     moves to `RETURNED`.
   - **The completion not yet known** (`DISPATCHED`, `UNKNOWN`, `RECEIVED`) → the worker
     defers, writing nothing; the sweep resolves the credit first (point 7).
   - **Anything else** — a partial return, more than was sent, a different declared currency,
     a customer no longer `ACTIVE` — is **never posted automatically**: at grace the item
     parks as `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)`, HIGH, owned by its break
     (ADR-0073 §5's precedent). A partial return credited automatically would leave our
     posting agreeing with the provider's line, so no break could ever surface the shortfall,
     and the customer would silently bear an intermediary's deduction under a promise of
     `OUR`. A person sees every such case.
   - **A `FAILED` credit's return** is a contradiction, parked at once as
     `REVERSAL_MISMATCH(TERMINAL_STATE_CONTRADICTED)` beside the late execution's own
     `TERMINAL_STATE_CONTRADICTED` (the withdrawal's precedent, `P9-TSK-022`'s recorded deviation
     from `UNKNOWN_EXTERNAL`); a person offsets the two.

   **The way out of a parked corridor return is a person's four-eyes resolution that also
   records the return** (T-g). The usual decision is a `TRANSFER_TO_ACCOUNT` (ADR-0071) of the
   parked value to the customer's wallet in the item's currency. When the break's item is a
   corridor `PAYOUT_RETURNED` line whose operation is an outbound credit, the approval also
   calls **`ResolvedCorridorReturns`** (declared by reconciliation, implemented in `app` over
   `payments` and `crossborder`, the `SettlementBatchRepudiations` precedent) in the same
   transaction, after its own rows: it inserts the born-once return fact with
   `applied_by = RESOLUTION` and the resolution id, posts the fee refund
   `crossborder-return-fee:<outboundCreditId>`, and moves the payment to `RETURNED`. If an
   inquiry applied the return first, the port finds the fact under the credit's lock (as
   built, rather than by an insert conflict), the whole approval rolls
   back (`409 reconciliation.ResolutionStale`) and the rematch leg closes the break
   `EVIDENCED`; if the approval wins, the automated application finds the fact and writes
   nothing. Nothing is ever credited twice. The port refuses while the credit is not
   `COMPLETED`, and a credit that ends `FAILED` leaves the parked line to a four-eyes
   `OFFSET_SUSPENSE`, never a customer credit. A closed customer's value stays parked with its
   HIGH break, aged and escalated — recorded as debt with Phase 15 as owner.

7. **An answer that implies acceptance is applied in order on a credit not yet `COMPLETED`**
   (`FX_AND_CROSS_BORDER_LIFECYCLES.md` §3.6). The provider can execute, lose its response, and then deliver or see
   the money returned before our sweep inquires. An inquiry answering `Accepted` with
   `deliveredAt`, or `Returned`, while the credit is `DISPATCHED`, `UNKNOWN` or `RECEIVED`, is
   applied by `OutboundCreditOutcomes` in **one transaction, in order**: first the completion
   (point 2's whole effect), then the delivery if the answer carries it, then an applicable
   return (point 6). No new outbound-credit edge is needed — a `Returned` answer proves the
   provider accepted — and a return that is not applicable is not applied while the completion
   and delivery still are. Losers of the acting conditional record nothing.

8. **The charge bearer is `OUR` only** (O9): the beneficiary receives the quoted destination
   amount, which is what makes the return and break rules decidable. `SHA`/`BEN` and
   correspondent-chain deductions are deferred, triggered by a corridor whose provider cannot
   guarantee the delivered amount; under `OUR`, a deduction from principal is an
   `AMOUNT_MISMATCH` break, never a silent short delivery.

9. **ADR-0062 §7's convergence trigger is fired by the corridor rail, and convergence is
   declined, with reasons.** The deferred "moving the merchant payout onto the push rail" row
   kept one live trigger after ADR-0073 evaluated the other: *a second outbound rail*. The
   corridor rail is one, so the trigger fires and must be answered. The answer is **no**:
   - the merchant payout's settlement shape is already canonical — ADR-0073 §8 showed that
     every outbound credit transfer has one evidence shape at normalization, which was the
     convergence's whole premise;
   - the payout's port carries a **single-currency merchant flow** that Phase 9 does not
     touch: the corridor rail prices in a corridor, screens a beneficiary and books FX, none
     of which a merchant payout has or wants;
   - converging would re-declare the payout's position and source and migrate a Phase 6 money
     path in a phase that is already the largest since Phase 8.

   The trigger is **re-recorded** as: *a merchant payout in a currency other than the
   settlement currency, or on a rail other than `PayoutProvider`*. Until then, two outbound
   disciplines coexist, which is ADR-0062's recorded negative, now three times affirmed.

10. **Why none of the forbidden outcomes can happen, under ten instances.** Executed twice:
    the provider dedupes on `E`, no new `E` is ever minted for a payment, the permit rule
    prevents a conclusion racing a re-send, and the payment is never re-routed (one reachable
    candidate, ADR-0080). FX applied twice: `UNIQUE (fx.trade.quote_id)` and the quote's
    single `ACCEPTED → EXECUTED` edge. Fees charged twice: the fee line exists only in the one
    completion entry — posting key, acting conditional, claim PK. Provider reference lost: `E`
    is ours and stored before the send; the provider's reference arrives on any of three
    channels, is stored once by conditional, and can be obtained again by `inquire(E)`.
    Success treated as failure: `INDETERMINATE` is `UNKNOWN`, never `FAILED`;
    `NEVER_RECEIVED` needs the declared deadline past the latest permit. Returned twice: the
    return fact's unique, the claim, and T-g's conflict rule (point 6). Each is a counted test
    in the building tasks, and `P9-TST-001` races two application contexts with skewed clocks
    over the whole chain.

## Alternatives Considered

### Debit at funding, with an in-transit ring-fence and a reversal on failure
Pros:
- The customer's statement shows the debit at the moment of commitment.
- No hold machinery on the wallet.

Cons:
- A failed payment needs a customer-visible reversal entry — the recognise-then-reverse shape
  ADR-0057 rejected for payouts.
- The in-transit account is a new position with no counterparty evidence to discharge it.
- A crash between debit and dispatch strands debited money with no instruction.

The ADR-0057 hold discipline is reused instead (D14).

### A third outbound discipline inside `crossborder`
Pros:
- The payment and its instruction live in one module.

Cons:
- It duplicates the permit, `UNKNOWN`, sweep and evidence machinery whose defects three gates
  repaired, and every repair would need making three times.
- `crossborder` would need provider ports and routing, which are `payments`' (ADR-0060).
- `RECEIVED` and the recall belong beside the other dispatch states, where their rules
  (`NEVER_RECEIVED`, never re-send) are enforced by the same triggers.

The Outbound Credit is `payments`' aggregate (D16).

### Re-convert a return at a fresh rate, or credit a partial return automatically
Pros:
- The customer's money comes back in their source currency without a person.

Cons:
- A fresh rate is a conversion the customer never accepted — a system-accepted quote with no
  owner (`INV-FX-04` refuses it).
- A partial return credited automatically leaves no break to surface the shortfall: our
  posting and the provider's line agree, and the customer silently bears a deduction under a
  promise of `OUR`.
- A return in another currency credited "helpfully" is reconciliation converting, which D29
  refuses platform-wide.

The return is credited in the instructed currency, exactly, or a person decides (D23).

### A `CANCELLED` payment state with platform authority
Pros:
- The customer's cancellation looks immediate.

Cons:
- On a `FINAL_ON_ACCEPTANCE` rail the platform has no such authority; the provider may refuse,
  days later, or execute anyway.
- A state concluded without provider knowledge is the class of defect `INV-LIFE-03` exists to
  prevent.

A cancellation is a recall request, and a confirmed recall is `FAILED(RECALLED)` (D22).

### Converge the merchant payout onto the corridor/push rail now (ADR-0062 §7)
Pros:
- One outbound discipline, one dispatch port, the trigger is literally fired.

Cons:
- The premise is already met: the canonical settlement line gives every outbound credit
  transfer one evidence shape (ADR-0073 §8).
- It re-declares the payout's position and source and migrates a Phase 6 money path during the
  phase that builds FX.
- The payout is a single-currency merchant flow; nothing cross-border applies to it.

Declined, with the trigger re-recorded (point 9).

## Consequences

Positive:
- The customer is never debited for a payment that did not happen, never meets a rate they did
  not accept, and never meets a reversal entry. What was shown is what is held, posted and
  instructed (`INV-XB-03`).
- The provider's ambiguity has one owner (the outbound credit) and one resolution path
  (inquiry), and a response lost after execution converges on the same completion as a clean
  acceptance.
- Every return that differs from what was sent is judged by a person, with the value owned by
  a break — and the routine exact return needs nobody.
- ADR-0062 §7 is finally closed on both triggers, each evaluated with reasons, and the
  deferred recall row is paid by the rail that needed it.

Negative:
- A customer's funds can wait under a hold for the corridor's full decision window (the
  declared `decisionDeadline`, 4 h simulated) before `IN_TRANSIT` or release; the
  `finapp.payments.outbound.received.age` gauge alerts past the declared deadline.
- A recall honoured late, or refused, leaves the customer with a payment they asked to cancel
  and could not; the cancellation-rate meter watches the free-option edge.
- A parked return resolved by a person leaves the payment's return readable through the break
  and its resolution (plus the recorded return fact), not through an automated entry.
- Funds owed to a closed customer by a parked corridor return rest in owned suspense, aged
  and alerting, until Phase 15 writes the operating procedure (recorded debt).

Operational impact: `finapp.crossborder.payment` (counter by corridor and outcome), payment
latency by stage, in-transit age, `finapp.payments.outbound.unknown.*` and `.received.age`
gauges, the cancellation and return counters, and the trace
`GET …/cross-border/payments/{id}/trace` walking customer → quote → offer → payment → credit →
entry → expectation → line → batch → bank line → allocation by identifiers alone. No series
carries an amount (ADR-0072).
Security impact: authorization and cancellation take the conditional step-up; ownership is
checked in the domain with uniform `404`s; the beneficiary's name never enters `crossborder`
or `payments` storage (ADR-0081); callbacks are hints (ADR-0083); audit records are written in
the act's own transaction, acting-only for platform acts.
Financial impact: one new liability position per corridor counterparty (`CORRIDOR_CLEARING`,
ADR-0078), the completion entry's shape fixed by the frozen plan, the transfer fee earned at
completion and refunded on return, and the conversion's books explained by the FX proofs
(ADR-0076).

## Invariants / Constraints

`INV-XB-01` (hold, one entry at acceptance, a failed payment debits nothing), `INV-XB-02`
(payability judged in-lock at quote and authorization), `INV-XB-03` (disclosure equals hold
equals posting equals instruction), `INV-XB-04` (a return applied once, exactly, or by a
person), `INV-FX-04` (the frozen plan; the trade's unique), `INV-FX-09` (the booked conversion
never waits on a provider), `INV-RAIL-02` (routed once, pinned, explainable), `INV-REV-03` (an
accepted credit is never reversed by us), `INV-SET-01` (settlement is never a payment state),
`INV-LIFE-03` (`UNKNOWN` resolved only by knowledge), `INV-LIFE-04` (terminal means terminal;
a return is a new fact), `INV-IDEM-01`/`-02`/`-04` (the claim, the uniques, duplicate
deliveries harmless), `INV-CON-01`/`-02` (every judgement on locked rows), `INV-AUD-01`
(audit in the act's transaction), `INV-PAY-03` (provider vocabulary confined),
`INV-PAY-04` (amended: the FX provider's `T` and the corridor's `E` and recall).

## Follow-up

- `P9-TSK-019` builds Tx1 (authorization, hold, dispatch, the routing subject), `P9-TSK-020`
  the resolution and completion (T-c, the multi-fact answer in order), `P9-TSK-023` the
  returns (both channels, the worker's scoping, T-f and T-g, `ResolvedCorridorReturns`),
  `P9-TSK-024` the cancellation by recall (the born-once request; the never-re-send rule).
- `P9-TSK-022` settles the corridor to cash; ADR-0082 holds the reconciliation half.
- `P9-TST-001` races two application contexts with skewed clocks plus ten movers over the
  whole chain; the ten mandatory scenarios include the ambiguous dispatch, the late `Returned`
  answer and the recall race.
- The transition annotates ADR-0057 (discipline reused), ADR-0062 §7 (trigger fired, declined,
  re-recorded), ADR-0073 (precedent extended) and DECISIONS' recall and convergence rows.
- Recorded debt: funds owed to a closed customer by a parked corridor return (Phase 15). The
  Phase 5–7 permits' instance stamps (`X-TSK-013`) are **paid** (2026-10-07): every send permit
  is the database's (payments `V028`, merchant `V009`), held by `SendPermitsAreTheDatabasesTest`.
- `SHA`/`BEN` charge bearers wait for a corridor whose provider cannot guarantee the delivered
  amount (O9).
- **As built** (2026-10-07, read against the code by `P9-DOC-001`): every point of this ADR is
  implemented by the tasks below, all `COMPLETE`, and every statement above is true of the code.
  The review's corrections: point 1's ports (`CrossBorderFx`'s scope; no `CrossBorderParticipants`),
  point 2's Tx1 order, point 3's `NEVER_RECEIVED` bound on the database clock (fixed in code by the
  review) and the sweep's candidate order (`P9-TST-001`), point 6's arbiter (no return claim) and
  the late execution's cause, and `X-TSK-013` paid.
- The Phase 9 review (`P9-DOC-001`) read this ADR against the code, corrected it where it had
  drifted, and accepted it on 2026-10-07.
- *As built by `P9-TSK-018` (2026-10-06):* the cross-border offer - crossborder `V004`'s `offer_request` (the
  claim, the pinned corridor version, the re-screen) and `payment_offer` (fx's `CROSS_BORDER` quote beside the
  corridor fee computed once on the customer's source amount, the total debit `CHECK`-held as source plus fee,
  the guaranteed destination and the estimate), recorded in one transaction with fx's quote through the
  `CrossBorderFx` port; the corridor maximum enforced on the destination; a lapsed clearance re-screened
  between the transactions; `POST /v1/me/cross-border/quotes` and `GET .../{id}`. Nothing is held.
- *As built by `P9-TSK-019` (2026-10-06):* the authorization and dispatch - crossborder `V005`'s `payment` (one
  per quote, per offer, per outbound credit and per dispatch key; its machine held by an edge trigger) and
  `payment_event`; in ONE transaction the beneficiary re-judged `FOR SHARE` (`ACTIVE`, clear, the clearance
  within the corridor's validity - else `ScreeningRequired`), the corridor available, the Phase 13 seams
  (`PermitAllUntilPhase13`), fx's quote accepted with its cover born and the accepted amounts asserted equal
  to the offer (`INV-XB-03`), routing's third subject, the hold of the total debit, the outbound credit born
  `DISPATCHED` with our end-to-end reference, the audit record and `crossborder.CrossBorderPaymentInitiated`;
  then the cover dispatched and the credit sent with no connection held, the answer recorded in a second
  transaction. A refusal rolls back to a savepoint, so nothing is accepted, held or sent.
  `POST /v1/me/cross-border/payments` (`202`) and `GET .../{id}`. **Deviation:** an `ACCEPTED`, `REJECTED` or
  `NOTHING_SENT` send answer is retained as evidence only - concluding it (the completion, the failure, the
  hold's release) is `P9-TSK-020`'s outcome appliers'.
- *As built by `P9-TSK-020` (2026-10-06):* points 2 and 7 - the completion is one transaction in the plan's
  order (the acting exit, the claim, the hold released, the entry `outbound-credit:<id>`, the trade booked, the
  quote `EXECUTED`, the payment `IN_TRANSIT`, the `CROSSBORDER_PAYOUT` expectation), its lines section 12.4(g)'s
  and checked against the offer, the hold and the instruction before posting (`INV-XB-03`); an answer implying
  acceptance with a delivery is applied completion-then-delivery in that transaction; a failure posts nothing,
  releases the hold and abandons the quote. **Deviations:** a callback naming no credit of ours is acknowledged
  and recorded by its dedupe only (`provider_evidence` has no unattributed subject); a `Returned` answer applies
  the completion and leaves the return to `P9-TSK-023`'s worker; a `RECALLED` answer is left to `P9-TSK-024`.
- *As built by `P9-TSK-023` (2026-10-06):* point 6 - both channels converge on payments' `OutboundCreditReturns` under the credit's row lock: an inquiry's `Returned` answer (after the completion it implies, in one transaction) and `OutboundReturnWorker`'s corridor-scoped `PAYOUT_RETURNED` items; the applicability rule exactly as written (`COMPLETED`, the instructed `Money`, an `ACTIVE` customer), the worker deferring while the credit is in flight; payments `V027` holds the fact born once, its applier amount by trigger. The parked return's way out is the four-eyes transfer, reconciliation's `ResolutionMachine` consulting `ResolvedCorridorReturns` at the proposal and recording the return inside the approval. **Deviations:** no `CROSSBORDER_RETURN` claim subject (the report line carries no return reference; `UNIQUE (outbound_credit_id)` and the posting key arbitrate, and the inquiry's return reference is kept on the fact); the port finds an existing fact under the credit's lock rather than by an insert conflict - the approval is refused `ResolutionStale` all the same, nothing written; the port also requires the transfer's target to be the credit's own customer's wallet in the returned currency, and never opens one; `finapp.crossborder.return{outcome}` counts the worker's and the resolution's outcomes, the inquiry channel's applications being the outcome applier's own (audited, evented).
- *As built by `P9-TSK-024` (2026-10-06):* point 5 - `POST /v1/me/cross-border/payments/{id}/cancellation` (keyed `crossborder.cancel`, step-up, owner; `202` with `cancellationRequested`, `409 crossborder.NotCancellable` past recall) marks the outbound credit for recall and records crossborder `V006`'s born-once `cancellation_request` in one transaction, the credit locked before the payment (the outcome applier's order); the outbound credit's resolution asks `CorridorRail.recall(E)` holding no connection and applies the answer on the locked row - `RECALLED` concludes `FAILED(RECALLED)` through the one failure path (the hold released, the quote abandoned, an executed cover unwound), `TOO_LATE` records `REFUSED` and the same pass completes the credit, `UNRECOGNISED` is the never-received rule, no answer is re-asked; a takeover's permit renewal refuses once a recall is requested, so nothing is re-sent; the customer sees `CANCELLED` for `FAILED(RECALLED)` (or `FAILED(NEVER_RECEIVED)` after a request), never for a refused recall. **Deviations:** the recall has no permit column of its own - it is paced by the sweep's poll and idempotent at the provider by `E`, so N instances asking is harmless; the first send of a flight whose cancellation committed between its Tx1 and its send still goes out (it is not a re-send, and the recall that follows decides).
