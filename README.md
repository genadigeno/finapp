# finapp

An enterprise fintech reference platform: double-entry ledger, payments, settlement and
reconciliation, built as a modular monolith with financial correctness enforced mechanically
rather than by convention.

**Phase 0 delivers no business capability.** That is deliberate — money representation,
idempotency, outbox, audit and correlation cannot be retrofitted once financial history
exists. See [`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md) for what is built
today.

---

## 1. Prerequisites

| You need | Why | Check with |
|---|---|---|
| **Git** | Clone the repository | `git --version` |
| **Docker** with Compose v2 | PostgreSQL, Kafka and Redis run in containers | `docker compose version` |
| A **JDK**, any recent version | Only to start Gradle | `java -version` |

**You do not need to install Java 21, Gradle, PostgreSQL, Kafka or Redis.**

- Gradle is provided by the wrapper (`./gradlew`) and verified by SHA-256 on first use.
- Java 21 is the pinned toolchain. If your machine does not have it, Gradle downloads a
  matching JDK — the build does not use whatever `JAVA_HOME` happens to point at, so the
  bytecode is identical everywhere.
- The infrastructure versions are pinned in `compose.yaml` and asserted against the version
  catalog on every build.

Report the Gradle version and the JVM Gradle itself is running on:

```bash
./gradlew toolchainInfo
```

Note that the launcher JVM is *not* the compile toolchain. The build targets Java 21 whatever
JVM launched it; what proves that is `BuildToolchainTest`, which asserts the emitted bytecode
version and runs as part of `./gradlew build`.

---

## 2. Build and test

From a fresh clone, this is the whole thing:

```bash
./gradlew build
```

That compiles every module, runs the full test suite, and runs the architecture rules that
fail the build on a boundary or floating-point-money violation.

**It needs no infrastructure and no network services.** The suite is hermetic by design: no
test starts a container, and no test skips itself when something is missing. Tests that
genuinely require a database live in a separate task you can see did not run — see §4.

Useful narrower commands:

```bash
./gradlew :sharedkernel:test
```

```bash
./gradlew :app:test --tests '*ModuleBoundaryRulesTest*'
```

---

## 3. Start local infrastructure

```bash
docker compose up -d --wait
```

`--wait` blocks until every container reports **healthy**, not merely started. Without it the
next command can fail against a database that is still initialising.

| Service | Host address | Role |
|---|---|---|
| PostgreSQL 18.6 | `localhost:5432` | Transactional source of truth — the ledger lives here |
| Kafka 4.3.1 (KRaft) | `localhost:29092` | Event transport. **Never** the accounting source of truth |
| Redis 8.10.1 | `localhost:6379` | Cache, coordination, ephemeral state. **Never** financial truth |

Everything binds to `127.0.0.1`, never `0.0.0.0`, so the stack is not reachable from the
network. Default credentials are `finapp` / `local-development-only-not-a-secret` against
database `finapp`, overridable via `FINAPP_DB_USER`, `FINAPP_DB_PASSWORD` and `FINAPP_DB_NAME`.
They are throwaway local values, deliberately named so they cannot be mistaken for a secret.

Check state at any time:

```bash
docker compose ps
```

### Database roles

PostgreSQL is provisioned with three roles, not one:

| Role | Used by | Holds |
|---|---|---|
| `finapp` | The container's own bootstrap | Cluster superuser. Nothing in this project connects as it except one test fixture |
| `finapp_migrator` | Flyway | Owns the `platform` schema. DDL only, **not** a superuser |
| `finapp_app` | The application | Per-table DML, granted by the migration that creates each table. No DDL |

The split is not cosmetic. `INV-LED-03`, `INV-HIST-01` and `INV-HIST-03` are enforced by the
application role **lacking** a privilege, and a superuser ignores every permission check — so
running as one would make those invariants untestable rather than merely unenforced.

`finapp_migrator` and `finapp_app` are created by
`infra/postgres/initdb/00-roles.sql`, which the container runs on **first initialisation only**.
Roles are cluster objects and so cannot live in a Flyway migration; see the script's header.

**If your volume predates this file**, the script never ran. Either reset:

```bash
docker compose down -v && docker compose up -d --wait
```

or apply it once by hand — `MSYS_NO_PATHCONV=1` is required in Git Bash, which otherwise
rewrites the container path:

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T postgres psql -U finapp -d finapp -f /docker-entrypoint-initdb.d/00-roles.sql
```

Without the roles, `./gradlew :platform:flywayMigrate` fails with
`FATAL: password authentication failed for user "finapp_migrator"`.

---

## 4. Database migrations and database tests

Migrations never run as a side effect of application startup (ADR-0011). Applying them is an
explicit act:

```bash
./gradlew :platform:flywayMigrate
```

Inspect what has been applied:

```bash
./gradlew :platform:flywayInfo
```

Assert that the applied migrations still match the repository — this is what catches an
edited migration file:

```bash
./gradlew :platform:flywayValidate
```

Tests that need a live PostgreSQL are a **separate task**, not tests that skip themselves:

```bash
./gradlew :platform:databaseTest
```

A test that quietly skips when the database is absent reports success, and a suite that
reports success for work it did not do is worse than one that fails. With this split,
`./gradlew build` stays green with nothing running, and an absent database means a task you
can see did not run.

---

## 5. Infrastructure lifecycle

```bash
docker compose stop
```

Stops the containers and keeps everything.

```bash
docker compose down
```

Removes the containers. **Data survives** — the volumes are named, not anonymous.

```bash
docker compose down -v
```

Removes the containers **and discards all data**. This is the deliberate reset. You need it
after changing Kafka's `CLUSTER_ID`, and when moving to a new PostgreSQL major version without
running `pg_upgrade` — a data directory is formatted for the major version that created it.

---

## 5a. The API contract

The public HTTP contract is [`docs/api/openapi.json`](docs/api/openapi.json). It is **generated
from the running application** during `:app:test` and compared byte for byte against that committed
file, so the build fails on any change to the contract - including one nobody meant to make.

Every route is served under `/v1` (ADR-0015). Controllers do not declare the prefix; the
application applies it.

When you change the contract on purpose, the failing test prints each difference labelled
`BREAKING` or `COMPATIBLE`, and tells you what to do. Accepting a compatible change is a copy:

```bash
cp app/build/openapi/openapi.json docs/api/openapi.json
```

If any difference is labelled `BREAKING`, do not copy it. A breaking change is not published under
an existing version - either withdraw it or open the next one.

No OpenAPI machinery is deployed. springdoc is a test-scope dependency, and the running
application serves no `/v3/api-docs`.

---

## 5c. API conventions

[`docs/architecture/API_CONVENTIONS.md`](docs/architecture/API_CONVENTIONS.md) is what a client
author reads: versioning, errors, correlation, request limits, idempotency, pagination and
deprecation, in one place.

Every section is labelled **Implemented** or **Decided, not yet implemented**, because Phase 0
publishes no business endpoint and two of the conventions - idempotency and pagination - are
settled decisions waiting for their first endpoint. `ApiConventionsAreAccurateTest` fails the build
if a stated value stops matching the code, or if a section is added without a label.

---

## 5b. Health, readiness and build info

Three endpoints, deliberately unversioned (ADR-0016):

```bash
curl -s -o /dev/null -w '%{http_code}
' http://localhost:8080/actuator/health/readiness
```

| Endpoint | Answers | Depends on |
|---|---|---|
| `/actuator/health/liveness` | Should this process be killed and restarted? | Nothing external |
| `/actuator/health/readiness` | Should traffic be routed here? | PostgreSQL |
| `/actuator/info` | Which build is running? | Nothing |

**Readiness returns 503 when PostgreSQL is unreachable**, and the application still starts and
still serves those endpoints - so a stopped database gives you an instance that says NOT_READY
rather than a crash-looping one that says nothing.

**Liveness ignores the database on purpose.** If it did not, a thirty-second failover would
restart every instance at once and leave them reconnecting in a herd to a database already in
trouble.

Health responses carry a status and nothing else - no dependency name, no URL, no exception. That
is a security decision, not an oversight; see `docs/architecture/SECURITY_ARCHITECTURE.md`.

Database settings come from the same `FINAPP_DB_*` environment variables `compose.yaml` uses, and
the application connects as `finapp_app` - never the superuser.

---

## 5d. Metrics and the dashboard

The application publishes Prometheus metrics at `/actuator/prometheus`. Names follow
`finapp.<module>.<noun>` and the build fails if one does not (ADR-0018).

To see the dashboard:

```bash
docker compose up -d prometheus grafana
```

Then run the application and open <http://localhost:3000/d/finapp-platform>. Grafana is
provisioned from `infra/grafana/` - the datasource and the dashboard are files in git, not state
in a volume, so `allowUiUpdates` is off: edit the JSON, not the UI.

Prometheus scrapes the application on the **host**, not in a container, because that is where a
developer runs it.

**A metric never carries a correlation identifier, an account, or anything else a request can
influence.** That is both a cardinality rule and a security rule - a tag value from a request
multiplies one series into thousands and puts it in a system with months of retention. Correlation
belongs on a trace and in a log.

**An unreadable metric reports absent, not zero.** Stop PostgreSQL and `finapp_outbox_pending`
becomes `NaN` rather than `0`, so an alert written on the value still fires.

---

## 6. What CI checks

Four independent jobs, so a failure names its own gate
([`.github/workflows/ci.yml`](.github/workflows/ci.yml)):

| Job | Gate |
|---|---|
| `build` | Compilation, tests, module boundary rules, infrastructure version drift, wrapper checksum |
| `migrations` | Migrations apply to an empty database, re-apply idempotently, still match the repository, and monetary values round-trip through real columns |
| `secret-scan` | No secret anywhere in git history — not just at the tip |
| `dependency-scan` | No HIGH or CRITICAL known vulnerability in the resolved dependency set |

The `migrations` job starts PostgreSQL from this same `compose.yaml`, so CI and your machine
run the identical pinned image.

> **Current limitation, stated plainly:** this repository has no git remote, so the pipeline
> has never executed on a runner. All four jobs pass when run locally. This is the one
> outstanding item in milestone M0.1.

---

## 7. Troubleshooting

**`PKIX path building failed` / `unable to find valid certification path`**

Something is intercepting HTTPS and re-signing it — corporate proxy, or antivirus with HTTPS
scanning (AVG and Kaspersky both do this). Your OS trusts that CA; the JDK's own trust store
does not, which is why `curl` and the browser work while Gradle does not.

On Windows, add this to `~/.gradle/gradle.properties` — **outside the repository**, because it
is a property of your machine, not of this project:

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

Bootstrapping the Gradle distribution runs in a separate JVM that reads `GRADLE_OPTS`, so on a
machine with no Gradle distribution cached yet, also export:

```bash
export GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatively, import the intercepting CA into the JDK's `cacerts`, or turn off HTTPS scanning.

**`clean` fails with "Unable to delete directory"** (Windows)

An orphaned Gradle daemon is holding module jars open. `./gradlew --stop` handles the usual
case. A daemon whose `GRADLE_USER_HOME` has been deleted survives that and must be killed by
PID:

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleDaemon' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**`docker compose exec` rewrites absolute paths** (Git Bash on Windows)

MSYS rewrites container paths, so `/opt/kafka/bin/...` becomes `C:/Program Files/Git/opt/...`.
Prefix the command with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use
only — health checks run inside the container and are unaffected.

**`databaseTest` fails to connect**

The database is not up, or is still initialising. `docker compose up -d --wait` returns only
when it is healthy; `docker compose ps` shows the current state.

---

## 8. Where to read next

| Question | Document |
|---|---|
| What is built right now? | [`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md) |
| What are the rules for changing this codebase? | [`CLAUDE.md`](CLAUDE.md) |
| What must never be violated? | [`docs/domain/FINANCIAL_INVARIANTS.md`](docs/domain/FINANCIAL_INVARIANTS.md) |
| How are modules bounded and enforced? | [`docs/architecture/MODULE_ARCHITECTURE.md`](docs/architecture/MODULE_ARCHITECTURE.md) |
| Why was something decided this way? | [`docs/adr/`](docs/adr/README.md) |
| What is the plan? | [`docs/project/DELIVERY_PLAN.md`](docs/project/DELIVERY_PLAN.md), [`docs/project/BACKLOG.md`](docs/project/BACKLOG.md) |
| When is work actually done? | [`docs/project/DEFINITION_OF_DONE.md`](docs/project/DEFINITION_OF_DONE.md) |
