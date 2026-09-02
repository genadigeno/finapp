# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

Last updated: 2026-09-02

---

## Current Phase

**Phase 0 — Domain and Architecture Foundation**
Status: `IN_PROGRESS`

Entry gate passed on 2026-08-31. All twelve entry-gate criteria in
[`PHASE_GATES.md`](PHASE_GATES.md) §2 are satisfied: the delivery plan is written, bounded
contexts and module boundaries are defined, the invariant catalog exists, the backlog is
elaborated to task granularity, and ADR-0001 through ADR-0012 are recorded as `Proposed`.

Phase 0 delivers a buildable, boundary-enforced modular monolith containing the financial and
platform kernel, with **zero business capability**. That constraint is deliberate: money
representation, idempotency, outbox, audit and correlation cannot be retrofitted once
financial history exists.

## Current Milestone

**M0.4 — API, observability and security baseline**
`P0-EPIC-08` (API Conventions and Error Contract) is **COMPLETE** (2026-09-02): the platform has a
versioned HTTP surface, an RFC 9457 error contract on every path, validation and correlation at the
boundary, a published OpenAPI contract compared on every build, operational endpoints, and one
conventions document holding it together. `P0-EPIC-09` (Observability Baseline) and `P0-EPIC-10`
(Security Baseline) remain.

**M0.3 — Correctness primitives** — `P0-EPIC-05`, `P0-EPIC-06` and `P0-EPIC-07`, all `COMPLETE`
(2026-09-01), with one exception recorded rather than hidden: `P0-TSK-017` (`Idempotency-Key`
header) is `BLOCKED` on the HTTP surface `P0-EPIC-08` brings in M0.4, and moves with it.

Every claim the milestone was for is now enforced and proven: money-moving commands are idempotent
under genuine concurrency; domain facts and their publication records commit together via an
outbox whose relay is safe across N instances; consumers deduplicate through an inbox; and
privileged actions produce append-only audit records the application role cannot edit — enforced
at the database privilege level, not in code.

**M0.2 — Financial kernel** — `P0-EPIC-03` and `P0-EPIC-04`, both `COMPLETE` (2026-09-01).

**M0.1 — Buildable, boundary-enforced skeleton** — `P0-EPIC-01` and `P0-EPIC-02`, both
`COMPLETE`. Three of its four completion criteria are met; the fourth, "green **in CI** from a
clean clone", cannot be met while the repository has no git remote. A clean clone was verified
to reach a green build locally during `P0-DOC-001`.

Subsequent Phase 0 milestones:
- **M0.4** API, observability, security baseline — `P0-EPIC-08`, `-09`, `-10`
- **M0.5** Test infrastructure and phase review — `P0-EPIC-11`, `P0-EPIC-12`

## Current Task

**`P0-TSK-029` - Metrics and dashboards**
Status: `READY` - not started.

Bounded context: platform. Depends on `P0-TSK-003` (`COMPLETE`).

Full definition: [`BACKLOG.md`](BACKLOG.md) §P0-EPIC-09. DoD profile: `DOD-OBS`.

### Just completed

**`P0-TSK-028` - OpenTelemetry tracing** - `COMPLETE` (2026-09-02).

**The acceptance criterion could not be met as written, and was corrected rather than approximated.**
It named four legs - HTTP, DB, outbox, consumer - and two of them have no subject: there is no
broker adapter (`EventPublisher` still has no implementation, which is recorded debt owned by
Phase 3) and no consumer wiring, so no request can reach either. Exactly the correction
`P0-TSK-014` needed. The outbox and consumer legs transfer to the broker adapter; HTTP and DB were
delivered and proven against a live PostgreSQL.

| Acceptance criterion (corrected) | Evidence |
|---|---|
| One connected trace spanning HTTP and the database | `TraceAcrossDatabaseTest`: one trace id across the request and the connection span, and the database span is a **child** of the request rather than a root of its own - a flat list of spans sharing a trace cannot answer "what was this connection acquired for?" |
| The correlation identifier on every span | Asserted over every recorded span, hermetically and against a real database |

Design decisions worth carrying forward (ADR-0017):
- **A trace identifier never substitutes for a correlation identifier.** A trace id is subject to
  sampling; a sampled-out flow would become unfindable from the one value a customer holds, and it
  is absent from every table. Correlation goes **on** spans instead - `finapp.correlation_id`.
- **Stamped once by a span processor, not by each component.** "Every span carries correlation" is
  not a property per-component discipline delivers: a component added next year, or an
  instrumentation library nobody wrote, would each have to remember, and forgetting is silent - the
  span is recorded, the trace looks complete, and it cannot be found. Applying it where the SDK
  starts spans makes it hold for spans this codebase never writes.
- **No JDBC tracing library, and no statement text on spans.** Every option records SQL as an
  attribute, which on this platform's tables means amounts and account identifiers flowing into a
  backend with different retention and access control (`INV-AUD-02`) - arriving silently the first
  time somebody writes a query. What remains is `finapp.db.connection`, which is the signal already
  identified twice: it turns "requests are slow" into "requests are waiting for a connection".
- **Telemetry is never the record**, the same relationship `INV-EVT-02` sets out for Kafka.
- **Nothing about where traces go is committed to source.** An endpoint in git is either wrong
  everywhere or a hostname nobody meant to publish.

**Three defects found by running it, none of which any test would have reported:**
- **Spring Boot 4 gates the OpenTelemetry SDK behind `management.opentelemetry.enabled`, off by
  default.** Without it there is no `SdkTracerProvider`, the `Tracer` is Micrometer's NOOP, and
  every span is recorded into nothing. Nothing fails; there are simply no traces.
- **The auto-configuration module and the bridge are both required.**
  `spring-boot-micrometer-tracing-opentelemetry` is `@ConditionalOnClass` on the bridge, so with
  only one of the two it backs off entirely - the same silent nothing. Found by disassembling the
  auto-configuration rather than guessing.
- **A second `management:` key in `application.yaml`** - YAML rejects duplicate keys, so every
  Spring context in the suite refused to start.

And one of my own: the data-source wrapper's javadoc said the tracer was **resolved lazily** while
the code resolved it eagerly in a bean post-processor, which runs before the tracing beans exist.
Comment and code disagreeing, for the fourth time this session.

**The correlation sink guard fired for the sixth time**, and this time on the sink it was built
for: `P0-TSK-014`'s criterion named a trace and could not verify it because there was no tracing.
That clause is now closed.

---

## Completed Capabilities

**Business capabilities: none** — by design (Phase 0 §2 of the delivery plan).

Platform foundation (2026-08-31), `P0-TSK-001`:
- Gradle 9.7.1 wrapper; distribution **and** wrapper jar verified by SHA-256 against
  `services.gradle.org`
- Version catalog as the single source of dependency versions
- Java 21 toolchain pinned via a `build-logic` convention plugin, with the foojay resolver
  provisioning a JDK where the machine lacks one
- Spring Boot 4.1.1 BOM; the Boot plugin applied only to the executable module, so library
  modules can take version alignment without `bootJar` packaging
- Placeholder `app` module carrying no business capability
- Reproducible archives; `-Werror`; LF enforced on `gradlew` so Linux CI is not broken by a
  Windows checkout

Module skeleton (2026-08-31), `P0-TSK-002`:
- `sharedkernel`, `platform` and `app` with the direction `app -> platform -> sharedkernel`,
  enforced structurally by Gradle and asserted by classpath tests
- `sharedkernel` is framework-free and provably carries no Spring artefact
- `java-library` everywhere, making `api`/`implementation` a deliberate boundary control
- No production code in either new module beyond boundary documentation

Local infrastructure (2026-08-31), `P0-TSK-003`:
- `compose.yaml` with PostgreSQL 18.6, Kafka 4.3.1 (KRaft) and Redis 8.10.1, pinned,
  health-checked, on named volumes, bound to loopback only
- Image versions single-sourced in the version catalog, with a build task that fails on
  drift between `compose.yaml` and the catalog

Migration tooling (2026-08-31), `P0-TSK-005`:
- Flyway 12.4.0, pinned to the Spring Boot BOM version, applied to the `platform` module
- Forward-only migrations with module-owned schema history (ADR-0011)
- `V001` creates the `platform` schema, documents its ownership, and revokes `PUBLIC`
- Migration conventions documented, including irreversible financial migrations and the
  privilege model the DB-level invariants require

Continuous integration (2026-08-31), `P0-TSK-004`:
- Four gates on every change: build/tests/boundary checks, migrations against a real
  PostgreSQL, secret scan over full git history, dependency scan of a CycloneDX SBOM
- Every third-party action pinned to a commit SHA; both scanners pinned to image digests
- The Java version CI installs is read from the version catalog rather than duplicated
- The `migrations` job starts PostgreSQL from the project's own `compose.yaml`, so CI and a
  developer run the identical pinned image

Architecture baseline (2026-08-31), `P0-TSK-006`:
- Context-to-module map: all 28 bounded contexts mapped to 24 modules, merges justified with
  recorded split triggers (ADR-0012)
- Single ownership verified by script over the register, not by reading it
- Module register with all nine `CLAUDE.md` boundary attributes for every module
- Authoritative-state ownership table proving no state has two owners

Boundary enforcement (2026-08-31), `P0-TSK-007`:
- Six ArchUnit rules on every build: dependency direction (defence in depth over Gradle),
  framework leakage into `sharedkernel`, cross-module internal access, cross-module entity
  references
- Each proven by a deliberate violation, then reverted
- A guard test asserting the analysis actually sees production classes, so the suite cannot
  become silently vacuous

Monetary type enforcement (2026-09-01), `P0-TSK-008`:
- `INV-MON-01` enforced statically on every build: no `float`, `double`, `Float` or `Double` in
  any field, signature, call target or field access in production code
- Scoped default-deny over every class rather than by a list of financial packages, with an
  empty, named exemption set
- Each rule proven to reject its violation and accept clean code on every build, and the whole
  sweep proven end to end by a `double` planted in `Money`, a `float` in a signature, and a
  `Double.parseDouble` call — each in a different module
- Coverage derived from the classpath by a shared `ProductionModules` helper, so a module that
  stops being analysed fails the build rather than silently losing its protection

Developer documentation (2026-09-01), `P0-DOC-001`:
- `README.md`: prerequisites, build, test, infrastructure lifecycle, migrations, CI gates and
  the failures this stack actually produces
- Every command verified from a clean clone, with infrastructure stopped first so the hermetic
  build claim was tested rather than assumed
- States the CI limitation rather than implying green

Architecture documentation (2026-09-01), `P0-DOC-002`:
- `MODULE_ARCHITECTURE.md` §6 names the rule behind every claim of mechanical enforcement
- `ArchitectureRulesAreDocumentedTest` fails the build when the document and the enforced rule
  set stop agreeing, in either direction
- The document is a declared input of `:app:test`, so a doc-only edit re-runs the check

Financial kernel (2026-08-31), `P0-TSK-009`:
- `Money`: integer minor units, explicit `CurrencyCode`, stored scale (ADR-0003)
- Exact arithmetic only — cross-currency, cross-scale and overflow all rejected with distinct
  domain exceptions under one `MonetaryException` supertype
- `CurrencyCode` validates against ISO 4217 and rejects codes with no minor unit
- No floating point anywhere on the monetary path

Correlation propagation (2026-09-01), `P0-TST-003`:
- One request's identifier proven identical in the log and in a committed database row, across
  a thread handoff, for both an accepted and a generated identifier
- A negative control asserting an unwrapped handoff loses it, so the test cannot pass by accident
- `CorrelationSinkCoverageTest` fails the build when a new platform concern appears without a
  decision about whether correlation must reach it

Distributed tracing (2026-09-02), `P0-TSK-028`:
- Every span carries `finapp.correlation_id`, stamped once by a span processor in the composition
  root, so the property holds for spans this codebase does not produce
- A trace identifier is explicitly not a substitute: sampling would make a flow unfindable from
  the value a customer quotes
- Inbound W3C `traceparent` is joined rather than replaced, with a negative control proving two
  unrelated requests remain two traces
- HTTP and database in one connected trace against a real PostgreSQL, the database span a child of
  the request rather than a sibling
- `finapp.db.connection` and no statement text: SQL on a span would carry amounts and account
  identifiers into a backend with different access control (`INV-AUD-02`)
- No exporter endpoint in source; sampling at 100% and recorded as a Phase 15 decision
- ADR-0017 records the reasoning and the four rejected alternatives
- A log line inside a request carries `traceId`, `spanId` and `correlationId` together, so a
  line found in a search leads to both the trace and the durable record - added by review,
  where it was found to be working by coincidence of two mechanisms and asserted nowhere
- A span outside any flow carries no correlation rather than a fabricated one

API conventions (2026-09-02), `P0-DOC-003`:
- [`API_CONVENTIONS.md`](../architecture/API_CONVENTIONS.md): versioning, the published contract,
  errors, correlation, request limits, idempotency, pagination and deprecation in one place
