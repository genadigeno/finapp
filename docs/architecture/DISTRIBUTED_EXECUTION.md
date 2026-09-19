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

**This table is now an enforced exemption set, not only a record** (`P0-TSK-041`, ADR-0024). The
build fails on `synchronized` (method or block), a process-local lock, ambient scheduling, or static
mutable state in production code, and the only permitted process-local entries are the two
`ThreadLocal`s below - named individually, because a type-wide exemption would admit the third one
without anyone deciding. Adding a process-local mechanism means adding it here, with the reason it
cannot affect correctness.

| Component | State | Multi-instance strategy | Authoritative? |
|---|---|---|---|
| `Money`, `CurrencyCode`, `RoundingPolicy` | none — immutable values | No shared state to protect | n/a |
| `EntityId` | none — immutable value | n/a | n/a |
| `IdGenerator` | in-memory `(millis, counter)` per instance | **Non-authoritative.** Uniqueness across instances rests on 62 `SecureRandom` bits, not the counter. The counter provides ordering *within* one instance only | No |
| `CorrelationContext` | `ThreadLocal` per request | **Non-authoritative.** Diagnostic context; each instance carries its own. Losing it costs traceability, never correctness | No |
| `SecurityContext` | `ThreadLocal` per flow | **Non-authoritative.** It carries the acting party within one instance; it is never the *source* of one. The durable answer to "who did this" is the audit row, written in the action's own transaction. Losing the context costs the operation, not correctness: `require()` throws rather than defaulting, so a lost actor fails the request instead of recording the wrong party (ADR-0021) | No |
| `MoneyColumns` | none — a column convention | n/a | n/a |
| `platform.idempotency_record` | durable | **Unique constraint** on (scope, key). The database is the arbiter (ADR-0004) | **Yes** |
| `IdempotentExecutor` | none — all state in the row | Claim by insert; conditional updates; database-owned lease | Delegates to the row |
| `JdbcIdempotencyRecordStore` | none | Conditional `UPDATE ... WHERE`, never read-then-write | Delegates |
| `platform.outbox_event` | durable | The pending set is a predicate (`published_at IS NULL`), not a cursor. Eligibility and abandonment are decided by the **server's** clock | **Yes**, for publication state |
| `OutboxWriter` / `JdbcOutboxWriter` | none | Writes on the caller's connection and opens nothing of its own (`INV-EVT-01`) | Delegates to the row |
| `OutboxRelay` | none — all state in the row | **Transaction-scoped advisory lock per aggregate.** Every instance polls; one drains a given aggregate at a time; conditional `UPDATE ... WHERE published_at IS NULL` on every write | Delegates to the row |
| `platform.inbox_message` | durable | **Primary key** on (consumer, dedupe_key). The database arbitrates between two instances handed the same redelivery | **Yes**, for "has this consumer handled this?" |
| `InboxConsumer` / `JdbcInboxRecordStore` | none — all state in the row | Insert-then-handle in the caller's transaction; a bounded `lock_timeout`, then report `CONTENDED` and let the broker redeliver | Delegates to the row |
| `KafkaEventReceiver` / `InboxConsumers` | consumer-group offsets, held **broker-side**; a poll loop thread per module per instance | **Non-authoritative, explicitly.** An offset is committed only after the inbox transaction committed, so every failure between the two — crash, rebalance, lost connection — redelivers into the dedupe, and losing the offsets entirely replays the topic into it. The group protocol shares work; it never decides correctness — during a rebalance two instances can hold the same in-flight record, and the arbiter is `platform.inbox_message`'s primary key. The loop threads are per-instance mechanics whose loss costs this instance's consumption and nothing else | No — delegates to the inbox row |
| `platform.audit_record` | durable | Append-only by **privilege**, not by convention: the application role holds no `UPDATE` or `DELETE`, so no instance can edit the trail whatever its code does | **Yes** |
| `AuditWriter` / `JdbcAuditWriter` | none | Writes on the caller's connection and opens nothing of its own; insert-only, so there is no lost update to have | Delegates to the row |
| `identity.authentication_failure` | durable | **One row per identity, updated by one atomic statement** — `INSERT … ON CONFLICT DO UPDATE … RETURNING`, so the post-increment count is produced *by the write*. There is no read-then-write to lose, which is what `INV-CON-03` means by a limit that is not bypassable: with a read-then-count, ten concurrent attempts at the threshold all read nine and all proceed. Every window and expiry decision uses the **server's** `now()` (`V004`, ADR-0014) | **Yes** |
| `AuthenticationThrottle` | none — all state in the row | Keys off the login identifier and resolves it in a subselect *inside the same statement*, so an absent account and a present one run the same query shape — the two-query alternative is a timing difference that discloses existence | Delegates to the row |
| `identity.session` | durable | **The authority, and there is no cache in front of it.** Every request that presents a token reads this table, so a revoked session is refused on the next request on every instance by construction (`INV-IDN-03`) rather than by a cache being told. Liveness is decided by the **server's** clock in the lookup predicate. `NoProcessLocalSessionStateTest` fails the build if any production type grows a field holding sessions — the shape ADR-0024's four patterns cannot see, and transition risk **R7** | **Yes** |
| `SessionStore` / `JdbcSessionStore` | none — all state in the row | Reads and writes on the caller's connection and keeps nothing between calls. Extending the idle bound is a conditional `UPDATE … WHERE` clamped by `LEAST(…, absolute_expires_at)`, so a touch can neither resurrect a revoked session nor outlast the absolute bound | Delegates to the row |
| `SessionRevocation` | none — all state in the row | Bulk revocation takes `SELECT … FROM identity.identity … FOR UPDATE`, and a session insert takes `FOR KEY SHARE` on the same row **through its foreign key**. The two conflict, so a session cannot be issued concurrently with a revocation and survive it (`PHASE_1_PLAN.md` §8). Only the revoking side needs an explicit lock — an explicit one on the issuing side was written, found redundant by a surviving mutation, and removed rather than left to read as the mechanism | Delegates to the row |
| `kyc.kyc_case` | durable | **A partial unique index over the non-terminal states** is the one-open-case arbiter — a rule *across aggregates of the same type*, which only the database can settle between two concurrent transactions (`P1-TSK-005`'s reasoning). Ten instances opening for one customer produce one row and nine **converged** callers, not nine errors, because "ensure my case exists" is what both doors mean. A decision frees the slot, so a successor case is insertable (`INV-LIFE-04`). `case_kind` is unwritable at **`DB-PRIVILEGE`**: `V008` revokes the table-wide `UPDATE` and re-grants exactly `(status, status_changed_at)`, because a `KYB → KYC` flip is the one write that would silently disarm the ownership gate | **Yes** |
| `KycCaseStore` / `JdbcKycCaseStore` | none — all state in the row | Every transition is a conditional `UPDATE … WHERE status = ?` whose **row count is the outcome**, so N racing movers produce one winner and N−1 truthful losers. Readiness additionally takes `SELECT … FOR UPDATE` on the case row **before** evaluating its predicate: under `READ COMMITTED` a blocked `UPDATE` re-runs its subqueries against the statement's *original* snapshot, so a predicate alone cannot see an owner committed while it waited (`P2-TSK-015`'s write skew) | Delegates to the row |
| `kyc.verification_check` | durable | **A partial unique index over the in-flight states** admits one question per check type; a terminal `INDETERMINATE` leaves the index, which is what makes a successor check insertable — resolution is a *new* check, never a reopened one (ADR-0038). Dispatch is a conditional transition committed **before** the provider is called, so only the instance that won it calls out: one dispatch, one provider effect, assertable as `requestCount == checkCount` under a ten-way race | **Yes** |
| `kyc.verification_evidence` | durable | Append-only by **privilege** — `SELECT, INSERT` and nothing else — so no instance can rewrite a provider's answer whatever its code does (`INV-HIST-02`). Evidence is appended on the callback path *always*, including for a late or losing delivery, because both are genuine provider statements an investigation wants | **Yes** |
| `kyc.review_task` | durable | **A total `UNIQUE (check_id)`** — total rather than partial, because the resolution of *that question* is permanent evidence and changed circumstances are a new check with its own task. It is also the concurrency arbiter: N assessors reaching `BLOCKED` insert `ON CONFLICT DO NOTHING` and exactly one wins. Resolution is a conditional `UPDATE` on `status = 'OPEN'`, and a resolved row then **freezes whole** by trigger | **Yes** |
| `kyc.kyc_decision` | durable | The **conditional case transition** `READY_FOR_DECISION → terminal` is the arbiter, not the index: whoever wins it records, so N concurrent deciders produce one decision, one audit record and N−1 losers told which of the two causes applied. The total `UNIQUE (case_id)` behind it is defence in depth that would only ever fire on an already-violated invariant. No `UPDATE` grant exists at all, so the privilege *is* the immutability (`INV-KYC-02`, the `audit_record` model) | **Yes** |
| `kyc.beneficial_owner` | durable | Unique per (case, owner), append-only by grant, and declaration takes the **same case-row `FOR UPDATE`** the readiness move takes — lock-then-look on both sides, so whichever arrives second sees the first. Once a case is `READY_FOR_DECISION` the owner set can never change, which is what lets the decision reference it without re-checking | **Yes** |
| `consent.consent_record` | durable | **The history *is* the store** (ADR-0037): grants and withdrawals are immutable facts and the current basis is **derived per read**, never stored. Order is the server's — `seq BIGINT GENERATED ALWAYS AS IDENTITY`, so no instance's clock or counter can decide which of two racing facts is later — and the derivation orders by `seq`, never `recorded_at`. Append-only at **`DB-PRIVILEGE`** (`INV-CNS-02`); ten instances append with no locks and no losers | **Yes** |
| `consent.consent_text` | durable | Unwritable by the application **entirely** — `SELECT` only. A consent text is a reviewed platform artefact, and a forward-only migration is the reviewed immutable channel it arrives through; a wording change is a new version in a new migration, and whether it demands re-consent is a recorded property of the version rather than a guess (`INV-CNS-04`) | **Yes** |
| `ConsentGate` | none — **deliberately** | One authoritative read per decision, on the caller's unit of work, with **no cache of any kind**: from the transaction that records a withdrawal, every instance refuses on its next decision (`INV-CNS-03`) — `INV-IDN-03`'s reasoning applied to lawful basis. `NoProcessLocalConsentStateTest` fails the build on a field retaining consent types, and its limit is recorded rather than implied: a bare `Map<String, Boolean>` names no consent type and is invisible to it, so the **behavioural cross-instance race is the load-bearing control** there and the field detector is a second one, blind in a different direction | Delegates to the row |
| `party.organisation_registrant` | durable | A **total `UNIQUE (registrant_party_id)`** doing three jobs at once: the Phase 2 scope bound (one organisation per person, ever), the arbiter for ten racing registrations, and the convergence key that makes a repeat registration replay the original `201` rather than conflict | **Yes** |
| `ledger.ledger_account` | durable | Both derivations (`normal_balance` from type, `owner_kind` from purpose) are stored and held to their derivation by **generated `CHECK`s**, so a writer that never ran our code cannot store an incoherent row. Identity fields frozen **unconditionally** by trigger; the classification freezes the moment a `journal_line` references the account (`INV-LED-06`), for every writer. Two partial unique indexes arbitrate: one owned account per (owner, purpose, currency) with `createOrConverge` behind a savepoint, and one operational account per (purpose, currency) so `ChartOfAccounts.resolve` has one answer. `UPDATE` grant column-narrowed to `(status, status_changed_at)` | **Yes** |
| `ledger.journal_entry` / `journal_line` | durable | **Entries are inserts, so under `READ COMMITTED` there is no lost update to have** (ADR-0039) — no lock is taken to post. Balance and line-count are judged at COMMIT by **deferred constraint triggers** for every writer (`INV-LED-01`, `INV-LED-02`); a line's currency is bound to its account's by composite FK (`V005`); immutability is the application role holding `INSERT`/`SELECT` only **plus** an unconditional append-only trigger binding even the migrator (`INV-LED-03`, `INV-HIST-01`) | **Yes** — the authoritative financial record |
| `ledger.account_balance` (`BalanceProjection`) | durable | Updated **in the posting's own transaction** under the row's exclusive lock — the increment re-reads under the lock (the `P1-TSK-011` counter shape), never read-modify-write; first rows converge through `ON CONFLICT`; multi-account entries lock projection rows in **one fixed order**, so no two postings deadlock. `last_entry_seq` is serialised by the same lock, so "is this current?" is a count comparison, never a time window. **No decision may read it** (ADR-0041): the write port's one method returns `void`, and the declared readers state what they return | No — display and verification only; the postings are the authority |
| `BalanceDerivation` | none — all state in the rows | One statement, one snapshot, committed postings only; sums fold through the kernel, never a SQL `SUM`. Every instance derives independently and gets the same answer, which is what `INV-BAL-02` means | Delegates to the journal rows |
| `ProjectionVerification` / `TrialBalance` | none — per-instance cached *readings* only | **No lock anywhere, deliberately** — a verifier must never contend with the write path it audits. The projection sweep tolerates in-flight postings by the **seq bracket** (stable watermark = one applied set; unstable = `IN_FLIGHT`, settled next run); the trial-balance sweep needs no in-flight verdict at all, because one `SELECT` reads one snapshot and a snapshot never contains half a deferred-judged entry. **The scrape is the schedule**: the sweep runs when a scrape finds the cached reading past its floor, so nothing schedules ambiently, no leader and no lease exist, and all instances publish the same fleet-wide figure (`max()`, never `sum()`). Detected drift is **reported, never repaired** | No — verdicts about the authority, never the authority |
| `ledger.hold` / `HoldService` | durable | Place and release take `SELECT … FOR UPDATE` on the **account row** (ADR-0039's enumerated set) — the mode that conflicts with every in-flight posting's `FOR KEY SHARE` and every sibling placer — then derive availability from authoritative rows in fresh statements **inside the lock** (`INV-BAL-04`, `INV-BAL-05`). Release is a conditional `UPDATE` whose row count gates the decrement, the record and the event, so a retried release converges; `RELEASED` is terminal by trigger for every writer | **Yes**, for reservation state |
| Reversal (`ReversalService` + `V009`) | none — all state in the rows | The over-reversal bound is summed per `(account, direction)` **inside a `BEFORE INSERT` trigger under `pg_advisory_xact_lock(2, hashtext(original))`** — namespace 2, registered below — so every writer reversing one original serialises, raw SQL included. The insert-vs-insert predicate alone cannot see a concurrent sibling (the `P2-TSK-015` snapshot lesson), and the row-lock arbiters are unavailable **by the phase's own privilege design** (no `UPDATE` on the journal), which is why the lock is taken in the database | Delegates to the journal rows |
| `ledger.adjustment_proposal` | durable | The one-way machine **is** the idempotency (`PROPOSED → {APPROVED, REJECTED}`, both terminal): approval is lock-then-look (`FOR UPDATE` on the proposal row) with the conditional decision as belt, so ten concurrent approvals produce one entry and every response converges on it. The payload is **frozen by trigger for every writer** — approve-what-you-read is structural — approver ≠ initiator is a `CHECK`, and a deferred trigger refuses any `ADJUSTMENT` entry COMMIT without an `APPROVED` proposal (`INV-AUD-04`). `UPDATE` grant column-narrowed to the decision columns | **Yes** |
| `accounts.customer_account` | durable | A **partial unique one-live index** per (customer, product type) arbitrates ten concurrent opens with nine converged behind a savepoint; eligibility is a **per-decision authoritative read** of the verification projection inside the opening's own unit of work — no cache, and the account's `customer_id` is that read's answer, never a caller's. Close takes `SELECT … FOR UPDATE` — the mode that conflicts with a posting's `FOR KEY SHARE` trigger read — then derives the zero-balance check inside the lock; `V007`'s trigger refuses posting to a non-`ACTIVE` account for every writer, both interleavings proven with the loser observed Lock-waiting | **Yes**, for the agreement; the money is the ledger's |
| `transfers.transfer` / `TransferExecution` + `TransferReversal` | durable | The row is **insert-carries-outcome** (`V002`'s grants make a `FAILED` outcome unreachable by `UPDATE`), so the execution has no lost update to have and takes its contention on the ledger's **source-account row** (ADR-0039's enumerated `FOR UPDATE` set; the claim's unique constraint arbitrates retries). The reversal (`P4-TSK-009`) is **lock-then-look on the transfer row**: `SELECT … FOR UPDATE`, the machine judged from the locked read *before any ledger work* — the loser of two concurrent reversals blocks, resumes onto the winner's commit and is refused with nothing posted — with the conditional `UPDATE`'s row count as belt, `V002`'s trigger edges binding raw SQL, and `V009`'s in-trigger advisory bound as the deepest layer. *(This row was added by `P4-TSK-009` rather than left to the next transition audit — the register-decay note below, finally heeded by a task instead of repaired after it.)* | **Yes**, for the movement's judgement; the money is the ledger's |
| `LedgerMetrics` caches | per-instance cached readings behind a floor | **Non-authoritative** — the `IdentityMetrics$Cached` stance: a reading, never a decision input; NaN when unreadable, never zero | No |

### Why Phase 3 added ten rows and one registered lock (`P3 → P4` transition)

Phase 3 introduced the platform's most contended shared state — the journal, the projection,
holds, the proposal — and **one genuinely new coordination twist**: `V009`'s advisory lock is
taken *inside a trigger*, so it binds every writer including raw SQL, where every earlier lock
bound only code that chose to take it. Everything else is the four Phase 0-proven protocols
plus ADR-0039's enumerated `FOR UPDATE` set (holds, account close, and — Phase 4 — the
transfer debit). Two deliberate *absences* are themselves the design: postings take no lock,
because inserts have no lost update to have; and the verifiers take no lock, because
monitoring must never become the contention it exists to detect.

**These rows were missing until the Phase 3 → 4 transition audited for them — the fourth
occurrence of the register-decay class**, one transition after the note below named the
pattern. The register decayed again at exactly the boundary where nobody was looking, which
strengthens rather than weakens the note's conclusion: the registers with build guards behind
them have not decayed, and this one's guard (ADR-0024's rules) checks only the *process-local*
direction. Adding durable-state rows remains discipline, and discipline means the transition
audit checks for them **by name** from now on.

