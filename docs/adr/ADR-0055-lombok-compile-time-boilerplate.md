# ADR-0055 — Lombok is the project-standard boilerplate reducer, at compile time only

Status: Proposed
Date: 2026-09-23
Phase: 6 (cross-cutting; no phase deliverable depends on it)
Context: Build · every Java module
Supersedes: nothing. Adopted on the project owner's direction; `java-spring.md`'s "no annotation
without a clear reason" now names this as its exception.

## Context

The codebase already avoids most boilerplate: value types are records (93 of them), and the
rest is explicit Java. What remains repetitive is mechanical. There are 137 constructors that
only assign their final fields, most null-checking each dependency with `Objects.requireNonNull`,
22 hand-declared SLF4J loggers, and 31 enums whose constructors only assign. The owner asked for
Lombok as the standard, with focused annotations and without weakening anything the explicit code
protects.

Two properties of this build shape how Lombok can enter it. Every artefact is checksum-verified
and version-locked (ADR-0025). The build compiles with `-Xlint:all -Werror`, so anything Lombok
reports as a warning fails the build.

## Decision

1. **Compile time only.** `compileOnly` + `annotationProcessor`, plus the test and test-fixture
   equivalents, wired once in `finapp.java-conventions` from a catalog pin. Lombok is on no runtime
   classpath and in no jar, and the lockfiles show that directly: it appears only in the
   `compileClasspath` and `annotationProcessor` configurations. The CycloneDX SBOM does list it,
   as a build-time component, because that SBOM covers every resolved configuration
   (`app/build.gradle.kts`). *(This decision first said "in no SBOM"; corrected when
   `X-TSK-001`'s final check read the SBOM.)*
2. **One version, aligned with the BOM.** Pinned in `gradle/libs.versions.toml` at exactly what
   Spring Boot 4.1.1 manages (1.18.46), for the JUnit and AssertJ reason: `sharedkernel` applies no
   Spring BOM, and one version must serve every module.
3. **The rule is `.claude/rules/java-lombok.md`, and `lombok.config` is its compiler-enforced
   half.**
   - Refused as compile errors: `@Data`, `@SneakyThrows`, `@Synchronized` (JVM-local locking),
     `val`/`var`, `@Cleanup`, experimental features and every non-SLF4J logger.
   - A generated `toString` shows no field unless that field is explicitly included.
   - `@NonNull` throws what `Objects.requireNonNull` throws.
4. **Existing code is converted in module batches, and only where the result is
   byte-equivalent** (`X-TSK-001`, [`CROSS-CUTTING-LOMBOK-REFACTOR.md`](../project/tasks/CROSS-CUTTING-LOMBOK-REFACTOR.md)).
   The conversion covers 152 production classes:
   - `@Slf4j` replaces the 22 loggers;
   - `@RequiredArgsConstructor` replaces 104 assign-only constructors on services, commands,
     stores, controllers and composition classes, and 28 contract-enum constructors;
   - fields the old constructor null-checked become `@NonNull`.

   Each batch must pass a `javap` equivalence gate before it is committed.
   - **Not converted (446 classes, each with a stated reason):** private constructors on
     aggregates, value objects, secrets and financial entities, which are part of how their
     invariants are kept; every hand-written `equals`/`hashCode`/`toString`, which carry identity
     equality or redaction; records; exceptions; fluent accessors, which `@Getter` would rename;
     and `Money`.

## Alternatives considered

### Keep explicit Java
Pros: nothing generated; what you read is what runs.
Cons: the owner chose otherwise, and the repetition is real (about 800 lines of constructors
that say nothing).

### Adopt Lombok without a configuration
Pros: less to maintain.
Cons: `@Data`, all-field `toString` and `@Synchronized` would each be one annotation away. In a
codebase holding PANs, secrets and KYC evidence, the safe defaults have to be mechanical.

## Consequences

Positive:
- Hundreds of lines of constructors and logger declarations that say nothing disappear, with each
  batch proved by bytecode comparison rather than argued. The method was proved first on a trial
  conversion of 123 classes:
  - 0 bytecode differences;
  - every constructor with the same access, signature and parameter-to-field mapping;
  - 356 null checks before and 356 after;
  - about 800 lines removed.

  The trial was withdrawn so that the batches proceed under review.
- The unsafe Lombok features are unavailable rather than discouraged.

Negative:
- Field order in a `@RequiredArgsConstructor` class is now its constructor signature.
  Reordering two same-typed dependencies swaps them silently, which is stated in the rule.
- A `@NonNull` failure message reads "x is marked non-null but is null" rather than "x must not
  be null". Same exception, same moment; no test asserted the old text.
- IDEs need annotation processing enabled (IntelliJ bundles Lombok support).
- Lombok declares generated constructor parameters `final`. It shows only as a flag in the
  `MethodParameters` attribute of classes compiled with `-parameters` (`app`). Parameter names
  do not change, and nothing reads the flag.
- A constructor whose parameter names select a bean cannot be generated: Spring autowires by
  parameter name among same-typed beans, and Lombok names each parameter after its field
  (`java-lombok.md`).

Operational impact: none at run time; nothing Lombok-owned ships.
Security impact: none; the conversion generates no `toString`, `equals` or accessor.
Financial impact: none; no domain type, invariant or persistence path changes.

## Implementation

`X-TSK-001` applied decision 4 on 2026-09-23 in Batches 1–9 (`4151eb5` … `8cfdcc3`):
- 152 classes, 0 bytecode differences, 428 null checks before and after, and constructor
  parameter names unchanged;
- production code 807 lines added and 1,663 removed;
- final run: 1,457 hermetic (0 failures), 952 database (1 failure, the pre-existing
  `OperationalChartDatabaseTest`, same message) and 14 Kafka tests (0 failures).

The status stays `Proposed`. In this register a review accepts an ADR once implementation has
validated it (README), and that call is the owner's.

## Invariants / constraints

- Lombok never enters a runtime classpath (checked in the lockfiles).
- No generated `toString` on a class holding sensitive data without an explicit include.
- Aggregates keep explicit construction; `Money` keeps its hand-written semantics.

## Follow-up

- On every Spring Boot upgrade, re-check the Lombok pin against the BOM (the catalog note says so).
- If an ORM ever arrives (unresolved question 12), its entities need their own Lombok rules:
  no-argument constructors, no generated equality over associations. They belong beside
  `java-lombok.md`'s persistence rule.
