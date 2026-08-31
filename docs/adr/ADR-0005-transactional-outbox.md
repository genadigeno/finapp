# ADR-0005 — Transactional outbox and inbox for reliable event exchange

Status: Proposed

Date: 2026-08-31

## Context

Domain state lives in PostgreSQL; integration events are published to Kafka. These are two
systems, and there is no distributed transaction between them.

Publishing directly from application code creates one of two defects, depending on ordering:
publish-then-commit can announce a fact that was rolled back; commit-then-publish can lose a
fact that was committed if the process dies in between. `CLAUDE.md` §Failure Engineering
names both: "the database commits but the response is lost" and "the service crashes".

On the consuming side, Kafka's at-least-once delivery means duplicates are normal, not
exceptional.

## Decision

**Outbox for publication, inbox for consumption.**

**Outbox:**
- Domain code writes the event envelope to an outbox table in the **same transaction** as
  the state change. It never publishes to the broker directly — enforced by an architecture
  rule.
- A relay process polls unpublished rows, publishes them, and marks them published.
- Ordering is preserved per aggregate; the aggregate id is the partition key.
- Retry with backoff and attempt counting; a poison-message path after a defined threshold.
- Delivery is **at least once**. Exactly-once is not claimed, because it cannot be honestly
  provided end to end.

**Inbox:**
- Consumers record a dedupe key in a processed-message table **in the same transaction** as
  the side effect.
- A redelivered message whose dedupe key exists is skipped.
- Consumers are written to be safe under duplicate, late, out-of-order and replayed
  delivery.

**Boundary:** Kafka is transport, never the accounting source of truth. No balance, position
or financial decision is derived solely from the event stream.

## Alternatives Considered

### Option A — Direct publish from application code
Pros: Simplest; no extra table or process.
Cons: Publishes rolled-back facts, or loses committed ones. There is no ordering of the two
operations that is correct. Rejected.

### Option B — Change-data-capture (Debezium on the WAL)
Pros: No application-side outbox writes; captures every change; low latency.
Cons: Events become a projection of the database schema rather than a designed contract, so
schema changes leak into published events. Significant operational complexity (connector,
replication slots, schema registry) for Phase 0. Harder to attach the full envelope with
correlation and causation.

### Option C — Two-phase commit across PostgreSQL and Kafka
Pros: Theoretically atomic.
Cons: XA with Kafka is impractical, poorly supported and slow. Blocking coordinator failure
modes are worse than the problem being solved.

### Option D — Transactional outbox and inbox (chosen)
Pros: Atomicity by construction — the fact and its publication record share one transaction.
Events are designed contracts, not schema leakage. Straightforward in a monolith on one
database (a direct benefit of ADR-0001). Envelope with correlation and causation is natural.
Cons: A relay process to operate and monitor. Publication latency equals polling interval.
Outbox table growth needs a retention job. Duplicate publication remains possible, which is
why the inbox exists.

## Consequences

Positive:
- No committed fact is ever lost, and no rolled-back fact is ever published.
- Correlation and causation propagate into events automatically (`INV-EVT-03`).
- Crash recovery is testable, and is tested (`P0-TST-005`).

Negative:
- Publication latency is bounded by the poll interval, not immediate.
- Relay lag becomes an operational metric requiring alerting.
- Every consumer must implement dedupe; this is a standing review item.

Operational impact: Outbox depth and age, relay lag and consumer lag are first-class
monitored metrics. Poison messages require a documented handling procedure.

Security impact: Event payloads are subject to the same redaction rules as logs — no
credentials, tokens, PANs or unnecessary PII (`INV-AUD-02`).

Financial impact: Guarantees that a posting and its `JournalEntryPosted` event cannot
diverge — a divergence that would silently corrupt every downstream projection.

## Invariants / Constraints

`INV-EVT-01`, `INV-EVT-02`, `INV-EVT-03`, `INV-EVT-04`, `INV-IDEM-04`.

## Follow-up

- Phase 0: crash-between-commit-and-publish test; duplicate-delivery test.
- Phase 15: schema registry and compatibility enforcement; dead-letter handling and replay.
- Phase 16: relay throughput under load; consider CDC only if polling becomes a measured
  bottleneck.