### Why Phase 2 added eleven rows and no new mechanism (`P2 → P3` transition)

Every row above is one of four protocols Phase 0 already proved: a **unique index** as the
arbiter, a **conditional `UPDATE … WHERE`** whose row count is the outcome, a **privilege**
that makes a write impossible rather than merely unwritten, and **lock-then-look** where a
predicate's own snapshot cannot be trusted. Phase 2 introduced no coordination primitive of its
own, took no leader, and added no process-local state — which is the claim this register
exists to make checkable, and it is checked: the `synchronized`/lock/scheduler/static-state rules
still name exactly two permitted `ThreadLocal`s, and the phase's own guard
(`NoProcessLocalConsentStateTest`) adds a third detector rather than an exemption.

**These rows were missing until the Phase 2 → 3 transition audited for them**, which is the
same decay `P0-TSK-032`'s review found (`SecurityContext` absent) and the Phase 1 → 2
transition found four more of. The register is an **enforced exemption set**, so an absent row is
not merely an undocumented component — it is a component whose next author finds no precedent
to extend and no reason recorded for why it needs none.

### `OutboxRelaySchedule` — why every instance runs a scheduler (`P2-TSK-001`)

The named exemption to `nothingSchedulesAmbiently`, and the register row that exemption points
at. Every instance runs the relay poll on a fixed delay **deliberately — there is no leader**:
the rule's stated bar is that scheduled work be idempotent per period *or take an explicit
database lease*, and each poll takes a transaction-scoped advisory lock **per aggregate** in
PostgreSQL (`P0-TSK-020`). Ten pollers drain disjoint aggregates; a poll that wins no locks does
nothing; losing the executor costs this instance's polling and nothing else. The exemption names
this class alone and is proven load-bearing — any future scheduler that is not lease-protected
is a new decision, not a ride on this one.

