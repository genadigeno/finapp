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
5. Add the module's `flywayMigrate` / `flywayValidate` to the CI sequence.

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
deliberate step.

Connection details default to the local Compose stack and are overridden by `FINAPP_DB_URL`,
`FINAPP_DB_USER` and `FINAPP_DB_PASSWORD`.

`flywayClean` is permanently disabled. To reset local state, discard the container volume
with `docker compose down -v`.

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

> **Not yet implemented.** Local development currently connects as the cluster superuser
> `finapp`, which is both migrator and application role. That is acceptable for bootstrapping
> a container and nowhere else, and it means the privilege-level invariants cannot yet be
> exercised. Closing this is `P0-TSK-022`, and it must be closed before any table those
> invariants apply to is created. Tracked in `CURRENT_STATE.md`.

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
