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

**M0.1 — Buildable, boundary-enforced skeleton**
`P0-EPIC-01` (Build and Repository Foundation) and `P0-EPIC-02` (Module Architecture and
Boundary Enforcement).

Milestone complete when: a Gradle multi-module Spring Boot build is green in CI from a clean
clone; `platform`, `sharedkernel` and `app` modules exist with enforced dependency direction;
ArchUnit boundary rules fail the build on a deliberately introduced violation; PostgreSQL,
Kafka and Redis run locally with pinned versions matching the test infrastructure.

Subsequent Phase 0 milestones:
- **M0.2** Financial kernel — `P0-EPIC-03`, `P0-EPIC-04`
- **M0.3** Correctness primitives — `P0-EPIC-05`, `P0-EPIC-06`, `P0-EPIC-07`
- **M0.4** API, observability, security baseline — `P0-EPIC-08`, `-09`, `-10`
- **M0.5** Test infrastructure and phase review — `P0-EPIC-11`, `P0-EPIC-12`

## Current Task

**`P0-TST-002` — Allocation zero-residual test**
Status: `READY` — not started.

Bounded context: sharedkernel. Depends on `P0-TSK-010` (`COMPLETE`).

Note for that task: `MoneyAllocationTest` already sweeps ~160,000 even splits and 2,000
weighted ones and asserts zero residual, and `P0-TSK-010` demonstrated it failing when the
remainder is discarded. The honest work is to check that claim against the acceptance criterion
as written — "any amount across any 1..100 parts" — and to say plainly where coverage is
already adequate rather than restating it.

Full definition: [`BACKLOG.md`](BACKLOG.md) §P0-EPIC-03. DoD profile: `DOD-TEST`.

### Just completed

**`P0-TST-001` — `Money` property and edge-case tests** — `COMPLETE` (2026-09-01).

| Acceptance criterion | Evidence |
|---|---|
| Tests fail if **rounding** is deliberately broken | Policy ignored, every amount rounded `CEILING` — `eachPolicyRoundsInItsOwnDirection` fails |
| Tests fail if **currency checking** is deliberately broken | `requireCompatible` removed from `plus` — cross-currency property fails |
| Tests fail if **overflow handling** is deliberately broken | `addExact` replaced with `+` — two properties fail |

`MoneyTest` already covered currency mismatch, scale mismatch, overflow, negatives and zero
thoroughly, but by example: commutativity and associativity rested on `1.11 + 2.22 + 3.33`,
which holds under implementations that break at the boundaries of `long`. The new
`MoneyPropertiesTest` asserts the laws over generated values instead.

Design decisions worth carrying forward:
- **Laws, not more examples.** The ledger will assume these from Phase 3: if addition is not
  associative, two postings that balance in one grouping do not balance in another; if
  `(a + b) - b` is not `a`, no reversal restores the original position (`INV-REV-01` in
  arithmetic form); if `compareTo` disagrees with `equals`, a sorted report and a lookup
  disagree about the same amount.
- **Coverage is asserted, because every operation may legitimately throw.** A law phrased as
  "the results agree" is satisfied trivially when both sides reject. Each law counts the trials
  that produced an amount and fails below a floor — demonstrated by collapsing the generator to
  extremes only, which fails three properties.
- **An oracle must not share the defect.** The first rounding property bracketed every policy
  between `FLOOR` and `CEILING` *computed through `Money`*. Breaking rounding moved the bounds
  and the value together, and it passed. Each policy is now pinned by the property that defines
  it, stated without reference to `RoundingMode`.
- **No property-based library added.** Seeded `java.util.Random`, following
  `MoneyAllocationTest`. Adding jqwik is a dependency decision, not one a test task should make
  on its own.

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

None in progress. `P0-TSK-008` is the next task.

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
| `P0-TSK-003`, `P0-TSK-005` | Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants (`INV-LED-03`, `INV-HIST-01`, `INV-HIST-03`) cannot yet be exercised. The migrator/application role split is designed and documented (`DATA_MIGRATIONS.md` §5) but not implemented, and must land **before** any table subject to those invariants is created. | `P0-TSK-022` |

---

## Known Architectural Debt

None yet. Debt is recorded here as it is deliberately accepted, with: what was deferred, why,
what risk it carries, what triggers paying it down, and the owning phase.

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

**`P0-TST-002` — Allocation zero-residual test.**

Rationale: next in [`BACKLOG.md`](BACKLOG.md) and the last item in `P0-EPIC-03`. Much of it
exists already (`MoneyAllocationTest`, `P0-TSK-010`), so the task is to establish what the
acceptance criterion asks for that is not yet covered — specifically the stated 1..100 part
range — and to record explicitly where existing coverage already satisfies it.

---

## Change Log

| Date | Change |
|------|--------|
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
