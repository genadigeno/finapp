---
paths:
  - "**/*.java"
  - "**/*.gradle.kts"
  - "lombok.config"
  - "gradle/libs.versions.toml"
---

# Lombok Rules

Lombok is the project standard for repetitive Java boilerplate: prefer it wherever it removes code
that says nothing. **Domain invariants take priority over boilerplate reduction**, and Lombok must
never hide important domain behaviour.

**Use, one focused annotation at a time (never blanket `@Data`):**
- `@RequiredArgsConstructor` for constructor injection when the constructor only assigns its final
  fields. A field the constructor null-checked is `@NonNull` (same `NullPointerException`, at
  construction). A package-private constructor stays so: `access = AccessLevel.PACKAGE`.
- `@Slf4j` for every logger. It is the only Lombok logger the build accepts.
- `@Getter`/`@Setter` selectively, per field, where the class really exposes that accessor. This
  codebase uses fluent accessors (`id()`), which `@Getter` would rename, so keep them explicit.
- `@Builder` where construction has many optional parts, going through a constructor or record so
  validation still runs.
- `@Value` for an immutable value object that cannot be a record. **Records come first**: a record
  is Java's own `@Value`.
- Other constructor annotations only where a framework needs them. Never `@NoArgsConstructor(force =
  true)` without a persistence review.

**Never:**
- **Replace a domain method with a setter.** `authorize()`, `capture()`, `refund()`, `revoke()`,
  `activate()`, `suspend()` and every other transition enforce rules. No `@Setter` on aggregates or
  entities, and no Lombok on an aggregate's private constructor or invariant-enforcing factory.
- **Generate `equals`/`hashCode` without reviewing it.** Aggregates compare by identity, and
  all-field equality on mutable state is a defect. `Money` and other monetary and scale-aware types
  stay hand-written.
- **Let a generated `toString` expose secrets, credentials, tokens, API keys, payment data (PAN,
  bank details), TOTP seeds, document bytes, KYC evidence or sensitive PII.** `lombok.config` makes
  every field opt-in (`@ToString.Include`); include one only after deciding it is safe to log
  (`security.md`). A `Sensitive<>` field is safe because it redacts itself.
- **Change persistence or entity semantics accidentally.** No-argument constructors, identity,
  equality and mutability of anything persisted are decisions (this codebase has no ORM; ADR-0033).
- **Change an external contract.** Generated accessors change JSON; request, response, event and
  view types are records.
- **Add shared mutable state or hidden locking.** `@Synchronized` is refused, and correctness never
  rests on one JVM (`CLAUDE.md`).
- **Replace a constructor that validates, derives, copies defensively or documents its
  parameters.** Move parameter documentation onto the field, or keep the constructor.
- **Reorder the fields of a `@RequiredArgsConstructor` class casually.** Field order IS the
  constructor's parameter order, and two same-typed dependencies swap silently.

**Build:** Lombok is `compileOnly` + `annotationProcessor` (and the test and test-fixture
equivalents), wired once in `finapp.java-conventions` from the catalog version. Never declare it in
a module build file or at a runtime scope. `lombok.config` is the compiler-enforced half of this
rule: `@Data`, `@SneakyThrows`, `@Synchronized`, `val`/`var`, `@Cleanup`, experimental features and
every non-SLF4J logger are compile errors.

**Converting existing code** follows `docs/project/tasks/CROSS-CUTTING-LOMBOK-REFACTOR.md`: module
batches, with the classes classified DO NOT REFACTOR left alone. Each batch proves with `javap` that
constructors, fields, null checks and methods are unchanged.
