# ADR-0006 — Module boundaries are enforced mechanically

Status: Accepted

Date: 2026-08-31

## Context

ADR-0001 chose a modular monolith. Its entire value rests on the boundaries being real. A
monolith with documented-but-unenforced boundaries is a single-module monolith with extra
documentation, and it degrades into one within months — not through malice, but through a
sequence of individually reasonable shortcuts.

Physical service boundaries enforce themselves. In-process boundaries do not. Something must
replace that enforcement, or ADR-0001 is not a viable decision.

## Decision

**Module boundaries are enforced by mechanisms that fail the build, not by review.**

**Code boundaries:**
- One package root per module. Internals are not accessible across modules.
- A module exposes only a published interface (commands, queries) and integration events.
- No cross-module entity or ORM-relationship references — cross-context references are typed
  identifiers.
- The dependency graph is acyclic; direction is `app → business modules → platform →
  sharedkernel`.
- `sharedkernel` contains no business concept and no Spring or JPA dependency.
- ArchUnit tests enforce all of the above and **fail the build** on violation.

**Data boundaries:**
- Schema per module, in one PostgreSQL database.
- **No foreign keys across module schemas.** Cross-context referential integrity is a domain
  concern enforced at the boundary.
- One writer per table. Cross-module data is read through the owning module's API, never by
  querying its tables.
- **The one exception: `app`'s direct reads** *(added by `P15-REM-001`, 2026-11-15, after the
  Phase 15 coupling review below found it practised and unstated)*. The composition root owns no
  schema, and a read that belongs to no one context - a report across products, accounting's
  reads of the journal, credit's exposure census, risk's monitored ledger, the readiness read of
  every schema's applied version - may read module schemas directly, on one snapshot, **only
  through an adapter `MODULE_ARCHITECTURE.md` §6 "Data boundary" pins with the schemas it reads,
  and `SELECT` only**: no write, DDL or sequence advance; no row lock; and a function called there
  only if declared `STABLE` or `IMMUTABLE`. No business module may do the same, and none does.

**Additional enforced rules:**
- No `float`/`double` in monetary code paths (`INV-MON-01`).
- No direct broker publication from domain code — outbox only (`INV-EVT-01`).
- No `Instant.now()` / `LocalDate.now()` in domain code — injected `Clock` only.
- Only the `ledger` module writes journal entries (`INV-LED-04`).

**Verification:** each rule is proven by deliberately introducing a violation, confirming the
build fails, and reverting. A rule that has never been seen to fail is not known to work.

## Alternatives Considered

### Option A — Convention and code review
Pros: No tooling; maximum flexibility.
Cons: Reviewers miss things; urgency erodes standards; a boundary crossed once becomes
precedent. Empirically this is how modular monoliths become big-ball-of-mud monoliths.

### Option B — Java Platform Module System (JPMS)
Pros: Language-level enforcement; compile-time.
Cons: Awkward interaction with Spring Boot and classpath tooling; coarse-grained; high
friction for the value delivered at this stage.

### Option C — Separate Gradle modules with declared dependencies
Pros: Compile-time enforcement of the dependency graph; genuinely prevents reverse
dependencies.
Cons: Enforces direction but not internal encapsulation — a public class in a dependency is
reachable regardless. Build-configuration overhead per module.

### Option D — ArchUnit tests plus Gradle module structure (chosen)
Pros: Gradle handles coarse layering (`app`/`platform`/`sharedkernel`); ArchUnit handles
fine-grained rules Gradle cannot express — internals encapsulation, no cross-module entity
references, no floating-point money, ledger write exclusivity. Rules are readable, live with
the code, and produce a clear failure message. Extending the rule set is cheap.
Cons: Enforcement is at test time rather than compile time. Rules must be maintained. Overly
strict rules early cause churn.

### Option E — Separate databases per module
Pros: Absolute data isolation.
Cons: Destroys the atomicity that justified ADR-0001. Rejected as self-defeating.

## Consequences

Positive:
- Boundary violations are caught in CI, immediately and unambiguously.
- Schema-per-module keeps ownership visible in the data layer.
- Extraction to a service remains genuinely feasible, because the boundary was real.

