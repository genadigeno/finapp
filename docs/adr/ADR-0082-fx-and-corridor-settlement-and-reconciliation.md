# ADR-0082 — FX and corridor settlement and reconciliation: legs are single-currency expectations, new causes but no new break types, and reconciliation still never converts

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: Settlement · Reconciliation · FX · Payments · Ledger
Supersedes: nothing. Amends ADR-0068 (the first rule set now arrives through a door, not a
seed), ADR-0069 (two new causes under existing types; two base severities; one escalation
rule), ADR-0071 §3 (the `V014` one-person zero-value `ACKNOWLEDGE` list gains
`VALUE_DATE_DIFFERS`) and ADR-0072 (FX position, revenue and corridor figures are audited
reports, never metrics — reaffirmed). Builds on ADR-0064 (evidence/expectation split),
ADR-0065 (two hops to cash), ADR-0067 (expectations opened in the owning transaction) and
ADR-0078 (counterparty-keyed positions).

## Context

Phase 8 built reconciliation for single-currency counterparties: a source per (purpose,
counterparty), canonical lines, pinned rule sets, fourteen closed break types, and cash in two
evidence hops. Phase 9 adds two counterparties whose economics are new:

1. **The FX provider settles a trade in two currencies.** A cover that sold EUR and bought
   USD produces two cash movements with one cause. If reconciliation modelled a
   "cross-currency expectation", every Phase 8 proof — per-currency position, suspense, cash,
   completeness — would need a second arithmetic.
2. **The corridor provider looks like the payout provider**: executed credits, returns and
   fees, reported in files, settled net on a clearing position. Inventing a new source kind
   for it would fork every code path keyed by kind.
3. **Phase 8's inputs bind.** The Phase 8 review handed over "reconciliation never converts;
   `CURRENCY_MISMATCH` stays a break", and ADR-0069 §2's split criterion says a type is split
   only where resolution or severity differs.
4. **Rule sets were seeded in Phase 8** (`V002`'s migration-provenance exemption, frozen as
   "V002's alone"). New sources need first rule sets, and D26 refuses both a wider exemption
   and silent seeding.
5. **Identical provider references across providers must never cross.** The corridor reuses
   reference kinds the merchant payout source also uses (`PAYOUT_PROVIDER_REF`); a reference
   colliding across source families must never be typed as the other family's operation.

## Decision

1. **Sources and formats.** `SourceKind` gains **`FX_PROVIDER_REPORT`** (it settles a
   position); the corridor **reuses `PAYOUT_PROVIDER_REPORT`** (D29). Formats: `SIM_FX_CSV`
   v1 and `SIM_CORRIDOR_CSV` v1, each one currency per batch, one file per provider, currency
   and value date, each with a golden file and a per-field fault test. Sources:
   `fx-sim-a.trade-report` (position `FX_PROVIDER_CLEARING`, counterparty `fx-sim-a`,
   remittance pattern `FXA-…`) and `corridor-sim-a.settlement` (`CORRIDOR_CLEARING`,
   `corridor-sim-a`, `XBA-…`); M9.8 adds the `-b` pair. `PositionProof`'s `PROVEN` list is
   derived from the composed register, no longer hard-coded.

2. **The descriptor names its counterparty and its settled currencies, and an unsettleable
   currency is refused at the door.** `SettlementSourceDescriptor` gains
   `Optional<String> settledCounterparty`, present exactly when the settled purpose is
   counterparty-owned, and with it the counterparty's settled currencies (all five for
   `fx-sim-a`; `{USD, JPY, BHD}` for `corridor-sim-a`). `SettlementSources.of` refuses two
   sources on one (purpose, counterparty) — `INV-SET-05` restated per counterparty
   (ADR-0078). A parsed batch in a currency its counterparty does not settle (no clearing
   account exists for it) is rejected and retained with the new
   `RejectionCode.CURRENCY_NOT_SETTLED`: never accepted, never posted, loud. Existing
   descriptors carry no counterparty and behave exactly as before.

