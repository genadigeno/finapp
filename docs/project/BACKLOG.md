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

**P1-TSK-003 — `party` and `identity` module skeletons** — `COMPLETE` (2026-09-04)
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
- **Outcome:** two modules, `party` and `identity`, each owning a schema and its own Flyway
  history. Three schemas now exist, all owned by `finapp_migrator` (never a superuser), each with
  `REVOKE ALL ... FROM PUBLIC` and `finapp_app` granted `USAGE` and nothing else — verified against
  a live database, not asserted.
  **The acceptance criterion was proven rather than assumed.** A `double` planted in
  `PartyAuditAction` fails **two** floating-point rules in `:app:test` — so every existing
  architecture rule protects the new modules without being edited, because `ProductionModules`
  derives its coverage from the classpath.
  **Each module has a real test, and the guard is what forced it**: `TestTaxonomyTest` failed with
  *"a module contributing no test classes means the sweep did not reach it"*. The tests assert the
  ADR-0029 boundary structurally — neither module may see the other, nor `app` — with a
  non-vacuity half asserting each *does* see `platform` and `sharedkernel`.
  **CI's `:platform:flywayMigrate` was a list of one** and is now unqualified, so a fourth
  schema-owning module is covered without anyone remembering — the `:platform:databaseTest` shape
  the `P0-TSK-027` review found.
  Two auditable actions in `identity` and one in `party`, catalogued and reconciled in both
  directions by the existing registry test. Deliberately few: a registry may list an action before
  its code exists, but not before its *design* does.
  One defect found by probing: an unescaped apostrophe in a schema `COMMENT` (`the platform's`),
  which Flyway rejected at 42601. Found by applying the migration to a real database rather than by
  reading it.
  **The completion gate found two more hardcoded `platform` names, both fixed**: the column
  classification guard queried one schema, so ADR-0022's guarantee had become true for one schema
  in three; and the database test harness applied one module's migrations, so every database test
  ran against a database missing two thirds of its schemas. With CI's `:platform:flywayMigrate`
  that is three names in one task — each correct when written — so all three now derive their set
  rather than naming it.

**P1-TSK-004 — Connection-pool sizing for N instances** — `COMPLETE` (2026-09-04)
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
- **Outcome:** `instances x maximum-pool-size <= server max_connections - reserved`, declared as
  configuration and enforced by `ConnectionPoolSizingGuard` at startup. Shipped: 10 x 8 = 80
  against 100 - 12 = 88.
  **The obvious repair is the wrong one, and that is the finding.** Dividing `max_connections` by
  the instance count treats the limit as a budget to spend; it is a ceiling not to hit. Every
  connection is a backend process, and PostgreSQL throughput stops improving once the machine's
  cores are busy — past that, extra connections queue **inside** the database, where the queueing is
  invisible to the application and shows up as latency on every query rather than as a pool timeout
  on one. So the pool is sized small for throughput, and "does the fleet fit" is a separate question
  asked afterwards.
  **Checked in two places, because they are two claims.** The guard proves it at startup;
  `ConnectionPoolSizingIsConfiguredTest` proves the *shipped* numbers satisfy it in the build — a
  guard alone would leave a violating configuration to be discovered by a rolling restart, one
  instance at a time.
  **Verified against a running instance** (`DOD-OBS`), in all three directions: the shipped
  configuration starts; `FINAPP_DB_INSTANCES=20` is refused with the arithmetic and the fix in the
  message; and raising `max_connections` to 200 is accepted, so the guard does not force the pool to
  be the thing that gives way.
  **Two limits stated rather than implied**: the guard cannot verify `max_connections` against the
  live server and does not try — it is a declaration, and a wrong one is a wrong answer; and the
  arithmetic assumes each instance holds its full pool, which is why `minimum-idle` equals
  `maximum-pool-size` and why a test asserts it.

#### P1-FEAT-02 — Registration

**P1-TSK-005 — Party, Customer and Identity aggregates** — `COMPLETE` (2026-09-05)
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
- **Outcome:** three aggregates in two modules, three tables in two schemas, fifteen columns each
  classified at its ceiling.
  **The acceptance criterion is a test that fails if any two are merged**, and it is written as the
  four shapes a merged model cannot represent rather than as an abstract claim: a person who is not
  a customer (a beneficial owner), a customer who is not a person (an organisation), one Party
  holding a retired login and its replacement, and lifecycles that move independently — suspending
  a login must not suspend the commercial relationship.
  **Two invariants are enforced only by the database, because no aggregate can enforce them**: at
  most one *live* customer relationship per party, and a login identifier used once ever. Both are
  rules *across* aggregates of the same type, so only the database arbitrates between two concurrent
  transactions — which ADR-0014 says is the normal case.
  **The two uniqueness rules deliberately point opposite ways.** A closed relationship frees the
  party for a new one (partial index); a closed login **never** frees its identifier (total index),
  because reissuing it would let a new person authenticate with a name appearing in someone else's
  audit history.
  **`identity.identity.party_id` has no `REFERENCES` clause**, asserted in the migration and in a
  test. The cost is stated rather than hidden: the database permits an identity for a party that
  does not exist, and what prevents it is the registration transaction, not the schema.
  **`Party` has no lifecycle**, which looks like an omission and is the design: existence has no
  states, and every state people reach for — inactive, closed, archived — is a statement about a
  relationship or a login, each of which has its own table.
  Seven mutations, all caught: `CLOSED` made non-terminal, the aggregate's transition check
  removed, the partial index dropped, a status added to `Party`, a cross-schema foreign key
  introduced, the `PartyName` mask removed, and `@` permitted in a login identifier.
  **The completion gate found two gaps and closed both**: `DOD-KERNEL` requires concurrency proven
  by integration test and there was none - while the argument for these rules living in the
  database is that only the database arbitrates between concurrent transactions, which had been
  asserted *sequentially*; and `INV-AUD-02` was claimed through masked `toString()` overrides that
  nothing tested. Ten racers, each with its own connection, plus the crash half: a rolled-back
  attempt must not consume the uniqueness slot.

**P1-TSK-006 — `POST /v1/registrations`, idempotent** — `COMPLETE` (2026-09-06),
**less the credential**
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
- Accept: all four, over real HTTP. **All four met.**
- Risk: **High**. Cx: L. DoD: `DOD-API`
- **Delivered without the credential leg, and the reason is recorded rather than absorbed.** This
  item declares `Deps: P1-TSK-007`, which is `TODO`; the work was scoped to this task alone on
  instruction. Two consequences follow and are carried as `P1-TSK-026`:
  1. **A registered Identity cannot yet acquire a credential.** `POST /v1/me/credential` requires a
     session, a session requires authentication, and authentication requires a credential. There is
     no path from *registered* to *able to log in* until `P1-TSK-007` and `P1-TSK-008` land.
  2. **Adding `password` to this request body later is a `BREAKING` change** to a published `/v1`
     contract (ADR-0015), on the platform's first endpoint. It is the right change and it will be
     labelled honestly; there is no client, so the cost is a diff review rather than a migration.
- **Completion gate found two defects and fixed both.** A NUL byte in `displayName` produced
  `500 api.InternalError` — a caller's mistake reported as ours (`ERROR_CONTRACT.md` §3) — and
  control characters were being accepted into a `RESTRICTED-PII` column. Closed in `PartyName`, at
  the request boundary and as a `CHECK` in `party` `V003`. Separately, the `409
  api.IdempotencyInProgress` branch was exercised by no test: the ten-way race returns 201 to all
  ten, so the branch is now driven by writing the row a still-running instance leaves behind.
- **Backlog defect found while executing it:** this task sits in milestone **M1.1** and depends on
  `P1-TSK-007`, which is in **M1.2**. `PHASE_1_PLAN.md` §11 puts `POST /v1/registrations` in M1.1
  and states M1.1's acceptance as *"a Party, a Customer and an Identity"* with no credential, so the
  plan and this item's `Deps`/`Description` disagree. The plan is the one that is internally
  consistent; the dependency should be on the milestone that owns the credential. Same class of
  defect as `P0-TSK-004`'s unsatisfiable dependency and `P0-TST-009`'s spurious one — the third.

**P1-TSK-026 — Registration takes a credential** — `COMPLETE` (2026-09-08)
- Context: party / identity / api
- Description: Extend `POST /v1/registrations` to accept and store a credential, closing the
  bootstrap gap `P1-TSK-006` left.
- Why: Without it a registered Identity can never authenticate — see `P1-TSK-006`'s two recorded
  consequences. This is `P1-TSK-006`'s remaining half, not new scope.
- Deps: P1-TSK-006, P1-TSK-007
- Implementation: a required `password` field; derived **before** any insert, so both the success
  and the collision paths pay the same cost and timing does not become an account oracle
  (`INV-IDN-07`); the credential row inside the same transaction; **the credential stays out of the
  request fingerprint** (`RegistrationService.canonicalForm`), because `request_fingerprint` is a
  durable single-round SHA-256 and hashing a body containing a password would store an
  offline-crackable derivation of it (`INV-IDN-01`).
- Tests: a registration produces exactly one active credential; the plaintext appears in no
  persisted or emitted representation; the fingerprint is unchanged by the password; timing is
  equivalent for an existing and an absent identifier.
- Accept: **all four met**, each demonstrated to fail by mutation.
- **The headline is asserted end to end, because the defect being closed was a gap between two
  halves that each worked.** `RegistrationCredentialDatabaseTest.aRegisteredPersonCanAuthenticate`
  registers over HTTP, logs in over HTTP with the password it registered, and uses the returned
  token on `GET /v1/sessions` — nothing inserted by the test. *"A credential row exists"* was
  deliberately not the headline: it passes against a credential stored under the wrong identity, the
  wrong algorithm, or a status nothing can verify (proven — the `SUPERSEDED` mutation).
  `aFabricatedPasswordOpensNothing` is its negative control.
- **The derivation is structurally unconditional, not balanced.** `IdentityRegistration.prepare`
  mints the `IdentityId` and derives; `create` takes the result. **You cannot call the second
  without having called the first**, so no database outcome can decide whether the work happens — a
  balanced pair of code paths is one somebody later optimises away.
- **The item's stated reason for that ordering is weaker than it reads, and the correction is
  recorded rather than repeated.** A success answers `201` and a collision `422`, in one round trip,
  so they are *already* distinguishable — necessarily, because an endpoint that claims a name must
  say when the name is taken. Equal work is defence in depth here. **The load-bearing reason is
  operational**: ~46 ms of CPU and ~19 MiB per derivation (ADR-0032) must not be paid while holding
  one of eight pooled connections (`P1-TSK-004`), or a registration flood becomes connection
  timeouts pointing at a healthy database. `theDerivationIsOutsideTheTransaction` asserts it, and it
  is the only test that catches `prepare` being moved beside the insert it feeds.
