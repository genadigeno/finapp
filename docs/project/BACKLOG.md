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
| 0 | Epic → Capability → Feature → Task / Test / Doc | Complete |
| 1 | Epic → Capability → Feature → Task / Doc | **Elaborated 2026-09-04** by the Phase 0 → Phase 1 transition |
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

**P0-TSK-017 — `Idempotency-Key` header handling** — `COMPLETE` (2026-09-03)
- Context: platform / api
- Description: Header extraction, validation, and a policy marking which endpoints require it.
- Why: Idempotency must be a boundary contract, not an internal convenience.
- Deps: P0-TSK-016, P0-TSK-023
- Accept: An endpoint declared as requiring the header rejects requests without one; key format validated; the header is never logged as sensitive data but is recorded in audit.
- ~~**BLOCKED (recorded 2026-09-01).**~~ Unblocked by `P0-TSK-023` and `P0-EPIC-08`, both of which
  landed in M0.4. The original note is preserved in the change log.
- **Acceptance corrected (2026-09-03).** Two of the three clauses were met as written. The third —
  *"is recorded in audit"* — **still has no subject**, and for a different reason than when the
  task was blocked: the auditable-action registry now exists, but `AuditRecord` has no field for an
  idempotency key and **nothing in Phase 0 writes an audit record in an HTTP flow**. The three
  registered platform actions are outbox operations, and none is emitted. There is nothing to
  record the key *on*.
  Adding a column and a field now would be a schema change nothing populates, for actions that
  never happen — `EXECUTION_PROTOCOL.md` rule 3 (a seam, not future-phase functionality). And no
  history is lost by waiting, which is the test ADR-0010 applies: unlike actor attribution, there
  are no audit records being written today that could never gain the key later.
  **The clause transfers to Phase 4**, the first phase with an audited money-moving action. This is
  the same correction `P0-TSK-014` and `P0-TSK-028` needed, for the same reason: a criterion naming
  a component that does not exist can only be satisfied on paper.
  The *decision* the clause encodes — that the key is not sensitive, is not redacted, and must be
  traceable — is implemented and proven.
- **Outcome:** `@RequiresIdempotencyKey` declares the requirement on a handler or its controller,
  and an **interceptor** enforces it. An interceptor rather than a filter, and that is load-bearing
  twice: a filter runs before the dispatcher has chosen a handler, so it could not know whether
  *this* endpoint declares the requirement without a second copy of the routing table; and a filter
  runs outside `@ExceptionHandler`, so its rejection would be the container's default page rather
  than the error contract — the problem `P0-TSK-025` had to work around by rendering the contract
  by hand inside its filters.
  Rejection happens **before the handler is entered**, asserted by counting handler entries: for a
  money-moving command, the half of the work done before a late rejection is the half that matters.
  New error code `api.IdempotencyKeyRequired` (422), distinct from `api.ValidationFailed` on
  purpose — a client can automate "generate a key and retry" but not "your request was invalid".
  The published contract gained six lines, every difference `COMPATIBLE`.
  **A real gap was found by following `DATA_CLASSIFICATION.md` §5**, which classifies this column
  as a caller-supplied identifier: `IdempotencyKey` bounds length and blankness because those are
  the table's `CHECK` constraints, and carries **no charset** — so a caller could put CR/LF into a
  value the platform logs and stores durably. Closed with the same default-deny charset the
  correlation identifier uses. It is unit-tested rather than driven over HTTP because the JDK's
  own `HttpClient` refuses to *send* CR/LF, and a hostile client writing raw bytes is not bound by
  that politeness.
  Five mutations, all caught.
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

- **Known defect, found during `P0-TSK-032` (2026-09-02), not yet fixed.**
  `RequestValidationTest.aMalformedIdentifierIsReplaced` asserts the issued correlation identifier
  `doesNotContain("bad")`, and a UUIDv7 hex string contains the substring `bad` about **0.7% of the
  time** - roughly one run in 137, observed once. The assertion is right in intent and wrong in
  method: it should assert the issued value is a well-formed generated identifier, not that it
  avoids three substrings that are also valid hex. Left unfixed deliberately - it belongs to
  `P0-TSK-025`, and `EXECUTION_PROTOCOL.md` rule 4 forbids fixing unrelated things opportunistically.

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

**P0-TST-008 — Log redaction test** — `COMPLETE` (2026-09-02)
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

**P0-TSK-031 — Secret management approach** — `COMPLETE` (2026-09-02)
- Context: platform / security
- Description: Externalised configuration, no secrets in source or committed config, documented local-development approach.
- Why: `.claude/rules/security.md` — never hard-code secrets.
- Deps: P0-TSK-001
- Accept: No secret value in the repository; secret scanning green; a deliberately committed dummy secret fails CI.
- Risk: High
- Cx: S
- DoD: `DOD-SEC`
- **Outcome:** all three clauses met, and probing changed the shape of the answer. gitleaks was
  measured rather than trusted: it catches a private key, a high-entropy token and a real-shaped
  AWS pair, and **misses `password: hunter2`** - so "scanning green" does not imply "no secret in
  the repository", and the scanner cannot be the control for clause 1. That control is now
  `CommittedConfigurationHoldsNoSecretTest`, default-deny over discovered configuration files,
  which also single-sources the marked local default across six files in three languages.
  Clause 3 is demonstrated against a throwaway clone with the identical pinned image, never
  against this repository - a dummy secret committed here would make the scan red for ever.
  `DatabaseCredentialGuard` closes the one documented bypass of externalised configuration
  (forgetting to set the variable) by confining the marked default to loopback. ADR-0020.

**P0-TSK-032 — Security context abstraction** — `COMPLETE` (2026-09-02)
- Context: platform / security
- Description: `ActorId` / `ActorType` abstraction consumed by audit and domain code; populated by a system actor in Phase 0, by real identity in Phase 1.
- Why: Audit records require an actor from the first posting; retrofitting actor attribution is not possible for historical records.
- Deps: P0-TSK-022
- Accept: Every audit record carries an actor; the Phase 1 identity implementation requires no change to the audit schema.
- Risk: Medium
- Cx: S
- DoD: `DOD-SEC`
- **Outcome:** the first clause was already met by `P0-TSK-022` - `AuditRecord` cannot be built
  without an actor - so the work was the mechanism and the decision behind it. `SecurityContext`
  carries the actor per flow and across handoffs; **`require()` throws rather than defaulting to
  `Actor.SYSTEM`**, because a default is correct today and silently wrong the moment Phase 1 lands.
  `Actor`/`ActorType` moved to `platform.security` - audit records an actor, it does not own the
  concept. The second clause is a claim about a phase that does not exist and was made falsifiable:
  `Phase1IdentityFitsTheAuditSchemaTest` writes a record as every non-`SYSTEM` type carrying the
  identifier shapes real providers issue (an OIDC `sub`, a directory DN, a service credential),
  through the writer as the application role. ADR-0021.

