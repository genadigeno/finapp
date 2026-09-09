# Testing Conventions

The test taxonomy: what the tiers are, which one a concern belongs to, how to name and tag a
test, and what the shared harnesses are for.

Established by `P0-TSK-036`. The parts of this document that can be enforced **are** enforced —
`TestTaxonomyTest` fails the build when this document and the build stop agreeing, in either
direction — and the parts that cannot be are marked as review questions rather than left to look
like rules.

---

## 1. A tier is what a test needs in order to run

That is the whole definition, and it is deliberately not "what the test proves".

**Why this axis and no other.** A tier decides which Gradle task a test runs in, so a tier that
mixes requirements produces a task that costs what its heaviest member costs and fails wherever
that member's infrastructure is absent. Grouping by *intent* — unit, contract, acceptance — reads
better in a document and cannot be decided mechanically: two people will classify the same test
differently, and nothing will notice. Grouping by *requirement* can be checked, and is.

| Tier | Needs | Tag | Task |
|---|---|---|---|
| **unit** | nothing beyond the JVM | *(none — the default)* | `unitTest` |
| **architecture** | the compiled classes of every module | `architecture` | `architectureTest` |
| **slice** | a Spring application context | `slice` | `sliceTest` |
| **database** | a real PostgreSQL | `database` | `databaseTest` |
| **kafka** | a real Kafka broker, and the database — what the tier tests is the outbox reaching the broker | `kafka` | `kafkaTest` |

The tiers are declared once, in `finapp.java-conventions.gradle.kts`, and mirrored in `TestTier`.
The build passes its declaration to the tests as a system property so the two can be compared —
a build script and a Java enum have no other way to share one definition.

### An ArchUnit suite declares its tier twice, and that is not a duplication

A class annotated `@AnalyzeClasses` is executed by **two** JUnit Platform engines: Jupiter runs its
`@Test` methods, and ArchUnit runs its `@ArchTest` **fields** under an engine of its own. Those two
read **different annotations** — Jupiter reads `@Tag`, ArchUnit's descriptors read
`com.tngtech.archunit.junit.@ArchTag` and cannot see `@Tag` at all.

So an ArchUnit suite carries both, with the same value, and `TestTaxonomyTest` requires it.

**`P1-TSK-025` is what the absence looked like.** Every `@ArchTest` field carried no tag, so the two
tier tasks disagreed in opposite directions: `architectureTest` selects by *inclusion* and got none
of them, while `unitTest` selects by *exclusion* and took all 28. `ModuleBoundaryRulesTest` — which
has no `@Test` method at all — produced **no result file** in the architecture tier: not a suite
that ran zero cases, a suite that did not appear.

**No existing guard could see it**, and the reason is worth keeping: the partition check asserts a
**sum**, and the sum was right. Every rule was in exactly one tier. A check on a total cannot see a
misallocation that preserves the total.

### The default tier takes everything no other tier claims

`unitTest` selects by **excluding** the other tiers' tags, not by including a `unit` tag of its
own. This matters more than it looks: a set of `includeTags`-only tasks lets a test belong to no
tier at all, and that failure is silent — the test compiles, is never selected, reports nothing,
and is believed to be running. Excluding makes the default tier a catch-all, so the worst case is
a test in a tier that is heavier than it needs rather than a test in no tier.

### Escalation

The tiers are ordered: each needs strictly more than the one above it. A test that needs a
database also needs a Spring context in most cases, and it is tagged `database` — **the heaviest
requirement wins**, because that is the one that decides where it can run.

---

## 2. Which tier a concern belongs to

| Concern | Tier | Why |
|---|---|---|
| A value type's behaviour — arithmetic, validation, equality | unit | The claim is about the type. A database would add nothing but time |
| An algebraic law over generated values | unit | Same, and these run tens of thousands of trials — they must stay fast |
| A build rule over production code — boundaries, forbidden APIs, naming | architecture | It reads compiled classes, which is a build output rather than a service |
| A document reconciled against the code | whichever the *test* needs | `ErrorCodeRegistryTest` enumerates classes, so architecture; `DashboardQueriesResolveTest` resolves against a live registry, so database |
| An HTTP contract — status, headers, problem-detail shape | slice | The contract is a property of the running stack, not of a handler method |
| Error rendering for framework-raised failures | slice | Those errors are raised before our code runs, so only a real context produces them |
| A schema constraint, trigger or privilege | database | The claim is about what PostgreSQL does. There is nothing else to ask |
| Concurrency, contention, locking, leases | database | Contention needs a real lock manager. See §4 |
| A monetary round trip | database | The claim is that the column type returns exactly what was written |
| Transaction boundaries — a fact and its outbox row committing together | database | The property *is* the transaction |

**When in doubt, the question is not "what kind of test is this?" but "what would have to be
running for this to work?"**

---

## 3. Naming and tagging