- **An Identity is no longer constructible without a credential.** The credential-less path is
  removed rather than deprecated: leaving it would leave the defect reachable.
- **The fingerprint decision was kept and its consequence stated.** `canonicalForm` takes no
  password, asserted **structurally** — there is nothing to vary, so the only way to break the
  property is to change the signature, and a second overload is caught too. The consequence, now
  written down and asserted behaviourally: **a retry with the same key and a different password
  replays** rather than being refused as an `INV-IDEM-03` conflict. That is the right trade — the
  alternative stores an offline-crackable derivation of every registration password for the life of
  the record — and the residual is bounded by the empty response body.
- **A short password is a 422, which is the opposite of authentication's answer, deliberately.**
  There a short password is an ordinary failure, because a second response shape is an enumeration
  risk; here it is a value the caller **chose** and must be able to correct, and the refusal is
  decided before any lookup. `@Size` could not express it — Bean Validation cannot see inside
  `Sensitive`, and a constraint that unwrapped it would put a plaintext in `app` and fail
  `SecretsAreUnwrappedInOnePlaceTest`, correctly — so `RegistrationService` maps `RawPassword`'s
  refusal, writing the client detail rather than passing the exception's message on.
- **The leak sweep covers every table in every schema, derived from `information_schema`.**
  `P1-TSK-007` proved the credential row does not hold the plaintext; what this task adds is a
  password crossing an HTTP boundary into an idempotency record, an audit record and an outbox row
  — three sinks credential storage never touched, two of which reach systems with different access
  control (`INV-AUD-02`).
- **The contract diff is two lines and was reviewed**: `password` added to properties (COMPATIBLE)
  and to `required` (BREAKING). Accepted on ADR-0015 and `P1-TSK-027`'s precedent — nothing consumes
  this API, and the alternative is a `/v2` for an endpoint whose first version was never usable.
- **The mutation harness reported seven proofs it had never measured**, and checking *why* each was
  caught is what found it: it invoked `./gradlew.bat`, which cmd answers *"'.' is not recognized"*
  with exit 1, so every mutation read as CAUGHT against a build that never ran. `P1-TSK-027`'s
  finding in a new disguise. The harness now asserts the build actually started and names the test
  that failed; all seven were re-run, and two were rewritten because they were caught by
  **compilation** rather than by an assertion.
- **The completion gate found two claims nothing asserted.** The credential identifier goes into the
  audit change summary and the event payload, and nothing looked at either — asserted now as the
  *property* (the identifier is findable) rather than as a rendering, which is `P1-TSK-027`'s
  finding. And **no password shape produces a 500**, driven across eleven shapes, with the two
  decided by *different* mechanisms pinned: a number is coerced and succeeds, an object never
  reaches the deserialiser and is a `400`.
- **The coercion is asserted symmetric across both endpoints**, which nothing had checked because
  until now there was no registration secret to be asymmetric with. It forecloses a customer who
  registers successfully and can never log in — this task's own failure, through another door.
- **The gate's first probe was wrong, and finding that out is what running one is for.** It reported
  a `400` for control characters in a password; the **Java source held real control characters**, so
  the body was invalid JSON and every answer was about the document. `P1-TSK-016`'s finding,
  reproduced by me — Java processes `\uXXXX` *before* string escapes. Recorded rather than worked
  around, and the claim is unnecessary anyway: the plaintext is never persisted, and both endpoints
  share one DTO, one deserialiser and one `RawPassword`.
- **Eleven mutations, all caught** — four added by the gate. One is recorded as caught by
  **compilation** rather than by `secretsAreWrapped`, so a second compiling mutation was written to
  prove the rule covers this type. 860 hermetic, 438 database.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

## P1-EPIC-02 — Identity and Credentials

### P1-CAP-02 — That person can authenticate

#### P1-FEAT-03 — Credentials

