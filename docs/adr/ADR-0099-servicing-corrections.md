# ADR-0099 — Servicing corrections: waivers, reversals and restructuring as reasoned, four-eyes acts that post, never edits

Status: Proposed
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
   (domain and `CHECK (approved_by <> proposed_by)`, `403 lending.SelfApprovalRefused`); one open
   proposal per subject (`409 lending.ProposalPending`); every act reasoned
   (`422 lending.ReasonRequired`), the reason screened for card and account numbers (the
   `CreditReasons` / R2 precedent, `INV-AUD-02`) by the domain and by twin `CHECK`s. Keyed
   `lending.servicing:EMPLOYEE:<id>`; ten approvers of one proposal produce one decision (the
   proposal row conditional `PROPOSED` under its lock). Always four-eyes, whatever the amount (A14).

2. **No self-dealing.** An employee never services a loan of their own party: every proposal and
   approval is refused `lending.SelfDealingRefused`, audited `FAILED` (the R1 lesson). Losers of a
   race record nothing.

3. **Waivers** (`P11-TSK-025`).
   - **Fee, unpaid** (rule 8): `ReversalService` of the assessment's entry, bounded by the original
     (`INV-REV-02`) — only the unpaid remainder; `> remainder` is `422 lending.WaiverExceedsDue`.
   - **Fee, already paid** (rule 8a, kind `FEE_REFUND`): `DR LOAN_FEE_INCOME / CR
     LOAN_CREDIT_BALANCE` — the fee is owed back, applied at the next billing or refunded at closure.
   - **Interest** (rule 9): an explicit posting `DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE` (or
     `…ACCRUED` for interest not yet billed), bounded by the component's balance under L3.
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

6. **Partial prepayment** (`P11-TSK-028`, the first cut candidate): a customer's repayment kind
   `PREPAYMENT` crediting `LOAN_PRINCIPAL`, with agreement and schedule vN+1 (shorter term, same
   instalment) in one transaction; refused while past due (`409 lending.ArrearsFirst`). If cut, it
   is recorded with its owner; payoff and the line's paydown remain.

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
  (restructuring) and, if not cut, `P11-TSK-028` (prepayment); the audit acts registered in
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
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
