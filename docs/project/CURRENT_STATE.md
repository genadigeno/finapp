# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

**History lives in [`history/`](history/)** — per-task records, closed milestones, completed
capabilities and the change log. This document stays current; the archives stay archived.

Last updated: 2026-09-20 (`P5-TSK-016` — the refund surface and events; **M5.7 CLOSES at 2 of 2**, next `P5-TSK-017`)

---

## Current Phase

**Phase 0 — Domain and Architecture Foundation**
Status: ✅ **`COMPLETE`** (2026-09-04) — **all twelve exit criteria hold.**

**Phase 1 — Identity and Customer Foundation**
Status: ✅ **`COMPLETE`** (2026-09-09) — **all twelve universal and all six phase-specific exit
criteria hold**, ruled by the re-run of the exit review
([`reviews/PHASE_1_REVIEW.md`](reviews/PHASE_1_REVIEW.md), addendum of 2026-09-09, `P1-DOC-002`).

The path there is the gate model working as designed: the review of 2026-09-08 (`P1-DOC-001`)
failed the gate on two criteria; the remediations landed the same day (`P1-TSK-027`, `P1-TSK-029`);
and the phase stayed `IN_PROGRESS` for a day **on purpose**, because a phase becomes `COMPLETE`
when a **review** says so, never because its remediation landed. The re-run re-assessed all twelve
criteria with **recounted** evidence — the first review had three numbers wrong for inheriting them
— and found one more defect of the phase's recurring class on the way (`P1-TSK-033`, below).

**What the phase delivered**: a Party can exist, become a Customer, hold an Identity, prove it over
HTTP, hold a session with a recorded assurance level, and have every privileged action authorised
and audited — 17 published endpoints, 10 tables in two new schema-owning modules, 20 auditable
actions, 8 new invariants (72 platform-wide), 6 ADRs, 864 hermetic and 465 database tests, and no
money anywhere in it, by design.

