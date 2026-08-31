# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

Last updated: 2026-08-31

---

## Current Phase

**Phase 0 — Domain and Architecture Foundation**
Status: `IN_PROGRESS`

Entry gate passed on 2026-08-31. All twelve entry-gate criteria in
[`PHASE_GATES.md`](PHASE_GATES.md) §2 are satisfied: the delivery plan is written, bounded
contexts and module boundaries are defined, the invariant catalog exists, the backlog is
elaborated to task granularity, and ADR-0001 through ADR-0010 are recorded as `Proposed`.

Phase 0 delivers a buildable, boundary-enforced modular monolith containing the financial and
platform kernel, with **zero business capability**. That constraint is deliberate: money
representation, idempotency, outbox, audit and correlation cannot be retrofitted once
financial history exists.

## Current Milestone

**M0.1 — Buildable, boundary-enforced skeleton**
`P0-EPIC-01` (Build and Repository Foundation) and `P0-EPIC-02` (Module Architecture and
Boundary Enforcement).

Milestone complete when: a Gradle multi-module Spring Boot build is green in CI from a clean
clone; `platform`, `sharedkernel` and `app` modules exist with enforced dependency direction;
ArchUnit boundary rules fail the build on a deliberately introduced violation; PostgreSQL,
Kafka and Redis run locally with pinned versions matching the test infrastructure.

Subsequent Phase 0 milestones:
- **M0.2** Financial kernel — `P0-EPIC-03`, `P0-EPIC-04`
- **M0.3** Correctness primitives — `P0-EPIC-05`, `P0-EPIC-06`, `P0-EPIC-07`
- **M0.4** API, observability, security baseline — `P0-EPIC-08`, `-09`, `-10`
- **M0.5** Test infrastructure and phase review — `P0-EPIC-11`, `P0-EPIC-12`

## Current Task

**`P0-TSK-003` — Local infrastructure via Docker Compose**
Status: `READY` — not started.

Bounded context: platform / ops. Depends on `P0-TSK-001` (`COMPLETE`).

Scope: PostgreSQL, Kafka and Redis with pinned image versions, named volumes, health checks.

Acceptance: `docker compose up` yields all three healthy; versions match those used by
Testcontainers; no credentials committed beyond local-only development defaults clearly
marked as such.

Full definition: [`BACKLOG.md`](BACKLOG.md) §P0-EPIC-01. DoD profile: `DOD-BUILD`.

### Just completed

**`P0-TSK-002` — Create module skeleton** — `COMPLETE` (2026-08-31).

`sharedkernel`, `platform` and `app` exist with the direction
`app -> platform -> sharedkernel`. Both new modules contain a `package-info.java` recording
what may and may not enter them, and no other production code — `Money`, identifiers,
`Clock`, the envelope, idempotency, outbox and audit are later tasks.

| Acceptance criterion | Evidence |
|---|---|
| Modules build independently | `:sharedkernel:build`, `:platform:build`, `:app:build` each green in isolation |
| Reverse dependency fails compilation | Adding `sharedkernel -> platform` fails with a circular-dependency error on the compile task graph |
| `sharedkernel` has no Spring Framework dependency | `SharedKernelIsolationTest`: no Spring type loadable, no `spring-*` artefact on the classpath |

Test credibility demonstrated per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) rule 8:
adding Spring to `sharedkernel` failed both isolation tests; reverting restored them.

Decisions taken during the task, recorded in
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md):
- Every module applies `java-library`, so `api`/`implementation` becomes a boundary control
  rather than a build detail. `platform` exposes `sharedkernel` via `api` because kernel
  value types will appear in its own signatures.
- `sharedkernel` takes its test libraries from the version catalog rather than the Spring
  Boot BOM, pinned to exactly the versions Boot manages so the whole build runs one JUnit
  and one AssertJ. **This alignment must be re-checked on every Spring Boot upgrade** — the
  procedure is in `gradle/libs.versions.toml`.

