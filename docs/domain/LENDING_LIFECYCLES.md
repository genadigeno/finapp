# Lending Lifecycles

*Written by the Phase 10 → 11 transition (2026-10-10); nothing here is built; each task corrects its
machine to the code.* No `lending` module, schema, table, trigger, door or sweep exists yet: every
machine, edge, lock, refusal, event and audit act below is the decided design, and the task named
beside it corrects this document where the code teaches otherwise. The Phase 11 exit review
(`P11-DOC-001`) reads the whole of it against the code, statement by statement.

Written on the `CREDIT_DECISIONING_LIFECYCLES.md` precedent: the document that names a phase's
machines is written before the phase's first task. The engineering plan is
[`PHASE_11_PLAN.md`](../project/PHASE_11_PLAN.md) (§3 ownership, §5 lifecycles, §7 concurrency,
§12 the model) — where this document and the plan disagree, the plan governs until the building task
corrects one of them; the invariants are `INV-LND-01`…`14` in
[`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md); the terms are in [`GLOSSARY.md`](GLOSSARY.md)
§7 and §7a.

Related: ADR-0090 (the lending bounded context) · ADR-0091 (consumption and exposure) · ADR-0092
(versioned terms and agreement) · ADR-0093 (the schedule) · ADR-0094 (accrual and day count) ·
ADR-0095 (allocation) · ADR-0096 (loan accounting and lending capital) · ADR-0097 (disbursement and
the external payout) · ADR-0098 (delinquency, default, the collections boundary) · ADR-0099
(servicing corrections) · ADR-0100 (the revolving credit line) — all `Proposed` at the transition,
indexed in [`docs/adr/README.md`](../adr/README.md).

---

## 1. The concepts, kept apart

| Kept apart | Why |
|---|---|
| **Loan application** vs **Decision request** | The application is lending's aggregate for one customer request for a product; it *opens* exactly one credit decision request (credit's envelope) in its own transaction and stores only its id. Lending never reads a score, an attribute or a threshold |
| **Loan offer** vs **Loan agreement** vs **Loan** | The offer is what the platform will grant, expiring; the agreement is the immutable, versioned contract born at acceptance (v1) and at each accepted amendment (v n+1); the loan is the account — identity, kind, status, permits, **no amount, rate or balance column** (`INV-LND-01`) |
| **Lifecycle state** vs **Condition** | A state is earned by a producer and walks a machine; delinquency, default, draws suspended, awaiting payout and the future servicing hold are **conditions** — derived, recorded only as append-only changes. An `ACTIVE` loan is `ACTIVE` whether current or 120 days past due |
| **Disbursement** vs **Loan payout** vs payments' **Withdrawal** | The disbursement is lending's financial act — principal credited to the borrower's wallet, the receivable born in the same entry; the payout (path X only) is lending's record of the borrower's chosen external leg, which *observes* a payments withdrawal and never posts |
| **Instalment** vs **Instalment billing** vs **Repayment** vs **Repayment allocation** | The instalment is a projection row of a schedule version; the billing is the born-once fact of what actually became due at its date (interest actually accrued); the repayment is money received; the allocation is its born-with split across due items and components |
| **Draw** vs **Disbursement** | Both credit the wallet against `LOAN_PRINCIPAL`; a disbursement happens once per instalment loan, a draw any number of times per line, each bounded by the available limit (`INV-LND-14`) |
| **Available limit** vs **Exposure** vs **Capital headroom** | Available limit is one line's undrawn amount, derived under the line's lock; exposure is credit's per-party figure (an open line counts at its full **limit**); headroom is the platform's undeployed lending capital per currency (drawn principal only, A28) |
| **Delinquency** vs **Default** vs **Write-off** | Delinquency is days past due and its bucket; default is a flag at DPD ≥ 90 (L7), cleared at cure, posting nothing; write-off is a deferred accounting event (Phase 14) |
| **Payoff** vs **Prepayment** vs **Restructuring** vs **Refinance** | Payoff settles everything and closes; a partial prepayment (cut candidate) pays principal early with agreement v n+1; a restructuring is an amendment (agreement and schedule v n+1) on the same loan; a refinance (deferred) is a new decision and a new loan paying off the old |

## 2. When each act takes effect

| Act | Record | Ledger effect |
|---|---|---|
| Acceptance | offer `ACCEPTED`, agreement v1, acceptance evidence, loan born, the decision consumed | **None.** Exposure moves from credit's reservation to lending's commitment, and capital headroom is consumed (loan only), in one transaction under L0 and L6 |
| Disbursement / draw | disbursement `POSTED` + loan `ACTIVE` / a born-once draw | Rule 1: `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET` (− an origination fee to `LOAN_FEE_INCOME`). The receivable is born here, on both paths |
| Payout (path X) | the payout's terminal outcome | None of lending's: payments' own withdrawal entry. Accrual starts at the outcome (A30) |
| Accrual · billing / statement · late fee | born-once rows | Rules 2, 3, 4 |
| Repayment · credit-balance application · payoff | repayment + allocations; application; quote execution | Rules 5, 6, 7 |
| Waiver · repayment reversal · amendment | the approved (and, for an amendment, accepted) proposal | Rules 8, 8a, 9, 10, 13 |
| Closure · cancellation | loan `CLOSED` / `CANCELLED` | None of its own: closure is the consequence of the entry that zeroed the last receivable; a cancellation posts nothing (rule 11) |

---

## 3. Rules every machine shares

**Actors.** **C** — the customer, keyed per principal, owner-scoped; a `MULTI_FACTOR` session for
the application, the acceptance, a draw, a payout destination, a line's closure, a payoff and an
amendment's acceptance (A19, R14's reasoning); the platform's conditional step-up for repayment,
withdrawal of an application and quotes. **P** — a person holding an operator permission
(`LENDING_ADMINISTER`, `LENDING_CAPITAL_ADMINISTER`, `LOAN_SERVICE`, `LOAN_SERVICE_APPROVE`),
keyed per employee, reasoned (`422 lending.ReasonRequired`, reasons screened for card and account
numbers), **four-eyes** where marked ★ (domain and `CHECK`; `403 lending.SelfApprovalRefused`), and
never on their own party's loan (`lending.SelfDealingRefused`, audited `FAILED`). **S** — the system:
a leaderless sweep's step or an in-transaction consequence. **No operator creates a loan, draws,
disburses, or changes a rate outside an amendment**; origination is never a person's act (a person's
approval is credit's underwriting).

**The three ranks** (`INV-LIFE-01`, `-02`; the ADR-0044 doctrine, as Phase 10 built it): **the
domain** — each act's conditional `UPDATE … WHERE status IN (the act's from-states)` under the row
lock, so a loser finds the row moved and writes nothing; **the database** — a hand-written
every-writer trigger `<table>_permits_only_machine_edges` admitting exactly the edges below, with the
frozen columns and the cross-table preconditions named per machine, and a status `CHECK` that is a
membership list of the machine's states; and **an append-only history** per machine. The
application role is granted `SELECT, INSERT` on fact tables and a conditional `UPDATE` only of
status and permit columns on the machine tables; never `DELETE`; every table carrying money is
`INSERT`-only (`LendingSchemaHasNoMutableMoneyTest`).

**The lock order** (`PHASE_11_PLAN.md` §7.2; a planned `DISTRIBUTED_EXECUTION.md` §3 row). Every
lending writer holding more than one takes an order-respecting subsequence of:

| Position | Row | Mode | Taken by |
|---|---|---|---|
| **L0** | the party's `credit.credit_profile` | `FOR UPDATE`, through credit's port | only transactions that raise the party's exposure or move it between reserved, committed and outstanding — acceptance, disbursement, repayment reversal — and then always first |
| **L1** | `loan_application` | `FOR UPDATE` | the offer step, withdrawal, closure |
| **L2** | `loan_offer` | `FOR UPDATE` | acceptance, decline, expiry |
| **L3** | `loan` | `FOR UPDATE` | every servicing act on one account, both kinds — the serialisation point |
| **L4** | the account's instalment, billing or statement rows, by due date | `FOR UPDATE` | only a step that must lock them; reads under L3 normally suffice |
| **L5** | the act's own row | `FOR UPDATE` | disbursement, payout, draw, payoff quote, amendment, waiver, reversal, capital proposal |
| **L6** | the `LENDING_CAPITAL` ledger account of the currency | `FOR UPDATE` | a loan's acceptance; a draw |
| **L7** | the borrower's `CUSTOMER_WALLET` `ledger_account` | `FOR UPDATE` | funds checks, the payout hold and its release |
| **L8** | ledger projection rows | `lockBalancesInOrder` | postings, last |

Then only inserts (rows, events, audit, outbox). The administrations take their own order: **A0**
advisory namespace `11` (`hashtext(product)` for terms, `hashtext(currency)` for capital), **A1**
the proposal row, **A2** the active row. Credit never locks a lending row; payments never calls
lending; a sweep's claim commits before its step; no connection is held across a provider call
(`PHASE_11_PLAN.md` §7.3 proves there is no cycle).

**The database clock, at every boundary** (`INV-LND-10`). Every window is judged on
`statement_timestamp()` read **after** the relevant row lock, never on an instance clock, so ±5 s of
instance skew cannot move a date:

| Boundary | Judged as |
|---|---|
| Offer expiry | stamped `expires_at = LEAST(decision.valid_until, offered_at + offer_validity)` at birth; acceptance on `expires_at > statement_timestamp()`, expiry on `<=` — exactly one at the instant |
| Decision consumable | credit's port: `valid_until > statement_timestamp()` under L0, the complement of the reservation's predicate |
| A business date ("a day", "midnight") | the database clock in the agreement's pinned `servicing_zone` (`UTC`, A2); date `d` is accruable only once the clock, read after L3, is past the end of `d` |
| Value and posting date | every lending entry: the business date of the database clock under L3 (A10); accrual: the accrual date |
| Due, past due, DPD | an item is due on its date and past due once its date has ended; DPD counts from the oldest unpaid due date to the current business date |
| Disbursement deadline | stamped at the disbursement's birth (24 h); retries stop and the loan is cancelled at `<=` |
| Accrual start (path X) | payments' conclusion instant, read from payments' row, in the pinned zone |
| Amendment acceptance window · payoff good-through date · capital expectation ageing | each stamped from `statement_timestamp()` at birth and compared with it at the act |
| Sweep permits | stamped from `statement_timestamp()` by the one-statement `FOR UPDATE SKIP LOCKED` claim; a permit binds nothing |

---

## 4. The state machines

### 4.1 Loan terms version (`lending.loan_terms_version`) — `P11-TSK-005`

```
 (proposal) ──> PROPOSED ──★ a second person activates──> ACTIVE ──a successor activated──> RETIRED
                   └──rejected (the proposer may withdraw)──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/lending/terms`, `LENDING_ADMINISTER`, key `lending.terms:EMPLOYEE:<id>` | A0; one `PROPOSED` per product (partial unique, the backstop) | `422 lending.TermsInvalid` (judged whole: allocation order, bounds, minimum payment, engines it names), `409 lending.ProposalPending`, `422 lending.ReasonRequired` | — | `lending.TermsVersionProposed` |
| `PROPOSED → ACTIVE` ★ | P ≠ proposer, `…/{v}/approval` | A0, A1 conditional `PROPOSED`, A2 the current `ACTIVE` `FOR UPDATE` | `403 lending.SelfApprovalRefused` | `LoanTermsVersionActivated` | `lending.TermsVersionActivated` |
| `ACTIVE → RETIRED` | S, the successor's activation, same transaction | A2 | — | — | — |
| `PROPOSED → REJECTED` | P, `…/{v}/rejection` | A0, A1 | — | — | `lending.TermsVersionRejected` |

Content is immutable once proposed; an agreement pins the version and keeps it for life (`INV-LND-05`,
`INV-HIST-04`). **Never migration-activated**: production activates none, and the application door
answers `422 lending.ProductNotOffered` until the runbook's activation gate opens (L11). An
application reads the `ACTIVE` row `FOR SHARE`, so an activation and a submission serialise — the
application pins the old version or the new, never neither. **Invalid:** anything out of `RETIRED`
or `REJECTED`; `PROPOSED → RETIRED`; `ACTIVE → PROPOSED | REJECTED`; an `ACTIVE` retired without its
successor activated in the same transaction; activation by the proposer (`CHECK`).

### 4.2 Lending capital contribution (`lending.capital_contribution`) — `P11-TSK-004`

```
 (proposal) ──> PROPOSED ──★ approved: expectation opened──> APPROVED      (recognised / lapsed: derived, read — never an edge)
                   └──rejected──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/lending/capital-contributions`, `LENDING_CAPITAL_ADMINISTER`, key `lending.capital:EMPLOYEE:<id>` | A0 (`hashtext(currency)`) | `422 lending.ReasonRequired`; amount not positive or currency unsupported | — | `lending.CapitalContributionProposed` |
| `PROPOSED → APPROVED` ★ | P ≠ proposer, approval | A0, L5 conditional `PROPOSED`; the `LENDING_CAPITAL_CONTRIBUTION` expectation opened through reconciliation's opener in the same transaction (`UNIQUE (expectation_ref)`) | `403 lending.SelfApprovalRefused` | — | `lending.CapitalContributionApproved` |
| `PROPOSED → REJECTED` | P | A0, L5 | — | — | `lending.CapitalContributionRejected` |
**Recognition is derived, never a lending-written edge** (settled by the transition's consistency
pass, 2026-10-10): reconciliation never calls lending, so an `APPROVED` contribution reads as
*recognised* when its expectation's statement line has been matched and rule 0 (`DR CASH_AT_BANK / CR
LENDING_CAPITAL`) posted in reconciliation's own transaction, and *lapsed* when the expectation aged out
unmatched — both read through `app`'s port (the payout's pattern). No lending event announces it:
the recognition is reconciliation's fact and the ledger's entry.

**Capital counts from journal lines, never from this status**: headroom is `CR−DR(LENDING_CAPITAL)`
less committed and outstanding principal, judged under L6 (`INV-LND-13`); the status is the
administration's record. Recognition only from bank evidence (`INV-SET-06`): no person posts capital
and nothing adjusts it to fit. **Invalid:** anything out of `APPROVED` or `REJECTED`; a bank line after
the expectation lapsed is a reconciliation break, resolved by reconciliation.

### 4.3 Loan application (`lending.loan_application`) — `P11-TSK-010`, offered `-011`

```
 (keyed submission) SUBMITTED ──credit request opened, same tx──> AWAITING_DECISION ──decision APPROVED──> OFFERED
                                                                   ├──decision DECLINED──> DECLINED
                                                                   ├──credit request CANCELLED | EXPIRED | ABANDONED──> CLOSED_UNDECIDED
                                                                   └──customer withdraws while credit's request is cancellable──> WITHDRAWN
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `SUBMITTED → AWAITING_DECISION` | C, `MULTI_FACTOR`, `POST /v1/me/loan-applications`, key `lending.application:CUSTOMER:<id>` (T-app) | the claim; standing; the `ACTIVE` terms `FOR SHARE`, pinned on the application; `DecisionRequests.submit` through `app` (credit's arbiters, its one-open-request partial unique); `UNIQUE (decision_request_id)` | `422 lending.ProductNotOffered`, `422 lending.AmountOutOfRange`, `409 lending.ApplicationOpen`; credit's refusals passed through unchanged | `LoanApplicationSubmitted` | — (the customer's keyed act: its idempotency record, history row and event) |
| `AWAITING_DECISION → OFFERED` | S, `LoanOriginationSchedule` (T-off); `credit.CreditDecisionRecorded` only a hint | the claim; L1 conditional `AWAITING_DECISION`; the decision read authoritatively; the pinned terms `FOR SHARE`; the offer born beside it (`UNIQUE (application_id)`) | — | `LoanOffered` | — |
| `AWAITING_DECISION → DECLINED` | S, the same step | L1 | — | `LoanApplicationClosed` (`DECLINED`) | — |
| `AWAITING_DECISION → CLOSED_UNDECIDED` | S, the same step, finding credit's request closed without a decision | L1 | — | `LoanApplicationClosed` | — |
| `AWAITING_DECISION → WITHDRAWN` | C, step-up, `POST …/{id}/withdrawal`, key `lending.application-withdrawal:CUSTOMER:<id>` | L1, then credit's own cancellation of its request (credit's L2, inside credit) in the same transaction | `409 lending.ApplicationNotWithdrawable` once credit has evaluated — the decision will arrive and the customer may decline the offer | `LoanApplicationClosed` (`WITHDRAWN`) | — |

`SUBMITTED` is transient, kept so the history records submission. Terminal: `OFFERED` (continued by
the offer), `DECLINED`, `CLOSED_UNDECIDED`, `WITHDRAWN`. A decline is never reconsidered — a new
application. **Invalid:** anything out of a terminal; `SUBMITTED → OFFERED | DECLINED`; `→ OFFERED`
without its offer beside it, or on a decision not `APPROVED`; `DECLINED → OFFERED`.

### 4.4 Loan offer (`lending.loan_offer`) — `P11-TSK-011`, accepted `-012`

```
 (born by the offer step) OFFERED ──customer accepts: decision consumed, agreement v1, loan born──> ACCEPTED
                             ├──customer declines──> DECLINED
                             └──expires_at <= the database clock──> EXPIRED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `OFFERED` | S (T-off) | under L1; amount and term exactly the decision's (A20); canonical terms + `terms_sha256` | — | `LoanOffered` | — |
| `OFFERED → ACCEPTED` | C, `MULTI_FACTOR`, `POST /v1/me/loan-offers/{id}/acceptance` echoing `terms_sha256`, optional verified payout destination, key `lending.offer-acceptance:CUSTOMER:<id>` (T-acc) | the claim; **L0**; L2 conditional `OFFERED`, `expires_at > statement_timestamp()`; standing re-read; `consume` (credit's `SECURITY DEFINER` path, `UNIQUE (decision_id)`); **L6** headroom ≥ principal (loan only); loan born (`UNIQUE (offer_id)`), agreement v1 (`UNIQUE (loan_id, version)`), acceptance evidence, disbursement `PENDING` (loan), the payout instruction (path X); the six loan accounts opened | `409 lending.OfferNotAcceptable` (moved, expired, or the decision not consumable — `Lapsed` cannot precede the offer's own expiry, since `expires_at <= valid_until`), `409 lending.TermsChanged`, `422 lending.CapitalUnavailable` | `LoanAccepted` | `lending.OfferAccepted`; credit's `credit.DecisionConsumed` |
| `OFFERED → DECLINED` | C, `POST …/{id}/decline`, key `lending.offer-decline:CUSTOMER:<id>` | L2 conditional `OFFERED` | `409 lending.OfferNotAcceptable` | `LoanOfferClosed` | — |
| `OFFERED → EXPIRED` | S, `LoanOriginationSchedule` | L2 conditional `OFFERED`, `expires_at <= statement_timestamp()` | — | `LoanOfferClosed` | — |

An expired or declined offer releases nothing: the unconsumed decision lapses at its own
`valid_until`. Acceptance is not consent (`INV-IDN-04`); the evidence stores session, assurance
level, client IP and user agent as `CONFIDENTIAL` (A13). **Invalid:** anything out of `ACCEPTED`,
`DECLINED`, `EXPIRED` — `EXPIRED → ACCEPTED` above all (a new application); `→ ACCEPTED` without its
loan and consumption beside it, or at `expires_at <= statement_timestamp()`; `→ EXPIRED` before it.

### 4.5 Loan (`lending.loan`), both kinds — `P11-TSK-012`, `-014`, `-023`, `-026`

```
INSTALMENT:  PENDING_DISBURSEMENT ──disbursement credited to the wallet──> ACTIVE ──every receivable zero, credit balance refunded──> CLOSED
                   └──standing lost / wallet not postable past the deadline──> CANCELLED
REVOLVING:   (born at acceptance) ACTIVE ──customer requests closure──> CLOSING ──every receivable zero──> CLOSED
                                    └──closure requested with nothing outstanding, or paid off──────────────> CLOSED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PENDING_DISBURSEMENT` (instalment) / `ACTIVE` (revolving) | C, the acceptance (§4.4) | inside T-acc, after L0, L2, L6 | §4.4 | `LoanAccepted` | `lending.OfferAccepted` |
| `PENDING_DISBURSEMENT → ACTIVE` | S, `LoanDisbursementSchedule` (T-dis) — the acceptance is the instruction, no further act (A7) | the claim; **L0**; L3 conditional; L5 disbursement `PENDING → POSTED`; standing re-read; wallet resolved; path X: **L7** and a hold of the net amount; rule 1 under `lending.disbursement:<loan>`; path W: accrual start and schedule v1 | — (failures retry, §4.6) | `LoanDisbursed` | `lending.LoanDisbursed` (system) |
| `PENDING_DISBURSEMENT → CANCELLED` | S, the same sweep: standing lost (`STANDING_LOST`), or the wallet not postable at the deadline (`DISBURSEMENT_FAILED`) | **L0** (the commitment released), L3, L5 disbursement `→ FAILED`; nothing posted; capital headroom restored; the decision stays consumed | — | `LoanCancelled` | — |
| `ACTIVE → CLOSING` (revolving) | C, `MULTI_FACTOR`, `POST /v1/me/loans/{id}/closure`, key `lending.closure:CUSTOMER:<id>` | L3 conditional `ACTIVE`; `line_closure_request` born (`UNIQUE (loan_id)`); with nothing outstanding the same transaction closes instead | `409 lending.LoanClosed` | — | — |
| `ACTIVE → CLOSED` · `CLOSING → CLOSED` | S — the transaction that zeroes the last receivable: a repayment, a payoff, a credit-balance application or a waiver; the line also at a closure request with nothing outstanding | L3; the six balances derived from journal lines under it; any credit balance refunded to the wallet in the same transaction | — | `LoanClosed` (`REPAID`, `PAID_OFF_EARLY`, `CLOSED_BY_CUSTOMER`) | — |

Closure is a consequence, never a command. The kind is frozen at birth. **A restructuring is not a
state** (agreement v n+1 on an `ACTIVE` loan), and **no condition is a state** (§5). **Invalid:**
anything out of `CLOSED` or `CANCELLED` (`INV-LIFE-04`, `INV-LND-08`); `ACTIVE → PENDING_DISBURSEMENT`;
`PENDING_DISBURSEMENT → CLOSED` (nothing to repay — that is `CANCELLED`); `ACTIVE → CANCELLED` (after
disbursement a loan ends only by repayment, or — deferred — write-off); `CLOSING → ACTIVE` (a new
application); a revolving loan in `PENDING_DISBURSEMENT`, an instalment loan in `CLOSING`; instalment
`→ ACTIVE` without its disbursement `POSTED` beside it, `→ CANCELLED` without it `FAILED`; `CLOSED`
without its reason. A `CLOSED` account's six balances are zero, proven continuously by
`LoanSubledgerProof`; a repayment, draw or servicing step on a closed account answers
`409 lending.LoanNotRepayable` / is skipped, and no condition is written after closure (scenario 10).

### 4.6 Disbursement (`lending.loan_disbursement`) — `P11-TSK-014`

`PENDING → POSTED | FAILED`, born `PENDING` once per instalment loan in T-acc (`UNIQUE (loan_id)`),
moved only beside the loan's own edge in the same transaction: `POSTED` with `PENDING_DISBURSEMENT →
ACTIVE`, `FAILED` with `→ CANCELLED`. A wallet that cannot be posted rolls back to a savepoint and is
retried on the sweep's permit until the deadline; ten disbursers find one `PENDING` row, one entry
(`lending.disbursement:<loan>`, scenario 1); a crash after acceptance is re-driven by the sweep. The
receivable is born exactly with the wallet credit (`INV-LND-07`). **Invalid:** anything out of
`POSTED` or `FAILED`; either without the loan's matching edge.

### 4.7 Loan payout (`lending.loan_payout`), path X only — `P11-TSK-015`

```
 (born in T-dis, hold placed) PENDING ──claimed──> DISPATCHING ──payments accepted the withdrawal──> DISPATCHED ──> PAID_OUT | FAILED | RETURNED
                                                       └──unroutable, or refused by payments: the hold released──> NOT_DISPATCHED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PENDING` | S, in T-dis | under L3, L5; **L7** wallet and a ledger hold of the net principal; `UNIQUE (loan_id)` | — | — | — |
| `PENDING → DISPATCHING` | S, `LoanPayoutSchedule` (T-pyo a) | the payout's conditional claim with a permit, committed **before** payments is called | — | — | — |
| `DISPATCHING → DISPATCHED` | S (T-pyo b, c) | `LoanPayouts.dispatch` — payments' system-actor withdrawal on the recorded instruction, key `payments.withdrawal:lending:<loanId>`, adopting the hold (payments' own transactions); then L3, L5, payments' reference stored. A crash anywhere re-drives under the same key, and payments replays | — | — | — |
| `DISPATCHING → NOT_DISPATCHED` | S, when payments refuses or cannot route the withdrawal (met after the claim) | L3, L5, L7: the hold released, the funds free in the wallet; accrual start and schedule v1 set | — | `LoanPayoutConcluded` | — |
| `DISPATCHED → PAID_OUT \| FAILED \| RETURNED` | S (T-pyr), on payments' terminal outcome — `COMPLETED` concluded from an authenticated answer (ADR-0083), its failure, or its return | L3, L5; payments' row **read** plain through `app`, never locked; accrual start = payments' conclusion instant, schedule v1 generated (A30) | — | `LoanPayoutConcluded` | — |

`UNKNOWN` at payments is `DISPATCHED` here — held, no interest — until payments concludes by inquiry;
lending never re-dispatches (scenario 5, `INV-RAIL-02`), and a payout outcome never creates,
duplicates or undoes the loan's receivable (`INV-LND-07`): on `FAILED`, `RETURNED` or
`NOT_DISPATCHED` the money is the borrower's, in their wallet. No account identifier enters lending,
only payments' opaque destination reference (`INV-RAIL-03`). **Invalid:** anything out of the four
terminals; `PENDING → DISPATCHED | PAID_OUT | NOT_DISPATCHED`; `DISPATCHING → PENDING`; `DISPATCHED →
NOT_DISPATCHED`; `DISPATCHED` without payments' reference. Payments' withdrawal machine has no return
state (`DISPATCHED → COMPLETED | FAILED | UNKNOWN`), so `RETURNED` is mapped from payments' return
record of a `COMPLETED` withdrawal.

### 4.8 Repayment (`lending.repayment`) — `P11-TSK-018`, `-019`, `-026`

Born `ALLOCATED` — a repayment row exists only once its money moved, with its allocations
(`repayment_allocation`, a deferred trigger asserting Σ allocation = amount) and its entry
(`lending.repayment:<id>`, rule 5) — and `→ REVERSED` only through an approved repayment reversal
(§4.9). A failed collection is a `collection_attempt`, never a repayment.

| Birth | Actor & door | Locks / arbiters | Refusals | Event |
|---|---|---|---|---|
| Customer repayment | C, step-up, `POST /v1/me/loans/{id}/repayments`, key `lending.repayment:CUSTOMER:<id>` (T-rep) | the claim; L3 `ACTIVE`/`CLOSING`; **L7**, available ≥ amount (`AvailableBalance.underLock`); allocation derived under L3 (`INV-LND-04`); L8 | `422 lending.InsufficientFunds`, `409 lending.LoanNotRepayable`; standing is **not** checked (a suspended customer may repay) | `RepaymentReceived` (+ `LoanClosed` if it closed) |
| Auto-collection | S, `LoanServicingSchedule` (T-svc) on the due date, retried daily while past due (A21) | L3; `collection_attempt UNIQUE (due_item_id, attempt_date)`; L7 | nothing collected → the attempt, no repayment | `RepaymentReceived` / `CollectionFailed` |
| Payoff | §4.10 | | | `RepaymentReceived`, `LoanClosed` |

The same key replays one answer (scenario 2); two keys serialise on L3, each allocating against what
it finds (scenario 3). The allocation order is the pinned terms' (L6): oldest due first, fees →
interest → principal; the remainder held as credit (loan) or paying down drawn principal (line, A26).

### 4.9 Servicing proposals — repayment reversal, waiver, amendment — `P11-TSK-020`, `-025`, `-027`

```
 repayment reversal, waiver:  PROPOSED ──★ approved: posted──> APPROVED        PROPOSED ──rejected──> REJECTED
 amendment:                   PROPOSED ──★ approved──> APPROVED ──customer accepts──> ACCEPTED   (agreement and schedule v n+1)
                                 └──rejected──> REJECTED     └──declined · its window expired · the agreement version moved──> LAPSED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `LOAN_SERVICE`, `POST /v1/operator/loans/{id}/waivers` · `…/repayments/{rid}/reversal` · `…/amendments`, key `lending.servicing:EMPLOYEE:<id>` | L3; one live proposal per subject | `422 lending.ReasonRequired`, `409 lending.ProposalPending`, `422 lending.WaiverExceedsDue`, `409 lending.LoanClosed`, `lending.SelfDealingRefused` | — | `lending.WaiverProposed` / `RepaymentReversalProposed` / `AmendmentProposed` |
| reversal `PROPOSED → APPROVED` ★ | P ≠ proposer, `LOAN_SERVICE_APPROVE` | **L0** (outstanding rises); L3; L5 conditional `PROPOSED`; **L6** and capital headroom when principal is re-instated (`422 lending.CapitalUnavailable`, the proposal stays `PROPOSED`); `ReversalService` of the repayment's entry, exact (`INV-REV-02`); allocations negated by `repayment_allocation_reversal` rows, later allocations untouched (scenario 6) | `403 lending.SelfApprovalRefused`; refused for a repayment that closed the loan or on a closed loan (A12, `INV-LIFE-04`) | `RepaymentReversed` | `lending.RepaymentReversalApproved` |
| waiver `PROPOSED → APPROVED` ★ | the same | L3; L5; a fee waiver reverses the assessment's unpaid remainder (rule 8), a paid fee is refunded to the credit balance (8a), interest by an explicit posting (9); may close the loan | `403 lending.SelfApprovalRefused`, `422 lending.WaiverExceedsDue` | `LoanFeeWaived` / `LoanInterestWaived` | `lending.WaiverApproved` |
| amendment `PROPOSED → APPROVED` ★ | the same | L3; L5; nothing posted yet | `403 lending.SelfApprovalRefused` | — | `lending.AmendmentApproved` |
| `PROPOSED → REJECTED` | P, `…/rejection` | L3, L5 | — | — | `…Rejected` |
| amendment `APPROVED → ACCEPTED` | C, `MULTI_FACTOR`, `POST …/amendments/{aid}/acceptance`, key `lending.amendment-acceptance:CUSTOMER:<id>`, with its own acceptance evidence | L3 `ACTIVE`; L5 conditional `APPROVED`, within its window, **the loan's current agreement version = the one it was proposed against**; agreement v n+1 and schedule v n+1 born once (`UNIQUE (loan_id, version)`, `UNIQUE (loan_id, schedule_version)`); arrears re-scheduled by rule 13; billed instalments kept, unbilled superseded | `409 lending.AmendmentLapsed` | `LoanAmended` | `lending.AmendmentAccepted` |
| amendment `APPROVED → LAPSED` | C (declines), or S (window expired; the agreement version moved — found by the servicing step or by the acceptance that meets it) | L3, L5 | — | — | — |

The repayment row is `INSERT`-only (it carries money), so `REVERSED` is read from the approved
reversal naming it, not written onto it. No "override" of a computed figure exists: a person changes
an outcome only through these acts, each a reasoned posting (`INV-LND-11`). **Invalid:** anything
out of `APPROVED` (reversal, waiver), `REJECTED`, `ACCEPTED` or `LAPSED`; `PROPOSED → ACCEPTED`;
`APPROVED → REJECTED`; approval by the proposer (`CHECK`).

### 4.10 Payoff quote and execution — `P11-TSK-026`

The **quote** is an immutable born-once fact (C, `POST …/payoff-quotes`, keyed, either kind, any
condition, `ACTIVE`/`CLOSING`): a good-through date `G` (today, up to 30 days) and the amount —
derived outstanding plus the accrual the engine would post through `G − 1` — stored with its inputs.
The **execution** is a separate born-once fact (`payoff_execution UNIQUE (quote_id)`): C,
`MULTI_FACTOR`, `POST …/payoff`, key `lending.payoff:CUSTOMER:<id>`, on `G` only (T-pay: the claim;
L3; L5 the quote; L7 funds; catch-up accrual and billing under the same uniques; recompute). A
recomputed amount ≠ the quote → `409 lending.PayoffQuoteStale`, nothing posted (scenario 7); else one
repayment allocated to every component, the credit balance refunded, the loan `CLOSED`
(`PAID_OFF_EARLY`; a line `CLOSED_BY_CUSTOMER`) — one transaction. Daily accrual makes an early
settlement "rebate" zero by construction. Partial prepayment (`-028`, the cut candidate) is a
repayment kind with agreement and schedule v n+1, refused while past due (`409 lending.ArrearsFirst`).

### 4.11 The born-once facts (not machines)

| Fact | Table | Born | Once by |
|---|---|---|---|
| Agreement version | `loan_agreement` | acceptance (v1); accepted amendment or prepayment (v n+1) | `UNIQUE (loan_id, version)`; `INSERT`-only for every role; `terms_sha256` re-verified by replay |
| Acceptance evidence | `loan_acceptance` | acceptance; amendment acceptance | one per accepting act |
| Schedule version · instalment | `repayment_schedule`, `loan_instalment` | the accrual start; an accepted change | `UNIQUE (loan_id, schedule_version)`; `UNIQUE (schedule_id, sequence)` |
| Accrual start | `accrual_start` | T-dis (path W) / T-pyr (path X) | `UNIQUE (loan_id)` |
| Interest accrual | `interest_accrual` | T-svc, per elapsed date, oldest first | `UNIQUE (loan_id, accrual_date)` + ledger key (`INV-LND-02`, scenario 4) |
| Instalment billing · credit-balance application | `instalment_billing`, `credit_balance_application` | T-svc at the due date, after every accrual through `Dj − 1` | `UNIQUE (instalment_id)`; `UNIQUE (billing_id)` |
| Draw | `credit_line_draw` | C, `MULTI_FACTOR`, T-drw: L3 `ACTIVE`, draws not suspended, L5, L6 headroom, available limit derived under L3 | `UNIQUE (loan_id, draw_key)`; `422 lending.LimitExceeded` (`INV-LND-14`), `409 lending.DrawsSuspended`, `422 lending.CapitalUnavailable` |
| Statement | `credit_line_statement` | T-svc at the cycle end, after its accruals | `UNIQUE (loan_id, cycle_end)` |
| Line closure request | `line_closure_request` | `ACTIVE → CLOSING` | `UNIQUE (loan_id)` |
| Collection attempt | `collection_attempt` | T-svc | `UNIQUE (due_item_id, attempt_date)` |
| Fee assessment | `fee_assessment` | T-svc, on the day DPD reaches `late_fee_day` (> grace) | `UNIQUE (due_item_id, kind)`, capped per account |
| Condition change | `loan_condition_event` | T-svc, only on a change | `UNIQUE (loan_id, business_date, kind)` |

---

## 5. Conditions (orthogonal to the lifecycle; append-only histories) — `P11-TSK-024`

| Condition | Values | Set / cleared by | Effect |
|---|---|---|---|
| Delinquency | `CURRENT`; `PAST_DUE` with DPD and bucket — `B1_1_29`, `B2_30_59`, `B3_60_89`, `B4_90_PLUS` (the pinned terms' bounds) | S, daily in T-svc under L3, derived from billed and allocated rows | late-fee eligibility; draws suspended (line); `LoanDelinquencyChanged` (old/new bucket, DPD) — also announcing cure |
| Default | `DEFAULTED` flag | S at DPD ≥ 90 (L7); cleared at cure, no probation (A8) | `LoanDefaulted` / `LoanDefaultCleared`; draws suspended; **no acceleration, no posting** |
| Draws suspended (line) | derived | S, read at the draw under L3 from the two above | a draw refused `409 lending.DrawsSuspended` (A25) |
| Awaiting payout (path X) | derived from the payout row (`PENDING`, `DISPATCHING`, `DISPATCHED`) | S | no schedule and no accrual until the payout's terminal outcome (A30) |
| Servicing hold | *designed, not built* (A9): legal, dispute, insolvency, deceased | P | would suspend late fees and auto-collection |

DPD is the days between the oldest past-due item's due date and the current business date (database
clock, pinned zone), 0 when nothing is past due — **derived, never a stored counter**. Grace delays
only the late fee, never DPD. Cure is DPD back to 0 because every billed amount is paid. The sweep
appends a `loan_condition_event` **only on a change** of bucket, state or flag, born once per
(account, business date, kind): re-running a day writes nothing, and a closed account is skipped
(`INV-LND-12`, scenario 10). Payoff is permitted in any condition. Lending owns these contractual
facts; risk (Phase 13) owns fraud and abuse judgements, and a future collections owner contact
strategy and placement — none writes lending's rows. **Not built** (each recorded with its default):
acceleration, default or penalty interest, interest on interest, arrears notifications, bureau
reporting, statutory contact limits, insolvency handling.

---

## 6. The ranks, per machine (planned)

| Machine | Table (task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Terms version | `loan_terms_version` (`-005`) | `loan_terms_version_event` | one `PROPOSED` / one `ACTIVE` per product; four-eyes `CHECK`; content frozen from insert; retirement only beside a successor (deferred); namespace `11` |
| Capital contribution | `capital_contribution` (`-004`) | `capital_contribution_event` | four-eyes `CHECK`; `UNIQUE (expectation_ref)`; namespace `11` |
| Loan application | `loan_application` (`-010`) | `loan_application_event` | `UNIQUE (decision_request_id)`; `→ OFFERED` only beside its offer |
| Loan offer | `loan_offer` (`-011`) | `loan_offer_event` | `UNIQUE (application_id)`; the complementary expiry and acceptance clock conditionals; `→ ACCEPTED` only beside its loan; canonical terms and hash frozen |
| Loan | `loan` (`-012`) | its edge history (§8, open) | `UNIQUE (offer_id)`; kind frozen; kind-specific edges; `→ ACTIVE` / `→ CANCELLED` only beside the disbursement's matching state; closure reason `CHECK` |
| Disbursement | `loan_disbursement` (`-012`, moved `-014`) | — | `UNIQUE (loan_id)`; moves only beside the loan's edge |
| Loan payout | `loan_payout` (`-015`) | — | `UNIQUE (loan_id)`; payments' reference required from `DISPATCHED` |
| Repayment reversal · waiver · amendment | `repayment_reversal` (`-020`), `waiver` (`-025`), `loan_amendment` (`-027`) | the proposal rows' decided columns | four-eyes `CHECK`; amendment acceptance conditional on the agreement version |

Every trigger is `<table>_permits_only_machine_edges`, written by hand; every status `CHECK` a
membership list; raw-SQL terminal edges are refused for every role in each task's database tests.

---

## 7. Settled points

The owner's transition decisions (2026-10-10, recorded in `BACKLOG.md` §Phase 11), as they bear on
the machines:

| # | Decision | Where it lands in the machines |
|---|---|---|
| L1 | Funding from `LENDING_CAPITAL`, recognised only from bank evidence, never over-deployed | §4.2; L6 at acceptance, draw and a principal-re-instating reversal; `CapitalUnavailable`; `INV-LND-13` |
| L2 | Disbursement to the wallet and to an external account through payments' withdrawal | §4.6, §4.7; the payout observes, never posts; no lock-order cycle |
| L3 | `PERSONAL_LOAN` and the revolving `CREDIT_LINE`; refinance, write-off, collections deferred | §4.5's two kinds; `CLOSING` for the line only; no write-off edge |
| L4 | Neutral EUR jurisdiction, no consumer-credit-law features | no withdrawal-right edge on the agreement, no statutory notice step |
| L5 | ACT/365F simple daily interest, rounded once per period | the accrual fact and the database-clock date rule (§3) |
| L6 | Allocation oldest-due first, fees → interest → principal, excess held as credit | §4.8 |
| L7 | Default at 90 days past due | §5 |
| L8 | No penalty interest, no prepayment fee, no APR display | no condition changes a rate; payoff charges nothing extra |
| L9 | Credit's changes from `V021`; `lending/V001…` | the consumption path `-001` (credit `V021`) that acceptance calls |
| L10 | `P11-TSK-001`, the credit-owned consumption port, before any loan | §4.4: acceptance consumes only through it, under L0 |
| L11 | Origination only against the simulators until a real bureau exists | §4.1: no `ACTIVE` terms in production → `ProductNotOffered` |
| L12 | The ten mandatory scenarios | 1 §4.6, §4.7 · 2, 3 §4.8 · 4 §4.11 · 5 §4.7 · 6 §4.9 · 7 §4.10 · 8 §4.9 · 9 every event in its state change's transaction · 10 §5 |

## 8. Settled by the transition's consistency pass

Six points this document first recorded as open were settled by the transition itself
(2026-10-10), and `PHASE_11_PLAN.md` corrected to match; each task still corrects its machine to
the code:

- **The payout's refusal edge** (`-015`): `DISPATCHING → NOT_DISPATCHED` — the claim commits before
  payments is called, so a refusal is met there (§4.7). Payments' withdrawal machine has no return
  state, so `RETURNED` is mapped from payments' return record; a return after `PAID_OUT` is
  payments' alone.
- **Capital recognition** (`-004`): derived, never a lending-written edge — read through `app`
  (§4.2); capital correctness never depends on it.
- **One live reversal proposal per repayment** (`-020`): a partial unique over
  `PROPOSED`/`APPROVED`, so a rejected proposal can be proposed again.
- **The loan's edge history** (`-012`): `loan_event`, as for the other machines.
- **Where `TermsNotAmortising` is judged**: at the terms proposal (`-005`), for terms that cannot
  amortise at their declared bounds; a specific offer that still would not amortise closes its
  application `CLOSED_UNDECIDED (TERMS_NOT_AMORTISING)` with no offer (`-011`).
- **The terms an offer pins** (`-011`): the version `ACTIVE` at the offering instant, read `FOR
  SHARE`; the version recorded on the application is informational, so a retirement between
  application and decision changes nothing already pinned and the offer takes the active one.
