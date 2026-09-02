# Engineering Backlog

Hierarchy: **Epic → Capability → Feature → Technical Task / Test Task / Documentation Task**

## ID Scheme

```
P<phase>-EPIC-<nn>    Epic
P<phase>-CAP-<nn>     Capability
P<phase>-FEAT-<nn>    Feature
P<phase>-TSK-<nnn>    Technical task
P<phase>-TST-<nnn>    Test task
P<phase>-DOC-<nnn>    Documentation task
```

IDs are permanent. A cancelled item is marked `CANCELLED`, never reused or renumbered.

## Field Definitions

Every task carries: **Context** (bounded context / module), **Description**, **Why**
(rationale), **Deps**, **Accept** (acceptance criteria), **Risk**, **Cx** (complexity:
S / M / L / XL), **DoD** (profile from
[`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) §Profiles).

## Progressive Elaboration Rule

Decomposing all seventeen phases to task granularity now would produce fiction: the tasks
for Phase 9 depend on decisions that Phase 3 has not yet made. This backlog is therefore
elaborated to the depth that is currently *knowable*:

| Phases | Depth | Elaborated |
|--------|-------|-----------|
| 0 | Epic → Capability → Feature → Task / Test / Doc | Now |
| 1 | Epic → Capability → Feature | Now; tasks at Phase 1 entry gate |
| 2–4 | Epic → Capability | Now; features at phase entry gate |
| 5–16 | Epic | Now; capabilities at phase entry gate |

**Entry-gate requirement 10** in [`PHASE_GATES.md`](PHASE_GATES.md) makes full task-level
decomposition mandatory before a phase may become `READY`. Elaborating a phase is itself
the first activity of its entry gate.

---

# Phase 0 — Domain and Architecture Foundation

Status: `IN_PROGRESS`

## P0-EPIC-01 — Build and Repository Foundation

*Rationale: nothing can be verified until there is a reproducible, CI-verified build.*

### P0-CAP-01 — Reproducible build and local infrastructure

#### P0-FEAT-01 — Gradle multi-module Spring Boot build

**P0-TSK-001 — Initialise Gradle multi-module build** — `COMPLETE` (2026-08-31)
- Context: platform / build
- Description: Gradle wrapper, root build with version catalog, Java toolchain pinned, Spring Boot BOM, one placeholder application module.
- Why: A pinned, reproducible toolchain is a prerequisite for every later correctness claim.
- Deps: none
- Accept: `./gradlew build` succeeds from a clean clone on a machine with no prior state; Java version pinned by toolchain, not by ambient `JAVA_HOME`; dependency versions centralised in a version catalog.
- Risk: Low
- Cx: M
- DoD: `DOD-BUILD`

**P0-TSK-002 — Create module skeleton** — `COMPLETE` (2026-08-31)
- Context: platform
- Description: Create `platform`, `sharedkernel` and `app` modules with declared dependency direction: `app → platform → sharedkernel`, never the reverse.
- Why: Boundaries must exist before code does; retrofitting them is the expensive path.
- Deps: P0-TSK-001
- Accept: Modules build independently; reverse dependency fails compilation; `sharedkernel` has no Spring Framework dependency.
- Risk: Medium — an over-large shared kernel becomes a coupling sink.
- Cx: M
- DoD: `DOD-BUILD`

**P0-TSK-003 — Local infrastructure via Docker Compose** — `COMPLETE` (2026-08-31)
- Context: platform / ops
- Description: PostgreSQL, Kafka and Redis with pinned image versions, named volumes, health checks.
- Why: Local behaviour must match the integration-test infrastructure.
- Deps: P0-TSK-001
- Accept: `docker compose up` yields all three healthy; versions match those used by Testcontainers; no credentials committed beyond local-only development defaults clearly marked as such.
- Risk: Low
- Cx: S
- DoD: `DOD-BUILD`

**P0-TSK-004 — CI pipeline** — `COMPLETE` (2026-08-31)
- Context: platform / build
- Description: CI running build, unit tests, integration tests, architecture tests, dependency scan and secret scan on every change.
- Why: A gate that is not automated is a gate that will be skipped.
- Deps: P0-TSK-001, P0-TSK-002
- Dependency correction (2026-08-31 review): previously declared `P0-TSK-011` (Money persistence mapping) and `P0-TSK-036` (test taxonomy). Neither is required to run a build with its tests, and both are scheduled after several tasks that carry `DOD-BUILD` — whose "CI green" criterion therefore could not be satisfied by any of them. The over-specified dependency, not the work itself, was the blocker. CI runs whatever tests exist and gains steps as later tasks add them.
- Accept: Pipeline green on a clean clone; a deliberately introduced boundary violation fails CI; a deliberately committed dummy secret fails CI.
- Risk: Low
- Cx: M
- DoD: `DOD-BUILD`

**P0-TSK-005 — Database migration tooling** — `COMPLETE` (2026-08-31)
- Context: platform / data
- Description: Flyway (or equivalent) with schema-per-module naming, forward-only migrations, and a documented convention for irreversible financial migrations.
- Why: Financial schemas must evolve without ambiguity about applied state.
- Deps: P0-TSK-003
- Accept: Migrations apply cleanly to an empty database; checksum drift fails the build; schema-per-module namespacing enforced by naming convention and reviewed.
- Risk: Medium — a bad migration convention is painful once financial data exists.
- Cx: M
- DoD: `DOD-BUILD`

**P0-DOC-001 — Build and local development guide** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: `README` covering prerequisites, build, run, test, and infrastructure lifecycle.
- Why: Reproducibility is part of operability.
- Deps: P0-TSK-001..005
- Accept: A reader following only this document reaches a green build and running infrastructure.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`

---

## P0-EPIC-02 — Module Architecture and Boundary Enforcement

*Rationale: `CLAUDE.md` requires domain ownership before service decomposition. Ownership is only real if violating it fails the build.*

### P0-CAP-02 — Enforced module boundaries

#### P0-FEAT-02 — Architecture rules as executable tests

**P0-TSK-006 — Define the context-to-module map** — `COMPLETE` (2026-08-31)
- Context: architecture
- Description: Map each bounded context in `BOUNDED_CONTEXTS.md` to a planned module, with authoritative state ownership, transaction boundary, consistency boundary and security boundary.
- Why: `CLAUDE.md` mandates these be established for every important component.
- Deps: none
- Accept: `MODULE_ARCHITECTURE.md` records all nine boundary attributes for every planned module; no state has two owners.
- Correction (2026-08-31): this criterion said "eight" attributes. `CLAUDE.md` §Architecture lists **nine** — responsibility, ownership of state, transaction boundary, consistency boundary, APIs, events, failure behaviour, security boundary, operational responsibility. `CLAUDE.md` is the authority, so the map records nine.
- Risk: Medium
- Cx: L
- DoD: `DOD-ARCH`

**P0-TSK-007 — ArchUnit boundary rules** — `COMPLETE` (2026-08-31)
- Context: platform
- Description: Rules forbidding cross-module internal access, cross-module entity references, reverse dependencies, and framework leakage into `sharedkernel`.
- Why: Boundary rules that are documented but unenforced decay within weeks.
- Deps: P0-TSK-002, P0-TSK-006
- Accept: Rules pass on the current codebase; each rule is proven by a deliberately introduced violation that fails the build, then reverted.
- Risk: Medium — rules that are too strict early cause churn; too loose and they are worthless.
- Cx: M
- DoD: `DOD-TEST`

**P0-TSK-008 — No-floating-point-money static rule** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: An architecture test forbidding `float`/`double`/`Float`/`Double` in any monetary type, field, parameter or return in financial packages.
- Why: `INV-MON-01` is the most fundamental rule in the platform and must be mechanically enforced.
- Deps: P0-TSK-007, P0-TSK-009
- Accept: Rule fails the build when a `double` monetary field is introduced; passes otherwise.
- Risk: Low
- Cx: S
- DoD: `DOD-TEST`

**P0-DOC-002 — MODULE_ARCHITECTURE.md** — `COMPLETE` (2026-09-01)
- Context: architecture
- Description: Document the module cut, ownership, boundaries and the rules enforcing them.
- Why: The architecture baseline is a durable reference, not a conversation artefact.
- Deps: P0-TSK-006
- Accept: Document exists and matches the enforced ArchUnit rules exactly.
- Risk: Low
- Cx: M
- DoD: `DOD-DOC`

---

## P0-EPIC-03 — Financial Kernel: Money

*Rationale: every monetary defect in the platform's future either originates here or is prevented here.*

### P0-CAP-03 — Correct monetary representation and arithmetic

#### P0-FEAT-03 — Money value type

**P0-TSK-009 — Implement `Money` and `CurrencyCode`** — `COMPLETE` (2026-08-31)
- Context: sharedkernel
- Description: Immutable `Money` holding minor units, ISO-4217 currency and scale; arithmetic that rejects currency mismatch and overflow; no floating point anywhere; no default currency.
- Why: `INV-MON-01`, `INV-MON-02` — currency is always explicit and money is never binary floating point.
- Deps: P0-TSK-002
- Accept: Currency mismatch throws a domain exception, never coerces; overflow is rejected rather than wrapping; construction requires an explicit currency; the type is immutable and has no public mutator.
- Risk: **High** — a design error here propagates into every table and every posting.
- Cx: L
- DoD: `DOD-KERNEL`

**P0-TSK-010 — Rounding policy** — `COMPLETE` (2026-08-31)
- Context: sharedkernel
- Description: Explicit named rounding policies; allocation/distribution helper that splits an amount across n parts with zero residual loss.
- Why: `INV-MON-03` — rounding is explicit; `INV-BAL-03` — value is never created or destroyed.
- Deps: P0-TSK-009
- Accept: Splitting any amount across any n reassembles to exactly the original; rounding mode is always caller-specified, never defaulted implicitly.
- Risk: **High** — silent rounding residual is money creation.
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TSK-011 — Money persistence mapping** — `COMPLETE` (2026-08-31)
- Context: platform / data
- Description: Persist as `amount_minor BIGINT NOT NULL`, `currency CHAR(3) NOT NULL`, `scale SMALLINT NOT NULL`; a reusable embeddable and column convention.
- Why: Per ADR-0003; scale is denormalised so a historical amount is interpretable even if currency configuration later changes.
- Deps: P0-TSK-009, P0-TSK-005
- Accept: Round-trip preserves exact value for 0-, 2- and 3-minor-unit currencies; no numeric-type coercion loss; convention documented.
- Risk: High
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TST-001 — `Money` property and edge-case tests** — `COMPLETE` (2026-09-01)
- Context: sharedkernel
- Description: Property-based tests for commutativity/associativity of addition, currency-mismatch rejection, overflow, negative amounts, zero handling, and rounding across JPY (0), USD (2) and BHD (3).
- Why: Kernel correctness must be demonstrated, not assumed.
- Deps: P0-TSK-009, P0-TSK-010
- Accept: Tests fail if rounding, currency checking or overflow handling is deliberately broken.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`

**P0-TST-002 — Allocation zero-residual test** — `COMPLETE` (2026-09-01)
- Context: sharedkernel
- Description: Randomised test asserting that allocating any amount across any 1..100 parts sums exactly back to the original.
- Why: Directly protects `INV-BAL-03`.
- Deps: P0-TSK-010
- Accept: No input produces a residual; test fails if the allocator is changed to naive division.
- Risk: Low
- Cx: S
- DoD: `DOD-TEST`

---

## P0-EPIC-04 — Identity, Time and Correlation Primitives

*Rationale: traceability and reproducibility depend on identifiers and time being controlled, not ambient.*

### P0-CAP-04 — Deterministic identifiers, time and request correlation

#### P0-FEAT-04 — Platform primitives

**P0-TSK-012 — Identifier strategy** — `COMPLETE` (2026-09-01)
- Context: sharedkernel
- Description: Typed identifiers with a time-ordered generation strategy (UUIDv7 or equivalent); typed IDs per aggregate to prevent accidental substitution.
- Why: Random UUID primary keys degrade index locality at ledger volume; untyped IDs invite cross-aggregate mistakes.
- Deps: P0-TSK-002
- Accept: IDs are time-ordered and monotonic enough for index locality; a `CustomerId` cannot be passed where an `AccountId` is required (compile error).
- Risk: Medium
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TSK-013 — Time abstraction** — `COMPLETE` (2026-09-01)
- Context: sharedkernel
- Description: Inject `java.time.Clock` everywhere; forbid `Instant.now()` / `LocalDate.now()` in domain code via an architecture rule. Distinguish posting date, value date and system time.
- Why: Accrual, period close and value-dating cannot be tested or reproduced against ambient clocks.
- Deps: P0-TSK-002, P0-TSK-007
- Accept: Architecture rule fails the build on direct `now()` use in domain packages; tests can advance time deterministically.
- Risk: Medium
- Cx: S
- DoD: `DOD-KERNEL`

**P0-TSK-014 — Correlation and causation context** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Ingress filter establishing `correlationId` (accepted or generated) and `causationId`; propagation into MDC, traces, outbox events and audit records.
- Why: `CLAUDE.md` requires observability across customer → request → domain → provider → ledger → settlement → reconciliation.
- Deps: P0-TSK-002
- Accept: One request produces log lines, a trace and an emitted event all carrying the identical `correlationId`; propagation survives an async handoff.
- **Acceptance corrected (2026-09-01).** As written this criterion could never be met when the
  task runs: it names a trace, an emitted event and an ingress filter, and the tracing exporter
  (`P0-EPIC-09`, M0.4), the outbox (`P0-EPIC-06`, M0.3), the audit store (`P0-EPIC-07`, M0.3)
  and the HTTP surface (`P0-EPIC-08`, M0.4) all arrive in later milestones. Structurally the
  same defect as `P0-TSK-004`'s over-specified dependencies. The clauses verifiable here — log
  lines carrying one identifier, and survival across an async handoff — are met and proven. The
  trace, event and audit clauses transfer to `P0-TST-003`, which must not be attempted before
  those subsystems exist.
- Risk: Medium
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TST-003 — Correlation propagation integration test** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: End-to-end test asserting correlation identity across log, trace, outbox row and audit record.
- **Completed against the sinks that exist, with the rest enforced on arrival (2026-09-01).**
  Previously recorded as blocked until M0.4. Re-examined: two of the four sinks do exist — log
  lines, and the `correlation_id` on `platform.idempotency_record`, which is a persisted sink
  and a real database round trip. `CorrelationPropagationTest` asserts one request's identifier
  reaches **both, identically**, across a thread handoff, and fails when propagation is removed.
  The outbox (`P0-EPIC-06`), audit store (`P0-EPIC-07`) and tracing exporter (`P0-EPIC-09`) do
  not exist to assert against. Rather than defer the task, `CorrelationSinkCoverageTest` fails
  the build when any new platform concern appears without a decision about whether correlation
  reaches it — proven by adding an `outbox` package and watching it fail. The four-sink
  criterion is therefore enforced as the sinks land, instead of depending on someone
  remembering this task existed.
- Why: Correlation gaps are only discovered during incidents unless tested.
- Deps: P0-TSK-014, P0-TSK-019, P0-TSK-022
- Accept: Test fails if propagation is removed from any one of the four sinks.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`

---

## P0-EPIC-05 — Idempotency Kernel

*Rationale: `CLAUDE.md` rule 7 — critical money-moving commands are idempotent. This must exist before any command does.*

### P0-CAP-05 — Idempotent command execution

#### P0-FEAT-05 — Persistent idempotency records

**P0-TSK-015 — Idempotency record schema** — `COMPLETE` (2026-09-01)
- Context: platform / data
- Description: Table keyed on (scope, idempotency key) with request fingerprint, state (`IN_PROGRESS`/`COMPLETED`/`FAILED`), stored response, created/expiry timestamps, unique constraint.
- Why: `INV-IDEM-01`; a database unique constraint is the only reliable arbiter under concurrency.
- Deps: P0-TSK-005
- Accept: Unique constraint prevents a second record; expiry policy documented; scope prevents key collision across different commands.
- Risk: High
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TSK-016 — Idempotent execution wrapper** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Execute-once wrapper: claim the key, execute, persist outcome, replay the stored response on retry. Reject a matching key with a differing request fingerprint.
- Why: Retrying the same command must never create a second financial effect; a *different* command reusing a key must be an error, not a silent success.
- Deps: P0-TSK-015
- Accept: Concurrent identical requests produce one effect and two identical responses; differing fingerprint returns a distinct conflict error; an in-progress claim is handled deterministically rather than deadlocking.
- Risk: **High**
- Cx: L
- DoD: `DOD-KERNEL`

**P0-TSK-017 — `Idempotency-Key` header handling**
- Context: platform / api
- Description: Header extraction, validation, and a policy marking which endpoints require it.
- Why: Idempotency must be a boundary contract, not an internal convenience.
- Deps: P0-TSK-016, P0-TSK-023
- Accept: An endpoint declared as requiring the header rejects requests without one; key format validated; the header is never logged as sensitive data but is recorded in audit.
- **BLOCKED (recorded 2026-09-01).** Two of its three requirements have no subject yet. Its own
  declared dependency `P0-TSK-023` (auditable-action registry) is in `P0-EPIC-07` and not
  started, so "recorded in audit" cannot be satisfied; and "an endpoint declared as requiring
  the header" needs an HTTP surface, which `P0-EPIC-08` introduces in M0.4 — there is no
  servlet, controller or web starter in the build today. Unblocks after `P0-TSK-023` and
  `P0-EPIC-08`. Not a gap in `P0-TSK-016`, which built and proved the mechanism this task will
  expose at the boundary.
- Risk: Medium
- Cx: S
- DoD: `DOD-API`

**P0-TST-004 — Idempotency concurrency and retry tests** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Integration tests: two threads with the same key; retry after simulated response loss; same key with different payload; expired key reuse; process crash while `IN_PROGRESS`.
- Why: The failure modes in `CLAUDE.md` §Failure Engineering apply directly to this component.
- Deps: P0-TSK-016
- Accept: Exactly one effect in every concurrent case; no test relies on timing luck; test fails if the unique constraint is dropped.
- Risk: Low
- Cx: L
- DoD: `DOD-TEST`

---

## P0-EPIC-06 — Reliable Messaging: Envelope, Outbox, Inbox

*Rationale: Kafka is integration infrastructure, not financial truth. Reliable publication must be anchored to the transaction that produced the fact.*

### P0-CAP-06 — Exactly-committed publication and duplicate-safe consumption

#### P0-FEAT-06 — Event envelope

**P0-TSK-018 — Event envelope type** — `COMPLETE` (2026-09-01)
- Context: sharedkernel
- Description: Envelope with `eventId`, `eventType`, `aggregateId`, `aggregateType`, `occurredAt`, `producer`, `eventVersion`, `schemaVersion`, `correlationId`, `causationId`.
- Why: Mandated by `EVENT_ARCHITECTURE.md` and `CLAUDE.md` §Events.
- Deps: P0-TSK-012, P0-TSK-014
- Accept: All ten fields mandatory and non-null at construction; serialisation is stable and versioned.
- Risk: Low
- Cx: S
- DoD: `DOD-KERNEL`

#### P0-FEAT-07 — Transactional outbox

**P0-TSK-019 — Outbox table and writer** — `COMPLETE` (2026-09-01)
- Context: platform / data
- Description: Outbox table written in the *same* transaction as the state change; writer API usable from domain services.
- Why: `INV-EVT-01` — a fact and its publication record commit atomically or not at all.
- Deps: P0-TSK-005, P0-TSK-018
- Accept: Rolling back the business transaction rolls back the outbox row; no code path publishes directly to Kafka bypassing the outbox (enforced by architecture rule).
- Risk: **High**
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TSK-020 — Outbox relay** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Poller publishing unpublished rows in order per aggregate, with retry, backoff, attempt counting and a poison-message path.
- Why: At-least-once delivery must survive crashes and broker unavailability.
- Deps: P0-TSK-019
- Accept: Relay restart after a crash publishes every committed, unpublished row exactly once per successful publish; ordering preserved per aggregate; broker unavailability causes retry, never row loss.
- Risk: High
- Cx: L
- DoD: `DOD-KERNEL`

#### P0-FEAT-08 — Inbox / consumer deduplication

**P0-TSK-021 — Inbox dedupe store and consumer wrapper** — `COMPLETE` (2026-09-01)
- Context: platform / data
- Description: Processed-message table keyed on (consumer, dedupe key) plus a consumer wrapper that skips already-processed messages.
- Why: `CLAUDE.md` rule 8 — duplicate events and webhooks are expected and must be safe.
- Deps: P0-TSK-005
- Accept: Redelivering the same message produces no second effect; dedupe record and side effect commit in one transaction; retention policy documented.
- Risk: High
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TST-005 — Outbox crash-recovery test** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Commit a business transaction, kill the relay before publication, restart, assert the event is published exactly once and carries the original correlation.
- Why: This is the canonical "database commits but the response is lost" scenario.
- Deps: P0-TSK-020
- Accept: Test fails if the outbox write is moved outside the business transaction.
- Risk: Low
- Cx: L
- DoD: `DOD-TEST`

**P0-TST-006 — Duplicate and out-of-order delivery test** — `COMPLETE` (2026-09-01)
- Context: platform
- Description: Deliver the same event twice and deliver events out of order; assert single effect and correct handling.
- Why: `EVENT_ARCHITECTURE.md` delivery assumptions must be demonstrably satisfied.
- Deps: P0-TSK-021
- Accept: One effect per duplicate set; test fails if dedupe is disabled.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`

---

## P0-EPIC-07 — Audit Trail

*Rationale: `CLAUDE.md` — application logs are not automatically a regulatory-grade audit trail.*

### P0-CAP-07 — Append-only, actor-attributed audit

#### P0-FEAT-09 — Audit record store

**P0-TSK-022 — Audit schema and writer** — `COMPLETE` (2026-09-01)
- Context: platform / audit
- Description: Append-only table: actor, actor type, occurred-at, operation, target type/id, reason (where applicable), correlation id, outcome, before/after summary where material.
- Why: Mandated by `CLAUDE.md` §Security and Audit.
- Deps: P0-TSK-005, P0-TSK-014
- Accept: Application database role has `INSERT` and `SELECT` only — `UPDATE` and `DELETE` are denied at the privilege level and this is proven by a test; no sensitive value is stored in clear.
- Risk: **High**
- Cx: M
- DoD: `DOD-KERNEL`

**P0-TSK-023 — Auditable-action registry** — `COMPLETE` (2026-09-01)
- Context: platform / audit
- Description: An explicit registry of action types that must be audited, with a mechanism making omission visible.
- Why: Phase 15 must verify audit completeness against a defined list; that list must start here.
- Deps: P0-TSK-022
- Accept: Registry exists and is referenced by the gate check; adding a privileged action without registering it is detectable in review.
- Risk: Medium
- Cx: S
- DoD: `DOD-KERNEL`

**P0-TST-007 — Audit immutability test** — `COMPLETE` (2026-09-01)
- Context: platform / audit
- Description: Assert `UPDATE` and `DELETE` against the audit table fail with the application role.
- Why: Immutability claimed at the ORM layer is not immutability.
- Deps: P0-TSK-022
- Accept: Test fails if the privilege grant is widened.
- Risk: Low
- Cx: S
- DoD: `DOD-TEST`

---

## P0-EPIC-08 — API Conventions and Error Contract

### P0-CAP-08 — Consistent, evolvable API surface

#### P0-FEAT-10 — API baseline

**P0-TSK-024 — Error contract** — `COMPLETE` (2026-09-01)
- Context: platform / api
- Description: RFC 9457 problem-detail responses with a stable machine-readable error code taxonomy; no stack traces or internal detail exposed.
- Why: `.claude/rules/api-design.md` — explicit API error contracts.
- Deps: P0-TSK-002
- Accept: Every error path returns the contract shape; no internal exception message or stack trace reaches a client; error codes are enumerated and documented.
- Risk: Medium
- Cx: M
- DoD: `DOD-API`

**P0-TSK-025 — Request validation at the boundary** — `COMPLETE` (2026-09-01)
- Context: platform / api
- Description: Declarative validation with rejection before any domain invocation; all external input treated as untrusted.
- Why: `.claude/rules/security.md` and `api-design.md`.
- Deps: P0-TSK-024
- Accept: Malformed and oversized payloads are rejected with the error contract; validation failures never reach domain code.
- Risk: Low
- Cx: S
- DoD: `DOD-API`

**P0-TSK-026 — API versioning and OpenAPI generation** — `COMPLETE` (2026-09-02)
- Context: platform / api
- Description: Versioning strategy, deprecation policy, OpenAPI generated in the build.
- Why: Backwards-compatible evolution is a stated API rule.
- Deps: P0-TSK-024
- Accept: OpenAPI document generated on build; versioning strategy documented; a breaking change is detectable.
- Risk: Low
- Cx: M
- DoD: `DOD-API`

**P0-TSK-027 — Health, readiness and info endpoints** — `COMPLETE` (2026-09-02)
- Context: platform / api
- Description: Liveness, readiness (including dependency checks) and build-info endpoints. No business endpoints.
- Why: Operability baseline; readiness must reflect real dependency state.
- Deps: P0-TSK-003
- Accept: Readiness fails when PostgreSQL is unavailable; endpoints expose no sensitive configuration.
- Risk: Low
- Cx: S
- DoD: `DOD-API`

**P0-DOC-003 — API conventions document** — `COMPLETE` (2026-09-02)
- Context: platform / api
- Description: Versioning, errors, pagination, idempotency, correlation headers, deprecation.
- Why: Convention drift across contexts is expensive to reverse.
- Deps: P0-TSK-024..027
- Accept: Document matches implemented behaviour.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`

---

## P0-EPIC-09 — Observability Baseline

### P0-CAP-09 — Correlated traces, metrics and structured logs

#### P0-FEAT-11 — Telemetry

**P0-TSK-028 — OpenTelemetry tracing** — `COMPLETE` (2026-09-02)
- Context: platform
- Description: Tracing across HTTP, database, Kafka producer and consumer, with correlation on every span.
- Why: `SYSTEM_ARCHITECTURE.md` principle 7.
- Deps: P0-TSK-014
- Accept: A single request produces one connected trace spanning HTTP → DB, with the
  flow correlation identifier on every span.
- **Criterion corrected (2026-09-02).** As written it named four legs, and two of them had no
  subject: there is no broker adapter (`EventPublisher` has no implementation - recorded debt
  owned by Phase 3) and no consumer wiring, so no request can reach an outbox relay or a
  consumer. This is the same correction `P0-TSK-014` needed, and for the same reason: a
  criterion naming components that do not exist can only be satisfied on paper. The outbox
  and consumer legs transfer to the broker adapter (Phase 3), which is where a span could
  first cross a message boundary. The HTTP and DB legs were delivered and proven against a
  live PostgreSQL, and the correlation-on-every-span property - which is what makes any of
  the legs findable - is delivered in full and holds for spans this codebase never writes.
- Risk: Low
- Cx: M
- DoD: `DOD-OBS`

**P0-TSK-029 — Metrics and dashboards** — `COMPLETE` (2026-09-02)
- Context: platform
- Description: Prometheus metrics with a naming convention; baseline Grafana dashboard.
- Why: Metric naming decided late becomes inconsistent across contexts.
- Deps: P0-TSK-003
- Accept: Convention documented; dashboard renders live data from a running instance.
- Risk: Low
- Cx: M
- DoD: `DOD-OBS`

**P0-TSK-030 — Structured logging with redaction** — `COMPLETE` (2026-09-02)
- Context: platform / security
- Description: JSON logging with correlation fields and default-deny redaction for credentials, tokens, PANs and unnecessary PII.
- Why: `.claude/rules/security.md` — never log credentials, tokens, PANs or unnecessary PII.
- Deps: P0-TSK-014
- Accept: A test asserting that a known credential value placed into a logged object does not appear in log output; redaction is opt-out, not opt-in.
- Risk: **High** — logging leaks are a common and severe fintech failure.
- Cx: M
- DoD: `DOD-OBS`

**P0-TST-008 — Log redaction test**
- Context: platform / security
- Description: Assert sensitive markers never appear in emitted log output across all appenders.
- Why: Redaction must be verified, not trusted.
- Deps: P0-TSK-030
- Accept: Test fails if a sensitive field is added without redaction.
- Risk: Low
- Cx: S
- DoD: `DOD-TEST`

---

## P0-EPIC-10 — Security Baseline

### P0-CAP-10 — Secure defaults before any authentication exists

#### P0-FEAT-12 — Security foundations

**P0-TSK-031 — Secret management approach**
- Context: platform / security
- Description: Externalised configuration, no secrets in source or committed config, documented local-development approach.
- Why: `.claude/rules/security.md` — never hard-code secrets.
- Deps: P0-TSK-001
- Accept: No secret value in the repository; secret scanning green; a deliberately committed dummy secret fails CI.
- Risk: High
- Cx: S
- DoD: `DOD-SEC`

**P0-TSK-032 — Security context abstraction**
- Context: platform / security
- Description: `ActorId` / `ActorType` abstraction consumed by audit and domain code; populated by a system actor in Phase 0, by real identity in Phase 1.
- Why: Audit records require an actor from the first posting; retrofitting actor attribution is not possible for historical records.
- Deps: P0-TSK-022
- Accept: Every audit record carries an actor; the Phase 1 identity implementation requires no change to the audit schema.
- Risk: Medium
- Cx: S
- DoD: `DOD-SEC`

**P0-TSK-033 — Data classification scheme**
- Context: platform / security
- Description: Classification levels (public / internal / confidential / restricted-financial / restricted-PII) with handling rules per level.
- Why: `DATA_ARCHITECTURE.md` — sensitive data must be classified and protected appropriately.
- Deps: none
- Accept: Scheme documented and referenced by later data-model tasks.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`

**P0-TSK-034 — Transport and at-rest encryption baseline**
- Context: platform / security
- Description: TLS configuration expectations and at-rest encryption expectations documented and applied where locally applicable.
- Why: Security objectives in `SECURITY_ARCHITECTURE.md`.
- Deps: P0-TSK-003
- Accept: Documented; local setup does not normalise insecure defaults into later environments.
- Risk: Medium
- Cx: S
- DoD: `DOD-SEC`

**P0-TSK-041 — Architecture rule for single-instance assumptions**
- Context: platform / architecture
- Description: An ArchUnit rule failing the build on the mechanically detectable single-instance patterns — `synchronized` methods or blocks, `ReentrantLock`/`Semaphore`, static mutable collections, `ScheduledExecutorService` and ambient scheduling — with a named, justified exemption set for the non-authoritative uses recorded in `DISTRIBUTED_EXECUTION.md` §3.
- Why: ADR-0014 is a design rule today and design rules decay. The `INV-MON-01` and no-ambient-time rules show the pattern works, and the P0-TSK-016 defect shows the assumption is easy to reintroduce.
- Deps: P0-TSK-007, ADR-0014
- Accept: Rule fails the build on a planted `synchronized` block over shared state and on a static mutable collection; passes on the documented non-authoritative uses; each exemption names why it cannot affect correctness.
- Risk: Medium
- Cx: M
- DoD: `DOD-ARCH`

**P0-TST-009 — Multi-instance concurrency test convention**
- Context: platform / test
- Description: A convention and harness for tests that must simulate several instances: separate connections, separate component instances, and separate clocks where a clock participates in the decision.
- Why: The P0-TSK-016 lease defect passed every test because they ran in one JVM with one clock. A concurrency test that shares a connection serialises itself, and one that shares a clock cannot see skew — both look like concurrency tests and prove far less.
- Deps: P0-TSK-016, P0-TSK-035
- Accept: A documented convention plus at least one test proving a clock-skew failure is detectable; existing concurrency tests audited against it.
- Risk: Medium
- Cx: M
- DoD: `DOD-TEST`

**P0-TSK-039 — Dependency verification and locking**
- Context: platform / security / build
- Description: Gradle dependency verification (checksum and/or signature metadata) plus dependency locking, so the set of artefacts the build resolves is pinned and tamper-evident.
- Why: Discovered during `P0-TSK-001`. The Gradle *distribution* is checksum-pinned, but every library the build resolves is currently trusted implicitly. For a platform whose stated posture is financial infrastructure, an unverified supply chain is a real exposure — a compromised or substituted artefact executes with full build privileges. `.claude/rules/security.md` requires treating external input as untrusted; a dependency is external input.
- Deps: P0-TSK-004
- Accept: `gradle/verification-metadata.xml` present and enforced; a deliberately altered artefact checksum fails the build; the procedure for adding or updating a dependency is documented and is not "regenerate everything and hope".
- Risk: Medium — over-strict verification is disruptive to routine upgrades; the update procedure must be practical or it will be bypassed.
- Cx: M
- DoD: `DOD-SEC`

**P0-TSK-040 — Update mechanism for pinned CI actions and scanner images**
- Context: platform / build / security
- Description: An automated path for proposing updates to the commit-SHA-pinned GitHub Actions and digest-pinned scanner images in `.github/workflows/ci.yml` (Dependabot, or an equivalent that raises a reviewable change).
- Why: Discovered during the `P0-TSK-004` review. Pinning to a SHA removes the risk of a tag being repointed, but it also freezes the action: without an update path the pins rot, and a security fix in `actions/checkout` or in a scanner is never picked up. Pinning without maintenance trades one supply-chain risk for another, quieter one.
- Deps: P0-TSK-004
- Accept: An update to a pinned action or image produces a reviewable proposed change rather than requiring someone to remember; the update procedure is documented alongside the pinning rationale.
- Risk: Low
- Cx: S
- DoD: `DOD-SEC`

---

## P0-EPIC-11 — Test Infrastructure

### P0-CAP-11 — Trustworthy verification

#### P0-FEAT-13 — Test foundations

**P0-TSK-035 — Testcontainers integration test harness**
- Context: platform / test
- Description: Reusable PostgreSQL, Kafka and Redis containers with a shared lifecycle and fast startup.
- Why: `.claude/rules/testing.md` — use Testcontainers for real infrastructure behaviour.
- Deps: P0-TSK-003, P0-TSK-005
- Accept: Integration tests run against real infrastructure in CI; no test depends on a developer's local services.
- Risk: Medium
- Cx: L
- DoD: `DOD-TEST`

**P0-TSK-036 — Test taxonomy and conventions**
- Context: platform / test
- Description: Define unit / slice / integration / contract / architecture tiers, naming, tagging, and which tier a given concern belongs to.
- Why: Without a taxonomy, integration coverage of financial behaviour is claimed but not achieved.
- Deps: P0-TSK-035
- Accept: Tiers runnable independently; documented; CI runs all tiers.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`

**P0-TSK-037 — WireMock harness for provider adapters**
- Context: platform / test
- Description: Reusable harness supporting timeout, 5xx, malformed response, delayed response and duplicate-callback simulation.
- Why: Provider failure modes must be testable from Phase 2 onward; building the harness once is cheaper and more consistent.
- Deps: P0-TSK-036
- Accept: Harness can reproduce every failure mode listed in `CLAUDE.md` §Failure Engineering that involves a provider.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`

**P0-TSK-038 — Mutation-style invariant verification convention**
- Context: platform / test
- Description: Convention requiring that each invariant test is demonstrated to fail when the invariant is deliberately broken, with the demonstration recorded.
- Why: Exit gate criterion 3 requires tests that *fail* when the invariant is broken — a test that passes regardless is worse than no test.
- Deps: P0-TSK-036
- Accept: Convention documented and applied to every `P0-TST-*` item.
- Risk: Medium
- Cx: S
- DoD: `DOD-TEST`

---

## P0-EPIC-12 — Documentation and Decision Baseline

### P0-CAP-12 — Durable project knowledge

#### P0-FEAT-14 — Planning and decision records

**P0-DOC-004 — Expand `FINANCIAL_INVARIANTS.md` into an invariant catalog** — *complete*
**P0-DOC-005 — `DEFINITION_OF_DONE.md`** — *complete*
**P0-DOC-006 — `EXECUTION_PROTOCOL.md`** — *complete*
**P0-DOC-007 — `PHASE_GATES.md`** — *complete*
**P0-DOC-008 — `DELIVERY_PLAN.md`** — *complete*
**P0-DOC-009 — ADR-0001..ADR-0010** — *complete (Proposed; ratified to Accepted at the Phase 0 exit gate)*
**P0-DOC-010 — `MODULE_ARCHITECTURE.md`** — *complete*

**P0-DOC-011 — Domain glossary**
- Context: domain
- Description: A precise glossary covering every term in `DOMAIN_MODEL.md`, with explicit statements of what each term is *not*.
- Why: `CLAUDE.md` §Domain Distinctions forbids collapsing these concepts; a glossary makes violations visible in review.
- Deps: none
- Accept: Every term in `DOMAIN_MODEL.md` defined, with the "do not collapse" pairs explicitly contrasted.
- Risk: Low
- Cx: M
- DoD: `DOD-DOC`

**P0-DOC-012 — Phase 0 review record**
- Context: project
- Description: Written phase review per `PHASE_GATES.md` §4.
- Why: Gate criterion 12.
- Deps: all Phase 0 items
- Accept: Review record exists covering all eight review areas; ADRs moved to `Accepted`.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`

---

# Phase 1 — Identity and Customer Foundation

Status: `PLANNED` — features listed; tasks elaborated at the Phase 1 entry gate.

## P1-EPIC-01 — Party and Customer
- **P1-CAP-01** Party registration and lifecycle
  - P1-FEAT-01 Party aggregate and registration
  - P1-FEAT-02 Customer relationship distinct from Party
  - P1-FEAT-03 Party profile read/update with change audit
- **P1-CAP-02** Party data protection
  - P1-FEAT-04 PII classification applied to party data
  - P1-FEAT-05 Party data access authorization

## P1-EPIC-02 — Identity and Credentials
- **P1-CAP-03** Credential management
  - P1-FEAT-06 Argon2id password credential with per-credential parameters
  - P1-FEAT-07 Credential rotation and change flow
  - P1-FEAT-08 Credential compromise handling
- **P1-CAP-04** Identity lifecycle
  - P1-FEAT-09 Identity creation linked to Party
  - P1-FEAT-10 Identity suspension and closure

## P1-EPIC-03 — Authentication
- **P1-CAP-05** Primary authentication
  - P1-FEAT-11 Login with enumeration-safe responses
  - P1-FEAT-12 Brute-force and credential-stuffing controls
- **P1-CAP-06** Multi-factor authentication
  - P1-FEAT-13 TOTP enrolment and verification
  - P1-FEAT-14 WebAuthn / passkey registration and assertion
  - P1-FEAT-15 Step-up authentication mechanism (consumed from Phase 4)
- **P1-CAP-07** Account recovery
  - P1-FEAT-16 Recovery initiation, verification and abuse controls

## P1-EPIC-04 — Session and Device
- **P1-CAP-08** Session management
  - P1-FEAT-17 Session issuance, refresh and rotation
  - P1-FEAT-18 Session listing and immediate revocation
- **P1-CAP-09** Device management
  - P1-FEAT-19 Device registration and trust
  - P1-FEAT-20 Device revocation

## P1-EPIC-05 — Authorization
- **P1-CAP-10** Access control model
  - P1-FEAT-21 Role and permission model
  - P1-FEAT-22 Ownership-scoped resource authorization
  - P1-FEAT-23 Privileged/administrative role separation

## P1-EPIC-06 — Actor-Attributed Audit
- **P1-CAP-11** Audit integration
  - P1-FEAT-24 Real actor populated into the Phase 0 security context
  - P1-FEAT-25 Authentication and authorization events audited
  - P1-FEAT-26 Security metrics and alerting

---

# Phases 2–4 — Epics and Capabilities

Status: `PLANNED` — features and tasks elaborated at each phase's entry gate.

## Phase 2 — KYC/KYB and Consent

| Epic | Capabilities |
|------|-------------|
| P2-EPIC-01 KYC case management | Case lifecycle; verification checks; decision recording; evidence retention |
| P2-EPIC-02 KYB and beneficial ownership | Business verification; ownership graph; control-person identification |
| P2-EPIC-03 Document capture | Secure upload; object storage; encryption; audited access |
| P2-EPIC-04 Screening | Sanctions adapter; PEP adapter; adverse media adapter; hit disposition |
| P2-EPIC-05 Manual review | Review queue; reviewer decision with reason codes; four-eyes on high risk |
| P2-EPIC-06 Consent | Versioned consent text; grant/withdraw lifecycle; consent-dependent capability gating |
| P2-EPIC-07 Onboarding gate | Queryable verification status for downstream contexts |

## Phase 3 — Accounts and Financial Ledger

| Epic | Capabilities |
|------|-------------|
| P3-EPIC-01 Chart of accounts | Account types; normal balance; hierarchy; multi-currency structure |
| P3-EPIC-02 Ledger accounts | Ledger account lifecycle; operational vs customer accounts; suspense accounts |
| P3-EPIC-03 Journal posting | Balanced entry validation; immutable persistence; idempotent posting command; atomic outbox publication |
| P3-EPIC-04 Reversal and adjustment | Reversal referencing original; adjustment with reason codes and four-eyes |
| P3-EPIC-05 Balance derivation | Balance projection; recomputation from postings; continuous verification |
| P3-EPIC-06 Holds | Hold placement against available balance; release; expiry |
| P3-EPIC-07 Customer accounts and wallets | Account product lifecycle; wallet as stored value; account status effects |
| P3-EPIC-08 Statements | Period statements derived from postings |
| P3-EPIC-09 Ledger integrity operations | Trial balance job; imbalance alerting; projection rebuild |

## Phase 4 — Internal Transfers

| Epic | Capabilities |
|------|-------------|
| P4-EPIC-01 Beneficiaries | Beneficiary lifecycle; ownership; new-beneficiary risk seam |
| P4-EPIC-02 Transfer lifecycle | State machine; validation; execution; terminal states |
| P4-EPIC-03 Transfer idempotency | Financial-boundary idempotency; conflict semantics |
| P4-EPIC-04 Transfer accounting | Posting request to ledger; atomicity or compensation |
| P4-EPIC-05 Transfer reversal | Reversal path with compensating postings |
| P4-EPIC-06 Limits and risk seams | Limit check interface; risk decision interface; documented defaults |
| P4-EPIC-07 Transfer history | Transfer query, receipts, correlation to postings |
| P4-EPIC-08 Transfer operations | Stuck-transfer detection; operational query and intervention |

---

# Phases 5–16 — Epics

Status: `PLANNED` — capabilities elaborated at each phase's entry gate.

| Phase | Epics |
|-------|-------|
| 5 Payment Infrastructure | Payment intent; payment attempt; payment methods and tokenisation; provider adapter framework; authorization and capture; refunds; webhook ingestion and dedupe; provider state mapping; unknown-state resolution; payment accounting |
| 6 Checkout and Merchant | Merchant onboarding and KYB integration; merchant accounts; checkout session; order; fee schedule and assessment; merchant payable accounting; merchant payout; merchant reporting; multi-tenant isolation |
| 7 Cards, Wallets, A2A, Instant | Rail abstraction and capability model; card rail; wallet rail; A2A rail; instant-payment rail; rail routing policy; finality and irrevocability handling; dispute lifecycle; chargeback and representment; dispute accounting |
| 8 Settlement and Reconciliation | Settlement expectation tracking; settlement file ingestion; evidence retention; matching engine; tolerance and rule versioning; break classification; break lifecycle and investigation; four-eyes resolution; suspense management; reconciliation reporting |
| 9 FX and Cross-Border | Rate sourcing and staleness; quote lifecycle and rate lock; spread and margin recognition; conversion execution; multi-currency accounting and FX position; rounding residual handling; corridor policy; cross-border payment workflow; FX reconciliation |
| 10 Credit Decisioning | Credit profile; bureau adapter and evidence; affordability assessment; risk scoring; versioned policy engine; decision recording and immutability; reason codes and adverse action; decision reproducibility; exposure tracking |
| 11 Lending | Loan application; offer and expiry; underwriting integration; disbursement; repayment schedule and amortisation; interest accrual; repayment allocation; early settlement; delinquency; restructuring; loan accounting |
| 12 BNPL | Eligibility at checkout; instalment plan; agreement lifecycle; merchant financing; merchant settlement; customer obligation; refund and return adjustment; late fees; BNPL accounting and reconciliation |
| 13 Risk, Fraud, AML | Signal ingestion; versioned rules engine; synchronous risk decisioning; fail-safe policy; limits and velocity; device and behavioural signals; account-takeover detection; AML transaction monitoring; alerting; case management; manual override controls |
| 14 Accounting and Reporting | GL account model; versioned GL mapping; accounting periods; period close with approval; trial balance and continuous verification; GL drill-down; prior-period adjustment; financial statement production; regulatory reporting abstraction; report reproducibility |
| 15 Production Hardening | Threat modelling; security hardening; secret and key rotation; privileged access review; audit completeness verification; rate limiting and quotas; schema registry and compatibility; dead-letter handling; SLO definition and alerting; runbooks and rehearsal; incident response; data retention and deletion |
| 16 Scale and DR | Load characterisation; capacity model; performance testing with invariant assertion; table partitioning and archival; read-replica routing policy; backpressure and load shedding; chaos engineering; database failover; backup and restore rehearsal; DR plan and RPO/RTO; post-recovery financial verification; service extraction evaluation |
