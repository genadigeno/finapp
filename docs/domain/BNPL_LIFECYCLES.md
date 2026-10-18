# BNPL Lifecycles

*Written by the Phase 11 → 12 transition (2026-10-16); nothing here is built; each task corrects its
machine to the code.* No `bnpl` module, schema, table, trigger, door or sweep exists yet, and neither does
`creditmath`: every machine, edge, lock, refusal, event and audit act below is the decided design, and the
task named beside it corrects this document where the code teaches otherwise. The Phase 12 exit review
(`P12-DOC-001`) reads the whole of it against the code, statement by statement.

Written on the `LENDING_LIFECYCLES.md` precedent (itself on `CREDIT_DECISIONING_LIFECYCLES.md`'s): the
document that names a phase's machines is written before the phase's first task. The engineering plan is
[`PHASE_12_PLAN.md`](../project/PHASE_12_PLAN.md) (§3 ownership, §5 lifecycles, §7 concurrency, §12 the
model, §24 the settled questions) — where this document and the plan disagree, the plan governs until the
building task corrects one of them; §8 below lists every point where this document had to read the plan
one way, for the task to confirm. The invariants are `INV-BNPL-01`…`10` (and `INV-MER-07`'s amendment) in
[`FINANCIAL_INVARIANTS.md`](FINANCIAL_INVARIANTS.md); the terms are in [`GLOSSARY.md`](GLOSSARY.md) §7b;
the module is [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md) §4 `bnpl`.

Related: ADR-0101 (the BNPL bounded context; `creditmath`; lending's `loan` not composed) · ADR-0102
(products, terms and the versioned agreement) · ADR-0103 (BNPL accounting) · ADR-0104 (funding from
lending capital; exposure version 3) · ADR-0105 (the refund policy and the merchant payable's third
bounded negative source) · ADR-0106 (disputes and the single refund bound) — all `Proposed` at the
transition, indexed in [`docs/adr/README.md`](../adr/README.md). The lending machines BNPL re-applies
the *shape* of — never the rows — are [`LENDING_LIFECYCLES.md`](LENDING_LIFECYCLES.md)'s.

---

## 1. The concepts, kept apart

| Kept apart | Why |
|---|---|
| **Purchase** vs **Order** vs **Payment** | The purchase is bnpl's financed commercial fact — session, merchant, gross, fee pin, the instant it was financed; the order is checkout's, born in the purchase's transaction with the financing entry as its `captured_entry_ref`; and there is **no payment**: no intent, no attempt, no rail, no clearing position (`INV-RAIL-04` would be violated by a "BNPL rail" that never settles — `PHASE_12_PLAN.md` §2.1) |
| **Merchant financing** vs **Merchant settlement** vs **Customer payment** | Financing is the platform's liability to the merchant born at purchase (`MERCHANT_PAYABLE` credited `G − f`, F1); settlement is `merchant`'s existing on-demand payout of that payable, unchanged; a customer payment does not happen at purchase at all — the customer owes the platform instead (`GLOSSARY.md` §2 Customer Payment / Merchant Settlement) |
| **BNPL application** vs **Decision request** | The application is bnpl's aggregate for one customer's choice of a BNPL product inside one checkout session; it *opens* exactly one credit decision request in its own transaction and stores only its id (owner decision B5: one decision per purchase, no spending limit). BNPL never reads a score, an attribute or a threshold, and the merchant sees only `ELIGIBLE / NOT_ELIGIBLE / PENDING` |
| **BNPL offer** vs **BNPL agreement** vs **Customer repayment obligation** | The offer is what the platform will finance for this purchase, expiring no later than the session; the agreement is the immutable, versioned contract (v1 at acceptance, v n+1 per schedule-changing refund); the obligation is the account — identity, product, status, permits, **no money column** (`INV-BNPL-01`) |
| **BNPL obligation** vs **Loan** | Not a lending `loan` of a new product (ADR-0101, `PHASE_12_PLAN.md` §3.2 alternative 3): its receivable is born with a merchant-payable credit, not a wallet credit; it is reduced by a third party's refund; P4 has no accrual and collects instalment 1 in its origination transaction. They share engines (`creditmath`) and capital (`LENDING_CAPITAL`), never a row |
| **Lifecycle state** vs **Condition** | A state is earned by a producer and walks a machine; delinquency, default, the **dispute hold** and late-fee eligibility are conditions — derived, recorded only as append-only changes. An `ACTIVE` obligation is `ACTIVE` whether current, 120 days past due or disputed; "partially refunded" is a derived condition of a `FINANCED` purchase, never a state |
| **BNPL instalment** vs **Billing** vs **Repayment** vs **Allocation** vs **Collection attempt** | The instalment is a projection row of a schedule version; the billing the born-once fact of what became due at its date (PN: interest actually accrued); the repayment money received from the wallet; the allocation its born-with split; a collection attempt a born-once record of one auto-collection try, never a repayment. A **Loan Instalment** is lending's and a different state (`MODULE_ARCHITECTURE.md` §5, "Instalment claimed by two modules") |
| **BNPL refund** vs **Refund adjustment** vs **Repayment reversal** vs **Waiver** | The refund is the merchant's (or a dispute outcome's) return of value on the purchase; the refund adjustment is its born-once per-instalment allocation, latest first, plus the excess to the customer; a repayment reversal undoes money *applied* (four-eyes, an operator's mistake); a waiver forgives a fee (four-eyes). None edits a figure |
| **BNPL dispute** vs payments' **Dispute / Chargeback** | A BNPL dispute is the customer contesting the purchase with the platform — no network, no chargeback on the financed amount; a chargeback can only arise on a card-funded wallet top-up, which is payments' dispute and unrelated to the agreement (`PHASE_12_PLAN.md` §12.7). A dispute resolved for the customer *is* a refund under the same bound (`INV-BNPL-08`) |
| **Merchant discount** vs **Late fee** vs **Interest** | The merchant discount is the merchant's fee, earned at purchase (`BNPL_MERCHANT_FEE_INCOME`, A9) and pro-rated back on refund; the late fee is the customer's, once per instalment (`BNPL_FEE_INCOME`); interest is PN's only, accrued daily (`BNPL_INTEREST_INCOME`). P4 charges the customer neither interest nor any fee but a late fee |
| **Capital headroom** vs **Exposure** | Headroom is the platform's undeployed `LENDING_CAPITAL` per currency, now shared by loans, lines and BNPL principal, judged under L6 (`INV-LND-13`, `INV-BNPL-10`); exposure is credit's per-party figure, version 3 adding the party's BNPL outstanding principal. BNPL has no committed-but-unfinanced state, so it adds outstanding principal only, from the purchase's commit |

## 2. When each act takes effect

| Act | Record | Ledger effect (`PHASE_12_PLAN.md` §12.11) |
|---|---|---|
| Application · offer | application, its history; offer and its canonical terms | **None.** Credit's reservation exists from the approval |
| Acceptance = the purchase | offer `ACCEPTED`, agreement v1, acceptance evidence, purchase `FINANCED`, merchant financing, obligation `ACTIVE`, schedule v1, the decision consumed, the session `COMPLETED` and checkout's order | **F1** `DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f` under `bnpl.financing:<purchase>`; P4 also **F2** (instalment 1 billed) and **F3** (instalment 1 repaid from the wallet). The receivable, the payable and the capital commitment are born in one commit (`INV-BNPL-02`) |
| Accrual (PN) · billing · late fee | born-once rows | F2a, F2, F4 |
| Repayment · auto-collection · credit-balance application · payoff | repayment + allocations; application; quote execution | F3, F3a, F6 |
| Refund (merchant, operator, or a dispute's outcome) | refund `APPLIED` + adjustment rows; schedule and agreement v n+1 when an unbilled instalment is cancelled or shortened | F5 (+ F5a releases) under `bnpl.refund:<refund>`; F9 is F5 |
| Waiver · repayment reversal | the approved proposal | F7 (`ReversalService` of F4) · F8 (exact swap of F3) |
| Merchant payout | `merchant`'s payout | F10 — merchant's own entry; BNPL posts nothing |
| Closure | obligation `CLOSED` (reason) | None of its own: the consequence of the entry that zeroed the last receivable, a credit balance refunded beside it (`bnpl.closure-refund:<obligation>`) |
| Write-off | — | F11, **deferred** (Phase 14); `BNPL_WRITE_OFF_EXPENSE` seeded, unposted |

---

## 3. Rules every machine shares

**Actors.** **C** — the customer, keyed per principal, owner-scoped (another party's id → `404`); a
`MULTI_FACTOR` session for the application, the acceptance and the payoff (A18; credit is created or an
obligation born); the platform's conditional step-up for a repayment, a quote and the application's
withdrawal. **M** — the merchant, over its API key (ADR-0052) with the BNPL scope the act needs (the refund
scope for a refund), tenant-isolated (`INV-MER-01`): another merchant's purchase or dispute is `404`, and
nothing a merchant reads carries eligibility reasons, scores, credit amounts, the customer's schedule,
repayments or delinquency. **P** — a person holding an operator permission (`BNPL_ADMINISTER`,
`BNPL_INVESTIGATE`, `BNPL_SERVICE`, `BNPL_SERVICE_APPROVE`, `BNPL_DISPUTE_RESOLVE`; identity `V022`), keyed
per employee, reasoned (`422 bnpl.ReasonRequired`, reasons screened for card and account numbers and
classified `RESTRICTED-PII`), **four-eyes** where marked ★ (domain and `CHECK`;
`403 bnpl.SelfApprovalRefused`, **audited `FAILED`** — the Phase 11 gate's R4 applied from the first door),
and never on their own party's obligation (`403 bnpl.SelfDealingRefused`, audited `FAILED`). **S** — the
system: a leaderless sweep's step or an in-transaction consequence. **No operator creates a purchase,
finances a merchant, cancels an instalment outside a refund or a dispute's resolution, or edits a
figure**; origination is never a person's act (a person's approval is credit's underwriting).

**The three ranks** (`INV-LIFE-01`, `-02`; the ADR-0044 doctrine as Phases 10 and 11 built it): **the
domain** — each act's conditional `UPDATE … WHERE status IN (the act's from-states)` under the row lock, so a
loser finds the row moved and writes nothing; **the database** — a hand-written every-writer trigger
`<table>_permits_only_machine_edges` admitting exactly the edges below, with the frozen columns and the
cross-table preconditions named per machine, and a status `CHECK` that is a membership list of the
machine's states; and **an append-only history** per machine (`<table>_event`). The application role is
granted `SELECT, INSERT` on fact tables and a conditional `UPDATE` only of status and permit columns on the
machine tables; never `DELETE`; every table carrying money is `INSERT`-only and migrator-owned
(`BnplSchemaHasNoMutableMoneyTest`, `INV-BNPL-01`). bnpl's migrations name no other schema: a precondition
that lives in credit, checkout, merchant or the ledger is the domain's, through `app`'s port, in the same
transaction (lending's A16 at the database).

**The lock order** (`PHASE_12_PLAN.md` §7.2; a planned `DISTRIBUTED_EXECUTION.md` §3 row, "The Phase 12 lock
order"). Every bnpl writer holding more than one takes an order-respecting subsequence of:

| Position | Row | Mode | Taken by |
|---|---|---|---|
| **L0** | the party's `credit.credit_profile` | `FOR UPDATE`, through credit's port (`BnplCreditDecisions.lockExposure`) | only transactions that raise the party's exposure — the acceptance and a repayment reversal — and then always first |
| **B1** | `bnpl_application` | `FOR UPDATE` | the offer step, withdrawal, closure |
| **B2** | `bnpl_offer` | `FOR UPDATE` | acceptance, decline, expiry |
| **B3** | the `checkout.checkout_session` row | `FOR UPDATE`, through `BnplCheckoutSessions` | **only T-pur**, and judged at once: a session not `OPEN`, or expired, is refused `409 bnpl.SessionNotPayable` before any further lock |
| **B5** | `purchase` | `FOR UPDATE` | a refund and every dispute act — the refund bound's arbiter (`INV-BNPL-07`, `-08`) |
| **B4** | `bnpl_obligation` | `FOR UPDATE` | every servicing act on one obligation — the serialisation point |
| **B6** | the act's own row | `FOR UPDATE` | dispute, waiver or reversal proposal, payoff quote |
| **L6** | the `LENDING_CAPITAL` ledger account of the currency (lending's row, through `BnplCapital`) | `FOR UPDATE` | the acceptance; a principal-re-instating repayment reversal |
| **(P)** | the merchant's `MERCHANT_PAYABLE` and the customer's `CUSTOMER_WALLET` `ledger_account` rows | `FOR UPDATE`, **as one element, in `UUID.compareTo` order of the two account ids** | the acceptance (payable; P4 also the wallet), a refund (payable; the wallet when an excess is credited), every wallet debit or credit (the wallet alone, at this position) |
| **L8** | ledger projection rows, the currency's shared `BNPL_MERCHANT_FEE_INCOME`, `BNPL_FEE_INCOME` and `BNPL_INTEREST_INCOME` among them | `lockBalancesInOrder`, one sorted call | postings, last |

Then only inserts (rows, events, audit, outbox). **B5 before B4**: a refund and a dispute lock the purchase
first, then its obligation; servicing takes only B4, so no writer holding B4 waits on B5. **(P) is
payments' order**: the book rail's `PaymentOutcomes.lockPairInFixedOrder` takes exactly this pair in exactly
this order, so a BNPL acceptance or refund and a wallet-paid capture or refund by the same customer at the
same merchant serialise in both id orders, never `40P01` (§7.3 (b) of the plan; counted by `P12-TSK-013`
and `-021`). **No shared income row is ever taken before (P)**: PN accrual is its own transaction, **T-acc**
(B4 → accrual postings → commit, no wallet), and any act that needs the record caught up runs T-acc first
and then verifies under B4 that every elapsed date is accrued — so no bnpl transaction holds a
currency-wide income row while it waits on a wallet or a payable (the Phase 11 gate's A2-F1, designed
out). The administrations take their own order: **A0** advisory namespace `12` (`hashtext(product)`), **A1**
the proposal row, **A2** the active row. Credit never locks a bnpl row; payments, merchant and checkout
never call bnpl; a sweep's claim commits before its step; BNPL calls no provider, so no connection is held
across one (ADR-0046).

**The database clock, at every boundary.** Every window is judged on `statement_timestamp()` read
**after** the relevant row lock, never on an instance clock, so ±5 s of instance skew cannot move a date
(the `INV-LND-10` discipline, applied to obligations by analogy — OQ-11):

| Boundary | Judged as |
|---|---|
| Offer expiry | stamped at birth `expires_at = LEAST(decision.valid_until, the session's expires_at, offered_at + the pinned offer validity (30 min))` (A17) — a BNPL offer cannot outlive its checkout session; acceptance on `expires_at > statement_timestamp()`, expiry on `<=` — exactly one at the instant |
| Session payable | under B3: `status = OPEN` and the session's `expires_at > statement_timestamp()`; the checkout expiry sweeper's `OPEN → EXPIRED` on the complement |
| Decision consumable | credit's port: `valid_until > statement_timestamp()` under L0 (BNPL validities: request ≤ 1 h, decision ≤ 1 day — OQ-18) |
| A business date | the database clock in the agreement's pinned `servicing_zone` (`UTC`, A22); a date is accruable (PN) only once the clock, read after B4, is past its end; the every-writer ranks judge a step's date against the range the transaction read (lending `V028`'s `business_date_earliest` / `_latest` pattern, from the first table) |
| Value and posting date | every bnpl entry: the business date of the database clock under B4; an accrual: its accrual date. **Every principal fact is value-dated no earlier than the obligation's floor** — the day after its latest accrual (PN) or its latest billing, read under B4 (the Phase 11 gate's R1, applied from the first table) |
| Due dates | P4: `D1` = the purchase's business date, `Dk = D1 + 14·(k−1)` calendar days, no business-day adjustment (A5); PN: one month after the purchase on its day-of-month, clamped to month end (A6) |
| Due, past due, DPD | an instalment is due on its date and past due once its date has ended; DPD counts from the oldest unpaid due date not under a dispute hold to the current business date |
| Dispute response deadline | stamped at the dispute's opening, `opened_at + the pinned response window` (14 days, OQ-10); the merchant's response on `response_deadline_at > statement_timestamp()`, the sweep's resolution on `<=` — complementary |
| Payoff good-through date · sweep permits | each stamped from `statement_timestamp()` at birth and compared with it at the act; a permit binds nothing, and one stamped "due at once" is due to a later statement only once the clock passes it (ADR-0063 decision 4) |

---

## 4. The state machines

### 4.1 BNPL terms version (`bnpl.bnpl_terms_version`) — `P12-TSK-004`

```
 (proposal) ──> PROPOSED ──★ a second person activates──> ACTIVE ──a successor activated──> RETIRED
                   └──rejected (the proposer may withdraw)──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `POST /v1/operator/bnpl/terms`, `BNPL_ADMINISTER`, key `bnpl.terms:EMPLOYEE:<id>` *(the plan's spelling; the platform enters `ActorType.CUSTOMER` for every session today, so lending's keys read `CUSTOMER` — `LENDING_LIFECYCLES.md` §4.1's A5-04 note; `-004` records which it builds)* | A0 (namespace `12`, `hashtext(product)`); one `PROPOSED` per product (partial unique, the backstop) | `422 bnpl.TermsInvalid` (judged whole — the plan shapes, rate, late fee and cap, bounds, engines it names; *not in the plan's §9 list, catalogued by `-004`*), `409 bnpl.ProposalPending`, `422 bnpl.ReasonRequired` | — | `bnpl.TermsVersionProposed` |
| `PROPOSED → ACTIVE` ★ | P ≠ proposer, `…/{v}/approval` | A0, A1 conditional `PROPOSED`, A2 the current `ACTIVE` `FOR UPDATE` | `403 bnpl.SelfApprovalRefused` (audited `FAILED`) | `BnplTermsVersionActivated` | `bnpl.TermsVersionActivated` |
| `ACTIVE → RETIRED` | S, the successor's activation, same transaction | A2 | — | — | — |
| `PROPOSED → REJECTED` | P, `…/{v}/rejection` | A0, A1 | — | — | `bnpl.TermsVersionRejected` |

One machine row per version, money-free, and an `INSERT`-only term set (`bnpl_terms`) holding, per product:
the plans offered (P4: `n = 4`, `BIWEEKLY`; PN: `n ∈ {3, 6, 12}`, `MONTHLY`), the rate (PN fixed, ACT/365F;
P4 held to zero by `CHECK`), the late fee, its `late_fee_day`, grace and per-agreement cap (A13), the DPD
bucket bounds and the default threshold (90, A14), the allocation order and overpayment treatment
(`HOLD_AS_CREDIT`), the engines (`BNPL_SCHEDULE_ENGINE_V1` for P4, `SCHEDULE_ENGINE_V1` and
`ACCRUAL_ENGINE_V1` for PN, `ALLOCATION_ENGINE_V1` for both, the refund allocator `-009` names), the offer
validity cap, the dispute response window and the servicing zone. Content is immutable once proposed; an
agreement pins the version for life (`INV-HIST-04`). **Never migration-activated**: production activates
none, and the application door answers `422 bnpl.ProductNotOffered` until the runbook's activation gate
opens (`PHASE_12_PLAN.md` §1.1). An application and an offer read the `ACTIVE` row `FOR SHARE`, so an
activation and a submission serialise. **Invalid:** anything out of `RETIRED` or `REJECTED`;
`PROPOSED → RETIRED`; `ACTIVE → PROPOSED | REJECTED`; an `ACTIVE` retired without its successor activated in
the same transaction; activation by the proposer (`CHECK`).

*As built (`P12-TSK-004`, 2026-10-17; this section's machine, corrected to the code):* `bnpl V002` -
`bnpl_terms_version` (money-free; the decision columns its only column-scoped `UPDATE`), `bnpl_terms` (`INSERT`-only for
every role) and `bnpl_terms_version_event`; `BnplTerms` (judged whole), `BnplTermsVersion`, `BnplTermsAdministration`,
`BnplTermsStore` / `JdbcBnplTermsStore` (namespace `12`), `BnplProductBounds` (`app`'s `CreditBnplProductBounds`). The
door's key reads `bnpl.terms:CUSTOMER:<id>` - the session actor's type (§8's operator-keys point, settled the lending way:
`LENDING_LIFECYCLES.md` §4.1's A5-04 note). The birth's refusals are `422 bnpl.TermsInvalid`, `422
bnpl.TermsNotAmortising` (pay-in-N judged by `SCHEDULE_ENGINE_V1`'s margined door over every purchase day 1-31, credit's
smallest amount and the longest plan offered; pay-in-4 by its own check - credit's smallest purchase at least one minor
unit per instalment), `409 bnpl.ProposalPending` and `422 bnpl.ReasonRequired`; a decision's loser is `409 bnpl.TermsStale`
(lending's precedent; the same person's retry converges); every route `BNPL_ADMINISTER`, the reads `GET
/v1/operator/bnpl/terms?product=&at=` (the version in force) and `GET …/{versionId}` (any version - what the second person
reviews). Every stamp is `clock_timestamp()`, written after the locks (lending `V030`'s repair, from the first row). The
term set holds, beside the fields above, the collection retry cadence (A7), the dispute four-eyes threshold (ADR-0106
decision 5 - "a field of the pinned terms version") and the payoff horizon (`P12-TSK-022`'s reused quote shape): an
immutable term set cannot gain a field later without inventing its value for the versions before it. The jurisdiction is
the agreement template's (`BnplAgreementTemplate`, lending's reading); there is no rate-type column (fixed is the only
kind, OQ-4); the servicing zone is `UTC` only (A22); pay-in-N's plans are an ascending choice among {3, 6, 12} and its
first-period floor 1-28 days (A6). The reader the application and the offer pin is `BnplTermsAdministration#pinActive`
(the `ACTIVE` row `FOR SHARE`; none → `422 bnpl.ProductNotOffered`).

### 4.2 BNPL application (`bnpl.bnpl_application`) — built by `P12-TSK-010` (`bnpl V003`), offered `-011`

```
 (keyed submission) SUBMITTED ──credit request opened, same tx──> AWAITING_DECISION ──decision APPROVED, offerable──> OFFERED
                                                                   ├──decision DECLINED──> DECLINED
                                                                   ├──credit request CANCELLED | EXPIRED | ABANDONED, the session no
                                                                   │   longer OPEN, or an approval that cannot be offered──> CLOSED_UNDECIDED
                                                                   └──customer withdraws while credit's request is cancellable──> WITHDRAWN
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `SUBMITTED → AWAITING_DECISION` | C, `MULTI_FACTOR`, `POST /v1/me/bnpl-applications` (session id, product, plan), key `bnpl.application:CUSTOMER:<id>` (T-app) | the claim; standing (`BnplPartyStanding`); the session read through `BnplCheckoutSessions` — `OPEN`, unexpired, EUR, its merchant `ACTIVE` and BNPL-enabled (`BnplMerchants`), its gross within credit's product bounds (A16); the `ACTIVE` terms `FOR SHARE`, recorded (informational — the offer pins the version active at its own instant); `DecisionRequests.submit(product, amount = the session's gross, term = the plan)` through `BnplCreditDecisions.open` (credit's arbiters, its one-open-request partial unique per party and product); `UNIQUE (decision_request_id)`; partial `UNIQUE (checkout_session_id)` over the open states | `422 bnpl.ProductNotOffered`, `422 bnpl.MerchantNotEnabled`, `422 bnpl.SessionNotEligible`, `422 bnpl.AmountOutOfRange`, `409 bnpl.ApplicationOpen`; credit's refusals passed through unchanged | `BnplApplicationSubmitted` | — (the customer's keyed act: its idempotency record, history row and event) |
| `AWAITING_DECISION → OFFERED` | S, `BnplOriginationSchedule` (T-off); `credit.CreditDecisionRecorded` and checkout's session events only hints | the claim; B1 conditional `AWAITING_DECISION`; the decision read authoritatively (`decisionFor`); the session still `OPEN` and unexpired (read); the terms `ACTIVE` `FOR SHARE`, pinned; the offer born beside it (`UNIQUE (application_id)`) | — | `BnplOffered`; `BnplEligibilityAssessed` (`ELIGIBLE`) | — |
| `AWAITING_DECISION → DECLINED` | S, the same step | B1 | — | `BnplApplicationClosed` (`DECLINED`); `BnplEligibilityAssessed` (`NOT_ELIGIBLE`) | — |
| `AWAITING_DECISION → CLOSED_UNDECIDED` | S, the same step, finding credit's request closed without a decision (`CREDIT_REQUEST_CANCELLED`, `_EXPIRED`, `_ABANDONED`), the session no longer `OPEN` or past its expiry (`SESSION_CLOSED` — expired, abandoned, or paid by another instrument), or an approval that cannot be offered (`APPROVED_BELOW_PURCHASE` — the price is fixed, §12.1; `PRODUCT_NOT_OFFERED`; `DECISION_LAPSED`) | B1; credit's request cancelled through the port in the same transaction **while it is still cancellable** — otherwise it decides, and its approval lapses unconsumed | — | `BnplApplicationClosed` (reason) | — |
| `AWAITING_DECISION → WITHDRAWN` | C, step-up, `POST …/{id}/withdrawal`, key `bnpl.application-withdrawal:CUSTOMER:<id>` | B1, then credit's own cancellation of its request (credit's lock, inside credit) in the same transaction | `409 bnpl.ApplicationNotWithdrawable` once credit has evaluated — the decision will arrive and the customer may decline the offer, or the session will close the application | `BnplApplicationClosed` (`WITHDRAWN`) | — |

`SUBMITTED` is transient, kept so the history records submission — the row is born in it and moved on in
the same transaction, and a deferred trigger refuses to commit a row still `SUBMITTED` (lending `V004`'s
pattern). The application holds no figure: the amount and term are credit's request's; it holds the
product, the plan, the session id, the request's id and the terms version. Terminal: `OFFERED` (continued by
the offer), `DECLINED`, `CLOSED_UNDECIDED`, `WITHDRAWN`. A decline is never reconsidered — a new application,
which the partial unique over open states admits for the same session. The application carries a permit
`next_step_at` (due at birth, re-stamped by every claim, pulled forward by a hint), a permit-only update
admitted only while `AWAITING_DECISION`. **Invalid:** anything out of a terminal; `SUBMITTED → OFFERED |
DECLINED`; `→ OFFERED` without its offer beside it, or on a decision not `APPROVED` (the domain's alone —
the decision is credit's); `DECLINED → OFFERED`; a second open application for one session.

**As built (`P12-TSK-010`, 2026-10-17).** `bnpl V003` holds the machine at three ranks: `BnplApplications`' conditional
edges under B1 (the withdrawal), the every-writer edge trigger `bnpl_application_permits_only_machine_edges` (born
`SUBMITTED` on its product's `ACTIVE` terms version with a plan that version offers; identity frozen; only the edges above;
a permit-only update only while `AWAITING_DECISION`; the closure reason said exactly for `CLOSED_UNDECIDED` by `CHECK`;
every stamp `clock_timestamp()` after the act's locks, the closure clamped forward of the submission) and the status
`CHECK`, beside the deferred never-rests-`SUBMITTED` trigger and the append-only `bnpl_application_event`. The arbiters:
the session's partial unique over `SUBMITTED`, `AWAITING_DECISION`, `OFFERED`; a backstop partial unique per (party,
product) over `SUBMITTED`, `AWAITING_DECISION` beneath credit's one-open-request partial unique (lending `V004`'s); `UNIQUE
(decision_request_id)`; the store's insert is `ON CONFLICT DO NOTHING` against both partial uniques, so a loser is
`409 bnpl.ApplicationOpen` and rolls back whole - its credit request with it. Read here one way and settled by the build:
**the application holds no figure** (the backlog's "requested amount frozen by column grant" would have made the machine
row a money table, which may never take its status `UPDATE` - `INV-BNPL-01`, `BnplSchemaHasNoMutableMoneyTest`); the amount
is the session's gross and credit's request's. The submission's order is T-app's: the standing (`BnplPartyStanding`), the
session by its token (one answer - `422 bnpl.SessionNotEligible` - for an unknown token, a session not `OPEN` or past its
deadline on the database clock, and one in another currency), credit's bounds (`422 bnpl.AmountOutOfRange`, before credit
is asked), the merchant's standing and the sale covering its fee (`BnplMerchants.standing`, `coversFee` - merchant's
`FeeCalculation`, judged without pinning; `422 bnpl.MerchantNotEnabled`, `422 bnpl.SessionNotEligible`), the terms pinned
`FOR SHARE` and the plan judged against them (`422 bnpl.ProductNotOffered`), then `BnplCreditDecisions.open` from the BNPL
origin for exactly the gross, P4 no term, PN the plan's months. Audited (`bnpl.ApplicationSubmitted`,
`bnpl.ApplicationWithdrawn`) though the table above reads "—": every customer act that opens credit is audited. The
withdrawal takes the platform's conditional step-up; its credit cancellation is asked from the BNPL origin.

