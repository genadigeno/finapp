# ADR-0091 — Taking up a credit decision: a credit-owned consumption under the party's profile lock, and exposure that counts committed principal, outstanding principal and open credit lines' limits

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Credit · Lending · Ledger · App
Supersedes: nothing. Designs the consumption writer ADR-0088 §4 left to Phase 11 and the
`PlatformCreditExposure` composition ADR-0088 §6 anticipated (version 2 replacing
`NoLoansUntilPhase11`, version 1); relates to ADR-0087 §5 (the profile-first deciding transaction,
which the Phase 10 → 11 transition's R12 repair made re-read outstanding credit beside the
reservation). Neither accepted ADR's decision text changes: each gains a dated follow-up note.
Applies ADR-0039 (`READ COMMITTED` plus row locks) and ADR-0090 §6 (lending reaches credit only
through `app`). Rests on `PHASE_11_PLAN.md` §2.2, §7, §12.10 and `INV-LND-06`, `INV-CRD-02`,
`INV-CRD-09`.

## Context

1. **An approval reserves; a loan commits.** ADR-0088 §4: a decision `APPROVED` reserves its
   amount while `valid_until > statement_timestamp()` and no `credit_decision_consumption` row
   exists. When the customer accepts an offer, the reservation must end *exactly as* the
   commitment begins — a single instant seeing neither under-counts the party's exposure, and a
   concurrent decision could approve into the gap (`INV-CRD-09`).
2. **The consumption had no owner** (R13). The table exists empty (`credit V011`,
   `UNIQUE (decision_id)`); nothing writes it, and lending writing it directly would be two
   contexts mutating credit's authoritative state.
3. **The deciding transaction reads both terms under one lock** (R12, repaired in Phase 10):
   reserved exposure and the platform's outstanding credit are re-read together under the party's
   `credit_profile` row `FOR UPDATE`, and a change in either freezes a successor snapshot. That is
   only sound if every writer that moves exposure between reserved, committed and outstanding
   holds the same lock.
4. **Outstanding credit is no longer zero.** Version 1 of `PlatformCreditExposure` answers zero.
   Phase 11 must supply committed and outstanding principal — from authoritative rows, in one
   consistent read, never from the balance projection (`INV-BAL-05`).
5. **A credit line's undrawn limit is a promise.** A party may draw to the limit at any moment
   without a new decision (ADR-0100). An exposure figure that counted only the drawn balance would
   let a second approval assume the undrawn limit away.

## Decision

1. **Credit publishes the consumption port; its implementation is credit's** (`P11-TSK-001`):

   ```java
   public interface CreditDecisionConsumptions<T> {
       Consumption consume(T unitOfWork, CreditDecisionId decision, ConsumerRef consumer);
       sealed interface Consumption {
           record Consumed(CreditDecisionConsumptionId id, CreditDecision decision, boolean replayed) implements Consumption {}
           record NotApproved(CreditDecisionId decision) implements Consumption {}
           record Lapsed(CreditDecisionId decision, Instant validUntil) implements Consumption {}
           record AlreadyConsumed(CreditDecisionId decision, ConsumerRef by) implements Consumption {}
           record NotFound(CreditDecisionId decision) implements Consumption {}
       }
   }
   record ConsumerRef(ConsumerKind kind, UUID ref) {}   // ConsumerKind { LOAN_AGREEMENT }; BNPL reserved for Phase 12
   ```

2. **In order, in the caller's transaction.** (a) Read the decision's party (plain). (b) Take the
   party's `credit_profile` row `FOR UPDATE` — Phase 10's lock element (1), so a consumption and a
   decision for one party serialise. (c) Re-read the decision. (d) Not `APPROVED` → `NotApproved`.
   (e) `valid_until <= statement_timestamp()` → `Lapsed` — the exact complement of
   `JdbcReservedExposure`'s `valid_until > statement_timestamp()`, so at the boundary exactly one
   of *reserves* and *consumable* holds. (f) Insert: the same `ConsumerRef` already present →
   `Consumed(replayed = true)`; another consumer present → `AlreadyConsumed`. (g) Audit
   `credit.DecisionConsumed` (decision, consumer — never an amount); count
   `finapp.credit.consumption{outcome}`.

