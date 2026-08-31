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

**`P0-TSK-006` — Define the context-to-module map**
Status: `READY` — not started.

Bounded context: architecture. No dependencies.

Scope: map each bounded context to a planned module with all eight boundary attributes.
Largely satisfied already by
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md); the task is to verify
completeness and close gaps, which is the precondition for `P0-TSK-007` (ArchUnit rules).

Full definition: [`BACKLOG.md`](BACKLOG.md) §P0-EPIC-02. DoD profile: `DOD-ARCH`.

### Just completed

**`P0-TSK-004` — CI pipeline** — `COMPLETE` (2026-08-31).

Four jobs in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), separated so a
red build names its own gate: `build`, `migrations`, `secret-scan`, `dependency-scan`.

| Acceptance criterion | Evidence |
|---|---|
| Pipeline green on a clean clone | Every job's commands executed locally against a clean tree; all four pass. **See the verification limit below.** |
| A boundary violation fails CI | Reverse dependency `sharedkernel -> platform` fails with a circular-dependency error; separately, adding Spring to `sharedkernel` fails `SharedKernelIsolationTest`. Both revert cleanly |
| A committed secret fails CI | A random AWS-shaped key committed in a **throwaway clone** produced `aws-access-token` and `generic-api-key` findings and a non-zero exit; the real repository's history was never rewritten |

Beyond the stated criteria, each scanner was proven to have teeth rather than merely to run:
the dependency scan flags `commons-collections:3.2.1` (CVE-2015-7501, CRITICAL) and exits
non-zero. The `migrations` job applies migrations to an empty database, re-applies to prove
idempotency, and runs `flywayValidate` — which automates `P0-TSK-005`'s "checksum drift fails
the build".

**Verification limit — this matters.** The repository has **no git remote**, so the workflow
has never executed on GitHub. "Pipeline green" is evidenced by running the identical
commands, images and digests locally, not by an observed CI run. The YAML is validated and
every action is SHA-pinned, but syntax-level CI behaviour (expressions, job orchestration,
runner environment) is unproven until the repository has a remote and one run completes.

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

Local infrastructure (2026-08-31), `P0-TSK-003`:
- `compose.yaml` with PostgreSQL 18.6, Kafka 4.3.1 (KRaft) and Redis 8.10.1, pinned,
  health-checked, on named volumes, bound to loopback only
- Image versions single-sourced in the version catalog, with a build task that fails on
  drift between `compose.yaml` and the catalog

Migration tooling (2026-08-31), `P0-TSK-005`:
- Flyway 12.4.0, pinned to the Spring Boot BOM version, applied to the `platform` module
- Forward-only migrations with module-owned schema history (ADR-0011)
- `V001` creates the `platform` schema, documents its ownership, and revokes `PUBLIC`
- Migration conventions documented, including irreversible financial migrations and the
  privilege model the DB-level invariants require

Continuous integration (2026-08-31), `P0-TSK-004`:
- Four gates on every change: build/tests/boundary checks, migrations against a real
  PostgreSQL, secret scan over full git history, dependency scan of a CycloneDX SBOM
- Every third-party action pinned to a commit SHA; both scanners pinned to image digests
- The Java version CI installs is read from the version catalog rather than duplicated
- The `migrations` job starts PostgreSQL from the project's own `compose.yaml`, so CI and a
  developer run the identical pinned image

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

None in progress. `P0-TSK-006` is the next task.

## Blockers

None.

`P0-TSK-004` (CI pipeline) was recorded as blocked. The 2026-08-31 task completion review
found the blocker was a defect in the backlog, not in the work: `P0-TSK-004` declared
dependencies on `P0-TSK-011` (Money persistence mapping) and `P0-TSK-036` (test taxonomy),
neither of which is required to run a build with its tests. Because both are scheduled after
several tasks carrying `DOD-BUILD`, whose "CI green" criterion they could therefore never
satisfy, the plan contained an unsatisfiable requirement. Dependencies corrected to
`P0-TSK-001, P0-TSK-002`; CI is now startable and closes the outstanding `DOD-BUILD` gap
across all four completed tasks.

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
scanning in AVG.

**Resolved for CI (`P0-TSK-004`):** this is specific to this machine. GitHub-hosted runners
perform no TLS interception, so the workflow needs no equivalent setting. If CI ever moves
to a self-hosted runner behind an intercepting proxy, that runner needs the same treatment —
in its own environment, never in the repository.

**Git Bash rewrites container paths.** Running a command inside a container with an absolute
path from Git Bash (MSYS) silently rewrites it:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh ...
  -> exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file
```

Prefix with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use only —
health checks and container entrypoints run inside Docker and are unaffected.

**`clean` fails with "Unable to delete directory".** On Windows an orphaned Gradle daemon
keeps module jars open, so `clean` cannot remove `build/`. It is leftover state, not a repo
defect. `./gradlew --stop` handles the usual case; a daemon whose `GRADLE_USER_HOME` has been
deleted survives that and must be killed by PID:

```
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'GradleDaemon' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**Resetting local infrastructure.** `docker compose down` keeps data; `docker compose down -v`
discards it. A reset is required after changing Kafka's `CLUSTER_ID`, or when moving to a new
PostgreSQL major version without running `pg_upgrade` — the volume is formatted for the major
version that created it.

