# ADR-0035 — The KYC context owns the verification decision; Party projects it

Status: Proposed

Date: 2026-09-09

## Context

Phase 2 introduces verification: whether a party may be onboarded to the standard a regulator
requires. Phase 1 deliberately left `party.customer.status` at `PENDING` with nothing moving it —
`MeController` records that a permanently-`PENDING` field was withheld from the API for exactly
this reason. Something must now move it, and the question is **who owns the answer**.

The pressure, as with ADR-0029's `users` table, comes from the simplest first story: put a
`verified` boolean on Customer and have the KYC flow set it. The cost arrives later and is
structural — the decision's evidence, its reproducibility and its lifecycle live in one context
while the authoritative answer lives in another, and by Phase 3 financial capabilities are gating
on a field whose provenance nobody can state.

## Decision

**The KYC/KYB decision is owned by the `kyc` context, as an immutable record on the case.**
`party` holds only a **projection** of the current verification outcome — updated by reacting to
the decision, never computed there, never edited there, and never authoritative for a dispute.
When a later phase asks *"may this party transact?"*, the authoritative answer is the `kyc`
context's current decision; the projection exists so the hot path need not join into `kyc`.

Concretely:

- `kyc.kyc_case` carries the lifecycle; the **decision** is a separate immutable record
  (`INV-KYC-02`) referencing the case, the evidence, the policy basis and the deciding actor.
- `party.customer.status` transitions (`PENDING → ACTIVE`/`REJECTED`) are performed by an `app`
  orchestration reacting to the recorded decision, in the same transaction that records it —
  the registration precedent (`P1-TSK-006`): `app` orchestrates, each module writes its own rows.
- The projection may lag conceptually but not operationally in Phase 2, because the transaction
  is shared; if a later phase moves the projection behind events, `INV-BAL-05`'s rule applies —
  a decision is never made from a projection whose staleness is unbounded.

## Alternatives Considered

### A status field on Customer, written by the KYC flow
Pros: simplest; no projection machinery.
Cons: two writers for one aggregate's status across a module boundary — exactly the shared
mutable ownership `CLAUDE.md` forbids; the decision separates from its evidence; a reviewer
correcting a case cannot re-derive what the customer status should be.

### Customer status queried live from `kyc` on every use
Pros: one authority, no projection.
Cons: every future financial operation joins into `kyc`'s schema or calls its API on the hot
path; couples module availability; and Phase 3+ will gate *every* money movement on it.

### Chosen: decision in `kyc`, projection on Customer
Pros: one authority with evidence attached; hot path reads its own module; disputes replay the
case, not a boolean.
Cons: two representations that can drift — accepted because the shared transaction closes the
window in Phase 2 and a reconciling test asserts they agree.

## Consequences

Positive: the decision is attributable, immutable and reproducible from retained evidence;
Customer keeps single ownership of its own status transitions.
Negative: a reconciliation obligation between decision and projection, carried by test.
Security impact: reviewer actions concentrate in one privileged surface in `kyc`.
Financial impact: none in Phase 2; Phase 3 inherits a queryable onboarding gate.

## Invariants / Constraints

`INV-KYC-02`, `INV-KYC-05`, `INV-LIFE-02`, `INV-LIFE-04`, `INV-HIST-02`.

## Follow-up

When the projection moves behind events (first phase that needs it), the staleness bound must be
stated and monitored (`INV-BAL-05`'s shape).
