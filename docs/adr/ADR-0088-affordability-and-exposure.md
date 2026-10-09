# ADR-0088 — Affordability and exposure: exact decimal in one currency, rounded once at declared points, and the exposure an approval reserves judged under the party's profile lock

Status: Accepted (2026-10-09, `P10-DOC-001` — read against the code and corrected first)
Date: 2026-10-07
Phase: 10
Context: Credit · Shared Kernel · Platform
Supersedes: nothing. Applies ADR-0003 (money as integer minor units with explicit currency and
scale) and the no-floating-point rule (`INV-MON-01`, `NoFloatingPointMoneyRulesTest`) to credit
arithmetic, and ADR-0039's row-lock discipline to the per-party exposure judgement. Rests on
`PHASE_10_PLAN.md` §7, §12.3, §12.4, §12.7, §14 and `INV-CRD-09`, `INV-CRD-12`, `INV-MON-01`…`02`.

## Context

Two figures decide most credit outcomes: whether the applicant can afford the repayment, and how
much credit the applicant would owe in total. Both are money arithmetic, and both have
traps:

1. **Money arithmetic must be exact** (`CLAUDE.md` rules 1–2). An annuity formula invites
   `Math.pow` on doubles; a ratio invites a float. Either makes the outcome depend on the
   platform's floating-point behaviour, and replay (`INV-CRD-01`) on the JVM.
2. **Rounding is a decision.** Where, how often and in which mode the figures are rounded
   changes the outcome at the margin; an unstated rounding is an unreproducible one.
3. **Currency mixing is conversion by stealth.** A bureau reporting obligations in another
   currency cannot be added to EUR income without a rate — and credit owns no rate.
4. **Exposure is a cross-request quantity.** Two concurrent approvals for one party (two
   products, or a system decision beside a person's) each looking at the other's reservation as
   absent would together exceed the limit — the classic write-skew under `READ COMMITTED`.
5. **Phase 10 has no loans.** The platform's own outstanding credit is zero until Phase 11, but
   the formula must not have to change when it stops being zero.

## Decision

1. **Affordability, exactly as plan §12.3, in the product's currency.**

   ```
   income      = min(verified income, declared income)          -- verified = financial data where present
   expenditure = max(verified committed expenditure, declared expenditure)
   obligations = bureau monthly obligations
   repayment   = annuity(requested amount, term, policy.assessment_rate)   -- stress rate, not a product price
   disposable  = income − expenditure − obligations − repayment
   affordable  ⇔ disposable ≥ policy.minimum_disposable
   ```

   - The conservative choices are deliberate: the *lower* of verified and declared income, the
     *higher* of verified and declared expenditure. Where verified data is absent, the declared
     figure stands alone — and the policy reasons about the absence explicitly (ADR-0086 §6).
   - The **assessment rate is a stress rate, not a product price** — pricing credit is Phase 11's
     offer (ADR-0084 §4).
   - A revolving `CREDIT_LINE` assesses `repayment = limit × policy.minimum_payment_ratio`.

2. **Exposure, exactly as plan §12.4.**

   ```
   exposure = bureau total balance              -- external, from the snapshot
            + platform outstanding credit       -- PlatformCreditExposure port: zero in Phase 10, recorded
            + reserved exposure                 -- Σ approved amount of this party's decisions
                                                --   APPROVED, in the product's currency,
                                                --   valid_until > now (DB clock), with no
                                                --   credit_decision_consumption row
            + requested amount
   within   ⇔ exposure ≤ policy.max_exposure
   ```

   `EXPOSURE` and `EXPOSURE_HEADROOM` (`policy.max_exposure − exposure`) are the derived figures
   rules read (ADR-0086 §1). *(As built (`P10-DOC-001`, 2026-10-09): a required input absent from
   the snapshot — declared income or expenditure or the bureau's monthly obligations for
   affordability; the bureau's total balance, the outstanding credit or the reservation for
   exposure — makes the figure `Unassessable`, naming what was absent, never a zero
   (`AffordabilityAssessment`, `ExposureAssessment`). A rule reading an unassessable figure is
   `UNASSESSED` and does not trigger, and an approval resting on an unassessed rule becomes the
   policy's unavailable fallback (`REFER` or `DECLINE`, `PolicyEvaluatorV1`) — so the system
   never approves on an exposure it could not compute.)*

3. **Exact decimal, one currency, rounding declared and done once** (`INV-CRD-12`).
   - All amounts are `Money` (ADR-0003) in the product's single currency; a source in another
     currency is normalised as partial data — the attribute `ABSENT` with the recorded
     `CURRENCY_NOT_SUPPORTED` marker, an attribute value and not an error code (ADR-0085 §6).
     **No `ExchangeRate` exists in credit; nothing is ever converted.**
   - The annuity is computed in `BigDecimal` at scale 10 with `HALF_EVEN` throughout, and rounded
     **once**, to the currency's minor units with `HALF_UP`, at the end; the revolving repayment
     (`limit × minimum_payment_ratio`) takes the same single rounding point. The other terms are
     already minor units; sums and differences are exact.
   - **The formula, the scale and the rounding modes are the engine's** — part of
     `engine_version` (ADR-0086 §2), changed only by a new engine version. The rate, the minimum
     disposable, the minimum payment ratio and the maximum exposure are the policy's.
   - No `double`, no `float`, no `Math.pow` on a monetary path — held by
     `NoFloatingPointMoneyRulesTest` over the module.
   - Property tests: affordability monotone in income, amount and rate; exact on worked cases.

4. **Reserved exposure is the sum of the party's current approvals, ended by lapse or
   consumption.** A decision `APPROVED` reserves its approved amount from the moment it is
   recorded until its `valid_until` passes on the database clock (ADR-0087 §7) or it is consumed.
   **Consumption is a separate born-once fact**, `credit_decision_consumption`
   (`UNIQUE (decision_id)`) — never a column on the decision, which no role ever updates
   (`INV-CRD-02`). Phase 10 builds the lapse (a comparison with the clock, not a state) and creates
   the consumption table empty; **Phase 11's loan writes it**, nothing writes it in Phase 10.
   Reserved exposure is therefore exactly: `APPROVED`, in the product's currency (the read filters
   `d.currency`, `JdbcReservedExposure`), `valid_until > statement_timestamp()`, and no
   consumption row. A declined or referred decision reserves nothing; a referral reserves only
   when a person approves it.