The `KafkaProducer` the adapter holds is likewise per-instance and non-authoritative: its
buffers are in-flight copies of durable outbox rows, and losing them costs a retry, never a
fact.

### `InboxConsumers` — why the consumer loops need no lease (`P2-TSK-002`)

The consuming counterpart, and deliberately **not** a second exemption to
`nothingSchedulesAmbiently`: each loop is a plain thread whose pacing is the poll's own bounded
blocking — no scheduler, so the rule has nothing to see. Every instance runs the loops, and
that needs no lease because the two jobs a lease would do are done elsewhere: **work-sharing**
is Kafka's consumer-group protocol (one group per consuming module, partitions assigned
disjointly in the steady state), and **correctness** is the inbox primary key — which is why a
rebalance handing the same in-flight record to two instances is the design's normal case rather
than a hazard. The offset commit happens strictly after the inbox transaction commits; the two
cannot be atomic, and every failure between them resolves as a redelivery into the dedupe,
which is the safe direction. The reverse ordering — acknowledge, then effect — is the one that
loses records, and `KafkaEventReceiverTest` asserts the order rather than describing it.

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

### `InboxConsumer` — why losing the race is free here

The idempotency kernel waits a few seconds for a competing claim, because a caller is holding a
connection waiting for an answer and "unknown, retry" is a poor thing to tell it. A consumer has
no such caller. Losing the race costs exactly one redelivery, which an at-least-once transport
was going to perform anyway — so the wait is short (500ms), and the loser is told `CONTENDED` and
leaves the message unacknowledged.