**P0-TSK-033 — Data classification scheme** — `COMPLETE` (2026-09-02)
- Context: platform / security
- Description: Classification levels (public / internal / confidential / restricted-financial / restricted-PII) with handling rules per level.
- Why: `DATA_ARCHITECTURE.md` — sensitive data must be classified and protected appropriately.
- Deps: none
- Accept: Scheme documented and referenced by later data-model tasks.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`
- **Outcome:** five levels, applied **per column at its ceiling** - the most sensitive thing a
  column may ever hold, not what it holds today - because a column cannot be reclassified once it
  has data. Phase 0 holds nothing sensitive, which is exactly why the scheme is written now (the
  argument ADR-0010 already made for actor attribution). "Referenced by later data-model tasks" is
  enforced rather than hoped: `ColumnClassificationTest` compares the register against the live
  schema in both directions, so a migration adding an unclassified column fails the build - proven
  with a planted `customer_email`. All 46 platform columns classified; handling rules referenced,
  never restated. ADR-0022.

**P0-TSK-034 — Transport and at-rest encryption baseline** — `COMPLETE` (2026-09-02)
- Context: platform / security
- Description: TLS configuration expectations and at-rest encryption expectations documented and applied where locally applicable.
- Why: Security objectives in `SECURITY_ARCHITECTURE.md`.
- Deps: P0-TSK-003
- Accept: Documented; local setup does not normalise insecure defaults into later environments.
- Risk: Medium
- Cx: S
- DoD: `DOD-SEC`
- **Outcome:** the second clause is the whole task, and the insecure default was the driver's own.
  Measured against the local container: `sslmode` unset and `prefer` both **connect unencrypted and
  report nothing**; only `require`/`verify-full` refuse. The platform sets no `sslmode`, which is
  correct locally and a plaintext connection to a remote database in a deployment with nothing
  saying so. `TransportSecurityGuard` refuses to start when a non-loopback database would be
  reached without `verify-full` - not `require`, which encrypts and verifies nothing. Every
  configured source must agree rather than trusting a measured driver precedence. Kafka, Redis and
  inbound HTTP are documented rather than guarded: no client exists for the first two and the
  application is never the TLS endpoint. Nothing is encrypted at rest and nothing needs to be yet;
  expectations recorded with owning phases. ADR-0023.

**P0-TSK-041 — Architecture rule for single-instance assumptions** — `COMPLETE` (2026-09-02)
- Context: platform / architecture
- Description: An ArchUnit rule failing the build on the mechanically detectable single-instance patterns — `synchronized` methods or blocks, `ReentrantLock`/`Semaphore`, static mutable collections, `ScheduledExecutorService` and ambient scheduling — with a named, justified exemption set for the non-authoritative uses recorded in `DISTRIBUTED_EXECUTION.md` §3.
- Why: ADR-0014 is a design rule today and design rules decay. The `INV-MON-01` and no-ambient-time rules show the pattern works, and the P0-TSK-016 defect shows the assumption is easy to reintroduce.
- Deps: P0-TSK-007, ADR-0014
- Accept: Rule fails the build on a planted `synchronized` block over shared state and on a static mutable collection; passes on the documented non-authoritative uses; each exemption names why it cannot affect correctness.
- Risk: Medium
- Cx: M
- DoD: `DOD-ARCH`
- **Outcome:** four rules - `synchronized` (method and block), process-local locks, ambient
  scheduling, static mutable state - with the exemption set being `DISTRIBUTED_EXECUTION.md` §3
  rather than a list the rule keeps for itself. Both criterion mutations proven: a planted
  `synchronized` block and a planted static `ConcurrentHashMap` each fail the build. The block
  check is **not** an ArchUnit rule: ArchUnit models accesses and a block is a `MONITORENTER`
  instruction, verified by probe to be invisible, so it reads bytecode with ASM at test scope.
  **Two defects found by the teeth tests**, both of which would have shipped rules that could not
  fail: `noClasses().should(customCondition)` inverts events - the identical defect `P0-TST-008`
  documented, reproduced one task later - and `haveModifier(SYNCHRONIZED)` on `classes()` checks
  the class rather than its methods. A third was found by the criterion mutation itself: the
  bytecode sweep walked only directories, so a consumed module arriving as a **jar** was never
  scanned, and a count-based vacuity guard could not see it. Coverage is now asserted per module
  from the shared classpath helper. ADR-0024.

**P0-TST-009 — Multi-instance concurrency test convention** — `COMPLETE` (2026-09-02)
- Context: platform / test
- Description: A convention and harness for tests that must simulate several instances: separate connections, separate component instances, and separate clocks where a clock participates in the decision.
- Why: The P0-TSK-016 lease defect passed every test because they ran in one JVM with one clock. A concurrency test that shares a connection serialises itself, and one that shares a clock cannot see skew — both look like concurrency tests and prove far less.
- Deps: P0-TSK-016. ~~P0-TSK-035~~ **removed - a backlog defect, second occurrence of the
  class `P0-TSK-004` found.** Testcontainers changes *where* the database comes from, not
  whether a test can give each simulated instance its own connection, component and clock.
  Every database test already runs against a real PostgreSQL with per-thread connections;
  the convention was expressible today and the dependency would have blocked it for a task
  in another epic.
- Accept: A documented convention plus at least one test proving a clock-skew failure is detectable; existing concurrency tests audited against it.
- Risk: Medium
- Cx: M
- DoD: `DOD-TEST`
- **Outcome:** the audit found the criterion's own subject broken.
  `clockSkewCannotStealALiveClaim` - written with `P0-TSK-016`'s fix to prove skew could not steal
  a live claim - built the "fast" instance's clock from a hard-coded fixture instant that was,
  measured against the running container, about **forty hours behind** the server rather than an
  hour ahead. It passed, and it went on passing when the defect was deliberately reintroduced,
  because a slow instance never believes anything has expired. Corrected to anchor on
  `SELECT now()` via a new `SimulatedInstance` harness, and now **fails** under that
  reintroduction - which is what "a clock-skew failure is detectable" means. Convention and audit
  recorded in `DISTRIBUTED_EXECUTION.md` §5; `DatabaseRoles` moved to a shared test-support package
  so a harness need not live in the audit tests.

**P0-TSK-039 — Dependency verification and locking** — `COMPLETE` (2026-09-02)
- Context: platform / security / build
- Description: Gradle dependency verification (checksum and/or signature metadata) plus dependency locking, so the set of artefacts the build resolves is pinned and tamper-evident.
- Why: Discovered during `P0-TSK-001`. The Gradle *distribution* is checksum-pinned, but every library the build resolves is currently trusted implicitly. For a platform whose stated posture is financial infrastructure, an unverified supply chain is a real exposure — a compromised or substituted artefact executes with full build privileges. `.claude/rules/security.md` requires treating external input as untrusted; a dependency is external input.
- Deps: P0-TSK-004
- Accept: `gradle/verification-metadata.xml` present and enforced; a deliberately altered artefact checksum fails the build; the procedure for adding or updating a dependency is documented and is not "regenerate everything and hope".
- Risk: Medium — over-strict verification is disruptive to routine upgrades; the update procedure must be practical or it will be bypassed.
- Cx: M
- DoD: `DOD-SEC`
- **Outcome:** both controls present and enforced, each proven by mutation - an altered checksum
  fails naming the artefact, a changed locked version fails naming the lock state. The interesting
  part was that locking looked redundant at first, since verification already refuses any artefact
  it does not know; measuring the generated file showed **69 of 342 modules recorded at more than
  one version**, so verification cannot tell a deliberate resolution from drift between versions it
  already trusts. The lockfile can, and it is the only place the thirteen BOM-managed versions
  appear. Trust-on-first-use is stated rather than glossed; PGP was measured (11 signed artefacts,
  49 trusted keys, from one narrow slice) and deferred to Phase 15. The update procedure is three
  steps and a diff review, and regeneration was verified to **merge** rather than rewrite - which is
  what makes it not "regenerate everything and hope". ADR-0025.

**P0-TSK-040 — Update mechanism for pinned CI actions and scanner images** — `COMPLETE` (2026-09-02)
- Context: platform / build / security
- Description: An automated path for proposing updates to the commit-SHA-pinned GitHub Actions and digest-pinned scanner images in `.github/workflows/ci.yml` (Dependabot, or an equivalent that raises a reviewable change).
- Why: Discovered during the `P0-TSK-004` review. Pinning to a SHA removes the risk of a tag being repointed, but it also freezes the action: without an update path the pins rot, and a security fix in `actions/checkout` or in a scanner is never picked up. Pinning without maintenance trades one supply-chain risk for another, quieter one.
- Deps: P0-TSK-004
- Accept: An update to a pinned action or image produces a reviewable proposed change rather than requiring someone to remember; the update procedure is documented alongside the pinning rationale.
- Risk: Low
- Cx: S
- DoD: `DOD-SEC`
- **Outcome:** three mechanisms, because none reaches all of it. Dependabot for the four SHA-pinned
  actions and the two version catalogues; a weekly `check-pinned-images.sh` for the two scanner
  digests, which Dependabot cannot read and which stay in `infra/scanner-pins.sh` deliberately - moving
  them into the workflow so a bot could see them would undo `P0-TSK-031`'s single definition. Each
  pin is now three facts (repository, version, digest) rather than a digest with the version in an
  uncheckable comment, so the check can distinguish a **moved tag** from **rot** - both proven.
  Trivy's digest moved out of a workflow `env:` value into the same record, and both scanners are
  now invoked through `infra/scripts/`. **Running the dependency scan for the first time made it
  fail**: three HIGH/CRITICAL CVEs in the Tomcat Spring Boot 4.1.1 brings. Recorded as a blocker.
  ADR-0026.

---

## P0-EPIC-11 — Test Infrastructure

### P0-CAP-11 — Trustworthy verification

#### P0-FEAT-13 — Test foundations

**P0-TSK-035 — Testcontainers integration test harness** — `COMPLETE` (2026-09-02)
- Context: platform / test
- Description: Reusable PostgreSQL, Kafka and Redis containers with a shared lifecycle and fast startup.
- Why: `.claude/rules/testing.md` — use Testcontainers for real infrastructure behaviour.
- Deps: P0-TSK-003, P0-TSK-005
- Accept: Integration tests run against real infrastructure in CI; no test depends on a developer's local services.
- Risk: Medium
- Cx: L
- DoD: `DOD-TEST`
- **Outcome:** a `LauncherSessionListener` starts one PostgreSQL container per test JVM, applies
  the same `00-roles.sql` and the real migrations, and publishes the coordinates as the system
  properties every test already read - so **no test changed** and all 173 pass with nothing running
  locally. PostgreSQL only: there is no Kafka or Redis client, and a container nothing connects to
  tests nothing. Putting the harness in `testFixtures` so `app` could use it exposed it to the
  ArchUnit sweep, and two rules fired - a static container field and a field named
  `LOCAL_PASSWORD`; **both fixed at source rather than exempted**. `HealthReadinessDatabaseTest`
  had to change and its own comment had predicted why: it checked the test classpath as a proxy for
  the runtime one, and now checks the runtime classpath directly. ADR-0027.

**P0-TSK-036 — Test taxonomy and conventions** — `COMPLETE` (2026-09-03)
- Context: platform / test
- Description: Define unit / slice / integration / contract / architecture tiers, naming, tagging, and which tier a given concern belongs to.
- Why: Without a taxonomy, integration coverage of financial behaviour is claimed but not achieved.
- Deps: P0-TSK-035
- Accept: Tiers runnable independently; documented; CI runs all tiers.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`
- **Outcome:** four tiers — unit, architecture, slice, database — defined by **what a test needs
  in order to run**, which is the only axis on which membership can be decided mechanically. Each
  is its own task and the tiers partition the hermetic suite exactly (418 + 54 + 68 = 540 = `test`),
  so `build` still runs all three hermetic ones and nothing left CI's coverage as a side effect of
  the split. `unitTest` is ~14s against `build`'s minute.
  **Two of the five names the description asks for are deliberately not tiers**, with the reason
  recorded rather than dropped: `contract` is a *kind* whose members have different requirements —
  `OpenApiContractTest` needs a Spring context, `ColumnClassificationTest` needs a database — and
  grouping two requirements under one name is the one thing a tier must not do; `integration` is
  replaced by `database`, which says what is integrated with, so a Kafka client gets its own tier
  instead.
  The split multiplies the ways to make the `:platform:databaseTest` mistake the `P0-TSK-027`
  review found, so it ships with `TestTaxonomyTest` holding the Gradle declaration, `TestTier`,
  every class's tag, `TESTING.md` and CI to each other — **nine mutations, all caught**, though
  only after the first one **survived** and exposed a real defect: the sweep reads sibling modules'
  test classes from disk and nothing had told Gradle that, so it read a stale class file. Also
  found by its own guards: `ModuleBoundaryRulesTest` — the oldest rule suite here — was skipped
  entirely, because ArchUnit executes `@ArchTest` **fields** and it declares no `@Test` method;
  and `MoneyTest` was skipped because every test method lives in a `@Nested` class. Detection was
  narrowed from `java.sql` to **acquisition** after a false positive on a rule suite whose fixture
  merely mentions `Connection`. Also pays down the recorded duplication: thirteen test classes
  opened connections through private helpers, all now on `DatabaseRoles`. **Review added two
  guards**, both found by asking what CI actually runs: `build` executes `test` and never the tier
  tasks, and an empty tier task passes in a second having selected nothing — so `noTierIsEmpty`;
  and an unrecognised `@Tag` is ignored rather than rejected, so the vocabulary is now closed.
  ADR-0028.

