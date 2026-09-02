# System Architecture

## Baseline Stack

Backend:
- Java
- Spring Boot
- Spring Security
- Gradle

Transactional:
- PostgreSQL

Caching / ephemeral:
- Redis

Messaging:
- Kafka

Object storage:
- S3-compatible storage

Observability:
- OpenTelemetry
- Prometheus
- Grafana
- centralized structured logging
- distributed tracing

Infrastructure:
- Docker
- Kubernetes
- Terraform

Testing:
- JUnit
- AssertJ
- Testcontainers
- WireMock
- Gatling or k6

API:
- REST / OpenAPI
- gRPC where justified
- signed/idempotent webhooks

## Pinned Versions

Versions are pinned in `gradle/libs.versions.toml` (the single source of truth) and in
`gradle/wrapper/gradle-wrapper.properties`. No version literal appears in a build script.

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 (LTS) | Pinned by Gradle toolchain, not by ambient `JAVA_HOME` |
| Gradle | 9.7.1 | Distribution and wrapper jar verified by SHA-256 |
| Spring Boot | 4.1.1 | Brings Spring Framework 7 and Jackson 3 |
| PostgreSQL | 18.6 | Debian-based image, ICU collation provider |
| Kafka | 4.3.1 | KRaft mode, no ZooKeeper |
| Redis | 8.10.1 | AOF persistence enabled locally |
| Flyway | 12.4.0 | Matches the Spring Boot BOM; forward-only (ADR-0011) |
| PostgreSQL JDBC | 42.7.13 | Matches the Spring Boot BOM |
| CycloneDX Gradle plugin | 3.4.1 | Produces the SBOM the dependency scan consumes |
| gitleaks | v8.30.1 | Secret scanning; pinned by image digest in `infra/scripts/secret-scan.sh`, which CI calls |
| Trivy | 0.74.0 | Dependency vulnerability scanning; pinned by image digest in CI |

Infrastructure versions appear in both `compose.yaml` and the version catalog. The
`verifyInfrastructureVersions` build task fails if they drift, because local infrastructure
and test infrastructure being different software is a defect the test suite cannot see.

**Why the current major lines rather than the previous ones.** Spring Boot 3.5.x and Gradle
8.x were both viable and, at the time of the decision, more widely deployed. They were
rejected because this platform spans seventeen phases: beginning on a superseded major
guarantees a framework migration during Phases 3-5, which are the phases that establish the
ledger, transfers and payments. A migration is cheapest when there is no financial history
and no business capability to regress — that is now, in Phase 0, or it is later at
materially higher risk.

Upgrades are deliberate acts: change the catalog entry, run the build, and record anything
that breaks. Gradle upgrades must also update `distributionSha256Sum` from
`services.gradle.org` — never from a mirror or a search result.

## Continuous Integration

`CLAUDE.md` and the Definition of Done depend on checks actually running. A gate that is not
automated is a gate that will be skipped, so every gate the DoD relies on runs on every
change in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), as four separate
jobs so a failure names its own gate:

| Job | Gate |
|-----|------|
| `build` | Compilation, unit tests, module boundary tests, infrastructure version drift, Gradle wrapper checksum validation |
| `migrations` | Migrations apply to an empty database, re-apply idempotently, and still match the repository (`flywayValidate`) |
| `secret-scan` | No secret anywhere in git history — not just at the tip, because a secret committed and later removed is still disclosed. A **net**, not the control: gitleaks is an entropy-and-pattern detector and does not catch `password: hunter2`. The control for that is a build rule in the `build` job (ADR-0020) |
| `dependency-scan` | No HIGH or CRITICAL known vulnerability in the resolved runtime dependency set, via a CycloneDX SBOM |

**Supply-chain pinning.** Every third-party action is pinned to a commit SHA and every
scanner to an image digest, never to a mutable tag: a tag can be repointed by whoever
controls the source repository, a SHA cannot. This is the same reasoning that pins the
Gradle distribution and wrapper jar by SHA-256. The number of third-party actions is
deliberately kept small, which is why the scanners run as pinned containers rather than as
marketplace actions.

**Version single-sourcing.** The Java version CI installs is read from
`gradle/libs.versions.toml` at run time rather than written into the workflow, so the
catalog remains the one place a version is defined.

**Infrastructure.** The `migrations` job starts PostgreSQL from the project's own
`compose.yaml` rather than a CI-specific service definition, so CI and a developer's machine
run the identical pinned image.

## Architectural Principles

1. Domain ownership before service decomposition.
2. Transactional truth before projections.
3. Explicit state machines for important financial lifecycles.
4. Asynchronous integration where it improves resilience without violating invariants.
5. Provider adapters at external boundaries.
6. Outbox/inbox patterns where reliable publication or deduplication requires them.
7. Observability across customer -> request -> domain operation -> provider -> ledger -> settlement -> reconciliation.
8. Regulatory-specific behavior behind policy/configuration/adapters.

## Multi-Instance Execution

Every service runs as **N concurrent instances** — pods, containers, JVMs, hosts — and N is
never 1. ADR-0001's modular monolith is one *deployable*, not one *process*: it is deployed as
several replicas behind a load balancer, and every distributed-systems constraint applies to it
exactly as it would to separate services.

No business logic may rely on a single-process assumption. Authoritative state lives in the
database; coordination involving time uses the database's clock; process-local mechanisms are
permitted only where they are explicitly non-authoritative and correctness does not depend on
them.

The full requirement, the component register and the audit that produced it are in
[`DISTRIBUTED_EXECUTION.md`](DISTRIBUTED_EXECUTION.md). The decision is ADR-0014.

---

## Preferred Evolution

Start with a modular monolith unless a hard requirement justifies distribution. Extract services only when independent scaling, isolation, ownership, deployment, or reliability warrants it.
