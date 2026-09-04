# ADR-0033 — Explicit SQL, and no object-relational mapper

Status: Proposed

Date: 2026-09-04

## Context

Unresolved question 12 has been open since `P0-TSK-011`, which asked for "a reusable JPA
embeddable" for money and produced a mechanism-agnostic `MoneyColumns` instead, because no ADR
had chosen a data-access mechanism and writing one would have chosen it by accident.

The question was scheduled for Phase 3 and **brought forward to Phase 1** by the Phase 0 → Phase 1
transition. Phase 1 introduces **nine tables and six aggregates** into a platform whose
only four tables are kernel infrastructure — an idempotency record, an outbox, an inbox and an
audit trail. Deciding after the first repository is written
means writing the second one twice.

It matters here more than it usually does, for a reason specific to this platform:

**Three of the strongest invariants in the catalogue are statements about a privilege the
application must not hold.** `INV-HIST-03` (audit append-only), `INV-HIST-01` (financial history
never edited) and `INV-LED-03` (posted entries immutable) are all enforced at `DB-PRIVILEGE` — the
second-strongest rank — by `finapp_app` holding **no `UPDATE` and no `DELETE`** on the tables
concerned (`V008`, `V009`).

A privilege model is only as good as the guarantee that nothing emits a statement nobody wrote.

### What the kernel already relies on

Every one of these is in production code today and was proven under concurrency against a real
PostgreSQL. They are not stylistic preferences; they are the mechanisms that make the platform
correct with N instances (ADR-0014, `DISTRIBUTED_EXECUTION.md` §3):

| Protocol | Where | What it needs from data access |
|---|---|---|
| Claim-by-insert | `JdbcIdempotencyRecordStore.claim` | The `INSERT` reaches the database **when the code says so**, so the unique violation or lock-wait happens now |
| Bounded lock wait | `SET LOCAL lock_timeout` around that insert | Statement-scoped session settings, same connection, same transaction |
| Conditional update, never read-then-write | idempotency, outbox | The **absence of the read** is the safety property |
| Transaction-scoped advisory lock per aggregate | `OutboxRelay` | `pg_try_advisory_xact_lock`, released by the transaction and not by a session a pool may hand on |
| Savepoint around an expected unique violation | `JdbcInboxRecordStore` | `Connection.setSavepoint`, so the caller's transaction survives |
| Write on the caller's connection, opening nothing | `OutboxWriter`, `AuditWriter` | `INV-EVT-01`: the fact and its outbox row commit together |
| Server clock for every lease | `V004`, `V006` | `now()` in SQL — ADR-0014's founding defect was a lease judged by two clients' clocks |

### The seam this closes

Four kernel ports — `AuditWriter<T>`, `OutboxWriter<T>`, `InboxRecordStore<T>`,
`IdempotencyRecordStore<T>` — are generic over the unit of work, and three of them say so
explicitly: *"a JDBC `Connection` today, whatever the Phase 3 decision produces later"*. That
generic parameter is the seam left for this ADR. This is the decision it was waiting for.

## Decision

**Authoritative writes and aggregate loads use explicit SQL through Spring's `JdbcClient`. No
object-relational mapper, no persistence context, no generated repositories.**

`spring-jdbc` is already on the runtime classpath (`spring-boot-starter-jdbc`, added by
`P0-TSK-027` for the readiness check), so this adds **no dependency**. `JdbcClient` supplies named
parameters, fluent binding and `RowMapper`, which removes the positional-index boilerplate that is
the honest objection to raw JDBC.

**Scope.** This governs **authoritative state**: the tables a module owns and writes. It
deliberately does **not** govern derived read models or reporting projections, which are Phase 14's
decision. Saying so now stops this ADR being cited later to forbid something it never weighed.

### Structure

The port/adapter split the kernel already uses, one layer up:

```
identity/                   IdentityRepository       port, owned by the domain
identity/internal/jdbc/     JdbcIdentityRepository   adapter, takes the unit of work
```

`IdempotencyRecordStore<T>` / `JdbcIdempotencyRecordStore` is that pattern verbatim.
`moduleInternalsArePrivateToTheirModule` already enforces the visibility half.

### The unit of work is a `Connection`, permanently

`T` is `java.sql.Connection` and will not become an `EntityManager`.

**The generic parameter is not removed**, and that is a deliberate non-change rather than an
oversight. Collapsing `<T>` across four interfaces, their implementations and their tests is a
refactor of proven Phase 0 code with no correctness benefit, and `EXECUTION_PROTOCOL.md` rule 4
forbids exactly that. What *is* corrected is the javadoc: five files said the choice belonged to
Phase 3, which stopped being true when this ADR was written, and `DEFINITION_OF_DONE.md` §3 forbids
documentation describing behaviour that does not exist.