### 4.3 BNPL offer (`bnpl.bnpl_offer`) — built by `P12-TSK-011` (`bnpl V004`), accepted `-013`

```
 (born by the offer step) OFFERED ──customer accepts = the purchase: decision consumed, agreement v1, purchase financed,
                             │                       obligation born, the session completed──> ACCEPTED
                             ├──customer declines──> DECLINED
                             └──expires_at <= the database clock──> EXPIRED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `OFFERED` | S (T-off) | under B1; the amount exactly the decision's and the session's gross (a lower approval closes the application, §4.2); the plan; the illustrative schedule from the pinned engine (P4 `BNPL_SCHEDULE_ENGINE_V1`, PN `SCHEDULE_ENGINE_V1`) and the total payable; canonical terms + `terms_sha256` in `bnpl_offer_terms` (INSERT-only, the hash held to the bytes by `CHECK`); `UNIQUE (application_id)`, `UNIQUE (decision_id)`; `expires_at` and the permit stamped by the trigger; `offered_at` the insert's own database instant | — | `BnplOffered` | — |
| `OFFERED → ACCEPTED` | C, `MULTI_FACTOR`, `POST /v1/me/bnpl-offers/{id}/acceptance` echoing `terms_sha256`, key `bnpl.offer-acceptance:CUSTOMER:<id>` (T-pur) | the claim; **L0**; **B2** conditional `OFFERED`, `expires_at > statement_timestamp()`; **B3** the session `FOR UPDATE`, judged at once (§4.4); standing re-read; the merchant still trading and BNPL-enabled (`merchant.merchant` `FOR SHARE`, as `CheckoutSessions.requireTrading` reads it); `consume(BNPL_AGREEMENT, agreementId)` (credit's `SECURITY DEFINER` path, `UNIQUE (decision_id)`); **L6** headroom ≥ G; agreement v1, acceptance evidence, purchase, merchant financing, the fee pin, obligation `ACTIVE`, schedule v1, the six per-obligation accounts opened; **(P)** the payable — and for P4 the wallet, available ≥ instalment 1 — as one pair; **L8**; F1, and for P4 F2 + F3; the session completed by financing and checkout's order born | `409 bnpl.OfferNotAcceptable` (moved, expired, or the decision not consumable), `409 bnpl.TermsChanged`, `409 bnpl.SessionNotPayable`, `422 bnpl.MerchantNotEnabled`, `422 bnpl.CapitalUnavailable`, `422 bnpl.InsufficientFunds` (P4's first instalment); the standing's refusal as lending's (`409 credit.ApplicantNotEligible`) — each refusal commits nothing under the customer's key | `BnplAgreementCreated`, `MerchantFinanced`; P4 `InstalmentDue` + `InstalmentPaid` (instalment 1) | `bnpl.OfferAccepted`, `bnpl.MerchantFinanced` (system); credit's `credit.DecisionConsumed` |
| `OFFERED → DECLINED` | C, `POST …/{id}/decline`, key `bnpl.offer-decline:CUSTOMER:<id>` | B2 conditional `OFFERED` and `expires_at > statement_timestamp()` | `409 bnpl.OfferNotAcceptable` | `BnplOfferClosed` | — |
| `OFFERED → EXPIRED` | S, `BnplOriginationSchedule` | B2 conditional `OFFERED`, `expires_at <= statement_timestamp()` | — | `BnplOfferClosed` | — |

An expired or declined offer releases nothing: the unconsumed decision lapses at its own `valid_until`, and
the session stays `OPEN` for another instrument. Acceptance is not consent (`INV-IDN-04`); the evidence
stores session, assurance level, client IP and user agent as `CONFIDENTIAL`. **A forced failure at any step
of T-pur leaves nothing** — no financing, no consumption, no order, no capital commitment (scenario 14;
`INV-BNPL-02`): there is no compensating path because there is nothing to compensate. **Instalment 1
short (P4):** `422 bnpl.InsufficientFunds`, nothing committed; the customer may top up and accept again
before the offer expires (no card-funded instalment — B6). **Invalid:** anything out of `ACCEPTED`,
`DECLINED`, `EXPIRED` — `EXPIRED → ACCEPTED` above all; `→ ACCEPTED` without its purchase, financing,
obligation, agreement v1 and evidence beside it (deferred), or at `expires_at <= statement_timestamp()`;
`→ EXPIRED` before it.

**As built (`P12-TSK-011`, 2026-10-18).** `bnpl V004` holds the offer at three ranks - `BnplOrigination`'s and
`BnplOffers`' conditional edges under B1 and B2, the every-writer edge trigger `bnpl_offer_permits_only_machine_edges`
(born `OFFERED` beside its application `AWAITING_DECISION` of the same party, product, plan and session, on the product's
`ACTIVE` terms version; `offered_at := clock_timestamp()`, `expires_at := LEAST(decision_valid_until, session_expires_at,
offered_at + the pinned validity)`, never born expired, the permit at the expiry; identity, pin and expiry frozen;
`DECLINED` refused once the statement's start has reached the expiry, `EXPIRED` while the trigger's clock has not;
`ACCEPTED` refused until `-013`) and the status `CHECK` with `expires_at <= decision_valid_until AND <= session_expires_at
AND <= offered_at + 30 min` - beside the deferred birth check, the INSERT-only `bnpl_offer_terms` (exactly the purchase,
the illustration's figures, the canonical terms as text with `terms_sha256 = sha256(convert_to(canonical_terms,
'UTF8'))` by `CHECK`, the document's hash) and the append-only `bnpl_offer_event`. `V004` replaces the application's edge
function verbatim but for `→ OFFERED` only beside its offer. The origination step is §4.2's table in this order: B1; credit's
answer (`decisionFor`, plain reads); still pending - the session read by reference, and if no longer `OPEN` or past its
deadline credit's request cancelled while cancellable (B1 → credit's request row, the withdrawal's order) and the
application `CLOSED_UNDECIDED (SESSION_CLOSED)`; closed - `CREDIT_REQUEST_*`; declined - `DECLINED`; approved - the
session still open, the approval exactly the gross (below it `APPROVED_BELOW_PURCHASE`; above it, another party,
product or term a defect that rolls back), the terms pinned and offering the plan (`PRODUCT_NOT_OFFERED`), the database
clock after the pin (`DECISION_LAPSED`), the illustration from the pinned engine (`BnplIllustration`: P4
`BNPL_SCHEDULE_ENGINE_V1`, PN `SCHEDULE_ENGINE_V1` on the start's day of the month), the offer, its terms, its history,
the application `OFFERED`. The read re-projects the illustration, re-renders the canonical terms and the document and
refuses to show any byte that is not the hashed one. **Settled here:** (1) an `OFFERED` application holds its session
for good (`V003`'s partial unique unchanged) - a declined or expired offer does NOT free the session for a second BNPL
application: owner decision B5 is one decision per purchase, credit's approval keeps its reservation until its own
validity whichever way the offer goes, so a second application would stack a second reservation on one purchase and
invite plan-shopping; the session stays `OPEN` for the card or the wallet (the owner's to revisit - it would need
credit's release of an unconsumed decision); (2) `BnplEligibilityAssessed` is `NOT_ELIGIBLE` for an approval below the
purchase as for a decline - the merchant's one bit says whether this purchase can be financed (§8's open point); (3) the
plan's `GET /v1/checkout/sessions/{id}/bnpl-options` is `POST /v1/checkout/sessions/bnpl-options` with the session's
token in the body - the holder's capability is the token, which may travel only in a body; (4) the checkout hint is
`checkout.CheckoutSessionExpired` (the session's one outboxed event; a card payment moves the session with no event and is
found by the sweep within one permit), beside credit's `CreditDecisionRecorded` and `CreditDecisionRequestClosed`; (5) an
offer whose session is paid by another instrument is not expired early - it runs to its expiry, which is never after
the session's, and the acceptance's B3 refuses it (`-013`).

### 4.4 The checkout session, as BNPL changes it (`checkout.checkout_session`) — `P12-TSK-007`, `-013`

*(Checkout's half built by `P12-TSK-007`, 2026-10-17: checkout `V004`, `CheckoutSession.completeByFinancing`,
`app`'s `CheckoutSessions.lockForFinancing` / `completedByFinancing` behind bnpl's port `BnplCheckoutSessions`
(`read` by token, `lockForPurchase` → `Payable` | `NotPayable(NOT_OPEN | EXPIRED)`, `completeByFinancing` →
`Completed(order)` | `NotPayable`). The completion re-judges the deadline in its own write, so a purchase whose
work crossed it is refused and rolls back whole; `V004` adds the order's presence at commit (deferred) and one
session per purchase to the invalid list below. The confirmation door's refusal of `"BNPL"` is its existing
`422 platform.ValidationFailed`.)*

Checkout's machine (checkout `V002`'s edge trigger) is `OPEN → PAYMENT_PENDING | EXPIRED | ABANDONED`,
`PAYMENT_PENDING → COMPLETED | EXPIRED`, `EXPIRED → COMPLETED_LATE`. BNPL adds **one edge and no state**
(checkout `V004`):

| Edge | Actor & door | Locks / arbiters | Refusals | Effect |
|---|---|---|---|---|
| `OPEN → COMPLETED` *(new, "by financing")* | S, inside T-pur — `app`'s `CheckoutSessions.completedByFinancing(uow, sessionId, purchaseRef, entryRef, correlation)` through `BnplCheckoutSessions` | B3 (the session `FOR UPDATE`, taken before any ledger row); `OPEN` and `expires_at > statement_timestamp()`; `financed_by_ref` set once, `payment_intent_ref` left null (exclusive by `CHECK`) | `409 bnpl.SessionNotPayable` (nothing consumed, nothing else locked) | checkout's order born with the financing entry as its `captured_entry_ref`; checkout's order events (their BNPL shape `-007`'s) |
| `OPEN → PAYMENT_PENDING` (existing) | a card or wallet confirmation | checkout's | once the BNPL acceptance has won: `409 checkout.NotConfirmable` (existing) | — |
| `OPEN → EXPIRED` (existing) | checkout's expiry sweeper | the session alone | — | an open BNPL application closes `CLOSED_UNDECIDED (SESSION_CLOSED)`; an offer already expired with it (A17) |

The session stays `OPEN` while the application and offer run — the card door must stay usable if BNPL is
declined. A card or wallet confirmation that won first moved the session to `PAYMENT_PENDING`, so the
acceptance finds it not `OPEN` and refuses; an acceptance that won first leaves the card confirmation
refused. The expiry and the acceptance are complementary on the database clock. **There is no
`COMPLETED_LATE` for BNPL**: no money lands late — the financing exists only in the commit that completes
the session. Why B3 before (P) is acyclic although checkout's capture completion takes the session after the
payable's rows: a capture completes only a session already `PAYMENT_PENDING`, and T-pur refuses such a
session under B3 before taking (P) (`PHASE_12_PLAN.md` §7.3 (c)). **Invalid (checkout's trigger):**
`OPEN → COMPLETED` without `financed_by_ref`, or with a `payment_intent_ref`; `PAYMENT_PENDING → COMPLETED`
with a `financed_by_ref`; `financed_by_ref` changed once set; `→ COMPLETED_LATE` for a financed session.

### 4.5 Purchase (`bnpl.purchase`) — `P12-TSK-013`; refunded `-021`, `-023`

```
 (born in T-pur) AUTHORISED ──merchant financing posted, same tx──> FINANCED ──refunds total the gross──> FULLY_REFUNDED
                                                                    (partially refunded: derived, never a state)
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `AUTHORISED → FINANCED` | C, the acceptance (§4.3) | inside T-pur after B3 and L6; `UNIQUE (checkout_session_id)`, `UNIQUE (offer_id)`; the merchant financing row beside it (`UNIQUE (purchase_id)`, `UNIQUE (journal_entry_id)`, `gross = fee + net` by `CHECK`); a deferred trigger: never committed `AUTHORISED`, and a financing row exists iff its purchase, obligation and entry exist (`INV-BNPL-02`, `-03`) | §4.3 | `MerchantFinanced` | `bnpl.MerchantFinanced` (system) |
| `FINANCED → FULLY_REFUNDED` | S — the refund (§4.9) whose amount brings Σ refunds to the gross, merchant's, operator's or a dispute's outcome | B5; Σ refunds ≤ gross (`INV-BNPL-07`) re-judged under it | — | `BnplRefundApplied` | `bnpl.RefundApplied` |