That answer is correct whichever way the other transaction goes, which is why it does not need to
wait to find out: if the holder commits, the redelivery is deduplicated; if it rolls back, the
redelivery is handled. Waiting longer would only convert a free redelivery into a held connection
during precisely the traffic spike that produced the duplicates.

**Insert-then-handle, not handle-then-insert.** Both are in one transaction so atomicity is
identical; the difference is that inserting first makes a concurrent duplicate block on the
primary key *before* it enters the handler. Handling first would let two instances run the same
handler simultaneously and discover the collision only at the end, after both had done the work.

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
| `2` | `V009`'s reversal-bound trigger (`P3-TSK-016`) | `hashtext(reverses_entry_id::text)` — taken **inside the trigger**, so every writer reversing one original serializes, raw SQL included; transaction-scoped, held to commit (`INV-REV-02`). Taken in the database rather than in Java deliberately: a Java-side lock would bind only the writers that ran our code |

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
`CorrelationContext`'s `ThreadLocal`, classified above as non-authoritative. `SecurityContext`
(`P0-TSK-032`) adds a second one, classified alongside it: same mechanism, and a strictly safer
failure mode, because an absent actor is refused rather than defaulted.

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

## 4a. The connection budget

**Every instance holds connections, and the database has a finite number of them.** That is the one
resource N instances contend for which is not solved by any of the protocols above — no lock, no
constraint and no idempotency key makes a connection available.

