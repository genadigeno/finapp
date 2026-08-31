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

## Data Rules

- Use constraints to enforce important invariants.
- Use unique constraints for idempotency keys and external identifiers where appropriate.
- Version state transitions and externally material changes when reproducibility matters.
- Do not let projections become writable authorities.
- Sensitive data must be classified and protected appropriately.
