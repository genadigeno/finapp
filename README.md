# finapp

An enterprise fintech reference platform: double-entry ledger, payments, settlement and
reconciliation, built as a modular monolith with financial correctness enforced mechanically
rather than by convention.

**Phase 0 delivered no business capability, and that was deliberate** — money representation,
idempotency, outbox, audit and correlation cannot be retrofitted once financial history exists.
Four phases on, money does exist: a double-entry ledger, balances explainable from the postings,
holds, and customer-visible internal transfers over HTTP. Where the project stands is summarised
immediately below, and described canonically in
[`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md).

---

## Where the project is

**Phases 0 through 4 are `COMPLETE`, and Phase 5 — Payment Infrastructure — is `READY`**: the
Phase 4 → 5 transition was conducted on 2026-09-20
([`docs/project/reviews/PHASE_4_TO_5_TRANSITION.md`](docs/project/reviews/PHASE_4_TO_5_TRANSITION.md)),
confirming Phase 4 with an independent audit and a **fleet-wide full battery** — 1157 hermetic /
729 database / 14 kafka, 0 failures — and initialising Phase 5 in full: ADR-0045–0049, the
`INV-PAY` invariant group, `PHASE_5_PLAN.md`, and 21 backlog items across nine milestones.

Every number below is counted from this repository rather than recalled: items from
[`docs/project/BACKLOG.md`](docs/project/BACKLOG.md), decisions from [`docs/adr/`](docs/adr/README.md),
properties from [`docs/domain/FINANCIAL_INVARIANTS.md`](docs/domain/FINANCIAL_INVARIANTS.md),
the surface from [`docs/api/openapi.json`](docs/api/openapi.json).
[`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md) is the canonical, always-current
description of where the project is; this section is the summary of it.

```
Programme    █████░░░░░░░░░░░░   5 of 17 phases complete
Backlog      ████████████████░░  160 of 181 elaborated items complete
Phase 4      ██████████████     14 of 14 items, 8 of 8 milestones closed
Phase 5      ░░░░░░░░░░░░░░░░░░   READY — 0 of 21 items, first task P5-TSK-001
```

| | |
|---|---|
| 🔨 **Current work** | **None in progress.** Phase 5 is `READY`; the next task is **`P5-TSK-001`** — the `payments` and `paymentmethods` modules and their privilege floors. The phase where the outcome becomes an unreliable third party's and `INV-LIFE-03` goes live |
| 💰 **Business capability** | Money exists: accounts, a double-entry ledger, explainable balances, holds, and customer-visible internal transfers over HTTP |
| 📐 **Decisions** | 49 ADRs — 44 `Accepted`, and ADR-0045–0049 `Proposed` by the Phase 4 → 5 transition for the phase they open |
| 🔒 **Invariants** | 87 catalogued — the transition added `INV-PAY-01`–05, Phase 5's gate properties given stable IDs before code is written against prose; each in-scope one has a test *demonstrated to fail* when the invariant is broken |
| 🗄️ **Schema** | 55 forward-only migrations across 8 schema-owning modules |
| 🌐 **API** | 39 published paths, 47 operations, compared byte for byte against the running application on every build |
| 🧾 **Audit and errors** | 46 auditable actions, 35 error codes, both reconciled with the code by the build |
| 🧩 **Code** | 10 Gradle modules, 491 production and 298 test source files |
| 📦 **History** | 239 commits, 2026-08-31 to 2026-09-19 |

---

### Roadmap

Seventeen phases. The sequence and the reasoning behind its order live in
[`docs/product/ROADMAP.md`](docs/product/ROADMAP.md); the per-phase engineering plan — objective,
contexts, deliverables, exit criteria, risks and explicit out-of-scope — lives in
[`docs/project/DELIVERY_PLAN.md`](docs/project/DELIVERY_PLAN.md). The gate model and the status
vocabulary are [`docs/project/PHASE_GATES.md`](docs/project/PHASE_GATES.md).

