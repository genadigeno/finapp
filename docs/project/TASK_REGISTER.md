# Task Register

A reading view of every unit of work on the platform: what it is, in two or three sentences,
and whether it is done.

**This document is not authoritative.** [`BACKLOG.md`](BACKLOG.md) owns acceptance criteria,
dependencies, risk, complexity and DoD profile; [`CURRENT_STATE.md`](CURRENT_STATE.md) owns
where the project actually is. This register deliberately carries **none** of those fields, so
there is nothing here that can silently contradict them — only an identifier, a name, and a
description in prose.

Work below Phase 1 does not exist as tasks yet, and that is a rule rather than an omission:
[`BACKLOG.md`](BACKLOG.md) §Progressive Elaboration Rule decomposes a phase at its own entry
gate, because the tasks for Phase 9 depend on decisions Phase 3 has not made. Later phases are
listed at the depth that is currently knowable.

Legend: ✅ complete · 🔵 next · ⚪ not started · 🟠 blocked

---

# Phase 0 — Domain and Architecture Foundation

**62 of 62 complete.** Phase 0 delivers a buildable, boundary-enforced modular monolith
containing the financial and platform kernel, with **zero business capability**. That
constraint is deliberate: money representation, idempotency, outbox, audit and correlation
cannot be retrofitted once financial history exists.

## P0-EPIC-01 — Build and Repository Foundation

*Nothing can be verified until there is a reproducible, CI-verified build.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-001** — Initialise Gradle multi-module build | A Gradle wrapper verified by SHA-256, a version catalogue as the single source of every dependency version, and a Java 21 toolchain pinned by the build rather than by whatever `JAVA_HOME` happens to be. Spring Boot's BOM is applied for version alignment, but its plugin only to the executable module, so library modules are not forced into `bootJar` packaging. Reproducible archives and `-Werror` are on from the first commit. |
| ✅ | **P0-TSK-002** — Create module skeleton | `sharedkernel`, `platform` and `app`, with the dependency direction `app → platform → sharedkernel` enforced structurally by Gradle and asserted by test. `sharedkernel` is framework-free and provably carries no Spring artefact, so the kernel cannot quietly acquire a framework. Boundaries exist before code does, because retrofitting them is the expensive path. |
| ✅ | **P0-TSK-003** — Local infrastructure via Docker Compose | PostgreSQL, Kafka and Redis at pinned versions, health-checked, on named volumes and bound to loopback only. Image versions are single-sourced in the version catalogue, and a build task fails when `compose.yaml` and the catalogue disagree — because local infrastructure and test infrastructure being different software is a defect the test suite cannot see. |
| ✅ | **P0-TSK-004** — CI pipeline | Four gates on every change, as separate jobs so a failure names its own gate: build and tests, migrations against a real PostgreSQL, a secret scan over full git history, and a dependency scan of a CycloneDX SBOM. Every third-party action is pinned to a commit SHA and both scanners to image digests. The Java version CI installs is read from the version catalogue rather than duplicated into the workflow. |
| ✅ | **P0-TSK-005** — Database migration tooling | Flyway, forward-only, with each schema-owning module holding its own migration history. A mistake is corrected by a new migration rather than an undo script — structurally the same rule as correcting a financial error with a compensating entry. Migrations never run as a side effect of application startup. |
| ✅ | **P0-DOC-001** — Build and local development guide | A `README` covering prerequisites, build, test, infrastructure lifecycle, migrations and CI gates, plus the failures this stack actually produces. Every command was verified from a clean clone with infrastructure stopped first, so the hermetic-build claim was tested rather than asserted. |

## P0-EPIC-02 — Module Architecture and Boundary Enforcement