### Transactions are begun explicitly

**`TransactionTemplate`, not `@Transactional` on service methods.**

`DEFINITION_OF_DONE.md` §1.4 requires the transaction boundary to be *"explicit and deliberate, not
an accident of annotation placement"*. `@Transactional` fails **silently** on self-invocation: the
method runs, with no transaction, and nothing anywhere reports it. For a registration that must
write two modules' tables plus an audit record plus an outbox row **in one commit**, a silently
absent transaction is a partially created person with an audit trail claiming otherwise.

`JdbcClient` and the kernel's `Connection`-taking writers share one connection through
`DataSourceUtils`, which is what keeps `INV-EVT-01` true across the two.

### Rules this fixes for every later task

- Schema is authored **only** by Flyway (ADR-0011). No mechanism generates DDL.
- Invariants expressible as database constraints are enforced there, not only in a mapper.
- Error translation preserves **SQLState** (`23505` unique violation, `55P03` lock timeout), never
  parsed message text — messages are localisable, a correction the `P0-TSK-011` review already
  made once.
- State transitions are conditional statements — `UPDATE … WHERE state = ?` — so a losing writer
  is told it lost. Not a mutated field and a flush.
- `MoneyColumns` remains the money convention and is now unblocked.

### Enforced, not merely recorded

`DOD-ARCH` requires a boundary to be *"enforced by an executable rule where enforceable"*, and this
one is. `NoObjectRelationalMapperTest` asserts that no JPA or Hibernate artefact is on the
application's **runtime** classpath.

The precedent is exact: `P0-TSK-027` made ADR-0011's "no migrations at startup" structural by
proving Flyway absent from that same classpath, rather than by setting a property that says so.

Writing it found a real gap. `MODULE_ARCHITECTURE.md` §6 forbade JPA and Hibernate **in
`sharedkernel` only** — and `sharedkernel` is not where an ORM would ever be added. `platform`,
`app`, `party` and `identity` were unprotected.

It passes vacuously today, which is why it carries a vacuity guard: the classpath property must be
non-blank, so a check that cannot see the classpath fails loudly instead of reporting that it found
nothing wrong. It stops being vacuous the first time somebody types
`spring-boot-starter-data-jpa`, which is precisely when it needs to fire.

## Alternatives Considered

### Option A — JPA / Hibernate
Pros: the industry default; least boilerplate; associations, lazy loading and optimistic locking
for free; every Java developer has used it; the widest hiring pool.

Cons, and the first one is decisive:

- **Dirty checking emits `UPDATE` without anyone writing one.** Against `platform.audit_record`,
  where the application role holds no `UPDATE`, that is a permission error whose timing depends on
  whether an entity happened to be dirty at flush — code far from the write decides whether the
  statement is emitted. That is the shape of defect that passes every test and fails in production,
  and it attacks invariants enforced at `DB-PRIVILEGE` — which the catalogue ranks second to
  `DB-CONSTRAINT`, but which is the strongest mechanism available for *forbidding an operation*:
  a `CHECK` constraint cannot express "this role may not `UPDATE`".
- **Deferred flush breaks claim-by-insert.** The whole mechanism of `INV-IDEM-01` is that the
  insert contends *now*: it either wins, or blocks and then reports `23505`. A persistence context
  decides when the statement is sent.
- **The first-level cache is a stale read in front of the arbiter.** Every race in this platform is
  settled by the database. `INV-IDN-03` requires session revocation to be immediate on every
  instance, and ADR-0030 chose server-side sessions *specifically* for that; a cached session
  lookup reintroduces exactly the property the JWT was rejected for, invisibly, because on one
  instance it looks correct.
- **It would be a fifth process-local mechanism on the authoritative path.** ADR-0024 fails the
  build on process-local state, with `DISTRIBUTED_EXECUTION.md` §3 as the enforced exemption set —
  two `ThreadLocal`s, both explicitly non-authoritative. A persistence context could not be
  registered there with a straight face.
- `ddl-auto` is one property away from authoring schema Flyway owns and from creating columns
  `ColumnClassificationTest` never saw (ADR-0022).

**Rejected.**

### Option B — Spring Data JDBC
Pros: genuinely the closest alternative, and it was not dismissed lightly. No persistence context,
no lazy loading, no dirty checking; aggregate-oriented by design, which matches this platform's
aggregate boundaries; `@Version` optimistic locking with explicit semantics; far less boilerplate
than hand-written mappers.

Cons — two concrete behaviours, not general unease:

- **`save()` on an aggregate root deletes and re-inserts child collections.** Against superseded
  credentials and session rows those children *are* the history, so the default operation destroys
  what the domain requires and needs `DELETE` privilege on tables whose design withholds it.
