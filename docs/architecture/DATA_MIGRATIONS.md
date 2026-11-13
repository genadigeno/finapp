# Database Migrations

How schema change works in this platform. The decision behind it is
[ADR-0011](../adr/ADR-0011-forward-only-migrations.md); this document is the operational
convention.

The governing idea: **a schema change to a financial database is a financial operation.**
It is subject to the same rules as a posting — history is not edited, corrections are new
forward steps, and evidence is preserved.

---

## 1. Layout and Ownership

```
<module>/src/main/resources/db/migration/<schema>/V<nnn>__<description>.sql
```

Today:

```
platform/src/main/resources/db/migration/platform/V001__initialise_platform_schema.sql
```

- A module that owns a schema owns its migrations and its `flyway_schema_history` table,
  which lives **inside that schema**. Schema ownership and migration ownership are the same
  thing (ADR-0006).
- No migration touches another module's schema. No cross-schema foreign keys.
- Versions are zero-padded and sequential *within a module*. Because each module has its own
  history table, two modules using version `001` do not collide.

### Adding a schema-owning module

1. Apply the Flyway plugin in the module's `build.gradle.kts`.
2. Add the `buildscript` classpath entries for the driver and `flyway-database-postgresql`.
3. Configure `schemas`, `defaultSchema` and `locations` for that module's schema.
4. Copy the correctness settings verbatim: `cleanDisabled`, `validateOnMigrate`,
   `outOfOrder = false`, `baselineOnMigrate = false`.
5. CI needs **no edit**: the workflow runs `flywayMigrate` / `flywayValidate` unqualified, so
   Gradle runs them in every project that has the task. *(This step said "add the module to the
   CI sequence" until `P3-TSK-011` followed it and found it stale — `P1-TSK-003` had removed
   that hand-maintained list in favour of the unqualified invocation, and the procedure kept
   instructing an edit to a list that no longer exists.)*

---

## 2. Running Migrations

```bash
./gradlew :platform:flywayInfo
```

```bash
./gradlew :platform:flywayMigrate
```

```bash
./gradlew :platform:flywayValidate
```

`flywayInfo` shows what is applied and what is pending; `flywayMigrate` applies pending
migrations; `flywayValidate` fails if the repository and the database disagree.

Migrations are **never** run automatically on application startup (ADR-0011). They are a
deliberate step: these tasks in a build, the Job of §2.1 in a deployed environment.