*Ownership is only real if violating it fails the build.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-006** — Define the context-to-module map | All 28 bounded contexts mapped to 24 modules, each recording the nine boundary attributes `CLAUDE.md` requires. Merges are deliberate and each carries the evidence that would trigger a split. Single ownership is verified by a script over the register rather than by reading it, which is how the exercise found one entity owned by two modules. |
| ✅ | **P0-TSK-007** — ArchUnit boundary rules | Rules forbidding reverse dependencies, cross-module internal access, cross-module entity references and framework leakage into `sharedkernel`, each proven by a deliberate violation that failed the build and was then reverted. A guard test asserts the analysis actually sees production classes, so the suite cannot become silently vacuous. |
| ✅ | **P0-TSK-008** — No-floating-point-money static rule | `INV-MON-01` enforced on every build across fields, signatures, call targets and field accesses, including generic arguments. Scoped default-deny over every class rather than by a list of financial packages, because a list is a thing somebody forgets to extend. Proven end to end by planting a `double` in `Money`, a `float` in a signature and a `Double.parseDouble` call, each in a different module. |
| ✅ | **P0-DOC-002** — `MODULE_ARCHITECTURE.md` | The module cut, ownership, boundaries and the rule behind every claim of mechanical enforcement. `ArchitectureRulesAreDocumentedTest` fails the build when the document and the enforced rule set stop agreeing **in either direction**, and the document is a declared build input so a doc-only edit re-runs the check. |

## P0-EPIC-03 — Financial Kernel: Money

*Every monetary defect in the platform's future either originates here or is prevented here.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-009** — Implement `Money` and `CurrencyCode` | Immutable money as integer minor units with an explicit ISO-4217 currency and a stored scale. Arithmetic is exact only: cross-currency, cross-scale, inexact and overflow operations each fail with a distinct domain exception rather than coercing. There is no no-currency constructor, so an implied currency is unrepresentable. |
| ✅ | **P0-TSK-010** — Rounding policy | Six named rounding policies, each with a stable name so a decision can record which one produced it. Rounding always requires an explicitly named policy — there is no defaulted mode — and allocation distributes the indivisible remainder rather than absorbing it, so parts always sum back to the original. |
| ✅ | **P0-TSK-011** — Money persistence mapping | `MoneyColumns` is the single definition of ADR-0003's three-column shape and supplies the DDL fragment migrations use, so the shape cannot drift between tables. Round-trip is verified against a real PostgreSQL including the `BIGINT` extremes. Written mechanism-agnostic, so no ORM is chosen by accident. |
| ✅ | **P0-TST-001** — `Money` property and edge-case tests | The algebraic laws — commutativity, associativity, identity, inverse, multiplication as repeated addition, the reversal round trip — asserted over 20,000 generated trials each, across 0-, 2- and 3-minor-unit currencies. Exactness is checked against `BigDecimal` as an independent implementation, and every law asserts its own coverage so it cannot pass by rejecting everything. |
| ✅ | **P0-TST-002** — Allocation zero-residual test | `INV-BAL-03` swept across the criterion's full 1..100 range with amounts drawn from the whole representable range. Both sweeps assert they actually encountered indivisible remainders, so neither can pass by allocating only divisible amounts, and evenness is asserted separately from totality because a first-part-takes-all allocator satisfies totality perfectly. |

## P0-EPIC-04 — Identity, Time and Correlation Primitives

*Traceability and reproducibility depend on identifiers and time being controlled, not ambient.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-012** — Identifier strategy | Typed per-aggregate identifiers over UUIDv7, where passing one aggregate's identifier for another's is a compile error and two identifiers of different kinds never compare equal. Monotonicity is engineered rather than inherited from the clock — a counter in the 12-bit field, borrowing the next millisecond on exhaustion, with no regression when the clock steps backwards. |
| ✅ | **P0-TSK-013** — Time abstraction | Ambient time is a build failure: no zero-argument `now()`, `System.currentTimeMillis()`, `nanoTime()` or `new Date()` in production code, with `Clock.systemUTC()` permitted in the composition root alone. `Instant.now(clock)` stays allowed, because forbidding the clock-taking overloads would push people off the correct API. Posting date, value date and system time are distinguished in the domain model. |
| ✅ | **P0-TSK-014** — Correlation and causation context | `CorrelationId` and `CausationId` as distinct validated types, with a context that survives an async handoff and provably does not leak between tasks on a pooled thread. Correlation is inherited by an emitted message while causation is replaced by the emitting event, so the causal tree survives rather than flattening. The kernel is framework-free and serves an HTTP filter, a job and a consumer alike. |
| ✅ | **P0-TST-003** — Correlation propagation integration test | One request's identifier proven identical in the log and in a committed database row, across a thread handoff, with a negative control showing an unwrapped handoff loses it. The sinks that did not exist yet are handled by `CorrelationSinkCoverageTest`, which fails the build when a new platform concern appears without a decision about whether correlation must reach it — so the four-sink criterion is enforced on arrival rather than left to memory. |

