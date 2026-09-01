# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

Last updated: 2026-09-01

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

**M0.3 — Correctness primitives**
`P0-EPIC-05` (Idempotency Kernel), `P0-EPIC-06` (Reliable Messaging) and `P0-EPIC-07` (Audit
Trail).

Milestone complete when: money-moving commands are idempotent under genuine concurrency; domain
facts and their publication records commit together via an outbox, with an inbox deduplicating
consumers; and privileged actions produce append-only audit records the application role cannot
edit.

**M0.2 — Financial kernel** — `P0-EPIC-03` and `P0-EPIC-04`, both `COMPLETE` (2026-09-01).

**M0.1 — Buildable, boundary-enforced skeleton** — `P0-EPIC-01` and `P0-EPIC-02`, both
`COMPLETE`. Three of its four completion criteria are met; the fourth, "green **in CI** from a
clean clone", cannot be met while the repository has no git remote. A clean clone was verified
to reach a green build locally during `P0-DOC-001`.

Subsequent Phase 0 milestones:
- **M0.4** API, observability, security baseline — `P0-EPIC-08`, `-09`, `-10`
- **M0.5** Test infrastructure and phase review — `P0-EPIC-11`, `P0-EPIC-12`

## Current Task

**`P0-TSK-021` - Inbox dedupe store and consumer wrapper**
Status: `READY` - not started.

Bounded context: platform / data. Depends on `P0-TSK-005` (`COMPLETE`).

It is the other half of ADR-0005 and the reason the relay is allowed to be at-least-once: the
relay guarantees an event is delivered, the inbox guarantees it takes effect once. The dedupe key
is `event_id`, which `P0-TSK-018` fixed at event creation precisely so that redelivery presents
the same value.

Full definition: [`BACKLOG.md`](BACKLOG.md) §P0-EPIC-06. DoD profile: `DOD-KERNEL`.

### Just completed

**`P0-TSK-020` - Outbox relay** - `COMPLETE` (2026-09-01).

| Acceptance criterion | Evidence |
|---|---|
| Relay restart after a crash publishes every committed, unpublished row | A publisher that delivers and then throws an `Error` reproduces a process death between publishing and recording it. The event is delivered, nothing marks it, the transaction rolls back leaving no attempt behind, and a restart delivers it again - **twice in total**, which is what at-least-once means and is stated rather than glossed |
| Ordering preserved per aggregate | Six events published in written order; and, in the case that actually distinguishes the property, a failed event blocks the ones behind it - they are not even attempted |
| Broker unavailability causes retry, never row loss | Three failed cycles leave the row pending with `attempts = 3` and an error recorded; the broker recovers and the row publishes |
| Correct under N instances | Eight instances drain a 36-event backlog and deliver each event exactly once between them; and while one instance is provably inside `publish()`, a second is refused that aggregate and publishes a *different* one |

Design decisions worth carrying forward:
- **The lock is per aggregate, not per row.** `SELECT ... FOR UPDATE SKIP LOCKED` is the usual
  outbox claiming pattern and it silently breaks ordering with more than one instance: A takes
  event 1, B takes event 2, whichever publishes first wins. A transaction-scoped advisory lock on
  the aggregate makes ordering structural rather than a property of how many relays happen to be
  running.
- **An abandoned row blocks its aggregate rather than being skipped.** Skipping is quiet -
  consumers get events 1 and 3 with no way to know 2 existed. A stall is loud, bounded to one
  aggregate, and visible as backlog age.
- **`next_attempt_at` is written and judged by the server's clock.** The V004 lesson applied
  before it could bite again: a schedule compared across two instances' clocks is a race against
  skew. `occurred_at` stays application-supplied, which is why one column carries a `DEFAULT` and
  the other does not.
- **Auto-commit is not restored on the way out.** JDBC commits the open transaction when
  auto-commit is switched back on, so a tidy-looking `finally` would turn every error path into a
  commit. The relay catches `Throwable`, rolls back, and lets the connection close.
- **No broker adapter, deliberately.** The relay publishes through an `EventPublisher` port.
  Writing a Kafka adapter here would decide the wire format, the topic scheme and the producer's
  acknowledgement configuration, and put a broker client on the classpath - four decisions that
  belong with the phase that has events to publish. `nothingPublishesToABrokerDirectly` therefore
  still exempts **no** module, which is the honest state rather than a placeholder.

**A test caught a documentation claim.** `RetryPolicy.DEFAULT`'s javadoc said ten attempts was
"roughly forty minutes of trying". With a five-minute ceiling it is eight and a half. The count
and the window are not independent, and the window is the number that matters operationally - so
the test pins the total, not the count, and the default is now fourteen attempts (~28 minutes).

