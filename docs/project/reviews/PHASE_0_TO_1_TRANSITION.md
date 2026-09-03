# Phase 0 → Phase 1 Transition Record

**Conducted:** 2026-09-03
**Scope:** formal gate audit of Phase 0, architectural review, and the Phase 1 entry gate.

> **Result: Phase 0 does not pass. One criterion fails, and it cannot be closed from inside this
> repository.** Phase 0 remains `IN_PROGRESS`. The Phase 1 entry-gate work is complete and Phase 1
> becomes `READY` the moment that criterion closes.

This is the second time the gate has been assessed. The first
([`PHASE_0_REVIEW.md`](PHASE_0_REVIEW.md)) found two failures and its Addendum records one being
closed. This record audits the phase against the thirteen areas named for the transition, which is
a wider frame than the twelve universal criteria, and then reviews the architecture.

---

# PART 1 — Phase 0 gate audit

## 1a. The thirteen transition areas

| # | Area | Result | Evidence |
|---|---|---|---|
| 1 | Fintech domain model | **PASS** | `DOMAIN_MODEL.md` + `GLOSSARY.md`: 62 terms, each with what it is **not** and its owning module; all eight `CLAUDE.md` distinction groups contrasted; enforced by `DomainGlossaryTest` in both directions |
| 2 | Bounded contexts | **PASS** | 28 contexts, each mapped to exactly one of 24 modules (ADR-0012); single ownership verified by script, not by reading; merges carry recorded split triggers |
| 3 | System architecture | **PASS** | `SYSTEM_ARCHITECTURE.md` + ADR-0001 (modular monolith) + ADR-0014 (N instances, never 1); every pinned version single-sourced and drift-checked |
| 4 | Data ownership | **PASS** | `MODULE_ARCHITECTURE.md` §5: every authoritative state has exactly one owner, table verified; `DATA_ARCHITECTURE.md` states the source-of-truth rules |
| 5 | Financial invariants | **PARTIAL** | 64 catalogued with enforcement and verification; all 17 Phase 0 ones demonstrated to fail. **But there is no identity/credential/session group** — see §2b Finding 1 |
| 6 | Ledger principles | **PASS** | ADR-0002 (postings authoritative), ADR-0009 (balance derived), ADR-0003 (money representation), `LEDGER_MODEL.md`; `INV-LED-*`/`INV-BAL-*` catalogued with Phase 3 enforcement |
| 7 | Security architecture | **PASS** | `SECURITY_ARCHITECTURE.md`; six controls enforced at build or startup rather than documented — no credential literal, no unwrapped secret, no unclassified column, no unverified remote TLS, no unestablished actor, no unpinned artefact |
| 8 | Event architecture | **PASS** | `EVENT_ARCHITECTURE.md` + ADR-0005; ten-field envelope mandatory at construction, outbox atomic with the fact, inbox deduplication keyed per consumer, all four `INV-EVT-*` demonstrated |
| 9 | API principles | **PASS** | `API_CONVENTIONS.md` + ADR-0015; every section labelled `Implemented` or `Decided, not yet implemented` with its owning task, and an **unlabelled section fails the build** |
| 10 | Testing principles | **PASS** | `TESTING.md` + ADR-0028 (tiers by requirement); `MUTATION_TESTING.md` + `MutationDemonstrationTest` make exit criterion 3 continuous rather than gate-time |
| 11 | Project execution protocol | **PASS** | `EXECUTION_PROTOCOL.md` + ADR-0007; observed in practice — three criteria corrected rather than approximated, debt recorded with owning phases, no phase skipped |
| 12 | Documentation | **PASS** | 15 documents are declared Gradle inputs; 11 guards hold documents and code to each other, several bidirectionally |
| 13 | ADR coverage | **PASS** | ADR-0001…0028, all `Accepted`; ADR-0029…0032 added by this transition as `Proposed` |

## 1b. The twelve universal exit criteria

| # | Criterion | Result |
|---|---|---|
| 1 | Required functionality exists | **PASS** — 62 of 62 backlog items |
| 2 | Architectural boundaries respected | **PASS** |
| 3 | Required invariants tested | **PASS** — 17 of 17, continuously enforced |
| 4 | Failure cases handled | **PASS** |
| 5 | Security requirements implemented | **PASS** — at the scope Phase 0 has |
| 6 | Observability exists | **PASS** |
| 7 | Integration tests pass | 🔴 **FAIL** |
| 8 | Documentation reflects reality | **PASS** |
| 9 | `CURRENT_STATE.md` updated | **PASS** |
| 10 | Relevant ADRs exist and are `Accepted` | **PASS** |
| 11 | No unresolved critical or high issue | **PASS** — closed 2026-09-03 |
| 12 | Formal phase review conducted | **PASS** |