| # | Phase | Status | Primary outcome |
|---|-------|--------|-----------------|
| 0 | Domain and Architecture Foundation | COMPLETE 2026-09-04 | Buildable, boundary-enforced modular monolith; financial and platform kernel |
| 1 | Identity and Customer Foundation | COMPLETE 2026-09-09 | Party/Customer/Identity, authentication, sessions, authorization, audit |
| 2 | KYC/KYB and Consent | COMPLETE 2026-09-13 | Verification lifecycle, screening adapters, append-only consent history |
| 3 | Accounts and Financial Ledger | COMPLETE 2026-09-17 | Chart of accounts, double-entry ledger, balances, holds, reversals |
| 4 | Internal Transfers | COMPLETE 2026-09-19 | The first end-to-end money movement on the ledger |
| 5 | Payment Infrastructure | PLANNED | Payment intent/attempt, provider adapters, auth/capture, refunds, webhooks |
| 6 | Checkout and Merchant Platform | PLANNED | Merchants, checkout sessions, fees, merchant payouts |
| 7 | Cards, Wallets, A2A and Instant Payments | PLANNED | Multi-rail abstraction, disputes and chargebacks |
| 8 | Settlement and Reconciliation | PLANNED | Settlement ingestion, matching, breaks, suspense, investigation |
| 9 | FX and Cross-Border Payments | PLANNED | Quotes, rate locks, multi-currency conversion, cross-border workflow |
| 10 | Credit Decisioning | PLANNED | Credit profile, bureau adapters, versioned policy, explainable decisions |
| 11 | Lending | PLANNED | Applications, offers, disbursement, schedules, repayment, delinquency |
| 12 | BNPL | PLANNED | Merchant-financed instalments, merchant settlement, refund interaction |
| 13 | Risk, Fraud and AML | PLANNED | Signals, rules, decisions, cases, ongoing monitoring |
| 14 | Accounting and Financial Reporting | PLANNED | GL mapping, trial balance, period close, reporting abstraction |
| 15 | Production Hardening | PLANNED | Security hardening, SLOs, runbooks, operational readiness |
| 16 | Scale, Resilience and Disaster Recovery | PLANNED | Load characterisation, degradation modes, backup/restore, DR |

**A phase is not complete because its tasks are.** It is complete when a *review* says so, against
the twelve universal exit criteria plus the phase's own. Phase 0's first review and Phase 1's both
**failed** their gate and returned the phase to `IN_PROGRESS` — which is the model working rather
than a setback. Phase 3 was the first phase whose financial supplement F1–F8 was binding.

---

### Completed phases

| Phase | Items | What it delivered |
|-------|-------|-------------------|
| 0 — Foundation | 63 of 63 | A boundary-enforced modular monolith with the financial and platform kernel and **zero business capability**, deliberately: money representation, idempotency, outbox, audit and correlation cannot be retrofitted once financial history exists |
| 1 — Identity | 35 of 35 | A Party can exist, become a Customer, hold an Identity, prove it over HTTP, hold a session with a recorded assurance level, and have every privileged action authorised and audited — with **no money anywhere in it, by design** |
| 2 — KYC/KYB and Consent | 23 of 23 | A Party verified to the standard a regulator requires, with the evidence retained verbatim, the decision defensible and reproducible, and an append-only consent history with an enforcement gate |
| 3 — Ledger | 25 of 25 | **Money exists**: balanced immutable postings, a balance explainable three independent ways, holds, reversals and four-eyes adjustments that correct mistakes without one committed byte changing, and a trial balance continuously asserted zero per currency |
| 4 — Internal Transfers | 14 of 14 | **Money moves between customers**: an explicit lifecycle whose every state is earned by a producer, idempotency at the financial boundary proven under concurrent submission, conservation under ten instances moving money **both ways**, a privileged reasoned reversal that corrects by referencing rather than editing, and the limit and risk seams as contracts Phase 13 can honour |

Each phase's review record is in [`docs/project/reviews/`](docs/project/reviews/), alongside the
transition audit that opened the phase after it.

---

### ✅ Phase 4 — Internal Transfers

The first customer-visible money movement, and deliberately the **easy half** of moving money: both
legs internal, one database, one transaction, no third party. Its job is to prove the lifecycle,
idempotency, conservation-under-contention and reversal disciplines on the rail the platform
controls entirely — so that Phase 5, where an unreliable provider decides outcomes and `UNKNOWN`
becomes a modelled state, changes one variable at a time rather than four.