- A test class is named `*Test`, and a class named `*Test` is a test. Both directions are
  enforced, because they cross-check two different discovery rules — see §6.
- The tier tag is the first annotation on the class, above `@SpringBootTest` and `@DisplayName`.
- A class carries **at most one** tier tag. Two would make its tier a function of which task
  selected it first.
- **The tag vocabulary is closed.** A tag that is neither a tier nor a declared non-tier selector
  fails the build. An unrecognised tag is otherwise *ignored* rather than rejected, so
  `@Tag("databse")` reads as a tier and schedules nothing — the same argument that makes
  `AuditableAction` a closed set. The non-tier list is currently empty; adding to it is a decision
  somebody makes, which is the whole difference between a selector and a typo.

### `contract` is deliberately not a tier

A contract test reconciles a committed artefact — `openapi.json`, `ERROR_CONTRACT.md`,
`AUDITABLE_ACTIONS.md`, `DATA_CLASSIFICATION.md`, the Grafana dashboard — against the code. That
is a **kind**, not a requirement: `OpenApiContractTest` needs a Spring context and
`ColumnClassificationTest` needs a database. Putting them in one task would group two different
requirements under one name, which is the single thing a tier must not do. They are therefore
placed in the tier their requirement dictates, and the fact that they are contract tests is
carried by their names and by the documents declared as build inputs beside them.

`integration` is likewise not a tier name here. `database` says what is integrated with, and when
a Kafka client exists it gets its own tier rather than being folded into a word that would then
mean two different things.

---

## 4. Concurrency and multi-instance tests

Governed by `DISTRIBUTED_EXECUTION.md` §5, which is authoritative. In short: a test claiming to
simulate N instances gives each of them its own connection, its own component instance, and its
own clock where a clock participates in the decision — with skew measured **against the server**,
never against a fixture constant. `SimulatedInstance` supplies all three.

---

## 5. Shared test support

Test support lives in `platform`'s `testFixtures`, so both `platform` and `app` reach one
definition rather than each growing its own. `sharedkernel` cannot: it sits below `platform`, and
that is the dependency direction working rather than a gap — it has no database tests and must not
acquire the ability to have one casually.

| Class | Package | For |
|---|---|---|
| `DatabaseUnderTest` | `…testing.database` | Starts one PostgreSQL container per test JVM, applies the role script and the real migrations, and publishes the coordinates (ADR-0027) |
| `DatabaseRoles` | `…testing.database` | A connection as each role, and the assertion that the connected role cannot bypass the privileges under test |
| `SimulatedInstance` | `…testing.database` | One simulated instance: its own connection and its own clock, server-anchored |
| `SimulatedProvider` | `…testing.provider` | An external provider that misbehaves on demand (`P0-TSK-037`) |
| `KafkaUnderTest` | `…testing.kafka` | Starts one Kafka broker per kafka-tier JVM and publishes `finapp.kafka.bootstrap` (`P2-TSK-001`). Its own package, because tier detection keys on package prefixes and a Kafka harness is not evidence a test needs PostgreSQL |
| `RepositoryPaths` | `…testing` | Locates a repository file without assuming a working directory |

**The database harnesses have their own package, and the tier rule is why.** They first sat beside
`RepositoryPaths`, which reads a file and needs no database — and the `database` signature is a
package prefix, so three hermetic contract tests were immediately reported as needing PostgreSQL.
Splitting `…testing.database` out keeps the prefix self-maintaining: a fourth harness added there
is covered without anyone editing a list, and a general helper is not.

### Which role to connect as

| Role | Use for |
|---|---|
| `DatabaseRoles.application()` | **Every test asserting a privilege-level invariant.** Call `assertCannotBypassPrivileges` first, or the assertion is vacuous |
| `DatabaseRoles.migrator()` | Fixtures that legitimately need DDL — a probe table |
| `DatabaseRoles.bootstrap()` | Tests where no privilege claim is being made |

A superuser ignores every permission check, so a denial test connected as one passes with the
grants correct, with the grants wrong, and with no grants at all. That is the worst kind of green,
and it is why `assertCannotBypassPrivileges` exists.

---

## 5a. Simulating a provider

`SimulatedProvider` is the harness ADR-0008 requires, so that "every adapter is contract-tested
against simulated failure" is something an adapter author reaches for rather than rebuilds.

**A provider is unreliable in both directions, so the harness has two halves.** A simulator with
only the first cannot reach the failure modes that cost the most:

| Half | What it is | Modes |
|---|---|---|
| **Outbound** | the provider's API, a real HTTP server on loopback that we call | timeout, unavailable, 5xx, delayed, malformed body, garbage, unknown state, retry sequence, **request received then response lost** |
| **Inbound** | the provider's callbacks, which it makes to us | **duplicate webhook**, late settlement |

