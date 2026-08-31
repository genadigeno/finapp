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

## Architectural Principles

1. Domain ownership before service decomposition.
2. Transactional truth before projections.
3. Explicit state machines for important financial lifecycles.
4. Asynchronous integration where it improves resilience without violating invariants.
5. Provider adapters at external boundaries.
6. Outbox/inbox patterns where reliable publication or deduplication requires them.
7. Observability across customer -> request -> domain operation -> provider -> ledger -> settlement -> reconciliation.
8. Regulatory-specific behavior behind policy/configuration/adapters.

## Preferred Evolution

Start with a modular monolith unless a hard requirement justifies distribution. Extract services only when independent scaling, isolation, ownership, deployment, or reliability warrants it.