Planned in [`docs/project/PHASE_4_PLAN.md`](docs/project/PHASE_4_PLAN.md). Entry gate passed
2026-09-17, all twelve criteria
([`PHASE_3_TO_4_TRANSITION.md`](docs/project/reviews/PHASE_3_TO_4_TRANSITION.md)); closed
2026-09-19 by the exit review
([`PHASE_4_REVIEW.md`](docs/project/reviews/PHASE_4_REVIEW.md)) — 8 areas, 12 universal
criteria, F1–F8 and 16 phase-specific criteria, with **the review's own verdict flipping the
status** and the post-flip battery green.

**Milestones**

| | Milestone | Progress | Note |
|---|---|---|---|
| ✅ | M4.1 — Foundations | `██████████` 2/2 | The `transfers` module, its privilege floor, and the build-graph edges that make the phase's top risk structurally unreachable |
| ✅ | M4.2 — The movement exists | `██████████` 3/3 | Ten instances draining one account accept exactly the affordable transfers, total value conserved to the minor unit, counted in the tables |
| ✅ | M4.3 — Beneficiaries | `██████████` 2/2 | The saved destination, created under a conditional second factor, listed and removed |
| ✅ | M4.4 — Over HTTP | `██████████` 1/1 | `POST /v1/transfers` answering the judgement in the body — a `FAILED` outcome is a `201` that says so, never an HTTP error |
| ✅ | M4.5 — Reversal | `██████████` 1/1 | The privileged, reasoned correction: the original entry byte-identical, both balances restored exactly, the loser of the race refused with nothing posted |
| ✅ | M4.6 — The seams | `██████████` 1/1 | Limits and risk as compiler-required, verdict-returning, in-lock contracts — Phase 13 inherits atomicity and changes no contract |
| ✅ | M4.7 — Observability and demonstration | `██████████` 3/3 | The meters, the dashboard row, conservation under sustained bidirectional contention — which found and fixed a real deadlock — and the register rows, whose audit found the caller had no concurrent-duplicate test and performed it |
| ✅ | M4.8 — The gate | `██████████` 1/1 | The exit review, whose verdict flipped the status — and the flip surfaced nothing, because `P4-TST-002` and `P4-TSK-011` had each pre-paid their half |

**What the review found**

| | Finding | Outcome |
|---|---|---|
| 🧠 | The component register had **no `transfers.beneficiary` row** — and §3 is an *enforced exemption set*, not a description | The register-decay class's **fifth** occurrence and the first *inside* a phase rather than at a boundary; row landed with its provenance |
| 🧠 | `P4-TSK-008`'s backlog block recorded *Completion notes* where every sibling records *Gate evidence*, with its mutation sweep only in `CURRENT_STATE.md` | Restated in the backlog, which is the record |
| ✅ | The ADR index needed **no** repair — the second-copy decay found by hand at three consecutive gates | `P4-TSK-002`'s build guard reconciled both copies when this gate accepted ADR-0043/0044 |
| ⚠️ | Criterion 7 asks for the full suite; the standing instruction skips `build databaseTest kafkaTest` | **Met with the deviation recorded**: hermetic fleet-wide (1157, 0 failures), database and kafka per task, **no fleet-wide count claimed** |

---

### Verification state

The standing instruction on Phase 4 was that the full battery is **skipped**; every task was
verified by targeted tiers and recorded in those words, and the exit review assessed criterion 7
on that evidence with the deviation stated rather than waived.

| | |
|---|---|
| Full battery | **Fleet-wide and current (2026-09-20, the Phase 4 → 5 transition): 1157 hermetic / 729 database / 14 kafka tests, 0 failures** — the first genuine fleet-wide database and kafka count of Phase 4, closing the exit review's recorded deviation. Producing it found and repaired a test-harness defect: the per-JVM PostgreSQL container's default connection ceiling could not carry every cached Spring context's fixed pool, so the fleet-wide `:app:databaseTest` was structurally unable to run — every failure a connection error, zero assertion failures, and the harness now provisions the ceiling the fleet needs |
| CI | Four gates on every push to `master`: build and tests, migrations against a real PostgreSQL, secret scan over full history, dependency scan of a CycloneDX SBOM (see section 6) |

---

## 1. Prerequisites