**P0-TSK-037 — WireMock harness for provider adapters** — `COMPLETE` (2026-09-03)
- Context: platform / test
- Description: Reusable harness supporting timeout, 5xx, malformed response, delayed response and duplicate-callback simulation.
- Why: Provider failure modes must be testable from Phase 2 onward; building the harness once is cheaper and more consistent.
- Deps: P0-TSK-036
- Accept: Harness can reproduce every failure mode listed in `CLAUDE.md` §Failure Engineering that involves a provider.
- Risk: Low
- Cx: M
- DoD: `DOD-TEST`
- **Outcome:** `SimulatedProvider`, in two halves, because **a provider is unreliable in both
  directions** and a simulator with only the first cannot reach the modes that cost the most.
  Outbound is a real HTTP server we call — timeout, unavailable, 5xx, delayed, malformed body,
  garbage, unknown state, retry sequence, and the request that is *received* before the response is
  lost. Inbound is the provider calling **us**: a duplicated webhook (`INV-IDEM-04`) and a late
  settlement (`INV-SET-03`) are the provider acting on its own schedule, and no stubbing of its API
  reproduces them.
  **The acceptance criterion is a checkable claim, so it is checked** — `ProviderFailureCoverageTest`
  holds three links that must all hold: every bullet in `CLAUDE.md` §Failure Engineering is
  classified as a provider concern or explicitly not one (with the reason and where it *is*
  covered); every provider concern names a harness method that **exists**; and every such method is
  **actually called** by the suite that proves the harness. The third link is what stops a mode
  being covered on paper. Seven mutations, all caught.
  **Review found one important defect**, by asking what the first real user would do rather than by
  reading: every stub was bound to a single HTTP method, so an adapter POSTing to create a payment
  got a **404 from a stub claiming the provider succeeds** — the worst shape for the failure, since
  a 404 reads as "wrong path". Every mode is now verb-agnostic, with a regression test. Review also
  bounded the ADR check to its one sentence — "timeout" appears three times in ADR-0008, so
  deleting the contract-test requirement had left the check green — and closed a silent `int`
  overflow on long delays.
  **Two defects, both found by the guard's own assertions**: the section regex read straight past
  `## Failure Engineering` into `## Definition of Done` and returned "auditability" as a failure
  mode, because `DOTALL` lets `.` match newlines — replaced by line-walking with an explicit stop,
  which cannot over-read; and a literal match on ADR-0008 reported that it had stopped requiring
  "malformed response", when the ADR simply **wraps mid-phrase**.
  WireMock is the **standalone** artefact, measured rather than assumed: it relocates Jetty and
  Jackson under `wiremock/`, so a harness cannot change the servlet container Spring Boot picks for
  every `@SpringBootTest` in `app` — and it resolves to exactly **one** lockfile entry. `unit` tier,
  decided on the measured 1.3s. No new ADR: this is ADR-0008's own recorded follow-up.

