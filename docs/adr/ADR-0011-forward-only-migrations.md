# ADR-0011 — Forward-only migrations with module-owned schema history

Status: Proposed

Date: 2026-08-31

## Context

The schema that will hold the ledger must evolve for years without ever leaving ambiguity
about what was applied, or destroying financial evidence.

Ordinary application practice does not transfer. Most teams treat a migration as reversible:
a change goes wrong, you roll it back. That model assumes the data the migration touched is
reproducible. In this platform it is not — `INV-HIST-01` says financial history is never
edited, `INV-LED-03` says posted entries are immutable, and `INV-REC-01` says evidence is
preserved. A "rollback" that drops a column holding financial data is not a rollback; it is
the destruction of records the platform is obliged to keep.

There is also an ownership question. ADR-0006 established schema-per-module with one writer
per table. If migrations were applied centrally, any module could alter any schema, which is
the coupling schema-per-module exists to prevent.

## Decision

**Migrations are forward-only, and each module owns its schema, its migrations and its
migration history.**

- **Tool:** Flyway, pinned to the version Spring Boot manages, so the tool that applies
  migrations and any runtime that validates them agree on the schema history format.
- **Ownership:** a module owning a schema owns its migration files
  (`<module>/src/main/resources/db/migration/<schema>/`) and its own
  `flyway_schema_history` table *inside that schema*. Migration ownership and schema
  ownership are the same thing.
- **Forward-only:** no undo scripts, no down migrations. A mistake is corrected by a new
  migration — structurally the same rule as `INV-REV-01`, where a financial error is
  corrected by a new compensating entry rather than by editing the original.
- **Applied migrations are immutable:** editing one is detected by checksum and fails.
- **In-order only** (`outOfOrder = false`): the schema is a function of the version
  sequence, not of merge timing.
- **No automatic baseline** (`baselineOnMigrate = false`): an unexpected non-empty database
  is something a human must look at.
- **`flywayClean` permanently disabled** (`cleanDisabled = true`): it drops every object in
  the managed schemas. There is no situation in this platform where that is correct.
- **Execution is a deliberate step, not an application side effect.** Migrations are not run
  automatically on application startup: startup migration means uncontrolled DDL, racing
  instances during a rolling deploy, and schema changes nobody decided to make at that
  moment.

Operational conventions — expand/contract, destructive DDL, long-running backfills, locking
— are in [`DATA_MIGRATIONS.md`](../architecture/DATA_MIGRATIONS.md).

## Alternatives Considered

### Option A — Migrations with undo/rollback scripts
Pros: Familiar; a bad change appears to be one command away from being undone.
Cons: The reassurance is mostly false. An undo script is written before the failure it is
meant to handle, is almost never tested against production-shaped data, and cannot restore
data a forward migration destroyed. For financial tables, executing one may itself violate
`INV-HIST-01`. Flyway's undo is also a paid feature, so the capability would be an illusion
in Community edition regardless.

### Option B — Liquibase changelogs
Pros: Database-agnostic; declarative; generates rollbacks automatically.
Cons: The generated-rollback model actively encourages the mental model rejected above. The
XML/YAML abstraction obscures the exact SQL executed, and for a schema under audit the SQL
that ran is the thing that matters. Database-agnosticism has no value here: PostgreSQL is a
fixed decision (`DATA_ARCHITECTURE.md`).

### Option C — Migrations run by the application at startup
Pros: Nothing extra to operate; the schema is always current.
Cons: Concurrent instances race during a rolling deploy. DDL executes as a side effect of a
process starting rather than as a decision someone made. A failed migration becomes a failed
deployment with the schema in an indeterminate state. For a database holding the ledger,
schema change must be an operation with a human decision behind it.

### Option D — Forward-only Flyway, module-owned history (chosen)
Pros: The SQL applied is exactly the SQL in the repository. Checksums make editing history
detectable. Forward-only mirrors the platform's own correction model. Module-owned history
preserves schema ownership. Execution is deliberate.
Cons: Correcting a mistake requires a new migration and cannot be hidden. Destructive
changes need the multi-step expand/contract discipline rather than one statement. Each
schema-owning module needs its own Flyway configuration.

## Consequences

Positive:
- The database's state is always explainable from the repository's migration sequence.
- Editing an applied migration is caught by checksum rather than discovered later as an
  inexplicable difference between environments.
- Schema ownership is enforced by where migrations live and which history table records them.

Negative:
- Removing a column becomes a multi-release exercise, deliberately.
- A mistake is permanently visible in the migration history. That is the intended trade:
  the same trade the ledger makes.
- Adding a schema-owning module means adding a Flyway configuration to it.

Operational impact: migrations run as an explicit step (`:<module>:flywayMigrate`). CI must
apply them to a fresh database and run `flywayValidate` (`P0-TSK-004`).

Security impact: DDL and application runtime need different privileges. The migrating role
owns objects; the application role holds only the DML its tables require, which is what makes
`INV-LED-03`, `INV-HIST-01` and `INV-HIST-03` enforceable at all. That split is **not yet
implemented** — see `DATA_MIGRATIONS.md` §Privilege model.

Financial impact: protects `INV-HIST-01` and `INV-LED-03` from being violated by a schema
change, which is the one route by which immutable history could otherwise be quietly rewritten.

## Invariants / Constraints

`INV-HIST-01`, `INV-HIST-02`, `INV-LED-03`, `INV-HIST-03`, `INV-REC-01`, `INV-MON-05`.

## Follow-up

- `P0-TSK-022`: create the migrator/application role split and prove the privilege-level
  invariants with tests that assert `UPDATE`/`DELETE` fail.
- `P0-TSK-004`: CI applies migrations to a fresh database and runs `flywayValidate`.
- `P0-TSK-035`: Testcontainers applies the same migrations, so tests run against the real
  schema rather than a generated one.
- Revisit if a second schema-owning module makes per-module Flyway configuration burdensome.
