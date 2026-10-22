# ADR-0099 — Servicing corrections: waivers, reversals and restructuring as reasoned, four-eyes acts that post, never edits

Status: Accepted (2026-10-15, `P11-DOC-001` — read against the code and corrected first)
Date: 2026-10-10
Phase: 11
Context: Lending · Ledger · Identity · Audit · Credit
Supersedes: nothing. Applies ADR-0002 and `INV-HIST-01` (history immutable; corrections are
compensating entries), `ReversalService` and its bound (`INV-REV-01`…`04`, `ReversalBound`,
advisory namespace 2), ADR-0031 (roles grant permissions, ownership checked separately), ADR-0071's
four-eyes shape and the Phase 10 → 11 transition's R1 (self-dealing refused) and R2 (person-written
reasons screened, `CreditReasons`). Rests on `PHASE_11_PLAN.md` §2.4 (A10, A12, A14), §4, §5.4,
§7.1 (T-rev, T-wvr, T-amd), §9, §11, §12.3, §12.5, §12.8 (rules 8–10, 13) and `INV-LND-11`.

## Context

1. **People make mistakes and customers need relief.** A fee charged wrongly, a repayment taken by
   mistake, a borrower who cannot meet the schedule — each needs a correction. In most systems the
   correction is an edit to a balance column; here there is none to edit (ADR-0090 §2).
2. **Every correction moves money or changes a promise.** A waiver forgoes income; a reversal gives
   money back to the wallet and reinstates debt; a restructuring changes the contract. Each must be
   explicit, reasoned, authorised by two people and auditable — and must leave the original fact
   intact.
3. **Operators are an attack surface.** An employee who could waive their own loan's fees, approve
   their own proposal, or "adjust" a computed figure could create money; reason fields invite card
   and account numbers.
4. **Corrections race servicing.** A reversal can arrive after later repayments were allocated
   (scenario 6); an amendment can be approved while the schedule moves (scenario 8).

## Decision

1. **One proposal machine for every correction.** `PROPOSED → APPROVED (posted) | REJECTED`; an
   amendment additionally `APPROVED → ACCEPTED | LAPSED`. Proposed by a person holding
   `LOAN_SERVICE`, approved by a different person holding `LOAN_SERVICE_APPROVE` — never one's own
   (domain and `CHECK (decided_by <> proposed_by)` on an approval, `403 lending.SelfApprovalRefused`); one open
   proposal per subject (`409 lending.ProposalPending`); every act reasoned
   (`422 lending.ReasonRequired`), the reason screened for card and account numbers (the
   `CreditReasons` / R2 precedent, `INV-AUD-02`) by the domain and by twin `CHECK`s. Keyed
   `lending.servicing:EMPLOYEE:<id>`; ten approvers of one proposal produce one decision (the
   proposal row conditional `PROPOSED` under its lock). Always four-eyes, whatever the amount (A14).
   *(As built (`P11-DOC-001`, 2026-10-15): the column is `decided_by` - `repayment_reversal` (`lending
   V016`), `waiver_decision` (`V021`), `loan_amendment_decision` (`V023`) - this read "`approved_by`". Only the
   reversal's proposal row carries its decision; a waiver and an amendment are `INSERT`-only, each decided by a
   born-once decision row whose uniqueness is the arbiter. An amendment's states are derived, never written
   (`LoanAmendmentStatus`): `PROPOSED → APPROVED → ACCEPTED | LAPSED` and `PROPOSED → REJECTED | LAPSED` - a
   proposal lapses too when the agreement it amends moves or the loan closes before any decision.)*

2. **No self-dealing.** An employee never services a loan of their own party: every proposal and
   approval is refused `lending.SelfDealingRefused`, audited `FAILED` (the R1 lesson). Losers of a
   race record nothing.

