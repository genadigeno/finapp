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
   reason, on a `MULTI_FACTOR` session (`403 identity.AssuranceRequired` below it, since 2026-11-09). The version moves `PROPOSED → ACTIVE`; both acts are audited
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
approves at `POST /v1/operator/fx/enable-requests/{id}/approval`, on a `MULTI_FACTOR` session. **An `FX_CONTROLLER` with no
enrolled factor is refused `403 identity.AssuranceRequired` at every approval and restart here and in §3b**, and
`GET /v1/operator/access-review/factorless-holdings` does not name that role, so enrol controllers ahead of need: enrolment
is self-service (`POST /v1/me/mfa`, then `POST /v1/authentications/mfa` to step up).

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
   `POST /v1/operator/cross-border/corridor-policies/{id}/approval`, on a `MULTI_FACTOR` session. The build is judged again; version 1
   moves `PROPOSED -> ACTIVE`, retiring nothing; `crossborder.CorridorPolicyActivated` is published.

**The kill switch.** `POST /v1/operator/cross-border/corridors/{S-D-CC}/availability` with
`{"available": false, "reason": ...}` stops a corridor at once (one person); `{"available": true}`
opens an enable request a different person approves at `…/corridor-enable-requests/{rid}/approval`, on a
`MULTI_FACTOR` session.

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
only corrector of a booked conversion is the four-eyes trade reversal (`P9-TSK-025`), proposed and approved on
`MULTI_FACTOR` sessions.

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
  `SUBMITTED` (nothing to pin), and the request expires `EXPIRED` at its 7-day validity. Since
  `P15-TSK-025`, `finapp.credit.scorecard.awaited` counts those requests and the `CreditScorecardMissing` alert
  (`max(finapp_credit_scorecard_awaited) > 0` for 15 min, ticket) says so within minutes rather than at the week's end
  (*until then: "No gauge or alert reports a missing scorecard"*). Activate version 1 as below.

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

### 7.13 A write-off: refused, stale, or approved in error (`P14-TSK-021`)

*Loans here; a BNPL obligation is the same under `bnpl.WriteOff*` (`POST /v1/operator/bnpl-agreements/{id}/write-offs...`).* A write-off is an act on our books: **it says nothing about what the customer owes, and no one should tell a customer it does.**