### 🔴 Criterion 7 — the only failure

**The suite has never run on a CI runner**, because the repository has no git remote. Verified for
this audit: `git remote -v` is empty and the only branch is `main`.

All four CI jobs pass when run locally and `./gradlew build databaseTest` is green from a clean
clone. *Green locally* and *green in CI* are different claims, and the second is what criterion 7
asks for. It is also the Phase 0-specific criterion "build green in CI from a clean clone" and the
`DOD-BUILD` item outstanding against `P0-TSK-001`–`005` since the first week.

**This cannot be closed from inside the repository.** It needs a remote and one successful run.

**Remediation task created: `P0-TSK-042`**, the sole item of a new `P0-EPIC-13` — *Exit gate
remediation*. It carries no new architecture, its acceptance criterion is the four CI jobs
themselves, and it deliberately adds no test: a test asserting "CI has run" could only assert its
own environment, which is the vacuity every guard in this repository exists to avoid. Its risk is
recorded as Medium rather than Low, because the first CI run of a build that has only ever run on
one Windows machine routinely fails on path case, line endings and cache assumptions — and those
failures must be fixed in the workflow or the build, never by relaxing a gate.

## 1c. Phase 0-specific criteria

| Criterion | Result |
|---|---|
| Build green in CI from a clean clone | 🔴 **FAIL** — the same cause as criterion 7 |
| ArchUnit rules fail on a deliberate violation | **PASS** |
| `Money` property tests across 0/2/3 minor units | **PASS** |
| No floating point on any monetary path | **PASS** |
| Idempotency: concurrent keys, differing fingerprint | **PASS** |
| Outbox: crash between commit and publish, recovery | **PASS** — at-least-once, stated as such |
| Inbox: duplicate produces one effect | **PASS** |
| Audit rejects `UPDATE`/`DELETE` at privilege level | **PASS** — on every column |
| One request → correlated trace, log line and event | 🟠 **PARTIAL** — log, trace and durable record proven; **event** has no subject, nothing implements `EventPublisher`. Transfers with the Phase 3 broker adapter |
| ADR-0001…0010 `Accepted` | **PASS** |

## 1d. Verdict

**Phase 0 remains `IN_PROGRESS`.** One mandatory criterion fails and one is partial with a named
owning phase. `PHASE_GATES.md` §4: *a review that finds a gate failure returns the phase to
`IN_PROGRESS`*, and §1: *shipping through a failed gate is the failure*.

Nothing outstanding is architectural. Phase 0's design work is complete.

---

# PART 2 — Phase 0 architectural review

The eight-area review is in [`PHASE_0_REVIEW.md`](PHASE_0_REVIEW.md) and is not repeated. This
section covers what the transition specifically asks for: weaknesses, inconsistencies, unresolved
questions, missing invariants, unnecessary complexity, premature technology, and forward risk.

## 2a. Architectural weaknesses

**W1 — Nothing has ever run outside one developer's machine.** Every claim in this repository is
true on one Windows laptop with one Docker daemon. The build is hermetic by construction and the
database tests bring their own PostgreSQL, so the risk is lower than it would otherwise be — but it
is not zero, and criterion 7 exists precisely to retire it. *Closes with `P0-TSK-042`.*

**W2 — The multi-instance rules narrow the ways to be wrong without closing them.** ADR-0024's four
build rules catch `synchronized`, process-local locks, ambient scheduling and static mutable state.
The defect that motivated ADR-0014 used **none of them** — it was a clock comparison. This is
recorded in the ADR and remains true. *Mitigation: it stays a design-review question, and Phase 1
adds sessions and lockout counters, which are exactly the shape that tempts process-local state.*

**W3 — Correlation identifiers are caller-supplied and reach every log line and span.** The widest
disclosure channel in the platform, bounded today only by there being no customers. **Phase 1 is
when customers arrive.** *Recorded as debt with Phase 1 as owner; see Risk R1.*

**W4 — Three privileged actions are registered and none is emitted.** The audit registry can prove
a *recorded* action is catalogued; it cannot detect an action that writes no record. Phase 15 owns
the completeness verification. Phase 1 substantially reduces the exposure by making audit writes
routine rather than theoretical.

## 2b. Missing invariants — the most significant finding

**Finding 1: there is no invariant group for identity, credentials or sessions.**

The catalogue has fifteen groups — `INV-MON`, `LED`, `BAL`, `HIST`, `IDEM`, `CON`, `LIFE`, `REV`,
`EVT`, `SET`, `REC`, `FX`, `ACC`, `AUD`, `CRD` — and **none of them covers**:

- a credential is never stored reversibly;
- session revocation takes effect immediately, not eventually;
- MFA cannot be bypassed by an alternative path;
- account recovery cannot be used to take over an account;
- authentication failure discloses nothing about whether the account exists.