`AUTHORISED` is transient, kept for history: the platform's commitment to pay the merchant exists from the
acceptance, and the financing posts in the same transaction — there is no committed-but-unfinanced
purchase. Frozen from birth: session, offer, merchant, gross, currency, the fee pin and the financed
instant. **Cancellation is not a state**: before financing it is unreachable (one transaction); after it is
a full refund, the merchant's act — the platform never "un-finances" by editing. A purchase stays
`FINANCED` after its obligation closes, so a refund after full repayment is admitted (OQ-15). **Invalid:**
anything out of `FULLY_REFUNDED`; `FINANCED → AUTHORISED`; `AUTHORISED → FULLY_REFUNDED`; `→ FINANCED`
without its financing row and entry; `→ FULLY_REFUNDED` while Σ refunds < gross.

*(As built by `P12-TSK-013`, `bnpl V006`: the purchase row holds **no money** - a table whose status moves carries no
`*_minor` column (`BnplSchemaHasNoMutableMoneyTest`) - so the gross, fee and net are `merchant_financing`'s, INSERT-only,
`CHECK (gross = fee + net)`, keyed by its purchase with `UNIQUE (journal_entry_id)`; the fee pin is merchant's
`bnpl_fee_pin` (by value). The purchase's business date is stamped by its trigger on `clock_timestamp()` in the pinned
zone after the act's locks, and is every principal fact's date. `AUTHORISED → FINANCED` is admitted only beside its
financing and with checkout's order id (`UPDATE (status, order_id)` the only grant); three deferred triggers on one
function hold the purchase, its financing and its obligation to one commit - and the purchase to its `ACCEPTED` offer,
agreement version 1, evidence and schedule version 1 (`INV-BNPL-02`). No bnpl trigger reads the ledger: the entry is named
by id, its lines the one transaction's and the three-way proof's (`-025`). `bnpl.MerchantFinanced` is recorded under the
accepting customer, the act that commits the financing (`SystemActorRulesTest` forbids `Actor.SYSTEM` outside
`SecurityContext`). `FULLY_REFUNDED` refused until `-021`.)*

