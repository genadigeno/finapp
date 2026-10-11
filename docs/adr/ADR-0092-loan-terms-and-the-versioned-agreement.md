# ADR-0092 — Loan terms as versioned data and the immutable, versioned agreement

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Identity · Platform · Audit
Supersedes: nothing. Applies ADR-0086's shape (policy as versioned data, four-eyes activation, the
active version answerable at any instant, engines versioned in code) and ADR-0087 §2's seal
(canonical JSON, SHA-256, re-verified on replay) to lending's terms and contracts; `INV-HIST-04`
(history kept, never rewritten). Rests on `PHASE_11_PLAN.md` §2.3 (L4–L8), §2.4, §5.4, §11, §12.1
and `INV-LND-05`, `INV-LND-09`.

## Context

1. **A loan is a contract that outlives the product that sold it.** Rates, fees and servicing
   rules change; a loan accepted under last year's terms must be serviced, explained and replayed
   under last year's terms, years later, without archaeology in code history.
2. **The figures depend on conventions, not only on numbers.** Day count, zone, rounding,
   allocation order, delinquency bounds and the minimum-payment rule each change a borrower's
   bill. An unstated convention is an unreproducible bill (`INV-LND-09`).
3. **The owner chose a neutral jurisdiction** (L4: a neutral EUR reference product, no statutory
   APR formula, withdrawal right, statutory notices or caps) and fixed conventions (L5 ACT/365F,
   L6 allocation, L7 default at 90 days, L8 no penalty interest, no prepayment fee, no APR
   display). A real jurisdiction later must be configuration plus review, never a rewrite.
4. **What the customer agreed to must be provable.** The acceptance must bind the exact terms the
   customer saw, and an amendment must be a new agreement the customer accepts, never an edit.

## Decision

1. **`LoanTermsVersion`: versioned product data, four-eyes** (`P11-TSK-005`). One row per
   (product, version), immutable once proposed; history in `loan_terms_version_event`. Machine
   `PROPOSED → ACTIVE → RETIRED`, `PROPOSED → REJECTED`; one `PROPOSED` and one `ACTIVE` per
   product (partial uniques); activation retires the predecessor in the same transaction. Proposed
   by a person holding `LENDING_ADMINISTER`, approved by a different person (domain and
   `CHECK (approved_by <> proposed_by)`), serialised per product on advisory namespace `11`
   (`hashtext(product)`); keyed `lending.terms:EMPLOYEE:<id>`; audited `lending.TermsVersionProposed`
   / `…Activated` / `…Rejected`. **Never activated by a migration** (the credit policy's precedent,
   `OPERATIONS_RUNBOOK.md` §6.1): production ships with none active (ADR-0090 §9). The version
   active at any past instant is answerable from the rows (`GET …/terms?product=&at=`).

2. **What a terms version holds** — every field the engines read, nothing they infer:

   | Group | Fields (Phase 11 values) |
   |---|---|
   | Identity | product (`PERSONAL_LOAN`, `CREDIT_LINE`), currency (EUR), jurisdiction (`NEUTRAL_EUR`, A1) |
   | Price | nominal annual rate (decimal), rate type (`FIXED` only) |
   | Bounds | amount or limit range, term range (loan), allowed repayment or statement days (1–28) |
   | Timing | offer validity (14 days), `min_first_period_days` (15), disbursement deadline (24 h), grace |
   | Fees | origination (amount or bps, `DEDUCTED`, default 0, A17); late fee (amount, `late_fee_day`, cap; A18) |
   | Conventions | day count (`ACT_365F`, A22), `servicing_zone` (`UTC`, A2), instalment rounding `UP`, interest rounding `HALF_EVEN` |
   | Allocation | order (`OLDEST_DUE_FIRST`; `FEES, INTEREST, PRINCIPAL`), overpayment treatment (`HOLD_AS_CREDIT` loan; `PAY_DOWN_PRINCIPAL` line), auto-collection (on, partial, daily retry; A21) |
   | Delinquency | bucket bounds (1–29, 30–59, 60–89, 90+), default threshold (90 days, L7), cure rule (A8) |
   | Line | statement day, payment-due days (25, A23), minimum-payment floor and ratio (A24) |
   | Engines | `SCHEDULE_ENGINE_V1`, `ACCRUAL_ENGINE_V1`, `ALLOCATION_ENGINE_V1`, `STATEMENT_ENGINE_V1` |
   | Document | agreement template id and version (code-registered) |

   **The excluded features have no field at all.** Phase 11's terms schema carries no penalty or
   default rate, no prepayment fee, no APR, no withdrawal period and no statutory cap (L4, L8). A
   jurisdiction that needs one adds a terms field in a new terms schema version and a reviewed
   engine version — configuration plus review.