These properties exist today **only as Phase 1 exit criteria** in `PHASE_GATES.md`. That is a
materially weaker regime than every other property in the platform gets, and the asymmetry is
backwards: Phase 1 is the phase whose *product* is security.

Concretely, what they miss by not being catalogued:

| Catalogued invariants get | Gate criteria get |
|---|---|
| A stable ID, cited from code and documents | Nothing citable |
| A named enforcement mechanism, ranked by strength | Prose |
| A named verification method | Prose |
| A row in `MUTATION_TESTING.md`, enforced on every build | Checked once, at the gate |
| `PHASE_GATES.md` §3 criterion 3 coverage | *They are* the criteria — circular |

**Recommendation, applied by this transition:** add an **`INV-IDN`** group — Identity, Credentials
and Sessions — with seven invariants. This is the one change in this review that materially
improves the long-term architecture, and it costs nothing to make now and a great deal to retrofit
after Phase 1 has written credential-handling code against prose.

## 2c. Inconsistencies between documents

**I1 — Authorization is forbidden from being collapsed, and is not a bounded context.**
`CLAUDE.md` §Domain Distinctions forbids collapsing *Identity / Authentication / Authorization*.
`BOUNDED_CONTEXTS.md` names context #2 as "Identity & Authentication" — no Authorization — while
`MODULE_ARCHITECTURE.md` gives `identity` ownership of "Role assignment". So authorization is
implemented inside a context that does not name it.

This is **not** a domain collapse: the glossary keeps the three concepts distinct and the module
register is explicit about what `identity` owns. It *is* an unrecorded merge, and ADR-0012 requires
every merge to carry a justification and a named split trigger. Every other merge in the register
has one; this has neither.

*Applied: recorded as a deliberate merge with a split trigger, in ADR-0031.*

**I2 — `CAPABILITY_MAP.md` lists "Account recovery" under Customer and Identity**, with no owner
stated. `MODULE_ARCHITECTURE.md` puts recovery initiation in `identity`'s API list. Not a
contradiction, but the capability map is the only document that leaves it ambiguous. *Applied:
Phase 1's plan assigns it to `identity` explicitly.*

**I3 — No inconsistency found** in the ledger, event, data or security architecture documents
against the implementation. The eleven document guards make most of this class mechanically
impossible; the two drifts the first review found by hand-diffing were both in the un-guarded
remainder, and both were closed.

## 2d. Unresolved domain questions

Twelve are recorded in `CURRENT_STATE.md`. **Two bear on Phase 1** and are answered by this
transition; the rest are Phase 3+ and are deliberately left open.

| # | Question | Status |
|---|---|---|
| — | How are Party, Customer and Identity separated? | **Answered — ADR-0029** |
| — | What is the authorization model and where is the decision point? | **Answered — ADR-0031** |
| 12 | Data-access mechanism: JPA, Spring Data JDBC or plain JDBC | **Still open, and Phase 1 forces it.** See Risk R2 |

**Question 12 is now urgent.** It was raised by `P0-TSK-011` and deferred because Phase 0 had no
persistence beyond the platform kernel, which uses plain JDBC. Phase 1 introduces six aggregates
with real persistence. Choosing by accident — by writing the first repository in whatever style is
convenient — is exactly what `MoneyColumns` was written mechanism-agnostic to prevent.

*Applied: `P1-TSK-001` is the ADR, and it blocks every persistence task in the phase.*

## 2e. Unnecessary complexity

**None found that is worth removing.** Two candidates were considered and rejected:

- **Four test tiers where two would do.** The tiers partition the suite exactly and `unitTest` runs
  in 14 seconds against `build`'s minute. The split earns its keep on the inner loop.
- **Twenty-five self-maintaining guards.** They look like a lot for a phase with no business
  capability. But the phase found *four tests that could not fail*, three criteria that could not
  be satisfied as written, and a register asserting a demonstration nobody performed. The guards
  are the reason those were found rather than shipped.

One thing is worth watching rather than changing: **the guards now cost real build time**, and
Phase 1 will add more. If `build` passes roughly three minutes, the tiering already exists to split
it.

## 2f. Premature technology decisions

**None.** Reviewed specifically:

| Decision | Premature? |
|---|---|
| PostgreSQL as transactional truth | No — ADR-0001's single-database argument is the whole basis of transfer atomicity |
| Kafka pinned and running | **Watch.** It is in `compose.yaml` and the catalogue, and **nothing connects to it.** No client on the classpath, no adapter, no consumer. It costs a container and a version to maintain for a Phase 3 need |
| Redis pinned and running | Same, and more so — no Phase 1 or Phase 3 task needs it. Session storage is the obvious future use and Phase 1 deliberately does **not** use it (ADR-0030) |
| Spring Boot 4.1.1 / Java 21 | No — the reasoning for starting on the current major rather than the previous one is recorded and has already paid off |
| Tomcat pinned above the BOM | No — forced by three CRITICAL advisories, with a revisit condition recorded |

