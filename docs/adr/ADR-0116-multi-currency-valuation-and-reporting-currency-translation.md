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

`P14-TSK-015` (2026-11-02), decisions 1, 3 and 4; no migration. **How a run asks**: by naming its period's approved set in
`RunParameters.valuationRateSet`, where the definition `takesValuationRateSet()`. That member was already in the canonical
parameters, the hash, `report_run_key`, the column, the foreign key and the content's header, so the translated and the plain
run are two questions and two runs of one definition version. A variant code per report was rejected: it doubles `ReportKind`
and the registry for every later definition to re-express what the key already holds. Decision 1's "always shown" is read as
every run that asks; the close's plain `TRIAL_BALANCE`, `RunParameters.none()`, never needs a set. **The refusal**:
`ReportRuns.claim`, under the period row `FOR SHARE` (an approval takes it `FOR UPDATE`, approved is terminal): anything but the
period's approved set - unknown, proposed, rejected, another period's, or none approved - is
`409 accounting.ValuationSetNotApproved`, nothing written, FINAL or PROVISIONAL; the generation re-judges in its snapshot
(`Translation.Rates.pinnedBy`, a run failed `DEFINITION_FAILED`). No trigger: the key holds existence, the two ranks approval, and
only `ReportRuns` writes the row. **PROVISIONAL**: an open period normally has no approved set and so no translated run; a
period unlocked after an approval keeps its set, whose rates are the period end's, and translates at it, labelled by basis and
cut. An earlier period's set is not used: which indicative rate to use is a policy this ADR does not make (owner item).
**The arithmetic** (`reporting.Translation`, pure): `amount / rate` by a correctly rounded exact division, `HALF_EVEN` to two
places, the reporting minor unit a literal, never a JDK's table (a guard holds it for report code); `EUR` at a stated identity;
per currency `translated parts + translation difference = translated total`, the total translated once from the exact original
total, so a balanced currency's translated debits equal its credits through the difference lines. Every column of a run is at
its one set: a translated row need not cross-foot by a cent, and a translated opening differs from the previous period's translated
closing when rates moved. That is decision 4's unrealised effect: not posted, and, as built, named by no member of the content, so
"disclosed" holds only in that a reader can find it by comparing two runs; the cross-foot caveat is likewise in these documents, not
the content. Both are `-016`'s C7 note to settle. **A currency the set does not value, with a figure that is not zero**: never
dropped, never at one - originals kept, translated figures `null`, named in `translation.untranslated`, `translation.complete`
false, the report-wide totals withheld, the run completed; the unmapped row's shape, since a failed run would carry no reason,
fail identically at every revision and, with no supersession edge, never be cured (whether a FINAL run with a real hole should be
issued at all is the owner's). **A zero needs no rate**: a currency with no rate whose every figure, in every column, is zero
translates to zero under a basis of its own (`ZERO_NO_RATE`, `rate` null - no rate of one stated or implied), stays out of
`untranslated`, keeps `complete` true and withholds nothing: a retired currency's rows persist from inception, so otherwise every
later period would read incomplete and `-016` refuse for good. **A currency the trial balance holds and no row carries** - a read
that lost its rows, the ledger's windows still holding it - is a hole whatever the set values (`NO_PARTS`, `original` and `rate`
null), never `complete` over a partial total. **The trial balance**: plain content byte-identical - a golden whose provenance is
shown by running it on master `a184bed2`'s main code in a throwaway worktree - version 1 unchanged: nothing it ever issued changes
and a run naming a set was refused until now; translation is outside `passes`, the verdict the same bytes at the same path. **The
port**:
`ValuationRateReads`, `app`'s `PinnedValuationRates`, because `ReportingNeverWritesTest` follows accounting's implementations of a
port a report class holds. `accounting.ReportRunCompleted` and the summaries of `accounting.ReportGenerated`, `ReportRead` and
`ReportRegenerated` name the set on a translated run only, so the two runs of one definition stay distinguishable. **Supersession
does not change this design**: it would mint a new
set id, a new parameters hash, a new question and a new run; the one touch point is `pinnedBy`, which would have to admit a
superseded set for the regeneration of a run that pinned it.

**Ruled by the owner, 2026-11-03.** A FINAL run with a missing rate stays issued, with the gap disclosed, and a statement still refuses it. Indicative translation of an open period is left out. **Still open: the translated
body's shape**, for the owner to look at before the first translated FINAL, because a later change means a new version.

## Follow-up

C7 B with qualified review; a reporting currency other than the functional one, if a jurisdiction requires it.
Superseding an approved valuation rate set (an `APPROVED → SUPERSEDED` edge under four eyes) if a wrong approved set must
ever be replaced (`P14-TSK-014`'s owner item). *(`P14-TSK-011`, 2026-11-02: `VALUATION_SET_APPROVED` reads `approvedFor` afresh
per attempt and pins nothing, so either answer fits; it names the period's sets, so a proposal can be found - `ValuationRateSets.ofPeriod`,
`P14-TSK-014`'s review, M-5.)*
