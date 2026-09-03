# Phase 0 Review Record

**Phase:** 0 — Domain and Architecture Foundation
**Conducted:** 2026-09-03, per [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (`P0-DOC-012`)
**Verdict when conducted:** **the exit gate did not pass.** Two of the twelve universal criteria
failed. **Both have since been closed** — see the Addendum: criterion 11 on 2026-09-03 and
criterion 7 on 2026-09-04.

**Verdict now: all twelve criteria hold. Phase 0 is `COMPLETE` (2026-09-04).**

The intermediate state is left in this document rather than edited away. `PHASE_GATES.md` §1 is
explicit that moving backwards from review is normal and that *"shipping through a failed gate"* is
the failure; a review record rewritten to look as though it always passed would destroy the only
evidence that the gate did its job.

Both failures were known, recorded and external to the architecture. Neither is a defect in what
Phase 0 built. Recording them is the point — `PHASE_GATES.md` §1 is explicit that moving backwards
from review is normal, and that *"shipping through a failed gate"* is the failure.

---

## The verdict in one table

| | Criterion | Status |
|---|---|---|
| 1 | Required functionality exists | **PASS** |
| 2 | Architectural boundaries respected | **PASS** |
| 3 | Required invariants tested | **PASS** |
| 4 | Failure cases handled | **PASS** |
| 5 | Security requirements implemented | **PASS** — with the scope Phase 0 actually has; see §5 |
| 6 | Observability exists | **PASS** |
| 7 | Integration tests pass | ✅ **PASS** — closed 2026-09-04; see the Addendum |
| 8 | Documentation reflects reality | **PASS** — two drifts found and closed by this review |
| 9 | `CURRENT_STATE.md` updated | **PASS** |
| 10 | Relevant ADRs exist and are `Accepted` | **PASS** — ADR-0001…0028 moved to `Accepted` by this task |
| 11 | No unresolved critical or high issue | ✅ **PASS** — closed 2026-09-03; see the Addendum |
| 12 | Formal phase review conducted | **PASS** — this document |

The financial-phase supplement (F1–F8) **does not apply**: Phase 0 creates no posting, moves no
money and touches no balance. That is the phase's defining constraint, not an omission.

---

## 1. Domain correctness

**Do the implemented concepts match the domain model?**

The honest answer is that **almost nothing in the domain model is implemented**, and that is by
design: Phase 0 delivers the financial and platform kernel with zero business capability. Verified
rather than assumed — no production class is named for any of the 62 terms in
[`GLOSSARY.md`](../../domain/GLOSSARY.md).

What Phase 0 *did* establish is the vocabulary those concepts will be built in, and two properties
of it are now mechanically enforced:

- every canonical term is defined with an explicit statement of what it is **not**, and every
  `CLAUDE.md` §Domain Distinctions group is contrasted (`P0-DOC-011`);
- the three senses of time — system time, posting date, value date — are distinguished in
  `DOMAIN_MODEL.md` §Time, and the mechanically enforceable part (no ambient clock read) fails the
  build (`P0-TSK-013`).

**Finding.** The glossary exercise surfaced that `MODULE_ARCHITECTURE.md` §4 places `Risk Score`
under `credit` while `CLAUDE.md` contrasts it with `Credit Score` as a different kind of question.
The register is followed and the ambiguity recorded; resolving it belongs to Phase 10 or 13. It is
listed in §8 below.

**No distinction is collapsed in the code**, because there is almost no code to collapse them in.
The risk this area exists to catch arrives in Phase 3.

---

## 2. Financial correctness

**Walk one real posting end to end.**

**There is no posting to walk.** No ledger, no journal entry, no balance — Phase 0 §2 of the
delivery plan forbids them. This area cannot be conducted as written and must not be reported as
passed.

What *is* verifiable is the kernel a posting will be built from, and it was:

| Property | Evidence |
|---|---|
| No binary floating point on any monetary path | Statically enforced over every class; proven end to end by planting a `double` in `Money`, a `float` in a signature and a `Double.parseDouble` call, each in a different module |
| Currency always explicit | No no-currency constructor; `CHECK` constraint on the column, proven by weakening it |
| Rounding explicit and named | Six named policies; no defaulted mode; each pinned by its defining property |
| Value neither created nor destroyed | Allocation sweeps across 1..100 parts, asserting they actually encountered indivisible remainders |
| Precision preserved in persistence | Round-trip against a real PostgreSQL, including the `BIGINT` extremes and a scale that no longer matches the currency's minor units |
| Overflow rejected, never wrapped | Boundary tests; the same reasoning later applied outside money in `P0-TSK-037` |

**The most valuable thing this area can say about Phase 0** is that the decisions which cannot be
retrofitted were taken before any history exists: integer minor units with a stored scale
(ADR-0003), balances derived rather than stored (ADR-0009), and the ledger as sole writer of
postings (ADR-0002). Each is a decision that would require a data migration of financial history to
reverse, which is why the phase exists.

---

## 3. Boundary integrity

**Did any module reach into another's state?**

No. `app → platform → sharedkernel` is enforced twice — structurally by Gradle, and by ArchUnit as
defence in depth — and the analysis is guarded against becoming vacuous: `ProductionModules`
derives the expected coverage from the classpath, so a module that silently stops being analysed
fails the build rather than passing quietly.

Twelve rules run on every build. `ArchitectureRulesAreDocumentedTest` holds
`MODULE_ARCHITECTURE.md` §6 and the enforced rule set to each other **in both directions**, so a
rule cannot be added without documenting it, nor documented without existing.

**`sharedkernel` is provably framework-free**, which is the boundary most likely to erode silently:
it is the module every other one depends on, so a Spring artefact entering it is invisible until
extraction is attempted.

**Finding, recorded not closed.** The `P0-TSK-041` review established the limit of these rules
plainly: the defect that motivated ADR-0014 — a lease judged against two instances' clocks — used
no lock, no static state and no scheduler. The rules narrow the ways to be wrong; they do not close
them, and the design question stays a review question.

---

## 4. Failure behaviour

**Pick two failure scenarios and trace them through the code.**

### Scenario A — the database commits but the response is lost

A caller submits a money-moving command with an idempotency key. The command executes, the
idempotency record and the business effect commit in one transaction, and the response is lost in
the network.

The retry presents the same key. `IdempotentExecutor` finds a terminal claim whose fingerprint
matches and **replays the stored response byte for byte** — it does not re-execute. Traced through
`resolveExistingClaim`, and proven: eight concurrent duplicates produce one execution, one effect
counted in a side-effect table, and eight identical responses.

The three ways this could go wrong are each closed and each demonstrated:

- a **different** request reusing the key is refused with a distinct conflict, never a silent
  replay — proven this session by removing the fingerprint guard, which fails two tests;
- a **crash mid-command** leaves an `IN_PROGRESS` claim; a stale one is taken over, with the
  staleness test in the database so two reclaims cannot both win;
- **clock skew** cannot steal a live claim, because the lease is set and judged by the server's
  clock (`V004`) — the defect ADR-0014 was written for.

### Scenario B — the service crashes between committing a fact and publishing it

The business fact and its outbox row commit in one transaction; the process dies before the relay
publishes.

`OutboxCrashRecoveryTest` traces the whole chain: the surviving relay publishes the event, once,
carrying the correlation identifier of the flow that produced the fact. An instance killed with
`pg_terminate_backend` while holding the advisory lock releases its aggregate, and another instance
finishes the job — which is what makes the lock's transaction scope load-bearing rather than
stylistic.

**Delivery is at-least-once and the review states it plainly**: a crash *between* publishing and
recording the publication republishes. That is not a defect; the inbox is where it is made
harmless, and a duplicate produces one effect keyed on (consumer, dedupe key).

**The sharpest thing found in this area** was not a crash at all. `P0-TST-006` demonstrated that an
order-dependent consumer is **still wrong under the inbox**: it receives `TransferCompleted` then
`TransferInitiated` and ends up believing a finished transfer is in flight — nothing failed,
nothing retried, no duplicate occurred, and the projection is wrong anyway.

---

## 5. Security

**Enumerate privileged actions and confirm each is authorised and audited.**

**Three privileged actions are registered**, all in the platform: `outbox.EventAbandoned`,
`outbox.EventRetryAuthorised`, `outbox.EventDiscarded`.

**None of the three is emitted.** Two describe a manual procedure performed today with raw SQL; the
third is a relay decision currently only logged. This is recorded as debt with an owning phase, and
it is the most important thing this section can say: an abandoned event — consumers permanently not
receiving a fact that happened — is recorded only in logs, which ADR-0010 is explicit do not count
as an audit trail.

**Authorisation does not exist yet**, and cannot: there is no authentication anywhere, which is
Phase 1's work. The two operational endpoints are unauthenticated, and what they publish is
constrained instead — bodies pinned by exact-match test, twelve other actuator endpoints proven
absent, and no health body naming a dependency, host, driver or exception.

What Phase 0 *did* establish, and where the enforcement is strongest:

| Control | Strength |
|---|---|
| Audit records are append-only | `DB-PRIVILEGE` — the application role holds `INSERT`/`SELECT` and nothing else, proven on **every column** including the column-level grant that had previously slipped through |
| An unestablished actor is an error | `DOMAIN` — `require()` refuses rather than defaulting to `Actor.SYSTEM`, so Phase 1 cannot silently record the platform as having done what a customer did |
| No credential literal in committed configuration | `STATIC` — over files the rule *discovers*, because the CI scanner was measured and does **not** catch `password: hunter2` |
| A remote database must use `verify-full` | `DOMAIN` — the application refuses to start otherwise; the driver's own default was measured to connect unencrypted and report nothing |
| Secrets cannot be rendered | `STATIC` — every field or accessor whose name says it holds a secret must be wrapped |
| Every column classified at its ceiling | `STATIC` — reconciled against the live schema in both directions |

**The one live security finding**, carried as debt: the correlation identifier is caller-supplied,
its charset permits `jane.doe@example.com` and `acct:GB29NWBK...` (confirmed by probe), and it is
written to every log line, stamped on every span and stored in four tables. That is a disclosure
channel into a telemetry backend with different access control — exactly what `INV-AUD-02` forbids.
Bounded today only by there being no customers. Owned by Phase 1.

---

## 6. Test quality

**Do the tests fail when the invariant is deliberately broken?**

This is the criterion Phase 0 answered most completely, and the answer is now **continuous rather
than periodic**. [`MUTATION_TESTING.md`](../MUTATION_TESTING.md) records a demonstration for all
**17** Phase 0 invariants and all **9** `P0-TST-*` items, and `MutationDemonstrationTest` holds
that register to the invariant catalogue, the backlog and the compiled test classes on every build.

Exit criterion 3 was previously answerable only by reading forty change-log entries. It is now a
build failure.

**The register distinguishes two forms**, and the distinction is the finding: an *in-suite* proof
runs on every build and cannot rot; a *recorded* procedure proves the test had teeth **on the day
it was written**. Three of seventeen are in-suite. That ratio is the honest statement of how much
of criterion 3 is verified *now* versus verified *once*.

**The phase found four tests that could not fail**, which is the strongest evidence the practice is
worth its cost:

| Found | What it was |
|---|---|
| `secretsAreWrapped` | `noClasses().should(condition)` inverts events, so the rule was structurally incapable of failing — with a green fixture test beside it |
| Two rules in `P0-TSK-041` | The identical defect, reproduced one task after it was documented |
| `clockSkewCannotStealALiveClaim` | Named for a property it did not exercise: its "fast" clock was forty hours *behind* the server |
| `INV-MON-05` | A test with no demonstration at all — found by this phase's last task |

**Residual risk, stated:** nothing checks that a *recorded* procedure still reproduces. Re-running
one means mutating production code or the schema, which a build must not do to itself.

---

## 7. Documentation drift

**Diff docs against implementation.**

Most of this is now mechanical. **Fifteen documents are declared Gradle inputs**, so an edit to any
of them re-runs the check that reads it, and eleven guards hold documents and code to each other —
several in both directions, one in three.

This review diffed the remainder by hand and found **two drifts, both closed**:

1. **`SYSTEM_ARCHITECTURE.md`'s pinned-version table omitted Prometheus, Grafana and WireMock.**
   The first two are `compose.yaml` images in the version catalogue, guarded by
   `verifyInfrastructureVersions` — so the table under-reported what the drift check actually
   covers, in the very section that explains that check. Added.
2. **`DOMAIN_MODEL.md` spelled `Installment` once** against twenty uses of `Instalment` elsewhere,
   including the module register that assigns its ownership. Corrected during `P0-DOC-011`.

**One documented status was knowingly stale and is not a drift but a scheduling debt:**
`P0-TSK-017` was recorded `BLOCKED` on an HTTP surface and an audit registry that both now exist.
It was unblocked in fact and needed doing rather than unblocking. **Done on 2026-09-03** — see the
Addendum; the backlog is now 62 of 62.

---

## 8. Architectural debt

Nineteen items are recorded in [`CURRENT_STATE.md`](../CURRENT_STATE.md) §Known Architectural Debt,
each with what was deferred, why, the risk carried, the trigger and the owning phase.

**None of it is financial-correctness debt.** `EXECUTION_PROTOCOL.md` §Architectural Debt forbids
accepting that kind, and the review confirms none was: every deferral is a capability that does not
exist yet, an operational concern with no subject, or a control whose first user has not arrived.

The five that matter most, by when they come due:

| Debt | Owning phase | Why it matters |
|---|---|---|
| A caller can put personal data into the correlation identifier | **1** | The widest disclosure channel in the platform; bounded only by having no customers |
| No production code establishes a security scope | **1** | Fails loudly rather than misattributing, but the first audited action must call it |
| Connection-pool sizing across N instances | **3** | Ten instances at Hikari's default exhaust PostgreSQL's `max_connections` before doing any work |
| Broker adapter behind `EventPublisher` | **3** | Nothing implements the port; an unpublished outbox is an empty outbox today |
| The three registered audit actions are not emitted | **15** | An abandoned event is recorded only in logs |

**Added by this review:** `Risk Score`'s owning module is unsettled between `MODULE_ARCHITECTURE.md`
(`credit`) and the sense in which `CLAUDE.md` and the glossary define it (fraud and abuse, which
would be `risk`). Owned by Phase 10 or 13.

---

## The two gate failures

### 🔴 Criterion 11 — three HIGH/CRITICAL vulnerabilities

`CVE-2026-65182`, `CVE-2026-65905` and `CVE-2026-68525`, all in
`org.apache.tomcat.embed:tomcat-embed-core:11.0.24`, which Spring Boot 4.1.1 brings. Trivy reports
the fix in Tomcat 11.0.25.

**Why nobody knew until late.** The repository has no git remote, so CI has never executed, and
until `P0-TSK-040` the scan was a `docker run` line inside a workflow — runnable only by copying it
out by hand. Making it a script a developer can run meant it *was* run, and it failed the first
time anyone tried. This is the clearest instance in the phase of the standing limitation below: a
gate that has never run is a gate whose result nobody knows.

**Not fixed here**, deliberately: it is a dependency upgrade plus the verification-metadata and
lockfile regeneration ADR-0025 requires, and `EXECUTION_PROTOCOL.md` rule 4 forbids doing that
inside another task. It needs its own change, and it is the last thing between Phase 0 and a clean
gate.

### 🔴 Criterion 7 — the suite has never run in CI

All four CI jobs pass when run locally, and `./gradlew build databaseTest` is green from a clean
clone with nothing running. **No CI runner has ever executed them**, because the repository has no
remote.

This affects criterion 1 of the Phase 0-specific list too ("build green in CI from a clean clone"),
and it is the outstanding `DOD-BUILD` item recorded against `P0-TSK-001` through `P0-TSK-005` since
the first week. It closes on the first successful run after a remote is added.

---

## Phase 0-specific exit criteria

| Criterion | Status |
|---|---|
| Multi-module Gradle build green in CI from a clean clone | 🔴 **FAIL** — green locally; CI has never run |
| ArchUnit rules fail on a deliberately introduced boundary violation | **PASS** — verified by introducing one, and now proven in-suite on every build |
| `Money` property tests: associativity, currency mismatch, rounding for 0/2/3 minor units, overflow | **PASS** — 20,000 generated trials per law, checked against `BigDecimal` |
| No floating-point type in any monetary path, statically enforced | **PASS** |
| Idempotency: concurrent identical keys produce one effect; differing fingerprint rejected | **PASS** |
| Outbox: killed between commit and publish; relay recovers and publishes the committed events; consumer sees at-least-once with dedupe | **PASS** — noting *at-least-once* is the delivery guarantee, not exactly-once |
| Inbox: duplicate inbound event produces one effect | **PASS** |
| Audit table rejects `UPDATE` and `DELETE` at the privilege level | **PASS** — on every column |
| One request produces a correlated trace, log line and event with the same `correlationId` | 🟠 **PARTIAL** — log, trace and durable record proven; **event** has no subject, because nothing implements `EventPublisher`. The clause transfers with the Phase 3 broker adapter |
| ADR-0001…ADR-0010 all `Accepted` | **PASS** — done by this task, along with ADR-0011…0028 |

---

## Decisions taken by this review

1. **ADR-0001 through ADR-0028 move from `Proposed` to `Accepted`.** Criterion 10 is a
   *precondition* of the gate, not a reward for passing it: the gate requires the ADRs to be
   accepted, so accepting them is work toward it. Holding ADR-0003 at `Proposed` because Tomcat has
   a CVE would be theatre — the decisions were taken, implemented and tested, and none is
   contingent on either open failure.

2. **Phase 0 remains `IN_PROGRESS`.** Two criteria failed at the time of review; one remains. `PHASE_GATES.md` §4 is explicit that a
   review finding a gate failure returns the phase, and §1 that *"shipping through a failed gate"*
   is the failure — not moving backwards.

3. **What closes the gate**, in order — all three were actioned; see the Addendum for the two that
   are done:
   - ~~upgrade Tomcat past the three CVEs~~ **done**;
   - **add a git remote and observe CI green** — the one that remains, and the only item here that
     cannot be done from inside the repository;
   - ~~reschedule `P0-TSK-017`~~ **done**.

Nothing on that list is architectural. Phase 0's design work is done.

---

## What the phase actually produced

Recorded because a review that lists only failures misrepresents the work.

| | |
|---|---|
| Tasks | **61 of 62** complete |
| Tests | **578 hermetic** (unit, architecture, slice) + **173 database** |
| ADRs | **28**, now `Accepted` |
| Migrations | 9, forward-only, module-owned history |
| Build-enforced rules | Boundaries · no floating-point money · no ambient time · no direct broker publish · no unwrapped secret · no unclassified column · no single-instance assumption |
| Self-maintaining guards | **23** — expectations derived from the codebase, so a new module is covered without anyone remembering |
| Declared documentation inputs | **15** |
| Business capability | **Zero**, by design |

**The recurring finding of the phase, stated once here:** across the last twenty tasks the defect
was almost never in production code. It was in the thing doing the checking — a rule that could not
fail, a sweep reading a stale class file, a regex that read past its section, a coverage list
checked in one direction, a register asserting a demonstration nobody had performed. Phase 0's
lasting output is not the kernel. It is a set of guards that have been shown to bite, and a habit
of proving that they do.

---

## Addendum — 2026-09-03, after the review

Recorded here rather than by editing the assessment above, so the sequence stays visible: the
review found the failure, and the failure was then closed.

### ✅ Criterion 11 closed — the three Tomcat advisories

`tomcat-embed-core` pinned to **11.0.25** in the version catalog and applied as a dependency
**constraint** in `app`. Spring Boot 4.1.1 is the latest stable 4.1.x, so there was no patch
release to move to and `4.2.0-M1` is a milestone; overriding the BOM was the only route. A
constraint rather than `force`, so a future Boot managing 11.0.26 still wins and this pin cannot
hold the platform back — the rot ADR-0026 exists to prevent.

**Verified, not assumed:**

| Check | Result |
|---|---|
| `dependency-scan` after the change | **0 vulnerabilities**, down from 3 CRITICAL |
| Resolved version, all three embed artefacts | 11.0.25 across every configuration in the lockfiles |
| Compatibility | 68 slice tests boot a real Tomcat 11.0.25 on a random port |
| Supply chain | Verification metadata and all six lockfiles regenerated in one invocation |

**The exposure is stated accurately rather than dramatised.** All three advisories are
authentication and authorization bypasses — security-constraint bypass, DIGEST replay, FORM
bypass — and Phase 0 has *no authentication at all*. They were practically unexploitable here. The
gate does not grade on exploitability, and Phase 1 brings precisely what they attack, so the fix
stands on its own.

**One claim was corrected by probing.** The build comment first said the lockfile would reject
removing the constraint. It does not: with the block deleted, resolution still yields 11.0.25,
because the lock applies its own `{strictly 11.0.25}`. The lock *keeps* the version; it does not
object to the loss. Regression needs both the deletion and a lock regeneration — and the
`dependency-scan` job is the control. The comment now says so.

### 🔴 Criterion 7 remains — the suite has never run in CI

Unchanged, and not closable from inside the repository: it needs a git remote and one successful
run. This is the last thing between Phase 0 and a passed gate.

### Revised verdict

**Eleven of twelve criteria hold. Phase 0 remains `IN_PROGRESS` on criterion 7 alone.**

### ✅ `P0-TSK-017` closed — the backlog is 62 of 62

The last backlog item, recorded `BLOCKED` on an HTTP surface and an auditable-action registry that
both landed in M0.4. `@RequiresIdempotencyKey` declares the requirement; an interceptor enforces it
before the handler is entered.

**Its audit clause was corrected rather than approximated.** *"Recorded in audit"* still has no
subject: `AuditRecord` has no field for a key and nothing in Phase 0 writes an audit record in an
HTTP flow — the three registered platform actions are outbox operations and none is emitted. Adding
a column now would be a schema change nothing populates, and unlike actor attribution **no history
is lost by waiting**, which is the test ADR-0010 applies. Transferred to Phase 4. This is the third
Phase 0 criterion to need that correction, after `P0-TSK-014` and `P0-TSK-028`, and for the same
reason each time: a criterion naming a component that does not exist can only be satisfied on paper.

**It also closed a security gap the review's own §7 method surfaced.** Following
`DATA_CLASSIFICATION.md` §5 — which classifies `idempotency_record.idempotency_key` as a
caller-supplied identifier — showed that `IdempotencyKey` carries **no charset**: it bounds length
and blankness because those are the table's `CHECK` constraints. A caller could therefore have put
CR/LF into a value the platform logs, stores durably and will put on an audit record. Closed with
the same default-deny charset the correlation identifier uses.

### ✅ Criterion 7 closed — 2026-09-04. **The gate passes.**

A remote was added (`P0-TSK-042`) and the workflow executed. Run
[33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202), commit `04f4a53`,
all four jobs green:

| Job | Result |
|---|---|
| `build` | ✅ 3m 9s — **32 actionable tasks, 32 executed**; 606 hermetic tests |
| `migrations` | ✅ applied to an empty database, `flywayValidate`, re-applied idempotently, 173 database tests |
| `secret-scan` | ✅ 119 commits, no leaks |
| `dependency-scan` | ✅ SBOM generated and scanned, no HIGH or CRITICAL |

**It took three runs, and the two failures are the finding.** Both were defects that no local run
on this machine could reach, which is the entire argument for the criterion:

1. **`gradlew` was committed mode `100644`.** Four jobs died on `Permission denied`. `core.filemode`
   is false on Windows, so nothing here could notice. `P0-TSK-001` had explicitly enforced LF line
   endings on that exact file so *"Linux CI is not broken by a Windows checkout"* — it reasoned
   about the file's bytes and not about its mode.
2. **`gradle/verification-metadata.xml` was complete for a warm cache only.** Gradle does not
   re-read metadata descriptors it has already parsed, so generation over a warm
   `GRADLE_USER_HOME` records fewer artefacts than a cold resolution needs. Regenerating against an
   empty home added **10 components and 23 artefacts, every one a parent POM or a BOM `.module`** —
   not one jar, which is what identifies the mechanism rather than guessing at it. The file had
   been complete for this machine and incomplete for CI and for any new developer.

A third finding was the scan's, not the build's: gitleaks met this repository's own history for
the first time — `P0-TSK-031` had proven its teeth only against a throwaway clone, deliberately —
and produced one **false positive**, a UUID fixture named `A_KEY`. Allowlisted as that one literal,
with the narrowness demonstrated rather than asserted. See `SECRET_MANAGEMENT.md` §6.

**None of the three was in what Phase 0 designed.** All three were in the machinery that checks it,
which is this phase's recurring finding arriving one final time, at the gate that exists to catch
exactly this class.

### Revised verdict, final

**All twelve universal criteria hold. Phase 0 is `COMPLETE` as of 2026-09-04.**

The financial-phase supplement (F1–F8) does not apply: Phase 0 creates no posting, moves no money
and touches no balance. Phase 1's entry gate criterion 1 is satisfied by this, and Phase 1 becomes
`READY`.