## P0-EPIC-05 — Idempotency Kernel

*`CLAUDE.md` rule 7 — this must exist before any money-moving command does.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-015** — Idempotency record schema | A table keyed on (scope, idempotency key) with a unique constraint, proven under 16-way contention against a real PostgreSQL. The state machine is checked in the schema as well as in code, using a trigger where a `CHECK` constraint cannot see the previous row. The fingerprint's algorithm is recorded on the record, so `INV-IDEM-03` cannot be weakened by a silent algorithm change. |
| ✅ | **P0-TSK-016** — Idempotent execution wrapper | Claim the key, execute, record the outcome — all inside the caller's transaction, so no crash can leave a financial effect that no record describes. Eight concurrent duplicates produce one execution, one effect and eight identical responses. A live claim is reported rather than assumed failed; a stale one is taken over, with the staleness test in the database so two reclaims cannot both win. |
| ✅ | **P0-TSK-017** — `Idempotency-Key` header handling | `@RequiresIdempotencyKey` declares the requirement; an interceptor enforces it before the handler is entered. An interceptor rather than a filter, because a filter cannot see which handler was chosen and its rejection would bypass the error contract. Found and closed a gap the classification document pointed at: the key carried no charset, so a caller could have put CR/LF into a value the platform logs and stores durably. |
| ✅ | **P0-TST-004** — Idempotency concurrency and retry tests | Five failure modes driven at the kernel: observed contention, contention outlasting the bounded wait, a response lost after commit, expiry on both sides of the retention sweep, and an instance crashing mid-command. Contention is proven by observing PostgreSQL's own lock waits rather than by hoping threads overlap, so the test cannot pass while contention is broken. |

## P0-EPIC-06 — Reliable Messaging: Envelope, Outbox, Inbox

*Kafka is integration infrastructure, not financial truth.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-018** — Event envelope type | All ten `INV-EVT-03` fields mandatory at construction, so an event that could not be traced cannot be built. The envelope carries metadata only and no payload, so relays and consumers can handle events they cannot deserialise and no log line can spill event contents. Its canonical form is pinned by exact-match test. |
| ✅ | **P0-TSK-019** — Outbox table and writer | The envelope stored as columns, all ten `NOT NULL`, written by a writer that never opens a transaction of its own — so `INV-EVT-01` holds by construction rather than by intent. Proven both ways: a rolled-back fact loses its outbox row and a committed one keeps it. A build rule fails on any direct publish to a broker, matched by package name so the rule exists before the dependency does. |
| ✅ | **P0-TSK-020** — Outbox relay | Every instance polls, and a transaction-scoped advisory lock **per aggregate** means one instance drains a given aggregate at a time — so ordering survives concurrency instead of depending on there being one relay. Delivery is at-least-once and says so. An abandoned event blocks its aggregate rather than being skipped, because a stall is loud and an undetectable gap in a financial event stream is not. |
| ✅ | **P0-TSK-021** — Inbox dedupe store and consumer wrapper | The dedupe record and the side effect commit in one transaction, so the row exists if and only if the effect happened. Keyed on **(consumer, dedupe key)**, so one event's many consumers each handle it once rather than the first silently suppressing the rest. Contention is reported after a bounded wait rather than waited on, because losing costs one redelivery an at-least-once transport was going to perform anyway. |
| ✅ | **P0-TST-005** — Outbox crash-recovery test | The full chain in one test: business fact and outbox row in one transaction, the relay killed before publication, restarted, and the event published once carrying the correlation of the flow that produced it. An instance terminated mid-publication releases its aggregate and a surviving instance finishes the job, which is the property that makes the transaction-scoped lock load-bearing. |
| ✅ | **P0-TST-006** — Duplicate and out-of-order delivery test | Deduplication proven independent of arrival order, with duplicates interleaved and delivered backwards. The sharpest claim is made executable: an order-dependent handler is **still wrong** under the inbox — it ends believing a completed transfer is in flight, with nothing failing anywhere — and an ordering key fixes it on the same deliveries. |

