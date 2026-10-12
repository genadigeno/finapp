# ADR-0100 — The revolving credit line: draws against an available limit derived under the line's lock, monthly statements with a minimum payment, closure through `CLOSING`, and exposure that counts the committed limit

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Credit · Ledger · Accounts
Supersedes: nothing. Builds on ADR-0090 §3 (two products, one account model), ADR-0091 §6 (an
open line's limit in exposure), ADR-0094 (accrual), ADR-0095 (allocation), ADR-0096 (accounting
and capital) and ADR-0088 §1's assessment of a `CREDIT_LINE` (`repayment = limit ×
minimum_payment_ratio`). Rests on `PHASE_11_PLAN.md` §2.3 (L3), §2.4 (A23–A28), §5.3, §7.1 (T-drw),
§7.4, §12.6, §12.10 and `INV-LND-14`, `INV-LND-13`, `INV-CRD-09`.

## Context

1. **The owner put the revolving line in scope** (L3). Phase 10 already decides `CREDIT_LINE`
   requests; without Phase 11 servicing, those decisions could never be taken up.
2. **A line is a promise, not a loan.** The customer may draw any amount up to the limit, repay,
   and draw again, without a new decision. Principal arrives in many small amounts at moments the
   platform does not choose.
3. **The available limit is a race.** Two draws (or a draw and a statement) on one line can each
   see the limit unused; a stored "available" figure drifts from the ledger.
4. **Exposure and capital ask different questions.** Exposure asks what the platform has promised
   the party (relevant to the next credit decision); capital asks how much of the platform's money
   is actually out (relevant to safeguarding). An undrawn limit is a promise but deploys no money.
5. **Statements replace instalments.** What falls due each month is the cycle's interest, fees and
   a minimum share of principal; the rest may stay drawn.

## Decision

1. **The agreement** (ADR-0092): limit `L` = the decision's approved amount (A20), the fixed rate,
   the statement day (1–28, the customer's choice), payment-due days (25, A23), the minimum-payment
   rule (floor and ratio, A24), and the four engines. Versioned like the loan's; no expiry in Phase
   11 and no periodic limit review (A27, deferred).

2. **Born `ACTIVE` at acceptance** (T-acc, `P11-TSK-012`): no disbursement, no commitment of
   capital (A28) — the decision is consumed and the limit is committed in exposure (ADR-0091) in
   the same transaction.

3. **Draws** (T-drw, `P11-TSK-021`): the claim `lending.draw:CUSTOMER:<id>` (`MULTI_FACTOR`, A19;
   standing checked in-transaction); **(L3)** the line `FOR UPDATE`, `ACTIVE`; draws not suspended —
   while any amount is past due or the line is defaulted, `409 lending.DrawsSuspended` (A25,
   ADR-0098); **(L5)** the `credit_line_draw` born (`UNIQUE (loan_id, draw_key)`); **(L6)** the
   capital row and headroom ≥ amount, else `422 lending.CapitalUnavailable` (A28, ADR-0096);
   `available = L − (LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE)`, **derived from journal lines under L3**,
   ≥ amount, else `422 lending.LimitExceeded` (`INV-LND-14`); posting (projections last, L8)
   `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET`, key `lending.draw:<drawId>`; `CreditLineDrawn`. Draws go
   **to the wallet only** (A25): an external leg is the borrower's own withdrawal.

4. **The available limit is never stored.** Every draw derives it under L3; a principal repayment,
   due or not yet due, restores it at commit; interest and fees never consume it. Two draws racing
   for the last of the limit serialise on L3 and one is refused.

5. **Revolving interest**: `ACCRUAL_ENGINE_V1` (ADR-0094) on drawn principal, born once per (line,
   date), the period being the statement cycle; interest never accrues on interest or fees (A4).

6. **`STATEMENT_ENGINE_V1`** (`P11-TSK-009` the engine, `P11-TSK-022` the statement): born once per
   (line, cycle end) in T-svc after the cycle's accruals exist (`UNIQUE (loan_id, cycle_end)`, key
   `lending.statement:<statement>`). It bills the cycle's accrued interest (`ACCRUED → DUE`),
   carries assessed fees, and bills the minimum payment's principal part,
   `min(drawn principal not yet due, max(round_UP(3 % × drawn principal), EUR 25.00 − interest −
   fees, 0))`, as `DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL` (rule 3). Minimum payment = interest +
   fees + that principal (A24) — so the **whole** minimum payment is at least EUR 25.00, or the
   entire balance when less; the floor is not a principal floor. Due date = statement date + 25
   days (A23). The statement row stores only
   what it billed; opening and closing balances are derived from the ledger, never stored as figures
   that could drift. `CreditLineStatementIssued`.

7. **Repayment on a line** (ADR-0095): billed items oldest first, fees → interest → principal; then
   `PAY_DOWN_PRINCIPAL` (credit `LOAN_PRINCIPAL`, restoring the limit); only what exceeds every
   receivable is held as `LOAN_CREDIT_BALANCE` (A26). Delinquency counts from an unpaid minimum's
   due date (ADR-0098).

8. **Closure** (`P11-TSK-023`): the customer requests it (`MULTI_FACTOR`,
   `line_closure_request UNIQUE (loan_id)`): `ACTIVE → CLOSING`, no further draws; the line closes
   in the transaction that zeroes its last receivable (a repayment, payoff, credit-balance
   application or waiver), any credit balance refunded; a line with nothing outstanding goes
   `ACTIVE → CLOSED` at the request. `CLOSING → ACTIVE` is refused: a closing line is never reopened
   (a new application). A payoff closes the line `CLOSED_BY_CUSTOMER`.

9. **Exposure counts the committed limit while the line is open** (ADR-0091 §5–6). While `ACTIVE`,
   the party can draw to `L` at any moment without a decision; a decision that counted only the
   drawn balance could approve a second credit assuming the undrawn limit away, after which both
   could be drawn — `INV-CRD-09` broken. So `PlatformCreditExposure` version 2 counts an `ACTIVE`
   line's **limit** and a `CLOSING` line's **drawn principal** (no further draw is possible).

10. **Capital counts only drawn principal** (A28, ADR-0096 §5). Capital is the money actually
    deployed; reserving capital for every undrawn limit would idle the pool against promises most
    customers never fully use. The price is that a draw within the limit can be refused for capital
    (`422 lending.CapitalUnavailable`) — visible, audited, and alerted before it happens by
    `finapp.lending.capital.headroom.low`.

## Alternatives Considered

### Count only the drawn balance in exposure
Pros:
- More credit available to customers with unused limits.

Cons:
- Two products could together exceed the party's limit the moment the line is drawn — the
  write-skew `INV-CRD-09` exists to forbid, built in by design.

Refused (point 9).

### Reserve capital for the full limit at acceptance
Pros:
- A draw within the limit can never fail for capital.

Cons:
- The pool would be committed against undrawn promises; the platform's capital would sit idle,
  and the headroom invariant would no longer measure deployed money.

Refused (A28): capital counts drawn principal.

### A separate module (or bounded context) for revolving credit
Pros:
- Each product's code is self-contained.

Cons:
- The same six accounts, accrual, allocation, delinquency, corrections, payoff and proofs would be
  duplicated or shared across a boundary; one account model (ADR-0090 §3) adds only draws,
  statements and closure.

Refused.

### A stored available-limit column
Pros:
- One read per draw.

Cons:
- A mutable money column (ADR-0090 §2); drifts from the ledger on any missed update; racing draws
  need it locked anyway.

Refused: derived under L3.

### Draws direct to an external account
Pros:
- One step for the customer.

Cons:
- Every draw would carry payout ambiguity and the path-X machinery; the wallet plus the borrower's
  own withdrawal covers it.

Refused (A25).

### Minimum payment as a fixed ratio of the balance only
Pros:
- Simpler formula.

Cons:
- On a small balance the payment may not cover the cycle's interest (negative amortisation) or be
  negligibly small; the floor and the interest-plus-fees base prevent both.

Refused.

## Consequences

Positive:
- Phase 10's `CREDIT_LINE` decisions become usable credit on the same account model, engines and
  proofs as the loan.
- The limit can never be exceeded by a draw, and the party's exposure never under-counts a promise.

Negative:
- An open line's unused limit reduces what else the party may be approved for.
- A draw within the limit can be refused for capital.
- The statement adds a fourth engine and a monthly cycle per line.

Operational impact: `finapp.lending.draw{outcome}`, `finapp.lending.billing{kind=statement}`; the
battery generates ≥ 2,000 lines with draw and payment behaviour on a simulated calendar.
Security impact: draws and closure `MULTI_FACTOR`; owner-scoped; standing checked for draws.
Financial impact: rule 1 per draw, rule 3 per statement, rule 5 with principal paydown.

## Invariants / Constraints

`INV-LND-14` (a draw never exceeds the available limit), `INV-LND-13` (capital never over-deployed),
`INV-LND-02`, `-04`, `-08`, `INV-CRD-09`, `INV-BAL-05`, ADR-0091, ADR-0096.

## Follow-up

- Built by `P11-TSK-009` (the statement engine), `P11-TSK-012` (the line born at acceptance),
  `P11-TSK-021` (draws), `P11-TSK-022` (statements and minimum payment) and `P11-TSK-023` (repayment
  and closure); exposure by `P11-TSK-013`.
- *(Written 2026-10-10: the transition's first draft of A24 read the EUR 25.00 floor as a
  principal floor, contradicting §12.6; the plan was corrected during the transition to §12.6's
  formula, which point 6 records — the floor applies to the whole minimum payment.)*
- **As built by `P11-TSK-009` (2026-10-11) - the engine.** `StatementEngineV1`, pure and static, over
  `StatementTerms` (`of(terms, statementDay)` - the floor, ratio, payment-due days, the minimum
  payment's rounding and the engine version, as declared, re-judged by the engine), answering a
  `Statement` (what the cycle billed and nothing else - no balance, no drawn principal) or a typed
  `StatementRefused`. Point 6 refined on the invariant's side: every cycle ends on the statement day,
  its exclusive end being the statement date and the next cycle's first day (the `AccrualPeriod`
  point 5's accruals round within); the first cycle runs from the opening to the first statement date
  strictly after it. "Drawn principal" in the ratio is `LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE`, the cap
  `LOAN_PRINCIPAL` alone; the minimum never exceeds what the statement can bill (its interest, fees and
  principal not yet due - an earlier unpaid minimum stays on its own due item); the interest is the
  cycle's posted accruals summed, a list that is not one chain of the cycle's own refused. Nine probes - eight caught,
  one an equivalent mutant recorded as such - `MUTATION_TESTING.md` §2. The statement row, its posting and the credit-balance application stay
  `P11-TSK-022`'s.
- **As built by `P11-TSK-021` (2026-10-12) - the draw.** Point 3 as decided, `lending V013` (`credit_line_draw`) and
  `CreditLineDraws`, with these refinements: (a) the available limit is judged under L3 **before** the capital lock - a draw
  past its own limit never queues on the currency's hot row - and the wallet is resolved after it (resolving may open the
  wallet, and no wallet row precedes L6, `PHASE_11_PLAN.md` section 7.3 (c)); (b) the draw row is born whole once its posting exists, naming its entry - "(L5) the draw born"
  is an insert no other writer can wait on (the same key's other attempt holds the claim and L3 first), so there is no L5
  lock; (c) a key retried after its claim lapsed **converges** on the draw born under it (`UNIQUE (loan_id, draw_key)`),
  another amount under it the platform's `409 api.Conflict`; (d) two refusals added - `409 lending.LineNotDrawable` (an
  instalment loan, a closing or closed line) and `409 lending.WalletUnavailable` (draws go to the wallet only, A25, so a
  wallet that cannot be had or posted refuses the draw, nothing written, the key unspent); (e) the suspension (point 3's
  "past due or defaulted") is the derived `DrawSuspension`, read under L3 on the business date - nothing can be past due
  before statements (`P11-TSK-022`) and nothing defaulted before the condition step (`P11-TSK-024`), so its derivation over
  what exists is "never suspended"; the check, its place and the `409` are built and proven with a double, and `-024`
  replaces the derivation with the condition read; (f) the line's read shows the available limit - zero on a line no longer
  `ACTIVE`; (g) no audit record: a customer's own draw is its row and its entry; the posting carries the request's own
  correlation. Point 10 holds as decided: the draw consumes headroom at its commit, and exposure does not move (no L0) -
  an `ACTIVE` line already counts `GREATEST(limit, drawn)` and a draw within the limit leaves that figure the limit
  (re-proven through the real door in `LendingCreditExposureDatabaseTest`).
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
