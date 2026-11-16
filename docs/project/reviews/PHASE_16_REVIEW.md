# Phase 16 Exit Review — Scale, Resilience and Disaster Recovery

**Conducted:** 2026-11-16 (`P16-DOC-001`, branch `P16-DOC-001` from `86ec7333`, `P16-REM-003`'s gate, unmerged)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (the twelve universal exit criteria, and the
financial-phase supplement read where it applies: F1, F3, F5, F6), §5 Phase 16 (the six criteria made measurable, the per-area
blocks and the exit pass, read from the gate at review time)
**Phase objective under review:** financial correctness under load, failure, partial availability and recovery, not throughput -
three named workloads and a load harness reading the invariants during load; registered failure experiments on a multi-node
`kind`; ten instances at load; the database, the event pipeline and the application at scale; resilience patterns; high
availability, backup, point-in-time recovery and a disaster-recovery rebuild with RTO and RPO by tier; a multi-region assessment;
the fifteen financial-correctness-under-failure scenarios - in this repository, with no cloud account, and never claimed for a real
environment (`PHASE_16_PLAN.md` §1.1).

**The tree read.** Branch `P16-DOC-001` stacks every Phase 16 item: `P16-TST-002`, `X-TSK-020`, `P16-REM-001`, `X-TSK-021`,
`P16-REM-002` and `P16-REM-003` are on their branches and unmerged. Master holds seven commits this branch does not: two
cross-cutting tasks of the operator console's own programme (`GET /v1/operator/me`, the console on the operator edge) and their
merges. Neither is a Phase 16 item, and neither changes what this review rules.