## P0-EPIC-07 — Audit Trail

*Application logs are not automatically a regulatory-grade audit trail.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-022** — Audit schema and writer | An append-only table where append-only is a **privilege**, not a convention: the application role holds `INSERT` and `SELECT` and nothing else. Two ordinary `NOSUPERUSER` roles were introduced with it, since a superuser ignores every permission check and would make these invariants untestable rather than merely unenforced. All seven questions — actor, type, time, operation, target, outcome, correlation — are mandatory at construction. |
| ✅ | **P0-TSK-023** — Auditable-action registry | An interface each module implements as an enum, because the platform sits below every business module and cannot enumerate their vocabulary. `AuditRecord.operation` is typed, so an action outside the registry cannot be recorded at all — the type system, not review, keeps the trail's vocabulary closed. Its limit is documented: it cannot detect a privileged action that writes no record. |
| ✅ | **P0-TST-007** — Audit immutability test | `UPDATE` proven denied on **every** column, with the column list read from the catalogue rather than from whichever one a test happens to set. Column-level grants are invisible in the usual privileges view, and probing found that `GRANT UPDATE (reason)` let the application rewrite a committed record's justification while the whole audit suite stayed green. Both widenings now fail the suite. |

## P0-EPIC-08 — API Conventions and Error Contract

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-024** — Error contract | RFC 9457 problem details on **every** error path, including the four the framework raises before our code runs. No exception message, type, stack frame or framework member reaches a client: the response is built from the error code alone, and `ApiException` keeps the log message and the client detail in separate fields so the unsafe default is unreachable rather than discouraged. |
| ✅ | **P0-TSK-025** — Request validation at the boundary | Declarative constraints on the request type, rejected before any domain invocation — asserted by counting handler entries, because a rejection returned after the handler did half the work looks identical from outside. An over-limit body is refused without reading a byte, and a chunked body, which declares no length, is bounded by a counting stream. |
| ✅ | **P0-TSK-026** — API versioning and OpenAPI generation | `/v1` applied once in the composition root, so no controller declares or forgets it, and the unprefixed path is proven not to be served as well. The OpenAPI document is generated from the running application on every build and compared byte for byte with the committed copy, with each difference labelled breaking or compatible. The generator is test-scope, so nothing about OpenAPI is deployed. |
| ✅ | **P0-TSK-027** — Health, readiness and info endpoints | Liveness depends on nothing external, because a liveness probe consulting the database restarts the whole fleet during a blip and destroys the evidence. Readiness includes PostgreSQL and is checked through the pool the application actually uses. The application starts with its database unreachable and reports NOT_READY, rather than crash-looping. |
| ✅ | **P0-DOC-003** — API conventions document | Versioning, the published contract, errors, correlation, request limits, idempotency, pagination and deprecation in one place. Every section is labelled implemented or decided-not-yet-implemented with the owning task, and **an unlabelled section fails the build**, so the distinction cannot erode. Every stated value is pinned against the code and the error-code catalogue is referenced rather than restated. |