The arithmetic (`P1-TSK-004`):

```
instances × maximum-pool-size  ≤  server max_connections − reserved
```

**The defaults fail it, which is why this is a guard and not a note.** Hikari's default pool is 10
and PostgreSQL's default `max_connections` is 100, so **ten instances exhaust the server before a
single connection does any work** — and §1 says N is never 1. Nothing in either default notices.
Each instance starts, fills its pool, and the instances that lose the race fail readiness with
*connection is not available*, which reads as the pool being too small or the database being slow.
It is neither. It is arithmetic nobody did.

It is the worst shape of operational failure: it appears only during a full deploy, a scale-out or a
restart storm — the moments when diagnosis is hardest — and the symptom points away from the cause.

**Dividing `max_connections` by the instance count is the wrong repair**, and it is the obvious one.
That treats the limit as a budget to spend; it is a ceiling not to hit. Every connection is a
backend process with its own memory, and PostgreSQL throughput stops improving once the machine's
cores are busy — past that the extra connections queue *inside* the database, where the queueing is
invisible to the application and appears as latency on every query rather than as a pool timeout on
one. So the pool is sized small for throughput, and the budget check is a separate question asked
afterwards: given that pool, does the whole fleet still fit?

**Why anything is reserved.** PostgreSQL keeps `superuser_reserved_connections` (3 by default), so
those were never ours. The rest is operational headroom: a migration runs as `finapp_migrator`
during a deploy, and an operator diagnosing an incident connects with `psql`. If the fleet is sized
to consume every remaining connection, the one thing nobody can do when the fleet is in trouble is
connect to the database to find out why.