- Every section labelled `Implemented` or `Decided, not yet implemented`, with the owning task -
  and an unlabelled section fails the build, so the distinction cannot erode
- Every stated value pinned against the code: the prefix, the handler package, the correlation
  header, its charset and 128-character bound, the size limit and its property, the problem-detail
  members and the media type
- The error-code catalogue is referenced, never restated; a pasted table fails the build
- Cursor pagination decided on a correctness argument, not a performance one
- Six mutations caught in both directions - document wrong, and implementation moved
- Review added three more: every error code the document names must exist, the documented
  charset must be the whole of the implemented one, and the deprecation windows must agree
  with ADR-0015 rather than being a second unguarded copy of it

Health, readiness and build info (2026-09-02), `P0-TSK-027`:
- Liveness depends on nothing external; readiness includes PostgreSQL; both proven in both
  directions, with a real database for the positive control
- 503 rather than a DOWN body behind a 200, because a load balancer acts on the status line
- The application starts with its database unreachable and says NOT_READY, rather than crash-looping
- Checked through the application's own pool, as `finapp_app` and never a superuser - asserted, so
  the check cannot pass on privileges the application would not hold
- Allow-list exposure: `health` and `info`; twelve other actuator endpoints asserted absent
- No health body names a dependency, URL, host, database, driver, error or exception
- `/actuator/info` carries build identity with no timestamp, so reproducible archives stay so
- Every wait on the readiness path is bounded; `socketTimeout` deliberately left to Phase 3
- Flyway is absent from the application's runtime classpath, so ADR-0011's "no migrations at
  startup" is structural rather than a setting - and is asserted
- ADR-0015's claim that operational endpoints escape the `/v1` prefix is now verified by test in
  both directions; it was unverifiable when written

API versioning and contract publication (2026-09-02), `P0-TSK-026`:
- `/v1` applied once in the composition root to every handler under `com.finapp`; controllers
  declare no version, and the unprefixed path is proven not to be served as well
- Operational endpoints are deliberately unversioned, and that falls out of the mechanism rather
  than needing an exception - actuator has its own handler mapping
- The OpenAPI document is generated from the running application on every build and compared byte
  for byte against [`docs/api/openapi.json`](../api/openapi.json); any difference fails the build
- Each difference is labelled `BREAKING` or `COMPATIBLE`, and the failure message says what to do
- Every error code is published as a reusable response keyed by the code, pinning the `status`,
  `code` and `type` it always carries as data rather than prose
- springdoc is test-scope: the running application serves no `/v3/api-docs` and ships no
  documentation library
- ADR-0015 records the strategy, the four rejected alternatives and the deprecation policy
- Every `$ref` in the published document is proven to resolve, and the contract is proven to
  contain no test fixture - both added by review, both proven by mutation

Boundary validation and ingress correlation (2026-09-01), `P0-TSK-025`:
- Declarative constraints on the request type, rejected before any domain invocation - proven by
  counting handler entries rather than by reading the response
- Constraint failures render 422; the detail names fields and constraints and never the rejected
  values, which are the caller's own input (`INV-AUD-02`)
- `api.PayloadTooLarge` made real: a declared over-limit `Content-Length` is refused without
  reading a byte, and a chunked body - which declares no length - is bounded by a counting stream
- Filters render the contract themselves, because an exception in a filter never reaches
  `@ExceptionHandler` and would produce the container's default page
- Every request gets a correlation identifier and every response carries it, in the body and in
  `X-Correlation-Id`; the scope wraps error handling, which is what makes the contract's member
  populated rather than always absent
- An untrusted inbound header is replaced rather than sanitised, and never fails the request

Error contract (2026-09-01), `P0-TSK-024`:
- RFC 9457 problem details on **every** error path, including the four the framework raises before
  our code runs - each proven over real HTTP, each failing if its handler is removed
- No exception message, type, stack frame or framework member reaches a client; the response is
  built from the error code alone, and `ProblemDetail` has no factory taking a `Throwable`
- `ApiException` keeps the log message and the client detail in separate fields, so the unsafe
  default is unreachable rather than merely discouraged
- The JSON is decided in one place (`ProblemDetailBody`), after direct serialisation was found to
  drop the correlation identifier and render absent members as null
- Codes are namespaced, enumerable and catalogued, reconciled with
  [`ERROR_CONTRACT.md`](../architecture/ERROR_CONTRACT.md) in both directions
- The contract lives in `platform`, the rendering in `app` - a published contract must not be a
  function of the web stack under it

Delivery assumptions made executable (2026-09-01), `P0-TST-006`:
- Deduplication proven independent of arrival order, with duplicates interleaved and backwards
- The document's sharpest claim demonstrated: an order-dependent handler is **still wrong** under
  the inbox - it ends believing a completed transfer is in flight, with nothing failing anywhere
- An ordering key shown to fix it on the same deliveries, with a positive control so a handler
  that ignored messages could not pass
- A swept dedupe record admits the effect again, which is what `DATA_MIGRATIONS.md` §9 means by
  retention being a correctness bound rather than housekeeping
- "Dedupe disabled" demonstrated against the live database: dropping the inbox primary key fails
  nine tests across three classes

Outbox crash recovery, end to end (2026-09-01), `P0-TST-005`:
- The full chain asserted in one test: business fact and outbox row in one transaction, the relay
  restarted, the event published once with the correlation of the flow that produced the fact
- An instance killed with `pg_terminate_backend` mid-publication releases its aggregate, and a
  surviving instance finishes the job - the property that makes the transaction-scoped lock
  load-bearing
- A rolled-back fact leaves the relay nothing to announce, asserted at the relay rather than at
  the writer, because the consequence is an announcement nobody can retract
- Exercised through the application role, so the outbox grants are proven rather than assumed
- The criterion demonstrated: moving the write onto its own connection fails three tests, every run

Audit immutability under privilege widening (2026-09-01), `P0-TST-007`:
- `UPDATE` proven denied on **every** column of the audit trail, not merely the one a test happens
  to set, with the column list derived from the catalogue
- Column-level grants - invisible in `information_schema.table_privileges` - are checked against
  the table grant for every platform table
- Found by trying it: `GRANT UPDATE (reason)` let the application rewrite a committed record's
  justification while the whole audit suite stayed green
- Both widenings now demonstrated to fail the suite: table-level fails six tests, column-level two

Auditable-action registry (2026-09-01), `P0-TSK-023`:
- `AuditableAction`: an interface each module implements as an enum, because the platform sits
  below every business module and cannot enumerate their vocabulary
- `AuditRecord.operation` is typed, so an action outside the registry cannot be recorded at all -
  the type system, not review, is what keeps the trail's vocabulary closed
- [`AUDITABLE_ACTIONS.md`](../architecture/AUDITABLE_ACTIONS.md) and the code are one definition,
  reconciled in three directions by `AuditableActionRegistryTest`, each proven by planting the fault
- `requiresReason()` decides per action whether a justification is mandatory, enforced by
  `AuditRecord` - the decision `V009` deferred to the domain
- The registry's limit is stated: it cannot detect a privileged action that writes no record

Audit trail and database role split (2026-09-01), `P0-TSK-022`:
- `platform.audit_record`: append-only at the **privilege** level - the application role holds
  `INSERT` and `SELECT` and nothing else (`INV-HIST-03`)
- Two ordinary roles, both `NOSUPERUSER`: `finapp_migrator` owns the schema and Flyway connects
  as it; `finapp_app` is what the application connects as
- Roles provisioned by infrastructure, grants by the migration that creates each table - a role
  is a cluster object and cannot belong to one schema's migration history
- `UPDATE`, `DELETE`, `TRUNCATE`, `DROP`, `ALTER` and self-granting all proven denied, with the
  vacuity precondition asserted first because a superuser would pass all of it
- `V008` pays the grants `V002`, `V005` and `V007` each promised; every table's granted set is
  checked against its design, so too-wide fails as loudly as too-narrow
- `AuditRecord` makes all seven questions mandatory at construction; `ActorType` and
  `AuditOutcome` generate their own `CHECK` constraints, guarded hermetically against drift

Inbox deduplication (2026-09-01), `P0-TSK-021`:
- `platform.inbox_message`: the dedupe record and the side effect commit in one transaction, so
  the row exists if and only if the effect happened (`INV-IDEM-04`)
- Keyed on **(consumer, dedupe_key)**, so one event's many consumers each handle it exactly once
  rather than the first one silently suppressing the rest
- Eight concurrent instances handed the same redelivery produce one effect, counted in a
  side-effect table rather than inferred from the wrapper's return value
- A handler that throws takes its dedupe record with it, and the redelivery is then handled
- Contention is reported after a 500ms bound, not waited on: losing costs one redelivery, which
  an at-least-once transport was going to perform anyway
- An auto-commit connection is refused explicitly, because the record would otherwise commit
  alone and lose the message
- Retention documented as a correctness bound (`DATA_MIGRATIONS.md` §9); every record is terminal,
  so there is no "never sweep a non-terminal record" caveat

Outbox relay (2026-09-01), `P0-TSK-020`:
- Every instance polls; a transaction-scoped advisory lock **per aggregate** means one instance
  drains a given aggregate at a time, so ordering survives concurrency instead of depending on
  there being one relay
- At-least-once, said plainly: a crash between publishing and recording it republishes, proven by
  a publisher that delivers and then dies
- Exponential backoff with a ceiling, attempt counting, and a poison path that **blocks** its
  aggregate rather than skipping it, so consumers never get an undetectable gap
- Eight concurrent instances drain a 36-event backlog with no duplicate and no loss
- `V006` adds `next_attempt_at`, `dead_lettered_at` and `last_error`, with eligibility and
  abandonment decided by the server's clock; three new constraints, all exercised at the schema
- Publishes through an `EventPublisher` port; no broker client on the classpath, so the
  direct-publish rule still exempts nothing

Transactional outbox (2026-09-01), `P0-TSK-019`:
- `platform.outbox_event`: the full envelope as columns, all ten NOT NULL, so an untraceable
  event cannot be queued any more than it can be constructed
- `INV-EVT-01` proven both ways: a rolled-back fact loses its outbox row, a committed one keeps it
- `nothingPublishesToABrokerDirectly` fails the build on a direct publish anywhere, matched by
  package name so the rule exists before the dependency does
- Correlation now proven to reach a third sink — the outbox row — closing one of `P0-TSK-014`'s
  deferred clauses

Event envelope (2026-09-01), `P0-TSK-018`:
- `EventEnvelope`: all ten `INV-EVT-03` fields mandatory at construction, so an untraceable
  event cannot be built; the field set and the null checks are derived from the record by test
- Metadata only — no payload — so relays and consumers handle events they cannot deserialise,
  and no log line can spill event contents
- `EventId` as a typed, time-ordered `EntityId`: the value an inbox deduplicates on
  (`INV-IDEM-04`), so it is fixed when the event is created rather than regenerated on redelivery
- Canonical form pinned by exact-match test; `eventVersion` and `schemaVersion` distinguished
  and documented
- Correlation and causation **identifiers** moved to `sharedkernel`; the context mechanism stays
  in `platform`

Idempotency failure modes (2026-09-01), `P0-TST-004`:
- Contention proven by observing PostgreSQL's own lock waits rather than by hoping threads
  overlap, so the test cannot pass while contention is broken
- Lost response, expired key, swept key, and a crashed instance mid-command each driven to a
  single effect
- Dropping the unique constraint fails 17 tests, demonstrated against the live database

Idempotent execution (2026-09-01), `P0-TSK-016`:
- Claim, execute, record outcome — all in the caller's transaction, so a crash cannot leave an
  effect without a record or a record without an effect
- 8-way concurrent duplicates: one execution, one effect, eight identical responses
- `INV-IDEM-03` enforced by fingerprint comparison, refusing rather than guessing when the
  algorithm differs
- A live `IN_PROGRESS` claim is reported; a stale one is taken over, with the staleness test in
  the database so two reclaims cannot both win
- The data-access mechanism stays undecided: the wrapper depends on a port

Idempotency schema (2026-09-01), `P0-TSK-015`:
- `platform.idempotency_record`: `INV-IDEM-01` enforced by a unique key on
  (scope, idempotency_key), proven under 16-way contention against a real PostgreSQL
- `V003`: terminal claims frozen and identity immutable, enforced by trigger because a `CHECK`
  constraint cannot see the previous row (`INV-LIFE-04`)
- The state machine checked in the schema, not only in code: an `IN_PROGRESS` claim cannot
  carry an outcome, a terminal one must be timestamped
- Fingerprint length bounded and its algorithm recorded, so `INV-IDEM-03` cannot be weakened by
  truncation or by a silent algorithm change
- `IdempotencyState` and the `CHECK` constraint generated from one definition, guarded
  hermetically

