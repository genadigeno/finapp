# Phase 15 — Production Hardening

*Written by the Phase 14 → 15 transition (2026-11-07), as documents only: nothing in this plan is implemented until
`P15-TSK-001` lands. Decided in ADR-0120…ADR-0125 (`Proposed`); the exit criteria in `PHASE_GATES.md` §5 Phase 15, made
measurable by this transition; the backlog in [`BACKLOG.md`](BACKLOG.md) §Phase 15. Where this plan and an earlier
document disagree, this plan is right and the earlier document carries a dated note saying so. Where this plan and the
code or configuration the tasks build disagree, the task's record is right and this plan gains an erratum.*

**Status of this document:** the decided design, drafted from `ROADMAP.md` (Phase 15 — **Production Hardening**:
"Security hardening, SLOs, runbooks, operational readiness"), `DELIVERY_PLAN.md` §Phase 15, `PHASE_GATES.md` §5 Phase 15's
seven criteria, the Phase 15 rows of `DECISIONS.md` §Deliberately Deferred and `CURRENT_STATE.md` §Known Architectural
Debt, and the repository as it stands at master `7840c716`. The owner's brief for this transition calls the phase
"Production Architecture and Hardening"; the roadmap's title is kept, and the brief's twelve areas (its 8.1–8.12) are this
plan's scope. Owner decisions C1–C15 (§2.2) are **ratified at their recommended defaults** (the owner, 2026-11-07, "accept recommendations"; *read "recorded as recommended defaults awaiting the owner's ratification" until then*);
the first task depends on none of them. Phase 14's formal completion gate (`P14-GATE`) runs in parallel; this plan edits
no Phase 14 section.

---

## 1. Objective

Make the platform **deployable, operable and defensible as N instances** — without changing what it computes. Phase 15
packages the one deployable unit as a minimal, signed, non-root image; defines how it runs on Kubernetes as N replicas
behind an edge that separates the public, operator and operational surfaces; provisions its infrastructure as reviewed,
reproducible code; delivers it through a pipeline whose every production change is two-person and evidence-backed;
moves every secret and key into a managed lifecycle (storage, access, rotation, revocation, recovery, audit); turns the
existing metrics, logs and spans into SLOs, routed alerts, an end-to-end trace and rehearsed runbooks; and states, per
deployable unit, what "production-ready" means with evidence rather than because a container started.

**Phase 15 moves no money and adds no business capability** (`DELIVERY_PLAN.md` §Phase 15 §18). Every financial rule,
invariant and lifecycle stays as Phases 3–14 built it. The phase's financial obligation is the opposite of building:
**a deployment, a migration, a rotation, a restart or a rollback must never change a financial outcome** — so the
rolling-update rehearsal, the restore rehearsal and the schema-compatibility guard each end in the platform's own
financial verification (trial balance zero per currency, GL = ledger, the product proofs, the projection check).

### 1.1 The production limitation, recorded up front

`ROADMAP.md` §Non-Goals: the platform is **not** "a production system carrying real customer funds", and every provider is
simulated. No cloud account, registry, DNS zone, certificate authority, on-call tool or Sigstore identity exists for this
repository. So:

- **Every Phase 15 task is verifiable inside the repository and CI**: image build and scan; `kubeconform` and policy checks
  over rendered manifests; a `kind` deployment of N replicas with a migration Job, smoke flows, a rolling update and a
  rollback; `terraform fmt`/`validate`/`tflint`/`trivy config` and `terraform test` with mock providers; `promtool check`
  and `promtool test` over the rules; `amtool check-config`; secret scanning; restore into a fresh database followed by
  the financial verification.
