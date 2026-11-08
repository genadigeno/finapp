# Production Readiness Standard

Written by `P15-TSK-001` (2026-11-08) from `PHASE_15_PLAN.md` §15. **`P15-TSK-034` fills it**, per deployable unit and
per module, from the evidence the Phase 15 tasks leave behind. This document sets the bar, says how a cell is ruled,
and carries the survey's reading of today. It rules nothing itself: every **Ruling** and **Evidence** cell below is left
for `-034`, and none may be read as met before then.

**A unit is production-ready when every dimension holds with evidence, not when its container starts.** Nothing here is a
compliance or certification claim (`PHASE_15_PLAN.md` §1.1), and nothing here declares a real environment ready. This
repository has no real environment (§1.1), so the most `-034` can rule is that a unit meets the bar **as rehearsed** on
`kind`, compose and CI. It says so in every cell it rules.

The threats each dimension answers are registered in [`THREAT_MODEL.md`](THREAT_MODEL.md). A readiness gap and an open
threat control are often the same fact seen twice: `-034` cites the threat's id where they are.

---

## 1. How `-034` rules a cell

Every cell takes exactly one ruling:

| Ruling | Means | Requires |
|---|---|---|
| `MET` | The dimension's bar holds for this unit or module | Evidence for **every** clause of the bar (§2), each item one of the forms below |
| `GAP` | Some clause of the bar does not hold, or has no evidence | The missing clause named, and its **owner** (a task id, a `P14-REM-*` or `X-TSK-*` entry, or the owner's acceptance in `THREAT_MODEL.md`) |
| `N/A` | The dimension does not apply to this unit or module | The reason, in one sentence. For example: telemetry has no financial recovery |

**What counts as evidence.** The forms are the threat model's (`THREAT_MODEL.md` §1.4), so one guard mechanism can read
both documents (`-034`'s entry: "the threat-model guard's mechanism reused"):

- a **repository path** in backticks, naming a file that exists: a test (`Class.java#member` where the class is broader
  than the claim), a manifest, a policy, a Terraform module, a rule file, a runbook section's document. A file that is
  not a test counts only with a content pin (`` `contains: …` ``, `` `lacks: …` ``) or a test asserting its property:
  its existence alone proves nothing (`THREAT_MODEL.md` §1.4);
- a **rehearsal record**: the path of a committed record of a `kind`, compose or CI rehearsal, stating what ran, on
  which commit, and the observed result (`-012`, `-020`, `-023`, `-030`, `-032` write these);
- a **counted test**, for the multi-instance row: the test or rehearsal whose count answers the ten-instance question.

**What never counts:** a container that started; a task's id (a promise, not evidence: a `GAP` names it as the gap's
owner); a document stating that a property holds without a test or record behind it; a `database`-tier test that has
not run fresh at the phase's database pass (`P15-TST-002`), cited as if it had.

**Named gaps that cannot be read as met.** Every open Phase 14 remediation entry (`P14-REM-001`…`-011`, each `PLANNED`
with its horizon) is a named `GAP` of the unit and module it touches, so this review cannot pass over it (§3.1, §4).
Each threat in `THREAT_MODEL.md` whose planned control is not yet built is a gap of the dimension that control serves.

**Which threats a unit answers for.** Security clause (i) reads the threats at the boundaries a unit stands on. A
threat whose `Where` names any of those boundaries is the unit's.

| Unit | Boundaries (`THREAT_MODEL.md` §2) |
|---|---|
| `finapp-app` | B2, B3, B4, B5, B6, B7, B8, B9, B10 |
| `finapp-migrator` | B4, B8, B9, B10 |
| PostgreSQL | B4, B10 |
| Kafka | B5, B10 |
| Telemetry | B10 |
| Edge | B1, B2, B3, B7 |

**Clause-level `N/A`.** A clause may be `N/A` while its dimension is not, with the reason stated in the cell. Two are
fixed here, so that `-034` does not decide them unit by unit:

- **PostgreSQL and Kafka, Deployment (a), (b) and (c).** Both are managed services outside the cluster (C3). The
  platform builds, signs and deploys no image of either, so "built by CI", "signed with provenance" and "deployed by
  digest through GitOps" do not apply. Their deployment is the Terraform data-tier module (`-015`), whose own checks
  stand in. Clause (d), "rolled and rolled back on `kind`", applies to the test-only single-node copies of the `kind`
  overlay (`-012`).
- **`finapp-migrator`, Health checks and Metrics.** A Job that exits has no probe and no scrape. Its health is its exit
  status, which Alerting reads.

---

## 2. The bar: twelve dimensions and the multi-instance row

Each bar is written as clauses that can each be checked against evidence. A dimension is `MET` only when every clause
holds.

| Dimension | The bar (each clause checkable) | Evidence that counts |
|---|---|---|
| **Deployment** | (a) built by CI from a reviewed commit; (b) signed, with provenance; (c) deployed by digest through GitOps; (d) rolled and rolled back on `kind` | CI workflow and image build (`-003`, `-004`); signature and admission policy (`-007`); the digest overlay and promotion script (`-017`); the rehearsal record (`-012`) |
| **Health checks** | (a) liveness reads the process alone; (b) readiness includes the database and the schema version; (c) a startup probe | Probe tests and manifests (`-005`, `-008`, `-009`) |
| **Logging** | (a) structured; (b) correlated (correlation and trace ids); (c) shipped through the one collector; (d) retained by class; (e) the needle test clean over a rehearsal's whole output | The ECS encoder's tests; the collector configuration and the needle test (`-023`) |
| **Metrics** | (a) registered per the naming contract; (b) every gauge read off the scrape thread; (c) scraped from the management port | `MetricConventionTest`; the off-thread guard, `NoGaugeReadsOnTheScrapeTest` (built by `P15-TSK-024`); the management port and its policy (`-008`, `-010`) |
| **Tracing** | (a) exported; (b) span attributes an allow-list; (c) one critical flow traced end to end | The exporter and collector allow-list; the end-to-end trace record (`-023`) |
| **Alerting** | (a) SLO burn-rate alerts and correctness alerts exist for the unit's flows; (b) routed by severity; (c) every alert names its runbook section | `promtool` cases, Alertmanager configuration and the runbook-link test (`-025`) |
| **Security** | (a) non-root; (b) read-only root filesystem; (c) no capabilities; (d) NetworkPolicy default-deny with its allowed flows; (e) workload identity; (f) secrets from the secret store; (g) scanned; (h) signed; (i) every CRITICAL and HIGH threat at the unit's boundaries (§1's map) has `EXISTS` evidence in `THREAT_MODEL.md` or the owner's acceptance | Image assertions (`-003`); pod profile policies (`-009`); NetworkPolicy tests (`-010`); identity modules (`-016`); secret delivery (`-018`); scans (`-006`); signatures (`-007`); the threat register's rows (`ThreatModelIsCompleteTest`) |
| **Resource limits** | (a) requests and limits set; (b) within the namespace quota; (c) the connection budget holds at maximum scale plus surge plus terminating pods (`PHASE_15_PLAN.md` §6.1) | Manifest policies (`-009`, `-010`); the measured peak (`-012`) |
| **Failure handling** | (a) graceful shutdown with schedules drained and consumers closed; (b) losing a dependency degrades readiness, never liveness; (c) idempotent recovery after a kill | Drain tests (`-008`); health tests; the rehearsal's kill step and census (`-012`) |
| **Recovery** | (a) restore rehearsed, ending in the financial verification; (b) rollback rehearsed | Restore record (`-030`); rollback record (`-012`) |
| **Ownership** | A named owning module (and person or rota in a real environment) for every alert and every runbook section | The alert files' and runbook's owner labels (`-025`, `-032`) |
| **Runbooks** | (a) written; (b) linked from every alert; (c) rehearsed, with the result recorded | `OPERATIONS_RUNBOOK.md` sections; the link test; the rehearsal records (`-032`) |
| **Multi-instance** (`CLAUDE.md`'s mandatory rule; `-034`'s "multi-instance row") | The ten-instance question answered `PASS` on a counted test or rehearsal, never by design alone | The counted test or rehearsal (`-012`, `-020`, `-028`, the phases' counted contention tests) |

---

## 3. The units

Each table carries **Today**, the survey's reading in `PHASE_15_PLAN.md` §15 (`Missing`, `Partial`, `Local only`,
`N/A`, or `—` where §15 records no reading), plus the repository's facts where this task counted them. **Ruling**,
**Evidence** and **Gap and owner** are `-034`'s, and `—` until it rules them.

