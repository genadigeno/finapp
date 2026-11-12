# Phase 15 Exit Review — Production Hardening

**Conducted:** 2026-11-12 (`P15-DOC-001`, branch `P15-DOC-001` from master `11ee8da3`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (the twelve universal exit criteria; Phase 15 is not a
financial phase, so the supplement F1–F8 does not apply), §5 Phase 15 (the seven criteria made measurable and the per-area blocks,
read from the gate at review time)
**Phase objective under review:** the modular monolith made deployable, operable and defensible as N instances without
changing what it computes - two images, Kubernetes manifests and a segmented edge, infrastructure as code verified without an
apply, a signed and digest-promoted delivery, secrets and key rings, telemetry, SLOs and routed alerts, platform runbooks and
their rehearsal, the database and messaging operations, the configuration register, a threat model and a readiness review -
inside this repository and never in a real environment (`PHASE_15_PLAN.md` §1.1).

| | Outcome |
|---|---|
| Items | **46**: 43 tasks `COMPLETE`; `P15-TST-001` (the storm, PI-004) and `P15-TST-002` (the database pass, PI-023) `POSTPONED` by the owner; this review |
| The seven criteria | **1 `PASS`** (its measurable form; 32 of 38 CRITICAL and HIGH threats carry residual gaps, each held by a named owner); **3 `PASS`**; **6 `PASS` in another form** (two clauses shown over empty data, one held by owner item 38 (c), one by new item 44 (a)); **2, 5, 7 `PARTIAL`** (three unaccepted scan baseline rows; an audit-record clause no guard holds; a trace without its provider and ledger legs); **4 `PARTIAL` by the owner's choice** (PI-024) |
| The twelve per-area blocks | **7 `PASS`** (containerisation, Kubernetes deployment, infrastructure as code, secrets and keys, observability, operational readiness, and documentation and runbooks after this review's corrections, its criterion 4 half aside; several in another form) - **5 `PARTIAL`** (CI/CD and configuration: the second person, owner item 4; security hardening, database and messaging operations, multi-instance deployment: PI-023, PI-004, PI-024) - see *The per-area blocks* |
| The database pass (the gate's exit item) | **`NOT MET` by the owner's choice** (PI-023): no `database`, own-container or `kafka` tier has run fresh since Phase 15 began |
| Universal criteria (12) | **9 `PASS`** (criterion 1 with the two `TST` items postponed); **criteria 4 and 11 `PARTIAL`; criterion 7 `NOT MET` by the owner's choice (PI-023)** |
| *"Correct with 10 concurrent instances?"* | **`UNKNOWN` at ten, by the owner's choice; no counted run failed.** `PASS` on every counted rehearsal - three replicas on `kind` through a rolling update, a pod kill, a rollback and the census after each step (2026-11-09); the rotations at three replicas (2026-11-11); the migrator at ten runs at once (2026-11-08). Open: the ten-way database cases of the phase's new arbiters (PI-023), the storm's counts (PI-004), the final-tree release run (PI-024) |
| ADR-0120…0125 | **`Accepted`**, each read decision by decision against the tree and corrected in place first (eleven corrections) |
| ADR-0006's coupling review | **The dependency graph is the declared one**; four rules weaker than the ADR says - `P15-REM-001` |
| C1–C15 | 12 as ratified (C3 and C6 with recorded detail); C9 and C13 honoured in another form; C14 changed by the owner (PI-023, 2026-11-12) |
| Compliance wording | **None claims compliance, certification or attestation under a standard** (A6 read every match: 778 lines) |
| The flip, rehearsed | **1 red of 68 on master's bytes** (`MutationDemonstrationTest`: no section 4 rows for `P15-TST-001`, `-002`); **0 of 68 after this review's rows** - see *The flip* |
| **Verdict** | **Phase 15 `COMPLETE` with recorded deviations, pending the Phase 15 → 16 transition gate and the owner's acceptance of the deviations below**: criteria 2, 5 and 7 `PARTIAL` with remediation entries (`P15-REM-002`, `-003`) and one owner decision (item 12's three rows); criterion 4, the database pass, universal criterion 7 and the ten-instance answer resting on the owner's postponements (PI-004, PI-023, PI-024); the second person (owner item 4); and one guard red on master's bytes, `TestTaxonomyTest` (`P15-REM-005`, before the transition gate's guard run). The status line is the owner's to flip at the transition |

Conducted in the `P14-DOC-001` order, without its first step: **no database pass** (PI-023) and no cluster or container run, by
the owner's rules ("avoid long running tests", "we dont need too many checks"). **The assessment was six independent read-only
audits** - A1 the seven criteria, A2 the per-area blocks from containerisation to secrets, A3 observability, database and
messaging operations, multi-instance, configuration and the database pass, A4 ADR-0120…0125 decision by decision and ADR-0006's
coupling, A5 the registers and the readiness review, A6 the documentation block (the stale documents, compliance wording,
C1–C15, the plan's errata). Each finding was proven from a file and a line and re-read before it was acted on; the counts below
were read from the tree, and the flip and the guards were run fresh.

---

## What the review found, and what it did about it

**No CRITICAL defect was found.** Phase 15 moves no money. The review hunted in what the phase claims, not in every write path:
its changes to financial code (`-033`'s two fixes, `-038`'s five stores on the database's clock, `-040`'s `TRUNCATE` refusals,
`-035`'s census) were gated by their tasks, and their database cases are owed with the pass (PI-023).

**Fixed here** (documents only; no production code, migration or manifest):

1. **The flip's one red** - `MUTATION_TESTING.md` §4 had no rows for `P15-TST-001` and `-002`. Two deferral rows written in
   `P14-TST-001`'s convention: no demonstration, the probe owed with the storm and the pass.
2. **A threat-model paragraph contradicting the register** (A5) - §5 said "no built control exists" for T21, T27, T28 and T45
   and "T45's rule is unwritten", while §4 holds fifteen `EXISTS` rows for them. Rewritten with a dated note; the
   `createStatement()` count beside it corrected (about fifteen → twenty, counted).
3. **Three scan baseline rows nobody accepted** (A1) - `infra/semgrep/baseline.tsv` holds 61 rows; owner item 12 records 59
   accepted. Wave F's run replaced one row by three (the role bootstrap's alternating users) on 2026-11-12, and no decision
   recorded them. Item 12 now says so and recommends acceptance; its claim that `SecurityScansAreWiredTest` holds "the
   baseline's date and row count" is corrected (it holds expiry, existence and a ratio, not the count). The guard is
   `P15-REM-004` (b).
4. **Owner item 43 (a)-(h)** recorded as decided (the owner, 2026-11-12, "accept all"); each part's decision after its
   recommendation, the awaiting line kept as a read-note. The readiness cells it holds stay `GAP`.
5. **`DISTRIBUTED_EXECUTION.md`** (A5) - two *planned* rows (key rings, retention sweeps) stood beside their built rows; the
   drain's row said 35 schedules (37) and left out `TelemetryFlush`; the "why" paragraph said five rows remain planned. Struck,
   corrected and dated. No guard reads this register (`P15-REM-004` (a)).
6. **`PRODUCTION_READINESS.md`** (A5) - `finapp-app`'s Ownership cell cited item 43 "(c)" for the owner-label check, which is
   (d); corrected. Nine module Alerting cells (`kyc`, `consent`, `ledger`, `settlement`, `fx`, `crossborder`, `credit`,
   `lending`, `bnpl`) are `MET` with no SLO burn-rate rule, because none of their flows has a §11.1 objective: the reading is
   recorded in §4's preamble rather than the cells re-ruled.
7. **`CONFIGURATION_REGISTER.md`** (A3) - "two people on every change … is not built yet: `P15-TSK-017` delivers it", a finished
   task's promise: `CODEOWNERS` exists, names one person, and no branch rule requires it (owner item 4). Corrected twice.
8. **ADR-0120…0125** accepted with eleven inline corrections (below); **ADR-0006**'s Follow-up done (below); **ADR-0027** and
   **ADR-0089** given notes where they said "no Kafka client" and "every session actor is `CUSTOMER`".
9. **`PHASE_15_PLAN.md` §26, errata** - fifteen entries, among them §13's "`PASS` by design", the runbooks' rehearsal (PI-024)
   and the database pass (PI-023), neither of which the plan named.
10. **Words left behind** - `SYSTEM_ARCHITECTURE.md` §Deployment still read "planned … nothing of it is built"; the threat
    model's and the backlog's Phase 15 preambles called ADR-0120…0125 `Proposed`; `DECISIONS.md` said "nothing of it is built".
    Each dated. `POSTPONED_ISSUES.md` PI-023 names one test known to be red before the pass runs (`MerchantApiKeyDatabaseTest`
    still asserts `CUSTOMER` for an operator's act, typed `EMPLOYEE` since `P15-TSK-021`).

**Found red by this review's run, on master's bytes too** - **`TestTaxonomyTest`** fails on `ReportingPoolMetricsTest` ("needs
DATABASE but is in UNIT"): `P15-TSK-032`'s hermetic test of the reporting pool's meters registers its stub driver through
`java.sql.DriverManager`, which the taxonomy reads as acquiring a database. This review changed no Java and the guard reads
bytecode, so the red is `11ee8da3`'s; every merge since ran named guards without the taxonomy. A test change is not this
review's to build: **`P15-REM-005`**, before the transition gate's guard run.

**Recorded, not changed** - five remediation entries, `PLANNED` (`BACKLOG.md` §Phase 15 remediation): **`P15-REM-001`**
ADR-0006's rules made to hold what they say; **`P15-REM-002`** an audit record tested for every auditable route (criterion 5);
**`P15-REM-003`** the trace's provider and ledger legs and its causation (criterion 7); **`P15-REM-004`** seven guards and
comments that hold less than their names; **`P15-REM-005`** the taxonomy's red above. And **`THREAT_MODEL.md` §6 item 44**, two decisions for the owner: (a) the restore's
lending and BNPL proofs judged no subject; (b) the rendered manifests scanned by policies alone.

---

## The seven criteria

### 1. Threat model complete; critical and high risks mitigated or accepted — `PASS` (its measurable form)

| Clause | Ruling | Evidence |
|---|---|---|
| Every boundary of plan §9.1, STRIDE per boundary | `PASS` | `ThreatModelIsCompleteTest#theTenBoundariesAreModelled`, `#everyBoundaryAnswersAllSixStrideCategories`, `#theMatrixAndTheRegisterAgree`: ten boundaries, sixty cells, three `N/A` with reasons |
| Every threat rated and disposed | `PASS` | `#everyThreatIsRatedByTheWrittenRule`, `#everyThreatHasADisposition`, `#theSummaryCountsWhatTheRegisterHolds`: **47 threats** - 25 CRITICAL, 13 HIGH, 8 MEDIUM, 1 LOW; 45 `CONTROL`, 2 accepted by the owner (T42 until 2027-06-30, T47) |
| Every CRITICAL and HIGH threat has a control with existing evidence, or the owner's acceptance | `PASS` | §4: **433 rows** - 356 `EXISTS`, 68 `GAP`, 9 `REMEDIATION`, no `PLANNED`; **all 38** CRITICAL and HIGH threats have an `EXISTS` row whose evidence resolves; `#atThePhaseExitEveryCriticalOrHighThreatHasBuiltEvidence` and `#atThePhaseExitNoAcceptanceIsStillProposed` green **at the flip** (below) |
| The guard green, its planted probe caught | `PASS` | the guard fresh (*Tests*); `MUTATION_TESTING.md` §2's `ThreatModelIsCompleteTest` rows (a control naming absent evidence, a `GAP` row held by nothing, a `GAP` row without its owner, each caught) |
| "Mitigated" in the headline's sense | **recorded** | **32 of the 38** also carry residual `GAP` or `REMEDIATION` rows (69 of the register's 77), each held by a named owner - the table below. The guard reads "at least one `EXISTS` row", which is the criterion as made measurable and less than "mitigated": the residuals are held, not mitigated, and the owner's acceptance of them is one of the flip's (*Owner acceptances*) |

**The residuals of the CRITICAL and HIGH threats, by who holds them** (each threat's rating in brackets):

| Holder | Threats | Nature |
|---|---|---|
| **PI-023**, the database pass | T3 (C), T12 (C), T13 (H), T17 (C), T33 (C), T34 (C), T38 (H), T44 (H) | the database-tier proofs of built controls: written, never run in Phase 15 |
| **PI-024**, the platform rehearsals | T11 (H), T12 (C), T29 (C), T46 (C) | the cluster runs of built procedures |
| PI-013, PI-014, PI-016, PI-020, PI-021, PI-022 | T13 (H), T15 (C), T18 (C), T46 (C) | owner-postponed defects: PI-013's and PI-014's fixes landed with their regression cases unrun, the rest "before production use" |
| `P14-REM-001`, `-002`, `-004`, `-006`, `-007` | T15 (C), T18 (C) | Phase 14's remediation, `PLANNED` |
| `X-TSK-002`, `-003`, `-010` | T19 (C), T16 (C), T35 (H) | cross-cutting, `PLANNED` |
| Owner items 4, 7, 12, 18, 21-23, 28, 31, 33-39, 42 (each decided) | T1, T3-T7, T9-T11, T20, T22, T27-T30, T32, T34, T36, T38, T43, T45, T46 | real-environment items (an account, a registry identity, branch rules, a managed restore) and accepted baselines |

### 2. Dependency, container and secret scanning green in CI — `PARTIAL`

| Clause | Ruling | Evidence |
|---|---|---|
| SBOM scan, both images' scans, Terraform's configuration scan, static analysis, history secret scan | `PASS` | `.github/workflows/ci.yml` jobs `dependency-scan`, `image`, `migrator-image`, `iac`, `sast`, `secret-scan`; `SecurityScansAreWiredTest`, `CiWorkflowIsLockedDownTest`; each a pinned script under `infra/scripts/` |
| The manifests' configuration scan | `PASS` in another form | `kubeconform -strict` and the platform's own policies (`infra/scripts/manifest-policy/*.rego`), each refusing a plant; no graded scanner, so "zero HIGH" reads "zero policy violations" (ADR-0122 corrected; item 44 (b)) |
| Zero HIGH or CRITICAL, or each one accepted in the threat model with an expiry | **`PARTIAL`** | Trivy's ten carried ids equal item 12 (held by `#everyFindingCarriedIsInTheRegister`), until 2027-01-31; the Terraform finding accepted inline to 2027-06-30 (item 40). **Three Semgrep baseline rows are accepted by no decision** (above) |
| A planted vulnerable base image and a planted secret each fail their job | `PASS` in another form | `image-scan.sh`'s plants (a vulnerable base, a secret in a layer and in the environment); the history scan's own plant is the manual Phase 0 procedure (`SECRET_MANAGEMENT.md`), not re-run in Phase 15 |
| Green in CI on the final tree | **not observable** | no CI run is read here (the standing instruction); the records say CI's `rehearsal` and `admission` jobs have not yet run on CI's runner (`OPERATIONS_RUNBOOK.md` §11.28, §11.32) |

### 3. SLOs defined for every critical flow with alerting and linked runbooks — `PASS`

Every §11.1 flow has an SLI over existing series, a target, a fast pair paging (14.4 over 1 h / 5 min) and a slow pair
ticketing (6 over 6 h / 30 min): `infra/prometheus/rules/slo.yml`, `SloRulesResolveTest`, `AlertCatalogueTest#everyObjectiveHasBothPairs`
(eight flows, ten objectives). Every alert names an existing runbook section (`#everyAlertNamesARunbookSectionThatExistsAndNamesIt`);
the correctness signals page on their first sample (`correctness.yml`, `for: 0s`; `#theCorrectnessSignalsPageOnTheirFirstSample`,
`#theTrialBalancePagesOnItsFirstNonZeroSample`). The SLO and correctness alerts each have a promtool case firing and one silent
(A1 parsed every case). **Noted**: `promtool check` runs as `promtool check config` loading every rule file; the last run with
its exit code recorded is `P15-TSK-043`'s, and `P15-TSK-032` reports its later rules (`telemetry.yml`, the `CreditPolicyMissing`
fix, the reporting pool's exclusion) tested both ways; the CI `alerts` job is unobserved. The guard that a case exists does not
hold that it fires and stays silent (`P15-REM-004` (d)).

### 4. Every runbook rehearsed at least once with the result recorded — `PARTIAL` by the owner's choice (PI-024)

`RunbookRehearsalCountTest` holds the per-section count in `OPERATIONS_RUNBOOK.md` §11.38 (it reads that table, not each
section's own record). **46 rows: 22 rehearsed at least once, 24 not** - 22 of them under PI-024, §11.7 for want of an account,
§11.40 a Phase 16 drill. Deploy, promote and restore are rehearsed; migration failure on containers (two of four procedures);
rollback by digest on a controller, the broker outage, dead letters and replay are under PI-024; rotation in part (§11.29 three
of nine, §11.33 whole); incident response one of four. **Domain sections**: §1, §2, §3 and §3b carry a sample from early
`kind` runs; **§4-§10 have none**. Criterion 4 as written (every section, the platform section whole, a counted sample per domain
section) is not met, and the owner postponed what remains (2026-11-12, PI-024).

### 5. Audit trail verified complete against the auditable actions — `PARTIAL`

| Clause | Ruling | Evidence |
|---|---|---|
| `AuditCompletenessTest`, `AuditableActionRegistryTest` green | `PASS` | fresh (*Tests*); the first proves every registered action referenced by production code, not every path recorded |
| The three platform outbox actions emitted | `PASS` | `AuditedAbandonmentsTest`, `DeadLetterOperationsTest` (hermetic; their database twins PI-023) |
| An operator's act recorded as `OPERATOR` | `PASS` in another form | `EMPLOYEE`, the existing constant, decided per request (`OperatorActorTypingTest#anOperatorActIsTypedEmployee`; C13) |
| A refused step-up recorded | `PASS` | `OperatorActorTypingTest#aRefusedStepUpIsRecorded`, `StepUpRefusalsTest` |
| Every auditable route in the permission register has a test of its record | **`NOT MET`** | no guard holds it, and a name match found actions named only by database-tier tests and some by none: **`P15-REM-002`** |

### 6. Backup restore rehearsed with post-restore trial-balance verification — `PASS` in another form

`OPERATIONS_RUNBOOK.md` §11.35's `kind` record (2026-11-11, master `0f5921e9`): a backup taken while the fleet wrote, restored
into a fresh PostgreSQL; `flywayValidate` clean over 21 histories; 358 tables' counts equal; the trial balance zero (one
currency); the projection clean; the census 854 of 854; the planted `journal-line-removed` refused by the counts, the trial
balance, the projection and the census. Managed point-in-time recovery configured (`infra/terraform/modules/postgres`, its
`terraform test`). **Two clauses were shown over empty data**: GL = ledger "for every locked period" ran over none (owner item
38 (c), decided: at a real environment's first month end); four of the eight product proofs judged zero subjects, because the
source holds no loan and no BNPL obligation (**new item 44 (a)**). The run is not on the final tree (PI-024's release scope).

### 7. Distributed tracing for one real flow end to end — `PARTIAL`

On `kind` (2026-11-11, §11.34's trace record), one card payment, its settlement file and a reconciliation run were found in
Tempo by correlation, and no exported span or log line carried any of 104 needles. **But the step asserts nine spans** - the
payment's two requests, the outbox and inbox, the file's upload, attestation, parse and accept, a reconciliation chunk - and
**no provider span, no ledger posting span and no causation id** (`infra/scripts/kind-rehearsal/telemetry.sh`), and its chunk is
any chunk, not the allocating run's. The criterion's provider and ledger legs and the causation across the outbox are unproven:
**`P15-REM-003`**. PI-022 (an amount in an exception's message) stays open.

---

## The per-area blocks

| Block | Ruling | Clauses not plainly held |
|---|---|---|
| Containerisation | `PASS` | read-only root and no capabilities are runtime properties held by policy and the readiness run (another form); the size budget measures the flattened export; last measured 2026-11-09, the final-tree build under PI-024 |
| Kubernetes deployment | `PASS` (rehearsed on `18326010`, 2026-11-09) | every policy with its plant; three replicas ready after the Job; 878 requests, 0 failed through the rolling update; rollback by digest; a pod kill with its lease reclaimed; **peak 39 connections of a budget of 48**, an operator admitted at 49 of 60. `manifest-check.sh` has no recorded run after `P15-TSK-032`'s last `deploy/` changes, and the final-tree release run is PI-024's |
| Infrastructure as code | `PASS` | `fmt`, `validate`, `tflint`, `trivy config`, `terraform test` with mock providers over nine modules and two roots (twelve test files); no secret in any file, no state, no apply. The test-file floor is three (`P15-REM-004` (c)) |
| CI/CD | **`PARTIAL`** | **the production overlay "owned by two persons": `CODEOWNERS` names one, no branch rule applied - owner item 4 (decided 2026-11-08), not met by the owner's choice**; signing demonstrated with a key generated for the run (another form; the production identity item 35); the lint, the schema guard and the promotion's refusals `PASS` |
| Security hardening | **`PARTIAL`** | criterion 1; the management port unreachable from an edge pod on `kind`; no operator route on the public list; forged-but-signed callbacks moving nothing on both rails (hermetic); **the burst refused across three replicas is held by a scripted store alone, its counted case database-tier (PI-023)**; operators recorded `EMPLOYEE` (another form) |
| Secrets and key management | `PASS` | `SecretInventoryIsCompleteTest` both ways (54 rows); no committed `Secret`; rotation per class held, revocation per class written but not held (`P15-REM-004` (e)); a database password and an HMAC secret rotated on `kind` with no failed request; a row read under its stamped version after rotation, a retired version refused (at the ring's build, another form) |
| Observability | `PASS` | criteria 3 and 7 are the criteria's; `NoGaugeReadsOnTheScrapeTest` with its plants; `InstanceDown` per instance with its promtool case; `amtool check-config` with its bad-routing plant |
| Operational readiness | `PASS` | `PRODUCTION_READINESS.md` §3 and §4: **170 cells - 64 `MET as rehearsed`, 4 `PASS`, 83 `GAP`, 19 `N/A`** (recounted); none called ready. A5 resolved all 236 `MET`/`PASS` citations and sampled 21 cells for sense; every `GAP` names a live owner, none a finished task. Two corrections above; the telemetry multi-instance `PASS` rests on the 2026-11-11 rehearsal read through tests of its wiring, not of its count - recorded |
| Database and messaging operations | **`PARTIAL`** | the migrator idempotent and ten at once (2026-11-08); `SASL_SSL` with the guard; the topic catalogue both ways; retention under the dedupe window; the handler-set diff; the handler window on `kind` (166 of 331 unseen replayed once); parking, retry, discard and replay-window refusals hermetic (another form). **The sweeps never ran on PostgreSQL** (PI-023), the cluster procedures (PI-024) |
| Multi-instance deployment | **`PARTIAL`** | §13's rows: schedules, rolling deployments, shutdown, draining, restarts, capacity, handlers, migrations and rotation `PASS`; distributed state in another form (the rules test and the runs, not the register); **concurrent execution `NOT MET` by the owner's choice** (every counted contention case is database-tier, PI-023; PI-012); rebalances partial (`KafkaRebalanceKafkaTest`, PI-023). The census green after every step on `18326010`, not on the final tree (59 production Java files changed since; PI-024). The storm (PI-004) |
| Configuration management | **`PARTIAL`** | the register both ways (`ConfigurationRegisterIsCompleteTest`, 353 rows, 57 `FINANCIAL`); no Spring profile; every `FINANCIAL` row states its mixed-fleet safety (a statement held to exist, not to be right: `P15-REM-004` (f)); the fingerprint alert's `for: 80m` over the longest rollout (76.95 min), its promtool case silent at 70 and firing at 90 (another form; the cluster plant PI-024). **The second person on a `FINANCIAL` change: owner item 4** |
| Documentation and runbooks | `PASS` after this review's corrections | criterion 4 is the criterion's; ADR-0120…0125 accepted; §3's five stale documents corrected; C1–C15 recorded; no compliance claim |
| **The database pass (exit item)** | **`NOT MET` by the owner's choice** | PI-023 (2026-11-12): PI-012's full run, PI-013's and PI-014's regression cases after `-033`'s fixes, PI-014's unclassified suites, PI-015's struck rows |

**The gate's "twelve per-area blocks"** are thirteen bullets: the twelve areas of the owner's brief and the database pass, an
exit item. Each is ruled above.

---

## Universal exit criteria (§3)

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | `PASS` - the storm and the pass postponed | 43 tasks `COMPLETE`; `P15-TST-001` (PI-004), `P15-TST-002` (PI-023) |
| 2 | Architectural boundaries respected | `PASS` | the graph is the declared one; isolation tests; ADR-0006's weaker rules are `P15-REM-001` |
| 3 | Required invariants tested | `PASS` | no new invariant (none of the catalogue names Phase 15); `MutationDemonstrationTest` green armed for Phase 15 at the flip; PI-015's struck rows owed |
| 4 | Failure cases handled | **`PARTIAL`** | the kill, rollback, failed migration and restore each rehearsed with a plant; the broker outage, poison record and dead letters on a cluster are PI-024's |
| 5 | Security requirements implemented | `PASS` | criterion 1; route permissions and their negative tests; the secret inventory; classification checked by hand for Phase 15's columns, its guard database-tier (PI-023) |
| 6 | Observability exists | `PASS` | metrics, logs and traces with correlation propagated; the trace's missing legs are criterion 7's |
| 7 | Integration tests pass | **`NOT MET` by the owner's choice** | C14's standing deviation, its one pass postponed (PI-023): no database or Kafka tier ran in Phase 15 |
| 8 | Documentation reflects reality | `PASS` after corrections | *What the review found*; the plan's §26 |
| 9 | `CURRENT_STATE.md` updated | `PASS` | by this review |
| 10 | ADRs exist and are `Accepted` | `PASS` | ADR-0120…0125 `Accepted` after correction |
| 11 | No unresolved critical issues | **`PARTIAL`** | no CRITICAL found; owner-postponed defects stand, each "before production use": PI-016, PI-018, PI-019 (a transfer pre-empting an instant execution, a double credit), PI-020, PI-021, PI-022 |
| 12 | Formal phase review conducted | `PASS` | this document |

---

## The ten-instance answer

**`UNKNOWN` at ten, by the owner's choice; `PASS` at what was counted; no `FAIL`.** Every arbitration stays in PostgreSQL, and
Phase 15 added arbiters of its own - the window counter, the retention sweeps, the source census, the dead-letter doors - each
with a ten-way database case written and never run (PI-023). The counted runs: `P15-TSK-012`'s three replicas on `kind` (a
rolling update under a scripted workload, 0 failed requests; a forced pod kill whose claim another replica completed; a
rollback by digest; the census - trial balance zero per currency, one effect per idempotency key, the outbox drained, no
orphaned lease, GL = ledger - green after every step); `P15-TSK-020`'s five rotation runs at three replicas; the migrator's ten
runs at once, each migration applied once. **Open**: the storm's counts (PI-004), the ten-way database cases (PI-023), the
final-tree release run (PI-024). `PRODUCTION_READINESS.md` rules `finapp-app`'s multi-instance row `GAP` on the same facts, and
`PHASE_15_PLAN.md` §13's "`PASS` by design" gains an erratum. Ten instances at load are Phase 16's (item 43 (g)).

---

## ADR-0120 … ADR-0125: accepted

Each read decision by decision against the tree (A4), corrected in place with a dated note, then `Accepted` (the index, the
README's rule and `DECISIONS.md` agreeing; `AdrRegistersAreReconciledTest` fresh). The corrections: **ADR-0120** - 37 schedules
and the telemetry flush in the drain; "Redis is not deployed" true of the platform, Argo CD's own cache named; **ADR-0121** -
"provider-neutral" holds of the base and components, not the overlays; "the secrets manager generates credentials" - entries
are created empty and filled by a person, the managed database alone generating its own; **ADR-0122** - "build once": each job
builds the commit's images, one digest per commit resting on proven reproducibility; Trivy does not scan the rendered
manifests (item 44 (b)); the Follow-up's "what remains" was stale; **ADR-0123** - two line references moved; **ADR-0124** -
Grafana runs in compose alone; **ADR-0125** - the schema-version guard is a readiness verdict, not a startup refusal; the
second person built as `CODEOWNERS` naming one person (item 4). Every other decision holds, as written or as its earlier dated
notes amend it.

## ADR-0006's coupling review (routed by the owner, 2026-11-08)

**The observed coupling is the declared design.** From every module's `build.gradle.kts`: `platform` has 21 dependants,
`ledger` 11, `creditmath` 3; `app` depends on `platform` and all 21 business modules; the twelve business-to-business edges are
ten to `ledger` and `lending` and `bnpl` to `creditmath`, each declared (`MODULE_ARCHITECTURE.md`, `BOUNDED_CONTEXTS.md`) and
pinned by its isolation test; no cycle; the direction holds, with one declared skip (`creditmath → sharedkernel`). **No foreign
key crosses a schema**: every cross-schema `REFERENCES` in a migration is inside a comment. No business module's SQL names
another module's schema. **What the ADR says that the build does not hold** (recorded in its Follow-up; `P15-REM-001`): the
internals rule and the entity rule pass over nothing (no `.internal` package, no entity); `INV-LED-04`'s static half is the
dependency direction alone; `app` reads thirteen module schemas `SELECT`-only through declared adapters, an exception the ADR's
data boundary does not state, pinned by nothing; the no-cross-schema-key rule has only per-migration checks. And
`MODULE_ARCHITECTURE.md` declares a `notification` module the build never had.

## C1–C15

| # | Decision | Recorded |
|---|---|---|
| C1 | AWS as the one reference, never applied | Ratified 2026-11-07, as written |
| C2 | EKS for staging and production, `kind` for CI; Kustomize; Helm for pinned third parties | As written |
| C3 | State outside the cluster; Redis not deployed | As written for the platform; Argo CD's own Redis recorded (ADR-0120) |
| C4 | Multi-stage, digest-pinned, distroless `nonroot` | As written |
| C5 | One Deployment, every role per replica, N ≥ 3, the HPA bounded; a migration Job | As written (7 + 2 × 1 + 1 = 10 in production) |
| C6 | Store and KMS, External Secrets, rotation by rolling restart, key rings | As written; rotation an act the fleet follows, not a schedule (ADR-0121, ADR-0123 notes) |
| C7 | ECR by digest, Cosign keyless with provenance, admission on `kind`, Trivy and Semgrep | As C7 foresaw: demonstrated with a run's own key and registry; the production identity decided 2026-11-11 (item 35) |
| C8 | GitOps by digest, four environments, two persons for production | Built as declared Applications and `CODEOWNERS`; the second person the owner's (item 4) |
| C9 | Public and operator ingresses, management port off every Service | Honoured in another form: two edge controllers on file-provided routes rather than `Ingress` objects (ADR-0120's note) |
| C10 | Collector, Prometheus, Alertmanager, Tempo, Loki; burn-rate SLOs; correctness never budgeted | As written; a real environment's back ends decided 2026-11-11 (item 37 (a)) |
| C11 | Build-time event catalogue, `SASL_SSL` with SCRAM | As written |
| C12 | Operational sweeps built; evidence purges re-dated | As written, a third sweep (the window counter) beside |
| C13 | Operator sessions `OPERATOR`; refused step-ups audited; a reporting role; workforce OIDC deferred | Honoured in another form: `EMPLOYEE`, per request; the reporting role built, not cut; OIDC deferred (item 30 (e)) |
| C14 | Criterion 7's deviation; one database pass at the exit; the storm postponed | **Changed by the owner, 2026-11-12**: the pass postponed (PI-023); per-task testing narrowed to named classes (the owner's rule of 2026-11-08) |
| C15 | §25's dispositions | As written, applied by `P15-TSK-002` |

## `PHASE_15_PLAN.md` §3's stale documents

All five are corrected on this tree: `SECURITY_ARCHITECTURE.md` §Encryption at rest and `SECRET_MANAGEMENT.md` §7 (by
`P15-TSK-001`, each with its note); `TransportSecurityGuard`'s note names the Kafka hop's own guard; `RequiresAssurance`'s note
counts 36 handlers; `SYSTEM_ARCHITECTURE.md` §Baseline Stack says no Spring Security, no Redis client and no application object
storage. The same claims elsewhere: ADR-0027 and ADR-0089 noted here; `DATA_CLASSIFICATION.md` §6 still says no Kafka topic
exists (its guard is database-tier; left for its next change, recorded); three production comments (`P15-REM-004` (g)).

## The compliance-wording check

`docs/`, `README*`, `deploy/` and `infra/` grepped for compliant, compliance, certified, certification, attest, attestation, PCI,
SOC 2, ISO 27001, ISO/IEC, HIPAA, GDPR, PSD2 and the two phrasings of a readiness claim - 778 matching lines, each classified.
**None claims compliance with, certification under or attestation to a standard, or declares anything ready to run in production.** The
hits are disclaimers (`PHASE_15_PLAN.md` §1.1, `THREAT_MODEL.md` §8, `PRODUCTION_READINESS.md`), supply-chain attestations
(signed provenance and SBOM statements), the domain's own four-eyes attestations (settlement files, regulatory returns), "PCI
scope" as a design aim, the Object Lock mode named `COMPLIANCE`, and the bar's definition. This review writes none.

## The registers

| Register | Guard (tier) | Ruling |
|---|---|---|
| `DISTRIBUTED_EXECUTION.md` §3 | none reads it; `NoSingleInstanceAssumptionRulesTest` (architecture) holds the code's side, `ShutdownOrderTest` the forty components | **Drift fixed here** (two stale rows, the drain's count, the flush); a guard is `P15-REM-004` (a) |
| `AUDITABLE_ACTIONS.md` | `AuditableActionRegistryTest` (architecture; both ways by reflection over every action enum) | Holds; fresh |
| `ERROR_CONTRACT.md` | `ErrorCodeRegistryTest` (architecture; both ways) | Holds; fresh |
| `DATA_CLASSIFICATION.md` | `ColumnClassificationTest` (**database**) | Phase 15's 64 new columns each a row, read by hand with the guard's own pattern; the whole schema owed (PI-023) |
| The secret inventory (`SECRET_MANAGEMENT.md` §3.2) | `SecretInventoryIsCompleteTest` (architecture; both ways over the code's key classes, marked defaults and placeholders) | Holds; 54 rows; fresh |
| `CONFIGURATION_REGISTER.md` | `ConfigurationRegisterIsCompleteTest` (both ways over source, yaml, overlays and the delivered environment) | Holds; two stale sentences fixed here; fresh |
| `THREAT_MODEL.md` | `ThreatModelIsCompleteTest` (architecture) | §4 holds; §5 and §6 prose corrected here (T21/T27/T28/T45, the statement count, item 12, item 43), item 44 added |

## The mutation register

No probe applies to a review, and none was performed. **Section 4** gains `P15-TST-001`'s and `P15-TST-002`'s rows, each recording
its deferral as `P14-TST-001`'s did - no demonstration. The rows whose target is a shared register this review edited
(`THREAT_MODEL.md`, `PRODUCTION_READINESS.md`, `BACKLOG.md`, `CURRENT_STATE.md`, `MUTATION_TESTING.md`) are not performed again;
they are owed to the post-phase pass with the database halves (PI-023).

## The flip

**Rehearsed twice, restored each time.** `CURRENT_STATE.md`'s bytes were saved (sha256 `99a64af6…13b472`), Phase 15's status line
set to the form `ThreatModelIsCompleteTest`'s `COMPLETED_STATUS` matches, and every guard that arms at the flip run by name -
`ThreatModelIsCompleteTest`, `PlannedMetersExistTest`, `MutationDemonstrationTest`, `ProductionReadinessIsEvidencedTest` (no other
test reads Phase 15's status; the filter also ran eight ArchUnit rule classes, green).

- **On master's bytes** (2026-11-12 16:51-16:57 UTC): **68 tests, 1 failed** - `MutationDemonstrationTest`'s test-item case,
  naming exactly `P15-TST-001` and `P15-TST-002`. The threat model's exit rules green (14/0: every CRITICAL and HIGH threat
  has an `EXISTS` row, no acceptance proposed); every planned meter of `PHASE_15_PLAN.md` §11.2 registered (13/0); the readiness
  guard 8/0. Restored, sha256 equal.
- **After this review's changes** (2026-11-12 17:11-17:12 UTC, the record's bytes saved at sha256 `363b33c2…2136a118`): **68 tests, 0
  failed** - `MutationDemonstrationTest` 9/0 with the two section 4 rows; the threat model 14/0, the planned meters 13/0, the
  readiness guard 8/0, the eight rule classes 24/0. Restored, sha256 equal.

**What the flip needs beyond its guards** is the owner's word on the deviations: *Owner acceptances* below.

---

## Owner acceptances the flip needs (one answer)

| # | Decision | Recommendation |
|---|---|---|
| **A** | **The owner's own postponements, as recorded deviations of Phase 15**: the storm (PI-004); the database pass and every case owed to it (PI-023 - universal criterion 7, the gate's database-pass item, the concurrent-execution row, the sweeps, the burst across replicas, and the residual rows of T3, T12, T13, T17, T33, T34, T38, T44); the platform rehearsals (PI-024 - criterion 4, the final-tree release and census, and T11's, T12's, T29's and T46's residual rows) | Accept, owned after the project's phases as each issue says; reminded at every exit |
| **B** | **The ten-instance answer `UNKNOWN` at ten**, held by A's PI-004 and PI-023; no counted run failed | Accept as a deviation; ten instances at load are written as a Phase 16 task by the transition (item 43 (g), decided) |
| **C** | **Criterion 1 read in its measurable form**: all 38 CRITICAL and HIGH threats have built evidence, and 32 carry residual rows held by the named owners above (the postponed issues, `P14-REM-*`, `X-TSK-*`, decided owner items) | Accept; the residuals re-read at the Phase 16 exit. No threat needs an acceptance disposition: the guard is green at the flip |
| **D** | **The second person** on production and on a `FINANCIAL` change (CI/CD, configuration): owner item 4, decided 2026-11-08 | Stands as decided: the owner applies the branch rules and names a second person when the repository gains a production environment |
| **E** | **Item 12's three Semgrep baseline rows** (the role bootstrap, 2026-11-12) | Accept until 2027-06-30, as their siblings (criterion 2) |
| **F** | **Item 44** (a) the restore's lending and BNPL proofs over a source that has them; (b) `trivy config` over the rendered manifests | (a) the Phase 15 → 16 transition writes it into Phase 16's post-recovery verification drill; (b) in Phase 16's first wave |
| **G** | **`P15-REM-001`…`-005`**, `PLANNED` with their horizons | Schedule as each entry's horizon says; **`-005` before the transition gate's guard run** (the taxonomy is red until it lands), the rest block nothing |

## Debt register (Phase 15's, after this review)

| Debt | Owner |
|---|---|
| The storm (`P15-TST-001`) | PI-004, after the phases |
| The database pass and every Phase 15 database case; PI-012, PI-014, PI-015 | PI-023, after the phases |
| The platform rehearsals not run whole | PI-024, after the phases |
| Owner-postponed defects, each with its horizon ("before production use", PI-017 before the first real customer launch) | PI-016…PI-022 |
| ADR-0006's rules; the audit record per route; the trace's legs; guards named for more than they hold; the taxonomy's red | `P15-REM-001`…`-005` |
| The restore's lending and BNPL proofs; the manifests' graded scan | `THREAT_MODEL.md` §6 item 44 |
| Every readiness `GAP` (83) | its named owner in `PRODUCTION_READINESS.md` |
| Phase 14's remediation | `P14-REM-001`…`-008`, `-010`, `-011`, `PLANNED` |

---

## Tests, every count from a fresh run

The flip's two runs are *The flip*'s. Then the guards, by name (the owner's rule: no whole-module task, no `:app:architectureTest`,
no package filter, no database, Kafka, cluster or container), each XML's UTC timestamp read against the run's start:

- **The named guards after the review's changes** (2026-11-12, started 17:14:50 UTC, every XML written 17:15:24-17:20:31): the
  eight the entry names (`ThreatModelIsCompleteTest` 14, `MutationDemonstrationTest` 9, `PlannedMetersExistTest` 13,
  `ProductionReadinessIsEvidencedTest` 8, `DomainGlossaryTest` 9, `AdrRegistersAreReconciledTest` 4,
  `ArchitectureRulesAreDocumentedTest` 2, `TestTaxonomyTest` 17), every guard reading a file this review changed
  (`SecurityScansAreWiredTest` 9, `RunbookRehearsalCountTest` 7, `CodeOwnersTest` 4, `ConfigurationRegisterIsCompleteTest` 39,
  `DatabaseRoleSettingsAreSafeTest` 5, `ConfigurationFingerprintTest` 10) and the registers' guards (`AuditCompletenessTest` 4,
  `AuditableActionRegistryTest` 7, `ErrorCodeRegistryTest` 6, `SecretInventoryIsCompleteTest` 38), with the eight rule classes
  the filter pulls in (24): **229 tests, 1 failed** - `TestTaxonomyTest#everyTestClassDeclaresATierAtLeastAsHeavyAsItNeeds` on
  `ReportingPoolMetricsTest`, master's red (above, `P15-REM-005`); every other class green.
- **After recording `P15-REM-005`** (the backlog, this review and `CURRENT_STATE.md` changed again): the guards reading those files
  once more - *(below)*.

**Not run, owed**: every `database`, own-container and `kafka` test (PI-023), `RiskStormTest` and the slow classes, the
`kind` and container scripts (PI-024), and the CI jobs, which no one here observes.

**The entry's "the `com.finapp.app.architecture` package"** is replaced by the named guards above, by the owner's rule of
2026-11-08; a deviation from the entry, recorded.

## What the phase produced

Two images (`finapp-app`, `finapp-migrator`) built reproducibly from digest-pinned bases; the runtime contract (a management
port, graceful shutdown, drained schedules); Kubernetes manifests with namespace security, a segmented edge and the connection
budget as policy; a `kind` rehearsal of three replicas; Terraform for nine modules and two roots, verified and never applied;
scanning, static analysis, signing, admission and promotion by digest through declared Argo CD Applications; the secret
inventory, key rings and rotation rehearsed; the telemetry pipeline and the trace; every gauge off the scrape thread; SLOs,
correctness alerts and routing; Kafka over `SASL_SSL` with topics as code; event and handler catalogues; dead letters, replay and
retention sweeps; backup and restore with the financial verification; the configuration register; platform runbooks; the
postponed production defects' fixes (PI-013, PI-014 (1), their database cases owed with PI-023); the source-join census; callbacks as hints; the clock class narrowed;
operators typed, refused on the public surface and their access hardened; the threat model (47 threats) and the readiness review
(170 cells) - in this repository, never in a real environment.

## What happens next

**The Phase 15 → 16 transition is the owner's to open** - this review marks nothing `READY` and starts nothing of Phase 16.
Its agenda: the owner acceptances above (A-G); item 43 (g)'s Phase 16 drills written as tasks (the database failover, the data
tier's availability, ten instances at load) and item 44 (a)'s verification beside them; and **the reminder** - the storms of
Phase 13 onward (PI-004), the Phase 15 database pass (PI-023) and the platform rehearsals (PI-024) are owed after the project's
phases.
