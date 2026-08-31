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

## Data Rules

- Use constraints to enforce important invariants.
- Use unique constraints for idempotency keys and external identifiers where appropriate.
- Version state transitions and externally material changes when reproducibility matters.
- Do not let projections become writable authorities.
- Sensitive data must be classified and protected appropriately.