## P0-EPIC-09 — Observability Baseline

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-028** — OpenTelemetry tracing | Every span carries the flow's correlation identifier, stamped once by a span processor rather than by each component — because "every span" is not a property discipline delivers, and forgetting is silent. A trace identifier is explicitly not a substitute, being subject to sampling. No statement text goes on a span, since SQL would carry amounts and account identifiers into a backend with different access control. |
| ✅ | **P0-TSK-029** — Metrics and dashboards | `finapp.<module>.<noun>` enforced against the live registry rather than a written list, because a metric name is a contract that outlives the code. No tag value may come from a request — an identifier in a tag is both a cardinality explosion and a disclosure with months of retention. Outbox depth and age are gauges over the database, so they are readable when the relay is down, which is when they matter. |
| ✅ | **P0-TSK-030** — Structured logging with redaction | `Sensitive<T>`, whose every rendering path masks — `toString`, interpolation, concatenation, a record's generated `toString`, and JSON. Default-deny is a property of the **build rule**, not of the wrapper: a field or accessor whose name says it holds a secret must be wrapped or the build fails. Equality is identity-based, so the wrapper cannot be used as an oracle for the value it hides. |
| ✅ | **P0-TST-008** — Log redaction test | Found that `secretsAreWrapped` was **structurally incapable of failing** — `noClasses().should(condition)` inverts the condition's events — so a production record holding a plaintext password passed cleanly, with a green fixture test beside it. A second path was found by probing: the MDC takes a `String`, so writes to it are now confined to one component. Redaction is asserted on console and file appenders, each with a negative control. |

## P0-EPIC-10 — Security Baseline

*Secure defaults before any authentication exists.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-031** — Secret management approach | Probing showed gitleaks catches a private key and a high-entropy token but **misses `password: hunter2`** — the shape a human actually commits — so the scanner is a net, not the control. The control is a build rule over configuration files it discovers rather than lists, and a startup guard confines the one published local default to loopback, closing the documented bypass of externalised configuration: forgetting to set the variable. |
| ✅ | **P0-TSK-032** — Security context abstraction | Who is acting travels with the flow, and **an unestablished actor is an error rather than the system actor**. A default would be correct today and silently wrong the moment real identity arrives, producing a complete, plausible, permanent record about the wrong party. Phase 0 claims the system actor out loud through `enterSystem()`, which is the greppable list of places Phase 1 must revisit. |
| ✅ | **P0-TSK-033** — Data classification scheme | Five levels applied **per column at its ceiling** — the most sensitive thing a column may ever hold, not what it holds today — because a column cannot be reclassified once it has data. Written in the phase that holds nothing sensitive, which is the only phase where the decision is still free. The register is reconciled against the live schema in both directions, so a migration adding an unclassified column fails the build. |
| ✅ | **P0-TSK-034** — Transport and at-rest encryption baseline | The insecure default turned out to be the **driver's**: `sslmode` unset or `prefer` connects unencrypted and reports nothing. A non-loopback database must now be reached with `verify-full` or the application refuses to start — not `require`, which encrypts and authenticates nothing. Every configured source of the setting must agree, so the control does not rest on a driver precedence that could change. |
| ✅ | **P0-TSK-039** — Dependency verification and locking | Every artefact the build resolves is checksum-verified and version-locked, closing the largest remaining supply-chain hole: a build-time dependency runs with full build privileges. The two controls are not redundant, and measuring proved it — 69 of 342 modules are recorded at more than one version, so verification cannot tell a deliberate resolution from drift between versions it already trusts. Trust-on-first-use is stated as the limit rather than glossed. |
| ✅ | **P0-TSK-040** — Update mechanism for pinned actions and images | Pinning removes one risk and creates another: a SHA cannot be repointed, and it also freezes the thing. Dependabot covers what it can read; the two scanner digests get a weekly registry check that distinguishes a **moved tag** — the attack pinning defends against — from ordinary rot. Making the dependency scan a script a developer can run had an immediate consequence: it was run, and it failed. |
| ✅ | **P0-TSK-041** — Architecture rule for single-instance assumptions | Four patterns now fail the build — `synchronized` methods and blocks, process-local locks, ambient scheduling, static mutable state — each of which means something only within one process, so its presence is a claim about coordination that is false the moment a second instance starts. The block check reads bytecode, because ArchUnit models accesses and a `MONITORENTER` has no access flag. The limit is recorded: the defect that motivated the rules used none of the four. |
| ✅ | **P0-TST-009** — Multi-instance concurrency test convention | A harness giving each simulated instance its own connection, component and clock, with skew anchored on the server's clock rather than a fixture constant. The audit found the criterion's own subject broken: the existing skew test's "fast" clock was **forty hours behind** the server, so it exercised a slow instance and passed under a deliberate reintroduction of the defect. |

