# ADR-0001 — Modular monolith as the initial deployment architecture

Status: Proposed

Date: 2026-08-31

## Context

The platform spans 26 bounded contexts (`BOUNDED_CONTEXTS.md`) covering banking, payments,
lending, risk and accounting. The obvious industry reflex is to map each context to a
microservice.

The central technical constraint is different from a typical CRUD platform: a money-moving
operation must change domain state **and** write balanced journal entries **and** record an
event for publication, atomically. If those live in different services with different
databases, atomicity is lost and must be replaced by sagas, compensations and eventual
consistency — for the single property the system least tolerates being eventually consistent.

`SYSTEM_ARCHITECTURE.md` already states a preference for starting with a modular monolith.
This ADR ratifies it and defines the conditions for changing it.

## Decision

Build the platform as a **single deployable modular monolith** over one PostgreSQL database,
with strictly enforced module boundaries (ADR-0006) and schema-per-module data ownership.

Services are extracted only when a specific module demonstrates a measured need, recorded in
a superseding ADR, against the criteria in `MODULE_ARCHITECTURE.md` §5.

## Alternatives Considered

### Option A — Microservices from the start
Pros: Independent scaling and deployment; strong physical enforcement of boundaries;
matches common industry practice and hiring expectations.
Cons: Every money-moving operation becomes a distributed transaction. Transfer-plus-posting
requires a saga with compensations before the domain is even understood. Debugging financial
correctness across services is dramatically harder. Distribution cost is paid immediately
while the boundaries are still least well understood — and early boundaries are usually
wrong. Contradicts `CLAUDE.md`: "Do not create a microservice merely because a noun exists."

### Option B — Single-module monolith
Pros: Simplest; fastest initial progress.
Cons: No enforced ownership. Contexts bleed into each other, financial logic spreads, and
extraction later becomes impractical. Fails `CLAUDE.md` §Architecture, which requires domain
ownership to be established.

### Option C — Modular monolith (chosen)
Pros: Atomic state-plus-posting-plus-outbox in one transaction. Boundaries are real and
build-enforced. Refactoring boundaries costs a package move, not a migration and a protocol.
Extraction remains available once boundaries are proven by use.
Cons: One deployment unit and one scaling unit. Boundary enforcement depends on discipline
plus tooling rather than physics. A single database is a shared failure domain.

## Consequences

Positive:
- Transfer state and its journal entry commit in one transaction — no saga required for the
  most correctness-critical operation in the platform.
- The outbox pattern is straightforward: same database, same transaction.
- Boundary mistakes are cheap to correct while we are still learning the domain.

Negative:
- All modules scale together and deploy together.
- A single database is a shared failure domain; addressed in Phase 16.
- Boundary discipline must be mechanically enforced or it will erode (ADR-0006).

Operational impact: One deployment, one set of runbooks, simpler observability initially.
Vertical scaling first; Phase 16 revisits with measurements.

Security impact: One process means one blast radius. Card-data handling is kept out of scope
by tokenisation (ADR-0008) rather than by service isolation. If PCI scope ever becomes
unavoidable, that is a legitimate extraction trigger.

Financial impact: **This is the decisive factor.** Atomicity between domain state and
accounting posting is preserved by construction rather than reconstructed through
compensation logic.

## Invariants / Constraints

- `INV-EVT-01` — state change and outbox publication commit atomically.
- `INV-LED-04` — the ledger remains the sole writer of postings regardless of topology.
- Module boundaries must be enforced such that extraction stays feasible.
- No transaction may span an external provider call, monolith or not.

## Follow-up

Revisit at Phase 16 with load and capacity measurements. Any extraction requires a
superseding ADR citing the evidence and the criterion from `MODULE_ARCHITECTURE.md` §5.
