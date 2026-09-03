# ADR-0013 — Aggregate identifiers are typed and time-ordered (UUIDv7)

Status: Accepted

Date: 2026-09-01

## Context

Every aggregate in the platform needs an identifier, and the choice is made once: identifiers
become primary keys, foreign references, event payload fields, API contract fields and audit
targets. Changing the scheme after financial history exists means rewriting immutable records,
which `ADR-0011` and `INV-HIST-01` exist to prevent.

Two independent problems have to be solved, and conflating them produces a bad answer to both.

**1. Type confusion.** A bare `UUID` parameter accepts every identifier in the system.
`postToAccount(UUID)` accepts a customer identifier, a transfer identifier, or a correlation
identifier. The compiler cannot help, review rarely catches an argument-order mistake between
two same-typed parameters, and the defect surfaces as a posting against the wrong entity —
found at reconciliation, if at all.

**2. Index locality.** A random primary key writes to a uniformly random position in the index
on every insert. The index's hot set becomes the whole index rather than its rightmost pages,
producing page splits, cache misses and bloat that worsen as the table grows. A ledger is an
append-heavy table that is never deleted from; it is the worst possible place for a random key.

A third consideration constrains the answer: identifiers appear in URLs, logs and support
tools. A guessable identifier is an enumeration primitive.

## Decision

**Aggregate identifiers are typed subclasses of `EntityId`, carrying a UUIDv7 value.**

**Typing:**
- Each aggregate declares its own identifier type extending `EntityId` — `CustomerId` in
  `party`, `AccountId` in `accounts`, `JournalEntryId` in `ledger`.
- Passing one where another is required is a **compile error**.
- Identity includes the concrete type: two identifiers of different kinds are never equal even
  when carrying the same value. `equals`/`hashCode` are `final` so no subclass can relax this.
- `EntityId` lives in `sharedkernel`; the per-aggregate types do **not**. The shared kernel
  holds the mechanism, never the business nouns (`MODULE_ARCHITECTURE.md` §2).

**Values:**
- UUIDv7 (RFC 9562 §5.7): 48 bits of Unix milliseconds, 4 version bits, a 12-bit counter, 2
  variant bits, 62 bits of randomness.
- The 12-bit field is a **counter, not randomness**, so identifiers minted in the same
  millisecond still sort in issue order.
- When the counter is exhausted within a millisecond the generator **borrows the next
  millisecond** rather than wrapping.
- A **backwards clock never produces a backwards identifier**. NTP correction and leap-second
  smearing move wall-clock time backwards; the generator treats a regressed clock as the
  current millisecond and lets the counter carry the ordering.
- The 62 random bits come from `SecureRandom` in production.
- `EntityId` **rejects any value that is not a UUIDv7**, so `createdAt()` cannot return a time
  fabricated from randomness.

**Time and randomness are constructor arguments,** never read ambiently, so generation is
reproducible under test and `P0-TSK-013`'s no-ambient-clock rule has nothing to forbid here.

**Storage** is PostgreSQL's native `uuid` type — 16 bytes, ordered by unsigned bytes, which is
the ordering the generator produces.

## Alternatives Considered

### Option A — `bigserial` / database sequence
Pros: Smallest key (8 bytes); perfect index locality; trivially ordered.
Cons: The identifier does not exist until the row is inserted, so it cannot be created in the
domain, put in an event, or returned from a command that has not yet committed. It leaks
business volume (`/accounts/1041` tells a competitor how many accounts exist). It collides on
merge across environments, and makes any future extraction of a module into its own database a
re-keying exercise. Rejected primarily on the first point: the outbox pattern (`ADR-0005`)
requires the identifier to exist before the transaction commits.

### Option B — UUIDv4 (random)
Pros: Trivial; `UUID.randomUUID()`; unguessable; no clock dependency.
Cons: The index-locality problem in full. At ledger volume this is the difference between an
append-only write pattern and a random one. Also gives no natural ordering for pagination or
diagnosis.

