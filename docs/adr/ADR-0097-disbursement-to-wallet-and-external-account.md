# ADR-0097 — Disbursement to the borrower's wallet and to an external bank account: the receivable is born with the wallet credit, and the external leg is a payments withdrawal lending only reads

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Payments · Ledger · Accounts · Payment Methods · Settlement · Reconciliation
Supersedes: nothing. Applies ADR-0043 (an internal money movement commits with its posting, no
saga), ADR-0046 (no transaction spans a provider call; `UNKNOWN`; resolution by query), ADR-0083
(an outbound flow adopts its outcome only from an authenticated inquiry), ADR-0056 (a payout
destination is the customer's own verified method, bank details never enter), ADR-0067 (the
completion opens its expectation) and `INV-RAIL-02`, `-03`. Rests on `PHASE_11_PLAN.md` §2.1, §2.3
(L2), §2.4 (A7, A19, A30), §5.4, §7.1 (T-acc, T-dis, T-pyo, T-pyr), §7.3, §12.4 and `INV-LND-07`.

## Context

1. **The owner chose both destinations** (L2): the borrower's wallet, and an external bank account
   through payments' withdrawal machinery, with its ambiguity, returns and reconciliation.
2. **External rails are ambiguous.** A provider may accept, refuse, time out with the money sent,
   return it days later, or answer a callback nobody should trust (ADR-0083). If the loan's
   existence waited on that answer, a loan could sit `DISBURSING` for days, its interest start
   unknowable, and a return would have to unwind a receivable.
3. **Payments already solves ambiguity for withdrawals**: hold-then-dispatch, routing, `UNKNOWN`
   held, inquiry-only outcomes, returns, the expectation opener. But it admits only a person actor
   and places its own hold; a system-initiated withdrawal on a recorded instruction does not exist.
4. **Composition direction matters.** `payments.OutboundCreditComposition` has one implementer
   (crossborder); its outcome appliers call the composer. If lending implemented it, payments would
   call lending while lending's disbursement calls payments — a dependency and lock cycle.
5. **Funds must not move between disbursement and payout.** A borrower who chose an external
   payout must not spend the money in the wallet while the payout is pending — nor lose it if the
   payout fails.

## Decision

1. **The receivable is born when the wallet is credited — on both paths** (T-dis,
   `P11-TSK-014`). One transaction: sweep claim; **(L0)** profile; **(L3)** the loan `FOR UPDATE`
   conditional `PENDING_DISBURSEMENT`; **(L5)** the disbursement `PENDING`; standing re-read;
   wallet resolved through `LoanWallets`; rule 1 posted — `DR LOAN_PRINCIPAL P / CR CUSTOMER_WALLET
   P − f, CR LOAN_FEE_INCOME f` — key `lending.disbursement:<loan>`; disbursement `POSTED`; loan
   `ACTIVE`; `LoanDisbursed`; audit `lending.LoanDisbursed` (system). Before T-dis there is a
   commitment (counted in exposure and capital headroom, ADR-0091, ADR-0096) and no ledger effect.
   Disbursement is internal and final: no provider, no ambiguity (ADR-0043). The acceptance is the
   instruction; no person releases it (A7).

2. **Path W (wallet): done at T-dis's commit.** The accrual start is the entry's value date; the
   schedule v1 is generated in the same transaction (ADR-0093, ADR-0094).

3. **Path X (external account): disbursement, then a payout of the borrower's funds.** At
   acceptance the borrower names a destination verified through `LoanPayoutDestinations` (their own
   payment method, the ADR-0056 shape; an opaque payments reference, never an account identifier,
   `INV-RAIL-03`); a payout instruction is born with the loan. In T-dis, after the posting, **(L7)**
   the wallet `FOR UPDATE` and a `HoldService` hold of `P − f`; the `loan_payout` born `PENDING`
   (`UNIQUE (loan_id)`); **no schedule and no accrual yet**.

