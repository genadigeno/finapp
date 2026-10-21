# ADR-0102 — BNPL products, terms and the versioned agreement: pay-in-4 on a BNPL schedule engine, pay-in-N on lending's engines, one decision per purchase

Status: Proposed
Date: 2026-10-16
Phase: 12
Context: BNPL · Credit · Checkout · Identity · Platform · Audit
Supersedes: nothing. Applies ADR-0086 (policy as versioned data, four-eyes activation, engines
versioned in code), ADR-0087 §2's seal (canonical JSON, SHA-256, re-verified on replay), ADR-0092
(lending's terms and the immutable, versioned agreement) to BNPL, ADR-0093, ADR-0094 and ADR-0095
(lending's schedule, accrual and allocation engines, reused through `creditmath`, ADR-0101) and
ADR-0091 (consumption through credit's port). Rests on `PHASE_12_PLAN.md` §2.2 (B1, B5, B6), §2.3
(A2–A7, A13, A16–A18, A22), §3.3, §5.1–§5.4, §12.1–§12.3, §24 (OQ-3, -4, -17, -18) and
`INV-BNPL-03`, `INV-BNPL-05`.

## Context

1. **Two products, chosen by the owner** (B1). Pay-in-4 (`BNPL_PAY_IN_4`): four equal instalments,
   the first collected at purchase, the next three every 14 days, no customer interest, a merchant
   fee, late fees per the pinned terms. Pay-in-N (`BNPL_PAY_IN_N`): 3, 6 or 12 monthly instalments
   with customer interest (fixed rate, ACT/365F daily accrual — the Phase 11 conventions), a merchant
   fee optional.
2. **Lending's schedule engine fits one of them.** `ScheduleEngineV1` is a level, actual-day monthly
   annuity whose first due date is the first date on the repayment day at least
   `min_first_period_days` after the accrual start (ADR-0093 §2); it refuses any frequency but
   `MONTHLY`. Pay-in-N is exactly that shape; pay-in-4 is biweekly, interest-free, and its first
   instalment falls on the purchase date itself.
3. **Credit's product model cannot express either.** `CreditProduct` is a closed enum whose
   `TermBounds` are months only, mirrored by `product IN (…)` `CHECK`s in credit `V004`, `V008`,
   `V010`, `V011` and `V013`; `DecisionRequest`'s rule `product.revolving() ==
   termMonths.isPresent()` knows a term range or no term — not a fixed plan (P4) nor a discrete set
   of terms (PN). Request validity is 7 days and decision validity 30 — longer than any checkout.
4. **A BNPL offer lives inside a checkout session.** The price is fixed by the merchant; the session
   expires on checkout's clock; the card door must stay usable if BNPL is declined (plan §5.2a).
5. **What the customer agreed to must be provable and replayable** — the ADR-0092 reasoning: terms
   change, an agreement accepted under last year's terms is serviced, explained and replayed under
   them, and a refund that reshapes the schedule is a new version, never an edit.

## Decision

1. **The two products.**

   | | `BNPL_PAY_IN_4` (P4) | `BNPL_PAY_IN_N` (PN) |
   |---|---|---|
   | Instalments | 4 | 3, 6 or 12 (the customer's plan) |
   | Frequency | `BIWEEKLY` (a BNPL vocabulary member, not lending's `RepaymentFrequency`) | monthly |
   | First due | the purchase's business date, collected in the purchase transaction | one month after the purchase on its day-of-month, clamped — no down payment (A6, OQ-4) |
   | Customer interest | none — the terms `CHECK` rate = 0, no accrual rows | fixed nominal rate, ACT/365F, daily |
   | Merchant fee | yes (merchant's BNPL fee schedule) | optional per schedule (OQ-17) |
   | Customer fees | late fee only; no customer fee on P4 (OQ-17) | late fee only |
   | Schedule engine | `BNPL_SCHEDULE_ENGINE_V1` (new) | `SCHEDULE_ENGINE_V1` (lending's, through `creditmath`) |
   | Accrual / allocation | none / `ALLOCATION_ENGINE_V1` | `ACCRUAL_ENGINE_V1` / `ALLOCATION_ENGINE_V1` |
   | Amount bounds | credit's product bounds (e.g. EUR 50–1,000, A16) | credit's product bounds (e.g. EUR 200–5,000) |

   EUR only (A2): the purchase's currency is the session's and the product's; a session in another
   currency is not BNPL-eligible (`422 bnpl.SessionNotEligible`). The amount bounds are credit's —
   one truth, the `LoanProductBounds` precedent — and are configuration, not code.

2. **Pay-in-4 on `BNPL_SCHEDULE_ENGINE_V1`** (`P12-TSK-008`). Pure, static, no clock, no database:
   `n = 4`; `D1` = the purchase's business date in the servicing zone (`UTC`, A22) on the database
   clock; `Dk = D1 + 14·(k − 1)` calendar days, no business-day adjustment (A5); `A = floor(P / 4)` to
   the minor unit for instalments 2–4 and instalment 1 `= P − 3A` — **the remainder on instalment 1**,
   collected at purchase (OQ-3, owner accepted default). Principal is conserved exactly
   (`INV-BNPL-05`): EUR 100.01 is 25.01, 25.00, 25.00, 25.00. The engine refuses any other shape (an
   `n` other than 4, a non-zero rate, another frequency) rather than approximating. It is a new
   engine, not a parameterisation of lending's: `ScheduleEngineV1`'s minimum first period and
   actual-day interest are exactly what P4 lacks. Its version identity is a new member beside the
   existing ones; no existing `EngineVersion` member changes (plan §20).

3. **Pay-in-N on lending's engines, through `creditmath`.** `ScheduleEngineV1` gains a
   `ScheduleTerms` overload beside its `LoanTerms` entry (ADR-0101 decision 5), every golden hash
   unchanged: fixed rate, monthly, level actual-day annuity, ACT/365F, instalment rounding `UP`,
   interest rounding `HALF_EVEN`, `n ∈ {3, 6, 12}`, accrual start the purchase's business date. A6's
   first due date is the engine's own rule with the repayment day the purchase's day-of-month and a
   first-period floor between 1 and 28 days (any such value yields "one month later, clamped"; a
   floor above 28 would skip a month after the 29th–31st) — pinned on the terms, never inferred.
   `ACCRUAL_ENGINE_V1` accrues daily with cumulative rounding once per period; billing bills actual
   accrued interest, so the projection is not the bill (Phase 11 §12.2 point 5).
   `recalculateAtInstalment` (same instalment, least term) re-projects after a refund (a payoff bills early beside its execution and re-projects nothing).
   `ALLOCATION_ENGINE_V1` serves both products — oldest due first, `FEES → INTEREST → PRINCIPAL`,
   excess `HOLD_AS_CREDIT` — built from its records' canonical constructors (`AllocationRules`,
   `DueState`, `AccrualTerms`), never from lending's `of(LoanTerms)`.

4. **Terms as four-eyes versioned data** (`P12-TSK-004`, `bnpl V002`). `BnplTermsVersion`: one row
   per (product, version), immutable once proposed, its history in `bnpl_terms_version_event`, its
   term set in the `INSERT`-only `bnpl_terms` (split along the money line, lending `V003`'s
   precedent). Machine `PROPOSED → ACTIVE → RETIRED`, `PROPOSED → REJECTED`; one `PROPOSED` and one
   `ACTIVE` per product (partial uniques); activation retires the predecessor in the same
   transaction. Proposed by a holder of `BNPL_ADMINISTER`, decided by a different person (domain and
   `CHECK (decided_by <> proposed_by)`), serialised per product on **advisory namespace `12`**
   (`hashtext(product)`), keyed `bnpl.terms:EMPLOYEE:<id>`, the decision stamped
   `clock_timestamp()` after the lock (the Phase 11 gate's A2-F3, lending `V030`); audited
   `bnpl.TermsVersionProposed` / `…Activated` / `…Rejected`; the self-approval refusal audited
   `FAILED`. **Never activated by a migration**: production ships with none (ADR-0101 decision 14).

   | Group | Fields (Phase 12 values) |
   |---|---|
   | Identity | product, currency (EUR), jurisdiction (`NEUTRAL_EUR`, A1) |
   | Plan | P4: `n = 4`, `BIWEEKLY`, 14 days; PN: allowed terms {3, 6, 12}, first-period floor (decision 3) |
   | Price | PN: nominal annual rate (decimal), rate type `FIXED`; P4: rate 0 by `CHECK` |
   | Timing | offer validity (30 min, OQ-18), grace |
   | Fees | late fee (amount, `late_fee_day`, cap per agreement, A13); no other customer fee |
   | Conventions | day count `ACT_365F` (PN), `servicing_zone` `UTC` (A22), rounding (P4 `DOWN` with the remainder first; PN `UP` / `HALF_EVEN`) |
   | Allocation | `OLDEST_DUE_FIRST`; `FEES, INTEREST, PRINCIPAL`; `HOLD_AS_CREDIT`; auto-collection on, partial, daily retry (A7) |
   | Delinquency | bucket bounds, default at 90 days (A14, L7), cure rule |
   | Disputes | the merchant's response window (14 days, OQ-10) |
   | Engines | P4: `BNPL_SCHEDULE_ENGINE_V1`, `ALLOCATION_ENGINE_V1`; PN: `SCHEDULE_ENGINE_V1`, `ACCRUAL_ENGINE_V1`, `ALLOCATION_ENGINE_V1` |
   | Document | agreement template id and version (code-registered) |

   **Not BNPL terms:** the merchant fee is the merchant's (`fee_schedule_version`, `INV-MER-03`),
   pinned per purchase in `bnpl_fee_pin` (ADR-0103); the amount bounds are credit's. **The excluded
   features have no field at all** — no penalty interest, prepayment fee, APR, cooling-off or
   withdrawal period, statutory cap or regulated-credit classification (A1, L8; plan §11): a
   jurisdiction that needs one adds a field in a new terms schema version and a reviewed engine
   version. A terms version naming a convention its engines do not implement is refused whole at
   proposal (lending's `TermsInvalid` precedent; the code catalogued by `P12-TSK-004`).

5. **One credit decision per purchase** (B5, owner decision; A3). Each BNPL application opens exactly
   one credit `DecisionRequest(product, amount = the session's gross, term = the PN plan)` through
   the BNPL door in its own transaction (T-app); P4 sends no term. Credit decides asynchronously
   through its sweep and simulated providers; the checkout waits on `202` and polls. The offer is
   exactly the approved amount: a reduced approval below the price is not offerable (the price is
   fixed), and the application closes `CLOSED_UNDECIDED (APPROVED_BELOW_PURCHASE)`. **There is no
   pre-approved spending limit in Phase 12** — a decision for a limit with each purchase drawn
   against it (ADR-0100's exposure shape) was considered and refused — **and therefore no ADR-0107.**
   One open application per (party, product) (credit's partial unique) and per checkout session
   (`UNIQUE (checkout_session_id)` over open states); a session that expires while awaiting a
   decision closes the application and the approval lapses unconsumed.

6. **Credit learns BNPL** (`P12-TSK-001`, credit `V022`). `CreditProduct.BNPL_PAY_IN_4` and
   `BNPL_PAY_IN_N`, the five `product IN (…)` `CHECK`s widened in one migration. **A term shape per
   product replaces the boolean `product.revolving() == termMonths.isPresent()`**: `NONE` for a line;
   `MONTHS` for a loan (its range) and for PN (its allowed set {3, 6, 12}); `FIXED_PLAN` for P4 (no
   term sent, one refused). BNPL validities: request ≤ 1 hour, decision ≤ 1 day (OQ-18). Affordability
   rule kinds for both (P4: the largest instalment × a stress factor; PN: the annuity at the policy's
   stress rate — credit's design). `ConsumerKind.BNPL_AGREEMENT` (reserved for Phase 12 in its
   javadoc and ADR-0091) and the consumption kind `CHECK`, through the existing `SECURITY DEFINER`
   path: one decision funds at most one agreement (`INV-BNPL-03`). A policy fixture per product for
   the simulators. `bnpl` never reads a score and the merchant never learns a reason (OQ-12: only
   `NOT_ELIGIBLE` for that session).

7. **The offer pins the terms and seals them** (`P12-TSK-011`). In the origination sweep's T-off,
   under the application's lock and with the session still open: the terms version `ACTIVE` at the
   offering instant, read `FOR SHARE`; the plan(s) and the illustrative schedule from the pinned
   engines; the full term set as canonical JSON and `terms_sha256`; `UNIQUE (application_id)`,
   `UNIQUE (decision_id)`. **`expires_at = LEAST(decision.valid_until, the session's expires_at,
   offered_at + 30 min)`** on the database clock (A17) — a BNPL offer never outlives its checkout
   session. `OFFERED → ACCEPTED | DECLINED | EXPIRED`; acceptance and expiry are complementary
   conditionals at the boundary, exactly one wins.

8. **The agreement: immutable versions, one row each** (`P12-TSK-013`). `bnpl_agreement` is
   `INSERT`-only for every role, `UNIQUE (obligation_id, version)`; version 1 is born in the purchase
   transaction from the offer, its `canonical_terms` self-contained `text` (money as minor units plus
   currency, rates as decimal strings, every convention named; `text`, not `jsonb`, so the hash
   survives — lending's finding). The acceptance echoes `terms_sha256` (a mismatch is
   `409 bnpl.TermsChanged`, nothing written); `bnpl_acceptance` records the offer, the version, the
   hash, the template, the document's SHA-256, identity, session and assurance (`MULTI_FACTOR`, A18),
   `accepted_at` on the database clock, channel, client IP and user agent (`CONFIDENTIAL`). Acceptance
   is not consent (`INV-IDN-04`). **Version n+1** is born for a refund that cancels or shortens any
   unbilled instalment — a dispute resolved as a refund is a refund (ADR-0106) — never for a payoff, which bills early beside its execution (lending's shape; aligned by
   the transition's consistency pass, 2026-10-16); billed instalments are kept, unbilled ones superseded by schedule version n+1
   (Phase 11 §12.2 point 7). A dispute upheld changes no version (it re-bills nothing). Nothing is
   updated in place (`INV-HIST-04`). Every servicing row names its agreement version and, where an
   engine computed it, the engine version; `BnplReplayProof` re-derives them and re-verifies every
   `terms_sha256`.

9. **Instalments are collected from the wallet only** (B6, owner decision; A7). The source is the
   customer's `CUSTOMER_WALLET`, funded by the existing pay-ins. P4's instalment 1 is taken in the
   purchase transaction — a short wallet refuses `422 bnpl.InsufficientFunds` with nothing
   committed, and the customer may top up and retry the same offer before it expires. Later
   instalments are auto-collected on the due date (partial allowed, retried daily while due — the
   lending A21 shape); the customer may repay or pay off any time, without fee (A21, L8). **No
   card-funded instalment and no new system-initiated card charge**: the only `SYSTEM` door in
   payments stays the outbound withdrawal.

## Alternatives Considered

### Pay-in-4 through `ScheduleEngineV1` with a biweekly frequency
Pros:
- One schedule engine.

Cons:
- Changes lending's engine and `RepaymentFrequency` after their exit gate; P4 has no interest and
  no minimum first period, so the annuity's machinery computes nothing it needs.

Refused: a small pure engine of its own.

### The P4 remainder on the last instalment
Pros:
- Lending's convention (the last instalment the remainder).

Cons:
- The remainder would sit on the instalment most exposed to delinquency and refund; on instalment
  1 it is collected at purchase, so the three open instalments are equal.

Refused (OQ-3, owner accepted default).

### A pre-approved BNPL spending limit
Pros:
- No credit latency at checkout; one decision serves many purchases.

Cons:
- A limit is a revolving exposure with its own lifecycle, draws, expiry and re-assessment — the
  ADR-0100 machinery, and a seventh ADR.

Refused by the owner (B5): one decision per purchase; no ADR-0107.

### Card-funded instalments
Refused by the owner (B6): a system-initiated card charge does not exist, and a card share would
open payments' disputes on the agreement (`INV-DSP-01` would need the BNPL bound, plan §12.7).

### BNPL validities equal to credit's loan validities (7 days, 30 days)
Refused: an approval reserving exposure for a month for a checkout that lasts minutes is phantom
exposure (OQ-18).

### A counter-offer at the reduced approved amount
Refused: the price is the merchant's, not the platform's; an offer below it pays for nothing.

### Terms as code constants, or lending's terms table
Refused: the ADR-0092 reasons (a price change would be a deployment), and lending's table would
couple BNPL's products to lending's machine and grants (ADR-0101).

## Consequences

Positive:
- Every instalment is reproducible from one agreement version and its engines; P4 and PN share the
  allocation engine and every servicing pattern.
- The offer is bounded by the session, so no BNPL commitment outlives the commercial fact it
  finances.
- The customer's acceptance is bound to the exact terms the engines read.

Negative:
- A third schedule engine identity in code for the life of the longest BNPL agreement.
- Checkout waits on an asynchronous credit decision per purchase (plan §21, recorded): a referral
  to manual review that outlasts the one-hour request or the session closes the application
  undecided.
- Credit's product model changes shape (the term shape), touching every request validation.

Operational impact: `finapp.bnpl.terms.active{product}`; activation is a runbook act.
Security impact: `BNPL_ADMINISTER` four-eyes; `MULTI_FACTOR` for the application and the
acceptance; acceptance evidence `CONFIDENTIAL`; amounts and rates `RESTRICTED-FINANCIAL`.
Financial impact: the price, the plan and every convention of every BNPL agreement fixed at
acceptance and changed only by a refund or a dispute's refund outcome — each a new version (a payoff closes
the agreement without one).

## Invariants / Constraints

`INV-BNPL-03` (one decision funds at most one agreement), `INV-BNPL-05` (a schedule conserves
principal), `INV-LND-03` (PN through `ScheduleEngineV1`), `INV-LND-02` and `INV-LND-09` (PN accrual
and its declared conventions, applied by analogy), `INV-LND-05` (terms pinned and immutable, by
analogy), `INV-HIST-04`, `INV-IDN-04`, `INV-CRD-02`, `INV-CRD-04`, `INV-AUD-01`…`04`, ADR-0086,
ADR-0087 §2, ADR-0092.

## Follow-up

- Built by `P12-TSK-001` (credit), `-004` (terms versions, namespace `12`), `-008` (the P4 engine),
  `-002` (the PN overload, with the extraction), `-010` (the application), `-011` (the offer and the
  origination schedule), `-013` (agreement v1, evidence, the wallet-paid instalment 1) and the
  refund and dispute tasks for versions n+1 (`-021`, `-023`); `-022`'s payoff makes none.
- `P12-TSK-008`'s design places `BNPL_SCHEDULE_ENGINE_V1`'s identity (a `bnpl` vocabulary or an
  additive `creditmath` member) without changing any existing member.
  **Placed by `P12-TSK-008` (2026-10-17): in `bnpl`** - the identity is `BnplEngine.BNPL_SCHEDULE_ENGINE_V1`
  (declared by `P12-TSK-002`) and the engine `BnplScheduleEngineV1` beside it, taking the pinned `BnplTerms` whole so
  that "refuses any other shape" judges the terms' own fields (`SHAPE_NOT_IMPLEMENTED`); `creditmath` holds no bnpl
  vocabulary and gains nothing. Refusals typed: `SHAPE_NOT_IMPLEMENTED`, `INVALID_REQUEST`, `PRINCIPAL_NOT_POSITIVE`,
  `INSTALMENT_OF_NOTHING` (`A = 0`).
- `P12-TSK-001`'s design states whether a BNPL request may be referred to manual underwriting
  within its one-hour validity, or is decided automatically only. **Stated by `P12-TSK-001`
  (2026-10-16): automatically only** - `CreditProduct.referable()` is false for both BNPL products; a
  BNPL policy's fallback is `DECLINE` and none of its rules refers (`credit.PolicyIncomplete`, and
  `credit_policy_bnpl_never_refers` for the fallback), and `underwriting_case`'s product `CHECK` holds
  the lending products only, so a BNPL underwriting case is unrepresentable. Also as built: pay-in-4's
  affordability is its largest instalment (A4's first, computed by credit) times the policy's
  `plan_stress_factor_bps` (pay-in-4's versions only, 10,000-40,000); a request's `RequestOrigin`
  (`CUSTOMER_DOOR` | `BNPL_APPLICATION`) binds BNPL requests to the BNPL application; four-eyes
  thresholds EUR 500.00 (P4) and EUR 2,500.00 (PN), moot while no BNPL request is referred.
- **Decision 4 built by `P12-TSK-004` (2026-10-17)**, `bnpl V002`, read one way on six points, each recorded in its backlog entry's as-built: the term set also holds the collection retry cadence (A7), the dispute four-eyes threshold (ADR-0106 decision 5 names it a terms field) and the payoff horizon (`P12-TSK-022`'s reused quote shape) - an immutable term set cannot gain a field later without inventing its value for the versions before it; the jurisdiction is the agreement template's (`BnplAgreementTemplate`, lending's reading), not an Identity field; no rate-type column (fixed is the only kind, OQ-4); the servicing zone `UTC` only; pay-in-N's plans an ascending choice among {3, 6, 12}; the reader the application and the offer pin is `BnplTermsAdministration#pinActive` (`422 bnpl.ProductNotOffered` with none `ACTIVE`). The schedulability judgement at the door: pay-in-N by `SCHEDULE_ENGINE_V1`'s margined `judgeAmortisation` over every purchase day 1-31, credit's smallest amount and the longest plan offered; pay-in-4 by its own check (credit's smallest purchase at least one minor unit per instalment). *(Since `P12-TSK-008`, that check is `BNPL_SCHEDULE_ENGINE_V1`'s own `judgeSchedulable`, the door mapping its `INSTALMENT_OF_NOTHING` to `422 bnpl.TermsNotAmortising` - one statement of the rule.)*
- **Two corrections found by the BNPL storm (`P12-TST-001`, 2026-10-20), each with its regression case and probe.**
  (a) **bnpl `V017` - the record carries the lock's order** (lending `V025`'s rule, ADR-0092's follow-up (d)): the
  sequence `bnpl.record_order` drawn into `recorded_order` on every fact `BnplReplayProof` walks (the financing, agreement
  and schedule versions, billings, accruals, credit-balance applications, repayments, collection attempts, refunds,
  conditions, fees, payoff quotes and executions, the closure, reversals, waivers and their decisions, disputes and their
  events), every writer drawing it after B4, and a reversal's `decided_order` by its trigger at the decision.
  `JdbcBnplRecordStore` re-stamps an obligation's record in that order before the replay reads it - an instant that
  disagrees with the order moved a microsecond past its predecessor - because a wall clock is not an order: the storm's
  first load round found the database clock stepped back between an obligation's facts, and the replay `DIVERGED` on a
  record that was right (`BnplProofDatabaseTest#aClockSteppedBackReplaysInTheRecordsOrder`). (b) **The agreement
  version in force is read AFTER B4, never beside it**: `JdbcBnplServicingStore.lock` and
  `JdbcBnplRepaymentStore.lockOwnedBy` took the obligation `FOR UPDATE OF o` and its latest agreement version through a
  `LATERAL` join in one statement - and READ COMMITTED's re-check of a row it waited for refreshes only the locked row, so
  a repayment (or a step, accrual, payoff, waiver or reversal) that waited behind a refund writing version n+1 read
  version n, and the repayment's trigger refused it as a `500`. The lock is now its own statement and the version is read
  by the next (`BnplProofDatabaseTest#aRepaymentBehindARefundReadsTheVersionItCommitted`). Neither changed a figure: the
  first was a false alarm of the proof, the second a refusal of a valid act.
- Accepted by the Phase 12 exit review (`P12-DOC-001`), read against the code first.
