# ADR-0009 — Balances are derived projections anchored to the ledger

Status: Proposed

Date: 2026-08-31

## Context

ADR-0002 makes immutable journal postings the authoritative financial record. That settles
correctness but not performance: computing a balance by summing every posting for an account
is O(history), and history only grows.

`FINANCIAL_INVARIANTS.md` requires the system to document whether a balance is authoritative
state or a derived projection. `LEDGER_MODEL.md` permits snapshots for performance provided
their correctness is anchored to the ledger.

The risk in any projection design is drift: the projection and the postings disagree, and
nobody notices until reconciliation — or until a customer does.

## Decision

**Balances are derived projections. Postings remain the sole authority.**

- The balance projection is maintained by the ledger's projector, updated in the **same
  transaction** as the posting. It is not eventually consistent and not maintained by an
  event consumer.
- No module other than the ledger's projector writes the projection.
- The projection is **rebuildable at any time** by replaying postings from zero; the rebuild
  procedure is implemented, documented and tested.
- A **continuous verification job** recomputes balances from postings and compares them to
  the projection. Any discrepancy raises a critical alert. Trial balance is asserted to be
  zero per currency.
- A discrepancy is never fixed by adjusting the projection to match the postings and moving
  on: the projection is rebuilt, and the cause is investigated as a defect.
- Financial decisions (sufficient funds, credit exposure, settlement eligibility) are never
  made from a projection with an unbounded staleness bound (`INV-BAL-05`). Because the
  projection is transactional, its staleness bound is zero — this constraint is what
  preserves that property if a future read model is introduced.
- Available balance = ledger balance − active holds.

## Alternatives Considered

### Option A — Compute balance from postings on every read
Pros: Impossible to drift; single source of truth by construction.
Cons: O(history) per read. Degrades continuously and unboundedly. Every balance check —
including in the hot path of every transfer — pays the full cost.

### Option B — Balance as authoritative mutable state, postings as a log
Pros: Fastest reads; simplest.
Cons: Reintroduces exactly the defect ADR-0002 exists to prevent. Balance and history can
disagree with no authority to resolve it. Explicitly forbidden by `CLAUDE.md` rule 5.

### Option C — Projection updated asynchronously by an event consumer
Pros: Decouples posting latency from projection maintenance; scales reads independently.
Cons: The projection is eventually consistent, so a sufficient-funds check against it can
authorise a payment from funds already spent. Solving that requires reading postings anyway
for financial decisions, which removes most of the benefit while adding a drift window.

### Option D — Transactional projection with continuous verification (chosen)
Pros: O(1) reads. Zero staleness, so financial decisions can safely use it. Drift is
detected continuously rather than at reconciliation. Rebuildable, so a drift incident is
recoverable. Postings remain unambiguously authoritative.
Cons: Posting transaction carries the projection update, adding contention on hot accounts.
A verification job to build and operate. Projection update is a potential lock hotspot,
which makes the Phase 3 concurrency ADR necessary.

### Option E — Periodic snapshots plus delta replay
Pros: Bounded replay cost; less write contention than a full projection.
Cons: More complex; reads still require replaying deltas since the snapshot. Worth
revisiting at Phase 16 volumes, not now.

## Consequences

Positive:
- Balance reads are constant-time and safe for financial decisions.
- Any balance is reproducible from zero — satisfying "a balance must be explainable".
- Projection drift is a monitored, alerting condition rather than a latent one.

Negative:
- Posting contention on high-velocity accounts; addressed by the Phase 3 concurrency ADR.
- The verification job costs resources and must itself be monitored.
- A projection rebuild on a large account is expensive; the procedure must be operationally
  practical, not just theoretically available.

Operational impact: Projection-vs-postings comparison and per-currency trial balance are
core operational controls with critical alerting. A rebuild runbook is required.

Security impact: Only the ledger's projector holds write access to the projection; enforced
by module boundary and database privilege.

Financial impact: Preserves ADR-0002's guarantee while making it usable at scale. The
continuous verification job is the platform's primary early-warning signal for financial
corruption.

## Invariants / Constraints

`INV-BAL-01`, `INV-BAL-02`, `INV-BAL-03`, `INV-BAL-04`, `INV-BAL-05`, `INV-ACC-01`,
`INV-CON-01`.

## Follow-up

- Phase 3: concurrency and isolation ADR — projection update is the primary contention point.
- Phase 3: rebuild procedure and runbook.
- Phase 9: per-currency projections and FX position accounts.
- Phase 16: revisit snapshot-plus-delta if contention or rebuild cost becomes material.