## P0-EPIC-11 — Test Infrastructure

*Trustworthy verification.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-TSK-035** — Testcontainers integration test harness | One PostgreSQL container per test JVM, applying the same role script the compose stack runs and the real migrations through Flyway, publishing the coordinates as the system properties every test already read. **No test changed** — that was the constraint rather than the outcome, since a harness needing 173 assertions edited would have been a change nobody could review. PostgreSQL only: there is no Kafka or Redis client, and a container nothing connects to tests nothing. |
| ✅ | **P0-TSK-036** — Test taxonomy and conventions | Four tiers — unit, architecture, slice, database — defined by **what a test needs in order to run**, because that is the only axis on which membership can be decided mechanically. The default tier selects by excluding the others' tags, so a test can never belong to no tier at all. `contract` is deliberately not a tier: its members have different requirements, and grouping two requirements under one name is the one thing a tier must not do. |
| ✅ | **P0-TSK-037** — WireMock harness for provider adapters | `SimulatedProvider`, in two halves, because a provider is unreliable in **both directions**: outbound is its API, which we call; inbound is its callbacks, which it makes to us. A duplicated webhook and a late settlement are the provider acting on its own schedule, so no amount of stubbing its API reproduces them. The coverage claim is enforced in three links — every failure mode classified, every one naming a method that exists, and every such method actually called by the suite that proves the harness. |
| ✅ | **P0-TSK-038** — Mutation-style invariant verification convention | A register recording, for all 17 Phase 0 invariants and all 9 `P0-TST-*` items, the mutation that breaks the property and what failed when it was applied. Each is labelled **in-suite** — a proof that runs on every build and cannot rot — or **recorded**, which only proves the test had teeth on the day it was written. The guard holds the register to the invariant catalogue and to the compiled test classes, which converts the phase exit gate's third criterion from a one-off human check into a continuous one. |

## P0-EPIC-12 — Documentation and Decision Baseline

*Durable project knowledge.*

| | Task | Description |
|---|---|---|
| ✅ | **P0-DOC-004** — Invariant catalog | `FINANCIAL_INVARIANTS.md` expanded into 64 invariants, each with a stable identifier, an enforcement mechanism and a verification method. Enforcement strength is ordered explicitly, so "we check it in code" is visibly weaker than a database constraint rather than equivalent to one. |
| ✅ | **P0-DOC-005** — `DEFINITION_OF_DONE.md` | What "done" means, as task-type profiles rather than one list — a kernel task, a test task and a documentation task are not finished by the same evidence. A feature is not complete because it compiles. |
| ✅ | **P0-DOC-006** — `EXECUTION_PROTOCOL.md` | How a working session runs: read the current state first, implement one task, do not fix unrelated things opportunistically, and record deferred work as debt rather than leaving it implicit. Rule 4 in particular is why several known defects in this repository are written down rather than quietly patched. |
| ✅ | **P0-DOC-007** — `PHASE_GATES.md` | Formal entry and exit criteria per phase, plus the status model. A phase declares the invariants it protects at entry and proves them by test at exit. |
| ✅ | **P0-DOC-008** — `DELIVERY_PLAN.md` | The master plan across all seventeen phases, with what each phase delivers and why it is sequenced where it is. |
| ✅ | **P0-DOC-009** — ADR-0001 … ADR-0010 | The first ten architecture decision records: modular monolith, ledger authority, monetary representation, idempotency, outbox, boundary enforcement, phase-gated delivery, provider adapters, balance as projection, audit trail. Recorded as `Proposed`; they become `Accepted` at the Phase 0 exit gate. |
| ✅ | **P0-DOC-010** — `MODULE_ARCHITECTURE.md` | The first architecture baseline: the module cut, ownership and boundaries. Later extended by `P0-DOC-002` into a document whose enforcement claims are checked against the enforced rules on every build. |
| ✅ | **P0-DOC-011** — Domain glossary | 62 terms — every canonical concept plus the seven the distinctions name and the canonical list never did — each with what it is, what it is **not**, and the module that will own it. The `Not:` line is the deliverable: a definition alone does not stop two people applying it to the same thing. The guard holds it to both source lists in both directions, so no term is silently undefined and the glossary cannot become a second home for vocabulary its owning document should define. |
| ✅ | **P0-DOC-012** — Phase 0 review record | All eight review areas, and ADR-0001…0028 moved to `Accepted`. Two of them could not be conducted as written and say so: there is no posting to walk, and none of the three registered privileged actions is emitted. **The review finds the exit gate does not pass** — three Tomcat CVEs and a suite that has never run in CI — so the phase remains `IN_PROGRESS`, which is what the gate model prescribes. |