**P0-TSK-038 — Mutation-style invariant verification convention** — `COMPLETE` (2026-09-03)
- Context: platform / test
- Description: Convention requiring that each invariant test is demonstrated to fail when the invariant is deliberately broken, with the demonstration recorded.
- Why: Exit gate criterion 3 requires tests that *fail* when the invariant is broken — a test that passes regardless is worse than no test.
- Deps: P0-TSK-036
- Accept: Convention documented and applied to every `P0-TST-*` item.
- Risk: Medium
- Cx: S
- DoD: `DOD-TEST`
- **Outcome:** [`MUTATION_TESTING.md`](MUTATION_TESTING.md) — the convention, plus a register with
  a row for all **17** Phase 0 invariants and all **9** `P0-TST-*` items. The criterion is the
  narrower of the two obligations: `PHASE_GATES.md` criterion 3 asks for every in-scope `INV-*` to
  have a test that fails when it is broken, so the register covers that too and
  `MutationDemonstrationTest` checks it on every build — which converts criterion 3 from something
  a human verifies once at the gate into something the build verifies continuously.
  **A demonstration has two admissible forms**, and the distinction is the point: an *in-suite*
  proof runs on every build and cannot rot, while a *recorded* procedure proves the test had teeth
  on the day it was written. The register labels each, and the guard verifies that an in-suite row
  names a **method that still exists** — so a claim of continuous proof cannot point at something
  renamed away.
  **The audit found a real gap.** `INV-MON-05` had a test and **no recorded demonstration**; its
  identifier appeared nowhere in the project's records. Closed by performing the demonstration:
  re-deriving the scale from the currency in `MoneyColumns.read` fails **exactly one** test, and
  notably *not* the general round-trip test — which writes amounts whose scale already matches the
  currency's current minor units, so re-derivation gives the same answer. That is why the register
  names a **method** and not just a class.
  Seven mutations, all caught, each by the intended assertion; review added two guards and closed
  one register row whose observed result had been inferred rather than recorded, bringing it to
  nine. Closes `P0-EPIC-11`.

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

**P0-DOC-011 — Domain glossary** — `COMPLETE` (2026-09-03)
- Context: domain
- Description: A precise glossary covering every term in `DOMAIN_MODEL.md`, with explicit statements of what each term is *not*.
- Why: `CLAUDE.md` §Domain Distinctions forbids collapsing these concepts; a glossary makes violations visible in review.
- Deps: none
- Accept: Every term in `DOMAIN_MODEL.md` defined, with the "do not collapse" pairs explicitly contrasted.
- Risk: Low
- Cx: M
- DoD: `DOD-DOC`
- **Outcome:** [`GLOSSARY.md`](../domain/GLOSSARY.md) — 62 terms, each with an `Is:`, a `Not:` and
  the module that will own it, plus all eight distinction groups contrasted under headings that
  repeat `CLAUDE.md`'s wording exactly.
  **Comparing the two lists mechanically found they disagree**: seven terms are forbidden from
  being collapsed that the canonical list never names — `Authentication`, `Transaction`,
  `Operational Account`, `Underwriting`, `Customer Payment`, `Merchant Settlement` and `KYC` (which
  is the *process*, distinct from the canonical `KYC Case`). The glossary is therefore the **union**
  of both lists, and `DomainGlossaryTest` enforces exactly that — including the reverse direction,
  so the glossary cannot become a second home for vocabulary its owning document should define.
  **A spelling was settled**: the canonical list had `Installment` once against twenty uses of
  `Instalment` elsewhere, including the module register that assigns its ownership. Corrected to
  the majority spelling.
  **`external` is an owner**, not a blank: a PSP is a company we contract with, and modelling one
  as our own state is the first step towards a domain that belongs to a vendor (ADR-0008).
  Nine mutations, all caught — after the guard found two defects in itself: `^` without
  `Pattern.MULTILINE`, and a `### Term` inside a fenced code block read as a definition.
  **Review found one contradiction with the module register**: `Risk Score` was attributed to
  `risk` while `MODULE_ARCHITECTURE.md` §4 lists it under `credit`. The register is followed, since
  ADR-0012 makes it the authority on ownership, and the underlying ambiguity — a credit-risk figure
  or a fraud figure? — is recorded as a Phase 10/13 question rather than settled by a glossary.
  Review added the guard that makes the next such contradiction a build failure, plus one asserting
  every `INV-*` the glossary cites exists.

**P0-DOC-012 — Phase 0 review record** — `COMPLETE` (2026-09-03)
- Context: project
- Description: Written phase review per `PHASE_GATES.md` §4.
- Why: Gate criterion 12.
- Deps: all Phase 0 items
- Accept: Review record exists covering all eight review areas; ADRs moved to `Accepted`.
- Risk: Low
- Cx: S
- DoD: `DOD-DOC`
- **Outcome:** [`reviews/PHASE_0_REVIEW.md`](reviews/PHASE_0_REVIEW.md), all eight areas in order,
  and ADR-0001…0028 moved to `Accepted`.
  **The review finds the exit gate does not pass**, which is the point of conducting one: criterion
  11 fails on three HIGH/CRITICAL Tomcat CVEs and criterion 7 on a suite that has never run in CI.
  `PHASE_GATES.md` §4 prescribes what follows — the phase **remains `IN_PROGRESS`** — and §1 is
  explicit that moving backwards is normal while *"shipping through a failed gate"* is the failure.
  **Two areas could not be conducted as written and say so** rather than being reported as passed:
  area 2 asks for one real posting walked end to end, and Phase 0 creates none; area 5's three
  registered privileged actions are none of them emitted.
  **The ADRs were accepted anyway, and the reasoning is recorded**: criterion 10 is a *precondition*
  of the gate rather than a reward for passing it, so accepting them is work toward it. Holding
  ADR-0003 at `Proposed` because Tomcat has a CVE would be theatre.
  Two documentation drifts found by hand-diffing what no guard covers, both closed — the
  pinned-version table omitted three components including two the drift check it describes actually
  guards.

