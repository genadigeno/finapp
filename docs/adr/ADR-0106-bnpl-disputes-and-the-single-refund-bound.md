# ADR-0106 — BNPL disputes and the single refund bound: a customer's contest holds servicing on the disputed amount and resolves only as a refund under the purchase's one bound, so nothing is credited twice

Status: Accepted (2026-10-22, `P12-DOC-001` — read against the code and corrected first)
Date: 2026-10-16
Phase: 12
Context: BNPL · Merchant · Payments (Disputes) · Identity · Audit
Supersedes: nothing. Applies ADR-0105 (the refund policy and its bound, `INV-BNPL-07`), ADR-0061 (a dispute is its
own lifecycle; the combined bound `INV-DSP-01`; §5's negative positions and the merchant close rule), ADR-0052 (merchant
keys, `INV-MER-01`), ADR-0099's four-eyes shape and the Phase 11 gate's R4 (self-approval refusals audited). Builds the
servicing-hold slot Phase 11 designed and did not build (lending A9). Rests on `PHASE_12_PLAN.md` §2.3 (A20), §4,
§5.8, §6 (`INV-BNPL-08`), §7.1 (T-dsp), §7.2, §7.4, §7.5, §9, §11, §12.7, §12.8, §13.2 (scenario 10), §13.4, §16
and §24 (OQ-9, OQ-10).

## Context

1. **A customer can contest a BNPL purchase** — goods not received, not as described, a return the merchant has not
   refunded. Paid by card, the issuer and the network would run a chargeback with deadlines and representment
   (ADR-0061). A BNPL purchase has no card network: the platform is the lender, the merchant was paid from the
   platform's capital (ADR-0104), and the customer's contest comes to the platform.
2. **Servicing does not stop by itself.** While the merchant is asked, the customer's instalments fall due, the
   collection sweep takes the wallet, days past due count and late fees accrue. A customer with a good claim would be
   made delinquent, charged, and collected money the platform may have to return.
3. **The phase's named risk: "chargeback plus refund cannot double-credit"** (gate criterion 3). A purchase can return
   value to the customer by a merchant refund, an operator refund and a dispute outcome. Unless one bound under one
   lock governs every path, two of them can each be admitted against the same remainder — and even within the gross,
   two paths answering the same complaint credit the customer twice for one claim.
4. **Owner defaults** (B8): the merchant's silence past a 14-day response window is acceptance (OQ-10); the operator
   resolves contested disputes, four-eyes above a threshold (plan §4, §9). Instalments are wallet-funded only (B6), so
   no card share of a purchase exists in Phase 12.

## Decision

1. **The aggregate** (`bnpl_dispute` + `_event`, `bnpl V015`, `P12-TSK-023`). One row per contest, referencing the
   purchase; **at most one open per purchase** (partial `UNIQUE (purchase_id)` over `OPEN`, `MERCHANT_RESPONDED`). The
   customer opens it — `POST /v1/me/bnpl-agreements/{id}/disputes`, keyed `bnpl.dispute:CUSTOMER:<id>`, owner-scoped
   (`404` otherwise) — with a reason category, a reason text (`RESTRICTED-PII`, screened as every person-written
   reason is) and an amount D, `0 < D ≤ G − refunded`, judged under **B5**: beyond it the bound's own refusal
   (`422 bnpl.RefundExceedsPurchase`); a fully refunded purchase `409 bnpl.PurchaseFullyRefunded`; a second open
   dispute `409 bnpl.DisputeOpen`. A purchase whose obligation is `CLOSED` can be disputed (the purchase is not terminal,
   ADR-0105 point 8). Phase 12 sets no opening window — the plan names none; it is a terms field when legal review
   sets one.

2. **The machine** (plan §5.8; three ranks — the conditional from-set under the row lock, an every-writer edge
   trigger, a status `CHECK`; recorded in `BNPL_LIFECYCLES.md`):

   | Edge | Actor | Effect |
   |---|---|---|
   | (born) `OPEN` | customer | the hold written (point 3); the response deadline stamped |
   | `OPEN → MERCHANT_RESPONDED` | merchant, before the deadline | evidence recorded; the hold stands |
   | `OPEN → RESOLVED_REFUND` | merchant (accepts liability) | a `BnplRefund` of D (point 6); the hold lifted |
   | `OPEN → RESOLVED_REFUND` | system, deadline passed | the same — silence is acceptance (OQ-10) |
   | `MERCHANT_RESPONDED → RESOLVED_REFUND \| RESOLVED_UPHELD` | operator (point 5) | a refund of D, or the hold lifted and nothing re-billed |
   | `OPEN \| MERCHANT_RESPONDED → WITHDRAWN` | customer | the hold lifted |

   `RESOLVED_REFUND`, `RESOLVED_UPHELD` and `WITHDRAWN` are terminal (`INV-LIFE-04`). A later contest on the same
   purchase is a new dispute judged by the same bound, never a reopened row.

