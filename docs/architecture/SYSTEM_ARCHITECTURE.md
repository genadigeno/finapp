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

*(The Phase 14 → 15 transition, 2026-11-07, read this list against the code (`PHASE_15_PLAN.md` §3): **Spring Security**
is not used - authentication and authorisation are the platform's own interceptors over server-side sessions and scoped
merchant keys (ADR-0030, ADR-0031, ADR-0052), with `spring-security-crypto` for Argon2 only; **Redis** runs in
`compose.yaml` and no code uses it (ADR-0030 chose PostgreSQL for sessions) - it is not deployed (C3); **S3-compatible
storage** is not used by the application (ADR-0036) and serves infrastructure only; **Docker, Kubernetes and Terraform**
are Phase 15's (ADR-0120, ADR-0121, `Proposed`); **OpenTelemetry** spans are recorded and not yet exported (ADR-0124).
`P15-TSK-031` rewrites the list to the stack in use.)*
## Pinned Versions

Versions are pinned in `gradle/libs.versions.toml` (the single source of truth) and in
`gradle/wrapper/gradle-wrapper.properties`. No version literal appears in a build script.

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 (LTS) | Pinned by Gradle toolchain, not by ambient `JAVA_HOME` |
| Gradle | 9.7.1 | Distribution and wrapper jar verified by SHA-256 |
| Spring Boot | 4.1.1 | Brings Spring Framework 7 and Jackson 3 |
| Tomcat | 11.0.25 | **Pinned above the BOM**, which manages 11.0.24 — three CRITICAL advisories. The only deliberate deviation; see the catalog note |
| PostgreSQL | 18.6 | Debian-based image, ICU collation provider |
| Kafka | 4.3.1 | KRaft mode, no ZooKeeper |
| Redis | 8.10.1 | AOF persistence enabled locally |
| Prometheus | v3.14.0 | Scrapes `/actuator/prometheus`; also guarded by `verifyInfrastructureVersions` |
| Grafana | 12.4.10 | Renders the reviewed dashboard in git; UI edits disabled (ADR-0018) |
| Flyway | 12.4.0 | Matches the Spring Boot BOM; forward-only (ADR-0011) |
| WireMock | 3.13.2 | Provider failure simulation, test scope; standalone artefact (ADR-0008, `P0-TSK-037`) |
| Lombok | 1.18.46 | **Compile time only** (`compileOnly` + `annotationProcessor`, wired once in `finapp.java-conventions`); matches the Spring Boot BOM; `.claude/rules/java-lombok.md` and `lombok.config` (ADR-0055) |
| PostgreSQL JDBC | 42.7.13 | Matches the Spring Boot BOM |
| CycloneDX Gradle plugin | 3.4.1 | Produces the SBOM the dependency scan consumes |
| gitleaks | v8.30.1 | Secret scanning; pinned by image digest in `infra/scripts/secret-scan.sh`, which CI calls |
| Trivy | 0.76.2 | Dependency vulnerability scanning, since `P15-TSK-013` the configuration scan of the Terraform (`trivy config`, its embedded checks), and since `P15-TSK-006` the image scan and the per-image SBOM; pinned by image digest in `infra/scanner-pins.sh` |
| Semgrep OSS | 1.184.0 | Static analysis of the production source, with rules vendored under `infra/semgrep/rules` and no network; pinned by image digest in `infra/scanner-pins.sh` (`P15-TSK-006`) |
| Terraform | 1.17.1 | Infrastructure code (ADR-0121), never applied in Phase 15; the image pinned by digest in `infra/scanner-pins.sh`, and every root's `required_version` equal to it |
| tflint | v0.65.0 | Lints the Terraform with its bundled ruleset only; pinned by image digest in `infra/scanner-pins.sh` |
| AWS provider (Terraform) | 6.72.0 | Pinned exactly in both environment roots and locked in each root's `.terraform.lock.hcl` |
| Image builder base | `eclipse-temurin:21.0.13_9-jdk-noble` | Only extracts the boot jar's layers; pinned by digest in the `Dockerfile`'s `FROM` line, its one definition (`P15-TSK-003`) |
| Image runtime base | `gcr.io/distroless/java21-debian13:nonroot` | No shell, no package manager, UID 65532; pinned by digest in the `Dockerfile`'s `FROM` line. `nonroot` is a floating tag, so its freshness is a newer build, not a release |

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
change in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), as separate jobs so
a failure names its own gate *(this read "as four separate jobs" until `P15-TSK-013` added
`iac`, `P15-TSK-003` added `image`, `P15-TSK-004` added `migrator-image` and `P15-TSK-006` added `sast`; the weekly `pinned-images` freshness job is scheduled, not a per-change gate, and
`README.md` §7b describes it)*:

| Job | Gate |
|-----|------|
| `build` | Compilation, unit tests, module boundary tests, infrastructure version drift, Gradle wrapper checksum validation |
| `migrations` | Migrations apply to an empty database, re-apply idempotently, and still match the repository (`flywayValidate`) |
| `secret-scan` | No secret anywhere in git history — not just at the tip, because a secret committed and later removed is still disclosed. A **net**, not the control: gitleaks is an entropy-and-pattern detector and does not catch `password: hunter2`. The control for that is a build rule in the `build` job (ADR-0020) |
| `dependency-scan` | No HIGH or CRITICAL known vulnerability in the resolved runtime dependency set, via a CycloneDX SBOM |
| `iac` | Every Terraform root and module passes `fmt`, `tflint`, `trivy config` (no HIGH or CRITICAL), `validate` and `terraform test` with mock providers, and each check refuses a planted defect in the same run; no credential, no backend (`P15-TSK-013`, ADR-0121) |
| `image` | The application image (`P15-TSK-003`, C4): both bases pinned by digest; built twice from one commit, the boot jar each time from source, and the jar's sha256, the image id and every layer digest identical; UID 65532, no `HEALTHCHECK`, no environment of its own, the OCI revision, source and version labels; the pinned distroless base plus the boot jar's entries and nothing else, no shell or package manager; within its 300 MiB budget; then run against compose's PostgreSQL and Kafka on loopback, read-only and with every capability dropped, until readiness reports `UP`. **Scanned** (`P15-TSK-006`): every image built, for vulnerabilities, secrets and misconfiguration, HIGH and CRITICAL failing, a finding carried only with a reason and an expiry in `infra/trivy/accepted-findings.yaml` and the threat model; one CycloneDX SBOM per image, asserted to list the image's operating-system packages and every third-party jar by path and no test-scope library, kept with the run for 30 days; five plants refused by name in the same run. Weekly, the same job rebuilds the last commit and re-scans it against a fresh database. Pushes nothing. `infra/scripts/image-build.sh`, `image-readiness.sh` and `image-scan.sh`, the scripts a developer runs (`README.md` §5f) |
| `migrator-image` | The migrator image built twice from one commit and identical, non-root, nothing in it but the pinned base, its libraries and the repository's migrations; run against a throwaway PostgreSQL with the schema history and schema compared to `flywayMigrate`'s, re-run, run concurrently, and held behind a lock (`P15-TSK-004`, `DATA_MIGRATIONS.md` §2.1) |
| `sast` | Static analysis of every module's production source (`P15-TSK-006`): Semgrep OSS, pinned by digest and run with no network, over the rules vendored in `infra/semgrep/rules` (a statement whose SQL text is not built from constants, T45; a provider's answer read with no size bound, T43; Java security; Spring; secrets). The rules' own tests run first; every finding must have a row in `infra/semgrep/baseline.tsv` with a reason and an expiry, every row a finding; plants refused by name in the same run. `infra/scripts/sast.sh`, the script a developer runs |

**Dependency verification and locking** (`P0-TSK-039`, ADR-0025). Two files, two different
controls, both enforced on every build:

| File | Refuses | Does not catch |
|---|---|---|
| `gradle/verification-metadata.xml` | an artefact whose bytes do not match the recorded SHA-256 | a *version* change to something it already records |
| a `gradle.lockfile` per project, plus one for each settings buildscript (the root and `build-logic`) | a configuration resolving a version other than the locked one | a substituted artefact at the locked version |

They are not redundant, and that was measured rather than assumed: on its first generation the
verification file recorded **69 of 342 modules at more than one version** — `jackson-bom` at five —
because the buildscript, plugin, compile and test classpaths legitimately resolve different
versions of the same module. Verification cannot distinguish a deliberate resolution from drift
*between versions it already knows*; the lockfile can, and it is the only place the thirteen
BOM-managed dependency versions are written down at all.

**The limit that matters, stated plainly: this is trust on first use.** The checksums record what
this machine downloaded on the day they were generated. They detect a *later* substitution and they
cannot detect that the first download was already compromised. Signature verification would address
that and is not enabled — see ADR-0025.

**Updating a dependency** is documented in [`README.md`](../../README.md) §7a. It is deliberately
two commands and a diff review, not a regeneration: `--write-verification-metadata` **merges**, so
existing entries survive a narrow run — verified — which is what makes a targeted update safe.

**Supply-chain pinning.** Every third-party action is pinned to a commit SHA and every
scanner and base image to an image digest, never to a mutable tag: a tag can be repointed by whoever
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

## Deployment (planned, Phase 15)

*Planned by the Phase 14 → 15 transition (2026-11-07; ADR-0120…0125, `Proposed`; `PHASE_15_PLAN.md`); nothing of it is
built* *(until 2026-11-08: the `finapp-app` image is built and asserted by CI's `image` job, `P15-TSK-003`, and pushed
nowhere; `P15-TSK-008` built the management port and the shutdown order, noted below).* The modular monolith deploys as the units it has: **`finapp-app`**, one minimal non-root image, a Kubernetes
Deployment of N ≥ 3 replicas each serving HTTP and running every leaderless schedule and consumer, and
**`finapp-migrator`**, a Job per release running every module's migrations as `finapp_migrator` before the rollout.
PostgreSQL and Kafka are managed outside the cluster. A public ingress and an internal operator ingress terminate TLS;
the actuator moves to a management port no ingress serves *(built by `P15-TSK-008`, 2026-11-08: 8081, loopback outside
Kubernetes, with the graceful shutdown order of ADR-0120 decision 4; the ingresses and their policy are still planned)*. Infrastructure is Terraform, verified with mock providers and
never applied in Phase 15. Each commit's images are scanned, signed with provenance and promoted by digest through
GitOps, and every production change needs two persons. The CI table above gains the image, static-analysis, manifest,
infrastructure, observability-rule and `kind`-rehearsal jobs as `P15-TSK-003`…`-025` land, each a pinned script that also
runs on a developer's machine.

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