| You need | Why | Check with |
|---|---|---|
| **Git** | Clone the repository | `git --version` |
| **Docker** with Compose v2 | PostgreSQL, Kafka and Redis run in containers | `docker compose version` |
| A **JDK**, any recent version | Only to start Gradle | `java -version` |

**You do not need to install Java 21, Gradle, PostgreSQL, Kafka or Redis.**

- Gradle is provided by the wrapper (`./gradlew`) and verified by SHA-256 on first use.
- Java 21 is the pinned toolchain. If your machine does not have it, Gradle downloads a
  matching JDK — the build does not use whatever `JAVA_HOME` happens to point at, so the
  bytecode is identical everywhere.
- The infrastructure versions are pinned in `compose.yaml` and asserted against the version
  catalog on every build.

Report the Gradle version and the JVM Gradle itself is running on:

```bash
./gradlew toolchainInfo
```

Note that the launcher JVM is *not* the compile toolchain. The build targets Java 21 whatever
JVM launched it; what proves that is `BuildToolchainTest`, which asserts the emitted bytecode
version and runs as part of `./gradlew build`.

---

## 2. Build and test

From a fresh clone, this is the whole thing:

```bash
./gradlew build
```

That compiles every module, runs the full test suite, and runs the architecture rules that
fail the build on a boundary or floating-point-money violation.

**It needs no infrastructure and no network services.** The suite is hermetic by design: no
test starts a container, and no test skips itself when something is missing. Tests that
genuinely require a database live in a separate task you can see did not run — see §4.

### Test tiers

A test's **tier** is what it needs in order to run, and each tier is its own task. The tiers are
declared once, in `finapp.java-conventions.gradle.kts`, and `TestTaxonomyTest` fails the build
when they, `TestTier`, the tag on a test class, and what CI invokes stop agreeing.

| Task | Runs tests that need |
|---|---|
| `./gradlew unitTest` | nothing beyond the JVM |
| `./gradlew architectureTest` | the compiled classes of every module |
| `./gradlew sliceTest` | a Spring application context |
| `./gradlew databaseTest` | a real PostgreSQL — see §4 |

`build` runs the first three. `unitTest` is the fast inner loop: seconds rather than a minute,
because it starts no Spring context.

The full conventions — which tier a concern belongs to, naming, tagging, and the shared harnesses
— are in [`docs/project/TESTING.md`](docs/project/TESTING.md).

Useful narrower commands:

```bash
./gradlew :sharedkernel:unitTest
```

```bash
./gradlew :app:architectureTest --tests '*ModuleBoundaryRulesTest*'
```

---

## 3. Start local infrastructure

```bash
docker compose up -d --wait
```

`--wait` blocks until every container reports **healthy**, not merely started. Without it the
next command can fail against a database that is still initialising.

| Service | Host address | Role |
|---|---|---|
| PostgreSQL 18.6 | `localhost:5432` | Transactional source of truth — the ledger lives here |
| Kafka 4.3.1 (KRaft) | `localhost:29092` | Event transport. **Never** the accounting source of truth |
| Redis 8.10.1 | `localhost:6379` | Cache, coordination, ephemeral state. **Never** financial truth |

**Tests no longer need this stack.** Since `P0-TSK-035`, `./gradlew databaseTest` starts its own
PostgreSQL container, applies `infra/postgres/initdb/00-roles.sql` and the real migrations, and
throws it away afterwards — so a test run needs Docker and nothing else. Compose is still what
`bootRun` and the Flyway build tasks connect to, and it is still useful when you want a database
that outlives the run: set `FINAPP_DB_URL` and the harness steps aside.

Everything binds to `127.0.0.1`, never `0.0.0.0`, so the stack is not reachable from the
network. Default credentials are `finapp` / `local-development-only-not-a-secret` against
database `finapp`, overridable via `FINAPP_DB_USER`, `FINAPP_DB_PASSWORD` and `FINAPP_DB_NAME`.
They are throwaway local values, deliberately named so they cannot be mistaken for a secret.

That name is not what protects you, though. The application **refuses to start** if that default
is aimed at a database which is not on loopback, because the one documented way around
externalised configuration is forgetting to set the variable
([`SECRET_MANAGEMENT.md`](docs/architecture/SECRET_MANAGEMENT.md), ADR-0020).

Check state at any time:

```bash
docker compose ps
```

### Database roles

PostgreSQL is provisioned with three roles, not one:

| Role | Used by | Holds |
|---|---|---|
| `finapp` | The container's own bootstrap | Cluster superuser. Nothing in this project connects as it except one test fixture |
| `finapp_migrator` | Flyway | Owns the `platform` schema. DDL only, **not** a superuser |
| `finapp_app` | The application | Per-table DML, granted by the migration that creates each table. No DDL |

The split is not cosmetic. `INV-LED-03`, `INV-HIST-01` and `INV-HIST-03` are enforced by the
application role **lacking** a privilege, and a superuser ignores every permission check — so
running as one would make those invariants untestable rather than merely unenforced.

`finapp_migrator` and `finapp_app` are created by
`infra/postgres/initdb/00-roles.sql`, which the container runs on **first initialisation only**.
Roles are cluster objects and so cannot live in a Flyway migration; see the script's header.

**If your volume predates this file**, the script never ran. Either reset:

```bash
docker compose down -v && docker compose up -d --wait
```

or apply it once by hand — `MSYS_NO_PATHCONV=1` is required in Git Bash, which otherwise
rewrites the container path:

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T postgres psql -U finapp -d finapp -f /docker-entrypoint-initdb.d/00-roles.sql
```

Without the roles, `./gradlew :platform:flywayMigrate` fails with
`FATAL: password authentication failed for user "finapp_migrator"`.

---

## 4. Database migrations and database tests

Migrations never run as a side effect of application startup (ADR-0011). Applying them is an
explicit act:

```bash
./gradlew :platform:flywayMigrate
```

Inspect what has been applied:

```bash
./gradlew :platform:flywayInfo
```

Assert that the applied migrations still match the repository — this is what catches an
edited migration file:

```bash
./gradlew :platform:flywayValidate
```

Tests that need a live PostgreSQL are a **separate tier**, not tests that skip themselves:

```bash
./gradlew databaseTest
```

A test that quietly skips when the database is absent reports success, and a suite that
reports success for work it did not do is worse than one that fails. With this split,
`./gradlew build` stays green with nothing running, and an absent database means a task you
can see did not run.

---

## 5. Infrastructure lifecycle

```bash
docker compose stop
```

Stops the containers and keeps everything.

```bash
docker compose down
```

Removes the containers. **Data survives** — the volumes are named, not anonymous.

```bash
docker compose down -v
```

Removes the containers **and discards all data**. This is the deliberate reset. You need it
after changing Kafka's `CLUSTER_ID`, and when moving to a new PostgreSQL major version without
running `pg_upgrade` — a data directory is formatted for the major version that created it.

---

## 5a. The API contract

The public HTTP contract is [`docs/api/openapi.json`](docs/api/openapi.json). It is **generated
from the running application** during `:app:test` and compared byte for byte against that committed
file, so the build fails on any change to the contract - including one nobody meant to make.

Every route is served under `/v1` (ADR-0015). Controllers do not declare the prefix; the
application applies it.

When you change the contract on purpose, the failing test prints each difference labelled
`BREAKING` or `COMPATIBLE`, and tells you what to do. Accepting a compatible change is a copy:

```bash
cp app/build/openapi/openapi.json docs/api/openapi.json
```

If any difference is labelled `BREAKING`, do not copy it. A breaking change is not published under
an existing version - either withdraw it or open the next one.

No OpenAPI machinery is deployed. springdoc is a test-scope dependency, and the running
application serves no `/v3/api-docs`.

---

## 5c. API conventions

[`docs/architecture/API_CONVENTIONS.md`](docs/architecture/API_CONVENTIONS.md) is what a client
author reads: versioning, errors, correlation, request limits, idempotency, pagination and
deprecation, in one place.

Every section is labelled **Implemented** or **Decided, not yet implemented**, because Phase 0
publishes no business endpoint and two of the conventions - idempotency and pagination - are
settled decisions waiting for their first endpoint. `ApiConventionsAreAccurateTest` fails the build
if a stated value stops matching the code, or if a section is added without a label.

---

## 5b. Health, readiness and build info

Three endpoints, deliberately unversioned (ADR-0016):

```bash
curl -s -o /dev/null -w '%{http_code}
' http://localhost:8080/actuator/health/readiness
```

| Endpoint | Answers | Depends on |
|---|---|---|
| `/actuator/health/liveness` | Should this process be killed and restarted? | Nothing external |
| `/actuator/health/readiness` | Should traffic be routed here? | PostgreSQL |
| `/actuator/info` | Which build is running? | Nothing |

**Readiness returns 503 when PostgreSQL is unreachable**, and the application still starts and
still serves those endpoints - so a stopped database gives you an instance that says NOT_READY
rather than a crash-looping one that says nothing.

**Liveness ignores the database on purpose.** If it did not, a thirty-second failover would
restart every instance at once and leave them reconnecting in a herd to a database already in
trouble.

Health responses carry a status and nothing else - no dependency name, no URL, no exception. That
is a security decision, not an oversight; see `docs/architecture/SECURITY_ARCHITECTURE.md`.

Database settings come from the same `FINAPP_DB_*` environment variables `compose.yaml` uses, and
the application connects as `finapp_app` - never the superuser.

---

## 5d. Metrics and the dashboard

The application publishes Prometheus metrics at `/actuator/prometheus`. Names follow
`finapp.<module>.<noun>` and the build fails if one does not (ADR-0018).

To see the dashboard:

```bash
docker compose up -d prometheus grafana
```

Then run the application and open <http://localhost:3000/d/finapp-platform>. Grafana is
provisioned from `infra/grafana/` - the datasource and the dashboard are files in git, not state
in a volume, so `allowUiUpdates` is off: edit the JSON, not the UI.

Prometheus scrapes the application on the **host**, not in a container, because that is where a
developer runs it.

**A metric never carries a correlation identifier, an account, or anything else a request can
influence.** That is both a cardinality rule and a security rule - a tag value from a request
multiplies one series into thousands and puts it in a system with months of retention. Correlation
belongs on a trace and in a log.

**An unreadable metric reports absent, not zero.** Stop PostgreSQL and `finapp_outbox_pending`
becomes `NaN` rather than `0`, so an alert written on the value still fires.

---

## 5e. The first administrator

`POST /v1/identities/{id}/roles` requires the `ROLE_ASSIGN` permission, which only the
`ADMINISTRATOR` role grants — so **the first administrator cannot be created through the API**. That
circularity is deliberate rather than an oversight, and the two alternatives were both rejected:

- **A bootstrap endpoint** would be a privileged surface with no authorisation in front of it, which
  is the thing the rest of this module exists to prevent.
- **Seeding a row in a migration** would put an administrator into *every* environment, including
  production, permanently, with credentials nobody chose.

So the first grant is made out of band by an operator with database access, against an identity that
has already registered normally:

```bash
docker compose exec postgres psql -U finapp_migrator -d finapp -c "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i WHERE i.login_identifier = 'REPLACE_ME'"
```

**The consequence is recorded rather than hidden: that first assignment has no actor in the audit
trail**, because no authenticated actor performed it — `assigned_by` names the subject itself, which
is the one self-grant the platform contains and the reason `IdentityAdministration` refuses every
later one. Every subsequent assignment names two parties.

An operator doing this is a privileged-access event in its own right, and Phase 15 owns the controls
for that (`CURRENT_STATE.md` §Known Architectural Debt).

## 6. What CI checks

Four independent jobs, so a failure names its own gate
([`.github/workflows/ci.yml`](.github/workflows/ci.yml)):

| Job | Gate |
|---|---|
| `build` | Compilation, tests, module boundary rules, infrastructure version drift, wrapper checksum |
| `migrations` | Migrations apply to an empty database, re-apply idempotently, still match the repository, and monetary values round-trip through real columns |
| `secret-scan` | No secret anywhere in git history — not just at the tip |
| | *and in `build`:* no credential literal in any committed configuration file |
| `dependency-scan` | No HIGH or CRITICAL known vulnerability in the resolved dependency set |

The `migrations` job starts PostgreSQL from this same `compose.yaml`, so CI and your machine
run the identical pinned image.

The secret scan is one definition, and you can run it:

```bash
./infra/scripts/secret-scan.sh
```

CI calls that same script, so the pinned image and the arguments exist in one place rather than
two that agree today. It scans **history**, not the working tree — a secret committed and later
removed is still disclosed.

**Two mechanisms, deliberately.** The scanner detects by the *value*'s shape and the build rule by
the *key*'s name, so they are blind in different directions: gitleaks catches a private key and
misses `password: hunter2`, which is exactly what a human commits. Do **not** commit a dummy secret
here to test the scan — history is scanned, so it would stay red for ever. Use a throwaway clone;
the procedure is in [`SECRET_MANAGEMENT.md`](docs/architecture/SECRET_MANAGEMENT.md) §6.

**The pipeline runs.** A remote was added on 2026-09-04 (`P0-TSK-042`) and the four jobs execute on
every push to `master`. The first run failed, which is the gate working: it found two defects that
could not be reached from a Windows machine at all — `gradlew` committed without its executable
bit, and verification metadata that was complete only for a *warm* dependency cache. Both are
fixed and described in §7a.

---

## 7a. Changing a dependency

Every artefact the build resolves is checksum-verified and version-locked
(`gradle/verification-metadata.xml` and a `gradle.lockfile` per project). Both are enforced, so
changing a dependency is three steps rather than two:

```bash
# 1. Change the version in gradle/libs.versions.toml - the single source.
# 2. Regenerate BOTH records in ONE invocation.
./gradlew --write-locks --write-verification-metadata sha256 build databaseTest
```

**One invocation, both flags** - this was two separate commands until `P0-TSK-035` added a
dependency and found that neither order works. The lock refuses a version it does not know, so
metadata generation cannot resolve; verification refuses an artefact it has no checksum for, so
lock generation cannot resolve. Each control blocks the other's regeneration. Passing both flags
together is the only sequence that succeeds.

**`build-logic` is a separate included build and is regenerated separately.** A root
`--write-locks` does not touch it - verified - so a change to the Kotlin DSL or toolchain
dependencies needs:

```bash
cd build-logic && ../gradlew --write-locks build
```

Its artefacts are covered by the root verification metadata either way, because dependency
verification is Gradle-wide and reaches included builds; only the version lock is separate.

Then **read the diff**. That is the step that does the work:

- the lockfile diff names every version that moved, including transitives a BOM bumped for you;
- the metadata diff names every artefact whose bytes are now trusted.

A change you cannot explain in those two diffs is the signal this exists for.

**Both task lists matter.** `build databaseTest` is what covers every configuration; a narrower run
adds only what it resolved. That is safe — regeneration merges, and existing entries survive,
which is verified — but it will not *record* anything the narrow run did not touch, so a later full
build fails with a missing-checksum error rather than a wrong one.

**And so does a cold cache — this is the part that was missing.** Regenerating over a warm
`GRADLE_USER_HOME` records *less* than a cold one needs, because Gradle does not re-read metadata
descriptors it has already parsed. The first CI run failed on exactly that: `kotlinx-coroutines-bom
:1.8.0.pom`, absent from a file generated on this machine over months of warm builds. Regenerating
against an empty `GRADLE_USER_HOME` added **10 components and 23 artefacts**, every one of them a
parent POM or a BOM `.module` — descriptors, never a jar. The file had been complete for this
machine and incomplete for every other, CI and a new developer alike.

If a regeneration is going to be trusted, do it cold:

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-locks --write-verification-metadata sha256   build databaseTest
```

