# The Phase 15 → 16 Transition, Part A — the Phase 15 Completion Gate

**Conducted:** 2026-11-12, on branch `P15-GATE` from master `3d5089d9`
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §1 (the status model), §3 (the twelve universal exit criteria; Phase 15
is not a financial phase, so the supplement F1–F8 does not apply), §5 Phase 15 (the seven made measurable and the per-area
blocks); [`EXECUTION_PROTOCOL.md`](../EXECUTION_PROTOCOL.md); [`DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md); the owner's
transition prompt of 2026-11-12 (PART 1–9 and 29), and its rule "AVOID LONG RUNNING CHECKS AND TESTS FOR PHASE 15"
**Precedent:** [`PHASE_14_TO_15_TRANSITION.md`](PHASE_14_TO_15_TRANSITION.md) (Part A, the Phase 14 gate)
**Rests on:** the exit review [`PHASE_15_REVIEW.md`](PHASE_15_REVIEW.md) (`P15-DOC-001`) and the owner's acceptance of its
deviations A–G ("accept all", 2026-11-12)
**Not done here:** Phase 16's initialisation (Part B, branch `P16-INIT`) - this gate marks nothing of Phase 16 `READY` and
leaves `CURRENT_STATE.md`'s Current Phase, Current Task and the Phase 16 block to it.

| | Outcome |
|---|---|
| **Gate result** | **`PASS` - Phase 15 `COMPLETE`, confirmed with its recorded deviations** after one repair inside the gate (`P15-REM-005`). Every mandatory criterion holds or is a deviation the owner accepted on 2026-11-12: criteria 2, 5 and 7 `PARTIAL` with remediation entries; criterion 4, the database pass, universal criterion 7 and the ten-instance count resting on the owner's postponements (PI-004, PI-023, PI-024); the second person (owner item 4) |
| *"Can the platform continue operating correctly when instances are added, removed, restarted, or replaced during active financial workloads?"* | **`PASS`** - each of the four was done under a scripted financial workload on a real API server at three replicas, and the financial census was green after every step (Part 6); every arbiter is PostgreSQL's, so the answer does not change with the count. **Not proven at ten**: ten application replicas have never run, and the ten-way database cases of Phase 15's own arbiters are written and unrun - the owner's accepted deviation **B** ("`UNKNOWN` at ten"), which this gate does not upgrade |
| Findings | **CRITICAL 0. IMPORTANT 1** (G-1, the taxonomy guard red on master - **repaired**). **MINOR 6** (G-2…G-7): two remediation entries `P15-REM-006` and `-007` `PLANNED`, none blocking Phase 16; two stale status lines corrected by the flip; the rest recorded |
| Repairs | **`P15-REM-005`** - `ReportingPoolMetricsTest` reaches its stub driver by class name instead of through `DriverManager`, and the reporting pool honours the writer's `spring.datasource.driver-class-name`; its probe performed again on the new bytes and caught |
| Universal criterion 7 | **`NOT MET` by the owner's choice** (PI-023): no `database`, own-container or `kafka` tier has run since Phase 15 began - accepted deviation A |

**The evidence this gate rests on.** By the owner's rule no database, Kafka, cluster, image or container run was made, and no
whole-module task: the gate ran **named test classes only** (*Tests*). It rests on (a) the exit review's rulings, each with its
evidence, and its fresh named-guard runs; (b) the platform rehearsals' records in
[`OPERATIONS_RUNBOOK.md`](../../architecture/OPERATIONS_RUNBOOK.md) §11.28 (`kind`, 2026-11-09), §11.33 (rotations, 2026-11-11),
§11.35 (restore, 2026-11-11) and §11.34 (the trace, 2026-11-11), and
[`PRODUCTION_READINESS.md`](../../architecture/PRODUCTION_READINESS.md)'s migrator row (ten runs at once, 2026-11-08); (c) the gate
engineer's own reading of the manifests, Dockerfile, CI workflow, Terraform tree and code named below. **It is a gate over the
exit review, not a second review**: no independent audit was commissioned. The review's key `PASS` rulings were spot-checked
against the tree (*Spot checks*), and the items the review never named were read and ruled here (Redis, the autoscaler, the
disruption budget, mTLS and service identity, smoke tests, contract tests).

**What `PASS` means here.** The owner's prompt: a capability is not ready because a configuration file exists; it must be
valid, connected, deployable and tested to the extent required. A row is `PASS` only where a run or a test shows the
configuration doing what it says; where the only proof is a run the owner postponed it is `PARTIAL` and names the issue.
Phase 15's stated extent (`PHASE_15_PLAN.md` §1.1, C1) is **this repository, never a real environment**: Terraform verified and
never applied, the platform rehearsed on `kind`, no cloud account - so "deployable" is read as "deployed on a real API server"
for the manifests and "planned and tested against mock providers" for the cloud, and a row says which. **No document here
claims compliance with, certification under or attestation to any standard, or that anything is ready for production.**

---

## PART 1 — Phase 15 as an integrated production capability

| Capability | Result | Basis |
|---|---|---|
| Containerisation | `PASS` | multi-stage `Dockerfile`: a digest-pinned Temurin JDK builder, a digest-pinned distroless Java 21 `nonroot` runtime, the layered jar, `USER 65532:65532`, no shell and no `HEALTHCHECK` (C4); two targets, `runtime` and `migrator`; built reproducibly and readiness-checked in CI (`image`, `migrator-image`); run on `kind` 2026-11-09 and 2026-11-11. The final-tree build is CI's, unobserved here (PI-024's release run) |
| Docker image quality and security | `PASS` | no package manager or shell in the runtime; `ApplicationImageTest`, `MigratorImageTest`; Trivy over both images, ten carried ids equal to `THREAT_MODEL.md` §6 item 12 until 2027-01-31; `image-scan.sh`'s plants (a vulnerable base, a secret in a layer and in the environment) each fail the job |
| Kubernetes architecture | `PASS` | namespaces under Pod Security `restricted`, a `LimitRange` and `ResourceQuota`, `default-deny` both ways, per-hop policies, two edge controllers with file-provided routes (C9 in another form); every policy with its plant (`manifest-check.sh`, `KubernetesManifestsTest`, `NamespaceSecurityManifestsTest`); the admission, network-policy and edge suites green on `kind` (§11.28). Its last `manifest-check.sh` run predates `P15-TSK-032`'s final `deploy/` changes (the review's note) |
| Service deployments | `PASS` | the units that exist (C5): one `finapp-app` Deployment, every role per replica, and a `finapp-migrator` Job per release; deployed, rolled, killed and rolled back on `kind` |
| Multi-instance deployment configuration | `PASS` | no `replicas:` in the Deployment (the autoscaler owns the count, `KubernetesManifestsTest:112`); `maxUnavailable: 0`, `maxSurge: 1`; a zone spread `DoNotSchedule` and a host spread; three replicas ready throughout every rehearsed rollout |
| Health checks | `PASS` | readiness is the application's own verdict, the database and the schema-version guard included (ADR-0125 as corrected): a replica of N started ahead of its Job stayed not-ready with no consumer membership and nothing published (§11.28's *hold* step); the reporting pool deliberately outside readiness (`ReportingPool`'s javadoc) |
| Readiness and liveness probes | `PASS` | startup, liveness and readiness on the management port `8081`, which no Service exposes (`deploy/base/service.yaml` names `8080` alone; `ManagementPortTest`); the scrape and the kubelet reach `8081` only, shown on `kind` |
| Graceful shutdown | `PASS` | `server.shutdown: graceful`, `timeout-per-shutdown-phase: 30s` (`application.yaml:20`, `:102`), `terminationGracePeriodSeconds: 117`, a `preStop` sleep of 10 s; forty lifecycle components stopped in a held order and the 37 schedules and the consumers drained (`ShutdownOrderTest`); 0 failed requests across three rolling updates, a rollback with an eviction, and five rotation rolls |
| Resource requests and limits | `PASS` | requests `500m`/`2Gi`, limits `2`/`2Gi` (memory request equal to limit); a pod stating none refused by the `LimitRange` and the quota on `kind`; the figures are reference values, calibrated by Phase 16's load (the autoscaler's comment) |
| Horizontal scaling configuration | **`PARTIAL`** | the `HorizontalPodAutoscaler` is valid, applied on `kind`, holds the count at its minimum of 3, and is **bounded at 7 by the connection budget** (`7 + 2 × maxSurge + 1 = 10 = FINAPP_DB_INSTANCES`, the production overlay's `100`/`12`; `KubernetesManifestsTest:219-242`); scale-down one pod per 120 s. **No scaling decision it made was ever observed**: the rehearsal installs no metrics source, so it never scaled on CPU (G-3, `P15-REM-007`; Phase 16's load) |
| Infrastructure as code | `PASS` in its stated extent | nine modules and two roots: `fmt`, `validate`, `tflint`, `trivy config` and `terraform test` with mock providers (twelve test files) in CI's `iac` job; no state, no secret, **never applied** (C1) |
| CI/CD pipelines | **`PARTIAL`** | seventeen jobs, each a pinned script (`.github/workflows/ci.yml`); `CiWorkflowIsLockedDownTest`, `SecurityScansAreWiredTest`; **no CI run is read in this process** (the owner's standing instruction), and CI's `rehearsal` and `admission` jobs have not yet run on CI's runner (§11.28, §11.32); the second person (owner item 4, deviation D) |
| Artifact management | `PASS` in another form | promotion by digest (`promote.sh`, `Promotion.java`, `PromotionTest`), Cosign signing with SLSA provenance and SBOM attestations, admission verifying them (`deploy/admission/`, the `admission` job, §11.32); demonstrated with a key and a registry made for the run (C7); the registry and its identity are Terraform's, never applied (item 35) |
| Database migration deployment | `PASS` | the migrator Job (`restartPolicy: Never`, `backoffLimit: 2`, a 900 s deadline) ordered before the rollout (`components/delivery-order`); ten runs of the image at once, each migration applied once (2026-11-08); the expand-only lint and the tightening rules (`MigrationsAreExpandOnlyTest`, `TighteningsOverOldRowsTest`); N − 1 serving on N's schema (§11.28); a failed migration rehearsed on containers, two of four procedures (§11.4; the rest PI-024) |
| Secrets management | `PASS` | `SecretInventoryIsCompleteTest` both ways (54 rows); no `kind: Secret` anywhere under `deploy/` (read at this gate); two `ExternalSecret`s over a store; a database password and an HMAC secret rotated on `kind` with no failed request (§11.33) |
| Key management | `PASS` | key rings over the eight field-level keys and the callback window (ADR-0123); the MFA ring rotated on `kind`, a replaced key's check value refusing the replica (§11.33); KMS keys are Terraform's, never applied; revocation per class written but not held by a test (`P15-REM-004` (e)) |
| Encryption | `PASS` in another form | in transit: `verify-full` to PostgreSQL, `SASL_SSL` with SCRAM to Kafka, TLS at the edge with HSTS, the provider hop's TLS guard - each refusing startup off loopback without it (`TransportSecurityGuard`, `KafkaTransportGuardTest`), each shown on `kind`; at rest: field-level ciphers under key rings; storage encryption by the managed services' KMS keys in Terraform, never applied |
| Network security | `PASS` | `default-deny` (`podSelector: {}`, both directions, read at this gate); every hop's policy shown on `kind` with kube-router as the engine; kindnetd unanswered (item 30) |
| Service-to-service authentication | `PASS` in another form | one deployable, so no in-cluster service-to-service call exists; each hop that leaves a pod authenticates: the database role per fleet (alternating users), a SCRAM identity per principal with ACLs as code, HMAC-signed provider callbacks, the provider hop's own credentials over TLS; the workload's cloud identity in the Terraform `kubernetes` module, never applied. **mTLS and a mesh: `N/A`, decided against** (C9; `THREAT_MODEL.md` §7) |
| Observability | `PASS` | the exit review's; the collector at two replicas behind a disruption budget (`deploy/observability/base/collector-workload.yaml`) |
| Logging | `PASS` | structured records over OTLP to the collector, scrubbed by its allow-list; no exported span or log line carried any of 104 needles (§11.34, 2026-11-11). PI-022 (an amount in an exception's message, kept by the pod's console) recorded |
| Metrics | `PASS` | every gauge off the scrape thread (`NoGaugeReadsOnTheScrapeTest` with its plants); every planned meter of `PHASE_15_PLAN.md` §11.2 registered (`PlannedMetersExistTest`, armed by the flip and green, *Tests*) |
| Distributed tracing | **`PARTIAL`** | criterion 7: one payment, its settlement file and a reconciliation run found by correlation, but no provider span, no ledger posting span and no causation id (`P15-REM-003`) |
| Alerting | `PASS` | 218 rules loaded on `kind` (§11.38's dev5); each alert names a runbook section (`AlertCatalogueTest`); `amtool check-config` with its bad-routing plant; promtool cases firing and silent (the CI `alerts` job unobserved; `P15-REM-004` (d)) |
| SLO/SLI definitions | `PASS` | criterion 3: eight flows, ten objectives, a fast pair (14.4) paging and a slow pair ticketing (`infra/prometheus/rules/slo.yml`: 21 lines carrying 14.4, read at this gate); correctness never budgeted (`correctness.yml`, nine `for: 0s`) |
| Database operations | **`PARTIAL`** | the migrator, the budget guard and policy (peak 39 of 48 on `kind`), the reporting role, restore with the financial verification; **the retention sweeps and every Phase 15 database case never ran on PostgreSQL** (PI-023); the failover a Phase 16 drill (§11.40) |
| Kafka operations | **`PARTIAL`** | `SASL_SSL` with SCRAM, topics and ACLs as code, retention under the dedupe window, the cooperative assignor, the handler window replayed exactly once on `kind`; `KafkaRebalanceKafkaTest` owed (PI-023), the broker outage and dead letters on a cluster (PI-024) |
| Redis operations | **`N/A`** | the platform uses no Redis: no client in any build file or in the version catalogue's libraries, none on the classpath (`TransportSecurityGuard.java:51`), sessions in PostgreSQL (ADR-0030, `NoProcessLocalSessionStateTest`), abuse limits in `platform.window_counter` (`P15-TSK-022`); C3 "Redis not deployed". Two Redises exist and neither is the platform's: Argo CD's own cache, pinned by digest (`controller-check.sh`), and an unused service in the local `compose.yaml` (G-2, `P15-REM-006`) |
| Backup configuration | `PASS` in another form | criterion 6: a backup taken while the fleet wrote, restored and verified (trial balance zero, the census 854 of 854, the planted removal refused); managed point-in-time recovery in Terraform with its test; two clauses over empty data (owner items 38 (c) and 44 (a)) |
| Operational runbooks | **`PARTIAL`** by the owner's choice | criterion 4: 22 of 46 sections rehearsed, §4-§10 unsampled (PI-024, deviation A) |
| Rollback procedures | `PASS` | rollback by digest on `kind` with an eviction mid-way, 402 s, 862 requests, 0 failed, census green (§11.28); a rollback past a contract marker refused by the promotion (`PromotionTest`); migrations forward-fix only (§11.4) |
| Testing | **`PARTIAL`** | universal criterion 7 `NOT MET` by the owner's choice (PI-023); the hermetic, slice and architecture guards held per task; the named classes of this gate fresh (*Tests*) |
| Documentation | `PASS` | after the exit review's corrections and this gate's (G-5, G-6) |

**Against the backlog and the exit criteria.** 46 items: 43 tasks `COMPLETE`, `P15-TST-001` and `P15-TST-002` `POSTPONED` by the
owner (PI-004, PI-023), `P15-DOC-001` `COMPLETE`. The seven, the per-area blocks and the universal criteria stand as the exit
review ruled them; this gate confirms each against the tree and changes none.

## PART 2 — the container and Kubernetes audit

| Check | Result | Basis |
|---|---|---|
| Production build stages | `PASS` | builder and runtime separated; the runtime receives the four layers and nothing of the JDK |
| Non-root | `PASS` | `USER 65532:65532` in both targets; the pod's `runAsNonRoot: true`, `runAsUser`/`runAsGroup: 65532`, `seccompProfile: RuntimeDefault` |
| No unnecessary tools | `PASS` | distroless: no shell, no package manager; `allowPrivilegeEscalation: false`, `privileged: false`, `readOnlyRootFilesystem: true`, every capability dropped |
| No secrets in images | `PASS` | the plants of `image-scan.sh`; the history scan; every credential delivered at runtime from the store (`envFrom: finapp-app-secrets`, an `ExternalSecret`) |
| Health checks represent readiness | `PASS` | Part 1 |
| Requests and limits reasonable and documented | `PASS` | Part 1; the connection budget is the documented ceiling (§11.14) |
| Graceful termination; in-flight requests | `PASS` | the `preStop` sleep lets the endpoint leave the Service before the port stops; the graceful phase finishes in-flight requests; a killed replica's claim is reclaimed by another under its lease (§11.28's *pod kill*: a payout held at the provider, one payout key, 17 attempts retried with the same key, none unresolved) |
| Rolling deployments and version compatibility | `PASS` | expand-only migrations; N − 1 on N's schema; a handler added in N proven by the window and its audited replay (166 of 331 records committed unseen, each applied exactly once); the configuration fingerprint alert for a mixed fleet outlasting a rollout (`for: 80m`) |
| Pod disruption and availability | `PASS` | `PodDisruptionBudget` `maxUnavailable: 1` (a drain never takes the fleet below N − 1), exercised through the Eviction API mid-rollback on `kind`; the collector and both edge controllers at two replicas with their own budgets |
| Persistent state not on ephemeral containers | `PASS` | no volume but a 256 MiB `emptyDir` at `/tmp`; a read-only root; no production class writes a file (searched at this gate: no `createTempFile`, `FileOutputStream` or `Files.write` under any `src/main`); all state in PostgreSQL and Kafka outside the cluster (C3) |
| Multiple instances supported | `PASS` | Part 6 |
| Horizontal scaling where appropriate | **`PARTIAL`** | Part 1 (G-3) |

**Components that still run as one instance.** None of the platform's services. What runs once, and why that is correct:
**the migrator Job** - once per release by design, Flyway's lock serialising concurrent runs (ten at once proven); **the
database primary** - the one authority by design (C3), made available by the managed service's standby (Multi-AZ in
the `postgres` module, never applied) and drilled as a failover in Phase 16 (§11.40); **the `kind` overlay's PostgreSQL, Kafka,
Prometheus, Tempo, Loki and Alertmanager** - test-only copies, never a production unit (C3, item 37 (a)).

## PART 3 — the security hardening audit

| Area | Result | Basis |
|---|---|---|
| Container security | `PASS` | Part 2 |
| Kubernetes RBAC | `PASS` | the application's service account with no token mounted (`automountServiceAccountToken: false`); people read-only, never a `Secret` (`deploy/base/rbac-humans.yaml`); controllers' cluster roles from a named list with plants (`ControllerRenderContractTest`); break-glass bounded (§11.25) |
| Network policies | `PASS` | Part 1 |
| Service accounts | `PASS` | one per workload; the controllers' bound only to their own roles (`controller-check.sh`'s plants) |
| Secrets management | `PASS` | Part 1 |
| Encryption at rest and in transit | `PASS` in another form | Part 1 |
| Key rotation | `PASS` | rotation as an act the fleet follows by a rolling restart (C6), five rotation runs at three replicas, 0 failed requests (§11.33); the TLS-certificate and signing classes' rows owed (`P15-REM-004` (e)) |
| TLS certificates | `PASS` | cert-manager on `kind`: a renewal served on a fresh connection with no controller restarted, its rollback, its expiry series and alerts (`CertificateExpiringSoon`, `CertificateExpiring`) |
| Service identity; mTLS | `PASS` in another form; mTLS `N/A` | Part 1 |
| OAuth2/OIDC | `N/A` for this phase | customer identity is the platform's product (ADR-0030, ADR-0031); workforce OIDC deferred (C13; item 30 (e)) |
| MFA | `PASS` | step-up on the ruled doors (`RuledDoorsStepUpTest`, `RoutePermissionRegisterTest`); a factor required before a money-redirecting role (`RoleGrantNeedsAFactorTest`); refused step-ups recorded (`OperatorActorTypingTest#aRefusedStepUpIsRecorded`) |
| Privileged access | `PASS` | operators typed `EMPLOYEE` per request (C13 in another form) and refused on the public surface (`OperatorsRefusedOnPublicTest`); the access review (`PrivilegedAccessReviewTest`); PI-020 (both roles of a pair on one identity) recorded |
| Database access controls | `PASS`; the database proofs owed | the application role without `TRUNCATE` on 51 more append-only tables; the `SELECT`-only reporting role and its capability check on every connection (`ReportingPoolCapabilityTest`; its database twin owed, PI-023); PI-016 (a balanced entry written with the application's credential) recorded |
| Kafka access controls | `PASS` | per-principal ACLs as code; a second user with exactly the first's 59 ACLs, the first revoked and refused, on `kind` (§11.33) |
| Redis security | `N/A` | Part 1 |
| API security | `PASS` | every mutating route permissioned and registered (`RoutePermissionRegisterTest`); abuse limits counted in PostgreSQL across replicas (the counted three-replica case database-tier, PI-023); a forged `X-Forwarded-For` counted in one bucket on `kind` |
| Dependency vulnerabilities | `PARTIAL`, accepted | the SBOM scan; carried findings each in item 12 with an expiry; three Semgrep baseline rows accepted by the owner until 2027-06-30 (deviation E) |
| Container vulnerabilities | `PASS` | Part 1 |
| Secret scanning | `PASS` in another form | the history scan in CI (`secret-scan.sh`); its own plant is Phase 0's manual procedure, not re-run in Phase 15 (the review) |
| Logging of sensitive data | `PASS` | Part 1; `MoneyReachesNoTextSinkTest` holds the residue PI-022 names |

**Implementation readiness is not assessment.** These are technical controls with their evidence, built and in part rehearsed in
this repository. Nothing here is, or claims, an independent assessment, a certification or an attestation under PCI DSS, ISO
27001, SOC 2 or any standard (`THREAT_MODEL.md` §8; the exit review's wording check found none; this gate writes none).

## PART 4 — CI/CD and deployment

| Stage or concern | Result | Basis |
|---|---|---|
| Source → build → tests | `PASS` | `build` runs `./gradlew build`, every tier but `database` and `kafka` (`finapp.java-conventions.gradle.kts`); `migrations` applies every history to a real PostgreSQL |
| Static analysis | `PASS`, its baseline accepted | `sast` (Semgrep, pinned rules, a dated baseline) |
| Security scanning | `PASS` | `secret-scan`, `dependency-scan`, `image`, `migrator-image`, `iac`, `manifests`, `controllers` |
| Artifact creation, storage, provenance | `PASS` in another form | Part 1 |
| Deployment | `PASS` on `kind` | the GitOps Applications by digest, the delivery order (bootstrap, migrator, Deployment), rehearsed (§11.36) |
| Smoke tests | `PASS` in another form | the rehearsal's own *smoke* step (register, authenticate, a card top-up, a transfer, a checkout, a payout, through the real edge) after every deploy on `kind`; the read-only post-deploy smoke (`post-deploy-smoke.sh`, health, build identity by digest, the trial balance) judged by `PromotionTest`'s refusals and never run against an environment, none existing |
| Release verification | `PASS` in another form | a staging smoke record required before production's promotion of the same pair (`Promotion.java`) |
| Build reproducibility | `PASS` | one digest per commit on proven reproducibility (ADR-0122 as corrected) |
| Dependency management | `PASS` | the version catalogue, lockfiles and pinned actions by commit; the infrastructure images pinned (`pinned-images`, `verifyInfrastructureVersions`) |
| Automated and integration tests | **`PARTIAL`** | universal criterion 7 (PI-023) |
| Contract tests | `PASS` | the API (`OpenApiContractTest`), the event catalogue's compatibility (`EventCompatibilityTest`, `HandlerSetDiffTest`), the providers' simulators (`SimulatedFxProviderContractTest`, `SimulatedCorridorContractTest`, the bureaus'), the rehearsal harnesses (`KindRehearsalContractTest`, `DeliveryRehearsalContractTest`, `PlatformRehearsalContractTest`) |
| Deployment approvals; environment promotion | **`PARTIAL`**, accepted | promotion by digest staging → production; **the second person**: `CODEOWNERS` names one, no branch rule applied (owner item 4, deviation D) |
| Database migrations; backward compatibility; rollback | `PASS` | Part 1 |

**Deployment and migration sequencing cannot silently corrupt financial state.** The migrator runs before the rollout and a
replica of N is held not-ready until its schema is present (the schema-version guard; §11.28's *hold*); migrations are
expand-only, a tightening over old rows refused without its data fix (`TighteningsOverOldRowsTest`); N − 1 serves on N's schema
(rehearsed); a rollback past a contract marker is refused (`PromotionTest`); a failed migration is fixed forward, never edited
(`MigrationSettingsAreForwardOnlyTest`); financial history is append-only by trigger in every module whatever the code version,
and every financial `FINANCIAL` property states its mixed-fleet safety (`CONFIGURATION_REGISTER.md`; the statement held to exist,
not to be right, `P15-REM-004` (f)). **`PASS`.**

## PART 5 — observability and operations

| Concern | Result |
|---|---|
| Structured logs; metrics; traces; correlation ids | `PASS`; the trace's provider and ledger legs and causation `PARTIAL` (`P15-REM-003`) |
| Dashboards | `PASS` in another form - Grafana in `compose` alone (ADR-0124 as corrected); a real environment's back ends decided (item 37 (a)) |
| Alerts; SLOs; SLIs; error budgets | `PASS` (criterion 3) |
| Business and financial metrics | `PASS` - the trial balance per currency, projection drift, GL = ledger, journal entries unsourced (`JournalEntryUnsourced`), the flows' meters; correctness pages on its first sample |
| Service, queue/consumer, database and infrastructure health | `PASS` - `InstanceDown` per instance, consumer lag and the outbox stalled, pool saturation and starvation, the collector's own alerts |
| The financial trace, customer → reconciliation | **`PARTIAL`** - criterion 7 (`P15-REM-003`) |
| No secrets, credentials or unnecessary PII in telemetry | `PASS` - the collector's allow-list and scrub, the needle sweep; PI-022 recorded |
| Important alerts have documented responses | `PASS` - `AlertCatalogueTest#everyAlertNamesARunbookSectionThatExistsAndNamesIt` |

## PART 6 — the mandatory multi-instance audit

| Concern | Result | How it is decided |
|---|---|---|
| Concurrent service execution | `PASS` by design; its Phase 15 counts owed | every arbiter is PostgreSQL's - unique keys, conditional edges, `FOR UPDATE SKIP LOCKED` claims under leases, advisory keys; no correctness on a JVM-local lock, set or scheduler (`NoSingleInstanceAssumptionRulesTest`, architecture); the counted ten-way cases of Phases 1-14's arbiters ran green in their phases' passes; **Phase 15's own** (the window counter, the retention sweeps, the source census, the dead-letter doors) are written and unrun (PI-023) |
| Horizontal scaling | `PASS` at three replicas and a surge; ten never run | Part 1 (G-3) |
| Instance startup and shutdown | `PASS` | Part 2 |
| Rolling deployments | `PASS` | three counted rollouts, 0 failed requests, census green after each |
| Consumer rebalancing | `PASS` on `kind`; its broker test owed | every partition owned and none behind after each step; an offset commit refused during a rebalance now retried (`KafkaEventReceiver`, found by the rehearsal); `KafkaRebalanceKafkaTest` (PI-023) |
| Connection management; database connection limits | `PASS` | `ConnectionPoolSizingGuard` and the budget as policy; peak 39 of 48 through a rollout, the fleet held at exactly its budget with an operator admitted (49 of 60) |
| Distributed scheduling | `PASS` | 37 leaderless schedules, each claiming its rows in the database (`DISTRIBUTED_EXECUTION.md` §3) |
| Shared-state management | `PASS` | no process-local session or idempotency state (`NoProcessLocalSessionStateTest`) |
| Kafka partition and consumer behaviour | `PASS` | the inbox dedupes per handler; the cooperative assignor; the handler window replayed exactly once |
| Idempotent recovery; retries | `PASS` | §11.28's *pod kill*: the payout's claim completed by another replica, one key at the provider, every retried request resolved under its key |
| Graceful degradation | `PASS` | the reporting pool outside readiness; a refused broker or database fails readiness rather than serving wrong |
| Instance failure; pod eviction | `PASS` | the forced kill with no grace; the eviction mid-rollback through the Eviction API |
| Network interruption | `PASS` by design; not injected | provider ambiguity resolved to `UNKNOWN` and settled by inquiry (Phases 5-10); no partition was injected in Phase 15 - Phase 16's chaos |

**"Can the platform continue operating correctly when instances are added, removed, restarted, or replaced during active
financial workloads?" → `PASS`.**

- **Proven at three replicas** (one `kind` cluster, a scripted workload of every money-moving flow, the financial census - the
  trial balance zero per currency and per entry, one effect per idempotency key, every balance explained by the journal, the
  outbox drained, nothing parked or dead-lettered, no orphaned lease, every partition owned, GL = ledger - green after **every**
  step): **replaced** - a rolling update N − 1 → N (878 requests, 0 failed), a rollback by digest (862, 0 failed) and a re-roll
  (869, 0 failed), mixed versions serving together throughout; **added** - each rollout's surge replicas joining a working fleet
  (up to five pods at three replicas), and five rotation rolls (2026-11-11); **removed** - an eviction through the disruption
  budget mid-rollback; **restarted** - a forced kill with no grace and a payout in flight, its claim completed by another replica
  with one key at the provider (2026-11-09, release N `18326010`).
- **Proven at ten**: the migrator, ten runs at once, each migration applied once (2026-11-08). Nothing else.
- **By design at N**: every deciding instant and arbiter is the database's, so a correctness argument does not mention the
  count; the application's ceiling is **seven** replicas, the connection budget's (`FINAPP_DB_INSTANCES=10`), so "ten instances"
  in the owner's sense needs the budget, or a pooler, changed first - Phase 16's (G-3).
- **Not proven, and not claimed**: ten application replicas at load; the ten-way database cases of Phase 15's new arbiters
  (PI-023); the census on the final tree, 59 production Java files after `18326010` (PI-024); the storm's counts (PI-004). The
  owner accepted these on 2026-11-12 as **deviation B - "`UNKNOWN` at ten"** - with ten instances at load written into Phase 16.
  This gate's `PASS` answers the question asked - instances changing under a financial workload - on counted runs; **it is not a
  `PASS` at ten, and it does not replace B**.
- **The owner's ruling on this reading** (2026-11-12, asked at the merge, since the transition's prompt says `UNKNOWN` keeps
  the phase open): **`PASS`**, as counted at three replicas for instances added, removed, restarted and replaced, with ten
  instances deviation B and Phase 16's ten-instance load task closing it (with the pooler or budget change the seven-replica
  ceiling needs, `P15-REM-007`).

## PART 7 — financial safety

| Scenario | Result | Why no duplicate, lost or silent effect |
|---|---|---|
| Deployment during active financial transactions | `PASS` (counted) | three rollouts under the workload, census green |
| Restart during ledger posting | `PASS` by design | an entry and its lines commit in one transaction under a deferred balance trigger (ledger `V004`); a crash rolls the whole entry back, and the retried command finds its idempotency record or none |
| Restart during payment processing | `PASS` (counted) | §11.28's pod kill with a payout held at the provider after its effect: one payout, one key, one posting |
| Restart during settlement | `PASS` by design (Phase 8's counted cases) | file reception and parsing under leases with reclaim; a line accepted once by its key |
| Restart during reconciliation | `PASS` by design (Phase 8's counted cases) | chunks claimed `SKIP LOCKED` under a lease, completed only by their holder; a break born once per subject |
| Duplicate event processing after restart | `PASS` (counted) | the inbox's per-handler dedupe; the handler window's records each applied exactly once across two rollouts |
| Database migration during active workloads | `PASS` | Part 4 |
| Provider timeout during deployment | `PASS` by design | an ambiguous outcome is `UNKNOWN`, resolved by inquiry and never by a second instruction; a payout held at the provider survived the kill |
| Kafka consumer rebalancing | `PASS` on `kind`; its broker test owed (PI-023) | Part 6 |
| Rolling deployment with mixed versions | `PASS` (counted) | N − 1 on N's schema; the handler window; the fingerprint alert |

**Financial history stays immutable** (append-only triggers in every module, whatever the code version, and `TRUNCATE` refused
on 51 more tables by `P15-TSK-040`); **idempotency survives instance restarts** (its records are rows, its leases expire into
another replica's reclaim); **critical operations keep their transactions** (Phase 15 changed no money path's boundary: its
financial-code changes - `-033`'s two fixes, `-038`'s five stores on the database's clock, `-040`'s refusals, `-035`'s census -
were gated by their tasks, their database cases owed, PI-023); **events replay safely** (the audited replay over a window, a
replay beyond the dedupe window refused); **reconciliation detects differences** (Phase 8's machinery, and `-035`'s source census
tying every journal entry to its domain record); **recovery creates no duplicate effect** (the restore's census 854 of 854 and the
planted removal refused). Owner-postponed defects that touch money stand as recorded, each "before production use": PI-016,
PI-018, **PI-019 (a double credit, surfaced by reconciliation, not silent)**, PI-021.

## PART 8 — tests, findings and the repair

### Findings

**CRITICAL - none.** Phase 15 moves no money; no path found creates or destroys money, overwrites history or lets one person
approve their own money-moving act.

**IMPORTANT - one, repaired.**

- **G-1 - `TestTaxonomyTest` red on master** (`P15-REM-005`). `#everyTestClassDeclaresATierAtLeastAsHeavyAsItNeeds` failed on
  `ReportingPoolMetricsTest` ("needs DATABASE but is in UNIT") since `11ee8da3`: the test registered its stub JDBC driver through
  `java.sql.DriverManager`, which `TestTier`'s database signature reads as acquiring a database. Rated IMPORTANT, not MINOR,
  because the guard is in the `architecture` tier and `test` excludes only `database` and `kafka`, so **CI's `build` job -
  `./gradlew build` - is red on master** until it lands: an operational defect of the delivery pipeline, though no production
  behaviour. **Repaired** (option (i) of the entry): the test names the stub as the writer's driver class
  (`spring.datasource.driver-class-name`) and HikariCP instantiates it by name, so the test's bytecode no longer references
  `DriverManager`; the stub became a public class with its count on the class. The reporting pool needed a seam to reach it, and
  the seam is the writer's own setting: `ReportingPoolBeans` passes `spring.datasource.driver-class-name`, where set, to a new
  `ReportingPool` constructor, which sets it on the pool, so the reporting pool reaches the database through the same driver as
  the writer - which it did not before, when a driver class was configured. **No shipped setting configures one**
  (`application.yaml`, every overlay: searched at this gate), so production still resolves the driver from the URL for both
  pools; the existing constructor is kept, delegating with none. **Tests**: `TestTaxonomyTest` and the four hermetic
  `ReportingPool*Test` classes green (*Tests*). **Probe performed again** on the new bytes: the binding removed
  (`setMetricsTrackerFactory` deleted) - caught, 3 tests 1 failed, `#itsMetersAreTheWritersOnes`, the other two green, which also
  shows the stub reached by class name; restored byte-identical (`MUTATION_TESTING.md` §2, a new row).

**MINOR** (each a remediation entry, corrected here, or recorded):

- **G-2 - an unused Redis in `compose.yaml`.** The local stack runs `redis:8.10.1` with a comment calling it "cache,
  coordination, rate limits, ephemeral state", and `verifyInfrastructureVersions` pins it; nothing in the platform uses Redis
  (Part 1), rate limits are PostgreSQL's (`P15-TSK-022`), and C3 says Redis is not deployed. A reader is told a store exists for
  rate limits that does not hold them. `P15-REM-006`.
- **G-3 - the autoscaler has never scaled, and its ceiling is seven.** On `kind` the `HorizontalPodAutoscaler` held the count at
  its minimum only: no metrics source is installed, so no CPU-driven scale-up or scale-down was observed; its bound of seven
  replicas is the connection budget's, so the owner's "10+ instances" is above what the platform is configured to run.
  Correctness is not at stake (scaling adds or removes a replica, which Part 6 counts); capacity is. `P15-REM-007`, done by
  Phase 16's ten-instance load task.
- **G-4 - in-cluster OTLP is plain HTTP.** The application exports to `http://finapp-collector…:4318`. Decided, not a defect:
  one egress held by policy and by `THREAT_MODEL.md` T46's rows, the record scrubbed before it leaves the collector, no mesh
  (C9, §7). Recorded so that a reader of "encryption in transit" sees the one hop without TLS.
- **G-5 - Phase 15's status line read `READY` for the whole phase** (`CURRENT_STATE.md`, 2026-11-07 through the flip): no task
  moved it to `IN_PROGRESS` - the stale-status class `P13-DOC-001` named, repeated as at Phase 14. Corrected by the flip, the old
  text kept as a read-note.
- **G-6 - `BACKLOG.md`'s Phase 15 header still read `READY`.** Corrected by the flip.
- **G-7 - nothing reads CI's results.** Criterion 2's "green in CI on the final tree" is not observable here, and CI's
  `rehearsal` and `admission` jobs have never run on CI's runner (§11.28, §11.32). Recorded, not an entry: reading CI is the
  owner's standing instruction not to do, and the final-tree release run is PI-024's.

### The remediation entries created (`PLANNED`; none blocks Phase 16)

| ID | Horizon | Subject |
|---|---|---|
| `P15-REM-005` | before the transition gate's guard run | **`COMPLETE`** at this gate (G-1) |
| `P15-REM-006` | with the next change to `compose.yaml` | the local Redis removed with its pin, or kept with a comment saying no platform code uses it (G-2) |
| `P15-REM-007` | Phase 16, with the ten-instance load task | the autoscaler observed scaling on a real metric, both ways, under load; the ceiling raised past seven only with the budget or a pooler, and the budget's arithmetic re-proved (G-3) |

### Spot checks of the exit review's `PASS` rulings (read against the tree at this gate)

Eighteen, every one as the review says: (1) both `Dockerfile` bases pinned by digest, distroless `nonroot`, `USER 65532:65532`;
(2) the Deployment's `maxUnavailable: 0`, `maxSurge: 1` and no `replicas:`; (3) the autoscaler's `minReplicas: 3`,
`maxReplicas: 7` and the budget's formula; (4) the disruption budget's `maxUnavailable: 1`; (5) the probes on `management` and
the Service exposing `8080` alone; (6) `default-deny` with an empty selector, both directions; (7) no `kind: Secret` under
`deploy/`; (8) the production overlay's `FINAPP_KAFKA_SECURITYPROTOCOL=SASL_SSL` and the budget literals `10`/`100`/`12`; (9)
`CODEOWNERS` naming one person (item 4); (10) `infra/semgrep/baseline.tsv` at 61 rows; (11) nine Terraform modules with twelve
test files; (12) `slo.yml`'s fast pairs and `correctness.yml`'s nine `for: 0s`; (13) the migrator Job's `restartPolicy: Never`,
`backoffLimit: 2` and deadline; (14) the CI jobs the review names, all present; (15) the graceful shutdown settings and the grace;
(16) no Redis client anywhere; (17) the collector and both edge controllers at two replicas behind budgets; (18) §11.28's last
record's numbers as the review quotes them.

## The recorded deviations, re-read

| Deviation (accepted 2026-11-12) | The gate's reading |
|---|---|
| A - PI-004 (the storm), PI-023 (the database pass and every case owed to it), PI-024 (the platform rehearsals) | Accurately recorded; each `PARTIAL` or `NOT MET` above names one of them, none hides a defect the review did not name |
| B - the ten-instance answer `UNKNOWN` at ten | Re-read in Part 6: true, and not upgraded by this gate's `PASS` to the narrower question |
| C - criterion 1 in its measurable form | Stands; the residuals re-read at the Phase 16 exit; `ThreatModelIsCompleteTest`'s exit rules green at the flip (*Tests*) |
| D - the second person (owner item 4) | Stands: `CODEOWNERS` names one person |
| E - item 12's three Semgrep baseline rows | Stands, until 2027-06-30 |
| F - item 44 (a) and (b) | For Part B to write into Phase 16: (a) the restore's lending and BNPL proofs into the post-recovery verification drill; (b) `trivy config` over the rendered manifests in Phase 16's first wave |
| G - `P15-REM-001`…`-005` at their horizons | `-005` landed here, before the guard run; `-001`…`-004` `PLANNED` |
| PI-016…PI-022 - owner-postponed defects | Each "before production use" (PI-017 before the first real customer launch); none newly found to be worse |

## Universal criteria and the Phase 15-specific criteria

Universal criteria 1-3, 5, 6, 8-10 and 12 **`PASS`** as the review ruled them; **4 and 11 `PARTIAL`**; **7 `NOT MET` by the
owner's choice** (PI-023). Criterion 9 is `PASS` again after this gate's records. The seven: **1 `PASS`** (measurable form,
deviation C), **3 `PASS`**, **6 `PASS` in another form**, **2, 5, 7 `PARTIAL`** (deviation E, `P15-REM-002`, `P15-REM-003`),
**4 `PARTIAL` by the owner's choice** (PI-024). Per area: seven `PASS`, five `PARTIAL`, the database pass `NOT MET` by the owner's
choice. Each `PARTIAL` and `NOT MET` is a deviation the owner accepted or a `PLANNED` remediation entry, not a pass; none is a
correctness defect left open by this gate.

## What Phase 15 delivered

**Production capabilities**: two images (`finapp-app`, `finapp-migrator`), reproducible from digest-pinned bases, signed with
provenance and SBOM attestations, scanned; the runtime contract (a management port, graceful shutdown in a held order, drained
schedules and consumers, the schema-version guard in readiness); the migrator Job and the one-time role bootstrap; dead letters,
replay and retention sweeps; the source-join census; the configuration register and its fingerprint; backup and restore ending in
the financial verification. **Deployment architecture**: one Deployment of N ≥ 3 replicas each running every role, rolling with
no unavailability, scaled by an autoscaler bounded by the connection budget, a disruption budget, zone spread; PostgreSQL and
Kafka managed outside the cluster; namespaces under `restricted` with quotas; a segmented edge of two controllers; GitOps by
digest through declared Argo CD Applications in four environments; Terraform for one reference cloud, nine modules and two roots,
tested and never applied. **Security improvements**: the threat model (47 threats, 433 control rows) and its guard; the secret
inventory, External Secrets and key rings with rotation rehearsed; `verify-full`, `SASL_SSL` with SCRAM and ACLs as code;
`default-deny` and per-hop policies; admission verifying signatures; operators typed, refused on the public surface, refused
step-ups recorded; a `SELECT`-only reporting role checked by capability on every connection; `TRUNCATE` refused on the
append-only tables; callbacks as hints; abuse limits across replicas. **Observability improvements**: the OpenTelemetry pipeline
with a scrub and one egress; every gauge off the scrape thread; SLOs with burn-rate pairs, correctness paging on its first sample,
routed alerts each with a runbook section; platform runbooks and the rehearsal record; the readiness review (170 cells). All of it
in this repository, never in a real environment.

## Remaining non-blocking debt

PI-004 (the storms, after the project's phases); PI-023 (the Phase 15 database pass and every case owed to it); PI-024 (the
platform rehearsals not run whole, the final-tree release and census); PI-016…PI-022 (owner-postponed defects, each before
production use); `P14-REM-001`…`-008`, `-010`, `-011` (Phase 14's remediation, `PLANNED`; `-009` was Phase 15's and is done);
`P15-REM-001`…`-004`, `-006`, `-007` (`PLANNED`); `X-TSK-002`, `-003`, `-010` (cross-cutting, `PLANNED`); `THREAT_MODEL.md` §6
item 44 (a) and (b) and item 43 (g)'s drills (Part B's to write into Phase 16); every readiness `GAP` (83) with its named owner.

## Tests

By the owner's rule: named classes, in one Gradle invocation after the last change, each XML's UTC timestamp read against the
run's start; no whole-module task, no `:app:architectureTest`, no package filter, no database, Kafka, cluster, image or container.

- **The probe** (2026-11-12, started 17:41:43 UTC; the XML written 17:47:15): `ReportingPoolMetricsTest` with
  `setMetricsTrackerFactory` removed - **3 tests, 1 failed** (`#itsMetersAreTheWritersOnes`, "the pool's meters" empty), as
  intended; the filter also ran eight ArchUnit rule classes, 24 tests, 0 failed. Restored, sha256 equal (`e028efb3…a7c93a7f`).
- **The gate's run**, once, after the repair, the flip and every record a guard reads (2026-11-12, started 17:54:45 UTC, every
  XML written 17:55:10-17:59:31): the tests of the changed classes - `ReportingPoolMetricsTest` 3, `ReportingPoolTransportTest` 2,
  `ReportingPoolCapabilityTest` 5, `ReportingPoolRoleTest` 3, `ReportGenerationReadsAsTheReportingRoleTest` 3; the guard the repair
  is for, `TestTaxonomyTest` 17; the guards reading the changed sources, `ConfigurationRegisterIsCompleteTest` 39 and
  `SecretInventoryIsCompleteTest` 38; and **every guard the flip arms or that reads a changed record** - `ThreatModelIsCompleteTest`
  14 (its exit rules: every CRITICAL and HIGH threat with built evidence, no acceptance proposed), `PlannedMetersExistTest` 13
  (every planned meter of `PHASE_15_PLAN.md` §11.2 registered), `MutationDemonstrationTest` 9, `ProductionReadinessIsEvidencedTest`
  8, `RunbookRehearsalCountTest` 7; with the eight ArchUnit rule classes the filter pulls in (24): **185 tests, 0 failed**.
  `ThreatModelIsCompleteTest` is the only test that matches "Phase 15 — Production Hardening" under any `src/test`.

**Not run, owed**: every `database`, own-container and `kafka` test (PI-023), `ReportingPoolCapabilityDatabaseTest` among them;
`RiskStormTest`, `RiskAlertRulesTest`, `BnplAlertRulesTest` and the slow classes; the `kind` and container scripts (PI-024); the
static validators (`manifest-check.sh`, `terraform-check.sh`, promtool), whose last recorded runs are the exit review's citations -
no file they read changed here; the CI jobs, which no one here observes.

## What happens next

The coordinator merges this gate, then Part B (Phase 16's initialisation, `P16-INIT`), and resolves their records; this gate
marks nothing `READY`. Part B carries item 43 (g)'s drills, item 44 (a) and (b) and `P15-REM-007` into Phase 16's backlog.
**The reminder, at this exit as at every one**: the storms of Phase 13 onward (PI-004), the Phase 15 database pass (PI-023) and the
platform rehearsals (PI-024) are owed after the project's phases - and before any of this runs anywhere real.
