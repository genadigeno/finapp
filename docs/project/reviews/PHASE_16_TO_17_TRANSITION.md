# The Phase 16 → 17 Transition — the Flip and the Operator Console's Initialisation

**Conducted:** 2026-11-17, on branch `P17-INIT` from master `5227f57e` (the X-TSK run merged)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §1 (the status model: `IN_REVIEW` → complete is the owner's decision),
§2 (the entry gate); [`EXECUTION_PROTOCOL.md`](../EXECUTION_PROTOCOL.md); the owner's instruction of 2026-11-17, "do
milestone-by-milestone" over finapp-admin-panel `docs/FINAPP_BACKEND_WORK.md`, and the owner's answer the same day, **"Flip 16,
open 17"**
**Precedent:** [`PHASE_15_TO_16_TRANSITION.md`](PHASE_15_TO_16_TRANSITION.md) (Part A, the Phase 15 gate) and `P16-INIT` (Part B)
**Rests on:** the Phase 16 exit review [`PHASE_16_REVIEW.md`](PHASE_16_REVIEW.md) (`P16-DOC-001`) and the owner's acceptances
A-G of 2026-11-16; finapp-admin-panel `docs/PHASE_17_PLAN.md` (`P17-DOC-001` there) and `docs/FINAPP_BACKEND_WORK.md` (the
hand-off, M0…M11, read against master `b23e3212`)

| | Outcome |
|---|---|
| **Part A — the flip** | **Phase 16 complete**, by the owner's explicit decision on the exit review's verdict ("complete with recorded deviations, the flip the owner's"). No second review: the exit review ruled every criterion, and the owner's acceptances A-G stand. The guards that arm at the flip were run on this branch's final bytes, every one green (*Tests*) |
| **Part B — the initialisation** | **Phase 17 — Operator Console — `READY`**, initialised as documents only. 44 backlog items in M17.1…M17.11, two of them built ahead of the phase under the owner's D-12 (`P17-TSK-003` as the cross-cutting `X-TSK-019`, `P17-TSK-004`'s finapp half as `X-TSK-020`); `P17-TSK-101` `READY`, every other item `PLANNED` |
| M0 of the hand-off | **B0.1** done here (the console's decisions cited as finapp-admin-panel ADR-C130…C136); **B0.2** done here (the records §17.2 of the plan names for the transition) |
| The postponed issues | PI-004, PI-023, PI-024 and PI-025 were postponed "until after the project's phases are finished". The flip delivers their reminder (below); the owner appended Phase 17 in the same decision, so they stay postponed, now until after Phase 17 |
| Findings | **CRITICAL 0. IMPORTANT 0. MINOR 4**, each recorded: (1) the browser-cache threat is held out of the register until its control exists - `ThreatModelIsCompleteTest` refuses a new HIGH threat without built evidence since Phase 15's exit - and `P17-TSK-101` registers it (`THREAT_MODEL.md` §6 item 73 (b)); (2) `ThreatModelIsCompleteTest` taught, deliberately, an eleventh boundary (B11, browser → operator edge) and that a `PLANNED` row may cite a Phase 17 task; (3) the hand-off's "unmerged branches" for `X-TSK-019`/`-020` are superseded, both merged; (4) the proposer's own approval answers 409 in five modules and 403 in six (`ERROR_CONTRACT.md` §5), recorded as optional debt with no task |

---

## Part A — the flip

**The decision.** `PHASE_GATES.md` §1 makes `IN_REVIEW` → complete the owner's act. The exit review recorded its verdict on
2026-11-16 - *Phase 16 complete with recorded deviations, the flip the owner's* - and rehearsed the flip twice, restoring the
bytes each time (*The flip* in the review). On 2026-11-17 the owner answered "Flip 16, open 17". `CURRENT_STATE.md`'s Phase 16
status line now reads `COMPLETE` in the form `ThreatModelIsCompleteTest`'s `COMPLETED_STATUS` matches, dated 2026-11-17, with
the line it replaced kept as a read-note.

**What the flip arms.** No other test reads a phase's status (the review's finding, re-read here): `MutationDemonstrationTest`
derives the highest completed phase, now 16, and demands a demonstration for every invariant of every phase reached;
`ThreatModelIsCompleteTest`, `ProductionReadinessIsEvidencedTest` and `PlannedMetersExistTest` read the state they always read;
`ResilienceExperimentsAreRegisteredTest` reads the register's held entries. Run on this branch's final bytes inside app's whole architecture tier, each green: `MutationDemonstrationTest` 9 (now demanding Phase 16's invariants as well), `ThreatModelIsCompleteTest` 14, `ProductionReadinessIsEvidencedTest` 8, `ResilienceExperimentsAreRegisteredTest` 14, `AdrRegistersAreReconciledTest` 4; and `PlannedMetersExistTest` 13 by name (it is a slice test).

