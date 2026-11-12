# Phase 16 — Scale, Resilience, High Availability and Disaster Recovery

*Written by the Phase 15 → 16 transition (2026-11-12), as documents only: nothing in this plan is implemented until
`P16-TSK-001` lands. Decided in ADR-0126…ADR-0129 (`Proposed`); the exit criteria in `PHASE_GATES.md` §5 Phase 16, made
measurable by this transition; the backlog in [`BACKLOG.md`](BACKLOG.md) §Phase 16. Where this plan and an earlier
document disagree, this plan is right and the earlier document carries a dated note saying so. Where this plan and what a
task measures disagree, the task's record is right and this plan gains an erratum.*

**Status of this document:** the proposed design, drafted from `ROADMAP.md` (Phase 16 — **Scale, Resilience and Disaster
Recovery**: "Load characterisation, degradation modes, backup/restore, DR"), `DELIVERY_PLAN.md` §Phase 16, `PHASE_GATES.md`
§5 Phase 16's six criteria, the owner's transition brief of 2026-11-12 (its Parts 10–28 and 30), the Phase 15 exit review
([`reviews/PHASE_15_REVIEW.md`](reviews/PHASE_15_REVIEW.md)) and the owner's acceptances of it (A–G, 2026-11-12), the inputs
the owner already decided for this phase (`THREAT_MODEL.md` §6 items 18, 32, 33, 42, 43 and 44; §17 of this plan), and the
repository as it stands at master `3d5089d9`. The owner's brief calls the phase "Scale, Resilience, High Availability and
Disaster Recovery"; the roadmap's shorter title stays in the roadmap and the backlog, and the brief's areas are this plan's
scope. The owner decisions C1–C12 (§2.2) are **decided at their recommendations** (the owner, 2026-11-12, "accept recommendations"; read "proposed, awaiting the owner" until then); the first task depends on none of them. The
Phase 15 completion gate (`P15-GATE`) ran beside this initialisation and passed (merged at master `cf6eb4a2`: Phase 15 `COMPLETE`
with its recorded deviations, the multi-instance answer `PASS` as counted at three replicas, ten instances deviation B, closed by
this phase's `P16-TSK-014`); this plan edits no Phase 15 section.

---

## 1. Objective

**Financial correctness under load, failure, partial availability and recovery** — not throughput. Phase 16 shows, by
executed experiments with recorded results, that the platform as Phase 15 deploys it keeps every financial invariant when
it is driven hard, when its parts fail one at a time and together, when only some of it is available, and when it is
rebuilt from backup; and it says, with measurements, how much load the platform carries before a limit, which limit it is,
and what recovering costs in time and in data.

The order of what matters, stated so no task inverts it: **(1)** no unintended duplicate financial effect, no money created
or destroyed unexplained, no history mutated, no evidence lost, under any experiment; **(2)** every failure is detected
(an alert, a refused readiness, a counted refusal) and recoverable by a written procedure; **(3)** the platform stays
available where correctness allows, and refuses openly where it does not; **(4)** throughput and latency, measured and
explained. A faster platform that answers (1) worse is a regression.

**Phase 16 moves no money that earlier phases did not move and adds no business capability** (`DELIVERY_PLAN.md` §Phase 16
§2). Every financial rule, posting treatment, lifecycle and invariant stays as Phases 3–15 built them, with three
exceptions the owner already decided, each a `DOD-FIN` task: the posting scope made unique before any idempotency record is
swept (`THREAT_MODEL.md` §6 item 18, debt row #61, `P16-TSK-011`); load shedding that refuses a money-moving command before
its financial effect, never after (`DELIVERY_PLAN.md` §Phase 16 §7, `P16-TSK-016`); and whatever a hot-row measurement
proves necessary, decided by an ADR before it is built (§6.4).

### 1.1 The honest environment boundary, recorded up front

**The project has no cloud account.** Phase 15 wrote Terraform for one reference cloud and never applied it
(`infra/terraform/`, ADR-0121), and every cluster rehearsal ran on a local `kind` cluster of one node
(`infra/scripts/kind-rehearsal/kind-cluster.yaml`) on one Windows development machine with Docker Desktop
(`OPERATIONS_RUNBOOK.md` §11.28's last record). Phase 16 inherits exactly that. So every criterion of this phase is one of
two kinds, and the backlog and the gate say which:

**Executable here** — run on the development machine, with a dated record of commands, numbers and the financial census:

- a load test of the deployed platform on `kind` through its real edge, and of its parts in containers (§5);
- ten application replicas at load on `kind`, scaled up and down and rolled during load (§13, scenario 14);
- pod-level and container-level failures: a crash, several at once, an eviction, a kill during a rollout; a **node** failure
  on a multi-node `kind` cluster (several node containers on the one machine — a node is a container, not a machine);
- network faults injected between the application and its dependencies (latency, loss, partition, DNS failure) (§9);
- a database failover between a primary and a **local synchronous standby** run as a test-only pair in the `kind` overlay,
  under load, its interruption measured (§10, `P16-TSK-022`);
- a broker failure in a test-only **three-broker** KRaft cluster on `kind`, replication 3, `min.insync.replicas` 2 (§7);
- a logical restore and a **point-in-time restore from archived WAL** of a local PostgreSQL, ending in the platform's own
  financial verification (§11);
- a disaster-recovery exercise that destroys the `kind` cluster and its data and rebuilds the platform from the repository,
  a backup and regenerated test secrets (§11);
- Terraform variants for multi-AZ and multi-region, checked with `terraform test` and mock providers and never applied (§12).

**Designed only, left to a real environment** — written, reviewed and, where a mock provider allows, tested as
configuration; **never claimed as executed**:

- a managed database's Multi-AZ failover (RDS), its endpoint move and its real interruption; a managed point-in-time
  restore; a cross-account or cross-region backup copy restored (`THREAT_MODEL.md` §6 items 21 (a) and 38);
- an availability zone's loss, a region's loss and a regional failover; cross-region replication and its lag;
- a managed broker's (MSK) broker or zone failover;
- a real provider's outage, slowness or late answer (every provider is simulated: `ROADMAP.md` §Non-Goals);
- real DDoS protection, a WAF and a connection limit in front of the edge (item 32 (b));
- KMS key availability across regions and a real secret store's recovery;
- production RTO and RPO **as measured numbers** — the local drills measure the procedure's own durations at the local
  data size, which are evidence about the procedure, not about a managed service.

**Requiring executed evidence for every mandatory criterion** (the owner's Part 28) therefore means: a criterion executable
here is met only by its executed record; a criterion that needs a real environment cannot be met in this repository, and it
is listed by name in `PHASE_GATES.md` §5 Phase 16 as **designed-only**, for the owner to accept as designed-only or to hold
open (C4). No document of this phase calls the platform ready for production, and none claims compliance with, or
certification or attestation under, any standard.

**Safety boundary, for every experiment.** Every load test and failure experiment runs only against a `kind` cluster or a
container stack the run itself created, with generated throwaway credentials and simulated providers, holding no real
customer, no real money and no real provider connection. The harness refuses to run against any other Kubernetes context
(§9.1). The owner's Part 16 rule — never a destructive experiment against real financial systems or production data without
safeguards and authorisation — is met by there being no such system to reach.

### 1.2 Scope

The owner's areas: workload and capacity (§4), load and stress testing (§5), database scale and concurrency (§6), Kafka and
the event pipeline (§7), resilience patterns (§8), failure injection (§9), high availability (§10), disaster recovery (§11),
the multi-region assessment (§12), financial correctness under failure (§13), capacity and scaling strategy (§14), SLO, SLI
and error-budget validation (§15), the distributed-system audit (§16), security under scale and failure (§18), the testing
strategy (§19); and the inputs earlier phases and the owner assigned here (§17).

### 1.3 Out of scope

A business capability; a real environment (an apply, a managed failover, a real provider); active-active multi-region writes
(§12); a microservice extraction **built** in this phase (the evaluation is in scope, `P16-TSK-030`; an extraction would be a
programme of its own after an ADR); Redis or any cache as a new dependency (§3: no code uses one); a runtime schema registry
(C11 of Phase 15 stands); the purge of financial evidence, agreements or regulatory reports (Phase 15's C12 stands); the
items whose horizon is "before production use" or "the first real environment" and not this phase (§17.3).

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 Inherited, binding

| Decision | Where | Consequence here |
|---|---|---|
| One modular monolith, one deployable plus a migrator | ADR-0001, `settings.gradle.kts`, `PHASE_15_PLAN.md` §4 and §26 | Load and failure are characterised for the units that exist — `finapp-app`, `finapp-migrator`, PostgreSQL, Kafka, the edge, the telemetry stack; an extraction is evaluated by measurement (ADR-0001's revisit point), never assumed |
| Every replica runs every role; correctness is leaderless and lives in PostgreSQL | ADR-0014, ADR-0024, `DISTRIBUTED_EXECUTION.md` §3, `NoSingleInstanceAssumptionRulesTest` | Scaling out adds HTTP capacity **and** schedule and consumer competitors; ten instances is a correctness test before it is a capacity test |
| The connection budget is a startup guard counting terminating pods | `DISTRIBUTED_EXECUTION.md` §4a, `ConnectionPoolSizingGuard`, `deploy/base/horizontalpodautoscaler.yaml` | Today `maxReplicas` 7 + 2 × `maxSurge` 1 + 1 = 10 = `FINAPP_DB_INSTANCES`, pool 7 + 1 reporting, `server-max-connections` 100 less 12 (`application.yaml`): **ten replicas do not fit today's budget** (§6.2, C3) |
| Balance projection updated in the posting transaction; hot rows accepted and ordered | ADR-0009, ADR-0041, ADR-0076 §9, `DISTRIBUTED_EXECUTION.md` §3 (the multi-entry lock-order rule) | Hot accounts are a measured contention, mitigated by sub-accounts by purpose (ADR-0041) only after measurement and an ADR (§6.4) |
| Kafka and projections are not financial truth; the outbox is the source | `CLAUDE.md` rule 12, ADR-0005, ADR-0122 | A lost broker loses no fact; recovery replays from the outbox within the inbox's dedupe window (14 days, `finapp.inbox.retention`) |
| Liveness is the process; readiness includes the database and the schema | ADR-0016, `HealthEndpointTest#readinessReflectsTheDatabase` | A dependency outage makes replicas unready, never restarts them (`OPERATIONS_RUNBOOK.md` §11.40) |
| No money moves on a restored database until the verification passes, enforced by the database | `OPERATIONS_RUNBOOK.md` §11.35, `infra/scripts/restore-rehearsal.sh`, `RestoreRehearsalScriptTest` | Phase 16's DR gate is this rule, extended to a whole-environment rebuild (§11) |
| SLOs budget availability; correctness signals page on first sample, never budgeted | ADR-0124, `docs/architecture/SLOS.md` | Load and failure experiments read both; a correctness page during any experiment fails it (§15) |
| Every tool pinned by digest, outside the build graph | ADR-0025, ADR-0026, `infra/scanner-pins.sh` | The load generator and the fault injector are pinned images, not build dependencies (C5, ADR-0126, ADR-0127) |
| The criterion-7 deviation and the owner's postponements | `POSTPONED_ISSUES.md` PI-004, PI-023, PI-024; the owner, 2026-11-12: Phase 16 keeps skipping the database and Kafka tiers per task | Per task, named guards and the touched classes' tests; Phase 16's experiments run their own stack (C2) |

### 2.2 Owner decisions — C1–C12, decided at their recommendations (the owner, 2026-11-12)

*(Recorded by the Phase 15 → 16 transition, 2026-11-12. Each is a question, its options and a recommendation; the owner
decides after the merge. The first task depends on none of them. A task whose design needs one names it.)*

| # | Decision | Options | Recommended | Decided |
|---|---|---|---|---|
| C1 | **The storm question** (PI-004) | (A) Phase 16's load tests, failure experiments and drills are its substance and are **not** postponed; `P16-TST-001` is the combined fault-injected soak, run in the phase; no new in-process JUnit storm is written for Phase 16; (B) `P16-TST-001` written as an in-process own-container storm in PI-004's sense and postponed with the others; (C) postpone the experiments too | **(A).** Every task of M16.2–M16.12 and `P16-TST-001` is Phase 16's own evidence and **must not be postponed**: postponing them leaves nothing of the phase. Only the in-process JUnit storms of Phases 13–15 (`P13-TST-001`'s residue, `P14-TST-001`, `P15-TST-001`) stay PI-004's. Two consequences for the owner: `-014`'s counted run at ten closes deviation B, as the Phase 15 completion gate ruled (its multi-instance answer `PASS` as counted at three replicas, ten instances deviation B, closed by Phase 16's ten-instance load task); and `P15-TST-001`, the rolling-deploy storm, overlaps `-014`'s rolling deploy at ten — the owner may keep it under PI-004 or close it on `-014`'s evidence at the Phase 16 exit | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C2 | **The database-tier question** | (A) The experiments run **their own stack** — the `kind` cluster or a container stack each run creates, real PostgreSQL and Kafka inside it — recorded as rehearsal records with artefacts; the Gradle fleet tiers (`databaseTest`, `kafkaTest`, own-container) stay skipped per task as the owner ruled; a database-tier test a Phase 16 task writes is owed to PI-023's pass; (B) also run the fleet tiers per task; (C) run neither, design only | **(A).** It keeps the owner's ruling and gives the phase real infrastructure. The line is: a **Gradle** database or Kafka tier never runs per task; an **experiment** always runs on its own real stack, which is what the experiment is | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C3 | **The connection budget at ten replicas** | (A) Measure first (`-009`): pool utilisation, wait and the server's active backends at load; for the `kind` drill raise the test-only server's `max_connections` and `FINAPP_DB_INSTANCES` to the formula (10 + 2 × 1 + 1 = 13 instances, 13 × 8 = 104, plus 12 reserved ⇒ ≥ 116); for staging and production decide by measurement between a larger server (`max_connections` and instance class in Terraform), a smaller pool, or a transaction-mode pooler; (B) shrink the pool to fit ten now; (C) PgBouncer now | **(A).** A pool sized without load is the guess `application.yaml` warns against; the pooler's open question (server-side prepared statements, `PHASE_15_PLAN.md` §12) is a measurement. The production `maxReplicas` stays 7 until `-009` decides | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C4 | **Designed-only criteria** that need a real environment (§1.1's second list) | (A) Accept each as designed-only, with its local analogue executed and recorded, each listed at the exit as a recorded deviation and never claimed; (B) hold Phase 16 open until a real environment exists; (C) drop them | **(A).** The local analogues exercise the platform's half (the application's behaviour across a failover, a restore's verification, a broker's loss) with real software; the managed services' half is the provider's and needs an account the project does not have | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C5 | **The load tool** | (A) **k6**, pinned by digest in `infra/scanner-pins.sh` like every other tool, run as a container on the `kind` network against the real edge, open-model arrival-rate executors, its summary and time series written to the run's artefacts; (B) Gatling's Java DSL through its Gradle plugin; (C) grow the rehearsal's JDK driver (`infra/scripts/kind-rehearsal/Rehearsal.java`) into a load tool | **(A).** The existing stack's convention for operational tools is a digest-pinned image outside the build graph (ADR-0025, ADR-0026; Trivy, Semgrep, kubeconform, conftest, Terraform); k6 adds one image and no dependency to the application's lockfiles or `gradle/verification-metadata.xml`, and its arrival-rate executors avoid coordinated omission. Gatling matches the language but brings a Scala and Netty tree into the verified build graph for a tool the application never runs. A home-grown generator would have to solve coordinated omission and percentile recording itself. `Rehearsal.java` stays the flows' setup (customers, sessions, signed callbacks) and the in-process JUnit storms stay the database-contention tier (ADR-0126) | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C6 | **The fault injector** | (A) Plain primitives (`kubectl delete`/`evict`, `docker stop`/`pause` of a `kind` node, scaling to zero, a NetworkPolicy for a partition) plus **Toxiproxy**, pinned by digest, between the application and PostgreSQL, Kafka and the provider simulator for latency, loss, bandwidth and resets; (B) Chaos Mesh; (C) LitmusChaos | **(A).** Each experiment stays a reviewed script with a stop condition, nothing privileged is installed cluster-wide, and the supply chain grows by one image. Chaos Mesh needs a privileged DaemonSet and CRDs to do what the primitives already do on `kind` (ADR-0127) | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C7 | **The local database failover pair** | (A) A test-only primary and synchronous standby by PostgreSQL's own streaming replication in the `kind` overlay (`synchronous_standby_names` set), failover by promoting the standby and moving the Service's selector — the shape of RDS Multi-AZ (a synchronous standby, the endpoint moves); (B) CloudNativePG in the `kind` overlay; (C) Patroni | **(A)**, test-only, nothing added to the deployed overlays: it models what the reference cloud does (C3 of Phase 15 keeps the managed database) with no operator in the supply chain. (B) is the credible alternative if the owner later wants an in-cluster database (`PHASE_15_PLAN.md` §2.2 C3 named it) | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C8 | **The reference production workload** (§4.2's assumptions W1–W8) | (A) Adopt W1–W8 as the proposed production target, every number traceable to its assumption, the owner free to change any; (B) the owner supplies other volumes; (C) no production target, only the measured engineering limits | **(A)**, explicitly a reference: a launch-scale e-money and payments institution. Without a stated target the phase can report limits but not whether they suffice; a target the owner never chose would be invented, which the brief forbids — hence a decision | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C9 | **Recovery objectives** (§11.2) | (A) ADR-0128's tiers: an in-region database failure RPO 0, RTO 15 minutes to money movement; a logical corruption or deletion recovered by point-in-time restore, RPO 5 minutes, RTO 4 hours with the full verification; the production account lost, recovered from the dedicated backup account's daily copy, RPO 24 hours, RTO 24 hours; a region lost, the same as the account until cross-region replication is bought; (B) tighter (cross-region replication now, RPO minutes for a region); (C) looser | **(A)**, each proposed from criticality, financial risk, cost and what the decided architecture can do (§11.2 shows the basis per tier); tightening the region tier is a cost the owner buys when a real environment exists (item 21 (a)) | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C10 | **The multi-region stance** (§12) | (A) One region, multi-AZ, active-passive for the financial record; another region only as a recovery site from backups (warm standby an option the owner may buy); **no active-active writes**; (B) active-passive with a replicated warm standby now; (C) active-active | **(A)** (ADR-0129). The ledger has one authoritative writer; active-active writes to it would need conflict resolution on money, which the platform refuses. (B) changes the region tier of C9 and costs a second environment | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C11 | **PI-023 and PI-024 as prerequisites** (§19.3) | (A) A narrow pre-flight before the ten-instance drill: the `databaseTest` and `kafkaTest` suites of `platform`, `ledger`, `transfers` and `payments` and the Phase 15 arbiters' ten-way cases run once (`-014`'s first step); the `incidents` steps of PI-024 that Phase 16's experiments repeat (the broker outage, the database restart, one instance down, the poisoned row) run as those experiments, closing those parts of PI-024; the rest of PI-023 and PI-024 stays postponed; (B) keep both postponed whole; the exit says the load census is black-box evidence over arbiters whose own cases never ran; (C) run PI-023 whole first | **(A).** A load result that rests on arbiters whose ten-way cases never ran could be built on a defect those cases would show; the pre-flight is bounded to what the drill exercises. (B) is the owner's standing choice and stays honest if recorded | **(A)**, the owner, 2026-11-12 ("accept recommendations") |
| C12 | **Automatic rail availability** (`DECISIONS.md` §Deliberately Deferred, re-dated to Phase 16) | (A) Measure failure rates per rail in `-018`'s provider experiments and write the decision: automation, if built, writes the same audited availability fact an operator writes, under four eyes or a bounded automatic suspension an operator confirms; (B) build it now; (C) keep it manual and re-date it | **(A)**: decide on measured failure rates, as the row says; building it is `-018`'s only if the measurement and the owner's word on the ADR support it | **(A)**, the owner, 2026-11-12 ("accept recommendations") |

**Not decisions** (already decided, carried without a question): item 43 (b), (c)'s quota, (f) and (g); item 44 (a) and (b);
acceptance B (ten instances at load); `P15-REM-004` in the first wave; item 18's three tables in Phase 16; item 42 (a), (h)
and (i) in Phase 16. Their placement is §17.

---

## 3. What exists (the existing infrastructure as it really is)

Read at master `3d5089d9`. **Implemented** means built and guarded; **Rehearsed** means run on `kind` or containers with a
recorded result; **Planned** means written and never applied; **Absent** means nothing exists.

| Component | Status | Evidence | Phase 16 |
|---|---|---|---|
| Deployable units | Implemented | `finapp-app` (one image, every role per replica), `finapp-migrator` (a Job per release); `Dockerfile`, `migrator/` | Load and failure characterised per unit |
| Replica count and scaling | Implemented | HPA `minReplicas` 3, `maxReplicas` 7, on CPU (`deploy/base/horizontalpodautoscaler.yaml`); `maxSurge` 1, `maxUnavailable` 0, `minReadySeconds` and grace 117 s (`deploy/base/deployment.yaml`); PDB `maxUnavailable` 1 (`deploy/base/poddisruptionbudget.yaml`); topology spread by zone (`DoNotSchedule`) and host (`ScheduleAnyway`) | HPA calibrated from load (`-015`); ten replicas need C3 |
| Resources | Implemented | Requests 500m / 2Gi, limits 2 CPU / 2Gi per replica; the namespace quota sized from the budget (`deploy/base/resourcequota.yaml`) | Measured against load; quotas for the edge and observability namespaces (`-004`) |
| Connection budget | Implemented | Pool 7 fixed-size + reporting 1, `FINAPP_DB_INSTANCES` 10 (base, production), 7 (staging), 6 (`kind`, server `max_connections=60`), `server-max-connections` 100 (`application.yaml`, `deploy/overlays/*/kustomization.yaml`, `deploy/overlays/kind/postgres.yaml`); `ConnectionPoolSizingGuard`; the peak measured on `kind` (§11.28's last record) | C3, `-009` |
| `kind` harness | Rehearsed | `infra/scripts/kind-rehearsal.sh` (scopes `release`, `rotations`, `signals`, `incidents`, `delivery`), `infra/scripts/kind-rehearsal/` (`Rehearsal.java` the HTTP workload, `census.sql` the financial census, `platform.sh`, `delivery.sh`, `telemetry.sh`); **one node** (`kind-cluster.yaml`); the `release` scope green on 2026-11-09 (75 assertions, `OPERATIONS_RUNBOOK.md` §11.28); `incidents` never run (PI-024) | The base every experiment extends (§9.1) |
| PostgreSQL on `kind` | Rehearsed (test-only) | One StatefulSet replica, data in an `emptyDir` (`deploy/overlays/kind/postgres.yaml`) | A synchronous standby pair, test-only (C7, `-022`) |
| PostgreSQL in the cloud | Planned | RDS, `multi_az = true`, encrypted, deletion protection, backup retention 7–35 days, a daily copy to a vault in another account or region, Performance Insights (`infra/terraform/modules/postgres/main.tf`, `variables.tf`) — never applied | Designed-only failover (C4) |
| Kafka on `kind` | Rehearsed (test-only) | One broker, RF 1 (`deploy/overlays/kind/kafka.yaml`, `infra/kafka/topics.yaml`'s note) | Three brokers, RF 3, min ISR 2, test-only (`-013`) |
| Kafka topics | Implemented | 12 partitions, RF 3, `min.insync.replicas` 2, retention below the inbox's dedupe (`infra/kafka/topics.yaml`; `P15-TSK-026`); MSK of three brokers (`infra/terraform/modules/kafka/main.tf`) never applied | Partition and key review at load (`-013`) |
| Redis | **Absent** | `compose.yaml` runs a Redis "local only: no code uses it and none is deployed"; no client library in `gradle/libs.versions.toml`; `TransportSecurityGuard`'s note; ADR-0030 put sessions in PostgreSQL | **No Redis work** (the local copy's removal or relabelling is `P15-REM-006`'s). The owner's "Redis latency", "Redis failure", "cache throughput" and the gate's "cache loss" are answered as N/A with this reason; the only caches are per-instance and non-authoritative, lost with a pod (§9's E03) |
| Object storage | Planned (infrastructure only) | Buckets for state and archives (`infra/terraform/modules/object-storage/`); the application uses none (ADR-0036) | Recovery of infrastructure objects only (§11) |
| Edge | Rehearsed | Two Traefik controllers per edge, public and operator (`deploy/edge/base/controllers.yaml`, `replicas: 2`); route lists generated (`P15-TSK-011`); a controller lost under traffic never rehearsed; never rolled (item 43 (f)) | `-005`, `-020` |
| Telemetry | Rehearsed | Two collectors (`deploy/observability/base/collector-workload.yaml`); Prometheus, Alertmanager, Tempo, Loki one each in the `kind` overlay (`deploy/observability/overlays/kind/backends.yaml`); 13 rule files (`infra/prometheus/rules/`); SLOs (`docs/architecture/SLOS.md`); the edge, the migrator and the stack's own lines unshipped and unalerted (item 43 (b)) | `-003`; saturation signals for load (`-006`) |
| Resilience mechanisms | Implemented, hand-built | **No resilience library** (no Resilience4j in `gradle/libs.versions.toml`). Per-adapter `HttpClient` connect timeouts (`PspWireClient`, `SchemeWireClient`, the credit, FX, KYC and payout adapters); unknown outcomes swept by inquiry (`PaymentSweeperDatabaseTest#indeterminateMarksUnknownOnceAndNeverFails`); outbox retry with the relay's own backoff (`OutboxRelaySchedule`); inbox failure backoff (`InboxConsumers`); a per-instance bulkhead on hinted inquiries (`CallbackInquiryBulkhead`); risk's read budget with a recorded fail-safe (`EvaluationBudget`, ADR-0108); cluster-wide abuse limits in PostgreSQL (`WindowCounter`, `P15-TSK-022`); the request size bound (`RequestSizeLimitFilter`, 1 MiB) | Inventoried and validated (§8); load shedding added (`-016`) |
| Backup and restore | Rehearsed (logical, containers) | `infra/scripts/restore-rehearsal.sh`, `RestoreVerification`, `RestoreRehearsalScriptTest`; the first rule (no money before verification) enforced by role logins (`OPERATIONS_RUNBOOK.md` §11.35); its lending and BNPL proofs judged no subject (item 44 (a)); no WAL archiving or point-in-time restore anywhere local | `-024` |
| Database failover | Procedure only | `OPERATIONS_RUNBOOK.md` §11.40: "the failover drill is Phase 16's"; no socket timeout on the data source (`application.yaml`, item 42 (h)) | `-022` |
| Load and stress tooling | Absent (HTTP); Implemented (in-process) | No HTTP load tool. In-process storms exist, own-container tier, each with its census: `BnplStormDatabaseTest`, `LendingStormDatabaseTest`, `SettlementReconciliationStormDatabaseTest`, `MultiRailConservationStormDatabaseTest`, `MerchantConservationStormDatabaseTest`, `FxCrossBorderStormDatabaseTest`, `CreditDecisionStormDatabaseTest`, `RiskStormTest`; `SustainedConcurrentPostingDatabaseTest` (`INV-BAL-02` under sustained posting) | k6 (C5, `-006`); the storms stay PI-004's |
| Fault injection | Absent | Only the rehearsal's own steps (a pod kill, a database restart, a broker scaled to zero — the last two never run, PI-024) | Toxiproxy and primitives (C6, `-019`) |
| Multi-region | Absent | ADR-0121's follow-up: "Phase 16 adds the multi-AZ and multi-region variants and cross-region backup" | `-026`, designed-only |

---

## 4. Workload models and capacity (Part 11)

### 4.1 Three workloads, never confused

| Workload | What it is | Where its numbers come from | What it proves |
|---|---|---|---|
| **Demonstration** | A small, fixed mix (§4.3's flows at 5 money movements a second for 10 minutes, reads ten times that) | Chosen to be carried by one development machine's `kind` with margin | The invariants hold **during** load, sampled every 30 s (§5.3); the harness works end to end |
| **Engineering test** | The highest sustained mix the reference machine's `kind` deployment carries within the latency objectives, found by stepping the arrival rate | **Measured** by `-007`, never chosen in advance | The platform's own bottleneck on this machine and its ordering (which resource saturates first), the ratio of the production target to what was carried |
| **Proposed production target** | A launch-scale reference institution's traffic, from W1–W8 below | **Derived from stated assumptions** (C8), each number traceable | What a real environment must carry; never claimed as carried here, since one development machine is not a production fleet |

A result is always reported with its workload's name. A number measured on `kind` is never presented as the production
capacity, and the production target is never presented as measured.

### 4.2 The assumptions (W1–W8, the owner's to change: C8)

- **W1** 1,000,000 registered customers; 20% active on a given day (200,000).
- **W2** The busiest hour carries 10% of a day's volume; a burst is three times the busiest hour's rate for 60 seconds (a
  payday, a merchant's sale); a spike is ten times it for 10 seconds.
- **W3** Per active customer per day: 0.5 internal transfers, 1 card or checkout payment, 0.2 wallet top-ups or withdrawals,
  10 balance or account reads, 1.2 sign-ins.
- **W4** 2,000 merchants; two provider callbacks per payment; merchant API reads twice the merchant's payments.
- **W5** 20 settlement sources, four files a day each, 200,000 lines a day in all; one reconciliation run per file.
- **W6** 5,000 credit decisions a day; 50,000 active loans with 2,000 repayments a day on average and 30% of a month's dues on
  its first day; 20,000 BNPL purchases and 15,000 instalments a day.
- **W7** Each money movement writes four outbox events on average (read from the catalogues, `P15-TSK-027`, by `-001`).
- **W8** Payloads: commands under 4 KiB; responses under 16 KiB; settlement files up to the platform's file bound; the request
  bound 1 MiB (`RequestSizeLimitFilter`).

### 4.3 Per operation (the proposed production target, from W1–W8)

Rates are per second; *average* is the day over 86,400 s; *peak* the busiest hour (W2); *burst* three times peak. Latency
and error targets are `SLOS.md`'s where an objective exists, and otherwise proposed here, to be validated by `-029`.
**Resource consumption is not stated: it is measured** by `-007` (CPU, memory, GC, threads, pool, database time per
operation) and written into `CAPACITY_MODEL.md` (`-001` creates it).

| Operation | Day | Average | Peak | Burst | Concurrent clients at peak | Latency target | Error target | Scaling |
|---|---|---|---|---|---|---|---|---|
| Internal transfer | 100,000 | 1.2 | 2.8 | 8.3 | ≈ 50 | 99% ≤ 1 s (`transfer_latency`) | non-5xx 99.9% | Horizontal; serialised per wallet pair by design |
| Card / checkout payment | 200,000 | 2.3 | 5.6 | 16.7 | ≈ 100 | p99 ≤ 2 s incl. the simulated provider (proposed) | non-5xx 99.9% (`payment_checkout`) | Horizontal; provider-bound |
| Wallet top-up / withdrawal | 40,000 | 0.5 | 1.1 | 3.3 | ≈ 20 | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal |
| Account / balance read | 2,000,000 | 23 | 56 | 167 | ≈ 500 | p99 ≤ 300 ms (proposed) | non-5xx 99.9% | Horizontal; reads of the projection (ADR-0041), never a replica for a decision (§6.6) |
| Sign-in | 240,000 | 2.8 | 6.7 | 20 | ≈ 100 | 95% ≤ 1 s (`authentication_latency`, the hash included) | non-5xx 99.9% | Horizontal; CPU-bound by the credential hash |
| Provider callbacks (webhooks) | 400,000 | 4.6 | 11 | 33 | provider-driven | accepted or deduplicated 99.95% (`provider_callbacks`) | as left | Horizontal; inquiries bounded by `CallbackInquiryBulkhead` |
| Merchant API | 400,000 | 4.6 | 11 | 33 | ≈ 200 | p99 ≤ 1 s (proposed) | non-5xx 99.9% (`merchant_api`) | Horizontal |
| Settlement ingestion | 80 files, 200,000 lines | — | one file per source at once | 20 files at once | 20 sources | a file accepted within 15 min of arrival (proposed) | no file lost; a refused file is evidence | Leaderless per file (`FOR UPDATE SKIP LOCKED`); one acceptance per source at a time (`settlement.source FOR UPDATE`) |
| Reconciliation | 80 runs | — | 20 runs at once | — | — | a run complete within 30 min of its file (proposed) | no run abandoned | Chunked, resumable by any instance |
| Credit decision | 5,000 | 0.06 | 0.14 | 0.4 | ≈ 5 | p99 ≤ 3 s (proposed) | non-5xx 99.9% | Horizontal; bureau-bound |
| Lending repayment | 2,000 (month start 15,000) | 0.02 | 0.4 (month start) | 1.2 | ≈ 10 | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal; per-loan serialisation |
| BNPL purchase / instalment | 35,000 | 0.4 | 1 | 3 | ≈ 20 | p99 ≤ 2 s (proposed) | non-5xx 99.9% | Horizontal; `LENDING_CAPITAL` a per-currency hot row (§6.4) |
| Events produced (outbox) | ≈ 1,400,000 | 16 | 39 | 117 | — | published within 60 s, 99% of minutes (`event_freshness`) | no dead letter | Relay on every instance, advisory lock per aggregate |
| Events consumed | per group, the same | — | — | — | — | lag under 1,000 records, 99% of minutes per group (`consumption`) | no parked record | ≤ 12 consumers per group (12 partitions) |
| Ledger postings | ≈ 600,000 entries | 7 | 17 | 50 | — | the posting's share of its command's latency, measured (`-007`) | 0 refused for a platform fault | One `INSERT` path; hot rows ordered (§6.4) |
| Database | — | — | — | — | ≤ the pool budget | statement p99 per top statement, measured | 0 deadlocks unexplained | Vertical for writes; §6 |
| Cache | — | — | — | — | — | — | — | **N/A**: no cache exists (§3) |

The burst column is what the **spike** and **burst** profiles of §5.1 drive; the peak column is the **peak** profile; the
average the **normal** profile. The proposed production peak is modest — about 30 money movements and 60 reads a second
across the platform — and the honest finding to expect is that a development machine's `kind` may carry it while a single
node's CPU, not the architecture, is the first limit; `-007` measures and reports whichever it is.

---

## 5. Load and stress testing (Part 12)

### 5.1 The profiles

| Profile | Shape | Duration | Purpose |
|---|---|---|---|
| Normal | Average rate, the §4.3 mix | 30 min | Baseline latency and resource per operation |
| Sustained | Peak rate, constant | 2 h | Steady-state saturation, pool and lock behaviour, outbox and consumer lag at peak |
| Peak | Step to peak from normal | 30 min | HPA reaction; readiness under a step |
| Burst | Three times peak for 60 s, repeated every 10 min | 1 h | Queueing and recovery; whether shedding (`-016`) refuses before the financial effect |
| Stress | Arrival rate stepped up by 25% every 5 min until the latency objective or an error target breaks | to the break | The engineering test workload: the first limit and its resource |
| Spike | Ten times peak for 10 s | 3 spikes | Admission, shedding and recovery without a restart |
| Soak / endurance | Peak rate | 8 h (the reference machine's realistic bound; longer runs are a real environment's) | Leaks, table and index growth, vacuum and WAL behaviour, retention sweeps keeping up |
| Concurrent financial | Many clients on **few** accounts: N payers to one merchant, N transfers between one pair, many repayments of one loan, many purchases in one currency | 15 min each | Hot rows, lock waits, deadlocks, idempotency under same-key retries |

Every profile also runs with **same-key retries**: a fraction of commands (5%) is retried with the same `Idempotency-Key`,
some before and some after the first answer, so that "the client retries" is part of every load and the census can count
one effect per key.

### 5.2 What is measured

Throughput and the latency distribution (p50, p95, p99, max) per operation from the load generator's side (open model, so
the client's own queueing is counted); error and timeout rates by status; from the platform: CPU, memory, GC pause time and
frequency, live threads and Tomcat's busy threads, Hikari's active, idle and pending connections and wait time
(`ConnectionPoolSaturated`, `ConnectionPoolStarved` already exist, `OPERATIONS_RUNBOOK.md` §11.21), the outbox's oldest
publishable age, consumer lag per group (`finapp.inbox.lag`), the provider simulator's latency; from PostgreSQL, sampled
by the harness as SQL every 10 s into the run's artefacts: `pg_stat_activity` by state and wait event, `pg_locks` waits,
`pg_stat_statements` (enabled in the test-only server) for the top statements' time, `pg_stat_database` deadlocks and
temporary files, `pg_stat_bgwriter`/`pg_stat_checkpointer` and WAL volume, autovacuum activity on the hottest tables.
**Redis latency: N/A** (§3).

### 5.3 Invariants **during** load, not only after

`PHASE_GATES.md` §5 Phase 16's first criterion. The harness reads the platform's own checks while the load runs, every 30
seconds, through what already exists: the continuous trial balance (`finapp.ledger.trial.balance`, `INV-ACC-01`, paging as
`LedgerTrialBalanceNonZero`), projection drift (`LedgerProjectionDrift`), GL = ledger (`GlNotEqualLedger`), dead letters
(`OutboxDeadLetter`, `InboxDeadLetterParked`), the source-join census (`JournalEntryUnsourced`) — every correctness page of
`SLOS.md` §3 — and, from the database, `infra/scripts/kind-rehearsal/census.sql`'s clauses that read committed facts only
(one effect per idempotency key, trial balance zero per currency), run in a `REPEATABLE READ READ ONLY` snapshot so they see
a consistent cut. A correctness page or a census clause failing **at any sample** fails the run, whatever the latency did.
After load: the full census, GL = ledger for every locked period, every product proof, the outbox drained.

### 5.4 Finding bottlenecks rather than adding resources

Every stress run ends in a named first limit, read from the saturation signals above in a fixed order (USE: utilisation,
saturation, errors per resource): the generator, the edge, the application's CPU, its threads, its pool, the database's CPU,
its locks, its I/O, the broker. A limit is fixed by the cheapest change that removes it, and **only after** the measurement
names it; raising a resource limit is a finding to record, not a fix. A limit that comes from the architecture rather than
the infrastructure (a hot row, a per-source lock, a whole-day read) is listed for §14.3 and, where it matters at the
production target, becomes an ADR before any change.

---

## 6. Database scale and concurrency (Part 13)

### 6.1 Connection pooling and transaction duration

Hikari per instance, fixed-size (7 + 1 reporting), `connectTimeout` 5 s, no `socketTimeout` (`application.yaml`). `-009`
measures, at the sustained and burst profiles: pending connections and wait per instance, the share of time a connection is
in a transaction, transaction duration percentiles per top statement, idle-in-transaction time. Transaction duration is a
correctness concern here as well as a capacity one, since several arbiters hold a row lock for a transaction's length
(`settlement.source FOR UPDATE` for a whole acceptance, debt row "Acceptance holds `settlement.source`"; the capital row
across BNPL's waits, debt row "L6 is held across (P) and L8 waits").

### 6.2 The connection budget at ten

Ten replicas do not fit today's numbers (the Phase 15 completion gate's `P15-REM-007`, which also found that the HPA has never
scaled: `kind` has no metrics source, so `-009` installs one, test-only): `maxReplicas + 2 × maxSurge + 1 ≤ FINAPP_DB_INSTANCES` with ten replicas needs 13
instances, and 13 × (7 + 1) = 104 > 88 usable connections. The `kind` drill raises the test-only server's `max_connections`
and the overlay's `FINAPP_DB_INSTANCES` to the formula (C3); the production decision is `-009`'s by measurement: a larger
server, a smaller pool, or a transaction-mode pooler — the last needing the server-side prepared statements question
answered (`PHASE_15_PLAN.md` §12; only transaction-scoped advisory locks and settings exist, which a pooler preserves).
`ConnectionPoolSizingGuard` keeps holding whatever is decided; the peak `pg_stat_activity` count for
`application_name = 'finapp'` is measured at ten during a rollout, as `P15-TSK-012` measured it at three.

### 6.3 Isolation, locks and deadlocks

Postings are inserts under `READ COMMITTED` that lock no account (ADR-0039); the balance projection's rows are locked in
account order inside the posting's transaction (ADR-0041; `PostingService.lockBalancesInOrder` and the multi-entry lock-order rule
in `DISTRIBUTED_EXECUTION.md` §3). Under the
concurrent-financial profile `-008` counts deadlocks (`pg_stat_database.deadlocks`) and serialisation failures and reads
each one's statements from the server log: every deadlock must be explained by a known path that rolls back whole and
converges (the debt row "The idempotency claim row is locked first in Tx1 and last in Tx2", #52, and the advisory namespace
3 collision, #53, both re-dated here); an unexplained one is a defect, fixed in a task of its own.

### 6.4 Hot rows and hot accounts

The platform's known hot rows: a merchant's payable, `FEE_REVENUE`, `FX_POSITION` and `FX_SPREAD_REVENUE` per currency
(ADR-0076 §9: the storm measured the conversion door's p99 at 713–760 ms), `LENDING_CAPITAL` per currency (ADR-0096; the
BNPL storm measured 65 waits, p95 142 ms, max 1,070 ms), `BNPL_MERCHANT_FEE_INCOME` (ADR-0103), the settlement source row,
the abuse window counter per source. `-008` measures each at the concurrent-financial profile: lock waits per row
(`pg_locks` sampled with `pg_blocking_pids`), the share of a command's latency spent waiting, and the throughput at which
the wait passes the operation's latency objective.

**The mechanisms, in the order they would be chosen, none built before a measurement names the row** (ADR-0041 already
records the first): (1) **sub-accounts by purpose or owner** — the hot account split into K rows (a bucket chosen by a
stable hash of the counterparty), their sum the account's balance, every posting still one balanced entry, the proofs
summing the buckets; (2) **shorter hold time** — the hot row locked last and the transaction kept short (the lock-order rule
already puts postings last); (3) **a separately decided projection exception** for a pure operational account that no
decision reads (ADR-0041: an explicit exception, never a silent change). **Never**: an asynchronous balance, a balance
mutated outside a posting, a weaker isolation for money, a cache in front of a decision. The decision is an ADR written by
`-008` if, and only if, a row passes its objective at the production target (§4.3); the capital row's tranches
(`DECISIONS.md` §Deferred "Capital tranches") are decided the same way.

### 6.5 Index efficiency, query plans and partitioning

`-008` reads the top statements' plans at the soak's end (`EXPLAIN (ANALYZE, BUFFERS)` on the test-only server for the
statements `pg_stat_statements` ranks highest by total time), and records any sequential scan on a growing table.
Partitioning (journal lines, outbox, inbox, audit, idempotency records) is decided by `-012` from the soak's growth rates
extrapolated to the production target's volume, by an ADR (the anticipated "partitioning decision", `docs/adr/README.md`
§Anticipated ADRs); none is built without one. Audit archival, the session sweep and the idempotency sweep are built
regardless, by the owner's decision (item 18: `-010`, `-011`). A transactional index build's write stop on a large table is
measured here (item 42 (b): "Phase 16's load characterisation measures that") and the migrator's mode for a non-transactional
build decided from it.

### 6.6 Read replicas and write scaling

**Writes do not scale out**: one primary holds the journal, and every arbiter is a row or constraint on it. Write capacity
is the primary's vertical size, the transaction length and the hot rows; §14.3 lists what would need an architectural
change. **Read replicas never serve an authoritative financial read** (`PHASE_GATES.md` §5 Phase 16): a decision (a hold, an
available balance, a limit, a four-eyes check) reads the primary under its lock. Today there is one data source and the
reporting pool reads the primary. If `-012`'s measurement shows report generation or operator reads loading the primary, a
replica may serve **the reporting pool only** (`finapp_reporting`, `SELECT` only, `ReportingNeverWritesTest`), a static guard
holding that no money-moving door reaches that pool, and every report from a replica stamped with the replica's replay
position. Absent that measurement no replica is routed, and the criterion holds by a guard that one primary data source
serves every financial door (`-012`).

### 6.7 Vacuum, WAL, checkpoints, backup and restore performance

The soak records autovacuum runs and dead tuples on the append-heavy and update-heavy tables (the balance projection, the
outbox, the inbox, the window counter, `platform.window_counter` being unlogged), WAL bytes per money movement, checkpoint
frequency and duration; `-024` records the restore's duration against the database's size at two sizes, so the RTO's
restore term is a measured rate rather than a guess (§11.2).

---

## 7. Kafka and the event pipeline (Part 14)

| Concern | Today | Phase 16 (`-013` unless named) |
|---|---|---|
| Partition strategy and keys | 12 partitions per topic, keyed by aggregate; "adding partitions later remaps keys" (`infra/kafka/topics.yaml`) | Read every producer's key from the event catalogue; measure skew under the §4.3 mix; partitions changed only with a migration plan (a new topic, never a resize in place) |
| Consumer groups and concurrency | One group per module (`finapp.<module>`), every replica a member; manual commit after the inbox transaction | At ten replicas a group has ten members over 12 partitions: measure idle members and per-partition lag; a member per partition is the ceiling |
| Rebalancing | Cooperative, bounded session timeouts (`P15-TSK-026`); a rebalance proven harmless at three (`KafkaInboxDeliveryKafkaTest#aRebalanceEffectsOncePerRecord`, the `kind` release scope) | Scale 3 → 10 → 3 during the sustained profile; rebalances counted; one effect per record by the census |
| Lag and backpressure | `finapp.inbox.lag`, the `consumption` SLO; the outbox's oldest publishable age, `event_freshness` | Lag at peak and after a burst, time to drain; the relay's throughput ceiling; a slow consumer never blocks a producer's transaction (the outbox decouples them) |
| Retry and dead letters | Bounded retries, a poison record parked (`P15-TSK-028`); the outbox's abandoned rows behind four-eyes doors | Retry budget under load; a burst of poison records parks without stopping the group |
| Ordering | Per aggregate, by key; per-aggregate advisory lock in the relay (`DISTRIBUTED_EXECUTION.md` §3 `OutboxRelay`) | Ordering asserted per aggregate across a rebalance and a broker failure; no handler relies on cross-aggregate order (the catalogue says which may) |
| Schema evolution | Build-time catalogue, additive within a major (`P15-TSK-027`) | Unchanged |
| Replay | Audited replay door, bounded by the inbox's 14-day dedupe (`DeadLetterDatabaseTest#aReplayPastTheWindowIsRefused`) | Replay of a day's volume measured; the inbox dedupes every applied record |
| Retention and replication | Retention below the dedupe window; RF 3, min ISR 2 in the reference; RF 1 on `kind` | A test-only three-broker KRaft on `kind` with the reference settings |
| Broker failure | Outbox holds rows (`OutboxRelayTest#brokerUnavailabilityRetriesAndLosesNothing`); the `incidents` broker step never ran (PI-024) | One of three brokers killed at peak (no outage: ISR 2 holds); all three stopped for 10 minutes (outage: rows wait, readiness holds, `event_freshness` burns, nothing lost); recovery and drain measured |

**Scaling consumers must not violate domain ordering or create a duplicate financial effect.** It cannot by construction —
a record's effect is one inbox transaction keyed by `(consumer, dedupe_key)` — and `-013` proves it at load with the census,
since a construction is a claim until it is counted. **Exactly-once is not claimed** because Kafka offers a mode; the
platform's guarantee is effectively-once **effects**: at-least-once delivery, an idempotent producer, and a dedupe that
commits with the effect (§13).

---

## 8. Resilience engineering (Part 15)

**The rule, for every pattern below: never a blind retry of a financial command or an external operation whose outcome may
be ambiguous.** A retry is safe only when (a) the operation is idempotent at its arbiter (an idempotency key, a unique claim,
a born-once row), or (b) it is a read or an inquiry, or (c) the provider's protocol makes it idempotent by our reference. An
ambiguous answer becomes `UNKNOWN` and an inquiry resolves it (no transaction spans a provider call, ADR-0046; the sweeps
resolve by inquiry); it never becomes a resend.

| Pattern | Exists | Validation and Phase 16 work |
|---|---|---|
| Timeouts | Connect timeouts per adapter; the pool's 5 s wait; `statement_timeout` armed on long reads; risk's 250 ms read budget; **no socket timeout** on the main data source (item 42 (h)) | `-018` inventories every outbound call's connect, request and overall bound into one table; `-022` decides the socket timeout from the measured longest statement and the failover's interruption |
| Retries, exponential backoff, jitter | Outbox and inbox retry with backoff; screening retry; sweeps re-attempt on their tick; no command is retried by the platform itself | `-018`: every retry classified (a)/(b)/(c) above, and jitter where several instances retry the same dependency at once |
| Circuit breakers | None as a library; the provider rails' availability is an operator's audited fact (ADR-0060 §4) | `-018` measures whether a provider outage wastes threads; a breaker, if added, only short-circuits calls whose failure is already safe (an inquiry, a read), and an automatic rail availability is C12's |
| Bulkheads | `CallbackInquiryBulkhead`; the reporting pool separate from the main pool; abuse counts bounded per instance (`max-concurrent-counts` 3) | `-016`: a bounded slot for each class of work that can starve another (operator reports, provider inquiries, money-moving commands) |
| Rate limiting | Cluster-wide per-source limits in PostgreSQL (`WindowCounter`); the edge's coarse per-controller tier | Calibrated from load (item 33 (b), `-015`); a per-key merchant limit stays item 33 (a)'s (the first real merchant) |
| Load shedding | None | `-016`: refuse with `503` and `Retry-After` **before** the idempotency claim and before any financial effect, when the pool's pending queue or the request's deadline says the command cannot finish; never after a claim; a shed command left no record and its retry is a first attempt |
| Backpressure | The outbox decouples producers from the broker; the consumers poll at their own pace | `-013` measures lag growth and drain; `-016` makes an HTTP-side bound explicit |
| Graceful degradation | Readiness down on database loss, never liveness; risk's recorded fail-safe; a provider unavailable answers `503` (counted against the SLO) | §10.3's matrix of financial-operation availability per failure |
| Queue buffering | The outbox (durable, PostgreSQL); the inbox (durable) | Bounded by retention; sizes measured in the soak |
| Provider failover | None (one simulated provider per rail; routing selects a rail by version, `findVersionInForce`) | Designed-only for a real second provider; rail availability per C12 |
| Dependency isolation | One pool per role; the edge and the operator surface separate | `-016`'s bulkheads |
| Resource exhaustion | Container limits, the namespace quota, the pool budget | Connection exhaustion, disk pressure and memory pressure experiments (§9, E07, E21) |

---

## 9. Failure injection (Part 16)

### 9.1 The method

**Controlled, repeatable, scripted** (ADR-0127): each experiment is a step of the `kind` harness (a new scope,
`REHEARSAL_SCOPE=experiments`, built by `-019`) with a hypothesis, a steady-state check before it, the fault, the
observations, a stop condition, the recovery, and the financial census after. The fault is injected while the demonstration
or sustained workload runs. The harness **refuses** any Kubernetes context other than the cluster it created (the context
name and a marker ConfigMap it wrote), any credential it did not generate, and any provider endpoint other than the
simulator. Tools (C6): `kubectl` and `docker` primitives, NetworkPolicies, and Toxiproxy pinned by digest on the data-tier
and provider hops. A multi-node `kind` cluster (one control plane, three workers, each labelled a zone) replaces the
one-node cluster for the experiments that need nodes or zones.

**Every result is recorded twice:** in the experiment register `docs/architecture/RESILIENCE_EXPERIMENTS.md` (created by
`-001`, held by its guard: every experiment carries every field; a result row names its run's date, commit and artefacts; a
failed result names its remediation task) and in the run's artefacts. The result and remediation slots below stay empty until
a run fills them.

### 9.2 The experiments

`Safety` for every experiment includes §1.1's boundary; the column adds what is specific. `Stop` is the termination condition:
the harness stops the fault and restores at the first of its triggers.

| ID | Scenario | Hypothesis | Affected | Expected behaviour | Safety | Success criteria | Stop | Result | Remediation |
|---|---|---|---|---|---|---|---|---|---|
| E01 | One application replica crashes (`kill -9` of the JVM) | The others serve; its claims lapse and are taken; in-flight commands retried by key take one effect | `finapp-app` | Its in-flight requests fail; the Service routes around it in one probe period; leases lapse and are claimed | One pod at a time | 0 duplicate effects; 0 lost outbox rows; failed requests ≤ those in flight on it; census green | A correctness page | — | — |
| E02 | Several replicas fail at once (half the fleet) | The rest carry the load or shed it before any effect | `finapp-app` | Readiness and the PDB keep ≥ 1; HPA replaces; latency rises, no money misstated | ≥ 1 replica always ready | As E01; shed requests left no record | Fewer than one ready replica for 60 s | — | — |
| E03 | Container termination and pod eviction during load (and the per-instance caches lost with them) | Graceful shutdown drains; an evicted pod's work completes elsewhere; nothing authoritative was in the lost caches | `finapp-app` | `preStop`, readiness out, HTTP drained, schedules and consumers stopped in order (`ShutdownOrderTest`) | Eviction via the API, the PDB respected | 0 failed requests across a graceful eviction; census green | A request failed on a graceful path | — | — |
| E04 | Node failure (a `kind` worker stopped) | Pods reschedule; the zone spread keeps replicas on the others | every in-cluster unit | Pods on the node lost; replacements scheduled; the edge's second controller serves | One node of three | Ready capacity restored within the HPA's and scheduler's bound; census green | The control plane unreachable | — | — |
| E05 | Database primary failure (the standby promoted) | Synchronous standby: nothing acknowledged is lost; replicas unready, not restarted; retries by key take one effect | PostgreSQL, every replica | §11.40's behaviour; new connections reach the new primary | Test-only pair (C7) | 0 committed rows lost (row counts and the census against the pre-failover snapshot); interruption measured; census green | The standby not caught up when promoted (refuse to promote) | — | — |
| E06 | Database restart, unclean (PI-024's step, without failover) | As §11.40's `kind` half | PostgreSQL | Readiness down and back; unlogged counters empty | — | No replica restarted; census green | — | — | — |
| E07 | Database connection exhaustion (other clients hold the reserve and the pool's share) | Pool waits bound requests; readiness reflects; operator access survives | every replica | `ConnectionPoolStarved` fires; requests time out at the pool's 5 s; no partial effect | A test client holding connections, released at stop | 0 partial effects; an operator connection admitted | 10 min | — | — |
| E08 | Kafka: one broker of three killed | ISR 2 holds; producers and consumers continue | Kafka | Leader elections; no error to the outbox | Test-only three-broker cluster | 0 records lost; lag drains | Two brokers down | — | — |
| E09 | Kafka unavailable (all brokers) for 10 min | Rows wait in the outbox; money still moves; nothing lost | Kafka, every consumer | `OutboxPublicationStalled`, `event_freshness` burns; readiness holds (Kafka is not in readiness) | — | After return: outbox drained, every row published once, every effect once; census green | 30 min | — | — |
| E10 | Redis failure | — | — | **N/A**: no Redis is used (§3); recorded as not applicable with the reason | — | — | — | N/A | — |
| E11 | Object storage unavailable | The application uses none; the restore's archive and Terraform state are infrastructure | infrastructure only | No application effect; a backup copy and a state write wait | Local object store (a container) stopped | No application error; the backup step fails loudly and retries | — | — | — |
| E12 | Network latency app ↔ database (Toxiproxy, +50/+200/+1000 ms) | Latency rises; timeouts bound it; no partial commit | every replica | Pool saturation at the higher steps; shedding if built | Toxiproxy only on the test hop | 0 partial effects; latency explained | p99 > 30 s | — | — |
| E13 | Packet loss and resets app ↔ database (1%, 10%) | Failed statements roll back whole; retries by key converge | every replica | Errors counted; no half transaction | — | 0 duplicate or partial effects | — | — | — |
| E14 | Network partition: one replica cut from the database (NetworkPolicy) while others serve | The cut replica goes unready; its leases lapse; a black-holed connection waits until the socket bound (item 42 (h)) | one replica | §11.40's black-holed case, measured | One replica | The others' claims proceed; the cut replica's threads freed within the decided bound | — | — | — |
| E15 | Partition app ↔ Kafka, app ↔ provider simulator | Outbox waits; provider calls become `UNKNOWN` or `503`, swept later | every replica | As E09 for Kafka; as E17 for providers | — | As E09 / E17 | — | — | — |
| E16 | DNS failure (CoreDNS scaled to zero, or a resolver returning errors, for 2 min) | Established connections continue; new ones fail and recover; nothing misstated | every replica | Readiness may fall on new connections; recovery on return | — | Census green; recovery time recorded | 10 min | — | — |
| E17 | External payment provider outage (the simulator answers `503`, then timeouts) | Commands answer `503` or `UNKNOWN`; sweeps resolve later; no resend of an ambiguous call | payments, checkout | `PaymentUnknownAged` within bounds; the SLO counts `503` | Simulator only | 0 payments posted without a provider answer; every `UNKNOWN` resolved once on return | — | — | — |
| E18 | FX provider outage | Quotes refused or stale-bounded; no conversion on a stale rate (`INV-FX-*`) | fx, crossborder | Quotes refused past their staleness | Simulator only | 0 conversions on a stale rate | — | — | — |
| E19 | KYC provider outage | Verifications wait; no customer activated without a decision | kyc | Cases pending; retries bounded | Simulator only | 0 activations without evidence | — | — | — |
| E20 | Credit bureau outage | Decisions take the fail-safe (`bureau-none`) as recorded, never an approval on missing data | credit | Recorded fail-safe decisions | Simulator only | 0 approvals without the policy's data | — | — | — |
| E21 | Slow external dependency (simulator at 2×, 10× its normal latency) | Thread use bounded; inquiries bulkheaded; other routes unaffected | payments, every adapter | `CallbackInquiryBulkhead` refusals counted; other SLOs hold | — | Unaffected routes within their objective | — | — | — |
| E22 | Message duplication (the same records re-sent; a consumer group reset to an earlier offset) | The inbox dedupes every applied record | every consumer | Duplicates counted as deduplicated | Within the dedupe window | 0 second effects | — | — | — |
| E23 | Message delay (records held 10 min, then released out of order across aggregates) | Per-aggregate order kept; late records applied once | every consumer | Lag rises, then drains | — | 0 order violations per aggregate; 0 second effects | — | — | — |
| E24 | Database disk pressure (the data volume filled to its threshold) | Writes fail cleanly; no partial transaction; readiness and alerts tell | PostgreSQL | Write errors; the platform refuses work; recovery on space | A size-limited volume on `kind` | 0 partial effects; census green after recovery | Volume full for 5 min | — | — |
| E25 | An edge controller lost under traffic (item 43 (f)) | The second controller serves | edge | Connections on the lost one fail once; the rest served | One controller of two | Failed requests ≤ those on the lost controller | — | — | — |
| E26 | Rolling deploy and rollback during load at ten replicas (scenario 14) | N − 1 and N coexist; zero failed requests; one effect per key | `finapp-app`, migrator | As the release scope, at ten | — | As §11.28's assertions, at ten | — | — | — |

---

## 10. High availability (Part 17)

### 10.1 Single points of failure

| Component | Today | Single point? | Answer |
|---|---|---|---|
| Application | ≥ 3 replicas, PDB, zone spread | No | E01–E04 |
| Migrator | A Job per release; Flyway's lock serialises | Not on the request path; a failed Job blocks a release, not service | Item 43 (b)'s alert (`-003`) |
| PostgreSQL (reference) | Multi-AZ synchronous standby | No, by design; failover unmeasured | Designed-only; local analogue E05 (`-022`) |
| PostgreSQL (`kind`) | One pod, its data on a volume claim (an `emptyDir` until 2026-11-12) | **Yes** (test-only) | The pair (C7) |
| Kafka (reference) | Three brokers, RF 3, ISR 2 | No | E08; designed-only for MSK |
| Kafka (`kind`) | One broker, RF 1 | **Yes** (test-only) | Three brokers (`-013`) |
| Edge | Two controllers per edge | No (within a cluster) | E25 |
| Telemetry | Two collectors; Prometheus, Alertmanager, Tempo, Loki one each on `kind` | **Yes** for alerting on `kind` — losing Prometheus or Alertmanager blinds the fleet | A real environment's HA pair; recorded, not built (item 43 (c)'s overlay is before the first real environment) |
| Registry | One (ECR in the reference) | **Yes** for a starting replica (T42, accepted until 2027-06-30) | `-025`: pre-pulled images on `kind`'s nodes and the mirror's design |
| DNS, the cluster's control plane | Managed in the reference; one control plane on `kind` | `kind`'s control plane is (test-only) | Designed-only |
| A region | One | **Yes** | §12 (C10) |
| The simulated providers | One per rail | **Yes**, by the programme's non-goal | Recorded; a second provider is a real-environment decision |

### 10.2 Availability targets

The availability SLOs of `SLOS.md` §2 (99.9% for the customer and merchant flows, 99.95% for callbacks, 99.5% for the
operator surface) stay the targets; `-029` recalibrates any whose measurement under load shows it cannot be met by the
architecture rather than by a defect, with the reason written. A composite target for a money-moving flow is never above
its weakest dependency's: a payment needs the application, PostgreSQL and its provider, and the provider's availability is
outside the platform's control (its `503`s spend the budget, `SLOS.md` §2).

### 10.3 Service availability is not financial-operation availability

A service can be up while an operation must be refused to keep money correct. The matrix `-023` writes and the experiments
check (examples; the full matrix per operation and per failure is the task's):

| Failure | Service | Operations that must be **unavailable** | Operations that stay available |
|---|---|---|---|
| PostgreSQL primary lost | Replicas unready, the edge answers `503` | Everything (every decision reads the primary) | None until failover; then all |
| Kafka lost | Up and ready | None of the commands; consumers' downstream effects wait (projections, notifications, cross-module reactions) | Every command; its events wait in the outbox |
| A provider lost | Up | That rail's dispatch (`503` or `UNKNOWN`), a rail an operator marks unavailable | Other rails, transfers, reads |
| FX rates stale | Up | Conversions and cross-border quotes past staleness (`INV-FX-*`) | Same-currency movements |
| The trial balance non-zero | Up | **An operator's decision** (`OPERATIONS_RUNBOOK.md` §11.20): the freeze of money movement is a person's act, not automatic — recorded as a question for `-023` whether any flow should refuse automatically | Reads |
| A restore under way | Down (roles locked, §11.35) | All money movement until the verification passes | None |

---

## 11. Disaster recovery (Part 18)

### 11.1 What is recovered, and how

| Concern | Today | Design (`-024`, `-025`) |
|---|---|---|
| Database backups | Reference: RDS automated backups, retention 7–35 days, a daily copy to a dedicated backup account (item 21 (a), never applied). Local: logical dump in one snapshot, encrypted (§11.35) | Unchanged in design; local drill adds WAL archiving |
| Point-in-time recovery | Reference: RDS PITR (designed-only). Local: none | `-024`: WAL archiving on the test-only server to a local object store; a restore to a named instant before a planted bad write, then the verification |
| WAL archiving | None locally | As above |
| Kafka recovery | Not truth: topics as code (`infra/kafka/topics.yaml`), the outbox the source | Recreate topics; reset each group to the restore's instant (§11.35 step 6, never run); an audited replay of the window; the inbox dedupes |
| Object storage recovery | Versioning and Object Lock on the archive bucket (Terraform, never applied) | Designed-only; local: the WAL archive and backup files restored from a second local copy |
| Infrastructure reconstruction | Terraform (never applied); the `kind` harness builds a cluster from the repository | `-025`: the `kind` cluster destroyed and rebuilt from the repository and the backup alone, timed |
| Secrets and keys | A managed store and KMS designed (ADR-0123); `kind` generates throwaway secrets per run | Designed-only for KMS and the store (key material's recovery is the critical path: a lost field-level key loses the evidence it encrypted); locally, the restore with the **same** test key ring proves evidence readable after recovery, and a restore with a wrong ring proves it refuses |
| Configuration | Git (GitOps), the configuration register (`P15-TSK-031`) | Re-sync from git; the fingerprint compared after recovery |
| Service redeployment | Argo CD by digest (`P15-TSK-017`); the registry a single point (T42) | Pre-pulled images; digests from the release record |
| Data integrity | `RestoreVerification`: row counts, catalogue, schema, sequences | Unchanged; run after every recovery |
| Financial ledger validation | Trial balance per currency, GL = ledger per period, projection check, every product proof, the source-join census | Over a source holding a loan and a BNPL obligation (item 44 (a)) |
| Reconciliation after recovery | Not exercised | After a restore to instant T: every settlement file accepted after T re-ingested from its source (the evidence is in the restored database up to T and at the provider after it); reconciliation re-run; the breaks it raises are the recovery's findings, each explained or escalated |

**The post-DR gate, before any money moves** (`DELIVERY_PLAN.md` §Phase 16 §13, `PHASE_GATES.md` §5 Phase 16): the roles stay
locked (§11.35's first rule) until `RestoreVerification` passes, then the reconciliation of the window since the restore
point runs with money movement still held (the doors refused by a hold the task designs on the existing `SchemaVersionGuard`
pattern or the role lock), and only a person's recorded release, after both, lets money move. A failover with a synchronous
standby is **not** a restore: nothing acknowledged is lost (§11.40), so readiness returning is enough, and the continuous
correctness signals are the check.

### 11.2 RTO and RPO, proposed (C9, ADR-0128)

Not arbitrary: each tier from the record's criticality (the journal is the financial record), the financial risk of loss (a
lost acknowledged transaction is money the customer was told moved), the cost (a second region doubles the environment) and
what the decided architecture can do.

| Tier | Event | RPO | RTO (to money movement) | Basis | Executable here |
|---|---|---|---|---|---|
| 1 | A database instance or zone lost | **0** | **15 min** | Synchronous Multi-AZ standby (Terraform `multi_az = true`): every acknowledged commit is on the standby; the RTO allows the provider's failover, readiness and a person's check of the correctness signals | Local analogue E05: the application's interruption measured; the managed failover designed-only |
| 2 | Logical corruption or deletion (a bad migration, a raw writer, an operator's mistake) | **≤ 5 min** before the bad write | **4 h** | Point-in-time restore from continuous log archiving (the provider's documented behaviour, unverified here); restore time scales with size, then the full verification and the reconciliation window | Local PITR drill: procedure's durations at the local size; restore rate per GiB measured |
| 3 | The production account lost or compromised | **24 h** | **24 h** | The dedicated backup account's daily copy (item 21 (a), decided 2026-11-09); infrastructure rebuilt from code into a clean account | Local rebuild from repository + backup, timed; the cloud half designed-only |
| 4 | A region lost | **24 h** (today's design); minutes only if cross-region replication is bought | **24 h** | As tier 3, into another region; cross-region replication is C10's (B) | Designed-only (Terraform variant tested with mocks, `-026`) |
| — | Kafka lost | **not applicable to financial truth**; events re-published from the outbox within the dedupe window | 1 h to drained | Kafka is not truth (rule 12) | E09 and the restore's broker step |

**Backup frequency and retention** (reference): continuous log archiving with daily snapshots, retention 35 days in production
and 7 in staging (the module's range), the daily cross-account copy kept as the module's copy retention; a jurisdiction's
legal period is not set here (`DATA_RETENTION.md`). **Ownership**: `platform` owns the procedure and the drill; a real
environment names a person or rota (`PRODUCTION_READINESS.md`'s Ownership row). **Validation**: every recovery ends in §11.1's
gate; a drill that skipped it is not a drill. **Restoration is tested, not merely backup creation**: the exit requires the
local restore and PITR drills executed with their numbers, and the managed ones accepted as designed-only (C4).

---

## 12. Multi-region assessment (Part 19)

**Stance (C10, ADR-0129): one region, multiple availability zones, active-passive for the financial record. No active-active
writes.** The ledger has one authoritative writer; two regions accepting writes to the same accounts would need conflict
resolution on money — a posting accepted in each region against the same balance — which no invariant of the catalogue
admits (`INV-LED-*`, `INV-BAL-*`). Split-brain is refused by construction: a second region is promoted only by a person's
recorded act after the first is fenced (its database stopped or its credentials revoked), never by a health check.

| Service, store or workflow | Model | Why |
|---|---|---|
| PostgreSQL (the journal and every arbiter) | Single writer; Multi-AZ synchronous; another region a **recovery site** (restore, or a warm asynchronous replica promoted by a person) | Authoritative ownership first; an asynchronous replica's lag is the region tier's RPO |
| `finapp-app` | Active in one region; deployable into another from code and digests | Stateless; correctness is the database's |
| `finapp-migrator` | With the database's region | Migrations follow the writer |
| Kafka | Per region, never replicated as truth; the recovery region recreates topics and replays from the outbox | Not truth (rule 12); cross-region mirroring would add a second ordering domain for nothing financial |
| Telemetry | Per region; the recovery region's own | Diagnostic only |
| Object storage (state, archives, backups) | Copied across an account (decided) and optionally a region | The recovery inputs must survive the region |
| Secrets and KMS keys | Replicated to the recovery region before it is needed (multi-region keys or a re-wrap procedure) | A key unavailable in the recovery region makes its evidence unreadable: the critical path of tier 4 |
| Customer reads (balances, statements) | From the active region only; a read-only regional cache is **refused** for anything a customer may act on | A stale balance shown as current is a financial misstatement to a customer |
| Settlement and reconciliation | Active region only; after a regional recovery, the window since the recovery point re-ingested (§11.1) | Evidence ordering and the gapless sequence per source |
| Provider connectivity | Regional availability of providers is the provider's; a real provider's regional endpoints are a real-environment decision | Simulated today |
| Data residency | A jurisdiction's question (none is chosen); a recovery region must satisfy the same residency rule as the primary | `ROADMAP.md` §Non-Goals: no real jurisdiction |
| Cross-region latency | Not on any request path under active-passive | No synchronous cross-region call is designed |

`-026` writes this as an assessment document section and the Terraform variants (a recovery-region root composing the
existing modules, the backup copy's cross-region destination) checked by `terraform test` with mock providers, never applied.

---

## 13. Financial correctness under failure (Part 20 — the most important part)

**The seven checks, for every scenario**: (a) no unintended duplicate financial effect — one effect per idempotency key, one
journal entry per business fact; (b) no unexplained money created or destroyed — the trial balance zero per currency, every
product proof `HOLDS`; (c) no silent mutation of history — journal rows unchanged (the census compares a checksum of the
journal up to the fault's start before and after); (d) no permanent loss of financial evidence — every evidence row present
after recovery, the restore's row counts equal; (e) safe recovery and replay — a re-run converges; (f) correct idempotency —
a same-key retry answers the stored result; (g) reconciliation detects what is unresolved — a planted discrepancy left by the
scenario is raised as a break. **Exactly-once is not claimed** from a framework mode: the guarantee shown is effectively-once
**effects** end to end, by the census.

**Each scenario is executed end to end** by `-027` against the `kind` deployment under the demonstration workload, with the
seven checks counted, and its result written in the experiment register. What already proves part of each, in the tree
(tier in brackets: `[db]`, `[kafka]` and own-container tests are owed to PI-023's pass and have not run since Phase 15 began;
`[hermetic]` runs per task; a "multi-instance" test is threads or a second context in one JVM on one database):

| # | Scenario | What already proves part of it | What it does not prove | Experiment |
|---|---|---|---|---|
| 1 | A transfer is committed but the HTTP response is lost | `platform/src/test/java/com/finapp/platform/idempotency/IdempotencyFailureModeTest.java#responseLostAfterCommitIsReplayed` [db]; `app/src/test/java/com/finapp/app/transfers/TransferExecutionDatabaseTest.java#tenConcurrentIdenticalKeysProduceOneTransfer` [db] | No real response lost on a network; one JVM | The edge's connection to the client reset after the command's commit (Toxiproxy on the client hop), the client retrying through another replica |
| 2 | A payment provider succeeds but the response is lost | `app/src/test/java/com/finapp/app/payments/PaymentAmbiguityDemonstrationDatabaseTest.java#receivedBeforeLostAtCapture` [db]; `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#anAgedCaptureUnknownResolvesToCapturedOnce` [db] | Sweep timing at load; a real network loss | The simulator's answer dropped after it records success (E15 on the provider hop), the sweep resolving it at load |
| 3 | A ledger posting succeeds but event publication fails | `app/src/test/java/com/finapp/app/bnpl/BnplOutboxAtomicityDatabaseTest.java#aCrashBetweenPostingAndPublicationLosesNothing` [db]; `platform/src/test/java/com/finapp/platform/outbox/OutboxRelayTest.java#brokerUnavailabilityRetriesAndLosesNothing` [db]; `platform/src/test/java/com/finapp/platform/outbox/KafkaOutboxDeliveryKafkaTest.java#aCrashBetweenAckAndMarkRedelivers` [kafka] | One broker, no load | E09 and a relay pod killed between the broker's ack and the mark |
| 4 | A consumer processes an event and crashes before acknowledgement | `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaInboxDeliveryKafkaTest.java#aCrashBeforeTheOffsetCommitRedelivers` [kafka]; `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#aCrashBeforeCommitIsRecoveredByRedelivery` [db] | A killed process, not a closed consumer | Consumer pods killed during the sustained profile (E01 timed to the commit window) |
| 5 | The same event delivered to multiple processing attempts | `platform/src/test/java/com/finapp/platform/inbox/InboxConsumerTest.java#concurrentDuplicatesProduceOneEffect` [db]; `platform/src/test/java/com/finapp/platform/inbox/kafka/KafkaInboxDeliveryKafkaTest.java#aRebalanceEffectsOncePerRecord` [kafka] | Separate processes; at load | E22, and scale 3 → 10 → 3 during load |
| 6 | A service crashes during a repayment allocation | `lending/src/test/java/com/finapp/lending/RepaymentDatabaseTest.java#aCrashAfterThePostingLeavesNothing` [db]; `lending/src/test/java/com/finapp/lending/RepaymentDatabaseTest.java#theSameRepaymentTwiceRepaysOnce` [db]; `app/src/test/java/com/finapp/app/lending/LendingStormDatabaseTest.java#theStormLeavesEveryLoanProvable` [own-container] | A killed process | A replica killed during month-start repayments at load; the loan subledger proof and `LOAN_REPLAY` after |
| 7 | Two instances concurrently capture the same payment | `app/src/test/java/com/finapp/app/payments/PaymentCaptureDatabaseTest.java#tenConcurrentCapturesProduceOneEntry` [db]; `app/src/test/java/com/finapp/app/payments/PaymentSweeperDatabaseTest.java#aSweeperRacingTheWebhookProducesOneEffect` [db] | Separate pods | The same capture sent to ten replicas at once (distinct connections through the edge), with a webhook racing |
| 8 | Two instances concurrently release the same hold | `app/src/test/java/com/finapp/app/payments/WithdrawalHoldDatabaseTest.java#tenReleasesOfOneHeldWithdrawalDispatchOnce` [db]; `app/src/test/java/com/finapp/app/domain/HoldDatabaseTest.java#releaseRestoresAvailabilityExactly` [db] | A ledger hold released concurrently (only sequentially tested); separate pods | Ten replicas releasing one hold, and a release racing the expiry; a concurrent ledger-hold case added by `-027` if the experiment shows the gap |
| 9 | A settlement file ingested while a previous ingestion is incomplete | `settlement/src/test/java/com/finapp/settlement/IntakeAtomicityAndRacesDatabaseTest.java#tenParsersOverTenConflictingFilesLeaveOneLiveBatch` [db] | A parse stuck across a real process death | A replica killed mid-parse of file 1 while file 2 of the same source arrives; one acceptance at a time per source, the gapless sequence kept |
| 10 | A reconciliation process crashes halfway through a batch | `app/src/test/java/com/finapp/app/reconciliation/SettlementReconciliationStormDatabaseTest.java#theStormReconcilesTheReconciliation` [own-container] | A killed process; load | A replica killed mid-chunk; another resumes from the last committed cursor |
| 11 | A database primary fails during financial processing | `app/src/test/java/com/finapp/app/api/HealthEndpointTest.java#readinessReflectsTheDatabase` [hermetic]; `platform/src/test/java/com/finapp/platform/outbox/OutboxCrashRecoveryTest.java#aKilledInstanceDoesNotStrandItsAggregate` [db]; `OPERATIONS_RUNBOOK.md` §11.40 (procedure only) | No failover anywhere; the `kind` restart never ran (PI-024) | E05 under load (`-022`) |
| 12 | Kafka becomes temporarily unavailable | `platform/src/test/java/com/finapp/platform/outbox/OutboxRelayTest.java#brokerUnavailabilityRetriesAndLosesNothing` [db]; `PlatformRehearsalContractTest` (the script's text, never run) | A real outage at load | E09 |
| 13 | A provider returns a delayed success after an internal timeout | `app/src/test/java/com/finapp/app/payments/PaymentAmbiguityDemonstrationDatabaseTest.java#timeoutThenSuccessAtCapture` [db]; `app/src/test/java/com/finapp/app/payments/PaymentWebhookTransitionDatabaseTest.java#lateAndOutOfOrderReportsAreEvidenceOnly` [db] | Real delivery delays | The simulator times out, then sends success 5 min later, after the sweep's inquiry and after a resolved failure |
| 14 | A service redeployed while transactions are in progress | `app/src/test/java/com/finapp/app/lifecycle/ShutdownOrderTest.java#theRealOrder` [slice]; the `kind` release scope, green 2026-11-09 at three replicas (`OPERATIONS_RUNBOOK.md` §11.28) | Ten replicas; the final tree (PI-024) | E26 at ten |
| 15 | A recovery process replays previously processed messages | `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterDatabaseTest.java#tenReplaysOneRequeue` [db]; `platform/src/test/java/com/finapp/platform/deadletter/DeadLetterDatabaseTest.java#aReplayPastTheWindowIsRefused` [db] | A replay after a restore (§11.35 step 6 never ran) | The restore drill's broker step: groups reset to the restore instant, the window replayed, every effect once |

---

## 14. Capacity and scaling strategy (Part 21)

### 14.1 Per workload class

| Class | Strategy | Limit to measure | Note |
|---|---|---|---|
| Stateless APIs | Horizontal (HPA on CPU, calibrated by `-015`) | The connection budget (§6.2), then the primary | Adding replicas also adds schedule and consumer competitors |
| Payment orchestration | Horizontal; provider-bound | Provider latency × concurrency; the bulkhead | Never a retry of an ambiguous dispatch |
| Ledger operations | **Vertical** for the writer; hot rows by sub-accounts if measured (§6.4) | Postings per second on the hottest row | No write scale-out without an ADR |
| Background workers (37 schedules) | Leaderless on every replica, `SKIP LOCKED` claims; batch sizes | Claim contention at ten; batch throughput per tick | C5 of Phase 15 left a role split (`api`/`worker`) to this measurement (`-015`) |
| Kafka consumers | Horizontal to the partition count (12) | Lag per group | More partitions is a new topic (§7) |
| Reconciliation workers | Chunked, resumable, any instance | Chunks per minute; per-source serialisation | |
| Report generation | Its own pool (`finapp_reporting`), read-only | The primary's read load; a replica only for this pool (§6.6) | |
| Risk evaluation | Synchronous budget 250 ms with a recorded fail-safe | Fail-safe rate at load (a fail-safe is a decision, not an error) | Risk's whole-day proofs (debt rows) measured here |
| Loan servicing | Leaderless sweeps; month-start spike | The spike's duration | |
| Notifications | **None exist** (no notifier: `DECISIONS.md` §Deferred "changing the verified contact channel") | — | N/A |
| Webhooks (inbound callbacks) | Horizontal; evidence and dedupe first, inquiry bulkheaded | Callback rate at burst | |

### 14.2 The distinctions

**Horizontal** for the stateless request path and the claim-based workers; **vertical** for the PostgreSQL primary; **partitioning**
of Kafka topics (fixed at 12) and, if `-012` decides, of high-volume tables; **workload isolation** by pools and bulkheads, and
by a role split only if measured (`-015`); **read scaling** only for the reporting pool (§6.6); **write limitations** stated
(§6.6); **backpressure** by the outbox and by explicit shedding (`-016`).

### 14.3 Bottlenecks that need architecture, not infrastructure

Candidates, each to be confirmed or struck by measurement: the per-currency hot rows (§6.4); `settlement.source FOR UPDATE` for a
whole acceptance (debt row, re-dated here); risk's whole-day proofs and the rescreening census counting each version whole (two
debt rows, re-dated here); the single writer itself at a volume far above the production target. Each confirmed one becomes an
ADR before a change.

---

## 15. SLO, SLI and error-budget validation (Part 22)

| Indicator | Today | Phase 16 (`-029`) |
|---|---|---|
| API availability | `SLOS.md` §2, per flow | Validated under every profile; the burn-rate rules fire on a real burn during E17 and stay silent at peak |
| API latency | Authentication and transfer only (`SloLatencyBuckets` adds one 1 s boundary to those routes) | Proposed latency SLIs for payments, merchant API and reads (§4.3), their buckets added only where a target is adopted |
| Payment success and processing latency | Availability only; a decline is never an error | Processing latency from command to final state, measured; declines and `UNKNOWN`s counted as business outcomes |
| Ledger posting latency | None | A posting-duration SLI proposed from `-007`'s measurement |
| Transfer processing | `transfer_*` | Unchanged; validated |
| Event processing lag | `event_freshness`, `consumption` | Validated at peak and after E09 |
| Webhook delivery | `provider_callbacks` (inbound) | Validated at burst; outbound webhooks do not exist |
| Settlement ingestion | Alerts only (`SettlementFileStuck` and the rest) | An ingestion-latency SLI (§4.3's 15 min) proposed |
| Reconciliation completion | Alerts only | A completion SLI (30 min) proposed |
| Critical financial workflows | Correctness signals, never budgeted | Unchanged: a correctness page during any experiment fails it |

**Business outcomes are not technical failures.** A provider's decline, a risk refusal, a credit decline, an insufficient-funds
refusal and a fail-safe decision answer `2xx` or `4xx` and spend no budget (`SLOS.md` §2); `-029` proves it under load by
driving the simulators to decline 30% of payments and showing no burn. **Operational responses**: `SLOS.md` §4's policy
(freeze non-reliability changes to a flow whose budget is spent) is kept, and validated by a planted burn in a run. Item 42 (a)'s
break-age counts (a gauge per severity of breaks past their bound, a rule when the count rises) are built here before any
environment holds parked value.

---

## 16. The distributed-system audit (Part 23)

`-031` performs it at the phase's end over the code and the experiments' evidence, assuming ten or more instances, dynamic
scaling, restarts, rebalances and partial outages: concurrency correctness, idempotency across instances, coordination, safe
event processing, transaction boundaries, consistency, ordering, recovery, replay, backpressure and resource isolation, each
answered with a citation. What is known now, for it to confirm or correct:

| Item | Reading today | Single-instance assumption? |
|---|---|---|
| Per-instance counters (`CallbackInquiryBulkhead`, the abuse doors' in-flight bound) | Protect an instance's threads, decide nothing financial (their own javadoc) | No |
| The edge's coarse limit | One bucket per source per controller replica, by design; the cluster-wide count is the application's (`PRODUCTION_READINESS.md` §3.6) | No (a stated design) |
| Time decided on an instance's clock | Routing's version in force and the dispute door (PI-021), the remainder of `X-TSK-008` | Bounded by skew; owned elsewhere (before production use) |
| The idempotency claim's lock order and the advisory namespace 3 hash | Availability only (an aborted transaction converges): debt rows #52, #53, re-dated here | No; measured by `-008` |
| A poisoned row at a sweep's head, a rematch residual faulting every tick, a corrupt id stalling the card sweep | Availability, never a misjudged amount: three debt rows and item 42 (i), re-dated here | No; fixed by `-017` |
| The connection budget at ten | Does not fit (§6.2) | Not single-instance, but a ceiling: C3 |
| `DISTRIBUTED_EXECUTION.md` §3 without a guard | `P15-REM-004` (a) | Fixed by `-002` |

A component that a Phase 16 task adds (shedding's bulkheads, a sweep's failure count, a replica route) gets its
`DISTRIBUTED_EXECUTION.md` §3 row in the task that adds it (§5 standing rules of that document). If the audit finds a
violation of the multi-instance rule, it raises a remediation task and the exit does not pass over it.

---

## 17. The inputs Phase 16 holds (decided by the owner)

### 17.1 Placed in this phase

| Input | Decided | Where it lands |
|---|---|---|
| `THREAT_MODEL.md` §6 item 43 (b): the edge's metrics scraped; the edge's, the migrator's and the telemetry stack's lines shipped; a failed-migrator-Job alert and an edge error-ratio alert, each with promtool cases and a runbook section | 2026-11-12, first wave | `P16-TSK-003` (wave A) |
| Item 43 (c), the quota part: a `ResourceQuota` for `finapp-edge` and `finapp-observability` | 2026-11-12 | `P16-TSK-004` (wave A) |
| Item 43 (f): the edge, the test-only PostgreSQL and the test-only broker rolled and rolled back on `kind` | 2026-11-12, first wave | `P16-TSK-005` (wave A); the edge controller lost under traffic is E25 in `-020` |
| Item 43 (g): the database failover drill (runbook §11.40, with item 42 (h)'s socket timeout) | 2026-11-12 | `P16-TSK-022` |
| Item 43 (g): the data tier's availability | 2026-11-12 | `P16-TSK-023` |
| Item 43 (g) and the exit review's acceptance B: ten instances at load | 2026-11-12 | `P16-TSK-014` |
| Item 44 (a): the restore's lending and BNPL proofs over a source holding a loan and a BNPL obligation, in the post-recovery verification drill | 2026-11-12 | `P16-TSK-024` |
| Item 44 (b): `trivy config` at HIGH and CRITICAL over every rendered overlay, in the `manifests` job | 2026-11-12, first wave | `P16-TSK-004` (wave A) |
| `P15-REM-004`: guards that hold less than they are named for | Horizon "Phase 16's first wave" | `P16-TSK-002` (wave A) |
| `P15-REM-001`: ADR-0006's boundary rules made to hold | Horizon "before any service extraction is decided" | A dependency of `P16-TSK-030` (the extraction evaluation); stays `P15-REM-001`, scheduled by the owner before it |
| Item 42 (a): break-age counts | 2026-11-12, "in Phase 16 before any environment holds parked value" | `P16-TSK-029` |
| Item 42 (b): a migration outside a transaction, measured | 2026-11-12, "Phase 16's load characterisation measures that" | `P16-TSK-008` |
| Item 42 (h): the socket timeout | 2026-11-12, "Phase 16's failover drill" | `P16-TSK-022` |
| Item 42 (i): a sweep's failed tick metered and paged, and (ii) the listing that skips and counts | 2026-11-12, Phase 16 | `P16-TSK-017` |
| Item 18: audit archival, the idempotency sweep (after the posting-scope uniqueness), the session sweep, the unused `DELETE` revoked | 2026-11-09, "Phase 16's partitioning and archival" | `P16-TSK-010`, `P16-TSK-011` |
| Item 32 (b): a connection limit or WAF in front of the edge, sized by the load model | 2026-11-09 | `P16-TSK-015` (the number, designed-only for the product) |
| Item 33 (b): the abuse limits calibrated from a load model | 2026-11-11 | `P16-TSK-015` |
| Item 1 (T42): registry availability for a starting replica, accepted until 2027-06-30, re-owned to Phase 16's DR | 2026-11-08 | `P16-TSK-025` |
| Item 21 (a): cross-region backup replication "is Phase 16's multi-region work" | 2026-11-09 | `P16-TSK-026` (designed-only) |
| `PHASE_15_PLAN.md` §12: a transaction-mode pooler evaluated by measurement | Phase 15's plan | `P16-TSK-009` |
| `P15-REM-007` (the Phase 15 completion gate's G-3): the HPA never seen scaling (no metrics source on `kind`) and its ceiling of seven set by the connection budget | Horizon "Phase 16, with the ten-instance load task" | A metrics source on `kind` and the budget or a pooler in `P16-TSK-009`; a scale-up and a scale-down observed under load in `P16-TSK-014` |
| `PHASE_15_PLAN.md` C5: the `api`/`worker` role split left to Phase 16's measurement | Ratified 2026-11-07 | `P16-TSK-015` |
| `PRODUCTION_READINESS.md` §3.1 and §3.3 Failure handling, §3.1, §3.3 and §4.2 Runbooks, §3.3 Multi-instance: "a failover, a Phase 16 drill no backlog entry holds" and "its own availability under failover, Phase 16's" | The readiness review, 2026-11-12 | `P16-TSK-022`, `P16-TSK-023`; `-001` re-points the cells and item 43 (g) at these tasks |
| `CURRENT_STATE.md` debt rows owned by Phase 16: audit archival, idempotency sweep and the unused `DELETE`; session rows; posting once-ness's second rank (#61) | The owner, 2026-11-08 and -09 | `-010`, `-011` |
| Debt rows: the idempotency claim's lock order (#52); advisory namespace 3 (#53); `settlement.source FOR UPDATE`; the rescreening census; risk's whole-day proofs; the lending capital row and L6 across BNPL's waits | C15, 2026-11-08; the Phase 12 → 13 gate | `-008` (measured; an ADR if a change is needed) |
| Debt rows: a poisoned row delaying a sweep; a rematch residual faulting every tick; a corrupt card attempt id | C15, 2026-11-08 | `-017` |
| Debt rows: the reconciliation intake rules in `app`; the risk module's extraction decision and M3's split triggers; `DECISIONS.md` §Deferred "Service extraction" | C15; `P13-DOC-001` | `-030` |
| `DECISIONS.md` §Deferred: automatic rail availability from observed failure rates | C15, 2026-11-08 | `-018` (C12) |
| `DECISIONS.md` §Deferred: capital tranches, "if a measurement demands it" | Phase 11 review | `-008` |

### 17.2 Re-read, not placed

- **Item 43 (a)** (the managed services' own signals) and **(c)'s overlay half** — "with the first real environment" and "before
  the first real environment": not this phase. The `kind` harness reads PostgreSQL's statistics for load tests (§5.2), which is
  not (a)'s production telemetry.
- **Item 43 (d)** (alert ownership labels) and **(e)** (modules with flows and no signal) — "before production use": not this
  phase; `-029` may take (d) if the owner wishes, as a small change beside the SLO work.
- **`P15-REM-002`** (an audit record per auditable route, "before production use") and **`P15-REM-003`** (the trace's provider
  and ledger legs, "before the trace is relied on in a real environment", its run with PI-024): not this phase's horizons.
- **`P15-REM-005`** is the Phase 15 → 16 gate's own (before its guard run).
- **`P15-REM-006`** (the local Redis nothing uses, its comment claiming "rate limits") — "with the next change to `compose.yaml`": not
  a Phase 16 task. It confirms §3's reading: Redis is N/A to the platform, and no Phase 16 experiment treats it as a dependency.
- **PI-016…PI-022**: each "before production use" (PI-017 before the first real customer launch); none is Phase 16's. The
  experiments do not exercise a raw writer (PI-016, PI-018), and PI-019's double credit is detectable, not prevented: `-027`
  records it as a known exception if scenario 13's instant-rail variant meets it, never as a pass.
- **PI-004, PI-023, PI-024**: C1 and C11.

### 17.3 Out of the phase by horizon

Every `P14-REM-*` still open, the owner items whose part is "with the first real environment" (items 7, 21 (b), 23, 30, 31,
35, 36, 37 (a), 38), and the real-environment list of `PHASE_15_PLAN.md` §1.1.

---

## 18. Security under scale and failure (Part 24)

| Concern | Reading | Phase 16 (`-028`) |
|---|---|---|
| Authentication under failover | Sessions are server-side rows in PostgreSQL (ADR-0030); no instance holds a session | A session survives a replica's replacement and a database failover (E01, E05), proven by the same session's next request |
| Session during instance replacement | As above; no sticky routing | Asserted at ten under a rolling deploy |
| Key availability and recovery | Field-level key rings from the environment (ADR-0123); KMS designed-only | Restore with the same ring reads evidence; with a missing version refuses (§11.1); the KMS half designed-only |
| Secrets during DR | Generated per `kind` run; the store's recovery designed-only | The DR exercise regenerates secrets and rotates nothing it should not: the restored database's roles keep their grants, credentials are new |
| API abuse during spikes | Per-source limits cluster-wide (`WindowCounter`) | Held at ten under the spike profile; the counter's unlogged reset on failover (§11.40) measured as a window of fresh budgets |
| Rate limiting across instances | PostgreSQL-arbitrated | Exactly the limit admitted at ten replicas, counted |
| DDoS protection | The edge's coarse tier; a WAF designed-only (item 32 (b)) | Sizing from the load model; designed-only |
| Privileged operations | Four-eyes in the database; step-up | Never relaxed by a failover or a restore: a planted four-eyes act during E05 still refused for the same person |
| Audit integrity | Append-only by grant (ADR-0010), `TRUNCATE` refused | Audit rows counted equal across a restore and a failover; an archival (`-010`) never deletes before the archive copy is verified |
| Confidentiality of backup and restore | Encrypted dump, key made for it (§11.35) | Unchanged; the PITR archive encrypted the same way; no plaintext on disk (asserted by the drill) |
| Fail-open under degradation | Authorization is the application's; readiness down on database loss | No route answers a protected request without its check under any experiment: a negative probe per experiment (a request without a session must stay refused while the fault is on) |

---

## 19. The testing strategy (Part 25)

### 19.1 The program

| Kind | Where | Per task or at the phase |
|---|---|---|
| Unit, slice | Hermetic tier | Per task: the changed classes' tests and the named guards reading changed files (the owner's rule) |
| Integration (database, Kafka) | Gradle `databaseTest`, `kafkaTest`, own-container | **Written and owed** (C2): never per task; PI-023's pass, or C11's pre-flight |
| Contract tests | Hermetic (the simulators' contracts, the harness contracts) | Per task when touched |
| Concurrency tests | The in-process database-tier tests (owed); the `kind` experiments at ten (executed) | Experiments per task |
| Load, stress, spike, soak | k6 on `kind` | `-006`, `-007`, `-014`, `P16-TST-001` |
| Failure injection and chaos | The `experiments` scope | `-019`…`-023`, `-027` |
| Database recovery, backup restoration | `restore-rehearsal.sh` extended | `-024` |
| DR exercises | Cluster destroyed and rebuilt | `-025` |
| Security | Negative probes per experiment; the existing security tests | `-028` |
| End-to-end financial correctness | The census during and after every run; the fifteen scenarios | `-027`, every experiment |

**Every experiment has measurable acceptance criteria and a documented result** (§9.2's columns), held by the register's guard.
A flaky result is classified (harness, environment, product) and never retried into green; a red caused by the machine (memory,
disk) is recorded as an environment limit with its numbers.

### 19.2 Checks per task (the owner's standing rule)

Named test classes only: the changed classes' tests, the guards that read the changed files, and the harness's contract tests
(`KindRehearsalContractTest`, `PlatformRehearsalContractTest`, and the new experiments scope's) — no whole-module task, no
`:app:architectureTest`, no package filter. An experiment's run is the task's evidence and is run in the task; it is not a
"test" in the owner's rule's sense but the phase's substance (C1).

### 19.3 What depends on PI-023 and PI-024

- **PI-024** — the `incidents` steps (broker outage §11.6, database restart §11.40, one instance down §11.8, the poisoned row
  §11.39) are E09, E06, E01 and `-017`'s experiment: Phase 16 runs them as its own experiments and, under C11 (A), records which
  parts of PI-024 they close. The `signals` scope, the delivery's unseen steps and the release scope on the final tree stay PI-024's.
- **PI-023** — the ten-way database cases of Phase 15's arbiters (the window counter, the retention sweeps, the source census, the
  dead-letter doors) and every Phase 15 database case: the ten-instance drill exercises those arbiters through HTTP and judges them
  by the census, black-box. C11 (A) runs a narrow pre-flight of the suites the drill exercises before `-014`.

---

## 20. Milestones (Part 26)

Sized to real dependencies: the harness before any measurement, the measurement before any change it justifies, the toolkit
before the experiments, the experiments before the scenarios that compose them.

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M16.1 Foundations and the first wave** | The capacity model and the experiment register with its guard; every first-wave input of the owner's built | `-001`…`-005` |
| **M16.2 The load harness and the workload baseline** | k6 against the real edge with invariants sampled during load; the demonstration run green; the engineering workload measured and its first limit named | `-006`, `-007` |
| **M16.3 Database load and concurrency** | Hot rows, locks, deadlocks and plans measured; the budget at ten decided; archival and sweeps built; partitioning and replica decisions recorded | `-008`…`-012` |
| **M16.4 The event pipeline at load** | Three brokers on `kind`; lag, rebalance, broker loss and replay at volume measured with one effect per record | `-013` |
| **M16.5 Scaling the application** | Ten replicas at load with the census green; HPA, limits and the role split calibrated | `-014`, `-015` |
| **M16.6 Resilience patterns** | Shedding before the financial effect; sweeps that survive a poisoned row; every retry and timeout classified; provider outages handled | `-016`, `-017`, `-018` |
| **M16.7 Controlled failure injection** | The toolkit with its safety; instance, node and network experiments run with results | `-019`, `-020`, `-021` |
| **M16.8 High availability** | The database failover drill measured; the data tier's availability and the SPOF register; the financial-operation availability matrix | `-022`, `-023` |
| **M16.9 Backup and restoration** | Restore and PITR drills with the post-recovery verification over a source holding every product; Kafka recovery | `-024` |
| **M16.10 Disaster recovery** | The DR plan; the cluster rebuilt from the repository and a backup, timed; RTO/RPO evidence per tier | `-025` |
| **M16.11 The multi-region assessment** | Per-store and per-workflow models; Terraform variants tested with mocks | `-026` |
| **M16.12 Financial correctness under failure** | The fifteen scenarios executed with the seven checks; security under failure | `-027`, `-028` |
| **M16.13 Capacity and the operational review** | SLOs validated under load; the extraction evaluation; the distributed-system audit; the integrated soak; the exit | `-029`, `-030`, `-031`, `P16-TST-001`, `P16-TST-002`, `P16-DOC-001` |

**Cut order** (if the phase must shrink, each deferral recorded with its owner): `-012`'s replica route (the decision stays), `-030`
(the extraction evaluation re-dated), `-026`'s Terraform variants (the assessment stays), `-021`'s DNS and disk-pressure
experiments. **Never cut**: `-001`, `-006`, `-007`, `-014`, `-022`, `-024`, `-025`, `-027`, `P16-TST-001`.

## 21. Backlog (elaborated in [`BACKLOG.md`](BACKLOG.md) §Phase 16)

| ID | Title | Milestone | Depends on | DoD profiles |
|---|---|---|---|---|
| `P16-TSK-001` | The capacity model, the experiment register and its guard | M16.1 | Phase 15 confirmed by `P15-GATE` | `DOD-ARCH`, `DOD-DOC`, `DOD-TEST` |
| `P16-TSK-002` | Guards that hold what they are named for (`P15-REM-004`) | M16.1 | — | `DOD-TEST`, `DOD-DOC` |
| `P16-TSK-003` | The platform's own components watched: the edge, the migrator, the telemetry stack (item 43 (b)) | M16.1 | — | `DOD-OBS`, `DOD-BUILD` |
| `P16-TSK-004` | Quotas outside `finapp` and a graded scan of every overlay (items 43 (c), 44 (b)) | M16.1 | — | `DOD-BUILD`, `DOD-SEC` |
| `P16-TSK-005` | The edge, PostgreSQL and the broker rolled and rolled back on `kind` (item 43 (f)) | M16.1 | — | `DOD-BUILD`, `DOD-TEST` |
| `P16-TSK-006` | The load harness: k6 through the real edge, invariants sampled during load | M16.2 | `-001` | `DOD-BUILD`, `DOD-TEST`, `DOD-FIN` |
| `P16-TSK-007` | The workload baseline: the demonstration run and the engineering workload measured | M16.2 | `-006` | `DOD-TEST`, `DOD-DOC` |
| `P16-TSK-008` | Hot rows, locks, deadlocks and plans under load | M16.3 | `-007` | `DOD-FIN`, `DOD-TEST` |
| `P16-TSK-009` | The connection budget at ten and the pooler evaluated (C3) | M16.3 | `-007` | `DOD-ARCH`, `DOD-BUILD` |
| `P16-TSK-010` | Audit archival and the session sweep (item 18) | M16.3 | `-007` | `DOD-KERNEL`, `DOD-SEC` |
| `P16-TSK-011` | Posting once-ness made unique, then idempotency records swept (item 18, debt #61) | M16.3 | `-008` | `DOD-FIN`, `DOD-KERNEL` |
| `P16-TSK-012` | Partitioning and read-replica decisions by measurement | M16.3 | `-008` | `DOD-ARCH`, `DOD-FIN` |
| `P16-TSK-013` | The event pipeline at load: three brokers, lag, rebalance, broker loss, replay | M16.4 | `-007` | `DOD-EVENT`, `DOD-TEST` |
| `P16-TSK-014` | Ten instances at load (item 43 (g), acceptance B) | M16.5 | `-009`, `-013` | `DOD-TEST`, `DOD-FIN` |
| `P16-TSK-015` | Capacity calibration: the HPA, the abuse limits, the edge's bound and the role split | M16.5 | `-014` | `DOD-BUILD`, `DOD-SEC` |
| `P16-TSK-016` | Load shedding before the financial effect, and bulkheads | M16.6 | `-007` | `DOD-API`, `DOD-FIN` |
| `P16-TSK-017` | Sweeps that survive a poisoned row (item 42 (i), three debt rows) | M16.6 | `-001` | `DOD-KERNEL`, `DOD-OBS` |
| `P16-TSK-018` | Timeouts and retries classified; provider outages and slow providers (C12) | M16.6 | `-007`, `-019` | `DOD-SEC`, `DOD-FIN`, `DOD-TEST` |
| `P16-TSK-019` | The failure-injection toolkit and its safety boundary | M16.7 | `-006` | `DOD-BUILD`, `DOD-TEST` |
| `P16-TSK-020` | Instance, pod and node failures at load | M16.7 | `-014`, `-019` | `DOD-TEST` |
| `P16-TSK-021` | Network, DNS, connection and disk faults | M16.7 | `-019` | `DOD-TEST` |
| `P16-TSK-022` | The database failover drill and the socket timeout (item 43 (g), item 42 (h)) | M16.8 | `-007`, `-019` | `DOD-TEST`, `DOD-FIN` |
| `P16-TSK-023` | The data tier's availability: the SPOF register and the financial-operation matrix (item 43 (g)) | M16.8 | `-005`, `-013`, `-022` | `DOD-ARCH`, `DOD-TEST` |
| `P16-TSK-024` | Restore and point-in-time recovery with the post-recovery verification (item 44 (a)) | M16.9 | `-007` | `DOD-FIN`, `DOD-TEST` |
| `P16-TSK-025` | The disaster-recovery plan and exercise; RTO and RPO measured (C9) | M16.10 | `-023`, `-024` | `DOD-DOC`, `DOD-TEST`, `DOD-SEC` |
| `P16-TSK-026` | The multi-region assessment and Terraform variants (C10) | M16.11 | `-023`, `-025` | `DOD-ARCH`, `DOD-BUILD` |
| `P16-TSK-027` | Financial correctness under failure: the fifteen scenarios | M16.12 | `-013`, `-016`, `-018`, `-020`, `-021`, `-022` | `DOD-FIN`, `DOD-TEST` |
| `P16-TSK-028` | Security under scale and failure | M16.12 | `-014`, `-022` | `DOD-SEC`, `DOD-TEST` |
| `P16-TSK-029` | SLOs and error budgets validated under load; break-age counts (item 42 (a)) | M16.13 | `-003`, `-014` | `DOD-OBS` |
| `P16-TSK-030` | The service-extraction evaluation (ADR-0001's revisit) | M16.13 | `-008`, `-014`, `P15-REM-001` | `DOD-ARCH`, `DOD-DOC` |
| `P16-TSK-031` | The distributed-system audit and the capacity and operational review | M16.13 | `-002`, `-004`, `-010`…`-012`, `-015`, `-017`, `-025`…`-030` | `DOD-ARCH`, `DOD-DOC` |
| `P16-TST-001` | The integrated soak: ten replicas, the engineering workload, faults on a schedule | M16.13 | `-015`, `-025`, `-027` | `DOD-TEST`, `DOD-FIN` |
| `P16-TST-002` | The exit pass: the mandatory experiments' quick forms on the final tree | M16.13 | `-031`, `P16-TST-001` | `DOD-TEST` |
| `P16-DOC-001` | The Phase 16 exit review | M16.13 | all | `DOD-DOC` |

## 22. Dependency graph and waves

**Waves** (each wave's items depend only on earlier waves; every dependency-free item of a wave may run in parallel, the owner's
standing rule): **A** `-001`, `-002`, `-003`, `-004`, `-005` (the first wave: the owner's decided inputs need nothing of Phase 16);
**B** `-006`, `-017`; **C** `-007`, `-019`; **D** `-008`, `-009`, `-010`, `-013`, `-016`, `-018`, `-021`, `-022`, `-024`; **E** `-011`,
`-012`, `-014`, `-023` (and `P15-REM-001`, which the owner schedules before `-030`); **F** `-015`, `-020`, `-025`, `-028`, `-029`, `-030`;
**G** `-026`, `-027`; **H** `-031`, `P16-TST-001`; **I** `P16-TST-002`; **J** `P16-DOC-001`.

**Critical path**: **`-001` → `-006` → `-007` → `-009` → `-014` → `-020` → `-027` → `-031` → `TST-002` → `DOC-001`**; the
equal-length branch `-007` → `-022` → `-023` → `-025` → `-026` → `-031`.

**Every edge** is the backlog table's Depends-on column above; nothing else orders them.

## 23. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 16)

The six original criteria kept and made measurable, and one block per area of the owner's Part 28: performance and capacity;
sustained and peak load; concurrent financial operations; database scalability; Kafka throughput and recovery; resilience; failure
injection; high availability; backup and restoration; RTO and RPO; disaster recovery; the multi-region assessment; financial
correctness under failure; security; observability; operational readiness; documentation. Each block says whether it is
**executable here** or **designed-only** (C4).

## 24. ADRs (next free number ADR-0126; all `Proposed` at the transition)

ADR-0126 the workload model and the load-testing method (three workloads, k6 pinned outside the build, invariants during load) ·
ADR-0127 the failure-injection method (scripted primitives and Toxiproxy on `kind`, every experiment registered, the safety
boundary) · ADR-0128 recovery objectives and the post-recovery financial gate (the RTO/RPO tiers and their basis) · ADR-0129
availability and the multi-region stance (one writer, multi-AZ active-passive, no active-active writes, financial-operation
availability separate from service availability, read replicas never authoritative). The next free number after the transition is
ADR-0130. Decisions a task makes by measurement (a hot-account mechanism, partitioning, the pooler, a replica route, an extraction)
are written by that task as an ADR before anything is built.

## 25. What Phase 16 must NOT implement

A business capability; a change to a financial rule, treatment, lifecycle or invariant beyond §1's three decided exceptions; an
asynchronous balance, a balance changed outside a posting, a weaker isolation for money; a retry of an ambiguous financial call; a
cache in front of a financial decision; Redis or any new datastore; a read replica serving an authoritative read; active-active
writes; a microservice built; a Kubernetes `CronJob`, a leader election or any single-instance mechanism; an experiment against
anything but a cluster or stack the run created; an apply to a real account; a claim of production readiness, compliance or
certification. A reviewer finding any of it refuses the change.

## 26. Risks

| Risk | Answer |
|---|---|
| One development machine is the bottleneck, not the platform | §5.4's order names the generator and the node first; results always carry their workload's name and machine; production capacity never inferred from `kind` |
| A load test that proves throughput and misses a misstatement | Invariants sampled during load (§5.3); a correctness page fails the run whatever the latency |
| Chaos that is a demo, not an experiment | Every experiment registered with hypothesis, stop and success criteria; the guard refuses a result without them |
| Designed-only criteria presented as met | §1.1; the gate lists them by name; C4 |
| Hot-row "fixes" that weaken correctness | §6.4's never-list; an ADR before any mechanism |
| Scope inflation from the carried items | §17's placement; each in one task; the cut order |
| The machine's flakiness read as the platform's | Results classified; never retried into green; environment limits recorded with numbers |
| Postponed database cases hiding a defect under a green load run | C11's pre-flight; the exit records what the census can and cannot see |

## 27. The first task

**`P16-TSK-001` — The capacity model, the experiment register and its guard** — `READY`, marked by this transition. It writes
`docs/architecture/CAPACITY_MODEL.md` (§4's three workloads, W1–W8 with their derivation, the per-operation table with its resource
columns left for `-007`, §14's strategies, §6.2's budget arithmetic) and `docs/architecture/RESILIENCE_EXPERIMENTS.md` (§9.2's
experiments and §13's scenarios as a register: every field per experiment, result and remediation slots empty), a guard test holding
the register (every experiment carries hypothesis, affected units, expected behaviour, safety, success criteria, stop, and a result
slot; a filled result names its date, commit and artefacts; a failed result names a live backlog task; no experiment of a scenario is
missing), and re-points `PRODUCTION_READINESS.md`'s "a Phase 16 drill no backlog entry holds" cells and `THREAT_MODEL.md` §6 item 43
(g) at `-022` and `-023` by dated notes, without citing a finished task. First because every later task measures into the model or
records into the register; it depends on no owner decision and changes no runtime behaviour.
