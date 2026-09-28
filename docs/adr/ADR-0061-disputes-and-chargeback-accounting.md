# ADR-0061 — A dispute is its own lifecycle on a card payment; a chargeback never takes more from the counterparty than it was credited, and every stage posts once

Status: Accepted (2026-09-28, `P7-DOC-001` — read against the implementation at the phase review; seven passages corrected to it, and the risks it said were recorded recorded, first)
Date: 2026-09-24
Phase: 7
Context: Payments (Disputes) · Merchant · Accounts · Ledger
Supersedes: nothing. Implements the seam `ROADMAP.md` records ("Dispute/chargeback financial
effect — introduced Phase 5, implemented Phase 7") and the evidence Phase 5 preserved for it
(`PAYMENT_LIFECYCLES.md` §1). Amends `INV-MER-07` (a second, bounded source of merchant debt).

## Context

A chargeback is not a refund. A refund is the platform's decision. A chargeback is forced on the
platform through the payer's issuer and the card network's rules, with deadlines and evidence
requirements, and its money has **already left** by the time the platform hears of it: the PSP
nets it from settlement or debits the platform. A dispute is the process around it. It can start
as an inquiry, becomes a chargeback when the network takes the funds, may be contested by
representment, and ends won or lost. Collapsing dispute into chargeback loses representment, the
only chance to defend the payment (`GLOSSARY.md`).

The delivery plan names the accounting risk directly: "chargeback accounting that double-debits
the merchant". The platform can double-debit a merchant in three ways:

1. A duplicated or replayed notification posts twice.
2. A chargeback arrives on a payment the merchant already refunded. The merchant has already
   given the money back once, and the network takes it again from the platform.
3. A refund is allowed after a chargeback has already taken the value back.

Phase 6 made one more fact relevant. A merchant's payable may already have been paid out when a
chargeback arrives. A wallet top-up (a payment with no merchant) can be charged back too, after
the customer has spent the money.

## Decision

1. **A dispute is its own aggregate in `payments`, one per provider dispute reference.**
   Bounded context 29, Disputes, is mapped to the `payments` module (merged). The chargeback
   bound below and the refund bound are one arithmetic over one payment's captured value, judged
   under one lock. Two modules would turn that arithmetic into a cross-module invariant.
   *Split trigger:* disputes acquiring rails other than cards, a case-management workflow with its
   own retention, or Phase 13's case management wanting to own the investigation.

   `UNIQUE (provider, provider_dispute_reference)`. The dispute references the attempt it
   contests.

   *(Shipped `P7-TSK-012`: `payments.dispute` in `V020`, the unique key the opening's arbiter
   under ten fresh-id deliveries *(since `P7-TSK-013` the attempt row lock every delivery takes
   first serialises the deliveries on one attempt, and the key arbitrates only a reference named
   against another attempt - corrected by the phase review, `P7-DOC-001`)*; the network's opening statement — provider, reference,
   attempt, reason category — frozen for every writer, and the chargeback's amount D arriving
   WITH the chargeback (`NULL → value`, the captured amount's discipline): an inquiry states
   only the transaction it asks about, and a chargeback may take less, so the figure point 4
   posts is what the network took, recorded once and never revised. The dispute is recorded
   against whatever card attempt the network names, whatever its state: the external fact
   first (point 4), attribution being the combined bound's.)*

2. **The lifecycle has a financial effect at each stage, and only there.**
   - `INQUIRY`: the issuer asks; nothing has moved. No effect.
   - `CHARGED_BACK`: the network has taken the funds. **The chargeback posting.**
   - `REPRESENTED`: evidence has been submitted. No effect.
   - `WON`: the network returned the funds. **The compensating posting**, the exact inverse of
     the chargeback's principal lines.
   - `LOST` or `ACCEPTED` (not contested): terminal. The chargeback's debit stands, and any
     recoverable excess is written off (point 4).
   - `CLOSED`: an inquiry the issuer closed without a chargeback. Terminal, no effect. *(Missing
     from this list until the review; `INQUIRY → CLOSED` is in the machine from `V020`.)*

   Terminal is terminal (`INV-LIFE-04`). A second-cycle chargeback is a new dispute notification
   judged by the same bound, never a reopened row. A representment after resolution is refused.
   A notification that names a stage later than the dispute's current one applies the intervening
   effects in order, in one transaction. A "won" arriving before its chargeback therefore posts
   both, and the history shows both.

   *(The stages shipped `P7-TSK-012` — their postings `P7-TSK-013`, point 4's note: the machine is
   `DisputeStage`, generated into `V020`'s every-writer trigger; "the intervening stages" is
   the shortest walk along its edges, which is exactly the stages the notified one implies —
   a "won" heard first opens at `CHARGED_BACK` and walks through `REPRESENTED`, each stage its
   own edge, trail row and audit record. A stage the dispute has passed is a late delivery and
   moves nothing quietly; any other contradicts the record and moves nothing loudly.)*

3. **The combined bound: refunds and chargebacks together never take more from the counterparty
   than the capture credited it.** For every captured card payment:

   ```
   refunded (non-failed) + charged back to the counterparty  ≤  captured
   ```

   The bound is judged under the attempt row lock, the lock a refund's dispatch already takes
   (`P5-TSK-015`), so the two money paths serialise on one row.
   - **A chargeback of D** debits the counterparty `min(D, captured − refunded −
     previously charged back)`. The excess is value the network took that the platform had
     already returned. It is posted to `CHARGEBACK_RECOVERABLE`, an operational asset, and **never
     to the counterparty**. This closes risk 2 by arithmetic, not by a check someone must remember.
   - **A refund** is refused when it would breach the bound (`payments.RefundExceedsCaptured`, the
     existing code, its meaning extended). This closes risk 3.
   - **Refunds count as soon as they are non-failed**, exactly as the refund bound counts them. If
     a counted refund later fails, its failure transaction re-attributes, under the same lock, the
     share of the chargeback's excess that the failed refund had caused, from
     `CHARGEBACK_RECOVERABLE` back to the counterparty. At every commit, the counterparty has been
     debited by no more than it was credited, and the recoverable holds exactly the value the
     network took twice.

   *(Shipped `P7-TSK-013`: `ChargebackSplit` is the arithmetic, judged by `ChargebackAccounting`
   under the attempt row lock every dispute delivery now takes FIRST — before the dispute insert,
   the lock order `P7-TSK-012` recorded — and the refund's dispatch judges `amount + non-failed
   refunds + standing attributions ≤ captured` under the same lock (`payments.RefundExceedsCaptured`,
   meaning extended). Both ranks for every writer: `V021`'s dispute-attribution trigger and the
   re-stated refund-bound trigger, sharing advisory namespace 3. "Charged back to the counterparty"
   is its ATTRIBUTED share — posted, or parked (point 5) — because a parked share is the
   counterparty's by attribution and a second cycle must not attribute it twice. A chargeback is
   recorded at once whatever the attempt's capture state — an attempt that has captured nothing,
   yet or ever, credited nobody, so its whole chargeback is excess. The re-attribution rule is
   applied to EVERY event that frees headroom (`captured − non-failed refunds − standing
   attributions`): a counted refund failing (this point's rule), a sibling chargeback WON (its
   attribution reversed, reachable when a win is delivered after a second cycle's chargeback), and
   a CAPTURE LANDING on an attempt a chargeback was already stated against (the completion gate's
   find — the design had deferred such a chargeback until the capture resolved, which left an
   `AUTHORIZED` attempt's later capture mis-attributed to the platform and made recording depend on
   the PSP's retry window; recording at once and re-attributing when the capture lands closes both).
   Oldest dispute first, from the recoverable while contested and from `DISPUTE_COSTS` once a loss
   wrote the excess off — so at every commit the split is the one a chargeback arriving now would
   take, and the closing sentence above holds - widened by the review to what the recoverable
   also carries: the value the network took twice, **or took uncredited** (a chargeback on an
   attempt that captured nothing), **plus parked shares** (point 5).)*

4. **Accounting, the external fact first.** The notification states what the PSP did, so the
   card rail's clearing position moves by exactly that; everything else is the platform's
   attribution of it.

   | Stage | Lines |
   |---|---|
   | `CHARGED_BACK` | CR `SETTLEMENT_CLEARING` D; DR the counterparty's account (the merchant's `MERCHANT_PAYABLE`, or the customer's `CUSTOMER_WALLET` for a top-up) by its share; DR `CHARGEBACK_RECOVERABLE` by the excess |
   | `WON` | The exact inverse of the principal lines: DR `SETTLEMENT_CLEARING` D; CR counterparty share; CR `CHARGEBACK_RECOVERABLE` excess |
   | `LOST` / `ACCEPTED` | The excess, if any, written off: DR `DISPUTE_COSTS`; CR `CHARGEBACK_RECOVERABLE` |
   | A dispute fee the PSP reports | DR `DISPUTE_COSTS`; CR `SETTLEMENT_CLEARING`. The platform bears it in Phase 7; passing it to merchants is a fee-schedule extension, recorded |

   Each posting is keyed by the dispute and its stage (`dispute-chargeback:<id>`,
   `dispute-won:<id>`, `dispute-loss:<id>`, `dispute-fee:<id>`), so a duplicate or replayed
   notification finds its conditional transition lost and its key taken. One financial effect
   per stage (`INV-IDEM-04`), and risk 1 is closed by the key, not by care.

   The counterparty's lines are **composed** through a `DisputeComposition` port that `app`
   implements, the `CaptureComposition` and `RefundComposition` pattern: `payments` does not know
   what a merchant is, and `merchant` does not see `payments`.

   The merchant's processing fee is **not** returned by a chargeback. The merchant loses the sale
   and keeps the fee it was charged for processing it. The refund policy (ADR-0054) governs
   refunds only.

   *(Shipped `P7-TSK-013`, with one design correction recorded: each financial stage posts TWO
   entries rather than one — the external fact (`dispute-chargeback:<id>`: DR
   `CHARGEBACK_RECOVERABLE` D / CR the rail's clearing D; `dispute-won:<id>` its inverse) and the
   attribution (`dispute-attribution:<id>`: DR the counterparty / CR `CHARGEBACK_RECOVERABLE` its
   share, composed by `DisputeComposition`; `dispute-restoration:<id>` its inverse). Per account the
   pair is exactly the table above. Why two: the first entry never varies with who bears the loss;
   the counterparty's line always faces the recoverable, so a merchant's payable drill-down names
   a chargeback in the ledger's own vocabulary (a one-entry chargeback with no excess is
   line-for-line a retained refund — `P7-TSK-010`'s mislabel class, prevented by the shape); and
   the composer composes only the counterparty's part, the rail's clearing staying payments'
   (`INV-RAIL-04`). The loss's write-off is `dispute-loss:<id>` (excess only), the fee
   `dispute-fee:<id>` (recorded once, `NULL → value`, with the chargeback's statement or reported
   later), a re-attribution `dispute-reattribution:<dispute>:<cause>`. The stage facts carry the
   split's accounts, never amounts.)*

5. **A counterparty may go below zero, and it is visible, never absorbed.**
   - A merchant payable driven negative by a chargeback after a payout is **merchant debt**.
     `INV-MER-07` is amended to name chargebacks as its second, bounded source: never more than
     the counterparty was credited. It is recovered from later captures before any payout,
     because `INV-MER-05` bounds a payout by the payable.
   - A customer wallet driven negative by a charged-back top-up the customer spent is a
     **receivable from the customer**, recorded and never silently written off. Collection is
     Phase 11's and Phase 13's. *(No phase plan named it until the review, which recorded it as
     Known Architectural Debt in `CURRENT_STATE.md` - merchant debt with no reserve and customer
     receivables with no collection - owned by Phase 13 and triggered by the negative-position
     gauge reading above zero in operation.)*

   Both positions are counted by a gauge.

   - A counterparty account that can no longer take a posting — a wallet the customer has since
     closed, whose ledger account `V007` refuses new lines — never makes the chargeback fail:
     the network has already taken the money. Its share is posted to `CHARGEBACK_RECOVERABLE`
     beside any excess, visibly, and recovering it is an operator's act. *(The Phase 6 → 7
     transition found the inbound mirror of this — a capture refused by a closed wallet, the
     customer charged and nothing booked — and closed it at confirmation and at close. A
     chargeback cannot be refused at the door, so it is parked instead.)*

   *(Shipped `P7-TSK-013`: the parked share is its own column (`parked_share`), counted against
   the bound, left untouched by a loss's write-off and returned from the recoverable by a win.
   Postability is read under a share lock, never upgraded in the dispute's transaction. The
   mirror hazard — a win crediting an account closed after its chargeback — is closed at the
   close: `AccountClosing`'s pending-credits port now also asks whether a chargeback whose share
   was POSTED to the account can still be won (`CHARGED_BACK`/`REPRESENTED`), and refuses the
   close until it cannot. `POSTING_SUSPENDED` has no producer; a restoration meeting one fails
   loudly and the PSP redelivers once the freeze lifts. The positions are counted by
   `finapp.ledger.negative.positions`, per purpose, and `INV-MER-07`'s amendment is in force.)*

   *(`P7-TST-001`, the multi-rail storm, found the lock order one step short and closed it: every
   stage that posts to the counterparty - `CHARGED_BACK` and `WON`; `LOST`, `ACCEPTED` and the
   fee never touch it, and a re-attribution locks it through its postability read - now
   share-locks the counterparty BEFORE its first posting *(this read "every stage" until the
   review)*. A win's external fact had
   taken the clearing's and the recoverable's balance rows before its restoration touched the
   counterparty, while a refund of another payment to the same counterparty held that account for
   its hold's release and waited on the clearing - a `40P01`, a 500 at the card door, the PSP's
   redelivery the only recovery.)*

6. **Notifications are authenticated, deduplicated and order-blind.** Dispute notifications
   arrive through the card rail's signed webhook door (ADR-0047): authenticated before parsing,
   deduplicated by the inbox (`INV-IDEM-04`), evidence retained verbatim in the effect's own
   transaction (`INV-HIST-02`), applied through conditional stage transitions. *(This read
   "before the effect" until the review: the door runs the effect first and inserts the
   evidence after it, in the same transaction, a deliberate lock-order rule - the evidence
   insert takes a key-share lock on the attempt row the effect locks - so the evidence and the
   effect commit together or not at all.)* Provider dispute vocabulary
   (reason codes, stage words) stays in the adapter (`INV-PAY-03`); the core sees our stages and
   our reason categories.

   *(Shipped `P7-TSK-012`: the card door's fourth statement kind (`status: "disputed"`), its
   stage words and reason codes mapped through `CardDisputeVocabulary`'s total tables — an
   unknown stage word moves nothing, an unknown reason code is `UNCATEGORISED` — onto
   `DisputeReason`'s four network groupings; evidence attributed to the disputed attempt;
   applied by `DisputeNotifications` inside the delivery's transaction as the platform. Read by
   the merchant over its key, the tenant predicate the payment's credit account among its own
   payables (`INV-MER-01`), and by operators under the new `DISPUTE_ADMINISTER`, every dispute
   shown audited.)*

7. **Representment is a dispatch like any other.**
   - The merchant (over its key, tenant-scoped, `INV-MER-01`), or an operator for a payment with
     no merchant, submits evidence before the respond-by deadline.
   - The submission to the PSP is dispatch-before-call and keyed (`INV-PAY-04`).
   - Evidence documents are encrypted at rest, and every access is audited (`INV-DSP-03`,
     `INV-KYC-06`'s regime restated for disputes).
   - A submission after the deadline or after resolution is refused.
   - The platform's clock only raises the alarm when a deadline nears. It never decides an
     outcome: `WON` and `LOST` come from the network.

   *(Shipped `P7-TSK-014`, payments `V022`: the answer is one aggregate, `DisputeResponse` —
   REPRESENTMENT or ACCEPTANCE — because acceptance needs every protocol representment has.
   Dispatched with our reference committed before the call, one LIVE answer per dispute for every
   writer, the send permit forward-only, an ambiguous answer resolved by query on our reference
   (`DisputeResponseResolution`, the SAME request re-sent where the PSP never saw it); SUBMITTED
   means the PSP took it and moves NO stage — the network's `under_review`, `accepted`, `won`,
   `lost` still arrive by notification, and `P7-TSK-013` posts what each owes. Evidence is
   `dispute_evidence`: AES-256-GCM under its own key (`FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEY`), the
   plaintext's SHA-256, append-only by grant, content-addressed; every content read audited
   (`payments.DisputeEvidenceRead`) and every wire transmission too
   (`payments.DisputeEvidenceTransmitted`). The deadline is the network's `respondBy`, recorded
   once with the chargeback, or first reported on a later statement - still `NULL → value`
   (added by the review); the platform refuses only its OWN late dispatch and raises
   `finapp.payments.dispute.deadline.near`. "An operator for a payment with no merchant" is
   enforced: the operator acts only where the payment credited a customer wallet, reasoned.
   Every act locks the attempt and then the dispute — the order every delivery keeps.)*

8. **Disputes exist on the card rail only** (ADR-0059's `disputes` capability). A push-rail
   payment has no chargeback. A customer's complaint about one is a refund decision, and a recall
   is out of scope.

## Alternatives Considered

### Debit the merchant for every chargeback in full
Pros: simple, and it is what a naive integration does.
Cons: it is the named double debit. A merchant that refunded properly pays twice, and the
platform's representment has no position in the books.

### Refuse to record a chargeback the bound does not allow
Pros: the bound is never "exceeded".
Cons: the network has already taken the money. Refusing to record it leaves `SETTLEMENT_CLEARING`
overstated, and Phase 8 finds the break without a record explaining it.

### Disputes as a separate module
Pros: a clean home for case handling.
Cons: the chargeback bound and the refund bound become a cross-module invariant over one
payment's captured value. The split trigger (point 1) says when this changes.

## Consequences

Positive:
- The gate's two dispute criteria (single effect under duplicates; no double debit after a
  refund) are properties of keys and one lock.
- `SETTLEMENT_CLEARING` stays equal to what the PSP will actually settle, disputes included:
  Phase 8's opening position.

Negative:
- A second source of merchant debt, bounded by the credit. Reserves are out of scope; they are
  recorded as a risk with an owner *(true since the review, `P7-DOC-001`: the Known
  Architectural Debt row in `CURRENT_STATE.md`, owned by Phase 13)*.
- Refund failure paths gain a re-attribution step when a chargeback stands.

Operational impact: dispute counts by stage and outcome; respond-by alarms; the negative-position
gauge; the chargeback ratio as an operator report, never a per-merchant metric tag (ADR-0018).
*(Shipped `P7-TSK-015`: `finapp.payments.dispute` by stage - the terminal stages are the
outcomes - and the audited report `GET /v1/operator/reports/chargeback-ratio` under
`MERCHANT_ADMINISTER`, the standing desk's evidence; the respond-by alarm and the negative-position
gauge shipped with `P7-TSK-014` and `P7-TSK-013`.)*
Security impact: dispute evidence is least-privilege, encrypted and access-audited; merchant
dispute routes are tenant-scoped.
Financial impact: three new operational accounts (`CHARGEBACK_RECOVERABLE` asset,
`DISPUTE_COSTS` expense, plus `INSTANT_CLEARING` from ADR-0062); `INV-MER-07` amended.

## Invariants / Constraints

`INV-DSP-01` (the combined bound), `INV-DSP-02` (each stage posts once; a resolution reverses
exactly what it resolves), `INV-DSP-03` (dispute evidence), `INV-IDEM-04`, `INV-LIFE-04`,
`INV-REV-01`, `INV-MER-01`, `INV-MER-07` (amended).

## Follow-up

- `P7-TSK-012` (the dispute aggregate, notifications and stages), `P7-TSK-013` (the accounting
  and the combined bound), `P7-TSK-014` (representment and evidence), `P7-TST-002` (the dispute
  battery).
- Merchant reserves and dispute-fee pass-through: candidates for a merchant-risk phase, recorded
  in `CURRENT_STATE.md`. *(They were not, until the review: reserves and receivable collection
  are the Known Architectural Debt row, fee pass-through a `DECISIONS.md` Deliberately Deferred
  row - the substance was in `PHASE_7_PLAN.md` §17 and the backlog alone.)*
- The phase review read this ADR against the code (`P7-DOC-001`): every decision holds in the
  code; seven passages corrected above with provenance.
