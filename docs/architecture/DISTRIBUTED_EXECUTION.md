# Distributed Execution

How this platform stays correct when N instances of the same service run concurrently
(ADR-0014). This document is the register: every component states its concurrency strategy
here, and the audit that produced it is recorded so it can be repeated.

The working assumption, everywhere:

```
Instance A ─┐
Instance B ─┼──> same service, same operation, same instant
Instance C ─┤
Instance D ─┘
```

**"It works because there is one instance" is a defect, not a simplification.**

---

## 1. What this does and does not say about the architecture

ADR-0001 chose a **modular monolith**, and that is unchanged. One deployable is not one
instance: the monolith runs as N replicas behind a load balancer, and every constraint here
applies to it exactly as it would to separate services. Conflating "one deployable" with "one
process" is itself the class of assumption this document exists to remove.

---

## 2. The eight questions

Every operation touching shared state answers these before it is written:

1. Can two instances execute it at the same time?
2. Can two requests modify the same aggregate simultaneously?
3. Can two workers consume related events concurrently?
4. Can the command be retried?
5. Can the event be delivered more than once?
6. Can a provider send the same callback more than once?
7. Can operations arrive out of order?
8. Can one instance crash while another continues?

---

## 3. Component register

Every component with state, and what makes it safe for N instances.

| Component | State | Multi-instance strategy | Authoritative? |
|---|---|---|---|
| `Money`, `CurrencyCode`, `RoundingPolicy` | none — immutable values | No shared state to protect | n/a |
| `EntityId` | none — immutable value | n/a | n/a |
| `IdGenerator` | in-memory `(millis, counter)` per instance | **Non-authoritative.** Uniqueness across instances rests on 62 `SecureRandom` bits, not the counter. The counter provides ordering *within* one instance only | No |
| `CorrelationContext` | `ThreadLocal` per request | **Non-authoritative.** Diagnostic context; each instance carries its own. Losing it costs traceability, never correctness | No |
| `MoneyColumns` | none — a column convention | n/a | n/a |
| `platform.idempotency_record` | durable | **Unique constraint** on (scope, key). The database is the arbiter (ADR-0004) | **Yes** |
| `IdempotentExecutor` | none — all state in the row | Claim by insert; conditional updates; database-owned lease | Delegates to the row |
| `JdbcIdempotencyRecordStore` | none | Conditional `UPDATE ... WHERE`, never read-then-write | Delegates |
| `platform.outbox_event` | durable | The pending set is a predicate (`published_at IS NULL`), not a cursor. Eligibility and abandonment are decided by the **server's** clock | **Yes**, for publication state |
| `OutboxWriter` / `JdbcOutboxWriter` | none | Writes on the caller's connection and opens nothing of its own (`INV-EVT-01`) | Delegates to the row |
| `OutboxRelay` | none — all state in the row | **Transaction-scoped advisory lock per aggregate.** Every instance polls; one drains a given aggregate at a time; conditional `UPDATE ... WHERE published_at IS NULL` on every write | Delegates to the row |

### `IdGenerator` — why a per-instance counter is acceptable

The 12-bit field is a counter rather than randomness, and that counter is process-local. It is
**not** what makes identifiers unique. Two instances on the same millisecond will happily pick
the same counter value; `IdGeneratorTest.restartDoesNotReissueIdentifiers` asserts exactly that,
and asserts the identifiers are still disjoint — because the 62 random bits are what carry
uniqueness.

What the counter buys is ordering *within* one instance, which is what index locality needs.
Across instances identifiers interleave within a millisecond, and that is fine: the timestamp
dominates the sort order, so the index still appends.

**If the generator were ever seeded deterministically, every instance would emit the same
identifiers.** That is why the production configuration uses `SecureRandom`, and why the test
asserts the collision explicitly rather than trusting the property.

### `CorrelationContext` — why a `ThreadLocal` is acceptable

Correlation identifies a flow for diagnosis. Each instance handles its own requests, and a
correlation identifier is carried *with* the request — in a header inbound, in a row or an event
outbound — not held in shared memory. The `ThreadLocal` is a per-request convenience whose worst
failure is an untraceable log line.

It is deliberately **not** used to coordinate anything, and `CorrelationContext.propagate`
exists because even *within* one instance the context does not survive a thread handoff by
itself.

### `OutboxRelay` — why an advisory lock per aggregate

ADR-0005 requires ordering per aggregate. The usual outbox claiming pattern,
`SELECT ... FOR UPDATE SKIP LOCKED`, locks **rows**: instance A takes event 1 while instance B
takes event 2 of the same aggregate, and whichever finishes its publish first publishes first.
Ordering then holds only while the relay happens to be running as one instance — the assumption
this document exists to remove — and the defect is invisible in any test that starts one relay.