3. **Validated at proposal, refused whole.** A terms version naming a convention its engine
   version does not implement (a day count other than `ACT_365F`, a rate type other than `FIXED`,
   an allocation order the engine does not know) is refused `422 lending.TermsInvalid`; V1 engines
   refuse any terms they cannot compute rather than approximating. A loan terms version that cannot
   amortise at its own declared bounds (rate, amount and term ranges, the longest first period) is
   refused at the proposal door, `422 lending.TermsNotAmortising` (ADR-0093 §5).

4. **The offer pins the terms and seals them** (`P11-TSK-011`). From the approved decision and the
   terms version **`ACTIVE` at the offering instant**, read `FOR SHARE` — the version recorded on the
   application at submission is informational, never what the offer pins: principal or limit = the
   decision's approved amount and term (A20, no counter-offer); the full term set rendered as
   canonical JSON and hashed (`terms_sha256`); `expires_at = LEAST(decision.valid_until, offered_at +
   offer_validity)` on the database clock. If a specific offer would still yield a non-positive
   principal portion, the origination step makes no offer and closes the application
   `CLOSED_UNDECIDED` with reason `TERMS_NOT_AMORTISING` (ADR-0093).

5. **The agreement: immutable versions, one row each** (`P11-TSK-012`). `loan_agreement` is
   `INSERT`-only for every role: `(id, loan_id, version, offer_id | amendment_id, decision_id,
   terms_version_id, template_id, template_version, engines, canonical_terms jsonb, terms_sha256,
   effective_from, created_at)`, `UNIQUE (loan_id, version)`. Version 1 is born in the acceptance
   transaction from the offer. `canonical_terms` is **self-contained** — money as minor units plus
   currency, rates as decimal strings, every convention named — so it is readable without joining
   a terms version later retired. Canonicalisation (sorted keys, no insignificant whitespace, no
   floating-point literal, UTF-8) is the code's, versioned with the template; replay recomputes
   the hash and compares it.

6. **The customer binds the hash.** The acceptance echoes `terms_sha256`; a mismatch is
   `409 lending.TermsChanged` and nothing is written. `loan_acceptance` (`INSERT` only) records
   the offer, agreement version, `terms_sha256`, template id and version, the SHA-256 of the
   document bytes shown, the identity, session and assurance level (`MULTI_FACTOR`, A19),
   `accepted_at` on the database clock, channel, client IP and user agent (`CONFIDENTIAL`, A13).
   Acceptance is not consent (`INV-IDN-04`): no consent purpose is created.

7. **An amendment is agreement version n+1, accepted by the customer.** A person proposes it, a
   second approves it (ADR-0099), and the customer accepts it (`MULTI_FACTOR`); the acceptance is
   conditional on the agreement version the amendment was drawn against still being current, so
   an amendment approved against vN lapses if vN+1 came first (scenario 8,
   `409 lending.AmendmentLapsed`). A partial prepayment's recalculation (`P11-TSK-028`, cut
   candidate) is also a version n+1. Nothing is ever updated in place; earlier versions stay
   readable for the explanation and the replay.

8. **Every servicing row names its agreement version** — and, where an engine computed it, the
   engine version: schedule versions, instalments, billings, statements, accruals, allocations,
   fee assessments and payoff quotes. "Why is this instalment €213.47?" is answered from rows:
   agreement vN (hash) → schedule vN → billing (period, accruals summed) → allocations → journal
   entries.

