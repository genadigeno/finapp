# ADR-0027 — Tests bring their own database

Status: Accepted

Date: 2026-09-02

## Context

Every database test connected to whatever was listening on `127.0.0.1:5432` — in practice the
`compose.yaml` stack, started by hand. `.claude/rules/testing.md` asks for Testcontainers, and
`P0-TSK-035`'s acceptance criterion is blunter: *no test depends on a developer's local services*.

Two things were wrong with the old arrangement, and only one of them is convenience:

- a developer who forgets `docker compose up -d postgres` gets a connection error rather than a
  test run;
- more importantly, it is a **shared, long-lived** database. One run's leftover row is the next
  run's mystery, and several suites already had to scope their cleanup by a probe marker to avoid
  deleting each other's evidence.

## Decision

**A `LauncherSessionListener` starts one PostgreSQL container per test JVM**, applies the same
`00-roles.sql` the compose stack runs on first initialisation, applies the real migrations through
Flyway's Java API, and publishes the coordinates as the system properties every existing test
already read.

**No test changed.** That was the constraint, not the outcome: a harness that required editing 173
assertions would have been a change nobody could review, and the point of the exercise is that the
tests are unchanged while what they connect to is not.

**A `LauncherSessionListener`, not a JUnit extension.** It runs once per JVM before any test class
loads, which is the only place that can set properties a static initialiser will later read — and
several suites open their connection in `@BeforeAll`.

**Roles come from the same script, and migrations from Flyway.** Roles are cluster objects and
cannot live in a migration (ADR-0011), so a container that skipped the script would have no
`finapp_app` and every privilege test would fail for the wrong reason. Migrations run through
Flyway rather than a schema dump because `flywayValidate` is a CI gate, and a schema that arrived by
another route has no history to validate.

**The image comes from the version catalog**, passed in by the Gradle task, so the container is the
same pinned PostgreSQL compose runs and `verifyInfrastructureVersions` already guards. A second
image literal would be a third definition.

**PostgreSQL only.** The task named Kafka and Redis too, and there is no Kafka or Redis client on
the classpath — a container nothing connects to is a container that tests nothing. Same argument
ADR-0023 used for not guarding transports that do not exist, and ADR-0022 for not writing an enum
nothing consumes. They join the harness with their first client.

**The escape hatch is deliberate.** If `FINAPP_DB_URL` is set, the harness steps aside and the
tests use it. A container that disappears is exactly wrong when you want to inspect what a test
left behind. It is not a way to skip the database: with no property and no Docker, container start
fails loudly.

## Consequences

- `./gradlew databaseTest` needs Docker and nothing else. All 173 pass with no compose running.
- The hermetic `test` task is untouched: the listener returns immediately when the image property
  is absent, so `./gradlew build` is still green on a machine with nothing running. Throwing there
  instead killed the hermetic suite, which is how that branch came to exist.
- CI still starts compose, because the **Flyway build tasks** need a database and those are
  build-tool invocations rather than tests.
- The harness lives in `platform`'s `testFixtures`, so `app` can use it. That put it where the
  ArchUnit rules sweep production code, and two of them fired: a static container field
  (`noStaticMutableState`) and a field named `LOCAL_PASSWORD` (`secretsAreWrapped`). **Both were
  fixed at source rather than exempted** — the field did not need to be static, and the constant is
  the published marker rather than a credential, exactly as `DatabaseCredentialGuard` records. A
  harness that holds credentials is the right place for those rules to apply.
- `HealthReadinessDatabaseTest` had to change, and its own comment predicted why: it asserted
  Flyway was unreachable by trying to load the class, *"a false positive if Flyway were ever added
  as a test dependency of this module"*. The harness needs Flyway. It now asserts against the
  module's **runtime** classpath, which is what ADR-0011's claim was always about — a strictly
  better check that the old one only approximated.
- Testcontainers 2.x moved `PostgreSQLContainer` to `org.testcontainers.postgresql` and deprecated
  the old package; `-Werror` turned that into a build failure, which is the rule working.
- **Testcontainers mounts the Docker socket** into a reaper container (`testcontainers/ryuk`) so
  that containers are removed even if the JVM dies. Named here rather than left implicit, because
  socket access is control of the daemon: it is a real capability granted to test-time code. It is
  not a *new* one — Docker was already required for `compose.yaml` — and it is what makes the
  measured behaviour true: after a run, the container count is unchanged from before it.
- Measured cost: **14 seconds** wall clock for the platform suite including container start, the
  role script and every migration. The concern that a container per JVM would be slow did not
  survive measurement.

## Alternatives rejected

**Keeping compose and documenting the step.** It is what existed. It leaves the shared-database
problem untouched, and "no test depends on a developer's local services" unmet.

**A container per test class.** Isolation is total and the cost is a container start per class.
Per-JVM with a fresh schema is the same isolation for the failure modes that matter here, since
every suite already cleans up after itself.

**Reusable containers (`withReuse`).** Faster still, and it reintroduces exactly what this replaces:
a long-lived database shared between runs.

**A new `:test-support` Gradle module instead of test fixtures.** More explicit, and it would need
registering in `MODULE_ARCHITECTURE.md`'s module map and would look like a production module to
`ProductionModules.of()`. Test fixtures are the supported mechanism for sharing test code between
modules and cost one plugin line.

## References

- `.claude/rules/testing.md`; ADR-0011 (migrations never run at startup)
- `DISTRIBUTED_EXECUTION.md` §5 — the multi-instance test convention this must not break
- `README.md` §3 — what compose is still for
