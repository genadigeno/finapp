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
to the Job by mistake, stops it (a managed database's master user is no superuser, but it holds `CREATEROLE`). It takes no
arguments, so nothing can ask it to clean. Flyway's `FLYWAY_*` variables and configuration files are not read.

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
| `11` | A lock was not granted within the bound, or the database was unavailable | Retried, up to `backoffLimit` |
| `12` | The configuration or identity was refused; a credential the server refused; a database that is not there. Nothing ran | `FailJob` |
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

### 2.2 The one-time bootstrap step: the deployed roles

*(Added by `P15-TSK-004`, 2026-11-08.)* Roles are infrastructure, never Flyway (§6): the migrator is `NOCREATEROLE`, and the
per-table grants stay in the migrations. So a deployed database gets its roles from a separate step, what
`infra/postgres/initdb/00-roles.sql` does for a laptop, **run once per database, before the first migration Job, and again only
when a release adds a role**. Never part of the Job: it is the migrator image with its `command` overridden, in a Job or a
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
alone, password included (rotation is a procedure of its own); one holding a right it must not hold stops the step (exit `12`),
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
(for example, after a failed non-transactional migration), and record why.

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

### 4.2 Destructive DDL

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

### 4.4 Locking

PostgreSQL supports transactional DDL, so Flyway runs each migration in a transaction and a
failure rolls back. Two exceptions matter:

- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction. It needs a migration marked
  non-transactional, and such a migration can leave an invalid index behind if it fails,
  which must be found and dropped before retrying.
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
| `finapp_app` | What the application connects as | Per-table DML, granted by the migration that creates the table. No DDL, no access to the migration history |
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

**Sweeping.** Deleting expired records is the retention job's work, not the schema's. No index on
`expires_at` is created by `V002`: its right shape depends on the predicate that sweep uses — a
plain btree and a partial index restricted to terminal states have different write costs — and an
index maintained on every insert for a query nobody has written yet is a cost with no benefit.
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
- any manual replay a support process might perform;
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

**Sweeping.** Deleting expired records is the retention job's work, not the schema's, and no index
on `expires_at` is created by `V007` — for the reason §8 gives: the sweep's predicate is not
written yet, and a plain btree, a BRIN and a partial index have different write costs on a table
whose entire traffic is inserts. The task that writes the sweep adds the index its query needs.

**Every record is terminal.** Unlike §8, there is no "never sweep a non-terminal record" caveat,
because an inbox record has no non-terminal state: it is written in the same transaction as the
effect, so it exists only for messages that were fully handled. That absence is a consequence of
the design in `V007`, not an oversight.