Negative:
- Cross-module data access is more verbose than a join. This is the intended cost.
- No cross-schema foreign keys means some integrity is enforced only in the domain, and must
  therefore be tested.
- Rules require maintenance as the module set grows.

Operational impact: CI runs architecture tests on every change.

Security impact: Module boundaries double as data-classification boundaries — restricted
data does not reach modules that do not need it.

Financial impact: `INV-LED-04` (ledger is the sole posting writer) is mechanically enforced
rather than trusted.

## Invariants / Constraints

`INV-LED-04`, `INV-MON-01`, `INV-EVT-01`, `INV-EVT-02`, `INV-BAL-01`.

## Follow-up

- Phase 0: implement rules; prove each by deliberate violation.
- Each phase entry gate: add rules for the new module's boundaries.
- Phase 15: review actual coupling observed in implementation against the declared design.

*(Done by the Phase 15 exit review, `P15-DOC-001`, 2026-11-12, [`reviews/PHASE_15_REVIEW.md`](../project/reviews/PHASE_15_REVIEW.md)
§ADR-0006's coupling review.)* **The code's dependency graph is the declared one.** Every module's `build.gradle.kts` edges were
read: no cycle; the direction `app → business modules → platform → sharedkernel` holds, the one skip-level edge
(`creditmath → sharedkernel`) declared; the twelve business-to-business edges are ten modules to `ledger` and `lending` and
`bnpl` to `creditmath`, each declared in `MODULE_ARCHITECTURE.md` and pinned by its module's isolation test. **No foreign key
crosses a schema** (every cross-schema `REFERENCES` in a migration is inside a comment), and no business module's SQL names
another module's schema. Four things the decision says are not what the build holds:
1. **Two of the rules guard nothing.** The internals rule reads `.internal` packages, and no module has one: every module is
   a flat package of public types, so "internals are not accessible across modules" rests on review. The entity rule reads
   JPA entities, and there are none (ADR-0033). Both pass over empty data.
2. **`INV-LED-04`'s static half is the dependency direction alone.** Nothing stops a module that depends on `ledger` calling
   the journal store's `append` outside the ledger's command API; the tree holds no such call today.
3. **`app` reads thirteen module schemas directly**, `SELECT` only, through port adapters each declared where it was built
   (ADR-0107, ADR-0113, `MODULE_ARCHITECTURE.md`); the data boundary above states no such exception, and nothing pins which
   adapter reads which schema.
4. **The no-cross-schema-foreign-key rule has no global guard**, only per-migration checks.

Each is `BACKLOG.md` `P15-REM-001`, `PLANNED`, before any service extraction is decided (ADR-0001's revisit point, Phase 16).
This ADR's decision is unchanged.

*(Done by `P15-REM-001`, 2026-11-15; every rule below was seen to fail on a planted violation, in-suite, `MUTATION_TESTING.md` §2.)*
1. **The internals rule reads the code.** `moduleInternalsArePrivateToTheirModule` keeps the internal package and adds what a
   module's private state is: a business module's class that executes SQL, found from the bytecode. No business module names
   another's - published service or not - and `app` names a store adapter (an SQL class implementing a module's port) only in its
   `@Configuration` wiring. It found two reaches, `CheckoutService` and `CounterpartyChartGuard`, each building another module's
   adapters itself; both now take the ports from their wiring. **The entity rule is retired**: there is no mapper to have an
   entity (ADR-0033), and a store crosses as its port, its adapter held by the internals rule.
2. **`onlyTheLedgerAppendsToTheJournal`**: no class outside `com.finapp.ledger` calls `JournalEntryStore.append`, by a call or
   a method reference, on the port or an implementation.
3. **The exception is in the decision above**, and `SchemaReadsArePinnedTest` holds it: `app`'s twenty-eight adapters and the
   schemas each reads (fifty-one pairs over thirteen schemas) equal to `MODULE_ARCHITECTURE.md` §6's table both ways, `SELECT`
   only, the three functions it calls each `STABLE`; and every module's SQL naming its own schema only.
4. **`NoForeignKeyCrossesASchemaTest`** reads every `REFERENCES` in every migration of every module - 467 of them, dollar-quoted
   bodies included - and refuses one that names another module's schema, or none.

