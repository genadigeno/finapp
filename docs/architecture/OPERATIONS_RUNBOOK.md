# Operations Runbook

Procedures an operator performs on a running platform, each with **when**, **why**, the exact
steps, how to **verify**, and what happens if it is skipped. A procedure belongs here when the
platform cannot do it for itself by design — usually because it is a four-eyes decision, which a
migration or a scheduler must never take on a person's behalf.

Every procedure below runs through the platform's own audited doors. None is a raw `UPDATE`.

---

## 1. Activating a currency's reconciliation terms (JPY and BHD) — before its first traffic

*(Added by `P9-TSK-003`, 2026-10-04; owner decision O6 in `PHASE_9_PLAN.md` §2; ADR-0074 §9;
decision D26.)*

**When.** Once, after a deployment carrying ledger `V019` (JPY and BHD postable), and **before**
the first JPY or BHD payment, report or statement reaches any of the four settlement sources.

**Why.** A currency being postable (`SupportedCurrencies`) gives it the ledger chart; it does not
give reconciliation its per-currency terms. Those live in each source's **rule set**, which is
versioned, pinned on every decision (`INV-HIST-04`) and never seeded by a migration (D26): a
version is a four-eyes decision. Until each source's successor carrying JPY and BHD rows is
**active**:

- a JPY or BHD break grades at **base** severity — no `high_value_minor` row exists for it;
- a JPY or BHD fee line meets **no fee schedule**, which `FeeCheck` prices at **zero** — so every
  genuine fee raises a `FEE_MISMATCH` break for its whole amount;
- the PSP's fee tolerances read **zero**.

None of this loses value — every line is still owned and explained — but it floods the desk with
false breaks. Hence: activate first, then admit traffic.

**What each successor carries.** The source's **whole** active version restated (a proposal is a
full version, never a delta: every rule, lag, tolerance, fee schedule and threshold of its
predecessor), plus, for JPY (scale 0) and BHD (scale 3):

