# ADR-0043 — The transfer and its posting commit in one transaction; there is no internal saga

Status: Proposed
Date: 2026-09-17
Phase: 4
Context: Transfers
Supersedes: nothing. Closes unresolved question 5, open since project initiation.

## Context

Phase 4 delivers the first customer-visible money movement: an internal transfer between two
accounts the platform controls. `CURRENT_STATE.md` §Unresolved Architectural Questions has
carried question 5 at **High** risk since initiation — *"Transfer/ledger transaction boundary
and compensation strategy … determines whether a saga is ever needed internally"* — and
`DELIVERY_PLAN.md` §Phase 4.14 requires this ADR before the first transfer is written.

The question: when a transfer executes, its state transition and its journal entry must end up
agreeing — a `COMPLETED` transfer whose posting never committed is money the customer was told
moved and did not, and a committed posting beside a transfer stuck anywhere else is money that
moved with no lifecycle record saying so. What mechanism guarantees they agree, and what
happens when something fails between them?

Everything needed to answer it already exists and was built deliberately:

- **ADR-0001**: the platform is a modular monolith over one PostgreSQL database. The
  `transfers` and `ledger` modules share a database, so a single local transaction can span a
  transfer row and a journal entry. `MODULE_ARCHITECTURE.md` has called this *"the principal
  benefit of ADR-0001"* since the module was registered.
- **`P3-TSK-006`**: `PostingService.post(unitOfWork, command)` **joins the caller's
  transaction** rather than opening its own — a seam built, in that task's own words, for
  exactly this caller: *"the transfer state transition and the ledger posting commit
  together."* The ledger decides the posting's write set; the boundary is the caller's.
- **`P3-TSK-016`**: `ReversalService` reverses a committed entry with a new referencing entry
  (`INV-REV-01`), joining its caller's transaction the same way.

## Decision

**One local database transaction per transfer command, and no compensation machinery of any
kind for the internal flow.**

1. **Execution is atomic.** The initiation command's single transaction contains: the
   idempotency claim, the transfer row, its lifecycle history, the journal entry and lines
   (through `PostingService`), the audit record, and the outbox row. They commit together or
   none of them exists. There is no observable instant at which the transfer and the ledger
   disagree, and there is no crash window that leaves a half-transfer to repair.

2. **A failed transfer is a domain outcome, committed the same way.** Insufficient funds, a
   non-postable destination and their siblings commit a `FAILED` transfer row with its reason,
   its audit record and its event — and **no posting and no claim on the money**. The refusal
   is a fact the customer can query and the idempotent retry replays; it is never an exception
   leak (`DELIVERY_PLAN.md` §Phase 4.17's named risk).

3. **"Compensation" means the business reversal, and nothing else.** A completed transfer that
   should not have happened is corrected by the **reversal command**: a new journal entry
   referencing the original (`INV-REV-01`, via `ReversalService`) committing atomically with
   the transfer's move to `REVERSED`. That is a new economic operation with its own actor,
   reason and audit record — not an unwind, not a rollback, and available only after the fact.
   No technical compensating path exists, because no partial state exists to compensate.

4. **No saga, and the boundary at which that answer changes is named.** A saga coordinates
   steps that cannot share a transaction. Phase 4 has no such step: both legs are internal,
   both modules share the database, and the provider whose outcome could be unknown does not
   exist here — by the glossary's own definition, *a transfer's state transition and its
   ledger posting commit in one transaction; a payment's outcome cannot*. The place where
   multi-step outcome coordination genuinely arrives is **Phase 5's payment lifecycle**
   (intent/attempt, `UNKNOWN` as a modelled state), and it arrives as a *lifecycle*, not as a
   saga bolted onto transfers. If a future rail ever makes an "internal" transfer's second leg
   external, that rail's flow is a payment, and it uses Phase 5's machinery.

## Why

**The alternative designs each manufacture the failure they exist to handle.**

A **two-transaction design** — commit the transfer `INITIATED`, then execute the posting —
creates a durable intermediate state whose only producers are crashes, and therefore requires
a sweeper to find stranded rows, a lease so ten instances' sweepers do not race, and an
answer for the transfer that is swept while its retry is executing. Three mechanisms and a
new failure mode, bought to avoid a transaction the database already offers. The
`DELIVERY_PLAN.md` §Phase 4.12 scenario *"ledger posting succeeds but transfer state update
fails"* is not handled by this design — it is **made unrepresentable**.

An **outbox-driven design** — commit the transfer, publish, let a consumer post — makes the
posting eventually consistent with the transfer, which puts a customer-visible `COMPLETED`
ahead of the money moving and violates the rule that a balance-affecting decision derives
inside the lock (`INV-BAL-05`, ADR-0041): the availability check and the posting would sit in
different transactions, and the funds checked are not the funds debited.

**The retry loop this avoids is the one ADR-0039 refused.** A compensating path invites
"retry the failed half", and a retry around a money-moving step is exactly where *"the
database committed but the response was lost"* becomes two effects. Idempotency at the
financial boundary (`INV-IDEM-01`, the platform executor) plus one atomic transaction is the
whole recovery story: a client that times out retries the command, and the claim decides
whether that replays an outcome or executes once.

## Consequences

- The execution transaction holds the source account's `FOR UPDATE` (ADR-0039's enumerated
  set gains its third member after holds and account close) for the duration of the posting
  write set. That is the same contention ADR-0041 already accepted and priced for postings;
  the availability derivation and the debit are thereby the same funds.
- The transfer lifecycle needs no states for coordination limbo — no `PROCESSING`, no
  `COMPENSATING` — which is ADR-0044's subject.
- The stuck-transfer detector named in `DELIVERY_PLAN.md` §Phase 4.10 has **no subject**:
  with no durable intermediate state there is nothing to be stuck. Recorded there rather than
  built vacuously; it arrives with the first asynchronous execution path.
- Phase 5 must not inherit this ADR by analogy. Its outcome is a third party's, and modelling
  that as one transaction is impossible — which is why `INV-LIFE-03` exists.

## Alternatives rejected

- **Saga / process manager with compensating transactions** — coordination for a boundary
  that does not exist; every compensating step is a money-moving retry loop.
- **Two-phase: durable `INITIATED`, then execute** — a sweeper, a lease and a stranded state,
  bought to avoid a transaction the database offers (above).
- **Outbox-mediated posting** — eventual consistency between a customer-visible state and the
  money (above).
- **Distributed transaction (XA)** — nothing here is distributed; there is one database.

## Follow-ups

- `P4-TSK-005` implements the execution command to this shape; its injected-failure probe
  (nothing at all remains) is the ADR's demonstration.
- `P4-TSK-009` implements the reversal as the one compensation path there is.
- Phase 5's transition must decide the payment lifecycle **against** this ADR's boundary
  clause, not by extending it.