3. **Waivers** (`P11-TSK-025`).
   - **Fee, unpaid** (rule 8): `ReversalService` of the assessment's entry, bounded by the original
     (`INV-REV-02`) — only the unpaid remainder; `> remainder` is `422 lending.WaiverExceedsDue`.
   - **Fee, already paid** (rule 8a, kind `FEE_REFUND`): `DR LOAN_FEE_INCOME / CR
     LOAN_CREDIT_BALANCE` — the fee is owed back, applied at the next billing or refunded at closure.
     *(Corrected by the Phase 11 gate, R2, 2026-10-16: applied at the next servicing step whenever something is
     already due - ADR-0095 point 5's catch-up application - else at the next billing; refunded at closure. And a fee
     refunded is never forgiven again - R3, below.)*
   - **Interest** (rule 9): an explicit posting `DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE` (or
     `…ACCRUED` for interest not yet billed), bounded by the component's balance under L3. *(As built
     (`P11-DOC-001`, 2026-10-15): the `…ACCRUED` arm was never built - an interest waiver releases a billed
     item's due interest only, accrued, unbilled interest is never waived (`WaiverKind.INTEREST`).)*
   A waiver that zeroes the last receivable closes the account in its transaction (ADR-0090).
   Events `LoanFeeWaived`, `LoanInterestWaived`; audit `lending.WaiverProposed` / `…Approved` /
   `…Rejected`.

4. **Repayment reversal** (`P11-TSK-020`, scenario 6). T-rev: claim; **(L0)** the profile (the
   reversal raises outstanding, ADR-0091); (L3) the loan; (L5) the proposal `FOR UPDATE`
   conditional `PROPOSED`; `ReversalService` swaps the repayment's entry exactly (rule 10); the
   `repayment_reversal` row (one **live** proposal per repayment — a partial `UNIQUE (repayment_id)
   WHERE status IN ('PROPOSED', 'APPROVED')`, so a rejected proposal can be proposed again) becomes
   `APPROVED`; the `repayment` row is `INSERT`-only and its `REVERSED` status is **derived** from the
   approved reversal, never written onto it; each allocation negated by a
   `repayment_allocation_reversal` row, never deleted; **later allocations
   are not re-cut** — the reversed amount becomes due again and DPD re-derives (ADR-0098). Refused
   for a repayment that closed the account (A12, `INV-LIFE-04` — terminal is terminal) and when the
   wallet cannot be credited (`LedgerAccountNotPostableException`, nothing posted). The interest
   that would have accrued on the reinstated principal in between is not recharged (A10).

5. **Restructuring by amendment** (`P11-TSK-027`, scenario 8; mandatory, never cut). A person
   proposes agreement version n+1 (a new term, a lower rate, re-scheduled arrears); a second person
   approves; the **customer accepts** (`MULTI_FACTOR`, acceptance evidence as ADR-0092 §6). The
   acceptance is conditional on the agreement version the amendment was drawn against still being
   current — if vN+1 arrived first, the amendment `LAPSED` (`409 lending.AmendmentLapsed`); an offer
   expiry or the customer's decline lapses it too. In one transaction under L3: agreement vN+1 and
   schedule vN+1 (ADR-0093 §9, `P` = principal outstanding from postings), billed instalments kept,
   unbilled superseded; arrears re-scheduled by rule 13 (`DR LOAN_PRINCIPAL / CR
   LOAN_PRINCIPAL_DUE`); overdue interest never capitalised; `LoanAmended`. **An amendment never
   raises principal outstanding or a line's limit** — more credit is a new credit decision.

6. **Partial prepayment** (`P11-TSK-028`, built, `lending V024`; *this read "the first cut candidate"
   until `P11-DOC-001`, 2026-10-15 - it was not cut*): a customer's repayment kind
   `PREPAYMENT` crediting `LOAN_PRINCIPAL`, with agreement and schedule vN+1 (shorter term, same
   instalment) in one transaction; refused while past due (`409 lending.ArrearsFirst`). *(If cut, it
   was to be recorded with its owner; it was built.)*

7. **What no operator can do.** Originate a loan, change a rate outside an accepted amendment, draw
   on a line, disburse, post to a loan account through `AdjustmentService` (every lending purpose is
   in `closedToFreeAdjustments()`), or "override" a computed figure. A person changes an outcome only
   through the acts above, each a posting with a reason — never an edit.

8. **Roles** (identity `V021`, `P11-TSK-002`): `LOAN_SERVICE` (read any loan, audited
   `lending.LoanRead`; propose) and `LOAN_SERVICE_APPROVE` (approve) → role `LOAN_SERVICING_AGENT`;
   `LENDING_ADMINISTER` and `LENDING_INVESTIGATE` → `LENDING_OFFICER`;
   `LENDING_CAPITAL_ADMINISTER` → `LENDING_TREASURY_OFFICER`. No role proposes and approves its own
   act.