5. **Exposure is judged under the party's profile row lock** (`INV-CRD-09`). The reserved
   exposure is recorded in the snapshot as `PLATFORM_RESERVED_EXPOSURE`, but the snapshot is
   frozen earlier than the decision, so the deciding transaction (ADR-0087 §5) locks the party's
   `credit_profile` row `FOR UPDATE` **first**, re-reads the reserved exposure under it, and — if
   it differs from the snapshot's — freezes a successor snapshot and re-evaluates against it. Two
   decisions for one party therefore serialise, and the second sees the first's reservation:
   concurrent approvals never together exceed the policy's exposure limit (failure scenario 15).
   A person's approval of a referral is held to the same lock and the same re-read: it is bounded
   by the evaluation's approved amount and by the exposure limit, and beyond it the act is refused
   `422 credit.ExposureLimitExceeded`, nothing recorded, and the person decides again
   (ADR-0089 §6). The profile row holds no figures; it exists to be the lock target (`INSERT`
   only). *(As built (`P10-DOC-001`, 2026-10-09), four precisions.)* **(a) The system path is
   bounded through a rule.** The evaluator (`PolicyEvaluatorV1`) judges exposure only through the
   policy's rules, so a system approval honoured `max_exposure` only if some rule read `EXPOSURE`
   or `EXPOSURE_HEADROOM`: a four-eyes-activated policy without one would have approved past its
   own limit. The seeded v1 policies and every test policy carry `EXPOSURE_LIMIT`
   (`EXPOSURE_HEADROOM LT 0`, `DECLINE`), so nothing had; corrected by `P10-DOC-001` — the
   proposal door now refuses `422 credit.PolicyIncomplete` a policy with no rule guaranteed to
   stop an approval past its maximum exposure (`CreditPolicy.boundsExposure`,
   `PolicyRule.refusesExposurePast`: `EXPOSURE_HEADROOM` `LT`/`LE` x with x ≥ 0, or `EXPOSURE`
   `GT`/`GE` x with x ≤ the limit; the effect `HARD_DECLINE`, `DECLINE` or `REFER`), judged at
   proposal. **(b) A person's limit check** (`UnderwritingCases.exposure`) is, on the deciding
   snapshot, the platform's outstanding credit plus the reserved exposure (re-read under the
   profile lock) plus the person's approved amount, plus the bureau's total balance **only when
   the snapshot holds it** — an absent bureau balance is the referral's question, which the person
   answers (ADR-0089 §Follow-up (2)); the platform's own terms stay bound by the limit. **(c) The
   bound's amount** for a referral is the case's ceiling, `approvable_minor` (ADR-0089 §2). **(d) The
   profile's grants** are `SELECT, INSERT` plus a column grant `UPDATE (party_id)` that exists only
   so `SELECT … FOR UPDATE` is legal; the trigger refuses every actual `UPDATE`, `DELETE` and
   `TRUNCATE` for every role (`credit V003`).

6. **The platform's outstanding credit arrives through a port that answers zero, recorded.**
   `PlatformCreditExposure`, declared in `credit` and implemented in `app`, answers zero for every
   party in Phase 10 (there are no loans), deterministically; the snapshot records the answer and
   the port's version as the term's provenance. When Phase 11's loans exist, the composition
   changes and the port's version with it; decisions pinned to the Phase 10 version replay
   identically (`INV-CRD-01`). *As built by `P10-TSK-010` (2026-10-08): the term is the snapshot
   attribute `PLATFORM_OUTSTANDING_CREDIT`, added to the closed vocabulary because no code named
   it, frozen by `SnapshotFreezer` with the port's version; Phase 10's composition is `app`'s
   `NoLoansUntilPhase11`, version 1.*

