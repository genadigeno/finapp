# ADR-0014 — Every service runs as N concurrent instances

Status: Proposed

Date: 2026-09-01

## Context

The platform must be correct when several instances of the same service execute the same
business operation at the same time — multiple pods, containers, JVMs and hosts, with
concurrent requests, background workers and event consumers.

This was **implicit** before now, and implicit constraints do not hold. `ADR-0001` chose a
modular monolith and `ADR-0006` enforces module boundaries, but neither says how many copies of
that monolith run, and nothing in the architecture documentation stated that the answer is "as
many as we like". `ADR-0004` and `ADR-0005` each depend on multi-instance correctness without
naming it: a database-enforced idempotency constraint is only worth choosing over a cache
*because* there is more than one instance, and an outbox only exists because publication and
commit cannot be made atomic across processes.

The gap was not theoretical. Reviewing the code against this requirement found a live
double-spend path in `P0-TSK-016`, described below. It had passed every test, because every
test ran in one JVM with one clock.

**Note on scope.** This ADR does not change `ADR-0001`. A modular monolith deployed as N
replicas has exactly the constraints below; "one deployable" and "one instance" are different
statements, and conflating them is how single-instance assumptions get in. If the platform
later extracts services (Phase 16), nothing here changes.

## Decision

**No business logic may depend on there being one instance, one process, or one thread of
control. The architecture must be correct for N concurrent instances, and N is never 1.**

Concretely:

**Authoritative state is shared and durable.** Uniqueness, idempotency, counters, limits,
leases, workflow state and financial position live in the database. Process memory is never the
authority for any of them.

**Coordination uses the one clock every instance shares.** A lease, a timeout or an expiry that
compares one instance's clock against another's is a race against skew, not a boundary. Where a
decision must be made about time *across* instances, the database supplies both sides of the
comparison. This is distinct from `P0-TSK-013`'s rule, which governs *business* time: business
timestamps remain application-supplied from one injected `Clock`.

**Process-local mechanisms are permitted only where they are explicitly non-authoritative** and
correctness does not depend on them. Each such use is documented where it lives, saying what it
would cost if it were wrong. A `ThreadLocal` carrying diagnostic context qualifies. A
`synchronized` block protecting a business invariant does not.

**Concurrency strategy is stated, not assumed.** Every shared mutable resource names the
mechanism protecting it — unique constraint, conditional update, optimistic version, row lock,
lease — and names the specific invariant that mechanism protects.

**Atomicity is claimed only where it exists.** A local transaction is atomic within its own
boundary and nowhere else. Anything spanning a service, a broker or a provider is eventually
consistent and says so, with an outbox, an inbox, a compensating action or a saga making it
safe.

**Scheduled work is cluster-safe.** Every instance runs the scheduler. Jobs are therefore
either idempotent under duplicate execution, or take an explicit distributed lease. The
preferred design for financial work is at-least-once execution with idempotent processing,
never a claim of exactly-once.

**Effects are idempotent; delivery is not exactly-once.** The platform distinguishes
at-least-once delivery, deduplicated processing and idempotent effect, and proves the effect
rather than asserting the delivery.

**Every feature answers, before completion:** would this remain correct with ten instances
processing concurrently, and would the financial invariant hold if two instances performed the
same operation simultaneously? "Unknown" is a "no".

## Alternatives Considered

### Option A — Leave it implicit
Pros: Nothing to write.
Cons: The reason this ADR exists. An implicit constraint is not checked at review, is not
visible to anyone joining, and is invisible in tests that run in one JVM. It produced a
double-spend path that took a deliberate audit to find.

### Option B — Single-writer instance, with the rest read-only
Pros: Removes write concurrency entirely; simple to reason about.
Cons: A single point of failure for every write in a payments platform, a failover procedure on
the critical path of every transfer, and no horizontal write scaling. Also fragile in the worst
way: it is correct only while the "one writer" election is correct, and split-brain during a
network partition silently reintroduces every race it was supposed to remove.

### Option C — Distributed locks for shared business state
Pros: Familiar; superficially simple.
Cons: A distributed lock is a lease, and a lease held by a process that has stalled — GC pause,
kernel scheduling, network partition — is a lock two processes believe they hold. Making that
safe requires fencing tokens and a store with strong guarantees, which is more machinery than
the database already provides. Where the state being protected is *in* the database, a unique
constraint or a conditional update is both simpler and strictly stronger. Not forbidden, but
never the first answer.

### Option D — Database-enforced invariants, idempotent effects, explicit leases (chosen)
Pros: The arbiter is the component that already sees every instance and already provides
atomicity, isolation and durability. Guarantees survive restart, deployment and partition.
Nothing depends on how many instances exist.
Cons: More thought per operation. Contention appears as blocking or conflict and must be
handled deterministically rather than ignored. Some operations need a schema change to be made
safe.

## Consequences

Positive:
- Instance count becomes an operational decision rather than a correctness assumption.
- Deployments are rolling by default: old and new instances may run together.
- The failure modes are the ones the database already has names and semantics for.

Negative:
- Contention is visible. Callers must handle "already claimed", "conflict" and "unknown
  outcome" as ordinary outcomes rather than exceptions to be logged.
- Some operations need an extra column or index purely for coordination.
- Concurrency tests need genuinely separate connections and, where a clock is involved,
  genuinely different clocks. A concurrency test sharing one connection or one clock proves
  much less than it appears to.

Operational impact:
- Scheduled jobs must be reviewed before they are written, not after.
- Clock skew between instances is bounded by NTP but never zero, and no correctness argument
  may depend on it being small.

Security impact:
- None directly. Indirectly, a race is an exploitable primitive: a limit or a hold enforced
  non-atomically is a limit that does not exist.

Financial impact:
- This is the point. Every duplicated effect, double spend and bypassed limit in the failure
  catalogue is a multi-instance failure. `INV-IDEM-01`, `INV-CON-01`, `INV-CON-02` and
  `INV-CON-03` are all statements about concurrent execution, and none of them is testable in a
  single-instance design.

## Invariants / Constraints

- `INV-IDEM-01`, `INV-IDEM-02`, `INV-IDEM-04` — one effect per key, per period, per event,
  across instances.
- `INV-CON-01`, `INV-CON-02`, `INV-CON-03` — no lost updates, one effect from racing
  money-movers, limits not bypassable concurrently.
- `INV-EVT-01`, `INV-EVT-04` — publication commits with the fact; consumers tolerate duplicates
  and reordering.
- `INV-BAL-05` — no financial decision from an unboundedly stale projection.
- `INV-LIFE-03` — an unknown outcome is a modelled state. Under concurrency, "unknown" is
  common rather than exceptional.

## Follow-up

- The single-instance audit and its findings are recorded in
  [`DISTRIBUTED_EXECUTION.md`](../architecture/DISTRIBUTED_EXECUTION.md), which every future
  component is checked against.
- `P0-TSK-041` adds an architecture rule for the mechanically detectable violations
  (`synchronized` on business state, static mutable collections, ambient schedulers).
- The retention sweep, the outbox relay and the inbox consumer are each scheduled or
  event-driven work and must state their cluster-safety strategy in the task that builds them.
- `ADR-0001` is unchanged and remains the deployment decision. If service extraction ever
  happens, this ADR already covers it.
