---
paths:
  - "**/*.java"
  - "**/*.gradle"
  - "**/*.gradle.kts"
  - "lombok.config"
  - "gradle/libs.versions.toml"
---

# Java Lombok Standard

Lombok is the project standard for repetitive Java boilerplate. For new Java code, consider it
first: manual boilerplate needs a reason when a suitable Lombok alternative exists. The reason may
be a domain invariant, security, persistence semantics, a framework requirement, generated
behaviour that would be wrong, readability or maintainability. **Domain invariants take priority
over boilerplate reduction**, Lombok must never hide domain behaviour, and it is never added merely
to cut annotation count: use the annotation that best expresses the intended semantics.

**Use, one focused annotation at a time:**
- `@RequiredArgsConstructor` for constructor injection when the constructor only assigns its final
  fields. A field the constructor null-checked is `@NonNull` (same `NullPointerException`, at
  construction). A package-private constructor stays so: `access = AccessLevel.PACKAGE`. Never
  field injection to avoid writing a constructor.
- `@Slf4j` for every logger. It is the only Lombok logger the build accepts.
- `@Getter`/`@Setter` selectively, per field, where the class really exposes that accessor. This
  codebase uses fluent accessors (`id()`), which `@Getter` would rename, so keep them explicit. No
  setter just because a field is mutable internally, and none that could admit invalid state.
- `@Builder` where construction has many optional parts (DTOs and API models, which are records
  here; test data; immutable configuration; complex value objects), going through a constructor or
  record so validation still runs. Never as a stand-in for a domain factory whose construction has
  rules.
- `@Value` for an immutable value object that cannot be a record. **Records come first**: a record
  is Java's own `@Value`. Not where a framework or the domain lifecycle needs mutability.
- `@EqualsAndHashCode` and `@ToString` only after reviewing what they generate (below).
- `@NoArgsConstructor` and `@AllArgsConstructor` only where a framework, persistence or
  serialization genuinely needs them. Never `@NoArgsConstructor(force = true)` without a
  persistence review, or where it would create invalid domain state.

**Refused by the build.** `lombok.config` makes these compile errors, not guidelines:
- `@Data`. It bundles `@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@ToString` and
  `@EqualsAndHashCode`, and so creates mutable APIs, equality and logging behaviour nobody chose
  for a domain or financial type. Compose the focused annotations instead.
- Every `lombok.experimental` feature, `@SuperBuilder` included; `@SneakyThrows`; `@Synchronized`;
  `val`/`var`; `@Cleanup`; `onX`; and every logger except `@Slf4j`.

Adopting any of them means changing `lombok.config` and ADR-0055 first.

**Never:**
- **Replace a domain method with a setter.** `authorize()`, `capture()`, `refund()`, `revoke()`,
  `activate()`, `suspend()` and every other transition enforce rules. No `@Setter` on aggregates or
  entities, and no Lombok on an aggregate's private constructor or invariant-enforcing factory.
- **Generate `equals`/`hashCode` without reviewing it.** Aggregates, entities and other
  identity-based objects compare by identity, and all-field equality over mutable state breaks
  collections and persistence. Generated equality suits a value object only when every field is
  part of its value. `Money` and other monetary and scale-aware types stay hand-written.
- **Let a generated `toString` expose secrets, passwords, credentials, tokens, API keys, payment
  data (PAN, bank details), TOTP seeds, document bytes, KYC/KYB evidence or other sensitive PII.**
  `lombok.config` makes every field opt-in (`@ToString.Include`); include one only after deciding
  it is safe to log (`security.md`). A `Sensitive<>` field is safe because it redacts itself. None
  of these ever reaches a log statement either.
- **Hide a financial invariant.** Ledger and journal entries and lines, accounts, balances,
  payments, transfers, loans, credit decisions and reconciliation records keep explicit domain
  behaviour wherever it protects an invariant. Boilerplate reduction is subordinate to financial
  correctness.
- **Replace validation.** Generated constructors, builders and accessors never stand in for
  required validation; business validation stays explicit.
- **Change persistence or entity semantics accidentally.** No-argument constructors, identity,
  equality, lifecycle, proxies and mutability of anything persisted are decisions (this codebase
  has no ORM; ADR-0033). Where a framework needs Lombok support, use the minimum.
- **Change an external contract.** Generated accessors change JSON; request, response, event and
  view types are records.
- **Add shared mutable state or hidden locking.** `@Synchronized` is refused, and correctness never
  rests on one JVM (`CLAUDE.md`).
- **Replace a constructor that validates, derives, copies defensively or documents its
  parameters.** Move parameter documentation onto the field, or keep the constructor.
- **Replace a constructor whose parameter names select a bean.** Several beans can share a type
  (16 `TransactionTemplate`s, and no `@Qualifier`), so Spring autowires by parameter name. Lombok
  names each parameter after its field, which silently changes the bean chosen or fails the
  context.
- **Reorder the fields of a `@RequiredArgsConstructor` class casually.** Field order IS the
  constructor's parameter order, and two same-typed dependencies swap silently.

**Reviewing Java code:** flag manual boilerplate that Lombok could replace safely, and Lombok usage
that should go because it hides domain behaviour, leaks sensitive data, or creates unsafe equality
or mutability.

**Build:** Lombok is `compileOnly` + `annotationProcessor` (and the test and test-fixture
equivalents), wired once in `finapp.java-conventions` from the catalog version. Never declare it in
a module build file or at a runtime scope. `lombok.config` is the compiler-enforced half of this
standard.

**Converting existing code** follows `docs/project/tasks/CROSS-CUTTING-LOMBOK-REFACTOR.md`: module
batches, with the classes classified DO NOT REFACTOR left alone. Each batch proves with `javap` that
constructors, fields, null checks, methods and constructor parameter names are unchanged.