4. **The payout machine; lending only observes** (`P11-TSK-015`): `PENDING → DISPATCHING →
   DISPATCHED → PAID_OUT | FAILED | RETURNED`; `DISPATCHING → NOT_DISPATCHED` (payments refuses the
   withdrawal or cannot route it — met while dispatching, not before: the hold released, funds free
   in the wallet). Payments' withdrawal machine has no `RETURNED` state: lending maps `RETURNED`
   from payments' return record for the withdrawal, read the same way as its status.
   - **T-pyo** (a) claims the payout `PENDING → DISPATCHING` with a database-stamped permit and
     **commits**; (b) calls `LoanPayouts.dispatch` with no lending lock held — payments' own Tx1 /
     wire / Tx2, keyed `payments.withdrawal:lending:<loanId>`, adopting the hold; (c) under (L3)
     loan and (L5) payout, records `DISPATCHED` with payments' reference. A crash anywhere re-drives
     under the same key; payments replays.
   - **T-pyr**: sweep claim; (L3) loan; (L5) payout; payments' outcome **read** through
     `LoanPayouts.outcome` (a plain read of payments' withdrawal store, no lock); a terminal outcome
     recorded; the accrual start and schedule v1 set; `LoanPayoutConcluded`. While payments holds
     the withdrawal `UNKNOWN`, the payout stays `DISPATCHED` — concluded only from payments'
     authenticated inquiry, never re-dispatched (scenario 5).

5. **The payments change: a system-actor withdrawal that adopts a hold** (payments `V032`, built by
   `P11-TSK-015`). A published entry admitting `ActorType.SYSTEM` with the borrower's recorded
   instruction reference in the request fingerprint, and an optional hold to adopt, taken under the
   wallet lock instead of placing a new one — the hold adopted, never duplicated. Payments' routing,
   dispatch, `UNKNOWN` handling, inquiry-only outcomes (ADR-0083), return path and expectation
   opener are unchanged; `merchant`, `crossborder` and the person's withdrawal door are unchanged.
   Rule 12 (`DR CUSTOMER_WALLET / CR INSTANT_CLEARING`) is payments' entry, reversed or returned by
   payments.

6. **When each act succeeds.** The **disbursement** (lending's financial act: the funds made the
   borrower's) succeeds at T-dis's commit on both paths. The **payout** succeeds when payments
   concludes the withdrawal `COMPLETED` from an authenticated answer, later matched to settlement by
   Phase 8's machinery. A payout's failure or return never touches the loan: the funds are back (or
   still) in the borrower's wallet, and the receivable stands (`INV-LND-07`).

7. **Interest starts at the payout's terminal outcome** (A30). On `PAID_OUT` the funds reached the
   borrower's bank; on `FAILED`, `RETURNED` or `NOT_DISPATCHED` they are available in the wallet —
   either way the borrower has the money from that instant. Between dispatch and conclusion the
   platform, not the borrower, bears the provider's ambiguity: no interest accrues on money the
   borrower cannot use.

8. **No lock cycle** (§7.3 (d)). Lending never holds a lending lock while waiting on a payments row:
   T-pyo commits its claim before calling payments; payments' withdrawal runs its own transactions
   in its own order (withdrawal row, wallet, projections) and adopts a hold row lending created and
   nobody else locks; T-pyr reads payments' row without a lock. Payments' outcome appliers never
   touch lending — the payout's money outcome is the wallet's, not the loan's. That is why lending
   does not implement `OutboundCreditComposition`.

9. **Failure modes.**

   | Situation | Outcome |
   |---|---|
   | Standing lost between acceptance and disbursement | `CANCELLED (STANDING_LOST)`, disbursement `FAILED`, the commitment released under L0, capital headroom restored, `LoanCancelled`; the decision stays consumed (a new application) |
   | Wallet not postable | savepoint rollback; retried until `disbursement_deadline` (24 h, a terms field), then `CANCELLED (DISBURSEMENT_FAILED)`; rule 11: nothing posted |
   | Crash after acceptance | the disbursement sweep disburses once |
   | Ten disbursers (scenario 1) | `UNIQUE (loan_id)` + conditional `PENDING_DISBURSEMENT → ACTIVE` + the ledger key: one entry |
   | Crash between posting and event (scenario 9) | the outbox row is in the posting's transaction: both or neither |
   | Ten payout dispatchers | the payout's conditional claim + payments' key: one withdrawal |
   | Provider succeeded, response lost (scenario 5) | payments `UNKNOWN`, held; lending `DISPATCHED`, no interest; concluded by inquiry |
   | Payout refused or unroutable by payments | `DISPATCHING → NOT_DISPATCHED`, hold released, interest starts |
   | Return recorded before lending concludes | `RETURNED` (from payments' return record); the funds back in the wallet; interest starts |
   | Return after lending concluded `PAID_OUT` | payments' return path credits the wallet; the payout stays `PAID_OUT` (terminal); the loan and its interest unaffected |

## Alternatives Considered

### Direct external disbursement (`DR LOAN_PRINCIPAL / CR clearing`)
Pros:
- One movement; no wallet in between.

Cons:
- The loan's birth depends on a provider's ambiguous answer; interest start unknowable; a return
  unwinds a receivable; lending becomes a second `OutboundCreditComposition` implementer (a cycle).

Refused: the wallet is the transit account.

### Lending implements `OutboundCreditComposition`
Pros:
- Reuses crossborder's proven composition pattern.

Cons:
- Payments' appliers would call lending while lending calls payments: a dependency and lock cycle.

Refused (point 8).

### Payments calls back into lending on the withdrawal's outcome
Pros:
- No polling sweep.

Cons:
- A new `payments → lending` dependency; the same cycle in another form.

Refused: lending reads payments' outcome.

### Interest from the disbursement's value date on path X too
Pros:
- One accrual-start rule for both paths.

Cons:
- The borrower would pay interest on money held in limbo by a provider's ambiguity they did not
  cause.

Refused (A30).

### Let the borrower withdraw on their own (no system payout)
Pros:
- No payments change.

Cons:
- Not what the owner chose (L2); the funds would be spendable in between, and the payout's
  failure handling would be the borrower's problem.

Refused for loans; it is exactly the credit line's model (A25, ADR-0100).

## Consequences

Positive:
- The receivable's birth is unambiguous, idempotent and final on both paths.
- The external leg rides machinery already proven for ambiguity, returns and reconciliation.
- The borrower never pays interest for the platform's provider uncertainty.

Negative:
- Path X adds a machine, a sweep and a payments change (V032).
- The platform forgoes interest for the payout's ambiguous window.

Operational impact: `finapp.lending.disbursement{path,outcome}`, `…disbursement.pending.age`
(alerting above the deadline), `finapp.lending.payout{outcome}`, `…payout.dispatched.age`
(alerting above payments' outcome objective); two sweepers' enabled gauges.
Security impact: no account identifier enters lending; the payout destination is verified at
acceptance under `MULTI_FACTOR` (A19); the system actor is admitted only with a recorded
instruction reference in the fingerprint.
Financial impact: rule 1 by lending, rule 12 by payments; settlement and reconciliation unchanged.

## Invariants / Constraints

`INV-LND-07` (disbursed at most once; the receivable born exactly with the wallet credit; no payout
outcome creates, duplicates or undoes it), `INV-RAIL-02`, `INV-RAIL-03`, `INV-PAY-01`, `INV-PAY-04`,
`INV-SET-02`, `INV-EVT-01`…`04`, ADR-0043, ADR-0046, ADR-0083.

## Follow-up

- Built by `P11-TSK-012` (the payout instruction at acceptance, `LoanPayoutDestinations`),
  `P11-TSK-014` (T-dis, path W, scenarios 1 and 9) and `P11-TSK-015` (path X, payments `V032`, the
  payout machine and sweep, scenario 5).
- Probe: re-dispatch a payout on `UNKNOWN` → scenario 5 red.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
