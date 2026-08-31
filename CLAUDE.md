# Fintech Platform — Claude Code Project Instructions

## Mission

This repository is an enterprise fintech reference platform. Treat it as financial infrastructure, not a CRUD application.

Optimize for:
- financial correctness
- auditability
- security
- resilience
- traceability
- reconciliation
- operational clarity
- maintainability
- appropriate scalability

## Source of Truth

Persistent project knowledge lives in repository documentation, not in conversation history.

Read before significant work:
- @docs/product/PRODUCT_VISION.md
- @docs/product/CAPABILITY_MAP.md
- @docs/architecture/BOUNDED_CONTEXTS.md
- @docs/architecture/SYSTEM_ARCHITECTURE.md
- @docs/domain/DOMAIN_MODEL.md
- @docs/domain/FINANCIAL_INVARIANTS.md
- @docs/project/CURRENT_STATE.md
- @docs/project/DECISIONS.md

For domain-specific work, read the relevant domain document and applicable `.claude/rules/` path-scoped rules.

Do not infer undocumented architecture when the repository can answer the question.

## Non-Negotiable Financial Rules

1. Never use floating-point arithmetic for money.
2. Monetary values always include explicit currency and precision semantics.
3. Financial history is immutable; corrections use reversals, adjustments, or compensating entries.
4. Every double-entry journal entry satisfies total debits = total credits.
5. Never create or destroy money by directly mutating a balance without an authoritative financial record.
6. Every money-moving operation has an explicit lifecycle/state machine.
7. Critical money-moving commands are idempotent.
8. Duplicate requests, events, and webhooks are expected and must be safe.
9. External providers are unreliable and may be slow, duplicated, inconsistent, late, or unavailable.
10. Reconciliation is a first-class financial capability.
11. A balance must be explainable from authoritative financial records.
12. Kafka, Redis, caches, search indexes, and projections are not financial truth.
13. Do not weaken a financial invariant merely to make distributed processing easier.

## Domain Distinctions

Do not collapse:
- Identity / Authentication / Authorization / Customer / KYC
- Payment / Transfer / Transaction / Journal Entry
- Authorization / Capture / Clearing / Settlement
- Wallet / Bank Account / Ledger Account / Operational Account
- PSP / Processor / Acquirer / Issuer / Payment Network
- Credit Score / Risk Score / Credit Decision / Underwriting
- Consent / Authentication / Authorization
- Customer Payment / Merchant Settlement

## Architecture

Establish bounded contexts before technical service boundaries.

Do not create a microservice merely because a noun exists. Prefer a modular monolith when it provides a clearer consistency boundary or lower unnecessary complexity.

For important components always establish:
- responsibility
- ownership of state
- transaction boundary
- consistency boundary
- APIs
- events
- failure behavior
- security boundary
- operational responsibility

No shared mutable ownership of the same authoritative state across independent domains.

## APIs and Integration

Financial APIs must explicitly consider:
- idempotency
- retries
- timeouts
- concurrency
- authorization
- auditability
- lifecycle/state
- asynchronous completion
- error semantics

External provider integrations use adapters. Provider-specific details must not leak into the core domain.

## Events

Important events should define:
- event ID
- event type
- aggregate ID
- event version
- schema version
- timestamp
- producer
- correlation ID
- causation ID

Events must tolerate duplication, replay, delayed delivery, and schema evolution.

Kafka is integration infrastructure, not the accounting source of truth.

## Ledger

The ledger is an authoritative financial record.

Do not make:
`balance = balance + amount`
the only financial truth.

Model economic events through explicit financial transactions and double-entry journal entries.

For monetary changes be able to explain:
economic event -> domain operation -> financial transaction -> journal entry -> debit/credit lines -> resulting balances -> settlement -> reconciliation.

## Security and Audit

Security is part of design. Consider authentication, authorization, least privilege, MFA/passkeys, OAuth2/OIDC, service identity, secrets, key management, encryption, tokenization, sensitive-data isolation, and privileged access.

Important financial and administrative actions must be auditable with actor, time, operation, target, reason where applicable, correlation, and outcome.

Application logs are not automatically a regulatory-grade audit trail.

## Failure Engineering

For critical workflows ask what happens when:
- the request times out
- the client retries
- the database commits but the response is lost
- the service crashes
- an event is duplicated
- an event is late or missing
- a provider is unavailable
- a provider returns an unknown state
- a webhook is duplicated
- two requests race
- settlement arrives late
- reconciliation detects a break

Financial correctness must survive these conditions.

## Implementation Workflow

Before significant implementation:
1. Read the relevant architecture/domain docs and current state.
2. Identify bounded context and aggregate/entity.
3. Identify invariants and lifecycle transitions.
4. Identify transaction/consistency boundaries.
5. Identify idempotency behavior.
6. Identify external dependencies and failure modes.
7. Identify security, audit, and reconciliation implications.
8. Inspect existing code and tests.
9. Make the smallest coherent change.
10. Run relevant tests.
11. Update documentation when behavior or architecture changes.
12. Update `docs/project/CURRENT_STATE.md` after meaningful work.
13. Create/update an ADR when an architectural decision changes.

Do not silently redesign unrelated subsystems.

## Working Style

The project owner is intentionally implementing the system manually to develop fintech and system-design expertise.

Act as a principal architect and mentor, not an autonomous code generator.

When a task is non-trivial, explain the domain and design first, then implement.

Challenge designs that violate financial, security, domain, or distributed-systems principles.

## Definition of Done

A significant feature is not complete merely because it compiles.

Review:
- domain correctness
- financial invariants
- persistence
- transaction boundaries
- idempotency
- failure handling
- security
- auditability
- reconciliation
- observability
- tests
- documentation