Connection details default to the local Compose stack and are overridden by `FINAPP_DB_URL`,
`FINAPP_DB_MIGRATOR_USER` and `FINAPP_DB_MIGRATOR_PASSWORD` — Flyway connects as the migrator
role, never the superuser. *(This named `FINAPP_DB_USER` and `FINAPP_DB_PASSWORD`, the
container's superuser, until the Phase 6 → 7 transition; no Flyway task reads them.)*

`flywayClean` is permanently disabled. To reset local state, discard the container volume
with `docker compose down -v`.

### 2.1 In a deployed environment: the migrator image and its Job

*(Added by `P15-TSK-004`, 2026-11-08. The Job's manifest is `P15-TSK-009`'s and `-012`'s, from this contract.)* Gradle is not
in a deployed environment, so Flyway runs from the second deployable, `finapp-migrator`: a stage of the root `Dockerfile` on
the application's distroless `nonroot` base, built by `infra/scripts/migrator-image-build.sh`. It holds a small program over
Flyway's API (module `migrator`), the same Flyway version the build's plugin uses, and every module's scripts at
`/migrator/sql/<schema>/`. It holds no credential and no configuration: everything comes from the Job.

**The same migrations, the same way.** One schema after another, in the build's order (`migrator/src/main/resources/com/finapp/migrator/schema-order.txt`,
which `MigratorOrderIsTheBuildsTest` holds equal to `settings.gradle.kts` and the build scripts), with each schema's
`build.gradle.kts` settings: `schemas`, `defaultSchema`, `cleanDisabled = true`, `validateOnMigrate = true`, `outOfOrder = false`,
`baselineOnMigrate = false` (`MigratorMatchesTheBuildTest`, against the configuration the runner really builds). It adds two
things only a deployed runner needs: a missing directory is a failure rather than "no migrations", and the wait for another
run's lock is bounded (below). `infra/scripts/migrator-verify.sh` runs the built image and `flywayMigrate` into two empty
databases and refuses any difference in the schema history (rank, version, description, type, script, checksum, installed by,
success) or in `pg_dump --schema-only`. CI's `migrator-image` job runs it.

| Variable | Meaning |
|---|---|
| `FINAPP_DB_URL` | Required, no default. Read by the driver's own parser: one host, and only `sslmode`, `sslrootcert`, `connectTimeout`, `socketTimeout`, `loginTimeout`, `tcpKeepAlive` as parameters. `sslmode=verify-full` off loopback |
| `FINAPP_DB_MIGRATOR_PASSWORD` | Required, no default; the marked local value is refused off loopback |
| `FINAPP_DB_MIGRATOR_USER` | `finapp_migrator` unless set, as in the build |

**It refuses to run as anything but the migrator.** Before the first migration it reads the catalogue, and a role holding
`SUPERUSER`, `CREATEROLE`, `CREATEDB`, `BYPASSRLS` or `REPLICATION` is refused: that is how the master user's credential, handed
to the Job by mistake, stops it (a managed database's master user is no superuser, but it holds `CREATEROLE`), and so is one
holding any role membership (`rds_superuser` and `pg_write_all_data` are memberships). It takes no arguments, so nothing can ask
it to clean. Flyway's `FLYWAY_*` variables and configuration files are not read. The server's error detail stays out of its
exceptions (`logServerErrorDetail=false`): `Key (...)=(...)` and `Failing row contains (...)` are row data, and a failed migration's
exception goes to the Job's log.

**A migration never queues in front of the writers.** PostgreSQL grants locks in arrival order, so a DDL statement waiting for
`ACCESS EXCLUSIVE` behind one long transaction makes every later reader and writer of that table wait behind *it*, each holding
a pooled connection. Every session therefore starts with `lock_timeout` 3 s, below the application pool's
`connection-timeout` (5 s, read by a test), set in the connection's startup packet, so it is in force before the first statement,
survives `RESET` and outranks `ALTER ROLE ... SET`. The run reads the setting back and refuses if it did not take. A migration
that cannot get a lock (`55P03`), or a deadlock's victim (`40P01`), is tried again five times in all, 5 s apart; each attempt
is its own transaction and leaves nothing behind.

**Two Jobs at once.** Flyway takes a PostgreSQL advisory lock on the schema's history table around each migration (not around
the run), so concurrent runs interleave safely: each migration is applied exactly once, by whichever run holds the lock, and the
others find it applied. The wait for another run's lock is bounded (300 polls, one a second). The acceptance script counts it:
the applied counts of the concurrent runs sum to the number of migrations, and the history equals the build's.

| Exit | Meaning | The Job |
|---|---|---|
| `0` | Every schema is at the latest migration: applied now, or nothing to apply | Succeeds; the rollout may start |
| `10` | A migration or a validation failed. It would fail the same way again | `FailJob`: no retry; §11.4 of the runbook |
| `11` | A lock was not granted within the bound, or the database was unavailable: the first connection is first tried ten times, ten seconds apart (`FirstConnection`: 90 s to 190 s a pod, within the deadline below across every pod; `P15-TSK-032`, because a new pod's first connections are refused until the cluster's network policy reaches its address), and only a refused or unanswered connection is tried again, never a credential, a database that is not there or a TLS refusal | Retried, up to `backoffLimit` |
| `12` | The configuration or identity was refused; a credential the server refused; a database that is not there; a TLS connection that cannot be verified (the CA bundle, its path, the host). Nothing ran | `FailJob` |
| others | The JVM's own (`1` uncaught, `3` out of memory) or a killed container's (`137`, `143`) | Retried, up to `backoffLimit` |

**The Job contract** (`P15-TSK-009` writes it, `-012` rehearses it, `-017` orders it before the rollout):

| Field | Value | Why |
|---|---|---|
| Image | `finapp-migrator` by digest, the release's | Never a tag |
| `command`, `args` | Not set | The entrypoint is the migrator and takes no arguments; a manifest setting either is doing something else |
| `restartPolicy` | `Never` | A retry is the Job's, counted by `backoffLimit`, on a fresh pod |
| `backoffLimit` | `2` | Three tries for a transient failure |
| `podFailurePolicy` | `FailJob` on exit codes `10` and `12` | A failure a retry cannot help must not be retried |
| `activeDeadlineSeconds` | `900` | The outer bound on everything above, on every pod of the Job; the whole chain of 316 migrations on an empty database took 77 s on a loaded development machine (2026-11-08) |
| `ttlSecondsAfterFinished` | `86400` | Keeps the logs of a failed run for a working day |
| Environment | The three variables above, the password from a secret only this Job reads | The master credential is never in this Job |
| Security context | `runAsNonRoot`, UID and GID 65532, `readOnlyRootFilesystem: true`, no capability, `allowPrivilegeEscalation: false`, seccomp `RuntimeDefault`; `/tmp` an `emptyDir` | As the application's; `migrator-verify.sh` runs it this way |
| Memory | `512Mi`, request equal to limit | `migrator-verify.sh` runs every apply under this limit; `-XX:MaxRAMPercentage=75.0` sizes the heap from it |
| Connections | Two per run at most (`migrator-verify.sh` measures the peak) | Inside `finapp.database.reserved-connections`; two Jobs at once are four |

**When a migration fails.** Flyway runs each migration in a transaction, and on PostgreSQL a failed one is rolled back and
**leaves no row in the schema history**: it is simply still pending, and the Job's failure is what blocks the rollout. Only a
migration that cannot run in a transaction (`executeInTransaction=false`, for `CREATE INDEX CONCURRENTLY`; none exists today)
leaves a failed row, and then `validateOnMigrate` refuses every later run until it is dealt with. Either way the way out is
forward, never `clean`: runbook §11.4.

*(Corrected 2026-11-12, `P15-TSK-032`, by its rehearsal on the image and on Gradle: **on this Flyway and PostgreSQL's default
transactional lock, a failed migration outside a transaction leaves no row either.** Flyway writes the failed row inside the
transaction that holds its lock, and that transaction rolls back; the effect stays (an `INVALID` index), unrecorded, and every
later run trips over it ("already exists"). On rows that allow it, `CREATE INDEX CONCURRENTLY` waits for Flyway's own
lock-holding transaction: the image's `lock_timeout` ends it after 3 s, and Gradle's task waits with no bound. The failed row
the paragraph above describes arises only with the lock taken per session, which neither the build nor the image does. So
**the build now refuses a migration that would run outside a transaction**: `EveryMigrationRunsInATransactionTest`, in the
migrator module, reads every script with the pinned Flyway's own scanner and PostgreSQL parser and refuses each form that version
accepts (a statement it runs outside a transaction, a `<script>.conf` saying `executeInTransaction=false`, and the setting made for
every script: a `flyway` block, a `gradle.properties` key, a Flyway configuration file, the runner's own configuration), until the
migrator gains a reviewed mode for one. `infra/scripts/migration-failure-rehearsal.sh` rehearses §11.4's cases; the runbook holds
the record.)*

### 2.2 The one-time bootstrap step: the deployed roles

*(Added by `P15-TSK-004`, 2026-11-08.)* Roles are infrastructure, never Flyway (§6): the migrator is `NOCREATEROLE`, and the
per-table grants stay in the migrations. So a deployed database gets its roles from a separate step, what
`infra/postgres/initdb/00-roles.sql` does for a laptop, **run once per database, before the first migration Job, and again only
when a release adds a role or changes a role's setting**. Never part of the Job: it is the migrator image with its `command` overridden, in a Job or a
session of its own:

```
/usr/bin/java -cp /migrator/lib/* com.finapp.migrator.RoleBootstrapMain
```

| Variable | Meaning |
|---|---|
| `FINAPP_DB_URL` | As above |
| `FINAPP_DB_MASTER_USER`, `FINAPP_DB_MASTER_PASSWORD` | The database's master user. **Only this step holds them.** It may not be one of the roles it creates |
| `FINAPP_DB_MIGRATOR_PASSWORD`, `FINAPP_DB_APP_PASSWORD`, `FINAPP_DB_REPORTING_PASSWORD` | From the secret store. Refused when blank, reused between roles, or the marked local value |

It creates `finapp_migrator`, `finapp_app` and `finapp_reporting` with `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
NOBYPASSRLS NOREPLICATION`, gives each its database rights (`CREATE, CONNECT` for the migrator, `CONNECT` for the others) and
revokes `PUBLIC`'s, in one transaction under an advisory lock. It sends the SCRAM verifier, computed in the process, never the
password, so the server, its log and `pg_stat_statements` never hold it in clear. A role that already exists is checked and left
alone, password included (rotation is a procedure of its own), except that its settings are applied again on every run (`CONFIGURATION_REGISTER.md` section 5); one holding a right it must not hold stops the step (exit `12`),
because how it got that right is something a person has to look at. It must run before a migration that grants to a role it
creates, or that migration fails with `role "..." does not exist`, never silently. The same exit codes apply.

---

## 3. Rules

### 3.1 Never edit an applied migration

Flyway checksums every applied migration. Editing one makes the database and the repository
disagree about what was executed, and both `flywayValidate` and `flywayMigrate` refuse to
proceed:

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 001
```

This is not an obstacle to work around with `flywayRepair`. `flywayRepair` rewrites the
history table to match the files — it makes the symptom disappear without making the
database correct. Use it only when the *files* are known-good and the history is known-bad
(for example, after a failed non-transactional migration), and record why. *(2026-11-12,
`P15-TSK-032`: that example does not arise on this repository's configuration - on PostgreSQL's
default transactional lock a failed migration writes no row - and the build refuses such a
migration; §2.1's note.)*

### 3.2 Forward-only

There are no undo scripts. A mistake is corrected by a new migration, exactly as a financial
error is corrected by a compensating entry rather than by editing the original
(`INV-REV-01`). The mistake stays visible in the history. That is the intended trade.

### 3.3 One logical change per migration

A migration that both adds a table and backfills another is two changes. Splitting them
keeps the failure surface small and the intent reviewable.

### 3.4 Migrations are reviewed as financial artefacts

A migration touching a table that holds money, postings, evidence or audit records gets the
same scrutiny as the code that writes them, and states in a comment which invariants it
affects.

---

## 4. Irreversible Financial Migrations

This is the case ordinary migration practice handles badly, and the reason ADR-0011 exists.

**A migration is irreversible when undoing it would destroy financial records or evidence.**
Dropping a column of posted amounts, rewriting historical rows into a new representation, or
deleting settlement evidence are all irreversible regardless of whether a script exists that
claims to reverse them.

### 4.1 Additive first — expand, migrate, contract

Never change a financial column in place. Use the parallel-change pattern, across separate
releases:

| Step | Action | Reversible? |
|------|--------|-------------|
| **Expand** | Add the new column or table. Nothing reads it. | Yes — drop it; nothing depended on it |
| **Backfill** | Populate the new representation. Write to both. | Yes — the old representation is still authoritative |
| **Verify** | Prove old and new agree for every row. Reconcile the difference to zero. | Yes |
| **Switch** | Reads move to the new representation. Still writing both. | Yes — switch reads back |
| **Contract** | Stop writing the old. Much later, and separately, consider removing it. | **No** |

Only the final step is irreversible, it is a migration of its own, and it does not happen in
the same release as the switch. If the verify step cannot reconcile old and new exactly, the
migration is wrong — do not proceed, and do not "explain" the difference.

#### Enforced: the expand-only lint (`P15-TSK-005`, ADR-0122 decision 5)

The migration Job runs first and the replicas roll after it, so for as long as a rollout lasts replicas of release N − 1
are serving on release N's schema, and after a rollback they serve on it again. **Release N's schema must serve release
N − 1's code.** `MigrationsAreExpandOnlyTest` (`app`, the architecture tier) holds that over every module's migrations;
`ExpandOnlyLintTest` holds its rules on planted files, through the same path.

**What is read: the floor.** Every migration above its module's line in
[`docs/migrations/released-schema-floor.yaml`](../migrations/released-schema-floor.yaml). The repository has no release tag and
CI clones shallowly, so a lint that asked git for "the last release" would pass on nothing; the floor is a committed file,
one line per module, and moving it is a reviewed change. The same test leaves the next floor under
`app/build/schema-floor/`, also when it goes red. **It moves on two occasions and no others**: when a release is cut, by copying
that file over the committed one and setting `release` to the version just cut; and, while `release` is `none`, at the
coordinator's merge of work whose migrations landed beside it, since those are not contracts with a release that exists. Moving it to get
a refused migration past the lint is the thing the review of this file is there to stop. A module the file does not name is read from its first migration, and a floor above a
module's newest file is refused, since a file added below it would never be read.

**While no release has been cut**, the file says `release: "none"`: no release N − 1 exists, so nothing can be broken yet,
and every line stands at the newest migration on master when the file was last recorded. Migrations added above those lines
are read from the moment they appear.

**Refused, unless the file carries a contract marker:**

| Class | What it is | Why it is refused |
|---|---|---|
| `DROP` | `DROP` of anything; `ALTER … DROP` of a column, a constraint, a default, `NOT NULL`; `SET DEFAULT NULL`, however the null is written | N − 1 reads, writes or relies on it |
| `RENAME` | `RENAME` of anything; `SET SCHEMA` | N − 1 names the old one |
| `TYPE_CHANGE` | A column's type, or an attribute's | N − 1 binds the old one |
| `TRUNCATE` | The statement | It destroys what N − 1 reads |
| `DELETE` | A `DELETE`, also inside a `WITH` | Likewise, a row at a time; unlike an `UPDATE` backfill, it can only take away |
| `CHECK` | A new `CHECK`, on a table or inline in `ADD COLUMN`; a domain's new constraint; a new column typed by a domain with a `CHECK`; a partition of a released table with a column or constraint list of its own | It can refuse a write N − 1 makes |
| `TRIGGER` | `CREATE [CONSTRAINT] TRIGGER`, `ENABLE TRIGGER`, `DISABLE TRIGGER` | Likewise, and this repository adds refusing triggers often. One firing on `TRUNCATE` alone is exempt: the application holds no `TRUNCATE`, so it can refuse nothing a release makes. A backfill bracketed by `DISABLE` and `ENABLE` in one file nets nothing, and says so with a marker of `ANY` |
| `UNIQUE` | `UNIQUE`, `PRIMARY KEY`, `EXCLUDE`, `CREATE UNIQUE INDEX` | Likewise |
| `FOREIGN_KEY` | `FOREIGN KEY`, inline `REFERENCES` | Likewise |
| `ALTERED_CONSTRAINT` | `ALTER CONSTRAINT` | It changes when, or whether, a constraint is checked against N − 1's writes |
| `SET_NOT_NULL` | `SET NOT NULL` | Likewise |
| `SET_GENERATED` | `ALTER COLUMN … SET GENERATED` | `BY DEFAULT` to `ALWAYS` refuses an explicit value with `428C9`, which N − 1 may still write |
| `NOT_NULL_WITHOUT_DEFAULT` | `ADD COLUMN … NOT NULL`, or typed by a domain that is, with no default; a default that is `NULL`, `(NULL)` or `CAST(NULL AS …)` is none (a generated or serial column has one) | N − 1's insert does not name it |
| `REVOKE` | Any `REVOKE`, a role's membership and a whole schema's tables included | N − 1 may use the privilege |
| `ROW_SECURITY` | `ENABLE`/`FORCE ROW LEVEL SECURITY`, `CREATE POLICY` | It can hide or refuse rows |
| `RULE` | `CREATE RULE` | It can rewrite or refuse a write |
| `REPLACED_BODY` | `CREATE OR REPLACE` of a function, procedure, aggregate or view that a migration at or below the floor defined, or of one in another module's schema | See below |
| `OPAQUE_BLOCK` | A `DO` block; a top-level call of a function this module defined | Its body is not read, so it is not vouched for |
| `UNRECOGNISED` | A statement form the lint does not know | The list of what passes is an allow-list: unknown is refused, never passed |

**Exempt: a table created earlier in the same file.** A `CHECK`, trigger, `UNIQUE`, foreign key, `SET NOT NULL`, policy or
`REVOKE` on it refuses nothing a release makes, so it needs no marker; nor does a `REVOKE` on a function or sequence created
earlier in the file. `CREATE TABLE IF NOT EXISTS` and `PARTITION OF` do not earn this: such a table may already hold a
release's rows. Nor does a `TEMP` table, or an unqualified table after the file moves its `search_path`: either may hide a
released table of the same name. Destructive statements are never exempt.

**A new table's foreign key to a released table is not refused**, though it can refuse release N − 1's `DELETE` of a parent row
that N has referenced. It takes a row only N writes, on a parent N − 1 must then delete or re-key during the rollout window,
and the schema's parents are mostly append-only to the application role. Refusing it would ask a marker of nearly every new
table. The rehearsal (`P15-TSK-012`) is what would show otherwise.

**Words inside comments, string literals, function bodies and quoted names do not count**: the file is tokenised and read
statement by statement. The cost is that a `CREATE FUNCTION`'s body is not read at all.

**What the lint does not do.** It reads no function body, and `CREATE OR REPLACE FUNCTION` on a trigger's function can add
a refusal without any of the words above. So it does not try to judge one: **every** replacement of something a release
already holds is refused as `REPLACED_BODY`, whatever the body says, and passes only with a marker, which is a person's
reviewed statement. A first definition by `CREATE OR REPLACE` is additive and passes. The lint cannot tell whether a
marker's claim is true: that release N − 1 runs on release N's schema is `P15-TSK-012`'s rehearsal.

**The contract marker, whose form is fixed here for `P15-TSK-017`'s promotion script.** One `--` line starting at column 0
in the file's header, before its first statement, matching exactly:

`^-- finapp-contract: first-conforming-release=(ANY|[0-9]+\.[0-9]+\.[0-9]+)(?=; waives=|; data-fix=)(?:; waives=([A-Z_]+(?:,[A-Z_]+)*))?(?:; data-fix=V([0-9]+(?:[._][0-9]+)*))?; reason=(\S.*)$`

For example:

```sql
-- finapp-contract: first-conforming-release=1.4.0; waives=DROP; reason=the last writer of legacy_status went in 1.4.0
-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V0041; reason=V0041 raises every negative ceiling to zero
-- finapp-contract: first-conforming-release=ANY; data-fix=V0057; reason=V0057 leaves no account without a code before the copy
```

*(The optional `; data-fix=V<version>` was added 2026-11-12 by `P15-TSK-032`, below, and beside it `waives` may be left out;
every marker written without them reads as before, and the promotion script reads the same form.)*

- **`first-conforming-release`** names the first release whose code conforms to this migration. A release is named by the
  Gradle `version` it was built with (`/actuator/info` publishes it), compared as a version. A marker names a release, never a
  snapshot; a build named `X.Y.Z-SNAPSHOT` comes before `X.Y.Z`, the release it leads to. *(Revised 2026-11-12: this said "less
  `-SNAPSHOT`", which made a snapshot equal to its release, so a rollback to a `1.4.0-SNAPSHOT` build across a marker naming
  `1.4.0` passed. The promotion's records keep the name as built.)*
- **`ANY`** says every release's code conforms, so it excludes nothing from a rollback: a tightening that refuses nothing,
  such as a widened `CHECK` replaced in place, or a function body that changed only how it reads the clock.
- **A named release must already be out** (`MARKER_AHEAD_OF_THE_RELEASE`): it may not be newer than the floor's `release`,
  because release N − 1 is running while this migration applies, and a contract N − 1 does not meet breaks it. While the
  floor says `none` there is no N − 1 to compare with, and any well-formed name stands.
- A line starting `-- finapp-contract` that does not parse, a second marker, or one after the first statement is
  `MALFORMED_MARKER`, and passes nothing. A marker inside a string or a block comment is not a marker.
- **`waives`** names the classes this marker answers, comma-separated, and only those are waived: a reason written for a
  replaced body says nothing about a dropped column beside it. A class no marker can answer, or one that does not exist, is
  `MALFORMED_MARKER`.
- It cannot waive `UNREADABLE` (a file that cannot be read to its end), `NOT_A_MIGRATION` (a file in the directory that is
  not a `V<version>__<name>.sql` directly in it, which the migrator image would not stage), or a marker's own refusals.
- **`data-fix`** names the migration that brings old rows into line before this file's tightening judges them: the next rule.
  `waives` cannot name `TIGHTENED_OVER_OLD_ROWS`; only `data-fix` answers it. A marker that answers only old rows, such as a
  copy's, leaves `waives` out (`first-conforming-release=ANY; data-fix=V<version>; reason=…`): it waives nothing for release
  N − 1. A marker carries `waives`, `data-fix` or both; one with neither is `MALFORMED_MARKER`.

#### Enforced: a tightening over old rows names its data fix, or leaves the old rows to a later release (`P15-TSK-032`)

*(Added 2026-11-12, `THREAT_MODEL.md` §6 item 42 (j), decided under the owner's standing word; widened the same day by its review
to copies, to what the expand-only lint cannot read, and to the forms marked below.)* A tightening judges every row the table
already holds. It passes in the build and in staging, on their rows, and can fail on production's alone; and then staging, which
has applied it, refuses any fix with a lower version (`outOfOrder = false`), so production has no forward fix at all and stays on
the release before (`OPERATIONS_RUNBOOK.md` §11.4, "In production"). `MigrationsAreExpandOnlyTest` holds this second rule over
every module's migrations above the floor, through `MigrationLint.tightenings`, and `TighteningsOverOldRowsTest` holds it on planted
files, through the same path. A refusal is `TIGHTENED_OVER_OLD_ROWS`.

**What a tightening is**, on a table or domain **older than the migration's release**:

- an added `CHECK`, foreign key, unique constraint, `PRIMARY KEY`, `EXCLUDE` or `CREATE UNIQUE INDEX`; PostgreSQL 18's
  `ADD CONSTRAINT … NOT NULL <column>`; `SET NOT NULL`;
- a column added `NOT NULL` with no default; with a `CHECK` or `PRIMARY KEY` of its own, or typed by a domain with a `CHECK` or
  `NOT NULL`; `GENERATED ALWAYS AS (…) STORED`, whose expression is computed for every row; `UNIQUE NULLS NOT DISTINCT`; and
  `UNIQUE` or `REFERENCES` when the old rows receive a value, from a default, an identity or serial, or a stored expression (with
  none they receive a null, which neither judges);
- a type change `USING` an expression, or to a domain with a `CHECK` or `NOT NULL`;
- a domain's new `CHECK` or `NOT NULL`, or its `SET NOT NULL`; a domain's constraint is known however it came, with the domain or
  added later;
- a `VALIDATE CONSTRAINT`;
- **a copy of old rows**: `INSERT … SELECT` or `INSERT … TABLE`, `UPDATE … FROM`, `MERGE`, `CREATE TABLE … AS` or `SELECT … INTO`
  that reads anything not certainly new (a table at or below the floor, a view, a common table expression's name). It makes its
  target old for the rest of the release, so a constraint added to it afterwards is judged as on any old table; and into a table
  of this release that already carries a `CHECK`, `UNIQUE`, `PRIMARY KEY`, foreign key, `NOT NULL` or generated column, or takes
  them through `LIKE` or `INHERITS`, the copy itself is the tightening. Renaming the old table aside and creating its name again
  is this case;
- **what the expand-only lint cannot vouch for**, which a `waives` answers for release N − 1 and not for the rows: a statement it
  classes `OPAQUE_BLOCK` (a `DO` block, a top-level call of a function this module defined), `UNRECOGNISED` (for example
  `ATTACH PARTITION`, which scans the partition, or `ALTER COLUMN … SET EXPRESSION`, which recomputes every row) or
  `ALTERED_CONSTRAINT` (PostgreSQL 18's `ALTER CONSTRAINT … ENFORCED` checks every row), when it is on an old object. **What an
  opaque block touches cannot be read, so it counts as old: the lint fails closed.** So does an unrecognised statement whose
  object it cannot read. Such a statement is refused here whatever it does; one that judges no row needs the lint taught its
  form, not a marker.

**Older than the release** reads the floor as the expand-only lint does: the release is the migrations above the module's line. A
table, domain, sequence, index or view one of them certainly created (as the same-file exemption above counts certainty) is new
and exempt until old rows reach it: a copy, a rename of an old table onto its name, an old partition attached to it, or, for a
domain, an old table's column taking it. Every other one is old. **While no release has been cut** (`release: "none"`), the lines
still divide the files and every table at or below them counts as old: staging, every developer's database and every rehearsal
already hold rows in those tables, and a tightening left unjudged until the first cut would be found by it.

**Such a tightening passes in one of these ways:**

1. **`NOT VALID`, named, then validated by a later release.** `ADD CONSTRAINT <name> CHECK (…) NOT VALID`, `ADD CONSTRAINT <name>
   FOREIGN KEY (…) … NOT VALID`, PostgreSQL 18's `ADD CONSTRAINT <name> NOT NULL <column> NOT VALID`, and a domain's `ADD
   CONSTRAINT <name> CHECK (…) NOT VALID` judge new writes at once and leave the old rows alone. **A `NOT VALID` constraint must be
   named**: its `VALIDATE CONSTRAINT <name>` is matched by that name, and the validation of one added without a name is refused.
   The validation passes only in a migration of a **later** release than the one that added it; validated in the same release it
   is a tightening like any other. **While the floor says `none`, "a later release" means a migration above the floor after the
   floor has next been recorded past the one that added it**: until then the lint reads both as one release. The release between
   is the time to find and fix what the old rows hold: the validation can still fail on a row nobody fixed, so its release should
   carry the fix too, named as in 4 when it does.
2. **`SET NOT NULL` over a proof.** `SET NOT NULL`, and PostgreSQL 18's `ADD CONSTRAINT … NOT NULL` without `NOT VALID`, pass once
   a valid, enforced `CHECK (<column> IS NOT NULL)` stands on that column, from which PostgreSQL proves the column without a scan:
   one validated as in 1, or one created valid, with its table or by an earlier release or with a named data fix; or once a
   not-null constraint on the column has been validated as in 1. Once a constraint or column of the table is dropped, or a
   constraint altered, the lint takes the proof as gone. *(Corrected 2026-11-12: this said `SET NOT NULL` has no `NOT VALID`
   form. PostgreSQL 18, the version this repository runs, adds a not-null constraint `NOT VALID`; and `SET NOT NULL` on a column
   whose not-null constraint is invalid validates it, scanning, so it is refused until that constraint has been validated.)*
3. **`UNIQUE USING INDEX` on an index an earlier release built.** `ADD CONSTRAINT <name> UNIQUE USING INDEX <index>` judges no row
   the index's build did not, when a migration at or below the floor built `<index>` unique. Built by this release, the index's
   own `CREATE UNIQUE INDEX` is the tightening. `PRIMARY KEY USING INDEX` sets its columns `NOT NULL`, scanning, and stays one.
4. **A named data fix.** The file's contract marker carries `data-fix=V<version>`: a migration of the **same release**, of a lower
   version, that writes rows (`INSERT`, `UPDATE`, `DELETE` or `MERGE`). Wherever the release runs, production included, the fix runs
   first. A version that is not lower, not in the release, or names a migration writing no row is refused. The lint holds that a
   fix is named and comes first; what it fixes is the marker's reason, a person's statement. A unique constraint or index
   otherwise has no `NOT VALID` form and no other way: it takes this one.

**What it does not claim.** It reads statements, not data: a named fix that misses a row, or a validation in a later release over a
row nobody fixed, fails as before, and the rule makes that the rare case rather than the usual one. A type change with no `USING`
and to no constrained domain (a narrowed `varchar`, say) can fail on old rows too and is not judged here; the expand-only lint
already asks a marker of it. A statement that reads an old table only to filter is counted a copy all the same.

**Grandfathered: none.** No migration above the floor tightened an old object when the rule was written, nor when it was widened;
the test holds the list (empty) so it cannot grow without a named version and a reason.

**The promotion script's rule (`P15-TSK-017`).** A rollback to release R is refused when any migration above R's own newest
version in that module carries a marker naming a release newer than R. Past such a marker the way back is a forward fix.
`ANY` never refuses. *(Built 2026-11-12: `infra/scripts/Promotion.java`, run by `promote.sh` before it writes and by CI on the pull
request. "The migrations" are those of the newest release the environment has ever run, read from its release record's history
along the branch's first-parent line, so a commit of a side branch that was merged never counts as run; a marker it cannot read, or a second one, refuses; and a rollback keeps the migrator of that
newest release, whose run on its own schema is a no-op, because a schema is never rolled back. `OPERATIONS_RUNBOOK.md` §11.36.)*

**Enforced at run time: the schema-version guard.** The build embeds each schema's newest version, read from the migrations
on the application's runtime classpath. The readiness contributor `schemaVersion` answers `OUT_OF_SERVICE` while any schema
is **older** than that, so a replica of N on N − 1's schema waits and the rollout pauses at `maxUnavailable: 0`. A **newer**
schema is never held against it: a replica of N − 1 on N's schema is what a rollout and a rollback are. Liveness never reads
it. Versions compare as versions, the highest row with `success = true`, read through `platform.applied_migration_versions()`
(§6). The probe never reads the database: a daemon thread does, one statement for every schema, at most once a minute on a
ready replica (`SchemaVersionGuard`; the runbook's §11.1c).

**Background work waits with it.** Readiness stops HTTP only, and a consumed event or a scheduled tick is traffic too: a handler
naming a column not yet applied fails with `42703`, which is not transient, so a valid financial event would be parked. So every
schedule, the outbox relay and every consumer loop does no work until the guard has proven the schema, and pauses while it is
found older. A failed read moves nothing: only a read that succeeded and shows an older schema takes a ready replica out.

### 4.2 Destructive DDL

The build refuses destructive DDL that carries no contract marker (§4.1). The marker is necessary, and these still apply.
`DROP TABLE`, `DROP COLUMN`, destructive `ALTER`, and any `DELETE` or `UPDATE` against
financial history require **all** of:

1. A migration containing that change and nothing else.
2. A comment naming what is being destroyed, why it is safe, and where the data now lives.
3. An ADR, if financial records or evidence are affected (`INV-HIST-01`, `INV-REC-01`).
4. Evidence of the verify step from section 4.1.

A column that ever held financial data is not dropped because it is unused. It is dropped
because someone decided, in writing, that the platform no longer needs to be able to answer
questions about it.

### 4.3 Backfills over financial data

- **Idempotent and resumable.** A backfill that crashes halfway must be safe to re-run — the
  same discipline as `INV-IDEM-02` for scheduled financial processes.
- **Batched.** A single statement over the journal-line table takes locks that block money
  movement. Batch, commit, continue.
- **Never in the same migration as the DDL** that created the target.
- **Verified by reconciliation**, not by inspection: sum the old, sum the new, assert equal.

*(Accounting `V014`, 2026-11-09, departs from "never in the same migration" and says why in its header: two small tables, one transaction so that no unnumbered row ever exists, and its numbering verified row by row before it commits. A row's position is not financial data. The rule stands for financial columns and large tables.)*

### 4.4 Locking

PostgreSQL supports transactional DDL, so Flyway runs each migration in a transaction and a
failure rolls back. Two exceptions matter:

- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction. It needs a migration marked
  non-transactional, and such a migration can leave an invalid index behind if it fails,
  which must be found and dropped before retrying. *(Since 2026-11-12, `P15-TSK-032`: **the build
  refuses one** - `EveryMigrationRunsInATransactionTest`, §2.1's note - until the migrator gains a
  reviewed mode for it. On this Flyway's default PostgreSQL lock such a migration cannot finish on
  the image (it waits for Flyway's own lock-holding transaction until `lock_timeout`), and a failure
  leaves its invalid index with no history row, which only a person can drop. Build an index in the
  migration's transaction, as ledger `V029` does; an index a large live table needs built
  `CONCURRENTLY` waits for that mode.)*
- `ALTER TABLE` variants that rewrite the table take an `ACCESS EXCLUSIVE` lock for the
  duration. On a large ledger table that is an outage. Prefer the expand/contract path.

---

## 5. Monetary Columns

Every monetary value is stored as three columns, never one (ADR-0003):

```
<field>_amount_minor  BIGINT    NOT NULL
<field>_currency      CHAR(3)   NOT NULL
<field>_scale         SMALLINT  NOT NULL
```

`com.finapp.platform.money.MoneyColumns` is the single definition of this shape. A migration
declaring a monetary field takes its DDL fragment from `MoneyColumns.columnsFor("<field>").ddl()`
rather than hand-writing the three columns, so the shape cannot drift between tables.

**Why three columns.** A single `NUMERIC` drops the currency. A `NUMERIC(19,2)` bakes one
currency's precision into the schema, which is wrong for JPY at 0 decimals and BHD at 3.
Integer minor units keep arithmetic exact (`INV-MON-01`) and let one column pair hold any
currency.

**Why the field-name prefix.** One table often holds several amounts — a gross, a fee, a net.
Prefixing keeps them distinct and stops one of them silently becoming "the" amount.

**Why every column is `NOT NULL`, and why that is not enough.** An amount with no currency,
or a currency with no scale, is not less information — it is uninterpretable. But `CHAR(3)`
pads rather than rejects, so it stores `'US '` as happily as `'USD'`. The DDL therefore also
carries check constraints:

```sql
CHECK (<field>_currency ~ '^[A-Z]{3}$')
CHECK (<field>_scale BETWEEN 0 AND 9)
```

Without them the only thing between a malformed code and a balance is the application
remembering to validate on read. `INV-MON-02` is enforceable in the schema, and
`DEFINITION_OF_DONE.md` §1.3 says an invariant that can be enforced by a database constraint
is enforced there rather than only in application code. The scale bound is generated from
`Money.MAX_SUPPORTED_SCALE` so the SQL and the Java cannot drift.

**Why scale is stored rather than derived.** Minor-unit counts are data that changes. A row
holding only `1234` and `USD` would silently change meaning if that data changed — 12.34
becoming 1.234. The stored scale is what makes a historical row interpretable as originally
written (`INV-MON-05`), and is why `Money.ofPersisted` exists.

**Never** widen, narrow or retype a monetary column in place. Changing `BIGINT` or the scale
column reinterprets every existing row at once, which is the irreversible case in section 4:
expand, backfill, verify, switch, contract.

Round-trip fidelity is verified against a real PostgreSQL by
`./gradlew :platform:databaseTest`, which CI runs in the `migrations` job. Those tests are
excluded from `build` on purpose, so a developer without a running database still gets a
green build rather than a test that skips itself and reports success.

---

## 6. Privilege Model

Three invariants are enforced at the database privilege level, not by application code:

| Invariant | Requirement |
|-----------|-------------|
| `INV-LED-03` | Posted journal entries and lines are immutable |
| `INV-HIST-01` | Financial history is never edited |
| `INV-HIST-03` | Audit records are append-only |

Each requires the **application** role to lack `UPDATE` and `DELETE` on those tables, and a
test asserting that those statements fail for that role. A guarantee held only in application
code is not a guarantee — the next caller is a job, an operator tool, or a psql session.

This implies two roles:

- **Migrator** — owns the schema and its objects, performs DDL, used only by Flyway.
- **Application** — no DDL, and only the DML each table genuinely requires. Append-only
  tables grant `INSERT` and `SELECT` and nothing else.

Grants belong in the migration that creates the table, so a table's privileges arrive with it
rather than being applied later by hand.

**Implemented by `P0-TSK-022`.**

| Role | Is | Holds |
|------|----|-------|
| `finapp_migrator` | The owner of the `platform` schema and every object in it. Flyway connects as this | DDL on its own schema. **Not** a superuser |
| `finapp_app` | What the application connects as | Per-table DML, granted by the migration that creates the table. No DDL, and no access to any `flyway_schema_history` table: its one view of what has been applied is `EXECUTE` on the definer function `platform.applied_migration_versions()` (platform `V013`, `P15-TSK-005`), which returns each schema's successful versions and nothing else, for the readiness guard (§4.1) |
| `finapp_reporting` | What report generation reads as (`P15-TSK-021`, C13) | `SELECT` on the tables a report run reads, granted by a migration of each owning module (accounting `V013`, ledger `V033`, reconciliation `V023`, lending `V034`) and pinned by `ReportingRoleIsSelectOnlyTest`. Nothing else. It must exist before those migrations run: the local role script creates it, and in a deployed database the one-time bootstrap step (`P15-TSK-004`) |

**Roles are provisioned by infrastructure, not by a migration.** A role is a cluster-level
object shared by every database in the cluster, while a migration belongs to one schema in one
database. Creating a role from a migration would have that schema's history claim an object
outside it, would break the second database in the same cluster (CI creates scratch databases to
verify migrations apply to an empty one), and would require the migrator to hold `CREATEROLE` —
the ability to invent roles, which is precisely what a role confined to DDL on one schema should
not have. Locally that provisioning is
[`infra/postgres/initdb/00-roles.sql`](../../infra/postgres/initdb/00-roles.sql); in a deployed
environment it is the one-time bootstrap step of §2.2 *(read "Terraform or a managed-database
operator" until `P15-TSK-004` built the step: neither was going to know the passwords' secret
store or the roles' exact attributes)*.

**Both roles are `NOSUPERUSER`, and that is the load-bearing part.** A superuser ignores every
permission check. Running the application as one does not merely weaken these invariants — it
makes them *untestable*, because an immutability test connecting as a superuser passes whatever
the grants say. `DatabaseRoles.assertCannotBypassPrivileges` asserts the connected role is
neither a superuser nor `BYPASSRLS` before every denial assertion, and pointing the application
credentials at the superuser fails the whole suite.

**Verified, not asserted.** `AuditImmutabilityTest` proves `UPDATE`, `DELETE` and — separately,
because it is a distinct privilege that "we denied DELETE" reasoning misses — `TRUNCATE` all
fail for the application role, along with `DROP TABLE`, `ALTER TABLE` and self-granting.
`ApplicationRoleGrantsTest` reads the granted privileges from the catalogue and checks each
table against the set its design requires, so a grant that is too *wide* fails as loudly as one
that is too narrow.

**Column-level grants are checked separately, because they are invisible to the obvious check.**
PostgreSQL can grant a privilege on a *column* — `GRANT UPDATE (reason) ON audit_record` — and
such a grant does **not** appear in `information_schema.table_privileges` at all. `P0-TST-007`
found the consequence by trying it: the application role rewrote a committed audit record's
justification, from `'original reason'` to `'rewritten after the fact'`, while the entire audit
test suite stayed green. `INV-HIST-03` was violated and nothing detected it.

`reason` is the worst column to lose that way — it is the justification for a privileged action,
and the field anyone covering their tracks would want to change. Two checks close it:
`AuditImmutabilityTest` attempts an `UPDATE` on **every** column, with the column list read from
the catalogue so a column added by a later migration is covered without anyone remembering; and
`ApplicationRoleGrantsTest` asserts that no table's column-level privileges exceed its
table-level grant, for every table rather than only the audit trail — a hole that was invisible
in one place is invisible in all of them.

**Widening the grant is what these tests are measured against.** Both a table-level
`GRANT UPDATE, DELETE` and a column-level `GRANT UPDATE (reason)` were applied to the live table
and the suites re-run: the first fails six tests across two classes, the second two. Before
`P0-TST-007` the column-level widening failed none.

---

## 7. Checklist

Before merging a migration:

- Does not edit any applied migration
- One logical change
- Version number is next in sequence for this module
- Touches only the owning module's schema; no cross-schema foreign keys
- Names the invariants it affects, if any
- Destructive changes: separate migration, justified, ADR where evidence is affected
- Expand-only, or carries a contract marker naming the first release that conforms (§4.1; the build refuses it otherwise)
- Backfills: idempotent, batched, separate from DDL, verified by reconciliation
- Grants stated for any new table, appropriate to its mutability
- Monetary fields declared through `MoneyColumns`, not hand-written
- Applies cleanly to an empty database
- `flywayValidate` passes

---

## 8. Retention and Expiry — `idempotency_record`

Some platform tables are bounded by an expiry rather than kept forever. Financial records are
never among them — `INV-HIST-01` makes financial history permanent — so this section covers the
correctness primitives only.

### Idempotency records (`platform.idempotency_record`)

**Policy: retain for at least as long as any client may retry, and no longer than necessary.**
The default is **24 hours** from creation, set by the caller through `expires_at` rather than by
a schema default, because different commands have different retry windows and a single hardcoded
value would be wrong for most of them.

**The floor is a correctness bound, not a preference.** An idempotency record is what makes a
retry safe. If it expires before the client stops retrying, the next retry finds no record,
claims the key afresh and produces **a second financial effect** — the exact outcome the
mechanism exists to prevent, arriving quietly and long after anyone is watching. So the expiry
of a command's records must exceed:

- the client's own retry schedule, including any exponential backoff and its final attempt;
- any gateway, proxy or SDK retry the client does not control;
- the manual-retry window a support process might use.

When those are unknown, the safe direction is unambiguous: **too long merely costs storage; too
short costs money.**

**The ceiling is real too.** Records accumulate, and an unbounded table degrades the index that
`INV-IDEM-01` depends on. Retention is therefore explicit and swept, not left to grow.

**Sweeping — not built, by ruling.** This record is **not swept**: `P15-TSK-002` (C15, debt row #61)
ruled that `journal_entry.idempotency_scope` is not `UNIQUE`, so the record is posting once-ness's
second rank, and any later sweep lands that uniqueness first (both Phase 16). `P15-TSK-029` swept the
outbox and the inbox and left this table alone, and `NoIdempotencyRecordIsSweptTest` holds that no
production statement deletes from it. Deleting expired records remains the retention job's work when
it comes. No index on `expires_at` is created by `V002`: its right shape depends on the predicate that
sweep uses — a plain btree and a partial index restricted to terminal states have different write costs
— and an index maintained on every insert for a query nobody has written yet is a cost with no benefit.
The task that writes the sweep adds the index its query needs.

**Never sweep a non-terminal record.** An `IN_PROGRESS` claim older than its expiry is not
garbage; it is a command whose outcome is unknown, and deleting it discards the evidence that
something was interrupted mid-flight. Those are resolved by the reclaim path (`P0-TSK-016`),
which is a decision about an unfinished command, not a cleanup.

---

## 9. Retention and Expiry — `inbox_message`

**Policy: retain for longer than any producer or broker may redeliver, and no longer.** The
default is **14 days** from processing (`finapp.inbox.retention`, `P14D`), chosen by the consumer
through the retention duration passed to `InboxConsumer` rather than by a schema default *(this
said 24 hours until the Phase 6 → 7 transition; the wiring's default was never that)*, because different producers have
different redelivery windows and a single value would be wrong for most of them.

**The floor is a correctness bound**, and it is the same shape of argument as §8 with a different
actor. There, the record is what makes a *client's* retry safe. Here it is what makes a
*broker's* redelivery safe. If it expires before redelivery can still occur, the next delivery
finds no record, runs the handler again and produces **a second effect** — the outcome
`INV-IDEM-04` exists to prevent, arriving quietly and long after anyone is watching. So expiry
must exceed:

- the broker's own retry and redelivery schedule, including its dead-letter and replay windows;
- any provider's webhook retry schedule, which for payment providers is routinely **days**;
- any manual replay a support process might perform *(`P15-TSK-028`: a replay is an audited door now, refused at or past this
  window, and the order itself extends every consumer's row a full retention, so a row outlives the record it was
  ordered to protect; `expires_at` is the one column of this table the application may update, `V011`);*
- the maximum time a consumer may be down while messages accumulate.

*(Added by `P15-TSK-026`, 2026-11-08.)* For Kafka the first bound is now asserted rather than
estimated: the broker can hand a consumer a record as old as its topic's `retention.ms` **plus one
`segment.ms`** (a segment is deleted only once its newest record is past retention) plus the
broker's retention check interval, and `TopicCatalogueTest` fails unless that sum stays below
`finapp.inbox.retention` for every catalogued topic - 7 days + 1 day + 5 minutes against 14 days
(`EVENT_ARCHITECTURE.md` §Topics, transport security and consumer groups).

When those are unknown the safe direction is the same: **too long merely costs storage; too short
costs money.** A provider that retries a webhook for three days against a 24-hour inbox will
produce a duplicate effect on day two, and nothing in the system will report it.

**The expiry instant is computed by the database, not the caller.** The retention *duration* is a
policy decision and belongs to the consumer; the *instant* is a coordination boundary between the
writer and a sweeper running on another instance, and ADR-0014 requires those to use the one clock
every instance shares. This differs deliberately from `idempotency_record`, whose `expires_at` is
application-supplied — recorded here so the divergence is read as a decision rather than an
inconsistency. An instance whose clock ran slow would otherwise write an expiry already in the
past and have its own dedupe record swept minutes later.

**Sweeping** *(built by `P15-TSK-029`, 2026-11-08; `DATA_RETENTION.md` is the policy)*. A leaderless,
batched sweep deletes a record only past **both** of its bounds: the written `expires_at`, on the
server's clock, and `finapp.inbox.retention` as it stands now, compared with `processed_at`, so that
raising the setting also protects rows already written. The sweep's bound is read from the
`InboxConsumer` bean and has no setting of its own, and that retention is refused at start at or below
the oldest record the broker can redeliver (`RetentionSweep.BROKER_REDELIVERY_HORIZON`, held equal to the
catalogue by `TopicCatalogueTest`; the function refuses such a bound too, `22023`). The
application role has no `DELETE` on this table: platform `V012`'s `SECURITY DEFINER` function is the
one path, `FOR UPDATE SKIP LOCKED` in a materialised CTE, so N sweepers delete disjoint batches.
A replay order extends `expires_at` (platform `V011`), so a row a replay depends on stays. Published
outbox rows go the same way, past `finapp.outbox.retention` (default 30 days, refused below this
window, across which an audited replay reads the outbox), unless a parked record not yet settled still
names the row, because a release republishes it. `V012` adds a plain btree on
`expires_at`: the query orders by it and stops at the batch, which a BRIN cannot serve.

**Every record is terminal, and the sweep does not take that on trust.** Unlike §8, there is no state
column to filter on, because an inbox record has no non-terminal state: it is written in the same
transaction as the effect, so it exists only for messages that were fully handled. That absence is
a consequence of the design in `V007`, not an oversight. A later state would live in a new column
the predicate cannot see, so the sweep's function lists the columns it was reviewed against and stops
when it finds another: a column added to this table needs a migration that says which of its values
are terminal, and `RetentionSweepMigrationTest` goes red until one does.

---

## 10. Retention and Expiry — `identity.session`, and the audit trail's archive

*(Added by `P16-TSK-010`, 2026-11-13; `DATA_RETENTION.md` is the policy.)*

**Sessions.** A session row is operational state (ADR-0030), and a dead one is kept only for diagnosis:
`finapp.identity.session.sweep.grace`, default 30 days past the row's idle bound, refused under a minute
by the application at start and by the function. Identity `V027`'s `SECURITY DEFINER` function is the one
path to deleting a session: it selects only `ACTIVE` or `REVOKED` rows whose `idle_expires_at` passed
more than the grace ago on the database's clock, so a live session, whose idle bound is still ahead, is
never selected, and a state a later migration adds is never selected either. A column of the table it was
not reviewed against stops it (`55000`), as the inbox's does. `FOR UPDATE SKIP LOCKED` in a materialised
CTE; an index on `idle_expires_at`, built in the migration's transaction while no production data exists.
`SessionSweepMigrationTest` reads the predicate and the reviewed columns from the file.

**The audit trail is never swept** (`INV-HIST-03`). Platform `V015` adds its archive: a queue every audit
row joins by trigger in the transaction that writes it, segments cut from that queue with each row in one
segment by a primary key, and the functions that claim, render and mark a segment. Nothing in it deletes an
audit row or grants a delete to anyone. Its trigger is a contract on an old table, waived by its marker
because it refuses no write: it only adds the row's id to the queue. Its backfill queues the rows written
before it while the trigger's lock holds new writes back; on a large table a deployment adds the trigger
first and backfills in batches (§4.4). A column added to `platform.audit_record` stops the archive
(`55000`) until a migration reviews the column and replaces format 1's line and decoder;
`AuditArchiveMigrationTest` holds the reviewed list equal to the table's columns.
