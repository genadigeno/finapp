# ADR-0116 — Multi-currency valuation: a functional currency, valuation rate sets separate from execution, translation at report time

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Accounting / General Ledger · Reporting · FX
Supersedes: nothing. Takes up what ADR-0076 deferred ("no revaluation, no functional currency, no unrealised P&L —
Phase 14", D30) and `DECISIONS.md` §Deferred's row for it. Rests on `PHASE_14_PLAN.md` §13.4 and `INV-ACC-11`,
`INV-FX-05`, `INV-MON-03`, `-04`.

## Context

The journal holds five currencies at three scales; every line is single-currency and a conversion is two legs meeting on
`FX_POSITION`, zero at rest; realised results (`FX_REALISED_*`), spread and rounding residual are already posted.
Statements need one reporting currency; customer execution rates must never be re-used or altered for valuation.

## Decision

1. **Functional = reporting currency = EUR** (owner decision C1). Transaction-currency columns are always shown beside
   the translation.
2. **A valuation rate set** is captured when a period locks from `fx.rate_snapshot` reference rates through the
   `ValuationRates` port — per pair, the last observation at or before the period end within a staleness bound, with
   source, `observed_at` and snapshot id — approved four-eyes, immutable, pinned by every FINAL run. It never reads a
   customer quote, an executed trade or a cover rate.
3. **Translation is a reporting act**: each line group translated at the pinned rate, `HALF_EVEN` to the reporting minor
   unit; the difference between the sum of translated parts and the translated total is an explicit **translation
   difference** line. Nothing is posted; no journal line changes (C7 A).
4. **Realised vs unrealised**: realised results are reported from their posted purposes; unrealised revaluation is not
   posted in Phase 14 and is disclosed as a translation effect. Posting revaluation (C7 B) is a later, qualified decision
   that would add purposes and an auto-reversing rule, without rewriting any closed period.

## Alternatives Considered

### Period-end revaluation postings (C7 B)
Pros: the ledger carries the functional-currency view. Cons: an accounting policy (monetary vs non-monetary items) the
platform must not invent; reversal machinery each period. Deferred.

### Translate at executed or quote rates
Pros: no new capture. Cons: conflates valuation with customer execution; breaks provenance. Rejected.

## Consequences

Positive: originals and provenance never lost; statements in one currency. Negative: translation differences visible
(by design). Operational impact: a rate set approval in each close. Security impact: four-eyes on the set. Financial
impact: none on balances.

## Invariants / Constraints

`INV-ACC-11`; `INV-FX-05`; `INV-MON-02`…`-04`.

## As built

`P14-TSK-014` (2026-10-31), decision 2: accounting `V005` (`valuation_rate_set` - born `PROPOSED` with all its rates, the
period `LOCKED`, `as_of` the first instant after the period's end (UTC), `max_age_hours`, persons and four-eyes `CHECK`s,
the partial unique `valuation_rate_set_one_approved_per_period`, the edge trigger, a deferred whole-set check;
`valuation_rate` - `EUR`-based, `NUMERIC(20,10)`, source, bare snapshot id, `observed_at`, `received_at`, born only with
its `PROPOSED` set inside its window, `INSERT`-only), `ValuationRateSets` over `JdbcValuationRateSetStore`, and the
`ValuationRates` port's `app` adapter `FxReferenceValuationRates`, which reads `fx.rate_snapshot` alone for the declared
reference source - never a quote, a trade's executed or customer rate, a cover or the provider's evidence (`INV-FX-05`).
**Which period**: a set is captured and approved only while its period is `LOCKED` ("captured when a period locks" read
as "once it is locked": an operator act after the lock, so a stale reference never blocks the cutoff); a rejection is
allowed in any state; an unlock does not void an approved set - its rates are the period end's. **The window**: per valued
currency (the ledger's postable currencies but EUR, each a pair the reference publishes from EUR, so nothing is inverted)
the last observation strictly before `as_of` and no older than the staleness bound
(`finapp.accounting.valuation.max-age-hours`, default 96, 1 to 336 - a weekend after a bank holiday; owner item), judged
against `as_of`, never the capture's clock; one stale or missing pair refuses the whole capture (`422 accounting.ValuationRateStale`). **Four eyes**: capture
`ACCOUNTING_CLOSE_PROPOSE`, approval, rejection and the review read `ACCOUNTING_CLOSE_APPROVE` - the close's existing
propose/approve pair, no new permission; approver ≠ capturer by the domain and the `CHECK`. **One approved per period**:
every approval of a period serialised on the period row (`FOR UPDATE`), the set row after it, the conditional edge; the
partial unique the backstop for every writer (`409 accounting.ValuationSetStale`). Beyond the decision text: no
supersession of an approved set (owner item); no history table - the set row's frozen capture and decision columns and
the audit trail are the history. **Pinned by runs**: at its integration with `P14-TSK-008` the same migration keys a report
run's pin - `report_run_valuation_rate_set_fk`, `report_run.valuation_rate_set_id → valuation_rate_set (id)` - so a run
can pin only a set that exists. Status stays `Proposed` until the phase's exit review.

## Follow-up

C7 B with qualified review; a reporting currency other than the functional one, if a jurisdiction requires it.
Superseding an approved valuation rate set (an `APPROVED → SUPERSEDED` edge under four eyes) if a wrong approved set must
ever be replaced (`P14-TSK-014`'s owner item).