9. **Engines are versions in code, never edited.** A change of arithmetic or rule semantics is a
   new engine version; old versions stay in the code so every agreement replays under the engine
   it was serviced by (`LoanReplayProof`, `P11-TSK-029`; the battery `P11-TST-002` replays in a
   second JVM).

## Alternatives Considered

### Terms as code constants
Pros:
- No administration, no four-eyes, no table.

Cons:
- A price change is a deployment; the terms in force at a past instant are a git question; an
  operator cannot see them; the L4–L8 conventions would be code archaeology.

Refused (the ADR-0086 lesson).

### Pin only `terms_version_id` on the loan
Pros:
- Smaller rows; one source of truth for each term.

Cons:
- The agreement would depend on the terms table's future integrity and on joins to retired rows;
  an amendment (vN+1) with different terms has no terms version of its own to point to.

Refused: the canonical copy and its hash on every agreement version.

### A mutable agreement row, updated by amendments
Pros:
- One row per loan.

Cons:
- History destroyed (`INV-HIST-04`); the acceptance evidence would bind terms that no longer exist.

Refused.

### The document (PDF) as the contract of record
Pros:
- What lawyers recognise.

Cons:
- Not machine-servicable; the engines would need a second, unbound representation. The document's
  hash is recorded beside the canonical terms; the canonical terms are what the engines read.

Refused as the record; kept as evidence.

### Optional fields for penalty interest and prepayment fees, defaulted to zero
Pros:
- A future jurisdiction needs only data.

Cons:
- Unreviewed engine paths for features the owner excluded (L8), exercised by nothing; a non-zero
  value set by a person would activate legally sensitive behaviour without review.

Refused: no field until a reviewed engine version needs one.

## Consequences

Positive:
- Every bill is reproducible from one agreement version and its engines; a retired product still
  services its loans exactly.
- The customer's acceptance is cryptographically bound to the terms the engines read.

Negative:
- Two copies of each term (terms version and agreement) — deliberate, the agreement being the
  self-contained, sealed one.
- Engines accumulate versions in code for the life of the longest loan.

Operational impact: `finapp.lending.terms.active{product}`; activation is a runbook act (§7).
Security impact: `LENDING_ADMINISTER` four-eyes; acceptance evidence `CONFIDENTIAL`; amounts and
rates `RESTRICTED-FINANCIAL`.
Financial impact: the price and every convention of every loan fixed at acceptance and changed
only by an accepted amendment.

## Invariants / Constraints

`INV-LND-05` (terms pinned and immutable), `INV-LND-09` (conventions declared), `INV-HIST-04`,
`INV-IDN-04`, `INV-AUD-01`…`04`, ADR-0086, ADR-0087 §2.

## Follow-up

- Built by `P11-TSK-005` (terms versions; registers advisory namespace `11` unless `-004` lands
  first), `P11-TSK-011` (the offer, its canonical terms and hash), `P11-TSK-012` (agreement v1 and
  acceptance evidence), `P11-TSK-027` (amendments as vN+1) and `P11-TSK-029` (the replay
  re-verifying every `terms_sha256`).