| Row | Sources | JPY | BHD |
|---|---|---|---|
| Severity threshold (`high_value_minor`) | all four | `150000` | `400000` (= 400.000) |
| `PROCESSING_FEE` schedule (rate as EUR's, `HALF_UP`) | PSP | fixed `40` | fixed `100` |
| `SCHEME_FEE` schedule | scheme | fixed `15` | fixed `40` |
| `PAYOUT_FEE` schedule | payout | fixed `40` | fixed `100` |
| `BANK_FEE` schedule | bank | fixed `75` | fixed `200` |
| `PROCESSING_FEE_PER_LINE` tolerance | PSP | `3` | `10` |
| `PROCESSING_FEE_PER_BATCH` tolerance | PSP | `75` | `200` |

Each fee schedule's **scale is its currency's minor units** — 0 for JPY, 3 for BHD. The door
refuses any other (`RuleSetInvalid`, since `P9-TSK-003`): `FeeCheck` prices in raw minor units, so
a JPY schedule at scale 2 would read its fixed part a hundred times too large.

**Steps** — for each source (`simulated-psp.settlement`, `simulated-scheme.cycle-report`,
`simulated-payout.settlement`, `simulated-bank.statement`), two different people holding
`RECONCILIATION_ADMINISTER`:

1. **Read** the active version: `GET /v1/operator/reconciliation/rule-sets?source=<sourceId>`.
2. **Propose** (person A): `POST /v1/operator/reconciliation/rule-sets` with an
   `Idempotency-Key`, the full version from step 1 plus the rows above, and a reason naming this
   procedure. The answer is the proposal's id and version (`PROPOSED`).
3. **Approve** (person B — the proposer is refused, `RuleSetActivationBySameActor`, at the domain
   and by the database's `CHECK`): `POST /v1/operator/reconciliation/rule-sets/{id}/approval`.
   In one transaction the predecessor moves `ACTIVE → RETIRED` and the successor
   `PROPOSED → ACTIVE`; both acts are audited (`reconciliation.RuleSetProposed`,
   `reconciliation.RuleSetActivated`).

**Verify.** Step 1 again shows the successor `ACTIVE` with the JPY and BHD rows. Decisions made
before activation keep their pinned version and replay `IDENTICAL`; decisions after it pin the
successor.

**If a proposal is wrong.** Reject it (`POST …/rule-sets/{id}/rejection`, reasoned) and propose
again. Once active, a version is never edited — a further successor corrects it.

---

## 2. Activating FX pricing policy v1 — before the first quote

*(Added by `P9-TSK-007`, 2026-10-04; owner decision O7 in `PHASE_9_PLAN.md` §2; ADR-0075 §7;
decision D26.)*

**When.** Once, after a deployment carrying `fx V004`, and **before** quoting is opened
(`P9-TSK-008`). Until a version is `ACTIVE` nothing can be priced: a quote finds no policy and is
refused - closed, never priced on a default.

**Why.** No migration seeds a pricing policy (D26): a price the platform charges is a four-eyes
decision with two named people, and a migration has neither. v1 is O7's values - 20 directional
pairs of EUR, GBP, USD, JPY and BHD under both purposes (40 rows): spread `0.003500` and markup
`0.001500`; rate scale 10 from JPY and 6 otherwise, the rate rounded towards zero, amounts and
the margin half-even; a 30 s window for a conversion and 60 s across a border, a 10 s cover
margin; a 150 bps band among EUR, GBP and USD and 300 bps for any JPY or BHD pair; the reference
at most 120 s old; five open quotes per customer; notional bounds EUR/GBP/USD 1.00-50,000.00,
JPY 100-7,500,000, BHD 0.500-20,000.000. The canonical body is `PricingPolicyV1` in the app's
tests (held to O7 by `PricingPolicyV1Test`, and proposed and activated end to end by
`FxAdministrationEndpointDatabaseTest`).

**Steps** — two different people holding `FX_CONTROLLER` (`FX_ADMINISTER`):

1. **Read**: `GET /v1/operator/fx/pricing-policies` — empty on a fresh deployment.
2. **Propose** (person A): `POST /v1/operator/fx/pricing-policies` with an `Idempotency-Key`, the
   40 rows (every decimal a JSON string) and a reason naming this procedure. The answer is the
   version's id (`PROPOSED`, version 1).
3. **Approve** (person B — the proposer is refused `fx.SelfApprovalRefused`, at the domain and
   by the database's `CHECK`): `POST /v1/operator/fx/pricing-policies/{id}/approval` with a
   reason. The version moves `PROPOSED → ACTIVE`; both acts are audited
   (`fx.PricingPolicyProposed`, `fx.PricingPolicyActivated`) and `fx.PricingPolicyActivated` is
   published.

**Verify.** Step 1 again shows version 1 `ACTIVE` with 40 rows.

**If a proposal is wrong.** Reject it (`POST …/pricing-policies/{id}/rejection`, reasoned) and
propose again. Once active, a version is never edited — a successor, approved the same way,
replaces it, and only new quotes see it.

**The kill switch.** One controller stops a pair or provider at once:
`POST /v1/operator/fx/pairs/{AAA-BBB}/availability` or `…/providers/{code}/availability` with
`{"available": false, "reason": …}`. Restarting is two people: the same route with
`"available": true` opens an enable request (`ENABLE_PROPOSED`), which a DIFFERENT controller
approves at `POST /v1/operator/fx/enable-requests/{id}/approval`.

## 3. Activating the FX provider source's rule set v1 - before the first cover settles

*(Added by `P9-TSK-011`, 2026-10-05; `PHASE_9_PLAN.md` §12.9.2, owner decision O7; decision D26.)*

**When.** Once, after a deployment carrying reconciliation `V020` and settlement `V015`, and before
the first FX provider report is expected (the first cover posts with `P9-TSK-012`). Until a version
is `ACTIVE`, `fx-sim-a.trade-report`'s parsed files wait `PARSED` with the accept leg's backoff
(`RuleSetMissing`) and `finapp.reconciliation.rule.set.missing{source="fx-sim-a.trade-report"}`
reads 1 and alerts - nothing from the source is matched, nothing is guessed.

**Why.** No migration seeds a new source's rule set (D26): how the platform matches a counterparty's
evidence is a four-eyes decision with two named people. v1 is the plan's: the legs `FX_SOLD` and
`FX_BOUGHT` `ONE_TO_ONE` keyed `COVER_REF`, grace 24 h, lag 2 days each; an `FX_FEE` rule of
cardinality `CHECK` (its original by `ORIGINAL_REF` = the cover reference) priced 0 + 0 in all five
currencies - the simulated provider bills nothing, so any reported FX fee is a `FEE_MISMATCH`;
`SETTLEMENT_DATE_DAYS` 2; no fee tolerance; high-value thresholds EUR/GBP/USD 1,000.00, JPY 150000,
BHD 400.000. The canonical body is `FxRuleSetV1` in the app's tests (held by `FxRuleSetV1Test`, and
proposed and activated by two controllers in `FxProviderSourceDatabaseTest`).

**Steps** - two different people holding the reconciliation controller role (§1's door, now
admitting a source's FIRST version):

1. **Read**: `GET /v1/operator/reconciliation/rule-sets?source=<fx-sim-a.trade-report's id>` - empty.
2. **Propose** (person A): `POST /v1/operator/reconciliation/rule-sets` with an `Idempotency-Key`,
   the source's code and v1's members, and a reason naming this procedure. The answer is version 1,
   `PROPOSED` - admitted because the source has no `ACTIVE` version (nothing to cover).
3. **Approve** (person B - the proposer is refused at the domain and by the database's `CHECK`):
   `POST /v1/operator/reconciliation/rule-sets/{id}/approval`. Version 1 moves `PROPOSED -> ACTIVE`,
   retiring nothing; both acts are audited (`reconciliation.RuleSetProposed`,
   `reconciliation.RuleSetActivated`, the latter naming it the source's first version).

**Verify.** The gauge reads 0 within 15 s, and a waiting file is accepted at its next backoff
deadline.

**The corridor source's v1** *(added by `P9-TSK-014`, 2026-10-05)* - the same procedure, once, after
a deployment carrying reconciliation `V021` and settlement `V016` and before the first cross-border
credit can settle (`P9-TSK-019`), for `corridor-sim-a.settlement`. v1 is the plan's: `PAYOUT_EXECUTED`
`ONE_TO_ONE` to `CROSSBORDER_PAYOUT` keyed `END_TO_END_REF` (our `E`), then `PAYOUT_PROVIDER_REF`,
grace 48 h; `PAYOUT_RETURNED` operation-anchored to `CROSSBORDER_RETURN` by either reference, grace
72 h; a `PAYOUT_FEE` rule of cardinality `CHECK` (its original by `ORIGINAL_REF` = the provider's
reference - the key every `PAYOUT_FEE`'s original is read by) priced 0 + USD 1.20 / JPY 180 /
BHD 0.450; lag 2 days for both kinds; `SETTLEMENT_DATE_DAYS` 2; no fee tolerance; high-value
thresholds USD 1,000.00, JPY 150000, BHD 400.000. The canonical body is `CorridorRuleSetV1` in the
app's tests (held by `CorridorRuleSetV1Test`, proposed and activated by two controllers in
`CorridorSourceDatabaseTest`).

## 3b. Activating the corridor policy v1 - before any corridor is offered

*(Added by `P9-TSK-015`, 2026-10-05; ADR-0080 section 4, owner decision O7, decision D26.)*

**When.** Once, after a deployment carrying crossborder `V002`. Until a version is `ACTIVE`,
`GET /v1/me/cross-border/corridors` offers nothing - no corridor exists without two named persons.

**What v1 is (O7).** EUR -> USD/US, EUR -> JPY/JP, USD -> BHD/BH and GBP -> USD/US, each on
`corridor-sim-a`; transfer fees EUR 2.50 / GBP 2.00 / USD 3.00 + 0 bps, rounded half-even; maxima
USD 10,000.00 / JPY 1,500,000 / BHD 4,000.000; screening valid 168 hours (7 days); delivery estimated at
24 hours; the beneficiary's name and entity type required. The canonical body is `CorridorPolicyV1` in
the app's tests (proposed and activated by two controllers in `CorridorAdministrationEndpointDatabaseTest`).

**The second corridor rail (`P9-TSK-026`).** To make `corridor-sim-b` a candidate, configure
`finapp.corridor.provider.b.url` and its key (`FINAPP_CORRIDOR_PROVIDER_B_KEY`), propose a corridor policy
successor listing it after `corridor-sim-a` for the corridors it covers (EUR -> USD/US), and a routing
version naming it after `corridor-sim-a` on the cross-border credit's rule; activate
`corridor-sim-b.settlement`'s rule set v1. `fx-sim-b` likewise: `finapp.fx.provider.b.url`,
`FINAPP_FX_PROVIDER_B_KEY`, a pricing policy successor listing it after `fx-sim-a`, and
`fx-sim-b.trade-report`'s rule set v1. Each remits in its own shape (`XBB-`, `FXB-`); its position is
its own and never nets with its sibling's.

**Steps** - two different people holding `FX_CONTROLLER` (which grants `CROSSBORDER_ADMINISTER`):

1. **Propose** (person A): `POST /v1/operator/cross-border/corridor-policies` with an `Idempotency-Key`,
   the corridors and a reason naming this procedure. A rail the build does not declare, or one not
   covering a corridor's destination, is `422 crossborder.RailNotDeclared`; a datum the platform does not
   hold is `422 crossborder.RequiredDataUnsatisfiable`.
2. **Approve** (person B - the proposer is refused at the domain and by the database's `CHECK`):
   `POST /v1/operator/cross-border/corridor-policies/{id}/approval`. The build is judged again; version 1
   moves `PROPOSED -> ACTIVE`, retiring nothing; `crossborder.CorridorPolicyActivated` is published.

**The kill switch.** `POST /v1/operator/cross-border/corridors/{S-D-CC}/availability` with
`{"available": false, "reason": ...}` stops a corridor at once (one person); `{"available": true}`
opens an enable request a different person approves at `…/corridor-enable-requests/{rid}/approval`.

**Verify.** Discovery lists the four corridors with their fees, maxima and delivery estimates.

## 4. An FX cover that will not conclude - UNKNOWN, refused requotes, off plan, anomalies

*(Added by `P9-TSK-012`, 2026-10-05; ADR-0077; `INV-FX-08`, `INV-LIFE-03`.)*

**Signals.** `finapp.fx.cover.unknown.active` and `.unknown.age` (covers sent and unanswered);
`finapp.fx.cover.open.age` (the oldest uncovered position); `finapp.fx.cover{outcome="requote_refused"}`,
`{outcome="off_plan"}` and `{outcome="anomaly"}`, each with an `ALERT:` log line naming the cover.

**What the platform already does.** An `UNKNOWN` cover is inquired on every sweep and re-sent under its SAME
reference only if the provider has never seen it - never concluded "never received", never re-sent under a
new reference. A refused requote (declined, unanswered, or outside the band against a fresh reference) leaves
the cover `REJECTED` and backs off (`requote-base x 2^min(failures, 6)`). An off-plan execution is booked
exactly - `FX_POSITION` closes, the difference is realised in that leg's currency - and flagged.

**What a person does.**
1. **UNKNOWN ageing:** confirm the provider is reachable (`finapp.fx.provider.*` meters); nothing is to be
   forced - the next inquiry concludes. Never edit a cover row: there is no manual execution (PHASE_9_PLAN.md
   section 11, "deliberately absent").
2. **Requote refused repeatedly:** check the reference feed's freshness (a stale reference refuses every
   requote, fail closed) and the provider's quotes against the band; a sustained market move beyond the band
   is a pricing-policy question for two FX controllers, not a cover edit.
3. **Off plan / anomaly:** raise it with the provider - an execution off its own firm quote, or one for a
   superseded reference, is a provider breach; the settlement line will reach reconciliation and be judged
   there (`AMOUNT_MISMATCH`/`FX_LEG_DIFFERS`, or `UNKNOWN_EXTERNAL`), resolved four-eyes.
4. **The FX source's v1 missing:** an executed cover cannot open its legs and its outcome refuses - fail
   closed; activate v1 (section 3) and the next sweep books it.

## 5. The FX proofs fail - a book unexplained, or a plan that does not replay

*(Added by `P9-TSK-013`, 2026-10-05; PHASE_9_PLAN.md section 12.9.4; `INV-FX-05`, `INV-FX-06`.)*

**Signals.** `finapp.fx.proof{purpose}` above 0 (currencies failing that FX book's identity) with a
`CRITICAL: the FX books proof fails` log line naming each book and currency; `finapp.fx.plan.verdict` at 0
with a `CRITICAL: FX trade ... does not replay` line naming the trade and what differs.

**What it means.** Not a reconciliation break - no external evidence states the platform's own spread,
position or residual. A failing book is value on an FX book that no conversion or cover explains (a raw line,
a defect in a composer); a failing replay is a stored plan the pricing function no longer reproduces from its
own stored inputs. Both are the platform's defect.

**What a person does.** Nothing is repaired automatically and nothing may be adjusted by hand: the FX books are
closed to free adjustment (`422 ledger.AdjustmentOnReconciledPosition`). Read the trade's provenance
(`GET /v1/operator/fx/trades/{id}/provenance`, `FX_INVESTIGATE`) and the book's entries; open an incident; the
only corrector of a booked conversion is the four-eyes trade reversal (`P9-TSK-025`).

## 6. Credit policy, scorecard and review operations (Phase 10)

*(Added by `P10-DOC-001`, 2026-10-09 - the entry ADR-0086 promised and `P10-TSK-011`/`-012` did not
write; ADR-0086 §4, ADR-0089, decision D26. Every name and number below is read from the code.)*

### 6.1 Bringing credit policy v1 and scorecard v1 into force - before the first decision

**When.** Once, after a deployment carrying credit `V006` and `V008`, before credit is offered.
Nothing is decided without an `ACTIVE` policy for the product **and** an `ACTIVE` `RETAIL_SCORECARD`:
- with no `ACTIVE` policy, a submission (`POST /v1/me/credit/decision-requests`) is refused
  `422 credit.ProductNotOffered`, and `finapp.credit.policy.active{product}` reads 0 - the
  `CreditPolicyMissing` alert (`min by (product) (finapp_credit_policy_active{product!~"bnpl_.*"}) == 0`
  for 15 min, ticket) - the lending products only: the BNPL products (`P12-TSK-001`) have no seed and
  production activates none (`PHASE_12_PLAN.md` §1.1), so their gauge reads 0 by design; `P12-TSK-026`
  owns BNPL's alerts;
- with a policy but no `ACTIVE` scorecard, a submission is admitted but its progress step waits at
  `SUBMITTED` (nothing to pin), and the request expires `EXPIRED` at its 7-day validity. **No gauge
  or alert reports a missing scorecard** - check it by its approval receipt (below).

**Why.** No migration activates a credit policy or a scorecard (D26). The migrations seed v1 *as
proposals* whose proposer is the reviewed migration itself - `RETAIL_SCORECARD` v1 by `credit V006`
(`proposed_by = 'migration:V006'`), `PERSONAL_LOAN` v1 and `CREDIT_LINE` v1 by `credit V008`
(`'migration:V008'`). The four-eyes `CHECK` compares the activator with the proposer, so **one
person** holding `CREDIT_POLICY_ADMINISTER` activates each seed, named as its decider. The seeded
ids are fixed:

| Version | Id |
|---|---|
| `RETAIL_SCORECARD` v1 | `0190a1b2-5c0e-7000-8000-00000000c001` |
| `PERSONAL_LOAN` policy v1 | `0190a1b2-5c0e-7000-8000-00000000d001` |
| `CREDIT_LINE` policy v1 | `0190a1b2-5c0e-7000-8000-00000000d002` |

v1's content is `V008`'s: both products EUR at scale 2, a 9% stress rate, EUR 100.00 minimum
disposable income, both source kinds read at most 30 days old, the unavailable-source fallback
`REFER`; `PERSONAL_LOAN` a 3% minimum payment ratio, EUR 40,000.00 maximum exposure and a
EUR 10,000.00 auto-approval ceiling; `CREDIT_LINE` 5%, EUR 20,000.00 and EUR 2,500.00; eleven rules
each (the outage fallback, insolvency, prior default, delinquency, the score floor and referral
band, affordability, the exposure limit, unverified income, a foreign currency, a low-score cap).

**Steps** - one person holding `CREDIT_POLICY_OFFICER` (which grants `CREDIT_POLICY_ADMINISTER` and
`CREDIT_INVESTIGATE`), each act with its own `Idempotency-Key` and a body `{"reason": "…"}` naming
this procedure:

1. **The scorecard**: `POST /v1/operator/credit/scorecards/0190a1b2-5c0e-7000-8000-00000000c001/approval`.
   The receipt shows `"status": "ACTIVE"`; audited `credit.ScorecardVersionActivated`,
   `credit.ScorecardModelVersionActivated` published.
2. **Each policy**: `POST /v1/operator/credit/policies/{d001 | d002 id}/approval`. Each receipt
   shows `"status": "ACTIVE"`; audited `credit.PolicyVersionActivated`,
   `credit.CreditPolicyVersionActivated` published.

A missing reason is `422 credit.ReasonRequired`, a missing key `422 api.IdempotencyKeyRequired`, a
second approval of a decided version `409 credit.PolicyStale`; an `UNDERWRITER` is `403`.

**Verify.** `GET /v1/operator/credit/policies?product=PERSONAL_LOAN` (and `CREDIT_LINE`) answers the
seeded id; `finapp.credit.policy.active{product}` reads 1 within 15 s and `CreditPolicyMissing`
clears.

**A later version** - always two people. Person A proposes the whole body,
`POST /v1/operator/credit/policies` (or `/v1/operator/credit/scorecards`) with a key and a reason;
person B, a **different** holder, approves at `…/{versionId}/approval` - the proposer is refused
`403 credit.SelfApprovalRefused`, at the domain and by the `CHECK`. The activation retires the
predecessor in the same transaction; requests already past `SUBMITTED` keep the versions they
pinned, only new ones see the successor. One proposal waits per product (or family): a second is
`409 credit.ProposalPending`. A policy without a fallback for every source kind it reads, with an
approving fallback, or with no rule guaranteed to refuse an approval past its maximum exposure is
`422 credit.PolicyIncomplete`; a malformed points table `422 credit.ScorecardInvalid`.

**If a proposal is wrong.** Any holder - the proposer included, as a withdrawal - rejects it,
`POST …/{versionId}/rejection` with a reason; then propose again. A version is never edited, and
there is no route that edits or deletes one.

### 6.2 The review queue - a referral nobody works is a silent decline

**The objective** (`CreditObjectives.REVIEW_AGE`): no `OPEN` underwriting case waits longer than a
day. `finapp.credit.review.age` is the oldest `OPEN` case's wait in seconds (database clock, cached
15 s, 0 when none waits); `CreditReviewAged` fires when `max(finapp_credit_review_age_seconds)`
exceeds 86400 for 30 min (ticket).

**What a person does** - holders of `UNDERWRITER` (`CREDIT_UNDERWRITE`), every act keyed:
1. Read the queue, oldest first: `GET /v1/operator/credit/review-cases` (optionally `?status=OPEN`).
2. Take a case: `POST /v1/operator/credit/review-cases/{id}/assignment` - of two underwriters one
   wins, the other `409 credit.CaseTaken`. Give it back with `…/release`.
3. Decide it: `POST …/{id}/decision` with `outcome` (`APPROVED` or `DECLINED`), for an approval the
   amount and currency, the catalogue's reason codes and a reason. A hard decline cannot be
   approved (`422 credit.HardDeclineNotOverridable`); an approval above what the evaluation allows
   or the party's exposure limit is `422 credit.ExposureLimitExceeded`.
4. **Four eyes above the product's threshold.** An approval above `PERSONAL_LOAN`'s EUR 10,000.00 or
   `CREDIT_LINE`'s EUR 2,500.00 (`CreditProduct.fourEyesThreshold`) is not recorded: the case moves
   `AWAITING_SECOND`, and a **different** underwriter answers
   `POST …/{id}/second-approval` with `{"decision": "APPROVE" | "REFUSE", "reason": …}` - the first
   decider is refused `403 credit.SelfApprovalRefused`; a refusal sends the case back to the first.

A case still `OPEN` when its request's 7-day validity ends closes with the request (`EXPIRED`); a
case someone holds is never expired under them, so release what you will not decide.

### 6.3 The other credit alerts (`infra/prometheus/rules/credit.yml`)

- **`CreditDecisionLatencyAboveObjective`** (page) - more than 1% of the platform's own decisions
  for a product took over 45 min from submission (`CreditObjectives.DECISION_LATENCY_P99`; a 30-min
  rate, for 15 min); a person's decision is not held to it. Check `finapp_credit_progress_sweeper_enabled`
  on every instance and `finapp_credit_request_open_age_seconds` by status for where requests stall.
- **`CreditDataUnavailable`** (ticket) - over a fifth of a source kind's answers unavailable for
  10 min. Decisions fall to each policy's fallback (`REFER`, so the review queue grows); read
  `GET /v1/operator/reports/credit/sources` (`CREDIT_INVESTIGATE`) for the provider. **In Phase 10
  this is expected on any traffic:** until unresolved questions #13 and #14 are answered no real
  provider may be configured (`CreditSourceOrder` refuses one at startup), every data request names
  the fail-safe (`bureau-none`, `findata-none`) and every answer is unavailable. Once providers can
  be named, a failing one is disabled with `finapp.credit.<bureau|findata>.disabled` - new births
  take the next in order, open requests keep the provider they were born naming (ADR-0085 §10).
- **`CreditRequestOpenPastValidity`** (page) - a request outside `IN_REVIEW` open past 7 days: the
  progress sweep is not expiring requests. Confirm it runs on some instance; the expiry is on the
  database clock and needs no hand.
- **`CreditDecisionDiverged`** (page) - a decision no longer replays from its sealed snapshot and
  pinned versions (`INV-CRD-01`). An incident: replay each named decision through
  `POST /v1/operator/credit/decisions/{id}/replay` (`CREDIT_INVESTIGATE`) to see what differs; never
  repair a row.
- **`CreditProgressSweeperOff`** / **`CreditDataRetrySweeperOff`** (ticket) - an instance runs with
  `finapp.credit.progress.sweeper.enabled` / `finapp.credit.data.retry.sweeper.enabled` false. The
  other instances carry the work; set it back to `true`.
- **`CreditGaugesUnreadable`** (ticket) - a credit gauge reads NaN (its database read failed): the
  replay proof, the open requests, the review queue or the active policies are unwatched until it
  reads again. Check the instance's database connectivity.

## 7. Lending (Phase 11): capital, terms, the activation gate and every lending alert

*Added by `P11-TSK-004` (ADR-0096 §4-6, `INV-LND-13`).* No loan can be accepted and no line drawn until the
platform's own money is recognised as lending capital in that currency - and capital is recognised only from the
settlement bank's statement, never typed.

### 7.1 Contributing capital

1. A `LENDING_TREASURY_OFFICER` proposes the contribution - `POST /v1/operator/lending/capital-contributions`
   (`Idempotency-Key`), currency, amount in minor units, the bank remittance reference the corporate transfer will
   carry (`LCAP-` and 4 to 12 digits, never used before), a reason. One proposal per currency awaits a decision at a
   time.
2. A **second** `LENDING_TREASURY_OFFICER` approves it - `POST …/{id}/approval` with a reason. The approval opens the
   contribution's reconciliation expectation; it creates **no** capital. The window it opens is the approval's date plus
   the settlement bank source's `funding_lag_days` (rule set pinned on the expectation).
3. Treasury sends the corporate transfer **after** the approval, carrying exactly that reference, for exactly that
   amount, to the settlement account.
4. When the bank's statement carrying the credit is accepted, bank recognition credits `LENDING_CAPITAL` and matches
   the contribution in the same transaction. `GET /v1/operator/lending/capital` (a `LENDING_OFFICER`, audited) shows
   the contribution `RECOGNISED` and the currency's recognised capital and headroom.

**What does not become capital.** A transfer that arrives before the approval, after the window (the contribution
then reads `LAPSED`), for another amount, with another reference, or a second time: each is an unattributed statement
credit - an `UNKNOWN_EXTERNAL(BANK_LINE_UNATTRIBUTED)` break with its suspense item - and is returned or otherwise
resolved through the normal break procedure. **It is never resolved into capital**: propose a new contribution with a
new reference and send the money again. A rejected contribution's reference stays spent.

### 7.2 The capital alerts

- **`finapp.lending.capital.proof{verdict="FAILS"}` above 0** (`LendingCapitalProofFailing`, page) - a `LENDING_CAPITAL` line no recognition
  explains, or headroom below zero at rest. Capital is never adjusted to fit: find the foreign entry (the CRITICAL log
  line names the currency; the capital report shows the terms) and open an incident. A repudiated bank statement that
  carried a recognised contribution also reports here - its reversal is a capital movement Phase 11 does not model.
- **`finapp.lending.capital.headroom.low` above 0** (`LendingCapitalHeadroomLow`, ticket) - a currency's headroom stands below
  `finapp.lending.capital.headroom-floor.<CCY>` (minor units, 0 when unset): contribute more capital before
  acceptances start refusing `422 lending.CapitalUnavailable`.
  *(Since `P12-TSK-005`: one pool - the headroom also falls by every unit of BNPL principal outstanding, so a busy BNPL
  book can bring this alert and refuse loans; the capital report's outstanding term is loan, line and BNPL principal
  together. BNPL purchases refuse on the same shortfall - `422 bnpl.CapitalUnavailable`, judged under the capital row's
  lock at the acceptance, built by `P12-TSK-013`.)* *(P12-DOC-001, 2026-10-22: this read "BNPL purchases will refuse on
  the same shortfall from `P12-TSK-013`" until the exit review.)*

### 7.3 Loan terms versions

*Added by `P11-TSK-005` (ADR-0092 §1-3, `INV-LND-05`, `INV-LND-09`).* A product is offered only while one of its terms
versions is `ACTIVE`, and **no migration activates one**: production starts with none,
`finapp.lending.terms.active{product}` reading 0. Bringing a product into force in production is gated
(`PHASE_11_PLAN.md` §1.1; the activation act itself is `P11-TSK-030`'s) - in a test environment:

1. A `LENDING_OFFICER` proposes a whole version - `POST /v1/operator/lending/terms` (`Idempotency-Key`): the product,
   the rate as a decimal fraction string, every fee, convention, window, engine version and the agreement template, and a
   reason. It is judged whole (`422 lending.TermsInvalid` names the defect) and frozen from that moment; one proposal per
   product awaits a decision at a time. A correction is a rejection and a new proposal.
2. A **second** `LENDING_OFFICER` activates it - `POST …/{versionId}/approval` with a reason - retiring the product's
   predecessor in the same transaction. The activation waits for any application or offer pinning the predecessor; every
   later offer pins the new version, and existing agreements keep theirs.
3. `GET /v1/operator/lending/terms?product=&at=` reads the version in force now, or at any past instant.

**`finapp.lending.terms.active{product}` at 0** for a product the environment offers is the `LendingTermsNoneActive` alert (enabled per
environment by the `lending_offered` target label, §7.4); NaN means the read failed, never "none".

### 7.4 The production activation gate - before the first terms version in production

*Added by `P11-TSK-030` (`PHASE_11_PLAN.md` §1.1, L11; ADR-0090).* **Production ships closed.** No migration activates a
terms version, so production starts with `finapp.lending.terms.active{product}` at 0 and every application answers
`422 lending.ProductNotOffered`. That is the expected state - not an incident - until **every** condition below is true,
in this order, each with its evidence recorded:

1. **Unresolved questions #13 and #14 are answered** (`CURRENT_STATE.md` §Unresolved questions): where a party's date of
   birth and country of residence come from (the bureau matches a person by them), and what consented account link a
   financial-data pull reads. Until both are answered `CreditSourceOrder` refuses a named provider at startup and every
   credit decision falls to the policy's fallback - `REFER`, and a person may not approve on an absent bureau balance
   (`422 credit.ExposureUnassessable`, R11) - so no production decision can be `APPROVED`.
2. **A real bureau adapter, contract-tested**: a `CreditBureau` for a real provider passing `CreditBureauContract` (the
   `bureau-sim-a`/`-b` precedent, `P10-TSK-006`, `-021`) with its golden files, configured in the provider order.
3. **A credit policy version reading it**: a `credit_policy_version` whose rules read that provider's attributes,
   proposed and four-eyes activated under §6.1 - never migration-activated.
4. **Legal review of the terms (A1)**: the products are a neutral EUR reference product with no consumer-credit-law
   features (L4); before production every term the agreement template states - rate, fees, conventions, documents - is
   reviewed for the jurisdiction it will be offered in, and the review's outcome recorded beside the terms version it
   approves.
5. **Lending capital recognised** in EUR (§7.1): `GET /v1/operator/reports/lending/capital?date=<today>` shows
   `recognisedMinor` covering the planned book and the headroom above `finapp.lending.capital.headroom-floor.EUR`; the
   capital proof holds (`finapp.lending.capital.proof{verdict="FAILS"}` at 0).
6. **A four-eyes terms activation** (§7.3): one `LENDING_OFFICER` proposes the reviewed version, a second activates it.
7. **The alert armed**: the environment's Prometheus scrape configuration labels the application targets
   `lending_offered: "true"`, so `LendingTermsNoneActive` watches the products from now on
   (`infra/prometheus/rules/lending.yml`).

Nothing here is a switch in code: each step is a person's recorded act. A step out of order is refused by the platform
where it can be (no decision is `APPROVED` before 1-3; no acceptance before 5) and by this procedure where it cannot (4, 7).

### 7.5 Interest accrual lags - `LendingAccrualLagging`

*Added by `P11-TSK-030`.* **Signal.** `max(finapp_lending_accrual_lag_seconds) > 86400` for 30 min (page): an account in
servicing has left an elapsed business date unaccrued for more than a day past its end (`LendingObjectives.ACCRUAL_LAG`).
**What it means.** The servicing sweep is not reaching an account: off on every instance (`LendingServicingSweeperOff`),
a step failing on every tick (`finapp_lending_accrual_total{outcome}` and the warn line naming only the exception's
class), or a pinned zone misread. **What a person does.** Nothing to the rows: accrual is born once per account and date
(`interest_accrual UNIQUE (loan_id, accrual_date)`, ledger key `lending.accrual:<loan>:<date>`), so the sweep catches
every missed date up by itself once it runs. Restore the sweep on at least one instance; if a step keeps failing, read the
accrual report (`GET /v1/operator/reports/lending/accrual?date=`) - which dates accrued, and that their totals tie to the
ledger - and the account's explanation (`GET /v1/operator/loans/{id}/explanation`, `LENDING_INVESTIGATE`), and open an
incident with the class name. A line accrues only dates that ended with principal drawn, and a path X loan accrues nothing
until its payout concludes (A30) - neither is a lag.

**Finding the stuck account** *(added by the Phase 11 completion gate, A5-03 - the lag names no loan, by design)*. A
servicing step that completes stamps its account's permit (`lending.loan.next_servicing_at`) at the next midnight in its
pinned zone; the claim stamps it the retry cadence ahead (`finapp.lending.servicing.retry`, 15 minutes) and a step that
fails leaves that claim's permit standing, so an account the step keeps failing on is re-claimed every cadence and its
permit never reaches a midnight. In a read-only session (`START TRANSACTION READ ONLY`):

```sql
SELECT l.id, l.product, l.status, l.next_servicing_at,
       (SELECT max(a.accrual_date) FROM lending.interest_accrual a WHERE a.loan_id = l.id) AS accrued_through
  FROM lending.loan l
 WHERE l.status IN ('ACTIVE', 'CLOSING')
   AND l.next_servicing_at <= statement_timestamp() + interval '15 minutes'
 ORDER BY l.next_servicing_at
 LIMIT 50;
```

Run it twice, one cadence apart: an id in both answers, its `accrued_through` not moving, is a step failing on every
tick (`finapp_lending_accrual_total{outcome="failed"}` counts each failure, an `AllocationAnomaly` in the step's collection
included - it is refused with the step, never counted as `LendingAllocationAnomaly`, which is a customer repayment's).
Explain that loan through `GET /v1/operator/loans/{id}/explanation` and open an incident with the warn line's class name.
The query reads ids and dates only; never update a permit or a row to move an account along. An account whose permit is
the next midnight is caught up; one stepped back across a midnight behind its own record (the Phase 11 gate's R1) is due
at the record's next date and is not stuck.

### 7.6 A stuck disbursement or payout - `LendingDisbursementPastDeadline`, `LendingPayoutUnconcluded`

*Added by `P11-TSK-030`.* **Signals.** `max(finapp_lending_disbursement_pending_age_seconds) > 86400` for 15 min (page): a
`PENDING` disbursement older than the longest deadline any terms version may name (`LoanProduct.disbursementDeadline()`,
24 h) - the sweep cancels a loan whose wallet cannot be posted by its own deadline (`DISBURSEMENT_FAILED`), so one pending
past the ceiling is not being stepped. `max(finapp_lending_payout_dispatched_age_seconds) > 1800` for 5 min (page): a
path X payout unconcluded thirty minutes after its birth (`LendingObjectives.PAYOUT_OUTCOME`).
**A disbursement.** Check `finapp_lending_disbursement_sweeper_enabled` on every instance and
`finapp_lending_disbursement_total{payout, outcome}` (`retrying` climbing: the wallet cannot be posted). Never post a
disbursement by hand and never move the loan's state: the sweep posts it once (`lending.disbursement:<loan>`) or cancels it
at the deadline, and a cancelled loan releases its committed capital.
**A payout.** The borrower's funds are already theirs - credited to the wallet and held - so nothing is lost; the platform
bears the ambiguity. Read the withdrawal in payments (`finapp_payments_withdrawal_unknown_age`; payments' resolution sweep
inquires a `DISPATCHED` withdrawal after its bound); a payout payments holds `UNKNOWN` concludes when payments learns the
outcome. **Never re-dispatch** - a second instruction could pay twice (scenario 5); the payout is re-driven only under its
own instruction, by the sweep. `NOT_DISPATCHED` releases the hold and starts interest; a `FAILED` withdrawal leaves the funds
in the wallet. The withdrawal's reconciliation is payments' and Phase 8's (§14).

### 7.7 A failing proof - subledger, replay, capital, census

*Added by `P11-TSK-030`; the carry from `P11-TSK-029`.* **Signals** (each page, `for: 1m`):
- `LendingSubledgerProofFailing` - `max(finapp_lending_subledger_proof{verdict="FAILS"}) > 0` (`INV-LND-01`): an account
  whose six ledger balances its rows do not explain - a line no row names, a balance on its wrong side, a closed account
  not zero. The CRITICAL log line counts the failing accounts per arm (`LOAN_PRINCIPAL`, `UNEXPLAINED_ENTRIES`,
  `NEGATIVE`, `TERMINAL_NOT_ZERO`, ...) - never which loan.
- `LendingReplayDiverged` - `max(finapp_lending_replay{verdict="DIVERGED"}) > 0` (`INV-LND-05`, `INV-LND-09`): a figure
  that no longer re-derives by the engine version its row pinned, or canonical terms that no longer hash to their stored
  value. The CRITICAL line counts the diverged accounts per kind (`ACCRUAL`, `SCHEDULE`, `TERMS`, ...).
- `LendingCapitalProofFailing` - `max(finapp_lending_capital_proof{verdict="FAILS"}) > 0` (`INV-LND-13`): §7.2.
- `CreditExposureCensusFailing` - `max(finapp_credit_exposure_census{verdict="FAILS"}) > 0` (`INV-CRD-09`,
  `INV-LND-06`): a party whose reserved, committed and outstanding principal exceeds the limit each deciding policy
  declared.

**Which accounts.** `GET /v1/operator/reports/lending/portfolio?date=<today>` (`LENDING_INVESTIGATE`, recorded) carries
the proofs read on its own snapshot: `proofs.failingLoans` and `proofs.divergedLoans` (bounded at 100, `truncated` when
more). **What a person does.** Investigate, never correct: explain each account (`GET /v1/operator/loans/{id}/explanation`
- every row, every journal entry on its six accounts with the row that names it, the proof's six figures) and replay it
(`POST /v1/operator/loans/{id}/replay` with a reason - `IDENTICAL` or the kinds that diverged); an investigator never
reads their own party's loan (`lending.SelfDealingRefused`). The loan ledger accounts are closed to free adjustment
(`422 ledger.AdjustmentOnReconciledPosition`) and no row is ever updated to make a proof hold. Open an incident; the only
correctors of a booked figure are the four-eyes acts (repayment reversal, waiver, amendment), and only where the
investigation shows the act, not the record, was wrong. A census failure is credit's (§6) and lending's together: never
release exposure by hand.
**The reading's cost** (the `-029` carry, decided by `-030`): each scraped instance reads every account's whole record per
reading, behind `finapp.lending.proofs.refresh` (15 min by default, never below 60 s); a verdict is cached until the next
reading, so the alerts' `for: 1m` holds. When one reading approaches the floor - the scrape that refreshes it slows by the
reading's duration - lengthen the floor; the partitioned or incremental proof is the recorded debt (`CURRENT_STATE.md`
§Architectural debt), due before the book reaches that size.

### 7.8 An allocation anomaly - `LendingAllocationAnomaly`

*Added by `P11-TSK-030`.* **Signal.** `sum(increase(finapp_lending_allocation_anomaly_total[15m])) > 0` (page): a
repayment whose allocation did not conserve its amount was refused - nothing posted, the customer's money untouched in
their wallet. **What a person does.** An engine or its inputs are wrong: replay the loan (§7.7) and open an incident; the
customer may retry once it is fixed. Never allocate by hand.

### 7.9 Low capital headroom - `LendingCapitalHeadroomLow`

*Added by `P11-TSK-030`.* **Signal.** `max(finapp_lending_capital_headroom_low) > 0` for 15 min (ticket): a currency's
headroom below `finapp.lending.capital.headroom-floor.<CCY>`. Read the amounts in
`GET /v1/operator/reports/lending/capital?date=<today>` - recognised, committed, outstanding, headroom, lending funded -
never in a series. **What a person does.** Contribute capital under §7.1 (four eyes, bank-recognised) before acceptances
and draws start refusing `422 lending.CapitalUnavailable`; repayments restore headroom at commit, and a cancelled
disbursement releases its commitment.

### 7.10 Sweepers off, gauges unreadable, terms missing

*Added by `P11-TSK-030`.* **`LendingOriginationSweeperOff`, `LendingDisbursementSweeperOff`, `LendingPayoutSweeperOff`,
`LendingServicingSweeperOff`** (ticket): an instance runs with `finapp.lending.<sweep>.sweeper.enabled` false. The four
sweeps are leaderless - every instance claims with `FOR UPDATE SKIP LOCKED` - so the others carry the work; set it back to
`true`. **`LendingGaugesUnreadable`** (ticket): a lending gauge reads NaN - its database read failed - so the proofs, the
census, the queues, the lag or the active terms are unwatched until it reads again; NaN is never read as 0 or as the last
value. Check the instance's database connectivity. **`LendingTermsNoneActive`** (ticket; only where the target is labelled
`lending_offered="true"`): an offered product has no `ACTIVE` terms - activate a version under §7.3.

### 7.11 The lending reports, and the failure scenarios they answer

*Added by `P11-TSK-030`.* `GET /v1/operator/reports/lending/{portfolio,delinquency,accrual,capital}?date=YYYY-MM-DD`
(`LENDING_INVESTIGATE`): the amounts lending never publishes as a series, each as of the end of one business date - every
account on its own date in its pinned zone - read in one read-only `REPEATABLE READ` snapshot and recorded
`lending.ReportRead` before the answer. A malformed or missing date is `422 api.ValidationFailed`, unrecorded; a date the
platform has not had is answered empty (`known: false`), recorded. The portfolio ties the book to the ledger
(`tiesToProducts`) and the trial balance through the date (`trialBalanceZero`): either false is an incident under §7.7.

| Failure scenario (`PHASE_11_PLAN.md` §14) | Alert | Procedure |
|---|---|---|
| Timeout and retry; a commit whose response is lost | none - the key replays the stored answer | - |
| A service crashes mid-step | the step's queue or lag alert if it stalls: `LendingApplicationAwaitingTooLong` and `LendingOriginationStepFailing` for origination and offer expiry (added by the Phase 11 completion gate - before it, a failing origination step had no alert, only a warn line), `LendingDisbursementPastDeadline` and `LendingPayoutUnconcluded`, `LendingAccrualLagging` for servicing | §7.12, §7.6, §7.5 |
| An event duplicated, late or missing | none - events are hints, rows decide | - |
| A provider unavailable or answering unknown | `LendingPayoutUnconcluded` | §7.6 |
| A webhook duplicated | none - payments' and the inbox's dedupe | - |
| Two requests race | `LendingSubledgerProofFailing`, `CreditExposureCensusFailing`, `LendingCapitalProofFailing` were an arbiter to fail | §7.7 |
| Settlement late; reconciliation breaks on a payout | payments' and Phase 8's alerts | §7.6 |
| A capital contribution never arrives at the bank | `LendingCapitalHeadroomLow` | §7.1, §7.9 |
| The accrual job crashes or runs twice | `LendingAccrualLagging` (twice: nothing - born once) | §7.5 |
| A repayment for a closed loan | none - `409 lending.LoanNotRepayable`, nothing posted | - |
| Clock skew across the accrual boundary | `LendingReplayDiverged` were a date misjudged | §7.7 |
| A payoff races a repayment or the delinquency worker | `LendingAllocationAnomaly`, `LendingSubledgerProofFailing` | §7.7, §7.8 |
| A sweeper off, a gauge unreadable, an offered product without terms | the four `…SweeperOff`, `LendingGaugesUnreadable`, `LendingTermsNoneActive` | §7.10 |

### 7.12 A stalled origination sweep - `LendingApplicationAwaitingTooLong`, `LendingOriginationStepFailing`

*Added by the Phase 11 completion gate (A5-03).* **Signals.** `max(finapp_lending_application_awaiting_age_seconds) >
608400` for 30 min (ticket): an application has waited `AWAITING_DECISION` longer than the longest credit request validity
and an hour (`LendingObjectives.awaitingDecision()`, seven days and an hour) - credit closes a request at its validity at
the latest and the origination step then closes the application undecided, so it is waiting on a sweep that is not acting.
`sum(increase(finapp_lending_origination_total{outcome="failed"}[15m])) > 0` for 15 min (ticket): origination steps
(`kind="application"`) or offer expiries (`kind="offer"`) failing and rolling back for a quarter hour running - each lets
its permit lapse and a later tick retries, so one failing on every tick was, before this alert, only a warn line naming
the exception's class. **What a person does.** Check `finapp_lending_origination_sweeper_enabled` on every instance
(§7.10) and the warn lines (`Loan origination step failed`, `Loan offer expiry failed`) for the class; a transient refusal
(a database clock stepped back - ADR-0063) clears by itself within a tick. Nothing to the rows: every edge is a
conditional the triggers re-judge, so a step that runs again does each act once. Credit's side (§6 - a request still
open, a review case unworked) says whether credit or the sweep is the one waiting; open an incident with the class name if
the failures persist.

## 8. BNPL (Phase 12): the activation gate and every BNPL alert

*Added by `P12-TSK-026` (`PHASE_12_PLAN.md` §1.1, §15; ADR-0101…ADR-0106).* BNPL draws on lending's capital (ADR-0104 - one
pool, §7.1, §7.9) and credit's decisions (§6), so it inherits both their closed production state and their procedures;
this section adds BNPL's own. Every alert below is `infra/prometheus/rules/bnpl.yml`'s; its thresholds are
`BnplObjectives`'. No procedure here edits a row, adjusts a balance or re-posts an entry: BNPL's figures change only by
their machines and the four-eyes acts (a repayment reversal, a fee waiver, a dispute resolution).

### 8.1 The production activation gate - before the first BNPL terms version in production

**Production ships closed.** No migration activates a BNPL terms version, so production starts with
`finapp.bnpl.terms.active{product}` at 0 for both products and no application can be submitted: one at a merchant not
BNPL-enabled and priced for the product answers `422 bnpl.MerchantNotEnabled`, and one at an enabled merchant
`422 bnpl.ProductNotOffered` (`PHASE_12_PLAN.md` §1.1, accepted limitation on the debt row "Lending cannot originate in
production (#13, #14)"). *(P12-DOC-001, 2026-10-22: this read "every application answers `422 bnpl.ProductNotOffered`" -
the application door judges the merchant's standing before the terms (`BnplApplications.submit`), so until step 4 a
customer meets `bnpl.MerchantNotEnabled` first.)* That is the expected state - not an incident - until **every**
condition below is true, in this order, each with its evidence recorded:

1. **Lending's §7.4 preconditions, each met** - unresolved #13 and #14 answered; a real bureau adapter contract-tested; a
   credit policy version reading it, four-eyes activated, deciding the BNPL products too (credit's `BNPL_PAY_IN_4` and
   `BNPL_PAY_IN_N`, `P12-TSK-001`); and lending capital recognised in EUR (§7.1) with headroom covering the planned loan
   **and** BNPL books together (`GET /v1/operator/reports/lending/capital?date=<today>`; the capital proof holding). Until
   1-3 hold no credit decision can be `APPROVED`, so no BNPL offer can be made.
2. **Legal review of the merchant agreement**: the terms on which a merchant is financed - the gross paid less the
   merchant discount, refunds after payout recovered from the payable (B7, the amended `INV-MER-07`), the dispute
   response window - reviewed for the jurisdiction, the outcome recorded beside the merchant fee schedule version it
   approves.
3. **Legal review of the consumer terms**: every term the BNPL agreement template states - the plans, the pay-in-N rate,
   the late fee and its cap, the allocation order, the delinquency bounds, the dispute window, the documents - reviewed
   for the jurisdiction it will be offered in (the products are a neutral EUR reference with no consumer-credit-law
   features), the outcome recorded beside the terms version it approves.
4. **The merchant-fee schedule approved**: the BNPL fee schedule versions (`refund_fee_policy = RETURNED`, the pro-rated
   return of B3) approved by the business and assigned per merchant and product by a `FEE_ADMINISTER` holder
   (`PUT /v1/operator/merchants/{merchantId}/bnpl-fee-schedules/{product}`; a schedule with a version that retains its fee
   is refused `422 merchant.BnplScheduleNotReturning`); each merchant BNPL-enabled under four eyes by two
   `MERCHANT_ADMINISTER` holders (`POST /v1/operator/merchants/{merchantId}/bnpl-enablement`, then `…/approval`; the
   proposer's own approval is `409 merchant.SelfApprovalRefused`). *(P12-DOC-001, 2026-10-22: the business's approval of
   the schedule is this procedure's, not the platform's - a fee schedule and its versions are created by one
   `FEE_ADMINISTER` holder (`POST /v1/operator/fee-schedules`, `…/{id}/versions`) with no second person in code; the
   platform enforces the `RETURNED` policy, the four-eyes enablement, and that a product the merchant has no version in
   force for is not offered - `422 bnpl.MerchantNotEnabled`.)*
5. **A four-eyes BNPL terms activation**: one `BNPL_OFFICER` (the role holding `BNPL_ADMINISTER`) proposes the reviewed
   version (`POST /v1/operator/bnpl/terms`, `Idempotency-Key`, a reason - judged whole, `422 bnpl.TermsInvalid` or
   `bnpl.TermsNotAmortising` naming the defect), a **second** activates it (`POST …/{versionId}/approval`, a reason),
   retiring any predecessor in the same transaction; `GET /v1/operator/bnpl/terms?product=` reads it back.
6. **The alert armed**: the environment's Prometheus scrape configuration labels the application targets
   `bnpl_offered: "true"`, so `BnplTermsNoneActive` watches both products from now on.

Nothing here is a switch in code: each step is a person's recorded act. A step out of order is refused by the platform
where it can be (no offer before 1; no acceptance without capital, `422 bnpl.CapitalUnavailable`; no purchase at a merchant
not enabled, `422 bnpl.MerchantNotEnabled`; no self-activation, `403 bnpl.SelfApprovalRefused`) and by this procedure
where it cannot (2, 3, the business's approval in 4, 6). *(P12-DOC-001, 2026-10-22: read "(2, 3, 6)" until the exit
review.)*

### 8.2 Accrual lags or fails - `BnplAccrualLagging`, `BnplAccrualFailing`

**Signals.** `max(finapp_bnpl_accrual_lag_seconds) > 86400` for 30 min (page; `BnplObjectives.ACCRUAL_LAG`, the `-017`
hand-off): an `ACTIVE` pay-in-N obligation has left an elapsed date of its accrual span unaccrued for more than a day past
its end. `sum(increase(finapp_bnpl_accrual_total{outcome="failed"}[15m])) > 0` for 15 min (ticket): T-acc calls failing
and rolling back on every tick. **What it means.** The servicing sweep runs T-acc in a transaction of its own before every
step; a lag is the sweep off everywhere (`BnplServicingSweeperOff`), a call failing (the warn line
`BNPL accrual failed` names only the exception's class; a failed call skips its obligation's step until the cadence
retries it, so it is not also a `BnplServicingStepFailing`), or a pinned zone misread. Pay-in-4 accrues nothing - it is never a lag. **What a person does.**
Nothing to the rows: accrual is born once per obligation and date (`bnpl_interest_accrual`, ledger key
`bnpl.accrual:<obligation>:<date>`), so the sweep catches every missed date up by itself once it runs. Restore the sweep
on at least one instance. To find a stuck obligation, in a read-only session (`START TRANSACTION READ ONLY`):

```sql
SELECT o.id, o.agreement_id, o.next_servicing_at,
       (SELECT max(a.accrual_date) FROM bnpl.bnpl_interest_accrual a WHERE a.obligation_id = o.id) AS accrued_through
  FROM bnpl.bnpl_obligation o
 WHERE o.status = 'ACTIVE' AND o.product = 'BNPL_PAY_IN_N'
   AND o.next_servicing_at <= statement_timestamp() + interval '15 minutes'
 ORDER BY o.next_servicing_at
 LIMIT 50;
```

Run it twice, one retry cadence (`finapp.bnpl.servicing.retry`, 15 minutes) apart: an id in both answers whose
`accrued_through` does not move is failing on every tick. Explain its agreement
(`GET /v1/operator/bnpl-agreements/{agreementId}/explanation`, `BNPL_INVESTIGATE`) and open an incident with the warn
line's class. Never update a permit or a row to move an obligation along.

### 8.3 A failing proof - three-way, subledger, replay

**Signals** (each page, `for: 1m`):
- `BnplThreeWayProofFailing` - `max(finapp_bnpl_threeway_proof{verdict="FAILS"}) > 0` (gate criterion 5): a currency in
  which the BNPL purchases are not proven three ways. The CRITICAL log line counts the failing currencies per arm -
  `MERCHANT_LEG` (a merchant's Σ (G - f) less its refunds' payable debit not what its payable's `bnpl.` lines hold, an
  entry no financing or refund names, or `INV-MER-02`'s equation broken), `CUSTOMER_LEG`, `ECONOMIC_IDENTITY`,
  `TRIAL_BALANCE`, `CAPITAL`, `EXPOSURE` - never which merchant or purchase.
- `BnplSubledgerProofFailing` - `max(finapp_bnpl_subledger_proof{verdict="FAILS"}) > 0` (`INV-BNPL-01`): an obligation
  whose six ledger balances its rows do not explain. The CRITICAL line counts per arm (`BNPL_PRINCIPAL`,
  `UNEXPLAINED_ENTRIES`, `NEGATIVE`, `TERMINAL_NOT_ZERO`, …).
- `BnplReplayDiverged` - `max(finapp_bnpl_replay{verdict="DIVERGED"}) > 0` (`INV-BNPL-04`, `-05`, `-09`): a figure that no
  longer re-derives by its pinned engine, or canonical terms that no longer re-verify. The CRITICAL line counts per kind
  (`SCHEDULE`, `ACCRUAL`, `ALLOCATION`, `REFUND`, `TERMS`, …).

**Which ones.** `GET /v1/operator/reports/bnpl/portfolio?date=<today>` (`BNPL_INVESTIGATE`, recorded `bnpl.ReportRead`)
carries the three proofs read on its own snapshot: `proofs.failingAgreements` and `proofs.divergedAgreements` (bounded
at 100, `truncated` when more) and, per currency, `threeWayHolds` with `threeWayFailures` (the arms);
`GET /v1/operator/reports/bnpl/merchant-financing?date=<today>` adds the merchant leg's `merchantLegsFailing` and whether
the exposure ties to the payables' lines. **What a person does.** Investigate, never correct: explain each agreement
(`GET /v1/operator/bnpl-agreements/{id}/explanation` - every row and journal entry, the subledger proof's figures) and
replay it (`POST …/replay` with a reason - `IDENTICAL` or the kinds that diverged); an investigator never reads their own
party's agreement (`403 bnpl.SelfDealingRefused`). The BNPL ledger accounts are closed to free adjustment and no row is
ever updated to make a proof hold. A merchant-leg failure is merchant's and bnpl's together - read the merchant's
drill-down (`MerchantPayable`'s BNPL terms); a capital or exposure arm is §7.7's. **The reading's cost**: each scraped
instance reads every obligation's whole record per reading, behind `finapp.bnpl.proofs.refresh` (15 minutes by default,
never below 60 s) - lengthen it if a reading approaches the floor (the debt row records the partitioned proof). Each
report that carries the proofs pays one reading too.

### 8.4 An allocation anomaly - `BnplAllocationAnomaly`

**Signal.** `sum(increase(finapp_bnpl_allocation_anomaly_total[15m])) > 0` for 1 min (page): a repayment's, an automatic
collection's, a payoff's or a refund's allocation did not conserve its amount (the engine's assertion, or the database's
deferred conservation at commit) and was refused - nothing posted, the customer's and the merchant's money untouched. A
servicing step's anomaly is also a failed step (`BnplServicingStepFailing`), a dispute deadline step's also
`BnplDisputeDeadlineStepFailing`. *(P12-DOC-001, 2026-10-22: a dispute's refund - the operator's resolution and the
deadline sweep's - counts the engine's assertion only; a refusal by the database's deferred conservation at commit there
is not counted here (the sweep's is a failed step), unlike the refund, repayment and payoff doors and the servicing step.
Recorded as a finding of the exit review.)* **What a person does.** An engine or its
inputs are wrong: replay the agreement (§8.3) and open an incident; the act may be retried once it is fixed. Never
allocate by hand.

### 8.5 A negative merchant payable from a refund after payout (B7) - `MerchantPayableNegative`

**Signal.** `max(finapp_ledger_negative_positions{purpose="MERCHANT_PAYABLE"}) > 0` for an hour (ticket): a merchant's
payable stands below zero. The gauge counts every merchant payable below zero, whatever drove it there: a card
chargeback after a payout or a retained refund fee (`INV-MER-07` and its Phase 7 amendment, payments' and merchant's
procedures) raise this alert as well. *(P12-DOC-001, 2026-10-22: added - the section read as if only a BNPL refund
could.)* **What it means** for BNPL. A BNPL refund after the merchant was paid out debits the payable the refund's
net (`R - r_f`) - legal within the amended `INV-MER-07` bound (Σ of its purchases' refund exposure), never absorbed by
the platform - and the debt is recovered from the merchant's later captures and financings before any payout
(`INV-MER-05`): the payout refuses while the payable is negative. **What a person does.** Read the amounts in the
merchant-financing report (`refundedMinor`, `payableDebitMinor`, `exposureMinor` - per currency) and the merchant's own
drill-down; the position is within the bound by construction - a refund never exceeds what remains of its purchase
(`422 bnpl.RefundExceedsPurchase`), so the debt is at most the refunds of purchases already paid out.
Nothing to post: recovery is automatic as the merchant trades. A merchant that stops trading with a negative payable is a
collections matter (Phase 13), not an adjustment. A `CUSTOMER_WALLET` below zero is not this alert's - it is payments'.

### 8.6 A stalled sweep - `BnplApplicationAwaitingTooLong`, `BnplOriginationStepFailing`, `BnplServicingStepFailing`, `BnplDisputeDeadlineStepFailing`

**Signals.** `max(finapp_bnpl_application_awaiting_age_seconds) > 608400` for 30 min (ticket): an application has waited
`AWAITING_DECISION` longer than the longest credit request validity and an hour (`BnplObjectives.awaitingDecision()`) -
credit closes a request at its validity at the latest and the origination step then closes the application undecided, so
it waits on a sweep that is not acting (the Phase 11 gate's A5-03 applied forward). The three failed-step counters - one per
sweep, each `sum(increase(…[15m])) > 0` for 15 min (ticket): origination steps or offer expiries
(`finapp_bnpl_origination_total{outcome="failed"}`, by `kind`), servicing steps
(`finapp_bnpl_servicing_total{step="service",outcome="failed"}`), dispute deadline steps
(`finapp_bnpl_dispute_total{outcome="sweep_failed"}`). Each failure rolls back alone and lets its permit lapse; a later
tick retries. **What a person does.** Check the sweep's `…sweeper.enabled` on every instance (§8.7) and the warn lines
(`BNPL origination step failed`, `BNPL offer expiry failed`, `BNPL servicing step failed`, `BNPL dispute deadline step
failed`) for the class; a transient refusal (a database clock stepped back) clears within a tick. Nothing to the rows:
every edge is a conditional the triggers re-judge, so a step that runs again does each act once. A dispute whose deadline
step fails leaves the customer's refund waiting, its held instalments taking no collection, days past due or late fee
meanwhile; **the deadline step is the only way such a dispute concludes** - the operator door
(`POST /v1/operator/bnpl-disputes/{id}/resolution`) resolves only a dispute the merchant answered and refuses an `OPEN`
one `409 bnpl.DisputeNotActionable` - so restore the step. A deadline step whose refund cannot be posted now
(`finapp_bnpl_dispute_total{outcome="not_postable"}`, the same warn line) is retried by the cadence and is **not** counted
by `BnplDisputeDeadlineStepFailing`: read that series too when a lapsed dispute's refund is overdue. Open an incident with
the class name if the failures persist. *(P12-DOC-001, 2026-10-22: this advised resolving a lapsed dispute "by the operator
door ... if the failure persists" - the door refuses an unanswered dispute (`BnplDisputes.resolve`); and the warn line of a
failing offer expiry was missing.)*

### 8.7 Sweepers off, terms missing

**`BnplOriginationSweeperOff`, `BnplServicingSweeperOff`, `BnplDisputeSweeperOff`** (ticket, for 5 min): an instance
runs with `finapp.bnpl.<sweep>.sweeper.enabled` false (`origination`, `servicing`, `dispute`). The three sweeps are leaderless - every instance claims with
`FOR UPDATE SKIP LOCKED` on its permit - so the others carry the work; set it back to `true`. **`BnplTermsNoneActive`**
(ticket, for 15 min; only where the target is labelled `bnpl_offered="true"`): an offered product has no `ACTIVE` terms - activate a
version under §8.1 step 5. NaN means the read failed, never "none" (§8.8).

### 8.8 Gauges unreadable, the BNPL reports, and the failure scenarios they answer

**`BnplGaugesUnreadable`** (ticket, for 15 min): a BNPL gauge reads NaN - its database read failed - so the proofs, the accrual lag,
the application queue or the active terms are unwatched until it reads again; NaN is never read as 0 or as the last value
(the lending precedent - the entry's "reports its last value" refined: a last value would read as healthy). Check the
instance's database connectivity.

**The reports.** `GET /v1/operator/reports/bnpl/{portfolio,merchant-financing,refunds,delinquency}?date=YYYY-MM-DD`
(`BNPL_INVESTIGATE`): the amounts BNPL never publishes as a series, each as of the end of one business date - every
obligation on its own date in its pinned zone - read in one read-only `REPEATABLE READ` snapshot and recorded
`bnpl.ReportRead` before the answer. A malformed or missing date is `422 api.ValidationFailed`, unrecorded; a date the
platform has not had is answered empty (`known: false`), recorded; a read that fails is an error and records nothing.
- **portfolio** - obligations by product and status, the six balances and the outstanding; per currency the book from
  the ledger's `BNPL_OBLIGATION` accounts (`tiesToProducts`), the trial balance (`trialBalanceZero`) and the three-way
  verdict; the proofs' counts and agreements. Either tie false is an incident under §8.3.
- **merchant-financing** - per currency the purchases financed, gross, merchant discount, net financed, the
  refunds by then, the exposure (net less the payable debited), the same from the payables' lines (`tiesToLedger`) and the
  failing merchant legs.
- **refunds** - the refunds valued on the date by product, currency, kind and door: principal cancelled, interest and
  late fees released, the excess to the wallet or the credit balance, the fee returned, the payable debited, and the
  impact on outstanding as their own entries left it (`tiesToLedger`).
- **delinquency** - `ACTIVE` obligations by recorded bucket, the defaulted among them, and their due sums from the ledger.

| Failure scenario (`PHASE_12_PLAN.md` §14) | Alert | Procedure |
|---|---|---|
| Timeout and retry; a commit whose response is lost (an acceptance) | none - the key replays the agreement id | - |
| A service crashes mid-T-pur | none - nothing committed; the offer stays acceptable until it expires | - |
| A sweep step crashes or keeps failing | `BnplApplicationAwaitingTooLong`, `BnplOriginationStepFailing`, `BnplServicingStepFailing`, `BnplDisputeDeadlineStepFailing`, `BnplAccrualLagging`, `BnplAccrualFailing` | §8.6, §8.2 |
| An event duplicated, late or missing | none - hints only; sweeps read rows | - |
| A merchant payout provider unavailable or unknown | merchant's and payments' alerts; BNPL unaffected | §7.6 by analogy, payments' |
| A webhook duplicated | none - payments' dedupe; BNPL consumes none | - |
| Two requests race | `BnplThreeWayProofFailing`, `BnplSubledgerProofFailing`, `CreditExposureCensusFailing`, `LendingCapitalProofFailing` were an arbiter to fail | §8.3, §7.7 |
| Settlement late; reconciliation breaks on a BNPL-financed payout | Phase 8's alerts; the agreement is unaffected | Phase 8's |
| A refund after the merchant was paid out | `MerchantPayableNegative` | §8.5 |
| A merchant suspended with open BNPL purchases | none - obligations serviced unchanged, new BNPL refused `422 bnpl.MerchantNotEnabled` | - |
| A customer's wallet closed | none - collection records `WALLET_NOT_POSTABLE`, DPD runs; the delinquency report shows it | §8.8 |
| Capital exhausted | `LendingCapitalHeadroomLow` (shared) | §7.9 |
| A failed instalment, a late fee, a default at 90 | none - the machines' own; the delinquency report | §8.8 |
| A repayment reversal or a fee waiver | none - four-eyes acts, recorded | - |
| An early payoff racing a refund | `BnplAllocationAnomaly`, `BnplSubledgerProofFailing` were an arbiter to fail | §8.4, §8.3 |
| Clock skew across the accrual boundary | `BnplReplayDiverged` were a date misjudged | §8.3 |
| A sweeper off, a gauge unreadable, an offered product without terms | the three `…SweeperOff`, `BnplGaugesUnreadable`, `BnplTermsNoneActive` | §8.7, §8.8 |