---

## P0-EPIC-13 — Exit gate remediation

Created by the Phase 0 → Phase 1 transition (2026-09-04). Not architectural work: the sole
remaining gate failure is a fact about the repository's hosting, not about what Phase 0 built.

**P0-TSK-042 — Add a git remote and observe CI green** — `COMPLETE` (2026-09-04)
- Context: platform
- Description: Push this repository to a remote that runs GitHub Actions, and observe all four
  jobs — `build`, `migrations`, `secret-scan`, `dependency-scan` — pass on a CI runner from a
  clean checkout.
- Why: **Phase 0 exit criterion 7 is failing on this and nothing else.** All four jobs pass when
  run locally and `./gradlew build databaseTest` is green from a clean clone, but *green locally*
  and *green in CI* are different claims and only the second satisfies the criterion. Until a
  runner has executed the workflow, every gate the Definition of Done depends on is a gate that
  has never actually run — `SYSTEM_ARCHITECTURE.md` §Continuous Integration says a gate that is
  not automated is a gate that will be skipped, and one that is automated but never executed is
  the same thing wearing a badge. Closing it also closes the `DOD-BUILD` "CI green" item
  outstanding against `P0-TSK-001`–`005` and the Phase 0-specific "build green in CI from a clean
  clone" criterion.
- Deps: none in the repository. **This task cannot be completed from inside it** — it requires an
  action by the project owner.
- Implementation:
  - Create the remote and add it; push `main`.
  - Observe the four jobs. Expect real failures the local runs cannot produce: a case-sensitive
    filesystem, an LF-only checkout, a runner without the TLS interception this machine has, a
    cold Gradle cache exercising dependency verification against the real repositories, and
    Docker-in-CI behaviour for the `migrations` job.
  - Fix what fails **in the workflow or the build**, never by relaxing a gate.
- Tests: the four CI jobs themselves are the test. No new test is added: a test asserting "CI has
  run" could only assert its own environment, which is the vacuity these guards exist to avoid.
- Accept: All four jobs green on a CI runner, from a clean checkout, at a commit on `main`; the
  run is linked from `reviews/PHASE_0_REVIEW.md`; `CURRENT_STATE.md` records the date.
- Risk: Medium — the first CI run of a build that has only ever run on one Windows machine
  routinely fails on path case, line endings and cache assumptions.
- Cx: S (if it passes) to M (if the first run exposes machine-specific assumptions)
- DoD: `DOD-BUILD`
- **Outcome:** remote added; run
  [33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202), commit `04f4a53`,
  **all four jobs green** — 32 actionable tasks executed, 606 hermetic tests, migrations applied to
  an empty database and re-applied idempotently, 173 database tests, 119 commits scanned, SBOM
  clean. **Exit criterion 7 closes and Phase 0 is `COMPLETE`.**
  **It took three runs, and the risk note was right about the class if not the specifics.** Two
  defects that no local run on this machine could reach: `gradlew` committed mode `100644`, killing
  four jobs on `Permission denied` — while `P0-TSK-001` had enforced *LF line endings* on that same
  file so Linux CI would not break, reasoning about the bytes and not the mode; and
  `verification-metadata.xml` complete for a **warm** dependency cache only, because Gradle does not
  re-read metadata descriptors it has already parsed. Cold regeneration added 10 components and 23
  artefacts, **every one a parent POM or a BOM `.module`** — not one jar, which is what identifies
  the mechanism rather than guessing at it. The file had been complete for one machine and
  incomplete for every other.
  A third finding belongs to the scan: gitleaks met this repository's own history for the first
  time — `P0-TSK-031` had proven its teeth only against a throwaway clone — and produced one **false
  positive**, a UUID fixture named `A_KEY`. Allowlisted as that one literal in `.gitleaks.toml`,
  narrowness demonstrated rather than asserted, after the first demonstration reported a pass having
  planted AWS's own documentation key, which gitleaks allowlists by default.
  **Nothing found was in what Phase 0 designed**; all of it was in the machinery that checks it.

---

# Phase 1 — Identity and Customer Foundation

Status: `IN_PROGRESS` — entry gate passed 2026-09-04 (all twelve criteria), first task complete.
Elaborated to task granularity 2026-09-03 by the Phase 0 → Phase 1 transition.

The engineering plan is [`PHASE_1_PLAN.md`](PHASE_1_PLAN.md): scope, domain model, security model,
data model, API model, events, failure scenarios, testing, observability, milestones and the
explicit out-of-scope list. This section is the task list.

**IDs use this document's scheme** (`P1-TSK-nnn`), not a parallel one. §ID Scheme: *IDs are
permanent*, and a second numbering for one phase would be the kind of drift every guard in this
repository exists to prevent.

## P1-EPIC-01 — Party and Customer

### P1-CAP-01 — A person exists and is registered

#### P1-FEAT-01 — Foundations before persistence

**P1-TSK-001 — ADR: data-access mechanism** — `COMPLETE` (2026-09-04)
- Context: platform / architecture
- Description: Decide between JPA/Hibernate, Spring Data JDBC and plain JDBC, and record it.
- Why: Unresolved question 12, raised by `P0-TSK-011` and deferred because Phase 0 had no
  persistence beyond the kernel. Phase 1 introduces six aggregates. It matters more than usual
  here: Hibernate's dirty checking emits `UPDATE`s, and `INV-LED-03`/`INV-HIST-01` say posted
  financial records are never updated — the application role holds no `UPDATE` privilege at all.
  `MoneyColumns` was written mechanism-agnostic so this would not be decided by accident.
- Deps: none
- Implementation: ADR-0033 with alternatives and consequences; no code.
- Tests: none (ADR).
- Accept: ADR-0033 exists in `Proposed`; the decision explains how it interacts with append-only
  tables and with the application role's privileges; unresolved question 12 is closed in
  `CURRENT_STATE.md`.
- Risk: Medium. Cx: M. DoD: `DOD-ARCH`
- **Outcome:**
  [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) — **explicit SQL
  through `JdbcClient`. No ORM, no persistence context, no generated repositories.** No new
  dependency: `spring-jdbc` has been on the runtime classpath since `P0-TSK-027`.
  **The decisive argument is the privilege model, not taste.** `INV-HIST-03`, `INV-HIST-01` and
  `INV-LED-03` are enforced at `DB-PRIVILEGE` by `finapp_app` holding **no `UPDATE` and no
  `DELETE`**, and that is worth exactly as much as the guarantee that nothing emits a statement
  nobody wrote. Hibernate's dirty checking emits `UPDATE` on its own initiative, at a flush point
  decided by code far from the write — so whether the forbidden statement is issued depends on
  whether an entity happened to be dirty.
  **Spring Data JDBC came far closer and was rejected on two concrete behaviours**, not general
  unease: `save()` deletes and re-inserts child collections, and against superseded credentials
  those children *are* the history; and application-minted UUIDv7 identifiers arrive non-null, so
  it defaults to `UPDATE` on a new aggregate — the `Persistable.isNew()` trap, sitting precisely on
  the registration path.
  **The seam it closes was explicit in the code**: four kernel ports are generic over the unit of
  work and three said *"a JDBC `Connection` today, whatever the Phase 3 decision produces later"*.
  `T` is now `Connection` permanently; the type parameter **stays**, because removing it is a
  refactor of proven Phase 0 code with no correctness benefit (`EXECUTION_PROTOCOL.md` rule 4). All
  five javadocs corrected — they described a decision that had moved phases and then been taken.
  **Transactions are begun explicitly** (`TransactionTemplate`), because `@Transactional` fails
  *silently* on self-invocation and registration must write two modules plus audit plus outbox in
  one commit.
  **Enforced rather than recorded** (`DOD-ARCH`): `NoObjectRelationalMapperTest` fails the build if
  a JPA, Hibernate or Spring Data artefact reaches the application's **runtime** classpath — which
  also catches one arriving transitively behind a starter, the way it would actually arrive.
  Writing it **found a real gap**: §6 had forbidden JPA in `sharedkernel` only, and `sharedkernel`
  is not where anyone would add an ORM.
  **A false positive was caught before commit, and it is the useful finding.** The first forbidden
  list matched `hibernate-` and **failed on the real classpath**: `hibernate-validator` is Bean
  Validation, arrives with `spring-boot-starter-validation` from `P0-TSK-025`, and has nothing to
  do with persistence. A rule that forbids a correct dependency is a rule somebody turns off —
  ADR-0019's reasoning for keeping `key` out of the `secretsAreWrapped` vocabulary. The list now
  names the ORM's own artefacts, and a test keeps the carve-out honest by asserting it is still
  needed.
  `DISTRIBUTED_EXECUTION.md` §3 gains **no row**: no new shared state, and every Phase 0
  concurrency protocol stays expressible unchanged. Four mutations, all caught.

