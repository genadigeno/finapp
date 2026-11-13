# ADR-0137 — Load shedding before the financial effect: a door in front of every borrow, per-class bounds, never after a claim

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · the API surface (`app`), every money-moving door; checkout's create
Rests on: ADR-0004 (the claim, the effect and the outcome in one transaction), ADR-0014 and ADR-0024 (no single-instance
assumption), ADR-0046 (no transaction spans a provider call), ADR-0124 (availability objectives), ADR-0126 (the workload model);
`DELIVERY_PLAN.md` §Phase 16 §7; `PHASE_16_PLAN.md` §8.

## Context

A replica has seven writer connections, a borrow waits at most 5 s for one, and every request thread, schedule and consumer shares
them. The stress profile's fourth step (`OPERATIONS_RUNBOOK.md` §11.45, E30, 2026-11-13) held all of them with slow statements and
queued up to 313 threads behind them. Three things followed. A money-moving command that waited its 5 s failed `500` before its claim,
which was safe, but its client could not tell "nothing happened" from "something failed". The checkout's create committed its session,
then read it back in a second transaction whose borrow timed out, and answered `500` after the effect: the token is in that answer only,
so twelve sessions could never be paid. And the source-join census, borrowing from the same queue, timed out every batch, so its gauge
read no value and the load sampler, which never passes blind, stopped the run.

The platform had no shedding. Its existing bounds were per component: the operational report door's two permits
(`OperationalDoorBound`), report generation's own pool, the hinted inquiries' `CallbackInquiryBulkhead`, the abuse counter's in-flight
cap. Nothing bounded money-moving commands or reads as classes, and nothing refused before the pool's queue had formed.

## Decision

1. **Shed only before the handler.** Every financial effect, and the idempotency claim that guards it, is made inside a handler
   (ADR-0004). Shedding is decided only in `HandlerInterceptor.preHandle`, so a shed request never entered its handler: no transaction
   of its command began, no claim exists, and its retry under the same key is a first attempt. Nothing is shed anywhere else, and
   no money command is queued in memory to wait for capacity.
2. **A door in front of every borrow** (`ShedAtTheDoor`), the first of the application's interceptors, ahead of the idempotency
   check, the session's and the merchant key's authentication and the per-source count, each of which may borrow a connection. It
   only refuses: what it admits meets every check behind it unchanged, so it cannot let through what authentication would refuse.
3. **Two classes and three rules**, per instance (`LoadShedding`):
   - *the pool*, every class: shed while `finapp.shedding.pool-waiters` (7) threads already wait for a writer connection, read from
     HikariCP's own count. A slow database then queues about that many requests behind the pool, and the schedules, the correctness
     monitors among them, wait behind that queue rather than behind every request thread;
   - *the permits*, commands (every method but `GET`, `HEAD`, `OPTIONS`, `TRACE`; `API_CONVENTIONS.md` §6: no money moves on those):
     at most `finapp.shedding.command-permits` (5) in flight, refused at start unless fewer than the pool's connections, so commands
     alone never hold all of them. A command past them is shed at once;
   - *the deadline*, commands, after both authentications and before the count (`ShedOnTheDeadline`): a command with less than one
     borrow's wait (the pool's `connection-timeout`, read from the pool) left of `finapp.shedding.deadline` (10 s) is shed, because its
     lookups already waited that long and its own transaction would wait again.
4. **The answer** is `503 api.Overloaded`, the same problem body for every caller and route, naming no figure of the load, with
   `Retry-After` in whole seconds: `finapp.shedding.retry-after` (1 s) plus a random 0 to `retry-after-spread` (2 s), so a crowd shed
   together comes back spread. No audit row and no log line per shed; the meter `finapp.shedding.requests{type,outcome}` is the record.
   A shed is a `5xx` on its route, so the availability objectives' burn alerts page or ticket on sustained shedding (no new alert).
5. **What is not shed.** The operator surface, by the edges' marker, so staff can act on a shedding platform; its classes keep their
   own bounds (the operational report door's permits, report generation's pool). The provider callbacks, each declaring `@NotShed`
   with its reason: a delivery is the provider's, retried on its schedule, its inquiry already bulkheaded.
6. **The existing per-class bounds stand** and are named as the plan's classes: operator reports (`OperationalDoorBound`, report
   generation's pool), provider inquiries (`CallbackInquiryBulkhead`; the sweeps' inquiries run on their schedules' own threads),
   money-moving commands (decision 3).
7. **The checkout's create is one transaction**: the session is opened and read back in the unit of work that claims its key, so the
   two commit together or not at all, and a create can no longer answer `500` after its effect.
8. **Per instance, and no correctness rests on it.** The permits are an instance field (not a `Semaphore`), the queue is the
   instance's pool. A request shed on one instance and retried on another meets the claim in PostgreSQL, which alone arbitrates.

## Alternatives Considered

### Shed inside the executor, after the claim
Pros: it would see the command's own cost. Cons: a claim exists; a shed after it is a half effect or an `IN_PROGRESS` key the retry
meets as `409`. Excluded by the entry.

### Queue commands in memory until a permit frees
Pros: fewer refusals in a short burst. Cons: an in-memory queue is lost with the pod and hides the overload from the client; the
pool's own 5 s wait is already a queue. Excluded by the entry.

### A reserved connection, or pool, for the correctness monitors
Pros: the monitors would read whatever the requests do. Cons: it changes the fleet's connection budget, which the startup guard and
the manifests hold and which `P16-TSK-009` is sizing from measurement. Kept as the follow-up if E52 shows the request-side bounds do
not keep the census reading.

### A resilience library's bulkhead
Cons: a `Semaphore` underneath (refused by `nothingUsesAProcessLocalLock`), a new dependency in the verified build graph, and no view
of the pool.

### Shedding at the edge only
The edge's per-source in-flight bound exists; it cannot see a replica's pool, and it would refuse by source rather than by what the
instance can finish.

### Shed after authentication only
Pros: a stranger is never answered `503`. Cons: authentication's lookups are the first borrows, so the queue would form before the
door; the door would sit behind the bottleneck it exists to bound.

## Consequences

Positive: a client can tell "busy, nothing done, retry the same key" from a failure; a slow database cannot queue every request
thread behind seven connections; the checkout's token cannot be lost to a failed read after its commit.

Negative: a balance read or a sign-in can be refused under load; the thresholds come from the pool's arithmetic, not from a
measurement, until PI-025's runs (E50–E52) calibrate them; requests the door admits in one instant can each add a waiter, so the
queue can pass its threshold by those.

Operational impact: `OPERATIONS_RUNBOOK.md` §11.52 (a shedding platform); five configuration keys
(`CONFIGURATION_REGISTER.md`); the meter on each replica.

Security impact: the door refuses before authentication and only refuses; the answer reads nothing of the request and names no
figure; the operator surface is exempt by a marker only the edges can set.

Financial impact: a shed has no effect. `INV-IDEM-01` and `INV-IDEM-03` hold across a shed and its retry
(`ShedBeforeTheClaimTest`).

## Invariants / Constraints

- A shed is decided before the handler, never after a claim.
- No money command waits in memory for capacity.
- The command permits are fewer than the pool's connections; the deadline exceeds one borrow's wait (refused at start otherwise).

## Follow-up

- Calibrate the three thresholds from E50 (the spike profile), E51 (a slowed database) and E52 (the stress profile's break), owed
  under `POSTPONED_ISSUES.md` PI-025.
- A reserved connection for the correctness monitors if E52 shows they still starve, decided with the connection budget.