3. **The servicing hold** (A20; plan §12.8). At opening, under B4, the platform records — born once, on the dispute —
   the **held set**: the instalments a refund of D would cancel now, latest first (ADR-0105 point 2 run as a dry run),
   each with its held principal. While the dispute is open:
   - billing proceeds on the contract's dates (F2 — the ledger keeps the contract; nothing is re-dated or edited);
   - **no days past due and no late fee** count on a held amount, and the delinquency and default derivations
     (`P12-TSK-020`) read the hold;
   - **auto-collection takes only the undisputed due amounts** (`P12-TSK-019`); the customer may still repay
     voluntarily, allocated by the engine unchanged — whatever it pays on held instalments returns as excess if the
     dispute resolves as a refund;
   - PN interest keeps accruing on outstanding principal; ADR-0105's PN interest rule applies at the resolution.

   The hold is a **condition, not a state** of the obligation: append-only condition facts (`bnpl_condition_event`
   kinds for the hold placed and lifted), derived and replayable. When it lifts without a refund (uphold, withdrawal),
   a held instalment's days past due and its late-fee day count from the **later of its due date and the lift's
   business date** — a good-faith contest never makes the customer delinquent retroactively.
   *(Corrected by `P12-DOC-001`, 2026-10-22, as the Follow-up's `P12-TSK-023` entry records: the hold's record is the
   dispute's own born-once `bnpl_dispute_hold` rows by due date (`bnpl V015`), not `bnpl_condition_event` kinds; the
   disputed amount is `bnpl_dispute_claim`'s; and an instalment the dry run touches is held **whole** — no collection
   attempt, no days past due and no fee on it — not only its held principal.)*

4. **The merchant's response** (OQ-9's merchant-key doors; OQ-10, owner accepted default). The deadline is stamped at
   opening on the database clock, `opened_at +` the window, a field of the pinned terms version *(corrected by the Phase 12 → 13 transition gate, 2026-10-22: read "`opened_at + 14 days`" until the gate - 14 days is OQ-10's default, not a constant; the window is the pinned version's `dispute_response_days`, 1 to 60 days by `bnpl V002`'s `CHECK` and `BnplTerms.MAXIMUM_DISPUTE_RESPONSE_DAYS`, and `bnpl V015`'s trigger stamps `opened_at + make_interval(days => dispute_response_days)`)*. The merchant
   answers over its key, tenant-isolated (`INV-MER-01`): `POST /v1/merchant/bnpl-disputes/{id}/response`, keyed
   `bnpl.dispute-response:MERCHANT:<id>` — **accept** (→ `RESOLVED_REFUND` at once) or **contest with evidence**
   (→ `MERCHANT_RESPONDED`). The response requires `deadline > statement_timestamp()` and the deadline sweep
   (`BnplDisputeSchedule`) requires `deadline <= statement_timestamp()`, both judged under the dispute's row lock —
   complementary conditionals on one clock, so exactly one wins. *(Corrected by `P12-DOC-001`, 2026-10-22: the clock is
   `clock_timestamp()` read by `bnpl V015`'s edge trigger after the act's locks, not `statement_timestamp()`; the
   complement is unchanged.)* The merchant sees the dispute's amount, reason
   category and deadline and its own evidence — never the customer's schedule, repayments, delinquency or any credit
   figure (plan §11). Evidence documents follow `INV-DSP-03`'s regime by analogy: least privilege, encrypted at rest,
   every access audited; the storage shape is `P12-TSK-023`'s design. *(As built (`P12-TSK-023`; recorded by
   `P12-DOC-001`, 2026-10-22): no evidence documents are stored — the merchant's evidence is a bounded text statement
   on the dispute row (`merchant_statement`, 1–1,000 characters, `RESTRICTED-PII`, refused if it holds an instrument's
   shape), every operator read of a dispute audited `bnpl.DisputeRead`. A document store is not built.)*