---

## Partially Satisfied Definition of Done

Recorded so it is not mistaken for a completed criterion.

| Task | DoD item not yet met | Owning task |
|------|---------------------|-------------|
| `P0-TSK-001` — `P0-TSK-005` | `DOD-BUILD` requires "CI green". A pipeline now exists and all four jobs pass when run locally, but it has never executed on a CI runner because the repository has no git remote. This closes on the first successful run after a remote is added. | Adding a remote |
| `P0-TSK-004` | The CycloneDX SBOM covers the whole resolved dependency set, test scope included (21 of ~61 components). Plugin 3.4.1 exposes no configuration filter. Adequate for vulnerability scanning — test libraries execute on CI runners, so they are legitimately in scope — but it means a HIGH/CRITICAL advisory in a test-only library fails the build though nothing vulnerable ships, and **the SBOM must not be published as shipping provenance in this form** because it overstates what is deployed. | Phase 15 (supply chain and provenance) |
| `P0-TSK-004` | CI actions and scanner images are pinned by SHA/digest with no automated update path, so the pins will rot. | `P0-TSK-040` |
| `P0-TSK-002` | Boundary enforcement is partial: Gradle enforces dependency direction and classpath tests assert module isolation, but cross-module internals and entity references rest on review until ArchUnit lands. | `P0-TSK-007` |
| `P0-TSK-003`, `P0-TSK-005` | Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants (`INV-LED-03`, `INV-HIST-01`, `INV-HIST-03`) cannot yet be exercised. The migrator/application role split is designed and documented (`DATA_MIGRATIONS.md` §5) but not implemented, and must land **before** any table subject to those invariants is created. | `P0-TSK-022` |

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

**`P0-TSK-006` — Define the context-to-module map.**

Rationale: `P0-TSK-007` (ArchUnit boundary rules) depends on it, and `P0-TSK-007` is the
task that converts the module boundaries from documented-and-reviewed into
mechanically-enforced. That gap is currently the weakest part of the architecture story:
Gradle enforces dependency *direction*, but nothing yet prevents a cross-module internal
reference.

Much of `P0-TSK-006` is already done in `MODULE_ARCHITECTURE.md`. The work is to verify
every planned module records all eight boundary attributes and that no state has two owners.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) rule 4, work stays within that task —
no ArchUnit rules (`P0-TSK-007`), no `Money` type (`P0-TSK-009`).

---

## Change Log

| Date | Change |
|------|--------|
| 2026-08-31 | `P0-TSK-004` complete. CI with four gates: build/tests, migrations against real PostgreSQL, secret scan over full history, SBOM dependency scan. Actions SHA-pinned, scanners digest-pinned. Not yet executed on a runner — no git remote exists. |
| 2026-08-31 | Task completion review of `P0-TSK-001`, `-002`, `-003`, `-005`. No critical or financial findings — no money-handling code exists yet. Six important findings fixed: an unsatisfiable `DOD-BUILD` "CI green" requirement caused by over-specified `P0-TSK-004` dependencies; a one-directional infrastructure drift check that let an unpinned image pass (proven, then closed); dead Spring Boot configuration in `build-logic` (proven unnecessary); an unnecessary Spring test stack in `platform` contradicting its own comment; a name-substring scope test replaced with a structural one; ADR-0011 missing from `DECISIONS.md`. Java toolchain version moved into the version catalog, removing four duplicated copies of "21". |
| 2026-08-31 | `P0-TSK-005` complete. Flyway 12.4.0, forward-only, module-owned schema history; ADR-0011 and `DATA_MIGRATIONS.md` written. |
| 2026-08-31 | `P0-TSK-003` complete. Local infrastructure (PostgreSQL 18.6, Kafka 4.3.1 KRaft, Redis 8.10.1), pinned and health-checked, with a build-enforced version-drift check against the catalog. |
| 2026-08-31 | `P0-TSK-002` complete. `sharedkernel`, `platform`, `app` with enforced dependency direction; `sharedkernel` proven Spring-free; `java-library` adopted for `api`/`implementation` boundary control. |
| 2026-08-31 | `P0-TSK-001` complete. Gradle 9.7.1 multi-module build, Java 21 toolchain, Spring Boot 4.1.1 BOM, `build-logic` conventions, checksum-pinned wrapper. Phase 0 `IN_PROGRESS`. Repository placed under Git. |
| 2026-08-31 | Project initiation. Delivery plan, phase gates, backlog, architecture baseline, invariant catalog, Definition of Done, execution protocol and ADR-0001..0010 created. Phase 0 entry gate passed; status `READY`. |