---

# Phase 1 — Identity and Customer Foundation

Status: `PLANNED`. Elaborated to **feature** level; tasks are written at the Phase 1 entry gate.

| Epic | Features |
|---|---|
| **P1-EPIC-01** Party and Customer | Party aggregate and registration; Customer relationship distinct from Party; profile read/update with change audit; PII classification applied to party data; party data access authorization |
| **P1-EPIC-02** Identity and Credentials | Argon2id password credential with per-credential parameters; credential rotation; credential compromise handling; identity creation linked to Party; identity suspension and closure |
| **P1-EPIC-03** Authentication | Enumeration-safe login; brute-force and credential-stuffing controls; TOTP; WebAuthn/passkeys; step-up authentication; account recovery with abuse controls |
| **P1-EPIC-04** Session and Device | Session issuance, refresh and rotation; session listing and immediate revocation; device registration and trust; device revocation |
| **P1-EPIC-05** Authorization | Role and permission model; ownership-scoped resource authorization; privileged/administrative role separation |
| **P1-EPIC-06** Actor-Attributed Audit | Real actor populated into the Phase 0 security context; authentication and authorization events audited; security metrics and alerting |

Phase 1 is also where several pieces of recorded debt come due: the correlation identifier is
caller-supplied and reaches every log line and span, the loopback credential guard covers exactly
one credential, and no production code establishes a security scope yet.

---

# Phases 2–4

Status: `PLANNED`. Elaborated to **capability** level; features at each phase's entry gate.

**Phase 2 — KYC/KYB and Consent.** Case lifecycle and decision recording; KYB with a beneficial-
ownership graph; secure document capture; sanctions, PEP and adverse-media screening adapters;
manual review with four-eyes on high risk; versioned consent with capability gating; a queryable
onboarding status for downstream contexts.

**Phase 3 — Accounts and Financial Ledger.** The chart of accounts; ledger account lifecycle
including suspense; balanced, immutable, idempotent journal posting with atomic outbox
publication; reversal and adjustment; balance derivation and continuous recomputation; holds
against available balance; customer accounts and wallets; statements; and the trial-balance job
that makes an imbalance an alert rather than a discovery. This is the phase that resolves the
data-access mechanism question and the isolation/locking strategy.

**Phase 4 — Internal Transfers.** Beneficiary lifecycle; the transfer state machine with terminal
states; financial-boundary idempotency and conflict semantics; posting to the ledger with
atomicity or compensation; reversal via compensating postings; limit and risk seams with
documented defaults; transfer history correlated to postings; and stuck-transfer detection with
operational intervention.

---

# Phases 5–16

Status: `PLANNED`. Elaborated to **epic** level; capabilities at each phase's entry gate.