A duplicated webhook (`INV-IDEM-04`) and a late settlement (`INV-SET-03`) are the provider acting
on its own schedule. No amount of stubbing its API reproduces them.

**No WireMock type appears in the harness's signature.** That is ADR-0008's own argument one layer
down: an adapter that leaks provider vocabulary couples the domain to a vendor, and a test that
reaches past the harness to raw stubbing couples the suite to the simulator. A caller sees a list
of ways a provider fails.

**The single most useful assertion is `requestCount`.** It separates two failures that are
identical from the caller's side — a request that never arrived, and a request that arrived and
was acted on before the answer was lost. That distinction is why `INV-LIFE-03` requires an explicit
indeterminate state rather than a guess in either direction.

**A failure mode applies whatever verb the adapter uses.** A provider that is unavailable is
unavailable for `GET` and `POST` alike — the failure belongs to the provider, not to the request
method. The first version bound each stub to one verb, which meant an adapter POSTing to create a
payment got a `404` from a stub that claimed the provider *succeeds*; found by review, and the
worst possible shape for the failure, because a 404 reads as "the adapter called the wrong path".

**`deliverCallbackAfter` sleeps real time**, which is the crude form and is usually not what a test
needs. `INV-SET-03`'s "late" is *logical* — the callback arrives after the operation completed —
and a plain `deliverCallback` at the right moment already gives that. Reach for the delaying
variant only when wall-clock separation is the property under test.

**Which tier.** `unit` — WireMock is an in-JVM server on loopback, needing no container, no Docker
and no external service. That is a judgement rather than a reading of the axis, and it was made on
measurement: the whole provider suite costs **1.3 seconds** including server start. If it grows
enough to slow the inner loop, the escalation is its own tier, on the same grounds `slice` has one.

**`ProviderFailureCoverageTest` is what makes the coverage claim real**, in three links that must
hold at once: every bullet in `CLAUDE.md` §Failure Engineering is classified as a provider concern
or explicitly not one; every provider concern names a harness method that **exists**; and every
such method is **actually called** by the suite that proves the harness. The third link is the one
that matters — without it the harness could claim a mode no test ever exercises.

## 6. What is enforced, and what is not

**Enforced by `TestTaxonomyTest` on every build:**

1. Every test class declares a tier at least as heavy as the one its bytecode proves it needs.
2. No class declares two tiers, and every `@Tag` value is one the taxonomy declares.
3. Every class named `*Test` is selected by the sweep, and every selected class is named `*Test`.
4. Gradle registers exactly the tiers `TestTier` declares, with the same tags.
5. Exactly one tier is the default, only tiers needing external infrastructure are excluded from
   `test`, and **no tier is empty**.
6. CI invokes a task covering every tier, **unqualified** — never `:platform:databaseTest`.
7. This document names exactly the tiers that exist, with their task and tag.

**Not enforced, and the reason:**

- **Over-declaration is permitted.** Detection reads the test class's own bytecode, so it sees a
  JDBC call and cannot see a `@SpringBootTest` reaching PostgreSQL through the application's own
  `DataSource`. `DashboardQueriesResolveTest` and `TraceAcrossDatabaseTest` name a database
  nowhere and need one; there is no bytecode in either to detect, because the requirement belongs
  to the context they start. Declaring a heavier tier than is detected is therefore the supported
  way to say "this needs more than you can see", and a test in a tier heavier than necessary is
  slow rather than wrong.
- **Nothing checks that a test is in the *lightest* tier that would work.** That is a review
  question, and the cost of getting it wrong is time rather than correctness.
- **Nothing checks that a test asserts anything**, and nothing can. What is enforced instead is
  that every Phase 0 invariant has a **recorded demonstration** that its test fails when the
  invariant is broken — see [`MUTATION_TESTING.md`](MUTATION_TESTING.md), which `P0-TSK-038`
  established and `MutationDemonstrationTest` holds to the invariant catalogue on every build.
  What that still cannot check is whether a *recorded* procedure would reproduce today; only an
  in-suite proof answers that, which is why the register records which form each one takes.

---

## 7. Running them

```bash
./gradlew unitTest
```

```bash
./gradlew build
```

`build` runs every hermetic test — unit, architecture and slice — and is green on a machine with
nothing running. It does so through `test`, **not** through the three tier tasks: those are
selection conveniences over the same tests, so CI never executes them. That is why `noTierIsEmpty`
exists — a tier task whose tag selects nothing passes in one second and writes no result file,
and nothing else would ever notice. The database tier is excluded from it deliberately: it is a task you can **see**
did not run, rather than a test that skips itself when its database is absent, because a skipped
test reports success.

```bash
./gradlew databaseTest
```

Needs Docker and nothing else. The harness starts its own PostgreSQL (ADR-0027). Set
`FINAPP_DB_URL` to point the suite at a long-lived database instead — the deliberate escape hatch
for inspecting what a test left behind.