5. **The operator's resolution** (`POST /v1/operator/bnpl-disputes/{id}/resolution`, `BNPL_DISPUTE_RESOLVE`, keyed
   `bnpl.servicing:EMPLOYEE:<id>` *(as built: `bnpl.servicing:<actor type>:<id>` — `BnplDisputeDesk` keys by the
   session's actor type, `CUSTOMER` until operator sessions carry `EMPLOYEE` actors (lending's A5-04 note); the
   sub-routes are `…/resolution/approval` and `…/resolution/rejection`; recorded by `P12-DOC-001`, 2026-10-22)*). Only from `MERCHANT_RESPONDED`; the outcome is binary — refund D or uphold —
   reasoned, audited with actor, target, reason, correlation and outcome. **Four-eyes above a threshold**: the
   threshold is a field of the pinned terms version, compared with D, for either outcome. At or below it one resolver
   decides; above it one proposes (the proposal the act's own row, B6) and a second, different employee approves or
   rejects through approval and rejection sub-routes mirroring the waiver's; self-approval is refused
   `403 bnpl.SelfApprovalRefused` and audited `FAILED` (the gate's R4); self-dealing `bnpl.SelfDealingRefused`. Unlike
   a waiver (ADR-0099 refused threshold four-eyes there), a resolution forgoes no platform income and moves no
   platform money: it allocates a bounded loss between a merchant that has argued its case and a customer, and only
   above the threshold does the consequence warrant a second person.

6. **`RESOLVED_REFUND` *is* a refund** (plan §12.7; F9 = F5, ADR-0103). The resolving transaction executes
   ADR-0105's T-ref body in the same commit, under the B5 lock it already holds and the same bound: a `BnplRefund` of
   amount D with origin `DISPUTE`, `UNIQUE (dispute_id)` on `bnpl_refund` (one dispute, at most one refund), ledger key
   `bnpl.refund:<refund>`. The latest-first split is recomputed over the due state at resolution — it equals the held
   set unless the customer paid held instalments meanwhile, whose payment then returns as excess. The merchant is
   liable: the payable is debited D − r_f, the fee pro-rated back by the cumulative method, and a dispute refund after
   payout is the same third bounded negative source (`INV-MER-07` as amended by ADR-0105). `RESOLVED_UPHELD` and
   `WITHDRAWN` post nothing.

7. **No merchant or operator refund while a dispute is open.** Both refund doors refuse a purchase with an open
   dispute `409 bnpl.DisputeOpen`, judged under B5; the merchant settles the contest by accepting it. A refund
   committed before the opening shrinks what can be disputed (`D ≤ G − refunded`); after the resolution, refunds
   resume within the bound. The bound alone stops the customer side receiving more than G; this rule also stops a
   refund and a dispute outcome both answering one complaint inside G.

8. **One bound for every path** (`INV-BNPL-08`). Merchant refunds, operator refunds and dispute refunds are all
   `BnplRefund`s judged by `INV-BNPL-07` under the one purchase lock: across all of them at most G is ever returned to
   the customer side (principal cancelled plus excess). The probe "skip the purchase lock in a refund" turns scenario 10
   (`BnplDisputeDatabaseTest#disputeRefundAndMerchantRefundShareOneBound`) red (`MUTATION_TESTING.md` §2).

9. **The lock order** (plan §7.2). Every dispute act (T-dsp): claim → **B5** purchase → **B4** obligation → **B6** the
   dispute row (and, above the threshold, its proposal) → on a refund outcome **(P)** the payable and, when an excess
   is credited, the wallet as one `UUID.compareTo` pair → **L8** → the entry. No L0 and no L6: no outcome raises
   exposure or re-instates principal (an uphold re-bills nothing; ADR-0104 Decision 4). The deadline sweep never takes
   B6 first: it claims candidates by a database-stamped permit in its own statement holding no row, then each step
   takes B5 → B4 → B6 in order and re-judges the deadline under B6 (lending's sweep shape). Ten sweepers resolve a
   lapsed dispute once.

10. **No chargeback on the financed amount** (plan §12.7). There is no card network on a BNPL purchase and no card
    share (B6). A chargeback on a **card-funded wallet top-up** the customer later spent on instalments is payments'
    dispute on that top-up (ADR-0061), unrelated to the agreement: it debits the wallet — below zero if the money was
    spent, ADR-0061 §5's customer receivable — and the instalments it funded stay paid. A customer who disputes the
    purchase and charges back the top-up is credited by one record and debited by the other, each bounded by its own
    (`INV-BNPL-07` on the purchase, `INV-DSP-01` on the top-up's capture): nothing is created. If a later phase adds
    card-funded instalments, `INV-DSP-01` must be extended to count against the purchase's bound under B5 — recorded.

11. **The merchant close.** A merchant with an open BNPL dispute cannot be closed: the close asks the question
    ADR-0061 §5's gate rule asks for card disputes and refuses until the dispute is resolved, so a resolution never
    debits a closed payable no later sale can recover.

12. **Events, audit, meters.** `BnplDisputeOpened`, `BnplDisputeResolved` (outcome) and, for a refund outcome,
    `BnplRefundApplied`, each in its state change's transaction; audit `bnpl.DisputeOpened`, `…Responded`, `…Resolved`
    (actor `SYSTEM` on a lapsed deadline) and the proposal's acts; `finapp.bnpl.dispute{outcome}`;
    `BnplDisputeSchedule` the 29th lease-protected scheduler, gauged `…sweeper.enabled`. *(As built (`P12-TSK-023`;
    recorded by `P12-DOC-001`, 2026-10-22): a withdrawal also publishes `bnpl.BnplDisputeResolved` with the outcome
    `WITHDRAWN` and is audited `bnpl.DisputeWithdrawn`; the proposal's acts are `bnpl.DisputeResolutionProposed` and
    `…ResolutionRejected` (an approval is `bnpl.DisputeResolved`); the gauge is `finapp.bnpl.dispute.sweeper.enabled`.)*

## Alternatives Considered

### Reuse payments' dispute aggregate
Cons: it is keyed by a provider dispute reference on a card attempt and bounded by the attempt's capture
(`INV-DSP-01`); a BNPL purchase has neither. The bound that matters is the purchase's. Refused.

### No hold — collect, and refund later
Cons: the customer is made delinquent and charged late fees for a contest that may succeed, and collected money comes
back. Refused (A20).

### Hold the whole obligation
Cons: a contest over one item becomes a payment holiday on the rest. Refused: only the disputed amount, latest first.

### Silence upholds the purchase
Cons: the merchant holds the evidence and the customer has none to add; silence as acceptance is the norm the card
networks also apply. Refused (OQ-10, owner accepted default; review later).

### Admit refunds during an open dispute, reducing D
Pros: the merchant can refund an unrelated return without waiting.
Cons: the platform cannot tell whether a refund answers the dispute or another return; either reading credits someone
wrongly. Refused for Phase 12; refusal with the accept path is deterministic — review later.

### Partial outcomes (refund part of D)
Deferred: a binary outcome keeps the resolution's refund exactly the held amount; a split decision is a later need.

## Consequences

Positive:
- "Chargeback plus refund cannot double-credit" holds by construction: one bound, one lock, one refund mechanism.
- A customer contesting in good faith is never made delinquent, charged or collected on the disputed amount.
- The servicing-hold slot lending designed now exists, as a condition, for later reuse.

Negative:
- A merchant cannot refund other items on a disputed purchase until the dispute ends (point 7).
- Silence is acceptance: a merchant that misses the window bears the refund, after payout as merchant debt (ADR-0105).
- The plan's cut order (§16) cuts the operator resolution first: if cut, `MERCHANT_RESPONDED` has no exit but
  withdrawal, so the cut must also make the response accept-only — recorded for the cut decision.

Operational impact: the dispute meter, the deadline sweeper and its gauge; the dispute report. *(The Phase 12 → 13
transition gate, 2026-10-22: the dispute report was not built by `P12-TSK-023`; it is delivered as the operator's queue -
`GET /v1/operator/bnpl-disputes?status=MERCHANT_RESPONDED|OPEN`, see the amendment below - with the contested-age gauge and
two alerts.)*
Security impact: merchant-key response door; `BNPL_DISPUTE_RESOLVE` with four-eyes above the threshold; evidence
least-privilege and access-audited; reasons `RESTRICTED-PII`.
Financial impact: none new — a dispute's only money is a refund's.

## Invariants / Constraints

`INV-BNPL-08` (refund and dispute never double-credit), `INV-BNPL-07` (Σ refunds ≤ G under the purchase lock),
`INV-BNPL-06`, `INV-BNPL-09`, `INV-DSP-01` (on a card share, none in Phase 12; on a top-up, payments'), `INV-DSP-03`
(applied by analogy), `INV-MER-01`, `INV-MER-05`, `INV-MER-07` (amended by ADR-0105), `INV-LIFE-04`, `INV-IDEM-01`,
`INV-AUD-01`, `INV-AUD-02`.

## Follow-up

- `P12-TSK-023` — the machine, the hold, the response, the resolution (four-eyes above the threshold), the sweep,
  point 7's refusal, point 11's close rule (with merchant); scenario 10 and the race "refund racing a dispute
  resolution" (plan §7.4).
- `P12-TSK-019` and `P12-TSK-020` — collection, days past due and late fees reading the hold; `P12-TSK-014` — the
  merchant's read of its BNPL purchases gains the dispute's state and deadline.
- `P12-TST-001` and `P12-TST-002` — disputes racing refunds, collections and payoffs; random dispute sequences in the
  battery, Σ refunds ≤ G per purchase at every round.
- **As built by `P12-TSK-023`** (2026-10-19, `bnpl V015`): decisions 1-9, 11 and 12 as written, with these readings - the
  hold's record is the dispute's own `bnpl_dispute_hold` rows by due date, not `bnpl_condition_event` kinds (that table's
  `UNIQUE (obligation_id, business_date, kind)` and its derivation trigger are delinquency's); an instalment touched by the
  dry run is held whole (collection, DPD, fee), its clock after any lift restarting at the lift's business date; the
  proposal is `bnpl_dispute_proposal`, its four-eyes a `CHECK`; the machine row is money-free (the claim INSERT-only); the
  merchant close's rule is the opening taking the payable at (P) and the close's settled-payable port asking
  `BnplDisputeStore.anyOpenAtMerchant`; the merchant's response needs its key, not the refund scope. Payoff and voluntary
  repayment are not paused by a dispute (the customer's own acts; what they pay on held instalments returns as excess on a
  refund outcome). `bnpl.days_past_due` replaced; the fee, collection and refund ranks are new triggers beside the old.
- **Acceptance.** **As built** (`P12-DOC-001`, 2026-10-22): `Proposed` at the Phase 11 → 12 transition
  (2026-10-16); the Phase 12 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.

## Amendment — the Phase 12 → 13 transition gate (repair R1, 2026-10-22; `bnpl V018`)

The gate's independent audits found that a dispute could fail to conclude silently, and that three rules were weaker than
this ADR meant. Each is repaired as below, each with a regression test red before and green after, its probe recorded in
`MUTATION_TESTING.md` §2. Nothing here changes the bound (`INV-BNPL-07`, `-08`) or the lock order (decision 9).

1. **A lapse whose refund cannot post is never silent, and is not born** (FINDINGS A7-1; decisions 6, 11). On a `CLOSED`
   obligation - a payoff mid-dispute is allowed - a refund outcome cancels nothing and holds no credit balance
   (`INV-BNPL-09`), so the whole of D can only be credited to the customer's wallet; with the wallet closed or absent the
   deadline step retried `not_postable` for ever, the dispute `OPEN`, the merchant unable to close, the refund owed
   recorded nowhere. Now: (a) `BnplDisputeRefundNotPostable` alerts on the dispute meter's `not_postable` outcome
   (`OPERATIONS_RUNBOOK.md` §8.9); (b) **a wallet cannot close while an open BNPL dispute of its customer may refund to it**
   - accounts' close asks its pending-credits port with the closing customer, and the composition root answers it over
   `BnplDisputes.anyMayRefundToWalletOf` (`BnplWalletCredits`) - accounts never reads a bnpl table; (c) **an opening on a
   `CLOSED` obligation takes the payable and the wallet as one (P) element** (decision 9's pair, `UUID.compareTo` order)
   and refuses a customer with no `ACTIVE` wallet `409 bnpl.WalletUnavailable`, nothing written. The close takes the
   wallet's row `FOR UPDATE` before it asks; the opening and every refund outcome take it at (P): the two are ordered,
   never beside each other. Residual, recorded: an opening on an `ACTIVE` obligation does not lock the wallet - a close
   racing it may commit beside it; that dispute's refund then goes to the credit balance (an `ACTIVE` obligation's
   fallback), and the agreement cannot be paid off from a closed wallet, so no lapse is stranded by it; an off-boarded
   customer (no live customer, so no wallet) remains the alert's to surface - owner item "a refund on a CLOSED
   obligation without a wallet".
2. **A contested dispute is visible** (FINDINGS A7-2; decisions 2, 12). `MERCHANT_RESPONDED` has no deadline - only an
   operator concludes it - and meanwhile its held instalments are never collected, aged or charged, every refund of its
   purchase is refused and its merchant cannot close. Now the contest publishes **`bnpl.BnplDisputeContested`** (the
   dispute, its purchase and obligation, its status and deadline - no amount, statement or party) in its transaction; the
   eager gauge **`finapp.bnpl.dispute.contested.age`** reads the oldest contested dispute's wait since the merchant
   answered (NaN when unreadable), alerted by `BnplDisputeContestedTooLong` above `BnplObjectives.CONTESTED_DISPUTE` (a
   week, ticket); and **the promised dispute report is delivered as the operator's queue**:
   `GET /v1/operator/bnpl-disputes?status=MERCHANT_RESPONDED|OPEN[&after=<id>]` under `BNPL_DISPUTE_RESOLVE`, oldest first,
   a hundred a page keyset-paged after the cursor, no free text, never the operator's own party's disputes, every page
   audited `bnpl.DisputeQueueRead`. **Auto-resolution of a contested dispute at an SLA is the owner's choice and is not
   built.**
3. **A withdrawal never restarts the clock** (FINDINGS A5-F2; decision 3). Decision 3's "the later of its due date and the
   lift's business date" applied to every lift, so open, withdraw and re-open restarted days past due and the late-fee day
   each time and could keep an unpaid instalment from ever reaching default. Now a **withdrawn** dispute's lift counts for
   nothing - DPD and the fee day are judged from the due date as if never held (`bnpl.dispute_clock_start` replaced in
   `bnpl V018`; `BnplDisputeHold.clockFrom` the same rule, read by `BnplDelinquencyState` and `BnplConditions`); a refund
   or upheld lift keeps decision 3's rule. While a dispute is open its instalments are still held, so a customer who
   could re-open on the very day of a withdrawal would still hold them for ever; therefore **a customer's withdrawal
   starts a cooldown**: the purchase takes no new dispute for 30 days from the withdrawal's own stamp
   (`BnplDisputes.WITHDRAWAL_COOLDOWN`, `bnpl.dispute_withdrawal_cooldown()` - a safe default, **the length the owner's
   to change**, or later a field of the pinned terms), judged under B5 on the database clock and refused
   `409 bnpl.DisputeCoolingOff`, nothing written; `bnpl V018`'s `BEFORE INSERT` trigger
   (`bnpl_dispute_respects_the_withdrawal_cooldown`) holds every writer to it. A dispute a merchant or an operator
   resolved starts no cooldown. Ten instances opening at once after a withdrawal all meet the same B5 and the same stamp:
   every one is refused within the cooldown, and after it the one-open-per-purchase unique admits one.
4. **Four eyes on the purchase's cumulative refunds by dispute** (FINDINGS A5-F7; decision 5). The threshold was compared
   with each dispute's own D, so a contest split into sequential disputes, each at or below it, was resolved by one
   operator each time. Now it is compared with **D plus every refund an earlier dispute of the same purchase made**
   (`bnpl.dispute_four_eyes_amount`, read under B5; the edge and proposal triggers replaced in `bnpl V018`;
   `BnplDisputes.fourEyesAmount` the same rule) - for either outcome.
5. **The deadline's conditional in the edge's own statement** (FINDINGS A3-M2; decision 4). The merchant's acceptance
   judged the deadline after B6, then waited at (P) and L8; when the deadline passed meanwhile, `bnpl V015`'s trigger
   refused the edge as a storage failure - a `5xx`. Now the merchant's edges carry `response_deadline_at >
   clock_timestamp()` and the sweep's `<=` in the `UPDATE` itself (`JdbcBnplDisputeStore.move`): no row is
   `409 bnpl.DisputeNotActionable`, the refund the acceptance wrote rolled back with it, and the sweep lapses the dispute.
   The trigger stays beneath for every writer; only a microsecond separates its clock read from the statement's.
6. **Self-dealing fails closed and covers the read** (FINDINGS A7-6/A5-F3, A5-F4; decision 5). An operator the platform
   cannot place in a party is refused at the resolution, its approval and rejection, the read and the queue, as the
   corrections' rule does; the read (`GET /v1/operator/bnpl-disputes/{id}`) refuses an operator of the dispute's own party;
   each refusal `403 bnpl.SelfDealingRefused`, recorded `FAILED` in a transaction of its own.