*Recommendation: leave Kafka and Redis in `compose.yaml`. Removing them would break
`verifyInfrastructureVersions`' coverage and they cost nothing but disk. But **do not** let their
presence justify using them: `SECURITY_ARCHITECTURE.md` already records that neither has a
transport guard because neither has a client, and that reasoning must hold.*

## 2g. Risks carried into Phase 1

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| **R1** | **The correlation identifier becomes a PII channel the day real customers exist.** A caller-supplied value reaching every log line and span, in a backend with different access control and months of retention (`INV-AUD-02`) | **High** | `P1-TSK-002` constrains it before any customer endpoint ships. This is the first Phase 1 task after the ADR for exactly this reason |
| **R2** | **The data-access mechanism gets chosen by accident.** Hibernate's dirty checking emits `UPDATE`s, and `INV-LED-03`/`INV-HIST-01` forbid updating financial records — the application role holds no `UPDATE` privilege at all | **High** | `P1-TSK-001` decides it by ADR before any repository is written |
| **R3** | **Collapsing Party/Customer/Identity.** `DELIVERY_PLAN.md` §17 names it as the phase's top risk: very expensive to unpick | **High** | ADR-0029 makes them three aggregates with three lifecycles; `P1-TSK-003` proves the separation by test before any auth work |
| **R4** | **Rolling bespoke cryptography** | **High** | ADR-0032: vetted libraries only, no custom primitives, parameters recorded per credential |
| **R5** | **Recovery becomes the weakest link** — the classic account-takeover vector, and it bypasses MFA by design | **High** | Deferred to the last Phase 1 milestone, deliberately, so it is built against a working MFA and session model rather than in parallel with them |
| **R6** | **Connection-pool sizing across N instances.** Hikari's default of 10 per instance × 10 instances exhausts PostgreSQL's default `max_connections` before any work | Medium | Phase 1 is the first phase with real pool usage. `P1-TSK-004` does the arithmetic |
| **R7** | **Session state tempts process-local storage** — exactly the shape ADR-0024's rules do not catch | Medium | ADR-0030 makes sessions database-backed and revocation immediate; `DISTRIBUTED_EXECUTION.md` §3 registers any new process-local state |

**No risk here is new information.** Six of the seven are already recorded as debt or as delivery-
plan risks. Collecting them at the boundary is the point: they are the things Phase 1 must not
discover on its own.

---

# PART 3 — Phase 1 entry gate

Assessed against `PHASE_GATES.md` §2.

| # | Criterion | Result |
|---|---|---|
| 1 | Hard dependency phases are `COMPLETE` | 🔴 **NOT MET** — Phase 0 is `IN_PROGRESS` on criterion 7 |
| 2 | `DELIVERY_PLAN.md` section current and specific | **MET** — and expanded into [`PHASE_1_PLAN.md`](../PHASE_1_PLAN.md) |
| 3 | Bounded contexts and aggregates identified | **MET** |
| 4 | Invariants identified by ID | **MET** — including the new `INV-IDN-01…07` |
| 5 | Lifecycles/state machines drafted | **MET** — five |
| 6 | Transaction and consistency boundaries stated | **MET** |
| 7 | Idempotency stated for money-moving commands | **MET (vacuously, and stated as such)** — Phase 1 moves no money. Registration is made idempotent anyway |
| 8 | External dependencies and failure modes listed | **MET** — Phase 1 has no external provider, which is itself recorded |
| 9 | Security, audit and reconciliation implications stated | **MET** — reconciliation: none financial |
| 10 | Backlog at task granularity with acceptance criteria | **MET** — 25 items (`P1-TSK-001`…`024`, `P1-DOC-001`), each with acceptance criteria and a DoD profile |
| 11 | Required decisions have an ADR in `Proposed` | **MET** — ADR-0029…0032 |
| 12 | `CURRENT_STATE.md` names it the active phase | **NOT YET** — deliberately, see below |

**Eleven of twelve are met. Criterion 1 is not, and criterion 12 must not be forced ahead of it.**

`PHASE_GATES.md` §1: *a phase may not become `READY` while a hard dependency is not `COMPLETE`*.
Phase 1 is therefore recorded as **`PLANNED` — entry gate satisfied except criterion 1**, and
becomes `READY` automatically when Phase 0 closes. All entry-gate work is done; the elaboration
that §2 criterion 10 requires *is* the first activity of the entry gate, and it is complete.

**One command from a different answer.** Adding a remote and observing one green run closes Phase 0
criterion 7, which closes Phase 1 entry criterion 1, and Phase 1 becomes `READY` with no further
analysis.