**Verified by mutation: 9 of 10 caught.** The survivor is the `AND published_at IS NULL` guard on
recording publication, and it survives correctly - while the aggregate lock holds there is no
second writer to lose to, so reaching that guard would require defeating the lock first. It is
kept as defence in depth and its javadoc says plainly that no test covers it, so nobody later
mistakes it for verified behaviour.

Two rounds of the sweep had to be discarded before that was trustworthy. The first harness read
an exit code with no failing-test name and reported a survivor as caught; the second used
`grep -oP`, unsupported in this shell, so it could never report *anything* as caught - and said
so only because the run included an unmutated baseline. A probe without a baseline is a probe
that cannot report its own failure, and this is the third review in which the defect was in the
harness rather than the code.

**Three test defects and one environment finding**, all surfaced by running the suite forty times
rather than once:
- The eight-instance test stopped each instance on *its own* idleness. An instance refused every
  lock has done no work and is not finished; all eight could quit with events still pending - a
  failure indistinguishable from a relay that loses events.
- The blocking-instance tests discarded the held instance's outcome, so a relay that threw, one
  that found nothing and one blocked on a lock all failed with the same unhelpful timeout.
- `shutdownNow()` does not wait, and nothing on the relay's path responds to an interrupt, so a
  simulated instance could outlive its test and publish the next test's rows.
- **The local container clock steps backwards**, making a freshly written row genuinely not yet
  due. The relay was right to decline it; the fixture was wrong to assume otherwise. Recorded
  under Local Environment Prerequisites.

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

None in progress. `P0-TSK-021` is the next task.

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
| `P0-TSK-014` | Narrowing. The **emitted-event** clause is now satisfied: `P0-TSK-019` added the outbox and `CorrelationPropagationTest` asserts a queued event carries the flow's identifier. The **trace** and **ingress-filter** clauses remain unverifiable — no tracing exporter or HTTP surface exists — and close on arrival rather than on memory, because `CorrelationSinkCoverageTest` fails the build when a new concern lands unclassified. | `P0-EPIC-08`, `-09` |
| `P0-TSK-003`, `P0-TSK-005` | Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants (`INV-LED-03`, `INV-HIST-01`, `INV-HIST-03`) cannot yet be exercised. The migrator/application role split is designed and documented (`DATA_MIGRATIONS.md` §5) but not implemented, and must land **before** any table subject to those invariants is created. | `P0-TSK-022` |

---

## Known Architectural Debt

Debt is recorded here as it is deliberately accepted, with: what was deferred, why, what risk it
carries, what triggers paying it down, and the owning phase.

| Deferred | Why | Risk carried | Trigger | Owning phase |
|---|---|---|---|---|
| **Broker adapter behind `EventPublisher`.** The relay publishes through a port; nothing implements it | An adapter decides the wire format, topic scheme and producer acknowledgement configuration, and puts a broker client on the classpath — four decisions belonging to the phase with events to publish. `EVENT_ARCHITECTURE.md` already defers the wire format | **None today.** Nothing produces events yet, so an unpublished outbox is an empty outbox. The relay's own correctness is proven against a publisher that fails on demand, which no real broker does reliably | The first module that emits a domain event | Phase 3 (ledger) |
| **Outbox retention.** Published rows are never deleted | `V005` says a published row may be deleted once retained long enough for diagnosis; the sweep is a scheduled job with its own cluster-safety question, and no task owned it | Unbounded table growth. The partial pending index does **not** grow with it — published rows leave it — so the cost is storage and vacuum, not relay latency | Table size becoming operationally material | Phase 15 (data retention and deletion) |
| **Relay metrics.** `RelayPollResult` is returned but nothing aggregates it | No metrics infrastructure exists (`P0-EPIC-09`, M0.4) | ADR-0005 names outbox depth, age and relay lag as first-class monitored metrics. Until they exist, a stalled aggregate is visible only in logs — which is detection by reading, not by alerting | `P0-EPIC-09` landing | Phase 0, M0.4 |
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

**`P0-TSK-021` - Inbox dedupe store and consumer wrapper**, completing `P0-EPIC-06`.

The relay's at-least-once delivery is only acceptable because this exists: the dedupe record and
the side effect must commit in one transaction, exactly as the outbox row and the fact do. The
dedupe key is `event_id`, fixed at event creation by `P0-TSK-018`.

`P0-TST-005` (outbox crash-recovery test) follows it. Note that `P0-TSK-020` already drives a
crash between publication and its record; `P0-TST-005`'s own criterion is different - it fails
when the **outbox write** is moved outside the business transaction - so it is not covered.

---

## Change Log

| Date | Change |
|------|--------|
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