- **As built by `P11-TSK-005` (2026-10-11) - decision 1 to 3, with these refinements:**
  - *Two tables for one version, split along the money line* (`lending V003`). The term set carries money (the
    origination and late fees, a line's minimum-payment floor), and every lending table carrying money is INSERT-only
    (`LendingSchemaHasNoMutableMoneyTest`); the machine needs a row that moves and can be locked (`FOR UPDATE` by the
    activation, `FOR SHARE` by an offer's pin - both need an `UPDATE` privilege on some column). So
    `loan_terms_version` is the money-free machine row (credit `V008`'s shape: a column-scoped `UPDATE` of the decision
    columns under the edge trigger, the partial uniques, the four-eyes `CHECK`, the `V014` clamp) and `loan_terms` the
    term set - one row per version, born in the proposing transaction (a version commits only with it, deferred; a term
    set is accepted only for a `PROPOSED` version), never changed by any role. The history is
    `loan_terms_version_event` (the plan's `_event` spelling; the backlog read `loan_terms_event`).
  - *The decisions take A0 too* - advisory namespace `11` on the product, then the version row (A1), then the `ACTIVE`
    row (A2), `LENDING_LIFECYCLES.md` §3's administration order; credit's decisions take only the row locks.
  - *Decision 2's table, narrowed.* No **amount, limit or term range** is a terms field: the offer is exactly the
    approved decision (A20) and the request bounds are credit's `CreditProduct` - one truth (`P11-TSK-002`'s
    refinement); whether terms amortise across those bounds is the schedule engine's judgement, read through an `app`
    port when `P11-TSK-006` wires `422 lending.TermsNotAmortising` (catalogued now). The **jurisdiction** is the
    agreement template's (`AgreementTemplate`, code-registered per product - A1's neutral EUR reference is the
    documents'), and the **cure rule** (A8) has no alternative in any engine version, so neither is a field until a
    second value and a reviewed engine version exist (the excluded-features rule of decision 2). The **allowed days** are
    the bound that remains; the "product's declared bounds" a value is judged against are the bounds `LoanTerms`
    declares for each field and `LoanProduct`'s offer-validity (14 days) and disbursement-deadline (24 h) **ceilings**
    (no longer called defaults - the pinned version's values are the ones an offer and a loan are held to).
  - *What "implemented" means* (decision 3, `INV-LND-09`): a rate type `FIXED`; instalment rounding `UP`
    (`SCHEDULE_ENGINE_V1`, `STATEMENT_ENGINE_V1`) and interest rounding `HALF_EVEN` (`ACCRUAL_ENGINE_V1`); a day count
    its named accrual engine implements; a fixed-offset servicing zone (`UTC` or `+HH:MM`, A2, A15); allocation
    `OLDEST_DUE_FIRST` over any order naming every component once (the engine applies the order the terms record); the
    overpayment treatment the kind's engines implement (`HOLD_AS_CREDIT` loan, `PAY_DOWN_PRINCIPAL` line, A26); engine
    versions the build holds, each of its own engine. The domain refuses each (`422 lending.TermsInvalid`); `V003`'s
    `CHECK`s hold the conventions again for every writer, so a new convention is a new engine version **and** a
    migration widening them.
  - *Engine versions are named on the version* (schedule or statement by kind, accrual and allocation), copied onto the
    agreement by `P11-TSK-012`.
  - *The doors' read* is behind `LENDING_INVESTIGATE` alone (a route declares one permission; `LENDING_OFFICER` holds
    both); an unknown product reads `404 lending.NotFound`.
  - *The event* is `lending.LoanTermsVersionActivated` (product, version, effective start, predecessor) - lending's first,
    through the platform outbox in the activation's transaction; no rate or fee in it.
- **As built by `P11-TSK-011` (2026-10-11) - the offer, its canonical terms and hash:** the offer pins the version
  `ACTIVE` at the offering instant (`pinActive`, `FOR SHARE`), never the version recorded on the application; `lending
  V005` splits it along the same money line as the terms - `loan_offer` (machine, money-free) and `loan_offer_terms`
  (INSERT-only, immutable for every role). The canonical terms are `CanonicalOfferTerms` format 1, hand-written as
  credit's `CanonicalSnapshot` is: self-contained (the offer, its decision, the approved amount and term, the day, the
  pinned version by id and number with its whole term set, the four engines, the template, and the loan's illustrative
  schedule figures or the line's minimum-payment rule), escape-free, every value a string token - so a `CHECK` holds
  `terms_sha256 = sha256(canonical_terms)` for every writer, and the trigger holds the terms row's rate, engines,
  template and line rule to the pinned `loan_terms` row. The offer document (`OfferDocument`, rendered by the
  template, naming the terms hash) is stored by its SHA-256 only and re-rendered on every read, shown only if its bytes
  are the ones hashed. `-012`'s agreement copies the canonical terms and hash; `-029`'s replay re-verifies them.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