### 3.1 `finapp-app`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Missing: no image, manifest or pipeline stage *(2026-11-08, `P15-TSK-003`: the image is built by CI from the commit, twice and compared, and pushed nowhere; unsigned; no manifest or delivery)* | — | — | — |
| Health checks | Partial: liveness `livenessState`, readiness `readinessState,db`, details never; no schema-version check, no startup probe; the actuator on the management port 8081, loopback outside Kubernetes *(`P15-TSK-008`, 2026-11-08; read "the actuator on the API port" until then)*, no NetworkPolicy yet | — | — | — |
| Logging | Partial: ECS JSON with correlation and trace ids, default-deny redaction; no shipping, retention or central search | — | — | — |
| Metrics | Partial: the naming contract enforced; every gauge read off the scrape thread on one gauge thread per instance, restartable (`P15-TSK-024`, 2026-11-08; read "about sixty gauge classes read on the scrape thread" until then); scraped from the management port *(`P15-TSK-008`, 2026-11-08; read "from the API port" until then)*, its policy not yet built | — | — | — |
| Tracing | Partial: spans recorded and dropped, no exporter | — | — | — |
| Alerting | Partial: 136 rules in seven files (counted), none on the continuous trial balance, the outbox or HTTP; no routing; `promtool` never run. Since 2026-11-08 (`P15-TSK-024`): 141 rules in eight files, `platform.yml` adding a rule on `up` per instance, the fleet absent and the gauges stale, and its fix round `FxGaugesUnreadable` and `ReconciliationGaugesUnreadable`, every gauge an alert reads now seen unreadable; their own `promtool test` cases run by hand | — | — | — |
| Security | Partial: transport guards off loopback, secrets externalised and confined; an operator's acts recorded as the operator population and every refused step-up recorded, report generation reading as a `SELECT`-only role (`P15-TSK-021`); no image, pod profile, NetworkPolicy, workload identity or secret store *(2026-11-08, `P15-TSK-003`: the image runs as UID 65532 with no shell or package manager, asserted on the built image; still no pod profile, NetworkPolicy, workload identity, secret store or signature; since `P15-TSK-006` the image is scanned in CI, with its findings carried under `THREAT_MODEL.md` section 6 item 12, not yet accepted by the owner)* | — | — | — |
| Resource limits | Missing (the connection budget is a startup guard, not a manifest) | — | — | — |
| Failure handling | Partial: graceful shutdown stated and held on a real context (`ShutdownOrderTest`): readiness refuses, HTTP drains, 32 schedules drain within 10 s + 2 s, consumers acknowledge and leave within 10 s + 2 s, the pool closes last *(`P15-TSK-008`, 2026-11-08; read "graceful shutdown unasserted; schedules' `stop()` does not wait; consumers join for 10 s" until then)*; no `preStop` or grace yet (`-009`); recovery after a kill not rehearsed (`-012`) | — | — | — |
| Recovery | Missing | — | — | — |
| Ownership | Partial: seven alert files cover eight modules (§4); no owner label on an alert, no person or rota | — | — | — |
| Runbooks | Partial: `OPERATIONS_RUNBOOK.md` §1–§10 domain sections; §11 (platform) planned | — | — | — |
| Multi-instance | Leaderless by design; 34 `SmartLifecycle` components (32 schedules, the gauge refresher - accounting's until `P15-TSK-024` made it every gauge's, 2026-11-08 - the inbox consumers), guarded by `NoSingleInstanceAssumptionRulesTest`; the deployed fleet not yet rehearsed | — | — | — |