Locking the **aggregate** instead makes the guarantee structural. `pg_try_advisory_xact_lock`
rather than the blocking form, so an instance refused a lock moves to other work instead of
queueing behind another instance's broker latency; transaction-scoped rather than session-scoped,
because a session lock outlives a crash of the code meant to release it and a relay that leaks
locks stops publishing an aggregate forever.

Two aggregates whose lock keys collide serialise against each other, which costs throughput and
nothing else: each is still published in order, by one instance at a time.

**Advisory-lock namespaces.** PostgreSQL advisory locks share one cluster-wide key space, so
every component taking one uses the two-argument form and reserves a namespace here. A component
reusing another's namespace would collide silently, and only under load.

| Namespace | Owner | Key |
|---|---|---|
| `1` | `OutboxRelay` | `aggregateId.hashCode()` |

**Scheduling.** Every instance runs the poller. There is no leader and no designated primary,
because every such arrangement is a single point of failure wearing a distributed costume.
`pollOnce()` is safe to call concurrently from any number of threads and instances, which is
what §5's rule for scheduled jobs asks a job to state.

**Delivery is at least once, and is never described otherwise.** The relay publishes, then
records publication; a crash between the two republishes on restart. The alternative ordering
loses the event instead. Exactly-once is a property of the *effect* at a deduplicating consumer
(`INV-IDEM-04`, P0-TSK-021), never of the relay.

---

## 4. The audit, 2026-09-01

Performed against all production code at the time of ADR-0014.

### Mechanically clean

No `synchronized`, no `ReentrantLock`, no `Semaphore`, no scheduler, no in-memory cache, and no
static mutable business state anywhere in production code. The only static mutable state is
`CorrelationContext`'s `ThreadLocal`, classified above as non-authoritative.

That is largely a consequence of ADR-0004 having chosen a database-enforced idempotency
mechanism in Phase 0, before any code could grow around a cache.

### One real defect, found and corrected

**Idempotency claim reclaim compared two different instances' clocks.**

`P0-TSK-016` let an abandoned claim be taken over once it was "stale", and staleness was:

```
created_at (written by instance A's clock)  <  now − leaseDuration (instance B's clock)
```

With N instances that is not a lease; it is a race against clock skew. An instance running six
minutes fast, with a five-minute lease, considers **every claim its neighbours have just made**
to be abandoned — takes the key, runs the command while the neighbour is still running it, and
produces two financial effects for one request. Precisely what `INV-IDEM-01` exists to prevent.

Every test passed, because they all ran in one JVM with one clock. A single-instance assumption
is invisible until there is a second instance.

**Corrected by `V004`:** `lease_expires_at` is set by the server on claim and compared against
the server's `now()` on reclaim, so no instance's clock participates. The client-side staleness
pre-check and `IdempotencyRecord.isStaleAt` were **removed rather than kept as a fast path** — a
predicate on a caller's clock invites the same assumption straight back.
`IdempotentExecutorTest.clockSkewCannotStealALiveClaim` gives the second instance a clock an
hour ahead and asserts the lease holds.

This does not contradict `P0-TSK-013`. Business timestamps — `created_at`, `completed_at`,
`expires_at` — remain application-supplied from one injected `Clock`, and the schema still
declares no `DEFAULT` for them. A lease is not a business fact; it is a coordination boundary,
and the only clock a cluster agrees on is the database's.

### Also found

The no-floating-point rule rejected `setDouble(lease.toMillis() / 1000.0d)` on the new lease
path. It was right to: a floating-point duration deciding whether a command may run twice is the
same category of mistake as floating-point money. Replaced with integer milliseconds.

---

## 5. Standing rules for future work

**Scheduled jobs.** Every instance runs the scheduler. A job is either idempotent under
duplicate execution, or takes an explicit database lease. At-least-once execution plus
idempotent processing is preferred over any "exactly once" claim. The retention sweep
(`DATA_MIGRATIONS.md` §8) and the outbox relay both fall under this and must say which they are.
The relay says so above: it takes a lease, per aggregate, for the duration of one transaction.

**Event consumers.** Duplicate-safe by inbox deduplication (`INV-IDEM-04`), and order-independent
unless an ordering key is stated explicitly. Rebalance, replay and redelivery are normal.

**Read-modify-write is forbidden on shared state.** Use a conditional `UPDATE ... WHERE`, an
optimistic version column, or a unique constraint. `SELECT` then `UPDATE` across two statements
is a lost update waiting for load.

**Atomicity is claimed only where it exists.** A local transaction is atomic within its own
boundary. Anything crossing a broker or a provider is eventually consistent and must say so.

**Concurrency tests need genuine concurrency.** Separate connections, and separate clocks where
a clock is involved. A test that shares one connection serialises itself; a test that shares one
clock cannot see skew. Both look like concurrency tests and prove much less.

---

## 6. Completion question

Before any feature is called done:

> Would this remain correct if ten instances processed requests concurrently, and would the
> financial invariant hold if two instances performed the same operation simultaneously?

**"Unknown" is a "no".**
