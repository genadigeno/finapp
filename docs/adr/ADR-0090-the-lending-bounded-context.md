# ADR-0090 — The lending bounded context: the contract and its servicing facts, none of the money; two products, one account model

Status: Proposed
Date: 2026-10-10
Phase: 11
Context: Lending · Credit · Ledger · Accounts · Payments · Reconciliation · Platform
Supersedes: nothing. Applies ADR-0001 and ADR-0012 (a modular monolith, contexts mapped to modules
deliberately), ADR-0006 (boundaries enforced mechanically), ADR-0002 and ADR-0009 (the journal is
the record, balances are derived), ADR-0043 (a money effect posts in its owner's transaction) and
ADR-0084 §4 (credit decides and moves no money; lending begins where credit stops). Rests on
`PHASE_11_PLAN.md` §1, §2, §3, §4, §5, §17 and `INV-LND-01`, `-05`, `-06`, `-08`.

## Context

1. **Phase 11 moves money.** Phase 10's credit decides and reserves exposure and posts nothing.
   Phase 11 funds loans and draws from the platform's lending capital (L1), credits borrowers'
   wallets, pays out to external bank accounts through payments (L2) and takes repayments back
   into receivables. Something must own the contract and its servicing — and must not become a
   second ledger while doing it.
2. **The tempting design is one row.** A `loan` table with `principal_outstanding`,
   `interest_due` and `rate` columns is the first thing every lending system builds and the
   first thing that drifts from the books: two authorities for one figure, reconciled by hope
   (`INV-LED-04`, owner brief 9.1).
3. **Credit already owns three things lending needs.** The decision (read through
   `CreditDecisions`), the exposure judgement (`PlatformCreditExposure`, ADR-0088 §6) and the
   consumption table (`credit_decision_consumption`, `credit V011`, created empty). The Phase 10
   → 11 transition found that the consumption write had no owner (R13): lending writing credit's
   table would be shared mutable ownership of authoritative state.
4. **Two products share almost everything.** The amortising `PERSONAL_LOAN` and the revolving
   `CREDIT_LINE` (L3) differ in how principal arrives (once versus per draw), how amounts fall due
   (instalments versus statements) and how they end; they share the receivable accounts, daily
   accrual, allocation, delinquency, corrections and payoff.
5. **Production cannot originate.** No production credit decision can be `APPROVED` until a real
   bureau exists (unresolved questions #13 and #14; R11, L11). The module must still be built,
   proven end to end, and shipped closed.

## Decision

1. **One new module, `lending`, one bounded context.** Schema `lending`, Flyway `lending/V001…`,
   `REVOKE ALL FROM PUBLIC`. It owns, as separate aggregates or born-once facts: the loan
   application, the product terms version, the offer, the versioned agreement and its acceptance
   evidence, the loan account (both kinds), the repayment schedule and its instalments, instalment
   billing, credit-line draws and statements, interest accrual, fee assessment, disbursement and
   payout, repayment and allocation, collection attempts, waivers, repayment reversals,
   amendments, conditions (delinquency, default), payoff quotes and executions, and the
   administration of lending capital (the account itself is the ledger's, ADR-0096). Its
   ownership table is `PHASE_11_PLAN.md` §3.1; nothing in it is co-owned.

2. **The contract and its servicing facts — none of the money.**
   - The `loan` row holds identity, party, product kind (`INSTALMENT` or `REVOLVING`), currency,
     lifecycle status and permits — **no amount, rate or balance column**; its every transition is
     appended to `loan_event`, the account's append-only history.
   - The contractual principal (loan), limit (line) and rate live only on the immutable agreement
     version (ADR-0092).
   - Every changing amount is a **ledger balance** of one of the loan's six per-loan accounts
     (ADR-0096), derived from journal lines under the account's lock (`BalanceDerivation`,
     `AvailableBalance.underLock`); the balance projection is display-only (`INV-BAL-05`).
   - Every operational amount — billed, drawn, accrued, assessed, allocated, quoted — is an
     immutable, born-once row whose sum is proven equal to the ledger by `LoanSubledgerProof`
     (`P11-TSK-029`); a difference is an alert, never a self-correction.
   - Held mechanically: `LendingSchemaHasNoMutableMoneyTest` (every `*_minor` column sits on an
     `INSERT`-only table); the application role holds `SELECT, INSERT` on fact tables and a
     conditional `UPDATE` of status and permit columns only on the machine tables
     (`loan_application`, `loan_offer`, `loan`, `loan_disbursement`, `loan_payout`, the proposal
     tables); never `DELETE`.

3. **Two products, one account model.**

   | | `PERSONAL_LOAN` (`INSTALMENT`) | `CREDIT_LINE` (`REVOLVING`) |
   |---|---|---|
   | Contract | principal, term, repayment day | limit, statement day, minimum-payment rule |
   | Principal arrives | once, by disbursement (ADR-0097) | per draw, to the wallet (ADR-0100) |
   | Falls due | instalments billed from actual accrual (ADR-0093) | monthly statements (ADR-0100) |
   | Birth state | `PENDING_DISBURSEMENT` | `ACTIVE` |
   | Ends | `CLOSED` when every receivable is zero | `ACTIVE → CLOSING → CLOSED` |
   | Shared | the six ledger accounts, `ACCRUAL_ENGINE_V1`, `ALLOCATION_ENGINE_V1`, delinquency, corrections, payoff, the proofs ||

   One `loan` table, one set of engines, one lock (L3, the loan row) serialising every servicing
   act on one account of either kind.

4. **Lifecycle apart from conditions.** Delinquency, default, draws suspended, awaiting payout and
   the designed servicing holds are conditions with append-only histories, never states; a
   restructuring is agreement version n+1, never a state. Every machine is held at three ranks —
   the domain's conditional from-set under the row lock, a hand-written every-writer trigger
   (`*_permits_only_machine_edges`) and a status `CHECK` — the Phase 10 precedent. The machines
   with every invalid edge are in `LENDING_LIFECYCLES.md`.

5. **Build edges: `lending → ledger, platform, sharedkernel` only** (the `crossborder` precedent,
   A16). No module depends on `lending`. `LendingModuleIsolationTest` pins both directions with
   planted probes (`P11-TSK-002`).

6. **Everything else through ports `lending` declares and `app` implements**, each running in the
   caller's unit of work:

   | Port | Over | Used by |
   |---|---|---|
   | `LoanCreditDecisions` | `DecisionRequests.submit` / cancel; `CreditDecisions`; `CreditDecisionConsumptions.consume`; `lockExposure` over the profile lock | application, offer, acceptance, every exposure-raising transaction (ADR-0091) |
   | `LoanPartyStanding` | `CreditPartyStanding` / `AccountHolderVerification` | application, acceptance, disbursement, draw |
   | `LoanWallets` | `WalletAccounts`, `AccountOpening` — resolve or open the borrower's `CUSTOMER_WALLET` in the currency | disbursement, draw, repayment |
   | `LoanPayouts` | payments' system-actor withdrawal entry (`dispatch`) and a plain read of its outcome (`outcome`) | the external payout (ADR-0097) |
   | `LoanPayoutDestinations` | the borrower's own verified payment method (the ADR-0056 shape), answering an opaque reference | acceptance (path X) |

   Plus `LendingObserver` (meters) and `TransactionRunner`. No account identifier, score,
   attribute or policy threshold ever crosses into `lending` (`INV-RAIL-03`).

7. **What lending never does.** It never writes another module's table (consumption is credit's
   port, ADR-0091); never reads a score, attribute or threshold; never judges exposure (it supplies
   facts, credit judges); never posts except through `PostingService` and `ReversalService` in its
   own transaction, and never through `AdjustmentService` on a loan account; never implements
   `OutboundCreditComposition` (ADR-0097); never consumes an event for correctness (credit's
   `CreditDecisionRecorded` v2 and `CreditDecisionRequestClosed` are hints, the authoritative read
   decides).

8. **The neighbours' changes are theirs, each by a named task.** Credit publishes
   `CreditDecisionConsumptions` (credit `V021`, `P11-TSK-001`); `app` composes
   `LendingCreditExposure`, `PlatformCreditExposure` version 2 (`P11-TSK-013`); ledger seats
   `OwnerKind.LOAN` and ten purposes (ledger `V026`, `P11-TSK-003`); identity admits the lending
   permissions and roles (identity `V021`, `P11-TSK-002`); reconciliation adds the
   `LENDING_CAPITAL_CONTRIBUTION` expectation kind (reconciliation `V022`, `P11-TSK-004`); payments
   admits a system-actor withdrawal adopting a hold (payments `V032`, `P11-TSK-015`).

9. **Origination is never a person's act, and production ships closed (L11).** An offer comes
   only from credit's real deciding transaction; acceptance is the customer's. A product is
   offered only while an `ACTIVE` terms version exists for it — four-eyes activated, never by
   migration (ADR-0092). Production activates none: the application door answers
   `422 lending.ProductNotOffered`, and the `finapp.lending.terms.active` gauge records the
   expected state. `OPERATIONS_RUNBOOK.md` §7 (`P11-TSK-030`) states the preconditions of a first
   activation: #13 and #14 answered, a real bureau adapter contract-tested, a credit policy reading
   it, legal review of the terms (A1), lending capital recognised. Origination is proven against
   the simulators (`bureau-sim-a`, `bureau-sim-b`, `findata-sim-a`); no test-only approval exists in
   production code.

10. **Deferred, each with an owner** (§17): refinance — a new decision and a two-account atomic
    payoff — owner: the Phase 11 → 12 transition, which names the later lending phase that takes
    it; write-off, charge-off, provisioning and IFRS 9 staging — Phase 14 (`LOAN_WRITE_OFF_EXPENSE`
    is seeded now, posted by nothing, so the account plan does not change later); collections case
    management — Phase 13, consuming `LoanDelinquencyChanged` and `LoanDefaulted`; BNPL — Phase 12;
    consumer-credit-law features — none (L4); variable rates; external inbound repayment (A11);
    the agreement and evidence purge — Phase 15.

## Alternatives Considered

### Lending inside the `credit` module
Pros:
- The consumption is an in-process call; one module for "credit".

Cons:
- Credit is the context that moves no money (ADR-0084); its consistency boundary is the decision
  and its snapshot, lending's is the account and its postings. One module would put decision
  evidence and servicing writes behind one set of grants, and every servicing change would touch
  the module whose immutability the replay depends on.

Refused: two contexts, the boundary a published port (ADR-0091).

### A loan row with mutable balance columns
Pros:
- One-row reads; the simplest servicing code.

Cons:
- A second authority beside the ledger (`INV-LED-04`); every posting must also update the row in
  step, and one missed update is an unexplainable balance (`INV-LND-01`). The proofs would compare
  two mutable things.

Refused: balances are derived from journal lines; operational amounts are immutable rows.

### A module per product
Refused in ADR-0100: the line shares every account, engine and correction with the loan.

### A `lending → credit` build edge
Pros:
- Direct use of credit's types; no adapter.

Cons:
- Couples lending's compilation to credit's internals and invites reading attributes or
  thresholds; the `crossborder → payments` question was settled by `app`-implemented ports.

Refused (A16).

### Lending writes `credit_decision_consumption` itself
Refused (R13, ADR-0091): shared mutable ownership of credit's authoritative state.

### A lending microservice
Refused by ADR-0001: consumption and commitment must commit atomically with the acceptance;
extraction is Phase 16's question, answered by measurement.

## Consequences

Positive:
- Every lending figure is explainable from records: agreement version → engine version →
  born-once row → journal entry → derived balance.
- One account model for two products halves the servicing surface; the line adds draws,
  statements and closure only.
- The boundary is mechanical (isolation test, grants, no mutable money column), not a convention.

Negative:
- Reads compose rows and ledger lines rather than reading a column; every balance-dependent
  decision pays for a derivation under L3.
- Five `app` adapters and three neighbour migrations make the phase's first milestone wide.
- Production originates nothing in Phase 11 — accepted and recorded as a debt row (owner: the
  phase that answers #13/#14, Phase 15 at the latest).

Operational impact: four leaderless schedules (`LEASE_PROTECTED_SCHEDULERS` twenty-two → twenty-six);
the activation runbook gates the first production offer.
Security impact: five new permissions and three roles (plan §11, ADR-0099 point 6); no account identifier,
score or attribute in lending's schema; acceptance evidence `CONFIDENTIAL`.
Financial impact: the module originates and services credit; it holds no balance of its own and
every money effect is one journal entry in its own transaction.

## Invariants / Constraints

`INV-LND-01` (a loan's figures are ledger balances), `INV-LND-05` (terms pinned and immutable),
`INV-LND-06` (one decision funds at most one account), `INV-LND-08` (closed is closed, and
settled), `INV-LED-04` (no duplicated ledger authority), `INV-BAL-05` (no decision from the
projection), `INV-CRD-02` (decisions never updated), `INV-LIFE-01`…`04`, `INV-RAIL-03`,
ADR-0001, ADR-0006, ADR-0084.

## Follow-up

- Built by `P11-TSK-002` (the module, its floors, the isolation test and identity `V021`), with the
  ports landing in the tasks that first use them: `LoanCreditDecisions` (`-010`, `-012`, `-013`),
  `LoanPartyStanding` (`-010`), `LoanWallets` (`-014`), `LoanPayouts` and
  `LoanPayoutDestinations` (`-012`, `-015`). `BOUNDED_CONTEXTS.md` and `MODULE_ARCHITECTURE.md`
  carry the planned entries; each building task corrects them as built.
- The Phase 11 → 12 transition names refinance's owning phase.
- **Acceptance.** `Proposed` at the Phase 10 → 11 transition (2026-10-10); the Phase 11 review
  (`P11-DOC-001`) reads it against the code, corrects it in place with dated notes, and accepts it.