**Enforced at startup**, by `ConnectionPoolSizingGuard`, because the three numbers live in three
places — application configuration, a deployment's replica count, and a database setting — so
nothing brings them together and nothing notices when one moves. Each is changed by someone with no
reason to be thinking about the other two, and scaling from eight instances to twelve is an ordinary
operational act. `ConnectionPoolSizingIsConfiguredTest` additionally checks the shipped numbers in
the build, so a violation is caught in the change that introduced it rather than by a rolling
restart discovering it one instance at a time.

**Two limits, stated rather than implied.**

- The guard **cannot verify `max_connections` against the live server** and does not try: it runs
  before the pool is used, and one that queried the database would fail for a database that is
  merely down. `finapp.database.server-max-connections` is a *declaration* by the deployment, and a
  wrong declaration is a wrong answer. That is why it has no silent default in a deployment's own
  configuration.
- The arithmetic assumes each instance holds its **full** pool. True here because `minimum-idle`
  equals `maximum-pool-size`, which is both HikariCP's recommendation and what makes the check
  meaningful — a pool that only sometimes reaches its maximum would make this a statement about the
  average, and a server is exhausted by the worst case.

**What it does not do:** shrink the pool to make the numbers work. That would change a deployment's
capacity on its own initiative, silently, when the right answer is often to raise `max_connections`
or run fewer instances — decisions this code has no business taking. It reports the largest pool
that would fit and refuses.