**The deviations, unchanged.** The flip changes no ruling of the review. Criteria 1-5 and fifteen of the seventeen per-area
blocks stay `PARTIAL` (PI-025), universal criterion 7 `NOT MET` (PI-023), the ten-instance answer `UNKNOWN` at ten and `PASS` at
three, D01-D06 designed-only, E30's standing `FAILED` held by `P16-REM-004` - all accepted by the owner on 2026-11-16.

**The reminder** (the review's *What happens next*, delivered). Postponed until after the project's phases, and still so, since
Phase 17 now follows:

- **PI-025** - every Phase 16 cluster run: the ten-instance runs, the soak, the failover, the restores and the rebuild, the
  fourteen runnable scenarios, the exit pass's quick forms, the cluster probes.
- **PI-023** - the database pass: every module's `databaseTest`, own-container and `kafkaTest` tier on the final tree.
- **PI-024** - the platform rehearsals not run whole.
- **PI-004** - the storms of Phases 14 and 15.
- **PI-026 and PI-027** - the first real environment's decisions, and two domain changes placed after the phases.

If the owner wants any of them before Phase 17's work, it is the owner's to schedule; nothing in Phase 17 depends on them,
except that every `kind` acceptance of Phase 17 (`P17-TSK-104`, `P17-TST-001`) joins the same queue of owed cluster runs.

---

## Part B — the initialisation

### What Phase 17 is, in this repository

The Operator Console is a separate repository, `finapp-admin-panel`, which keeps the console's plan, its backlog, its
decisions (ADR-C130…C136, `Proposed` there) and `DOD-UI`, by the owner's instruction of 2026-11-13. This repository holds the
**backend half**: the gaps each console module needs, closed in the module that owns the data. The phase's numbering is
shared - `P17-TSK-001`…`-044`, `P17-TST-001` and `P17-DOC-001`/`-002` mean the same task in both repositories - and the work
the hand-off names that the console's plan never numbered (the operator surface's foundation, the seeding, the small view
additions) takes a **reserved block from `P17-TSK-101`**, so neither repository can collide with the other. The
`X-TSK-019`/`-020` pair, numbered twice on two branches and renumbered on 2026-11-16, is the reason.

### The plan read against the code (master `5227f57e`)

The hand-off was read against `b23e3212`; master has moved by the Phase 16 exit review, `P16-REM-005` and the X-TSK run. Its
claims that a task starts from were re-read here:

| Claim (hand-off) | On master `5227f57e` |
|---|---|
| B1.1: no `Cache-Control` set in `app/src/main` or `deploy/` | Holds for the API: none in `app/src/main`; the only `deploy/` mention is the operator edge's console headers, which refuse one |
| B1.3: the operator edge has neither `rateLimit` nor `inFlightReq` | Holds: 0 in `operator-edge.yaml`, 9 in `public-edge.yaml` |
| B1.4: the console overlay's digest is sixty-four zeros | Holds: `deploy/console/overlays/kind/kustomization.yaml` |
| B1.2: many operator acts accept a password-only session | Holds in kind: `@RequiresAssurance` appears 41 times in main code, `@RequiresPermission` 330 times (reads and acts together); the acts without the factor are `P17-TSK-102`'s guard to derive, as the hand-off says |
| B3.1: `RoleAssignmentStore.activeHoldingsOf` exists, unexposed | Holds |
| B4.3: `ScreeningListStore.find` exists | Holds (`risk`) |
| B0.1: seven citations of ADR-0130/0131 meaning a console decision | Holds, and re-pointed here (below); four X-TSK-020 notes the hand-off listed cite no number and needed nothing |
| X-TSK-019/-020 ("`P17-TSK-003`/`-004`") on unmerged branches | Superseded: both merged into master on 2026-11-16 with their renumbered neighbours (`X-TSK-022`, `-023`) |

Nothing read here changes a task's scope; the corrections above are in the backlog entries.

### The records written (B0.2, the plan's §17.2)

