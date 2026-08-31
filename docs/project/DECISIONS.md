# Project Decisions

Human-readable index of important architectural decisions.

Detailed reasoning belongs in `docs/adr/` — see [`docs/adr/README.md`](../adr/README.md) for
the full index and the list of anticipated decisions.

---

## Accepted So Far

### Financial truth
Immutable double-entry journal postings are the authoritative financial record. Balances are
derived from postings; no balance is an independent authority. → [ADR-0002](../adr/ADR-0002-ledger-authoritative-record.md), [ADR-0009](../adr/ADR-0009-balance-as-projection.md)

### Architecture
Prefer modularity and clear domain ownership; do not create microservices by default. The
platform is built as a modular monolith over one PostgreSQL database, with boundaries
enforced mechanically so that extraction remains feasible. → [ADR-0001](../adr/ADR-0001-modular-monolith.md), [ADR-0006](../adr/ADR-0006-module-boundary-enforcement.md)

### Module boundaries
Each of the 28 bounded contexts maps to exactly one of 24 modules; a context is never split
across modules, because that would give its state two owners. Merges are deliberate and each
records the evidence that would trigger a split — starting merged is the reversible
direction, since splitting a module later is a package move whereas merging two modules that
have both grown authoritative state is not. → [ADR-0012](../adr/ADR-0012-context-to-module-mapping.md),
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)

### Integration
External financial providers are accessed through adapters and treated as unreliable.
Provider vocabulary never enters the domain or a public API contract; unknown provider state
is a modelled state, never an assumed outcome. → [ADR-0008](../adr/ADR-0008-provider-adapters.md)

### State
Important financial lifecycles use explicit state machines. Invalid transitions are rejected
by the domain, not merely made unreachable through the API. → `INV-LIFE-01`, `INV-LIFE-02`

### Money representation
Monetary values are integer minor units with an explicit currency and a stored scale.
High-precision rates are a separate type; converting a rate result to money is an explicit,
named rounding step. → [ADR-0003](../adr/ADR-0003-monetary-representation.md)

### Idempotency
Money-moving commands are made idempotent by a unique database constraint at the financial
boundary — never by a cache or an HTTP-layer filter. Key reuse with a different request is
rejected, not silently accepted. → [ADR-0004](../adr/ADR-0004-idempotency-strategy.md)

### Event publication
Domain facts and their publication records commit in the same transaction via a transactional
outbox; consumers deduplicate via an inbox. Kafka is transport, never the accounting source
of truth. → [ADR-0005](../adr/ADR-0005-transactional-outbox.md)

### Schema evolution
Database migrations are forward-only with no undo scripts: a mistake is corrected by a new
migration, structurally the same rule as correcting a financial error with a compensating
entry. Each schema-owning module owns its migrations and its own migration history table.
Migrations never run as a side effect of application startup. → [ADR-0011](../adr/ADR-0011-forward-only-migrations.md),
[`DATA_MIGRATIONS.md`](../architecture/DATA_MIGRATIONS.md)

### Audit
The audit trail is a dedicated append-only store, immutable at the database privilege level
and written in the same transaction as the action it records. Application logs are not an
audit trail. → [ADR-0010](../adr/ADR-0010-audit-trail.md)

### Delivery process
Work proceeds phase by phase through formal entry and exit gates with a defined status model.
A phase is not complete because it compiles. Future-phase functionality is not implemented;
where later capability is structurally needed earlier, the earlier phase defines a seam only.
→ [ADR-0007](../adr/ADR-0007-phase-gated-delivery.md), [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)

### Invariant governance
Sixty-four financial, security and operational invariants are catalogued with stable IDs,
enforcement mechanisms and verification methods. Phases declare the invariants they protect
at the entry gate and prove them by test at the exit gate. →
[`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md)

---

## Deliberately Deferred

Recorded so these are not mistaken for oversights.

| Deferred | Until | Why |
|----------|-------|-----|
| Service extraction | Phase 16 | Requires measurement; anticipating it costs atomicity now (ADR-0001) |
| Kubernetes and Terraform | Phase 15 | Deployment topology should follow a proven architecture, not precede it |
| Real provider connectivity | Never | Adapters plus simulation give the same design pressure without the risk |
| Jurisdiction-specific compliance | Per phase | Jurisdiction-neutral core; specifics behind policy/configuration/adapters |
| Machine-learning risk models | Beyond scope | Versioned rules first; models add reproducibility burden without domain insight |
| Handling raw card data | Never | Tokenised at the boundary; PCI scope deliberately minimised |