3. **The database guard: the port is the only path** (credit `V021`). The migration asserts the
   table holds no rows, then adds `consumer_kind` and `consumer_ref` (`NOT NULL`) and
   `UNIQUE (consumer_kind, consumer_ref)` beside the existing `UNIQUE (decision_id)`. A
   `SECURITY DEFINER` function `credit.consume_decision(...)`, owned by credit's owner role with a
   pinned `search_path`, takes the profile lock and re-judges outcome and validity itself, then
   inserts; the application role is granted `EXECUTE` on it and **its `INSERT` on the table is
   revoked**; a trigger refuses any insert not made inside the function. Java and SQL judge the
   same rule twice — the domain for the typed answer, the function so no future caller (or a
   compromised one) can consume an unapproved or lapsed decision. `JdbcReservedExposure` is
   unchanged: it already excludes consumed decisions.

4. **Acceptance is atomic with consumption** (T-acc, `P11-TSK-012`). One transaction: the
   idempotency claim `lending.offer-acceptance:CUSTOMER:<id>`; **(L0)** the profile `FOR UPDATE`
   through `LoanCreditDecisions.lockExposure`; (L2) the offer `FOR UPDATE`, conditional `OFFERED`
   and `expires_at > statement_timestamp()`; standing re-read; `consume` (the profile lock is
   re-entrant within the transaction); for a loan, (L6) the capital row and headroom (ADR-0096);
   the loan, agreement v1, acceptance evidence, disbursement `PENDING` (loan) and payout
   instruction (path X) born; offer `ACCEPTED`; events and audit. Any consumption answer other
   than `Consumed` rolls the whole transaction back and answers `409 lending.OfferNotAcceptable`
   (credit's reason preserved in the error detail). The offer's `expires_at` is
   `LEAST(decision.valid_until, offered_at + offer_validity)`, so an acceptable offer is never
   resting on a lapsed decision; the function re-judges it anyway.

5. **`PlatformCreditExposure` version 2** (`app`'s `LendingCreditExposure`, `P11-TSK-013`). **One
   SQL statement** over lending's definer view (`P11-TSK-013`'s migration) and the ledger's journal
   lines — never `account_balance` — answering for (party, currency):

   ```
   loans:  committed   = Σ agreement principal of the party's loans PENDING_DISBURSEMENT
           outstanding = Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) of its loans not CANCELLED
   lines:  committed   = Σ GREATEST(agreement limit, DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE)) of its lines ACTIVE
           outstanding = Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) of its lines CLOSING
   outstandingFor      = Σ committed + Σ outstanding
   ```

   **Principal only** (A3): the policy's maximum exposure is a principal concept and the bureau's
   total balance its comparable external figure; accrued or billed interest and fees are not credit
   the platform extended by a decision. In the product's currency only (ADR-0088 §3: nothing is
   converted). The snapshot records the answer as `PLATFORM_OUTSTANDING_CREDIT` with port version
   2; decisions frozen under version 1 replay identically (their snapshots carry zero with
   version 1, `INV-CRD-01`).

6. **An open line counts its limit; a closing line its drawn principal.** While a line is
   `ACTIVE` its holder can draw to the limit without any decision, so the platform has promised
   the whole limit; counting the drawn balance would let a second approval rest on headroom the
   first product can consume at will — `INV-CRD-09` broken by construction. Once `CLOSING`, no
   draw is possible, and the promise shrinks to what is drawn. Capital is a different question and
   counts only drawn principal (A28, ADR-0096): exposure is what the platform promised the
   customer; capital is the money actually deployed.