3. **FX legs are single-currency expectations, keyed by the cover reference.** The cover
   entry opens `FX_SELL_LEG` (OUTBOUND) and `FX_BUY_LEG` (INBOUND) on
   `FX_PROVIDER_CLEARING(provider)`, each keyed `COVER_REF` = `Tn` (`FX_TRADE_REF` is a
   stored alias for the trace, never a matching key). Every Phase 8 proof then covers the FX
   provider per (counterparty, currency) **unchanged in principle**. Line types `FX_SOLD`,
   `FX_BOUGHT`, `FX_FEE` arrive contiguous at the enum's end (`EnumSet.range` holds); the
   corridor reuses `PAYOUT_EXECUTED`, `PAYOUT_RETURNED` and `PAYOUT_FEE`. The cross-border
   completion opens `CROSSBORDER_PAYOUT` (OUTBOUND) on `CORRIDOR_CLEARING(rail)`, keyed
   `END_TO_END_REF` = `E` (alias `PAYOUT_PROVIDER_REF`); a return opens `CROSSBORDER_RETURN`
   (INBOUND) with **no key of its own — operation-anchored**, `UNIQUE (kind, operation_ref)`,
   the ADR-0067 §5 / ADR-0073 precedent.

4. **Every operation key resolves within the item's own source family.**
   `JdbcInternalReferenceLookup` reads covers in flight (a `COVER_REF` naming a dispatched or
   unknown attempt is `MISSING_INTERNAL`, else `UNKNOWN_EXTERNAL`) and outbound-credit
   claims — and resolves `END_TO_END_REF` and `PAYOUT_PROVIDER_REF` against outbound credits
   **only for a corridor source**, and against attempts, withdrawals and merchant payouts
   only for their own sources, through the existing `RailOfSource`. `WaitingPayoutReturns` is
   scoped the same way: the corridor return worker is handed only the sources settling
   `CORRIDOR_CLEARING`, the merchant `PayoutReturnSweep` only those settling
   `PAYOUT_CLEARING`, both read off the compiled register. Identical provider references in
   the two source families can never credit the wrong party — tested with deliberately
   colliding references in both sources.

5. **Provider fees are judged against a schedule in every currency they can arrive in, and
   `FX_FEE` becomes a priced fee line.** `FeeCheck` prices an absent schedule at zero, so a
   missing row would turn every genuine fee into a `FEE_MISMATCH`. Therefore: the FX source's
   v1 carries `FX_FEE` 0 + 0 in **all five** currencies (the simulated FX provider earns its
   spread and bills nothing, so any reported FX fee is a `FEE_MISMATCH` — O7); the corridor's
   v1 carries `PAYOUT_FEE` 0 + USD 120 / JPY 180 / BHD 450 in its three settled currencies;
   and the four existing sources get **v2 successors** carrying O6's JPY/BHD thresholds, fee
   schedules and tolerances through the existing four-eyes successor door — earlier decisions
   replay `IDENTICAL` under their pinned v1. Reconciliation's migration makes `FX_FEE` join
   `RuleSetProposal.FEE_LINE_TYPES` and the `provider_fee_line_type` `CHECK`. (The
   transition separately repairs `FeeCheck`'s cross-currency throw as a Phase 8 defect,
   before the boundary.)

6. **No migration seeds a rule set: the first version arrives through a door** (D26, amending
   ADR-0068's bootstrap). `RuleSetAdministration` gains a version-1 path, four-eyes: a
   proposal is admitted when the source has no `ACTIVE` version, activation retires nothing,
   and `V012`'s insert trigger and four-eyes `CHECK` stand unchanged. A batch from a source
   with no active rule set gets a typed **`RuleSetMissing`** refusal: the file waits `PARSED`
   with backoff, and `finapp.reconciliation.rule_set.missing{source}` reads 1 and alerts,
   instead of retrying without end. The FX v1: legs `ONE_TO_ONE` keyed `COVER_REF`, grace
   24 h, lag 2 d, `SETTLEMENT_DATE_DAYS` 2, the `FX_FEE` rule of cardinality `CHECK`
   (original by `ORIGINAL_REF` = `Tn`), no fee tolerance, thresholds in five currencies. The
   corridor v1: payout `ONE_TO_ONE` (keys `END_TO_END_REF`, then `PAYOUT_PROVIDER_REF`),
   returns operation-anchored with grace 72 h, the `PAYOUT_FEE` rule, no fee tolerance, lag
   2 d, thresholds in three currencies.

7. **Two new causes, no new break types, and reconciliation never converts** (D29, amending
   ADR-0069 by its own §2 criterion — resolution and severity, not provenance, split types):
   - **`FX_LEG_DIFFERS`** under `AMOUNT_MISMATCH`: a leg settled ≠ the cover's executed leg,
     selected by the expectation's kind in `Matching.differenceCause` (the
     `REMITTANCE_DIFFERS` precedent) — `FX_SELL_LEG` and `FX_BUY_LEG` name it, every other
     kind keeps `AMOUNT_DIFFERS`. Its resolution kinds are `AMOUNT_MISMATCH`'s. A provider
     breaching its own confirmation is a counterparty shortfall
     (`RECONCILIATION_LOSSES`/`GAINS`), never market FX result.
   - **`VALUE_DATE_DIFFERS`** under `TIMING_DIFFERENCE` (value 0): the line's value date
     differs from the cover's contractual `expected_by` beyond the source's
     `SETTLEMENT_DATE_DAYS`. As a timing detector's cause it joins the one-person zero-value
     `ACKNOWLEDGE` list (`V014`'s list restated — ADR-0071 §3 amended). Precedence, one break
     per decision: an open `MISSING_EXTERNAL` on the leg already states the timing; otherwise
     an FX leg kind names `VALUE_DATE_DIFFERS`; otherwise `CYCLE_MISMATCH`, else
     `LATE_MATCH`, as today.
   - **A spread difference is not a reconciliation break.** No external evidence states our
     spread; `FxPlanVerification` proves it, and a divergence is our defect (CRITICAL, a
     verdict gauge), while an off-plan cover is posted as confirmed, flagged, counted and
     reported (ADR-0077).
   - **An unexpected currency** stays `CURRENCY_MISMATCH` (never converted, never a gain)
     when the source settles the currency but the candidate expectation is in another;
     a currency the counterparty does not settle at all never gets that far
     (`CURRENCY_NOT_SETTLED`, point 2).
   - **A return that cannot apply** is `REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)`, and a
     `FAILED` credit's return `TERMINAL_STATE_CONTRADICTED` at once (ADR-0073's precedent,
     ADR-0079); a late execution after we concluded `FAILED` parks as `UNKNOWN_EXTERNAL`.