---

## Completed Capabilities

**Business capabilities: none** — by design (Phase 0 §2 of the delivery plan).

Platform foundation (2026-08-31), `P0-TSK-001`:
- Gradle 9.7.1 wrapper; distribution **and** wrapper jar verified by SHA-256 against
  `services.gradle.org`
- Version catalog as the single source of dependency versions
- Java 21 toolchain pinned via a `build-logic` convention plugin, with the foojay resolver
  provisioning a JDK where the machine lacks one
- Spring Boot 4.1.1 BOM; the Boot plugin applied only to the executable module, so library
  modules can take version alignment without `bootJar` packaging
- Placeholder `app` module carrying no business capability
- Reproducible archives; `-Werror`; LF enforced on `gradlew` so Linux CI is not broken by a
  Windows checkout

Module skeleton (2026-08-31), `P0-TSK-002`:
- `sharedkernel`, `platform` and `app` with the direction `app -> platform -> sharedkernel`,
  enforced structurally by Gradle and asserted by classpath tests
- `sharedkernel` is framework-free and provably carries no Spring artefact
- `java-library` everywhere, making `api`/`implementation` a deliberate boundary control
- No production code in either new module beyond boundary documentation

Project initiation (2026-08-31):
- Master delivery plan for all seventeen phases — [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md)
- Phase gate model, status model and per-phase exit criteria — [`PHASE_GATES.md`](PHASE_GATES.md)
- Engineering backlog with Phase 0 elaborated to task granularity — [`BACKLOG.md`](BACKLOG.md)
- First architecture baseline — [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- Invariant catalog, 64 invariants — [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md)
- Definition of Done with task-type profiles — [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)
- Execution protocol — [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)
- ADR-0001 through ADR-0010 (`Proposed`) — [`docs/adr/`](../adr/README.md)
- Roadmap with sequencing rationale and seam register — [`ROADMAP.md`](../product/ROADMAP.md)

## Active Work

None in progress. `P0-TSK-003` is the next task to start.

## Blockers

None.

---

## Local Environment Prerequisites

Machine-specific setup that the repository deliberately does **not** contain. The build must
work on any machine without local edits (`DOD-BUILD`: "no developer-machine-specific
assumptions"), so anything below belongs in `GRADLE_USER_HOME`, never in the repo.

**TLS interception by antivirus (this development machine).** AVG "Web/Mail Shield"
intercepts HTTPS and re-signs it with its own root CA. Windows trusts that CA; the JDK's
bundled `cacerts` does not. Java tooling therefore fails with:

```
PKIX path building failed ... unable to find valid certification path to requested target
```

while `curl` and the browser work — which makes it look like a Gradle fault rather than a
TLS-trust one. Resolved in `~/.gradle/gradle.properties` (outside the repo):

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

That covers the Gradle daemon. Bootstrapping the distribution runs in a separate JVM that
reads `GRADLE_OPTS`, so on a machine with no Gradle distribution cached also export:

```
GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatives: import the AVG root into the JDK `cacerts` with `keytool`, or disable HTTPS
scanning in AVG. This must be revisited at `P0-TSK-004` — CI runners will need whatever the
equivalent is in that environment.

---

## Partially Satisfied Definition of Done

Recorded so it is not mistaken for a completed criterion.

| Task | DoD item not yet met | Owning task |
|------|---------------------|-------------|
| `P0-TSK-001` | `DOD-BUILD` requires "CI green". No CI pipeline exists yet, so the build is verified only locally — including from a clean clone with an empty Gradle home. | `P0-TSK-004` |

---

## Known Architectural Debt

None yet. Debt is recorded here as it is deliberately accepted, with: what was deferred, why,
what risk it carries, what triggers paying it down, and the owning phase.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Architectural Debt,
**financial-correctness debt is never accepted** — an invariant is either protected or the
work is not done.

Note: items in [`DECISIONS.md`](DECISIONS.md) §Deliberately Deferred are scoping decisions,
not debt.

---

## Unresolved Architectural Questions

Ordered by when they must be answered. Each requires an ADR before the work that depends on
it begins.

| # | Question | Must resolve by | Risk if unresolved |
|---|----------|-----------------|--------------------|
| 1 | Isolation level and locking strategy for concurrent postings | Phase 3 | **High** — lost updates or double spend under contention (`INV-CON-01`) |
| 2 | Chart-of-accounts structure and its relationship to the Phase 14 GL | Phase 3 | High — a narrow structure forces retroactive remapping, breaking `INV-ACC-04` |
| 3 | Balance projection placement (ledger schema vs separate read store) | Phase 3 | Medium — affects contention and rebuild cost (ADR-0009) |
| 4 | Whether `accounts` and `wallet` are one module or two | Phase 3 | Medium — splitting later is cheap; merging authoritative state later is not |
| 5 | Transfer/ledger transaction boundary and compensation strategy | Phase 4 | High — determines whether a saga is ever needed internally |
| 6 | Accounting treatment of authorization (memo/hold) vs capture (posting) | Phase 5 | High — misstates available funds if wrong |
| 7 | Whether `checkout` is its own module or part of `merchant` | Phase 6 | Low |
| 8 | Fee model: who pays, when recognised, gross vs net settlement | Phase 6 | High — changing revenue recognition after postings exist is a restatement |
| 9 | Which payment rail to simulate first, and its finality semantics | Phase 5 | Medium — first rail shapes the abstraction (mitigated by designing to `PAYMENT_LIFECYCLES.md`) |
| 10 | Which jurisdiction-neutral compliance abstractions belong in the MVP | Phase 2 | Medium |
| 11 | Fail-safe policy for risk evaluation: block or allow on unavailability | Phase 13 | High — a wrong default is either an outage or an open door |

Resolved during initiation:
- ~~Which modules form the initial modular-monolith cut?~~ → [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- ~~Deployment topology?~~ → ADR-0001
- ~~Money representation?~~ → ADR-0003
- ~~Idempotency mechanism?~~ → ADR-0004
- ~~Reliable event publication?~~ → ADR-0005
- ~~Is balance authoritative or derived?~~ → ADR-0009

---

## Next Task

**`P0-TSK-003` — Local infrastructure via Docker Compose.**

Rationale: `P0-TSK-005` (migrations) and `P0-TSK-035` (Testcontainers) both need real
infrastructure, and the versions used locally must match the versions used in tests — a
schema or broker behaviour that differs between the two is a defect the test suite cannot
see. It has no unresolved architectural question and Docker 28.0.1 is already present.

Note that `P0-TSK-004` (CI) is blocked on more than this task: it also depends on
`P0-TSK-011` and `P0-TSK-036`, and it must solve the TLS-interception problem recorded under
Local Environment Prerequisites for whatever runner it uses.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) rule 4, work stays within that task —
containers and health checks only. No schema (`P0-TSK-005`), no Testcontainers harness
(`P0-TSK-035`).

---

## Change Log

| Date | Change |
|------|--------|
| 2026-08-31 | `P0-TSK-002` complete. `sharedkernel`, `platform`, `app` with enforced dependency direction; `sharedkernel` proven Spring-free; `java-library` adopted for `api`/`implementation` boundary control. |
| 2026-08-31 | `P0-TSK-001` complete. Gradle 9.7.1 multi-module build, Java 21 toolchain, Spring Boot 4.1.1 BOM, `build-logic` conventions, checksum-pinned wrapper. Phase 0 `IN_PROGRESS`. Repository placed under Git. |
| 2026-08-31 | Project initiation. Delivery plan, phase gates, backlog, architecture baseline, invariant catalog, Definition of Done, execution protocol and ADR-0001..0010 created. Phase 0 entry gate passed; status `READY`. |
