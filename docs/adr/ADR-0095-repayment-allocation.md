# ADR-0095 — Repayment allocation: an explicit, versioned order pinned by the agreement, born with the repayment and conserving by constraint

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Ledger · Accounts
Supersedes: nothing. Resolves `docs/adr/README.md`'s anticipated Phase 11 decision "Repayment
allocation order". Applies ADR-0039 (the balance-dependent decision under the account's lock),
ADR-0041 (never from the projection), ADR-0043 (the posting in the owner's transaction) and
ADR-0004 (idempotency at the database). Rests on `PHASE_11_PLAN.md` §2.3 (L6), §2.4 (A11, A12, A21,
A26), §7.1 (T-rep), §7.4, §12.5, §12.8 (rules 5, 6, 10) and `INV-LND-04`.

## Context

1. **Money received must be split, and the split is a financial decision.** Which billed item,
   and which component — fees, interest or principal — a repayment settles changes delinquency,
   future interest, income recognition and what the customer still owes. An implicit split is an
   unexplainable one.
2. **The split must conserve.** Σ allocated = the amount received, exactly; no component may be
   paid beyond what is due; a repayment must never be partly lost or doubly applied.
3. **Repayments race.** A customer's repayment, the auto-collection on the due date, the billing
   step and a payoff can all touch one account at once (scenarios 2, 3, 7).
4. **Repayments are corrected.** An operator's mistaken repayment is reversed after it was
   allocated, and later repayments must not be re-cut (scenario 6).
5. **The two products overflow differently.** A loan's money beyond everything billed has nowhere
   contractual to go until the next billing; a line's pays down drawn principal and restores the
   limit (A26).

## Decision

1. **`ALLOCATION_ENGINE_V1` is pure.** `allocate(amount, dueState, rules) → lines`, where
   `dueState` is derived from postings and rows **under L3** (never the projection) and `rules` are
   the agreement version's pinned allocation order and overpayment treatment (ADR-0092). Asserted:
   Σ lines = amount exactly; no line exceeds its component's due; every line names a billed item
   (or the overflow target).

2. **The order (L6), as terms data.** (1) Billed items — instalments or statement minimums —
   **oldest due date first**; within each, **`FEES → INTEREST → PRINCIPAL`**. (2) The remainder by
   the overpayment treatment: loan → **`HOLD_AS_CREDIT`** (`CR LOAN_CREDIT_BALANCE`, applied
   automatically at the next billing, refunded at closure); line → **`PAY_DOWN_PRINCIPAL`**
   (`CR LOAN_PRINCIPAL`, restoring the available limit), then any excess over every receivable
   held as credit (A26). A different order is a new terms version naming an order the engine
   implements, or a new engine version.

3. **Born with the repayment, conserving by constraint** (`P11-TSK-018`). In T-rep: the claim
   `lending.repayment:CUSTOMER:<id>`; (L3) the loan `FOR UPDATE`, `ACTIVE` or `CLOSING`; (L7) the
   wallet `FOR UPDATE` and `AvailableBalance.underLock` ≥ amount; `dueState` derived; allocate;
   `repayment` born `ALLOCATED` with its `repayment_allocation` rows (per item and component,
   naming the entry); a **deferred constraint trigger** asserts Σ allocation = amount at commit;
   one entry `DR CUSTOMER_WALLET A / CR <due accounts …> / CR LOAN_PRINCIPAL (line paydown) /
   CR LOAN_CREDIT_BALANCE rest` (lines aggregated per account; the per-item split in the rows),
   key `lending.repayment:<repaymentId>`; when every receivable reaches zero, the loan closes in
   the same transaction (ADR-0090). A repayment row exists only once its money moved, and is
   `INSERT`-only: `REVERSED` is derived from an approved reversal (point 7), never written onto it.

4. **The cases.**

   | Case | Behaviour |
   |---|---|
   | Full instalment | allocated; billed − allocated = 0; cure if it was the oldest past-due (ADR-0098) |
   | Partial | allocated in order; the remainder stays due; DPD continues from the oldest unpaid due date |
   | Over | the remainder by the overpayment treatment (point 2) |
   | Early, before any billing | nothing due — held as credit (loan) or principal paid down (line); payoff is a separate command |
   | Insufficient funds / wallet not postable | no repayment, no entry; a customer's command `422 lending.InsufficientFunds`; auto-collection records a `collection_attempt` |
   | Duplicate (scenario 2) | the same key replays the same answer, one entry (`INV-IDEM-01`); auto-collection's `UNIQUE (due_item_id, attempt_date)` |
   | Concurrent (scenario 3) | serialised on L3; each allocates against what it finds; conserved, never over-allocated |
   | `CLOSED` / `CANCELLED` | `409 lending.LoanNotRepayable`, nothing posted |
   | Returned | not applicable: a wallet repayment is internal and final; an external top-up's return is payments' |

5. **Credit-balance application** (rule 6, `P11-TSK-017`). At each billing, after the billing
   entry, any `LOAN_CREDIT_BALANCE` is applied through the same engine: `DR LOAN_CREDIT_BALANCE /
   CR <due accounts>`, `credit_balance_application UNIQUE (billing_id)`, key
   `lending.credit-application:<billing>`.

6. **Auto-collection** (A21, `P11-TSK-019`). On the due date and daily while past due, the
   servicing step collects `min(due, wallet available)` under L3 then L7 — partial allowed — as a
   repayment through the same engine; every attempt, successful or not, is a born-once
   `collection_attempt`, so two sweepers, a customer repayment and the billing on one due date
   never collect twice.

7. **Reversal negates rows; it never re-cuts** (scenario 6, ADR-0099). A reversed repayment's
   entry is swapped exactly by `ReversalService`; each of its allocations is negated by a
   `repayment_allocation_reversal` row; later allocations are untouched — the reversed amount simply
   becomes due again, and DPD re-derives. A repayment that closed the account is not reversible
   (A12, `INV-LIFE-04`).

8. **Payoff uses the same engine** (`P11-TSK-026`): after accruing and billing everything, one
   repayment of the quoted amount allocated to every component, the credit balance refunded.

9. **Sources in Phase 11** are the wallet only: the customer's repayment, auto-collection,
   credit-balance application and payoff. External inbound repayment (a bank transfer to a loan
   reference, a direct debit) is deferred (A11): money arrives through the wallet's existing
   pay-ins, whose duplicates and returns payments already makes harmless.

## Alternatives Considered

### Principal first
Pros:
- Reduces future interest fastest for the customer.

Cons:
- Leaves interest and fees billed and unpaid while principal not yet due is paid — the account stays
  delinquent while the customer pays; DPD and the contract disagree.

Refused (L6).

### Pro rata across components
Pros:
- No component favoured.

Cons:
- Fractional splits invite rounding residue and an order of their own for the residue; delinquency
  never fully cures on a partial payment.

Refused.

### Customer-directed allocation
Pros:
- Customer choice.

Cons:
- Every repayment carries its own rules — unexplainable portfolio behaviour and a door for paying
  principal while arrears age. A customer who wants to reduce principal uses a payoff or (if not
  cut) a partial prepayment.

Refused for Phase 11.

### Excess refunded to the wallet immediately
Pros:
- No credit balance account.

Cons:
- A customer paying ahead sees the money bounce back; the next instalment then needs a second
  collection.

Refused: held as credit and applied at the next billing (loan).

### Excess applied as prepayment, shortening the term
Pros:
- Lowers total interest.

Cons:
- A contractual change (agreement vN+1) triggered by an overpayment, possibly by mistake.

Refused: prepayment is its own command (`P11-TSK-028`).

### Allocation recomputed at read time
Pros:
- No allocation rows.

Cons:
- The split would change whenever the engine or history changed; a reversal could not negate what
  was never recorded.

Refused: born with the repayment.

## Consequences

Positive:
- Every unit received is accounted to an item and a component, conserved by a constraint, and
  explainable years later.
- Racing repayments, collections and payoffs serialise on one row and never over-allocate.

Negative:
- Allocation rows per repayment per component; reversals add negation rows rather than editing.
- The loan's credit balance is a liability the platform carries until applied or refunded.

Operational impact: `finapp.lending.repayment{kind,outcome}`, `finapp.lending.collection{outcome}`,
`finapp.lending.allocation.anomaly` alerting above zero.
Security impact: repayment is a customer act with conditional step-up (A19), owner-scoped; a
suspended customer may still repay (standing not checked).
Financial impact: rule 5 and rule 6 postings; delinquency and income follow the order.

## Invariants / Constraints

`INV-LND-04` (allocation conserves and never over-pays a component), `INV-IDEM-01`…`04`,
`INV-BAL-05`, `INV-REV-01`…`04`, `INV-LIFE-04`, ADR-0039, ADR-0041.

## Follow-up

- Built by the M11.2 allocation engine task (`P11-TSK-008`: conservation, order, never above due,
  both overpayment treatments), `P11-TSK-017` (credit-balance application), `P11-TSK-018`
  (repayment, scenarios 2 and 3), `P11-TSK-019` (auto-collection), `P11-TSK-020` (reversal,
  scenario 6), `P11-TSK-021`…`023` (the line's paydown) and `P11-TSK-026` (payoff).
- Probes: read the projection for allocation → scenario 3 red under a racing reversal; take the
  wallet before the loan → `40P01` in scenario 3.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