On a machine behind TLS interception, that temporary home needs the truststore setting from
`CURRENT_STATE.md` §Local Environment Prerequisites — it is outside the repository by design, so a
fresh home does not inherit it.

**Removing a dependency leaves its entries behind.** Gradle merges and never prunes, so a superseded
version stays trusted. Delete those entries by hand in the same change; the lockfile is what shows
you which ones.

**What this does and does not protect.** The checksums are trust-on-first-use: they record what was
downloaded when they were written, so they catch a substitution *afterwards* and cannot catch a
first download that was already compromised. The lockfile catches an accidental version drift, not
an attacker — anyone who can edit it can edit the version catalog beside it. See
[ADR-0025](docs/adr/ADR-0025-dependency-verification-and-locking.md).

**If verification fails**, the report at `build/reports/dependency-verification/` names the artefact
and the expected checksum. Treat a mismatch as a compromise until proven otherwise: check the
artefact against the publisher's own published checksum, never against the one the build just
downloaded.

## 7b. Keeping the pins fresh

Everything here is pinned, which means everything here can rot. Three mechanisms, because no one
of them reaches all of it (ADR-0026):

| Pinned thing | Where | Update path |
|---|---|---|
| Four GitHub Actions (commit SHA) | `.github/workflows/ci.yml` | Dependabot, weekly - a pull request |
| Library versions | `gradle/libs.versions.toml` | Dependabot, weekly - the PR **will fail its build** until you run §7a's regeneration |
| Two scanner images (digest) | `infra/scanner-pins.sh` | `infra/scripts/check-pinned-images.sh`, weekly in CI |
| Infrastructure images | `compose.yaml` + the catalog | `verifyInfrastructureVersions` fails the build on drift |
| Gradle distribution | `gradle-wrapper.properties` | Manual, with the checksum from `services.gradle.org` |