| | Outcome |
|---|---|
| Items | **37**: 36 `COMPLETE` before this review, and this review. Two remediation entries written here, `PLANNED` (`P16-REM-004`, `-005`) |
| What ran on a cluster | **Four experiments of 87**, each at three replicas on 2026-11-13, before the owner postponed every `kind` run (PI-025): E01, E27, E28 `PASSED`; E30 `FAILED` (its remediations built, its run again owed). Of the rest, 79 are owed under PI-025, three cannot be driven on `kind` (E19, E20, F06) and one does not apply (E10, no Redis) |
| The six criteria | **6 `PASS`** (no replica routed, held by a guard); **1-5 `PARTIAL` by the owner's choice (PI-025)** - each built and contract-tested, its runs owed; criterion 5's no-extraction decision rests on executed runs, its no-partition and no-replica decisions are derived |
| The per-area blocks (17) | **2 `PASS`** (the multi-region assessment, designed-only by nature; documentation, after this review's corrections); **15 `PARTIAL`**, each by PI-025 and some by PI-023 as well - their hermetic and written halves pass, their runs are owed |
| The exit pass | **`PARTIAL` by the owner's choice** (PI-025): the guards by name green; no quick form ran; the fleet tiers `NOT MET` (PI-023) |
| Universal criteria (12) | **7 `PASS`** (2, 3, 6, 8, 9, 10, 12 - 3, 8, 9 and 10 after this review's work); **1, 4, 5, 11 `PARTIAL`**; **7 `NOT MET` by the owner's choice (PI-023)** |
| F1, F3, F5, F6 | F1 `PASS` for what the phase changed, its under-load form `PARTIAL`; F3, F5, F6 `PARTIAL` by the owner's choice (PI-023, PI-025; F3's lasting reach is PI-027's) |
| *"Correct with 10 concurrent instances?"* | **`UNKNOWN` at ten, by the owner's choice** (PI-025, PI-023). **`PASS` at three as counted** (E27, E01, E28 and the isolation run, 2026-11-13); **`PASS` by construction** (`DISTRIBUTED_EXECUTION.md` §4b's eleven questions, held by `DistributedSystemAuditIsEvidencedTest`); no `FAIL` |
| ADR-0126…0151 | **All twenty `Accepted`**, each read decision by decision against the tree; **seventeen corrected in place first** (thirty-nine corrections), ADR-0137, ADR-0148 and ADR-0150 as written. Six numbers were never written (0130…0132, 0138, 0143, 0146); the next free number is ADR-0152 |
| C1-C12 | 7 as decided (C3, C5, C6, C8 with the owner's W7 change, C9's objectives, C10, C2's fleet-tier half); C7 honoured in another form; C1, C2's experiment half, C4 and C11 changed by the owner's later word (PI-025); C12 re-dated to the first real rail, confirmed by the owner at this exit |
| Designed-only | **Ten named** (below), D01-D06 of the register **accepted by the owner, 2026-11-16**, their local analogues owed |
| Compliance wording | **None claims compliance, certification, attestation or production readiness** (952 matching lines read, 49 more in `deploy/`) |
| The flip, rehearsed | **1 red of 86 on the review's first bytes** (the register's guard reading this review's own note in E30's cell); **0 of 86 after the fix** - see *The flip* |
| **Verdict** | **Phase 16 complete with recorded deviations, the flip the owner's.** Every item is built and guarded; what rests on a run is owed under PI-025 and accepted by the owner as Phase 16's recorded deviation (acceptance A, 2026-11-16), as are universal criterion 7 and the fleet tiers (PI-023) and the ten-instance answer `UNKNOWN` at ten. No CRITICAL finding; five IMPORTANT, each fixed here or decided by the owner. The status reads `IN_REVIEW`; `IN_REVIEW` → complete is the owner's explicit act (`PHASE_GATES.md` §1) |

**How it was conducted.** Seven independent read-only audits, each given the gate and the owner's postponements and nothing of the
others' findings: A1 the six criteria, the exit pass, the ten-instance question and the designed-only list; A2 and A3 the per-area
blocks, eight each; A4 and A5 the twenty ADRs, decision by decision; A6 the universal criteria, the supplement and the registers;
A7 C1-C12, the stale documents and the compliance wording. Every finding acted on was re-read from its file and line first; the
register's counts were recomputed from its rows. By the owner's standing rule no `database`, `kafka` or own-container tier and no
cluster or container run was made; the named guards were run fresh (*Tests*).

---

## What the review found, and what it did about it

**No CRITICAL defect was found.** Phase 16 changes no financial rule beyond its three remediation tasks' reads and the posting
scope's uniqueness, and adds no money movement. The review hunted where the phase claims something.

**IMPORTANT, five:**

1. **ADR-0142 recorded a bound that does not exist.** Decision 1 kept the journal unpartitioned because the whole-history account
   folds are "bounded by ADR-0133's commit-order checkpoint". The tree bounds one: the merchant API's payable read
   (`CheckpointedPositionBreakdown`, its only reader held by `PositionCheckpointsAreTheApisAloneTest`). `AvailableBalance.underLock`
   still folds an account's whole history while it holds that account's lock, on the money-moving path, and so do the purpose
   balances and the projection's verification. **Corrected in place**: the decision stands (a time key prunes none of them), its
   justification now says which fold is bounded and that the rest wait on E38, owed under PI-025.
2. **Seven tables and one column had no classification.** Phase 16's migrations added `ledger.position_checkpoint`,
   `position_checkpoint_bucket` and `journal_line.txid` (`V038`), `accounting.opening_checkpoint` and `opening_checkpoint_group`
   (`V017`), `payments.sweep_row_failure` (`V040`), `merchant.sweep_row_failure` (`V016`) and `reconciliation.rematch_fault`
   (`V025`), and `DATA_CLASSIFICATION.md` had no row for any - `ColumnClassificationTest` is database-tier and has not run since
   Phase 15. **Fixed**: 54 rows written by hand against the DDL, the checkpoints' sums at `account_balance`'s ceiling
   (`RESTRICTED-FINANCIAL`); the database's comparison is owed with PI-023's pass, and PI-023 now lists it.
3. **Closing this review would have left two of the register's holds empty.** E30's standing `FAILED` and D01-D05 were held by
   `P16-DOC-001` alone, and `ResilienceExperimentsAreRegisteredTest` needs a live task, or the owner's dated acceptance, for each.
   **Decided by the owner** (acceptances B and C): D01-D05, and D06 (the managed restore, which the register lacked and the gate
   names) accepted as designed-only on 2026-11-16; E30 held by `P16-REM-004`, `PLANNED`, the stress profile again with E52 when
   PI-025's runs are made.
4. **Two task identifiers collide with master.** This stack's `X-TSK-020` (no transaction spans a provider call) and `X-TSK-021`
   (the payable read's census) were numbered after master's last cross-cutting task as this stack saw it; master has since given
   `X-TSK-020` to the operator console on the operator edge, and its backlog already lists `X-TSK-019` twice (the operator's own
   identity, and the three guards red since Phase 15 and 16 merges). A guard that maps an identifier to a status would read one of
   each pair. **The owner's at the merge** (acceptance F): this stack's two are renumbered when it merges. Recorded, not changed
   here: this branch cannot see master's entries.
5. **`INV-IDEM-01` read unbounded after the owner bounded it.** Since the idempotency records' sweep (ADR-0141, `THREAT_MODEL.md` §6
   item 63, decided 2026-11-13), a transfer's or a card checkout's once-ness lasts its record's 24-hour expiry plus the sweep's
   30-day grace; a posting's is for ever. The catalogue said nothing. **Fixed**: a dated note under the invariant; its second
   arbiter is PI-027's, after the phases.

**MINOR, recorded** (fixed where marked):

- **Stale documents** that a reader would take as current - **fixed with dated notes**: `PHASE_16_PLAN.md` (its header, §2.2's
  outcome, §3's table, §19.2, §19.3, §24, §27), `CURRENT_STATE.md` (the ADR count, the item count, M16.14 missing from the milestone
  list, C1's row, §Active Work's initialisation paragraph, the payable read's debt row, paid by `P16-REM-001`), `ROADMAP.md`,
  `DELIVERY_PLAN.md`, `SYSTEM_ARCHITECTURE.md`, `DECISIONS.md` (the next free number, C12), `TASK_REGISTER.md`, `CAPABILITY_MAP.md`,
  `MODULE_ARCHITECTURE.md`, `CAPACITY_MODEL.md`, `docs/adr/README.md` (the Phase 16 row closed; the next free number),
  `RESILIENCE_EXPERIMENTS.md`'s head ("no experiment has run"), `POSTPONED_ISSUES.md` (E30's holder; PI-024's poisoned row, run as
  E28; PI-023's Phase 16 cases), `THREAT_MODEL.md` (items 68 and 69 missing from the list awaiting the owner; item 18's closed third
  part), the runbook and the availability matrix (a lost database answered `500` "until `P16-REM-002` is built", built on
  2026-11-15; the WAF's "item 32 (b)", which is 22 (b)), `DISTRIBUTED_EXECUTION.md` §4b (the background roles "at ten, `owed E77`" -
  E77 runs at three; at ten it is E69), `P15-REM-007`'s horizon ("Phase 16", now after the phases with E70), and
  `MUTATION_TESTING.md` §4 (no rows for `P16-TST-001`, `-002`).
- **The register cannot tell an owed run from one that does not apply.** Since PI-025, 79 rows read `N/A: not run` with the
  postponement as the reason, and the guard admits any `N/A` with a reason, though §1.2 keeps `N/A` for an experiment that does not
  apply. ADR-0127's note said the guard refuses an unrun experiment once its runners finish; it refuses only an empty cell.
  **ADR-0127 corrected; the guard is `P16-REM-005`'s**, with the runbook count's "Phase 16 drill" reason, which reads at the exit as a
  drill still to come.
- **`P16-REM-002`'s client-answer paragraph sits in §11.35 (the restore)**, not beside the failover it describes. Recorded; moving
  it is the next change to either section.
- **The FX hot rows have no profile.** `FX_POSITION` and `FX_SPREAD_REVENUE` are hot rows of `PHASE_16_PLAN.md` §6.4 that no load
  profile drives, though the simulator serves FX; no owner decision records it. Recorded with E38's owed run.
- **`P16-TST-002`'s "final tree" is `8f20d7c3`**, which thirty production files of the three remediation tasks and `X-TSK-021`
  postdate; each of those re-ran its modules' tiers at its gate, so the coverage holds and the wording overstates it. Recorded.
- **The Terraform record names no commit** (`CHANGE_LOG.md`, 2026-11-14: 192 `terraform test` runs, 46 of 46 plants refused); the
  tree under `infra/terraform` is unchanged since. Recorded.
- **E01's pass predates the security probe and the availability walk** its row now asks for, so it is not evidence for ADR-0147 or
  the matrix. Recorded; its run at ten is owed.
- **A restore brings revoked sessions, roles and merchant keys back** after its point (`THREAT_MODEL.md` §6 item 69, a residual of
  T3 CRITICAL and T20 HIGH), awaiting the owner. Recorded under universal criterion 11.

---

## The six criteria

### 1. Load test at target volume with financial invariants asserted during load — `PARTIAL` by the owner's choice (PI-025)

| Clause | Ruling | Evidence |
|---|---|---|
| The sustained profile at the target's peak, or at the engineering workload with the ratio recorded | `PARTIAL` | The ratio is recorded, about 2.4 (`CAPACITY_MODEL.md` §3.4), from E30's third step, which held 277 s; no sustained profile has run (E29 and E69, `RESILIENCE_EXPERIMENTS.md`) |
| The correctness signals and the census sampled every 30 s, zero failures | `PARTIAL` | E27, the demonstration load at three replicas: 61 of 61 samples green (`7231d298`, `OPERATIONS_RUNBOOK.md` §11.43); the isolation run: 57 of 57 (`f021383f`). At the engineering workload E30 stopped at its 35th sample on an unreadable sample, the pool starved (`89b8fc60`, §11.45) |
| One effect per key, the same-key retries included | `PARTIAL` | E27's retried keys alike (39/39, 79/79, 15/15, §11.43): demonstration load, three replicas |
| The full census after the load | `PARTIAL` | E27's and the isolation run's; none at the engineering workload |

### 2. Chaos experiments each leave the ledger correct — `PARTIAL` by the owner's choice (PI-025)

E04 (a worker stopped), E05 (the standby promoted), E08 and E09 (one broker, then all), E03 (pods and their caches lost), E14 and
E15 (partitions): each built and held by its contract test (`InstanceFailuresRehearsalContractTest`, `ExperimentsRehearsalContractTest`,
`PipelineRehearsalContractTest`, `NetworkFaultExperimentsContractTest`), none run. The phase's one fault run is E01 at three replicas
(`a6fee3a1`, `PASSED`), which the criterion does not list. A shared cache's loss is `N/A`: the platform has none (E10, the gate's
dated note of 2026-11-12). Designed-only: D01-D03, accepted.