Correlation kernel (2026-09-01), `P0-TSK-014`:
- `CorrelationId` / `CausationId`: distinct types, validated against log injection with a
  default-deny charset, bounded, rejected rather than sanitised
- `Correlation`: correlation inherited, causation replaced by the emitting message, so the
  causal tree survives rather than flattening
- `CorrelationContext`: scope entry/exit with restore-not-clear, and explicit capture at
  submission so a pooled worker never inherits an unrelated flow
- Log lines proven to carry the identifier by reading a real appender, not by mocking a logger
- No Spring: the kernel is framework-free and serves an HTTP filter, a job and a consumer alike

Time discipline (2026-09-01), `P0-TSK-013`:
- Ambient time is a build failure: no zero-argument `now()`, `System.currentTimeMillis()`,
  `nanoTime()` or `new Date()` anywhere in production code
- `Clock.systemUTC()` permitted in the composition root alone, proven from both sides — the
  root is exempt, another module is not
- `Instant.now(clock)` and `LocalDate.now(clock)` deliberately allowed
- `TestClock` moves time forwards, backwards and to an instant, so time-dependent behaviour is
  tested at boundaries rather than by sleeping
- Posting date, value date and system time distinguished in `DOMAIN_MODEL.md` §Time

Identifier kernel (2026-09-01), `P0-TSK-012`:
- `EntityId`: typed per-aggregate identifiers; substitution is a compile error, proven by
  invoking `javac`; identity includes the concrete type so two kinds never compare equal
- `IdGenerator`: UUIDv7, monotonic within a millisecond, through counter exhaustion, and
  across a backwards clock; clock and randomness injected
- Concurrency proven at 80,000 identifiers across 16 threads on one frozen millisecond
- ADR-0013 records the decision, the rejected alternatives, and the creation-time disclosure
  a UUIDv7 inherently carries

Allocation residual proof (2026-09-01), `P0-TST-002`:
- `INV-BAL-03` swept across the criterion's full 1..100 range for even allocation and up to 100
  weights for weighted allocation, with amounts from the whole representable range
- Both sweeps assert they actually encountered indivisible remainders, so neither can pass by
  allocating only divisible amounts
- Evenness asserted separately from totality, since a first-part-takes-all allocator satisfies
  totality

Kernel property tests (2026-09-01), `P0-TST-001`:
- `MoneyPropertiesTest`: commutativity, associativity, additive identity and inverse,
  subtraction as negated addition, multiplication as repeated addition, and the reversal
  round-trip, over 20,000 generated trials per law across JPY (0), USD (2) and BHD (3)
- Exactness checked against `BigDecimal` as an independent implementation
- Rounding bounded within one minor unit, with each policy pinned by its defining direction
- Every law asserts its own coverage, so it cannot pass by rejecting everything

Rounding and allocation (2026-08-31), `P0-TSK-010`:
- `RoundingPolicy`: six named policies with a stable name for `INV-HIST-04` recording
- `Money.of(BigDecimal, CurrencyCode, RoundingPolicy)` — rounding requires a named policy
- `Money.allocateEvenly(int)` and `Money.allocateByWeights(long...)` — the parts always sum
  back to the original, so no residual is ever absorbed (`INV-BAL-03`)

Money persistence (2026-08-31), `P0-TSK-011`:
- `MoneyColumns` in `platform`: the single definition of the three-column shape from ADR-0003,
  with the DDL fragment migrations use so the shape cannot drift between tables
- Round-trip verified against a real PostgreSQL, including the `BIGINT` extremes
- Mechanism-agnostic: no ORM is chosen, so none is chosen by accident