Check the scanner pins yourself at any time:

```bash
./infra/scripts/check-pinned-images.sh
```

It distinguishes two findings, and the distinction matters:

- **the tag moved** - the digest no longer matches the version it claims. The pin held. Find out
  why the tag changed *before* updating it;
- **a newer release exists** - ordinary rot.

To update a scanner: bump `VERSION` in `infra/scanner-pins.sh`, re-resolve the digest with
`docker buildx imagetools inspect <repository>:<version>`, and re-run the check.

**Why the scanner pins are not Dependabot's job.** They live in a shell-sourced file it does not
read, and they stay there deliberately: both scanners are invoked through `infra/scripts/`, which
CI calls, so a developer and CI run the identical image. Moving a digest into the workflow so a bot
could see it would put the same fact in two places.

---

## 7. Troubleshooting

**`PKIX path building failed` / `unable to find valid certification path`**

Something is intercepting HTTPS and re-signing it — corporate proxy, or antivirus with HTTPS
scanning (AVG and Kaspersky both do this). Your OS trusts that CA; the JDK's own trust store
does not, which is why `curl` and the browser work while Gradle does not.

On Windows, add this to `~/.gradle/gradle.properties` — **outside the repository**, because it
is a property of your machine, not of this project:

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

Bootstrapping the Gradle distribution runs in a separate JVM that reads `GRADLE_OPTS`, so on a
machine with no Gradle distribution cached yet, also export:

```bash
export GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatively, import the intercepting CA into the JDK's `cacerts`, or turn off HTTPS scanning.

**`clean` fails with "Unable to delete directory"** (Windows)

An orphaned Gradle daemon is holding module jars open. `./gradlew --stop` handles the usual
case. A daemon whose `GRADLE_USER_HOME` has been deleted survives that and must be killed by
PID:

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleDaemon' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**`docker compose exec` rewrites absolute paths** (Git Bash on Windows)

MSYS rewrites container paths, so `/opt/kafka/bin/...` becomes `C:/Program Files/Git/opt/...`.
Prefix the command with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use
only — health checks run inside the container and are unaffected.

**`databaseTest` fails to connect**

The database is not up, or is still initialising. `docker compose up -d --wait` returns only
when it is healthy; `docker compose ps` shows the current state.

---

## 8. Where to read next

| Question | Document |
|---|---|
| What is built right now? | [`docs/project/CURRENT_STATE.md`](docs/project/CURRENT_STATE.md) |
| What are the rules for changing this codebase? | [`CLAUDE.md`](CLAUDE.md) |
| What must never be violated? | [`docs/domain/FINANCIAL_INVARIANTS.md`](docs/domain/FINANCIAL_INVARIANTS.md) |
| How are modules bounded and enforced? | [`docs/architecture/MODULE_ARCHITECTURE.md`](docs/architecture/MODULE_ARCHITECTURE.md) |
| Why was something decided this way? | [`docs/adr/`](docs/adr/README.md) |
| Where does the whole programme go? | [`docs/product/ROADMAP.md`](docs/product/ROADMAP.md) |
| What is the plan? | [`docs/project/DELIVERY_PLAN.md`](docs/project/DELIVERY_PLAN.md), [`docs/project/BACKLOG.md`](docs/project/BACKLOG.md) |
| When is work actually done? | [`docs/project/DEFINITION_OF_DONE.md`](docs/project/DEFINITION_OF_DONE.md) |