- **`lending.WriteOffStale`.** Something moved since the proposal: a repayment, an accrual, a late fee, a default cured and set again. Nothing was posted. An accrual moves the figures daily, so decide a proposal on the business date it was made. Otherwise reject it and propose again; the next proposal reads what is there.
- **`lending.WriteOffBehindAccrual`.** An elapsed date is not accrued: the account's next servicing step brings it; if `LendingAccrualLagging` is firing, follow that first (section 7.5).
- **`lending.WriteOffCreditHeld`.** The account holds the customer's money; a write-off never takes it. The next servicing step applies it.
- **`ledger.PeriodClosed` at the approval.** Today's month is locked. Nothing was posted, the proposal stands, and it is never moved to another date: decide it once the month holding today is open, or reject it.
- **`lending.LoanWrittenOff` from a customer's repayment.** Refused, nothing taken: the money stays in their wallet. No recovery rule exists; do not post one by hand.
- **Approved in error. No way back is built.** Nothing leaves `WRITTEN_OFF`, and the expense is closed to free adjustments, so `LEDGER_ADJUST` cannot undo it: **do not try, and do not write to the rows.** Open an incident naming the loan, the write-off and its journal entry, and bring it to the owner: the reversal is an owner item (`P14-TSK-021`'s record). Meanwhile the entry stands and the books state the loss; what the customer owes is unchanged and the customer's read still shows it; the credit exposure still counts it; the headroom is low by the principal, never high. Report the error in the month's close as a known item.
- **A merchant asks why `bnpl.RefundNotPostable`.** `finapp.bnpl.refund{outcome="written_off"}` counts these. Never tell a merchant the customer's agreement was written off: it is the customer's credit, not the merchant's to read. The refund cannot post until `PI-007` is built; the money stays on the merchant's payable.
- **A customer calls to pay** (`P14-TSK-022`'s review). The notice says "Please contact us about repaying it", and **no door can take that money** until `PI-006` is built: a repayment, a payoff and a prepayment are each refused (`lending.LoanWrittenOff`, `bnpl.ObligationWrittenOff`), nothing taken.
  - **Read**: the status and what stands owed, at `GET /v1/operator/loans/{id}` (`LOAN_SERVICE`) or `GET /v1/operator/bnpl-agreements/{id}` (`BNPL_INVESTIGATE`).
  - **Say**: that we cannot take a repayment on this account yet; that their money stays in their wallet, none of it taken and theirs to use; that what stands owed is unchanged; and that someone will come back to them, with no date.
  - **Never**: take a repayment by another route, whether a transfer, a card over the phone or a wallet debit; post, adjust or write to any row; quote a settlement, or say that interest or anything else stops.
  - **Escalate**: an incident to the owner naming the account, the write-off and that the customer offered to pay, with no card or account number in it, against `PI-006`.
- **A customer contests the debt** (`P14-TSK-022`). The notice beside their `WRITTEN_OFF` status, and the refusal when they try to dispute (`409 bnpl.ObligationWrittenOff` at `POST /v1/me/bnpl-agreements/{id}/disputes`), tell them: contact us and a person will review it, with no outcome promised. They may be someone who never received the goods. **You review; you cannot settle.**
  - **Read**, each audited: the account at `GET /v1/operator/loans/{id}` (`LOAN_SERVICE`, the servicing agent) or `GET /v1/operator/bnpl-agreements/{id}` (`BNPL_INVESTIGATE`, the officer); every version, row and journal entry with its proof at `.../explanation` (`LENDING_INVESTIGATE`, `BNPL_INVESTIGATE`, the desk's officer); who proposed the write-off, why, and on what figures at `.../write-offs/{writeOffId}` (`LOAN_WRITE_OFF_APPROVE`, `BNPL_WRITE_OFF_APPROVE`, the officer). What your role does not hold, the desk's officer reads for you. An employee never reads or acts on their own party's account.
  - **Say**: that a person has it and it will be reviewed, that a write-off is an entry in our books, and what stands owed as their own read shows it.
  - **Never**: promise an outcome or a date; say it is paid, settled, forgiven or cancelled; take a repayment by another route; post, adjust or write to any row (`LEDGER_ADJUST` cannot reach the expense, and must not try); open a dispute by hand; tell the merchant the customer's agreement was written off, even to ask whether the goods arrived.
  - **Escalate**: nothing here settles it. A write-off has no way back (`PI-005`) and a written-off agreement has no refund rule and no dispute door (`PI-007`), so open an incident naming the account, the write-off, its journal entry and what the customer says, with no card or account number in it, and bring it to the owner, who decides against those two until they are built. Tell the customer it has gone further; do not say to whom or with what result.
- **`WriteOffApproved`**, `finapp.lending.write.off{outcome="approved"}` or `finapp.bnpl.write.off{outcome="approved"}` rising, is a financial event an operator expects to know of, no break: it stays firing for an hour after the last approval, per product. **`WriteOffSelfDealingRefused`**, `outcome="self_dealing_refused"`, is a signal for the security desk: an operator acted on their own party's account and was refused, and the audit record names who.
- **How many customers were sent to a person.** `finapp.bnpl.dispute{outcome="written_off"}` counts a dispute refused on a written-off agreement, each one told to contact us (`P14-TSK-020`). No alert reads it: nothing is broken, and no queue exists to send it to until `PI-007` names one. A customer who retries counts again, since nothing is stored under their key.

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

### 8.6 A stalled sweep - `BnplApplicationAwaitingTooLong`, `BnplOriginationStepFailing`, `BnplServicingStepFailing`, `BnplDisputeDeadlineStepFailing`, `BnplSweepClaimFailing`

**A claim that cannot be made** (`P15-TSK-025`): `BnplSweepClaimFailing` (ticket) counts a failed page read - the claim, usually a database that is not answering - by sweep, in `finapp.bnpl.sweeper.claim.failed{kind}`. The three step-failing alerts below stay silent while nothing is claimed, since no step ever runs; this is the alert that sees it. It clears by itself when a later tick claims. If it does not, read the `… sweep failed` warn line for the class and read the database first.

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
by `BnplDisputeDeadlineStepFailing`: it is `BnplDisputeRefundNotPostable`'s, §8.9 *(the Phase 12 → 13 gate's A7-1,
2026-10-22: until then it was alerted by nothing)*. Open an incident with the class name if the failures persist. *(P12-DOC-001, 2026-10-22: this advised resolving a lapsed dispute "by the operator
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
the application queue, the contested disputes' wait (`finapp_bnpl_dispute_contested_age_seconds`, the Phase 12 → 13 gate's
A7-2) or the active terms are unwatched until it reads again; NaN is never read as 0 or as the last value
(the lending precedent - the entry's "reports its last value" refined: a last value would read as healthy). Check the
instance's database connectivity. *(`P15-TSK-024`'s fix round, 2026-11-08: it reads the negative merchant payables too,
`finapp_ledger_negative_positions`, which `MerchantPayableNegative` compares; and since that task a gauge is also NaN while its
instance's gauge refresher is behind - `GaugesStale` on the same instance says so, section 11.8.)*

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
| A customer's wallet closed | none - collection records `WALLET_NOT_POSTABLE`, DPD runs; the delinquency report shows it. *(The Phase 12 → 13 gate's A7-1: a wallet cannot close while an open BNPL dispute may refund to it, and a dispute is not opened on a `CLOSED` agreement without an `ACTIVE` wallet - `409 bnpl.WalletUnavailable`.)* | §8.8, §8.9 |
| A dispute's refund cannot be posted (a lapse against a closed payable or wallet) | `BnplDisputeRefundNotPostable` | §8.9 |
| A contested dispute no operator resolves | `BnplDisputeContestedTooLong` | §8.9 |
| Capital exhausted | `LendingCapitalHeadroomLow` (shared) | §7.9 |
| A failed instalment, a late fee, a default at 90 | none - the machines' own; the delinquency report | §8.8 |
| A repayment reversal or a fee waiver | none - four-eyes acts, recorded | - |
| An early payoff racing a refund | `BnplAllocationAnomaly`, `BnplSubledgerProofFailing` were an arbiter to fail | §8.4, §8.3 |
| Clock skew across the accrual boundary | `BnplReplayDiverged` were a date misjudged | §8.3 |
| A sweeper off, a gauge unreadable, an offered product without terms | the three `…SweeperOff`, `BnplGaugesUnreadable`, `BnplTermsNoneActive` | §8.7, §8.8 |

### 8.9 A dispute that cannot conclude - `BnplDisputeRefundNotPostable`, `BnplDisputeContestedTooLong`

*(Added by the Phase 12 → 13 transition gate's repair R1, 2026-10-22 - FINDINGS A7-1 and A7-2: a lapse whose refund could
not post was retried for ever with no alert, and a contested dispute had no deadline, queue, event, gauge or alert.)*

**`BnplDisputeRefundNotPostable`** (ticket, `sum(increase(finapp_bnpl_dispute_total{outcome="not_postable"}[1h])) > 0` for
15 min). A dispute's refund outcome was refused as not postable: the merchant's payable or the customer's wallet cannot take
it. **Why it matters.** The deadline sweep retries a lapsed dispute every cadence (`finapp.bnpl.dispute.retry`, 15 min) and
the dispute stays `OPEN` - its held instalments taking no collection, days past due or late fee, the merchant unable to close,
and the refund owed recorded nowhere but the dispute itself. A door's refusal (the merchant's acceptance, an operator's
resolution) answered `409 bnpl.RefundNotPostable` and wrote nothing; the caller may retry under the same key. **What a person
does.**
1. List the lapsed disputes still waiting: `GET /v1/operator/bnpl-disputes?status=OPEN` (`BNPL_DISPUTE_RESOLVE`, recorded
   `bnpl.DisputeQueueRead`) - those whose `responseDeadline` has passed are the deadline step's; read one with
   `GET /v1/operator/bnpl-disputes/{id}` for its purchase (both refuse a dispute of the reader's own party).
2. Find which account refuses. On an `ACTIVE` agreement the refund needs only the merchant's payable (an excess goes to the
   wallet or, failing it, to the credit balance); on a `CLOSED` agreement it can go **only to the customer's wallet**
   (`INV-BNPL-09`: no credit balance after closure). Since this gate a wallet cannot close while one of its customer's
   disputes is open (`AccountClosing`'s pending-credits arm, `BnplWalletCredits`) and a dispute is not opened on a `CLOSED`
   agreement without an `ACTIVE` wallet (`409 bnpl.WalletUnavailable`), so a refusal now means the payable is closed or the
   customer was off-boarded (no live customer, so no wallet) - or a dispute opened before this gate.
3. Restore what the refund needs - the customer opens a wallet (the refund then posts to it at the next retry), or the
   merchant's payable is re-opened under merchant's own procedure. Never post the refund by hand and never edit the dispute:
   the next deadline step posts it once (`UNIQUE (dispute_id)`, ledger key `bnpl.refund:<refund>`) and the alert clears an
   hour after the last refusal.
4. If no account can be restored, open an incident: the customer is owed the dispute's amount and the platform has no rail
   to pay it - an owner decision (FINDINGS P item 3, "a refund on a CLOSED obligation without a wallet").

**`BnplDisputeContestedTooLong`** (ticket, `max(finapp_bnpl_dispute_contested_age_seconds) > 604800` for 30 min,
`BnplObjectives.CONTESTED_DISPUTE`, a week). A dispute its merchant contested (`MERCHANT_RESPONDED`, announced by
`bnpl.BnplDisputeContested`) has awaited an operator longer than a week. It has **no deadline** - only an operator concludes
it (auto-resolution at a deadline is the owner's choice and is not built) - and meanwhile its held instalments are never
collected, aged or charged, every refund of its purchase is refused `409 bnpl.DisputeOpen` and its merchant cannot close.
**What a person does.** Work the queue oldest first:
`GET /v1/operator/bnpl-disputes?status=MERCHANT_RESPONDED` (at most 100 a page; pass the page's `next` as `after` for the
next), read each (`GET /v1/operator/bnpl-disputes/{id}` - the customer's reason and the merchant's statement) and resolve it
(`POST /v1/operator/bnpl-disputes/{id}/resolution`, `REFUND` or `UPHOLD`, reasoned; above the pinned four-eyes threshold -
judged on the purchase's cumulative refunds by dispute since this gate - a second operator approves). A NaN reading is
`BnplGaugesUnreadable`'s (§8.8).

## 9. Risk, fraud and AML (Phase 13): the activation gate and every risk alert

*Written from the code by `P13-TSK-024` (2026-10-26) over the transition's outline (2026-10-22; `PHASE_13_PLAN.md` §1.1,
§12.7, §12.9, §14, §15; ADR-0107…ADR-0112, `Proposed`); §9.4 and §9.6 keep the as-built notes of `-021` and `-022`,
reviewed here.* Risk judges money movements inside their owners' transactions and watches the ledger's journal after
them; it moves no money, holds none and releases none of its own (`INV-RSK-02`) - every procedure here ends in a risk
record and, where an effect follows, in a keyed command through the owning domain's existing door. Every alert below is
`infra/prometheus/rules/risk.yml`'s (thirty rules, each naming the invariant it watches - the `invariant` annotation -
and the section here that answers it), each a count, an age, a verdict or a gauge - never an amount, a party, a subject
or an id (`INV-AUD-02`, `INV-RSK-08`: what an operator reads in an alert can be read aloud on a bridge call without
tipping anyone off). No procedure here edits a row, re-posts an entry, deletes an alert or re-opens a case: risk's figures
change only by their machines and the four-eyes acts (a policy activation, an override, a disposition, a report approval,
a downstream action, a list version). The doors below are the operator API under `/v1`, each act audited and every
`POST` door but the two replays keyed by an `Idempotency-Key`; the dashboard's risk row (`finapp-platform.json`) reads
every `finapp.risk.*` series named here. *(P13-DOC-001, 2026-10-27: this read "each audited" and "every series named
here": the two policy reads - `GET /v1/operator/risk/policies?operation=` and `…/policies/{versionId}` - are not
audited, and the platform's `finapp.inbox.consumption`, which `InboxConsumptionFailing` reads (§9.12), is on no panel.)*
The phase's owner items and debt rows, which several steps below wait on, are listed in one place: `PHASE_13_PLAN.md`
§1.2.

### 9.1 The production activation gate - before the first risk policy version in production

**Production ships closed to judging.** No migration activates a risk policy version (C8), so production starts with
`finapp.risk.policy.active{operation}` at 0 for every operation, and every seam answers `ALLOW` with the reason code
`NO_POLICY_ACTIVE` - **recorded as an assessment** (the control ran; it had no policy), so the first activation changes
behaviour and not the shape of any record (`PHASE_13_PLAN.md` §1.1). The monitoring sweep runs from the first deployment
and raises no alert until a `MONITORING` version is active; profiles stay `UNRATED` (`finapp.risk.profile.rating`). That
is the expected state - not an incident - until **every** condition below is true, in this order, each with its evidence
recorded in the gate's record (one per policy version admitted: the operation, the version number and its id):

1. **The battery's fingerprint recorded for the version**: `P13-TST-002`'s policy battery run against the exact version
   to be activated (its rules, weights, score bands, fail-safe bands and override threshold - a version is immutable once
   proposed, `INV-RSK-05`) and its committed fingerprint written into the gate's record beside the version's id; a
   version whose battery has not run, or whose fingerprint differs from the committed one, is not admitted.
   *How it is produced and recorded (as built by `P13-TST-002`, 2026-10-26):* the battery is `risk`'s four hermetic suites,
   run at full size - `./gradlew :risk:test --tests "com.finapp.risk.RiskBattery*" --tests
   "com.finapp.risk.RiskPolicyBatteryTest" --rerun` with `FINAPP_RISK_BATTERY_SUBJECTS` unset (a shrunk run fails the
   census and can never be the gate's). **What it proves today is the C8 v1 fixtures, and nothing else**: it judges a
   version as the battery states it (`RiskBatteryOracles.Statement`) and requires the policy under test equal to that
   statement, but only the amount bands' thresholds and the fail-safe edges are read from the statement - every other
   planned edge (the velocity figures, the lookbacks, the account age, structuring, dormancy, round amounts, the ratio
   figures), the coverage floors' rule positions, the successor versions and the oracles' hand cases are written for C8's
   values. **A production version's fingerprint means nothing until** a reviewed test change gives the battery that
   version's statement and fixture, generator edges at its thresholds, floors for its rows and a successor written against
   it, and the run is green at full size. A green run prints one line per version - `P13-TST-002 VERSION: <OPERATION>
   v<n> content=<sha256> fingerprint=<sha256>` - the content digest being the battery's canonical text of the version
   (rules, parameters, weights, hard decisions, bands, fail-safe bands, challenge, override threshold, hold validity) and
   the fingerprint binding it to every verdict of the 10,000-subject population; and `P13-TST-002 FINGERPRINT: ...
   fingerprint=<sha256>` over all of them. The record carries the version's id, its `content` and `fingerprint` from that
   run, the commit the run was on, and the reviewer's read-back of `GET /v1/operator/risk/policies/{versionId}` (the
   version by id, in any state) against the statement, rule by rule - the version row carries no content digest of its
   own (`PHASE_13_PLAN.md` §1.2 item 69). *(P13-DOC-001, 2026-10-27: this read `GET /v1/operator/risk/policies?operation=`
   - the in-force door, which before the activation answers the predecessor or `409 risk.PolicyNotActive`, never the
   version under review.)* The
   committed figures today are the C8 fixtures' (test content; production activates none): the battery
   `d470990578800637e77550116ba5147c77704b02c587e84f99d74a27566683c8`, each version's in `BACKLOG.md`'s `P13-TST-002`
   record.
2. **The storm's latency figures within budget**: `P13-TST-001`'s storm on the release - T-eval's p99 at or below 50 ms per
   operation (`RiskEvaluationLatencyOverBudget`'s objective, `RiskObjectives.EVALUATION_LATENCY_P99`) with the
   monitoring sweep running beside it (M3's split trigger), the figures copied into the record. *(P13-DOC-001,
   2026-10-27: **this step cannot be met today, so it blocks production activation.** `P13-TST-001` is POSTPONED by the
   owner until after the project's phases (`POSTPONED_ISSUES.md` PI-004; parked on branch `P13-TST-001` at `e353e11c`,
   its partial ninth run green on all 57 census checks but never finished - no latency figure, no probe, no final run),
   so no p99 of T-eval under concurrent load, with or without the sweep beside it, has been measured (M3's split
   triggers unmeasured, `PHASE_13_PLAN.md` §1.2 item 76). The evidence that exists is each task's hermetic proofs, the
   owed database suites (run for the first time in the owner-authorised database pass, 2026-10-27, green after test-only
   repairs), the production censuses and proofs with their gauges (`AssessmentCensus`, `MonitoringCensus`,
   `VelocityProof`, `RescreeningCensus`, `FilingCensus`, `AlertBornOnceCensus`, `CaseOneOpenCensus`; §9.11) and the
   independent reviews - none of it a latency figure. Until the storm runs the step stands unmet, and the activation
   waits on it unless the owner's written allowance (step 8) names it deferred - the owner's call, not this runbook's;
   `RiskEvaluationLatencyOverBudget` (§9.7) is then the only live measure.)*
3. **The fail-safe bands (C1) reviewed and signed**: per operation and value band, what a movement does when risk is
   unavailable - internal transfers `ALLOW` recorded below EUR 1,000 and `DENY` at or above; wallet withdrawals and
   cross-border `DENY` at every band; checkout card and wallet payments `ALLOW` recorded below EUR 250 and `DENY` at or
   above - a field of the version, changed by activation, never by code. A fail-open band lets a movement through
   unjudged, a fail-closed band refuses it; either is a recorded, counted decision (`RiskEvaluationUnavailable`).
   **Signed** by the owner and the `RISK_OFFICER` holders, with the two known consequences acknowledged in writing: every
   version is EUR, so a movement in another currency meets the open-ended band (`DENY` where the band refuses; corridors
   with no EUR leg always `DENY`) until per-currency versions exist (owner items, `-004` MINOR 3, `-011` MINOR 2); and the
   monitoring policy's weight-only `ROUND_AMOUNT` alerts for every round-amount payer, because every fired AML kind
   alerts (`-020` MINOR 4) - its weight reviewed, or the rule left out of the version, knowingly. The SLA per case kind
   (`SlaDeadlines`), the queues' staffed capacity and the alert-flood objective (`RiskObjectives` - defaults) are
   reviewed in the same signature. **The version's rules read for reachability** in the same review (`P13-TST-002`'s
   findings, `PHASE_13_PLAN.md` §1.2 item 72): a rule whose reading no request of its operation can carry never fires and
   protects nothing - a velocity dimension the operation's seam does not carry (a `SOURCE_ACCOUNT` or `DESTINATION` window
   on `CARD_PAYMENT`: a card payment carries neither), and today `NEW_DEVICE` and `RECENT_AUTH_FAILURES` on every operation
   (no seam carries a device or authentication failures yet) - each such row removed, or kept knowingly and named in the
   signature. *(P13-DOC-001, 2026-10-27: **the owner items this signature meets, each to be decided - or accepted in
   writing - before the activation; none is decided here** (`PHASE_13_PLAN.md` §1.2): item 1 (every version is EUR, so
   a non-EUR movement meets the open-ended band and is `DENY`ed once a EUR version is active); item 10 (corridors with no
   EUR leg always `DENY` under a EUR `CROSS_BORDER` version); item 12 (one currency per (dimension, subject, window): a
   multi-currency wallet party reads `READ_FAILED` - the fail-safe - until its windows roll); item 75 (a failure of the
   pin's read answers the compiled `ALLOW`, `RISK_UNAVAILABLE`, for every operation - `WITHDRAWAL` and `CROSS_BORDER`
   included - so the bands signed here do not govern it; §9.7); item 77 (a hot subject's evaluation queued on its
   subject locks past the 250 ms read budget becomes a `TIMEOUT` fail-safe judged by the amount band alone, its count
   possibly `UNCOUNTED` - concurrent bursts can pass a velocity limit, against `INV-CON-03`); item 83 (a `PROHIBITED`
   rating refuses a movement only when the active version carries a `PROFILE_RATING` → `DENY` rule); item 72 (the dead
   rules above); item 92 (the monitoring intake's 60 s settle horizon is unenforced: a posting committing later than it
   after its id was minted is skipped until an operator orders a reprocessing, §9.3, §9.11).)*
   *(**The Phase 13 completion gate, 2026-10-29 - the owner's ratification ("Accept recommendations") turned these items
   into activation preconditions.** The activation waits on each of these `BACKLOG.md` §Phase 13 remediation entries
   being `COMPLETE`: **`P13-REM-009`** (items 1, 10, 12 - a pinned EUR conversion), **`P13-REM-010`** (item 75 - an
   unreadable pin answers `DENY` for withdrawals and cross-border, and every `ACTIVE` version decodes under the release's
   build before it ships), **`P13-REM-011`** (item 77 - a hot subject's queued evaluations cannot outrun velocity),
   **`P13-REM-012`** (items 83, 72 - `PROHIBITED` refused whatever the version; a dimension the operation never carries
   refused at the door), **`P13-REM-006`** (item 21 - a four-eyes lift of `PROHIBITED`); and on **`P13-REM-013`**'s
   decisions written into this signature - item 86 (one risk role per identity, or ratified; step 5), items 36/66 (the
   AML kinds and weights, the queues' capacities, the flood objective) and item 92 (the settle horizon, ratified as the
   recorded bound unless the owner bounds it). Production use - the case desk is live from the first deployment, before
   any activation - waits on `P13-REM-001`…`-005`, `-007` and `-008`; items 79, 78 and 25 were fixed by the gate itself
   (risk `V019`, identity `V024`).)*
4. **The reporting jurisdiction decided (C4) or the simulation explicitly accepted**: the reference remains the neutral
   EUR one and `RegulatoryFilingProvider` is simulated (`SimulatedFilingProvider`, `finapp.risk.filing.simulation`;
   `PHASE_13_PLAN.md` §12.9). Either a real jurisdiction is chosen - its reporting format, deadlines and content rules
   brought in as adapter data, verified against the official text, with legal review of the report template and of the
   tipping-off handling recorded - or the owner records in writing that this environment files to the simulation and
   that no suspicious-activity report reaches an authority from it (the simulation explicitly accepted).
5. **The roles held by named persons, pairwise disjoint**: `RISK_ANALYST` {`RISK_INVESTIGATE`, `RISK_CASE_APPROVE`},
   `RISK_OFFICER` {`RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`} and `AML_OFFICER` {`AML_REPORT_APPROVE`, `AML_REPORT_FILE`}
   (`PHASE_13_PLAN.md` §11) each granted to **named persons**, at least two per role so every four-eyes act has its second
   person (`INV-RSK-07`, `INV-AUD-04`), and the **pairwise disjointness verified** on the live grants: no person holding
   two of the three roles, no holder whose own party is a subject on the desk they work (the self-dealing refusal is the
   platform's backstop, not the plan). The list of names and the grant read-back go into the record; the gate is the
   trust procedure for these roles. *(P13-DOC-001, 2026-10-27: this read "one live role per identity (identity
   `V010`)": `V010`'s `role_assignment_one_live_per_identity` is unique on (identity, role) - one live assignment **of
   each role** - so one identity may hold `RISK_ANALYST`, `RISK_OFFICER` and `AML_OFFICER` at once; nothing in the
   platform holds the three populations apart, and this step's read-back is the only control (`PHASE_13_PLAN.md` §1.2
   item 86, for the owner's ratification).)*
6. **The four-eyes rehearsal performed**: in the environment, before the real version, the two `RISK_OFFICER` holders
   propose and activate a rehearsal version of one operation, the proposer's own activation refused `403
   risk.SelfApprovalRefused` and recorded `FAILED`; an override proposed and approved by two `RISK_OVERRIDE` holders; a
   disposition approved by a second `RISK_ANALYST`; a report approved and filed by two `AML_OFFICER` holders (to the
   simulation) - each act read back from the audit trail. The rehearsal version is then retired by the real one's
   activation.
7. **The tipping-off guards green on the release**: the build of the exact release carries `NoCustomerSurfaceReadsRiskTest`,
   `RiskMetricTagsAreBoundedTest`, `RiskLogSitesAreEnumeratedTest`, `RiskEventsCarryOnlyCodesTest` and
   `RiskAlertRulesTest` green (`INV-RSK-08`) - their run's identifier copied into the record.
8. **The owner's written allowance**: the owner reads 1-7 and allows, in writing, the named version to be activated in
   production - the open owner items of `PHASE_13_PLAN.md` §1.2 that this activation does not wait on named as accepted.
9. **The activation by two holders**: one `RISK_OFFICER` (the role holding `RISK_POLICY_ADMINISTER`) proposes the
   allowed version (`POST /v1/operator/risk/policies`, `Idempotency-Key`, a reason - judged whole, `422
   risk.PolicyInvalid` naming the defect), a **second** activates it from a `MULTI_FACTOR` session (`POST
   …/policies/{versionId}/activation`, `Idempotency-Key`, a reason), retiring any predecessor in the same transaction; `GET
   /v1/operator/risk/policies?operation=` reads it back and `finapp.risk.policy.active{operation}` reads its number. The
   proposer's own activation is `403 risk.SelfApprovalRefused`, recorded `FAILED`; a version no longer awaiting a decision
   `409 risk.PolicyStale`. The `MONITORING` version the same way, after its own steps 1-8.
10. **The alert armed**: once every operation the environment is meant to judge has its version `ACTIVE`, the
   environment's Prometheus scrape configuration labels the application targets `risk_judging: "true"`, so
   `RiskPolicyNoneActive` watches every operation from then on (a judging target with an operation at 0, or a movement
   recorded `NO_POLICY_ACTIVE`, pages). *(P13-DOC-001, 2026-10-27: "every operation" is six - `finapp.risk.policy.active`
   is registered for every `RiskOperation` but `CREDIT_SIGNAL`: the five judged operations and `MONITORING` - and the one
   label arms the rule for all of them, so a labelled target with any of the six unactivated pages; label only once all
   six are `ACTIVE` (`PHASE_13_PLAN.md` §1.2 item 67).)*

Nothing here is a switch in code: each step is a person's recorded act. A step out of order is refused by the platform
where it can be (no evaluation under a version not `ACTIVE`; no self-activation; no filing of an unapproved report, `409
risk.ReportNotFileable`) and by this procedure where it cannot (1-8, 10). **`RiskPolicyNoneActive`** after step 10 means a
version was retired with no successor, or a new operation came in unjudged: activate the reviewed version (step 9),
never relabel the target to silence it.

### 9.2 The risk alerts (`infra/prometheus/rules/risk.yml`) - at a glance

| Alert | Reads | Watches | Severity | Procedure |
|---|---|---|---|---|
| `RiskPolicyNoneActive` | `policy.active` 0, or `NO_POLICY_ACTIVE` recorded, on a judging target | `INV-RSK-05` | page | §9.1, §9.7 |
| `RiskEvaluationUnavailable` | `evaluation.unavailable{reason_code="RISK_UNAVAILABLE"}` rising | `INV-RSK-04` | page | §9.7 |
| `RiskEvaluationLatencyOverBudget` | > 1% of evaluations above the 50 ms bucket | `INV-RSK-04` | page | §9.7 |
| `RiskAlertsUnraised` | `alert.unraised` rising | `INV-RSK-06` | page | §9.7 |
| `RiskAlertFlood` | a kind's alerts in an hour above the objective | `INV-RSK-06` | ticket | §9.8 |
| `RiskCaseOverdue<Kind>` (five) | `case.age{kind}` past `SlaDeadlines` | `INV-RSK-06` | page (ATO, FRAUD), ticket | §9.8 |
| `RiskCaseBacklogAboveCapacity` | `case.backlog{kind}` above the staffed capacity | `INV-RSK-06` | ticket | §9.8 |
| `RiskActionDispatchFailing` | `action.failed{kind}` in each quarter hour | `INV-RSK-02`, `INV-IDEM-01` | ticket | §9.9 |
| `RiskActionRefused` | `action{outcome="refused"}` rising | `INV-RSK-02` | ticket | §9.9 |
| `RiskFilingUnknown` | `report.unconcluded.age` past two inquiry intervals (30 min) - a report `FILED` and not concluded | `INV-RSK-06` | ticket | §9.4 |
| `RiskFilingCallsFailing` | `report.filing{outcome="failed"}` in the hour | `INV-RSK-06` | ticket | §9.4 |
| `RiskMonitoringLagging` | `monitoring.age` - the oldest unmonitored journal entry - past an hour | `INV-RSK-09` | page | §9.10 |
| `RiskMonitoringUnclassified` | NEW `monitoring.unclassified` rows in the hour (`delta`) | `INV-RSK-09` | ticket | §9.10 |
| `RiskMonitoringSubjectFailing`, `RiskMonitoringUnplaced` | `monitoring.subject{outcome="failed"}` in the hour, `monitoring.unplaced` | `INV-RSK-09` | ticket | §9.10 |
| `VelocityDiverged` | `velocity.proof{verdict="DIVERGED"}` | `INV-RSK-03` | page | §9.11 |
| `RiskCensusDiverged` | `census{kind,verdict="DIVERGED"}`, per kind | `INV-RSK-01`, `-06`, `-09`, `INV-KYC-04` | page | §9.11, §9.6 |
| `RiskProofReadingsFailing`, `RiskProofReadingsStalled` | one instance's `proof.reading` failed at two floors / none completed in two | the proofs' invariants | ticket | §9.11 |
| `RiskGaugesUnreadable` | any risk gauge NaN past 35 min (beyond the proofs' two floors) | the gauges' invariants | ticket | §9.11 |
| `RiskSweeperOff` | `sweeper.enabled{kind}` 0 on an instance | `INV-RSK-06`, `-09`, `INV-KYC-04` | ticket | §9.10, §9.9, §9.6 |
| `RiskSweeperClaimFailing` | `sweeper.claim.failed{kind}` in each quarter hour | `INV-RSK-06`, `-09`, `INV-KYC-04` | ticket | §9.10, §9.9, §9.4, §9.6 |
| `RiskCollectionsUnplaced`, `InboxConsumptionFailing` | a default unplaced; any inbox delivery failing | `INV-RSK-06`, `INV-EVT-04` | ticket | §9.12 |
| `RiskRescreeningFailing`, `RiskRescreeningBacklogStanding` | `rescreening{outcome="failed"}`; `rescreening.backlog` above 0 for a day | `INV-KYC-04` | ticket | §9.6 |

Every threshold is the code's (`RiskObjectives`, `SlaDeadlines`); `RiskAlertRulesTest` proves each rule fires on its
break and only then, and `AlertRulesResolveTest#theRiskRulesResolve` that each reads a series a live instance publishes.

### 9.3 Reprocessing the monitoring from an earlier watermark - the order

Ordered by a `RISK_POLICY_ADMINISTER` holder after a monitoring policy change, a classifier change or an incident,
audited `risk.ReprocessingOrdered`. Idempotent by construction (`INV-RSK-09`): monitored transactions are born once per
journal entry, alerts once per (subject, rule, window start), cases once open per (subject, kind), so a re-run creates
nothing that exists and only what the earlier run missed. **A reprocessing, the order**: (1) activate the new monitoring
policy, or deploy the classifier, first - a reprocessing under the old one re-finds what it found; (2) set the source's
watermark back to the chosen journal sequence through the operator door (`POST
/v1/operator/risk/monitoring/reprocessing`, a reason, `Idempotency-Key`; never SQL); (3) let `RiskMonitoringSchedule`
sweep - every instance claims the watermark `SKIP LOCKED`, none is designated; (4) watch `finapp.risk.monitoring.lag`
fall to 0 and `finapp.risk.monitoring.unclassified` hold its figure - a reprocessing creates no monitored transaction
that exists, so the rows born `UNCLASSIFIED` before a classifier change stay `UNCLASSIFIED` (insert-only, §9.10) and
`RiskMonitoringUnclassified` stays silent unless the re-read meets a namespace still unknown; `RiskMonitoringLagging`
(`finapp.risk.monitoring.age` - the oldest unmonitored entry) fires when the step back reaches entries posted more than
an hour ago and clears as the sweep catches up, so acknowledge it for the reprocessing's duration rather than silencing
it; (5) read the decisions and alerts
reports for the window reprocessed (`GET /v1/operator/reports/risk/alerts?date=`). New alerts fold into their subjects'
open cases; a disposed case is never re-opened - a new alert on its subject opens a new case referencing it. A replayed
history is proven by `MonitoringIntakeDatabaseTest#reprocessingCreatesNothingNew` and
`AlertDatabaseTest#reprocessingRaisesNothingNew` (green in the owner-authorised database pass, 2026-10-27) and, hermetically,
`MonitoringRulesTest#reprocessingRaisesNoSecondAlert` and `AlertsTest#aReplayOrAReprocessingRaisesNothingNew`.
*(P13-DOC-001, 2026-10-27: this cited `P13-TST-001`'s scenario 8 first - the storm is POSTPONED, `POSTPONED_ISSUES.md`
PI-004, and its scenario never ran to a result.)* A reprocessing is also how a posting the intake skipped is
recovered - one that committed past the settle horizon after its id was minted (`PHASE_13_PLAN.md` §1.2 item 92; §9.11).

### 9.4 A regulatory filing that will not conclude - `UNKNOWN`

`FILED` is committed **before** the provider is called, with the provider's reference pending, and the artefact is
immutable from then (`PHASE_13_PLAN.md` §14; the payout precedent). A timeout or an unknown answer leaves the report
`FILED` with no acknowledgement: the platform **inquires** by its own reference through `RegulatoryFilingProvider` on the
schedule's cadence and **never re-files** - a second filing of one report is the one thing a filing must not be
(`INV-RSK-06`). *(As built by `P13-TSK-021`, 2026-10-25: the platform's own reference is the report's id; the drive's
first attempt submits and every later one inquires by it (a definitive `NOT_RECEIVED` submits under the same reference -
still the one filing); an unknown standing is asked again every `finapp.risk.filing.inquiry-interval` (15 min) by
`RiskActionSchedule`'s report leg, and each answer is counted `finapp.risk.report.filing{outcome}`.)*

**A filing `UNKNOWN`** - `RiskFilingUnknown` (`finapp.risk.report.unconcluded.age` - seconds since the oldest report
still `FILED` was filed, on the database's clock - past two inquiry intervals, 30 min): the report was submitted, asked
again at least once a `finapp.risk.filing.inquiry-interval` later, and the authority has neither acknowledged nor rejected
it - it answered `unknown`, or the calls failed (`RiskFilingCallsFailing`, any `failed` call in the hour), or the drive did
not run (`RiskSweeperClaimFailing{kind="report"}`, `RiskSweeperOff{kind="action"}`). An age, not a count of `unknown`
answers: those come an inquiry interval apart, which a counter's window of one interval cannot hold (the coordinator's
review of `P13-TSK-024`). *(P13-DOC-001, 2026-10-27: the 30 minutes are a literal in `risk.yml` - `> 1800` - not read
from `finapp.risk.filing.inquiry-interval`: a deployment that changes the interval changes the rule with it; and right
for the simulated authority, which answers at once - a real one's acknowledgement time is the threshold, set with C4 at
the activation gate, `PHASE_13_PLAN.md` §1.2 item 70.)* **What a person
does**: the `AML_OFFICER` reads the report (`GET /v1/operator/risk/cases/{caseId}/reports/{reportId}`, `AML_REPORT_APPROVE`,
recorded `risk.RegulatoryReportRead`) and confirms with the authority by the report's reference outside the platform.
**No operator door records the authority's answer by hand** - only the drive records it, as the platform; a report the
authority confirmed outside the platform stays `FILED` and inquired until the adapter says so. A recording door, four eyes
and audited, is an owner item (`-021` MINOR 10; `PHASE_13_PLAN.md` §1.2) - reviewed here and not built: the inquiry's
answer is the authority's own record, and a hand-typed acknowledgement would be the one unverifiable row in the report's
history. A rejection (`finapp.risk.report{status="REJECTED"}`) is a new draft from the same case, approved and filed under
four eyes again. **`RiskFilingCallsFailing`**: the calls themselves throw (the adapter, its network) - nothing is known
and nothing recorded; each `FILED` report is asked again by its reference, never re-filed; fix the adapter's
reachability. **`RiskSweeperClaimFailing{kind="report"}`**: the drive's own claim fails (the database) - read the
`Regulatory filing claim failed` warn lines; `RiskCensusDiverged{kind="FILING"}` follows if a `FILED` report waits past
its permit by a whole interval (the cases report's filing census, `GET /v1/operator/reports/risk/cases?date=`). Nothing of
the inquiry, the report or its subject reaches a customer surface, a log line or an event payload beyond the report id
and the jurisdiction (`INV-RSK-08`). **The AML desk has no worklist** (`-021` MINOR 4): a reporter learns the case and
report ids from the cases queue (`GET /v1/operator/risk/cases?kind=AML`, an analyst's) and the case's file - an owner
item, `PHASE_13_PLAN.md` §1.2, not built (an API).

### 9.5 Releasing or denying a held withdrawal

A withdrawal the seam decided `HOLD` is `HELD` with its ledger hold kept (payments `V033`, `P13-TSK-009`): the customer
reads the status word `HELD` and nothing else (C6), the case the decision opened carries the assessment as its first
evidence, and the money moves only by a person's act or the hold's expiry. Two paths, each a risk action:

- **An override** - a `RISK_OVERRIDE` holder proposes an override of the `HOLD` with a reason code, a text and its
  resolution (`POST /v1/operator/risk/assessments/{id}/overrides`, `Idempotency-Key`; `PROCEED` - the default - or
  `REFUSE`), and it is approved from a `MULTI_FACTOR` session (`…/overrides/{overrideId}/approval`, `Idempotency-Key`) -
  by a second holder at or above the pinned threshold (C7), by any holder - its proposer included - below it; the
  approved override issues `RELEASE_HELD_WITHDRAWAL` (`PROCEED`) or `DENY_HELD_WITHDRAWAL` (`REFUSE`).
- **The case's own action** - while the case is open, an analyst who has taken it proposes `RELEASE_HELD_WITHDRAWAL` or
  `DENY_HELD_WITHDRAWAL` on the withdrawal it holds (`POST /v1/operator/risk/cases/{caseId}/risk-actions`,
  `Idempotency-Key`, the `targetRef` the withdrawal's id) and a second `RISK_CASE_APPROVE` holder approves it from a
  `MULTI_FACTOR` session (`…/risk-actions/{actionId}/approval`). **Act before disposing the case**: a disposition issues
  nothing, a case is not disposed while an action proposed on it awaits its decision (`409 risk.CaseActNotAdmitted`),
  and once it is disposed nothing more can be proposed on it - the withdrawal then waits for an override or out its
  `held_until`. *(P13-DOC-001, 2026-10-27: this read "the approved override issues `RELEASE_HELD_WITHDRAWAL` - or the
  case's four-eyes disposition issues `DENY_HELD_WITHDRAWAL`": no disposition issues an action (`Cases.proposeDisposition`
  / `approveDisposition` dispose and announce the case - a prohibiting one sets the profile `PROHIBITED` after its
  commit - and dispatch nothing); a held withdrawal is denied by an override's `REFUSE` or by the case's own four-eyes
  action, proposed before the disposal.)*

Either action is dispatched by `RiskActionSchedule` through payments' own door, keyed `risk.action:<id>`, on payments'
lock order: a release moves `HELD → DISPATCHED` and the withdrawal proceeds as today; a denial moves
`HELD → FAILED(RISK_REFUSED)` and releases the hold in the same transaction.
A release and the hold's expiry (`held_until`: the policy's hold validity, default 72 h; payments'
`WithdrawalResolutionSchedule` expiry leg - one `HELD` row past `held_until` on the database clock per transaction, `FOR
UPDATE SKIP LOCKED`, failed `RISK_REFUSED`/`HOLD_EXPIRED` and its hold released as the platform) leave exactly one
outcome (`WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`); a re-drive replays payments' stored answer. Never
release a hold by hand and never edit the withdrawal: the action's outcome is recorded on both sides. A customer who
closes the wallet meanwhile is refused while the hold is `ACTIVE` (accounts' existing rule). *(Reviewed by `P13-TSK-024`:
the expiry leg logs its counts - "Withdrawal hold expiry: n expired, m failed" - and meters nothing, so a hold whose
expiry keeps failing is visible in the logs and in the `FRAUD` case's age (`RiskCaseOverdueFraud`), not by an alert of its
own - an owner item, `PHASE_13_PLAN.md` §1.2 item 68.)*

### 9.5b Capturing or voiding a held card payment

*(Added by `P13-DOC-001`, 2026-10-27, from the code: §9.5 covered the withdrawal alone.)* A checkout card payment the
seam decided `HOLD` (risk `V008`: `CARD_PAYMENT` admits `HOLD`, and a card version that can decide it names its hold
validity, 1 to 720 h; a wallet payment never holds - C2) is authorised as any card payment and its capture deferred
(payments `V034`, `P13-TSK-010`'s second slice): the intent `PROCESSING`, the card attempt `AUTHORIZED` once the issuer
answers, beside an `OPEN` `payment_capture_hold` stamped `held_at` / `held_until` (the pinned version's validity) on the
database clock. The customer and the merchant read the intent's generic `PROCESSING` and nothing of the hold (C6); the
`FRAUD` (or `ATO`) case the decision opened carries the assessment. The hold ends **exactly once** (`HeldPayments`), by
one of three hands:

- **A capture** - an override `PROCEED` (`CAPTURE_HELD_PAYMENT`), approved as §9.5's, or the case's own action
  `CAPTURE_HELD_PAYMENT` on the intent (`targetRef` the payment intent's id), proposed and approved under four eyes while
  the case is open (§9.5's "act before disposing the case" holds here too): payments' keyed door resolves the hold
  `OPEN → CAPTURE` **within `held_until`** on the database clock and hands the money to the existing capture
  (`payment-capture:<attempt>`). Past `held_until` the door answers `REFUSED`, nothing written - the action `FAILED`,
  `RiskActionRefused` (§9.9): the expiry is the policy's outcome.
- **A void** - an override `REFUSE` (`VOID_HELD_PAYMENT`) or the case's action `VOID_HELD_PAYMENT`: the hold
  `OPEN → VOID`, the authorisation released through the existing void.
- **The expiry** - `PaymentSweeperSchedule`'s second leg (`HeldPayments.expireHolds`; every instance running the payments
  sweeper, `finapp.payments.sweeper.enabled`, each `finapp.payments.sweeper.poll-interval`, 30 s): one open hold past
  `held_until` on the database clock per transaction, claimed `FOR UPDATE SKIP LOCKED`, resolved `EXPIRED` as the
  platform; then the finisher voids every hold resolved `VOID` or `EXPIRED` whose attempt still stands `AUTHORIZED`
  (reason `HOLD_EXPIRED` for the expiry's) - so an unworked held card payment is voided, never captured.

A capture racing the deadline leaves one resolution (`HeldPaymentsTest#captureAndTheDeadlineLeaveExactlyOne`;
`V034`'s every-writer rule beneath, `PaymentRiskMigrationDatabaseTest#aHoldIsBornOpenAndResolvesOnce`). An authorisation
still in flight (`AUTH_DISPATCHED` / `AUTH_UNKNOWN`) is thrown with nothing recorded, so the schedule re-drives the same
key once the issuer has answered - `RiskActionDispatchFailing{kind="CAPTURE_HELD_PAYMENT"}` (or `VOID_…`) meanwhile
(§9.9). **The alerts that apply**: the case's `RiskCaseOverdueFraud` / `RiskCaseOverdueAto` (§9.8),
`RiskActionDispatchFailing` and `RiskActionRefused` (§9.9); the hold has no alert of its own - the leg logs its counts
("Held payments: n expired, m voided, f failed"; "Held payments' expiry failed: <class>") and meters nothing.
**Payments' stuck-payment gauge counts it**: `finapp.payments.unknown.active` / `.age` (the dashboard's "Payments
stranded in an unknown state") count an `AUTHORIZED` attempt past the sweep's dispatched bound
(`finapp.payments.sweeper.dispatched-age`, 10 min) as stranded, so every held card payment reads stranded there from ten
minutes after its authorisation until its hold ends - read the risk case queue before chasing it as an ambiguity
(`PHASE_13_PLAN.md` §1.2 item 85, for the owner's ratification). A validity of up to 30 days can outlive the issuer's
authorisation (item 8). Never capture or void by hand and never edit the hold row: the resolution is recorded on both
sides.

### 9.6 A screening list update - activating it, reading its census, re-driving a burnt version

*(Written by `P13-TSK-022`, 2026-10-26, with the owner's decision O1; reviewed by `P13-TSK-024`, which added the alerts
and the census.)* A new edition of the sanctions or PEP list is an operator's fact: a `RISK_POLICY_ADMINISTER` holder
records it (`POST /v1/operator/risk/screening-lists`, a label unique per list), a **second** holder activates it from a
`MULTI_FACTOR` session (`POST …/{listVersionId}/activation`, `202`; the recorder is refused `403
risk.SelfApprovalRefused`); the activation retires the list's previous version in the same transaction. From then
`RiskActionSchedule`'s rescreening leg enqueues one permit per party whose customer relationship is `ACTIVE` or `PENDING`,
in batches, and asks kyc's re-screening door for each: a `RESCREEN` check on the party's latest case under the permit's
key and an ordinal. On a **decided** case the case, the customer and kyc's review queue are untouched - a hit is a
`SCREENING_HIT` alert in the party's `AML` case. On a case **not yet decided** (an onboarding customer's) a hit is that and
kyc's own review item too: the case goes `IN_REVIEW` with the rescreen's task, its automatic approval blocked, exactly as
an onboarding hit (fail-closed; ratified at `P13-DOC-001`) - the kyc reviewer sees the check with purpose `RESCREEN`
(counted `finapp.kyc.check{outcome,purpose}`) and the decision names it. One alert per party **and list version**: a
second list's hit of the party the same day is a second alert, folded into the open `AML` case or a new one if the first
was closed.

**The census of a version** - `rescreening_permit` by `list_version_id` and `status` (`rescreening_permit_of_version`),
beside `screening_list_version.enqueued`: every permit `DONE` (`CLEARED`, `HIT`, `SKIPPED` for a relationship gone, or
`NOT_ONBOARDED` - counted `finapp.risk.rescreening{outcome=not_onboarded}` - for an onboarding customer with no KYC case
yet, whose onboarding screens it against the list in force; never an exposure) is a party accounted for under the
version; `PENDING` is still being asked; `FAILED` - counted `finapp.risk.rescreening{outcome=failed}` - is a party **not**
rescreened under it, with its cause. **The census is read by the meters** (`P13-TSK-024`, `RescreeningCensus`, the proofs'
reading): per version in force or retired since yesterday's start, the permits equal the enqueue's count, the `DONE(CLEARED)`
permits equal the `SCREENING_CLEARED` signals naming the version, and the `DONE(HIT)` permits are at most its
`SCREENING_HIT` signals (a late hit adds a signal - below); a version breaking one is
`finapp.risk.census{kind="RESCREENING",verdict="DIVERGED"}` - **`RiskCensusDiverged` for `RESCREENING`**: a permit lost or
born twice, or a clearance recorded without its signal - a defect, never an exposure to accept. No door serves the
per-version rows (the list version read door is an owner item, `-022`'s O4): a database operator reads them read-only
with the census's own statement (`JdbcRiskProofReads.RESCREENINGS`) to name the version, and the incident is the
platform's - nothing is written to "fix" it. **The leg's bound governs the asking**: every claim asks kyc one new question
(the next key ordinal) while the last answered "we do not know" or was abandoned mid-call, up to `max-attempts` (12)
claims at the backoff (5 min doubling to 6 h) - about 34.6 h from the first claim to the last. So the causes read:
`UNAVAILABLE` (no provider configured, or kyc unreachable at every claim of the bound), `NOT_SCREENED` (the provider said
"we do not know" at every claim of the bound - an outage that outlasted ~34.6 h; or an `ACTIVE` customer with no KYC case
at all, decided on nothing - investigate), `IN_FLIGHT` (the last claim's question still within its asker's lease - a
provider slower than the lease at the very end of the bound; a question older than a lease at a claim is abandoned and
asked again, so a restart mid-call never fails a permit), `UNCONCLUDED` (every conclusion failed - a defect: read the
`claim.failed{kind=rescreening}` warn lines), or `STALE` (an answer asked before the activation - impossible for a
permit's own keyed check, so a `STALE` failure is a defect to report, never an exposure to accept). An abandoned
question's row stays `DISPATCHED` in kyc - the record of the call that was lost; a late answer to it is still the
permit's own, and its hit wins. A clear concludes a permit only when no newer question is still live. **A hit kyc records
after its permit concluded otherwise** (a late callback for an abandoned question, a slow call that outlived its lease)
is told back to the permit (kyc's `RescreenHits`, risk's `RescreeningRun.hit`): a second `SCREENING_HIT` signal, born of
kyc's check, and its own alert in the party's `AML` case - counted `finapp.risk.rescreening{outcome=late_hit}`; the permit
stays as it concluded, so a version's `SCREENING_HIT` signals may outnumber its `DONE(HIT)` permits - the census reads
them so. On an **undecided** case the kyc reviewer's decision waits on the hit's open review task: `byReviewer` answers
`NOT_READY` until it is resolved - for a late onboarding hit too.

**The rescreening alerts.** **`RiskRescreeningFailing`** - a permit `FAILED` in the last hour: a party not rescreened
under the version in force, the exposure (`INV-KYC-04`); read the version's census by cause (above) and, once the cause
is fixed, re-drive by a successor version (below). **`RiskRescreeningBacklogStanding`** - permits `PENDING` for a whole
day: the leg is off (**`RiskSweeperOff{kind="rescreening"}`** - the action schedule's flag,
`finapp.risk.action.sweeper.enabled`), failing (**`RiskSweeperClaimFailing{kind="rescreening"}`** - an enqueue batch, a
claim or a conclusion rolling back; the `Rescreening … failed` warn lines name the class), or slower than the population
at the per-tick bounds (500 parties a batch, 4 batches and 20 permits a tick - owner item O6): raise the bounds, never
conclude a permit by hand.

**A burnt version - re-drive by a successor, never by hand.** A permit that has `FAILED` is terminal and a version is
activated once (`409 risk.PolicyStale`), so its failed parties are never asked again under it. When the cause is fixed (the
provider configured, kyc reachable), **record and activate a successor version of the same list** under a new label
(e.g. `2026-10-edition-r2`), under four eyes as any activation: its enqueue reaches every party again - those already
cleared under the burnt version are asked once more (a cost, not an error), the burnt version's census stays as the
record of the exposure. There is no door that moves a `FAILED` permit back to `PENDING`, by design (the owner's decision,
2026-10-26). **Never** write a permit, a signal or a kyc check by hand: the leg's record is the evidence.

**Deploy-time locks.** risk `V018` (the rescreening's tables, the signal's two references, the alert's pairing CHECKs
and its born-once identity) and kyc `V011` (the check's purpose and key, the decided-latest index) hold
`risk.risk_signal`, `risk.risk_alert` and `kyc.verification_check` under `ACCESS EXCLUSIVE` for an index build or two and
a few validating scans each - and `risk.risk_alert` for one table **rewrite** besides (its generated `fact_signal_id`
column is computed for every row): while they run the signal intake (every consumed event's handler), T-eval's signal
reads and alert raises, and kyc's runs wait on the locks - nothing fails, the inbox retries a delivery that times out and
the sweeps re-run their claims. Deploy them when the signal intake and the kyc runs are quiet, with `lock_timeout` set so
a long reader fails the migration (to retry) rather than queues every writer behind it; watch
`InboxConsumptionFailing` and `RiskSweeperClaimFailing` settle after.

### 9.7 Evaluation unavailable, over budget, unjudged or unraised

- **`RiskEvaluationUnavailable`** (`finapp.risk.evaluation.unavailable{reason_code="RISK_UNAVAILABLE"}` rising): a seam
  answered its fail-safe band - the pin's read failing, a rule throwing, or the 250 ms statement timeout (`PHASE_13_PLAN.md`
  §7.6) - **recorded** as an assessment with reason `RISK_UNAVAILABLE`, the money movement committing the band's outcome;
  one warn line per assessment names its id, its operation and the cause's code. A fail-open is a visible, counted event,
  never silence. *(P13-DOC-001, 2026-10-27: the pin's read failing is not a band's answer - with no version pinned there
  is no band to read, so the seam answers the compiled `ALLOW` (`RISK_UNAVAILABLE`) for every operation, `WITHDRAWAL` and
  `CROSS_BORDER` included, whatever C1 signs for them; the bands govern a failure after the pin (a rule throwing, a read
  past its statement timeout) - `PHASE_13_PLAN.md` §1.2 item 75, to decide before the activation gate.)*
  **What a person does**: read the day's decisions by reason (`GET /v1/operator/reports/risk/decisions?date=`,
  `RISK_INVESTIGATE`, recorded `risk.ReportRead`); find the cause - latency (the next alert), the database, a rule kind
  failing on an input (a policy defect: a new version, never a patched rule); an assessment is read and replayed at `GET
  /v1/operator/risk/assessments/{id}` and `POST …/{id}/replay`. Never evaluate by hand, and never re-judge a committed
  movement.
- **`RiskEvaluationLatencyOverBudget`** (more than 1% of five minutes' evaluations of an operation above the 50 ms bucket
  of `finapp.risk.evaluation.latency`): T-eval runs inside every money movement's locks, so a breach is paid by every
  transfer, withdrawal, payment and cross-border authorisation and, where one read statement passes 250 ms, becomes the
  fail-safe decision. *(P13-DOC-001, 2026-10-27: this read "past 250 ms": the 250 ms is a per-statement limit
  (`EvaluationBudget.READ_TIMEOUT`, each read of R2a and the alerts' raise behind its savepoint), not the evaluation's
  total - an evaluation of many statements each within it can take longer and still judge; and the pin (R1) runs under
  no timeout at all.)* Read the velocity windows' contention (a hot subject serialises its movements on its subject
  locks and window rows: a burst queued past the budget becomes `TIMEOUT` fail-safes judged by the amount band alone,
  their counts possibly `UNCOUNTED`, so a concurrent burst can pass a velocity limit - `PHASE_13_PLAN.md` §1.2 item 77)
  and the policy's rule count; the monitoring sweep running beside it (`finapp.risk.monitoring.latency`, the dashboard's
  latency panel) is M3's split trigger (`PHASE_13_PLAN.md` §3.2) - a sweep that harms the synchronous budget is the
  evidence that the AML capability wants its own module. The trigger is unmeasured: the storm that was to measure it is
  POSTPONED (PI-004; item 76, §9.1 step 2).
- **`RiskPolicyNoneActive`** (`finapp.risk.policy.active{operation}` at 0, or `NO_POLICY_ACTIVE` recorded, on a target
  labelled `risk_judging="true"`): §9.1 step 9 - a version retired with no successor, or an operation never activated.
- **`RiskAlertsUnraised`** (`finapp.risk.alert.unraised{operation}` rising): a sealed assessment's alerts were undone
  behind their savepoint (the raise failed after the seal) - the decision stands, but the alerts and the case they would
  have opened were never born, so a `REVIEW` or `HOLD` would wait for an investigator who never sees it. The warn line
  `risk alerts unraised behind their savepoint` names the assessment; an analyst reads it (`GET
  /v1/operator/risk/assessments/{id}` - its subject, decision and fired rules) and opens the case by hand (`POST
  /v1/operator/risk/cases`, `Idempotency-Key`, the `kind`, the subject's `subjectKind` and `subjectRef`, a reason naming
  the assessment - the subject's open case of the kind answers if one exists) and works it as any other; a held
  withdrawal the decision holds follows §9.5. Then find why the raise
  failed (the database, the alert store) from the same line's cause.

### 9.8 An alert flood, a case backlog, a case past its SLA

- **An alert flood** - `RiskAlertFlood` (`finapp.risk.alert{kind}` summed over an hour above
  `RiskObjectives.ALERT_FLOOD_PER_HOUR`, 500 - a default the owner replaces with the production policy's measured
  baseline): a flood buries the alert that matters (`INV-RSK-06`'s why). Find what changed before touching a single alert:
  a policy activated (`GET /v1/operator/risk/policies?operation=`), a rule weight that now fires on ordinary traffic
  (`ROUND_AMOUNT`, §9.1 step 3), a reprocessing (§9.3), a new posting namespace, an upstream replay of signals. The
  remedy is a new policy version under four eyes (§9.1 step 9), never a mass suppression: a false positive is suppressed
  one alert at a time by two analysts (`POST /v1/operator/risk/alerts/{alertId}/suppression`, then `…/approval` from a
  `MULTI_FACTOR` session, a reason code), and the alerts report (`GET /v1/operator/reports/risk/alerts?date=`) shows the
  day's alerts by kind and rule.
- **A case backlog** - `RiskCaseBacklogAboveCapacity` (`finapp.risk.case.backlog{kind}` above the kind's staffed
  capacity, `RiskObjectives.caseCapacity` - ATO 50, FRAUD 200, MERCHANT_ABUSE 100, AML 200, COLLECTIONS 1,000, defaults
  pending the owner's staffing figures; the `COLLECTIONS` queue grows with every default, `P13-TSK-025`): the analysts
  read the queue oldest first - `GET /v1/operator/risk/cases?status=&kind=&after=&limit=`, each page audited
  `risk.CaseRead` - and a case's file at `…/cases/{caseId}`; an analyst never sees their own party's case (withheld from
  the page, the file `403 risk.SelfDealingRefused`), so a backlog only one desk member can see is reassigned, never
  self-served. The alerts waiting for a person are `GET /v1/operator/risk/alerts` (`OPEN` by default). A page that ends
  `bounded` in its `risk.AlertRead` / `risk.CaseRead` summary met more than a thousand withheld rows - the reader's own
  party's or unplaceable subjects' - and is an owner item, not a fault. A backlog that stays above capacity is a staffing
  decision, never a reason to close cases unworked.
- **A case past its SLA** - `RiskCaseOverdueAto` (4 h), `RiskCaseOverdueFraud` (24 h), `RiskCaseOverdueMerchantAbuse`
  (72 h), `RiskCaseOverdueAml` (30 days), `RiskCaseOverdueCollections` (7 days) - `finapp.risk.case.age{kind}`, the oldest
  open case's age on the database clock, past `SlaDeadlines` (stamped `sla_deadline` at the opening, never an instance's
  clock): work the queue oldest first (above); an ATO case is the customer's account being taken now - its downstream
  actions (`REVOKE_SESSIONS`, `SUSPEND_IDENTITY`, C10) are proposed and approved under four eyes (§9.9); a FRAUD case holding
  a withdrawal has its hold's 72-hour validity running (§9.5); an AML case is the investigation a filing decision rests on
  (§9.4). The SLA is never extended by editing a row - a different figure is a code change the owner signs (§9.1 step 3).

### 9.9 An action `FAILED` - a dispatch failing, an owner's refusal

Every downstream effect is a `risk_action` row - a case's (`POST /v1/operator/risk/cases/{caseId}/risk-actions`, then
`…/{actionId}/approval` by a second `RISK_CASE_APPROVE` holder from a `MULTI_FACTOR` session) or an approved override's -
re-driven by `RiskActionSchedule` (every instance, `finapp.risk.action.sweeper.enabled`, each due or lapsed action claimed
`SKIP LOCKED` and dispatched as the platform under `risk.action:<id>` through the owning domain's existing command). Two
different things can go wrong:

- **An action `FAILED` by its owner** - `RiskActionRefused` (`finapp.risk.action{kind,outcome="refused"}` rising): the
  owning domain refused the command (a withdrawal no longer `HELD`, an identity already gone) and the action is recorded
  `FAILED` - terminal, never re-driven. Read the case's file (`GET /v1/operator/risk/cases/{caseId}`, `riskActions`:
  `attempts`, `status`, `outcome`, the stamps) and decide on the case: re-propose a different action, or record on the
  case why none follows. A domain's "already so" is `DONE(ALREADY)`, not a failure.
- **A dispatch failing** - `RiskActionDispatchFailing` (`finapp.risk.action.failed{kind}` in each quarter hour): the
  owner's door threw (its database, payments' push rail, a card authorisation still in flight) - nothing was recorded and
  the permit stands, so the schedule re-drives the same key `risk.action:<id>` each lease (`finapp.risk.action.lease`,
  2 min) until the owner answers. A count that keeps rising for one kind is that owner's door failing: read the
  `Risk action dispatch failed` warn lines (the class) and the owner's own health. The money is never at risk meanwhile:
  a held withdrawal not released is failed by its own expiry (§9.5), a capture hold not captured voided by payments'
  deadline. *(An age gauge for `DISPATCHED` actions - `-017` MINOR 8 - was reviewed and not built: the re-drive is the
  leaderless schedule's, and its three failure modes are each alerted - the schedule off, its claim failing, the dispatch
  throwing.)*
- **The schedule itself** - `RiskSweeperOff{kind="action"}` (an instance not running it; set it back) and
  `RiskSweeperClaimFailing{kind="action"}` (the schedule's own claim failing - the database, not an owner; the
  `Risk action claim failed` warn lines). `SUSPEND_ACCOUNT`, `REINSTATE_ACCOUNT` and `BLOCK_BENEFICIARY` have no owning
  command and never appear (owner item, `-017` MINOR 2). *(P13-DOC-001, 2026-10-27: nor does `RESCREEN_PARTY` - it reads
  available, but no door proposes it and the rescreening leg asks kyc through its own permits, never a `risk_action`
  row; `PHASE_13_PLAN.md` §1.2 item 93.)*

### 9.10 The monitoring lag, the unclassified, the parties unjudged

`RiskMonitoringSchedule` (every instance, `finapp.risk.monitoring.sweeper.enabled`) claims the ledger journal's
watermark `SKIP LOCKED` and ingests up to `finapp.risk.monitoring.batch` (200) entries a batch, `batches-per-tick` (10) a
tick every `poll` (5 s), judging each party behind them against the `MONITORING` policy in the same claim
(`P13-TSK-020`), then the profile and case legs.

- **The monitoring lag** - `RiskMonitoringLagging` (`finapp.risk.monitoring.age{source}` - seconds since the oldest
  journal entry beyond the watermark was posted, its UUIDv7 read on the database's clock - past an hour): the sweep has
  left a movement unmonitored for an hour. An age, not the entry count: `finapp.risk.monitoring.lag` (the dashboard's)
  stands above zero whenever the ledger posts faster than a poll and near zero on a quiet ledger however stalled the
  sweep, while the age stays within the settle horizon and a poll (`finapp.risk.monitoring.horizon`, 60 s) on a busy,
  draining sweep and rises from the first entry left behind at any volume. Causes: every instance's sweeper off (**`RiskSweeperOff{kind="monitoring"}`** on each - the
  standing cause), the claim failing (**`RiskSweeperClaimFailing{kind="monitoring"}`**, the
  `Risk monitoring intake batch failed` warn lines), a party's head held by a stuck transaction holding the claim and so
  the watermark (`-020` MINOR 6 - find the blocker in `pg_stat_activity`; a `lock_timeout` is the owner's trade), or a
  reprocessing in progress (§9.3, expected). **Never advance a watermark by hand** - an entry skipped is an entry never
  monitored (`INV-RSK-09`).
- **`RiskMonitoringUnclassified`** (NEW rows of `finapp.risk.monitoring.unclassified` in the last hour - the gauge's
  growth; its level counts every `UNCLASSIFIED` row ever born and never falls, the rows being insert-only): the intake met
  a posting-key namespace its register does not know and recorded the movement `UNCLASSIFIED` - nothing passes unseen,
  but nothing is evaluated for it either. A new module's postings or a renamed key: extend the classifier (`-019`'s
  register, a code change with its test) and deploy; the alert resolves an hour after the last new row, and fires again
  for a second unknown namespace. A row born `UNCLASSIFIED` stays so after the register learns its namespace - a
  reprocessing meets it `ON CONFLICT` and evaluates nothing for it (owner item, `PHASE_13_PLAN.md` §1.2 #33: a
  reclassification fact if history must be re-judged).
- **`RiskMonitoringSubjectFailing`** (any `finapp.risk.monitoring.subject{outcome="failed"}` in the hour - a party's leg
  re-runs at its next movement or profile permit, not each tick): a party's rule leg rolled back alone (its savepoint) -
  the movements are monitored but the party is not judged until a later run succeeds. The `Risk monitoring subject failed` warn line names the cause's class, never the party; a profile whose
  canonical sealing passes its bound (`-020` MINOR 8) fails every run - an owner item.
- **`RiskMonitoringUnplaced`** (`finapp.risk.monitoring.unplaced` rising): customer accounts no party could be placed
  behind (accounts' ownership read through `MonitoredParties`) - unjudged. An account whose owner is gone or a new
  account kind; report it to the accounts owner, then reprocess the window (§9.3).

### 9.11 A `VelocityDiverged`, a census diverged, the proofs unread

The proofs write nothing and decide nothing: each reading takes one read-only snapshot, every statement bounded
(`finapp.risk.proofs.statement-timeout`, 5 s), behind a 15-minute floor, single-flight per instance, off the scrape thread
(`P13-TSK-023`); the gauges read 1 on the latest verdict, NaN when unreadable or older than two floors.

- **A `VelocityDiverged`** (`finapp.risk.velocity.proof{verdict="DIVERGED"}`, `INV-RSK-03`): a live velocity window's
  count or sum differs, unexplained, from what it must equal - the counted assessments for every dimension, and for
  windows of transfers alone the monitored journal (the journal link subtracts the transfers the record permitted
  `UNCOUNTED` and sets aside the postings before the go-live floor, so `DIVERGED` is an unexplained difference only).
  **What a person does**: read `GET /v1/operator/reports/risk/velocity?date=<today>` (`RISK_INVESTIGATE`, recorded
  `risk.ReportRead`): each divergence located with its window, its `subjectRef` (the subject's reference - never shown
  for the reader's own party, which is `withheld`, counted) and its recorded and expected figures, each link's
  `explained` and `beforeFloor` counts, and the day's monitoring census beside it. *(P13-DOC-001, 2026-10-27: this read
  "whether it is `explained` … or `beforeFloor`" of each divergence: both are per-link counts on
  `RiskVelocityLinkView` - the windows whose expected figures had an uncounted transfer subtracted (still judged), and
  the postings older than the record's first transfer - never a divergence's flag; a listed divergence is unexplained.)*
  A window written by a movement the journal never
  recorded (a counted transfer refused after its seams - `DESTINATION_NOT_POSTABLE`, an owner item), or a monitored
  transaction the window missed, is an incident: open it, attach the report, and **never edit the window** - limits read
  the window, and a corrected row would hide the defect the proof found.
- **A census diverged** - `RiskCensusDiverged`, one alert per kind (`finapp.risk.census{kind,verdict="DIVERGED"}`):
  `ASSESSMENT` (`INV-RSK-01`: a judged command without its seal, or a seal without its command - the decisions report,
  `GET /v1/operator/reports/risk/decisions?date=`, names each; a payment intent is dated by its creation, the debt row),
  `MONITORING` (`INV-RSK-09`: monitored ≠ journal by sequence - the velocity report's monitoring census, which compares
  the journal-entry id sets behind the watermark and never an amount (item 82); its `missing` names, among other causes,
  a posting that committed past the 60 s settle horizon after its id was minted, which the intake does not enforce and
  so passed (item 92); reprocess the window, §9.3), `ALERT_BORN_ONCE` and `CASE_ONE_OPEN` (`INV-RSK-06`: an alert born twice, two open cases of one subject
  and kind - the alerts and cases reports), `FILING` (a `FILED` report undriven past its permit - §9.4) and `RESCREENING`
  (a list version's permits or clearances unaccounted for - §9.6). Each is a defect to report with its report attached,
  never a row to correct.
- **The proofs unread** - `RiskProofReadingsFailing` (one instance's `finapp.risk.proof.reading{outcome="failed"}` at
  two floors in a row - `by (instance)`, each reading its own: a statement past its bound, a lost connection, a refused start - the `Could not read the risk proofs` warn lines
  name the class) and `RiskProofReadingsStalled` (no `completed` reading on an instance in two floors: a reading stuck on a
  connection the network black-holed - the data source sets no socket timeout - or killed by an `Error`; restart that
  instance, nothing is lost). While either stands, the velocity proof and the censuses are not watched: read the four
  reports by hand for today until the readings complete. The rules' figures are floors of the default
  `finapp.risk.proofs.refresh` (15 min, `RiskObjectives.proofReadingFloors`): the failing rule's window and wait two
  floors each (`[30m]`, `for: 30m`), the stalled rule's window two floors and its wait one (`[30m]`, `for: 15m`) - a
  deployment that raises the floor raises these figures in `risk.yml` with it, or the stalled rule fires on a healthy
  instance. *(P13-DOC-001, 2026-10-27: this read "Both rules' window and wait are two floors".)*
- **`RiskGaugesUnreadable`**: any database-read risk gauge NaN for 35 minutes - past the proofs' two floors, so one
  failed reading (NaN until the next floor) is tolerated - the proofs, the census, the active policies, the monitoring lag,
  age and unclassified count, the filing's age, the case queues, the rescreening backlog, the rating distribution
  (`finapp.risk.profile.rating`). A rescreening census at a population of millions can pass the shared 5 s statement
  timeout and fail the whole reading (the debt row "the rescreening census counts each version whole"). A NaN is "not watched", never "clean": find the failing read in the instances' warn
  lines (`… could not be read`, the class alone).

### 9.12 The signal intake stalled

Risk's signals arrive through the inbox (`risk.signals`' consumer group): a delivery the handler cannot record is thrown,
rolled back with its dedupe record, and retried - the partition **stalls loudly** behind it, never skips it (`INV-EVT-04`).
*(Since `P15-TSK-028` the stall is bounded: a record failing for a reason of its own parks after five failures and two minutes,
kept, counted and alerted, and the partition goes on. A version this build cannot read never parks. §11.6 has the door.)*

- **`RiskCollectionsUnplaced`** (`finapp.risk.collections.unplaced` rising - `P13-TSK-025`): a `LoanDefaulted` or
  `BnplDefaulted` delivery names an agreement whose party cannot be placed (lending's and bnpl's published views), so it is
  refused before any alert is born and its partition retries it, then parks it (§11.6). The fix is upstream: the agreement's
  party missing from the view (a status filter, a migration) - the owning module restores it. If the record has parked, release
  it (§11.6) and the retry records the default and opens its `COLLECTIONS` case. Never acknowledge the delivery by hand: the
  default would never reach the collections desk.
- **`InboxConsumptionFailing`** (`finapp.inbox.consumption{outcome="failed"}` in each quarter hour - platform-wide, the
  series carries no module tag, registered eagerly whatever the consumer's flag): some consumer's partition is stalled -
  an event version this build cannot read (deploy the build that knows it), a payload out of bounds, a handler failing.
  The warn lines name the consumer and the event type; risk's signal intake is the largest consumer, and the signals
  recorded by kind (`finapp.risk.signal{kind,outcome}`, the dashboard) show which kinds stopped arriving.

### 9.13 What the operators carry - owner items and debt

The open owner items of the phase (each to be ratified or decided at `P13-DOC-001`) and the debt rows it accrued are
listed once, in `PHASE_13_PLAN.md` §1.2 - among them the ones the procedures above meet: per-currency policy versions
(§9.1), the `ROUND_AMOUNT` alerting choice (§9.1, §9.8), the SLA, capacity and flood figures (§9.8), the manual filing
answer door and the AML desk worklist (§9.4), the list version read door (§9.6), the held withdrawal expiry's meter
(§9.5), the rescreening bounds (§9.6), a `lock_timeout` on the monitoring claim (§9.10). *(P13-DOC-001, 2026-10-27,
the exit review's additions the procedures meet - items of `PHASE_13_PLAN.md` §1.2, none decided here: the storm's
postponement and the unmeasured latency (§9.1 step 2; items 76, PI-004); before the activation, items 1, 10, 12, 72, 75,
77, 83 and 92 (§9.1 step 3, §9.7); the roles one identity may hold at once (item 86, §9.1 step 5); the one label for six
operations (item 67, §9.1 step 10); the filing alert's literal 30 minutes (item 70, §9.4); the held card payment read
as stranded by payments' gauge (item 85, §9.5b); the monitoring census's id sets and the unenforced settle horizon
(items 82, 92, §9.3, §9.11); `RESCREEN_PARTY` never dispatched (item 93, §9.9).)*

## 10. Accounting (Phase 14): the close and its alerts

*Opened by `P14-TSK-017` (2026-11-02), written whole by `P14-TSK-020` (2026-11-05). Every alert below is in `infra/prometheus/rules/accounting.yml`, its bound in `AccountingObjectives`. Routed by severity to a placeholder receiver since `P15-TSK-025` (`infra/alertmanager/alertmanager.yml`), until an on-call tool exists. `AccountingRunbookTest` holds what it can of this section to the code: its javadoc lists exactly what, and JSON fields and prose are not among it. What a row cannot see is said in its section.*

| Alert | Severity | The break | Section |
|---|---|---|---|
| `AccountingCloseOverdue` | ticket | the oldest month not `CLOSED` is over 7 days past the day it could lock | 10.7 |
| `AccountingScheduleOff` | ticket | no instance runs the close schedule | 10.7 |
| `AccountingCloseRunStalled`, `AccountingCloseRunStallUnreadable` | page, ticket | a lock with no run past the bound; or that cannot be read | 10.1 |
| `AccountingCloseCheckStalled`, `AccountingCloseCheckStallUnreadable` | page, ticket | a run's attempts claimable past the bound, nobody claiming; or that cannot be read | 10.9 |
| `AccountingCloseCheckFailed` | ticket | an attempt concluded `FAILED` | 10.9, 10.3 |
| `AccountingCloseLegFailing` | ticket | a schedule leg threw more than once in two hours | 10.9, 10.6 |
| `AccountingPostingIntoClosedPeriod` | page | a posting or reversal refused `ledger.PeriodClosed` | 10.8 |
| `AccountingAdjustmentIntoClosedPeriod`, `AccountingAdjustmentStanding` | ticket | an adjustment's approval refused at the cutoff; one `PROPOSED` past 7 days | 10.4 |
| `AccountingMappingGap` | ticket | lines in the open period the active version does not map | 10.12 |
| `AccountingBreakAged` | ticket | a break `OPEN` past 7 days | 10.13 |
| `AccountingReportDiverged` | page | a FINAL run did not reproduce | 10.10 |
| `AccountingClosedMonthStatementRefused` | page | a definition refused a FINAL run, a set's hole apart | 10.5 |
| `AccountingReportRunFailed` | ticket | any run ended `FAILED` | 10.5 |
| `AccountingClosedMonthLostItsRun` | ticket | a repudiation took the latest closed month's counting run | 10.11 |
| `RegulatorySubmissionRejected`, `RegulatoryContraryAnswer`, `RegulatoryRunBytesDiffer`, `RegulatorySendWithheld`, `RegulatoryResendWithheld` | page | each its outcome's `increase` | 10.6 |
| `RegulatoryAnswerAnomaly`, `RegulatoryReturnOverdue`, `RegulatoryReturnOvertaken`, `RegulatoryResendsExhausted`, `RegulatoryStaleUnsent`, `RegulatoryAttentionUnreadable` | ticket | section 10.6 by name | 10.6 |
| `AccountingGaugesUnreadable`, `AccountingGaugesAbsent` | ticket | a gauge reads no value; no instance publishes it | 10.14 |
| `WriteOffApproved`, `WriteOffSelfDealingRefused` | ticket | a write-off approved; refused as self-dealing | 7.13 |

### 10.1 A locked period with no close run - `AccountingCloseRunStalled`, `AccountingCloseRunStallUnreadable`

**What it means.** `finapp.accounting.close.run.stalled` is above zero: a period has been `LOCKED` for longer than `finapp.accounting.close.run-stalled-polls` (default 10, never
under 3) polls of `finapp.accounting.close.checks-poll` (default 30 s: five minutes) and no close run answers its present lock. The ledger's cutoff has moved, so no posting dated in the period
commits - the lock waits for nothing, by design - but **nothing is judging the books**: the check leg that births the run is not running on any instance. No money is at risk; the close cannot be proposed,
and the month's readiness is unknown.

**Diagnose.** (1) `finapp.accounting.sweeper.enabled` per instance: `0` everywhere means the schedule is switched off (`finapp.accounting.sweeper.enabled=false`). (2)
`finapp.accounting.close.leg.failed{leg="checks"}` rising, and the application log's `The accounting close schedule's checks leg failed` with its exception's class: the leg runs and fails. (3) `GET
/v1/operator/accounting/periods/{periodId}/operational-reports/CLOSE_STATUS` (`ACCOUNTING_READ`): `runState` `STALLED`, with `lockedAt` and `asOf`. (4) **The flag says on, nothing fails, and nothing is born: an overtaken worker can hold the leg.** Each instance runs the check leg on one thread, and a judge runs on it to its end, so a judge that outlives its lease (its heartbeat stops at `finapp.accounting.close.max-run`, 2 hours by default) is overtaken and still holds that thread until its statements return. `finapp.accounting.close.check{outcome="overtaken"}` rising says one concluded late; a rising `reclaims` on `GET .../periods/{periodId}/close-run` says an attempt was taken from a holder. A thread dump of the instance shows a thread named `accounting-close` inside a judge. Restarting that instance frees it and loses nothing: nothing of an unconcluded attempt is recorded, and the next worker reclaims it. Past three reclaims the next worker concludes it `FAILED`, `WORKERS_EXHAUSTED`, unjudged, and a person re-runs it (section 10.9). A leg that stops *after* the run's birth is section 10.9's.

**Act.** Restore the leg - switch the schedule on, or fix what it fails on. Within one poll it births the run and its nine attempts, insert-if-absent, whichever instance gets there first, and the gauge falls
within 15 s. **Never** insert a run by hand: the edge trigger refuses a run not under the present lock, and an attempt is the check leg's. **Do not unlock** to clear the alert: an unlock is four-eyes, moves the
cutoff back and answers a different question.

**`AccountingCloseRunStallUnreadable`** is the gauge publishing no value for 15 minutes in scrapes that complete: never a reassuring zero. Whether a close has stalled is then unknown. **With the database gone or the pool exhausted this rule cannot fire at all, because the scrape itself is lost: section 10.14 says what you see instead.** `CLOSE_STATUS` answers per period meanwhile, or refuses.

### 10.2 An operational report answered `503 accounting.ReadTimedOut`

**What it means.** `GET .../periods/{periodId}/operational-reports/{definition}` served nothing and recorded nothing, for one of two reasons, which the response's detail names. **Its time bound**: the read outlived
`finapp.accounting.report.operational-timeout` (default 30 s, generation and `statement_timeout` alike). **Its permits**: this instance was already answering `finapp.accounting.report.operational-permits` (default 2)
of them, and the door refuses the next rather than queue it, because each read holds a connection of the instance's pool (8, a 5 s wait) that postings need. Neither is a defect, and no figure is affected.

**A known limit.** `RECONCILIATION_STATUS` reads every entry posted after the month's cutoff, so its cost grows with the journal and with the age of the month. **A long-past month may no longer answer inside 30 s, and the door then answers 503 honestly**, every time, on every instance.
That is not a transient to retry. `CLOSE_STATUS` holds no journal read and is not expected to reach it.

**Act.** On a refusal for permits: retry, it is a new answer as of its own instant; another instance may have one free. On the time bound, for a recent month: look for what is slow on the database. For a long-past month: raise `finapp.accounting.report.operational-timeout`
for an operator's read, and keep the permits low, since the two together are what the door can hold of a pool. **These two reports stay unstored** (the owner, 2026-11-03: nothing is filed or relied on from them), so rising refusals are a question of the bound's configuration, not of storing them. **Never** raise the permits to or near the pool's size: that is the outage the bound exists to prevent.

### 10.3 A month that cannot be proposed

**What it means.** `POST .../periods/{periodId}/close-proposal` answered `409 accounting.CloseNotReady`, or `GET .../close-run` shows a check `FAILED`, which `AccountingCloseCheckFailed` says when it happens (section 10.9). No door discloses a `FAILED` must-pass check (the owner, 2026-11-02): each has its own way on. Periods close in order, so a stuck month holds up every later one: act on it, do not wait.

**`RECONCILIATION_DISCLOSURE` `MONTH_NOT_RUN`.** The attempt's `monthRuns.sourcesNotRun` names the sources the month requires with no `COMPLETED` `BATCH` run of a batch not repudiated in the month. **A month requires a source** that this build declares, admitted by the month's last day, not retired before its first, and whose rule set stood active by its last day (`P14-TSK-025`): each source in `monthRuns.sources` shows its `admittedOn`, `retiredOn` and `ruleSetActiveOn` and whether it is `required`. A source admitted later, retired earlier or with no rule set yet is not asked of the month and is never the reason it is not run; its unfinished or repudiated runs are still disclosed. The days are a migration's (section 10.16). A source that was **really silent** gets its run from the counterparty's **nil report**: a file with no lines dated in the month, through the same door as any other, pulled, or uploaded and attested by a second person. It posts nothing and its run completes at no items. Then re-run the check. If the batch **was** repudiated, present its corrected file or a nil: other bytes are admitted as a new batch of the same source and date, the repudiated one freeing its identity, and the same bytes are a person's readmission, since the door answers them `DUPLICATE`. **Never** insert a batch or a run by hand: that is the false pass this rule exists to stop. A source without a scheduled pull needs that upload every quiet month, a chore chosen over a false pass.

**A source with no `ACTIVE` rule set.** `finapp.reconciliation.rule.set.missing{source}` is `1` (published `finapp_reconciliation_rule_set_missing`; alert `ReconciliationRuleSetMissing`), and its report waits `PARSED` with backoff. **Since `P14-TSK-025` it holds no month's close**: a source is required only from the UTC day its first rule set version was activated, so a deployment that never uses a counterparty source is never asked its run. No migration seeds a counterparty's rule set: once a person activates it under four eyes (§3), every month ending on or after that day requires its run, the month of the activation included, and its waiting reports then go through; give a quiet month a nil report. Reconciliation's own four sources were seeded with their rule sets and are required in every month. A version superseded later changes nothing: the first activation's day stands.

**`CloseNotReady` or `CloseProposalStale` naming a source not run, after the check concluded.** A batch was repudiated since. The attempt's evidence is what stood when it concluded, and the proposal and the approval each ask again. Give the source a new counting run as above; a proposal that stands can then be approved as it is, with nothing else moved. **A repudiation landing after the approval's read, or after `CLOSED`, is not caught**: the month stays closed with no run that counts, `RECONCILIATION_STATUS` shows it, and the way on is the reopen.

**`RULE_CONFORMANCE` or `SOURCE_COMPLETENESS` `FINDING_CHANGED`.** A finding reads differently from what a person decided on, so it is a new break at the next generation, `OPEN`, with its own four eyes. Read it (`GET .../breaks`), acknowledge or resolve it, then re-run the check and `OPEN_ACCOUNTING_BREAKS`. A finding **unchanged** from what was decided needs nothing and counts as disclosed. **A source proof's evidence names only what is failing** (`P14-TSK-025`): the subject, its failing arms, the ids of the failing items its proof names (`BNPL_THREE_WAY`'s merchant legs and purchases, behind their counts and a digest of the whole failing set, so a failure past the evidence's 2000 characters still reads changed) and the count of those it only counts (`LENDING_CAPITAL`'s `unexplainedEntries=`, the position's unattributed lines), and no lifecycle status, open count or statement sequence, so the same failure under traffic reads the same and stays disclosed. A finding that reads changed is a new or different failure: another arm, another merchant or purchase, another unexplained entry, the cash chain broken. **Once, after the release**: a decided break of `LOAN_SUBLEDGER`, `LOAN_REPLAY`, `BNPL_SUBLEDGER`, `BNPL_THREE_WAY` or `POSITION` judged before it reads as changed, its evidence's format having changed; acknowledge its next generation once. **What still reads the same**: a failure differing only in an amount; **everything further on a position or the suspense once it is acknowledged**, its evidence being one fixed string, `position explained=false` or `suspense explained=false`, so new open expectations or items on it, new money movements, ride on that acknowledgement for the month; and a replay divergence of a kind already named on another fact of the same loan. So before acknowledging a position or the suspense, read reconciliation's own position report, and read the proof's own report before re-acknowledging any long-standing finding.

**`VALUATION_SET_APPROVED` shows `PENDING`.** Not a failure: the attempt waits for an approved set, deferred and re-judged, counted `finapp.accounting.close.check{outcome="deferred"}`, and neither `AccountingCloseCheckFailed` nor `AccountingCloseCheckStalled` fires for it, since the leg keeps coming back. **So a month waiting on a set is not an alert until `AccountingCloseOverdue`, and the stall alert never covers it** (`P14-TSK-020`'s (an), accepted as built by the owner on 2026-11-05), and `finapp.accounting.close.duration` and `CLOSE_STATUS`'s `RUNNING` include the wait. `CLOSE_STATUS` lists the proposed sets (`proposedValuationRateSets`): approve one, or capture and then approve. `accounting.ValuationRateStale` at the capture: a stale bound is configuration, up to two weeks. **A reference gap beyond that bound** has a four-eyes way on since `P14-TSK-026`: section 10.18. A pair never observed before the period's end still has none: take that to the owner.

**`PROJECTION_VERIFIED` `PROJECTION_DRIFT`.** A domain-path entry commits with its projection row, so drift means something wrote past the projection: **a ledger incident, not a close chore**. `ProjectionVerification` reports and never repairs. **No accountant or controller can clear it**: the way on is a ledger operator's four-eyes rebuild of the account, section 10.17 (`P14-TSK-026`), after the incident process has found the cause, with `finapp.ledger.projection.drift` as the live count; then re-run the check. The month waits meanwhile, `finapp.accounting.period.open.age` rising, and `AccountingCloseOverdue` fires on its own day. Do not unlock to clear it: the drift is in the live book and an unlock moves nothing there.

**`LEDGER_TRIAL_BALANCE`, `GL_EQUALS_LEDGER`.** Never disclosable. Unlock under four eyes, correct, or activate a mapping version from the month, and re-lock: a new run.

**`accounting.CloseProposalStale`.** At the proposal: a re-run landed while it was written, so read the run again. At the approval: something moved since the proposal; `GET .../close-proposal` says `stillStands: false`. Reject it and propose again, with reasons written for what stands now, unless the month's run was all that moved, above.

**A pending unlock blocks a proposal**: end it without its effect and propose on the same lock and run. A controller rejects it, `POST .../periods/{periodId}/unlock-rejection` (`ACCOUNTING_CLOSE_APPROVE`), or the accountant who proposed it withdraws it, `POST .../periods/{periodId}/unlock-withdrawal` (`ACCOUNTING_CLOSE_PROPOSE`). Nothing moves: no cutoff, no pin, no run (`P14-TSK-024`). Each names the unlock read by `unlockProposedAt`, which `GET /v1/operator/accounting/periods` (`ACCOUNTING_READ`) serves the accountant and `GET .../periods/{periodId}/requests` (`ACCOUNTING_CLOSE_APPROVE`) the controller; another pending is `409 accounting.RequestStale`, recorded: read it again. Approving it and locking again is not the way on: it moves the cutoff twice and costs a new run. A pending reopen on a `CLOSED` period blocks nothing but another person's reopen, a prior-period adjustment's approval included; its approval judges everything again, and names what stands then (`P14-TSK-022`: section 10.4). A reopen is refused once the next period has left `OPEN`: only the latest closed period reopens, so one proposed before the next month locked can never be approved. End it, section 10.11.

### 10.4 Correcting a closed month: a prior-period adjustment

**What it is.** An error in a `CLOSED` month is corrected by an entry dated today, in the month holding today, under four eyes (`INV-ACC-10`). A `LOCKED` month is unlocked instead. A line on a loan, BNPL, FX or reconciled position is the owning domain's correction: `422 accounting.PriorPeriodPurposeClosed` names which.

**To correct.** An accountant reads the month's figures at its present revision, and `GET .../periods/{periodId}/prior-period-adjustments` for what has already corrected it. `POST .../prior-period-adjustments` names the period, the revision, a reason and the lines; the server dates it, and the receipt says the date and the month's other corrections. A controller finds it at `GET .../prior-period-adjustments`, reads `GET .../prior-period-adjustments/{adjustmentId}`, where each line says its account's purpose and owner kind, and approves naming the date, the revision and the ids of the `POSTED` corrections it read, on a `MULTI_FACTOR` session. A correction of a break of that month: acknowledge the break if the month must close first, post the adjustment, then resolve the break naming its entry.

**One that can no longer post.** `GET .../prior-period-adjustments/{adjustmentId}` answers `stillStands: false` and `blockedBy`: `CORRECTED_NOT_CLOSED` or `CORRECTED_REVISION_MOVED`, the corrected month reopened; `TARGET_NOT_OPEN`, the month it lands in locked, closing or closed, which an unlock lifts; `TARGET_REVISION_MOVED`, that month closed and reopened while it stood, which nothing lifts. An approval is refused, `409 accounting.PeriodStale` or `accounting.PriorPeriodAdjustmentStale`, and both rows stay `PROPOSED`. Reject it, `POST .../prior-period-adjustments/{adjustmentId}/rejection`, which asks nothing of either month, and propose again against what now stands. **`AccountingAdjustmentStanding`** counts these, `finapp.accounting.adjustment.standing`: every adjustment `PROPOSED` past `finapp.accounting.adjustment.standing-after`, seven days by default on the database's clock, whether or not it can still post, since `blockedBy` is read per row by a person. Beside it, the same door: **`AccountingAdjustmentIntoClosedPeriod`**, `finapp.accounting.cutoff.refused{command="adjustment"}`, an approval the ledger refused because the entry's month is locked or closed. It counts the generic `POST /v1/ledger/adjustments/{id}/approval` as well, whose proposal is the ledger's and has no row here. Nothing else moves either: a person approves or rejects.

**`stillStands: true` and the approval refused.** `stillStands` answers for the two months only. `422 accounting.PriorPeriodPurposeClosed`, or `accounting.PriorPeriodAdjustmentInvalid` for a purpose no longer mapped, or the ledger's own refusal: it stands `PROPOSED`, and the way on is the same.

**`403 identity.AssuranceRequired` on an approval** (`P14-TSK-022`). The session is below `MULTI_FACTOR`, at `POST .../prior-period-adjustments/{adjustmentId}/approval` and at the generic `POST /v1/ledger/adjustments/{id}/approval`, each of which posts a person's decision into the journal. Nothing was read or written, and a key, where the door takes one, is unspent: step up with your enrolled factor and retry. The proposals, the reads and the rejections are not stepped up.

**`500` on an approval.** Nothing committed: a `40P01` against a wallet's decider, say (`PHASE_14_PLAN.md` §8.2), leaves both rows `PROPOSED` and the key unspent. Retry with the same key.

### 10.5 A financial statement that will not issue

**`409 accounting.ReportAwaitsClose`.** The period is `LOCKED` or `CLOSE_PROPOSED`. Nothing was claimed, and the close is not waiting on it: close the month and ask again. To read a locked month before approving it: the FINAL `TRIAL_BALANCE`, `GL_ACTIVITY` and `SUSPENSE` runs, and `GET .../periods/{periodId}/close-run`.

**A run answered `FAILED`, `DEFINITION_FAILED`: `AccountingReportRunFailed`, and on a FINAL run `AccountingClosedMonthStatementRefused`.** Every `FAILED` run is `finapp.accounting.report.run{outcome="failed"}`, the ticket. A definition's refusal is also `finapp.accounting.report.refused{outcome}`, by class and never by reason: `final_run` pages, since on a `CLOSED` month the journal moved under the close or something bypassed the machine, and the ticket fires beside it; `provisional_run`, a month still `OPEN`, and `translation_incomplete`, a set's hole on any month, do not page. **The alert tells you to look; the reason stays on the record, with no column for it on `report_run` (the owner's (aa)).** Read `accounting.ReportGenerated` for the run in the audit record: `refusal=` holds why, by code, no figure. `UNCLASSIFIED:<purpose>/<owner kind>`: a line the run's version does not map. On an open month, activate a version that maps it. On a closed one `GL_EQUALS_LEDGER` passed, so the journal changed under the close: escalate. `OUT_OF_BALANCE:<currencies>`: the books do not balance, or the equation does not. Read the period's `TRIAL_BALANCE` run, whose `verdict` and per-currency legs say which and by how much, and treat it as a ledger incident: nothing here corrects it, and a statement never will. `NOT_THE_LEDGERS:<currencies>`: a read lost or gained lines against the ledger's own totals: a defect, escalate. `TRANSLATION_INCOMPLETE:<currencies>`: the approved set values no such currency and a figure of it is not zero. The plain statement is unaffected; no door supersedes an approved set today (`P14-TSK-014`'s owner item). `CLOSE_RECORD_MISSING`, `NOT_CLOSED`: something bypassed the machine: escalate. A new ask is a new revision and fails alike until what it found has gone. **None of these blocks the close**, which reads no statement. A run with no `refusal=` failed on a read: the logs, then retry.

**`.../regeneration` answers `DIVERGED` with an empty `regeneratedSha256`.** A FINAL statement its definition now refuses: the books moved under what was issued. Read `accounting.ReportRegenerated` for the run in the audit record: `refusal=` holds why, by code, as above. Asking again finds nothing: a claim answers the standing FINAL and generates nothing. Counted `finapp.accounting.report.diverged`, which `AccountingReportDiverged` pages on (section 10.10), never repaired: escalate.

**An `untraced` count above zero in a statement's `priorPeriodCorrections`.** Entries of the `ACCOUNTING` origin dated in that month with no posted adjustment beside them: a writer bypassed the door. It has no line route under `ACCOUNTING_READ`, since no view there shows an origin: escalate to whoever holds the ledger's own read.

**`409 accounting.PriorPeriodAdjustmentStale` on an approval.** Read it again. Another correction of the month may have posted since: that is the control working, so decide knowing it.

**`422 accounting.PriorPeriodAdjustmentInvalid` naming a purpose not mapped.** The month it lands in could not close with that line on it: activate a GL mapping version that maps it, effective from an open month.

**Before reopening a month.** Its figures at the next revision still show what the adjustments already posted against it corrected, each entry being dated in a later month, so **a direct correction in the reopened month on top of one would correct it twice**. So a reopen names them (`P14-TSK-022`): `postedAdjustments` in the body of `.../reopen-proposal` and `.../reopen-approval`, both, the ids of every adjustment posted against the month whatever its revision, at most 201, the most a month can hold. A month at 201 takes no further adjustment, since an approval names at most 200 and the next would have to name 201 (`422`), and the reopen is the way on. The proposer reads them at `GET .../periods/{periodId}/prior-period-adjustments` (`ACCOUNTING_READ`); the approver at `priorPeriodAdjustments` of `GET .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_APPROVE`), and each one's lines at `GET .../prior-period-adjustments/{id}` (`ACCOUNTING_ADJUST_APPROVE`). **`409 accounting.PriorPeriodAdjustmentStale`** means another stands now: each act is judged against what is posted at that act, under the period's row, so one posted while the reopen was pending, which nothing stops, must be named at the approval though the proposal never named it. Nothing moved and the reopen stands: read again and approve naming what stands. Naming them proves the person read them, and no more: the generic adjustment door still knows nothing of accounting and refuses nothing dated in the reopened month, so agree who corrects what.

**A controller reading a statement** (`P14-TSK-022`). `GET .../report-runs/{runId}/final-statement` (`ACCOUNTING_CLOSE_APPROVE`) serves a FINAL `BALANCE_SHEET` or `INCOME_STATEMENT` with its `drillDown` and each note's `route` and `reasons` withheld, since those are directions written for an accountant, to doors under `ACCOUNTING_READ` and `RECONCILIATION_INVESTIGATE`, the close record's `reasons` naming one of them beside the one door that does open; `withheld` lists what went and `doors` says what opens. The accountant who generated the run hands over its id. A `404` is one answer for another definition, a run not FINAL, a run superseded by a reopen and an id that is nothing. A month reopened has nothing to read here until it is closed again and a statement generated, a new run with a new id; `periodStatus` is the month's own status as read with the run. `issuedSha256` hashes the issued bytes, not this view: to compare with a saved copy, ask the accountant. A figure's lines and the run's whole bytes are the accountant's read.

### 10.6 A regulatory return that needs a person

Every return here is `REFERENCE`, through a simulated adapter (ADR-0118, C4): no jurisdiction, channel or form is real, it was delivered to no one, and nothing here is a statement that any obligation was met. The numbers are `finapp.accounting.regulatory.attention{kind}` and `finapp.accounting.regulatory.submission{outcome}`. **The alerts, each named again below:** on an outcome's `increase`, `RegulatorySubmissionRejected`, `RegulatoryContraryAnswer`, `RegulatoryRunBytesDiffer`, `RegulatorySendWithheld`, `RegulatoryResendWithheld` (each `page`) and `RegulatoryAnswerAnomaly`; on a level a person's door clears, `RegulatoryResendsExhausted` and `RegulatoryStaleUnsent`; on the **growth** of a level that can hold for good, `RegulatoryReturnOverdue` and `RegulatoryReturnOvertaken`, which fire when the number stands higher than an hour ago and stop an hour later. They read only the hour's two ends, so a return clearing inside the hour hides another rising, and a read that failed at either end hides that hour. So they are a prompt, not a count: the standing list below is where a return you were told of last week still is. `RegulatoryAttentionUnreadable`: section 10.14. `AccountingCloseLegFailing` with `leg="inquiry"`: the inquiry leg threw, as below under `overdue`. `GET /v1/operator/regulatory/submissions` lists what stands, with `REGULATORY_REPORT_PREPARE`. Each row's `statusMeaning` says what its status says.

**`RegulatorySubmissionRejected`, a return `REJECTED`.** The provider's answer to what was sent, its code on the row, its bytes base64 on the read. Read them as data; they are untrusted. Correct by a new draft naming it in `correctsSubmissionId`.

**A return `REFUSED`.** A controller would not attest it, and nothing was sent. A plain new draft is free. **`WITHDRAWN`**: the same, a preparer's.

**`RegulatoryReturnOverdue`, `attention{kind=overdue}`: `SUBMITTED` past its answer horizon, one day by default.** The inquiry leg goes on. An answer that decides nothing backs it off past the horizon, to at most one question a day. **A provider that throws backs nothing off:** it keeps no answer and is asked again each lease, about 720 calls a day per return, counted `call_failed`, with no rows kept. A read serves the newest twenty answers and says how many are kept. Check `finapp.accounting.close.leg.failed{leg=inquiry}` and the read's latest answer. An `UNKNOWN` answer has no door: nobody here may assert what the provider holds. **While it stands it holds the form and month: no correction of them can be drafted, even after a reopen.** Wait, or escalate to whoever runs the provider.

**`RegulatoryResendsExhausted`, `attention{kind=resends_exhausted}`: five resends spent, the provider still answering "not received".** Find out why, then `POST .../submissions/{submissionId}/non-delivery` under `REGULATORY_REPORT_SUBMIT` from a `MULTI_FACTOR` session, and correct by a new draft naming it. The read's `nonDeliveryOpen` says when the door is open: a definite "not received" last, and the resends spent, the horizon past, or the month moved. **The door asks the provider once, at the act**, and concludes only on a "not received" said then. `503 accounting.RegulatoryProviderUnavailable`: it could not be asked or its answer not kept, nothing was concluded, ask again with the same key. `409 accounting.RegulatorySubmissionStale`: it said otherwise, or a resend is in flight; its answer is kept. Do not conclude it merely because it is slow. **A provider that lost the first send can still produce it later: before drafting a correction, ask whoever runs the provider whether two returns could stand.**

**`RegulatorySendWithheld`, `outcome=send_withheld_month_moved`, and `RegulatoryResendWithheld`, `outcome=not_resent_month_moved`: a return `SUBMITTED` whose month moved before it was delivered.** The platform does not send figures the books no longer say, on a first attempt or a resend. It asks, and goes on asking. Once the provider says "not received", `nonDeliveryOpen` is true whatever the resend count: conclude it at `.../non-delivery`, and when the month is re-closed and its runs re-issued, draft a correction naming it. **What this cannot catch:** a send that began just before the reopen committed. The month stood when that send began; it may arrive after. It reads `overtaken` like any other.

**`RegulatoryReturnOvertaken`, `attention{kind=overtaken}`: a return sent or acknowledged, its month since reopened, and no acknowledged return of its form at the month's present revision.** It stands, frozen: the month stood when its send began, and it is never edited. A draft or a rejected correction does not clear this, since the regulator still holds the figure; only an acknowledgement at the present revision does. `monthMoved`, with both revisions, stays true on its read for good: it says the books moved, while `overtaken` says nothing yet answers that. **A reopen's two receipts list these**: `overtakenReturns`, each return `SUBMITTED` or `ACKNOWLEDGED` for the month with its id, form and status, the newest twenty of `overtakenReturnsTotal`, and `overtakenReturnsNote`, which says this. Each is read at its act, so the approval's can hold more than the proposal's, and a status can have moved a moment later. The approver reads the same list before deciding, while the reopen is pending, at `GET .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_APPROVE`): section 10.11. **It is information:** it stops nothing, and nobody names it back. **It is not everything in flight:** a return not yet sent is not on it, `APPROVED` and about to send among them, nor one `UNDELIVERED`, which may still arrive late. An empty list says only that nothing stood sent. A return `APPROVED` can still go until the approval moves the revision, and after it is `RegulatoryStaleUnsent` below; the standing list above holds them all, for whoever holds `REGULATORY_REPORT_PREPARE`, and neither of a reopen's two people does. Once the month is re-closed and its runs re-issued, decide whether a correction is owed, then draft one naming the original. **If you decide none is owed, for a reopen that changed nothing, this does not clear:** the gauge and the list hold that return for good, so watch this number's increase, not its level, and keep your decision outside the platform: the owner postponed a door for it as `PI-010` on 2026-11-05, required before any real regulatory channel.

**`RegulatoryAnswerAnomaly`, an acknowledgement the screen discarded: `statedOutcome=ACKNOWLEDGED`, `readOutcome=UNKNOWN`, `anomaly=MALFORMED` on the read's answers, counted `outcome=answer_anomaly`.** A reference holding an instrument's shape decides nothing, even when it is only shaped like one: `TX2026110500001` always is, and a 16-digit reference often holds a Luhn-valid window. **The regulator has acknowledged, but the row stays `SUBMITTED` forever, with no door, holding the form and month against any correction.** Read the answer's bytes and escalate; nothing here clears it. The simulated adapter cannot reach this.

**`RegulatoryStaleUnsent`, `attention{kind=stale_unsent}`: a return not yet sent whose month has moved.** No act going forward will pass. `POST .../withdrawal` (`DRAFT`, `VALIDATED`, `APPROVED`) or `.../refusal` (`VALIDATED`, `APPROVED`), each asking nothing of the month, then draft again.

**`RegulatoryContraryAnswer`, `increase(finapp.accounting.regulatory.submission{outcome="contrary"})`: a provider's answer disagreeing with what a concluded return says.** Kept, flagged and audited as `regulatory.SubmissionContraryAnswer`, and never an edge: the row still says what it said. **To find which return:** `GET /v1/operator/regulatory/submissions` lists every return holding one, with `contraryAnswers` above zero, a page at a time (`nextAfter`, then `?after=`), and the audit record names it as its target. It stays on that list, since nothing clears a fact. Read the answer and escalate. Do not resend, and do not write to the row. A contrary answer on an `UNDELIVERED` return with a correction means **two returns may stand at the regulator**: escalate at once.

**`RegulatoryRunBytesDiffer`, `outcome=run_bytes_differ`, `409 accounting.RegulatoryRunBytesDiffer`.** A FINAL run's bytes no longer prove against its stored hash. Report runs are frozen, so this means someone wrote to one. Treat it as an integrity incident and do not draft around it.

**`409 accounting.RegulatorySourceNotFinal`.** The month is not `CLOSED`, or a source has no FINAL run at its present revision: an accountant issues it. **`422 accounting.RegulatoryFigureUnresolved`.** A form line's path found nothing, more than one, or no amount in that run; the detail names the line and path. Never fill it: the obligation's next version corrects the mapping, or the books answer why nothing is there.

**`403 accounting.SelfApprovalRefused`.** The preparer approving, or the approver sending. One identity holding two roles is why it exists. Another person acts.

**To rehearse.** `finapp.accounting.regulatory.simulation` chooses what the simulated adapter answers: `ACKNOWLEDGE` by default, `REJECT`, `UNKNOWN_THEN_ACKNOWLEDGE`, `UNKNOWN`, `NEVER_RECEIVED`, `UNAVAILABLE`. An outside environment only. **Only `NEVER_RECEIVED` rehearses a month that moved before the send:** under the default, an inquiry answers `ACKNOWLEDGED` even for a send that was withheld and never went, since the simulator remembers nothing.

#### The reference safeguarding position return: its configuration

`P14-TSK-019`, ADR-0118 decision 4, C13. A **reference** return: no jurisdiction, channel or form it names is real, and it asserts nothing about any legal obligation. It enters through the four-eyes doors, with no migration and no seed, and the block below is exactly its proposal's body. `SafeguardingReturnTest` reads this block and takes it through the real doors, the real definition and the real machine, so it cannot drift: change the form by a later form version, here first. **Its lines and rules moved in place at form and definition version 1** (`P14-TSK-023`, the owner, 2026-11-05: `committed` out of the subtraction), because no FINAL of this definition had been issued and no obligation activated. Nothing after a first issue moves in place. **That rests on there being no obligation of form `REF-SAFEGUARDING-POSITION` at version 1, in any status, where this block is proposed** (the fix round's M-2): `regulatory_obligation_one_version` holds a jurisdiction, form and version once whatever its status, so an environment that proposed `P14-TSK-019`'s block cannot propose this one at version 1, and that block left `ACTIVE` refuses every draft, `422 accounting.RegulatoryFigureUnresolved` on the paths that have gone. It fails closed: ask the owner before proposing this block there.

1. A `REGULATORY_REPORTER` proposes it: `POST /v1/operator/regulatory/obligations`, `REGULATORY_REPORT_PREPARE`, with the block as its body. The receipt names the configuration's hash.
2. A second person, a `FINANCIAL_CONTROLLER` from a `MULTI_FACTOR` session, reads `GET .../obligations/{obligationId}/attestation` and activates it: `POST .../obligations/{obligationId}/activation` under `REGULATORY_REPORT_APPROVE`, naming that hash.
3. Each month, once it is `CLOSED`, an accountant issues the run: `POST /v1/operator/accounting/report-runs` under `ACCOUNTING_REPORT_GENERATE`, definition `SAFEGUARDING_POSITION` (section 10.7's calendar). Nothing generates it by itself. Without it a draft is `409 accounting.RegulatorySourceNotFinal`.
4. Draft, validate, approve and submit as any return.

```json
{
  "jurisdictionCode": "REF",
  "formCode": "REF-SAFEGUARDING-POSITION",
  "formVersion": 1,
  "adapterName": "simulated-reference",
  "sources": [{"definition": "SAFEGUARDING_POSITION", "version": 1}],
  "lines": [
    {"code": "CASH_AT_BANK", "label": "Cash at bank", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].cashAtBank"},
    {"code": "CLEARING_RECEIVABLES", "label": "Clearing receivables", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].clearingReceivables"},
    {"code": "ASSETS", "label": "Assets: cash at bank and clearing receivables", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].assets"},
    {"code": "CUSTOMER_WALLETS", "label": "Customer wallets in credit, gross", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].customerWallets"},
    {"code": "CUSTOMER_WALLETS_NET", "label": "Customer wallets as the trial balance nets them, debt included: not the figure the position reads", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].customerWalletsNet"},
    {"code": "MERCHANT_PAYABLES", "label": "Merchant payables in credit, gross", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].merchantPayables"},
    {"code": "MERCHANT_PAYABLES_NET", "label": "Merchant payables as the trial balance nets them, debt included: not the figure the position reads", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].merchantPayablesNet"},
    {"code": "CREDIT_BALANCES", "label": "Credit balances owed back to borrowers and BNPL customers, in credit, gross", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].creditBalances"},
    {"code": "CREDIT_BALANCES_NET", "label": "Credit balances as the trial balance nets them, debt included: not the figure the position reads", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].creditBalancesNet"},
    {"code": "CLEARING_PAYABLES", "label": "Clearing payables: money owed onward, the cash not yet gone", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].clearingPayables"},
    {"code": "SUSPENSE_HELD", "label": "Suspense held as owed: its net credit", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].suspenseHeld"},
    {"code": "LIABILITIES", "label": "Liabilities", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].liabilities"},
    {"code": "PRINCIPAL_OUTSTANDING", "label": "Principal outstanding: loans, lines and BNPL", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].principalOutstanding"},
    {"code": "PRINCIPAL_COMMITTED", "label": "Principal committed: accepted loans awaiting disbursement. Disclosed, not subtracted: it has created no wallet money, so no liability or journal line stands behind it", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].principalCommitted"},
    {"code": "PRINCIPAL_WRITTEN_OFF", "label": "Principal written off", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].principalWrittenOff"},
    {"code": "PRINCIPAL_UNREPAID", "label": "Principal unrepaid: outstanding and written off, committed not among it. A disclosure, and an upper bound on the money capital created", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].principalUnrepaid"},
    {"code": "CUSTOMER_FUNDED", "label": "Customer-funded liabilities: liabilities less principal unrepaid. Can read negative, because principal unrepaid does not fall when loan money leaves wallets", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].customerFundedLiabilities"},
    {"code": "POSITION", "label": "Position after principal unrepaid: a disclosure. Negative is short. Positive does not prove cover: it does not fall when loan money leaves, so it can read over in a short month. See POSITION_BEFORE", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].position"},
    {"code": "POSITION_BEFORE", "label": "Position before principal unrepaid, the figure that warns: assets less liabilities, every liability a claim on cash whoever funded it. Negative is short, whatever POSITION reads", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].positionBeforePrincipalUnrepaid"},
    {"code": "MEMO_CHARGEBACKS", "label": "Memo, out of the position: chargebacks recoverable", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].chargebackRecoverable"},
    {"code": "MEMO_FX_POSITION", "label": "Memo, out of the position: FX position", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].fxPosition"},
    {"code": "MEMO_SUSPENSE_DEBIT", "label": "Memo, out of the position: suspense at a net debit", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].suspenseNetDebit"},
    {"code": "MEMO_WALLET_DEBT", "label": "Memo, out of the position: customer wallets in debit, owed by those customers and no cover for anyone else", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].customerWalletDebt"},
    {"code": "MEMO_MERCHANT_DEBT", "label": "Memo, out of the position: merchant payables in debit, owed by those merchants and no cover for anyone else", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].merchantDebt"},
    {"code": "MEMO_CREDIT_BALANCE_DEBT", "label": "Memo, out of the position: credit balances in debit, owed by those holders and no cover for anyone else", "source": "SAFEGUARDING_POSITION", "figure": "body.currencies[currency=*].creditBalanceDebt"}
  ],
  "rules": [
    {"code": "ASSETS_FOOT", "type": "EQUALS", "left": ["ASSETS"], "right": ["CASH_AT_BANK", "CLEARING_RECEIVABLES"]},
    {"code": "LIABILITIES_FOOT", "type": "EQUALS", "left": ["LIABILITIES"], "right": ["CUSTOMER_WALLETS", "MERCHANT_PAYABLES", "CREDIT_BALANCES", "CLEARING_PAYABLES", "SUSPENSE_HELD"]},
    {"code": "CUSTOMER_WALLETS_FOOT", "type": "EQUALS", "left": ["CUSTOMER_WALLETS"], "right": ["MEMO_WALLET_DEBT", "CUSTOMER_WALLETS_NET"]},
    {"code": "MERCHANT_PAYABLES_FOOT", "type": "EQUALS", "left": ["MERCHANT_PAYABLES"], "right": ["MEMO_MERCHANT_DEBT", "MERCHANT_PAYABLES_NET"]},
    {"code": "CREDIT_BALANCES_FOOT", "type": "EQUALS", "left": ["CREDIT_BALANCES"], "right": ["MEMO_CREDIT_BALANCE_DEBT", "CREDIT_BALANCES_NET"]},
    {"code": "PRINCIPAL_UNREPAID_FOOT", "type": "EQUALS", "left": ["PRINCIPAL_UNREPAID"], "right": ["PRINCIPAL_OUTSTANDING", "PRINCIPAL_WRITTEN_OFF"]},
    {"code": "CUSTOMER_FUNDED_FOOT", "type": "EQUALS", "left": ["CUSTOMER_FUNDED", "PRINCIPAL_UNREPAID"], "right": ["LIABILITIES"]},
    {"code": "POSITION_FOOT", "type": "EQUALS", "left": ["POSITION", "CUSTOMER_FUNDED"], "right": ["ASSETS"]},
    {"code": "POSITION_BEFORE_FOOT", "type": "EQUALS", "left": ["POSITION_BEFORE", "LIABILITIES"], "right": ["ASSETS"]}
  ],
  "reason": "The reference safeguarding position return, form version 1. No legal obligation is asserted."
}
```

**Every rule is arithmetic that must hold in any month.** No rule judges a position, so **a shortfall validates, and is reported**: a rule on it would make the return impossible to validate in exactly the month it matters, and the way on would be to change the configuration. Nothing alerts on one: `PI-011`, required before any real regime. A person sees it on the form's `POSITION_BEFORE` line, the figure that warns, and `POSITION` beside it, at validation and at attestation, negative and never floored, and in the run's `positionBeforePrincipalUnrepaidNegative` and `positionNegative` flags per currency. **A `*_FOOT` rule failing on `CUSTOMER_WALLETS`, `MERCHANT_PAYABLES` or `CREDIT_BALANCES`** means the per-account read and the trial balance disagree on one snapshot: treat it as an integrity incident. The return stays `DRAFT`, and its way on is a withdrawal.

**A currency.** Every figure is per currency and none crosses one. A currency with wallets and no lending carries true zeros from the run's own reads. A currency nothing touched has no line. A month with no line in any currency cannot be drawn: `422 accounting.RegulatoryFigureUnresolved`.

**`PRINCIPAL_COMMITTED` has no journal line behind it, and is disclosed, not subtracted.** An accepted loan awaiting its disbursement has posted nothing and has created no wallet money, so no liability stands behind it to subtract it from. It is capital's term: capital recognised = headroom + outstanding + committed + written off. The figure rests on lending's frozen rows, and the run's `drillDown` says so; no route under `ACCOUNTING_READ` lists those loans. `GET /v1/operator/reports/lending/capital?date={lastDay}` states the term under `LENDING_INVESTIGATE`.

**Reading it.** **`POSITION_BEFORE` is the figure that warns.** It subtracts nothing, every liability a claim on cash whoever funded it: negative there is short, whatever `POSITION` reads. **`PRINCIPAL_UNREPAID`, `CUSTOMER_FUNDED` and `POSITION` are a disclosure, and a positive `POSITION` does not prove cover.** `PRINCIPAL_UNREPAID` is the principal outstanding and written off, an upper bound on the money capital created still in wallets and payables: it does not fall when a borrower withdraws what a loan created. **So a shortfall can still be masked on `POSITION`:** it is `POSITION_BEFORE` + `PRINCIPAL_UNREPAID`, so it reads zero or above in a month short on `POSITION_BEFORE` whenever `PRINCIPAL_UNREPAID` is at least that shortfall. Capital 30, deposits 100, 30 lent and withdrawn and a loss of 20 reads `POSITION` +10 and `POSITION_BEFORE` -20. **By double entry**, `POSITION` is capital + results + written off - what the return leaves out (receivables, chargebacks, the FX position, a holder's debt, suspense at a net debit), and `POSITION_BEFORE` is capital + results - outstanding - the same. **An acceptance moves neither:** capital 30, deposits 100, a loss of 50 and 30 accepted reads -20 on both. **A disbursement**, or a BNPL purchase, lowers `POSITION_BEFORE` by what it lands as a credit balance, wallet or payable money created and no cash entering, and raises `POSITION` only by what it clears of a holder's debt and by any fee it earns: landing on a wallet in credit with no fee, that month then reads -20 and -50; onto a wallet at -10, a disbursement of 30 clears 10 and lands 20, `POSITION` +10 and `POSITION_BEFORE` -20. `CUSTOMER_FUNDED` can read negative, because `PRINCIPAL_UNREPAID` can exceed what is still owed, and that is a credit to nobody. **A holder in debit is no cover for anyone else:** the liabilities are their accounts in credit, gross, so wallets at +100 and -30 owe 100, and the -30 is `MEMO_WALLET_DEBT`, a receivable from that customer. `finapp.ledger.negative.positions` counts those accounts now; this is what they stood at at the cutoff. An FX conversion not yet covered is a true shortfall in its currency: `MEMO_FX_POSITION` says why.

### 10.7 The close calendar and a lock - `AccountingCloseOverdue`, `AccountingScheduleOff`

**The calendar, as the code holds it.** (1) **A month opens by itself**: the schedule's open leg, `finapp.accounting.close.open-poll` (default an hour), inserts it if absent. No door births a period. `GET /v1/operator/accounting/periods` (`ACCOUNTING_READ`) lists them. (2) **It may lock from its last day plus two business days** (`finapp.accounting.period.lock-grace-business-days`; Monday to Friday, since no holiday calendar exists; never under 2), and only once the month before it is `CLOSED`. (3) **A person locks it**: `POST .../periods/{periodId}/lock` (`ACCOUNTING_CLOSE_PROPOSE`), keyed. In one transaction the ledger's cutoff moves to the month's end and the GL mapping version in force is pinned. From that commit no entry dated in the month commits (`INV-ACC-08`). `409 accounting.PeriodNotLockable`: too early, or the month before is not closed. `accounting.CutoffBusy`: postings held the cutoff through its attempts; nothing moved, ask again. (4) **The checks run by themselves**: within one poll of `finapp.accounting.close.checks-poll` (default 30 s) the schedule births the run and its nine attempts. `GET .../periods/{periodId}/close-run` (`ACCOUNTING_READ`). (5) **A valuation rate set is captured and approved**: `POST /v1/operator/accounting/valuation-rate-sets` (`ACCOUNTING_CLOSE_PROPOSE`), then `POST .../valuation-rate-sets/{setId}/approval` (`ACCOUNTING_CLOSE_APPROVE`), a different person. `VALUATION_SET_APPROVED` waits for it. (6) **An accountant proposes**: `POST .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_PROPOSE`), keyed, a reason per `DISCLOSED` attempt. Section 10.3 if it is refused. (7) **A controller decides**: reads `GET .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_APPROVE`), each check with `concludedAt`, when its attempt concluded: what it judged was read no later than that, and nothing judges it again at the approval. Then `POST .../periods/{periodId}/close-approval` (`ACCOUNTING_CLOSE_APPROVE`), from a `MULTI_FACTOR` session (`P14-TSK-024`, the owner's acceptance: `403 identity.AssuranceRequired` below it, nothing read or written and the key unspent; step up and retry), or `POST .../periods/{periodId}/close-rejection` (`ACCOUNTING_CLOSE_APPROVE`), from any session. Each names the proposal read. The accountant who proposed cannot: `403 accounting.SelfApprovalRefused`. (8) **The month's safeguarding return is a person's too**: each month, once it is `CLOSED`, an accountant issues the run, `POST /v1/operator/accounting/report-runs` (`ACCOUNTING_REPORT_GENERATE`), definition `SAFEGUARDING_POSITION`. **Nothing issues it for them**, and a month nobody issues shows nothing at all (`PI-011`). Without it a draft of that return is `409 accounting.RegulatorySourceNotFinal`. Section 10.6 has the rest, a reference return on a simulated channel.

**Each month's runs: the monthly chore** (`P14-TSK-012`'s (e), as the owner narrowed it by (c) and (k), built by `P14-TSK-025`). `RECONCILIATION_DISCLOSURE` asks one counting run of every source the month requires: declared by the build, admitted by the month's last day, not retired before its first, and with a rule set active by its last day. A source with a scheduled pull brings its reports itself. **A source without one, quiet that month, needs a person's nil report**: uploaded and attested by a second person, dated in the month, before the lock's checks run or followed by a re-run (section 10.3). A source not yet admitted, retired before the month, or whose rule set no person has activated, needs nothing. `GET .../periods/{periodId}/operational-reports/RECONCILIATION_STATUS` (`ACCOUNTING_READ`) lists every source with its days and whether the month requires it.

**To undo a lock**: `POST .../periods/{periodId}/unlock-proposal` (`ACCOUNTING_CLOSE_PROPOSE`), then `POST .../periods/{periodId}/unlock-approval` (`ACCOUNTING_CLOSE_APPROVE`), a different person from a `MULTI_FACTOR` session, naming the unlock read by `unlockProposedAt` from `GET .../periods/{periodId}/requests` (`ACCOUNTING_CLOSE_APPROVE`): the cutoff moves back, FINAL runs are superseded, the next lock is a new run. **A pending unlock that should not go through** is ended without its effect (`P14-TSK-024`): rejected by a different person, `POST .../periods/{periodId}/unlock-rejection` (`ACCOUNTING_CLOSE_APPROVE`), or withdrawn by the accountant who proposed it, `POST .../periods/{periodId}/unlock-withdrawal` (`ACCOUNTING_CLOSE_PROPOSE`). The lock, its pin and the cutoff stay, and a new unlock may be proposed. Each names the unlock read by `unlockProposedAt`, as the approval does: an end makes a pending request replaceable, so a decision meant for one never reaches the next, `409 accounting.RequestStale`, recorded `FAILED`; read it again and decide. The proposer rejecting their own is `403 accounting.SelfApprovalRefused`; anyone else withdrawing it `403 accounting.NotTheProposer`, each recorded `FAILED`; with none pending, `409 accounting.PeriodStale` says what became of the one named. Neither end takes a `MULTI_FACTOR` session: an end moves nothing. The approval does, and `403 identity.AssuranceRequired` below it reads and writes nothing, the key unspent: step up with your enrolled factor and retry.

**What each publishes and records** (`AUDITABLE_ACTIONS.md`): a lock, a close proposal, a close and a reopen's approval each publish an event (`accounting.AccountingPeriodLocked`, `accounting.AccountingPeriodCloseProposed`, `accounting.AccountingPeriodClosed`, `accounting.AccountingPeriodReopened`) and record the act. **A rejection and a reopen's proposal publish no event**: they record the act only (`accounting.PeriodCloseRejected`, `accounting.PeriodReopenProposed`), so a consumer of the events sees nothing until a close or a reopen happens. Nor does an unlock's or a reopen's end (`accounting.PeriodUnlockRejected`, `accounting.PeriodUnlockWithdrawn`, `accounting.PeriodReopenRejected`, `accounting.PeriodReopenWithdrawn`).

**`AccountingCloseOverdue`.** `finapp.accounting.period.open.age`, published `finapp_accounting_period_open_age_days`: whole days the oldest month not `CLOSED` has stood past the day it could lock, on the database's clock. Over 7 for an hour. Seven calendar days hold five business days whichever weekday the grace ends on, so a month closed within a working week of being lockable never reads it. **That target is a default the owner accepted as built on 2026-11-05 (`P14-TSK-020`'s (af)), not a figure a regime has stated.** Read `CLOSE_STATUS` at `GET .../periods/{periodId}/operational-reports/CLOSE_STATUS` (`ACCOUNTING_READ`) for the month, and act on whatever holds it: section 10.9 and 10.3 for checks, 10.13 for a break, 10.1 for no run. Later months wait behind it, in order.

**What it reads alike, and why that is not noise.** A month at any state short of `CLOSED`. So a proposal waiting on a controller has no meter of its own and this covers it, the month and not the proposal; a set awaited likewise. **A reopen fires it within the hour** if the reopened month is past its week, as nearly every reopened month is: the month is unclosed, the alert is true, and it clears at the re-close. A pending reopen on a `CLOSED` row moves nothing and reads nothing.

**`AccountingScheduleOff`.** `finapp.accounting.sweeper.enabled` is 0 on every instance for 15 minutes: no month opens, no run is born, no check is claimed, no orphaned report completes, no return is asked after. One instance off is no break, since the others take every leg, and reads nothing here. Set `finapp.accounting.sweeper.enabled` back to true. **What it cannot see**: a schedule on and failing, which is `AccountingCloseLegFailing`.

### 10.8 A refused posting - `AccountingPostingIntoClosedPeriod`

**What it means.** `finapp.accounting.cutoff.refused{command="posting"}` or `{command="reversal"}` rose: an entry dated on or before the ledger's cutoff was refused `ledger.PeriodClosed` and **nothing was written**. Also counted `finapp.ledger.posting{outcome="refused"}`. At rest nothing counts: every back-dating system poster floors its posting date to the first open date (C3, counted `finapp.accounting.cutoff.floored`, no refusal and no alert), and a rule dated today is never reached by a lock. Every reversal poster dates today or later, so no person's reversal reaches it either. So one that counts is a defect in a poster, or a clock behind the database's.

**Diagnose.** The caller answered or logged `ledger.PeriodClosed`: find which flow by the time and `finapp.ledger.posting{outcome="refused"}` beside it. `GET /v1/operator/accounting/posting-rules` (`ACCOUNTING_READ`) says each rule's dating class and what a lock does to it. A system poster's sweep re-drives a refused posting, so a rate that holds is one defect, not many.

**Act.** It is an engineer's: **the refused posting stands refused until the defect is fixed**, and the money flow it belongs to waits with it. **What you cannot do**: there is no door that lets one entry through a cutoff. An unlock moves the cutoff back for every writer, is four-eyes (section 10.7) and answers a different question; it cannot reach a `CLOSED` month at all. Do not write to the ledger's cutoff.

### 10.9 A failed check, or a stalled leg - `AccountingCloseCheckFailed`, `AccountingCloseCheckStalled`, `AccountingCloseCheckStallUnreadable`, `AccountingCloseLegFailing`

**`AccountingCloseCheckFailed`.** `finapp.accounting.close.check{check,outcome="failed"}` rose: an attempt concluded `FAILED`. It fires for an hour per failure, whoever asked, and then stops, though the check may stand `FAILED`: `AccountingCloseOverdue` is what stays. It does not fire for `deferred` (a judge that cannot answer yet), `overtaken` (a slower worker's conclusion the fence refused) or `disclosed`. `GET .../periods/{periodId}/close-run` (`ACCOUNTING_READ`): each check's newest attempts, with `failures`, `evidence` and `reclaims`. **Section 10.3 holds each check's way on, by name.** `WORKERS_EXHAUSTED`: the attempt's workers kept dying past three reclaims and it concluded unjudged: find why they die, then re-run. `PERIOD_MOVED`: an unlock landed under it; the new lock has a new run. To re-run: `POST .../periods/{periodId}/close-run/checks/{check}/rerun` (`ACCOUNTING_CLOSE_PROPOSE`), keyed and reasoned, a new attempt and never an edit. **What you cannot do**: disclose a `FAILED` must-pass check; no door does (the owner, 2026-11-02).

**`AccountingCloseCheckStalled`.** `finapp.accounting.close.check.stalled`: attempts of a present run that have stood claimable for `finapp.accounting.close.run-stalled-polls` polls of the check leg (default 10, 5 minutes), with nobody claiming them and no claim of a present run live. The run was born, and then its leg stopped. **Not counted, so not firing**: `OPEN_ACCOUNTING_BREAKS` while a raising check of its run is `PENDING`, which the claim itself holds back; a deferred attempt, whose lease moves forward each poll; attempts queued behind a live worker, since one instance judges one attempt at a time and a long judge is no stall. **What it cannot see**: a judge hung with its heartbeat alive holds the count at zero for `finapp.accounting.close.max-run` plus a lease from its last claim, 2 h 02 by default, past which the renewal is refused and the lease lapses. **A reclaim starts a new window**, and each hung judge keeps its instance's one check-leg thread. So a judge that hangs every time walks the fleet, up to (`finapp.accounting.close.max-reclaims` + 1) windows: on 1 to 4 instances this alert fires only once every leg is held, about 2 h 08 on one, 4 h 10 on two, 6 h 12 on three, 8 h 14 on four; **on 5 or more it never fires**, and at about 8 h 08 the next reclaim concludes the attempt `FAILED`, `WORKERS_EXHAUSTED`, a ticket by `AccountingCloseCheckFailed`, with four check legs still stuck until those instances restart. `finapp.accounting.close.check{outcome="overtaken"}` says nothing while they hang, and a rising `reclaims` on `GET .../periods/{periodId}/close-run` is the early sign.

**Diagnose and act**, as section 10.1's four steps: the schedule off (`AccountingScheduleOff`), the leg failing (`AccountingCloseLegFailing`), the database, an overtaken worker holding the thread. Restore the leg and the next poll claims every attempt; the gauge falls within 15 s. Nothing here needs a person's act on the rows, and **no door claims an attempt by hand**. **`AccountingCloseCheckStallUnreadable`** is the gauge publishing no value for 15 minutes in scrapes that complete: section 10.14, which also says when it cannot fire; `GET .../close-run` still answers per period, or refuses.

**`AccountingCloseLegFailing`.** `finapp.accounting.close.leg.failed{leg}` rose more than once in two hours: `open`, `checks`, `orphans` or `inquiry`. One failure alone is silent, a transaction lost at a bad moment. Two hours so that the hourly `open` leg's second failure is seen. The log says `The accounting close schedule's <leg> leg failed` with its exception's class, no message; `checks` also counts a conclusion that could not commit (`A close check could not be concluded`), whose lease then lapses and is reclaimed, nothing having passed. `inquiry`: section 10.6. **What it means for the month**: `open` failing means no next month opens; `checks`, no run and no judgement; `orphans`, a report run stuck `GENERATING` past its lease. Fix what it fails on. A restart loses nothing.

### 10.10 A divergence: a FINAL report that does not reproduce - `AccountingReportDiverged`

**What it means.** `POST .../report-runs/{runId}/regeneration` (`ACCOUNTING_REPORT_GENERATE`), keyed, read a FINAL run's definition again over its pinned inputs and the bytes differ, or the definition refused what it issued. `finapp.accounting.report.diverged{definition}` counts it once per keyed regeneration. The alert fires only when somebody or something asks: nothing regenerates on a schedule, so **a run nobody regenerates is not proven either way**.

**Diagnose.** `DIVERGED` with both hashes: the bytes differ. An empty `regeneratedSha256`: section 10.5's refusal, `refusal=` on `accounting.ReportRegenerated`. A definition's version retired from the build refuses the regeneration instead and is no divergence. Report runs and the month's journal are frozen, so a true divergence means a writer went past a trigger, or a read of the report changed behaviour without a new definition version: compare the run's definition version with the build.

**Act.** **Escalate as an incident; a FINAL report that does not reproduce is never repaired.** No door edits a run, and asking again finds nothing new: a replay answers the same record. **What you cannot do**: supersede a FINAL run except by a reopen, and a reopen is not a repair; it issues new figures beside the old.

### 10.11 A reopen - `AccountingClosedMonthLostItsRun`

**The door.** `POST .../periods/{periodId}/reopen-proposal` (`ACCOUNTING_CLOSE_PROPOSE`), then `POST .../periods/{periodId}/reopen-approval` (`ACCOUNTING_CLOSE_APPROVE`), a different person from a `MULTI_FACTOR` session (`P14-TSK-024`, the owner's acceptance), each keyed and reasoned, each naming the month's posted adjustments (section 10.5). The approval also names the reopen read, `reopenId` from `GET .../periods/{periodId}/close-proposal`: a reopen withdrawn and another proposed is never approved by a decision on the first, `409 accounting.RequestStale`, recorded. Only the latest `CLOSED` month reopens, and only while the next is still `OPEN`. The approval moves the cutoff back one period, supersedes the month's FINAL runs and never deletes one, and moves the revision on; the month is then `OPEN` and closes again from a new lock, under a new run. **A pending reopen is ended without its effect** (`P14-TSK-024`): rejected by a different person, `POST .../periods/{periodId}/reopen-rejection` (`ACCOUNTING_CLOSE_APPROVE`), or withdrawn by the accountant who proposed it, `POST .../periods/{periodId}/reopen-withdrawal` (`ACCOUNTING_CLOSE_PROPOSE`), each keyed and reasoned and naming the reopen by `reopenId` (the accountant reads it at `GET /v1/operator/accounting/periods`); anyone else withdrawing it is `403 accounting.NotTheProposer`, recorded. The month stays `CLOSED` at its revision, nothing is superseded, and a reopen may be proposed again. This is how a reopen proposed before the next month locked leaves, since no approval can take it. An approval racing an end waits on the month's row: one of them happens, and the other answers `409 accounting.PeriodStale` saying which.

**What fires, and why that is right.** `AccountingCloseOverdue`, within the hour (section 10.7). `RegulatoryReturnOvertaken`, for each return sent or acknowledged for that month (section 10.6): each of the reopen's two receipts lists them in `overtakenReturns`, as read at that act and deciding nothing, the newest twenty of `overtakenReturnsTotal`. **The approver reads it before deciding**: `GET .../periods/{periodId}/close-proposal` (`ACCOUNTING_CLOSE_APPROVE`) serves `overtakenReturns` while a reopen is pending, by the receipts' statement, read holding nothing. The approval's receipt reads its own holding the month, so it holds what stood at the approval, and differs from what was read before deciding when a return was sent or concluded in between; `overtakenReturnsNote` says so. Section 10.6 says what the list leaves out: an empty one is not nothing in flight. `RegulatoryStaleUnsent`, for each draft of it. Each clears as its section says.

**`AccountingClosedMonthLostItsRun`.** `finapp.accounting.close.month.unrun`: in the latest `CLOSED` month, sources the month requires holding a repudiated run and no run that counts, by the check's own rule (`P14-TSK-025`). A batch was repudiated after the approval read the month, which takes no period row, so the month closed or stays closed with its evidence withdrawn. Read it at `GET .../periods/{periodId}/operational-reports/RECONCILIATION_STATUS` (`ACCOUNTING_READ`). **What the code does today**: nothing else. No check runs again and no report is re-issued. **The owner ruled on `P14-TSK-012`'s (l), 2026-11-06**: the limit stands, this stays a ticket, and the way on is the reopen, then a corrected file or a nil report, then the re-close (section 10.3), and that is a person's decision weighing what was issued. **What it cannot see**: an older month's repudiation, since only the latest closed month can reopen; `RECONCILIATION_STATUS` shows those when asked. A source with no run at all lost none, so it never reads here, and neither does one the month does not require: admitted after it, retired before it, or with no rule set active by its last day; `RECONCILIATION_STATUS` says which, per source. It stops firing when the next month closes.

### 10.12 The first mapping activation - `AccountingMappingGap`

**Production starts with no version** (C2): nothing activates one for you, and no migration seeds one. Until the first activation the GL's reads answer `409 accounting.NoActiveMapping`, a lock pins nothing, and that month's `GL_EQUALS_LEDGER` is `FAILED`, `NO_PINNED_VERSION`, so **activate before the first lock**: a month locked with no pin needs an unlock first.

**The steps.** (1) An accountant proposes the whole chart and its rules: `POST /v1/operator/accounting/mapping-versions` (`ACCOUNTING_MAPPING_PROPOSE`), keyed and reasoned, effective from a stated month. `GET .../mapping-versions/{versionId}` (`ACCOUNTING_READ`). (2) A different person reads what would change: `GET .../mapping-versions/{versionId}/diff` (`ACCOUNTING_MAPPING_APPROVE`). (3) They activate it: `POST .../mapping-versions/{versionId}/activation` (`ACCOUNTING_MAPPING_APPROVE`), or refuse it, `POST .../mapping-versions/{versionId}/rejection` (`ACCOUNTING_MAPPING_APPROVE`). The proposer cannot: `403 accounting.SelfApprovalRefused`. **Verify**: the audit record's `accounting.MappingActivated`, the event `accounting.GlMappingVersionActivated`, and `GET .../gl/balances` (`ACCOUNTING_READ`) answering.

**Refusals.** `accounting.MappingNotTotal`: the ledger's chart holds a purpose the version does not map, judged as the activation's own transaction reads the chart. `accounting.MappingIncompatible`: a purpose's classification against its ledger type. `accounting.MappingRetroactive`: its month is earlier than a version already activated, or a period at or after it is locked or closed; a version takes effect forward, never over what is pinned. `accounting.MappingStale`: another activation won.

**`AccountingMappingGap`.** `finapp.accounting.gl.unmapped` above zero for 15 minutes: lines from the first open month on whose purpose and owner kind the active version does not map, shown in the GL's unmapped bucket and never dropped. A release added a purpose and no version maps it. Propose and activate a version that does, effective from an open month; a month holding such lines fails `GL_EQUALS_LEDGER`, raising `MAPPING_GAP` breaks (section 10.13). **What it cannot see**: no version at all, which reads 0, since that state is the steps above and is not a gap; a locked or closed month's lines, which its pin and its checks answer for.

### 10.13 A break standing open - `AccountingBreakAged`

**What it means.** `finapp.accounting.break.aged{type}`: breaks still `OPEN` past `finapp.accounting.break.aged-after`, seven days by default on the database's clock. Nobody has decided on them. While its month is unclosed a break blocks `OPEN_ACCOUNTING_BREAKS`; on a `CLOSED` month it is what an overtaken worker's raise leaves, never hidden, and corrected as any finding after a close. **It is not `finapp.accounting.break.open`**, the dashboard's count of every status but `RESOLVED`, per generation: an `ACKNOWLEDGED` break, even one a later generation has superseded, was decided under four eyes and can stand for good, and no alert reads it.

**Act.** The alert names no month and `GET .../breaks` (`ACCOUNTING_READ`) requires `?period=`, so walk the months from `GET /v1/operator/accounting/periods` (`ACCOUNTING_READ`), oldest unclosed first, then the closed ones, since a raise can land on one. Then `GET .../breaks/{breakId}` (`ACCOUNTING_ADJUST_APPROVE`). Either acknowledge with a disclosure, `POST .../breaks/{breakId}/acknowledgement-proposal` (`ACCOUNTING_ADJUST_PROPOSE`) then `POST .../breaks/{breakId}/acknowledgement-approval` (`ACCOUNTING_ADJUST_APPROVE`), a different person; or correct through the owning domain's door and resolve naming the entry, `POST .../breaks/{breakId}/resolution` (`ACCOUNTING_ADJUST_APPROVE`). **What you cannot do**: a break posts nothing, and nothing here corrects a figure. `FINDING_CHANGED`: section 10.3.

### 10.14 A gauge that cannot be read, or is not there - `AccountingGaugesUnreadable`, `AccountingGaugesAbsent`, `RegulatoryAttentionUnreadable`

**How accounting's gauges read.** On the instance's one gauge thread, `gauge-refresher`, and never on the scrape: a scrape answers the last reading and asks that thread for another. Since `P15-TSK-024` that thread is the platform's, behind every module's gauges (section 11.8); until then accounting had its own, `accounting-gauges`. Each read is a `REPEATABLE READ READ ONLY` snapshot armed with `finapp.accounting.gauges.statement-timeout` (default 2 s; `finapp.accounting.gl.unmapped` keeps `finapp.accounting.gl.statement-timeout`, 10 s, since it counts journal lines), with a floor of 15 s (60 s for `finapp.accounting.gl.unmapped` and `finapp.accounting.close.month.unrun`). So accounting adds nothing to a scrape's time, and every gauge of the instance together holds at most one connection of its pool. A read that fails or outlives its bound publishes no value, NaN, and **never zero and never its last number**. The log names the gauge and an exception's class.

**`AccountingGaugesUnreadable`, `RegulatoryAttentionUnreadable`, and the two stall rules' unreadable sections.** A gauge publishing NaN for 15 minutes **in scrapes that complete**: an accounting read is failing while the rest of the scrape is not, such as one statement past its bound or a grant gone. While a gauge is NaN **its threshold alert cannot fire**: whether a close has stalled is unknown, not fine. Every instance runs every read, so `pg_stat_activity` shows them by their statement. Raising the timeout is a choice to wait longer on that one thread, so on every other accounting gauge behind it.

**A read that never returns: restart that instance.** The data source sets no `socketTimeout`, so a socket the network black-holed never returns and no statement timeout reaches it. It holds the one thread, so **every gauge on that instance, every module's, goes to no value**: it stands on its last reading until its ask has gone unanswered for `finapp.gauges.stale-after` (2 minutes), and then says so, and `GaugesStale` tickets on that instance (section 11.8). That is the right signal: nothing is being read. A thread dump shows `gauge-refresher` inside a read. Nothing else frees it; nothing is lost by the restart, since a gauge decides nothing. *(Until `P15-TSK-024`: "every accounting gauge", standing "for four floors, a minute for most and four for the two with the 60 s floor".)*

**The whole scrape holds.** Since `P15-TSK-024` no gauge reads on the scrape thread, so with the pool exhausted or the database gone a scrape still completes at once: `up` stays 1, each read gauge publishes NaN once its read fails, and the unreadable rules above hold their 15 minutes. A database in trouble makes every read wait the pool's 5 s (`spring.datasource.hikari.connection-timeout`) and fail, about sixty classes in turn, so the one thread falls behind: each failed read is NaN at every scrape, so the unreadable rules hold, and the canary, a constant read between them, is NaN for most of each pass, which `GaugesStale` counts (section 11.8). A database that answers slowly but answers is the harder case: every gauge alternates between a number and NaN, no `for` above holds, and only `GaugesStale` sees it *(the fix round of 2026-11-08; until then this paragraph said every gauge of the instance went NaN past `finapp.gauges.stale-after`, and `GaugesStale` waited for its canary to hold NaN for 5 minutes, which a thread behind by less than about seven minutes never gives)*. *(Until `P15-TSK-024` about sixty other modules' classes read on the scrape thread: measured with the database gone, one scrape took 96.7 s against the local 5 s timeout, `up` went to 0, no NaN was stored, and none of these rules could hold its `for`.)*

**What you see instead.** One instance in trouble fires its unreadable rules, and `GaugesStale` once its thread stops answering or falls behind. An instance that cannot be scraped at all pages `InstanceDown`, and a fleet gone from the scrape pages `FinappTargetsAbsent` (section 11.8, `infra/prometheus/rules/platform.yml`). Still read the application's logs and the database directly in a database incident: these alerts take minutes. *(Until `P15-TSK-024` nothing in this repository read `up`; the owner deferred a rule on it to Phase 15 on 2026-11-05, `P14-TSK-020`'s (am).)*

**Absent.** `AccountingGaugesAbsent` fires when no instance publishes a gauge at all for 5 minutes: the fleet is down or unscraped (`FinappTargetsAbsent` pages beside it), every instance's scrape is being lost, or a release dropped a meter. Every threshold rule on it is green for the wrong reason, so believe nothing else here until it clears. Check the scrape target first; one instance's series missing is not this alert.

### 10.16 Admitting or retiring a settlement source - what a month requires of it

*(Added by `P14-TSK-025`, 2026-11-06; settlement `V018`; the owner's rulings of 2026-11-06 on `P14-TSK-012`'s (c), (e) and (k); ADR-0117's note of that date.)*

**What it means.** Each row of `settlement.source` carries `admitted_on`, the first day the source served, and `retired_on`, the day it was retired, present exactly when the row is `RETIRED`. A month requires a counting run of a source this build declares, admitted by the month's last day, not retired before its first, and whose rule set stood active by its last day: the first activation of a version, a person's four-eyes act (section 3), or for reconciliation's four seeded sources their admission. `RECONCILIATION_DISCLOSURE` (section 10.3), the close's re-judge and `finapp.accounting.close.month.unrun` (section 10.11) read that one rule.

**Who sets the days: a migration, and nobody else.** No door writes them and the application holds no grant on them. The eight sources standing when `V018` ran were admitted `1970-01-01`, before any month, because each served every month any deployment holds; none retired. **Admitting a source**: its migration inserts the row with the `admitted_on` it states, the first day it serves; there is no default. Then activate its rule set under four eyes (section 3); until then no month requires it. **Retiring a source**: a migration sets `status = 'RETIRED'` and `retired_on`, the last day it served. **The migration must state the day**: the trigger refuses a retirement left undated by any writer that holds `UPDATE` on `retired_on`, the migrator among them, because stamped with today a retirement on the 1st would require that month's run. Only the application role's status edge, which no door of the product uses, is stamped with the database's UTC day. **Retire it only once its last month's run counts**: settlement's door refuses a retired source's reports (`SOURCE_RETIRED`), unchanged, so a month it served and has not delivered would ask a run no door takes, and stand `MONTH_NOT_RUN`; with periods closing in order every later month would wait behind it, and the day can never move earlier nor the status return, so **there is no way on short of a person disabling an immutability trigger**: read `RECONCILIATION_STATUS` for that month first. **And the stated day decides**: a `retired_on` before a month, like an `admitted_on` after it, excuses that month even unclosed. That is why only a reviewed migration states the days.

**What you cannot do.** Move `admitted_on` later, or `retired_on` earlier, clear it, or set it ahead of today, once it stands: the trigger refuses every writer, the migrator included, because either would excuse a month the day already requires. It cannot judge a first retirement's day: that is the migration's review. Moving `admitted_on` earlier, or `retired_on` later, is allowed and asks more of the months it reaches; a closed month it reaches stays closed until a reopen, and `RECONCILIATION_STATUS` shows it. Do not delete a source: it is never deleted.

### 10.17 A drifting projection - `PROJECTION_VERIFIED` failed `PROJECTION_DRIFT`

**What it means.** The close's `PROJECTION_VERIFIED` check, and `finapp.ledger.projection.drift` beside it, found an account whose balance projection (`ledger.account_balance`) disagrees with what its journal and its `ACTIVE` holds derive. The journal is the authority; the row is a display copy. Something bypassed the posting path: a raw writer, a hand on the migrator's role, a defect. **Never correct it with an adjustment**: that posts real entries to make the books agree with a broken copy. The way on is a four-eyes rebuild of that one account (`P14-TSK-026`, ADR-0119), which moves the row to the journal and never touches a journal line.

**Who.** The accountant who sees the check fail names the account (the check's evidence lists up to twenty, `subjects`) and hands it to a ledger operator; an accountant cannot rebuild, a controller cannot rebuild, and a ledger operator closes no month. Both acts are `LEDGER_ADJUST`'s; the approval also takes `MULTI_FACTOR`.

**The procedure.**
1. Find why first: the row's last writer is in the audit trail and the database's logs. A rebuild repairs the copy; it does not explain the cause, and a cause still writing will drift the row again.
2. One ledger operator proposes: `POST /v1/ledger/projection-rebuilds` with `LEDGER_ADJUST`, keyed, body `{ledgerAccountId, reason}`. The answer records what was observed: the row's figures (`observedRow`, `null` when no row exists) and what the journal derives (`observedDerived`). `409 ledger.ProjectionNotDrifting` means the row agrees, or a posting landed mid-reading: read again. `409 ledger.ProjectionRebuildStanding`: one already awaits a decision. `409 ledger.ProjectionNotRebuildable`: the account's history cannot be derived at the row's scale, or the row stands for an account with no entries. That is the migrator's to correct, under change control, never this door's.
3. A second ledger operator reads it (`GET /v1/ledger/projection-rebuilds/{id}`) and approves on a `MULTI_FACTOR` session: `POST /v1/ledger/projection-rebuilds/{id}/approval`, keyed. The approval recomputes at its own moment under the posting's own locks and writes **what it computed**, never what the proposal saw: `APPLIED` (with `before` and `after`) or `UNCHANGED` when nothing was left to correct. `driftAsProposed` false means postings or another repair landed between the two acts; the approval's figures are still the journal's. `409 ledger.RebuildSelfApprovalRefused`: the proposer cannot approve. Anyone holding the door may decline it: `POST /v1/ledger/projection-rebuilds/{id}/rejection`, the proposer's withdrawal included.
4. The approval holds the account's projection row while it folds the account's whole history, so postings to that account wait for it. On a hot operational account, approve at a quiet moment.
5. Re-run the close's `PROJECTION_VERIFIED` check (section 10.9). Nothing here decides it: it judges the projection by its own means and should now pass. `finapp.ledger.projection.drift` falls at its next sweep, within half a minute.

**What is counted.** `finapp.ledger.projection.rebuild{outcome}`: `proposed`, `applied`, `unchanged`, `rejected`, `refused`. No alert reads it: the drift gauge is the alarm, and this meter is the record of the acts.

### 10.18 A reference gap beyond the staleness bound

**What it means.** A valuation rate set's capture was refused `422 accounting.ValuationRateStale`: a valued pair's last reference observation before the period's end is older than the staleness bound (96 hours by default). If the reference was only late, wait for it and capture again. If it was **silent**, no ordinary capture will ever succeed, and `VALUATION_SET_APPROVED` waits for good. The way on (`P14-TSK-026`, ADR-0119) admits the reference's **real** last observation of that pair before the end, by a named, reasoned, four-eyes exception, its age disclosed on every translated report. Nobody types a rate.

**The procedure.**
1. Read the refusal's detail: for each stale pair it names the last observation's instant and its snapshot id. A pair the reference **never** observed before the end has no real observation to admit and stays refused. That is an owner's question, not a door.
2. The accountant captures across the gap: `POST /v1/operator/accounting/valuation-rate-sets/reference-gap` with `ACCOUNTING_CLOSE_PROPOSE`, keyed, body `{periodId, reason, gaps: [{pair: "EUR/JPY", snapshotId}]}`. Every pair not named is still judged by the bound. `409 accounting.ReferenceGapRefused`: a named pair has an observation inside the bound (no gap), none before the end, or its last observation is not the snapshot named (a late observation arrived: read the refusal again). The receipt flags the pair `beyondBound` with its `ageHours`.
3. A controller, a different person, reviews it (`GET /v1/operator/accounting/valuation-rate-sets/{setId}` with `ACCOUNTING_CLOSE_APPROVE`) and approves on a `MULTI_FACTOR` session at `POST /v1/operator/accounting/valuation-rate-sets/{setId}/reference-gap-approval`, reasoned. The approval re-reads the reference: if a newer observation of the pair arrived since the capture, it is `409 accounting.ReferenceGapRefused`. Reject that set and capture again, ordinarily if the new observation is inside the bound. The ordinary approval refuses a set holding a gap, `409 accounting.ValuationDoorMismatch`, and the gap door refuses a set holding none.
4. An approved set stands for its period: there is no supersession. Approve a gap only when the age is one the month's statements can carry. Every translated trial balance, balance sheet and income statement pinning it states the pair, its snapshot, its observation, its age and the bound in `translation.referenceGaps`, and the rate's own entry says `beyondBound`. The close's approver is told too: `GET .../periods/{periodId}/close-proposal` serves `valuationRateSetReferenceGaps`, how many of the named set's rates a gap admitted (`P14-DOC-001`, `P14-TSK-026`'s owner item (c)); read the set itself before approving a close that names one above zero.

---

## 11. Platform operations (Phase 15) — planned

*(Planned by the Phase 14 → 15 transition, 2026-11-07 — `PHASE_15_PLAN.md` §11, §12, ADR-0120…0125, `Proposed`. Nothing
below is a procedure yet: each subsection is written by the task named, from the code and manifests it lands, linked from
its alerts, and **rehearsed on `kind` or compose with the result recorded** (`P15-TSK-032`); §1–§10 are each rehearsed by a counted sample of at least one procedure in the same task (`PHASE_GATES.md` §5 Phase 15, criterion 4). Until then no platform
procedure exists and no deployed environment exists to apply one to.)*

| Planned subsection | Written by | Rehearsed by |
|---|---|---|
| 11.1 Deploy: the bootstrap step when a role is added, the migration Job, then the rolling update; what a stopping pod does; **a release that adds a consumer handler to an existing group**: after the rollout, the audited replay of that event type over the rollout window (an N − 1 replica commits a record it has no handler for), or the new group with recorded starting offsets | `P15-TSK-004`, `-008`, `-012`, `-017`, `-027`, `-028` | `-012` |
| 11.2 Promote a release by digest; the production approval boundary | `-017` | `-017` |
| 11.3 Roll back by digest (a schema is never rolled back; never across a contract marker; an added handler's type replayed over the rollback window after the re-roll) | `-005`, `-012`, `-017`, `-027` | `-012` |
| 11.4 A failed migration (forward fix only) | `-004`, `-005` | `-032` |
| 11.5 Restore, then verify financially before any money moves *(written 2026-11-11 as §11.35, below)* | `-030` | `-030` |
| 11.6 Broker outage, consumer lag, a poison record, dead letters and replay | `-026`, `-028` | `-028`, `-032` |
| 11.7 Rotation and revocation per secret class; a key compromise *(the cloud half written 2026-11-09, below; the fleet's half is `SECRET_MANAGEMENT.md` §5.1 and §5.2)* | `-016`, `-018`…`-020` | `-020` |
| 11.8 Instance down, stale gauges, a burning error budget, an expiring certificate | `P15-TSK-024` (instance down, stale gauges: written below, 2026-11-08), `P15-TSK-025` (burn rate and certificates: written below, 2026-11-08) | `-032` |
| 11.9 A failing scan *(written 2026-11-08, below)*; a refused image *(§11.32 since 2026-11-11)* | `P15-TSK-006` (the failing scan), `P15-TSK-007` (the refused image, §11.32) | `P15-TSK-007` |
| 11.10 Edge and abuse *(the break-glass half is §11.25, since 2026-11-09; this row read "Edge, abuse and break-glass" until then)* | `-011`, `-022` | `-032` |
| 11.11 Parked value with no attributable owner: return-to-sender, a closed merchant's returned payout, a closed customer's corridor return (the value stays parked, aged and alerted) | `-032` | `-032` |
| 11.12 Incident response: severity, roles, communication, post-incident review | `-032` | `-032` |
| 11.13 Infrastructure code: running the Terraform checks, and changing a pin *(written 2026-11-08, below)* | `-013` | `-013` (the checks run on Docker, each plant refused) |
| 11.14 `deploy/`: the overlays, running the manifest check, and changing a number the budget reads *(written 2026-11-08, below)* | `-009` | `-009` (the check runs on Docker, each plant refused) |
| 11.15 Retention sweeps: what they delete, a failing sweep, pausing them, moving a bound *(written 2026-11-08, below)* | `-029` | `-032` |
| 11.25 Break-glass: opening it, what it can and cannot do, closing it, what bounds it in time *(written 2026-11-09, below)* | `-010` | `-032` |
| 11.26 The edge: two controllers, the generated route lists, a renewed certificate *(written 2026-11-09, below)* | `-011` | `-012`, `-020` |
| 11.27 A source-join break, the census behind or unreadable, and the fleet-blind page *(written 2026-11-09, below)* | `P15-TSK-035` | `-032` |
| 11.28 The `kind` rehearsal: running it, reading it, the last record *(written 2026-11-09, below)* | `P15-TSK-012` | `P15-TSK-012` |
| 11.30 Abuse limits: the doors' per-source limits, a door that refuses everyone, a store that cannot count, raising a limit, rotating the bucket key *(written 2026-11-09, below)* | `P15-TSK-022` | `-032` |
| 11.29 Key rotation and key compromise: the field-level key rings and the callback window *(written 2026-11-09, below)* | `P15-TSK-019` | `-020` |
| 11.31 Changing configuration: which class, which way, and `ConfigFingerprintDisagrees` *(written 2026-11-09, below)* | `P15-TSK-031` | `-032` |
| 11.32 A refused image: signing, admission, the controller down, the install order, where signatures live *(written 2026-11-11, below)* | `P15-TSK-007` | `P15-TSK-007` (on `kind`: every case, each beside its control) |
| 11.35 Restore, held until the platform's own checks pass on it; the restore rehearsal and its last record *(written 2026-11-11, below)* | `P15-TSK-030` | `P15-TSK-030` (the logical restore, on containers); the managed restore is `THREAT_MODEL.md` §6 item 38 |

*(Added 2026-11-08 by `P15-TSK-013`: §11.13 below is the first written subsection. It is a developer's and a reviewer's
procedure over the repository, not over a deployed environment, so the paragraph above still holds for every other
row.)*

*(Added 2026-11-08 by `P15-TSK-024`: §11.8 below is written too, its instance-down and stale-gauge parts; the paragraph above holds for the rest of that row and every other.)*

*(Added 2026-11-08 by `P15-TSK-009`: §11.14 below is written too, a developer's and a reviewer's procedure over the repository, like §11.13.)*

*(Added 2026-11-08 by `P15-TSK-004`: §11.1b, the bootstrap step and the migration Job, and §11.4 are written; `-012` and `-032` rehearse them.)*
*(Added 2026-11-08 by `P15-TSK-025`: §11.8's burn-rate and certificate parts, §11.6's consumer signals and §§11.20 to 11.24, a section for every alert of this phase and for the settlement, reconciliation and FX alerts that had none. Every alert is now routed by `infra/alertmanager/alertmanager.yml` to a placeholder receiver, and `AlertCatalogueTest` holds that each alert's section exists and names it. The paragraph above holds for every row still planned.)*

*(Added 2026-11-08 by `P15-TSK-027`: §11.1.1 below is written, the handler-set steps of rows 11.1 and 11.3; not yet rehearsed.)*

*(Added 2026-11-09 by `P15-TSK-011`: §11.26 below is written, from the manifests and `infra/scripts/edge-check.sh`; `-012` rehearses it on `kind`, `-020` the certificate.)*

*(Added 2026-11-09 by `P15-TSK-005`: §11.1c below is written, the schema-compatibility steps of rows 11.1 and 11.3, and §11.4's compatibility half; not yet rehearsed.)*
*(Added 2026-11-09 by `P15-TSK-035`: §11.27 below is written, from the census's code and its rules in `infra/prometheus/rules/correctness.yml`; not yet rehearsed.)*
*(Added 2026-11-09 by `P15-TSK-012`: §11.28 below records the `kind` rehearsal. §11.1, §11.1.1's handler-window case, §11.1b, §11.1c and §11.26's `kind` half have now run on one cluster on one machine; §11.28 says what that does and does not mean.)*
*(Added 2026-11-09 by `P15-TSK-019`: §11.29 below is written, from the ring's code and the two-step's checks; rotation has not run on a cluster or against a store, and `-020` rehearses it.)*

*(Added 2026-11-09 by `P15-TSK-031`: §11.31 below is written, from the register, the overlays and `infra/prometheus/rules/platform.yml`; not yet rehearsed.)*

*(Added 2026-11-09 by `P15-TSK-016`: §11.7 below is written, the cloud half; no account exists to run it.)*

*(Added 2026-11-11 by `P15-TSK-030`: §11.35 below is written, row 11.5's procedure, and records the restore rehearsal: a logical backup restored into containers on one machine. A managed restore has not run anywhere.)*

### 11.1 Deploy — what a stopping pod does (`P15-TSK-008`)

*(Written by `P15-TSK-008`, 2026-11-08, from the code and its tests; **rehearsed on `kind`** by `P15-TSK-012`, 2026-11-09, §11.28. The `preStop` pause and the grace period are `P15-TSK-009`'s manifest; the rest of §11.1 is the other tasks'
the table names.)*

A pod is stopped by every rollout step, scale-down, eviction and node drain. **Nothing an operator does is needed for
a stop to be correct**: a pod killed outright at any moment leaves its claims to lapse and another instance takes them
(`DISTRIBUTED_EXECUTION.md` §3, "Shutdown and drain"). What a graceful stop adds is fewer retries. On `SIGTERM`, after
the `preStop` pause, the application does this, in this order:

| Step | What happens | Bound | What the log says |
|---|---|---|---|
| 1 | Readiness turns `REFUSING_TRAFFIC`: `/actuator/health/readiness` on 8081 answers `503` `{"status":"OUT_OF_SERVICE"}`; liveness still `200` | — | — |
| 2 | The API port, 8080, stops accepting; requests it accepted finish | 30 s (`spring.lifecycle.timeout-per-shutdown-phase`); a request still running then is no longer waited for, and is cut off when the web server stops (step 5) | `Commencing graceful shutdown…`, then `Graceful shutdown complete` (or `…aborted with one or more requests still active`) |
| 3 | No new tick starts; the tick each schedule is running finishes, one that claims in a loop of its own ending before its next claim; all 35 in parallel (32 until `P15-TSK-029`) | 10 s, then the tick is interrupted and awaited 2 s more | `Schedule <name> drained` (DEBUG); `Schedule <name> did not drain within PT10S; its tick is interrupted` (WARN); `… abandoned to the process's exit` (WARN) |
| 4 | Every consumer loop takes no new batch, acknowledges the one it holds and leaves its group | 10 s, then interrupted, 2 s more | `Inbox consumers stopping: <n> loops`, `Inbox consumers stopped`; WARN past the bound |
| 5 | The management port, 8081, closes at once, a scrape in flight holding nothing; then the API's web server stops | For each, Tomcat's 2 s unload, and up to 5 s for a worker thread deaf to its interrupt | — |
| 6 | The gauge refresher (`gauge-refresher`, every gauge's one thread since `P15-TSK-024`; the accounting gauges' reader until then) drops its queued reads; the one in flight ends | 3 s | `The gauge refresher stopped` (DEBUG); WARN past the bound |
| 7 | The Kafka producer closes, then the connection pool; report generation's one-connection pool (`P15-TSK-021`) closes with it, at the context's close | the producer's `finapp.kafka.ack-timeout` (10 s), then HikariCP's own close (at most 10 s each, while something still holds a connection) | HikariCP's shutdown lines |

**Reading it.** A WARN in step 3 or 4 is not an incident: the interrupted work's claim lapses to another instance and
its records redeliver into the dedupe. It is a signal that a tick or a batch ran longer than 10 s, which the schedule's
own latency metrics explain. Steps 1-6 together take at most 71 s (read "57 s" until `P15-TSK-009` counted Tomcat's two
stops and their worker pools, and removed the 10 s a scrape in flight could hold step 5 for, the child context's own phase timeout). `terminationGracePeriodSeconds` is 115: the 10 s
`preStop` pause, those 71 s, step 7's 30 s and 4 s for the JVM's exit (§11.14). A pod still running when the grace ends
is killed - the same as any kill, and as correct.

### 11.1.1 A release that changes the handler set, or an event's contract (`P15-TSK-027`)

*(Written by `P15-TSK-027`, 2026-11-08, from the tests; **the handler-window case rehearsed on `kind`** by `P15-TSK-012`, 2026-11-09, §11.28. The audited replay it names is `P15-TSK-028`'s door.)*

The build refuses a release whose events a consumer of the last release cannot read, and a release that changes who
handles what with no recorded way through (`EVENT_ARCHITECTURE.md` §Schema evolution and §Adding a handler). What it
cannot do is run the step, so a release with an entry in `docs/events/release-record.yaml` has these to perform.

**A handler added to an existing consumer group** (the record's `replay`):

1. Note the instant the rolling update starts.
2. Let the rollout complete: every replica on N. While it runs, an N − 1 replica acknowledges records of the type unseen.
3. Wait until no pod is terminating, and note that instant. `kubectl rollout status` returns once the last old pod is marked for deletion, and that pod still runs its `preStop` and its drain with its consumers polling, so an instant taken then leaves a tail acknowledged unseen and never replayed. The instant of step 1 and this one are the rollout window.
4. Replay that one event type over that window through the audited replay. The inbox deduplicates what N already applied,
   so nothing is applied twice. The operator's reason names the release.
5. Check that the replay's audit record names the type and the window.

**A rollback** after step 2 has the same exposure from the other side: every record of the type the rollback window
carries is acknowledged unseen by N − 1. After the re-roll, once no pod is terminating, replay the type again over the rollback window, from the start of the rollback to that instant. A window older
than the inbox's dedupe window is refused by the replay itself.

**A handler removed** (the record's `replay` over `[rollback]`): nothing to do unless the release is rolled back. If it
is, after the rollback replay the type over the window the release ran, because N acknowledged those records unseen.

**A handler in a new group** (the record's `newGroup`): set the group's offsets before any replica of the release starts,
to the choice the record names (`latest`, or the instant). The consumers' `auto.offset.reset` is `earliest`, so a group
that starts with no committed offset reads the whole retention. Only a handler recorded `naturallyIdempotent: true` may
do that. Set them with the broker's consumer-group tooling, before the group has any member.

**A new `eventVersion` of a type a handler consumes** (the record's `versions`): the consumer must be running on every
replica, a release earlier, before the producer ships. Never roll the consumer back past that release while the new
version is being written.

**At every release, roll the catalogues forward.** After the release is cut:

1. Copy `app/build/event-catalogue/event-catalogue.yaml` and `handler-catalogue.yaml` over the files of the same name in
   `docs/events/`. `EventCatalogueTest` and `HandlerSetCatalogueTest` leave them there on every run. Never write them by
   hand: the build's comparison is only as good as this file.
2. Set each file's `release:` line to the release's identity.
3. Empty `handlers:`, `versions:` and `retired:` in `docs/events/release-record.yaml`.
4. Review the diff as a contract change, not as generated output. A version the code stopped writing stays in the
   catalogue on purpose.

### 11.1b Deploy — the bootstrap step and the migration Job (`P15-TSK-004`)

*(Written by `P15-TSK-004`, 2026-11-08, from the image and its acceptance run; **rehearsed on a cluster** by `P15-TSK-012`, 2026-11-09, §11.28,
with this task's Job contract in `DATA_MIGRATIONS.md` §2.1; `-017` orders it before the rollout.
The commands here are the image's own; the manifest is `P15-TSK-009`'s.)*

**Before the first release to a database, and again only when a release adds a role or changes a role's setting**, run the one-time bootstrap step
(`DATA_MIGRATIONS.md` §2.2): the migrator image with its `command` overridden to `RoleBootstrapMain`, handed the master
credential and the three roles' passwords from the secret store. It is the only thing that ever holds the master credential.
Read its output: one line per role saying `created` or `present, unprivileged, unchanged`, then one naming the setting it applied. Exit `12` naming a role means a role
already holds a right it must not: stop and find out how. Never give the migration Job the master credential to get past it.

**Then, for every release, the migration Job, and only then the rollout.** It runs the release's `finapp-migrator` by digest,
with no `command` of its own. One line per schema and a result line:

| The result line | What to do |
|---|---|
| `result=APPLIED schemas=21 applied=<n>` | The rollout may start. `applied=0` is normal: no schema changed, or another run got there first |
| `result=TRANSIENT` (exit `11`) | A table was locked past the bound, or the database was away. The Job retries it (`backoffLimit`). If it fails again, find the long transaction: `SELECT pid, state, now() - xact_start, query FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY xact_start` |
| `result=FAILED` (exit `10`) | Not retried. §11.4 |
| `result=REFUSED` (exit `12`) | The configuration or identity is wrong; nothing ran. The line says which. "The TLS connection could not be verified" is the CA bundle, its path or the host, never a lock |

**The rollout does not start until the Job has succeeded.** A Job that is still running is not a green light.

### 11.1c Deploy — schema compatibility across the rollout, and the way back (`P15-TSK-005`)

*(Written by `P15-TSK-005`, 2026-11-09, from the code and its tests; **rehearsed on `kind`** by `P15-TSK-012`, 2026-11-09, §11.28, release N − 1 run on
release N's schema. Promotion and its refusal are `P15-TSK-017`'s.)*

**A replica that will not go ready.** Readiness answers `503` with `{"status":"OUT_OF_SERVICE"}` while any schema is older
than the build expects, and until a read has first shown otherwise. So the rollout pauses at `maxUnavailable: 0`, and the old
replicas keep serving. Its schedules, relay and consumers do no work in that time either: readiness holds HTTP back, and the
same verdict holds those. That is the guard working. Read the replica's log for the one line it writes:

| The log says | What it is | What to do |
|---|---|---|
| `This instance is out of service until the migration has run: [ledger is at 33, expected 34]` | This replica's build is newer than the schema. The migration Job has not run, or failed | §11.1b, §11.4. Do not touch the replicas. Once the Job succeeds they go ready at the second probe after it, up to about 20 s: the first asks for the read and answers what it last knew |
| `The schema-version read failed: … (SQLState 42883)` on a replica that was never ready | `platform.applied_migration_versions()` does not exist: platform's migrations have not reached this database at all | As above |
| `The schema-version read was refused: … ` at ERROR, on a replica that was ready | The function has gone or the role has changed under a running fleet. The replica stands on its verdict and keeps serving, because only a read showing an older schema moves it: this same refusal meets every replica at once, and flipping would stop the whole fleet over a fault that is not the schema's. While it lasts, nothing can show the replica an older schema | Find out what changed the function or the grant. Restore it; do not restart the fleet for this |
| `The schema-version read failed: … (SQLState 42501)` | The connected role cannot execute it | The application is not connecting as `finapp_app`. Find out why; never grant it to something else to get past this |
| `The schema-version read failed: … (SQLState 08…)` or no state | The database did not answer. A verdict already reached stands: a schema is not older for being unreachable, and `db` reports the outage | Treat it as the database's |

**A replica that is on a newer schema stays ready.** Replicas of release N − 1 on release N's schema, in a rollout or after a
rollback, are not a fault, and nothing here reports them.

**Rolling back.** A schema is never rolled back; the image is. Before reverting the digest, read the migrations the release being
left added, in the files themselves, for a line starting `-- finapp-contract:` (`DATA_MIGRATIONS.md` §4.1):

- `first-conforming-release=ANY`, or no marker: the older release's code conforms. Roll back.
- A named release, and the target is that release or newer: roll back.
- A named release, and the target is older: **do not roll back.** That code does not fit this schema. The way back is a forward
  fix. `P15-TSK-017`'s promotion script will refuse this; until it exists, this reading is the check.

A rollback replica starts on a schema newer than the one it was built for, and goes ready: its guard looks only for older.

### 11.4 A failed migration (forward fix only) (`P15-TSK-004`; the compatibility half `P15-TSK-005`)

*(Written by `P15-TSK-004`, 2026-11-08; **not yet rehearsed** - `P15-TSK-032` rehearses it.)*

A failed migration stops the release before any replica changes. The old release keeps serving on the schema it had: the
rollout has not started, a release's migrations are expand-only or carry a contract marker (the build holds that, `DATA_MIGRATIONS.md` §4.1), and the failed
one has either not happened at all or, in the second case below, left the schema where a person must read it. **Never `clean`. Never edit an applied migration. Do not run
`flywayRepair` to make the symptom go away** (`DATA_MIGRATIONS.md` §3.1).

First read `<schema>.flyway_schema_history` for the failing schema, and the Job's log.

**No row for the migration (the usual case).** Every migration in this repository runs in a transaction, and on PostgreSQL a
failed one is rolled back and leaves no history row. Nothing happened. The next run tries it again. So the question is why it
failed:

- *The statement is wrong.* It was also wrong in the build and against an empty database, unless it depends on data. A migration
  that has never succeeded anywhere can be corrected and released again, because no database has applied it.
- *It depends on data this database has and no other did* (a new `CHECK`, a backfill). If it has already succeeded in another
  environment, it **must not be edited**: the other environment's checksum would no longer match. Add a new migration with a
  **lower version than the failed one and higher than the last applied**, which fixes the data, and release that. In the
  environments that already applied the failed one, `outOfOrder = false` would refuse it, so such a fix is only possible while
  every environment is behind the failed version; otherwise fix the data by an audited operational procedure and re-run the Job.

**A row with `success = false`.** Only a migration that cannot run in a transaction (`executeInTransaction=false`) writes one, and
none exists today. Its effect may be partial: for `CREATE INDEX CONCURRENTLY`, an `INVALID` index stays behind and must be dropped
first. Every later run refuses at validation until the row is dealt with, so this is the one case for `flywayRepair`, with the
files known good and the reason recorded (`DATA_MIGRATIONS.md` §3.1).

**The compatibility half: what the earlier migrations of the same release did.** Each migration commits on its own, so a Job that
fails at its fourth has applied its first three, and they stay. Whichever failed, the schema is now part way to the new release.
Replicas of the release in service are unaffected, because every one of those three is expand-only or carries a contract marker
that names a release already out. Replicas of the new release, if any has started, stay unready: the failed migration's schema is
older than their build expects. **There is nothing to roll back**: the old image is what is serving, so there is no digest to
revert, and the forward fix is the only way.

**Whichever it is**, the application release has not started. After a fix, run the Job again; it applies what is pending and nothing
else.

### 11.6 Broker outage, consumer lag, a poison record, dead letters and replay

*Written by `P15-TSK-026` and `P15-TSK-028` (2026-11-08) from the code they built; **not yet rehearsed** - no deployed broker
exists, and the rehearsal is `-032`'s. The consumption and freshness objectives are `docs/architecture/SLOS.md`'s, and their burn-rate alerts §11.8's; the signals
below exist today.*

**The consumer signals** (`P15-TSK-025`). Two series name what a poll cannot otherwise say. **`finapp.inbox.poll.failures{outcome}`** counts every thrown consumer poll: `InboxPollFailing` (page) reads `failed`, sustained for 15 minutes, and `InboxPollRefused` (page) reads `refused`, for 5. **`finapp.inbox.consumer.evictions`** counts a commit the group refused because it had already evicted the member: `InboxConsumerEvicted` (ticket). **`finapp.inbox.commits.deferred`** counts a commit a rebalance refused, sent after the next poll *(added 2026-11-09, `P15-TSK-012`; no alert reads it yet)*: one is nothing, and a sustained rate is a group that keeps rebalancing with its committed offsets standing still. `finapp.inbox.lag{module}` is what `InboxLagHigh` (ticket, over 1000 records in a group for 15 minutes) and `ConsumptionBurnFast` read; a group's lag is the sum of its members', and a consumer whose polls throw publishes NaN, never its last number.

**What is at stake.** Kafka carries hints, never truth (`INV-EVT-02`). While the broker is away nothing financial is lost
or wrong: every event waits in the outbox in PostgreSQL, in its aggregate's order, and every consumer's place is its
group's committed offset with the inbox behind it. Both procedures below are therefore about restoring flow, never about
repairing data, and neither ever edits a ledger table.

#### A broker outage

*Signals*: `finapp.outbox.pending` and `finapp.outbox.oldest` rising on every replica;
`finapp.outbox.publication{outcome="failed"}` counting, the relay's `last_error` on the waiting rows naming a timeout or
network failure class; `finapp.inbox.consumption{outcome="processed"}` flat. **Readiness stays UP, deliberately**: an
outage is not a refusal, and the replicas keep serving, because the outbox holds what the broker cannot take. Liveness
never reads the broker.

1. **Leave the application alone.** Do not restart or scale replicas: the relay already backs off per event and holds each
   aggregate's order, and a restart only adds a rebalance of every group to the broker's return.
2. **Diagnose the broker where it lives**, which on the `kind` rehearsal is the broker pod. On the managed service (`infra/terraform/modules/kafka`; unrehearsed, no cluster has existed):
   - **Is the cluster there?** `aws kafka describe-cluster-v2` for its state: `ACTIVE`, `MAINTENANCE` (a patch or a configuration change rolling brokers, one at a time, the writes continuing if two brokers stay in sync) or `HEALING`.
   - **Can a client get in?** A failing poll that is a refusal is the section below, not this one. A timeout that names the brokers' addresses is the network: the brokers' security group admits port 9096 from the cluster's nodes and nothing else.
   - **Is it a replication problem?** CloudWatch's `UnderReplicatedPartitions`, and `OfflinePartitionsCount` above zero. A topic holds three copies and demands two in sync, so one broker lost keeps every write; two lost refuses them (`NotEnoughReplicas`), and the relay retries with the outbox holding the events. **Never turn on `unclean.leader.election.enable` to get writes moving**: it silently discards acknowledged records (`infra/kafka/broker.properties`).
   - **The brokers' own logs** are the log group `/finapp/<environment>/msk-brokers`.
   - **A broker lost for good** is replaced by the service. Nothing here needs a person to re-create a topic: they are declared, and `prevent_destroy` stops a plan from removing one.
3. **When it returns**, the relay drains the backlog in per-aggregate order and `finapp.outbox.oldest` falls; the
   consumers resume from their committed offsets. A short rise of `finapp.inbox.consumption{outcome="duplicate"}` is the
   inbox absorbing redeliveries, and is expected.
4. **If the outage outlasted the relay's bounded attempts**, the rows past them are dead-lettered and block their
   aggregates: resolve them as `EVENT_ARCHITECTURE.md` §Handling an abandoned event says (retry once the broker is back;
   the cause was environmental), and never by deleting a row.

#### A refused credential or permission

*Signals*: `InboxPollRefused` (page) after 5 minutes; readiness `503` on the replicas whose consumers are refused (the contributor `kafkaConsumers`); a warning per
attempt, `Inbox consumer finapp.<module> refused by the broker:` followed by the refusing class
(`SaslAuthenticationException`, `TopicAuthorizationException`, `GroupAuthorizationException`); the relay's `last_error`
naming the same classes. A broker that refuses is reachable: this is configuration, not an outage.

1. **A rollout stalled on it** (the new replicas never became ready, the old ones still serve): the release carries a wrong
   credential or its principal lacks a permission. Roll back by digest (§11.3), then fix the release's secret or the grant.
2. **Every replica went unready at once**: the credential was revoked or rotated out of order. Restore the previous
   credential first, then roll. Rotation is add, roll, remove (ADR-0123 decision 5; §11.7), never remove first.
3. **A permission missing**: the grants are `infra/kafka/topics.yaml`'s principals, as `infra/terraform/modules/kafka` plans them; the
   application's principal needs read, write and describe on every catalogued topic and read on the groups
   `finapp.<module>`, and nothing more.

Never take a refused replica out of readiness's reach, or point it at the broker without `SASL_SSL`: off this machine the
application refuses to start on anything else (`KafkaTransportGuard`). A refused replica's readiness returns once the
broker is seen to admit it - a delivered record, or three clean polls in a row - so it does not flap up between the
client's reconnection attempts.

#### A failed TLS handshake

*Signals*: `InboxPollFailing` (page) after 15 minutes; `finapp.inbox.poll.failures{outcome="failed"}` counting, usually on every replica at once; a warning per
attempt, `Inbox poll failed on finapp.<module>: SslAuthenticationException`; `finapp.outbox.publication{outcome="failed"}`
counting with the relay's `last_error` naming the same class. **Readiness stays UP, deliberately** (`P15-TSK-026`'s review
fix round): the broker's side fails a handshake as surely as the client's, and a fault every replica meets at once is no
reason to take the fleet out of service.

1. **Every replica at once**: the broker's certificate expired or was rotated to an issuer the truststore
   (`finapp.kafka.ssl.truststore-location`, or the JVM's) does not hold, or a listener lost its certificate. Fix it on the
   broker side, or ship the new CA bundle; do not roll back the application.
2. **Only the new replicas**: the release carries a wrong truststore. Roll back by digest (§11.3).
3. **Never** turn hostname verification off or point the clients at a non-TLS listener to get past it: the guard refuses
   to start on anything but `SASL_SSL` off this machine, and `BrokerConnection` fixes verification on.

#### Consumer lag

*Signals*: `InboxLagHigh`, `ConsumptionBurnFast` and `finapp.inbox.lag{module}` (a group's lag in records, the sum over its members); `finapp.inbox.consumption{outcome="processed"}` below the rate of
`finapp.outbox.publication{outcome="published"}`; `InboxConsumptionFailing` (§9.12) for a stalled partition. The broker's consumer-group view of
`finapp.<module>`, read with the provider's tooling, is the authority on a group's lag; the gauge is each member's own view of it.

1. **A stalled partition** (a record that fails or is contended): §9.12. The record is retried, never skipped; if it is
   failing for a reason of its own it parks after five failures and two minutes, and the partition goes on (below). Fix the
   cause - deploy the build that reads the event's version, restore the upstream data - and the partition drains. Never
   move the group's offset past it by hand: the effect would never happen, and nothing would say so.
2. **Too slow, not stalled**: a group's parallelism is its partitions (12 per topic) and its members (one per replica).
   Adding replicas adds members, and the cooperative assignor pauses only the partitions that move. Repeated rebalances in
   the logs mean a member is being evicted for exceeding the poll budget (10 records in 120 seconds; a budget, not a
   bound - a handler's statements have no timeout, and the offset commit can wait 60 seconds): the commit that follows
   fails and counts in `finapp.inbox.consumer.evictions` and fires `InboxConsumerEvicted`, the inbox still prevents a second effect, but
   throughput collapses - find the slow statement in the handler.
3. **A group stopped for days**: a topic keeps a record for 7 days, so restore consumption before the oldest unconsumed
   record reaches that age. Past it, the deleted records are lost hints: the group resumes at the earliest one retained,
   and the gap is recovered from the outbox by an audited replay (below), bounded by the inbox's 14-day window. A
   group with no members loses its committed offsets after 7 days and restarts from the earliest retained record; every
   such record is inside the dedupe window, so the replay is absorbed.

Never reset a group's offsets to the latest record: it skips facts silently. Resetting to the earliest retained record is
safe, because the inbox deduplicates everything the topic still holds.

#### A poison record

*Signals*: `finapp.deadletter.parked{group}` above zero, alerted by `P15-TSK-025`'s rule; `finapp.inbox.consumption{outcome="parked"}`;
an `ERROR`, `Record of <type> parked for consumer ...`, naming the failure's class; `inbox.RecordParked` in the audit trail. A parked
record is set aside and its partition has already moved on, so this is not an outage and nothing is lost: the record is on
`platform.inbox_parked_record`, its event still in the outbox.

Only a failure of the record's own counts, against both budgets, on the database's clock: five failures and two minutes
(`finapp.inbox.park-after-attempts`, `finapp.inbox.park-after-failing-for`). A database that blinked, a pool that ran dry, a
contended claim, a refused broker credential or a build too old to read a version never counts: the partition stalls until the
cause is gone, as §9.12 says. **Many parks at once are a bad release, not bad records**: stop, read the failure class, fix forward.

1. **Read the queue**: `GET /v1/operator/events/dead-letters` (`EVENT_OPERATE`). Audited; identifiers and the failure's class,
   never a payload or the failure's message.
2. **Fix the cause first.** Releasing a record the handler still cannot take costs it its budget again and parks it again.
3. **Release it**: `POST /v1/operator/events/parked/{parkId}/release`, with a reason, on a `MULTI_FACTOR` session. It clears the
   count and republishes the event through the outbox; every other consumer's inbox refuses the duplicate.
   Refused `deadletter.ReplayOutsideDedupeWindow` past the 14 days, and `deadletter.NotReleasable` for a record that was
   never an event (a malformed one) or whose event has left the outbox: its only way on is a discard.
4. **Or discard it**, below. Never `DELETE` a row or move a group's offset to get past it.

#### Dead letters: an abandoned event

*Signals*: `outbox.EventAbandoned` in the audit trail; `finapp.outbox.publication{outcome="deadlettered"}`; an `ERROR`
naming the event, the aggregate and the attempts; a `WARN` each cycle that the aggregate is blocked behind it;
`finapp.outbox.oldest` climbing. The row blocks its aggregate, by design: later events of that aggregate wait.

1. **Read the queue**, as above. The failure's class says what to do: a broker or credential class is environmental; a
   class that speaks of the payload is the event's own.
2. **Environmental, and the cause is fixed: retry.** `POST /v1/operator/events/abandoned/{eventId}/retry`, with a reason. The
   relay takes it again in its aggregate's order. `deadletter.RetryOutsideDedupeWindow` means the event occurred 14 days ago or
   more: an earlier attempt may have reached the broker with its acknowledgement lost, so a consumer may have applied it and lost
   its record of doing so. Discard it instead, and recover the fact from the owning domain. Any replay waiting behind the event is
   judged by the same act; one past the window is withdrawn, and the audit record counts them (`replaysWithdrawn`).
3. **The event can never be published: discard, two people.** `POST /v1/operator/events/discards` (`subjectKind`
   `OUTBOX_EVENT` or `INBOX_RECORD`, `subjectId`, `reason`), then a **different** `EVENT_OPERATOR`
   `POST /v1/operator/events/discards/{discardId}/approval`. Consumers will never see that fact: record why, and expect to
   answer for it at reconciliation. The row stays as the evidence of the gap, and the aggregate goes on. A proposal that
   should not proceed is rejected, by anyone including its proposer, `.../rejection`; while one stands, a retry is refused.

There is no raw `UPDATE` in this procedure, and none is needed.

#### Replay

*For*: a group stopped past topic retention (above); a handler added to a release, over its rollout and any rollback windows
(§11.1, §11.3; `P15-TSK-027`'s catalogue says which). *Not for*: reprocessing a parked record, which is a release.

1. **Choose the type and the window** of first publication, `[from, to)`, as instants. One command, one type, at most 1000
   events, all or nothing. More than that: narrow the window and repeat. Replay at volume is Phase 16's.
2. **Order it** after the rollout completes and no pod is terminating (§11.1.1, step 3): `POST /v1/operator/events/replays` `{eventType, from, to, reason}`, on a
   `MULTI_FACTOR` session (`outbox.EventReplayOrdered`). The receipt says how many were requeued, not delivered.
3. **What to expect**: the events return to the outbox, so `finapp.outbox.pending` rises and falls, the age measured from the order;
   consumers that already applied them count `finapp.inbox.consumption{outcome="duplicate"}`, and only a consumer without a
   dedupe record runs its handler.
4. **`deadletter.ReplayOutsideDedupeWindow`**: an event in the window is 14 days old or more, so its dedupe rows may be gone and a
   replay could apply an effect twice. The whole command is refused. Never work around it: narrow the window to what is
   inside, and treat what is outside as a gap, recovered from the system of record by the owning domain. Only a type every
   consumer of which declares itself `naturallyIdempotent` on its handler may go past it, and none does today.

### 11.7 Rotation and revocation: the cloud half (`P15-TSK-016`)

*Written from `modules/security`, and never run: no account exists. `P15-TSK-020` rehearses the fleet's half on `kind`, and key rings are
`P15-TSK-019`'s.* Each class's method and the sync and the roll are `SECRET_MANAGEMENT.md` §5.1 and §5.2; this is what happens in the account around them.

**First fill: every entry needs a value before the first sync, once.** Terraform creates the entries empty and never holds a value. Under
`Retain` one missing or empty entry fails the whole sync, the keys of providers that do not exist yet included, and no Secret is created.

1. List the entries from the environment's root: `terraform output -json secret_entry_names`.
2. For each, skip it if it already has a current version: `aws secretsmanager describe-secret --secret-id "$name" --query VersionIdsToStages`.
   **A fill must not overwrite.** A field key's entry already in use is what the rows under it decrypt with. This check alone is check-then-write; step 4's
   fixed token is what closes the gap.
3. Generate by the rule `infra/scripts/kind-secrets.sh` states: a name ending `_KEY`, 32 random bytes as base64; a name ending `_PASSWORD`,
   64 random hex characters. Anything else is not a secret and is given by what knows it: the database URL from `database_endpoint`, the broker list
   `FINAPP_KAFKA_BOOTSTRAPSERVERS` from `kafka_bootstrap_brokers`, a user from the bootstrap step's conventions.
4. Write it from a pipe, so that it reaches no command line, file or terminal:
   `... | aws secretsmanager put-secret-value --secret-id "$name" --secret-string file:///dev/stdin --client-request-token 00000000-0000-4000-8000-000000000001 > /dev/null`.
   The token becomes this version's id, so a second run that generated a different value fails rather than replaces it.
5. The two SCRAM entries are `modules/kafka`'s, filled before stage `principals` (that module's README). The master credential is RDS's own.
6. **Verify:** every name reports an `AWSCURRENT` stage, and `kubectl get externalsecret -n finapp` shows `Ready=True` after the first sync.

Whoever runs this needs `secretsmanager:DescribeSecret` and `secretsmanager:PutSecretValue` on these entries and `kms:GenerateDataKey` on their keys (`secrets`,
`field_keys`), and never needs to read one. No role in this repository holds that right yet: it is the pipeline role of `THREAT_MODEL.md` §6 item 7, and §6 item 31(b)
records the gap. **Then run the bootstrap step** (§11.1b): it reads the master credential and the three role passwords through the bootstrap role, which exists only while
`bootstrap_role_enabled` is on. Turn it on by a reviewed change for the one step, and off after it. **Today nobody can run the step**: nothing reaches the database and no
person has a shell session (§6 item 31(e)). Nothing records that read until the account has a trail (item 31(c)).

**Rotate a value.** Write a new version with `put-secret-value`, as above but without the fixed token (the CLI makes one), then §5.2's sync and roll. The new value becomes `AWSCURRENT`, and
the previous one stays as `AWSPREVIOUS` until the next write. **To go back**, move the stage: `aws secretsmanager update-secret-version-stage
--secret-id "$name" --version-stage AWSCURRENT --move-to-version-id <previous> --remove-from-version-id <current>`, then sync and roll.

**Rotate a key.** Every KMS key rotates its material yearly, and old material stays to decrypt what it encrypted, so nothing is re-encrypted and
nothing rolls. To rotate one early: `aws kms rotate-key-on-demand --key-id <arn>`. A KMS rotation is not a rotation of the secret under it.

**Revoke a reader or a person.** A reader's token is the operator's: to cut one, remove the ServiceAccount's annotation and restart the operator, or
detach the role's policy. To end every session already issued for a role, attach an inline policy denying everything for sessions issued before now
(`aws:TokenIssueTime`), which is what the console's "Revoke active sessions" does. A person is removed from `human_users`, and the plan removes them
with their password, device and any access key, and from the group and the trust. Delete any access key by hand: the next plan fails until it is gone.

**A key suspected of compromise.** Rotating the key does not help: what was decrypted is out. Rotate every secret under that key (§5.1), and for
`field_keys` follow §5.1's key-ring row. Disable a key only knowing what stops: `etcd` stops the cluster reading its Secrets, `secrets` stops every sync,
`database` stops the database. **Deletion** is 30 days away by design, and `aws kms cancel-key-deletion` undoes it inside the window. A plan refuses it first.

**What this does not cover.** A rotation function, because nothing rotates on a schedule. A cloud API trail: the record of who read a secret or
used a key is the account's own, and no module creates one (`THREAT_MODEL.md` §6 item 31).

### 11.8 Instance down, stale gauges, a burning error budget, an expiring certificate - `InstanceDown`, `FinappTargetsAbsent`, `GaugesStale`, `FxGaugesUnreadable`, `ReconciliationGaugesUnreadable`, `AuthenticationAvailabilityBurnFast` and `AuthenticationAvailabilityBurnSlow`, `AuthenticationLatencyBurnFast` and `AuthenticationLatencyBurnSlow`, `TransferAvailabilityBurnFast` and `TransferAvailabilityBurnSlow`, `TransferLatencyBurnFast` and `TransferLatencyBurnSlow`, `PaymentCheckoutBurnFast` and `PaymentCheckoutBurnSlow`, `MerchantApiBurnFast` and `MerchantApiBurnSlow`, `ProviderCallbacksBurnFast` and `ProviderCallbacksBurnSlow`, `OperatorSurfaceBurnFast` and `OperatorSurfaceBurnSlow`, `EventFreshnessBurnFast` and `EventFreshnessBurnSlow`, `ConsumptionBurnFast` and `ConsumptionBurnSlow`, `CertificateExpiringSoon`, `CertificateExpiring`, `CertificateExpirySeriesAbsent`

*(Written by `P15-TSK-024`, 2026-11-08: the instance-down and stale-gauge parts, from `infra/prometheus/rules/platform.yml` and the code. **Not yet rehearsed**: `P15-TSK-032` rehearses this section on `kind` or compose and records the result. Every alert is routed by severity to a placeholder receiver (`infra/alertmanager/alertmanager.yml`); no on-call tool exists.)*

**How every gauge reads.** Every `finapp.` gauge of an instance is read on one thread, `gauge-refresher`, never on the scrape. A scrape answers each gauge's last read and asks the thread for another once that read is 5 s old; each gauge class keeps its own floor and bounds on top (accounting's: section 10.14). So a scrape does no database work on its own thread, whatever the database is doing, and every gauge of an instance together holds at most one connection of its pool. A gauge answers no value (NaN) before its first read, when its read failed, and once its ask has gone unanswered for `finapp.gauges.stale-after` (2 minutes): never a zero nobody read, and never its last number for good. The library series (`jvm_*`, `hikaricp_*`, `http_server_*`) read memory and stay on the scrape. Each instance reads the shared database for itself; a fleet-wide gauge aggregates with `max()`.

**`InstanceDown` (page).** `up` has been 0 for one instance for 2 minutes: Prometheus could not scrape it. A slow or absent database no longer costs a scrape, so read this as the instance itself: stopped, crashed, out of memory, unreachable on its port, or unable to answer at all. One alert per instance, so healthy neighbours never hide it. Look at the process or pod first (its state, its last log lines, its restart count). Every instance runs every schedule and consumer, so the others take its work; if every instance is down, nothing is taken. Restart or replace it: nothing is lost, since a gauge decides nothing and every claim it held has a lease that expires (`DISTRIBUTED_EXECUTION.md` section 3).

**`FinappTargetsAbsent` (page).** No target of the `finapp` job for 5 minutes: the fleet is gone from the discovery (scaled to zero, every pod deleted), or the scrape configuration lost the job. `up == 0` cannot see this, since a target that is gone leaves no `up` at all. Every rule on a `finapp_*` series is then green for the wrong reason, and the domain absent rules (`AccountingGaugesAbsent`) ticket beside it. Check the deployment and the scrape configuration first.

**`GaugesStale` (ticket).** The instance answers its scrapes, but its canary, `finapp.gauges.refreshed` (a constant 1 read through the same thread), was no value at more than 6 of the last 30 minutes' 120 evaluations, over 90 s: the gauge thread left asks unanswered past `finapp.gauges.stale-after`, and every `finapp` gauge of that instance was stale with it. **It counts; it does not wait for NaN to hold.** A thread behind but not stuck answers each ask about one pass of its queue (C) after it, so the canary, and every gauge whose read succeeds, is a number for about two minutes after each answer and NaN for the rest, about C - 120 s of every C. A rule that needs NaN to hold for its `for` fires only once C - 120 s outlasts that `for` (for 5 minutes, a pass over seven), and every threshold rule's `for` on that instance restarts at each NaN. So while this fires, **that instance's threshold rules cannot be trusted**: they are not merely late. A fleet-wide `max()` still reads the instances whose thread answers, so one instance behind blinds only its own per-instance rules (the `…SweeperOff` switches among them); every instance behind at once, a database in trouble, blinds the fleet's. The count fires five minutes after the seventh stale evaluation, about eight and a half minutes after a read hangs. A start whose first reads take a minute, or one short burst, stays under it. Two causes:

- **A read that never returns** holds the one thread. The data source sets no `socketTimeout`, so a socket the network black-holed never returns and no statement timeout reaches it. A thread dump shows `gauge-refresher` inside a read. **Restart that instance**: nothing else frees it.
- **The thread behind its asks.** Either the reads wait on a database in trouble (each up to the pool's 5 s, `spring.datasource.hikari.connection-timeout`, then fails; about sixty classes in turn make a pass near 300 s), or they are simply slow (the proofs at volume, `CURRENT_STATE.md`'s owner item (a) of `P15-TSK-024`). A read that fails is NaN at every scrape, so its module's unreadable rule holds its `for` and fires beside this. A read that succeeds slowly alternates as above, and only this alert sees it. The instance's requests fail the same way: read the database and `hikaricp_connections_pending` directly. When the reads are fast again, the thread catches up and the count leaves its 30-minute window with no restart.

One gauge NaN while the canary is 1 is not this alert: that gauge's read is failing (a statement past its bound, a grant gone), and its module's unreadable rule answers it. **Every gauge an alert reads has one**, and `PlatformAlertRulesTest#everyGaugeAnAlertReadsCanBeSeenUnreadable` holds that over every rule file: accounting's, BNPL's, credit's, lending's and risk's `…GaugesUnreadable` and their stall and attention rules, `MustBeZeroGaugeUnreadable`, `ReferenceRateNeverFetched`, `SettlementSourceNeverAccepted`, and, since `P15-TSK-024`'s fix round (2026-11-08), **`FxGaugesUnreadable`** (`infra/prometheus/rules/fx.yml`: the cover ages, the FX books proof and plan verdict, the outbound credits' ages, the in-transit age, the counterparty review age, the missing rule sets) and **`ReconciliationGaugesUnreadable`** (`settlement-reconciliation.yml`: the run, file, suspense and break ages and the overdue expectations), with `BnplGaugesUnreadable` now reading the negative merchant payables too. Each of those two names the series in its `gauge` label and the instance; for either, check that instance's database connectivity and its log, where the gauge's class names its failed read, and if `GaugesStale` fires on the same instance start there. Only the sweeps' switches (`…sweeper.enabled`) have no unreadable rule: they are configuration constants, read from no database, so their only NaN is the canary's, which this alert counts. *(Until the fix round this paragraph claimed the domain rules covered every gauge; fifteen gauges had none, six of them read by seven paging rules.)*

**What these cannot see.** `up` says a scrape completed, not that the instance serves its customers: readiness and the burn-rate alerts below answer that. A healthy canary says the thread answers, not that every read succeeded: that is each module's unreadable rule. The rules are unit-tested by `promtool test rules` over two instances, only the down, stuck or behind one firing (`infra/prometheus/tests/platform.test.yml`; the two unreadable rules above in `fx.test.yml` and `settlement-reconciliation.test.yml` beside it), run in CI's `alerts` job by `infra/scripts/alert-rules-check.sh` through the pinned Prometheus image.


*(Written by `P15-TSK-025`, 2026-11-08: the burn-rate and certificate parts. **Not yet rehearsed**: `P15-TSK-032` rehearses it.)*

#### A burning error budget

**Each objective's alerts, and what spends its budget:**

| Alerts | Objective | Owner | Target and budget |
|---|---|---|---|
| `AuthenticationAvailabilityBurnFast` (page), `AuthenticationAvailabilityBurnSlow` (ticket) | the authentication and session routes, 99.9% non-5xx | identity | `docs/architecture/SLOS.md` section 2 |
| `AuthenticationLatencyBurnFast` (page), `AuthenticationLatencyBurnSlow` (ticket) | the authentication and session routes, 95% within 1 s | identity | `docs/architecture/SLOS.md` section 2 |
| `TransferAvailabilityBurnFast` (page), `TransferAvailabilityBurnSlow` (ticket) | POST /v1/transfers, 99.9% non-5xx | transfers | `docs/architecture/SLOS.md` section 2 |
| `TransferLatencyBurnFast` (page), `TransferLatencyBurnSlow` (ticket) | POST /v1/transfers, 99% within 1 s | transfers | `docs/architecture/SLOS.md` section 2 |
| `PaymentCheckoutBurnFast` (page), `PaymentCheckoutBurnSlow` (ticket) | the payment and checkout commands, 99.9% non-5xx | payments | `docs/architecture/SLOS.md` section 2 |
| `MerchantApiBurnFast` (page), `MerchantApiBurnSlow` (ticket) | the merchant API, 99.9% non-5xx | merchant | `docs/architecture/SLOS.md` section 2 |
| `ProviderCallbacksBurnFast` (page), `ProviderCallbacksBurnSlow` (ticket) | the provider callbacks, 99.95% accepted or deduplicated | payments | `docs/architecture/SLOS.md` section 2 |
| `OperatorSurfaceBurnFast` (page), `OperatorSurfaceBurnSlow` (ticket) | the operator surface, 99.5% non-5xx | platform | `docs/architecture/SLOS.md` section 2 |
| `EventFreshnessBurnFast` (page), `EventFreshnessBurnSlow` (ticket) | event freshness, 99% of minutes under 60 s | platform | `docs/architecture/SLOS.md` section 2 |
| `ConsumptionBurnFast` (page), `ConsumptionBurnSlow` (ticket) | consumption, 99% of minutes under 1000 records | platform | `docs/architecture/SLOS.md` section 2 |

A **fast** alert pages: errors over 1 hour and over 5 minutes exceed 14.4 times the budget, two per cent of a 30-day budget gone in an hour. A **slow** alert tickets: errors over 6 hours and 30 minutes exceed 6 times the budget, five per cent gone in six hours. Each pair needs both windows, so a fast alert that has cleared means the burn stopped, even though its 1-hour window is still high. The ratios are the fleet's: a single instance burning alone does not page while the fleet stays under its threshold.

1. **Open the dashboard `finapp-slo`** and read the burn rate of each window for that objective.
2. **Find what is failing.** For a request objective, read `http_server_requests_seconds_count` by `uri` and `status` and the instance's log lines. For `EventFreshness…`, §11.21's outbox procedure. For `Consumption…`, §11.6's lag procedure; the alert names the group in `module`.
3. **Check a deploy first.** A burn that began with a rollout is rolled back by digest (§11.3). Do not wait for the budget.
4. **A provider cannot be asked** (a `503` from a payment command): it spends the budget and it is real. Read `finapp.payments.attempt` by outcome, and treat the provider as down: nothing is lost, since an unanswered attempt stays honestly `UNKNOWN` and its sweep resolves it.
5. **When it clears**, record the budget spent and, if it is spent, what the error-budget policy freezes (`docs/architecture/SLOS.md` section 4), with the date, in the incident record.

**Never** raise a threshold to silence a burn: the targets are reference values, changed by Phase 16's calibration or by a reviewed change to `slo.yml` and `SLOS.md` together.

#### An expiring certificate

`CertificateExpiringSoon` (ticket) fires when a certificate cert-manager holds has under 21 days left: it renews at two-thirds of a lifetime, so a 90-day certificate's renewal has been failing for over a week. `CertificateExpiring` (page) fires under 7 days. `CertificateExpirySeriesAbsent` (ticket) says cert-manager publishes no expiry series in a deployed environment, so an expiring certificate could not be seen; compose runs no cert-manager and is exempt through its `env="local"` label.

1. `kubectl describe certificate` for the one the alert names, and read the `CertificateRequest` and the issuer's status. A renewal failing is almost always the issuer, or its ACME challenge reaching the ingress.
2. Fix the issuer, then let cert-manager renew. Force a renewal only once the issuer is healthy.
3. **The CA bundles for PostgreSQL and Kafka are not in this series**: they are the provider's, and an expired or rotated broker certificate shows as `InboxPollFailing` (§11.6) with the warn line naming `SslAuthenticationException`. Rotation is §11.7.

### 11.20 Correctness pages - `LedgerTrialBalanceNonZero`, `LedgerProjectionDrift`, `GlNotEqualLedger`, `OutboxDeadLetter`, `InboxDeadLetterParked`, `PaymentWebhookUnmappable`, `CorrectnessGaugesUnreadable`, `CorrectnessUnverifiable`

*(Written by `P15-TSK-025`, 2026-11-08, from `infra/prometheus/rules/correctness.yml`. **Not yet rehearsed** (`P15-TSK-032`). The register of every correctness signal, the product proofs among them, is `docs/architecture/SLOS.md` section 3.)*

**These have no budget and no waiting.** Each pages on its first breaching sample. **Never correct a figure to clear one**: financial history is immutable, and a correction is a reversal or a compensating entry through the existing doors, after the cause is understood.

**`LedgerTrialBalanceNonZero` (`INV-ACC-01`).** Total debits differ from total credits in the currency the alert names. The gauge is a verdict and never an amount. This cannot be produced by the posting path, which refuses an unbalanced entry and the journal's own constraint with it, so suspect a raw write or a defect in that constraint. Stop deployments, and read the warn line, which names the currency. Then generate that period's `TRIAL_BALANCE` report (`POST /v1/operator/accounting/report-runs`) and read its lines (`GET /v1/operator/accounting/report-runs/{runId}/lines`); `GET /v1/operator/accounting/gl/balances` shows the same books where a mapping version is active. Escalate as a financial incident: no money should move until it is understood.

**`LedgerProjectionDrift` (`INV-BAL-02`).** A balance projection disagrees with its recomputation from postings. The journal is the truth and the projection is a cache of it. The warn line names the drifting accounts, by identifier and without an amount. Section 10.17 is the procedure: rebuild from the journal under the four-eyes door, and never edit the projection.

**`GlNotEqualLedger` (`INV-ACC-06`).** The close check `GL_EQUALS_LEDGER` failed. `AccountingCloseCheckFailed` tickets the same counter beside it. Read the run's findings (section 10.9): an unavailable proof, an unmapped line (section 10.12) or a real difference. Nothing closes the month until it passes; it is never disclosable.

**`OutboxDeadLetter`.** An event used up its publication attempts and stands abandoned, blocking its aggregate's later events (`EVENT_ARCHITECTURE.md` §Handling an abandoned event). Nothing financial is lost: the row is durable and the journal is the truth. Resolve it by retry once its cause is fixed, and never by deleting a row. The page lasts as long as the row does.

**`InboxDeadLetterParked`.** A poison inbox record was parked after its bounded retries; the group went on past it, and its effect has not happened (`finapp.deadletter.parked`, §11.6's dead-letter part).

**`PaymentWebhookUnmappable`.** `finapp.payments.webhook{outcome="unmappable"}` counted a signed callback that names no operation this platform minted, that the provider's own inquiry denied past its bound or contradicted, or whose inquiry answer could not be read. Both the door and the hint sweep count on that series. Follow the warn lines through the table in "Provider callbacks are hints" below: a denial reads as a possible **webhook key compromise** until shown otherwise, and "we cannot read" is a change of the provider's wire format. Nothing is adopted from such a callback.

**`CorrectnessGaugesUnreadable` (ticket).** A correctness gauge has been NaN on one instance for 15 minutes: its read is failing or its gauge refresher is behind (`GaugesStale`, §11.8). The other instances still answer, so a real breach still pages. **`CorrectnessUnverifiable` (ticket)** says no instance holds a number for one of them, so every rule on it is silent for the wrong reason; read the database first.

### 11.21 The platform under load - `OutboxPublicationStalled`, `HttpServerErrorsHigh`, `ConnectionPoolSaturated`, `ConnectionPoolStarved`, `InstanceClockOffset`, `PlatformGaugesUnreadable`, `PlatformSeriesAbsent`

*(Written by `P15-TSK-025`, 2026-11-08, from `infra/prometheus/rules/platform.yml`. **Not yet rehearsed** (`P15-TSK-032`).)*

**`OutboxPublicationStalled` (page).** The oldest row the relay may still publish is over 5 minutes old. Kafka is integration, not truth: every event waits durably in the database. Follow §11.6's broker-outage procedure. If the broker is healthy, the relay is the suspect: read the `outbox-relay` schedule's log lines and `finapp.outbox.publication` by outcome. A dead-lettered row does not fire this; its aggregate's later rows do.

**`HttpServerErrorsHigh` (page).** More than 5% of every request over 5 minutes answered `5xx`, across every route including the ones no objective names. It is the floor under the burn-rate alerts. Read `http_server_requests_seconds_count` by `uri` and `status` to find the route, then the instance's log by correlation id.

**`ConnectionPoolSaturated` (ticket), `ConnectionPoolStarved` (page).** Per instance, because a pool is an instance's: over 90% in use for 5 minutes, or threads waiting for 2 minutes. A pool never grows at runtime and is bounded by the connection budget (`DISTRIBUTED_EXECUTION.md` §4a), so raising it is a reviewed change to that budget, never an incident's first move. Look for a slow statement or a long-held transaction on that instance. Report generation's one-connection pool is excluded: it is full whenever a report runs.

**`InstanceClockOffset` (ticket).** An instance's clock differs from the database's by over a second for 5 minutes (`finapp.platform.clock.offset`, positive when the instance runs ahead). Leases and permits read only the database's clock (ADR-0014), so this costs correctness nothing. ADR-0063's clamp no longer surfaces the skew through a refused write, and this is how it is seen. Check the node's time synchronisation and replace the node if it will not sync.

**`PlatformGaugesUnreadable` (ticket), `PlatformSeriesAbsent` (ticket).** A platform gauge has been NaN on an instance for 15 minutes, or a series the SLO and platform rules read has gone from every instance, so those rules are blind. Read that instance's gauge refresher (`GaugesStale`, §11.8) and the scrape.

### 11.22 The critical flows - `AuthenticationRefusalsSpike`, `AccountLockoutSpike`, `LedgerPostingsRefused`, `CustomerWalletNegative`, `PaymentUnknownAged`, `WithdrawalUnknownAged`, `PaymentWebhookRefusalsSpike`, `TransferFailureSpike`, `TransferKeyConflict`, `CheckoutExpiryRising`, `MerchantPayoutUnknownAged`, `KycReviewQueueBacklog`, `ConsentRoutesFailing`, `FlowGaugesUnreadable`, `FlowSeriesAbsent`

*(Written by `P15-TSK-025`, 2026-11-08, from `infra/prometheus/rules/flows.yml`. **Not yet rehearsed** (`P15-TSK-032`). Thresholds are reference values.)*

| Alert | What it says | First steps | Owner |
|---|---|---|---|
| `AuthenticationRefusalsSpike` (ticket) | Over half of the last 15 minutes' authentications refused, at least 50 | Read `finapp.identity.authentication`. A bad release looks like it, and a credential-stuffing campaign does too: check `AccountLockoutSpike` beside it, and the audit trail for which accounts | identity |
| `AccountLockoutSpike` (page) | Over 20 accounts locked in 15 minutes | A credential-stuffing campaign until shown otherwise. Audit trail by the action, then rate-limit at the edge (§11.10) | identity |
| `LedgerPostingsRefused` (ticket) | Over 50 journal writes refused in 15 minutes | A refused posting wrote nothing and is not a loss. Read `finapp.accounting.cutoff.refused` first (a closed period, §10.8), then the callers' logs | ledger |
| `CustomerWalletNegative` (ticket) | A customer wallet below zero | Legal after a chargeback, and a receivable from the customer. Follow the collections process; never absorb it | ledger |
| `PaymentUnknownAged` (page) | An operation unknown for over 15 minutes | The provider has not answered. Its resolution sweep is the path; never decide by hand. If nothing is sweeping (`finapp.payments.sweeper.enabled` false on every instance), restore that first | payments |
| `WithdrawalUnknownAged` (page) | A withdrawal unanswered for over 15 minutes | As above, by the withdrawal sweep. A person's claw-back is a recorded door, never an edit | payments |
| `PaymentWebhookRefusalsSpike` (ticket) | Over 50 webhook deliveries refused in 15 minutes | Forged traffic, or a provider rotating its signing secret out of order. Spends no provider-callback budget (`docs/architecture/SLOS.md` section 2) | payments |
| `TransferFailureSpike` (ticket) | A fifth of judged transfers failed | Each `failed` is a committed refusal and moved nothing. Read the failure reasons | transfers |
| `TransferKeyConflict` (ticket) | A key reused with a different body (`INV-IDEM-03`) | A security signal: refused and recorded. Look for one client doing it repeatedly in the audit trail | transfers |
| `CheckoutExpiryRising` (ticket) | Over half of the last hour's sessions expired | A provider slow to capture, or an offer window too short for the traffic. `finapp.checkout.conversion.age` separates them | checkout |
| `MerchantPayoutUnknownAged` (page) | A payout unanswered for over 15 minutes | Its resolution sweep is the path; never pay out twice by hand | merchant |
| `KycReviewQueueBacklog` (ticket) | Over 100 reviews waiting for over an hour | Capacity: nobody is working the queue, or a provider is sending everything to review | kyc |
| `ConsentRoutesFailing` (page) | The consent routes have answered `5xx` for 15 minutes | A person may be unable to withdraw consent. Treat as an incident on the route, whatever the error budget says | consent |
| `FlowGaugesUnreadable` (ticket) | One of these gauges NaN on an instance for 15 minutes | While it is NaN that instance's reading says nothing. Read its gauge refresher (`GaugesStale`, §11.8) | platform |
| `FlowSeriesAbsent` (ticket) | One of these gauges is published by no instance | Its alert above is blind. Check the scrape and the release: a renamed meter is a defect | platform |

### 11.23 Settlement and reconciliation alerts

*(Written by `P15-TSK-025`, 2026-11-08: these alerts predate this section and had no runbook section. **Not yet rehearsed** (`P15-TSK-032`).)*

No amount appears in any of them: the audited operator reports answer the figures (`GET /v1/operator/reports/reconciliation/*`). A break is resolved through its own door and never by editing a line.

| Alert | What it says | First steps |
|---|---|---|
| `PositionProofFailing`, `CashProofFailing`, `UnattributedLinesOnReconciledPosition` (page) | The reconciled position or the cash proof fails, or a reconciled position has lines attributed to nobody (`INV-REC-06`) | Correctness: read the operator report for that purpose or currency. The first two page on their first sample (`docs/architecture/SLOS.md` section 3). Never adjust a line to clear it |
| `SuspenseItemUnowned` (page) | A suspense item has no owner | Assign it in the queue: an unowned item is one nobody will ever resolve |
| `ReconciliationRunBlocked` (page) | A source's run is blocked | Read that run's blocking reason; a blocked run also blocks what depends on it |
| `ReconciliationRunStalled` (ticket) | A run has aged past an hour | Read its schedule's log and claim; another instance takes a lapsed claim |
| `SettlementSourceSilent`, `SettlementSourceNeverAccepted` (ticket) | A source has delivered nothing for a day, or has never been accepted | Check the source: a delivery may be missing, or a configuration wrong |
| `SettlementFileStuck` (ticket) | A file has not concluded within its bound | Read its processing state; a retry is idempotent |
| `SettlementDeliveryRefused` (ticket) | A delivery was refused | Read the refusal's outcome: the file is retained as evidence and never changed |
| `SettlementExpectationOverdue` (ticket) | Expected money has not arrived | Follow the expectation in the operator report; it is a break if it stays |
| `SuspenseItemAged` (ticket) | A suspense item past 30 days | Work the queue; it stays aged and alerted until it is resolved |
| `BreakAgeCritical`, `BreakAgeHigh`, `BreakAgeMedium`, `BreakAgeLow` | A break past its severity's bound | Work the break through its door. A `critical` break pages at once and is a correctness problem until it is explained |
| `MustBeZeroGaugeUnreadable`, `ReconciliationGaugesUnreadable` (ticket) | A reconciliation gauge cannot be read | §11.8 |

### 11.24 FX and cross-border alerts

*(Written by `P15-TSK-025`, 2026-11-08: these alerts predate this section. **Not yet rehearsed** (`P15-TSK-032`). Sections 3, 4 and 5 are the procedures for a cover, a proof and a plan.)*

| Alert | What it says | First steps |
|---|---|---|
| `ReferenceRateStale`, `ReferenceRateNeverFetched` | The reference rate for a pair is stale, or was never fetched | Quotes in that pair fail closed: nothing mis-prices. Check the reference source and `finapp.fx.rate.fetch` by outcome |
| `FxQuoteImplausibleRefusals` | Quotes refused as implausible | A rate the policy will not trust: check the source before the policy |
| `FxCoverUnknownAged`, `FxCoverOpenAged` | A cover unknown or open for over 5 minutes | Section 4 |
| `FxBooksUnexplained`, `FxPlanDiverged` | The FX books are unexplained, or a plan does not replay | Section 5. Correctness: both page on their first sample (`docs/architecture/SLOS.md` section 3) |
| `OutboundCreditUnknownAged`, `OutboundCreditUndecided` | An outbound credit unanswered or undecided past its bound | Its resolution sweep is the path; never resend by hand |
| `CrossBorderPaymentUndelivered` | A cross-border payment in transit past a day | Read its status; the corridor return path is the correction (§11.11) |
| `CounterpartyReviewAged` | A counterparty review past a day | Work the queue |
| `CrossBorderCancellationSpike` | Cancellations rising | Read by corridor; a provider or a rate is the usual cause |
| `FxGaugesUnreadable` | An FX gauge cannot be read | §11.8 |

### 11.25 Break-glass (`P15-TSK-010`)

*Not rehearsed: `-032` does. No cluster, cloud account or person exists to hold this role; the procedure is written for the
environment that will.*

**What it is for.** An incident that cannot wait for a reviewed change, where the normal path, a pull request and the GitOps
sync, is too slow or is the thing that broke. Nothing else. A change you would like to make is a pull request.

**What bounds it in time, plainly.** Kubernetes RBAC has no expiring binding, so nothing in the cluster ends this. The binding
in `deploy/base/rbac-humans.yaml` names the group `finapp-break-glass`, which has no members. In a real environment a person
reaches it only through a cloud role that carries that group, and **that session's lifetime is the bound**: a role whose session lasts
an hour and whose assumption needs a factor at most an hour old (`modules/security`, asserted over a plan). How long a token already issued,
or a `watch` already open, outlives the session is unverified. **Today no person can use this role from a terminal**: a person has no access key,
so no shell session, the console has no `kubectl`, and nothing routes to the private endpoint (`THREAT_MODEL.md` §6 item 31(e)). No account exists, so
none of that has ever run; **until one does the bound is a procedure**: the steps below, kept by people, enforced by nothing.

**What it can do.** Everything `finapp-read-only` reads, except the RBAC, network and ExternalSecret objects, and exactly four writes: delete a
pod, evict a pod, read and change a Deployment's scale.

**What it cannot do, deliberately.** Read a Secret in any verb. Exec, attach, port-forward, add an ephemeral container or proxy into
a pod. Create a pod or change a template, because a template can mount every Secret in the namespace, and that would be reading
them. Change RBAC. If an incident needs one of those, it needs the reviewed path, or the cluster administrator's own access,
which is the identity layer's and leaves its own record.

**Open.**

1. Declare the incident and open its record: the reason, the person, the approver (a second person, never the holder), the
   time, and the **end time, at most one hour ahead**.
2. The approver grants it through the identity layer. Nobody edits the binding: it names only the group.
3. Note the session's start in the incident record.

**Use.** One pod at a time: each terminating pod keeps its whole connection pool open through its grace, and the budget allows one
beyond a rollout (`PHASE_15_PLAN.md` §6.1).

- Stop one pod, prefer an eviction, which respects the disruption budget. Delete only a pod eviction refuses and that is
  genuinely stuck.
- **Stop the fleet** (a defect moving money the wrong way, say): scale the Deployment to zero. The autoscaler treats zero as
  disabled and leaves it. It does not drain anything you did not ask for, and pending work stays in PostgreSQL. To restart, scale to the
  autoscaler's `minReplicas` (3) and it resumes.
- Don't scale above the autoscaler's maximum: the connection budget counts on it.

**Close.** Before the end time, however the incident stands:

1. End the session and confirm it is gone (a request with the old credential is refused).
2. Record the end time in the incident record, and every pod deleted and every scale change, read from the API audit log rather
   than from memory.
3. Restore what you changed: `kubectl get deployment finapp-app` shows the HPA's count, and the GitOps sync shows no drift.

**Afterwards.** The second person reviews the audit log against the incident record, within one working day. Anything in the log that
the record doesn't explain is a security event of its own.

**Verify it holds** (from a principal in the group):

```bash
kubectl auth can-i get secrets -n finapp                    # no
kubectl auth can-i list secrets -n finapp                   # no
kubectl auth can-i create pods/exec -n finapp               # no
kubectl auth can-i patch deployments -n finapp              # no
kubectl auth can-i delete pods -n finapp                    # yes
```

**The audit.** Every call is in the cluster's API audit log, which `modules/kubernetes` turns on (asserted over a plan, never applied).
That is the record of what was done; the incident record is why. Application logs are neither.

### 11.27 A source-join break, the census behind or unreadable, the fleet blind - `JournalEntryUnsourced`, `SourceCensusFindingsRecent`, `SourceCensusLagging`, `SourceCensusStalled`, `GaugesStaleAcrossTheFleet`

*(Written by `P15-TSK-035`, 2026-11-09, from the census (`accounting`'s `SourceCensus`, the per-module `*JournalSources` read ports) and `infra/prometheus/rules/correctness.yml`. **Not yet rehearsed** (`P15-TSK-032`).)*

**What it means.** The census reads every journal entry against the domain record that caused it, namespace by namespace (the join and what it compares are stated on each module's `*JournalSources`). `JournalEntryUnsourced` is its page, on the first non-zero sample of `finapp.accounting.source.census.breaks{kind}`: `UNSOURCED`, no record behind the entry (or a namespace no join answers for), or `MISMATCHED`, a record whose figures differ from the entry's lines. It is a detective control on the books no proof reads (`THREAT_MODEL.md` T15). It does not detect an entry whose domain record was written first (`PI-016`), nor a forged entry matching an open record's amounts and currency to another account, and it compares amounts and currency, not accounts.

**Reading the findings.** In a read-only session (`START TRANSACTION READ ONLY`); neither table ever holds an amount:

```sql
SELECT entry_id, kind, door, namespace, evidence, found_at FROM accounting.source_census_finding ORDER BY found_at DESC LIMIT 50;
SELECT position, entries_judged, last_success_at, last_failure_at, last_failure_class FROM accounting.source_census_head;
```

**Triage, in this order.** Treat `UNSOURCED` as a possible unauthorised posting until shown otherwise, and escalate as an incident (section 11.12). (1) Read the finding: its namespace and kind. (2) Read the entry in that same read-only session, from `ledger.journal_entry` and `ledger.journal_line` by its id: its door, its lines and who it attributes. (3) For `UNSOURCED`: is there really no record of that namespace's type with that id? For `MISMATCHED`: the evidence names the side, currency and purposes that disagree; read the record and the entry's lines side by side. (4) **Never correct a figure to clear the page.** Financial history is immutable; a correction is a reversal or a compensating entry through the existing doors (section 11.20), reviewed as an incident. **A finding stands in the gauge for 24 hours** (`finapp.accounting.source-census.standing`) and stays in `accounting.source_census_finding` for good: the page ends on its own, and no count of open findings exists. Nothing closes or dismisses a finding yet; see the owner's item on it (`CURRENT_STATE.md`).

**The first run on a journal that predates the census.** The head starts at the floor, so the first walk judges everything the journal already holds: anything unsourced in it is counted and pages for 24 hours from the moment it is found, however old it is. On a fresh journal this is nothing; on one with history, expect it and treat each finding the same way.

**A false page, if it ever comes.** A namespace was added and its join points at the wrong record, or a poster now writes its record after its entry in a later transaction. `SourceJoinCoverageTest` holds every registered namespace to a join; it cannot hold a join to its poster. Compare the poster with the join's stated comparison and fix the join, never the entry.

**`SourceCensusLagging` (ticket, 15 minutes) and `SourceCensusStalled` (page, one hour).** Something is waiting for the census and has been for that long, measured from when the head last moved. The walk reads only transactions that have finished, so a transaction holding an xid and staying open holds it back: look for one (`SELECT pid, state, xact_start, query_start FROM pg_stat_activity WHERE backend_xid IS NOT NULL ORDER BY xact_start LIMIT 10`, as a role that can see them) and end it the ordinary way. A session holding the head row itself stalls it too: it shows as a `FOR UPDATE` lock on `accounting.source_census_head`, and the census reports only that it is busy. Otherwise a batch keeps failing: `source_census_head.last_failure_class` names the class, and the instance's log (`The source-join census failed a batch: <class>`) the rest; the batch is retried each tick. A stall loses nothing: the head does not move and the entries wait. The hour is the bound past which a stopped census must not go unnoticed: it is longer than any healthy transaction or catch-up, and the page exists because this is exactly how T15's detective control stops with nothing in the journal to say so. Expected once: the first run on a journal that predates the census takes as long as walking it, at 20 batches of 200 per minute at the defaults; while the head moves, the time since it moved stays short.

**`SourceCensusFindingsRecent` (ticket, 30 days).** A finding stands in the page for 24 hours and in this ticket's count for 30 days (`finapp.accounting.source-census.recent`), so the page's end is not the end of anyone being told. Nothing closes a finding: the ticket ages out; `accounting.source_census_finding` keeps the record for good.

**`CorrectnessGaugesUnreadable`/`CorrectnessUnverifiable` (tickets) naming the census gauges.** A census that could not read a source, that has not run within 15 minutes, or whose last run failed publishes no value rather than a zero: its gauges are NaN, these tickets fire, and `JournalEntryUnsourced` cannot speak until they clear.

**Stopping it.** `finapp.accounting.source-census.enabled=false` stops the census on an instance; it moves no money and writes only its own records, so rolling a release back removes it cleanly. Stopped on every instance, its gauges read no value past `.stale-after` (the unreadable tickets, section 11.20), by design.

**A logical restore.** Transaction ids are not portable: a dump restored into a new cluster restarts the counter below the restored rows' `txid`. A census whose head is ahead of the database's counter refuses to walk (a stamped failure, the head unmoved) rather than skip silently. The restore procedure (§11.35) moves the restored cluster's counter past every restored `xid8` before anything runs, by its epoch on the stopped cluster, so the head stays where it was, nothing is walked twice and new entries land after it; the procedure's verification proves the census can walk the whole restored journal. A managed server gives no one its stopped data directory, which `pg_resetwal` needs, so a logical restore into one is refused by that verification (`THREAT_MODEL.md` §6 item 38): a migration resetting the head to its floor would not make the walk whole, because restored entries above the counter stay out of reach. A physical restore carries the counter with it and needs nothing. *(Read "and it needs a reviewed migration to reset the head at the floor: the head's own trigger refuses a move backward from any other path" until 2026-11-11.)*

**`GaugesStaleAcrossTheFleet` (page, 5 minutes).** Every instance that is up has a stale gauge canary (`finapp_gauges_refreshed` NaN): each instance's own `GaugesStale` is a ticket, but while the whole fleet's refreshers are stale, every fleet-wide `max()` rule on a gauge reads NaN and every correctness page on a gauge is blind. One stale instance never pages this way. Check the database first (the gauge reads block on the pool and database timeouts), then the refresher on one instance (section 11.8). Do not restart the fleet at once; the page is about reading, not about money.

### 11.29 Key rotation and key compromise: the field-level key rings and the callback window (`P15-TSK-019`, ADR-0123)

Eight field-encryption keys, each a **ring**: an active version that writes, retained versions that read only the rows stamped with them. Five callback doors, each with a current signing key and, during a rotation, a previous one. **Nothing is re-encrypted, ever**: a row keeps the version that wrote it for as long as it exists. Every step below is a change to an overlay or to a store entry; none has been rehearsed on a real cluster (that is `P15-TSK-020`'s).

**What a ring is configured from.** The non-secret half, in the overlay's `configMapGenerator` literals, per key: `FINAPP_<KEY>_RING_VERSIONS` (the versions a replica holds, default the base key's version alone), `FINAPP_<KEY>_RING_ACTIVEVERSION` (the pointer, default the base key's version), `FINAPP_<KEY>_RING_RETIREDVERSIONS` (default none). `<KEY>` is the variable's stem without `_KEY`: `FINAPP_DOC` for `FINAPP_DOC_KEY`, `FINAPP_PAYMENTS_DISPUTE_EVIDENCE` for that key. The secret half is the base key's entry (`FINAPP_DOC_KEY`, with the version `…_KEY_VERSION` names) and one entry per further version (`FINAPP_DOC_V2_KEY`, `FINAPP_DOC_V3_KEY`), each already an entry of the `ExternalSecret` and each already holding a generated value, because a sync fails on an entry with no value. **Never write a new value to a version's entry once that version has been listed**: a row written under it would be unreadable by any replica holding a different value, and nothing can tell the two apart except a row failing its authentication tag. New material always goes to a new version.

**Rotate a key (the two-step). Step 1, known.** Add to the overlay `FINAPP_<KEY>_RING_VERSIONS=1,2` (keeping every version already listed) and commit. The ConfigMap's hash changes, so the fleet rolls; a replica that cannot hold every listed version refuses to start, so the rollout stalls rather than half-completes. Wait for `kubectl rollout status deployment/finapp-app -n finapp` to finish: **that** is what makes every running replica a holder of version 2, and it is the one thing no automated check here can see. **Step 2, active.** In a later change, set `FINAPP_<KEY>_RING_ACTIVEVERSION=2` and commit; `key-ring-activation-check.sh` refuses, in CI, a change that activates a version its base commit did not list. The fleet rolls; while it does, some replicas write version 1 and some version 2, and every replica reads both.

**Roll a rotation back.** Move `FINAPP_<KEY>_RING_ACTIVEVERSION` back to the previous version and roll. Keep the version listed in `RING_VERSIONS`: every row written while it was active stays readable only while it is held. **Do not roll the image back behind the release that first ran rings once the active version differs from the base.** The older image holds one key and ignores the pointer, so it cannot read a single row written under any other version, and nothing makes those rows readable to it. Move the pointer back, keep the image and keep the version listed.

**A version's last rows are stranded by anything that drops it from the list.** A `git revert` of a rotation's commits, or a `kubectl rollout undo` to before the listing, returns a configuration without the newest version: every replica starts, and every row written while it was active is refused. The hole check cannot see this: a version missing from the top leaves no hole. `key-ring-activation-check.sh` refuses the revert in CI (every version its base lists must still be held or retired), but a `kubectl rollout undo` never passes through CI, so **do not use `rollout undo` for a ring change, and do not revert a listing: to go back, move the pointer and keep the version listed.**

**When a replica will not start.** `is not held by this instance`: the active version is not among those listed or has no key. `is still the published local default` naming a `FINAPP_…_V2_KEY`: a listed version's entry is missing or empty off loopback. `has a hole at key version` or `neither held nor retired`: a version was dropped from `RING_VERSIONS`; put it back, or retire it properly. `is named twice`: a base key's `key-version` was set to a number a further version also uses.

**Rotate a callback secret.** Order matters, because a replica started between the store write and the roll would otherwise read the new current key with no window. **First**, in the overlay, set `FINAPP_<DOOR>_PREVIOUS_VALIDUNTIL` to an instant at most seven days ahead of the replicas' starts (a replica started with it further ahead refuses to start), commit and roll: the window is open and the previous key is still the slot's generated value, which verifies nothing. **Then** the provider issues a second secret (a real provider's process; for a simulated provider, generate both values). Store the new secret as `FINAPP_<DOOR>_KEY`, the old value as `FINAPP_<DOOR>_PREVIOUS_KEY`, sync and roll (`SECRET_MANAGEMENT.md` §5.2). Only when the fleet has rolled does the provider start signing with the new secret; both verify meanwhile. **The window closes itself at that instant**, on every replica, with nothing restarted; the previous key is then simply any other key. Afterwards remove the `VALIDUNTIL` literal: housekeeping, not what closes it. Leave `…_PREVIOUS_KEY` in the store holding a generated value (a sync fails on an empty entry): a replica ignores it with no instant set, and only an instant with no key refuses. The window's variable has the name Spring derives from the property: `finapp.kyc.callback.previous.valid-until` reads `FINAPP_KYC_CALLBACK_PREVIOUS_VALIDUNTIL`, and likewise for the other four doors, `FINAPP_PAYMENTS_WEBHOOK_…`, `FINAPP_PAYMENTS_INSTANT_WEBHOOK_…`, `FINAPP_FX_WEBHOOK_…`, `FINAPP_CORRIDOR_WEBHOOK_…`.

**A key compromise: a field-level key.** Decide what the compromised key can read, from the rows carrying its version. **Whatever reached this key reached its neighbours**: `V2` and `V3` are pre-generated and live in the same store path, the same Secret and every pod's environment, so a compromise of any of those exposes them too, and activating an already-delivered slot would activate a key the same compromise holds. So: generate fresh material and write it to the next *unlisted* slot (allowed, because no row can stand under a version never listed; never a slot already listed), sync it (`SECRET_MANAGEMENT.md` §5.2 step 2), read `Ready=True`, and only then commit the listing of that slot, then the activation, as above. The two-step's first half can be shortened only as far as a rollout's own speed allows. The compromised version **stays retained**: it is the only key that can read the rows under it, and those rows cannot be re-encrypted. Then re-key what the domain allows (an MFA enrolment can be replaced by re-enrolment, for instance, so its rows stop mattering), and record what stays under the compromised version with the reason it stays, as an incident note (section 11.12). No re-encryption, no rows touched, and the retained version's key never leaves the ring while rows need it.

**A key compromise: a callback key.** If the *previous* key is the compromised one, close the window now: set the instant to the past and roll (a replica started after it refuses nothing and accepts only the current key). If the *current* key is the compromised one, a window does not help you: rotate it (a fresh current, the compromised one must not become previous) and let callbacks signed with the old one be refused; the provider re-signs.

**Retire a version.** Only a version with no row under it can be retired, and evidence never stops having rows, so in Phase 15 this is theoretical (C12 defers the purge that would make it real). Add the number to `FINAPP_<KEY>_RING_RETIREDVERSIONS`, remove it from `…_RING_VERSIONS`, destroy its store entry's value (leave a generated placeholder, since the entry must still sync) and roll. **`KeyRingRetirementGuard` reads the real tables on every replica's start and refuses if any row stands under a retired version**, and it refuses too if it cannot find out (a database unreachable, or a statement over its time limit, ten seconds per column, a full scan because no column is indexed, kept so that every column fits inside the 180-second startup probe; a table too large to answer inside that refuses the retirement). Credit's ring cannot be retired from this code at all: the application's role cannot read that table's version column. Crypto-shredding, destroying a version *with* rows under it on purpose, is not done (C12); it would have to change this rule deliberately.

**A base `key-version` other than 1.** Rows may stand under every lower number, so a replica configured with `FINAPP_<KEY>_KEYVERSION=4` refuses to start until versions 1 to 3 are listed or declared retired.

**Adding a third further version.** Past `V3` a version has no configured place, and listing `4` refuses to start. Adding one is a reviewed change: a constant and a slot in the key class, a row in `SECRET_MANAGEMENT.md` section 3.2 and an `ExternalSecret` entry, then the store's entry with a generated value (`SecretInventoryIsCompleteTest` holds the three together).

**What is not proved here.** That a rollout finished before the pointer moved is a person's reading of `rollout status`; the delivery order between the two commits is `P15-TSK-017`'s to enforce once a GitOps controller applies them. Rotation has not run on a cluster or against a store.

### 11.31 Changing configuration - `ConfigFingerprintDisagrees`, `ConfigFingerprintAbsent` (`P15-TSK-031`)

*(Written by `P15-TSK-031`, 2026-11-09, from [`CONFIGURATION_REGISTER.md`](CONFIGURATION_REGISTER.md), the overlays and `infra/prometheus/rules/platform.yml`. **Not yet rehearsed**, and no real environment exists to change.)*

**Find the property first.** The register names its class and where each environment gets it.

| Class | How it changes |
|---|---|
| `OPERATIONAL` in an overlay | A pull request against the ConfigMap literals of `deploy/overlays/<environment>/kustomization.yaml`. The ConfigMap's name carries a hash of its contents, so the change rolls the Deployment under the usual strategy (§11.14) |
| `OPERATIONAL` not set in an overlay | Add it to the overlay's literals, and set that environment's cell in the register from `default` to `overlay`: `ConfigurationRegisterIsCompleteTest` fails until both agree |
| `FINANCIAL`, not a URL | As `OPERATIONAL`, or by changing the code's default and the row's `Default` cell together, which `everyDefaultIsTheCodes` forces. **A second person on it is not built**: ADR-0122's decision, delivered by `P15-TSK-017`'s `CODEOWNERS` and the owner's branch rules. Read the row's "A mixed fleet" sentence before changing it: it says which direction is safe and which a reviewer must refuse without a measurement |
| `FINANCIAL`, a provider or report URL | Never an overlay literal: the manifest policy refuses a `_URL` key. A real provider's URL becomes an entry of `deploy/components/secret-delivery/externalsecret-app.yaml` and a row of `SECRET_MANAGEMENT.md` section 3.3, and the register's cell becomes `store`. After that the value changes in the store, outside any pull request |
| `SECRET` | Never here: `SECRET_MANAGEMENT.md` section 5 |

A property the code gains is one new row in the register, or the build fails. A value that decides money and turns out not to be safe while replicas hold two values goes into versioned database data instead (register section 4), and that is a change to its module.

**During a rollout, a disagreement is expected and is not an incident.** `finapp_config_fingerprint` carries the digest of each replica's effective `FINANCIAL` configuration in its `fingerprint` label, so the fleet's disagreement is visible at once and nothing pages for its length.

**`ConfigFingerprintDisagrees` (page): the fleet has disagreed for longer than any rollout takes.**

1. `count by (fingerprint) (finapp_config_fingerprint)` shows the groups, and `finapp_config_fingerprint` the replicas in each. The digest says only that they differ: compare the two replicas' ConfigMaps and the Deployment's revisions to see what.
2. A rollout that has stopped: `kubectl rollout status deployment/finapp-app` and `kubectl get replicasets`. A replica on the old revision with the new one failing readiness is the usual cause, and §11.1 is the way forward. Rolling back is §11.3.
3. A replica no revision explains: someone changed a pod or a ConfigMap by hand. Treat it as a security event, restore the desired state from the overlay and record who did it.
4. Do **not** restart replicas to make the alert stop: it clears the signal and not the cause, and a restart that picks up a mis-set ConfigMap makes the wrong value universal.

**What it cannot see.** A replica on the odd configuration that stays unscraped for more than five minutes at a time, a crash loop for instance, drops out of the count, so the wait restarts and this alert can stay pending for as long as that goes on. `InstanceDown` and the rollout's own status show that case.

Each `FINANCIAL` row is safe mixed for about a rollout's length. Past that, nothing promises that the fleet's two values were meant.

**`ConfigFingerprintAbsent` (ticket).** No replica publishes the digest, so the alert above is blind: usually a release without the meter or a scrape that lost the job. `FinappTargetsAbsent` pages if the whole fleet is gone.

**The role's idle-in-transaction timeout** (register section 5) is set by the bootstrap step (§11.1b), which has to run again when this value changes, and reaches each session at its next connection. If it fires, the application logs a broken connection and the caller's retry succeeds; a repeating one is a transaction held open by something it should not be, and the session's last statement in `pg_stat_activity` is where to start.

### 11.9 A failing scan

**When.** CI's `image`, `migrator-image` or `sast` job is red, or `./infra/scripts/image-scan.sh` or `./infra/scripts/sast.sh` exits
non-zero on a developer's machine. They are the same scripts. A red **scheduled** run with no new commit means a new
advisory against an unchanged image: the weekly run re-scans the last built image with a fresh database.

**Read the first line starting `!!` or `??`.**

`??` (exit 2): the scan **could not run**: a database could not be downloaded, Docker is not available, a file could
not be read. It is neither a finding nor a pass, and nothing deploys. Re-run; if a registry rate limit or a network
problem persists, fix that. Do not make the job pass by other means.

`!!` (exit 1), from `image-scan.sh`:

- **`<image> carries a finding at HIGH,CRITICAL`**: Trivy's table above it names the CVE, the package and the fixed
  version. In order of preference:
  1. A library with a fixed version: move it (`README.md` §7a), and let the library's tests run.
  2. An operating-system package in the base: `check-pinned-images.sh` reports when a newer base digest exists. Move
     the `FROM` pin (`README.md` §5f) and re-run.
  3. No fix exists, or the fix cannot land now: the finding is **carried**, and the owner decides that, not the
     engineer. Add the CVE to `infra/trivy/accepted-findings.yaml` with a `statement` (the reason) and an `expired_at`,
     list it with the same date in `THREAT_MODEL.md` section 6's owner item for the carried findings, and name it in the
     change's review. Past `expired_at` Trivy stops honouring the entry and the job fails again.

  **Never lower the bar**: `SEVERITY`, `--ignore-unfixed`, `--skip-dirs`, `--skip-files` and a removed scanner are all
  refused by `SecurityScansAreWiredTest`, and a run with a lowered bar goes red anyway at the `high-only` plant.
- **`the SBOM of <image> ...`**: the scanner did not read what the image holds (a path filter, an analyzer failure), or
  a test-scope library reached the image. Fix what changed: an SBOM that omits what ships is a report of nothing.
- **`plant <name> was NOT refused`**, or **refused, but not for its reason**: the check has stopped working. Do not merge
  and do not delete the plant: find what changed the scanner's behaviour (usually a Trivy pin bump renaming a check).

From `sast.sh`:

- **`!! <rule> <path>:<line>`**: a finding with no accepted row. Fix the code. The SQL rules' messages say how: bind
  the value with a `?` parameter, or choose a column or a lock clause from constants. If the finding is genuinely safe
  and cannot be expressed as constant, add the row the report prints to `infra/semgrep/baseline.tsv` with a reason that
  says why this statement is safe and an `until`; the owner item for the carried findings in `THREAT_MODEL.md` section 6
  records how many rows there are.
- **`baseline: line N matches no finding`**: the statement changed, or the code was fixed. Remove the row. A stale row
  is refused, not kept.
- **`baseline: line N expired`**: re-judge the finding; extend the date only with a reason that still holds.
- **`the rules' own tests` FAILED**: a rule change broke its annotated test beside it in `infra/semgrep/rules/`.
- **`plant <name> was NOT refused`**: a rule has stopped firing. Do not merge.

**Run them locally** (Docker required, no credential):

```bash
./infra/scripts/image-build.sh && ./infra/scripts/image-scan.sh
./infra/scripts/migrator-image-build.sh && ./infra/scripts/image-scan.sh --only finapp-migrator
./infra/scripts/sast.sh
```

On a machine whose TLS is intercepted (a corporate proxy, or an antivirus's HTTPS scanning), the Trivy container cannot
download its databases. Build a local image `FROM` the pinned digest with that machine's root certificate added and
set `TRIVY_IMAGE` to it for the run. The scanner binary stays the pinned one; CI never sets the variable. `sast.sh` needs
no network at all and has no such step.

**Change a scanner pin.** Bump `VERSION` in `infra/scanner-pins.sh` and re-resolve `DIGEST` with `docker buildx imagetools
inspect <repository>:<version>`. A Trivy bump also moves the checks `trivy config` runs over the Terraform, so re-run
`./infra/scripts/terraform-check.sh` with it. Run both scan scripts: a plant that stopped being refused by name is
what a new scanner version breaking a check looks like.

**What this does not cover.** Signing and admission (`-007`, the refused image, above). A secret inside a jar: Trivy reads the image's loose files, which include the boot jar's exploded classes and `application.yaml`, and does not unpack the libraries' jars. A vulnerability nobody has
published yet. A statement built from a constant that another file declares and only its name vouches for
(`THREAT_MODEL.md` T45's residual).

### 11.13 Infrastructure code: running the Terraform checks, and changing a pin

**When.** Before pushing any change under `infra/terraform/`, and when Dependabot proposes a provider update or
`check-pinned-images.sh` reports a newer Terraform or `tflint`. **Why.** The code is never applied in Phase 15
(ADR-0121 decision 6), so these checks are the only thing that holds it. CI's `iac` job runs the same script.

**Run the checks** (Docker required, no account and no credential):

```bash
./infra/scripts/terraform-check.sh
```

It fetches the providers once into a mirror, verified against the lock file, so a run makes one network fetch and not
one per directory. It then runs `terraform fmt -check`, `tflint`, `trivy config` (HIGH and CRITICAL fail), and, in every directory that
holds a `.tf` file, `terraform init -backend=false -lockfile=readonly`, `terraform validate` and `terraform test`.
Each test only plans; the roots' tests and the concern modules' tests plan against mock providers (AWS, and Kafka for the
brokers' topics and ACLs), and the conventions module uses no provider. It then plants forty-three defects in copies of the
tree (six over the checks themselves, seven over the network and cluster modules' named properties, fourteen over the
data tier's, sixteen over the security modules') and requires each check to refuse its plant by name. **Verify**: the last line reads `ok   every check green, every plant refused for its
reason`, and the exit status is 0. A line starting `!!` names what failed:

- **a check failed** on the tree: fix the code. For a formatting failure, `terraform fmt -recursive infra/terraform`
  run with the pinned version rewrites the files;
- **a plant was NOT refused**, or was **refused, but not for its reason**: the check has stopped working. Do not
  merge: a check that cannot fail is the defect this script exists to find;
- **a plant was not planted**: a change moved the line its mutation edits. Update the mutation in the script so that
  it still plants the same defect.

The script never writes the tree: it mounts `infra/`, `deploy/` and `docs/` read-only (the roots read each overlay's
`FINAPP_DB_SERVER_MAX_CONNECTIONS` from `deploy/`, and the secret inventory from `docs/`) and works in a Docker volume it removes at exit.
On a machine whose TLS is intercepted (a corporate proxy, or an antivirus's HTTPS scanning), `terraform init` in the
container cannot reach the provider registry. In that case, build a local image `FROM` the pinned Terraform digest with
that machine's root certificate added, and set `TERRAFORM_IMAGE` to it for the run. The Terraform binary stays the
pinned one, and the providers are still verified against the committed lock file. CI never sets the variable.

**The conventions** (each enforced where it says, so a violation fails the build or the job):

| Convention | Enforced by |
|---|---|
| Terraform and every provider pinned exactly; `required_version` equals the pinned image's version; the two roots on the same versions; the lock file holding exactly those; a concern module requiring a provider commits a lock file at the same version the roots pin | `TerraformIsPinnedAndCheckedTest`; `tflint` (`terraform_required_version`, `terraform_required_providers`); `init -lockfile=readonly` |
| One `s3` backend per root, its own key, `encrypt = true`, `use_lockfile = true`; no credential argument in a backend or provider block | `TerraformIsPinnedAndCheckedTest` |
| snake_case identifiers; `main.tf`, `variables.tf`, `outputs.tf` per directory; every variable and output typed and described | `tflint` |
| Names start `finapp-<environment>` and every resource carries `Project`, `Environment` and `ManagedBy`, from `modules/conventions` through the AWS provider's `default_tags` | `terraform test` (the conventions module and each root); `TerraformIsPinnedAndCheckedTest` (each root's provider takes the tags, each root calls the module as itself). Each concern module's tests assert the prefix on the resources they name; a new resource's use of it is a review point |
| `prevent_destroy = true` on the database, its backups and every KMS key | `TerraformIsPinnedAndCheckedTest` |
| No resource or data source that writes a secret value into state, and no database given its password (use `manage_master_user_password`) | `TerraformIsPinnedAndCheckedTest` |
| The secret entries are read from the inventory and the `ExternalSecret`s, never listed; no `.tf` file names an inventoried variable; no policy holds a wildcard action on a secret or a key; no access policy and no scheduled secret rotation; a person, a group and an access entry only in `modules/security` | `TerraformIsPinnedAndCheckedTest`; each root's `terraform test` |
| The topic catalogue and the brokers' posture are read from `infra/kafka/`, never copied under `infra/terraform/`; a variable holding an open decision has no default | `TerraformIsPinnedAndCheckedTest`; each root's `terraform test` |
| Every test file mocks every provider its directory requires, and configures no real one | `TerraformIsPinnedAndCheckedTest` |

**Change a provider pin.** Use the same version in every root's `versions.tf`. Then regenerate each root's lock file
with the pinned Terraform, for the platforms the checks run on:
`terraform providers lock -platform=linux_amd64 -platform=linux_arm64`, run in each root after an
`init -backend=false`. A root that calls concern modules gets a combined `constraints` line (`">= 6.72.0, 6.72.0"`); set it
back to the root's own exact pin, which is what `TerraformIsPinnedAndCheckedTest` reads (Terraform itself ignores that line
when it verifies a lock). Then copy a root's lock file over each concern module's `.terraform.lock.hcl`, setting its
`constraints` line to the module's own constraint (`versions.tf`). Review the lock files' diff and run the checks. A
Dependabot pull request that moves one root and not the other, or the roots and not the modules, fails
`TerraformIsPinnedAndCheckedTest` until they agree.

**The modules.** What each concern module holds, what asserts it and how to operate it is in its own README:
[`network`](../../infra/terraform/modules/network/README.md),
[`kubernetes`](../../infra/terraform/modules/kubernetes/README.md),
[`postgres`](../../infra/terraform/modules/postgres/README.md),
[`kafka`](../../infra/terraform/modules/kafka/README.md),
[`object-storage`](../../infra/terraform/modules/object-storage/README.md),
[`security`](../../infra/terraform/modules/security/README.md),
[`registry`](../../infra/terraform/modules/registry/README.md) and
[`observability`](../../infra/terraform/modules/observability/README.md).

**Change the Terraform or `tflint` version.** Bump `VERSION` in `infra/scanner-pins.sh` and re-resolve `DIGEST` with
`docker buildx imagetools inspect <repository>:<version>`, as for the scanners. For Terraform, set every root's
`required_version` to the same version. The build refuses a mismatch.

**What this does not cover.** An apply, a plan against an account, a drift check, and a managed restore (the `postgres` module's README says what one must re-attach; the local rehearsal is §11.35's, row 11.5's procedure). Each needs a real account, which
Phase 15 does not have (`THREAT_MODEL.md` §6 items 7 and 23). A first apply is not one step: the state bucket comes before its own
backend (the `object-storage` README) and the brokers come up in three stages (the `kafka` README).

### 11.14 `deploy/`: the overlays, running the manifest check, and changing a number the budget reads

**When.** Before pushing any change under `deploy/` or `infra/scripts/manifest-policy/`, and when
`check-pinned-images.sh` reports a newer Kustomize, kubeconform or conftest. **Why.** No cluster runs these files yet
(`P15-TSK-012` is the first), so this check is the only thing that holds them. CI's `manifests` job runs the same script.

**The layout.** `deploy/base` is the application's desired state and is never rendered alone: only an overlay names an
image digest. One Deployment, one Service (8080 only), a hash-named ConfigMap, two ServiceAccounts with no token, a
PodDisruptionBudget, a HorizontalPodAutoscaler that alone owns the replica count (the Deployment states none), the
migrator Job and, since 2026-11-09, the default-deny, the application's and migrator's NetworkPolicies, the two human Roles,
the quota and the `LimitRange`.

| Overlay | For | `maxReplicas` | `FINAPP_DB_INSTANCES` | Difference from the base |
|---|---|---|---|---|
| `kind` | The rehearsal, test-only | 3 | 6, the formula exactly | Single-node PostgreSQL and Kafka, ephemeral, labelled `finapp/test-only`; 1 GiB; the zone spread relaxed, since `kind`'s nodes carry no zone label; NetworkPolicies selecting those copies; a quota of 9 pods |
| `staging` | Reference values | 4 | 7 | The maximum, its budget and a quota of 8 pods; placeholder data-tier and provider addresses |
| `production` | Reference values | 7 | 10 | Its own statement of the same numbers; the base's quota of 11 pods; placeholder data-tier and provider addresses |

**Nothing real is committed.** No secret, no hostname, no endpoint. An image is `finapp-app@sha256:` and sixty-four
zeros until a promotion replaces it, which no registry serves, so an unpromoted overlay cannot start a pod, and with
`maxUnavailable: 0` it replaces nothing. Endpoints and credentials arrive through the `finapp-app-secrets` and
`finapp-migrator-secrets` Secrets: absent, the pod does not start.

**Run the check** (Docker required, no cluster and no credential):

```bash
./infra/scripts/manifest-check.sh
```

It renders every overlay, validates each with `kubeconform -strict` against the schemas in `infra/scanner-pins.sh`, runs
every policy of `infra/scripts/manifest-policy/finapp.rego` over each, and then copies `deploy/`, plants one defect per
copy and requires the same check to refuse each by name. **Verify**: the last line reads `ok   every check green, every
plant refused for its reason`, and the exit status is 0. A line starting `!!` reads as in §11.13. On a machine whose TLS
is intercepted, kubeconform cannot read the schemas: build a local image `FROM` the pinned digest with that machine's root
certificate added, and set `KUBECONFORM_IMAGE` to it for the run. CI never sets it.

**Change a number the budget reads.** The policy refuses each of these if it is done alone.

- **More replicas**: `FINAPP_DB_INSTANCES` must reach `maxReplicas + 2 × maxSurge + 1`, and
  `FINAPP_DB_INSTANCES × (7 + 1)` must fit `FINAPP_DB_SERVER_MAX_CONNECTIONS` less `FINAPP_DB_RESERVED_CONNECTIONS`. The
  pool sizes are read from `application.yaml`; an overlay does not set them.
- **A stop bound in the code**: `KubernetesManifestsTest` goes red until `manifest-policy/shutdown-budget.yaml` agrees. Then
  raise `terminationGracePeriodSeconds`, `minReadySeconds` with it, and the autoscaler's scale-down `periodSeconds` if the
  grace passes it.
- **The pool's `connection-timeout`**: the readiness probe's `timeoutSeconds` must outlast it, or a lost database reads as a
  probe timeout rather than a `503`.
- **`FINAPP_DB_SERVER_MAX_CONNECTIONS`**: on a managed database the same line of the same overlay sets the server, so it is
  one change and two deliveries, in an order: `max_connections` is static on the engine, and a plan shows nothing while it
  waits for a reboot. To raise it, apply and reboot first, then roll the application; to lower it, the application first. Never
  the other way round: the guard reads the declaration and cannot see the server. The `postgres` module's README has the steps.

**What a rollout costs.** `minReadySeconds` is the grace, 115 s, so each step takes a new pod's startup plus 115 s: about
eight minutes for three replicas, longer at the maximum. That is the price of never stacking terminating pods.

**Kubernetes 1.30 or later.** The `preStop` is a `sleep` action, because the image has no shell. The schemas checked are
`KUBERNETES_VERSION` in `infra/scanner-pins.sh`, whose minor `KubernetesManifestsTest` holds equal to each environment's
`kubernetes_version` in Terraform: raise the two together.

**Change the fleet and the quota follows.** Each overlay's `ResourceQuota` must equal `maxReplicas + 2 × maxSurge + 1`
application pods, the migrator and anything test-only, in pods, CPU and memory: a pod inside its grace is still charged. The
policy reads each number from the rendered stream and refuses a quota above or below it (`deploy/base/resourcequota.yaml` shows
the sum), and the `LimitRange`'s `max` must equal the largest pod rendered. A controller that needs RBAC goes on
`controller_accounts` in `finapp.rego`, one line: a binding to any other ServiceAccount is refused. A new egress or a new client of the application is refused until a flow is added to `finapp.rego` beside its
NetworkPolicy: the allowed flows are the decision, written once there and once in the policy.

**`deploy/namespaces` is rendered separately.** An overlay's `namespace: finapp` renames every Namespace it renders, so the three
namespaces are a root of their own, appended to each overlay's stream by this check and applied before an overlay.

**What this does not cover.** A cluster's own refusal (no admission controller or network plugin runs here: `-012`), a real
rollout and the measured connection peak (`-012`), and delivery (`-017`). *(Read "(Pod Security Admission, NetworkPolicy, quotas: `-010`)" until 2026-11-09.)*

### 11.15 Retention sweeps - `finapp.retention.swept`, `finapp.retention.sweep.failed`

*(Written by `P15-TSK-029`, 2026-11-08, from the code it landed. Not rehearsed: `-032` rehearses it. The policy is
[`DATA_RETENTION.md`](DATA_RETENTION.md).)*

**What runs.** Two schedules on every instance, `outbox-retention-sweep` and `inbox-retention-sweep`, each every
`finapp.retention.sweep.interval` (default five minutes) and each deleting up to 20 batches of
`finapp.retention.sweep.batch-size` (default 500, at most 1,000) per tick. Published outbox rows go past
`finapp.outbox.retention` (default 30 days). Inbox records go past both their written expiry and `finapp.inbox.retention`.
No lease and no leader: `FOR UPDATE SKIP LOCKED` means instances delete disjoint batches. The counters are per table,
tagged `type="outbox_event"` or `type="inbox_message"`.

**What is never deleted.** A pending or an abandoned outbox row, and a published one that a parked record not yet settled still
names. An inbox record younger than either bound, or one a replay order has extended.
`platform.idempotency_record`, sessions and audit records are not swept at all. They grow, by ruling, until Phase 16. Nor are
parked records, discard decisions or callback hints (`DATA_RETENTION.md`).

**`finapp.retention.sweep.failed` rising.** Read the instance's `Retention sweep of <table> failed` line for its SQLState.

| SQLState | Means | Do |
|---|---|---|
| `55000` | `platform.inbox_message` has a column the sweep was not reviewed against, so the inbox's sweep stops and deletes nothing. It may carry a state | Nothing is at risk: a kept record only costs storage. Ship a migration that reviews the column, adds a positive condition for its terminal values and lists it. **Never** add the column to the function's list by hand |
| `08*`, `null`, or an exception class instead of a SQLState | The database was unreachable or the pool was exhausted | The next tick retries. Treat it as the database's or the pool's incident |
| `42883` or `42501` | The function does not exist, or the role cannot execute it | Platform `V012` did not apply as it should: read the schema version before anything else |

**A table still growing.** Compare `finapp.retention.swept` with the table's arrival rate. An outbox that keeps
growing with a healthy sweep is usually a backlog (pending rows, which are never swept): §11.6. If the sweep is merely behind,
raise `finapp.retention.sweep.batch-size` up to its limit, or shorten the interval, on a rolling restart.

**Pause the sweeps.** Set `finapp.retention.sweep.enabled=false` and restart. Correctness does not depend on them: a row that
is not deleted costs storage only, so pausing is always safe.

**Move a bound.**

- `finapp.inbox.retention`: raising it protects rows already written, at once. **Raise `finapp.outbox.retention` first** if the new
  value would pass it (30 days by default): every instance refuses to start with the inbox's above the outbox's. Lowering it takes
  effect only for new records and for rows past their written expiry. An instance refuses to start with it at or below 8 days and
  5 minutes, the oldest record the broker can redeliver.
- `finapp.outbox.retention`: an instance refuses to start with it below `finapp.inbox.retention`, because an audited replay
  reads the outbox across that window. Lowering it deletes only what is then past it.

**Never** delete from these tables by hand to relieve space: a dedupe record removed early makes the next redelivery of that
message a second effect (`INV-IDEM-04`). The application role cannot, and an operator with the migrator's credential must not.

### 11.26 The edge: two controllers, the generated route lists, a renewed certificate (`P15-TSK-011`)

*(Written by `P15-TSK-011`, 2026-11-09, from the manifests and their checks. **Its `kind` half rehearsed on a cluster** by `P15-TSK-012`, 2026-11-09, §11.28. What
`infra/scripts/edge-check.sh` proves is the pinned proxy, with the committed files, against a stand-in backend.)*

**What is there.** Two Deployments in `finapp-edge`, `finapp-edge-public` and `finapp-edge-operator`, each Traefik by digest (`deploy/edge/base/controllers.yaml`,
held equal to `infra/scanner-pins.sh`), on the file provider. Neither calls the Kubernetes API or holds a token. Routes reach a controller only through
the hash-named ConfigMap generated from `public-routes.yaml` or `operator-routes.yaml` plus its `*-edge.yaml`; a route is never added any other way. The
operator controller sits behind an internal load balancer, and the public one holds no operator route. Both forward to the Service `finapp-app` on 8080, a new
connection per request, so any replica serves any request and no session is sticky.

**Run the checks.** `infra/scripts/edge-check.sh`, Docker required, from any machine. It renders every overlay, validates them, runs the policies (with a plant per
rule), then drives the real proxy. On a machine whose TLS is intercepted set `KUSTOMIZE_IMAGE`, `KUBECONFORM_IMAGE` and `CONFTEST_IMAGE` as in §11.14. It exits 0
green, 1 on any failure, 2 with no Docker or no `openssl`.

**A route was added or its guard changed, and `EdgeAllowListTest` is red.** That is the check working. Review `app/build/edge/public-routes.yaml` and
`operator-routes.yaml` (the test writes them there), copy each over `deploy/edge/base/`, and commit. A route that newly appears in the public file is a decision to expose it:
read it as one. Never edit the committed file by hand: the test fails on any difference.

**A client gets a `404` from the edge.** Either the method and path are not on the surface's list, or the path uses a refused encoded character. A path variable value must be letters, digits,
`_` and `-`, with a dot only between other characters. The controller's JSON access log has the request; there is no application record, because the application was not reached.

**A provider's or a merchant's traffic gets a `429`.** The limits in `public-edge.yaml` are coarse, one bucket per source across each tier's routes per controller replica, in rate (`source-*`) and in requests held in flight (`hold-*`), and are reference numbers until Phase 16 calibrates
them. Raising one is a change to that file: read the tier's comment first. Raising a `hold-*` amount also moves the sum `EdgeAllowListTest` holds under the spool.

**Large bodies answer `500` and small ones are fine.** The controller's spool is full. A body or response past 64 KiB spools to `/tmp`, a memory-backed volume of 256 MiB, before anything has authenticated. One source cannot fill it at the limits above; several can, and an abandoned upload's spool is held until the proxy's next garbage collection, under two minutes as measured. No pod is evicted or restarted, and a rollout clears it at once. While a request lasts, a KYC document or a settlement file past 64 KiB sits on that volume in plaintext: in RAM, never on the node's disk.

**The address in a record is the edge's own, or every client shares one.** The application believes `X-Forwarded-For` only from a connection inside `FINAPP_EDGE_PROXIES`, the pod network its
overlay names (for staging and production the Terraform environment's `cidr_block`). Blank trusts nobody and the recorded address is the controller pod's. On a real load balancer the
client's own address must survive to the controller, which the staging and production overlays' `preserve_client_ip.enabled=true` target-group attribute asks for on their `ip` targets (`kind`'s NodePort uses `externalTrafficPolicy: Local`, which does nothing for an `ip` target); without it, every client appears as the load balancer and shares one per-source limit and one recorded address.

**A renewed certificate.** The Secret and the routes are one projected volume, and the file provider watches its directory. The kubelet's swap of the volume reloads the certificate without a restart; `edge-check.sh`
proves that swap on the pinned image. A real cluster's is `P15-TSK-020`'s rehearsal. No `Certificate`, issuer, hostname or TLS Secret is committed for staging or production: until a real environment provides the Secret,
the controller pods do not start.

**Failure and rollback.** An edge outage is refused traffic and nothing more: the application stays ready, and its schedules and consumers do not notice. Revert by the previous manifest digest; every route and limit is in the ConfigMap's hash, so a revert restores them together.

**The operators' half: staff on the operator host alone** *(written by `P15-TSK-042`, 2026-11-11, from the code and its checks; not yet run on a cluster - the `kind` rehearsal's edge step asserts it from its next run)*. Each controller sets `X-Finapp-Surface` (`public-edge.yaml`, `operator-edge.yaml`, the `edge-headers` middleware), replacing whatever a client sent. The application refuses a role-holding identity - anyone holding a live role - at sign-in, the MFA step, a factor's enrolment and confirmation, the password change, a recovery channel's addition and recovery, unless the operator edge marked the request. The word is trusted only because `finapp-app-ingress` admits no pod but the two controllers to port 8080: a change to that policy that admits anything else makes the marker anybody's to write, and is a security change, not a network one. One exception is the policy's own: a pod cannot block its node, and on EKS the network policy agent admits the node's address on every port, so a host-network process on that node could write the marker; it could reach the operator routes directly as well, so the marker gives it nothing more, and keeping host-network workloads off the application's nodes is what bounds both.

**Rolling it out, and back.** The edge first, then the application: an application that requires the marker must never meet an edge that does not send it. The order is always safe, because an application without this change ignores the header. To roll back, the application first, then the edge if at all; the header left on the edge harms nothing. Wherever nothing is deployed in front of the application - no edge network named (`FINAPP_EDGE_PROXIES` blank) and its database on loopback: a laptop, the test tiers - it needs no marker and the one port serves both surfaces, as before the edge. A deployment whose database is reached through a sidecar on 127.0.0.1 is still behind the edge, because its overlay names the edge network.

**An operator cannot sign in.** First: are they on the operator host? On the public one their own password is refused with the same `401` as a wrong one, by design, and nothing is counted against their lockout there. Read `identity.PublicSurfaceRefused` for their identity (`platform.audit_record`, `target_id` the identity): `surface=public` means they used the public host; `credential=proven` on it means their correct password was presented from the internet: treat it as a phished credential to be replaced on the operator host, and the identity's sessions ended, not as a support ticket. `surface=unmarked` means the request reached the application with no usable marker: the edge was rolled after the application, or something other than the edge reached it. `finapp_identity_surface_refusal_total{kind="unmarked"}` rising across operators says the first; roll the edge forward (or the application back), and check `finapp-app-ingress` before anything else if the edge is as built. Customers are served throughout, so this is never an outage for them. An operator who only lost their password recovers on the operator host; the public host answers their recovery request `202` and does nothing. A staff member who banks on the platform with the same login cannot sign in from the internet to their own accounts either; they use them from the operator host, or keep a second, customer-only login (`THREAT_MODEL.md` section 6 item 39 (c)). `finapp_identity_surface_refusal_total{outcome="credential_proven"}` counts the refused sign-ins whose password was right: any increase is a phished credential.

### 11.30 Abuse limits - `finapp.abuse.requests`, `PublicDoorsRefusingMost` (`P15-TSK-022`)

*(Written by `P15-TSK-022`, 2026-11-09, from the code and its tests. The counted case across real instances is owed to `P15-TST-002`; it has run on a scratch PostgreSQL by hand and on no cluster.)*

**What is there.** The five routes that need no session and no signature (registration, sign-in, recovery, recovery completion, channel verification) are each counted per source and fixed window in the unlogged table `platform.window_counter`, across every replica. The numbers are `finapp.abuse.*` in `application.yaml`, reference values calibrated by Phase 16. The edge's limits (§11.26) are a first, coarse layer, per controller; these are the ones that count. A provider's callback is not counted: its signature is checked first and a forged one writes nothing.

**What a source is.** The connection's address as the edge set it (`FINAPP_EDGE_PROXIES`, §11.26): an IPv4 address whole, an IPv6 address by its /64, hashed under `FINAPP_ABUSE_BUCKET_KEY` to one of 65,536 buckets, and the table holds no address. Registration, sign-in and the recovery request are counted per IPv6 /48 as well, after the /64 (a /48 holds 65,536 /64s, and each of those doors does work a holder of many /64s would multiply). So a carrier's NAT is one source, a host's whole /64 is one source, and two unlucky sources can share a bucket.

**`PublicDoorsRefusingMost` (ticket).** Over half of the last 15 minutes' requests at the doors refused, at least 50 of them. A real flood at a door looks like this, and so does a wrong address: if `FINAPP_EDGE_PROXIES` is wrong, or the load balancer's client-address preservation is off, the edge's own address becomes every client's source and the doors refuse almost everyone. Check those two first when `outcome="refused"` rises across every door at once. **A shared address is not a fault**: behind a carrier's NAT or an office, one device can spend sign-in's budget for everyone there, because a successful sign-in counts too, and the 11th new customer from an address in an hour waits for the window. `THREAT_MODEL.md` section 6 item 33 (b).

**Reading it.** `finapp.abuse.requests{type,outcome}`: `type` is the door, and `outcome` is `admitted`, `refused`, or one of the three reasons for a `503` below. **This meter is the whole record of a refused request**: a refusal writes no audit row and no log line per request, by design, because either would let a flood write the table or fill the log. A rise in `refused` on one door is somebody at that door; the rows (`SELECT scope, count(*), sum(admitted) FROM platform.window_counter GROUP BY 1`) say how many buckets are in use and how many requests were admitted, and never who.

**A door answers `503 api.TemporarilyUnavailable`.** It could not count the request, and no door fails open: they all need the same database next, and a limit that lifted itself when the database was in trouble would be the attacker's best moment. Which of three:

| `outcome` | Meaning | First steps |
|---|---|---|
| `at_capacity` | This instance is running `finapp.abuse.max-concurrent-counts` (3) counts already and no place came free in `finapp.abuse.permit-wait` (100 ms). A count holds its place while it waits on the pool, so a saturated pool shows up here | §11.21: the pool, then who is holding it. Raise the cap only with the pool, keeping counting to at most half of it |
| `store_unreachable` | The connection, the pool's 5 s wait, or the server: a timeout, a dropped connection, too many connections, the server going down. A read the server never answers ends at the 2 s network timeout and gives its place back, so a failover's old primary cannot hold the doors shut after the pool has moved on | The database's own health. The doors recover by themselves |
| `store_error` | PostgreSQL answered and refused the statement: a schema this release was not built against, or a broken constraint | The deploy in progress (§11.1c), then the table's DDL against platform `V014` |

**Fixed windows.** A window has a fixed start and end, the same on every replica, so a source can be admitted twice its limit across a window's end. That is a burst bound, accepted; a limit a source hits repeatedly is hit at the same rate in every window.

**A retry counts.** A count runs after the `Idempotency-Key` header's shape is checked and before the idempotency record is read. So a same-key retry costs a count, and past the limit it is answered `429` where the stored `201` would have been replayed; the stored answer comes back once the window has ended.

**Raising a limit.** Set `FINAPP_ABUSE_<DOOR>_LIMIT` (and `_WINDOW`, and `_SITE_LIMIT` where the door has one) in the overlay and roll. There is no switch that turns a limit off, deliberately: a door without a limit is registration without a bound, and `THREAT_MODEL.md` T47's acceptance rests on it. A window below a minute is refused at start, and so is any set of windows one instance's sweep could not keep up with at the worst the key space allows. A changed window applies to new windows; the row for the old one ages out.

**Rotating `FINAPP_ABUSE_BUCKET_KEY`.** Change the store, sync, roll (`SECRET_MANAGEMENT.md` §5.1). The buckets reshuffle: each source starts a fresh window in a new bucket, and while a rollout has both keys on the fleet a source can count once per key. Nothing is signed or encrypted with it, so the cost is at most one window's budget. Keep the key secret: it is what stops an attacker computing an address of their own that shares a named victim's bucket.

**After a failover.** The table is unlogged, so a crash or failover empties it: every source has a fresh window, once per crash or failover. That is the cost of a counter that puts no row in the WAL. Nothing needs doing.

**Size.** At most 65,537 rows per count per window, measured at about 14 MB per count-window including both indexes; a door counted per /48 as well has two counts. `finapp.retention.sweep.failed{type="window_counter"}` rising means ended windows are not leaving; the doors keep working and the table grows by at most that bound each window. See §11.15 for a failing sweep.

### 11.28 The kind rehearsal: running it, reading it, the last record (`P15-TSK-012`)

*(Written by `P15-TSK-012`, 2026-11-09. This is a record of a rehearsal, not a procedure for a deployed environment: nothing here has run anywhere but one `kind` cluster on one machine. Numbers are from the run named below, on a loaded development machine, and are not a capacity statement.)*

**What it is.** `infra/scripts/kind-rehearsal.sh` deploys the platform as the `kind` overlay describes it onto one real API server (node image `KIND_NODE_VERSION` in `infra/scanner-pins.sh`, the minor the clusters declare) and walks a release through: the role bootstrap Job, the migrator Job, release N − 1 on its own schema, smoke, the network-policy, admission and edge acceptance suites, a replica of N started ahead of its Job (the hold), N's migrator, N − 1 on N's schema, a rolling update to N under a scripted workload, the fleet held at its connection budget with an operator tried, a payout held at the provider and the replica that sent it killed, a rollback by digest with one eviction mid-way, a re-roll. After each step it reads the financial census from PostgreSQL. It exits `0` when every assertion held, `1` when one failed, `2` when a step could not run.

**What is generated, per run, and removed at exit.** A local CA and everything it signs (PostgreSQL's server certificate, the broker's keystore, the simulator's keystore), the application's truststore, every value of the four Secrets `infra/scripts/kind-secrets.sh` writes, and the SCRAM user. Nothing of it is ever under the repository, and `CommittedConfigurationHoldsNoSecretTest` holds the committed side.

**N − 1 and N, until a release is cut.** No release exists (`docs/migrations/released-schema-floor.yaml`: `none`), so N is the tree under test and N − 1 is the newest first-parent commit before the last migration landed, older code on a genuinely older schema; `REHEARSAL_NMINUS1_REF` names another. N − 1 is further built without one handler, so that "a handler added in release N" is a real case rather than an assertion: this is the rehearsal's stand-in, and the first real release moves both definitions.

**Run it.** Docker, `kind` (`KIND`, default `kind`), a JDK for Gradle, `openssl`, `git`. On a Docker Desktop machine whose Linux kernel has no `CONFIG_NFT_QUEUE`, kindnetd cannot enforce NetworkPolicy; set `REHEARSAL_POLICY_ENGINE=kube-router`, and the record will say which engine answered. On a machine whose TLS is intercepted, `REHEARSAL_CA_BUNDLE` names a bundle Helm and the node trust. `REHEARSAL_KEEP=1` leaves the cluster; the artefacts go to `build/rehearsal` (`REHEARSAL_ARTEFACTS`). CI's `rehearsal` job runs it on `master`, nightly and on a change to `deploy/` or the harness.

**A red run.** `assertions.tsv` holds every assertion's verdict, `rehearsal.log` the timeline. A red census names the failing clause in `census-<step>.txt`; a red rollout keeps `pods-at-failure-<step>.txt`; `samples-<step>.tsv` is the connection count every two seconds and `reports-<step>.tsv` each replica's report.

| Step | What it asserts |
|---|---|
| bootstrap, migrator | The roles created over `verify-full`; every migration applied to an empty database; `result=APPLIED` |
| smoke | Register, authenticate, a card top-up (authorize and capture through the simulated provider), a transfer, a checkout to the merchant, a payout: every step checked, through the real edge |
| network policy | A pod in the rehearsal's own namespace reaches neither port of a replica; the scrape reaches 8081 only and the edge's controller 8080 only; from a replica's own network namespace an unlisted address and listener time out while PostgreSQL and DNS (UDP and TCP) answer; the migrator reaches PostgreSQL and not Kafka; a pod no other policy selects is held by `default-deny` |
| admission | A pod stating no requests refused by the LimitRange (`No request is specified`), a second container stating nothing by the quota (`must specify`), a complete pod admitted, a pod breaking only `restricted` refused by Pod Security |
| edge | A customer route over TLS with HSTS, the client trusting the edge's local CA alone; an operator route `404` on the public host and answered on the operator host; cert-manager's expiry series scraped |
| hold | A replica of N ahead of its Job: not ready, its log saying why, no consumer-group membership, nothing parked, its relay publishing nothing while a smoke runs through the fleet |
| census (each step) | Trial balance zero per currency and per entry; one transfer per reference and one entry per transfer; one captured intent and one posting per card payment the client was told succeeded; one payout and one posting per payout; one capture key and one payout key at the provider for each; the wallets' total against what the client put in; no intent or payout left in flight; the outbox drained, nothing dead-lettered, nothing parked; no expired idempotency lease; every balance explained by the journal, on its account's normal side; every partition owned and none behind; no published event of a catalogued handler's type without its inbox record; GL = ledger (the application's own `agrees` for the month, with the reference chart activated by two persons); the fleet on TLS. A census printing fewer verdicts than it has clauses is red |
| rollout | Zero failed requests across it, every flow of the workload doing work; ready replicas never below three; the peak of `pg_stat_activity` for the fleet at most `instances x pool`, every sample at most eight for each pod, a report generated on each replica so that all eight open; an operator admitted at the peak; a window that closes only when no pod is terminating |
| handler window | The records of the added handler's event that N − 1 committed unseen, counted; the audited replay over the window; every event of the window then applied exactly once; at the end, nothing of the type since N's first rollout began left unseen, in the windows or out of them |
| budget's ceiling | The fleet held at exactly `instances x pool` connections, and an operator admitted while it is |
| forged address | Five requests at the recovery door, each forging a different `X-Forwarded-For`, counted in one per-source bucket; a second real client at another address in a second: the key is the address the controller saw, and the door's count is read from `platform.window_counter` |
| pod kill | A payout held at the provider after its effect, the replica that sent it killed with its claim `IN_PROGRESS`; requests retried with the same key until they resolve, each counted, a kill with no failed attempt being not exercised; the claim completed by another replica; the budget and an operator as for a rollout; another replica holding every partition; no lag, read from the broker's own offsets |

**The last record.** 2026-11-09, one run of the whole sequence on a fresh cluster, both images built in the run, on one development machine: `kind` v0.33.0, node `v1.34.11`, kube-router as the policy engine (this machine's kernel has no nft queue), release N the merged tree `18326010`, N − 1 `740c87bf` built without the capture hint. 75 assertions held, none failed; each step's census was green.

| Step | Measured |
|---|---|
| Migrator | N − 1: `result=APPLIED schemas=21 applied=329`, on an empty database. N: `applied=13`, on N − 1's schema; a surge replica of N stayed held until it had run |
| Rolling update, N − 1 to N | 398 s to the last old pod gone; 878 requests succeeded, 0 failed, none unresolved; peak 39 connections of the budget 48, no sample above eight for each pod; an operator admitted at the peak; three replicas ready throughout; the settled fleet holding 24 connections, every reporting pool open |
| Handler window | 166 of 331 `payments.PaymentCaptured` records committed unseen by N − 1, the window closed once no pod was terminating; after the audited replay each applied exactly once |
| Forged address | Five forged `X-Forwarded-For` values at the recovery door: one bucket, counted five times; a second real client: a second bucket |
| The budget's ceiling | The fleet held at 48 connections by sessions named as its own: an operator admitted, 49 client connections of the server's 60 |
| Pod kill, force and no grace | A payout held at the provider after its effect, the replica that sent it killed with its claim `IN_PROGRESS`: 108 requests succeeded, 17 attempts failed and were retried with the same key, none unresolved; the claim taken over and completed by another replica, one payout key at the provider; peak 24, an operator admitted at it; every partition owned, none behind |
| Rollback by digest, an eviction mid-way | 402 s; 862 requests succeeded, 0 failed; peak 39, an operator admitted at it |
| Re-roll to N | 401 s; 869 succeeded, 0 failed; peak 39; a replica answers the scrape after traffic; the rollback window's 343 of 674 records each applied exactly once |
| The whole run | Nothing of that type published since N's first rollout began left unseen, inside the two windows or outside them |

**Two defects it found, both fixed.** The first scrape under traffic answered `500`: the SLO filter gave one route's timer a histogram and another's a summary under one name, which the Prometheus registry cannot hold (`SloLatencyBuckets` now gives every `http.server.requests` timer the boundary). After the pod kill one partition stayed one record behind for as long as no record arrived: an offset commit refused during a rebalance was not retried (`KafkaEventReceiver` now sends it after the next poll, for a partition this member still holds). Neither moved money: a record whose offset was never acknowledged is redelivered into the dedupe.

**What the budget measured.** Each replica generates one report, so that its reporting pool opens and all eight of its connections count: without that a replica holds seven, and the eighth is never measured. (A provisional report's freshness is one second in this overlay, or the first report would answer all the others.) The peak is held to `instances x pool`, and each sample to eight for each pod it had. At three replicas the shipped rollout reached five pods at most in these runs. With `minReadySeconds: 0` and a long `preStop` it reaches six, the formula's maximum, and what stops a seventh is the `kind` overlay's `ResourceQuota`, which equals the formula, not the rollout. With that quota raised by one pod and a new pod evicted while the three old ones drain, 56 connections went past the budget of 48 and the run went red at its peak assertion.

**Read from it rather than asserted.** Pods reaching Ready under `default-deny` with no rule naming the kubelet is this environment's evidence that node traffic is admitted. The zero failed requests through the edge across each rollout are what measure `maxIdleConnsPerHost: -1`, a new connection through the Service per request.

**What this does not mean.** One node, one machine, three replicas plus a surge: load, chaos and ten instances are the storm's (`P15-TST-001`, postponed by the owner). The broker and the database are test-only copies. kindnetd has not answered yet: `THREAT_MODEL.md` section 6 item 30. No Prometheus runs in the cluster: cert-manager's series and a replica's scrape are read once by the rehearsal's own pod. The cooperative assignor's moving only what changes hands is counted by a test of the broker tier owed to `P15-TST-002`, and a refused broker credential failing readiness was not exercised. T40's acceptance rows were not created, because an offer needs the origination chain the rehearsal does not build: the forged address is measured on the per-source key, the same `request.getRemoteAddr()`. The numbers are from a loaded development machine.

**CI's runner.** Nothing has run there. `ubuntu-latest` on a public repository is, by GitHub's published figures, 4 vCPUs and 16 GB. The application namespace's quota is 8 GiB, and the rest of the cluster comes on top of it (the node's own components, cert-manager, both edge controllers, the simulator, the client's JVM), so it should fit with little to spare, and a 7 GB runner would not. The job's first run is the test. It sits inside the workflow's `concurrency` group: a run in progress on `master` is not cancelled, but a newer push replaces one still waiting, so a rehearsal can be skipped between two pushes and the nightly run is what covers it.

### 11.32 A refused image: signing, admission, and what to do when the cluster says no (`P15-TSK-007`)

*(Written by `P15-TSK-007`, 2026-11-11, from `infra/scripts/admission-check.sh`, `deploy/admission` and
`deploy/operators/policy-controller`. **Proven on `kind`** by that script: the last record is below. No real cluster runs
the controller yet, and no real identity signs anything.)*

**What is there.** The Sigstore policy controller, two replicas in `cosign-system`, installed from its pinned chart with
`deploy/operators/policy-controller/values.yaml`. It watches the namespaces labelled `policy.sigstore.dev/include: "true"`:
`finapp` and `finapp-edge` (`deploy/namespaces`). Every pod there and every pod template is checked before it exists:
Deployments, Jobs and their pods, init containers included. A debug container added to a running pod is checked too, by the controller's own registration of `pods/ephemeralcontainers` (read from its code, not exercised on `kind`). `deploy/admission` decides what passes:

- `finapp-app` and `finapp-migrator`, and the names an environment's registry gives them (`finapp-<environment>-app`,
  `-migrator`), from any registry, must carry the trusted authority's signature (`finapp-images-signed`) and both attestations,
  SLSA provenance and the CycloneDX SBOM (`finapp-images-attested`);
- the edge's Traefik passes by its one pinned digest (`finapp-edge-traefik`), the only exception;
- anything else is refused (`no-match-policy: deny`), and so is everything while the controller cannot answer
  (`failurePolicy: Fail`).

**Run the check.** `infra/scripts/admission-check.sh`, Docker and `kind` required, a JDK for the build. It builds both images,
signs and attests them under a key of its own run, verifies them, then proves every case on a cluster of its own and deletes
it. On a machine whose TLS is intercepted set `ADMISSION_CA_BUNDLE` (the chart pull and the node's pull of the controller)
and `TRIVY_IMAGE` (§11.9). `ADMISSION_IMAGES="<app> <migrator>"` skips the build. Exit 0 green, 1 an assertion failed,
2 it could not run. The artefacts are in `build/admission/<run>`: every case's answer, the policies applied, the webhook as
registered, each image's provenance and SBOM, the digests, and the run's public key. The private key is never written there.
Each run names its own cluster, registry and volume, so two runs never meet, and it refuses a cluster of its name that it did
not create rather than delete it.

**The apply or the sync itself is denied.** This is the usual symptom: the controller checks a Deployment, a Job and every
other pod template when it is written, so the change never lands. The answer reads
`Error from server (BadRequest): ... admission webhook "policy.sigstore.dev" denied the request: validation failed: ...`,
and GitOps shows the sync failed with it. A ReplicaSet's `FailedCreate` event with the same words comes only later, when a pod
is created from a template admitted before: a scale-out, a rescheduled replica, or a policy changed since. Either way, a
`failed policy: finapp-images-signed` (or `-attested`) means the image is not signed (or not attested) by the identity the
policy trusts. Read the image reference in the message:
- **A digest nobody promoted.** Someone wrote a digest into the overlay by hand. Revert the change. The running replicas are
  untouched: a refused ReplicaSet gets no pods, and the old one keeps serving.
- **A digest that was promoted.** Its signature is missing from the signature repository, or was made under another
  identity. Verify it from a workstation: `cosign verify --key <the environment's key>` for a key, or the identity flags for
  keyless, pointing `COSIGN_REPOSITORY` at the environment's signature repository. Re-sign only through the pipeline, never
  by hand.
- **`no matching policies`.** The image is neither of the two platform images nor the pinned Traefik: a third image, a
  sidecar, a debug container. Adding it is a change to `deploy/admission` and a review, never a label taken off the namespace.
- **`must be an image digest`.** A tag reached the manifest. The overlays name digests; find who wrote the tag.

**Every pod creation in `finapp` fails with `failed calling webhook "policy.sigstore.dev"`.** The controller cannot answer.
Admission is failing closed: nothing new starts, and nothing running stops. Look at `cosign-system`: are both replicas ready,
and is their Service reachable? Bring the controller back. **Do not** set `failurePolicy: Ignore`, remove the namespace
label, or add the label `webhooks.knative.dev/exclude` to the namespace to get a rollout through. Each opens the namespace to
any image, and the second and third are invisible in a rollout's diff. The controller adds
`webhooks.knative.dev/exclude DoesNotExist` to its own selector, so that label, with any value, takes a namespace out of
admission. `ImageAdmissionTest` refuses it on any namespace under `deploy/`.

**Installing the controller in a new cluster: the order matters.** Its validating webhook is created with no rules, and the
controller fills them in when it starts. Until then nothing is checked. So the order is:
1. the controller, ready;
2. `kubectl get validatingwebhookconfiguration policy.sigstore.dev -o jsonpath='{.webhooks[0].rules}'` not empty;
3. `deploy/admission` applied, every policy `Ready`;
4. only then the platform's namespaces and their workloads.

The check waits for each of these the same way. `P15-TSK-017`'s delivery carries the order.

**Where signatures live.** In a repository of their own per environment, cosign's tag-based form (`sha256-<digest>.sig` and
`.att`), with mutable tags and no lifecycle rule. The image repositories keep immutable tags. A lifecycle rule on an image
repository can never reach a signature; deleting a signature repository's objects refuses every image it signed.

**What this does not cover.** The controller's own namespace, External Secrets' and `finapp-observability` are not watched:
their images are pinned by digest in their manifests, and nothing at the cluster enforces it (`THREAT_MODEL.md` section 6
item 35). The `kind` rehearsal (§11.28) deploys its own unsigned builds on a cluster with no controller. It rehearses the
rollout, not admission, and its test-only database and broker would be refused by this policy.

**The last record (2026-11-11).** On one machine (Docker Desktop, TLS intercepted, so `ADMISSION_CA_BUNDLE` and `TRIVY_IMAGE` set), Kubernetes v1.34.11 on `kind`. A full run built both images from the tree, wrote their SBOMs (111 and 31 components), signed and attested them under the run's key, verified both, and refused the three plants by name: `no signatures found`, `no matching signatures: invalid signature ...`, `no matching attestations`. Then every case answered as stated, 20 assertions, none failed. Both images were admitted at their signed digests. The unsigned and other-key images of each were refused by `finapp-images-signed`, and the unattested one by `finapp-images-attested`. The unsigned init container was refused, naming `initContainers[0]`, and the signed one admitted. An unnamed image was refused (`no matching policies`) in `finapp` and in `finapp-edge`, and Traefik was admitted at its pinned digest. While the controller was scaled to zero the signed app was refused (`failed calling webhook`), and it was admitted again once the controller was back. Every refusal's control was admitted in `finapp-admission-control`. The final bytes were run green again with that build's images and SBOMs, and each probe was run on them (`MUTATION_TESTING.md`). *(The review's fix round, the same day: a full run on its final bytes was green with 23 assertions, adding the application admitted under an environment's repository name and refused there unsigned; the registered webhook's selector read `webhooks.knative.dev/exclude DoesNotExist` beside `policy.sigstore.dev/include In [true]`.)* CI's `admission` job has not run yet.

### 11.35 Restore: held until the platform's own checks pass on it; the restore rehearsal and its last record (`P15-TSK-030`)

*(Written by `P15-TSK-030`, 2026-11-11, from `infra/scripts/restore-rehearsal.sh` and the two commands it runs from `app`'s test source set, `RestoreVerification` and `RestoreRehearsalSeed`. The procedure has run against containers on one machine, as the rehearsal below. It has never run against a managed instance: `THREAT_MODEL.md` §6 item 38.)*

**The first rule: no money moves on a restored database until the verification passes.** Every schedule, every consumer, the outbox relay and every door reach PostgreSQL only through the pools the application opens as `finapp_app`, and report generation as `finapp_reporting` (`application.yaml`, `spring.datasource` and `finapp.database.reporting`). So the restore makes those two roles and `finapp_migrator` unable to log in before anything is restored into the database, and releases them only when every check below has passed. What enforces it is the database, not this page: a replica pointed at the restored database cannot open a connection, so readiness's `db` is down and no door is routed to it, and `SchemaVersionGuard` never proves `READY` (a failed read moves no verdict), so its schedules, relay and consumers do no work (`P15-TSK-005`'s hold, as a second line). Ten replicas are refused exactly as one is, and a migration Job cannot run either.

**What "financially whole" means: the platform's own checks, not a copy of them.**

| Check | What answers it | Where it runs |
|---|---|---|
| `flywayValidate` | Every schema's history against the migrations of the release the backup was taken at: the manifest records each schema history's head, and `./gradlew flywayValidate` (CI's gate) runs from that release's tree | the script, in a read-only session |
| Row counts equal to the source's | Every table's `count(*)`, read in the dump's own snapshot and again on the restore | the script, against the backup's manifest |
| The catalogue equal to the source's | Read from the catalogue: the roles' attributes, expiry, settings and memberships (cluster objects `pg_dump` never carries); the database's encoding, collation, character type, locale provider and locale, connection limit, grants and settings, a role's own setting in it included; every definer function with its owner and settings, every `TRUNCATE` refusal, every `UNLOGGED` table, every grant to a platform role | the script, against the manifest |
| The schema equal to the source's | `pg_dump --schema-only`, every object, constraint, row trigger, grant and comment, against the source's schema taken once through dump and restore in its own cluster (a `CHECK` comes back from a restore with its parentheses regrouped, so the source's own text would differ from every faithful restore) | the script, against the manifest |
| Every sequence at or past the source's | Each sequence's value, read outside MVCC in the backup's own session (a sequence never goes back, so `pg_dump`, reading after it, writes one at least that far on), against the restore's: a sequence missing, never called where the source's was, or behind, fails. No unique index stands behind every value a sequence hands out (`bnpl`'s `record_order`, `kyc`'s `check_order` and `case_order`, `accounting`'s `recorded_order` are ordering keys, and a unique `seq` column takes a value that falls in a gap), so a sequence restored behind would not be refused by the first insert | the script, against the manifest |
| Trial balance per currency | The ledger's `TrialBalance.sweep` (`INV-ACC-01`) | `RestoreVerification` |
| GL = ledger per period | `GeneralLedger.balances` under the version `GlPins.forPeriod` reads, a locked period by its pin, judged by `GlEqualsLedger` (`INV-ACC-06`); a period that cannot be read fails | `RestoreVerification` |
| The projection check | `ProjectionVerification` through the wired `SourceProofs` (`INV-BAL-02`) | `RestoreVerification` |
| Every product proof | The wired `SourceProofs`, one reading per `SourceProofs.Source`, every `SourceProofs.Proof` answered once and complete (`INV-ACC-12`) | `RestoreVerification` |
| The source-join census | `SourceCensus`'s own walk and judgement over the whole journal from its floor, with a head kept in memory so the restored head never moves; every entry must be walked, and no finding may be new | `RestoreVerification` |

`RestoreVerification` starts the platform's own application context against the database, as the restore's administrator, in sessions whose default is `default_transaction_read_only`: whatever in that context started could change nothing, and the command refuses to run on a session that is not read-only. A check that throws fails, and so does a check over nothing (an empty journal, no period judged, a census that walked no entry). Its report states how many checks the catalogue has and then one `PASS|<check>|<detail>` line each, never an amount.

**The procedure.**

1. **Take the backup in one snapshot.** A `REPEATABLE READ READ ONLY` session exports its snapshot; in it, read the manifest: every table's count, the catalogue, every sequence's value, each schema history's head (the release the backup belongs to) and the snapshot's instant, T; `pg_dump --snapshot` writes the schema and the data from the same snapshot; the session commits after them. The dump is a copy of the ledger: it goes straight into `openssl enc` (AES-256) under a key made for it and never reaches a disk unencrypted. The manifest holds counts, names and versions, no row: its plaintext exists only while it is read, beside the source until it is copied out (and removed there) and in the operator's work directory until it is encrypted beside the dump (and removed there), and again during a comparison. Each ciphertext's SHA-256 is kept so a damaged file is refused before it is decrypted.
2. **A fresh server, the roles held.** Create the platform's three roles with their attributes and settings, and `NOLOGIN`, in one transaction, before anything is restored (the local role script here, the bootstrap step in an environment). Confirm each is refused at log-in (`role "…" is not permitted to log in`).
3. **Restore** with `pg_restore --single-transaction --exit-on-error`, as the administrator: all or nothing.
4. **Move the transaction counter.** A logical restore restarts the counter below the restored rows' `xid8` values (ledger `V034`'s `txid`, the census's head, any other `xid8` column, read from the catalogue). Stop the server cleanly, set the epoch past the highest of them (`pg_resetwal -e <highest / 2^32 + 1>`), start it, and confirm `pg_snapshot_xmin(pg_current_snapshot())` is above them. Only the epoch moves: the 32-bit transaction ids, and with them visibility, wraparound and the migrations that compare a row's `xmin` with the current id modulo 2^32, are untouched. A managed server gives no one its stopped data directory, so it cannot do this, and a logical restore into one is refused by the census check (`THREAT_MODEL.md` §6 item 38); a physical restore carries the counter and skips this step. The reset starts a new write-ahead log, so take a base backup of the restored server once it is released.
5. **Compare and verify**: `flywayValidate`, the counts, the catalogue, the schema and the sequences against the manifest, then `RestoreVerification`. `flywayValidate` judges a history against the migrations of the tree it runs from, so run it from the release the manifest's heads name: a backup taken at an older release is validated by that release's migrations, never refused for lacking a newer one's, and is migrated forward by the ordinary migration Job only after it is released.
6. **Before the release, the broker.** Kafka is not restored. The records it holds from after T (the manifest's instant) come from transactions the restore undid, and the replaced fleet had consumed some of them and not others when the disaster came: a group left where it stood would go on to read the rest, and one reset to an instant before T would read them all; either way the restored fleet would apply effects of transactions that no longer exist. And the records published before T that a group consumed only after it lost their effects and their dedupe rows in the restore, while the group's offsets stand past them. So the broker is brought to the restored database's own position, in this order (`EVENT_ARCHITECTURE.md` §Replay; `DISTRIBUTED_EXECUTION.md`'s `KafkaEventReceiver` row; an offset is committed only after its inbox transaction):
   1. **Every group to the end, with the fleet stopped.** Scale the deployment to zero, so that no `finapp.<module>` group has a member (the tool resets only an inactive group), and move each group to the end of every partition with the broker's own tooling, Kafka 4.3.1's `kafka-consumer-groups.sh --bootstrap-server <broker> --command-config <the operator's client settings> --group finapp.<module> --reset-offsets --to-latest --all-topics`: once without `--execute` (a dry run, the default) to read the plan, then with `--execute`. `--all-topics` acts on the topics a group already holds offsets for; a group with none is given each topic it consumes by `--topic <topic>`, so it too starts at the end and never at `earliest`, where a group with no offset would otherwise begin (`KafkaEventReceiver`). `--describe --group finapp.<module>` then shows no lag. Nothing written after T is delivered.
   2. **Release** (step 7), and the fleet started.
   3. **The audited replay from the restored outbox** (§11.1.1's door), for each event type, over the window of first publication [T − m, T]. It re-sends only events the restored database holds: a consumer that handled one before T keeps its dedupe row and refuses the copy, and one that handled it after T lost the row with the restore and applies it once. m is the longest any group was behind at T, read from `finapp.inbox.lag{module}` at T in the metrics store where that survived. When the lag at T cannot be read, m is **24 hours**, a stated and deliberately wide bound: a replayed event a consumer already handled is refused by its dedupe row, so a wider window costs commands, never correctness. Whatever m is, T − m must stay inside the inbox's dedupe window counted from now (`finapp.inbox.retention`, 14 days), or the replay refuses the command; so a backup whose T − m is older than that cannot be caught up this way, which bounds the backup a restore may use. One command requeues at most 1000 events (replay at volume is Phase 16's), so a busy window is several commands.
   4. **The relay** publishes the rows the restored outbox holds unpublished, those written before T and published after it among them, with their own event ids. The copies the replaced fleet published after T sit behind the groups' new offsets and are never read; each event is delivered once, from the restored outbox, through the dedupe.

   So everything the restored database knows is delivered once, and nothing it does not know is delivered at all. What the undone transactions did at a provider is the paragraph after this procedure.
7. **Release only when every verdict is a pass.** `ALTER ROLE finapp_app LOGIN`, the same for `finapp_migrator` and `finapp_reporting`, and confirm each logs in. Then, and only then, point the deployment at it, start the fleet, and order step 6's replay (6.3).
8. **A refusal leaves the database unused.** Nothing is corrected to make a restore pass. A failing check is read as the incident it is (§11.12), the restored database is discarded, and the restore is taken again from another backup or the cause is found in the source.

**What a restore to an earlier point leaves outside the books.** A backup is a moment. Anything a provider did for the platform after it, a capture, a payout, a settlement line, is not in the restored journal, and the released fleet will meet it as an unknown outcome to inquire about or a settlement line with nothing behind it: the resolution sweeps ask the providers, and reconciliation raises the rest as breaks. That is the platform's ordinary handling of a provider that is ahead of it, and no step here replaces it. How far behind a backup may be is the recovery point, which Phase 16 measures.

**The managed half, referenced and deferred.** A point-in-time restore makes a new instance; its parameter group, security group and subnet group must be attached before anything connects, and only that parameter group gives it the application's `max_connections` (`infra/terraform/modules/postgres`, its README's "Operating it"). A physical restore brings the roles back able to log in, so step 2 becomes the master user's `ALTER ROLE … NOLOGIN` before the new address is given to anything, and step 4 does not apply. The verification would run from inside the network as the master user acting as the schemas' owner, a Job of the application image that does not exist yet. None of it has run: `THREAT_MODEL.md` §6 item 38.

**Run the rehearsal.** `infra/scripts/restore-rehearsal.sh`; Docker, a JDK for Gradle, `openssl`. Every server is `postgres` at the version the version catalog declares, by the digest `deploy/overlays/kind/postgres.yaml` pins for it. It builds the two commands (`:app:restoreRehearsalLauncher`); starts a source PostgreSQL on a random loopback port with its epoch set to 1, as a cluster that has run for a while would have; migrates it; seeds it through the platform's own services (`RestoreRehearsalSeed`: the period opened, the reference GL chart proposed and activated by two persons, four customers with EUR wallets, each funded by a two-person manual adjustment, the census run to its end, then six transfers past the census's head); verifies the source by the same checks, and a source that is not whole stops the run; backs it up and removes it; restores into a fresh server held as above; and releases that restore. Then it plants each kind of corruption in a restore of its own and requires each to be refused by exactly the checks named below and by no other (`RESTORE_PLANTS` selects them): nine are built so that one check alone can see them, which shows each of those checks working on its own, a dropped grant is seen by the catalogue and the schema each, and the last is the phase gate's. Each verification also reports how many subjects each proof judged, which a pass does not say. `RESTORE_SOURCE=kind:<node>` takes the backup instead from a `kind` rehearsal's cluster left up after its workload (`REHEARSAL_KEEP=1`, §11.28), inside its PostgreSQL pod as the overlay's superuser over the pod's own socket, and restores it the same way; nothing on the host reaches that database, so it is not verified before the backup, and its default plant is the phase gate's alone. The commands open no port (`spring.main.web-application-type=none`). No password is on a command line or in `docker inspect`: Docker copies each from the script's environment, and the servers read the administrator's from a file in the work directory. Artefacts (logs, reports, verdicts, never the backup) go to `build/restore-rehearsal` (`RESTORE_ARTEFACTS`). Exit `0` every assertion held, `1` one failed, `2` a step could not run. Stopped by a signal it traps (`INT`, `TERM`, `HUP`, `PIPE`) or by a failure it removes everything it made; a process killed outright cannot, and leaves its work directory under the system's temporary directory (a `backup.enc`, the key and the administrator's password file) and its containers (`finapp-restore-<pid>-…`), which a person removes by hand, as happened twice while this was written. CI's `restore-rehearsal` job runs the seeded sequence on master, nightly and on a pull request that changes a migration, the harness, its commands, the role script, the PostgreSQL version or pin, the GL chart, the launcher's build script, or the production code the commands call.

| Plant | What it is | Refused by |
|---|---|---|
| `unbalanced-entry` | One line of a two-person adjustment raised by one minor unit, with its proposal line and its balance row, so the census and the projection still agree | the trial balance, alone |
| `balance-disagrees` | One wallet's balance row moved by one minor unit | the projection check, alone |
| `missing-row` | One audit record deleted (no financial check reads the audit trail) | the row counts, alone |
| `grant-dropped` | `finapp_app`'s `INSERT` on the journal's lines revoked | the catalogue and the schema, each from its own side |
| `role-altered` | `finapp_app`'s connection limit changed, a cluster object `pg_dump` never carries | the catalogue, alone |
| `trigger-dropped` | `journal_entry_is_append_only` dropped: a row trigger, so no `TRUNCATE` refusal changes | the schema, alone |
| `sequence-behind` | The most advanced sequence set back to its start | the sequences, alone |
| `history-checksum` | One migration's checksum changed in `ledger`'s Flyway history | `flywayValidate`, alone |
| `gl-rule-repointed` | The active GL mapping's customer-wallet rule moved to a key no ledger account has | GL = ledger, alone |
| `counter-not-moved` | Step 4 skipped | the source-join census, alone |
| `journal-line-removed` | One wallet line of a transfer's entry deleted, `PHASE_GATES.md` §5 Phase 15 criterion 6's plant, made in the restore rather than in the dump's bytes | the row counts, the trial balance, the projection check and the census together; GL = ledger and the product proofs do not see it, because GL = ledger compares the GL with the same lines and no proof covers a wallet |

The product proofs have no plant of their own here: each is the platform's check with its own tests, run here as it is. On the seeded source the products with no seed flow give their proofs nothing to judge, which the subjects each one reports make visible; the `kind` source's record, when it stands below, is where they judge a flow's rows.

**The last record, seeded (CI's sequence and every plant).** 2026-11-11, one run on one development machine: Docker 28.0.1, `postgres:18.6` by the overlay's digest, the harness at sha256 `0e3004e2…8d6ddacd`, `RestoreVerification` `128e5b34…907fa0fe`, `RestoreVerdicts` `e536c832…dc9aa533`, `RestoreRehearsalSeed` `711a175e…43e1cb29`. 27 assertions held, none failed, in 15 minutes 9 seconds; the thirteen verifications' logs hold no `ERROR` line, so nothing in them tried to write.

| Step | Measured |
|---|---|
| source | migrated with its epoch at 1, the counter at 4294968789 |
| seed | period 2026-11 opened; GL mapping v1 `ACTIVE`; 4 wallets funded by two-person adjustments; the census judged 4 entries and stood at their end; 6 transfers after its head |
| source verified | all five checks pass |
| backup | at 14:24:12 UTC, in one snapshot: 3214416 encrypted bytes; a manifest of 358 tables, 34 sequences and 21 schema histories' heads; no plaintext dump at rest |
| roles | `finapp_app`, `finapp_migrator` and `finapp_reporting` each refused at log-in before `pg_restore` |
| counter | the highest restored `xid8` 4294968860 against a counter of 756, moved to 8589935349 |
| restore verified | `flywayValidate`, 21 histories at the backup's heads; 358 tables' counts equal; the catalogue equal (3 roles with their attributes and settings, the database's locale and grants, 6 definer functions with their owners, 263 `TRUNCATE` refusals, 1 `UNLOGGED` table, 722 tables' grants); the schema identical, 41813 lines; 34 sequences at or past the backup's; 1 of 1 currencies balanced; 1 period agreeing (2026-11 `OPEN`, v1); the projection proof and 8 product proofs complete with no discrepant subject; the census walked 10 of 10 entries with no new finding |
| subjects | `LOAN_SUBLEDGER` and `LOAN_REPLAY` 0 loan accounts, `LENDING_CAPITAL` 5 currencies, `BNPL_SUBLEDGER` 0 obligations, `BNPL_THREE_WAY` 0 currencies, `FX_BOOKS` 25 book lines, `POSITION` 26 positions with 5 suspense and 5 cash verdicts, `MERCHANT_PAYABLE` 0 payables, `PROJECTION` 5 ledger accounts |
| release | the three roles log in after the verification, and only then |
| plants | each refused by exactly the checks in the table above and by no other, and never released: `unbalanced-entry` (EUR out of balance), `balance-disagrees` (1 discrepant wallet), `missing-row` (`platform.audit_record` 27 against 26), `grant-dropped` (the journal line's `INSERT` missing, in the catalogue and in the schema), `role-altered` (`finapp_app`'s connection limit), `trigger-dropped` (7 schema lines), `sequence-behind` (`transfers.transfer_event_id_seq` never called against 6), `history-checksum` (`flywayValidate` failed), `gl-rule-repointed` (a mapping gap, `key:CUSTOMER_WALLET/CUSTOMER`), `counter-not-moved` (the census walked 0 of 10 entries), `journal-line-removed` (`ledger.journal_line` 20 against 19, with the trial balance, the projection and the census) |

**The `kind` source: no record yet.** `PHASE_GATES.md` §5 Phase 15 criterion 6 names a backup of a database the `kind` rehearsal populated. On 2026-11-11 the rehearsal (§11.28, master `332a8b08` merged, `REHEARSAL_KEEP=1`) went red at its `edge-acceptance` step, before release N's migrations: its newest assertion, that an operator's own password is refused on the public host, ran against release N − 1 (`a5b8507e`), which predates that refusal. That is outside the restore, so nothing here counts it. A diagnostic restore of the kept cluster's database (smoke only, 7 journal entries, the schema at N − 1's heads) ran the `kind` mode end to end: backed up from inside the pod (357 tables, 21 histories), every role refused at log-in, the counter moved past the restored `xid8` (1779, from a fresh cluster's epoch 0), and nine of the ten checks passed, the catalogue equal although the source's roles came from the role bootstrap Job and the restore's from the local role script; `flywayValidate` failed, as it must, run from a tree whose migrations are newer than the backup's heads (step 5). The proofs judged `MERCHANT_PAYABLE` 1 payable and `PROJECTION` 8 accounts there. Until a record of the whole sequence stands here, criterion 6 is met only over the seeded source above.

**What this does not mean.** One machine, containers, a journal of ten entries: no measured recovery time or recovery point (Phase 16), and no managed instance (item 38). The products with no seed flow (cards, lending, BNPL, FX, merchants, positions) were judged over no rows, so their proofs answered complete over nothing in this record; they read real rows when the backup holds them. No period was locked, because a period locks only after its grace on the database's clock, so "GL = ledger per locked period" ran on the open period, through the same `GlPins.forPeriod`, and a locked period that cannot be read under its pin is refused (`RestoreVerdictsTest`). An open period with a GL finding, or any proof finding, refuses a restore of that database: the verification is strict, and a person decides from the break register. A census finding recorded before the backup is disclosed rather than refused, because nothing ever closes one. GL = ledger reads the months that have an accounting period; a month with entries and no period is read by the trial balance and the census only. A sequence's value is outside every snapshot, so the comparison is "at or past", never "equal": a value the source drew after the snapshot, for a row the dump does not hold, leaves a gap, as a rolled-back insert does in ordinary running. The backup's integrity is a SHA-256 digest beside it, which finds a damaged file and not a deliberate one: the work directory is the run's own and lives as long as the run. The seed's parties, identities and customers are written by SQL, as the database suites write them; every entry and balance is the platform's own.

## Provider callbacks are hints (`P15-TSK-036`, ADR-0083)

A signed callback no longer concludes anything by itself on the payment, instant, FX or corridor doors. Its evidence and dedupe
commit, then the door asks the provider, and the delivery is acknowledged after that one inquiry. KYC's callbacks are outside
this rule. So a callback's outcome now waits on one inquiry, bounded by that provider's timeout.

| You see | It means | Do |
|---|---|---|
| A payment stays `*_UNKNOWN` or `AWAITING_PAYER` after its callback arrived | The inquiry failed or had no answer; nothing was adopted | Nothing. The resolution sweep asks again. If it persists, check the provider's inquiry endpoint |
| `finapp.payments.webhook{outcome="unmappable"}` rising, with WARN lines saying the provider "has denied a signed callback for longer than its bound" | A signed callback the provider's own inquiry still denies, past that kind's `refute-after` bound | Treat as a possible **webhook key compromise** until shown otherwise, then rotate the key under `SECRET_MANAGEMENT.md`. Nothing has moved, and no reversal is needed on this signal alone |
| The same series rising, with WARN lines saying "we cannot read" | The provider answered and the adapter cannot read it: a wire change. Nothing moves and each question stays open | Fix the adapter. The hints then conclude on their own |
| WARN "contradicts the row" | The provider's answer disagrees with a concluded payment | A break: the row stands, the answer rests as evidence, and reconciliation will type it from settlement. Investigate with the provider |
| `payments.callback_hint` rows with `concluded_at IS NULL` growing | Clearing, dispute or parking inquiries are not being answered | Check the provider's inquiry endpoints. The sweep's wait doubles from `finapp.payments.callback-hint.retry-after` (1 min) to `.retry-cap` (6 h). A denial is believed only past `.refute-after` (disputes 2 h, clearing 30 min, executions 15 min) |
| WARN "never answered within their bound and are abandoned" | A hint passed `finapp.payments.callback-hint.give-up-after` (7 days) | The callback's evidence stands and settlement reconciliation is the backstop; find the notice in `provider_evidence` and reconcile it |