### Option C — ULID
Pros: Time-ordered like UUIDv7; a compact 26-character Crockford base-32 text form.
Cons: Not a UUID, so PostgreSQL's `uuid` type does not apply — storage becomes `bytea` or
`char(26)`, losing native indexing and comparison. Requires a third-party library in
`sharedkernel`, which is meant to be dependency-free. UUIDv7 provides the same ordering
property inside a standard, natively-supported type. Rejected as strictly worse here, not as a
bad format.

### Option D — UUIDv7 with typed identifiers (chosen)
Pros: Time-ordered, so index locality is preserved. Native `uuid` storage. Generated in the
domain before commit. Unguessable. Standardised in RFC 9562. Typing removes an entire class of
argument-substitution defect at compile time.
Cons: 16 bytes rather than 8. Leaks creation time (see below). Requires a generator with real
monotonicity logic rather than a one-line call. One small class per aggregate.

### Option E — a generic `Id<T>` phantom type
Pros: No class per aggregate.
Cons: Erasure. `Id<Customer>` and `Id<Account>` are the same class at run time, so a
`Set<Id<?>>` conflates them, deserialisation cannot reconstruct the parameter, and a raw `Id`
defeats the check entirely. It buys brevity by making the guarantee compile-time-only, and the
run-time half is the half that matters for equality and map keys.

## Consequences

Positive:
- Argument substitution between identifier kinds is a compile error.
- Insert-heavy tables keep their index hot set at the right-hand edge.
- Identifiers sort by creation time, which makes diagnosis and keyset pagination natural.
- Every identifier carries its own minting time, useful in support and forensics.

Negative:
- 16 bytes per key rather than 8, on every row and every foreign reference.
- One small class per aggregate identifier.
- Under sustained load above 4096 identifiers per millisecond the generator borrows from the
  future, so identifier time can run slightly ahead of wall-clock time. This is deliberate: an
  ordering guarantee is worth more than an exact timestamp, and `createdAt()` is documented as
  a minting time rather than a business timestamp.

Operational impact:
- Identifier timestamps are millisecond-precision and derived from the generating host's clock.
  They are a diagnostic aid, not an audit time; `INV-AUD-01` audit records carry their own
  timestamp from an injected clock.

Security impact:
- **A UUIDv7 discloses its creation time to anyone holding it.** This is inherent to the
  format, and it is the same property that provides index locality. It is acceptable because
  the 62 random bits keep identifiers unguessable, so identifiers cannot be enumerated. It is
  **not** acceptable for an entity whose existence time is itself sensitive; such a case needs
  a different scheme and a superseding decision, not an exception.
- An identifier is never a secret and never a capability. Authorisation is always an explicit
  check (`INV-AUD-03`), never "knows the identifier".

Financial impact:
- None directly. Indirectly, index locality is what keeps posting throughput from degrading as
  the ledger grows, and typed identifiers remove a class of defect that would otherwise post
  against the wrong entity.

## Invariants / Constraints

- `INV-HIST-01` — identifiers become part of immutable history, so the scheme must not change
  after postings exist. This ADR is therefore made before any table exists.
- `INV-AUD-02` — identifiers appear in logs and events; they must carry no PII. A UUIDv7
  carries a timestamp and randomness only.
- `INV-AUD-03` — holding an identifier confers no authority.
- `P0-TSK-013` — the generator takes an injected `Clock`; it must never read ambient time.

## Follow-up

- `P0-TSK-013` introduces the platform-wide `Clock` injection and the architecture rule
  forbidding ambient `now()`. The generator is already written to it.
- Persistence mapping for `EntityId` (native `uuid` column) belongs with the data-access
  decision still open as unresolved question 12, due Phase 3.
- If an aggregate is ever found whose creation time is sensitive, that needs a superseding ADR
  rather than a local exception.
- Revisit the 4096-per-millisecond ceiling if sustained issue rates ever approach it; the
  current behaviour is correct but would drift identifier time from wall-clock time.
