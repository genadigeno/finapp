# Data Architecture

## Source-of-Truth Rules

Each authoritative state has one owner.

Preferred roles:
- PostgreSQL: transactional source of truth
- Ledger tables: authoritative financial postings
- Kafka: durable integration/event transport
- Redis: cache, coordination, rate limits, ephemeral state
- Object storage: documents, files, settlement files
- Search index: derived query model
- Analytics warehouse/database: derived analytical model

## Schema Evolution

Schema change is governed by [ADR-0011](../adr/ADR-0011-forward-only-migrations.md) and the
conventions in [`DATA_MIGRATIONS.md`](DATA_MIGRATIONS.md). Migrations are forward-only, each
module owns its schema and its migration history, and a change to a financial table is
treated as a financial operation: history is not edited, corrections are new forward steps,
and evidence is preserved.

## Data Access

Authoritative writes and aggregate loads use **explicit SQL through `JdbcClient`** — no
object-relational mapper, no persistence context (ADR-0033). The reason is the privilege model
above rather than a preference: `INV-HIST-03`, `INV-HIST-01` and `INV-LED-03` are enforced by the
application role holding no `UPDATE` and no `DELETE`, which is only meaningful while nothing emits
a statement nobody wrote.

The unit of work is a JDBC `Connection`, transactions are begun explicitly, and
`NoObjectRelationalMapperTest` fails the build if an ORM artefact reaches the application's runtime
classpath. This decision governs **authoritative state only**; derived read models and reporting
projections are Phase 14's decision.

## Data Rules

- Use constraints to enforce important invariants.
- Use unique constraints for idempotency keys and external identifiers where appropriate.
- Version state transitions and externally material changes when reproducibility matters.
- Do not let projections become writable authorities.
- Sensitive data must be classified and protected appropriately. The scheme is
  [`DATA_CLASSIFICATION.md`](DATA_CLASSIFICATION.md) (ADR-0022): five levels, classified **per
  column at its ceiling** rather than at what the column holds today, because a column cannot be
  reclassified once it has data. `ColumnClassificationTest` fails the build if a migration adds a
  column the register does not classify.
