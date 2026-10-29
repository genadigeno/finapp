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

## Follow-up

C7 B with qualified review; a reporting currency other than the functional one, if a jurisdiction requires it.
