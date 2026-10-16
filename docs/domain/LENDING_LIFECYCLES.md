# Lending Lifecycles

*Written by the Phase 10 → 11 transition (2026-10-10); built by Phase 11's tasks and read against the
code, statement by statement, by the Phase 11 exit review (`P11-DOC-001`, 2026-10-15).* Every machine,
edge, lock, refusal, event and audit act below is now a description of the `lending` module as built
(`lending/src/main/java/com/finapp/lending`, migrations `lending V001`…`V028`, the doors in `app`); where
a task or the review found the code and the design apart, the document was corrected, each correction
noted where it stands. *(This read "nothing here is built … No `lending` module, schema, table, trigger,
door or sweep exists yet: every machine … is the decided design" until the exit review.)*

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
(servicing corrections) · ADR-0100 (the revolving credit line) — all written `Proposed` at the
transition and accepted by the exit review (`P11-DOC-001`, 2026-10-15), indexed in [`docs/adr/README.md`](../adr/README.md).

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
| **Payoff** vs **Prepayment** vs **Restructuring** vs **Refinance** | Payoff settles everything and closes; a partial prepayment (`-028`, built - *"cut candidate" until `P11-DOC-001`, 2026-10-15*) pays principal early with agreement v n+1; a restructuring is an amendment (agreement and schedule v n+1) on the same loan; a refinance (deferred) is a new decision and a new loan paying off the old |

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

**The lock order** (`PHASE_11_PLAN.md` §7.2; `DISTRIBUTED_EXECUTION.md` §3's row "The Phase 11 lock
order" - *"a planned … row" until `P11-DOC-001`, 2026-10-15: the row is written*). Every
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
| A business date ("a day", "midnight") | the database clock in the agreement's pinned `servicing_zone` (`UTC`, A2); date `d` is accruable only once the clock, read after L3, is past the end of `d`. The every-writer ranks judge the step's date against the range the transaction read - `lending.business_date_earliest(zone)` for a floor, `lending.business_date_latest(zone)` for a ceiling, the earlier and later of its start's and this statement's dates (`lending V028`, `P11-TSK-036`) - so a database clock stepped back across a midnight between the step's read and its write refuses nothing a reading admitted |
| Value and posting date | every lending entry: the business date of the database clock under L3 (A10); accrual: the accrual date |
| Due, past due, DPD | an item is due on its date and past due once its date has ended; DPD counts from the oldest unpaid due date to the current business date |
| Disbursement deadline | stamped at the disbursement's birth (24 h); the attempt that fails at or after it (`deadline_at <= statement_timestamp()`) cancels the loan - a wallet that can be posted is still disbursed after an outage past it (§4.6's as-built note; *"retries stop and the loan is cancelled at `<=`" until `P11-DOC-001`, 2026-10-15*) |
| Accrual start (path X) | payments' conclusion instant, read from payments' row, in the pinned zone |
| Amendment acceptance window · payoff good-through date · capital expectation ageing | each stamped from `statement_timestamp()` at birth and compared with it at the act |
| Sweep permits | stamped from `statement_timestamp()` by the one-statement `FOR UPDATE SKIP LOCKED` claim; a permit binds nothing. A permit "due at once" (stamped at birth) is due to a later statement only once the clock passes it - on a clock stepped back, a delay of at most the step (ADR-0063 decision 4, `P11-TSK-036`) |

---

## 4. The state machines

### 4.1 Loan terms version (`lending.loan_terms_version`) — `P11-TSK-005`

```
 (proposal) ──> PROPOSED ──★ a second person activates──> ACTIVE ──a successor activated──> RETIRED
                   └──rejected (the proposer may withdraw)──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/lending/terms`, `LENDING_ADMINISTER`, key `lending.terms:CUSTOMER:<id>` *(the session actor's type: `CUSTOMER` for an operator session too - `SessionAuthenticationInterceptor` enters `ActorType.CUSTOMER` for every session and the platform does not yet issue `EMPLOYEE` actors to operator sessions, a known platform debt (`CURRENT_STATE.md` §Known Architectural Debt, Phase 15), so the key and the audit record both read `CUSTOMER`; read `EMPLOYEE` until the Phase 11 completion gate's repair, 2026-10-16, A5-04)* | A0; one `PROPOSED` per product (partial unique, the backstop) | `422 lending.TermsInvalid` (judged whole: allocation order, bounds, minimum payment, engines it names), `409 lending.ProposalPending`, `422 lending.ReasonRequired` | — | `lending.TermsVersionProposed` |
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
| (birth) → `PROPOSED` | P, `POST /v1/operator/lending/capital-contributions`, `LENDING_CAPITAL_ADMINISTER`, key `lending.capital:CUSTOMER:<id>` *(`CUSTOMER`-typed for an operator session - §4.1's note; read `EMPLOYEE` until A5-04)* | A0 (`hashtext(currency)`) | `422 lending.ReasonRequired`; amount not positive or currency unsupported | — | `lending.CapitalContributionProposed` |
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
| (birth) → `SUBMITTED → AWAITING_DECISION` | C, `MULTI_FACTOR`, `POST /v1/me/loan-applications`, key `lending.application:CUSTOMER:<id>` (T-app) | the claim; standing; the `ACTIVE` terms `FOR SHARE`, recorded on the application - informational, the offer pins the version active at its own instant (`-011`); `DecisionRequests.submit` through `app` (credit's arbiters, its one-open-request partial unique); `UNIQUE (decision_request_id)`; one open application per party and product (partial unique, the backstop) | `422 lending.ProductNotOffered`, `422 lending.AmountOutOfRange`, `409 lending.ApplicationOpen`; credit's refusals passed through unchanged | `LoanApplicationSubmitted` | — (the customer's keyed act: its idempotency record, history row and event) |
| `AWAITING_DECISION → OFFERED` | S, `LoanOriginationSchedule` (T-off); `credit.CreditDecisionRecorded` only a hint | the claim; L1 conditional `AWAITING_DECISION`; the decision read authoritatively; the pinned terms `FOR SHARE`; the offer born beside it (`UNIQUE (application_id)`) | — | `LoanOffered` | — |
| `AWAITING_DECISION → DECLINED` | S, the same step | L1 | — | `LoanApplicationClosed` (`DECLINED`) | — |
| `AWAITING_DECISION → CLOSED_UNDECIDED` | S, the same step, finding credit's request closed without a decision | L1 | — | `LoanApplicationClosed` | — |
| `AWAITING_DECISION → WITHDRAWN` | C, step-up, `POST …/{id}/withdrawal`, key `lending.application-withdrawal:CUSTOMER:<id>` | L1, then credit's own cancellation of its request (credit's L2, inside credit) in the same transaction | `409 lending.ApplicationNotWithdrawable` once credit has evaluated — the decision will arrive and the customer may decline the offer | `LoanApplicationClosed` (`WITHDRAWN`) | — |

`SUBMITTED` is transient, kept so the history records submission - the row is born in it and moved on in the same
transaction, and `lending V004`'s deferred trigger refuses to commit a row still `SUBMITTED`, for every writer
*(as built by `P11-TSK-010`: born `SUBMITTED` only, on its product's `ACTIVE` terms version and a repayment or statement
day that version offers; identity and choices frozen; closure stamped by the database; the application holds no figure -
the amount, term and declared income and expenditure are credit's request's; the trigger already admits the
origination step's three edges, and `-011` adds "`→ OFFERED` only beside its offer" and its permit column)*. Terminal:
`OFFERED` (continued by the offer), `DECLINED`, `CLOSED_UNDECIDED`, `WITHDRAWN`. A decline is never reconsidered — a new
application. **Invalid:** anything out of a terminal; `SUBMITTED → OFFERED | DECLINED`; `→ OFFERED`
without its offer beside it, or on a decision not `APPROVED`; `DECLINED → OFFERED`. *(As built
(`P11-DOC-001`, 2026-10-15): "on a decision not `APPROVED`" is refused by the domain alone - the origination
step offers only on credit's `APPROVED` answer read through `LoanCreditDecisions.decisionFor`; no lending
trigger can check it, because the decision is credit's and lending's migrations name no other schema (A16).
Every other invalid edge here is also the trigger's.)*

*As built by `P11-TSK-011` (2026-10-11): the origination step (`LoanOrigination`, swept by `LoanOriginationSchedule`)
takes the three exits under L1 on credit's answer read through `LoanCreditDecisions.decisionFor` - `OFFERED` (reason
`DECISION_APPROVED`) only beside its offer (`lending V005`'s replaced edge function), `DECLINED` (`CREDIT_DECLINED`),
`CLOSED_UNDECIDED` with `CREDIT_REQUEST_CANCELLED | _EXPIRED | _ABANDONED`, and - refined - also for an approval that
cannot be offered: `PRODUCT_NOT_OFFERED` (no `ACTIVE` terms version), `REPAYMENT_DAY_NOT_OFFERED` (the active version no
longer offers the chosen day), `DECISION_LAPSED` (the decision's `valid_until` already past on the database clock - an
offer is never born expired) and `TERMS_NOT_AMORTISING`. The application gained its permit `next_step_at` (due at birth,
re-stamped by every claim, pulled forward by a credit event's hint) - a permit-only update admitted only while
`AWAITING_DECISION`. The owner's read names the offer's id once `OFFERED`.*

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

*As built by `P11-TSK-011` (2026-10-11): `lending V005` splits the offer along the money line, as `-005` split the terms
version - `loan_offer`, the machine row with no money (column-scoped `UPDATE` of `status` and its permit
`next_step_at`, lockable as L2), and `loan_offer_terms`, INSERT-only and immutable for every role: exactly the approved
amount and term, the pinned version's rate, engines, template and a line's rule (the trigger holds them to the pinned
`loan_terms` row and the application's day), the loan's illustrative schedule figures from the offering day (A5), the
canonical terms (format 1; format 2 from `P11-TSK-031` - format 1 with `+` in a token, so a servicing zone east of UTC
is spelled) with `terms_sha256` - a `CHECK` holds the hash to the stored bytes - and the offer document's
`document_sha256`; `loan_offer_event` the history. Born `OFFERED` under L1 for an application `AWAITING_DECISION` of the
same party and product, on the product's `ACTIVE` version, `offered_at` a database instant of the transaction - the
insert's own stamp, `GREATEST(statement_timestamp(), transaction_timestamp())`, never a value the step read in an
earlier statement, which a database clock stepped back would put after the insert's clock (`lending V028`,
`P11-TSK-036`); the illustration starts on its business date; `expires_at` and the permit stamped by the trigger; `UNIQUE (application_id)` and `UNIQUE (decision_id)`; committed only
beside the application's `OFFERED` edge and with its terms (deferred). The decline (`POST /v1/me/loan-offers/{id}/decline`,
conditional step-up) is conditional on `OFFERED` and `expires_at > statement_timestamp()`, refused `409
lending.OfferNotAcceptable` otherwise - so an offer past its instant but not yet swept is not declined; it expires. The
expiry's conditional is the complement, the edge trigger holds both for every writer, and `ACCEPTED` is refused for
every writer until `-012` replaces the edge function and admits it beside its loan.*

*As built by `P11-TSK-012` (2026-10-11): `lending V006` replaces the edge function - `OFFERED → ACCEPTED` admitted only
while `expires_at > statement_timestamp()` and only beside the offer's loan (born first, in the same transaction), and a
deferred check commits the edge only with the loan's agreement v1 and its evidence. The acceptance is the order L0 → L2
→ checks → credit's consumption → L6 (a loan) → inserts (`LoanAcceptances`): the echoed hash (`409 lending.TermsChanged`,
malformed included), the payout destination (`422 lending.PayoutDestinationInvalid`, first raised here: path X exactly
when the application preferred `EXTERNAL_ACCOUNT`, the customer's own live bank-account payment method by opaque
reference; none for a wallet payout or a line) and the standing (`409 credit.ApplicantNotEligible`) are judged before
any write. Credit's lapse, judged on its own clock under its lock after the offer's, refuses `409
lending.OfferNotAcceptable` and **writes nothing** - the offer is not expired by the acceptance (a refusal commits
nothing under the customer's key); its `expires_at <= valid_until` is then past or at the instant, and the sweep expires
it. The binding of agreement and consumption is credit's `UNIQUE (consumer_kind, consumer_ref)` over the agreement's id
and the one transaction - no lending trigger reads credit's table (A16 at the database).*

*The expiry crossed during the acceptance's waits (`P11-TSK-034`, 2026-10-15; found by `P11-TST-001`'s storm): the
acceptance judges the offer unexpired after L2 and then waits - credit's consumption, L6 - so the database clock may pass
`expires_at` before the loan is born. The loan's edge trigger re-judges the expiry at the INSERT and refuses it - the
strict backstop: no loan is born for an offer expired at that instant - but it refused in the generic `P0001` every
breach of the trigger shares, and the customer got `500`. `lending V026` gives that refusal, and the offer's own refusal
of `ACCEPTED` or `DECLINED` past the instant, their own SQLSTATE, `LNEXP`, judged after the offer's identity (a foreign,
moved or mismatched offer is refused `P0001` even when expired); the acceptance maps exactly `LNEXP` to `409
lending.OfferNotAcceptable` and writes nothing, the offer left `OFFERED` for the sweep. Every other refusal of the loan's
birth is an invariant breach and stays a `500`.*

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
| `PENDING_DISBURSEMENT → CANCELLED` | S, the same sweep: standing lost (`STANDING_LOST`), or an attempt failing at or after the deadline (`DISBURSEMENT_FAILED`) | **L0** (the commitment released), L3, L5 disbursement `→ FAILED`; nothing posted; capital headroom restored; the decision stays consumed | — | `LoanCancelled` | — |
| `ACTIVE → CLOSING` (revolving) | C, `MULTI_FACTOR`, `POST /v1/me/loans/{id}/closure`, key `lending.closure:CUSTOMER:<id>` | L3 conditional `ACTIVE`; `line_closure_request` born (`UNIQUE (loan_id)`, outcome `CLOSING`); with nothing outstanding - every receivable zero and every elapsed date accrued - the same transaction closes instead (outcome `CLOSED`) *(as built by `P11-TSK-023`: a second key on a `CLOSING` line is answered with the one request)* | `409 lending.LoanClosed`, `409 lending.LineNotClosable` (an instalment loan) | `LineClosureRequested` | — |
| `ACTIVE → CLOSED` · `CLOSING → CLOSED` | S — the transaction that zeroes the last receivable: a repayment, a payoff, a credit-balance application or a waiver; the line also at a closure request with nothing outstanding *(as built by `P11-TSK-023` for a line: the customer's repayment, its automatic collection, or the servicing step after rule 6 at a statement - an `ACTIVE` line paid to zero stays open)* | L3; the six balances derived from journal lines under it; any credit balance refunded to the wallet in the same transaction | — | `LoanClosed` (`REPAID`, `PAID_OFF_EARLY`, `CLOSED_BY_CUSTOMER`) | — |

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

*As built by `P11-TSK-012` (2026-10-11): `lending V006`'s `loan` - identity, product and its kind (a `CHECK` ties them),
status, currency, the offer and decision by value (`UNIQUE (offer_id)`, `UNIQUE (decision_id)`), the database's stamps
and the terminal reason columns with their `CHECK`s; born by kind under the offer's lock for its own party's `OFFERED`,
unexpired offer, identity frozen, and **no edge admitted yet** - the edge function refuses every move until `-014`,
`-023` and `-026` replace it beside their facts, and no `UPDATE` is granted. `loan_event` is the history (one birth row
here). Committed only beside its offer's `ACCEPTED` edge, its agreement v1, its evidence and (a loan) its disbursement;
a line never has one.*

*As built by `P11-TSK-014` (2026-10-12): `lending V008` replaces the edge function - `PENDING_DISBURSEMENT -> ACTIVE`
only beside the disbursement `POSTED`, `-> CANCELLED` only beside it `FAILED` with the same reason (the closing instant stamped by
the trigger); every other edge still refused until `-023`/`-026`; a deferred check holds an instalment loan's `ACTIVE` edge to its
disbursement `POSTED` and, on path W, its accrual start and schedule v1. The application role holds `UPDATE (status,
cancellation_reason)`. History reasons `DISBURSED`, `STANDING_LOST`, `DISBURSEMENT_FAILED`; events `lending.LoanDisbursed`,
`lending.LoanCancelled`.*

*As built by `P11-TSK-018` (2026-10-12): `lending V014` replaces the edge function to admit `ACTIVE -> CLOSED (REPAID)` for an
instalment loan, only beside its `loan_closure` row born in the same transaction (one per loan, refusing while any instalment of
the latest schedule is unbilled or any billed component unpaid); a deferred check holds every closure to its loan `CLOSED` at
commit; the application role holds `UPDATE (closure_reason)`, the closing instant the trigger's. The closing transactions are a
repayment and the servicing step after a credit-balance application (`LoanClosures`, under L3, the refund after L7); `PAID_OFF_EARLY`
(`-026`) and a line's closure (`-023`) replace both functions beside their facts. History reason `REPAID`; event
`lending.LoanClosed`.*

*As built by `P11-TSK-026` (2026-10-13): `lending V022` replaces the edge function and the closure trigger once more - an
instalment loan's `ACTIVE -> CLOSED` admits `PAID_OFF_EARLY` beside its `loan_closure`, and the closure trigger admits
`PAID_OFF_EARLY` only beside the loan's `payoff_execution` (and `REPAID` never beside one), naming the payoff's `PAYOFF`
repayment or none; a line's payoff closes it `CLOSED_BY_CUSTOMER` through `-023`'s own edges, its `line_closure_request`
(outcome `CLOSED`) born by the payoff when the line is `ACTIVE`. The closing transaction is T-pay (section 4.10) - through
the one `LoanClosures` (`closeOnPayoff`: the same settlement - every receivable zero, every elapsed date accrued - the same
refund under `lending.closure-refund:<loan>`). History reasons `PAID_OFF_EARLY`, `CLOSED_BY_CUSTOMER`; event
`lending.LoanClosed`.*

### 4.6 Disbursement (`lending.loan_disbursement`) — `P11-TSK-014`

`PENDING → POSTED | FAILED`, born `PENDING` once per instalment loan in T-acc (`UNIQUE (loan_id)`),
moved only beside the loan's own edge in the same transaction: `POSTED` with `PENDING_DISBURSEMENT →
ACTIVE`, `FAILED` with `→ CANCELLED`. A wallet that cannot be posted rolls back to a savepoint and is
retried on the sweep's permit until the deadline; ten disbursers find one `PENDING` row, one entry
(`lending.disbursement:<loan>`, scenario 1); a crash after acceptance is re-driven by the sweep. The
receivable is born exactly with the wallet credit (`INV-LND-07`). **Invalid:** anything out of
`POSTED` or `FAILED`; either without the loan's matching edge.

*As built by `P11-TSK-012` (2026-10-11): born `PENDING` in T-acc beside its instalment loan `PENDING_DISBURSEMENT`;
the database stamps `created_at`, `deadline_at` (the pinned terms version's `disbursement_deadline_hours`) and the sweep's
permit `next_attempt_at` (due at once); `payout_method_id` present exactly when the application preferred an external
account. It does not move, and no `UPDATE` is granted, until `-014`.*

*As built by `P11-TSK-014` (2026-10-12): `lending V008` - identity, path, birth and deadline frozen; a permit-only move
(the sweep's claim, path W only - `payout_method_id IS NULL` - until `-015`); `PENDING -> POSTED` with the journal entry by value
(`UNIQUE`) and its value date (held to this transaction's business date in the pinned zone), `PENDING -> FAILED` with the
cancellation's reason - `STANDING_LOST` at any time, `DISBURSEMENT_FAILED` only once `deadline_at <= statement_timestamp()`, so
the deadline is the database's for every writer; either only while the loan is `PENDING_DISBURSEMENT`, and a deferred check
holds each beside the loan's matching edge. Path X's `POSTED` is refused until `-015`. Refinement: "retries stop and the loan is
cancelled at `<=`" reads, as built, "the attempt that fails at or after the deadline cancels" - a wallet that can be posted is
still disbursed after an outage longer than the deadline.*

### 4.7 Loan payout (`lending.loan_payout`), path X only — `P11-TSK-015`

```
 (born in T-dis, hold placed) PENDING ──claimed──> DISPATCHING ──payments accepted the withdrawal──> DISPATCHED ──> PAID_OUT | FAILED
                                                       └──unroutable, or refused by payments: the hold released──> NOT_DISPATCHED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PENDING` | S, in T-dis | under L3, L5; **L7** wallet and a ledger hold of the net principal; `UNIQUE (loan_id)` | — | — | — |
| `PENDING → DISPATCHING` | S, `LoanPayoutSchedule` (T-pyo a) | the payout's conditional claim with a permit, committed **before** payments is called | — | — | — |
| `DISPATCHING → DISPATCHED` | S (T-pyo b, c) | `LoanPayouts.dispatch` — payments' system-actor withdrawal on the recorded instruction, key `payments.withdrawal:lending:<loanId>`, adopting the hold (payments' own transactions); then L3, L5, payments' reference stored. A crash anywhere re-drives under the same key, and payments replays | — | — | — |
| `DISPATCHING → NOT_DISPATCHED` | S, when payments refuses or cannot route the withdrawal (met after the claim) | L3, L5, L7: the hold released, the funds free in the wallet; accrual start and schedule v1 set | — | `LoanPayoutConcluded` | — |
| `DISPATCHED → PAID_OUT \| FAILED` | S (T-pyr), on payments' terminal outcome — `COMPLETED` concluded from an authenticated answer (ADR-0083), or its failure | L3, L5; payments' row **read** plain through `app`, never locked; accrual start = payments' conclusion instant, schedule v1 generated (A30) | — | `LoanPayoutConcluded` | — |

`UNKNOWN` at payments is `DISPATCHED` here — held, no interest — until payments concludes by inquiry;
lending never re-dispatches (scenario 5, `INV-RAIL-02`), and a payout outcome never creates,
duplicates or undoes the loan's receivable (`INV-LND-07`): on `FAILED` or
`NOT_DISPATCHED` the money is the borrower's, in their wallet. No account identifier enters lending,
only payments' opaque destination reference (`INV-RAIL-03`). **Invalid:** anything out of the three
terminals; `PENDING → DISPATCHED | PAID_OUT | NOT_DISPATCHED`; `DISPATCHING → PENDING`; `DISPATCHED →
NOT_DISPATCHED`; `DISPATCHED` without payments' reference. Payments' withdrawal machine has no return
state (`DISPATCHED → COMPLETED | FAILED | UNKNOWN`), so `RETURNED` was to be mapped from payments' return
record of a `COMPLETED` withdrawal - which has no producer (the refinement below). *(The diagram, the
terminal edge's row and the money sentence above read `PAID_OUT | FAILED | RETURNED` and "on `FAILED`,
`RETURNED` or `NOT_DISPATCHED`", and "the four terminals", until `P11-DOC-001`, 2026-10-15: the terminals
are `PAID_OUT`, `FAILED` and `NOT_DISPATCHED` - `PayoutStatus`, `lending V009`'s `CHECK`.)*

*As built by `P11-TSK-015` (2026-10-12): `lending V009` - `loan_payout` born `PENDING` in path X's T-dis beside the loan
`ACTIVE` and its disbursement `POSTED` (L7 the wallet's row before the posting, then a hold of `P - f`); a deferred check
holds the `ACTIVE` edge to the payout's birth. The claim (one statement, `FOR UPDATE SKIP LOCKED`) moves `PENDING →
DISPATCHING` and re-stamps the permit of lapsed `DISPATCHING` and due `DISPATCHED` rows; every later step acts only on
the permit its claim stamped. `DISPATCHING → DISPATCHED` names payments' withdrawal (stamped `dispatched_at`, due for an
observation at once); `DISPATCHING → NOT_DISPATCHED` names its reason (`NO_ELIGIBLE_RAIL` - payments' recorded routing
refusal; `DESTINATION_UNAVAILABLE` - the method no longer the borrower's own active bank account; `CUSTOMER_UNAVAILABLE`
- no active customer) and the hold is released in that transaction; `DISPATCHED → DISPATCHED` stamps an observation
(`last_observed_at`) and nothing else; `DISPATCHED → PAID_OUT | FAILED` carries payments' conclusion instant, held by the
trigger within `[created_at, statement_timestamp()]` - and on a database clock stepped back behind the payout's birth,
where that interval is empty, at the birth: `GREATEST(LEAST(instant, statement_timestamp()), created_at)`, the order of
facts winning over the clock's reading (`lending V019`, `P11-TSK-033`; ADR-0063 decision 2); `NOT_DISPATCHED` is
stamped `GREATEST(statement_timestamp(), created_at)`. A terminal payout commits only beside its accrual start
(`PAYOUT_CONCLUDED`, the conclusion's business date in the pinned zone) and schedule version 1 (a deferred check).
**Refinement: `RETURNED` is not a state.** Payments keeps no return record for an instant withdrawal - the instant rail
is final on acceptance (`INV-REV-03`) and recalls and return windows are deferred by ADR-0062 - so a `RETURNED` edge
would have no producer (every state has one, ADR-0044). Funds that come back after `PAID_OUT` arrive as payments' and
Phase 8's own facts (an inbound credit, a parked confirmation) and the payout stays `PAID_OUT`, the loan untouched
(`LoanPayoutDatabaseTest#aReturnLandsInTheWalletAndTheLoanIsUnaffected`); the state arrives with a payments return
capability, if ever.*

### 4.8 Repayment (`lending.repayment`) — `P11-TSK-018`, `-019`, `-026`

Born `ALLOCATED` — a repayment row exists only once its money moved, with its allocations
(`repayment_allocation`, a deferred trigger asserting Σ allocation = amount) and its entry
(`lending.repayment:<id>`, rule 5) — and `→ REVERSED` only through an approved repayment reversal
(§4.9). A failed collection is a `collection_attempt`, never a repayment.

| Birth | Actor & door | Locks / arbiters | Refusals | Event |
|---|---|---|---|---|
| Customer repayment | C, step-up, `POST /v1/me/loans/{id}/repayments`, key `lending.repayment:CUSTOMER:<id>` (T-rep) | the claim; L3 `ACTIVE`/`CLOSING`; **L7**, available ≥ amount (`AvailableBalance.underLock`); allocation derived under L3 (`INV-LND-04`); L8 | `422 lending.InsufficientFunds`, `409 lending.LoanNotRepayable`; standing is **not** checked (a suspended customer may repay) | `RepaymentReceived` (+ `LoanClosed` if it closed) |
| Auto-collection | S, `LoanServicingSchedule` (T-svc) on the due date, retried daily while past due (A21) | L3; `collection_attempt UNIQUE (due_item_id, attempt_date)`; L7 | nothing collected → the attempt, no repayment | `RepaymentReceived` / `CollectionFailed` |

*(As built by `P11-TSK-019`, 2026-10-13 - the collection step of T-svc (`LoanCollections`), once the accrual has caught up
and after the billing and its credit-balance application, under the same L3, when the governing agreement's pinned terms
collect automatically (the repayment method the wallet): every billed item due on or before today not yet attempted today,
oldest first - an item on its due date always (one settled already records `NOTHING_DUE`), a past-due one while it has
something due. Per attempt the due state is re-derived under L3; (L7) the existing wallet's row once a step - none, or not
`ACTIVE`, is `WALLET_NOT_POSTABLE` - and `AvailableBalance.underLock` re-read per attempt. The amount is what the item has
due, or as much of it as is available where the pinned terms allow partial collection (`PARTIAL`); otherwise nothing
(`INSUFFICIENT_FUNDS`). Oldest first: once an item is not collected in full, every younger item that day is
`INSUFFICIENT_FUNDS` - a younger collection would be allocated to the older one. Money moves only through `Repayments`' one
posting half (kind `AUTO_COLLECTION`, the key `<item>:<attempt date>`, rule 5, every line on that item - never the credit
balance); a collection that settles the loan closes it in the step (its attempt the one `lending V015` admits on the loan
it closed). Each attempt is one `collection_attempt` row; `lending.CollectionFailed` (the item, the outcome) when nothing
could be taken from an item with something due. A missed business date (an outage) is not back-filled: the next step makes
that date's one attempt. A closed loan is never serviced, so never collected.)*
| Payoff | §4.10 *(as built by `P11-TSK-026`: kind `PAYOFF`, the request key its quote, admitted only beside its `payoff_execution`, for either kind - `lending V022`'s repayment trigger; none when the credit balance paid everything)* | | | `RepaymentReceived`, `LoanClosed` |

The same key replays one answer (scenario 2); two keys serialise on L3, each allocating against what
it finds (scenario 3). The allocation order is the pinned terms' (L6): oldest due first, fees →
interest → principal; the remainder held as credit (loan) or paying down drawn principal (line, A26).

*As built by `P11-TSK-018` (2026-10-12): `repayment` carries no status - every repayment reads `ALLOCATED` until `-020`'s
approved reversal derives `REVERSED`; `UNIQUE (loan_id, kind, request_key)` is the arbiter behind a lapsed claim; a wallet absent
or not `ACTIVE` is `422 lending.InsufficientFunds` (ADR-0095 point 4); an `ACTIVE` instalment loan only - a line answers `409
lending.LoanNotRepayable` until `-023`. Read: `GET /v1/me/loans/{id}/repayments`, owner-scoped.*

### 4.9 Servicing proposals — repayment reversal, waiver, amendment — `P11-TSK-020`, `-025`, `-027`

```
 repayment reversal, waiver:  PROPOSED ──★ approved: posted──> APPROVED        PROPOSED ──rejected──> REJECTED
 amendment:                   PROPOSED ──★ approved──> APPROVED ──customer accepts──> ACCEPTED   (agreement and schedule v n+1)
                                 └──rejected──> REJECTED     └──declined · its window expired · the agreement version moved──> LAPSED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `LOAN_SERVICE`, `POST /v1/operator/loans/{id}/waivers` · `…/repayments/{rid}/reversal` · `…/amendments`, key `lending.servicing:CUSTOMER:<id>` *(`CUSTOMER`-typed for an operator session - §4.1's note; read `EMPLOYEE` until A5-04)* | L3; one live proposal per subject | `422 lending.ReasonRequired`, `409 lending.ProposalPending`, `422 lending.WaiverExceedsDue`, `409 lending.LoanClosed`, `lending.SelfDealingRefused` | — | `lending.WaiverProposed` / `RepaymentReversalProposed` / `AmendmentProposed` |
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

*As built by `P11-TSK-020` (2026-10-13) - the repayment reversal: `lending V016`'s `repayment_reversal` is the machine
row (money-free - the amount is the repayment's - so its decision columns take a column-scoped `UPDATE` under the edge
trigger), with `repayment_allocation_reversal` the negating rows. The proposal takes L3 only; the rejection L3 then L5
(any holder of `LOAN_SERVICE`, the proposer's withdrawal included, on a closed loan too); the approval L0, L3, L5, L6 when
the repayment paid principal, L7 (the wallet the repayment debited), then `ReversalService`. Each decision names its
`reversalId`, so an approver decides exactly the proposal reviewed. Refused, nothing written and the proposal still
`PROPOSED`: `409 lending.LoanClosed` on any loan not `ACTIVE` (the closing repayment and every earlier one), `409
lending.RepaymentNotReversible` for a repayment already reversed or whose held credit has since been applied (the exact
swap would drive the credit balance below zero), `422 lending.CapitalUnavailable`, `409 lending.WalletUnavailable` (the
wallet not `ACTIVE`), `403 lending.SelfDealingRefused` (recorded `FAILED`) at every act. The interest the re-opened
principal would have earned between the repayment and its reversal is not recharged (A10); it accrues from the reversal's
value date.*

*As built by `P11-TSK-025` (2026-10-13) - the waiver: the proposal carries money, so it is INSERT-only for every role -
`lending V021`'s `waiver` (born `PROPOSED`, no status column) and `waiver_decision` (born once - `UNIQUE (waiver_id)` - its
outcome `APPROVED` or `REJECTED`); the status is derived (no decision: `PROPOSED`), the four eyes a `CHECK` over the
proposer copied into the decision and pinned by the composite key `(waiver_id, proposed_by)` (P11-TSK-004's capital pattern,
not -020's column-scoped `UPDATE`, which only a money-free row may take). So "L5" is the proposal read under L3 with its
decision's unique beneath - no row lock exists to take. The proposal takes L3 only; the approval L3, then **L7 (the
borrower's existing wallet) before any posting** - its rules post to a shared income account, and a closure's refund must
never take the wallet after them - then rule 8 (`ReversalService` of the fee's rule 4 entry), 8a or 9 under
`lending.waiver:<waiver>`, then the closure when the account is settled (`REPAID`, or a `CLOSING` line
`CLOSED_BY_CUSTOMER` - the existing edges); the rejection L3 (any holder of `LOAN_SERVICE`, the proposer's withdrawal
included, on a closed account too). The approval names the waiver by the path. One waiver of a kind on an item awaits a
decision at a time (`409 lending.ProposalPending`, the trigger beneath L3); the bound - what is due now, from the rows the
ledger is proven against - is judged at the proposal and again at the approval (`422 lending.WaiverExceedsDue`, the
proposal still `PROPOSED`, nothing posted) and once more by the decision trigger, which takes L3 itself, for every writer.
An interest waiver releases billed interest only (`LOAN_INTEREST_DUE`); accrued interest not yet billed is never waived.*

*As built by `P11-TSK-027` (2026-10-14) - the amendment: `lending V023`'s `loan_amendment` (the proposal - the agreement
version it amends, a rate never above it, the remaining instalments never fewer than the current schedule has left, the
arrears flag, the SHA-256 of the document the customer is shown, the screened reason) and `loan_amendment_decision` (born
once, the four eyes a `CHECK` over the proposer copied and pinned by the composite key - the waiver's pattern; an approval
stamps the offer's `expires_at` from the pinned offer validity on the database clock), both INSERT-only; the acceptance is
agreement version n+1 naming the amendment (`UNIQUE (amendment_id)`), never a status column. **Every status is derived**:
no decision `PROPOSED`; an agreement version naming it `ACCEPTED`; a rejection `REJECTED`; and `LAPSED` - from either
`PROPOSED` or `APPROVED` - once the agreement version it amends is no longer the loan's, the loan is no longer `ACTIVE`,
or (approved) its offer expired; no sweeper writes a lapse, and a customer's decline is simply letting it lapse (no decline
door). One amendment of a loan is open at a time (`PROPOSED`, or `APPROVED` and unexpired; `409 lending.ProposalPending`,
the trigger beneath L3). The proposal and the decisions take L3 and read the proposal under it (no row lock exists to take);
the approval is refused `409 lending.AmendmentLapsed` once the agreement moved or the loan closed; the rejection is
`PROPOSED`-only (a lapsed amendment is already terminal). The acceptance (C, `MULTI_FACTOR`, the body echoing the
document's hash - `409 lending.TermsChanged` otherwise) takes L3 owner-scoped, reads the amendment under it, accrues every
elapsed date and bills everything due (the servicing step's own accrual and billing), then writes agreement v n+1
(canonical terms **format 3**, the amended agreement's own self-contained bytes), its evidence (`loan_acceptance`, the
amendment's document hash, no offer), rule 13 and one `loan_amendment_rescheduling` row per past-due item re-scheduled,
and schedule v n+1 - two deferred checks hold the agreement beside its evidence and its schedule, and the schedule
continuing the open period. The customer reads only an amendment ever approved (a `PROPOSED` one is `404`).*

*The lapse crossed during the acceptance's work (`P11-TSK-035`, 2026-10-15; found by `P11-TSK-034`'s gate - the offer's
race, on the amendment): the acceptance judges the amendment's offer unexpired at its read under L3 and then accrues,
bills and plans - waiting, among others, on the loan's ledger accounts and the shared interest income row - so the
database clock may pass the decision's `expires_at` before agreement v n+1 is born. The version's trigger re-judges the
expiry at the INSERT and refuses it - the strict backstop: no version is born for an amendment lapsed at that instant -
but it refused in the generic `P0001` every breach of the trigger shares, and the customer got `500`. `lending V027`
gives that refusal its own SQLSTATE, `LNAEX`, judged **last** - after the loan `ACTIVE`, the amendment the loan's own
and `APPROVED`, the version conditional and "never more credit" (a breach is refused `P0001` even when the offer has
expired); the acceptance maps exactly `LNAEX` to `409 lending.AmendmentLapsed` and writes nothing, the catch-up rolled
back with it, the amendment then derived `LAPSED`. Every other refusal of the version's birth is an invariant breach and
stays a `500`.*

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
settlement "rebate" zero by construction. Partial prepayment (`-028`, built, `lending V024` - *"the cut
candidate" until `P11-DOC-001`, 2026-10-15*) is a
repayment kind with agreement and schedule v n+1, refused while past due (`409 lending.ArrearsFirst`).

*As built by `P11-TSK-026` (2026-10-13) - `lending V022`, `Payoffs`. **The quote** (the claim; **L3** owner-scoped; a key
retried converges, another good-through date under it `api.Conflict`; an `ACTIVE` instalment loan with an accrual start or
a line `ACTIVE`/`CLOSING`, else `409 lending.LoanNotRepayable`; the database clock after the lock; `G` today up to the
pinned horizon, else `422 api.ValidationFailed`): every elapsed date accrued first under the servicing step's own uniques
and keys (rule 2 - a quote writes accruals), then `payoff_quote` born with the five receivables and the credit balance
from journal lines, the base, the dates `[today, G - 1]` and the interest `ACCRUAL_ENGINE_V1` projects for them chained onto
the latest accrual, the versions and the outstanding (its parts, `CHECK`ed; negative when the credit balance exceeds
everything owed - nothing to pay, the excess refunded). **The execution** (T-pay, in this order: the claim; **L3**; an
execution of the same quote converges, of another `LoanNotRepayable`; the quote read plainly - immutable, so no L5 lock,
`payoff_execution UNIQUE (quote_id)` and `UNIQUE (loan_id)` arbitrate beneath L3; today `G` and the agreement version the
quote's, else `409 lending.PayoffQuoteStale`; **`payoff_execution` born first** - it alone admits the early billing, the
`PAYOFF` repayment and the `PAID_OFF_EARLY` closure, and a deferred check holds it to its loan `CLOSED` at commit; rule 2
through `G - 1`; rule 3 for every unbilled instalment whatever its due date - a line's current cycle stated early - each with
rule 6; the outstanding recomputed from journal lines - different, `PayoffQuoteStale`, everything rolled back; a line's
closure request born; **L7** the wallet, after the step's own postings as in T-svc, `ACTIVE` and available at least the net
amount, else `422 lending.InsufficientFunds`; **L8** and rule 5 - one repayment of kind `PAYOFF`, keyed by the quote, of
everything receivable, allocated to every component; the closure). No standing check, no L0, no L6, no audit record; any
condition.*

*As built by `P11-TSK-028` (2026-10-14) - `lending V024`, `Prepayments`. **The prepayment** (C, `MULTI_FACTOR`, `POST
…/prepayments`, key `lending.prepayment:CUSTOMER:<id>`): **L3** owner-scoped (the agreement read after the lock); a key
retried converges, another amount under it `api.Conflict`; an `ACTIVE`, scheduled instalment loan, else `409
lending.LoanNotRepayable` (a credit line's repayment already pays its principal down); the database clock after the lock;
the servicing step's own catch-up (accrual, billing, credit-balance application - so a billing on the same business date
always comes first); then `AllocationEngineV1.allocatePrepayment` over the due state derived under L3 - anything billed still
owing, past due or due today, `409 lending.ArrearsFirst`; an amount reaching the principal not yet due `422
lending.PrepaymentExceedsPrincipal` (the whole is the payoff's - a prepayment never closes the loan); schedule v n+1 by
`ScheduleEngineV1.recalculateAtInstalment` from the open period over `LOAN_PRINCIPAL` less the amount - the SAME level
instalment over the least term, never longer than what the superseded schedule had left (only when late payments left more
principal than that instalment can amortise in the remaining term is the term kept and the instalment the engine's); **L7**
the wallet and its available balance; rule 5 (`DR CUSTOMER_WALLET / CR LOAN_PRINCIPAL`, one `NOT_DUE_PRINCIPAL` line) through
`Repayments`' one posting half, kind `PREPAYMENT`; agreement v n+1 - a third origin, `prepayment_id`: everything of version n
but the term (instalments billed plus the new schedule's), canonical terms format 4, no acceptance evidence (a right the
agreement grants, exercised by the customer's own keyed command) - and schedule v n+1, billed instalments kept, unbilled
superseded; `lending.RepaymentReceived` (kind `PREPAYMENT`) and `lending.LoanAmended` (origin `PREPAYMENT`). No fee (L8).
A pending amendment of version n lapses and a payoff quote made under it is stale, both by derivation; a prepayment is never
reversed (`RepaymentNotReversible`, `lending V024`) - its correction is an amendment. Beneath every writer, `lending V024`
refuses a schedule version superseding an instalment already due and unbilled - the billing first, an amendment's too.*

### 4.11 The born-once facts (not machines)

| Fact | Table | Born | Once by |
|---|---|---|---|
| Agreement version | `loan_agreement` | acceptance (v1); accepted amendment or prepayment (v n+1) | `UNIQUE (loan_id, version)`; `INSERT`-only for every role; `terms_sha256` re-verified by replay |
| Acceptance evidence | `loan_acceptance` | acceptance; amendment acceptance | one per accepting act |
| Schedule version · instalment | `repayment_schedule`, `loan_instalment` | the accrual start; an accepted change | `UNIQUE (loan_id, schedule_version)`; `UNIQUE (schedule_id, sequence)` |
| Accrual start | `accrual_start` | T-dis (path W) / the payout's terminal outcome - T-pyr, or T-pyo's `NOT_DISPATCHED` (path X, `P11-TSK-015`) | `UNIQUE (loan_id)`; no row = interest has not started |
| Interest accrual | `interest_accrual` | T-svc, per elapsed date, oldest first | `UNIQUE (loan_id, accrual_date)` + ledger key (`INV-LND-02`, scenario 4) |
| Instalment billing · credit-balance application | `instalment_billing`, `credit_balance_application` (+ `credit_balance_allocation`) | T-svc at the due date, after every accrual through `Dj − 1`, oldest first; after the final due date each post-maturity month in which interest accrued, interest only (`P11-TSK-017`) | `UNIQUE (instalment_id)`, `UNIQUE (loan_id, due_date)` + ledger key `lending.billing:<instalment>`; `UNIQUE (billing_id)` + `lending.credit-application:<billing>`; never before the due date, interest exactly the period's accruals, no accrual into a billed period, no billed component over-paid (`lending V012`, every writer) |
| Draw | `credit_line_draw` | C, `MULTI_FACTOR`, T-drw: L3 `ACTIVE`, draws not suspended, available limit derived under L3, L6 headroom, the row born whole after its posting (`P11-TSK-021`) | `UNIQUE (loan_id, draw_key)` + `UNIQUE (journal_entry_id)` + ledger key `lending.draw:<drawId>`; `422 lending.LimitExceeded` (`INV-LND-14`), `409 lending.DrawsSuspended`, `422 lending.CapitalUnavailable`, `409 lending.LineNotDrawable`, `409 lending.WalletUnavailable` |
| Repayment · allocation | `repayment`, `repayment_allocation` | C, the door's claim, T-rep: L3 `ACTIVE` instalment loan, the due state derived under L3 and explained by the ledger, L7 and the available balance, rule 5, the rows born after the posting (`P11-TSK-018`) | `UNIQUE (loan_id, kind, request_key)` + `UNIQUE (journal_entry_id)` + ledger key `lending.repayment:<id>`; the rows sum to the amount and no billed component is over-paid, at commit (every writer) |
| Closure | `loan_closure` | the transaction that zeroes the last receivable - a repayment, or the servicing step after a credit-balance application - with every elapsed date accrued; the credit balance refunded (`P11-TSK-018`) | `UNIQUE (loan_id)` + ledger key `lending.closure-refund:<loan>` + the conditional edge; only beside the loan `CLOSED` at commit |
| Statement | `credit_line_statement` | T-svc at the cycle end, after every accrual of the cycle, oldest first, contiguous from the opening; the credit balance applied at it; collected on its due date when the pinned terms say so (`P11-TSK-022`) | `UNIQUE (loan_id, cycle_end)`, `UNIQUE (loan_id, due_date)` + ledger key `lending.statement:<line>:<statement date>`; never before the statement date, interest exactly the cycle's accruals, no accrual into a stated cycle; `UNIQUE (billing_id)` + `lending.credit-application:<statement>` |
| Line closure request | `line_closure_request` | `ACTIVE → CLOSING` | `UNIQUE (loan_id)` |
| Collection attempt | `collection_attempt` | T-svc after the billing, once caught up, when the pinned terms collect automatically: one per billed item and business date, whatever happened (`P11-TSK-019`) | `UNIQUE (due_item_id, attempt_date)` + `UNIQUE (repayment_id)` + the repayment's `UNIQUE (loan_id, kind, request_key)` under the key `<item>:<date>`; the trigger holds the authority, the date, the figures and the repayment; an `AUTO_COLLECTION` repayment without its attempt refused at commit |
| Fee assessment | `fee_assessment` | T-svc, after the condition step, on the day the billed item's own age reaches `late_fee_day` (> grace) while it owes interest or principal - a missed day caught up while it still owes; rule 4 under `lending.fee:<item>:LATE` (`P11-TSK-025`) | `UNIQUE (due_item_id, kind)` + ledger key; capped per account (the sum never above the pinned cap - a fee the cap partly admits charged at what it admits); the trigger takes L3 and holds the day, the fee, the cap, the owing item and a line's cycle (every writer) |
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

*(As built by `P11-TSK-024`, 2026-10-13 - `lending V020`, `LoanConditions`, ADR-0098's Follow-up: the condition is derived
live for every decision - a line's draw under L3, the customer's and operator's reads in their snapshot - and recorded by
T-svc's condition step, after the closure step on an account the step did not close, once caught up, **at most once a
business date** (the end-of-day position: an item unpaid at the end of its due date is past due at the next date's first
step); a cure a repayment brings after that date's change is the next date's row. The default flag's memory is the latest
`DEFAULT` row: set at the threshold, held while anything is past due, cleared at cure. A partial payment leaves its item past
due - DPD moves only when the oldest past-due item is paid in full. `lending V020`'s trigger holds every row to the
derivation for every writer.)*

---

## 6. The ranks, per machine

*(Headed "(planned)" until the Phase 11 exit review, `P11-DOC-001`, 2026-10-15, which read each row
against the migrations: every rank below is built.)*

| Machine | Table (task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Terms version | `loan_terms_version` + its INSERT-only term set `loan_terms` (`-005`) | `loan_terms_version_event` | one `PROPOSED` / one `ACTIVE` per product; four-eyes `CHECK`; content frozen from insert; retirement only beside a successor (deferred); namespace `11` |
| Capital contribution | `capital_contribution` (`-004`) | `capital_contribution_event` | four-eyes `CHECK`; `UNIQUE (expectation_ref)`; namespace `11` |
| Loan application | `loan_application` (`-010`) | `loan_application_event` | `UNIQUE (decision_request_id)`; born `SUBMITTED` on the `ACTIVE` terms version and an offered day, never committed `SUBMITTED` (deferred); one open per party and product (partial unique); `→ OFFERED` only beside its offer (`-011`) |
| Loan offer | `loan_offer` (`-011`) | `loan_offer_event` | `UNIQUE (application_id)`; the complementary expiry and acceptance clock conditionals; `→ ACCEPTED` only beside its loan; canonical terms and hash frozen |
| Loan | `loan` (`-012`) | `loan_event` (§8; *"its edge history (§8, open)" until `P11-DOC-001` - §8 settled it*) | `UNIQUE (offer_id)`; kind frozen; kind-specific edges; `→ ACTIVE` / `→ CANCELLED` only beside the disbursement's matching state; closure reason `CHECK` |
| Disbursement | `loan_disbursement` (`-012`, moved `-014`) | — | `UNIQUE (loan_id)`; moves only beside the loan's edge |
| Loan payout | `loan_payout` (`-015`) | — | `UNIQUE (loan_id)`; payments' reference required from `DISPATCHED` |
| Repayment reversal · waiver · amendment | `repayment_reversal` (`-020`), `waiver` and its born-once `waiver_decision` (`-025` - a money row takes no `UPDATE`), `loan_amendment` and its born-once `loan_amendment_decision` (`-027`, the status derived, never written) | the reversal's decided columns; a waiver's and an amendment's decision row | four-eyes `CHECK`; amendment acceptance conditional on the agreement version |

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
  payments' alone. *(As built by `P11-TSK-015`, read by `P11-DOC-001`, 2026-10-15: payments keeps no
  return record for an instant withdrawal, so `RETURNED` has no producer and is not a state - §4.7's
  refinement.)*
- **Capital recognition** (`-004`): derived, never a lending-written edge — read through `app`
  (§4.2); capital correctness never depends on it.
- **One live reversal proposal per repayment** (`-020`): a partial unique over
  `PROPOSED`/`APPROVED`, so a rejected proposal can be proposed again.
- **The loan's edge history** (`-012`): `loan_event`, as for the other machines.
- **Where `TermsNotAmortising` is judged**: at the terms proposal (`-005`), for terms that cannot
  amortise at their declared bounds (wired by `-006`: the schedule engine against credit's bounds,
  exact over every real calendar, before anything is locked); a specific offer that still would not
  amortise closes its application `CLOSED_UNDECIDED (TERMS_NOT_AMORTISING)` with no offer (`-011`).
- **The terms an offer pins** (`-011`): the version `ACTIVE` at the offering instant, read `FOR
  SHARE`; the version recorded on the application is informational, so a retirement between
  application and decision changes nothing already pinned and the offer takes the active one.