8. **Principal risk is loud: leg severities, and a paired-leg escalation.** Base severity is
   **HIGH** for `MISSING_EXTERNAL` on `FX_SELL_LEG`, `FX_BUY_LEG` and `CROSSBORDER_PAYOUT`
   (the `MERCHANT_PAYOUT` precedent). One new escalation rule in `ReconciliationSweep`'s
   escalation leg: an open `MISSING_EXTERNAL` on an FX leg whose **paired leg** (the same
   cover attempt's other leg, by `COVER_REF`) is allocated is raised to **CRITICAL** — one
   side of the exchange settled and the other did not — under the source's namespace-4
   advisory, by the existing expected-value severity step, with a `SEVERITY_ESCALATED`
   `break_event` whose detail reads `PAIRED_LEG_ALLOCATED`. Ten sweepers write one event; no
   migration is needed (`V004`'s `break_event` already admits it).

9. **Proofs stay report-only, one `REPEATABLE READ` snapshot, `Money` folds, never
   repairing.** The existing position, suspense, cash and completeness proofs cover the two
   new positions per (counterparty, currency) over five currencies. The FX books proof and
   `FxPlanVerification` are `fx`'s own (ADR-0076) and feed only verdict gauges; FX amounts
   reach operators as audited reports (ADR-0072 reaffirmed).

## Alternatives Considered

### A cross-currency expectation holding both legs of a trade
Pros:
- One row per economic event; the pairing is structural.

Cons:
- Every Phase 8 proof is per currency; a two-currency row breaks position, cash and
  completeness arithmetic or forks them.
- Matching, suspense and severity are all single-currency; the pairing is already held by
  `COVER_REF`, and the escalation rule (point 8) makes the pairing loud where it matters.

Two single-currency legs, paired by reference (point 3).

### A new `CORRIDOR_PROVIDER_REPORT` source kind
Pros:
- The corridor is nominally distinct from the merchant payout provider.

Cons:
- `SettlementSources` keys by code and position, not by kind; the kind exists to pick
  parsing and proof behaviour, which are identical to the payout provider's.
- Every `switch` on kind grows a case that duplicates `PAYOUT_PROVIDER_REPORT`'s.

The corridor reuses `PAYOUT_PROVIDER_REPORT` (D29); scoping is by settled position, not kind
(point 4).

### A fifteenth break type for FX discrepancies
Pros:
- "FX break" is readable on a dashboard.

Cons:
- ADR-0069 §2 splits a type only where resolution or severity differs; an FX leg difference
  resolves exactly like `AMOUNT_MISMATCH` and a value-date slip exactly like
  `TIMING_DIFFERENCE`.
- A new type needs its per-type resolution table row, severity rules and tests for no new
  behaviour.

New causes under existing types (point 7).

### Seed the new sources' rule sets by migration, widening V012's exemption
Pros:
- The sources work the moment the migration lands, no operator ceremony.

Cons:
- Phase 8 froze migration provenance as "V002's alone", and D26 refuses to widen it: a seeded
  rule set is an unaudited pricing of fees and tolerances nobody approved.
- The failure mode without a rule set must be loud anyway (a source could be declared before
  its rules in any design).

A four-eyes version-1 door, and a typed `RuleSetMissing` refusal that alerts (point 6).

### Let reconciliation convert a mismatched currency at the day's rate
Pros:
- Fewer `CURRENCY_MISMATCH` breaks to resolve.

Cons:
- Reconciliation would create FX exposure with no quote, no cover and no owner — the exact
  thing `INV-REC-08`'s no-amount-tolerance rule exists to prevent, one level up.
- Phase 8's input ("reconciliation never converts") was handed over explicitly.

Never (D29).

## Consequences

Positive:
- Both new counterparties reconcile with Phase 8's machinery, proofs and vocabulary; the
  only new moving parts are two causes, one escalation rule and a bootstrap door.
- Principal risk — one leg settled, the other missing — is CRITICAL within one sweep, not a
  report finding.
- No fee line can park for want of a schedule row, in any currency a source can settle.
- A forged or misrouted provider reference cannot cross source families.

Negative:
- Operators must activate six rule-set versions (the two v1s plus the four v2 successors)
  before Phase 9 traffic reconciles: a runbook entry, with the
  `rule_set.missing` alert as the backstop.
- The corridor's reuse of `PAYOUT_PROVIDER_REPORT` means dashboards distinguish the two by
  source, not kind.
- `CURRENCY_NOT_SETTLED` rejects a whole batch: a provider that misfiles one currency delays
  its day's file until redelivery (deliberate — a partial accept would hide the defect).

Operational impact: `finapp.reconciliation.rule_set.missing{source}` (alerting), the existing
source meters on the new sources, the paired-leg escalation visible in break meters by
severity; FX and corridor amounts in audited reports only.
Security impact: files are screened, authenticated and retained under ADR-0066 unchanged;
the new sources' report keys join the confined-credential regime; no new door.
Financial impact: `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` join `reconciledPositions()`
(ADR-0078), so they take no free adjustment and every line on them is explained at every
commit; `FX_FEE` is priced, so a billing provider meets a schedule, not silence.

## Invariants / Constraints

`INV-SET-01` (nothing final before settlement; settlement is never a payment state),
`INV-SET-05` (one source per (purpose, counterparty) — restated), `INV-SET-07` (evidence
authenticated before effect), `INV-REC-05`/`-06` (positions explained; expectations opened in
the owning transaction), `INV-REC-07`/`-08` (the comparison in one schema; no amount
tolerance), `INV-REC-09` (parked value owned by its break), `INV-AUD-04` (every rule-set
activation names two persons; migration provenance stays V002's alone), `INV-FX-06` (the FX
books proof is `fx`'s, fed by reconciliation's positions), `INV-HIST-02` (rejected files
retained), `INV-PAY-03` (provider vocabulary confined to format adapters).

## Follow-up

- *(`P9-TSK-013`, built 2026-10-05: `differenceCause` names `FX_LEG_DIFFERS` and the timing verdict `VALUE_DATE_DIFFERS` for the cover-leg kinds, after the open-`MISSING_EXTERNAL` rule; a cover leg is expected on the cover entry's value date - the provider's confirmed T+2 - rather than posting date plus lag; the paired leg is found by the same operation and the other kind, equivalent to `COVER_REF` since a cover has one execution.)*
- `P9-TSK-011` builds the FX source, vocabulary and vendor rows; `-013` the FX legs to cash
  and the proofs' coverage; `-014` the corridor source, the scoped lookup and waiting-return
  reader; `-022` corridor settlement to cash; `-023` the return path's reconciliation half;
  `P9-TSK-003` the v2 successors' currency rows (O6); `P9-TST-001` storms all of it.
- The transition repairs `FeeCheck`'s cross-currency throw as a Phase 8 defect, before the
  boundary, with its own probe and MUTATION_TESTING row.
- The transition annotates ADR-0068, ADR-0069, ADR-0071 §3 and ADR-0072 with these
  amendments, each with dated provenance.
- The rematch clock-skew debt (Phase 15) is inherited by the new sources, recorded,
  unchanged.
- The Phase 9 review (`P9-DOC-001`) reads this ADR against the code before accepting it.
