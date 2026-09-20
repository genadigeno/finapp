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

Persistent project knowledge lives in repository documentation, not in conversation history —
and **not in this file's context budget**. Nothing below is auto-loaded. Read what the task
needs, and read the *section* you need rather than the whole document.

| Question | Read |
|---|---|
| Where is the project right now? | `docs/project/CURRENT_STATE.md` — §Current Phase, §Current Task, §Next Task |
| What is the next task, and its scope/acceptance/DoD? | `docs/project/BACKLOG.md` — grep the task ID |
| How is work conducted? | `docs/project/EXECUTION_PROTOCOL.md` |
| When is work done? | `docs/project/DEFINITION_OF_DONE.md` |
| What must never be violated? | `docs/domain/FINANCIAL_INVARIANTS.md` — the `INV-*` catalogue |
| What has been decided, and why? | `docs/adr/README.md` → the specific ADR; `docs/project/DECISIONS.md` for the index |
| Which module owns this? | `docs/architecture/MODULE_ARCHITECTURE.md`, `docs/architecture/BOUNDED_CONTEXTS.md` |
| What does this term mean? | `docs/domain/GLOSSARY.md` |
| What is the stack and why? | `docs/architecture/SYSTEM_ARCHITECTURE.md` |
| Multi-instance rules and the component register | `docs/architecture/DISTRIBUTED_EXECUTION.md` |
| Phase gates and exit criteria | `docs/project/PHASE_GATES.md`, `docs/project/PHASE_<n>_PLAN.md` |
| What happened before? | `docs/project/history/` — task, milestone, capability and change records |
| Product scope and capabilities | `docs/product/PRODUCT_VISION.md`, `docs/product/CAPABILITY_MAP.md`, `docs/product/ROADMAP.md` |

Domain-specific work also loads the applicable path-scoped rules in `.claude/rules/`
automatically; the procedures for working a backlog task are skills in `.claude/skills/`.

Do not infer undocumented architecture when the repository can answer the question.
Do not re-read a document already read in this session.

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

## Mandatory Multi-Instance Microservices Rule

This is a true multi-instance microservices system.

Assume every service runs with N concurrent instances.

Never design correctness around a single process, JVM, container, pod, scheduler, consumer, or local memory.

For every shared state and critical operation explicitly consider:

* race conditions;
* concurrent requests;
* atomicity;
* transaction boundaries;
* consistency;
* idempotency;
* duplicate events;
* retries;
* distributed scheduling;
* service failure;
* network failure.

If existing code or architecture documentation does not satisfy this requirement, do not preserve it merely because it already exists.

Update the relevant architecture documentation and ADRs, create remediation work, and refactor the implementation.

A feature is not complete until it remains correct under concurrent execution by multiple instances.

Before completing any critical feature, ask:

"Would this remain correct if 10 instances executed it concurrently?"

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

Work is conducted through the phase-gated protocol in
[`docs/project/EXECUTION_PROTOCOL.md`](docs/project/EXECUTION_PROTOCOL.md), whose Ten Rules and
Working Session Procedure are binding. Every backlog task runs the three-command loop —
design, implement, completion gate — in `.claude/skills/`.

Before significant implementation, establish and state: bounded context and aggregate ·
invariants (`INV-*`) and lifecycle transitions · transaction and consistency boundaries ·
idempotency behaviour · external dependencies and their failure modes · security, audit and
reconciliation implications. Inspect existing code and tests before writing anything.

Then: make the smallest coherent change · run the relevant tests · update documentation and
ADRs where behaviour or architecture changed · update `docs/project/CURRENT_STATE.md`.

Do not silently redesign unrelated subsystems. Do not implement future-phase functionality.
Do not skip or informally pass a phase gate.

## Working Style

The project owner is intentionally implementing the system manually to develop fintech and system-design expertise.

Act as a principal architect and mentor, not an autonomous code generator.

When a task is non-trivial, explain the domain and design first, then implement.

Challenge designs that violate financial, security, domain, or distributed-systems principles.

## Definition of Done

A significant feature is not complete merely because it compiles, and a task is complete only
when its completion gate says so.

The binding criteria are the thirteen dimensions and the task-type profiles
(`DOD-FIN`, `DOD-SEC`, `DOD-API`, …) in
[`docs/project/DEFINITION_OF_DONE.md`](docs/project/DEFINITION_OF_DONE.md): domain correctness ·
financial invariants · persistence · transaction boundaries · consistency · idempotency ·
failure handling · security · audit · reconciliation · observability · testing · documentation.

Each backlog task names the profiles that apply to it. Every one of them must hold, and the
multi-instance question above must be answered `PASS` — `FAIL` or `UNKNOWN` is not complete.
