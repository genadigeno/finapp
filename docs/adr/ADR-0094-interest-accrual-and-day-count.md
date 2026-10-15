# ADR-0094 — Interest accrual and day count: ACT/365F simple daily interest, born once per account and date, rounded once per period, on the database clock in a declared zone

Status: Accepted (2026-10-15, `P11-DOC-001` — read against the code and corrected first)
Date: 2026-10-10
Phase: 11
Context: Lending · Ledger · Platform
Supersedes: nothing. Resolves `docs/adr/README.md`'s anticipated Phase 11 decision "Interest
accrual and day-count convention". Applies ADR-0003, `INV-MON-01`, ADR-0039 (row locks,
conditionals, uniques), the database-clock discipline of `X-TSK-013` and the Phase 9 exit review
(`INV-LND-10`), and ADR-0005 (no correctness through events). Rests on `PHASE_11_PLAN.md` §2.3 (L5,
L8), §2.4 (A2, A4, A10, A15, A22, A30), §7.1 (T-svc), §7.5, §12.3, §12.6 and `INV-LND-02`, `-09`,
`-10`.

## Context

1. **Interest is earned every day and owed only at due dates.** The platform must recognise
   income as it is earned, bill it when it falls due, and be able to say on any day how much has
   accrued — without double-counting a day, skipping one, or inventing one.
2. **A daily job is a distributed-systems problem.** N instances run the sweep; it crashes
   mid-run; it runs twice; instance clocks disagree by seconds around midnight; a day must be
   accrued exactly once and never before it has ended.
3. **Daily rounding leaks.** Rounding each day's interest to cents independently accumulates up to
   half a cent a day of error — over a 60-month loan, a figure the replay and the schedule disagree
   on.
4. **Conventions decide money.** ACT/365F versus ACT/360 versus 30/360, simple versus compound,
   which zone defines "a day", whether a posting may be back-valued — each changes the bill.
   L5 chose ACT/365F simple daily interest rounded once per period.

## Decision

1. **The four words, kept apart.** *Accrued* — earned day by day, not yet due (`interest_accrual`
   rows; `LOAN_INTEREST_ACCRUED`). *Due* — became payable at a due date or a statement
   (`LOAN_INTEREST_DUE`, ADR-0096). *Paid* — settled by an allocation (ADR-0095). *Outstanding* —
   derived: `LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE + LOAN_INTEREST_ACCRUED + LOAN_INTEREST_DUE +
   LOAN_FEES_DUE − LOAN_CREDIT_BALANCE`.

2. **`ACCRUAL_ENGINE_V1`: ACT/365F simple daily interest** (L5, A22). For each date `d` from the
   accrual start while principal outstanding at the end of `d` is positive:
   `base(d) = LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE` over journal lines with `value_date ≤ d`, read
   under L3 — **never interest or fees** (A4: no compounding, no interest on interest). The divisor
   is 365 every day; 29 February accrues one ordinary day. `ACT_365F` is the only convention V1
   implements; another is a new engine version and a terms value (ADR-0092).

3. **Cumulative rounding: one rounding per period.** Within a period — an instalment period
   (loan) or a statement cycle (line) — `cum_exact(d) = Σ base(x) · r / 365` over the period's
   dates up to `d` (scale 20, the division once per day, fixed by the engine version), and
   `posted(d) = round_HALF_EVEN(cum_exact(d)) − round_HALF_EVEN(cum_exact(d−1))`. A period's postings
   therefore sum to its exact interest rounded **once**; no residue accumulates across days. A day
   whose `posted(d)` is zero writes the row (with `posted_minor = 0`) and no journal entry.

4. **Born once per (account, date).** `interest_accrual` (`INSERT` only) holds `loan_id`,
   `accrual_date`, `base_minor`, the exact cumulative figure, `posted_minor`, the agreement and
   engine versions and the entry id, under `UNIQUE (loan_id, accrual_date)`. The posting is
   `DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME` with ledger idempotency key
   `lending.accrual:<loan>:<date>` and `value_date = posting_date = d`. Three arbiters — the
   unique, the ledger key, and L3 — so ten sweepers, a duplicate tick or a restart mid-run accrue a
   date once (scenario 4).

5. **The accrual start.** Path W: the disbursement entry's value date (ADR-0097). Path X: the
   payout's terminal outcome instant, in the pinned zone (A30) — the platform, not the borrower,
   bears provider ambiguity. A line: each draw's value date adds to the base from that date. The
   start is recorded once (`accrual_start`, `UNIQUE (loan_id)`), with the schedule (ADR-0093).

6. **Billing reads the accruals, never recomputes them.** An instalment is billed only after every
   accrual through `Dj − 1` exists: interest due = Σ the period's `posted_minor`; one entry
   `DR LOAN_INTEREST_DUE / CR LOAN_INTEREST_ACCRUED` and `DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL`
   (`instalment_billing UNIQUE (instalment_id)`, key `lending.billing:<instalment>`). A line's
   statement does the same for the cycle (ADR-0100).

