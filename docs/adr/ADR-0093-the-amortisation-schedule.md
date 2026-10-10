# ADR-0093 — The amortisation schedule: a deterministic projection of the agreement, billed from actual accrual

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Shared Kernel
Supersedes: nothing. Applies ADR-0003 (integer minor units), the no-floating-point rule
(`INV-MON-01`, `NoFloatingPointMoneyRulesTest` extended to `lending`) and ADR-0088 §3's discipline
(exact decimal, rounding declared and done once, the formula the engine's) to the instalment loan.
Rests on `PHASE_11_PLAN.md` §12.2, §12.3, §13.1, §13.3 and `INV-LND-03`, `INV-LND-09`.

## Context

1. **The schedule is what the customer is shown and what the platform promises.** It must be
   identical on every instance and every JVM, years later, and its principal must sum to exactly
   what was lent — a minor unit lost or invented across ten thousand loans is a ledger difference
   nobody can explain.
2. **The annuity formula invites floating point** (`Math.pow`) and invites rounding at every step;
   either makes the instalment depend on the platform rather than the contract.
3. **Calendars are irregular.** Months have 28 to 31 days, leap years add 29 February, and a
   repayment day of 31 does not exist in most months.
4. **The plan and reality diverge.** A borrower who pays late carries principal longer and owes
   more interest than the projection showed; one who pays early owes less. If the schedule *is*
   the bill, interest is charged on principal that was not outstanding, or not charged on principal
   that was.

## Decision

1. **`SCHEDULE_ENGINE_V1` implements one family and refuses the rest**: fixed rate, monthly,
   level-payment annuity, actual-day interest. A terms set naming anything else (a variable rate,
   another frequency, a business-day convention) is refused at the terms proposal
   (`422 lending.TermsInvalid`, ADR-0092 §3). The engine is pure — `schedule(agreement, d0) →
   instalments` — with no clock, no database and no randomness.

2. **Due dates.** `d0` is the accrual start (ADR-0094 §5). `D1` is the first date whose
   day-of-month is the agreed repayment day `k` and which is at least `d0 + min_first_period_days`
   (15, a terms field); `Dj = D1 + (j−1)` months, each computed **from the intended day `k`**
   and clamped to the month's last day — so 31 January → 28 or 29 February → 31 March, never
   drifting to the 28th. No business-day adjustment (A6): calendar days only.

3. **The level instalment.** `i = r / 12` at scale 20, `HALF_EVEN`;
   `A = P · i / (1 − (1+i)^−n)`, with `(1+i)^n` computed by exact `BigDecimal.pow(n)` and one
   division at scale 20; `r = 0` gives `A = P / n`. `A` is rounded **once** to minor units, mode
   `UP`, so the final instalment is never larger than a regular one.

4. **The periods.** `days_j = Dj − D(j−1)` (with `D0 = d0`);
   `interest_j = round_HALF_EVEN(B(j−1) · r · days_j / 365)` — the exact product, one division, one
   rounding (ACT/365F, the accrual engine's convention, ADR-0094); `principal_j = A − interest_j` for
   `j < n`; `principal_n = B(n−1)`, so the final instalment absorbs every rounding residue.

5. **Conservation, asserted by the engine and by the database.** Σ `principal_j` = `P` exactly; Σ
   `amount_j` = `P` + Σ `interest_j`. The platform never negatively amortises: a terms version that
   cannot amortise at its declared bounds is refused at the terms proposal door
   (`422 lending.TermsNotAmortising`, ADR-0092 §3); if a specific offer still yields a non-positive
   principal portion in any period (a long first period at a high rate), the origination step makes
   no offer and closes the application `CLOSED_UNDECIDED` with reason `TERMS_NOT_AMORTISING`.

6. **The projection is not the bill.** Billing (ADR-0094 §6) bills the interest **actually
   accrued** over the period — on actual principal outstanding, so a late payment raises it — and
   principal due = `max(0, A − interest due)`; the final instalment bills all remaining principal.
   On an on-time path the billed amounts equal the projection to the minor unit (property-tested).
   The schedule therefore tells the customer what to expect; the ledger records what happened.

7. **Grace delays only the late fee, never DPD** (ADR-0098).

8. **Stored once, versioned, never updated.** `repayment_schedule` (`UNIQUE (loan_id,
   schedule_version)`, naming the agreement version and the engine version) and `loan_instalment`
   (`UNIQUE (schedule_id, sequence)`: due date, projected interest, principal, amount) are
   `INSERT`-only. The schedule is generated **at the accrual start**, in that transaction: at
   disbursement for path W, at the payout's terminal outcome for path X (A30, ADR-0097).

9. **Recalculation is a new version.** After an approved contractual change — an accepted
   amendment (ADR-0099, scenario 8) or a partial prepayment (`P11-TSK-028`, cut candidate) — the
   engine runs again from agreement v n+1 with `P` = principal outstanding derived from postings at
   `effective_from`, the remaining or new term, and `D1` the next due date. Billed instalments of
   the old version are kept; unbilled ones are superseded by the new version — nothing is updated
   or deleted.

10. **The offer's illustrative schedule.** The offer shows the schedule computed with `d0` = today,
    labelled illustrative, with total interest and total payable (A5) — no APR (L8). The real
    schedule is computed at the accrual start, so its dates and interest may differ by the days
    between offer and accrual start; the agreement's terms do not.

11. **Every figure has a test that knows its answer.** Hermetic property tests: Σ principal = P;
    Σ amount = P + Σ interest; monotone in rate; `r = 0`; every term 6–60; every repayment day
    1–28 with the clamp; disbursement on every day of a leap and a non-leap year; golden worked
    examples checked by hand. The battery (`P11-TST-002`): ≥ 10,000 generated loans replayed
    `IDENTICAL` in two JVMs, a perturbed byte flipping the verdict.

## Alternatives Considered

### Floating-point annuity, rounded at display
Pros:
- One line of code.

Cons:
- `INV-MON-01`; the instalment would depend on the JVM and library; replay could diverge.

Refused (point 3).

### Round the instalment `HALF_EVEN` and let the final instalment adjust either way
Pros:
- The instalment is the "nearest" amount.

Cons:
- The final instalment may exceed the regular one — a surprise the customer did not agree to, and
  an affordability figure the decision did not assess.

Refused: `UP`, the final instalment never larger.

### 30/360 or monthly-rate interest per period
Pros:
- Every regular period's interest is identical; simpler tables.

Cons:
- Disagrees with the daily accrual (ADR-0094); the projection and the bill would differ even on
  an on-time path, and the borrower would pay interest for days that did not exist.

Refused: actual days, ACT/365F, as the accrual.

### The schedule as the bill (precomputed interest)
Pros:
- The bill is known at acceptance; no daily accrual needed for billing.

Cons:
- Interest charged on principal not outstanding (late or early payment); an early settlement then
  needs a rebate formula — the source of most consumer-credit disputes. With billing from actual
  accrual, the early-settlement rebate is zero by construction (tested).

Refused (point 6).

### Business-day adjustment of due dates
Pros:
- No due date falls on a weekend.

Cons:
- Needs a holiday calendar the platform does not own; a wallet repayment is internal and works
  every day.

Refused (A6); a calendar would be a terms field and an engine version.

### Recalculate by updating the schedule rows
Pros:
- One schedule per loan.

Cons:
- Destroys what the customer was shown and what was billed against; the explanation chain breaks.

Refused (point 9).

## Consequences

Positive:
- Exact, deterministic, replayable schedules; principal conserved to the minor unit.
- Late and early payments are charged fairly without rebate arithmetic.

Negative:
- The billed amount can differ from the shown instalment when payments are late — explained on
  the statement of the instalment, but a support question.
- Scale-20 `BigDecimal.pow(n)` per schedule — trivial at Phase 11's volumes, measured in the battery.

Operational impact: none of its own; schedule-versus-actual drift is an audited report, never a
metric (ADR-0072).
Security impact: schedule amounts `RESTRICTED-FINANCIAL`.
Financial impact: fixes the promised repayment profile; billing, not the schedule, posts.

## Invariants / Constraints

`INV-LND-03` (a schedule conserves principal exactly), `INV-LND-09` (exact arithmetic, declared
conventions), `INV-MON-01`…`02`, ADR-0003, ADR-0088 §3.

## Follow-up

- Built by the M11.2 engine task for the schedule (`P11-TSK-006`) as a pure engine with its
  property tests; persisted and generated at the accrual start by `P11-TSK-014` (path W) and
  `P11-TSK-015` (path X); recalculated by `P11-TSK-027` and, if not cut, `P11-TSK-028`.
- Probe (recorded in `MUTATION_TESTING.md` §2 by the building task): round the instalment
  `HALF_EVEN` → the "final ≤ regular" property red.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
