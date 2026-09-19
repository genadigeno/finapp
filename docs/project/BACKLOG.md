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

Status: `COMPLETE` (2026-09-04) *(this header read `IN_PROGRESS` until `P3-DOC-001`'s review —
the backlog carries a second copy of each phase's status, and three of four were stale; the
same second-copy decay as the ADR index, corrected as a review finding)*

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

Status: `COMPLETE` (2026-09-09) — exit gate ruled passed by `P1-DOC-002`
([`reviews/PHASE_1_REVIEW.md`](reviews/PHASE_1_REVIEW.md)); 34 of 34 items. One item created by
the exit review remains open and is scheduled into Phase 2's M2.1: `P1-TSK-033`.
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

**P1-TSK-032 — Reinstatement: the other half of suspension** — `COMPLETE` (2026-09-09)
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
- Accept: all four, and a suspended identity can authenticate again afterwards — **met**, with the
  acceptance driven end to end: the subject is *registered over HTTP* rather than inserted, because
  a fixture row without a credential cannot authenticate at all and the assertion would have been
  unreachable. Suspend → login refused → reinstate → login succeeds, **and the pre-suspension
  session stays dead**.
- **Both self-refusal decisions were revisited together, and both stand on corrected arguments**:
  self-suspension stays refused because reinstatement makes the door two-way only when a *second*
  administrator exists, which the platform does not guarantee; self-reinstatement gets its own
  `SELF` branch, nearly unreachable (a suspended identity holds no live session) and kept for the
  trail property — no administrative record ever names one party twice.
- **The permission is `IDENTITY_SUSPEND`, not a new one** — `ROLE_ASSIGN`'s own "grant or revoke"
  shape: one capability, two directions, and a third permission held by the only role that exists
  would be vocabulary with no decision behind it.
- **The reason travels in a DELETE body**, because it is free prose that may name a person or an
  incident and a query parameter reaches access logs (`INV-AUD-02`).
- **`NOT_SUSPENDED` is named for what is checked**: `ACTIVE` and `CLOSED` both land on the 409, and
  only the first could honestly be called "already done" — `CLOSED` is terminal (`INV-LIFE-04`) and
  proven to stay closed.
- **Four mutations, all caught by the intended assertion**: the wrong from-status in the
  conditional, the `SELF` check removed, the audit call removed, and the permission annotation
  removed.
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

**P1-TSK-031 — Two fixtures read `now()` twice and assume it moves forwards** — `COMPLETE` (2026-09-09)
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
- **The shape was in five files, not two** — surveyed rather than trusted: the named
  `AuthenticationCostsTheSameDatabaseTest`, plus `AuthenticationEndpointDatabaseTest`,
  `CredentialVerificationDatabaseTest`, `RecoveryAbuseDatabaseTest` (identity), and
  `PartyAndIdentitySchemaDatabaseTest.insertCustomer` (the customer twin,
  `customer_status_change_is_not_before_opening`). Every other timestamp-ordering constraint is
  reached by a single statement, an already back-dated write, or a privilege-refused one.
- **The INSERT is back-dated and the UPDATE stays at `now()`**: the update models what production
  writes; "both columns in one statement" cannot fix an insert-then-*update* pair, whose two reads
  are in different statements by construction.
- **Proven in-suite with its own vacuity control**
  (`PartyAndIdentitySchemaDatabaseTest.fixturesSurviveABackwardsClockCorrection`): a simulated
  correction of thirty minutes — absurdly worse than the observed 225 ms — succeeds against a
  back-dated row, and the same update against a row written at plain `now()` is still refused, so a
  pass proves the back-dating carries the property rather than the constraint being dead.
- Risk: Low — a fixture, not production code, and the constraint it trips is the platform being
  correct. Cx: S. DoD: `DOD-TEST`

**P1-TSK-033 — `POST /v1/me/credential`: a logged-in person can change their password** — `COMPLETE` (2026-09-09)
- Context: identity / api
- Description: The endpoint `PHASE_1_PLAN.md` §7 has declared since the phase was planned —
  `session, MULTI_FACTOR`, revokes every other session — and nothing built.
- Why: **Found by `P1-DOC-002`'s recount, the ninth backlog defect of this class in Phase 1** — and
  the sharpest form of it, because the first review's area 7 table attributed the endpoint to
  `P1-TSK-026`, whose description reads *"Extend `POST /v1/registrations`"* and never included it.
  **The capability gap is real**: a person with a stolen password and no verified channel cannot
  replace their credential through the platform — session revocation ends the attacker's sessions,
  not their knowledge, and recovery requires a verified channel registration does not create.
  `IdentityAdministration`'s javadoc cited "a credential change" as an incident-response tool; the
  claim is corrected until this exists.
- Deps: none — every mechanism exists (`CredentialStore.supersede`, `RawPassword`, the deriver,
  `SessionRevocation.revokeAllForExcept`, whose javadoc has described this exact caller since
  `P1-TSK-014`).