Project initiation (2026-08-31):
- Master delivery plan for all seventeen phases — [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md)
- Phase gate model, status model and per-phase exit criteria — [`PHASE_GATES.md`](PHASE_GATES.md)
- Engineering backlog with Phase 0 elaborated to task granularity — [`BACKLOG.md`](BACKLOG.md)
- First architecture baseline — [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- Invariant catalog, 64 invariants — [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md)
- Definition of Done with task-type profiles — [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)
- Execution protocol — [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)
- ADR-0001 through ADR-0010 (`Proposed`) — [`docs/adr/`](../adr/README.md)
- Roadmap with sequencing rationale and seam register — [`ROADMAP.md`](../product/ROADMAP.md)

## Active Work

None in progress. `P0-TSK-029` is the next task.

## Blockers

None.

`P0-TSK-004` (CI pipeline) was recorded as blocked. The 2026-08-31 task completion review
found the blocker was a defect in the backlog, not in the work: `P0-TSK-004` declared
dependencies on `P0-TSK-011` (Money persistence mapping) and `P0-TSK-036` (test taxonomy),
neither of which is required to run a build with its tests. Because both are scheduled after
several tasks carrying `DOD-BUILD`, whose "CI green" criterion they could therefore never
satisfy, the plan contained an unsatisfiable requirement. Dependencies corrected to
`P0-TSK-001, P0-TSK-002`; CI is now startable and closes the outstanding `DOD-BUILD` gap
across all four completed tasks.

---

## Local Environment Prerequisites

Machine-specific setup that the repository deliberately does **not** contain. The build must
work on any machine without local edits (`DOD-BUILD`: "no developer-machine-specific
assumptions"), so anything below belongs in `GRADLE_USER_HOME`, never in the repo.

**TLS interception by antivirus (this development machine).** AVG "Web/Mail Shield"
intercepts HTTPS and re-signs it with its own root CA. Windows trusts that CA; the JDK's
bundled `cacerts` does not. Java tooling therefore fails with:

```
PKIX path building failed ... unable to find valid certification path to requested target
```

while `curl` and the browser work — which makes it look like a Gradle fault rather than a
TLS-trust one. Resolved in `~/.gradle/gradle.properties` (outside the repo):

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

That covers the Gradle daemon. Bootstrapping the distribution runs in a separate JVM that
reads `GRADLE_OPTS`, so on a machine with no Gradle distribution cached also export:

```
GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatives: import the AVG root into the JDK `cacerts` with `keytool`, or disable HTTPS
scanning in AVG.

**Resolved for CI (`P0-TSK-004`):** this is specific to this machine. GitHub-hosted runners
perform no TLS interception, so the workflow needs no equivalent setting. If CI ever moves
to a self-hosted runner behind an intercepting proxy, that runner needs the same treatment —
in its own environment, never in the repository.

**Git Bash rewrites container paths.** Running a command inside a container with an absolute
path from Git Bash (MSYS) silently rewrites it:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh ...
  -> exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file
```

Prefix with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use only —
health checks and container entrypoints run inside Docker and are unaffected.

**`clean` fails with "Unable to delete directory".** On Windows an orphaned Gradle daemon
keeps module jars open, so `clean` cannot remove `build/`. It is leftover state, not a repo
defect. `./gradlew --stop` handles the usual case; a daemon whose `GRADLE_USER_HOME` has been
deleted survives that and must be killed by PID:

```
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'GradleDaemon' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**The container clock drifts behind the host and is corrected backwards.** PostgreSQL's
`now()` is therefore not monotonic across two statements seconds apart: a row written before a
correction and read after it can have a `now()`-derived timestamp *in the future*. Observed at
542 ms during `P0-TSK-020`, where it made the relay suite fail about one run in fourteen —
always as "the relay published nothing", never anywhere near the clock.

This is a property of the local Docker VM, not of the code, and the platform is already built
for it: coordination timestamps are set **and** compared by the server, so a step affects both
sides equally and correctness never depends on the step's direction. What it does break is a
*test* that assumes a row written a moment ago is eligible a moment later. Such fixtures
back-date the row explicitly rather than relying on the clock (`OutboxRelayTest.backDate`).

A time-dependent test failing intermittently on this machine is worth checking against
`SELECT now()` before it is treated as a defect.

**Resetting local infrastructure.** `docker compose down` keeps data; `docker compose down -v`
discards it. A reset is required after changing Kafka's `CLUSTER_ID`, or when moving to a new
PostgreSQL major version without running `pg_upgrade` — the volume is formatted for the major
version that created it.

---

## Partially Satisfied Definition of Done

Recorded so it is not mistaken for a completed criterion.

| Task | DoD item not yet met | Owning task |
|------|---------------------|-------------|
| `P0-TSK-001` — `P0-TSK-005` | `DOD-BUILD` requires "CI green". A pipeline now exists and all four jobs pass when run locally, but it has never executed on a CI runner because the repository has no git remote. This closes on the first successful run after a remote is added. | Adding a remote |
| `P0-TSK-004` | The CycloneDX SBOM covers the whole resolved dependency set, test scope included (21 of ~61 components). Plugin 3.4.1 exposes no configuration filter. Adequate for vulnerability scanning — test libraries execute on CI runners, so they are legitimately in scope — but it means a HIGH/CRITICAL advisory in a test-only library fails the build though nothing vulnerable ships, and **the SBOM must not be published as shipping provenance in this form** because it overstates what is deployed. | Phase 15 (supply chain and provenance) |
| `P0-TSK-004` | CI actions and scanner images are pinned by SHA/digest with no automated update path, so the pins will rot. | `P0-TSK-040` |
| ~~`P0-TSK-002`~~ | ~~Boundary enforcement partial~~ — **closed**. Cross-module internals and entity references by `P0-TSK-007`; `INV-MON-01` by `P0-TSK-008`. | — |
| ~~`P0-TSK-014`~~ | ~~Correlation must reach four sinks; the trace one is unverifiable~~ - **closed** by `P0-TSK-028`. All four sinks are now asserted: the log (`P0-TSK-014`), the outbox row (`P0-TSK-019`), the audit record (`P0-TSK-022`) and the trace, where every span carries `finapp.correlation_id`. The clause survived four tasks and a milestone because `CorrelationSinkCoverageTest` refused to let a new platform concern land unclassified - which is what closing on arrival rather than on memory means. | — |
| ~~`P0-TSK-003`, `P0-TSK-005`~~ | ~~Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants cannot be exercised~~ — **closed** by `P0-TSK-022`. `finapp_migrator` and `finapp_app` exist, both `NOSUPERUSER`; Flyway connects as the migrator and every table grants the application role only the DML it requires. `INV-HIST-03` is now enforced and proven; `INV-LED-03` and `INV-HIST-01` have the mechanism they need and close when the ledger tables exist (Phase 3). | — |

---

## Known Architectural Debt

Debt is recorded here as it is deliberately accepted, with: what was deferred, why, what risk it
carries, what triggers paying it down, and the owning phase.

| Deferred | Why | Risk carried | Trigger | Owning phase |
|---|---|---|---|---|
| **Broker adapter behind `EventPublisher`.** The relay publishes through a port; nothing implements it | An adapter decides the wire format, topic scheme and producer acknowledgement configuration, and puts a broker client on the classpath — four decisions belonging to the phase with events to publish. `EVENT_ARCHITECTURE.md` already defers the wire format | **None today.** Nothing produces events yet, so an unpublished outbox is an empty outbox. The relay's own correctness is proven against a publisher that fails on demand, which no real broker does reliably | The first module that emits a domain event | Phase 3 (ledger) |
| **Outbox retention.** Published rows are never deleted | `V005` says a published row may be deleted once retained long enough for diagnosis; the sweep is a scheduled job with its own cluster-safety question, and no task owned it | Unbounded table growth. The partial pending index does **not** grow with it — published rows leave it — so the cost is storage and vacuum, not relay latency | Table size becoming operationally material | Phase 15 (data retention and deletion) |
| **Relay metrics.** `RelayPollResult` is returned but nothing aggregates it | No metrics infrastructure exists (`P0-EPIC-09`, M0.4) | ADR-0005 names outbox depth, age and relay lag as first-class monitored metrics. Until they exist, a stalled aggregate is visible only in logs — which is detection by reading, not by alerting | `P0-EPIC-09` landing | Phase 0, M0.4 |
| **Inbox retention sweep.** Records are never deleted | The sweep is a scheduled job with its own cluster-safety question, and `V007` deliberately adds no `expires_at` index until its predicate is written | Unbounded growth of a table whose only index is its primary key. **Not** a correctness risk in this direction: a record that is never swept deduplicates forever, and it is early expiry that admits a duplicate (`DATA_MIGRATIONS.md` §9) | Table size becoming operationally material, or the first consumer going live | Phase 15 (data retention and deletion) |
| **Inbox metrics.** Duplicate rate and contention rate are returned as outcomes but nothing aggregates them | No metrics infrastructure exists (`P0-EPIC-09`, M0.4) | A rising duplicate rate is a signal about the transport and a rising contention rate about consumer concurrency; both are currently visible only as log lines, one of which is at debug | `P0-EPIC-09` landing | Phase 0, M0.4 |
| **Audit retention and archival.** Records are never deleted, and the application role cannot delete them | ADR-0010 is explicit that deletion is not an option and that archival must preserve queryability - which is a Phase 15 deliverable, not a sweep | Unbounded growth of a table written on every privileged action. **Not** a correctness risk: the inability to delete is the invariant working, and archival must preserve the trail rather than trim it | Table size becoming operationally material | Phase 15 (retention and archival) |
| **Four-eyes approver is not modelled.** `audit_record` records one actor | `INV-AUD-04` applies to manual adjustments, break resolutions, policy activations and period close - none of which exist yet. ADR-0010 schedules it for Phases 3, 8 and 14 | None today: there is no four-eyes action to under-record. When one arrives it needs a second actor column, which is an ordinary forward migration | The first action requiring a second approver | Phase 3 |
| **The three registered platform actions are not emitted.** `outbox.EventAbandoned`, `outbox.EventRetryAuthorised`, `outbox.EventDiscarded` | Two describe the manual procedure in `EVENT_ARCHITECTURE.md` §Handling an abandoned event, performed today with raw SQL; the third is a relay decision currently only logged. Wiring them is a change to `P0-TSK-020`'s relay and to tooling that does not exist | An abandoned event - consumers permanently not receiving a fact that happened - is recorded only in logs, which ADR-0010 is explicit do not count as an audit trail. This is exactly the gap the registry exists to make visible | Dead-letter tooling, or the relay taking an `AuditWriter` | Phase 15 (dead-letter handling), or sooner if the relay is revisited |
| ~~**No ingress correlation filter.**~~ — **closed** by `P0-TSK-025`. `CorrelationFilter` establishes a scope per request at `HIGHEST_PRECEDENCE` and echoes the identifier in `X-Correlation-Id`; every response carries it, error or not. | — | — | — | — |
| ~~**The ingress filter must wrap error handling.**~~ — **closed** by `P0-TSK-025`. The filter is ordered outside the dispatcher and its scope closes only after the whole chain, error handling included. | — | — | — | — |
| **The operational endpoints are unauthenticated.** `/actuator/health/*` and `/actuator/info` are reachable by anyone who can reach the port | `DOD-API` requires a negative authentication test for every new surface, and there is no authentication anywhere in the platform yet - `P0-EPIC-10` is the epic that brings it. Building one authentication mechanism for the actuator alone would be a second scheme to retire | **Low, and bounded by what is published.** The bodies are pinned by exact-match test to a status and, for the aggregate, its group names; details, components, environment, JVM and OS are all off, and twelve other endpoints are proven absent. What remains is that an unauthenticated caller can learn the instance is up and which build it runs | `P0-EPIC-10` landing, at which point `show-details: when-authorized` also becomes available | Phase 0, M0.4 |
| **Connection-pool sizing is not reasoned about across instances.** Hikari's default is 10 connections per instance | Nothing uses the pool for anything but a health check, so any number chosen now would be a guess. Sizing needs a workload | **None today, real at Phase 3.** Ten instances at the default exhaust PostgreSQL's default `max_connections` of 100 on their own, before any connection is used for work. The failure mode is instances failing readiness for pool exhaustion rather than for anything wrong with the database - and `ADR-0014` says N is never 1, so this is arithmetic that has to be done before the ledger, not after | The first module that actually uses the pool | Phase 3 |
| **Dead-letter tooling.** Resolving an abandoned event is a manual `UPDATE` | The mechanism is needed now; the tooling is a Phase 15 concern | An operator resolving a stalled aggregate acts by hand against a live table. Acceptable only because the outbox is transport, not financial history (`INV-EVT-02`) — the same action against a ledger table would not be. The procedure is documented in `EVENT_ARCHITECTURE.md` §Handling an abandoned event | Abandonment occurring in practice | Phase 15 |

None of these is financial-correctness debt.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Architectural Debt,
**financial-correctness debt is never accepted** — an invariant is either protected or the
work is not done.

Note: items in [`DECISIONS.md`](DECISIONS.md) §Deliberately Deferred are scoping decisions,
not debt.

---

## Unresolved Architectural Questions

Ordered by when they must be answered. Each requires an ADR before the work that depends on
it begins.

| # | Question | Must resolve by | Risk if unresolved |
|---|----------|-----------------|--------------------|
| 1 | Isolation level and locking strategy for concurrent postings | Phase 3 | **High** — lost updates or double spend under contention (`INV-CON-01`) |
| 2 | Chart-of-accounts structure and its relationship to the Phase 14 GL | Phase 3 | High — a narrow structure forces retroactive remapping, breaking `INV-ACC-04` |
| 3 | Balance projection placement (ledger schema vs separate read store) | Phase 3 | Medium — affects contention and rebuild cost (ADR-0009) |
| 4 | Whether `accounts` and `wallet` are one module or two | Phase 3 | Medium — **working position recorded** (one module, `MODULE_ARCHITECTURE.md` §3 M1) with a named split trigger; still to be confirmed |
| 5 | Transfer/ledger transaction boundary and compensation strategy | Phase 4 | High — determines whether a saga is ever needed internally |
| 6 | Accounting treatment of authorization (memo/hold) vs capture (posting) | Phase 5 | High — misstates available funds if wrong |
| 7 | Whether `checkout` is its own module or part of `merchant` | Phase 6 | Low — **working position recorded** (own module, §3 M2) with a named merge trigger |
| 8 | Fee model: who pays, when recognised, gross vs net settlement | Phase 6 | High — changing revenue recognition after postings exist is a restatement |
| 9 | Which payment rail to simulate first, and its finality semantics | Phase 5 | Medium — first rail shapes the abstraction (mitigated by designing to `PAYMENT_LIFECYCLES.md`) |
| 10 | Which jurisdiction-neutral compliance abstractions belong in the MVP | Phase 2 | Medium |
| 11 | Fail-safe policy for risk evaluation: block or allow on unavailability | Phase 13 | High — a wrong default is either an outage or an open door |
| 12 | **Data-access mechanism: JPA/Hibernate, Spring Data JDBC, or plain JDBC** | Phase 3 | Medium–High — surfaced by `P0-TSK-011`, whose description said "a reusable embeddable" while no ADR had chosen an ORM. It matters here more than usual: Hibernate's dirty checking emits `UPDATE`s, and `INV-LED-03`/`INV-HIST-01` say posted financial records are never updated, with the application role holding no `UPDATE` privilege at all. `MoneyColumns` was written mechanism-agnostic so the decision is not made by accident; it must be made before the ledger schema exists |

Resolved during initiation:
- ~~Which modules form the initial modular-monolith cut?~~ → [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- ~~Deployment topology?~~ → ADR-0001
- ~~Money representation?~~ → ADR-0003
- ~~Idempotency mechanism?~~ → ADR-0004
- ~~Reliable event publication?~~ → ADR-0005
- ~~Is balance authoritative or derived?~~ → ADR-0009

---

## Next Task

**`P0-TSK-029` - Metrics and dashboards**, continuing `P0-EPIC-09`.

Two debts come due with it, both recorded since `P0-TSK-020` and `-021`: `RelayPollResult` and the
inbox outcomes are returned and aggregated nowhere, so a stalled aggregate and a rising duplicate
rate are visible only by reading logs - detection by reading rather than by alerting. ADR-0005
names outbox depth, age and relay lag as first-class monitored metrics.

`P0-EPIC-09` then finishes with `P0-TSK-030` (structured logging with redaction, the highest-risk
task in the epic) and `P0-TST-008`. `P0-EPIC-10` (security baseline) closes M0.4.

---

## Change Log

| Date | Change |
|------|--------|
| 2026-09-02 | Task completion review of `P0-TSK-028`. One important finding, and it is a property that was **working by coincidence and asserted nowhere**: a log line emitted inside a request carries `traceId`, `spanId` and `correlationId` together - which is the join that makes any of this usable, and it holds only because two independent mechanisms happen to agree, Boot's log correlation and `CorrelationContext`. Disable either and every log line quietly stops being joinable, with nothing failing. Found by probing the logging context from inside a request rather than from the test thread, where it is empty and says nothing. Now asserted. Three further findings, all mine. **A fabricated exception**: the database span recorded connection failures as `span.error(new IllegalStateException(type))`, attaching a stack trace pointing at the recording line rather than at anything that failed - worse than no stack trace, because it looks like one; replaced with an `error.type` tag. **A bean lookup on every connection acquisition**, in front of every database connection the platform will ever make; memoised. And **the hermetic "every span carries correlation" assertion ran over a set of one**, because liveness produces a single span - a claim that cannot fail for the right reason; it now uses readiness, which reaches for a connection and produces two. Also added the branch nobody had covered: a span started outside any flow must carry **no** correlation, since inventing one would fill a dashboard with identifiers matching nothing in any table. Verified rather than assumed: the recorded spans really do include a real HTTP SERVER span, so the HTTP leg is genuine and not the database span in disguise. 400 hermetic tests, 159 database tests. |
| 2026-09-02 | `P0-TSK-028` complete; `P0-EPIC-09` opened. **The acceptance criterion could not be met as written and was corrected rather than approximated** - it named HTTP, DB, outbox and consumer, and two of those have no subject: there is no broker adapter and no consumer wiring, so no request can reach either. Same correction `P0-TSK-014` needed, and the legs transfer to the Phase 3 broker adapter. What was delivered: **correlation on every span**, stamped once by a span processor rather than by each component - because "every span" is not a property discipline delivers, and forgetting is silent: the span is recorded, the trace looks complete, and it cannot be found. **A trace id is explicitly not a substitute for a correlation id**: it is subject to sampling, so a sampled-out flow would be unfindable from the only value a customer holds, and it is absent from every table. HTTP and database proven in one connected trace against a live PostgreSQL, with the database span a **child** of the request - a flat list sharing a trace id cannot answer what a connection was acquired for. **No JDBC tracing library and no statement text**: SQL on a span would carry amounts and account identifiers into a backend with different retention and access control (`INV-AUD-02`), arriving silently the first time somebody writes a query. Three defects found by running it, each producing **no traces and no error**: Boot 4 gates the OTel SDK behind `management.opentelemetry.enabled`, off by default; the auto-configuration module is `@ConditionalOnClass` on the bridge so both are required; and a duplicate `management:` key in YAML stopped every context. Plus one of mine - a javadoc claiming the tracer was resolved lazily while the code resolved it eagerly in a bean post-processor. **The correlation sink guard fired for the sixth time**, on the sink it was built for, closing `P0-TSK-014`'s last clause four tasks and one milestone after it was recorded. ADR-0017 recorded. 398 hermetic tests, 159 database tests. |
| 2026-09-02 | Task completion review of `P0-DOC-003`. No critical findings; three gaps in the guard, all closed, and the pattern is that a document guard is only as good as the set of claims it thought to check. **The document named error codes and nothing verified they exist**: `ErrorCodeRegistryTest` reconciles the CATALOGUE with the taxonomy, but these were mentions in prose, so renaming a code would have left the conventions telling a client to handle something that can never arrive - the same defect as documenting one that was never added, from the other direction. **The charset check ran one way only**: it caught a character dropped from the document but not the pattern being widened without the document following, which is the direction that rots quietly; now derived from `CorrelationId` by probing every printable character rather than restated. **And the deprecation windows were a second unguarded copy of ADR-0015** - the exact duplication this class refuses to allow for error codes, written by me two sections later; now compared numerically and wording-independently. Nine of nine mutations caught across the two rounds, in both directions. Two claims verified rather than assumed: every response really does carry `X-Correlation-Id`, actuator responses included, which is what the document promises; and the document is genuinely a declared build input - a green run, an edit to the document alone, and the task re-ran and failed. 394 hermetic tests, 156 database tests. |
| 2026-09-02 | `P0-DOC-003` complete; **`P0-EPIC-08` closed**. The API conventions document - versioning, errors, correlation, request limits, idempotency, pagination, deprecation - and the interesting part was not writing it. Two of the six conventions the backlog asks for describe behaviour that **does not exist**: there is no money-moving endpoint and no collection endpoint, while `DOD-DOC` forbids aspirational statements presented as current fact. Omitting them would have defeated the task's own reason for existing, since a convention decided after five modules have each invented their own is not a convention. Resolved by making the distinction **structural**: every section is labelled `Implemented`, naming the class and test that prove it, or `Decided, not yet implemented`, naming the owning task - and **an unlabelled section fails the build**, so the distinction survives the next person in a hurry. The acceptance criterion is enforced rather than asserted: `ApiConventionsAreAccurateTest` pins the prefix, the handler package, the correlation header with its charset and 128-character bound, the size limit and its property, the problem-detail members and the media type - and fails if the error-code catalogue is ever pasted in, because `ERROR_CONTRACT.md` owns it and a second unguarded copy would drift while looking authoritative. **Pagination decided on a correctness argument**: offset re-reads a moving set, so a row inserted between pages is silently skipped or repeated - on a transaction history that is a payment missing from a statement with nothing reporting an error; that offset is also O(offset) on a ledger is the lesser objection. Six of six mutations caught in both directions. Also corrected a stale record found while checking: the `P0-TSK-014` DoD row still said no HTTP surface existed, which `P0-TSK-025` had built the day before. 391 hermetic tests, 156 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-027`. Two important findings, both about checks rather than code. **`:app:databaseTest` would never have run in CI**: the workflow step named `:platform:databaseTest` explicitly - a list of one that went stale the moment a second module gained database tests, which is what this task did. The positive control for readiness, the half of the acceptance criterion that says it reports UP when the database is actually there, would have run on one machine and nowhere else. Now `./gradlew databaseTest` unqualified, so a third module is covered without anyone remembering. And **the leak test was a deny-list**: turning details on and reading the body it would really publish showed the disk-space indicator reporting an absolute filesystem path - a username and the host's directory layout - which no list of forbidden substrings had anticipated, while three of its seven entries never fired at all. Replaced with an exact match, which immediately found something else the deny-list had never questioned: the aggregate publishes its group names even with details off. Same argument as `ProblemDetailBody` - specify what is published, because anything else publishes itself. Two minor fixes: the allow-list test was **partly vacuous**, since three of its twelve endpoints return 404 for reasons other than the allow-list - probing with exposure widened to `*` showed nine genuinely gated, and `heapdump` reachable behind one further property, returning 55 MB of process memory; the twelve are now split so no assertion overstates its protection. The migration check also asserted against the test classpath while describing the runtime one, and now checks the running context directly as well. Verified rather than assumed: **readiness recovers on its own** - 200, then 503 with the container stopped, then 200 within one poll of it returning, no restart. Two deferrals recorded as debt: the endpoints are unauthenticated until `P0-EPIC-10`, and connection-pool sizing across N instances is arithmetic owed before Phase 3. 384 hermetic tests, 156 database tests. |
| 2026-09-02 | `P0-TSK-027` complete. Liveness, readiness and build info - and the decision that matters is which questions they answer. **Liveness depends on nothing external**: a liveness probe consulting PostgreSQL restarts every instance at once during a thirty-second failover, leaving the fleet reconnecting in a herd to a database already in trouble, with the diagnostic state destroyed - a degradation turned into an outage by the check meant to prevent one. **Readiness includes PostgreSQL and excludes Kafka and Redis**, because the outbox holds events durably and a broker outage delays publication rather than invalidating the instance (`INV-EVT-02`). The trap closed here is Spring's own default: the readiness group is `readinessState` alone, so adding the actuator and a `DataSource` gives a readiness endpoint that returns **UP while PostgreSQL is unreachable** - correct-looking and worthless. The application now has a `DataSource` at all because readiness must be answered **through the pool the application uses**; one that opens its own connection reports healthy while the pool is exhausted, which is exactly when traffic must be diverted. `spring-boot-starter-jdbc`, never `-data-jpa`: unresolved question 12 stays open. **The application starts when its database is down**, deliberately - one that refuses to boot leaves an orchestrator with a crash-loop instead of an instance able to say what is broken. Security: allow-list exposure, no detail, no components, twelve other actuator endpoints asserted 404, and no URL, host, driver or exception in any health body. One defect found by running it, the third of its kind this session: **`properties { time = null }` compiles and does nothing** - Boot 4 excludes via an `excludes` set and otherwise falls back to the build instant, which would have defeated reproducible archives; found by reading the generated file, not the build script. ADR-0015's claim that operational endpoints escape `/v1` is now verified rather than asserted. Five of five mutations caught, and `./gradlew build` re-run with PostgreSQL stopped to prove the hermetic guarantee survived a new `DataSource`. ADR-0016 recorded. 383 hermetic tests, 156 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-026`. One important finding, and it is the defect class this project keeps meeting: **a fix that compiled, read correctly, and did nothing**. Declaring the contract baseline as a Gradle input made the test's own bootstrap message unreachable - a missing baseline aborts the task before any test runs, reporting an internal property name instead of "a first document has been generated, here it is". The first repair, `optional(true)`, was wrong for a reason worth remembering: it permits a null *value*, and the value is present - it is the file behind it that is missing. `inputs.files` rather than `inputs.file` holds both properties, and both were then re-proven: deleting the baseline reaches the test's message, editing it still re-runs the task and fails. Three further findings. **A `synchronized` that guarded nothing**: an instance method locking a different instance per test method, protecting a static memo whose own comment argued the caching was unnecessary - shared mutable state removed rather than fixed. **Two literals for one component name**, so renaming the problem-detail schema would leave every response pointing at nothing; single-sourced, and a `$ref` resolution test added because a document that does not resolve still parses, still diffs, and still looks complete. **Nothing asserted that the published contract contains no test fixture** - several suites register probe controllers by `@Import`, and their separation from this one is a property of Spring's context cache key rather than something anyone declared; a probe baked into a baseline would look exactly as authoritative. Both new guards proven by mutation. Also tightened the handler predicate to `com.finapp.`, since `HandlerTypePredicate` matches by `startsWith` and would have claimed a sibling namespace - confirmed by disassembling Spring rather than by assuming. 375 hermetic tests, 152 database tests. |
| 2026-09-02 | `P0-TSK-026` complete. The API is versioned in the path - `/v1`, applied **once** in the composition root rather than written on each controller, because a prefix repeated in every mapping is a prefix somebody eventually omits, and an unversioned route can never be changed: there is no second version to move its clients to. The version lives in the path because that is the only place it survives an access log, an audit record, a proxy cache key and a `curl` pasted into a ticket. The OpenAPI document is **generated from the running application** on every build and compared byte for byte against the committed copy: any difference fails the build, and each is labelled BREAKING or COMPATIBLE. **The gate and the classifier are deliberately separate** - a classifier clever enough to gate would have to be right about every possible edit, and its one dangerous mistake fails at the customer's end. springdoc is test-scope, so the deployed application serves no `/v3/api-docs` and ships no documentation library. Two defects found by mutation, both in what was published: **the status existed only inside an English description**, so changing `api.Conflict` from 409 to 422 read as a harmless rewording - status, code and type are now pinned as data, which was a real gap in the contract and not only in the classifier; and **springdoc synthesises a `servers` entry from the request**, which in a test is the random port and on a deployment is an internal address published to every client. A third in the classifier: exempting a removed `description` as prose made deleting a component look like a rewording. Six of six mutations now behave correctly. Also fixed a literal NUL byte committed in `RequestValidationTest`, which made git treat the file as binary and its diffs unreviewable. ADR-0015 recorded. 373 hermetic tests, 152 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-025`. One important finding, the same defect class this epic's previous task closed one layer in: **a constraint declared on a method parameter was not a 422**. Probing found one class of failure reported three different ways depending only on where the constraint sat - a request body gave 422, a method parameter gave 400, and a method parameter under `@Validated` gave **500**. A client cannot write error handling against that, and the 500 is the worst: it says our side failed for something only the caller can fix. Spring validates these on three separate mechanisms and only the first was mapped. **The first fix was dead code**: overriding `handleHandlerMethodValidationException` compiled, read correctly, and never ran - that exception extends `ResponseStatusException`, so the base class dispatches it through a more general branch and it reaches `handleExceptionInternal` with 400 already chosen. The mapping now sits in that funnel, which every framework error provably passes through, plus a handler for the proxy path Spring does not cover at all; removing either fails the test. It was caught only because **the isolated run and the full suite disagreed** - which mechanism Spring picks depends on whether any bean in the context is `@Validated`, so a single-class run and a whole-suite run genuinely exercise different code. Correlation assertion also strengthened: 5 of 5 mutations caught after \"does not contain the original\" was found to pass for a *sanitised* value. **Process failure**: a fix was reported complete while not present in the commit. Verified now by grepping `git show HEAD:` rather than the working tree, and `git checkout` on a path is no longer used to revert a probe - a scratch copy is. 352 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TSK-025` complete. Declarative validation rejecting before any domain invocation - asserted by **counting handler entries**, because a 422 returned after the handler ran and did half the work looks identical from outside. Rendered 422 rather than the 400 Spring defaults to, keeping the distinction the error contract makes between a wrong serialiser and wrong data. **`api.PayloadTooLarge` made real**: a JSON body is streamed with no default bound, so an unbounded request body was a denial-of-service vector costing an attacker one connection - and a limit that only reads `Content-Length` is one a caller opts out of by sending chunked, so the body is bounded by a counting stream as well. Two findings while building it: **a filter cannot throw its way to the error contract**, since `@ExceptionHandler` is a dispatcher mechanism and a filter runs outside it, so the filters render the contract themselves; and the test context declared its own `@SpringBootApplication`, which scanned only `com.finapp.app.api` and missed the composition root - it now uses the real application. Also closes the ingress correlation filter recorded as debt: every response carries an identifier in the body and in `X-Correlation-Id`, the scope wraps error handling, and an untrusted inbound header is **replaced rather than sanitised** - a silently rewritten identifier breaks the client's own correlation without telling anyone. Five of five mutations caught after one round exposed a weak assertion: \"does not contain the original\" is satisfied by a sanitised value. 350 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-024`. One important finding, from probing error paths the tests had not: **a missing query parameter and a wrong-typed path variable both returned `500 api.InternalError`**. Unambiguous client mistakes reported as platform failures - a client may retry a 500 forever on a request that can never succeed, and a spike of malformed requests is indistinguishable from an outage on every error-rate dashboard. The catch-all was swallowing a whole family of Spring's web exceptions. Two fixes failed before the third worked, and the sequence is the lesson: enumerating exception types fixed the ones I had thought of; testing for Spring's `ErrorResponse` interface fixed the missing parameter and still missed the type mismatch, which does not implement it. **The set of framework exceptions is Spring's to define, so the mapping from exception to status has to be Spring's too** - extending `ResponseEntityExceptionHandler` routes every one through a single override with the status already decided, and a future Spring version's new exception routes there as well. Removing that base class now fails five tests. Added `api.NotAcceptable` (406) to complete the mapping. **Process failure, fifth occurrence**: `git checkout --` destroyed the uncommitted rewrite while reverting a probe. Rewritten and committed before probing again. 339 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TSK-024` complete; **`P0-EPIC-08` and milestone M0.4 opened**, and the platform has its first outward-facing surface. RFC 9457 problem details on every error path - and the paths worth the work are the four the framework raises **before our code runs**: an unknown route, an unsupported method, an unparseable body, an unread media type. Left alone, each answers in Spring's own shape, so a client sees two error formats depending on how far into the request it got, and nothing notices because each looks reasonable alone. Tested over **real HTTP** rather than MockMvc, because MockMvc does not run the container's error dispatch and would have reported a clean contract for paths that return the framework's `/error` body in production. Two defects found by running it: **the wire format was an accident of the serialiser** - the platform record serialised directly produced `\"correlationId\":{}`, the identifier a client is meant to quote silently absent while its member was present, and `\"detail\":null` for absent members; fixed with an explicit wire record so a field added to the contract can no longer publish itself to every client. And **a correlation scope entered in a controller closes before the error handler runs** - the same shape as the relay defect, and now a recorded requirement on the ingress filter. `ApiException` keeps the log message and the client detail in separate fields so the unsafe default is unreachable rather than discouraged. 339 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-006`. No critical or important findings. The ordering guard was probed and is load-bearing - removing the `applied_sequence < EXCLUDED.applied_sequence` clause fails the two tests that depend on it, so neither is vacuous. One code-quality fix: the probe handlers read ambient state - a `ThreadLocal` sequence and a static mutable transfer id - which is fragile and, more to the point, models something no consumer does. `InboxConsumer.Handler` receives only the unit of work precisely because the caller has already deserialised the message, so the handlers now close over their message as a real consumer's would, and the test reads as the usage pattern it is meant to document. **Process note**: `git checkout --` destroyed the uncommitted refactor while reverting a probe, for the fourth time in this project. The remedy that works is the one already known - commit before probing - and it is recorded here rather than resolved to be remembered. 316 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TST-006` complete; **`P0-EPIC-06` and milestone M0.3 closed**. Duplicate delivery was already covered; **ordering was covered nowhere**, and `EVENT_ARCHITECTURE.md` made three claims about it that existed only as prose. The sharpest is now executable: an order-dependent handler is **still wrong under the inbox**. The handler everybody writes first receives `TransferCompleted` then `TransferInitiated` and ends up believing a finished transfer is still in flight - nothing failed, nothing retried, no duplicate occurred, the inbox did its job perfectly, and the projection is wrong anyway. An ordering key fixes it on the identical deliveries, with a positive control because a handler that ignored every second message would otherwise pass. Retention was made executable too: deleting a dedupe record - what a sweep running earlier than the producer's redelivery window does - makes the same message run twice with nothing reporting it, which is what `DATA_MIGRATIONS.md` §9 means by a correctness bound. The acceptance criterion was demonstrated against the live database: dropping the inbox primary key fails **nine tests across three classes**, then restored. Run through the application role, so the inbox's narrow grant is proven sufficient for real consumer use. 316 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-005`. One important finding: **the killed-instance test did not prove what it claimed**. Its comment said terminating a backend showed the relay's advisory lock had to be transaction-scoped - but killing a backend releases session-scoped locks just as thoroughly, and switching the relay to `pg_try_advisory_lock` passed all 145 database tests. The claim in the relay's own javadoc was therefore unverified. Closed by a test that models a **connection pool** rather than a crash: a source handing out one physical connection whose `close()` does nothing, which is what a pool does and the only case where a session actually survives the cycle. A session-scoped lock now fails exactly that test, and the comment on the killed-instance test says what it does prove. Two minor fixes: `pg_terminate_backend` returns whether it worked, and counting rows rather than successes would have asserted recovery from a crash that never happened; and a second event for one aggregate collided with the probe table's primary key. **A flake class removed**: three tests looped a fixed number of relay cycles assuming each would land an attempt, which holds only while the row is due when the poll runs - and the local clock steps backwards. They failed about one run in twenty, never the same test twice. All now loop on the state they are waiting for. 30 consecutive green runs. 316 hermetic tests, 146 database tests. |
| 2026-09-01 | `P0-TST-005` complete. `OutboxCrashRecoveryTest` joins the whole chain - business fact, outbox row, relay, publisher - which no existing test did: the writer's tests end at the row and the relay's crash test covers dying *after* publishing. The scenario in between is the one the outbox exists for, and the one where a mistake is invisible. Two properties are new. **A killed instance does not strand its aggregate**: the backend is terminated with `pg_terminate_backend` while it holds the advisory lock and an open transaction, and a surviving instance publishes the event - which is what makes the transaction-scoped lock load-bearing rather than stylistic, since a session-scoped lock on a pooled connection would outlive the code meant to release it and stop that aggregate forever. And **the criterion is asserted at the relay**, not the writer: a missing row is a fact about storage, an announcement nobody can retract is the consequence. Run through the application role, so `V008`'s outbox grants are exercised rather than assumed. The criterion was demonstrated by moving the write onto its own connection - three tests fail. One test initially caught that mutation only sometimes, because it asserted what the publisher saw and so depended on the surviving row being *due*, which the local clock's backwards steps decide; restated as \"the row must not exist\", it catches it every run. 316 hermetic tests, 145 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-007`. One important finding, and it is the same shape as the defect the task itself closed: **the new column-privilege check was vacuous when its query saw nothing**. `isSubsetOf` over an empty set is trivially true, so a renamed table, a typo or a changed catalogue view would have left it green while checking nothing - proven by making the query return empty and watching it pass. A guard written to catch a blind spot had one of its own. Closed by asserting the query sees something, which is reliable because PostgreSQL expands every table-level grant into `column_privileges`, so non-empty is the normal state. Also verified rather than asserted: **the self-maintaining claim**. A column added as a future migration would add it, with `UPDATE` granted on it, fails both tests with no edit to any test - which is what \"covered without anyone remembering\" has to mean to be worth writing down. The `hasSizeGreaterThan(5)` vacuity guard was replaced with named columns, since a count is satisfied by a query returning the wrong table. Recorded a limit rather than a gap: immutability is enforced against the **application** role; the migrator owns the table and can alter it, which is a privileged-access concern for Phase 15 and is what ADR-0010 claims - that the application cannot alter its own audit trail. 316 hermetic tests, 141 database tests. |
| 2026-09-01 | `P0-TST-007` complete; `P0-EPIC-07` closed. The task looked already satisfied - `P0-TSK-022` had shipped an immutability test covering `UPDATE`, `DELETE`, `TRUNCATE`, `DROP`, `ALTER` and self-granting - and checking rather than assuming found its acceptance criterion **false for one kind of widening**. PostgreSQL can grant a privilege on a *column*, and a column grant does not appear in `information_schema.table_privileges` at all: `GRANT UPDATE (reason)` let the application role rewrite a committed audit record's justification - `'original reason'` became `'rewritten after the fact'` - **while the entire audit suite passed green**. `INV-HIST-03` violated, undetected, because the existing update test happened to set `outcome` and the grants test read a view column grants do not reach. `reason` is the worst column to lose: it is the justification for a privileged action. Closed in two places - `UPDATE` attempted on every column with the list read from the catalogue so a future column is covered automatically, and column-versus-table privilege comparison for **every** platform table, since the inbox's deliberate lack of `UPDATE` had the identical hole and a blind spot found in one place is a blind spot everywhere. Both widenings now fail the suite; before this task the column-level one failed nothing. 316 hermetic tests, 141 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-023`. One important finding, and it is the same defect this project already fixed once: **the catalogue was not a declared Gradle input**, so editing it left `:app:test` `UP-TO-DATE` and the build went green over a document the guard never opened. Proven by breaking the catalogue and watching the build pass. `P0-DOC-002`'s review found exactly this for `MODULE_ARCHITECTURE.md` and its build-file comment even names the failure - *\"a check that reports success for work it did not do\"* - which did not generalise on its own to a second document-backed guard. Declared, and the comment now says a third guard needs a third line. A minor finding alongside it: the catalogue was located by a path relative to an assumed working directory, which works under Gradle and breaks in an IDE with a failure reading as a missing document rather than a misconfigured test; it now walks upward like its sibling. Also confirmed a missing catalogue fails loudly rather than passing vacuously. 316 hermetic tests, 138 database tests. |
| 2026-09-01 | `P0-TSK-023` complete. The auditable-action registry, which `INV-AUD-01` names as half of its enforcement and `V009` already assumed existed. The shape that cannot be built is the obvious one - a single enum in the platform listing every action - because actions belong to the modules that perform them and the platform sits below every business module; an enum here naming KYC's actions would invert the dependency. So `AuditableAction` is an interface, each module declares an enum, and only `app` sees the whole set. **The registry is enforced by the type system, not by review**: `AuditRecord.operation` is an `AuditableAction`, so an action outside the registry cannot be recorded at all. The catalogue and the code are reconciled in three directions and each was proven by planting the fault - an action declared but not catalogued, one catalogued but not declared, and a `requiresReason` flag that disagrees. That flag closes the question `V009` deferred: it made `reason` nullable saying \"the domain decides which those are\", and the registry is where the domain decides. The registry's **limit** is documented rather than glossed - it cannot detect a privileged action that writes no record at all, and a registry that looked complete while the calls were missing would be worse than none because it would be believed. Recorded as debt: the three declared platform actions are not yet emitted. 316 hermetic tests, 138 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-022`. One important finding, in the file that is hardest to test because it runs once: **the role-provisioning script hardcoded the database name** while `compose.yaml` parameterises it as `${FINAPP_DB_NAME:-finapp}` and the README documents it as overridable. The failure is not graceful - a `GRANT` naming a database that was never created is an error, `ON_ERROR_STOP` aborts initialisation, and the container exits 3 complaining about a name nobody typed. Reproduced by starting PostgreSQL with `POSTGRES_DB=altdb`, fixed with `current_database()` and `format()`, and verified against both the default and an overridden name. Also added: an escalation test (the application role cannot `SET ROLE` to the migrator, create roles or databases, `COPY TO PROGRAM`, or read `pg_authid` - if it could assume the owning role every grant below it would be decorative), and the concurrency test `DOD-KERNEL` requires, whose subject is an **absence**: audit writes must not serialise, because an audit write is on the critical path of every privileged action and anything making two contend would put a lock in front of the whole platform and present as latency rather than failure. Two deferrals recorded as debt - audit retention/archival and the four-eyes approver column. 307 hermetic tests, 138 database tests. |
| 2026-09-01 | `P0-TSK-022` complete. The audit trail, and with it **the database role split that had been documented and deferred since `P0-TSK-005`**. Roles are cluster objects, so they are provisioned by infrastructure and only their grants live in migrations - a migration creating a role would claim an object outside its schema, break against the scratch databases CI creates, and need the migrator to hold `CREATEROLE`. Both roles are `NOSUPERUSER`, which is the load-bearing part: a superuser ignores every permission check, so running the application as one does not weaken these invariants but makes them **untestable**, and pointing the app credentials at the superuser now fails all seven immutability tests including the precondition that detects it. `TRUNCATE` is asserted separately from `DELETE`, being a distinct privilege that \"we denied DELETE\" reasoning misses. `V008` pays the grants `V002`, `V005` and `V007` each promised, and the granted set is read from the catalogue per table so a too-wide grant fails as loudly as a too-narrow one. **One test was asserting the wrong thing**: a self-`GRANT` does not raise - PostgreSQL warns \"no privileges were granted\" and returns success - so the assertion checked the database's error-reporting choice while the boundary held; it now checks the outcome instead. Teeth proven by granting `UPDATE, DELETE` on the live table: four tests fail. The correlation guard fired for the fourth time and closed the audit sink `P0-TST-003` named. 307 hermetic tests, 136 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-021`. No critical or important findings; three minor ones, all fixed. A **duplicate arriving inside one transaction** - an entirely ordinary poll batch - was untested, and it takes a different database path from a redelivery: the unique violation is raised immediately rather than after blocking, so the savepoint rather than the lock timeout is what keeps the caller's transaction usable. Probed, found correct, and made permanent. `messageType` was validated only in the store, so a caller got the error from three layers down; it is now checked in the wrapper too, **before** the ambient-correlation lookup, so a caller that got both wrong is told about the argument it passed rather than the context it did not establish. Two deferrals recorded as debt with owning phases - inbox retention sweep and inbox metrics - noting that for retention the risk runs only one way: a record never swept deduplicates forever, and it is early expiry that admits a duplicate. 295 hermetic tests, 109 database tests. |
| 2026-09-01 | `P0-TSK-021` complete; `P0-EPIC-06` closed. The inbox: a dedupe record written in the same transaction as the side effect, so it exists if and only if the effect happened. Keyed on **(consumer, dedupe_key)** - scoping to the consumer is the decision that matters, because keying on the message alone lets the first consumer silently suppress every other one, and that defect looks like success until somebody notices months later that a notification never arrived. No state machine, deliberately: an idempotency record needs `IN_PROGRESS` because a caller is waiting to be told something, and nobody waits on a redelivered message. Contention is reported after a 500ms bound rather than waited on, because losing the race costs one redelivery that the broker was going to perform anyway - a trade available to a consumer and not to a command. **An API defect found by using it**: handed an auto-commit connection the store failed with \"could not create a savepoint\", an error about a mechanism rather than about the mistake, when what would actually happen is the dedupe record committing alone and the message being lost; it now refuses auto-commit and says why. Six of six mutations caught after one round exposed a weak assertion - \"the handler did not run\" was checked by counting effects after a rollback, which cannot tell that apart from \"ran and was undone\"; invocations are now counted in memory. The correlation guard fired for the third time and forced the inbox row to be asserted as the fourth sink. 295 hermetic tests, 107 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-020`. One important finding, and it was in the half of the code nobody reads until something is wrong: **every failure log line lacked its correlation identifier**. The correlation scope wrapped only the publish, and a `catch` attached to a try-with-resources runs *after* the resource closes — so the publication-failure warning, the blocked-aggregate warning and the abandonment error, the three lines an operator actually reads, could not be joined to the transfer or payment whose event they concerned. Proven by reading a real Logback appender (three of four new assertions failed), then fixed by scoping the whole per-event handling. Also closed: `P0-TSK-020` was never marked complete in `BACKLOG.md`; ADR-0005 requires a documented poison-message procedure and none existed, though abandonment stalls an aggregate until a person acts — now written, including that an abandoned row is never resolved by deleting it, since the row is the only evidence the gap exists; the `last_error` bound is duplicated between Java and SQL with no test that a maximal error is storable, so a tightened constraint would have made *recording* a broker failure fail; and the advisory-lock namespace had no register. Four deferrals recorded as architectural debt with owning phases — broker adapter, outbox retention, relay metrics, dead-letter tooling — none of them financial-correctness debt. Migrations verified against a from-scratch empty database. 291 hermetic tests, 88 database tests. |
| 2026-09-01 | `P0-TSK-020` complete. The outbox relay: every instance polls, and a **transaction-scoped advisory lock per aggregate** is what makes ordering survive more than one of them. The usual pattern - `SELECT ... FOR UPDATE SKIP LOCKED` - locks rows, so two instances can take events 1 and 2 of the same aggregate and publish them in whichever order finishes first; ordering would hold only while the relay happened to be running singly, which is the assumption ADR-0014 exists to remove. Delivery is at-least-once and is said so: a publisher that delivers and then dies leaves the event unmarked, and the restart delivers it a second time. Ordering under failure is asserted separately from ordering on the happy path, because the two are different properties and only the second is easy. An abandoned row **blocks** its aggregate rather than being skipped - a stall is loud, an undetectable gap in a financial event stream is not. `V006` puts eligibility and abandonment on the server's clock, applying the V004 lesson before it could bite again. Two implementation traps recorded: JDBC **commits** when auto-commit is restored, so a tidy `finally` would turn every error path into a commit; and `RetryPolicy`'s default claimed forty minutes of retrying where the ceiling made it eight, so the test now pins the window rather than the attempt count. No Kafka adapter, deliberately - the relay publishes through a port, and `nothingPublishesToABrokerDirectly` still exempts nothing at all. 291 hermetic tests, 83 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-019`. Two findings, both from probing. **The byte-exactness assertion could not detect its own loss**: re-encoding the payload through `new String(bytes).trim()` survived every test, because every payload chosen — `{}`, a short JSON object, `{1,2,3}` — happens to be unchanged by a trim-and-re-encode. The payload is now deliberately hostile to it: leading and trailing whitespace, a NUL, and a byte that is not valid UTF-8. A relay must publish what the producer wrote, not a round-trip of it. **All ten of `V005`'s constraints were unexercised** — the same gap the `V002` review closed for the idempotency table. Most cannot be reached through the writer at all, since the envelope validates bounds and versions before SQL sees them and nothing writes `attempts` or `published_at` until the relay, which is exactly the argument for testing them at the schema: a constraint application code cannot reach is one only the database will ever enforce, against an operator or a writer nobody has written yet. `OutboxEventSchemaTest` added; both fixes proven by mutation. 283 hermetic tests, 63 database tests. |
| 2026-09-01 | `P0-TSK-019` complete. `platform.outbox_event` carries the full envelope as columns, all ten NOT NULL, and the writer never opens a transaction of its own — so `INV-EVT-01` holds by construction rather than by intent. Proven both ways: a rolled-back fact loses its outbox row and a committed one keeps it, because a rollback-only test would pass against a writer that never wrote anything. A failed write raises rather than logs, since a fact committed without its publication record is a lost event nobody can detect afterwards. `nothingPublishesToABrokerDirectly` enforces the second acceptance criterion, matched by package name so the rule exists before the dependency does and covering method references; proven by planting a direct publish in production code. `V005` records why the relay must select unpublished rows rather than a sequence watermark — allocation happens at insert and visibility at commit, so a watermark relay skips rows permanently. Both self-maintaining guards fired as designed, and the sink guard forced correlation propagation into the outbox row to be asserted, closing one of `P0-TSK-014`'s deferred clauses. 283 hermetic tests, 56 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-018`. All eight mutations of the envelope were caught, including reordering two fields of the canonical form and having an emitted event inherit its parent's cause rather than being caused by the event emitting it — so the two reflection-derived tests are load-bearing rather than merely clever. Three minor findings, all fixed: `correlationForEmittedEvent` used a fully-qualified type name twice where an import sat two lines above; a name at exactly `MAX_NAME_LENGTH` was untested, so an off-by-one to `>=` would have silently rejected a legal name (proven, then closed); and `EventId.of(String)` — the path a received message header takes — had no test that a v4 or a malformed value is refused. 279 hermetic tests, 47 database tests. |
| 2026-09-01 | `P0-TSK-018` complete. `EventEnvelope` enforces `INV-EVT-03` at construction — all ten fields mandatory, so an event that could not be traced cannot be built — and carries metadata only, so relays and consumers can handle events they cannot deserialise and no log line can spill event contents. A boundary conflict had to be resolved first: the architecture places the envelope in `sharedkernel` in three places, but the correlation identifiers it carries lived in `platform` and the shared kernel may not depend upward. Resolved by the general rule rather than a workaround — value types sit below the mechanisms that move them, so the identifiers moved down and `CorrelationContext` with its MDC dependency stayed. `EVENT_ARCHITECTURE.md` listed `eventVersion` and `schemaVersion` without defining either; both are now defined and distinguished, and `schemaVersion` leads the canonical form because a consumer that cannot parse an envelope cannot read the field telling it which layout to expect unless that field never moves. No wire format: that would commit the shared kernel to a serialisation library, and it belongs to the outbox. 277 hermetic tests, 47 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-004`. One important finding, in the test whose headline claim is that it relies on no timing luck: the losers used the default three-second claim wait while the winner held its claim until the lock-wait poll finished, so on a slow machine the losers would time out first and the test would fail for a reason unrelated to what it asserts. The losers now wait far longer than they can need — bounding the wait is a different test's subject and must not be this one's constraint. The lock-wait observation itself was verified real by pointing the losers at a different key and watching the test time out rather than pass. Two mutations survive this suite — the lease condition and the fingerprint check — and both were confirmed caught by the full database suite rather than assumed to be; neither is this suite's subject. Five consecutive full runs green. |
| 2026-09-01 | `P0-TST-004` complete. Five failure modes from `CLAUDE.md` §Failure Engineering driven directly at the idempotency kernel: observed contention, contention outlasting the bounded wait, a response lost after commit, expiry on both sides of the retention sweep, and an instance crashing mid-command. The acceptance criterion "no test relies on timing luck" is met by waiting until PostgreSQL reports the losing sessions waiting on a lock rather than by sleeping — a latch makes threads *begin* together but the winner may finish first, so the test would pass without exercising contention at all. "Test fails if the unique constraint is dropped" demonstrated against the live database: 17 failures, then restored. Recorded that V003 freezes a terminal claim entirely, so retention cannot be extended after completion. `P0-TSK-017` recorded as `BLOCKED` on `P0-TSK-023` and on the HTTP surface `P0-EPIC-08` brings in M0.4. |
| 2026-09-01 | **Multi-instance execution made an explicit architectural requirement** — ADR-0014, [`DISTRIBUTED_EXECUTION.md`](../architecture/DISTRIBUTED_EXECUTION.md), and a new §Multi-Instance Execution in `SYSTEM_ARCHITECTURE.md`. It had been implicit: ADR-0004 and ADR-0005 both depend on it without naming it, and nothing said how many copies of the monolith run. An audit of all production code found it mechanically clean — no locks, schedulers, caches or static mutable business state — and **one real defect**: `P0-TSK-016`'s claim reclaim compared `created_at` written by one instance's clock against a staleness bound computed from another's. An instance running six minutes fast with a five-minute lease would consider every neighbour's fresh claim abandoned, take the key, and run the command while the neighbour was still running it — two financial effects for one request. It passed every test because they all ran in one JVM with one clock. Corrected by `V004`: the lease is set and judged by the database's clock, the client-side staleness predicate is removed rather than kept as a fast path, and a test gives the second instance a clock an hour ahead. The no-floating-point rule then caught a `double` on the new lease path, which was the right call. Remediation recorded as `P0-TSK-041` (architecture rule) and `P0-TST-009` (multi-instance test convention). ADR-0001 is unchanged: one deployable is not one instance. 268 hermetic tests, 41 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-016`. One important finding, from checking a claim rather than reading it: the wrapper's javadoc said the blocking wait was "bounded: see `lock_timeout`" and `lock_timeout` existed nowhere. The claim was false — a duplicate blocked for as long as the first command took, which on a hot key with a retrying client is connection-pool exhaustion, the exact failure the class argues against two paragraphs earlier. ADR-0004 requires a bounded wait then conflict. Closed: the store now bounds the claim with a `lock_timeout` confined to that statement, and `claim()` returns `CLAIMED`/`ALREADY_CLAIMED`/`CONTENDED` because a contended claim has nothing to read — the holder may still commit or roll back — so it is honestly reported as unknown. The test asserts both ends of the bound, since an upper bound alone would pass if the claim failed instantly for an unrelated reason. A mutation sweep then left two survivors, both closed: the store's `state = 'IN_PROGRESS'` guard on recording an outcome, and `isStaleAt`, which survived only because the reclaim statement re-checks staleness in SQL — defence in depth working, and precisely why the Java predicate needed its own test, since together the two mutations would re-run a live command. 272 hermetic tests, 40 database tests. |
| 2026-09-01 | `P0-TSK-016` complete. The execute-once wrapper: claim, run, record, replay — all inside the caller's transaction, so no crash can leave a financial effect that no idempotency record describes. All three acceptance clauses proven against a real PostgreSQL, with "exactly one effect" counted in a side-effect table rather than inferred from the wrapper's own return value. A live `IN_PROGRESS` claim is reported rather than waited on or assumed failed; a stale one is taken over with the staleness test in the database, so two racing reclaims cannot both win. The fingerprint is compared before staleness, so a different request never inherits a key. The store is a port, leaving unresolved question 12 open. Two fixture findings: a `TEMPORARY TABLE` is session-local and so invisible to the racing connections, and `IdempotencyKey` had to become `Serializable` or the exceptions lose their diagnostic state — the `P0-TSK-009` defect again. 268 hermetic tests, 38 database tests. |
| 2026-09-01 | `P0-TST-003` complete; `P0-EPIC-04` and milestone M0.2 closed. The task had been recorded as blocked until M0.4, which was too pessimistic: a second sink already existed, since `platform.idempotency_record` carries `correlation_id NOT NULL`. One request's identifier is now proven identical in the log and in a committed row across a thread handoff, for both an accepted and a generated identifier, with a negative control showing an unwrapped handoff loses it. The three sinks that genuinely do not exist are handled by `CorrelationSinkCoverageTest`, which derives platform concerns from the build output and fails when one appears unclassified — proven by adding an `outbox` package. The four-sink criterion is enforced as the sinks arrive rather than left to memory. 261 hermetic tests, 28 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-015`. All nine constraint mutations were caught, and so was the enum/migration drift guard. One real gap the sweep could not reveal: a `CHECK` constraint sees only the row being written, so V002 constrained row *shape* and said nothing about *transitions*. Probing the developer database with the statement an operator or a defective wrapper would run — `UPDATE ... SET state='IN_PROGRESS', completed_at=NULL` — turned a finished command back into an unfinished one, which a wrapper would then re-execute: a second financial effect from an UPDATE no application code performed. Closed by `V003`, a `BEFORE UPDATE` trigger freezing terminal claims entirely and making identity and fingerprint immutable in any state; both halves proven by isolated mutation. A second finding was a test artefact worth keeping: mixing a client-generated `created_at` with PostgreSQL's `now()` for `completed_at` produced a backwards row, because the container's clock runs behind the host's — which is why these timestamps are application-supplied from one injected clock and the schema declares no `DEFAULT now()`. 259 hermetic tests, 25 database tests. |
| 2026-09-01 | `P0-TSK-015` complete — the platform's first table. `INV-IDEM-01` enforced by a unique key on (scope, idempotency_key) and proven under 16-way contention against a real PostgreSQL: exactly one winner, every loser a unique violation. The state machine is checked in the schema as well as in code, the fingerprint's algorithm is recorded on the record (`INV-HIST-04`'s rule applied to the thing that decides whether two requests are the same), and the response is stored as bytes so a retry receives what the first caller received. Established that this table is legitimately mutable and so not gated on the `P0-TSK-022` privilege split. `flywayValidate` caught a checksum mismatch when the migration was edited after being applied locally — the rule working; repaired, then verified against an empty scratch database. Expiry policy documented in `DATA_MIGRATIONS.md` §8, including why too-short expiry costs money and too-long costs storage. 259 hermetic tests, 20 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-014`. All eight mutations of the correlation kernel were caught, including `InheritableThreadLocal`, which fails through the unwrapped-task path — so the claim the design rests on is genuinely tested rather than merely argued. Two gaps the sweep could not reveal, both fixed: the token charset used `String.matches`, recompiling the expression on the ingress path of every request; and the MDC key names are documented as a published contract that log queries and dashboards are written against, yet every test used the constants, so renaming one would have been a compile-safe refactor that silently broke every dashboard. Literals now pinned. Also recorded that `propagate` returns an `Executor` rather than an `ExecutorService`, so a caller needing `submit` wraps the task — no speculative decorator written. 255 tests. |
| 2026-09-01 | `P0-TSK-014` complete for what can be verified now. Correlation and causation modelled as distinct types, validated as untrusted input against log injection, with a context that survives an async handoff and provably does not leak between tasks on a pooled thread — the failure `InheritableThreadLocal` would have introduced. Log lines proven to carry the identifier by reading a real Logback appender. **The acceptance criterion could not be met as written**: it names a trace, an emitted event and an ingress filter, and the exporter, outbox, audit store and HTTP surface all arrive in later milestones. Backlog corrected and the clauses transferred to `P0-TST-003`, which is recorded as blocked until M0.4. `slf4j-api` added to `platform` (facade only). 254 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-013`. Three findings in the rule itself, all from probing rather than reading. **`Instant::now` as a method reference bypassed the rule entirely** — a method reference is an `invokedynamic`, not a call, so `getMethodCallsFromSelf()` never sees it; closed with `getMethodReferencesFromSelf()`, and a rule one syntax away from being bypassed is not enforcement. **`TemporalAdjusters.firstDayOfNextMonth` was forbidden and should not have been** — it is applied to a date the caller already holds and reads nothing, so the rule was pushing people off a correct API, exactly the failure its own javadoc warns about. **Ambient *zone* was not forbidden**, though `instant.atZone(ZoneId.systemDefault())` makes which date an instant falls on depend on server configuration — a dating defect no amount of clock injection prevents. Also removed a dead `java.sql.Timestamp` entry that could never match. All four re-probed, including two positive controls proving the allowed APIs stay allowed. 219 tests. |
| 2026-09-01 | `P0-TSK-013` complete. Two ArchUnit rules make ambient time a build failure, with `Clock.systemUTC()` permitted in the composition root alone. The rules immediately caught a violation written in the previous task — `IdGenerator.systemDefault()` — which was removed rather than exempted. `Instant.now(clock)` is deliberately allowed, since forbidding the clock-taking overloads would push people off the correct API. `TestClock` replaces an inline test clock and makes `rewind` a named operation, because NTP correction moves real clocks backwards. `DOMAIN_MODEL.md` §Time records the posting-date/value-date/system-time distinction, which no rule can enforce. The `P0-DOC-002` documentation-equivalence check failed the build until the new rules were documented, one task after it was written. 219 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-012`. A mutation sweep over `IdGenerator` and `EntityId` found one real gap: deleting the RFC-variant check from `EntityId` survived every test, because every rejection case in the suite already failed the *version* check first, so the variant branch was never reached. A version-7-but-wrong-variant value claims to be time-ordered while not being an RFC 9562 UUID, and its high bits would be read as a timestamp on the strength of a version field nothing corroborates. Case added; the mutation now fails. Two other mutations survived and are correct to: `hashCode` dropping the class component violates no contract (`equals` still distinguishes), and making `EntityId` final is caught at compile time rather than by a test — my probe harness reported it as surviving because it parsed stale results without checking the exit code, which is the same defect shape these reviews keep finding, this time in the probe rather than the code. 213 tests — the case was added to an existing rejection test rather than as a new one. |
| 2026-09-01 | `P0-TSK-012` complete; ADR-0013 recorded. Typed aggregate identifiers over UUIDv7. The task named `CustomerId` and `AccountId`, but those are business nouns owned by `party` and `accounts` and the shared kernel forbids them, so the kernel holds the mechanism and the compile-error criterion is proven with probe types — by invoking `javac` on the substitution, since a compile error cannot be asserted at run time. Monotonicity is engineered rather than inherited from the clock: a counter rather than randomness in the 12-bit field, borrowing the next millisecond on exhaustion, and no regression when the clock jumps backwards. The restart test makes explicit that cross-instance uniqueness rests on the 62 random bits, not the counter. 213 tests. |
| 2026-09-01 | `P0-TST-002` complete, closing `P0-EPIC-03`. The criterion's naive-division clause was already satisfied — that mutation is caught by eight existing tests — so the work was the untested range: 1..100 parts rather than 1..40, and amounts from the whole representable range rather than a band around zero. Both sweeps assert they encountered indivisible remainders, because zero residual is trivially true on divisible amounts and a sweep of those would pass over a broken allocator. Evenness asserted separately: an allocator dumping the whole remainder on the first part satisfies totality and is caught only by that. |
| 2026-09-01 | `P0-DOC-001` complete. `README.md` covering prerequisites, build, test, infrastructure lifecycle, migrations and CI gates. Verified by cloning the repository into a temporary directory and running every documented command in order, with infrastructure stopped first so the hermetic-build claim was tested rather than asserted. One inaccuracy found and corrected: `toolchainInfo` reports the launcher JVM, not the compile toolchain. Closes `P0-EPIC-01` and completes every item in milestone M0.1; only "green in CI" remains, blocked on the absent git remote. Milestone pointer corrected from M0.1 to M0.2, which the last five tasks had already been working in. |
| 2026-09-01 | Task completion review of `P0-TST-001`. A mutation sweep over `Money` found three surviving mutants, two of them real gaps. Reversing `compareTo` survived every ordering property — antisymmetry, transitivity and consistency with `equals` are all satisfied by a comparator running backwards, so the properties described its shape but never its orientation; closed by stating the orientation against `BigDecimal`'s own ordering. Deleting the scale comparison from `equals` also survived, because the generator built every amount with `ofMinorUnits` and so never varied scale at all — an entire dimension of `Money`'s state was invisible. Generation is now scale-aware, with a new property asserting same-currency/different-scale operations are rejected and that such amounts are not equal. The third mutant, `hashCode` ignoring currency, survives correctly: `hashCode` may collide. 190 tests. |
| 2026-09-01 | `P0-TST-001` complete. `MoneyPropertiesTest` asserts Money's algebraic laws over generated values, where the existing coverage was example-based — commutativity had rested on a single triple of small positive amounts. Each law asserts its own coverage so it cannot pass by rejecting everything. The rounding properties initially failed the acceptance criterion: making every policy round `CEILING` passed, because the `FLOOR`/`CEILING` bracket was computed through `Money` itself and the break moved the bounds with the value. Replaced with per-policy defining properties; all three deliberate breaks named in the criterion now fail. 188 tests. |
| 2026-09-01 | Task completion review of `P0-DOC-002`. One important finding: rule-suite discovery listed a single directory, so a suite placed in a subpackage ran its rules on every build while escaping the documentation check — enforced but undocumentable, with the equivalence test still green. Proven with a probe suite, then closed by walking the whole test-classes tree (loading classes with `initialize=false`, since deciding whether something is a rule suite must not run its static initialiser). Also confirmed a missing document fails rather than passing vacuously, and that the ten `*(ArchUnit: ...)*` markers all sit in §6 and yield exactly the twelve enforced rule names with no false positives. |
| 2026-09-01 | `P0-DOC-002` complete. Audit of `MODULE_ARCHITECTURE.md` against the enforced rules found six drifts, including a rule enforced on every build that the §6 list did not mention, and two sections still calling `INV-MON-01` unenforced two tasks after it was enforced. Prose fixed, then the equivalence made mechanical: every enforcement claim names its rule, and `ArchitectureRulesAreDocumentedTest` fails the build in either direction. Probing also found `:app:test` staying `UP-TO-DATE` after the document was broken — the document is now a declared task input. Closes `P0-EPIC-02`. 172 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-008`. One critical finding, found by probing rather than reading: the new coverage guard asserted only that `Money` was analysed, so it could not see a whole module falling out of the sweep. Proven by narrowing the sweep to `sharedkernel` — all four floating-point rules reported PASSED and the build exited 0 with a `double` planted in `platform`'s `MoneyColumns`. This is the same weakness the `P0-TSK-007` review found and that the new rule's own javadoc cites as its rationale. Both suites now derive expected coverage from the classpath through a shared `ProductionModules` helper, and the identical probe now fails the build. Also verified end to end that a `float` in a signature and a `Double.parseDouble` call in production code each fail the build — the latter also catching `BigDecimal.valueOf(double)`, the trap beside the safe `valueOf(long, int)`. |
| 2026-09-01 | `P0-TSK-008` complete. `INV-MON-01` enforced statically over all production code — fields, signatures, call targets and field accesses, including generic arguments. Default-deny rather than a list of financial packages, with deliberate-violation fixtures asserting the teeth on every build. Proven end to end by planting a `double` in `Money`. Closes `P0-EPIC-02` and Phase 0 exit criterion 4. 170 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-011`. Probing PostgreSQL showed `CHAR(3)` accepts `'US '` — the column type was not the guarantee the design implied, leaving `INV-MON-02` enforced only by application code against `DEFINITION_OF_DONE.md` §1.3. Added check constraints on the currency pattern and scale range, generated from `Money.MAX_SUPPORTED_SCALE`, and proved them by weakening them. Also switched the not-null assertion from message text to SQLState (messages are localisable), tightened `trim()` to `stripTrailing()` to match its own stated rationale, and made the write path use `MonetaryColumnException` like the read path. |
| 2026-08-31 | `P0-TSK-011` complete. `MoneyColumns` fixes the three-column storage shape from ADR-0003 and supplies the DDL migrations use. Round-trip verified against a real PostgreSQL across 0-, 2-, 3- and 4-decimal currencies and the `BIGINT` extremes. Written mechanism-agnostic: the task asked for a JPA embeddable, but no ADR has chosen a data-access mechanism — recorded as unresolved question 12. |
| 2026-08-31 | Task completion review of `P0-TSK-010`. One important finding: `allocate(int)` and `allocate(long...)` resolved silently by literal width — `allocate(3)` split three ways, `allocate(3L)` returned the whole amount as one part. Proven, then removed by renaming to `allocateEvenly` / `allocateByWeights`, with a test guarding against reintroduction. |
| 2026-08-31 | `P0-TSK-010` complete. `RoundingPolicy` with six named policies, explicit-policy rounding, and allocation that distributes the indivisible remainder rather than absorbing it. Zero-residual proven by sweeping ~160,000 even splits and 2,000 weighted ones, and demonstrated to fail when the remainder is discarded. 127 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-009`. One important finding: the diagnostic state on the monetary exceptions was `transient`, so `left()` and `right()` returned `null` after serialization — proven by round-tripping one, and fixed by making `CurrencyCode` serializable. Also corrected an operand-order inversion in the mismatch message, and added the three tests whose absence let those through: scale mismatch on `minus`/`compareTo`, `absoluteValue` overflow, and serialization of diagnostics. 74 tests. |
| 2026-08-31 | `P0-TSK-009` complete. `Money` and `CurrencyCode` — the platform's first financial code. Integer minor units, explicit currency, stored scale; exact arithmetic only, with cross-currency, cross-scale, inexact-amount and overflow failures all distinct and all under one `MonetaryException` supertype. 70 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-007`. Probing showed ArchUnit was importing exactly one class — benign (it skips `package-info`, which is all `platform` and `sharedkernel` contain), but it exposed that the coverage guard asserted only that `app` was seen and would have passed if a module were dropped from the analysis. Guard replaced with one that derives expected coverage from the classpath, proven by excluding a module that had production code. Also added a rule that production classes must belong to a module package: a class directly in `com.finapp` was silently exempt from every rule. |
| 2026-08-31 | `P0-TSK-007` complete. Six ArchUnit boundary rules enforced on every build, each proven by a deliberate violation; plus a guard test so the suite cannot become silently vacuous. `MODULE_ARCHITECTURE.md` §2 and §8 corrected — they claimed the rules were "not yet in place". |
| 2026-08-31 | Task completion review of `P0-TSK-006`. Four defects found and fixed, all by mechanical checks rather than re-reading: `Instalment` owned by both `lending` and `bnpl` (the headline acceptance criterion, violated); the `app` module absent from the register entirely; `paymentmethods` and `crossborder` missing from the layering diagram; the §5 ownership table maintained as a second enumeration that could drift from §4. |
| 2026-08-31 | `P0-TSK-006` complete. Context-to-module map: 28 contexts to 24 modules, all nine boundary attributes per module, authoritative-state ownership table. Found two missing bounded contexts and one state at risk of two owners. ADR-0012 records the mapping decision. |
| 2026-08-31 | `P0-TSK-004` complete. CI with four gates: build/tests, migrations against real PostgreSQL, secret scan over full history, SBOM dependency scan. Actions SHA-pinned, scanners digest-pinned. Not yet executed on a runner — no git remote exists. |
| 2026-08-31 | Task completion review of `P0-TSK-001`, `-002`, `-003`, `-005`. No critical or financial findings — no money-handling code exists yet. Six important findings fixed: an unsatisfiable `DOD-BUILD` "CI green" requirement caused by over-specified `P0-TSK-004` dependencies; a one-directional infrastructure drift check that let an unpinned image pass (proven, then closed); dead Spring Boot configuration in `build-logic` (proven unnecessary); an unnecessary Spring test stack in `platform` contradicting its own comment; a name-substring scope test replaced with a structural one; ADR-0011 missing from `DECISIONS.md`. Java toolchain version moved into the version catalog, removing four duplicated copies of "21". |
| 2026-08-31 | `P0-TSK-005` complete. Flyway 12.4.0, forward-only, module-owned schema history; ADR-0011 and `DATA_MIGRATIONS.md` written. |
| 2026-08-31 | `P0-TSK-003` complete. Local infrastructure (PostgreSQL 18.6, Kafka 4.3.1 KRaft, Redis 8.10.1), pinned and health-checked, with a build-enforced version-drift check against the catalog. |
| 2026-08-31 | `P0-TSK-002` complete. `sharedkernel`, `platform`, `app` with enforced dependency direction; `sharedkernel` proven Spring-free; `java-library` adopted for `api`/`implementation` boundary control. |
| 2026-08-31 | `P0-TSK-001` complete. Gradle 9.7.1 multi-module build, Java 21 toolchain, Spring Boot 4.1.1 BOM, `build-logic` conventions, checksum-pinned wrapper. Phase 0 `IN_PROGRESS`. Repository placed under Git. |
| 2026-08-31 | Project initiation. Delivery plan, phase gates, backlog, architecture baseline, invariant catalog, Definition of Done, execution protocol and ADR-0001..0010 created. Phase 0 entry gate passed; status `READY`. |