## Alternatives Considered

### Editable balances or an operator "adjust" door on loans
Pros:
- Fast fixes.

Cons:
- No balance column exists to edit (ADR-0090); a free adjustment is unexplained money
  (`INV-REC-06`'s reasoning) and the subledger proof would fail by design.

Refused.

### Threshold-based four-eyes (single approval below an amount)
Pros:
- Less operator friction for small waivers.

Cons:
- Splitting is trivial; every waiver forgoes income the platform earned.

Refused (A14): always four-eyes.

### Re-allocate later repayments after a reversal
Pros:
- The "as if it never happened" view.

Cons:
- Rewrites committed allocations and their entries; the customer's history changes under them;
  concurrency with live repayments becomes a cascade.

Refused: negate the reversed rows; the amount becomes due again.

### Restructuring without the customer's acceptance
Pros:
- Operators can act immediately.

Cons:
- A contract changed by one party; acceptance evidence would bind terms the customer never saw.

Refused.

### Reversing a repayment that closed the account
Pros:
- Corrects every mistake.

Cons:
- Re-opens a terminal account (`INV-LIFE-04`); closure has released commitments and refunded
  credit balances.

Refused (A12): such a case is a new operation for a later phase.

## Consequences

Positive:
- Every correction is a reasoned, two-person, audited posting; nothing is ever edited.
- No operator can create money, originate credit or service their own loan.

Negative:
- Corrections are slower than edits — by design.
- A mistaken repayment that closed an account cannot be reversed in Phase 11.

Operational impact: `finapp.lending.reversal`, `…waiver{kind}`, `…amendment` counters (outcomes only).
Security impact: four-eyes by domain and `CHECK`; reasons screened; self-dealing refused and audited.
Financial impact: rules 8, 8a, 9, 10 and 13; income forgone is visible as reversing revenue lines.

## Invariants / Constraints

`INV-LND-11` (a servicing correction is a reasoned, four-eyes act that posts), `INV-HIST-01`,
`INV-REV-01`…`04`, `INV-LIFE-04`, `INV-AUD-01`…`04`, `INV-ACC-01`, ADR-0031, ADR-0071.

## Follow-up

- Built by `P11-TSK-020` (reversal), `P11-TSK-025` (fees and waivers), `P11-TSK-027`
  (restructuring) and `P11-TSK-028` (prepayment; *"if not cut" until `P11-DOC-001`, 2026-10-15 - built*); the audit acts registered in
  `AUDITABLE_ACTIONS.md` by each.
- **Settled at the transition (2026-10-10), found while this ADR was written.** (1) A reversal of
  a repayment allocated to principal re-instates principal and credits the wallet, consuming capital
  headroom: T-rev takes L6 after L5 and re-checks headroom, refusing `422
  lending.CapitalUnavailable` with the proposal staying `PROPOSED` (`PHASE_11_PLAN.md` §7.1, §12.9;
  `INV-LND-13`). (2) On a credit line, reversing a principal paydown after the customer re-drew the
  restored limit raises drawn principal above the limit: the reversal is **admitted** (a correction
  must stay possible; `INV-LND-14` governs draws), the line's available limit is then zero until it
  is repaid below the limit, and `PlatformCreditExposure` version 2 counts an `ACTIVE` line at the
  **greater** of its limit and its drawn principal (ADR-0091, `PHASE_11_PLAN.md` §12.10). (3) Point
  5's rule — an amendment never raises principal outstanding or a line's limit; more credit is a new
  decision — is confirmed and stated in `PHASE_11_PLAN.md` §5.4.
- **As built by `P11-TSK-020` (2026-10-13) - point 4, the repayment reversal, with these refinements:** the migration is
  `lending V016` (the wave 4 board's reservation; the backlog's `V013` was stale); `repayment_reversal` is a money-free
  machine row with column-scoped `UPDATE` (the terms version's split - the amount is the repayment's), its proposer,
  decider, both reasons and instants on the one row (one edge out of `PROPOSED`, nothing overwritten); **each decision
  names its `reversalId`** in the body, so an approver approves exactly the proposal reviewed; **a reversal is refused on
  any loan not `ACTIVE`** (`409 lending.LoanClosed` - not only the closing repayment: every repayment of a closed loan, A12
  read whole) and **when the repayment's held credit has since been applied** (`409 lending.RepaymentNotReversible`, added -
  the exact swap would drive `LOAN_CREDIT_BALANCE` below zero, and later allocations are not re-cut), also answering a
  repayment already reversed; the wallet credited is the account rule 5 debited (read from the original entry), its
  refusal `409 lending.WalletUnavailable` (the draw's code, its second subject); L0 is taken on every approval (raising or
  not), L6 only when the reversed repayment paid principal; the rejection is `LOAN_SERVICE` (the proposer's withdrawal
  included) and allowed on a closed loan, to clear a standing proposal; self-dealing is refused at the proposal, the
  approval and the rejection - not at the operator's read, which is audited (`lending.LoanRead`) and shows nothing the
  customer's own read does not. Whose party an operator is comes from credit's `IdentityActingParty`, which now serves
  lending's `LoanActingParty` too (one read).
