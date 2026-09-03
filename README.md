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

### Test tiers

A test's **tier** is what it needs in order to run, and each tier is its own task. The tiers are
declared once, in `finapp.java-conventions.gradle.kts`, and `TestTaxonomyTest` fails the build
when they, `TestTier`, the tag on a test class, and what CI invokes stop agreeing.

| Task | Runs tests that need |
|---|---|
| `./gradlew unitTest` | nothing beyond the JVM |
| `./gradlew architectureTest` | the compiled classes of every module |
| `./gradlew sliceTest` | a Spring application context |
| `./gradlew databaseTest` | a real PostgreSQL — see §4 |

`build` runs the first three. `unitTest` is the fast inner loop: seconds rather than a minute,
because it starts no Spring context.

The full conventions — which tier a concern belongs to, naming, tagging, and the shared harnesses
— are in [`docs/project/TESTING.md`](docs/project/TESTING.md).

Useful narrower commands:

```bash
./gradlew :sharedkernel:unitTest
```

```bash
./gradlew :app:architectureTest --tests '*ModuleBoundaryRulesTest*'
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

**Tests no longer need this stack.** Since `P0-TSK-035`, `./gradlew databaseTest` starts its own
PostgreSQL container, applies `infra/postgres/initdb/00-roles.sql` and the real migrations, and
throws it away afterwards — so a test run needs Docker and nothing else. Compose is still what
`bootRun` and the Flyway build tasks connect to, and it is still useful when you want a database
that outlives the run: set `FINAPP_DB_URL` and the harness steps aside.

Everything binds to `127.0.0.1`, never `0.0.0.0`, so the stack is not reachable from the
network. Default credentials are `finapp` / `local-development-only-not-a-secret` against
database `finapp`, overridable via `FINAPP_DB_USER`, `FINAPP_DB_PASSWORD` and `FINAPP_DB_NAME`.
They are throwaway local values, deliberately named so they cannot be mistaken for a secret.

That name is not what protects you, though. The application **refuses to start** if that default
is aimed at a database which is not on loopback, because the one documented way around
externalised configuration is forgetting to set the variable
([`SECRET_MANAGEMENT.md`](docs/architecture/SECRET_MANAGEMENT.md), ADR-0020).

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

Tests that need a live PostgreSQL are a **separate tier**, not tests that skip themselves:

```bash
./gradlew databaseTest
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
| | *and in `build`:* no credential literal in any committed configuration file |
| `dependency-scan` | No HIGH or CRITICAL known vulnerability in the resolved dependency set |

The `migrations` job starts PostgreSQL from this same `compose.yaml`, so CI and your machine
run the identical pinned image.

The secret scan is one definition, and you can run it:

```bash
./infra/scripts/secret-scan.sh
```

CI calls that same script, so the pinned image and the arguments exist in one place rather than
two that agree today. It scans **history**, not the working tree — a secret committed and later
removed is still disclosed.

**Two mechanisms, deliberately.** The scanner detects by the *value*'s shape and the build rule by
the *key*'s name, so they are blind in different directions: gitleaks catches a private key and
misses `password: hunter2`, which is exactly what a human commits. Do **not** commit a dummy secret
here to test the scan — history is scanned, so it would stay red for ever. Use a throwaway clone;
the procedure is in [`SECRET_MANAGEMENT.md`](docs/architecture/SECRET_MANAGEMENT.md) §6.

> **Current limitation, stated plainly:** this repository has no git remote, so the pipeline
> has never executed on a runner. All four jobs pass when run locally. This is the one
> outstanding item in milestone M0.1.

---

## 7a. Changing a dependency

Every artefact the build resolves is checksum-verified and version-locked
(`gradle/verification-metadata.xml` and the three `gradle.lockfile`s). Both are enforced, so
changing a dependency is three steps rather than two:

```bash
# 1. Change the version in gradle/libs.versions.toml - the single source.
# 2. Regenerate BOTH records in ONE invocation.
./gradlew --write-locks --write-verification-metadata sha256 build databaseTest
```

**One invocation, both flags** - this was two separate commands until `P0-TSK-035` added a
dependency and found that neither order works. The lock refuses a version it does not know, so
metadata generation cannot resolve; verification refuses an artefact it has no checksum for, so
lock generation cannot resolve. Each control blocks the other's regeneration. Passing both flags
together is the only sequence that succeeds.

**`build-logic` is a separate included build and is regenerated separately.** A root
`--write-locks` does not touch it - verified - so a change to the Kotlin DSL or toolchain
dependencies needs:

```bash
cd build-logic && ../gradlew --write-locks build
```

Its artefacts are covered by the root verification metadata either way, because dependency
verification is Gradle-wide and reaches included builds; only the version lock is separate.

Then **read the diff**. That is the step that does the work:

- the lockfile diff names every version that moved, including transitives a BOM bumped for you;
- the metadata diff names every artefact whose bytes are now trusted.

A change you cannot explain in those two diffs is the signal this exists for.

**Both task lists matter.** `build databaseTest` is what covers every configuration; a narrower run
adds only what it resolved. That is safe — regeneration merges, and existing entries survive,
which is verified — but it will not *record* anything the narrow run did not touch, so a later full
build fails with a missing-checksum error rather than a wrong one.

**Removing a dependency leaves its entries behind.** Gradle merges and never prunes, so a superseded
version stays trusted. Delete those entries by hand in the same change; the lockfile is what shows
you which ones.

**What this does and does not protect.** The checksums are trust-on-first-use: they record what was
downloaded when they were written, so they catch a substitution *afterwards* and cannot catch a
first download that was already compromised. The lockfile catches an accidental version drift, not
an attacker — anyone who can edit it can edit the version catalog beside it. See
[ADR-0025](docs/adr/ADR-0025-dependency-verification-and-locking.md).

**If verification fails**, the report at `build/reports/dependency-verification/` names the artefact
and the expected checksum. Treat a mismatch as a compromise until proven otherwise: check the
artefact against the publisher's own published checksum, never against the one the build just
downloaded.

## 7b. Keeping the pins fresh

Everything here is pinned, which means everything here can rot. Three mechanisms, because no one
of them reaches all of it (ADR-0026):

| Pinned thing | Where | Update path |
|---|---|---|
| Four GitHub Actions (commit SHA) | `.github/workflows/ci.yml` | Dependabot, weekly - a pull request |
| Library versions | `gradle/libs.versions.toml` | Dependabot, weekly - the PR **will fail its build** until you run §7a's regeneration |
| Two scanner images (digest) | `infra/scanner-pins.sh` | `infra/scripts/check-pinned-images.sh`, weekly in CI |
| Infrastructure images | `compose.yaml` + the catalog | `verifyInfrastructureVersions` fails the build on drift |
| Gradle distribution | `gradle-wrapper.properties` | Manual, with the checksum from `services.gradle.org` |

Check the scanner pins yourself at any time:

```bash
./infra/scripts/check-pinned-images.sh
```

It distinguishes two findings, and the distinction matters:

- **the tag moved** - the digest no longer matches the version it claims. The pin held. Find out
  why the tag changed *before* updating it;
- **a newer release exists** - ordinary rot.

To update a scanner: bump `VERSION` in `infra/scanner-pins.sh`, re-resolve the digest with
`docker buildx imagetools inspect <repository>:<version>`, and re-run the check.

**Why the scanner pins are not Dependabot's job.** They live in a shell-sourced file it does not
read, and they stay there deliberately: both scanners are invoked through `infra/scripts/`, which
CI calls, so a developer and CI run the identical image. Moving a digest into the workflow so a bot
could see it would put the same fact in two places.

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