**P1-TSK-002 — Constrain the correlation identifier** — `COMPLETE` (2026-09-04)
- Context: platform / security
- Description: Stop a caller placing personal or financial data into `X-Correlation-Id`.
- Why: Recorded Phase 0 debt and the transition's **risk R1**. The charset permits
  `jane.doe@example.com` and `acct:GB29NWBK…` (confirmed by probe), a well-formed inbound value is
  accepted verbatim, and it reaches every log line, every span, four tables and every response.
  That is a disclosure into a telemetry backend with different access control (`INV-AUD-02`),
  bounded today only by there being no customers. **Phase 1 is when customers arrive**, so this
  lands before any customer-facing endpoint.
- Deps: none
- Implementation: generate the platform's own correlation identifier always; carry any caller value
  separately as a distinct, non-propagated field, or narrow the accepted charset — the ADR-level
  choice is part of the task. Never relax the handling: forbidding correlation in logs defeats
  correlation.
- Tests: a caller-supplied value that would be PII cannot reach a log line, a span or a stored
  column; the client can still join its own logs to ours.
- Accept: no caller-controlled value reaches an unbounded-retention sink; the debt row is closed.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`
- **Outcome:**
  [ADR-0034](../adr/ADR-0034-the-platform-owns-the-correlation-identifier.md) — **the platform
  mints the correlation identifier on every request and never adopts an inbound one.** A
  well-formed caller value becomes a *client reference*, echoed in `X-Client-Correlation-Id` and
  carried nowhere else.
  **The finding is that narrowing the charset — the option the task offered first and the one most
  people would reach for — does not work.** A date of birth, a phone number and an account number
  are alphanumeric, so any charset still able to carry a UUID or a W3C trace value carries them
  too. Of the four values `P0-TSK-033` probed, narrowing to `[A-Za-z0-9_-]` would have stopped
  `jane.doe@example.com` and `acct:GB29NWBK…` and **left `customer-1990-05-14` and
  `447700900123`**. A lexical control cannot express the property; the control had to be
  structural.
  **The test asserts at the source, not sink by sink.** All four durable columns, the MDC and the
  span attribute read from one `CorrelationContext`, so `CallerCorrelationIsNotPropagatedTest`
  asserts what that context holds during a request — which covers sinks that do not exist yet. The
  span is checked separately because it is stamped by a span processor rather than by anything
  reading the context on that thread. The four probed values are the test data on purpose: a
  synthetic `client-flow-77` would prove the mechanism and not the risk.
  **The client keeps its join** — it logs the identifier we return, and `X-Client-Correlation-Id`
  lets a gateway match a response to a request. What is deliberately lost is searching *our* logs
  by a caller-chosen string, which is the property that made the disclosure possible.
  **A recorded flake was closed on the way past**: `doesNotContain("bad")` fails about one run in
  137, because a UUIDv7 hex string contains `bad` roughly 0.7% of the time. Replaced by pinning the
  *shape* — a platform-minted UUIDv7 — which nothing derived from caller input can satisfy.
  Four mutations, all caught; the MDC leak was caught twice, the second time by
  `onlyCorrelationContextWritesTheMdc` from `P0-TST-008`.
  **The completion gate found one real gap, in the published contract rather than the code**:
  `X-Client-Correlation-Id` is set on every response and documented in `API_CONVENTIONS.md`, and
  was absent from `openapi.json` — that document's response headers are hand-injected by
  `OpenApiDocument`, so nothing would ever have added it. Published with `required: false`;
  the diff is 43 added lines and zero removed.

**P1-TSK-003 — `party` and `identity` module skeletons**
- Context: party, identity
- Description: Two modules, their Gradle wiring, their schemas and their Flyway histories.
- Why: The first modules other than `platform` to own a schema. The boundary must exist before the
  aggregates do — `P0-TSK-002`'s lesson, one phase on.
- Deps: P1-TSK-001
- Implementation: modules in the documented dependency direction; schema-per-module (ADR-0011);
  `AuditableAction` enum per module, catalogued.
- Tests: boundary rules see both modules; no cross-module entity reference; migrations apply to an
  empty database; both enums reconciled with `AUDITABLE_ACTIONS.md`.
- Accept: `./gradlew build` green with both modules; `ProductionModules` coverage includes them, so
  every existing architecture rule now protects them without being edited.
- Risk: Low. Cx: M. DoD: `DOD-BUILD`

**P1-TSK-004 — Connection-pool sizing for N instances**
- Context: platform / ops
- Description: Size and document the pool against `max_connections` for a realistic instance count.
- Why: Recorded debt and transition **risk R6**. Hikari's default is 10 per instance; ten instances
  exhaust PostgreSQL's default `max_connections` of 100 before any connection does work. The
  failure presents as instances failing readiness for pool exhaustion rather than for anything
  wrong with the database. ADR-0014 says N is never 1, so this is arithmetic owed before the ledger
  — and Phase 1 is the first phase with real pool usage.
- Deps: P1-TSK-003
- Implementation: sizing derived from a stated instance count and `max_connections`, both
  configuration; a startup guard or a documented check.
- Tests: the arithmetic asserted against the configured values, so a change to either that breaks
  the relationship fails the build.
- Accept: the relationship between instances, pool size and `max_connections` is written down and
  checked rather than assumed.
- Risk: Medium. Cx: S. DoD: `DOD-OBS`

#### P1-FEAT-02 — Registration

**P1-TSK-005 — Party, Customer and Identity aggregates**
- Context: party, identity
- Description: The three aggregates, their state machines, their schemas and their constraints.
- Why: ADR-0029. `DELIVERY_PLAN.md` §17 names collapsing them as the phase's top risk.
- Deps: P1-TSK-003
- Implementation: three aggregates in two modules; `identity` references `PartyId` **by value**, no
  cross-module foreign key; state machines with terminal states enforced by the aggregate;
  `CHECK` constraints generated from the enums (`P0-TSK-022` pattern); every column classified.
- Tests: each state machine's invalid transitions rejected **by the aggregate**, not merely
  unreachable through an API (`INV-LIFE-02`); a closed Customer cannot be reopened
  (`INV-LIFE-04`); one Party with two Identities and with zero Customers are both representable;
  `ColumnClassificationTest` green.
- Accept: the three are separately persisted with distinct lifecycles — the Phase 1 exit criterion
  — proven by a test that fails if any two are merged.
- Risk: **High**. Cx: L. DoD: `DOD-KERNEL`

**P1-TSK-006 — `POST /v1/registrations`, idempotent**
- Context: party / api
- Description: One transaction creating Party, Customer, Identity and Credential, with audit and
  outbox rows.
- Why: The first vertical slice, and the first real user of `P0-TSK-017`'s
  `@RequiresIdempotencyKey`.
- Deps: P1-TSK-005, P1-TSK-007
- Implementation: one transaction across two modules — permitted and required by ADR-0001; the
  idempotency kernel at the financial boundary (`INV-IDEM-01`); enumeration-safe collision
  handling.
- Tests: atomicity — a failure leaves no Party, Customer, Identity, audit row or outbox row;
  a retry with the same key creates nothing more and replays the original response byte for byte;
  a differing fingerprint on a known key is a distinct conflict (`INV-IDEM-03`); an email collision
  is indistinguishable from an unrelated failure (`INV-IDN-07`).
- Accept: all four, over real HTTP.
- Risk: **High**. Cx: L. DoD: `DOD-API`

## P1-EPIC-02 — Identity and Credentials

### P1-CAP-02 — That person can authenticate

#### P1-FEAT-03 — Credentials

**P1-TSK-007 — Credential storage**
- Context: identity / security
- Description: Argon2id derivation with algorithm and parameters stored per credential.
- Why: ADR-0032. A global work factor cannot be raised without invalidating every credential.
- Deps: P1-TSK-003
- Implementation: vetted library only; derivation, algorithm and queryable parameters; `Sensitive`
  wrapping; partial unique index on active credential per identity and type; supersede rather than
  edit (`INV-HIST-01`'s reasoning).
- Tests: no persisted or emitted representation contains the input (`INV-IDN-01`); parameters
  recorded (`INV-IDN-02`); a credential is superseded, never updated.
- Accept: both invariants demonstrated to fail when broken.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-008 — Verification and upgrade-on-use**
- Context: identity / security
- Description: Constant-time verification; re-derive under current parameters on success.
- Why: The only moment the platform legitimately holds the plaintext, and the only moment an
  upgrade is possible without a forced reset.
- Deps: P1-TSK-007
- Implementation: constant-time comparison; dummy verification of equivalent cost for an absent
  identity; upgrade inside the verification transaction.
- Tests: a credential under weak parameters verifies and is upgraded; timing for an absent identity
  is equivalent to a wrong credential (`INV-IDN-07`).
- Accept: the store converges without a forced reset, proven by a test.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-009 — `P1-TST-001`: credentials never leak**
- Context: identity / test
- Description: A credential appears in no log, event, response, span or metric.
- Why: `INV-AUD-02` and `INV-IDN-01`. `P0-TST-008` found the rule protecting this was structurally
  incapable of failing; this is its first real subject.
- Deps: P1-TSK-008
- Implementation: assertions over emitted output on every appender, over published events, and over
  API responses — each with a negative control.
- Tests: as above.
- Accept: fails when a credential field is added without wrapping.
- Risk: Medium. Cx: M. DoD: `DOD-TEST`

#### P1-FEAT-04 — Authentication

**P1-TSK-010 — `POST /v1/authentications`, enumeration-safe**
- Context: identity / api
- Description: Password authentication returning one response shape for every failure.
- Why: `INV-IDN-07`. Enumeration turns a credential-stuffing list into a targeted one.
- Deps: P1-TSK-008, P1-TSK-013
- Implementation: one shape and equivalent timing for unknown identity, wrong credential and locked
  account; `AuthenticationSucceeded` / `AuthenticationFailed` events, the latter carrying **no**
  identity identifier; audit record carrying the attempted identifier.
- Tests: responses and timing compared across existing and absent accounts; the failure event
  proven to carry no identifier.
- Accept: `INV-IDN-07` demonstrated to fail when the responses diverge.
- Risk: **High**. Cx: M. DoD: `DOD-API`

**P1-TSK-011 — Brute-force and credential-stuffing controls**
- Context: identity / security
- Description: Failure counting, lockout and rate limiting on every credential endpoint.
- Why: ADR-0032 makes verification deliberately expensive, so the login endpoint is the platform's
  most CPU-costly operation and a denial-of-service target. Not an extra — part of the same design.
- Deps: P1-TSK-010
- Implementation: **database-backed counters**, never process-local (ADR-0024, and transition risk
  R7); lockout indistinguishable from an ordinary failure; per-identity and per-source limits.
- Tests: concurrent attempts across simulated instances produce one correct count (`P0-TST-009`
  convention); a locked account's response is unchanged; the counter survives an instance restart.
- Accept: the limit is not bypassable by concurrency, proven under real contention.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-012 — `P1-TST-002`: authentication failure modes**
- Context: identity / test
- Description: The `PHASE_1_PLAN.md` §8 scenarios that concern authentication.
- Deps: P1-TSK-011
- Tests: invalid credential; lockout; concurrent login and credential change; database unavailable
  fails closed with no session issued.
- Accept: each demonstrated to fail when the control is removed.
- Risk: Low. Cx: M. DoD: `DOD-TEST`

## P1-EPIC-03 — Sessions and Devices

### P1-CAP-03 — Sessions are real and revocation is immediate

#### P1-FEAT-05 — Session lifecycle

**P1-TSK-013 — Session aggregate and issuance**
- Context: identity
- Description: Server-side sessions with an opaque identifier, assurance level, device and two
  expiry bounds.
- Why: ADR-0030. An eventually-revoked session is an unrevoked session.
- Deps: P1-TSK-003
- Implementation: authoritative in PostgreSQL, **no Redis**; opaque random identifier; idle and
  absolute expiry both recorded on the row so a policy change does not retroactively extend
  existing sessions; assurance level as a level, never a boolean.
- Tests: expiry bounds enforced; an expired session is indistinguishable from a revoked one.
- Accept: no process-local session state anywhere, asserted.
- Risk: **High**. Cx: M. DoD: `DOD-KERNEL`

**P1-TSK-014 — Revocation, immediate and multi-instance**
- Context: identity
- Description: Revoke one session, revoke all, and revoke on credential change.
- Why: `INV-IDN-03`.
- Deps: P1-TSK-013
- Implementation: revocation is a state transition to a terminal state; credential change revokes
  every other session in the same transaction.
- Tests: revoke on one simulated instance, assert refusal on another; concurrent login and
  revocation — revocation wins.
- Accept: `INV-IDN-03` demonstrated to fail when a session cache is introduced.
- Risk: **High**. Cx: M. DoD: `DOD-KERNEL`

**P1-TSK-015 — Rotation on privilege change**
- Context: identity / security
- Description: A new session identifier on login, step-up and credential change.
- Why: Session fixation. Elevating in place lets a stolen pre-elevation identifier become elevated.
- Deps: P1-TSK-013
- Tests: the pre-rotation identifier is refused after rotation; the elevated session is a different
  identifier.
- Accept: no privilege change leaves the identifier unchanged.
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

**P1-TSK-016 — Session and device endpoints**
- Context: identity / api
- Description: `GET /v1/sessions`, `DELETE /v1/sessions/{id}`, `DELETE /v1/sessions/current`,
  device recorded on the session.
- Deps: P1-TSK-014, P1-TSK-020
- Implementation: ownership checked in the domain, never at the boundary from a request parameter;
  device recorded, **never scored** — trust is a Phase 13 risk decision.
- Tests: a negative ownership test — one identity cannot list or revoke another's sessions.
- Accept: the negative ownership test passes and fails when the check is removed.
- Risk: Medium. Cx: M. DoD: `DOD-API`

## P1-EPIC-04 — Multi-Factor Authentication

### P1-CAP-04 — A second factor that cannot be bypassed

**P1-TSK-017 — TOTP enrolment**
- Context: identity / security
- Description: Enrol a TOTP factor, with the secret encrypted at rest and never emitted.
- Deps: P1-TSK-013
- Implementation: vetted library; secret wrapped and encrypted; enrolment is not complete until
  confirmed by a valid code.
- Tests: a partially enrolled factor never satisfies a challenge; the secret appears in no
  response, log or event.
- Accept: partial enrolment leaves assurance unchanged.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-018 — Challenge, verification and assurance elevation**
- Context: identity / security
- Description: `POST /v1/authentications/mfa`; a verified challenge produces a `MULTI_FACTOR`
  session.
- Deps: P1-TSK-017, P1-TSK-015
- Implementation: replay refused; elevation produces a **new** session identifier; the level is
  recorded on the session, and operations ask for a minimum level.
- Tests: a replayed code is refused; elevation rotates the identifier.
- Accept: an operation requiring `MULTI_FACTOR` refuses a `PASSWORD` session.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-019 — `P1-TST-003`: MFA cannot be bypassed**
- Context: identity / test
- Description: One test per enumerated alternative path to a session.
- Why: `INV-IDN-05`. Every real MFA bypass is a path nobody enumerated, which is why the paths are
  enumerated here rather than the property asserted once.
- Deps: P1-TSK-018
- Tests: an older `PASSWORD` session; a refresh; re-enrolment of a second factor; recovery
  (once M1.6 exists); a direct call to any endpoint that issues a session.
- Accept: each path either requires the factor or cannot produce a `MULTI_FACTOR` session; the
  suite fails if the level check is replaced by a boolean.
- Risk: **High**. Cx: M. DoD: `DOD-TEST`

## P1-EPIC-05 — Authorization and Actor-Attributed Audit

### P1-CAP-05 — Every action is permitted and attributable

**P1-TSK-020 — Roles, permissions and the boundary check**
- Context: identity / security
- Description: Role and permission model; a declarative, deny-by-default permission check before
  the handler.
- Why: ADR-0031, `INV-IDN-04`, `INV-AUD-03`.
- Deps: P1-TSK-013
- Implementation: roles assigned to identities; the check declared per endpoint and enforced before
  dispatch — the `P0-TSK-017` interceptor pattern; an endpoint with no declaration is refused.
- Tests: a **negative authorization test for every protected endpoint** — the Phase 1 exit
  criterion; an undeclared endpoint is refused rather than permitted.
- Accept: deny-by-default demonstrated by adding an endpoint with no declaration and watching it be
  refused.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-021 — Ownership checks in the domain**
- Context: identity, party
- Description: Resource-scoped operations check ownership against authoritative state.
- Why: ADR-0031. The most common authorization defect is a legitimate permission used against
  someone else's resource — every check passes and nothing is logged as a denial.
- Deps: P1-TSK-020
- Implementation: the check lives in the module that owns the state, never at the boundary from a
  request parameter.
- Tests: a negative ownership test per resource-scoped operation.
- Accept: each fails when the ownership check is removed. **Recorded limit:** no build rule detects
  a missing ownership check; this is a review question, on the same terms as ADR-0024's limit.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-022 — Actor-attributed audit**
- Context: identity, party, platform
- Description: Every privileged action writes an `AuditRecord` naming the real actor, in the same
  transaction as its effect.
- Why: The phase's reason for existing. Phase 0 built the trail and recorded that nothing writes to
  it; this is its first real writer, and `SecurityContext.enterSystem()` call sites are revisited
  as `SECURITY_ARCHITECTURE.md` says they must be.
- Deps: P1-TSK-020
- Implementation: `SecurityContext` established per authenticated request; `require()` satisfied by
  a real actor; audit written in the effect's transaction; `AuditableAction` enums extended and
  catalogued.
- Tests: an audit record per privileged action with all seven fields; the record is immutable at
  the privilege level (`INV-HIST-03`, already enforced); a request with no established actor is
  refused rather than attributed to the system.
- Accept: `INV-AUD-01` holds for every Phase 1 privileged action; the number of `enterSystem()`
  call sites is reduced to those that are genuinely the platform acting, and each is justified.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

## P1-EPIC-06 — Account Recovery

### P1-CAP-06 — Recovery that is not the way in

**P1-TSK-023 — Account recovery**
- Context: identity / security
- Description: Initiation, channel verification, single-use expiring token, completion, and
  notification as an outbox event.
- Why: `INV-IDN-06`. `DELIVERY_PLAN.md` §17 names recovery becoming the weakest link as a top risk;
  it bypasses the credential by design, which is exactly why it is built last, against a working
  MFA, session and audit model.
- Deps: P1-TSK-019, P1-TSK-022
- Implementation: proof of control of a **previously registered and verified** channel; token
  hashed at rest like a credential; single use, expiring; rate limits and cooling-off; notification
  emitted as an outbox event with **no delivery adapter** — that is Phase 15's; recovery never
  lowers the assurance required to reach the account.
- Tests: the `INV-IDN-06` abuse cases, each its own test — replayed token, unverified channel,
  recently changed channel, concurrent recovery and login, recovery used to reach an operation
  requiring `MULTI_FACTOR`.
- Accept: every abuse case refused; `INV-IDN-06` demonstrated to fail when the channel-verification
  check is removed.
- Risk: **High**. Cx: L. DoD: `DOD-SEC`

## P1-EPIC-07 — Phase Review

**P1-TSK-024 — Extend the mutation register to Phase 1**
- Context: platform / test
- Description: A row in `MUTATION_TESTING.md` for every `INV-IDN-*`, and the guard extended to
  require them.
- Why: `MutationDemonstrationTest` currently enforces demonstrations for Phase 0 invariants only.
  Seven new invariants without it would be exactly the regime `INV-IDN` was created to escape.
- Deps: all `P1-TSK-*`
- Implementation: extend the guard from "Phase 0" to "every phase up to and including the current
  one", so the extension is not needed again in Phase 2.
- Tests: proven by mutation — a Phase 1 invariant with no register row fails the build.
- Accept: all seven `INV-IDN-*` have a recorded demonstration; the guard covers them.
- Risk: Low. Cx: S. DoD: `DOD-TEST`

**P1-DOC-001 — Phase 1 review record**
- Context: project
- Description: The written phase review per `PHASE_GATES.md` §4, and ADR-0029…0033 to `Accepted`.
- Deps: all Phase 1 items
- Accept: all eight review areas covered; the twelve universal and six Phase 1-specific exit
  criteria assessed with evidence.
- Risk: Low. Cx: S. DoD: `DOD-DOC`

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