7. **No gap: which transactions take L0, and why.**

   | Transaction | Exposure effect | L0 first |
   |---|---|---|
   | Acceptance (T-acc) | reserved → committed (loan) or limit committed (line), atomically | yes |
   | Disbursement (T-dis) | committed → outstanding, same amount | yes |
   | Repayment reversal (T-rev) | outstanding rises | yes |
   | Cancellation of a pending loan | committed released | yes (the release is in T-dis's failure branch) |
   | Draw | none: the limit is already counted | no |
   | Repayment, payoff, closure request | only lowers exposure | no |

   Every exposure-raising or exposure-moving lending transaction takes L0 **first**, before any
   lending row. A transaction that only lowers exposure may commit concurrently with a decision:
   the decision then reads the higher figure (conservative) or the lower, never a torn one, because
   the exposure read is one statement.

8. **No lock cycle.** Credit's deciding transaction takes the profile, then credit's rows, then
   reads lending's facts in one plain statement — it never waits on a lending lock. Lending takes
   the profile only first. Credit never locks a lending row; consumption is the only place a
   lending transaction holds a credit lock (`PHASE_11_PLAN.md` §7.3 (a), (e)).

9. **Proven continuously.** `ExposureCensus` (`P11-TSK-029`, gauged `finapp.credit.exposure.census`):
   per party, credit's reserved plus lending's committed plus outstanding never exceeds the limit
   its policy declared at the deciding instant — order-free, the Phase 10 storm's census shape.

## Alternatives Considered

### Lending inserts the consumption row itself (granted `INSERT`)
Pros:
- No new credit code; one statement in the acceptance.

Cons:
- Two contexts writing credit's authoritative table; nothing would stop a lending defect from
  consuming an unapproved or lapsed decision, or doing so without the profile lock (R13).

Refused: credit's port, behind a definer function and a revoked grant.

### A `consumed_at` column on the decision
Pros:
- One row to read.

Cons:
- The decision is never updated by any role (`INV-CRD-02`); a mutable column would break the
  replay's premise and the decision's immutability trigger.

Refused (as ADR-0088 §4 already refused it).

### Consumption by event (credit consumes `LoanAccepted`)
Pros:
- No synchronous coupling.

Cons:
- Between the acceptance's commit and credit's consumption the approval is counted twice
  (reserved and committed) — conservative, but a wrong figure in every snapshot frozen meanwhile;
  a lost event leaves the reservation alive until lapse; and nothing stops a second acceptance
  (another product's offer on the same decision) from committing before the first event lands.
- Correctness would rest on Kafka, which is integration infrastructure, never truth.

Refused: atomic in the acceptance.

### Exposure from the balance projection
Pros:
- One indexed read per account.

Cons:
- The projection never backs a decision (`INV-BAL-05`, ADR-0041); it lags nothing today but is
  not the authority.

Refused: journal lines, one statement.

### Count a line's drawn balance only
Pros:
- More credit available to customers with unused limits.

Cons:
- `INV-CRD-09` broken by construction (point 6).

Refused.

### Count interest and fees in exposure
Pros:
- A fuller picture of what the customer owes.

Cons:
- Not comparable to the policy's limit or the bureau's balance (both principal); exposure would
  grow daily without any credit decision.

Refused (A3).

## Consequences

Positive:
- One decision funds at most one account, and no instant under-counts a party's exposure.
- Credit keeps sole ownership of its tables; the definer function makes the rule a database fact.
- Phase 12's BNPL consumes decisions through the same port with a new `ConsumerKind`.

Negative:
- Every acceptance, disbursement and repayment reversal for a party serialises with that party's
  decisions on one row — a per-party cost.
- An open line's unused limit reduces what else the party may be approved for.

Operational impact: `finapp.credit.consumption{outcome}` and the census gauge; no amount in a tag.
Security impact: the function runs as credit's owner — its body is reviewed with the migration, its
`search_path` pinned; the application role cannot insert consumptions directly.
Financial impact: none posted by the consumption itself; it bounds what lending may fund.

## Invariants / Constraints

`INV-LND-06` (one decision funds at most one loan or line, and exposure has no gap),
`INV-CRD-02`, `INV-CRD-09`, `INV-CRD-01` (version 1 decisions replay), `INV-BAL-05`, ADR-0039,
ADR-0087 §5, ADR-0088 §4, §6.

## Follow-up

- Built by `P11-TSK-001` (the port, credit `V021`, the counted races: ten consumers, consume
  against decide for one party, the `valid_until` boundary under ±5 s skew; no lending code) and
  `P11-TSK-013` (version 2, lending's definer view, the lock-wait test asserting the waiting
  statement for L0, the census). `BOUNDED_CONTEXTS.md` is corrected so that lending never writes
  credit's tables.
- ADR-0087 and ADR-0088 carry a dated follow-up note pointing here; their decisions stand.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); accepted by the Phase 11
  review (`P11-DOC-001`) after reading it against the code.
