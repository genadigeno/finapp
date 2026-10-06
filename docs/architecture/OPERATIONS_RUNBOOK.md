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