## 5. Standing rules for future work

**Scheduled jobs.** Every instance runs the scheduler. A job is either idempotent under
duplicate execution, or takes an explicit database lease. At-least-once execution plus
idempotent processing is preferred over any "exactly once" claim. The retention sweep
(`DATA_MIGRATIONS.md` §8) and the outbox relay both fall under this and must say which they are.
The relay says so above: it takes a lease, per aggregate, for the duration of one transaction.

**Event consumers.** Duplicate-safe by inbox deduplication (`INV-IDEM-04`), and order-independent
unless an ordering key is stated explicitly. Rebalance, replay and redelivery are normal.

**Four single-instance patterns fail the build** (ADR-0024): `synchronized`, process-local locks,
ambient scheduling and static mutable state. They are the mechanically detectable half of this
document; the other half is the design question in §6, which no rule can answer. The
`IdempotentExecutor` defect in §4 used none of the four - it was a clock comparison - so the rules
narrow the ways to be wrong rather than closing them.

**Read-modify-write is forbidden on shared state.** Use a conditional `UPDATE ... WHERE`, an
optimistic version column, or a unique constraint. `SELECT` then `UPDATE` across two statements
is a lost update waiting for load.

**Atomicity is claimed only where it exists.** A local transaction is atomic within its own
boundary. Anything crossing a broker or a provider is eventually consistent and must say so.