| Document | Change |
|---|---|
| `CURRENT_STATE.md` | Phase 16 flipped; Phase 17's block (`READY`, the milestones, the owner's open questions); `P17-TSK-101` the current task; the Next Task paragraph |
| `BACKLOG.md` | §Phase 17: 44 items, M17.1…M17.11, each with its context, scope, dependencies, acceptance, DoD profiles and multi-instance note; the D-12 pair recorded `COMPLETE`; B0.1's citation in `X-TSK-020`'s entry re-pointed |
| `PHASE_GATES.md` | §5 Phase 17: the entry gate and the plan's ten criteria made measurable - what runs here, what runs on `kind` (owed to the owner's cluster run), what finapp-admin-panel evidences |
| `THREAT_MODEL.md` | The browser ↔ operator edge boundary and the console's threats (plan §20), rated by the model's rule; the compromised workstation put to the owner |
| `DEFINITION_OF_DONE.md`, `TESTING.md` | `DOD-UI`, pointing to finapp-admin-panel's profile; the console's tiers and the E2E-on-`kind` requirement |
| `SYSTEM_ARCHITECTURE.md`, `MODULE_ARCHITECTURE.md`, `ERROR_CONTRACT.md` | The console as a client of the operator surface; `finapp-console` a non-module deployable; the 403/409 self-approval divergence |
| `ROADMAP.md`, `CAPABILITY_MAP.md`, `DELIVERY_PLAN.md`, `DECISIONS.md`, `docs/adr/README.md` | Phase 17's row, dependency (`16 ──> 17`), non-goals, capabilities (planned), delivery section and decision entry; the console's register named ADR-C130…C136, none reserved here, the next free number ADR-0152 |

The plan's other §17.2 rows - `API_CONVENTIONS.md`, `DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md`,
`PRIVILEGED_ACCESS_REVIEW.md`, `OPERATIONS_RUNBOOK.md`, the edge and its allow-list - change with the task that changes the
behaviour they describe, and each task's entry names them. Nothing describes behaviour that does not exist.

### B0.1 — the console's decisions cited by their own numbers

`app/src/test/java/com/finapp/app/api/EdgeRoutes.java`, `ConsoleHostingManifestsTest.java`,
`deploy/console/base/kustomization.yaml`, `deploy/edge/base/operator-edge.yaml`, `infra/scripts/manifest-policy/edge.rego`,
ADR-0120's dated note (with a read-note) and `X-TSK-020`'s backlog entry now read "finapp-admin-panel ADR-C130/C131". The
citations that mean this register's own numbers are left as they are - ADR-0117's "no ADR-0131 was opened", ADR-0132 as an
unused number, "the next free number is ADR-0130" in the Phase 16 initialisation's records, and the exit review's own account
of the numbering, which is a record of its date. `git grep -nE "ADR-013[0-2]\b"` finds no citation of a console decision.

### Owner decisions left open (none blocks `P17-TSK-101`)

| Decision | Where | Needed by |
|---|---|---|
| The segregation-of-duties conflict pairs | `P17-TSK-016` | M17.3 |
| Whether `ADMINISTRATOR` holds `AUDIT_READ` (ADR-C134 decision 2; recommended not) | `P17-TSK-017` | M17.3 |
| Whether an investigator may see that a case's regulatory report exists (tipping-off) | `P17-TSK-109` | M17.4 |
| M6's PII fields, document view and request-info (B6.6) | M17.6 | M17.6 |
| The identity that signs the console in a real environment | `P17-TSK-105` | M17.1 (designed-only) |
| The compromised operator workstation (not mitigated by the console; T53) | `THREAT_MODEL.md` §6 item 73 (a); recommended: accept until a workforce identity provider exists | M17.11's exit |
| The browser-cache threat held out of the register until its control exists | `THREAT_MODEL.md` §6 item 73 (b); recommended: `P17-TSK-101` registers it with its evidence | `P17-TSK-101`'s gate |

---

## Tests

By the owner's rule, no database, Kafka, own-container or cluster run; named guard classes only, from a fresh run.

- **App's whole architecture tier**, fresh with `--rerun` on the transition's final bytes (2026-11-17, 18:28:29-18:56:18 UTC, every JUnit XML in that window): **1,265 tests in 118 classes, 0 failed** - among them every guard reading a document changed here (the threat model's, the mutation register's, the readiness review's, the experiment register's, the ADR registers', the console hosting and edge manifests', the distributed-system audit's).
- **By name, the slice guards touched**: `PlannedMetersExistTest` 13 and `EdgeAllowListTest` 22 (it reads `EdgeRoutes`, whose comment B0.1 re-pointed), fresh, 0 failed.
- **Earlier on the same day, while the records were written**: the backlog's seven guards (103 tests, 1 failed - the threat rows' Phase 17 citations, fixed by the guard's edit) and the 44 guard classes reading the gate and threat documents (461 tests, 1 failed, the same cause), each re-run green after the fix.

**Not run, owed**: every database, own-container, Kafka and cluster tier (PI-023, PI-025); the edge policy's `conftest` run (`edge-check.sh`) - its one change here is a comment.

## What happens next

`P17-TSK-101` is `READY`. The owner's run is milestone by milestone: M17.1 in order (`-101`, `-102`, `-103`, `-104`, `-105`),
then M17.2 and onward, each task through design, implementation and its completion gate, each on its own branch, each task's
own database classes run under the owner's allowance of 2026-11-17. Every `kind` acceptance is built and held by contract
tests, and its run owed to the owner.