- **Out of scope, deferred to a real environment** (recorded, not claimed): `terraform apply` against a real account; a
  real KMS key or secret store holding real material (the rehearsals use generated, throwaway values); a
  push to a real registry and signing under the repository's real OIDC identity; real DNS and publicly trusted
  certificates; a managed database's point-in-time restore; a real paging receiver; GitHub branch-protection and
  environment rules (repository settings, the owner's to apply); a third-party penetration test; any production deploy.
- **No compliance is claimed.** Controls are mapped to threats (§9). Nothing in this phase claims compliance with, or
  certification or attestation under, PCI DSS, ISO 27001, SOC 2 or any other standard; technical preparation is not an
  audit, and a statement of conformity is an assessor's, not this repository's.

*The owner's decision (2026-11-08, `THREAT_MODEL.md` §6 item 7), a real-environment item:* when an account exists, applies run only from a
pipeline role on the plan a pull request showed, humans hold read-only access, and a scheduled plan reports drift. Phase 15
builds none of it.

*The owner's decisions (2026-11-09, `THREAT_MODEL.md` §6 items 21 to 23), real-environment items:* a backup under a separate key lives in a
dedicated backup account; the audit archive is locked in `COMPLIANCE` mode in production and `GOVERNANCE` in staging once a jurisdiction and
period are named; the Kafka ACLs are applied by the pipeline role from inside the cluster's network; the EKS network policy agent stays in
`standard` mode until a real cluster can measure `strict`; a connection limit or WAF goes in front of the edge. Phase 15 builds none of it.

### 1.2 Scope

The owner's twelve areas: the production architecture assessment (§3), containerisation and deployment (§5), the
Kubernetes architecture (§6), infrastructure as code (§7), CI/CD (§8), security hardening (§9), secrets and keys (§10),
observability and operations (§11), database and messaging operations (§12), multi-instance production execution (§13),
configuration and environments (§14), production readiness (§15); and the items earlier phases assigned here (§25).

### 1.3 Out of scope (Phase 16 and beyond)

Phase 16 — Scale, Resilience and Disaster Recovery — keeps, unchanged: load and soak tests with invariants asserted during
load, the capacity model and HPA calibration, chaos experiments (node loss, database failover, broker outage, network
partition), multi-AZ and multi-region topology, disaster recovery with measured RPO/RTO and cross-region backups,
partitioning and archival of high-volume tables, read-replica routing, backpressure and load shedding, and any service
extraction (ADR-0001's revisit point). Phase 15 builds what those need (images, manifests, restore, alerts) and measures
none of them at scale. *(Added 2026-11-08:)* registry availability for a starting replica (image pre-pulling or a mirror),
`THREAT_MODEL.md` T42, accepted by the owner until 2027-06-30 and re-owned to Phase 16. Beyond the programme: real provider connectivity, a real jurisdiction's retention periods and
filing channels, real customer funds.

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 Inherited, binding

| Decision | Where | Consequence here |
|---|---|---|
| One modular monolith, one deployable | ADR-0001, `settings.gradle.kts` (23 modules + `app`), `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution | The deployment plan is for the units that exist — the `app` image and a migration Job — run as N replicas; no microservice is invented (§4) |
| Every service runs as N concurrent instances; single-instance assumptions fail the build | ADR-0014, ADR-0024, `NoSingleInstanceAssumptionRulesTest` | N ≥ 3 in every deployed environment; no Kubernetes `CronJob` carries financial work; the leaderless schedules stay |
| The connection budget is a startup guard | `DISTRIBUTED_EXECUTION.md` §4a, `ConnectionPoolSizingGuard` | Replicas, the HPA maximum, the rolling surge **and the pods still terminating** are bounded by `finapp.database.instances` (§6.1); a manifest rule checks it and the `kind` rehearsal measures it |
| Liveness is the process, readiness includes the database | ADR-0016, `application.yaml` `management.endpoint.health.group` | The probes map one-to-one; liveness never reads a dependency |
| Forward-only, module-owned migrations, never run by the application | ADR-0011, `DATA_MIGRATIONS.md` §3, §4.1 (expand, migrate, contract) | Migrations run as a separate Job with the migrator's credentials; a rollback never rolls a schema back |
| Secrets externalised; a marked local default confined to loopback | ADR-0019, ADR-0020, `ConfinedCredential`, `CommittedConfigurationHoldsNoSecretTest` | The secrets manager fills the environment the application already reads; the loopback guards stay the backstop |
| Transport security off loopback | ADR-0023, `TransportSecurityGuard`, `KafkaTransportGuard`, `ProviderTransportGuard`; `SECURITY_ARCHITECTURE.md` §Transport encryption | `verify-full` to PostgreSQL, TLS to Kafka, `https` to providers; inbound TLS terminated at the edge, never by the application |
| Checksum-verified, locked, pinned supply chain | ADR-0025, ADR-0026, `infra/scanner-pins.sh` | Base images and every new tool are pinned by digest; trust on first use is stated, then narrowed by signing |
| Metric names are a contract; no request-derived tag; amounts never enter metrics | ADR-0018, ADR-0072, `MetricNames` | SLIs are built from existing series; no SLO introduces an unbounded tag |
| Kafka and projections are not financial truth | `CLAUDE.md` rule 12, ADR-0005 | Topic retention, replay and consumer-group operations can never repair or create a financial fact; the outbox and the journal are the sources |
| Evidence in PostgreSQL; object storage deferred | ADR-0036 | The application uses no object store; storage buckets serve infrastructure only (state, archives) |
| The standing criterion-7 deviation; storms after the phases | `PHASE_14_PLAN.md` §2.2 C5, `POSTPONED_ISSUES.md` PI-004, PI-012…PI-015 | Per task hermetic + architecture + static checks; one database pass at the exit, PI-012's full run with `kafkaTest`; `P15-TST-001` planned and `POSTPONED` |

### 2.2 Owner decisions — C1–C15, ratified at their recommended defaults

*(Ratified by the owner, 2026-11-07, "accept recommendations", after the transition's report: every one of C1–C15 at its recommended default as written below, C15 carrying §25's dispositions for `P15-TSK-002` to apply. This heading read "C1–C15, recommended defaults awaiting ratification" until then; the recommended defaults' text is kept as it was decided.)*

| # | Decision | Options | Recommended default | Ratified |
|---|---|---|---|---|
| C1 | Reference cloud and managed services | (A) one reference cloud — AWS: EKS, RDS for PostgreSQL Multi-AZ, MSK, ECR, KMS, Secrets Manager, S3 — its Terraform modules validated with mock providers and never applied; (B) cloud-neutral IaC of the Kubernetes layer only; (C) another provider | **(A)**: the brief asks for modules for networking, databases and Kafka, which need a provider; one reference keeps them concrete and reviewable, the Kubernetes layer stays provider-neutral and runs on `kind`. A second cloud would be a module set, not a redesign | Ratified 2026-11-07 |
| C2 | Kubernetes distribution and manifest packaging | (A) managed Kubernetes (EKS) for staging and production, `kind` (node image pinned by digest) for CI and local; the app's manifests plain Kubernetes with Kustomize overlays; Helm only to install pinned third-party components; (B) a Helm chart for the app; (C) self-run Kubernetes | **(A)**: one deployable needs no templating language; Kustomize renders reviewable YAML that `kubeconform` and policies read | Ratified 2026-11-07 |
| C3 | Where stateful dependencies run | (A) PostgreSQL and Kafka managed and outside the cluster; single-node copies only in the test-only `kind` overlay; Redis not deployed (no client in the code); object storage for infrastructure only; (B) in-cluster operators (CloudNativePG, Strimzi); (C) hand-written StatefulSets | **(A)**: the journal is the financial record — a synchronous Multi-AZ standby, point-in-time recovery and managed patching are the bar. An operator is a credible Phase 16 alternative; a hand-written StatefulSet for the ledger's database is refused | Ratified 2026-11-07 |
| C4 | Container image build | (A) a multi-stage Dockerfile, digest-pinned bases, the Spring Boot layered jar, a distroless Java 21 `nonroot` runtime, no Docker `HEALTHCHECK` (Kubernetes probes are the mechanism); (B) Jib; (C) Cloud Native Buildpacks (`bootBuildImage`) | **(A)**: explicit and reviewable; digest pins fit ADR-0025/0026; reproducibility measured by building twice and comparing | Ratified 2026-11-07 |
| C5 | Deployment shape | (A) one Deployment of the one image, every replica serving HTTP and running every schedule and consumer as today, ≥ 3 replicas, an HPA bounded by the connection budget; migrations a separate Job image; (B) two Deployments of the one image, `api` and `worker` roles | **(A)**: correctness is already leaderless; a role split is an optimisation that needs Phase 16's measurement and a rule that no role ever runs at zero. The existing role flags stay | Ratified 2026-11-07 |
| C6 | Secrets and keys | (A) AWS Secrets Manager and KMS customer-managed keys per environment and purpose; External Secrets Operator into Kubernetes Secrets (etcd envelope-encrypted by KMS) injected as environment variables; rotation by rolling restart; field-level keys as versioned key rings — encrypt with the active version, decrypt any retained one — and no re-encryption of `INSERT`-only evidence; (B) HashiCorp Vault; (C) the Secrets Store CSI driver | **(A)**: the application already reads its secrets from the environment (ADR-0020), so delivery changes nothing in the code; key rings are the one code change rotation needs | Ratified 2026-11-07 |
| C7 | Registry, signing, provenance and supply chain | (A) ECR with immutable tags, deployed by digest; Cosign keyless signing from CI's OIDC identity, SLSA provenance and SBOM attestations; admission verification in the cluster (Sigstore policy-controller), proven on `kind`; Trivy image and configuration scans (the scanner already pinned); Semgrep OSS with vendored rules for static analysis; (B) a KMS-held signing key; (C) no signing | **(A)**, demonstrated in CI with a local registry and an ephemeral key because no registry or Sigstore identity exists yet; the production signing identity is ratified when one does | Ratified 2026-11-07 |
| C8 | Delivery, environments and the approval boundary | (A) GitOps pull (Argo CD) from environment overlays pinned by digest; environments `local`, `ci` (`kind`), `staging`, `production`, production and non-production in separate accounts and clusters; promotion by a digest pull request after staging evidence; every production change approved by a second person; no CI credential reaches production; (B) push deploys from CI; (C) one cluster with a namespace per environment | **(A)**: the four-eyes rule the domain applies to money applies to the change that runs it | Ratified 2026-11-07 |
| C9 | Edge and network segmentation | (A) an ingress controller terminating TLS; a public ingress serving an allow-list of customer, merchant and provider routes; operator routes (`/v1/operator/**` and every route an operator permission guards) only on an internal ingress; the actuator on a management port reachable by the kubelet and Prometheus alone; NetworkPolicy default-deny; no API gateway product; no service mesh or in-cluster mTLS; (B) an API gateway product; (C) a mesh | **(A)**: authentication and authorisation are the application's (ADR-0030, ADR-0031) and a gateway would duplicate them; with one deployable there is no service-to-service hop for mTLS to protect (§9.3) | Ratified 2026-11-07 |
| C10 | Observability stack and SLO policy | (A) the OpenTelemetry Collector as the one telemetry egress for traces and logs; Prometheus, Alertmanager and Grafana; Tempo and Loki as the reference backends in compose and `kind`; head sampling 100% with a collector tail policy; SLOs per critical flow with multi-window burn-rate alerts; **correctness invariants page on the first breach and are never budgeted**; the paging receiver a placeholder until an on-call tool exists; (B) a vendor agent per signal | **(A)** | Ratified 2026-11-07 |
| C11 | Event contracts and Kafka security | (A) event schemas as a build-time catalogue with compatibility tests against the last released versions, no runtime schema registry; Kafka over `SASL_SSL` with SCRAM-SHA-512 credentials from the secrets manager and per-principal ACLs; (B) a runtime schema registry; (C) mTLS client certificates or cloud IAM authentication | **(A)**: no consumer outside the monolith exists and Kafka is never truth; SCRAM needs no new client library | Ratified 2026-11-07 |
| C12 | Data retention scope | (A) build the operational sweeps — outbox published rows and inbox records past their redelivery bound; idempotency records only once debt row #61 (posting once-ness) is settled — write the retention policy by data class, design audit archival to write-once storage; defer purges of financial evidence, agreements and regulatory reports to the choice of a real jurisdiction, crypto-shredding by key version the designed mechanism; (B) build every purge now against assumed periods | **(A)**: retention periods are a jurisdiction's, and the reference jurisdiction is neutral; a purge built against an invented period is a defect waiting for one | Ratified 2026-11-07 |
| C13 | Privileged access | (A) keep platform-native operator identities with TOTP step-up; type an operator's session `OPERATOR` in audit (debt row: every session actor is `CUSTOMER`); audit a refused step-up; operator routes internal only (C9); a `finapp_reporting` `SELECT`-only database role for report generation — Phase 14's C10, its Phase 15 half — a cut candidate; workforce OIDC federation deferred until a workforce identity provider exists; (B) OIDC federation for operators now; (C) no reporting role | **(A)** | Ratified 2026-11-07 |
| C14 | Criterion 7, the database pass and the storm | — | The standing deviation (Phase 14's C5): per task the hermetic tier, the architecture tier, the touched modules' tests and the phase's new static checks; database suites written and owed; **one database pass at the exit (`P15-TST-002`)** running every tier fresh **including `kafkaTest`** (PI-012) and closing PI-013, PI-014 and PI-015, which are due "at the next phase's database pass at the latest"; the storm `P15-TST-001` planned and `POSTPONED` (PI-004) | Ratified 2026-11-07 |
| C15 | The carried items owned by Phase 15 | — | §25's dispositions: the production-hardening classes scheduled into this phase's tasks, the rest re-dated with a reason; applied to `CURRENT_STATE.md` and `DECISIONS.md` by `P15-TSK-002` on ratification | Ratified 2026-11-07 |

C1, C3, C6, C7 and C8 are infrastructure choices a real environment would revisit against cost and an organisation's
standards; each is isolated behind a module or an overlay so that changing it rewrites a module, not the platform.

---

## 3. What exists, what is reused, what is missing (8.1 — the production architecture assessment)

Surveyed at master `7840c716` by reading the build, `compose.yaml`, `infra/`, `.github/workflows/ci.yml`,
`app/src/main/resources/application.yaml`, the security and distributed-execution documents and the ADRs.
**Implemented** means built and guarded; **Partial** means present with a named gap; **Missing** means absent;
**N/A** means not applicable to this platform, with the reason.

| Component | Status | Evidence | Phase 15 |
|---|---|---|---|
| Deployable units | Implemented | One Spring Boot application: `app` composes 23 modules (`settings.gradle.kts`: `sharedkernel`, `platform`, 21 business modules); no other runnable | §4: the `app` image and a migration Job; no microservice |
| "All microservices" | N/A | ADR-0001; `MODULE_ARCHITECTURE.md`; extraction is Phase 16's, by measurement | Every per-service question answered for the real units (§4) |
| Container image | Missing | No `Dockerfile` or `.dockerignore`; `bootJar` is Spring Boot's default — **layered by default** (`-003` relies on it) — with no Jib or `bootBuildImage` configured (`app/build.gradle.kts`) *(read "no layering" until the transition's review, its M-9)* | `P15-TSK-003`, `-004` |
| Kubernetes manifests, Helm, Kustomize | Missing *(read as of the transition; `deploy/` built by `P15-TSK-009`, 2026-11-08)* | No manifest of any kind in the repository | `-009`…`-012` |
| Infrastructure as code | Missing | No Terraform; `DECISIONS.md` §Deferred "Kubernetes and Terraform — Phase 15"; `infra/postgres/initdb/00-roles.sql` names Terraform as its deployed owner | `-013`…`-016` |
| Local stack | Implemented (local only) | `compose.yaml`: PostgreSQL 18.6, Kafka 4.3.1 (KRaft, single node, `PLAINTEXT`, RF 1), Redis 8.10.1, Prometheus v3.14.0, Grafana 12.4.10, all on `127.0.0.1`, tags asserted by `verifyInfrastructureVersions`; the application runs on the host | Kept for development; `kind` added for deployment rehearsal |
| PostgreSQL | Partial | Two roles, `finapp_migrator` and `finapp_app`, least privilege, per-table grants in each migration (`00-roles.sql`); `verify-full` off loopback (`TransportSecurityGuard`, ADR-0023); the connection budget guarded (`ConnectionPoolSizingGuard`); **no HA, backup, restore, or encryption-at-rest configuration** | `-015`, `-030` |
| Migrations | Partial | Flyway per module, forward-only, `cleanDisabled`, `validateOnMigrate`, `outOfOrder=false` (each module's `build.gradle.kts`; ADR-0011), run by Gradle and CI's `migrations` job; **no runtime runner**; expand/contract written (`DATA_MIGRATIONS.md` §4.1), **not enforced**; no guard against a new application on an old schema | `-004`, `-005` |
| Kafka and messaging | Partial | Raw `kafka-clients`; producer `acks=all`, idempotent, not transactional (`KafkaEventPublisher`); consumers manual-commit, groups `finapp.<module>` (`InboxConsumers`), inbox dedupe 14 days; `KafkaTransportGuard` refuses `PLAINTEXT` off loopback; **no SASL/TLS client configuration, no topic configuration as code, no schema registry (bytes + media type), no consumer-side dead letter**; outbox dead-lettering exists (`dead_lettered_at`) **with no tooling** *(the transition's reading; since `P15-TSK-026`, 2026-11-08, `SASL_SSL` with SCRAM-SHA-512 is required off loopback, the topics, their retention and the ACLs are code in `infra/kafka/`, and the consumers rebalance cooperatively)* | `-026`…`-028` |
| Redis | N/A | In `compose.yaml` only; no client library in the code (`TransportSecurityGuard`'s note; ADR-0030 chose PostgreSQL for sessions); `SYSTEM_ARCHITECTURE.md` §Baseline Stack still lists it | Not deployed (C3); the stack list corrected by `-031` |
| Object storage | N/A (application) / Missing (infrastructure) | ADR-0036 keeps evidence in PostgreSQL; `SettlementFileStore` names object storage a future adapter | Buckets for Terraform state and archives only (`-015`) |
| API gateway / edge | Missing | No gateway or ingress; the application authenticates (sessions, merchant keys) and bounds request size (`RequestSizeLimitFilter`); 397 routes, 269 under `/v1/operator` (`docs/api/openapi.json`) | Ingress, not a gateway product (C9, `-011`) |
| External provider integrations | Implemented (simulated) | Adapters behind ports; `ProviderTransportGuard` refuses plain `http` off loopback; real connectivity a programme non-goal | Egress allow-list (`-010`); callbacks as hints (`-036`, §25 - `X-TSK-015` until `P15-TSK-002` moved it into the phase, 2026-11-08) |
| Identity and authentication | Partial | Server-side opaque sessions (ADR-0030), Argon2 credentials (ADR-0032), TOTP MFA, `@RequiresAssurance` on 25 handlers, `@RequiresPermission` (ADR-0031), scoped merchant keys (ADR-0052); **no OAuth2/OIDC and no Spring Security** although `SYSTEM_ARCHITECTURE.md` lists it; **every session actor audited as `CUSTOMER`** *(closed 2026-11-08 by `-021`: an operator act is typed `EMPLOYEE`; the handlers recounted at 21)* | C13, `-021` |
| Service identity, mTLS | N/A in Phase 15 | One deployable; no service-to-service hop; `SECRET_MANAGEMENT.md` §7 defers per-service identity to Phase 16 | Workload identity for the cloud APIs only (`-016`) |
| Financial modules | Implemented | Phases 3–14 | Unchanged; their correctness is the rehearsals' assertion |
| Background workers and schedules | Implemented | 33 `SmartLifecycle` schedules, leaderless, arbitrated in PostgreSQL (`FOR UPDATE SKIP LOCKED`, advisory locks, leases), guarded by `nothingSchedulesAmbiently` and `LEASE_PROTECTED_SCHEDULERS`; no `@Scheduled` | Kept; drained on shutdown (`-008`) |
| Health and readiness | Partial | Probes enabled; liveness `livenessState`, readiness `readinessState,db`, details never (ADR-0016); **the actuator shares port 8080 and is unauthenticated** (two debt rows) | Management port and NetworkPolicy (`-008`, `-010`) |
| Graceful shutdown | Partial | Not configured explicitly (the HTTP server's graceful default unasserted); schedules' `stop()` calls `shutdownNow()` with no bounded wait; `InboxConsumers` wakes and joins each loop for 10 s | `-008` |
| Metrics and alerts | Partial | Prometheus registry; 136 alerts in seven files (`infra/prometheus/rules/`), proved by `RuleEvaluator` tests; **no rule on the ledger's continuous trial balance, the outbox or HTTP errors and latency; no Alertmanager or routing; `promtool` never run in CI; about sixty gauge classes read on the scrape thread** | `-024`, `-025` |
| Dashboards | Partial | One provisioned baseline dashboard, read-only (`infra/grafana/`) | SLO dashboard (`-025`) |
| Tracing | Partial | Micrometer tracing with the OpenTelemetry bridge, sampling 1.0, `CorrelationSpanProcessor`; **no exporter, collector or backend** — spans are dropped (`application.yaml`) | `-023` |
| Logging | Partial | ECS JSON on the console, correlation and trace ids in the MDC, default-deny redaction (ADR-0019); **no shipping, retention or central search** | `-023` |
| SLOs and error budgets | Missing | Latency objectives exist as code per domain (`*Objectives.java`); no SLO document, budget or burn-rate alert | `-025` |
| Runbooks | Partial | `OPERATIONS_RUNBOOK.md` §1–§10: domain activations and every domain alert; **no platform runbook** (deploy, rollback, restore, rotation, dead letters, incident response) | `-032` |
| CI | Partial | Jobs `build`, `migrations` (including `databaseTest`, `kafkaTest`), `secret-scan` (gitleaks over history), `dependency-scan` (Trivy over the CycloneDX SBOM), `pinned-images`; actions pinned by SHA; Dependabot; **no image build or scan, static analysis, signing, manifest or Terraform checks, `promtool`, deployment or smoke** | `-006`, `-007`, `-009`, `-012`, `-013`, `-025` |
| Supply chain | Implemented | Locking and checksum verification (ADR-0025), pins and their freshness (ADR-0026); signature verification off — trust on first use, stated | Signing and admission (`-007`) |
| Secrets | Partial | Environment placeholders with a marked local default confined to loopback (`ConfinedCredential`, `DatabaseCredentialGuard`); 37 `finapp.*` key, secret, password or token properties; **no secrets manager, KMS, rotation or revocation procedure** | `-018`…`-020` |
| Encryption at rest | Partial | Eight AES-256-GCM cipher classes, nine instances (`EvidenceCipher` built twice, `PaymentBeans.java:83`, `:829`) and eight keys (`DocumentCipher` and `CounterpartySubjectCipher` share `finapp.doc.key`, `CounterpartyScreeningBeans.java:65-76`), with a key version on each row (`SecretCipher`, `DocumentCipher`, `CounterpartySubjectCipher`, `EvidenceCipher`, `PayoutEvidenceCipher`, `CreditEvidenceCipher`, `FxEvidenceCipher`, `SettlementFileCipher`), one active key each, **no key ring**; volume and backup encryption absent (infrastructure) | `-019`, `-015` |
| Rate limiting | Partial | Login throttle and lockout (`AuthenticationThrottle`); request size limit; **no per-source or registration limit** (two debt rows) | `-022` |
| Audit | Partial | Append-only by grant (ADR-0010), `AuditCompletenessTest`, `AuditableActionRegistryTest`; **no retention or archival; operator actor typing wrong** *(the typing closed 2026-11-08 by `-021`, with refused step-ups recorded)* | `-021`, C12 |
| Data classification | Implemented | `DATA_CLASSIFICATION.md` per-column register (ADR-0022) | Read by the threat model and the log scrubber |
| Threat model | Missing | Named by `PHASE_GATES.md` §5 Phase 15 and `DELIVERY_PLAN.md` §Phase 15 §5; no document | `-001` |
| Backup and restore | Missing | No backup, no restore procedure, no rehearsal | `-030` (and `-015` for managed PITR) |
| Retention sweeps | Missing | Outbox and inbox rows never deleted; audit never archived (debt rows) | `-029` (C12) |

**Stale documents found by the survey**, each corrected by the task that touches it (§25 lists them with their owner):
`SECURITY_ARCHITECTURE.md` §Encryption at rest says nothing is encrypted at rest (eight ciphers are);
`SECRET_MANAGEMENT.md` §7 says key rotation "needs keys, of which there are none" (there are eight);
`TransportSecurityGuard`'s note says there is no Kafka client (there is); `RequiresAssurance`'s note says no production
endpoint declares it (25 do); `SYSTEM_ARCHITECTURE.md` §Baseline Stack lists Spring Security, Redis and S3, none of which
the code uses.

*Erratum (`P15-TSK-001`, 2026-11-08, recounted from the code; the rows above are kept as surveyed): **37** `finapp.*`
credential properties is **27** distinct (each with its own `ConfinedCredential` key class; 35 with the eight `key-version`
companions); `@RequiresAssurance` is declared on **21** handlers, all `MULTI_FACTOR` (25 is a text count that also matches four
mentions in comments and the interceptor's message); the 397 "routes" are OpenAPI **paths** - 430 operations, 269 operator paths and 288 operator
operations - and the code holds five provider callback doors, each present only where its provider is configured, three
of them in the published contract; 33 schedules are **34** `SmartLifecycle` components (32 schedules, `AccountingGaugeRefresher`, `InboxConsumers`);
the cipher counts (eight classes, nine instances, eight keys) hold, and **three** of the eight bind associated data, five
none. The first two stale statements are corrected (`SECURITY_ARCHITECTURE.md` §Encryption at rest, `SECRET_MANAGEMENT.md`
§7); the other three stay with `-008`, `-021` and `-031`, which carry them.*

---

## 4. The deployable units (the brief's "every microservice", answered for this platform)

**This platform is a modular monolith** (ADR-0001): `settings.gradle.kts` declares `sharedkernel`, `platform`, twenty-one
business modules and `app`, and only `app` produces a runnable (`bootJar`). `SYSTEM_ARCHITECTURE.md` §Multi-Instance
Execution: "one *deployable*, not one *process*". Where the owner's brief says "all microservices" or "each service",
this plan answers for the units that exist:

| Unit | What it is | Runs as | State it owns |
|---|---|---|---|
| **`finapp-app`** | The `app` boot jar: HTTP API (customer, merchant, provider callbacks, operator), the 33 leaderless schedules, the inbox consumers, the outbox relay | A Kubernetes Deployment, N ≥ 3 replicas (C5) | None in process — all authoritative state in PostgreSQL |
| **`finapp-migrator`** | Flyway over every module's `db/migration` with the build's settings, as `finapp_migrator` | A Kubernetes Job per release, before the rollout | `flyway_schema_history` per schema (Flyway's own lock serialises two runs) |
| PostgreSQL | The financial record | Managed, outside the cluster (C3) | Everything authoritative |
| Kafka | Integration transport, never truth | Managed, outside the cluster (C3) | Nothing authoritative |
| Telemetry | OpenTelemetry Collector; Prometheus, Alertmanager, Grafana; trace and log backends | In-cluster (reference) | Diagnostic only |
| Edge | Ingress controller(s) | In-cluster | None |

The bounded contexts inside `finapp-app` keep their **operational ownership** — alerts, runbook sections, readiness rows —
per module (§15), because "who is woken for a lending alert" is a module's question even when the process is shared.

---

## 5. Containerisation and deployment (8.2)

| Concern | Design | Task |
|---|---|---|
| Image | Multi-stage Dockerfile (C4): a JDK 21 builder stage extracts the Spring Boot layered jar (`-Djarmode=tools extract --layers --launcher`); the runtime stage is distroless Java 21 `nonroot`, both bases pinned by digest; dependencies, Spring Boot loader, snapshot and application layers copied in that order so a code change rebuilds one layer | `-003` |
| Multi-stage, minimisation | No build tool, shell, package manager or source in the runtime image; `.dockerignore` excludes everything but the jar; a size budget recorded and asserted | `-003` |
| Non-root | UID/GID 65532 (distroless `nonroot`); `readOnlyRootFilesystem` with an `emptyDir` for `/tmp`; no capability; `allowPrivilegeEscalation: false`; seccomp `RuntimeDefault` | `-003`, `-009` |
| Container health checks | No Docker `HEALTHCHECK`: Kubernetes ignores it and distroless has no shell to run one; liveness, readiness and startup probes on the management port | `-008`, `-009` |
| Resource requests and limits | Memory request = limit (no overcommit of the JVM heap), `-XX:MaxRAMPercentage` set so the heap fits; CPU request sized from the `kind` baseline, a CPU limit at a multiple of it; calibrated in Phase 16 | `-009` |
| Graceful shutdown | `server.shutdown=graceful` stated and tested; `spring.lifecycle.timeout-per-shutdown-phase` 30 s; a `preStop` pause so endpoints leave the Service before the JVM stops accepting; no new schedule tick starts and each drains within a bound; consumers commit and leave their group; `terminationGracePeriodSeconds` greater than the sum | `-008`, `-009` |
| Readiness and liveness | Liveness `/actuator/health/liveness` (process only); readiness `/actuator/health/readiness` (`readinessState`, `db`, and the schema-version guard of `-005`); a startup probe so slow startup guards are not killed by liveness | `-005`, `-008`, `-009` |
| Configuration | Non-secret values from a ConfigMap per overlay, secrets from the secrets manager (C6), both as environment variables the application already reads; no Spring profile — environments differ by values, never by code path (§14) | `-018`, `-031` |
| Environment separation | Separate accounts, clusters, KMS keys and secret paths for production and non-production (C8); a non-production identity cannot read a production secret | `-014`, `-016` |
| Deployment versioning | Image tag = the commit SHA (plus a release version when cut), deployed **by digest**; OCI labels `org.opencontainers.image.revision` and `.source`; `/actuator/info` already publishes build identity, and the post-deploy smoke asserts it equals the promoted revision | `-003`, `-017` |
| Migrations | A second image, `finapp-migrator`: Flyway with the build's settings per schema, in a pinned module order, `lock_timeout` set so a migration never queues behind long transactions and stalls posting; refuses `clean`; exits non-zero on any failure | `-004` |

---

## 6. Kubernetes architecture (8.3)

**Not everything goes into Kubernetes** (C3): PostgreSQL and Kafka run managed outside it in staging and production; the
cluster runs the stateless application, its migration Job, the edge and the telemetry pipeline.

| Object | Design | Task |
|---|---|---|
| Deployment `finapp-app` | N ≥ 3 (the HPA's `minReplicas`: the Deployment states no `replicas`, so an apply never resets the HPA, *as built 2026-11-08*); `RollingUpdate` with `maxUnavailable: 0`, `maxSurge: 1`; `minReadySeconds ≥ terminationGracePeriodSeconds` (§6.1); `revisionHistoryLimit` kept for rollback; `topologySpreadConstraints` across zones and nodes (`DoNotSchedule` on zone, `ScheduleAnyway` on node) | `-009` |
| StatefulSets | **None in staging or production.** The test-only `kind` overlay runs single-node PostgreSQL and Kafka for rehearsal, labelled non-production and refused by a policy in any other overlay | `-009`, `-012` |
| Job `finapp-migrator` | One per release, `backoffLimit` small, `activeDeadlineSeconds` bounded; run before the rollout (an Argo CD `PreSync` hook, C8); its own ServiceAccount; egress to PostgreSQL only | `-004`, `-017` |
| Services | `finapp-app` ClusterIP on 8080 (API); no Service exposes the management port — Prometheus scrapes pods directly | `-009` |
| Ingress | Two (C9): **public** — an allow-list of customer, merchant and provider-callback routes, TLS, HSTS, coarse per-source limits; **operator** — internal only, every route an operator permission guards; no sticky sessions (sessions live in PostgreSQL) *(as built 2026-11-09: two Traefik controllers, one per surface, on the file provider, rather than two `Ingress` objects, because that API can state neither a method nor a template; ADR-0120 decision 6's dated note)* | `-011` |
| ConfigMaps | One per overlay, generated by Kustomize with a content hash in its name so a change rolls the Deployment | `-009`, `-031` |
| Secrets | Delivered by External Secrets Operator from the secrets manager (C6); never committed; etcd encryption with a KMS provider; in `kind`, generated at runtime by the rehearsal script and never written to the repository | `-018` |
| ServiceAccounts | `finapp-app` and `finapp-migrator`, each with `automountServiceAccountToken: false` (neither calls the Kubernetes API) and a cloud workload identity limited to its own secrets | `-010`, `-016` |
| NetworkPolicies | Default deny in and out; app ingress from the public and operator ingress controllers on 8080; management port from the kubelet and Prometheus; egress to PostgreSQL, Kafka, DNS, the collector and the provider egress list; the migrator to PostgreSQL alone | `-010` |
| HPA | CPU-based, `minReplicas` 3, `maxReplicas` bounded by §6.1's budget rule; scale-down stabilisation of minutes and a scale-down policy of one pod per period no shorter than `terminationGracePeriodSeconds`, which limits consumer-group churn and keeps terminating pods to one; targets calibrated in Phase 16 | `-009` |
| PodDisruptionBudget | `maxUnavailable: 1` — a node drain never takes the fleet below N − 1 | `-009` |
| Affinity | Topology spread as above; no node affinity beyond the node pool | `-009` |
| Persistent storage | None for the application (no volume but `/tmp`); in `kind`, the test-only stateful copies use ephemeral storage | `-009` |
| Namespaces, quotas | `finapp` per cluster (one environment per cluster, C8), `finapp-edge`, `finapp-observability`; Pod Security Admission `restricted` on `finapp`; `ResourceQuota` and `LimitRange` so a pod without requests is refused | `-010` |
| RBAC | No human holds write access in production; the GitOps controller applies; a break-glass role, time-bound and audited, for incident response; CI holds no production credential | `-010`, `-017` |
| Rolling deployments | `maxUnavailable: 0`; readiness gates traffic; N − 1 and N run together against one schema, which expand/contract and `-005` make safe | `-005`, `-012` |
| Rollback | Revert the overlay's digest (Argo CD syncs); allowed only to a release no later migration's contract marker excludes (`-005`'s rule, §12), and the promotion script refuses a rollback across a marker; **a schema is never rolled back** — a bad migration is fixed forward; the rollback is rehearsed on `kind` | `-005`, `-012`, `-017`, `-032` |

*As built, `P15-TSK-010` (2026-11-09).* The table's words, against what was found. **"Management port from the kubelet and Prometheus"**: no rule names the kubelet, and none can; a pod cannot block its resident node, so the probes need no rule and anything else on that node reaches 8081 too. **"Pod Security Admission `restricted` on `finapp`"**: on all three namespaces, because it is what stops a pod of ours using host networking to come through that same door. **"`ResourceQuota` and `LimitRange` so a pod without requests is refused"**: two refusals, in admission's order. A pod stating nothing meets the `LimitRange` first; a container stating nothing beside one that does passes its per-pod sum and meets the quota, which checks each container. The `LimitRange` is `type: Pod`, because a `type: Container` item is defaulted from its own `max` and would admit that pod. **"The migrator to PostgreSQL alone"**: PostgreSQL and the resolver that finds it. **"Time-bound" break-glass**: Kubernetes RBAC has no expiring binding, so the bound is the session of the cloud role carrying the group (`-016`), and a written procedure until it exists (`OPERATIONS_RUNBOOK.md` §11.25). **The data tier's and providers' addresses** are per overlay and RFC 5737 placeholders, as the digests are zeros.

### 6.1 The connection budget counts terminating pods

*(Corrected by the transition's review, 2026-11-07, its I-1: this read "`maxReplicas + maxSurge ≤
finapp.database.instances`", which counted only the pods a Deployment counts.)* A Deployment's rolling update and a
ReplicaSet's replacement **do not count terminating pods** against `replicas + maxSurge` (the motivation of KEP-3973), and
§5 keeps a terminating pod's full pool open through the `preStop` pause, the drain and, at worst, the whole
`terminationGracePeriodSeconds`. So a rollout step holds the counted replicas, the surge pods **and** the pods still
terminating — and `ConnectionPoolSizingGuard` (`DISTRIBUTED_EXECUTION.md` §4a) is only as true as the instance count the
deployment declares. The rule, checked by `-009`'s manifest policy:

```
maxReplicas + 2 × maxSurge + 1  ≤  finapp.database.instances
minReadySeconds                 ≥  terminationGracePeriodSeconds
```

- **`2 × maxSurge`**: the surge pods, plus at most `maxSurge` terminating from the rollout. `minReadySeconds ≥
  terminationGracePeriodSeconds` holds the next scale-down until the previous terminating pod's grace has run out, so
  the rollout never stacks terminating pods.
- **`+ 1`**: one more terminating pod from outside the rollout — a voluntary eviction, which the PodDisruptionBudget
  (`maxUnavailable: 1`) allows only one at a time, or an HPA scale-down step, limited to one pod per period no shorter
  than the grace. An involuntary kill closes the process's connections when the process dies; a lost node's connections
  linger until the database's TCP keepalive ends them, which the reserved connections absorb (a Phase 16 failover
  question).
- **Why not `podReplacementPolicy: TerminationComplete`.** It is the direct fix, but it is alpha behind a feature gate
  and not enabled on a managed cluster by default. When it is generally available, the policy may drop the `2 ×` term for
  it, by a dated amendment.

`P15-TSK-012` measures the rule rather than trusting it. It runs with `finapp.database.instances` set to the formula's
value exactly, and a server `max_connections` equal to `instances × pool + reserved`. During the rolling update and the
pod kill it records the peak count of `pg_stat_activity` rows with `application_name = 'finapp'` and asserts that count
is at most `instances × pool`. It also asserts that an operator's `psql` still connects at the peak.

---

## 7. Infrastructure as code (8.4)

**Terraform**, one module per concern under `infra/terraform/modules/`, composed by root modules per environment under
`infra/terraform/environments/{staging,production}` (C1). Reproducible (versions and providers pinned with a lock file,
as ADR-0025 does for Gradle), versioned (in this repository), reviewable (a plan per pull request) and safely changeable
(remote state with locking and versioning; `prevent_destroy` on the database, its backups and the KMS keys; no manual
change — a drift check is a scheduled plan in a real environment).

| Module | Contents | Task |
|---|---|---|
| `network` | VPC, private subnets per zone, no public subnet for workloads, NAT for egress, VPC endpoints for the cloud APIs, flow logs | `-014` |
| `kubernetes` | EKS with a private API endpoint, managed node groups, etcd secret encryption with a KMS key, workload identity, control-plane audit logs | `-014` |
| `postgres` | RDS for PostgreSQL at the pinned major (18), Multi-AZ, `rds.force_ssl`, a parameter group whose `max_connections` is the one value `finapp.database.server-max-connections` declares, automated backups with point-in-time recovery and a KMS key separate from the volume's, deletion protection, Performance Insights | `-015` |
| `kafka` | MSK, three brokers across zones, TLS in transit, SCRAM authentication, `min.insync.replicas` 2, `unclean.leader.election.enable` false, auto-create off; topics from the catalogue of `-026` | `-015` |
| `object-storage` | Buckets for Terraform state and for archives (audit, logs), versioned, KMS-encrypted, public access blocked; Object Lock (write-once) on the audit archive | `-015` |
| `security` | KMS keys per environment and purpose (etcd, database, backups, secrets, field-level key wrapping); Secrets Manager entries **without values**; IAM roles for the two workloads with access to their own secrets only | `-016` |
| `registry` | ECR repositories with immutable tags, scan on push, lifecycle rules | `-016` |
| `observability` | Log and trace retention buckets, alerting endpoints (placeholders) | `-016` |

*(Corrected by `P15-TSK-015`, 2026-11-09, wherever this plan says backups are under a separate key (this table, §9.2's T7 and §12's backup row): RDS encrypts its automated backups and snapshots with the instance's key and offers no setting, and within one account and region an AWS Backup vault's key does not change that. A separate key needs a copy across an account or region, which is what the module builds; point-in-time recovery stays under the volume's key. `THREAT_MODEL.md` §6 item 23.)*

*(Corrected by `P15-TSK-016`, 2026-11-09, on the `observability` row: no cloud alerting endpoint is built. The placeholder receivers are Alertmanager's (`infra/alertmanager/alertmanager.yml`), and a topic nobody subscribes to would read as paging. The `security` row's list of key purposes is longer as built: etcd, database, database backup, secrets, field-level keys, brokers, state, archive and logs. On the `registry` row, no lifecycle rule is built until a promotion lands under an immutable tag.)*

**Verification without an account**: `terraform fmt -check`, `terraform validate`, `tflint` (pinned), `trivy config`
(the pinned scanner) and **`terraform test` with mock providers** asserting the security properties (encryption on, public
access off, deletion protection on, Multi-AZ on, `max_connections` equal to the application's declaration). **Never
`terraform apply`** against a real account in Phase 15; the state backend is configured per environment and not
initialised by CI. **Roles are infrastructure, never Flyway** (`00-roles.sql`: `finapp_migrator` is `NOCREATEROLE`). In a
deployed database the roles of `00-roles.sql` (`finapp_migrator`, `finapp_app`), the alternating application users of
§10 and `finapp_reporting` (C13) are created by a **separate one-time bootstrap step**. Only that step holds the master
user's credentials, and it runs once per database and again only when a role is added. **It is never part of the
per-release migrator Job.** The per-table grants stay in each module's migrations, as today. Terraform never holds a
role's password; the secrets manager generates them. *(Corrected by the transition's review, 2026-11-07, its M-4: this
read "move from an `initdb` script to the migrator's first step".)*

---

## 8. CI/CD (8.5)

The existing jobs stay. Each new job delegates to a script under `infra/scripts/` so that every check runs on a
developer's machine as well as in CI (the `secret-scan.sh` precedent), and every tool is pinned by digest beside
`infra/scanner-pins.sh`.

| Stage | Check | Exists / new | Task |
|---|---|---|---|
| Build, unit tests | `./gradlew build` (unit, architecture, slice tests; `OpenApiContractTest`; `CommittedConfigurationHoldsNoSecretTest`; `verifyInfrastructureVersions`) | Exists | — |
| Integration tests | `migrations` job: migrate, validate, re-migrate, `databaseTest`, `kafkaTest` | Exists | — |
| Contract tests | OpenAPI contract (exists); event schema compatibility against the last release (new) | Partial | `-027` |
| Static analysis | Semgrep OSS, pinned, rules vendored in the repository (C7) | New | `-006` |
| Dependency scanning | Trivy over the CycloneDX SBOM | Exists | — |
| Container scanning | Trivy over the built image (vulnerabilities, secrets, misconfiguration), HIGH/CRITICAL fail | New | `-006` |
| Secret scanning | gitleaks over history | Exists | — |
| Configuration scanning | `kubeconform` (strict, pinned schemas) and `trivy config` over rendered manifests and Terraform; the platform's own policies (connection budget, probes, security context, no `kind: Secret` with data, digest-only images) | New | `-009`, `-010`, `-013` |
| Observability rules | `promtool check rules`, `promtool test rules`, `amtool check-config` | New | `-025` |
| Artifact creation | App and migrator images, their SBOMs, built once per commit | New | `-003`, `-004`, `-006` |
| Signing | Cosign signature, SLSA provenance and SBOM attestations, verified before deploy and at admission (C7) | New | `-007` |
| Deployment | GitOps: a digest pull request per environment; Argo CD syncs (C8) | New | `-017` |
| Smoke tests | `kind`: N replicas, migration Job, flows with simulated providers, rolling update, rollback, financial census; production smoke is **read-only** (health, build identity, the trial-balance gauge) and moves no money | New | `-012`, `-017` |
| Rollback | Digest revert, rehearsed on `kind` | New | `-012` |
| Database migration safety | The expand-only lint over migrations added since the last release, counting tightening DDL as a contract change (§12); the schema-version readiness guard; Flyway validate before migrate; the N − 1 release run against the new schema in the rehearsal; a rollback across a contract marker refused | New | `-005`, `-012`, `-017` |
| Consumer handler sets | The handler-set catalogue `(group, topic, eventType, consumerName)` diffed against the last release; a handler added to an existing group requires an audited replay over the rollout window or a new group (§12) | New | `-027`, `-028`, `-012` |

**Promotion and approval boundaries** (C8): `ci` (every commit: build, scans, `kind` rehearsal on `master` and nightly)
→ `staging` (a digest pull request; automatic sync; the full smoke and the rehearsals' financial census must pass for that
digest) → `production` (a digest pull request naming the staging evidence, **approved by a second person** through
CODEOWNERS on the production overlay; migration Job first, then the rollout; read-only smoke). The same digest moves
through every environment; nothing is rebuilt for production. GitHub's branch protection and environment rules are
repository settings, recorded in the runbook as the owner's to apply.

---

## 9. Security hardening (8.6)

### 9.1 The threat model first

*Written: [`THREAT_MODEL.md`](../architecture/THREAT_MODEL.md) (`P15-TSK-001`, 2026-11-08) - the ten boundaries below
drawn for N replicas, STRIDE at each, 47 threats rated by a written rule (read 44 until its fix round), every CRITICAL and HIGH threat
controlled;
held by `ThreatModelIsCompleteTest`. Where this section and the model differ, the model is right.*

`P15-TSK-001` writes `docs/architecture/THREAT_MODEL.md`: the trust boundaries (internet → edge; edge → application;
operator network → operator ingress; application → PostgreSQL; application → Kafka; application → providers; providers
→ callback routes; CI → registry; GitOps → cluster; humans → cluster and cloud), STRIDE per boundary, each threat's
likelihood and impact, and **each threat mapped to a control with its evidence** (a test, a policy, a file) or to an
accepted risk with an owner. A guard test holds the register: every control row names evidence that exists, every
threat has a disposition, and no CRITICAL or HIGH threat is left without a control or a recorded acceptance.

### 9.2 Controls mapped to threats

*T1-T14 carried into [`THREAT_MODEL.md`](../architecture/THREAT_MODEL.md) §3 with their numbers, T15-T47 added there;
each control's evidence, or the task that builds it, is the model's §4, not this table.*

| Threat | Controls (existing → Phase 15) | Task |
|---|---|---|
| T1 Credential stuffing, registration abuse, volumetric abuse from the internet | Lockout and throttle per identity (exists) → edge per-source limits; a cluster-safe registration throttle in PostgreSQL; request size limit (exists) | `-011`, `-022` |
| T2 Reconnaissance through operational endpoints | Actuator allow-list, details never (exists) → management port off the Services and ingresses; NetworkPolicy to the kubelet and Prometheus only | `-008`, `-010` |
| T3 A compromised operator account or a malicious insider | TOTP step-up, four-eyes by `CHECK`, every act audited (exist) → operator routes internal only; operator sessions typed `OPERATOR`; refused step-ups audited; a privileged access review of role holdings; break-glass time-bound and audited | `-011`, `-021`, `-032` |
| T4 Lateral movement from a compromised pod | Non-root, read-only root, no capabilities, no service-account token, no Kubernetes RBAC, no shell in the image, default-deny NetworkPolicy, egress allow-list | `-003`, `-009`, `-010` |
| T5 Supply chain (dependency, base image, action, tool) | Locking, checksums, SHA and digest pins (exist) → image scan, SBOM per image, static analysis, signing with provenance, admission refusing unsigned images, Dependabot for base images | `-006`, `-007` |
| T6 Secret disclosure (repository, logs, environment dumps, heap dumps) | gitleaks, default-deny redaction, unloggable secrets, actuator allow-list (exist) → secrets manager, etcd encryption, `get secrets` denied to humans, rotation and revocation runbooks | `-018`…`-020` |
| T7 Database compromise or exfiltration | Two roles, per-table grants, `verify-full`, field-level encryption (exist) → private subnets, KMS-encrypted volumes and backups under separate keys, a `SELECT`-only reporting role, key rings | `-015`, `-019`, `-021` |
| T8 Forged or replayed provider callbacks | HMAC verification, callbacks as hints for Phase 9 flows (exist) → per-route public allow-list; `-036` extends callbacks-as-hints to Phase 5 and 7 providers (§25; `X-TSK-015` until `P15-TSK-002` moved it into the phase, 2026-11-08) | `-011`, `-036` |
| T9 Message tampering or injection on Kafka | Events are hints, inbox dedupe (exist) → `SASL_SSL`, SCRAM per principal, ACLs per topic | `-026` |
| T10 Audit tampering or loss | Append-only grants (exist) → archival to write-once storage (designed, C12), audit gaps closed (`-021`) | `-021`, `-029` |
| T11 Configuration drift and manual change | Infrastructure and manifests as code, GitOps self-heal, two-person production changes, the configuration register | `-013`, `-017`, `-031` |
| T12 A deployment breaking financial consistency (version skew, a migration) | Forward-only migrations (exist) → expand-only lint, schema-version readiness guard, N − 1 / N rehearsal with the financial census | `-005`, `-012` |
| T13 A stepped clock deciding a financial instant | The database's clock is the authority (exists) → PI-013's and PI-014's clock-step defects fixed with regression cases; managed time sync on nodes | `-033` |
| T14 Resource exhaustion and noisy neighbours | Connection budget guard (exists) → requests and limits, quotas, bounded HPA | `-009`, `-010` |

### 9.3 Decided against, with the reason

- **mTLS inside the cluster and a service mesh.** With one deployable there is no service-to-service hop; the hops that
  leave a pod — PostgreSQL, Kafka, providers — already have their own TLS and authentication. A mesh adds a control plane
  and a certificate authority to protect a hop that does not exist. Revisited if Phase 16 extracts a service.
- **An API gateway product.** Authentication and authorisation are the application's; a gateway would be a second policy
  point that can disagree with the first. The ingress does TLS, routing allow-lists and coarse limits only.
- **OIDC for customers.** Customer identity is the platform's own product (Phase 1). OIDC federation for the workforce is
  the target and waits for a workforce identity provider (C13).

### 9.4 What remains of the brief's list

Service identity — the two workload identities, cloud-scoped (`-016`). OAuth2/OIDC — §9.3. MFA — exists; refused
step-ups audited (`-021`). RBAC/ABAC — the permission register and ownership checks exist (`RoutePermissionRegisterTest`);
the privileged access review reads role holdings (`-021`). Secrets, keys, rotation — §10. Encryption — field-level
(exists, key rings `-019`), in transit (exists off loopback, provisioned `-015`, `-026`), at rest (managed volumes and
backups, `-015`). Network segmentation — §6. Least privilege — workload identities, database roles, Kubernetes RBAC.
Container security — §5. Vulnerability and dependency management — §8, with a weekly scheduled re-scan of the last
released image. API security — the route allow-lists, request limits, abuse limits. Privileged access — C13. Secure
configuration — the configuration register (`-031`) and the startup guards. Audit protection — T10.

---

## 10. Secrets and key management (8.7)

The lifecycle per class (C6). **No production secret is stored in source control**: gitleaks over history,
`CommittedConfigurationHoldsNoSecretTest` and a manifest policy refusing any committed `kind: Secret` carrying data.

| Class | Storage and access | Rotation | Revocation | Recovery | Audit |
|---|---|---|---|---|---|
| Database credentials (`finapp_app`, `finapp_migrator`, the master user) | Secrets Manager, generated, per environment; the app's identity reads only its own | Managed rotation, alternating users so the old password stays valid until the fleet has rolled; **each alternating user holds exactly the per-table grants made to `finapp_app`** (through membership of a role carrying them, the grants staying in the migrations) — a user missing one grant fails the rehearsal; users created by the bootstrap step (§7) | Rotate immediately; the fleet rolls; sessions on the old user end | Secret versions; the master user recovered through the cloud account | Secrets Manager and KMS access logs |
| Kafka credentials (SCRAM per principal) | Secrets Manager; ACLs per principal | Add the new credential, roll, remove the old | Remove the principal's credential; ACLs refuse | Re-issue | Broker authentication logs |
| Provider API credentials and webhook HMAC secrets | Secrets Manager per provider | Two valid secrets during a window (verify against current and previous), then retire the previous | Retire at once; callbacks under it refused | Provider re-issue | Access logs; refused callbacks counted |
| Field-level encryption keys (eight cipher classes, nine instances, eight keys — §3) | Data keys in Secrets Manager wrapped by a KMS key per purpose; the application holds a **key ring** | A new active version for new rows; old versions retained for decryption for as long as data under them exists; **no re-encryption of `INSERT`-only evidence** | A compromised version: stop encrypting with it at once, re-key what the domain allows, record what stays under it | KMS key deletion is protected by a waiting period; versions never exported | KMS usage logs |
| Signing keys (image signing) | Keyless (short-lived certificates from CI's OIDC identity) in the reference; a KMS key in the alternative | Per signature (keyless) | Revoke the identity's trust in the admission policy | Re-sign from CI | The transparency log |
| JWT/JWKS keys | **N/A today**: the platform issues opaque sessions (ADR-0030) and validates no JWT; becomes applicable with workforce OIDC (C13), where the identity provider owns the JWKS | — | — | — | — |
| TLS certificates (ingress, and the CA bundles for PostgreSQL and Kafka) | cert-manager issuing the ingress certificate (a local CA in `kind`, a public issuer in a real environment); the managed services' CA bundles from the provider | cert-manager renews ahead of expiry; an alert on days-to-expiry | Revoke and re-issue | Re-issue | cert-manager events |

**Separation of environments**: separate accounts, KMS keys and secret paths per environment; a workload identity reads
only its own environment's secrets; local development keeps ADR-0020's marked default, confined to loopback by the
existing guards, so a local value can never start a deployed instance.

---

## 11. Observability and operations (8.8)

| Signal | Today | Phase 15 | Task |
|---|---|---|---|
| Structured logging | ECS JSON, correlation and trace ids, default-deny redaction | Shipped by the collector, searchable, retained by class; a scrubbing stage as a second net; a log-needle test over a rehearsal's whole output | `-023` |
| Metrics | Prometheus registry, naming contract, 136 domain alerts | Every gauge off the scrape thread; platform alerts (trial balance, outbox, inbox, HTTP, pool, certificate expiry, `up` per instance) | `-024`, `-025` |
| Distributed tracing | Spans recorded and dropped | OTLP export to the collector; tail policy keeping errors, slow spans and money-moving routes; span attributes an allow-list; one end-to-end trace proven | `-023` |
| Dashboards | One baseline | An SLO dashboard per critical flow, provisioned read-only | `-025` |
| Alerts | Domain alerts, no routing | Alertmanager: severity `page` and `ticket`, every alert linking its runbook section (a test), a placeholder receiver *(built by `-025`, 2026-11-08: `docs/architecture/SLOS.md`)* | `-025` |
| SLIs, SLOs, error budgets | Latency objectives in code only | §11.1 | `-025` |
| Business and financial metrics | Per-domain series; amounts never in metrics (ADR-0072) | Unchanged; the financial-correctness signals become paging invariants (§11.1) | `-025` |
| Operational runbooks | Domain runbooks | Platform runbooks, each rehearsed with its result recorded; every domain section §1–§10 rehearsed by a counted sample of at least one procedure (criterion 4 as written, not narrowed) | `-032` |

### 11.1 SLOs (reference targets, recalibrated by Phase 16's load model)

| Flow / signal | SLI | Target (30 days) |
|---|---|---|
| Customer authentication | Non-5xx ratio and latency of the authentication and session routes | 99.9%; p95 within the route's documented objective |
| Internal transfer | Non-5xx ratio and latency of `POST /v1/transfers` | 99.9%; p99 ≤ 1 s |
| Payment and checkout | Non-5xx ratio of payment and checkout commands (a provider's answer counted by its modelled outcome, never as an error) | 99.9% |
| Merchant API | Non-5xx ratio of `/v1/merchant/**` | 99.9% |
| Provider callbacks | Accepted-or-deduplicated ratio of `/v1/providers/**` | 99.95% |
| Event freshness | Outbox rows published within 60 s of commit | 99% |
| Consumption | Inbox lag per consumer group below its bound | 99% of minutes |
| Operator surface | Non-5xx ratio of `/v1/operator/**` | 99.5% |

**Correctness signals are not SLOs and have no budget**: a non-zero continuous trial balance
(`finapp.ledger.trial.balance`, `INV-ACC-01` — today alerted by no rule), a failing product proof, projection drift, GL ≠
ledger, an outbox dead letter. Each pages on its first sample. Burn-rate alerts follow the multi-window pattern (a fast
pair paging, a slow pair ticketing); the error-budget policy freezes non-reliability changes to a flow whose budget is
spent, recorded in the runbook.

**Tracing critical financial workflows without exposing data**: a trace carries the correlation and causation ids, route
templates, outcomes, module names and identifiers already public in the API; never an amount (ADR-0072), a name, an
instrument, a credential or free text. The collector drops any attribute outside the allow-list; a test reads a
rehearsal's exported spans for the planted needles of `DATA_CLASSIFICATION.md`.

### 11.2 Planned meters (the names `PlannedMetersExistTest` reads once Phase 15 is `COMPLETE`)

| Meter | Instrument | Task |
|---|---|---|
| `finapp.retention.swept` | counter by bounded table name | `-029` |
| `finapp.deadletter.parked` | gauge by consumer group | `-028` |
| `finapp.config.fingerprint` | info gauge, value 1, the effective financial configuration's hash as its one tag | `-031` |
| `finapp.outbox.deadlettered` | gauge: abandoned events still standing | `-025` |
| `finapp.outbox.oldest.publishable` | gauge in seconds: the oldest row the relay may still publish, the event-freshness SLI | `-025` |
| `finapp.inbox.poll.failures` | counter by outcome (`refused`, `failed`): thrown consumer polls, registered by `-026`, alerted by `-025` | `-026`, `-025` |
| `finapp.inbox.consumer.evictions` | counter: commits a group refused because it had evicted the member | `-025` |
| `finapp.inbox.commits.deferred` | counter: offset commits a rebalance refused, sent after the next poll | `-012` |
| `finapp.bnpl.sweeper.claim.failed` | counter by sweep (`origination`, `servicing`, `dispute`): a failed claim, the page read | `-025` |
| `finapp.credit.scorecard.awaited` | gauge: requests admitted and waiting because no `RETAIL_SCORECARD` is `ACTIVE` | `-025` |
| `finapp.platform.clock.offset` | gauge in seconds, per instance: this instance's clock minus the database's (ADR-0063's Follow-up) | `-025` |

*`finapp.inbox.lag` (`-025`, by module, the consumption SLI) is not a row here: it exists only on an instance whose consumers run, and `InboxConsumersTest` holds its registration; `finapp.deadletter.parked` above is read by `correctness.yml`'s `InboxDeadLetterParked`.*

---

## 12. Database and messaging operations (8.9)

| Practice | Design | Task |
|---|---|---|
| Database migrations | The migrator Job (§5); expand-only lint over every migration since the last release unless it carries a contract marker naming the release from which every reader and writer conforms. **Tightening DDL is a contract change too**: a new `CHECK`, trigger, `UNIQUE` or foreign key, `SET NOT NULL`, `ADD COLUMN … NOT NULL` with no default, and a `REVOKE` can each refuse a write release N − 1 still makes — and this repository adds refusing triggers often; the readiness guard refusing a schema older than the build expects; Flyway's schema-history lock serialising two runs; `lock_timeout` on the migrator's session | `-004`, `-005` |
| Connection pooling | Hikari per instance, `minimum-idle = maximum-pool-size` (exists); **no transaction-mode pooler in Phase 15**. Production takes only transaction-scoped advisory locks (`pg_advisory_xact_lock`, its `_shared` form, `pg_try_advisory_xact_lock`) and transaction-scoped settings (`SET LOCAL`, `set_config(…, true)`), which a transaction pooler preserves. The open question is the driver's **server-side prepared statements** under transaction pooling, to be measured. A PgBouncer evaluation is Phase 16's, by measurement *(read "the outbox relay and several schedules take session-level advisory locks" until the transition's review, its M-1)* | `-009` |
| Connection limits | One value for `max_connections` in Terraform, declared to the application from the same source; replicas, HPA maximum, surge **and terminating pods** checked against `finapp.database.instances` by a manifest policy (§6.1) and measured on `kind` (peak `pg_stat_activity` for `application_name = 'finapp'`) | `-009`, `-015` |
| Backup configuration | Managed automated backups with point-in-time recovery, encrypted under a separate key, retention per environment; locally, a logical backup and restore rehearsal ending in the financial verification | `-015`, `-030` |
| Kafka retention | Per topic class from the catalogue; events are hints and the outbox is the durable source, so retention bounds diagnosis and replay, never truth. **Every topic's `retention.ms` is below `finapp.inbox.retention`** (P14D, `EventingBeans.java:87-95`): the inbox's dedupe must outlive every record the broker can still redeliver. `-026`'s catalogue test asserts it, `-015`'s topics take their retention from the catalogue, and `-029`'s inbox sweep never deletes a record younger than that bound | `-026`, `-015`, `-029` |
| Consumer groups | `finapp.<module>` (exists); manual commit after the inbox record (exists); cooperative rebalancing and bounded session timeouts so a rolling update moves partitions without stopping the group; a rebalance proven harmless in the rehearsal | `-026`, `-012` |
| Schema evolution | A build-time schema catalogue; additive changes only within a major; a new major is a new event type version consumers opt into (C11); `X-TSK-014` (explicit scale on pre-Phase-9 amount events) scheduled here if ratified (ratified by C15; applied by `P15-TSK-002`, 2026-11-08) | `-027` |
| Consumer handler sets | **Payload compatibility does not cover a new handler.** `KafkaEventReceiver` commits the offset of a record no registered handler wants (`UNHANDLED`, `KafkaEventReceiver.java:184-197`). Groups are per module, and each topic carries every event type its module produces. So while release N − 1 and release N share a group, a record of a type only N handles is committed unseen by an N − 1 replica. After a rollback, every such record is committed unseen. A **handler-set catalogue** of `(group, topic, eventType, consumerName)` is diffed against the last release. Adding a handler to an existing group requires one of two things: an **audited replay of that type over the rollout window** (and over any rollback window) after the rollout completes, through `-028`'s door, where the inbox deduplicates what N already applied; or **a new consumer group** whose starting offsets are chosen and recorded, never `earliest` over the whole retention unless the handler is naturally idempotent | `-027`, `-028`, `-012` |
| Dead-letter queues | Outbox: an operator door to retry or discard an abandoned event, discard four-eyes, both audited — emitting the three platform actions registered and never emitted; inbox: a poison record parked after its bounded retries, the group continuing, the parked record counted and alerted | `-028` |
| Message replay | From the outbox by an audited operator command; **bounded by the inbox's dedupe window** (14 days): a replay older than the window is refused unless every consumer of the type is naturally idempotent, which the catalogue records | `-028` |
| Redis availability | **N/A**: no Redis client exists; Redis is not deployed (C3) | — |
| Object storage lifecycle | Versioning, lifecycle transitions and Object Lock on the archive bucket; nothing of the application's | `-015` |
| Retention sweeps | Outbox published rows and inbox records past their bound, leaderless and batched (`SKIP LOCKED`), never a non-terminal row; idempotency records **not swept** until debt row #61 is settled (`journal_entry.idempotency_scope` is not unique, and the record is posting once-ness's second rank) | `-029` |

**Migrations and deployments never silently violate financial consistency or create incompatible versions**: the
schema of release N must serve release N − 1's code (expand/contract, tightening DDL included); the new code refuses
readiness on an older schema; the rehearsal runs N − 1 against N's schema with the financial census before rolling; and
a handler added to an existing consumer group is replayed over the rollout window or given its own group. So two
versions can coexist for as long as a rollout lasts. **A rollback is safe only to a release no later contract marker
excludes**: a migration carrying a marker names the first release whose code conforms to it, and the promotion script
refuses a rollback below that release. Past it, the way back is a forward fix. *(Read "a rollback is always to a
version the schema still serves" until the transition's review, its M-2 and I-2.)*

---

## 13. Multi-instance production execution (8.10)

Every replica runs every role (C5). No single-instance assumption is introduced to simplify operations: **no Kubernetes
`CronJob` carries financial work** (a CronJob controller can start a job twice and is a single-instance scheduler by
construction), no leader election, no sticky session, no in-process cache authoritative for anything.

| Concern | How the deployed fleet stays correct | Proven by |
|---|---|---|
| Concurrent execution | All authoritative state and every arbitration in PostgreSQL (unchanged) | The phases' counted contention tests |
| Cluster-safe schedules | Leaderless claims, advisory locks and leases (unchanged); a killed pod's lease expires and another instance claims | `NoSingleInstanceAssumptionRulesTest`; the rehearsal's kill step (`-012`) |
| Rolling deployments | `maxUnavailable: 0`, readiness-gated; N − 1 and N coexist on one schema | `-005`, `-012` |
| Consumer rebalances | Commit after the inbox record; a redelivered record is deduplicated; cooperative rebalancing | `-012`, `-026` |
| Graceful shutdown | `preStop` pause, graceful HTTP, schedules drained within a bound, consumers closed, the pool closed last | `-008` |
| Connection draining | The ingress stops routing on readiness `OUT_OF_SERVICE`; in-flight requests complete within the grace | `-008`, `-012` |
| Distributed state | Sessions, idempotency, leases, outbox and inbox in PostgreSQL; per-instance caches non-authoritative and bounded | `DISTRIBUTED_EXECUTION.md` §3 |
| Idempotent recovery | A crash between commit and response is answered by the idempotency record on retry; an unpublished outbox row is re-published by any instance | The phases' tests; the rehearsal's kill step |
| Service restarts | Startup guards; readiness gated on the database and the schema version; no warm-up state | `-005`, `-008` |
| Autoscaling and rollout capacity | Bounded by the connection budget **counting terminating pods** (§6.1); scale-down one pod per grace period | `-009`; the peak measured by `-012` |
| New consumer handlers | Replayed over the rollout and any rollback window, or given a new group (§12) | `-027`, `-028`, `-012` |
| Two migrations at once | Flyway's lock serialises them; the second finds nothing to do | `-004` |
| Secret rotation | Alternating database users and two valid HMAC secrets during a window, so a fleet half-rolled works | `-020` |

**The ten-instance answer for the operational architecture: `PASS` by design**, to be proven by `-012`'s counted
rehearsal (three replicas on `kind`, a rolling update and a pod kill under a scripted workload, the census at the end:
trial balance zero per currency, one effect per idempotency key, the outbox drained, no orphaned lease, GL = ledger) and
by `-020` and `-028`. The ten-instance storm is `P15-TST-001`, `POSTPONED` (PI-004).

---

## 14. Configuration and environment management (8.11)

| Environment | Runs | Configuration | Secrets |
|---|---|---|---|
| `local` | `compose.yaml` dependencies, the application on the host | `application.yaml` defaults | The marked local default, confined to loopback (ADR-0020) |
| `test` | Testcontainers | The test overlay (`app/src/test/resources`) | Test-only values |
| `ci` | `kind`, three replicas, single-node PostgreSQL and Kafka (test-only) | The `kind` overlay | Generated per run, never committed |
| `staging` | Managed cloud, simulated providers | The `staging` overlay | Secrets Manager (staging account) |
| `production` | Managed cloud | The `production` overlay | Secrets Manager (production account) |

**One mechanism**: the application reads its configuration from the environment; Kustomize overlays supply non-secret
values, the secrets manager supplies secrets. **No Spring profile** decides behaviour: an environment differs by values,
never by a code path — the guards key on loopback, not on a profile name (`DatabaseCredentialGuard`).

**The configuration register** (`-031`): every `finapp.*` property — and every Spring property an overlay sets — in one
document with its class (`SECRET`, `FINANCIAL`, `OPERATIONAL`), its default, and its source per environment; a test holds
it both ways against the code. **Configuration that affects financial behaviour, limits, pricing, routing or risk policy**
already lives as versioned, four-eyes data in PostgreSQL wherever it decides money (fee schedules, terms, policies, rule
sets, mapping versions); a property classed `FINANCIAL` (for example a lock grace, a staleness bound, a fail-safe
timeout) changes only through the production overlay's two-person approval. It **must be safe while replicas hold
mixed values** — during a rollout one replica judges with the old value and another with the new — and a property for which
that is not safe belongs in versioned database data, not in a property. It is published as a fingerprint
(`finapp.config.fingerprint`), and an alert fires when replicas disagree **for longer than a rollout** (its `for:`
longer than the longest rolling update the overlay allows), so a rollout's expected disagreement never pages.

---

## 15. Production readiness (8.12)

*Written as [`PRODUCTION_READINESS.md`](../architecture/PRODUCTION_READINESS.md) (`P15-TSK-001`, 2026-11-08): the twelve
dimensions below as checkable clauses, a multi-instance row (`-034`'s entry), one table per unit and per module carrying
this section's reading of today, the ruling and evidence columns left for `-034`.*

**A unit is production-ready when every row below holds with evidence** — not when its container starts. `P15-TSK-001`
writes the standard; `P15-TSK-034` fills it per unit and per module from the tasks' evidence.

| Dimension | The bar |
|---|---|
| Deployment | Built by CI from a commit, signed, deployed by digest through GitOps, rolled and rolled back on `kind` |
| Health checks | Liveness is the process alone; readiness includes the database and the schema version; a startup probe |
| Logging | Structured, correlated, shipped, retained; the needle test clean |
| Metrics | Registered per the naming contract; gauges off the scrape thread; scraped from the management port |
| Tracing | Exported; attributes allow-listed; one critical flow traced end to end |
| Alerting | SLO burn-rate and correctness alerts routed; every alert names its runbook section |
| Security | Non-root, read-only, no capabilities, NetworkPolicy, workload identity, secrets from the manager, scanned and signed |
| Resource limits | Requests and limits set; within the namespace quota; the connection budget holds at maximum scale plus surge |
| Failure handling | Graceful shutdown; dependency loss degrades readiness, never liveness; idempotent recovery |
| Recovery | Restore rehearsed with the financial verification; rollback rehearsed |
| Ownership | A named owning module (and person or rota in a real environment) per alert and runbook section |
| Runbooks | Written, linked from alerts, rehearsed with the result recorded |

**Where each unit stands today** (the survey's reading; `-034` re-reads it with evidence, and counts every open
Phase 14 remediation entry, `P14-REM-001`…`-011`, as a named gap of the unit it touches):

| Unit | Deployment | Health | Logging | Metrics | Tracing | Alerting | Security | Limits | Failure | Recovery | Ownership | Runbooks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `finapp-app` | Missing | Partial | Partial | Partial | Partial | Partial | Partial | Missing | Partial | Missing | Partial | Partial |
| `finapp-migrator` | Missing | N/A | Missing | N/A | N/A | Missing | Partial | Missing | Partial | Missing | Missing | Missing |
| PostgreSQL | Missing | — | — | Missing | — | Missing | Partial | Partial | — | Missing | Missing | Missing |
| Kafka | Missing | — | — | Missing | — | Missing | Partial | — | — | — | Missing | Missing |
| Telemetry | Local only | Local only | — | Local only | Missing | Missing | Missing | Missing | — | — | Missing | Missing |
| Edge | Missing | — | — | — | — | — | Missing | — | — | — | Missing | Missing |

Per module inside `finapp-app`: alert files exist for accounting, bnpl, credit, fx, lending, risk, and settlement with
reconciliation; **identity, ledger, payments, transfers, checkout, merchant, kyc and consent have dashboard panels and no
alert rule** — `-025` gives each critical flow its SLO alerts and the ledger its correctness alerts.

---

## 16. Testing strategy (C14)

- **Per task**: the hermetic tier of the touched modules, `:app:architectureTest`, the task's `app` tests by filter, the
  guard packages and named guards; the task's new static checks (`kubeconform`, policies, `terraform test`, `promtool`)
  run fresh; database suites written and owed; no fleet `databaseTest`/`kafkaTest` per task.
- **Rehearsals** run on `kind` (Docker required): `-012` (rolling update, rollback, kill), `-020` (rotation), `-023` (the
  trace), `-030` (restore), `-032` (every runbook section of `OPERATIONS_RUNBOOK.md`: the domain sections §1–§10 by a
  counted sample of at least one procedure each, the platform section §11 whole). Each ends in the financial census where money is involved.
- **The exit's database pass**, `P15-TST-002`: every module's `databaseTest`, own-container and `kafkaTest` tiers fresh on
  the final tree (PI-012); PI-013's and PI-014's regression cases green (fixed by `-033`); PI-014's unclassified suites
  classified; PI-015's struck rows performed again or struck for good with a reason.
- **The storm**, `P15-TST-001`: planned and `POSTPONED` (PI-004).

---

## 17. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M15.1 Foundations** | the threat model and readiness standard; the carried items ruled | `-001`, `-002` |
| **M15.2 Images and supply chain** | app and migrator images, minimal and non-root; schema compatibility guarded; scanned, analysed, signed, admitted | `-003`…`-007`, `-041` *(added by the owner, 2026-11-09)* |
| **M15.3 Kubernetes and the edge** | the runtime contract; manifests, namespace security, ingresses; three replicas rehearsed on `kind` | `-008`…`-012` |
| **M15.4 Infrastructure as code** | Terraform foundations and modules, tested with mock providers, never applied | `-013`…`-016` |
| **M15.5 Delivery** | GitOps promotion by digest with the two-person production boundary | `-017` |
| **M15.6 Secrets and keys** | every secret inventoried and delivered; key rings; rotation rehearsed | `-018`…`-020` |
| **M15.7 Application security** | privileged access and abuse limits; callbacks as hints on every rail | `-021`, `-022`, `-036` *(added by `P15-TSK-002`, 2026-11-08, applying C15)*, `-039` *(added by the owner, 2026-11-08)*, `-042` *(added by the owner, 2026-11-09)* |
| **M15.8 Observability** | telemetry pipeline and the end-to-end trace; gauges off the scrape thread; SLOs and routing; the source-join census | `-023`…`-025`, `-035` *(added by the owner, 2026-11-08)* |
| **M15.9 Database and messaging operations** | Kafka for production; event contracts; dead letters and replay; retention sweeps; restore rehearsed | `-026`…`-030` |
| **M15.10 Configuration** | the configuration register and overlays | `-031` |
| **M15.11 Operations and exit** | platform runbooks rehearsed; the postponed defects; the database pass; the readiness review; the exit review (the storm postponed) | `-032`, `-033`, `-037`, `-038` *(added by the owner, 2026-11-08)*, `-040` *(added by the owner, 2026-11-09)*, `-034`, `P15-TST-002`, `P15-TST-001` (`POSTPONED`), `P15-DOC-001` |

**Cut order:** the reporting role of `-021` (C13's cut candidate), then `-027`'s `X-TSK-014` half, then `-016`'s
observability module. **Never cut:** the threat model (`-001`), the schema-compatibility guard (`-005`), graceful
shutdown (`-008`), the `kind` rehearsal (`-012`), secret delivery and key rings (`-018`, `-019`), SLOs (`-025`), the
restore rehearsal (`-030`), the runbooks' rehearsal (`-032`), the database pass.

## 18. Backlog (small, independently verifiable — elaborated in [`BACKLOG.md`](BACKLOG.md) §Phase 15)

| ID | Title | Milestone | Depends on | DoD profiles |
|---|---|---|---|---|
| `P15-TSK-001` | The threat model, the trust boundaries and the readiness standard | M15.1 | Phase 14 confirmed by `P14-GATE` | `DOD-SEC`, `DOD-ARCH`, `DOD-DOC` |
| `P15-TSK-002` | The carried items ruled (C15) | M15.1 | `-001` | `DOD-DOC` |
| `P15-TSK-003` | The application image | M15.2 | `-001` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-004` | The migrator image and the migration Job contract | M15.2 | `-003` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-005` | Schema compatibility across a rolling deploy: the expand-only lint and the schema-version guard | M15.2 | `-004` | `DOD-BUILD`, `DOD-FIN` |
| `P15-TSK-006` | Image scanning, SBOMs and static analysis in CI | M15.2 | `-003` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-007` | Signing, provenance and admission verification | M15.2 | `-006`, `-012` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-008` | The runtime contract: management port, graceful shutdown, drained schedules | M15.3 | `-001` | `DOD-KERNEL`, `DOD-SEC` |
| `P15-TSK-009` | Kubernetes base manifests for the application | M15.3 | `-003`, `-008` | `DOD-BUILD`, `DOD-ARCH` |
| `P15-TSK-010` | Namespace security: RBAC, NetworkPolicy, Pod Security, quotas | M15.3 | `-009` | `DOD-SEC`, `DOD-BUILD` |
| `P15-TSK-011` | The edge: public and operator ingresses, TLS, the route allow-list | M15.3 | `-009` | `DOD-SEC`, `DOD-API` |
| `P15-TSK-012` | The `kind` deployment: N replicas, the migration Job, smoke, rolling update and rollback rehearsed | M15.3 | `-004`, `-005`, `-010`, `-011`, `-026` | `DOD-BUILD`, `DOD-TEST` |
| `P15-TSK-013` | Terraform foundations: layout, state, conventions, CI checks | M15.4 | `-001` | `DOD-BUILD` |
| `P15-TSK-014` | Network and cluster modules | M15.4 | `-013` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-015` | Data-tier modules: PostgreSQL, Kafka, object storage | M15.4 | `-014`, `-026` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-016` | Security modules: KMS, secrets, workload identities, registry | M15.4 | `-014`, `-018` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-017` | GitOps delivery, promotion and the production approval boundary | M15.5 | `-007`, `-012`, `-016` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-018` | The secret inventory and its delivery | M15.6 | `-009` | `DOD-SEC` |
| `P15-TSK-019` | Key rings: rotating field-level keys without losing history | M15.6 | `-018` | `DOD-SEC`, `DOD-KERNEL` |
| `P15-TSK-020` | Credential and certificate rotation, rehearsed | M15.6 | `-012`, `-019` | `DOD-SEC`, `DOD-TEST` |
| `P15-TSK-021` | Privileged access: the operator actor, the refused step-up, the reporting role (C13) | M15.7 | `-001` | `DOD-SEC`, `DOD-ARCH` |
| `P15-TSK-022` | Abuse limits: registration and per-source throttling, cluster-safe | M15.7 | `-011` | `DOD-SEC`, `DOD-API` |
| `P15-TSK-023` | The telemetry pipeline and the end-to-end trace | M15.8 | `-012` | `DOD-OBS`, `DOD-SEC` |
| `P15-TSK-024` | Every gauge off the scrape thread, a rule on `up`, the refresher restartable | M15.8 | `-001` | `DOD-OBS` |
| `P15-TSK-025` | SLOs, error budgets, correctness alerts and routing | M15.8 | `-024` | `DOD-OBS` |
| `P15-TSK-026` | Kafka for production: authenticated TLS, topics as code, rebalance-safe consumers | M15.9 | `-001` | `DOD-EVENT`, `DOD-SEC` |
| `P15-TSK-027` | Event contracts: the schema catalogue and compatibility | M15.9 | `-026` | `DOD-EVENT` |
| `P15-TSK-028` | Dead letters and replay | M15.9 | `-026` | `DOD-EVENT`, `DOD-SEC` |
| `P15-TSK-029` | Retention sweeps and the retention policy (C12) | M15.9 | `-002` | `DOD-KERNEL`, `DOD-DOC` |
| `P15-TSK-030` | Backup and restore with post-restore financial verification | M15.9 | `-012` | `DOD-FIN`, `DOD-TEST` |
| `P15-TSK-031` | The configuration register and the environment overlays | M15.10 | `-008`, `-018` | `DOD-ARCH`, `DOD-SEC` |
| `P15-TSK-032` | Platform runbooks, incident response, and their rehearsal | M15.11 | `-017`, `-020`, `-025`, `-028`, `-030` | `DOD-DOC`, `DOD-OBS` |
| `P15-TSK-033` | The postponed production defects: PI-013 and PI-014 | M15.11 | `-001` | `DOD-FIN` |
| `P15-TSK-035` | The source-join census: every journal entry tied to the domain record that caused it *(added by the owner, 2026-11-08, `THREAT_MODEL.md` §6 item 2)* | M15.8 | `-025` | `DOD-FIN`, `DOD-OBS` |
| `P15-TSK-036` | Callbacks as hints for the payment (Phase 5) and instant (Phase 7) providers *(added by `P15-TSK-002`, 2026-11-08, applying C15: `X-TSK-015` moved into the phase, §25)* | M15.7 | `-001`, `-002` | `DOD-SEC`, `DOD-FIN` |
| `P15-TSK-037` | "The latest" by a stamp, outside the close and the terms: a census *(added by the owner, 2026-11-08, `THREAT_MODEL.md` §6 item 8)* | M15.11 | `-033` | `DOD-FIN`, `DOD-TEST` |
| `P15-TSK-038` | `X-TSK-008`'s five stores on the database's clock *(added by the owner, 2026-11-08)* | M15.11 | `-002` | `DOD-SEC`, `DOD-FIN` |
| `P15-TSK-039` | The wider audit class and the two step-up rulings *(added by the owner, 2026-11-08, `THREAT_MODEL.md` §6 item 10)* | M15.7 | `-021` | `DOD-SEC`, `DOD-API` |
| `P15-TSK-040` | The owner's reach outside reconciliation, settlement and lending: `TRUNCATE` refused and the clock qualified *(added by the owner, 2026-11-09, `THREAT_MODEL.md` §6 item 11)* | M15.11 | `-004`, `-037`, `-038`, `P14-REM-009` | `DOD-FIN`, `DOD-SEC` |
| `P15-TSK-041` | Jackson, `lz4-java` and `snappy-java` moved to their fixed releases *(added by the owner, 2026-11-09, `THREAT_MODEL.md` §6 item 12)* | M15.2 | `-006` | `DOD-BUILD`, `DOD-SEC` |
| `P15-TSK-042` | Operators refused on the public surface *(added by the owner, 2026-11-09, `THREAT_MODEL.md` §6 item 22)* | M15.7 | `-011`, `-022` | `DOD-SEC`, `DOD-API` |
| `P15-TST-002` | The Phase 15 database pass: every tier on the final tree (PI-012, PI-014, PI-015) | M15.11 | `-002`…`-033`, `-035`…`-042`, `P14-REM-009` | `DOD-TEST` |
| `P15-TSK-034` | The production readiness review | M15.11 | `-032`, `P15-TST-002` | `DOD-DOC` |
| `P15-TST-001` | The rolling-deploy storm — `POSTPONED` (PI-004) | M15.11 | all | `DOD-TEST` |
| `P15-DOC-001` | The Phase 15 exit review | M15.11 | all but `TST-001` | `DOD-DOC` |

## 19. Dependency graph and waves

**Waves** (each wave's items depend only on earlier waves; every dependency-free item of a wave may run in parallel, the
owner's standing rule): **A** `-001`; **B** `-002`, `-003`, `-008`, `-013`, `-021`, `-024`, `-026`, `-033`; **C** `-004`,
`-006`, `-009`, `-014`, `-025`, `-027`, `-028`, `-029`, `-036`; **D** `-005`, `-010`, `-011`, `-015`, `-018`, `-035`, `-037`, `-038`, `-039`, `P14-REM-009` *(the last four added by the owner, 2026-11-08)*; **E** `-012`,
`-016`, `-019`, `-022`, `-031`, `-040`, `-041` *(the last two added by the owner, 2026-11-09)*; **F** `-007`, `-020`, `-023`, `-030`, `-042` *(the last added by the owner, 2026-11-09)*; **G** `-017`; **H** `-032`; **I** `P15-TST-002`;
**J** `-034`; **K** `P15-DOC-001` (`P15-TST-001` `POSTPONED`, PI-004).

**Critical path** (bold): **`-001` → `-003` → `-004` → `-005` → `-012` → `-007` → `-017` → `-032` → `TST-002` → `-034`
→ `DOC-001`**; the equal-length branch `-003` → `-009` → `-010` / `-011` → `-012` (with `-008` before `-009`).

**Every edge**: `-002`, `-003`, `-008`, `-013`, `-021`, `-024`, `-026`, `-033` after `-001`; `-029` after `-002`;
`-004` and `-006` after `-003`; `-005` after `-004`; `-009` after `-003` and `-008`; `-010`, `-011` and `-018` after
`-009`; `-012` after `-004`, `-005`, `-010`, `-011` and `-026` (`kind` is not loopback, so `TransportSecurityGuard` needs `verify-full` to PostgreSQL and `KafkaTransportGuard` refuses `PLAINTEXT`: the rehearsal needs `-026`'s authenticated TLS client and a TLS PostgreSQL from the local CA - added by the transition's review, its M-5; the waves and the critical path are unchanged, `-026` being in wave B); `-007` after `-006` and `-012`; `-014` after `-013`; `-015` after
`-014` and `-026`; `-016` after `-014` and `-018`; `-017` after `-007`, `-012` and `-016`; `-019` after `-018`; `-020`
after `-012` and `-019`; `-022` after `-011`; `-023` and `-030` after `-012`; `-025` after `-024`; `-027` and `-028`
after `-026`; `-031` after `-008` and `-018`; `-035` after `-025` (added by the owner, 2026-11-08); `-036` after `-001` and `-002`, whose ruling created it (added by `-002`, 2026-11-08, applying C15; wave C, off the critical path); `-037` after `-033`, `-038` after `-002`, `-039` after `-021` (added by the owner, 2026-11-08; off the critical path); `-040` after `-004`, `-037`, `-038` and `P14-REM-009`, `-041` after `-006` (added by the owner, 2026-11-09; off the critical path); `-042` after `-011` and `-022` (added by the owner, 2026-11-09; off the critical path); `-032` after `-017`, `-020`, `-025`, `-028` and `-030`; `P15-TST-002` after
every item from `-002` to `-033`, `-035` to `-042` and `P14-REM-009`, so that it runs on the final tree; `-034` after `-032` and `P15-TST-002`; `P15-DOC-001` after every item but
`P15-TST-001`.

## 20. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 15)

The seven original criteria made measurable (threat model; scanning; SLOs with runbooks; runbook rehearsal; audit
completeness; restore with trial-balance verification; end-to-end tracing), and one measurable block per area of the
owner's brief: containerisation, Kubernetes deployment, infrastructure as code, CI/CD, security hardening, secrets and
keys, observability, operational readiness, database and messaging operations, multi-instance deployment, configuration,
documentation and runbooks — plus the database pass (PI-012…PI-015) as a named exit item.

## 21. ADRs (next free number ADR-0120; all `Proposed` at the transition)

ADR-0120 the production deployment model (one image, one Deployment of N replicas, a migration Job, managed stateful
dependencies, the edge and network segmentation) · ADR-0121 infrastructure as code · ADR-0122 CI/CD and the supply chain
(images, scanning, signing and admission, GitOps promotion, migration and event compatibility) · ADR-0123 secrets and key
management · ADR-0124 observability, SLOs and error budgets · ADR-0125 configuration and environment management. The next
free number after the transition is ADR-0126. Decisions a task makes inside these (the retention policy, the reporting
role, the dead-letter door) are written by that task, as `P14-TSK-026` wrote ADR-0119.

## 22. What Phase 15 must NOT implement

A business capability of any kind; a change to a financial rule, posting treatment, lifecycle or invariant (other than
the postponed defects' fixes, `-033`, each at its stated cause); a microservice, a second deployable application or a
module split; a Kubernetes `CronJob`, a leader election or any single-instance mechanism; a schema rollback; a
transaction-mode connection pooler (Phase 16 measures it, for server-side prepared statements); a runtime schema registry; Redis; an application use of object storage; a service
mesh; an apply to a real cloud account or a push to a real registry; any claim of compliance or certification; the
purge of financial evidence, agreements or regulatory reports (C12); Phase 16's load, chaos, multi-region or DR work. A
reviewer finding any of it refuses the change.

## 23. Risks

| Risk | Answer |
|---|---|
| Hardening treated as polish | Every area has a measurable exit block; the readiness review fills evidence, not ticks |
| Runbooks written and never rehearsed | `-032`'s acceptance is the recorded rehearsal of each, on `kind` or compose |
| A deployment changing a financial outcome | Expand-only lint, schema-version guard, N − 1 / N rehearsal with the census, restore with the verification |
| Infrastructure code that has never met a real cloud | Stated (§1.1); `terraform test` with mock providers asserts the security properties; apply is the owner's, outside the phase |
| Tool sprawl in the supply chain | Reuse the pinned Trivy for image, configuration and Terraform scans; every new tool pinned by digest and justified in ADR-0122 |
| Scope inflation from about fifty carried debt rows | §25 and `-002`: each row scheduled, re-dated or closed by the owner's ruling, never silently carried |
| The `kind` rehearsal flaking on a loaded machine (the clock steps of PI-013) | The census reads facts from rows, never instants read a statement earlier; flakes classified, never retried into green |
| Compliance language creeping into documents | §1.1; the threat model's and the readiness review's wording checked by the exit review |

## 24. The first task

**`P15-TSK-001` — The threat model, the trust boundaries and the readiness standard** — `READY`, marked by this
transition. It writes `docs/architecture/THREAT_MODEL.md` (§9.1: the boundaries, STRIDE per boundary, each threat's
rating, each mapped to a control with evidence or an accepted risk with an owner), the production readiness standard
(§15's dimensions, as a document the review fills), and a guard test holding the threat register (every control's
evidence exists, every threat has a disposition, no CRITICAL or HIGH without a control or a recorded acceptance). It
corrects the stale security statements §3 lists. First because every later task implements a control this model names
and is judged by this standard; it depends on no owner decision and changes no runtime behaviour.

## 25. The transition's carried items (Phase 15 rows of `CURRENT_STATE.md` debt, `DECISIONS.md` §Deferred and the backlog)

Recommended dispositions, for the owner's ratification (C15); `P15-TSK-002` applies the ratified ones to the rows.

| Item | Recommended disposition |
|---|---|
| Kubernetes and Terraform; a secrets manager; key rotation, KMS, envelope encryption; inbound TLS at the edge; database volume and backup encryption | This phase: §5–§10 |
| The operational endpoints and the scrape endpoint unauthenticated (two rows) | `-008`, `-010`: management port and NetworkPolicy |
| No per-source rate limiting; `POST /v1/registrations` unthrottled | `-022` |
| Every session actor audited as `CUSTOMER`; a refused step-up not audited; refused Phase 7 acts without an audit outcome *(the third closed by `-039`, 2026-11-09)* | `-021` *(2026-11-08: the first two closed there; the refused acts returned to the owner at its gate, `THREAT_MODEL.md` §6 owner item 10)* |
| A separate database role for reporting (Phase 14 C10) | `-021`, C13 (cut candidate) |
| Dead-letter tooling; the three platform actions never emitted | `-028` |
| Outbox retention; inbox retention sweep | `-029` (C12) |
| Audit retention and archival; regulatory report retention; credit evidence purge; the lending and BNPL agreement and evidence purge | C12: the policy and the archival design in `-029`; the purges re-dated to the choice of a real jurisdiction |
| Every gauge off the scrape thread; a platform rule on `up`; the refresher's restart (PI-014 item 2) | `-024` |
| `promtool` in CI | `-025` |
| Redis plaintext with no enforcement | Closed by C3: Redis is not deployed; ADR-0023's guard applies to any future client |
| `X-TSK-015` callbacks as hints for the Phase 5 and 7 providers | Scheduled into this phase on ratification, as its own item after `-001` (security, T8) |
| `X-TSK-014` explicit scale on pre-Phase-9 amount events | `-027` (cut candidate) |
| PI-012…PI-015 | `-033` (PI-013, PI-014's production defects) and `P15-TST-002` (PI-012, PI-014's suites, PI-015) |
| Time decided on an instance's clock: the lock's grace (A4-F1) | **`P14-REM-009`'s** (the completion gate's entry, `PLANNED`, "Phase 15 at the latest"): scheduled by the owner inside this phase, recommended before `P15-TST-002` so its database cases run in the pass *(read "recommended into `-033`" until the gate's merge was taken in, 2026-11-07)* *(built 2026-11-09, the prior-period correction's month with it; its database cases owed to `P15-TST-002`)* |
| `X-TSK-008`'s five stores (time decided across instances) | Recommended into `-033` beside PI-013 (the same class: an instant decided outside the database's clock) |
| `P14-REM-001`…`-011` (the Phase 14 completion gate's remediation entries, `BACKLOG.md` §Phase 14 remediation) | Stay `PLANNED` and outside every Phase 15 milestone, as the gate wrote them; horizons "before production use", "before any real regulatory channel", "before production write-offs and BNPL", and `-009` "Phase 15 at the latest". `-002` names each with its horizon, and `-034`'s readiness review counts each still-open entry as a named gap of the unit it touches. Scheduling them is the owner's |
| The posting once-ness's second rank (debt #61) | Ruled by `-002`: a uniqueness on the posting scope before any idempotency sweep, or the sweep stays excluded |
| An equity account for a non-zero first bank opening | Re-dated to a real bank's go-live: the reference platform has no real bank opening |
| Return-to-sender; a payout return to a closed merchant; funds owed to a closed customer by a parked corridor return | `-032` writes the operating procedure (the value stays parked, aged and alerted); the new operation re-dated to a later payments phase |
| Changing the verified contact channel, with the notifier | Re-dated: a notifier is a business capability Phase 15 does not add |
| Automatic rail availability from observed failure rates | Re-dated to Phase 16 (it needs failure-rate measurement) |
| Production origination on a real bureau; a real FIU, screening-list and device provider | Re-dated to the choice of real providers (a programme non-goal) |
| The reconciliation, settlement, Phase 7, 9 and 10 residues, the risk census rows, the BNPL sweep meter, the lending privacy and trigger residues, the reconciliation `TRUNCATE` | Ruled one by one by `-002`: security- and correctness-class rows scheduled as Phase 15 items, the others re-dated with a reason |
| The stale documents of §3 | Corrected by `-001` (security statements), `-008` (the guard's note), `-021` (`RequiresAssurance`'s note), `-031` (the baseline stack) |

**Applied by `P15-TSK-002` (2026-11-08), as ratified (C15).** Each row now carries its dated disposition in place,
marked **C15** (`P15-TSK-002`, 2026-11-08): 58 rows of `CURRENT_STATE.md` §Known Architectural Debt, 14 of
`DECISIONS.md` §Deliberately Deferred, and `X-TSK-008`, `-014` and `-015` in `BACKLOG.md`. Each task a row is scheduled
into has its entry widened (scope, testing, acceptance, probe): `-004`, `-019` (a note only), `-021`, `-023`, `-025`,
`-027`, `-029`, `-032`, `-033`. `X-TSK-015` is the new item **`P15-TSK-036`** (M15.7, wave C), and `X-TSK-015` is
`CANCELLED` (moved, not dropped). Debt row #61 is ruled **not settled**, so `-029` sweeps no idempotency record. How the
last row of the table was read: **security-class** rows went into the task that fits them. **Correctness-class** rows,
the entry's instants decided off the database's clock, stayed in the phase: `X-TSK-008`'s five stores went to `-033`
*(returned untaken by `-033`'s gate the same day, to `X-TSK-008` and the owner: the ruling reached it after its gate; scheduled by the owner as `P15-TSK-038`, 2026-11-08)*,
and A4-F1 stays `P14-REM-009`'s. **Others** were re-dated with a reason: scale, latency and contention to Phase 16;
functional gaps that need a financial treatment §22 forbids to their own triggers; purges and providers as above. Four
rows are owner items rather than rulings: `P14-REM-009`'s scheduling; `X-TSK-008`'s remainder (the ordering `CHECK`s
and two session leftovers, which no Phase 15 task can take whole); a raw writer closing a reconciliation break with
another break's resolution; and a transfer pre-empting an in-flight instant execution, a money defect whose fix is a
lifecycle change. **Two readings beyond the table's letter**: an instance-clock judgement that decides no financial
instant (the screening retry's pacing, the callback doors' freshness window) is not counted in the correctness class;
and `X-TSK-008`'s remainder is the clock class but not one of §25's five stores, so it went to the owner.