- **Identifiers are minted in the application.** `IdGenerator` produces UUIDv7 before the row
  exists, so every new aggregate arrives with a non-null id and Spring Data JDBC defaults to
  `UPDATE` — the `Persistable.isNew()` trap, sitting precisely on the registration path, where the
  failure is a registration that silently updates nothing.

Both are workaroundable. Needing a workaround on the **first** aggregate is the signal.

**Both cons are the libraries' documented behaviour, not something measured here** — and that is
stated rather than glossed, because this repository has repeatedly found that an unverified claim
was wrong. They could not be probed: adding either dependency fails dependency verification and the
lockfile before any code runs (ADR-0025), which is that control working. If either behaviour is
ever found to differ, this ADR is wrong on a load-bearing point and should be revisited rather than
defended.

**Rejected.**

### Option C — Raw JDBC with hand-written `PreparedStatement` code
Pros: exactly what the kernel does today; total control; no abstraction to be surprised by.

Cons: positional parameter indices are a real and recurring source of defects, and every row mapper
repeats the same five lines. The objection is genuine and is answered by `JdbcClient` rather than
by an ORM.

**Rejected in favour of D, which is the same thing with the boilerplate removed.**

### Option D — Explicit SQL through `JdbcClient` (chosen)
Pros: every statement is written by a person, so nothing can emit one the privilege model forbids;
statement ordering, isolation, `lock_timeout`, savepoints, `ON CONFLICT`, advisory locks and
conditional updates are all directly expressible; composes with the kernel's `Connection`-passing
ports with zero impedance; no new dependency; no process-local state.

Cons: more code per aggregate than an ORM, and mapping is written rather than derived. Accepted
deliberately — the cost is proportional to the number of tables, which is bounded and known, while
the risks in Options A and B are not proportional to anything and are worst on the tables that
matter most.

## Consequences

**Positive:**
- Nothing emits a statement nobody wrote, which is what makes `DB-PRIVILEGE` enforcement mean
  something rather than merely be configured.
- Every concurrency protocol the kernel proved in Phase 0 remains expressible unchanged.
- `DISTRIBUTED_EXECUTION.md` §3 gains **no row**. Under 10 instances the behaviour is identical to
  today's kernel, which is already proven under 8- and 16-way contention.
- One mechanism from Phase 1 to Phase 14, so the ledger inherits a decision that was made with the
  ledger's constraints in view rather than one made for six identity tables.

**Negative:**
- More code. Each aggregate needs its repository, its SQL and its row mapping.
- Aggregate loading across parent and children is hand-written, including the `1 + N` question,
  which an ORM would at least have surfaced as a setting.
- The mapping layer is a place defects can live. Mitigated by keeping every constraint that can
  live in the database there, so a wrong mapping fails a constraint rather than writing a wrong row.

**Operational impact:** none. No new dependency, no configuration, no deployment change.

**Security impact:** positive, and it is the point. Least privilege is enforceable only if the
application cannot be made to issue a privileged statement by a mechanism acting on its own.
Parameter binding is explicit everywhere, so SQL injection has no ambient path.

**Financial impact:** none in Phase 1, which moves no money. The decision exists because Phase 3
does, and `INV-LED-03` is the same argument as `INV-HIST-03` with more at stake.

## Invariants / Constraints

Preserves, and is chosen to preserve: `INV-HIST-01`, `INV-HIST-03`, `INV-LED-03` (nothing emits an
unwritten `UPDATE`); `INV-EVT-01` (writers use the caller's connection); `INV-IDEM-01`,
`INV-IDEM-03`, `INV-IDEM-04` (insert timing, fingerprint comparison against committed state,
savepoint recovery); `INV-IDN-01`, `INV-IDN-02` (no accessor shape forced onto credential types;
algorithm and parameters as queryable columns per ADR-0032); `INV-IDN-03` (no cached session
lookup, per ADR-0030); `INV-BAL-05` (no unbounded-staleness projection on a decision path);
`INV-AUD-02` (`secretsAreWrapped` unaffected).

Depends on: ADR-0011 (Flyway owns schema), ADR-0014 (multi-instance), ADR-0022 (columns classified
before they hold data), ADR-0024 (no process-local state), ADR-0001 (one database, so one
transaction can span two modules).

## Follow-up

- **Phase 3** is the real test. If the ledger's aggregate loading proves genuinely unworkable by
  hand, that is a superseding ADR with measurement behind it, not a quiet exception.
- **Phase 14** decides the mechanism for reporting read models, which this ADR deliberately does
  not govern.
- A shared `EntityId` ↔ `uuid` mapping helper belongs with the first module that needs it
  (`P1-TSK-005`), not written speculatively here.
- The `1 + N` question for aggregate loading is answered per repository against a stated query, and
  no repository is written before its query is known.