**Two items leave the phase open-eyed rather than silently**: `P1-TSK-033`
(`POST /v1/me/credential`, planned and unbuilt, found by the re-run's recount) and the broker
adapter (owned now by the Phase 1 → 2 transition, whose phase holds the first consumers). Neither
is named by any exit criterion; both are recorded with owners.

**Phase 2 — KYC/KYB and Consent**
Status: ✅ **`COMPLETE`** (2026-09-13) — **all twelve universal and all six phase-specific
exit criteria hold**, ruled by the exit review
([`reviews/PHASE_2_REVIEW.md`](reviews/PHASE_2_REVIEW.md), `P2-DOC-001`). Entry gate passed
2026-09-09, all twelve criteria
([`reviews/PHASE_1_TO_2_TRANSITION.md`](reviews/PHASE_1_TO_2_TRANSITION.md)); started the same
day with `P2-TSK-001`, and closed four days later at **23 of 23** backlog items.

**Two criteria were not passing when the review opened, and the review closed both rather than
waiving them.** Criterion 3 wanted a mutation-register row for `INV-KYC-06`, which `P2-TST-001`
had deferred *in writing* to this review — landed, and **performed rather than inferred**
(the `P0-TSK-038` finding, applied to the last row of the set). Criterion 8 found two drifts in
`PHASE_2_PLAN.md` §11, one of them a **delivery guarantee the architecture deliberately
refuses to make**: the milestone table still promised *"exactly once per fact"* through Kafka,
which `P2-TSK-001`'s design corrected at the time in the backlog and here but not in the plan.
An adapter claiming exactly-once invites consumers to skip their inbox.

**The flip is itself the guarded act — and flipping it found an invariant nobody had
counted.** Since the transition's guard redesign, recording a phase `COMPLETE` changes what the
build demands, so the review's order was **land the row → flip the status → re-run the
full battery**. The battery then **failed**, naming one missing element: **`INV-HIST-02`**, which
is marked `Phase: 2 (screening), 5 (providers), 8 (files)` in the catalogue and therefore belongs
to Phase 2 while sitting in neither of the phase's named groups. **Phase 2 has eleven invariants,
not ten** — and the plan, the transition, this document and the review's own first draft all
said ten, because each counted the two groups the transition *created*. That is `P1-TSK-024`'s
finding repeating (`INV-AUD-03` was missed the same way): **a phase's invariants are what the
catalogue says they are, not what its plan remembers creating.** The property was never
unprotected — evidence has been retained verbatim since `P2-TSK-009` and the append-only
grant sits at `DB-PRIVILEGE` — what was missing was the record that the test has teeth, which
is exactly the gap the register exists to close. Row landed, demonstration **performed**, battery
green. Had the flip come after the final battery rather than before it, the phase would have been
recorded `COMPLETE` on a build about to fail, and the failure would have surfaced in Phase 3
attributed to whatever touched the tree first.

**What the phase delivered**: a Party verified to the standard a regulator requires, with the
evidence retained and the decision defensible — 2 new modules, 11 tables, 13 migrations, 13
endpoints, 9 auditable actions, 10 new invariants (**82** platform-wide, **11 in scope**), 4
ADRs, 1025 hermetic / 584 database / 14 kafka tests, and **no money anywhere in it, by design**.

**Phase 3 — Accounts and Financial Ledger**
Status: ✅ **`COMPLETE`** (2026-09-17) — **all twelve universal criteria, all eight F1–F8
supplement criteria — binding for the first time — and all sixteen phase-specific criteria
hold**, ruled by the exit review
([`reviews/PHASE_3_REVIEW.md`](reviews/PHASE_3_REVIEW.md), `P3-DOC-001`). Entry gate passed
2026-09-13, all twelve criteria
([`reviews/PHASE_2_TO_3_TRANSITION.md`](reviews/PHASE_2_TO_3_TRANSITION.md)); started the
same day with `P3-TSK-001` and closed four days later at **25 of 25** backlog items across
eight milestones, all `CLOSED`.

**The review's area 2 had a subject for the first time in the programme, and the posting was
walked**: an operator's correction as the economic event, propose-then-approve as the domain
operation, the approval's transaction as the financial transaction, the `ADJUSTMENT` journal
entry with the approver as its actor, balanced per-currency lines, and the balance reaching
the customer three ways that each say which number they are — every step naming its code and
its test. **The financial supplement F1–F8 was re-assessed at the gate rather than inherited
and every criterion is met**, F5 with its Phase-3 vacuity stated.

**The flip was the guarded act, and this time it surfaced nothing** — the review ran in the
`P2-DOC-001` order (assess → corrections → **flip** → full battery) and the post-flip battery
was green, because `P3-TST-003` had predicted the one failure the flip would have produced
(`INV-AUD-04`'s missing register row) and `P3-TSK-021` pre-paid it. The gate machinery found
its defect **before** the gate instead of at it. **One mutation survived across the whole
phase, correctly** (`P3-TSK-003`'s defence-in-depth predicate); zero survived wrongly.

**What the phase delivered**: money exists — a verified customer opens an account, receives
balanced immutable postings, sees the balance as a transactional projection, a
replay-from-zero derivation and a reconciling statement, holds funds against it, has mistakes
corrected by referencing reversals and four-eyes adjustments without one committed byte
changing, and closes the product with the accounting intact, while the trial balance is
continuously asserted zero per currency. 2 new modules, 8 tables, 13 migrations, 9 operations
on 7 new paths, 8 auditable actions all emitted, 2 permissions + 1 role, 5 event types, 5
aggregates, 4 ADRs (`Accepted`, platform → 42), 0 new invariants (the catalogue was written
for this phase; 19 in scope, 19 register rows), 138 mutations across 22 items with 1 correct
survivor, and **1121 hermetic / 683 database / 14 kafka tests** after the flip.

**The transition's four decisions carried the phase and are now `Accepted`**: `READ COMMITTED`
with postings as inserts and balance-dependent decisions taking the account lock (ADR-0039);
the flat typed chart (ADR-0040); the transactional projection no decision may read (ADR-0041);
the four account concepts (ADR-0042). **`DB-PRIVILEGE` finally carries `INV-LED-03` and
`INV-HIST-01`** — the mechanism built in `P0-TSK-022` met the tables it was built for.

**One item left the phase open-eyed rather than silently**: plan §9 declared
`GET /v1/ledger/accounts/{id}` and `GET /v1/ledger/trial-balance` behind a `LEDGER_READ`
permission, and none of the three was built or owned by any task — the recurring
unowned-declaration class, found by the review's hand-diff. **Ruled by the Phase 3 → 4
transition: the declaration is struck** — the trial-balance capability exists as the
continuous job and gauge, and an operational read surface with no consumer is dead contract;
it arrives with the operator tooling that consumes it, as its own decision.

**Phase 4 — Internal Transfers**
Status: ✅ **`COMPLETE`** (2026-09-19) — **all twelve universal criteria, all eight F1–F8
supplement criteria re-assessed at the gate, and all sixteen phase-specific criteria hold**,
ruled by the exit review ([`reviews/PHASE_4_REVIEW.md`](reviews/PHASE_4_REVIEW.md),
`P4-DOC-001`). Entry gate passed 2026-09-17, all twelve criteria
([`reviews/PHASE_3_TO_4_TRANSITION.md`](reviews/PHASE_3_TO_4_TRANSITION.md)); started the
same day with `P4-TSK-001` and closed two days later at **14 of 14** backlog items across
eight milestones, all `CLOSED`.

**Area 2 had a transfer to walk, which is what this phase was for**: a customer's instruction
as the economic event, `TransferExecution` as the domain operation, one local transaction as
the financial transaction (ADR-0043), a `POSTING` journal entry whose reference carries the
transfer id, balanced per-currency lines debiting the source wallet and crediting the
destination, both balances moving as a transactional projection, and a reversal that corrects
by referencing rather than editing — every step naming its code and its test. **The financial
supplement F1–F8 was re-assessed at the gate rather than inherited, and every criterion is
met**, F5 with its Phase-4 reading stated: no external event produces a financial effect here,
and the mechanism that will bind is the Phase 0/2-proven inbox.

**The flip surfaced nothing, and that was pre-paid twice rather than lucky**: `P4-TST-002`
landed a register row for every invariant the catalogue marks `Phase: 4` and **probed the
flip** — simulated `COMPLETE`, battery green, then one row removed to prove the demanded set
had grown — and `P4-TSK-011` landed §15's meters with a pinned guard that the derived one
takes over at the flip with no edit. The gate machinery found its work done before the gate
instead of at it, for the second phase running.

**The review's own findings were two, both in the record rather than the code**: the
component register had no `transfers.beneficiary` row — the register-decay class's **fifth**
occurrence and the first *inside* a phase rather than at its boundary — and `P4-TSK-008`'s
backlog block carried *Completion notes* where every sibling carries *Gate evidence*, with its
eight-mutation sweep written only into this document. Both corrected in the review.

**One criterion is met with a recorded deviation, stated rather than glossed**: criterion 7
asks for the full suite against real infrastructure, and the owner's standing instruction
skips `build databaseTest kafkaTest`. The hermetic tier — which is where the flip's own
guards live — was run **fleet-wide**; the database and kafka tiers were verified per task by
targeted suites throughout. **No fleet-wide database or kafka count is claimed for this
phase.**

Planned in [`PHASE_4_PLAN.md`](PHASE_4_PLAN.md): **the first customer-visible money
movement** — a verified customer moves funds between two platform accounts, with the
ADR-0044 lifecycle, idempotency at the financial boundary, every command audited, a
privileged reasoned reversal, second-factor beneficiary creation, and the limit/risk
**seams** as compiler-required parameters Phase 13 will implement. Decisions in ADR-0043
(the transfer and its posting commit in **one transaction** — no internal saga; unresolved
question 5 closed) and ADR-0044 (four states, each earned by a producer — no `PROCESSING`,
no `CANCELLED`, no fiction), both `Accepted` at this gate. 14 backlog items across 8 milestones
(M4.1–M4.8); the in-scope invariants are whatever the catalogue marks `Phase: 4` — **five**
at planning time (`INV-IDEM-01` transfers element, `INV-CON-02`, `INV-LIFE-01/-02/-04`), no
new group needed for the second transition running. Deliberately the *easy* half of moving
money — both legs internal, no third party — so Phase 5 changes one variable at a time.

**Phase 5 — Payment Infrastructure**
Status: **`IN_PROGRESS`** — started 2026-09-20 with `P5-TSK-001`; entry gate passed the same day, all twelve criteria
([`reviews/PHASE_4_TO_5_TRANSITION.md`](reviews/PHASE_4_TO_5_TRANSITION.md)). The external
world arrives: money movement whose outcome is decided by an unreliable third party, with
`INV-LIFE-03` live for the first time. Planned in [`PHASE_5_PLAN.md`](PHASE_5_PLAN.md);
decisions in ADR-0045–0049 (`Proposed`): the intent/attempt model and its three machines,
**no transaction spans a provider call** (dispatch-before-call, `UNKNOWN` modelled,
reconciliation by query with no lease), webhooks (authenticated before parsing,
freshness-bounded, evidence-first, order-blind), **authorization is a payment-domain fact
and the ledger's first touch is capture** (unresolved question 6 closed — DR `PSP_CLEARING`
/ CR wallet, with the refund holding its funds at dispatch), and the first provider — a
simulated card-style PSP whose finality is nothing-final-before-settlement (question 9
closed; `INV-REV-03` stays subjectless until the second rail). The transition catalogued
**`INV-PAY-01`…`05`** — Phase 5's gate properties given stable IDs before code is written
against prose, the `INV-IDN`/`INV-KYC` precedent — taking the platform to **87
invariants**; the in-scope set is whatever the catalogue marks `Phase: 5`, **eleven** at
planning time. 21 backlog items across nine milestones (M5.1–M5.9);
[`PAYMENT_LIFECYCLES.md`](../domain/PAYMENT_LIFECYCLES.md) rewritten from its stub to the
decided model. First task: **`P5-TSK-001`**, `READY`.

Planned in [`PHASE_2_PLAN.md`](PHASE_2_PLAN.md): a Party verified to the standard a regulator
requires, with evidence retained and the decision defensible — KYC/KYB cases, screening with
human-resolved hits, encrypted access-audited documents, an append-only consent history with an
enforcement gate, and the platform's first broker adapter and consumer. Decisions in
ADR-0035…0038 (`Proposed`); properties in the new `INV-KYC-01`…`06` and `INV-CNS-01`…`04` groups
(**82 invariants** platform-wide). 24 backlog items across six milestones; the two items the exit
review left open — the broker adapter and `P1-TSK-033` — are scheduled first, in M2.1.

**The transition repaired its own gate machinery before using it**: both phase-derived guards
(`MutationDemonstrationTest`, `PlannedMetersExistTest`) keyed on the highest phase *named*, which
would have demanded Phase 2's meters and demonstrations at entry and silently dropped Phase 1's
plan from the checked set. They now key on phases recorded **`COMPLETE`**, so the status flip is
the guarded act — and `CURRENT_STATE.md` plus the phase plans are now declared build inputs,
because the probe that found this passed against a build that had not run (the `P0-TSK-023`
class, again).

## Current Milestone

The active milestone is the one named in [§Current Task](#current-task) below, which is the
section this document keeps current. The closed milestone records for Phases 0-4 — every
milestone's stated acceptance and the demonstration that met it — are archived verbatim in
[`history/MILESTONE_HISTORY.md`](history/MILESTONE_HISTORY.md).

*(Until 2026-09-20 this section carried those closed records inline, under two separate
`## Current Milestone` headings — a structural drift that made "current" mean "every milestone
since M0.1". Moved, not edited.)*

## Current Task

**`P5-TSK-017` — the meters and the dashboard row** — `READY`.
M5.8 opens with it: `PHASE_5_PLAN.md` §15 real — the six meters, eager from a plain context
(pinned Phase-5 guard until the flip, the established shape), counted from judgements' own
vocabulary post-commit (replays/converges never throughput); `provider` and `operation` join
`ALLOWED_TAG_KEYS` as bounded compile-time sets, the decision recorded;
`unknown.active`/`unknown.age` as database gauges, NaN never zero, `max()` fleet-wide; the
*Payments* dashboard row resolving against a live scrape. Accept: a fresh instance publishes
every series; the mutation sweep over the counting discipline; queries resolve live. See the
backlog entry.

### Just completed

**`P5-TSK-016` — the refund surface and events** — `COMPLETE` (2026-09-20). **M5.7 CLOSES at
2 of 2: the refund is observable, publishable and asynchronously resolvable — and the schema
corrected the design before a line landed.** The sketched rewrite-the-stored-response
collided with platform `V003`'s freeze (`INV-LIFE-04`: a terminal claim's response IS what a
replay renders), so the invariant stood and the design adapted: **the platform's first
two-transaction keyed command** — `IdempotentExecutor.begin`/`complete` +
`DispatchCommand`, Tx1 committing the dispatch beside the claim held `IN_PROGRESS`, Tx2
completing it with the judged `refundId|status`, `V008`'s `dispatch_key` making the takeover
re-run converge (the reconstructed-crash test: no second hold, the wire re-driven with the
STORED reference, byte-for-byte thereafter). The webhook completes UNKNOWN refunds through
the shared `applyRefund` under the ONE enumerated site; the facts publish inside the
conditionals (`RefundInitiated`/`RefundCompleted`/`RefundFailed`, no amounts — needle-
tested; `UNKNOWN` publishes nothing, the standing hold its visible record); the view's
`refunded`/`refundPending` derive from the rows (ADR-0045).

| Acceptance criterion | Evidence |
|---|---|
| The acceptance chain over HTTP | Operator 201 → webhook heal through the real door → the customer's GET moves; the no-500 sweep beside it |
| The derived totals reconcile with the rows | Independent SQL, with a FAILED refund in the picture — freed budget in neither total |
| A replayed refund key replays byte-for-byte | The sharpest form: original UNKNOWN, webhook completes, replay answers the original bytes while the GET shows the heal |

### Eight mutations — one survived its first run, and that is the battery working

All eight ended caught, restores `cmp`-verified: the NAMED replay-from-a-re-read (the healed
`COMPLETED` leaked — the byte-equality probe refused it); the NAMED
webhook-default-made-success; **the fact-outside-the-conditional SURVIVED round one** — the
resolver's own from-state gate absorbs sequential terminal re-reports before `applyRefund`,
masking the sequential-duplicate probe (two absorption layers, blind in different
directions — the battery's own observation, recorded) — caught by the TEN-WAY RACE: ten
facts where one belongs; `RefundInitiated` dropped; the claim never completed (the replay
answered `IdempotencyInProgress` where the recorded bytes belong); the attribution dropped;
the from-state gate dropped (the loud illegal edge where quiet evidence belongs); the
freed-budget lie. **Verified by targeted tiers — `:payments:test` 108 / `:platform:test`
171 / `:app:test` 444 / the payment database suites 80 (schema 10, authorization 8, capture
6, endpoints 10, unconfigured 1, webhook 6, transitions 7, sweeper 8, ambiguity 5, refund
13, refund endpoints 6) / the executor's database suite 17, 0 failures, fresh runs — the
full battery deliberately skipped on the owner's instruction; no fleet-wide database or
kafka counts claimed.** Refund sweeping stays a recorded deferral: the webhook is the
resolver, the standing hold the loud symptom, the sweep extension the phase audit's.

### Previously

The per-task completion records behind this one — 120 blocks, from `P5-TSK-015` back to project
initiation — are archived verbatim in [`history/TASK_HISTORY.md`](history/TASK_HISTORY.md).
Each records what the task delivered, the mutations performed, and the findings made on the way.

---

## Completed Capabilities

**Business capabilities: none in Phase 0** — by design. What each closed phase delivered is
archived verbatim in
[`history/COMPLETED_CAPABILITIES.md`](history/COMPLETED_CAPABILITIES.md).

## Active Work

**Phase 5 is `IN_PROGRESS`** — M5.1–M5.7 `CLOSED` (3+2+3+3+2+2+2): `P5-TSK-001`…`-016` and
`P5-TST-001` complete. Next: `P5-TSK-017`, `READY` — the meters and the dashboard row,
M5.8's open.

The last work performed was the **Phase 4 → Phase 5 transition** (2026-09-20):
Phase 4 confirmed by independent audit, the first fleet-wide full battery of
the phase (1157 / 729 / 14, 0 failures — after finding and repairing the
test-harness connection ceiling that had made the fleet-wide database tier
structurally unable to run), ADR-0045–0049 `Proposed`, the `INV-PAY` group
catalogued (87 invariants), `PHASE_5_PLAN.md` and 21 backlog items across
nine milestones, `PAYMENT_LIFECYCLES.md` rewritten, and questions 6, 9 and
the overdue 10 closed.

*(This section named `P2-TSK-001` as next until `P3-TSK-001`'s gate — stale across the whole of
Phase 2, found by re-reading the document the gate updates.)*

*(This section had said "Phase 1 is `READY` but not started" since 2026-09-04 — pre-existing drift
the re-run's criterion 9 check caught, corrected here rather than left because a review about
documentation reflecting reality must not leave its own document stale.)*

## Blockers

**None.**

~~**The suite has never run in CI.**~~ — **resolved 2026-09-04** by `P0-TSK-042`. The remote is
`https://github.com/genadigeno/finapp`, and the four jobs run on every push to `master`. Run
[33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202) is green on all four.
This closes exit criterion 7, the Phase 0-specific "build green in CI from a clean clone", and the
`DOD-BUILD` item outstanding against `P0-TSK-001`–`005` since the first week.

**It took three runs, and *green locally* versus *green in CI* turned out to be exactly the
distinction the criterion exists for.** Two defects, neither reachable from this machine:

1. **`gradlew` was committed mode `100644`.** Four jobs died on `Permission denied`, exit 126.
   `core.filemode` is false on Windows, so nothing here could observe it — and `P0-TSK-001` had
   enforced *LF line endings* on that same file so *"Linux CI is not broken by a Windows
   checkout"*, reasoning about the file's bytes and not its mode.
2. **`gradle/verification-metadata.xml` was complete for a warm cache only.** Gradle does not
   re-read metadata descriptors it has already parsed, so generation over a warm
   `GRADLE_USER_HOME` records fewer artefacts than a cold resolution needs. Cold regeneration added
   **10 components and 23 artefacts, every one a parent POM or a BOM `.module`** — not one jar,
   which is what identifies the mechanism rather than guessing at it. The file had been complete
   for this machine and incomplete for CI and for any new developer. `README.md` §7a now
   regenerates against a temporary home.

A third finding belongs to the secret scan rather than the build, and is recorded under Known
Architectural Debt: gitleaks met this repository's own history for the first time and produced one
false positive.

~~**The `dependency-scan` CI gate fails.**~~ — **resolved 2026-09-03.** Three **CRITICAL**
advisories in `org.apache.tomcat.embed:tomcat-embed-core:11.0.24`, which Spring Boot 4.1.1 brings:
`CVE-2026-65182` (security-constraint bypass), `CVE-2026-65905` (DIGEST authenticator replay) and
`CVE-2026-68525` (FORM authentication bypass).

Fixed by pinning Tomcat to **11.0.25** in the version catalog and applying it as a dependency
**constraint** — Spring Boot 4.1.1 is the latest stable 4.1.x, so there was no patch release to
move to, and 4.2.0-M1 is a milestone. A constraint rather than `force`, so a future Boot managing
11.0.26 still wins. The scan now reports **zero** vulnerabilities, and the 68 slice tests boot a
real Tomcat 11.0.25, so compatibility is proven rather than assumed.

**The exposure was recorded honestly rather than overstated**: all three are authentication and
authorization bypasses, and Phase 0 has no authentication at all. Practically unexploitable here —
but the gate does not grade on exploitability, and Phase 1 brings exactly what they attack.

**One claim was corrected by probing.** The first version of the build comment said the lockfile
would reject removing the constraint. It does not: with the block deleted, resolution still yields
11.0.25 because the lock applies its own `{strictly 11.0.25}`. The lock *keeps* the version; it
does not object to the loss. A regression needs both the deletion and a lock regeneration, and the
`dependency-scan` job is the control.


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
| ~~`P0-TSK-001` — `P0-TSK-005`~~ | ~~`DOD-BUILD` requires "CI green"~~ — **closed 2026-09-04** by `P0-TSK-042`. All four jobs green on a runner, from a clean checkout: run [33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202). Outstanding since the first week, and closing it found two defects local runs could not reach. | — |
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
| ~~**Broker adapter behind `EventPublisher`.**~~ - **closed 2026-09-09** by `P2-TSK-001`. `KafkaEventPublisher` publishes every outbox event to Kafka - payload bytes verbatim, envelope as record headers, aggregate as the record key, one topic per producing module - and `OutboxRelaySchedule` polls on every instance, safely, because the per-aggregate advisory lock is the lease (`DISTRIBUTED_EXECUTION.md` §3). Delivery is at-least-once with `finapp.eventId` as the consumer dedupe key, and the crash duplicate is DEMONSTRATED in `KafkaOutboxDeliveryKafkaTest` rather than hidden. | - | - | - | - |
| **Outbox retention.** Published rows are never deleted | `V005` says a published row may be deleted once retained long enough for diagnosis; the sweep is a scheduled job with its own cluster-safety question, and no task owned it | Unbounded table growth. The partial pending index does **not** grow with it — published rows leave it — so the cost is storage and vacuum, not relay latency | Table size becoming operationally material | Phase 15 (data retention and deletion) |
| ~~**Relay metrics.**~~ - **paid in full 2026-09-09** (`P0-TSK-029` the gauges, `P2-TSK-001` the counters): `finapp.outbox.publication` by outcome (published, failed, deadlettered), registered eagerly and fed from `RelayPollResult` by the schedule that now actually runs. The eager series is asserted before any flow in `OutboxRelayScheduleKafkaTest` | Nothing schedules a relay, so those meters would be structurally always zero - which reads as "nothing is failing" rather than "nothing is running" | The remaining risk is narrower: a relay that is running but failing is visible as a growing backlog, not as a failure count | A scheduled relay | Phase 3 |
| **Inbox retention sweep.** Records are never deleted | The sweep is a scheduled job with its own cluster-safety question, and `V007` deliberately adds no `expires_at` index until its predicate is written | Unbounded growth of a table whose only index is its primary key. **Not** a correctness risk in this direction: a record that is never swept deduplicates forever, and it is early expiry that admits a duplicate (`DATA_MIGRATIONS.md` §9) | Table size becoming operationally material, or the first consumer going live | Phase 15 (data retention and deletion) |
| ~~**Inbox metrics.**~~ - **paid in full 2026-09-09** by `P2-TSK-002`, whose trigger this row named: *"the first live consumer"*. `finapp.inbox.consumption` by outcome (processed, duplicate, contended, failed), registered eagerly and fed from `ReceiverPollResult` by the consumer loops; asserted present at zero before any record has ever arrived | - | - | - | - |
| **Audit retention and archival.** Records are never deleted, and the application role cannot delete them | ADR-0010 is explicit that deletion is not an option and that archival must preserve queryability - which is a Phase 15 deliverable, not a sweep | Unbounded growth of a table written on every privileged action. **Not** a correctness risk: the inability to delete is the invariant working, and archival must preserve the trail rather than trim it | Table size becoming operationally material | Phase 15 (retention and archival) |
| ~~**Four-eyes approver is not modelled.**~~ - **dissolved 2026-09-17** by `P3-TSK-021`, and *dissolved* is the accurate word: the anticipated "second actor column" was never added, because a four-eyes action is **two acts, each with one actor** - `ledger.AdjustmentProposed` names the initiator with the justification, `ledger.AdjustmentPosted` names the approver - and the pairing lives on `ledger.adjustment_proposal` (approver ≠ initiator at `DB-CONSTRAINT`, plus a deferred trigger refusing any unapproved `ADJUSTMENT` COMMIT). ADR-0010's follow-up now records that later phases' four-eyes actions should look at the proposal row's shape before adding columns | - | - | - | - |
| **The three registered platform actions are not emitted.** `outbox.EventAbandoned`, `outbox.EventRetryAuthorised`, `outbox.EventDiscarded` | Two describe the manual procedure in `EVENT_ARCHITECTURE.md` §Handling an abandoned event, performed today with raw SQL; the third is a relay decision currently only logged. Wiring them is a change to `P0-TSK-020`'s relay and to tooling that does not exist | An abandoned event - consumers permanently not receiving a fact that happened - is recorded only in logs, which ADR-0010 is explicit do not count as an audit trail. This is exactly the gap the registry exists to make visible | Dead-letter tooling, or the relay taking an `AuditWriter` | Phase 15 (dead-letter handling), or sooner if the relay is revisited |
| ~~**No ingress correlation filter.**~~ — **closed** by `P0-TSK-025`. `CorrelationFilter` establishes a scope per request at `HIGHEST_PRECEDENCE` and echoes the identifier in `X-Correlation-Id`; every response carries it, error or not. | — | — | — | — |
| ~~**The ingress filter must wrap error handling.**~~ — **closed** by `P0-TSK-025`. The filter is ordered outside the dispatcher and its scope closes only after the whole chain, error handling included. | — | — | — | — |
| ~~**Thirteen test classes open connections through their own private helper.**~~ — **closed** by `P0-TSK-036`. All thirteen now use `DatabaseRoles`, so the property names and the driver call have one definition. What they had been copying was a connection as the **superuser**, which `DatabaseRoles.bootstrap()` now documents as the wrong default and confines to tests making no privilege claim. All 173 database tests pass unchanged. | — | — | — | — |
| **Redis is plaintext with no enforcement; Kafka is now guarded.** `P2-TSK-001` brought the first Kafka client and, with it, `KafkaTransportGuard` - a non-loopback bootstrap over `PLAINTEXT` refuses startup, which is ADR-0023's recorded promise kept on schedule. TLS/SASL themselves remain Phase 15's deployment posture, and the guard's limit is stated in `SECURITY_ARCHITECTURE.md` | There is still no Redis client, so a Redis guard would guard nothing | **Bounded**: the local broker is loopback-only and the guard holds the boundary; Redis carries no risk until a client exists | The first Redis client; a deployed broker for the TLS posture | Phase 15 |
| ~~**A caller can put personal or financial data into the correlation identifier.**~~ - **closed 2026-09-04** by `P1-TSK-002` / ADR-0034. The platform now mints the identifier on every request and never adopts an inbound one; a well-formed caller value is echoed in `X-Client-Correlation-Id` and reaches no sink. **Narrowing the charset was the obvious repair and does not work** - a date of birth, a phone number and an account number are alphanumeric, so any charset still able to carry a UUID carries them; of the four probed values it would have stopped two and left two. The control had to be structural. | - | - | - | - |
| ~~**No production code establishes a security scope.**~~ - **closed 2026-09-06** by `P1-TSK-006`. `RegistrationService` establishes one for `POST /v1/registrations`, and the actor is `enterSystem()` because the caller is **unauthenticated** - which is a call site that *stays* after Phase 1 revisits it, not one to be removed. The alternative, attributing the action to the Party it creates, is circular and is unavailable on the refusal path where nothing was created; an actor that differs between success and failure is worse than a uniform honest one. The information is carried by the audit record's **target** instead - the attempted login identifier, on both paths. | - | - | - | - |
| ~~**The loopback confinement is per credential, not a general mechanism.**~~ — **paid 2026-09-20** by `P5-TSK-002` | **The trigger is now met**: `P2-TSK-011`'s callback signing key (`FINAPP_KYC_CALLBACK_KEY`, `CallbackKey`) arrived as the **fourth** credential, again confined and tested in `MfaKey`'s shape rather than by generalising, because folding a refactor of three proven guards into a callback task is `EXECUTION_PROTOCOL.md` rule 4's case — but the row's own trigger ("the fourth credential, or Phase 5's provider adapters — whichever asks first") has fired, so the generalisation is **due as its own piece of work** rather than the next task's side effect | **Low but no longer shrinking.** The build rule remains general - a fifth credential cannot arrive as a literal - and four hand-written instances of one shape is exactly the count at which the copies start to drift | **Paid in full (2026-09-20, `P5-TSK-002`)**: one mechanism (`ConfinedCredential.KeySpec`), the four guards re-expressed over it with their untouched suites as the equivalence proof, and the acceptance mutation — the confinement removed — failing every consumer at once. The fifth and sixth credentials arrive as one-line specs | — |
| ~~**No output scrubber for text the platform does not control.**~~ - **answered 2026-09-06** by `P1-TSK-009`, and the answer is that the scrubber is **not built**. A scrubber is a deny-list over emitted text, and to recognise a secret it must be *given* the secret - which makes the plaintext travel **further**, into a filter invoked on every log statement in the platform, rather than less far; it also produces exactly the false confidence ADR-0019 warns about, since a deny-list that misses one shape is indistinguishable from one that misses none. **What replaces it is the opposite shape and is checkable**: a plaintext can only reach any sink if something first *unwraps* it, and every unwrap is a call to `expose()` - named to be found, deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in `identity`**, so a new unwrap anywhere fails the build and forces a decision. **The residual is stated rather than closed**: inside `identity` a plaintext could still be handed to a log call and nothing mechanical would catch it - bounded by the set being four classes rather than a codebase, and by the one production log call on that path being asserted quiet against a real database. |
| **The scrape endpoint widens the unauthenticated surface to three.** `/actuator/prometheus` joins health and info | `DOD-OBS` requires the dashboard to render live data from a running instance, which needs a scrape endpoint, and there is no authentication anywhere yet | A scrape publishes JVM internals, HTTP route templates and pool statistics - a description of the running system rather than its secrets. The **content** is constrained by a build failure: no tag may carry a request-influenced value | `P0-EPIC-10` landing | Phase 0, M0.4 |
| **The operational endpoints are unauthenticated.** `/actuator/health/*` and `/actuator/info` are reachable by anyone who can reach the port | `DOD-API` requires a negative authentication test for every new surface, and there is no authentication anywhere in the platform yet - `P0-EPIC-10` is the epic that brings it. Building one authentication mechanism for the actuator alone would be a second scheme to retire | **Low, and bounded by what is published.** The bodies are pinned by exact-match test to a status and, for the aggregate, its group names; details, components, environment, JVM and OS are all off, and twelve other endpoints are proven absent. What remains is that an unauthenticated caller can learn the instance is up and which build it runs | `P0-EPIC-10` landing, at which point `show-details: when-authorized` also becomes available | Phase 0, M0.4 |
| ~~**Connection-pool sizing is not reasoned about across instances.**~~ - **closed 2026-09-04** by `P1-TSK-004`. The relationship `instances x pool <= max_connections - reserved` is declared as configuration and enforced by `ConnectionPoolSizingGuard` at startup, with the shipped numbers additionally checked in the build. **The obvious repair - divide `max_connections` by the instance count - is the wrong one**: that treats the limit as a budget to spend when it is a ceiling not to hit, and PostgreSQL throughput stops improving once the cores are busy, after which extra connections queue *inside* the database where the queueing is invisible. The pool is sized small for throughput and the fleet check is a separate question asked afterwards. `DISTRIBUTED_EXECUTION.md` §4a. | - | - | - | - |
| ~~**`@ArchTest` rules do not run in the `architectureTest` tier.**~~ - **closed 2026-09-08** by `P1-TSK-025`, and the defect was worse than this row described: the rules were not missing from the tier, they were **in the wrong one**. `unitTest` selects by *exclusion*, so it took all **28** untagged rule fields; `architectureTest` selects by *inclusion* and got none - and `ModuleBoundaryRulesTest`, which has no `@Test` method at all, produced **no result file** there: not a suite that ran zero cases, a suite that did not appear. **Root cause established by disassembling the engine**: `AbstractArchUnitTestDescriptor.findTagsOn` loads exactly one annotation, `com.tngtech.archunit.junit.ArchTag`, and cannot see JUnit's `@Tag`. Fixed with `@ArchTag` beside `@Tag` on all seven suites. **No existing guard could see it because the partition check asserts a SUM, and the sum was right** - every rule was in exactly one tier. | - | - | - | - |
| **No per-source rate limiting.** Lockout bounds *guessing* per identity; nothing bounds the *volume* one source can generate | **Building it now would be harmful, not merely premature.** `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to N replicas behind a load balancer, so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in seconds, and authentication goes down for everyone. `X-Forwarded-For` is caller-supplied and ADR-0034 settled that such values are not trusted; no trusted-proxy configuration exists. The missing input is a deployment topology, not effort (`P1-TSK-011`) | **Resource exhaustion, and it is the platform's most expensive unauthenticated operation**: ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by design*, so the work factor protecting a stolen credential store is the one an attacker spends for free. Ten concurrent attempts is ~190 MiB on one instance. `INV-IDN-07` still holds - every response is identical, so flooding discloses nothing - and lockout now bounds what an attacker learns, though not what they cost. Bounded today only by the fact that nothing is deployed | A deployment topology and a trusted-proxy declaration | Phase 15 |
| **`POST /v1/registrations` is unauthenticated and unthrottled.** Anyone who can reach the port can create Parties, Customers and Identities without limit | There is no rate-limiting mechanism anywhere on the platform. `P1-TSK-011` builds one for **authentication** - failure counting and lockout keyed on an identity - and none of that applies to an endpoint whose whole point is that no identity exists yet. Building a second, differently-shaped mechanism here before that one exists would be designing the general case from one example | **Resource exhaustion, not disclosure - and `P1-TSK-026` made it materially worse, which is recorded rather than left for somebody to notice.** Every response is still identical whatever is sent, so flooding discloses nothing (`INV-IDN-07` holds). What changed is the cost: a required password means **every** request now performs an Argon2id derivation, ~46 ms of CPU and ~19 MiB, *before* anything can refuse it (ADR-0032) - so this endpoint has become the same CPU-and-memory amplifier `POST /v1/authentications` already is, and unlike that one it needs no existing account. It also still fills three tables and the outbox, and the idempotency key does not help since a flooder generates a fresh one. Bounded today only by the fact that nothing is deployed | **Re-owned by `P1-DOC-002` (2026-09-09)**: `P1-TSK-011`'s mechanism is keyed on an identity, and an unauthenticated endpoint has none - the only usable key is the source, so this row's missing input is per-source rate limiting's missing input, a deployment topology and a trusted-proxy declaration. Merged with that row's trigger | Phase 15 |
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
| 7 | Whether `checkout` is its own module or part of `merchant` | Phase 6 | Low — **working position recorded** (own module, §3 M2) with a named merge trigger |
| 8 | Fee model: who pays, when recognised, gross vs net settlement | Phase 6 | High — changing revenue recognition after postings exist is a restatement |
| 11 | Fail-safe policy for risk evaluation: block or allow on unavailability | Phase 13 | High — a wrong default is either an outage or an open door |

Resolved since:
- ~~6. Accounting treatment of authorization (memo/hold) vs capture (posting)~~ →
  [ADR-0048](../adr/ADR-0048-authorization-is-not-a-posting.md) (Phase 4 → 5 transition,
  2026-09-20). Authorization is a payment-domain fact with **no ledger effect** — the
  issuer holds the customer's external funds, so neither a memo posting (entries for
  never-money) nor a wallet hold (the wrong subject) states anything true; **the ledger's
  first touch is capture** (DR `PSP_CLEARING` / CR wallet), and the refund is the mirror
  that *does* hold, because there the funds at risk are wallet funds
- ~~9. Which payment rail to simulate first, and its finality semantics~~ →
  [ADR-0049](../adr/ADR-0049-first-provider-simulated-card-psp.md) (same transition). A
  simulated card-style PSP — the maximal exercise of the lifecycle distinctions, so the
  port cannot ship too thin — with nothing final before settlement; `INV-REV-03` stays
  subjectless until the second rail (Phase 7)
- ~~10. Which jurisdiction-neutral compliance abstractions belong in the MVP~~ —
  **answered by the Phase 1 → 2 transition's plan and Phase 2's delivery** (KYC/KYB cases,
  screening with human-resolved hits, consent as an append-only history —
  jurisdiction-neutral behind provider adapters and versioned policy, ADR-0035…0038), and
  found still sitting in the open table three phases later by the Phase 4 → 5 transition
  — the stale-second-copy class in this table, again (questions 1–4 sat the same way for
  a phase). Ruled resolved with this provenance rather than silently deleted
- ~~5. Transfer/ledger transaction boundary and compensation strategy~~ &rarr;
  [ADR-0043](../adr/ADR-0043-transfer-and-posting-commit-together.md) (Phase 3 → 4
  transition, 2026-09-17). One local transaction; a failed transfer is a committed domain
  outcome; compensation is the business reversal and nothing else; **no internal saga** —
  and the boundary at which that answer changes is named (Phase 5's payment lifecycle).
  The question's own "determines whether a saga is ever needed internally" is answered: no,
  not while ADR-0001 holds
- ~~1–4. Posting isolation and locking · chart structure · projection placement ·
  `accounts`/`wallet` as one module~~ &rarr; ADR-0039, ADR-0040, ADR-0041, ADR-0042 (Phase
  2 → 3 transition, 2026-09-13; `Accepted` at `P3-DOC-001`). *These four rows sat in the
  open table for a full phase after their ADRs were taken — found by the Phase 3 → 4
  transition while moving question 5, the stale-second-copy class in this document's own
  §Unresolved table, corrected here*
- ~~Data-access mechanism: JPA/Hibernate, Spring Data JDBC, or plain JDBC?~~ &rarr; [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) (`P1-TSK-001`, 2026-09-04). Explicit SQL through `JdbcClient`; no ORM. Open since `P0-TSK-011`, scheduled for Phase 3, brought forward because Phase 1 creates nine tables

Resolved during initiation:
- ~~Which modules form the initial modular-monolith cut?~~ → [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- ~~Deployment topology?~~ → ADR-0001
- ~~Money representation?~~ → ADR-0003
- ~~Idempotency mechanism?~~ → ADR-0004
- ~~Reliable event publication?~~ → ADR-0005
- ~~Is balance authoritative or derived?~~ → ADR-0009

---

## Next Task

**`P5-TSK-001` — the `payments` and `paymentmethods` modules and schemas.**
Phase 5's first task, first for the standing reason — the privilege floor is
what every later grant claim rests on — and for the phase-specific one: the
build-graph decisions (`payments → ledger` declared so postings are commanded
and never written; `payments → paymentmethods` **refused** so the PCI
boundary is a module nothing payment-facing can see) are the structure that
keeps the phase's named risks unreachable before any payment code exists.
Scope, acceptance and DoD profile in the backlog entry; the module registers
in `MODULE_ARCHITECTURE.md` §3 already carry both modules' nine attributes.

**What Phase 5 inherits, already scheduled**: the per-credential confinement
generalisation (`P5-TSK-002` — the debt row that fired at `P2-TSK-011`), the
`P3-TSK-015` hold-then-capture composition (the refund's mechanism,
`P5-TSK-015`), and the provider harness built in `P0-TSK-037` meeting the
caller it was built for (`P5-TSK-003`).

### Superseded: the Phase 4 → 5 transition

*(This section described the transition until it was conducted on 2026-09-20.
Its stated inheritance — "nothing owed … one deviation (no fleet-wide
database or kafka count), which the transition may choose to close by running
one" — was exercised: the transition ran the full battery, which failed for a
test-harness reason, was repaired, and is green fleet-wide for the first time
in the phase.)*

### Superseded: P4-DOC-001

*(This section named `P4-DOC-001` until its gate on 2026-09-19 — the review
that flipped the phase. Its two findings were the missing
`transfers.beneficiary` register row and `P4-TSK-008`'s thin backlog
evidence, both corrected in the review rather than waived.)*


### Superseded: P4-TST-002

*(This section named `P4-TST-002` until its gate on 2026-09-19. Its
audit found the set is five and four rows were owed — and, the finding
that changed what the item was, that the transfers caller had no
concurrent-duplicate test while `INV-IDEM-01`'s Verify line and the
phase's criterion 2 both name one; performed rather than recorded.)*

### Superseded: P4-TST-001

*(This section named `P4-TST-001` until its gate on 2026-09-19. Its
text is kept below, because the gate's finding changed what the item
turned out to be: the "recorded from `P4-TSK-005`'s sweep" conditional
resolved to **record** as written — that sweep did perform the moved
form — and the item then performed it again against the sustained
composition, where it **survived** until the storm's amounts were
sharpened.)*

The phase's composition demonstration (the `P3-TST-001` posture): ten
instances transferring A→B and B→A continuously — mixed amounts, some
designed to lose — while the projection verification and trial-balance
sweeps run; ended by the sweeps' floors, never by time. Plus the
register row for `INV-CON-02` with its named mutation (the availability
check moved outside the lock — recorded from `P4-TSK-005`'s sweep,
which **performed exactly the moved form** and caught it by the drain's
over-acceptance; dropped and moved are different defects, the
`P3-TST-002` lesson). Accept: every mid-storm trial-balance sweep reads
zero per currency; every verification verdict `CLEAN`/`IN_FLIGHT`,
never `DRIFTING`; the final sum over both accounts equals the starting
sum **exactly**, counted from the tables and independently recomputed;
no source ever negative. Risk: Medium, Cx: M per the backlog. DoD:
`DOD-TEST`, `DOD-FIN`.

### Superseded: P4-TSK-011

*(This section named `P4-TSK-011` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-010

*(This section named `P4-TSK-010` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-009

*(This section named `P4-TSK-009` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-008

*(This section named `P4-TSK-008` until its gate on 2026-09-18.)*

### Superseded: P4-TSK-007

*(This section named `P4-TSK-007` until its gate on 2026-09-18.)*

### Superseded: P4-TSK-006

*(This section named `P4-TSK-006` until its gate on 2026-09-18. Its
"depends on `P4-TSK-004`" was a drift against the backlog's own
`Deps: P4-TSK-001` — noted on replacement rather than left.)*

### Superseded: P4-TSK-005

*(This section named `P4-TSK-005` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-004

*(This section named `P4-TSK-004` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-003

*(This section named `P4-TSK-003` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-002

*(This section named `P4-TSK-002` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-001

*(This section named `P4-TSK-001` until its gate on 2026-09-17.)*

### Superseded: the transition itself

*(This section named the Phase 3 → 4 transition until it was conducted on
2026-09-17, and `P3-TSK-011` — eleven tasks stale — before that, until
`P3-DOC-001` replaced it.)*

### Superseded earlier: the Phase 2 → 3 transition

*(This section named the Phase 2 → 3 transition until it was conducted on 2026-09-13.)*

**Phase 3 is where money arrives.** The financial supplement F1–F8 binds for the first time;
`INV-LED-*`, `INV-BAL-*` and `INV-CON-01` become live; and mistakes become permanent, because
`INV-HIST-01` forbids editing financial history — a posting written wrongly is corrected by a
compensating entry and the wrong one stays visible for ever. The cost of a design error here is
not rework; it is a permanent record of the error. Not a backlog task: a transition is its own act,
performed under the constraint that **no application code is written**, and it is what makes
Phase 3 `READY` rather than merely planned.

It must produce what Phase 0 → 1 and Phase 1 → 2 produced: a completion audit of the
closed phase, a distributed-systems and security audit, `PHASE_3_PLAN.md`, the backlog
elaborated to task granularity with acceptance criteria, and — the part that cannot be
deferred into implementation — the **three High-risk decisions at the top of §Unresolved
Architectural Questions**: the isolation level and locking strategy for concurrent postings
(`INV-CON-01`), the chart-of-accounts structure and its relationship to the Phase 14 GL
(`INV-ACC-04`), and balance-projection placement (ADR-0009). Each is irreversible once postings
exist, which is the whole reason a transition takes them before the first one is written.

*(A second copy of the "Phase 3 is where money arrives" paragraph sat here until
`P3-DOC-001` — a duplication inside the already-superseded block, removed by the review
rather than left.)*


## Change Log

The dated change log is archived verbatim in
[`history/CHANGE_LOG.md`](history/CHANGE_LOG.md).

**Append new entries there**, newest first, in the established format. It is not loaded into
context by default, which is why it can stay as detailed as it has been.