**Concurrency tests need genuine concurrency.** Separate connections, and separate clocks where
a clock is involved. A test that shares one connection serialises itself; a test that shares one
clock cannot see skew. Both look like concurrency tests and prove much less.

### The multi-instance test convention (`P0-TST-009`)

A test that claims to simulate N instances gives each of them:

1. **its own connection** — two "instances" on one session cannot contend for a row lock, because
   the second statement simply waits for the first on the same session;
2. **its own component instance** — a shared object is one instance wearing eight hats;
3. **its own clock, where a clock participates in the decision** — and the skew must be measured
   **against the server**, never against a fixture constant.

`SimulatedInstance` supplies all three. `skewedBy(Duration)` anchors the clock on `SELECT now()`,
which is the correction this task exists for.

**Why the third point is stated so bluntly.** `clockSkewCannotStealALiveClaim` was written with
`P0-TSK-016`'s fix precisely to prove skew could not steal a live claim. It built the fast
instance's clock as `FIXED.plus(1 hour)` from a hard-coded `2026-09-01T12:00:00Z` — which, measured
against the running container, was about **forty hours behind** the server rather than an hour
ahead. The test passed. It also passed when the defect was deliberately reintroduced, because a
*slow* instance never believes anything has expired. It was named for a property it did not
exercise, and nothing said so for two tasks.

Every skew test therefore carries a **precondition** asserting the skew is real and in the
dangerous direction, exactly as the redaction tests assert their appenders received the line.

**The harness's own limit, stated rather than implied.** Nothing mechanically prevents
`SimulatedInstance.serverNow()` being changed to read the JVM's clock instead of the database's. On
a machine where the two happen to agree - which is most of them, and is nearly true here, where the
container drifts only about half a second - every skew test would keep passing while measuring the
wrong thing again. The precondition does not catch it either, for the same reason. A guard would
have to assume a drift that may not exist, so the defence is that the anchor is named here and in
the harness, not that a test enforces it.

### The audit, 2026-09-02

| Test | Own connection per instance | Own clock | Verdict |
|---|---|---|---|
| `IdempotentExecutorTest` (8-way race, reclaim, skew) | yes | yes, now server-anchored | conforms |
| `IdempotencyFailureModeTest` | yes | yes | conforms |
| `InboxConsumerTest` (8 instances, one redelivery) | yes | shared — correct, see below | conforms |
| `OutboxRelayTest` (8 instances drain a backlog) | yes | shared — correct, see below | conforms |
| `OutboxCrashRecoveryTest` | yes | shared — correct | conforms |
| `AuditWriterTest` (concurrent audit writes) | yes | shared — no clock in the decision | conforms |
| `IdempotencyRecordSchemaTest` (16-way contention) | yes | n/a | conforms |

**A shared clock is correct in every case but one, and that is the design working.** Eligibility,
abandonment, leases and retention are all decided by the *server's* clock (§3), so no client clock
participates in a cross-instance decision. The single place one did was the idempotency lease —
which was the defect ADR-0014 was written for, and is now server-side. A test sharing a clock is
therefore not a finding; a test sharing a clock **where a client clock decides something** would
be, and there is nowhere left for that to happen.

---

## 6. Completion question

Before any feature is called done:

> Would this remain correct if ten instances processed requests concurrently, and would the
> financial invariant hold if two instances performed the same operation simultaneously?

**"Unknown" is a "no".**