**P1-TSK-007 — Credential storage** — `COMPLETE` (2026-09-06)
- Context: identity / security
- Description: Argon2id derivation with algorithm and parameters stored per credential.
- Why: ADR-0032. A global work factor cannot be raised without invalidating every credential.
- Deps: P1-TSK-003
- Implementation: vetted library only; derivation, algorithm and queryable parameters; `Sensitive`
  wrapping; partial unique index on active credential per identity and type; supersede rather than
  edit (`INV-HIST-01`'s reasoning).
- Tests: no persisted or emitted representation contains the input (`INV-IDN-01`); parameters
  recorded (`INV-IDN-02`); a credential is superseded, never updated.
- Accept: both invariants demonstrated to fail when broken. **Both demonstrated**; seven mutations,
  all caught.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`
- **`INV-IDN-01` landed at `DB-CONSTRAINT`, stronger than the item asked for.** The derivation
  column refuses a value that is not in its algorithm's encoded form, so a plaintext cannot
  physically be stored by a migration, an operator, or code nobody has written yet.
- **Measured, not asserted:** ~46 ms per derivation at m=19456/t=2/p=1, recorded in ADR-0032's
  follow-up. ADR-0032 asks for parameters chosen against a *stated* verification time, and a stated
  time nobody measured is not stated.
- **The library needs more at run time than its POM declares**, found by running the real encoder
  rather than reading: `spring-security-crypto` lists one optional assertj and in fact needs
  BouncyCastle to derive and spring-core to verify. `identity` therefore takes a Spring Framework
  runtime dependency, recorded plainly rather than described away.
- **Completion gate found and fixed one defect**, and it is the one that mattered:
  `Credential.derived` took the algorithm, parameters and derivation as three independent arguments,
  so a caller could record parameters that were not the ones used - `INV-IDN-02` satisfied in form
  and defeated in substance, since an upgrade campaign would skip a weak credential while believing
  it had been assessed. Closed by construction: `Credential.forPassword` takes the deriver, so the
  three facts come from one place. Eight mutations, all caught.
- **Not in scope, with the owning task named:** verification and upgrade-on-use (`P1-TSK-008`);
  the endpoint and registration integration (`P1-TSK-026`); session revocation on change (M1.3).
  `isWeakerThan` exists and is tested; nothing calls it yet.

**P1-TSK-008 — Verification and upgrade-on-use** — `COMPLETE` (2026-09-06)
- Context: identity / security
- Description: Constant-time verification; re-derive under current parameters on success.
- Why: The only moment the platform legitimately holds the plaintext, and the only moment an
  upgrade is possible without a forced reset.
- Deps: P1-TSK-007
- Implementation: constant-time comparison; dummy verification of equivalent cost for an absent
  identity; upgrade inside the verification transaction.
- Tests: a credential under weak parameters verifies and is upgraded; timing for an absent identity
  is equivalent to a wrong credential (`INV-IDN-07`).
- Accept: the store converges without a forced reset, proven by a test. **Proven**: a weak
  credential verifies, is re-derived at policy, and the same password works afterwards.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`
- **Timing is asserted by counting work, not by reading a clock.** A counting deriver proves all
  four failing paths - absent identity, suspended identity, no credential, wrong password - perform
  exactly one full verification. A wall-clock assertion would be flaky and would measure the machine.
- **Two defects found while building it.** An auto-commit connection made `setSavepoint` throw and
  the upgrade's catch-all swallowed it, so every login would have verified correctly and upgraded
  nothing, permanently, with nothing failing; the verifier now refuses such a connection outright.
  And a surviving mutation showed the concurrency test asserted the *outcome* rather than the
  *coordination* - ignoring the conditional supersede's answer still produced one upgrade, via the
  unique index and the catch-all. Now counted.
- **The completion gate found a third defect, wider than this task.** PostgreSQL puts the entire
  refused row in a `CHECK` violation's `DETAIL`, and our storage exceptions carried the driver
  exception as a cause - so the constraint that stops a plaintext being *stored* caused it to be
  *logged* when it fired, and the same route carried a person's name out of `party.party` and a
  login identifier out of `identity.identity`. Closed across all three tables by
  `DatabaseFailure.describe`, and by removing the cause-taking constructor so the unsafe path does
  not compile. Seven mutations, all caught.
- **Not in scope, with the owning task named:** the endpoint and its response shaping
  (`P1-TSK-010`), lockout (`P1-TSK-011`), session issuance (`P1-TSK-013`), audit and events
  (`P1-TSK-010`).

**P1-TSK-009 — `P1-TST-001`: credentials never leak** — `COMPLETE` (2026-09-06)
- Context: identity / test
- Description: A credential appears in no log, event, response, span or metric.
- Why: `INV-AUD-02` and `INV-IDN-01`. `P0-TST-008` found the rule protecting this was structurally
  incapable of failing; this is its first real subject.
- Deps: P1-TSK-008
- Implementation: `SecretsAreUnwrappedInOnePlaceTest` (architecture),
  `CredentialNeverReachesALogTest` (slice), `CredentialReachesNoEmittedSinkTest` (unit),
  `CredentialVerifierLogsNothingSensitiveDatabaseTest` (database). Four tiers, because a tier is
  what a test needs in order to run (ADR-0028) and one class would have cost the heaviest member's
  price everywhere.
- Accept: **met, and it was already met before the task started** — probed against real production
  code rather than the existing fixture: an unwrapped `String lastPassword` in `CredentialVerifier`
  fails `secretsAreWrapped` twice, for the field and for the accessor. So the delivered work is the
  gap between the item's *description* and its *acceptance clause*, which are different claims.
- **The scrubber debt was answered, not carried forward.** The Phase 0 row *"no output scrubber for
  text the platform does not control"* named its trigger as a business module logging real flows.
  It is **not built**: a scrubber is a deny-list, and to recognise a secret it must be given the
  secret, so the plaintext travels further rather than less far. Replaced by the checkable opposite
  — every `expose()` call site pinned, four classes, all in `identity`.
- **Three of the five sinks have no credential-carrying producer yet**, so for those the deliverable
  is the mechanism that will refuse one, never an assertion of absence over an empty stream.
- **Two limits stated in the tests**: `EventPayload` is a charset and would publish `hunter2`; the
  whitelist says where a secret may be unwrapped, not what happens next.
- **The completion gate found `secretsAreWrapped`'s vocabulary was one third dead.** The splitter
  separates `apiKey` into `[api, Key]`, so its six *compound* entries — `apikey`, `privatekey`,
  `signingkey`, `cardnumber`, `mfacode`, `sessionid` — could never match. Measured in both
  directions: a production `String cardNumber` passed the build before the fix and fails after it.
  Closed by adjacent-pair matching, with the false-positive direction re-proven.
- **Three further gate findings, all in this task's own work**: a coverage guard that could not see
  `app` leave the sweep (`contains` where every sibling asserts equality); a contract guard reading
  JSON keys and not values, so an OpenAPI parameter named `token` was invisible; and a second copy
  of the secret vocabulary that had already drifted, now reconciled by test.
- **A mutation reported SURVIVED having never landed, twice** — a marker that did not match the
  document's formatting, and a backup file that silently failed to be written. Every mutation is now
  applied with the plant asserted present first. Eleven mutations, all caught.
- Risk: Medium. Cx: M. DoD: `DOD-TEST`

#### P1-FEAT-04 — Authentication

**P1-TSK-010 — `POST /v1/authentications`, enumeration-safe** — `COMPLETE` (2026-09-06)
- Context: identity / api
- Description: Password authentication returning one response shape for every failure.
- Why: `INV-IDN-07`. Enumeration turns a credential-stuffing list into a targeted one.
- Deps: P1-TSK-008, P1-TSK-013
- **`P1-TSK-013` is `TODO`, and the plan contradicts itself about where it belongs.**
  `PHASE_1_PLAN.md` §11 puts *session issuance* in **M1.2's scope** and makes M1.2's acceptance
  *"an identity authenticates and receives a session"* — while listing M1.2's tasks as
  `P1-TSK-007 … P1-TSK-012`, which excludes the session task. Fourth backlog defect of this class.
  Unlike `P1-TSK-006`'s, here the **`Deps` line is right and the milestone boundary is wrong**:
  "that person can authenticate" is not a meaningful milestone without a session. Recorded, not
  silently corrected — moving a task between milestones is a planning act.
- **Delivered without session issuance.** A success returns `204` and issues nothing a client can
  hold, so **M1.2 cannot close on `P1-TSK-012`**. The remainder is `P1-TSK-027`.
- Implementation: `AuthenticationController` (204 / 401), `AuthenticationService` (one transaction,
  refusal **returned not thrown**), `IdentityAuthentication` (audit + event, both paths),
  `IdentityErrorCode.AUTHENTICATION_FAILED`, two new `IdentityAuditAction` constants.
- Accept: **met.** The four failing causes are asserted **equal to each other** rather than each
  against a remembered expectation, and the **timing** half is asserted by counting derivations.
- **Not idempotent, and that is stronger than "the money-moving clause is vacuous":** an idempotency
  key is explicitly not a secret, so a stored success keyed on one would let anybody who saw the key
  replay a *successful authentication*. The mechanism that makes registration safe would make this
  an authentication bypass.
- **The platform's first real actor.** A success is attributed to `Actor(identityId, CUSTOMER)`;
  a failure to the platform, because there may be no identity at all. The asymmetry is correct
  information rather than a channel — the audit trail is not client-visible.
- **`secretsAreWrapped` found a real defect in the request DTO**: a `String password` component
  prints itself through a record's generated `toString`. Wrapped, which needed the Jackson
  *deserialiser* — the symmetric half of `P0-TSK-030`'s masking serialiser.
- **Two contract defects, found by generating the document**: the wrapper published as an empty
  `SensitiveString` schema, so a client would model a password as an untyped object; and
  `P1-TSK-009`'s contract guard fired, correctly, on a framing that forbade a secret *anywhere* —
  narrowed to *a secret may be sent, never returned and never put in a URL or header*.
- **Six mutations. One survived and produced a new test**; one survived correctly, having targeted
  the log field rather than the response.
- **The completion gate found a javadoc claiming a test that did not exist** — `AuthenticationRequest`
  said "a test asserts they still match" and none did, while the sibling `RegistrationRequest` had
  one. Written, sweeping every code point the boundary admits.
- **Three further gate findings, all from probing**: eight request shapes over real HTTP, none
  producing a 500, now pinned; a "hardening" null-check in the deserialiser that was **unreachable
  code** with a comment describing a mechanism that is not the real one, removed after probing
  Jackson directly; and a correlation exclusion in the response comparison that was a hole rather
  than an allowance, now asserted non-vacuous.
- **The throttling debt is recorded**: this endpoint is a CPU and memory amplifier, ~46 ms and
  ~19 MiB per attempt by design (ADR-0032). Owned by `P1-TSK-011`.
- Risk: **High**. Cx: M. DoD: `DOD-API`

**P1-TSK-027 — Authentication issues a session** — `COMPLETE` (2026-09-08)
- Context: identity / api
- Description: `POST /v1/authentications` returns a session on success.
- Why: `PHASE_1_PLAN.md` §11's M1.2 acceptance is *"an identity authenticates and **receives a
  session**"*, and `P1-TSK-010` delivered the endpoint without one because its declared dependency
  `P1-TSK-013` sits in M1.3. **M1.2 cannot close until this lands.** This is `P1-TSK-010`'s recorded
  remainder, not new scope — the `P1-TSK-026` precedent. `P1-DOC-001` then failed **exit criterion
  1** on it: no production path issued a first session at all, so the eight endpoints marked
  *"Auth: session"* were unreachable by any real client.
- Deps: P1-TSK-010, P1-TSK-013
- Implementation: `SessionIssue` in `identity` (the level is **not a parameter** — always
  `PASSWORD`), called from `AuthenticationService.attempt` inside the authentication transaction and
  the same security scope as the success audit record; `AuthenticatedSession` response record;
  `IdentityAuthentication.succeeded` gains a mandatory `SessionId`.
- Accept: **met, end to end.** `aLoginProducesAUsableSession` takes the token a login returned and
  opens `GET /v1/sessions` over real HTTP, with nothing inserted by the test — which is the step
  that was missing, since every earlier suite reached those endpoints by writing a session row.
- **The contract change is BREAKING, and the description above said "additive".** It is additive in
  everything except the one line that matters: removing `204` breaks a client written against it.
  The classifier said so, the diff was reviewed, and the change was accepted because nothing consumes
  this API and the alternative is a `/v2` for an endpoint whose first version was never usable.
  Recorded rather than corrected quietly — a plan that mislabels its own change is exactly what the
  byte-for-byte contract comparison exists to catch.
- **A session IS issued when a second factor is enrolled**, at `PASSWORD`. Withholding one until MFA
  is done looks stricter and makes step-up **unreachable**, because `MfaChallenge.elevate` takes a
  *current* session — the same shape of gap as the one this task closes. Assurance being a level
  rather than a boolean (ADR-0030) is what makes the composition safe.
- **`MfaBypassPathsAreEnumeratedTest` predicted this task by name** and failed until it came and
  wrote the entry: *"`P1-TSK-027` will add the second path and must come here and say so."*
- **The mutation harness broke production code and manufactured a defect that did not exist.** Its
  plant-verification assertion fired correctly on a mutation that *wraps* its target rather than
  replacing it; the script exited on that assertion and the restore was on the happy path only, so it
  left `sessions.insert` disabled. Seven suite failures, three reproductions and a probe later, the
  reported symptom — *the audit record commits and the session row does not, in one transaction on
  one connection* — was impossible, which is what pointed at the harness. **All ten mutation results
  were void and were re-run**: they had run against a codebase that was red whatever the mutation
  did. The restore is in a `finally` now. Seventh occurrence in this project of a mutation reporting
  something it did not measure, and the first where the harness broke the tree.
- **The plan's milestone boundary should be corrected rather than worked around**: §11 names session
  issuance in M1.2's scope while numbering the session tasks into M1.3. Either `P1-TSK-013` moves
  into M1.2, or M1.2's acceptance drops the session clause — a planning decision, recorded here
  rather than taken by an implementation task. **M1.2 now closes on the acceptance as written.**
- Risk: Medium. Cx: S. DoD: `DOD-API`

**P1-TSK-011 — Brute-force and credential-stuffing controls** — `COMPLETE` (2026-09-06)
- Context: identity / security
- Description: Failure counting, lockout and rate limiting on every credential endpoint.
- Why: ADR-0032 makes verification deliberately expensive, so the login endpoint is the platform's
  most CPU-costly operation and a denial-of-service target. Not an extra — part of the same design.
- Deps: P1-TSK-010
- **The task is two controls with two keys, and only one of them can be built honestly now.**
  Lockout (per identity) stops *guessing* and **must not change cost or response**; a rate limit
  (per source) stops *resource exhaustion* and may refuse cheaply, because it says nothing about any
  account. Conflating them is what produces a locked account that answers in a millisecond while an
  unknown one takes ~46 ms — `INV-IDN-07` defeated by the control added beside it.
- **Per-source is NOT built, and the reason is that building it would be harmful.**
  `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to N replicas behind a load balancer,
  so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in
  seconds, and **authentication goes down for everyone**. `X-Forwarded-For` is caller-supplied, and
  ADR-0034 settled that caller-supplied values are not trusted — there is no trusted-proxy
  configuration anywhere in this repository. The missing input is a deployment topology, not effort.
  Recorded as debt with that trigger.
- Implementation: `identity.authentication_failure` (V004) — **one row per identity**, updated by one
  atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING`. `AuthenticationThrottle`, `LockoutPolicy`,
  `IdentityAuditAction.AUTHENTICATION_LOCKED`, `finapp.identity.lockout`.
- **A row per identity, not per attempt**, and that is `INV-CON-03`: counting rows in a window is a
  read-then-count, so ten concurrent attempts at the threshold all read nine and all proceed. The
  invariant is catalogued at Phase 13 and is **first enforced here**.
- **Keyed on the login identifier, resolved by a subselect in the same statement.** The obvious
  alternative — look the identity up, then record if found — runs one query when the account is
  absent and two when present, which is a timing difference that discloses existence. It also keeps
  `VerificationOutcome` opaque: having verification report which identity it tried would put back
  the field `P1-TSK-008` removed.
- **The lock is time-bounded and self-healing, with no operator unlock.** Lockout is itself an
  attack — anyone who knows a login identifier can lock its owner out — and a lock needing an
  operator converts that cheap attack into a support-desk denial of service.
- Tests: threshold locks; a **correct** password is refused while locked and does not clear it; a
  locked identity costs the same **counted** derivation as an unknown one; success clears; the lock
  expires by the server's clock; an unknown identifier creates no row; **ten instances produce
  exactly ten**; the counter survives a restart; the lock is audited exactly once.
- Accept: **met** — the limit is not bypassable by concurrency, proven under real contention.
- **One defect found by the platform's own guard**: `recordFailure` wrote its audit record outside
  the security scope, and every lockout test failed with *"no actor has been established"*. That is
  `P0-TSK-032`'s refusal to default the actor doing its job — a default would have accepted the
  mistake silently and recorded the wrong party permanently (`INV-HIST-03`).
- **The completion gate found the lock was PERMANENT**, and the suite passed over it: after a lock
  expired, one failure re-locked the account for another full period, for ever. The tests missed it
  because the expired-lock test authenticated *successfully* afterwards, and a success deletes the
  row. The first fix was still wrong - it worked only because the shipped policy makes window and
  lock both 15 minutes, which is coincidence, not equivalence. The rule is now two independent
  clauses: a **served lock** ends the run whatever the window says; an **elapsed window** ends it
  provided no lock is live. Both proven load-bearing, with a test under a policy whose window and
  lock differ.
- **Seven mutations, all caught.**
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-012 — `P1-TST-002`: authentication failure modes** — `COMPLETE` (2026-09-06)
- Context: identity / test
- Description: The `PHASE_1_PLAN.md` §8 scenarios that concern authentication.
- Deps: P1-TSK-011
- **Two of the four were already met, and checking rather than assuming is what made the task worth
  doing**: *invalid credential* is `everyFailureLooksTheSame` plus `everyFailingPathDoesTheWork`,
  and *lockout* is `AuthenticationLockoutDatabaseTest`'s twelve tests. Restating either would be
  duplication that drifts, not coverage.
- **Concurrent login and credential change** — `LoginRacingACredentialChangeDatabaseTest`. The risk
  is not "does the old password still work for a moment" (it may; every platform accepts that). It
  is that upgrade-on-use **reinstates the replaced password**: supersede the new credential, insert a
  re-derivation of the old one, and the customer's password change is silently undone.
- **Database unavailable, fails closed** — `AuthenticationFailsClosedDatabaseTest`. Fails closed
  means *no success reported* **and** *no durable trace claiming otherwise*.
- **The first version of the race proved nothing, and two mutations said so.** It read the
  credential, waited for the change, then called `verify` — but `verify` does its own read, so the
  old password simply did not match and the upgrade path was never reached. The change now lands
  **inside** the verifier's window, via a store decorator.
- **Then a third mechanism had to be separated out.** The right end state is produced by the
  conditional supersede, the append-only trigger *and* the partial unique index. An outcome-only
  assertion cannot tell them apart, so the test asserts the coordination: no insert attempted, and
  nothing discarded — the login was *told* it lost rather than finding out by failing.
- **The kill was racing a one-millisecond derivation.** A 60 ms sleep before terminating the backend
  meant the transaction had already committed. Now deterministic: the decorator kills the
  transaction's own backend from inside the flow.
- **The completion gate found a negative assertion checking nothing**, proven by making the
  failure-counter predicate unsatisfiable and watching every assertion still pass. The positive
  control could not have caught it: it drives a *success*, and a success **clears** the counter, so
  only a **failed** authentication is a control for that query. And the third "trace" was derived
  from the other two — a restatement dressed as a check — while the **outbox** was uncovered, which
  matters because an announcement of a login that did not happen cannot be retracted.
- Accept: **met** — each demonstrated to fail when the control is removed. **Three mutations and two
  vacuity probes, all caught**; several only after the tests were corrected. Five consecutive runs
  green.
- **Not in scope, with the owner named**: concurrent login and revocation, and expired session, need
  sessions (M1.3); recovery abuse is M1.6; partial MFA enrolment is M1.4; the registration rows are
  `P1-TSK-006`'s; extending `MutationDemonstrationTest` to Phase 1 invariants is `P1-TSK-024`
  (`PHASE_1_PLAN.md` §9).
- Risk: Low. Cx: M. DoD: `DOD-TEST`

## P1-EPIC-03 — Sessions and Devices

### P1-CAP-03 — Sessions are real and revocation is immediate

#### P1-FEAT-05 — Session lifecycle

**P1-TSK-013 — Session aggregate and issuance** — `COMPLETE` (2026-09-07)
- Context: identity
- Description: Server-side sessions with an opaque identifier, assurance level, device and two
  expiry bounds.
- Why: ADR-0030. An eventually-revoked session is an unrevoked session.
- Deps: P1-TSK-003
- Implementation: `identity.session` (V005), `Session`, `SessionToken`, `SessionId`,
  `AssuranceLevel`, `SessionStatus`, `SessionPolicy`, `SessionStore` / `JdbcSessionStore`.
- **The token is stored HASHED, and the plan never said so.** A session identifier is a bearer
  credential: a database leak with plaintext tokens hands an attacker every live session with no
  work at all, which is worse than the credential table where Argon2 buys time. `PHASE_1_PLAN.md` §5
  already requires the recovery token to be hashed at rest; the same argument applies here.
- **SHA-256, deliberately not Argon2**, and it is not an inconsistency with ADR-0032: a password
  needs a work factor because it is *low-entropy*. A 256-bit random token has nothing to guess, so a
  work factor would buy no security while costing ~46 ms on every authenticated request.
- **Two identifiers, two jobs**: `SessionId` is a UUIDv7 for foreign keys and logs; the token is 32
  random bytes, because a UUIDv7 encodes its creation time and ADR-0030 forbids structure in the
  presented value.
- **There is no `EXPIRED` status.** Expiry is derived from the row's bounds, because a stored one
  needs a sweep to write it and until that sweep runs the database says `ACTIVE` about a session
  that is not — a second answer free to disagree with the first.
- **Both bounds live on the row**, so a policy change cannot retroactively extend sessions issued
  under the old one (`INV-HIST-04`'s reasoning), and each is asserted **alone** because a suite
  testing them together passes against an implementation checking only one.
- Accept: **met** — `NoProcessLocalSessionStateTest` fails the build on a field holding sessions,
  which is the shape ADR-0024's four patterns cannot see and transition risk **R7** named.
- **`secretsAreWrapped` fired on `Session.tokenHash` and the rule had the better argument**: a token
  hash in a log is a precise identifier of one customer's live session. Wrapped — the third time in
  this phase the right answer was to change the code rather than the rule.
- **The completion gate found a migration comment claiming a test that covers a different enum.**
  `V005` named `IdentityEnumMigrationTest` for `AssuranceLevel` and `SessionStatus`; it covers
  `IdentityStatus` alone, and nothing reconciled either new enum with its constraint —
  `P1-TSK-007` had established the pattern and this task did not follow it. `SessionMigrationTest`
  closes it.
- **Two aggregate methods were dead code with confident javadoc.** `idleBoundAfterUseAt` deleted;
  `isLiveAt` kept and made load-bearing, because it is the definition of liveness and the SQL is an
  implementation of it.
- **`AssuranceLevel` had no test**, which for `INV-IDN-05`'s named enforcement mechanism is the
  wrong number. Now swept over every pair, with the declaration order pinned.
- **Eight mutations, all caught.**
- Risk: **High**. Cx: M. DoD: `DOD-KERNEL`

**P1-TSK-014 — Revocation, immediate and multi-instance** — `COMPLETE` (2026-09-07)
- Context: identity
- Description: Revoke one session, revoke all, and revoke on credential change.
- Why: `INV-IDN-03`.
- Deps: P1-TSK-013
- Implementation: `SessionStore.revoke` / `revokeAllFor` / `revokeAllForExcept` (conditional
  `UPDATE`, row count as the outcome), `SessionRevocation` (audit), `IdentityAuditAction`
  `SESSION_REVOKED`, and `V006` adding the partial by-identity index `V005` deliberately omitted
  because no query needed one — bulk revocation is that query.
- Accept: **met** — `INV-IDN-03` demonstrated to fail when a session cache is introduced, and
  caught **twice**: the behavioural test fails and `NoProcessLocalSessionStateTest` fails
  independently.
- **The hard reading of `PHASE_1_PLAN.md` §8 was broken, and it was verified broken before the fix
  was written.** *"A session must never survive a concurrent revoke"* — a session **issued**
  concurrently with a revoke-all was still live afterwards, so an attacker holding the old password
  kept a live session across a password change, with every revoke-then-look-up test passing.
- **One explicit lock, not two, and a surviving mutation is what established that.** Bulk revocation
  takes `FOR UPDATE` on the identity; a session insert already takes `FOR KEY SHARE` on the same row
  **through its foreign key**, and the two conflict. An explicit lock on the issuing side was
  written, proved redundant, and removed — keeping it would read as the mechanism and hide the real
  one, so the next person to drop the foreign key would see a lock two lines away and conclude the
  serialisation was safe.
- **The race test asserts the coordination, not the outcome.** Its first version asserted the end
  state and a mutation removing the lock survived it: with the lock the insert serialises after the
  revoke and the new session is live; without it the insert races and the new session is also live.
  Same rows, opposite mechanisms. It now waits for PostgreSQL to report the issuer **blocked**.
- **One audit record per operation, never per session**: forty sessions ended by one decision is one
  record with the count in its change summary. The rows carry `revoked_at` and say *when*; the trail
  says *who decided*.
- **Nothing calls it yet** — endpoints are `P1-TSK-016`, the credential change is `P1-TSK-026`.
- **The completion gate found the headline assertion unscoped**: it counted *any* backend waiting on
  a lock, which is a different claim from "the insert is blocked". Now matched to the issuer's own
  statement text, with the `FOR UPDATE` mutation still caught.
- **Only the bulk path had its audit asserted.** A single revocation's record, its target being the
  session rather than the identity, and a revocation that ended nothing writing nothing — none was
  covered. All three now are.
- **Seven mutations. Five caught, one caught twice, and one survived correctly**, having removed
  redundant code.
- Risk: **High**. Cx: M. DoD: `DOD-KERNEL`

**P1-TSK-015 — Rotation on privilege change** — `COMPLETE` (2026-09-07)
- Context: identity / security
- Description: A new session identifier on login, step-up and credential change.
- Why: Session fixation. Elevating in place lets a stolen pre-elevation identifier become elevated.
- Deps: P1-TSK-013
- Implementation: `SessionRotation` composing the revoke and issue that already existed, plus
  `IdentityAuditAction.SESSION_ROTATED`. No schema change.
- **Login needed no code, and that is asserted rather than implemented.** Classic fixation is the
  attacker planting an identifier the victim then authenticates *with* — and a session's token comes
  from `SecureRandom` inside the server, with no path by which a client supplies one. A test proves
  the mechanism refuses; a redundant rotation step would have implemented a property already true.
- **The decision nothing had written down: rotation PRESERVES the absolute bound.** Resetting it
  would let anyone able to trigger a rotation hold a session indefinitely — step up, rotate, step up
  again — making the absolute lifetime advisory. That is the failure `P1-TSK-013` closed on the idle
  bound with `LEAST(…)`, returning through a different door. A step-up must not extend how long you
  can stay logged in.
- **Revoke first, issue only if the revoke won.** The ordering *is* the concurrency property:
  inserting first would leave a loser's session live, handing out two usable identifiers where there
  should be one. Ten instances produce exactly one replacement.
- **Audited as a rotation, never as a revocation**, and naming both identifiers — an investigator
  must be able to tell *"this session was ended"* from *"this session was replaced"*.
- Accept: **met** — no privilege change leaves the identifier unchanged.
- **One mutation survived correctly**: deriving the new token from the old derived it from the
  *hash*, which an attacker never holds. The dangerous version — reuse the old plaintext — is
  structurally unreachable, because `SessionRotation` never receives it. Now asserted reflectively,
  and a mutation adding that parameter is caught.
- **Nothing calls it yet**: step-up is `P1-TSK-017`, credential change `P1-TSK-026`, login
  `P1-TSK-027`.
- **Six mutations. Five caught, one survived correctly.**
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

**P1-TSK-016 — Session and device endpoints** — `COMPLETE` (2026-09-07)
- Context: identity / api
- Description: `GET /v1/sessions`, `DELETE /v1/sessions/{id}`, `DELETE /v1/sessions/current`,
  device recorded on the session.
- Deps: P1-TSK-014, ~~P1-TSK-020~~ — **the `P1-TSK-020` dependency was wrong for this task.** All
  three endpoints are available to every session-holder against their own resources: there is no
  role gate, and the control is **ownership**, which ADR-0031 puts in the domain and which is this
  task's stated acceptance criterion. Delivered in full without roles. (`P1-TSK-010`'s finding
  inverted — there the `Deps` line was right and the milestone boundary wrong.)
- **Found: `SessionRevocation.revoke` took an `owner` and did not check it.** The statement was
  `WHERE id = ? AND status = 'ACTIVE'`, so any caller could end any session by identifier, while the
  audit record asserted an owner nobody verified. Worse than an absent parameter, because the
  signature reads as though ownership is enforced. `P1-TSK-014` built it that way having no caller;
  this task is the first. Closed by `revokeOwned`, with the check in the `WHERE` clause.
- **Found: nothing in the platform could authenticate a request, and no task owned it.**
  `PHASE_1_PLAN.md` §7 marks **eight** endpoints `Auth: session`; `P1-TSK-020` and `P1-TSK-021` both
  presuppose a caller, and `P1-TSK-027` hands a token out rather than consuming one. Built here as
  `SessionAuthenticationInterceptor`, because `GET /v1/sessions` means *my* sessions and without it
  the task has no deliverable. **Fifth backlog defect of this class in Phase 1, and the widest.**
- Nine mutations, all caught — two only after they found real gaps.
- Risk: Medium. Cx: M. DoD: `DOD-API`
- Implementation: ownership checked in the domain, never at the boundary from a request parameter;
  device recorded, **never scored** — trust is a Phase 13 risk decision.
- Tests: a negative ownership test — one identity cannot list or revoke another's sessions.
- Accept: **met** — `SessionOwnershipDatabaseTest`; dropping `AND identity_id = ?` fails three tests.

## P1-EPIC-04 — Multi-Factor Authentication

### P1-CAP-04 — A second factor that cannot be bypassed

**P1-TSK-017 — TOTP enrolment** — `COMPLETE` (2026-09-07)
- Context: identity / security
- Description: Enrol a TOTP factor, with the secret encrypted at rest and never emitted.
- Deps: P1-TSK-013, and in practice **P1-TSK-016** — both endpoints are `Auth: session`, so this is
  the first task to consume the session authentication that task had to build.
- **`INV-IDN-01` cannot apply here, and that is catalogued rather than glossed.** A TOTP secret must
  be recoverable — the server computes the expected code *from* it — so irreversibility is
  impossible and **confidentiality replaces it**: the new **`INV-IDN-08`**, with `INV-IDN-01` gaining
  an explicit scope note so a reader finding a recoverable secret in an identity table does not have
  to guess whether it is a defect. The platform now has **72 invariants**.
- **The secret IS emitted, once**, and the plan's *"never emitted"* cannot be met literally: the QR
  code *is* the secret. Bounded to one response, to the proven owner, never retrievable again.
- **Deviation from "vetted library", stated:** JDK primitives plus RFC 6238 arithmetic, because no
  library implements HMAC itself and **the RFC publishes test vectors** — correctness is demonstrated
  against the specification rather than against a library's reputation. No primitive is invented.
- Eleven mutations, all caught — four only after they found real gaps.
- Implementation: secret wrapped and encrypted; enrolment is not complete until
  confirmed by a valid code.
- Tests: a partially enrolled factor never satisfies a challenge; the secret appears in no
  response, log or event.
- Accept: **met** — `aStartedEnrolmentIsNotUsable`; a mutation making `findActive` ignore status is caught.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-018 — Challenge, verification and assurance elevation** — `COMPLETE` (2026-09-07)
- Context: identity / security
- Description: `POST /v1/authentications/mfa`; a verified challenge produces a `MULTI_FACTOR`
  session.
- Deps: P1-TSK-017, P1-TSK-015
- Implementation: replay refused; elevation produces a **new** session identifier; the level is
  recorded on the session, and operations ask for a minimum level.
- Tests: **met** — four replay tests (same code, earlier code, across instances, and the code that
  confirmed the enrolment); `elevationRotatesTheIdentifier`.
- Accept: **met** — `assuranceIsRequiredAndSatisfied`, with the elevated session as its positive
  control; a mutation removing the check is caught.
- **Replay needed a mechanism enrolment did not**: a challenge leaves the factor `ACTIVE` and has no
  state to consume, so the last accepted **time step** is recorded and anything at or before it is
  refused (RFC 6238 §5.2).
- **`@RequiresAssurance` has no production caller**, per `PHASE_1_PLAN.md` §66. A probe proves it.
- Ten mutations: nine caught, one survived correctly.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-019 — `P1-TST-003`: MFA cannot be bypassed** — `COMPLETE` (2026-09-07)
- Context: identity / test
- Description: One test per enumerated alternative path to a session.
- Why: `INV-IDN-05`. Every real MFA bypass is a path nobody enumerated, which is why the paths are
  enumerated here rather than the property asserted once.
- Deps: P1-TSK-018
- Tests: an older `PASSWORD` session; a refresh; re-enrolment of a second factor; recovery
  (once M1.6 exists); a direct call to any endpoint that issues a session.
- Accept: **met** — one named test per path; `assuranceIsALevelAndNotABoolean` fails when `atLeast`
  becomes equality.
- **Found a real bypass by probing**: a `PASSWORD` session could begin a replacement enrolment with
  an attacker-controlled secret. Refused only by a partial unique index, surfacing as a **500** —
  the property held by accident of a constraint rather than by a decision. **Replacing a confirmed
  factor now requires that factor**; a first enrolment does not, because it cannot.
- **The enumeration is held against the code** (`MfaBypassPathsAreEnumeratedTest`): a new
  session-issuing path fails the build until it is named with the reason it is not a bypass. A list
  of tests is a snapshot; this is what survives Phase 4.
- **Recovery is a recorded remainder** for M1.6 — asserting absence over an unmapped route would
  pass vacuously.
- Six mutations, all caught.
- Risk: **High**. Cx: M. DoD: `DOD-TEST`

## P1-EPIC-05 — Authorization and Actor-Attributed Audit

### P1-CAP-05 — Every action is permitted and attributable

**P1-TSK-020 — Roles, permissions and the boundary check** — `COMPLETE` (2026-09-07)
- Context: identity / security
- Description: Role and permission model; a declarative, deny-by-default permission check before
  the handler.
- Why: ADR-0031, `INV-IDN-04`, `INV-AUD-03`.
- Deps: P1-TSK-013
- Implementation: roles assigned to identities; the check declared per endpoint and enforced before
  dispatch — the `P0-TSK-017` interceptor pattern; an endpoint with no declaration is refused.
- Tests: a **negative authorization test for every protected endpoint** — the Phase 1 exit
  criterion; an undeclared endpoint is refused rather than permitted.
- Accept: **met** — `/probe/undeclared` is refused `403` **with a valid session**, which is the
  stronger form: a version presenting no session would pass against an implementation that merely
  required authentication.
- **Deny-by-default is enforced twice, and neither replaces the other.** The interceptor refuses at
  run time, because ADR-0031 says *refused*; `EveryEndpointDeclaresARuleTest` fails the **build**,
  because a deployment defect a customer finds by receiving a 403 has been found too late. A static
  sweep cannot see a handler registered at run time and a runtime check cannot fail a build.
- **Permissions are resolved per request, never stamped on the session** — a role carried on a
  session survives its own revocation until that session expires, and *"remove their access now"*
  becomes a promise the architecture cannot keep (`INV-IDN-03`'s reasoning, applied to
  authorization). Asserted with the **same** session token across the revoke.
- **A denial is audited**, because it is the only trace an attacker leaves: a permitted privileged
  action is audited by the operation itself, and a refused one has no operation to do it. Written
  **after** the security scope opens, so the record names the person rather than the platform.
- **`api.Forbidden`, deliberately not a distinct code** — unlike `identity.AssuranceRequired` this
  is not actionable, and a special code would imply a remedy the client does not have.
- **Backlog defect, sixth of this class in Phase 1**: `PHASE_1_PLAN.md` §7 lists
  `POST /v1/identities/{id}/suspension` and `POST /v1/identities/{id}/roles` — the only two
  endpoints in the phase that would carry a `@RequiresPermission` — and **no task owns either**.
  `P1-TSK-022` writes the audit record for a suspension it does not build. Recorded rather than
  invented here: an admin endpoint added to give the annotation a production caller would be a
  security surface chosen to suit a test, which is `P1-TSK-018`'s recorded reasoning for
  `@RequiresAssurance`. Carried as the new **`P1-TSK-028`**.
- **Recorded limit:** with one role holding both permissions, the role→permission mapping cannot be
  meaningfully mutated — the first mutation attempted (`permissions()` returns *all* permissions)
  was a **no-op** and was re-aimed at the role granting *nothing*. It becomes testable at the
  second role.
- **The completion gate found an endpoint that reads as protected and is public**: a handler
  declaring **both** `@Unauthenticated` and `@RequiresPermission` was answered `200` with no session,
  and **both** guards passed it. Worse than an absent check, because the declaration asserts a
  control nobody applies (`P1-TSK-016`'s unowned-revoke finding, in a new place). **Refused rather
  than resolved to the stricter reading**, which would have hidden it, and closed in both places.
- **`V010` named a migration test that did not exist** — fifth occurrence this phase. Written, and
  `RoleName` gained the first test it has ever had.
- **Two dead `sqlValueList()` methods, disposed of differently** — `RoleName`'s kept and made
  load-bearing by the migration test, `PermissionName`'s deleted, because a permission is never a
  column under ADR-0031. The `P1-TSK-013` shape.
- **`assign`'s concurrency claim had no test.** Two live rows would make revocation **partial** —
  reporting success while the identity keeps the role.
- **Thirteen mutations, all caught** — six added by the gate.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-028 — The two administrative endpoints** — `COMPLETE` (2026-09-08)
- Context: identity / api
- Description: `POST /v1/identities/{id}/suspension` and `POST /v1/identities/{id}/roles`.
- Why: `PHASE_1_PLAN.md` §7 lists both and **no task owns either** — sixth backlog defect of this
  class in Phase 1, found by `P1-TSK-020`. They are the only endpoints in the phase that would
  carry `@RequiresPermission`, so without them `IDENTITY_SUSPEND` and `ROLE_ASSIGN` have no
  production caller and `P1-TSK-022` audits a suspension nothing performs.
- Deps: P1-TSK-020, P1-TSK-021
- Implementation: `@RequiresPermission` at the boundary **and** the ownership rule in the domain —
  ADR-0031 requires both, and here the second is the one that stops an administrator suspending
  themselves out of the platform or assigning themselves a role they were not given.
- **Not built inside `P1-TSK-020`, deliberately**: an admin endpoint invented to give the
  annotation something to do would be a security surface chosen to suit a test, which is
  `P1-TSK-018`'s recorded reasoning for shipping `@RequiresAssurance` with a probe endpoint.
- Tests: a negative authorization test per endpoint; a negative **ownership** test — self-suspension
  and self-elevation both refused; the audit record names the administrator and targets the subject.
- Accept: **met** — both refuse a session holding no role, and both refusals are audited against the
  person who attempted them, with positive controls so refusal is not blanket.
- **The blocking finding: suspension did not suspend anybody.** `JdbcSessionStore.findByToken`
  filters on the **session's** status and never joins `identity.identity`, and `CredentialVerifier`
  refuses a suspended identity only at *authentication* — so a suspension stopped the next login and
  left the session an attacker is holding **working until its absolute bound expired**, while the
  administrator got a success response. `suspend` now revokes every session in the same transaction
  (`INV-IDN-03`: an eventually-revoked session is an unrevoked session), asserted with the **same
  token** across the suspension. Joining identity status into the session lookup was the alternative
  and was rejected: a second table in the hottest query on the platform, per request, to enforce
  once what a revoke enforces once per decision.
- **Ownership is inverted here, and that is the whole shape of the task.** Everywhere else the rule
  is *the resource must belong to the caller*; here it is **the subject must not be the actor**. It
  cannot live in `@RequiresPermission`, which is static per handler and knows nothing about which
  identity the path names.
- **What refusing self-elevation buys is stated honestly rather than overclaimed**: it is **not** a
  containment control, because an administrator holding `ROLE_ASSIGN` can escalate through a second
  account. What it buys is that the trail **never contains a self-loop** — every escalation names
  two parties, and a self-grant reads like a system action rather than a decision somebody took.
  Refusing self-*suspension* is a different argument: there is no reinstatement endpoint, so it is a
  one-way door out of the platform.
- **`OwnershipIsScopedTest` gained a fifth class, `ADMINISTERED`, because this task broke an
  assumption the rule rested on.** It excluded `IdentityId` on the reasoning that it *is* the owner —
  true of every operation written before, and false of an administrative one, where the identifier
  comes from a URL and names a different person. The exclusion is now conditional on the statement
  reaching `identity.identity` by primary key, and three methods are classified.
- **A pre-existing blind spot in that rule was closed**: `statementOf` read only the method's own
  string literals, so a statement built from a table-name **constant** was invisible to
  `referencesTheOwner`. It had never been reached because every earlier statement happened to
  mention `identity_id` literally. Inlining the constant to satisfy the detector was the alternative
  and would have been a change made to please a rule rather than to state a property.
- **The first administrator cannot be created through the API**, and that is a decision: a bootstrap
  endpoint is a privileged surface with nothing in front of it, and a seeded migration row puts an
  administrator into production for ever. Documented in `README.md` §5e, with the consequence
  recorded — that first grant has **no actor in the audit trail**.
- **A mutation survived and found a test passing for the wrong reason.** `anUnknownSubjectIs404`
  used `UUID.randomUUID()`, and `IdentityId.of` validates **UUIDv7** (`P0-TSK-012`) — so a v4 was
  refused as *malformed* and never reached the service. The test proved only that a v4 is rejected;
  it is now driven with a well-formed identifier that names nobody, and the mutation is caught.
- **Not built, and recorded rather than silently absent**: no reinstatement endpoint (`P1-TSK-032`),
  no second role — `P1-TSK-020`'s note that the role→permission mapping becomes mutation-testable at
  the second role stands, because inventing one to give a mutation somewhere to land is a surface
  chosen to suit a test — and no four-eyes, which `INV-AUD-04` schedules and whose approver column
  is recorded debt.
- **The completion gate found an outcome name that can be false**: `ALREADY_SUSPENDED` is reached
  by a `CLOSED` identity too, which is not suspended but gone permanently — a caller would read the
  operation as having effectively succeeded. Renamed `NOT_ACTIVE`, for what is *checked*, with the
  `CLOSED` path now tested.
- **And a javadoc claiming a bounds-parity test that did not exist** — seventh occurrence this
  phase. The drift would fail at the **last write** as a 500, after the transition and the session
  revocations had run inside a transaction that then rolls back. The test's own first version failed
  on correct code, which is the more useful outcome: `@Size` has no `RECORD_COMPONENT` target, so it
  lands on the field and a component lookup returns null for a working constraint.
- **Nine mutations, all caught** — two added by the gate. 863 hermetic, 453 database.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-032 — Reinstatement: the other half of suspension** — `TODO`
- Context: identity / api
- Description: `DELETE /v1/identities/{id}/suspension`, moving a `SUSPENDED` identity back to
  `ACTIVE`.
- Why: **`P1-TSK-028` shipped a one-way door.** `IdentityStatus` models `SUSPENDED` as explicitly
  reversible and `Identity.reinstate` exists with no caller, but no endpoint reaches it — so an
  administrator who suspends the wrong person cannot undo it through the platform, and the remedy is
  an operator with database access. That is the shape of manual intervention this project treats as
  debt everywhere else.
- **It is also what makes refusing self-suspension correct rather than merely tidy**: with
  reinstatement, an administrator locking themselves out is recoverable, and the argument for the
  refusal changes. Both decisions should be revisited together.
- Deps: P1-TSK-028
- Implementation: the mirror of `suspend` — conditional `UPDATE … WHERE status = 'SUSPENDED'`, a
  required reason, an audit record and an event. **Sessions are not restored**, because they were
  ended and `INV-HIST-01` does not un-happen things; the person logs in again.
- Tests: a negative authorization test; reinstatement of an identity that is not `SUSPENDED` is a
  conflict; the audit record names the administrator; ten instances produce one transition.
- Accept: all four, and a suspended identity can authenticate again afterwards.
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

**P1-TSK-021 — Ownership checks in the domain** — `COMPLETE` (2026-09-08)
- Context: identity, party
- Description: Resource-scoped operations check ownership against authoritative state.
- Why: ADR-0031. The most common authorization defect is a legitimate permission used against
  someone else's resource — every check passes and nothing is logged as a denial.
- Deps: P1-TSK-020
- Implementation: the check lives in the module that owns the state, never at the boundary from a
  request parameter.
- Tests: a negative ownership test per resource-scoped operation.
- Accept: **met, and it was already met when the task opened** — `revokeOwned` and `findLiveFor`
  carry `identity_id = ?` in the **statement** (`P1-TSK-016`), and MFA resolves the enrolment from
  the session's identity (`P1-TSK-017`). Probed rather than assumed: dropping the predicate fails
  **six** tests in `SessionOwnershipDatabaseTest`. Restating any of it would be duplication that
  drifts, not coverage — the `P1-TSK-012` precedent.
- **So the deliverable is the part genuinely missing, and the task's own text names it**:
  `OwnershipIsScopedTest`, the register held **against the code**. Every persistence method taking
  a resource identifier is classified `OWNER_SCOPED`, `AUTHORITATIVE_ID` or `NOT_OWNED`, and a new
  one fails the build. The `MfaBypassPathsAreEnumeratedTest` shape, because a list of tests is a
  snapshot and the operation added in Phase 4 will not be in it.
- **The recorded limit is narrowed rather than removed** (ADR-0031 amended): the rule forces
  classification and does not decide safety. It cannot see that an owner-scoped statement binds the
  *right* owner — the named negative test does — cannot verify an `AUTHORITATIVE_ID` claim, and is
  blind to an ownership decision that issues no SQL.
- **Five correct statements look exactly like the defect**, which is why the rule classifies rather
  than forbids: `revoke`, `touch`, `confirm`, `consumeStep` and `supersede` all target a row by
  primary key with no owner predicate, and all five are safe because the identifier came from an
  owner-constrained read. A rule that merely forbade the shape would have produced five false
  positives on its first run.
- **`party` owns no resource-scoped operation**, asserted rather than assumed — the first one fails
  the build until it is classified.
- **The gate found the rule aimed at the wrong half of the defect.** The shape this repository has
  actually shipped is a method that takes an owner and **never uses it** (`P1-TSK-016`). A second
  assertion now requires a statement handed an `IdentityId` to name the owner — twelve of thirteen
  satisfy it, and the thirteenth locks `identity.identity` where `id = ?` *is* the owner. It also
  covers the **bulk disclosure** the first half cannot see: `findLiveFor` takes no resource
  identifier, so nothing would have noticed it losing its scope.
- **The coverage guard had deviated from its four siblings** — a bare `isNotEmpty()` left `party`
  protected by nothing, the `P0-TSK-008` finding.
- **A latent defect in the helper**, exposed by widening its input: the string-literal regex
  backtracked catastrophically and overflowed the stack on the first long method body.
- **Ten mutations, all caught** — two added by the gate.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

**P1-TSK-022 — Actor-attributed audit** — `COMPLETE` (2026-09-08)
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
- Accept: **met.** Probed rather than assumed — six of the seven implementation clauses were
  **already true**: the interceptor establishes a scope per authenticated request (`P1-TSK-016`),
  all **13** audit sites call `SecurityContext.require()` and none defaults, every writer takes the
  caller's `Connection`, immutability is at `DB-PRIVILEGE` (`P0-TSK-022`), an unestablished actor is
  refused, and 15 actions are catalogued. Restating any of it would be duplication that drifts.
- **So the deliverable is the three things that were missing, and each is an acceptance clause
  nothing checked:**
  - `AuditCompletenessTest` — every action **emitted or declared not to be**, held against the code.
    This closes the limit `P0-TSK-023` recorded against itself: *"it cannot detect a privileged
    action that writes no record at all."* Two Phase 1 actions were silently unemitted.
  - `SystemActorCallSitesAreEnumeratedTest` — *"each is justified"* made mechanical. **Two** sites,
    both on unauthenticated paths; a third fails the build. ADR-0021 called this *"the greppable
    list"*, and grep is a thing somebody has to remember to run.
  - `AuditNamesTheActorDatabaseTest` — **no record written under an authenticated request names the
    platform**, asserted over the *rows* rather than per action. Each earlier task asserted its own
    record; nothing asserted the trail.
- **`IDENTITY_SUSPENDED` cannot be audited by this task** — `P1-TSK-028` owns the endpoint, and
  inventing one to give the action a caller would be a surface chosen to suit a test.
- **The enumeration is at method granularity**, and the one place that matters is closed:
  `AuthenticationService.attempt` holds both branches, so a separate assertion requires the success
  branch to still establish a real actor.
- **The gate found a claim my own test did not support** — its display name promised *"reason where
  the registry needs it"* and nothing looked at the column. Sixth occurrence this phase. The claim is
  **unassertable** over this sweep: `AuditRecord` refuses such a record at construction, and the two
  actions requiring a reason have no production caller in Phase 1.
- **The coverage guard had deviated from its siblings again** — `P1-TSK-021`'s gate found the same
  one task earlier. Closed in both suites; `ProductionModules.of` widened rather than duplicated.
- **Ten mutations, all caught** — two added by the gate.
- Risk: **High**. Cx: M. DoD: `DOD-SEC`

## P1-EPIC-06 — Account Recovery

### P1-CAP-06 — Recovery that is not the way in

**P1-TSK-023 — Account recovery** — `COMPLETE` (2026-09-08)
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
- Accept: **met.** Nine abuse-case tests, each named for the route it closes; removing the
  channel-verification join fails two of them.
- **BLOCKING FINDING, resolved here: `INV-IDN-06` had no subject.** It requires *"a previously
  registered and **verified** channel"*, and no channel existed anywhere — no type, no table, no
  verification, and **no backlog task owning one**. Seventh backlog defect of this class in Phase 1
  and the most consequential: the others were missing endpoints, this was a missing **precondition
  of the invariant**. Built here on the `P1-TSK-016` precedent, minimally.
- **Recovery issues NO session**, which is the sharpest decision. The conventional design logs you
  in on completion, and `INV-IDN-06`'s second clause forbids exactly that. So *"recovery used to
  reach a `MULTI_FACTOR` operation"* cannot be **attempted**, and
  `MfaBypassPathsAreEnumeratedTest`'s statement — nothing new creates a session — stays true. That
  guard's recorded remainder is now answered in it.
- **Bound to the credential it was raised against**, so an attacker who initiates before the customer
  changes their password loses. A predicate, not a procedure, because a predicate cannot be
  forgotten by a future credential-change caller.
- **The token is delivered nowhere**, and that is `PHASE_1_PLAN.md` §8's recorded seam.
- **Four existing guards refused the new code and all four were right** — the unwrap whitelist, the
  system-actor enumeration, the ownership register and the published-contract diff.
- **The gate found two claims with no test**: neither statement's **concurrency** claim was
  exercised (the `P1-TSK-020` finding about `assign`), and the **boundary was never driven** over
  HTTP (the `P1-TSK-017` finding). Six properties were unasserted, the first being whether the
  token appears in a response — the one the whole design rests on.
- **Fourteen mutations: thirteen caught, one survived correctly.** Three survivors along the way
  each found a real gap — a test passing for the wrong reason, no test that ever suspended an
  identity, and no concurrency coverage at all.
- **Out of scope, recorded:** channel change and multiple channels, phone, recovery when the
  authenticator is also lost, and the delivery adapter (Phase 15).
- Risk: **High**. Cx: L. DoD: `DOD-SEC`

## P1-EPIC-07 — Phase Review

**P1-TSK-025 — `@ArchTest` rules do not run in the `architectureTest` tier** — `COMPLETE` (2026-09-08)
- Context: platform / testing
- Description: `./gradlew architectureTest` executes the `@Test` methods of an ArchUnit suite and
  **not its `@ArchTest` rule fields**, so the tier named for architecture rules runs none of them.
- Why: Found by `P1-TSK-003`, which planted a `double` in a new module to prove the existing rules
  now cover it. `:app:architectureTest` reported **BUILD SUCCESSFUL**; `:app:test` failed two
  rules on the same code. `NoFloatingPointMoneyRulesTest` contributes **2 cases to
  `architectureTest` and 7 to `test`** — the five missing ones are the rules themselves, including
  the coverage guard.
  **Enforcement is not lost**: `build` runs `test`, which runs all seven, so CI has always been
  checking them. What is lost is the tier task's meaning — a developer running `architectureTest`
  before pushing is told the architecture is fine by a task that checked none of it, which is the
  "green while checking nothing" failure this repository has met five times.
  This is the same root cause as the `ModuleBoundaryRulesTest` skip that `P0-TSK-036` found: ArchUnit
  executes `@ArchTest` **fields** under its own JUnit engine, and the tier task's tag filtering does
  not select them.
- Deps: none
- Implementation: make the ArchUnit engine's rule fields selectable by the tier task, or make
  `TestTaxonomyTest` assert per suite that the tier runs as many cases as `test` does.
- Tests: the taxonomy guard must fail when a suite's rules are selected by one task and not the
  other — proven by mutation, not asserted.
- Accept: a `double` planted in production code fails `./gradlew architectureTest`, not only
  `./gradlew build`.
- Accept: **met, and proven by performing it.** With a `double` planted in production code,
  `./gradlew :app:architectureTest` exited **0** before the fix and **1** after — the same planted
  code, the same command.
- **The finding is worse than this item recorded: the rules were not missing, they were in the
  WRONG TIER.** `unitTest` selects by *exclusion*, so it took all **28** untagged rule fields;
  `architectureTest` selects by *inclusion* and got none. And `ModuleBoundaryRulesTest` — the oldest
  suite here, enforcing `app → platform → sharedkernel`, with no `@Test` method at all — produced
  **no result file** in the architecture tier: not a suite that ran zero cases, a suite that did not
  appear.
- **Root cause established by disassembling the engine, not by reading documentation.**
  `javap` on `AbstractArchUnitTestDescriptor.findTagsOn` shows it loads exactly one annotation:
  `com.tngtech.archunit.junit.ArchTag`. JUnit's `@Tag` is invisible to it, so every `@ArchTest`
  field carried no tag at all.
- Implementation: `@ArchTag("architecture")` beside `@Tag("architecture")` on all **seven**
  `@AnalyzeClasses` suites — the mechanism ArchUnit provides for exactly this, never used here
  because nobody had asked what its engine does with a tag.
- **Why no guard saw it, and this is the part worth keeping.** `theTiersPartitionTheHermeticSuite`
  asserts a **sum**, and the sum was right: every rule was in exactly one tier. **A check on a total
  cannot see a misallocation that preserves the total.** Same class as `P1-TSK-024`'s unreadable
  register rows and `P0-TST-008`'s rule that could not fail.
- Tests: `TestTaxonomyTest.everyArchUnitSuiteIsTaggedForBothEngines` — for every `@AnalyzeClasses`
  class the `@Tag` and `@ArchTag` value sets must be **equal**, stated as *both engines must agree
  which tier this class is in* rather than as the fix's shape.
- **A Launcher-based behavioural guard was investigated and rejected**: discovering with
  `includeTags("architecture")` in-process is the property itself, but `junit-platform-launcher` is
  **not on `testRuntimeClasspath`** (verified) — Gradle injects it into the worker — so it would
  need a new dependency plus verification-metadata and lockfile regeneration. Disproportionate for a
  Low-risk `Cx: S` item (`EXECUTION_PROTOCOL` rule 4).
- **The chosen guard's limit is recorded rather than left to be discovered**: it asserts the two
  annotations *agree*, not that ArchUnit reads `ArchTag`. If ArchUnit ever read `@Tag`, the guard
  would demand a now-unnecessary annotation — **erring in the safe direction**, which is why the
  shape was chosen: a false requirement is a build failure somebody investigates, a false pass is
  silence (`P0-TSK-026`'s reasoning).
- Risk: Low — no enforcement gap to close, only a misleading task. Cx: S. DoD: `DOD-TEST`

**P1-TSK-024 — Extend the mutation register to Phase 1** — `COMPLETE` (2026-09-08)
- Context: platform / test
- Description: A row in `MUTATION_TESTING.md` for every `INV-IDN-*`, and the guard extended to
  require them.
- Why: `MutationDemonstrationTest` currently enforces demonstrations for Phase 0 invariants only.
  Seven new invariants without it would be exactly the regime `INV-IDN` was created to escape.
- Deps: all `P1-TSK-*`
- Implementation: extend the guard from "Phase 0" to "every phase up to and including the current
  one", so the extension is not needed again in Phase 2.
- Tests: proven by mutation — a Phase 1 invariant with no register row fails the build.
- Accept: **met, and the acceptance as written would have been too narrow.** There are **eight**
  `INV-IDN-*` — `P1-TSK-017` added `INV-IDN-08` mid-phase — and **nine** Phase 1 invariants, because
  `INV-AUD-03` is `Phase: 1 onward` and is not in that group at all. A guard extended only to
  `INV-IDN-*`, which is what this item said, would have missed it.
- **Two had no row: `INV-IDN-02` — the one the task is named for — and `INV-AUD-03`.** Both were
  already demonstrated; the rows record work done rather than work invented.
- **The finding is that nine rows did not parse.** The grammar admitted exactly one backticked
  reference and nothing after it, and the register is written with lists and trailing prose. Eight of
  the nine were written during Phase 1, so the guard was **not** checking that the tests they name
  exist — a register whose rows the guard cannot read reports coverage it does not have, which is
  `P0-TST-008`'s finding in the artefact built to prevent exactly that.
- **Proven precisely rather than argued**: a reference in *second* position naming a test that does
  not exist **survives** the old parser and is **caught** by the widened one.
- **The current phase is derived from `CURRENT_STATE.md`**, so Phase 2 needs no change — which is the
  "extension not needed again" this item asked for. A constant would be the stale list this
  repository closes by derivation everywhere else.
- **§4 generalised to `P{n}-TST-*`, and the two phases declare them differently**: Phase 0 gives them
  their own headings, Phase 1 names them inside task headings. Anchoring to either shape finds
  nothing for the other and passes vacuously.
- **One mutation survived and found a defect in this task's own new assertion**: `everyRowNamesATest`
  read the merged references *per invariant*, so emptying one of `INV-IDN-06`'s two rows left the
  merge non-empty. Now per row.
- **The gate found the same defect one level out, in this task's own fix**: a row failing the row
  pattern *entirely* is not in the map at all, so `everyRowNamesATest` cannot see it. Proven by a
  form-column typo that left the build green. Every §2 line that looks like a row must now parse.
- Seven mutations, all caught.
- Risk: Low. Cx: S. DoD: `DOD-TEST`

**P1-DOC-001 — Phase 1 review record** — `COMPLETE` (2026-09-08)
- Context: project
- Description: The written phase review per `PHASE_GATES.md` §4, and ADR-0029…**0034** to `Accepted`.
- Deps: all Phase 1 items
- Accept: **met** — [`reviews/PHASE_1_REVIEW.md`](reviews/PHASE_1_REVIEW.md). All eight areas, the
  twelve universal criteria and the six Phase 1-specific ones, each with evidence.
- **The review finds the exit gate does not pass, and that is what conducting one is for.**
  **Phase 1 remains `IN_PROGRESS`** (`PHASE_GATES.md` §4). Two criteria fail:
  - **Criterion 1** — **no production path issues a first session.** `Session.issue` ← `SessionRotation`
    ← `MfaChallenge.elevate`, which requires a session, behind a `@RequiresSession` controller; and
    `POST /v1/authentications` returns `204` with no body. So the **eight** endpoints the plan marks
    `Auth: session` are unreachable by any client, and the phase objective — *"prove it, hold a
    session"* — is not met end to end. Owner: `P1-TSK-027`.
  - **Criterion 6** — the plan names six `finapp.identity.*` meters and **two** exist. The missing
    four are the phase's critical flows, including the recovery rate the plan itself annotates
    *"recovery is the ATO vector; its rate is a security signal"*. Owner: the new `P1-TSK-029`.
- **Neither failure is architectural**: the design work is done, and what is missing is a connection
  between two things the phase built, plus four meters.
- **ADR-0029…0034 accepted despite the failures**, on Phase 0's recorded reasoning: criterion 10 is a
  **precondition** of the gate rather than a reward for passing it.
- **Three documentation drifts found by hand-diffing what no guard covers**, and all three corrected:
  the plan declares 15 endpoints and 12 exist (two of the absentees owned by **nobody**, now
  `P1-TSK-030`); an exemption in `AuditCompletenessTest` rested on a statement that was **false**; and
  `CURRENT_STATE.md` carried an imprecision about the phase-specific criteria.
- **Area 2 has no subject and says so** — Phase 1 creates no posting — rather than reporting a pass,
  as Phase 0's review did for the same reason.
- Risk: Low. Cx: S. DoD: `DOD-DOC`

**P1-TSK-029 — The four missing Phase 1 meters** — `COMPLETE` (2026-09-08)
- Context: identity / platform
- Description: `finapp.identity.mfa_challenge`, `session_lifetime`, `recovery` and `active_sessions`.
- Why: **Criterion 6's remediation.** `PHASE_1_PLAN.md` §Observability names six meters and two
  exist. The criterion asks for metrics for the phase's *critical flows*, and the missing four are
  exactly those.
- Deps: none — every flow they measure is built.
- Implementation: counters and a timer at the existing call sites; `active_sessions` as a **gauge over
  the database**, for the reason `P0-TSK-029` gives for the outbox gauges — a count held in the
  application reports nothing when the application is the thing that is wrong, and is per instance
  besides. `MetricConventionTest` enforces the naming and forbids a request-derived tag.
- **`finapp.identity.recovery` is the one that matters most.** A takeover campaign is a rise in
  recovery initiations, and today that is visible only by querying the audit trail — which is
  evidence, not monitoring. `INV-AUD-01` is satisfied and criterion 6 is not, and the distinction is
  why the platform has both.
- Tests: each meter asserted against the live registry, as `P0-TSK-029` does; `DashboardQueriesResolveTest`
  extended if the dashboard gains panels.
- Accept: **met.** All six exist and are named correctly, and criterion 6 is now a **build
  failure** rather than a review opinion: `PlannedMetersExistTest` reads the plan's own §10 table and
  asserts every meter it names is in the live registry — bidirectionally, so a meter renamed or a
  plan naming one nobody built both fail.
- **Three of the four planned names could not be registered.** `mfa_challenge`, `session_lifetime`
  and `active_sessions` carry **underscores**, which `MetricNames.NAME` forbids. The plan was
  corrected rather than the convention widened, because the correction is free: Micrometer
  translates dots to the backend's idiom, so both forms produce the identical Prometheus series.
- **The worse finding: the meters that "existed" did not exist until the flow ran.**
  `MeterRegistry.counter(...)` creates the meter on the first call, so a freshly started instance
  published no series at all for authentication, lockout or registration — and an alert on a rate
  had nothing to evaluate at exactly the moment it was needed. All counters are registered at
  construction now, and `PlannedMetersExistTest` runs no flow, so it can only pass against that.
- **Recovery is two meters rather than one tagged by `stage`**, because `stage` is not in
  `ALLOWED_TAG_KEYS` and widening that list was refused: it exists to make such an addition an
  explicit decision, and a naming exists that needs none. It also makes *"recovery initiation rate"*
  one series rather than a filtered sum.
- **The initiation counter distinguishes what the `202` deliberately hides**, which is correct
  rather than a leak: a metric is never visible to the caller, and a rise in `refused` is somebody
  walking a list of identifiers.
- **`session.active` counts LIVE sessions, not `ACTIVE` ones.** With no `EXPIRED` status and no
  sweep, the obvious query counts sessions nobody can use — wrong in the *reassuring* direction.
- **`session.lifetime` measures one population and says so**: expiry is never observed, bulk
  revocation is one decision rather than forty samples, and supersession is a replacement.
  `SessionStore.revokeOwned` returns the lifetime its own `UPDATE` computes, so no query was added
  to a security-critical operation to feed a metric.
- **Two guards refused the new code and both were right**: `INV-MON-01` on Micrometer's
  `ToDoubleFunction` (exemption extended, same case as `OutboxMetrics`), and `TestTaxonomyTest`,
  which produced a design improvement rather than a tag — `IdentityMetrics` takes a connection
  source now, `OutboxBacklog`'s shape.
- Risk: Medium — a security signal nobody can see. Cx: S. DoD: `DOD-OBS`

**P1-TSK-031 — Two fixtures read `now()` twice and assume it moves forwards** — `TODO`
- Context: identity / test
- Description: `AuthenticationCostsTheSameDatabaseTest.suspend` (and the same shape wherever a
  fixture inserts with `created_at = now()` and then updates `status_changed_at = now()`) can write
  a row whose status change precedes its creation.
- Why: **Observed, not theorised.** During `P1-TSK-025`'s gate the suite failed with
  `identity_status_change_is_not_before_creation` on a row whose `status_changed_at` was **225 ms
  before** its `created_at`. Probing the container clock showed three successive `SELECT now()`
  calls **429 ms apart** while three host `date` calls were 33 ms apart — the container's clock runs
  fast and is corrected backwards, which `CURRENT_STATE.md` §Local Environment Prerequisites
  documents and which that section says to check before treating such a failure as a defect.
  **The constraint is right and the fixture is fragile**: two statements, two `now()` reads, and
  nothing requires the second to be later than the first.
- Deps: none
- Implementation: the established remedy — back-date the row explicitly rather than relying on the
  clock (`OutboxRelayTest.backDate`'s precedent), or set both columns in one statement so they come
  from one `now()`.
- **Recorded rather than fixed by `P1-TSK-025`**, which is that task's own precedent: `P1-TSK-003`
  found `P1-TSK-025` by an acceptance probe and recorded it rather than fixing it in passing
  (`EXECUTION_PROTOCOL` rule 4).
- Tests: the fixture must produce a valid row under a clock that moves backwards between the two
  statements — provable by setting the second timestamp behind the first deliberately.
- Accept: the suite is not sensitive to a backwards clock correction.
- Risk: Low — a fixture, not production code, and the constraint it trips is the platform being
  correct. Cx: S. DoD: `DOD-TEST`

**P1-DOC-002 — Re-run the Phase 1 exit review** — `TODO`
- Context: process
- Description: Re-assess criteria 1 and 6 and record the verdict.
- Why: `PHASE_GATES.md` §4. `P1-DOC-001` returned the phase to `IN_PROGRESS` on two failures; both
  are now closed (`P1-TSK-027`, `P1-TSK-029`). **A phase does not become `COMPLETE` because the
  remediation landed** — it becomes `COMPLETE` when the review says so, and an implementation task
  declaring its own phase complete is the shape the gate model exists to prevent.
- Deps: P1-TSK-027, P1-TSK-029
- Implementation: an addendum to `reviews/PHASE_1_REVIEW.md` re-assessing the two criteria against
  the code, not against this backlog; the phase status updated in `CURRENT_STATE.md` if it passes.
- **The four open items do not block it**: `P1-TSK-025`, `-026`, `-028` and `-030` are named by no
  universal or phase-specific criterion. The gate blocks on the criteria, not on the backlog being
  empty — the distinction `P1-DOC-001` recorded.
- Accept: every universal criterion assessed with evidence, and a verdict.
- Risk: Low. Cx: S. DoD: `DOD-DOC`

**P1-TSK-030 — `GET /v1/me` and `PATCH /v1/me`** — `TODO`
- Context: party / api
- Description: Read and change your own profile.
- Why: **`PHASE_1_PLAN.md` §7 declares both and no backlog task owned either** — the eighth backlog
  defect of this class in Phase 1, and the first found by a **review** rather than by the task that
  tripped over it.
- Deps: P1-TSK-021 (ownership), P1-TSK-022 (audit)
- Implementation: `@RequiresSession`; the profile is read and written **scoped to the proven
  identity's party**, never to an identifier from the request (ADR-0031); `PATCH` emits
  `party.ProfileChanged`, which is catalogued and currently declared unemitted for exactly this
  reason.
- **It gives `party.ProfileChanged` its producer**, and `AuditCompletenessTest`'s entry for it must be
  removed when this lands — the guard will say so.
- Tests: a negative ownership test per operation; the audit record names the person; a display name
  carrying control characters is refused at the boundary (`P1-TSK-006`'s finding).
- Accept: both endpoints exist with ownership enforced in the domain and an audit record for the
  change.
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

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
