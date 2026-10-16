# ADR-0106 — BNPL disputes and the single refund bound: a customer's contest holds servicing on the disputed amount and resolves only as a refund under the purchase's one bound, so nothing is credited twice

Status: Proposed
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

4. **The merchant's response** (OQ-9's merchant-key doors; OQ-10, owner accepted default). The deadline is stamped at
   opening on the database clock, `opened_at + 14 days`, the window a field of the pinned terms version. The merchant
   answers over its key, tenant-isolated (`INV-MER-01`): `POST /v1/merchant/bnpl-disputes/{id}/response`, keyed
   `bnpl.dispute-response:MERCHANT:<id>` — **accept** (→ `RESOLVED_REFUND` at once) or **contest with evidence**
   (→ `MERCHANT_RESPONDED`). The response requires `deadline > statement_timestamp()` and the deadline sweep
   (`BnplDisputeSchedule`) requires `deadline <= statement_timestamp()`, both judged under the dispute's row lock —
   complementary conditionals on one clock, so exactly one wins. The merchant sees the dispute's amount, reason
   category and deadline and its own evidence — never the customer's schedule, repayments, delinquency or any credit
   figure (plan §11). Evidence documents follow `INV-DSP-03`'s regime by analogy: least privilege, encrypted at rest,
   every access audited; the storage shape is `P12-TSK-023`'s design.

5. **The operator's resolution** (`POST /v1/operator/bnpl-disputes/{id}/resolution`, `BNPL_DISPUTE_RESOLVE`, keyed
   `bnpl.servicing:EMPLOYEE:<id>`). Only from `MERCHANT_RESPONDED`; the outcome is binary — refund D or uphold —
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
    `BnplDisputeSchedule` the 29th lease-protected scheduler, gauged `…sweeper.enabled`.

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

Operational impact: the dispute meter, the deadline sweeper and its gauge; the dispute report.
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
- **Acceptance.** `Proposed` at the Phase 11 → 12 transition (2026-10-16); read against the code and accepted by the
  Phase 12 exit review (`P12-DOC-001`).
