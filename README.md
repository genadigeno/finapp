# finapp

**An enterprise fintech reference platform, built to show how serious financial infrastructure is engineered.**
It covers digital banking, payments, merchants, settlement and reconciliation, FX and cross-border, credit, lending,
buy-now-pay-later, risk and AML, and accounting, all built on one double-entry ledger. It is a Java 21 / Spring Boot
modular monolith on PostgreSQL, designed to run as many concurrent instances, with its financial rules enforced
by the build rather than by convention.

This README has two parts:

- **[Part 1 - For technical readers](#part-1---for-technical-readers)**: architecture, stack, modules, status, how to
  build, test, run and ship it, and where the detailed documents are.
- **[Part 2 - For non-technical readers](#part-2---for-non-technical-readers)**: what finapp is, what it can do, why it
  is built the way it is, and where it stands, in plain language.

The canonical, always-current record of where the project is lives in
[`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md). Every number in this README was counted from the
repository on 2026-11-21. Other documents cite this README by its older section labels (§3, §5e, §5f, §5g, §6, §7a,
§7b). Those labels are kept on the subsections that replaced them.

---

# Part 1 - For technical readers

## 1.1 At a glance

| | |
|---|---|
| 🏗️ **Shape** | One deployable modular monolith, `finapp-app`, run as **N ≥ 3 replicas**; a migration Job, `finapp-migrator`; and a static Operator Console, `finapp-console`, on the operator edge |
| 🧩 **Modules** | **25 Gradle modules**: 22 domain or platform modules, the pure `creditmath`, the `migrator` and the `app` |
| 🗄️ **Schema** | **388 forward-only Flyway migrations** across **21 schema-owning modules**, one PostgreSQL schema per module |
| 🌐 **API** | **558 published paths, 604 operations** (456 of them on the operator surface), 871 schemas, in [`docs/api/openapi.json`](docs/api/openapi.json), generated from the running application and compared byte for byte on every build |
| 📐 **Decisions** | **150 ADRs** in [`docs/adr/`](docs/adr/README.md): 148 `Accepted`, 2 `Proposed` |
| 🔒 **Invariants** | **171 financial and platform invariants** (`INV-*`) in [`docs/domain/FINANCIAL_INVARIANTS.md`](docs/domain/FINANCIAL_INVARIANTS.md) |
| 🧾 **Audit and errors** | **469 auditable actions** ([`AUDITABLE_ACTIONS.md`](docs/architecture/AUDITABLE_ACTIONS.md)) and **399 error codes** ([`ERROR_CONTRACT.md`](docs/architecture/ERROR_CONTRACT.md)), both reconciled with the code by guard tests |
| 💻 **Code** | 3,151 production Java files (~448k lines), 1,957 test files and 50 test fixtures; 12,495 test methods |
| 📦 **History** | 2,327 commits, 2026-08-31 to 2026-11-21 |

## 1.2 Where the project is

**Phases 0 to 16 are `COMPLETE`. Phase 17, the Operator Console, is in progress: 49 of 51 items done.**

```
Programme   ████████████████░  17 of 18 phases complete (0-16)
Phase 17    ████████████████░  49 of 51 items - the end-to-end battery and the exit review open
```

| # | Phase | Status | What it delivered |
|---|---|---|---|
| 0 | Domain and Architecture Foundation | ✅ 2026-09-04 | The boundary-enforced modular monolith; money, idempotency, outbox, audit and correlation before any business capability |
| 1 | Identity and Customer Foundation | ✅ 2026-09-09 | Party, customer and identity; authentication, server-side sessions, MFA, authorisation, audit |
| 2 | KYC/KYB and Consent | ✅ 2026-09-13 | The verification lifecycle, screening adapters, evidence kept verbatim, an append-only consent history |
| 3 | Accounts and Financial Ledger | ✅ 2026-09-17 | Chart of accounts, double-entry journal, explainable balances, holds, reversals, four-eyes adjustments |
| 4 | Internal Transfers | ✅ 2026-09-19 | The first end-to-end money movement, conserved under ten concurrent instances |
| 5 | Payment Infrastructure | ✅ 2026-09-21 | Payment intents and attempts, provider adapters, an honest `UNKNOWN`, refunds, authenticated webhooks |
| 6 | Checkout and Merchant Platform | ✅ 2026-09-24 | Merchant onboarding behind KYB, checkout, versioned fee schedules, merchant payouts, tenancy |
| 7 | Cards, Wallets, A2A and Instant Payments | ✅ 2026-09-28 | Three rails behind one abstraction; disputes and chargebacks posted exactly once |
| 8 | Settlement and Reconciliation | ✅ 2026-10-01 | Settlement ingestion, matching, breaks, suspense and investigation |
| 9 | FX and Cross-Border Payments | ✅ 2026-10-07 | Quotes, rate locks, conversion, FX cover, corridors and the cross-border workflow |
| 10 | Credit Decisioning | ✅ 2026-10-09 | Credit profiles, bureau adapters, versioned policy and scorecards, explainable decisions |
| 11 | Lending | ✅ 2026-10-16 | Applications, offers, disbursement, schedules, accrual, repayment, credit lines, delinquency, lending capital |
| 12 | Buy Now, Pay Later | ✅ 2026-10-22 | Pay-in-N at checkout, merchants paid upfront net of fee, refunds latest-instalment-first, the three-way proof |
| 13 | Risk, Fraud and AML | ✅ 2026-10-27 | Signals, rules, decisions, cases and ongoing monitoring |
| 14 | Accounting and Financial Reporting | ✅ 2026-11-07 | The GL as a versioned mapping over the journal, periods with a posting cutoff, a four-eyes close, reports |
| 15 | Production Hardening | ✅ 2026-11-12 | Two reproducible, signed images; Kubernetes manifests; a segmented edge; Terraform verified with mock providers; SLOs and runbooks |
| 16 | Scale, Resilience and Disaster Recovery | ✅ 2026-11-17 | Load characterisation (k6, Toxiproxy), degradation modes, backup and restore, the DR stance |
| 17 | Operator Console | 🟡 in progress | The operator endpoints, edge and admission behind the console in the `finapp-admin-panel` repository |

**A phase is complete when a review says so, not when its tasks are.** Each phase closes on an exit review against
twelve universal criteria, the financial supplement F1-F8 and its own criteria, and a transition audit opens the next.
Several reviews failed their gate and sent the phase back. The review records are in
[`docs/project/reviews/`](docs/project/reviews/).

**What is owed, stated rather than hidden:**

- **Never deployed.** Nothing has run in a real environment. The images, manifests and Terraform are built and checked
  in CI and rehearsed on `kind`; there is no cloud account, and provider connectivity is simulated.
- **Phase 17's verification is partial, by the owner's choice.** Its later tasks were verified on the unit and slice
  tiers only, and the last five merged (`P17-TSK-123`...`-126` and `-040`) on no tests at all. The database, architecture and
  end-to-end tiers are recorded as owed, as are five known-red unit cases from `P17-TSK-124`.
- Open items, debt and postponed issues are in `CURRENT_STATE.md` and
  [`docs/project/POSTPONED_ISSUES.md`](docs/project/POSTPONED_ISSUES.md).

## 1.3 Architecture

### The shape

A **modular monolith** (ADR-0001): one deployable whose modules are bounded contexts with their own PostgreSQL schemas,
published ports and enforced boundaries. It is one *deployable*, never one *process*. It always runs as N concurrent
replicas, and **no correctness rests on a single JVM, a local lock, local memory or a single scheduler** (ADR-0014,
[`DISTRIBUTED_EXECUTION.md`](docs/architecture/DISTRIBUTED_EXECUTION.md)). Authoritative state lives in the database,
time-based coordination uses the database's clock, and schedules are leaderless (`FOR UPDATE SKIP LOCKED`).

```
             public edge                               operator edge (TLS, separate ingress)
                  |                                         |                 |
                  v                                         v                 v
   +-------------------------------------------+    /v1/operator/**     /console/**
   |  finapp-app  x N replicas                 |<------------+        finapp-console
   |  (HTTP + every leaderless schedule and    |                      (static SPA, no state)
   |   consumer, in every replica)             |
   +---------------------+---------------------+
                         |
          +--------------+---------------+
          v                              v
     PostgreSQL                        Kafka
  (the financial truth:            (integration transport,
   one schema per module)           never the accounting truth)
          ^
          |
   finapp-migrator  (a Job per release, before the rollout)
```

### Modules

| Module | Owns | Main / test files | Migrations |
|---|---|---|---|
| `sharedkernel` | `Money` (integer minor units, explicit currency and scale), identifiers, correlation value types; no Spring | 25 / 14 | - |
| `platform` | Idempotency, the transactional outbox and inbox, audit, correlation, authentication and authorisation interceptors, the error contract | 119 / 80 | 17 |
| `party` | Parties and customers | 18 / 4 | 6 |
| `identity` | Identities, credentials (Argon2), server-side sessions, MFA, roles and permissions, segregation of duties | 87 / 27 | 30 |
| `kyc` | KYC/KYB cases, documents and evidence, screening, the review queue | 83 / 32 | 16 |
| `consent` | Versioned consent texts and the append-only consent history | 13 / 3 | 3 |
| `ledger` | Ledger accounts, the double-entry journal, balances, holds, reversals, adjustments | 113 / 41 | 39 |
| `accounts` | Customer accounts and wallets, restrictions | 29 / 5 | 3 |
| `transfers` | Internal transfers, beneficiaries, reversals | 40 / 8 | 5 |
| `paymentmethods` | Tokenised instruments: the PCI boundary | 17 / 7 | 3 |
| `payments` | Payment intents and attempts, provider adapters, rails, refunds, voids, webhooks, disputes | 222 / 45 | 42 |
| `checkout` | Checkout sessions and orders | 21 / 5 | 5 |
| `merchant` | Merchants, scoped API keys, fee schedules, payables and payouts | 140 / 36 | 22 |
| `settlement` | External settlement evidence and its recognition | 61 / 43 | 22 |
| `reconciliation` | Matching, breaks, suspense and investigation | 129 / 63 | 27 |
| `fx` | Rates, quotes, rate locks, trades, pricing policy, cover | 81 / 25 | 14 |
| `crossborder` | Corridors and cross-border payments | 47 / 10 | 8 |
| `credit` | Credit profiles, bureau adapters, policy and scorecard versions, decisions, review cases | 122 / 43 | 26 |
| `lending` | Loan applications, offers, disbursement, schedules, accrual, repayment, delinquency, capital | 184 / 59 | 36 |
| `creditmath` | The pure, deterministic credit arithmetic that lending and BNPL share | 24 / 11 | - |
| `bnpl` | Pay-in-N agreements, merchant financing, instalments, refunds, disputes | 164 / 92 | 23 |
| `risk` | Signals, rules, decisions, cases, AML monitoring | 246 / 117 | 22 |
| `accounting` | GL mapping, accounting periods, the close, statements and regulatory reports | 182 / 73 | 19 |
| `migrator` | The Flyway runner shipped as the `finapp-migrator` image | 12 / 10 | - |
| `app` | The Spring Boot application: HTTP doors, wiring, the API contract and the architecture rules | 972 / 1,104 | - |

Which module owns what, and why, is in [`MODULE_ARCHITECTURE.md`](docs/architecture/MODULE_ARCHITECTURE.md) and
[`BOUNDED_CONTEXTS.md`](docs/architecture/BOUNDED_CONTEXTS.md). The build fails on a dependency that crosses a boundary
it may not cross.

### Stack

| Area | In use |
|---|---|
| Language and build | Java 21 (pinned toolchain), Gradle wrapper with dependency locking and SHA-256 verification, Lombok under a restricted `lombok.config` |
| Application | Spring Boot 4.1 (web, actuator, JDBC). **No Spring Security** (the platform's own interceptors over server-side sessions and scoped merchant keys); `spring-security-crypto` for Argon2 only. **No ORM**: explicit JDBC (ADR-0033) |
| Data | PostgreSQL 18 through explicit JDBC; Flyway 13 per-module migrations, expand-only; four database roles, least privilege |
| Messaging | Kafka 4.3 (KRaft) behind the transactional outbox and an inbox; **never** the accounting truth |
| Not used by the application | Redis (local compose only), object storage (evidence lives in PostgreSQL, ADR-0036) |
| Observability | OpenTelemetry over OTLP to one collector; Prometheus, Alertmanager, Grafana; Tempo and Loki as reference backends; ECS JSON logs |
| Testing | JUnit 6, AssertJ, Testcontainers, WireMock, ArchUnit; k6 and Toxiproxy for load and faults |
| Delivery | Distroless non-root images built twice and compared, SBOMs, signing and admission, Kubernetes manifests, Argo CD promotion by digest, Terraform with mock providers |
| API | REST under `/v1`, OpenAPI generated from the running app; signed, idempotent webhooks |

### Two surfaces, two edges

- **The public surface** serves customers (`/v1/me/**`, transfers, payments, KYC and so on) and merchants (scoped API
  keys).
- **The operator surface**, `/v1/operator/**` and the other operator acts, serves staff through a separate operator edge.
  Every operator act requires a `MULTI_FACTOR` session. Money acts are four eyes: one person proposes, a different
  person approves. Personal-data reads are recorded. Lists are keyset-paged collections (`{items, nextCursor}`).
- The edge's allow-lists (`deploy/edge/base/*-routes.yaml`) are **generated** from the permission register and the
  handler mapping, never kept by hand.

### The Operator Console and the customer application

- **`finapp-admin-panel`** is the Operator Console, a React/TypeScript single-page
  application. It is a client of the operator surface and nothing more: no backend-for-frontend, no authority in the
  browser, served same-origin from `/console/**` on the operator edge. It pins this repository's contract by commit.
- **finapp-front-end** is the customer-facing web application, designed in its own repository and planned as Phase 18.

## 1.4 The engineering rules that make it financial infrastructure

The binding rules are in [`CLAUDE.md`](CLAUDE.md) and the invariant catalogue. In short:

1. **No floating point for money, ever.** `Money` is an integer of minor units with an explicit currency and scale. An
   architecture rule fails the build on a `double` or `float` near money.
2. **Every journal entry balances.** Total debits equal total credits per currency, and the trial balance is
   continuously asserted at zero.
3. **History is immutable.** Corrections are reversals, adjustments or compensating entries. The application's database
   role *lacks* the privilege to update or delete the journal, so the rule holds even against a bug.
4. **A balance is explainable** from the postings that make it: economic event, domain operation, financial
   transaction, journal entry, debit and credit lines, balance, settlement, reconciliation.
5. **Every money-moving operation has an explicit state machine**, and an outcome nobody knows yet is a modelled
   `UNKNOWN`, never a guess.
6. **Critical commands are idempotent.** An `Idempotency-Key` per principal, persisted at the financial boundary: a retry
   replays the stored answer, and the same key carrying a different request is refused.
7. **Duplicates are expected.** Events, webhooks and requests arrive twice, late or out of order, and must be harmless.
8. **Kafka, caches and projections are not truth.** The outbox publishes what PostgreSQL committed.
9. **Reconciliation is a first-class capability.** External evidence is kept, matched, and every break investigated.
10. **Everything privileged is audited** with actor, time, operation, target, reason, correlation and outcome.
11. **Correct under ten instances.** Every critical path is argued, and where possible tested, under concurrent replicas.

**How the rules are held:** architecture tests (module boundaries, money types, schema ownership), guard tests that
reconcile the code with its registers (audit actions, error codes, permissions, routes, ownership, threat model,
configuration), a generated and diffed API contract, and **mutation demonstrations**: a protecting test is shown to
fail when its invariant is deliberately broken, and the result recorded in
[`docs/project/MUTATION_TESTING.md`](docs/project/MUTATION_TESTING.md). The demonstrations recent Phase 17 tasks still
owe are recorded as owed there and in `CURRENT_STATE.md`.

## 1.5 Repository layout

```
app/, ledger/, payments/, ...   the Gradle modules (section 1.3)
build-logic/                    convention plugins (an included build)
docs/adr/                       architecture decision records
docs/api/                       the generated OpenAPI contract
docs/architecture/              architecture, security, data, operations, runbook, threat model
docs/domain/                    invariants and glossary
docs/product/                   vision, capability map, roadmap
docs/project/                   current state, backlog, phase plans, reviews, history, proposals
deploy/                         Kubernetes manifests, overlays and the edge's route lists
infra/                          compose config, Grafana and Prometheus, Terraform, scripts, scanner pins
compose.yaml                    the local stack
```

## 1.6 Getting started

### Prerequisites

| You need | Why | Check with |
|---|---|---|
| **Git** | Clone the repository | `git --version` |
| **Docker** with Compose v2 | PostgreSQL, Kafka and the observability stack locally; Testcontainers for the database tier | `docker compose version` |
| A **JDK**, any recent version | Only to start Gradle | `java -version` |

You do **not** need to install Java 21, Gradle, PostgreSQL or Kafka. The wrapper is checksum-verified, and Gradle
provisions the pinned Java 21 toolchain whatever `JAVA_HOME` points at. `./gradlew toolchainInfo` reports both. In
IntelliJ IDEA, enable annotation processing for Lombok (ADR-0055).

### Build and test

```bash
./gradlew build
```

`build` compiles every module and runs every **hermetic** test (unit, architecture and slice), green with nothing
running. Tests that need real infrastructure are separate tasks, so an absent database is a task you can *see* did not
run, never a test that silently skipped.

| Task | Runs tests that need |
|---|---|
| `./gradlew unitTest` | nothing beyond the JVM (the fast inner loop) |
| `./gradlew architectureTest` | the compiled classes of every module |
| `./gradlew sliceTest` | a Spring application context |
| `./gradlew databaseTest` | a real PostgreSQL, started by Testcontainers (Docker only) |
| `./gradlew ownContainerDatabaseTest` | suites that need a PostgreSQL of their own |
| `./gradlew kafkaTest` | a real Kafka broker and the database |

Narrow runs: `./gradlew :ledger:unitTest`, or
`./gradlew :app:architectureTest --tests '*ModuleBoundaryRulesTest*'`. Conventions are in
[`docs/project/TESTING.md`](docs/project/TESTING.md).

### The local stack (§3)

```bash
docker compose up -d --wait
```

`--wait` returns only when every container is healthy. Everything binds to `127.0.0.1`.

| Service | Address | Role |
|---|---|---|
| PostgreSQL 18.6 | `localhost:5432` | The transactional source of truth |
| Kafka 4.3.1 (KRaft) | `localhost:29092` | Event transport |
| Redis 8.10.1 | `localhost:6379` | Local only; no code uses it |
| Prometheus, Alertmanager, Grafana | Grafana at <http://localhost:3000/d/finapp-platform> | Metrics, alerts, the dashboard (provisioned from `infra/`) |
| OpenTelemetry Collector, Tempo, Loki | local | Traces and logs |

The local credentials are deliberately throwaway (`finapp` / `local-development-only-not-a-secret`), and the application
**refuses to start** if that default is aimed at anything but loopback (ADR-0020).

**Database roles.** PostgreSQL is provisioned with four roles by `infra/postgres/initdb/00-roles.sql`, which runs on the
volume's first initialisation:

| Role | Used by | Holds |
|---|---|---|
| `finapp` | The container's bootstrap | Superuser; nothing connects as it but one test fixture |
| `finapp_migrator` | Flyway | DDL, not a superuser |
| `finapp_app` | The application | Per-table DML, granted by each migration; no DDL |
| `finapp_reporting` | Report generation's own pool | `SELECT` on what reports read |

The split is part of the design: several invariants are enforced by the application role *lacking* a privilege. If your
volume predates a role, re-apply the idempotent script, or reset with `docker compose down -v && docker compose up -d
--wait`. In Git Bash, prefix with `MSYS_NO_PATHCONV=1`:

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T postgres psql -U finapp -d finapp -f /docker-entrypoint-initdb.d/00-roles.sql
```

### Migrations

Migrations never run at application start-up (ADR-0011). Apply them explicitly:

```bash
./gradlew flywayMigrate
```

`flywayInfo` shows what is applied, and `flywayValidate` catches an edited migration. Migrations are expand-only. A
deployed environment runs the `finapp-migrator` image as a Job instead
([`DATA_MIGRATIONS.md`](docs/architecture/DATA_MIGRATIONS.md)).

### Running the application

With the stack up and migrations applied, run the Spring Boot application from the `app` module (`./gradlew :app:bootRun`),
or run the image as section 1.8 describes. It connects as `finapp_app` (`FINAPP_DB_URL`, `FINAPP_DB_APP_USER`,
`FINAPP_DB_APP_PASSWORD`). There are no Spring profiles: environments differ by values only, and every property is in
[`CONFIGURATION_REGISTER.md`](docs/architecture/CONFIGURATION_REGISTER.md).

- **API port 8080.** Every route is under `/v1`.
- **Management port 8081**, loopback only: `/actuator/health/liveness`, `/actuator/health/readiness` (503 while
  PostgreSQL is unreachable; liveness deliberately ignores the database), `/actuator/info` and `/actuator/prometheus`.
  Health answers carry a status and nothing else.

### The first administrator (§5e)

The first administrator cannot be created through the API (`ROLE_ASSIGN` is the administrator's own). That is
deliberate: neither a bootstrap endpoint nor a seeded row in every environment. Register an identity normally, enrol a
second factor (`POST /v1/me/mfa`, then `/v1/me/mfa/confirmation`), then grant the role out of band:

```bash
docker compose exec postgres psql -U finapp_migrator -d finapp -c "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i WHERE i.login_identifier = 'REPLACE_ME'"
```

That one grant has no other actor in the audit trail, which is recorded rather than hidden. Every later grant names two
parties, and an administrator's revocation or suspension refuses to take the last administrator. The same out-of-band
write is the last-resort way back if risk's automated suspension ever leaves none (`OPERATIONS_RUNBOOK.md` §11.12). It
is a privileged-access event in its own right.

## 1.7 The API contract

[`docs/api/openapi.json`](docs/api/openapi.json) is generated from the running application by `OpenApiContractTest` and
compared, difference by difference, with the committed file. Each difference is labelled `BREAKING` or `COMPATIBLE`, and
a breaking change is never published under `/v1` (ADR-0015). The edge's route lists are generated the same way by
`EdgeAllowListTest`. To accept an intended, compatible change:

```bash
cp app/build/openapi/openapi.json docs/api/openapi.json
cp app/build/edge/operator-routes.yaml app/build/edge/public-routes.yaml deploy/edge/base/
```

Conventions for client authors (errors, correlation, idempotency, pagination, deprecation) are in
[`API_CONVENTIONS.md`](docs/architecture/API_CONVENTIONS.md). Events are described in
[`EVENT_ARCHITECTURE.md`](docs/architecture/EVENT_ARCHITECTURE.md): each carries an event id, type, aggregate id,
versions, timestamp, producer, correlation and causation.

## 1.8 Images, CI and delivery

### Building the images (§5f, §5g)

```bash
./infra/scripts/image-build.sh
```

It builds `finapp-app` twice from source and refuses the image unless both builds are byte-identical. It also checks
that the image runs as `65532:65532` with no shell and no `HEALTHCHECK`, that it is exactly the pinned distroless base
plus the boot jar, and that it fits its 300 MiB budget. It tags the image `finapp-app:<commit>` and pushes nothing.

- `./infra/scripts/image-readiness.sh` runs the image against the local stack (`docker compose up -d postgres kafka
  --wait` and `./gradlew flywayMigrate` first), read-only and with every capability dropped.
- `./infra/scripts/image-scan.sh` scans every `finapp-*` image and writes one CycloneDX SBOM each.
- The migrator image has `./infra/scripts/migrator-image-build.sh` and `./infra/scripts/migrator-verify.sh`. The verify
  script compares the image's schema with `flywayMigrate`'s on throwaway databases and races ten concurrent runs.
- **Updating a base image**: the two `FROM` lines are pinned `tag@sha256:digest`. Re-resolve a digest with
  `docker buildx imagetools inspect <image>:<tag>`.

### What CI checks (§6)

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs independent jobs, so a failure names its own gate:

| Job | Gate |
|---|---|
| `build` | Compilation, hermetic tests, boundary rules, version drift, wrapper checksum, no credential literal in config |
| `migrations` | Migrations apply to an empty database, re-apply idempotently, still match the repository |
| `secret-scan` | No secret anywhere in git **history** (`./infra/scripts/secret-scan.sh`) |
| `dependency-scan` | No HIGH or CRITICAL vulnerability in the resolved dependency set |
| `sast` | Semgrep over production source, findings fixed or baselined with a reason |
| `iac` | Terraform `fmt`, `tflint`, `trivy config`, `validate` and `terraform test` with mock providers |
| `manifests` | Every overlay renders, passes `kubeconform -strict` and every policy; the edge's route lists through the real proxy |
| `image`, `migrator-image` | The two images: reproducible, non-root, minimal, scanned, ready |

Every gate is a pinned script that also runs on a developer's machine (`infra/scripts/`). Further rehearsals, run on
demand rather than per push, cover signing and admission, promotion by digest, restore, migration failure, telemetry
scrubbing and a full `kind` rehearsal (`OPERATIONS_RUNBOOK.md` §11).

### Changing a dependency (§7a)

Every artefact is checksum-verified and version-locked (`gradle/verification-metadata.xml` and a `gradle.lockfile` per
project, ADR-0025). Change the version in `gradle/libs.versions.toml`, then regenerate **both records in one
invocation**, because each control blocks the other's regeneration. Do it **on a cold cache**: a warm
`GRADLE_USER_HOME` records less than a fresh machine needs, which is how CI's first run once failed.

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-locks --write-verification-metadata sha256 build databaseTest
```

- `build-logic` is a separate included build, locked separately: `cd build-logic && ../gradlew --write-locks build`.
- **Read both diffs.** The lockfile names every version that moved, and the metadata names every artefact now trusted.
- Removing a dependency leaves its entries behind, because Gradle never prunes. Delete them by hand in the same change.
- The checksums are trust-on-first-use. Treat a verification failure as a compromise until the artefact is checked
  against its publisher's own checksum.

### Keeping the pins fresh (§7b)

Everything is pinned, so everything can rot (ADR-0026). Dependabot proposes the GitHub Actions, libraries, the two base
images and the Terraform providers weekly. A library PR fails its build until the §7a regeneration is run.
`./infra/scripts/check-pinned-images.sh` checks the scanner and tool images (`infra/scanner-pins.sh`) and the base
images. It tells a moved tag, which needs explaining before any update, from a newer release, which is ordinary rot.
`verifyInfrastructureVersions` fails the build when `compose.yaml` drifts from the version catalog.

## 1.9 Troubleshooting

- **`PKIX path building failed`**: something is re-signing HTTPS (a corporate proxy, or antivirus HTTPS scanning). On
  Windows, add `org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m` to
  `~/.gradle/gradle.properties` (outside the repository), and export
  `GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` for the first wrapper download.
- **`clean` cannot delete a directory** (Windows): an orphaned Gradle daemon holds a jar. Stop *your own* daemons; on a
  machine shared with other builds, kill only the orphan's PID rather than every daemon.
- **`docker compose exec` rewrites paths** (Git Bash): prefix with `MSYS_NO_PATHCONV=1`, or use PowerShell.
- **`password authentication failed for user "finapp_migrator"`** or `role "finapp_reporting" does not exist`: the role
  script never ran on your volume (section 1.6).

## 1.10 Where to read next

| Question | Document |
|---|---|
| Where is the project right now? | [`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md) |
| What are the rules for changing this codebase? | [`CLAUDE.md`](CLAUDE.md), [`docs/project/EXECUTION_PROTOCOL.md`](docs/project/EXECUTION_PROTOCOL.md) |
| When is work done? | [`docs/project/DEFINITION_OF_DONE.md`](docs/project/DEFINITION_OF_DONE.md) |
| What must never be violated? | [`docs/domain/FINANCIAL_INVARIANTS.md`](docs/domain/FINANCIAL_INVARIANTS.md) |
| What does a term mean? | [`docs/domain/GLOSSARY.md`](docs/domain/GLOSSARY.md) |
| Why was something decided this way? | [`docs/adr/`](docs/adr/README.md), [`docs/project/DECISIONS.md`](docs/project/DECISIONS.md) |
| How is it secured, and against what? | [`SECURITY_ARCHITECTURE.md`](docs/architecture/SECURITY_ARCHITECTURE.md), [`THREAT_MODEL.md`](docs/architecture/THREAT_MODEL.md) |
| How is it run and recovered? | [`OPERATIONS_RUNBOOK.md`](docs/architecture/OPERATIONS_RUNBOOK.md), [`SLOS.md`](docs/architecture/SLOS.md), [`PRODUCTION_READINESS.md`](docs/architecture/PRODUCTION_READINESS.md) |
| What is planned? | [`docs/product/ROADMAP.md`](docs/product/ROADMAP.md), [`docs/project/BACKLOG.md`](docs/project/BACKLOG.md) |
| What was proposed but not approved? | [`docs/project/proposals/`](docs/project/proposals/README.md) |

---

# Part 2 - For non-technical readers

## 2.1 What finapp is

finapp is **the engine room of a digital financial company**, built as a reference: what a bank, a payments company or
a lender runs behind its app. Customers hold money, send it, pay with it and borrow it. Merchants sell through it and
get paid. The company's own staff check, approve and correct what happens, and the finance team closes the books
every month.

It is built to show *how* such a system should be engineered when real money is at stake. It is not tied to one
country's rules: country-specific requirements plug in at clearly marked boundaries.

## 2.2 What it can do today

**For customers**
- Open an account, prove who they are (identity verification, "KYC") and give or withdraw consent.
- Hold money in wallets, in several currencies, and see balances that can always be explained line by line.
- Send money to other customers, pay by card, by instant bank transfer or from the wallet, and move money in and out of
  the platform.
- Exchange currencies at a quoted, locked rate, and send money abroad.
- Apply for credit and loans, get a decision that can be explained, and repay on a schedule.
- Buy now and pay later in instalments at a merchant's checkout.

**For merchants**
- Join after a business check ("KYB"), take payments through checkout, issue refunds, handle disputes and chargebacks,
  and receive payouts net of agreed, versioned fees.

**For the company's staff (the Operator Console)**
- Look up customers, merchants, accounts, payments and loans; review identity checks; investigate fraud and
  money-laundering alerts.
- Approve or reject sensitive actions. **Every action that moves money needs two different people**: one proposes,
  another approves.
- Read only what their role allows. Every look at personal data is recorded.

**For the finance team**
- Match the company's records against what banks and card networks report (reconciliation), and investigate every
  difference.
- Keep a general ledger, close each month with a second person's approval, and produce financial and regulatory
  reports.

## 2.3 Why it is built this way

In most software, a small bug is an inconvenience. In financial software it can create or lose money that does not
exist, so finapp is built around a few promises:

- **Money is never created or lost by accident.** Every movement is recorded twice, as a debit and a credit, which
  must always balance, like a traditional accountant's double-entry book.
- **Nothing is ever erased.** A mistake is corrected by a new, visible correcting entry, never by changing history.
  Anyone can see what happened and when.
- **Pressing "pay" twice pays once.** Networks fail and people retry, and the system recognises a repeated request and
  never acts twice.
- **When a partner's answer is unknown, the system says "unknown".** It never guesses that a payment went through or
  failed. It finds out, and reconciles.
- **Everything important leaves a trail**: who did it, when, to what, why, and what happened.
- **It keeps working at scale.** It is designed to run on many machines at once without two of them doing the same
  thing twice or stepping on each other.

These promises are written down as more than 170 rules. Automated checks fail the build when one is broken, and the
checks are themselves tested by deliberately breaking a rule to prove the check catches it. The few recent checks not yet
tested this way are recorded as owed.

## 2.4 How the work is run

The project is delivered in **phases**, each with a written plan, tasks, and a clear definition of "done". A phase is
not finished when its tasks are. It is finished when a formal review says so, and several reviews have sent a phase
back for more work. Every significant decision is recorded with its reasons (150 so far). Every task goes through
three steps: design first, then build, then a completion check that looks for gaps.

The project owner is building finapp deliberately to develop deep expertise in financial systems and system design. The
documentation is a large part of the result: why each part exists, and what would go wrong without it.

## 2.5 Where it stands

- **17 of 18 phases are complete**: the foundations, identity, verification, the ledger, transfers, payments, merchants,
  cards and instant payments, settlement, FX and cross-border, credit, lending, buy-now-pay-later, risk and anti-money-
  laundering, accounting, production readiness, and scale and disaster recovery.
- **Phase 17, the Operator Console** (the staff application), is nearly done: 49 of 51 items. The end-to-end test battery and the formal exit review remain.
- **Next**: a customer-facing web application, planned in its own project.

**What it is not, yet:**
- It has **never run in a real environment** and moves no real money. Its connections to banks, card networks and
  credit bureaus are simulated.
- It is **not licensed or certified** for any country. The core is deliberately jurisdiction-neutral.
- Some recent work was merged with lighter testing, at the owner's choice to finish faster. The remaining tests are
  recorded as owed, not forgotten.

## 2.6 A few terms

| Term | Meaning |
|---|---|
| **Ledger** | The book where every movement of money is recorded. The final word on who owns what |
| **Double entry** | Every movement recorded as a debit in one place and a credit in another, always in balance |
| **KYC / KYB** | "Know your customer / business": checking who a person or company really is |
| **AML** | Anti-money-laundering: spotting and stopping suspicious flows of money |
| **Reconciliation** | Comparing the company's records with its partners' records, and explaining every difference |
| **Settlement** | When money between companies actually changes hands, often days after a payment |
| **Four eyes** | A rule that two different people must agree before a sensitive action happens |
| **BNPL** | Buy now, pay later: paying for a purchase in instalments |
| **FX** | Foreign exchange: converting one currency into another |
| **Idempotency** | The property that doing the same request twice has the effect of doing it once |