## Alternatives Considered

### Floating-point annuity (`Math.pow`), rounded at display
Pros:
- One line of code; the textbook formula.

Cons:
- The outcome at the affordability margin would depend on floating-point behaviour; replay could
  diverge across JVMs or libraries; `INV-MON-01` forbids it outright.

Refused (point 3).

### Round at every intermediate step
Pros:
- Each intermediate figure is a displayable amount.

Cons:
- Accumulated rounding error, mode-dependent, with no single declared point; the outcome would
  depend on the order of operations.

Refused: scale 10 `HALF_EVEN` internally, one `HALF_UP` rounding to minor units at the end.

### Convert foreign-currency bureau figures at a reference rate
Pros:
- Fewer partial-data cases for applicants with foreign obligations.

Cons:
- Credit owns no rate; borrowing FX's would put a rate (with its own staleness and version) into
  the snapshot and the replay, and an executable-looking rate where none is executed. Phase 10's
  products are single-currency.

Refused: refused at normalisation and recorded (point 3).

### Judge exposure in the evaluating step, from the snapshot alone
Pros:
- No re-read; no successor snapshot.

Cons:
- Write-skew: two requests for one party, evaluated concurrently, each see the other's
  reservation as absent and both approve over the limit. The evaluation starts from the request
  row, so locking the profile there would invert the lock order.

Refused: profile-first in the deciding transaction, with the re-read (point 5).

### A serializable transaction instead of the profile lock
Pros:
- No explicit lock target; the database detects the conflict.

Cons:
- ADR-0039 settled `READ COMMITTED` plus explicit locks platform-wide; serialization failures
  would need a retry protocol in the deciding path, and the arbiter would be implicit rather than
  a named row.

Refused.

## Consequences

Positive:
- Affordability and exposure are exact, reproducible across instances and JVMs, and replayable
  under the engine version that computed them.
- No party's approved exposure can exceed its limit under any concurrency — the storm's exposure
  census checks it (`P10-TST-001`). *(As built (`P10-DOC-001`, 2026-10-09): precisely, the exposure
  the platform knows — outstanding credit plus reserved exposure plus the approval, with the
  bureau's total balance wherever the snapshot read it. A system approval is bounded through the
  policy's exposure rule, which the proposal door now requires (point 5 (a), corrected by
  `P10-DOC-001`; before it, a policy without one would not have bounded system approvals, though
  the seeded v1 and every test policy carry `EXPOSURE_LIMIT`); a person may approve a referral
  whose bureau balance is absent, bounded by the platform's own terms (point 5 (b)).)*
- Phase 11 plugs in outstanding credit and consumption without changing the formula.

Negative:
- All decisions for one party serialise on one row — a per-party cost, invisible fleet-wide.
- A foreign-currency obligation is a partial-data case that the policy must refer or decline,
  never an approval — some applicants are referred who a converting design would approve.

Operational impact: none of its own; the figures never enter a metric tag, a log or an event
(ADR-0072's discipline).
Security impact: the figures are `RESTRICTED-FINANCIAL` and never appear in a customer response —
only the outcome, the approved amount and the reasons' customer texts.
Financial impact: none posted. The reservation bounds what Phase 11 may lend against a decision;
the arithmetic fixes how conservatively the platform judges affordability.

## Invariants / Constraints

`INV-CRD-12` (exact decimal, one currency, rounded once at declared points), `INV-CRD-09`
(per-party serialisation; concurrent approvals never exceed the limit), `INV-CRD-01` (the
arithmetic is the engine's; the port's answer recorded), `INV-CRD-07` (every term in the
snapshot with its provenance), `INV-MON-01`…`02` (no floating point; explicit currency),
ADR-0003, ADR-0039.

## Follow-up

- **As built**: `P10-TSK-009` built affordability, its property tests and worked cases; `-010`
  exposure, the reserved-exposure contract and the `PlatformCreditExposure` port; `-016` the
  profile-first deciding transaction, the successor snapshot (ADR-0087 §5), the JDBC
  reserved-exposure read (`JdbcReservedExposure`, version 2) and the empty
  `credit_decision_consumption` table (`credit V011`, `UNIQUE (decision_id)`, written by no Phase 10
  code); `-018` a person's approval bounded by the limit (point 5 (b)).
- Phase 11: the loan writes the consumption fact and the outstanding-credit composition.
- **Acceptance.** **As built** (`P10-DOC-001`, 2026-10-09): the Phase 10 review read this ADR
  against the code, corrected it in place above (and the proposal door, point 5 (a)), and accepted
  it.