**Named gaps of `finapp-app`** (open Phase 14 remediation entries; each `PLANNED` with its horizon; none may be read as
met): `P14-REM-001` (a break's resolution under `MULTI_FACTOR`), `P14-REM-002` (step-up on two approvals),
`P14-REM-003` (reason classifications and screens), `P14-REM-004` (the Phase 14 schema closed to raw writers),
`P14-REM-005` (regulatory submission hardening, before any real regulatory channel), `P14-REM-006` (write-offs and the
BNPL fee pin, before production write-offs and BNPL), `P14-REM-007` (a settlement source's retirement),
`P14-REM-008` (close-check robustness), `P14-REM-009` (the lock's grace on the database's clock, Phase 15 at the
latest), `P14-REM-010` (the exit review's accepted owner items), `P14-REM-011` (read surfaces). `-034` re-reads each
entry's status when it rules: an entry still open is a `GAP` of the dimension it touches.

### 3.2 `finapp-migrator`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Missing: migrations run by Gradle and CI's `migrations` job; no image, no Job | — | — | — |
| Health checks | N/A: a Job that exits | — | — | — |
| Logging | Missing | — | — | — |
| Metrics | N/A: a Job that exits | — | — | — |
| Tracing | N/A: a Job that exits | — | — | — |
| Alerting | Missing (a failed or stalled Job alerts nobody) | — | — | — |
| Security | Partial: `finapp_migrator` is `NOSUPERUSER NOCREATEDB NOCREATEROLE`; every one of the 21 migrating modules' builds disables `clean`, validates and refuses out-of-order (counted) | — | — | — |
| Resource limits | Missing | — | — | — |
| Failure handling | Partial: Flyway's schema-history lock serialises two runs; no `lock_timeout` | — | — | — |
| Recovery | Missing: forward-only by design; the fix-forward procedure unwritten | — | — | — |
| Ownership | Missing | — | — | — |
| Runbooks | Missing (migration failure is a planned platform section) | — | — | — |
| Multi-instance | Two Jobs at once serialised by Flyway's lock (unproven on a deployed database) | — | — | — |

**Named gaps of `finapp-migrator`**: `P14-REM-004` and `P14-REM-006` are migrations it will apply; their schemas are
gaps of the module they touch (§4).

### 3.3 PostgreSQL

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Missing: compose only, PostgreSQL 18.6 on loopback | — | — | — |
| Health checks | — | — | — | — |
| Logging | — | — | — | — |
| Metrics | Missing | — | — | — |
| Tracing | — | — | — | — |
| Alerting | Missing | — | — | — |
| Security | Partial: three least-privilege roles - the migrator, the application and report generation's `SELECT`-only `finapp_reporting` (`P15-TSK-021`) - per-table grants in every migration, `verify-full` required off loopback; no encryption at rest beyond the application's field-level ciphers (`SECURITY_ARCHITECTURE.md` §Encryption at rest) | — | — | — |
| Resource limits | Partial: the connection budget guarded by `ConnectionPoolSizingGuard` against a declared instance count | — | — | — |
| Failure handling | — | — | — | — |
| Recovery | Missing: no backup, no restore, no rehearsal | — | — | — |
| Ownership | Missing | — | — | — |
| Runbooks | Missing | — | — | — |
| Multi-instance | Every arbitration of the platform is here (`DISTRIBUTED_EXECUTION.md`); its own availability is Phase 16's | — | — | — |

### 3.4 Kafka

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Missing: compose only, single node, `PLAINTEXT`, replication factor 1. The topics and the broker posture exist as code (`infra/kafka/`, `P15-TSK-026`), applied by no broker yet (`P15-TSK-015`, `-012`) | — | — | — |
| Health checks | — | — | — | — |
| Logging | — | — | — | — |
| Metrics | Missing | — | — | — |
| Tracing | — | — | — | — |
| Alerting | Missing | — | — | — |
| Security | Partial: off loopback the clients require `SASL_SSL` with SCRAM-SHA-512 and the marked local password is refused (`KafkaTransportGuard`); ACLs per principal written in `infra/kafka/topics.yaml`, not yet applied (`P15-TSK-015`); no real TLS handshake run yet (`P15-TSK-012`) (`THREAT_MODEL.md` T9) *(read "`PLAINTEXT` refused off loopback; `SASL_PLAINTEXT` admitted; no SASL or TLS configuration, no ACLs" until 2026-11-08)* | — | — | — |
| Resource limits | — | — | — | — |
| Failure handling | — | — | — | — |
| Recovery | — | — | — | — |
| Ownership | Missing | — | — | — |
| Runbooks | Partial: `OPERATIONS_RUNBOOK.md` §11.6, broker outage and consumer lag, written and not yet rehearsed | — | — | — |
| Multi-instance | Consumer groups per module, manual commit after the inbox record, dedupe window `finapp.inbox.retention` (14 days by default); every topic's retention plus one segment below that window, and the cooperative assignor with bounded session and poll timeouts (`P15-TSK-026`) | — | — | — |

### 3.5 Telemetry

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Local only: Prometheus and Grafana in compose | — | — | — |
| Health checks | Local only | — | — | — |
| Logging | — | — | — | — |
| Metrics | Local only | — | — | — |
| Tracing | Missing: no collector or back end | — | — | — |
| Alerting | Missing: no Alertmanager | — | — | — |
| Security | Missing | — | — | — |
| Resource limits | Missing | — | — | — |
| Failure handling | — | — | — | — |
| Recovery | — | — | — | — |
| Ownership | Missing | — | — | — |
| Runbooks | Missing | — | — | — |
| Multi-instance | — | — | — | — |

### 3.6 Edge

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Deployment | Missing: no ingress | — | — | — |
| Health checks | — | — | — | — |
| Logging | — | — | — | — |
| Metrics | — | — | — | — |
| Tracing | — | — | — | — |
| Alerting | — | — | — | — |
| Security | Missing: no TLS, no allow-list; the application's own port is the boundary | — | — | — |
| Resource limits | — | — | — | — |
| Failure handling | — | — | — | — |
| Recovery | — | — | — | — |
| Ownership | Missing | — | — | — |
| Runbooks | Missing | — | — | — |
| Multi-instance | No affinity by design (sessions in PostgreSQL); unrehearsed | — | — | — |

---

## 4. The modules of `finapp-app`

`PHASE_15_PLAN.md` §4: the bounded contexts keep their **operational ownership** (alerts, runbook sections, readiness
rows) per module, because who is woken for a lending alert is a module's question even when the process is shared. The
unit-level dimensions (deployment, health, logging, tracing, security, limits, failure handling, recovery) are
`finapp-app`'s (§3.1) and are not repeated per module. Each module's table carries the four module-level dimensions and
the module's named gaps. **Today** is §15's reading, with the alert rule files and runbook sections counted here; `—`
where neither has one.

### 4.1 `sharedkernel`

Value types with no runtime state, schedule or route: every row is `N/A` by construction, which `-034` confirms.

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | N/A: no runtime behaviour | — | — | — |
| Alerting | N/A: no runtime behaviour | — | — | — |
| Ownership | N/A: no runtime behaviour | — | — | — |
| Runbooks | N/A: no runtime behaviour | — | — | — |

### 4.2 `platform`

The outbox relay, inbox consumers, idempotency, audit and the provider harness.

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | No rule on the outbox, the inbox or a dead letter (§15; `-025`) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No platform section; dead letters resolved by a manual change (`-028`, `-032`) | — | — | — |

### 4.3 `party`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | — | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | — | — | — | — |

### 4.4 `identity`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.5 `kyc`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.6 `consent`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.7 `ledger`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule, the continuous trial balance included (§15, §11.1) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

**Named gaps**: `P14-REM-003` (reason classification), `P14-REM-004` (raw writers: the rebuild, the journal line),
`P14-REM-010` (A3-F4, A3-F5), `P14-REM-011` (the adjustment read by origin).

### 4.8 `accounts`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | — | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | — | — | — | — |

### 4.9 `transfers`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.10 `paymentmethods`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | — | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | — | — | — | — |

### 4.11 `payments`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.12 `checkout`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule (§15) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | No section | — | — | — |

### 4.13 `merchant`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Dashboard panels (§15) | — | — | — |
| Alerting | No alert rule of its own (§15); `MerchantPayableNegative` is in the BNPL rules | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §8.5 (a negative payable from a BNPL refund) | — | — | — |

**Named gaps**: `P14-REM-006` (the BNPL fee pin's trigger).

### 4.14 `settlement`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/settlement-reconciliation.yml`, 17 rules with reconciliation (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | — | — | — | — |

**Named gaps**: `P14-REM-007` (a source's retirement through a reviewed door).

### 4.15 `reconciliation`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/settlement-reconciliation.yml`, shared with settlement | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §1 (activating a currency's reconciliation terms) | — | — | — |

### 4.16 `fx`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/fx.yml`, 13 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §2, §3, §4, §5 | — | — | — |

### 4.17 `crossborder`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | — | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §3b (activating the corridor policy) | — | — | — |

### 4.18 `credit`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/credit.yml`, 9 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §6 | — | — | — |

### 4.19 `lending`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/lending.yml`, 17 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §7 | — | — | — |

**Named gaps**: `P14-REM-003` (the write-off proposal's reason classification), `P14-REM-006` (write-offs for every
writer).

### 4.20 `creditmath`

Pure calculation with no state, schedule or route: every row is `N/A` by construction, which `-034` confirms.

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | N/A: no runtime state | — | — | — |
| Alerting | N/A: no runtime state | — | — | — |
| Ownership | N/A: no runtime state | — | — | — |
| Runbooks | N/A: no runtime state | — | — | — |

### 4.21 `bnpl`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/bnpl.yml`, 18 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §8 | — | — | — |

**Named gaps**: `P14-REM-006` (write-offs and the fee pin for every writer).

### 4.22 `risk`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | — | — | — | — |
| Alerting | `infra/prometheus/rules/risk.yml`, 30 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §9 | — | — | — |

### 4.23 `accounting`

| Dimension | Today | Ruling | Evidence | Gap and owner |
|---|---|---|---|---|
| Metrics | Its nine gauges read on the instance's gauge thread, the platform's since `P15-TSK-024` made it every gauge's and restartable (2026-11-08; read "the refresher's own thread" until then) | — | — | — |
| Alerting | `infra/prometheus/rules/accounting.yml`, 32 rules (counted) | — | — | — |
| Ownership | — | — | — | — |
| Runbooks | §10 | — | — | — |

**Named gaps**: `P14-REM-001`, `P14-REM-002`, `P14-REM-003`, `P14-REM-004`, `P14-REM-005`, `P14-REM-007` (the
coverage reads of a retired or undeclared source), `P14-REM-008`, `P14-REM-009`, `P14-REM-010`, `P14-REM-011`.

---

## 5. What this standard does not claim

- That any unit or module is ready. No cell is ruled until `-034` rules it.
- That a reading of **Today** is complete. It is the survey's, with the counts this task made. `-034` reads every cell
  again against the code.
- Readiness of a real environment, which this repository cannot test (`PHASE_15_PLAN.md` §1.1).
- Compliance with, or certification under, any standard.