| Phase | Scope |
|---|---|
| **5** Payment Infrastructure | Payment intent and attempt; payment methods and tokenisation; the provider adapter framework; authorization and capture; refunds; webhook ingestion and dedupe; provider state mapping and **unknown-state resolution**; payment accounting |
| **6** Checkout and Merchant | Merchant onboarding with KYB; merchant accounts; checkout session and order; fee schedule and assessment; merchant payable accounting and payout; reporting; multi-tenant isolation |
| **7** Cards, Wallets, A2A, Instant | A rail abstraction and capability model; the card, wallet, A2A and instant rails; routing policy; finality and irrevocability; disputes, chargebacks, representment and their accounting |
| **8** Settlement and Reconciliation | Settlement expectation tracking; file ingestion and evidence retention; the matching engine with tolerance and rule versioning; break classification, investigation and four-eyes resolution; suspense management; reconciliation reporting |
| **9** FX and Cross-Border | Rate sourcing and staleness; quote lifecycle and rate lock; explicit spread recognition; conversion execution; multi-currency accounting and FX position; rounding residual handling; corridor policy; cross-border workflow and FX reconciliation |
| **10** Credit Decisioning | Credit profile; bureau adapter and evidence; affordability; risk scoring; a versioned policy engine; immutable decision recording; reason codes and adverse action; decision reproducibility; exposure tracking |
| **11** Lending | Loan application, offer and expiry; underwriting integration; disbursement; repayment schedule and amortisation; interest accrual; repayment allocation; early settlement; delinquency; restructuring; loan accounting |
| **12** BNPL | Eligibility at checkout; instalment plan and agreement lifecycle; merchant financing and settlement; customer obligation; refund and return adjustment; late fees; BNPL accounting and reconciliation |
| **13** Risk, Fraud, AML | Signal ingestion; a versioned rules engine; synchronous risk decisioning and fail-safe policy; limits and velocity; device and behavioural signals; account-takeover detection; AML monitoring; alerting; case management; manual override controls |
| **14** Accounting and Reporting | The GL account model and versioned mapping; accounting periods and close with approval; trial balance and continuous verification; GL drill-down; prior-period adjustment; financial statement production; a regulatory reporting abstraction; report reproducibility |
| **15** Production Hardening | Threat modelling; security hardening; secret and key rotation; privileged access review; audit completeness verification; rate limiting; schema registry and compatibility; dead-letter handling; SLOs and alerting; runbooks, rehearsal and incident response; data retention and deletion |
| **16** Scale and DR | Load characterisation and capacity model; performance testing with invariant assertion; partitioning and archival; read-replica routing; backpressure and load shedding; chaos engineering; database failover; backup and restore rehearsal; the DR plan with RPO/RTO; post-recovery financial verification; and the evaluation of whether any service should be extracted at all |

---

# Cross-cutting work

Items that belong to no phase and gate no phase exit.

- 🟠 **`X-TSK-001` — Lombok adoption and the Phase 1–6 refactor.** Lombok becomes the project
  standard for Java boilerplate, at compile time only, with its safe defaults enforced by
  `lombok.config`. The build and the standard are in place, and the existing code is converted:
  152 classes in nine module batches, each proved byte-identical with `javap`. Aggregates, value
  objects, secrets and `Money` stay hand-written. Blocked on final acceptance only, by a
  pre-existing test failure it did not cause.

## Maintenance

This register is a **derived, unguarded view**. Nothing in the build checks that it still matches
[`BACKLOG.md`](BACKLOG.md), which means it will drift — this repository has met that failure
several times, most recently in a document that restated an ADR's numbers as a second unguarded
copy. It carries no acceptance criteria, dependencies, risk or DoD profile precisely to keep the
drift surface small: an out-of-date sentence here is a stale description, never a contradicted
requirement.

If it is to be relied on, it needs the same treatment the other document-backed claims in this
repository have: a test reconciling the task identifiers here against `BACKLOG.md` in both
directions, with this file declared as a build input.
