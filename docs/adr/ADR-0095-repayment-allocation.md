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
- **As built by `P11-TSK-008` (2026-10-11) - decision 1, 2 and the engine halves of 5 and 8, with these
  refinements:** `AllocationEngineV1.allocate(amount, dueState, rules)` returns `AllocationLine`s
  (target `DUE_ITEM` with the item and component, `NOT_DUE_PRINCIPAL` or `CREDIT_BALANCE`; every line
  positive, one per component). `AllocationRules` (engine version, sequence, order, treatment) is taken
  from the terms (`AllocationRules.of`) and judged again by its constructor, since the agreement's
  canonical terms will rebuild it (`-011`): an order naming every component once, an allocation
  engine's version - and the engine refuses rules pinning any version but its own. `DueState` is the
  caller's derivation (each billed item's identity, due date and fees / interest / principal still due,
  and the principal not yet due), refused whole for a negative figure, a repeated item or **a repeated
  due date** - oldest-first is then a total order and the allocation a function of the set, never of an
  input order. Every order the vocabulary admits (the six permutations `LoanTerms` accepts) is applied
  as pinned. **The line's remainder** pays down principal not yet due and holds only the rest; accrued,
  unbilled interest is not a target (it is billed first, then the credit balance applies). **The
  credit-balance application (rule 6) is `allocate` with the credit balance as the amount** - its
  `CREDIT_BALANCE` line is what stays held, so no separate entry point. **The prepayment** is
  `allocatePrepayment` (one `NOT_DUE_PRINCIPAL` line) and refuses arrears (`ARREARS_OUTSTANDING` - the
  engine's half of `-028`'s `409 lending.ArrearsFirst`), an amount beyond the principal not yet due and
  the line's treatment. Refusals are one typed exception, `AllocationRefused`, by `Reason` (negative
  amount, currency or scale, malformed rules, unsupported engine, malformed due state, and the three
  prepayment reasons); no HTTP code - the commands that call the engine map them. Conservation and
  never-above-due are asserted before the lines return (an `IllegalStateException`: an engine defect,
  never an input refusal). The backlog's `AllocationEngineV1Properties` is `AllocationEngineV1PropertiesTest`
  (`TestTaxonomyTest`), its two-JVM comparison through the lending test helper `SecondJvm`.
- **As built by `P11-TSK-017` (2026-10-12) - point 5, the credit-balance application.** After each billing, in its
  transaction, a positive `LOAN_CREDIT_BALANCE` is `allocate`d over the due state derived under L3 from the rows (each
  billed item's billed less allocated - `InstalmentBillingStore.BilledItem.dueItem()`, the billed-item model `-018`
  allocates against - and `LOAN_PRINCIPAL` as the principal not yet due), refused unless those rows explain the due
  accounts' ledger balances exactly. Its `DUE_ITEM` lines are `credit_balance_allocation` rows, aggregated per due account
  in rule 6's entry; its `CREDIT_BALANCE` line stays held; a balance that meets nothing due writes no application.
  `lending V012`: `UNIQUE (billing_id)`; at commit the rows sum to the application and no billed component is paid beyond
  what it billed, the paid sum one function (`lending.billing_component_paid`) that `-018` extends with
  `repayment_allocation`, so the rule covers every payer. Point 1's per-item split and point 2's order are the engine's,
  unchanged.
- **As built by `P11-TSK-018` (2026-10-12) - points 3 and 4, the customer's repayment, with these refinements:** `lending
  V014` (the board's next free version; the backlog's `V011` stale). The repayment row carries no status - `ALLOCATED` is
  what a repayment without an approved reversal reads as (point 7's `REVERSED` derived by `-020`); its `request_key` makes
  `UNIQUE (loan_id, kind, request_key)` the arbiter behind a lapsed claim. `repayment_allocation` records every line -
  billed items' components, and the overflow targets (`CREDIT_BALANCE`; `NOT_DUE_PRINCIPAL` refused for an instalment loan
  by its trigger until a line's paydown, `-023`) - so Σ rows = amount is exact at commit and the credit balance's arm of the
  subledger proof reads from rows. The due state is the one `-017` derives (lifted to `LoanReceivables` for its second
  caller); an unexplained ledger or an engine defect is an `AllocationAnomaly` (a `500`, nothing written, counted by
  `finapp.lending.allocation.anomaly`). Point 4's "wallet not postable" folds into `422 lending.InsufficientFunds`: the
  wallet is the party's existing one whatever their standing (`LoanWallets.existingWallet` - never opened) and must be
  `ACTIVE` under L7. **Closure (point 3's last clause)** runs in the repayment and, for a credit-balance application that
  settles the loan, as the servicing step's last step - one `LoanClosures` - and additionally requires that no elapsed
  date is left unaccrued with principal outstanding; the refund is its own entry (`lending.closure-refund:<loan>`) and the
  closure its own fact (`loan_closure`, one per loan). A credit line's repayment answers `409 lending.LoanNotRepayable`
  until `-023`. The probe "wallet before loan → 40P01" is held by scenario 3's racing loan-then-wallet writer and by
  `RepaymentDatabaseTest#repaymentTakesTheLoanThenTheWallet`.
- **As built by `P11-TSK-019` (2026-10-13) - point 6, auto-collection, with these refinements:** `lending V015` (the board's
  reservation; the backlog's `V012` stale). The collection is T-svc's step after the billing and its credit-balance
  application, once the accrual has caught up, under the same L3 - when the governing agreement's pinned terms collect
  automatically and the repayment method is the wallet. **One attempt per billed item and business date**, oldest first: an
  item on its due date always (`NOTHING_DUE` when already settled), a past-due one while something is due; a missed date is
  not back-filled. Point 6's `min(due, wallet available)` is **per item** and only where the pinned terms allow partial
  collection; otherwise the item's due in full or nothing (`INSUFFICIENT_FUNDS`); and once an item is not collected in full
  nothing younger is collected that day (a younger collection would be allocated to the older by point 2's order). The
  money moves through **T-rep's one posting half** (`Repayments.receive`: L8, rule 5, the rows, `RepaymentReceived`, the
  closure), kind `AUTO_COLLECTION`, under the attempt's natural key `<item>:<date>` - so `repayment UNIQUE (loan_id, kind,
  request_key)` is a second arbiter of once per item and date - every line on the attempted item, never the credit balance.
  A wallet absent or not `ACTIVE` is its own outcome, `WALLET_NOT_POSTABLE` (the customer's door folds it into
  `InsufficientFunds`). A collection that settles the loan closes it in its step, the closure naming it (so `-020`'s A12
  refusal covers it); `lending V015` admits that one attempt on the loan it closed. Every attempt is born once
  (`collection_attempt`), `lending.CollectionFailed` when nothing could be taken; an `AUTO_COLLECTION` repayment without its
  attempt is refused at commit. The suites' fixture terms default to auto-collection off (every suite before it bills
  against a wallet holding the disbursed principal); `AutoCollectionDatabaseTest` activates its own.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