- **As built by `P11-TSK-025` (2026-10-13) - points 1, 2 and the waivers, with these refinements:** the migration is
  `lending V021` (the wave 5 board's reservation; the backlog's `V018` was stale); **the waiver is two INSERT-only rows** -
  `waiver` (the proposal, carrying the amount) and `waiver_decision` (born once, `UNIQUE (waiver_id)`) - not -020's
  money-free machine row with a column-scoped `UPDATE`: a row carrying money takes no `UPDATE` in this schema
  (`LendingSchemaHasNoMutableMoneyTest`), so the four eyes are a `CHECK` over the proposer copied into the decision and pinned
  by the composite key (P11-TSK-004's capital contribution pattern), the status is derived, and "L5" is the proposal read
  under L3 with its decision's unique beneath; **the decision names its waiver by the path**
  (`…/waivers/{waiverId}/approval`), so an approver approves exactly the proposal reviewed; **one waiver of a kind on an item
  awaits a decision at a time** (`409 lending.ProposalPending`, the trigger beneath L3) - financial safety rests on the
  bound, not on it; **every waiver is bounded by what is due now at the proposal and again at the approval**
  (`422 lending.WaiverExceedsDue`, new), derived from the rows the ledger is proven against and re-judged by the decision
  trigger, which takes L3 itself, for every writer; **an interest waiver releases billed interest only** (rule 9 against
  `LOAN_INTEREST_DUE`) - accrued interest not yet billed is never waived, because a billing's interest is exactly its period's
  accruals for every writer (owner judgement recorded); **the approval takes L7 before posting** (its rules post to shared
  income rows; a closure's refund must not take the wallet after them); a waiver that settles the last receivable closes the
  account in its transaction through the one `LoanClosures` (`REPAID`, or a `CLOSING` line `CLOSED_BY_CUSTOMER` - no new
  closure reason); the self-dealing judgement is lifted into `LoanSelfDealing`, one rule for both acts.
- **As built by `P11-TSK-027` (2026-10-14) - point 5, restructuring by amendment, with these refinements:** the migration is
  `lending V023` (the backlog's `V020` was stale); `loan_amendment` and `loan_amendment_decision` are INSERT-only (the
  waiver's two-table pattern) and **every status is derived** - `LAPSED` from either `PROPOSED` or `APPROVED` once the
  agreement version it amends is no longer the loan's, the loan is no longer `ACTIVE` (a payoff closed it) or the approved
  offer expired (the pinned offer validity, stamped at the approval on the database clock) - so no sweeper writes a lapse
  and the customer declines by letting it lapse; **what an amendment may change**: the rate (equal or lower - a higher rate
  is a new decision, `422 lending.TermsInvalid`), the remaining term (never fewer instalments than the current schedule has
  left, the loan's whole term - instalments billed plus remaining - within the product's maximum) and whether the arrears
  are re-scheduled; never the principal, the repayment day, the terms version, the engines or the template, and never a
  credit line (`422 lending.TermsInvalid`); **the customer's consent** is a `MULTI_FACTOR` acceptance echoing the SHA-256 of
  the amendment's document (`AmendmentDocument`, rendered from the proposal's immutable facts and hashed at the proposal; a
  mismatch `409 lending.TermsChanged`), its evidence a `loan_acceptance` row binding that hash; **the acceptance catches up
  first** - every elapsed date accrued under the agreement in force and everything due billed (the servicing step's own
  accrual and billing, so "billed instalments kept" includes today's) - and the new rate governs every date accrued from
  the acceptance's business date; **the new schedule starts at the open period** (the latest calendar's period holding
  today - its start the latest billed due date), so the accruals already posted in it are billed by the new first
  instalment and never stranded (a deferred check holds it); **arrears** are billed principal past due (due before today):
  each such item's whole unpaid principal re-classified by rule 13 under one entry `lending.amendment:<amendment>` and
  recorded per item (`loan_amendment_rescheduling`); `lending.billing_component_billed(item, 'PRINCIPAL')` subtracts it, so
  the due state, DPD, the closure's rows-side rule and the never-over-paid rules read the re-classification - overdue
  interest and fees stay due on their items (never capitalised), and the loan cures only when they are paid; no condition is
  written by the acceptance (the next servicing step records the change - conditions follow the rows); **agreement v n+1**
  keeps the principal, decision, terms version, day, engines and template, takes the amendment's rate and term (`term_months`
  the instalments billed plus the remaining ones), and carries canonical terms **format 3** (format 2's content with the
  illustration replaced by the amendment, the version and hash it amends, the effective date and the schedule it fixed) -
  admitted only for a version after the first; **schedule v n+1**'s principal is exactly the principal not yet billed by the
  rows (agreement v1's principal less the principal billed net of re-scheduling - `LOAN_PRINCIPAL` after rule 13), held by
  the schedule trigger for every writer; the billing trigger's principal rank reads the principal billed **net of
  re-scheduling** (`P11-TSK-017`'s carry) and a billing names the agreement version its schedule projects; **no L0, L6 or
  L7** - the exposure measure and capital headroom read `LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE` and the latest agreement's
  principal, all unchanged; an amendment is final - a later one supersedes it as version n+2, and a quote made before it
  goes stale (`409 lending.PayoffQuoteStale`, `P11-TSK-026`).
- **As built by `P11-TSK-028` (2026-10-14) - point 6, the partial prepayment, with these refinements:** the migration is
  `lending V024` (the backlog's `V021` was stale); **agreement version n+1 is a prepayment's** - a third origin,
  `loan_agreement.prepayment_id` naming the repayment (the origin `CHECK` now "version 1 the offer's; every later version
  exactly one of an amendment's or a prepayment's") - carrying everything of version n but a term never longer (instalments
  billed plus the new schedule's) and canonical terms **format 4** (the prepayment, the version and hash it follows, the
  effective date, the amount prepaid and the schedule it fixed); a new version rather than a schedule alone because a
  schedule is named for the agreement version it projects, the agreement states the contract's term, a pending amendment
  of version n then lapses and a payoff quote made under it is stale by derivation, and replay re-derives each version from
  its bytes; **no acceptance evidence** - a prepayment is a right the agreement already grants, exercised by the customer's
  own keyed `MULTI_FACTOR` command, the repayment row its record; **"shorter term, same instalment"** is exact:
  `ScheduleEngineV1.recalculateAtInstalment` keeps the superseded schedule's level instalment and takes the least term that
  amortises the remaining principal, never more instalments than the superseded schedule had left - only when late payments
  left more principal than that instalment can carry in the remaining term is the term kept and the instalment the
  engine's own (necessarily higher); the reduce-instalment option stays out of scope; **arrears first** means nothing billed
  still owing - past due or due today - after the prepayment's own catch-up (the servicing step's accrual, billing and
  credit-balance application, so a billing on the same business date always comes first: `409 lending.ArrearsFirst`, new);
  **strictly partial** - an amount reaching the principal not yet due is `422 lending.PrepaymentExceedsPrincipal` (new):
  the whole is the payoff's, which also settles the open period's accrued interest, so a prepayment never closes the loan
  and never meets `LoanClosures`; **never reversed** - the reversal's proposal is `409 lending.RepaymentNotReversible` and
  `lending V024` refuses the row (swapping its entry would leave the schedule amortising less principal than the loan owes);
  the correction is an amendment (F4); **`MULTI_FACTOR`** for every customer, as the payoff and the acceptance, not the
  repayment's conditional step-up; **no fee** (L8); `finapp.lending.repayment{kind=prepayment}` with the outcomes
  `prepaid`, `arrears_first`, `exceeds_principal`, `not_repayable`, `insufficient_funds`. Found on the way and corrected:
  `JdbcLoanStore.lock`/`lockOwnedBy` read the agreement in the lock statement's pre-wait snapshot, so an act waiting on L3
  behind an amendment's acceptance or a prepayment saw the superseded version (and failed against the database's version
  checks); the agreement is now read by a statement of its own after the lock, for every caller. Added at the completion
  gate: `lending V024`'s schedule trigger refuses, for every later version (an amendment's too), a schedule superseded
  while an instalment of it already due is unbilled - the step order's "billing first" held beneath the domain, so no due
  instalment and none of its period's accrued interest is stranded; and every `lending.LoanAmended` names its `origin`
  (`AMENDMENT`, `PREPAYMENT`).
- **The interest waiver's lock order, corrected in the code** (`P11-DOC-001`, 2026-10-15). T-wvr took the borrower's
  wallet (L7) before posting rule 9 to the currency's shared `LOAN_INTEREST_INCOME` row, while the servicing step's
  accrual, the payoff's and the prepayment's catch-up accrual take that row BEFORE their wallet - over two accounts of
  one party, a cycle (`40P01`; liveness, not money - recorded for the owner by `P11-TSK-028`). The exit review moved
  the interest waiver into the order everyone else keeps: `Waivers.approve` takes the income row
  (`PostingService.lockBalancesInOrder`) before L7; the fee kinds keep L7 first (T-dis path X holds the wallet while it
  posts `LOAN_FEE_INCOME`). Proven by `FeeAndWaiverDatabaseTest#anInterestWaiverTakesTheIncomeRowBeforeTheWallet` and
  probed (`MUTATION_TESTING.md` §2); `DISTRIBUTED_EXECUTION.md` §3 records it.
- **The Phase 11 gate's R3 (2026-10-16) - a refunded fee is never forgiven twice.** A fee paid by a repayment, refunded
  (rule 8a), then the repayment reversed (rule 10) was due again on its item, and the fee waiver's bound (billed less paid)
  admitted waiving it: `LOAN_FEE_INCOME` negative for the loan while the borrower still held the refund as credit. The
  bound of a `FEE` waiver is now `least(billed - paid, assessed - fee waived - refunded)` - at the domain (`Waivers.room`)
  and re-judged by the decision trigger for every writer (`lending V029`'s `waiver_decision_is_born_once`). The reversal
  stays admitted (a bounced payment must stay correctable): the fee due again is settled by the refund held - ADR-0095
  point 5's catch-up application, at the next servicing step - so the books end where a waiver alone would have left
  them. The subledger proof gained an income arm: each fee assessment's income (assessed less approved fee waivers and
  refunds) is never negative, `FEE_INCOME_NEGATIVE` (`FeeAndWaiverDatabaseTest#aRefundedFeeIsNeverForgivenTwice`).
- **A reversal is never valued before the repayment it reverses (the Phase 12 → 13 transition gate's A2-1, repair R3,
  2026-10-22; lending `V031`).** Point 4's value date was `max(today, floor)` - the floor (the Phase 11 gate's R1) has no arm
  for the reversed repayment's own date - so on a clock stepped back behind the repayment, the floor further back, the
  reversal was valued before it: a date between the two carried the re-opened amount twice (the repayment's credit not yet
  valued, the reversal's debit already) and the accrual charged its day's interest twice, a figure every accrual re-derived
  identically (`IDENTICAL`). `RepaymentReversals` now values it `max(today, floor, the repayment's value date)`; lending's
  triggers never read the ledger, so `V031` gives the reversal row the value date of its entry (`value_date`, written beside
  the entry it names; `NULL` while `PROPOSED`, when `REJECTED`, and on rows decided before it) and the edge function holds it
  for every writer - an approval names it, never before the floor (R1's rank, now held for the reversal too), never before
  the repayment, never after the later of the clock's latest date, the floor and the repayment's own date. That the entry
  is valued on that date is the replay's: `LoanReplayProof`'s reversal arm reads the entry's lines and finds one valued
  before its repayment `REVERSAL`. `BackwardClockDatabaseTest#aReversalSteppedBackBehindItsRepaymentIsValuedOnIt`. bnpl's
  T-rev alike (`bnpl V019`, `BnplReplayProof`'s `VALUE_DATE` arm).
- **Acceptance.** **As built** (`P11-DOC-001`, 2026-10-15): `Proposed` at the Phase 10 → 11 transition
  (2026-10-10); the Phase 11 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.