### 3. Measured RPO and RTO meet documented targets — `PARTIAL` by the owner's choice (PI-025)

The targets are documented and accepted (ADR-0128's tiers, C9). **None is measured**: tier 1's interruption is E05's, tier 2's
point-in-time duration and rate per GiB E60's, tier 3's rebuild E87's - all owed. What ran is the point-in-time mechanics over a
5,000-row table in containers (2026-11-13, `OPERATIONS_RUNBOOK.md` §11.55: "not a restore rate"). ADR-0128 is corrected to say every
RTO and RPO is an objective, not a result. Designed-only: the managed services' times and tier 4 (D01, D04, D06).

### 4. Post-DR verification before money movement resumes — `PARTIAL` by the owner's choice (PI-025)

| Clause | Ruling | Evidence |
|---|---|---|
| Roles locked until `RestoreVerification` passes over a source holding every product | `PARTIAL` | The widened source, a loan and a BNPL obligation among it, verified as a source (`RESTORE_STOP_AFTER=source`, head `8e357114`, §11.35); no restore of it ran (E59). The restore of 2026-11-11 ran on Phase 15's source: its lending and BNPL proofs had no subject |
| The checks covered | `PARTIAL` | Ran on 2026-11-11 over one open period, none locked |
| Settlement re-ingested and reconciliation re-run, money held | `PARTIAL` | `RestoreReconciliation` built; E61 owed |
| Money moves only after a recorded release | `PARTIAL` | Built (ADR-0140 decision 4); the release of 2026-11-11 was the drill's own; E59, E61 and E87 owed |
| A planted corruption fails the verification | `PARTIAL` | Eleven plants refused on 2026-11-11 (§11.35); the after-DR plant (E87) owed |

### 5. Extraction and partitioning decisions backed by measurement and an ADR — `PARTIAL`

| Decision | ADR | Measurement |
|---|---|---|
| No module extracted | ADR-0151 | **Executed**: E30 and the isolation run at three replicas (2026-11-13). Its reopening runs (E38, E39, E77) owed |
| No table partitioned | ADR-0142 decision 1 | **Derived** from W1-W8 and the code; only the lines-per-entry ratio measured (E30); E65 owed. Corrected here (finding 1) |
| No read replica routed | ADR-0142 decision 2 | Derived; criterion 6 below |
| No role split | ADR-0145 | Derived from costs measured at three; E77 owed |
| No transaction-mode pooler | ADR-0134 | Derived; E41 owed |

### 6. Read replicas provably never serve authoritative financial reads — `PASS`

The first branch holds: no replica is routed (ADR-0142 decision 2), and `OnePrimaryServesEveryDoorTest` holds that one primary data
source serves every money-moving door, with its probe; `ReportingPoolEndpointTest` and `PrimaryOnlyGuard` hold the reporting pool
to the primary. Green in this review's run (*Tests*).

---

## The per-area blocks

| Block | Ruling | What holds, and what is owed |
|---|---|---|
| Performance and capacity | `PARTIAL` (PI-025) | 10 of 13 operations measured at three replicas (E30's third step, the isolation run); credit, lending and BNPL not drivable (no bureau on `kind`, the owner's decision). The first limit named, `PASS`: the database's CPU at three replicas (`CAPACITY_MODEL.md` §3.4, E30). W1-W8 `PASS`: adopted (C8), W7 changed by the owner on 2026-11-13 |
| Sustained and peak load | `PARTIAL` (PI-025) | The longest hold is 277 s; E29, E39, E69 and E90 owed. Shedding before the claim proven hermetically (`ShedBeforeTheClaimTest`); the checkout's create in one transaction (`CheckoutCreateIsOneTransactionTest`) after E30's overload answered 12 checkouts `500` after their commit; E50-E52 and E75 owed |
| Concurrent financial operations | `PARTIAL` (PI-025) | Nothing of `CAPACITY_MODEL.md`'s hot-row section is measured; E38 owed; scenarios 7 and 8 at ten (F07, F08) owed; the FX hot rows have no profile (MINOR, above) |
| Database scalability | `PARTIAL` (PI-025, PI-023) | The budget at ten held at startup by `ConnectionPoolSizingGuard` and the `kind` formula by `KubernetesManifestsTest`; its peak never measured (E41). One plan read (E30's fold). Item 18's archival and sweeps built with their database cases written, `PASS`; the posting scope unique before any record is swept, `PASS` - platform `V016` refuses to sweep while `journal_entry`'s scope index is absent, after ledger `V037` |
| Kafka throughput and recovery | `PARTIAL` (PI-025) | The only measurement is adverse: the relay fell from 6 s to 59 s behind at E30's checkout peak; ADR-0136's change is unmeasured. E08, E09, E47-E49, E71 owed; D03 accepted |
| Resilience | `PARTIAL` (PI-025) | One inventory with each retry classified, `PASS` (`TIMEOUTS_AND_RETRIES.md`, `OutboundCallsAreBoundedTest` - it holds the class's presence, the classification is the review's claim). Shedding's plant hermetic, its `kind` plant owed. A poisoned row survived and paged, `PASS`: E28 (`e9cdbb3a`), `PaymentSweepRowUnreadable` reaching the page receiver, no tick failed whole |
| Failure injection | `PARTIAL` (PI-025) | Of §9.2's 26: E01 run, E10, E19 and E20 `N/A` with their reasons, 22 owed. Every row complete, `PASS` (the register's guard). Every failure held by a live task, `PASS` (E30 → `P16-REM-004`). The foreign-context refusal proven by a plant, `PASS` (2026-11-13, `a6fee3a1`'s harness; later harness bytes not re-proven) |
| High availability | `PARTIAL` (PI-025) | The SPOF register with an answer each, `PASS` (`AVAILABILITY.md`, `AvailabilityMatrixTest`). The matrix written and guarded; its ten walks owed. No objective claimed met or recalibrated (`SLOS.md`). D02 accepted |
| Backup and restoration | `PARTIAL` (PI-025) | Criterion 4. A missing key version refused hermetically (`RestoreVerdictsTest`), its plant owed (E59); the Kafka step of §11.35 run nowhere (E61, F15). D06 accepted |
| RTO/RPO validation | `PARTIAL` (PI-025) | Criterion 3 |
| Disaster recovery | `PARTIAL` (PI-025) | The plan written, `PASS` (ADR-0149, runbook §11.64 and §11.65). The rebuild built and contract-tested (`DisasterRecoveryRehearsalContractTest`); E87-E89 owed. T42 answered and built, E88 owed. The cloud half designed only (D04) |
| Multi-region assessment | `PASS` (designed-only by nature) | Every service, store and workflow assigned a model with its reason (`SYSTEM_ARCHITECTURE.md`, ADR-0129, ADR-0150); no active-active write path; the recovery-region variant through `terraform test` with mock providers, 192 runs and 46 of 46 plants refused on 2026-11-14, never applied. The rebuild there is item 72, awaiting the owner |
| Financial correctness under failure | `PARTIAL` (PI-025) | 0 of the 15 run; 14 built with their seven checks and held by `ScenarioExperimentsContractTest`; F06 not drivable (no loan on `kind`, the owner, 2026-11-13). No scenario claimed by a test alone |
| Security | `PARTIAL` (PI-025) | The hermetic halves hold (`SecurityUnderFailureContractTest`, `DegradedStorageFailsClosedTest`, `ShedBeforeTheClaimTest#theDoorNeverAdmitsPastAuthentication`). No plaintext at rest, run (2026-11-11; the archive's `.enc` files only, 2026-11-13); audit rows equal across the 2026-11-11 restore. A probe per experiment, sessions across failover (E82), the limits exact at ten (E81) and four eyes during a failover (E05) owed. WAF and DDoS designed only (ADR-0145 decision 8, ADR-0147) |
| Observability | `PARTIAL` (PI-025) | Item 43 (b), `PASS`: the platform components' rules seen to fire on `kind` (2026-11-12, `722365ad`), `ClusterWatchBlind` read, not run. Saturation in the executed runs' artefacts. Burn rules by promtool only; a real burn (E84-E86) owed. Item 42 (a)'s break-age counts, `PASS`: built, promtool both ways |
| Operational readiness | `PARTIAL` (PI-025) | The audit at ten, `PASS` by construction (`DISTRIBUTED_EXECUTION.md` §4b, `DistributedSystemAuditIsEvidencedTest`; "measured at ten: not yet"). Of 27 new procedures, six rehearsed with records, twenty owed, one designed only. The readiness cells re-read, `PASS` (`PRODUCTION_READINESS.md` §6.2). No unit called ready on a configuration file alone, `PASS` (`ProductionReadinessIsEvidencedTest`) |
| Documentation | `PASS` after this review | The twenty ADRs accepted; C1-C12 recorded; every designed-only criterion named; no compliance or readiness claim |

### The exit pass — `PARTIAL` by the owner's choice (PI-025)

None of the quick forms ran (the demonstration load at three and ten, E01, E05, E09, E59 with E61, F01, F14, E30 with E52); each
harness's contract test is green. The guard classes ran by name: `P16-TST-002`'s hermetic tiers across 25 modules (2026-11-15, 7,000
unit, 299 slice, 1,299 architecture, 0 failed) and this review's (*Tests*). The fleet tiers: `NOT MET` (PI-023) - C11's narrow
pre-flight never ran; two database classes ran on the owner's word before their merges.

---

## Universal exit criteria (§3)

| # | Criterion | Ruling | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | `PARTIAL` (PI-025) | All 36 items built and contract-tested; "exercisable end to end" shown on a cluster for four experiments only |
| 2 | Architectural boundaries respected | `PASS` | ADR-0006's rules made to hold (`P15-REM-001`); the architecture tier green, fresh (*Tests*) |
| 3 | Required invariants tested | `PASS` | No new invariant (no `Phase:` field names 16); Phase 16's items recorded 14 `INV` rows in `MUTATION_TESTING.md` §2, one of them a cluster probe performed (`INV-ACC-01`, `kind`); about 18 cluster demonstrations listed as owed under PI-025, not recorded; §4's rows for `P16-TST-001` and `-002` written here |
| 4 | Failure cases handled | `PARTIAL` (PI-025, PI-023) | Every scenario of `PHASE_16_PLAN.md` §13 has a partial test and a built experiment; none ran; F06's rationale accepted by the owner |
| 5 | Security requirements implemented | `PARTIAL` | Authentication and authorisation negatives hold hermetically; no secret in source; the classification completed here (finding 2), its database comparison owed (PI-023); the per-experiment probes owed (PI-025) |
| 6 | Observability exists | `PASS` (hermetic) | Metrics, rules, promtool cases, runbook sections; correlation reaches the dead-letter row (`CorrelationSinkCoverageTest`); a real burn owed |
| 7 | Integration tests pass | **`NOT MET` by the owner's choice** (PI-023; PI-025 for the cluster) | No `databaseTest`, `kafkaTest` or own-container tier has run since Phase 15 began; Phase 16's ten migrations have never run as a tier. On PostgreSQL 18.6 (2026-11-15): `PositionCheckpointDatabaseTest` and `OpeningCheckpointDatabaseTest`, on the owner's word |
| 8 | Documentation reflects reality | `PASS` after this review's corrections | Finding 1, the ADR corrections and the MINOR list |
| 9 | `CURRENT_STATE.md` updated | `PASS` after this review | The status `IN_REVIEW` (it read `READY` from 2026-11-12 to this review - the stale-status class the Phase 13 review named); the counts checked against `BACKLOG.md` (37 items, 14 milestones) |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` | Twenty, accepted here |
| 11 | No unresolved critical issues | `PARTIAL` | No item labelled critical or high is open in the debt register or the blockers. But owner items 67-72 await the owner, each a residual of a CRITICAL or HIGH threat (T1, T3, T20, T23, T32, T46) - item 69 among them, a restore reviving revoked access; PI-016…PI-022 stand "before production use"; PI-027 stands |
| 12 | Formal phase review conducted | `PASS` | This record |

### The financial-phase supplement, where it applies

Phase 16 is not a financial phase; the entry reads the supplement where the phase touched a financial path.

| # | Ruling | Proof, and what is owed |
|---|---|---|
| F1 | `PASS` for what the phase changed; under load `PARTIAL` (PI-025) | E27's census every 30 s at three replicas; `OpeningCheckpointTest#theSplitIsTheWholeFold` (unit) and its database twin, and `PositionCheckpointDatabaseTest#theSplitIsTheWholeFoldUnderConcurrentPosting`, run. The trial balance during E29, E38, E90 and F01-F15 owed |
| F3 | `PARTIAL` (PI-023, PI-027) | `PostingScopeUniquenessMigrationTest`, `PostingAfterTheSweepTest`, `IdempotencySweepMigrationTest`, `NoIdempotencyRecordIsSweptTest`, `ShedBeforeTheClaimTest`, `CheckoutCreateIsOneTransactionTest` run; `PostingScopeUniquenessDatabaseTest` owed (PI-023). A transfer's or card checkout's retry past its record's life is a new command (finding 5; PI-027) |
| F5 | `PARTIAL` (PI-023, PI-025) | Phase 16 changed no dedupe path; the inbox's and the webhooks' database and Kafka cases not run since Phase 15; E22, F05, F13, F15 owed |
| F6 | `PARTIAL` (PI-023, PI-025) | `racingWritersStoreOnePerWatermarkEachCorrect` and `tenInstancesFoldEachLockOnce` run on PostgreSQL; the ten-writer posting-scope case, the hold's ten releases and the sweeps' cases owed (PI-023); E38, F07, F08 owed |

---

## The ten-instance answer

**`UNKNOWN` at ten, by the owner's choice (PI-025, PI-023); `PASS` at three as counted; `PASS` by construction; no `FAIL`.** Accepted by
the owner (acceptance A).

- **Counted at ten**: no cluster run. E26, E41, E69, E70, E71, E81, E82, E90, F07, F08 and F14 read not run. The only ten-way counts
  are database cases in one JVM, run on the owner's word on 2026-11-15, over the two new mechanisms:
  `PositionCheckpointDatabaseTest` (racing writers) and `OpeningCheckpointDatabaseTest#tenInstancesFoldEachLockOnce`.
- **Counted at three, the census green**: E27, the isolation run, E01 and E28 (2026-11-13), and Phase 15's three-replica release
  runs. E30's `FAILED` is not a census failure: it stopped on a sample it could not read, the pool starved.
- **By construction**: `DISTRIBUTED_EXECUTION.md` §4b rules its eleven questions `PASS`, every arbitration in PostgreSQL, "measured at
  ten: not yet", held by `DistributedSystemAuditIsEvidencedTest`, which refuses an owed run cited as evidence.
- **Open as a result**: `P16-TSK-014`'s ten-instance answer, the Phase 15 completion gate's deviation B and `P15-REM-007`; production's
  ceiling stays seven (item 56, decided 2026-11-13).

---

## ADR-0126 … ADR-0151: accepted

Each read decision by decision against the tree - classes, files, configuration keys, numbers, task statuses - and corrected in
place first, each correction a dated note in ADR-0120's form. Status: `Accepted (2026-11-16, P16-DOC-001 - read against the code and
corrected first)`, or `… no correction needed`; the index agrees (`AdrRegistersAreReconciledTest`).

| ADR | Corrections |
|---|---|
| 0126 | 2: C1-C12 decided; what ran (E27, E30, the isolation run, at three) and what is owed |
| 0127 | 4: decided; one failure experiment run; the guard admits an owed `N/A` (`P16-REM-005`); the three brokers built |
| 0128 | 4: decided; "item 21 (a)" is 23 (a), twice; tier 4's recovery point is ADR-0150's region copy; no recovery run made, so every RTO and RPO is an objective |
| 0129 | 2: decided; decision 6's matrix and the `503` since `P16-REM-002`; decision 7's guard |
| 0133 | 1: the payable read bounded since `P16-REM-001`; the soaks still leave it out; its run under load is `P16-REM-004`'s |
| 0134 | 4: the ten-instance runs named in full and item 56 decided; 1.37 cores; 39 schedules; the HPA's targets derived |
| 0135 | 2: no partition, decided by ADR-0142 |
| 0136 | 1: E68 and E71 |
| 0137 | none |
| 0139 | 1: the `503` built |
| 0140 | 2: item 62 decided |
| 0141 | 2: a fifth sweep bean; E62 measures `V037` with `V038` |
| 0142 | 3: finding 1; the seven-replica runs owed under PI-025; the idempotency sweep exists |
| 0144 | 2: ten walks; the tasks built since |
| 0145 | 2: item 56 decided; item 67 (b) awaiting the owner |
| 0147 | 1: "item 32 (b)" is 22 (b) |
| 0148 | none |
| 0149 | 3: "21 (a)" is 23 (a), twice; tier 4's standing site |
| 0150 | none |
| 0151 | 3: the cutoff's ADR-0114; `P16-TSK-031` complete; its acceptance |

**The numbering.** ADR-0130, 0131, 0132, 0138, 0143 and 0146 were never written: 0132, 0138, 0143 and 0146 reserved by tasks that
needed none, 0130 and 0131 never reserved. Nothing here cites them; on master the operator console's programme cites an ADR-0130 and
ADR-0131 of its own repository, labelled so. **The next free number is ADR-0152.**

---

## C1-C12

| # | Decision | Ruling |
|---|---|---|
| C1 | The storm question | **Changed by the owner's later word** (PI-025, 2026-11-13): no in-process storm was written, as decided, but the soak and the experiments were postponed. `P15-TST-001` kept under PI-004 (acceptance E): nothing at ten exists to close it on |
| C2 | The database tier | Its fleet-tier half **as decided** (skipped per task, owed to PI-023, two exceptions on the owner's word); its experiment half **changed** (PI-025) |
| C3 | The connection budget at ten | **As decided**: `kind` at 13 instances and 116 connections, production's ceiling seven (item 56); measured at three only, E41 owed |
| C4 | Designed-only criteria | **Changed**: its "local analogue executed and recorded" is unmet; the owner accepted D01-D06 at the exit without it (acceptance B) |
| C5 | The load tool | **As decided**: k6 2.3.0 by digest, outside the build |
| C6 | The fault injector | **As decided**: primitives and Toxiproxy 2.13.0 by digest; no Chaos Mesh |
| C7 | The local failover pair | **In another form**: a test-only manifest the experiments scope applies, never rendered by an overlay; E05 and E56 owed |
| C8 | The reference workload | **As decided**, W7 changed by the owner on 2026-11-13 (about 7.3 events per money movement, read from the code) |
| C9 | Recovery objectives | **As decided** as objectives; none measured (criterion 3) |
| C10 | Multi-region | **As decided**: one region, active-passive, no active-active write; ADR-0150's standing site added |
| C11 | PI-023 and PI-024 as prerequisites | **Changed** (PI-025): the pre-flight never ran; of PI-024's steps, the poisoned row's substance ran as E28 |
| C12 | Automatic rail availability | **Re-dated** to the first real rail's measured failure rates - `kind` can measure only a simulator (D05) - **confirmed by the owner at this exit** (acceptance D) |

---

## Every designed-only criterion, by name

Each is written and reviewed, never claimed as executed; its local analogue's run is owed under PI-025 unless stated.

1. **A managed database's Multi-AZ failover and its real interruption** - D01; analogue E05, F11.
2. **An availability zone's loss** - D02; analogue E04.
3. **A managed broker's failover, of a broker or a zone** - D03; analogue E08.
4. **A region's loss and a regional recovery (tier 4)** - D04; analogue E87-E89; the standing site's configuration checked by
   `terraform test` with mock providers (2026-11-14), never applied; the rebuild there is item 72.
5. **The DR plan's cloud half**: a clean account, the copied snapshot restored, the key store and the registry in the recovery
   account - D04's note, ADR-0149, item 71 (b); analogue E87-E89.
6. **A managed restore and its times** - D06, written here; analogue E59-E61. The logical restore ran on 2026-11-11, on Phase 15's
   source.
7. **A real provider's outage, slowness or late answer** - D05; analogue E17, E18, E21, F13 (E19 cannot be driven; E20 rests on
   the recorded fail-safe).
8. **Rails no simulator serves** - matrix row A13 (ADR-0144), accepted by the owner on 2026-11-14; analogue the adapters' contract
   tests.
9. **A WAF and DDoS protection in front of the edge** - ADR-0145 decision 8, ADR-0147, item 22 (b); analogue the edge's per-source
   limits (E76, E81).
10. **The managed key store and secret store at recovery, managed DNS and the control plane, object-store recovery** - the plan's
    §10.1, §11.1 and §18; analogues E89, E16, E60.

D01-D06 were **accepted as designed-only by the owner on 2026-11-16** (acceptance B); 8 was accepted on 2026-11-14; 9 and 10 are
written and owned by `POSTPONED_ISSUES.md` PI-026 and the readiness cells for the first real environment.

---

## The compliance-wording check

**None claims compliance with, certification under or attestation to a standard, or declares anything ready for production.** 952
lines match the pattern in `docs/`, `infra/`, `README.md` and the root's documents, and 49 more in `deploy/`; almost all are TLS
certificates or supply-chain attestations (and the settlement files' four-eyes attestations). The 57 claim-shaped lines (production
ready, compliant, compliance with, SOC 2, PCI DSS, ISO 27001, certified) are each a disclaimer, a requirement, the readiness bar's
definition or a standard's name. Borderline, ruled fine: `ROADMAP.md`'s "designed to stay out of PCI DSS scope" (a design aim); a
planted wording mutation in `MUTATION_TESTING.md` (a probe); the PKIX "certification path" error text.

---

## The registers

- **`RESILIENCE_EXPERIMENTS.md`**: 87 rows - 4 results (E01, E27, E28 `PASSED`; E30 `FAILED`), 79 owed under PI-025, 3 not
  drivable (E19, E20, F06), 1 not applicable (E10). D01-D06 accepted. Guard green after this review.
- **`THREAT_MODEL.md` §4**: 418 `EXISTS`, 121 `GAP`, 9 `REMEDIATION`. `GAP` rows grew from 68 at the Phase 15 exit; 35 read
  `GAP | PI-025`, 23 `GAP | PI-023`, 5 `GAP | PI-024`. Two Phase 16 `GAP` rows turned `EXISTS` in place (T10's audit archival, T37's
  idempotency growth); T14's report-generation row `EXISTS` since `P16-REM-003`.
- **`MUTATION_TESTING.md`**: Phase 16's section 2 rows recorded and performed where they say so; the cluster demonstrations owed are
  listed under PI-025, not recorded. Section 4 gains `P16-TST-001`'s and `P16-TST-002`'s deferral rows here.
- **`PRODUCTION_READINESS.md`**: the six cells the transition gave Phase 16 re-read with their evidence (§6.2, 2026-11-15), each still
  as it was ruled; no unit called ready on a configuration file alone.

---

## The flip

**Rehearsed twice, restored each time.** `CURRENT_STATE.md`'s bytes, with this review's records in, were saved (sha256 `21d6c129…`), Phase 16's status line set to the form `ThreatModelIsCompleteTest`'s `COMPLETED_STATUS` matches, with this entry already `COMPLETE` in the backlog, and every guard that arms at the flip run by name - `ThreatModelIsCompleteTest`, `MutationDemonstrationTest`, `ProductionReadinessIsEvidencedTest`, `PlannedMetersExistTest` (no other test reads a phase's status) - with the register's guard, which reads this entry's:

- **First** (2026-11-16, 06:44-06:45 UTC): 73 architecture tests, **1 failed** - `ResilienceExperimentsAreRegisteredTest`'s failed-result case, on E30: this review's own note in the remediation cell named `PLANNED` in backticks, which the guard reads as a backlog identifier. The note fixed, the word unquoted; restored, sha256 equal.
- **Second** (06:45:59-06:47:07 UTC): 73 architecture tests and 13 slice tests, **0 failed** - the threat model's exit rules 14, the mutation register 9 (its section 4 now holding `P16-TST-001` and `-002`), the readiness review 8, the register 13 (E30 held by `P16-REM-004`, D01-D06 accepted, nothing held by this entry), the planned meters 13, and the rule classes the filter pulls in. Restored, sha256 equal.

**What the flip needs beyond its guards** is the owner's act: the status line's `IN_REVIEW` changed, and its date.

---

## Owner acceptances (decided 2026-11-16, "accept all")

| # | Decision | As decided |
|---|---|---|
| **A** | The owner's postponements as Phase 16's recorded deviations: every `kind` run after 2026-11-13 (PI-025), the database and Kafka tiers (PI-023), the storms (PI-004), the platform rehearsals (PI-024). Criteria 1-4 and the per-area run clauses `PARTIAL`; universal criterion 7 `NOT MET`; the ten-instance answer `UNKNOWN` at ten, `PASS` at three as counted | Accepted |
| **B** | D01-D05, and D06 written here, accepted as designed-only on 2026-11-16, though C4 asked for each analogue executed first | Accepted; the register reads so |
| **C** | E30's standing `FAILED` held by `P16-REM-004`, `PLANNED`; `P16-REM-005`, `PLANNED`, the guards told an owed run from an inapplicable one | Written |
| **D** | C12 waits for the first real rail's measured failure rates | Confirmed; `DECISIONS.md` reads so |
| **E** | `P15-TST-001` kept under PI-004 | Kept |
| **F** | This stack's `X-TSK-020` and `-021` renumbered when it merges (master holds an `X-TSK-020` and two `X-TSK-019`) | The owner's at the merge |
| **G** | F06 not drivable on `kind`: fourteen of fifteen scenarios can run | Accepted (as on 2026-11-13) |

---

## Debt register (Phase 16's, after this review)

| Debt | Owner |
|---|---|
| Every Phase 16 cluster run: 79 experiments, the soak, the exit pass's quick forms, the cluster probes | PI-025, after the phases |
| Every database, own-container and Kafka tier, Phase 15's and Phase 16's cases among them, and the classification's comparison | PI-023, after the phases |
| The storms of Phases 14 and 15 | PI-004, after the phases |
| The platform rehearsals not run whole | PI-024, after the phases |
| E30's run again with E52 | `P16-REM-004` |
| The register's owed form; the runbook count's PI-025 reason | `P16-REM-005` |
| The account folds other than the payable read, on E38's measurement | ADR-0142's corrected decision 1; E38 under PI-025 |
| The first real environment's managed-service decisions | PI-026 |
| A transfer's and a card checkout's second arbiter; a KYC case's production trigger | PI-027 |
| Owner items 67-72 (residuals of CRITICAL and HIGH threats) | The owner |
| The autoscaler seen scaling, and a ceiling above seven | `P15-REM-007` (E70, PI-025) |
| Phase 15's remediation still open | `P15-REM-002`, `-003`, `-006` |
| The two colliding identifiers | The owner, at the merge |

---

## Tests, every count from a fresh run

The flip's runs are *The flip*'s. Then, by the owner's standing rule (no database, Kafka, own-container or cluster run):

- **The baseline**, before any change, on `86ec7333`'s bytes (2026-11-16): app's architecture tier, 1,251 tests in 116 classes, 0 failed.
- **Every module's hermetic tiers, fresh, once on this review's bytes** (2026-11-16, started 06:47:28 UTC, each of the 75 test tasks executed with `--rerun`, every JUnit XML written 06:47:33-07:07:25): `unitTest` **7,057** tests in 990 classes, `sliceTest` **302** in 53, `architectureTest` **1,308** in 140 - **0 failed**, across 25 modules. Among them every guard reading a document this review changed: `ResilienceExperimentsAreRegisteredTest` 13, `ThreatModelIsCompleteTest` 14, `MutationDemonstrationTest` 9, `ProductionReadinessIsEvidencedTest` 8, `AdrRegistersAreReconciledTest` 4, `DistributedSystemAuditIsEvidencedTest` 7, `DistributedExecutionRegisterTest` 6, `RunbookRehearsalCountTest` 7, `AvailabilityMatrixTest` 11, `DomainGlossaryTest` 9, `ArchitectureRulesAreDocumentedTest` 2, `TestTaxonomyTest` 17, `PlannedMetersExistTest` 13; and criterion 6's `OnePrimaryServesEveryDoorTest` 12.
- **After this section's counts were written** into the review, `BACKLOG.md`, `CURRENT_STATE.md` and the change log, the guards reading those files once more: 2026-11-16, 07:08:39-07:12:28 UTC - `ResilienceExperimentsAreRegisteredTest`, `ThreatModelIsCompleteTest`, `MutationDemonstrationTest`, `ProductionReadinessIsEvidencedTest`, `DistributedSystemAuditIsEvidencedTest`, `AvailabilityMatrixTest`, `RunbookRehearsalCountTest`, `AdrRegistersAreReconciledTest`, `TestTaxonomyTest` and `PlannedMetersExistTest`, with the rule classes the filter pulls in: **132 tests in 20 classes, 0 failed**. After it only this line changed, and no guard reads this document.

**Not run, owed**: every `database`, own-container and `kafka` tier (PI-023) - among them `ColumnClassificationTest` over the 54 rows written here - and every cluster and container run (PI-025, PI-024).

---

## What the phase produced

A capacity model with three named workloads and a proposed production target; a load harness driving k6 through the real edge with
the invariants sampled during load; a failure-injection toolkit with its safety boundary and a register of 87 experiments and
scenarios, every field filled and guarded; the connection budget at ten and its guard; audit archival by verified segment, the
session sweep, one journal entry per posting scope and then the idempotency sweep; the event pipeline at load on three test-only
brokers; load shedding before the financial effect; sweeps that survive a poisoned row; every timeout and retry in one inventory; the
database failover drill and the socket timeout; the financial-operation availability matrix; restore and point-in-time recovery
with a held, verified, released recovery; the DR plan and its rebuild; the recovery region's standing site, checked and never
applied; the fifteen scenarios with their seven checks; security under scale and failure; the objectives judged under load and the
break-age counts; the extraction evaluation (no module leaves); the distributed-system audit; and, by the owner's remediation, the
merchant payable read and report generation's openings bounded by checkpoints, and a lost database answered `503` with `Retry-After`.
Four of its experiments ran on a cluster; the rest wait for the owner's runs.

## What happens next

**The flip is the owner's**: Phase 16's status reads `IN_REVIEW`, and `IN_REVIEW` → complete is recorded in `CURRENT_STATE.md` by the
owner's explicit decision (`PHASE_GATES.md` §1). This review marks no task `READY`. Phase 16 is the last phase of this repository's
plan, so the flip is also the point at which what was postponed "until after the project's phases" falls due. **The reminder**:

- **PI-025** - every Phase 16 cluster run: the ten-instance runs (E26, E41, E69, E70), the soak (E90), the failover (E05), the
  restores and the rebuild (E59-E61, E87-E89), the fourteen runnable scenarios, the exit pass's quick forms, about eighteen cluster
  probes; three brokers in every scope first; production's ceiling to ten only after them.
- **PI-023** - the database pass: every module's `databaseTest`, own-container and `kafkaTest` tier on the final tree, PI-012's,
  PI-013's, PI-014's and PI-015's cases, and Phase 16's own (listed there since this review).
- **PI-024** - the platform rehearsals: the `incidents` and `signals` scopes, the delivery's unseen steps, the release scope, the
  cluster probes.
- **PI-004** - the storms of Phases 14 and 15.
- **PI-026 and PI-027** - the first real environment's decisions, and the two domain changes placed after the phases.

**The merge** of this stack (`P16-TST-002` through `P16-DOC-001`, with `X-TSK-020` and `-021`) is the owner's, its two cross-cutting
identifiers renumbered on the way (acceptance F).