### 4.6 BNPL agreement (`bnpl.bnpl_agreement`) — versioned, immutable rows; the status is the obligation's — `P12-TSK-013`, `-021`, `-023`

No machine: each version is a born-once, `INSERT`-only row for every role (`UNIQUE (obligation_id,
version)`), carrying full, self-contained canonical terms and their `terms_sha256`, beside the acceptance
evidence (`bnpl_acceptance`) of what the customer saw and how they were authenticated. **v1** at the
acceptance — the offer's canonical terms copied byte for byte (text, never re-serialised); its id is the
consumer reference under which credit records the decision's consumption. **v n+1** in the transaction of a
refund (or a dispute resolved as a refund) that cancels or shortens any unbilled instalment (§12.6 point
6), with schedule v n+1 — billed instalments kept, unbilled superseded. A dispute upheld or withdrawn
changes nothing and makes no version. Never edited (`INV-HIST-04`); every servicing row names the version
it was computed under; every version's hash is re-verified by `BnplReplayProof` (`-025`).

*(As built by `P12-TSK-013`, `bnpl V006`: `bnpl_agreement` keyed `(agreement_id, version)` - one identity across
versions, the obligation's `UNIQUE (agreement_id)` - with `UNIQUE (obligation_id, version)`; version 1 only (`CHECK`) until
`-021`; its trigger holds it to an exact copy of the offer's canonical terms, hash and document hash, the offer's decision
and terms version, and the engines (schedule, pay-in-N's accrual, allocation, refund allocation) and template of that
terms version; immutable for every role. The evidence `bnpl_acceptance` (`UNIQUE (agreement_id, agreement_version)`)
is bound to that version's offer and hashes, `MULTI_FACTOR` by `CHECK`, the channel `API`, the address `inet` and the
agent bounded. No money column: the amounts are the canonical terms' and the financing's.)*

### 4.7 Customer repayment obligation (`bnpl.bnpl_obligation`) — `P12-TSK-013`, `-015`, `-018`, `-021`, `-022`

```
 (born at acceptance) ACTIVE ──every receivable zero, every elapsed date accrued, credit balance refunded──> CLOSED
                                (reason REPAID | PAID_OFF_EARLY | REFUNDED | REFUNDED_PARTIALLY_REPAID)
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `ACTIVE` | C, the acceptance (§4.3) | inside T-pur, after L0, B2, B3, L6; `UNIQUE (purchase_id)`; no money column; product and plan frozen; `next_servicing_at` stamped | §4.3 | `BnplAgreementCreated` | `bnpl.OfferAccepted` |
| `ACTIVE → ACTIVE` (permit only) | S, the servicing claim | `next_servicing_at` re-stamped — the only update admitted on an `ACTIVE` row besides closure | — | — | — |
| `ACTIVE → CLOSED` | S — the transaction that zeroes the last receivable: a repayment (T-rep), an auto-collection or a credit-balance application (T-svc), a payoff (T-pay), a refund (T-ref, or T-dsp's refund outcome), a waiver (T-wvr) | B4; the six balances derived from journal lines under it; PN every elapsed date accrued; any credit balance refunded to the wallet in the same transaction (`bnpl.closure-refund:<obligation>`) | — | `BnplAgreementClosed` (reason) | — |

**Closure reasons, by the closing act** (this document's reading of §5.5 and §12.6 point 7 — §8): a refund
that leaves the purchase `FULLY_REFUNDED` → **`REFUNDED`** (P4's instalment 1, already paid, returned as the
excess); a partial refund that settles the last receivable → **`REFUNDED_PARTIALLY_REPAID`**; a payoff →
**`PAID_OFF_EARLY`**; any other closing act (repayment, auto-collection, credit-balance application,
waiver) → **`REPAID`**, earlier partial refunds notwithstanding. Closure is a consequence, never a command.
No `PENDING_DISBURSEMENT` (nothing to disburse) and no `CANCELLED` (once financed, an obligation ends only
by being settled — or, deferred to Phase 14, written off). **No condition is a state** (§5). A `CLOSED`
obligation's six balances are zero, proven continuously by `BnplSubledgerProof` (`INV-BNPL-09`); a
repayment, collection or fee on it answers `409 bnpl.ObligationClosed` or is skipped, and no condition is
written after closure (scenario 13). **Invalid:** anything out of `CLOSED` (`INV-LIFE-04`); `CLOSED`
without its reason, or with a receivable or credit balance not zero; a reason that does not match its
closing act (`REFUNDED` without the purchase `FULLY_REFUNDED`, `PAID_OFF_EARLY` without a payoff execution).

*(As built by `P12-TSK-013`, `bnpl V006`: born `ACTIVE` beside its `AUTHORISED` purchase, its party, product, plan,
currency and decision the purchase's and offer's, its purchase date the purchase's business date, `next_servicing_at` due
at once; `UNIQUE (purchase_id)`, `(agreement_id)`, `(decision_id)`. **No edge and no permit move is admitted yet** - the
servicing (`-015`), refund (`-021`) and payoff (`-022`) tasks replace its edge function and grant the columns they move.
Schedule version 1 (`bnpl_schedule`, `bnpl_instalment`) from the pinned engine over the purchase's own business date,
conserving at commit (a deferred trigger, `INV-BNPL-05`), pay-in-4's dates every 14 days by `CHECK`-like rank. Pay-in-4's
instalment 1 is billed (`bnpl_billing`, F2) and repaid (`bnpl_repayment` kind `AT_PURCHASE`, keyed by the purchase,
allocated by `ALLOCATION_ENGINE_V1`, F3) in T-pur; `bnpl.principal_value_floor(obligation)` - the purchase date, then the
latest billing's date - floors both. The owner's reads `GET /v1/me/bnpl-agreements`, `/{id}`, `/{id}/schedule` derive the
six balances from journal lines in one `REPEATABLE READ` snapshot.)*

### 4.8 Merchant settlement, as BNPL sees it — no bnpl machine — `P12-TSK-016`, `-021`

BNPL owns no settlement machine and never pays a merchant. The merchant leg walks existing machines:

| Step | Owner and machine | BNPL's part |
|---|---|---|
| The payable credited, net of the merchant discount | the ledger's `MERCHANT_PAYABLE` (merchant's purpose; `INV-MER-02`'s equation gains "+ financed − BNPL fees − BNPL refunds + BNPL fees returned") | F1 in T-pur — bnpl becomes a poster of the payable; `MerchantPayable`'s drill-down shows `BNPL_FINANCING` and `BNPL_REFUND` lines (`-016`) |
| Paid out | `merchant`'s on-demand payout (ADR-0051, ADR-0057): lock the payable, hold, commit `DISPATCHED`, wire; `DISPATCHED → COMPLETED \| FAILED \| UNKNOWN`, `UNKNOWN → COMPLETED \| FAILED` by inquiry only (ADR-0083); bounded by the payable (`INV-MER-05`) | none — a BNPL-financed payable is paid out exactly as a card-captured one |
| Reported and final | Phase 8's two evidence hops (ADR-0065): the payout provider's report, then the platform's bank debit | none — BNPL adds no expectation and no external evidence |
| A refund after a payout | the payable debited by F5 | **the third bounded negative source** (owner decision B7, ADR-0105): a BNPL refund may drive the payable below zero, by at most that purchase's own net — Σ over its refunds of `(R − fee returned)` ≤ `G − f` (`INV-BNPL-07` with the telescoping fee guarantees it) — the debt recovered from later captures and financings before any payout (`INV-MER-05`); `INV-MER-07` amended explicitly, effective when `-021` lands; counted by `finapp.ledger.negative.positions` |

**Scenario 5** (`BnplMerchantPayoutDatabaseTest#aLostPayoutResponseIsHeldAndTheRetryReplays`, `-016`): the
payout of a BNPL-financed payable executes at the simulated provider and the answer is lost → the payout
`UNKNOWN`, its hold standing; the merchant's retry under the same `merchant.payout:<merchantId>` key
replays and never re-dispatches; the inquiry concludes `COMPLETED` once; the agreement is unaffected.

### 4.9 Refund (`bnpl.bnpl_refund`) — born `APPLIED` — `P12-TSK-021`; the dispute outcome `-023`

A refund row exists only once its effects posted (lending's repayment precedent): one transaction claims
the key, locks, judges the bound, allocates latest first, posts, and writes the refund and its adjustment
rows. There is no `PENDING` state, because there is no provider; a refusal writes nothing.

| Birth | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| Merchant refund | M, the refund scope, `POST /v1/merchant/bnpl-purchases/{id}/refunds` (amount, reason), key `bnpl.refund:MERCHANT:<id>` (T-ref); admitted while the merchant is suspended (§14) | the claim; **B5** the purchase, owner-scoped; `R ≤ G − Σ refunds` (`INV-BNPL-07`); **B4**; the database clock; PN every elapsed date accrued (else T-acc first, then re-verified under B4); the due state derived under B4; the split by the pinned refund allocator — walk instalments from the **last** to the first, cancelling at each `min(remaining R, its unpaid principal)` (unbilled → `BNPL_PRINCIPAL`, billed-unpaid → `BNPL_PRINCIPAL_DUE`, curing its DPD; a partly paid instalment only its unpaid part), the excess `E` to the customer; the fee returned `cum_fee(after) − cum_fee(before)`, `cum_fee(x) = round(f · x / G)`; schedule and agreement v n+1 if an unbilled instalment is cancelled or shortened; **(P)** the payable and — when `E > 0` — the wallet, one pair; **L8**; F5 (+ F5a: late fees on a cancelled instalment released, OQ-14; PN interest stays owed except at a full cancellation, A11) under `bnpl.refund:<refund>`; the refund and its adjustment rows (`UNIQUE (merchant_id, refund_key)`; deferred: Σ adjustments + excess = amount; per-instalment rows); closure if every receivable is now zero (§4.7) | `422 bnpl.RefundExceedsPurchase`, `409 bnpl.PurchaseFullyRefunded`, `422 bnpl.ReasonRequired`, `404 bnpl.NotFound` (another merchant's purchase), merchant's key-scope refusals | `BnplRefundApplied` (+ `BnplAgreementClosed`) | `bnpl.RefundApplied` (merchant actor, reason) |
| Operator refund | P, the door beside the merchant's (A19, OQ-9; *its route and permission are not in the plan's §9 table — `-021` names them*) | the same body | the same | the same | `bnpl.RefundApplied` (operator actor, reason) |
| A dispute's refund outcome | S, inside T-dsp's resolution (§4.10) | the same body under the locks T-dsp already holds (B5, B4, B6) | — | `BnplRefundApplied`, `BnplDisputeResolved` | `bnpl.DisputeResolved` |

**Value is preserved** (`INV-BNPL-06`): `R` = Σ principal cancelled + `E`, the payable debited `R − fee
returned`, nothing else moves. **Before, during, after:** before instalment 2 of P4 a full refund cancels
2–4 and returns instalment 1's payment as the excess; during, it cancels from the last; after every
instalment is paid, the whole `R` is excess — admitted on a `CLOSED` obligation, posting only the payable
debit and the wallet credit (OQ-15). A refund racing a collection conserves in either order (scenario 7):
collected-then-refunded → more excess; refunded-then-collected → nothing left to collect. Excess the wallet
cannot take is held in `BNPL_CREDIT_BALANCE` and refunded at closure or when postable (OQ-13 — but see §8 on
a `CLOSED` obligation). **Invalid:** a refund row without its entry and adjustment rows; an adjustment that
cancels a paid amount, or an earlier instalment while a later one still owes principal; Σ refunds above the
gross; a refund on a `FULLY_REFUNDED` purchase. *(The split is `REFUND_ALLOCATION_ENGINE_V1`'s, built pure by `P12-TSK-009`: `RefundAllocationEngineV1.allocate`
over the `RefundPosition` derived under B5 then B4, the fee merchant's arithmetic bound in as a parameter; it refuses a
refund in another currency, not positive or above `G − Σ refunds` with typed reasons and writes nothing - this
machine's rows and entry are `-021`'s.)*

### 4.10 BNPL dispute (`bnpl.bnpl_dispute`) — `P12-TSK-023`

```
 (customer opens; hold written) OPEN ──merchant responds with evidence──> MERCHANT_RESPONDED ──★ operator resolves──> RESOLVED_REFUND | RESOLVED_UPHELD
                                  ├──merchant accepts liability──────────────────────────────────────────────────> RESOLVED_REFUND
                                  ├──response deadline passes (the merchant's silence is acceptance)─────────────> RESOLVED_REFUND
                                  └──customer withdraws (also from MERCHANT_RESPONDED)───────────────────────────> WITHDRAWN
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `OPEN` | C, `POST /v1/me/bnpl-agreements/{id}/disputes` (reason, amount), key `bnpl.dispute:CUSTOMER:<id>` (T-dsp) | the claim; **B5** the purchase; the amount ≤ `G − Σ refunds`; **B4**; one open dispute per purchase (partial `UNIQUE (purchase_id)` over `OPEN`, `MERCHANT_RESPONDED`); `response_deadline_at` stamped; the hold written over the instalments a refund of that amount would cancel, latest first | `409 bnpl.DisputeOpen`, `409 bnpl.PurchaseFullyRefunded`, `422 bnpl.RefundExceedsPurchase`, `422 bnpl.ReasonRequired` | `BnplDisputeOpened` | `bnpl.DisputeOpened` |
| `OPEN → MERCHANT_RESPONDED` | M, `POST /v1/merchant/bnpl-disputes/{id}/response` (evidence), key `bnpl.dispute-response:MERCHANT:<id>` | B5, B4, **B6** conditional `OPEN`, `response_deadline_at > statement_timestamp()` | `409` (moved or past the deadline — its code `-023`'s), `404 bnpl.NotFound` (another merchant's) | — | `bnpl.DisputeResponded` |
| `OPEN → RESOLVED_REFUND` (accepted) | M, the same door, accepting liability | B5, B4, B6 conditional `OPEN`, unexpired; §4.9's body in the same transaction | as above | `BnplDisputeResolved`, `BnplRefundApplied` | `bnpl.DisputeResponded`, `bnpl.DisputeResolved` |
| `OPEN → RESOLVED_REFUND` (silence) | S, `BnplDisputeSchedule` on the deadline (OQ-10, 14 days) | the claim; B5, B4, B6 conditional `OPEN`, `response_deadline_at <= statement_timestamp()`; §4.9's body | — | `BnplDisputeResolved`, `BnplRefundApplied` | `bnpl.DisputeResolved` (system) |
| `MERCHANT_RESPONDED → RESOLVED_REFUND \| RESOLVED_UPHELD` ★ above the threshold | P, `BNPL_DISPUTE_RESOLVE`, `POST /v1/operator/bnpl-disputes/{id}/resolution`, key `bnpl.servicing:EMPLOYEE:<id>`, reasoned | B5, B4, B6 conditional `MERCHANT_RESPONDED`; `RESOLVED_REFUND` runs §4.9's body; `RESOLVED_UPHELD` lifts the hold and re-bills nothing (nothing was cancelled) | `403 bnpl.SelfApprovalRefused`, `403 bnpl.SelfDealingRefused` (both audited `FAILED`), `422 bnpl.ReasonRequired` | `BnplDisputeResolved` (+ `BnplRefundApplied`) | `bnpl.DisputeResolved` |
| `OPEN \| MERCHANT_RESPONDED → WITHDRAWN` | C, the dispute's withdrawal (route `-023`'s) | B5, B4, B6 conditional; the hold lifted | — | `BnplDisputeResolved` (`WITHDRAWN`) | — *(no audit act in the plan's §11 — `-023` decides)* |

**One bound, two paths** (`INV-BNPL-08`, ADR-0106): a dispute's refund outcome *is* a `BnplRefund`, under
the same B5 lock and the same `INV-BNPL-07` bound as a merchant refund, so at most the gross ever returns to
the customer's side — whichever of a merchant refund and a dispute resolution commits first, the other
sees what it left (scenario 10). While `OPEN` or `MERCHANT_RESPONDED`, the disputed amount is held (§5). A
chargeback on a card-funded wallet top-up is payments' dispute and touches no BNPL row; if a later phase
adds card-funded instalments, `INV-DSP-01` must be extended to the BNPL bound (recorded, §12.7).
**Invalid:** anything out of `RESOLVED_REFUND`, `RESOLVED_UPHELD`, `WITHDRAWN`; `OPEN → RESOLVED_UPHELD`
(the merchant must respond first); `MERCHANT_RESPONDED → OPEN`; `→ RESOLVED_REFUND` without its refund
row beside it (unless nothing remains refundable — §8); the merchant's response at or after the deadline;
the deadline's resolution before it; a second open dispute on one purchase.

### 4.11 Servicing proposals — repayment reversal, waiver — `P12-TSK-024`

```
 PROPOSED ──★ approved: posted──> APPROVED        PROPOSED ──rejected──> REJECTED
```

| Edge | Actor & door | Locks / arbiters | Refusals | Event | Audit |
|---|---|---|---|---|---|
| (birth) → `PROPOSED` | P, `BNPL_SERVICE`, `POST /v1/operator/bnpl-agreements/{id}/waivers` · `…/repayments/{rid}/reversal`, key `bnpl.servicing:EMPLOYEE:<id>` | B4; one live proposal per subject (a partial unique over `PROPOSED`/`APPROVED` for a reversal, so a rejected one may be proposed again) | `422 bnpl.ReasonRequired`, `409 bnpl.ProposalPending`, `422 bnpl.WaiverExceedsDue`, `409 bnpl.ObligationClosed`, `403 bnpl.SelfDealingRefused` | — | `bnpl.WaiverProposed` / `bnpl.RepaymentReversalProposed` |
| reversal `PROPOSED → APPROVED` ★ | P ≠ proposer, `BNPL_SERVICE_APPROVE` (T-rev) | **L0** (outstanding rises); B4; B6 conditional `PROPOSED`; **L6** and headroom when principal is re-instated (`422 bnpl.CapitalUnavailable`, the proposal stays `PROPOSED`); **(P)** the wallet; L8; F8 — `ReversalService`'s exact swap of the repayment's entry (`INV-REV-02`); allocations negated by allocation-reversal rows, later allocations untouched; DPD recomputed | `403 bnpl.SelfApprovalRefused` (audited `FAILED`); refused for a repayment that closed the obligation or on a closed one (lending's A12, `INV-LIFE-04`) | `BnplRepaymentReversed` | `bnpl.RepaymentReversalApproved` |
| waiver `PROPOSED → APPROVED` ★ | the same (T-wvr) | B4; B6; the fee's unpaid remainder at most, judged under B4; L8; F7 — `ReversalService` of F4, bounded; may close the obligation (`REPAID`) | `403 bnpl.SelfApprovalRefused`, `422 bnpl.WaiverExceedsDue` | `BnplFeeWaived` | `bnpl.WaiverApproved` |
| `PROPOSED → REJECTED` | P, `…/rejection` | B4, B6 | — | — | `bnpl.WaiverRejected` / `bnpl.RepaymentReversalRejected` |

The repayment row is `INSERT`-only (it carries money), so `REVERSED` is read from the approved reversal naming
it, never written onto it. No "override" of a computed figure exists: a person changes an outcome only
through these acts, each a reasoned posting. **Invalid:** anything out of `APPROVED` or `REJECTED`;
`APPROVED → REJECTED`; approval by the proposer (`CHECK`).

### 4.12 Payoff quote and execution — `P12-TSK-022`

The **quote** is an immutable born-once fact (C, `POST /v1/me/bnpl-agreements/{id}/payoff-quotes`, keyed,
any condition, `ACTIVE` only): a good-through date and the amount — P4: every unpaid instalment's principal
plus unpaid fees, less any credit balance; PN: Phase 11's shape (derived outstanding plus the accrual
`ACCRUAL_ENGINE_V1` would post through the day before the good-through date, A21) — stored with its inputs.
The **execution** is a separate born-once fact (`bnpl_payoff_execution`, `UNIQUE (quote_id)`, `UNIQUE
(obligation_id)`): C, `MULTI_FACTOR`, `POST …/payoff`, key `bnpl.payoff:CUSTOMER:<id>` (T-pay: the claim;
PN accrued to date, else T-acc first; B4; B6 the quote; the catch-up credit-balance application over
past-due items, the gate's R2; recompute; **(P)** the wallet; L8). A recomputed amount ≠ the quote →
`409 bnpl.PayoffQuoteStale`, nothing posted; else every unbilled instalment billed early beside it, one
repayment allocated to every component, the credit balance refunded, the obligation `CLOSED
(PAID_OFF_EARLY)` — one transaction, every application floored from its first line (the gate's residual
RG-M1 not repeated). No fee (L8). A refund racing it waits on B5 → B4 and finds the obligation `CLOSED`: the
refund is excess to the customer only (scenario 11).

### 4.13 Repayment (`bnpl.bnpl_repayment`) and collection attempt — `P12-TSK-018`, `-019`

Born `ALLOCATED` — a repayment row exists only once its money moved, with its allocations
(`bnpl_repayment_allocation`, a deferred trigger asserting Σ allocation = amount) and its entry
(`bnpl.repayment:<id>`, F3) — and `REVERSED` only through an approved reversal (§4.11). A failed collection is
a collection attempt, never a repayment.

| Birth | Actor & door | Locks / arbiters | Refusals | Event |
|---|---|---|---|---|
| P4 instalment 1 | C, inside T-pur (§4.3) | (P) the wallet in the pair, available ≥ `a1` | `422 bnpl.InsufficientFunds` (the whole purchase refused) | `InstalmentPaid` |
| Customer repayment | C, step-up, `POST /v1/me/bnpl-agreements/{id}/repayments`, key `bnpl.repayment:CUSTOMER:<id>` (T-rep) | the claim; B4 `ACTIVE`; PN accrued to date (else T-acc first); the due state derived under B4; the allocation (`ALLOCATION_ENGINE_V1`: oldest due first, `FEES → INTEREST → PRINCIPAL`, the remainder `HOLD_AS_CREDIT`); (P) the wallet, available ≥ amount; L8; `UNIQUE (obligation_id, kind, request_key)` | `422 bnpl.InsufficientFunds`, `409 bnpl.ObligationClosed`; standing is **not** checked (a suspended customer may repay) | `InstalmentPaid` per instalment settled (+ `BnplAgreementClosed`) |
| Auto-collection | S, `BnplServicingSchedule` (T-svc) on the due date, retried daily while due (A7); partial allowed | B4; `bnpl_collection_attempt UNIQUE (instalment_id, attempt_date)`; (P) the wallet; held instalments collected only beyond the disputed part | nothing collected → the attempt (`INSUFFICIENT_FUNDS`, `WALLET_NOT_POSTABLE`), no repayment | `InstalmentPaid` / `BnplCollectionFailed` |
| Payoff | §4.12 | | | `BnplAgreementClosed` |

The same key replays one answer; two keys serialise on B4, each allocating against what it finds (scenario
6); ten sweepers and a customer's repayment collect once (scenario 4). Wallet only — no card-funded
instalment and no system-initiated card charge (B6).

### 4.14 The born-once facts (not machines)

| Fact | Table | Born | Once by |
|---|---|---|---|
| Agreement version · acceptance evidence | `bnpl_agreement`, `bnpl_acceptance` | the acceptance (v1); a schedule-changing refund (v n+1) | `UNIQUE (obligation_id, version)`; `INSERT`-only for every role; `terms_sha256` re-verified by replay |
| Merchant financing | `merchant_financing` | T-pur | `UNIQUE (purchase_id)`, `UNIQUE (journal_entry_id)`; `gross = fee + net` (`INV-BNPL-04`) |
| Fee pin | `bnpl_fee_pin` (merchant `V010`, built by `P12-TSK-006`) | T-pur, through `BnplMerchants.pinFee` | the purchase is the primary key - one per purchase, never a second version; insert-only, V004's immutability trigger, no truncate; a trigger holds it to a returning version of the merchant's BNPL schedule for the product, in force by `pinned_at`, the merchant BNPL-enabled - the intent-keyed `PaymentFeePin` cannot hold it |
| Schedule version · instalment | `bnpl_schedule`, `bnpl_instalment` | T-pur (v1); a schedule-changing refund (v n+1) | `UNIQUE (obligation_id, schedule_version)`, `UNIQUE (schedule_id, sequence)`; deferred conservation Σ principal = purchase principal (`INV-BNPL-05`) |
| Interest accrual (PN) | `bnpl_interest_accrual` | T-acc, per elapsed date, oldest first | `UNIQUE (obligation_id, accrual_date)` + ledger key `bnpl.accrual:<obligation>:<date>` (scenario 18) |
| Billing · credit-balance application | `bnpl_billing`, `bnpl_credit_balance_application` | T-svc at the due date (P4 instalment 1 in T-pur); the catch-up application in every servicing step and the payoff (R2) | `UNIQUE (instalment_id)` |
| Collection attempt | `bnpl_collection_attempt` | T-svc | `UNIQUE (instalment_id, attempt_date)` |
| Refund adjustment | `bnpl_refund_adjustment` | T-ref | per instalment per refund; Σ + excess = the refund (deferred) |
| Fee assessment | `bnpl_fee_assessment` | T-svc, on the business date the instalment's age reaches the pinned `late_fee_day` (beyond grace), never while held | `UNIQUE (instalment_id, kind)`, capped per agreement |
| Condition change | `bnpl_condition_event` | T-svc, only on a change | `UNIQUE (obligation_id, business_date, kind)` |
| Payoff quote · execution | `bnpl_payoff_quote`, `bnpl_payoff_execution` | C | `UNIQUE (quote_id)`, `UNIQUE (obligation_id)` |

---

### 4.15 Merchant BNPL enablement (`merchant.merchant_bnpl_enablement`) — `P12-TSK-006`

```
 (birth) PROPOSED --a second, distinct operator--> ACTIVE --any one operator--> WITHDRAWN
             \--any operator, the proposer included--> REJECTED
```

Merchant's machine, not bnpl's (OQ-8, owner accepted default under B8: a four-eyes operator act). All four
doors are `MERCHANT_ADMINISTER`, keyed per principal (`merchant.bnpl-enablement:<type>:<id>`), reasoned; reasons
`RESTRICTED-PII`, never served. **One live** (proposed or active) per merchant by partial unique - ten proposers,
one proposal, nine `409 merchant.BnplEnablementPending`; a proposal beside an `ACTIVE` enablement is refused the
same way. **Four eyes at three ranks**: the domain, the approval's own `proposed_by <> ?`, and
`merchant_bnpl_enablement_approver_is_not_proposer` (`CHECK`); the proposer's approval is `409
merchant.SelfApprovalRefused`, recorded `FAILED` in a transaction of its own (R4). Every decision takes the row
`FOR UPDATE` and writes conditionally, so an approval and a rejection racing leave exactly one (`409
merchant.BnplEnablementNotOpen` for the other); the same person's retry converges. **The withdrawal is one
person's act** - stopping BNPL reduces risk, the merchant suspension's precedent; it waits for every standing read
holding the row `FOR SHARE`, so no purchase is admitted after it commits and none financed before it is touched. A
closed merchant takes no proposal and no approval (`409 merchant.IllegalTransition`). Every instant is the
database's (`clock_timestamp()` in the edge trigger, after the lock, clamped forward); each decision whole and frozen
on the row; never deleted or truncated.

**The standing** (`BnplMerchants.standing`, the first failing condition): the merchant row `FOR SHARE` -
`ACTIVE`, else `NOT_TRADING`; its `ACTIVE` enablement `FOR SHARE`, else `NOT_ENABLED`; the product's BNPL fee
schedule (`merchant_bnpl_fee_schedule`, `PUT .../bnpl-fee-schedules/{product}`, `FEE_ADMINISTER`, reasoned,
converging) with a version in force that returns its fee, else `NOT_PRICED`. A schedule with any `RETAINED` version
is refused at the assignment (`422 merchant.BnplScheduleNotReturning`, and by trigger for every writer); a
`RETAINED` version added later makes the product `NOT_PRICED` rather than ever pricing a BNPL purchase.

## 5. Conditions (orthogonal to the lifecycle; append-only histories) — `P12-TSK-020`, `-023`

| Condition | Values | Set / cleared by | Effect |
|---|---|---|---|
| Delinquency | `CURRENT`; `PAST_DUE` with DPD and bucket (the pinned terms' bounds) | S, daily in T-svc under B4, derived from billed and allocated rows | late-fee eligibility; `BnplDelinquencyChanged` (old/new bucket, DPD) — also announcing cure |
| Default | `DEFAULTED` flag | S at DPD ≥ 90 (A14); cleared at cure, no probation | `BnplDefaulted` / `BnplDefaultCleared`; **no acceleration, no posting** (write-off is Phase 14's) |
| Disputed (servicing hold) | the held amount and the instalments it covers, latest first | the dispute's opening (T-dsp); lifted by its resolution or withdrawal | no late fee and no DPD on the held instalments; no auto-collection beyond the undisputed part — the hold Phase 11 designed and did not build (lending's A9), built here for BNPL's own reason |
| Late-fee eligibility | derived | S, read in T-svc under B4 | a late fee once per instalment on the pinned day, capped, never on a held instalment |

DPD is the days between the oldest past-due, unheld instalment's due date and the current business date
(database clock, pinned zone), 0 when nothing is past due — **derived, never a stored counter** (Phase 11
§12.7's definition per obligation, `INV-LND-12` by analogy). Grace delays only the late fee, never DPD.
Cure is DPD back to 0 because every billed, unheld amount is paid — a refund that cancels a billed
instalment's unpaid principal cures it. The sweep appends a `bnpl_condition_event` **only on a change** of
bucket, state or flag, born once per (obligation, business date, kind): re-running a day writes nothing, and
a closed obligation is skipped (scenario 13). Payoff is permitted in any condition. **Collections case: out
of scope** (Phase 13) — bnpl emits `BnplDelinquencyChanged` and `BnplDefaulted` and owns these contractual
facts; risk owns fraud and abuse judgements; neither writes the other's rows. **Not built** (each recorded
with its default): acceleration, penalty interest, interest on interest, arrears notifications, bureau
reporting, statutory contact limits, any consumer-credit-law feature (A1).

---

## 6. The ranks, per machine (planned)

| Machine | Table (task) | History | Database rank beyond the edge trigger |
|---|---|---|---|
| Terms version | `bnpl_terms_version` (`-004`) | `bnpl_terms_version_event` | one `PROPOSED` / one `ACTIVE` per product; four-eyes `CHECK`; content frozen from insert; retirement only beside a successor (deferred); P4's rate held to zero; namespace `12` |
| Application | `bnpl_application` (`-010`) | `bnpl_application_event` | `UNIQUE (decision_request_id)`; partial `UNIQUE (checkout_session_id)` over open; never committed `SUBMITTED`; `→ OFFERED` only beside its offer |
| Offer | `bnpl_offer` + `bnpl_offer_terms` (`-011`) | `bnpl_offer_event` | `UNIQUE (application_id)`, `UNIQUE (decision_id)`; the complementary expiry and acceptance clock conditionals; `expires_at` never after the session's; `→ ACCEPTED` only beside its purchase, obligation and agreement v1 (deferred); canonical terms and hash frozen |
| Checkout session (checkout's) | `checkout_session` (checkout `V004`, `-007`) | checkout's | `OPEN → COMPLETED` only with `financed_by_ref` (set once, exclusive with `payment_intent_ref`); no `COMPLETED_LATE` for a financed session |
| Purchase | `purchase` (`-013`) | `purchase_event` | `UNIQUE (checkout_session_id)`, `UNIQUE (offer_id)`; never committed `AUTHORISED`; `FINANCED` only beside its financing and entry; `FULLY_REFUNDED` only when Σ refunds = gross |
| Obligation | `bnpl_obligation` (`-013`) | `bnpl_obligation_event` | `UNIQUE (purchase_id)`; no money column; product and plan frozen; permit-only update while `ACTIVE`; closure reason `CHECK` and its act's presence |
| Refund | `bnpl_refund` + `bnpl_refund_adjustment` (`-021`) | — (born `APPLIED`, `INSERT`-only) | `UNIQUE (merchant_id, refund_key)`; Σ adjustments + excess = amount (deferred); Σ refunds ≤ gross per purchase |
| Dispute | `bnpl_dispute` (`-023`) | `bnpl_dispute_event` | partial `UNIQUE (purchase_id)` over open; the complementary deadline conditionals; `RESOLVED_REFUND` only beside its refund |
| Repayment reversal · waiver | `bnpl_repayment_reversal`, `bnpl_waiver` (`-024`) | the proposal rows' decided columns | four-eyes `CHECK`; one live reversal per repayment |
| Payoff | `bnpl_payoff_quote`, `bnpl_payoff_execution` (`-022`) | — | `UNIQUE (quote_id)`, `UNIQUE (obligation_id)`; `PAID_OFF_EARLY` only beside an execution |

Every trigger is `<table>_permits_only_machine_edges`, written by hand; every status `CHECK` a membership
list; raw-SQL terminal edges are refused for every role in each task's database tests.

---

## 7. Settled points

The owner's transition decisions (2026-10-16, `PHASE_12_PLAN.md` §2.2 and §24), as they bear on the machines:

| # | Decision | Where it lands in the machines |
|---|---|---|
| B1 | Both products: P4 (4 equal, first at purchase, then every 14 days, no interest) and PN (3/6/12 monthly with interest) | §4.1's plan shapes; §4.3's T-pur collects P4's instalment 1; the P4 and PN due dates (§3) |
| B2 | Merchant paid upfront net of fee on its normal on-demand payout; funded from `LENDING_CAPITAL` under L6 | §4.3 (L6 at the acceptance, `CapitalUnavailable`); §4.8 (no BNPL payout path); §4.11 (L6 at a principal-re-instating reversal) |
| B3 | Refunds latest instalments first; excess to the customer's wallet; the merchant fee pro-rated back by the cumulative method | §4.9's allocator, excess and fee return |
| B4 | Fleet `databaseTest`/`kafkaTest` skipped through Phase 12 (the criterion-7 deviation) | none in the machines — each task's own and adjacent DB suites prove its ranks |
| B5 | One credit decision per purchase; no spending limit; no ADR-0107 | §4.2: one application, one decision request, per session |
| B6 | Instalments collected from the wallet only | §4.13: no card-funded instalment, no system-initiated card charge |
| B7 | A refund after payout may drive the payable negative, bounded by the purchase's own net, recovered before any payout; `INV-MER-07` amended | §4.8, §4.9 |
| B8 | Every other open question takes the draft's default, "owner accepted default, 2026-10-16, review later" | OQ-2 `creditmath` (the engines §4.1 names); OQ-3 P4 remainder on instalment 1; OQ-4 PN no down payment; OQ-6 PN interest on refund (§4.9); OQ-9 merchant-key refund door (§4.9); OQ-10 silence is acceptance, 14 days (§4.10); OQ-11 separate purposes and `INV-BNPL-*`; OQ-12 the merchant sees only `NOT_ELIGIBLE` (§4.2); OQ-13 excess held as credit balance; OQ-14 late fees released (§4.9); OQ-15 refund after closure admitted (§4.5, §4.9); OQ-18 the validities (§3) |

The eighteen mandatory scenarios (`PHASE_12_PLAN.md` §13.2): 1, 2, 3, 14 §4.3–§4.5 · 4, 6 §4.13 · 5 §4.8 ·
7, 8, 9, 16, 17 §4.9 · 10 §4.10 · 11 §4.12 · 12 §4.11 · 13 §5 · 15 §4.2 · 18 §4.14.

## 8. Read one way here, for the building task to confirm

Points where the plan is silent, or says two things, and this document had to choose. Each is the plan's
until its task's design settles it; none is a decision the transition took.

- **The acceptance's session refusal** (`-013`): `409 bnpl.SessionNotPayable` (§5.2a, §7.1 of the plan),
  though §7.4 names `409 checkout.SessionNotPayable` and §9's error list names neither (it has `422
  bnpl.SessionNotEligible`, which this document keeps for the application door). The card door's refusal is
  checkout's existing `409 checkout.NotConfirmable`, not a new code.
- **The application's exits on a closed session** (`-010`, `-011`; `-010`'s half confirmed by `P12-TSK-010` - its only
  `WITHDRAWN` edge is the customer's withdrawal door): §5.1 lists "abandons checkout" under
  `WITHDRAWN` and "the checkout session expired/closed" under `CLOSED_UNDECIDED`. Here `WITHDRAWN` is only the
  customer's withdrawal door; any session no longer `OPEN` (expired, abandoned, or paid by another
  instrument) is found by the sweep and closes `CLOSED_UNDECIDED (SESSION_CLOSED)`. `BnplEligibilityAssessed`
  for an `APPROVED_BELOW_PURCHASE` closure is unspecified (§10 says "offer or decline") - *settled by `P12-TSK-011`:
  `NOT_ELIGIBLE`, as a decline (§4.3's as-built note).*
- **The obligation's closure reasons** (`-018`, `-021`): §5.5 says `REFUNDED_PARTIALLY_REPAID` is "refunds and
  repayments together"; here each reason is named by the closing act (§4.7).
- **When an agreement version is born** (`-021`, `-022`): §5.4 and §3.1 name "a payoff recalculation" and "a
  dispute outcome"; §12.6 point 6 says "whenever any unbilled instalment is cancelled or shortened". Here: a
  refund (or a dispute's refund outcome) under §12.6's rule; a payoff bills early beside its execution
  (lending's shape) and makes no version.
- **A credit balance on a `CLOSED` obligation** (`-021`): OQ-13 holds an unpostable excess in
  `BNPL_CREDIT_BALANCE`, OQ-15 admits a refund after closure, and §12.12 point 2 and `INV-BNPL-09` require a
  `CLOSED` obligation all zero — a refund after closure whose wallet cannot be credited satisfies at most
  two of the three. Likewise §14's "customer's wallet closed" leaves the closure refund nowhere to post.
- **The dispute** (`-023`): (a) §5.8's first line routes "accepts" to `MERCHANT_RESPONDED`, its second to
  `RESOLVED_REFUND`; here acceptance resolves at once. (b) The four-eyes threshold for the operator's
  resolution is unspecified, and the plan's one-act T-dsp has no proposal row for a second person; here ★
  marks it, and `-023` decides whether it takes the §4.11 proposal shape. (c) §9 bounds the disputed amount by
  the purchase; here by `G − Σ refunds` at opening — and a merchant refund between opening and resolution may
  leave less refundable than was disputed: the outcome's refund is then capped at what remains, and a zero
  remainder resolves with no refund row. (d) The hold's record: `bnpl_condition_event`'s `UNIQUE
  (obligation_id, business_date, kind)` cannot hold an opening and a withdrawal on the same date, so here the
  hold is read from the dispute row's state and history, not a once-per-date condition row. (e) A dispute on a
  `CLOSED` obligation is admitted (the purchase is not terminal) with a vacuous hold. (f) No event announces
  the merchant's response and no audit act the withdrawal.
- **The operator refund door** (`-021`): A19 and OQ-9 place one "beside" the merchant's; §9 has no route.
- **Late fees on a partly cancelled instalment** (`-021`): OQ-14 releases fees on "an instalment later
  cancelled"; here only when the refund cancels the instalment's whole unpaid principal.
- **Waivers** (`-024`): F7 posts fee waivers only; no PN interest waiver is planned (lending's rule 9 has no
  BNPL counterpart).
- **Repayment events** (`-018`): §10 has no repayment event — a repayment that settles no instalment
  publishes nothing (its row and entry are the record).
- **Operator keys** (`-004`, `-023`, `-024`): the plan's `bnpl.…:EMPLOYEE:<id>` keys meet the platform's
  `CUSTOMER`-typed operator sessions (`LENDING_LIFECYCLES.md` §4.1, A5-04). *Settled for `-004` (2026-10-17): the key is the session actor's type,
  `bnpl.terms:CUSTOMER:<id>` today, as lending's; `-023` and `-024` inherit it.*

*Reconciled by the transition's consistency pass (2026-10-16), after this section was written — the plan now says, and
these points above stand settled rather than open: the acceptance's refusal is `409 bnpl.SessionNotPayable` everywhere
(`PHASE_12_PLAN.md` §7.4 and §9 corrected), the losing card confirmation checkout's existing refusal; an agreement
version is born by a refund that cancels or shortens an unbilled instalment (a dispute's refund outcome included) and
never by a payoff (§5.4 aligned to this document); a refund after closure into a wallet that cannot take the credit is
refused with nothing written, and a full refund whose excess the wallet cannot take closes the obligation in the
transaction that refunds the held credit (§6 `INV-BNPL-09`, §12.6 points 7 and 8 — ADR-0105); a merchant's acceptance
of a dispute resolves it at once, a refund while a dispute is open is refused `409 bnpl.DisputeOpen`, the response
window and the four-eyes threshold are terms fields, and the deadline sweep's claim holds no row (ADR-0106). The other
points remain their tasks' to confirm.*