- Implementation: require the **current** password re-proven in the request (a stolen session must
  not suffice to change the credential it rides on), derive outside the transaction
  (`P1-TSK-026`'s reasoning), supersede conditionally, revoke every **other** session
  (`revokeAllForExcept` — the person changing their password keeps the session they are doing it
  from), audit against the person, `MULTI_FACTOR` per the plan's row.
- Tests: ownership (the session's identity, no identifier in the request — `SESSION_DERIVED`);
  a wrong current password refused without disclosure; other sessions dead, the current one alive;
  the old password refused and the new one accepted afterwards.
- Accept: all four, and the plan's §7 row stops being a declaration nothing implements.
- **Does not reopen the Phase 1 gate**: no exit criterion names it (`P1-DOC-002`'s recorded
  ruling, on the review's own `P1-TSK-028`/`-030` precedent). Scheduled by the Phase 1 → 2
  transition.
- **Gate evidence (2026-09-09)**: composition, not new mechanism — `CredentialVerifier.matchCurrent`
  re-proves the current password (no upgrade-on-use: the credential is about to be superseded), the
  new one is derived outside the transaction, the supersede is conditional so ten concurrent changes
  yield one credential, every other session is revoked and the caller's own rotated at the same
  assurance level. **The plan's `MULTI_FACTOR` row was corrected**: the requirement is conditional
  on a factor existing (a domain check, not a static annotation — the `P1-TSK-019` finding), so an
  MFA-enrolled identity on a `PASSWORD` session gets the actionable `identity.AssuranceRequired`
  while a password-only one changes at `PASSWORD`. **Five mutations, all caught by the intended
  assertion** — one (the wrong-password lockout count) survived first and the test was strengthened
  to prove the counter drives a lock rather than only writing an audit record. The session-token
  unwrap, the rotation call site, the new audit action and the two secret request fields each joined
  their guard's register with a claim.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`

**P1-DOC-002 — Re-run the Phase 1 exit review** — `COMPLETE` (2026-09-09)
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
- Accept: every universal criterion assessed with evidence, and a verdict — **met**: all twelve
  re-assessed with recounted evidence (17 endpoints, 20 auditable actions, 864 hermetic and 465
  database tests — none inherited), all pass, and **Phase 1 is `COMPLETE` (2026-09-09)**. The
  `PARTIAL` phase-specific criterion closes: every registered action is emitted or declared with a
  Phase 15 owner.
- **The recount found the ninth backlog defect of the class, in the review's own area 7 table**:
  `POST /v1/me/credential` is planned, unbuilt and owned by nobody — the table had attributed it to
  `P1-TSK-026`, falsely, and a false owner is worse than no owner for the reason a false exemption
  is. Recorded as `P1-TSK-033`; a javadoc citing the missing capability as an incident-response
  tool was corrected.
- **Three debt rows owned by "Phase 1" were resolved**, because a `COMPLETE` phase cannot own open
  debt: broker adapter → the Phase 1 → 2 transition (first consumers are Phase 2's);
  registration throttling → Phase 15, merged with per-source rate limiting; the loopback-credential
  row's trigger was reached **and handled** by `MfaKey`'s own tested confinement, remainder →
  Phase 5.
- Risk: Low. Cx: S. DoD: `DOD-DOC`

**P1-TSK-030 — `GET /v1/me` and `PATCH /v1/me`** — `COMPLETE` (2026-09-08)
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
- Accept: **met.** Both exist, `party.ProfileChanged` has its producer, and the change is audited.
- **Ownership is enforced by there being no parameter, which is the strongest form and changes what
  the test can be.** Neither endpoint takes a path variable, a query parameter or a body field
  naming a party — ADR-0031's defect is *trusting an identifier out of the request*, and there is
  none to trust. So an attacker cannot name a victim, the usual negative test is impossible to
  write, and the test proves the **resolution chain** instead. That is a weaker shape of test for a
  stronger shape of control, and saying so beats implying they are the same.
- **The catalogue description promised what the classification forbids.**
  `PARTY_PROFILE_CHANGED` read *"recording what was held before and after"* — those values are
  display names, `RESTRICTED-PII`, and `audit_record.change_summary` is `RESTRICTED-FINANCIAL`.
  Those are **peers, not a hierarchy**: a name written there would sit outside the PII rules
  (retention, subject access, erasure), and ADR-0022 forbids reclassifying a column that holds data.
  The record now names the **field**, never the value — the choice `PartyRegistration` had already
  made — and the description was corrected in the enum and in `AUDITABLE_ACTIONS.md`.
- **`AUTHORITATIVE_ID` was tried and `OwnershipIsScopedTest` refused it**, correctly: the read in the
  chain is `JdbcIdentityStore.findById`, which `P1-TSK-028` classified `ADMINISTERED` because an
  administrator names its subject from a URL — so citing it as owner-constrained is false, and the
  guard said so in those words (*"every operation citing it inherits the gap"*). A sixth class,
  **`SESSION_DERIVED`**, records what is actually true: the identifier comes from a proven `Session`
  held in memory, which is `P1-TSK-021`'s recorded uncheckable case arriving. Its entry names the
  **endpoint**, and a new assertion checks the one mechanically checkable thing that is also the real
  control — that endpoint's handlers accept no request-supplied identifier.
- **Two guards were written to break on this day and both did.** `partyHasNothingToScope` asserted
  that `party` owned no resource-scoped operation; and `PartyAndIdentitySchemaDatabaseTest`'s grant
  assertion said *"nothing about a party changes yet; the grant arrives with the capability"*.
- **`PATCH` failed with SQLState 42501 before a line of it was reviewed** — the application role had
  no `UPDATE` on `party.party`, because `V002` was written when nothing changed one.
  `V004` grants **`UPDATE (display_name)` only**, so `kind` and `registered_at` stay unwritable:
  those are facts rather than fields. Column-level is the mechanism `P0-TST-007` found can widen a
  privilege *invisibly*, used here deliberately to narrow — and the assertion that checks its
  narrowness is what makes that visible.
- **Absence and explicit null are the same thing here, and the limit is recorded**: a record cannot
  distinguish them, which costs nothing while `display_name` is `NOT NULL` and can never be cleared.
  The first genuinely nullable field needs a wrapper type or JSON Merge Patch — a decision for the
  task that has one.
- **A no-op rename succeeds and writes no audit record**: an entry reading *"changed from Ada to
  Ada"* is noise, and it would let anybody pad the trail at will.
- **The completion gate found a javadoc of mine asserting the opposite of what the code does**:
  `updateProfile` said the value *"is normalised by `PartyName` on the way in"*, and `PartyName`
  normalises nothing - its own documentation refuses to. Eighth occurrence this phase, mine again.
  The decision stands on a truer argument and the reason was corrected rather than the behaviour.
- **One test replaced several**: eight `PATCH` body shapes, none produces a 500, and it is where the
  absent-versus-null decision is checked rather than only documented.
- **Seven mutations, all caught.** 864 hermetic, 459 database.
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

---

# Phase 2 — KYC/KYB and Consent

Status: `COMPLETE` (2026-09-13, [`reviews/PHASE_2_REVIEW.md`](reviews/PHASE_2_REVIEW.md))
*(read `READY` until `P3-DOC-001` — the stale-second-copy finding)*. Entry gate passed
2026-09-09 by the Phase 1 → 2 transition
([`reviews/PHASE_1_TO_2_TRANSITION.md`](reviews/PHASE_1_TO_2_TRANSITION.md)).
Elaborated to task granularity by the same transition.

The engineering plan is [`PHASE_2_PLAN.md`](PHASE_2_PLAN.md); decisions are ADR-0035…0038
(`Proposed`); the properties to protect are `INV-KYC-01`…`06` and `INV-CNS-01`…`04`. Every task
below assumes **N concurrent instances, N never 1**, and inherits the standing security rules
(`.claude/rules/security.md`) and the DoD profile it names.

**Two inherited items are scheduled into M2.1**: the broker adapter (`P2-TSK-001`, re-owned to
this phase by `P1-DOC-002`) and **`P1-TSK-033`** (`POST /v1/me/credential`, which keeps its
Phase 1 ID — IDs are permanent — and is worked as the second task of the phase).

| Epic | Capabilities |
|------|-------------|
| P2-EPIC-01 KYC case management | Case lifecycle; verification checks; decision recording; evidence retention |
| P2-EPIC-02 KYB and beneficial ownership | Business verification; ownership graph; control-person identification |
| P2-EPIC-03 Document capture | Secure upload; encryption; audited access (object storage deferred — ADR-0036) |
| P2-EPIC-04 Screening | Sanctions adapter; PEP adapter; adverse media adapter; hit disposition |
| P2-EPIC-05 Manual review | Review queue; reviewer decision with reason codes; four-eyes on high risk |
| P2-EPIC-06 Consent | Versioned consent text; grant/withdraw lifecycle; consent-dependent capability gating |
| P2-EPIC-07 Onboarding gate | Queryable verification status for downstream contexts |
| P2-EPIC-08 Foundations settle | Broker adapter and first consumer; module skeletons; reviewer role; `P1-TSK-033` |

Document-capture tasks live under the epic that consumes them; the epic labels above are the
capability map, and the task list below is the schedule.

## P2-EPIC-08 — Foundations settle (M2.1)

**P2-TSK-001 — The broker adapter: outbox events reach Kafka** — `COMPLETE` (2026-09-09)
- Context: platform / integration
- Description: An `EventPublisher` implementation over a Kafka producer, plus a cluster-safe
  schedule for the relay, so the events the outbox has held durably since `P1-TSK-006` are
  actually published.
- Why: **The trigger was reached 2026-09-06 and re-owned here by `P1-DOC-002`** — this phase
  holds the first consumers. Decides the topic scheme and acknowledgement configuration
  (`acks=all`; idempotent producer), which ADR-0005 deferred to the first adapter.
- Deps: none.
- Implementation: adapter in `platform` behind the existing port; topic per aggregate type;
  producer configured for at-least-once with the relay's dedupe key as the record key so
  per-aggregate ordering survives partitioning; relay scheduling through an explicit, injected
  scheduler (ADR-0024 forbids ambient scheduling — the mechanism is part of this task's design).
- Out of scope: any consumer (P2-TSK-002); broker TLS (no non-loopback broker exists; the debt
  row stands).
- Distributed: N relays already safe (advisory locks, `P0-TSK-020`); the adapter must not
  introduce a second ordering authority; publish-then-crash re-publishes (at-least-once, stated).
- Security: no payload changes; `EventPayload` charset unchanged; no credential in producer
  config beyond externalised bootstrap address.
- Invariants: `INV-EVT-01`…`04`.
- Tests: database tier with a Kafka container — published once per fact under relay crash/restart
  (`P0-TST-005` extended to the real broker); ordering per aggregate under two relays; the
  dead-letter path still blocks its aggregate.
- Accept *(corrected by the design — the original line promised what the port's own javadoc
  refuses)*: exactly one publication on the non-crash path, envelope and bytes intact;
  per-aggregate order under concurrent relays; a crash between broker acknowledgement and the
  publication mark **redelivers with the same `eventId`** — at-least-once per ADR-0005, with the
  consumer inbox as the dedupe — never a lost event; broker unavailability blocks and backs off,
  bounded; a non-loopback plaintext bootstrap refused at startup.
- **Gate evidence (2026-09-09)**: every corrected acceptance clause has a named green test in
  the new kafka tier; **five mutations, all caught by the intended assertion** — the dedupe
  header dropped, return-before-acknowledgement (rewritten once, because the first form was
  caught by *compilation* and proved nothing), the guard inverted, the counter unfed — plus the
  two in-suite exemption proofs. The composition root never touches a Kafka type: producer
  construction and configuration live in the adapter's factory, inside the one exempted package.
- Risk: Medium. Cx: M. DoD: `DOD-KERNEL`

**P2-TSK-002 — The first consumer path: Kafka in, inbox dedupe, effect once** — `COMPLETE` (2026-09-09)
- Context: platform / integration
- Description: A Kafka consumer shell that hands records to `InboxConsumer`, so duplicate and
  redelivered records produce one effect (`INV-IDEM-04`), proven against a real broker.
- Why: the inbox (`P0-TSK-021`) has never met a real transport; consumer restart and rebalance
  are the §Failure Engineering modes nothing has yet exercised.
- Deps: P2-TSK-001.
- Implementation: manual offset commit **after** the inbox transaction commits, so a crash
  between effect and commit redelivers into the dedupe rather than losing the record; consumer
  group per consuming module.
- Out of scope: any business handler (P2-TSK-007 is the first).
- Distributed: two consumers in one group across a rebalance produce one effect per record;
  offset-commit-after-effect is the load-bearing ordering and is asserted, not described.
- Invariants: `INV-IDEM-04`, `INV-EVT-04`.
- Tests: duplicate delivery, redelivery after crash-before-commit, rebalance mid-batch — all
  counting effects in a side-effect table (`P0-TSK-021`'s idiom).
- Accept: at-least-once transport, exactly-once effect, demonstrated under restart and rebalance.
- **Gate evidence (2026-09-09)**: `KafkaInboxDeliveryKafkaTest`, five tests against a real broker
  and a real PostgreSQL — end to end with bytes verbatim and correlation on the inbox row; a wire
  duplicate (the relay's own crash duplicate, replayed on purpose) is one effect; a crash between
  the database commit and the offset commit redelivers into the dedupe; two consumers across a
  rebalance effect once per record; a handler failure rolls the dedupe record back so the
  redelivery retries. Offset-commit-after-effect is **asserted, not described** — a journalling
  consumer proves the ordering, and inverting it in code fails exactly that test. **Five
  mutations, all caught by the intended assertion.** The broker rule gained its second exemption
  (`platform.inbox.kafka`) with fixture proofs in both directions, and the gate found two stale
  architecture-document claims left from `P2-TSK-001` (the "deliberately absent" adapter, the
  "still empty" exemption), both corrected with provenance.
- Risk: Medium. Cx: M. DoD: `DOD-KERNEL`

**P2-TSK-003 — `kyc` and `consent` module skeletons** — `COMPLETE` (2026-09-09)
- Context: kyc / consent
- Description: Two modules, two schemas (`V001` each: schema, ownership, `REVOKE PUBLIC`,
  `USAGE` to `finapp_app`), isolation tests both directions, audit-action enums with their
  catalogue rows.
- Why: the `P1-TSK-003` shape; every derived guard (classification, taxonomy, boundaries,
  secrets) must see the modules from their first commit.
- Deps: none. Out of scope: any table beyond the schema bootstrap.
- Distributed: none (DDL). Security: schema privileges are the enforcement floor for everything
  after.
- Tests: isolation both directions with non-vacuity halves; migrations apply/validate/re-apply;
  a planted unclassified column fails the build.
- Accept: `./gradlew build` green with both modules and every existing sweep provably covering
  them (planted-`double` probe, the `P1-TSK-003` acceptance).
- **Gate evidence (2026-09-09)**: five probes, all caught by the intended guard — a `double`
  planted in each module fails the floating-point rules; a cross-module dependency added to
  `kyc` fails **its isolation test itself**, not merely lock resolution; a catalogue section
  removed fails the registry reconciliation; an unclassified column migrated into `kyc` fails
  the classification guard against a real database. Five audit actions declared under the
  deliberately-few licence (the two reason-required ones are `INV-KYC-02`/`-04` speaking; case
  opening and check outcomes left to their owning tasks on purpose), each with a
  `NOT_YET_EMITTED` entry naming its emitter. **The gate's own finding: sibling isolation had
  quietly become one-directional** — the Phase 1 isolation tests forbade only each other, so
  `party` could have grown a dependency on `kyc` unnoticed; both extended to forbid all
  siblings. The `build-logic` lockfile drift (kotlin RC3→GA floating resolution) was met again
  and reverted on the `P2-TSK-001` precedent.
- Risk: Low. Cx: S. DoD: `DOD-BUILD`

**P2-TSK-004 — `KYC_REVIEW` permission and the `KYC_REVIEWER` role** — `COMPLETE` (2026-09-09)
- Context: identity
- Description: A second role and third permission; `V0xx` migration for the role constraint
  (`RoleName.sqlValueList` regenerates it, `V010`'s reconciling test catches drift).
- Why: the reviewer surface must exist before any reviewer endpoint; **and the role→permission
  mapping finally becomes mutation-testable** — `P1-TSK-020` recorded that limit in as many
  words: *"it becomes testable at the second role."* That mutation is this task's acceptance.
- Deps: none.
- Distributed: role grant/revoke concurrency already proven (`P1-TSK-020`); no new mechanism.
- Security: `KYC_REVIEWER` deliberately does **not** hold `IDENTITY_SUSPEND` or `ROLE_ASSIGN` —
  the first real least-privilege split between administrative populations.
- Invariants: `INV-IDN-04`, `INV-AUD-03`.
- Tests: the role-mapping mutation ("a role granting everything") now caught; migration/enum
  reconciliation; a `KYC_REVIEWER` refused by an administrative endpoint and vice versa.
- Accept: the recorded `P1-TSK-020` limit closes, demonstrated by the previously-impossible
  mutation failing the build.
- **Gate evidence (2026-09-09)**: the acceptance mutation — `KYC_REVIEWER` granting everything —
  is caught **twice**, by `RoleNameTest`'s exact-grant assertions and independently by the
  cross-population HTTP refusal test; five further mutations (administrator gaining
  `KYC_REVIEW`, the grants swapped, the reviewer granting nothing, `V013` losing the value) all
  caught by the intended assertion. The reconciliation test was redesigned: it pinned the
  constraint to `V010`, which is applied history, so it now **derives** the latest migration
  defining the constraint and separately pins `V010`'s original literal as untouched history —
  and its first directory-only version threw on this module's own **jar** (the `P0-TSK-036`
  finding as a file), fixed to read both classpath shapes. The grant is driven through the real
  `POST /v1/identities/{id}/roles`, so the boundary enum, the regenerated constraint and the
  per-request resolution are all on the tested path. The contract diff is one added request-enum
  value, reviewed and accepted (the classifier's BREAKING label errs safe by design; a client
  that never sends the value cannot be broken by it).
- Risk: Low. Cx: S. DoD: `DOD-SEC`

## P2-EPIC-01 — KYC case management (M2.2)

**P2-TSK-005 — The KycCase aggregate and its lifecycle** — `COMPLETE` (2026-09-09)
- Context: kyc
- Description: `KycCase` with the §5 machine, `V002` case table — status `CHECK`s generated from
  the enum, `NOT NULL` policy version, and the **one-open-case partial unique index** on
  `(customer_id) WHERE status NOT IN (terminal)`.
- Why: the phase's spine. The one-open-case rule is a cross-aggregate uniqueness rule, so only
  the database can arbitrate it (`P1-TSK-005`'s reasoning, verbatim).
- Deps: P2-TSK-003.
- Out of scope: checks, documents, decisions — later tasks; no endpoint yet.
- Distributed: concurrent opens for one customer produce one case (the index is the mechanism;
  ten instances, ten connections); conditional status transitions whose row count is the outcome.
- Invariants: `INV-LIFE-02`, `INV-LIFE-04`, `INV-KYC-05` (the case is where the authority lives).
- Tests: exhaustive invalid-transition sweep **derived from the machine** (`P1-TSK-005` idiom);
  10-way open race; terminal-state immutability.
- Accept: every invalid transition rejected by the aggregate; one open case under contention.
- **Gate evidence (2026-09-09)**: the exhaustive cross-product sweep is derived from the machine
  (`P1-TSK-005` idiom) and both terminal states are swept separately; ten instances with ten
  connections produce one case, with the nine losers **converged** onto the winner's case behind
  a savepoint rather than errored — the semantics both callers-to-come need. **Two generated
  schema artefacts, not one**: the status `CHECK` from `sqlValueList()` and the one-open-case
  index predicate from `sqlTerminalValueList()`, both reconciled, so a state added without a
  decision about whether it frees the slot cannot land quietly. A decided case demonstrably
  frees the slot (the successor-case test). `policy_version NOT NULL` pinned at open
  (`INV-HIST-04` at the moment it is free). The ownership rule fired and got its entries: the
  store's `moveStatus` classified `AUTHORITATIVE_ID` on `findOpenFor`, and the predicate
  vocabulary gained the third entry its own javadoc predicted (`customer_id = ?`, the kyc
  schema's owner column). `kyc.CaseOpened` declared with the aggregate whose design fixed its
  meaning, catalogued, `NOT_YET_EMITTED` naming `P2-TSK-006`/`-007`. **Five mutations, all
  caught by the intended assertion** — a terminal state reopened, the aggregate check dropped,
  the index made total, convergence removed, the conditional transition made unconditional.
- Risk: Medium. Cx: M. DoD: `DOD-KERNEL`

**P2-TSK-006 — `POST /v1/me/kyc` and `GET /v1/me/kyc`** — `COMPLETE` (2026-09-13)
- Context: kyc / api
- Description: The caller opens (or converges on) their case and reads its status —
  `SESSION_DERIVED`, no identifier anywhere in the request (the `/v1/me` shape).
- Why: the customer's half of onboarding; **"ensure my case exists" semantics** so it converges
  with the auto-open consumer (P2-TSK-007) instead of racing it.
- Deps: P2-TSK-005, P2-TSK-019 (the consent gate: opening a case is the first gated capability).
- Distributed: double-tap and retry meet the one-open-case index; the answer names the existing
  case rather than erroring.
- Security: status shaping — a screening hit is indistinguishable from ordinary processing in the
  customer-facing status (`IN_PROGRESS` covers both; tipping-off, `INV-IDN-07`'s reasoning); the
  response carries no screening vocabulary at all.
- Invariants: `INV-CNS-01` (gate), `INV-KYC-05`.
- Tests: over HTTP; ownership by construction (no parameter); the hit-invisibility assertion;
  consent-absent refusal.
- Accept: a registered, consented person reaches an open case; a second POST is the same case.
- **Gate evidence (2026-09-13)**: the acceptance end to end over HTTP
  (`KycCaseEndpointDatabaseTest`): a consented person's POST is 201 with an open case,
  audited **as the person** — this door is their own act, where the consumer door records
  the platform — and announced once; the second POST is 201, the same case, one record, one
  announcement, because creation is distinguished by the records and never the answer (the
  convergence idiom, and `KycCaseStore.Opening`'s javadoc corrected where it claimed
  otherwise). **The gate's refusal earned its code here**: `409 consent.ConsentRequired`,
  one code for three causes deliberately, mapped once in `ApiErrorHandler` so every later
  gated surface answers alike — and **absence and withdrawal are one refusal,
  byte-identical** with only the correlation identifier excluded (`INV-CNS-01` at the
  surface); a refused POST writes nothing structurally, because the exception rolls the
  transaction back. **Two extractions, each earned by the second caller arriving**: the
  tipping-off shaping into `CustomerFacingCaseStatus` (one definition of a security-control
  mapping; a case in review and a case in checks answer byte-identically with no review
  vocabulary, mutation-proven) and the record-and-announce block into `CaseOpeningTrail`
  (the `CheckOutcomeTrail` rule — with the actor deliberately the DOOR's own, because the
  consumer is the platform's policy act and the endpoint is the person's). `findLatestFor`
  on the read, so `REJECTED` is never a 404; a decided customer's POST opens a successor
  case (the freed slot, `INV-LIFE-04`), gated like the first. **The contract classifier
  caught a real breaking change**: a second handler named `view` renamed the KYB surface's
  published operationId to `view_1` — withdrawn by renaming the method rather than
  accepted; the baseline is 75 added lines, zero removed, all COMPATIBLE. The status-move
  fixture met the container clock drift (`P1-TSK-031`'s shape, again) and pins
  `GREATEST(now(), opened_at)`. **Six mutations, all caught by the intended assertion** —
  the gate call dropped, the converged path recording too, the shaping leaking `IN_REVIEW`,
  the platform recorded as the opener, the refusal mapping removed, the announcement
  dropped. 1019 hermetic tests, 584 database tests, 14 kafka tests. **M2.2 CLOSES: 7 of
  7** — every implementation milestone of the phase is closed.
- Risk: Medium. Cx: S. DoD: `DOD-SEC`

**P2-TSK-007 — The first production consumer: a registration opens a case** — `COMPLETE` (2026-09-09)
- Context: kyc / integration
- Description: `kyc` consumes `party.CustomerRegistered` through P2-TSK-002's shell and opens
  the case eagerly.
- Why: `DELIVERY_PLAN.md` §Phase 2.8 — downstream contexts react; and it makes the broker path
  carry a real business flow rather than a test's.
- Deps: P2-TSK-002, P2-TSK-005.
- Distributed: the inbox dedupes the event; the one-open-case index arbitrates against a
  concurrent `POST /v1/me/kyc`; both paths converge on one case — asserted under the race.
- Invariants: `INV-IDEM-04`, `INV-KYC-03`'s mechanism.
- Tests: duplicate event → one case; event racing the endpoint → one case; consumer restart
  mid-handling → one case.
- Accept: registration alone yields exactly one open case, through the real broker.
- **Gate evidence (2026-09-09)**: the acceptance is driven on the deployed chain — the app
  booted whole with relay AND consumer enabled, registration over HTTP, no call from the test
  anywhere in the middle. **A naming drift corrected**: this item said the consumer handles
  `party.CustomerRegistered`, which is the *audit action*; the event is `party.CustomerOpened`,
  whose aggregate IS the customer — so the handler reads no payload at all, the envelope's
  metadata-only principle paying off at the first real consumer. Created announces
  (`kyc.CaseOpened` audit — its first emitter — plus `kyc.KycCaseOpened` on the outbox, caused
  by the consumed event); converged is silent. The platform is the actor, the fourth enumerated
  `enterSystem()` site and the class every future consumer will be. **Six mutations: five
  caught first time, one survived and strengthened the suite** — the converged-guard removal
  survived the wire-duplicate test because an exact duplicate never reaches the handler (the
  INBOX absorbs it by eventId); the guard's real subject is a DISTINCT event converging on an
  existing case, now driven end to end and asserting one case, one audit record, one
  announcement. Restart-mid-handling maps to the shell's proven crash/rollback properties, with
  the case-level instance being exactly that convergence test.
- Risk: Medium. Cx: S. DoD: `DOD-KERNEL`

**P2-TSK-008 — Documents: captured, encrypted, checksummed, access-audited** — `COMPLETE` (2026-09-09)
- Context: kyc
- Description: `V003` document tables (append-only at `DB-PRIVILEGE`; AES-256-GCM content under
  `FINAPP_DOC_KEY`; SHA-256 recorded), the `DocumentStore` port (ADR-0036's seam),
  `POST /v1/me/kyc/documents`, and the audited read path.
- Why: ADR-0036; the most sensitive bytes the platform holds before card data.
- Deps: P2-TSK-005.
- Out of scope: object storage (the port is the seam); document *verification* (P2-TSK-009).
- Distributed: concurrent uploads append; no update path exists to race on.
- Security: `INV-KYC-06` in full — plaintext content in no column (`information_schema` sweep,
  the `P1-TSK-007` idiom); tampered ciphertext refused; wrong key cannot read; every content
  read writes an audit record naming the actor; size/type bounds at the boundary; key guard
  confines the published default to loopback (`MfaKey`'s shape, third instance of the
  per-credential confinement).
- Invariants: `INV-KYC-06`, `INV-HIST-02` (checksum), `INV-AUD-02`.
- Tests: the sweep, tamper, wrong-key, unaudited-access mutation, oversized/foreign-type upload
  refused at the boundary, upload-then-case-update atomicity.
- Accept: content readable only through the audited path; every listed refusal proven.
- **Gate evidence (2026-09-09)**: every listed refusal proven — the information_schema column
  sweep, GCM tamper, wrong-key, the unaudited-access mutation, oversized/foreign-type at the
  boundary, one-transaction atomicity (plus the schema's in-module FK, which makes the orphan
  impossible even for a writer that skips the service). **Content-addressed convergence is the
  idempotency mechanism**: UNIQUE (case_id, checksum_sha256) + savepoint, so a retry, a
  double-tap and ten racing instances all land on one row and one 201 — no Idempotency-Key,
  content addressing is stronger. **The cipher is SecretCipher's mechanism, deliberately not its
  class** (module isolation; moving it would pull an expose() site out of the pinned identity
  set); one key per concern, FINAPP_DOC_KEY, the marked default REFERENCED from MfaKey so one
  literal exists, domain-separated locally, confined to loopback — the third per-credential
  guard, meeting the debt row's trigger one phase early (premise corrected). The read path has
  no HTTP caller yet BY PLAN (P2-TSK-012's reviewer surface); building it now is what makes
  "readable only through the audited path" true from the first day content exists.
  **Six mutations, all caught by the intended assertion.**
- Risk: High. Cx: M. DoD: `DOD-SEC`

**P2-TSK-009 — VerificationCheck, the provider port, and the simulated verifier** — `COMPLETE` (2026-09-09)
- Context: kyc / integration
- Description: The check entity (`REQUESTED → DISPATCHED → CLEAR | HIT | INDETERMINATE`), a
  `VerificationProvider` port (ADR-0008: our vocabulary in, our vocabulary out), and identity- +
  document-verification adapters over `SimulatedProvider`.
- Why: ADR-0038's evidence layer; the harness `P0-TSK-037` built has waited two phases for
  exactly this caller.
- Deps: P2-TSK-005, P2-TSK-008.
- Distributed: dispatch is recorded before the provider call (a crash mid-call leaves a
  DISPATCHED check to reconcile, never an unknown); duplicate dispatch prevented by conditional
  transition.
- Invariants: `INV-KYC-01` (evidence verbatim + separate normalised outcome), `INV-LIFE-03`
  (INDETERMINATE on timeout — the invariant arriving three phases early, recorded), `INV-HIST-02`.
- Security: provider payloads are `RESTRICTED-PII` evidence — append-only, classified; no
  provider vocabulary in domain or contract (boundary test extends the ADR-0008 rule).
- Tests: every `SimulatedProvider` outbound mode drives a defined check outcome; evidence bytes
  identical to bytes received; timeout → INDETERMINATE and the case does not decide.
- Accept: a clean simulated run takes a case to `READY_FOR_DECISION` with retained evidence.
- **Gate evidence (2026-09-09)**: the acceptance is driven against a real database and a real
  HTTP provider — two adapters answer clear, the case reads `READY_FOR_DECISION`, the evidence
  rows hold the bytes received, and `kyc.CheckCompleted` names the system actor (the fifth
  enumerated site). **The choreography is the deliverable**: dispatch committed BEFORE the
  provider call (a crash mid-call leaves a DISPATCHED fact, never an unknown), the call holds no
  connection, the outcome transaction writes completion + evidence + audit + counter together,
  and the assessment is a SEPARATE transaction after the commit — two instances assessing
  inside their own outcome transactions would each see the other still DISPATCHED and nobody
  would move the case. Ten instances racing one case: `requestCount sum == checkCount`, the
  call-per-check equality that is the load-bearing race assertion, and one `READY_FOR_DECISION`.
  All five `CheckType`s declared (plan §4 states them outright — a later constraint-replacement
  migration avoided); `INDETERMINATE` is terminal and its resolution a NEW check (ADR-0038);
  a HIT wins every tie in `ChecksAssessment`, not even a later CLEAR of the same type
  un-blocks (`INV-KYC-04`); the empty required-type set is refused. The residual
  redundant-question race is recorded (a wasted call, never a wrong answer); the
  stuck-DISPATCHED sweeper is recorded remainder. **Six mutations, all caught by the intended
  assertion** — after a first sweep whose six VOID results (the build never ran; cmd refused
  the bare gradlew.bat name) were caught by the harness's build-actually-ran assertion, the
  `P1-TSK-026` lesson holding.
- Risk: High. Cx: L. DoD: `DOD-KERNEL`

**P2-TSK-010 — Screening: sanctions, PEP, adverse media** — `COMPLETE` (2026-09-10)
- Context: kyc / integration
- Description: The three screening check types over the same port and harness; a HIT routes the
  case to `IN_REVIEW` and creates review tasks.
- Why: the gate's screening criteria; one check machine, not a second one (plan §5).
- Deps: P2-TSK-009.
- Distributed: as P2-TSK-009; concurrent HIT arrivals create review tasks idempotently
  (conditional insert keyed on check).
- Invariants: `INV-KYC-04` (no silent path from HIT to terminal), `INV-KYC-01`.
- Tests: HIT → IN_REVIEW with a task; no code path from HIT to a terminal state without a
  review resolution (asserted structurally and behaviourally); adverse-media INDETERMINATE
  handling.
- Accept: a hit case cannot terminate without a person.
- **Gate evidence (2026-09-10)**: the acceptance held both ways — behaviourally (a sanctions
  hit routes to `IN_REVIEW` with exactly one `OPEN` task on the hit check, and `IN_REVIEW` has
  no exit in this build) and structurally (the machine has no edge from `IN_REVIEW` to a
  terminal, and `ChecksAssessment` decides `HIT` first). **One `ScreeningAdapter`, three
  static factories**, so a type-to-path mismatch is unconstructible; the misbehaviour matrix
  is cited, not triplicated. **Routing is atomic**: tasks inserted and the conditional move
  made in ONE transaction, so a case is never `IN_REVIEW` with nothing to resolve — and task
  creation is unconditional on case status, so a late `HIT` joining an in-review case still
  gets its task. `UNIQUE (check_id)` is TOTAL: one task per check EVER (changed circumstances
  are a new check), and it is the ten-way-race arbiter (`ON CONFLICT DO NOTHING`, row count is
  the outcome). **The convergence rule was corrected, not smuggled**: `P2-TSK-009`'s
  converge-in-any-state made an `INDETERMINATE` unresolvable on the run path against
  ADR-0038's resolution-is-a-new-check; now an under-budget unknown is retried as a NEW check
  and the third (`INDETERMINATE_RETRY_BUDGET`) routes the type to a person — proven by the
  adverse-media walk: three questions asked, a fourth run asks nothing, the case in review
  with one task on the newest unknown. The asymmetry is deliberate and tested both ways: a
  `CLEAR` never un-blocks a `HIT`; a later `CLEAR` DOES satisfy an exhausted type. Resolution
  columns and the `UPDATE` grant are deferred to `P2-TSK-012` (grant-arrives-with-capability,
  proven by permission-denied now); its exit must be conditional on "no OPEN task" IN THE
  STATEMENT, recorded in the store's javadoc. `finapp.kyc.review.queue` arrives with the queue
  it measures (NaN-never-zero, fleet-wide, `max()` not `sum()`). **Six mutations, all caught
  by the intended assertion** — task creation removed, the move removed, `ON CONFLICT`
  removed, retry-never (the 009 regression), budget unbounded, exhausted-no-longer-blocks.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`

**P2-TSK-011 — Provider callbacks, deduplicated** — `COMPLETE` (2026-09-10)
- Context: kyc / api / integration
- Description: The inbound callback endpoint for asynchronous provider results, deduplicated
  through the platform inbox, updating checks by conditional transition.
- Why: `INV-KYC-03`; duplicate webhooks are the §Failure Engineering norm, and this is the
  phase's inbound door.
- Deps: P2-TSK-009.
- Distributed: duplicate and concurrent callbacks → one check transition (inbox +
  `WHERE status = 'DISPATCHED'`); a late callback for a terminal check is recorded as evidence
  and changes nothing (`INV-LIFE-04`).
- Security: simulated-signature verification at the boundary (the real scheme is Phase 5's
  webhook work; the seam and its limit stated); callback bodies are untrusted input — bounded,
  refused loudly, never a 500.
- Invariants: `INV-KYC-03`, `INV-IDEM-04`, `INV-LIFE-03`/`04`.
- Tests: duplicate, concurrent, late and malformed callbacks — one transition, no 500, evidence
  retained.
- Accept: the four callback scenarios each proven against a real database.
- **Gate evidence (2026-09-10)**: all four scenarios proven over real HTTP against a real
  database (`ProviderCallbackDatabaseTest`) — a triple delivery and a **ten-way concurrent
  race** each land one completion, one evidence row, one audit record, one inbox row, every
  response 204 or the inbox's honest 409 (`CONTENDED` is **not acknowledged**, its own
  contract, so the provider redelivers and either resolution of the race is correct); a late
  callback for a terminal check is evidence and never a transition (`INV-LIFE-04`,
  acknowledged 204 — a refusal would make a correct provider retry a fact we will never
  accept); eleven malformed-but-signed shapes are each the caller's 4xx, never a 500; and the
  unsigned or mis-signed stranger gets one uniform 401 with **nothing written**. **The
  signature is real authenticity, not a checksum**: the callback clears sanctions screenings,
  so HMAC-SHA256 over the raw bytes is verified before parsing and before any read, constant
  time asserted structurally, RFC 4231's own vectors in the suite (`CallbackSignatureTest`) —
  limits (one static key, no rotation, no replay window BECAUSE the inbox dedupes) stated in
  `SECURITY_ARCHITECTURE.md`. **Two dedupe layers designed in**: inbox for identical
  deliveries, conditional completion for distinct re-sends; evidence appended ALWAYS on this
  path (the late answer is a genuine provider statement, `INV-HIST-02`), deliberately unlike
  the run's evidence-on-win. The callback **heals the stranded-DISPATCHED remainder**
  `P2-TSK-009` recorded (dispatch-commits-before-call means the provider received that
  request), and a duplicate delivery re-assesses, healing a crash between commit and
  assessment. `CaseAssessment` + `CheckOutcomeTrail` extracted on the second-caller licence
  (`P1-TSK-033`); the enumerated `enterSystem()` site MOVED to `CheckOutcomeTrail.record` —
  one site whichever door. `OwnershipIsScopedTest` gained `SIGNED_CALLBACK` (its seventh
  class; every existing label would say something false); `CallbackKey` is the **fourth**
  per-credential confinement and the debt row's trigger, fired — generalisation recorded as
  its own due work. **The gate found `CallbackKey` had no test** (the `P1-TSK-017` finding,
  about to repeat) — `CallbackKeyTest` closes it — and found this task's own structural
  assertion vacuous as first written (a constant pool holds class and method names as
  separate entries). **Seven mutations, all caught by the intended assertion** — signature
  bypassed, inbox bypassed, completion unconditional, evidence dropped, assessment dropped,
  constant-time swapped for `Arrays.equals`, confinement removed.
- Risk: Medium. Cx: M. DoD: `DOD-KERNEL`

## P2-EPIC-05 / 01 — Decisions and review (M2.3)

**P2-TSK-012 — Review tasks and the reviewer endpoints** — `COMPLETE` (2026-09-10)
- Context: kyc / api
- Description: `ReviewTask`, `GET /v1/kyc/cases/{id}` and
  `POST /v1/kyc/cases/{id}/reviews/{taskId}/resolution` — behind
  `@RequiresPermission(KYC_REVIEW)`, reason required, everything audited.
- Why: `INV-KYC-04`'s human half; the phase's privileged surface.
- Deps: P2-TSK-004, P2-TSK-010.
- Distributed: concurrent resolutions of one task — one wins by conditional UPDATE, the loser
  gets 409, **one** audit record (the `P1-TSK-028` suspension shape).
- Security: negative authorization tests per endpoint (`INV-AUD-03`); case reads audited (the
  reviewer is the insider surface); reason bounds cite the audit record's (the
  `SuspensionRequest` constants pattern); identifiers `ADMINISTERED` in the ownership register.
- Invariants: `INV-KYC-04`, `INV-AUD-01`/`03`.
- Tests: no-role refusal audited; resolution race; reason bounds parity; resolution of another
  case's task refused (task-belongs-to-case predicate in the statement).
- Accept: a review task resolves exactly once, by an authorized person, with a reason, audibly.
- **Gate evidence (2026-09-10)**: the acceptance driven whole over real HTTP against a real
  database (`ReviewDatabaseTest`, 12 tests) — **exactly once**: a ten-way concurrent race
  produces one 204, nine 409s, one audit record, and the recorded reviewer is one of the
  racers; **by an authorized person**: a valid session holding no role is refused by both
  endpoints (`INV-AUD-03`); **with a reason**: a missing one is a 422 naming the field
  (`ResolutionRequest` cites `SuspensionRequest`'s constants across a package — they became
  `public` for it — and `V006`'s CHECK carries the same bound, three copies reconciled by
  test); **audibly**: `kyc.ReviewResolved` names the reviewer, rides the resolve's
  transaction, and the 409 loser writes nothing. Resolution is **state, not an aggregate
  method** — the conditional `UPDATE`'s three predicates (id, belongs-to-case, `OPEN`) are
  the concurrency protocol, and RESOLVED rows **freeze whole** by trigger
  (`review_task_resolution_is_final`), with the `UPDATE` grant column-narrowed to the four
  resolution columns (the `V004` precedent; identity columns stay permission-denied). The
  `IN_REVIEW → READY_FOR_DECISION` exit is conditional on no `OPEN` task **in the statement**
  (`moveStatusWhenNoOpenTasks`, `NOT EXISTS` — the predicate `P2-TSK-010` recorded), runs in
  a **separate transaction after the commit** (the `P2-TSK-009` argument), and the 409 path
  re-attempts it — a stranded exit (crash between resolve-commit and exit) is healed by the
  retried request, proven by fixture. `GET` is the audited read (`kyc.CaseRead`,
  `INV-KYC-06`'s trail-of-who-looked one level up; a guessed id writes no record) returning
  references only — no content endpoint, by plan. `kyc.ScreeningHitResolved` was **renamed
  `kyc.ReviewResolved` before first emission** (a task arises from any check's HIT or
  exhausted INDETERMINATE, so the old name could be false); `kyc.CaseRead` added.
  `ScreeningRunDatabaseTest`'s grant test **broke on schedule** (written by `P2-TSK-010` to
  break the day the grant arrived) and now pins the grant's boundary. One test of this task's
  own was wrong: a `doesNotContain` over the whole migration matched its **prose comment**
  (the `P1-TSK-021` lesson) — now statements-only with a vacuity control. **Seven mutations,
  all caught by the intended assertion** — resolve unconditional, belongs-to-case removed,
  exit's `NOT EXISTS` removed, resolution audit dropped, 409-path healing removed, freeze
  trigger removed, read audit dropped. 983 hermetic / 514 database / 14 kafka.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`

**P2-TSK-013 — The decision: immutable, attributable, policy-pinned** — `COMPLETE` (2026-09-10)
- Context: kyc
- Description: `KycDecision` (`V004`: append-only at `DB-PRIVILEGE`, `NOT NULL` actor / reason /
  policy version / evidence refs) plus the stated automatic policy for all-clear cases and
  `POST /v1/kyc/cases/{id}/decision` for reviewed ones.
- Why: ADR-0038; `INV-KYC-02` is the record every later phase gates on.
- Deps: P2-TSK-012.
- Distributed: decision races the case transition — conditional on `READY_FOR_DECISION`, row
  count is the outcome; two deciders → one decision, one audit record.
- Invariants: `INV-KYC-02`, `INV-HIST-04` (policy pinned), `INV-LIFE-04`.
- Tests: `UPDATE`/`DELETE` denied on every column (the `P0-TST-007` idiom); replay: evidence +
  pinned policy re-derives the decision; decision race; automatic path refuses a case with any
  non-CLEAR check.
- Accept: one immutable decision per case, reproducible from what it references.
- **Gate evidence (2026-09-10)**: the acceptance driven whole (`DecisionDatabaseTest`, 10
  tests over real HTTP and the real run) — **one immutable decision per case**: a ten-way
  reviewer race lands one 204 / nine 409s / one row / one `kyc.DecisionRecorded` record;
  a second decision is a 409 "already decided" with the first untouched; `UPDATE`/`DELETE`
  denied on **every column** of both tables, list from `information_schema` (the
  `P0-TST-007` idiom), with no `UPDATE` grant at all so no freeze trigger is needed (the
  `audit_record` model); **reproducible**: the replay test rebuilds the decision's inputs
  from the check rows the record *references* and the pinned policy code, and re-derives
  the stored outcome. **Attributable both ways** (`INV-KYC-02`'s two actor cases): a
  reviewer's decision names the person in `decided_by` and the audit record; the automatic
  one is `basis=AUTOMATIC`, `decided_by NULL`, actor `system` — the **sixth enumerated
  `enterSystem()` site**, justified in `SECURITY_ARCHITECTURE.md`, scoped around the audit
  write only so the reviewer path can never inherit it. **Policy-pinned**: the CASE's own
  `policy_version`, copied by the factory — never `CURRENT` re-read (`INV-HIST-04`) — and
  the evidence references are a join table with real FKs, so the accepted upload race
  (`P2-TSK-008`) is now *mechanically* harmless. **The automatic policy lives on the domain
  factory** (`KycDecision.automatic` refuses any non-CLEAR check loudly — `INV-LIFE-02`'s
  rejected-by-the-domain, proven at the domain since production reachability alone would
  make the refusal untestable) and rides `CaseAssessment`'s own transaction — deliberately
  unlike the assess-after-commit rule, because it reads only what that transaction already
  read, so the all-clear-and-undecided state has **no observable instant**; a reviewed
  case (BLOCKED-first assessment) never reaches it, so `IN_REVIEW → RFD` stays durable and
  the reviewer endpoint decides it. The conditional case move
  (`READY_FOR_DECISION → terminal`) is the arbiter; `UNIQUE (case_id)` **total** is defence
  in depth. Four assertions in two suites were superseded from `READY_FOR_DECISION` to
  `APPROVED` (the `P2-TSK-010` stopgap-superseded precedent). The 409 details are named
  for what is checked — "already decided" vs "not ready" (the `NOT_ACTIVE` lesson).
  **Seven mutations, all caught by the intended assertion** — arbiter ignored, policy
  predicate removed, evidence refs dropped, audit dropped, automatic hook dropped,
  `UPDATE` granted in V007 (caught against a from-scratch database), reviewer's audit
  written as the platform (the hermetic site enumeration is the second control).
  983→995 hermetic / 524 database / 14 kafka.
- Risk: High. Cx: M. DoD: `DOD-SEC`

**P2-TSK-014 — The projection: a decision moves `customer.status`** — `COMPLETE` (2026-09-10)
- Context: app / party / kyc
- Description: The `app` orchestration: recording a decision and transitioning
  `party.customer.status` (`PENDING → ACTIVE`/`REJECTED`) in **one transaction** (ADR-0035);
  the `party` grant for the status column arrives with this capability (`V00x`,
  column-narrow — the `V004` display-name precedent).
- Why: the onboarding gate every later phase queries; the moment `PENDING` finally moves.
- Deps: P2-TSK-013.
- Distributed: kill between decision and projection impossible by construction — asserted by the
  atomicity test (`P1-TSK-006`'s idiom: inject failure at the last write).
- Invariants: `INV-KYC-05` (one authority; projection reconciles), `INV-LIFE-02` on Customer.
- Tests: atomicity under injected failure and backend kill; reconciliation (decision ↔ status
  agree, swept); grant narrowness asserted.
- Accept: an approved case's customer is `ACTIVE`, atomically, and the reconciliation sweep
  proves the pair cannot drift.
- **Gate evidence (2026-09-10)**: the acceptance driven whole (`CustomerProjectionDatabaseTest`,
  8 tests) — **atomically, both doors**: a reviewer's approval and the automatic all-clear run
  each leave the customer `ACTIVE` in the decision's own transaction, with the projection as
  the recording's **last write** so the atomicity probes target it: an **injected failure**
  and a **backend killed mid-recording** (the `P1-TSK-012` deterministic-kill idiom) each
  leave *nothing* — no decision, no audit record, case still `READY_FOR_DECISION`, customer
  still `PENDING`. **The sweep proves the pair cannot drift, in both directions**: every
  decision's customer moved as the outcome says, and — the sharp direction — no customer
  under verification left `PENDING` without a decision authorizing it (scoped to customers
  *with* a KYC case, because schema-suite fixtures insert case-less `ACTIVE` rows to probe
  the one-live index). **`CustomerStatus` gained `REJECTED`** — the plan's and ADR-0035's own
  word: terminal, `PENDING`-only, deliberately not overloaded onto `CLOSED` ("refused" and
  "ended" are different facts, and the projection stays faithful to the decision). Rejection
  **frees the party's one-live slot**, proven behaviourally by the re-onboarding insert V005's
  widened predicate admits — and `PartyEnumMigrationTest`'s one-terminal assertion, written
  to break the day a second terminal arrived, **broke on schedule** and now derives the
  latest CHECK and the index predicate from the enum's own `sqlTerminalValueList()` (the
  `RoleAssignmentMigrationTest` applied-history lesson), with V002's originals pinned as
  history. **The grant premise was corrected rather than propagated**: V002 had already paid
  a table-level `UPDATE` before any writer existed, so V005 **narrows** it to
  `(status, status_changed_at)` — the V004 precedent, proven by the per-column denial sweep
  with its positive control. A lost projection conditional (a customer closed mid-KYC, the
  one reachable cause) **fails the whole transaction loudly**: recording the decision beside
  an unmoved projection would be the silent drift `INV-KYC-05` forbids, proven by the
  closed-customer test (500, nothing written, case still decidable). The mapping lives in
  `app` because `kyc` cannot see `party`; `JdbcPartyStore.moveCustomerStatus` asks the
  machine before any SQL (`INV-LIFE-02`) and joined the ownership register (`ADMINISTERED`).
  No new audit action, deliberately — the projection is derived bookkeeping of the audited
  decision, one join away. **Seven mutations, all caught by the intended assertion** —
  projection dropped (both doors), conditional from-status removed, lost-move failure
  swallowed, mapping inverted, index predicate kept narrow (caught against a from-scratch
  database), grant not narrowed, `status_changed_at` not written. 996 hermetic / 532
  database / 14 kafka. **M2.3 closes, 3 of 3.**
- Risk: High. Cx: M. DoD: `DOD-KERNEL`

**P2-TST-001 — The KYC gate criteria, demonstrated** — `COMPLETE` (2026-09-10)
- Context: kyc / test
- Description: The Phase 2 gate's first three bullets held by demonstration: exhaustive invalid
  transitions; verdict-is-evidence (no decision from a provider outcome without the platform's
  act); duplicate callback → one decision. Register rows for `INV-KYC-01`…`05` land here or with
  their owning tasks — whichever proved them first — and the register guard begins demanding
  them when Phase 2's status flips `COMPLETE`.
- Deps: P2-TSK-013.
- Accept: each demonstration recorded in `MUTATION_TESTING.md` §2 with its named test, per the
  convention (`P0-TSK-038`).
- Risk: Low. Cx: S. DoD: `DOD-TEST`
- **Outcome:** five rows in `MUTATION_TESTING.md` §2 (`INV-KYC-01`…`05`) and the item's own §4 row.
  **The audit found no demonstration missing**: every one of the five was performed by its owning
  task's mutation sweep (`P2-TSK-005`, `-009`, `-010`, `-011`, `-013`, `-014`), so the work was
  recording, not performing — each row names the mutation, the tests that caught it, and the
  observed result, all **Recorded** form honestly, since every mutation changed production code or
  a migration and cannot live in the suite. The gate's first bullet (exhaustive invalid
  transitions) is the §4 row: `KycCaseLifecycleTest#everyTransitionIsEnforced`, `P2-TSK-005`'s
  terminal-reopened mutation. `MutationDemonstrationTest`'s checks 3–7 hold the new rows to the
  code **immediately** — every named class and method verified to exist on a green run — and the
  guard's teeth were re-proven per §5: one method reference corrupted (backup-copy, not
  `git checkout`), `everyNamedMethodExists` failed naming exactly it, restored, green. One
  machinery hazard met on the way: a PowerShell round-trip re-encoded the register's non-ASCII
  characters as mojibake; caught by byte-comparison against the backup before anything was
  committed. `INV-KYC-06` and the `INV-CNS-*` rows are deliberately not here — 06's demonstrations
  exist (`P2-TSK-008`) and its row lands with the exit review; the consent rows' owning tasks are
  `TODO`.

## P2-EPIC-02 — KYB and beneficial ownership (M2.4)

**P2-TSK-015 — KybCase and the beneficial-ownership graph** — `COMPLETE` (2026-09-12)
- Context: kyc / party
- Description: `KybCase` for `ORGANISATION` customers; `BeneficialOwner` rows linking the case
  to natural-person Parties with stake/control attributes; the decision precondition: every
  owner's verification terminal before `READY_FOR_DECISION`.
- Why: GLOSSARY — *"not a KYC Case with a flag set"*; the graph is recursive and terminates in
  verified persons.
- Deps: P2-TSK-013.
- Distributed: owner additions race the readiness transition — the readiness check is a
  predicate in the transition statement, not a read-then-act.
- Invariants: `INV-KYC-02`/`05`; the owner set is part of the decision's evidence.
- Tests: a case with an unverified owner cannot reach `READY_FOR_DECISION`; owner added during
  review re-routes; graph termination (an owner who is an organisation needs their own case —
  bounded depth for Phase 2, recorded).
- Accept: an organisation decides only on a fully verified ownership graph.
- Risk: High. Cx: L. DoD: `DOD-KERNEL`
- **Outcome:** one case machine, two kinds — `kyc_case.case_kind` (`KYC`|`KYB`), fixed at open
  and **unwritable at DB-PRIVILEGE** (V008 narrows the case grant to
  `(status, status_changed_at)`), never a second table: what makes KYB "not a flag" is the
  graph. `kyc.beneficial_owner` is append-only (`SELECT, INSERT` only), kind-bound at
  `DB-CONSTRAINT` by **composite FKs** over a new `UNIQUE (id, case_kind)` — owner rows attach
  only to KYB cases, verifications are only KYC cases, so the graph's depth-1 bound is
  structural. An owner's verification is their **own KYC case, pinned at declaration**
  (`findLatestFor`, the `INV-HIST-04` shape); readiness demands every owner ANSWERED (terminal
  either way), not APPROVED, and **KYB never auto-decides** — `KycDecision.automatic` refuses
  the kind at the domain, because an automatic approval could clear a terminal-REJECTED owner
  by silence (`INV-KYC-04`'s shape). The distributed edge was sharper than the backlog's own
  sentence: under READ COMMITTED a blocked UPDATE re-runs its subqueries against the
  statement's ORIGINAL snapshot, so "predicate in the statement" alone still misses a
  just-committed owner — **write skew, closed by lock-then-look on both sides**
  (`SELECT … FOR UPDATE` on the case row, then the predicate in a fresh statement), proven by
  a deterministic two-connection test that observes the mover blocked in `pg_stat_activity`.
  Declarations are accepted only in OPEN/CHECKS_IN_PROGRESS/IN_REVIEW under the same lock, so
  once RFD the owner set is **frozen** and "the owner rows of the case" ARE the decision's
  evidence (`INV-KYC-02`) with no join table. An owner's terminal decision **re-routes**
  waiting parents post-commit through both doors — assess, and the reviewer's recording on
  RECORDED and ALREADY_DECIDED (the 409-heals path). **The sweep found the reviewer door
  exercised by nothing** — both re-route tests drove the assess door, so removing the
  controller's call survived — and the fix was a move, not a test for the controller: the
  hook now sits on `DecisionRecording.byReviewer` itself, because the recording already has
  a second caller and a consequence living only on the HTTP boundary is one a second caller
  silently loses; a new test drives an owner decided by a reviewer, and the re-aimed
  mutation is caught.
  Both Phase-2 bounds recorded: ORGANISATION owners refused (depth-1, DB-enforced), owners
  without a registered customer/case refused — declaration never auto-opens cases. Stake in
  basis points (1..10000, `INV-MON-01` hygiene), per-case sum ≤ 10000 checked under the lock.
  New audit action `kyc.OwnerDeclared`; `CaseKindResolver` port keeps `kyc` blind to `party`.
  **The full battery found one real interaction**: the kafka race test's crafted event named a
  customer with no rows, which the consumer's kind resolution now refuses loudly — stalling
  the partition by the block-don't-skip design and timing out every later test; the fixture
  now inserts the party/customer rows production guarantees exist. **Ten mutations, all caught
  by the intended assertion** — the ownership gate neutralised, the mover's lock dropped
  (caught by the race test's blocked-lock observation), the at-least-one-owner clause
  neutralised, the KYB-automatic refusal dropped, the accepting-status predicate dropped, a
  composite FK dropped (against a from-scratch database), the declaration audit dropped, the
  stake-sum bound removed, and each re-route door dropped — the reviewer door's survivor is
  the finding above, and its fix is what the sweep is for. 1006 hermetic tests, 546 database
  tests, 14 kafka tests.

**P2-TSK-016 — KYB endpoints** — `COMPLETE` (2026-09-12)
- Context: kyc / api
- Description: Owner declaration and KYB case views for the organisation's acting person;
  reviewer views extended.
- Deps: P2-TSK-015.
- Security: who may act for an organisation is **this task's design question** (the ADR-0031
  split trigger names organisations); Phase 2 scopes it to the registering identity, recorded
  as the deliberate minimum with delegated access out of scope (Phase 6+).
- Tests: over HTTP; ownership; a stranger cannot declare owners onto another's case.
- Accept: the M2.4 milestone criterion end to end.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`
- **Outcome:** the design question was answered by building its missing precondition: **no
  production path created `ORGANISATION` parties** — `PartyRegistration` hardcodes `PERSON` —
  so "the registering identity" had no subject (the `P1-TSK-023` shape: a missing precondition
  of the requirement, built in-task at the minimum). `POST /v1/me/organisations`: a logged-in
  person registers the organisation they act for, and party `V006` records their Party as its
  one registrant under a **total** `UNIQUE (registrant_party_id)` — the Phase 2 scope bound,
  the concurrency arbiter and the convergence key in one index — append-only
  (`SELECT, INSERT`), with delegation, multiple representatives and registrant replacement
  recorded as Phase 6+. Registration **converges**: a same-name repeat replays the original
  **201** (the `P2-TSK-008` convergence idiom — corrected at the gate from a 200, where the
  generated contract had published "200" only, the `P1-TSK-006` `ResponseEntity` trap met
  again), a different name is `409 api.Conflict` (`INV-IDEM-03`'s shape, the stored name never
  echoed), and no idempotency key, because convergence keyed on the registrant cannot be lost
  by the client. Created audits (`party.OrganisationRegistered`, actor the **proven person**,
  never `enterSystem()`) and publishes the same two events person registration publishes, so
  the case-opening consumer's kind resolution converges; **converged is silent**, asserted as
  one record however many times the request arrived; the KYB case opens **inside the
  registration transaction**. Ownership is the `/v1/me` absence shape, one hop further:
  Session → Identity.partyId → `organisationRegisteredBy` (the statement carries
  `registrant_party_id = ?`) → `findLatestFor` — a stranger's own chain is a 404, two acting
  persons' declarations provably land on their own organisations only, and the one
  request-supplied identifier, `ownerPartyId`, names the declaration's **subject**, never a
  resource (three new `SESSION_DERIVED` ownership-register entries). **Owner ineligibility is
  one refusal**: unknown party, organisation party, unregistered person and malformed
  identifier are byte-identical `422 kyc.OwnerNotEligible` — a split would make the endpoint
  an oracle over third parties' registrations — while the caller's own-graph refusals stay
  specific (`kyc.OwnerAlreadyDeclared` 409, `kyc.CaseNotAcceptingOwners` 409,
  `kyc.StakeExceedsWhole` 422), and the at-least-one-qualification invariant is told at the
  boundary as a 422 rather than thrown from the aggregate as a 500. **The view is shaped and
  the shaping is the control**: `IN_PROGRESS` covers checks AND review (tipping-off, plan §6),
  an owner's verification appears only as a `verificationPending` boolean — never status,
  outcome or case identifier — and the reviewer's case file gains the graph **whole**
  (verification case id and real status), because the owner rows are the decision's evidence
  (`INV-KYC-02`). The M2.4 acceptance is driven end to end over HTTP: register → declare →
  the gate refuses readiness → the owner's verification answers → readiness → the reviewer
  decides → the acting person reads `APPROVED`; ten concurrent registrations produce ten 201s,
  one registrant row, one audit record. **Nine mutations: eight caught first time; the
  survivor was the boundary-check mutation**, which found the no-500 sweep's
  no-qualification shape naming an *unknown* party — refused by eligibility before the
  aggregate was ever constructed — re-aimed at an eligible owner and caught. 1006
  hermetic tests, 557 database tests, 14 kafka tests. **M2.4 closes: 2 of 2.**

## P2-EPIC-06 — Consent (M2.5)

**P2-TSK-017 — Consent texts and the append-only record** — `COMPLETE` (2026-09-12)
- Context: consent
- Description: `V002`: `consent_text` (versioned, immutable) and `consent_record` (append-only
  at `DB-PRIVILEGE`, `NOT NULL` text-version reference); the derivation query; the
  `ConsentPurpose` enumeration.
- Why: ADR-0037 made real.
- Deps: P2-TSK-003.
- Distributed: concurrent grant+withdraw append two facts; the derivation orders by a
  server-assigned ordering column, not by two instances' clocks (`P0-TST-009`'s lesson).
- Invariants: `INV-CNS-02`/`04`.
- Tests: privilege sweep on every column; derivation under interleaved records; version-pinning
  refused null.
- Accept: the history is the store, proven immutable.
- Risk: Medium. Cx: S. DoD: `DOD-KERNEL`
- **Outcome:** ADR-0037 made real, with the immutability landed one rank stronger than the
  backlog asked in one place: `consent_record` is `SELECT, INSERT` and nothing else (the
  `audit_record` model — the privilege IS the immutability, no freeze trigger), and
  **`consent_text` is unwritable by the application entirely** (`SELECT` alone) — a consent
  text is a reviewed platform artefact, and a forward-only migration is exactly the reviewed,
  immutable channel it arrives through: v1 for both purposes is seeded by `V002`, a wording
  change is a NEW version in a later migration, and `requires_reconsent` is a recorded
  property of the version (`INV-CNS-04`), never a guess. The pin is **unforgeable twice
  over**: `text_version NOT NULL` on BOTH kinds (a withdrawal pins the version current when
  the person withdrew — the invariant's reference is unconditional) plus the **composite FK**
  `(purpose, text_version) → consent_text (purpose, version)` (the V008 lesson: a record
  cannot pin another purpose's text), and the NOT NULL is load-bearing BESIDE the FK because
  SQL's composite-FK semantics let a NULL slip past it — proven by mutation. **The order of
  the history is the server's**: `seq BIGINT GENERATED ALWAYS AS IDENTITY` — client values
  refused, so no instance's clock or counter decides which racing fact is later — and the
  derivation orders by it, never `recorded_at`, proven deterministically: a grant inserted
  first (lower seq) with a LATER timestamp, committing LAST, still loses to the withdrawal
  holding the higher seq, which kills the clock ordering and the commit-order intuition in
  one held-transaction test. The derivation (`hasCurrentBasis`) is one statement, one
  snapshot: latest fact is a `GRANT` AND no newer text version of the purpose requires
  re-consent — with **absence-equals-withdrawal asserted as an equality between the causes**
  (`INV-CNS-01`'s shape), and purpose-scoping proven. Ten instances append concurrently with
  no locks and no losing branch; every appender succeeds and every reader agrees which fact
  is last. **The privilege sweep found its own subtlety**: `GENERATED ALWAYS` refuses
  `seq = seq` before the privilege check runs, so the sweep probes `seq = DEFAULT` — the one
  update the identity mechanism admits, and the one that would RE-ORDER history if an
  `UPDATE (seq)` grant ever appeared. `consent_text.body` is the platform's **first genuinely
  PUBLIC column** (the words shown to every customer), which makes the classification
  scheme's every-level-used check honest rather than vacuous. Deliberately absent: any bean
  (no consumer until `P2-TSK-018` — the P1-TSK-007 unconsumed-wiring licence), endpoints,
  audit emission (both actions stay `NOT_YET_EMITTED` naming 018), events, meters. **Nine
  mutations, all caught first time** — UPDATE granted on the record table, INSERT granted on
  texts, the composite FK dropped, both reads re-ordered by `recorded_at` (separately), the
  re-consent clause dropped, the GRANT predicate dropped (a withdrawal deriving a basis),
  `text_version` made nullable, and `seq` made `BY DEFAULT` (caught hermetically). 1016
  hermetic tests, 564 database tests, 14 kafka tests. **M2.5 opens: 1 of 4.**

**P2-TSK-018 — Consent endpoints** — `COMPLETE` (2026-09-13)
- Context: consent / api
- Description: `POST /v1/me/consents`, `DELETE /v1/me/consents/{purpose}`,
  `GET /v1/me/consents` — session-derived, no identifiers.
- Deps: P2-TSK-017.
- Distributed: repeated grants/withdrawals are new facts and converge; no conflict surface.
- Security: withdrawal must not be refusable by anything but authentication — a person can
  always withdraw; audited (`consent.ConsentWithdrawn` et al.).
- Invariants: `INV-CNS-01`/`02`/`04`, `INV-IDN-04`.
- Tests: over HTTP; grant against a stale text version refused when the current version demands
  re-consent; absence vs withdrawal indistinguishable to a caller of the query.
- Accept: the lifecycle over HTTP with the audit trail naming the person.
- Risk: Low. Cx: S. DoD: `DOD-SEC`
- **Outcome:** The store's first consumer, and the beans arrive with it (the P1-TSK-007
  unconsumed-wiring licence expiring on schedule). The `/v1/me` shape with **one path
  variable that is not an identifier**: `{purpose}` is a closed enum naming a category of
  processing shared by everyone — it cannot name a resource, a person, or anything of
  anybody else's — and mechanically the consent store takes a raw `UUID`, not an `EntityId`
  subtype, so `OwnershipIsScopedTest`'s detector demands no entry (verified against the
  detector, not assumed). **The grant carries the version the person was SHOWN**, because a
  server-side "grant against current" would record consent to words the platform merely
  hopes the person saw; the refusal rule is the derivation's own clause applied before the
  fact exists (`ConsentStore.assessGrant`, one statement, one snapshot): refused exactly
  when a later version records `requires_reconsent` — recording it would write a "consent"
  the derivation immediately judges basis-less — and a stale version whose successors never
  demanded re-consent stays grantable, the backlog's conditional honoured rather than
  over-tightened. Two new codes catalogued (`consent.ReconsentRequired` 409 actionable,
  `consent.UnknownTextVersion` 422 client defect; the composite FK backs the second as
  defence in depth), and **deliberately no withdrawal code**: there is no withdrawal failure
  for one to name. Withdrawal pins `currentTextFor().version()` server-side, requires no
  prior grant (honest history, `INV-CNS-02`), and a repeated one is a new fact — proven as
  the security property it is, from both plausible refusal causes. `GET` answers per purpose
  from `hasCurrentBasis` with the **current text riding along** (the words are what a person
  consents to, and `consent_text.body` is the platform's first PUBLIC column doing exactly
  its job); absence vs withdrawal proven **byte-identical over HTTP** as an equality between
  the causes. Both audit actions get their first emitters and leave `NOT_YET_EMITTED`
  (three Phase-15 outbox actions remain): actor the **person**, never the platform; target a
  consent record proven to exist; summary naming purpose and pinned version, never the
  words. Record and trail commit in one transaction (the ProfileService idiom). No events,
  deliberately: the gate reads authoritative state per decision (`INV-CNS-03`), so a
  consent event would be transport with no consumer. No `Idempotency-Key`: retries append
  new facts that converge (ADR-0037), asserted. The contract gained two paths and two
  schemas, 178 added lines and zero removed; `ConsentGrantRequest` joined the
  credential-sink pinned set with its reason. **Eight mutations, all caught** — grant audit
  dropped, withdrawal audit dropped, stale-version refusal dropped, withdrawal made
  conditional on a prior basis (204 silently writing nothing), the audit written as the
  platform, the GET derivation inverted, the unknown-version refusal dropped (the FK
  answering with our 500), a withdrawal recorded as a GRANT. 1016 hermetic tests, 573
  database tests, 14 kafka tests. **M2.5: 2 of 4.**

**P2-TSK-019 — The consent gate, and the first capability behind it** — `COMPLETE` (2026-09-13)
- Context: consent / kyc / app
- Description: The gate (`ConsentGate.require(party, purpose)`) reading authoritative state per
  decision, and its first consumer: opening a KYC case requires a current `KYC_PROCESSING`
  grant.
- Why: `INV-CNS-01`/`03`; the gate bullet — *withdrawal demonstrably blocks the dependent
  capability* — needs a dependent capability to block.
- Deps: P2-TSK-017, P2-TSK-005.
- Distributed: withdrawal on one instance blocks the capability on another, immediately —
  the multi-instance test is the acceptance (`P0-TST-009` convention); **no process-local
  consent cache**, asserted the `NoProcessLocalSessionStateTest` way.
- Invariants: `INV-CNS-01`, `INV-CNS-03`.
- Tests: absent, withdrawn and stale-version bases each refuse; the cross-instance immediacy
  race; the cache detector.
- Accept: `P2-TST-002`'s demonstration is possible and performed.
- Risk: Medium. Cx: M. DoD: `DOD-SEC`
- **Outcome:** `ConsentGate` in `consent` — `permits` and `require`, both one authoritative
  read per decision over `hasCurrentBasis`, no state of its own, the refusal causes
  indistinguishable on purpose (`ConsentNotGrantedException` names the purpose and nothing
  more; no error code yet, deliberately — codes are declared by the surface that shapes them,
  `P2-TSK-006`'s). **The design's crux was that the gated capability had two doors**: the
  eager registration consumer (`P2-TSK-007`) opens a case for a person who CANNOT yet hold a
  grant — a grant needs a session, a session needs the registration the event announces — and
  a gate with an ungated second door is not a gate. So the consumer asks through a `kyc` port
  (`CaseOpeningConsent`, the `CaseKindResolver` shape — `kyc` cannot see `consent`) that
  `app` implements over `PartyStore.partyOfCustomer` (new read, `ADMINISTERED` with
  `kindOfCustomer`'s provenance verbatim) and the gate, and **skips when refused**:
  acknowledged, logged with correlation, nothing written — no case, no audit record, no
  announcement, the store not even asked. A refusal is the platform's own correct decision,
  never a poison record. **`P2-TSK-007`'s headline changed and the change is recorded**:
  registration alone now opens nothing — that refusal is the milestone's acceptance working
  at the eager door — and a party WITH a basis opens exactly one, eagerly; the case otherwise
  opens when the consented person acts (`P2-TSK-006`, now unblocked). The kafka suite was
  restructured on consented fixtures keeping every prior property (dedupe, silent
  convergence, the race), and its first test drives the whole deployed chain twice: consumed
  with nothing written, then granted and opened. **The KYB registration's in-transaction open
  is deliberately OUTSIDE the gate**, recorded in `KybService`: the declared capability is a
  PERSON's KYC case under `KYC_PROCESSING`, whose text covers "my identity data" — an
  organisation cannot consent, and a lawful-basis regime for organisational verification is a
  later phase's decision, not one to smuggle in under a text that does not cover it.
  **M2.5's demonstration is performed**: withdrawal committed on one connection refuses the
  gate on another's very next decision (`P0-TST-009` convention) — `P2-TST-002` records the
  register row and the cached-read mutation against exactly that test.
  `NoProcessLocalConsentStateTest` closes the cache shape ADR-0024's patterns cannot see
  (`ConsentRecord`/`ConsentText` retention; the Boolean-cache limit stated, and covered
  behaviourally — the memoizing-gate mutation was caught by the race, not the detector).
  **Five mutations, all caught** — the consumer opening without asking (hermetically: the
  store stays untouched), the gate permitting everything, `require` swallowing, the adapter
  asking about the customer where the party belongs (caught through the real broker), and
  the gate memoizing per (party, purpose). 1019 hermetic tests, 576 database tests, 14 kafka
  tests. **M2.5: 3 of 4.**

**P2-TST-002 — Consent withdrawal blocks the capability, across instances** — `COMPLETE` (2026-09-13)
- Context: consent / test
- Description: The gate bullet demonstrated: withdraw on one simulated instance, the gated
  capability refused on another, with the register row recorded.
- Deps: P2-TSK-019.
- Accept: the demonstration fails when the gate's authoritative read is replaced by a cached
  value — performed, not asserted.
- Risk: Low. Cx: S. DoD: `DOD-TEST`
- **Outcome:** The four `INV-CNS-01`…`04` rows landed in `MUTATION_TESTING.md` §2, the item's
  own §4 row beside them, and **the acceptance was performed rather than recorded from
  memory** — which is what found the two defects below. The register guard's teeth were
  re-proven per §5: one method reference in the new `INV-CNS-03` row corrupted,
  `everyNamedMethodExists` failed naming exactly it, restored from a backup **copy** and
  verified byte-identical (never `git checkout --`, and never a PowerShell round-trip — the
  `P2-TST-001` mojibake lesson).
  **Performing the acceptance changed the test twice.** The demonstration shared **one**
  `ConsentGate` across both simulated instances, so it caught the cached-read mutation by
  accident (A memoises before B withdraws) — and `SimulatedInstance`'s own rule is never to
  share the thing whose sharing hides the defect, which for a process-local cache is the gate.
  Giving each instance its own gate then made the test **fail**, and the reason was the second
  finding: it had been resting on autocommit, so B's withdrawal had never been a committed
  fact and the commit boundary was asserted nowhere. It now **straddles the commit**, which is
  the invariant's own wording (*"from the transaction that records a withdrawal"*): while
  uncommitted A still permits — an instance refusing there would be reading dirty — and on A's
  very next decision after the commit it refuses. Deterministic, no sleep, no polling.
  **And reading the item's own words found the demonstration aimed one level too low.** It
  proved the **gate's answer** flipped across instances and left the **capability** to a
  composition argument over two green tests — the `P1-TSK-027` shape, where both halves worked
  and nothing joined them. The bullet names the capability, so
  `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` drives the real consumer with the real
  case store, party store, gate and audit/outbox writers, wired as `KycBeans` wires them, and
  asserts that after a withdrawal committed on another instance it opens **nothing** — no
  case, no audit record, no announcement — with a positive control, so a capability that could
  never open anything cannot pass it.
  **Three demonstrations performed, and two of them bound the claim rather than confirming
  it.** D1, the acceptance: the gate's read replaced by a cached value — caught by the race
  **and** by the capability test. D2: the same cache hidden one layer down in
  `JdbcConsentStore` — caught by the race, and **survived the field detector**, because a
  `Map<String, Boolean>` names no consent type; so the behavioural test is the load-bearing
  control there and the detector is the second, blind in a different direction. D3: a cache
  that remembers only **refusals** — **survived** the race, correctly, since withdrawal still
  takes effect and `INV-CNS-03` says nothing about a stale refusal — and was caught at the
  other door by the kafka test, whose second half opens a case for a party refused moments
  earlier. Neither test covers a cache alone, and §3 records that rather than leaving a reader
  of either to assume it does. 1019 hermetic tests, 577 database tests, 14 kafka tests.
  **M2.5 CLOSES: 4 of 4.**

## P2-EPIC-07 — Observability and the gate (M2.6)

**P2-TSK-020 — The six planned meters, eagerly registered** — `COMPLETE` (2026-09-13)
- Context: kyc / consent / platform
- Description: `PHASE_2_PLAN.md` §10's table, registered at construction (`P1-TSK-029`'s rule),
  plus a dashboard row with queries that resolve (`DashboardQueriesResolveTest` extends).
- Deps: the flows they measure (P2-TSK-013, P2-TSK-018).
- Accept: a freshly started instance publishes every §10 series; `PlannedMetersExistTest` will
  hold them from the day the phase completes.
- Risk: Low. Cx: S. DoD: `DOD-OBS`
- Gate evidence (2026-09-13): **The survey found three of the six already built — and two of
  those behind a condition.** `finapp.kyc.check` and `finapp.kyc.provider.latency` were
  registered only by `@ConditionalOnProperty("finapp.kyc.provider.url")` beans, so the exact
  context the guards boot — nothing configured — published neither: the `P1-TSK-029` defect
  wearing a condition. Closed by one definition (`KycMeters`) built through by the conditional
  owners AND registered unconditionally by `KycMetrics` at startup (registration is
  idempotent). **`finapp.kyc.case` counts at the store seam**: a `MeteredKycCaseStore`
  decorator over the one `kycCaseStore` bean, because every door — the endpoint, the consumer,
  KYB registration, both decision paths — goes through it; `opened` on `created`,
  `approved`/`rejected` on a WON terminal move, tag values derived from the machine.
  **`finapp.consent.grant`/`.withdrawal` by purpose**, eager per purpose in `ConsentService`,
  incremented after the commit and only for the recorded act; `ALLOWED_TAG_KEYS` widened with
  `purpose` — the designed edit-forces-decision path, bounded by the closed `ConsentPurpose`
  enum, and unlike the refused `stage` there is no naming that carries the plan's own table
  without it. The acceptance is performed ahead of the flip: a pinned test in
  `PlannedMetersExistTest` holds Phase 2's §10 table against the plain context now, so the
  flip is a non-event for the derived guard. Dashboard row added, every query resolving
  against a live scrape; the per-purpose eagerness is held by the series nothing in any suite
  ever increments (`finapp.consent.withdrawal{purpose=screening}`). **Seven mutations, all
  caught by the intended assertion** — converged open counted, decorator un-wired,
  unconditional registration removed, non-terminal move counted, refused grant counted,
  dashboard series renamed, per-purpose registration incomplete. 1025 hermetic tests, 584
  database tests, 14 kafka tests.

**P2-DOC-001 — Phase 2 review record** — `COMPLETE` (2026-09-13)
- Context: process
- Description: The `PHASE_GATES.md` §4 review: eight areas, twelve universal criteria, the six
  Phase 2-specific ones, with evidence — and the ADR-0035…0038 acceptance decision.
- Deps: everything above.
- Accept: every criterion assessed with evidence and a verdict; numbers counted, never quoted
  (`P1-DOC-001`'s own finding); the phase flips `COMPLETE` only here, which since the
  transition's guard redesign is itself build-guarded.
- Risk: Low. Cx: S. DoD: `DOD-DOC`
- Gate evidence (2026-09-13): **The gate passes; Phase 2 is `COMPLETE`** ([`reviews/PHASE_2_REVIEW.md`](reviews/PHASE_2_REVIEW.md)). Eight areas (7 `PASS`, 1 `NOT APPLICABLE` — area 2 walks a posting end to end and the phase creates none), twelve universal criteria (**12 `PASS`**), the financial supplement recorded **not applicable** rather than skipped, six phase-specific criteria (**6 `PASS`**), and the ten-instance question answered **`PASS`**. **Two criteria were closed by the review rather than waived**: criterion 3's `INV-KYC-06` register row, deferred *in writing* by `P2-TST-001` to this review and **performed rather than inferred**, and criterion 8's two drifts in `PHASE_2_PLAN.md` §11 — a promised *"exactly once per fact"* delivery the architecture deliberately refuses, and an M2.6 that omitted the observability the same document specifies. **The flip is the guarded act**, so the order was land the row → flip → re-run the battery. ADR-0035…0038 → `Accepted`. Every number counted, and the review's own drafted mutation total was wrong (95 → **135**) until it was. 1025 hermetic / 584 database / 14 kafka. **Phase 2 closes at 23 of 23.**

---

---

# Phase 3 — Accounts and Financial Ledger

Status: `COMPLETE` (2026-09-17, [`reviews/PHASE_3_REVIEW.md`](reviews/PHASE_3_REVIEW.md)) —
**25 of 25** *(this header read `READY` throughout the phase's execution — the
stale-second-copy finding of `P3-DOC-001`'s area 7)*. Entry gate passed 2026-09-13
([`reviews/PHASE_2_TO_3_TRANSITION.md`](reviews/PHASE_2_TO_3_TRANSITION.md)).
Planned in [`PHASE_3_PLAN.md`](PHASE_3_PLAN.md); decisions in ADR-0039…0042 (`Accepted`).

**The strictest gate in the programme**, and the first phase the financial supplement (F1–F8)
binds. Every task below carries `DOD-FIN` where it can affect money, balances or accounting —
which is most of them.

**Field conventions.** Each task states its bounded context, scope and out-of-scope,
dependencies, the domain/persistence/API/event changes it makes, the `INV-*` it must protect,
its distributed-system concerns under ≥10 instances, security, audit, reconciliation, tests,
acceptance criteria and DoD profile. A task that states "n/a" for a field has considered it.

## P3-EPIC-01 — The chart of accounts (M3.1)

**P3-TSK-001 — The `ledger` module, its schema, and the privilege floor** — `COMPLETE` (2026-09-13)
- **Objective**: a guarded `ledger` module exists with a schema owned by the migrator, so every
  later `DB-PRIVILEGE` claim in the phase is *available* to be made.
- **Context**: Ledger (7). **Scope**: Gradle module on the documented direction; `V001` creating
  the schema with `REVOKE ALL FROM PUBLIC` and `USAGE` only to `finapp_app`; isolation tests both
  directions; `LedgerAuditAction` enum with its catalogue rows; lockfile regeneration.
- **Out of scope**: any table, any aggregate, any bean.
- **Deps**: none (Phase 2 complete).
- **Domain**: `LedgerAuditAction` only — declared under the deliberately-few licence: an action
  whose *design* is fixed (`ledger.JournalEntryPosted`, `ledger.AdjustmentPosted`), never one a
  later task will shape.
- **Persistence**: schema only, no tables.
- **API / Events**: none.
- **Invariants**: none directly — this task makes `INV-LED-03`, `INV-HIST-01` and `INV-LED-04`
  *enforceable* at `DB-PRIVILEGE` by putting the objects under the migrator. That is the whole
  point of doing it first (`P2-TSK-003`'s recorded reasoning).
- **Distributed**: n/a — no state.
- **Security**: default-deny grants. **Audit**: actions catalogued, `NOT_YET_EMITTED` with owning
  tasks. **Reconciliation**: n/a.
- **Tests**: module isolation both directions; migration applies to an empty database, validates
  and re-applies idempotently; the five existing sweeps proven to cover the new module by probe
  (a planted `double`, a cross-module dependency, an unclassified column).
- **Accept**: `./gradlew build databaseTest` green with the module present; a planted `double` in
  `ledger` fails the floating-point rules; a cross-module dependency fails the isolation test.
- **Gate evidence (2026-09-13)**: the full battery ran with the module present — 1027 hermetic
  and 584 database tests — and its one failure was this task's own catalogue row:
  `ledger.AdjustmentPosted` written `Yes` where the convention is `**Yes**`, caught by
  `AuditableActionRegistryTest`. That failure is also the coverage evidence: the app-level sweep
  reached `LedgerAuditAction` through the derived module set with no rule edited. Both
  `LedgerModuleIsolationTest` tests passed; `databaseTest` applied `V001` and
  `ColumnClassificationTest` passed over the new schema. Migrate → validate → re-migrate on a
  throwaway PostgreSQL: one history row, owner `finapp_migrator`, ACL `finapp_app=U`, no
  `PUBLIC` entry, zero tables. **Not performed, at the owner's direction**: the re-run after the
  one-line `**Yes**` fix, and the four planned mutation probes (a planted `double`,
  `ledger → party`, `party → ledger`, an unclassified column) — the owner stopped that run and
  closed the gate without it; the next full battery confirms the fix.
  `ledger/gradle.lockfile` byte-identical to `consent`'s; verification metadata unchanged;
  `build-logic` Kotlin RC3→GA lockfile drift reverted a third time.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-BUILD`, `DOD-ARCH`

**P3-TSK-002 — `LedgerAccount`: typed, single-currency, and unchangeable once posted to** — `COMPLETE` (2026-09-13)
- **Objective**: the chart's row exists and its classification cannot drift (`INV-LED-06`).
- **Context**: Ledger. **Scope**: `LedgerAccount` aggregate; `AccountType`, `NormalBalance`,
  `AccountPurpose`, `OwnerKind` enums generating their own `CHECK` constraints; `V002` creating
  `ledger.ledger_account`; the **trigger** freezing `account_type`, `normal_balance` and
  `currency` once a line references the account.
- **Out of scope**: journal entries; any balance; any API.
- **Deps**: P3-TSK-001.
- **Domain**: normal balance **derived from type and stored**, because `INV-LED-06` constrains
  something only if it is stored.
- **Persistence**: `ledger_account`; `UNIQUE (owner_ref, purpose, currency)` where owned;
  `UPDATE` column-narrowed to status fields (the `V004` narrowing) — never type, normal balance
  or currency.
- **API / Events**: none.
- **Invariants**: `INV-LED-06`, `INV-MON-02` (currency `NOT NULL`).
- **Distributed**: account creation races on the unique index; converge or refuse, decided by
  whether two callers asking for "the customer's GBP wallet account" mean the same thing (they
  do — converge, the `openOrConverge` idiom).
- **Security**: no endpoint yet. **Audit**: none — creating a chart row is not yet an act anybody
  performs. **Reconciliation**: n/a.
- **Tests**: every enum value round-trips; the migration/enum reconciliation (`P2-TSK-004`'s
  latest-constraint derivation); the freeze trigger proven by attempting each forbidden `UPDATE`
  **after** a line exists; ten concurrent creates produce one row.
- **Accept**: reclassifying a posted-to account is refused by the database; a `CHECK` rejects an
  unknown type; the enum and the constraint cannot drift.
- **Gate evidence (2026-09-13)**: six mutations, all caught by the intended assertion — the
  type→normal-balance derivation inverted, the aggregate's transition check removed, the
  coherence `CHECK` dropped from `V002`, the freeze trigger's posted-probe neutralised
  (against a from-scratch database), the one-per-owner unique index dropped (the ten-way race
  producing ten rows), and the `UPDATE` grant widened to the whole table (caught by the
  per-column denial sweep). The freeze is proven in **two layers with a positive control
  between them**: the migrator corrects the classification of an *unposted* account, then a
  stand-in `ledger.journal_line` row makes the same coherent update refuse — so the test says
  "frozen once posted to" rather than "frozen"; `P3-TSK-005`'s real table supersedes the
  stand-in and its sweep must re-prove the trigger. The identity fields (purpose, currency,
  owner, creation instant) are frozen **unconditionally**, stricter than the task's letter and
  recorded in the migration: an unposted account with the wrong currency is corrected by
  opening another. `owner_kind` is **derived from purpose and stored** — the same argument
  the backlog makes for `normal_balance`, so both derivations generate their own `CHECK` and
  the migration test reconciles all seven generated fragments. `OwnershipIsScopedTest`
  demanded no entry, verified rather than assumed: neither store method takes an `EntityId`;
  `P3-TSK-014`'s status move will be the first. 1035 hermetic / 588 database tests.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-DOMAIN`

**P3-TSK-003 — The operational chart, seeded by migration** — `COMPLETE` (2026-09-13)
- **Objective**: the platform's own accounts exist before anything can post, because a double
  entry needs both sides.
- **Context**: Ledger. **Scope**: `V003` seeding operational accounts per supported currency —
  `SETTLEMENT_CLEARING`, `FEE_REVENUE`, `FX_POSITION`, `ROUNDING_RESIDUAL`, `SUSPENSE_UNMATCHED`;
  a `ChartOfAccounts` lookup resolving purpose+currency → account.
- **Out of scope**: posting to any of them.
- **Deps**: P3-TSK-002.
- **Rationale**: seeded by **migration**, not by application startup — a reviewed, immutable
  channel (ADR-0011), the `consent_text` precedent. An operational account created at run time is
  one whose existence depends on which instance started first.
- **Invariants**: `INV-BAL-03` (the residual account must exist before any allocation posts),
  `INV-REC-05` (suspense exists before Phase 8 needs it).
- **Distributed**: none — migrations are serialised by Flyway's own lock.
- **Tests**: every purpose resolves for every supported currency; the seed is idempotent on
  re-apply; `FX_POSITION` and `SUSPENSE_UNMATCHED` exist and **nothing posts to them in Phase 3**,
  asserted so the seam stays a seam.
- **Accept**: `ChartOfAccounts.resolve(purpose, currency)` answers for every combination; a
  missing seed fails the build.
- **Gate evidence (2026-09-13)**: the task's one open design question — *which currencies?* —
  answered explicitly rather than implied by a migration: **`SupportedCurrencies` (EUR, GBP,
  USD) is the single definition in `ledger`**, jurisdiction-neutral, three so the
  per-currency structure (the Phase 9 seam) is exercised rather than assumed; growing it is
  a new seed migration in the same change, refused by `OperationalChartMigrationTest` until
  both halves agree. Seed ids are **hand-minted UUIDv7 literals** — `gen_random_uuid()` is
  v4 and `LedgerAccountId.of` would refuse it at rehydrate — and deterministic across
  environments, which for operational accounts is a feature; timestamps are literals, never
  `now()`. The seeded types are the seed's recorded decision, pinned by test as its
  contract. **Eight mutations: seven caught by the intended assertion** — a seed row removed
  (caught twice: the hermetic reconciliation and `resolve` against a from-scratch database),
  a stray row added, a currency added with no seed, a *coherent-but-wrong* type flip (the
  case only the pinned contract can catch: ASSET/DEBIT passes every schema CHECK), a v4 id,
  the owned-purpose guard removed — **and one survived correctly**: `findOperational`
  losing `owner_ref IS NULL`, because the purpose→owner-kind→owner-ref CHECK chain makes an
  owned row with an operational purpose unstorable; the predicate is recorded defence in
  depth for a future mixed-kind purpose. The seam test is **self-arming**: zero lines
  reference `FX_POSITION`/`SUSPENSE_UNMATCHED`, structurally true while `journal_line` does
  not exist and live from the day `P3-TSK-005` creates it. 1040 hermetic / 592 database
  tests.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-FIN`

## P3-EPIC-02 — Postings that cannot be wrong (M3.2)

**P3-TSK-004 — `JournalEntry` and `JournalLine`: the balance rule at the domain** — `COMPLETE` (2026-09-13)
- **Objective**: an unbalanced entry cannot be **constructed** (`INV-LED-01`, `INV-LED-02`).
- **Context**: Ledger. **Scope**: the two aggregates; `Direction` enum; balancing validated per
  currency at construction; ≥2 lines; posting date and value date as **required inputs**, never
  clock reads.
- **Out of scope**: persistence; the command; reversal.
- **Deps**: P3-TSK-002.
- **Domain**: `Money` unchanged (integer minor units, explicit currency, stored scale). Amounts
  are **positive**; `Direction` carries the sign, so "unbalanced" is two sums that must be equal
  rather than a subtraction that happens to be non-zero.
- **Invariants**: `INV-LED-01`, `INV-LED-02`, `INV-MON-01`…`06`, `DOMAIN_MODEL.md` §Time.
- **Distributed**: n/a — immutable values.
- **Tests**: balanced and unbalanced across JPY(0)/USD(2)/BHD(3); a multi-currency entry must
  balance **in each currency**; single-line refused; a posting date derived from the clock is
  impossible because the constructor demands one.
- **Accept**: every unbalanced shape throws; property test over generated line sets.
- **Gate evidence (2026-09-13)**: the one factory validates `INV-LED-02` then `INV-LED-01`,
  so an unbalanced entry has no code path on which to exist; sums fold through
  `Money.plus`, making cross-currency addition impossible and **mixed scales within one
  currency a refusal rather than a normalisation** (the implicit rescale `INV-MON-03`
  forbids — with the cross-sides case surfacing as unbalanced under `Money`'s own
  scale-including equality, tested and documented). The property sweep (2000 trials,
  JPY/USD/BHD, seeds repaired to balance then perturbed by **one minor unit**) asserts its
  own coverage and re-verifies balance with an independent `BigDecimal` implementation, so
  the sweep does not certify `Money` with `Money`. No amount reaches any rendering or
  exception message (`INV-AUD-02`: `JournalLine`'s record `toString` is overridden, the
  unbalanced refusal names the currency and the fact). **Six mutations, all caught by the
  intended assertion** — the balance check removed, the per-currency grouping collapsed,
  the line-count refusal removed (the empty entry is the load-bearing half: it balances
  vacuously, which is why `INV-LED-02` is checked separately), the positivity refusal
  removed, the amount leaked into a rendering, and the fold's scale-aware zero identity
  removed. 1049 hermetic / 592 database tests.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-DOMAIN`

**P3-TSK-005 — Postings persisted: balanced by constraint, immutable by privilege** — `COMPLETE` (2026-09-13)
- **Objective**: the database refuses what the domain refuses, and refuses to let anything edit
  it afterwards (`INV-LED-01`, `INV-LED-03`, `INV-HIST-01`).
- **Context**: Ledger. **Scope**: `V004` creating `journal_entry` and `journal_line`; the
  entry-level balance enforcement; `SELECT, INSERT` grants and **no `UPDATE`, no `DELETE`**;
  `NOT NULL` attribution columns (`INV-LED-05`).
- **Out of scope**: the command; idempotency; events.
- **Deps**: P3-TSK-004.
- **The one real design problem**: a `CHECK` cannot see sibling rows, so entry-level balance must
  be a **constraint trigger** or a deferred constraint. The task must choose, state why, and
  **prove it against a direct `INSERT` that never passes through the domain** — because that is
  the writer the constraint exists for.
- **Invariants**: `INV-LED-01`, `INV-LED-02`, `INV-LED-03`, `INV-LED-05`, `INV-HIST-01`,
  `INV-MON-05`.
- **Distributed**: inserts only; no lost update to have (ADR-0039).
- **Security**: grants are the enforcement. **Audit**: n/a (the command audits).
- **Tests**: **the `P0-TST-007` column sweep** — `UPDATE` denied on *every* column of both tables,
  the list derived from `information_schema`, plus `DELETE` and `TRUNCATE`; a direct unbalanced
  `INSERT` refused; money round-trips at `BIGINT` extremes for every scale.
- **Accept**: dropping the balance enforcement fails a test; granting `UPDATE` fails a test; a
  raw SQL unbalanced entry is impossible.
- **Gate evidence (2026-09-13)**: **the named design problem chosen and argued**: two
  `CONSTRAINT TRIGGER`s, `DEFERRABLE INITIALLY DEFERRED`, firing at COMMIT — a `CHECK`
  cannot see sibling rows, cannot be deferred, and SQL `ASSERTION` is unimplemented; sum
  columns fail multi-currency and need `UPDATE` on an insert-only table. **Two triggers**,
  because a zero-line entry balances vacuously and the line trigger never fires for it —
  `P3-TSK-004`'s finding at the schema, proven by direct SQL. The balance trigger also
  refuses mixed scales per currency, probed with the one shape only that clause catches:
  equal raw sums at different scales. Immutability is **two layers**: no `UPDATE`/`DELETE`
  grant (the `P0-TST-007` sweep over every column of both tables), and an unconditional
  append-only trigger binding the migrator too — stronger than asked, the freeze-trigger
  precedent. `INV-MON-05` proven at `BIGINT` extremes for scales 0/2/3 **and at an
  off-default stored scale by raw SQL**, so a rehydrate that re-derived scale is caught
  (the `P0-TSK-038` finding one layer up). Ten concurrent postings to one account all
  succeed (ADR-0039: inserts contend on nothing; the account FK's `FOR KEY SHARE` is
  share-compatible). **Three findings on the way**: the driver ROUNDS nanoseconds to the
  column's microseconds rather than truncating (caught by an assertion expecting
  truncation, off by exactly one microsecond); `OwnershipIsScopedTest` refused `findById`
  until classified — the day the `OutboxRelay` entry predicted — landing `NOT_OWNED` with
  the surfaces that must reclassify named; and `PostingAttribution` carries the actor
  **id** rather than a typed `Actor`, because the column holds an id and a typed copy
  would be a guess on read-back. The two self-armed guards went live: the classification
  freeze re-proven against the real `journal_line` (stand-in dropped), the seam count now
  querying a real table. **Eight mutations, all caught by the intended assertion**. 1057
  hermetic / 600 database tests.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`

**P3-TSK-006 — The posting command: idempotent, atomic, audited, announced** — `COMPLETE` (2026-09-13)
- **Objective**: one command, one financial effect, whatever the caller does (`INV-IDEM-01`).
- **Context**: Ledger. **Scope**: `PostingService` — entry, lines, audit record and outbox row in
  **one transaction**; the Phase 0 idempotency kernel at the financial boundary;
  `ledger.JournalEntryPosted` published via the outbox.
- **Out of scope**: the projection (P3-TSK-009); any HTTP surface.
- **Deps**: P3-TSK-005.
- **Events**: `ledger.JournalEntryPosted` — identifiers and enumerated names only, **never
  amounts** (`INV-AUD-02`).
- **Invariants**: `INV-IDEM-01`, `INV-IDEM-03`, `INV-EVT-01`, `INV-LED-04`, `INV-AUD-01`.
- **Distributed**: ten concurrent identical keys → one effect, counted in the database rather
  than inferred from a return value; a different request on a known key → refused; a stale
  `IN_PROGRESS` claim reclaimed by the **server's** clock.
- **Security**: `LEDGER_POST` is internal; no public caller. **Audit**: `ledger.JournalEntryPosted`
  with actor, correlation, outcome.
- **Tests**: ten-way race, one effect; crash between commit and publication → republished, same
  `eventId`; injected failure at the last write → nothing at all; a rolled-back posting leaves no
  outbox row.
- **Gate evidence (2026-09-13)**: `PostingService` joins the **caller's** transaction —
  Phase 4's transfer-and-posting-commit-together depends on joining, and "the ledger owns
  the posting transaction" means the write set, not the boundary. Validate → claim →
  effect: an unbalanced request never consumes its key; a replay returns the original
  entry id from the stored response. `ledger.JournalEntryPosted` **leaves
  `NOT_YET_EMITTED`** — audit record and outbox row in the posting's own transaction, the
  event carrying enumerated names only, never an amount. The retry, the ten-way race
  (one executed, nine replayed, one row **counted in the table**), the `INV-IDEM-03`
  conflict, the injected last-write failure (nothing at all — no entry, and **no claim**,
  so a retry re-attempts rather than replaying a failure that never committed) and the
  rolled-back posting (no outbox row) all proven against the real executor and writers;
  republish-on-crash with the same `eventId` is the relay's proven property of the table
  the row lands in (`P0-TST-005`), cited in the assertion rather than re-proven. **The
  command's own validation question answered one rank stronger**: `V005` binds a line's
  currency to its account's by composite FK (the kyc `V008` precedent), refusing a USD
  line on a JPY account for every writer — the domain deliberately cannot see it, since
  a line holds an identifier. **Account status is a recorded remainder with its owner**:
  every reachable account is `ACTIVE` (no store writes status), and posting-to-closed
  refused *under the account lock* is `P3-TSK-014`'s own race to close — a lock-free
  status read here would be the check that passes every test and loses the race. Actor
  from `SecurityContext.require()` (never defaulted), correlation with the flow-root
  cause resolved the `OrganisationRegistration` way. **Six mutations, all caught by the
  intended assertion** — audit dropped, outbox dropped, fingerprint made constant, the
  key silently made per-call (the `P2-TSK-002` dedupe-key-per-delivery shape), the actor
  defaulted, the composite FK dropped. 1058 hermetic / 608 database tests.
- **Accept**: the F3 supplement criterion met; duplicate delivery proven to produce no second
  effect.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-EVENT`

**P3-TSK-007 — `LEDGER_POST` and `LEDGER_ADJUST` permissions, and the ledger role** — `COMPLETE` (2026-09-14)
- **Objective**: posting authority is a privileged capability rather than an ambient one.
- **Context**: Identity (authorization) + Ledger. **Scope**: two permissions, one role
  (`LEDGER_OPERATOR`), the migration widening the role constraint (`P2-TSK-004`'s
  latest-constraint derivation).
- **Out of scope**: the adjustment endpoint (P3-TSK-017).
- **Deps**: P3-TSK-001.
- **Invariants**: `INV-AUD-03` (a negative test per privileged action).
- **Security**: the grants are **disjoint** from `ADMINISTRATOR` and `KYC_REVIEWER`, asserted as
  its own property; the self-elevation limit is restated rather than re-argued.
- **Tests**: cross-population — a ledger operator refused by administrative and KYC surfaces and
  vice versa; the role granting everything fails the build.
- **Accept**: negative authorization test per new permission.
- **Gate evidence (2026-09-14)**: **two permissions, one role, and the asymmetry is the
  design**: the permission vocabulary is precise (`P3-TSK-017`'s adjustment endpoint
  checks `LEDGER_ADJUST` specifically, `INV-REV-04`'s reason regime attaching there)
  because splitting a permission later means re-auditing every check site, while the
  role bundles both because a role exists when a distinct trust decision does
  (`P2-TSK-004`'s rule) and Phase 3 has one ledger-operating population.
  **`PostingService` deliberately carries neither**: ADR-0031 puts permission at the
  boundary, and the in-process command runs under the flow's actor — a Phase 4 transfer
  runs as the customer, who holds no ledger permission; the permissions gate surfaces
  where a *person* commands a posting, so both ship with probes and no production
  caller (the `KYC_REVIEW` precedent, stated rather than smuggled). `V014` is the
  `V013` ceremony — constraint replaced from `RoleName.sqlValueList()`, covered by the
  latest-constraint reconciliation with **no test edit**, `V010`'s pinned history
  untouched. Disjointness generalised to **pairwise over `values()`** in code and to
  **three populations over HTTP** (each admitted by its own probes, refused by both
  others' — `INV-AUD-03` from the attacker's direction, every side); a ledger operator
  granted through the **real** roles endpoint is refused by both administrative
  endpoints. The self-elevation limit restated, not re-argued. The contract gained one
  request-enum value, `BREAKING` by the classifier's blanket rule, reviewed and
  accepted (the `P2-TSK-004` precedent). **Six mutations, all caught by the intended
  assertion** — the acceptance one counted twice, hermetically and over HTTP. 1059
  hermetic / 609 database tests. **M3.2 closes: 4 of 4.**
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-SEC`

## P3-EPIC-03 — Balances that are explainable (M3.3)

**P3-TSK-008 — Balance derived from postings** — `COMPLETE` (2026-09-16)
- **Objective**: the authoritative number, computed from the rows (`INV-BAL-01`, `INV-BAL-02`).
- **Context**: Ledger. **Scope**: `BalanceDerivation` aggregating lines by direction and normal
  balance, per account per currency, with an "as of" (posting date or entry sequence).
- **Out of scope**: the projection; any endpoint.
- **Deps**: P3-TSK-005.
- **Invariants**: `INV-BAL-01`, `INV-BAL-02`, `INV-MON-04` (no cross-currency arithmetic).
- **Distributed**: a read; sees committed postings only, which is correct.
- **Tests**: replay from zero reproduces the balance for every account type and both normal
  balances; an account with no postings is zero **in its own currency**, never a bare `0`.
- **Accept**: derivation is the definition the projection is checked against.
- **Gate evidence (2026-09-16)**: `BalanceDerivation.settle` is the one statement of
  the sign convention — where `Direction` finally meets `NormalBalance`, as the
  former's javadoc promised since `P3-TSK-004` — and **the sums fold through `Money`,
  never through a SQL `SUM`**: an aggregate computed in SQL is a second implementation
  of monetary arithmetic outside the kernel, which would silently rescale
  (`INV-MON-03`) and silently widen past `long` (`INV-MON-06`); folding through the
  kernel makes those refusals structural. Replay verified against **independent
  `BigDecimal` arithmetic over raw SQL rows** for every reachable type (`EQUITY` has
  no purpose and so no account; the hermetic sweep covers all five via `values()`).
  A mixed-scale history — reachable only by the raw-SQL writer, planted per
  `P3-TSK-005` — **refuses loudly**, naming account, currency and fact and never a
  sum (`INV-AUD-02`); a one-sided persisted-scale history settles via the scale-aware
  zero identity (`JournalEntry.sum`, package-private for its second caller). Negative
  is a legal state, not an error. The `throughEntry` cut compares **in SQL only**
  (`java.util.UUID.compareTo` disagrees with PostgreSQL's byte order), and its
  mint-vs-commit caveat is stated in `AsOf`'s javadoc where `P3-TSK-009` will read
  it. An uncommitted posting is invisible to another connection's derivation —
  committed rows only, one statement, one snapshot. No migration (`V004` indexed this
  read by name), no bean (nothing consumes it until `P3-TSK-009`/`-010`), no
  endpoint. **Six mutations, all caught by the intended assertion.** 1062
  hermetic / 616 database tests.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`

**P3-TSK-009 — The transactional projection** — `COMPLETE` (2026-09-16)
- **Objective**: fast balance reads that can never be behind (ADR-0041).
- **Context**: Ledger. **Scope**: a migration creating `ledger.account_balance` with
  `posted_minor`, `holds_minor`, `last_entry_seq`; updated **in the posting transaction**; owned
  and written only by `ledger`. (The row said `V005`, which `P3-TSK-006`'s currency binding had
  already taken — the next free number is `V006`; corrected at `P3-TSK-008`'s gate rather than
  propagated.)
- **Out of scope**: holds populating `holds_minor` (P3-TSK-015).
- **Deps**: P3-TSK-006, P3-TSK-008.
- **Invariants**: `INV-BAL-01`, `INV-BAL-05` — and the rule that **no decision reads it**, which
  is a design constraint this task must make structurally visible rather than documented.
- **Distributed**: each posting contends on its accounts' projection rows — the accepted cost,
  recorded in ADR-0041 with the operational-account mitigation.
- **Tests**: projection equals derivation after every posting; ten concurrent postings to one
  account leave the projection equal to the derivation; a rolled-back posting leaves the
  projection unchanged.
- **Gate evidence (2026-09-16)**: `V006` creates the table with **`scale` added to ADR-0041's
  sketch** (a persisted amount without its scale is uninterpretable — `INV-MON-05`, and an
  accumulating row is exactly where silent cross-scale addition would hide), the currency
  bound to the account's by composite FK (the `V005` mechanism), and grants of
  `SELECT, INSERT` plus `UPDATE` **column-narrowed** to the accumulating columns — identity
  unwritable, no `DELETE`, proven per column with the positive control. The apply joins
  `PostingService`'s effect as its **last** write, so the contended row lock (ADR-0041's
  accepted cost) is held for the shortest window; a replay never re-enters the effect, a
  rollback takes the projection change with it, and an injected refusal commits **nothing at
  all**. **The delta folds through the kernel** — `JournalEntry.sum` per side, signed by
  `BalanceDerivation.settle`, the convention still stated once — and the accumulation is one
  guarded SQL addition, admissible where a history-wide `SUM` was not because cross-scale is
  refused by the scale-match condition and `bigint` overflow **raises** (`INV-MON-06`). Not
  read-modify-write: the increment re-reads under the row's own lock, first postings converge
  through `ON CONFLICT`, and multi-account entries lock rows in one fixed order (no deadlock).
  **"No decision reads it" is structural**: no read exists anywhere — the port declares one
  `void` method, pinned hermetically by `BalanceProjectionTest`, with the arriving readers
  (`P3-TSK-010`, `P3-TSK-018`) named as the decisions that must come and say what kind of
  number they return. **The watermark answers `AsOf`'s mint-vs-commit caveat by not being an
  entry id**: `last_entry_seq` counts entries applied to the row, serialised by the row's
  lock, and "current?" is seq = `COUNT(DISTINCT entry_id)` — in-flight entries tolerated by
  the count, never by a time window (plan §14.6). **A scale-divergent posting is refused
  wholly**, a deliberate strengthening: previously postable and balance-poisoning, now
  refused at posting time with an amount-free message (`INV-AUD-02`); the raw-SQL writer
  still bypasses the projection, which is exactly the drift `P3-TSK-010` exists to detect.
  Proven over rows: equality with the derivation after sequential, both-directions-in-one-
  entry (seq +1, not +2), replayed, ten-way-concurrent (fresh account, first-insert race
  included, 5500 counted in the table) and rolled-back postings. **Six mutations, all caught
  by the intended assertion** — the apply dropped, the sign inverted, the accumulation made
  an overwrite, the watermark frozen, the scale guard dropped, the refusal swallowed.
  1064 hermetic / 621 database tests.
- **Accept**: projection and derivation agree under sustained concurrent posting.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`

**P3-TSK-010 — The verification job and the drift metric** — `COMPLETE` (2026-09-16)
- **Objective**: the comparison, not anybody's confidence, is the evidence (`INV-BAL-02`).
- **Context**: Ledger. **Scope**: a job recomputing every balance from postings and comparing to
  the projection; `finapp.ledger.projection.drift` gauge; alerting threshold of **zero**.
- **Deps**: P3-TSK-009.
- **Distributed**: no leader — idempotent per run, or lease-protected; a new
  `DISTRIBUTED_EXECUTION.md` §3 exemption is a **decision**, not a ride on the relay's.
- **Tests**: an injected drift is detected and reported; the job is safe to run while postings
  continue; the gauge reports **NaN when unreadable, never zero** (`P1-TSK-029`'s rule).
- **Gate evidence (2026-09-16)**: `ProjectionVerification` in `ledger` — every account with
  lines or a projection row recomputed through the `P3-TSK-008` derivation and compared, with
  the verdict per account `CLEAN | DRIFTING | IN_FLIGHT`. **"Safe while postings continue" is
  the seq-bracketed read**: the row's watermark read before and after the derivation, and a
  mid-comparison commit — every domain entry commits atomically with its seq bump — yields
  `IN_FLIGHT`, tolerated by the watermark and never by a time window (plan §14.6), settled by
  the next run; **no lock is taken anywhere**, because the verifier must never contend with
  the write path it audits. Proven **deterministically**: a derivation decorator commits a
  concurrent posting mid-comparison (the `P1-TSK-012` idiom — no sleeps, no timing luck), the
  verdict is `IN_FLIGHT` never false drift, and the next run is `CLEAN`. **Drift is**: a row
  absent while lines exist (the raw-SQL bypass `P3-TSK-009` recorded, now detected and proven
  with planted entries), `seq ≠ COUNT(DISTINCT entry_id)`, settled numbers differing under
  scale-including equality, or an underivable history with a standing claim — unverifiable is
  not clean. Injected corruptions through the app role's own narrow `UPDATE` grant (the
  closest stand-in for the writer nobody wrote) each detected, with positive controls.
  **Never repaired** — the §14.12 rule: a self-correcting ledger destroys the evidence.
  `finapp.ledger.projection.drift` registered **eagerly** in `LedgerMetrics` (the
  `IdentityMetrics` shape verbatim: connection-source seam, cached reading at a 30s floor —
  six times the siblings', because this read walks every posted account — **NaN when
  unreadable, never zero**, which bites hardest here since zero means *verified clean*); a
  non-zero reading WARN-logs the drifting account ids, bounded and amount-free
  (`INV-AUD-02`). **The scheduling question answered by needing no schedule**: the scrape
  drives the sweep through the cache floor — the `OutboxBacklog` shape — so nothing schedules
  ambiently, no leader, no lease, and no new `DISTRIBUTED_EXECUTION.md` §3 entry to argue;
  every instance verifies independently and publishes the same fleet-wide figure (`max()`,
  never `sum()`). **The first reader of the projection arrived and said what it returns**:
  verdicts and counts, never `Money` — `BalanceProjectionTest` now pins the verifier's public
  surface alongside the write port, so `INV-BAL-05` survives the read's arrival.
  `LedgerMetrics`/`$Cached` joined the floating-point exemption set as the same
  Micrometer-gauge case a fourth time; `OwnershipIsScopedTest` demanded the three SQL-bearing
  helpers and got honest `NOT_OWNED` entries. The dashboard row is M3.8's with the rest of
  the §15 meters; the zero threshold is recorded in the meter description. **Six mutations,
  all caught by the intended assertion** — the settled comparison dropped, the seq-vs-count
  check dropped, the absent row made clean, the in-flight bracket dropped (false drift under
  concurrency), the underivable refusal swallowed, the gauge's NaN made zero.
  1069 hermetic / 624 database tests.
- **Accept**: drift is detectable, alertable and zero.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-OBS`

**P3-TST-001 — `INV-BAL-02` under sustained concurrent posting** — `COMPLETE` (2026-09-16)
- **Objective**: the F2 supplement criterion, demonstrated rather than asserted.
- **Scope**: ten instances posting continuously to one account while the verification job runs;
  replay-from-zero equals projection throughout; a projection rebuild **while postings continue**.
- **Deps**: P3-TSK-010.
- **Gate evidence (2026-09-16)**: `SustainedConcurrentPostingDatabaseTest`, and the composition
  is the deliverable — the pieces were proven alone (`P3-TSK-009`'s ten-way race,
  `P3-TSK-010`'s deterministic in-flight interleave), and the `P1-TSK-027` lesson is that two
  green halves compose only when something drives them together. **Overlap by construction,
  never timing luck** (`P0-TST-004`'s rule): the storm is ended by the VERIFIER, which sweeps
  until both floors are met — ≥25 verdicts completed and ≥200 entries committed (raised from a
  first draft whose 10/30 floors were satisfied in ~300ms on a warm container: a gust, not a
  storm) — so "the job ran while postings continued" is a property of the loop's exit condition.
  Every mid-storm verdict `CLEAN` or `IN_FLIGHT`, **never `DRIFTING`** — that is
  "replay-from-zero equals the projection *throughout*", stated through the delivered
  verification machinery; afterwards the verdict is `CLEAN` and the row equals the posters' own
  committed tally, **tracked outside the kernel** (the independent no-lost-increment check).
  **The rebuild half records the operator procedure that is safe under live posting**:
  lock-then-look as the migrator — `SELECT … FOR UPDATE` on the projection row, then recompute
  and overwrite in a FRESH statement — because a single `UPDATE` with recomputing subqueries
  re-evaluates them against its ORIGINAL snapshot when blocked (`P2-TSK-015`'s write-skew
  finding) and would lose exactly the posting it blocked on. The row is corrupted before each
  of three mid-storm rebuilds, so each provably rewrites rather than no-ops, with committed
  entries required between rounds so each runs against live traffic; the storm ends `CLEAN`
  with the tally exact. **The named mutation performed and recorded**: the projection updated
  outside the posting transaction (upserts rerouted onto a private autocommit connection) —
  **five tests fail across both suites, the two intended among them**: the rolled-back posting
  deterministically, and the sustained storm's drift-free-throughout sweep. The `INV-BAL-02`
  row landed in `MUTATION_TESTING.md` §2 with the item's §4 row, and the register guard's teeth
  re-proven per §5: one method reference corrupted (backup copy, never `git checkout --`),
  `every method the register names exists` failed naming it, restore byte-identical, green.
  **With this the M3.3 acceptance holds and the milestone closes** — a balance recomputed from
  zero equals the projection, under sustained concurrent posting. 1069 hermetic /
  626 database tests.
- **Accept**: the register row lands with the mutation that breaks it (the projection updated
  outside the posting transaction) proven caught.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-TEST`

## P3-EPIC-04 — The customer account product (M3.4)

**P3-TSK-011 — The `accounts` module and schema** — `COMPLETE` (2026-09-17)
- Objective/shape as P3-TSK-001, for `accounts`. **Deps**: P3-TSK-001.
- **Boundary**: `accounts` may see `ledger`; `ledger` may **not** see `accounts` (ADR-0042),
  asserted in both directions.
- **Gate evidence (2026-09-17)**: the full battery green with the module present — 1071 hermetic,
  626 database, 14 kafka tests. The P3-TSK-001 shape delivered whole: guarded module, `V001`
  privilege floor (owner `finapp_migrator`, ACL exactly `finapp_app=U`, no `PUBLIC`, zero
  tables, **no `ALTER DEFAULT PRIVILEGES`** — the first table here is exactly one whose `UPDATE`
  must be column-narrowed, plan §8), migrate → validate → re-migrate idempotent on a throwaway
  PostgreSQL. **The ADR-0042 asymmetry is structural, not only tested**: `accounts → ledger` is
  declared with the module (its first consumer is P3-TSK-012, one task away), so
  `ledger → accounts` is a Gradle dependency cycle — demonstrated: the planted reverse edge
  fails configuration with "Circular dependency" — while `AccountsModuleIsolationTest` pins the
  positive half (ledger/platform/sharedkernel required) and all five sibling isolation tests
  gained `accounts` in their forbidden lists (the P2-TSK-003 one-directional-decay lesson,
  applied at design time). **One deliberate deviation from P3-TSK-001's letter**: no
  `AccountsAuditAction` enum — the plan names `accounts.AccountOpened`/`AccountClosed` as
  *events* (§10) and no audit action outright, so under the deliberately-few licence the
  lifecycle actions arrive with the aggregate whose design fixes their meaning (the
  `kyc.CaseOpened`/P2-TSK-005 precedent), recorded in `package-info.java`. **Five probes, all
  caught by the intended guard, restores byte-identical**: a planted `double` fails
  `NoFloatingPointMoneyRulesTest` naming `accounts.Planted.amount` (the derived sweep reached
  the module with no rule edited); `accounts → party` fails the new isolation test;
  `party → accounts` fails party's (failure naming the transitively-arriving `ledger` first —
  list order; the plant is caught unconditionally, recorded); the reverse-edge cycle above; an
  unclassified column migrated into `accounts` fails `ColumnClassificationTest` naming
  `probe_unclassified.customer_email` against a from-scratch database. **Two findings**:
  `DATA_MIGRATIONS.md` §"Adding a schema-owning module" step 5 instructed editing a CI list
  `P1-TSK-003` removed — the procedure was stale at its last step, found by following it,
  corrected with provenance; and `CURRENT_STATE.md`'s M3.4 block counted 3 tasks where the
  epic holds 4 and the milestone acceptance requires `P3-TSK-014` — corrected. Housekeeping:
  `accounts/gradle.lockfile` identical to `ledger`'s but for its header; verification metadata
  unchanged; the `build-logic` Kotlin RC3→GA lockfile drift met and reverted a **fourth** time.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-BUILD`, `DOD-ARCH`

**P3-TSK-012 — `CustomerAccount`: the product, gated on verification** — `COMPLETE` (2026-09-17)
- **Objective**: a verified customer may hold an account; an unverified one may not.
- **Context**: Accounts. **Scope**: the aggregate and its machine
  (`PENDING → ACTIVE → {SUSPENDED ⇄ ACTIVE} → CLOSED`); `V002` creating
  `accounts.customer_account` *(written `V001` before `P3-TSK-011` landed `V001` as the
  schema init — the ledger numbering shape)*; opening requires
  `party.customer.status = ACTIVE` — **Phase 2's projection as the gate**, its first
  consumer.
- **Out of scope**: the API; closing (P3-TSK-014).
- **Deps**: P3-TSK-011, P3-TSK-002.
- **Invariants**: `INV-LIFE-01`, `INV-LIFE-02`, `INV-LIFE-04`, `INV-KYC-05` (consuming the
  projection, never recomputing it).
- **Distributed**: one account per customer per product type, DB-enforced; ten concurrent opens
  produce one.
- **Security**: ownership is the `/v1/me` shape when the API lands.
- **Tests**: every invalid transition refused **by the aggregate**, derived from the machine; a
  `PENDING` or `REJECTED` customer cannot open an account; opening creates the ledger account(s)
  in the **same transaction**.
- **Accept**: a KYC-approved customer opens an account; a rejected one is refused.
- **Gate evidence (2026-09-17)**: full battery green — 1079 hermetic, 633 database, 14 kafka
  tests. The acceptance end to end against a live PostgreSQL: an `ACTIVE` customer's open
  commits the agreement (`ACTIVE` from birth — the gate is opening's only precondition, so
  `PENDING` has no producer, the `STRONG`-assurance precedent), the `CUSTOMER_WALLET` ledger
  account (**`LIABILITY`/`CREDIT`, asserted** — a wallet typed `ASSET` would state that
  customer money is the platform's own), the audit record naming the person and the one
  announcement, in **one transaction** — proven by the rolled-back open leaving none of the
  four. A `PENDING` and a `REJECTED` customer are one uniform refusal writing nothing
  (`REJECTED` via the freed one-live slot, so the causes are structurally indistinguishable).
  **The gate consumes the projection per decision** (`INV-KYC-05`): the port resolves the
  customer from `findLiveCustomerFor` filtered to `ACTIVE` inside the opening's own unit of
  work — the customer identifier is never a caller's — and a customer closed on another
  connection is refused on this one's very next open. **Ten instances, ten connections, own
  scopes**: one agreement, one ledger account, one record, one event, nine converged —
  counted in the tables; the partial unique index arbitrates and the savepoint keeps the
  losers' transactions alive. The one open-vs-customer-close race is **accepted and stated**
  (the `P2-TSK-008` class) with the cross-module lock rejected as boundary-breaking coupling.
  **The wallet-aggregate tension in the plan resolved on the record**: product type on
  `CustomerAccount` (`WALLET`, the phase's one product over the one customer-owned purpose),
  ADR-0042's premature-boundary argument applied one level down, provenance note in plan §4.
  `AccountsAuditAction` arrives **with the aggregate** per `P3-TSK-011`'s recorded decision —
  `ACCOUNT_OPENED` only, emitted by the creating call; `ACCOUNT_CLOSED` stays `P3-TSK-014`'s.
  Grants proven per column with a positive control (`UPDATE` on exactly
  `status, status_changed_at`; identity columns and `DELETE` refused at the privilege).
  The container clock drift met once more (`P1-TSK-031` — a fixture `now()` against a
  JVM-clock `opened_at`; the constraint was right, the fixture corrected to the
  `LedgerAccountDatabaseTest` idiom). **Eight mutation runs, all caught by the intended
  assertion, restores byte-identical**: the gate call dropped, the eligibility filter widened
  past `ACTIVE`, the ledger creation dropped, the converged path acting again, the one-live
  index dropped (caught **twice** — the hermetic reconciliation and the ten-way race), the
  transition check removed, the `UPDATE` grant made table-wide.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-DOMAIN`, `DOD-FIN`

**P3-TSK-013 — `POST /v1/me/accounts`, `GET /v1/me/accounts`, `GET …/balance`** — `COMPLETE` (2026-09-17)
- **Context**: Accounts + app. **Scope**: the three endpoints, ownership by absence
  (`SESSION_DERIVED`); the balance response states **which number it is** (settled, holds,
  available) and that it is a projection.
- **Deps**: P3-TSK-012, P3-TSK-009.
- **API**: `@RequiresSession`; `@RequiresIdempotencyKey` on the open.
- **Invariants**: `INV-AUD-03`, `INV-BAL-04` (available is presented as `settled − holds`).
- **Tests**: two customers reach exactly their own accounts; no request shape yields a 500; the
  OpenAPI diff reviewed and accepted.
- **Accept**: end to end over HTTP — verified customer opens, reads a balance, sees it change
  after a posting.
- **Gate evidence (2026-09-17)**: full battery green — 1079 hermetic, 639 database, 14 kafka
  tests. **The acceptance end to end over real HTTP**: register-shaped fixtures with a live
  session, `POST` opens (201), the list shows it, the balance reads zero-at-currency
  (`"0.00"` — a scaled amount, never a bare 0), a real `PostingService` credit lands, and the
  next read shows settled and available moved with holds zero — nothing to wait for, because
  the projection is transactional (ADR-0041). **The response names its numbers and its
  nature**: `kind: "PROJECTION"`, per-currency `{settled, holds, available}` with
  `available = settled − holds` (`INV-BAL-04`'s presentation), and **the platform's first
  published amounts leave as decimal strings** — a JSON number is a `double` in every careless
  client, and `INV-MON-01`'s reasoning does not stop at our own boundary. The display read is
  `ledger`'s second declared projection reader (`BalanceDisplay`/`JdbcBalanceDisplay`),
  lock-free and display-only (`INV-BAL-05` stated in type and prose); the plan's own API table
  puts the balance read here while `P3-TSK-009/-010`'s javadoc had said `P3-TSK-018` — a
  one-task drift, recorded. **Ownership**: open and list take no identifier at all; the
  balance's `{id}` resolves through `findOwnedBy`, whose `customer_id = ?` is the check in the
  statement — unknown, not-yours and malformed proven **one 404 as an equality between the
  causes** (per-request members normalised). `OwnershipIsScopedTest` classified it
  `OWNER_SCOPED` — and its own machinery met the second module: the OWNER_SCOPED predicate
  check hardcoded `identity_id = ?` under a javadoc saying "one module owns every table this
  rule covers", widened to the documented ownership-predicate set; the `revokeOwned` entry's
  "the only operation whose resource identifier comes from the request" corrected the same
  way. **Idempotency at two layers**: the executor replays the recorded 201 byte-for-byte
  (`INV-IDEM-01`), a reused key with a different request is a 409 conflict (`INV-IDEM-03`, the
  fingerprint binding the party as ADR-0004's owning principal), a keyless request is the
  interceptor's 422, and `openOrConverge` remains the layer beneath. Two new codes catalogued
  (`accounts.AccountOpeningRefused` 409 cause-blind, `accounts.UnsupportedCurrency` 422),
  mapped globally; the no-500 sweep drives eight body shapes. **The generated contract caught
  two published-name defects before the baseline was born**: `operationId: "open_1"` (a
  method-name collision suffix) and a raw generic `list` — both renamed
  (`openAccount`/`listAccounts`/`readBalance`), then the diff accepted: 222 added lines, zero
  removed, the `BREAKING` labels the classifier erring safe on brand-new required
  fields/enum/params (the recorded precedent). **One honest limit recorded**: the
  `available = settled − holds` formula is mutation-untestable while holds are structurally
  zero — owned by `P3-TSK-015`'s tests. **Eight mutation runs, all caught, restores
  byte-identical**: the ownership predicate dropped (caught **twice** — behaviourally and by
  the widened build rule), `@RequiresSession` removed (caught **twice** — the 401s and
  `EveryEndpointDeclaresARuleTest`, the latter by the `--tests` filter's own construction),
  the fingerprint made constant, the absent-projection-row zero made a throw,
  `@RequiresIdempotencyKey` removed, the malformed-id fold into 404 removed.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-API`, `DOD-FIN`

**P3-TSK-014 — Closing an account, without closing its history** — `COMPLETE` (2026-09-17)
- **Objective**: `CLOSED` ends the agreement and **not** the accounting history (`INV-HIST-01`).
- **Scope**: close with a zero-balance precondition; the ledger account stops accepting postings
  and keeps every row.
- **Deps**: P3-TSK-013.
- **Tests**: closing with a non-zero balance refused; posting to a closed account refused under
  the account lock; history intact and readable afterwards.
- **Gate evidence (2026-09-17)**: full battery green — 1079 hermetic, 645 database, 14 kafka
  tests — **and M3.4 closes with it**: the milestone acceptance holds end to end over HTTP
  (open 201 → balance → `DELETE` 204 → the list shows `CLOSED` → the balance of the closed
  account still readable — the history over HTTP — → a repeated `DELETE` converges 204).
  **The race this task exists for is closed by lock-mode analysis, not hope**: every in-flight
  posting holds `FOR KEY SHARE` on its accounts (the `journal_line` FK, and `V007`'s trigger
  read takes it explicitly), a plain status `UPDATE`'s `FOR NO KEY UPDATE` would NOT conflict
  with it, so the closer takes `SELECT … FOR UPDATE` — the mode that does — then looks
  (`P2-TSK-015`). Both interleavings proven **deterministically** with the loser observed
  Lock-waiting in `pg_stat_activity`: a posting in flight blocks the close, whose
  fresh-statement derivation then sees the money and refuses (`accounts.AccountNotEmpty`, 409,
  naming **no amount** — `INV-AUD-02`); a close in flight blocks the posting's trigger read,
  which on resume re-reads `CLOSED` and refuses — the exact race `P3-TSK-006` recorded that a
  lock-free status read loses. **`V007` is the DB-CONSTRAINT-rank half**: a `BEFORE INSERT`
  trigger on `journal_line` refusing any non-`ACTIVE` account for every writer — proven by raw
  SQL under the application role against a from-scratch database — translated in the store to
  the named `LedgerAccountNotPostableException` so Phase 4 can treat it as a domain outcome.
  **The zero-balance check derives from postings inside the lock** (`INV-BAL-05` — never the
  projection; recorded honestly: the swap is behaviourally invisible because the projection is
  transactional, so that property is held by the stated design and review rather than a
  runnable mutation). The stores' deferred status moves arrived with their first caller as
  their javadocs promised (`lockOwnedBy` + `moveStatus` on both stores; classified in
  `OwnershipIsScopedTest` — `OWNER_SCOPED`/`AUTHORITATIVE_ID`/`NOT_OWNED`, with the closing
  test as the named negative). `ACCOUNT_CLOSED` arrived with the design that fixed it, no
  reason (the withdrawal argument), emitted by the closing call only; ten concurrent closes
  produce one transition, one record, one event, nine converged — and the freed slot admits a
  successor agreement (`INV-LIFE-04`'s asymmetry). History proven intact: same line count,
  same stored values, readable after the close. The plan's §9 table had **no close endpoint
  row** while its own M3.4 line says "open/query/close over HTTP" — the recurring plan-drift
  class, corrected with provenance. **Seven mutations, all caught by the intended assertion,
  restores byte-identical**: the zero-check dropped, the `V007` trigger dropped, the trigger's
  `FOR KEY SHARE` made lock-free, the closer's `FOR UPDATE` dropped (each lock proven
  load-bearing by its own interleaving), the converged path acting again, the audit dropped,
  the ledger close dropped.
- **Risk**: Medium. **Cx**: S. **DoD**: `DOD-FIN`

## P3-EPIC-05 — Holds and available balance (M3.5)

**P3-TSK-015 — `Hold`: place and release against available balance** — `COMPLETE` (2026-09-17)
- **Objective**: `INV-BAL-04` — a hold cannot make available balance negative.
- **Context**: Ledger. **Scope**: the aggregate; `V006` creating `ledger.hold`; place/release
  taking `SELECT … FOR UPDATE` on the **account row**, deriving from postings inside the lock
  (ADR-0039), then acting; `holds_minor` maintained in the same transaction.
- **Deps**: P3-TSK-009.
- **Invariants**: `INV-BAL-04`, `INV-CON-01`, `INV-BAL-05` (the decision does **not** read the
  projection).
- **Distributed**: **the phase's sharpest contention point.** Ten instances placing holds against
  one account: the sum of accepted holds never exceeds available balance. The test must observe
  the losers **blocked** in `pg_stat_activity` (the `P0-TST-004`/`P2-TSK-015` idiom), not merely
  observe the outcome — an outcome-only test passes against both the right and the wrong
  mechanism.
- **Tests**: release restores availability **exactly**; a released hold cannot be released twice;
  holds survive a crash mid-placement as all-or-nothing.
- **Gate evidence (2026-09-17)**: full battery green — 1090 hermetic, 653 database, 14 kafka
  tests. The `Hold` aggregate (`ACTIVE → RELEASED`, `RELEASED` terminal, amount strictly
  positive), `V008` (`ledger.hold`: `MoneyColumns` shape pinned, currency bound to the
  account's by composite FK, status/release-instant coherence, a `BEFORE UPDATE` trigger
  making `RELEASED` terminal for **every** writer, grants `SELECT, INSERT` +
  `UPDATE (status, released_at)` — the migration is **V008, not the "`V006`" this item
  named**: numbering drift from planning time, recorded), and `HoldService` — the
  `PostingService` position, no HTTP surface (plan §9 declares none) and no key of its own
  (a hold joins its commanding flow's transaction and replays with that flow's key).
  **The protocol is `P3-TSK-014`'s lock-mode analysis reused**: place and release take
  `SELECT … FOR UPDATE` on the account row — the mode that conflicts with every in-flight
  posting's `FOR KEY SHARE` and with every sibling placer — then derive from postings
  (`BalanceDerivation`) and standing holds (`ACTIVE` rows folded through `Money.plus`, never
  a SQL `SUM`) in fresh statements. **The ten-way race**: ten instances placing 1000 against
  3000 available — exactly three accepted, seven refused, counted in the table, with
  `holds_minor` equal to the fold of the active rows; the deterministic interleaving observes
  the loser **Lock-waiting in `pg_stat_activity`**, resuming onto the winner's committed hold
  and refusing. The boundary is exact: a hold of exactly available is accepted, one minor
  unit more refused (`HoldExceedsAvailableBalanceException` — account and currency, **no
  amount**, `INV-AUD-02`; no account may permit overdraw — `INV-BAL-04`'s permission clause
  has no subject). Release converges (row count gates the decrement, the record and the
  event) and restores availability **exactly** — the full amount is placeable again.
  **`INV-BAL-05` is behaviourally catchable here**, closing the `P3-TSK-014`-class honest
  limit: a `holds_minor` corrupted through the app role's own grant changes **no decision**,
  and the projection-read mutation is caught by exactly that test. The close gained its
  standing-holds check (a hold on a settled-zero account no longer strands — refused as
  `AccountNotEmpty` under the same lock); the display's `available = settled − holds`
  became load-bearing (`P3-TSK-013`'s recorded limit, closed). Plan §8's "partial unique
  where active" corrected with provenance — uniqueness needs a subject and the commanding
  reference is Phase 4's; the index is partial and deliberately not unique. **Recorded
  remainders**: hold expiry is a rail/product rule (Phase 5's authorization lifecycle);
  capture is release-plus-posting in the capturing flow (Phase 4/5); `holds_minor` joining
  the verification job's comparison is M3.8's observability pass; `finapp.ledger.hold.active`
  is §15/M3.8's. **Eight mutations, all caught by the intended assertion, restores
  byte-identical** — the account `FOR UPDATE` dropped (the blocked-observation precondition),
  the availability check dropped, the decision reading `holds_minor` instead of the rows,
  the release decrement dropped, the converged release acting again, the audit dropped, the
  event dropped, the freeze trigger dropped from `V008`.
- **Accept**: the ten-way race respects available balance; the lock is proven load-bearing by
  removing it. **Met** — see gate evidence.
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`

**P3-TST-002 — `INV-CON-01` and `INV-BAL-04` under contention** — `COMPLETE` (2026-09-17)
- **Scope**: the register rows for both, with the mutations that break them (the lock removed;
  the availability check moved outside it; the hold read from the projection).
- **Deps**: P3-TSK-015. **Risk**: High. **Cx**: M. **DoD**: `DOD-TEST`
- **Gate evidence (2026-09-17)**: the `INV-CON-01` and `INV-BAL-04` rows landed in
  `MUTATION_TESTING.md` §2 with the item's own §4 row, each naming its tests by
  `Class#method` and the observed result — all `Recorded` form honestly, since every
  mutation edits production code. **The audit found one of the three named mutations had
  not been performed**: `P3-TSK-015`'s sweep performed the availability check *dropped*,
  and the item names the check *moved outside the lock* — a different defect, the
  `P2-TSK-015` write-skew shape wearing hold clothes: the code still locks, still checks,
  and still loses the race, which is exactly the mutation that passes every sequential
  test. **Performed by this item** (the derivation and the standing-holds fold hoisted
  above `lockForUpdate`, judged after the grant): **caught by both intended assertions** —
  the deterministic interleaving's blocked-observation precondition stays green (the lock
  is still taken) and its **outcome half** fails (the loser resumes, judges its pre-lock
  snapshot and wrongly accepts — two holds of 600 in 1000), and the ten-way race admits
  more than was available. Restore byte-identical. The lock-removed and projection-read
  demonstrations recorded from `P3-TSK-015`'s sweep log (M1: the blocked-observation
  precondition fails; M3: the corrupted-`holds_minor` test — which is what makes
  `INV-BAL-05` behaviourally catchable, its own row **deferred in writing to the exit
  review**, the `P2-TST-001` handling of `INV-KYC-06`). Register guard teeth re-proven per
  §5: one method reference corrupted (backup copy, never `git checkout --`),
  `everyNamedMethodExists` failed naming exactly
  `HoldDatabaseTest#theLosingPlacerIsObservedBlockedThenRefusedX`, restored and verified
  byte-identical, green again — all nine guard checks green over the new rows. **M3.5
  closes at 2 of 2**: its acceptance — *a hold cannot exceed available balance under a
  ten-way race; release restores availability exactly* — held by `P3-TSK-015`'s
  demonstration and now recorded where the exit review will look. No production code
  shipped. Full battery green: 1090 hermetic, 653 database, 14 kafka tests.

## P3-EPIC-06 — Correction without mutation (M3.6)

**P3-TSK-016 — Reversal: a new effect referencing the original** — `COMPLETE` (2026-09-17)
- **Objective**: `INV-REV-01`, `INV-REV-02`.
- **Scope**: reversal entry with directions swapped and a reference to the original; bounded by
  the original accounting for previous partial reversals; the original **byte-identical**
  afterwards, asserted.
- **Deps**: P3-TSK-006.
- **Distributed**: concurrent partial reversals cannot over-reverse — the bound is a predicate in
  the statement, not a read-then-act.
- **Tests**: over-reversal refused; concurrent partial reversals sum correctly; the original row
  compared byte for byte before and after.
- **Gate evidence (2026-09-17)**: full battery green — 1100 hermetic, 660 database, 14 kafka
  tests. `V009`: `reverses_entry_id` with the implication `CHECK` (`(entry_type = 'REVERSAL')
  = (reverses_entry_id IS NOT NULL)` — a reversal references, nothing else may), no
  self-reference, a chain-refusing entry trigger (a reversal of a `REVERSAL` is refused for
  every writer — a correction of a correction is a new posting or adjustment), and **the
  bound at `DB-CONSTRAINT` rank**: a `BEFORE INSERT` line trigger summing prior reversal
  lines per `(account, direction)` pair against the original's opposite side, scale-guarded,
  refusing `23514` with a stable marker translated to the named `OverReversalException`.
  **The item's "predicate in the statement" was corrected on the record**: for insert-vs-
  insert a statement predicate re-evaluates against the statement snapshot and cannot see a
  concurrent uncommitted sibling — exactly the `P2-TSK-015` write-skew — and the row-lock
  arbiters are unavailable by the phase's own privilege design (no `UPDATE` on
  `journal_entry`, so no `FOR UPDATE`; a mutable reversed-total row would be a second
  authority for a number the immutable rows define). The serializer is
  `pg_advisory_xact_lock(2, hashtext(original))` **taken inside the trigger** — namespace 2,
  registered in `DISTRIBUTED_EXECUTION.md` — so every writer reversing one original queues,
  raw SQL included. **Both interleavings proven**: the losing reversal observed Lock-waiting
  in `pg_stat_activity`, resuming onto the winner's committed rows and refusing; ten
  concurrent partials of 400 against 1000 accept exactly two, counted in the table. The
  domain half (`ReversalBound`, hermetic — plan §12's "reversal arithmetic including partial
  reversals") refuses deterministically before any idempotency claim; each layer suffices
  alone, proven by removing **both** (the `P1-TSK-018` defence-in-depth form). **The
  original is byte-identical afterwards**, captured as PostgreSQL's own row rendering
  (`e::text` and every line) before the reversal and compared after — on top of the standing
  `DB-PRIVILEGE` immutability. The effect was **extracted, earned by its second caller**
  (`PostingEffect`: journal, audit, outbox, projection-last — `PostingService` delegates
  unchanged); `ReversalService` has its own idempotency scope (`ledger.reverse`), replay
  proven one-entry. **No new audit action or event type, decided on the record**: the act is
  *a journal entry was posted* — `ledger.JournalEntryPosted` carries `entryType` as data and
  the entry row carries `reverses_entry_id`, where an investigator joins it
  (`AUDITABLE_ACTIONS.md` records the decision). No reason field: the reason regime is the
  adjustment's (`INV-REV-04`, `P3-TSK-017`), which V004's implication was written to leave
  free. `Direction.opposite()` arrived with its promised first caller. **Seven mutations,
  all caught by the intended assertion, restores byte-identical** — the bound trigger's
  refusal dropped (three tests fail, the raw-SQL refusal among them), the advisory
  serializer removed (the blocked-observation precondition), domain check AND trigger both
  dropped, `opposite()` made identity, the implication CHECK dropped, a replay re-entering
  the effect, the attribution losing its reference. One harness self-check corrected on the
  way: a wrap plant legitimately contains its original (the `P1-TSK-027` class), and the
  survived-check now applies only to replacements.
- **Accept**: met — see gate evidence; the original byte-identical and the ten-way bound
  counted in the table.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`

**P3-TSK-017 — `POST /v1/ledger/adjustments`: reason, permission, audit** — `COMPLETE` (2026-09-17)
- **Objective**: `INV-REV-04` — the highest-risk financial action in any platform.
- **Scope**: the endpoint behind `LEDGER_ADJUST`; reason code required and bounded in three
  reconciled places; audited with actor and correlation.
- **Deps**: P3-TSK-007, P3-TSK-016.
- **Out of scope**: **four-eyes** — recorded debt (`INV-AUD-04`, ADR-0010). The task must record
  the remainder rather than imply a threshold check is four-eyes.
- **Tests**: negative authorization; missing reason 422; every adjustment audited.
- **Gate evidence (2026-09-17)**: full battery green — 1102 hermetic, 668 database, 14 kafka
  tests — **and M3.6 closes with it.** `AdjustmentService` in `ledger` (`PostingService`'s
  discipline, scope `ledger.adjust`, validate-then-claim, through the extracted
  `PostingEffect` — which now **derives the audit action from the entry's kind**:
  `ADJUSTMENT` records `ledger.AdjustmentPosted` **with its reason**, everything else stays
  `JOURNAL_ENTRY_POSTED`; the event vocabulary stays one, plan §10's own list, and both
  ledger actions leave `NOT_YET_EMITTED`). The boundary: `@RequiresPermission(LEDGER_ADJUST)`
  — **the permission's first real check site, exactly as `P3-TSK-007` recorded** — plus
  `@RequiresIdempotencyKey` (money-moving; `P0-TSK-017`'s header meeting the money it was
  built for). **The first request body ever to carry amounts**: decimal strings parsed
  **exactly** (`Money.of` — an inexact amount is the caller's 422 naming the line and field,
  never a rounding, `INV-MON-03`); the reason bounded in **three reconciled places** (DTO
  `@Size(max = AuditRecord.MAX_REASON_LENGTH)`, `V004`'s `CHECK`, `AuditRecord`).
  **The fingerprint binds the actor AND the reason** (ADR-0004; `INV-IDEM-03`): a second
  operator replaying a logged key gets a 409, and so does the same key with a different
  justification — a reason is what makes an adjustment defensible, so two requests differing
  only there never silently collapse into one record. Three codes catalogued
  (`ledger.UnbalancedAdjustment` 422, `ledger.UnknownAccount` 422 — the store translating
  the line FK's `23503`, the V007 pattern, via `UnknownPostingAccountException` —
  `ledger.AccountNotPostable` 409), mapped globally. Proven over real HTTP: the operator's
  201 with the `ADJUSTMENT` row, its stored reason, the `ledger.AdjustmentPosted` record
  naming the **person** and carrying the reason, the event, the projection moved; the
  no-role 403 with nothing written (`INV-AUD-03` with test 1 as the positive control);
  missing reason 422; **unbalanced 422 whose key then carries the corrected request**
  (validate-before-claim, visible at HTTP); replay = same entry, one record; unknown account
  422; keyless 422; nine body shapes, none our 500. Contract: 68 added lines, **zero
  removed**; `BREAKING` labels the classifier erring safe on a brand-new path (the
  `P1-TSK-006` precedent), reviewed and accepted; `operationId` a real name
  (`postAdjustment`). **Four-eyes recorded, not implied**: no threshold check exists, and
  the controller javadoc says so in as many words. **Seven mutations, all caught by the
  intended assertion, restores byte-identical** — the permission removed, the reason's
  `@NotBlank` dropped (the 422 becomes a 500), the action derivation dropped (an adjustment
  recorded as a plain posting), the actor dropped from the fingerprint, the unbalanced
  mapping removed, the key requirement removed, the reason dropped from the fingerprint.
  One planned mutation was cut on analysis rather than performed and recorded as such:
  claim-before-validate is behaviourally invisible in the caller-transaction model, because
  the refusal's rollback takes the claim with it either way — the ordering is architectural
  discipline, not a testable boundary here.
- **Accept**: met — see gate evidence.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-SEC`

## P3-EPIC-07 — Statements and the trial balance (M3.7)

**P3-TSK-018 — Statements derived from postings** — `COMPLETE` (2026-09-17)
- **Scope**: `GET /v1/me/accounts/{id}/statement` for a period, derived from postings, with
  opening and closing balances that reconcile to the lines between them (`INV-ACC-02`'s
  drill-down shape, three phases early).
- **Deps**: P3-TSK-013.
- **Gate evidence (2026-09-17)**: full battery green — 1102 hermetic, 672 database,
  14 kafka tests — **and M3.7 opens with it.** `StatementDerivation`/`JdbcStatementDerivation`
  in `ledger`: opening = `BalanceDerivation.derive` at `AsOf.postingDate(from − 1)` — the
  definition composed, never copied — the period's lines in one statement/one snapshot
  (`journal_line JOIN journal_entry` on `posting_date BETWEEN`, both boundaries inclusive,
  ordered `posting_date, entry.id, seq`), folded through `JournalEntry.sum`, and
  **the closing computed as `opening + settle(debits, credits)` rather than derived a third
  time** — the design's crux: under `READ COMMITTED` the opening read and the lines read are
  two snapshots, but their ranges are **disjoint predicates** (`≤ from−1` vs `[from, to]`),
  so no interleaved commit can land in both or between them and
  `opening + lines = closing` holds **structurally** under any concurrency, never by
  scheduling luck. No lock anywhere (a statement must never contend with the write path);
  no migration (the `journal_line_by_account` index serves the read); underivable histories
  refuse through the derivation's own amount-free regime. The surface: the `/v1/me` shape —
  ownership resolved `Session → Identity → live Customer → findOwnedBy` (`customer_id = ?`
  in the statement), unknown/not-yours/malformed one 404 **asserted as an equality between
  the causes**; the period parameters are the caller's own correctable values, so their
  refusals are specific 422s naming the parameter and never echoing the value; a `CLOSED`
  product's statement stays readable (`INV-HIST-01` — the agreement ended, the accounting
  did not). Response `kind: "DERIVED"` (the symmetric answer to the balance endpoint's
  `"PROJECTION"`), amounts as decimal strings; per line: entry id (the drill-down key),
  posting/value dates, type, direction, amount, reference — and **deliberately no
  counterparty account and no `reason`** (free text written by a person,
  `RESTRICTED-PII` — audit material, never statement material), both asserted.
  **The closing held to an independent `BigDecimal` recomputation over raw SQL rows**
  (`P3-TSK-008`'s discipline: never certify the kernel with the kernel). Deliberately not
  audited — a person's own read of their own account (the `SessionQueries`/balance-read
  stance, recorded in the javadoc); no events, no key (a GET), no meters (M3.8's).
  Contract: 36 added lines, **zero removed**; the three `BREAKING` labels are
  `required = true` on the brand-new operation's own parameters — the classifier erring safe
  (the `P1-TSK-006` precedent), reviewed and accepted; `operationId` a real name
  (`readStatement`). **Three stale `P3-TSK-018` records settled at their sources**: the
  `BalanceProjection`/`BalanceProjectionTest` javadocs and the ownership register's `derive`
  entry had named this task as "the display query"/"the balance endpoint" — that surface was
  `P3-TSK-013`'s, the recorded one-task plan drift, now corrected where it lived; the
  register's `derive` and `findById` entries record what actually arrived, and
  `JdbcStatementDerivation.periodLines` joined the register (`NOT_OWNED`, the disclosing
  surface's provenance named). **Seven mutations, all caught by the intended assertion,
  restores byte-identical** — the period's upper bound made exclusive, the opening
  derivation dropped, the period net dropped from the closing, the period sides swapped
  (sign inverted), the reason leaked into the line's reference, the inverted-period refusal
  dropped at the boundary (the port's IAE surfacing as our 500 — caught by the 422
  assertion, proving the boundary owes the answer), the account predicate neutralised
  (every account's lines in one statement — caught deterministically, because balanced
  entries make the leaked period net exactly zero and the closing collapses to the opening).
- **Accept**: met — see gate evidence.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-API`, `DOD-FIN`

**P3-TSK-019 — The trial-balance job: zero per currency, or an incident** — `COMPLETE` (2026-09-17)
- **Objective**: `INV-ACC-01`, the primary continuous correctness signal.
- **Scope**: a job asserting total debits = total credits per currency across all postings;
  `finapp.ledger.trial.balance` gauge per currency; alerting; **it never self-corrects** — a
  ledger that repairs itself has destroyed the evidence.
- **Deps**: P3-TSK-006.
- **Tests**: an injected imbalance (via a direct `INSERT` bypassing the domain) is detected and
  alerted; the job is safe under concurrent posting; the gauge is NaN when unreadable.
- **Gate evidence (2026-09-17)**: full battery green — 1105 hermetic, 674 database,
  14 kafka tests — **and M3.7 CLOSES at 2 of 2.** `TrialBalance` in `ledger`: one
  `SELECT ... GROUP BY currency, scale, direction` — one statement, one snapshot — with the
  per-currency verdict computed as **exact decimal arithmetic** in Java. The deviation from
  the `Money` fold is argued on `P3-TSK-008`'s own terms: both failure modes it names are
  **structurally closed at this one statement** — cross-scale addition cannot occur because
  `scale` is a grouping key, and silent widening cannot occur because `SUM(bigint)` is
  `numeric`, read back as `BigDecimal` exactly; `Money` itself is deliberately not used,
  because a system-wide group sum can legitimately exceed `long` and `Money`'s overflow
  refusal would turn a large **balanced** ledger into a false incident — the one failure a
  monitoring job must not produce. **No `IN_FLIGHT` verdict exists, and that is the design**:
  a snapshot never contains half an entry and every committed entry balances at COMMIT, so
  every snapshot of a healthy journal balances exactly — demonstrated by sweeps racing four
  live posters (≥8 sweeps overlapping ≥40 commits, every verdict zero). **The injection
  rides the deferral**: V004's balance constraint is `INITIALLY DEFERRED`, so an open
  transaction holds raw unbalanced rows the constraint has not yet judged — exactly what a
  trigger-less writer's committed rows look like to the sweep's one `SELECT` — three shapes
  at once (USD equal-raw-sums-at-different-scales 1500@2 vs 1500@3, the `P3-TSK-005` scales
  probe at system level; EUR debit-excess and GBP credit-excess of the same decimal value,
  so collapsed currency buckets would cancel), all three flagged, then rolled back: nothing
  commits, no trigger is disabled, no cleanup can leak corruption into sibling suites.
  **What leaves the class is verdicts and currency codes, never an amount** (the
  `ProjectionVerification` stance, `INV-AUD-02`). The gauge:
  `finapp.ledger.trial.balance{currency=...}` — 0 verified balanced, 1 out of balance,
  **NaN when unreadable, never zero** (zero means *verified balanced*, so a comforting zero
  would silence the one alert the gauge exists to fire); eager per `SupportedCurrencies`
  (`P1-TSK-029`), a currency found only in history registers at discovery; WARN names
  currency codes only, rate-limited by the 30s cache floor. **The scrape is the schedule** —
  the `P3-TSK-010` answer to plan §14's no-leader warning verbatim: read-only, idempotent,
  no lease, nothing ambient, no `DISTRIBUTED_EXECUTION.md` §3 question; every instance
  publishes the same fleet-wide figure (`max()`, never `sum()`). **`currency` joined
  `ALLOWED_TAG_KEYS` deliberately** — bounded by ISO 4217, a category shared by everyone
  that cannot name a person or a resource, and the plan's §15 table says "per currency"
  (the `purpose` precedent). **It never self-corrects, structurally**: the class issues
  exactly one `SELECT`, and repair is a reasoned adjustment (`P3-TSK-017`). Dashboard row
  deferred to M3.8 with the drift panel, per `P3-TSK-010`'s recorded deferral;
  `LedgerMetrics$TrialCached` joined the floating-point exemption set as the same
  Micrometer-gauge case a fifth time. **Six mutations, all caught by the intended
  assertion, restores byte-identical** — the zero comparison neutralised, the currency
  buckets collapsed (the cross-currency subsidy cancels), the scale dropped from the
  decimal conversion (raw minor units compared), the direction sign dropped (caught by the
  positive control), the unknown reading made zero, the eager registration removed. One
  process finding recorded: the first battery invocation **never ran** — a `grep -c`
  returning zero matches broke the `&&` chain before gradle started, and the missing log
  file is what caught it (the build-never-ran class, met in the chaining rather than the
  harness).
- **Accept**: met — see gate evidence.
- **Risk**: High. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-OBS`

**P3-TST-003 — The financial supplement F1–F8, demonstrated** — `COMPLETE` (2026-09-17)
- **Scope**: each of F1–F8 assessed with a named test, and the register rows for every
  `Phase: 3` invariant — **the set read from `FINANCIAL_INVARIANTS.md`, not from
  `PHASE_3_PLAN.md` §6** (the Phase 2 → 3 transition's finding: `INV-HIST-02` belonged to Phase 2
  while sitting outside both of its named groups, and only the status flip revealed it).
- **Deps**: everything above. **Risk**: High. **Cx**: M. **DoD**: `DOD-TEST`
- **Gate evidence (2026-09-17)**: reading the set from the catalogue found **nineteen**
  `Phase: 3` invariants where the plan's §6 table lists seventeen — `INV-REC-05` and
  `INV-AUD-04` sit outside it, the exact drift the scope sentence predicted; the plan's own
  closing paragraph already rules the catalogue wins, so the finding is recorded rather than
  the table edited. **The audit found every recordable demonstration already performed by its
  owning task's sweep** (the `P2-TST-001` posture: record, not re-perform): fourteen new
  `MUTATION_TESTING.md` §2 rows landed plus two extension rows (`INV-BAL-03`'s posting half;
  a fourth `INV-CON-01` row for the account-lifecycle context) and a second `INV-IDEM-01` row
  for the financial boundary — every named class and method held to the code by the register
  guard from the moment they landed, and all nine `MutationDemonstrationTest` checks green.
  `INV-BAL-05`'s row landed here, earlier than `P3-TST-002`'s recorded deferral, because this
  item's scope demands every row and what the deferral postponed was the record, not the work.
  **The sixteenth row cannot be written and that is the headline finding**: `INV-AUD-04` is
  `Phase: 3` in the catalogue and its mechanism is deliberately unbuilt (`P3-TSK-017` recorded
  four-eyes as ADR-0010's debt; the debt row's trigger fired there) — a row naming the
  adjustment suite's tests would be a false claim, and the build **will fail naming it the
  moment Phase 3 flips `COMPLETE`**, known in advance this time (the `INV-HIST-02` lesson
  pre-applied). Recorded in the register's §3; the mechanism and its row are `P3-TSK-021`'s,
  created by this item and scheduled before the review. The F1–F8 assessment is
  `docs/project/reviews/PHASE_3_FINANCIAL_SUPPLEMENT.md` — every criterion met (F5 met with
  its Phase-3 vacuity stated: no external event produces a financial effect this phase, and
  the mechanism that will bind is the proven inbox), every named `Class#method` also named by
  a §2 row so the register guard holds the supplement's references too, verified by script.
  §5 teeth re-proof performed: one method reference corrupted, `everyNamedMethodExists`
  failed naming exactly it, restored byte-identical. No production code shipped.
- **Accept**: met — see gate evidence.

## P3-EPIC-08 — Observability and the gate (M3.8)

**P3-TSK-020 — The six planned meters, eagerly registered** — `COMPLETE` (2026-09-17)
- **Scope**: `PHASE_3_PLAN.md` §15's table, registered at construction and **unconditionally** —
  `P2-TSK-020`'s finding that a plan-named meter behind a property condition is the same defect
  wearing a condition; plus a dashboard row whose queries resolve.
- **Deps**: the flows they measure. **Risk**: Low. **Cx**: S. **DoD**: `DOD-OBS`
- **Gate evidence (2026-09-17)**: all six §15 series published by a freshly started instance,
  held by the **pinned Phase-3 guard** in `PlannedMetersExistTest` (the `P2-TSK-020` shape: the
  plan's own table against the plain no-database context; the derived guard takes over at the
  flip). Two series existed (`trial.balance`, `projection.drift`); four arrived:
  `finapp.ledger.posting{outcome}` and `finapp.ledger.posting.latency` through a new
  **`PostingObserver` port** — a **required** primary-constructor parameter on all three
  journal-write commands, no defaulted overload, so Phase 4's transfer wiring is forced by the
  compiler to decide rather than silently lose its counts (the `MeteredKycCaseStore` argument
  made structural); `finapp.ledger.hold.active` (fleet-wide `COUNT` via the new
  `HoldStore.countActive`, NaN never zero, `max()` never `sum()`); and
  `finapp.accounts.account{opened|closed}` counted **after the commit and only for the acting
  call** (`Creation.created()`/`Closure.closed()` — a converged retry and a replay are never
  throughput). The observer wiring is proven **through real HTTP**, not a test's own registry:
  posted +1, a replay +1 replayed with posted unchanged, an unbalanced refusal +1 refused,
  every command timed. **`P3-TSK-015`'s owned remainder landed with its owner**:
  `ProjectionVerification` now compares `holds_minor` against the fold of the `ACTIVE` hold
  rows **through the kernel** (`JournalEntry.sum`, never a SQL `SUM`), read in the **same
  statement** as the projection row — one snapshot, so no watermark is needed: a hold
  transaction updates both atomically under the account lock, and a single statement cannot
  see half of that; an unverifiable fold is `DRIFTING`, because unverifiable is not clean. The
  extension found its own enabling fix in advance: `HoldDatabaseTest`'s corruption test left
  `holds_minor` corrupted **committed** in the shared container — harmless before, permanent
  global drift after — so it restores now. Dashboard row *Ledger and accounts — financial
  correctness* (six panels; the deferred drift and trial-balance panels land here), every
  query resolving against a live scrape — the trial panel's `by (currency)` grouping label
  joined the resolver's non-series vocabulary, the designed edit-forces-decision path. Timer
  panels read `_count`/`_sum`/`_max`, never `_bucket` (the `P1-TSK-029` lesson);
  `LedgerMetrics$HoldCached` joined the floating-point exemption set (the same Micrometer
  case, sixth time). **Eight mutations, all caught by the intended assertion, restores
  byte-identical** — the observer un-wired, a replay counted as posted, a refusal not
  counted, the eager outcome series narrowed, the unknown hold reading made zero, the holds
  comparison dropped, the opened count made unconditional, the closed count on presence.
  **1107 hermetic tests, 676 database tests, counted.**
- **Accept**: met — a freshly started instance publishes every series, proven by the pinned
  guard rather than asserted.

**P3-TSK-021 — Four-eyes on manual adjustments** — `COMPLETE` (2026-09-17)
- **Scope**: `INV-AUD-04`'s Phase 3 element, found owed by `P3-TST-003`: the catalogue marks
  the invariant `Phase: 3`, the manual adjustment exists (`P3-TSK-017`), and the mechanism is
  deliberately unbuilt — no defined threshold, no second approver, four-eyes recorded as
  ADR-0010's debt whose trigger ("the first action requiring a second approver") has fired.
  Define the threshold, model the second approver (approver ≠ initiator at `DB-CONSTRAINT`
  where representable), refuse self-approval with a negative test, and land the
  `MUTATION_TESTING.md` §2 row — **without which the battery fails at the status flip**,
  because `MutationDemonstrationTest` derives its demanded set from the catalogue. The
  alternative outcome — a superseding decision narrowing the catalogue's phase marking — is a
  decision for this task to argue, not assume.
- **Deps**: `P3-TSK-017`. **Risk**: High. **Cx**: M. **DoD**: `DOD-SEC`, `DOD-FIN`
- **Done**: the catalogue's marking stands and the mechanism is built — **the threshold is
  defined as every adjustment**: `INV-REV-04` permits thresholds, but a threshold is a
  per-currency amount policy (a versioned artefact, `INV-HIST-04`) with nothing to calibrate
  it and the `INV-MON-04` cross-currency trap beneath, so unconditional is the honest
  strengthening, with a de-minimis threshold recorded as a future policy artefact whose seam
  is the proposal row. **Four-eyes is two authenticated acts, never one request with two
  names**: `POST /v1/ledger/adjustments` now *proposes* (`ledger.adjustment_proposal` +
  lines, `V010`; nothing posts; 201 `{proposalId}` — the reviewed BREAKING change, since no
  client exists and a parallel one-person write kept for compatibility would keep the
  invariant violated), `GET …/{id}` shows an approver exactly what they would approve,
  `POST …/{id}/approval` by a **different** `LEDGER_ADJUST` holder posts the entry through
  `PostingEffect` in the approval's own transaction, and `DELETE …/{id}` rejects — or, for
  the initiator, withdraws, deliberately: removing an action needs no second person.
  **Approver ≠ initiator holds at three ranks**: the aggregate refuses self-approval
  (`INV-LIFE-02`), `V010`'s `CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)` is
  the invariant's own Enforce clause at `DB-CONSTRAINT` (plain, because the table is new),
  and a deferred constraint trigger (the `V004` mechanism) refuses any `ADJUSTMENT` entry
  COMMIT without an approved proposal — raw SQL bound, history untouched (`INV-HIST-01`).
  **The approver approves what they read, structurally**: payload and lines frozen by
  trigger for every writer, terminals terminal, the decision columns arriving with their
  edge. **One permission, deliberately** (`P2-TSK-004`'s rule: the trust decision is one;
  the control is person-distinctness; maker/checker is a recorded seam). **Approval carries
  no idempotency key, deliberately**: the one-way machine is the idempotency
  (`INV-IDEM-01` through state, the `P2-TSK-008` natural-key argument) — lock-then-look on
  the proposal row, ten concurrent approvals producing exactly one entry counted in the
  table with every response converging on it; propose keeps the full machinery (scope
  `ledger.adjust`, fingerprint binding actor + reason + lines). **Two audit records are the
  four-eyes trail**: `ledger.AdjustmentProposed` (new, reason required) names the initiator,
  `ledger.AdjustmentPosted` names the approver — ADR-0010's "second actor column" debt
  dissolved rather than paid, and the entry's `idempotency_scope`
  (`ledger.adjust.approve:<proposalId>`) is the investigator's join. A refused
  self-approval writes **nothing** and answers the actionable
  `409 ledger.SelfApprovalRefused`; a decided proposal answers `ledger.ProposalNotOpen`;
  unknown and malformed are one 404. The posting meter observes the **approval** (posted /
  converged-replayed / self-approval-refused) and a proposal moves no meter, because it
  writes no journal. Registries fed: two audit actions, two error codes, eighteen classified
  columns, five ownership-register entries (the journal `findById` entry's predicted
  URL-named arrival landed on the *proposal*), the `MUTATION_TESTING.md` §2 `INV-AUD-04` row
  with §3 rewritten as the resolution record, the plan's three four-eyes-debt statements and
  the supplement's closing item corrected with provenance, the OpenAPI baseline regenerated
  (58 diffs, 5 BREAKING, each reviewed). **Seven mutations, all caught by the intended
  assertion, restores byte-identical** — the domain self-check dropped, `V010`'s CHECK
  dropped, the deferred trigger dropped, the lock made a plain read, the freeze trigger
  dropped, the proposed-audit dropped, the entry's actor made the initiator.
- **Accept**: met — self-approval is refused with the invariant's named negative test,
  nothing written, and a second person approves the same proposal as the positive control;
  the register row stands and the battery survives the status flip.

**P3-DOC-001 — Phase 3 review record** — `COMPLETE` (2026-09-17)
- **Scope**: the `PHASE_GATES.md` §4 review: eight areas, twelve universal criteria, **the
  financial supplement F1–F8 — which binds for the first time and is not "not applicable" here**,
  and the nine Phase 3-specific criteria, each with evidence; numbers counted, never quoted; the
  ADR-0039…0042 acceptance decision. *(The gate in fact lists **sixteen** Phase 3-specific
  criteria — 9 original + 7 transition-extension; "nine" predates the extension and is the
  review's area-7 finding 5. All sixteen were assessed.)*
- **Deps**: everything above.
- **Done**: [`reviews/PHASE_3_REVIEW.md`](reviews/PHASE_3_REVIEW.md) — 8 areas `PASS`, 12
  universal `PASS`, F1–F8 all **Met** (re-assessed at the gate, not inherited), 16
  phase-specific `PASS`, the ten-instances answer `PASS` over nine contended decisions;
  ADR-0039…0042 → `Accepted` in the files and the index. Conducted in the `P2-DOC-001` order —
  assess → land corrections → **flip the status (the guarded act)** → re-run the full battery —
  and the post-flip battery is green with nothing surfaced, because `P3-TST-003` predicted and
  `P3-TSK-021` pre-paid the one failure the flip would have produced. Six area-7 findings:
  the plan's unbuilt `LEDGER_READ` surface (owner: the Phase 3 → 4 transition), two stale
  `LEDGER_MODEL.md` adjustment spots (corrected — the doc's own front matter assigned them
  here), three stale backlog phase headers (corrected), `CURRENT_STATE.md`'s §Next Task stale
  at `P3-TSK-011` across eleven tasks (replaced), this item's "nine" (recorded), the ADR
  second-copy decay (both copies flipped). Counted: 138 mutations across 22 items + 3
  guard-teeth re-proofs, 1 survivor correctly and 0 wrongly; 19 of 19 register rows;
  **1121 hermetic / 683 database / 14 kafka** after the flip.
- **Accept**: met — area 2 **has a subject for the first time in the programme and it is
  walked**: an operator's correction as the economic event → propose/approve as the domain
  operation → the approval's transaction → the `ADJUSTMENT` journal entry with the approver
  as actor → balanced per-currency lines → projection, derivation, statement and trial
  balance, each step naming its code and its test.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-DOC`

---

# Phase 4 — Internal Transfers

Status: `COMPLETE` (2026-09-19, `P4-DOC-001`) — started 2026-09-17 with `P4-TSK-001`; entry gate passed the same day
([`reviews/PHASE_3_TO_4_TRANSITION.md`](reviews/PHASE_3_TO_4_TRANSITION.md)) *(this header
read `READY` until `P4-TSK-008`'s gate — the stale-second-copy class `P3-DOC-001` found in
three earlier phase headers, corrected on being met rather than left)*. Elaborated to
task granularity by the same transition. The engineering plan is
[`PHASE_4_PLAN.md`](PHASE_4_PLAN.md); decisions are ADR-0043 and ADR-0044 (`Proposed`); the
in-scope invariants are whatever the catalogue marks `Phase: 4` — five at planning time
(`INV-IDEM-01` transfers element, `INV-CON-02`, `INV-LIFE-01`, `INV-LIFE-02`, `INV-LIFE-04`) —
**read from the catalogue at the gate, never from this file**. The financial supplement F1–F8
binds this phase as it bound Phase 3; every task below carries `DOD-FIN` where it can affect
money.

**The original epic table is superseded by the elaboration below, with two epics corrected on
the record**: *P4-EPIC-03 Transfer idempotency* is not a slice — the mechanism is the Phase 0
executor and the semantics land inside the execution and HTTP tasks (`INV-IDEM-01/-03`), where
they are testable against real behaviour rather than as a layer; and *P4-EPIC-08's
stuck-transfer detection has no subject* — under ADR-0043 no durable intermediate state exists
to be stuck, so building the detector would be monitoring a fiction (it arrives with the first
asynchronous execution path). Operational query is the ordinary status/list surface.

**Milestones**: M4.1 Foundations (`P4-TSK-001`, `-002`) · M4.2 The movement exists
(`P4-TSK-003`…`-005`) · M4.3 Beneficiaries (`P4-TSK-006`, `-007`) · M4.4 Over HTTP
(`P4-TSK-008`) · M4.5 Reversal (`P4-TSK-009`) · M4.6 The seams (`P4-TSK-010`) · M4.7
Observability and demonstration (`P4-TSK-011`, `P4-TST-001`, `P4-TST-002`) · M4.8 The gate
(`P4-DOC-001`). Acceptance per milestone in `PHASE_4_PLAN.md` §16.

**P4-TSK-001 — The `transfers` module and schema** — `COMPLETE` (2026-09-17)
- **Scope**: the `P3-TSK-011` shape, fourth performance: a guarded module on the documented
  direction with the build-graph edge ADR-0042's discipline decides — `transfers → ledger`
  declared (postings are commanded, never written), `transfers → accounts` **refused** (the
  product resolves through a port `app` implements, the `AccountHolderVerification` shape);
  every existing sibling forbids `transfers` and `transfers` forbids every sibling but
  `ledger`; a planted `ledger → transfers` edge fails Gradle configuration as a cycle,
  demonstrated. `V001`: the `transfers` schema, owner `finapp_migrator`, `REVOKE ALL FROM
  PUBLIC`, `USAGE` alone to `finapp_app`, no tables, no `ALTER DEFAULT PRIVILEGES` — each
  table's grants arrive with its migration. Migrate → validate → re-migrate idempotent on a
  throwaway PostgreSQL; ACL checked exactly. No audit-action enum yet (the deliberately-few
  licence: the actions arrive with the aggregates whose designs fix their meaning).
- **Deps**: none (Phase 3 `COMPLETE`).
- **Gate evidence (2026-09-17)**: the full battery green with the module present —
  **1123 hermetic, 683 database, 14 kafka tests** (the two new tests are
  `TransfersModuleIsolationTest`'s pair). The shape delivered whole: guarded module, `V001`
  privilege floor proven live on a throwaway `postgres:18.6` (owner `finapp_migrator`, ACL
  exactly `{finapp_migrator=UC, finapp_app=U}`, no `PUBLIC` entry, `USAGE` and **not**
  `CREATE` for the app role, zero application tables, migrate → validate → re-migrate
  idempotent — two history rows: Flyway's schema-creation marker plus `V001`, the
  `ledger`/`accounts` shape checked rather than assumed). **The ADR-0043 asymmetry is
  structural**: `transfers → ledger` declared with the module (first consumer `P4-TSK-003`),
  so the planted `ledger → transfers` edge fails Gradle configuration —
  `:ledger:compileJava → :transfers:compileJava → :ledger:compileJava` — while
  `TransfersModuleIsolationTest` pins the positive half and all six sibling isolation tests
  gained `transfers` in their forbidden lists (the one-directional-decay lesson, fourth
  application at design time). **Four probes, all caught by the intended guard, restores
  byte-identical** — and the first probe **survived before it was caught, which is the
  task's finding**: the planted `double` passed cleanly until `app` gained
  `implementation(project(":transfers"))`, exactly as the comment beside `app`'s
  business-module block predicts — `ProductionModules` derives the swept set from `app`'s
  classpath, so a module not on it is a module **no rule protects**, and the module-creation
  checklist's untested step was the classpath edge, not the schema. With the edge added the
  probe fails naming `transfers.Planted.amount is double (INV-MON-01)`; `transfers → party`
  fails the new isolation test; `accounts → transfers` fails `AccountsModuleIsolationTest`.
  The deliberate deviation repeated with its licence: **no `TransfersAuditAction` enum**,
  recorded in `package-info.java` (the `P3-TSK-011`/`P2-TSK-005` precedent).
  `DATA_MIGRATIONS.md` §"Adding a schema-owning module" followed end to end — all five
  steps, none stale this time. Housekeeping: `transfers/gradle.lockfile` identical to
  `accounts`'s but for its header line; `gradle/verification-metadata.xml` unchanged (no new
  artefact); no `build-logic` lockfile drift this run.
- **Accept**: met — all four criteria demonstrated (battery; the planted `double` naming the
  module; both directions plus the cycle; the floor live).
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-BUILD`, `DOD-ARCH`

**P4-TSK-002 — The ADR governance registers, build-reconciled** — `COMPLETE` (2026-09-17)
- **Scope**: the twice-carried governance item, finally paid as its own task rather than
  re-carried: a hermetic test reconciling, for every `docs/adr/ADR-*.md`, the file's
  `Status:` line against the README index's status column, and asserting every ADR file has
  exactly one index row and every index row a file — so the second-copy decay (`P2-DOC-001`'s
  finding, met again verbatim by `P3-DOC-001`) becomes a build failure instead of a review
  finding. `DECISIONS.md` is deliberately out of scope: it is curated prose, not a status
  copy, and a coverage check over prose is a false precision. The documents join the declared
  build inputs (the established mechanism).
- **Deps**: `P4-TSK-001` (only for sequencing; no code dependency).
- **Gate evidence (2026-09-17)**: `AdrRegistersAreReconciledTest`, four checks each owning
  its defect — the bijection with every row's **link resolved to the file it names** and
  `ADR-0001` as the non-vacuity anchor; status agreement compared on the leading token (the
  file form legitimately carries provenance, `Status: Accepted (2026-09-17, P3-DOC-001)`);
  the **closed vocabulary** on both copies, because an equality-only check is satisfied by a
  typo present in both; and the outer structural check that every row-looking line inside
  `## Index` parses — the `P1-TSK-024` fix-one-level-out lesson applied at design time. The
  documents joined the declared build inputs as a **file tree, not a list** (a new ADR file
  re-runs the guard without anyone remembering), proven by the probe: the doc-only mutation
  re-ran the task rather than reporting `UP-TO-DATE`. **Five mutations, all caught by the
  intended assertion, restores byte-identical**: the index status of ADR-0043 flipped →
  fails naming the ADR with both values; ADR-0044's row deleted → the bijection; an orphan
  ADR-0099 row → "unexpected: [0099]" — after the first plant landed **outside** the
  `## Index` section and was rightly ignored, the section-bounding working; the file-side
  flip on ADR-0044 → fails naming it; a corrupted row form → the structural check fails
  **naming the line**, with the bijection also firing (two controls, blind in different
  directions). One limit recorded rather than guessed at: a future `Superseded by ADR-NNNN`
  status line fails the parser **loudly**, forcing the format decision at the moment it has
  a subject — the direction to err in. **1127 hermetic, 683 database, 14 kafka tests** —
  the +4 the new test's own methods. **M4.1 closes: 2 of 2.**
- **Accept**: met — a mutated index status fails naming the ADR; a missing row and an orphan
  row each fail; teeth by mutation, restored byte-identical.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-BUILD`, `DOD-DOC`

**P4-TSK-003 — The `Transfer` aggregate and its lifecycle** — `COMPLETE` (2026-09-17)
- **Scope**: ADR-0044 made code: `Transfer` with `INITIATED → {COMPLETED, FAILED}`,
  `COMPLETED → REVERSED`, `FAILED`/`REVERSED` terminal; the machine on the enum
  (`permittedTransitions()`, `sqlValueList()`, `sqlTerminalValueList()` — the established
  generated-constraint ceremony); `FailureReason` enumerated (`INSUFFICIENT_FUNDS`,
  `SOURCE_NOT_POSTABLE`, `DESTINATION_NOT_POSTABLE`, `CURRENCY_MISMATCH`, `SELF_TRANSFER`);
  invariants in the constructor — positive amount, source ≠ destination, reason present
  exactly when `FAILED`, entry id present exactly when money moved. Hermetic only; no store,
  no schema (next task), no service.
- **Deps**: `P4-TSK-001`.
- **Gate evidence (2026-09-17)**: all three criteria by demonstration — the cross-product
  sweep derived from `values()` with per-outcome transition doors (`INV-LIFE-01/-02`), both
  terminals swept separately (`INV-LIFE-04`), and **the machine pinned exactly** — which IS
  the `COMPLETED`-has-one-exit assertion, plus the property that **nothing transitions TO
  `INITIATED`**: no state permits it and no method targets it, birth being the only door.
  **One constructor holds every invariant and every path shares it** — birth, the three
  per-outcome transitions (each routed through the same machine check), and `rehydrate`, so
  a corrupt row is refused on read-back ahead of `V002`'s `CHECK`s. Coherence both
  directions per rule: reason ⇔ `FAILED`; entry ⇔ money moved (`REVERSED` keeps the
  original entry — the reversal is more evidence, not less); the reversal triple
  (entry/actor/instant) ⇔ `REVERSED`. **The one interpretive decision, resolved on the
  record**: the flat "source ≠ destination" of this item's own scope line tensions with
  `SELF_TRANSFER` as a *committed* reason (`P4-TSK-005` commits sibling refusals; ADR-0044
  forbids enum values with no producer), so the pair rule is **coherence with the machine,
  exactly like the reason** — the equal pair is legal only `INITIATED` (unjudged input) or
  as `FAILED(SELF_TRANSFER)` (the committed record of refusing exactly that mistake, which
  conversely *requires* the equal pair); money-moved states and every other reason require
  inequality, and an equal-pair `INITIATED` transfer has exactly one legal exit, enforced
  for free by the constructor. **Typed where the boundary permits, raw where it forbids**:
  `LedgerAccountId`/`JournalEntryId` through the `transfers → ledger` edge (its first use);
  `customerId`/actors raw `UUID` (the `CustomerAccount` precedent — `party`/`identity` own
  the typed ids). **Deliberately no `statusChangedAt`**: the reversal is the only
  post-insert transition and its columns are the narrowed `UPDATE` grant's; transition
  instants are the history table's evidence (`P4-TSK-004`). Only `reverse` reads the clock.
  `INV-AUD-02` at the type: the positivity refusal names the fact and currency, never the
  amount, needle-asserted. The module's `package-info` "nothing implemented" paragraph
  updated with the task that made it stale. **Seven mutations, all caught by the intended
  assertion, restores byte-identical** — the machine check removed from `complete()`,
  `COMPLETED` made terminal, `FAILED` given an exit, the reason coherence dropped, the
  entry coherence dropped, the money-moved pair rule dropped, the amount leaked into the
  refusal message. **1133 hermetic, 683 database, 14 kafka tests** — the +6 the new test's
  own methods.
- **Accept**: met — every invalid transition rejected by the aggregate, swept from the
  cross-product of the machine (`INV-LIFE-01/-02`); both terminals swept separately
  (`INV-LIFE-04`); `COMPLETED`'s single outgoing edge asserted as a property of the machine.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-DOMAIN`, `DOD-FIN`

**P4-TSK-004 — The transfer schema: `V002`** — `COMPLETE` (2026-09-17)
- **Scope**: `transfers.transfer` (UUIDv7 id; owning customer id — the ownership predicate's
  column; source/destination ledger-account references; the `MoneyColumns` generated shape
  pinned verbatim; reference; status with the generated `CHECK`; `failure_reason NOT NULL ⇔
  FAILED`; `journal_entry_id UNIQUE, NOT NULL ⇔ status IN (COMPLETED, REVERSED)`;
  `reversal_entry_id NOT NULL ⇔ REVERSED`; actor and instants from the injected clock, never
  `DEFAULT now()`) and `transfers.transfer_event` (append-only history: seq, from/to, actor,
  instant). A transition trigger permits exactly the machine's edges for every writer (the
  `V010` shape); grants `SELECT, INSERT` plus `UPDATE` column-narrowed to the reversal
  columns; history `SELECT, INSERT` only. Every column classified at its ceiling. A
  migration-reconciliation test derives the `CHECK`s from the enum.
- **Deps**: `P4-TSK-003`.
- **Gate evidence (2026-09-17)**: every raw-SQL accept demonstrated from scratch through the
  per-JVM harness — unknown status and unknown reason; a reasonless `FAILED` and a reason on
  `COMPLETED`; an entryless `COMPLETED` and an entry on `FAILED`; the incomplete reversal
  triple; the pair rule both ways (a completed self-transfer, and `SELF_TRANSFER` naming two
  different accounts); a zero amount — each `23514` with the two coherent shapes as positive
  controls. **The trigger is the `V010` shape, both halves**: everything outside the reversal
  columns frozen for every writer (the migrator's own `amount_minor` update refused), and
  exactly the machine's edges permitted — `COMPLETED → FAILED`, the idle same-status touch
  and any exit from a terminal all refused, with the reversal `UPDATE` succeeding **as the
  app role through exactly the granted columns** (trigger edge, coherence `CHECK`s and
  narrowed grant proven sufficient in one act). The per-column `UPDATE` sweep is **derived
  from `information_schema`** (the `P0-TST-007` idiom) minus the four reversal columns;
  `DELETE` denied on both tables; the history's `id` probed with `DEFAULT` (the `P2-TSK-017`
  identity-column lesson). `journal_entry_id UNIQUE` (one transfer per posted entry,
  whichever instance wrote it); history FK-anchored with the enum vocabulary. **Stricter at
  rest than the aggregate, recorded**: an equal-pair `INITIATED` is refused by the pair
  `CHECK` — `INITIATED` is never durably observed, so the one legal equal pair at rest is the
  committed `SELF_TRANSFER` refusal. The `INITIATED`-source trigger edges are present because
  the machine has them and simultaneously dead at rest (frozen reason/entry columns) — the
  layers agreeing, recorded. History edge-validity deliberately unconstrained: evidence must
  record what a defective writer actually did. `TransferMigrationTest` reconciles four
  generated artefacts — status `CHECK` (three columns), reason `CHECK`, the `MoneyColumns`
  fragment verbatim, and **the trigger's edge conditions from `permittedTransitions()`**,
  with terminal states asserted absent as edge sources. 22 columns classified at their
  ceiling (`reference` `RESTRICTED-PII` — free text a person writes; `failure_reason`
  `CONFIDENTIAL` — `INSUFFICIENT_FUNDS` is a fact about a person's finances). **Eight
  mutations, all caught by the intended assertion, restores byte-identical** — status list
  narrowed and the money fragment edited (reconciliation, hermetic); the trigger's edge check
  removed, the frozen check silenced, the reason/entry/pair `CHECK`s dropped, the grant
  widened table-wide (each against a from-scratch database). **Verified by targeted tiers —
  `:transfers:test` and the schema + classification database tests — the full battery
  deliberately skipped this task on the owner's instruction**; no fleet-wide tier counts are
  claimed.
- **Accept**: met — each raw-SQL refusal from scratch; the derived per-column sweep with its
  positive control; classification guard green.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-BUILD`

**P4-TSK-005 — The execution command: one transaction, the lock, the outcome** — `COMPLETE` (2026-09-17)
- **Scope**: `TransferExecution` (the phase's High-risk task): claim (scope
  `transfer.execute`, fingerprint binding actor + source + destination + amount + currency +
  reference — `INV-IDEM-03`'s subjects) → resolve and validate (ownership via the caller's
  live customer; both accounts `ACTIVE` products; same currency; not self) → `SELECT … FOR
  UPDATE` on the **source account row** (ADR-0039's set, third member) → availability =
  settled (through `BalanceDerivation`) − active holds, **inside the lock, in fresh
  statements** (`INV-BAL-04/-05`, the `P3-TSK-015` protocol reused) → the seams consulted
  (ports arrive `P4-TSK-010`; until then the parameters exist with the documented
  default-permit implementations) → post through `PostingService` (one `POSTING` entry:
  debit source wallet, credit destination wallet; the transfer id in the entry's reference)
  → transfer row + history + `transfers.TransferExecuted` audit + terminal event, one
  commit. Insufficient funds and sibling refusals commit `FAILED` with the reason, **no
  posting and no hold on the money**; boundary mistakes remain 4xx with nothing written.
- **Deps**: `P4-TSK-004`.
- **Gate evidence (2026-09-17)**: **every acceptance clause demonstrated and counted in the
  tables.** The ten-way drain (own connection, scope and flow per instance): exactly **3
  `COMPLETED`, 7 `FAILED(INSUFFICIENT_FUNDS)`**, the source settled at 100 and never
  negative, destination at 900, **the pair summing to the funded 1000 to the minor unit**
  (`INV-CON-02`); the injected failure at the last write (a throwing outbox decorator)
  leaves *nothing* — no row, no entry, no history, **no claim: the same key then executes
  afresh rather than replaying a failure that never committed** (`P3-TSK-006`'s property as
  ADR-0043's demonstration); the retry replays **success and failure both** — the `FAILED`
  refusal commits, so its claim survives and its retry learns it (`CommandResult.failed`,
  the executor's own documented rejected-transfer case); same key + different amount →
  `IdempotencyConflictException` (`INV-IDEM-03`, fingerprint binding actor + source +
  destination + amount + currency + scale + reference). **The named mutation performed and
  caught**: the availability decision hoisted outside the lock — still locks, still checks,
  still loses the race (the `P3-TST-002` shape) — fails the drain with the textbook
  over-acceptance, `expected: 3 but was: 10`. **Seven mutations total, all caught by the
  intended assertion, restores byte-identical** (check dropped; claim made per-call;
  fingerprint constant; event, audit and history each dropped). **The sibling refusals each
  commit their reason**, `SELF_TRANSFER` with the equal pair stored, `CURRENCY_MISMATCH`
  carrying the real accounts (resolution is deliberately **currency-blind** — the row would
  otherwise be unconstructible), destination-closed at resolution, and **the mid-flight
  `V007` path sequentially and deterministically** (a resolution decorator lies, the posting
  trigger refuses, and the **savepoint** turns the aborted transaction state into a
  committed `FAILED(DESTINATION_NOT_POSTABLE)` — named with certainty because the source
  was verified under our own lock); boundary mistakes (unknown source, somebody else's
  source — one indistinguishable refusal) throw with nothing written. **Two additions to
  proven `ledger` code, each earned by its arriving second caller** (the `PostingEffect`
  precedent): `AvailableBalance` extracted from `HoldService` (which now delegates,
  constructor unchanged, `HoldDatabaseTest` the behavioural equivalence proof) with the
  **in-lock contract as the first line of its javadoc**; and
  `LedgerAccountStore.findAllOwned` — the lock-free sibling of `lockOwnedForUpdate`'s read,
  because no currency-blind product→wallet resolution existed. The reason precedence is
  **forced by the aggregate's pair rule** (`SELF_TRANSFER` first) — the constructor teaching
  the execution, recorded. `TransferParticipants` resolves the destination **through the
  ledger alone** (product and ledger status close together, `P3-TSK-014` — recorded as
  today-exact), so no unowned `accounts` read exists and the counterparty-disclosure
  question never arises. `transfers.TransferExecuted` arrives with the command whose design
  fixes it (catalogued; registry and completeness guards green); terminal events only
  (`transfers.TransferCompleted`/`TransferFailed`, identifiers and enums, never an amount).
  The container-clock trap met again on schedule and fixed with the `GREATEST()` idiom
  (`P1-TSK-031`). **Verified by targeted tiers plus the full architecture tier** —
  `OwnershipIsScopedTest` demanded no entries (the store API takes aggregates, never bare
  identifiers, verified against the detector) — **the full battery deliberately skipped on
  the owner's instruction; no fleet-wide counts claimed.** Process note: the Gradle daemon
  was externally stopped mid-run twice ("stop command received"); both runs were repeated
  and the results read from fresh executions, never from the interrupted ones.
- **Accept**: met — the drain counted; the injected failure leaving nothing; replay of both
  outcomes; the in-lock proof by the performed moved-outside mutation.
- **Risk**: **High**. **Cx**: L. **DoD**: `DOD-FIN`, `DOD-DOMAIN`

**P4-TSK-006 — The `Beneficiary` aggregate and schema: `V003`** — `COMPLETE` (2026-09-18)
- **Scope**: `Beneficiary` (owning party id, display name, destination account reference,
  `ACTIVE → REMOVED`, terminal) and `transfers.beneficiary`: partial unique one-live per
  (party, destination account); removal a conditional `UPDATE` whose row count converges
  retries; the row survives `REMOVED` as evidence; grants column-narrowed to the removal
  columns; display name classified `RESTRICTED-PII`. The destination must exist and be a
  customer-owned product (validated through the resolution port); it is deliberately **not**
  re-validated on every later transfer — the transfer's own postability check owns that.
- **Deps**: `P4-TSK-001`.
- **Gate evidence (2026-09-18)**: **every acceptance clause demonstrated and counted in the
  table.** Ten concurrent creates of one destination (own connection each): exactly one
  created, nine converged **onto the winner's row id**, one row counted — the partial unique
  index arbitrating behind the savepoint converge (a pre-flight `SELECT` recorded as not a
  substitute). The freed slot demonstrated (`INV-LIFE-04`'s asymmetry, the
  `customer_account` kind): remove, save again, a **new** aggregate through the freed slot,
  two rows counted with the `REMOVED` one surviving as evidence. Raw SQL cannot resurrect,
  as the **migrator**: `V003`'s every-writer trigger (the ledger `V008` shape) refuses the
  resurrection and — the probe that isolates the frozen half — **an edit smuggled inside the
  legal removal edge**, since a status-preserving edit is refused by the edge check too and
  a probe two controls catch proves neither. Removal converges (retry `false`, the first
  instant untouched) and carries `party_id = ?` in the statement — a stranger's attempt and
  already-removed are one indistinguishable `false` (the one-404 shape prepared at the
  port). The coherence pair (`status ⇔ removed_at`) held at three ranks: constructor
  (rehydrate refuses corrupt rows), `CHECK`, and the aggregate's machine. Creation validated
  through `TransferParticipants.destination` — **existence only, deliberately**: postability
  goes stale by design and the transfer's own judgement owns it (plan §7's accepted race);
  a retry with a different display name **converges onto the existing name, recorded**:
  renaming is remove-and-recreate. `OwnershipIsScopedTest`'s predicate vocabulary gained
  **`party_id = ?`** (a beneficiary belongs to the Party — it outlives any one customer
  relationship, the consent precedent) and demanded the negative test by name before
  passing. `BeneficiaryMigrationTest` reconciles the status `CHECK`, the one-live predicate
  and the trigger edges from the enum; 7 columns classified at their ceiling
  (`display_name` `RESTRICTED-PII` — a person names people). **Eight mutations, all caught
  by the intended assertion, restores byte-identical** — the index dropped (the drain
  over-accepts, `Expected size: 1 but was: 10`), the trigger's edge check removed
  (resurrection accepted), the frozen check removed (**caught by exactly the smuggled-edge
  probe**), the coherence `CHECK` dropped, the ownership predicate dropped (**caught twice**:
  the stranger's removal returns `true`, and the build rule), the aggregate's machine check
  removed, the index made total (**caught twice**: the freed slot refused, and the hermetic
  reconciliation), the constructor coherence dropped. **Verified by targeted tiers —
  `:transfers:test` (27 hermetic), the beneficiary + classification database tests, and the
  ownership guard — the full battery deliberately skipped this task on the owner's
  instruction**; no fleet-wide tier counts are claimed.
- **Accept**: met — the ten-way create counted in the table; the freed slot re-creatable;
  raw SQL refused by the every-writer trigger.
- **Risk**: Low. **Cx**: M. **DoD**: `DOD-DOMAIN`
- **Out of scope**: external destinations (Phase 5+), beneficiary sharing, per-beneficiary
  limits (Phase 13).

**P4-TSK-007 — The beneficiary endpoints, and the step-up point** — `COMPLETE` (2026-09-18)
- **Scope**: `POST /v1/beneficiaries` — **`MULTI_FACTOR` required when a factor is
  enrolled**, the conditional-assurance domain check (`P1-TSK-033`'s pattern; a static
  annotation would lock out password-only customers), because creating a destination is
  where account-takeover monetises and a value threshold is a policy artefact nothing can
  calibrate (the `P3-TSK-021` argument, recorded in the plan §11) — plus
  `GET /v1/beneficiaries` and `DELETE /v1/beneficiaries/{id}` (ownership; one 404;
  converging 204). Audit: `transfers.BeneficiaryAdded` / `BeneficiaryRemoved`, actor the
  person. Contract diff reviewed; no body shape a 500.
- **Deps**: `P4-TSK-006`.
- **Gate evidence (2026-09-18)**: **every acceptance clause driven over real HTTP with the
  whole flow, nothing seeded where the flow could be driven.** The step-up: register →
  log in → enrol → confirm, then the enrolled identity on its `PASSWORD` session is refused
  **403 `identity.AssuranceRequired`** (actionable — step up and retry, `P1-TSK-018`'s
  earning test) with **nothing written** (zero rows counted for the party); the positive
  control proves the factor over `/v1/authentications/mfa` and creates at `MULTI_FACTOR` —
  audited as the **person** (`actor_id` the identity, target the beneficiary,
  `destination=` in the summary and the `RESTRICTED-PII` display name asserted absent). An
  unenrolled identity creates at `PASSWORD`; the retry — carrying a **different display
  name** — converges 201 onto the same row and the existing name (`P4-TSK-006`'s recorded
  consequence proven at the surface), one row, **one** `BeneficiaryAdded` record. A
  stranger's id, an unknown id and a malformed id are **one 404 asserted as an equality
  between the causes** (normalised only for the correlation id and the caller's own echoed
  `instance` path), the stranger's target untouched; the owner's `DELETE` converges 204
  with **one** `BeneficiaryRemoved` record. Unknown and malformed destinations are **one
  byte-identical 422 `transfers.UnknownDestination`** (the module's first error code —
  malformed-equals-absent for a third party's identifier) with nothing written. The list
  shows only the caller's live rows. Ten body shapes none our 500 — including the control
  character travelling as a **JSON escape** (a raw control byte is the parser's 400 and
  never reaches the rule under test), refused 422 naming `displayName` and never the value.
  The contract baseline: **135 added lines, zero removed**; the five `BREAKING` labels are
  the classifier erring safe on the brand-new path's own `required` members — the
  `P1-TSK-006` precedent, reviewed and accepted. Both audit actions catalogued and
  **emitted on arrival** — `NOT_YET_EMITTED` untouched, still holding exactly the three
  Phase-15 `outbox.*` actions. `OwnershipIsScopedTest` gained `findOwned` (`OWNER_SCOPED` —
  the read that tells own-already-removed from the one 404) and its negative test by name;
  `BeneficiaryCreateRequest` joined the credential-sink pinned set with its claim. **Eight
  mutations, all caught by the intended assertion, restores byte-identical** — the step-up
  check removed (`expected: 403 but was: 201`), the creation audit dropped, the converged
  retry audited too (`Expected size: 1 but was: 2`), the removal audit dropped, the 404
  fold broken (the stranger got 204), the destination validation dropped (201 for a
  destination that does not exist), the list's ownership predicate dropped (the bulk
  disclosure), the domain name-rule mapping removed (`expected: 422 but was: 500`). **One
  planned mutation cut on analysis and recorded**: the step-up refusal moved after the
  write is behaviourally invisible — the `ApiException` rolls the transaction back either
  way, so nothing-written is structural (the `P3-TSK-017` claim-before-validate shape).
  **Verified by targeted tiers — the full `:app:test` hermetic tier (registry,
  completeness, error-code, contract, ownership and endpoint-rule guards all green) plus
  the endpoint and store database suites — the full battery deliberately skipped on the
  owner's instruction**; no fleet-wide tier counts are claimed.
- **Accept**: met — the enrolled/`PASSWORD` refusal with nothing written and the
  `MULTI_FACTOR` success; the unenrolled `PASSWORD` create; the stranger's one 404; both
  actions emitted.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-API`, `DOD-SEC`

**P4-TSK-008 — `POST /v1/transfers`, status and list** — `COMPLETE` (2026-09-18)
- **Completion notes**: the events were already wired by `P4-TSK-005`, so the conditional
  clause demanded nothing. The accept's "a verified customer with two accounts" is
  unsatisfiable by design — `ProductType` has one value and one agreement per customer per
  product type is live (`P3-TSK-012`), so a second open converges onto the first — and the
  demonstration is two verified customers, which exercises the same three surfaces and
  more: both parties see the same entry identifier from their own side. One idempotency
  claim (the command's), no second HTTP layer; byte-for-byte holds structurally — the view
  renders the replayed judgement plus columns `V002`'s trigger freezes. New
  `transfers.UnknownSource` (422); the beneficiary arm folds unknown/stranger's/malformed/
  **removed** into one `transfers.UnknownDestination` (M4.3's fourth clause demonstrated
  with row counts).
- **Scope**: the transfer surface: `POST /v1/transfers` (session +
  `@RequiresIdempotencyKey`; body: source account id, exactly one of destination account id
  or beneficiary id, amount as a decimal string parsed exactly, currency, reference) →
  `201` with the transfer view **including status** — the asynchronous-outcome contract
  shape; `GET /v1/transfers/{id}` and `GET /v1/transfers` (ownership; not-yours/unknown/
  malformed one 404). A `FAILED` outcome is a `201` whose body says so, never an HTTP error
  (the command was accepted; the refusal is the domain's, queryable and replayable). The
  events (`transfers.TransferCompleted`/`TransferFailed`) get their first emitters here if
  not already wired by `P4-TSK-005`.
- **Deps**: `P4-TSK-005`, `P4-TSK-007`.
- **Gate evidence (2026-09-18)**: *(added by `P4-DOC-001`'s hand-diff — this item recorded
  **Completion notes** where every sibling records **Gate evidence**, and its mutation sweep
  was written only into `CURRENT_STATE.md`. The backlog is the record, so the sweep is
  restated here from it rather than left to a reader who compares two documents.)* **Eight
  mutations performed, all caught by the intended assertion, restores byte-identical** — the
  ownership predicate dropped (**caught twice**: behaviourally as `404 but was 200`, and by
  the build rule naming the method), the live-beneficiary filter dropped, the
  exactly-one-destination check dropped, the source fold split, the `@RequiresIdempotencyKey`
  requirement removed, the list's ownership predicate neutralised (the bulk disclosure), the
  list order inverted, and the amount rounded rather than refused — **which survived once**
  and strengthened the suite before being caught: the no-500 sweep's inexact shape named an
  *unknown destination* whose refusal masked the rounding, so the probe was re-aimed at an
  otherwise-valid pair (the `P2-TSK-016` masked-shape lesson). **One mutation cut on analysis
  and recorded**: the POST view's status-from-row swap is behaviourally invisible until
  `P4-TSK-009` gives the row a second writer. Contract baseline **+180/−0**, the six
  `BREAKING` labels the classifier erring safe on a brand-new path's own `required` members
  (reviewed). **Verified by targeted tiers — the full `:app:test` hermetic tier with every
  guard green, `:transfers:test`, and the transfer, execution, beneficiary and adjustment
  database suites — the full battery deliberately skipped on the owner's instruction; no
  fleet-wide counts claimed.**
- **Accept**: end to end over HTTP — a verified customer with two accounts moves money,
  both balance endpoints move, **both statements show the entry's lines**, and the chain
  transfer → entry id → statement line is walked by identifier; a retried key replays the
  original body byte-for-byte; a reused key with a different payload is the distinct 409
  (`INV-IDEM-03`); a keyless request is the interceptor's 422; no body shape a 500.
- **Risk**: Medium. **Cx**: L. **DoD**: `DOD-API`, `DOD-FIN`

**P4-TSK-009 — The reversal** — `COMPLETE` (2026-09-19)
- **Completion notes**: every accept clause demonstrated in `TransferReversalDatabaseTest`
  over real HTTP with the operator granted through the real `Authorization` write - the
  original entry and every line byte-identical as PostgreSQL's own renderings; both
  balances restored exactly over the balance endpoints; the trail naming the operator with
  the reason verbatim; ten concurrent reversals one entry and one move **counted in the
  tables** (one 201, nine 409s), plus the deterministic interleaving with the loser
  observed Lock-waiting on the transfer row's `FOR UPDATE` and refused with nothing
  posted; `FAILED` and already-`REVERSED` each the one `409 transfers.NotReversible`; the
  permissionless session (the transfer's own customer) refused 403 with nothing written,
  the operator's success the positive control. The arbiter is **lock-then-look on the
  transfer row** (the conditional's row count demoted to belt - under the held lock it
  cannot lose, the mutation cut on analysis and recorded), because the `UPDATE` needs the
  reversal entry id the posting has not yet minted, and post-then-move would surface the
  loser as a ledger-level over-reversal instead of the machine's 409. **No idempotency
  key, deliberately** (the `P3-TSK-021` approval precedent): the one-way machine is the
  idempotency, and a retry gets the 409 with the view carrying the reversal. The reversal
  posts **unconditionally** - no availability judgement on the destination, whose wallet
  legitimately goes negative (`P3-TSK-008`) - and a destination product closed since the
  transfer surfaces the catalogued `ledger.AccountNotPostable` with the whole transaction
  rolled back, the recorded corner. **The scope's "identity `V015`, the `V014` ceremony"
  was a drift, corrected on being met**: that ceremony replaces the ROLE constraint when a
  role is added, and this task adds a permission to an existing role - a permission is
  never a column (ADR-0031), so there is no migration to write and
  `RoleAssignmentMigrationTest` holds the constraint unchanged. **Eight mutations, all
  caught by the intended assertion, restores byte-identical**; verified by targeted tiers
  (the full `:app:test` hermetic tier with every guard green, `:transfers:test`,
  `:identity:test`, the transfers-package database suites) with **the full battery
  deliberately skipped on the owner's instruction; no fleet-wide counts claimed**.
- **Scope**: `TRANSFER_REVERSE` (identity `V015`, the `V014` ceremony — granted to
  `LEDGER_OPERATOR`: one money-operating population, a new role being a trust decision
  nothing here takes) and `POST /v1/transfers/{id}/reversal` (reason required, bounded in
  the three reconciled places): one transaction moving `COMPLETED → REVERSED` (the
  conditional's row count arbitrates) and posting the referencing reversal entry through
  `ReversalService` (`INV-REV-01/-02` — the full-amount reversal; partials are not a
  transfer-level concept). Audit `transfers.TransferReversed` naming the operator and the
  reason; event `transfers.TransferReversed`. Four-eyes deliberately not required
  (`INV-AUD-04` names adjustments; a reversal is bounded by the original — recorded in the
  plan §11 with the proposal-row seam named).
- **Deps**: `P4-TSK-008`.
- **Accept**: the original entry byte-identical after reversal (PostgreSQL's own
  renderings); both customers' balances restored exactly; ten concurrent reversals produce
  **one** reversal entry and one state move, counted; a reversal of a `REVERSED` or
  `FAILED` transfer is a 409 at the aggregate and has nothing to reverse at the ledger; a
  session without the permission is refused with nothing written and the operator's success
  as the positive control (`INV-AUD-03`).
- **Risk**: **High**. **Cx**: M. **DoD**: `DOD-FIN`, `DOD-SEC`

**P4-TSK-010 — The limit and risk seams** — `COMPLETE` (2026-09-19)
- **Completion notes**: the skeletons `P4-TSK-005` shipped were hardened into contracts
  Phase 13 can actually honour — the delta the skeleton left: both ports became generic
  over the unit of work (a `check(Transfer)` with no connection could never anchor a
  counter to durable state in-lock, so the in-lock contract was a sentence, not a seam)
  and **verdict-returning** (`SeamVerdict.PERMIT/REFUSE`, two values and no reason,
  because the refusal→reason mapping is the execution's, fixed per seam — a limit
  implementation can never commit the risk vocabulary). `FailureReason` gained
  `LIMIT_REFUSED`/`RISK_REFUSED` with their producers — the execution's mapping arms,
  exercised by a refusing test decorator: a seam refusal is a committed
  `FAILED` outcome, nothing posted, the retry replaying it (`CommandResult.failed`), so
  Phase 13's arrival changes no contract anywhere. `V004` widened the reason `CHECK`
  (the role-ceremony shape on a genuine column constraint), and
  `TransferMigrationTest`'s reason check moved to the **latest-definition derivation**
  with `V002`'s five-value literal pinned as history — the applied-history lesson its own
  javadoc predicted it would have to learn. **The in-lock contract is observed, not
  stated**: a decorator probe's second connection attempts `FOR UPDATE NOWAIT` on the
  source row and both seams see `55P03` — deterministic, and the only assertion that can
  see WHERE a permit-all seam ran. **Removing the `limits` parameter was demonstrated to
  fail compilation** at the composition root (`TransferBeans`: constructor cannot be
  applied) and restored byte-identical. `DISTRIBUTED_EXECUTION.md` §3 gained both rows
  (stateless by contract; the row says Phase 13's authority lives in durable rows on the
  execution's unit of work, never process memory — `INV-CON-03`). The no-Phase-13-logic
  clause is mechanised: `TransferSeamsTest` asserts the default holds no field, each port
  one method, the verdict exactly two values. **Seven mutations, all caught by the
  intended assertion, restores byte-identical** (seams hoisted above the lock — the
  NOWAIT probe; limit verdict ignored; risk verdict ignored; reasons swapped — both
  reason assertions; `V004` narrowed — the latest-definition reconciliation naming it;
  the default made refusing; a state field added — the size guard). Verified by targeted
  tiers (the full `:app:test` hermetic tier with every guard green, `:transfers:test`,
  the seam database suite) with **the full battery deliberately skipped on the owner's
  instruction; no fleet-wide counts claimed**.
- **Scope**: `TransferLimitCheck` and `TransferRiskDecision` — ports in `transfers`,
  **required parameters of the execution command with no defaulted overload** (the
  `PostingObserver` compiler-enforced precedent: Phase 13's wiring must be a decision, and a
  skipped control must not compile). Default implementations permit and are named for what
  they are (`PermitAllUntilPhase13`); the contracts state **in-lock evaluation** — the seam
  is consulted after the source lock is held, so Phase 13 inherits atomicity instead of
  discovering `INV-CON-03`'s race (the seam register's two Phase 4 rows in `ROADMAP.md`
  §Refinement 2, honoured). A refusal-shaped result exists in the vocabulary
  (`FAILED(LIMIT_REFUSED)` / `FAILED(RISK_REFUSED)` reasons reserved) so the Phase 13
  implementation changes no contract. `DISTRIBUTED_EXECUTION.md` §3 rows for both (stateless
  by contract; state is Phase 13's problem, and the row says where its authority must live).
- **Deps**: `P4-TSK-005`.
- **Accept**: removing either parameter fails compilation, demonstrated; the defaults are
  exercised on every transfer test; the in-lock contract is asserted (the seam observes the
  lock held — a decorator probe); no Phase 13 logic anywhere, verified by the seams' size.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-ARCH`
- **Out of scope**: any limit value, any velocity window, any risk rule — Phase 13.

**P4-TSK-011 — The meters and the dashboard row** — `COMPLETE` (2026-09-19)
- **Completion notes**: `PHASE_4_PLAN.md` §15 made real with no new mechanism — every
  question was answered by `P3-TSK-020`, applied to transfers without weakening. One
  `TransferMetrics` class in `app.telemetry` (the `AccountMetrics` shape), every series
  registered at construction (`P1-TSK-029`), wired eagerly in `TelemetryConfiguration`.
  **The counter anchors in `TransferService`, not a command decorator, because
  post-commit is not achievable inside the command** (the execution runs in the caller's
  transaction, ADR-0043): the outcome is captured across the transaction boundary (the
  `AccountService.openedNow` shape) and counted after the commit, from the
  `TransferResult`'s own vocabulary so the count cannot drift from the judgement.
  `replayed` lands with the original outcome's series unchanged; `reversed` counts the
  acting reversal only. **`refused` defined on the record** (the plan only listed it): a
  transfer command the platform declined to judge with nothing written — the resolution
  refusals and the reversal machine's 409 — never the caller's own 422s, which named no
  coherent command; a metric is invisible to the caller, so it may count what the
  byte-identical responses hide. **`conflict` is its own series, never an outcome value**:
  a security signal must be one series an alert can watch. The latency timer wraps the
  execution path in a `finally` — every outcome, the injected clock, no histogram
  buckets. Beneficiary counters take the acting-call discipline verbatim (converged
  creates and removals count nothing). The pinned Phase-4 guard joined
  `PlannedMetersExistTest` (the third of its shape; the derived guard takes over at the
  flip with no edit); the dashboard gained the *Transfers* row (outcomes, latency from
  `_count`/`_sum`/`_max`, conflicts on their own panel, beneficiary lifecycle), every
  query resolving against a live scrape. No `DISTRIBUTED_EXECUTION.md` §3 row — per-
  instance counters are non-authoritative readings (the `AccountMetrics` precedent); no
  `INV-MON-01` exemption owed (no gauge, so no `ToDoubleFunction`). **Eight mutations,
  all caught by the intended assertion, restores byte-identical** (replay counted as
  completed; refused dropped — caught twice; conflict dropped; reversed hoisted above
  the acting check — 1 became 2; eager registration made lazy — the pinned guard by
  name; a dashboard series renamed; a converged beneficiary create counted; latency made
  success-only); the count-inside-the-transaction mutation **cut on analysis and
  recorded** — after the judgement returns, nothing reachable rolls the transaction
  back, so the post-commit discipline is held by the code shape and the stated rule (the
  `P3-TSK-014` class). Verified by targeted tiers (the full `:app:test` hermetic tier
  with every guard green, `TransferMetersDatabaseTest`, `DashboardQueriesResolveTest`)
  with **the full battery deliberately skipped on the owner's instruction; no
  fleet-wide counts claimed**.
- **Scope**: `PHASE_4_PLAN.md` §15 real: `finapp.transfers.transfer{outcome}` (completed,
  failed, reversed, replayed, refused — post-commit, acting call only, the `P3-TSK-020`
  discipline), `finapp.transfers.transfer.latency` (the injected clock),
  `finapp.transfers.beneficiary{outcome}`, `finapp.transfers.conflict` (`INV-IDEM-03`
  conflicts — a security signal). All eager; the pinned Phase-4 guard in
  `PlannedMetersExistTest` until the flip; a *Transfers* dashboard row resolving against a
  live scrape. The plan's refused value-by-state meters and subjectless stuck-detector stay
  refused with their §15 provenance.
- **Deps**: `P4-TSK-008`, `P4-TSK-009`.
- **Accept**: a freshly started instance publishes every series; a replayed transfer lands
  `replayed` with `completed` unchanged; a conflict lands `conflict`; dashboard queries
  resolve; mutation sweep over the counting discipline.
- **Risk**: Low. **Cx**: M. **DoD**: `DOD-OBS`

**P4-TST-001 — Conservation under sustained concurrent movement** — `COMPLETE` (2026-09-19)
- **Completion notes**: the composition demonstration **found a real defect**, which is what
  this class of item exists for. Ten instances moving money both ways between one pair
  produced **783 deadlocks (`40P01`) against 203 domain outcomes**: the execution locked the
  **source** row `FOR UPDATE` while the posting's foreign key takes `FOR KEY SHARE` on the
  destination regardless (`P3-TSK-014`'s mechanism), so A→B and B→A held each other's row and
  waited for the other's — a cycle. **The one-directional drain (`P4-TSK-005`) could not
  reach it**, because a cycle needs two directions, and `TransferExecution`'s own javadoc had
  said for three tasks that *"the destination is deliberately never locked"* — true of the
  explicit lock and false about what happens. **Money was never at risk** (a deadlocked
  transaction writes nothing, so conservation held exactly through all 783), but
  `INV-CON-02` requires the loser to fail with a **domain outcome**, and an infrastructure
  abort is not one. **Remedied in scope, deliberately**: the fix is the idiom the sibling
  store method already names (`lockOwnedForUpdate` orders by id *"so two multi-account
  closers cannot deadlock"*, `P3-TSK-009`) applied to the multi-account operation that is a
  transfer — both participants locked in one fixed order — and the alternative was an
  `INV-CON-02` register row, **this item's own deliverable**, that could not be written
  honestly (the `P3-TST-003` unwritable-row shape, resolved the other way because here the
  mechanism is ten lines rather than a lifecycle). The order is Java's `UUID` order and
  deliberately not PostgreSQL's byte order: a deadlock-free protocol needs every *instance*
  to agree, not the database (`P3-TSK-008`'s recorded disagreement, harmless here). The storm
  went from **206s with 79% aborts to 4.5s with none**. **A surviving mutation then corrected
  the test**: the pre-lock availability derivation survived the first draft, because amounts
  of 1.00–3.00 against 10.00 never bring an account near zero and a stale read differs only
  at the boundary — with 7.00 and 9.00 in the rotation it is caught mid-storm at **−5.00 in
  round 2**. Conservation is asserted as **three readings that must reconcile**: the journal's
  own sum over the pair, an independent recomputation from `transfers.transfer` applied to the
  starting balances, and the outcome tally where every loser is a committed
  `FAILED(INSUFFICIENT_FUNDS)`. **Four mutations, all caught, restores byte-identical** — the
  ordering removed (495 deadlocks return); availability derived pre-lock (−5.00 mid-storm, and
  `expected: 3 but was: 10` on the drain); the posting over-moving (caught by the negative
  assertion, **recorded as the different assertion it is**) and under-moving (caught by the
  two-table reconciliation, `expected: 2 but was: 0` — which established which assertion is
  load-bearing rather than assuming). §5 teeth re-proven. **One process finding**: a compile
  failure was read as a test result from a stale XML — the harness now refuses to report
  unless `:app:databaseTest` actually ran. Verified by targeted tiers (the full `:app:test`
  hermetic tier with the register guard green, every transfers database suite, and the Phase 3
  contention suites the lock change could have disturbed) with **the full battery deliberately
  skipped on the owner's instruction; no fleet-wide counts claimed**.
- **Scope**: the phase's composition demonstration (the `P3-TST-001` posture): ten
  instances transferring A→B and B→A continuously — mixed amounts, some designed to lose —
  while the projection verification and trial-balance sweeps run; ended by the sweeps'
  floors, never by time. Plus the register row for `INV-CON-02` with its named mutation
  (the availability check moved outside the lock — recorded from `P4-TSK-005`'s sweep, or
  performed here if that sweep dropped rather than moved it: dropped and moved are
  different defects, the `P3-TST-002` lesson).
- **Deps**: `P4-TSK-008`.
- **Accept**: every mid-storm trial-balance sweep reads zero per currency; every
  verification verdict `CLEAN`/`IN_FLIGHT`, never `DRIFTING`; the final sum over both
  accounts equals the starting sum **exactly**, counted from the tables and independently
  recomputed; no source ever negative.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-TEST`, `DOD-FIN`

**P4-TST-002 — The `Phase: 4` register rows** — `COMPLETE` (2026-09-19)
- **Scope**: `MUTATION_TESTING.md` §2 rows for every invariant the catalogue marks
  `Phase: 4` — the set read from the catalogue at execution time, not from this sentence —
  including the **transfers-context `INV-IDEM-01` row** (the kernel and financial-boundary
  rows prove the mechanism, not this caller) and the `INV-LIFE-01/-02/-04` rows naming the
  cross-product sweep. Every named mutation performed by its owning task's sweep or by this
  item; §5 teeth re-proven; the item's §4 row.
- **Deps**: `P4-TSK-009`, `P4-TST-001`.
- **Gate evidence (2026-09-19)**: the set read token-exactly with the guard's own regex
  (`(?<![0-9])4(?![0-9])` against each `**Phase:**` line, so `13` cannot match and
  `4 onward` does) is **five** — `INV-IDEM-01` (transfers), `INV-CON-02` (landed by
  `P4-TST-001`) and `INV-LIFE-01/-02/-04` — and the four remaining rows landed, taking §2
  to 87 rows over 82 catalogued invariants. **All nine `MutationDemonstrationTest` checks
  green**; §5 teeth re-proven (one method reference corrupted, `every method the register
  names exists on its class` failed naming exactly
  `INV-LIFE-04 -> TransferTest has no bothTerminalsRefuseEveryTransitionX`, restored and
  verified byte-identical by comparison). **The flip is pre-paid, and the probe proves it
  rather than asserting it**: Phase 4's status line simulated `COMPLETE`, the guard green,
  and — the non-vacuity half — one new row then removed, which fails naming the invariant
  and reports *(currently 4)*, so the demanded set really did grow and the four rows are
  what makes the flip green. Both files restored byte-identical. **Two findings, and the
  first is the reason this item added a test.** (1) **The guard would never have demanded
  the `INV-IDEM-01` row** — it keys on the invariant identifier, which already carries the
  kernel and posting rows — so that row is owed by the gate's own extended list and by
  doctrine, and by nothing the build can say. (2) **The transfers caller had no
  concurrent-duplicate test at all**, while `INV-IDEM-01`'s Verify line reads *concurrent-
  duplicate integration tests* and this phase's criterion 2 reads *proven under concurrent
  submission from two threads*: what existed was the sequential retry, which exercises the
  **replay** path because by then the record is committed. Writing the row against it would
  have been the false row this register refuses, so the demonstration was **performed** (the
  `P3-TST-002` precedent) — `TransferExecutionDatabaseTest#tenConcurrentIdenticalKeysProduceOneTransfer`:
  ten instances on one key behind a `CyclicBarrier`, own connection and scopes each, one
  distinct transfer id across every judged result, exactly one executed, and **one effect
  counted in four tables** with the source at `7.00` and the destination at `3.00`. The
  losers' second legal outcome — the honest `IdempotencyInProgressException` — is accepted
  and every other exception fails the test, because demanding *nine replays* would make the
  assertion a statement about how fast the winner's transaction happens to be. **The
  mutation is concurrency-only, which is what makes the test load-bearing rather than a
  second copy of `P0-TST-004`**: a pre-flight read substituted for the unique constraint
  leaves **all five sequential tests green** and fails exactly the new one — and its
  observed shape is recorded rather than assumed, since the losers do not commit a second
  transfer but abort with `IdempotencyStorageException`, so a retry that should have
  replayed reaches the customer as a 500. One documentation finding recorded on the way:
  §6 item 4 claimed the class-existence check covers *either register* and it reads §2 only
  — corrected to what is actually checked, with why widening it needs a reference column §4
  does not have. **No production code changed**, no migration, no contract change; one §3
  paragraph records that `INV-LIFE-01` names seven operations and this phase delivers one,
  and that `transfers.beneficiary`'s machine is deliberately not claimed under any
  `INV-LIFE` row. **Verified by targeted tiers — `:app:test` 427 and `:transfers:test` 31,
  0 failures, plus the execution database suite (6 tests) green before the mutation, failing
  only the new test under it, and green again after the restore — the full battery
  deliberately skipped on the owner's instruction; no fleet-wide counts claimed.**
- **Accept**: met — all register-guard checks green over the new rows; the demanded set
  verified token-exactly against the catalogue (five, four landed here); every named class
  and method exists (the guard proves it, including the three `transfers`-module classes);
  every restore byte-identical by comparison.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-TEST`

**P4-DOC-001 — Phase 4 review record** — `COMPLETE` (2026-09-19)
- **Scope**: the `PHASE_GATES.md` §4 review: eight areas — area 2 walking a **transfer**
  end to end this time (economic event → transfer → entry → lines → both balances) — the
  twelve universal criteria, the financial supplement F1–F8 re-assessed at the gate, the
  Phase 4-specific criteria **as the gate lists them at review time** (the extended list;
  never a remembered count — the `P3-DOC-001` finding), the ADR-0043/0044 acceptance
  decision, every number counted. The `P2-DOC-001` order: assess → corrections → **flip
  (the guarded act)** → full battery → finalize.
- **Deps**: everything above.
- **Gate evidence (2026-09-19)**:
  [`reviews/PHASE_4_REVIEW.md`](reviews/PHASE_4_REVIEW.md) — **8 areas `PASS`, 12 universal
  criteria `PASS`, F1–F8 all `Met`, 16 phase-specific criteria `PASS`**, and the
  ten-instances question `PASS` over **six** contended decisions each with its arbiter and
  its counted race. **Verdict: Phase 4 `COMPLETE`.** Conducted in the `P2-DOC-001` order,
  and **the post-flip battery is green — 1157 hermetic tests, 0 failures across all ten
  modules** — including both guards the flip arms: `MutationDemonstrationTest`, now deriving
  five `Phase: 4` invariants from the catalogue, and `PlannedMetersExistTest`, whose derived
  rule now unions §15's meter table. **The flip surfaced nothing, pre-paid twice rather than
  lucky**: `P4-TST-002` landed every row and *probed* the flip (simulated `COMPLETE`, battery
  green, one row then removed to prove the demanded set had grown — *currently 4*), and
  `P4-TSK-011` landed the meters behind a pinned guard the derived one takes over with no
  edit. Second phase running that the gate machinery finished its work **before** the gate.
  **Two area-7 findings, both in the record rather than the code, both corrected here**:
  `DISTRIBUTED_EXECUTION.md` §3 had **no `transfers.beneficiary` row** — the register-decay
  class's **fifth** occurrence and the first *inside* a phase rather than at a boundary,
  sharper because `P4-TSK-009` added its own row precisely as the register's note asks while
  `P4-TSK-006`'s table never got one (row landed, with provenance); and **`P4-TSK-008`'s
  block recorded *Completion notes* where every sibling records *Gate evidence***, with its
  eight-mutation sweep written only into `CURRENT_STATE.md` (restated here, since the backlog
  is the record and a reader comparing two documents is not a mechanism). **The ADR index
  needed no repair, and that is a result**: the second-copy decay found by hand at three
  consecutive gates is now `P4-TSK-002`'s build failure, and this gate's acceptance flipped
  both copies with the guard reconciling them. **One criterion is met with a recorded
  deviation rather than waived**: criterion 7 asks for the full suite against real
  infrastructure, and the owner's standing instruction skips `build databaseTest kafkaTest` —
  so the **hermetic** tier (where the flip's own guards live) was run fleet-wide and the
  database and kafka tiers were verified per task throughout, with **no fleet-wide database
  or kafka count claimed for this phase**. Counted, never quoted: 1 module, 3 tables, 4
  migrations, 7 operations on 5 paths, 2 aggregates, 4 audit actions all emitted, 3 events,
  3 error codes, 1 permission on an existing role, 4 meters, 2 ADRs `Accepted`, **0 new
  invariants** (the catalogue stays at 82; 5 in scope, 5 register rows), 14 of 14 backlog
  items across 8 milestones, and **83 mutations — every one caught by the intended
  assertion, 4 cut on analysis, 2 survived mid-task and each improved a test, 0 survived
  wrongly**.
- **Accept**: met — the review's own verdict is what flipped the phase (the status was
  `IN_PROGRESS` until this record ruled); the post-flip battery green; area 2's walk names
  its code and its test at every step.
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-DOC`

---

# Phase 5 — Payment Infrastructure

Status: `IN_PROGRESS` (started 2026-09-20 with `P5-TSK-001`) — entry gate passed the same day by the Phase 4 → 5 transition
([`reviews/PHASE_4_TO_5_TRANSITION.md`](reviews/PHASE_4_TO_5_TRANSITION.md)), elaborated to
task granularity by the same transition. The engineering plan is
[`PHASE_5_PLAN.md`](PHASE_5_PLAN.md); decisions are ADR-0045…ADR-0049 (`Proposed`); the
domain statement is [`PAYMENT_LIFECYCLES.md`](../domain/PAYMENT_LIFECYCLES.md). The in-scope
invariants are whatever the catalogue marks `Phase: 5` — **eleven at planning time**
(`INV-HIST-02` providers, `INV-IDEM-01` payments, `INV-IDEM-04` webhooks, `INV-LIFE-03`,
`INV-REV-02` refunds, `INV-SET-01`, and the transition's new `INV-PAY-01`…`05`) — **read
from the catalogue at the gate, never from this file**. The financial supplement F1–F8
binds; every task that can affect money carries `DOD-FIN`.

**The original epic table is superseded by the elaboration below, with one epic corrected
on the record**: *provider adapter framework* is deliberately **not** a framework — the port
is one provider wide until Phase 7 (ADR-0049 §4), and building the abstraction against a
sample of one is the first-rail trap the question 9 record warns about.

**Milestones**: M5.1 Foundations (`P5-TSK-001`…`-003`) · M5.2 The instrument (`P5-TSK-004`,
`-005`) · M5.3 The intent and the attempt (`P5-TSK-006`…`-008`) · M5.4 Money arrives
(`P5-TSK-009`…`-011`) · M5.5 Webhooks (`P5-TSK-012`, `-013`) · M5.6 The unknown state
(`P5-TSK-014`, `P5-TST-001`) · M5.7 Refunds (`P5-TSK-015`, `-016`) · M5.8 Observability and
demonstration (`P5-TSK-017`, `P5-TST-002`, `P5-TST-003`) · M5.9 The gate (`P5-DOC-001`).
Acceptance per milestone in `PHASE_5_PLAN.md` §16.

**P5-TSK-001 — The `payments` and `paymentmethods` modules and schemas** — `COMPLETE` (2026-09-20)
- **Objective**: the module shape's fifth and sixth performances, and the phase's boundary
  decisions as build-graph facts. Bounded contexts 9 and 10.
- **Scope**: two guarded modules on the documented direction. `payments → ledger` declared
  (postings and holds are commanded, never written — `INV-LED-04`); `payments →
  paymentmethods` **refused** — the instrument resolves through a port `app` implements
  (`InstrumentResolution`, the `AccountHolderVerification` shape), so the PCI module is
  invisible to the module that talks to providers; every sibling isolation test gains both
  modules in both directions; a planted `ledger → payments` edge fails Gradle configuration
  as a cycle, demonstrated. `V001` in each schema: owner `finapp_migrator`, `REVOKE ALL
  FROM PUBLIC`, `USAGE` alone to `finapp_app`, no tables, no `ALTER DEFAULT PRIVILEGES`.
  Migrate → validate → re-migrate idempotent on a throwaway PostgreSQL, ACL checked
  exactly. No audit-action enums yet (the deliberately-few licence). A planted `double` in
  each module fails the floating-point rules naming it (the `P4-TSK-001` classpath lesson:
  both modules join `app`'s classpath with the edge that makes `ProductionModules` see
  them).
- **Deps**: none. **Out of scope**: every table, aggregate, bean and endpoint.
- **Accept**: build green with both modules; both floors proven live; both isolation
  asymmetries demonstrated; the planted probes caught.
- **Gate evidence (2026-09-20)**: hermetic fleet green with both modules — **1161 tests, 0
  failures**, the +4 the two isolation tests' own methods. **Six probes, all caught by the
  intended assertion, restores verified byte-identical by comparison**: a planted `double`
  in each module caught **naming that module** (`com.finapp.payments.Planted.amount is
  double (INV-MON-01)`, then the paymentmethods twin — run once per module deliberately,
  because one module's catch cannot vouch for the other's coverage; the first plant was
  written with a PowerShell BOM and refused by javac before it could prove anything — the
  a-mutation-must-compile rule, met again, re-planted clean); `payments → party` caught
  (*payments must not depend on party*, the party jar named in the failure);
  **`payments → paymentmethods` caught** (*payments must not depend on paymentmethods* —
  the load-bearing probe, since no Gradle cycle backs the PCI refusal and the test is the
  only control); `accounts → payments` caught from the other side; `ledger → payments`
  refused by **Gradle configuration outright** as a circular dependency. **The floor proven
  live** on a throwaway `postgres:18.6` with the real role script: migrate → validate →
  re-migrate idempotent for both schemas; owner `finapp_migrator`; ACL exactly
  `{finapp_migrator=UC, finapp_app=U}` with **no `PUBLIC` entry**; `USAGE` and **not**
  `CREATE` for the app role; **zero application tables** (each schema holding only Flyway's
  own history: the schema-creation marker plus one versioned row, both successful).
  Housekeeping: both `gradle.lockfile`s generated in the one-invocation regeneration and
  **identical to `transfers`'s but for the header line**; `gradle/verification-metadata.xml`
  unchanged (the modules add no artefact the build did not already trust); the `build-logic`
  Kotlin RC3→GA lockfile drift met and reverted a **fifth** time. Verified by targeted
  tiers — the fleet-wide hermetic `test` task and the two schema-floor checks above —
  **the full battery deliberately skipped on the owner's instruction; no fleet-wide
  database or kafka counts claimed.**
- **Risk**: Low. **Cx**: S. **DoD**: `DOD-BUILD`, `DOD-ARCH`

**P5-TSK-002 — The per-credential confinement, generalised** — `READY`
- **Objective**: pay the debt row that fired at `P2-TSK-011`: four hand-written copies of
  the loopback-confinement shape (`DatabaseCredentialGuard`, `MfaKey`, `DocumentKey`,
  `CallbackKey`) become one mechanism before the fifth and sixth credentials (the provider
  API key, the provider webhook key) arrive — the count at which copies drift.
- **Scope**: one platform mechanism (marked-local-default recognition, loopback
  confinement, domain separation) that each credential declares itself to; the four
  existing guards re-expressed over it with **behaviour proven unchanged** (their existing
  tests are the equivalence proof, untouched); the two payment credentials arrive in
  `P5-TSK-003`/`-012` as its first new consumers. The startup-guard test gains the new
  credentials as they land (the standing `P1-TSK-017` precedent).
- **Deps**: none. **Out of scope**: a secrets manager (Phase 15); key rotation.
- **Accept**: one definition of the confinement; all four existing credential guards green
  with no test edited; removing the confinement from the shared mechanism fails every
  consumer's guard test.
- **Risk**: Medium (touches four proven controls). **Cx**: M. **DoD**: `DOD-SEC`,
  `DOD-KERNEL`

**P5-TSK-003 — The provider port and the simulated card PSP adapter** — `TODO`
- **Objective**: ADR-0049 as code: `PaymentProvider` (authorize / capture / refund /
  **query by our reference**), our vocabulary in and out, one simulated card-style adapter.
- **Scope**: the port in `payments`; the adapter over HTTP against the `P0-TSK-037`
  harness, with a deployable simulated endpoint for the running instance (the Phase 2
  verification-provider shape). The **total state mapping** with its default branch
  indeterminate, never success (`INV-PAY-03`); every operation takes the platform-minted
  idempotency reference (`INV-PAY-04` — the port's contract requires it); every answer's
  raw bytes returned for evidence retention by the caller. Contract tests over every
  outbound harness mode: clear, refused, timeout, unavailable, 5xx, malformed, garbage,
  unknown state, slow-but-in-time, and **received-before-lost-response** (`requestCount`
  is the oracle). Misbehaviour is a result, never an exception (the `P2-TSK-009` totality
  rule). Provider API credential through `P5-TSK-002`'s mechanism, confined.
- **Deps**: `P5-TSK-001`, `P5-TSK-002`. **Out of scope**: a second provider; rail
  abstraction (Phase 7); webhooks (`P5-TSK-012`).
- **Accept**: every harness mode drives a defined port outcome; the unknown-state answer
  maps to indeterminate; a re-dispatched operation presents the same reference, asserted.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-KERNEL`, `DOD-SEC`

**P5-TSK-004 — The `PaymentMethod` aggregate and schema** — `TODO`
- **Objective**: the tokenised instrument — the PCI boundary's subject (`INV-PAY-02`).
- **Scope**: the aggregate (`ACTIVE → DETACHED`, terminal — the `Beneficiary` machine
  shape), token reference plus display metadata only; `V002` in `paymentmethods`: one-live
  partial index per (party, token) as concurrency arbiter and freed-slot rule,
  every-writer freeze trigger, grants `SELECT, INSERT` + the detach columns; the
  `information_schema`-derived sweep asserting instrument input appears in **no column**;
  ten concurrent attaches produce one live row, counted; raw SQL cannot resurrect a
  detached row. Column classification at the ceiling.
- **Deps**: `P5-TSK-001`. **Out of scope**: endpoints (`P5-TSK-005`); the tokenisation
  provider call (simulated token minting arrives with the attach surface).
- **Accept**: the race counted; the sweep green; the freeze proven as the migrator; the
  machine swept exhaustively.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-DOMAIN`, `DOD-SEC`
- Note: **not** `DOD-FIN` — an instrument reference moves no money.

**P5-TSK-005 — The payment-method endpoints and the step-up point** — `TODO`
- **Scope**: `POST /v1/me/payment-methods` (attach — step-up required exactly when a
  factor is enrolled, the `P4-TSK-007` conditional domain check verbatim; the simulated
  tokenisation exchange fails the attach when unavailable, **nothing raw ever stored**),
  `DELETE /v1/me/payment-methods/{id}` (converging, one 404 for stranger's/unknown/
  malformed), `GET /v1/me/payment-methods`. Audit actions attach/detach as the person,
  catalogued and emitted on arrival; `PaymentMethodAttached`/`Detached` events; contract
  baseline extended; request DTOs join the credential-sink pinned set.
- **Deps**: `P5-TSK-004`. **Accept**: the whole flow over real HTTP including the step-up
  refusal with nothing written; the outage attach fails clean; ownership one-404 asserted
  as an equality between causes.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-API`, `DOD-SEC`

**P5-TSK-006 — The `PaymentIntent` aggregate and machine** — `TODO`
- **Scope**: ADR-0045's five-state machine (`REQUIRES_CONFIRMATION → {PROCESSING,
  CANCELLED}`, `PROCESSING → {SUCCEEDED, FAILED}`) on the enum with
  `sqlValueList()`/`sqlTerminalValueList()`; one constructor holding the coherence;
  `rehydrate` refusing corrupt rows; the exhaustive cross-product sweep derived from
  `permittedTransitions()`; `SUCCEEDED`'s **no outgoing edge** pinned as a machine
  property; typed ledger-account reference for the wallet, raw UUIDs where module
  boundaries forbid the typed ids (the `Transfer` precedent). Hermetic only.
- **Deps**: `P5-TSK-001`. **Accept**: every invalid transition rejected; both terminals
  and the stable state swept; `INV-AUD-02` needle-asserted on refusal messages.
- **Risk**: Low. **Cx**: M. **DoD**: `DOD-DOMAIN`, `DOD-FIN`

**P5-TSK-007 — The `PaymentAttempt` and `Refund` aggregates and machines** — `TODO`
- **Scope**: the seven-state attempt machine and four-state refund machine (ADR-0045,
  `PAYMENT_LIFECYCLES.md` §3–§4), same ceremony: per-outcome transition doors through one
  machine check, coherence both directions (mapped reason ⇔ `FAILED`; captured amount ⇔
  `CAPTURED`; provider references' presence rules), `*_UNKNOWN` resolution edges,
  `INV-PAY-05`'s domain half (capture ≤ authorized; refund sum ≤ captured judged at the
  aggregate). Hermetic only.
- **Deps**: `P5-TSK-006`. **Accept**: exhaustive sweeps over both machines; the
  deliberately-absent states asserted absent (no state without a producer); coherence
  refused on rehydrate.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-DOMAIN`, `DOD-FIN`

**P5-TSK-008 — The payments schema: intent, attempt, refund, evidence** — `TODO`
- **Scope**: `V002`+ in `payments`: `payment_intent`, `payment_attempt`, `refund`, their
  history tables, and `provider_evidence` — the machines' `CHECK`s and every-writer
  transition triggers generated from the enums and reconciled by migration tests;
  `MoneyColumns` verbatim; the one-live-attempt-per-intent partial index; per-operation
  idempotency references `NOT NULL`-before-dispatch and `UNIQUE` (`INV-PAY-04`'s
  representable half); provider references unique per provider when present; the refund
  **in-trigger sum bound** against the captured amount (`INV-PAY-05` at `DB-CONSTRAINT`,
  the `V009` pattern, advisory-lock namespace registered); evidence append-only at the
  privilege, checksummed, encrypted under `P5-TSK-002`'s mechanism (`INV-HIST-02`);
  grants per table, swept per column with positive controls; classification at the
  ceiling. Raw-SQL refusals from scratch.
- **Deps**: `P5-TSK-007`, `P5-TSK-002`. **Accept**: every constraint exercised against raw
  SQL; the reconciliations hold; the bound refuses an over-refund for every writer.
- **Risk**: High (the phase's schema). **Cx**: L. **DoD**: `DOD-FIN`, `DOD-KERNEL`

**P5-TSK-009 — The authorization command: dispatch-before-call** — `TODO`
- **Objective**: ADR-0046 as code, on the money path for the first time.
- **Scope**: intent create (keyed `payment.create`, fingerprint binding actor + wallet +
  instrument + amount + currency + scale) and confirm: one transaction commits
  `PROCESSING`, the attempt at `AUTH_DISPATCHED` with its minted provider reference, the
  audit record and `PaymentIntentCreated`/dispatch bookkeeping — **then** the provider
  call holding no connection (asserted structurally, the `P1-TSK-026` discipline) — then
  the outcome transaction: the conditional transition to
  `AUTHORIZED`/`FAILED`/`AUTH_UNKNOWN`, verbatim evidence, audit, outbox. Cancel from
  `REQUIRES_CONFIRMATION` only. Connection-refused-before-send is
  `FAILED(PROVIDER_UNAVAILABLE)`; anything after send is `AUTH_UNKNOWN`. **No ledger
  effect anywhere** (ADR-0048).
- **Deps**: `P5-TSK-003`, `P5-TSK-008`. **Accept**: the two-transaction shape proven by a
  crash injected between them (stranded `AUTH_DISPATCHED`, visible, nothing else); every
  harness outcome drives its committed state; a retried confirm converges; ten instances
  confirming one intent produce one attempt, counted.
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`, `DOD-API`
- Note: named mutation for the register — the dispatch commit moved **after** the provider
  call (the discipline inverted): the crash probe and the stranded-state assertions catch
  it.

**P5-TSK-010 — The capture command: the ledger's first touch** — `TODO`
- **Scope**: `AUTHORIZED → CAPTURE_DISPATCHED` commit → provider call → outcome
  transaction committing `CAPTURED` **with the posting** — debit `PSP_CLEARING`, credit
  the customer wallet, key `payment-capture:<attemptId>` through `PostingService` on the
  same connection — and the intent's `PROCESSING → SUCCEEDED`, atomically (ADR-0048).
  Ambiguity commits `CAPTURE_UNKNOWN` with **nothing posted**. `INV-SET-01` recorded on
  the row and in the docs: captured is not settled; nothing moves clearing onward.
  Events: `PaymentAuthorized` (from `P5-TSK-009`'s outcome), `PaymentCaptured`,
  `PaymentFailed`, `PaymentStateUnknown` — terminal-and-durable facts through the outbox.
- **Deps**: `P5-TSK-009`. **Accept**: exactly one entry per attempt under a ten-way
  duplicate-outcome race, counted in the journal; a rolled-back outcome leaves no posting
  and no transition; the wallet balance moves and is explainable (`INV-BAL-02` extends
  with no new mechanism); an injected posting failure fails the whole outcome transaction
  loudly.
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`
- Note: named mutation — the posting hoisted **out** of the outcome transaction: the
  crash-between probe catches a `CAPTURED` state beside no entry.

**P5-TSK-011 — The payment surface over HTTP** — `TODO`
- **Scope**: `POST /v1/payments`, `POST /v1/payments/{id}/confirmation`,
  `DELETE /v1/payments/{id}`, `GET /v1/payments/{id}`, `GET /v1/payments` — session +
  ownership (the `/v1/me`-derived chain; one 404 across stranger's/unknown/malformed),
  `@RequiresIdempotencyKey` on create and the byte-for-byte replay discipline, the
  asynchronous-outcome contract shape (the answer may honestly be `PROCESSING`), payment
  error codes catalogued (**no provider vocabulary** — `INV-PAY-03` at the contract,
  boundary-tested), no body shape a 500, contract baseline extended and reviewed.
  Confirmation/cancellation audited as the person.
- **Deps**: `P5-TSK-010`. **Accept**: the acceptance chain over real HTTP — attach,
  create, confirm, the simulated provider authorises and captures, the wallet balance
  moves, the statement shows the entry, the chain walked by identifier; a retried create
  replays byte-for-byte; a changed request is the distinct 409.
- **Risk**: Medium. **Cx**: L. **DoD**: `DOD-API`, `DOD-FIN`

**P5-TSK-012 — Webhook ingestion: authenticated, evidence-first, deduplicated** — `TODO`
- **Scope**: ADR-0047's door: `POST /v1/providers/payments/webhooks` — HMAC over
  timestamp + raw bytes per provider key (through `P5-TSK-002`, confined), constant-time,
  **before parsing**; freshness window refusing stale messages; verbatim evidence row +
  inbox dedupe on (provider, event id) committed together before any state effect;
   2xx-after-commit acknowledgment; authentic-but-unmappable acknowledged with evidence
  and meter (the anti-stall decision, recorded); unauthenticated/stale writes **nothing**
  (`INV-PAY-01`). The `SIGNED_CALLBACK` ownership class covers the reads.
- **Deps**: `P5-TSK-008`, `P5-TSK-003`. **Accept**: negative tests per cause, each with
  nothing written; a triple delivery lands one dedupe record and three evidence
  decisions per ADR-0047 §2; signature proven against published vectors.
- **Risk**: High (the forgery surface). **Cx**: M. **DoD**: `DOD-SEC`, `DOD-EVENT`
- Note: named mutation — verification moved after parsing; the raw-bytes discipline is the
  `P2-TSK-011` precedent and its mutation.

**P5-TSK-013 — Webhook-driven transitions: idempotent, order-blind** — `TODO`
- **Scope**: authenticated webhooks mapped through the total state mapping onto the
  conditional machine edges; duplicate-with-fresh-id, out-of-order (capture report before
  auth report), before-the-sync-response, racing-the-sweeper and
  late-on-a-terminal-attempt each driven to **exactly one effect counted in the tables**,
  the losers retained as evidence (`INV-IDEM-04`, `INV-LIFE-04`); a webhook resolving an
  `*_UNKNOWN` applies the same outcome transaction as the sweeper (one code path — the
  `CheckOutcomeTrail` extraction rule if a second copy threatens).
- **Deps**: `P5-TSK-012`, `P5-TSK-010`. **Accept**: each ordering scenario counted; the
  webhook-resolved capture posts exactly once (the claim proven under the race).
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`, `DOD-EVENT`

**P5-TSK-014 — The reconciliation-by-query sweeper** — `TODO`
- **Scope**: ADR-0046 §4: every instance polls for `*_DISPATCHED`/`*_UNKNOWN` rows past
  their bounds (server-clock judged — the ADR-0014 discipline), queries the provider by
  our reference, applies outcomes through the standard outcome transactions. **No lease,
  no leader, by design** — the conditional transition arbitrates and queries are
  idempotent; the schedule property-gated, disabled in the suites that need stillness,
  registered in `DISTRIBUTED_EXECUTION.md` §3 with the relay's justification shape.
  Bounds configuration explicit; a swept resolution is audited as the platform (an
  enumerated `enterSystem()` site with its justification).
- **Deps**: `P5-TSK-010`. **Accept**: a stranded `AUTH_DISPATCHED` (crash mid-call) and an
  aged `CAPTURE_UNKNOWN` each resolve; **concurrent sweepers race to one winner counted**;
  a sweeper racing the webhook produces one effect.
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`, `DOD-KERNEL`

**P5-TST-001 — The ambiguity demonstration** — `TODO`
- **Scope**: the phase's reason for existing, driven whole: the provider **succeeds while
  the response is lost** (the harness's received-before-lost mode) → `*_UNKNOWN`
  committed → the sweeper resolves → **exactly one financial effect**, counted in the
  journal and the payment tables; the same for timeout-then-success at authorization and
  at capture; provider-succeeds-after-we-resolved-failure lands as refused-edge evidence
  with an alert meter. The named mutations: timeout mapped to `FAILED` (the
  most-expensive-mistake shape) — caught by the demonstration; the sweeper's transition
  made unconditional — caught by the race.
- **Deps**: `P5-TSK-014`, `P5-TSK-013`. **Accept**: every scenario's effect counted, never
  inferred; the register rows for `INV-LIFE-03` land here with the demonstrations
  performed.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-TEST`, `DOD-FIN`

**P5-TSK-015 — The refund command: hold, then post** — `TODO`
- **Scope**: `POST /v1/payments/{id}/refund` behind `PAYMENT_REFUND` (joins
  `LEDGER_OPERATOR` — one money-operating population; a permission is never a column),
  reason required (`INV-AUD-03`, the reversal precedent); dispatch transaction: the bound
  judged under lock-then-look on the attempt row, the **Phase 3 hold placed on the wallet
  inside the account lock** (ADR-0048 §4 — `P3-TSK-015`'s owed capture composition),
  `DISPATCHED` + provider reference committed; outcome transaction: completion
  releases-and-posts (debit wallet, credit clearing, key `payment-refund:<refundId>`)
  atomically, failure releases with nothing posted, ambiguity commits `UNKNOWN` with the
  hold standing. Partial refunds; concurrent partials bounded at both ranks
  (`INV-PAY-05`, `INV-REV-02`).
- **Deps**: `P5-TSK-010`, `P5-TSK-008`. **Accept**: ten concurrent partials accept exactly
  the bounded set, counted; the held funds are unspendable mid-flight (driven); the
  permissionless session refused with nothing written; the sum bound refuses raw SQL.
- **Risk**: High. **Cx**: L. **DoD**: `DOD-FIN`, `DOD-SEC`

**P5-TSK-016 — The refund surface and events** — `TODO`
- **Scope**: the refund view on the payment surface (refund totals derived from the rows —
  the intent has no refund state, ADR-0045), `RefundInitiated`/`RefundCompleted`/
  `RefundFailed` through the outbox, audit naming the operator with the reason, the
  refund's no-500 sweep, contract baseline. The webhook-completed refund proven (the
  provider reports completion asynchronously).
- **Deps**: `P5-TSK-015`, `P5-TSK-013`. **Accept**: the acceptance chain over HTTP;
  the derived totals reconcile with the rows; a replayed refund key replays byte-for-byte.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-API`, `DOD-EVENT`

**P5-TSK-017 — The meters and the dashboard row** — `TODO`
- **Scope**: `PHASE_5_PLAN.md` §15 real: the six meters, eager from a plain context
  (pinned Phase-5 guard until the flip — the established shape), counted from judgements'
  own vocabulary post-commit (replays/converges never throughput); `provider` and
  `operation` join `ALLOWED_TAG_KEYS` as bounded compile-time sets, the decision recorded;
  `unknown.active`/`unknown.age` as database gauges, NaN never zero, `max()` fleet-wide;
  the *Payments* dashboard row resolving against a live scrape.
- **Deps**: `P5-TSK-011`, `P5-TSK-014`. **Accept**: a fresh instance publishes every
  series; the mutation sweep over the counting discipline; queries resolve live.
- **Risk**: Low. **Cx**: M. **DoD**: `DOD-OBS`

**P5-TST-002 — The `Phase: 5` register rows** — `TODO`
- **Scope**: every invariant the catalogue marks `Phase: 5` — **token-parsed with the
  guard's own regex, eleven at planning time** — carries a `MUTATION_TESTING.md` §2 row
  with its demonstration performed or honestly recorded from the owning task's sweep (the
  `P3-TST-003`/`P4-TST-002` posture: the audit finds what was not done and does it); the
  flip probed — Phase 5 simulated `COMPLETE`, battery green, one row removed to prove the
  demanded set grew — and restored byte-identical; §5 teeth re-proven.
- **Deps**: `P5-TST-001` and the owning tasks. **Accept**: all guard checks green over the
  new rows; the probe's failure names the invariant.
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-TEST`
- Note: the guard keys on invariant **identifiers**, so the payments-context rows for
  `INV-IDEM-01`/`INV-IDEM-04`/`INV-HIST-02`/`INV-REV-02` are owed by doctrine and the gate
  list, not by the build — the `P4-TST-002` finding, pre-applied.

**P5-TST-003 — Conservation under concurrent captures and refunds** — `TODO`
- **Scope**: the composition storm (the `P3-TST-001`/`P4-TST-001` posture): ten instances
  authorising, capturing and partially refunding continuously while the trial-balance and
  projection sweeps run, ended by the sweeper's floors; every sweep zero per currency;
  every verdict `CLEAN`/`IN_FLIGHT`; the clearing and wallet positions reconciling
  **exactly** to captured − refunded, counted from the tables and independently
  recomputed; no wallet negative through the refund holds.
- **Deps**: `P5-TSK-015`. **Accept**: the three readings reconcile; the storm's amounts
  contest the availability boundary (the `P4-TST-001` lesson, pre-applied).
- **Risk**: Medium. **Cx**: M. **DoD**: `DOD-TEST`, `DOD-FIN`

**P5-DOC-001 — Phase 5 review record** — `TODO`
- **Scope**: the exit review per `PHASE_GATES.md` §4 and §5 Phase 5 (original bullets plus
  the transition's extension, **read from the gate at review time**), the F1–F8 supplement
  re-assessed, the ten-instances question answered over the phase's contended decisions,
  conducted in the assess → corrections → **flip** → battery order with the review's own
  verdict flipping the phase.
- **Deps**: everything above. **Accept**: the review's verdict is what flips the status;
  the post-flip battery green. **Risk**: Low. **Cx**: M. **DoD**: `DOD-DOC`

---

# Phases 6–16 — Epics

Status: `PLANNED` — capabilities elaborated at each phase's entry gate.

| Phase | Epics |
|-------|-------|
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