7. **When is `d` accruable? When the database says it has ended.** In T-svc, after taking L3, the
   step reads `statement_timestamp()` and accrues only dates whose end, in the agreement's
   `servicing_zone` (`UTC` by default, A2), lies before it — never an instance clock
   (`INV-LND-10`). Catch-up runs oldest first; the sweep's permit `next_servicing_at` is the start
   of the next business date in the pinned zone. ±5 s skew across midnight accrues a date once and
   never early (`InterestAccrualDatabaseTest`).

8. **No back-valuing** (A10). Every lending posting's value date is the business date of the
   database clock read under the account lock (an accrual: its own date). A late-arriving fact is
   applied on the day it is applied; the interest gap after a reversed repayment is not recharged
   retroactively. Restated as a positive rule: no posting ever re-opens a date already accrued.

9. **No compounding, no penalty or default interest, no interest on interest** (A4, L8). Late
   payment raises interest only because principal stays outstanding longer.

10. **A payoff accrues for itself.** The payoff execution (`P11-TSK-026`) accrues every missing
    date through `G − 1` under L3 with the same uniques and keys, then bills, then repays — so a
    payoff and the sweep never double-accrue (scenario 10's serialisation, ADR-0098).

11. **Corrections are reserved, not built.** A wrong accrual (an engine defect) is never edited: a
    new engine version and an explicit correction run posting deltas under
    `lending.accrual-correction:<loan>:<date>:<engine>`. Phase 11 has nothing to correct; the key
    space is reserved so nothing collides. An interest waiver is a servicing correction
    (ADR-0099), not an accrual change.

12. **No per-day event.** `InterestAccrued` (listed in `DELIVERY_PLAN.md` §8) is refused: one event
    per account per day that nobody consumes. The accrual is the row and the entry; the billing or
    statement event carries what became due.

## Alternatives Considered

### Accrue monthly, at billing only
Pros:
- One posting per period; no daily sweep.

Cons:
- Income is not recognised as earned; a payoff mid-period needs a separate computation path; the
  accrued figure on any day is a calculation, not a record.

Refused.

### Round each day independently
Pros:
- Each row is self-contained.

Cons:
- Up to half a minor unit of error per day, accumulating per period; the bill would disagree with
  the period's exact interest and with the schedule's one rounding.

Refused: cumulative rounding (point 3).

### ACT/360 or 30/360
Pros:
- ACT/360 is common in commercial lending; 30/360 makes periods uniform.

Cons:
- ACT/360 charges more than the nominal rate over a year; 30/360 disagrees with actual days held.
  The owner chose ACT/365F (L5); another convention is an engine version.

Refused for Phase 11.

### Accrual triggered by events or by the instance's midnight
Pros:
- No sweep; immediate.

Cons:
- Events are hints and can be lost or duplicated; an instance clock ahead of the database accrues
  early. Correctness would rest on timing.

Refused: a leaderless sweep, the database clock after L3.

### A running accrued-interest column on the loan
Pros:
- One read for "accrued so far".

Cons:
- A mutable money column (ADR-0090 §2); a second authority beside `LOAN_INTEREST_ACCRUED`.

Refused.

## Consequences

Positive:
- Each date accrued exactly once under any concurrency, crash or skew; a period's interest exact to
  one rounding; income recognised daily and explainable row by row.

Negative:
- One row per account per day (an account-year is 365 rows) and up to one entry per day; the
  servicing sweep's volume grows with the book — not measured - a debt row (`CURRENT_STATE.md` §Known
  Architectural Debt), partitioning in Phase 16 if a measurement demands it. *(Read "measured in the storm,
  partitioned in Phase 16 if needed" until `P11-DOC-001`, 2026-10-15: the storm (`P11-TST-001`) proved the
  sweep correct and measured no volume - the class of its gate's finding I10.)*
- No back-valuing means a corrected repayment's interest effect is forgone, not recharged (A10) —
  the platform bears it.

Operational impact: `finapp.lending.accrual{outcome}`, `finapp.lending.accrual.lag` alerting above
one day; the servicing sweeper's enabled gauge.
Security impact: accrual amounts `RESTRICTED-FINANCIAL`; none in a log line or tag.
Financial impact: daily income recognition on the operational ledger; the GL mapping is Phase 14's.

## Invariants / Constraints

`INV-LND-02` (interest accrues once per account per date), `INV-LND-09` (exact, declared),
`INV-LND-10` (servicing time is the database's), `INV-MON-01`, `INV-IDEM-01`…`04`, ADR-0039.

## Follow-up

- Built by the M11.2 accrual engine task (`P11-TSK-007`, pure, with cumulative-rounding, 29
  February and zero-day tests) and `P11-TSK-016` (the servicing sweep, the rows, the postings, the
  skew and crash tests, scenario 4); billing by `P11-TSK-017`; the line's cycle by `P11-TSK-022`.
- Long horizons are tested with fixed-offset zones per storm cohort and pure engines for decades —
  no production clock seam (A15).
- Probes: drop the accrual unique → scenario 4 red; judge the boundary on the instance clock → the
  skew test red.
- **As built by `P11-TSK-007` (2026-10-11) - the engine.** `AccrualEngineV1` (`accrue`, `postedThrough`,
  `project`) over the values `AccrualTerms`, `AccrualPeriod` and `InterestAccrual`, refusing by the typed
  `AccrualRefused`; the `ACT_365F` arithmetic is the package-private `Act365Fixed` (one product, one
  division at scale 20 `HALF_EVEN`, one rounding), shared with `SCHEDULE_ENGINE_V1` so the two engines
  cannot disagree on a day, a year or the division. Point 3's agreement with the schedule is exact, not
  approximate: with the base at its currency's scale and the rate at ≤ 8 places, a constant-base
  period's sum of scale-20 daily figures rounds to the schedule's `interest_j` whenever the period is
  not a multiple of 73 days long - every monthly period is shorter (`AccrualMatchesSchedulePropertiesTest`,
  two-JVM identical). A date with no accrual inside a period contributes nothing; completeness is the
  sweep's (`P11-TSK-016`). Nine probes, `MUTATION_TESTING.md` §2.
- **As built by `P11-TSK-016` (2026-10-12) - the database half.** `LoanServicing` (T-svc: L3, the database clock
  after it, the accrual step, the permit) and `LoanServicingSchedule` over `lending V010` (`interest_accrual`,
  `loan.next_servicing_at`). Refinements, each for its reason: **point 4** - the row also stores its period
  (`period_start`, `period_end`) so the predecessor and a replay are read from rows, and `UNIQUE (journal_entry_id)`;
  its trigger holds every point-7 judgement for every writer (a date that has ended on the database clock in the pinned
  zone, on or after the accrual start, after every date already accrued). **Point 3** - after an instalment loan's final
  due date the periods continue monthly on the intended day (`InstalmentCalendar`), so post-maturity interest is still
  rounded per month. **Point 5** - a line accrues from its opening business date on each date with drawn principal
  (no `accrual_start` row exists for a line). **Point 7** - the claim stamps the permit to a retry cadence (15
  minutes) and a completed step to the next business date's start; at most 92 dates a step after an outage, the rest
  due at once. **Rule 2's reference** is the loan, so a re-attempted date is the same request under the same key, a
  replay refused as a defect. **Point 11** - `LoanServicing.CORRECTION_KEY_PREFIX` names the reserved key. The
  accrual-lag gauge reads instalment loans (a line's zero days write no row; its arm waits for `-021`). Two probes and
  their companions, `MUTATION_TESTING.md` §2.
- **As built by `P11-TSK-017` (2026-10-12) - point 6, billing.** `InstalmentBillings`, T-svc's step after the
  accrual under the same L3, over `lending V012`'s `instalment_billing`. Refinements: **completeness** is "`Dj` has come
  and the accrual has reached `Dj - 1`" (`Dj <= min(today, accrued-through + 1)`), so a catch-up stopped at its bound
  waits; the database half is forward - the billing's trigger holds interest due to exactly the period's accruals and
  the accrual's trigger, replaced, refuses any date inside a billed period, so billed interest stays accrued interest
  for ever. **Post-maturity**: each period of the continued calendar (point 3's refinement) in which interest accrued is
  billed at its end, interest only (`POST_MATURITY`, key `lending.billing:<loan>:<due date>`) - so a loan past maturity
  stays closable; this ADR's billing, not ADR-0098's. **One billed item per due date**: `UNIQUE (loan_id, due_date)` beside
  `UNIQUE (instalment_id)`. **Point 8 applies to billing**: rules 3 and 6 are dated the business date of their
  transaction, so a billing caught up after an outage is applied that day - and a credit balance held through the outage
  is applied that day too (the outage days accrued on the principal it would have paid; owner judgement recorded). Rule
  3's reference is the item billed, so a second attempt from the same history is a replay refused as a defect. Two
  probes and their companions, `MUTATION_TESTING.md` §2.
- **As built by `P11-TSK-026` (2026-10-13) - point 10 and the projection.** The payoff's execution accrues every
  elapsed date through `G - 1` with `LoanServicing`'s own accrual (exposed as `catchUp` - the same rows, uniques and keys, no
  per-step bound), so the sweep and the payoff accrue a date once. **The quote accrues too** (refinement): it is made on a
  caught-up account, so its projection covers exactly `[today, G - 1]` on the current base; without it a quote made before
  the day's sweep would project elapsed dates on today's base, which their dated base need not equal. `AccrualEngineV1`
  gains `project(terms, periods, last, from, through, base)` beside the original signature (which now delegates with
  `from` the date after `last`): dates between `last` and `from` that accrued nothing (no principal outstanding - a line at
  zero drawn) contribute nothing, exactly as `accrue` chains a period's next accrual onto its latest; no figure of the
  original signature changed (`AccrualEngineV1Test#projectsFromADate`). A projection spanning a billing at which a held
  credit balance is applied goes stale on `G` (rule 6 lowers the base) - stale, never wrong.
- **Acceptance.** **As built** (`P11-DOC-001`, 2026-10-15): `Proposed` at the Phase 10 → 11 transition
  (2026-10-10); the Phase 11 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.
